/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PackServiceStore {

    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_ZIP_ENTRIES = 100_000;
    private static final Duration ARTIFACT_TOUCH_INTERVAL = Duration.ofMinutes(10);

    private final PackServiceConfig config;
    private final PackServiceMetrics metrics;
    private final Path artifactsDirectory;
    private final Path mappingsDirectory;
    private final Path pendingDirectory;
    private final Path tempDirectory;
    private final Map<UUID, PendingState> pendingByToken = new ConcurrentHashMap<>();
    private final Map<String, UUID> pendingByLookup = new ConcurrentHashMap<>();
    private final Map<String, Integer> artifactLeases = new HashMap<>();
    private final Map<String, ArtifactStamp> validatedArtifacts = new HashMap<>();

    PackServiceStore(final PackServiceConfig config, final PackServiceMetrics metrics) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.artifactsDirectory = config.dataDirectory().resolve("artifacts");
        this.mappingsDirectory = config.dataDirectory().resolve("mappings");
        this.pendingDirectory = config.dataDirectory().resolve("pending");
        this.tempDirectory = config.dataDirectory().resolve("temp");
        Files.createDirectories(this.artifactsDirectory);
        Files.createDirectories(this.mappingsDirectory);
        Files.createDirectories(this.pendingDirectory);
        Files.createDirectories(this.tempDirectory);
        this.requireSingleFileStore();
        this.loadPending();
        this.maintain();
        if (!this.isReady()) {
            throw new IOException("Pack service data directory is not writable: " + config.dataDirectory());
        }
    }

    synchronized LookupResult lookupOrCreate(final String lookupKey) throws IOException {
        requireSha256(lookupKey, "lookup key");
        final Artifact mapped = this.readMapping(lookupKey);
        if (mapped != null) {
            this.metrics.lookup(true);
            return LookupResult.ready(mapped);
        }

        this.metrics.lookup(false);
        final UUID existingToken = this.pendingByLookup.get(lookupKey);
        if (existingToken != null) {
            final PendingState existing = this.pendingByToken.get(existingToken);
            if (existing != null && !existing.expired(System.currentTimeMillis(), this.config.pendingTimeout())) {
                if (existing.artifact == null) {
                    existing.claims++;
                    existing.updatedAtMillis = System.currentTimeMillis();
                    this.writePending(existing);
                }
                return existing.artifact != null
                        ? LookupResult.ready(existing.artifact)
                        : LookupResult.pending(existingToken);
            }
            this.removePending(existingToken, "timeout");
        }

        final UUID token = UUID.randomUUID();
        final PendingState state = new PendingState(token, lookupKey, System.currentTimeMillis());
        this.writePending(state);
        this.pendingByToken.put(token, state);
        this.pendingByLookup.put(lookupKey, token);
        this.metrics.pendingResult("created");
        return LookupResult.pending(token);
    }

    CompletableFuture<Artifact> await(final UUID token) {
        final PendingState state = this.pendingByToken.get(token);
        if (state == null) {
            return CompletableFuture.failedFuture(new MissingPendingException(token));
        }
        if (state.expired(System.currentTimeMillis(), this.config.pendingTimeout())) {
            try {
                synchronized (this) {
                    this.removePending(token, "timeout");
                }
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.failedFuture(new TimeoutException("Pending resource pack expired"));
        }
        final long age = System.currentTimeMillis() - state.updatedAtMillis;
        final long remainingMillis = Math.max(1L, this.config.pendingTimeout().toMillis() - age);
        final CompletableFuture<Artifact> dependent = new CompletableFuture<>();
        state.completion.whenComplete((artifact, error) -> {
            if (error != null) dependent.completeExceptionally(error);
            else dependent.complete(artifact);
        });
        return dependent.orTimeout(remainingMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    ArtifactLease acquire(final String sha1) throws IOException {
        requireSha1(sha1);
        synchronized (this) {
            final Artifact artifact = this.validateArtifact(null, sha1, -1L);
            if (artifact == null) return null;
            this.touchArtifact(artifact.path());
            this.artifactLeases.merge(sha1, 1, Integer::sum);
            return new ArtifactLease(this, artifact);
        }
    }

    Artifact publish(final UUID token, final String lookupKey, final String artifactKey,
                     final String expectedSha1, final long expectedSize, final InputStream input) throws IOException {
        requireSha256(lookupKey, "lookup key");
        requireSha256(artifactKey, "artifact key");
        requireSha1(expectedSha1);
        if (expectedSize < 0L || expectedSize > this.config.maxUploadBytes()) {
            throw new UploadValidationException("size", "Upload size is outside the configured limit");
        }
        final PendingState state = this.pendingByToken.get(token);
        if (state == null) throw new MissingPendingException(token);
        if (!state.lookupKey.equals(lookupKey)) {
            throw new UploadValidationException("lookup_key", "Pending token does not match lookup key");
        }

        synchronized (state) {
            synchronized (this) {
                if (this.pendingByToken.get(token) != state) throw new MissingPendingException(token);
                state.uploading = true;
            }
            Path temp = null;
            try {
                temp = Files.createTempFile(this.tempDirectory, token + "-", ".zip.tmp");
                final MessageDigest digest = sha1Digest();
                long actualSize;
                try (InputStream limited = new LimitedInputStream(input, this.config.maxUploadBytes());
                     DigestInputStream digested = new DigestInputStream(limited, digest);
                     OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    actualSize = digested.transferTo(output);
                } catch (UploadLimitException e) {
                    throw new UploadValidationException("size", e.getMessage());
                }
                if (actualSize != expectedSize) {
                    throw new UploadValidationException("size",
                            "Upload produced " + actualSize + " bytes, expected " + expectedSize);
                }
                final String actualSha1 = HexFormat.of().formatHex(digest.digest());
                if (!actualSha1.equals(expectedSha1)) {
                    throw new UploadValidationException("sha1", "Upload SHA-1 does not match declared SHA-1");
                }
                validateZip(temp);

                final Artifact artifact;
                synchronized (this) {
                    if (state.artifact != null && (!state.artifact.sha1.equals(expectedSha1)
                            || !state.artifact.artifactKey.equals(artifactKey))) {
                        throw new UploadValidationException(
                                "conflict", "Pending token was already completed with different content");
                    }
                    final Path target = this.artifactPath(expectedSha1);
                    if (Files.isRegularFile(target)) {
                        if (Files.size(target) != expectedSize || Files.mismatch(target, temp) != -1L) {
                            throw new UploadValidationException(
                                    "conflict", "Existing SHA-1 artifact contains different bytes");
                        }
                        Files.delete(temp);
                    } else {
                        moveAtomically(temp, target);
                    }
                    this.touchArtifact(target);
                    artifact = new Artifact(artifactKey, expectedSha1, target, expectedSize);
                    this.validatedArtifacts.put(expectedSha1, stamp(target));
                    this.writeMapping(lookupKey, artifact);
                    state.artifact = artifact;
                    state.updatedAtMillis = System.currentTimeMillis();
                    this.writePending(state);
                    this.pendingByLookup.remove(lookupKey, token);
                }
                state.completion.complete(artifact);
                this.metrics.pendingResult("completed", state.updatedAtMillis - state.createdAtMillis);
                return artifact;
            } catch (IOException | RuntimeException | Error e) {
                if (temp != null) Files.deleteIfExists(temp);
                throw e;
            } finally {
                state.uploading = false;
            }
        }
    }

    synchronized boolean cancel(final UUID token, final String lookupKey) throws IOException {
        final PendingState state = this.pendingByToken.get(token);
        if (state == null) return false;
        if (!state.lookupKey.equals(lookupKey)) {
            throw new UploadValidationException("lookup_key", "Pending token does not match lookup key");
        }
        if (state.artifact != null || state.uploading) return false;
        if (state.claims > 1) {
            state.claims--;
            this.writePending(state);
            return true;
        }
        this.removePending(token, "cancelled");
        return true;
    }

    synchronized void maintain() throws IOException {
        final long now = System.currentTimeMillis();
        for (PendingState state : List.copyOf(this.pendingByToken.values())) {
            if (state.expired(now, this.config.pendingTimeout())) {
                this.removePending(state.token, "timeout");
            }
        }
        try (var files = Files.list(this.tempDirectory)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)
                        && Files.getLastModifiedTime(file).toMillis()
                        < now - this.config.pendingTimeout().toMillis()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        this.evictArtifacts(now);
        this.removeBrokenMappings();
    }

    boolean isReady() {
        if (!Files.isDirectory(this.artifactsDirectory) || !Files.isDirectory(this.mappingsDirectory)
                || !Files.isDirectory(this.pendingDirectory) || !Files.isDirectory(this.tempDirectory)
                || !Files.isWritable(this.tempDirectory)) {
            return false;
        }
        Path probe = null;
        try {
            probe = Files.createTempFile(this.tempDirectory, ".ready-", ".tmp");
            Files.writeString(probe, "ready", StandardCharsets.US_ASCII);
            return Files.size(probe) == 5L;
        } catch (IOException e) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                }
            }
        }
    }

    StorageSnapshot snapshot() {
        try {
            long artifacts = 0L;
            long bytes = 0L;
            try (var files = Files.list(this.artifactsDirectory)) {
                for (Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".zip")) {
                        artifacts++;
                        bytes += Files.size(file);
                    }
                }
            }
            final long mappings;
            try (var files = Files.list(this.mappingsDirectory)) {
                mappings = files.filter(Files::isRegularFile).count();
            }
            final long pending = this.pendingByToken.values().stream()
                    .filter(state -> state.artifact == null).count();
            final FileStore fileStore = Files.getFileStore(this.config.dataDirectory());
            return new StorageSnapshot(artifacts, mappings, pending, bytes,
                    fileStore.getTotalSpace(), fileStore.getUsableSpace(), fileStore.getUnallocatedSpace());
        } catch (IOException e) {
            return new StorageSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    private synchronized Artifact readMapping(final String lookupKey) throws IOException {
        final Path mapping = this.mappingPath(lookupKey);
        if (!Files.isRegularFile(mapping)) return null;
        try {
            final Properties properties = readProperties(mapping);
            final Artifact artifact = this.validateArtifact(
                    requireProperty(properties, "artifactKey"), requireProperty(properties, "sha1"),
                    Long.parseLong(requireProperty(properties, "size")));
            if (artifact == null) Files.deleteIfExists(mapping);
            return artifact;
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(mapping);
            return null;
        }
    }

    private Artifact validateArtifact(final String artifactKey, final String sha1,
                                      final long expectedSize) throws IOException {
        if (!SHA1.matcher(sha1).matches() || artifactKey != null && !SHA256.matcher(artifactKey).matches()) {
            return null;
        }
        final Path artifact = this.artifactPath(sha1);
        if (!Files.isRegularFile(artifact)) return null;
        final long size = Files.size(artifact);
        if (expectedSize >= 0L && size != expectedSize) return null;
        final ArtifactStamp stamp = stamp(artifact);
        if (!stamp.equals(this.validatedArtifacts.get(sha1))) {
            if (!sha1.equals(sha1Hex(artifact))) return null;
            this.validatedArtifacts.put(sha1, stamp);
        }
        return new Artifact(artifactKey, sha1, artifact, size);
    }

    private void loadPending() throws IOException {
        final long now = System.currentTimeMillis();
        try (var files = Files.list(this.pendingDirectory)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".properties")) continue;
                try {
                    final Properties properties = readProperties(file);
                    final UUID token = UUID.fromString(requireProperty(properties, "token"));
                    final String lookupKey = requireProperty(properties, "lookupKey");
                    requireSha256(lookupKey, "lookup key");
                    final long created = Long.parseLong(requireProperty(properties, "createdAtMillis"));
                    final PendingState state = new PendingState(token, lookupKey, created);
                    state.updatedAtMillis = Long.parseLong(properties.getProperty(
                            "updatedAtMillis", Long.toString(created)));
                    state.claims = Math.max(1, Integer.parseInt(properties.getProperty("claims", "1")));
                    final String sha1 = properties.getProperty("sha1");
                    if (sha1 != null) {
                        state.artifact = this.validateArtifact(
                                requireProperty(properties, "artifactKey"), sha1,
                                Long.parseLong(requireProperty(properties, "size")));
                        if (state.artifact == null) {
                            Files.deleteIfExists(file);
                            continue;
                        }
                        this.writeMapping(lookupKey, state.artifact);
                        state.completion.complete(state.artifact);
                    }
                    if (state.expired(now, this.config.pendingTimeout())) {
                        Files.deleteIfExists(file);
                        continue;
                    }
                    this.pendingByToken.put(token, state);
                    if (state.artifact == null) this.pendingByLookup.put(lookupKey, token);
                } catch (IllegalArgumentException e) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private void evictArtifacts(final long now) throws IOException {
        final List<Path> artifacts;
        try (var files = Files.list(this.artifactsDirectory)) {
            artifacts = new ArrayList<>(files.filter(Files::isRegularFile).toList());
        }
        artifacts.sort(Comparator.comparingLong(PackServiceStore::lastModified));
        long totalBytes = 0L;
        for (Path artifact : artifacts) totalBytes += Files.size(artifact);
        final long idleCutoff = now - this.config.cacheIdleTime().toMillis();
        final Set<String> pendingArtifacts = new HashSet<>();
        for (PendingState state : this.pendingByToken.values()) {
            if (state.artifact != null) pendingArtifacts.add(state.artifact.sha1);
        }
        for (Path artifact : artifacts) {
            final String fileName = artifact.getFileName().toString();
            if (!fileName.endsWith(".zip")) continue;
            final String sha1 = fileName.substring(0, fileName.length() - 4);
            if (!SHA1.matcher(sha1).matches() || pendingArtifacts.contains(sha1)
                    || this.artifactLeases.getOrDefault(sha1, 0) > 0) {
                continue;
            }
            final long size = Files.size(artifact);
            if (Files.getLastModifiedTime(artifact).toMillis() < idleCutoff
                    || totalBytes > this.config.cacheBudgetBytes()) {
                if (Files.deleteIfExists(artifact)) {
                    totalBytes -= size;
                    this.validatedArtifacts.remove(sha1);
                    this.metrics.eviction(size);
                }
            }
        }
    }

    private void removeBrokenMappings() throws IOException {
        try (var files = Files.list(this.mappingsDirectory)) {
            for (Path mapping : files.toList()) {
                if (!Files.isRegularFile(mapping)) continue;
                final String fileName = mapping.getFileName().toString();
                if (!fileName.endsWith(".properties")) {
                    Files.deleteIfExists(mapping);
                    continue;
                }
                final String lookupKey = fileName.substring(0, fileName.length() - ".properties".length());
                if (!SHA256.matcher(lookupKey).matches() || this.readMapping(lookupKey) == null) {
                    Files.deleteIfExists(mapping);
                }
            }
        }
    }

    private void writeMapping(final String lookupKey, final Artifact artifact) throws IOException {
        final Properties properties = new Properties();
        properties.setProperty("artifactKey", artifact.artifactKey);
        properties.setProperty("sha1", artifact.sha1);
        properties.setProperty("size", Long.toString(artifact.size));
        writePropertiesAtomically(this.mappingPath(lookupKey), properties);
    }

    private void writePending(final PendingState state) throws IOException {
        final Properties properties = new Properties();
        properties.setProperty("token", state.token.toString());
        properties.setProperty("lookupKey", state.lookupKey);
        properties.setProperty("createdAtMillis", Long.toString(state.createdAtMillis));
        properties.setProperty("updatedAtMillis", Long.toString(state.updatedAtMillis));
        properties.setProperty("claims", Integer.toString(state.claims));
        if (state.artifact != null) {
            properties.setProperty("artifactKey", state.artifact.artifactKey);
            properties.setProperty("sha1", state.artifact.sha1);
            properties.setProperty("size", Long.toString(state.artifact.size));
        }
        writePropertiesAtomically(this.pendingPath(state.token), properties);
    }

    private void writePropertiesAtomically(final Path target, final Properties properties) throws IOException {
        final Path temp = Files.createTempFile(this.tempDirectory, target.getFileName().toString() + "-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, null);
            }
            moveAtomically(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void removePending(final UUID token, final String result) throws IOException {
        final PendingState removed = this.pendingByToken.remove(token);
        if (removed == null) return;
        this.pendingByLookup.remove(removed.lookupKey, token);
        Files.deleteIfExists(this.pendingPath(token));
        if (removed.artifact == null) {
            removed.completion.completeExceptionally(new TimeoutException("Pending resource pack " + result));
            this.metrics.pendingResult(result, System.currentTimeMillis() - removed.createdAtMillis);
        }
    }

    private synchronized void release(final String sha1) {
        final int leases = this.artifactLeases.getOrDefault(sha1, 0);
        if (leases <= 1) this.artifactLeases.remove(sha1);
        else this.artifactLeases.put(sha1, leases - 1);
    }

    private void requireSingleFileStore() throws IOException {
        final FileStore expected = Files.getFileStore(this.artifactsDirectory);
        for (Path path : List.of(this.mappingsDirectory, this.pendingDirectory, this.tempDirectory)) {
            if (!expected.equals(Files.getFileStore(path))) {
                throw new IOException("Pack service directories must share one filesystem for atomic publication");
            }
        }
    }

    private void touchArtifact(final Path artifact) throws IOException {
        final long now = System.currentTimeMillis();
        if (Files.getLastModifiedTime(artifact).toMillis() < now - ARTIFACT_TOUCH_INTERVAL.toMillis()) {
            Files.setLastModifiedTime(artifact, FileTime.fromMillis(now));
            this.validatedArtifacts.put(
                    artifact.getFileName().toString().replace(".zip", ""), stamp(artifact));
        }
    }

    private Path artifactPath(final String sha1) {
        return this.artifactsDirectory.resolve(sha1 + ".zip");
    }

    private Path mappingPath(final String lookupKey) {
        return this.mappingsDirectory.resolve(lookupKey + ".properties");
    }

    private Path pendingPath(final UUID token) {
        return this.pendingDirectory.resolve(token + ".properties");
    }

    private static Properties readProperties(final Path path) throws IOException {
        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requireProperty(final Properties properties, final String name) {
        final String value = properties.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing property " + name);
        return value;
    }

    private static void validateZip(final Path path) throws IOException {
        int files = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final String name = entry.getName();
                final Path normalized = Path.of(name).normalize();
                if (name.isBlank() || name.indexOf('\\') >= 0 || normalized.isAbsolute()
                        || normalized.startsWith("..")) {
                    throw new UploadValidationException("zip", "ZIP contains an unsafe entry path");
                }
                if (!entry.isDirectory() && ++files > MAX_ZIP_ENTRIES) {
                    throw new UploadValidationException("zip", "ZIP contains too many entries");
                }
            }
        } catch (UploadValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new UploadValidationException("zip", "Upload is not a readable ZIP", e);
        }
        if (files == 0) throw new UploadValidationException("zip", "ZIP contains no files");
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha1Hex(final Path path) throws IOException {
        final MessageDigest digest = sha1Digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    private static ArtifactStamp stamp(final Path path) throws IOException {
        return new ArtifactStamp(Files.size(path), Files.getLastModifiedTime(path).toMillis());
    }

    private static long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static void requireSha1(final String value) {
        if (value == null || !SHA1.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SHA-1: " + value);
        }
    }

    private static void requireSha256(final String value, final String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    record Artifact(String artifactKey, String sha1, Path path, long size) {
    }

    record LookupResult(Artifact artifact, UUID token) {
        static LookupResult ready(final Artifact artifact) {
            return new LookupResult(artifact, null);
        }

        static LookupResult pending(final UUID token) {
            return new LookupResult(null, token);
        }

        boolean ready() {
            return this.artifact != null;
        }
    }

    record StorageSnapshot(long artifacts, long mappings, long pending, long artifactBytes,
                           long pvcTotalBytes, long pvcUsableBytes, long pvcFreeBytes) {
    }

    static final class ArtifactLease implements AutoCloseable {
        private final PackServiceStore owner;
        private final Artifact artifact;
        private boolean closed;

        private ArtifactLease(final PackServiceStore owner, final Artifact artifact) {
            this.owner = owner;
            this.artifact = artifact;
        }

        Artifact artifact() {
            return this.artifact;
        }

        @Override
        public synchronized void close() {
            if (this.closed) return;
            this.closed = true;
            this.owner.release(this.artifact.sha1);
        }
    }

    static final class UploadValidationException extends IOException {
        private final String reason;

        UploadValidationException(final String reason, final String message) {
            super(message);
            this.reason = reason;
        }

        UploadValidationException(final String reason, final String message, final Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        String reason() {
            return this.reason;
        }
    }

    static final class MissingPendingException extends IOException {
        MissingPendingException(final UUID token) {
            super("Unknown pending resource pack token: " + token);
        }
    }

    private static final class PendingState {
        private final UUID token;
        private final String lookupKey;
        private final long createdAtMillis;
        private final CompletableFuture<Artifact> completion = new CompletableFuture<>();
        private volatile long updatedAtMillis;
        private volatile Artifact artifact;
        private volatile boolean uploading;
        private int claims = 1;

        private PendingState(final UUID token, final String lookupKey, final long createdAtMillis) {
            this.token = token;
            this.lookupKey = lookupKey;
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = createdAtMillis;
        }

        private boolean expired(final long now, final Duration timeout) {
            return !this.uploading && now - this.updatedAtMillis >= timeout.toMillis();
        }
    }

    private record ArtifactStamp(long size, long modifiedMillis) {
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long read;

        private LimitedInputStream(final InputStream input, final long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            final int value = super.read();
            if (value >= 0) this.accept(1L);
            return value;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            final int value = super.read(buffer, offset, length);
            if (value > 0) this.accept(value);
            return value;
        }

        private void accept(final long amount) throws UploadLimitException {
            this.read += amount;
            if (this.read > this.maximum) {
                throw new UploadLimitException("Upload exceeds the configured byte limit");
            }
        }
    }

    private static final class UploadLimitException extends IOException {
        private UploadLimitException(final String message) {
            super(message);
        }
    }
}
