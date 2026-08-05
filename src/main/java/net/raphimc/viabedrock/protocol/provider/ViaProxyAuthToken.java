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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

final class ViaProxyAuthToken {

    private ViaProxyAuthToken() {
    }

    static String create(final String secret, final UUID uuid, final String username,
                         final SocketAddress remoteAddress, final long timestamp) {
        final String clientIp = clientIp(remoteAddress);
        if (clientIp == null) {
            final String payload = uuid + ":" + username + ":" + timestamp;
            return computeHmacSha256(secret, payload) + ":" + timestamp;
        }

        final String encodedIp = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clientIp.getBytes(StandardCharsets.UTF_8));
        final String payload = uuid + ":" + username + ":" + timestamp + ":" + clientIp;
        return "2:" + timestamp + ":" + encodedIp + ":" + computeHmacSha256(secret, payload);
    }

    private static String clientIp(final SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress inetSocketAddress)
                || inetSocketAddress.getAddress() == null) {
            return null;
        }
        return inetSocketAddress.getAddress().getHostAddress();
    }

    private static String computeHmacSha256(final String secret, final String data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

}
