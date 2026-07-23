/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.Position2f;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ClientLightStorage;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinPacketsTest {

    @Test
    void runsPlayDependentWorkOnlyAfterConfigurationFinish() {
        final List<String> events = new ArrayList<>();
        final Runnable completion = JoinPackets.sequenceConfigurationCompletion(
                () -> events.add("finish-and-play"),
                () -> events.add("play-dependent"));

        assertEquals(List.of(), events);
        completion.run();
        assertEquals(List.of("finish-and-play", "play-dependent"), events);
    }

    @Test
    void doesNotRunPlayDependentWorkWhenFinishFails() {
        final List<String> events = new ArrayList<>();
        final Runnable completion = JoinPackets.sequenceConfigurationCompletion(
                () -> {
                    events.add("finish-failed");
                    throw new IllegalStateException("failed");
                },
                () -> events.add("play-dependent"));

        assertThrows(IllegalStateException.class, completion::run);
        assertEquals(List.of("finish-failed"), events);
    }

    @Test
    void negotiationBarrierDelaysTheEntireCompletionSequence() {
        final ClientLightStorage storage = new ClientLightStorage();
        final List<String> events = new ArrayList<>();
        final Runnable completion = JoinPackets.sequenceConfigurationCompletion(
                () -> events.add("finish-and-play"),
                () -> events.add("play-dependent"));

        assertTrue(storage.markProbeSent(0L));
        assertEquals(ClientLightStorage.FinishDecision.WAIT, storage.requestFinish(1L, completion).decision());
        assertEquals(List.of(), events);

        assertTrue(storage.tryNegotiateClientComputed());
        final Runnable released = storage.releasePendingFinishAfterNegotiation();
        assertNotNull(released);
        released.run();
        assertEquals(List.of("finish-and-play", "play-dependent"), events);
    }

    @Test
    void coldSkippedNegotiationBuildDoesNotBlockAndReplaysOnEventLoop() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final long entityUniqueId = 7L;
        final long entityRuntimeId = 42L;
        final int playerGameType = 1;
        final Position3f playerPosition = new Position3f(12.5F, 64.25F, -31.75F);
        final Position2f playerRotation = new Position2f(91.5F, -12.25F);
        final long seed = 0x1020304050607080L;
        final short spawnBiomeType = 2;
        final String customBiomeName = "minecraft:plains";
        final int dimension = 1;
        final ByteBuf sourcePayload = channel.alloc().buffer();
        BedrockTypes.VAR_LONG.writePrimitive(sourcePayload, entityUniqueId);
        BedrockTypes.UNSIGNED_VAR_LONG.writePrimitive(sourcePayload, entityRuntimeId);
        BedrockTypes.VAR_INT.writePrimitive(sourcePayload, playerGameType);
        BedrockTypes.POSITION_3F.write(sourcePayload, playerPosition);
        BedrockTypes.POSITION_2F.write(sourcePayload, playerRotation);
        sourcePayload.writeLongLE(seed);
        sourcePayload.writeShortLE(spawnBiomeType);
        BedrockTypes.STRING.write(sourcePayload, customBiomeName);
        BedrockTypes.VAR_INT.writePrimitive(sourcePayload, dimension);

        final ByteBuf payload;
        try {
            final PacketWrapper incoming = new PacketWrapperImpl(
                    ClientboundBedrockPackets.START_GAME, sourcePayload, user);
            incoming.setId(-1); // StatelessTransitionProtocol applies its unmapped transition id first.
            incoming.cancel();

            assertEquals(-1, incoming.getId());
            assertNull(incoming.getPacketType());
            assertTrue(incoming.isCancelled());
            payload = JoinPackets.copyDeferredStartGamePayload(incoming);
            assertEquals(sourcePayload.writerIndex(), sourcePayload.readerIndex());
        } finally {
            sourcePayload.release();
        }
        final CompletableFuture<ResourcePackStorage> creation = new CompletableFuture<>();
        final CompletableFuture<Void> initialization = new CompletableFuture<>();
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseCreation = new CountDownLatch(1);
        final CountDownLatch initializerStarted = new CountDownLatch(1);
        final CountDownLatch releaseInitialization = new CountDownLatch(1);
        final CountDownLatch replayed = new CountDownLatch(1);
        final AtomicReference<Thread> buildThread = new AtomicReference<>();
        final AtomicReference<Thread> initializerThread = new AtomicReference<>();
        final AtomicReference<Thread> replayThread = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<StartGameHeader> replayedHeader = new AtomicReference<>();
        final CompletableFuture<ResourcePackStorage> preparation =
                JoinPackets.initializePreparedResourcePackStorage(creation, storage -> {
                    initializerThread.set(Thread.currentThread());
                    initializerStarted.countDown();
                    return initialization;
                });
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.execute(() -> {
                buildThread.set(Thread.currentThread());
                workerStarted.countDown();
                await(releaseCreation);
                creation.complete(ResourcePackStorage.createUnshared(List.of()));
                await(releaseInitialization);
                initialization.complete(null);
            });
            assertTrue(workerStarted.await(5L, TimeUnit.SECONDS));

            JoinPackets.resumeStartGameAfterResourcePackPreparation(
                    user, payload, preparation,
                    (liveUser, deferredPayload) -> {
                        replayThread.set(Thread.currentThread());
                        final ByteBuf replayBuffer = deferredPayload.duplicate();
                        final PacketWrapper replay = new PacketWrapperImpl(
                                ClientboundBedrockPackets.START_GAME, replayBuffer, liveUser);
                        replayedHeader.set(new StartGameHeader(
                                replay.read(BedrockTypes.VAR_LONG),
                                replay.read(BedrockTypes.UNSIGNED_VAR_LONG),
                                replay.read(BedrockTypes.VAR_INT),
                                replay.read(BedrockTypes.POSITION_3F),
                                replay.read(BedrockTypes.POSITION_2F),
                                replay.read(BedrockTypes.LONG_LE),
                                replay.read(BedrockTypes.SHORT_LE),
                                replay.read(BedrockTypes.STRING),
                                replay.read(BedrockTypes.VAR_INT),
                                replayBuffer.readableBytes()));
                        replayed.countDown();
                    }, (liveUser, error) -> failure.set(error));

            assertFalse(preparation.isDone());
            assertNull(user.get(ResourcePackStorage.class));
            assertEquals(1, payload.refCnt());

            releaseCreation.countDown();
            assertTrue(initializerStarted.await(5L, TimeUnit.SECONDS));
            channel.runPendingTasks();
            assertTrue(creation.isDone());
            assertFalse(preparation.isDone());
            assertNull(user.get(ResourcePackStorage.class));
            assertEquals(1L, replayed.getCount());

            releaseInitialization.countDown();
            final ResourcePackStorage storage = preparation.get(5L, TimeUnit.SECONDS);
            runPendingTasksUntil(channel, replayed);

            assertNull(failure.get());
            assertSame(storage, user.get(ResourcePackStorage.class));
            assertEquals(new StartGameHeader(
                    entityUniqueId, entityRuntimeId, playerGameType, playerPosition, playerRotation,
                    seed, spawnBiomeType, customBiomeName, dimension, 0), replayedHeader.get());
            assertNotSame(buildThread.get(), replayThread.get());
            assertSame(buildThread.get(), initializerThread.get());
            assertTrue(channel.eventLoop().inEventLoop(replayThread.get()));
            assertEquals(0, payload.refCnt());
        } finally {
            releaseCreation.countDown();
            releaseInitialization.countDown();
            worker.shutdownNow();
            user.clearStoredObjects();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void deferredPayloadRemovesPacketIdOnlyWhenWrapperActuallySerializesIt() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final ByteBuf sourcePayload = channel.alloc().buffer();
        BedrockTypes.VAR_LONG.writePrimitive(sourcePayload, 7L);
        try {
            final PacketWrapper incoming = new PacketWrapperImpl(
                    ClientboundBedrockPackets.START_GAME, sourcePayload, user);
            final ByteBuf payload = JoinPackets.copyDeferredStartGamePayload(incoming);
            try {
                assertEquals(7L, BedrockTypes.VAR_LONG.readPrimitive(payload));
                assertFalse(payload.isReadable());
            } finally {
                payload.release();
            }
        } finally {
            sourcePayload.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectReleasesDeferredStartGameAndCancelsOnlyItsWaiter() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final ByteBuf payload = channel.alloc().buffer().writeByte(1);
        final CompletableFuture<ResourcePackStorage> preparation = new CompletableFuture<>();
        try {
            JoinPackets.resumeStartGameAfterResourcePackPreparation(
                    user, payload, preparation,
                    (liveUser, deferredPayload) -> {
                        throw new AssertionError("Disconnected START_GAME must not be replayed");
                    }, (liveUser, error) -> {
                        throw new AssertionError("Disconnected START_GAME must not report a build failure", error);
                    });

            channel.close();
            channel.runPendingTasks();

            assertTrue(preparation.isCancelled());
            assertEquals(0, payload.refCnt());
            assertNull(user.get(ResourcePackStorage.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void runtimeInitializationFailurePreventsPublicationAndReplay() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final ByteBuf payload = channel.alloc().buffer().writeByte(1);
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of());
        final CompletableFuture<Void> initialization = new CompletableFuture<>();
        final CompletableFuture<ResourcePackStorage> preparation =
                JoinPackets.initializePreparedResourcePackStorage(
                        CompletableFuture.completedFuture(storage), ignored -> initialization);
        final IllegalStateException expected = new IllegalStateException("runtime initialization failed");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch failureReported = new CountDownLatch(1);
        try {
            JoinPackets.resumeStartGameAfterResourcePackPreparation(
                    user, payload, preparation,
                    (liveUser, deferredPayload) -> {
                        throw new AssertionError("Uninitialized START_GAME must not be replayed");
                    }, (liveUser, error) -> {
                        failure.set(error);
                        failureReported.countDown();
                    });

            initialization.completeExceptionally(new java.util.concurrent.CompletionException(expected));
            runPendingTasksUntil(channel, failureReported);

            assertSame(expected, failure.get());
            assertTrue(preparation.isCompletedExceptionally());
            assertNull(user.get(ResourcePackStorage.class));
            assertEquals(0, payload.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void runPendingTasksUntil(final EmbeddedChannel channel,
                                             final CountDownLatch completion) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (completion.getCount() != 0L && System.nanoTime() < deadline) {
            channel.runPendingTasks();
            Thread.onSpinWait();
        }
        assertTrue(completion.await(0L, TimeUnit.SECONDS));
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", e);
        }
    }

    private record StartGameHeader(
            long entityUniqueId,
            long entityRuntimeId,
            int playerGameType,
            Position3f playerPosition,
            Position2f playerRotation,
            long seed,
            short spawnBiomeType,
            String customBiomeName,
            int dimension,
            int remainingBytes) {
    }

}
