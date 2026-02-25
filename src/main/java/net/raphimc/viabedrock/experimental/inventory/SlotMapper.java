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

import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

public class SlotMapper {

    public record BedrockSlotRef(int containerId, int slot, Container container) {}

    /**
     * Resolves a Java slot in the Player Inventory Window (containerId=0) to its Bedrock equivalent.
     * Returns null for unsupported slots (e.g. crafting output slot 0).
     */
    public static BedrockSlotRef resolvePlayerInventory(int javaSlot, InventoryTracker tracker) {
        if (javaSlot == 0) {
            // Crafting output slot — handled by CraftingSimulator
            return null;
        } else if (javaSlot >= 1 && javaSlot <= 4) {
            // Crafting input slots → HUD container slots 28-31
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 27 + javaSlot, tracker.getHudContainer());
        } else if (javaSlot >= 5 && javaSlot <= 8) {
            // Armor slots → Armor container slots 0-3
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_ARMOR.getValue(), javaSlot - 5, tracker.getArmorContainer());
        } else if (javaSlot >= 9 && javaSlot <= 35) {
            // Main inventory slots 9-35 → Inventory container (direct mapping)
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), javaSlot, tracker.getInventoryContainer());
        } else if (javaSlot >= 36 && javaSlot <= 44) {
            // Hotbar slots 36-44 → Inventory container slots 0-8
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), javaSlot - 36, tracker.getInventoryContainer());
        } else if (javaSlot == 45) {
            // Offhand slot → Offhand container slot 0
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_OFFHAND.getValue(), 0, tracker.getOffhandContainer());
        }
        return null;
    }

    /**
     * Resolves a Java slot in the Crafting Table window to its Bedrock equivalent.
     * Java crafting window: slot 0=output, 1-9=3x3 grid, 10-36=main inv, 37-45=hotbar
     * Returns null for output slot 0 (handled by CraftingSimulator).
     */
    public static BedrockSlotRef resolveCraftingTable(int javaSlot, InventoryTracker tracker) {
        if (javaSlot == 0) {
            // Output slot — handled by CraftingSimulator
            return null;
        } else if (javaSlot >= 1 && javaSlot <= 9) {
            // 3x3 grid → HUD container slots 32-40
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 31 + javaSlot, tracker.getHudContainer());
        } else if (javaSlot >= 10 && javaSlot <= 36) {
            // Main inventory → Inventory container slots 9-35
            int inventorySlot = 9 + (javaSlot - 10);
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), inventorySlot, tracker.getInventoryContainer());
        } else if (javaSlot >= 37 && javaSlot <= 45) {
            // Hotbar → Inventory container slots 0-8
            int hotbarSlot = javaSlot - 37;
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), hotbarSlot, tracker.getInventoryContainer());
        }
        return null;
    }

    /**
     * Resolves a Java slot to its Bedrock equivalent based on the container context.
     * For containerId=0, delegates to resolvePlayerInventory.
     * For CraftingTableContainer, delegates to resolveCraftingTable.
     * For other containers, maps based on container size.
     * Returns null for unsupported slots.
     */
    public static BedrockSlotRef resolve(int javaContainerId, int javaSlot, InventoryTracker tracker) {
        if (javaContainerId == 0) {
            return resolvePlayerInventory(javaSlot, tracker);
        }

        // Container window
        final Container currentContainer = tracker.getCurrentContainer();
        if (currentContainer == null) {
            return null;
        }

        if (currentContainer instanceof CraftingTableContainer) {
            return resolveCraftingTable(javaSlot, tracker);
        }

        final int containerSize = currentContainer.size();

        if (javaSlot >= 0 && javaSlot < containerSize) {
            // Slots within the container itself
            return new BedrockSlotRef(currentContainer.containerId(), javaSlot, currentContainer);
        } else if (javaSlot >= containerSize && javaSlot < containerSize + 27) {
            // Player inventory slots 9-35 (main inventory area below the container)
            int inventorySlot = 9 + (javaSlot - containerSize);
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), inventorySlot, tracker.getInventoryContainer());
        } else if (javaSlot >= containerSize + 27 && javaSlot < containerSize + 36) {
            // Hotbar slots 0-8
            int hotbarSlot = javaSlot - containerSize - 27;
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), hotbarSlot, tracker.getInventoryContainer());
        }
        return null;
    }

    /**
     * Gets the cursor item from HUD container slot 0.
     */
    public static BedrockItem getCursorItem(InventoryTracker tracker) {
        return tracker.getHudContainer().getItem(0);
    }

}
