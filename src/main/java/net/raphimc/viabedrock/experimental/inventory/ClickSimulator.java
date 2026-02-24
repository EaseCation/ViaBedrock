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

import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.experimental.inventory.SlotMapper.BedrockSlotRef;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ClickType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClickSimulator {

    private static final int MAX_STACK = 64;

    /**
     * Simulates a Java CONTAINER_CLICK and returns the list of Bedrock InventoryActionData.
     * Returns null if the operation is unsupported (caller should rollback).
     * Returns empty list if there's nothing to do (no packet sent, no rollback).
     */
    public static List<InventoryActionData> simulate(
            final int javaContainerId,
            final short javaSlot,
            final byte button,
            final ClickType action,
            final InventoryTracker tracker,
            final ClientAuthInventoryModule.DragState dragState) {

        return switch (action) {
            case PICKUP -> simulatePickup(javaContainerId, javaSlot, button, tracker);
            case QUICK_MOVE -> simulateQuickMove(javaContainerId, javaSlot, tracker);
            case SWAP -> simulateSwap(javaContainerId, javaSlot, button, tracker);
            case CLONE -> simulateClone(javaContainerId, javaSlot, tracker);
            case THROW -> simulateThrow(javaContainerId, javaSlot, button, tracker);
            case QUICK_CRAFT -> simulateQuickCraft(javaContainerId, javaSlot, button, tracker, dragState);
            case PICKUP_ALL -> simulatePickupAll(javaContainerId, javaSlot, tracker);
        };
    }

    // --- PICKUP (mode=0) ---

    private static List<InventoryActionData> simulatePickup(int javaContainerId, short javaSlot, byte button, InventoryTracker tracker) {
        if (javaSlot == -999) {
            // Click outside window — drop cursor item
            return simulateDropCursor(button, tracker);
        }

        final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, javaSlot, tracker);
        if (ref == null) return null;

        final BedrockItem slotItem = ref.container().getItem(ref.slot());
        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);

        if (button == 0) {
            // Left click
            if (cursorItem.isEmpty() && slotItem.isEmpty()) {
                return Collections.emptyList();
            } else if (cursorItem.isEmpty()) {
                // Pick up entire stack
                return List.of(
                        slotAction(ref, slotItem, BedrockItem.empty()),
                        cursorAction(cursorItem, slotItem)
                );
            } else if (slotItem.isEmpty()) {
                // Place entire stack
                return List.of(
                        slotAction(ref, slotItem, cursorItem),
                        cursorAction(cursorItem, BedrockItem.empty())
                );
            } else if (canStack(slotItem, cursorItem)) {
                // Merge cursor into slot
                int merged = Math.min(slotItem.amount() + cursorItem.amount(), MAX_STACK);
                int remaining = slotItem.amount() + cursorItem.amount() - merged;
                BedrockItem newSlot = slotItem.copy();
                newSlot.setAmount(merged);
                BedrockItem newCursor = remaining > 0 ? cursorItem.copy() : BedrockItem.empty();
                if (remaining > 0) newCursor.setAmount(remaining);
                return List.of(
                        slotAction(ref, slotItem, newSlot),
                        cursorAction(cursorItem, newCursor)
                );
            } else {
                // Swap cursor and slot
                return List.of(
                        slotAction(ref, slotItem, cursorItem),
                        cursorAction(cursorItem, slotItem)
                );
            }
        } else if (button == 1) {
            // Right click
            if (cursorItem.isEmpty() && slotItem.isEmpty()) {
                return Collections.emptyList();
            } else if (cursorItem.isEmpty()) {
                // Pick up half
                int takeAmount = (slotItem.amount() + 1) / 2;
                int leaveAmount = slotItem.amount() - takeAmount;
                BedrockItem newSlot = leaveAmount > 0 ? slotItem.copy() : BedrockItem.empty();
                if (leaveAmount > 0) newSlot.setAmount(leaveAmount);
                BedrockItem newCursor = slotItem.copy();
                newCursor.setAmount(takeAmount);
                return List.of(
                        slotAction(ref, slotItem, newSlot),
                        cursorAction(cursorItem, newCursor)
                );
            } else if (slotItem.isEmpty()) {
                // Place one
                BedrockItem newSlot = cursorItem.copy();
                newSlot.setAmount(1);
                BedrockItem newCursor = cursorItem.amount() > 1 ? cursorItem.copy() : BedrockItem.empty();
                if (cursorItem.amount() > 1) newCursor.setAmount(cursorItem.amount() - 1);
                return List.of(
                        slotAction(ref, slotItem, newSlot),
                        cursorAction(cursorItem, newCursor)
                );
            } else if (canStack(slotItem, cursorItem) && slotItem.amount() < MAX_STACK) {
                // Place one into stackable slot
                BedrockItem newSlot = slotItem.copy();
                newSlot.setAmount(slotItem.amount() + 1);
                BedrockItem newCursor = cursorItem.amount() > 1 ? cursorItem.copy() : BedrockItem.empty();
                if (cursorItem.amount() > 1) newCursor.setAmount(cursorItem.amount() - 1);
                return List.of(
                        slotAction(ref, slotItem, newSlot),
                        cursorAction(cursorItem, newCursor)
                );
            } else {
                // Swap cursor and slot (different types)
                return List.of(
                        slotAction(ref, slotItem, cursorItem),
                        cursorAction(cursorItem, slotItem)
                );
            }
        }
        return null;
    }

    private static List<InventoryActionData> simulateDropCursor(byte button, InventoryTracker tracker) {
        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        if (cursorItem.isEmpty()) return Collections.emptyList();

        if (button == 0) {
            // Left click outside — drop entire stack
            return List.of(
                    worldDropAction(cursorItem),
                    cursorAction(cursorItem, BedrockItem.empty())
            );
        } else if (button == 1) {
            // Right click outside — drop one
            BedrockItem dropped = cursorItem.copy();
            dropped.setAmount(1);
            BedrockItem remaining = cursorItem.amount() > 1 ? cursorItem.copy() : BedrockItem.empty();
            if (cursorItem.amount() > 1) remaining.setAmount(cursorItem.amount() - 1);
            return List.of(
                    worldDropAction(dropped),
                    cursorAction(cursorItem, remaining)
            );
        }
        return null;
    }

    // --- QUICK_MOVE (mode=1, Shift+Click) ---

    private static List<InventoryActionData> simulateQuickMove(int javaContainerId, short javaSlot, InventoryTracker tracker) {
        final BedrockSlotRef sourceRef = SlotMapper.resolve(javaContainerId, javaSlot, tracker);
        if (sourceRef == null) return null;

        final BedrockItem sourceItem = sourceRef.container().getItem(sourceRef.slot());
        if (sourceItem.isEmpty()) return Collections.emptyList();

        // Determine target slot ranges based on source location
        final List<int[]> targetRanges = getQuickMoveTargets(javaContainerId, javaSlot, tracker);

        int remaining = sourceItem.amount();
        final List<InventoryActionData> actions = new ArrayList<>();

        // Round 1: Fill existing stacks of same type
        for (int[] range : targetRanges) {
            if (remaining <= 0) break;
            for (int targetJavaSlot = range[0]; targetJavaSlot <= range[1]; targetJavaSlot++) {
                if (remaining <= 0) break;
                final BedrockSlotRef targetRef = SlotMapper.resolve(javaContainerId == 0 ? 0 : javaContainerId, targetJavaSlot, tracker);
                if (targetRef == null) continue;
                final BedrockItem targetItem = targetRef.container().getItem(targetRef.slot());
                if (targetItem.isEmpty() || !canStack(targetItem, sourceItem)) continue;
                if (targetItem.amount() >= MAX_STACK) continue;

                int addAmount = Math.min(remaining, MAX_STACK - targetItem.amount());
                BedrockItem newTarget = targetItem.copy();
                newTarget.setAmount(targetItem.amount() + addAmount);
                actions.add(slotAction(targetRef, targetItem, newTarget));
                remaining -= addAmount;
            }
        }

        // Round 2: Fill empty slots
        for (int[] range : targetRanges) {
            if (remaining <= 0) break;
            for (int targetJavaSlot = range[0]; targetJavaSlot <= range[1]; targetJavaSlot++) {
                if (remaining <= 0) break;
                final BedrockSlotRef targetRef = SlotMapper.resolve(javaContainerId == 0 ? 0 : javaContainerId, targetJavaSlot, tracker);
                if (targetRef == null) continue;
                final BedrockItem targetItem = targetRef.container().getItem(targetRef.slot());
                if (!targetItem.isEmpty()) continue;

                int addAmount = Math.min(remaining, MAX_STACK);
                BedrockItem newTarget = sourceItem.copy();
                newTarget.setAmount(addAmount);
                actions.add(slotAction(targetRef, targetItem, newTarget));
                remaining -= addAmount;
            }
        }

        if (actions.isEmpty()) return Collections.emptyList();

        // Source slot action: item → remaining or empty
        BedrockItem newSource = remaining > 0 ? sourceItem.copy() : BedrockItem.empty();
        if (remaining > 0) newSource.setAmount(remaining);
        actions.add(0, slotAction(sourceRef, sourceItem, newSource));

        return actions;
    }

    private static List<int[]> getQuickMoveTargets(int javaContainerId, int javaSlot, InventoryTracker tracker) {
        if (javaContainerId != 0) {
            // Container window
            final Container currentContainer = tracker.getCurrentContainer();
            final int containerSize = currentContainer != null ? currentContainer.size() : 27;

            if (javaSlot < containerSize) {
                // Source is in container → target is player inventory (9-35 then 0-8)
                return List.of(new int[]{containerSize, containerSize + 26}, new int[]{containerSize + 27, containerSize + 35});
            } else if (javaSlot >= containerSize + 27) {
                // Source is in hotbar → target is container then main inventory
                return List.of(new int[]{0, containerSize - 1}, new int[]{containerSize, containerSize + 26});
            } else {
                // Source is in main inventory → target is container then hotbar
                return List.of(new int[]{0, containerSize - 1}, new int[]{containerSize + 27, containerSize + 35});
            }
        } else {
            // Player Inventory Window
            if (javaSlot >= 9 && javaSlot <= 35) {
                // Main inventory → hotbar
                return List.of(new int[]{36, 44});
            } else if (javaSlot >= 36 && javaSlot <= 44) {
                // Hotbar → main inventory
                return List.of(new int[]{9, 35});
            } else if (javaSlot >= 5 && javaSlot <= 8) {
                // Armor → main inventory + hotbar
                return List.of(new int[]{9, 35}, new int[]{36, 44});
            } else if (javaSlot == 45) {
                // Offhand → main inventory + hotbar
                return List.of(new int[]{9, 35}, new int[]{36, 44});
            } else if (javaSlot >= 1 && javaSlot <= 4) {
                // Crafting input → main inventory + hotbar
                return List.of(new int[]{9, 35}, new int[]{36, 44});
            }
            return Collections.emptyList();
        }
    }

    // --- SWAP (mode=2) ---

    private static List<InventoryActionData> simulateSwap(int javaContainerId, short javaSlot, byte button, InventoryTracker tracker) {
        final BedrockSlotRef clickedRef = SlotMapper.resolve(javaContainerId, javaSlot, tracker);
        if (clickedRef == null) return null;

        final BedrockSlotRef otherRef;
        if (button >= 0 && button <= 8) {
            // Number key 1-9 → hotbar slot
            otherRef = new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), button, tracker.getInventoryContainer());
        } else if (button == 40) {
            // F key → offhand
            otherRef = new BedrockSlotRef(ContainerID.CONTAINER_ID_OFFHAND.getValue(), 0, tracker.getOffhandContainer());
        } else {
            return null;
        }

        final BedrockItem clickedItem = clickedRef.container().getItem(clickedRef.slot());
        final BedrockItem otherItem = otherRef.container().getItem(otherRef.slot());

        if (clickedItem.isEmpty() && otherItem.isEmpty()) {
            return Collections.emptyList();
        }

        return List.of(
                slotAction(clickedRef, clickedItem, otherItem),
                slotAction(otherRef, otherItem, clickedItem)
        );
    }

    // --- CLONE (mode=3, Creative middle click) ---

    private static List<InventoryActionData> simulateClone(int javaContainerId, short javaSlot, InventoryTracker tracker) {
        final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, javaSlot, tracker);
        if (ref == null) return null;

        final BedrockItem slotItem = ref.container().getItem(ref.slot());
        if (slotItem.isEmpty()) return Collections.emptyList();

        final BedrockItem cloned = slotItem.copy();
        cloned.setAmount(MAX_STACK);

        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);

        return List.of(
                new InventoryActionData(
                        new InventorySource(InventorySourceType.CreativeInventory, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                        0, BedrockItem.empty(), cloned
                ),
                cursorAction(cursorItem, cloned)
        );
    }

    // --- THROW (mode=4) ---

    private static List<InventoryActionData> simulateThrow(int javaContainerId, short javaSlot, byte button, InventoryTracker tracker) {
        if (javaSlot == -999) {
            // Drop from cursor (same as PICKUP outside window)
            return simulateDropCursor(button, tracker);
        }

        final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, javaSlot, tracker);
        if (ref == null) return null;

        final BedrockItem slotItem = ref.container().getItem(ref.slot());
        if (slotItem.isEmpty()) return Collections.emptyList();

        if (button == 0) {
            // Q — drop one
            BedrockItem dropped = slotItem.copy();
            dropped.setAmount(1);
            BedrockItem remaining = slotItem.amount() > 1 ? slotItem.copy() : BedrockItem.empty();
            if (slotItem.amount() > 1) remaining.setAmount(slotItem.amount() - 1);
            return List.of(
                    worldDropAction(dropped),
                    slotAction(ref, slotItem, remaining)
            );
        } else if (button == 1) {
            // Ctrl+Q — drop entire stack
            return List.of(
                    worldDropAction(slotItem),
                    slotAction(ref, slotItem, BedrockItem.empty())
            );
        }
        return null;
    }

    // --- QUICK_CRAFT (mode=5, Drag) ---

    private static List<InventoryActionData> simulateQuickCraft(int javaContainerId, short javaSlot, byte button, InventoryTracker tracker, ClientAuthInventoryModule.DragState dragState) {
        int stage = button & 3;
        int mode = button >> 2;

        switch (stage) {
            case 0: // Begin drag
                dragState.begin(mode);
                return Collections.emptyList();

            case 1: // Add slot
                dragState.addSlot(javaSlot);
                return Collections.emptyList();

            case 2: // End drag
                return finishQuickCraft(javaContainerId, tracker, dragState);

            default:
                dragState.reset();
                return null;
        }
    }

    private static List<InventoryActionData> finishQuickCraft(int javaContainerId, InventoryTracker tracker, ClientAuthInventoryModule.DragState dragState) {
        final int dragMode = dragState.getDragMode();
        final List<Short> dragSlots = new ArrayList<>(dragState.getDragSlots());
        dragState.reset();

        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        if (dragSlots.isEmpty() || (dragMode != 2 && cursorItem.isEmpty())) {
            return null;
        }

        final List<InventoryActionData> actions = new ArrayList<>();
        int totalDistributed = 0;

        switch (dragMode) {
            case 0: { // Left click — even distribution
                int amountPerSlot = cursorItem.amount() / dragSlots.size();
                if (amountPerSlot == 0) return null;

                for (short slot : dragSlots) {
                    final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, slot, tracker);
                    if (ref == null) continue;
                    final BedrockItem slotItem = ref.container().getItem(ref.slot());
                    if (!slotItem.isEmpty() && !canStack(slotItem, cursorItem)) continue;

                    int currentAmount = slotItem.isEmpty() ? 0 : slotItem.amount();
                    int addAmount = Math.min(amountPerSlot, MAX_STACK - currentAmount);
                    if (addAmount <= 0) continue;

                    BedrockItem newSlot = cursorItem.copy();
                    newSlot.setAmount(currentAmount + addAmount);
                    actions.add(slotAction(ref, slotItem, newSlot));
                    totalDistributed += addAmount;
                }
                break;
            }
            case 1: { // Right click — one per slot
                for (short slot : dragSlots) {
                    if (totalDistributed >= cursorItem.amount()) break;
                    final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, slot, tracker);
                    if (ref == null) continue;
                    final BedrockItem slotItem = ref.container().getItem(ref.slot());
                    if (!slotItem.isEmpty() && (!canStack(slotItem, cursorItem) || slotItem.amount() >= MAX_STACK)) continue;

                    int currentAmount = slotItem.isEmpty() ? 0 : slotItem.amount();
                    BedrockItem newSlot = cursorItem.copy();
                    newSlot.setAmount(currentAmount + 1);
                    actions.add(slotAction(ref, slotItem, newSlot));
                    totalDistributed += 1;
                }
                break;
            }
            case 2: { // Creative middle click — fill to max
                for (short slot : dragSlots) {
                    final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, slot, tracker);
                    if (ref == null) continue;
                    final BedrockItem slotItem = ref.container().getItem(ref.slot());
                    BedrockItem newSlot = cursorItem.copy();
                    newSlot.setAmount(MAX_STACK);
                    actions.add(slotAction(ref, slotItem, newSlot));
                }
                break;
            }
            default:
                return null;
        }

        if (actions.isEmpty()) return null;

        // Add cursor action
        BedrockItem newCursor;
        if (dragMode == 2) {
            newCursor = cursorItem; // Creative mode — cursor unchanged
        } else {
            int remainingAmount = cursorItem.amount() - totalDistributed;
            newCursor = remainingAmount > 0 ? cursorItem.copy() : BedrockItem.empty();
            if (remainingAmount > 0) newCursor.setAmount(remainingAmount);
        }
        actions.add(cursorAction(cursorItem, newCursor));

        return actions;
    }

    // --- PICKUP_ALL (mode=6, Double click) ---

    private static List<InventoryActionData> simulatePickupAll(int javaContainerId, short javaSlot, InventoryTracker tracker) {
        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        if (cursorItem.isEmpty()) return Collections.emptyList();

        int remaining = MAX_STACK - cursorItem.amount();
        if (remaining <= 0) return Collections.emptyList();

        final List<InventoryActionData> actions = new ArrayList<>();
        int collected = 0;

        // Determine scan range
        final int scanStart;
        final int scanEnd;
        if (javaContainerId == 0) {
            scanStart = 1; // Skip crafting output (slot 0)
            scanEnd = 45;
        } else {
            scanStart = 0;
            final Container currentContainer = tracker.getCurrentContainer();
            final int containerSize = currentContainer != null ? currentContainer.size() : 27;
            scanEnd = containerSize + 35;
        }

        // Two rounds: first collect partial stacks, then full stacks
        for (int round = 1; round <= 2; round++) {
            for (int scanSlot = scanStart; scanSlot <= scanEnd; scanSlot++) {
                if (collected >= remaining) break;
                final BedrockSlotRef ref = SlotMapper.resolve(javaContainerId, scanSlot, tracker);
                if (ref == null) continue;
                final BedrockItem slotItem = ref.container().getItem(ref.slot());
                if (slotItem.isEmpty() || !canStack(slotItem, cursorItem)) continue;

                if (round == 1 && slotItem.amount() >= MAX_STACK) continue;
                if (round == 2 && slotItem.amount() < MAX_STACK) continue;

                int take = Math.min(slotItem.amount(), remaining - collected);
                int newSlotAmount = slotItem.amount() - take;
                BedrockItem newSlot = newSlotAmount > 0 ? slotItem.copy() : BedrockItem.empty();
                if (newSlotAmount > 0) newSlot.setAmount(newSlotAmount);

                actions.add(slotAction(ref, slotItem, newSlot));
                collected += take;
            }
        }

        if (collected == 0) return Collections.emptyList();

        BedrockItem newCursor = cursorItem.copy();
        newCursor.setAmount(cursorItem.amount() + collected);
        actions.add(cursorAction(cursorItem, newCursor));

        return actions;
    }

    // --- Helper methods ---

    private static InventoryActionData slotAction(BedrockSlotRef ref, BedrockItem from, BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ref.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                ref.slot(), from.copy(), to.copy()
        );
    }

    private static InventoryActionData cursorAction(BedrockItem from, BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, from.copy(), to.copy()
        );
    }

    private static InventoryActionData worldDropAction(BedrockItem dropped) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), dropped.copy()
        );
    }

    private static boolean canStack(BedrockItem a, BedrockItem b) {
        return !a.isEmpty() && !b.isEmpty() && !a.isDifferent(b);
    }

}
