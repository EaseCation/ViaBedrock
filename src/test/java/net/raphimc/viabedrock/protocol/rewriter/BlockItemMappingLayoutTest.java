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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.viaversion.libs.fastutil.ints.IntLinkedOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockItemMappingLayoutTest {

    @Test
    void missingPaletteDoesNotThrowAndYieldsUnknownRuntime() {
        assertEquals(0, assertDoesNotThrow(() -> BlockItemMappingLayout.fallbackBlockRuntimeId(null)));
        assertEquals(0, BlockItemMappingLayout.fallbackBlockRuntimeId(new IntLinkedOpenHashSet()));
    }

    @Test
    void populatedPaletteUsesTheFirstRuntimeId() {
        final IntSortedSet valid = new IntLinkedOpenHashSet();
        valid.add(42);
        valid.add(7);
        assertEquals(42, BlockItemMappingLayout.fallbackBlockRuntimeId(valid));
    }

    @Test
    void emptyPaletteKeepsTheWireRuntimeInsteadOfCallingFirstInt() {
        assertEquals(12345, BlockItemMappingLayout.sanitizeBlockRuntimeId(null, 12345));
        assertEquals(12345, BlockItemMappingLayout.sanitizeBlockRuntimeId(new IntLinkedOpenHashSet(), 12345));
    }

    @Test
    void populatedPaletteRemapsUnknownRuntimeToTheFirstKnownId() {
        final IntSortedSet valid = new IntLinkedOpenHashSet();
        valid.add(42);
        valid.add(7);
        assertEquals(42, BlockItemMappingLayout.sanitizeBlockRuntimeId(valid, 99));
        assertEquals(7, BlockItemMappingLayout.sanitizeBlockRuntimeId(valid, 7));
    }
}
