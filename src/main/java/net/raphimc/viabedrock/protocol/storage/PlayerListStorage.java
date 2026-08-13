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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.util.Pair;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerListStorage implements StorableObject {

    private final Map<UUID, Pair<Long, String>> playerList = new HashMap<>();
    private final Map<UUID, JavaProfile> javaProfiles = new LinkedHashMap<>();
    private final Map<UUID, Integer> serverLatencies = new HashMap<>();
    private final Map<UUID, Integer> publishedLatencies = new HashMap<>();
    private boolean invalidLatencyPayloadLogged;

    public Pair<Long, String> addPlayer(final UUID uuid, final long entityUniqueId, final String name) {
        return this.playerList.put(uuid, new Pair<>(entityUniqueId, name));
    }

    public Pair<Long, String> removePlayer(final UUID uuid) {
        this.publishedLatencies.remove(uuid);
        this.javaProfiles.remove(uuid);
        return this.playerList.remove(uuid);
    }

    public boolean containsPlayer(final UUID uuid) {
        return this.playerList.containsKey(uuid);
    }

    public Pair<Long, String> getPlayer(final UUID uuid) {
        return this.playerList.get(uuid);
    }

    public List<Entry> entries() {
        return this.playerList.entrySet().stream()
                .map(entry -> new Entry(entry.getKey(), entry.getValue().key(), entry.getValue().value()))
                .toList();
    }

    public void putJavaProfile(final JavaProfile profile) {
        this.javaProfiles.put(profile.uuid(), profile);
    }

    public void putJavaProfileIfAbsent(final JavaProfile profile) {
        this.javaProfiles.putIfAbsent(profile.uuid(), profile);
    }

    public JavaProfile javaProfile(final UUID uuid) {
        return this.javaProfiles.get(uuid);
    }

    public List<JavaProfile> javaProfiles() {
        return List.copyOf(this.javaProfiles.values());
    }

    public void updateJavaGameMode(final UUID uuid, final GameMode gameMode) {
        final JavaProfile profile = this.javaProfiles.get(uuid);
        if (profile != null) {
            this.javaProfiles.put(uuid, profile.withGameMode(gameMode));
        }
    }

    public Pair<UUID, String> getPlayer(final long entityUniqueId) {
        for (Map.Entry<UUID, Pair<Long, String>> entry : this.playerList.entrySet()) {
            if (entry.getValue().key() == entityUniqueId) {
                return new Pair<>(entry.getKey(), entry.getValue().value());
            }
        }

        return null;
    }

    public int serverLatency(final UUID uuid) {
        return this.serverLatencies.getOrDefault(uuid, PacketSyncStorage.UNKNOWN_LATENCY);
    }

    public Map<UUID, Integer> replaceServerLatencies(final Map<UUID, Integer> latencies, final UUID localPlayerUuid) {
        this.serverLatencies.clear();
        this.serverLatencies.putAll(latencies);

        final Map<UUID, Integer> updates = new LinkedHashMap<>();
        for (UUID uuid : this.playerList.keySet()) {
            if (uuid.equals(localPlayerUuid)) continue;

            final int latency = this.serverLatency(uuid);
            if (this.publishedLatencies.getOrDefault(uuid, PacketSyncStorage.UNKNOWN_LATENCY) != latency) {
                updates.put(uuid, latency);
            }
        }
        return updates;
    }

    public void markLatencyPublished(final UUID uuid, final int latency) {
        this.publishedLatencies.put(uuid, latency);
    }

    public void markLatenciesPublished(final Map<UUID, Integer> latencies) {
        this.publishedLatencies.putAll(latencies);
    }

    public boolean markInvalidLatencyPayloadLogged() {
        if (this.invalidLatencyPayloadLogged) return false;

        this.invalidLatencyPayloadLogged = true;
        return true;
    }

    public record Entry(UUID uuid, long entityUniqueId, String name) {
    }

    public record JavaProfile(
            UUID uuid,
            String name,
            GameProfile.Property[] properties,
            GameMode gameMode,
            boolean listed,
            int latency,
            Tag displayName
    ) {

        public JavaProfile {
            properties = properties.clone();
        }

        @Override
        public GameProfile.Property[] properties() {
            return this.properties.clone();
        }

        public JavaProfile withGameMode(final GameMode gameMode) {
            return new JavaProfile(this.uuid, this.name, this.properties, gameMode,
                    this.listed, this.latency, this.displayName);
        }
    }

}
