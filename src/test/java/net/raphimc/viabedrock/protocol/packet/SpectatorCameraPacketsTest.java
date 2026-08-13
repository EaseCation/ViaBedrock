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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectatorCameraPacketsTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_TARGET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void parsesVersionedSessionMessages() {
        assertEquals(
                new SpectatorCameraPackets.Message(
                        SpectatorCameraPackets.Action.BEGIN_SESSION,
                        SESSION_ID,
                        1L,
                        -1L,
                        List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Target")),
                        List.of(new SpectatorCameraPackets.Team(
                                "bedwars_red", "Red Team", 12, List.of(TARGET_ID, SECOND_TARGET_ID)))
                ),
                SpectatorCameraPackets.parse("{\"version\":2,\"action\":\"begin_session\","
                        + "\"session\":\"" + SESSION_ID + "\",\"generation\":1,"
                        + "\"targets\":[{\"uuid\":\"" + TARGET_ID + "\",\"name\":\"Target\"}],"
                        + "\"teams\":[{\"key\":\"bedwars_red\",\"name\":\"Red Team\",\"color\":12,"
                        + "\"members\":[\"" + TARGET_ID + "\",\"" + SECOND_TARGET_ID + "\"]}]}")
        );
        assertEquals(
                new SpectatorCameraPackets.Message(
                        SpectatorCameraPackets.Action.ATTACH,
                        SESSION_ID,
                        2L,
                        123L,
                        List.of(),
                        List.of()
                ),
                SpectatorCameraPackets.parse("{\"version\":2,\"action\":\"attach\","
                        + "\"session\":\"" + SESSION_ID + "\",\"generation\":2,\"targetRuntimeId\":123}")
        );
        assertEquals(
                new SpectatorCameraPackets.Message(
                        SpectatorCameraPackets.Action.END_SESSION,
                        SESSION_ID,
                        -1L,
                        -1L,
                        List.of(),
                        List.of()
                ),
                SpectatorCameraPackets.parse("{\"version\":2,\"action\":\"end_session\","
                        + "\"session\":\"" + SESSION_ID + "\"}")
        );
    }

    @Test
    void parsesLegacyMessagesOnlyOnLegacyCodec() {
        assertEquals(
                new SpectatorCameraPackets.LegacyMessage(SpectatorCameraPackets.LegacyAction.ATTACH, 123L),
                SpectatorCameraPackets.parseLegacy("{\"action\":\"attach\",\"targetRuntimeId\":123}")
        );
        assertEquals(
                new SpectatorCameraPackets.LegacyMessage(SpectatorCameraPackets.LegacyAction.DETACH, -1L),
                SpectatorCameraPackets.parseLegacy("{\"action\":\"detach\"}")
        );
        assertThrows(IllegalArgumentException.class,
                () -> SpectatorCameraPackets.parse("{\"action\":\"detach\"}"));
    }

    @Test
    void treatsMissingTeamsAsEmptyDuringRollingUpgrade() {
        assertEquals(
                List.of(),
                SpectatorCameraPackets.parse("{\"version\":2,\"action\":\"begin_session\","
                        + "\"session\":\"" + SESSION_ID + "\",\"generation\":1,\"targets\":[]}").teams()
        );
    }

    @Test
    void rejectsMalformedOrUnsupportedMessages() {
        assertThrows(RuntimeException.class, () -> SpectatorCameraPackets.parse("not-json"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{\"version\":1}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("{\"version\":2,\"action\":\"unknown\"}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse(
                "{\"version\":2,\"action\":\"begin_session\",\"session\":\"" + SESSION_ID
                        + "\",\"generation\":1,\"targets\":[],\"teams\":[{\"key\":\"bad\","
                        + "\"name\":\"Bad\",\"color\":16,\"members\":[]}]}"
        ));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse(
                "{\"version\":2,\"action\":\"replace_targets\",\"session\":\"" + SESSION_ID
                        + "\",\"generation\":1,\"targets\":[],\"teams\":[{\"key\":\"duplicate\","
                        + "\"name\":\"A\",\"color\":1,\"members\":[]},{\"key\":\"duplicate\","
                        + "\"name\":\"B\",\"color\":2,\"members\":[]}]}"
        ));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parseLegacy("{\"action\":\"attach\"}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parseLegacy("{\"action\":\"attach\",\"targetRuntimeId\":0}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parseLegacy("{\"action\":\"attach\",\"targetRuntimeId\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> SpectatorCameraPackets.parse("x".repeat(SpectatorCameraPackets.MAX_PAYLOAD_LENGTH + 1)));
    }

    @Test
    void encodesVersionedAndLegacyRequests() {
        final JsonObject ready = GsonUtil.getGson()
                .fromJson(SpectatorCameraPackets.encodeSessionReady(SESSION_ID, 1L), JsonObject.class);
        assertEquals("session_ready", ready.get("action").getAsString());
        assertEquals(SESSION_ID.toString(), ready.get("session").getAsString());
        assertEquals(1L, ready.get("generation").getAsLong());

        final JsonObject detach = GsonUtil.getGson()
                .fromJson(SpectatorCameraPackets.encodeDetachRequest(SESSION_ID, 2L, "sneak"), JsonObject.class);
        assertEquals("detach_request", detach.get("action").getAsString());
        assertEquals("sneak", detach.get("reason").getAsString());

        final JsonObject legacy = GsonUtil.getGson()
                .fromJson(SpectatorCameraPackets.encodeLegacyDetachRequest("sneak"), JsonObject.class);
        assertEquals(2, legacy.size());
        assertEquals("detach_request", legacy.get("action").getAsString());
    }
}
