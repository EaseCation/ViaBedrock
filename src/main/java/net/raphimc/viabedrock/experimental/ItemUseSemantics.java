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
package net.raphimc.viabedrock.experimental;

import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemReleaseInventoryTransaction_ActionType;

import java.util.Set;

final class ItemUseSemantics {

    private static final int DEFAULT_CONSUMABLE_USE_TICKS = 32;
    private static final String FOOD_ITEM_TAG = "minecraft:is_food";
    private static final Set<String> CONSUME_ON_RELEASE_ITEMS = Set.of(
            "minecraft:potion",
            "minecraft:milk_bucket"
    );
    private static final Set<String> RELEASE_ON_RELEASE_ITEMS = Set.of(
            "minecraft:bow",
            "minecraft:crossbow",
            "minecraft:trident",
            "minecraft:brush",
            "minecraft:spyglass",
            "minecraft:shield"
    );

    static boolean isContinuousUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final boolean chargedCrossbow) {
        if (identifier == null || ("minecraft:crossbow".equals(identifier) && chargedCrossbow)) {
            return false;
        }
        return RELEASE_ON_RELEASE_ITEMS.contains(identifier)
                || CONSUME_ON_RELEASE_ITEMS.contains(identifier)
                || isFood(itemTags)
                || itemUse != null;
    }

    static boolean isConsumableUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse) {
        return consumableUseTicks(identifier, itemTags, itemUse) > 0;
    }

    /**
     * Official Bedrock starts food from PlayerAuthInput START_USING_ITEM +
     * PERFORM_ITEM_INTERACTION. NetEase 860 Nukkit-MOT parses that payload but never
     * handles it, so consumables need a standalone USE_ITEM transaction there.
     * Bows/crossbows already send that standalone packet on both paths.
     */
    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow) {
        return bow || crossbow || (emulateNetEase && consumable);
    }

    /**
     * Official Bedrock still finishes food with a second USE_ITEM plus delayed Release.
     * NetEase 860 auto-completes after the first CLICK_AIR, so repeating that sequence
     * would start eating the next stack item.
     */
    static boolean neteaseAutoCompletesConsumable(final boolean emulateNetEase, final boolean consumable) {
        return emulateNetEase && consumable;
    }

    static ItemReleaseInventoryTransaction_ActionType releaseAction(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final int usingTicks) {
        if (identifier == null || RELEASE_ON_RELEASE_ITEMS.contains(identifier)) {
            return ItemReleaseInventoryTransaction_ActionType.Release;
        }

        final int useDurationTicks = consumableUseTicks(identifier, itemTags, itemUse);
        return useDurationTicks > 0 && usingTicks >= useDurationTicks
                ? ItemReleaseInventoryTransaction_ActionType.Use
                : ItemReleaseInventoryTransaction_ActionType.Release;
    }

    private static int consumableUseTicks(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse) {
        if (CONSUME_ON_RELEASE_ITEMS.contains(identifier) || isFood(itemTags)) {
            return DEFAULT_CONSUMABLE_USE_TICKS;
        }
        return itemUse != null ? itemUse.useDurationTicks() : -1;
    }

    private static boolean isFood(final Set<String> itemTags) {
        return itemTags != null && itemTags.contains(FOOD_ITEM_TAG);
    }

    private ItemUseSemantics() {
    }

}
