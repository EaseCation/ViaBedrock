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
package net.raphimc.viabedrock.protocol.types.item;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntLinkedOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BedrockItemTypeEmptyPaletteTest {

    @Test
    void emptySessionPaletteDoesNotThrowOnLegacySlotDecode() {
        final Int2ObjectOpenHashMap<IntSortedSet> palettes = new Int2ObjectOpenHashMap<>();
        palettes.put(737, new IntLinkedOpenHashSet());
        final BedrockItemType type = new BedrockItemType(0, palettes, true);
        final BedrockItem written = new BedrockItem(737, (short) 0, (byte) 1);
        written.setBlockRuntimeId(12345);

        final ByteBuf buffer = Unpooled.buffer();
        try {
            type.write(buffer, written);
            final BedrockItem read = assertDoesNotThrow(() -> type.read(buffer));
            assertEquals(737, read.identifier());
            assertEquals(1, read.amount());
            assertEquals(0, read.data());
            assertEquals(12345, read.blockRuntimeId(), "empty palette must keep the MOT 860 wire runtime");
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void emptySessionPaletteDoesNotThrowOnNetworkDescriptorDecode() {
        final Int2ObjectOpenHashMap<IntSortedSet> palettes = new Int2ObjectOpenHashMap<>();
        palettes.put(737, new IntLinkedOpenHashSet());
        final NetworkItemStackDescriptorType type = new NetworkItemStackDescriptorType(0, palettes, true);
        final BedrockItem written = new BedrockItem(737, (short) 0, (byte) 1);
        written.setBlockRuntimeId(12345);
        written.setNetId(1);

        final ByteBuf buffer = Unpooled.buffer();
        try {
            type.write(buffer, written);
            final BedrockItem read = assertDoesNotThrow(() -> type.read(buffer));
            assertEquals(737, read.identifier());
            assertEquals(1, read.amount());
            assertEquals(0, read.data());
            assertEquals(12345, read.blockRuntimeId(), "empty palette must keep the MOT 860 wire runtime");
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void populatedPaletteRemapsUnknownRuntimeDuringLegacySlotDecode() {
        final IntSortedSet valid = new IntLinkedOpenHashSet();
        valid.add(42);
        valid.add(7);
        final Int2ObjectOpenHashMap<IntSortedSet> palettes = new Int2ObjectOpenHashMap<>();
        palettes.put(1, valid);
        final BedrockItemType type = new BedrockItemType(0, palettes, false);
        final BedrockItem written = new BedrockItem(1, (short) 0, (byte) 1);
        written.setBlockRuntimeId(99);

        final ByteBuf buffer = Unpooled.buffer();
        try {
            type.write(buffer, written);
            final BedrockItem read = type.read(buffer);
            assertEquals(42, read.blockRuntimeId());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
