/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakingProgressTrackerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final List<Integer> sentAcks = new ArrayList<>();
    private final BlockBreakingProgressTracker tracker = new BlockBreakingProgressTracker(new UserConnectionImpl(this.channel), this.sentAcks::add);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void preservesOriginalStateForTimedOutPredictionRollback() {
        final BlockPosition position = new BlockPosition(1, 64, 2);
        this.tracker.expectJavaAckAfterBlockUpdate(position, 7, 1234);

        assertTrue(this.tracker.collectTimedOutBreakAcks(System.currentTimeMillis()).isEmpty());

        final List<BlockBreakingProgressTracker.TimedOutBreakAck> timedOut =
                this.tracker.collectTimedOutBreakAcks(System.currentTimeMillis() + 1_001L);
        assertEquals(List.of(new BlockBreakingProgressTracker.TimedOutBreakAck(position, 7, 1234, null)), timedOut);
        assertTrue(this.tracker.collectTimedOutBreakAcks(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void delaysPredictionUntilAuthoritativeProgressIsComplete() {
        final BlockPosition position = new BlockPosition(1, 64, 2);
        this.tracker.startMining(position, Direction.NORTH);
        this.tracker.handleStartCracking(position, 65_535 / 15, true);
        for (int i = 0; i < 12; i++) {
            assertNull(this.tracker.advanceAuthInput());
        }

        this.tracker.finishMining(position, 7, 1234, true);
        final BlockBreakingProgressTracker.FinishingStep early = this.tracker.advanceAuthInput();
        assertNotNull(early);
        assertFalse(early.predict());

        final BlockBreakingProgressTracker.FinishingStep ready = this.tracker.advanceAuthInput();
        assertNotNull(ready);
        assertTrue(ready.predict());
        assertEquals(position, ready.target().position());
    }

    @Test
    void treatsNoCorrectionAsSilentSuccess() {
        final BlockPosition position = new BlockPosition(3, 70, 4);
        this.tracker.startMining(position, Direction.UP);
        this.tracker.finishMining(position, 8, 4321, true);

        assertTrue(this.tracker.advanceAuthInput().predict());
        for (int i = 0; i < 9; i++) {
            assertFalse(this.tracker.advanceAuthInput().silentSuccess());
        }
        assertTrue(this.tracker.advanceAuthInput().silentSuccess());
    }

    @Test
    void nonAirUpdateSettlesAsServerCorrection() {
        final BlockPosition position = new BlockPosition(5, 80, 6);
        this.tracker.startMining(position, Direction.SOUTH);
        this.tracker.finishMining(position, 9, 9876, true);

        this.tracker.handleBlockUpdate(position, false);

        assertTrue(this.tracker.collectTimedOutBreakAcks(System.currentTimeMillis() + 1_001L).isEmpty());
        assertEquals(BlockBreakingProgressTracker.MiningPhase.IDLE, this.tracker.miningPhase());
    }

    @Test
    void authoritativeFinishStillTimesOutWithoutJavaSequence() {
        final BlockPosition position = new BlockPosition(7, 90, 8);
        this.tracker.startMining(position, Direction.WEST);
        this.tracker.finishMining(position, 0, 2468, true);

        final List<BlockBreakingProgressTracker.TimedOutBreakAck> timedOut =
                this.tracker.collectTimedOutBreakAcks(System.currentTimeMillis() + 1_001L);
        assertEquals(1, timedOut.size());
        assertEquals(0, timedOut.getFirst().sequence());
        assertEquals(BlockBreakingProgressTracker.MiningPhase.IDLE, this.tracker.miningPhase());
    }

    @Test
    void laterSequenceWaitsForEarlierSequence() {
        final BlockPosition first = new BlockPosition(1, 64, 1);
        final BlockPosition second = new BlockPosition(2, 64, 2);
        this.tracker.expectJavaAckAfterBlockUpdate(first, 10, 100);
        this.tracker.expectJavaAckAfterBlockUpdate(second, 11, 101);

        this.tracker.handleBlockUpdate(second, true);
        this.tracker.afterJavaBlockUpdate(second);
        assertEquals(2, this.tracker.pendingAckCount());
        assertTrue(this.sentAcks.isEmpty());

        this.tracker.handleBlockUpdate(first, true);
        this.tracker.afterJavaBlockUpdate(first);
        assertEquals(0, this.tracker.pendingAckCount());
        assertEquals(List.of(11), this.sentAcks);
    }

    @Test
    void lifecycleChangeDropsOldDimensionState() {
        final BlockPosition position = new BlockPosition(7, 70, 7);
        this.tracker.startMining(position, Direction.DOWN);
        this.tracker.finishMining(position, 12, 777, true);

        this.tracker.clearForLifecycleChange();

        assertEquals(BlockBreakingProgressTracker.MiningPhase.IDLE, this.tracker.miningPhase());
        assertNull(this.tracker.miningTarget());
        assertEquals(0, this.tracker.pendingAckCount());
        assertTrue(this.tracker.collectTimedOutBreakAcks(Long.MAX_VALUE).isEmpty());
    }

}
