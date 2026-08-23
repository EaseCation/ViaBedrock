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
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * Pre-click copy of the SAI mirror. NetEase 860 error ITEM_STACK_RESPONSE
 * packets omit container contents, so a rejected click has to restore this
 * snapshot instead of keeping the optimistic Java prediction.
 */
public final class InventorySnapshot {

    private final BedrockItem[] inventory;
    private final BedrockItem[] hud;
    private final BedrockItem[] armor;
    private final BedrockItem[] offhand;
    private final BedrockItem[] current;

    private InventorySnapshot(final BedrockItem[] inventory, final BedrockItem[] hud, final BedrockItem[] armor,
                              final BedrockItem[] offhand, final BedrockItem[] current) {
        this.inventory = inventory;
        this.hud = hud;
        this.armor = armor;
        this.offhand = offhand;
        this.current = current;
    }

    public static InventorySnapshot capture(final InventoryTracker tracker) {
        if (tracker == null) {
            return null;
        }
        final Container open = tracker.getCurrentContainer();
        return new InventorySnapshot(
                copy(tracker.getInventoryContainer()),
                copy(tracker.getHudContainer()),
                copy(tracker.getArmorContainer()),
                copy(tracker.getOffhandContainer()),
                open != null && open != tracker.getInventoryContainer() ? copy(open) : null
        );
    }

    public void restore(final InventoryTracker tracker) {
        if (tracker == null) {
            return;
        }
        restoreInto(tracker.getInventoryContainer(), this.inventory);
        restoreInto(tracker.getHudContainer(), this.hud);
        restoreInto(tracker.getArmorContainer(), this.armor);
        restoreInto(tracker.getOffhandContainer(), this.offhand);
        final Container open = tracker.getCurrentContainer();
        if (open != null && open != tracker.getInventoryContainer() && this.current != null) {
            restoreInto(open, this.current);
        }
    }

    private static BedrockItem[] copy(final Container container) {
        if (container == null) {
            return null;
        }
        final BedrockItem[] items = container.getItems();
        final BedrockItem[] copy = new BedrockItem[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] != null ? items[i].copy() : BedrockItem.empty();
        }
        return copy;
    }

    private static void restoreInto(final Container container, final BedrockItem[] items) {
        if (container == null || items == null) {
            return;
        }
        final int size = Math.min(container.size(), items.length);
        for (int i = 0; i < size; i++) {
            container.setItemSilent(i, items[i] != null ? items[i].copy() : BedrockItem.empty());
        }
    }
}
