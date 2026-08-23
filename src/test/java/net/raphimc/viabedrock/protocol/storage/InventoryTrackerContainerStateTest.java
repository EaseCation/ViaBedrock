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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.test.StubUserConnection;
import com.viaversion.viaversion.libs.mcstructs.text.components.StringComponent;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.raphimc.viabedrock.protocol.storage.InventoryTracker.ContainerState.CLOSED;
import static net.raphimc.viabedrock.protocol.storage.InventoryTracker.ContainerState.CLOSE_PENDING;
import static net.raphimc.viabedrock.protocol.storage.InventoryTracker.ContainerState.OPEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTrackerContainerStateTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private final RecordingClosePacketSink packets = new RecordingClosePacketSink();
    private final RecordingClosePreparation closePreparation = new RecordingClosePreparation();
    private InventoryTracker tracker;
    private Container container;

    @BeforeEach
    void setUp() {
        this.tracker = new InventoryTracker(this.user, this.packets, this.closePreparation);
        this.container = new ChestContainer(this.user, (byte) 7, new StringComponent("Chest"), null, 27);
    }

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void openThenServerCloseAcknowledgesOnceAndCloses() {
        this.openContainer();
        this.putItemOnCursor();

        assertSame(this.container, this.tracker.acceptServerClose((byte) 7, ContainerType.CONTAINER));
        assertEquals(CLOSED, this.tracker.getContainerState());
        assertTrue(this.tracker.getHudContainer().getItem(0).isEmpty());
        assertEquals(List.of(new BedrockClose((byte) 7, ContainerType.NONE)), this.packets.bedrockCloses);
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(1, this.closePreparation.calls);

        assertNull(this.tracker.acceptServerClose((byte) 7, ContainerType.CONTAINER));
        assertEquals(1, this.packets.bedrockCloses.size());
    }

    @Test
    void clientCloseTransitionsThroughPendingUntilConfirmation() {
        this.openContainer();

        assertTrue(this.tracker.beginClientClose(this.container));
        assertEquals(CLOSE_PENDING, this.tracker.getContainerState());
        assertSame(this.container, this.tracker.acceptClientCloseConfirmation((byte) 7));

        assertEquals(CLOSED, this.tracker.getContainerState());
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(List.of(), this.packets.bedrockCloses);
    }

    @Test
    void pendingClientCloseThenDimensionChangeOnlyClearsState() {
        this.openContainer();
        assertTrue(this.tracker.beginClientClose(this.container));

        this.tracker.closeForDimensionChange();

        assertEquals(CLOSED, this.tracker.getContainerState());
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(List.of(), this.packets.bedrockCloses);
        assertEquals(0, this.closePreparation.calls);
    }

    @Test
    void directDimensionChangeClosesBedrockContainerOnce() {
        this.openContainer();
        this.putItemOnCursor();

        this.tracker.closeForDimensionChange();
        this.tracker.closeForDimensionChange();

        assertEquals(CLOSED, this.tracker.getContainerState());
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(List.of(new BedrockClose((byte) 7, ContainerType.NONE)), this.packets.bedrockCloses);
        assertEquals(1, this.closePreparation.calls);
    }

    @Test
    void repeatedCloseEventsWhileClosedAreNoOps() {
        assertNull(this.tracker.acceptServerClose((byte) 7, ContainerType.CONTAINER));
        assertNull(this.tracker.acceptClientCloseConfirmation((byte) 7));
        assertNull(this.tracker.completePendingCloseWithoutConfirmation());
        assertFalse(this.tracker.forceCloseCurrentContainer());
        this.tracker.closeForDimensionChange();

        assertEquals(CLOSED, this.tracker.getContainerState());
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(List.of(), this.packets.bedrockCloses);
        assertEquals(0, this.closePreparation.calls);
    }

    @Test
    void forcedCloseSendsEachPacketOnceAndConsumesLateConfirmation() {
        this.openContainer();
        this.putItemOnCursor();

        assertTrue(this.tracker.forceCloseCurrentContainer());
        assertEquals(CLOSE_PENDING, this.tracker.getContainerState());
        assertEquals(List.of(7), this.packets.javaCloses);
        assertEquals(List.of(new BedrockClose((byte) 7, ContainerType.NONE)), this.packets.bedrockCloses);
        assertEquals(1, this.closePreparation.calls);

        assertSame(this.container, this.tracker.acceptClientCloseConfirmation((byte) 7));
        assertNull(this.tracker.acceptClientCloseConfirmation((byte) 7));
        assertEquals(CLOSED, this.tracker.getContainerState());
        assertEquals(1, this.packets.javaCloses.size());
        assertEquals(1, this.packets.bedrockCloses.size());
    }

    @Test
    void forcedCloseThenDimensionChangeMakesLateConfirmationANoOp() {
        this.openContainer();
        assertTrue(this.tracker.forceCloseCurrentContainer());

        this.tracker.closeForDimensionChange();
        assertNull(this.tracker.acceptClientCloseConfirmation((byte) 7));

        assertEquals(CLOSED, this.tracker.getContainerState());
        assertEquals(List.of(7), this.packets.javaCloses);
        assertEquals(List.of(new BedrockClose((byte) 7, ContainerType.NONE)), this.packets.bedrockCloses);
    }

    @Test
    void clientAuthoritativeCompletionClearsPendingWithoutPackets() {
        this.openContainer();
        this.putItemOnCursor();
        assertTrue(this.tracker.beginClientClose(this.container));

        assertSame(this.container, this.tracker.completePendingCloseWithoutConfirmation());
        assertNull(this.tracker.completePendingCloseWithoutConfirmation());

        assertEquals(CLOSED, this.tracker.getContainerState());
        assertTrue(this.tracker.getHudContainer().getItem(0).isEmpty());
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(List.of(), this.packets.bedrockCloses);
    }

    @Test
    void mismatchedCloseIdOrTypeDoesNotAdvanceState() {
        this.openContainer();
        this.putItemOnCursor();

        assertNull(this.tracker.acceptServerClose((byte) 8, ContainerType.CONTAINER));
        assertNull(this.tracker.acceptServerClose((byte) 7, ContainerType.WORKBENCH));

        assertEquals(OPEN, this.tracker.getContainerState());
        assertSame(this.container, this.tracker.getCurrentContainer());
        assertFalse(this.tracker.getHudContainer().getItem(0).isEmpty());
        assertEquals(List.of(), this.packets.bedrockCloses);
    }

    @Test
    void closingUntrackedPlayerInventoryClearsCursor() {
        this.putItemOnCursor();

        assertTrue(this.tracker.clearCursorIfContainerClosed());

        assertTrue(this.tracker.getHudContainer().getItem(0).isEmpty());
        assertEquals(CLOSED, this.tracker.getContainerState());
    }

    @Test
    void untrackedCloseCannotClearCursorWhileContainerIsOpen() {
        this.openContainer();
        this.putItemOnCursor();

        assertFalse(this.tracker.clearCursorIfContainerClosed());

        assertFalse(this.tracker.getHudContainer().getItem(0).isEmpty());
        assertEquals(OPEN, this.tracker.getContainerState());
    }

    @Test
    void bedrockInventoryOpenDoesNotOccupyCurrentContainer() {
        this.tracker.acknowledgeBedrockInventoryOpen((byte) 0, new com.viaversion.viaversion.api.minecraft.BlockPosition(10, 64, 10));

        assertTrue(this.tracker.isBedrockPlayerInventoryOpen());
        assertEquals((byte) 0, this.tracker.bedrockInventoryContainerId());
        assertNull(this.tracker.getCurrentContainer());
        assertEquals(CLOSED, this.tracker.getContainerState());

        this.openContainer();
        assertSame(this.container, this.tracker.getCurrentContainer());
        assertFalse(this.tracker.isBedrockPlayerInventoryOpen());
    }

    @Test
    void serverCloseClearsUntrackedBedrockInventoryOpen() {
        this.tracker.acknowledgeBedrockInventoryOpen((byte) 0, new com.viaversion.viaversion.api.minecraft.BlockPosition(1, 64, 1));

        assertNull(this.tracker.acceptServerClose((byte) 0, ContainerType.INVENTORY));
        assertFalse(this.tracker.isBedrockPlayerInventoryOpen());
        assertEquals(CLOSED, this.tracker.getContainerState());
    }

    @Test
    void transientHotbarDropClosesBedrockPlayerInventoryAndIgnoresLateOpen() {
        this.tracker.closeTransientBedrockPlayerInventory();

        assertFalse(this.tracker.isBedrockPlayerInventoryOpen());
        assertTrue(this.tracker.isSuppressingNextBedrockPlayerInventoryOpen());
        assertEquals(List.of(new BedrockClose((byte) -1, ContainerType.NONE)), this.packets.bedrockCloses);
        assertEquals(List.of(), this.packets.javaCloses);
        assertEquals(CLOSED, this.tracker.getContainerState());

        this.tracker.acknowledgeBedrockInventoryOpen((byte) 0, new com.viaversion.viaversion.api.minecraft.BlockPosition(0, 64, 0));
        assertFalse(this.tracker.isBedrockPlayerInventoryOpen());
        assertFalse(this.tracker.isSuppressingNextBedrockPlayerInventoryOpen());
        assertNull(this.tracker.getCurrentContainer());
    }

    @Test
    void transientCloseDoesNotTouchAnOpenChest() {
        this.openContainer();
        this.tracker.closeTransientBedrockPlayerInventory();

        assertSame(this.container, this.tracker.getCurrentContainer());
        assertEquals(OPEN, this.tracker.getContainerState());
        assertEquals(List.of(), this.packets.bedrockCloses);
        assertFalse(this.tracker.isSuppressingNextBedrockPlayerInventoryOpen());
    }

    private void openContainer() {
        this.tracker.setCurrentContainer(this.container);
        assertEquals(OPEN, this.tracker.getContainerState());
    }

    private void putItemOnCursor() {
        this.tracker.getHudContainer().setItemSilent(0, new BedrockItem(1));
        assertFalse(this.tracker.getHudContainer().getItem(0).isEmpty());
    }

    private record BedrockClose(byte containerId, ContainerType type) {
    }

    private static final class RecordingClosePacketSink implements InventoryTracker.ContainerClosePacketSink {

        private final List<Integer> javaCloses = new ArrayList<>();
        private final List<BedrockClose> bedrockCloses = new ArrayList<>();

        @Override
        public void sendJavaClose(final UserConnection user, final int containerId) {
            this.javaCloses.add(containerId);
        }

        @Override
        public void sendBedrockClose(final UserConnection user, final byte containerId, final ContainerType containerType) {
            this.bedrockCloses.add(new BedrockClose(containerId, containerType));
        }
    }

    private final class RecordingClosePreparation implements InventoryTracker.ContainerClosePreparation {

        private int calls;

        @Override
        public boolean returnCursor(final UserConnection user) {
            this.calls++;
            InventoryTrackerContainerStateTest.this.tracker.getHudContainer().setItemSilent(0, BedrockItem.empty());
            return true;
        }
    }

}
