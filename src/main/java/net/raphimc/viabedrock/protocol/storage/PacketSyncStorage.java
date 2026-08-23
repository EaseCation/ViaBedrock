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

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class PacketSyncStorage extends StoredObject {

    public static final int UNKNOWN_LATENCY = -1;
    /**
     * Marker timestamp for a Java-only PING/PONG sample. Used by the NetEase
     * spawn heartbeat for HUD ping when no server {@code NETWORK_STACK_LATENCY}
     * is in flight. A probe with this timestamp must not be forwarded back
     * to Bedrock.
     */
    public static final long JAVA_ONLY_LATENCY_PROBE = Long.MIN_VALUE;
    static final long LATENCY_UPDATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final AtomicInteger ID_COUNTER = new AtomicInteger(0);
    private final Int2ObjectMap<NetworkStackLatencyResponse> pendingNetworkStackLatencyResponses = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<Runnable> pendingActions = new Int2ObjectOpenHashMap<>();
    private int latencyMillis = UNKNOWN_LATENCY;
    private int lastPublishedLatencyMillis = UNKNOWN_LATENCY;
    private long lastLatencyPublishNanos;
    private boolean hasPublishedLatency;
    private Long pendingKeepAliveId;
    private long pendingKeepAliveNanos;

    public PacketSyncStorage(final UserConnection user) {
        super(user);
    }

    public int addNetworkStackLatencyResponse(final long timestamp) {
        return this.addNetworkStackLatencyResponse(timestamp, System.nanoTime());
    }

    int addNetworkStackLatencyResponse(final long timestamp, final long requestNanos) {
        if (ID_COUNTER.get() >= Short.MAX_VALUE) { // VB compatibility
            ID_COUNTER.set(0);
        }
        final int id = this.ID_COUNTER.getAndIncrement();
        if (this.pendingNetworkStackLatencyResponses.put(id, new NetworkStackLatencyResponse(timestamp, requestNanos)) != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Overwrote pending network stack latency response with id " + id);
        }
        return id;
    }

    public NetworkStackLatencyResponse getNetworkStackLatencyResponse(final int id) {
        return this.pendingNetworkStackLatencyResponses.remove(id);
    }

    public static boolean isJavaOnlyLatencyProbe(final NetworkStackLatencyResponse response) {
        return response != null && response.timestamp() == JAVA_ONLY_LATENCY_PROBE;
    }

    /**
     * Measures Java-client RTT without touching Bedrock {@code NETWORK_STACK_LATENCY}.
     * Server NSL on the NetEase path already round-trips through Java PING/PONG;
     * this probe only fills HUD samples between those pings.
     */
    public void sendJavaLatencyProbe() {
        if (this.user().getProtocolInfo().getServerState() != State.PLAY
                || this.user().getProtocolInfo().getClientState() != State.PLAY) {
            return;
        }
        final int id = this.addNetworkStackLatencyResponse(JAVA_ONLY_LATENCY_PROBE);
        final PacketWrapper pingPacket = PacketWrapper.create(ClientboundPackets26_1.PING, this.user());
        pingPacket.write(Types.INT, id); // parameter
        pingPacket.send(BedrockProtocol.class);
    }

    public void noteKeepAliveSent(final long id) {
        this.pendingKeepAliveId = id;
        this.pendingKeepAliveNanos = System.nanoTime();
    }

    public Long consumeKeepAlive(final long id) {
        if (this.pendingKeepAliveId == null || this.pendingKeepAliveId != id) {
            return null;
        }
        this.pendingKeepAliveId = null;
        return this.pendingKeepAliveNanos;
    }

    public int updateLatency(final long clientLatencyNanos, final int serverTransportLatencyMillis) {
        final long clientLatencyMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, clientLatencyNanos));
        final long combinedLatencyMillis = clientLatencyMillis + Math.max(0, serverTransportLatencyMillis);
        this.latencyMillis = (int) Math.min(Integer.MAX_VALUE, combinedLatencyMillis);
        return this.latencyMillis;
    }

    public int latencyMillis() {
        return this.latencyMillis;
    }

    public boolean shouldPublishLatency(final long nowNanos) {
        if (this.latencyMillis == UNKNOWN_LATENCY || this.latencyMillis == this.lastPublishedLatencyMillis) {
            return false;
        }
        return !this.hasPublishedLatency || nowNanos - this.lastLatencyPublishNanos >= LATENCY_UPDATE_INTERVAL_NANOS;
    }

    public void markLatencyPublished(final long nowNanos) {
        if (this.latencyMillis == UNKNOWN_LATENCY) return;

        this.lastPublishedLatencyMillis = this.latencyMillis;
        this.lastLatencyPublishNanos = nowNanos;
        this.hasPublishedLatency = true;
    }

    public void syncWithClient(final Runnable runnable) {
        if (ID_COUNTER.get() >= Short.MAX_VALUE) { // VB compatibility
            ID_COUNTER.set(0);
        }
        final int id = ID_COUNTER.getAndIncrement();
        if (this.pendingActions.put(id, runnable) != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Overwrote pending action with id " + id);
        }

        final State state = this.user().getProtocolInfo().getServerState();
        final PacketWrapper pingPacket = PacketWrapper.create(state == State.PLAY ? ClientboundPackets26_1.PING : ClientboundConfigurationPackets1_21_9.PING, this.user());
        pingPacket.write(Types.INT, id); // parameter
        pingPacket.send(BedrockProtocol.class);
    }

    public boolean handleSyncTask(final int id) {
        final Runnable runnable = this.pendingActions.remove(id);
        if (runnable != null) {
            runnable.run();
            return true;
        } else {
            return false;
        }
    }

    public record NetworkStackLatencyResponse(long timestamp, long requestNanos) {
    }

}
