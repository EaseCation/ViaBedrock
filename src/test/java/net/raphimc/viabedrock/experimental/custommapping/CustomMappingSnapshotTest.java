/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.custommapping;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomMappingSnapshotTest {

    private static final int ITEM_SOURCE_BASE = 10_000;

    @Test
    void decodesSchemaFiveCustomItemStackSize() {
        final CustomMappingSnapshot snapshot = decode(itemSnapshot(5, 64));

        assertEquals(5, snapshot.schemaVersion());
        assertEquals(1, snapshot.items().size());
        assertEquals("easecation:hxgz_planks", snapshot.items().getFirst().bedrockIdentifier());
        assertEquals(12_345, snapshot.items().getFirst().targetJavaRawId());
        assertEquals(64, snapshot.items().getFirst().maxStackSize());
    }

    @Test
    void keepsSchemaFourCustomItemLimitUnknown() {
        final CustomMappingSnapshot snapshot = decode(itemSnapshot(4, 0));

        assertEquals(0, snapshot.items().getFirst().maxStackSize());
    }

    @Test
    void rejectsInvalidSchemaFiveCustomItemStackSizes() {
        assertThrows(IllegalArgumentException.class, () -> decode(itemSnapshot(5, 0)));
        assertThrows(IllegalArgumentException.class, () -> decode(itemSnapshot(5, 100)));
    }

    @Test
    void cachesAndProjectsSupportedCustomItemStackSizes() {
        for (int maxStackSize : List.of(1, 16, 64, 99)) {
            final byte[] body = itemSnapshot(5, maxStackSize);
            final long cacheKey = CustomMappingProfileCache.cacheKey(body);
            final SnapshotProfile profile = CustomMappingProfileCache.getInstance().getOrBuild(
                    body,
                    () -> SnapshotProfile.fromSnapshot(decode(body), cacheKey, ITEM_SOURCE_BASE)
            );

            assertEquals(maxStackSize, profile.item("easecation:hxgz_planks").maxStackSize());
            assertEquals(cacheKey, profile.cacheKey());

            final RuntimeProjection projection = new RuntimeProjection(List.of(), List.of(), profile.items());
            final CustomMappingAccess sourceAccess = projection.toAccess(false, false, false);
            final CustomMappingAccess targetAccess = projection.toAccess(false, false, true);
            assertEquals(maxStackSize, sourceAccess.customItem("easecation:hxgz_planks").maxStackSize());
            assertEquals(maxStackSize, targetAccess.customItem("easecation:hxgz_planks").maxStackSize());
            assertEquals(profile.item("easecation:hxgz_planks").sourceJavaRawId(), sourceAccess.customItemSourceId("easecation:hxgz_planks"));
            assertEquals(12_345, targetAccess.customItemSourceId("easecation:hxgz_planks"));
        }
    }

    @Test
    void differentStackMetadataUsesDifferentCachedProfile() {
        final byte[] stack16 = itemSnapshot(5, 16);
        final byte[] stack64 = itemSnapshot(5, 64);
        final SnapshotProfile profile16 = CustomMappingProfileCache.getInstance().getOrBuild(
                stack16,
                () -> SnapshotProfile.fromSnapshot(decode(stack16), CustomMappingProfileCache.cacheKey(stack16), ITEM_SOURCE_BASE)
        );
        final SnapshotProfile profile64 = CustomMappingProfileCache.getInstance().getOrBuild(
                stack64,
                () -> SnapshotProfile.fromSnapshot(decode(stack64), CustomMappingProfileCache.cacheKey(stack64), ITEM_SOURCE_BASE)
        );

        assertNotSame(profile16, profile64);
        assertEquals(16, profile16.item("easecation:hxgz_planks").maxStackSize());
        assertEquals(64, profile64.item("easecation:hxgz_planks").maxStackSize());
    }

    @Test
    void schemaFourUnknownLimitIsNotProjectedAsKnownMetadata() {
        final SnapshotProfile profile = SnapshotProfile.fromSnapshot(
                decode(itemSnapshot(4, 0)),
                CustomMappingProfileCache.UNKNOWN_KEY,
                ITEM_SOURCE_BASE
        );
        final RuntimeProjection projection = new RuntimeProjection(List.of(), List.of(), profile.items());

        assertEquals(0, projection.toAccess().customItem("easecation:hxgz_planks").maxStackSize());
        assertNull(projection.toAccess().customItem("easecation:missing"));
    }

    private static CustomMappingSnapshot decode(final byte[] body) {
        return CustomMappingSnapshot.decode(body, 1_048_576, 16, 16, 16, 32_767);
    }

    private static byte[] itemSnapshot(final int schemaVersion, final int maxStackSize) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, schemaVersion);
        writeVarInt(out, 1);
        writeVarInt(out, 0);
        writeVarInt(out, 1);
        writeString(out, "easecation:hxgz_planks");
        writeVarInt(out, 0);
        writeVarInt(out, 0);
        writeVarInt(out, 1);
        writeVarInt(out, 0);
        writeVarInt(out, 12_345);
        if (schemaVersion >= 5) {
            writeVarInt(out, maxStackSize);
        }
        return out.toByteArray();
    }

    private static void writeString(final ByteArrayOutputStream out, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarInt(final ByteArrayOutputStream out, int value) {
        do {
            int part = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                part |= 0x80;
            }
            out.write(part);
        } while (value != 0);
    }

}
