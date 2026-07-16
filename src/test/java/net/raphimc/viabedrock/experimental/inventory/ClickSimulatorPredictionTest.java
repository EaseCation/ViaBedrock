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

import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.HashedStructuredItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.Enchantments;
import com.viaversion.viaversion.api.minecraft.item.data.Equippable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickSimulatorPredictionTest {

    @Test
    void acceptsAuthoritativeStackWhenClientComponentHashesDiffer() {
        final Item authoritativeItem = enchantedItem(1, 1);
        final HashedItem predictedItem = new HashedStructuredItem(1, 1);
        predictedItem.dataHashesById().put(37, 123456789);

        assertTrue(ClickSimulator.samePredictedStack(authoritativeItem, predictedItem));
    }

    @Test
    void targetPredictionMayChangeAmountButNotItemIdentifier() {
        final Item authoritativeItem = enchantedItem(1, 4);

        assertTrue(ClickSimulator.samePredictedItem(authoritativeItem, new HashedStructuredItem(1, 1)));
        assertFalse(ClickSimulator.samePredictedItem(authoritativeItem, new HashedStructuredItem(2, 1)));
    }

    @Test
    void carriedStackStillRequiresTheAuthoritativeAmount() {
        final Item authoritativeItem = enchantedItem(1, 1);

        assertFalse(ClickSimulator.samePredictedStack(authoritativeItem, new HashedStructuredItem(1, 2)));
        assertFalse(ClickSimulator.samePredictedStack(authoritativeItem, new HashedStructuredItem(2, 1)));
    }

    @Test
    void rejectsGhostArmorPredictionAboveJavaStackLimit() {
        assertTrue(ClickSimulator.isValidPredictedTargetAmount(new HashedStructuredItem(1, 1), 1));
        assertFalse(ClickSimulator.isValidPredictedTargetAmount(new HashedStructuredItem(1, 2), 1));
        assertFalse(ClickSimulator.isValidPredictedTargetAmount(new HashedStructuredItem(1, 100), 99));
        assertFalse(ClickSimulator.isValidPredictedTargetAmount(HashedStructuredItem.empty(), 1));
        assertFalse(ClickSimulator.isValidPredictedTargetAmount(new HashedStructuredItem(1, 1), 0));
    }

    @Test
    void acceptsOnlyTheDeclaredArmorSlot() {
        for (int armorSlot = 0; armorSlot < 4; armorSlot++) {
            final Item item = equippableItem(4 - armorSlot, 1);
            for (int targetSlot = 0; targetSlot < 4; targetSlot++) {
                if (targetSlot == armorSlot) {
                    assertTrue(ClickSimulator.isValidArmorTarget(targetSlot, item));
                } else {
                    assertFalse(ClickSimulator.isValidArmorTarget(targetSlot, item));
                }
            }
        }

        assertFalse(ClickSimulator.isValidArmorTarget(0, equippableItem(0, 1)));
        assertFalse(ClickSimulator.isValidArmorTarget(0, equippableItem(5, 1)));
        assertFalse(ClickSimulator.isValidArmorTarget(0, equippableItem(4, 2)));
    }

    private static Item enchantedItem(final int identifier, final int amount) {
        final Enchantments enchantments = new Enchantments(true);
        enchantments.add(0, 4);
        final StructuredDataContainer data = new StructuredDataContainer(new StructuredData<?>[]{
                StructuredData.of(StructuredDataKey.ENCHANTMENTS1_21_5, enchantments, 37)
        });
        return new StructuredItem(identifier, amount, data);
    }

    private static Item equippableItem(final int equipmentSlot, final int amount) {
        final Equippable equippable = new Equippable(
                equipmentSlot, Holder.of(0), null, null, null, true, true, true);
        final StructuredDataContainer data = new StructuredDataContainer(new StructuredData<?>[]{
                StructuredData.of(StructuredDataKey.EQUIPPABLE1_21_6, equippable, 0)
        });
        return new StructuredItem(1, amount, data);
    }
}
