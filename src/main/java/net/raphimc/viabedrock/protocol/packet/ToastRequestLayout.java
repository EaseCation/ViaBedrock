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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock TOAST_REQUEST (packet 186).
 * <p>
 * Nukkit-MOT {@code ToastRequestPacket.encode()} writes {@code string title + string content}
 * with no protocol-version fork. Java 1.21.11 has no native toast packet, so ViaBedrock
 * projects this onto {@code SET_ACTION_BAR_TEXT}. MOT {@code Player.sendToast()} already
 * falls back to {@code sendTitle(title, content)} below protocol 527; action-bar is the
 * closest Java HUD that does not steal the current title/subtitle.
 */
public final class ToastRequestLayout {

    private ToastRequestLayout() {
    }

    public static void write(final ByteBuf buffer, final String title, final String content) {
        BedrockTypes.STRING.write(buffer, title != null ? title : "");
        BedrockTypes.STRING.write(buffer, content != null ? content : "");
    }

    public static Packet read(final ByteBuf buffer) {
        final String title = BedrockTypes.STRING.read(buffer);
        final String content = BedrockTypes.STRING.read(buffer);
        return new Packet(title, content);
    }

    public static Packet read(final PacketWrapper wrapper) {
        final String title = wrapper.read(BedrockTypes.STRING);
        final String content = wrapper.read(BedrockTypes.STRING);
        return new Packet(title, content);
    }

    /**
     * Combines title and content the same way MOT {@code sendTitle(title, content)} would
     * when toast is unavailable: empty/blank parts are dropped, remaining parts joined
     * with a space. Both empty yields a single space so Java still receives a visible
     * (blank) action-bar packet, matching MOT's {@code " "} fallback.
     */
    public static String actionBarText(final String title, final String content) {
        final String trimmedTitle = title != null ? title.strip() : "";
        final String trimmedContent = content != null ? content.strip() : "";
        if (trimmedTitle.isEmpty() && trimmedContent.isEmpty()) {
            return " ";
        }
        if (trimmedTitle.isEmpty()) {
            return trimmedContent;
        }
        if (trimmedContent.isEmpty()) {
            return trimmedTitle;
        }
        return trimmedTitle + " " + trimmedContent;
    }

    public record Packet(String title, String content) {
    }
}
