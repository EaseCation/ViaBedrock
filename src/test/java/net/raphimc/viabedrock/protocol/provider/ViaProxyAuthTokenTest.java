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
package net.raphimc.viabedrock.protocol.provider;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViaProxyAuthTokenTest {

    @Test
    void signsCanonicalClientIpInVersionTwoToken() {
        final String token = ViaProxyAuthToken.create(
                "test-secret",
                UUID.fromString("3d11b010-2f3a-409d-9387-55980de2573f"),
                "yuxuanchiadm",
                new InetSocketAddress("14.19.55.74", 47768),
                1_785_928_500L
        );

        assertEquals(
                "2:1785928500:MTQuMTkuNTUuNzQ:anM9/aA5+BaMUlqU5DI0VpAC4jNkR4bR/bG0g0MziQk=",
                token
        );
    }

}
