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
import com.viaversion.viaversion.libs.fastutil.longs.LongArrayList;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.NyaNetworkStackLatencyPayload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class PacketSyncStorage extends StoredObject {

    public static final int UNKNOWN_LATENCY = -1;
    static final long LATENCY_UPDATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final int MAX_CLIENT_TICK_END_BOUNDARIES = 128;

    private final AtomicInteger ID_COUNTER = new AtomicInteger(0);
    private final Int2ObjectMap<NetworkStackLatencyResponse> pendingNetworkStackLatencyResponses = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<Runnable> pendingActions = new Int2ObjectOpenHashMap<>();
    // 只保存已收到对应 Java Pong 的专属边界；释放条件来自服务端载荷，不读取运动数据。
    // 两个序号都是代理侧包序：tick-end 用于新版，movement 仅为第一版兼容保留。
    private final Deque<ClientTickEndBoundary> clientTickEndBoundaries = new ArrayDeque<>();
    private long javaMovementSequence;
    private long javaClientTickEndSequence;
    private int latencyMillis = UNKNOWN_LATENCY;
    private int lastPublishedLatencyMillis = UNKNOWN_LATENCY;
    private long lastLatencyPublishNanos;
    private boolean hasPublishedLatency;

    public PacketSyncStorage(final UserConnection user) {
        super(user);
    }

    public int addNetworkStackLatencyResponse(final long timestamp) {
        return this.addNetworkStackLatencyResponse(timestamp, System.nanoTime());
    }

    synchronized int addNetworkStackLatencyResponse(final long timestamp, final long requestNanos) {
        if (ID_COUNTER.get() >= Short.MAX_VALUE) { // VB compatibility
            ID_COUNTER.set(0);
        }
        final int id = this.ID_COUNTER.getAndIncrement();
        final boolean nyaBoundaryPayload = NyaNetworkStackLatencyPayload.isPayload(timestamp);
        final NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor boundaryDescriptor =
                NyaNetworkStackLatencyPayload.decode(timestamp);
        if (this.pendingNetworkStackLatencyResponses.put(id,
                new NetworkStackLatencyResponse(
                        timestamp, requestNanos, nyaBoundaryPayload, boundaryDescriptor)) != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Overwrote pending network stack latency response with id " + id);
        }
        return id;
    }

    public synchronized NetworkStackLatencyResponse getNetworkStackLatencyResponse(final int id) {
        return this.pendingNetworkStackLatencyResponses.remove(id);
    }

    /**
     * 仅在消费对应 Java Pong 后入队。目标序号取当前值 + 1，不能用 Pong 之前的包提前确认。
     * 新版只等待下一份可发出的 AuthInput；旧版 movement 门控仅用于兼容，不影响新版。
     * 队列满时丢弃最老未释放边界，不伪造 ACK；是否重试由请求边界的服务端处理。
     */
    public synchronized void deferClientTickEndBoundary(
            final long timestamp,
            final NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor descriptor) {
        if (descriptor == null || descriptor.releasePolicy() == null
                || descriptor.family() != NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY) {
            return;
        }
        if (this.clientTickEndBoundaries.size() >= MAX_CLIENT_TICK_END_BOUNDARIES) {
            this.clientTickEndBoundaries.removeFirst();
        }
        final long requiredMovementSequence = descriptor.releasePolicy()
                == NyaNetworkStackLatencyPayload.ReleasePolicy.LEGACY_AFTER_MOVEMENT
                ? this.javaMovementSequence + 1L : -1L;
        final long requiredClientTickEndSequence = descriptor.releasePolicy()
                == NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END
                ? this.javaClientTickEndSequence + 1L : -1L;
        this.clientTickEndBoundaries.addLast(new ClientTickEndBoundary(
                timestamp, descriptor.releasePolicy(),
                requiredMovementSequence, requiredClientTickEndSequence));
    }

    /** 记录 Java movement 的处理顺序，仅为旧版边界兼容保留。 */
    public synchronized void recordJavaMovementFrame() {
        this.javaMovementSequence++;
    }

    /** 在当前 AuthInput 字段构建完成后、释放 ACK 前调用；被取消的 tick-end 不推进序号。 */
    public synchronized void recordJavaClientTickEndFrame() {
        this.javaClientTickEndSequence++;
    }

    /**
     * 由 AuthInput 构建末尾调用，按各自释放条件取出就绪边界，并在返回前移除排队记录。
     * 就绪 ACK 保持相对入队顺序；未就绪的旧版边界不能阻塞已就绪的新版边界。
     */
    public synchronized long[] consumeClientTickEndBoundaries() {
        final LongArrayList ready = new LongArrayList();
        final Iterator<ClientTickEndBoundary> iterator = this.clientTickEndBoundaries.iterator();
        while (iterator.hasNext()) {
            final ClientTickEndBoundary boundary = iterator.next();
            final boolean boundaryReady = switch (boundary.releasePolicy()) {
                case NEXT_CLIENT_TICK_END -> boundary.requiredClientTickEndSequence()
                        <= this.javaClientTickEndSequence;
                case LEGACY_AFTER_MOVEMENT -> boundary.requiredMovementSequence()
                        <= this.javaMovementSequence;
            };
            if (boundaryReady) {
                ready.add(boundary.timestamp());
                iterator.remove();
            }
        }
        return ready.toLongArray();
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

    public record NetworkStackLatencyResponse(
            long timestamp,
            long requestNanos,
            boolean nyaBoundaryPayload,
            NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor boundaryDescriptor) {
        public NetworkStackLatencyResponse(final long timestamp, final long requestNanos) {
            this(timestamp, requestNanos, false, null);
        }
    }

    private record ClientTickEndBoundary(
            long timestamp,
            NyaNetworkStackLatencyPayload.ReleasePolicy releasePolicy,
            long requiredMovementSequence,
            long requiredClientTickEndSequence) {
    }

}
