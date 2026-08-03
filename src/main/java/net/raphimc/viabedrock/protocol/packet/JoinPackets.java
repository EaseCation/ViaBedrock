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

import com.vdurmont.semver4j.Semver;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntArrayTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.minecraft.RegistryEntry;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.IntIntImmutablePair;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.base.v1_7.ClientboundBaseProtocol1_7;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.modinterface.ECClientLightInterface;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.GameEventType;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.model.*;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;

public class JoinPackets {

    private static final PacketHandler BIOME_DEFINITION_LIST_HANDLER = wrapper -> {
        if (wrapper.isCancelled()) return;

        wrapper.user().get(GameSessionStorage.class).setBedrockBiomeDefinitions((CompoundTag) wrapper.read(BedrockTypes.NETWORK_TAG)); // biome definitions
    };

    private static final PacketHandler COMPRESSED_BIOME_DEFINITION_LIST_HANDLER = wrapper -> {
        if (wrapper.isCancelled()) return;

        // Compressed biome definitions are used for the clientside generation of the world. Should not be sent as we tell the server that the client doesn't support it.
        BedrockProtocol.kickForIllegalState(wrapper.user(), "Compressed biome definitions are not supported.");
    };

    private static final PacketHandler REQUIRE_UNINITIALIZED_WORLD_HANDLER = wrapper -> {
        if (!wrapper.user().get(ChunkTracker.class).isEmpty()) {
            wrapper.cancel();
        } else if (!wrapper.user().get(EntityTracker.class).isEmpty()) {
            wrapper.cancel();
        }

        if (wrapper.isCancelled()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to change world properties after the world was already loaded");
        }
    };

