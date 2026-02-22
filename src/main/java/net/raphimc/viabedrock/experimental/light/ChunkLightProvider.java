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
package net.raphimc.viabedrock.experimental.light;

import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * Interface for custom chunk light computation providers.
 * Implementations replace the default full-bright sky light behavior in ChunkTracker.
 */
public interface ChunkLightProvider {

    /**
     * Process light data and send the chunk to the Java client.
     * When this method returns true, the caller should skip its default chunk sending logic.
     *
     * @param tracker the chunk tracker for the current connection
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param chunk   the remapped Java chunk ready for sending
     * @return true if this provider handled sending the chunk, false to use default behavior
     */
    boolean processAndSendChunk(ChunkTracker tracker, int chunkX, int chunkZ, Chunk chunk);

    /**
     * Called when a chunk is unloaded. Implementations should clean up any cached state.
     *
     * @param chunkKey the packed chunk key (ChunkTracker.chunkKey(x, z))
     */
    void onChunkUnload(long chunkKey);

    /**
     * Called once per tick to process pending async light updates.
     */
    void tick();

}
