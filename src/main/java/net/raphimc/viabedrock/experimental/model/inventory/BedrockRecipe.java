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
package net.raphimc.viabedrock.experimental.model.inventory;

import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.List;

public record BedrockRecipe(
        String recipeId,
        RecipeType type,
        int width,
        int height,
        List<RecipeIngredient> ingredients,
        BedrockItem primaryOutput,
        List<BedrockItem> extraOutputs,
        String tag,
        int priority,
        int networkId,
        boolean assumeSymmetry
) {

    public enum RecipeType {
        SHAPED, SHAPELESS
    }

    public record RecipeIngredient(int runtimeId, int damage, int count) {
        public static final int ANY_DAMAGE = 0x7fff;

        public boolean matches(final BedrockItem item) {
            if (item.isEmpty()) return false;
            if (runtimeId != item.identifier()) return false;
            if (damage != ANY_DAMAGE && damage != item.data()) return false;
            return true;
        }
    }

}
