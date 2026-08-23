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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathSyncLayoutTest {

    @Test
    void motDeathInfoRoundTripsTranslationKeyAndParameters() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            DeathSyncLayout.writeDeathInfo(buffer, "death.attack.player", new String[]{"Steve"});
            final String message = net.raphimc.viabedrock.protocol.types.BedrockTypes.STRING.read(buffer);
            final String[] parameters = net.raphimc.viabedrock.protocol.types.BedrockTypes.STRING_ARRAY.read(buffer);
            assertEquals("death.attack.player", message);
            assertArrayEquals(new String[]{"Steve"}, parameters);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void motSetHealthRoundTripsUnsignedVarInt() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            DeathSyncLayout.writeSetHealth(buffer, 20);
            assertEquals(20, net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer).intValue());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void immediateRespawnHealthIsAnyPositiveValue() {
        assertFalse(DeathSyncLayout.isImmediateRespawnHealth(0));
        assertTrue(DeathSyncLayout.isImmediateRespawnHealth(1));
        assertTrue(DeathSyncLayout.isImmediateRespawnHealth(20));
    }
}
