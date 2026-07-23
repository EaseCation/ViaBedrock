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

import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundAcousticProjectorTest {

    private static final JavaSoundCatalog.Variant DEFAULT_JAVA =
            new JavaSoundCatalog.Variant(1F, 1F, 16);

    @Test
    void lowVolumeSoundUsesBedrockRangeInsteadOfJavaMinimumRange() {
        final SoundDefinitions.SoundDefinition splash = definition(0F, null,
                new SoundDefinitions.SoundFile("sounds/random/splash", 1F, 1F, 1, true));

        final SoundAcousticProjector.Projection inside = SoundAcousticProjector.project(
                splash, 0.25F, 1F, 2F, false, DEFAULT_JAVA, new Random(1));
        final SoundAcousticProjector.Projection outside = SoundAcousticProjector.project(
                splash, 0.25F, 1F, 5F, false, DEFAULT_JAVA, new Random(1));

        assertTrue(inside.send());
        assertEquals(4F, inside.audibleRange());
        assertEquals(0.0625F, inside.targetGain(), 0.0001F);
        assertFalse(outside.send());
    }

    @Test
    void compensatesForSelectedJavaFileVolumeWithoutSoundSpecificRules() {
        final SoundDefinitions.SoundDefinition attack = definition(0F, null,
                new SoundDefinitions.SoundFile("sounds/mob/player/attack/weak1", 0.2F, 1F, 1, true));
        final JavaSoundCatalog.Variant javaAttack = new JavaSoundCatalog.Variant(0.7F, 1F, 16);

        final SoundAcousticProjector.Projection projection = SoundAcousticProjector.project(
                attack, 1F, 1F, 0F, false, javaAttack, new Random(2));

        assertTrue(projection.send());
        assertEquals(0.2F, projection.targetGain(), 0.0001F);
        assertEquals(0.2F, SoundAcousticProjector.javaHeardGain(
                projection.volume(), 0F, javaAttack.volume(), javaAttack.attenuationDistance()), 0.0001F);
    }

    @Test
    void appliesMinAndMaxDistanceAsOneSharedAttenuationRule() {
        final SoundDefinitions.SoundDefinition definition = definition(4F, 20F,
                new SoundDefinitions.SoundFile("sound", 1F, 1F, 1, true));

        assertEquals(20F, SoundAcousticProjector.audibleRange(definition, 2F));
        assertEquals(1F, SoundAcousticProjector.bedrockAttenuation(definition, 4F, 20F));
        assertEquals(0.25F, SoundAcousticProjector.bedrockAttenuation(definition, 12F, 20F), 0.0001F);
        assertEquals(0F, SoundAcousticProjector.bedrockAttenuation(definition, 20F, 20F));
    }

    @Test
    void uiAndNon3dSoundsDoNotUseSpatialRange() {
        final SoundDefinitions.SoundDefinition ui = new SoundDefinitions.SoundDefinition(
                "ui.sound", "ui", 0F, null,
                List.of(new SoundDefinitions.SoundFile("sound", 0.5F, 1F, 1, true)));
        final SoundDefinitions.SoundDefinition stereo = definition(0F, null,
                new SoundDefinitions.SoundFile("sound", 0.5F, 1F, 1, false));

        assertTrue(SoundAcousticProjector.project(
                ui, 1F, 1F, 500F, false, DEFAULT_JAVA, new Random(3)).nonSpatial());
        assertTrue(SoundAcousticProjector.project(
                stereo, 1F, 1F, 500F, false, DEFAULT_JAVA, new Random(3)).nonSpatial());
    }

    @Test
    void doesNotCapVolumeNeededForFullGainInsideBedrockMinDistance() {
        final SoundDefinitions.SoundDefinition longRange = definition(100F, 160F,
                new SoundDefinitions.SoundFile("sound", 1F, 1F, 1, true));

        final SoundAcousticProjector.Projection projection = SoundAcousticProjector.project(
                longRange, 10F, 1F, 80F, false, DEFAULT_JAVA, new Random(4));

        assertTrue(projection.send());
        assertEquals(1F, projection.targetGain());
        assertTrue(projection.volume() > 64F);
        assertEquals(1F, SoundAcousticProjector.javaHeardGain(
                projection.volume(), 80F, 1F, 16), 0.0001F);
    }

    @Test
    void rejectsNonFinitePacketAcoustics() {
        final SoundDefinitions.SoundDefinition sound = definition(0F, null,
                new SoundDefinitions.SoundFile("sound", 1F, 1F, 1, true));

        assertFalse(SoundAcousticProjector.project(
                sound, Float.NaN, 1F, 0F, false, DEFAULT_JAVA, new Random(5)).send());
        final SoundAcousticProjector.Projection invalidPitch = SoundAcousticProjector.project(
                sound, 1F, Float.POSITIVE_INFINITY, 0F, false, DEFAULT_JAVA, new Random(5));
        assertTrue(invalidPitch.send());
        assertEquals(1F, invalidPitch.pitch());
    }

    private static SoundDefinitions.SoundDefinition definition(
            final float minDistance, final Float maxDistance, final SoundDefinitions.SoundFile... files) {
        return new SoundDefinitions.SoundDefinition("test", "player", minDistance, maxDistance,
                List.of(files));
    }

}
