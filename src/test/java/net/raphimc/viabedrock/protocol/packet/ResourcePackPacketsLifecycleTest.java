/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArchiveDigest;
import net.raphimc.viabedrock.api.resourcepack.http.RemotePackServiceClient;
import net.raphimc.viabedrock.protocol.storage.ResourcePackLoadStateTracker;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackPacketsLifecycleTest {

    @Test
    void eventLoopRejectionRunsLeaseCleanup() {
        final AtomicInteger taskCalls = new AtomicInteger();
        final AtomicInteger cleanupCalls = new AtomicInteger();

        final RejectedExecutionException rejection = ResourcePackPackets.executeOnEventLoop(
                task -> {
                    throw new RejectedExecutionException("event loop stopped");
                }, taskCalls::incrementAndGet, cleanupCalls::incrementAndGet);

        assertNotNull(rejection);
        assertEquals(0, taskCalls.get());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void acceptedEventLoopTaskDoesNotRunRejectionCleanup() {
        final AtomicInteger taskCalls = new AtomicInteger();
        final AtomicInteger cleanupCalls = new AtomicInteger();

        final RejectedExecutionException rejection = ResourcePackPackets.executeOnEventLoop(
                Runnable::run, taskCalls::incrementAndGet, cleanupCalls::incrementAndGet);

        assertNull(rejection);
        assertEquals(1, taskCalls.get());
        assertEquals(0, cleanupCalls.get());
    }

    @Test
    void completedBuildWaitsForRemoteLookupBeforePublication() throws Exception {
        final CompletableFuture<RemotePackServiceClient.Lookup> lookup = new CompletableFuture<>();
        final CompletableFuture<String> ready = ResourcePackPackets.awaitRemoteLookup(
                CompletableFuture.completedFuture("built"), lookup);

        assertFalse(ready.isDone());
        final UUID token = UUID.randomUUID();
        lookup.complete(new RemotePackServiceClient.Lookup(
                "a".repeat(64), null, null, -1L, token, token,
                "https://packs.example.test/packs/pending/" + token));

        assertEquals("built", ready.get(5, TimeUnit.SECONDS));
    }

    @Test
    void remoteLookupFailurePreventsPublication() {
        final CompletableFuture<RemotePackServiceClient.Lookup> lookup = new CompletableFuture<>();
        final CompletableFuture<String> ready = ResourcePackPackets.awaitRemoteLookup(
                CompletableFuture.completedFuture("built"), lookup);

        lookup.completeExceptionally(new java.io.IOException("lookup failed"));

        assertThrows(java.util.concurrent.CompletionException.class, ready::join);
    }

    @Test
    void detachedTimeoutDoesNotCancelSharedSourceAndCleansLateValue() throws Exception {
        final ScheduledThreadPoolExecutor timeoutScheduler = timeoutScheduler();
        try {
            final CompletableFuture<String> source = new CompletableFuture<>();
            final AtomicInteger timeoutCleanup = new AtomicInteger();
            final AtomicInteger lateCleanup = new AtomicInteger();
            final CompletableFuture<String> bounded = ResourcePackPackets.detachedTimeout(
                    source, 10L, TimeUnit.MILLISECONDS, timeoutCleanup::incrementAndGet,
                    ignored -> lateCleanup.incrementAndGet(), timeoutScheduler);

            final ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> bounded.get(5, TimeUnit.SECONDS));
            assertEquals(TimeoutException.class, failure.getCause().getClass());
            assertEquals(1, timeoutCleanup.get());
            assertFalse(source.isDone());
            assertFalse(source.isCancelled());

            source.complete("late");
            assertEquals(1, lateCleanup.get());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void detachedTimeoutTransfersOnTimeValueWithoutCleanup() throws Exception {
        final ScheduledThreadPoolExecutor timeoutScheduler = timeoutScheduler();
        try {
            final AtomicInteger timeoutCleanup = new AtomicInteger();
            final AtomicInteger lateCleanup = new AtomicInteger();
            final CompletableFuture<String> bounded = ResourcePackPackets.detachedTimeout(
                    CompletableFuture.completedFuture("ready"), 10L, TimeUnit.MILLISECONDS,
                    timeoutCleanup::incrementAndGet, ignored -> lateCleanup.incrementAndGet(),
                    timeoutScheduler);

            assertEquals("ready", bounded.get(5, TimeUnit.SECONDS));
            Thread.sleep(30L);
            assertEquals(0, timeoutCleanup.get());
            assertEquals(0, lateCleanup.get());
            assertTrue(timeoutScheduler.getQueue().isEmpty());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void detachedTimeoutCancelsLongTimerAfterSourceCompletes() throws Exception {
        final ScheduledThreadPoolExecutor timeoutScheduler = timeoutScheduler();
        try {
            final CompletableFuture<String> source = new CompletableFuture<>();
            final CompletableFuture<String> bounded = ResourcePackPackets.detachedTimeout(
                    source, 1L, TimeUnit.DAYS, () -> {
                    }, ignored -> {
                    }, timeoutScheduler);

            source.complete("ready");

            assertEquals("ready", bounded.get(5, TimeUnit.SECONDS));
            assertTrue(timeoutScheduler.getQueue().isEmpty());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void disconnectedWaiterDoesNotCancelSharedBuildAndCleansLateValue() {
        final CompletableFuture<String> source = new CompletableFuture<>();
        final CompletableFuture<Void> disconnected = new CompletableFuture<>();
        final AtomicInteger lateCleanup = new AtomicInteger();
        final CompletableFuture<String> dependent = ResourcePackPackets.detachedCancellation(
                source, disconnected, ignored -> lateCleanup.incrementAndGet());

        disconnected.completeExceptionally(new java.util.concurrent.CancellationException("disconnected"));
        assertThrows(java.util.concurrent.CancellationException.class, dependent::join);
        assertFalse(source.isDone());
        assertFalse(source.isCancelled());

        source.complete("late");
        assertEquals(1, lateCleanup.get());
    }

    @Test
    void preclosedWaiterWinsOverAlreadyAvailableLateValue() {
        final CompletableFuture<String> source = CompletableFuture.completedFuture("late");
        final CompletableFuture<Void> disconnected = CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("disconnected"));
        final AtomicInteger lateCleanup = new AtomicInteger();

        final CompletableFuture<String> dependent = ResourcePackPackets.detachedCancellation(
                source, disconnected, ignored -> lateCleanup.incrementAndGet());

        assertThrows(java.util.concurrent.CancellationException.class, dependent::join);
        assertEquals(1, lateCleanup.get());
    }

    @Test
    void clearedClaimWaiterDetachesFromSharedArchiveFuture() {
        final ResourcePack.Key packKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
        final ResourcePackLoadStateTracker.Info info = new ResourcePackLoadStateTracker.Info(
                packKey, new byte[0], "", null);
        final CompletableFuture<Path> sharedArchive = new CompletableFuture<>();
        final CompletableFuture<Void> waiter = ResourcePackPackets.attachClaimedPackWaiter(
                new WeakReference<UserConnection>(null),
                new WeakReference<ResourcePackLoadStateTracker>(null),
                packKey, info, ArchiveDigest.compute(new byte[0]), sharedArchive, "unused");

        sharedArchive.complete(Path.of("not-opened"));

        assertDoesNotThrow(waiter::join);
    }

    private static ScheduledThreadPoolExecutor timeoutScheduler() {
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

}
