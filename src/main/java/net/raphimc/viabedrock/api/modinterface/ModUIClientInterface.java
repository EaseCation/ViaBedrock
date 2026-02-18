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
package net.raphimc.viabedrock.api.modinterface;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.concurrent.atomic.AtomicInteger;

public class ModUIClientInterface {

    private static final AtomicInteger MSG_ID_COUNTER = new AtomicInteger(1);

    public static final String CONFIRM_CHANNEL = "moduiclient:confirm";
    public static final String CHANNEL = "moduiclient:data";

    public static void confirmPresence(final UserConnection user) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL); // Channel
        pluginMessage.write(Types.INT, PayloadType.CONFIRM.ordinal()); // Type
        pluginMessage.send(BedrockProtocol.class);
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.PY_RPC, ClientboundPackets1_21_11.CUSTOM_PAYLOAD, wrapper -> {
            final byte[] msgpackData = wrapper.read(BedrockTypes.BYTE_ARRAY); // MsgPack data
            wrapper.read(BedrockTypes.INT_LE); // msgId (not needed for S2C forwarding)

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.STRING, CHANNEL); // Channel
            wrapper.write(Types.INT, PayloadType.PY_RPC_DATA.ordinal()); // Type
            wrapper.write(Types.REMAINING_BYTES, msgpackData); // Raw MsgPack bytes
        });
    }

    public static void handleC2S(final PacketWrapper wrapper) {
        final int type = wrapper.read(Types.INT);
        if (type == PayloadType.PY_RPC_DATA.ordinal()) {
            final byte[] msgpackData = wrapper.read(Types.REMAINING_BYTES);
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(msgpackData.length, 200); i++) {
                hex.append(String.format("%02x ", msgpackData[i] & 0xFF));
            }
            ViaBedrock.getPlatform().getLogger().info("[ModUIClient C2S] Forwarding PY_RPC, " + msgpackData.length + " bytes: " + hex.toString().trim());
            try {
                final PacketWrapper pyRpc = PacketWrapper.create(ServerboundBedrockPackets.PY_RPC, wrapper.user());
                pyRpc.write(BedrockTypes.BYTE_ARRAY, msgpackData); // MsgPack data
                pyRpc.write(BedrockTypes.INT_LE, MSG_ID_COUNTER.getAndIncrement()); // msgId (must be unique per packet)
                pyRpc.sendToServer(BedrockProtocol.class);
            } catch (final Exception e) {
                ViaBedrock.getPlatform().getLogger().severe("[ModUIClient C2S] Failed to forward PY_RPC: " + e.getMessage());
            }
        } else {
            ViaBedrock.getPlatform().getLogger().warning("[ModUIClient C2S] Unknown payload type: " + type);
        }
    }

    private enum PayloadType {
        CONFIRM, PY_RPC_DATA
    }

}
