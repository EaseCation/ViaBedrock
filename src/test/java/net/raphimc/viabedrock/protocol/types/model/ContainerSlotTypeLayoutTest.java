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
package net.raphimc.viabedrock.protocol.types.model;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerSlotTypeLayoutTest {

    @Test
    void neteaseShiftsIdsFromRecipeItems() {
        assertTrue(ContainerSlotTypeLayout.usesNetEaseIdShift(true));
        assertFalse(ContainerSlotTypeLayout.usesNetEaseIdShift(false));
        assertEquals(17, ContainerSlotTypeLayout.toWire(ContainerEnumName.RecipeItemsContainer, false));
        assertEquals(18, ContainerSlotTypeLayout.toWire(ContainerEnumName.RecipeItemsContainer, true));
        assertEquals(63, ContainerSlotTypeLayout.toWire(ContainerEnumName.DynamicContainer, false));
        assertEquals(64, ContainerSlotTypeLayout.toWire(ContainerEnumName.DynamicContainer, true));
        assertEquals(0, ContainerSlotTypeLayout.toWire(null, true));
    }

    @Test
    void neteaseWire64IsDynamicContainer() {
        assertEquals(ContainerEnumName.DynamicContainer, ContainerSlotTypeLayout.fromWire(64, true));
        assertEquals(ContainerEnumName.RecipeFoodContainer, ContainerSlotTypeLayout.fromWire(64, false));
        assertNull(ContainerSlotTypeLayout.fromWire(17, true));
        assertEquals(ContainerEnumName.RecipeItemsContainer, ContainerSlotTypeLayout.fromWire(17, false));
    }
}
