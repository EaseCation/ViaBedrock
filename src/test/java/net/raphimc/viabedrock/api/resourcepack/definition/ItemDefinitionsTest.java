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
import net.raphimc.viabedrock.protocol.rewriter.BedrockArmorProtectionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDefinitionsTest {

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

    private static CompoundTag armorTag(final Tag protection) {
        final CompoundTag armor = new CompoundTag();
        armor.put("protection", protection);
        return armor;
    }

}
