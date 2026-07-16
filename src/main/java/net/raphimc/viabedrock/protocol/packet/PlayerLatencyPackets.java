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
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class PlayerLatencyPackets {

    static final String MESSAGE_ID = "easecation:player_latency_v1";
    static final int MAX_PAYLOAD_LENGTH = 262_144;
    static final int MAX_ENTRIES = 4_096;

    private PlayerLatencyPackets() {
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.SCRIPT_MESSAGE, null, wrapper -> {
            wrapper.cancel();
            final String messageId = wrapper.read(BedrockTypes.STRING); // message id
            final String payload = wrapper.read(BedrockTypes.STRING); // value
            if (!MESSAGE_ID.equals(messageId)) return;

            final Map<UUID, Integer> latencies;
            try {
                latencies = parseSnapshot(payload);
            } catch (RuntimeException e) {
                final PlayerListStorage playerList = wrapper.user().get(PlayerListStorage.class);
                if (playerList.markInvalidLatencyPayloadLogged()) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Ignoring invalid player latency snapshot");
                }
                return;
            }

            final PlayerListStorage playerList = wrapper.user().get(PlayerListStorage.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final UUID localPlayerUuid = entityTracker.getClientPlayer() != null ? entityTracker.getClientPlayer().javaUuid() : null;
            final Map<UUID, Integer> updates = playerList.replaceServerLatencies(latencies, localPlayerUuid);
            if (updates.isEmpty() || wrapper.user().getProtocolInfo().getServerState() != State.PLAY) return;

            final PacketWrapper playerInfoUpdate = PacketFactory.createJavaPlayerLatencyUpdate(wrapper.user(), updates);
            playerInfoUpdate.send(BedrockProtocol.class);
            playerList.markLatenciesPublished(updates);
            ExperimentalFeatures.dispatchPlayerLatenciesUpdated(wrapper.user(), updates);
        });
    }

    static Map<UUID, Integer> parseSnapshot(final String payload) {
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Player latency snapshot is too large");
        }

        final JsonObject object = GsonUtil.getGson().fromJson(payload, JsonObject.class);
        if (object == null || object.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid player latency snapshot structure");
        }

        final Map<UUID, Integer> latencies = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            final UUID uuid;
            try {
                uuid = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!uuid.toString().equals(entry.getKey())) continue;

            final JsonElement element = entry.getValue();
            if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) continue;

            final long latency;
            try {
                latency = Long.parseLong(primitive.getAsString());
            } catch (RuntimeException e) {
                continue;
            }
            if (latency < 0L || latency > Integer.MAX_VALUE) continue;

            latencies.put(uuid, (int) latency);
        }
        return latencies;
    }
}
