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
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.UseAnimation;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemReleaseInventoryTransaction_ActionType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseSemanticsTest {

    @Test
    void customConsumableCompletesAtConfiguredDuration() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(32, UseAnimation.DRINK);

        assertTrue(ItemUseSemantics.isContinuousUseItem("easecation:stackable_potion_heal", null, itemUse, false));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("easecation:stackable_potion_heal", null, itemUse, 31));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("easecation:stackable_potion_heal", null, itemUse, 32));
    }

    @Test
    void customConsumableUsesNonDefaultDuration() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(12, UseAnimation.EAT);

        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 11));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 12));
    }

    @Test
    void vanillaContinuousUseBehaviorRemainsAvailable() {
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:potion", null, null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:apple", Set.of("minecraft:is_food"), null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:bow", null, null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:shield", null, null, false));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:crossbow", null, null, true));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:paper", null, null, false));
    }

    @Test
    void vanillaConsumablesKeepThirtyTwoTickThreshold() {
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:potion", null, null, 31));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:potion", null, null, 32));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 32));
    }

    @Test
    void consumableDetectionCoversFoodPotionAndCustomUseItems() {
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:apple", Set.of("minecraft:is_food"), null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:potion", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:milk_bucket", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("test:quick_food", null, new ItemUseDefinition(12, UseAnimation.EAT)));
        assertFalse(ItemUseSemantics.isConsumableUseItem("minecraft:bow", null, null));
        assertFalse(ItemUseSemantics.isConsumableUseItem("minecraft:shield", null, null));
        assertFalse(ItemUseSemantics.isConsumableUseItem("minecraft:paper", null, null));
    }

    @Test
    void neteaseConsumablesNeedStandaloneUseItemWhileOfficialKeepsAuthInputOnly() {
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, true, false, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, false, true, false));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(false, true, false, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(false, false, true, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false));
    }

    @Test
    void neteaseAutoCompletesFoodAfterTheFirstClickAir() {
        assertTrue(ItemUseSemantics.neteaseAutoCompletesConsumable(true, true));
        assertFalse(ItemUseSemantics.neteaseAutoCompletesConsumable(false, true));
        assertFalse(ItemUseSemantics.neteaseAutoCompletesConsumable(true, false));
    }

}
