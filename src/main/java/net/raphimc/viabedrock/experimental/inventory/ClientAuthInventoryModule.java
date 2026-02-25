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
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.packet.ServerboundPackets1_21_6;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ComplexInventoryTransaction_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ClickType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ClientAuthInventoryModule implements FeatureModule {

    private static final int TODO_USE_INGREDIENT = -5;

    private static Logger logger() {
        return ViaBedrock.getPlatform().getLogger();
    }

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new DragState(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        registerContainerClickHandler(protocol);
    }

    private void registerContainerClickHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets1_21_6.CONTAINER_CLICK, wrapper -> {
            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int revision = wrapper.read(Types.VAR_INT); // revision
            final short slot = wrapper.read(Types.SHORT); // slot
            final byte button = wrapper.read(Types.BYTE); // button
            final ClickType action = ClickType.values()[wrapper.read(Types.VAR_INT)]; // action

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

            logger().fine("[CLICK] containerId=" + containerId + " slot=" + slot + " button=" + button + " action=" + action
                    + " cursor=" + SlotMapper.getCursorItem(inventoryTracker));

            final DragState dragState = wrapper.user().get(DragState.class);
            final List<InventoryActionData> actions = ClickSimulator.simulate(
                    containerId, slot, button, action, inventoryTracker, dragState);

            if (actions == null) {
                logger().fine("[CLICK] -> unsupported, rollback");
                // Unsupported operation — rollback container contents
                if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
                return;
            }
            if (actions.isEmpty()) {
                logger().fine("[CLICK] -> no-op (empty actions)");
                return; // No-op, no packet needed
            }

            // Log actions
            for (InventoryActionData a : actions) {
                logger().fine("[CLICK]   action: src=" + a.source().type() + " cid=" + a.source().containerId()
                        + " slot=" + a.slot() + " from=" + a.fromItem() + " to=" + a.toItem());
            }

            // Check if this is a crafting operation (has SOURCE_TODO actions)
            final boolean isCraftingAction = hasCraftingActions(actions);

            // Apply mirror updates (optimistic) — client-authoritative: assume server will accept
            applyMirrorUpdates(actions, inventoryTracker);

            // Resync client for operations where Java client predicts differently
            if (action == ClickType.QUICK_MOVE || isCraftingAction) {
                if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            }

            // Send NormalTransaction to Bedrock server
            final InventoryTransactionRewriter txRewriter = wrapper.user().get(InventoryTransactionRewriter.class);
            final PacketWrapper txPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper.user());

            txPacket.write(txRewriter.getInventoryTransactionType(),
                    new BedrockInventoryTransaction(
                            0,
                            null,
                            actions,
                            ComplexInventoryTransaction_Type.NormalTransaction,
                            new InventoryTransactionData.NormalTransactionData()
                    ));

            txPacket.sendToServer(BedrockProtocol.class);
        });
    }

    private static boolean hasCraftingActions(final List<InventoryActionData> actions) {
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.NonImplementedFeatureTODO) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the expected inventory state changes to the container mirror.
     * In client-authoritative mode, the client applies changes optimistically.
     * If the server rejects the transaction, it will send revert packets to correct the mirror.
     */
    private static void applyMirrorUpdates(final List<InventoryActionData> actions, final InventoryTracker tracker) {
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.ContainerInventory) {
                final Container container = resolveContainerById(action.source().containerId(), tracker);
                if (container != null) {
                    container.setItemSilent(action.slot(), action.toItem());
                }
            } else if (action.source().type() == InventorySourceType.NonImplementedFeatureTODO) {
                // Handle SOURCE_TODO crafting actions
                final int todoType = action.source().containerId();
                if (todoType == TODO_USE_INGREDIENT) {
                    // USE_INGREDIENT: toItem is the ingredient consumed; find matching grid slots and decrement
                    applyIngredientConsumption(action.toItem(), tracker);
                }
                // CRAFTING_RESULT(-4): no mirror update needed (the result is handled by cursor action)
            }
            // Skip WorldInteraction (drops) and CreativeInventory actions
        }
    }

    /**
     * Decrements grid slot items to simulate ingredient consumption.
     * Each consumed ingredient reduces matching grid items by 1 count.
     */
    private static void applyIngredientConsumption(final BedrockItem ingredient, final InventoryTracker tracker) {
        final Container hudContainer = tracker.getHudContainer();
        int toConsume = ingredient.amount();

        // Choose grid range based on whether a crafting table is open
        final boolean is3x3 = tracker.getCurrentContainer() instanceof CraftingTableContainer;
        final int[][] gridRanges = is3x3 ? new int[][]{{32, 40}} : new int[][]{{28, 31}};
        for (int[] range : gridRanges) {
            for (int slot = range[0]; slot <= range[1] && toConsume > 0; slot++) {
                final BedrockItem gridItem = hudContainer.getItem(slot);
                if (gridItem.isEmpty()) continue;
                if (gridItem.identifier() != ingredient.identifier()) continue;
                if (gridItem.data() != ingredient.data()) continue;

                if (gridItem.amount() <= toConsume) {
                    toConsume -= gridItem.amount();
                    hudContainer.setItemSilent(slot, BedrockItem.empty());
                } else {
                    BedrockItem newItem = gridItem.copy();
                    newItem.setAmount(gridItem.amount() - toConsume);
                    hudContainer.setItemSilent(slot, newItem);
                    toConsume = 0;
                }
            }
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
