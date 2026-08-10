/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorReturnSimulatorTest {

    private static final int ITEM_ID = 2;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final InventoryTracker tracker = new InventoryTracker(this.user);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void usesTheFirstHotbarSlotWhenInventoryHasNoMatchingStack() {
        this.tracker.getHudContainer().setItemSilent(0, item(1));

        final List<InventoryActionData> actions = simulate(64);

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertEquals(0, actions.get(0).slot());
        assertEquals(1, actions.get(0).toItem().amount());
        assertTrue(actions.get(1).toItem().isEmpty());
        assertEquals(List.of(36), ClientAuthInventoryModule.changedJavaInventorySlots(actions, this.tracker));
    }

    @Test
    void skipsOccupiedHotbarSlotsFromLeftToRight() {
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(ITEM_ID + 1, (short) 0, (byte) 1));
        this.tracker.getInventoryContainer().setItemSilent(1, new BedrockItem(ITEM_ID + 1, (short) 0, (byte) 1));
        this.tracker.getHudContainer().setItemSilent(0, item(1));

        final List<InventoryActionData> actions = simulate(64);

        assertNotNull(actions);
        assertEquals(2, actions.get(0).slot());
        assertEquals(1, actions.get(0).toItem().amount());
        assertTrue(actions.get(1).toItem().isEmpty());
    }

    @Test
    void mergesExistingStacksBeforeUsingTheFirstEmptySlot() {
        this.tracker.getInventoryContainer().setItemSilent(9, item(63));
        this.tracker.getInventoryContainer().setItemSilent(10, item(60));
        this.tracker.getHudContainer().setItemSilent(0, item(6));

        final List<InventoryActionData> actions = simulate(64);

        assertNotNull(actions);
        assertEquals(64, actions.get(0).toItem().amount());
        assertEquals(64, actions.get(1).toItem().amount());
        assertEquals(1, actions.get(2).toItem().amount());
        assertTrue(actions.get(3).toItem().isEmpty());
        assertEquals(List.of(9, 10, 36), ClientAuthInventoryModule.changedJavaInventorySlots(actions, this.tracker));
    }

    @Test
    void dropsOnlyTheRemainderWhenPlayerInventoryIsFull() {
        for (int slot = 0; slot < 36; slot++) {
            this.tracker.getInventoryContainer().setItemSilent(slot, new BedrockItem(ITEM_ID + 1, (short) 0, (byte) 64));
        }
        this.tracker.getHudContainer().setItemSilent(0, item(3));

        final List<InventoryActionData> actions = simulate(64);

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertEquals(InventorySourceType.WorldInteraction, actions.get(0).source().type());
        assertEquals(3, actions.get(0).toItem().amount());
        assertTrue(actions.get(1).toItem().isEmpty());
        assertTrue(ClientAuthInventoryModule.changedJavaInventorySlots(actions, this.tracker).isEmpty());
    }

    @Test
    void rejectsUnknownStackLimitsWithoutClearingTheCursor() {
        this.tracker.getHudContainer().setItemSilent(0, item(1));

        assertNull(simulate(JavaItemStackLimits.UNSUPPORTED));
        assertFalse(this.tracker.getHudContainer().getItem(0).isEmpty());
    }

    @Test
    void clearsOnlyThePlayerCraftingGridAndOutputOnClose() {
        for (int slot = 28; slot <= 32; slot++) {
            this.tracker.getHudContainer().setItemSilent(slot, item(1));
        }
        this.tracker.getHudContainer().setItemSilent(50, item(1));

        ClientAuthInventoryModule.clearPlayerCraftingGrid(this.tracker);

        for (int slot = 28; slot <= 31; slot++) {
            assertTrue(this.tracker.getHudContainer().getItem(slot).isEmpty());
        }
        assertTrue(this.tracker.getHudContainer().getItem(50).isEmpty());
        assertFalse(this.tracker.getHudContainer().getItem(32).isEmpty());
    }

    private List<InventoryActionData> simulate(final int limit) {
        return ClickSimulator.simulateCursorReturn(this.tracker, ignored -> limit);
    }

    private static BedrockItem item(final int amount) {
        return new BedrockItem(ITEM_ID, (short) 0, (byte) amount);
    }
}
