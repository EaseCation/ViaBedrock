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

import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffhandRestoreIdentityTest {

    @Test
    void distinguishesPromotedRollbackFromConfirmedRestoredMirror() {
        final OffhandRestoreIdentity identity = new OffhandRestoreIdentity();
        final BedrockItem promotedMain = item(20, 202, 2);
        final BedrockItem promotedOffhand = item(10, 101, 3);
        identity.capturePromotedHands(promotedMain, promotedOffhand);

        assertTrue(identity.matchesPromotedHands(promotedMain, promotedOffhand));
        assertFalse(identity.matchesRestoredHands(promotedMain, promotedOffhand));

        final BedrockItem restoredMain = item(10, 501, 3);
        final BedrockItem restoredOffhand = item(20, 502, 2);
        assertTrue(identity.matchesRestoredHands(restoredMain, restoredOffhand));
        assertFalse(identity.matchesPromotedHands(restoredMain, restoredOffhand));
    }

    @Test
    void requiresStackContentButIgnoresResponseAssignedNetworkIds() {
        final OffhandRestoreIdentity identity = new OffhandRestoreIdentity();
        identity.capturePromotedHands(item(20, 202, 2), item(10, 101, 3));

        assertTrue(identity.matchesRestoredHands(item(10, 901, 3), item(20, 902, 2)));
        assertFalse(identity.matchesRestoredHands(item(10, 901, 2), item(20, 902, 2)));
        assertFalse(identity.matchesRestoredHands(item(11, 901, 3), item(20, 902, 2)));
    }

    @Test
    void capturesCopiesBeforeTheOptimisticReverseSwapMutatesSlots() {
        final OffhandRestoreIdentity identity = new OffhandRestoreIdentity();
        final BedrockItem promotedMain = item(20, 202, 2);
        final BedrockItem promotedOffhand = item(10, 101, 3);
        identity.capturePromotedHands(promotedMain, promotedOffhand);

        promotedMain.setAmount(1);
        promotedOffhand.setAmount(1);

        assertTrue(identity.matchesRestoredHands(item(10, 301, 3), item(20, 302, 2)));
    }

    private static BedrockItem item(final int id, final int netId, final int amount) {
        final BedrockItem item = new BedrockItem(id, (short) 0, (byte) amount);
        item.setNetId(netId);
        return item;
    }

}
