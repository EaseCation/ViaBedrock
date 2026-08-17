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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntConsumer;

public final class BlockBreakingProgressTracker extends StoredObject {

    private static final int SYNTHETIC_BREAKER_ID_BASE = 1_000_000_000;
    private static final long STALE_PROGRESS_TIMEOUT_MS = 1_500L;
    private static final long BREAK_ACK_TIMEOUT_MS = 1_000L;
    private static final double BEDROCK_PROGRESS_SCALE = 65_535D;
    private static final double BREAK_PROGRESS_EPSILON = 1E-6D;
    private static final int SILENT_SUCCESS_TICKS = 10;

    private final Map<BlockPosition, BreakProgress> activeProgress = new HashMap<>();
    private final Map<BlockPosition, PendingBreakAck> pendingBreakAcks = new HashMap<>();
    private final NavigableMap<Integer, PendingBreakAck> sequencedBreakAcks = new TreeMap<>();
    private final IntConsumer ackSink;
    private MiningPhase miningPhase = MiningPhase.IDLE;
    private MiningTarget miningTarget;
    private int suspendedTicks;
    private int postFinishCooldownTicks;
    private int nextSyntheticBreakerId = SYNTHETIC_BREAKER_ID_BASE;
    private double localBreakProgress;
    private double localBreakRate;
    private boolean finishPredictionSent;
    private int ticksSinceFinishPrediction;

    public BlockBreakingProgressTracker(final UserConnection user) {
        this(user, sequence -> PacketFactory.sendJavaBlockChangedAck(user, sequence));
    }

    BlockBreakingProgressTracker(final UserConnection user, final IntConsumer ackSink) {
        super(user);
        this.ackSink = ackSink;
    }

    public MiningPhase miningPhase() {
        return this.miningPhase;
    }

    public MiningTarget miningTarget() {
        return this.miningTarget;
    }

    public boolean isMiningTarget(final BlockPosition position) {
        return this.miningTarget != null && this.miningTarget.position.equals(position);
    }

    public boolean shouldSuppressMissedSwing() {
        return this.postFinishCooldownTicks > 0 || this.miningPhase == MiningPhase.ACTIVE || this.miningPhase == MiningPhase.SUSPENDED;
    }

    public void startMining(final BlockPosition position, final Direction direction) {
        this.miningTarget = new MiningTarget(position, direction);
        this.miningPhase = MiningPhase.ACTIVE;
        this.suspendedTicks = 0;
        this.localBreakProgress = 0D;
        this.localBreakRate = 0D;
        this.finishPredictionSent = false;
        this.ticksSinceFinishPrediction = 0;
    }

    public void suspendMining(final BlockPosition position) {
        if (!this.isMiningTarget(position)) {
            return;
        }
        this.miningPhase = MiningPhase.SUSPENDED;
        this.suspendedTicks = 0;
    }

    public void resumeMining() {
        if (this.miningPhase == MiningPhase.SUSPENDED) {
            this.miningPhase = MiningPhase.ACTIVE;
            this.suspendedTicks = 0;
        }
    }

    public void finishMining(final BlockPosition position, final int sequence, final int javaBlockStateId, final boolean serverAuthoritative) {
        if (this.isMiningTarget(position)) {
            this.miningPhase = MiningPhase.FINISHING;
            this.suspendedTicks = 0;
            this.postFinishCooldownTicks = 5;
            this.expectJavaAckAfterBlockUpdate(position, sequence, javaBlockStateId);
            if (!serverAuthoritative) {
                this.miningTarget = null;
            }
        }
    }

    public FinishingStep advanceAuthInput() {
        if (this.miningPhase == MiningPhase.ACTIVE && this.localBreakRate > 0D) {
            this.localBreakProgress = Math.min(1D, this.localBreakProgress + this.localBreakRate);
            return null;
        }
        if (this.miningPhase != MiningPhase.FINISHING || this.miningTarget == null) {
            return null;
        }

        if (this.localBreakRate > 0D) {
            this.localBreakProgress = Math.min(1D, this.localBreakProgress + this.localBreakRate);
        }
        final boolean progressReady = this.localBreakRate <= 0D || this.localBreakProgress >= 1D - BREAK_PROGRESS_EPSILON;
        boolean predict = false;
        boolean silentSuccess = false;
        if (!this.finishPredictionSent && progressReady) {
            this.finishPredictionSent = true;
            this.ticksSinceFinishPrediction = 0;
            final PendingBreakAck pending = this.pendingBreakAcks.get(this.miningTarget.position());
            if (pending != null) {
                pending.timestamp = System.currentTimeMillis();
            }
            predict = true;
        } else if (this.finishPredictionSent && ++this.ticksSinceFinishPrediction >= SILENT_SUCCESS_TICKS) {
            silentSuccess = true;
        }
        return new FinishingStep(this.miningTarget, predict, silentSuccess);
    }

