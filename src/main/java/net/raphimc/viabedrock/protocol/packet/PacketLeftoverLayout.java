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
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;

/**
 * PacketWrapper copies any unread Bedrock bytes onto the rewritten Java packet.
 * NetEase 860 trailers (and any under-read official payload) then kick 1.21.11
 * as extra data on mapped S2C such as OPEN_SCREEN / CONTAINER_SET_CONTENT.
 * <p>
 * Skip only the remaining input bytes. {@link PacketWrapper#clearInputBuffer()}
 * also wipes mapped {@code readableObjects}, which breaks PacketHandlers that
 * used {@code map()} before the leftover discard.
 */
public final class PacketLeftoverLayout {

    private PacketLeftoverLayout() {
    }

    public static void discardUnreadInput(final PacketWrapper wrapper) {
        if (wrapper instanceof PacketWrapperImpl packetWrapper) {
            discardUnreadInput(packetWrapper.getInputBuffer());
        }
    }

    public static void discardUnreadInput(final ByteBuf input) {
        if (input != null && input.isReadable()) {
            input.skipBytes(input.readableBytes());
        }
    }
}
