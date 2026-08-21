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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PackType;
import net.raphimc.viabedrock.protocol.storage.ResourcePackLoadStateTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackPacketsTypeTest {

    @Test
    void rejectsOversizedLengthBeforeReadingOrAllocatingPayload() {
        final ResourcePackPackets.BoundedByteArrayType type =
                new ResourcePackPackets.BoundedByteArrayType(1, 8);
        final ByteBuf buffer = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, Integer.MAX_VALUE);
        buffer.writeByte(1);
        final int payloadReaderIndex = buffer.readerIndex();

        assertThrows(IllegalArgumentException.class, () -> type.read(buffer));

        assertEquals(payloadReaderIndex + 5, buffer.readerIndex());
        assertEquals(1, buffer.readableBytes());
        buffer.release();
    }

    @Test
    void enforcesExactHashLengthAndRoundTripsBoundedPayload() {
        final ResourcePackPackets.BoundedByteArrayType hashType =
                new ResourcePackPackets.BoundedByteArrayType(32, 32);
        final ByteBuf wrongLength = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(wrongLength, 31);
        wrongLength.writeZero(31);
        assertThrows(IllegalArgumentException.class, () -> hashType.read(wrongLength));
        wrongLength.release();

        final byte[] expected = new byte[32];
        expected[31] = 42;
        final ByteBuf valid = Unpooled.buffer();
        hashType.write(valid, expected);
        assertArrayEquals(expected, hashType.read(valid));
        valid.release();
    }

    @Test
    void disabledSharedCacheNeverClaimsRawCas() {
        final ResourcePack.Key key = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
        final ResourcePackLoadStateTracker.Info info = new ResourcePackLoadStateTracker.Info(
                key, new byte[0], "", null);

        assertFalse(ResourcePackPackets.shouldClaimRawArchive(false, PackType.Resources, info));
        assertTrue(ResourcePackPackets.shouldClaimRawArchive(true, PackType.Resources, info));
        assertTrue(ResourcePackPackets.shouldClaimRawArchive(true, PackType.Behavior, info));
        assertFalse(ResourcePackPackets.shouldClaimRawArchive(true, PackType.Resources, null));
    }

}
