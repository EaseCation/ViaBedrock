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

import net.raphimc.viabedrock.api.resourcepack.cache.ArtifactKey;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RemotePackServiceClientTest {

    @Test
    void lookupKeyIncludesArtifactVariant() {
        final String announcement = "a".repeat(64);
        final String legacyRotation = RemotePackServiceClient.computeLookupKey(announcement, false);
        assertEquals(64, legacyRotation.length());
        assertEquals(legacyRotation, RemotePackServiceClient.computeLookupKey(announcement, false));
        assertNotEquals(legacyRotation, RemotePackServiceClient.computeLookupKey(announcement, true));
        assertThrows(IllegalArgumentException.class,
                () -> RemotePackServiceClient.computeLookupKey("not-a-digest", false));
    }

    @Test
    void lookupRejectsResponseWithMissingMetadata(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                resource-pack-delivery:
                  mode: remote
                  internal-url: http://pack-service.internal:8081/
                  public-url: https://packs.example.test/
                  shared-secret: test-secret
                """);
        final net.raphimc.viabedrock.ViaBedrockConfig config =
                new net.raphimc.viabedrock.ViaBedrockConfig(
                        configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();
        final RemotePackServiceClient client = new RemotePackServiceClient(
                config, ignored -> response(200));

        assertThrows(IOException.class, () -> client.lookupBlocking("a".repeat(64)));
    }

    @Test
    void lookupClientParsesPendingAndReadyResponses(@TempDir final Path tempDir) throws Exception {
        final int publicPort = freePort();
        final int internalPort = freePort();
        final int metricsPort = freePort();
        final PackServiceConfig serviceConfig = PackServiceStoreTest.config(
                tempDir.resolve("data"), publicPort, internalPort, metricsPort);
        final PackServiceMetrics metrics = new PackServiceMetrics();
        final PackServiceStore store = new PackServiceStore(serviceConfig, metrics);
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                resource-pack-delivery:
                  mode: remote
                  internal-url: http://127.0.0.1:%d/
                  public-url: https://packs.example.test/
                  shared-secret: test-secret
                """.formatted(internalPort));
        final net.raphimc.viabedrock.ViaBedrockConfig config =
                new net.raphimc.viabedrock.ViaBedrockConfig(
                        configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();
        final List<String> logs = new ArrayList<>();
        final RemotePackServiceClient client = new RemotePackServiceClient(
                config, logs::add,
                (message, error) -> logs.add(message + " error=" + error.getClass().getSimpleName()));

        try (PackServiceHttpServer server = new PackServiceHttpServer(serviceConfig, store, metrics)) {
            server.start();
            final String lookupKey = "c".repeat(64);
            final RemotePackServiceClient.Lookup pending = client.lookupBlocking(lookupKey);
            assertFalse(pending.ready());
            assertEquals("https://packs.example.test/packs/pending/" + pending.token(), pending.publicUrl());

            final byte[] zip = PackServiceStoreTest.zip();
            final String sha1 = PackServiceStoreTest.sha1(zip);
            final Path artifactPath = tempDir.resolve(sha1 + ".zip");
            Files.write(artifactPath, zip);
            client.uploadBlocking(pending, new ArtifactKey("d".repeat(64)),
                    new ArtifactRef("d".repeat(64), sha1, artifactPath, zip.length));
            final RemotePackServiceClient.Lookup ready = client.lookupBlocking(lookupKey);
            assertTrue(ready.ready());
            assertEquals(sha1, ready.sha1());
            assertEquals("https://packs.example.test/packs/" + sha1 + ".zip", ready.publicUrl());
            assertTrue(logs.stream().anyMatch(line -> line.contains(
                    "[remote-pack-service] lookup=pending duration_ms=")));
            assertTrue(logs.stream().anyMatch(line -> line.contains(
                    "[remote-pack-service] lookup=ready artifact=" + sha1.substring(0, 12))));
            assertTrue(logs.stream().noneMatch(line -> line.contains("test-secret")));
            assertTrue(logs.stream().noneMatch(line -> line.contains(lookupKey)));
            assertTrue(logs.stream().noneMatch(line -> line.contains(pending.token().toString())));
            assertTrue(logs.stream().noneMatch(line -> line.contains(sha1)));
        }
    }

    @Test
    void retriesUploadOnceAfterConnectionWriteFailure(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                resource-pack-delivery:
                  mode: remote
                  internal-url: http://pack-service.internal:8081/
                  public-url: https://packs.example.test/
                  shared-secret: test-secret
                """);
        final net.raphimc.viabedrock.ViaBedrockConfig config =
                new net.raphimc.viabedrock.ViaBedrockConfig(
                        configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        final byte[] zip = PackServiceStoreTest.zip();
        final String sha1 = PackServiceStoreTest.sha1(zip);
        final Path artifactPath = tempDir.resolve(sha1 + ".zip");
        Files.write(artifactPath, zip);
        final AtomicInteger opened = new AtomicInteger();
        final List<HttpRequest> requests = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final RemotePackServiceClient client = new RemotePackServiceClient(
                config, request -> {
                    requests.add(request);
                    if (opened.getAndIncrement() == 0) {
                        throw new IOException("stale pooled connection");
                    }
                    return response(201);
                }, logs::add,
                (message, error) -> logs.add(message + " error=" + error.getClass().getSimpleName()));
        final UUID token = UUID.randomUUID();
        final RemotePackServiceClient.Lookup lookup = new RemotePackServiceClient.Lookup(
                "a".repeat(64), null, null, -1L, token, token,
                "https://packs.example.test/packs/pending/" + token);
        final ArtifactRef artifact = new ArtifactRef(
                "a".repeat(64), sha1, artifactPath, zip.length);

        client.uploadBlocking(lookup, new ArtifactKey("b".repeat(64)), artifact);

        assertEquals(2, opened.get());
        assertEquals(2, requests.size());
        assertNotSame(requests.get(0), requests.get(1));
        final HttpRequest retried = requests.get(1);
        assertEquals("PUT", retried.method());
        assertEquals(zip.length, retried.bodyPublisher().orElseThrow().contentLength());
        assertEquals("a".repeat(64), retried.headers().firstValue(
                RemotePackServiceClient.LOOKUP_KEY).orElseThrow());
        assertEquals(sha1, retried.headers().firstValue(RemotePackServiceClient.SHA1).orElseThrow());
        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[remote-pack-service] upload=retry artifact=" + sha1.substring(0, 12)
                        + " next_attempt=2 error=IOException")));
        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[remote-pack-service] upload=completed status=201 artifact="
                        + sha1.substring(0, 12) + " bytes=" + zip.length + " attempts=2")));
        assertTrue(logs.stream().noneMatch(line -> line.contains("test-secret")));
        assertTrue(logs.stream().noneMatch(line -> line.contains(token.toString())));
        assertTrue(logs.stream().noneMatch(line -> line.contains(sha1)));
    }

    @Test
    void doesNotRetryUploadAfterHttpFailure(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                resource-pack-delivery:
                  mode: remote
                  internal-url: http://pack-service.internal:8081/
                  public-url: https://packs.example.test/
                  shared-secret: test-secret
                """);
        final net.raphimc.viabedrock.ViaBedrockConfig config =
                new net.raphimc.viabedrock.ViaBedrockConfig(
                        configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        final byte[] zip = PackServiceStoreTest.zip();
        final String sha1 = PackServiceStoreTest.sha1(zip);
        final Path artifactPath = tempDir.resolve(sha1 + ".zip");
        Files.write(artifactPath, zip);
        final AtomicInteger attempts = new AtomicInteger();
        final RemotePackServiceClient client = new RemotePackServiceClient(
                config, ignored -> {
                    attempts.incrementAndGet();
                    return response(503);
                });
        final UUID token = UUID.randomUUID();
        final RemotePackServiceClient.Lookup lookup = new RemotePackServiceClient.Lookup(
                "a".repeat(64), null, null, -1L, token, token,
                "https://packs.example.test/packs/pending/" + token);

        final IOException error = assertThrows(IOException.class, () -> client.uploadBlocking(
                lookup, new ArtifactKey("b".repeat(64)),
                new ArtifactRef("a".repeat(64), sha1, artifactPath, zip.length)));

        assertEquals("Pack service upload failed with HTTP 503", error.getMessage());
        assertEquals(1, attempts.get());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static RemotePackServiceClient.TransportResponse response(final int status) {
        return new RemotePackServiceClient.TransportResponse(
                status, HttpHeaders.of(Map.of(), (name, value) -> true));
    }
}
