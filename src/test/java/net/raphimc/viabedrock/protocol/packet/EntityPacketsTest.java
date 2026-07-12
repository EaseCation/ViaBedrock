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

import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
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

    private static EntityData ownerData(final long ownerUniqueId) {
        return new EntityData(ActorDataIDs.OWNER.getValue(), EntityDataTypesBedrock.LONG, ownerUniqueId);
    }

}
