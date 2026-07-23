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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    void lookupDisconnectsWhenResponseMetadataIsInvalid(@TempDir final Path tempDir) throws Exception {
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
        final StubConnection connection = new StubConnection();
        final RemotePackServiceClient client = new RemotePackServiceClient(config, ignored -> connection);

        assertThrows(IOException.class, () -> client.lookupBlocking("a".repeat(64)));
        assertTrue(connection.disconnected);
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
                config, url -> (HttpURLConnection) url.openConnection(), logs::add,
                (message, error) -> logs.add(message + " error=" + error.getClass().getSimpleName()));

        try (PackServiceHttpServer server = new PackServiceHttpServer(serviceConfig, store, metrics)) {
            server.start();
            final String lookupKey = "c".repeat(64);
            final RemotePackServiceClient.Lookup pending = client.lookupBlocking(lookupKey);
            assertFalse(pending.ready());
            assertEquals("https://packs.example.test/packs/pending/" + pending.token(), pending.publicUrl());

            final byte[] zip = PackServiceStoreTest.zip();
            final String sha1 = PackServiceStoreTest.sha1(zip);
            store.publish(pending.token(), lookupKey, "d".repeat(64), sha1, zip.length,
                    new ByteArrayInputStream(zip));
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
        final FailingOutputConnection first = new FailingOutputConnection();
        final RecordingUploadConnection second = new RecordingUploadConnection();
        final AtomicInteger opened = new AtomicInteger();
        final List<String> logs = new ArrayList<>();
        final RemotePackServiceClient client = new RemotePackServiceClient(
                config, ignored -> opened.getAndIncrement() == 0 ? first : second, logs::add,
                (message, error) -> logs.add(message + " error=" + error.getClass().getSimpleName()));
        final UUID token = UUID.randomUUID();
        final RemotePackServiceClient.Lookup lookup = new RemotePackServiceClient.Lookup(
                "a".repeat(64), null, null, -1L, token, token,
                "https://packs.example.test/packs/pending/" + token);
        final ArtifactRef artifact = new ArtifactRef(
                "a".repeat(64), sha1, artifactPath, zip.length);

        client.uploadBlocking(lookup, new ArtifactKey("b".repeat(64)), artifact);

        assertEquals(2, opened.get());
        assertTrue(first.disconnected);
        assertTrue(second.disconnected);
        assertArrayEquals(zip, second.output.toByteArray());
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

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class StubConnection extends HttpURLConnection {
        private boolean disconnected;

        private StubConnection() throws Exception {
            super(URI.create("http://pack-service.internal:8081/").toURL());
        }

        @Override
        public int getResponseCode() {
            return HTTP_OK;
        }

        @Override
        public String getHeaderField(final String name) {
            return null;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }

    private static final class FailingOutputConnection extends HttpURLConnection {
        private boolean disconnected;

        private FailingOutputConnection() throws Exception {
            super(URI.create("http://pack-service.internal:8081/failed").toURL());
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(final int value) throws IOException {
                    throw new IOException("stale pooled connection");
                }

                @Override
                public void write(final byte[] values, final int offset, final int length) throws IOException {
                    throw new IOException("stale pooled connection");
                }
            };
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }

    private static final class RecordingUploadConnection extends HttpURLConnection {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean disconnected;

        private RecordingUploadConnection() throws Exception {
            super(URI.create("http://pack-service.internal:8081/success").toURL());
        }

        @Override
        public int getResponseCode() {
            return HTTP_CREATED;
        }

        @Override
        public OutputStream getOutputStream() {
            return this.output;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
