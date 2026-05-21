package net.raphimc.viabedrock.experimental.custommapping;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.storage.CustomRegistryStorage;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ScheduledFuture;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.model.BlockProperties;
import net.raphimc.viabedrock.protocol.packet.JoinPackets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

public final class CustomMappingSyncStorage extends StoredObject {
    public static final String CHANNEL = "bedrockloader:mapping_sync";
    private static final int MSG_REQUEST = 1;
    private static final int MSG_SNAPSHOT = 2;
    private static final int MSG_CHUNK = 3;
    private static final int MSG_RESULT = 4;
    private static final int MAX_PAYLOAD_BYTES = 28672;
    private static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;
    private static final Set<String> CUSTOM_BLOCK_ENTITY_STAGE_NAMES = Set.of(
        "com.viaversion.viabackwards.protocol.v1_18to1_17_1.Protocol1_18To1_17_1"
    );
    private static final int MAX_CUSTOM_BLOCK_STATES = 65536;
    private static final int MAX_CUSTOM_BLOCK_ENTITY_TYPES = 4096;
    private static final int MAX_JAVA_BLOCK_STATE_ID = 1048575;

    private long requestId;
    private State state = State.UNSEEN;
    private SnapshotProfile profile;
    private CustomMappingAccess access = CustomMappingAccess.safe();
    private ChunkAccumulator chunks;
    private final Limits limits;
    private boolean resultSent;
    private JoinPackets.PendingStartGame pendingStartGame;
    private long deadlineNanos;
    private ScheduledFuture<?> timeoutTask;
    private boolean finishStarted;
    private boolean channelProbeSent;
    private boolean blockStatesAreFinalOutput;
    private boolean blockEntityTypesAreFinalOutput;

    public CustomMappingSyncStorage(final UserConnection user) {
        super(user);
        this.limits = Limits.fromConfig(ViaBedrock.getConfig());
        if (user.getChannel() != null) {
            user.getChannel().closeFuture().addListener(future -> clearConnectionState());
        }
    }

    public CustomMappingAccess access() {
        return this.access;
    }

    public boolean hasInstalledAccess() {
        return this.access.hasCustomMappings();
    }

    public void probeChannelIfNeeded() {
        if (this.state != State.UNSEEN || !ViaBedrock.getConfig().isCustomMappingSyncEnabled()) return;
        sendChannelProbeIfNeeded();
    }

