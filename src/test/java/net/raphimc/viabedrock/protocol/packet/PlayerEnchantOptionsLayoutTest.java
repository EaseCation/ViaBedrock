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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerEnchantOptionsLayoutTest {

    @Test
    void netease860UsesUnsignedMinLevelAndCustomEnchantList() {
        assertFalse(PlayerEnchantOptionsLayout.usesOfficial974Layout(true, 860));
        assertTrue(PlayerEnchantOptionsLayout.usesNetEaseCustomEnchants(true));
        assertTrue(PlayerEnchantOptionsLayout.usesOfficial974Layout(false, 975));
        assertFalse(PlayerEnchantOptionsLayout.usesNetEaseCustomEnchants(false));
    }

    @Test
    void roundTripsNetease860OptionWithModEnchantIdentifier() {
        final PlayerEnchantOptionsLayout.EnchantOption option = new PlayerEnchantOptionsLayout.EnchantOption(
                7, 1,
                List.of(new PlayerEnchantOptionsLayout.EnchantData(9, 2, "mod:sharp")),
                List.of(),
                List.of(),
                List.of(new PlayerEnchantOptionsLayout.EnchantData(17, 1, "mod:unbreaking")),
                "ancient",
                0x10000001
        );
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerEnchantOptionsLayout.writePacket(buffer, List.of(option), true, 860);
            final List<PlayerEnchantOptionsLayout.EnchantOption> decoded = PlayerEnchantOptionsLayout.readPacket(buffer, true, 860);
            assertFalse(buffer.isReadable());
            assertEquals(1, decoded.size());
            final PlayerEnchantOptionsLayout.EnchantOption actual = decoded.get(0);
            assertEquals(7, actual.minLevel());
            assertEquals(1, actual.primarySlot());
            assertEquals(2, actual.javaXpCost());
            assertEquals("ancient", actual.enchantName());
            assertEquals(0x10000001, actual.enchantNetId());
            assertEquals(1, actual.enchants0().size());
            assertEquals(9, actual.enchants0().get(0).type());
            assertEquals(2, actual.enchants0().get(0).level());
            assertEquals("mod:sharp", actual.enchants0().get(0).modEnchantIdentifier());
            assertEquals(1, actual.enchantsCustom().size());
            assertEquals("mod:unbreaking", actual.enchantsCustom().get(0).modEnchantIdentifier());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975UsesByteMinLevelUnsignedTypeAndNoCustomList() {
        final PlayerEnchantOptionsLayout.EnchantOption option = new PlayerEnchantOptionsLayout.EnchantOption(
                12, 2,
                List.of(new PlayerEnchantOptionsLayout.EnchantData(15, 3)),
                List.of(),
                List.of(),
                List.of(new PlayerEnchantOptionsLayout.EnchantData(99, 1, "should-not-be-written")),
                "greater",
                42
        );
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerEnchantOptionsLayout.writePacket(buffer, List.of(option), false, 975);
            final List<PlayerEnchantOptionsLayout.EnchantOption> decoded = PlayerEnchantOptionsLayout.readPacket(buffer, false, 975);
            assertFalse(buffer.isReadable());
            assertEquals(1, decoded.size());
            final PlayerEnchantOptionsLayout.EnchantOption actual = decoded.get(0);
            assertEquals(12, actual.minLevel());
            assertEquals(2, actual.primarySlot());
            assertEquals(3, actual.javaXpCost());
            assertEquals("greater", actual.enchantName());
            assertEquals(42, actual.enchantNetId());
            assertEquals(15, actual.enchants0().get(0).type());
            assertEquals(3, actual.enchants0().get(0).level());
            assertEquals("", actual.enchants0().get(0).modEnchantIdentifier());
            assertTrue(actual.enchantsCustom().isEmpty());
        } finally {
            buffer.release();
        }
    }

    @Test
    void emptyOptionsRoundTrip() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerEnchantOptionsLayout.writePacket(buffer, List.of(), true, 860);
            final List<PlayerEnchantOptionsLayout.EnchantOption> decoded = PlayerEnchantOptionsLayout.readPacket(buffer, true, 860);
            assertFalse(buffer.isReadable());
            assertTrue(decoded.isEmpty());
        } finally {
            buffer.release();
        }
    }
}
