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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaItemStackLimitsTest {

    @Test
    void usesExplicitComponentBeforeVanillaFallback() {
        assertEquals(1, resolve(itemWithLimit(1), id -> 64));
        assertEquals(16, resolve(itemWithLimit(16), id -> 64));
        assertEquals(64, resolve(itemWithLimit(64), id -> 1));
        assertEquals(99, resolve(itemWithLimit(99), id -> 1));
    }

    @Test
    void treatsRemovedComponentAsNonStackable() {
        final StructuredDataContainer data = new StructuredDataContainer(new StructuredData<?>[]{
                StructuredData.empty(StructuredDataKey.MAX_STACK_SIZE, 1)
        });

        assertEquals(1, resolve(new StructuredItem(2, 1, data), id -> 64));
    }

    @Test
    void rejectsInvalidComponentAndFallbackLimits() {
        assertEquals(JavaItemStackLimits.UNSUPPORTED, resolve(itemWithLimit(0), id -> 64));
        assertEquals(JavaItemStackLimits.UNSUPPORTED, resolve(itemWithLimit(100), id -> 64));
        assertEquals(JavaItemStackLimits.UNSUPPORTED, resolve(itemWithoutLimit(), id -> 0));
        assertEquals(JavaItemStackLimits.UNSUPPORTED, resolve(itemWithoutLimit(), id -> 100));
    }

    @Test
    void usesGeneratedVanillaLimitForKnownItems() {
        assertEquals(16, resolve(itemWithoutLimit(), id -> 16));
        assertEquals(64, resolve(itemWithoutLimit(), id -> 64));
    }

    @Test
    void rejectsEmptyUnknownAndFailedTranslations() {
        assertEquals(JavaItemStackLimits.UNSUPPORTED,
                JavaItemStackLimits.resolve(StructuredItem.empty(), 10, id -> 64));
        assertEquals(JavaItemStackLimits.UNSUPPORTED,
                JavaItemStackLimits.resolve(new StructuredItem(10, 1, new StructuredDataContainer()), 10, id -> 64));
        assertEquals(JavaItemStackLimits.UNSUPPORTED, resolve(itemWithoutLimit(), id -> {
            throw new IllegalStateException("missing mapping");
        }));
    }

    private static int resolve(final Item item, final java.util.function.IntUnaryOperator fallback) {
        return JavaItemStackLimits.resolve(item, 10, fallback);
    }

    private static Item itemWithLimit(final int limit) {
        final StructuredDataContainer data = new StructuredDataContainer(new StructuredData<?>[]{
                StructuredData.of(StructuredDataKey.MAX_STACK_SIZE, limit, 1)
        });
        return new StructuredItem(2, 1, data);
    }

    private static Item itemWithoutLimit() {
        return new StructuredItem(2, 1, new StructuredDataContainer());
    }

}