    public void beginRequestIfNeeded() {
        if (this.state != State.UNSEEN && this.state != State.WAITING_FOR_CHANNEL) return;
        if (!ViaBedrock.getConfig().isCustomMappingSyncEnabled()) {
            this.state = State.UNSUPPORTED_SAFE;
            this.access = CustomMappingAccess.safe();
            sendResult(STATUS_UNSUPPORTED_SAFE, "unsupported_safe: disabled by ViaBedrock config");
            return;
        }
        this.requestId = ThreadLocalRandom.current().nextLong();
        this.state = State.REQUESTED;
        cancelTimeout();
        startTimeout();
        advertiseServerboundChannel();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, MSG_REQUEST);
        writeLong(out, this.requestId);
        writeVarInt(out, BedrockProtocol.MAPPINGS.getVanillaBlockStateCount());
        writeVarInt(out, BedrockProtocol.MAPPINGS.getVanillaBlockEntityCount());
        writeVarInt(out, this.user().getProtocolInfo().getProtocolVersion());
        writeString(out, CustomMappingProfileCache.SYNC_MAPPING_VERSION);
        writeVarInt(out, this.limits.maxPayloadBytes());
        writeVarInt(out, this.limits.maxSnapshotBytes());
        final PacketWrapper request = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, this.user());
        request.write(Types.STRING, CHANNEL);
        request.write(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA, out.toByteArray());
        request.send(BedrockProtocol.class);
        if (this.user().getChannel() != null) {
            this.user().getChannel().flush();
        }
        ViaBedrock.getPlatform().getLogger().info("Requested BedrockLoader custom mapping snapshot; requestId=" + Long.toUnsignedString(this.requestId));
    }

    public void onStartGame(final JoinPackets.PendingStartGame pendingStartGame) {
        if (this.pendingStartGame != null || this.finishStarted) return;
        this.pendingStartGame = pendingStartGame;
        ViaBedrock.getPlatform().getLogger().info("Bedrock START_GAME received; custom mapping sync state=" + this.state);
        sendChannelProbeIfNeeded();
        tryFinishCustomMapping();
    }

    public void tryFinishCustomMapping() {
        if (this.pendingStartGame == null || this.finishStarted) return;
        if (this.state == State.REQUESTED && hasTimedOut()) {
            fail("Timed out waiting for BedrockLoader custom mapping snapshot", null);
        }
        switch (this.state) {
            case UNSEEN -> {
                if (ViaBedrock.getConfig().isCustomMappingSyncEnabled()) {
                    sendChannelProbeIfNeeded();
                    this.state = State.WAITING_FOR_CHANNEL;
                    startTimeout();
                    ViaBedrock.getPlatform().getLogger().info("Bedrock START_GAME is waiting for BedrockLoader custom mapping sync channel");
                    return;
                }
                this.state = State.UNSUPPORTED_SAFE;
                this.access = CustomMappingAccess.safe();
                ViaBedrock.getPlatform().getLogger().warning("BedrockLoader custom mapping sync is disabled; using safe fallback");
                sendResult(STATUS_UNSUPPORTED_SAFE, "unsupported_safe: BedrockLoader mapping sync channel was not registered");
                finishPendingStartGame();
            }
            case WAITING_FOR_CHANNEL -> {
                if (!hasTimedOut()) return;
                this.state = State.UNSUPPORTED_SAFE;
                this.access = CustomMappingAccess.safe();
                ViaBedrock.getPlatform().getLogger().warning("BedrockLoader custom mapping sync channel was not registered before timeout; using safe fallback");
                sendResult(STATUS_UNSUPPORTED_SAFE, "unsupported_safe: BedrockLoader mapping sync channel was not registered");
                finishPendingStartGame();
            }
            case SNAPSHOT_READY, UNSUPPORTED_SAFE, FAILED_SAFE -> finishPendingStartGame();
            case REQUESTED, KICKED, INSTALLED -> {
            }
        }
    }

    public boolean handlePayload(final byte[] payload) {
        try {
            if (payload.length > this.limits.maxPayloadBytes()) {
                throw new IllegalArgumentException("Payload too large: payloadLength=" + payload.length + ", maxPayloadBytes=" + this.limits.maxPayloadBytes());
            }
            final Reader reader = new Reader(payload, this.limits.maxSnapshotBytes());
            final int messageType = reader.readVarInt();
            final long receivedRequestId = reader.readLong();
            if (receivedRequestId != this.requestId) throw new IllegalArgumentException("Request id mismatch");
            if (this.state != State.REQUESTED) throw new IllegalStateException("Unexpected custom mapping payload in state " + this.state);
            if (messageType == MSG_SNAPSHOT) {
                final boolean hasBlocks = reader.readBool();
                final boolean hasBlockEntities = reader.readBool();
                final int uncompressedLength = reader.readVarInt();
                final int compressedLength = reader.readVarInt();
                final byte[] compressed = reader.readByteArray();
                reader.ensureFullyRead();
                if (!hasBlocks && !hasBlockEntities && uncompressedLength == 0 && compressedLength == 0) {
                    this.profile = SnapshotProfile.empty();
                    this.state = State.SNAPSHOT_READY;
                    ViaBedrock.getPlatform().getLogger().info("Received empty BedrockLoader custom mapping snapshot");
                    tryFinishCustomMapping();
                    return true;
                }
                if (compressedLength != compressed.length) {
                    throw new IllegalArgumentException("Compressed length mismatch: compressedLength=" + compressedLength + ", byteArrayLength=" + compressed.length + ", payloadLength=" + payload.length);
                }
                if (compressedLength > this.limits.maxSnapshotBytes()) {
                    throw new IllegalArgumentException("Compressed snapshot too large: compressedLength=" + compressedLength + ", byteArrayLength=" + compressed.length + ", payloadLength=" + payload.length + ", maxSnapshotBytes=" + this.limits.maxSnapshotBytes());
                }
                this.profile = profileFromBody(inflate(compressed, uncompressedLength));
                this.state = State.SNAPSHOT_READY;
                ViaBedrock.getPlatform().getLogger().info("Received BedrockLoader custom mapping snapshot; uncompressedBytes=" + uncompressedLength + ", compressedBytes=" + compressedLength);
                tryFinishCustomMapping();
                return true;
            } else if (messageType == MSG_CHUNK) {
                if (!ViaBedrock.getConfig().isCustomMappingSyncChunksEnabled()) throw new IllegalArgumentException("Chunked custom mapping snapshots are disabled");
                final boolean hasBlocks = reader.readBool();
                final boolean hasBlockEntities = reader.readBool();
                final int chunkIndex = reader.readVarInt();
                final int chunkCount = reader.readVarInt();
                final int uncompressedLength = reader.readVarInt();
                final int compressedLength = reader.readVarInt();
                final byte[] part = reader.readByteArray();
                reader.ensureFullyRead();
                if (this.chunks == null) this.chunks = new ChunkAccumulator(hasBlocks, hasBlockEntities, chunkCount, uncompressedLength, compressedLength, this.limits.maxSnapshotBytes(), payload.length);
                final byte[] compressed = this.chunks.add(chunkIndex, part, hasBlocks, hasBlockEntities, uncompressedLength, compressedLength);
                if (compressed != null) {
                    this.profile = profileFromBody(inflate(compressed, uncompressedLength));
                    this.state = State.SNAPSHOT_READY;
                    ViaBedrock.getPlatform().getLogger().info("Received chunked BedrockLoader custom mapping snapshot; chunks=" + chunkCount + ", uncompressedBytes=" + uncompressedLength + ", compressedBytes=" + compressedLength);
                    tryFinishCustomMapping();
                }
                return true;
            } else {
                throw new IllegalArgumentException("Unknown custom mapping message type " + messageType);
            }
        } catch (Throwable e) {
            fail("Failed to handle BedrockLoader custom mapping snapshot", e);
        }
        return true;
    }

    public RuntimeProjectionBuilder runtimeProjectionBuilder(final BlockProperties[] blockProperties, final boolean hashedRuntimeBlockIds) {
        if (this.profile == null || this.state == State.FAILED_SAFE) {
            this.access = CustomMappingAccess.safe();
            return null;
        }
        final int blockStateSourceBase = blockStateSourceBase();
        final int blockEntitySourceBase = blockEntitySourceBase();
        if ((long) blockStateSourceBase + this.profile.blockStateCount() > this.limits.maxJavaBlockStateId()) {
            fail("Custom block state source allocation exceeds configured maximum: sourceBase=" + blockStateSourceBase + ", count=" + this.profile.blockStateCount() + ", maxJavaBlockStateId=" + this.limits.maxJavaBlockStateId(), null);
            return null;
        }
        if ((long) blockEntitySourceBase + this.profile.blockEntityTypeCount() > Integer.MAX_VALUE) {
            fail("Custom block entity source allocation exceeds integer id range: sourceBase=" + blockEntitySourceBase + ", count=" + this.profile.blockEntityTypeCount(), null);
            return null;
        }
        final long pipelineRegistryKey = RuntimeProjectionCache.pipelineRegistryKey(this.user().getProtocolInfo().getPipeline().pipes());
        return new RuntimeProjectionBuilder(this.profile, RuntimeProjectionCache.key(this.profile, blockProperties, hashedRuntimeBlockIds, blockStateSourceBase, blockEntitySourceBase, pipelineRegistryKey), blockStateSourceBase, blockEntitySourceBase);
    }

    public void installProjection(final RuntimeProjection projection) {
        if (projection != null) {
            final List<Protocol> blockStateStages = relevantStages(true);
            final List<Protocol> blockEntityStages = relevantStages(false);
            ViaBedrock.getPlatform().getLogger().info("Custom mapping clientbound block state stages: " + stageNames(blockStateStages));
            ViaBedrock.getPlatform().getLogger().info("Custom mapping clientbound block entity stages: " + stageNames(blockEntityStages));
            this.blockStatesAreFinalOutput = blockStateStages.isEmpty();
            this.blockEntityTypesAreFinalOutput = blockEntityStages.isEmpty();
            this.access = projection.toAccess(this.blockStatesAreFinalOutput, this.blockEntityTypesAreFinalOutput);
            clearCustomRegistryStorage();
            if (!projection.blockEntityTypes().isEmpty() || !projection.blockStates().isEmpty()) {
                final CustomRegistryStorage storage = new CustomRegistryStorage();
                this.user().put(storage);
                installStageAwareOverlay(storage, projection, blockStateStages, blockEntityStages);
            }
            this.state = State.INSTALLED;
            ViaBedrock.getPlatform().getLogger().info("Installed custom block mapping overlay; max java block state id " + this.access.maxJavaBlockStateId());
            sendResult(STATUS_INSTALLED, "installed: maxJavaBlockStateId=" + this.access.maxJavaBlockStateId());
        }
    }

    public void failProjection(final String message, final Throwable e) {
        fail(message, e);
    }

    public int packetBlockStateId(final RuntimeProjection.ProjectedBlockState state) {
        return this.blockStatesAreFinalOutput ? state.targetJavaRawId() : state.sourceJavaRawId();
    }

    private void installStageAwareOverlay(final CustomRegistryStorage storage, final RuntimeProjection projection, final List<Protocol> blockStateStages, final List<Protocol> blockEntityStages) {
        if (!projection.blockStates().isEmpty()) {
            final int minSourceId = projection.blockStates().get(0).sourceJavaRawId();
            final int maxSourceId = projection.blockStates().get(projection.blockStates().size() - 1).sourceJavaRawId();
            for (Protocol stage : blockStateStages) {
                storage.putSourceRange(stage.getClass(), CustomRegistryStorage.BLOCK_STATE, minSourceId, maxSourceId);
            }
        }
        for (RuntimeProjection.ProjectedBlockState state : projection.blockStates()) {
            for (int i = 0; i < blockStateStages.size(); i++) {
                final Protocol stage = blockStateStages.get(i);
                final boolean last = i == blockStateStages.size() - 1;
                storage.put(stage.getClass(), CustomRegistryStorage.BLOCK_STATE, state.sourceJavaRawId(), last ? state.targetJavaRawId() : state.sourceJavaRawId(), "mod_block:" + state.bedrockIdentifier());
            }
        }

        if (!projection.blockEntityTypes().isEmpty()) {
            final int minSourceId = projection.blockEntityTypes().get(0).sourceJavaRawId();
            final int maxSourceId = projection.blockEntityTypes().get(projection.blockEntityTypes().size() - 1).sourceJavaRawId();
            for (Protocol stage : blockEntityStages) {
                storage.putSourceRange(stage.getClass(), CustomRegistryStorage.BLOCK_ENTITY_TYPE, minSourceId, maxSourceId);
            }
        }
        for (SnapshotProfile.BlockEntityTypeMapping type : projection.blockEntityTypes()) {
            for (int i = 0; i < blockEntityStages.size(); i++) {
                final Protocol stage = blockEntityStages.get(i);
                final boolean last = i == blockEntityStages.size() - 1;
                storage.put(stage.getClass(), CustomRegistryStorage.BLOCK_ENTITY_TYPE, type.sourceJavaRawId(), last ? type.targetJavaRawId() : type.sourceJavaRawId(), type.javaIdentifier());
            }
        }
    }

    private List<Protocol> relevantStages(final boolean blockStates) {
        final List<Protocol> stages = new ArrayList<>();
        for (Protocol protocol : this.user().getProtocolInfo().getPipeline().reversedPipes()) {
            final MappingData mappingData = protocol.getMappingData();
            if (mappingData == null) continue;
            if (blockStates) {
                if (mappingData.getBlockStateMappings() != null) stages.add(protocol);
            } else if (isBlockEntityStage(protocol, mappingData)) {
                stages.add(protocol);
            }
        }
        return stages;
    }

    private static boolean isBlockEntityStage(final Protocol protocol, final MappingData mappingData) {
        return mappingData.getBlockEntityMappings() != null || CUSTOM_BLOCK_ENTITY_STAGE_NAMES.contains(protocol.getClass().getName());
    }

    private static String stageNames(final List<Protocol> stages) {
        if (stages.isEmpty()) {
            return "<final-output>";
        }
        final StringJoiner joiner = new StringJoiner(" -> ");
        for (Protocol stage : stages) {
            joiner.add(stage.getClass().getSimpleName());
        }
        return joiner.toString();
    }

    private int blockStateSourceBase() {
        int base = Math.max(BedrockProtocol.MAPPINGS.getVanillaBlockStateCount(), this.profile.maxTargetJavaRawId() + 1);
        for (Protocol protocol : this.user().getProtocolInfo().getPipeline().pipes()) {
            final MappingData mappingData = protocol.getMappingData();
            if (mappingData == null || mappingData.getBlockStateMappings() == null) continue;
            base = Math.max(base, registryUpperBound(mappingData.getBlockStateMappings().size(), mappingData.getBlockStateMappings().mappedSize()));
        }
        return avoidSourceRangeConflicts(base, this.profile.blockStateCount(), true);
    }

    private int blockEntitySourceBase() {
        int base = Math.max(BedrockProtocol.MAPPINGS.getVanillaBlockEntityCount(), this.profile.maxTargetBlockEntityRawId() + 1);
        for (Protocol protocol : this.user().getProtocolInfo().getPipeline().pipes()) {
            final MappingData mappingData = protocol.getMappingData();
            if (mappingData == null || mappingData.getBlockEntityMappings() == null) continue;
            base = Math.max(base, registryUpperBound(mappingData.getBlockEntityMappings().size(), mappingData.getBlockEntityMappings().mappedSize()));
        }
        return avoidSourceRangeConflicts(base, this.profile.blockEntityTypeCount(), false);
    }

    private static int registryUpperBound(final int sourceSize, final int targetSize) {
        return Math.max(Math.max(0, sourceSize), Math.max(0, targetSize));
    }

    private int avoidSourceRangeConflicts(int base, final int count, final boolean blockStates) {
        if (count <= 0) return base;
        final int step = Math.max(1, count);
        while (base >= 0 && base <= Integer.MAX_VALUE - count) {
            if (!sourceRangeConflicts(base, count, blockStates)) return base;
            base += step;
        }
        return Integer.MAX_VALUE;
    }

    private boolean sourceRangeConflicts(final int base, final int count, final boolean blockStates) {
        for (Protocol protocol : this.user().getProtocolInfo().getPipeline().pipes()) {
            final MappingData mappingData = protocol.getMappingData();
            if (mappingData == null) continue;
            final Mappings mappings = blockStates ? mappingData.getBlockStateMappings() : mappingData.getBlockEntityMappings();
            if (mappings == null) continue;
            for (int id = base; id < base + count; id++) {
                if (mappings.getNewId(id) != -1) {
                    return true;
                }
            }
        }
        return false;
    }

    private void finishPendingStartGame() {
        if (this.user().getChannel() != null && !this.user().getChannel().eventLoop().inEventLoop()) {
            this.user().getChannel().eventLoop().execute(this::finishPendingStartGame);
            return;
        }
        final JoinPackets.PendingStartGame pending = this.pendingStartGame;
        if (pending == null || this.finishStarted) return;
        this.finishStarted = true;
        this.pendingStartGame = null;
        cancelTimeout();
        JoinPackets.finishCustomMappingStartGame(this.user(), pending);
    }

    private void startTimeout() {
        final int timeoutMs = Math.max(0, ViaBedrock.getConfig().getCustomMappingSyncTimeoutMs());
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (this.user().getChannel() == null) return;
        this.timeoutTask = this.user().getChannel().eventLoop().schedule(() -> {
            if (this.user().getChannel() == null || !this.user().getChannel().isActive()) return;
            tryFinishCustomMapping();
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void sendChannelProbeIfNeeded() {
        if (this.channelProbeSent) return;
        this.channelProbeSent = true;
        JoinPackets.sendBrandCustomPayload(this.user(), "Bedrock");
        ViaBedrock.getPlatform().getLogger().info("Sent configuration brand to trigger Java client channel registration");
    }

    private void advertiseServerboundChannel() {
        final PacketWrapper register = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, this.user());
        register.write(Types.STRING, "minecraft:register");
        register.write(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA, CHANNEL.getBytes(StandardCharsets.UTF_8));
        register.send(BedrockProtocol.class);
        ViaBedrock.getPlatform().getLogger().info("Advertised BedrockLoader custom mapping sync serverbound channel to Java client");
    }

    private boolean hasTimedOut() {
        return this.deadlineNanos != 0L && System.nanoTime() - this.deadlineNanos >= 0L;
    }

    private void cancelTimeout() {
        if (this.timeoutTask != null) {
            this.timeoutTask.cancel(false);
            this.timeoutTask = null;
        }
    }

    private void clearConnectionState() {
        cancelTimeout();
        this.profile = null;
        this.access = CustomMappingAccess.safe();
        this.chunks = null;
        this.pendingStartGame = null;
        this.blockStatesAreFinalOutput = false;
        this.blockEntityTypesAreFinalOutput = false;
        clearCustomRegistryStorage();
    }

    private void clearCustomRegistryStorage() {
        final CustomRegistryStorage storage = this.user().remove(CustomRegistryStorage.class);
        if (storage != null) {
            storage.clear();
        }
    }

    private CustomMappingSnapshot decodeSnapshot(final byte[] body) {
        return CustomMappingSnapshot.decode(
                body,
                this.limits.maxSnapshotBytes(),
                this.limits.maxCustomBlockStates(),
                this.limits.maxCustomBlockEntityTypes(),
                this.limits.maxJavaBlockStateId());
    }

    private SnapshotProfile profileFromBody(final byte[] body) {
        final long cacheKey = CustomMappingProfileCache.cacheKey(body);
        return CustomMappingProfileCache.getInstance().getOrBuild(body, () -> SnapshotProfile.fromSnapshot(decodeSnapshot(body), cacheKey));
    }

    private byte[] inflate(final byte[] compressed, final int expectedLength) throws Exception {
        if (expectedLength < 0 || expectedLength > this.limits.maxSnapshotBytes()) {
            throw new IllegalArgumentException("Invalid uncompressed length: uncompressedLength=" + expectedLength + ", maxSnapshotBytes=" + this.limits.maxSnapshotBytes());
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(expectedLength);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                if ((long) out.size() + read > expectedLength || (long) out.size() + read > this.limits.maxSnapshotBytes()) {
                    throw new IllegalArgumentException("Uncompressed snapshot exceeds declared length: uncompressedLength=" + expectedLength + ", maxSnapshotBytes=" + this.limits.maxSnapshotBytes());
                }
                out.write(buffer, 0, read);
            }
        }
        final byte[] bytes = out.toByteArray();
        if (bytes.length != expectedLength) throw new IllegalArgumentException("Uncompressed length mismatch: uncompressedLength=" + expectedLength + ", actualBodyLength=" + bytes.length + ", compressedByteArrayLength=" + compressed.length);
        return bytes;
    }

    private void fail(final String message, final Throwable e) {
        this.profile = null;
        this.access = CustomMappingAccess.safe();
        this.blockStatesAreFinalOutput = false;
        this.blockEntityTypesAreFinalOutput = false;
        clearCustomRegistryStorage();
        if (ViaBedrock.getConfig().getCustomMappingSyncFailureMode() == ViaBedrockConfig.CustomMappingSyncFailureMode.KICK) {
            this.state = State.KICKED;
            cancelTimeout();
            sendResult(STATUS_KICKED, "kicked: " + message);
            BedrockProtocol.kickForIllegalState(this.user(), message, e);
            return;
        }
        this.state = State.FAILED_SAFE;
        sendResult(STATUS_FAILED_SAFE, "failed_safe: " + message);
        if (e == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, message);
        } else {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, message, e);
        }
        tryFinishCustomMapping();
    }

    private void sendResult(final int status, final String message) {
        if (!ViaBedrock.getConfig().shouldSendCustomMappingSyncResult() || this.resultSent) return;
        this.resultSent = true;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, MSG_RESULT);
        writeLong(out, this.requestId);
        out.write(status & 0xFF);
        writeString(out, message);
        try {
            final PacketWrapper result = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, this.user());
            result.write(Types.STRING, CHANNEL);
            result.write(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA, out.toByteArray());
            result.send(BedrockProtocol.class);
            if (this.user().getChannel() != null) {
                this.user().getChannel().flush();
            }
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.FINE, "Failed to send custom mapping sync result", e);
        }
    }

    private enum State { UNSEEN, WAITING_FOR_CHANNEL, REQUESTED, SNAPSHOT_READY, INSTALLED, UNSUPPORTED_SAFE, FAILED_SAFE, KICKED }

    private static final int STATUS_INSTALLED = 0;
    private static final int STATUS_UNSUPPORTED_SAFE = 1;
    private static final int STATUS_FAILED_SAFE = 2;
    private static final int STATUS_KICKED = 3;

    private record Limits(int maxPayloadBytes, int maxSnapshotBytes, int maxCustomBlockStates, int maxCustomBlockEntityTypes, int maxJavaBlockStateId) {
        static Limits fromConfig(final ViaBedrockConfig config) {
            return new Limits(
                    clamp(config.getCustomMappingSyncMaxPayloadBytes(), 1024, 32767, MAX_PAYLOAD_BYTES),
                    clamp(config.getCustomMappingSyncMaxSnapshotBytes(), 0, MAX_SNAPSHOT_BYTES, MAX_SNAPSHOT_BYTES),
                    clamp(config.getCustomMappingSyncMaxCustomBlockStates(), 0, MAX_CUSTOM_BLOCK_STATES, MAX_CUSTOM_BLOCK_STATES),
                    clamp(config.getCustomMappingSyncMaxCustomBlockEntityTypes(), 0, MAX_CUSTOM_BLOCK_ENTITY_TYPES, MAX_CUSTOM_BLOCK_ENTITY_TYPES),
                    clamp(config.getCustomMappingSyncMaxJavaBlockStateId(), BedrockProtocol.MAPPINGS.getVanillaBlockStateCount(), Integer.MAX_VALUE, MAX_JAVA_BLOCK_STATE_ID)
            );
        }

        private static int clamp(final int value, final int min, final int max, final int fallback) {
            if (value < min) return fallback;
            return Math.min(value, max);
        }
    }

    private static final class ChunkAccumulator {
        private final byte[][] chunks;
        private final boolean hasBlocks;
        private final boolean hasBlockEntities;
        private final int uncompressedLength;
        private final int compressedLength;
        private final int maxSnapshotBytes;
        private final int firstPayloadLength;
        private int received;
        private int receivedBytes;
        ChunkAccumulator(final boolean hasBlocks, final boolean hasBlockEntities, final int count, final int uncompressedLength, final int compressedLength, final int maxSnapshotBytes, final int firstPayloadLength) {
            if (count <= 0 || count > 4096) throw new IllegalArgumentException("Invalid chunk count");
            if (uncompressedLength < 0 || uncompressedLength > maxSnapshotBytes) throw new IllegalArgumentException("Invalid uncompressed length");
            if (compressedLength < 0 || compressedLength > maxSnapshotBytes) throw new IllegalArgumentException("Invalid compressed length");
            this.chunks = new byte[count][];
            this.hasBlocks = hasBlocks;
            this.hasBlockEntities = hasBlockEntities;
            this.uncompressedLength = uncompressedLength;
            this.compressedLength = compressedLength;
            this.maxSnapshotBytes = maxSnapshotBytes;
            this.firstPayloadLength = firstPayloadLength;
        }
        byte[] add(final int index, final byte[] part, final boolean hasBlocks, final boolean hasBlockEntities, final int uncompressedLength, final int compressedLength) {
            if (this.hasBlocks != hasBlocks || this.hasBlockEntities != hasBlockEntities || this.uncompressedLength != uncompressedLength || this.compressedLength != compressedLength) {
                throw new IllegalArgumentException("Chunk metadata mismatch");
            }
            if (index < 0 || index >= this.chunks.length) throw new IllegalArgumentException("Chunk index out of range");
            if (this.chunks[index] != null) throw new IllegalArgumentException("Duplicate chunk");
            if (part.length > this.maxSnapshotBytes) {
                throw new IllegalArgumentException("Chunk part too large: compressedLength=" + compressedLength + ", byteArrayLength=" + part.length + ", firstPayloadLength=" + this.firstPayloadLength + ", maxSnapshotBytes=" + this.maxSnapshotBytes);
            }
            final long nextReceivedBytes = (long) this.receivedBytes + part.length;
            if (nextReceivedBytes > this.compressedLength || nextReceivedBytes > this.maxSnapshotBytes) {
                throw new IllegalArgumentException("Chunk parts exceed declared compressed length: compressedLength=" + this.compressedLength + ", receivedBytes=" + nextReceivedBytes + ", firstPayloadLength=" + this.firstPayloadLength);
            }
            this.chunks[index] = part;
            this.received++;
            this.receivedBytes += part.length;
            if (this.received != this.chunks.length) return null;
            final ByteArrayOutputStream out = new ByteArrayOutputStream(this.compressedLength);
            for (byte[] chunk : this.chunks) out.writeBytes(chunk);
            final byte[] result = out.toByteArray();
            if (result.length != this.compressedLength) {
                throw new IllegalArgumentException("Compressed chunk length mismatch: compressedLength=" + this.compressedLength + ", byteArrayLength=" + result.length + ", firstPayloadLength=" + this.firstPayloadLength);
            }
            return result;
        }
    }

    private static final class Reader {
        private final ByteBuf buf;
        private final int maxByteArrayLength;
        Reader(final byte[] bytes, final int maxByteArrayLength) {
            this.buf = Unpooled.wrappedBuffer(bytes);
            this.maxByteArrayLength = maxByteArrayLength;
        }
        int readUnsignedByte() { return this.buf.readUnsignedByte(); }
        boolean readBool() { final int b = readUnsignedByte(); if (b > 1) throw new IllegalArgumentException("Invalid bool"); return b == 1; }
        long readLong() { return this.buf.readLong(); }
        int readVarInt() {
            int result = 0;
            int shift = 0;
            for (int i = 0; i < 5; i++) {
                final int b = readUnsignedByte();
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IllegalArgumentException("VarInt too long");
        }
        String readString() {
            final int len = readVarInt();
            if (len < 0 || len > 32767 || len > this.buf.readableBytes()) throw new IllegalArgumentException("Invalid string length");
            final byte[] bytes = new byte[len];
            this.buf.readBytes(bytes);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Invalid UTF-8 string", e);
            }
        }
        byte[] readByteArray() {
            final int len = readVarInt();
            if (len < 0 || len > this.maxByteArrayLength || len > this.buf.readableBytes()) throw new IllegalArgumentException("Invalid byte array length");
            final byte[] bytes = new byte[len]; this.buf.readBytes(bytes); return bytes;
        }
        void ensureFullyRead() {
            if (this.buf.isReadable()) throw new IllegalArgumentException("Unexpected trailing payload bytes: " + this.buf.readableBytes());
        }
    }

    private static void writeVarInt(final ByteArrayOutputStream out, int value) {
        do { int temp = value & 0x7F; value >>>= 7; if (value != 0) temp |= 0x80; out.write(temp); } while (value != 0);
    }
    private static void writeLong(final ByteArrayOutputStream out, final long value) {
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((value >>> shift) & 0xFF));
    }
    private static void writeString(final ByteArrayOutputStream out, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8); writeVarInt(out, bytes.length); out.writeBytes(bytes);
    }
}
