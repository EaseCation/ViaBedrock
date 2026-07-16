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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomItemDataComponentsTest {

    @Test
    void synchronizedStackSizeIsApplied() {
        final StructuredItem item = itemWith();

        CustomItemDataComponents.applyMaxStackSize(item, 16, false);

        assertEquals(16, item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    @Test
    void resourceStackSizeDoesNotReplaceSynchronizedValue() {
        final StructuredItem item = itemWith(StructuredData.of(StructuredDataKey.MAX_STACK_SIZE, 64, 1));

        CustomItemDataComponents.applyMaxStackSize(item, 16, true);

        assertEquals(64, item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    @Test
    void unknownStackSizeDoesNotAddComponent() {
        final StructuredItem item = itemWith();

        CustomItemDataComponents.applyMaxStackSize(item, null, false);

        assertNull(item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    private static StructuredItem itemWith(final StructuredData<?>... data) {
        return new StructuredItem(10_000, 1, new StructuredDataContainer(data));
    }

}
