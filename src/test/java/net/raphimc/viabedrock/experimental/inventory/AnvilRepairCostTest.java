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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.nbt.tag.CompoundTag;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnvilRepairCostTest {

    @Test
    void repairMaterialLoopSpendsQuarterDurabilityUnits() {
        assertEquals(3, AnvilRepairCost.repairMaterialUnits(800, 1562, 8));
        assertEquals(-1, AnvilRepairCost.repairMaterialUnits(0, 1562, 8));
        assertEquals(1, AnvilRepairCost.materialCount(damaged(276, 100), item(264, 4), null));
        assertEquals(1, AnvilRepairCost.materialCount(damaged(276, 100), item(276, 1), null));
        assertEquals(0, AnvilRepairCost.materialCount(damaged(276, 100), BedrockItem.empty(), null));
    }

    private static BedrockItem item(final int id, final int amount) {
        return new BedrockItem(id, (short) 0, (byte) amount, null, new String[0], new String[0], 0, 0, 1);
    }

    private static BedrockItem damaged(final int id, final int damage) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", damage);
        return new BedrockItem(id, (short) 0, (byte) 1, tag, new String[0], new String[0], 0, 0, 1);
    }
}
