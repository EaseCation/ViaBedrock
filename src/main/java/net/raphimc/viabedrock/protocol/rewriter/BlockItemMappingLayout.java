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

import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;

/**
 * Safe lookup helpers for Bedrock block-item → Java item mapping.
 * <p>
 * Nukkit-MOT 860 START_GAME palettes (and NetEase extras such as
 * {@code askyblockwar:*} / {@code minecraft:cinnabar_*} walls) can list an
 * identifier in {@code bedrockToJavaBlockItems} while the session
 * {@code validBlockStates} table has no runtime ids. Official ViaBedrock
 * then called {@code IntSortedSet.firstInt()} on null and kicked Java 1.21.11
 * from {@code ADD_PLAYER} / {@code MOB_EQUIPMENT}. A missing palette must
 * fall through to the paper/custom-item path instead of throwing.
 */
public final class BlockItemMappingLayout {

    private BlockItemMappingLayout() {
    }

    /**
     * Default block runtime id for a block item that arrived with runtime 0.
     * {@code 0} means "unknown" and the caller must not treat it as a mapped state.
     */
    public static int fallbackBlockRuntimeId(final IntSortedSet validBlockStates) {
        if (validBlockStates == null || validBlockStates.isEmpty()) {
            return 0;
        }
        return validBlockStates.firstInt();
    }
}
