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

import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.container.player.OffhandContainer;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

public record ItemUseHandContext(
        InteractionHand hand,
        byte containerId,
        int containerSlot,
        int transactionHotbarSlot,
        BedrockItem item
) {

    public static final int JAVA_OFFHAND_HOTBAR_SLOT = -1;

    public static ItemUseHandContext resolve(final InventoryTracker inventoryTracker, final InteractionHand hand) {
        final InventoryContainer inventory = inventoryTracker.getInventoryContainer();
        final OffhandContainer offhand = inventoryTracker.getOffhandContainer();
        return create(
                hand,
                inventory.containerId(),
                inventory.getSelectedHotbarSlot(),
                inventory.getSelectedHotbarItem(),
                offhand.containerId(),
                offhand.getItem(0)
        );
    }

    static ItemUseHandContext create(final InteractionHand hand, final byte mainContainerId, final int mainSlot, final BedrockItem mainItem, final byte offhandContainerId, final BedrockItem offhandItem) {
        return switch (hand) {
            case MAIN_HAND -> new ItemUseHandContext(hand, mainContainerId, mainSlot, mainSlot, mainItem);
            case OFF_HAND -> new ItemUseHandContext(hand, offhandContainerId, 0, JAVA_OFFHAND_HOTBAR_SLOT, offhandItem);
        };
    }

    public boolean isMainHand() {
        return this.hand == InteractionHand.MAIN_HAND;
    }

}
