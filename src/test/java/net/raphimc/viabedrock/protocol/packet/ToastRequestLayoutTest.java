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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToastRequestLayoutTest {

    @Test
    void motToastRoundTripsTitleAndContent() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ToastRequestLayout.write(buffer, "任务完成", "获得 10 金币");
            final ToastRequestLayout.Packet packet = ToastRequestLayout.read(buffer);
            assertEquals("任务完成", packet.title());
            assertEquals("获得 10 金币", packet.content());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void actionBarJoinsTitleAndContentAndFallsBackToSpace() {
        assertEquals("任务完成 获得 10 金币", ToastRequestLayout.actionBarText("任务完成", "获得 10 金币"));
        assertEquals("任务完成", ToastRequestLayout.actionBarText("任务完成", " "));
        assertEquals("获得 10 金币", ToastRequestLayout.actionBarText("", "获得 10 金币"));
        assertEquals(" ", ToastRequestLayout.actionBarText("", ""));
        assertEquals(" ", ToastRequestLayout.actionBarText(null, null));
    }
}
