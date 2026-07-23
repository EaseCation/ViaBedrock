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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PackServiceHttpServerTest {

    private static final String LOOKUP_KEY = "a".repeat(64);
    private static final String ARTIFACT_KEY = "b".repeat(64);

    @Test
    void servesPendingAndStableDownloadsWhileKeepingInternalApiPrivate(
            @TempDir final Path dataDirectory) throws Exception {
        final int publicPort = freePort();
        final int internalPort = freePort();
        final int metricsPort = freePort();
        final PackServiceConfig config = PackServiceStoreTest.config(
                dataDirectory, publicPort, internalPort, metricsPort);
        final PackServiceMetrics metrics = new PackServiceMetrics();
        final PackServiceStore store = new PackServiceStore(config, metrics);
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final List<String> accessLogs = new CopyOnWriteArrayList<>();

        try (PackServiceHttpServer server = new PackServiceHttpServer(
                config, store, metrics, path -> new RandomAccessFile(path.toFile(), "r"), accessLogs::add)) {
            server.start();
            final URI internal = URI.create("http://127.0.0.1:" + internalPort);
            final URI publicBase = URI.create("http://127.0.0.1:" + publicPort);
            final HttpResponse<byte[]> unauthorized = client.send(HttpRequest.newBuilder(
                            internal.resolve("/internal/v1/lookups/" + LOOKUP_KEY))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, unauthorized.statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(internal.resolve("/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());

            final HttpResponse<byte[]> lookup = client.send(HttpRequest.newBuilder(
                            internal.resolve("/internal/v1/lookups/" + LOOKUP_KEY))
                            .header("Authorization", "Bearer test-secret")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, lookup.statusCode());
            final UUID token = UUID.fromString(lookup.headers().firstValue(RemotePackServiceClient.TOKEN).orElseThrow());

            final CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(HttpRequest.newBuilder(
                            publicBase.resolve("/packs/pending/" + token)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            final byte[] zip = PackServiceStoreTest.zip();
            final String sha1 = PackServiceStoreTest.sha1(zip);
            final HttpResponse<byte[]> upload = client.send(HttpRequest.newBuilder(
                            internal.resolve("/internal/v1/pending/" + token))
                            .header("Authorization", "Bearer test-secret")
                            .header(RemotePackServiceClient.LOOKUP_KEY, LOOKUP_KEY)
                            .header(RemotePackServiceClient.ARTIFACT_KEY, ARTIFACT_KEY)
                            .header(RemotePackServiceClient.SHA1, sha1)
                            .header(RemotePackServiceClient.SIZE, Integer.toString(zip.length))
                            .header("Content-Type", "application/zip")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(201, upload.statusCode());
            assertArrayEquals(zip, pending.get(5, TimeUnit.SECONDS).body());

            final URI artifact = publicBase.resolve("/packs/" + sha1 + ".zip");
            final HttpResponse<byte[]> range = client.send(HttpRequest.newBuilder(artifact)
                            .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                            .header("Range", "bytes=0-7").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, range.statusCode());
            assertArrayEquals(java.util.Arrays.copyOf(zip, 8), range.body());
            assertEquals("bytes 0-7/" + zip.length,
                    range.headers().firstValue("Content-Range").orElseThrow());
            final HttpResponse<byte[]> invalidRange = client.send(HttpRequest.newBuilder(artifact)
                            .header("Range", "bytes=" + zip.length + "-").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(416, invalidRange.statusCode());
            assertEquals("bytes */" + zip.length,
                    invalidRange.headers().firstValue("Content-Range").orElseThrow());

            final String etag = range.headers().firstValue("ETag").orElseThrow();
            assertEquals(304, client.send(HttpRequest.newBuilder(artifact)
                            .header("If-None-Match", etag).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(artifact)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(artifact)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(publicBase.resolve("/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(
                            publicBase.resolve("/internal/v1/lookups/" + LOOKUP_KEY)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(publicBase.resolve("/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());

            final HttpResponse<String> renderedMetrics = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + metricsPort + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, renderedMetrics.statusCode());
            assertTrue(renderedMetrics.body().contains("viabedrock_pack_service_cache_lookups_total{result=\"miss\"} 1"));
            assertTrue(renderedMetrics.body().contains("viabedrock_pack_service_downloads_total{result=\"success\"}"));
            assertFalse(renderedMetrics.body().contains(sha1));
            assertFalse(renderedMetrics.body().contains(token.toString()));

            assertTrue(accessLogs.stream().anyMatch(line -> line.contains(
                    "listener=public client=203.0.113.9 method=GET route=artifact status=206 bytes=8")));
            assertTrue(accessLogs.stream().anyMatch(line -> line.contains(
                    "listener=internal") && line.contains("method=POST route=lookup status=202 bytes=0")));
            assertTrue(accessLogs.stream().noneMatch(line -> line.contains("route=health_")));
            assertTrue(accessLogs.stream().noneMatch(line -> line.contains("listener=metrics")));
            assertTrue(accessLogs.stream().noneMatch(line -> line.contains("test-secret")));
            assertTrue(accessLogs.stream().noneMatch(line -> line.contains(LOOKUP_KEY)));
            assertTrue(accessLogs.stream().noneMatch(line -> line.contains(token.toString())));
            assertTrue(accessLogs.stream().noneMatch(line -> line.contains(sha1)));
        }
    }

    @Test
    void artifactOpenFailureReturnsInternalServerError(@TempDir final Path dataDirectory) throws Exception {
        final int publicPort = freePort();
        final int internalPort = freePort();
        final int metricsPort = freePort();
        final PackServiceConfig config = PackServiceStoreTest.config(
                dataDirectory, publicPort, internalPort, metricsPort);
        final PackServiceMetrics metrics = new PackServiceMetrics();
        final PackServiceStore store = new PackServiceStore(config, metrics);
        final PackServiceStore.LookupResult pending = store.lookupOrCreate(LOOKUP_KEY);
        final byte[] zip = PackServiceStoreTest.zip();
        final String sha1 = PackServiceStoreTest.sha1(zip);
        store.publish(pending.token(), LOOKUP_KEY, ARTIFACT_KEY, sha1, zip.length,
                new ByteArrayInputStream(zip));

        try (PackServiceHttpServer server = new PackServiceHttpServer(
                config, store, metrics, ignored -> {
                    throw new IOException("simulated artifact open failure");
                })) {
            server.start();
            final HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + publicPort + "/packs/" + sha1 + ".zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(500, response.statusCode());
            assertEquals(0, response.body().length);
        }
    }

    @Test
    void pendingDownloadsCannotStarveInternalUploads(@TempDir final Path dataDirectory) throws Exception {
        final int publicPort = freePort();
        final int internalPort = freePort();
        final int metricsPort = freePort();
        final PackServiceConfig config = PackServiceStoreTest.config(
                dataDirectory, publicPort, internalPort, metricsPort);
        final PackServiceMetrics metrics = new PackServiceMetrics();
        final PackServiceStore store = new PackServiceStore(config, metrics);
        final HttpClient client = HttpClient.newHttpClient();
        final URI internal = URI.create("http://127.0.0.1:" + internalPort);
        final URI publicBase = URI.create("http://127.0.0.1:" + publicPort);

        try (PackServiceHttpServer server = new PackServiceHttpServer(config, store, metrics)) {
            server.start();
            final String firstKey = "c".repeat(64);
            final String secondKey = "d".repeat(64);
            final UUID first = createPending(client, internal, firstKey);
            final UUID second = createPending(client, internal, secondKey);
            final CompletableFuture<HttpResponse<byte[]>> firstDownload = client.sendAsync(
                    HttpRequest.newBuilder(publicBase.resolve("/packs/pending/" + first)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            final CompletableFuture<HttpResponse<byte[]>> secondDownload = client.sendAsync(
                    HttpRequest.newBuilder(publicBase.resolve("/packs/pending/" + second)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            final byte[] zip = PackServiceStoreTest.zip();
            final String sha1 = PackServiceStoreTest.sha1(zip);
            final HttpResponse<Void> upload = client.send(HttpRequest.newBuilder(
                            internal.resolve("/internal/v1/pending/" + first))
                            .header("Authorization", "Bearer test-secret")
                            .header(RemotePackServiceClient.LOOKUP_KEY, firstKey)
                            .header(RemotePackServiceClient.ARTIFACT_KEY, ARTIFACT_KEY)
                            .header(RemotePackServiceClient.SHA1, sha1)
                            .header(RemotePackServiceClient.SIZE, Integer.toString(zip.length))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(201, upload.statusCode());
            assertArrayEquals(zip, firstDownload.get(5, TimeUnit.SECONDS).body());

            assertEquals(204, client.send(HttpRequest.newBuilder(
                            internal.resolve("/internal/v1/pending/" + second))
                            .header("Authorization", "Bearer test-secret")
                            .header(RemotePackServiceClient.LOOKUP_KEY, secondKey)
                            .DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(504, secondDownload.get(5, TimeUnit.SECONDS).statusCode());
        }
    }

    private static UUID createPending(final HttpClient client, final URI internal,
                                      final String lookupKey) throws Exception {
        final HttpResponse<Void> response = client.send(HttpRequest.newBuilder(
                        internal.resolve("/internal/v1/lookups/" + lookupKey))
                        .header("Authorization", "Bearer test-secret")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(202, response.statusCode());
        return UUID.fromString(response.headers().firstValue(RemotePackServiceClient.TOKEN).orElseThrow());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
