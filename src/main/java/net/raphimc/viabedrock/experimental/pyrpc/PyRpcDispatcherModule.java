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
package net.raphimc.viabedrock.experimental.pyrpc;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.storage.GlowProjectionTracker;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared JE PY_RPC transport. Bedrock PY_RPC bytes are forwarded unchanged
 * through floodgate:netease; JE C2S bytes are wrapped back into PY_RPC.
 */
public class PyRpcDispatcherModule implements FeatureModule {

    public static final String CHANNEL = "floodgate:netease";

    private static final AtomicInteger MSG_ID_COUNTER = new AtomicInteger(1);

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.PY_RPC, null, wrapper -> {
            wrapper.cancel();
            final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // MsgPack data
            wrapper.read(BedrockTypes.INT_LE); // msgId (not needed for S2C forwarding)

            final GlowProjectionTracker glow = wrapper.user().get(GlowProjectionTracker.class);
            if (glow != null) {
                GlowModEventCodec.decode(data).ifPresent(glow::apply);
            }

            final String routedChannel = ExperimentalFeatures.dispatchResolveClientboundPyRpcChannel(data);
            final String targetChannel = routedChannel != null ? routedChannel : CHANNEL;
            final ChannelStorage channels = wrapper.user().get(ChannelStorage.class);
            if (!channels.hasChannel(targetChannel)) {
                return;
            }

            final PacketWrapper msg = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, wrapper.user());
            msg.write(Types.STRING, targetChannel);
            msg.write(Types.REMAINING_BYTES, data);
            msg.scheduleSend(BedrockProtocol.class);
        });
    }

    @Override
    public boolean handleCustomPayload(final String channel, final PacketWrapper wrapper) {
        if (!channel.equals(CHANNEL)) {
            return false;
        }

        try {
            final byte[] msgpackData = wrapper.read(Types.REMAINING_BYTES);
            final PacketWrapper pyRpc = PacketWrapper.create(ServerboundBedrockPackets.PY_RPC, wrapper.user());
            pyRpc.write(BedrockTypes.BYTE_ARRAY, msgpackData);
            pyRpc.write(BedrockTypes.INT_LE, MSG_ID_COUNTER.getAndIncrement());
            pyRpc.sendToServer(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().severe("[PY_RPC] Failed to forward JE C2S payload: " + e.getMessage());
        }
        return true;
    }

    @Override
    public void onStorageRegistration(final com.viaversion.viaversion.api.connection.UserConnection user) {
        user.put(new GlowProjectionTracker(user));
    }

    @Override
    public void onEntityAdded(final com.viaversion.viaversion.api.connection.UserConnection user, final Entity entity) {
        final GlowProjectionTracker glow = user.get(GlowProjectionTracker.class);
        if (glow != null) {
            glow.onEntityAdded(entity);
        }
    }

    @Override
    public void onEntityRemoved(final com.viaversion.viaversion.api.connection.UserConnection user, final Entity entity) {
        final GlowProjectionTracker glow = user.get(GlowProjectionTracker.class);
        if (glow != null) {
            glow.onEntityRemoved(entity);
        }
    }

}
