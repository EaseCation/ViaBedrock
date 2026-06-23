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
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class BlockBreakingProgressTracker extends StoredObject {

    private static final int SYNTHETIC_BREAKER_ID_BASE = 1_000_000_000;
    private static final long STALE_PROGRESS_TIMEOUT_MS = 1_500L;
    private static final long BREAK_ACK_TIMEOUT_MS = 1_000L;
    private static final double BEDROCK_PROGRESS_SCALE = 65_535D;

    private final Map<BlockPosition, BreakProgress> activeProgress = new HashMap<>();
    private final Map<BlockPosition, PendingBreakAck> pendingBreakAcks = new HashMap<>();
    private MiningPhase miningPhase = MiningPhase.IDLE;
    private MiningTarget miningTarget;
    private int suspendedTicks;
    private int postFinishCooldownTicks;
    private int nextSyntheticBreakerId = SYNTHETIC_BREAKER_ID_BASE;

    public BlockBreakingProgressTracker(final UserConnection user) {
        super(user);
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

    public void finishMining(final BlockPosition position, final int sequence) {
        if (this.isMiningTarget(position)) {
            this.miningPhase = MiningPhase.FINISHING;
            this.miningTarget = null;
            this.suspendedTicks = 0;
            this.postFinishCooldownTicks = 5;
            this.expectJavaAckAfterBlockUpdate(position, sequence);
        }
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
            return;
        }

        final BreakProgress progress = this.activeProgress.computeIfAbsent(position, key -> new BreakProgress(this.breakerIdFor(position), 0D, -1, System.currentTimeMillis()));
        progress.addData(data);
        final int stage = Math.max(0, Math.min(9, (int) Math.floor(progress.progress * 10D)));
        this.sendStage(position, progress, stage);
    }

    public void handleStopCracking(final BlockPosition position) {
        this.clearProgress(position);
    }

    public void expectJavaAckAfterBlockUpdate(final BlockPosition position, final int sequence) {
        if (sequence > 0) {
            this.pendingBreakAcks.put(position, new PendingBreakAck(sequence, System.currentTimeMillis()));
        }
    }

    public void handleBlockUpdate(final BlockPosition position) {
        this.clearProgress(position);
        final PendingBreakAck ack = this.pendingBreakAcks.remove(position);
        if (ack != null) {
            PacketFactory.sendJavaBlockChangedAck(this.user(), ack.sequence);
        }
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

        final Iterator<Map.Entry<BlockPosition, PendingBreakAck>> it = this.pendingBreakAcks.entrySet().iterator();
        while (it.hasNext()) {
            final PendingBreakAck ack = it.next().getValue();
            if (now - ack.timestamp > BREAK_ACK_TIMEOUT_MS) {
                PacketFactory.sendJavaBlockChangedAck(this.user(), ack.sequence);
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

    private record PendingBreakAck(int sequence, long timestamp) {
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
