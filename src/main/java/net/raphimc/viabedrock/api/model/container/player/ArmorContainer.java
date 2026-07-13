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
package net.raphimc.viabedrock.api.model.container.player;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.PlayerArmorHudTracker;

public class ArmorContainer extends InventorySubContainer {

    private boolean batchUpdate;

    public ArmorContainer(final UserConnection user) {
        super(user, (byte) ContainerID.CONTAINER_ID_ARMOR.getValue(), ContainerType.ARMOR, 4);
    }

    @Override
    public int javaSlot(final int slot) {
        return 5 + slot;
    }

    @Override
    public boolean setItems(final BedrockItem[] items) {
        this.batchUpdate = true;
        final boolean result;
        try {
            result = super.setItems(items);
        } finally {
            this.batchUpdate = false;
        }
        if (result) {
            this.notifyArmorChanged();
        }
        return result;
    }

    @Override
    public void clearItems() {
        super.clearItems();
        this.notifyArmorChanged();
    }

    @Override
    protected void onSlotChanged(final int slot, final BedrockItem oldItem, final BedrockItem newItem) {
        if (!this.batchUpdate) {
            this.notifyArmorChanged();
        }
    }

    private void notifyArmorChanged() {
        final PlayerArmorHudTracker armorHudTracker = this.user.get(PlayerArmorHudTracker.class);
        if (armorHudTracker != null) {
            armorHudTracker.markDirty();
        }
    }

}
