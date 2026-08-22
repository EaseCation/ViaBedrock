/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock ITEM_STACK_RESPONSE (packet 0x94).
 * <p>
 * Official 975 and NetEase 860 both still encode: result byte, request id,
 * then on OK a container array. Each container is a required FullContainerName
 * on 712+, followed by slot entries whose stack-network id is a signed varint.
 * Protocol 2168 adds optional container/stack-id booleans that must not be
 * read on 860 or official 975, or the next click request is parsed as leftover.
 */
public final class ItemStackResponseLayout {

    public static final int FULL_CONTAINER_NAME_PROTOCOL = 712;
    public static final int CUSTOM_NAME_PROTOCOL = 422;
    public static final int FILTERED_CUSTOM_NAME_PROTOCOL = 766;
    public static final int DURABILITY_CORRECTION_PROTOCOL = 428;
    public static final int OPTIONAL_CONTAINER_PROTOCOL = 2168;
    public static final byte RESULT_OK = 0;

    private ItemStackResponseLayout() {
    }

    public static boolean usesOptionalContainerEntries(final boolean emulateNetEase, final int protocol) {
        return protocol >= OPTIONAL_CONTAINER_PROTOCOL;
    }

    public static boolean usesFullContainerName(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= FULL_CONTAINER_NAME_PROTOCOL;
    }

    public static boolean usesCustomName(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= CUSTOM_NAME_PROTOCOL;
    }

    public static boolean usesFilteredCustomName(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= FILTERED_CUSTOM_NAME_PROTOCOL;
    }

    public static boolean usesDurabilityCorrection(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= DURABILITY_CORRECTION_PROTOCOL;
    }

    public static DecodedResponse skip(final PacketWrapper wrapper) {
        return skip(wrapper, emulateNetEase(), encodeProtocol());
    }

    public static DecodedResponse skip(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        boolean anyRejected = false;
        for (int i = 0; i < count; i++) {
            if (!skipEntry(wrapper, emulateNetEase, protocol)) {
                anyRejected = true;
            }
        }
        return new DecodedResponse(count, anyRejected);
    }

    public static DecodedResponse skip(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        boolean anyRejected = false;
        for (int i = 0; i < count; i++) {
            if (!skipEntry(buffer, emulateNetEase, protocol)) {
                anyRejected = true;
            }
        }
        return new DecodedResponse(count, anyRejected);
    }

    public static boolean skipEntry(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final byte result = wrapper.read(Types.BYTE);
        wrapper.read(BedrockTypes.VAR_INT); // request id
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            wrapper.read(Types.BOOLEAN); // always true on current Nukkit
            if (!wrapper.read(Types.BOOLEAN)) {
                return result == RESULT_OK;
            }
        } else if (result != RESULT_OK) {
            return false;
        }
        skipContainers(wrapper, emulateNetEase, protocol);
        return result == RESULT_OK;
    }

    public static boolean skipEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final byte result = buffer.readByte();
        BedrockTypes.VAR_INT.read(buffer); // request id
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.readBoolean(); // always true on current Nukkit
            if (!buffer.readBoolean()) {
                return result == RESULT_OK;
            }
        } else if (result != RESULT_OK) {
            return false;
        }
        skipContainers(buffer, emulateNetEase, protocol);
        return result == RESULT_OK;
    }

    public static void skipContainers(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int containerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < containerCount; i++) {
            if (usesFullContainerName(emulateNetEase, protocol)) {
                wrapper.read(BedrockTypes.FULL_CONTAINER_NAME);
            } else {
                wrapper.read(Types.BYTE);
            }
            skipSlots(wrapper, emulateNetEase, protocol);
        }
    }

    public static void skipContainers(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int containerCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        for (int i = 0; i < containerCount; i++) {
            if (usesFullContainerName(emulateNetEase, protocol)) {
                BedrockTypes.FULL_CONTAINER_NAME.read(buffer);
            } else {
                buffer.readByte();
            }
            skipSlots(buffer, emulateNetEase, protocol);
        }
    }

    public static void skipSlots(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int slotCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < slotCount; i++) {
            wrapper.read(Types.BYTE); // slot
            wrapper.read(Types.BYTE); // hotbar slot
            wrapper.read(Types.BYTE); // count
            if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
                wrapper.read(Types.BOOLEAN); // always true on current Nukkit
                if (wrapper.read(Types.BOOLEAN)) {
                    wrapper.read(BedrockTypes.VAR_INT);
                }
            } else {
                wrapper.read(BedrockTypes.VAR_INT); // stack network id
            }
            if (usesCustomName(emulateNetEase, protocol)) {
                wrapper.read(BedrockTypes.STRING);
            }
            if (usesFilteredCustomName(emulateNetEase, protocol)) {
                wrapper.read(BedrockTypes.STRING);
            }
            if (usesDurabilityCorrection(emulateNetEase, protocol)) {
                wrapper.read(BedrockTypes.VAR_INT);
            }
        }
    }

    public static void skipSlots(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int slotCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        for (int i = 0; i < slotCount; i++) {
            buffer.readByte(); // slot
            buffer.readByte(); // hotbar slot
            buffer.readByte(); // count
            if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
                buffer.readBoolean(); // always true on current Nukkit
                if (buffer.readBoolean()) {
                    BedrockTypes.VAR_INT.read(buffer);
                }
            } else {
                BedrockTypes.VAR_INT.read(buffer); // stack network id
            }
            if (usesCustomName(emulateNetEase, protocol)) {
                BedrockTypes.STRING.read(buffer);
            }
            if (usesFilteredCustomName(emulateNetEase, protocol)) {
                BedrockTypes.STRING.read(buffer);
            }
            if (usesDurabilityCorrection(emulateNetEase, protocol)) {
                BedrockTypes.VAR_INT.read(buffer);
            }
        }
    }

    public static void writeOkEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol,
                                    final int requestId, final int containerWireId, final int slot,
                                    final int count, final int stackNetworkId) {
        buffer.writeByte(RESULT_OK);
        BedrockTypes.VAR_INT.write(buffer, requestId);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
        if (usesFullContainerName(emulateNetEase, protocol)) {
            buffer.writeByte(containerWireId & 0xFF);
            buffer.writeBoolean(false);
        } else {
            buffer.writeByte(containerWireId & 0xFF);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
        buffer.writeByte(slot);
        buffer.writeByte(slot);
        buffer.writeByte(count);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
            BedrockTypes.VAR_INT.write(buffer, stackNetworkId);
        } else {
            BedrockTypes.VAR_INT.write(buffer, stackNetworkId);
        }
        if (usesCustomName(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, "");
        }
        if (usesFilteredCustomName(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, "");
        }
        if (usesDurabilityCorrection(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, 0);
        }
    }

    public static void writeRejectedEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol, final int requestId) {
        buffer.writeByte(1);
        BedrockTypes.VAR_INT.write(buffer, requestId);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.writeBoolean(true);
            buffer.writeBoolean(false);
        }
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
        return 975;
    }

    public record DecodedResponse(int entryCount, boolean anyRejected) {
    }

}
