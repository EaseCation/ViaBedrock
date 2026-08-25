/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import net.raphimc.viabedrock.protocol.model.EntityPropertyDefinition;
import net.raphimc.viabedrock.protocol.model.EntityPropertyValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EntityPropertyTranslationTest {

    @Test
    void translatesClimateNamesThroughJavaRegistryOrder() {
        final CompoundTag registries = new CompoundTag();
        final CompoundTag variants = new CompoundTag();
        variants.put("minecraft:warm", new CompoundTag());
        variants.put("minecraft:temperate", new CompoundTag());
        variants.put("minecraft:cold", new CompoundTag());
        registries.put("minecraft:cow_variant", variants);

        assertEquals("minecraft:temperate",
                EntityMetadataRewriter.climateVariantName(enumValue("temperate", 0)));
        assertEquals(1, EntityMetadataRewriter.javaRegistryIndex(
                registries, "minecraft:cow_variant", "minecraft:temperate"));
        assertEquals(0, EntityMetadataRewriter.javaRegistryIndex(
                registries, "minecraft:cow_variant", "warm"));
        assertNull(EntityMetadataRewriter.javaRegistryIndex(
                registries, "minecraft:cow_variant", "unknown"));
        assertNull(EntityMetadataRewriter.climateVariantName(enumValue("unknown", 3)));
    }

    @Test
    void translatesSoundNamesToJavaRegistryIdentifiers() {
        assertEquals("minecraft:classic",
                EntityMetadataRewriter.catSoundVariantName(enumValue("default", 0)));
        assertEquals("minecraft:royal",
                EntityMetadataRewriter.catSoundVariantName(enumValue("royal", 1)));
        assertEquals("minecraft:classic",
                EntityMetadataRewriter.wolfSoundVariantName(enumValue("default", 0)));
        assertEquals("minecraft:angry",
                EntityMetadataRewriter.wolfSoundVariantName(enumValue("mad", 4)));
        assertNull(EntityMetadataRewriter.wolfSoundVariantName(enumValue("future", 99)));
    }

    @Test
    void mapsStatePropertiesOnlyToProvenJavaStateValues() {
        assertEquals(0, EntityMetadataRewriter.javaArmadilloState(enumValue("unrolled", 0)));
        assertEquals(2, EntityMetadataRewriter.javaArmadilloState(enumValue("rolled_up_peeking", 2)));
        assertEquals(3, EntityMetadataRewriter.javaArmadilloState(enumValue("rolled_up_unrolling", 4)));
        assertEquals(3, EntityMetadataRewriter.javaWeatheringCopperState(enumValue("oxidized", 3)));
        assertEquals(4, EntityMetadataRewriter.javaCopperGolemState(enumValue("put_fail", 4)));
        assertNull(EntityMetadataRewriter.javaCopperGolemState(enumValue("future", 99)));
        assertEquals("minecraft:temperate",
                EntityMetadataRewriter.zombieNautilusVariantName(enumValue("default", 0)));
        assertEquals("minecraft:warm",
                EntityMetadataRewriter.zombieNautilusVariantName(enumValue("coral", 1)));
    }

    @Test
    void translatesCreakingStateWithoutInventingSwayingMetadata() {
        assertEquals(new EntityMetadataRewriter.CreakingStateFlags(false, false),
                EntityMetadataRewriter.creakingState("neutral"));
        assertEquals(new EntityMetadataRewriter.CreakingStateFlags(true, false),
                EntityMetadataRewriter.creakingState("hostile_observed"));
        assertEquals(new EntityMetadataRewriter.CreakingStateFlags(true, true),
                EntityMetadataRewriter.creakingState("crumbling"));
        assertNull(EntityMetadataRewriter.creakingState("future_state"));
    }

    @Test
    void combinesBeeNectarWithExistingAngerFlag() {
        assertEquals(0x0A, Byte.toUnsignedInt(EntityMetadataRewriter.beeFlags(true, true)));
        assertEquals(0x08, Byte.toUnsignedInt(EntityMetadataRewriter.beeFlags(false, true)));
        assertEquals(0x02, Byte.toUnsignedInt(EntityMetadataRewriter.beeFlags(true, false)));
    }

    private static EntityPropertyValue enumValue(final String value, final int index) {
        return new EntityPropertyValue("test", EntityPropertyDefinition.ENUM, value, index, null);
    }
}
