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
import com.viaversion.nbt.tag.IntArrayTag;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.data.Equippable;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.experimental.inventory.SlotMapper.BedrockSlotRef;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ClickSimulator {

    private static final int MAX_STACK = 64;
    private static final int MAX_JAVA_STACK_SIZE = 99;

    /**
     * Simulates a Java CONTAINER_CLICK and returns the list of Bedrock InventoryActionData.
     * Returns null if the operation is unsupported (caller should rollback).
     * Returns empty list if there's nothing to do (no packet sent, no rollback).
     */
    public static List<InventoryActionData> simulate(
            final int javaContainerId,
            final short javaSlot,
            final byte button,
            final ContainerInput action,
            final InventoryTracker tracker,
            final ClientAuthInventoryModule.DragState dragState,
            final Map<Short, HashedItem> changedSlots,
            final HashedItem carriedItem) {

        // Intercept crafting output slot clicks
        if (javaSlot == 0) {
            final boolean is3x3 = javaContainerId != 0 && tracker.getCurrentContainer() instanceof CraftingTableContainer;
            final boolean is2x2 = javaContainerId == 0;
            if (is3x3 || is2x2) {
                return switch (action) {
                    case PICKUP -> CraftingSimulator.simulateCraftPickup(is3x3, tracker);
                    case QUICK_MOVE -> CraftingSimulator.simulateCraftQuickMove(is3x3, tracker);
                    default -> null;
                };
            }
        }

        return switch (action) {
            case PICKUP -> simulatePickup(javaContainerId, javaSlot, button, tracker);
            case QUICK_MOVE -> javaContainerId == 0 && javaSlot >= 1 && javaSlot <= 45
                    ? simulatePredictedPlayerQuickMove(javaSlot, button, tracker, changedSlots, carriedItem)
                    : simulateQuickMove(javaContainerId, javaSlot, tracker);
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

    // Preserve Java's component-driven equipment rules while accepting only count-conserving
    // moves of the Bedrock item already present in the authoritative mirror.
    private static List<InventoryActionData> simulatePredictedPlayerQuickMove(
            final short javaSlot,
            final byte button,
            final InventoryTracker tracker,
            final Map<Short, HashedItem> changedSlots,
            final HashedItem carriedItem) {
        if (button < 0 || button > 1 || changedSlots == null || carriedItem == null) {
            return null;
        }

        final ItemRewriter itemRewriter = tracker.user().get(ItemRewriter.class);
        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        final Item javaCarriedItem = itemRewriter.javaItem(cursorItem.copy());
        if (!samePredictedStack(javaCarriedItem, carriedItem)) return null;

        final BedrockSlotRef sourceRef = SlotMapper.resolvePlayerInventory(javaSlot, tracker);
        if (sourceRef == null) return null;

        final BedrockItem sourceItem = sourceRef.container().getItem(sourceRef.slot());
        if (sourceItem.isEmpty()) {
            return changedSlots.isEmpty() ? Collections.emptyList() : null;
        }

        int relevantChanges = changedSlots.size();
        if (javaSlot <= 4 && changedSlots.containsKey((short) 0)) {
            relevantChanges--; // Crafting output is a local preview, not an inventory action.
        }
        if (relevantChanges == 0) {
            return Collections.emptyList();
        }

        int equipmentTarget = -1;
        for (final short changedSlot : changedSlots.keySet()) {
            if (changedSlot != javaSlot && ((changedSlot >= 5 && changedSlot <= 8) || changedSlot == 45)) {
                if (equipmentTarget != -1) return null;
                equipmentTarget = changedSlot;
            }
        }
        final Item javaSourceItem = itemRewriter.javaItem(sourceItem.copy());
        final int expectedEquipmentSlot = trustedEquipmentSlot(javaSourceItem);
        final int maxStackSize = javaMaxStackSize(javaSourceItem);
        if (equipmentTarget == -1 && expectedEquipmentSlot == -1) {
            return simulateQuickMove(0, javaSlot, tracker);
        }

        if (javaSourceItem.isEmpty()) return null;
        if (equipmentTarget != -1 && (!isPredictedEquipmentTarget(javaSlot, equipmentTarget)
                || equipmentTarget != expectedEquipmentSlot)) {
            return null;
        }

        final HashedItem predictedSource = changedSlots.get(javaSlot);
        if (predictedSource == null || predictedSource.amount() < 0) return null;

        final int sourceAmountAfter;
        if (predictedSource.isEmpty()) {
            sourceAmountAfter = 0;
        } else {
            if (!samePredictedItem(javaSourceItem, predictedSource)
                    || predictedSource.amount() > MAX_JAVA_STACK_SIZE
                    || predictedSource.amount() >= sourceItem.amount()) return null;
            sourceAmountAfter = predictedSource.amount();
        }

        final int removedAmount = sourceItem.amount() - sourceAmountAfter;
        if (removedAmount <= 0) return null;

        final List<InventoryActionData> targetActions = new ArrayList<>();
        final List<Integer> orderedTargetSlots = new ArrayList<>();
        if (equipmentTarget != -1) orderedTargetSlots.add(equipmentTarget);
        final List<Integer> fallbackTargetSlots = getQuickMoveTargetSlots(0, javaSlot, tracker);
        for (final int targetJavaSlot : fallbackTargetSlots) {
            final BedrockSlotRef targetRef = SlotMapper.resolvePlayerInventory(targetJavaSlot, tracker);
            if (changedSlots.containsKey((short) targetJavaSlot)
                    && targetRef != null && !targetRef.container().getItem(targetRef.slot()).isEmpty()) {
                orderedTargetSlots.add(targetJavaSlot);
            }
        }
        for (final int targetJavaSlot : fallbackTargetSlots) {
            final BedrockSlotRef targetRef = SlotMapper.resolvePlayerInventory(targetJavaSlot, tracker);
            if (changedSlots.containsKey((short) targetJavaSlot)
                    && targetRef != null && targetRef.container().getItem(targetRef.slot()).isEmpty()) {
                orderedTargetSlots.add(targetJavaSlot);
            }
        }
        int addedAmount = 0;
        int processedTargets = 0;
        for (final int targetJavaSlot : orderedTargetSlots) {
            final HashedItem predictedTarget = changedSlots.get((short) targetJavaSlot);
            if (predictedTarget == null) continue;
            if (!isAllowedPredictedQuickMoveTarget(javaSlot, targetJavaSlot)) return null;

            final BedrockSlotRef targetRef = SlotMapper.resolvePlayerInventory(targetJavaSlot, tracker);
            if (targetRef == null) return null;

            final BedrockItem targetItem = targetRef.container().getItem(targetRef.slot());
            if (predictedTarget.isEmpty() || predictedTarget.amount() <= 0
                    || predictedTarget.amount() > MAX_JAVA_STACK_SIZE || predictedTarget.amount() > maxStackSize
                    || !samePredictedItem(javaSourceItem, predictedTarget)) return null;

            final int targetAmountBefore = targetItem.isEmpty() ? 0 : targetItem.amount();
            if (!targetItem.isEmpty() && !canStackPredicted(targetItem, sourceItem)) return null;
            if (predictedTarget.amount() <= targetAmountBefore) return null;

            if (isPredictedEquipmentTarget(javaSlot, targetJavaSlot)) {
                if (targetJavaSlot != equipmentTarget || !targetItem.isEmpty()) return null;
                if (targetJavaSlot >= 5 && targetJavaSlot <= 8 && predictedTarget.amount() != 1) return null;
            }

            final int addedToTarget = predictedTarget.amount() - targetAmountBefore;
            if (addedToTarget > removedAmount - addedAmount) return null;

            final BedrockItem newTarget = targetItem.isEmpty() ? sourceItem.copy() : targetItem.copy();
            newTarget.setAmount(predictedTarget.amount());
            targetActions.add(slotAction(targetRef, targetItem, newTarget));
            addedAmount += addedToTarget;
            processedTargets++;
        }

        if (targetActions.isEmpty() || processedTargets != relevantChanges - 1
                || addedAmount != removedAmount) return null;

        final BedrockItem newSource = sourceAmountAfter == 0 ? BedrockItem.empty() : sourceItem.copy();
        if (sourceAmountAfter > 0) newSource.setAmount(sourceAmountAfter);

        final List<InventoryActionData> actions = new ArrayList<>(targetActions.size() + 1);
        actions.add(slotAction(sourceRef, sourceItem, newSource));
        actions.addAll(targetActions);
        return actions;
    }

    private static boolean isAllowedPredictedQuickMoveTarget(final int sourceSlot, final int targetSlot) {
        if (sourceSlot >= 1 && sourceSlot <= 8) {
            return targetSlot >= 9 && targetSlot <= 44;
        } else if (sourceSlot >= 9 && sourceSlot <= 35) {
            return (targetSlot >= 36 && targetSlot <= 44) || isPredictedEquipmentTarget(sourceSlot, targetSlot);
        } else if (sourceSlot >= 36 && sourceSlot <= 44) {
            return (targetSlot >= 9 && targetSlot <= 35) || isPredictedEquipmentTarget(sourceSlot, targetSlot);
        } else if (sourceSlot == 45) {
            return (targetSlot >= 9 && targetSlot <= 44) || isPredictedEquipmentTarget(sourceSlot, targetSlot);
        }
        return false;
    }

    private static boolean isPredictedEquipmentTarget(final int sourceSlot, final int targetSlot) {
        if (targetSlot >= 5 && targetSlot <= 8) {
            return sourceSlot >= 9 && sourceSlot <= 45;
        }
        return targetSlot == 45 && sourceSlot >= 9 && sourceSlot <= 44;
    }

    static boolean samePredictedStack(final Item authoritativeItem, final HashedItem predictedItem) {
        if (authoritativeItem.isEmpty()) {
            return predictedItem.isEmpty();
        }
        return !predictedItem.isEmpty()
                && authoritativeItem.amount() == predictedItem.amount()
                && samePredictedItem(authoritativeItem, predictedItem);
    }

    // Component hashes can change across registry and ViaVersion transformations. They are not trusted
    // transaction data: every Bedrock item is copied from the authoritative mirror below.
    static boolean samePredictedItem(final Item authoritativeItem, final HashedItem predictedItem) {
        return !authoritativeItem.isEmpty()
                && !predictedItem.isEmpty()
                && authoritativeItem.identifier() == predictedItem.identifier();
    }

    private static boolean canStackPredicted(final BedrockItem first, final BedrockItem second) {
        return canStack(first, second)
                && Arrays.equals(first.canPlace(), second.canPlace())
                && Arrays.equals(first.canBreak(), second.canBreak())
                && first.blockingTicks() == second.blockingTicks();
    }

    private static int trustedEquipmentSlot(final Item javaItem) {
        if (javaItem.dataContainer().hasEmpty(StructuredDataKey.EQUIPPABLE1_21_6)) return -1;

        final Equippable equippable = javaItem.dataContainer().get(StructuredDataKey.EQUIPPABLE1_21_6);
        if (equippable != null) {
            return switch (equippable.equipmentSlot()) {
                case 1 -> 8; // feet
                case 2 -> 7; // legs
                case 3 -> 6; // chest
                case 4 -> 5; // head
                case 5 -> 45; // offhand
                default -> -1;
            };
        }

        final int identifier = javaItem.identifier();
        if (isInJavaItemTag(identifier, "minecraft:head_armor")) return 5;
        if (isInJavaItemTag(identifier, "minecraft:chest_armor")) return 6;
        if (isInJavaItemTag(identifier, "minecraft:leg_armor")) return 7;
        if (isInJavaItemTag(identifier, "minecraft:foot_armor")) return 8;

        final String identifierName = BedrockProtocol.MAPPINGS.getJavaItems().inverse().get(identifier);
        if (identifierName == null) return -1;
        return switch (identifierName) {
            case "minecraft:carved_pumpkin", "minecraft:creeper_head", "minecraft:dragon_head",
                 "minecraft:piglin_head", "minecraft:player_head", "minecraft:skeleton_skull",
                 "minecraft:wither_skeleton_skull", "minecraft:zombie_head" -> 5;
            case "minecraft:elytra" -> 6;
            case "minecraft:shield" -> 45;
            default -> -1;
        };
    }

    private static int javaMaxStackSize(final Item javaItem) {
        final Integer maxStackSize = javaItem.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE);
        if (maxStackSize != null) {
            return maxStackSize >= 1 && maxStackSize <= MAX_JAVA_STACK_SIZE ? maxStackSize : 1;
        }
        if (javaItem.dataContainer().hasEmpty(StructuredDataKey.MAX_STACK_SIZE)) return 1;
        if (javaItem.identifier() < 0 || javaItem.identifier() >= BedrockProtocol.MAPPINGS.getJavaItems().size()) return 1;
        return BedrockProtocol.MAPPINGS.getJavaItemMaxStackSize(javaItem.identifier());
    }

    private static boolean isInJavaItemTag(final int identifier, final String tagName) {
        if (!(BedrockProtocol.MAPPINGS.getJavaTags().get("minecraft:item") instanceof CompoundTag itemTags)
                || !(itemTags.get(tagName) instanceof IntArrayTag tag)) {
            return false;
        }
        for (final int taggedIdentifier : tag.getValue()) {
            if (taggedIdentifier == identifier) return true;
        }
        return false;
    }

    private static List<InventoryActionData> simulateQuickMove(int javaContainerId, short javaSlot, InventoryTracker tracker) {
        final BedrockSlotRef sourceRef = SlotMapper.resolve(javaContainerId, javaSlot, tracker);
        if (sourceRef == null) return null;

        final BedrockItem sourceItem = sourceRef.container().getItem(sourceRef.slot());
        if (sourceItem.isEmpty()) return Collections.emptyList();

        // Ordered list of target Java menu slots, in the exact order Java's ScreenHandler#insertItem visits them
        final List<Integer> targetSlots = getQuickMoveTargetSlots(javaContainerId, javaSlot, tracker);
        final int maxStackSize = resolveQuickMoveMaxStackSize(sourceItem, tracker);
        if (maxStackSize <= 0) return null;

        int remaining = sourceItem.amount();
        final List<InventoryActionData> actions = new ArrayList<>();

        // Round 1: Fill existing stacks of same type
        for (int targetJavaSlot : targetSlots) {
            if (remaining <= 0) break;
            final BedrockSlotRef targetRef = SlotMapper.resolve(javaContainerId, targetJavaSlot, tracker);
            if (targetRef == null) continue;
            final BedrockItem targetItem = targetRef.container().getItem(targetRef.slot());
            if (targetItem.isEmpty() || !canStackPredicted(targetItem, sourceItem)) continue;
            if (targetItem.amount() >= maxStackSize) continue;

            int addAmount = Math.min(remaining, maxStackSize - targetItem.amount());
            BedrockItem newTarget = targetItem.copy();
            newTarget.setAmount(targetItem.amount() + addAmount);
            actions.add(slotAction(targetRef, targetItem, newTarget));
            remaining -= addAmount;
        }

        // Round 2: Fill empty slots
        for (int targetJavaSlot : targetSlots) {
            if (remaining <= 0) break;
            final BedrockSlotRef targetRef = SlotMapper.resolve(javaContainerId, targetJavaSlot, tracker);
            if (targetRef == null) continue;
            final BedrockItem targetItem = targetRef.container().getItem(targetRef.slot());
            if (!targetItem.isEmpty()) continue;

            int addAmount = Math.min(remaining, maxStackSize);
            BedrockItem newTarget = sourceItem.copy();
            newTarget.setAmount(addAmount);
            actions.add(slotAction(targetRef, targetItem, newTarget));
            remaining -= addAmount;
            break;
        }

        if (actions.isEmpty()) return Collections.emptyList();

        // Source slot action: item → remaining or empty
        BedrockItem newSource = remaining > 0 ? sourceItem.copy() : BedrockItem.empty();
        if (remaining > 0) newSource.setAmount(remaining);
        actions.add(0, slotAction(sourceRef, sourceItem, newSource));

        return actions;
    }

    private static int resolveQuickMoveMaxStackSize(final BedrockItem sourceItem, final InventoryTracker tracker) {
        try {
            final Item javaItem = tracker.user().get(ItemRewriter.class).javaItem(sourceItem.copy());
            return javaMaxStackSize(javaItem);
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    /**
     * Returns the target Java menu slots for a shift-click, in the exact visiting order of the
     * corresponding vanilla {@code ScreenHandler#quickMove}/{@code insertItem} call. Java replicates
     * its own click locally, so matching this order avoids the client prediction being corrected.
     * Slot layout (matches {@link SlotMapper}): container {@code [0,N)}, main inventory {@code [N,N+27)},
     * hotbar {@code [N+27,N+36)}.
     */
    private static List<Integer> getQuickMoveTargetSlots(int javaContainerId, int javaSlot, InventoryTracker tracker) {
        if (javaContainerId != 0) {
            final Container currentContainer = tracker.getCurrentContainer();

            if (currentContainer instanceof CraftingTableContainer) {
                // Crafting table: slot 0=output, 1-9=grid, 10-36=main inv, 37-45=hotbar
                if (javaSlot >= 1 && javaSlot <= 9) {
                    // Grid → main inventory then hotbar (insertItem(10, 46, false))
                    return ascending(10, 45);
                } else if (javaSlot >= 37) {
                    // Hotbar → main inventory
                    return ascending(10, 36);
                } else if (javaSlot >= 10) {
                    // Main inventory → hotbar
                    return ascending(37, 45);
                }
                return Collections.emptyList();
            }

            // Generic container window
            final int containerSize = currentContainer != null ? currentContainer.size() : 27;

            if (javaSlot < containerSize) {
                // Source is in container → player inventory, insertItem(N, N+36, reverse=true):
                // highest menu slot first, i.e. hotbar (slot 8→0) then main inventory (slot 35→9)
                return descending(containerSize, containerSize + 35);
            } else {
                // Source is in player inventory → container only, insertItem(0, N, false).
                // Vanilla has no player-area fallback: if the container is full the item stays put.
                return ascending(0, containerSize - 1);
            }
        } else {
            // Player Inventory Window
            if (javaSlot >= 9 && javaSlot <= 35) {
                // Main inventory → hotbar (insertItem(36, 45, false))
                return ascending(36, 44);
            } else if (javaSlot >= 36 && javaSlot <= 44) {
                // Hotbar → main inventory (insertItem(9, 36, false))
                return ascending(9, 35);
            } else if (javaSlot >= 5 && javaSlot <= 8) {
                // Armor → main inventory then hotbar (insertItem(9, 45, false))
                return ascending(9, 44);
            } else if (javaSlot == 45) {
                // Offhand → main inventory then hotbar (insertItem(9, 45, false))
                return ascending(9, 44);
            } else if (javaSlot >= 1 && javaSlot <= 4) {
                // Crafting input → main inventory then hotbar (insertItem(9, 45, false))
                return ascending(9, 44);
            }
            return Collections.emptyList();
        }
    }

    /** Inclusive slot range [from, to] in ascending order (vanilla insertItem with reverse=false). */
    private static List<Integer> ascending(int from, int to) {
        final List<Integer> slots = new ArrayList<>(Math.max(0, to - from + 1));
        for (int slot = from; slot <= to; slot++) {
            slots.add(slot);
        }
        return slots;
    }

    /** Inclusive slot range [from, to] in descending order (vanilla insertItem with reverse=true). */
    private static List<Integer> descending(int from, int to) {
        final List<Integer> slots = new ArrayList<>(Math.max(0, to - from + 1));
        for (int slot = to; slot >= from; slot--) {
            slots.add(slot);
        }
        return slots;
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
