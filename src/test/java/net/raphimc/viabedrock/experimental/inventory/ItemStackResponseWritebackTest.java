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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.packet.ItemStackResponseLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackResponseWritebackTest {

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
    void successResponseStampsNewDestNetIdAfterSplit() {
        this.tracker.getInventoryContainer().setItemSilent(0, item(264, 7, 32));
        this.tracker.getHudContainer().setItemSilent(0, item(264, 7, 32));

        ClientAuthInventoryModule.applyStackResponse(this.tracker, ok(
                container(ContainerEnumName.HotbarContainer, 0, 32, 7),
                container(ContainerEnumName.CursorContainer, 0, 32, 99)
        ));

        assertEquals(7, this.tracker.getInventoryContainer().getItem(0).netId());
        assertEquals(32, this.tracker.getInventoryContainer().getItem(0).amount());
        assertEquals(99, this.tracker.getHudContainer().getItem(0).netId());
        assertEquals(32, this.tracker.getHudContainer().getItem(0).amount());
    }

    @Test
    void successResponseEmptiesSourceWhenCountIsZero() {
        this.tracker.getInventoryContainer().setItemSilent(9, item(1, 7, 1));
        ClientAuthInventoryModule.applyStackResponse(this.tracker, ok(
                container(ContainerEnumName.InventoryContainer, 9, 0, 0)
        ));
        assertTrue(this.tracker.getInventoryContainer().getItem(9).isEmpty());
    }

    @Test
    void emptyOkListLeavesPredictedNetIds() {
        this.tracker.getInventoryContainer().setItemSilent(0, item(264, 7, 64));
        ClientAuthInventoryModule.applyStackResponse(this.tracker, ok());
        assertEquals(7, this.tracker.getInventoryContainer().getItem(0).netId());
        assertEquals(64, this.tracker.getInventoryContainer().getItem(0).amount());
    }

    @Test
    void openChestLevelEntitySlotIsUpdated() {
        final ChestContainer chest = new ChestContainer(this.user, (byte) 7, null, new BlockPosition(0, 0, 0), 27);
        chest.setItemSilent(5, item(1, 3, 8));
        this.tracker.setCurrentContainer(chest);

        ClientAuthInventoryModule.applyStackResponse(this.tracker, ok(
                container(ContainerEnumName.LevelEntityContainer, 5, 8, 44)
        ));

        assertEquals(44, this.tracker.getCurrentContainer().getItem(5).netId());
        assertEquals(8, this.tracker.getCurrentContainer().getItem(5).amount());
    }

    private static ItemStackResponseLayout.DecodedResponse ok(final ItemStackResponseLayout.DecodedContainer... containers) {
        final List<ItemStackResponseLayout.DecodedContainer> list = new ArrayList<>(List.of(containers));
        final ItemStackResponseLayout.DecodedEntry entry = new ItemStackResponseLayout.DecodedEntry(-1, true, list);
        return new ItemStackResponseLayout.DecodedResponse(1, false, new int[]{-1}, List.of(entry));
    }

    private static ItemStackResponseLayout.DecodedContainer container(final ContainerEnumName name, final int networkSlot,
                                                                     final int count, final int netId) {
        return new ItemStackResponseLayout.DecodedContainer(
                new FullContainerName(name, null),
                List.of(new ItemStackResponseLayout.DecodedSlot(networkSlot, networkSlot, count, netId))
        );
    }

    private static BedrockItem item(final int id, final int netId, final int count) {
        final BedrockItem item = new BedrockItem(id, (short) 0, (byte) count);
        item.setNetId(netId);
        return item;
    }
}
