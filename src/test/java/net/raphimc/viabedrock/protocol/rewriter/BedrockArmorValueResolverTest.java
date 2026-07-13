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
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockArmorValueResolverTest {

    private static final Map<Integer, String> IDENTIFIERS = Map.of(
            1, "minecraft:leather_helmet",
            2, "minecraft:iron_chestplate",
            3, "minecraft:diamond_leggings",
            4, "minecraft:diamond_boots",
            5, "test:custom_armor",
            6, "test:unknown"
    );
    private static final Map<String, Integer> PROTECTION = Map.of(
            "minecraft:leather_helmet", 1,
            "minecraft:iron_chestplate", 6,
            "minecraft:diamond_leggings", 6,
            "minecraft:diamond_boots", 3,
            "test:custom_armor", 15
    );

    private final BedrockArmorValueResolver resolver = new BedrockArmorValueResolver(
            item -> item == null || item.isEmpty() ? null : IDENTIFIERS.get(item.identifier()),
            identifier -> identifier != null ? PROTECTION.getOrDefault(identifier, 0) : 0
    );

    @Test
    void resolvesNakedSingleMixedAndCustomArmor() {
        assertEquals(0, this.resolver.resolve(BedrockItem.emptyArray(4)));
        assertEquals(1, this.resolver.resolve(items(1, 0, 0, 0)));
        assertEquals(16, this.resolver.resolve(items(1, 2, 3, 4)));
        assertEquals(15, this.resolver.resolve(items(5, 0, 0, 0)));
        assertEquals(0, this.resolver.resolve(items(6, 0, 0, 0)));
    }

    @Test
    void clampsTotalsToVanillaHudMaximum() {
        assertEquals(20, this.resolver.resolve(items(5, 5, 5, 5)));
    }

    @Test
    void ignoresDurabilityEnchantmentsAndOtherItemNbt() {
        final CompoundTag itemData = new CompoundTag();
        itemData.putInt("Damage", 999);
        itemData.putString("customColor", "irrelevant");
        final BedrockItem item = new BedrockItem(1, (short) 42, (byte) 1, itemData);

        assertEquals(1, this.resolver.resolve(new BedrockItem[]{item, BedrockItem.empty(), BedrockItem.empty(), BedrockItem.empty()}));
    }

    private static BedrockItem[] items(final int... ids) {
        final BedrockItem[] items = new BedrockItem[ids.length];
        for (int i = 0; i < ids.length; i++) {
            items[i] = ids[i] == 0 ? BedrockItem.empty() : new BedrockItem(ids[i]);
        }
        return items;
    }

}
