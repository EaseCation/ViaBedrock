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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PacketLeftoverLayoutTest {

    @Test
    void discardsUnreadTrailerBytes() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(1);
            buffer.writeByte(2);
            buffer.writeByte(3);
            buffer.readByte();
            assertEquals(2, buffer.readableBytes());
            PacketLeftoverLayout.discardUnreadInput(buffer);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialEmptyTrailerIsNoOp() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PacketLeftoverLayout.discardUnreadInput(buffer);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
