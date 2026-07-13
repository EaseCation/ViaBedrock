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

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DroppedItemEntityTest {

    @Test
    void preservesInitialStackAndUpdatesAmountWithoutMutatingSource() {
        final DroppedItemEntity entity = createEntity();
        final BedrockItem sourceItem = new BedrockItem(1, (short) 0, (byte) 16);

        entity.setItem(sourceItem);
        final Item updatedItem = entity.updateItemAmount(17);

        assertEquals(16, sourceItem.amount());
        assertEquals(17, updatedItem.amount());
        assertEquals(17, entity.item().amount());
        assertNotSame(updatedItem, entity.item());
    }

    @Test
    void supportsConsecutiveStackSizeUpdates() {
        final DroppedItemEntity entity = createEntity();
        entity.setItem(new BedrockItem(1));

        for (int amount : new int[]{2, 16, 17, 33, 49, 64}) {
            assertEquals(amount, entity.updateItemAmount(amount).amount());
        }
        assertEquals(64, entity.item().amount());
    }

    @Test
    void rejectsInvalidOrPrematureUpdates() {
        final DroppedItemEntity entity = createEntity();

        assertThrows(IllegalStateException.class, () -> entity.updateItemAmount(2));
        entity.setItem(new BedrockItem(1));
        assertThrows(IllegalArgumentException.class, () -> entity.updateItemAmount(0));
        assertThrows(IllegalArgumentException.class, () -> entity.updateItemAmount(-1));
    }

    private static DroppedItemEntity createEntity() {
        return new DroppedItemEntity(null, 1L, 2L, "minecraft:item", 3, UUID.randomUUID(), EntityTypes1_21_11.ITEM);
    }

}
