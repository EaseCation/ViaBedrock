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
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactRef;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class RemotePackServiceClient {

    private static final int UPLOAD_ATTEMPTS = 2;

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
    private final Duration requestTimeout;
    private final Transport transport;
    private final Consumer<String> infoLogger;
    private final BiConsumer<String, Throwable> warningLogger;

    public RemotePackServiceClient(final ViaBedrockConfig config) {
        this(config, createTransport(config),
                message -> ViaBedrock.getPlatform().getLogger().info(message),
                (message, error) -> ViaBedrock.getPlatform().getLogger().log(
                        Level.WARNING, message + " error=" + error.getClass().getSimpleName()));
    }

    RemotePackServiceClient(final ViaBedrockConfig config,
                            final Transport transport) {
        this(config, transport, ignored -> {
        }, (ignored, error) -> {
        });
    }

    RemotePackServiceClient(final ViaBedrockConfig config,
                            final Consumer<String> infoLogger,
                            final BiConsumer<String, Throwable> warningLogger) {
        this(config, createTransport(config), infoLogger, warningLogger);
    }

    RemotePackServiceClient(final ViaBedrockConfig config,
                            final Transport transport,
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
        this.requestTimeout = Duration.ofMillis(config.getRemotePackServiceRequestTimeoutMillis());
        this.transport = Objects.requireNonNull(transport, "transport");
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
            try {
                final HttpRequest request = this.request("internal/v1/pending/" + lookup.token())
                        .header(LOOKUP_KEY, lookup.lookupKey())
                        .DELETE()
                        .build();
                final int status = this.send(request).statusCode();
                if (status != 204 && status != 404) {
                    throw new IOException("Pack service cancellation failed with HTTP " + status);
                }
                this.logInfo("[remote-pack-service] cancel status=" + status
                        + " duration_ms=" + elapsedMillis(startedNanos));
            } catch (IOException | RuntimeException error) {
                this.logWarning("[remote-pack-service] cancel=failed duration_ms="
                        + elapsedMillis(startedNanos), error);
                throw error;
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
                this.uploadBlocking(lookup, artifactKey, lease.artifact());
            } catch (IOException | RuntimeException error) {
                this.logWarning("[remote-pack-service] upload=failed artifact=" + artifact
                        + " bytes=" + lease.artifact().size()
                        + " duration_ms=" + elapsedMillis(startedNanos), error);
                throw error;
            }
            return null;
        });
    }

    void uploadBlocking(final Lookup lookup, final ArtifactKey artifactKey,
                        final ArtifactRef artifact) throws IOException {
        final long startedNanos = System.nanoTime();
        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            final int status;
            try {
                status = this.uploadAttempt(lookup, artifactKey, artifact);
            } catch (IOException error) {
                if (error instanceof InterruptedIOException || attempt == UPLOAD_ATTEMPTS) throw error;
                this.logWarning("[remote-pack-service] upload=retry artifact="
                        + shortArtifact(artifact.hash())
                        + " next_attempt=" + (attempt + 1), error);
                continue;
            }
            if (status != 200 && status != 201) {
                throw new IOException("Pack service upload failed with HTTP " + status);
            }
            this.logInfo("[remote-pack-service] upload=completed status=" + status
                    + " artifact=" + shortArtifact(artifact.hash())
                    + " bytes=" + artifact.size()
                    + " attempts=" + attempt
                    + " duration_ms=" + elapsedMillis(startedNanos));
            return;
        }
    }

    private int uploadAttempt(final Lookup lookup, final ArtifactKey artifactKey,
                              final ArtifactRef artifact) throws IOException {
        final HttpRequest request = this.request("internal/v1/pending/" + lookup.token())
                .header(LOOKUP_KEY, lookup.lookupKey())
                .header(ARTIFACT_KEY, artifactKey.hex())
                .header(SHA1, artifact.hash())
                .header(SIZE, Long.toString(artifact.size()))
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofFile(artifact.path()))
                .build();
        return this.send(request).statusCode();
    }

    Lookup lookupBlocking(final String lookupKey) throws IOException {
        final long startedNanos = System.nanoTime();
        try {
            final TransportResponse response = this.send(this.request("internal/v1/lookups/" + lookupKey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());
            if (response.statusCode() == 200) {
                final String artifactKey = requireHeader(response, ARTIFACT_KEY);
                final String sha1 = requireHeader(response, SHA1);
                requireSha256(artifactKey, "artifact key");
                requireSha1(sha1);
                final long size = parsePositiveLong(requireHeader(response, SIZE), "artifact size");
                final UUID id = UUID.nameUUIDFromBytes(
                        ("ViaBedrock-Pack-Service:" + artifactKey).getBytes(StandardCharsets.UTF_8));
                final Lookup lookup = new Lookup(lookupKey, artifactKey, sha1, size, null, id,
                        this.publicBaseUrl + "packs/" + sha1 + ".zip");
                this.logInfo("[remote-pack-service] lookup=ready artifact=" + shortArtifact(sha1)
                        + " bytes=" + size
                        + " duration_ms=" + elapsedMillis(startedNanos));
                return lookup;
            }
            if (response.statusCode() == 202) {
                final UUID token;
                try {
                    token = UUID.fromString(requireHeader(response, TOKEN));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Pack service returned an invalid pending token", e);
                }
                final Lookup lookup = new Lookup(lookupKey, null, null, -1L, token, token,
                        this.publicBaseUrl + "packs/pending/" + token);
                this.logInfo("[remote-pack-service] lookup=pending duration_ms="
                        + elapsedMillis(startedNanos));
                return lookup;
            }
            throw new IOException("Pack service lookup failed with HTTP " + response.statusCode());
        } catch (IOException | RuntimeException error) {
            this.logWarning("[remote-pack-service] lookup=failed duration_ms="
                    + elapsedMillis(startedNanos), error);
            throw error;
        }
    }

    private HttpRequest.Builder request(final String relativePath) {
        return HttpRequest.newBuilder(URI.create(this.internalBaseUrl + relativePath))
                .timeout(this.requestTimeout)
                .header(AUTHORIZATION, this.authorization);
    }

    private TransportResponse send(final HttpRequest request) throws IOException {
        try {
            return this.transport.send(request);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            final InterruptedIOException interrupted = new InterruptedIOException(
                    "Interrupted while waiting for the pack service response");
            interrupted.initCause(error);
            throw interrupted;
        }
    }

    private static String requireHeader(final TransportResponse response, final String name) throws IOException {
        final String value = response.headers().firstValue(name).orElse(null);
        if (value == null || value.isBlank()) {
            throw new IOException("Pack service response is missing " + name);
        }
        return value;
    }

    private static Transport createTransport(final ViaBedrockConfig config) {
        Objects.requireNonNull(config, "config");
        final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getRemotePackServiceConnectTimeoutMillis()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return request -> {
            final HttpResponse<Void> response = client.send(
                    request, HttpResponse.BodyHandlers.discarding());
            return new TransportResponse(response.statusCode(), response.headers());
        };
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
    interface Transport {
        TransportResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, HttpHeaders headers) {

        TransportResponse {
            Objects.requireNonNull(headers, "headers");
        }
    }
}
