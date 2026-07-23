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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackServiceStoreTest {

    private static final String LOOKUP_KEY = "a".repeat(64);
    private static final String ARTIFACT_KEY = "b".repeat(64);

    @Test
    void publishesAndReloadsContentAddressedArtifact(@TempDir final Path dataDirectory) throws Exception {
        final byte[] zip = zip();
        final String sha1 = sha1(zip);
        final PackServiceStore first = store(dataDirectory);
        final PackServiceStore.LookupResult pending = first.lookupOrCreate(LOOKUP_KEY);
        assertFalse(pending.ready());

        final PackServiceStore.Artifact published = first.publish(
                pending.token(), LOOKUP_KEY, ARTIFACT_KEY, sha1, zip.length,
                new ByteArrayInputStream(zip));
        assertEquals(sha1, published.sha1());
        assertArrayEquals(zip, Files.readAllBytes(dataDirectory.resolve("artifacts").resolve(sha1 + ".zip")));
        assertEquals(published, first.await(pending.token()).join());
        assertEquals(published, first.lookupOrCreate(LOOKUP_KEY).artifact());
        assertEquals(published, first.publish(
                pending.token(), LOOKUP_KEY, ARTIFACT_KEY, sha1, zip.length,
                new ByteArrayInputStream(zip)));

        final PackServiceStore reloaded = store(dataDirectory);
        final PackServiceStore.LookupResult ready = reloaded.lookupOrCreate(LOOKUP_KEY);
        assertTrue(ready.ready());
        assertEquals(ARTIFACT_KEY, ready.artifact().artifactKey());
        assertEquals(sha1, ready.artifact().sha1());
        assertTrue(reloaded.isReady());
    }

    @Test
    void reloadsPendingLookupAndCompletesIt(@TempDir final Path dataDirectory) throws Exception {
        final PackServiceStore first = store(dataDirectory);
        final PackServiceStore.LookupResult pending = first.lookupOrCreate("f".repeat(64));

        final PackServiceStore reloaded = store(dataDirectory);
        final PackServiceStore.LookupResult restored = reloaded.lookupOrCreate("f".repeat(64));
        assertEquals(pending.token(), restored.token());

        final byte[] zip = zip();
        final PackServiceStore.Artifact artifact = reloaded.publish(
                restored.token(), "f".repeat(64), ARTIFACT_KEY, sha1(zip), zip.length,
                new ByteArrayInputStream(zip));
        assertEquals(artifact, reloaded.await(restored.token()).join());
    }

    @Test
    void cancellingOneClaimDoesNotAbortSharedPendingLookup(@TempDir final Path dataDirectory) throws Exception {
        final String lookupKey = "3".repeat(64);
        final PackServiceStore store = store(dataDirectory);
        final PackServiceStore.LookupResult first = store.lookupOrCreate(lookupKey);
        final PackServiceStore.LookupResult second = store.lookupOrCreate(lookupKey);
        assertEquals(first.token(), second.token());

        assertTrue(store.cancel(first.token(), lookupKey));
        final byte[] zip = zip();
        final PackServiceStore.Artifact artifact = store.publish(
                second.token(), lookupKey, ARTIFACT_KEY, sha1(zip), zip.length,
                new ByteArrayInputStream(zip));

        assertEquals(artifact, store.await(second.token()).join());
    }

    @Test
    void expiresPendingLookupAndCreatesFreshToken(@TempDir final Path dataDirectory) throws Exception {
        final PackServiceConfig base = config(dataDirectory, 18080, 18081, 19462);
        final PackServiceConfig expiring = new PackServiceConfig(
                base.publicAddress(), base.internalAddress(), base.metricsAddress(), base.dataDirectory(),
                base.sharedSecret(), base.maxUploadBytes(), Duration.ofMillis(10), base.cacheBudgetBytes(),
                base.cacheIdleTime(), base.maintenanceInterval(), base.workerThreads(),
                base.maxPendingDownloads());
        final PackServiceStore store = new PackServiceStore(expiring, new PackServiceMetrics());
        final PackServiceStore.LookupResult pending = store.lookupOrCreate("1".repeat(64));
        Thread.sleep(30L);

        final ExecutionException error = assertThrows(ExecutionException.class,
                () -> store.await(pending.token()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, error.getCause());
        assertNotEquals(pending.token(), store.lookupOrCreate("1".repeat(64)).token());
    }

    @Test
    void rejectsInvalidSizeHashAndZip(@TempDir final Path dataDirectory) throws Exception {
        final byte[] zip = zip();
        final PackServiceStore store = store(dataDirectory);

        final PackServiceStore.LookupResult wrongSize = store.lookupOrCreate("c".repeat(64));
        final PackServiceStore.UploadValidationException sizeError = assertThrows(
                PackServiceStore.UploadValidationException.class,
                () -> store.publish(wrongSize.token(), "c".repeat(64), ARTIFACT_KEY,
                        sha1(zip), zip.length + 1L, new ByteArrayInputStream(zip)));
        assertEquals("size", sizeError.reason());

        final PackServiceStore.LookupResult wrongHash = store.lookupOrCreate("d".repeat(64));
        final PackServiceStore.UploadValidationException hashError = assertThrows(
                PackServiceStore.UploadValidationException.class,
                () -> store.publish(wrongHash.token(), "d".repeat(64), ARTIFACT_KEY,
                        "0".repeat(40), zip.length, new ByteArrayInputStream(zip)));
        assertEquals("sha1", hashError.reason());

        final byte[] plain = "not a zip".getBytes(StandardCharsets.UTF_8);
        final PackServiceStore.LookupResult wrongZip = store.lookupOrCreate("e".repeat(64));
        final PackServiceStore.UploadValidationException zipError = assertThrows(
                PackServiceStore.UploadValidationException.class,
                () -> store.publish(wrongZip.token(), "e".repeat(64), ARTIFACT_KEY,
                        sha1(plain), plain.length, new ByteArrayInputStream(plain)));
        assertEquals("zip", zipError.reason());

        final byte[] unsafe = zip("../outside.txt");
        final PackServiceStore.LookupResult unsafeZip = store.lookupOrCreate("2".repeat(64));
        final PackServiceStore.UploadValidationException unsafeZipError = assertThrows(
                PackServiceStore.UploadValidationException.class,
                () -> store.publish(unsafeZip.token(), "2".repeat(64), ARTIFACT_KEY,
                        sha1(unsafe), unsafe.length, new ByteArrayInputStream(unsafe)));
        assertEquals("zip", unsafeZipError.reason());
    }

    @Test
    void logsStateChangesWithoutSensitiveIdentifiers(@TempDir final Path dataDirectory) throws Exception {
        final List<String> logs = new ArrayList<>();
        final PackServiceStore store = new PackServiceStore(
                config(dataDirectory, 18080, 18081, 19462), new PackServiceMetrics(), logs::add);
        final byte[] zip = zip();
        final String sha1 = sha1(zip);

        final PackServiceStore.LookupResult first = store.lookupOrCreate(LOOKUP_KEY);
        final PackServiceStore.LookupResult second = store.lookupOrCreate(LOOKUP_KEY);
        assertTrue(store.cancel(first.token(), LOOKUP_KEY));
        store.publish(second.token(), LOOKUP_KEY, ARTIFACT_KEY, sha1, zip.length,
                new ByteArrayInputStream(zip));
        assertTrue(store.lookupOrCreate(LOOKUP_KEY).ready());

        assertTrue(logs.stream().anyMatch(line -> line.equals(
                "[pack-service-cache] result=miss pending=created")));
        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[pack-service-cache] result=pending_joined claims=2")));
        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[pack-service-pending] result=released remaining_claims=1")));
        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[pack-service-upload] result=created artifact=" + sha1.substring(0, 12))));
        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[pack-service-cache] result=hit artifact=" + sha1.substring(0, 12))));
        assertTrue(logs.stream().noneMatch(line -> line.contains(LOOKUP_KEY)));
        assertTrue(logs.stream().noneMatch(line -> line.contains(ARTIFACT_KEY)));
        assertTrue(logs.stream().noneMatch(line -> line.contains(first.token().toString())));
        assertTrue(logs.stream().noneMatch(line -> line.contains(sha1)));
    }

    @Test
    void logsMaintenanceOnlyWhenFilesAreRemoved(@TempDir final Path dataDirectory) throws Exception {
        final Path mappings = dataDirectory.resolve("mappings");
        Files.createDirectories(mappings);
        Files.writeString(mappings.resolve("broken.tmp"), "broken");
        final List<String> logs = new ArrayList<>();

        new PackServiceStore(config(dataDirectory, 18080, 18081, 19462),
                new PackServiceMetrics(), logs::add);

        assertTrue(logs.stream().anyMatch(line -> line.contains(
                "[pack-service-maintenance] temp_deleted=0 broken_mappings=1 evicted=0")));
    }

    static PackServiceConfig config(final Path dataDirectory, final int publicPort,
                                    final int internalPort, final int metricsPort) {
        return new PackServiceConfig(
                new InetSocketAddress("127.0.0.1", publicPort),
                new InetSocketAddress("127.0.0.1", internalPort),
                new InetSocketAddress("127.0.0.1", metricsPort),
                dataDirectory, "test-secret", 16L * 1024L * 1024L,
                Duration.ofMinutes(5), 64L * 1024L * 1024L,
                Duration.ofDays(1), Duration.ofHours(1), 2, 16);
    }

    static byte[] zip() throws Exception {
        return zip("pack.mcmeta");
    }

    private static byte[] zip(final String entryName) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("{\"pack\":{\"pack_format\":1,\"description\":\"test\"}}"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static String sha1(final byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
    }

    private static PackServiceStore store(final Path dataDirectory) throws Exception {
        return new PackServiceStore(config(dataDirectory, 18080, 18081, 19462), new PackServiceMetrics());
    }
}
