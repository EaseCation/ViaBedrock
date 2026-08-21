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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextPacketType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock TEXT (packet 0x09).
 * <p>
 * Official Bedrock 1.26 / protocol 924+ and Nukkit-MOT's 897/924 branches encode:
 * {@code localize, category(0/1/2), type, payload..., xuid, platformId, optional filtered}.
 * <p>
 * NetEase / Nukkit-MOT protocol 860 still uses the pre-897 layout:
 * {@code type, localize, payload..., xuid, platformId, filtered string}.
 * Treating that packet as 924+ consumes the UTF-8 length as the type id and then
 * explodes on the trailing xuid/platform/filtered reads (the join-welcome kick).
 */
public final class TextPacketLayout {

    public static final int NETEASE_FILTERED_STRING_PROTOCOL = 685;
    public static final int NETEASE_UNKNOWN_TAIL_PROTOCOL = 410;
    public static final int CATEGORY_LAYOUT_PROTOCOL = 897;

    private TextPacketLayout() {
    }

    public static boolean isLegacyTypeFirstLayout() {
        return isLegacyTypeFirstLayout(emulateNetEase(), netEaseProtocol());
    }

    public static boolean isLegacyTypeFirstLayout(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < CATEGORY_LAYOUT_PROTOCOL;
    }

    public static boolean usesRequiredFilteredString() {
        return usesRequiredFilteredString(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesRequiredFilteredString(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol >= NETEASE_FILTERED_STRING_PROTOCOL && protocol < CATEGORY_LAYOUT_PROTOCOL;
    }

    public static boolean usesNetEaseUnknownTail(final TextPacketType type) {
        return usesNetEaseUnknownTail(emulateNetEase(), netEaseProtocol(), type);
    }

    public static boolean usesNetEaseUnknownTail(final boolean emulateNetEase, final int protocol, final TextPacketType type) {
        return emulateNetEase
                && protocol >= NETEASE_UNKNOWN_TAIL_PROTOCOL
                && (type == TextPacketType.chat || type == TextPacketType.popup);
    }

    public static TextHeader readHeader(final ByteBuf buffer, final boolean legacyTypeFirst) {
        if (legacyTypeFirst) {
            final short rawType = buffer.readUnsignedByte();
            final boolean localize = buffer.readBoolean();
            return new TextHeader(localize, rawType);
        }
        final boolean localize = buffer.readBoolean();
        buffer.readUnsignedByte(); // category: 0=message only, 1=author+message, 2=message+params
        final short rawType = buffer.readUnsignedByte();
        return new TextHeader(localize, rawType);
    }

    public static void writeHeader(final ByteBuf buffer, final boolean localize, final TextPacketType type, final boolean legacyTypeFirst) {
        if (legacyTypeFirst) {
            buffer.writeByte(type.getValue());
            buffer.writeBoolean(localize || type == TextPacketType.translate);
            return;
        }
        buffer.writeBoolean(localize || type == TextPacketType.translate);
        buffer.writeByte(categoryOf(type));
        buffer.writeByte(type.getValue());
    }

    public static byte categoryOf(final TextPacketType type) {
        return switch (type) {
            case chat, whisper, announcement -> (byte) 1;
            case translate, popup, jukeboxPopup -> (byte) 2;
            default -> (byte) 0;
        };
    }

    public static DecodedText readPacket(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final boolean legacyTypeFirst = isLegacyTypeFirstLayout(emulateNetEase, protocol);
        final TextHeader header = readHeader(buffer, legacyTypeFirst);
        final TextPacketType type = TextPacketType.getByValue(header.rawType());
        if (type == null) {
            throw new IllegalArgumentException("Unknown TextPacketType: " + header.rawType());
        }

        final String sourceName;
        final String message;
        final String[] parameters;
        switch (type) {
            case chat, whisper, announcement -> {
                sourceName = BedrockTypes.STRING.read(buffer);
                message = BedrockTypes.STRING.read(buffer);
                parameters = new String[0];
            }
            case translate, popup, jukeboxPopup -> {
                sourceName = "";
                message = BedrockTypes.STRING.read(buffer);
                parameters = BedrockTypes.STRING_ARRAY.read(buffer);
            }
            default -> {
                sourceName = "";
                message = BedrockTypes.STRING.read(buffer);
                parameters = new String[0];
            }
        }

        final String xuid = BedrockTypes.STRING.read(buffer);
        final String platformOnlineId = BedrockTypes.STRING.read(buffer);
        final String filteredMessage = usesRequiredFilteredString(emulateNetEase, protocol)
                ? BedrockTypes.STRING.read(buffer)
                : BedrockTypes.OPTIONAL_STRING.read(buffer);
        String unknownNe = "";
        if (usesNetEaseUnknownTail(emulateNetEase, protocol, type) && buffer.isReadable()) {
            unknownNe = BedrockTypes.STRING.read(buffer);
        }
        if (buffer.isReadable()) {
            buffer.skipBytes(buffer.readableBytes());
        }
        return new DecodedText(header.localize(), type, sourceName, message, parameters, xuid, platformOnlineId, filteredMessage, unknownNe);
    }

    public static void writeTrailer(final ByteBuf buffer, final TextPacketType type, final String xuid,
                                    final boolean emulateNetEase, final int protocol) {
        BedrockTypes.STRING.write(buffer, xuid == null ? "" : xuid);
        BedrockTypes.STRING.write(buffer, "");
        if (usesRequiredFilteredString(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, "");
        } else {
            BedrockTypes.OPTIONAL_STRING.write(buffer, null);
        }
        if (usesNetEaseUnknownTail(emulateNetEase, protocol, type)) {
            BedrockTypes.STRING.write(buffer, "");
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }

    public record TextHeader(boolean localize, short rawType) {
    }

    public record DecodedText(boolean localize, TextPacketType type, String sourceName, String message,
                              String[] parameters, String xuid, String platformOnlineId,
                              String filteredMessage, String unknownNe) {
    }
}
