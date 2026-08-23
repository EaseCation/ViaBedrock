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
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock FILTER_TEXT (packet 163).
 * <p>
 * MOT {@code FilterTextPacket} encodes {@code string text + boolean fromServer}.
 * The Java anvil rename packet has no Bedrock counterpart besides this echo:
 * the client sends {@code fromServer=false}, MOT copies the text back with
 * {@code fromServer=true}. The actual custom name is applied later by
 * {@code CraftRecipeOptional} using the ITEM_STACK_REQUEST filter-string trailer.
 */
public final class FilterTextLayout {

    public static final int MOT_MAX_LENGTH = 64;
    public static final int ANVIL_NAME_MAX_LENGTH = 50;

    private FilterTextLayout() {
    }

    public static void write(final ByteBuf buffer, final String text, final boolean fromServer) {
        BedrockTypes.STRING.write(buffer, text != null ? text : "");
        buffer.writeBoolean(fromServer);
    }

    public static Packet read(final ByteBuf buffer) {
        final String text = BedrockTypes.STRING.read(buffer);
        final boolean fromServer = buffer.readBoolean();
        return new Packet(text, fromServer);
    }

    public static Packet read(final PacketWrapper wrapper) {
        final String text = wrapper.read(BedrockTypes.STRING);
        final boolean fromServer = wrapper.read(com.viaversion.viaversion.api.type.Types.BOOLEAN);
        return new Packet(text, fromServer);
    }

    public static void write(final PacketWrapper wrapper, final String text, final boolean fromServer) {
        wrapper.write(BedrockTypes.STRING, text != null ? text : "");
        wrapper.write(com.viaversion.viaversion.api.type.Types.BOOLEAN, fromServer);
    }

    public static String sanitize(final String text) {
        return truncate(text, MOT_MAX_LENGTH);
    }

    public static String sanitizeAnvilName(final String text) {
        return truncate(text, ANVIL_NAME_MAX_LENGTH);
    }

    private static String truncate(final String text, final int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public record Packet(String text, boolean fromServer) {
    }
}
