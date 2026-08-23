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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiDataPickItemLayoutTest {

    @Test
    void mot860WritesOnlyHotbarSlot() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            GuiDataPickItemLayout.write(buffer, new GuiDataPickItemLayout.Packet("Stone", "Speed", 3), true, 860);
            assertEquals(4, buffer.readableBytes());
            final GuiDataPickItemLayout.Packet packet = GuiDataPickItemLayout.read(buffer, true, 860);
            assertEquals("", packet.itemName());
            assertEquals("", packet.itemEffects());
            assertEquals(3, packet.hotbarSlot());
            assertFalse(buffer.isReadable());
            assertFalse(GuiDataPickItemLayout.hasOverlayText(packet));
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialLayoutKeepsNameAndEffects() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            GuiDataPickItemLayout.write(buffer, new GuiDataPickItemLayout.Packet("Stone", "Speed II", 7), false, 975);
            final GuiDataPickItemLayout.Packet packet = GuiDataPickItemLayout.read(buffer, false, 975);
            assertEquals("Stone", packet.itemName());
            assertEquals("Speed II", packet.itemEffects());
            assertEquals(7, packet.hotbarSlot());
            assertFalse(buffer.isReadable());
            assertEquals("Stone\nSpeed II", GuiDataPickItemLayout.overlayText(packet));
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingMot860AsOfficialWouldOverRead() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            GuiDataPickItemLayout.write(buffer, new GuiDataPickItemLayout.Packet("", "", 2), true, 860);
            assertEquals(4, buffer.readableBytes());
            assertTrue(GuiDataPickItemLayout.usesNameAndEffects(false, 975));
            assertFalse(GuiDataPickItemLayout.usesNameAndEffects(true, 860));
            assertTrue(GuiDataPickItemLayout.isHotbarSlot(0));
            assertTrue(GuiDataPickItemLayout.isHotbarSlot(8));
            assertFalse(GuiDataPickItemLayout.isHotbarSlot(9));
            assertFalse(GuiDataPickItemLayout.isHotbarSlot(-1));
        } finally {
            buffer.release();
        }
    }
}
