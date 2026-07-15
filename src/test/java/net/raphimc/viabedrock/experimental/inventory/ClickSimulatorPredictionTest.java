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

import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.HashedStructuredItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.Enchantments;
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

    private static Item enchantedItem(final int identifier, final int amount) {
        final Enchantments enchantments = new Enchantments(true);
        enchantments.add(0, 4);
        final StructuredDataContainer data = new StructuredDataContainer(new StructuredData<?>[]{
                StructuredData.of(StructuredDataKey.ENCHANTMENTS1_21_5, enchantments, 37)
        });
        return new StructuredItem(identifier, amount, data);
    }
}