    public MiningTarget abortSuspendedMiningAfterTick(final int suspendedAbortTicks) {
        if (this.miningPhase != MiningPhase.SUSPENDED || this.miningTarget == null) {
            return null;
        }
        if (++this.suspendedTicks <= suspendedAbortTicks) {
            return null;
        }

        final MiningTarget aborted = this.miningTarget;
        this.miningPhase = MiningPhase.IDLE;
        this.miningTarget = null;
        this.suspendedTicks = 0;
        return aborted;
    }

    public void handleStartCracking(final BlockPosition position, final int data, final boolean localTarget) {
        if (localTarget) {
            this.localBreakRate = normalizedBreakRate(data);
            this.localBreakProgress = this.localBreakRate;
            return;
        }

        final BreakProgress progress = this.activeProgress.compute(position, (key, old) -> {
            final int breakerId = old != null ? old.breakerId : this.breakerIdFor(position);
            return new BreakProgress(breakerId, 0D, -1, System.currentTimeMillis());
        });
        progress.addData(data);
        this.sendStage(position, progress, 0);
    }

    public void handleUpdateCracking(final BlockPosition position, final int data, final boolean localTarget) {
        if (localTarget) {
            this.localBreakRate = normalizedBreakRate(data);
            return;
        }

        final BreakProgress progress = this.activeProgress.computeIfAbsent(position, key -> new BreakProgress(this.breakerIdFor(position), 0D, -1, System.currentTimeMillis()));
        progress.addData(data);
        final int stage = Math.max(0, Math.min(9, (int) Math.floor(progress.progress * 10D)));
        this.sendStage(position, progress, stage);
    }

    public void handleStopCracking(final BlockPosition position) {
        if (this.isMiningTarget(position)) {
            this.localBreakProgress = 0D;
            this.localBreakRate = 0D;
            return;
        }
        this.clearProgress(position);
    }

    public void expectJavaAckAfterBlockUpdate(final BlockPosition position, final int sequence, final int javaBlockStateId) {
        final PendingBreakAck pending = new PendingBreakAck(position, sequence, javaBlockStateId, System.currentTimeMillis());
        final PendingBreakAck replaced = this.pendingBreakAcks.put(position, pending);
        if (replaced != null && replaced.sequence > 0) {
            this.sequencedBreakAcks.remove(replaced.sequence);
        }
        if (sequence > 0) {
            this.sequencedBreakAcks.put(sequence, pending);
        }
    }

    public void handleBlockUpdate(final BlockPosition position, final boolean air) {
        this.clearProgress(position);
        final PendingBreakAck pending = this.pendingBreakAcks.get(position);
        if (pending != null) {
            pending.settled = true;
        }
        if (this.isMiningTarget(position)) {
            this.clearMiningTarget();
        }
    }

    public void afterJavaBlockUpdate(final BlockPosition position) {
        final PendingBreakAck pending = this.pendingBreakAcks.get(position);
        if (pending != null && pending.settled) {
            pending.javaStateSent = true;
        }
        this.flushSettledAcks();
    }

    public void completeSilentSuccess(final BlockPosition position) {
        final PendingBreakAck pending = this.pendingBreakAcks.get(position);
        if (pending != null) {
            pending.settled = true;
        }
        if (this.isMiningTarget(position)) {
            this.clearMiningTarget();
        }
    }

    public CancelledBreak cancelFinishing(final BlockPosition position) {
        if (this.miningPhase != MiningPhase.FINISHING || !this.isMiningTarget(position)) {
            return null;
        }
        final PendingBreakAck pending = this.pendingBreakAcks.get(position);
        if (pending == null) {
            this.clearMiningTarget();
            return null;
        }
        pending.settled = true;
        this.clearMiningTarget();
        return new CancelledBreak(position, pending.javaBlockStateId);
    }

    public void cancelCurrentTarget() {
        this.clearMiningTarget();
    }

    public void clearForLifecycleChange() {
        this.activeProgress.clear();
        this.pendingBreakAcks.clear();
        this.sequencedBreakAcks.clear();
        this.postFinishCooldownTicks = 0;
        this.clearMiningTarget();
    }

    int pendingAckCount() {
        return this.pendingBreakAcks.size();
    }

