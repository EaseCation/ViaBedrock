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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ServerboundConfigurationPackets1_21_9;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.provider.NettyPipelineProvider;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.PacketSyncStorage;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;

import java.util.Map;

public class UnhandledPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.cancelClientbound(ClientboundBedrockPackets.SET_HEALTH); // Seems to do nothing meaningful
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.PHOTO_TRANSFER); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.SHOW_PROFILE);
        protocol.cancelClientbound(ClientboundBedrockPackets.LAB_TABLE); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.EDUCATION_SETTINGS); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.EMOTE); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.CODE_BUILDER); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.EMOTE_LIST); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.PLAYER_FOG); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.EDU_URI_RESOURCE); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.LESSON_PROGRESS); // Not relevant (Education Edition)
        protocol.cancelClientbound(ClientboundBedrockPackets.SET_HUD); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.CURRENT_STRUCTURE_FEATURE); // Useless
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_AIM_ASSIST); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_AIM_ASSIST_PRESETS); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.PLAYER_VIDEO_CAPTURE); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.GRAPHICS_OVERRIDE_PARAMETER); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.TEXTURE_SHIFT); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_SPLINE); // Not possible in Java Edition
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_AIM_ASSIST_ACTOR_PRIORITY); // Not possible in Java Edition
        // MOT packet-pool IDs that ViaBedrock's generated MinecraftPacketIds omit.
        // Without a registered type they surface as "Received unknown packet" and
        // abort the current batch. MOT 860 never constructs 301/319, but 173/197
        // exist in the pool; cancel them so leftover bytes cannot kick Java.
        protocol.cancelClientbound(ClientboundBedrockPackets.PHOTO_INFO_REQUEST);
        protocol.cancelClientbound(ClientboundBedrockPackets.CLIENT_CHEAT_ABILITY);
        protocol.cancelClientbound(ClientboundBedrockPackets.COMPRESSED_BIOME_DEFINITION_LIST);
        protocol.cancelClientbound(ClientboundBedrockPackets.SET_MOVEMENT_AUTHORITY);

        protocol.registerServerboundTransition(ServerboundConfigurationPackets1_21_9.KEEP_ALIVE, null, UnhandledPackets::handleJavaKeepAlive);
        protocol.cancelServerbound(ServerboundPackets26_1.CHAT_ACK);
        protocol.cancelServerbound(ServerboundPackets26_1.CHAT_SESSION_UPDATE);
        protocol.cancelServerbound(ServerboundPackets26_1.COOKIE_RESPONSE);
        protocol.cancelServerbound(ServerboundPackets26_1.DEBUG_SAMPLE_SUBSCRIPTION);
        protocol.registerServerbound(ServerboundPackets26_1.KEEP_ALIVE, null, UnhandledPackets::handleJavaKeepAlive);
        protocol.cancelServerbound(ServerboundPackets26_1.SET_TEST_BLOCK);
        protocol.cancelServerbound(ServerboundPackets26_1.TEST_INSTANCE_BLOCK_ACTION);
    }

    static void handleJavaKeepAlive(final PacketWrapper wrapper) {
        final long id = wrapper.read(Types.LONG);
        wrapper.cancel();
        final PacketSyncStorage packetSyncStorage = wrapper.user().get(PacketSyncStorage.class);
        if (packetSyncStorage == null) {
            return;
        }
        final Long sentNanos = packetSyncStorage.consumeKeepAlive(id);
        if (sentNanos == null) {
            return;
        }
        final long nowNanos = System.nanoTime();
        final int serverTransportLatencyMillis = Via.getManager().getProviders().get(NettyPipelineProvider.class)
                .getServerTransportLatencyMillis(wrapper.user());
        packetSyncStorage.updateLatency(nowNanos - sentNanos, serverTransportLatencyMillis);
        publishJavaPlayerLatency(wrapper, packetSyncStorage, nowNanos);
    }

    private static void publishJavaPlayerLatency(final PacketWrapper wrapper, final PacketSyncStorage packetSyncStorage, final long nowNanos) {
        if (wrapper.user().getProtocolInfo().getServerState() != State.PLAY || !packetSyncStorage.shouldPublishLatency(nowNanos)) {
            return;
        }
        final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
        if (entityTracker == null || entityTracker.getClientPlayer() == null) {
            return;
        }
        final java.util.UUID javaUuid = entityTracker.getClientPlayer().javaUuid();
        final PlayerListStorage playerListStorage = wrapper.user().get(PlayerListStorage.class);
        if (playerListStorage == null || !playerListStorage.containsPlayer(javaUuid)) {
            return;
        }
        PacketFactory.createJavaPlayerLatencyUpdate(wrapper.user(), javaUuid, packetSyncStorage.latencyMillis()).send(BedrockProtocol.class);
        packetSyncStorage.markLatencyPublished(nowNanos);
        ExperimentalFeatures.dispatchPlayerLatenciesUpdated(wrapper.user(), Map.of(javaUuid, packetSyncStorage.latencyMillis()));
    }

}
