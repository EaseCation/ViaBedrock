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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.HashedStructuredItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.container.AnvilContainer;
import net.raphimc.viabedrock.api.model.container.BrewingStandContainer;
import net.raphimc.viabedrock.api.model.container.FurnaceContainer;
import net.raphimc.viabedrock.api.model.container.SmithingTableContainer;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickSimulatorQuickMoveTest {

    private static final int BEDROCK_ITEM_ID = 2;
    private static final int JAVA_ITEM_ID = 100;
    private static final int MAX_STACK_SIZE = 64;
    private static final BlockPosition POSITION = new BlockPosition(0, 64, 0);
    private static final Function<BedrockItem, Item> JAVA_ITEMS = item -> item.isEmpty()
            ? StructuredItem.empty()
            : new StructuredItem(JAVA_ITEM_ID, item.amount(),
            new StructuredDataContainer(new StructuredData<?>[0]));

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private final InventoryTracker tracker = new InventoryTracker(this.user);
    private final ClientAuthInventoryModule.DragState dragState = new ClientAuthInventoryModule.DragState(this.user);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void furnaceResultQuickMoveUsesPredictedPlayerDestinationAndEncodes() {
        final FurnaceContainer furnace = new FurnaceContainer(
                this.user, (byte) 1, ContainerType.FURNACE, null, POSITION);
        furnace.setItemSilent(2, item(3, 11));
        this.tracker.setCurrentContainer(furnace);

        final List<InventoryActionData> actions = this.quickMove(
                1, (short) 2, moved((short) 2, (short) 38, 3));

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertEquals(1, actions.get(0).source().containerId());
        assertEquals(2, actions.get(0).slot());
        assertTrue(actions.get(0).toItem().isEmpty());
        assertEquals(ContainerID.CONTAINER_ID_INVENTORY.getValue(), actions.get(1).source().containerId());
        assertEquals(8, actions.get(1).slot());
        assertEquals(3, actions.get(1).toItem().amount());
        assertFalse(ItemStackRequestEncoder.encode(actions, this.tracker, true, 860).unsupported());
    }

    @Test
    void brewingPotionQuickMoveUsesRemappedOutputSlotAndEncodes() {
        final BrewingStandContainer brewing = new BrewingStandContainer(this.user, (byte) 1, null, POSITION);
        brewing.setItemSilent(1, item(1, 12));
        this.tracker.setCurrentContainer(brewing);

        final List<InventoryActionData> actions = this.quickMove(
                1, (short) 0, moved((short) 0, (short) 40, 1));

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertEquals(1, actions.get(0).slot());
        assertTrue(actions.get(0).toItem().isEmpty());
        assertEquals(ContainerID.CONTAINER_ID_INVENTORY.getValue(), actions.get(1).source().containerId());
        assertEquals(8, actions.get(1).slot());
        assertFalse(ItemStackRequestEncoder.encode(actions, this.tracker, true, 860).unsupported());
    }

    @Test
    void brewingPotionSlotsRemainValidInsertionDestinations() {
        final BrewingStandContainer brewing = new BrewingStandContainer(this.user, (byte) 1, null, POSITION);
        this.tracker.setCurrentContainer(brewing);
        this.tracker.getInventoryContainer().setItemSilent(9, item(1, 18));

        for (int javaSlot = 0; javaSlot < 3; javaSlot++) {
            assertFalse(ClickSimulator.isResultOnlyContainerSlot(brewing, javaSlot));
        }

        final List<InventoryActionData> actions = this.quickMove(
                1, (short) 5, moved((short) 5, (short) 0, 1));

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertEquals(ContainerID.CONTAINER_ID_INVENTORY.getValue(), actions.get(0).source().containerId());
        assertEquals(9, actions.get(0).slot());
        assertEquals(1, actions.get(1).slot());
        assertFalse(ItemStackRequestEncoder.encode(actions, this.tracker, true, 860).unsupported());
    }

    @Test
    void canonicalPreviewSlotsAreRejectedWithoutRejectingMachineInputs() {
        final FurnaceContainer furnace = new FurnaceContainer(
                this.user, (byte) 1, ContainerType.FURNACE, null, POSITION);
        final AnvilContainer anvil = new AnvilContainer(this.user, (byte) 2, null, POSITION);
        final SmithingTableContainer smithing = new SmithingTableContainer(this.user, (byte) 3, null, POSITION);

        assertTrue(ClickSimulator.isResultOnlyContainerSlot(furnace, 2));
        assertFalse(ClickSimulator.isResultOnlyContainerSlot(furnace, 0));
        assertTrue(ClickSimulator.isResultOnlyContainerSlot(anvil, 2));
        assertTrue(ClickSimulator.isResultOnlyContainerSlot(smithing, 3));
    }

    @Test
    void resultOnlySlotCannotBePredictedAsQuickMoveDestination() {
        final FurnaceContainer furnace = new FurnaceContainer(
                this.user, (byte) 1, ContainerType.FURNACE, null, POSITION);
        this.tracker.setCurrentContainer(furnace);
        this.tracker.getInventoryContainer().setItemSilent(9, item(4, 13));

        assertNull(this.quickMove(1, (short) 3, moved((short) 3, (short) 2, 4)));
        assertTrue(furnace.getItem(2).isEmpty());
    }

    @Test
    void openMenuQuickMoveDoesNotRouteBetweenPlayerInventorySections() {
        final FurnaceContainer furnace = new FurnaceContainer(
                this.user, (byte) 1, ContainerType.FURNACE, null, POSITION);
        this.tracker.setCurrentContainer(furnace);
        this.tracker.getInventoryContainer().setItemSilent(9, item(4, 19));

        assertNull(this.quickMove(1, (short) 3, moved((short) 3, (short) 30, 4)));
    }

    @Test
    void resultPreviewPredictionIsNeverEmittedAsAnInsertionAction() {
        final FurnaceContainer furnace = new FurnaceContainer(
                this.user, (byte) 1, ContainerType.FURNACE, null, POSITION);
        this.tracker.setCurrentContainer(furnace);
        this.tracker.getInventoryContainer().setItemSilent(9, item(4, 14));

        final Map<Short, HashedItem> changedSlots = moved((short) 3, (short) 0, 4);
        changedSlots.put((short) 2, new HashedStructuredItem(JAVA_ITEM_ID + 1, 1));
        final List<InventoryActionData> actions = this.quickMove(1, (short) 3, changedSlots);

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertTrue(actions.stream().noneMatch(action -> action.source().containerId() == 1 && action.slot() == 2));
        assertTrue(furnace.getItem(2).isEmpty());
    }

    @Test
    void anvilResultQuickMoveRollsBackInsteadOfSendingPlainTransfer() {
        final AnvilContainer anvil = new AnvilContainer(this.user, (byte) 1, null, POSITION);
        anvil.setItemSilent(2, item(1, 15));
        this.tracker.setCurrentContainer(anvil);

        assertNull(this.quickMove(1, (short) 2, moved((short) 2, (short) 38, 1)));
    }

    @Test
    void smithingResultQuickMoveRollsBackInsteadOfSendingPlainTransfer() {
        final SmithingTableContainer smithing = new SmithingTableContainer(this.user, (byte) 1, null, POSITION);
        smithing.setItemSilent(3, item(1, 16));
        this.tracker.setCurrentContainer(smithing);

        assertNull(this.quickMove(1, (short) 3, moved((short) 3, (short) 39, 1)));
    }

    @Test
    void creativeMiddleDragStopsBeforeEncoderCanCreatePhantomSwap() {
        final BedrockItem fullStack = item(MAX_STACK_SIZE, 17);
        this.tracker.getHudContainer().setItemSilent(0, fullStack.copy());
        this.tracker.getInventoryContainer().setItemSilent(9, fullStack.copy());
        final HashedItem carried = new HashedStructuredItem(JAVA_ITEM_ID, MAX_STACK_SIZE);

        assertTrue(this.quickCraft((short) -999, (byte) 8, carried).isEmpty());
        assertTrue(this.quickCraft((short) 9, (byte) 9, carried).isEmpty());
        final List<InventoryActionData> actions = this.quickCraft((short) -999, (byte) 10, carried);

        assertNull(actions);
        assertEquals(-1, this.dragState.getDragMode());
        assertTrue(this.dragState.getDragSlots().isEmpty());
        final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encode(
                actions, this.tracker, true, 860);
        assertTrue(encoded.isEmpty());
        assertEquals(0, this.tracker.pendingItemStackRequestCount());
    }

    private List<InventoryActionData> quickMove(final int containerId, final short slot,
                                                final Map<Short, HashedItem> changedSlots) {
        return ClickSimulator.simulate(
                containerId,
                slot,
                (byte) 0,
                ContainerInput.QUICK_MOVE,
                this.tracker,
                this.dragState,
                changedSlots,
                HashedStructuredItem.empty(),
                ignored -> MAX_STACK_SIZE,
                JAVA_ITEMS
        );
    }

    private List<InventoryActionData> quickCraft(final short slot, final byte button, final HashedItem carried) {
        return ClickSimulator.simulate(
                0,
                slot,
                button,
                ContainerInput.QUICK_CRAFT,
                this.tracker,
                this.dragState,
                Map.of(),
                carried,
                ignored -> MAX_STACK_SIZE,
                JAVA_ITEMS
        );
    }

    private static Map<Short, HashedItem> moved(final short source, final short target, final int amount) {
        final Map<Short, HashedItem> changedSlots = new LinkedHashMap<>();
        changedSlots.put(source, HashedStructuredItem.empty());
        changedSlots.put(target, new HashedStructuredItem(JAVA_ITEM_ID, amount));
        return changedSlots;
    }

    private static BedrockItem item(final int amount, final int stackNetworkId) {
        return new BedrockItem(BEDROCK_ITEM_ID, (short) 0, (byte) amount, null,
                new String[0], new String[0], 0, 0, stackNetworkId);
    }
}
