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
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestContainerTest {

    @Test
    void chestContainerAcceptsBarrelTag() {
        final ChestContainer container = new ChestContainer(null, (byte) 1, null, new BlockPosition(0, 64, 0), 27);
        assertTrue(container.isValidBlockTag(CustomBlockTags.CHEST));
        assertTrue(container.isValidBlockTag(CustomBlockTags.TRAPPED_CHEST));
        assertTrue(container.isValidBlockTag(CustomBlockTags.BARREL));
        assertFalse(container.isValidBlockTag(CustomBlockTags.HOPPER));
        assertFalse(container.isValidBlockTag(null));
    }

    @Test
    void emptyValidBlockTagsSkipClose() {
        final GenericContainer hopperMinecart = new GenericContainer(null, (byte) 2, ContainerType.MINECART_HOPPER, null, new BlockPosition(1, 64, 1), 5);
        assertEquals(5, hopperMinecart.size());
        assertTrue(hopperMinecart.isValidBlockTag(null));
        assertTrue(hopperMinecart.isValidBlockTag("air"));
    }

    @Test
    void hopperKeepsHopperTag() {
        final GenericContainer hopper = new GenericContainer(null, (byte) 3, ContainerType.HOPPER, null, new BlockPosition(2, 64, 2), 5, CustomBlockTags.HOPPER);
        assertTrue(hopper.isValidBlockTag(CustomBlockTags.HOPPER));
        assertFalse(hopper.isValidBlockTag(CustomBlockTags.CHEST));
        assertFalse(hopper.isValidBlockTag(null));
    }

    @Test
    void mismatchedContentLengthIsPaddedOrTruncated() {
        final ChestContainer single = new ChestContainer(null, (byte) 1, null, new BlockPosition(0, 64, 0), 27);
        final BedrockItem[] doubleContents = BedrockItem.emptyArray(54);
        doubleContents[0] = new BedrockItem(3);
        doubleContents[26] = new BedrockItem(4);
        assertTrue(single.setItems(doubleContents));
        assertEquals(3, single.getItem(0).identifier());
        assertEquals(4, single.getItem(26).identifier());

        final ChestContainer large = new ChestContainer(null, (byte) 2, null, new BlockPosition(0, 64, 0), 54);
        final BedrockItem[] shortContents = BedrockItem.emptyArray(27);
        shortContents[0] = new BedrockItem(5);
        assertTrue(large.setItems(shortContents));
        assertEquals(5, large.getItem(0).identifier());
        assertTrue(large.getItem(53).isEmpty());
    }

    @Test
    void mismatchedContentLengthIsPaddedOrTruncatedForOtherMenus() {
        final EnderChestContainer ender = new EnderChestContainer(null, (byte) 4, null, new BlockPosition(0, 64, 0));
        final BedrockItem[] oversized = BedrockItem.emptyArray(54);
        oversized[0] = new BedrockItem(6);
        assertTrue(ender.setItems(oversized));
        assertEquals(6, ender.getItem(0).identifier());
        assertEquals(27, ender.size());

        final ShulkerBoxContainer shulker = new ShulkerBoxContainer(null, (byte) 5, null, new BlockPosition(0, 64, 0));
        final BedrockItem[] shortShulker = BedrockItem.emptyArray(13);
        shortShulker[12] = new BedrockItem(7);
        assertTrue(shulker.setItems(shortShulker));
        assertEquals(7, shulker.getItem(12).identifier());
        assertTrue(shulker.getItem(26).isEmpty());

        final BrewingStandContainer brewing = new BrewingStandContainer(null, (byte) 6, null, new BlockPosition(0, 64, 0));
        final BedrockItem[] brewingItems = BedrockItem.emptyArray(3);
        brewingItems[0] = new BedrockItem(8);
        assertTrue(brewing.setItems(brewingItems));
        assertEquals(8, brewing.getItem(0).identifier());
        assertTrue(brewing.getItem(4).isEmpty());

        final CraftingTableContainer table = new CraftingTableContainer(null, (byte) 7, null, new BlockPosition(0, 64, 0));
        final BedrockItem[] tableItems = BedrockItem.emptyArray(1);
        tableItems[0] = new BedrockItem(9);
        assertTrue(table.setItems(tableItems));
        assertEquals(9, table.getItem(0).identifier());
        assertTrue(table.getItem(9).isEmpty());
    }
}
