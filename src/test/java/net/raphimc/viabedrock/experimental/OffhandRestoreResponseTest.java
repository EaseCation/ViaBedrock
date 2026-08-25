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
package net.raphimc.viabedrock.experimental;

import net.raphimc.viabedrock.protocol.packet.ItemStackResponseLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OffhandRestoreResponseTest {

    @Test
    void resolvesExactEntryStatusByRestoreRequestId() {
        final ItemStackResponseLayout.DecodedResponse accepted = new ItemStackResponseLayout.DecodedResponse(
                1, false, new int[]{37}, List.of(new ItemStackResponseLayout.DecodedEntry(37, true)));
        final ItemStackResponseLayout.DecodedResponse rejected = new ItemStackResponseLayout.DecodedResponse(
                1, true, new int[]{37}, List.of(new ItemStackResponseLayout.DecodedEntry(37, false)));

        assertEquals(Boolean.TRUE, ExperimentalFeatures.restoreResponseAccepted(accepted, 37));
        assertEquals(Boolean.FALSE, ExperimentalFeatures.restoreResponseAccepted(rejected, 37));
        assertNull(ExperimentalFeatures.restoreResponseAccepted(accepted, 38));
    }

    @Test
    void usesSinglePendingFallbackOnlyForSingleEntryResponse() {
        final ItemStackResponseLayout.DecodedResponse accepted = new ItemStackResponseLayout.DecodedResponse(1, false);
        final ItemStackResponseLayout.DecodedResponse rejected = new ItemStackResponseLayout.DecodedResponse(1, true);
        final ItemStackResponseLayout.DecodedResponse ambiguous = new ItemStackResponseLayout.DecodedResponse(2, false);

        assertEquals(Boolean.TRUE, ExperimentalFeatures.restoreResponseAccepted(accepted, 37));
        assertEquals(Boolean.FALSE, ExperimentalFeatures.restoreResponseAccepted(rejected, 37));
        assertNull(ExperimentalFeatures.restoreResponseAccepted(ambiguous, 37));
    }

}
