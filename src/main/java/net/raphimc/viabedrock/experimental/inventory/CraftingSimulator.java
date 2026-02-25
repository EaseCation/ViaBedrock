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

import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.*;

public class CraftingSimulator {

    private static final int MAX_STACK = 64;

    // Nukkit SOURCE_TODO windowId values
    private static final int TODO_USE_INGREDIENT = -5;
    private static final int TODO_CRAFTING_RESULT = -4;

    /**
     * Simulates a PICKUP on the crafting output slot (left click to take crafting result to cursor).
     * Returns the list of Bedrock InventoryActionData, or null if no recipe matches.
     */
    public static List<InventoryActionData> simulateCraftPickup(final boolean is3x3, final InventoryTracker tracker) {
        final BedrockItem[] gridItems = getGridItems(is3x3, tracker);
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        if (recipe == null) {
            return null;
        }

        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        final BedrockItem primaryOutput = recipe.primaryOutput().copy();

        // If cursor already has items, check if we can stack
        if (!cursorItem.isEmpty()) {
            if (cursorItem.isDifferent(primaryOutput)) {
                return Collections.emptyList(); // Can't take result — cursor has different item
            }
            if (cursorItem.amount() + primaryOutput.amount() > MAX_STACK) {
                return Collections.emptyList(); // Can't take result — would exceed max stack
            }
        }

        final List<InventoryActionData> actions = new ArrayList<>();

        // ACTION 1: SOURCE_TODO(-5) for each ingredient type consumed
        addIngredientActions(actions, gridItems);

        // ACTION 2: SOURCE_TODO(-4) — set primaryOutput
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CRAFTING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, primaryOutput, BedrockItem.empty()
        ));

        // ACTION 3: cursor update — place result in cursor
        final BedrockItem newCursor;
        if (cursorItem.isEmpty()) {
            newCursor = primaryOutput.copy();
        } else {
            newCursor = cursorItem.copy();
            newCursor.setAmount(cursorItem.amount() + primaryOutput.amount());
        }
        actions.add(cursorAction(cursorItem, newCursor));

        return actions;
    }

    /**
     * Simulates a QUICK_MOVE (Shift+Click) on the crafting output slot.
     * The crafted item goes directly into the player's inventory.
     * Returns the list of Bedrock InventoryActionData, or null if no recipe matches.
     */
    public static List<InventoryActionData> simulateCraftQuickMove(final boolean is3x3, final InventoryTracker tracker) {
        final BedrockItem[] gridItems = getGridItems(is3x3, tracker);
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        if (recipe == null) {
            return null;
        }

        final BedrockItem primaryOutput = recipe.primaryOutput().copy();

        final List<InventoryActionData> actions = new ArrayList<>();

        // ACTION 1: SOURCE_TODO(-5) for ingredients
        addIngredientActions(actions, gridItems);

        // ACTION 2: SOURCE_TODO(-4) — set primaryOutput
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CRAFTING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, primaryOutput, BedrockItem.empty()
        ));

        // ACTION 3: Place result in inventory (find target slot)
        int remaining = primaryOutput.amount();

        // Round 1: fill existing stacks in main inventory (9-35) then hotbar (0-8)
        for (int invSlot = 9; invSlot <= 35 && remaining > 0; invSlot++) {
            remaining = tryMergeIntoSlot(actions, tracker, invSlot, primaryOutput, remaining);
        }
        for (int invSlot = 0; invSlot <= 8 && remaining > 0; invSlot++) {
            remaining = tryMergeIntoSlot(actions, tracker, invSlot, primaryOutput, remaining);
        }

        // Round 2: fill empty slots in main inventory (9-35) then hotbar (0-8)
        for (int invSlot = 9; invSlot <= 35 && remaining > 0; invSlot++) {
            remaining = tryPlaceIntoEmptySlot(actions, tracker, invSlot, primaryOutput, remaining);
        }
        for (int invSlot = 0; invSlot <= 8 && remaining > 0; invSlot++) {
            remaining = tryPlaceIntoEmptySlot(actions, tracker, invSlot, primaryOutput, remaining);
        }

        if (remaining > 0) {
            // Not enough room in inventory for the full result
            return null;
        }

        return actions;
    }

    private static int tryMergeIntoSlot(final List<InventoryActionData> actions, final InventoryTracker tracker, final int invSlot, final BedrockItem output, int remaining) {
        final BedrockItem targetItem = tracker.getInventoryContainer().getItem(invSlot);
        if (targetItem.isEmpty() || targetItem.isDifferent(output) || targetItem.amount() >= MAX_STACK) {
            return remaining;
        }
        int addAmount = Math.min(remaining, MAX_STACK - targetItem.amount());
        BedrockItem newTarget = targetItem.copy();
        newTarget.setAmount(targetItem.amount() + addAmount);
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                invSlot, targetItem.copy(), newTarget
        ));
        return remaining - addAmount;
    }

    private static int tryPlaceIntoEmptySlot(final List<InventoryActionData> actions, final InventoryTracker tracker, final int invSlot, final BedrockItem output, int remaining) {
        final BedrockItem targetItem = tracker.getInventoryContainer().getItem(invSlot);
        if (!targetItem.isEmpty()) {
            return remaining;
        }
        int addAmount = Math.min(remaining, MAX_STACK);
        BedrockItem newTarget = output.copy();
        newTarget.setAmount(addAmount);
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                invSlot, BedrockItem.empty(), newTarget
        ));
        return remaining - addAmount;
    }

    /**
     * Reads the crafting grid contents from HUD container.
     * 2x2: HUD slots 28-31
     * 3x3: HUD slots 32-40
     */
    private static BedrockItem[] getGridItems(final boolean is3x3, final InventoryTracker tracker) {
        final int gridSize = is3x3 ? 9 : 4;
        final int startSlot = is3x3 ? 32 : 28;
        final BedrockItem[] gridItems = new BedrockItem[gridSize];
        for (int i = 0; i < gridSize; i++) {
            gridItems[i] = tracker.getHudContainer().getItem(startSlot + i);
        }
        return gridItems;
    }

    /**
     * Creates SOURCE_TODO(-5) actions for each ingredient type consumed.
     * Each non-empty grid slot contributes one action with count=1.
     * Same item types are merged into one action with summed count.
     */
    private static void addIngredientActions(final List<InventoryActionData> actions, final BedrockItem[] gridItems) {
        // Merge by (runtimeId, data) to combine same materials
        final Map<Long, BedrockItem> merged = new LinkedHashMap<>();
        for (final BedrockItem gridItem : gridItems) {
            if (gridItem.isEmpty()) continue;
            long key = ((long) gridItem.identifier() << 16) | (gridItem.data() & 0xFFFF);
            BedrockItem existing = merged.get(key);
            if (existing == null) {
                BedrockItem ingredient = gridItem.copy();
                ingredient.setAmount(1);
                merged.put(key, ingredient);
            } else {
                existing.setAmount(existing.amount() + 1);
            }
        }

        for (final BedrockItem ingredient : merged.values()) {
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_USE_INGREDIENT, InventorySource_InventorySourceFlags.NoFlag),
                    0, BedrockItem.empty(), ingredient
            ));
        }
    }

    private static InventoryActionData cursorAction(final BedrockItem from, final BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, from.copy(), to.copy()
        );
    }

}
