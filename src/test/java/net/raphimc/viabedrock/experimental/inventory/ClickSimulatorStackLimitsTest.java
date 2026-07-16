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

import com.viaversion.viaversion.api.minecraft.item.HashedStructuredItem;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickSimulatorStackLimitsTest {

    private static final int ITEM_ID = 2;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final InventoryTracker tracker = new InventoryTracker(this.user);
    private final ClientAuthInventoryModule.DragState dragState = new ClientAuthInventoryModule.DragState(this.user);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void fullArmorStacksDoNotMergeWithNormalClicks() {
        this.tracker.getInventoryContainer().setItemSilent(9, item(1));
        this.tracker.getHudContainer().setItemSilent(0, item(1));

        assertTrue(this.simulate((short) 9, (byte) 0, ContainerInput.PICKUP, 1).isEmpty());
        assertTrue(this.simulate((short) 9, (byte) 1, ContainerInput.PICKUP, 1).isEmpty());
        assertEquals(1, this.tracker.getInventoryContainer().getItem(9).amount());
        assertEquals(1, this.tracker.getHudContainer().getItem(0).amount());
    }

    @Test
    void normalMergeStopsAtSixteen() {
        this.tracker.getInventoryContainer().setItemSilent(9, item(15));
        this.tracker.getHudContainer().setItemSilent(0, item(2));

        final List<InventoryActionData> actions = this.simulate((short) 9, (byte) 0, ContainerInput.PICKUP, 16);

        assertNotNull(actions);
        assertEquals(16, actions.get(0).toItem().amount());
        assertEquals(1, actions.get(1).toItem().amount());
        assertTargetsWithin(actions, 16);
    }

    @Test
    void quickMoveSplitsAnExistingArmorStackWithoutCreatingAnotherStack() {
        this.tracker.getInventoryContainer().setItemSilent(9, item(2));

        final List<InventoryActionData> actions = ClickSimulator.simulateQuickMove(
                0, (short) 9, this.tracker, ignored -> 1);

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertEquals(1, actions.get(0).toItem().amount());
        assertEquals(1, actions.get(1).toItem().amount());
        assertTargetsWithin(actions, 1);
    }

    @Test
    void creativeCloneUsesTheResolvedItemLimit() {
        this.tracker.getInventoryContainer().setItemSilent(9, item(1));

        final List<InventoryActionData> actions = this.simulate((short) 9, (byte) 0, ContainerInput.CLONE, 1);

        assertNotNull(actions);
        assertEquals(2, actions.size());
        assertTargetsWithin(actions, 1);
    }

    @Test
    void allQuickCraftModesRespectArmorLimit() {
        assertTargetsWithin(this.quickCraft(0, List.of((short) 9), 1), 1);
        assertTargetsWithin(this.quickCraft(1, List.of((short) 9, (short) 10), 1), 1);
        assertTargetsWithin(this.quickCraft(2, List.of((short) 9, (short) 10), 1), 1);
    }

    @Test
    void pickupAllDoesNotOverfillArmorAndCapsSixteenStacks() {
        this.tracker.getHudContainer().setItemSilent(0, item(1));
        this.tracker.getInventoryContainer().setItemSilent(9, item(1));
        assertTrue(this.simulate((short) 9, (byte) 0, ContainerInput.PICKUP_ALL, 1).isEmpty());

        this.clearInventory();
        this.tracker.getHudContainer().setItemSilent(0, item(15));
        this.tracker.getInventoryContainer().setItemSilent(9, item(2));
        final List<InventoryActionData> actions = this.simulate((short) 9, (byte) 0, ContainerInput.PICKUP_ALL, 16);

        assertNotNull(actions);
        assertEquals(16, cursorAction(actions).toItem().amount());
        assertEquals(1, actions.get(0).toItem().amount());
        assertTargetsWithin(actions, 16);
    }

    @Test
    void unresolvedLimitRejectsPredictionForAuthoritativeRollback() {
        this.tracker.getHudContainer().setItemSilent(0, item(1));

        assertNull(this.simulate((short) 9, (byte) 0, ContainerInput.PICKUP, JavaItemStackLimits.UNSUPPORTED));
    }

    private List<InventoryActionData> quickCraft(final int mode, final List<Short> slots, final int limit) {
        this.clearInventory();
        this.tracker.getHudContainer().setItemSilent(0, item(1));
        assertTrue(this.simulate((short) -999, (byte) (mode << 2), ContainerInput.QUICK_CRAFT, limit).isEmpty());
        for (final short slot : slots) {
            assertTrue(this.simulate(slot, (byte) ((mode << 2) | 1), ContainerInput.QUICK_CRAFT, limit).isEmpty());
        }
        final List<InventoryActionData> actions = this.simulate(
                (short) -999, (byte) ((mode << 2) | 2), ContainerInput.QUICK_CRAFT, limit);
        assertNotNull(actions);
        return actions;
    }

    private List<InventoryActionData> simulate(final short slot, final byte button,
                                               final ContainerInput action, final int limit) {
        return ClickSimulator.simulate(
                0,
                slot,
                button,
                action,
                this.tracker,
                this.dragState,
                Map.of(),
                HashedStructuredItem.empty(),
                ignored -> limit
        );
    }

    private void clearInventory() {
        this.tracker.getInventoryContainer().clearItems();
        this.tracker.getHudContainer().clearItems();
    }

    private static InventoryActionData cursorAction(final List<InventoryActionData> actions) {
        return actions.stream()
                .filter(action -> action.source().containerId() == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue())
                .filter(action -> action.slot() == 0)
                .findFirst()
                .orElseThrow();
    }

    private static void assertTargetsWithin(final List<InventoryActionData> actions, final int limit) {
        assertTrue(actions.stream()
                .map(InventoryActionData::toItem)
                .filter(item -> !item.isEmpty())
                .allMatch(item -> item.amount() <= limit));
    }

    private static BedrockItem item(final int amount) {
        return new BedrockItem(ITEM_ID, (short) 0, (byte) amount);
    }

}
