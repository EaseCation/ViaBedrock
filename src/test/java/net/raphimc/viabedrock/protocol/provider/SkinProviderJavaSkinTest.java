/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.provider;

import net.raphimc.viabedrock.protocol.model.JavaSkinData;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinProviderJavaSkinTest {

    @Test
    void appliesWideAndSlimSkinClaims() {
        final Map<String, Object> wideClaims = defaultCapeClaims();
        SkinProvider.applyJavaSkinClaims(wideClaims, skin(false, null));
        assertEquals("wide", wideClaims.get("ArmSize"));
        assertEquals(64, wideClaims.get("SkinImageWidth"));
        assertEquals(64, wideClaims.get("SkinImageHeight"));
        assertEquals(64 * 64 * 4, Base64.getDecoder().decode((String) wideClaims.get("SkinData")).length);
        assertTrue(decode((String) wideClaims.get("SkinResourcePatch")).contains("geometry.humanoid.custom\""));

        final Map<String, Object> slimClaims = defaultCapeClaims();
        SkinProvider.applyJavaSkinClaims(slimClaims, skin(true, null));
        assertEquals("slim", slimClaims.get("ArmSize"));
        assertTrue(decode((String) slimClaims.get("SkinResourcePatch")).contains("geometry.humanoid.customSlim"));
    }

    @Test
    void preservesEmptyCapeOrWritesSuppliedCape() {
        final Map<String, Object> emptyCapeClaims = defaultCapeClaims();
        SkinProvider.applyJavaSkinClaims(emptyCapeClaims, skin(false, null));
        assertEquals("", emptyCapeClaims.get("CapeData"));
        assertEquals(0, emptyCapeClaims.get("CapeImageWidth"));
        assertEquals(0, emptyCapeClaims.get("CapeImageHeight"));

        final BufferedImage cape = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        final Map<String, Object> capeClaims = defaultCapeClaims();
        SkinProvider.applyJavaSkinClaims(capeClaims, skin(false, cape));
        assertEquals(64, capeClaims.get("CapeImageWidth"));
        assertEquals(32, capeClaims.get("CapeImageHeight"));
        assertEquals(64 * 32 * 4, Base64.getDecoder().decode((String) capeClaims.get("CapeData")).length);
        assertFalse(((String) capeClaims.get("CapeId")).isEmpty());
    }

    @Test
    void onlyCancelsMojangFutureOnTimeout() {
        final CompletableFuture<JavaSkinData> external = new CompletableFuture<>();
        assertThrows(TimeoutException.class, () -> SkinProvider.awaitJavaSkin(external, 1, false));
        assertFalse(external.isCancelled());

        final CompletableFuture<JavaSkinData> mojang = new CompletableFuture<>();
        assertThrows(TimeoutException.class, () -> SkinProvider.awaitJavaSkin(mojang, 1, true));
        assertTrue(mojang.isCancelled());
    }

    private static JavaSkinData skin(final boolean slim, final BufferedImage cape) {
        return new JavaSkinData(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), cape, slim, "test:skin");
    }

    private static Map<String, Object> defaultCapeClaims() {
        final Map<String, Object> claims = new HashMap<>();
        claims.put("CapeId", "");
        claims.put("CapeData", "");
        claims.put("CapeImageWidth", 0);
        claims.put("CapeImageHeight", 0);
        return claims;
    }

    private static String decode(final String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

}
