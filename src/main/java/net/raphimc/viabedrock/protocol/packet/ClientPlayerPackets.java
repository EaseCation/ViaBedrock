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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.util.Pair;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.api.util.InstantBreakBlocks;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.ItemUseSemantics;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.inventory.ItemUseHandContext;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.*;
import net.raphimc.viabedrock.protocol.model.Position2f;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.BlockNeighborView;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.TrackerNeighborView;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class ClientPlayerPackets {

    private static final Set<PlayerAuthInputPacket_InputData> IMMOBILE_MOVEMENT_INPUTS = EnumSet.of(
            PlayerAuthInputPacket_InputData.Ascend,
            PlayerAuthInputPacket_InputData.Descend,
            PlayerAuthInputPacket_InputData.JumpDown,
            PlayerAuthInputPacket_InputData.SprintDown,
            PlayerAuthInputPacket_InputData.ChangeHeight,
            PlayerAuthInputPacket_InputData.Jumping,
            PlayerAuthInputPacket_InputData.AutoJumpingInWater,
            PlayerAuthInputPacket_InputData.SneakDown,
            PlayerAuthInputPacket_InputData.Up,
            PlayerAuthInputPacket_InputData.Down,
            PlayerAuthInputPacket_InputData.Left,
            PlayerAuthInputPacket_InputData.Right,
            PlayerAuthInputPacket_InputData.UpLeft,
            PlayerAuthInputPacket_InputData.UpRight,
            PlayerAuthInputPacket_InputData.WantUp,
            PlayerAuthInputPacket_InputData.WantDown,
            PlayerAuthInputPacket_InputData.WantDownSlow,
            PlayerAuthInputPacket_InputData.WantUpSlow,
            PlayerAuthInputPacket_InputData.Sprinting,
            PlayerAuthInputPacket_InputData.AscendBlock,
            PlayerAuthInputPacket_InputData.DescendBlock,
            PlayerAuthInputPacket_InputData.DownLeft,
            PlayerAuthInputPacket_InputData.DownRight,
            PlayerAuthInputPacket_InputData.StartJumping,
            PlayerAuthInputPacket_InputData.JumpPressedRaw,
            PlayerAuthInputPacket_InputData.JumpCurrentRaw,
            PlayerAuthInputPacket_InputData.SneakPressedRaw,
            PlayerAuthInputPacket_InputData.SneakCurrentRaw
    );

    private static final PacketHandler CLIENT_PLAYER_GAME_MODE_INFO_UPDATE = wrapper -> {
        final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
        final GameMode gameMode = wrapper.user().get(SpectatorCameraTracker.class).projectJavaGameMode(clientPlayer.javaGameMode());

        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper.user());
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // actions
        playerInfoUpdate.write(Types.VAR_INT, 1); // length
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.VAR_INT, gameMode.ordinal()); // game mode
        playerInfoUpdate.send(BedrockProtocol.class);
    };

    private static final PacketHandler CLIENT_PLAYER_GAME_MODE_UPDATE = wrapper -> {
        final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
        final GameMode gameMode = wrapper.user().get(SpectatorCameraTracker.class).projectJavaGameMode(clientPlayer.javaGameMode());
        PacketFactory.sendJavaGameEvent(wrapper.user(), GameEventType.CHANGE_GAME_MODE, gameMode.ordinal());
    };

    public static void sendJavaGameMode(final UserConnection user, final GameMode gameMode) {
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // actions
        playerInfoUpdate.write(Types.VAR_INT, 1); // length
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.VAR_INT, gameMode.ordinal()); // game mode
        playerInfoUpdate.send(BedrockProtocol.class);

        PacketFactory.sendJavaGameEvent(user, GameEventType.CHANGE_GAME_MODE, gameMode.ordinal());
    }

    private static boolean isInstantBreak(final UserConnection user, final ChunkTracker chunkTracker, final BlockPosition position) {
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final int javaBlockStateId = chunkTracker.getJavaBlockState(position);
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        final CustomMappingSyncStorage customMappingSync = user.get(CustomMappingSyncStorage.class);
        final String heldIdentifier = user.get(ItemRewriter.class).bedrockIdentifier(
                user.get(InventoryTracker.class).getInventoryContainer().getSelectedHotbarItem());
        final String customIdentifier = customMappingSync != null
                ? customMappingSync.access().identifierByJavaBlockStateId(javaBlockStateId) : null;
        final Float customSeconds = customMappingSync != null
                ? customMappingSync.access().secondsToDestroy(javaBlockStateId) : null;
        return InstantBreakBlocks.shouldCompleteOnJavaStart(
                clientPlayer != null && clientPlayer.javaGameMode() == GameMode.CREATIVE,
                javaBlockState != null ? javaBlockState.identifier() : null,
                customSeconds,
                heldIdentifier,
                customIdentifier
        );
    }

    private static void finishBlockBreak(final UserConnection user, final GameSessionStorage gameSession, final ClientPlayerEntity clientPlayer, final ChunkTracker chunkTracker, final BlockPosition position, final Direction direction) {
        if (!gameSession.isBlockBreakingServerAuthoritative()) {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StopDestroyBlock));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, position, 0));
        } else {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, position, 0));
        }

        chunkTracker.handleBlockChange(position, 0, chunkTracker.bedrockAirId());
        PacketFactory.sendJavaBlockUpdate(user, position, ProtocolConstants.JAVA_AIR_ID);
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.RESPAWN, ClientboundPackets26_1.RESPAWN, wrapper -> {
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            final byte rawState = wrapper.read(Types.BYTE); // state
            final PlayerRespawnState state = PlayerRespawnState.getByValue(rawState);
            if (state == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayerRespawnState: " + rawState);
                wrapper.cancel();
                return;
            }
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id

            switch (state) {
                case ReadyToSpawn -> {
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    clientPlayer.setPosition(position);

                    if (clientPlayer.isInitiallySpawned()) {
                        final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                        final GameRulesStorage gameRulesStorage = wrapper.user().get(GameRulesStorage.class);
                        final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
                        final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                        wrapper.user().get(JavaPlayerStateStorage.class).reset();

                        if (clientPlayer.isDead() && !gameRulesStorage.<Boolean>getGameRule("keepInventory")) {
                            inventoryTracker.getInventoryContainer().clearItems();
                            inventoryTracker.getOffhandContainer().clearItems();
                            inventoryTracker.getArmorContainer().clearItems();
                            inventoryTracker.getHudContainer().clearItems();
                            // TODO: InventoryTransactionPacket(legacyRequestId=0, legacySlots=[], actions=[], transactionType=INVENTORY_MISMATCH, actionType=0, entityRuntimeId=0, blockPosition=null, blockFace=0, hotbarSlot=0, itemInHand=null, playerPosition=null, clickPosition=null, headPosition=null, usingNetIds=false, blockDefinition=null)
                        }
                        clientPlayer.clearEffects();

                        clientPlayer.setHealth(clientPlayer.attributes().get("minecraft:health").maxValue());
                        clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.Respawn, -1);
                        wrapper.write(Types.VAR_INT, chunkTracker.getDimension().ordinal()); // dimension id
                        wrapper.write(Types.STRING, chunkTracker.getDimensionKey()); // dimension name
                        wrapper.write(Types.LONG, 0L); // hashed seed
                        final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
                        wrapper.write(Types.BYTE, (byte) spectatorCamera.projectJavaGameMode(clientPlayer.javaGameMode()).ordinal()); // game mode
                        wrapper.write(Types.BYTE, (byte) -1); // previous game mode
                        wrapper.write(Types.BOOLEAN, false); // is debug
                        wrapper.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // is flat
                        wrapper.write(Types.OPTIONAL_GLOBAL_POSITION, null); // last death position
                        wrapper.write(Types.VAR_INT, 0); // portal cooldown
                        wrapper.write(Types.VAR_INT, 64); // sea level
                        wrapper.write(Types.BYTE, (byte) (RespawnKeepFlag.ATTRIBUTE_MODIFIERS.getBit() | RespawnKeepFlag.ENTITY_DATA.getBit())); // keep data mask
                        PacketLeftoverLayout.discardUnreadInput(wrapper);
                        wrapper.send(BedrockProtocol.class);
                        clientPlayer.sendAttribute("minecraft:health"); // Ensure health is synced
                        wrapper.user().get(PlayerArmorHudTracker.class).forceSync();
                        chunkTracker.resetJavaChunkLoading();
                        clientPlayer.setDimensionChangeInfo(null);
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer()); // Java client always resets inventory on respawn. Resend it
                        inventoryTracker.getInventoryContainer().sendSelectedHotbarSlotToClient(); // Java client always resets selected hotbar slot on respawn. Resend it
                        spectatorCamera.restorePresentationAfterClientReset();
                    }
                    wrapper.cancel();

                    clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
                }
                case SearchingForSpawn, ClientReadyToSpawn -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled PlayerRespawnState: " + state);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS, null, wrapper -> {
            wrapper.cancel();
            final UpdateClientInputLocksLayout.DecodedLocks locks = UpdateClientInputLocksLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            if (clientPlayer == null) {
                return;
            }
            final boolean wasLocked = clientPlayer.isInputMovementLocked();
            clientPlayer.setInputMovementLocked(locks.movementLocked());
            if (locks.movementLocked()) {
                if (locks.serverPosition() != null) {
                    clientPlayer.setPosition(locks.serverPosition());
                }
                if (!wasLocked) {
                    clientPlayer.beginPositionSync(Relative.ROTATION);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ACTION, null, wrapper -> {
            wrapper.cancel();
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final int rawAction = wrapper.read(BedrockTypes.VAR_INT); // action
            final PlayerActionType action = PlayerActionType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayerActionType: " + rawAction);
                return;
            }
            wrapper.read(BedrockTypes.BLOCK_POSITION); // block position
            wrapper.read(BedrockTypes.BLOCK_POSITION); // result position
            wrapper.read(BedrockTypes.VAR_INT); // face

            if (action == PlayerActionType.ChangeDimensionAck) {
                final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                if (clientPlayer.dimensionChangeInfo() != null) {
                    clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
                    PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, clientPlayer.dimensionChangeInfo().loadingScreenId());
                    clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
                    clientPlayer.setDimensionChangeInfo(null);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CORRECT_PLAYER_MOVE_PREDICTION, ClientboundPackets26_1.PLAYER_POSITION, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final byte rawRewindType = wrapper.read(Types.BYTE); // rewind type
            final RewindType rewindType = RewindType.getByValue(rawRewindType);
            if (rewindType == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown RewindType: " + rawRewindType);
                return;
            }
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            wrapper.read(BedrockTypes.POSITION_3F); // position delta
            wrapper.read(BedrockTypes.POSITION_2F); // vehicle rotation
            if (wrapper.read(Types.BOOLEAN)) {
                wrapper.read(BedrockTypes.FLOAT_LE); // vehicle angular velocity
            }
            switch (rewindType) {
                case Player -> {
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    final boolean onGround = wrapper.read(Types.BOOLEAN); // on ground
                    final long tick = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick
                    if (tick > clientPlayer.age() || tick < clientPlayer.age() - gameSession.getMovementRewindHistorySize()) {
                        wrapper.cancel();
                        return;
                    }

                    clientPlayer.setPosition(position);
                    clientPlayer.setOnGround(onGround);
                    clientPlayer.writePlayerPositionPacketToClient(wrapper, Relative.union(Relative.ROTATION, Relative.VELOCITY), true);
                }
                case Vehicle -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled RewindType: " + rewindType);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_PLAYER_GAME_TYPE, null, new PacketHandlers() {
            @Override
            protected void register() {
                handler(wrapper -> {
                    wrapper.cancel();
                    wrapper.user().get(EntityTracker.class).getClientPlayer().setGameType(GameType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GameType.Undefined)); // game type
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_DEFAULT_GAME_TYPE, null, new PacketHandlers() {
            @Override
            protected void register() {
                handler(wrapper -> {
                    wrapper.cancel();
                    wrapper.user().get(GameSessionStorage.class).setLevelGameType(GameType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GameType.Undefined)); // game type
                    wrapper.user().get(EntityTracker.class).getClientPlayer().updateJavaGameMode();
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_PLAYER_GAME_TYPE, ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final PlayerListStorage playerList = wrapper.user().get(PlayerListStorage.class);

            final GameType gameType = GameType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GameType.Undefined); // game type
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick

            final Pair<UUID, String> playerListEntry = playerList.getPlayer(entityUniqueId);
            if (playerListEntry == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // actions
            wrapper.write(Types.VAR_INT, 1); // length
            wrapper.write(Types.UUID, playerListEntry.key()); // uuid
            final boolean clientPlayerUpdate = playerListEntry.key().equals(clientPlayer.javaUuid());
            GameMode javaGameMode = GameTypeRewriter.getEffectiveGameMode(gameType, gameSession.getLevelGameType());
            playerList.updateJavaGameMode(playerListEntry.key(), javaGameMode);
            if (clientPlayerUpdate) {
                clientPlayer.setGameType(gameType);
                javaGameMode = wrapper.user().get(SpectatorCameraTracker.class).projectJavaGameMode(clientPlayer.javaGameMode());
                CLIENT_PLAYER_GAME_MODE_UPDATE.handle(wrapper);
            }
            final SpectatorMenuProjection spectatorMenu = wrapper.user().get(SpectatorMenuProjection.class);
            if (spectatorMenu.isActive()) {
                wrapper.cancel();
                spectatorMenu.refreshProfile(playerListEntry.key());
                return;
            }
            wrapper.write(Types.VAR_INT, javaGameMode.ordinal()); // game mode
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_ADVENTURE_SETTINGS, null, wrapper -> {
            wrapper.cancel();
            wrapper.read(Types.BOOLEAN); // no player vs mobs
            wrapper.read(Types.BOOLEAN); // no mobs vs player
            wrapper.user().get(GameSessionStorage.class).setImmutableWorld(wrapper.read(Types.BOOLEAN)); // immutable world
            wrapper.read(Types.BOOLEAN); // show name tags
            wrapper.read(Types.BOOLEAN); // auto jump
        });
        protocol.registerClientbound(ClientboundBedrockPackets.OPEN_SIGN, ClientboundPackets26_1.OPEN_SIGN_EDITOR, new PacketHandlers() {
            @Override
            protected void register() {
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // position
                map(Types.BOOLEAN); // front
            }
        });

        protocol.registerServerbound(ServerboundPackets26_1.CLIENT_COMMAND, ServerboundBedrockPackets.RESPAWN, wrapper -> {
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final ClientCommandAction action = ClientCommandAction.values()[wrapper.read(Types.VAR_INT)]; // action

            switch (action) {
                case PERFORM_RESPAWN -> {
                    wrapper.write(BedrockTypes.POSITION_3F, Position3f.ZERO); // position
                    wrapper.write(Types.BYTE, (byte) PlayerRespawnState.ClientReadyToSpawn.getValue()); // state
                    wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
                }
                case REQUEST_STATS, REQUEST_GAMERULE_VALUES -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled ClientCommandAction: " + action);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.TELEPORT_TO_ENTITY, null, wrapper -> {
            wrapper.cancel();
            final UUID targetId = wrapper.read(Types.UUID);
            wrapper.user().get(SpectatorCameraTracker.class).requestTarget(targetId);
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_COMMAND, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            wrapper.read(Types.VAR_INT); // entity id
            final PlayerCommandAction action = PlayerCommandAction.values()[wrapper.read(Types.VAR_INT)]; // action
            final int data = wrapper.read(Types.VAR_INT); // data
            final PlayerAuthInputPacket_InputData inputData = playerCommandInputData(action);

            if (action == PlayerCommandAction.START_SPRINTING) {
                // Nukkit START_SPRINTING calls setUsingItem(false). Keep eating from being cancelled
                // by a Java sprint that started after the use animation began.
                if (ItemUseSemantics.suppressStartSprintingWhileUsingItem(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), clientPlayer.isUsingItem())) {
                    return;
                }
                clientPlayer.setSprinting(true);
            } else if (action == PlayerCommandAction.STOP_SPRINTING) {
                clientPlayer.setSprinting(false);
            }
            clientPlayer.addAuthInputData(inputData);
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_ACTION, null, wrapper -> {
            wrapper.cancel();
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final PlayerActionAction action = PlayerActionAction.values()[wrapper.read(Types.VAR_INT)]; // action
            final BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // block position
            final Direction direction = Direction.values()[wrapper.read(Types.UNSIGNED_BYTE)]; // face
            final int sequence = wrapper.read(Types.VAR_INT); // sequence number

            final boolean isMining = action == PlayerActionAction.START_DESTROY_BLOCK || action == PlayerActionAction.ABORT_DESTROY_BLOCK || action == PlayerActionAction.STOP_DESTROY_BLOCK;
            if (isMining && (gameSession.isImmutableWorld() || !clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Mine))) {
                // TODO: Prevent breaking and cancel any packets that would be sent (swing, player action)
                final int rawBlockState = chunkTracker.getJavaBlockState(position);
                final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
                final int fixedBlockState = BedrockProtocol.MAPPINGS.getNeighborRewriter().resolveUpdate(view, position, rawBlockState).getOrDefault(position, rawBlockState);
                PacketFactory.sendJavaBlockUpdate(wrapper.user(), position, fixedBlockState);
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                return;
            }

            // TODO: Block breaking: Send correct inventory transactions

            switch (action) {
                case START_DESTROY_BLOCK -> {
                    clientPlayer.sendSwingPacketToServer();
                    clientPlayer.cancelNextSwingPacket();
                    // Creative and hardness-0 blocks: Java only sends START_DESTROY_BLOCK.
                    // TODO: Test breaking fire
                    // TODO: The java client keeps spamming swing packets while waiting for the block break cooldown. Those need to be cancelled

                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StartDestroyBlock, position, direction.ordinal()));
                    if (isInstantBreak(wrapper.user(), chunkTracker, position)) {
                        // Instant-break cases (creative, 0 destroy time, shears on leaves): Java only
                        // sends START_DESTROY_BLOCK and never a STOP. MOT SAI ignores CreativeDestroyBlock(13)
                        // and only breaks on PredictDestroyBlock, so finish in this same tick.
                        finishBlockBreak(wrapper.user(), gameSession, clientPlayer, chunkTracker, position, direction);
                    } else {
                        clientPlayer.setBlockBreakingInfo(new ClientPlayerEntity.BlockBreakingInfo(position, direction));
                    }
                }
                case ABORT_DESTROY_BLOCK -> {
                    clientPlayer.setBlockBreakingInfo(null);
                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, position, 0/*TODO: Figure this value out*/));
                }
                case STOP_DESTROY_BLOCK -> {
                    clientPlayer.cancelNextSwingPacket();
                    clientPlayer.setBlockBreakingInfo(null);
                    finishBlockBreak(wrapper.user(), gameSession, clientPlayer, chunkTracker, position, direction);
                }
                case DROP_ALL_ITEMS, DROP_ITEM -> {
                    // TODO: Implement DROP_ALL_ITEMS, DROP_ITEM (Currently experimental)
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), wrapper.user().get(InventoryTracker.class).getInventoryContainer());
                }
                case RELEASE_USE_ITEM -> {
                    // ExperimentalFeatures owns the Bedrock finish/cancel translation. Resyncing
                    // here would overwrite Nukkit's just-consumed stack with the pre-eat snapshot.
                }
                case SWAP_ITEM_WITH_OFFHAND -> {
                    if (!ExperimentalFeatures.tryHandleSwapHands(wrapper.user())) {
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), wrapper.user().get(InventoryTracker.class).getInventoryContainer());
                    }
                }
                case STAB -> {
                }
                default -> throw new IllegalStateException("Unhandled PlayerActionAction: " + action);
            }

            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.ATTACK, ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
            final Entity entity = entityTracker.getEntityByJid(entityId);
            if (entity == null) {
                if (!ExperimentalFeatures.tryHandleItemFrameAttack(wrapper.user(), entityId)) {
                    ExperimentalFeatures.tryHandleCustomBlockOverlayAttack(wrapper.user(), entityId);
                }
                wrapper.cancel();
                return;
            }

            wrapper.write(BedrockTypes.VAR_INT, 0); // legacy request id
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ComplexInventoryTransaction_Type.ItemUseOnEntityTransaction.getValue()); // transaction type
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // actions count
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, entity.runtimeId()); // entity runtime id
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue()); // action type
            wrapper.write(BedrockTypes.VAR_INT, (int) inventoryContainer.getSelectedHotbarSlot()); // hotbar slot
            wrapper.write(wrapper.user().get(ItemRewriter.class).itemType(), inventoryContainer.getSelectedHotbarItem()); // held item
            wrapper.write(BedrockTypes.POSITION_3F, entityTracker.getClientPlayer().position()); // player position
            // Java ATTACK has no click vector. MOT attack ignores clickPos; write the look-dir unit
            // vector so the field is not a literal ZERO (vanilla-like telemetry / future AC).
            wrapper.write(BedrockTypes.POSITION_3F, attackClickPosition(entityTracker.getClientPlayer())); // click position

            entityTracker.getClientPlayer().sendSwingPacketToServer();
            entityTracker.getClientPlayer().cancelNextSwingPacket();
        });
        protocol.registerServerbound(ServerboundPackets26_1.INTERACT, ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand
            final Entity entity = entityTracker.getEntityByJid(entityId);
            if (entity == null) {
                // Item frames and custom-block overlays are fake Java entities over real Bedrock blocks.
                // Translate the right-click into the block interaction the server expects.
                if (!ExperimentalFeatures.tryHandleItemFrameInteract(wrapper.user(), entityId, hand)) {
                    ExperimentalFeatures.tryHandleCustomBlockOverlayInteract(wrapper.user(), entityId, hand);
                }
                wrapper.cancel();
                return;
            }
            if (hand != InteractionHand.MAIN_HAND && !ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
                wrapper.cancel();
                return;
            }
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(wrapper.user().get(InventoryTracker.class), hand);

            // TODO: Bedrock client sends INTERACT packet when hovered entity changes. Might be used by anticheats

            wrapper.write(BedrockTypes.VAR_INT, 0); // legacy request id
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ComplexInventoryTransaction_Type.ItemUseOnEntityTransaction.getValue()); // transaction type
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // actions count
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, entity.runtimeId()); // entity runtime id
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue()); // action type
            wrapper.write(BedrockTypes.VAR_INT, handContext.transactionHotbarSlot()); // hotbar slot
            wrapper.write(wrapper.user().get(ItemRewriter.class).itemType(), handContext.item()); // held item
            wrapper.write(BedrockTypes.POSITION_3F, entityTracker.getClientPlayer().position()); // player position
            final Vector3d location = wrapper.read(Types.LOW_PRECISION_VECTOR); // location
            wrapper.write(BedrockTypes.POSITION_3F, entity.position().add((float) location.x(), (float) location.y(), (float) location.z())); // click position
            wrapper.read(Types.BOOLEAN); // using secondary action
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_STATUS_ONLY, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_POS, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), MathUtil.wrapDegrees(wrapper.read(Types.FLOAT)), wrapper.read(Types.FLOAT), wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_ROT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(MathUtil.wrapDegrees(wrapper.read(Types.FLOAT)), wrapper.read(Types.FLOAT), wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.ACCEPT_TELEPORTATION, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.confirmTeleport(wrapper.read(Types.VAR_INT)); // teleport id
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_INPUT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final Set<InputFlag> inputFlags = EnumUtil.getEnumSetFromBitmask(InputFlag.class, wrapper.read(Types.BYTE), InputFlag::ordinal); // input flags
            final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
            if (spectatorCamera.handleShiftInput(inputFlags.contains(InputFlag.SHIFT))) {
                inputFlags.remove(InputFlag.SHIFT);
                clientPlayer.setSneaking(false);
            }
            clientPlayer.setInputFlags(inputFlags);
        });
        protocol.registerServerbound(ServerboundPackets26_1.CLIENT_TICK_END, ServerboundBedrockPackets.PLAYER_AUTH_INPUT, wrapper -> {
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final Position3f prevPosition = clientPlayer.prevPosition();
            final boolean prevOnGround = clientPlayer.prevOnGround();
            final Set<InputFlag> prevInputFlags = clientPlayer.prevInputFlags();
            clientPlayer.tick();
            final boolean immobile = clientPlayer.isInputMovementLocked() || clientPlayer.hasEntityFlag(ActorFlags.NOAI);

            if (prevOnGround && clientPlayer.inputFlags().contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartJumping);
            }

            if (!clientPlayer.isInitiallySpawned() || clientPlayer.isDead()) {
                wrapper.cancel();
                return;
            }

            final PlayerAuthInputPacket_InputData crawlingTransition = wrapper.user()
                    .get(JavaPlayerStateStorage.class)
                    .consumeCrawlingTransition();
            if (crawlingTransition != null) {
                clientPlayer.addAuthInputData(crawlingTransition);
            }

            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.BlockBreakingDelayEnabled);
            if (clientPlayer.isOnGround()) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.VerticalCollision);
            }
            if (clientPlayer.horizontalCollision()) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.HorizontalCollision);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.FORWARD)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Up);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.BACKWARD)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Down);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.LEFT)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Left);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.RIGHT)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Right);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpDown, PlayerAuthInputPacket_InputData.Jumping, PlayerAuthInputPacket_InputData.WantUp, PlayerAuthInputPacket_InputData.JumpCurrentRaw);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.SHIFT)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakDown, PlayerAuthInputPacket_InputData.Sneaking, PlayerAuthInputPacket_InputData.WantDown, PlayerAuthInputPacket_InputData.SneakCurrentRaw);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.SPRINT)
                    && !ItemUseSemantics.suppressStartSprintingWhileUsingItem(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), clientPlayer.isUsingItem())) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SprintDown, PlayerAuthInputPacket_InputData.Sprinting);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.JUMP) && !prevInputFlags.contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpPressedRaw);
            }
            if (prevInputFlags.contains(InputFlag.JUMP) && !clientPlayer.inputFlags().contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpReleasedRaw);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.SHIFT) && !prevInputFlags.contains(InputFlag.SHIFT)) {
                clientPlayer.setSneaking(true);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakPressedRaw, PlayerAuthInputPacket_InputData.StartSneaking);
            }
            if (prevInputFlags.contains(InputFlag.SHIFT) && !clientPlayer.inputFlags().contains(InputFlag.SHIFT)) {
                clientPlayer.setSneaking(false);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakReleasedRaw, PlayerAuthInputPacket_InputData.StopSneaking);
            }

            final Position3f positionDelta = clientPlayer.position().subtract(prevPosition);
            final Position3f velocity;
            if (immobile) {
                velocity = Position3f.ZERO;
            } else if (!clientPlayer.isInitiallySpawned() || clientPlayer.dimensionChangeInfo() != null || clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Flying)) {
                velocity = positionDelta;
            } else {
                float dx = positionDelta.x() * 0.98F;
                float dy = positionDelta.y();
                float dz = positionDelta.z() * 0.98F;
                final float friction = clientPlayer.isOnGround() ? ProtocolConstants.BLOCK_FRICTION : 1F;
                dx *= friction;
                dz *= friction;

                if (clientPlayer.effects().containsKey("minecraft:levitation")) {
                    dy += (0.05F * (clientPlayer.effects().get("minecraft:levitation").amplifier() + 1)) * 0.2F;
                } else {
                    dy -= neteaseAuthInputGravity(wrapper.user());
                }
                // Slow falling does not change the velocity when standing still

                velocity = new Position3f(dx * 0.91F, dy * 0.98F, dz * 0.91F);
            }

            final PlayerAuthInputContext authInputContext = new PlayerAuthInputContext(clientPlayer.position(), velocity);
            ExperimentalFeatures.dispatchPlayerAuthInput(wrapper.user(), clientPlayer, authInputContext);
            if (immobile) {
                removeImmobileMovementInput(clientPlayer.authInputData());
                authInputContext.setPosition(clientPlayer.position());
                authInputContext.setDelta(Position3f.ZERO);
            }

            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().x()); // pitch
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().y()); // yaw
            wrapper.write(BedrockTypes.POSITION_3F, authInputContext.position()); // position
            wrapper.write(BedrockTypes.POSITION_2F, immobile ? new Position2f(0F, 0F) : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), clientPlayer.isSneaking())); // move vector
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().z()); // head yaw
            wrapper.write(BedrockTypes.UNSIGNED_VAR_BIG_INTEGER, PlayerAuthInputLayout.encodeBitmask(clientPlayer.authInputData())); // input flags
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, InputMode.Mouse.getValue()); // input mode
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ClientPlayMode.Screen.getValue()); // play mode
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, NewInteractionModel.Crosshair.getValue()); // interaction mode
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().x()); // interact pitch
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().y()); // interact yaw
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, (long) clientPlayer.age()); // tick
            wrapper.write(BedrockTypes.POSITION_3F, authInputContext.delta()); // delta
            if (PlayerAuthInputLayout.usesCameraDeparted()) {
                wrapper.write(Types.BOOLEAN, false); // camera departed (NetEase >= 422)
            }
            if (clientPlayer.authInputData().contains(PlayerAuthInputPacket_InputData.PerformItemInteraction)) {
                final BedrockInventoryTransaction itemInteraction = clientPlayer.authInputItemInteraction();
                if (itemInteraction != null) {
                    wrapper.write(wrapper.user().get(InventoryTransactionRewriter.class).getItemInteractionDataType(), itemInteraction);
                }
            }
            if (clientPlayer.authInputData().contains(PlayerAuthInputPacket_InputData.PerformBlockActions)) {
                wrapper.write(BedrockTypes.VAR_INT, clientPlayer.authInputBlockActions().size()); // player block actions count
                for (ClientPlayerEntity.AuthInputBlockAction blockAction : clientPlayer.authInputBlockActions()) {
                    wrapper.write(BedrockTypes.VAR_INT, blockAction.action().getValue()); // action
                    switch (blockAction.action()) {
                        // StopDestroyBlock does not have additional data even tho bedrock protocol docs claim it does
                        case StartDestroyBlock, AbortDestroyBlock, CrackBlock, PredictDestroyBlock, ContinueDestroyBlock -> {
                            wrapper.write(BedrockTypes.SIGNED_BLOCK_POSITION, blockAction.position()); // signed position
                            wrapper.write(BedrockTypes.VAR_INT, blockAction.direction()); // facing
                        }
                    }
                }
            }
            if (authInputContext.hasPredictedVehicle()) {
                wrapper.write(BedrockTypes.FLOAT_LE, authInputContext.vehiclePitch()); // vehicle pitch
                wrapper.write(BedrockTypes.FLOAT_LE, authInputContext.vehicleYaw()); // vehicle yaw
                wrapper.write(BedrockTypes.VAR_LONG, authInputContext.predictedVehicleUniqueId()); // predicted vehicle entity unique id
            }
            final Position2f analogMoveVector = immobile
                    ? new Position2f(0F, 0F)
                    : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), clientPlayer.isSneaking());
            final Position2f rawMoveVector = immobile
                    ? new Position2f(0F, 0F)
                    : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), false);
            wrapper.write(BedrockTypes.POSITION_2F, analogMoveVector); // analog move vector
            wrapper.write(BedrockTypes.POSITION_3F, MathUtil.calculateCameraOrientation(clientPlayer.rotation().y(), clientPlayer.rotation().x())); // camera orientation
            wrapper.write(BedrockTypes.POSITION_2F, rawMoveVector); // raw move vector

            clientPlayer.authInputData().clear();
            clientPlayer.authInputBlockActions().clear();
            clientPlayer.clearAuthInputItemInteraction();
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_ABILITIES, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final byte flags = wrapper.read(Types.BYTE); // flags
            final boolean flying = (flags & AbilitiesFlag.FLYING.getBit()) != 0;
            if (flying != clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Flying)) {
                clientPlayer.abilities().getOrCreateCacheLayer().setAbility(AbilitiesIndex.Flying, flying);
                clientPlayer.addAuthInputData(flying ? PlayerAuthInputPacket_InputData.StartFlying : PlayerAuthInputPacket_InputData.StopFlying);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.CHANGE_GAME_MODE, ServerboundBedrockPackets.SET_PLAYER_GAME_TYPE, new PacketHandlers() {
            @Override
            protected void register() {
                handler(wrapper -> {
                    if (!wrapper.user().get(SpectatorCameraTracker.class).acceptsJavaGameModeChange()) {
                        wrapper.cancel();
                        return;
                    }
                    final GameMode gameMode = GameMode.values()[wrapper.read(Types.VAR_INT)]; // game mode
                    final GameType gameType = switch (gameMode) {
                        case SURVIVAL -> GameType.Survival;
                        case CREATIVE -> GameType.Creative;
                        case ADVENTURE -> GameType.Adventure;
                        case SPECTATOR -> GameType.Spectator;
                        default -> throw new IllegalStateException("Unhandled GameMode: " + gameMode);
                    };
                    wrapper.write(BedrockTypes.VAR_INT, gameType.getValue()); // game type
                    wrapper.user().get(EntityTracker.class).getClientPlayer().setGameType(gameType);
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SWING, ServerboundBedrockPackets.ANIMATE, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand
            if (hand != InteractionHand.MAIN_HAND || clientPlayer.checkCancelSwingPacket()) {
                wrapper.cancel();
                return;
            }

            EntityPacketLayout.writeAnimateAction(wrapper, AnimatePacketPayload_Action.Swing.getValue()); // action
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
            wrapper.write(BedrockTypes.FLOAT_LE, 0F); // data
            EntityPacketLayout.writeAnimateTrailer(wrapper, ActorSwingSource.Attack.name().toLowerCase(Locale.ROOT)); // swing source (897+)

            if (clientPlayer.blockBreakingInfo() != null) {
                if (!gameSession.isBlockBreakingServerAuthoritative()) {
                    final ClientPlayerEntity.BlockBreakingInfo blockBreakingInfo = clientPlayer.blockBreakingInfo();
                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, blockBreakingInfo.position(), blockBreakingInfo.direction().ordinal()));
                }
            } else {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.MissedSwing);
            }
        });
    }

    static void removeImmobileMovementInput(final Set<PlayerAuthInputPacket_InputData> inputData) {
        inputData.removeAll(IMMOBILE_MOVEMENT_INPUTS);
    }

    static float neteaseAuthInputGravity(final UserConnection user) {
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        return neteaseAuthInputGravity(gameSession != null ? gameSession.getNeteaseLevelGravity() : null);
    }

    static float neteaseAuthInputGravity(final Float gravity) {
        if (gravity == null) {
            return ProtocolConstants.PLAYER_GRAVITY;
        }
        // MOT writes SET_LEVEL_GRAVITY as a signed acceleration (join reset = -0.08).
        // PLAYER_AUTH_INPUT already subtracts a positive gravity constant, so take abs.
        return Math.abs(gravity);
    }

    static PlayerAuthInputPacket_InputData playerCommandInputData(final PlayerCommandAction action) {
        return switch (action) {
            case START_SPRINTING -> PlayerAuthInputPacket_InputData.StartSprinting;
            case STOP_SPRINTING -> PlayerAuthInputPacket_InputData.StopSprinting;
            case START_FALL_FLYING -> PlayerAuthInputPacket_InputData.StartGliding;
            default -> throw new IllegalStateException("Unhandled PlayerCommandAction: " + action);
        };
    }

    /**
     * Java ATTACK is entity-id only. MOT combat does not consume clickPos; emit the current look
     * direction so the field is a unit vector instead of ZERO.
     */
    static Position3f attackClickPosition(final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null || clientPlayer.rotation() == null) {
            return Position3f.ZERO;
        }
        return MathUtil.calculateCameraOrientation(clientPlayer.rotation().y(), clientPlayer.rotation().x());
    }
}
