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
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakingProgressTrackerTest {

    @Test
    void forwardsOnlyStandaloneSwings() {
        final BlockBreakingProgressTracker tracker = new BlockBreakingProgressTracker(null);
        final BlockPosition position = new BlockPosition(1, 2, 3);

        assertTrue(tracker.shouldForwardStandaloneSwing());

        tracker.startMining(position, Direction.NORTH);
        assertFalse(tracker.shouldForwardStandaloneSwing());

        tracker.suspendMining(position);
        assertFalse(tracker.shouldForwardStandaloneSwing());

        tracker.finishMining(position, 0);
        assertFalse(tracker.shouldForwardStandaloneSwing());
        for (int i = 0; i < 5; i++) {
            tracker.tick();
        }
        assertTrue(tracker.shouldForwardStandaloneSwing());
    }

}
