/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectatorCameraPacketsTest {

    @Test
    void parsesAttachAndDetachMessages() {
        assertEquals(
                new SpectatorCameraPackets.Message(SpectatorCameraPackets.Action.ATTACH, 123L),
                SpectatorCameraPackets.parse("{\"action\":\"attach\",\"targetRuntimeId\":123}")
        );
        assertEquals(
                new SpectatorCameraPackets.Message(SpectatorCameraPackets.Action.DETACH, -1L),
                SpectatorCameraPackets.parse("{\"action\":\"detach\"}")
        );
    }

    @Test
    void rejectsMalformedAttachMessages() {
        assertThrows(RuntimeException.class, () -> SpectatorCameraPackets.parse("not-json"));
        assertThrows(RuntimeException.class, () -> SpectatorCameraPackets.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{\"action\":\"unknown\"}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{\"action\":\"attach\"}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{\"action\":\"attach\",\"targetRuntimeId\":0}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{\"action\":\"attach\",\"targetRuntimeId\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("x".repeat(SpectatorCameraPackets.MAX_PAYLOAD_LENGTH + 1)));
    }

    @Test
    void encodesDetachRequests() {
        final JsonObject message = GsonUtil.getGson()
                .fromJson(SpectatorCameraPackets.encodeDetachRequest("sneak"), JsonObject.class);

        assertEquals("detach_request", message.get("action").getAsString());
        assertEquals("sneak", message.get("reason").getAsString());
    }

}
