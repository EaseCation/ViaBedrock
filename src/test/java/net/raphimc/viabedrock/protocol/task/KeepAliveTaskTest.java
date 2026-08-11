/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.task;

import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeepAliveTaskTest {

    @Test
    void sendsConfigurationKeepAliveOnlyAfterBothDirectionsAndPlatformRegistryAgree() {
        assertNull(KeepAliveTask.selectPacketType(State.LOGIN, State.CONFIGURATION, false));
        assertNull(KeepAliveTask.selectPacketType(State.LOGIN, State.CONFIGURATION, true));
        assertNull(KeepAliveTask.selectPacketType(State.CONFIGURATION, State.CONFIGURATION, false));
        assertEquals(ClientboundConfigurationPackets1_21_9.KEEP_ALIVE,
                KeepAliveTask.selectPacketType(State.CONFIGURATION, State.CONFIGURATION, true));
    }

    @Test
    void doesNotLeakPlayKeepAliveAcrossConfigurationFinishOrReconfiguration() {
        assertNull(KeepAliveTask.selectPacketType(State.CONFIGURATION, State.PLAY, false));
        assertNull(KeepAliveTask.selectPacketType(State.CONFIGURATION, State.PLAY, true));
        assertNull(KeepAliveTask.selectPacketType(State.PLAY, State.CONFIGURATION, false));
        assertNull(KeepAliveTask.selectPacketType(State.PLAY, State.CONFIGURATION, true));
        assertEquals(ClientboundPackets26_1.KEEP_ALIVE,
                KeepAliveTask.selectPacketType(State.PLAY, State.PLAY, true));
    }

    @Test
    void ignoresStatesWhereJavaKeepAliveDoesNotExist() {
        assertNull(KeepAliveTask.selectPacketType(State.LOGIN, State.LOGIN, true));
        assertNull(KeepAliveTask.selectPacketType(State.STATUS, State.STATUS, true));
    }
}
