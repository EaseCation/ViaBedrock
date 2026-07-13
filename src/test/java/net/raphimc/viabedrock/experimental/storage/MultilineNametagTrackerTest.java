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
package net.raphimc.viabedrock.experimental.storage;

import org.junit.jupiter.api.Test;

import static net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker.PlayerNametagRenderState.HIDDEN_NORMAL;
import static net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker.PlayerNametagRenderState.HIDDEN_SNEAKING;
import static net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker.PlayerNametagRenderState.VISIBLE_NORMAL;
import static net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker.PlayerNametagRenderState.VISIBLE_SNEAKING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MultilineNametagTrackerTest {

    @Test
    void keepsNormalNametagsVisibleAtAnyVanillaRenderDistance() {
        assertAll(
                () -> assertState(VISIBLE_NORMAL, false, false, false, 0D),
                () -> assertState(VISIBLE_NORMAL, false, false, false, 1024D),
                () -> assertState(VISIBLE_NORMAL, false, false, false, Double.POSITIVE_INFINITY)
        );
    }

    @Test
    void appliesTheExactSneakingDistanceBoundary() {
        assertAll(
                () -> assertState(VISIBLE_SNEAKING, true, false, false, Math.nextDown(1024D)),
                () -> assertState(VISIBLE_SNEAKING, true, false, false, 1024D),
                () -> assertState(HIDDEN_SNEAKING, true, false, false, Math.nextUp(1024D))
        );
    }

    @Test
    void hidesInvisiblePlayersFromNormalViewers() {
        assertAll(
                () -> assertState(HIDDEN_NORMAL, false, true, false, 0D),
                () -> assertState(HIDDEN_SNEAKING, true, true, false, 0D),
                () -> assertState(HIDDEN_SNEAKING, true, true, false, 2048D)
        );
    }

    @Test
    void letsSpectatorsSeeInvisiblePlayersWhilePreservingSneakingRules() {
        assertAll(
                () -> assertState(VISIBLE_NORMAL, false, true, true, 2048D),
                () -> assertState(VISIBLE_SNEAKING, true, true, true, 1024D),
                () -> assertState(HIDDEN_SNEAKING, true, true, true, Math.nextUp(1024D))
        );
    }

    @Test
    void restoresTheCurrentSneakingStateAfterInvisibilityEnds() {
        assertAll(
                () -> assertState(VISIBLE_SNEAKING, true, false, false, 900D),
                () -> assertState(HIDDEN_SNEAKING, true, false, false, 1100D)
        );
    }

    @Test
    void mapsStatesToTheExpectedArmorStandMetadata() {
        assertAll(
                () -> assertEquals((byte) 0x20, VISIBLE_NORMAL.sharedFlags),
                () -> assertEquals((byte) 0x22, VISIBLE_SNEAKING.sharedFlags),
                () -> assertEquals((byte) 0x20, HIDDEN_NORMAL.sharedFlags),
                () -> assertEquals((byte) 0x22, HIDDEN_SNEAKING.sharedFlags),
                () -> assertEquals(true, VISIBLE_NORMAL.nameVisible),
                () -> assertEquals(true, VISIBLE_SNEAKING.nameVisible),
                () -> assertEquals(false, HIDDEN_NORMAL.nameVisible),
                () -> assertEquals(false, HIDDEN_SNEAKING.nameVisible)
        );
    }

    private static void assertState(final MultilineNametagTracker.PlayerNametagRenderState expected,
                                    final boolean hostSneaking,
                                    final boolean hostInvisible,
                                    final boolean viewerSpectator,
                                    final double distanceSquared) {
        assertEquals(expected, MultilineNametagTracker.resolvePlayerRenderState(
                hostSneaking, hostInvisible, viewerSpectator, distanceSquared));
    }

}
