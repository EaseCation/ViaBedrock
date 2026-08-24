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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.libs.fastutil.ints.IntLinkedOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemVersion;
import net.raphimc.viabedrock.protocol.model.ItemEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockItemMappingLayoutTest {

    @Test
    void missingPaletteDoesNotThrowAndYieldsUnknownRuntime() {
        assertEquals(0, assertDoesNotThrow(() -> BlockItemMappingLayout.fallbackBlockRuntimeId(null)));
        assertEquals(0, BlockItemMappingLayout.fallbackBlockRuntimeId(new IntLinkedOpenHashSet()));
    }

    @Test
    void populatedPaletteUsesTheFirstRuntimeId() {
        final IntSortedSet valid = new IntLinkedOpenHashSet();
        valid.add(42);
        valid.add(7);
        assertEquals(42, BlockItemMappingLayout.fallbackBlockRuntimeId(valid));
    }

    @Test
    void emptyPaletteKeepsTheWireRuntimeInsteadOfCallingFirstInt() {
        assertEquals(12345, BlockItemMappingLayout.sanitizeBlockRuntimeId(null, 12345));
        assertEquals(12345, BlockItemMappingLayout.sanitizeBlockRuntimeId(new IntLinkedOpenHashSet(), 12345));
    }

    @Test
    void populatedPaletteRemapsUnknownRuntimeToTheFirstKnownId() {
        final IntSortedSet valid = new IntLinkedOpenHashSet();
        valid.add(42);
        valid.add(7);
        assertEquals(42, BlockItemMappingLayout.sanitizeBlockRuntimeId(valid, 99));
        assertEquals(7, BlockItemMappingLayout.sanitizeBlockRuntimeId(valid, 7));
    }

    @Test
    void motCustomBlockUsesNegativeItemId() {
        assertEquals(-9745, BlockItemMappingLayout.customBlockItemId(10000));
        final CompoundTag properties = new CompoundTag();
        final CompoundTag vanilla = new CompoundTag();
        vanilla.putInt("block_id", 10000);
        properties.put("vanilla_block_data", vanilla);
        assertEquals(-9745, BlockItemMappingLayout.customBlockItemId(properties));
    }

    @Test
    void mergeSynthesizesMissingCustomBlockItems() {
        final CompoundTag properties = new CompoundTag();
        final CompoundTag vanilla = new CompoundTag();
        vanilla.putInt("block_id", 10000);
        properties.put("vanilla_block_data", vanilla);
        final Map<String, CompoundTag> customBlocks = new LinkedHashMap<>();
        customBlocks.put("askyblockwar:war_hall", properties);

        final ItemEntry[] merged = BlockItemMappingLayout.mergeCustomBlockItems(new ItemEntry[0], customBlocks);
        assertEquals(1, merged.length);
        assertEquals("askyblockwar:war_hall", merged[0].identifier());
        assertEquals(-9745, merged[0].id());
        assertEquals(ItemVersion.None, merged[0].version());
        assertEquals(false, merged[0].componentBased());
        assertEquals(null, merged[0].componentData());
    }

    @Test
    void signedAndUnsignedItemIdsShareTheSameCustomBlockAlias() {
        assertEquals(-9745, BlockItemMappingLayout.customBlockItemId(10000));
        assertEquals(55791, BlockItemMappingLayout.customBlockItemId(10000) + 65536);
    }

    @Test
    void mergeKeepsExistingRegistryEntries() {
        final ItemEntry existing = new ItemEntry("askyblockwar:war_hall", 42, false, ItemVersion.None, null);
        final CompoundTag properties = new CompoundTag();
        final CompoundTag vanilla = new CompoundTag();
        vanilla.putInt("block_id", 10000);
        properties.put("vanilla_block_data", vanilla);
        final Map<String, CompoundTag> customBlocks = new LinkedHashMap<>();
        customBlocks.put("askyblockwar:war_hall", properties);

        final ItemEntry[] merged = BlockItemMappingLayout.mergeCustomBlockItems(new ItemEntry[]{existing}, customBlocks);
        assertEquals(1, merged.length);
        assertEquals(42, merged[0].id());
    }
}
