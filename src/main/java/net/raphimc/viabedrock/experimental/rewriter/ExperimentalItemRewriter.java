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
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.nbt.tag.*;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.SoundEvent;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.data.Consumable1_21_2;
import com.viaversion.viaversion.api.minecraft.item.data.Enchantments;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.api.util.RegistryUtil;
import net.raphimc.viabedrock.experimental.model.map.MapObject;
import net.raphimc.viabedrock.experimental.storage.MapTracker;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.JavaRegistries;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Enchant_Type;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.Set;
import java.util.logging.Level;

public class ExperimentalItemRewriter {

    private static final StructuredDataKey<Item[]> CHARGED_PROJECTILES = new StructuredDataKey<>("charged_projectiles", VersionedTypes.V26_1.itemArray());

    private static final int BLOCK_USE_ANIMATION = 3;
    private static final float VISUAL_BLOCK_DURATION_SECONDS = 1_000_000F;
    private static final Set<String> VANILLA_SWORDS = Set.of(
            "minecraft:wooden_sword",
            "minecraft:stone_sword",
            "minecraft:iron_sword",
            "minecraft:golden_sword",
            "minecraft:diamond_sword",
            "minecraft:netherite_sword"
    );
    private static final Consumable1_21_2 VISUAL_BLOCK_CONSUMABLE = new Consumable1_21_2(
            VISUAL_BLOCK_DURATION_SECONDS,
            BLOCK_USE_ANIMATION,
            Holder.of(new SoundEvent("minecraft:entity.generic.eat", null)),
            false,
            new Consumable1_21_2.ConsumeEffect[0]
    );

    private static final long MAP_INFO_REQUEST_THROTTLE_MS = 1000L;

    // BedrockTag can be null
    public static void handleItem(final UserConnection user, final BedrockItem bedrockItem, final CompoundTag bedrockTag, final Item javaItem) {
        if (isSwordBlockingAnimationItem(user.get(ItemRewriter.class), bedrockItem)) {
            javaItem.dataContainer().set(StructuredDataKey.CONSUMABLE1_21_2, VISUAL_BLOCK_CONSUMABLE);
        }

        if (bedrockTag != null) {

            if (bedrockTag.get("Damage") instanceof NumberTag durability) {
                final int damage = durability.asInt();
                if (damage != 0) {
                    javaItem.dataContainer().set(StructuredDataKey.DAMAGE, damage);
                }
            }

            if (bedrockTag.get("map_uuid") instanceof NumberTag uuidTag) {
                MapTracker mapTracker = user.get(MapTracker.class);
                final long uuid = uuidTag.asLong();

                MapObject map = mapTracker.getMapObjects().get(uuid);
                if (map == null) {
                    final int mapId = mapTracker.getNextMapId();
                    map = new MapObject(uuid, mapId);
                    mapTracker.getMapObjects().put(uuid, map);
                    //ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Registered new map with id " + mapId + " and uuid " + uuid);
                }

                javaItem.dataContainer().set(StructuredDataKey.MAP_ID, map.getJavaId());

                // Bedrock servers (e.g. Nukkit) only send MAP_ITEM_DATA in response to a MapInfoRequest.
                // Request the texture if we don't have it yet, throttled to avoid spamming the server.
                if (!map.hasTexture()) {
                    final long now = System.currentTimeMillis();
                    if (now - map.getLastRequestedMs() > MAP_INFO_REQUEST_THROTTLE_MS) {
                        map.setLastRequestedMs(now);
                        requestMapInfo(user, uuid);
                    }
                }
            }

            if (bedrockTag.get("ench") instanceof ListTag<?> enchantments) {

                // Bedrock uses an empty enchantment list to request glint without tooltip entries.
                if (enchantments.isEmpty()) {
                    javaItem.dataContainer().set(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE, true);
                }

                StructuredData<Enchantments> enchantmentsData = javaItem.dataContainer().getData(StructuredDataKey.ENCHANTMENTS1_21_5);
                Enchantments javaEnchantments;
                if (enchantmentsData == null || enchantmentsData.isEmpty()) {
                    javaEnchantments = new Enchantments(true);
                } else {
                    javaEnchantments = enchantmentsData.value();
                }

                for (Tag enchantment : enchantments) {
                    if (enchantment instanceof CompoundTag compoundTag) {
                        //id and lvl must be a short. Else bedrock defaults to protection (id 0) and lvl 0 (TODO: implement the fallback)
                        if (compoundTag.get("id") instanceof ShortTag idTag && compoundTag.get("lvl") instanceof ShortTag levelTag) {
                            Enchant_Type bedrockId = Enchant_Type.getByValue(idTag.asInt());
                            int level = levelTag.asInt();

                            if (bedrockId == null) {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown enchantment with id " + idTag.asInt() + " and level " + level);
                                continue;
                            }

                            String javaEnchantmentId = BedrockProtocol.MAPPINGS.getBedrockToJavaEnchantments().get(bedrockId);

                            //Update the java item with the enchantment
                            if (javaEnchantmentId != null) {
                                CompoundTag enchantmentsRegistry = (CompoundTag) BedrockProtocol.MAPPINGS.getJavaRegistries().get("minecraft:enchantment");
                                CompoundTag enchantmentEntry = (CompoundTag) enchantmentsRegistry.get(javaEnchantmentId);
                                if (enchantmentEntry == null) {
                                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Enchantment entry is null for enchantment " + javaEnchantmentId);
                                } else {
                                    int javaId = RegistryUtil.getRegistryIndex(enchantmentsRegistry, enchantmentEntry);
                                    javaEnchantments.add(javaId, javaEnchantmentLevel(level));
                                }
                            } else {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown enchantment with id " + bedrockId + " and level " + level);
                            }
                        }
                    }
                }

                javaItem.dataContainer().set(StructuredDataKey.ENCHANTMENTS1_21_5, javaEnchantments);
            }

            if (bedrockTag.get("chargedItem") instanceof CompoundTag chargedItemTag) {
                final Item chargedProjectile = chargedProjectile(user, chargedItemTag);
                if (chargedProjectile != null) {
                    javaItem.dataContainer().set(CHARGED_PROJECTILES, new Item[]{chargedProjectile});
                }
            }

        }
    }

