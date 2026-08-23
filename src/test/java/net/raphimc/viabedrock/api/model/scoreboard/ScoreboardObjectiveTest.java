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
package net.raphimc.viabedrock.api.model.scoreboard;

import com.viaversion.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoreboardObjectiveTest {

    @Test
    void keepsRawGanquanFlagsInInsertion() {
        final CompoundTag tag = (CompoundTag) ScoreboardObjective.ganquanScoreboardEntryName("\u00a7r\u00a7hPlayer: Name");
        assertEquals("\u00a7r\u00a7hPlayer: Name", tag.getString("insertion"));
    }

    @Test
    void emptyNamesStayUnnamed() {
        assertNull(ScoreboardObjective.ganquanScoreboardEntryName(""));
        assertNull(ScoreboardObjective.ganquanScoreboardEntryName(null));
    }

}
