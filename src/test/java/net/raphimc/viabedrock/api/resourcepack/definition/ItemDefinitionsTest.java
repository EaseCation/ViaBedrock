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
package net.raphimc.viabedrock.api.resourcepack.definition;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.DoubleTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.LongTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.protocol.rewriter.BedrockArmorProtectionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDefinitionsTest {

    @Test
    void mergesResourceAnimationWithLegacyNetworkConsumableComponents() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});
        final JsonObject resourceComponents = new JsonObject();
        resourceComponents.addProperty("minecraft:icon", "stackable_potion_heal");
        resourceComponents.addProperty("minecraft:use_animation", "drink");
        definitions.addFromResourceComponents("easecation:stackable_potion_heal", resourceComponents);

        definitions.addFromNetworkTag("easecation:stackable_potion_heal", consumableTag(new IntTag(32), null));

        final ItemDefinitions.ItemDefinition definition = definitions.get("easecation:stackable_potion_heal");
        final ItemDefinitions.ItemUseDefinition itemUse = definition.itemUseDefinition();
        assertEquals("stackable_potion_heal", definition.iconComponent());
        assertTrue(definition.networkDefinition());
        assertNotNull(itemUse);
        assertEquals(32, itemUse.useDurationTicks());
        assertEquals(ItemDefinitions.UseAnimation.DRINK, itemUse.animation());
    }

    @Test
    void networkUseAnimationOverridesResourceAnimation() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});
        final JsonObject resourceComponents = new JsonObject();
        resourceComponents.addProperty("minecraft:use_animation", "drink");
        definitions.addFromResourceComponents("test:food", resourceComponents);

        definitions.addFromNetworkTag("test:food", consumableTag(new IntTag(20), 1));

        final ItemDefinitions.ItemUseDefinition itemUse = definitions.get("test:food").itemUseDefinition();
        assertNotNull(itemUse);
        assertEquals(20, itemUse.useDurationTicks());
        assertEquals(ItemDefinitions.UseAnimation.EAT, itemUse.animation());
    }

    @Test
    void resourceAnimationWithoutFoodIsNotConsumable() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});
        final JsonObject resourceComponents = new JsonObject();
        resourceComponents.addProperty("minecraft:use_animation", "drink");
        definitions.addFromResourceComponents("test:cosmetic", resourceComponents);

        definitions.addFromNetworkTag("test:cosmetic", componentsTag(new CompoundTag()));

        assertNull(definitions.get("test:cosmetic").itemUseDefinition());
    }

    @Test
    void missingAnimationAndDurationUseFoodDefaults() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});

        definitions.addFromNetworkTag("test:food", consumableTag(null, null));

        final ItemDefinitions.ItemUseDefinition itemUse = definitions.get("test:food").itemUseDefinition();
        assertNotNull(itemUse);
        assertEquals(ItemDefinitions.DEFAULT_USE_DURATION_TICKS, itemUse.useDurationTicks());
        assertEquals(ItemDefinitions.UseAnimation.EAT, itemUse.animation());
    }

    @Test
    void invalidUseDurationsDegradeAndWarnOnce() {
        final List<String> warnings = new ArrayList<>();
        final ItemDefinitions definitions = new ItemDefinitions(warnings::add);

        definitions.addFromNetworkTag("test:broken_food", consumableTag(new IntTag(0), null));
        definitions.addFromNetworkTag("test:broken_food", consumableTag(new LongTag(ItemDefinitions.MAX_USE_DURATION_TICKS + 1L), null));

        assertEquals(ItemDefinitions.DEFAULT_USE_DURATION_TICKS, definitions.get("test:broken_food").itemUseDefinition().useDurationTicks());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("test:broken_food"));
    }

    @Test
    void rejectsInvalidUseDurationValues() {
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new StringTag("bad")));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new IntTag(0)));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new IntTag(-1)));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new DoubleTag(1.5D)));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new DoubleTag(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new DoubleTag(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseUseDurationTicks(new LongTag(ItemDefinitions.MAX_USE_DURATION_TICKS + 1L)));
    }

    @Test
    void parsesAndReplacesNetworkArmorProtection() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});

        definitions.addFromNetworkTag("test:armor", itemTag(new IntTag(5)));
        assertEquals(5, definitions.get("test:armor").armorProtection());
        assertTrue(definitions.get("test:armor").networkDefinition());

        definitions.addFromNetworkTag("test:armor", itemTag(new IntTag(7)));
        assertEquals(7, definitions.get("test:armor").armorProtection());
    }

    @Test
    void leavesMissingArmorComponentUnset() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});
        final CompoundTag itemTag = new CompoundTag();
        itemTag.put("components", new CompoundTag());

        definitions.addFromNetworkTag("test:hat", itemTag);

        assertNull(definitions.get("test:hat").armorProtection());
    }

    @Test
    void rejectsInvalidArmorProtectionValues() {
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(new StringTag("bad")));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(armorTag(new StringTag("bad"))));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(armorTag(new IntTag(-1))));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(armorTag(new DoubleTag(1.5D))));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(armorTag(new DoubleTag(Double.NaN))));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(armorTag(new DoubleTag(Double.POSITIVE_INFINITY))));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitions.parseArmorProtection(armorTag(new LongTag((long) Integer.MAX_VALUE + 1L))));
    }

    @Test
    void degradesMalformedNetworkArmorToZeroAndWarnsOnce() {
        final List<String> warnings = new ArrayList<>();
        final ItemDefinitions definitions = new ItemDefinitions(warnings::add);
        final CompoundTag malformed = itemTag(new StringTag("bad"));

        definitions.addFromNetworkTag("test:broken", malformed);
        definitions.addFromNetworkTag("test:broken", malformed);

        assertEquals(0, definitions.get("test:broken").armorProtection());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("test:broken"));
    }

    @Test
    void networkDefinitionsOverrideVanillaAndUnknownItemsStayZero() {
        final ItemDefinitions definitions = new ItemDefinitions(message -> {});
        final BedrockArmorProtectionRegistry registry = new BedrockArmorProtectionRegistry(definitions, Map.of("minecraft:iron_helmet", 2));

        assertEquals(2, registry.protection("minecraft:iron_helmet"));
        assertEquals(0, registry.protection("test:unknown"));

        definitions.addFromNetworkTag("minecraft:iron_helmet", itemTag(new IntTag(8)));
        assertEquals(8, registry.protection("minecraft:iron_helmet"));

        final CompoundTag componentsOnly = new CompoundTag();
        componentsOnly.put("components", new CompoundTag());
        definitions.addFromNetworkTag("minecraft:iron_helmet", componentsOnly);
        assertEquals(0, registry.protection("minecraft:iron_helmet"));
    }

    private static CompoundTag itemTag(final Tag protection) {
        final CompoundTag itemTag = new CompoundTag();
        final CompoundTag components = new CompoundTag();
        components.put("minecraft:armor", armorTag(protection));
        itemTag.put("components", components);
        return itemTag;
    }

    private static CompoundTag consumableTag(final Tag useDuration, final Integer useAnimation) {
        final CompoundTag components = new CompoundTag();
        components.put("minecraft:food", new CompoundTag());
        if (useDuration != null) {
            components.put("minecraft:use_duration", useDuration);
        }
        if (useAnimation != null) {
            final CompoundTag itemProperties = new CompoundTag();
            itemProperties.putInt("use_animation", useAnimation);
            components.put("item_properties", itemProperties);
        }
        return componentsTag(components);
    }

    private static CompoundTag componentsTag(final CompoundTag components) {
        final CompoundTag itemTag = new CompoundTag();
        itemTag.put("components", components);
        return itemTag;
    }

    private static CompoundTag armorTag(final Tag protection) {
        final CompoundTag armor = new CompoundTag();
        armor.put("protection", protection);
        return armor;
    }

}
