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

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.BossEventUpdateType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudPacketsTest {

    @Test
    void dropsUpdatesAfterBossBarRemoval() {
        assertFalse(HudPackets.shouldForwardBossEvent(false, BossEventUpdateType.Update_Percent));
        assertFalse(HudPackets.shouldForwardBossEvent(false, BossEventUpdateType.Update_Name));
        assertFalse(HudPackets.shouldForwardBossEvent(false, BossEventUpdateType.Update_Properties));
        assertFalse(HudPackets.shouldForwardBossEvent(false, BossEventUpdateType.Update_Style));
        assertFalse(HudPackets.shouldForwardBossEvent(false, BossEventUpdateType.Remove));
    }

    @Test
    void forwardsUpdatesWhileBossBarExists() {
        assertTrue(HudPackets.shouldForwardBossEvent(true, BossEventUpdateType.Update_Percent));
        assertTrue(HudPackets.shouldForwardBossEvent(true, BossEventUpdateType.Update_Name));
        assertTrue(HudPackets.shouldForwardBossEvent(true, BossEventUpdateType.Update_Properties));
        assertTrue(HudPackets.shouldForwardBossEvent(true, BossEventUpdateType.Update_Style));
        assertTrue(HudPackets.shouldForwardBossEvent(true, BossEventUpdateType.Remove));
    }

    @Test
    void allowsNextPhaseToAddBossBarAgain() {
        boolean hasBossBar = false;
        assertTrue(HudPackets.shouldForwardBossEvent(hasBossBar, BossEventUpdateType.Add));

        hasBossBar = true;
        assertTrue(HudPackets.shouldForwardBossEvent(hasBossBar, BossEventUpdateType.Remove));

        hasBossBar = false;
        assertFalse(HudPackets.shouldForwardBossEvent(hasBossBar, BossEventUpdateType.Update_Percent));
        assertTrue(HudPackets.shouldForwardBossEvent(hasBossBar, BossEventUpdateType.Add));
    }

}
