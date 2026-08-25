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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackRequestLayoutTest {

    @Test
    void netease860KeepsLegacyByteActionType() {
        assertFalse(ItemStackRequestLayout.usesUnsignedActionType(true, 860));
        assertFalse(ItemStackRequestLayout.usesUnsignedActionType(false, 975));
        assertTrue(ItemStackRequestLayout.usesUnsignedActionType(false, 2168));
        assertTrue(ItemStackRequestLayout.usesOptionalDynamicId(true, 860));
        assertTrue(ItemStackRequestLayout.usesOptionalDynamicId(false, 975));
        assertFalse(ItemStackRequestLayout.usesLittleEndianStackNetworkId(true, 860));
        assertFalse(ItemStackRequestLayout.usesLittleEndianStackNetworkId(false, 975));
    }

    @Test
    void netease860TakeUsesByteTypeAndVarintNetId() {
        final ItemStackRequestLayout.SlotInfo source = new ItemStackRequestLayout.SlotInfo(ContainerEnumName.HotbarContainer, 0, 12);
        final ItemStackRequestLayout.SlotInfo destination = new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CursorContainer, 0, 0);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeTransfer(buffer, ItemStackRequestActionType.Take, 1, source, destination, true, 860);
            assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            assertEquals(1, buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo decodedSource = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.HotbarContainer, decodedSource.container());
            assertEquals(0, decodedSource.slot());
            assertEquals(12, decodedSource.stackNetworkId());
            final ItemStackRequestLayout.DecodedSlotInfo decodedDestination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.CursorContainer, decodedDestination.container());
            assertEquals(0, decodedDestination.slot());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official2168TakeWritesUnsignedTypeAndLittleEndianNetId() {
        final ItemStackRequestLayout.SlotInfo source = new ItemStackRequestLayout.SlotInfo(ContainerEnumName.InventoryContainer, 12, 44);
        final ItemStackRequestLayout.SlotInfo destination = new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CursorContainer, 0, 0);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeTransfer(buffer, ItemStackRequestActionType.Take, 8, source, destination, false, 2168);
            assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            assertEquals(0, buffer.readUnsignedByte());
            assertEquals(8, buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo decodedSource = ItemStackRequestLayout.readSlotInfo(buffer, false, 2168);
            assertEquals(ContainerEnumName.InventoryContainer, decodedSource.container());
            assertEquals(12, decodedSource.slot());
            assertEquals(44, decodedSource.stackNetworkId());
            final ItemStackRequestLayout.DecodedSlotInfo decodedDestination = ItemStackRequestLayout.readSlotInfo(buffer, false, 2168);
            assertEquals(ContainerEnumName.CursorContainer, decodedDestination.container());
            assertEquals(0, decodedDestination.slot());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975StillUsesLegacyByteActionType() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeActionType(buffer, ItemStackRequestActionType.Swap, false, 975);
            assertEquals(ItemStackRequestActionType.Swap.getValue(), buffer.readUnsignedByte());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860TrailerStillWritesFilterStringsAndOrigin() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeRequestTrailer(buffer, true, 860);
            final ItemStackRequestLayout.DecodedRequestTrailer trailer = ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertEquals(0, trailer.filterStringCount());
            assertEquals(TextProcessingEventOrigin.BlockActorDataText.getValue(), trailer.textOrigin());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860TrailerWritesFilterStringsAndAnvilOrigin() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeRequestTrailer(buffer, true, 860, new String[]{"Renamed"}, TextProcessingEventOrigin.AnvilText);
            final ItemStackRequestLayout.DecodedRequestTrailer trailer = ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertEquals(1, trailer.filterStringCount());
            assertEquals("Renamed", trailer.filterStrings()[0]);
            assertEquals(TextProcessingEventOrigin.AnvilText.getValue(), trailer.textOrigin());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860CraftRecipeOptionalWritesByteTypeUnsignedNetIdAndFilterIndex() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeCraftRecipeOptional(buffer, 0, 0, true, 860);
            assertEquals(ItemStackRequestActionType.CraftRecipeOptional.getValue(), buffer.readUnsignedByte());
            assertEquals(0, (int) net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(0, buffer.readIntLE());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860CraftRecipeWritesByteTypeUnsignedNetIdAndTimesCrafted() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeCraftRecipe(buffer, 0x10000001, 1, true, 860);
            assertEquals(ItemStackRequestActionType.CraftRecipe.getValue(), buffer.readUnsignedByte());
            assertEquals(0x10000001, (int) net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(1, buffer.readUnsignedByte());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860CraftCreativeWritesByteTypeUnsignedNetIdAndTimesCrafted() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeCraftCreative(buffer, 7, 1, true, 860);
            assertEquals(ItemStackRequestActionType.CraftCreative.getValue(), buffer.readUnsignedByte());
            assertEquals(7, (int) net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(1, buffer.readUnsignedByte());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860CraftRepairAndDisenchantWritesByteTypeUnsignedNetIdTimesAndRepairCost() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeCraftRepairAndDisenchant(buffer, 0, 1, 0, true, 860);
            assertEquals(ItemStackRequestActionType.CraftRepairAndDisenchant.getValue(), buffer.readUnsignedByte());
            assertEquals(0, (int) net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(1, buffer.readUnsignedByte());
            assertEquals(0, (int) net.raphimc.viabedrock.protocol.types.BedrockTypes.VAR_INT.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860CraftLoomWritesByteTypePatternIdAndTimesCrafted() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeCraftLoom(buffer, "bricks", 1, true, 860);
            assertEquals(ItemStackRequestActionType.CraftLoom.getValue(), buffer.readUnsignedByte());
            assertEquals("bricks", net.raphimc.viabedrock.protocol.types.BedrockTypes.STRING.read(buffer));
            assertEquals(1, buffer.readUnsignedByte());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860CraftRecipeAutoWritesDefaultDescriptorsAndRequestedCraftCount() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeCraftRecipeAuto(
                    buffer, 42, 8, 8,
                    List.of(new BedrockItem(12, (short) 3, (byte) 8)),
                    true, 860);
            assertEquals(ItemStackRequestActionType.CraftRecipeAuto.getValue(), buffer.readUnsignedByte());
            assertEquals(42, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(8, buffer.readUnsignedByte());
            assertEquals(8, buffer.readUnsignedByte());
            assertEquals(1, buffer.readUnsignedByte());
            assertEquals(1, buffer.readUnsignedByte());
            assertEquals(12, buffer.readShortLE());
            assertEquals(3, buffer.readShortLE());
            assertEquals(8, (int) BedrockTypes.VAR_INT.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void neteaseShiftMovesCursorPastRecipeItemsHole() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ItemStackRequestLayout.writeSlotInfo(buffer, ContainerEnumName.CursorContainer, 0, 0, null, true, 860);
            assertEquals(60, buffer.readUnsignedByte());
            assertFalse(buffer.readBoolean());
            assertEquals(0, buffer.readUnsignedByte());
        } finally {
            buffer.release();
        }
    }
}
