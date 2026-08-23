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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListLayoutTest {

    @Test
    void netease860AddTrailerMatchesMotBloomLayout() {
        assertTrue(PlayerListLayout.usesNetEaseAddTrailer(true, 860));
        assertFalse(PlayerListLayout.usesNetEaseAddTrailer(false, 860));
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerListLayout.writeNetEaseAddTrailer(buffer, 1, true, 860);
            PlayerListLayout.skipNetEaseAddTrailer(buffer, 1, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialPlayerListHasNoBloomTrailer() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerListLayout.writeNetEaseAddTrailer(buffer, 2, false, 975);
            assertFalse(buffer.isReadable());
            PlayerListLayout.skipNetEaseAddTrailer(buffer, 2, false, 975);
        } finally {
            buffer.release();
        }
    }
}
