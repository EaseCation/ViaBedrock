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
package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerEntityImmobileTest {

    @Test
    void rejectsOnlyChangedPositionsForSpawnedImmobilePlayers() {
        final Position3f currentPosition = new Position3f(1F, 2F, 3F);

        assertTrue(ClientPlayerEntity.shouldRejectImmobilePosition(true, true, currentPosition, new Position3f(2F, 2F, 3F)));
        assertFalse(ClientPlayerEntity.shouldRejectImmobilePosition(true, true, currentPosition, currentPosition));
        assertFalse(ClientPlayerEntity.shouldRejectImmobilePosition(true, false, currentPosition, new Position3f(2F, 2F, 3F)));
        assertFalse(ClientPlayerEntity.shouldRejectImmobilePosition(false, true, currentPosition, new Position3f(2F, 2F, 3F)));
        assertFalse(ClientPlayerEntity.shouldRejectImmobilePosition(true, true, currentPosition, null));
    }

}
