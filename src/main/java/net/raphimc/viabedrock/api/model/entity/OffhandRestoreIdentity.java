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

final class OffhandRestoreIdentity {

    private BedrockItem expectedPromotedMainHand;
    private BedrockItem expectedPromotedOffhand;
    private BedrockItem expectedMainHand;
    private BedrockItem expectedOffhand;

    void capturePromotedHands(final BedrockItem promotedMainHand, final BedrockItem promotedOffhand) {
        this.expectedPromotedMainHand = copy(promotedMainHand);
        this.expectedPromotedOffhand = copy(promotedOffhand);
        this.expectedMainHand = copy(promotedOffhand);
        this.expectedOffhand = copy(promotedMainHand);
    }

    boolean matchesPromotedHands(final BedrockItem mainHand, final BedrockItem offhand) {
        return sameStackIdentity(this.expectedPromotedMainHand, mainHand)
                && sameStackIdentity(this.expectedPromotedOffhand, offhand);
    }

    boolean matchesRestoredHands(final BedrockItem mainHand, final BedrockItem offhand) {
        return sameStackIdentity(this.expectedMainHand, mainHand)
                && sameStackIdentity(this.expectedOffhand, offhand);
    }

    void clear() {
        this.expectedPromotedMainHand = null;
        this.expectedPromotedOffhand = null;
        this.expectedMainHand = null;
        this.expectedOffhand = null;
    }

    static boolean sameStackIdentity(final BedrockItem expected, final BedrockItem actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.isEmpty() && actual.isEmpty()) {
            return true;
        }
        return expected.amount() == actual.amount() && !expected.isDifferent(actual);
    }

    private static BedrockItem copy(final BedrockItem item) {
        return item != null ? item.copy() : null;
    }

}
