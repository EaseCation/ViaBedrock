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
package net.raphimc.viabedrock.api.resourcepack.content;

import net.raphimc.viabedrock.ViaBedrock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipContent extends InMemoryContent {

    public ZipContent(final byte[] zipData) throws IOException {
        this(zipData, Limits.configured());
    }

    ZipContent(final byte[] zipData, final Limits limits) throws IOException {
        if (zipData.length > limits.maxArchiveBytes()) {
            throw new IOException("Resource pack archive exceeds the configured size limit");
        }
        final Set<String> paths = new HashSet<>();
        final byte[] buffer = new byte[16 * 1024];
        long expandedBytes = 0L;
        int entries = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) {
                    throw new IOException("Resource pack contains too many entries");
                }
                final boolean directory = zipEntry.isDirectory();
                final String rawPath = zipEntry.getName();
                final String path = validatePath(directory ? rawPath.substring(0, rawPath.length() - 1) : rawPath);
                if (!paths.add(path)) {
                    throw new IOException("Resource pack contains duplicate path: " + path);
                }

                final ByteArrayOutputStream entry = directory ? null : new ByteArrayOutputStream(8 * 1024);
                long entryBytes = 0L;
                int read;
                while ((read = zipInputStream.read(buffer)) != -1) {
                    entryBytes += read;
                    expandedBytes += read;
                    if (entryBytes > limits.maxEntryBytes()) {
                        throw new IOException("Resource pack entry exceeds the configured size limit: " + path);
                    }
                    if (expandedBytes > limits.maxExpandedBytes()) {
                        throw new IOException("Resource pack exceeds the configured expanded size limit");
                    }
                    if (zipData.length > 0
                            && expandedBytes > (long) zipData.length * limits.maxCompressionRatio()) {
                        throw new IOException("Resource pack exceeds the configured compression ratio");
                    }
                    if (directory) {
                        throw new IOException("Resource pack directory entry contains data: " + rawPath);
                    }
                    entry.write(buffer, 0, read);
                }
                if (!directory) {
                    this.content.put(path, entry.toByteArray());
                }
            }
        }
    }

    private static String validatePath(final String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
            throw new IOException("Resource pack contains a non-canonical path: " + path);
        }
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            throw new IOException("Resource pack path contains a drive prefix: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Resource pack contains a non-canonical path: " + path);
            }
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw new IOException("Resource pack path contains a control character");
            }
        }
        return path;
    }

    record Limits(long maxArchiveBytes, long maxExpandedBytes, long maxEntryBytes, int maxEntries,
                  int maxCompressionRatio) {

        private static final long MIB = 1024L * 1024L;

        static Limits configured() {
            if (ViaBedrock.getConfig() == null) {
                return new Limits(2_048L * MIB, 4_096L * MIB, 512L * MIB, 100_000, 200);
            }
            return new Limits(
                    ViaBedrock.getConfig().getResourcePackMaxArchiveMiB() * MIB,
                    ViaBedrock.getConfig().getResourcePackMaxExpandedMiB() * MIB,
                    ViaBedrock.getConfig().getResourcePackMaxEntryMiB() * MIB,
                    ViaBedrock.getConfig().getResourcePackMaxEntries(),
                    ViaBedrock.getConfig().getResourcePackMaxCompressionRatio());
        }
    }

}
