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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FakeBlockEntityConversionTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void itemFramePickResolvesToTrackedBlock() {
        final EntityTracker entityTracker = new EntityTracker(this.user);
        this.user.put(entityTracker);
        final BlockPosition position = new BlockPosition(8, 64, 12);
        entityTracker.putItemFrame(position, 7, 3);

        assertEquals(position, ExperimentalFeatures.resolveFakeBlockEntityPickPosition(this.user, 7));
        assertNull(ExperimentalFeatures.resolveFakeBlockEntityPickPosition(this.user, 8));
    }
}
