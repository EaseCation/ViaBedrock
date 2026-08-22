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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.model.ContainerSlotTypeLayout;

/**
 * Wire-layout helpers for Bedrock ITEM_STACK_REQUEST (packet 0x93).
 * <p>
 * Nukkit only switches action types to unsigned varints (plus a trailing byte)
 * and stack-network ids to little-endian ints at protocol 2168. Official 975 and
 * NetEase / Nukkit-MOT 860 both still use the pre-2168 layout: action type as a
 * raw byte and stack-network id as a signed varint.
 * <p>
 * Writing the 2168 shape into a 860/975 SAI server makes Nukkit fail the request
 * as an unknown action type, so Java clicks never leave the cursor. Official 975
 * must keep the byte action-type layout; only protocol 2168+ uses the new shape.
 */
public final class ItemStackRequestLayout {

    public static final int UNSIGNED_ACTION_TYPE_PROTOCOL = 2168;
    public static final int OPTIONAL_DYNAMIC_ID_PROTOCOL = 729;
    public static final int FILTER_STRINGS_PROTOCOL = 422;
    public static final int TEXT_ORIGIN_PROTOCOL = 554;

    private ItemStackRequestLayout() {
    }

    public static boolean usesUnsignedActionType() {
        return usesUnsignedActionType(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesUnsignedActionType(final boolean emulateNetEase, final int protocol) {
        // Nukkit only switches to unsigned-varint action types at protocol 2168.
        // Official 975 and NetEase 860 both still write a raw byte.
        return protocol >= UNSIGNED_ACTION_TYPE_PROTOCOL;
    }

    public static boolean usesLittleEndianStackNetworkId() {
        return usesLittleEndianStackNetworkId(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesLittleEndianStackNetworkId(final boolean emulateNetEase, final int protocol) {
        return usesUnsignedActionType(emulateNetEase, protocol);
    }

    public static boolean usesOptionalDynamicId() {
        return usesOptionalDynamicId(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesOptionalDynamicId(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= OPTIONAL_DYNAMIC_ID_PROTOCOL;
    }

    public static void writeActionType(final ByteBuf buffer, final ItemStackRequestActionType type) {
        writeActionType(buffer, type, emulateNetEase(), netEaseProtocol());
    }

    public static void writeActionType(final ByteBuf buffer, final ItemStackRequestActionType type,
                                       final boolean emulateNetEase, final int protocol) {
        if (usesUnsignedActionType(emulateNetEase, protocol)) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, type.getValue());
            buffer.writeByte(0);
            return;
        }
        buffer.writeByte(type.getValue());
    }

    public static void writeSlotInfo(final ByteBuf buffer, final ContainerEnumName container, final int slot, final int stackNetworkId) {
        writeSlotInfo(buffer, container, slot, stackNetworkId, null, emulateNetEase(), netEaseProtocol());
    }

    public static void writeSlotInfo(final ByteBuf buffer, final ContainerEnumName container, final int slot, final int stackNetworkId,
                                     final Integer dynamicId, final boolean emulateNetEase, final int protocol) {
        buffer.writeByte(ContainerSlotTypeLayout.toWire(container, ContainerSlotTypeLayout.usesNetEaseIdShift(emulateNetEase)));
        if (usesOptionalDynamicId(emulateNetEase, protocol)) {
            buffer.writeBoolean(dynamicId != null);
            if (dynamicId != null) {
                buffer.writeIntLE(dynamicId);
            }
        }
        buffer.writeByte(slot);
        writeStackNetworkId(buffer, stackNetworkId, emulateNetEase, protocol);
    }

    public static void writeStackNetworkId(final ByteBuf buffer, final int stackNetworkId) {
        writeStackNetworkId(buffer, stackNetworkId, emulateNetEase(), netEaseProtocol());
    }

    public static void writeStackNetworkId(final ByteBuf buffer, final int stackNetworkId,
                                           final boolean emulateNetEase, final int protocol) {
        if (usesLittleEndianStackNetworkId(emulateNetEase, protocol)) {
            buffer.writeIntLE(stackNetworkId);
            return;
        }
        BedrockTypes.VAR_INT.write(buffer, stackNetworkId);
    }

    public static void writeRequestTrailer(final ByteBuf buffer) {
        writeRequestTrailer(buffer, emulateNetEase(), netEaseProtocol());
    }

    public static void writeRequestTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!emulateNetEase || protocol >= FILTER_STRINGS_PROTOCOL) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
        }
        if (!emulateNetEase || protocol >= TEXT_ORIGIN_PROTOCOL) {
            buffer.writeIntLE(TextProcessingEventOrigin.BlockActorDataText.getValue());
        }
    }

    public static void writeTransfer(final ByteBuf buffer, final ItemStackRequestActionType type, final int count,
                                     final SlotInfo source, final SlotInfo destination,
                                     final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, type, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
        writeSlotInfo(buffer, destination.container(), destination.slot(), destination.stackNetworkId(), destination.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeSwap(final ByteBuf buffer, final SlotInfo source, final SlotInfo destination,
                                 final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Swap, emulateNetEase, protocol);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
        writeSlotInfo(buffer, destination.container(), destination.slot(), destination.stackNetworkId(), destination.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeDrop(final ByteBuf buffer, final int count, final SlotInfo source, final boolean randomly,
                                 final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Drop, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
        buffer.writeBoolean(randomly);
    }

    public static void writeDestroy(final ByteBuf buffer, final int count, final SlotInfo source,
                                    final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Destroy, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeConsume(final ByteBuf buffer, final int count, final SlotInfo source,
                                    final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Consume, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeCraftRecipe(final ByteBuf buffer, final int recipeNetworkId, final int timesCrafted,
                                        final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftRecipe, emulateNetEase, protocol);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, recipeNetworkId);
        if (!emulateNetEase || protocol >= 712) {
            buffer.writeByte(timesCrafted);
        }
    }

    public static void writeCraftCreative(final ByteBuf buffer, final int creativeItemNetworkId, final int timesCrafted,
                                          final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftCreative, emulateNetEase, protocol);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, creativeItemNetworkId);
        if (!emulateNetEase || protocol >= 712) {
            buffer.writeByte(timesCrafted);
        }
    }

    public static DecodedRequestTrailer readRequestTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int filterCount = (!emulateNetEase || protocol >= FILTER_STRINGS_PROTOCOL)
                ? BedrockTypes.UNSIGNED_VAR_INT.read(buffer)
                : 0;
        Integer origin = null;
        if (!emulateNetEase || protocol >= TEXT_ORIGIN_PROTOCOL) {
            origin = buffer.readIntLE();
        }
        return new DecodedRequestTrailer(filterCount, origin);
    }

    public static DecodedSlotInfo readSlotInfo(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int wireId = buffer.readUnsignedByte();
        final ContainerEnumName container = ContainerSlotTypeLayout.fromWire(wireId, ContainerSlotTypeLayout.usesNetEaseIdShift(emulateNetEase));
        Integer dynamicId = null;
        if (usesOptionalDynamicId(emulateNetEase, protocol) && buffer.readBoolean()) {
            dynamicId = buffer.readIntLE();
        }
        final int slot = buffer.readUnsignedByte();
        final int stackNetworkId = usesLittleEndianStackNetworkId(emulateNetEase, protocol)
                ? buffer.readIntLE()
                : BedrockTypes.VAR_INT.read(buffer);
        return new DecodedSlotInfo(container, slot, stackNetworkId, dynamicId);
    }

    public static FullContainerName containerName(final ContainerEnumName container, final Integer dynamicId) {
        return new FullContainerName(container, dynamicId);
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig() != null ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : 0;
    }

    public record SlotInfo(ContainerEnumName container, int slot, int stackNetworkId, Integer dynamicId) {
        public SlotInfo(final ContainerEnumName container, final int slot, final int stackNetworkId) {
            this(container, slot, stackNetworkId, null);
        }
    }

    public record DecodedSlotInfo(ContainerEnumName container, int slot, int stackNetworkId, Integer dynamicId) {
    }

    public record DecodedRequestTrailer(int filterStringCount, Integer textOrigin) {
    }
}
