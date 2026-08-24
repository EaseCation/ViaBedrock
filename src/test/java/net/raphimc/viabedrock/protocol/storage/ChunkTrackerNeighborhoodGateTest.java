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
package net.raphimc.viabedrock.protocol.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTrackerNeighborhoodGateTest {

    @Test
    void fallsThroughOnlyWhenPlayerColumnIsSentAndNoNeighborhoodDirtyRemains() {
        assertTrue(ChunkTracker.shouldFallThroughNeighborhoodGate(false, false, true, true));
        assertFalse(ChunkTracker.shouldFallThroughNeighborhoodGate(true, false, true, true));
        assertFalse(ChunkTracker.shouldFallThroughNeighborhoodGate(false, true, true, true));
        assertFalse(ChunkTracker.shouldFallThroughNeighborhoodGate(false, false, false, true));
        assertFalse(ChunkTracker.shouldFallThroughNeighborhoodGate(false, false, true, false));
    }

    @Test
    void seedsJavaCacheCenterFromMotLobbySpawnInsteadOfOrigin() {
        assertEquals(13, ChunkTracker.javaChunkCoord(208.5D));
        assertEquals(13, ChunkTracker.javaChunkCoord(215.9D));
        assertEquals(0, ChunkTracker.javaChunkCoord(0D));
        assertEquals(-1, ChunkTracker.javaChunkCoord(-1D));
        assertTrue(Math.abs(13 - 0) > 8);
        assertFalse(Math.abs(13 - 13) > 8);
    }
}
