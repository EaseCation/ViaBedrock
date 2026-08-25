/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.types.inventory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryTransactionPacketTypeTest {

    @Test
    void entityRuntimeIdUsesUnsignedVarLong() {
        assertUnsignedEncoding(1L, 0x01);
        assertUnsignedEncoding(127L, 0x7F);
        assertUnsignedEncoding(128L, 0x80, 0x01);
        assertUnsignedEncoding(2_147_483_648L, 0x80, 0x80, 0x80, 0x80, 0x08);
        assertUnsignedEncoding(4_294_967_295L, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F);
    }

    private static void assertUnsignedEncoding(final long runtimeId, final int... expectedBytes) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventoryTransactionPacketType.writeEntityRuntimeId(buffer, runtimeId);
            assertEquals(expectedBytes.length, buffer.readableBytes());
            for (final int expectedByte : expectedBytes) {
                assertEquals(expectedByte, buffer.getUnsignedByte(buffer.readerIndex()));
                buffer.skipBytes(1);
            }
            buffer.readerIndex(0);
            assertEquals(runtimeId, InventoryTransactionPacketType.readEntityRuntimeId(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