    private static final PacketHandler RECONFIGURE_HANDLER = wrapper -> {
        if (wrapper.isCancelled()) return;
        wrapper.cancel();

        wrapper.user().put(new ChunkTracker(wrapper.user(), wrapper.user().get(ChunkTracker.class).getDimension()));
        if (wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_20_2)) {
            wrapper.user().get(ClientChannelDiscoveryStorage.class).beginConfigurationCycle();
            wrapper.user().get(ClientLightStorage.class).beginConfigurationCycle();
            final PacketWrapper startConfiguration = PacketWrapper.create(ClientboundPackets26_1.START_CONFIGURATION, wrapper.user());
            startConfiguration.send(BedrockProtocol.class);
            wrapper.user().getProtocolInfo().setServerState(State.CONFIGURATION);

            handleJavaClientGameJoin(wrapper.user());
        } else {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Skipping reconfigure packet as it is not supported by the client. This may cause issues.");
        }
    };

    static String resolveTabListPlaceholders(final String template, final String levelName) {
        return template.replace("%version%", ViaBedrock.VERSION).replace("%level_name%", levelName);
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientboundTransition(ClientboundBedrockPackets.PLAY_STATUS,
                State.LOGIN, (PacketHandler) wrapper -> {
                    final int rawStatus = wrapper.read(Types.INT); // status
                    final PlayStatus status = PlayStatus.getByValue(rawStatus);
                    if (status == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayStatus: " + rawStatus);
                        wrapper.cancel();
                        return;
                    }

                    if (status == PlayStatus.LoginSuccess) {
                        // Open the initial cycle before LOGIN_FINISHED reaches the Java client. Otherwise
                        // CLIENT_INFORMATION can start the probe before a later Bedrock pre-play packet
                        // resets the cycle and accidentally removes the negotiation deadline.
                        wrapper.user().get(ClientChannelDiscoveryStorage.class).beginConfigurationCycle();
                        wrapper.user().get(ClientLightStorage.class).beginConfigurationCycle();
                        final ProtocolInfo protocolInfo = wrapper.user().getProtocolInfo();
                        wrapper.setPacketType(ClientboundLoginPackets.LOGIN_FINISHED);
                        wrapper.write(Types.UUID, protocolInfo.getUuid()); // uuid
                        wrapper.write(Types.STRING, protocolInfo.getUsername()); // username
                        wrapper.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[0]); // properties

                        ClientboundBaseProtocol1_7.onLoginSuccess(wrapper.user());
                        sendClientCacheStatus(wrapper.user());
                    } else {
                        wrapper.setPacketType(ClientboundLoginPackets.LOGIN_DISCONNECT);
                        writePlayStatusKickMessage(wrapper, status);
                    }
                }, State.PLAY, (PacketHandler) wrapper -> {
                    final int rawStatus = wrapper.read(Types.INT); // status
                    final PlayStatus status = PlayStatus.getByValue(rawStatus);
                    if (status == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayStatus: " + rawStatus);
                        wrapper.cancel();
                        return;
                    }

                    if (status == PlayStatus.LoginSuccess) {
                        wrapper.cancel();
                        sendClientCacheStatus(wrapper.user());
                    } else if (status == PlayStatus.PlayerSpawn) {
                        wrapper.cancel();
                        final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                        if (clientPlayer.isInitiallySpawned()) {
                            // C1: re-spawn on the same connection (e.g. cross-server reload that reuses this entity).
                            // The plain early-return below would skip the loading-screen finalization, leaving the
                            // client stuck on the loading screen and the backend unaware the player is ready. Gated by
                            // the movementWatchdog switch: ACTIVE re-sends the idempotent finalization, OBSERVE logs,
                            // OFF keeps upstream behaviour.
                            final ViaBedrockConfig.MovementWatchdogMode mode = ViaBedrock.getConfig().getMovementWatchdogMode();
                            if (mode == ViaBedrockConfig.MovementWatchdogMode.ACTIVE) {
                                PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, null);
                                final PacketWrapper reInit = PacketWrapper.create(ServerboundBedrockPackets.SET_LOCAL_PLAYER_AS_INITIALIZED, wrapper.user());
                                reInit.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId());
                                reInit.sendToServer(BedrockProtocol.class);
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] C: re-spawn after reload; re-sent EndLoadingScreen + SET_LOCAL_PLAYER_AS_INITIALIZED");
                            } else if (mode == ViaBedrockConfig.MovementWatchdogMode.OBSERVE) {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] C(observe): re-spawn after reload detected (would re-send loading finalization in active mode)");
                            }
                            return;
                        }

                        final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());
                        interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.InteractUpdate.getValue()); // action
                        interact.write(BedrockTypes.UNSIGNED_VAR_LONG, 0L); // target entity runtime id
                        interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
                        interact.sendToServer(BedrockProtocol.class);

                        clientPlayer.setRotation(new Position3f(clientPlayer.rotation().x(), clientPlayer.rotation().y(), clientPlayer.rotation().y()));
                        clientPlayer.setInitiallySpawned();

                        PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, null);
                        final PacketWrapper setLocalPlayerAsInitialized = PacketWrapper.create(ServerboundBedrockPackets.SET_LOCAL_PLAYER_AS_INITIALIZED, wrapper.user());
                        setLocalPlayerAsInitialized.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
                        setLocalPlayerAsInitialized.sendToServer(BedrockProtocol.class);
                    } else {
                        wrapper.setPacketType(ClientboundPackets26_1.DISCONNECT);
                        writePlayStatusKickMessage(wrapper, status);
                    }
                }, State.CONFIGURATION, (PacketHandler) wrapper -> {
                    final int rawStatus = wrapper.read(Types.INT); // status
                    final PlayStatus status = PlayStatus.getByValue(rawStatus);
                    if (status == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayStatus: " + rawStatus);
                        wrapper.cancel();
                        return;
                    }

                    if (status == PlayStatus.LoginSuccess) {
                        wrapper.cancel();
                        sendClientCacheStatus(wrapper.user());
                    } else {
                        wrapper.setPacketType(ClientboundConfigurationPackets1_21_9.DISCONNECT);
                        writePlayStatusKickMessage(wrapper, status);
                    }
                }
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.START_GAME,
                State.CONFIGURATION, (PacketHandler) wrapper -> {
                    wrapper.cancel();
                    final UserConnection user = wrapper.user();
                    final ResourcePackLoadStateTracker loadStateTracker =
                            user.get(ResourcePackLoadStateTracker.class);
                    final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);
                    final boolean negotiationPending = loadStateTracker != null
                            && loadStateTracker.hasResourcePackStackStarted()
                            && !loadStateTracker.negotiationReadyFuture().isDone();
                    if (resourcePackStorage == null || negotiationPending) {
                        if (loadStateTracker != null && !loadStateTracker.deferStartGame()) {
                            ViaBedrock.getPlatform().getLogger().log(
                                    Level.WARNING, "Ignoring duplicate deferred START_GAME packet");
                            return;
                        }
                        final ByteBuf payload = copyDeferredStartGamePayload(wrapper);
                        final CompletableFuture<ResourcePackStorage> preparation;
                        final boolean sessionOwnedPreparation;
                        try {
                            if (loadStateTracker != null
                                    && loadStateTracker.hasResourcePackStackStarted()) {
                                sessionOwnedPreparation = true;
                                preparation = loadStateTracker.negotiationReadyFuture();
                                ViaBedrock.getPlatform().getLogger().log(
                                        Level.INFO, "Deferring START_GAME until resource pack negotiation is ready");
                            } else {
                                if (loadStateTracker != null
                                        && loadStateTracker.hasAnnouncedResourcePacks()) {
                                    throw new IllegalStateException(
                                            "START_GAME arrived before RESOURCE_PACK_STACK for announced resource packs");
                                }
                                sessionOwnedPreparation = false;
                                ViaBedrock.getPlatform().getLogger().log(
                                        Level.WARNING, "Resource pack negotiation was skipped without announced packs");
                                if (loadStateTracker != null) {
                                    ResourcePackPackets.cancelRemotePackDelivery(loadStateTracker);
                                }
                                final int buildTimeoutSeconds =
                                        ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds();
                                final CompletableFuture<ResourcePackStorage> initialized =
                                        initializePreparedResourcePackStorage(
                                                ResourcePackStorage.createAsync(List.of(), List.of()),
                                                ResourcePackModule::ensureRuntimeData);
                                preparation = ResourcePackPackets.detachedTimeout(
                                        initialized, buildTimeoutSeconds,
                                        TimeUnit.SECONDS, () -> {
                                        }, JoinPackets::cleanupDeferredStorage,
                                        user.getChannel().eventLoop());
                            }
                        } catch (Throwable error) {
                            ReferenceCountUtil.safeRelease(payload);
                            BedrockProtocol.kickForIllegalState(
                                    user, "Failed to prepare resource packs before START_GAME", error);
                            return;
                        }
                        resumeStartGameAfterResourcePackPreparation(
                                user, payload, preparation, sessionOwnedPreparation,
                                (liveUser, deferredPayload) -> {
                                    if (loadStateTracker != null) {
                                        if (liveUser.get(ResourcePackLoadStateTracker.class)
                                                != loadStateTracker) {
                                            throw new IllegalStateException(
                                                    "Resource pack session changed before START_GAME replay");
                                        }
                                        loadStateTracker.markDeferredStartGameReady();
                                    }
                                    PacketWrapper.create(
                                            ClientboundBedrockPackets.START_GAME,
                                            deferredPayload.duplicate(), liveUser)
                                            .send(BedrockProtocol.class, false);
                                },
                                (liveUser, error) -> BedrockProtocol.kickForIllegalState(
                                        liveUser, "Failed to prepare resource packs before START_GAME", error));
                        return;
                    }
                    if (loadStateTracker != null) {
                        if (!loadStateTracker.claimStartGameProcessing()) {
                            ViaBedrock.getPlatform().getLogger().log(
                                    Level.WARNING, "Ignoring duplicate START_GAME packet during resource pack replay");
                            return;
                        }
                        if (loadStateTracker.hasAnnouncedResourcePacks()
                                && loadStateTracker.stackPhase()
                                != ResourcePackLoadStateTracker.StackPhase.PUBLISHED) {
                            BedrockProtocol.kickForIllegalState(user,
                                    "START_GAME observed an unpublished resource pack runtime",
                                    new IllegalStateException(
                                            "Resource pack stack state is " + loadStateTracker.stackPhase()));
                            return;
                        }
                        user.remove(ResourcePackLoadStateTracker.class);
                    }
                    final GameSessionStorage gameSession = user.get(GameSessionStorage.class);

                    final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
                    final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
                    final GameType playerGameType = GameType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GameType.Undefined); // player game type
                    final Position3f playerPosition = wrapper.read(BedrockTypes.POSITION_3F); // player position
                    final Position2f playerRotation = wrapper.read(BedrockTypes.POSITION_2F); // player rotation

                    // Level settings
                    wrapper.read(BedrockTypes.LONG_LE); // seed
                    wrapper.read(BedrockTypes.SHORT_LE); // spawn biome type
                    wrapper.read(BedrockTypes.STRING); // custom biome name
                    final Dimension dimension = Dimension.values()[wrapper.read(BedrockTypes.VAR_INT)]; // dimension
                    final GeneratorType generatorType = GeneratorType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GeneratorType.Undefined); // generator id
                    final GameType levelGameType = GameType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GameType.Undefined); // level game type
                    final boolean hardcore = wrapper.read(Types.BOOLEAN); // hardcore
                    final Difficulty difficulty = Difficulty.getByValue(wrapper.read(BedrockTypes.VAR_INT), Difficulty.Unknown); // difficulty
                    wrapper.read(BedrockTypes.BLOCK_POSITION); // default spawn position
                    wrapper.read(Types.BOOLEAN); // achievements disabled
                    final Editor_WorldType editorWorldType = Editor_WorldType.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // world editor type
                    wrapper.read(Types.BOOLEAN); // created in world editor
                    wrapper.read(Types.BOOLEAN); // exported from world editor
                    final int currentTime = wrapper.read(BedrockTypes.VAR_INT); // day cycle stop time
                    wrapper.read(BedrockTypes.VAR_INT); // education edition offers
                    wrapper.read(Types.BOOLEAN); // education features enabled
                    wrapper.read(BedrockTypes.STRING); // education product id
                    final float rainLevel = wrapper.read(BedrockTypes.FLOAT_LE); // rain level
                    final float lightningLevel = wrapper.read(BedrockTypes.FLOAT_LE); // lightning level
                    wrapper.read(Types.BOOLEAN); // platform locked content confirmed
                    wrapper.read(Types.BOOLEAN); // multiplayer game
                    wrapper.read(Types.BOOLEAN); // is broadcasting to lan
                    wrapper.read(BedrockTypes.VAR_INT); // Xbox Live broadcast mode
                    wrapper.read(BedrockTypes.VAR_INT); // platform broadcast mode
                    final boolean commandsEnabled = wrapper.read(Types.BOOLEAN); // commands enabled
                    wrapper.read(Types.BOOLEAN); // texture packs required
                    final GameRule[] gameRules = wrapper.read(BedrockTypes.VAR_INT_GAME_RULE_ARRAY); // game rules
                    final Experiment[] experiments = wrapper.read(BedrockTypes.EXPERIMENT_ARRAY); // experiments
                    wrapper.read(Types.BOOLEAN); // experiments previously toggled
                    wrapper.read(Types.BOOLEAN); // bonus chest enabled
                    wrapper.read(Types.BOOLEAN); // start with map enabled
                    final int playerPermission = wrapper.read(BedrockTypes.VAR_INT); // player permission
                    final int chunkTickRange = wrapper.read(BedrockTypes.INT_LE); // server chunk tick range
                    wrapper.read(Types.BOOLEAN); // behavior pack locked
                    wrapper.read(Types.BOOLEAN); // resource pack locked
                    wrapper.read(Types.BOOLEAN); // from locked world template
                    wrapper.read(Types.BOOLEAN); // using msa gamer tags only
                    wrapper.read(Types.BOOLEAN); // from world template
                    wrapper.read(Types.BOOLEAN); // world template option locked
                    wrapper.read(Types.BOOLEAN); // only spawn v1 villagers
                    wrapper.read(Types.BOOLEAN); // disable personas
                    wrapper.read(Types.BOOLEAN); // disable custom skins
                    wrapper.read(Types.BOOLEAN); // mute emote chat
                    final String vanillaVersion = wrapper.read(BedrockTypes.STRING); // vanilla version
                    wrapper.read(BedrockTypes.INT_LE); // limited world width
                    wrapper.read(BedrockTypes.INT_LE); // limited world height
                    wrapper.read(Types.BOOLEAN); // nether type
                    wrapper.read(BedrockTypes.EDUCATION_URI_RESOURCE); // education shared uri
                    wrapper.read(Types.BOOLEAN); // enable experimental game play
                    final ChatRestrictionLevel chatRestrictionLevel = ChatRestrictionLevel.getByValue(wrapper.read(Types.BYTE), ChatRestrictionLevel.Disabled); // chat restriction level
                    wrapper.read(Types.BOOLEAN); // disabling player interactions

                    // Continue reading start game packet
                    wrapper.read(BedrockTypes.STRING); // level id
                    final String levelName = wrapper.read(BedrockTypes.STRING); // level name
                    wrapper.read(BedrockTypes.STRING); // premium world template id
                    wrapper.read(Types.BOOLEAN); // is trial
                    final int rewindHistorySize = wrapper.read(BedrockTypes.VAR_INT); // rewind history size
                    final boolean blockBreakingServerAuthoritative = wrapper.read(Types.BOOLEAN); // server authoritative block breaking
                    final long levelTime = wrapper.read(BedrockTypes.LONG_LE); // current level time
                    wrapper.read(BedrockTypes.VAR_INT); // enchantment seed
                    final BlockProperties[] blockProperties = wrapper.read(BedrockTypes.BLOCK_PROPERTIES_ARRAY); // block properties
                    wrapper.read(BedrockTypes.STRING); // multiplayer correlation id
                    final boolean inventoryServerAuthoritative = wrapper.read(Types.BOOLEAN); // server authoritative inventories
                    final String serverEngine = wrapper.read(BedrockTypes.STRING); // server engine
                    wrapper.read(BedrockTypes.NETWORK_TAG); // player property data
                    wrapper.read(BedrockTypes.LONG_LE); // block registry checksum
                    wrapper.read(BedrockTypes.UUID); // world template id
                    wrapper.read(Types.BOOLEAN); // client side generation
                    final boolean hashedRuntimeBlockIds = wrapper.read(Types.BOOLEAN); // use hashed block runtime ids
                    wrapper.read(Types.BOOLEAN); // server authoritative sounds
                    if (wrapper.read(Types.BOOLEAN)) { // has server join information
                        if (wrapper.read(Types.BOOLEAN)) { // has gathering join information
                            wrapper.read(BedrockTypes.UUID); // experience id
                            wrapper.read(BedrockTypes.STRING); // experience name
                            wrapper.read(BedrockTypes.UUID); // experience world id
                            wrapper.read(BedrockTypes.STRING); // experience world name
                            wrapper.read(BedrockTypes.STRING); // creator id
                            wrapper.read(BedrockTypes.UUID); // target id
                            wrapper.read(BedrockTypes.STRING); // scenario id
                            wrapper.read(BedrockTypes.STRING); // server id
                        }
                        if (wrapper.read(Types.BOOLEAN)) { // has store entry point info
                            wrapper.read(BedrockTypes.STRING); // store id
                            wrapper.read(BedrockTypes.STRING); // store name
                        }
                        if (wrapper.read(Types.BOOLEAN)) { // has presence info
                            wrapper.read(BedrockTypes.STRING); // experience name
                            wrapper.read(BedrockTypes.STRING); // world name
                        }
                    }
                    wrapper.read(BedrockTypes.STRING); // server id
                    wrapper.read(BedrockTypes.STRING); // scenario id
                    wrapper.read(BedrockTypes.STRING); // world id
                    wrapper.read(BedrockTypes.STRING); // owner id

                    if (editorWorldType == Editor_WorldType.EditorProject) {
                        final PacketWrapper disconnect = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.DISCONNECT, wrapper.user());
                        PacketFactory.writeJavaDisconnect(wrapper, resourcePackStorage.getTexts().get("disconnectionScreen.editor.mismatchEditorWorld"));
                        disconnect.send(BedrockProtocol.class);
                        return;
                    }

                    ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Server feature version: " + vanillaVersion);
                    Semver version;
                    try {
                        if (vanillaVersion.equals("*")) {
                            version = new Semver("99.99.99");
                        } else {
                            version = new Semver(vanillaVersion, Semver.SemverType.LOOSE);
                        }
                    } catch (Throwable e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Invalid vanilla version: " + vanillaVersion);
                        version = new Semver("99.99.99");
                    }

                    final List<String> enabledFeatures = new ArrayList<>();
                    for (Experiment experiment : experiments) {
                        if (experiment.enabled()) {
                            if (BedrockProtocol.MAPPINGS.getBedrockToJavaExperimentalFeatures().containsKey(experiment.name())) {
                                enabledFeatures.add(BedrockProtocol.MAPPINGS.getBedrockToJavaExperimentalFeatures().get(experiment.name()));
                            } else {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "This server uses an unsupported experimental feature: " + experiment.name());
                            }
                        }
                    }

                    if (!inventoryServerAuthoritative) {
                        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "This server uses client authoritative inventories. This is not supported yet.");
                    }

                    gameSession.setBedrockVanillaVersion(version);
                    gameSession.setFlatGenerator(generatorType == GeneratorType.Flat);
                    gameSession.setMovementRewindHistorySize(rewindHistorySize);
                    gameSession.setLevelGameType(levelGameType);
                    gameSession.setLevelTime(levelTime);
                    gameSession.setHardcoreMode(hardcore);
                    gameSession.setChatRestrictionLevel(chatRestrictionLevel);
                    gameSession.setCommandsEnabled(commandsEnabled);
                    gameSession.setInventoryServerAuthoritative(inventoryServerAuthoritative);
                    gameSession.setBlockBreakingServerAuthoritative(blockBreakingServerAuthoritative);

                    final PlayerAbilities playerAbilities = new PlayerAbilities(entityUniqueId, (byte) playerPermission, (byte) CommandPermissionLevel.Any.getValue());
                    final ClientPlayerEntity clientPlayer = new ClientPlayerEntity(wrapper.user(), entityRuntimeId, wrapper.user().getProtocolInfo().getUuid(), playerAbilities);
                    clientPlayer.setPosition(new Position3f(playerPosition.x(), playerPosition.y() + clientPlayer.eyeOffset(), playerPosition.z()));
                    clientPlayer.setRotation(new Position3f(playerRotation.x(), playerRotation.y(), 0F));
                    clientPlayer.setOnGround(false);
                    clientPlayer.setGameType(playerGameType);
                    clientPlayer.setName(wrapper.user().getProtocolInfo().getUsername());

                    wrapper.user().get(CustomMappingSyncStorage.class).onStartGame(new PendingStartGame(
                            levelName,
                            difficulty,
                            rainLevel,
                            lightningLevel,
                            currentTime,
                            chunkTickRange,
                            gameRules,
                            blockProperties,
                            hashedRuntimeBlockIds,
                            dimension,
                            clientPlayer,
                            serverEngine,
                            vanillaVersion,
                            enabledFeatures
                    ));
                }, State.PLAY, (PacketHandler) PacketWrapper::cancel // Bedrock client ignores multiple start game packets
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.BIOME_DEFINITION_LIST,
                // Biomes are technically data driven, but the client seems to ignore most of the defined data and instead uses hardcoded values.
                State.CONFIGURATION, new PacketHandlers() {
                    @Override
                    protected void register() {
                        handler(PacketWrapper::cancel);
                    }
                }, State.PLAY, new PacketHandlers() {
                    @Override
                    protected void register() {
                        handler(REQUIRE_UNINITIALIZED_WORLD_HANDLER);
                        handler(PacketWrapper::cancel);
                    }
                }
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.DIMENSION_DATA,
                State.CONFIGURATION, (PacketHandler) wrapper -> {
                    wrapper.cancel();
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // entry count
                    for (int i = 0; i < count; i++) {
                        final String dimensionIdentifier = wrapper.read(BedrockTypes.STRING); // dimension identifier
                        final int maximumHeight = wrapper.read(BedrockTypes.VAR_INT); // maximum height
                        final int minimumHeight = wrapper.read(BedrockTypes.VAR_INT); // minimum height
                        wrapper.read(BedrockTypes.VAR_INT); // generator type
                        wrapper.read(BedrockTypes.VAR_INT); // dimension type
                        if (dimensionIdentifier.equals(Dimension.OVERWORLD.getKey())) { // Bedrock client currently only supports overworld
                            gameSession.putBedrockDimensionDefinition(dimensionIdentifier, new IntIntImmutablePair(minimumHeight, maximumHeight));
                        }
                    }
                }, State.PLAY, (PacketHandler) PacketWrapper::cancel // Bedrock client ignores dimension data after start game
        );
        protocol.registerClientbound(ClientboundBedrockPackets.ITEM_REGISTRY, null, wrapper -> {
            wrapper.cancel();
            if (wrapper.user().has(ItemRewriter.class) && !wrapper.user().get(ItemRewriter.class).getItems().isEmpty()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received ITEM_REGISTRY after item rewriter was already initialized");
                return;
            }

            final ItemEntry[] itemEntries = wrapper.read(BedrockTypes.ITEM_ENTRY_ARRAY); // items
            final ItemRewriter itemRewriter = new ItemRewriter(wrapper.user(), itemEntries);
            wrapper.user().put(itemRewriter);
            final ItemDefinitions itemDefinitions = wrapper.user().get(ResourcePackStorage.class).getItems();

            for (ItemEntry itemEntry : itemEntries) {
                final boolean dataDrivenComponent = itemEntry.version() == ItemVersion.DataDriven && itemRewriter.getComponentItems().contains(itemEntry.identifier());
                final boolean legacyCustomComponent = !itemEntry.identifier().startsWith("minecraft:")
                        && itemEntry.componentData() != null
                        && itemEntry.componentData().get("components") instanceof CompoundTag;
                if (itemEntry.componentData() != null && (dataDrivenComponent || legacyCustomComponent)) {
                    itemDefinitions.addFromNetworkTag(itemEntry.identifier(), itemEntry.componentData());
                }
            }
            wrapper.user().get(PlayerArmorHudTracker.class).markDirty();
            if (!itemRewriter.getItems().isEmpty()) {
                wrapper.user().get(InventoryBootstrapQueue.class).onItemRegistryReady();
            }
        });
        protocol.registerClientboundTransition(ClientboundBedrockPackets.AVAILABLE_ENTITY_IDENTIFIERS,
                State.CONFIGURATION, (PacketHandler) PacketWrapper::cancel, // Bedrock client ignores entity identifiers before start game
                State.PLAY, (PacketHandler) wrapper -> {
                    wrapper.cancel();
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    final CompoundTag entityIdentifiers = (CompoundTag) wrapper.read(BedrockTypes.NETWORK_TAG); // entity identifiers
                    for (CompoundTag entityIdentifier : entityIdentifiers.getListTag("idlist", CompoundTag.class)) {
                        final String identifier = entityIdentifier.getString("id");
                        if (identifier != null) {
                            gameSession.addEntityIdentifier(identifier);
                        }
                    }
                }
        );
    }

    static ByteBuf copyDeferredStartGamePayload(final PacketWrapper wrapper) {
        final Channel channel = wrapper.user().getChannel();
        if (channel == null) {
            throw new IllegalStateException("Cannot defer START_GAME without an active channel");
        }

        final ByteBuf framedPacket = channel.alloc().buffer();
        try {
            final int packetId = wrapper.getId();
            wrapper.writeToBuffer(framedPacket);
            if (packetId != -1) {
                final int serializedPacketId = Types.VAR_INT.readPrimitive(framedPacket);
                if (serializedPacketId != packetId) {
                    throw new IllegalStateException("Serialized START_GAME packet id changed from "
                            + packetId + " to " + serializedPacketId);
                }
            }
            return framedPacket.readRetainedSlice(framedPacket.readableBytes());
        } finally {
            framedPacket.release();
        }
    }

    static CompletableFuture<ResourcePackStorage> initializePreparedResourcePackStorage(
            final CompletionStage<ResourcePackStorage> creation,
            final Function<ResourcePackStorage, ? extends CompletionStage<Void>> initializer) {
        Objects.requireNonNull(creation, "creation");
        Objects.requireNonNull(initializer, "initializer");
        final CompletableFuture<ResourcePackStorage> ready = new CompletableFuture<>();
        creation.whenComplete((storage, creationError) -> {
            if (creationError != null) {
                ready.completeExceptionally(unwrapCompletion(creationError));
                return;
            }
            if (storage == null) {
                ready.completeExceptionally(
                        new IllegalStateException("Resource pack creation returned no storage"));
                return;
            }
            if (ready.isDone()) {
                cleanupDeferredStorage(storage);
                return;
            }

            final CompletionStage<Void> initialization;
            try {
                initialization = Objects.requireNonNull(
                        initializer.apply(storage), "resource pack initializer returned null");
            } catch (Throwable error) {
                cleanupDeferredStorage(storage);
                ready.completeExceptionally(error);
                return;
            }
            initialization.whenComplete((ignored, initializationError) -> {
                if (initializationError != null) {
                    cleanupDeferredStorage(storage);
                    ready.completeExceptionally(unwrapCompletion(initializationError));
                } else if (!ready.complete(storage)) {
                    cleanupDeferredStorage(storage);
                }
            });
        });
        return ready;
    }

    static void resumeStartGameAfterResourcePackPreparation(
            final UserConnection user, final ByteBuf payload,
            final CompletableFuture<ResourcePackStorage> preparation,
            final DeferredStartGameReplayer replayer,
            final BiConsumer<UserConnection, Throwable> failureHandler) {
        resumeStartGameAfterResourcePackPreparation(
                user, payload, preparation, false, replayer, failureHandler);
    }

    static void resumeStartGameAfterResourcePackPreparation(
            final UserConnection user, final ByteBuf payload,
            final CompletableFuture<ResourcePackStorage> preparation,
            final boolean sessionOwnedPreparation,
            final DeferredStartGameReplayer replayer,
            final BiConsumer<UserConnection, Throwable> failureHandler) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(replayer, "replayer");
        Objects.requireNonNull(failureHandler, "failureHandler");

        final Channel channel = user.getChannel();
        if (channel == null) {
            ReferenceCountUtil.safeRelease(payload);
            if (!sessionOwnedPreparation) preparation.cancel(false);
            return;
        }

        final AtomicReference<ByteBuf> pendingPayload = new AtomicReference<>(payload);
        channel.closeFuture().addListener(ignored -> {
            ReferenceCountUtil.safeRelease(pendingPayload.getAndSet(null));
            if (!sessionOwnedPreparation) preparation.cancel(false);
        });
        preparation.whenComplete((storage, buildError) -> {
            final ByteBuf ownedPayload = pendingPayload.getAndSet(null);
            if (ownedPayload == null) {
                if (!sessionOwnedPreparation) cleanupDeferredStorage(storage);
                return;
            }

            if (buildError != null || storage == null) {
                ReferenceCountUtil.safeRelease(ownedPayload);
                final Throwable failure = buildError != null ? unwrapCompletion(buildError)
                        : new IllegalStateException("Resource pack preparation returned no storage");
                if (!executeDeferredStartGame(channel, () -> {
                    if (channel.isActive()) failureHandler.accept(user, failure);
                })) {
                    channel.close();
                }
                return;
            }

            if (!executeDeferredStartGame(channel, () -> {
                boolean storageAccountedFor = false;
                try {
                    if (!channel.isActive()) {
                        if (!sessionOwnedPreparation) cleanupDeferredStorage(storage);
                        storageAccountedFor = true;
                        return;
                    }

                    final ResourcePackStorage existing = user.get(ResourcePackStorage.class);
                    if (sessionOwnedPreparation && existing != storage) {
                        throw new IllegalStateException(
                                "Resource pack session completed without publishing its runtime");
                    } else if (existing == null) {
                        user.put(storage);
                    } else if (existing != storage && !sessionOwnedPreparation) {
                        cleanupDeferredStorage(storage);
                    }
                    storageAccountedFor = true;
                    replayer.replay(user, ownedPayload);
                } catch (Throwable error) {
                    if (!storageAccountedFor && !sessionOwnedPreparation) cleanupDeferredStorage(storage);
                    if (channel.isActive()) failureHandler.accept(user, error);
                } finally {
                    ReferenceCountUtil.safeRelease(ownedPayload);
                }
            })) {
                ReferenceCountUtil.safeRelease(ownedPayload);
                if (!sessionOwnedPreparation) cleanupDeferredStorage(storage);
                channel.close();
            }
        });
    }

    private static boolean executeDeferredStartGame(final Channel channel, final Runnable task) {
        try {
            channel.eventLoop().execute(task);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cleanupDeferredStorage(final ResourcePackStorage storage) {
        if (storage != null) storage.onRemove();
    }

    private static Throwable unwrapCompletion(final Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    interface DeferredStartGameReplayer {

        void replay(UserConnection user, ByteBuf payload) throws Exception;
    }

    public record PendingStartGame(
            String levelName,
            Difficulty difficulty,
            float rainLevel,
            float lightningLevel,
            int currentTime,
            int chunkTickRange,
            GameRule[] gameRules,
            BlockProperties[] blockProperties,
            boolean hashedRuntimeBlockIds,
            Dimension dimension,
            ClientPlayerEntity clientPlayer,
            String serverEngine,
            String vanillaVersion,
            List<String> enabledFeatures
    ) {
    }

    public static void finishCustomMappingStartGame(final UserConnection user, final PendingStartGame pending) {
        final JoinGate joinGate = JoinGate.install(user);
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        gameSession.setServerBrand(formatServerBrand(pending.serverEngine(), pending.vanillaVersion()));
        user.put(new JoinGameStorage(pending.levelName(), pending.difficulty(), pending.rainLevel(), pending.lightningLevel(), pending.currentTime(), pending.chunkTickRange()));
        user.put(new GameRulesStorage(user, pending.gameRules()));
        user.put(new BlockStateRewriter(user, pending.blockProperties(), pending.hashedRuntimeBlockIds()));
        user.put(new ItemRewriter(user, new ItemEntry[0]));
        user.put(new ChunkTracker(user, pending.dimension()));
        final EntityTracker entityTracker = new EntityTracker(user);
        entityTracker.addEntity(pending.clientPlayer(), false);
        user.put(entityTracker);

        sendJavaConfigurationOutputs(user, pending.enabledFeatures(), () -> {
            final PacketWrapper requestChunkRadius = PacketWrapper.create(ServerboundBedrockPackets.REQUEST_CHUNK_RADIUS, user);
            requestChunkRadius.write(BedrockTypes.VAR_INT, user.get(ClientSettingsStorage.class).viewDistance()); // radius
            requestChunkRadius.write(Types.BYTE, ProtocolConstants.BEDROCK_REQUEST_CHUNK_RADIUS_MAX_RADIUS); // max radius
            requestChunkRadius.sendToServer(BedrockProtocol.class);
            PacketFactory.sendBedrockLoadingScreen(user, ServerboundLoadingScreenPacketType.StartLoadingScreen, null);
        });
    }

    static String formatServerBrand(final String serverEngine, final String vanillaVersion) {
        return "Bedrock" + (!serverEngine.isEmpty() ? " @" + serverEngine : "") + " v: " + vanillaVersion;
    }

    public static void sendConfigurationBrand(final UserConnection user, final String brand) {
        final PacketWrapper brandCustomPayload = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, user);
        brandCustomPayload.write(Types.STRING, "minecraft:brand"); // channel
        brandCustomPayload.write(Types.STRING, brand); // content
        brandCustomPayload.send(BedrockProtocol.class);
        if (user.getChannel() != null) {
            user.getChannel().flush();
        }
    }

    private static void sendClientCacheStatus(final UserConnection user) {
        final PacketWrapper clientCacheStatus = PacketWrapper.create(ServerboundBedrockPackets.CLIENT_CACHE_STATUS, user);
        clientCacheStatus.write(Types.BOOLEAN, !ViaBedrock.getConfig().getBlobCacheMode().equals(ViaBedrockConfig.BlobCacheMode.DISABLED)); // is supported
        clientCacheStatus.sendToServer(BedrockProtocol.class);
    }

    private static void writePlayStatusKickMessage(final PacketWrapper wrapper, final PlayStatus status) {
        final Map<String, String> translations = BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(DataValues.VANILLA_RESOURCE_PACK_KEY).content().getLang("texts/en_US.lang");

        switch (status) {
            case LoginFailed_ClientOld -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.outdatedClient"));
            case LoginFailed_ServerOld -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.outdatedServer"));
            case LoginFailed_InvalidTenant -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.invalidTenant"));
            case LoginFailed_EditionMismatchEduToVanilla -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.editionMismatchEduToVanilla"));
            case LoginFailed_EditionMismatchVanillaToEdu -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.editionMismatchVanillaToEdu"));
            case LoginFailed_ServerFullSubClient, LoginFailed_EditorMismatchVanillaToEditor ->
                    PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.serverFull") + "\n\n\n\n" + translations.get("disconnectionScreen.serverFull.title"));
            case LoginFailed_EditorMismatchEditorToVanilla -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.editor.mismatchEditorToVanilla"));
            case PlayerSpawn, LoginSuccess -> wrapper.cancel();
            default -> throw new IllegalStateException("Unhandled PlayStatus: " + status);
        }
    }

    private static void handleJavaClientGameJoin(final UserConnection user) {
        sendJavaConfigurationOutputs(user, Collections.emptyList(), () -> sendJavaLoginAndInitialPackets(user));
    }

    public static void sendJavaConfigurationOutputs(final UserConnection user, final List<String> enabledFeatures, final Runnable afterFinish) {
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);

        if (enabledFeatures != null && !enabledFeatures.isEmpty()) {
            final List<String> features = new ArrayList<>(enabledFeatures);
            features.add("minecraft:vanilla");
            final PacketWrapper updateEnabledFeatures = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.UPDATE_ENABLED_FEATURES, user);
            updateEnabledFeatures.write(Types.STRING_ARRAY, features.toArray(new String[0])); // enabled features
            updateEnabledFeatures.send(BedrockProtocol.class);
        }

        for (Map.Entry<String, Tag> registry : gameSession.getJavaRegistries().entrySet()) {
            final CompoundTag registryTag = (CompoundTag) registry.getValue();
            final PacketWrapper registryData = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.REGISTRY_DATA, user);
            registryData.write(Types.STRING, registry.getKey()); // registry key
            final List<RegistryEntry> entries = new ArrayList<>();
            for (Map.Entry<String, Tag> entry : registryTag.entrySet()) {
                entries.add(new RegistryEntry(entry.getKey(), entry.getValue()));
            }
            registryData.write(Types.REGISTRY_ENTRY_ARRAY, entries.toArray(new RegistryEntry[0])); // registry entries
            registryData.send(BedrockProtocol.class);
        }

        final PacketWrapper updateTags = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.UPDATE_TAGS, user);
        updateTags.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaTags().size()); // number of registries
        for (Map.Entry<String, Tag> registryEntry : BedrockProtocol.MAPPINGS.getJavaTags().entrySet()) {
            final CompoundTag tag = (CompoundTag) registryEntry.getValue();
            updateTags.write(Types.STRING, registryEntry.getKey()); // registry key
            updateTags.write(Types.VAR_INT, tag.size()); // number of tags
            for (Map.Entry<String, Tag> tagEntry : tag.entrySet()) {
                updateTags.write(Types.STRING, tagEntry.getKey()); // tag name
                updateTags.write(Types.VAR_INT_ARRAY_PRIMITIVE, ((IntArrayTag) tagEntry.getValue()).getValue().clone()); // tag ids
            }
        }
        updateTags.send(BedrockProtocol.class);

        ECClientLightInterface.finishConfigurationWhenReady(user, sequenceConfigurationCompletion(() -> {
            final PacketWrapper finishConfiguration = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.FINISH_CONFIGURATION, user);
            finishConfiguration.send(BedrockProtocol.class);
            user.getProtocolInfo().setServerState(State.PLAY);
            if (user.getProtocolInfo().protocolVersion().betweenInclusive(ProtocolVersion.v1_20_2, ProtocolVersion.v1_21_2)) { // VB compatibility
                // Problematic code: https://github.com/ViaVersion/ViaBackwards/blob/b90b573f1d6f4d59841a3243e5bd072a43ec78e5/common/src/main/java/com/viaversion/viabackwards/protocol/v1_21_4to1_21_2/rewriter/EntityPacketRewriter1_21_4.java#L109
                user.getProtocolInfo().setClientState(State.PLAY); // Wrong, but needed because ViaBackwards expects this and would otherwise send the player loaded packet in configuration state.
            }
            user.get(InventoryBootstrapQueue.class).onPlayReady();
        }, afterFinish));
    }

    static Runnable sequenceConfigurationCompletion(final Runnable finishConfiguration, final Runnable afterFinish) {
        return () -> {
            finishConfiguration.run();
            afterFinish.run();
        };
    }

    public static void sendJavaLoginAndInitialPackets(final UserConnection user) {
        final JoinGameStorage joinGameStorage = user.get(JoinGameStorage.class);
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final ClientSettingsStorage clientSettingsStorage = user.get(ClientSettingsStorage.class);
        final GameRulesStorage gameRulesStorage = user.get(GameRulesStorage.class);
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final CommandsStorage commandsStorage = user.get(CommandsStorage.class);
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();

        final PacketWrapper joinGame = PacketWrapper.create(ClientboundPackets26_1.LOGIN, user);
        joinGame.write(Types.INT, clientPlayer.javaId()); // entity id
        joinGame.write(Types.BOOLEAN, gameSession.isHardcoreMode()); // hardcore
        joinGame.write(Types.STRING_ARRAY, Dimension.getDimensionKeys()); // dimension types
        joinGame.write(Types.VAR_INT, 100); // max players
        joinGame.write(Types.VAR_INT, clientSettingsStorage.viewDistance()); // view distance
        joinGame.write(Types.VAR_INT, joinGameStorage.chunkTickRange()); // simulation distance
        joinGame.write(Types.BOOLEAN, ViaBedrock.getConfig().shouldTranslateShowCoordinatesGameRule() && !gameRulesStorage.<Boolean>getGameRule("showCoordinates")); // reduced debug info
        joinGame.write(Types.BOOLEAN, !gameRulesStorage.<Boolean>getGameRule("doImmediateRespawn")); // show death screen
        joinGame.write(Types.BOOLEAN, gameRulesStorage.getGameRule("doLimitedCrafting")); // limited crafting
        joinGame.write(Types.VAR_INT, chunkTracker.getDimension().ordinal()); // dimension id
        joinGame.write(Types.STRING, chunkTracker.getDimension().getKey()); // dimension name
        joinGame.write(Types.LONG, 0L); // hashed seed
        joinGame.write(Types.BYTE, (byte) clientPlayer.javaGameMode().ordinal()); // game mode
        joinGame.write(Types.BYTE, (byte) -1); // previous game mode
        joinGame.write(Types.BOOLEAN, false); // is debug
        joinGame.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // is flat
        joinGame.write(Types.OPTIONAL_GLOBAL_POSITION, null); // last death location
        joinGame.write(Types.VAR_INT, 0); // portal cooldown
        joinGame.write(Types.VAR_INT, 64); // sea level
        joinGame.write(Types.BOOLEAN, false); // enforce secure chat
        joinGame.send(BedrockProtocol.class);

        final String serverBrand = gameSession.getServerBrand();
        if (serverBrand != null) {
            final PacketWrapper brandCustomPayload = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            brandCustomPayload.write(Types.STRING, "minecraft:brand"); // channel
            brandCustomPayload.write(Types.STRING, serverBrand); // content
            brandCustomPayload.send(BedrockProtocol.class);
        }

        clientPlayer.createTeam();
        clientPlayer.sendInitialEntityData();
        clientPlayer.updateAttributes(clientPlayer.attributes().values().toArray(new EntityAttribute[0]));
        clientPlayer.setAbilities(clientPlayer.abilities());
        clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
        if (commandsStorage != null) {
            commandsStorage.updateCommandTree();
        }
        if (inventoryTracker != null) {
            PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
        }
        user.get(PlayerArmorHudTracker.class).forceSync();
        chunkTracker.sendCurrentCacheSettingsToJava();

        final PacketWrapper initializeBorder = PacketWrapper.create(ClientboundPackets26_1.INITIALIZE_BORDER, user);
        initializeBorder.write(Types.DOUBLE, 0D); // center x
        initializeBorder.write(Types.DOUBLE, 0D); // center z
        initializeBorder.write(Types.DOUBLE, 0D); // old size
        initializeBorder.write(Types.DOUBLE, 60_000_000D); // new size
        initializeBorder.write(Types.VAR_LONG, 0L); // lerp time
        initializeBorder.write(Types.VAR_INT, 60_000_000); // new absolute max size
        initializeBorder.write(Types.VAR_INT, 0); // warning blocks
        initializeBorder.write(Types.VAR_INT, 0); // warning time
        initializeBorder.send(BedrockProtocol.class);

        final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, user);
        updateAttributes.write(Types.VAR_INT, clientPlayer.javaId()); // entity id
        updateAttributes.write(Types.VAR_INT, 1); // attribute count
        updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.ATTACK_SPEED)); // attribute id
        updateAttributes.write(Types.DOUBLE, 20D); // base value
        updateAttributes.write(Types.VAR_INT, 0); // modifier count
        updateAttributes.send(BedrockProtocol.class);

        final PacketWrapper serverDifficulty = PacketWrapper.create(ClientboundPackets26_1.CHANGE_DIFFICULTY, user);
        serverDifficulty.write(Types.VAR_INT, joinGameStorage.difficulty().getValue()); // difficulty
        serverDifficulty.write(Types.BOOLEAN, false); // locked
        serverDifficulty.send(BedrockProtocol.class);

        final ViaBedrockConfig config = ViaBedrock.getConfig();
        if (config.shouldSendTabList()) {
            final PacketWrapper tabList = PacketWrapper.create(ClientboundPackets26_1.TAB_LIST, user);
            final String header = resolveTabListPlaceholders(config.getTabListHeader(), joinGameStorage.levelName());
            final String footer = resolveTabListPlaceholders(config.getTabListFooter(), joinGameStorage.levelName());
            tabList.write(Types.TAG, TextUtil.stringToNbt(header));
            tabList.write(Types.TAG, TextUtil.stringToNbt(footer));
            tabList.send(BedrockProtocol.class);
        }

        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_GAME_MODE, PlayerInfoUpdateAction.UPDATE_LATENCY)); // actions
        playerInfoUpdate.write(Types.VAR_INT, 1); // length
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.STRING, StringUtil.encodeUUID(clientPlayer.javaUuid())); // username
        playerInfoUpdate.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[0]); // properties
        playerInfoUpdate.write(Types.VAR_INT, clientPlayer.javaGameMode().ordinal()); // game mode
        playerInfoUpdate.write(Types.VAR_INT, user.get(PacketSyncStorage.class).latencyMillis()); // latency
        playerInfoUpdate.send(BedrockProtocol.class);

        if (joinGameStorage.rainLevel() > 0F || joinGameStorage.lightningLevel() > 0F) {
            PacketFactory.sendJavaGameEvent(user, GameEventType.START_RAINING, 0F);
            if (joinGameStorage.rainLevel() > 0F) {
                PacketFactory.sendJavaGameEvent(user, GameEventType.RAIN_LEVEL_CHANGE, joinGameStorage.rainLevel());
            }
            if (joinGameStorage.lightningLevel() > 0F) {
                PacketFactory.sendJavaGameEvent(user, GameEventType.THUNDER_LEVEL_CHANGE, joinGameStorage.lightningLevel());
            }
        }

        final PacketWrapper setTime = PacketWrapper.create(ClientboundBedrockPackets.SET_TIME, user);
        setTime.write(BedrockTypes.VAR_INT, joinGameStorage.currentTime()); // time of day
        setTime.send(BedrockProtocol.class, false);
    }

}