    public void tick() {
        if (this.postFinishCooldownTicks > 0) {
            this.postFinishCooldownTicks--;
        }

        final long now = System.currentTimeMillis();
        final List<BlockPosition> stalePositions = new ArrayList<>();
        for (Map.Entry<BlockPosition, BreakProgress> entry : this.activeProgress.entrySet()) {
            if (now - entry.getValue().lastUpdateTime > STALE_PROGRESS_TIMEOUT_MS) {
                stalePositions.add(entry.getKey());
            }
        }
        for (BlockPosition position : stalePositions) {
            this.clearProgress(position);
        }

        for (TimedOutBreakAck timedOut : this.collectTimedOutBreakAcks(now)) {
            if (timedOut.target() != null) {
                final ClientPlayerEntity clientPlayer = this.user().get(EntityTracker.class).getClientPlayer();
                clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(
                        PlayerActionType.AbortDestroyBlock, timedOut.target().position(), 0));
            }
            PacketFactory.sendJavaBlockUpdate(this.user(), timedOut.position(), timedOut.javaBlockStateId());
            this.afterJavaBlockUpdate(timedOut.position());
        }
    }

    List<TimedOutBreakAck> collectTimedOutBreakAcks(final long now) {
        final List<TimedOutBreakAck> timedOut = new ArrayList<>();
        final Iterator<Map.Entry<BlockPosition, PendingBreakAck>> it = this.pendingBreakAcks.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<BlockPosition, PendingBreakAck> entry = it.next();
            final PendingBreakAck ack = entry.getValue();
            if (ack.settled) {
                continue;
            }
            if (now - ack.timestamp > BREAK_ACK_TIMEOUT_MS) {
                final MiningTarget target = this.isMiningTarget(entry.getKey()) ? this.miningTarget : null;
                ack.settled = true;
                timedOut.add(new TimedOutBreakAck(entry.getKey(), ack.sequence, ack.javaBlockStateId, target));
                if (target != null) {
                    this.clearMiningTarget();
                }
            }
        }
        return timedOut;
    }

    private void flushSettledAcks() {
        int cumulativeAck = -1;
        while (!this.sequencedBreakAcks.isEmpty()) {
            final Map.Entry<Integer, PendingBreakAck> first = this.sequencedBreakAcks.firstEntry();
            final PendingBreakAck pending = first.getValue();
            if (!pending.settled || !pending.javaStateSent) {
                break;
            }
            cumulativeAck = first.getKey();
            this.sequencedBreakAcks.pollFirstEntry();
            this.pendingBreakAcks.remove(pending.position, pending);
        }
        if (cumulativeAck > 0) {
            this.ackSink.accept(cumulativeAck);
        }

        final Iterator<Map.Entry<BlockPosition, PendingBreakAck>> it = this.pendingBreakAcks.entrySet().iterator();
        while (it.hasNext()) {
            final PendingBreakAck pending = it.next().getValue();
            if (pending.sequence <= 0 && pending.settled && pending.javaStateSent) {
                it.remove();
            }
        }
    }

    private int breakerIdFor(final BlockPosition position) {
        return this.nextSyntheticBreakerId++;
    }

    private void sendStage(final BlockPosition position, final BreakProgress progress, final int stage) {
        progress.lastUpdateTime = System.currentTimeMillis();
        if (progress.lastStage == stage) {
            return;
        }

        progress.lastStage = stage;
        this.sendJavaBlockDestruction(progress.breakerId, position, stage);
    }

    private void clearProgress(final BlockPosition position) {
        final BreakProgress progress = this.activeProgress.remove(position);
        if (progress != null) {
            this.sendJavaBlockDestruction(progress.breakerId, position, -1);
        }
    }

    private void clearMiningTarget() {
        this.miningPhase = MiningPhase.IDLE;
        this.miningTarget = null;
        this.suspendedTicks = 0;
        this.localBreakProgress = 0D;
        this.localBreakRate = 0D;
        this.finishPredictionSent = false;
        this.ticksSinceFinishPrediction = 0;
    }

    private static double normalizedBreakRate(final int data) {
        return data > 0 ? Math.min(1D, data / BEDROCK_PROGRESS_SCALE) : 0D;
    }

    private void sendJavaBlockDestruction(final int breakerId, final BlockPosition position, final int progress) {
        final PacketWrapper blockDestruction = PacketWrapper.create(ClientboundPackets26_1.BLOCK_DESTRUCTION, this.user());
        blockDestruction.write(Types.VAR_INT, breakerId);
        blockDestruction.write(Types.BLOCK_POSITION1_14, position);
        blockDestruction.write(Types.UNSIGNED_BYTE, (short) (progress & 0xFF));
        blockDestruction.send(BedrockProtocol.class);
    }

    private static final class BreakProgress {
        private final int breakerId;
        private double progress;
        private int lastStage;
        private long lastUpdateTime;

        private BreakProgress(final int breakerId, final double progress, final int lastStage, final long lastUpdateTime) {
            this.breakerId = breakerId;
            this.progress = progress;
            this.lastStage = lastStage;
            this.lastUpdateTime = lastUpdateTime;
        }

        private void addData(final int data) {
            if (data > 0) {
                this.progress = Math.min(1D, this.progress + data / BEDROCK_PROGRESS_SCALE);
            }
        }
    }

    private static final class PendingBreakAck {
        private final BlockPosition position;
        private final int sequence;
        private final int javaBlockStateId;
        private long timestamp;
        private boolean settled;
        private boolean javaStateSent;

        private PendingBreakAck(final BlockPosition position, final int sequence, final int javaBlockStateId, final long timestamp) {
            this.position = position;
            this.sequence = sequence;
            this.javaBlockStateId = javaBlockStateId;
            this.timestamp = timestamp;
        }
    }

    record TimedOutBreakAck(BlockPosition position, int sequence, int javaBlockStateId, MiningTarget target) {
    }

    public record CancelledBreak(BlockPosition position, int javaBlockStateId) {
    }

    public record FinishingStep(MiningTarget target, boolean predict, boolean silentSuccess) {
    }

    public enum MiningPhase {
        IDLE,
        ACTIVE,
        SUSPENDED,
        FINISHING
    }

    public record MiningTarget(BlockPosition position, Direction direction) {
    }

}
