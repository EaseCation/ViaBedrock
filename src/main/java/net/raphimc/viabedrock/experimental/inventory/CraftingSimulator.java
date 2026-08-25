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

    // Nukkit SOURCE_TODO windowId values
    private static final int TODO_USE_INGREDIENT = -5;
    private static final int TODO_CRAFTING_RESULT = -4;

    /**
     * Simulates a PICKUP on the crafting output slot (left click to take crafting result to cursor).
     * Returns the list of Bedrock InventoryActionData, or null if no recipe matches.
     */
    public static List<InventoryActionData> simulateCraftPickup(final boolean is3x3, final InventoryTracker tracker) {
        return simulateCraftPickup(is3x3, tracker, JavaItemStackLimits.forTracker(tracker));
    }

    static List<InventoryActionData> simulateCraftPickup(final boolean is3x3, final InventoryTracker tracker,
                                                         final JavaItemStackLimits.Resolver stackLimits) {
        final BedrockItem[] gridItems = getGridItems(is3x3, tracker);
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        if (recipe == null) {
            return null;
        }

        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        final BedrockItem primaryOutput = recipe.primaryOutput().copy();
        final int maxStackSize = stackLimits.maxStackSize(primaryOutput);
        if (maxStackSize <= 0 || primaryOutput.amount() > maxStackSize) {
            return null;
        }

        // If cursor already has items, check if we can stack
        if (!cursorItem.isEmpty()) {
            if (cursorItem.isDifferent(primaryOutput)) {
                return Collections.emptyList(); // Can't take result — cursor has different item
            }
            if (cursorItem.amount() > maxStackSize || cursorItem.amount() + primaryOutput.amount() > maxStackSize) {
                return Collections.emptyList(); // Can't take result — would exceed max stack
            }
        }

        final List<InventoryActionData> actions = new ArrayList<>();

        // ACTION 1: per grid slot, SOURCE_TODO(-5 USE_INGREDIENT) + an explicit grid SlotChange that
        // decrements the slot by 1. This mirrors the real Bedrock client packets so the server's
        // CraftingTransaction collects the inputs and validates (single merged -5 with slot=0 and no grid
        // change made canExecute fail, leaving a stale transaction that corrupted the next craft).
        addGridConsumption(actions, is3x3, tracker);

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
        return simulateCraftQuickMove(is3x3, tracker, JavaItemStackLimits.forTracker(tracker));
    }

    static List<InventoryActionData> simulateCraftQuickMove(final boolean is3x3, final InventoryTracker tracker,
                                                            final JavaItemStackLimits.Resolver stackLimits) {
        final BedrockItem[] gridItems = getGridItems(is3x3, tracker);
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        if (recipe == null) {
            return null;
        }

        final BedrockItem primaryOutput = recipe.primaryOutput().copy();
        final int maxStackSize = stackLimits.maxStackSize(primaryOutput);
        if (maxStackSize <= 0) {
            return null;
        }

        final List<InventoryActionData> actions = new ArrayList<>();

        final int timesCrafted = timesCraftable(is3x3, tracker, recipe, primaryOutput, maxStackSize);
        if (timesCrafted <= 0) {
            return null;
        }

        // ACTION 1: per grid slot consumption (-5 USE_INGREDIENT + explicit grid SlotChange decrement)
        addGridConsumption(actions, is3x3, tracker, timesCrafted);

        final BedrockItem craftedOutput = primaryOutput.copy();
        craftedOutput.setAmount(primaryOutput.amount() * timesCrafted);

        // ACTION 2: SOURCE_TODO(-4) — set primaryOutput
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CRAFTING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, craftedOutput, BedrockItem.empty()
        ));

        // ACTION 3: Place result in inventory (find target slot)
        int remaining = craftedOutput.amount();

        // Round 1: fill existing stacks in main inventory (9-35) then hotbar (0-8)
        for (int invSlot = 9; invSlot <= 35 && remaining > 0; invSlot++) {
            remaining = tryMergeIntoSlot(actions, tracker, invSlot, craftedOutput, remaining, maxStackSize);
        }
        for (int invSlot = 0; invSlot <= 8 && remaining > 0; invSlot++) {
            remaining = tryMergeIntoSlot(actions, tracker, invSlot, craftedOutput, remaining, maxStackSize);
        }

        // Round 2: fill empty slots in main inventory (9-35) then hotbar (0-8)
        for (int invSlot = 9; invSlot <= 35 && remaining > 0; invSlot++) {
            remaining = tryPlaceIntoEmptySlot(actions, tracker, invSlot, craftedOutput, remaining, maxStackSize);
        }
        for (int invSlot = 0; invSlot <= 8 && remaining > 0; invSlot++) {
            remaining = tryPlaceIntoEmptySlot(actions, tracker, invSlot, craftedOutput, remaining, maxStackSize);
        }

        if (remaining > 0) {
            // Not enough room in inventory for the full result
            return null;
        }

        return actions;
    }

    private static int tryMergeIntoSlot(final List<InventoryActionData> actions, final InventoryTracker tracker,
                                        final int invSlot, final BedrockItem output, int remaining,
                                        final int maxStackSize) {
        final BedrockItem targetItem = tracker.getInventoryContainer().getItem(invSlot);
        if (targetItem.isEmpty() || targetItem.isDifferent(output) || targetItem.amount() >= maxStackSize) {
            return remaining;
        }
        int addAmount = Math.min(remaining, maxStackSize - targetItem.amount());
        BedrockItem newTarget = targetItem.copy();
        newTarget.setAmount(targetItem.amount() + addAmount);
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                invSlot, targetItem.copy(), newTarget
        ));
        return remaining - addAmount;
    }

    private static int tryPlaceIntoEmptySlot(final List<InventoryActionData> actions, final InventoryTracker tracker,
                                             final int invSlot, final BedrockItem output, int remaining,
                                             final int maxStackSize) {
        final BedrockItem targetItem = tracker.getInventoryContainer().getItem(invSlot);
        if (!targetItem.isEmpty()) {
            return remaining;
        }
        int addAmount = Math.min(remaining, maxStackSize);
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
    public static BedrockItem[] getGridItems(final boolean is3x3, final InventoryTracker tracker) {
        final int gridSize = is3x3 ? 9 : 4;
        final int startSlot = is3x3 ? 32 : 28;
        final BedrockItem[] gridItems = new BedrockItem[gridSize];
        for (int i = 0; i < gridSize; i++) {
            gridItems[i] = tracker.getHudContainer().getItem(startSlot + i);
        }
        return gridItems;
    }

    /**
     * For each non-empty crafting grid slot, emits the pair of actions a real Bedrock client sends when
     * crafting consumes items from that slot:
     *   1. SOURCE_TODO(-5 USE_INGREDIENT) with slot = grid-relative index (0-based), fromItem empty,
     *      toItem the consumed ingredient — this feeds the server's CraftingTransaction inputs.
     *   2. A ContainerInventory SlotChange on the HUD/UI container (id 124) at the absolute grid slot,
     *      decrementing the stack (or clearing it) — the actual grid mutation the server validates.
     */
    private static void addGridConsumption(final List<InventoryActionData> actions, final boolean is3x3, final InventoryTracker tracker) {
        addGridConsumption(actions, is3x3, tracker, 1);
    }

    private static void addGridConsumption(final List<InventoryActionData> actions, final boolean is3x3,
                                           final InventoryTracker tracker, final int timesCrafted) {
        final var hudContainer = tracker.getHudContainer();
        final int startSlot = is3x3 ? 32 : 28;
        final int gridSize = is3x3 ? 9 : 4;
        final int consumeCount = Math.max(1, timesCrafted);
        for (int i = 0; i < gridSize; i++) {
            final int hudSlot = startSlot + i;
            final BedrockItem gridItem = hudContainer.getItem(hudSlot);
            if (gridItem.isEmpty()) continue;

            final int consumed = Math.min(gridItem.amount(), consumeCount);
            final BedrockItem ingredient = gridItem.copy();
            ingredient.setAmount(consumed);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_USE_INGREDIENT, InventorySource_InventorySourceFlags.NoFlag),
                    i, BedrockItem.empty(), ingredient
            ));

            final BedrockItem newGrid;
            if (gridItem.amount() > consumed) {
                newGrid = gridItem.copy();
                newGrid.setAmount(gridItem.amount() - consumed);
            } else {
                newGrid = BedrockItem.empty();
            }
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                    hudSlot, gridItem.copy(), newGrid
            ));
        }
    }

    /**
     * Java Shift-click crafts as many times as the current grid and inventory room allow.
     * MOT extra-output recipes reject {@code timesCrafted != 1}, so those stay at one craft.
     */
    static int timesCraftable(final boolean is3x3, final InventoryTracker tracker, final BedrockRecipe recipe,
                              final BedrockItem primaryOutput, final int maxStackSize) {
        if (recipe == null || primaryOutput == null || primaryOutput.isEmpty() || maxStackSize <= 0) {
            return 0;
        }
        if (recipe.extraOutputs() != null && !recipe.extraOutputs().isEmpty()) {
            return 1;
        }
        final int perCraft = Math.max(1, primaryOutput.amount());
        int times = Integer.MAX_VALUE;
        for (final BedrockItem gridItem : getGridItems(is3x3, tracker)) {
            if (gridItem == null || gridItem.isEmpty()) {
                continue;
            }
            times = Math.min(times, gridItem.amount());
        }
        if (times == Integer.MAX_VALUE || times <= 0) {
            return 0;
        }
        int remainingCapacity = 0;
        remainingCapacity += remainingMergeCapacity(tracker, primaryOutput, maxStackSize, 9, 35);
        remainingCapacity += remainingMergeCapacity(tracker, primaryOutput, maxStackSize, 0, 8);
        remainingCapacity += remainingEmptyCapacity(tracker, maxStackSize, 9, 35);
        remainingCapacity += remainingEmptyCapacity(tracker, maxStackSize, 0, 8);
        times = Math.min(times, remainingCapacity / perCraft);
        return Math.max(0, times);
    }

    private static int remainingMergeCapacity(final InventoryTracker tracker, final BedrockItem output,
                                              final int maxStackSize, final int from, final int to) {
        int remaining = 0;
        for (int slot = from; slot <= to; slot++) {
            final BedrockItem target = tracker.getInventoryContainer().getItem(slot);
            if (target.isEmpty() || target.isDifferent(output) || target.amount() >= maxStackSize) {
                continue;
            }
            remaining += maxStackSize - target.amount();
        }
        return remaining;
    }

    private static int remainingEmptyCapacity(final InventoryTracker tracker, final int maxStackSize,
                                              final int from, final int to) {
        int remaining = 0;
        for (int slot = from; slot <= to; slot++) {
            if (tracker.getInventoryContainer().getItem(slot).isEmpty()) {
                remaining += maxStackSize;
            }
        }
        return remaining;
    }

    private static InventoryActionData cursorAction(final BedrockItem from, final BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, from.copy(), to.copy()
        );
    }

}
