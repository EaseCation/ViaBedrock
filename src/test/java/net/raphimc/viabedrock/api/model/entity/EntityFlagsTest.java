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
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityFlagsTest {

    @Test
    void combinesFlagWordsWithoutSignExtension() {
        final long flags = Long.MIN_VALUE;
        final long flags2 = (1L << 24) | Long.MIN_VALUE;

        final BigInteger expected = BigInteger.ZERO
                .setBit(63)
                .setBit(88)
                .setBit(127);
        assertEquals(expected, Entity.combineEntityFlags(flags, flags2));
    }

    @Test
    void combinesEmptyAndPositiveFlagWords() {
        assertEquals(BigInteger.ZERO, Entity.combineEntityFlags(0L, 0L));
        assertEquals(BigInteger.ZERO.setBit(25).setBit(42), Entity.combineEntityFlags((1L << 25) | (1L << 42), 0L));
        assertEquals(BigInteger.ZERO.setBit(88), Entity.combineEntityFlags(0L, 1L << 24));
    }

    @Test
    void readsBothFlagWordsWithoutShiftWraparound() {
        final Entity entity = new Entity(null, 1L, 2L, "minecraft:pig", 3, UUID.randomUUID(), EntityTypes1_21_11.PIG);
        entity.entityData().put(ActorDataIDs.RESERVED_0, new EntityData(
                ActorDataIDs.RESERVED_0.getValue(), EntityDataTypesBedrock.LONG, (1L << ActorFlags.ONFIRE.getValue()) | Long.MIN_VALUE));
        entity.entityData().put(ActorDataIDs.RESERVED_092, new EntityData(
                ActorDataIDs.RESERVED_092.getValue(), EntityDataTypesBedrock.LONG, 1L | Long.MIN_VALUE));

        assertTrue(entity.hasEntityFlag(ActorFlags.ONFIRE));
        assertTrue(entity.hasEntityFlag(ActorFlags.EATING));
        assertTrue(entity.hasEntityFlag(ActorFlags.LAYING_DOWN));
        assertTrue(entity.hasEntityFlag(ActorFlags.USES_LEGACY_FRICTION));
        assertFalse(entity.hasEntityFlag(ActorFlags.NOAI));
        assertFalse(entity.hasEntityFlag(ActorFlags.USES_UNIFORM_AIR_DRAG));
        assertFalse(entity.hasEntityFlag(ActorFlags.NAMEPLATE_DEPTH_TESTED));
        assertFalse(entity.hasEntityFlag(null));
        assertEquals(EnumSet.of(
                ActorFlags.ONFIRE,
                ActorFlags.EATING,
                ActorFlags.LAYING_DOWN,
                ActorFlags.USES_LEGACY_FRICTION
        ), entity.entityFlags());
    }

    @Test
    void twoWordDecoderMatchesLegacyBigIntegerForEverySupportedBit() {
        final Entity entity = new Entity(null, 1L, 2L, "minecraft:pig", 3, UUID.randomUUID(), EntityTypes1_21_11.PIG);
        for (int bit = 0; bit < 128; bit++) {
            final long low = bit < 64 ? 1L << bit : 0L;
            final long high = bit >= 64 ? 1L << (bit - 64) : 0L;
            entity.entityData().put(ActorDataIDs.RESERVED_0, new EntityData(
                    ActorDataIDs.RESERVED_0.getValue(), EntityDataTypesBedrock.LONG, low));
            entity.entityData().put(ActorDataIDs.RESERVED_092, new EntityData(
                    ActorDataIDs.RESERVED_092.getValue(), EntityDataTypesBedrock.LONG, high));

            assertEquals(
                    EnumUtil.getEnumSetFromBitmask(
                            ActorFlags.class, Entity.combineEntityFlags(low, high), ActorFlags::getValue),
                    entity.entityFlags(), "bit " + bit);
        }
    }

    @Test
    void metadataBatchDecodesBothFlagWordsOnce() {
        final CountingEntity entity = new CountingEntity();
        final EntityData low = new EntityData(
                ActorDataIDs.RESERVED_0.getValue(), EntityDataTypesBedrock.LONG,
                1L << ActorFlags.ONFIRE.getValue());
        final EntityData high = new EntityData(
                ActorDataIDs.RESERVED_092.getValue(), EntityDataTypesBedrock.LONG, 1L);

        entity.entityData().put(ActorDataIDs.RESERVED_0, low);
        entity.entityData().put(ActorDataIDs.RESERVED_092, high);
        entity.translateEntityDataBatch(List.of(
                Map.entry(ActorDataIDs.RESERVED_0, low),
                Map.entry(ActorDataIDs.RESERVED_092, high)), new ArrayList<>());

        assertEquals(1, entity.flagTranslations);
        assertEquals(1, entity.bulkDecodes);
        assertEquals(EnumSet.of(ActorFlags.ONFIRE, ActorFlags.LAYING_DOWN), entity.translatedFlags);
    }

    private static final class CountingEntity extends Entity {
        private int flagTranslations;
        private int bulkDecodes;
        private Set<ActorFlags> translatedFlags;

        private CountingEntity() {
            super(null, 1L, 2L, "minecraft:pig", 3, UUID.randomUUID(), EntityTypes1_21_11.PIG);
        }

        @Override
        public Set<ActorFlags> entityFlags() {
            this.bulkDecodes++;
            return super.entityFlags();
        }

        @Override
        protected boolean translateEntityData(final ActorDataIDs id, final EntityData entityData,
                                              final List<EntityData> javaEntityData) {
            if (id == ActorDataIDs.RESERVED_0 || id == ActorDataIDs.RESERVED_092) {
                this.flagTranslations++;
                this.translatedFlags = this.entityFlags();
            }
            return true;
        }
    }
}
