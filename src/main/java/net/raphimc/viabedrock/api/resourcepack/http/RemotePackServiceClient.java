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

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.cache.ArtifactKey;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactLease;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class RemotePackServiceClient {

    static final String AUTHORIZATION = "Authorization";
    static final String STATUS = "X-Pack-Status";
    static final String TOKEN = "X-Pack-Token";
    static final String LOOKUP_KEY = "X-Pack-Lookup-Key";
    static final String ARTIFACT_KEY = "X-Pack-Artifact-Key";
    static final String SHA1 = "X-Pack-Sha1";
    static final String SIZE = "X-Pack-Size";
    private static final byte[] LOOKUP_DOMAIN =
            "ViaBedrock-Pack-Service-Lookup-v1\0".getBytes(StandardCharsets.US_ASCII);

    private final String internalBaseUrl;
    private final String publicBaseUrl;
    private final String authorization;
    private final int connectTimeoutMillis;
    private final int requestTimeoutMillis;
    private final ConnectionFactory connectionFactory;
    private final Consumer<String> infoLogger;
    private final BiConsumer<String, Throwable> warningLogger;

    public RemotePackServiceClient(final ViaBedrockConfig config) {
        this(config, url -> (HttpURLConnection) url.openConnection(),
                message -> ViaBedrock.getPlatform().getLogger().info(message),
                (message, error) -> ViaBedrock.getPlatform().getLogger().log(
                        Level.WARNING, message + " error=" + error.getClass().getSimpleName()));
    }

    RemotePackServiceClient(final ViaBedrockConfig config,
                            final ConnectionFactory connectionFactory) {
        this(config, connectionFactory, ignored -> {
        }, (ignored, error) -> {
        });
    }

    RemotePackServiceClient(final ViaBedrockConfig config,
                            final ConnectionFactory connectionFactory,
                            final Consumer<String> infoLogger,
                            final BiConsumer<String, Throwable> warningLogger) {
        Objects.requireNonNull(config, "config");
        this.internalBaseUrl = normalizeBaseUrl(config.getRemotePackServiceInternalUrl(), "internal-url");
        this.publicBaseUrl = normalizeBaseUrl(config.getRemotePackServicePublicUrl(), "public-url");
        final String secret = config.getRemotePackServiceSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Remote resource pack delivery requires a non-empty shared-secret");
        }
        this.authorization = "Bearer " + secret;
        this.connectTimeoutMillis = config.getRemotePackServiceConnectTimeoutMillis();
        this.requestTimeoutMillis = config.getRemotePackServiceRequestTimeoutMillis();
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    public CompletableFuture<Lookup> lookup(final String lookupKey) {
        requireSha256(lookupKey, "lookup key");
        return ViaBedrock.getResourcePackWorkScheduler().submitIo(() -> this.lookupBlocking(lookupKey));
    }

    public CompletableFuture<Void> publish(final Lookup lookup, final ResourcePackStorage storage) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(storage, "storage");
        if (lookup.ready()) {
            return CompletableFuture.completedFuture(null);
        }

        final JavaPackCache cache = Objects.requireNonNull(
                ViaBedrock.getJavaPackCache(), "Java resource pack cache is unavailable");
        return storage.retainRuntimeDuring(() -> {
            final ArtifactKey artifactKey = storage.getExactArtifactKey();
            return ViaBedrock.getResourcePackWorkScheduler().submitIo(() ->
                            cache.getOrBuildLeaseHashed(artifactKey,
                                    target -> ResourcePackHttpServer.convertJavaPackWithAdmission(
                                            cache, storage, target)))
                    .thenCompose(future -> future)
                    .thenCompose(lease -> this.upload(lookup, artifactKey, lease));
        });
    }

    public void cancel(final Lookup lookup) {
        if (lookup == null || lookup.ready()) return;
        ViaBedrock.getResourcePackWorkScheduler().submitIo(() -> {
            final long startedNanos = System.nanoTime();
            HttpURLConnection connection = null;
            try {
                connection = this.open("DELETE", "internal/v1/pending/" + lookup.token());
                connection.setRequestProperty(LOOKUP_KEY, lookup.lookupKey());
                final int status = connection.getResponseCode();
                drain(connection);
                if (status != HttpURLConnection.HTTP_NO_CONTENT && status != HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new IOException("Pack service cancellation failed with HTTP " + status);
                }
                this.logInfo("[remote-pack-service] cancel status=" + status
                        + " duration_ms=" + elapsedMillis(startedNanos));
            } catch (IOException | RuntimeException error) {
                this.logWarning("[remote-pack-service] cancel=failed duration_ms="
                        + elapsedMillis(startedNanos), error);
                throw error;
            } finally {
                if (connection != null) connection.disconnect();
            }
            return null;
        });
    }

    private CompletableFuture<Void> upload(final Lookup lookup, final ArtifactKey artifactKey,
                                           final ArtifactLease lease) {
        return ViaBedrock.getResourcePackWorkScheduler().submitIo(() -> {
            final long startedNanos = System.nanoTime();
            final String artifact = shortArtifact(lease.artifact().hash());
            this.logInfo("[remote-pack-service] upload=started artifact=" + artifact
                    + " bytes=" + lease.artifact().size());
            try (lease) {
                final HttpURLConnection connection = this.open(
                        "PUT", "internal/v1/pending/" + lookup.token());
                try {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(lease.artifact().size());
                    connection.setRequestProperty(LOOKUP_KEY, lookup.lookupKey());
                    connection.setRequestProperty(ARTIFACT_KEY, artifactKey.hex());
                    connection.setRequestProperty(SHA1, lease.artifact().hash());
                    connection.setRequestProperty(SIZE, Long.toString(lease.artifact().size()));
                    connection.setRequestProperty("Content-Type", "application/zip");
                    try (InputStream input = Files.newInputStream(lease.artifact().path());
                         OutputStream output = connection.getOutputStream()) {
                        input.transferTo(output);
                    }
                    final int status = connection.getResponseCode();
                    drain(connection);
                    if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_CREATED) {
                        throw new IOException("Pack service upload failed with HTTP " + status);
                    }
                    this.logInfo("[remote-pack-service] upload=completed status=" + status
                            + " artifact=" + artifact
                            + " bytes=" + lease.artifact().size()
                            + " duration_ms=" + elapsedMillis(startedNanos));
                } finally {
                    connection.disconnect();
                }
            } catch (IOException | RuntimeException error) {
                this.logWarning("[remote-pack-service] upload=failed artifact=" + artifact
                        + " bytes=" + lease.artifact().size()
                        + " duration_ms=" + elapsedMillis(startedNanos), error);
                throw error;
            }
            return null;
        });
    }

    Lookup lookupBlocking(final String lookupKey) throws IOException {
        final long startedNanos = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = this.open("POST", "internal/v1/lookups/" + lookupKey);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                final String artifactKey = requireHeader(connection, ARTIFACT_KEY);
                final String sha1 = requireHeader(connection, SHA1);
                requireSha256(artifactKey, "artifact key");
                requireSha1(sha1);
                final long size = parsePositiveLong(requireHeader(connection, SIZE), "artifact size");
                final UUID id = UUID.nameUUIDFromBytes(
                        ("ViaBedrock-Pack-Service:" + artifactKey).getBytes(StandardCharsets.UTF_8));
                final Lookup lookup = new Lookup(lookupKey, artifactKey, sha1, size, null, id,
                        this.publicBaseUrl + "packs/" + sha1 + ".zip");
                drain(connection);
                this.logInfo("[remote-pack-service] lookup=ready artifact=" + shortArtifact(sha1)
                        + " bytes=" + size
                        + " duration_ms=" + elapsedMillis(startedNanos));
                return lookup;
            }
            if (responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                final UUID token;
                try {
                    token = UUID.fromString(requireHeader(connection, TOKEN));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Pack service returned an invalid pending token", e);
                }
                final Lookup lookup = new Lookup(lookupKey, null, null, -1L, token, token,
                        this.publicBaseUrl + "packs/pending/" + token);
                drain(connection);
                this.logInfo("[remote-pack-service] lookup=pending duration_ms="
                        + elapsedMillis(startedNanos));
                return lookup;
            }
            drain(connection);
            throw new IOException("Pack service lookup failed with HTTP " + responseCode);
        } catch (IOException | RuntimeException error) {
            this.logWarning("[remote-pack-service] lookup=failed duration_ms="
                    + elapsedMillis(startedNanos), error);
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection open(final String method, final String relativePath) throws IOException {
        final URL url = URI.create(this.internalBaseUrl + relativePath).toURL();
        final HttpURLConnection connection = this.connectionFactory.open(url);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(this.connectTimeoutMillis);
        connection.setReadTimeout(this.requestTimeoutMillis);
        connection.setUseCaches(false);
        connection.setRequestProperty(AUTHORIZATION, this.authorization);
        connection.setRequestProperty("Connection", "close");
        return connection;
    }

    private static void drain(final HttpURLConnection connection) {
        try {
            final InputStream stream = connection.getErrorStream() != null
                    ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try (stream) {
                    stream.transferTo(OutputStream.nullOutputStream());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String requireHeader(final HttpURLConnection connection, final String name) throws IOException {
        final String value = connection.getHeaderField(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Pack service response is missing " + name);
        }
        return value;
    }

    private static long parsePositiveLong(final String value, final String name) throws IOException {
        try {
            final long parsed = Long.parseLong(value);
            if (parsed < 0L) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Pack service returned an invalid " + name, e);
        }
    }

    private static String normalizeBaseUrl(final String raw, final String name) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Remote resource pack delivery requires " + name);
        }
        final URI uri = URI.create(raw);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid remote resource pack " + name + ": " + raw);
        }
        final String normalized = uri.toString();
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    public static String computeLookupKey(final String announcementFingerprint,
                                          final boolean supportsFreeRotation) {
        requireSha256(announcementFingerprint, "announcement fingerprint");
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LOOKUP_DOMAIN);
            digest.update(HexFormat.of().parseHex(announcementFingerprint));
            digest.update((byte) (JavaPackCache.CONVERTER_VERSION >>> 24));
            digest.update((byte) (JavaPackCache.CONVERTER_VERSION >>> 16));
            digest.update((byte) (JavaPackCache.CONVERTER_VERSION >>> 8));
            digest.update((byte) JavaPackCache.CONVERTER_VERSION);
            final byte[] rewriter = ResourcePackRewriter.rewriterFingerprint().getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (rewriter.length >>> 24));
            digest.update((byte) (rewriter.length >>> 16));
            digest.update((byte) (rewriter.length >>> 8));
            digest.update((byte) rewriter.length);
            digest.update(rewriter);
            digest.update((byte) (supportsFreeRotation ? 1 : 0));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void requireSha256(final String value, final String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    private static void requireSha1(final String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid SHA-1: " + value);
        }
    }

    private void logInfo(final String message) {
        try {
            this.infoLogger.accept(message);
        } catch (RuntimeException ignored) {
        }
    }

    private void logWarning(final String message, final Throwable error) {
        try {
            this.warningLogger.accept(message, error);
        } catch (RuntimeException ignored) {
        }
    }

    private static long elapsedMillis(final long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static String shortArtifact(final String sha1) {
        return sha1.substring(0, 12);
    }

    public record Lookup(String lookupKey, String artifactKey, String sha1, long size,
                         UUID token, UUID id, String publicUrl) {

        public Lookup {
            requireSha256(lookupKey, "lookup key");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(publicUrl, "publicUrl");
            if (sha1 == null) {
                Objects.requireNonNull(token, "token");
                if (artifactKey != null || size != -1L) {
                    throw new IllegalArgumentException("Pending lookup must not include artifact metadata");
                }
            } else {
                requireSha1(sha1);
                requireSha256(artifactKey, "artifact key");
                if (size < 0L || token != null) {
                    throw new IllegalArgumentException("Ready lookup has invalid artifact metadata");
                }
            }
        }

        public boolean ready() {
            return this.sha1 != null;
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }
}
