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
 * along with <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Queue;
import java.util.logging.Level;

/**
 * Bridges NetEase's early world stream with ViaBedrock's Java configuration handshake.
 *
 * <p>Vanilla Bedrock servers only send world packets after the client acknowledged the spawn
 * (SET_LOCAL_PLAYER_AS_INITIALIZED), which ViaBedrock sends after the Java client reached the
 * PLAY state. NetEase 860 backends push chunk, entity and inventory packets immediately after
 * START_GAME, while the Java client is still completing its configuration phase. The protocol's
 * pre-play guard dropped those packets, so PLAY_STATUS(PlayerSpawn) never initialized the player
 * and the backend timed the silent client out.</p>
 *
 * <p>This queue captures the raw early packets and replays them through the normal clientbound
 * pipeline once the Java PLAY state is entered and the START_GAME storages are installed.</p>
 */
public class PlayStateTransitionQueue extends StoredObject {

    /**
     * Packets relevant to the early join stream. Everything else keeps the old drop behaviour;
     * in particular creative-content and HUD packets already have their own parsers that may
     * depend on registries built during START_GAME processing.
     */
    private static final EnumSet<ClientboundBedrockPackets> DEFERRED_PACKETS = EnumSet.of(
            ClientboundBedrockPackets.PLAY_STATUS,
            ClientboundBedrockPackets.SET_ENTITY_DATA,
            ClientboundBedrockPackets.ADD_ENTITY,
            ClientboundBedrockPackets.REMOVE_ENTITY,
            ClientboundBedrockPackets.ADD_ITEM_ENTITY,
            ClientboundBedrockPackets.UPDATE_ATTRIBUTES,
            ClientboundBedrockPackets.MOB_EFFECT,
            ClientboundBedrockPackets.SET_DISPLAY_OBJECTIVE,
            ClientboundBedrockPackets.SET_SCORE,
            ClientboundBedrockPackets.UPDATE_BLOCK,
            ClientboundBedrockPackets.BLOCK_ENTITY_DATA,
            ClientboundBedrockPackets.CRAFTING_DATA,
            ClientboundBedrockPackets.LEVEL_CHUNK,
            ClientboundBedrockPackets.SUB_CHUNK,
            ClientboundBedrockPackets.BIOME_DEFINITION_LIST,
            ClientboundBedrockPackets.PLAYER_LIST,
            ClientboundBedrockPackets.SET_TIME,
            ClientboundBedrockPackets.SET_DIFFICULTY,
            ClientboundBedrockPackets.CONFIRM_SKIN,
            ClientboundBedrockPackets.SYNC_SKIN,
            ClientboundBedrockPackets.NETEASE_JSON
    );

    private static final int MAX_QUEUED_PACKETS = 4096;
    private static final int MAX_QUEUED_BYTES = 32 * 1024 * 1024;

    private record DeferredPacket(ClientboundBedrockPackets packet, ByteBuf payload) {
    }

    private final Queue<DeferredPacket> queue = new ArrayDeque<>();
    private int queuedBytes;
    private boolean replaying;
    private boolean failed;

    public PlayStateTransitionQueue(final UserConnection user) {
        super(user);
    }

    public static boolean deferIfNeeded(final UserConnection user, final ClientboundBedrockPackets packet, final PacketWrapper wrapper) {
        final PlayStateTransitionQueue transitionQueue = user.get(PlayStateTransitionQueue.class);
        if (transitionQueue == null || !DEFERRED_PACKETS.contains(packet)) {
            return false;
        }
        return transitionQueue.defer(packet, wrapper);
    }

    private boolean defer(final ClientboundBedrockPackets packet, final PacketWrapper wrapper) {
        if (this.replaying || this.failed) {
            return false;
        }

        final ByteBuf payload;
        try {
            payload = copyRawPayload(wrapper);
        } catch (final RuntimeException e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to copy early " + packet + " packet for replay", e);
            return false;
        }
        if (payload == null) {
            return false;
        }

        if (this.queue.size() >= MAX_QUEUED_PACKETS || this.queuedBytes + payload.readableBytes() > MAX_QUEUED_BYTES) {
            payload.release();
            this.fail();
            return true;
        }

        this.queue.add(new DeferredPacket(packet, payload));
        this.queuedBytes += payload.readableBytes();
        return true;
    }

    public void replayPackets() {
        if (this.replaying || this.failed || this.queue.isEmpty()) {
            this.clear();
            return;
        }

        this.replaying = true;
        try {
            DeferredPacket deferredPacket;
            while ((deferredPacket = this.queue.poll()) != null) {
                this.queuedBytes -= deferredPacket.payload().readableBytes();
                final PacketWrapper replay = PacketWrapper.create(deferredPacket.packet(), deferredPacket.payload(), this.user());
                try {
                    replay.send(BedrockProtocol.class, false);
                } catch (final Throwable t) {
                    // One malformed early packet must not abort the rest of the join stream; the
                    // normal pipeline would have dropped it anyway before this queue existed.
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to replay early " + deferredPacket.packet() + " packet", t);
                }
            }
        } finally {
            this.clear();
            this.replaying = false;
        }
    }

    private void fail() {
        this.failed = true;
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Early world packet queue exceeded its safety limit; dropping remaining early packets");
        this.clear();
    }

    private void clear() {
        DeferredPacket deferredPacket;
        while ((deferredPacket = this.queue.poll()) != null) {
            this.queuedBytes -= deferredPacket.payload().readableBytes();
            deferredPacket.payload().release();
        }
    }

    @Override
    public void onRemove() {
        this.clear();
    }

    private static ByteBuf copyRawPayload(final PacketWrapper wrapper) {
        final ByteBuf framedPacket = wrapper.user().getChannel().alloc().buffer();
        try {
            final int packetId = wrapper.getId();
            wrapper.writeToBuffer(framedPacket);
            if (packetId != -1) {
                final int serializedPacketId = Types.VAR_INT.readPrimitive(framedPacket);
                if (serializedPacketId != packetId) {
                    throw new IllegalStateException("Serialized packet id changed from " + packetId + " to " + serializedPacketId);
                }
            }
            return framedPacket.readRetainedSlice(framedPacket.readableBytes());
        } finally {
            framedPacket.release();
        }
    }
}
