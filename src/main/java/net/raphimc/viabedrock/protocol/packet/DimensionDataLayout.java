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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock DIMENSION_DATA (packet 0xB4 / -76).
 * <p>
 * Official Bedrock / Nukkit-MOT protocol 975+ append {@code dimension type}
 * after {@code generator type}. NetEase 860 still encodes four fields per
 * entry: identifier, max height, min height, generator type.
 * Reading the extra varint on 860 consumes the next entry's string length
 * and aborts the remaining definitions, so overworld height can be dropped.
 */
public final class DimensionDataLayout {

    public static final int DIMENSION_TYPE_PROTOCOL = 975;

    private DimensionDataLayout() {
    }

    public static boolean usesDimensionType() {
        return usesDimensionType(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesDimensionType(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= DIMENSION_TYPE_PROTOCOL;
    }

    public static void skipDimensionType(final PacketWrapper wrapper) {
        if (usesDimensionType()) {
            wrapper.read(BedrockTypes.VAR_INT);
        }
    }

    public static Integer readDimensionType(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesDimensionType(emulateNetEase, protocol)) {
            return null;
        }
        return BedrockTypes.VAR_INT.read(buffer);
    }

    public static void skipDimensionType(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        readDimensionType(buffer, emulateNetEase, protocol);
    }

    public static void writeDimensionType(final ByteBuf buffer, final int dimensionType,
                                          final boolean emulateNetEase, final int protocol) {
        if (usesDimensionType(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, dimensionType);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
