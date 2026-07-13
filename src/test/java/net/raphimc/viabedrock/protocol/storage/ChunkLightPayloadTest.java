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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSectionLight;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLightPayloadTest {

    @Test
    void absentLightDataStillMarksEverySectionEmpty() {
        final int lightSectionCount = 26;
        final ChunkLightPayload payload = ChunkLightPayload.create(
                new byte[lightSectionCount][],
                new byte[lightSectionCount][],
                lightSectionCount
        );
        final BitSet allSections = new BitSet(lightSectionCount);
        allSections.set(0, lightSectionCount);

        assertEquals(new BitSet(), payload.skyLightMask());
        assertEquals(new BitSet(), payload.blockLightMask());
        assertEquals(allSections, payload.emptySkyLightMask());
        assertEquals(allSections, payload.emptyBlockLightMask());
        assertEquals(0, payload.skyLightArrays().size());
        assertEquals(0, payload.blockLightArrays().size());
    }

    @Test
    void clientComputedPayloadUsesFullBrightSkyPlaceholderAndEmptyBlockLight() {
        final int lightSectionCount = 26;
        final ChunkLightPayload payload = ChunkLightPayload.placeholder(lightSectionCount);
        final BitSet allSections = new BitSet(lightSectionCount);
        allSections.set(0, lightSectionCount);

        assertEquals(allSections, payload.skyLightMask());
        assertEquals(new BitSet(), payload.blockLightMask());
        assertEquals(new BitSet(), payload.emptySkyLightMask());
        assertEquals(allSections, payload.emptyBlockLightMask());
        assertEquals(lightSectionCount, payload.skyLightMask().cardinality());
        assertEquals(lightSectionCount, payload.emptyBlockLightMask().cardinality());
        assertEquals(lightSectionCount, payload.skyLightArrays().size());
        assertEquals(0, payload.blockLightArrays().size());
        assertTrue(payload.skyLightArrays().stream().allMatch(ChunkLightPayloadTest::isFullBright));
        for (int i = 1; i < payload.skyLightArrays().size(); i++) {
            assertSame(payload.skyLightArrays().getFirst(), payload.skyLightArrays().get(i), "placeholder sections should not allocate duplicate arrays");
        }
    }

    @Test
    void clientComputedPayloadSurvivesWireCodecRoundTrip() {
        final int lightSectionCount = 26;
        final ChunkLightPayload payload = ChunkLightPayload.placeholder(lightSectionCount);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            Types.LONG_ARRAY_PRIMITIVE.write(buffer, payload.skyLightMask().toLongArray());
            Types.LONG_ARRAY_PRIMITIVE.write(buffer, payload.blockLightMask().toLongArray());
            Types.LONG_ARRAY_PRIMITIVE.write(buffer, payload.emptySkyLightMask().toLongArray());
            Types.LONG_ARRAY_PRIMITIVE.write(buffer, payload.emptyBlockLightMask().toLongArray());
            Types.VAR_INT.writePrimitive(buffer, payload.skyLightArrays().size());
            for (final byte[] array : payload.skyLightArrays()) {
                Types.BYTE_ARRAY_PRIMITIVE.write(buffer, array);
            }
            Types.VAR_INT.writePrimitive(buffer, payload.blockLightArrays().size());

            assertEquals(payload.skyLightMask(), BitSet.valueOf(Types.LONG_ARRAY_PRIMITIVE.read(buffer)));
            assertArrayEquals(new long[0], Types.LONG_ARRAY_PRIMITIVE.read(buffer));
            assertArrayEquals(new long[0], Types.LONG_ARRAY_PRIMITIVE.read(buffer));
            assertEquals(payload.emptyBlockLightMask(), BitSet.valueOf(Types.LONG_ARRAY_PRIMITIVE.read(buffer)));
            assertEquals(lightSectionCount, Types.VAR_INT.readPrimitive(buffer));
            for (int i = 0; i < lightSectionCount; i++) {
                assertTrue(isFullBright(Types.BYTE_ARRAY_PRIMITIVE.read(buffer)));
            }
            assertEquals(0, Types.VAR_INT.readPrimitive(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidPlaceholderSectionCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkLightPayload.placeholder(-1));
    }

    private static boolean isFullBright(final byte[] light) {
        if (light.length != ChunkSectionLight.LIGHT_LENGTH) {
            return false;
        }
        for (final byte value : light) {
            if (value != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

}
