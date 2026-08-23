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

import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemReleaseInventoryTransaction_ActionType;

import java.util.Set;

public final class ItemUseSemantics {

    private static final int DEFAULT_CONSUMABLE_USE_TICKS = 32;
    private static final String FOOD_ITEM_TAG = "minecraft:is_food";
    private static final Set<String> CONSUME_ON_RELEASE_ITEMS = Set.of(
            "minecraft:potion",
            "minecraft:milk_bucket",
            // MOT ItemOminousBottle.onUse() requires ticksUsed >= getUseDuration()-2 (32).
            // It is not in minecraft:is_food; without this set Java right-click is a no-op.
            "minecraft:ominous_bottle"
    );
    private static final Set<String> RELEASE_ON_RELEASE_ITEMS = Set.of(
            "minecraft:bow",
            "minecraft:crossbow",
            "minecraft:trident",
            // MOT ItemBrush has no onClickAir/canRelease. Java brushing is
            // USE_ITEM_ON against the suspicious block; treating brush as
            // hold-to-use swallowed that CLICK_BLOCK.
            "minecraft:spyglass",
            "minecraft:shield"
    );
    private static final String SPEAR_ITEM_TAG = "minecraft:is_spear";
    private static final String ARMOR_ITEM_TAG = "minecraft:is_armor";
    /**
     * MOT {@code onActivate} delegates to {@code onClickAir} for these items.
     * Java often emits USE_ITEM_ON then USE_ITEM for one right-click; the extra
     * CLICK_AIR would double-throw / double-cast / give two maps / equip twice.
     * Ref: MOT ProjectileItem, ItemFishingRod, ItemEmptyMap, ItemArmor.
     */
    private static final Set<String> DUPLICATE_CLICK_AIR_AFTER_USE_ON = Set.of(
            "minecraft:ender_pearl",
            "minecraft:ender_eye",
            "minecraft:snowball",
            "minecraft:egg",
            "minecraft:splash_potion",
            "minecraft:lingering_potion",
            "minecraft:experience_bottle",
            "minecraft:wind_charge",
            "minecraft:fishing_rod",
            "minecraft:empty_map",
            // MOT ItemFirework.onActivate places on pass-through blocks, then onClickAir
            // boosts while gliding. Java USE_ITEM_ON+USE_ITEM would consume two rockets.
            "minecraft:firework_rocket"
    );

