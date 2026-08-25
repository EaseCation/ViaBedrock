/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import net.raphimc.viabedrock.protocol.model.EntityPropertyValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPropertyRegistryTest {

    @Test
    void resolvesIntegerAndFloatArraysInSeparateRegistrationOrder() {
        final EntityPropertyRegistry registry = new EntityPropertyRegistry();
        registry.register(registryTag("minecraft:test",
                property("minecraft:first_int", 0),
                property("minecraft:float", 1),
                property("minecraft:enum", 3, "zero", "one"),
                property("minecraft:boolean", 2)));

        final Int2IntOpenHashMap integers = new Int2IntOpenHashMap();
        integers.put(0, 11);
        integers.put(1, 1);
        integers.put(2, 1);
        final Int2ObjectOpenHashMap<Float> floats = new Int2ObjectOpenHashMap<>();
        floats.put(Integer.valueOf(0), Float.valueOf(2.5F));
        final EntityProperties resolved = registry.resolve("minecraft:test", new EntityProperties(integers, floats));

        assertEquals("minecraft:test", resolved.entityIdentifier());
        assertEquals(List.of(
                "minecraft:first_int", "minecraft:float", "minecraft:enum", "minecraft:boolean"),
                resolved.namedProperties().stream().map(EntityPropertyValue::name).toList());
        assertEquals(11, resolved.namedProperty("minecraft:first_int").value());
        assertEquals(2.5F, resolved.namedProperty("minecraft:float").value());
        assertEquals("one", resolved.namedProperty("minecraft:enum").value());
        assertEquals(true, resolved.namedProperty("minecraft:boolean").value());
    }

    @Test
    void replacesNamedValuesForEachFullSnapshotAndKeepsUnknownNamesInspectable() {
        final EntityPropertyRegistry registry = new EntityPropertyRegistry();
        registry.register(registryTag("minecraft:test",
                property("minecraft:known", 0),
                property("minecraft:unknown_property", 0),
                property("minecraft:float", 1)));

        final Int2IntOpenHashMap firstInts = new Int2IntOpenHashMap();
        firstInts.put(0, 4);
        firstInts.put(1, 8);
        final Int2ObjectOpenHashMap<Float> firstFloats = new Int2ObjectOpenHashMap<>();
        firstFloats.put(Integer.valueOf(0), Float.valueOf(3.0F));
        final EntityProperties first = registry.resolve("minecraft:test", new EntityProperties(firstInts, firstFloats));

        final Int2IntOpenHashMap secondInts = new Int2IntOpenHashMap();
        secondInts.put(0, 9);
        final EntityProperties second = registry.resolve("minecraft:test", new EntityProperties(secondInts, new Int2ObjectOpenHashMap<>()));

        assertEquals(4, first.namedProperty("minecraft:known").value());
        assertEquals(8, first.namedProperty("minecraft:unknown_property").value());
        assertEquals(3.0F, first.namedProperty("minecraft:float").value());
        assertEquals(9, second.namedProperty("minecraft:known").value());
        assertNull(second.namedProperty("minecraft:unknown_property"));
        assertNull(second.namedProperty("minecraft:float"));
        assertTrue(registry.definitions("minecraft:test").stream()
                .anyMatch(definition -> definition.name().equals("minecraft:unknown_property")));
    }

    private static CompoundTag registryTag(final String type, final CompoundTag... properties) {
        final CompoundTag root = new CompoundTag();
        root.put("type", new StringTag(type));
        final ListTag<CompoundTag> list = new ListTag<>(CompoundTag.class);
        for (final CompoundTag property : properties) {
            list.add(property);
        }
        root.put("properties", list);
        return root;
    }

    private static CompoundTag property(final String name, final int type, final String... enumValues) {
        final CompoundTag property = new CompoundTag();
        property.put("name", new StringTag(name));
        property.put("type", new IntTag(type));
        if (enumValues.length > 0) {
            final ListTag<StringTag> enums = new ListTag<>(StringTag.class);
            for (final String enumValue : enumValues) {
                enums.add(new StringTag(enumValue));
            }
            property.put("enum", enums);
        }
        return property;
    }
}
