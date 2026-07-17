/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityMetadataRewriterTest {

    @Test
    void mapsSkeletonRangedAttackAndVindicatorAngerToAggressiveMobFlag() {
        assertEquals(0x04, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.SKELETON, EnumSet.of(ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)));
        assertEquals(0x04, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.STRAY, EnumSet.of(ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)));
        assertEquals(0x04, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.VINDICATOR, EnumSet.of(ActorFlags.ANGRY)));
        assertEquals(0, EntityMetadataRewriter.mobFlags(EntityTypes1_21_11.SKELETON, EnumSet.noneOf(ActorFlags.class)));
        assertEquals(0, EntityMetadataRewriter.mobFlags(EntityTypes1_21_11.VINDICATOR, EnumSet.noneOf(ActorFlags.class)));
    }

    @Test
    void preservesNoAiAlongsideAggressiveMobFlag() {
        assertEquals(0x05, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.BOGGED, EnumSet.of(ActorFlags.NOAI, ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)));
        assertEquals(0x01, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.ZOMBIE, EnumSet.of(ActorFlags.NOAI, ActorFlags.ANGRY)));
    }

    @Test
    void resolvesBedrockSpellColorsWithOrWithoutAlpha() {
        assertEquals(1, EntityMetadataRewriter.spellType(true, 0xB3B3CC));
        assertEquals(2, EntityMetadataRewriter.spellType(true, 0xFF664D59));
        assertEquals(3, EntityMetadataRewriter.spellType(true, 0xB38033));
        assertEquals(2, EntityMetadataRewriter.spellType(true, null));
        assertEquals(0, EntityMetadataRewriter.spellType(false, 0xFF664D59));
    }

    @Test
    void mapsLivingAndTamableFlags() {
        assertEquals(0x05, EntityMetadataRewriter.livingFlags(EnumSet.of(
                ActorFlags.USINGITEM, ActorFlags.EMERGING, ActorFlags.DAMAGENEARBYMOBS)));
        assertEquals(0x01, EntityMetadataRewriter.livingFlags(EnumSet.of(ActorFlags.BLOCKING)));
        assertEquals(0, EntityMetadataRewriter.livingFlags(EnumSet.of(ActorFlags.EMERGING)));
        assertEquals(0x07, EntityMetadataRewriter.tamableFlags(EnumSet.of(
                ActorFlags.SITTING, ActorFlags.ANGRY, ActorFlags.TAMED)));
    }

    @Test
    void combinesCompositeAnimalFlags() {
        assertEquals(0x2D, EntityMetadataRewriter.foxFlags(EnumSet.of(
                ActorFlags.SITTING, ActorFlags.SNEAKING, ActorFlags.INTERESTED, ActorFlags.SLEEPING)));
        assertEquals(0x1E, EntityMetadataRewriter.pandaFlags(EnumSet.of(
                ActorFlags.SNEEZING, ActorFlags.ROLLING, ActorFlags.SITTING, ActorFlags.LAYING_DOWN)));
        assertEquals(0x1E, Byte.toUnsignedInt(EntityMetadataRewriter.sheepFlags(14, true)));
        assertEquals(0x03, Byte.toUnsignedInt(EntityMetadataRewriter.sheepFlags(3, false)));
    }
}
