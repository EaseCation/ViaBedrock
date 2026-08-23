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

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.container.AnvilContainer;
import net.raphimc.viabedrock.api.model.container.GenericContainer;
import net.raphimc.viabedrock.api.model.container.TradeContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.test.StubUserConnection;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
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
    void hotbarPartialDropWritesDropCountOne() {
        final BedrockItem stack = item(9, 8, 4);
        final BedrockItem remaining = item(9, 7, 4);
        final BedrockItem dropped = item(9, 1, 4);
        final ItemStackRequestEncoder.EncodedRequest encoded = encode(List.of(
                worldDrop(dropped),
                slotAction(ContainerID.CONTAINER_ID_INVENTORY.getValue(), 3, stack, remaining)
        ), true, 860);

        assertFalse(encoded.unsupported());
        final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
        try {
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(ItemStackRequestActionType.Drop.getValue(), buffer.readUnsignedByte());
            assertEquals(1, buffer.readUnsignedByte());
            final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
            assertEquals(ContainerEnumName.HotbarContainer, source.container());
            assertEquals(3, source.slot());
            assertFalse(buffer.readBoolean());
            ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void creativeCloneStaysUnsupportedWithoutCache() {
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
    void netease860CreativeSpawnWritesCraftCreativeAndTake() {
        final BedrockItem cloned = item(35, 64, 0);
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final CreativeContentCache cache = new CreativeContentCache(user);
            cache.replace(List.of(new CreativeContentCache.Entry(7, cloned.copy())));
            user.put(cache);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encode(List.of(
                    new InventoryActionData(
                            new InventorySource(InventorySourceType.CreativeInventory, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                            0, BedrockItem.empty(), cloned),
                    cursorAction(BedrockItem.empty(), cloned)
            ), tracker, true, 860);

            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(2, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftCreative.getValue(), buffer.readUnsignedByte());
                assertEquals(7, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(1, buffer.readUnsignedByte());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                assertEquals(64, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CreatedOutputContainer, source.container());
                assertEquals(50, source.slot());
                final ItemStackRequestLayout.DecodedSlotInfo destination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CursorContainer, destination.container());
                ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860AnvilApplyWritesCraftRecipeOptionalConsumeAndCreatedOutputTake() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final AnvilContainer anvil = new AnvilContainer(user, (byte) 1, null, new BlockPosition(0, 64, 0));
            anvil.setItemSilent(0, item(12, 1, 7));
            anvil.setItemSilent(1, item(13, 4, 8));
            tracker.setCurrentContainer(anvil);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeAnvilApply(
                    tracker, "Renamed", 1, 1, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(4, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipeOptional.getValue(), buffer.readUnsignedByte());
                assertEquals(0, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(0, buffer.readIntLE());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo input = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.AnvilInputContainer, input.container());
                assertEquals(1, input.slot());
                assertEquals(7, input.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo material = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.AnvilMaterialContainer, material.container());
                assertEquals(2, material.slot());
                assertEquals(8, material.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CreatedOutputContainer, source.container());
                assertEquals(50, source.slot());
                final ItemStackRequestLayout.DecodedSlotInfo destination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CursorContainer, destination.container());
                assertEquals(0, destination.slot());
                final ItemStackRequestLayout.DecodedRequestTrailer trailer = ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
                assertEquals(1, trailer.filterStringCount());
                assertEquals("Renamed", trailer.filterStrings()[0]);
                assertEquals(TextProcessingEventOrigin.AnvilText.getValue(), trailer.textOrigin());
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860AnvilApplyBlankNameUsesNegativeFilterIndexAndEmptyTrailer() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final AnvilContainer anvil = new AnvilContainer(user, (byte) 1, null, new BlockPosition(0, 64, 0));
            anvil.setItemSilent(0, item(12, 1, 3));
            tracker.setCurrentContainer(anvil);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeAnvilApply(
                    tracker, "", 1, 0, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(3, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipeOptional.getValue(), buffer.readUnsignedByte());
                assertEquals(0, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(-1, buffer.readIntLE());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                buffer.readUnsignedByte();
                ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                buffer.readUnsignedByte();
                ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                final ItemStackRequestLayout.DecodedRequestTrailer trailer = ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
                assertEquals(0, trailer.filterStringCount());
                assertEquals(TextProcessingEventOrigin.AnvilText.getValue(), trailer.textOrigin());
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860CartographyApplyWritesCraftRecipeOptionalConsumeAndCreatedOutputTake() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final GenericContainer cartography = new GenericContainer(user, (byte) 1, ContainerType.CARTOGRAPHY, null, new BlockPosition(0, 64, 0), 3);
            cartography.setItemSilent(0, item(12, 1, 7));
            cartography.setItemSilent(1, item(13, 1, 8));
            tracker.setCurrentContainer(cartography);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeCartographyApply(
                    tracker, 1, 1, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(4, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipeOptional.getValue(), buffer.readUnsignedByte());
                assertEquals(0, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(-1, buffer.readIntLE());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo input = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CartographyInputContainer, input.container());
                assertEquals(12, input.slot());
                assertEquals(7, input.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo additional = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CartographyAdditionalContainer, additional.container());
                assertEquals(13, additional.slot());
                assertEquals(8, additional.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CreatedOutputContainer, source.container());
                assertEquals(50, source.slot());
                final ItemStackRequestLayout.DecodedSlotInfo destination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CursorContainer, destination.container());
                assertEquals(0, destination.slot());
                final ItemStackRequestLayout.DecodedRequestTrailer trailer = ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
                assertEquals(0, trailer.filterStringCount());
                assertEquals(TextProcessingEventOrigin.CartographyText.getValue(), trailer.textOrigin());
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860GrindstoneApplyWritesCraftRepairConsumeAndCreatedOutputTake() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final GenericContainer grindstone = new GenericContainer(user, (byte) 1, ContainerType.GRINDSTONE, null, new BlockPosition(0, 64, 0), 3);
            grindstone.setItemSilent(0, item(12, 1, 7));
            grindstone.setItemSilent(1, item(13, 1, 8));
            tracker.setCurrentContainer(grindstone);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeGrindstoneApply(
                    tracker, 1, 1, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(4, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRepairAndDisenchant.getValue(), buffer.readUnsignedByte());
                assertEquals(0, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(1, buffer.readUnsignedByte());
                assertEquals(0, (int) BedrockTypes.VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo input = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.GrindstoneInputContainer, input.container());
                assertEquals(16, input.slot());
                assertEquals(7, input.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo additional = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.GrindstoneAdditionalContainer, additional.container());
                assertEquals(17, additional.slot());
                assertEquals(8, additional.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CreatedOutputContainer, source.container());
                assertEquals(50, source.slot());
                final ItemStackRequestLayout.DecodedSlotInfo destination = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CursorContainer, destination.container());
                assertEquals(0, destination.slot());
                ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860AnvilApplyConsumesMultipleRepairUnits() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final AnvilContainer anvil = new AnvilContainer(user, (byte) 1, null, new BlockPosition(0, 64, 0));
            anvil.setItemSilent(0, damaged(276, 800, 7));
            anvil.setItemSilent(1, item(264, 8, 8));
            tracker.setCurrentContainer(anvil);

            assertEquals(3, AnvilRepairCost.materialCount(anvil.getItem(0), anvil.getItem(1), null));
            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeAnvilApply(
                    tracker, "Renamed", 1, 3, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(4, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipeOptional.getValue(), buffer.readUnsignedByte());
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                buffer.readIntLE();
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(3, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo material = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.AnvilMaterialContainer, material.container());
                assertEquals(8, material.stackNetworkId());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860LoomApplyWritesCraftLoomConsumeAndCreatedOutputTake() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final GenericContainer loom = new GenericContainer(user, (byte) 1, ContainerType.LOOM, null, new BlockPosition(0, 64, 0), 4);
            loom.setItemSilent(0, item(12, 1, 7));
            loom.setItemSilent(1, item(13, 1, 8));
            tracker.setCurrentContainer(loom);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeLoomApply(
                    tracker, 1, 1, 1, "cre", true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(4, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftLoom.getValue(), buffer.readUnsignedByte());
                assertEquals("cre", BedrockTypes.STRING.read(buffer));
                assertEquals(1, buffer.readUnsignedByte());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo banner = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.LoomInputContainer, banner.container());
                assertEquals(9, banner.slot());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo dye = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.LoomDyeContainer, dye.container());
                assertEquals(10, dye.slot());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CreatedOutputContainer, source.container());
                assertEquals(50, source.slot());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860StonecutterApplyWritesCraftRecipeConsumeAndTake() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final GenericContainer cutter = new GenericContainer(user, (byte) 1, ContainerType.STONECUTTER, null, new BlockPosition(0, 64, 0), 2);
            cutter.setItemSilent(0, item(12, 1, 7));
            tracker.setCurrentContainer(cutter);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeStonecutterApply(
                    tracker, 42, 1, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(3, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipe.getValue(), buffer.readUnsignedByte());
                assertEquals(42, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(1, buffer.readUnsignedByte());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo input = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.StonecutterInputContainer, input.container());
                assertEquals(3, input.slot());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860SmithingApplyConsumesEquipmentIngredientAndTemplate() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final net.raphimc.viabedrock.api.model.container.SmithingTableContainer smithing =
                    new net.raphimc.viabedrock.api.model.container.SmithingTableContainer(user, (byte) 1, null, new BlockPosition(0, 64, 0));
            smithing.setItemSilent(0, item(12, 1, 7));
            smithing.setItemSilent(1, item(13, 1, 8));
            smithing.setItemSilent(2, item(14, 1, 9));
            tracker.setCurrentContainer(smithing);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeSmithingApply(
                    tracker, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(5, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipe.getValue(), buffer.readUnsignedByte());
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(1, buffer.readUnsignedByte());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo equipment = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.SmithingTableInputContainer, equipment.container());
                assertEquals(51, equipment.slot());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                buffer.readUnsignedByte();
                final ItemStackRequestLayout.DecodedSlotInfo ingredient = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.SmithingTableMaterialContainer, ingredient.container());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                buffer.readUnsignedByte();
                final ItemStackRequestLayout.DecodedSlotInfo template = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.SmithingTableTemplateContainer, template.container());
                assertEquals(53, template.slot());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860TradeApplyWritesCraftRecipeTrade2ConsumeAndCreatedOutputTake() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final TradeContainer trade = new TradeContainer(user, (byte) -12, null);
            trade.setItemSilent(0, item(388, 3, 7));
            trade.setItemSilent(1, item(340, 1, 8));
            tracker.setCurrentContainer(trade);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeTradeApply(
                    tracker, 0x20000001, 3, 1, 1, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(4, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.CraftRecipe.getValue(), buffer.readUnsignedByte());
                assertEquals(0x20000001, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(1, buffer.readUnsignedByte());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(3, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo buyA = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.Trade2Ingredient1Container, buyA.container());
                assertEquals(0, buyA.slot());
                assertEquals(7, buyA.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Consume.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo buyB = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.Trade2Ingredient2Container, buyB.container());
                assertEquals(1, buyB.slot());
                assertEquals(8, buyB.stackNetworkId());
                assertEquals(ItemStackRequestActionType.Take.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.CreatedOutputContainer, source.container());
                assertEquals(50, source.slot());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netease860BeaconPaymentWritesEffectAndDestroy() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final GenericContainer beacon = new GenericContainer(user, (byte) 1, ContainerType.BEACON, null, new BlockPosition(0, 64, 0), 1);
            beacon.setItemSilent(0, item(264, 1, 7));
            tracker.setCurrentContainer(beacon);

            final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeBeaconPayment(
                    tracker, 1, 10, true, 860);
            assertFalse(encoded.unsupported());
            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(2, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.ScreenBeaconPayment.getValue(), buffer.readUnsignedByte());
                assertEquals(1, (int) BedrockTypes.VAR_INT.read(buffer));
                assertEquals(10, (int) BedrockTypes.VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.Destroy.getValue(), buffer.readUnsignedByte());
                assertEquals(1, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo payment = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.BeaconPaymentContainer, payment.container());
                assertEquals(0, payment.slot());
                assertEquals(7, payment.stackNetworkId());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
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

    private static BedrockItem damaged(final int id, final int damage, final int netId) {
        final com.viaversion.nbt.tag.CompoundTag tag = new com.viaversion.nbt.tag.CompoundTag();
        tag.putInt("Damage", damage);
        return new BedrockItem(id, (short) 0, (byte) 1, tag, new String[0], new String[0], 0, 0, netId);
    }
}
