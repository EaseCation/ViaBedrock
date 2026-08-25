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
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import net.raphimc.viabedrock.protocol.model.EntityPropertyDefinition;
import net.raphimc.viabedrock.protocol.model.EntityPropertyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses and resolves the per-entity MOT synced-property registry. */
public final class EntityPropertyRegistry {

    private final Map<String, List<EntityPropertyDefinition>> definitions = new LinkedHashMap<>();

    public void register(final CompoundTag root) {
        if (root == null) {
            return;
        }
        final String entityIdentifier = root.getString("type");
        final ListTag<CompoundTag> properties = root.getListTag("properties", CompoundTag.class);
        if (entityIdentifier == null || entityIdentifier.isEmpty() || properties == null) {
            return;
        }

        final List<EntityPropertyDefinition> parsed = new ArrayList<>(properties.size());
        for (final CompoundTag property : properties) {
            final String name = property.getString("name");
            if (name == null) {
                continue;
            }
            final List<String> enumValues = new ArrayList<>();
            final ListTag<StringTag> enumTag = property.getListTag("enum", StringTag.class);
            if (enumTag != null) {
                for (final StringTag value : enumTag) {
                    enumValues.add(value.getValue());
                }
            }
            parsed.add(new EntityPropertyDefinition(name, property.getInt("type", -1), enumValues));
        }
        this.definitions.put(entityIdentifier, List.copyOf(parsed));
    }

    public List<EntityPropertyDefinition> definitions(final String entityIdentifier) {
        return this.definitions.getOrDefault(entityIdentifier, List.of());
    }

    public Map<String, List<EntityPropertyDefinition>> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.definitions));
    }

    /**
     * Resolves the two positional MOT arrays without merging with an older snapshot.
     * Integer-like definitions occupy the integer array; float definitions occupy the
     * float array. The raw maps remain on EntityProperties for lossless inspection.
     */
    public EntityProperties resolve(final String entityIdentifier, final EntityProperties raw) {
        final List<EntityPropertyDefinition> registered = this.definitions(entityIdentifier);
        if (registered.isEmpty()) {
            return raw.withNamedProperties(entityIdentifier, List.of());
        }

        final Int2IntMap integers = raw.intProperties();
        final Int2ObjectMap<Float> floats = raw.floatProperties();
        int integerIndex = 0;
        int floatIndex = 0;
        final List<EntityPropertyValue> values = new ArrayList<>();
        for (final EntityPropertyDefinition definition : registered) {
            if (definition.isFloat()) {
                if (floats.containsKey(floatIndex)) {
                    final Float value = floats.get(floatIndex);
                    values.add(new EntityPropertyValue(definition.name(), definition.type(), value, null, value));
                }
                floatIndex++;
                continue;
            }

            if (!integers.containsKey(integerIndex)) {
                integerIndex++;
                continue;
            }
            final int rawValue = integers.get(integerIndex++);
            final Object value = switch (definition.type()) {
                case EntityPropertyDefinition.BOOLEAN -> rawValue != 0;
                case EntityPropertyDefinition.ENUM -> {
                    final String enumValue = definition.enumValue(rawValue);
                    yield enumValue != null ? enumValue : rawValue;
                }
                default -> rawValue;
            };
            values.add(new EntityPropertyValue(definition.name(), definition.type(), value, rawValue, null));
        }
        return raw.withNamedProperties(entityIdentifier, values);
    }
}
