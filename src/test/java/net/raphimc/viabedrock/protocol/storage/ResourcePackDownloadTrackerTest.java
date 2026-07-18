/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackDownloadTrackerTest {

    @Test
    void writesOutOfOrderChunksAtProtocolOffsets() throws Exception {
        final byte[] archive = bytes("abcdefghij");
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download download = tracker.add(
                "pack", archive.length, 4L, sha256(archive), false, PackType.Resources);

        assertNull(download.processDataChunk(2L, 8L, bytes("ij")));
        assertNull(download.processDataChunk(0L, 0L, bytes("abcd")));
        final Path completed = download.processDataChunk(1L, 4L, bytes("efgh"));

        assertEquals(download.tempFile(), completed);
        download.verifyCompletedHash();
        assertArrayEquals(archive, Files.readAllBytes(completed));
        tracker.remove("pack");
        assertFalse(Files.exists(completed));
    }

    @Test
    void rejectsDuplicateOutOfBoundsOverlappingAndWrongLengthChunks() throws Exception {
        final byte[] archive = bytes("abcdefgh");

        final ResourcePackDownloadTracker duplicateTracker = trackerWithFirstChunk(archive);
        final ResourcePackDownloadTracker.Download duplicate = duplicateTracker.get("pack");
        assertThrows(IllegalStateException.class,
                () -> duplicate.processDataChunk(0L, 0L, bytes("abcd")));
        duplicateTracker.remove("pack");

        final ResourcePackDownloadTracker outOfBoundsTracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download outOfBounds = add(outOfBoundsTracker, archive);
        assertThrows(IllegalStateException.class,
                () -> outOfBounds.processDataChunk(2L, 8L, bytes("abcd")));
        outOfBoundsTracker.remove("pack");

        final ResourcePackDownloadTracker overlapTracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download overlap = add(overlapTracker, archive);
        assertThrows(IllegalStateException.class,
                () -> overlap.processDataChunk(1L, 2L, bytes("efgh")));
        overlapTracker.remove("pack");

        final ResourcePackDownloadTracker wrongLengthTracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download wrongLength = add(wrongLengthTracker, archive);
        assertThrows(IllegalStateException.class,
                () -> wrongLength.processDataChunk(0L, 0L, bytes("abc")));
        wrongLengthTracker.remove("pack");
    }

    @Test
    void hashMismatchDeletesTempAndSameKeyCanRetry() throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download wrong = tracker.add(
                "pack", archive.length, 4L, sha256(bytes("different")), false, PackType.Resources);
        wrong.processDataChunk(0L, 0L, bytes("abcd"));
        final Path wrongTemp = wrong.processDataChunk(1L, 4L, bytes("efgh"));

        final IllegalStateException mismatch = assertThrows(
                IllegalStateException.class, wrong::verifyCompletedHash);
        assertTrue(mismatch.getMessage().contains("hash mismatch"));
        assertFalse(Files.exists(wrongTemp));
        tracker.fail("pack", mismatch);

        final ResourcePackDownloadTracker.Download retry = add(tracker, archive);
        retry.processDataChunk(1L, 4L, bytes("efgh"));
        retry.processDataChunk(0L, 0L, bytes("abcd"));
        retry.verifyCompletedHash();
        assertArrayEquals(archive, Files.readAllBytes(retry.tempFile()));
        tracker.remove("pack");
    }

    @Test
    void connectionRemovalDeletesIncompleteTemp() throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download download = add(tracker, archive);
        download.processDataChunk(0L, 0L, bytes("abcd"));
        final Path temp = download.tempFile();

        tracker.onRemove();

        assertFalse(Files.exists(temp));
    }

    @Test
    void stageTrackedAfterConnectionRemovalIsCancelledImmediately() {
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        tracker.onRemove();
        final CompletableFuture<Void> stage = new CompletableFuture<>();

        tracker.trackConnectionStage(stage);

        assertTrue(stage.isCancelled());
    }

    @Test
    void completedFileCanBeTransferredWithoutDeletion() throws Exception {
        final byte[] archive = bytes("abcd");
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download download = tracker.add(
                "pack", archive.length, 4L, sha256(archive), false, PackType.Resources);
        download.processDataChunk(0L, 0L, archive);

        final Path transferred = tracker.takeCompleted("pack");
        tracker.onRemove();

        assertTrue(Files.exists(transferred));
        assertArrayEquals(archive, Files.readAllBytes(transferred));
        Files.delete(transferred);
    }

    @Test
    void legacyCompletedPackLoadsWithoutArchiveStore() throws Exception {
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePack.Key packKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final byte[] archive = legacyPack(packKey);
            final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker(null, scheduler);
            final ResourcePackDownloadTracker.Download download = tracker.add(
                    packKey.toString(), archive.length, archive.length, sha256(archive),
                    false, PackType.Resources, null);
            download.processDataChunk(0L, 0L, archive);

            assertEquals(packKey, download.loadCompletedLegacyPackAsync(scheduler)
                    .get(10, TimeUnit.SECONDS).key());

            tracker.remove(packKey.toString());
            awaitDeletion(download.tempFile());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void doesNotAllocateAnArchiveSizedJavaArray() throws Exception {
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        final long archiveSize = (long) Integer.MAX_VALUE + 1L;

        final ResourcePackDownloadTracker.Download download = tracker.add(
                "large", archiveSize, ResourcePackDownloadTracker.MAX_CHUNK_BYTES,
                new byte[32], false, PackType.Resources);

        assertEquals(archiveSize, download.size());
        assertEquals(256, download.chunkCount());
        assertEquals(0L, Files.size(download.tempFile()));
        tracker.remove("large");
    }

    @Test
    void validatesMetadataBeforeAllocatingDownloadState() throws Exception {
        final byte[] hash = sha256(bytes("archive"));

        assertThrows(IllegalArgumentException.class, () -> ResourcePackDownloadTracker.validateMetadata(
                8L, ResourcePackDownloadTracker.MAX_CHUNK_BYTES + 1L, hash));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackDownloadTracker.validateMetadata(
                ResourcePackDownloadTracker.MAX_CHUNK_COUNT + 1L, 1L, hash));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackDownloadTracker.validateMetadata(
                8L, 4L, new byte[31]));
    }

    @Test
    void serializesAsyncWritesAndIssuesEachChunkRequestOnce() throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker(null, scheduler);
            final ResourcePackDownloadTracker.Download download = add(tracker, archive);

            assertEquals(0L, download.claimNextChunkRequest());
            assertEquals(1L, download.claimNextChunkRequest());
            assertEquals(-1L, download.claimNextChunkRequest());

            final CompletableFuture<Path> second = download.processDataChunkAsync(
                    scheduler, 1L, 4L, bytes("efgh"));
            final CompletableFuture<Path> first = download.processDataChunkAsync(
                    scheduler, 0L, 0L, bytes("abcd"));

            assertNull(second.get(10, TimeUnit.SECONDS));
            assertEquals(download.tempFile(), first.get(10, TimeUnit.SECONDS));
            assertArrayEquals(archive, Files.readAllBytes(download.tempFile()));
            tracker.remove("pack");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void connectionRemovalCancelsLocalStageAndPromotesCasWaiter(@TempDir final Path tempDir) throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(
                    tempDir, scheduler, metrics);
            final ResourcePackArchiveStore.Claim leader = store.claim(sha256(archive));
            final ResourcePackArchiveStore.Claim waiter = store.claim(sha256(archive));
            final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker(store, scheduler);
            final ResourcePackDownloadTracker waiterTracker = new ResourcePackDownloadTracker(store, scheduler);
            final CompletableFuture<Boolean> waiterLeadership = waiterTracker.trackArchiveClaim(waiter);
            final ResourcePackDownloadTracker.Download download = tracker.add(
                    "pack", archive.length, 4L, sha256(archive), false, PackType.Resources, leader);
            final Path temp = download.tempFile();
            final CompletableFuture<Void> followerStage = new CompletableFuture<>();
            tracker.trackConnectionStage(followerStage);

            tracker.onRemove();

            assertTrue(followerStage.isCancelled());
            assertTrue(waiterLeadership.get(10, TimeUnit.SECONDS));
            assertTrue(waiter.leader());
            assertFalse(waiter.path().isDone());
            assertEquals(0L, metrics.getArchiveFailures());
            final Path published = store.publish(waiter, archive);
            assertEquals(published, waiter.path().get(10, TimeUnit.SECONDS));
            waiter.close();
            awaitDeletion(temp);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void transportCancellationPromotesCasWaiterWithoutBackoff(@TempDir final Path tempDir) throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(tempDir, scheduler, metrics);
            final ResourcePackArchiveStore.Claim leader = store.claim(sha256(archive));
            final ResourcePackArchiveStore.Claim follower = store.claim(sha256(archive));
            final ResourcePackDownloadTracker leaderTracker = new ResourcePackDownloadTracker(store, scheduler);
            final ResourcePackDownloadTracker followerTracker = new ResourcePackDownloadTracker(store, scheduler);
            final CompletableFuture<Boolean> followerLeadership = followerTracker.trackArchiveClaim(follower);
            final ResourcePackDownloadTracker.Download download = leaderTracker.add(
                    "pack", archive.length, 4L, sha256(archive), false, PackType.Resources, leader);

            leaderTracker.cancel("pack", new IllegalStateException("chunk request send failed"));

            assertTrue(followerLeadership.get(10, TimeUnit.SECONDS));
            assertTrue(follower.leader());
            assertEquals(0L, metrics.getArchiveFailures());
            store.publish(follower, archive);
            follower.close();
            awaitDeletion(download.tempFile());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void connectionRemovalWithdrawsPendingFollowerBeforePromotion(@TempDir final Path tempDir) throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(tempDir, scheduler, metrics);
            final ResourcePackArchiveStore.Claim leader = store.claim(sha256(archive));
            final ResourcePackArchiveStore.Claim disconnected = store.claim(sha256(archive));
            final ResourcePackArchiveStore.Claim survivor = store.claim(sha256(archive));
            final ResourcePackDownloadTracker disconnectedTracker =
                    new ResourcePackDownloadTracker(store, scheduler);
            final CompletableFuture<Boolean> disconnectedLeadership =
                    disconnectedTracker.trackArchiveClaim(disconnected);

            disconnectedTracker.onRemove();
            store.abandon(leader, new CancellationException("leader disconnected"));

            assertFalse(disconnectedLeadership.get(10, TimeUnit.SECONDS));
            assertTrue(survivor.leadership().get(10, TimeUnit.SECONDS));
            assertTrue(survivor.leader());
            assertEquals(0L, metrics.getArchiveFailures());
            store.publish(survivor, archive);
            survivor.close();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void closedTrackerCannotReacquirePromotedLeadership(@TempDir final Path tempDir) throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final byte[] hash = sha256(archive);
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(tempDir, scheduler, metrics);
            final ResourcePackArchiveStore.Claim leader = store.claim(hash);
            final ResourcePackArchiveStore.Claim follower = store.claim(hash);
            final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker(store, scheduler);
            final CompletableFuture<Boolean> leadership = tracker.trackArchiveClaim(follower);

            store.abandon(leader, new CancellationException("leader disconnected"));
            assertTrue(leadership.get(10, TimeUnit.SECONDS));
            tracker.onRemove();

            assertThrows(CancellationException.class, () -> tracker.add(
                    "pack", archive.length, 4L, hash, false, PackType.Resources, follower));
            final ResourcePackArchiveStore.Claim retry = store.claim(hash);
            assertTrue(retry.leader());
            assertEquals(0L, metrics.getArchiveFailures());
            store.publish(retry, archive);
            retry.close();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void casHashMismatchDeletesTransferredTempAndBacksOffRetry(@TempDir final Path tempDir) throws Exception {
        final byte[] expected = bytes("abcdefgh");
        final byte[] wrong = bytes("abcdEFGH");
        final ResourcePackWorkScheduler scheduler = scheduler();
        try {
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(
                    tempDir, scheduler, new ResourcePackCacheMetrics());
            final byte[] expectedHash = sha256(expected);
            final ResourcePackArchiveStore.Claim claim = store.claim(expectedHash);
            final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker(store, scheduler);
            final ResourcePackDownloadTracker.Download download = tracker.add(
                    "pack", wrong.length, 4L, expectedHash, false, PackType.Resources, claim);
            download.processDataChunk(0L, 0L, bytes("abcd"));
            download.processDataChunk(1L, 4L, bytes("EFGH"));
            final Path completed = tracker.takeCompleted("pack");

            assertThrows(IllegalStateException.class, () -> store.publish(claim, completed));
            assertFalse(Files.exists(completed));
            final ResourcePackArchiveStore.Claim retry = store.claim(expectedHash);
            assertFalse(retry.leader());
            final ExecutionException backedOff = assertThrows(
                    ExecutionException.class, () -> retry.path().get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rootCause(backedOff));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void failedTakeKeepsIncompleteDownloadOwned() throws Exception {
        final byte[] archive = bytes("abcdefgh");
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        final ResourcePackDownloadTracker.Download download = add(tracker, archive);

        assertThrows(IllegalStateException.class, () -> tracker.takeCompleted("pack"));
        assertSame(download, tracker.get("pack"));
        tracker.remove("pack");
    }

    private static ResourcePackDownloadTracker trackerWithFirstChunk(final byte[] archive) throws Exception {
        final ResourcePackDownloadTracker tracker = new ResourcePackDownloadTracker();
        add(tracker, archive).processDataChunk(0L, 0L, bytes("abcd"));
        return tracker;
    }

    private static ResourcePackDownloadTracker.Download add(
            final ResourcePackDownloadTracker tracker, final byte[] archive) throws Exception {
        return tracker.add("pack", archive.length, 4L, sha256(archive), false, PackType.Resources);
    }

    private static byte[] sha256(final byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] legacyPack(final ResourcePack.Key key) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("""
                    {"format_version":2,"header":{"uuid":"%s","version":[1,0,0],"name":"test"}}
                    """).formatted(key.id()).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static ResourcePackWorkScheduler scheduler() {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers" -> 1;
                    case "getResourcePackCacheIoWorkers" -> 2;
                    case "getResourcePackCacheQueueCapacity" -> 32;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        return new ResourcePackWorkScheduler(config, metrics);
    }

    private static Throwable rootCause(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void awaitDeletion(final Path path) throws Exception {
        for (int i = 0; i < 100 && Files.exists(path); i++) {
            Thread.sleep(10L);
        }
        assertFalse(Files.exists(path));
    }

}
