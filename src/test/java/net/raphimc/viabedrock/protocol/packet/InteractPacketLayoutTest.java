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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractPacketLayoutTest {

    @Test
    void official975UsesOptionalPositionPrefix() {
        assertTrue(InteractPacketLayout.usesOptionalPosition(false, 975));
        assertTrue(InteractPacketLayout.usesOptionalPosition(true, 897));
        assertFalse(InteractPacketLayout.usesOptionalPosition(true, 860));
    }

    @Test
    void netease860MouseoverWritesRawVector3f() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InteractPacketLayout.writePosition(buffer, InteractPacket_Action.InteractUpdate, Position3f.ZERO, true, 860);
            assertEquals(12, buffer.readableBytes());
            assertEquals(0f, buffer.readFloatLE());
            assertEquals(0f, buffer.readFloatLE());
            assertEquals(0f, buffer.readFloatLE());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975MouseoverWritesBooleanThenVector3f() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InteractPacketLayout.writePosition(buffer, InteractPacket_Action.InteractUpdate, Position3f.ZERO, false, 975);
            assertTrue(buffer.readBoolean());
            assertEquals(0f, buffer.readFloatLE());
            assertEquals(0f, buffer.readFloatLE());
            assertEquals(0f, buffer.readFloatLE());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975OpenInventoryWritesFalseBoolean() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InteractPacketLayout.writePosition(buffer, InteractPacket_Action.OpenInventory, Position3f.ZERO, false, 975);
            assertFalse(buffer.readBoolean());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860OpenInventoryWritesNoPosition() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InteractPacketLayout.writePosition(buffer, InteractPacket_Action.OpenInventory, Position3f.ZERO, true, 860);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
