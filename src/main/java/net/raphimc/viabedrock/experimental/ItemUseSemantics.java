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
            "minecraft:brush",
            "minecraft:spyglass",
            "minecraft:shield"
    );

    static boolean isContinuousUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final boolean chargedCrossbow) {
        if (identifier == null || ("minecraft:crossbow".equals(identifier) && chargedCrossbow)) {
            return false;
        }
        return RELEASE_ON_RELEASE_ITEMS.contains(identifier)
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
     */
    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow) {
        return bow || crossbow || (emulateNetEase && consumable);
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
     * the same ItemUseTransaction to AuthInput for food or bows.
     */
    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable,
                                                  final boolean bow) {
        if (!emulateNetEase) {
            return true;
        }
        return !consumable && !bow;
    }

    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable) {
        return attachAuthInputItemInteraction(emulateNetEase, consumable, false);
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
        if (identifier == null || RELEASE_ON_RELEASE_ITEMS.contains(identifier)) {
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
