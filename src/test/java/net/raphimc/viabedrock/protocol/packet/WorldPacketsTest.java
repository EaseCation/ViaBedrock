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

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SubChunkPacket_SubChunkRequestResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldPacketsTest {

    @Test
    void retriesOnlyPotentiallyTransientSubChunkFailures() {
        assertTrue(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.Undefined));
        assertTrue(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.PlayerDoesntExist));

        assertFalse(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.LevelChunkDoesntExist));
        assertFalse(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.WrongDimension));
        assertFalse(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.IndexOutOfBounds));
        assertFalse(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.Success));
        assertFalse(WorldPackets.shouldRetryFailedSubChunk(SubChunkPacket_SubChunkRequestResult.SuccessAllAir));
    }

    @Test
    void packsNegativeSectionYUsingJavaTwentyBitField() {
        final long packed = WorldPackets.packJavaSectionPosition(12, -2, -34);

        assertEquals(12, (int) (packed >> 42));
        assertEquals(-2, (int) (packed << 44 >> 44));
        assertEquals(-34, (int) (packed << 22 >> 42));
    }
}
