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
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.SoundEvent;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.data.Consumable1_21_2;
import com.viaversion.viaversion.api.minecraft.item.data.FoodProperties1_21_2;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;

final class CustomItemDataComponents {

    static final String BEDROCK_IDENTIFIER_KEY = "viabedrock:bedrock_identifier";

    static void applyPaperFallbackIdentity(final Item item, final String bedrockIdentifier) {
        final StructuredDataContainer data = item.dataContainer();
        final CompoundTag existingCustomData = data.get(StructuredDataKey.CUSTOM_DATA);
        data.set(StructuredDataKey.CUSTOM_DATA, createPaperFallbackIdentity(existingCustomData, bedrockIdentifier));
    }

    static void applyConsumable(final Item item, final ItemUseDefinition itemUse, final boolean experimentalFeaturesEnabled) {
        final ConsumableComponents components = createConsumableComponents(itemUse, experimentalFeaturesEnabled);
        if (components != null) {
            item.dataContainer().set(StructuredDataKey.CONSUMABLE1_21_2, components.consumable());
            item.dataContainer().set(StructuredDataKey.FOOD1_21_2, components.food());
        }
    }

    static void applyMaxStackSize(final Item item, final Integer maxStackSize, final boolean fallbackOnly) {
        if (maxStackSize == null) {
            return;
        }
        final StructuredDataContainer data = item.dataContainer();
        if (fallbackOnly && (data.get(StructuredDataKey.MAX_STACK_SIZE) != null || data.hasEmpty(StructuredDataKey.MAX_STACK_SIZE))) {
            return;
        }
        data.set(StructuredDataKey.MAX_STACK_SIZE, maxStackSize);
    }

    static CompoundTag createPaperFallbackIdentity(final String bedrockIdentifier) {
        return createPaperFallbackIdentity(null, bedrockIdentifier);
    }

    static CompoundTag createPaperFallbackIdentity(final CompoundTag existingCustomData, final String bedrockIdentifier) {
        final CompoundTag customData = existingCustomData != null ? existingCustomData.copy() : new CompoundTag();
        customData.putString(BEDROCK_IDENTIFIER_KEY, bedrockIdentifier);
        return customData;
    }

    static ConsumableComponents createConsumableComponents(final ItemUseDefinition itemUse, final boolean experimentalFeaturesEnabled) {
        if (!experimentalFeaturesEnabled || itemUse == null) {
            return null;
        }
        return new ConsumableComponents(createConsumable(itemUse), createFoodProperties());
    }

    static Consumable1_21_2 createConsumable(final ItemUseDefinition itemUse) {
        if (itemUse == null) {
            return null;
        }
        return new Consumable1_21_2(
                itemUse.useDurationTicks() / 20F,
                itemUse.animation().javaId(),
                Holder.of(new SoundEvent(itemUse.animation().soundIdentifier(), null)),
                itemUse.animation().consumeParticles(),
                new Consumable1_21_2.ConsumeEffect[0]
        );
    }

    static FoodProperties1_21_2 createFoodProperties() {
        return new FoodProperties1_21_2(0, 0F, true);
    }

    record ConsumableComponents(Consumable1_21_2 consumable, FoodProperties1_21_2 food) {
    }

    private CustomItemDataComponents() {
    }

}
