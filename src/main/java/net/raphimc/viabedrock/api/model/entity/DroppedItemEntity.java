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
package net.raphimc.viabedrock.api.model.entity;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.item.Item;

import java.util.Objects;
import java.util.UUID;

public class DroppedItemEntity extends Entity {

    private Item item;

    public DroppedItemEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final UUID javaUuid, final EntityTypes1_21_11 javaType, final Integer customJavaTypeId) {
        super(user, uniqueId, runtimeId, type, javaId, javaUuid, javaType, customJavaTypeId);
    }

    public DroppedItemEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final UUID javaUuid, final EntityTypes1_21_11 javaType) {
        this(user, uniqueId, runtimeId, type, javaId, javaUuid, javaType, null);
    }

    public void setItem(final Item item) {
        this.item = Objects.requireNonNull(item, "item").copy();
    }

    public Item updateItemAmount(final int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Dropped item amount must be positive");
        }
        if (this.item == null) {
            throw new IllegalStateException("Dropped item has not been initialized");
        }

        final Item updatedItem = this.item.copy();
        updatedItem.setAmount(amount);
        this.item = updatedItem;
        return updatedItem.copy();
    }

    public Item item() {
        if (this.item == null) {
            throw new IllegalStateException("Dropped item has not been initialized");
        }
        return this.item.copy();
    }

}
