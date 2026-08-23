/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.util;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.BuildPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaClientDeviceTest {

    @Test
    void roundTripsWindowsHandshakeSuffix() {
        final JavaClientDevice original = JavaClientDevice.fromSystemProperties("Windows 11", "amd64", "10.0");
        final String handshake = JavaClientDevice.appendToHandshake("127.0.0.1", original);
        final JavaClientDevice parsed = JavaClientDevice.parseFromHandshake(handshake);

        assertFalse(handshake.startsWith("\0"));
        assertEquals("127.0.0.1", JavaClientDevice.stripHandshakeSuffix(handshake));
        assertEquals(original.model(), parsed.model());
        assertEquals(BuildPlatform.UWP.getValue(), parsed.deviceOs());
        assertEquals("windows", parsed.osName());
    }

    @Test
    void mapsMacAndLinuxToBedrockPlatforms() {
        assertEquals(BuildPlatform.OSX.getValue(), JavaClientDevice.fromOsAndModel("Mac OS X", "MacBook Pro").deviceOs());
        assertEquals("macos", JavaClientDevice.fromOsAndModel("Mac OS X", "MacBook Pro").osName());
        assertEquals(BuildPlatform.Linux.getValue(), JavaClientDevice.fromOsAndModel("Linux", "Linux 6.8 (amd64)").deviceOs());
        assertEquals("linux", JavaClientDevice.fromOsAndModel("Linux", "Linux 6.8 (amd64)").osName());
    }

    @Test
    void missingSuffixFallsBackToJavaEditionLabel() {
        final JavaClientDevice device = JavaClientDevice.parseFromHandshake("play.example.com");
        assertEquals(JavaClientDevice.JAVA_EDITION.model(), device.model());
        assertEquals(BuildPlatform.UWP.getValue(), device.deviceOs());
        assertFalse(device.model().contains("MS-7E51"));
    }

}
