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
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared PY_RPC packet dispatcher. Owns the PY_RPC clientbound handler and
 * broadcasts raw MsgPack bytes to all confirmed consumer channels.
 * <p>
 * Consumers register via {@link #registerConsumer} before packet registration occurs.
 */
public class PyRpcDispatcherModule implements FeatureModule {

    public record PyRpcConsumer(String confirmChannel, String dataChannel, int pyRpcDataOrdinal) {}

    private static final List<PyRpcConsumer> consumers = new ArrayList<>();

    /**
     * Register a PY_RPC consumer. Must be called during module onPacketRegistration
     * (after this module is registered but before the first PY_RPC packet arrives).
     */
    public static void registerConsumer(final String confirmChannel, final String dataChannel, final int pyRpcDataOrdinal) {
        consumers.add(new PyRpcConsumer(confirmChannel, dataChannel, pyRpcDataOrdinal));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.PY_RPC, ClientboundPackets1_21_11.CUSTOM_PAYLOAD, wrapper -> {
            final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // MsgPack data
            wrapper.read(BedrockTypes.INT_LE); // msgId (not needed for S2C forwarding)

            final ChannelStorage channels = wrapper.user().get(ChannelStorage.class);
            boolean sentFirst = false;

            for (final PyRpcConsumer consumer : consumers) {
                if (!channels.hasChannel(consumer.confirmChannel())) continue;

                if (!sentFirst) {
                    // Reuse original wrapper for the first consumer
                    wrapper.write(Types.STRING, consumer.dataChannel());
                    wrapper.write(Types.INT, consumer.pyRpcDataOrdinal());
                    wrapper.write(Types.REMAINING_BYTES, data);
                    sentFirst = true;
                } else {
                    // Create additional packet for subsequent consumers
                    final PacketWrapper extra = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, wrapper.user());
                    extra.write(Types.STRING, consumer.dataChannel());
                    extra.write(Types.INT, consumer.pyRpcDataOrdinal());
                    extra.write(Types.REMAINING_BYTES, data);
                    extra.send(BedrockProtocol.class);
                }
            }

            if (!sentFirst) {
                wrapper.cancel(); // No confirmed consumers
            }
        });
    }

}
