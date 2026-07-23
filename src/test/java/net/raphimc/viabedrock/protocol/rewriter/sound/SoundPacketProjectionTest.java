/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.sound;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;
import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundPacketProjectionTest {

    private static final Position3f ORIGIN = Position3f.ZERO;

    @Test
    void filtersTheSameSourcePerConnectionWithoutMovingNormalWorldAudio() {
        final SoundDefinitions.SoundDefinition splash = definition(0F, null,
                new SoundDefinitions.SoundFile("sounds/random/splash", 1F, 1F, 1, true));
        final Position3f source = new Position3f(3F, 0F, 0F);

        final SoundPacketProjection.Result near = project(splash, source, 0.25F,
                new Position3f(1F, 0F, 0F), new Position3f(1F, 0F, 0F));
        final SoundPacketProjection.Result far = project(splash, source, 0.25F,
                new Position3f(8F, 0F, 0F), new Position3f(8F, 0F, 0F));

        assertTrue(near.send());
        assertEquals(source, near.position());
        assertFalse(far.send());
    }

    @Test
    void placesNonSpatialAudioAtTheRealJavaListener() {
        final Position3f listener = new Position3f(12F, 70F, -4F);
        final SoundPacketProjection.Result result = SoundPacketProjection.project(
                definition(0F, null, new SoundDefinitions.SoundFile("ui", 1F, 1F, 1, true)),
                ProtocolVersion.v1_21_7, "minecraft:ui.button.click", new Position3f(100F, 20F, 50F),
                1F, 1F, true, false, 11L, listener, listener);

        assertTrue(result.send());
        assertEquals(listener, result.position());
        assertNotEquals(Integer.MAX_VALUE, result.position().x());
    }

    @Test
    void translatesTheSourceBetweenBedrockAndJavaAudioListeners() {
        final SoundDefinitions.SoundDefinition sound = definition(0F, 64F,
                new SoundDefinitions.SoundFile("sound", 1F, 1F, 1, true));
        final Position3f bedrockPlayerListener = ORIGIN;
        final Position3f javaCameraListener = new Position3f(10F, 0F, 0F);

        final SoundPacketProjection.Result playerAudioAtJavaCamera = project(sound,
                new Position3f(3F, 0F, 0F), 4F, bedrockPlayerListener, javaCameraListener);
        final SoundPacketProjection.Result cameraAudioAtJavaPlayer = project(sound,
                new Position3f(13F, 0F, 0F), 4F, javaCameraListener, bedrockPlayerListener);

        assertEquals(new Position3f(13F, 0F, 0F), playerAudioAtJavaCamera.position());
        assertEquals(new Position3f(3F, 0F, 0F), cameraAudioAtJavaPlayer.position());
    }

    @Test
    void usesThePacketSeedForBothSidesOfGeneratedCustomVariants() {
        final SoundDefinitions.SoundDefinition custom = definition(0F, null,
                new SoundDefinitions.SoundFile("quiet", 0.2F, 0.8F, 1, true),
                new SoundDefinitions.SoundFile("loud", 0.9F, 1.2F, 3, true));

        for (long seed = 0; seed < 32; seed++) {
            final SoundPacketProjection.Result result = SoundPacketProjection.project(
                    custom, ProtocolVersion.v1_21_7, "bedrock:test", ORIGIN,
                    1F, 1F, false, true, seed, ORIGIN, ORIGIN);
            assertTrue(result.send());
            assertEquals(1F, result.volume(), 0.0001F);
            assertEquals(1F, result.pitch(), 0.0001F);
        }
    }

    @Test
    void doesNotForwardNonFiniteAcousticsBeforeTheListenerExists() {
        final SoundPacketProjection.Result result = SoundPacketProjection.project(
                null, ProtocolVersion.v1_21_7, "minecraft:test", ORIGIN,
                Float.NaN, Float.POSITIVE_INFINITY, false, false, 19L, null, null);

        assertEquals(0F, result.volume());
        assertEquals(1F, result.pitch());
    }

    private static SoundPacketProjection.Result project(
            final SoundDefinitions.SoundDefinition definition, final Position3f source, final float volume,
            final Position3f bedrockListener, final Position3f javaListener) {
        return SoundPacketProjection.project(definition, ProtocolVersion.v1_21_7,
                "minecraft:entity.generic.splash", source, volume, 1F,
                false, false, 7L, bedrockListener, javaListener);
    }

    private static SoundDefinitions.SoundDefinition definition(
            final float minDistance, final Float maxDistance, final SoundDefinitions.SoundFile... files) {
        return new SoundDefinitions.SoundDefinition("test", "player", minDistance, maxDistance,
                List.of(files));
    }

}
