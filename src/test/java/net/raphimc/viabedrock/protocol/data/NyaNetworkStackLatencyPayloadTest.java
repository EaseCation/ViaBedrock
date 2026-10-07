/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NyaNetworkStackLatencyPayloadTest {

    @Test
    void versionTwoEncodesBoundaryFamilyAndReleasePolicy() {
        final long encoded = NyaNetworkStackLatencyPayload.encode(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END,
                0xBEEFL);

        assertEquals(new NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor(
                        NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                        NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END),
                NyaNetworkStackLatencyPayload.decode(encoded));
    }

    @Test
    void versionOneKindOneRemainsLegacyAfterMovement() {
        final long encoded = NyaNetworkStackLatencyPayload.encodeLegacyAfterMovement(123L);

        assertEquals(new NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor(
                        NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                        NyaNetworkStackLatencyPayload.ReleasePolicy.LEGACY_AFTER_MOVEMENT),
                NyaNetworkStackLatencyPayload.decode(encoded));
    }

    @Test
    void unrelatedAndUnknownDescriptorsAreRejected() {
        assertNull(NyaNetworkStackLatencyPayload.decode(1234L));

        final long valid = NyaNetworkStackLatencyPayload.encode(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END,
                7L);
        final long unknownPolicy = valid ^ (0xFL << 16);
        final long unknownVersion = valid ^ (0x7L << 24);
        assertTrue(NyaNetworkStackLatencyPayload.isPayload(unknownPolicy));
        assertTrue(NyaNetworkStackLatencyPayload.isPayload(unknownVersion));
        assertNull(NyaNetworkStackLatencyPayload.decode(unknownPolicy));
        assertNull(NyaNetworkStackLatencyPayload.decode(unknownVersion));
    }
}
