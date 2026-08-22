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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAuthInventoryModuleTest {

    @Test
    void simulationFailureTriggersAuthoritativeRollbackPath() {
        final RuntimeException failure = new IllegalStateException("broken item hash");
        final AtomicReference<RuntimeException> handled = new AtomicReference<>();

        final Object result = ClientAuthInventoryModule.runOrRollback(() -> {
            throw failure;
        }, handled::set);

        assertNull(result);
        assertSame(failure, handled.get());
    }

    @Test
    void successfulSimulationPassesThroughUntouched() {
        final AtomicReference<RuntimeException> handled = new AtomicReference<>();

        assertEquals("actions", ClientAuthInventoryModule.runOrRollback(() -> "actions", handled::set));
        assertNull(handled.get());
    }

    @Test
    void unsupportedSimulationPreservesAuthoritativeRollbackSignal() {
        final AtomicReference<RuntimeException> handled = new AtomicReference<>();

        assertNull(ClientAuthInventoryModule.runOrRollback(() -> null, handled::set));
        assertNull(handled.get());
    }

    @Test
    void playerInventoryMutationsOpenBedrockInventoryOnlyUntilAcknowledged() {
        assertTrue(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), false));
        assertFalse(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), true));
        assertFalse(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(4, false));
    }
}
