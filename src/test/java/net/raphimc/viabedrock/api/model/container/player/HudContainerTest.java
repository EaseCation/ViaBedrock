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
package net.raphimc.viabedrock.api.model.container.player;

import net.raphimc.viabedrock.test.StubUserConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudContainerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void netease51SlotContentIsPaddedToOfficialHudSize() {
        final HudContainer hud = new HudContainer(this.user);
        final BedrockItem[] items = BedrockItem.emptyArray(51);
        items[0] = new BedrockItem(1);
        items[50] = new BedrockItem(2);

        assertTrue(hud.setItems(items));
        assertEquals(54, hud.size());
        assertEquals(1, hud.getItem(0).identifier());
        assertEquals(2, hud.getItem(50).identifier());
        assertTrue(hud.getItem(51).isEmpty());
        assertTrue(hud.getItem(53).isEmpty());
    }

    @Test
    void official54SlotHudContentStaysExact() {
        final HudContainer hud = new HudContainer(this.user);
        final BedrockItem[] items = BedrockItem.emptyArray(54);
        items[53] = new BedrockItem(9);

        assertTrue(hud.setItems(items));
        assertEquals(9, hud.getItem(53).identifier());
    }
}
