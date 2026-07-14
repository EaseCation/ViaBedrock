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
package net.raphimc.viabedrock.api.util;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketFactoryTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void createsSinglePlayerLatencyUpdateInProtocolOrder() {
        final UUID uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        final PacketWrapper wrapper = new PacketWrapperImpl(ClientboundPackets26_1.PLAYER_INFO_UPDATE, Unpooled.EMPTY_BUFFER, this.user);
        PacketFactory.writeJavaPlayerLatencyUpdate(wrapper, uuid, 87);

        assertEquals(ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper.getPacketType());
        assertEquals(BitSets.create(8, PlayerInfoUpdateAction.UPDATE_LATENCY), wrapper.get(Types.PROFILE_ACTIONS_ENUM1_21_4, 0));
        assertEquals(1, wrapper.get(Types.VAR_INT, 0));
        assertEquals(uuid, wrapper.get(Types.UUID, 0));
        assertEquals(87, wrapper.get(Types.VAR_INT, 1));
    }

    @Test
    void createsBatchPlayerLatencyUpdateInProtocolOrder() {
        final UUID firstUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        final UUID secondUuid = UUID.fromString("87654321-4321-8765-cba9-876543210fed");
        final Map<UUID, Integer> latencies = new LinkedHashMap<>();
        latencies.put(firstUuid, 87);
        latencies.put(secondUuid, -1);
        final PacketWrapper wrapper = new PacketWrapperImpl(ClientboundPackets26_1.PLAYER_INFO_UPDATE, Unpooled.EMPTY_BUFFER, this.user);

        PacketFactory.writeJavaPlayerLatencyUpdate(wrapper, latencies);

        assertEquals(BitSets.create(8, PlayerInfoUpdateAction.UPDATE_LATENCY), wrapper.get(Types.PROFILE_ACTIONS_ENUM1_21_4, 0));
        assertEquals(2, wrapper.get(Types.VAR_INT, 0));
        assertEquals(firstUuid, wrapper.get(Types.UUID, 0));
        assertEquals(87, wrapper.get(Types.VAR_INT, 1));
        assertEquals(secondUuid, wrapper.get(Types.UUID, 1));
        assertEquals(-1, wrapper.get(Types.VAR_INT, 2));
    }

}
