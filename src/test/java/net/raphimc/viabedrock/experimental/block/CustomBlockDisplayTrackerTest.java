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
package net.raphimc.viabedrock.experimental.block;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomBlockDisplayTrackerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private final CustomBlockDisplayTracker tracker = new CustomBlockDisplayTracker(this.user);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void overlayIdRoundTripsToBlockPosition() {
        final BlockPosition position = new BlockPosition(12, 64, -8);
        this.tracker.putOverlay(position, 42);

        assertEquals(position, this.tracker.getOverlayPosition(42));
        assertEquals(42, this.tracker.getOverlayJavaId(position));
        assertNull(this.tracker.getOverlayPosition(7));
    }

    @Test
    void unknownOverlayIdIsIgnored() {
        assertNull(this.tracker.getOverlayPosition(1));
        assertEquals(-1, this.tracker.getOverlayJavaId(new BlockPosition(0, 64, 0)));
    }

    @Test
    void lookDirectionUsesJavaYawZeroAsSouth() {
        assertEquals(Direction.SOUTH, CustomBlockDisplayTracker.facingFromLook(new Position3f(0F, 0F, 0F)));
        assertEquals(Direction.WEST, CustomBlockDisplayTracker.facingFromLook(new Position3f(0F, 90F, 0F)));
        assertEquals(Direction.NORTH, CustomBlockDisplayTracker.facingFromLook(new Position3f(0F, 180F, 0F)));
        assertEquals(Direction.EAST, CustomBlockDisplayTracker.facingFromLook(new Position3f(0F, 270F, 0F)));
        assertEquals(Direction.UP, CustomBlockDisplayTracker.facingFromLook(new Position3f(-90F, 0F, 0F)));
        assertEquals(Direction.DOWN, CustomBlockDisplayTracker.facingFromLook(new Position3f(90F, 0F, 0F)));
        assertEquals(Direction.NORTH, CustomBlockDisplayTracker.facingFromLook(null));
    }
    @Test
    void placeholderJavaBlockStateUsesGlassWhenMappingsArePresent() {
        final int placeholder = this.tracker.placeholderJavaBlockState();
        if (placeholder == -1) {
            return;
        }
        assertEquals(placeholder, this.tracker.overlayJavaBlockState(-1, placeholder));
    }
}
