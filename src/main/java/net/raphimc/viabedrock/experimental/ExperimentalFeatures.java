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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.longs.LongArrayList;
import com.viaversion.viaversion.libs.fastutil.longs.LongList;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.experimental.model.map.MapDecoration;
import net.raphimc.viabedrock.experimental.model.map.MapObject;
import net.raphimc.viabedrock.experimental.model.map.MapTrackedObject;
import net.raphimc.viabedrock.experimental.block.CustomBlockMappingModule;
import net.raphimc.viabedrock.experimental.camera.CameraModule;
import net.raphimc.viabedrock.experimental.inventory.CraftingDataModule;
import net.raphimc.viabedrock.experimental.inventory.ClientAuthInventoryModule;
import net.raphimc.viabedrock.experimental.pyrpc.PyRpcDispatcherModule;
import net.raphimc.viabedrock.experimental.dimension.AlternateDimensionModule;
import net.raphimc.viabedrock.experimental.entity.CustomEntityTypeResolver;
import net.raphimc.viabedrock.experimental.light.AsyncLightModule;
import net.raphimc.viabedrock.experimental.npc.NpcDialogueModule;
import net.raphimc.viabedrock.experimental.modinterface.ModUIClientModule;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.experimental.storage.BlockPlacementAckTracker;
import net.raphimc.viabedrock.experimental.storage.MapTracker;
import net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker;
import net.raphimc.viabedrock.experimental.storage.ScriptDebugTextTracker;
import net.raphimc.viabedrock.experimental.task.ScriptDebugTextTickTask;
import net.raphimc.viabedrock.experimental.util.JavaMapPaletteUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ItemUseInventoryTransaction_TriggerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerActionAction;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * This class is used to register experimental features that are not yet stable/tested enough to be included in the main protocol.
 * These features may be subject to change or removal in future versions.
 */
public class ExperimentalFeatures {

    private static final int FOOD_USE_TICKS = 32;
    private static final int DRINK_USE_TICKS = 32;
    private static final int MILK_BUCKET_USE_TICKS = 32;
    private static final int CROSSBOW_CHARGE_TICKS = 23;
    private static final int CROSSBOW_AUTO_FINISH_TICKS = 40;
    private static final long FINISH_USE_RELEASE_DELAY_MS = 50L;
    private static final int USE_ITEM_LEGACY_REQUEST_ID = 0;
    private static final BlockPosition AIR_USE_BLOCK_POSITION = new BlockPosition(0, 0, 0);
    private static final int AIR_USE_BLOCK_FACE = 255;
    private static final int AIR_USE_BLOCK_RUNTIME_ID = 0;
    private static final byte DEFAULT_COOLDOWN_STATE = 0;
    private static final String FOOD_ITEM_TAG = "minecraft:is_food";
    private static final Set<String> CONSUME_ON_RELEASE_ITEMS = Set.of(
            "minecraft:potion",
            "minecraft:milk_bucket"
    );
    private static final Set<String> RELEASE_ON_RELEASE_ITEMS = Set.of(
            "minecraft:bow",
            "minecraft:crossbow",
            "minecraft:trident",
            "minecraft:brush",
            "minecraft:spyglass"
    );

    private static final List<FeatureModule> MODULES = new ArrayList<>();

    private record ReleaseItemSnapshot(byte hotbarSlot, BedrockItem itemInHand, Position3f headPosition) {
    }

    public static List<FeatureModule> getModules() {
        return Collections.unmodifiableList(MODULES);
    }

    public static void registerModule(final FeatureModule module) {
        MODULES.add(module);
    }

    // --- Module dispatch methods ---

