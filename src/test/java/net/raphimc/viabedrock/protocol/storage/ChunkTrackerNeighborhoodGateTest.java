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

    @Test
    void publisherAtOriginDoesNotYankLobbyPlayerColumnOutsideJavaRadius() {
        assertEquals(13, ChunkTracker.resolveJavaCacheCenter(0, 0, 13, 13, 8)[0]);
        assertEquals(13, ChunkTracker.resolveJavaCacheCenter(0, 0, 13, 13, 8)[1]);
        assertEquals(13, ChunkTracker.resolveJavaCacheCenter(0, 0, 13, 13, 12)[0]);
        assertEquals(0, ChunkTracker.resolveJavaCacheCenter(0, 0, 13, 13, 16)[0]);
        assertEquals(0, ChunkTracker.resolveJavaCacheCenter(0, 0, 13, 13, 16)[1]);
        assertEquals(13, ChunkTracker.resolveJavaCacheCenter(13, 13, 13, 13, 8)[0]);
        assertEquals(-4, ChunkTracker.resolveJavaCacheCenter(0, 0, -4, 0, 3)[0]);
        assertEquals(1, ChunkTracker.resolveJavaCacheCenter(1, 1, 5, 5, 8)[0]);
        assertEquals(1, ChunkTracker.resolveJavaCacheCenter(1, 1, 5, 5, 8)[1]);
    }

    @Test
    void publisherRadiusZeroOrBelowViewDistanceKeepsJavaFloor() {
        assertEquals(8, ChunkTracker.resolveJavaCacheRadius(0, 8, 8));
        assertEquals(12, ChunkTracker.resolveJavaCacheRadius(4, 8, 12));
        assertEquals(16, ChunkTracker.resolveJavaCacheRadius(16, 8, 8));
        assertEquals(8, ChunkTracker.resolveJavaCacheRadius(-1, 8, 8));
    }

    @Test
    void playerColumnArrivalSnapsStaleOriginCenter() {
        assertTrue(ChunkTracker.shouldSnapJavaCacheCenterToPlayerColumn(0, 0, 13, 13, 8));
        assertFalse(ChunkTracker.shouldSnapJavaCacheCenterToPlayerColumn(13, 13, 13, 13, 8));
        assertFalse(ChunkTracker.shouldSnapJavaCacheCenterToPlayerColumn(0, 0, 8, 0, 8));
        assertTrue(ChunkTracker.shouldSnapJavaCacheCenterToPlayerColumn(0, 0, 9, 0, 8));
    }
}
