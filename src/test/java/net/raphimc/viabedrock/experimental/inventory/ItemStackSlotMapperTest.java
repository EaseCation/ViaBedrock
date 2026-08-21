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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.api.model.container.AnvilContainer;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.api.model.container.FurnaceContainer;
import net.raphimc.viabedrock.api.model.container.GenericContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemStackSlotMapperTest {

    @Test
    void playerHotbarAndMainInventoryStayOnBedrockSlotNumbers() {
        assertEquals(ContainerEnumName.HotbarContainer, ItemStackSlotMapper.playerInventory(0).container());
        assertEquals(0, ItemStackSlotMapper.playerInventory(0).slot());
        assertEquals(ContainerEnumName.InventoryContainer, ItemStackSlotMapper.playerInventory(9).container());
        assertEquals(9, ItemStackSlotMapper.playerInventory(9).slot());
        assertEquals(ContainerEnumName.InventoryContainer, ItemStackSlotMapper.playerInventory(35).container());
        assertNull(ItemStackSlotMapper.playerInventory(36));
    }

    @Test
    void hudCursorAndCraftingSlotsUseNukkitUiIndexes() {
        assertEquals(ContainerEnumName.CursorContainer, ItemStackSlotMapper.hud(0).container());
        assertEquals(0, ItemStackSlotMapper.hud(0).slot());
        assertEquals(ContainerEnumName.CraftingInputContainer, ItemStackSlotMapper.hud(28).container());
        assertEquals(28, ItemStackSlotMapper.hud(28).slot());
        assertEquals(ContainerEnumName.CreatedOutputContainer, ItemStackSlotMapper.hud(50).container());
        assertEquals(50, ItemStackSlotMapper.hud(50).slot());
    }

    @Test
    void anvilFurnaceEnchantAndHopperUseNukkitNetworkSlots() {
        final AnvilContainer anvil = new AnvilContainer(null, (byte) 1, null, new BlockPosition(0, 64, 0));
        assertEquals(ContainerEnumName.AnvilInputContainer, ItemStackSlotMapper.fromOpenContainer(anvil, 0).container());
        assertEquals(1, ItemStackSlotMapper.fromOpenContainer(anvil, 0).slot());
        assertEquals(ContainerEnumName.AnvilMaterialContainer, ItemStackSlotMapper.fromOpenContainer(anvil, 1).container());
        assertEquals(2, ItemStackSlotMapper.fromOpenContainer(anvil, 1).slot());

        final FurnaceContainer furnace = new FurnaceContainer(null, (byte) 2, ContainerType.FURNACE, null, new BlockPosition(0, 64, 0));
        assertEquals(ContainerEnumName.FurnaceIngredientContainer, ItemStackSlotMapper.fromOpenContainer(furnace, 0).container());
        assertEquals(0, ItemStackSlotMapper.fromOpenContainer(furnace, 0).slot());
        assertEquals(ContainerEnumName.FurnaceFuelContainer, ItemStackSlotMapper.fromOpenContainer(furnace, 1).container());
        assertEquals(ContainerEnumName.FurnaceResultContainer, ItemStackSlotMapper.fromOpenContainer(furnace, 2).container());

        final GenericContainer enchant = new GenericContainer(null, (byte) 3, ContainerType.ENCHANTMENT, null, new BlockPosition(0, 64, 0), 2);
        assertEquals(ContainerEnumName.EnchantingInputContainer, ItemStackSlotMapper.fromOpenContainer(enchant, 0).container());
        assertEquals(14, ItemStackSlotMapper.fromOpenContainer(enchant, 0).slot());
        assertEquals(ContainerEnumName.EnchantingMaterialContainer, ItemStackSlotMapper.fromOpenContainer(enchant, 1).container());
        assertEquals(15, ItemStackSlotMapper.fromOpenContainer(enchant, 1).slot());

        final GenericContainer hopper = new GenericContainer(null, (byte) 4, ContainerType.HOPPER, null, new BlockPosition(0, 64, 0), 5);
        assertEquals(ContainerEnumName.LevelEntityContainer, ItemStackSlotMapper.fromOpenContainer(hopper, 0).container());
        assertEquals(0, ItemStackSlotMapper.fromOpenContainer(hopper, 0).slot());
    }

    @Test
    void chestAndBarrelStayOnLevelEntityWhenChestTagsArePresent() {
        final ChestContainer chest = new ChestContainer(null, (byte) 5, null, new BlockPosition(0, 64, 0), 27);
        assertEquals(ContainerEnumName.LevelEntityContainer, ItemStackSlotMapper.fromOpenContainer(chest, 3).container());
        assertEquals(3, ItemStackSlotMapper.fromOpenContainer(chest, 3).slot());
    }
}

