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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaClientEncryptionKeyClaimTest {

    private static final String CLAIM = "JavaClientEncryptionKey";

    @Test
    void writesStandardBase64KeyWhenAuthenticationBridgeIsEnabled() {
        final Map<String, Object> claims = new HashMap<>();
        final SecretKey key = new SecretKeySpec(new byte[]{
                0, 1, 2, 3, 4, 5, 6, 7,
                8, 9, 10, 11, 12, 13, 14, 15
        }, "AES");

        SkinProvider.addJavaClientEncryptionKeyClaim(claims, "shared-secret", key);

        assertEquals("AAECAwQFBgcICQoLDA0ODw==", claims.get(CLAIM));
    }

    @Test
    void omitsClaimWhenAuthenticationBridgeIsDisabled() {
        final Map<String, Object> claims = new HashMap<>();
        final SecretKey key = new SecretKeySpec(new byte[16], "AES");

        SkinProvider.addJavaClientEncryptionKeyClaim(claims, "", key);

        assertFalse(claims.containsKey(CLAIM));
    }

    @Test
    void omitsClaimWhenJavaSessionEncryptionIsUnavailable() {
        final Map<String, Object> claims = new HashMap<>();

        SkinProvider.addJavaClientEncryptionKeyClaim(claims, "shared-secret", null);

        assertFalse(claims.containsKey(CLAIM));
    }

}
