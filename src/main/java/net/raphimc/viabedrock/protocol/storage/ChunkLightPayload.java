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

import com.viaversion.viaversion.api.minecraft.chunks.ChunkSectionLight;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

record ChunkLightPayload(
        BitSet skyLightMask,
        BitSet blockLightMask,
        BitSet emptySkyLightMask,
        BitSet emptyBlockLightMask,
        List<byte[]> skyLightArrays,
        List<byte[]> blockLightArrays
) {

    private static final byte[] FULL_SKY_LIGHT = new byte[ChunkSectionLight.LIGHT_LENGTH];

    static {
        Arrays.fill(FULL_SKY_LIGHT, (byte) 0xFF);
    }

    static ChunkLightPayload placeholder(final int lightSectionCount) {
        if (lightSectionCount < 0) {
            throw new IllegalArgumentException("lightSectionCount must be non-negative");
        }
        final BitSet allSections = new BitSet(lightSectionCount);
        allSections.set(0, lightSectionCount);
        return new ChunkLightPayload(
                allSections,
                new BitSet(),
                new BitSet(),
                (BitSet) allSections.clone(),
                Collections.nCopies(lightSectionCount, FULL_SKY_LIGHT),
                List.of()
        );
    }

    static ChunkLightPayload create(final byte[][] skyLight, final byte[][] blockLight, final int lightSectionCount) {
        if (lightSectionCount < 0 || skyLight.length < lightSectionCount || blockLight.length < lightSectionCount) {
            throw new IllegalArgumentException("light arrays do not cover lightSectionCount");
        }
        final BitSet skyLightMask = new BitSet();
        final BitSet blockLightMask = new BitSet();
        final BitSet emptySkyLightMask = new BitSet();
        final BitSet emptyBlockLightMask = new BitSet();
        final List<byte[]> skyLightArrays = new ArrayList<>();
        final List<byte[]> blockLightArrays = new ArrayList<>();

        for (int i = 0; i < lightSectionCount; i++) {
            if (skyLight[i] != null) {
                skyLightMask.set(i);
                skyLightArrays.add(skyLight[i]);
            } else {
                emptySkyLightMask.set(i);
            }

            if (blockLight[i] != null) {
                blockLightMask.set(i);
                blockLightArrays.add(blockLight[i]);
            } else {
                emptyBlockLightMask.set(i);
            }
        }

        return new ChunkLightPayload(
                skyLightMask,
                blockLightMask,
                emptySkyLightMask,
                emptyBlockLightMask,
                skyLightArrays,
                blockLightArrays
        );
    }

}
