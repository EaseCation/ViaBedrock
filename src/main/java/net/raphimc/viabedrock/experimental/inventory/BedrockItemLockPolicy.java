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

import com.viaversion.nbt.tag.CompoundTag;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.List;

public final class BedrockItemLockPolicy {

    private static final String ITEM_LOCK_TAG = "minecraft:item_lock";
    private static final byte LOCK_IN_SLOT = 1;
    private static final byte LOCK_IN_INVENTORY = 2;

    private BedrockItemLockPolicy() {
    }

    public static boolean canDrop(final BedrockItem item) {
        final byte lockMode = lockMode(item);
        return lockMode != LOCK_IN_SLOT && lockMode != LOCK_IN_INVENTORY;
    }

    public static boolean allows(final List<InventoryActionData> actions) {
        for (final InventoryActionData action : actions) {
            if (isNoOp(action)) {
                continue;
            }

            final byte fromLockMode = lockMode(action.fromItem());
            final byte toLockMode = lockMode(action.toItem());
            if (fromLockMode == LOCK_IN_SLOT || toLockMode == LOCK_IN_SLOT) {
                return false;
            }
            if ((fromLockMode == LOCK_IN_INVENTORY || toLockMode == LOCK_IN_INVENTORY)
                    && !isPlayerInventoryLocation(action)) {
                return false;
            }
        }
        return true;
    }

    private static byte lockMode(final BedrockItem item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        final CompoundTag tag = item.tag();
        return tag != null ? tag.getByte(ITEM_LOCK_TAG, (byte) 0) : 0;
    }

    private static boolean isNoOp(final InventoryActionData action) {
        final BedrockItem fromItem = action.fromItem();
        final BedrockItem toItem = action.toItem();
        return fromItem != null && toItem != null
                && !fromItem.isDifferent(toItem)
                && fromItem.amount() == toItem.amount();
    }

    private static boolean isPlayerInventoryLocation(final InventoryActionData action) {
        if (action.source().type() != InventorySourceType.ContainerInventory) {
            return false;
        }

        final int containerId = action.source().containerId();
        return containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()
                || containerId == ContainerID.CONTAINER_ID_ARMOR.getValue()
                || containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()
                || containerId == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue() && action.slot() == 0;
    }

}
