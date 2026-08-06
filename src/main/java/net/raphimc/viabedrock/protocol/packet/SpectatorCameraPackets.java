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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.storage.SpectatorCameraTracker;

import java.util.Locale;
import java.util.logging.Level;

public final class SpectatorCameraPackets {

    public static final String MESSAGE_ID = "easecation:spectator_camera_v1";
    static final int MAX_PAYLOAD_LENGTH = 512;

    private SpectatorCameraPackets() {
    }

    static void handle(final PacketWrapper wrapper, final String payload) {
        if (wrapper.user().getProtocolInfo().getServerState() != State.PLAY) return;

        final Message message;
        try {
            message = parse(payload);
        } catch (RuntimeException e) {
            final SpectatorCameraTracker tracker = wrapper.user().get(SpectatorCameraTracker.class);
            if (tracker.markInvalidPayloadLogged()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Ignoring invalid spectator camera message");
            }
            return;
        }

        final SpectatorCameraTracker tracker = wrapper.user().get(SpectatorCameraTracker.class);
        switch (message.action()) {
            case ATTACH -> tracker.attach(message.targetRuntimeId());
            case DETACH -> tracker.detach();
            case DETACH_REQUEST -> {
                // This action is only valid in the proxy-to-server direction.
            }
        }
    }

    static Message parse(final String payload) {
        if (payload == null || payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Spectator camera payload is too large");
        }

        final JsonObject object = GsonUtil.getGson().fromJson(payload, JsonObject.class);
        if (object == null) {
            throw new IllegalArgumentException("Spectator camera payload must be an object");
        }

        final JsonElement actionElement = object.get("action");
        if (!(actionElement instanceof JsonPrimitive actionPrimitive) || !actionPrimitive.isString()) {
            throw new IllegalArgumentException("Spectator camera action is missing");
        }

        final Action action;
        try {
            action = Action.valueOf(actionPrimitive.getAsString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown spectator camera action", e);
        }

        long targetRuntimeId = -1L;
        if (action == Action.ATTACH) {
            final JsonElement targetElement = object.get("targetRuntimeId");
            if (!(targetElement instanceof JsonPrimitive targetPrimitive) || !targetPrimitive.isNumber()) {
                throw new IllegalArgumentException("Spectator camera target is missing");
            }
            try {
                targetRuntimeId = Long.parseLong(targetPrimitive.getAsString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid spectator camera target", e);
            }
            if (targetRuntimeId <= 0L) {
                throw new IllegalArgumentException("Invalid spectator camera target");
            }
        }

        return new Message(action, targetRuntimeId);
    }

    public static String encodeDetachRequest(final String reason) {
        final JsonObject object = new JsonObject();
        object.addProperty("action", "detach_request");
        object.addProperty("reason", reason);
        return GsonUtil.getGson().toJson(object);
    }

    enum Action {
        ATTACH,
        DETACH,
        DETACH_REQUEST
    }

    record Message(Action action, long targetRuntimeId) {
    }

}
