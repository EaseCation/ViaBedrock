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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock INTERACT (packet 0x21).
 * <p>
 * Official Bedrock / Nukkit-MOT protocol 897+ prefixes the optional Vector3f with a
 * boolean. NetEase 860 still uses the pre-897 layout: mouseover (action 4) and, since
 * protocol 388, stop-riding (action 3) always carry a Vector3f with no presence flag.
 * Writing a Vector3f without that boolean into official 975 INTERACT is leftover data;
 * omitting it on NetEase 860 makes Nukkit over-read the next packet.
 */
public final class InteractPacketLayout {

    public static final int OPTIONAL_POSITION_PROTOCOL = 897;

    private InteractPacketLayout() {
    }

    public static boolean usesOptionalPosition() {
        return usesOptionalPosition(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesOptionalPosition(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= OPTIONAL_POSITION_PROTOCOL;
    }

    public static boolean hasPosition(final InteractPacket_Action action) {
        return action == InteractPacket_Action.InteractUpdate || action == InteractPacket_Action.StopRiding;
    }

    public static void writePosition(final PacketWrapper wrapper, final InteractPacket_Action action, final Position3f position) {
        writePosition(wrapper, action, position, emulateNetEase(), netEaseProtocol());
    }

    public static void writePosition(final PacketWrapper wrapper, final InteractPacket_Action action, final Position3f position,
                                     final boolean emulateNetEase, final int protocol) {
        if (usesOptionalPosition(emulateNetEase, protocol)) {
            final boolean present = hasPosition(action);
            wrapper.write(Types.BOOLEAN, present);
            if (present) {
                wrapper.write(BedrockTypes.POSITION_3F, position);
            }
            return;
        }
        if (hasPosition(action)) {
            wrapper.write(BedrockTypes.POSITION_3F, position);
        }
    }

    public static void writePosition(final ByteBuf buffer, final InteractPacket_Action action, final Position3f position,
                                     final boolean emulateNetEase, final int protocol) {
        if (usesOptionalPosition(emulateNetEase, protocol)) {
            final boolean present = hasPosition(action);
            buffer.writeBoolean(present);
            if (present) {
                BedrockTypes.POSITION_3F.write(buffer, position);
            }
            return;
        }
        if (hasPosition(action)) {
            BedrockTypes.POSITION_3F.write(buffer, position);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
