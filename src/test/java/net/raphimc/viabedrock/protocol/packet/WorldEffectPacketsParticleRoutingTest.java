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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldEffectPacketsParticleRoutingTest {

    @Test
    void javaMappingsTakePriorityOverVbuCapabilities() {
        assertEquals(WorldEffectPackets.ParticleRoute.JAVA,
                WorldEffectPackets.selectParticleRoute(true, true, true));
        assertEquals(WorldEffectPackets.ParticleRoute.JAVA,
                WorldEffectPackets.selectParticleRoute(true, false, false));
    }

    @Test
    void unmappedParticlesUseTheBestAvailableVbuTransport() {
        assertEquals(WorldEffectPackets.ParticleRoute.VBU_V2,
                WorldEffectPackets.selectParticleRoute(false, true, true));
        assertEquals(WorldEffectPackets.ParticleRoute.VBU_LEGACY,
                WorldEffectPackets.selectParticleRoute(false, false, true));
        assertEquals(WorldEffectPackets.ParticleRoute.DROP,
                WorldEffectPackets.selectParticleRoute(false, false, false));
    }

    @Test
    void onlyTheProtocolSentinelSelectsAWorldAnchor() {
        assertTrue(WorldEffectPackets.isWorldParticle(-1L));
        assertFalse(WorldEffectPackets.isWorldParticle(0L));
        assertFalse(WorldEffectPackets.isWorldParticle(42L));
    }
}