    public static boolean isSwordBlockingAnimationItem(final ItemRewriter itemRewriter, final BedrockItem item) {
        final boolean enabled = ViaBedrock.getConfig().shouldEnableSwordBlockingAnimation();
        if (!enabled) {
            return false;
        }

        return shouldApplySwordBlockingAnimation(enabled, itemRewriter.bedrockIdentifier(item));
    }

    static boolean shouldApplySwordBlockingAnimation(final boolean enabled, final String identifier) {
        return enabled && identifier != null && VANILLA_SWORDS.contains(identifier);
    }

    static int javaEnchantmentLevel(final int bedrockLevel) {
        return MathUtil.clamp(bedrockLevel, 0, 255);
    }

    private static void requestMapInfo(final UserConnection user, final long bedrockMapId) {
        final PacketWrapper mapInfoRequest = PacketWrapper.create(ServerboundBedrockPackets.MAP_INFO_REQUEST, user);
        mapInfoRequest.write(BedrockTypes.VAR_LONG, bedrockMapId); // map id
        mapInfoRequest.write(BedrockTypes.UNSIGNED_INT_LE, 0L); // client pixels (uint32 length-prefixed, empty)
        mapInfoRequest.sendToServer(BedrockProtocol.class);
    }

    private static Item chargedProjectile(final UserConnection user, final CompoundTag chargedItemTag) {
        final String identifier = chargedItemTag.getString("Name", null);
        if (identifier == null) {
            return null;
        }

        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final Integer bedrockId = itemRewriter.getItems().get(identifier);
        if (bedrockId == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown charged projectile item: " + identifier);
            return null;
        }

        final int count = chargedItemTag.get("Count") instanceof NumberTag countTag ? countTag.asInt() : 1;
        final int damage = chargedItemTag.get("Damage") instanceof NumberTag damageTag ? damageTag.asInt() : 0;
        final BedrockItem projectile = new BedrockItem(bedrockId, (short) damage, (byte) count);
        return itemRewriter.javaItem(projectile);
    }
}
