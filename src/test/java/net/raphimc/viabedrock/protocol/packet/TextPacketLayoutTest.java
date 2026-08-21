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
package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextPacketType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextPacketLayoutTest {

    private static final String WELCOME = "\u00a7AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void netease860UsesTypeFirstLayout() {
        assertTrue(TextPacketLayout.isLegacyTypeFirstLayout(true, 860));
        assertTrue(TextPacketLayout.usesRequiredFilteredString(true, 860));
        assertFalse(TextPacketLayout.isLegacyTypeFirstLayout(false, 860));
        assertFalse(TextPacketLayout.isLegacyTypeFirstLayout(true, 924));
        assertFalse(TextPacketLayout.usesRequiredFilteredString(true, 924));
    }

    @Test
    void parsesLegacyNetease860RawMessage() {
        final ByteBuf buffer = encodeLegacyRaw(WELCOME);
        try {
            final TextPacketLayout.DecodedText decoded = TextPacketLayout.readPacket(buffer, true, 860);
            assertEquals(TextPacketType.raw, decoded.type());
            assertFalse(decoded.localize());
            assertEquals(WELCOME, decoded.message());
            assertEquals("", decoded.sourceName());
            assertEquals("", decoded.xuid());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingLegacyNetease860RawAs924LayoutHitsUnknownType() {
        final ByteBuf buffer = encodeLegacyRaw(WELCOME);
        try {
            final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> TextPacketLayout.readPacket(buffer, false, 975));
            assertTrue(thrown.getMessage().contains("Unknown TextPacketType: 57"), thrown.getMessage());
        } finally {
            buffer.release();
        }
    }

    @Test
    void parsesOfficial924RawMessage() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            TextPacketLayout.writeHeader(buffer, false, TextPacketType.raw, false);
            BedrockTypes.STRING.write(buffer, WELCOME);
            TextPacketLayout.writeTrailer(buffer, TextPacketType.raw, "", false, 975);
            final TextPacketLayout.DecodedText decoded = TextPacketLayout.readPacket(buffer, false, 975);
            assertEquals(TextPacketType.raw, decoded.type());
            assertEquals(WELCOME, decoded.message());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void parsesLegacyChatWithSourceAndUnknownNeTail() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            TextPacketLayout.writeHeader(buffer, false, TextPacketType.chat, true);
            BedrockTypes.STRING.write(buffer, "Steve");
            BedrockTypes.STRING.write(buffer, "hello");
            TextPacketLayout.writeTrailer(buffer, TextPacketType.chat, "123", true, 860);
            final TextPacketLayout.DecodedText decoded = TextPacketLayout.readPacket(buffer, true, 860);
            assertEquals(TextPacketType.chat, decoded.type());
            assertEquals("Steve", decoded.sourceName());
            assertEquals("hello", decoded.message());
            assertEquals("123", decoded.xuid());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf encodeLegacyRaw(final String message) {
        final ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(TextPacketType.raw.getValue());
        buffer.writeBoolean(false);
        BedrockTypes.STRING.write(buffer, message);
        BedrockTypes.STRING.write(buffer, "");
        BedrockTypes.STRING.write(buffer, "");
        BedrockTypes.STRING.write(buffer, "");
        assertEquals(57, message.getBytes(StandardCharsets.UTF_8).length);
        return buffer;
    }
}
