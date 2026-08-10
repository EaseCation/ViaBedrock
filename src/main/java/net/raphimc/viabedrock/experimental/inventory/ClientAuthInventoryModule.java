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

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.util.Limit;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ComplexInventoryTransaction_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public class ClientAuthInventoryModule implements FeatureModule {

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new DragState(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        registerContainerClickHandler(protocol);
        // Java expects the crafting output preview to be pushed by the server, but Bedrock computes it
        // client-side and never sends it. Recompute it locally whenever the (server-authoritative) grid
        // contents change, so the Java output slot reflects the matched recipe's result.
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.INVENTORY_SLOT, wrapper -> updateCraftingOutputPreview(wrapper.user()));
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.INVENTORY_CONTENT, wrapper -> updateCraftingOutputPreview(wrapper.user()));
        // Client-authoritative servers don't echo a clientbound CONTAINER_CLOSE for client-initiated
        // closes, so pendingCloseContainer would never clear and the container could not be reopened
        // (until the player walked away and the server force-closed it). Clear it right after the close
        // is forwarded to the server.
        ProtocolUtil.appendServerbound(protocol, ServerboundPackets26_1.CONTAINER_CLOSE, wrapper -> {
            final InventoryTracker tracker = wrapper.user().get(InventoryTracker.class);
            final Container pending = tracker.completePendingCloseWithoutConfirmation();
            // Java can open and close its player inventory without a matching Bedrock CONTAINER_OPEN.
            // In that case there is no pending container, but the predicted HUD cursor still has to be
            // discarded so it cannot be restored by the next full inventory sync.
            if (tracker.clearCursorIfContainerClosed()) {
                sendJavaCursor(wrapper.user(), tracker);
                clearPlayerCraftingGrid(tracker);
            }
            wrapper.user().get(DragState.class).reset();
            // The server (Nukkit) returns the crafting grid items to the inventory on close
            // (resetCraftingGridType -> inventory.addItem) and echoes the inventory, but it does NOT echo the
            // UI grid being emptied. Clear the 2x2/3x3 crafting grid + output mirror here so stale 3x3 items
            // don't linger and leak into the 2x2 view the next time a crafting screen is opened.
            if (pending instanceof CraftingTableContainer) {
                final Container hud = tracker.getHudContainer();
                for (int slot = 28; slot <= 40; slot++) {
                    hud.setItemSilent(slot, BedrockItem.empty());
                }
                hud.setItemSilent(50, BedrockItem.empty());
            }
        });
    }

    private void registerContainerClickHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CONTAINER_CLICK, wrapper -> {
            if (wrapper.user().get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
                return; // The built-in handler will reject clicks until ItemStackRequest is implemented.
            }

            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int revision = wrapper.read(Types.VAR_INT); // revision
            final short slot = wrapper.read(Types.SHORT); // slot
            final byte button = wrapper.read(Types.BYTE); // button
            final int actionId = wrapper.read(Types.VAR_INT); // action
            if (actionId < 0 || actionId >= ContainerInput.values().length) {
                wrapper.cancel();
                return;
            }
            final ContainerInput action = ContainerInput.values()[actionId];
            final int changedSlotCount = Limit.max(wrapper.read(Types.VAR_INT), 128);
            if (changedSlotCount < 0) {
                wrapper.cancel();
                return;
            }
            final Map<Short, HashedItem> changedSlots = new LinkedHashMap<>(changedSlotCount);
            boolean validPrediction = true;
            for (int i = 0; i < changedSlotCount; i++) {
                final short changedSlot = wrapper.read(Types.SHORT);
                final HashedItem changedItem = wrapper.read(Types.HASHED_ITEM);
                if (changedSlots.put(changedSlot, changedItem) != null) {
                    validPrediction = false;
                }
            }
            final HashedItem carriedItem = wrapper.read(Types.HASHED_ITEM);

            wrapper.cancel(); // Prevent original handler from executing

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            if (inventoryTracker.getPendingCloseContainer() != null) {
                return;
            }

            // Resolve container reference
            final Container container;
            if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                container = inventoryTracker.getInventoryContainer();
            } else {
                container = inventoryTracker.getContainerServerbound((byte) containerId);
                if (container == null) {
                    return;
                }
            }

            final DragState dragState = wrapper.user().get(DragState.class);
            final List<InventoryActionData> actions = validPrediction ? runOrRollback(
                    () -> ClickSimulator.validateArmorActions(
                            ClickSimulator.simulate(containerId, slot, button, action, inventoryTracker, dragState, changedSlots, carriedItem),
                            inventoryTracker),
                    error -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                            "Failed to simulate Java container click; rolling back to the authoritative inventory", error)) : null;

            if (actions == null) {
                // Unsupported operation — roll back container contents to the authoritative mirror
                if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
                return;
            }
            if (actions.isEmpty()) {
                return; // No-op, no packet needed
            }
            if (!BedrockItemLockPolicy.allows(actions)) {
                dragState.reset();
                resyncAfterRejectedClick(wrapper.user(), inventoryTracker, containerId, container);
                return;
            }

            sendNormalTransaction(wrapper.user(), actions);

            // Optimistically commit the predicted result to our mirror, then push it to Java. Previously we
            // reset Java to the pre-click state without committing the prediction, which made every action
            // visually roll back and forced the user to click twice (the second click then desynced because
            // the server had already applied the first). The server stays authoritative and will correct us
            // via clientbound inventory packets if it rejects the transaction.
            applyMirrorUpdates(actions, inventoryTracker);
            if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
            }
            PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            sendJavaCursor(wrapper.user(), inventoryTracker);
            updateCraftingOutputPreview(wrapper.user());
            // NOTE: real Bedrock clients never send an InventoryMismatch after a normal transaction.
        });
    }

    public static boolean tryHandleSwapHands(final UserConnection user) {
        if (user.get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
            return false;
        }

        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker.getPendingCloseContainer() != null) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            return true;
        }

        final List<InventoryActionData> actions = runOrRollback(
                () -> ClickSimulator.simulateSwapHands(tracker),
                error -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "Failed to simulate Java hand swap; rolling back to the authoritative inventory", error));
        if (actions == null) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            return true;
        }
        if (actions.isEmpty()) {
            return true;
        }
        if (!BedrockItemLockPolicy.allows(actions)) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            return true;
        }

        sendNormalTransaction(user, actions);
        applyMirrorUpdates(actions, tracker);
        PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        return true;
    }

    public static boolean returnCursorBeforeClose(final UserConnection user) {
        if (user.get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
            return true;
        }

        final InventoryTracker tracker = user.get(InventoryTracker.class);
        final List<InventoryActionData> actions = runOrRollback(
                () -> ClickSimulator.simulateCursorReturn(
                        tracker, JavaItemStackLimits.forTracker(tracker)),
                error -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "Failed to return the Java cursor before closing the Bedrock container", error));
        if (actions == null || !BedrockItemLockPolicy.allows(actions)) {
            return false;
        }
        if (!actions.isEmpty()) {
            sendNormalTransaction(user, actions);
            applyMirrorUpdates(actions, tracker);
            sendChangedJavaInventorySlots(user, tracker, actions);
            sendJavaCursor(user, tracker);
        }
        return true;
    }

    private static void resyncAfterRejectedClick(final UserConnection user, final InventoryTracker tracker,
                                                 final int containerId, final Container container) {
        if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        }
        PacketFactory.sendJavaContainerSetContent(user, container);
        sendJavaCursor(user, tracker);
    }

    private static void sendNormalTransaction(final UserConnection user, final List<InventoryActionData> actions) {
        final InventoryTransactionRewriter txRewriter = user.get(InventoryTransactionRewriter.class);
        final PacketWrapper txPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        txPacket.write(txRewriter.getInventoryTransactionType(),
                new BedrockInventoryTransaction(
                        0,
                        null,
                        actions,
                        ComplexInventoryTransaction_Type.NormalTransaction,
                        new InventoryTransactionData.NormalTransactionData()
                ));
        txPacket.sendToServer(BedrockProtocol.class);
    }

    private static void sendChangedJavaInventorySlots(final UserConnection user, final InventoryTracker tracker,
                                                      final List<InventoryActionData> actions) {
        final Container inventory = tracker.getInventoryContainer();
        for (final int slot : changedJavaInventorySlots(actions, tracker)) {
            final int bedrockSlot = slot >= 36 ? slot - 36 : slot;
            final PacketWrapper setSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
            setSlot.write(Types.VAR_INT, ContainerID.CONTAINER_ID_INVENTORY.getValue());
            setSlot.write(Types.VAR_INT, 0);
            setSlot.write(Types.SHORT, (short) slot);
            setSlot.write(VersionedTypes.V26_1.item, inventory.getJavaItem(bedrockSlot));
            setSlot.send(BedrockProtocol.class);
        }
    }

    static List<Integer> changedJavaInventorySlots(final List<InventoryActionData> actions,
                                                   final InventoryTracker tracker) {
        final LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.ContainerInventory
                    && action.source().containerId() == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                slots.add(tracker.getInventoryContainer().javaSlot(action.slot()));
            }
        }
        return List.copyOf(slots);
    }

    static <T> T runOrRollback(final Supplier<T> simulation, final Consumer<RuntimeException> failureHandler) {
        try {
            return simulation.get();
        } catch (final RuntimeException e) {
            failureHandler.accept(e);
            return null;
        }
    }

    private static final int HUD_OUTPUT_SLOT = 50;

    /**
     * Java relies on the server to push the crafting result into the output slot, but Bedrock computes the
     * preview client-side and never sends it. This recomputes the result from the (mirror) grid via the
     * loaded recipe table and pushes it into the Java output slot (slot 0) for both the 2x2 inventory grid
     * and the 3x3 crafting table. The result is mirrored into HUD slot 50 so the container's getJavaItems
     * (which reads HUD 50 for slot 0) stays consistent.
     */
    public static void updateCraftingOutputPreview(final UserConnection user) {
        final RecipeRegistry registry = user.get(RecipeRegistry.class);
        if (registry == null) {
            return;
        }
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        final Container current = tracker.getCurrentContainer();

        final boolean is3x3;
        final int javaWindowId;
        if (current instanceof CraftingTableContainer) {
            is3x3 = true;
            javaWindowId = current.javaContainerId();
        } else if (current == null || current.type() == ContainerType.INVENTORY) {
            is3x3 = false;
            javaWindowId = ContainerID.CONTAINER_ID_INVENTORY.getValue();
        } else {
            return; // No crafting grid in this screen
        }

        final BedrockItem[] gridItems = CraftingSimulator.getGridItems(is3x3, tracker);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        final BedrockItem output = recipe != null ? recipe.primaryOutput().copy() : BedrockItem.empty();

        final Container hud = tracker.getHudContainer();
        final BedrockItem previous = hud.getItem(HUD_OUTPUT_SLOT);
        if (!previous.isDifferent(output) && previous.amount() == output.amount()) {
            return; // No change, avoid redundant packets
        }
        hud.setItemSilent(HUD_OUTPUT_SLOT, output);

        final Item javaOutput = user.get(ItemRewriter.class).javaItem(output);
        final PacketWrapper setSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
        setSlot.write(Types.VAR_INT, javaWindowId); // window id
        setSlot.write(Types.VAR_INT, 0); // revision
        setSlot.write(Types.SHORT, (short) 0); // slot 0 = crafting output
        setSlot.write(VersionedTypes.V26_1.item, javaOutput);
        setSlot.send(BedrockProtocol.class);
    }

    /**
     * Pushes the current cursor item (HUD slot 0) to the Java client as SET_CURSOR_ITEM. sendJavaContainerSetContent
     * does not include the cursor, so after an optimistic prediction that moved the cursor we must sync it explicitly.
     */
    private static void sendJavaCursor(final UserConnection user, final InventoryTracker tracker) {
        final BedrockItem cursor = tracker.getHudContainer().getItem(0);
        final Item javaCursor = user.get(ItemRewriter.class).javaItem(cursor);
        final PacketWrapper setCursor = PacketWrapper.create(ClientboundPackets26_1.SET_CURSOR_ITEM, user);
        setCursor.write(VersionedTypes.V26_1.item, javaCursor);
        setCursor.send(BedrockProtocol.class);
    }

    static void clearPlayerCraftingGrid(final InventoryTracker tracker) {
        final Container hud = tracker.getHudContainer();
        for (int slot = 28; slot <= 31; slot++) {
            hud.setItemSilent(slot, BedrockItem.empty());
        }
        hud.setItemSilent(HUD_OUTPUT_SLOT, BedrockItem.empty());
    }

    /**
     * Applies the expected inventory state changes to the container mirror.
     * In client-authoritative mode, the client applies changes optimistically.
     * If the server rejects the transaction, it will send revert packets to correct the mirror.
     */
    private static void applyMirrorUpdates(final List<InventoryActionData> actions, final InventoryTracker tracker) {
        for (final InventoryActionData action : actions) {
            // Only ContainerInventory actions mutate slots in our mirror (grid slots, cursor, inventory,
            // armor, offhand). SOURCE_TODO craft markers (-5 USE_INGREDIENT / -4 CRAFTING_RESULT) carry no
            // mirror change — the grid decrement is now sent as an explicit ContainerInventory grid SlotChange,
            // so consuming them here too would double-decrement the grid.
            if (action.source().type() == InventorySourceType.ContainerInventory) {
                final Container container = resolveContainerById(action.source().containerId(), tracker);
                if (container != null) {
                    container.setItemSilent(action.slot(), action.toItem());
                }
            }
            // Skip WorldInteraction (drops) and CreativeInventory actions
        }
    }

    private static Container resolveContainerById(final int containerId, final InventoryTracker tracker) {
        if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) return tracker.getInventoryContainer();
        if (containerId == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue()) return tracker.getHudContainer();
        if (containerId == ContainerID.CONTAINER_ID_ARMOR.getValue()) return tracker.getArmorContainer();
        if (containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()) return tracker.getOffhandContainer();
        return tracker.getContainerServerbound((byte) containerId);
    }

    // --- DragState (per-connection storage for QUICK_CRAFT) ---

    public static class DragState extends StoredObject {
        private int dragMode = -1;
        private final List<Short> dragSlots = new ArrayList<>();

        public DragState(final UserConnection user) {
            super(user);
        }

        public void begin(int mode) {
            this.dragMode = mode;
            this.dragSlots.clear();
        }

        public void addSlot(short javaSlot) {
            this.dragSlots.add(javaSlot);
        }

        public void reset() {
            this.dragMode = -1;
            this.dragSlots.clear();
        }

        public int getDragMode() {
            return dragMode;
        }

        public List<Short> getDragSlots() {
            return new ArrayList<>(dragSlots);
        }

    }

}
