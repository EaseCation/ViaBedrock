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
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerStartItemCooldownLayoutTest {

    @Test
    void motGoatHornCooldownRoundTrips() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerStartItemCooldownLayout.write(buffer, "goat_horn", 140);
            final PlayerStartItemCooldownLayout.Packet packet = PlayerStartItemCooldownLayout.read(buffer);
            assertEquals("goat_horn", packet.itemCategory());
            assertEquals(140, packet.coolDownDuration());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void motShieldCooldownRoundTripsNegativeSafeDuration() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerStartItemCooldownLayout.write(buffer, "shield", 100);
            final PlayerStartItemCooldownLayout.Packet packet = PlayerStartItemCooldownLayout.read(buffer);
            assertEquals("shield", packet.itemCategory());
            assertEquals(100, packet.coolDownDuration());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void javaCooldownIdentifierNamespacesMotCategories() {
        assertEquals("minecraft:goat_horn", PlayerStartItemCooldownLayout.javaCooldownIdentifier("goat_horn"));
        assertEquals("minecraft:shield", PlayerStartItemCooldownLayout.javaCooldownIdentifier("shield"));
        assertEquals("custom:widget", PlayerStartItemCooldownLayout.javaCooldownIdentifier("custom:widget"));
        assertNull(PlayerStartItemCooldownLayout.javaCooldownIdentifier(""));
        assertNull(PlayerStartItemCooldownLayout.javaCooldownIdentifier("   "));
        assertNull(PlayerStartItemCooldownLayout.javaCooldownIdentifier(null));
    }
}
