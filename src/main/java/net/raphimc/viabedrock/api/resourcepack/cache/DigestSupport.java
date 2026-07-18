/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.cache;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

final class DigestSupport {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DigestSupport() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String requireSha256Hex(final String hex) {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() != 64) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 64 hexadecimal characters");
        }

        final String normalized = hex.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            final char c = normalized.charAt(i);
            if (c < '0' || (c > '9' && c < 'a') || c > 'f') {
                throw new IllegalArgumentException("Invalid SHA-256 digest: " + hex);
            }
        }
        return normalized;
    }

    static String toHex(final byte[] bytes) {
        final char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int value = Byte.toUnsignedInt(bytes[i]);
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }

    static byte[] fromHex(final String hex) {
        final String normalized = requireSha256Hex(hex);
        final byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            final int high = Character.digit(normalized.charAt(i * 2), 16);
            final int low = Character.digit(normalized.charAt(i * 2 + 1), 16);
            bytes[i] = (byte) (high << 4 | low);
        }
        return bytes;
    }

    static byte[] strictUtf8(final String value, final String name) {
        Objects.requireNonNull(value, name);
        try {
            final ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            final byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(name + " is not valid Unicode", e);
        }
    }

    static void updateInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    static void updateLong(final MessageDigest digest, final long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    static void updateBytes(final MessageDigest digest, final byte[] bytes) {
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

}
