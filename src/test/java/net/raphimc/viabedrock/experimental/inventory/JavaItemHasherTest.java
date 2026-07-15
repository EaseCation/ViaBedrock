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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.Enchantments;
import com.viaversion.viaversion.data.item.ItemHasherBase;
import com.viaversion.viaversion.util.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaItemHasherTest {

    private static final int ENCHANTMENTS_DATA_ID = 37;
    private static final CodecContext.RegistryAccess FAILING_DYNAMIC_REGISTRY_DELEGATE = new CodecContext.RegistryAccess() {
        @Override
        public Key item(final int id) {
            return key("item", id);
        }

        @Override
        public Key attributeModifier(final int id) {
            return key("attribute_modifier", id);
        }

        @Override
        public Key dataComponentType(final int id) {
            return key("data_component_type", id);
        }

        @Override
        public Key entity(final int id) {
            return key("entity", id);
        }

        @Override
        public Key blockEntity(final int id) {
            return key("block_entity", id);
        }

        @Override
        public Key sound(final int id) {
            return key("sound", id);
        }

        @Override
        public Key key(final MappingData.MappingType mappingType, final int id) {
            return key(mappingType.name().toLowerCase(), id);
        }

        @Override
        public int id(final MappingData.MappingType mappingType, final String identifier) {
            return 0;
        }

        @Override
        public Key registryKey(final String registry, final int id) {
            throw new NullPointerException("No registry mappings for registry: " + registry);
        }

        @Override
        public CodecContext.RegistryAccess withMapped(final boolean mapped) {
            return this;
        }

        private Key key(final String type, final int id) {
            return Key.of("test", type + "/" + id);
        }
    };

    @Test
    void hashesEnchantedEquipmentUsingTheSentJavaRegistry() {
        final JavaItemHasher protectionHasher = new JavaItemHasher(
                null, FAILING_DYNAMIC_REGISTRY_DELEGATE, registries("minecraft:protection", "minecraft:sharpness"));
        final JavaItemHasher sharpnessHasher = new JavaItemHasher(
                null, FAILING_DYNAMIC_REGISTRY_DELEGATE, registries("minecraft:sharpness", "minecraft:protection"));

        final HashedItem protection = protectionHasher.toHashedItem(enchantedItem(0));
        final HashedItem sharpness = sharpnessHasher.toHashedItem(enchantedItem(0));

        assertTrue(protection.dataHashesById().containsKey(ENCHANTMENTS_DATA_ID));
        assertNotEquals(ItemHasherBase.UNKNOWN_HASH, protection.dataHashesById().get(ENCHANTMENTS_DATA_ID));
        assertNotEquals(protection.dataHashesById().get(ENCHANTMENTS_DATA_ID),
                sharpness.dataHashesById().get(ENCHANTMENTS_DATA_ID));
    }

    @Test
    void missingRegistryEntryProducesStableUnknownKeyInsteadOfDisconnecting() {
        final JavaItemHasher hasher = new JavaItemHasher(
                null, FAILING_DYNAMIC_REGISTRY_DELEGATE, new CompoundTag());

        assertDoesNotThrow(() -> hasher.toHashedItem(enchantedItem(42)));
    }

    private static Item enchantedItem(final int enchantmentId) {
        final Enchantments enchantments = new Enchantments(true);
        enchantments.add(enchantmentId, 4);
        final StructuredDataContainer data = new StructuredDataContainer(new StructuredData<?>[]{
                StructuredData.of(StructuredDataKey.ENCHANTMENTS1_21_5, enchantments, ENCHANTMENTS_DATA_ID)
        });
        return new StructuredItem(1, 1, data);
    }

    private static CompoundTag registries(final String... enchantmentKeys) {
        final CompoundTag enchantments = new CompoundTag();
        for (final String enchantmentKey : enchantmentKeys) {
            enchantments.put(enchantmentKey, new CompoundTag());
        }

        final CompoundTag registries = new CompoundTag();
        registries.put("minecraft:enchantment", enchantments);
        return registries;
    }
}
