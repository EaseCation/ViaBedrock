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
import net.raphimc.viabedrock.protocol.model.EntityEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivingEntityTickTest {

    @Test
    void keepsActiveAndInfiniteEffectsWithoutRequiringRemovalState() {
        final LivingEntity entity = new LivingEntity(null, 1L, 2L, "minecraft:zombie", 3, UUID.randomUUID(), EntityTypes1_21_11.ZOMBIE);
        final EntityEffect active = new EntityEffect("minecraft:speed", 0, 2, true, false);
        final EntityEffect infinite = new EntityEffect("minecraft:strength", 0, -1, true, false);
        entity.effects().put(active.identifier(), active);
        entity.effects().put(infinite.identifier(), infinite);

        entity.tick();

        assertEquals(1, entity.age());
        assertEquals(1, active.duration().get());
        assertEquals(-1, infinite.duration().get());
        assertEquals(2, entity.effects().size());
        assertTrue(entity.effects().containsKey(active.identifier()));
        assertTrue(entity.effects().containsKey(infinite.identifier()));
    }

    @Test
    void expiresDurationOneAndZeroAfterIterationAndDeduplicatesIdentifiers() {
        final TestLivingEntity entity = new TestLivingEntity();
        final EntityEffect active = new EntityEffect("minecraft:speed", 0, 2, true, false);
        final EntityEffect durationOne = new EntityEffect("minecraft:poison", 0, 1, true, false);
        final EntityEffect durationZero = new EntityEffect("minecraft:wither", 0, 0, true, false);
        final EntityEffect duplicateOne = new EntityEffect("test:duplicate", 0, 1, true, false);
        final EntityEffect duplicateTwo = new EntityEffect("test:duplicate", 0, 0, true, false);
        entity.effects().put(active.identifier(), active);
        entity.effects().put(durationOne.identifier(), durationOne);
        entity.effects().put(durationZero.identifier(), durationZero);
        entity.effects().put("duplicate-one", duplicateOne);
        entity.effects().put("duplicate-two", duplicateTwo);

        entity.tick();

        assertEquals(1, active.duration().get());
        assertEquals(List.of("minecraft:poison", "minecraft:wither", "test:duplicate").stream().sorted().toList(),
                entity.removed.stream().sorted().toList());
        assertEquals(1L, entity.removed.stream().filter("test:duplicate"::equals).count());
    }

    private static final class TestLivingEntity extends LivingEntity {
        private final List<String> removed = new ArrayList<>();

        private TestLivingEntity() {
            super(null, 1L, 2L, "minecraft:zombie", 3, UUID.randomUUID(), EntityTypes1_21_11.ZOMBIE);
        }

        @Override
        protected void removeExpiredEffect(final String identifier) {
            this.removed.add(identifier);
            this.effects().remove(identifier);
        }
    }
}
