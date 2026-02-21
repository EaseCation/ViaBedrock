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
package net.raphimc.viabedrock.api.http;

import net.raphimc.viabedrock.api.model.resourcepack.ResourcePack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.stream.Collectors;

public class JavaPackCache {

    private final File cacheFolder;

    public JavaPackCache(final File cacheFolder) {
        this.cacheFolder = cacheFolder;
        this.cacheFolder.mkdirs();
    }

    public static String computeCacheKey(final Collection<ResourcePack> packs) {
        final String raw = packs.stream()
                .map(p -> p.packId() + "_" + p.version())
                .sorted()
                .collect(Collectors.joining(","));
        return sha1Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    public boolean has(final String cacheKey) {
        return getZipFile(cacheKey).isFile() && getHashFile(cacheKey).isFile();
    }

    public String getHash(final String cacheKey) throws IOException {
        return Files.readString(getHashFile(cacheKey).toPath(), StandardCharsets.UTF_8).trim();
    }

    public byte[] getData(final String cacheKey) throws IOException {
        return Files.readAllBytes(getZipFile(cacheKey).toPath());
    }

    public void put(final String cacheKey, final byte[] zipData) throws IOException {
        final String hash = sha1Hex(zipData);
        Files.write(getZipFile(cacheKey).toPath(), zipData);
        Files.writeString(getHashFile(cacheKey).toPath(), hash, StandardCharsets.UTF_8);
    }

    private File getZipFile(final String cacheKey) {
        return new File(this.cacheFolder, cacheKey + ".zip");
    }

    private File getHashFile(final String cacheKey) {
        return new File(this.cacheFolder, cacheKey + ".sha1");
    }

    private static String sha1Hex(final byte[] data) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            final byte[] hash = digest.digest(data);
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

}
