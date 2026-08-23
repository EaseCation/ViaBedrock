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
 * Wire layout for Bedrock MAP_INFO_REQUEST (packet 68).
 * <p>
 * MOT {@code MapInfoRequestPacket.decode()} reads {@code varlong mapId} and, for
 * protocol &gt;= 544, an unsigned-varint pixel count followed by that many
 * {@code int + lshort} pixels. Official Java clients never send pixels; the
 * count must be a 0 unsigned varint. A little-endian int 0 leaves three extra
 * zero bytes that some servers treat as a huge pixel count and drop the request.
 */
public final class MapInfoRequestLayout {

    public static final int PIXELS_PROTOCOL = 544;

    private MapInfoRequestLayout() {
    }

    public static void write(final PacketWrapper wrapper, final long mapId) {
        write(wrapper, mapId, true, PIXELS_PROTOCOL);
    }

    public static void write(final PacketWrapper wrapper, final long mapId,
                             final boolean includePixels, final int protocol) {
        wrapper.write(BedrockTypes.VAR_LONG, mapId);
        if (includePixels && protocol >= PIXELS_PROTOCOL) {
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, 0);
        }
    }

    public static void write(final ByteBuf buffer, final long mapId,
                             final boolean includePixels, final int protocol) {
        BedrockTypes.VAR_LONG.write(buffer, mapId);
        if (includePixels && protocol >= PIXELS_PROTOCOL) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
        }
    }
}
