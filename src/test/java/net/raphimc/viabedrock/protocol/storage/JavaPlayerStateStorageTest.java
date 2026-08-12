/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPlayerStateStorageTest {

    @Test
    void emitsEachCrawlingEdgeOnlyOnce() {
        final JavaPlayerStateStorage storage = new JavaPlayerStateStorage();

        assertTrue(storage.updateFromPayload(payload(JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertEquals(PlayerAuthInputPacket_InputData.StartCrawling, storage.consumeCrawlingTransition());
        assertNull(storage.consumeCrawlingTransition());
        assertTrue(storage.updateFromPayload(payload(JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertNull(storage.consumeCrawlingTransition());

        assertTrue(storage.updateFromPayload(payload(0)));
        assertEquals(PlayerAuthInputPacket_InputData.StopCrawling, storage.consumeCrawlingTransition());
        assertNull(storage.consumeCrawlingTransition());
    }

    @Test
    void storesUnknownFlagsButOnlyConsumesCrawling() {
        final JavaPlayerStateStorage storage = new JavaPlayerStateStorage();
        final int unknownFlag = 1 << 8;

        assertTrue(storage.updateFromPayload(payload(unknownFlag)));
        assertEquals(unknownFlag, storage.stateFlags());
        assertTrue(storage.hasState(unknownFlag));
        assertNull(storage.consumeCrawlingTransition());

        assertTrue(storage.updateFromPayload(payload(unknownFlag | JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertEquals(PlayerAuthInputPacket_InputData.StartCrawling, storage.consumeCrawlingTransition());
    }

    @Test
    void rejectsMalformedPayloadWithoutChangingState() {
        final JavaPlayerStateStorage storage = new JavaPlayerStateStorage();

        assertFalse(storage.updateFromPayload(new byte[0]));
        assertFalse(storage.updateFromPayload(new byte[]{JavaPlayerStateStorage.PROTOCOL_VERSION}));
        assertFalse(storage.updateFromPayload(new byte[]{2, 0}));
        assertFalse(storage.updateFromPayload(new byte[]{1, 0, 0}));
        assertFalse(storage.updateFromPayload(new byte[]{1, (byte) 0x80, 0}));
        assertFalse(storage.updateFromPayload(new byte[]{1, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}));
        assertFalse(storage.updateFromPayload(new byte[]{1, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, 0x0F}));
        assertNull(storage.consumeCrawlingTransition());

        assertTrue(storage.updateFromPayload(payload(JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertFalse(storage.updateFromPayload(new byte[]{1, 0, 1}));
        assertEquals(PlayerAuthInputPacket_InputData.StartCrawling, storage.consumeCrawlingTransition());
    }

    @Test
    void coalescesUnforwardedStateChanges() {
        final JavaPlayerStateStorage storage = new JavaPlayerStateStorage();

        assertTrue(storage.updateFromPayload(payload(JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertTrue(storage.updateFromPayload(payload(0)));
        assertNull(storage.consumeCrawlingTransition());
    }

    @Test
    void resetsForwardedStateForRespawnResynchronization() {
        final JavaPlayerStateStorage storage = new JavaPlayerStateStorage();

        assertTrue(storage.updateFromPayload(payload(JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertEquals(PlayerAuthInputPacket_InputData.StartCrawling, storage.consumeCrawlingTransition());

        storage.reset();
        assertTrue(storage.updateFromPayload(payload(JavaPlayerStateStorage.FLAG_CRAWLING)));
        assertEquals(PlayerAuthInputPacket_InputData.StartCrawling, storage.consumeCrawlingTransition());

        storage.reset();
        assertTrue(storage.updateFromPayload(payload(0)));
        assertEquals(PlayerAuthInputPacket_InputData.StopCrawling, storage.consumeCrawlingTransition());
    }

    private static byte[] payload(final int stateFlags) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(JavaPlayerStateStorage.PROTOCOL_VERSION);
            Types.VAR_INT.writePrimitive(buffer, stateFlags);
            final byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return payload;
        } finally {
            buffer.release();
        }
    }
}