    public static void dispatchMappingsLoad(final BedrockMappingData data, final MappingLoadPhase phase) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onMappingsLoad(data, phase);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onMappingsLoad (" + phase + ")", e);
            }
        }
    }

    public static void dispatchEntityAdded(final UserConnection user, final Entity entity) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityAdded(user, entity);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityAdded", e);
            }
        }
    }

    public static void dispatchEntityRemoved(final UserConnection user, final Entity entity) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityRemoved(user, entity);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityRemoved", e);
            }
        }
    }

    public static void dispatchChannelRegistered(final UserConnection user, final Set<String> channels) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onChannelRegistered(user, channels);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onChannelRegistered", e);
            }
        }
    }

    public static String dispatchResolveDimensionKey(final Dimension dimension, final ChunkTracker oldChunkTracker) {
        for (final FeatureModule module : MODULES) {
            try {
                final String result = module.resolveDimensionKey(dimension, oldChunkTracker);
                if (result != null) {
                    return result;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module resolveDimensionKey", e);
            }
        }
        return null;
    }

    public static Entity dispatchResolveEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type) {
        for (final FeatureModule module : MODULES) {
            try {
                final Entity result = module.resolveEntity(user, uniqueId, runtimeId, type);
                if (result != null) {
                    return result;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module resolveEntity", e);
            }
        }
        return null;
    }

    public static void dispatchResourcePackStackSet(final UserConnection user) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onResourcePackStackSet(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onResourcePackStackSet", e);
            }
        }
    }

    public static void dispatchChunkTrackerCreated(final ChunkTracker tracker) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onChunkTrackerCreated(tracker);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onChunkTrackerCreated", e);
            }
        }
    }

    public static boolean dispatchCustomPayload(final String channel, final PacketWrapper wrapper) {
        for (final FeatureModule module : MODULES) {
            try {
                if (module.handleCustomPayload(channel, wrapper)) {
                    return true;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module handleCustomPayload", e);
            }
        }
        return false;
    }

    public static synchronized void registerModules() {
        if (!MODULES.isEmpty()) {
            return;
        }

        registerModule(new PyRpcDispatcherModule());  // Must be first (owns PY_RPC handler)
        registerModule(new ModUIClientModule());      // PY_RPC consumer
        registerModule(new CameraModule());
        registerModule(new AlternateDimensionModule());
        registerModule(new CustomEntityTypeResolver());
        registerModule(new CustomBlockMappingModule());
        registerModule(new ResourcePackModule());
        registerModule(new NpcDialogueModule());
        registerModule(new AsyncLightModule());
        registerModule(new CraftingDataModule());
        registerModule(new ClientAuthInventoryModule());
    }

    private static ItemReleaseInventoryTransaction_ActionType releaseActionForItem(final ItemRewriter itemRewriter, final BedrockItem item, final int usingTicks) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        if (identifier == null) {
            return ItemReleaseInventoryTransaction_ActionType.Release;
        }
        if (RELEASE_ON_RELEASE_ITEMS.contains(identifier)) {
            return ItemReleaseInventoryTransaction_ActionType.Release;
        }
        final Set<String> itemTags = BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier);
        if (CONSUME_ON_RELEASE_ITEMS.contains(identifier) || (itemTags != null && itemTags.contains(FOOD_ITEM_TAG))) {
            return usingTicks >= consumableUseTicks(identifier) ? ItemReleaseInventoryTransaction_ActionType.Use : ItemReleaseInventoryTransaction_ActionType.Release;
        }
        return ItemReleaseInventoryTransaction_ActionType.Release;
    }

    private static int consumableUseTicks(final String identifier) {
        return switch (identifier) {
            case "minecraft:potion" -> DRINK_USE_TICKS;
            case "minecraft:milk_bucket" -> MILK_BUCKET_USE_TICKS;
            default -> FOOD_USE_TICKS;
        };
    }

    private static boolean isContinuousUseItem(final ItemRewriter itemRewriter, final BedrockItem item) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        if (identifier == null) {
            return false;
        }
        if ("minecraft:crossbow".equals(identifier) && isChargedCrossbow(item)) {
            return false;
        }
        if (RELEASE_ON_RELEASE_ITEMS.contains(identifier) || CONSUME_ON_RELEASE_ITEMS.contains(identifier)) {
            return true;
        }
        final Set<String> itemTags = BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier);
        return itemTags != null && itemTags.contains(FOOD_ITEM_TAG);
    }

    private static boolean isBow(final ItemRewriter itemRewriter, final BedrockItem item) {
        return "minecraft:bow".equals(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isCrossbow(final ItemRewriter itemRewriter, final BedrockItem item) {
        return "minecraft:crossbow".equals(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isChargedCrossbow(final ItemRewriter itemRewriter, final BedrockItem item) {
        return isCrossbow(itemRewriter, item) && isChargedCrossbow(item);
    }

    private static boolean isChargedCrossbow(final BedrockItem item) {
        return item.tag() != null && (item.tag().get("chargedItem") != null || item.tag().get("ChargedProjectiles") != null);
    }

    private static boolean shouldSendStandaloneUseTransaction(final ItemRewriter itemRewriter, final BedrockItem item) {
        return isBow(itemRewriter, item) || isCrossbow(itemRewriter, item);
    }

    private static BedrockInventoryTransaction createUseItemTransaction(final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer) {
        return new BedrockInventoryTransaction(
                USE_ITEM_LEGACY_REQUEST_ID,
                null,
                null,
                ComplexInventoryTransaction_Type.ItemUseTransaction,
                new InventoryTransactionData.UseItemTransactionData(
                        ItemUseInventoryTransaction_ActionType.Use,
                        ItemUseInventoryTransaction_TriggerType.Unknown,
                        AIR_USE_BLOCK_POSITION,
                        AIR_USE_BLOCK_FACE,
                        inventoryContainer.getSelectedHotbarSlot(),
                        inventoryContainer.getSelectedHotbarItem(),
                        clientPlayer.position(),
                        Position3f.ZERO,
                        AIR_USE_BLOCK_RUNTIME_ID,
                        ItemUseInventoryTransaction_PredictedResult.Success,
                        DEFAULT_COOLDOWN_STATE
                )
        );
    }

    private static void sendReleaseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer, final ItemReleaseInventoryTransaction_ActionType actionType) {
        sendReleaseItemTransaction(user, inventoryTransactionRewriter, createReleaseItemSnapshot(inventoryContainer, clientPlayer), actionType);
    }

    private static ReleaseItemSnapshot createReleaseItemSnapshot(final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer) {
        return new ReleaseItemSnapshot(
                inventoryContainer.getSelectedHotbarSlot(),
                inventoryContainer.getSelectedHotbarItem().copy(),
                clientPlayer.position().add(0F, clientPlayer.eyeOffset(), 0F)
        );
    }

    private static void sendReleaseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ReleaseItemSnapshot snapshot, final ItemReleaseInventoryTransaction_ActionType actionType) {
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        final BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                0, // legacy request id
                null,
                null,
                ComplexInventoryTransaction_Type.ItemReleaseTransaction,
                new InventoryTransactionData.ReleaseItemTransactionData(
                        actionType,
                        snapshot.hotbarSlot(),
                        snapshot.itemInHand(),
                        snapshot.headPosition()
                )
        );
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);
        transactionPacket.sendToServer(BedrockProtocol.class);
    }

    private static void sendUseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer) {
        sendUseItemTransaction(user, inventoryTransactionRewriter, inventoryContainer, clientPlayer, true);
    }

    private static void sendUseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer, final boolean sendEquipment) {
        if (sendEquipment) {
            sendSelectedHotbarSlot(user, inventoryContainer, clientPlayer);
        }
        if (isBow(user.get(ItemRewriter.class), inventoryContainer.getSelectedHotbarItem())) {
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    user,
                    clientPlayer.runtimeId(),
                    PlayerActionType.StartItemUseOn,
                    AIR_USE_BLOCK_POSITION,
                    AIR_USE_BLOCK_POSITION,
                    AIR_USE_BLOCK_FACE
            );
        }
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), createUseItemTransaction(inventoryContainer, clientPlayer));
        transactionPacket.sendToServer(BedrockProtocol.class);
    }

    private static void finishCrossbowCharge(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer) {
        sendUseItemTransaction(user, inventoryTransactionRewriter, inventoryContainer, clientPlayer, false);
        final ReleaseItemSnapshot releaseSnapshot = createReleaseItemSnapshot(inventoryContainer, clientPlayer);
        user.getChannel().eventLoop().schedule(() -> sendReleaseItemTransaction(user, inventoryTransactionRewriter, releaseSnapshot, ItemReleaseInventoryTransaction_ActionType.Release), FINISH_USE_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void finishConsumableUse(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer) {
        sendUseItemTransaction(user, inventoryTransactionRewriter, inventoryContainer, clientPlayer, false);
        final ReleaseItemSnapshot releaseSnapshot = createReleaseItemSnapshot(inventoryContainer, clientPlayer);
        user.getChannel().eventLoop().schedule(() -> sendReleaseItemTransaction(user, inventoryTransactionRewriter, releaseSnapshot, ItemReleaseInventoryTransaction_ActionType.Release), FINISH_USE_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void sendSelectedHotbarSlot(final UserConnection user, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer) {
        final BedrockItem selectedItem = inventoryContainer.getSelectedHotbarItem();
        sendMobEquipment(user, inventoryContainer, clientPlayer, inventoryContainer.getSelectedHotbarSlot(), selectedItem);
    }

    private static void sendMobEquipment(final UserConnection user, final InventoryContainer inventoryContainer, final ClientPlayerEntity clientPlayer, final byte slot, final BedrockItem item) {
        final PacketWrapper mobEquipmentPacket = PacketWrapper.create(ServerboundBedrockPackets.MOB_EQUIPMENT, user);
        mobEquipmentPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
        mobEquipmentPacket.write(user.get(ItemRewriter.class).newItemType(), item); // item
        mobEquipmentPacket.write(Types.BYTE, slot); // slot
        mobEquipmentPacket.write(Types.BYTE, slot); // selected slot
        mobEquipmentPacket.write(Types.BYTE, inventoryContainer.containerId()); // container id
        mobEquipmentPacket.sendToServer(BedrockProtocol.class);
    }

    // --- Existing experimental features ---

    private static final int MAP_FLAGS_ALL = ClientboundMapItemDataPacket_Type.Creation.getValue() | ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue() | ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();

    public static void registerPacketTranslators(final BedrockProtocol protocol) {
        // Dispatch to feature modules before the built-in experimental hooks are installed.
        for (final FeatureModule module : MODULES) {
            try {
                module.onPacketRegistration(protocol);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onPacketRegistration", e);
            }
        }

        MultilineNametagTracker.registerHandlers(protocol);
        ScriptDebugTextTracker.registerHandlers(protocol);

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PLAYER_ACTION, wrapper -> {
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            final PlayerActionAction action = PlayerActionAction.values()[wrapper.passthrough(Types.VAR_INT)]; // action
            wrapper.passthrough(Types.BLOCK_POSITION1_14); // block position
            wrapper.passthrough(Types.UNSIGNED_BYTE); // face
            final int sequence = wrapper.passthrough(Types.VAR_INT); // sequence number

            if (action == PlayerActionAction.RELEASE_USE_ITEM) {
                final InventoryContainer inventoryContainer = inventoryTracker.getInventoryContainer();
                final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
                final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();

                wrapper.clearPacket();
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }

                if (!clientPlayer.isUsingItem()) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryContainer);
                    return;
                }

                final BedrockItem selectedItem = inventoryContainer.getSelectedHotbarItem();
                final ItemReleaseInventoryTransaction_ActionType releaseAction = releaseActionForItem(
                        wrapper.user().get(ItemRewriter.class),
                        selectedItem,
                        clientPlayer.usingItemTicks()
                );
                if (isCrossbow(wrapper.user().get(ItemRewriter.class), selectedItem) && clientPlayer.usingItemTicks() >= CROSSBOW_CHARGE_TICKS) {
                    finishCrossbowCharge(wrapper.user(), inventoryTransactionRewriter, inventoryContainer, clientPlayer);
                    clientPlayer.setUsingItem(false);
                    return;
                }
                if (releaseAction == ItemReleaseInventoryTransaction_ActionType.Use) {
                    finishConsumableUse(wrapper.user(), inventoryTransactionRewriter, inventoryContainer, clientPlayer);
                    clientPlayer.setUsingItem(false);
                    return;
                }
                clientPlayer.setUsingItem(false);

                sendReleaseItemTransaction(wrapper.user(), inventoryTransactionRewriter, inventoryContainer, clientPlayer, releaseAction);
            } else if (action == PlayerActionAction.DROP_ITEM || action == PlayerActionAction.DROP_ALL_ITEMS) {
                final BedrockItem currentItem = inventoryTracker.getInventoryContainer().getSelectedHotbarItem();

                wrapper.cancel();
                if (currentItem.isEmpty()) {
                    return;
                }

                BedrockItem predictedAmount = currentItem.copy();
                if (action == PlayerActionAction.DROP_ITEM) {
                    predictedAmount.setAmount(1); // Drop a single item
                }

                BedrockItem predictedToItem = currentItem.copy();
                if (action == PlayerActionAction.DROP_ITEM) {
                    if (predictedToItem.amount() > 1) {
                        predictedToItem.setAmount(currentItem.amount() - 1);
                    } else {
                        predictedToItem = BedrockItem.empty();
                    }
                } else {
                    predictedToItem = BedrockItem.empty();
                }

                final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper.user());

                BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                        0,
                        null,
                        List.of(
                                new InventoryActionData(
                                        new InventorySource(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                                        0,
                                        BedrockItem.empty(),
                                        predictedAmount
                                ),
                                new InventoryActionData(
                                        new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                                        inventoryTracker.getInventoryContainer().getSelectedHotbarSlot(),
                                        currentItem,
                                        predictedToItem
                                )
                        ),

                        ComplexInventoryTransaction_Type.NormalTransaction,
                        new InventoryTransactionData.NormalTransactionData()
                );

                transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);

                transactionPacket.sendToServer(BedrockProtocol.class);

                // Update mirror optimistically so rapid consecutive drops read the correct count
                inventoryTracker.getInventoryContainer().setItemSilent(
                        inventoryTracker.getInventoryContainer().getSelectedHotbarSlot(),
                        predictedToItem
                );

                //TODO: I think vanilla client also sends these and im not sure what their purposes are but it works without them
                    /*final PacketWrapper interactPacket = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());

                    interactPacket.write(Types.BYTE, (byte) InteractPacket_Action.InteractUpdate.getValue());
                    interactPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId());
                    interactPacket.write(BedrockTypes.POSITION_3F, new Position3f(0, 0, 0));

                    interactPacket.sendToServer(BedrockProtocol.class);

                    final PacketWrapper mobEquipPacket = PacketWrapper.create(ServerboundBedrockPackets.MOB_EQUIPMENT, wrapper.user());

                    mobEquipPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId());
                    mobEquipPacket.write(itemRewriter.newItemType(), predictedToItem);
                    mobEquipPacket.write(Types.BYTE, inventoryTracker.getInventoryContainer().getSelectedHotbarSlot());
                    mobEquipPacket.write(Types.BYTE, inventoryTracker.getInventoryContainer().getSelectedHotbarSlot());
                    mobEquipPacket.write(Types.BYTE, (byte) ContainerID.CONTAINER_ID_INVENTORY.getValue());

                    mobEquipPacket.sendToServer(BedrockProtocol.class);*/
            }


        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CLIENT_TICK_END, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final InventoryContainer inventoryContainer = inventoryTracker.getInventoryContainer();
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (!clientPlayer.isUsingItem()) {
                return;
            }

            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final BedrockItem selectedItem = inventoryContainer.getSelectedHotbarItem();
            if (!isContinuousUseItem(itemRewriter, selectedItem)) {
                clientPlayer.setUsingItem(false);
                return;
            }

            final ItemReleaseInventoryTransaction_ActionType actionType = releaseActionForItem(itemRewriter, selectedItem, clientPlayer.usingItemTicks());
            if (isCrossbow(itemRewriter, selectedItem) && clientPlayer.usingItemTicks() >= CROSSBOW_AUTO_FINISH_TICKS) {
                if (clientPlayer.isCrossbowChargeFinishSent()) {
                    return;
                }
                finishCrossbowCharge(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), inventoryContainer, clientPlayer);
                clientPlayer.setCrossbowChargeFinishSent(true);
                return;
            }
            if (actionType == ItemReleaseInventoryTransaction_ActionType.Use) {
                finishConsumableUse(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), inventoryContainer, clientPlayer);
                clientPlayer.setUsingItem(false);
            }
        });

        protocol.registerServerbound(ServerboundPackets26_1.USE_ITEM, ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);

            final int hand = wrapper.read(Types.VAR_INT); // hand
            final int sequence = wrapper.read(Types.VAR_INT); // sequence
            wrapper.read(Types.FLOAT); // yaw
            wrapper.read(Types.FLOAT); // pitch

            // Bedrock can't hold the majority of item in offhand and can't use any either.
            // TODO: We need to handle cases where the item changes, or it affect player movement (eg: eating/blocking/etc)
            if (hand != InteractionHand.MAIN_HAND.ordinal()) {
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            // Mark as using item and send StartUsingItem flag in the current tick's PlayerAuthInput
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final BedrockItem selectedItem = inventoryContainer.getSelectedHotbarItem();
            if (isContinuousUseItem(itemRewriter, selectedItem)) {
                clientPlayer.setUsingItem(true);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartUsingItem);
                clientPlayer.setAuthInputItemInteraction(createUseItemTransaction(inventoryContainer, clientPlayer));
                if (shouldSendStandaloneUseTransaction(itemRewriter, selectedItem)) {
                    sendUseItemTransaction(wrapper.user(), inventoryTransactionRewriter, inventoryContainer, clientPlayer);
                }
                wrapper.clearPacket();
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            if (isChargedCrossbow(itemRewriter, selectedItem)) {
                sendSelectedHotbarSlot(wrapper.user(), inventoryContainer, clientPlayer);
            }
            wrapper.write(inventoryTransactionRewriter.getInventoryTransactionType(), createUseItemTransaction(inventoryContainer, clientPlayer));
            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
            }
        });

        // Handle COMPLETED_USING_ITEM from server (sent when item use completes, e.g. eating)
        protocol.registerClientbound(ClientboundBedrockPackets.COMPLETED_USING_ITEM, null, wrapper -> {
            wrapper.cancel();
            wrapper.user().get(EntityTracker.class).getClientPlayer().setUsingItem(false);
        });

        protocol.registerServerbound(ServerboundPackets26_1.USE_ITEM_ON, null, wrapper -> {
            wrapper.cancel();

            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);

            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand

            BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // block position
            int faceInt = wrapper.read(Types.UNSIGNED_BYTE); // face
            Direction direction = Direction.getFromVerticalId(faceInt);
            if (direction == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown block face id: " + faceInt);
                return;
            }
            BlockFace face = direction.blockFace();
            Position3f clickPosition = new Position3f(
                    wrapper.read(Types.FLOAT), // x
                    wrapper.read(Types.FLOAT), // y
                    wrapper.read(Types.FLOAT)  // z
            );
            boolean insideBlock = wrapper.read(Types.BOOLEAN); // inside block
            wrapper.read(Types.BOOLEAN); // world border, this doesn't exist on Bedrock.

            // Defer block changed ack until the server confirms via UPDATE_BLOCK.
            // Sending ack immediately would clear the Java client's prediction before any BLOCK_UPDATE arrives,
            // causing the placed block to flicker (disappear then reappear).
            final int sequence = wrapper.read(Types.VAR_INT);

            // The player can only interact using the main hand on Bedrock!
            if (hand != InteractionHand.MAIN_HAND) {
                return;
            }

            final InventoryContainer inventoryContainer = inventoryTracker.getInventoryContainer();
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final BedrockItem selectedItem = inventoryContainer.getSelectedHotbarItem();
            if (isContinuousUseItem(itemRewriter, selectedItem)) {
                clientPlayer.setUsingItem(true);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartUsingItem);
                clientPlayer.setAuthInputItemInteraction(createUseItemTransaction(inventoryContainer, clientPlayer));
                if (shouldSendStandaloneUseTransaction(itemRewriter, selectedItem)) {
                    sendUseItemTransaction(wrapper.user(), inventoryTransactionRewriter, inventoryContainer, clientPlayer);
                }

                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            final BlockPosition expectedPos = insideBlock ? position : position.getRelative(face);
            wrapper.user().get(BlockPlacementAckTracker.class).addPendingAck(expectedPos, sequence);

            // The bedrock client will send a start item use on action to the server first.
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    wrapper.user(),
                    clientPlayer.runtimeId(),
                    PlayerActionType.StartItemUseOn,
                    position,
                    insideBlock ? position : position.getRelative(face),
                    faceInt
            );

            // This is the main packet that the bedrock client use to interact with block.The rest of the
            final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper.user());

            BedrockItem predictedToItem = inventoryContainer.getSelectedHotbarItem().copy();
            // This is not entirely correct, but at least it's more accurate than not sending actions or sending the original item data.
            if (predictedToItem.blockRuntimeId() != 0 && clientPlayer.javaGameMode() != GameMode.CREATIVE) {
                predictedToItem.setAmount(predictedToItem.amount() - 1);
            }
            if (predictedToItem.amount() <= 0) {
                predictedToItem = BedrockItem.empty();
            }

            BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                    0, // legacy request id
                    null,
                    List.of(
                            new InventoryActionData(
                                    new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                                    inventoryContainer.getSelectedHotbarSlot(),
                                    inventoryContainer.getSelectedHotbarItem(),
                                    predictedToItem
                            )
                    ),
                    ComplexInventoryTransaction_Type.ItemUseTransaction,
                    new InventoryTransactionData.UseItemTransactionData(
                            ItemUseInventoryTransaction_ActionType.Place,
                            ItemUseInventoryTransaction_TriggerType.PlayerInput,
                            position,
                            faceInt,
                            inventoryContainer.getSelectedHotbarSlot(),
                            inventoryContainer.getSelectedHotbarItem(),
                            clientPlayer.position(),
                            clickPosition,
                            chunkTracker.getBlockState(position),
                            ItemUseInventoryTransaction_PredictedResult.Success,
                            (byte) 0 // TODO: client cooldown state
                    )
            );
            transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);

            transactionPacket.sendToServer(BedrockProtocol.class);

            // Bedrock sends a stop item use on after the transaction packet
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    wrapper.user(),
                    clientPlayer.runtimeId(),
                    PlayerActionType.StopItemUseOn,
                    position,
                    new BlockPosition(0, 0, 0),
                    0
            );
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_TRANSACTION, null, wrapper -> {
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);
            InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            wrapper.cancel();
            BedrockInventoryTransaction inventoryTransaction = wrapper.read(inventoryTransactionRewriter.getInventoryTransactionType());

            if (inventoryTransaction.legacyRequestId() != 0) {
                // Ignore legacy inventory transactions for now
                return;
            }

            if (inventoryTransaction.actions() != null && !inventoryTransaction.actions().isEmpty()) {
                for (InventoryActionData action : inventoryTransaction.actions()) {
                    if (action.source().type() == InventorySourceType.ContainerInventory) {
                        Container container = inventoryTracker.getContainerClientbound((byte) action.source().containerId(), null, null);

                        if (container != null) {
                            container.setItem(action.slot(), action.toItem());
                            PacketFactory.sendJavaContainerSetContent(wrapper.user(),  container);
                        } else {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received inventory action for unknown container ID: " + action.source().containerId());
                        }
                    }
                }
            }

            switch (inventoryTransaction.transactionType()) {
                case NormalTransaction -> {
                    break; // Nothing to do here for now
                }
                default -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received unsupported inventory transaction type: " + inventoryTransaction.transactionType());
                }
            }
        });

        protocol.registerClientbound(ClientboundBedrockPackets.MAP_ITEM_DATA, ClientboundPackets26_1.MAP_ITEM_DATA, wrapper -> {
            MapTracker mapTracker = wrapper.user().get(MapTracker.class);

            final long mapId = wrapper.read(BedrockTypes.VAR_LONG); // map id
            final int typeFlags = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // type flags
            final byte dimension = wrapper.read(Types.BYTE); // dimension
            final boolean locked = wrapper.read(Types.BOOLEAN); // locked
            final BlockPosition origin = wrapper.read(BedrockTypes.BLOCK_POSITION); // origin

            final LongList trackedEntities = new LongArrayList();
            if ((typeFlags & ClientboundMapItemDataPacket_Type.Creation.getValue()) != 0) {
                final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                for (int i = 0; i < length; i++) {
                    trackedEntities.add(wrapper.read(BedrockTypes.VAR_LONG).longValue());
                }
            }

            byte scale = 0;
            if ((typeFlags & MAP_FLAGS_ALL) != 0) {
                scale = wrapper.read(Types.BYTE); // scale
            }

            final List<MapDecoration> decorations = new ArrayList<>();
            final List<MapTrackedObject> trackedObjects = new ArrayList<>();
            if ((typeFlags & ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue()) != 0) {
                final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                for (int i = 0; i < length; i++) {
                    MapTrackedObject.Type objectType = MapTrackedObject.Type.values()[wrapper.read(BedrockTypes.INT_LE)]; //TODO: Error logging
                    switch (objectType) {
                        case BLOCK:
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.BLOCK_POSITION)));
                            break;
                        case ENTITY:
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.VAR_LONG)));
                            break;
                    }
                }

                final int decorLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                for (int i = 0; i < decorLength; i++) {
                    final byte iconType = wrapper.read(Types.BYTE);
                    final byte rotation = wrapper.read(Types.BYTE);
                    final byte x = wrapper.read(Types.BYTE);
                    final byte y = wrapper.read(Types.BYTE);
                    final String name = wrapper.read(BedrockTypes.STRING); // name
                    final int color = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // color

                    decorations.add(new MapDecoration(iconType, rotation, x, y, name, color));
                }
            }

            int width = 0;
            int height = 0;
            int xOffset = 0;
            int yOffset = 0;
            int[] colors = new int[0];
            if ((typeFlags & ClientboundMapItemDataPacket_Type.TextureUpdate.getValue()) != 0) {
                width = wrapper.read(BedrockTypes.VAR_INT); // width
                height = wrapper.read(BedrockTypes.VAR_INT); // height
                xOffset = wrapper.read(BedrockTypes.VAR_INT); // x offset
                yOffset = wrapper.read(BedrockTypes.VAR_INT); // y offset

                final int colorsLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // colors length
                colors = new int[colorsLength];
                for (int i = 0; i < colorsLength; i++) {
                    colors[i] = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                }
            }

            //TODO: Clean this up
            int nextJavaId = mapTracker.getNextMapId();
            if ((typeFlags & ClientboundMapItemDataPacket_Type.Creation.getValue()) != 0) {
                MapObject existingMap = mapTracker.getMapObjects().get(mapId);
                if (existingMap != null) {
                    existingMap.getTrackedEntities().clear();
                    existingMap.getTrackedEntities().addAll(trackedEntities);
                } else {
                    MapObject mapObject = new MapObject(
                            mapId,
                            dimension,
                            locked,
                            origin,
                            trackedEntities,
                            scale,
                            trackedObjects,
                            decorations,
                            width,
                            height,
                            xOffset,
                            yOffset,
                            colors,
                            nextJavaId
                    );
                    mapTracker.getMapObjects().put(mapId, mapObject);
                }
            }
            if ((typeFlags & ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue()) != 0) {
                MapObject existingMap = mapTracker.getMapObjects().get(mapId);
                if (existingMap != null) {
                    existingMap.getTrackedObjects().clear();
                    existingMap.getTrackedObjects().addAll(trackedObjects);
                    existingMap.getDecorations().clear();
                    existingMap.getDecorations().addAll(decorations);
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received map decoration update for unknown map id: " + mapId);
                    MapObject mapObject = new MapObject(
                            mapId,
                            dimension,
                            locked,
                            origin,
                            trackedEntities,
                            scale,
                            trackedObjects,
                            decorations,
                            0,
                            0,
                            0,
                            0,
                            new int[0],
                            nextJavaId
                    );
                    mapTracker.getMapObjects().put(mapId, mapObject);
                }
            }
            if ((typeFlags & ClientboundMapItemDataPacket_Type.TextureUpdate.getValue()) != 0) {
                MapObject existingMap = mapTracker.getMapObjects().get(mapId);
                if (existingMap != null) {
                    existingMap.setWidth(width);
                    existingMap.setHeight(height);
                    existingMap.setXOffset(xOffset);
                    existingMap.setYOffset(yOffset);
                    existingMap.setColors(colors);
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received map texture update for unknown map id: " + mapId);
                    MapObject mapObject = new MapObject(
                            mapId,
                            dimension,
                            locked,
                            origin,
                            trackedEntities,
                            scale,
                            new ArrayList<>(),
                            new ArrayList<>(),
                            width,
                            height,
                            xOffset,
                            yOffset,
                            colors,
                            nextJavaId
                    );
                    mapTracker.getMapObjects().put(mapId, mapObject);
                }
            }

            MapObject mapObject = mapTracker.getMapObjects().get(mapId);
            if (mapObject == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received map item data for unknown map id: " + mapId);
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, mapObject.getJavaId()); // map id
            wrapper.write(Types.BYTE, mapObject.getScale()); // scale
            wrapper.write(Types.BOOLEAN, mapObject.isLocked()); // locked

            wrapper.write(Types.BOOLEAN, false); // Icons (Prefixed Optional, TODO: Implement)
            wrapper.write(Types.UNSIGNED_BYTE, (short) mapObject.getWidth()); // width
            if (mapObject.getWidth() > 0) {
                wrapper.write(Types.UNSIGNED_BYTE, (short) mapObject.getHeight()); // height
                wrapper.write(Types.BYTE, (byte) mapObject.getXOffset()); // xOffset
                wrapper.write(Types.BYTE, (byte) mapObject.getYOffset()); // yOffset

                wrapper.write(Types.VAR_INT, mapObject.getColors().length);
                for (short color : JavaMapPaletteUtil.convertToJavaPalette(mapObject.getColors())) {
                    wrapper.write(Types.UNSIGNED_BYTE, color);
                }

            } else {
                //ViaBedrock.getPlatform().getLogger().warning("Sent empty map data for map id: " + mapId);
                //TODO: Bedrock requests map data if it doesnt have it, so we need to send something
            }
        });
    }

    public static void registerTasks() {
        Via.getPlatform().runRepeatingSync(new ScriptDebugTextTickTask(), 1L);
    }

    public static void registerStorages(final UserConnection user) {
        user.put(new InventoryTransactionRewriter(user));
        user.put(new MapTracker(user));
        user.put(new MultilineNametagTracker(user));
        user.put(new ScriptDebugTextTracker(user));
        user.put(new BlockPlacementAckTracker(user));

        // Dispatch to feature modules
        for (final FeatureModule module : MODULES) {
            try {
                module.onStorageRegistration(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onStorageRegistration", e);
            }
        }
    }
}
