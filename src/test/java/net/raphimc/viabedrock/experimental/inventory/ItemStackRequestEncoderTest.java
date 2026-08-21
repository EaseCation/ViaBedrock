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
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackRequestEncoderTest {

    @Test
    void netease860PickupHotbarToCursorWritesTake() {
        final BedrockItem stack = item(12, 16, 7);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 0, stack, BedrockItem.empty()),
                cursorAction(BedrockItem.empty(), stack)
        ), true, 860);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            assertEquals(16, buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.HotbarContainer, source.container());
            assertEquals(0, source.slot());
            assertEquals(7, source.stackNetworkId());
            final ItemStackRequestLayout.DecodedSlotInfo destination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.CursorContainer, destination.container());
            assertEquals(0, destination.slot());
            assertEquals(0, destination.stackNetworkId());
            final ItemStackRequestLayout.DecodedRequestTrailer trailer = ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertEquals(0, trailer.filterStringCount());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975PickupKeepsByteActionType() {
        final BedrockItem stack = item(3, 8, 0);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 12, stack, BedrockItem.empty()),
                cursorAction(BedrockItem.empty(), stack)
        ), false, 975);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            assertEquals(8, buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, false, 975);
            assertEquals(ContainerEnumName.InventoryContainer, source.container());
            assertEquals(12, source.slot());
            ItemStackRequestLayout.readSlotInfo(buffer, false, 975);
            ItemStackRequestLayout.readRequestTrailer(buffer, false, 975);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void swapDifferentStacksWritesSwap() {
        final BedrockItem sword = item(1, 1, 11);
        final BedrockItem food = item(2, 4, 22);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 0, sword, food),
                cursorAction(food, sword)
        ), true, 860);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Swap.getValue(), buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.HotbarContainer, source.container());
            assertEquals(11, source.stackNetworkId());
            final ItemStackRequestLayout.DecodedSlotInfo destination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.CursorContainer, destination.container());
            assertEquals(22, destination.stackNetworkId());
            ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void shiftClickSplitsIntoMultipleTakes() {
        final BedrockItem sourceFrom = item(5, 64, 3);
        final BedrockItem destA = item(5, 32, 0);
        final BedrockItem destB = item(5, 32, 0);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 9, sourceFrom, BedrockItem.empty()),
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 0, BedrockItem.empty(), destA),
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 1, BedrockItem.empty(), destB)
        ), true, 860);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(2, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            assertEquals(32, buffer.readUnsignedByte());
            ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            assertEquals(32, buffer.readUnsignedByte());
            ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void dropFromHotbarWritesDrop() {
        final BedrockItem stack = item(9, 3, 4);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                worldDrop(stack),
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 0, stack, BedrockItem.empty())
        ), true, 860);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Drop.getValue(), buffer.readUnsignedByte());
            assertEquals(3, buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.HotbarContainer, source.container());
            assertFalse(buffer.readBoolean());
            ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void creativeCloneStaysUnsupported() {
        final BedrockItem cloned = item(1, 64, 0);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                new InventoryActionData(
                        new InventorySource(InventorySourceType.CreativeInventory, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                        0, BedrockItem.empty(), cloned),
                cursorAction(BedrockItem.empty(), cloned)
        ), true, 860);
        assertTrue(encoded.unsupported());
    }

    @Test
    void placeFromCursorUsesPlaceAction() {
        final BedrockItem stack = item(8, 5, 0);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 9, BedrockItem.empty(), stack),
                cursorAction(stack, BedrockItem.empty())
        ), true, 860);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Place.getValue(), buffer.readUnsignedByte());
            assertEquals(5, buffer.readUnsignedByte());
        } finally {
            buffer.release();
        }
    }

    private static ItemStackRequestEncoder.EncodedRequest encode(final List<InventoryActionData> actions,
                                                                 final boolean emulateNetEase, final int protocol) {
        return ItemStackRequestEncoder.encode(actions, null, emulateNetEase, protocol);
    }

    private static InventoryActionData slotAction(final int containerId, final int slot, final BedrockItem from, final BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, containerId, InventorySource_InventorySourceFlags.NoFlag),
                slot, from.copy(), to.copy());
    }

    private static InventoryActionData cursorAction(final BedrockItem from, final BedrockItem to) {
        return slotAction(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 0, from, to);
    }

    private static InventoryActionData worldDrop(final BedrockItem dropped) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), dropped.copy());
    }

    private static BedrockItem item(final int id, final int amount, final int netId) {
        return new BedrockItem(id, (short) 0, (byte) amount, null, new String[0], new String[0], 0, 0, netId);
    }
}