    static boolean isContinuousUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final boolean chargedCrossbow) {
        if (identifier == null || ("minecraft:crossbow".equals(identifier) && chargedCrossbow)) {
            return false;
        }
        return RELEASE_ON_RELEASE_ITEMS.contains(identifier)
                || isSpear(identifier, itemTags)
                || CONSUME_ON_RELEASE_ITEMS.contains(identifier)
                || isFood(itemTags)
                || itemUse != null;
    }

    public static boolean isConsumableUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse) {
        return consumableUseTicks(identifier, itemTags, itemUse) > 0;
    }

    /**
     * Official Bedrock starts food from PlayerAuthInput START_USING_ITEM +
     * PERFORM_ITEM_INTERACTION. NetEase 860 Nukkit-MOT parses that payload but never
     * handles it, so consumables need a standalone USE_ITEM transaction there.
     * Bows/crossbows already send that standalone packet on both paths.
     * MOT 860 also starts tridents and spears from CLICK_AIR
     * ({@code ItemTrident.onClickAir}, {@code ItemSpear.onClickAir}); AuthInput
     * START_USING_ITEM is parsed and ignored, so NetEase must send the same
     * standalone packet. Shields must not: MOT blocking is sneak, not use.
     */
    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow, final boolean trident,
                                                 final boolean spear) {
        return needsStandaloneUseTransaction(emulateNetEase, consumable, bow, crossbow, trident, spear, false);
    }

    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow, final boolean trident,
                                                 final boolean spear, final boolean spyglass) {
        return bow || crossbow || (emulateNetEase && (trident || spear || consumable || spyglass));
    }

    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow, final boolean trident) {
        return needsStandaloneUseTransaction(emulateNetEase, consumable, bow, crossbow, trident, false);
    }

    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow) {
        return needsStandaloneUseTransaction(emulateNetEase, consumable, bow, crossbow, false, false);
    }

    /**
     * Nukkit's MobEquipmentProcessor always calls {@code setUsingItem(false)}. Sending
     * MOB_EQUIPMENT immediately before a NetEase consumable CLICK_AIR therefore cancels
     * the using-state that the first click just started.
     */
    static boolean skipMobEquipmentBeforeUse(final boolean emulateNetEase, final boolean consumable) {
        return emulateNetEase && consumable;
    }

    /**
     * Java may emit USE_ITEM_ON then USE_ITEM for the same right-click. Nukkit 860 treats a
     * second CLICK_AIR while already using as a finish attempt, so the extra packet must be
     * dropped until the held duration is ready.
     */
    static boolean ignoreDuplicateUseStart(final boolean alreadyUsing, final boolean sameItem) {
        return alreadyUsing && sameItem;
    }

    /**
     * Official 975 starts food from AuthInput START_USING_ITEM + PERFORM_ITEM_INTERACTION.
     * NetEase 860 Nukkit parses that payload but never handles it. A second CLICK_AIR
     * while already using is treated as a finish attempt, so NetEase must not attach
     * the same ItemUseTransaction to AuthInput for any hold-to-use item
     * (food, bow, crossbow, trident, spear, shield, spyglass, brush).
     */
    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable,
                                                  final boolean bow, final boolean holdToUseWeapon) {
        if (!emulateNetEase) {
            return true;
        }
        return !consumable && !bow && !holdToUseWeapon;
    }

    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable,
                                                  final boolean bow) {
        return attachAuthInputItemInteraction(emulateNetEase, consumable, bow, false);
    }

    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable) {
        return attachAuthInputItemInteraction(emulateNetEase, consumable, false, false);
    }

    /**
     * MOT CLICK_AIR compares the held stack with {@code equalsFast} (id+data only).
     * A later INVENTORY_SLOT rewrite that only changes NBT/netId/blockRuntimeId must
     * not cancel an in-progress eat/draw on NetEase.
     */
    public static boolean matchesUseItem(final boolean emulateNetEase, final int snapshotId, final short snapshotData,
                                  final int snapshotBlockRuntimeId, final Object snapshotTag,
                                  final Integer currentId, final Short currentData, final Integer currentBlockRuntimeId,
                                  final Object currentTag) {
        if (currentId == null || currentData == null) {
            return false;
        }
        if (emulateNetEase) {
            return snapshotId == currentId && snapshotData == currentData;
        }
        return snapshotId == currentId
                && snapshotData == currentData
                && snapshotBlockRuntimeId == (currentBlockRuntimeId == null ? 0 : currentBlockRuntimeId)
                && java.util.Objects.equals(snapshotTag, currentTag);
    }

    /**
     * MOT {@code ItemEdible.onClickAir} is {@code player.canEat(true)} except for
     * golden apples, chorus fruit and honey bottles, which always return true.
     * Potions/milk/ominous bottles are not ItemEdible and also start regardless of hunger.
     */
    static boolean alwaysEatsWhenFull(final String identifier) {
        return "minecraft:golden_apple".equals(identifier)
                || "minecraft:enchanted_golden_apple".equals(identifier)
                || "minecraft:chorus_fruit".equals(identifier)
                || "minecraft:honey_bottle".equals(identifier)
                || CONSUME_ON_RELEASE_ITEMS.contains(identifier);
    }

    static boolean canStartConsumable(final boolean emulateNetEase, final String identifier, final boolean consumable,
                                      final boolean creative, final boolean hungry) {
        if (!emulateNetEase || !consumable) {
            return true;
        }
        return creative || hungry || alwaysEatsWhenFull(identifier);
    }

    /**
     * MOT {@code ItemBow.getArrow} only accepts id 262 ({@code minecraft:arrow}), not
     * spectral/tipped. Survival with no such arrow must not start a local draw.
     */
    static boolean canStartBow(final boolean emulateNetEase, final boolean bow, final boolean creative,
                               final boolean hasRegularArrow) {
        if (!emulateNetEase || !bow) {
            return true;
        }
        return creative || hasRegularArrow;
    }

    static boolean isRegularArrow(final String identifier) {
        return "minecraft:arrow".equals(identifier);
    }

    static boolean isShield(final String identifier) {
        return "minecraft:shield".equals(identifier);
    }

    static boolean isSpyglass(final String identifier) {
        return "minecraft:spyglass".equals(identifier);
    }

    /**
     * MOT {@code ItemSpear.onClickAir} starts using and {@code canRelease()} is true.
     * Java 1.21 spears are also hold-to-use; without this mapping a right-click
     * becomes an instant CLICK_AIR and never reaches {@code onRelease}/stab.
     * Prefer the Bedrock {@code minecraft:is_spear} tag, then identifier suffix.
     */
    static boolean isSpear(final String identifier, final Set<String> itemTags) {
        if (itemTags != null && itemTags.contains(SPEAR_ITEM_TAG)) {
            return true;
        }
        return identifier != null && identifier.endsWith("_spear");
    }

    static boolean isSpear(final String identifier) {
        return isSpear(identifier, null);
    }

    /**
     * MOT {@code ItemShield} has no onClickAir. Blocking is sneak-or-riding + shield
     * in either hand. NetEase must not send a use transaction for a shield.
     */
    static boolean emulateShieldAsSneak(final boolean emulateNetEase, final boolean shield) {
        return emulateNetEase && shield;
    }

    /**
     * MOT {@code ItemCrossbow.launchArrow} requires {@code serverTick - loadTick > 10}.
     * Firing on the same tick charge completes starts a new charge instead of shooting.
     */
    static boolean crossbowFireReady(final boolean emulateNetEase, final boolean charged, final int ticksSinceCharge) {
        if (!emulateNetEase || !charged) {
            return true;
        }
        return ticksSinceCharge > 10;
    }

    static boolean chargedCrossbowUsesMotTag(final boolean emulateNetEase, final boolean hasChargedItem,
                                             final boolean hasJavaChargedProjectiles) {
        return emulateNetEase ? hasChargedItem : (hasChargedItem || hasJavaChargedProjectiles);
    }

    /**
     * Java may emit USE_ITEM_ON then USE_ITEM for one click. MOT items whose
     * {@code onActivate} calls {@code onClickAir} would then double-apply.
     */
    static boolean dropDuplicateAirClickAfterUseOn(final boolean emulateNetEase, final String identifier,
                                                   final boolean sameTickUseOn) {
        return dropDuplicateAirClickAfterUseOn(emulateNetEase, identifier, null, sameTickUseOn);
    }

    static boolean dropDuplicateAirClickAfterUseOn(final boolean emulateNetEase, final String identifier,
                                                   final Set<String> itemTags, final boolean sameTickUseOn) {
        if (!emulateNetEase || !sameTickUseOn || identifier == null) {
            return false;
        }
        if (DUPLICATE_CLICK_AIR_AFTER_USE_ON.contains(identifier)) {
            return true;
        }
        return itemTags != null && itemTags.contains(ARMOR_ITEM_TAG);
    }

    /**
     * MOT {@code Player.onSpinAttack}: {@code riptideTicks = 50 + (level << 5)}.
     * Unknown level uses 1 so the proxy never leaves spin latched forever.
     */
    public static int riptideDurationTicks(final int riptideLevel) {
        final int level = riptideLevel > 0 ? riptideLevel : 1;
        return 50 + (level << 5);
    }

    /**
     * MOT {@code ItemTrident.onRelease} reads {@code Enchantment.getEnchantmentLevel(RIPTIDE)}.
     * Bedrock stores that as a short {@code id}/{@code lvl} pair in {@code ench}.
     */
    public static int riptideLevel(final com.viaversion.nbt.tag.CompoundTag tag) {
        if (tag == null) {
            return 1;
        }
        if (!(tag.get("ench") instanceof com.viaversion.nbt.tag.ListTag<?> enchantments)) {
            return 1;
        }
        int level = 0;
        for (final com.viaversion.nbt.tag.Tag enchantment : enchantments) {
            if (!(enchantment instanceof com.viaversion.nbt.tag.CompoundTag compoundTag)) {
                continue;
            }
            if (!(compoundTag.get("id") instanceof com.viaversion.nbt.tag.NumberTag idTag)
                    || !(compoundTag.get("lvl") instanceof com.viaversion.nbt.tag.NumberTag lvlTag)) {
                continue;
            }
            if (idTag.asInt() == net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Enchant_Type.Riptide.getValue()) {
                level = Math.max(level, lvlTag.asInt());
            }
        }
        return level > 0 ? level : 1;
    }

    /**
     * MOT {@code Level.useItemOn} activates the block first unless sneaking.
     * Hold-to-use (food/bow/shield) must start from Java {@code USE_ITEM}, not {@code USE_ITEM_ON}.
     */
    public static boolean shouldStartContinuousUseFromUseItemOn(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    static boolean isFilledPlaceBucket(final String identifier) {
        return "minecraft:water_bucket".equals(identifier)
                || "minecraft:lava_bucket".equals(identifier)
                || "minecraft:powder_snow_bucket".equals(identifier)
                || "minecraft:cod_bucket".equals(identifier)
                || "minecraft:salmon_bucket".equals(identifier)
                || "minecraft:tropical_fish_bucket".equals(identifier)
                || "minecraft:pufferfish_bucket".equals(identifier)
                || "minecraft:axolotl_bucket".equals(identifier)
                || "minecraft:tadpole_bucket".equals(identifier);
    }

    /**
     * MOT CLICK_AIR / CLICK_BLOCK always call {@code inventory.getItemInHand()}
     * after {@code equipItem(hotbarSlot)}. {@code equipItem} rejects {@code < 0},
     * and {@code equalsFast(itemInHand)} still compares against the main hand, so
     * NetEase cannot consume an offhand stack from UseItemData. Keep the Java
     * offhand (except shield sneak-emulation) until the player swaps with F.
     * Ref: MOT Player.java case 1 CLICK_AIR; PlayerInventory.equipItem.
     */
    static boolean rejectNetEaseOffhandUse(final boolean emulateNetEase, final boolean offhand, final boolean shield) {
        return emulateNetEase && offhand && !shield;
    }

    /**
     * MOT Player.java never handles {@code StartItemUseOn}. Sending it on NetEase bow
     * start is unused noise; keep it on official 975 only.
     */
    static boolean sendStartItemUseOnForBow(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * Official 975 still finishes food with a second USE_ITEM plus delayed ItemRelease.
     * Nukkit 860 treats a second CLICK_AIR as {@code onUse(ticksUsed)} and always clears
     * using-state first. If that packet arrives before {@code eatingTick} (apple = 31),
     * {@code onUse} fails and {@code processAutoCompletion()} can never consume.
     * <p>
     * Java chewing is hidden separately by {@code javaUsingVisible()}. NetEase therefore
     * must not send a finish click at all and let Nukkit auto-complete while protocol
     * using-state stays true.
     */
    static boolean sendConsumableFinishTransaction(final boolean emulateNetEase, final boolean consumable) {
        return consumable && !emulateNetEase;
    }

    /**
     * Official 975 still follows that second USE_ITEM with a delayed ItemRelease; Nukkit
     * 860 ignores ItemRelease Consume here, so NetEase must not send it.
     */
    static boolean delayReleaseAfterConsumableFinish(final boolean emulateNetEase, final boolean consumable) {
        return consumable && !emulateNetEase;
    }

    /**
     * Official 975 can drop the local using-state as soon as the finish packet is queued.
     * NetEase still has to keep protocol-side using-state until the finish USE_ITEM is
     * actually sent, otherwise START_SPRINTING / MOB_EQUIPMENT cancel Nukkit first.
     * Java chewing itself is driven separately by {@code javaUsingVisible()}.
     */
    static boolean keepLocalUsingAfterConsumableFinish(final boolean emulateNetEase, final boolean consumable, final boolean finishSent) {
        return emulateNetEase && consumable && !finishSent;
    }

    /**
     * Java always sends RELEASE_USE_ITEM when the local eat animation ends. Official 975
     * still translates a too-early release into ItemRelease; Nukkit 860's ItemRelease
     * handler has a finally that always calls {@code setUsingItem(false)}, which would
     * cancel eating before {@code processAutoCompletion()} can consume. NetEase therefore
     * must ignore that Java release and keep waiting for auto-complete.
     */
    static boolean ignoreJavaConsumableRelease(final boolean emulateNetEase, final boolean consumable) {
        return emulateNetEase && consumable;
    }

    static boolean consumableConsumedByServer(final int startedId, final int startedAmount,
                                             final boolean currentEmpty, final int currentId, final int currentAmount) {
        if (startedId == 0 || startedAmount <= 0) {
            return false;
        }
        if (currentEmpty || currentId == 0) {
            return true;
        }
        return currentId == startedId && currentAmount < startedAmount;
    }

    static boolean localUsingTimedOut(final boolean emulateNetEase, final boolean consumable, final int usingTicks) {
        return emulateNetEase && consumable && usingTicks >= DEFAULT_CONSUMABLE_USE_TICKS + 16;
    }

    /**
     * Java's eat animation is local. Once the vanilla duration has elapsed, stop rewriting
     * LIVING_ENTITY_FLAGS so a later SET_ENTITY_DATA cannot restart chewing after the
     * client already released the item.
     */
    public static boolean javaUsingVisible(final boolean usingItem, final boolean consumable, final int usingTicks) {
        return usingItem && (!consumable || usingTicks < DEFAULT_CONSUMABLE_USE_TICKS);
    }

    /**
     * Nukkit START_SPRINTING always calls {@code setUsingItem(false)}. Java starts sprinting
     * from movement even while the eating animation is held, so NetEase must drop that
     * command until the consumable finishes.
     */
    public static boolean suppressStartSprintingWhileUsingItem(final boolean emulateNetEase, final boolean usingItem) {
        return emulateNetEase && usingItem;
    }

    static ItemReleaseInventoryTransaction_ActionType releaseAction(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final int usingTicks) {
        return releaseAction(identifier, itemTags, itemUse, usingTicks, false);
    }

    static ItemReleaseInventoryTransaction_ActionType releaseAction(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final int usingTicks, final boolean emulateNetEase) {
        if (identifier == null || RELEASE_ON_RELEASE_ITEMS.contains(identifier) || isSpear(identifier, itemTags)) {
            return ItemReleaseInventoryTransaction_ActionType.Release;
        }

        final int useDurationTicks = consumableUseTicks(identifier, itemTags, itemUse);
        return useDurationTicks > 0 && usingTicks >= finishReadyTicks(useDurationTicks, emulateNetEase)
                ? ItemReleaseInventoryTransaction_ActionType.Use
                : ItemReleaseInventoryTransaction_ActionType.Release;
    }

    private static int consumableUseTicks(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse) {
        if (CONSUME_ON_RELEASE_ITEMS.contains(identifier) || isFood(itemTags)) {
            return DEFAULT_CONSUMABLE_USE_TICKS;
        }
        return itemUse != null ? itemUse.useDurationTicks() : -1;
    }

    /**
     * CLIENT_TICK_END inspects {@code usingItemTicks} before {@code Entity.age} advances.
     * Nukkit counts {@code serverTick - startAction} after the same game tick, so duration
     * {@code N} is ready at {@code N - 1} Java ticks.
     */
    private static int finishReadyTicks(final int useDurationTicks, final boolean emulateNetEase) {
        if (!emulateNetEase) {
            return useDurationTicks;
        }
        // CLIENT_TICK_END inspects usingItemTicks before Entity.age advances. Official 975
        // still finishes at the configured duration; NetEase 860 needs the packet one tick
        // earlier so Nukkit's serverTick - startAction reaches eatingTick (apple = 31).
        return Math.max(1, useDurationTicks - 1);
    }

    private static boolean isFood(final Set<String> itemTags) {
        return itemTags != null && itemTags.contains(FOOD_ITEM_TAG);
    }

    private ItemUseSemantics() {
    }

}
