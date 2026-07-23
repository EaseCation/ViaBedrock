/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.camera;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CameraPreset_AudioListener;
import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraAudioTrackerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final CameraAudioTracker tracker = new CameraAudioTracker(new UserConnectionImpl(this.channel));

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void inheritsCameraListenerAndPositionFromParentPreset() {
        this.tracker.setPresets(
                new String[]{"base", "child"}, new String[]{"", "base"},
                new Float[]{10F, null}, new Float[]{20F, null}, new Float[]{30F, null},
                new Byte[]{(byte) CameraPreset_AudioListener.Camera.getValue(), null});
        this.tracker.applyInstruction(true, 1, null, false);

        final Position3f player = new Position3f(1F, 2F, 3F);
        assertEquals(new Position3f(10F, 20F, 30F), this.tracker.bedrockListenerPosition(player));
        assertEquals(new Position3f(10F, 20F, 30F), this.tracker.javaListenerPosition(player, true));
        assertEquals(player, this.tracker.javaListenerPosition(player, false));
    }

    @Test
    void keepsBedrockListenerAtPlayerWhenCameraPresetRequestsPlayerAudio() {
        this.tracker.setPresets(
                new String[]{"player-listener"}, new String[]{""},
                new Float[]{10F}, new Float[]{20F}, new Float[]{30F},
                new Byte[]{(byte) CameraPreset_AudioListener.Player.getValue()});
        this.tracker.applyInstruction(true, 0, null, false);

        final Position3f player = new Position3f(1F, 2F, 3F);
        assertEquals(player, this.tracker.bedrockListenerPosition(player));
        assertEquals(new Position3f(10F, 20F, 30F), this.tracker.javaListenerPosition(player, true));

        this.tracker.applyInstruction(false, 0, null, true);
        assertEquals(player, this.tracker.javaListenerPosition(player, true));
    }

}
