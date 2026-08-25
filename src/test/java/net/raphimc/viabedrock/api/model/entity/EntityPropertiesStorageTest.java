/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.model.entity;

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityPropertiesStorageTest {

    @Test
    void replacesFullMotPropertySnapshotAndRetainsFrame() {
        final Entity entity = new Entity(null, 1L, 2L, "minecraft:pig", 3, UUID.randomUUID(), EntityTypes1_21_11.PIG);
        final Int2IntOpenHashMap ints = new Int2IntOpenHashMap();
        ints.put(0, 7);
        final Int2ObjectOpenHashMap<Float> floats = new Int2ObjectOpenHashMap<>();
        floats.put(0, Float.valueOf(1.5F));
        final EntityProperties properties = new EntityProperties(ints, floats);

        entity.setEntityProperties(properties);
        entity.setEntityDataFrame(4_294_967_295L);

        assertEquals(properties, entity.entityProperties());
        assertEquals(4_294_967_295L, entity.entityDataFrame());
    }
}
