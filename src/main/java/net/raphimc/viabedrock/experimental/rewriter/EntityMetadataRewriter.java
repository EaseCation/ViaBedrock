/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2025 RK_01/RaphiMC and contributors
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
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.EulerAngle;
import com.viaversion.viaversion.api.minecraft.VillagerData;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.CustomEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.LivingEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;

import com.viaversion.nbt.tag.Tag;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class EntityMetadataRewriter {

    // Called in Entity#translateEntityData if experimental features are enabled
    public static boolean rewrite(final UserConnection user, final Entity entity, final ActorDataIDs id, final EntityData entityData, final List<EntityData> javaEntityData) {
        EntityTracker entityTracker = user.get(EntityTracker.class);

        switch (id) {
            case RESERVED_0, RESERVED_092 -> { // Entity flags mask
                Set<ActorFlags> bedrockFlags = entity.entityFlags();
                byte javaBitMask = 0; // https://minecraft.wiki/w/Java_Edition_protocol/Entity_metadata#Entity
                javaBitMask = sharedFlags(entity, false);
                final EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
                if (entity instanceof LivingEntity && scaleData != null && readNumber(scaleData).floatValue() == 0F) {
                    javaBitMask |= (1 << 5);
                }

                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SHARED_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, javaBitMask));

                // Bedrock only exposes sneaking as an actor flag, but the Java client derives a player's
                // *visual* crouch from the POSE entity data (Pose.CROUCHING), not the sharedflags sneaking
                // bit (that bit only affects eye height / nameplate). Without setting POSE, remote Bedrock
                // players never visually crouch on the Java side. Toggle the pose for players accordingly.
                if (entity.javaType().is(EntityTypes1_21_11.PLAYER)) {
                    final int javaPose = bedrockFlags.contains(ActorFlags.SNEAKING) ? 5 : 0; // Pose.CROUCHING : Pose.STANDING
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.POSE), VersionedTypes.V26_1.entityDataTypes().poseType, javaPose));
                }

                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SILENT), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.SILENT)));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.NO_GRAVITY), VersionedTypes.V26_1.entityDataTypes().booleanType, !bedrockFlags.contains(ActorFlags.HAS_GRAVITY)));

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.MOB)) {
                    byte mobBitMask = 0;
                    if (bedrockFlags.contains(ActorFlags.NOAI)) {
                        mobBitMask |= 0x01;
                    }

                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.MOB_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, mobBitMask));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ALLAY)) {
                    boolean dancing = bedrockFlags.contains(ActorFlags.DANCING);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.DANCING), VersionedTypes.V26_1.entityDataTypes().booleanType, dancing));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_AGEABLE)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));
                }

                if (entity.javaType().is(EntityTypes1_21_11.AXOLOTL)) {
                    boolean playingDead = bedrockFlags.contains(ActorFlags.PLAYING_DEAD);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PLAYING_DEAD), VersionedTypes.V26_1.entityDataTypes().booleanType, playingDead));
                }

                if (entity.javaType().is(EntityTypes1_21_11.BEE)) {
                    byte beeBitMask = 0;
                    if (bedrockFlags.contains(ActorFlags.ANGRY)) {
                        beeBitMask |= 0x02;
                    }

                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, beeBitMask));
                }

                if (entity.javaType().is(EntityTypes1_21_11.OCELOT)) {
                    boolean isTrusting = bedrockFlags.contains(ActorFlags.TRUSTING);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TRUSTING), VersionedTypes.V26_1.entityDataTypes().booleanType, isTrusting));
                }

                if (entity.javaType().is(EntityTypes1_21_11.SHEEP)) {
                    byte sheepBitMask = 0;
                    if (bedrockFlags.contains(ActorFlags.SHEARED)) {
                        sheepBitMask |= 0x10;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WOOL), VersionedTypes.V26_1.entityDataTypes().byteType, sheepBitMask));
                }

                if (entity.javaType().is(EntityTypes1_21_11.SNIFFER)) {
                    int sniffingState = 0;
                    if (bedrockFlags.contains(ActorFlags.IDLING)) {
                        sniffingState = 0;
                    } else if (false) {
                        //TODO: FEELING_HAPPY
                        sniffingState = 1;
                    } else if (false) {
                        //TODO: SCENTING
                        sniffingState = 2;
                    } else if (bedrockFlags.contains(ActorFlags.SNIFFING)) {
                        sniffingState = 3;
                    } else if (bedrockFlags.contains(ActorFlags.SEARCHING)) {
                        sniffingState = 4;
                    } else if (bedrockFlags.contains(ActorFlags.DIGGING)) {
                        sniffingState = 5;
                    } else if (false) {
                        //TODO: RISING
                        sniffingState = 6;
                    } else {
                        sniffingState = 0;
                        //TODO: Currently spams a bit but thats probably because we are missing states
                        //ViaBedrock.getPlatform().getLogger().warning("Unknown sniffer state, defaulting to IDLING.");
                    }

                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.STATE), VersionedTypes.V26_1.entityDataTypes().snifferState, sniffingState));
                }

                if (entity.javaType().is(EntityTypes1_21_11.TURTLE)) { //TODO: Test
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HAS_EGG), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.IS_PREGNANT)));
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LAYING_EGG), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.LAYING_EGG)));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_CHESTED_HORSE)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CHEST), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.CHESTED)));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.TAMABLE_ANIMAL)) {
                    byte tamableBitMask = 0;
                    if (bedrockFlags.contains(ActorFlags.SITTING)) {
                        tamableBitMask |= 0x01;
                    }
                    if (bedrockFlags.contains(ActorFlags.TAMED)) {
                        tamableBitMask |= 0x04;
                    }

                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, tamableBitMask));
                }

                if (entity.javaType().is(EntityTypes1_21_11.CAT)) { //TODO: Test
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_LYING), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.LAYING_DOWN)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.BOGGED)) {
                    boolean isSheared = bedrockFlags.contains(ActorFlags.SHEARED);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SHEARED), VersionedTypes.V26_1.entityDataTypes().booleanType, isSheared));
                }

                if (entity.javaType().is(EntityTypes1_21_11.CREEPER)) {
                    boolean charged = bedrockFlags.contains(ActorFlags.CHARGED);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_POWERED), VersionedTypes.V26_1.entityDataTypes().booleanType, charged));

                    boolean ignited = bedrockFlags.contains(ActorFlags.IGNITED);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_IGNITED), VersionedTypes.V26_1.entityDataTypes().booleanType, ignited));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ZOGLIN)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ZOMBIE)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));
                }

                if (entity.javaType().is(EntityTypes1_21_11.PIGLIN)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));

                    boolean isDancing = bedrockFlags.contains(ActorFlags.DANCING);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_DANCING), VersionedTypes.V26_1.entityDataTypes().booleanType, isDancing));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_RAIDER)) { //TODO: Test
                    boolean isCelebrating = bedrockFlags.contains(ActorFlags.CELEBRATING);
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_CELEBRATING), VersionedTypes.V26_1.entityDataTypes().booleanType, isCelebrating));
                }

            }
            case VARIANT -> {
                int variant = readNumber(entityData).intValue();

                switch (entity.javaType()) {
                    case WOLF -> {
                        int javaVariant = switch (variant) {
                            case 0 -> 4; // PALE
                            case 1 -> 7; // ASHEN
                            case 2 -> 6; // BLACK
                            case 3 -> 2; // CHESTNUT
                            case 4 -> 1; // RUSTY
                            case 5 -> 8; // SNOWY
                            case 6 -> 0; // SPOTTED
                            case 7 -> 3; // STRIPED
                            case 8 -> 5; // WOODS
                            default -> {
                                ViaBedrock.getPlatform().getLogger().warning("Unknown wolf variant " + variant + ", defaulting to PALE.");
                                yield 4;
                            }
                        };
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().wolfVariantType, javaVariant));
                    }
                    case HORSE -> {
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE_VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, variant));
                    }
                    case FROG -> {
                        int javaVariant = switch (variant) {
                            case 0 -> 1; // TEMPERATE
                            case 1 -> 2; // COLD
                            case 2 -> 0; // WARM
                            default -> {
                                ViaBedrock.getPlatform().getLogger().warning("Unknown frog variant " + variant + ", defaulting to TEMPERATE.");
                                yield 1;
                            }
                        };
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().frogVariantType, javaVariant));
                    }
                    case TROPICAL_FISH -> {
                        //TODO: Remap tropical fish variants properly
                        //javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE_VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, variant));
                    }
                    case PUFFERFISH -> {} // For some reason bedrock sends the puffed state here as well as in the PUFFED_STATE Actor ID so we ignore this one
                    case SHULKER -> {
                        byte color = (byte) variant;
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.COLOR), VersionedTypes.V26_1.entityDataTypes().byteType, color));
                    }
                    case AXOLOTL -> {
                        int javaVariant = switch (variant) {
                            case 0 -> 0; // LUCY
                            case 1 -> 3; // CYAN
                            case 2 -> 2; // GOLD
                            case 3 -> 1; // WILD
                            case 4 -> 4; // BLUE
                            default -> {
                                ViaBedrock.getPlatform().getLogger().warning("Unknown axolotl variant " + variant + ", defaulting to LUCY.");
                                yield 2;
                            }
                        };
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case MOOSHROOM -> {
                        int javaVariant = switch (variant) {
                            case 0 -> 0; // RED
                            case 1 -> 1; // BROWN
                            default -> {
                                ViaBedrock.getPlatform().getLogger().warning("Unknown mooshroom variant " + variant + ", defaulting to RED.");
                                yield 0;
                            }
                        };
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case SLIME, MAGMA_CUBE -> {
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SIZE), VersionedTypes.V26_1.entityDataTypes().varIntType, variant));
                    }
                    case RABBIT -> { // TODO: Test when I can
                        int javaVariant = variant;
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case PARROT -> { // TODO: Test when I can
                        int javaVariant = variant;
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case VILLAGER -> {
                        // Bedrock encodes the villager profession in VARIANT (region/biome in MARK_VARIANT,
                        // trade level in TRADE_TIER). All three combine into one Java VILLAGER_DATA field.
                        applyVillagerData(entity, javaEntityData);
                    }
                    default -> {
                        if (variant != 0 && !(entity instanceof CustomEntity)) { // Custom entity variants are consumed by the custom renderer.
                            ViaBedrock.getPlatform().getLogger().warning("Received non-zero VARIANT " + variant + " for unsupported entity " + entity.type());
                        }
                    }
                }

            }
            case MARK_VARIANT, TRADE_TIER -> {
                // Villager region/biome (MARK_VARIANT) and trade level (TRADE_TIER) also feed the combined
                // Java VILLAGER_DATA field. Other entities using these IDs are not translated yet.
                if (entity.javaType().is(EntityTypes1_21_11.VILLAGER)) {
                    applyVillagerData(entity, javaEntityData);
                } else {
                    return false;
                }
            }
            case COLOR_INDEX -> {
                int javaColorIndex = readNumber(entityData).intValue();

                switch (entity.javaType()) {
                    case WOLF, CAT -> {
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.COLLAR_COLOR), VersionedTypes.V26_1.entityDataTypes().varIntType, javaColorIndex));
                    }
                    case SHEEP -> { // TODO: This seems to get overwritten by the entity flags sheared value, need to combine both
                        byte sheepBitMask = 0;
                        sheepBitMask |= (byte) (javaColorIndex & 0x0F); // Lower 4 bits for color
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WOOL), VersionedTypes.V26_1.entityDataTypes().byteType, sheepBitMask));
                    }
                    default -> {
                        if (javaColorIndex != 0 && !(entity instanceof CustomEntity)) { // Custom entity colors are consumed by the custom renderer.
                            ViaBedrock.getPlatform().getLogger().warning("Received non-zero COLOR_INDEX " + javaColorIndex + " for unsupported entity " + entity.type());
                        }
                    }
                }
            }
            case OWNER -> {
                long ownerId = readNumber(entityData).longValue();
                if (ownerId == -1) {
                    break; // No owner
                }
                Entity ownerEntity = entityTracker.getEntityByUid(ownerId);
                if (ownerEntity == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find owner entity with id " + ownerId + " for entity " + entity.type());
                    break;
                }
                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.TAMABLE_ANIMAL)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.OWNERUUID), VersionedTypes.V26_1.entityDataTypes().optionalUUIDType, ownerEntity.javaUuid()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received OWNER for non-TAMEABLE_ANIMAL entity " + entity.type());
                }
            }
            case FUSE_TIME -> {
                int fuseTime = readNumber(entityData).intValue();
                if (entity.javaType().is(EntityTypes1_21_11.TNT)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FUSE), VersionedTypes.V26_1.entityDataTypes().varIntType, fuseTime));
                } else {
                    //ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received FUSE_TIME for non-TNT entity " + entity.type());
                }
            }
            case AIR_SUPPLY -> { // Air supply is stored as a short in Bedrock, but an int in Java (Bedrock also has a max air supply value we ignore for now)
                int airSupply = readNumber(entityData).intValue();
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.AIR_SUPPLY), VersionedTypes.V26_1.entityDataTypes().varIntType, airSupply));
            }
            case POSE_INDEX -> {
                if (!entity.javaType().is(EntityTypes1_21_11.ARMOR_STAND)) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received POSE_INDEX for non-ARMOR_STAND entity " + entity.type());
                    break;
                }

                byte javaBitMask = 0;
                javaBitMask |= 0x04; // Has arms
                EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
                if (scaleData != null && readNumber(scaleData).floatValue() == 0f) {
                    javaBitMask |= 0x10; // Marker: zero-size bounding box (matches Bedrock scale=0)
                }

                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CLIENT_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, javaBitMask));

                int poseIndex = readNumber(entityData).intValue();

                EulerAngle headPose;
                EulerAngle bodyPose;
                EulerAngle leftArmPose;
                EulerAngle rightArmPose;
                EulerAngle leftLegPose;
                EulerAngle rightLegPose;

                //Poses from https://github.com/lpsmods/armor-stand-poses/blob/1.21/datapack/datapack/data/poses/function/armor_stand/defaults.mcfunction
                switch (poseIndex) {
                    case 0 -> { // DEFAULT
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-10f, 0f, -10f);
                        rightArmPose = new EulerAngle(-15f, 0f, 10f);
                        leftLegPose = new EulerAngle(-1f, 0f, -1f);
                        rightLegPose = new EulerAngle(1f, 0f, 1f);
                    }
                    case 1 -> { // NONE
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(0f, 0f, 0f);
                        rightArmPose = new EulerAngle(0f, 0f, 0f);
                        leftLegPose = new EulerAngle(0f, 0f, 0f);
                        rightLegPose = new EulerAngle(0f, 0f, 0f);
                    }
                    case 2 -> { // SOLEMN
                        headPose = new EulerAngle(15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 2f);
                        leftArmPose = new EulerAngle(-30f, 15f, 15f);
                        rightArmPose = new EulerAngle(-60f, -20f, -10f);
                        leftLegPose = new EulerAngle(-1f, 0f, -1f);
                        rightLegPose = new EulerAngle(1f, 0f, 1f);
                    }
                    case 3 -> { // ATHENA
                        headPose = new EulerAngle(-5f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 2f);
                        leftArmPose = new EulerAngle(10f, 0f, -5f);
                        rightArmPose = new EulerAngle(-60f, 20f, -10f);
                        leftLegPose = new EulerAngle(-3f, -3f, -3f);
                        rightLegPose = new EulerAngle(3f, 3f, 3f);
                    }
                    case 4 -> { // BRANDISH
                        headPose = new EulerAngle(-15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, -2f);
                        leftArmPose = new EulerAngle(20f, 0f, -10f);
                        rightArmPose = new EulerAngle(-110f, 50f, 0f);
                        leftLegPose = new EulerAngle(5f, -3f, -3f);
                        rightLegPose = new EulerAngle(-5f, 3f, 3f);
                    }
                    case 5 -> { // HONOR
                        headPose = new EulerAngle(-15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-110f, 35f, 0f);
                        rightArmPose = new EulerAngle(-110f, -35f, 0f);
                        leftLegPose = new EulerAngle(5f, -3f, -3f);
                        rightLegPose = new EulerAngle(-5f, 3f, 3f);
                    }
                    case 6 -> { // ENTERTAIN
                        headPose = new EulerAngle(-15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-110f, -35f, 0f);
                        rightArmPose = new EulerAngle(-110f, 35f, 0f);
                        leftLegPose = new EulerAngle(5f, -3f, -3f);
                        rightLegPose = new EulerAngle(-5f, 3f, 3f);
                    }
                    case 7 -> { // SALUTE
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(10f, 0f, -5f);
                        rightArmPose = new EulerAngle(-70f, -40f, 0f);
                        leftLegPose = new EulerAngle(-1f, 0f, -1f);
                        rightLegPose = new EulerAngle(1f, 0f, 1f);
                    }
                    case 8 -> { // RIPOSTE
                        headPose = new EulerAngle(16f, 20f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(4f, 8f, 237f);
                        rightArmPose = new EulerAngle(246f, 0f, 89f);
                        leftLegPose = new EulerAngle(-14f, -18f, -16f);
                        rightLegPose = new EulerAngle(8f, 20f, 4f);
                    }
                    case 9 -> { // ZOMBIE
                        headPose = new EulerAngle(-10f, 0f, -5f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-105f, 0f, 0f);
                        rightArmPose = new EulerAngle(-100f, 0f, 0f);
                        leftLegPose = new EulerAngle(7f, 0f, 0f);
                        rightLegPose = new EulerAngle(-46f, 0f, 0f);
                    }
                    case 10 -> { // CAN_CAN_A
                        headPose = new EulerAngle(-5f, 18f, 0f);
                        bodyPose = new EulerAngle(0f, 22f, 0f);
                        leftArmPose = new EulerAngle(8f, 0f, -114f);
                        rightArmPose = new EulerAngle(0f, 84f, 111f);
                        leftLegPose = new EulerAngle(-111f, 55f, 0f);
                        rightLegPose = new EulerAngle(0f, 23f, -13f);
                    }
                    case 11 -> { // CAN_CAN_B
                        headPose = new EulerAngle(-10f, -20f, 0f);
                        bodyPose = new EulerAngle(0f, -18f, 0f);
                        leftArmPose = new EulerAngle(0f, 0f, -112f);
                        rightArmPose = new EulerAngle(8f, 90f, 111f);
                        leftLegPose = new EulerAngle(0f, 0f, 13f);
                        rightLegPose = new EulerAngle(-119f, -42f, 0f);
                    }
                    case 12 -> { // HERO
                        headPose = new EulerAngle(-4f, 67f, 0f);
                        bodyPose = new EulerAngle(0f, 8f, 0f);
                        leftArmPose = new EulerAngle(16f, 32f, -8f);
                        rightArmPose = new EulerAngle(-99f, 63f, 0f);
                        leftLegPose = new EulerAngle(0f, -75f, -8f);
                        rightLegPose = new EulerAngle(4f, 63f, 8f);
                    }
                    default -> {
                        // Fallback to none
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(0f, 0f, 0f);
                        rightArmPose = new EulerAngle(0f, 0f, 0f);
                        leftLegPose = new EulerAngle(0f, 0f, 0f);
                        rightLegPose = new EulerAngle(0f, 0f, 0f);
                        ViaBedrock.getPlatform().getLogger().warning("Unknown armor stand pose index " + poseIndex + ", defaulting to NONE.");
                    }
                }

                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HEAD_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, headPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BODY_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, bodyPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LEFT_ARM_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, leftArmPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.RIGHT_ARM_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, rightArmPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LEFT_LEG_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, leftLegPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.RIGHT_LEG_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, rightLegPose));
            }

            case PUFFED_STATE -> {
                int javaPuffedState = readNumber(entityData).intValue();
                if (entity.javaType().is(EntityTypes1_21_11.PUFFERFISH)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PUFF_STATE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaPuffedState));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received PUFFED_STATE for non-PUFFERFISH entity " + entity.type());
                }
            }
            case FREEZING_EFFECT_STRENGTH -> {
                float freezingStrength = readNumber(entityData).floatValue();

                // Java freezing strength is from 0-140 whereas Bedrock is from 0.0-1.0
                int javaStrength = Math.round(freezingStrength * 140f);
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TICKS_FROZEN), VersionedTypes.V26_1.entityDataTypes().varIntType, javaStrength));
            }
            case GOAT_HORN_COUNT -> {
                if (entity.javaType().is(EntityTypes1_21_11.GOAT)) {
                    // In bedrock the goat always loses its right horn first, whereas in java its random
                    int hornCount = readNumber(entityData).intValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HAS_LEFT_HORN), VersionedTypes.V26_1.entityDataTypes().booleanType, hornCount != 0));
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HAS_RIGHT_HORN), VersionedTypes.V26_1.entityDataTypes().booleanType, hornCount == 2));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received GOAT_HORN_COUNT for non-GOAT entity " + entity.type());
                }
            }
            case EATING_COUNTER -> {
                int eatingCounter = readNumber(entityData).intValue();
                if (entity.javaType().is(EntityTypes1_21_11.PANDA)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.EAT_COUNTER), VersionedTypes.V26_1.entityDataTypes().varIntType, eatingCounter));
                } else if (eatingCounter != 0) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received EATING_COUNTER for non-PANDA entity " + entity.type() + " with non-zero value " + eatingCounter);
                }
            }
            case ATTACH_FACE -> {
                if (entity.javaType().is(EntityTypes1_21_11.SHULKER)) {
                    int javaAttachFace = readNumber(entityData).intValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ATTACH_FACE), VersionedTypes.V26_1.entityDataTypes().directionType, javaAttachFace));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received ATTACH_FACE for non-SHULKER entity " + entity.type());
                }
            }
            case PEEK_ID -> {
                if (entity.javaType().is(EntityTypes1_21_11.SHULKER)) {
                    byte peek = readNumber(entityData).byteValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PEEK), VersionedTypes.V26_1.entityDataTypes().byteType, peek));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received PEEK_ID for non-SHULKER entity " + entity.type());
                }
            }
            case ATTACHED, ATTACH_POS -> { // Not needed in java
                if (!entity.javaType().is(EntityTypes1_21_11.SHULKER)) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received ATTACH for non-SHULKER entity " + entity.type());
                }
            }
            case RESERVED_053 -> { // BOUNDING_BOX_WIDTH
                if (entity.javaType().is(EntityTypes1_21_11.INTERACTION)) {
                    float width = readNumber(entityData).floatValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WIDTH), VersionedTypes.V26_1.entityDataTypes().floatType, width));
                }
            }
            case RESERVED_054 -> { // BOUNDING_BOX_HEIGHT
                if (entity.javaType().is(EntityTypes1_21_11.INTERACTION)) {
                    float height = readNumber(entityData).floatValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HEIGHT), VersionedTypes.V26_1.entityDataTypes().floatType, height));
                }
            }
            case DATA_RADIUS -> {
                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    float radius = readNumber(entityData).floatValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.RADIUS), VersionedTypes.V26_1.entityDataTypes().floatType, radius));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received DATA_RADIUS for non-AREA_EFFECT_CLOUD entity " + entity.type());
                }
            }
            case DATA_WAITING -> {
                if (entity.javaType().is(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    boolean isWaiting = (boolean) entityData.getValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WAITING), VersionedTypes.V26_1.entityDataTypes().booleanType, isWaiting));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received DATA_WAITING for non-AREA_EFFECT_CLOUD entity " + entity.type());
                }
            }
            case DATA_PARTICLE -> {
                if (entity.javaType().is(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    int particle_id_or_colour = readNumber(entityData).intValue(); //TODO: not sure what this is exactly
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received DATA_PARTICLE for non-AREA_EFFECT_CLOUD entity " + entity.type());
                }
            }
            case INV -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    int invulnerabilityTicks = readNumber(entityData).intValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.INV), VersionedTypes.V26_1.entityDataTypes().varIntType, invulnerabilityTicks));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received INV for non-WITHER entity " + entity.type());
                }
            }
            case TARGET_A -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    long targetAId = readNumber(entityData).longValue();
                    if (targetAId == -1) {
                        break; // No target
                    }
                    Entity targetAEntity = entityTracker.getEntityByUid(targetAId);
                    if (targetAEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET_A entity with id " + targetAId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_A), VersionedTypes.V26_1.entityDataTypes().varIntType, targetAEntity.javaId()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET_A for non-WITHER entity " + entity.type());
                }
            }
            case TARGET_B -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    long targetBId = readNumber(entityData).longValue();
                    if (targetBId == -1) {
                        break; // No target
                    }
                    Entity targetBEntity = entityTracker.getEntityByUid(targetBId);
                    if (targetBEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET_B entity with id " + targetBId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_B), VersionedTypes.V26_1.entityDataTypes().varIntType, targetBEntity.javaId()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET_B for non-WITHER entity " + entity.type());
                }
            }
            case TARGET_C -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    long targetCId = readNumber(entityData).longValue();
                    if (targetCId == -1) {
                        break; // No target
                    }
                    Entity targetCEntity = entityTracker.getEntityByUid(targetCId);
                    if (targetCEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET_C entity with id " + targetCId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_C), VersionedTypes.V26_1.entityDataTypes().varIntType, targetCEntity.javaId()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET_C for non-WITHER entity " + entity.type());
                }
            }
            case TARGET -> {
                long targetId = readNumber(entityData).longValue();
                if (entity.javaType().is(EntityTypes1_21_11.GUARDIAN)) {
                    if (targetId == 0) {
                        break; // No target
                    }
                    Entity targetEntity = entityTracker.getEntityByUid(targetId);
                    if (targetEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET entity with id " + targetId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ATTACK_TARGET), VersionedTypes.V26_1.entityDataTypes().varIntType, targetEntity.javaId()));
                } else if (targetId != 0)  {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET for non-GUARDIAN entity " + entity.type() + " with non-zero value " + targetId);
                }
            }
            case NAME, NAME_RAW_TEXT -> {
                // Trim blank lines so a single-line CUSTOM_NAME never carries leading/trailing newlines
                // (rendered as missing-glyph boxes). For multiline always-show entities the multiline
                // tracker spawns a TEXT_DISPLAY and filters this CUSTOM_NAME out of the packet.
                String name = TextUtil.trimBlankLines((String) entityData.getValue());
                if (name != null && !TextUtil.stripFormatting(name).isEmpty()) {
                    Tag nbtName = TextUtil.stringToNbt(name);
                    javaEntityData.add(new EntityData(
                        entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                        VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                        nbtName));
                    javaEntityData.add(new EntityData(
                        entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                        VersionedTypes.V26_1.entityDataTypes().booleanType,
                        true));
                } else {
                    javaEntityData.add(new EntityData(
                        entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                        VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                        null));
                    javaEntityData.add(new EntityData(
                        entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                        VersionedTypes.V26_1.entityDataTypes().booleanType,
                        false));
                }
            }
            case NAMETAG_ALWAYS_SHOW -> {
                byte alwaysShow = (byte) entityData.getValue();
                boolean hasName = false;
                if (alwaysShow == 1) {
                    final EntityData nameData = entity.entityData().get(ActorDataIDs.NAME);
                    final EntityData nameRawData = entity.entityData().get(ActorDataIDs.NAME_RAW_TEXT);
                    final String name = nameData != null ? (String) nameData.getValue() : null;
                    final String nameRaw = nameRawData != null ? (String) nameRawData.getValue() : null;
                    hasName = (name != null && !TextUtil.stripFormatting(name).isEmpty()) || (nameRaw != null && !TextUtil.stripFormatting(nameRaw).isEmpty());
                }
                javaEntityData.add(new EntityData(
                    entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                    VersionedTypes.V26_1.entityDataTypes().booleanType,
                    alwaysShow == 1 && hasName));
            }
            case RESERVED_038 -> { // SCALE (Bedrock entity data ID 38)
                float scale = readNumber(entityData).floatValue();
                if (entity instanceof LivingEntity) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SHARED_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, sharedFlags(entity, scale == 0f)));
                }
                if (entity.javaType().is(EntityTypes1_21_11.ARMOR_STAND)) {
                    if (scale == 0f) {
                        // Bedrock: scale=0 hides body but keeps nametag visible
                        // Java armor stands also need Marker to remove bounding box height.
                        byte clientFlags = 0x04; // Has arms
                        clientFlags |= 0x10; // Marker: zero-size bounding box
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CLIENT_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, clientFlags));
                    } else {
                        byte clientFlags = 0x04; // Has arms, no marker
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CLIENT_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, clientFlags));
                    }
                }

                // Send minecraft:scale attribute for all LivingEntity types (including ARMOR_STAND when scale != 0)
                if (entity instanceof LivingEntity && scale != 0f) {
                    final double javaScale = Math.max(1.0 / 16.0, Math.min(16.0, (double) scale));

                    final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, user);
                    updateAttributes.write(Types.VAR_INT, entity.javaId()); // entity id
                    updateAttributes.write(Types.VAR_INT, 1); // attribute count
                    updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.SCALE)); // attribute id
                    updateAttributes.write(Types.DOUBLE, javaScale); // base value
                    updateAttributes.write(Types.VAR_INT, 0); // modifier count
                    updateAttributes.send(BedrockProtocol.class);
                }
            }
            case AGENT, BALLOON_ANCHOR -> {} // Education edition only, ignore
            default -> {
                return false;
            }
        }

        return true;
    }

    // Bedrock villager VARIANT (profession) -> Java profession registry id. Inverse of Geyser's
    // VillagerEntity#VILLAGER_PROFESSIONS (Java->Bedrock). Index = Bedrock value, value = Java id.
    private static final int[] BEDROCK_TO_JAVA_PROFESSION = {0, 5, 6, 12, 7, 9, 3, 4, 1, 14, 13, 2, 8, 10, 11};
    // Bedrock villager MARK_VARIANT (region/biome) -> Java villager type registry id. Inverse of Geyser's
    // VillagerEntity#VILLAGER_REGIONS.
    private static final int[] BEDROCK_TO_JAVA_REGION = {2, 0, 1, 3, 4, 5, 6};

    /**
     * Combines the Bedrock villager fields VARIANT (profession), MARK_VARIANT (region/biome) and TRADE_TIER
     * (trade level) into a single Java VILLAGER_DATA entry. Reads sibling fields from the entity's stored
     * data so a change to any one of them rebuilds the complete value (Entity#updateEntityData stores the
     * whole batch before translating, so the latest values are always visible here).
     */
    private static void applyVillagerData(final Entity entity, final List<EntityData> javaEntityData) {
        final EntityData variantData = entity.entityData().get(ActorDataIDs.VARIANT);
        final EntityData markVariantData = entity.entityData().get(ActorDataIDs.MARK_VARIANT);
        final EntityData tradeTierData = entity.entityData().get(ActorDataIDs.TRADE_TIER);

        final int bedrockProfession = variantData != null ? readNumber(variantData).intValue() : 0;
        final int bedrockRegion = markVariantData != null ? readNumber(markVariantData).intValue() : 0;
        final int bedrockTradeTier = tradeTierData != null ? readNumber(tradeTierData).intValue() : 0;

        final int profession = bedrockProfession >= 0 && bedrockProfession < BEDROCK_TO_JAVA_PROFESSION.length
                ? BEDROCK_TO_JAVA_PROFESSION[bedrockProfession] : 0;
        final int type = bedrockRegion >= 0 && bedrockRegion < BEDROCK_TO_JAVA_REGION.length
                ? BEDROCK_TO_JAVA_REGION[bedrockRegion] : 0;
        final int level = bedrockTradeTier + 1; // Java trade levels are 1-based, Bedrock 0-based

        final int index = entity.getJavaEntityDataIndex(EntityDataFields.VILLAGER_DATA);
        // Idempotent: if several villager fields arrive in one batch, keep a single VILLAGER_DATA entry.
        javaEntityData.removeIf(d -> d.id() == index);
        javaEntityData.add(new EntityData(index, VersionedTypes.V26_1.entityDataTypes().villagerDataType, new VillagerData(type, profession, level)));
    }

    private static byte sharedFlags(final Entity entity, final boolean forceInvisible) {
        final Set<ActorFlags> bedrockFlags = entity.entityFlags();
        byte sharedFlags = 0;
        if (bedrockFlags.contains(ActorFlags.ONFIRE)) sharedFlags |= (1 << 0);
        if (bedrockFlags.contains(ActorFlags.SNEAKING)) sharedFlags |= (1 << 1);
        if (bedrockFlags.contains(ActorFlags.RIDING)) sharedFlags |= (1 << 2);
        if (bedrockFlags.contains(ActorFlags.SPRINTING)) sharedFlags |= (1 << 3);
        if (bedrockFlags.contains(ActorFlags.SWIMMING)) sharedFlags |= (1 << 4);
        if (forceInvisible || bedrockFlags.contains(ActorFlags.INVISIBLE)) sharedFlags |= (1 << 5);
        return sharedFlags;
    }

    private static Number readNumber(EntityData data) {
        if (data.dataType() == null || data.getValue() == null) {
            throw new IllegalArgumentException("EntityData " + data.id() + " has null data type or value");
        }
        return switch ((EntityDataTypesBedrock) data.dataType()) {
            case BYTE -> (byte) data.getValue();
            case SHORT -> (short) data.getValue();
            case INT -> (int) data.getValue();
            case FLOAT -> (float) data.getValue();
            case LONG -> (long) data.getValue();
            default -> throw new IllegalArgumentException("Unsupported number type: " + data.dataType());
        };
    }
}
