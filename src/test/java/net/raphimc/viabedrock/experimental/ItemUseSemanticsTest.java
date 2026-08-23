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
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("easecation:stackable_potion_heal", null, itemUse, 31, true));
    }

    @Test
    void customConsumableUsesNonDefaultDuration() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(12, UseAnimation.EAT);

        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 11));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 12));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 11, true));
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
    void vanillaConsumablesKeepOfficialDurationAndFinishEarlyOnNetEase() {
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:potion", null, null, 31));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:potion", null, null, 32));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 32));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 30, true));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 31, true));
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
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:ominous_bottle", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:glow_berries", Set.of("minecraft:is_food"), null));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:ominous_bottle", null, null, false));
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
    void neteaseConsumablesSkipMobEquipmentAndFinishWithoutRelease() {
        assertTrue(ItemUseSemantics.skipMobEquipmentBeforeUse(true, true));
        assertFalse(ItemUseSemantics.skipMobEquipmentBeforeUse(false, true));
        assertFalse(ItemUseSemantics.skipMobEquipmentBeforeUse(true, false));
        assertFalse(ItemUseSemantics.sendConsumableFinishTransaction(true, true));
        assertTrue(ItemUseSemantics.sendConsumableFinishTransaction(false, true));
        assertFalse(ItemUseSemantics.sendConsumableFinishTransaction(true, false));
        assertFalse(ItemUseSemantics.delayReleaseAfterConsumableFinish(true, true));
        assertTrue(ItemUseSemantics.delayReleaseAfterConsumableFinish(false, true));
        assertFalse(ItemUseSemantics.delayReleaseAfterConsumableFinish(true, false));
        assertTrue(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(true, true, false));
        assertFalse(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(true, true, true));
        assertFalse(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(false, true, false));
        assertFalse(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(true, false, false));
        assertTrue(ItemUseSemantics.javaUsingVisible(true, true, 31));
        assertFalse(ItemUseSemantics.javaUsingVisible(true, true, 32));
        assertTrue(ItemUseSemantics.javaUsingVisible(true, false, 40));
        assertTrue(ItemUseSemantics.ignoreJavaConsumableRelease(true, true));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(false, true));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(true, false));
        assertTrue(ItemUseSemantics.suppressStartSprintingWhileUsingItem(true, true));
        assertFalse(ItemUseSemantics.suppressStartSprintingWhileUsingItem(false, true));
        assertFalse(ItemUseSemantics.suppressStartSprintingWhileUsingItem(true, false));
        assertTrue(ItemUseSemantics.consumableConsumedByServer(322, 8, false, 322, 7));
        assertTrue(ItemUseSemantics.consumableConsumedByServer(322, 1, true, 0, 0));
        assertFalse(ItemUseSemantics.consumableConsumedByServer(322, 8, false, 322, 8));
        assertFalse(ItemUseSemantics.localUsingTimedOut(true, true, 47));
        assertTrue(ItemUseSemantics.localUsingTimedOut(true, true, 48));
        assertFalse(ItemUseSemantics.localUsingTimedOut(false, true, 40));
    }

    @Test
    void neteaseConsumablesDropDuplicateStartAndAuthInputInteraction() {
        assertTrue(ItemUseSemantics.ignoreDuplicateUseStart(true, true));
        assertFalse(ItemUseSemantics.ignoreDuplicateUseStart(false, true));
        assertFalse(ItemUseSemantics.ignoreDuplicateUseStart(true, false));
        assertFalse(ItemUseSemantics.attachAuthInputItemInteraction(true, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(false, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(true, false));
        assertFalse(ItemUseSemantics.attachAuthInputItemInteraction(true, false, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(false, false, true));
        assertFalse(ItemUseSemantics.attachAuthInputItemInteraction(true, false, false, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(false, false, false, true));
        assertFalse(ItemUseSemantics.sendStartItemUseOnForBow(true));
        assertTrue(ItemUseSemantics.sendStartItemUseOnForBow(false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(false, false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false, false));
        assertTrue(ItemUseSemantics.emulateShieldAsSneak(true, true));
        assertFalse(ItemUseSemantics.emulateShieldAsSneak(false, true));
        assertTrue(ItemUseSemantics.rejectNetEaseOffhandUse(true, true, false));
        assertFalse(ItemUseSemantics.rejectNetEaseOffhandUse(true, true, true));
        assertFalse(ItemUseSemantics.canStartBow(true, true, false, false));
        assertTrue(ItemUseSemantics.canStartBow(true, true, true, false));
        assertFalse(ItemUseSemantics.canStartConsumable(true, "minecraft:apple", true, false, false));
        assertTrue(ItemUseSemantics.canStartConsumable(true, "minecraft:golden_apple", true, false, false));
        assertFalse(ItemUseSemantics.crossbowFireReady(true, true, 10));
        assertTrue(ItemUseSemantics.crossbowFireReady(true, true, 11));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:ender_pearl", true));
        assertFalse(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:bow", true));
        assertTrue(ItemUseSemantics.chargedCrossbowUsesMotTag(true, true, false));
        assertFalse(ItemUseSemantics.chargedCrossbowUsesMotTag(true, false, true));
        assertTrue(ItemUseSemantics.chargedCrossbowUsesMotTag(false, false, true));
        assertTrue(ItemUseSemantics.matchesUseItem(true, 1, (short) 0, 9, "a", 1, (short) 0, 8, "b"));
        assertFalse(ItemUseSemantics.matchesUseItem(false, 1, (short) 0, 9, "a", 1, (short) 0, 8, "b"));
    }

}
