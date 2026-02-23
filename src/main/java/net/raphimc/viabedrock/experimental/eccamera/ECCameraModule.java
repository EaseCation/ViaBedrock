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
package net.raphimc.viabedrock.experimental.eccamera;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.pyrpc.PyRpcDispatcherModule;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.Set;

/**
 * ECCamera module for forwarding PY_RPC camera path events to the ECCameraClient mod.
 */
public class ECCameraModule implements FeatureModule {

    public static final String CONFIRM_CHANNEL = "eccamera:confirm";
    public static final String CHANNEL = "eccamera:data";

    // ECCameraClient PayloadType ordinals: CONFIRM=0, PY_RPC_DATA=1
    private static final int PY_RPC_DATA_ORDINAL = 1;
    private static final int CONFIRM_ORDINAL = 0;

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        PyRpcDispatcherModule.registerConsumer(CONFIRM_CHANNEL, CHANNEL, PY_RPC_DATA_ORDINAL);
    }

    @Override
    public void onChannelRegistered(final UserConnection user, final Set<String> channels) {
        if (channels.contains(CONFIRM_CHANNEL)) {
            final PacketWrapper msg = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, user);
            msg.write(Types.STRING, CHANNEL);
            msg.write(Types.INT, CONFIRM_ORDINAL);
            msg.send(BedrockProtocol.class);
        }
    }

}
