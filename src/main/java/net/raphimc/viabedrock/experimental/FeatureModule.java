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
package net.raphimc.viabedrock.experimental;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.Set;

/**
 * Interface for modular experimental feature implementations.
 * Each feature module is independently registered and receives lifecycle callbacks
 * from the protocol translation pipeline.
 */
public interface FeatureModule {

    /**
     * Called during BedrockMappingData.load() at various phases to allow
     * modules to load custom mappings at the correct point in the initialization sequence.
     */
    default void onMappingsLoad(BedrockMappingData data, MappingLoadPhase phase) {
    }

    /**
     * Called during BedrockProtocol.registerPackets() to register custom packet translators.
     */
    default void onPacketRegistration(BedrockProtocol protocol) {
    }

    /**
     * Called during BedrockProtocol.init() to register per-connection storages.
     */
    default void onStorageRegistration(UserConnection user) {
    }

    /**
     * Called after an entity is added to the EntityTracker.
     */
    default void onEntityAdded(UserConnection user, Entity entity) {
    }

    /**
     * Called before an entity is removed from the EntityTracker.
     */
    default void onEntityRemoved(UserConnection user, Entity entity) {
    }

    /**
     * Called when the Java client registers custom payload channels.
     */
    default void onChannelRegistered(UserConnection user, Set<String> channels) {
    }

    /**
     * Called during dimension change to resolve the dimension key for the Java client.
     * The first non-null result from any module is used.
     *
     * @return the resolved dimension key, or null to use the default
     */
    default String resolveDimensionKey(Dimension dimension, ChunkTracker oldChunkTracker) {
        return null;
    }

    /**
     * Called during ADD_ENTITY processing to resolve custom entity types.
     * The first non-null result from any module is used.
     *
     * @return a custom Entity instance, or null to fall back to default resolution
     */
    default Entity resolveEntity(UserConnection user, long uniqueId, long runtimeId, String type) {
        return null;
    }

    /**
     * Called after the resource pack stack is set and ready for processing.
     */
    default void onResourcePackStackSet(UserConnection user) {
    }

    /**
     * Called when a new ChunkTracker is created (dimension change, initial join, etc.).
     * Modules can use this to install custom light providers or other per-dimension state.
     */
    default void onChunkTrackerCreated(ChunkTracker tracker) {
    }

    /**
     * Called when a custom payload packet is received from the Java client.
     * The first module that returns true claims the packet.
     *
     * @param channel the custom payload channel
     * @param wrapper the packet wrapper (already cancelled by the caller)
     * @return true if this module handled the payload, false to pass to next module
     */
    default boolean handleCustomPayload(String channel, PacketWrapper wrapper) {
        return false;
    }

}
