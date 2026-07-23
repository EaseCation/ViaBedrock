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

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.http.RemotePackServiceClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackLoadStateTrackerAsyncTest {

    @Test
    void claimsEachRemoteLookupCancellationOnlyOnce() {
        final EmbeddedChannel firstChannel = new EmbeddedChannel();
        final EmbeddedChannel secondChannel = new EmbeddedChannel();
        try {
            final UUID token = UUID.randomUUID();
            final RemotePackServiceClient.Lookup lookup = new RemotePackServiceClient.Lookup(
                    "a".repeat(64), null, null, -1L, token, token,
                    "https://packs.example.test/packs/pending/" + token);
            final ResourcePackLoadStateTracker first = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(firstChannel), new ResourcePackLoadStateTracker.Info[0]);
            final ResourcePackLoadStateTracker second = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(secondChannel), new ResourcePackLoadStateTracker.Info[0]);
            first.setRemotePackLookupFuture(CompletableFuture.completedFuture(lookup));
            second.setRemotePackLookupFuture(CompletableFuture.completedFuture(lookup));

            assertSame(lookup, first.claimRemotePackCancellation());
            assertNull(first.claimRemotePackCancellation());
            assertSame(lookup, second.claimRemotePackCancellation());
            assertNull(second.claimRemotePackCancellation());
        } finally {
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

    @Test
    void resolvesVersionedAndVersionlessTransferNamesToTheAnnouncedIdentity() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key key = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker.Info info = new ResourcePackLoadStateTracker.Info(
                    key, 128L, new byte[0], "content", "", null);
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{info});

            final ResourcePackLoadStateTracker.ResolvedRequest versioned =
                    tracker.resolveTransferRequest(key.toString());
            final ResourcePackLoadStateTracker.ResolvedRequest versionless =
                    tracker.resolveTransferRequest(key.id().toString());

            assertEquals(key, versioned.key());
            assertSame(info, versioned.info());
            assertEquals(key, versionless.key());
            assertSame(info, versionless.info());
            assertThrows(IllegalArgumentException.class,
                    () -> ResourcePack.Key.fromString(key.id().toString()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsAmbiguousVersionlessTransferNamesWithoutGuessing() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final UUID id = UUID.randomUUID();
            final ResourcePack.Key firstKey = new ResourcePack.Key(id, "1.0.0");
            final ResourcePack.Key secondKey = new ResourcePack.Key(id, "2.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(
                                    firstKey, 128L, new byte[0], "first", "", null),
                            new ResourcePackLoadStateTracker.Info(
                                    secondKey, 256L, new byte[0], "second", "", null)
                    });

            assertEquals(firstKey, tracker.resolveTransferRequest(firstKey.toString()).key());
            assertEquals(secondKey, tracker.resolveTransferRequest(secondKey.toString()).key());
            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> tracker.resolveTransferRequest(id.toString()));
            assertTrue(failure.getMessage().contains("matches 2 announced versions"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMalformedAndUnannouncedTransferNames() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);

            assertThrows(IllegalArgumentException.class,
                    () -> tracker.resolveTransferRequest("not-a-uuid"));
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.resolveTransferRequest(UUID.randomUUID().toString()));
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.resolveTransferRequest(UUID.randomUUID() + "_1.0.0"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void preparationSnapshotsProtocolArraysAndRunsOnTheSubmittedWorker() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final AtomicReference<Thread> preparationThread = new AtomicReference<>();
        final AtomicReference<ResourcePack.Key> preparedKey = new AtomicReference<>();
        final AtomicReference<String> preparedSubpack = new AtomicReference<>();
        final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]) {
            @Override
            PreparedStack prepareResourcePackStack(final ResourcePack.Key[] keys,
                                                   final String[] selectedSubpacks) {
                preparationThread.set(Thread.currentThread());
                preparedKey.set(keys[0]);
                preparedSubpack.set(selectedSubpacks[0]);
                return new PreparedStack(List.of(), List.of());
            }
        };
        final ResourcePack.Key originalKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
        final ResourcePack.Key[] keys = {originalKey};
        final String[] subpacks = {"hd"};
        final AtomicReference<Callable<ResourcePackLoadStateTracker.PreparedStack>> submitted =
                new AtomicReference<>();
        final CompletableFuture<ResourcePackLoadStateTracker.PreparedStack> result = new CompletableFuture<>();

        final CompletableFuture<ResourcePackLoadStateTracker.PreparedStack> preparation =
                tracker.prepareResourcePackStackAsync(keys, subpacks, task -> {
                    submitted.set(task);
                    return result;
                });
        keys[0] = new ResourcePack.Key(UUID.randomUUID(), "2.0.0");
        subpacks[0] = "mutated";

        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.execute(() -> {
                try {
                    result.complete(submitted.get().call());
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            });

            preparation.get(2, TimeUnit.SECONDS);
            assertNotSame(Thread.currentThread(), preparationThread.get());
            assertEquals(originalKey, preparedKey.get());
            assertEquals("hd", preparedSubpack.get());
        } finally {
            worker.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMismatchedStackArraysBeforeSubmittingWork() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final ResourcePack.Key[] keys = {new ResourcePack.Key(UUID.randomUUID(), "1.0.0")};

            final CompletableFuture<ResourcePackLoadStateTracker.PreparedStack> preparation =
                    tracker.prepareResourcePackStackAsync(keys, new String[0], task -> {
                        throw new AssertionError("Mismatched input must not be submitted");
                    });

            assertThrows(ExecutionException.class, preparation::get);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void cancellingConnectionWaiterDoesNotCancelSharedSource() {
        final CompletableFuture<String> sharedSource = new CompletableFuture<>();
        final CompletableFuture<String> connectionWaiter =
                ResourcePackLoadStateTracker.detachedWaiter(sharedSource);
        final ResourcePackDownloadTracker connectionTracker = new ResourcePackDownloadTracker();
        connectionTracker.trackConnectionStage(connectionWaiter);

        connectionTracker.onRemove();

        assertTrue(connectionWaiter.isCancelled());
        assertFalse(sharedSource.isDone());
        assertFalse(sharedSource.isCancelled());
        sharedSource.complete("shared-result");
        assertEquals("shared-result", sharedSource.join());
        assertThrows(CancellationException.class, connectionWaiter::join);
    }

    @Test
    void validatesCdnBytesAgainstTrustedAnnouncementSize() throws Exception {
        ResourcePackLoadStateTracker.validateCdnSize(-1L, 7L);
        ResourcePackLoadStateTracker.validateCdnSize(7L, 7L);

        final IOException failure = assertThrows(IOException.class,
                () -> ResourcePackLoadStateTracker.validateCdnSize(8L, 7L));
        assertTrue(failure.getMessage().contains("7 != 8"));
    }

    @Test
    void strictSharedModeDoesNotTreatAnAnnouncedIdentityAsABundledPack() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key announcedKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel),
                    new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(announcedKey, new byte[0], "", null)
                    });

            assertFalse(tracker.mayUseBundledResourcePack(announcedKey, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void strictSharedModeStillAllowsTrulyLocalBundledLayers() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key announcedKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePack.Key localKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel),
                    new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(announcedKey, new byte[0], "", null)
                    });

            assertTrue(tracker.mayUseBundledResourcePack(localKey, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void legacyModeKeepsUuidVersionBundledPackCompatibility() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key announcedKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel),
                    new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(announcedKey, new byte[0], "", null)
                    });

            assertTrue(tracker.mayUseBundledResourcePack(announcedKey, false));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

}
