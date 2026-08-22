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

import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.experimental.types.inventory.InstanceItemType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.item.BedrockItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeContentLayoutTest {

    private static final Type<BedrockItem> SLOT_ITEM_TYPE = new BedrockItemType(0, new Int2ObjectOpenHashMap<IntSortedSet>(), false);

    @Test
    void officialAndNeteaseKeepGroupsAfter776() {
        assertTrue(CreativeContentLayout.usesGroups(true, 860));
        assertTrue(CreativeContentLayout.usesGroups(false, 975));
        assertFalse(CreativeContentLayout.usesGroups(true, 775));
    }

    @Test
    void netease860RoundTripsOneBasedNetIdsWithInstanceItems() {
        final List<CreativeContentCache.Entry> entries = List.of(
                new CreativeContentCache.Entry(1, new BedrockItem(35, (short) 0, (byte) 64)),
                new CreativeContentCache.Entry(2, new BedrockItem(260, (short) 0, (byte) 1))
        );
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CreativeContentLayout.write(buffer, entries, InstanceItemType.INSTANCE, true, 860);
            final List<CreativeContentCache.Entry> decoded = CreativeContentLayout.read(buffer, InstanceItemType.INSTANCE, true, 860);
            assertEquals(2, decoded.size());
            assertEquals(1, decoded.get(0).netId());
            assertEquals(35, decoded.get(0).item().identifier());
            assertEquals(2, decoded.get(1).netId());
            assertEquals(260, decoded.get(1).item().identifier());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975StillWritesGroupsAndOneBasedNetIds() {
        final List<CreativeContentCache.Entry> entries = List.of(
                new CreativeContentCache.Entry(1, new BedrockItem(1, (short) 0, (byte) 1))
        );
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CreativeContentLayout.write(buffer, entries, InstanceItemType.INSTANCE, false, 975);
            final List<CreativeContentCache.Entry> decoded = CreativeContentLayout.read(buffer, InstanceItemType.INSTANCE, false, 975);
            assertEquals(1, decoded.size());
            assertEquals(1, decoded.get(0).netId());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void slotItemTypeDesynchronizesInstanceCreativeEntries() {
        final List<CreativeContentCache.Entry> entries = List.of(
                new CreativeContentCache.Entry(1, new BedrockItem(35, (short) 0, (byte) 64)),
                new CreativeContentCache.Entry(2, new BedrockItem(260, (short) 0, (byte) 1))
        );
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CreativeContentLayout.write(buffer, entries, InstanceItemType.INSTANCE, true, 860);
            final ByteBuf copy = buffer.copy();
            try {
                boolean leftoverOrWrong = false;
                try {
                    final List<CreativeContentCache.Entry> decoded = CreativeContentLayout.read(copy, SLOT_ITEM_TYPE, true, 860);
                    leftoverOrWrong = copy.isReadable()
                            || decoded.size() != entries.size()
                            || decoded.get(0).item().identifier() != 35
                            || decoded.get(1).item().identifier() != 260;
                } catch (final RuntimeException ignored) {
                    leftoverOrWrong = true;
                }
                assertTrue(leftoverOrWrong, "legacy slot itemType must not round-trip instance creative entries");
            } finally {
                copy.release();
            }
        } finally {
            buffer.release();
        }
    }
}
