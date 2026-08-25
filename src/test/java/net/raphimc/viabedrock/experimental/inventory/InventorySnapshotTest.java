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

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.ItemStackResponseLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySnapshotTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private InventoryTracker tracker;

    @BeforeEach
    void setUp() {
        this.tracker = new InventoryTracker(this.user);
        this.user.put(this.tracker);
    }

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void restorePutsPredictedCursorBack() {
        this.tracker.getHudContainer().setItemSilent(0, new BedrockItem(264, (short) 0, (byte) 1));
        this.tracker.getInventoryContainer().setItemSilent(0, BedrockItem.empty());
        final InventorySnapshot snapshot = InventorySnapshot.capture(this.tracker);

        this.tracker.getHudContainer().setItemSilent(0, BedrockItem.empty());
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(264, (short) 0, (byte) 1));
        snapshot.restore(this.tracker);

        assertEquals(264, this.tracker.getHudContainer().getItem(0).identifier());
        assertTrue(this.tracker.getInventoryContainer().getItem(0).isEmpty());
    }

    @Test
    void dummyWorldOriginIsRecognized() {
        assertTrue(InventoryTracker.isDummyWorldPosition(new BlockPosition(0, 0, 0)));
        assertTrue(!InventoryTracker.isDummyWorldPosition(new BlockPosition(1, 64, 1)));
    }

    @Test
    void restoreAlsoRevertsOpenChest() {
        final ChestContainer chest = new ChestContainer(this.user, (byte) 7, null, new BlockPosition(0, 0, 0), 27);
        chest.setItemSilent(0, new BedrockItem(1, (short) 0, (byte) 8));
        this.tracker.setCurrentContainer(chest);
        final InventorySnapshot snapshot = InventorySnapshot.capture(this.tracker);

        chest.setItemSilent(0, BedrockItem.empty());
        snapshot.restore(this.tracker);
        assertEquals(1, this.tracker.getCurrentContainer().getItem(0).identifier());
        assertEquals(8, this.tracker.getCurrentContainer().getItem(0).amount());
    }

    @Test
    void rejectedResponseAfterAuthoritativeContentPreservesNewerItem() {
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(1, (short) 0, (byte) 1));
        final InventorySnapshot snapshot = InventorySnapshot.capture(this.tracker);
        this.tracker.rememberPendingItemStackRequest(-1, snapshot);

        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(2, (short) 0, (byte) 1));
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(3, (short) 0, (byte) 1));
        this.tracker.incrementAuthoritativeInventoryGeneration(this.tracker.getInventoryContainer());
        assertTrue(ClientAuthInventoryModule.restoreRejectedItemStackRequest(this.tracker, -1));

        assertEquals(3, this.tracker.getInventoryContainer().getItem(0).identifier());
        assertEquals(0, this.tracker.pendingItemStackRequestCount());
    }

    @Test
    void unrelatedAuthoritativeContainerUpdateDoesNotSuppressRestore() {
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(1, (short) 0, (byte) 1));
        this.tracker.getHudContainer().setItemSilent(0, new BedrockItem(4, (short) 0, (byte) 1));
        final InventorySnapshot snapshot = InventorySnapshot.capture(this.tracker);

        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(2, (short) 0, (byte) 1));
        this.tracker.getHudContainer().setItemSilent(0, new BedrockItem(5, (short) 0, (byte) 1));
        this.tracker.incrementAuthoritativeInventoryGeneration(this.tracker.getHudContainer());

        assertTrue(snapshot.restore(this.tracker));
        assertEquals(1, this.tracker.getInventoryContainer().getItem(0).identifier());
        assertEquals(5, this.tracker.getHudContainer().getItem(0).identifier());
    }

    @Test
    void rejectedResponseWithoutAuthoritativeUpdateRestoresSnapshot() {
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(1, (short) 0, (byte) 1));
        final InventorySnapshot snapshot = InventorySnapshot.capture(this.tracker);

        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(2, (short) 0, (byte) 1));
        this.tracker.rememberPendingItemStackRequest(-1, snapshot);

        assertTrue(ClientAuthInventoryModule.restoreRejectedItemStackRequest(this.tracker, -1));
        assertEquals(1, this.tracker.getInventoryContainer().getItem(0).identifier());
        assertEquals(0, this.tracker.pendingItemStackRequestCount());
    }

    @Test
    void reusedWindowIdsDoNotRestoreCapturedContentsIntoNewWindow() {
        final ChestContainer oldChest = new ChestContainer(this.user, (byte) 7, null, new BlockPosition(0, 0, 0), 27);
        oldChest.setItemSilent(0, new BedrockItem(1, (short) 0, (byte) 8));
        this.tracker.setCurrentContainer(oldChest);
        final InventorySnapshot snapshot = InventorySnapshot.capture(this.tracker);

        oldChest.setItemSilent(0, BedrockItem.empty());
        final ChestContainer newChest = new ChestContainer(this.user, (byte) 7, null, new BlockPosition(1, 0, 0), 27);
        newChest.setItemSilent(0, new BedrockItem(3, (short) 0, (byte) 4));
        this.tracker.replaceCurrentContainer(newChest);

        assertTrue(snapshot.restore(this.tracker));
        assertTrue(oldChest.getItem(0).isEmpty());
        assertEquals(3, newChest.getItem(0).identifier());
        assertEquals(4, newChest.getItem(0).amount());
    }

    @Test
    void authoritativeRequestsAreSerialized() {
        assertTrue(ClientAuthInventoryModule.canSendItemStackRequest(this.tracker));

        this.tracker.rememberPendingItemStackRequest(-1, InventorySnapshot.capture(this.tracker));
        assertFalse(ClientAuthInventoryModule.canSendItemStackRequest(this.tracker));

        this.tracker.takePendingItemStackRequest(-1);
        assertTrue(ClientAuthInventoryModule.canSendItemStackRequest(this.tracker));
    }

    private static ItemStackResponseLayout.DecodedResponse rejectedResponse(final int requestId) {
        final ItemStackResponseLayout.DecodedEntry entry =
                new ItemStackResponseLayout.DecodedEntry(requestId, false, List.of());
        return new ItemStackResponseLayout.DecodedResponse(1, true, new int[]{requestId}, List.of(entry));
    }

    @Test
    void pendingItemStackRequestsAreKeyedById() {
        final InventorySnapshot first = InventorySnapshot.capture(this.tracker);
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(264, (short) 0, (byte) 1));
        final InventorySnapshot second = InventorySnapshot.capture(this.tracker);

        this.tracker.rememberPendingItemStackRequest(-1, first);
        this.tracker.rememberPendingItemStackRequest(-3, second);
        assertEquals(2, this.tracker.pendingItemStackRequestCount());

        assertTrue(this.tracker.takePendingItemStackRequest(-1) != null);
        assertEquals(1, this.tracker.pendingItemStackRequestCount());
        assertNull(this.tracker.takePendingItemStackRequest(-1));
        assertTrue(this.tracker.takePendingItemStackRequest(-3) != null);
        assertEquals(0, this.tracker.pendingItemStackRequestCount());
    }
}
