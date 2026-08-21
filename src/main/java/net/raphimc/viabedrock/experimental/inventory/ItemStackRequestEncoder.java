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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.experimental.inventory.SlotMapper.BedrockSlotRef;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the existing client-auth {@link InventoryActionData} simulation into a
 * Bedrock ITEM_STACK_REQUEST payload. NetEase 860 SAI servers reject legacy
 * InventoryTransaction UI clicks, so the same predicted slot deltas have to
 * travel as Take/Place/Swap/Drop actions instead.
 */
public final class ItemStackRequestEncoder {

    private ItemStackRequestEncoder() {
    }

    public static EncodedRequest encode(final List<InventoryActionData> actions, final InventoryTracker tracker) {
        return encode(actions, tracker, emulateNetEase(), encodeProtocol());
    }

    public static EncodedRequest encode(final List<InventoryActionData> actions, final InventoryTracker tracker,
                                        final boolean emulateNetEase, final int protocol) {
        if (actions == null || actions.isEmpty()) {
            return EncodedRequest.empty();
        }
        final List<Action> stackActions = new ArrayList<>();
        final List<InventoryActionData> remaining = new ArrayList<>(actions);
        final boolean crafting = prependCraftRecipe(remaining, tracker, stackActions);
        while (!remaining.isEmpty()) {
            final Action converted = takeNext(remaining, tracker, crafting);
            if (converted == null) {
                return EncodedRequest.notSupported();
            }
            stackActions.add(converted);
        }
        if (stackActions.isEmpty()) {
            return EncodedRequest.empty();
        }
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            BedrockTypes.VAR_INT.write(buffer, nextRequestId(tracker));
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, stackActions.size());
            for (final Action action : stackActions) {
                writeAction(buffer, action, emulateNetEase, protocol);
            }
            ItemStackRequestLayout.writeRequestTrailer(buffer, emulateNetEase, protocol);
            final byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return EncodedRequest.of(payload);
        } finally {
            buffer.release();
        }
    }

    private static void writeAction(final ByteBuf buffer, final Action action,
                                    final boolean emulateNetEase, final int protocol) {
        switch (action.type) {
            case Take, Place -> ItemStackRequestLayout.writeTransfer(
                    buffer, action.type, action.count, action.source, action.destination, emulateNetEase, protocol);
            case Swap -> ItemStackRequestLayout.writeSwap(buffer, action.source, action.destination, emulateNetEase, protocol);
            case Drop -> ItemStackRequestLayout.writeDrop(buffer, action.count, action.source, false, emulateNetEase, protocol);
            case Consume -> ItemStackRequestLayout.writeConsume(buffer, action.count, action.source, emulateNetEase, protocol);
            case CraftRecipe -> ItemStackRequestLayout.writeCraftRecipe(buffer, action.count, 1, emulateNetEase, protocol);
            default -> throw new IllegalStateException("Unsupported item-stack action: " + action.type);
        }
    }

    private static Action takeNext(final List<InventoryActionData> remaining, final InventoryTracker tracker, final boolean crafting) {
        for (final InventoryActionData action : remaining) {
            if (isCreative(action) || (!isContainer(action) && !isWorldDrop(action) && !isCraftTodo(action))) {
                return null;
            }
        }

        if (crafting) {
            final Action consume = takeNextGridConsume(remaining, tracker);
            if (consume != null) {
                return consume;
            }
            final Action craftedOutput = takeNextCraftedOutput(remaining, tracker);
            if (craftedOutput != null) {
                return craftedOutput;
            }
        }

        for (int i = 0; i < remaining.size(); i++) {
            final InventoryActionData first = remaining.get(i);
            if (!isContainer(first)) {
                continue;
            }
            for (int j = i + 1; j < remaining.size(); j++) {
                final InventoryActionData second = remaining.get(j);
                if (!isContainer(second)) {
                    continue;
                }
                final Action swap = trySwap(first, second, tracker);
                if (swap != null) {
                    remaining.remove(j);
                    remaining.remove(i);
                    return swap;
                }
            }
        }

        final Action transfer = takeNextPartialTransfer(remaining, tracker);
        if (transfer != null) {
            return transfer;
        }
        return takeNextDrop(remaining, tracker);
    }

    private static Action takeNextGridConsume(final List<InventoryActionData> remaining, final InventoryTracker tracker) {
        for (int i = 0; i < remaining.size(); i++) {
            final InventoryActionData action = remaining.get(i);
            if (!isCraftingGridDecrease(action) || movedCount(action) <= 0) {
                continue;
            }
            remaining.remove(i);
            final ItemStackRequestLayout.SlotInfo slot = slotInfo(action, tracker);
            if (slot == null) {
                return null;
            }
            return Action.consume(movedCount(action), slot);
        }
        return null;
    }

    private static Action takeNextCraftedOutput(final List<InventoryActionData> remaining, final InventoryTracker tracker) {
        for (int i = 0; i < remaining.size(); i++) {
            final InventoryActionData destination = remaining.get(i);
            if (!isContainer(destination) || !isDestinationIncrease(destination) || movedCount(destination) <= 0) {
                continue;
            }
            remaining.remove(i);
            final ItemStackRequestLayout.SlotInfo destinationSlot = slotInfo(destination, tracker);
            if (destinationSlot == null) {
                return null;
            }
            final ItemStackRequestLayout.SlotInfo source = new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CreatedOutputContainer, 50, 0);
            return Action.transfer(ItemStackRequestActionType.Take, movedCount(destination), source, destinationSlot);
        }
        return null;
    }

    private static boolean prependCraftRecipe(final List<InventoryActionData> remaining, final InventoryTracker tracker,
                                              final List<Action> stackActions) {
        Integer recipeNetworkId = null;
        for (int i = 0; i < remaining.size(); i++) {
            final InventoryActionData action = remaining.get(i);
            if (isCraftResultTodo(action)) {
                recipeNetworkId = recipeNetworkId(tracker, action.fromItem().isEmpty() ? action.toItem() : action.fromItem());
                remaining.remove(i);
                break;
            }
        }
        if (recipeNetworkId == null) {
            return false;
        }
        for (int i = 0; i < remaining.size(); ) {
            if (isCraftIngredientTodo(remaining.get(i))) {
                remaining.remove(i);
            } else {
                i++;
            }
        }
        stackActions.add(Action.craft(recipeNetworkId));
        return true;
    }

    private static Integer recipeNetworkId(final InventoryTracker tracker, final BedrockItem output) {
        if (tracker == null || tracker.user() == null) {
            return null;
        }
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        if (registry == null) {
            return null;
        }
        BedrockRecipe recipe = registry.matchRecipe(CraftingSimulator.getGridItems(false, tracker), false);
        if (recipe == null) {
            recipe = registry.matchRecipe(CraftingSimulator.getGridItems(true, tracker), true);
        }
        if (recipe == null || output == null || output.isEmpty() || recipe.primaryOutput().isDifferent(output)) {
            return null;
        }
        return recipe.networkId();
    }

    private static Action takeNextDrop(final List<InventoryActionData> remaining, final InventoryTracker tracker) {
        for (int i = 0; i < remaining.size(); i++) {
            final InventoryActionData drop = remaining.get(i);
            if (!isWorldDrop(drop)) {
                continue;
            }
            final int dropped = movedCount(drop);
            remaining.remove(i);
            final InventoryActionData source = findMatchingSource(remaining, destinationItem(drop), dropped);
            if (source == null) {
                return null;
            }
            remaining.remove(source);
            final ItemStackRequestLayout.SlotInfo slot = slotInfo(source, tracker);
            if (slot == null) {
                return null;
            }
            return Action.drop(dropped, slot);
        }
        return null;
    }

    private static Action takeNextPartialTransfer(final List<InventoryActionData> remaining, final InventoryTracker tracker) {
        for (int sourceIndex = 0; sourceIndex < remaining.size(); sourceIndex++) {
            final InventoryActionData source = remaining.get(sourceIndex);
            if (!isContainer(source) || !isSourceDecrease(source) || movedCount(source) <= 0) {
                continue;
            }
            for (int destIndex = 0; destIndex < remaining.size(); destIndex++) {
                if (destIndex == sourceIndex) {
                    continue;
                }
                final InventoryActionData destination = remaining.get(destIndex);
                if (!isContainer(destination) || !isDestinationIncrease(destination) || sameSlot(source, destination)) {
                    continue;
                }
                if (!sameItemFamily(sourceItem(source), destinationItem(destination))) {
                    continue;
                }
                final int count = Math.min(movedCount(source), movedCount(destination));
                if (count <= 0) {
                    continue;
                }
                final ItemStackRequestLayout.SlotInfo sourceSlot = slotInfo(source, tracker);
                final ItemStackRequestLayout.SlotInfo destinationSlot = slotInfo(destination, tracker);
                if (sourceSlot == null || destinationSlot == null) {
                    return null;
                }
                consumeMovedCount(remaining, sourceIndex, count, true);
                consumeMovedCount(remaining, indexOfSlot(remaining, destination), count, false);
                return Action.transfer(transferType(source), count, sourceSlot, destinationSlot);
            }
        }
        return null;
    }

    private static int indexOfSlot(final List<InventoryActionData> remaining, final InventoryActionData expected) {
        for (int i = 0; i < remaining.size(); i++) {
            if (sameSlot(remaining.get(i), expected)) {
                return i;
            }
        }
        return -1;
    }

    private static void consumeMovedCount(final List<InventoryActionData> remaining, final int index, final int count, final boolean source) {
        if (index < 0) {
            return;
        }
        final InventoryActionData action = remaining.get(index);
        if (movedCount(action) <= count) {
            remaining.remove(index);
            return;
        }
        remaining.set(index, source ? shrinkSource(action, count) : shrinkDestination(action, count));
    }

    private static InventoryActionData shrinkSource(final InventoryActionData action, final int count) {
        final BedrockItem from = action.fromItem().copy();
        from.setAmount(from.amount() - count);
        if (from.amount() <= 0) {
            return new InventoryActionData(action.source(), action.slot(), action.fromItem().copy(), action.toItem().copy());
        }
        return new InventoryActionData(action.source(), action.slot(), from, action.toItem().copy());
    }

    private static InventoryActionData shrinkDestination(final InventoryActionData action, final int count) {
        final BedrockItem from = action.fromItem().isEmpty() ? BedrockItem.empty() : action.fromItem().copy();
        final BedrockItem to = action.toItem().copy();
        to.setAmount(to.amount() - count);
        if (to.amount() <= 0) {
            return new InventoryActionData(action.source(), action.slot(), from, BedrockItem.empty());
        }
        return new InventoryActionData(action.source(), action.slot(), from, to);
    }

    private static ItemStackRequestActionType transferType(final InventoryActionData source) {
        return source.source().containerId() == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue() && source.slot() == 0
                ? ItemStackRequestActionType.Place
                : ItemStackRequestActionType.Take;
    }

    private static Action trySwap(final InventoryActionData a, final InventoryActionData b, final InventoryTracker tracker) {
        if (sameSlot(a, b)) {
            return null;
        }
        if (a.fromItem().isEmpty() || b.fromItem().isEmpty()) {
            return null;
        }
        if (!sameStack(a.fromItem(), b.toItem()) || !sameStack(b.fromItem(), a.toItem())) {
            return null;
        }
        if (a.fromItem().amount() != b.toItem().amount() || b.fromItem().amount() != a.toItem().amount()) {
            return null;
        }
        final ItemStackRequestLayout.SlotInfo source = slotInfo(a, tracker);
        final ItemStackRequestLayout.SlotInfo destination = slotInfo(b, tracker);
        if (source == null || destination == null) {
            return null;
        }
        return Action.swap(source, destination);
    }

    private static InventoryActionData findMatchingSource(final List<InventoryActionData> remaining, final BedrockItem dropped, final int count) {
        for (final InventoryActionData action : remaining) {
            if (isContainer(action) && isSourceDecrease(action) && sameItemFamily(sourceItem(action), dropped)
                    && movedCount(action) == count) {
                return action;
            }
        }
        return null;
    }

    private static ItemStackRequestLayout.SlotInfo slotInfo(final InventoryActionData action, final InventoryTracker tracker) {
        final InventorySource source = action.source();
        final Container container = resolveContainer(source.containerId(), tracker);
        ItemStackRequestLayout.SlotInfo info = ItemStackSlotMapper.fromContainer(container, source.containerId(), action.slot());
        if (info == null && container != null) {
            info = ItemStackSlotMapper.fromRef(new BedrockSlotRef(source.containerId(), action.slot(), container));
        }
        if (info == null) {
            return null;
        }
        return withNetId(info, action.fromItem());
    }

    private static ItemStackRequestLayout.SlotInfo withNetId(final ItemStackRequestLayout.SlotInfo info, final BedrockItem item) {
        final int netId = item != null && item.netId() != null ? item.netId() : 0;
        return new ItemStackRequestLayout.SlotInfo(info.container(), info.slot(), netId, info.dynamicId());
    }

    private static Container resolveContainer(final int containerId, final InventoryTracker tracker) {
        if (tracker == null) {
            return null;
        }
        if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) return tracker.getInventoryContainer();
        if (containerId == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue()) return tracker.getHudContainer();
        if (containerId == ContainerID.CONTAINER_ID_ARMOR.getValue()) return tracker.getArmorContainer();
        if (containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()) return tracker.getOffhandContainer();
        return tracker.getContainerServerbound((byte) containerId);
    }

    private static boolean isContainer(final InventoryActionData action) {
        return action.source().type() == InventorySourceType.ContainerInventory;
    }

    private static boolean isWorldDrop(final InventoryActionData action) {
        return action.source().type() == InventorySourceType.WorldInteraction;
    }

    private static boolean isCreative(final InventoryActionData action) {
        return action.source().type() == InventorySourceType.CreativeInventory;
    }

    private static boolean isCraftTodo(final InventoryActionData action) {
        return action.source().type() == InventorySourceType.NonImplementedFeatureTODO;
    }

    private static boolean isCraftIngredientTodo(final InventoryActionData action) {
        return isCraftTodo(action) && action.source().containerId() == -5;
    }

    private static boolean isCraftResultTodo(final InventoryActionData action) {
        return isCraftTodo(action) && action.source().containerId() == -4;
    }

    private static boolean isCraftingGridDecrease(final InventoryActionData action) {
        return isContainer(action)
                && action.source().containerId() == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue()
                && action.slot() >= 28 && action.slot() <= 40;
    }

    private static boolean sameSlot(final InventoryActionData a, final InventoryActionData b) {
        return a.source().containerId() == b.source().containerId() && a.slot() == b.slot();
    }

    private static boolean isSourceDecrease(final InventoryActionData action) {
        if (action.fromItem().isEmpty()) {
            return false;
        }
        if (action.toItem().isEmpty()) {
            return true;
        }
        return sameItemFamily(action.fromItem(), action.toItem()) && action.toItem().amount() < action.fromItem().amount();
    }

    private static boolean isDestinationIncrease(final InventoryActionData action) {
        if (action.toItem().isEmpty()) {
            return false;
        }
        if (action.fromItem().isEmpty()) {
            return true;
        }
        return sameItemFamily(action.fromItem(), action.toItem()) && action.toItem().amount() > action.fromItem().amount();
    }

    private static int movedCount(final InventoryActionData action) {
        if (action.toItem().isEmpty()) {
            return action.fromItem().amount();
        }
        if (action.fromItem().isEmpty()) {
            return action.toItem().amount();
        }
        return Math.abs(action.toItem().amount() - action.fromItem().amount());
    }

    private static BedrockItem sourceItem(final InventoryActionData action) {
        return action.fromItem().isEmpty() ? action.toItem() : action.fromItem();
    }

    private static BedrockItem destinationItem(final InventoryActionData action) {
        return action.toItem().isEmpty() ? action.fromItem() : action.toItem();
    }

    private static boolean sameStack(final BedrockItem a, final BedrockItem b) {
        if (a.isEmpty() != b.isEmpty()) {
            return false;
        }
        if (a.isEmpty()) {
            return true;
        }
        return !a.isDifferent(b) && a.amount() == b.amount();
    }

    private static boolean sameItemFamily(final BedrockItem a, final BedrockItem b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return !a.isDifferent(b);
    }

    private static int nextRequestId(final InventoryTracker tracker) {
        return tracker != null ? tracker.nextItemStackRequestId() : -1;
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig() != null ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : 0;
    }

    private static int encodeProtocol() {
        if (emulateNetEase() && netEaseProtocol() > 0) {
            return netEaseProtocol();
        }
        return ProtocolConstants.BEDROCK_PROTOCOL_VERSION;
    }

    private record Action(ItemStackRequestActionType type, int count,
                          ItemStackRequestLayout.SlotInfo source, ItemStackRequestLayout.SlotInfo destination) {
        static Action transfer(final ItemStackRequestActionType type, final int count,
                               final ItemStackRequestLayout.SlotInfo source, final ItemStackRequestLayout.SlotInfo destination) {
            return new Action(type, count, source, destination);
        }

        static Action swap(final ItemStackRequestLayout.SlotInfo source, final ItemStackRequestLayout.SlotInfo destination) {
            return new Action(ItemStackRequestActionType.Swap, 0, source, destination);
        }

        static Action drop(final int count, final ItemStackRequestLayout.SlotInfo source) {
            return new Action(ItemStackRequestActionType.Drop, count, source, null);
        }

        static Action consume(final int count, final ItemStackRequestLayout.SlotInfo source) {
            return new Action(ItemStackRequestActionType.Consume, count, source, null);
        }

        static Action craft(final int recipeNetworkId) {
            return new Action(ItemStackRequestActionType.CraftRecipe, recipeNetworkId, null, null);
        }
    }

    public record EncodedRequest(byte[] payload, boolean unsupported) {
        public static EncodedRequest empty() {
            return new EncodedRequest(new byte[0], false);
        }

        public static EncodedRequest notSupported() {
            return new EncodedRequest(new byte[0], true);
        }

        public static EncodedRequest of(final byte[] payload) {
            return new EncodedRequest(payload, false);
        }

        public boolean isEmpty() {
            return !unsupported && payload.length == 0;
        }
    }
}

