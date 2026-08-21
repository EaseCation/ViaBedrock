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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock INVENTORY_SLOT (packet 0x32).
 * <p>
 * Official Bedrock 1.21.90+ / protocol 974+ and Nukkit-MOT's matching branch encode:
 * {@code containerId, slot, optional full container name, optional storage item, item}.
 * <p>
 * NetEase / Nukkit-MOT protocol 860 still uses the pre-974 layout:
 * {@code containerId, slot, required full container name, required storage item, item}.
 * Treating that packet as 974+ consumes the container-name byte as a boolean and then
 * leaves the real item payload unread. PacketWrapper appends those leftover Bedrock
 * bytes onto Java {@code container_set_slot}, which 1.21.11 rejects as extra data.
 */
public final class InventorySlotLayout {

    public static final int OPTIONAL_CONTAINER_FIELDS_PROTOCOL = 974;

    private InventorySlotLayout() {
    }

    public static boolean usesRequiredContainerFields() {
        return usesRequiredContainerFields(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesRequiredContainerFields(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < OPTIONAL_CONTAINER_FIELDS_PROTOCOL;
    }

    public static DecodedInventorySlot read(final PacketWrapper wrapper, final ItemRewriter itemRewriter) {
        final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        if (usesRequiredContainerFields()) {
            final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME);
            final BedrockItem storageItem = wrapper.read(itemRewriter.itemType());
            final BedrockItem item = wrapper.read(itemRewriter.itemType());
            return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
        }
        final FullContainerName containerName = wrapper.read(BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME);
        final BedrockItem storageItem = wrapper.read(itemRewriter.optionalNewItemType());
        final BedrockItem item = wrapper.read(itemRewriter.newItemType());
        return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
    }

    public static DecodedInventorySlot read(final ByteBuf buffer, final Type<BedrockItem> itemType, final Type<BedrockItem> newItemType,
                                           final boolean emulateNetEase, final int protocol) {
        final int containerId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final int slot = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        if (usesRequiredContainerFields(emulateNetEase, protocol)) {
            final FullContainerName containerName = BedrockTypes.FULL_CONTAINER_NAME.read(buffer);
            final BedrockItem storageItem = itemType.read(buffer);
            final BedrockItem item = itemType.read(buffer);
            return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
        }
        final FullContainerName containerName = BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME.read(buffer);
        final BedrockItem storageItem = buffer.readBoolean() ? newItemType.read(buffer) : null;
        final BedrockItem item = newItemType.read(buffer);
        return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
    }

    public static void writeRequiredContainerFields(final ByteBuf buffer, final int containerId, final int slot,
                                                    final FullContainerName containerName, final BedrockItem storageItem,
                                                    final BedrockItem item, final Type<BedrockItem> itemType) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, containerId);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, slot);
        BedrockTypes.FULL_CONTAINER_NAME.write(buffer, containerName);
        itemType.write(buffer, storageItem);
        itemType.write(buffer, item);
    }

    public static void writeOptionalContainerFields(final ByteBuf buffer, final int containerId, final int slot,
                                                    final BedrockItem item, final Type<BedrockItem> newItemType) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, containerId);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, slot);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
        newItemType.write(buffer, item);
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }

    public record DecodedInventorySlot(int containerId, int slot, FullContainerName containerName,
                                       BedrockItem storageItem, BedrockItem item) {
    }
}

