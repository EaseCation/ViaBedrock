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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPackDownloaderTest {

    @Test
    void streamsIntoCallerOwnedOutputWithoutClosingIt() throws Exception {
        final byte[] response = new byte[2 * 1024 * 1024 + 17];
        for (int i = 0; i < response.length; i++) {
            response[i] = (byte) i;
        }
        final FakeConnection connection = new FakeConnection(response, response.length);
        final TrackingOutputStream output = new TrackingOutputStream();

        final long downloaded = new BedrockPackDownloader(url(connection))
                .downloadTo(output, response.length);

        assertEquals(response.length, downloaded);
        assertArrayEquals(response, output.toByteArray());
        assertFalse(output.closed());
        assertTrue(connection.inputClosed());
        assertTrue(connection.disconnected());
    }

    @Test
    void streamsResponseDirectlyToTarget(@TempDir final Path tempDir) throws Exception {
        final byte[] response = new byte[2 * 1024 * 1024];
        Arrays.fill(response, (byte) 0x5A);
        final FakeConnection connection = new FakeConnection(response, response.length);
        final Path target = tempDir.resolve("pack.mcpack.tmp");

        final long downloaded = new BedrockPackDownloader(url(connection)).downloadTo(target, response.length);

        assertEquals(response.length, downloaded);
        assertArrayEquals(response, Files.readAllBytes(target));
        assertTrue(connection.disconnected());
    }

    @Test
    void enforcesLimitAgainstActualBytesWhenContentLengthIsSmaller(@TempDir final Path tempDir) throws Exception {
        final byte[] response = new byte[16 * 1024];
        final FakeConnection connection = new FakeConnection(response, 16L);
        final Path target = tempDir.resolve("oversized.mcpack.tmp");

        final IOException failure = assertThrows(IOException.class,
                () -> new BedrockPackDownloader(url(connection)).downloadTo(target, 4 * 1024L));

        assertTrue(failure.getMessage().contains("size limit"));
        assertFalse(Files.exists(target));
        assertTrue(connection.disconnected());
    }

    @Test
    void rejectsDeclaredOversizeBeforeReadingAndDeletesTarget(@TempDir final Path tempDir) throws Exception {
        final FakeConnection connection = new FakeConnection(new byte[32], 8 * 1024L);
        final Path target = tempDir.resolve("declared-oversized.mcpack.tmp");
        Files.write(target, new byte[]{1, 2, 3});

        assertThrows(IOException.class,
                () -> new BedrockPackDownloader(url(connection)).downloadTo(target, 4 * 1024L));

        assertEquals(0, connection.readCalls());
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsResponseWhoseActualLengthDiffersFromContentLength(@TempDir final Path tempDir) throws Exception {
        final FakeConnection connection = new FakeConnection(new byte[8 * 1024], 4 * 1024L);
        final Path target = tempDir.resolve("length-mismatch.mcpack.tmp");

        final IOException failure = assertThrows(IOException.class,
                () -> new BedrockPackDownloader(url(connection)).downloadTo(target, 16 * 1024L));

        assertTrue(failure.getMessage().contains("Content-Length"));
        assertFalse(Files.exists(target));
    }

    private static URL url(final FakeConnection connection) throws Exception {
        return new URL(null, "memory://resource-pack", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(final URL url) {
                return connection;
            }
        });
    }

    private static final class FakeConnection extends HttpURLConnection {

        private final byte[] response;
        private final long contentLength;
        private int readCalls;
        private boolean inputClosed;
        private boolean disconnected;

        private FakeConnection(final byte[] response, final long contentLength) throws Exception {
            super(new URL("http://resource-pack.invalid"));
            this.response = response;
            this.contentLength = contentLength;
        }

        @Override
        public void connect() {
            this.connected = true;
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
            this.connected = false;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public int getResponseCode() {
            return HTTP_OK;
        }

        @Override
        public long getContentLengthLong() {
            return this.contentLength;
        }

        @Override
        public InputStream getInputStream() {
            this.readCalls++;
            return new ByteArrayInputStream(this.response) {
                @Override
                public void close() {
                    FakeConnection.this.inputClosed = true;
                }
            };
        }

        private int readCalls() {
            return this.readCalls;
        }

        private boolean disconnected() {
            return this.disconnected;
        }

        private boolean inputClosed() {
            return this.inputClosed;
        }

    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() {
            this.closed = true;
        }

        private boolean closed() {
            return this.closed;
        }

    }

}
