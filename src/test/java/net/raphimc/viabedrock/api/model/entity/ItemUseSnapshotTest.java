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
package net.raphimc.viabedrock.api.model.entity;

import com.viaversion.nbt.tag.CompoundTag;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity.ItemUseSnapshot;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseSnapshotTest {

    @Test
    void matchesSameSlotAndIdentityDespiteAmountChanges() {
        final BedrockItem original = item(100, (short) 2, (byte) 8, 200, "heal");
        final ItemUseSnapshot snapshot = new ItemUseSnapshot((byte) 3, original);

        original.setIdentifier(101);
        final BedrockItem current = item(100, (short) 2, (byte) 7, 200, "heal");

        assertTrue(snapshot.matches((byte) 3, current));
    }

    @Test
    void rejectsSlotOrItemIdentityChanges() {
        final ItemUseSnapshot snapshot = new ItemUseSnapshot((byte) 3, item(100, (short) 2, (byte) 8, 200, "heal"));

        assertFalse(snapshot.matches((byte) 4, item(100, (short) 2, (byte) 8, 200, "heal")));
        assertFalse(snapshot.matches((byte) 3, item(101, (short) 2, (byte) 8, 200, "heal")));
        assertFalse(snapshot.matches((byte) 3, item(100, (short) 3, (byte) 8, 200, "heal")));
        assertFalse(snapshot.matches((byte) 3, item(100, (short) 2, (byte) 8, 201, "heal")));
        assertFalse(snapshot.matches((byte) 3, item(100, (short) 2, (byte) 8, 200, "speed")));
        assertFalse(snapshot.matches((byte) 3, null));
    }

    private static BedrockItem item(final int id, final short data, final byte amount, final int blockRuntimeId, final String effect) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("effect", effect);
        final BedrockItem item = new BedrockItem(id, data, amount, tag);
        item.setBlockRuntimeId(blockRuntimeId);
        return item;
    }

}
