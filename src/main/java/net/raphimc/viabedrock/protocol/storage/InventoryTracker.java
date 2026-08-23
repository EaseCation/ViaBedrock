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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.dynamic.BundleContainer;
import net.raphimc.viabedrock.api.model.container.player.ArmorContainer;
import net.raphimc.viabedrock.api.model.container.player.HudContainer;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.container.player.OffhandContainer;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.inventory.ClientAuthInventoryModule;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ModalFormCancelReason;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.NpcRequestPacket_RequestType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomItemTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public class InventoryTracker extends StoredObject {

    public record NpcDialogueState(long npcEntityUniqueId, long npcEntityRuntimeId, String sceneName) {}

    public enum ContainerState {
        CLOSED,
        OPEN,
        CLOSE_PENDING
    }

    interface ContainerClosePacketSink {

        void sendJavaClose(UserConnection user, int containerId);

        void sendBedrockClose(UserConnection user, byte containerId, ContainerType containerType);

    }

    interface ContainerClosePreparation {

        boolean returnCursor(UserConnection user);

    }

    private static final ContainerClosePacketSink PACKET_FACTORY_CLOSE_SINK = new ContainerClosePacketSink() {
        @Override
        public void sendJavaClose(final UserConnection user, final int containerId) {
            PacketFactory.sendJavaContainerClose(user, containerId);
        }

        @Override
        public void sendBedrockClose(final UserConnection user, final byte containerId, final ContainerType containerType) {
            PacketFactory.sendBedrockContainerClose(user, containerId, containerType);
        }
    };
    private static final ContainerClosePreparation CLIENT_AUTH_CLOSE_PREPARATION = ClientAuthInventoryModule::returnCursorBeforeClose;

    private final InventoryContainer inventoryContainer = new InventoryContainer(this.user());
    private final OffhandContainer offhandContainer = new OffhandContainer(this.user());
    private final ArmorContainer armorContainer = new ArmorContainer(this.user());
    private final HudContainer hudContainer = new HudContainer(this.user());
    private final Map<FullContainerName, BundleContainer> dynamicContainerRegistry = new HashMap<>();
    private final ContainerClosePacketSink closePacketSink;
    private final ContainerClosePreparation closePreparation;

    private Container currentContainer = null;
    private Container pendingCloseContainer = null;
    private IntObjectPair<Form> currentForm = null;
    private byte bedrockInventoryContainerId = (byte) ContainerID.CONTAINER_ID_NONE.getValue();
    private boolean bedrockPlayerInventoryOpen;
    /**
     * Java Q / F never open a GUI. Interact.OpenInventory still sets MOT
     * {@code inventoryOpen}. The matching CONTAINER_OPEN type=-1 must be ignored
     * so it cannot re-open after we immediately close -1.
     * Ref: MOT Player.java Interact action 6 / CONTAINER_CLOSE windowId -1.
     */
    private boolean suppressNextBedrockPlayerInventoryOpen;
    private final Map<Integer, PendingItemStackRequest> pendingItemStackRequests = new LinkedHashMap<>();
    private NpcDialogueState currentNpcDialogue = null;
    private int nextItemStackRequestId = -1;

    public InventoryTracker(final UserConnection user) {
        this(user, PACKET_FACTORY_CLOSE_SINK, CLIENT_AUTH_CLOSE_PREPARATION);
    }

    InventoryTracker(final UserConnection user, final ContainerClosePacketSink closePacketSink) {
        this(user, closePacketSink, CLIENT_AUTH_CLOSE_PREPARATION);
    }

    InventoryTracker(final UserConnection user, final ContainerClosePacketSink closePacketSink,
                     final ContainerClosePreparation closePreparation) {
        super(user);
        this.closePacketSink = closePacketSink;
        this.closePreparation = closePreparation;
    }

    public Container getContainerClientbound(final int containerId, final FullContainerName containerName, final BedrockItem storageItem) {
        if ((byte) containerId == this.inventoryContainer.containerId()) return this.inventoryContainer;
        if ((byte) containerId == this.offhandContainer.containerId()) return this.offhandContainer;
        if ((byte) containerId == this.armorContainer.containerId()) return this.armorContainer;
        if ((byte) containerId == this.hudContainer.containerId()) return this.hudContainer;
        if ((byte) containerId == ContainerID.CONTAINER_ID_REGISTRY.getValue() && containerName != null
                && containerName.name() == ContainerEnumName.DynamicContainer) {
            final String itemTag = BedrockProtocol.MAPPINGS.getBedrockCustomItemTags().get(this.user().get(ItemRewriter.class).getItems().inverse().get(storageItem.identifier()));
            if (!storageItem.isEmpty() && CustomItemTags.BUNDLE.equals(itemTag)) {
                return this.dynamicContainerRegistry.computeIfAbsent(containerName, cn -> new BundleContainer(this.user(), cn));
            } else {
                return null;
            }
        }
        if (this.currentContainer != null && matchesBedrockContainerId(this.currentContainer, containerId)) {
            return this.currentContainer;
        }
        return null;
    }

    public static boolean matchesBedrockContainerId(final Container container, final int containerId) {
        if (container == null) {
            return false;
        }
        final int stored = container.containerId() & 0xFF;
        return stored == containerId || container.containerId() == (byte) containerId;
    }

    public Container getContainerServerbound(final int containerId) {
        if (this.currentContainer != null && containerId == this.currentContainer.javaContainerId()) {
            return this.currentContainer;
        }
        return null;
    }

    public BundleContainer getDynamicContainer(final FullContainerName containerName) {
        return this.dynamicContainerRegistry.get(containerName);
    }

    public void removeDynamicContainer(final FullContainerName containerName) {
        this.dynamicContainerRegistry.remove(containerName);
    }

    public boolean beginClientClose(final Container container) {
        if (container == null || this.currentContainer != container || this.pendingCloseContainer != null) {
            return false;
        }
        this.currentContainer = null;
        this.pendingCloseContainer = container;
        return true;
    }

    public Container acceptServerClose(final byte containerId, final ContainerType containerType) {
        final Container container = this.currentContainer;
        if (container == null || !matchesCloseContainerId(container, containerId)
                || (containerType != null && container.type() != containerType && containerType != ContainerType.NONE)) {
            if (container == null && this.bedrockPlayerInventoryOpen && containerType == ContainerType.INVENTORY
                    && containerId == this.bedrockInventoryContainerId) {
                this.clearBedrockPlayerInventoryOpen();
            }
            return null;
        }
        if (!this.returnCursorBeforeClose()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to return cursor before server-initiated container close");
        }
        this.closePacketSink.sendBedrockClose(this.user(), container.bedrockCloseContainerId(), ContainerType.NONE);
        this.currentContainer = null;
        this.pendingCloseContainer = null;
        this.clearCursorAfterContainerClose();
        return container;
    }

    public Container acceptClientCloseConfirmation(final byte containerId) {
        final Container container = this.pendingCloseContainer;
        if (container == null || !matchesCloseContainerId(container, containerId)) {
            if (container == null && this.bedrockPlayerInventoryOpen && containerId == this.bedrockInventoryContainerId) {
                this.clearBedrockPlayerInventoryOpen();
            }
            return null;
        }
        this.currentContainer = null;
        this.pendingCloseContainer = null;
        this.clearCursorAfterContainerClose();
        return container;
    }

    public Container completePendingCloseWithoutConfirmation() {
        final Container container = this.pendingCloseContainer;
        if (container == null) {
            return null;
        }
        this.currentContainer = null;
        this.pendingCloseContainer = null;
        this.clearCursorAfterContainerClose();
        return container;
    }

    public void closeForDimensionChange() {
        if (!this.returnCursorBeforeClose()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to return cursor before dimension change");
        }
        if (this.currentContainer != null) {
            this.closePacketSink.sendBedrockClose(this.user(), this.currentContainer.bedrockCloseContainerId(), ContainerType.NONE);
        }
        this.currentContainer = null;
        this.pendingCloseContainer = null;
        this.clearCursorAfterContainerClose();
        this.clearBedrockPlayerInventoryOpen();
    }

    private void clearCursorAfterContainerClose() {
        this.hudContainer.setItemSilent(0, BedrockItem.empty());
    }

    private boolean returnCursorBeforeClose() {
        return this.hudContainer.getItem(0).isEmpty() || this.closePreparation.returnCursor(this.user());
    }

    public boolean clearCursorIfContainerClosed() {
        if (this.isContainerOpen()) {
            return false;
        }
        this.clearCursorAfterContainerClose();
        return true;
    }

    public void closeCurrentForm() {
        if (this.currentForm == null) {
            throw new IllegalStateException("There is no form currently open");
        }
        final PacketWrapper modalFormResponse = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, this.user());
        modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, this.currentForm.leftInt()); // id
        modalFormResponse.write(Types.BOOLEAN, false); // has response
        modalFormResponse.write(Types.BOOLEAN, true); // has cancel reason
        modalFormResponse.write(Types.BYTE, (byte) ModalFormCancelReason.UserClosed.getValue()); // cancel reason
        modalFormResponse.sendToServer(BedrockProtocol.class);
        this.currentForm = null;
    }

    public void tick() {
        if (this.currentContainer != null && this.currentContainer.position() != null) {
            if (this.currentContainer.type() == ContainerType.INVENTORY) return;

            final ChunkTracker chunkTracker = this.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
            final int blockState = chunkTracker.getBlockState(this.currentContainer.position());
            final String tag = blockStateRewriter.tag(blockState);
            // MOT plugin / fake inventories send CONTAINER_OPEN at (0,0,0). Air at world
            // origin is not a real chest; keep the GUI. A real tagged block at spawn still
            // closes if the player walks away. Untagged GenericContainers treat air as valid,
            // so they must skip the distance check as well.
            if (isDummyWorldPosition(this.currentContainer.position())
                    && (!this.currentContainer.isWorldBacked() || !this.currentContainer.isValidBlockTag(tag))) {
                return;
            }
            if (!this.currentContainer.isValidBlockTag(tag)) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Closing " + this.currentContainer.type() + " because block state is not valid for container type: " + blockState);
                this.forceCloseCurrentContainer();
                return;
            }

            final EntityTracker entityTracker = this.user().get(EntityTracker.class);
            final Position3f containerPosition = new Position3f(this.currentContainer.position().x() + 0.5F, this.currentContainer.position().y() + 0.5F, this.currentContainer.position().z() + 0.5F);
            final Position3f playerPosition = entityTracker.getClientPlayer().position();
            if (playerPosition.distanceTo(containerPosition) > 6) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Closing " + this.currentContainer.type() + " because player is too far away (" + playerPosition.distanceTo(containerPosition) + " > 6)");
                this.forceCloseCurrentContainer();
            }
        }
    }

    /**
     * Nukkit-MOT writes {@code x=y=z=0} when the inventory holder is not a world block
     * ({@code ContainerInventory}, plugin fake chests, ender chest with no viewing block).
     */
    public static boolean isDummyWorldPosition(final BlockPosition position) {
        return position != null && position.x() == 0 && position.y() == 0 && position.z() == 0;
    }

    public boolean isContainerOpen() {
        return this.getContainerState() != ContainerState.CLOSED;
    }

    public boolean isAnyScreenOpen() {
        return this.isContainerOpen() || this.currentForm != null || this.currentNpcDialogue != null;
    }

    private static boolean matchesCloseContainerId(final Container container, final byte containerId) {
        return container.containerId() == containerId || container.bedrockCloseContainerId() == containerId;
    }

    public void rememberPendingItemStackRequest(final int requestId, final net.raphimc.viabedrock.experimental.inventory.InventorySnapshot snapshot) {
        if (requestId == 0 || snapshot == null) {
            return;
        }
        this.pendingItemStackRequests.put(requestId, new PendingItemStackRequest(requestId, snapshot));
    }

    public net.raphimc.viabedrock.experimental.inventory.InventorySnapshot takePendingItemStackRequest(final int requestId) {
        if (requestId == 0) {
            return this.takeLatestPendingItemStackRequest();
        }
        final PendingItemStackRequest pending = this.pendingItemStackRequests.remove(requestId);
        return pending != null ? pending.snapshot() : null;
    }

    public net.raphimc.viabedrock.experimental.inventory.InventorySnapshot peekPendingItemStackRequest(final int requestId) {
        final PendingItemStackRequest pending = this.pendingItemStackRequests.get(requestId);
        return pending != null ? pending.snapshot() : null;
    }

    public net.raphimc.viabedrock.experimental.inventory.InventorySnapshot takeLatestPendingItemStackRequest() {
        if (this.pendingItemStackRequests.isEmpty()) {
            return null;
        }
        Integer lastKey = null;
        for (final Integer key : this.pendingItemStackRequests.keySet()) {
            lastKey = key;
        }
        final PendingItemStackRequest pending = lastKey != null ? this.pendingItemStackRequests.remove(lastKey) : null;
        return pending != null ? pending.snapshot() : null;
    }

    public int pendingItemStackRequestCount() {
        return this.pendingItemStackRequests.size();
    }

    public int nextItemStackRequestId() {
        final int requestId = this.nextItemStackRequestId;
        this.nextItemStackRequestId -= 2;
        if (this.nextItemStackRequestId >= 0) {
            this.nextItemStackRequestId = -1;
        }
        return requestId;
    }

    public InventoryContainer getInventoryContainer() {
        return this.inventoryContainer;
    }

    public OffhandContainer getOffhandContainer() {
        return this.offhandContainer;
    }

    public ArmorContainer getArmorContainer() {
        return this.armorContainer;
    }

    public HudContainer getHudContainer() {
        return this.hudContainer;
    }

    public Container getCurrentContainer() {
        return this.currentContainer;
    }

    public ContainerState getContainerState() {
        if (this.currentContainer != null) {
            if (this.pendingCloseContainer != null) {
                throw new IllegalStateException("Container cannot be open and pending close at the same time");
            }
            return ContainerState.OPEN;
        }
        return this.pendingCloseContainer != null ? ContainerState.CLOSE_PENDING : ContainerState.CLOSED;
    }

    public void setCurrentContainer(final Container container) {
        if (this.isContainerOpen()) {
            throw new IllegalStateException("There is already another container open");
        }
        this.currentContainer = container;
        if (container != null && container.type() != ContainerType.INVENTORY) {
            this.bedrockPlayerInventoryOpen = false;
        }
    }

    /**
     * MOT HorseInventory size is {@code 2 + chestSize} and can grow after CONTAINER_OPEN
     * (donkey chest / llama strength). Resize the mirror without closing the JE window.
     */
    public void replaceCurrentContainer(final Container container) {
        if (this.currentContainer == null || container == null) {
            throw new IllegalStateException("Cannot replace a closed container");
        }
        if (this.pendingCloseContainer != null) {
            throw new IllegalStateException("Container cannot be open and pending close at the same time");
        }
        this.currentContainer = container;
    }

    public void acknowledgeBedrockInventoryOpen(final byte containerId, final BlockPosition position) {
        if (this.suppressNextBedrockPlayerInventoryOpen) {
            this.suppressNextBedrockPlayerInventoryOpen = false;
            this.bedrockPlayerInventoryOpen = false;
            this.bedrockInventoryContainerId = (byte) ContainerID.CONTAINER_ID_NONE.getValue();
            this.inventoryContainer.clearBedrockOpen();
            return;
        }
        this.bedrockInventoryContainerId = containerId;
        this.bedrockPlayerInventoryOpen = true;
        this.inventoryContainer.rememberBedrockOpen(containerId, position);
    }

    /**
     * Close MOT player inventory after a Java-only hotbar Drop/Swap. MOT
     * {@code inventoryOpen} blocks melee and later {@code addWindow}.
     * If CONTAINER_OPEN type=-1 has not arrived yet, ignore that one OPEN so it
     * cannot re-open after this close.
     */
    public void closeTransientBedrockPlayerInventory() {
        if (this.currentContainer != null || this.pendingCloseContainer != null) {
            return;
        }
        if (!this.bedrockPlayerInventoryOpen) {
            this.suppressNextBedrockPlayerInventoryOpen = true;
        }
        this.closePacketSink.sendBedrockClose(this.user(), (byte) -1, ContainerType.NONE);
        this.clearBedrockPlayerInventoryOpen();
    }

    public boolean isSuppressingNextBedrockPlayerInventoryOpen() {
        return this.suppressNextBedrockPlayerInventoryOpen;
    }

    public boolean isBedrockPlayerInventoryOpen() {
        return this.bedrockPlayerInventoryOpen;
    }

    public byte bedrockInventoryContainerId() {
        return this.bedrockInventoryContainerId;
    }

    public void clearBedrockPlayerInventoryOpen() {
        this.bedrockPlayerInventoryOpen = false;
        this.bedrockInventoryContainerId = (byte) ContainerID.CONTAINER_ID_NONE.getValue();
        this.inventoryContainer.clearBedrockOpen();
    }

    public Container getPendingCloseContainer() {
        return this.pendingCloseContainer;
    }

    public IntObjectPair<Form> getCurrentForm() {
        return this.currentForm;
    }

    public void setCurrentForm(final IntObjectPair<Form> currentForm) {
        this.currentForm = currentForm;
    }

    public NpcDialogueState getCurrentNpcDialogue() {
        return this.currentNpcDialogue;
    }

    public void setCurrentNpcDialogue(final NpcDialogueState npcDialogue) {
        this.currentNpcDialogue = npcDialogue;
    }

    public void closeCurrentNpcDialogue() {
        if (this.currentNpcDialogue == null) {
            throw new IllegalStateException("There is no NPC dialogue currently open");
        }
        final PacketWrapper npcRequest = PacketWrapper.create(ServerboundBedrockPackets.NPC_REQUEST, this.user());
        npcRequest.write(BedrockTypes.UNSIGNED_VAR_LONG, this.currentNpcDialogue.npcEntityRuntimeId()); // entity runtime id
        npcRequest.write(Types.BYTE, (byte) NpcRequestPacket_RequestType.ExecuteClosingCommands.getValue()); // type
        npcRequest.write(BedrockTypes.STRING, ""); // command
        npcRequest.write(Types.BYTE, (byte) 0); // action index
        npcRequest.write(BedrockTypes.STRING, this.currentNpcDialogue.sceneName()); // scene name
        npcRequest.sendToServer(BedrockProtocol.class);
        this.currentNpcDialogue = null;
    }

    private record PendingItemStackRequest(int requestId, net.raphimc.viabedrock.experimental.inventory.InventorySnapshot snapshot) {
    }

    boolean forceCloseCurrentContainer() {
        final Container container = this.currentContainer;
        if (container == null || !this.returnCursorBeforeClose()) {
            return false;
        }
        if (!this.beginClientClose(container)) {
            return false;
        }
        this.closePacketSink.sendJavaClose(this.user(), container.javaContainerId());
        this.closePacketSink.sendBedrockClose(this.user(), container.bedrockCloseContainerId(), ContainerType.NONE);
        return true;
    }

}
