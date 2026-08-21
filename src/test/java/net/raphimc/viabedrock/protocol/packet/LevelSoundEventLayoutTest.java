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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelSoundEventLayoutTest {

    @Test
    void netease860KeepsUniqueIdButDropsFireAtPosition() {
        assertTrue(LevelSoundEventLayout.usesEntityUniqueId(true, 860));
        assertFalse(LevelSoundEventLayout.usesFireAtPosition(true, 860));
        assertTrue(LevelSoundEventLayout.usesFireAtPosition(false, 860));
        assertTrue(LevelSoundEventLayout.usesFireAtPosition(true, 974));
        assertTrue(LevelSoundEventLayout.usesFireAtPosition(true, 975));
    }

    @Test
    void parsesNetease860SoundTrailerWithoutReadingPastThePacket() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            LevelSoundEventLayout.writeTrailer(buffer, -1L, null, true, 860);
            assertEquals(8, buffer.readableBytes(), "NetEase 860 trailer is only the unique id");

            LevelSoundEventLayout.skipTrailer(buffer, true, 860);
            assertFalse(buffer.isReadable(), "NetEase 860 sound trailer must consume the full packet");
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860SoundTrailerAs974ReadsPastThePacket() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            LevelSoundEventLayout.writeTrailer(buffer, -1L, null, true, 860);
            assertThrows(IndexOutOfBoundsException.class,
                    () -> LevelSoundEventLayout.skipTrailer(buffer, true, 974));
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialSoundTrailerStillReadsFireAtPosition() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            LevelSoundEventLayout.writeTrailer(buffer, 42L, null, false, 860);
            assertEquals(9, buffer.readableBytes(), "official trailer is unique id plus absent optional vector");
            LevelSoundEventLayout.skipTrailer(buffer, false, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
