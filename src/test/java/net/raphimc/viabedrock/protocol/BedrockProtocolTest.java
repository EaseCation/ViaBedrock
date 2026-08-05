/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol;

import com.viaversion.viaversion.api.protocol.packet.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockProtocolTest {

    @Test
    void resourcePackInfoDoesNotSynthesizeLoginSuccessAfterTheRealLoginSuccess() {
        assertTrue(BedrockProtocol.shouldSynthesizeLoginSuccess(
                State.LOGIN, ClientboundBedrockPackets.RESOURCE_PACKS_INFO));
        assertFalse(BedrockProtocol.shouldSynthesizeLoginSuccess(
                State.CONFIGURATION, ClientboundBedrockPackets.RESOURCE_PACKS_INFO));
    }

}
