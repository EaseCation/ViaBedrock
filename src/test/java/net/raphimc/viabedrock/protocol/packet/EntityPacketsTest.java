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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorEvent;
import net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.Test;

import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

class EntityPacketsTest {

    private static final long OWNER_UNIQUE_ID = 1234L;

    @Test
    void resolvesRemoteFishingHookOwnerJavaId() {
        final Integer ownerJavaId = EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(OWNER_UNIQUE_ID)}, uniqueId -> {
            assertEquals(OWNER_UNIQUE_ID, uniqueId);
            return 42;
        });

        assertEquals(Integer.valueOf(42), ownerJavaId);
    }

    @Test
    void preservesLocalPlayerJavaIdZero() {
        final Integer ownerJavaId = EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(OWNER_UNIQUE_ID)}, uniqueId -> 0);

        assertEquals(Integer.valueOf(0), ownerJavaId);
    }

    @Test
    void findsOwnerRegardlessOfMetadataOrder() {
        final EntityData variantData = new EntityData(ActorDataIDs.VARIANT.getValue(), EntityDataTypesBedrock.INT, 7);
        final Integer ownerJavaId = EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{variantData, ownerData(OWNER_UNIQUE_ID)}, uniqueId -> 8);

        assertEquals(Integer.valueOf(8), ownerJavaId);
    }

    @Test
    void rejectsMissingOrInvalidFishingHookOwner() {
        final LongFunction<Integer> unexpectedLookup = uniqueId -> fail("Owner lookup should not run for invalid metadata");
        final EntityData wrongType = new EntityData(ActorDataIDs.OWNER.getValue(), EntityDataTypesBedrock.INT, (int) OWNER_UNIQUE_ID);

        assertAll(
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[0], unexpectedLookup)),
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(-1L)}, unexpectedLookup)),
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{wrongType}, unexpectedLookup)),
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(OWNER_UNIQUE_ID)}, uniqueId -> null))
        );
    }

    @Test
    void mapsFallingBlockRuntimeIdToJavaBlockState() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(7)}, bedrockRuntimeId -> {
            assertEquals(7, bedrockRuntimeId);
            return 42;
        });

        assertEquals(Integer.valueOf(42), javaBlockStateId);
    }

    @Test
    void mapsNegativeHashedFallingBlockRuntimeId() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(-7)}, bedrockRuntimeId -> {
            assertEquals(-7, bedrockRuntimeId);
            return 42;
        });

        assertEquals(Integer.valueOf(42), javaBlockStateId);
    }

    @Test
    void findsFallingBlockStateRegardlessOfMetadataOrder() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{ownerData(OWNER_UNIQUE_ID), variantData(7)}, bedrockRuntimeId -> 8);

        assertEquals(Integer.valueOf(8), javaBlockStateId);
    }

    @Test
    void preservesMappedJavaBlockStateZero() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(7)}, bedrockRuntimeId -> 0);

        assertEquals(Integer.valueOf(0), javaBlockStateId);
    }

    @Test
    void rejectsMissingInvalidOrUnmappedFallingBlockState() {
        final EntityData wrongType = new EntityData(ActorDataIDs.VARIANT.getValue(), EntityDataTypesBedrock.LONG, 7L);

        assertAll(
                () -> assertNull(EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[0], bedrockRuntimeId -> fail("Block state lookup should not run without variant metadata"))),
                () -> assertNull(EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{wrongType}, bedrockRuntimeId -> fail("Block state lookup should not run for invalid metadata"))),
                () -> assertNull(EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(7)}, bedrockRuntimeId -> -1))
        );
    }

    @Test
    void mapsContextSensitiveActorEvents() {
        assertAll(
                () -> assertEquals(EntityEvent.START_ATTACKING, EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.EVOKER_FANGS)),
                () -> assertEquals(EntityEvent.START_ATTACKING, EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.IRON_GOLEM)),
                () -> assertEquals(EntityEvent.START_RAM, EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.GOAT)),
                () -> assertEquals(EntityEvent.END_RAM, EntityPackets.javaEntityEvent(ActorEvent.STOP_ATTACKING, EntityTypes1_21_11.GOAT)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.VINDICATOR)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.STOP_ATTACKING, EntityTypes1_21_11.VINDICATOR))
        );
    }

    @Test
    void mapsStableVanillaActorEvents() {
        assertAll(
                () -> assertEquals(EntityEvent.TAMING_SUCCEEDED, EntityPackets.javaEntityEvent(ActorEvent.TAMING_SUCCEEDED, EntityTypes1_21_11.WOLF)),
                () -> assertEquals(EntityEvent.JUMP, EntityPackets.javaEntityEvent(ActorEvent.JUMP, EntityTypes1_21_11.RABBIT)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.JUMP, EntityTypes1_21_11.ZOMBIE)),
                () -> assertEquals(EntityEvent.EAT_GRASS, EntityPackets.javaEntityEvent(ActorEvent.EAT_GRASS, EntityTypes1_21_11.SHEEP)),
                () -> assertEquals(EntityEvent.VILLAGER_ANGRY, EntityPackets.javaEntityEvent(ActorEvent.VILLAGER_ANGRY, EntityTypes1_21_11.VILLAGER)),
                () -> assertEquals(EntityEvent.CANCEL_SHAKE_WETNESS, EntityPackets.javaEntityEvent(ActorEvent.SHAKE_WETNESS_STOP, EntityTypes1_21_11.WOLF)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.FINISHED_CHARGING_ITEM, EntityTypes1_21_11.PILLAGER))
        );
    }

    private static EntityData ownerData(final long ownerUniqueId) {
        return new EntityData(ActorDataIDs.OWNER.getValue(), EntityDataTypesBedrock.LONG, ownerUniqueId);
    }

    private static EntityData variantData(final int bedrockRuntimeId) {
        return new EntityData(ActorDataIDs.VARIANT.getValue(), EntityDataTypesBedrock.INT, bedrockRuntimeId);
    }

}
