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

import net.raphimc.viabedrock.api.model.entity.DeferredEntityActionQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityInteractionPacketSenderTest {

    @Test
    void rejectsRemovedTargetsAndRuntimeIdReuse() {
        final DeferredEntityActionQueue.Action action = new DeferredEntityActionQueue.Action(
                1, 0xFFFF_FFFFL, 37L, 1F, 2F, 3F, 5);

        assertTrue(EntityInteractionPacketSender.matchesTarget(action, 0xFFFF_FFFFL, 37L));
        assertFalse(EntityInteractionPacketSender.matchesTarget(action, 12L, 37L));
        assertFalse(EntityInteractionPacketSender.matchesTarget(action, 0xFFFF_FFFFL, 38L));
    }

}
