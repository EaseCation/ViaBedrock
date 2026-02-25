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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.ArrayList;
import java.util.List;

public class RecipeRegistry extends StoredObject {

    private final List<BedrockRecipe> craftingRecipes = new ArrayList<>();

    public RecipeRegistry(final UserConnection user) {
        super(user);
    }

    public void clear() {
        this.craftingRecipes.clear();
    }

    public void addRecipe(final BedrockRecipe recipe) {
        this.craftingRecipes.add(recipe);
    }

    public int recipeCount() {
        return this.craftingRecipes.size();
    }

    public BedrockRecipe matchRecipe(final BedrockItem[] gridItems, final boolean is3x3) {
        final int gridWidth = is3x3 ? 3 : 2;
        final int gridHeight = is3x3 ? 3 : 2;

        BedrockRecipe bestMatch = null;
        int bestPriority = Integer.MAX_VALUE;

        for (final BedrockRecipe recipe : this.craftingRecipes) {
            if (recipe.type() == BedrockRecipe.RecipeType.SHAPED) {
                if (matchShaped(recipe, gridItems, gridWidth, gridHeight) && recipe.priority() < bestPriority) {
                    bestMatch = recipe;
                    bestPriority = recipe.priority();
                }
            } else if (recipe.type() == BedrockRecipe.RecipeType.SHAPELESS) {
                if (matchShapeless(recipe, gridItems) && recipe.priority() < bestPriority) {
                    bestMatch = recipe;
                    bestPriority = recipe.priority();
                }
            }
        }

        return bestMatch;
    }

    private static boolean matchShapeless(final BedrockRecipe recipe, final BedrockItem[] gridItems) {
        final List<RecipeIngredient> ingredients = recipe.ingredients();

        // Count non-empty grid items (each grid slot has amount=1 for crafting purposes)
        int nonEmptyGridCount = 0;
        for (final BedrockItem item : gridItems) {
            if (!item.isEmpty()) nonEmptyGridCount++;
        }

        // Total ingredient count must match non-empty grid items
        int totalIngredientCount = 0;
        for (final RecipeIngredient ingredient : ingredients) {
            totalIngredientCount += ingredient.count();
        }
        if (totalIngredientCount != nonEmptyGridCount) return false;

        // Track which grid slots have been matched
        final boolean[] matched = new boolean[gridItems.length];

        // For each ingredient, try to consume matching grid items
        for (final RecipeIngredient ingredient : ingredients) {
            int remaining = ingredient.count();
            for (int i = 0; i < gridItems.length && remaining > 0; i++) {
                if (matched[i]) continue;
                if (ingredient.matches(gridItems[i])) {
                    matched[i] = true;
                    remaining--;
                }
            }
            if (remaining > 0) return false;
        }

        return true;
    }

    private static boolean matchShaped(final BedrockRecipe recipe, final BedrockItem[] gridItems, final int gridWidth, final int gridHeight) {
        if (recipe.width() > gridWidth || recipe.height() > gridHeight) return false;

        // Try all possible offsets and mirror (mirror only if assumeSymmetry is true)
        final int maxMirror = recipe.assumeSymmetry() ? 1 : 0;
        for (int mirror = 0; mirror <= maxMirror; mirror++) {
            for (int offX = 0; offX <= gridWidth - recipe.width(); offX++) {
                for (int offY = 0; offY <= gridHeight - recipe.height(); offY++) {
                    if (matchShapedAt(recipe, gridItems, gridWidth, gridHeight, offX, offY, mirror == 1)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean matchShapedAt(final BedrockRecipe recipe, final BedrockItem[] gridItems, final int gridWidth, final int gridHeight, final int offX, final int offY, final boolean mirror) {
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                final int gridIndex = y * gridWidth + x;
                final BedrockItem gridItem = gridIndex < gridItems.length ? gridItems[gridIndex] : BedrockItem.empty();

                final int recipeX = mirror ? (recipe.width() - 1 - (x - offX)) : (x - offX);
                final int recipeY = y - offY;

                if (recipeX >= 0 && recipeX < recipe.width() && recipeY >= 0 && recipeY < recipe.height()) {
                    final int recipeIndex = recipeY * recipe.width() + recipeX;
                    final RecipeIngredient ingredient = recipe.ingredients().get(recipeIndex);

                    if (ingredient.runtimeId() == 0) {
                        if (!gridItem.isEmpty()) return false;
                    } else {
                        if (!ingredient.matches(gridItem)) return false;
                    }
                } else {
                    if (!gridItem.isEmpty()) return false;
                }
            }
        }
        return true;
    }

}
