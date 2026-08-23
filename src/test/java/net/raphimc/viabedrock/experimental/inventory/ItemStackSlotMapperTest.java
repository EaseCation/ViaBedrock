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
import net.raphimc.viabedrock.api.model.container.HorseContainer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.test.StubUserConnection;
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

        final net.raphimc.viabedrock.api.model.container.SmithingTableContainer smithing =
                new net.raphimc.viabedrock.api.model.container.SmithingTableContainer(null, (byte) 5, null, new BlockPosition(0, 64, 0));
        assertEquals(1, smithing.javaSlot(0));
        assertEquals(0, smithing.bedrockSlot(1));
        assertEquals(ContainerEnumName.SmithingTableInputContainer, ItemStackSlotMapper.fromOpenContainer(smithing, 0).container());
        assertEquals(51, ItemStackSlotMapper.fromOpenContainer(smithing, 0).slot());
        assertEquals(ContainerEnumName.SmithingTableTemplateContainer, ItemStackSlotMapper.fromOpenContainer(smithing, 2).container());
    }

    @Test
    void chestAndBarrelStayOnLevelEntityWhenChestTagsArePresent() {
        final ChestContainer chest = new ChestContainer(null, (byte) 5, null, new BlockPosition(0, 64, 0), 27);
        assertEquals(ContainerEnumName.LevelEntityContainer, ItemStackSlotMapper.fromOpenContainer(chest, 3).container());
        assertEquals(3, ItemStackSlotMapper.fromOpenContainer(chest, 3).slot());
    }

    @Test
    void responseSlotsInvertPlayerAndOpenContainerMappings() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);

            final SlotMapper.BedrockSlotRef hotbar = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.HotbarContainer, null), 3);
            assertEquals(ContainerID.CONTAINER_ID_INVENTORY.getValue(), hotbar.containerId());
            assertEquals(3, hotbar.slot());

            final SlotMapper.BedrockSlotRef cursor = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.CursorContainer, null), 0);
            assertEquals(0, cursor.slot());
            assertEquals(tracker.getHudContainer(), cursor.container());

            final SlotMapper.BedrockSlotRef created = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), 50);
            assertEquals(50, created.slot());

            final AnvilContainer anvil = new AnvilContainer(user, (byte) 1, null, new BlockPosition(0, 64, 0));
            tracker.setCurrentContainer(anvil);
            final SlotMapper.BedrockSlotRef anvilInput = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.AnvilInputContainer, null), 1);
            assertEquals(0, anvilInput.slot());
            assertEquals(anvil, anvilInput.container());
        } finally {
            channel.finishAndReleaseAll();
        }

        final EmbeddedChannel chestChannel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(chestChannel);
            final InventoryTracker tracker = new InventoryTracker(user);
            final ChestContainer chest = new ChestContainer(user, (byte) 5, null, new BlockPosition(0, 64, 0), 27);
            tracker.setCurrentContainer(chest);
            final SlotMapper.BedrockSlotRef chestSlot = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.LevelEntityContainer, null), 5);
            assertEquals(5, chestSlot.slot());
            assertEquals(chest, chestSlot.container());
        } finally {
            chestChannel.finishAndReleaseAll();
        }
    }

    @Test
    void horseSaddleArmorUseHorseEquipAndCargoUsesLevelEntity() {
        final HorseContainer horse = new HorseContainer(null, (byte) 12, null, new BlockPosition(0, 0, 0), 17, 99L, 7);
        assertEquals(ContainerEnumName.HorseEquipContainer, ItemStackSlotMapper.fromOpenContainer(horse, 0).container());
        assertEquals(0, ItemStackSlotMapper.fromOpenContainer(horse, 0).slot());
        assertEquals(ContainerEnumName.HorseEquipContainer, ItemStackSlotMapper.fromOpenContainer(horse, 1).container());
        assertEquals(ContainerEnumName.LevelEntityContainer, ItemStackSlotMapper.fromOpenContainer(horse, 2).container());
        assertEquals(2, ItemStackSlotMapper.fromOpenContainer(horse, 2).slot());
        assertEquals(ContainerEnumName.LevelEntityContainer, ItemStackSlotMapper.fromOpenContainer(horse, 16).container());
        assertEquals(5, horse.javaColumns());
    }

    @Test
    void horseResponseSlotsStayOnHorseInventory() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final HorseContainer horse = new HorseContainer(user, (byte) 12, null, new BlockPosition(0, 0, 0), 17, 99L, 7);
            tracker.setCurrentContainer(horse);

            final SlotMapper.BedrockSlotRef saddle = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.HorseEquipContainer, null), 0);
            assertEquals(0, saddle.slot());
            assertEquals(horse, saddle.container());

            final SlotMapper.BedrockSlotRef cargo = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.CombinedHotbarAndInventoryContainer, null), 5);
            assertEquals(5, cargo.slot());
            assertEquals(horse, cargo.container());

            final SlotMapper.BedrockSlotRef playerHotbar = ItemStackSlotMapper.resolveResponseSlot(
                    tracker, new FullContainerName(ContainerEnumName.HotbarContainer, null), 3);
            assertEquals(3, playerHotbar.slot());
            assertEquals(tracker.getInventoryContainer(), playerHotbar.container());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}

