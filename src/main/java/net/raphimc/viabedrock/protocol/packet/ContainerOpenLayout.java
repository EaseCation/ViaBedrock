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
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;

/**
 * Trailer helpers for Bedrock CONTAINER_OPEN (packet 0x2E).
 * <p>
 * Official Bedrock still ends after {@code entityUniqueId}. NetEase 860
 * ({@code V1_21_124_NETEASE}) appends an extra boolean after that unique id.
 * PacketWrapper copies leftover Bedrock bytes onto Java {@code OPEN_SCREEN},
 * so leaving the boolean unread makes 1.21.11 drop the GUI as extra data.
 */
public final class ContainerOpenLayout {

    public static final int NETEASE_TRAILER_PROTOCOL = 860;

    private ContainerOpenLayout() {
    }

    public static boolean usesNetEaseTrailer() {
        return usesNetEaseTrailer(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesNetEaseTrailer(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol >= NETEASE_TRAILER_PROTOCOL;
    }

    public static void skipTrailer(final PacketWrapper wrapper) {
        if (usesNetEaseTrailer()) {
            wrapper.read(Types.BOOLEAN);
        }
    }

    public static void skipTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesNetEaseTrailer(emulateNetEase, protocol)) {
            buffer.readBoolean();
        }
    }

    public static void writeTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesNetEaseTrailer(emulateNetEase, protocol)) {
            buffer.writeBoolean(false);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
