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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.minecraft.item.data.Consumable1_21_2;
import com.viaversion.viaversion.api.minecraft.item.data.FoodProperties1_21_2;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.UseAnimation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemDataComponentsTest {

    @Test
    void paperFallbackIdentitySeparatesDifferentBedrockItems() {
        final CompoundTag healData = CustomItemDataComponents.createPaperFallbackIdentity("easecation:stackable_potion_heal");
        final CompoundTag speedData = CustomItemDataComponents.createPaperFallbackIdentity("easecation:stackable_potion_move_speed");
        assertEquals("easecation:stackable_potion_heal", healData.getString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));
        assertEquals("easecation:stackable_potion_move_speed", speedData.getString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));
        assertNotEquals(healData, speedData);
        assertEquals(healData, CustomItemDataComponents.createPaperFallbackIdentity("easecation:stackable_potion_heal"));
    }

    @Test
    void drinkConsumableUsesBedrockDurationWithoutLocalEffects() {
        final Consumable1_21_2 consumable = CustomItemDataComponents.createConsumable(new ItemUseDefinition(32, UseAnimation.DRINK));
        assertEquals(1.6F, consumable.consumeSeconds());
        assertEquals(2, consumable.animationType());
        assertEquals("minecraft:entity.generic.drink", consumable.sound().value().identifier());
        assertFalse(consumable.hasConsumeParticles());
        assertEquals(0, consumable.consumeEffects().length);
    }

    @Test
    void missingItemUseDefinitionDoesNotAddConsumable() {
        assertNull(CustomItemDataComponents.createConsumable(null));
    }

    @Test
    void compatibilityFoodPropertiesDoNotApplyGameplayValues() {
        final FoodProperties1_21_2 food = CustomItemDataComponents.createFoodProperties();
        assertEquals(0, food.nutrition());
        assertEquals(0F, food.saturationModifier());
        assertTrue(food.canAlwaysEat());
    }

}
