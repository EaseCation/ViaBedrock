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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.nbt.tag.CompoundTag;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockItemLockPolicyTest {

    private static final int ITEM_ID = 2;

    @Test
    void onlyKnownLockModesPreventDirectDrops() {
        assertTrue(BedrockItemLockPolicy.canDrop(new BedrockItem(ITEM_ID, (short) 0, (byte) 1)));
        assertTrue(BedrockItemLockPolicy.canDrop(item(1, 0)));
        assertTrue(BedrockItemLockPolicy.canDrop(item(1, 3)));
        assertFalse(BedrockItemLockPolicy.canDrop(item(1, 1)));
        assertFalse(BedrockItemLockPolicy.canDrop(item(1, 2)));
    }

    @Test
    void lockInSlotRejectsMovesAndAmountChanges() {
        final BedrockItem locked = item(2, 1);

        assertFalse(BedrockItemLockPolicy.allows(List.of(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()))));
        assertFalse(BedrockItemLockPolicy.allows(List.of(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, item(1, 1)))));
        assertFalse(BedrockItemLockPolicy.allows(List.of(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 1, BedrockItem.empty(), locked))));
    }

    @Test
    void unchangedLockedSlotIsAllowed() {
        final BedrockItem locked = item(1, 1);

        assertTrue(BedrockItemLockPolicy.allows(List.of(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, locked.copy()))));
    }

    @Test
    void lockInInventoryCanMoveWithinPlayerOwnedStorage() {
        final BedrockItem locked = item(1, 2);

        assertTrue(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI, 0, BedrockItem.empty(), locked))));
        assertTrue(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_ARMOR, 0, BedrockItem.empty(), locked))));
        assertTrue(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_OFFHAND, 0, BedrockItem.empty(), locked))));
    }

    @Test
    void lockInInventoryCanMoveFromExternalContainerIntoPlayerInventory() {
        final BedrockItem locked = item(1, 2);

        assertTrue(BedrockItemLockPolicy.allows(move(
                externalContainerAction(0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, BedrockItem.empty(), locked))));
        assertTrue(BedrockItemLockPolicy.allows(move(
                externalContainerAction(0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI, 0, BedrockItem.empty(), locked))));
    }

    @Test
    void lockInInventoryCanBePartiallyImportedFromExternalContainer() {
        final BedrockItem locked = item(3, 2);
        final BedrockItem remaining = item(1, 2);
        final BedrockItem imported = item(2, 2);

        assertTrue(BedrockItemLockPolicy.allows(move(
                externalContainerAction(0, locked, remaining),
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, BedrockItem.empty(), imported))));
    }

    @Test
    void externalImportMustConserveTheLockedItemCount() {
        final BedrockItem locked = item(2, 2);

        assertFalse(BedrockItemLockPolicy.allows(move(
                externalContainerAction(0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, BedrockItem.empty(), item(1, 2)))));
    }

    @Test
    void lockInInventoryRejectsWorldDrops() {
        final BedrockItem locked = item(1, 2);

        assertFalse(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()),
                action(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), 0,
                        BedrockItem.empty(), locked))));
    }

    @Test
    void lockInInventoryRejectsMovesFromPlayerInventoryToExternalContainersAndCraftingSlots() {
        final BedrockItem locked = item(1, 2);

        assertFalse(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()),
                action(InventorySourceType.ContainerInventory, 5, 0, BedrockItem.empty(), locked))));
        assertFalse(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI, 28, BedrockItem.empty(), locked))));
        assertFalse(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI, 28, locked, BedrockItem.empty()),
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, BedrockItem.empty(), locked))));
    }

    @Test
    void lockInInventoryRejectsMovesBetweenExternalContainers() {
        final BedrockItem locked = item(1, 2);

        assertFalse(BedrockItemLockPolicy.allows(move(
                externalContainerAction(0, locked, BedrockItem.empty()),
                externalContainerAction(1, BedrockItem.empty(), locked))));
    }

    @Test
    void unlockedTransactionsRemainAllowed() {
        final BedrockItem unlocked = item(1, 0);

        assertTrue(BedrockItemLockPolicy.allows(move(
                containerAction(ContainerID.CONTAINER_ID_INVENTORY, 0, unlocked, BedrockItem.empty()),
                action(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), 0,
                        BedrockItem.empty(), unlocked))));
    }

    private static List<InventoryActionData> move(final InventoryActionData first,
                                                   final InventoryActionData second) {
        return List.of(first, second);
    }

    private static InventoryActionData containerAction(final ContainerID containerId, final int slot,
                                                       final BedrockItem from, final BedrockItem to) {
        return action(InventorySourceType.ContainerInventory, containerId.getValue(), slot, from, to);
    }

    private static InventoryActionData externalContainerAction(final int slot,
                                                               final BedrockItem from,
                                                               final BedrockItem to) {
        return action(InventorySourceType.ContainerInventory, 5, slot, from, to);
    }

    private static InventoryActionData action(final InventorySourceType sourceType, final int containerId,
                                              final int slot, final BedrockItem from, final BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(sourceType, containerId, InventorySource_InventorySourceFlags.NoFlag),
                slot, from, to);
    }

    private static BedrockItem item(final int amount, final int lockMode) {
        final CompoundTag tag = new CompoundTag();
        if (lockMode != 0) {
            tag.putByte("minecraft:item_lock", (byte) lockMode);
        }
        return new BedrockItem(ITEM_ID, (short) 0, (byte) amount, tag);
    }

}
