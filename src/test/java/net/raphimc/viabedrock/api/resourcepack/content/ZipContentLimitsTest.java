/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.content;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZipContentLimitsTest {

    private static final ZipContent.Limits GENEROUS_LIMITS = new ZipContent.Limits(
            1_000_000L, 1_000_000L, 1_000_000L, 100, 10_000);

    @Test
    void loadsAValidArchiveWithinLimits() throws Exception {
        final byte[] archive = zip(List.of(
                new Entry("manifest.json", bytes("manifest")),
                new Entry("textures/value.txt", bytes("value"))));

        final ZipContent content = new ZipContent(archive, GENEROUS_LIMITS);

        assertArrayEquals(bytes("value"), content.get("textures/value.txt"));
    }

    @Test
    void rejectsDuplicateEntryNames() throws Exception {
        final byte[] distinctNames = zip(List.of(
                new Entry("a.txt", bytes("first")),
                new Entry("b.txt", bytes("second"))));
        final byte[] duplicateNames = replaceAscii(distinctNames, "b.txt", "a.txt");

        assertThrows(IOException.class, () -> new ZipContent(duplicateNames, GENEROUS_LIMITS));
    }

    @Test
    void rejectsTraversalAndNonCanonicalPathsIncludingDirectories() throws Exception {
        for (String path : List.of(
                "../escape.txt",
                "folder/../escape.txt",
                "/absolute.txt",
                "C:/drive.txt",
                "folder\\windows.txt",
                "folder//empty.txt")) {
            final byte[] archive = zip(List.of(new Entry(path, bytes("value"))));
            assertThrows(IOException.class, () -> new ZipContent(archive, GENEROUS_LIMITS), path);
        }

        final byte[] traversalDirectory = zip(List.of(new Entry("../escape/", new byte[0])));
        assertThrows(IOException.class, () -> new ZipContent(traversalDirectory, GENEROUS_LIMITS));
    }

    @Test
    void countsDirectoryEntriesAgainstTheEntryLimit() throws Exception {
        final byte[] archive = zip(List.of(
                new Entry("first/", new byte[0]),
                new Entry("second/", new byte[0])));
        final ZipContent.Limits limits = new ZipContent.Limits(
                archive.length, 1_000L, 1_000L, 1, 100);

        assertThrows(IOException.class, () -> new ZipContent(archive, limits));
    }

    @Test
    void rejectsArchiveEntryAndExpandedSizeLimitBreaches() throws Exception {
        final byte[] archive = zip(List.of(
                new Entry("first.bin", new byte[8]),
                new Entry("second.bin", new byte[8])));

        assertThrows(IOException.class, () -> new ZipContent(archive, new ZipContent.Limits(
                archive.length - 1L, 1_000L, 1_000L, 10, 1_000)));
        assertThrows(IOException.class, () -> new ZipContent(archive, new ZipContent.Limits(
                archive.length, 1_000L, 7L, 10, 1_000)));
        assertThrows(IOException.class, () -> new ZipContent(archive, new ZipContent.Limits(
                archive.length, 15L, 1_000L, 10, 1_000)));
    }

    @Test
    void rejectsArchivesAboveTheCompressionRatio() throws Exception {
        final byte[] archive = zip(List.of(new Entry("compressed.bin", new byte[16 * 1024])));

        assertThrows(IOException.class, () -> new ZipContent(archive, new ZipContent.Limits(
                archive.length, 1_000_000L, 1_000_000L, 10, 2)));
    }

    private static byte[] zip(final List<Entry> entries) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.path()));
                zip.write(entry.data());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] replaceAscii(final byte[] source, final String target, final String replacement) {
        final byte[] result = source.clone();
        final byte[] targetBytes = bytes(target);
        final byte[] replacementBytes = bytes(replacement);
        if (targetBytes.length != replacementBytes.length) {
            throw new IllegalArgumentException("ZIP entry names must have equal encoded lengths");
        }
        for (int i = 0; i <= result.length - targetBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < targetBytes.length; j++) {
                if (result[i + j] != targetBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(replacementBytes, 0, result, i, replacementBytes.length);
                i += replacementBytes.length - 1;
            }
        }
        return result;
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Entry(String path, byte[] data) {
    }

}
