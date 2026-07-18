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

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipContent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourcePackIdentityTest {

    private static final PackAlias BASE_ALIAS = new PackAlias(
            UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000"), "1.0.0");
    private static final PackAlias EXTRA_ALIAS = new PackAlias(
            UUID.fromString("f0e0d0c0-b0a0-9080-7060-504030201000"), "2.0.0");

    @Test
    void effectiveContentIgnoresZipLayoutButArchiveDigestDoesNot() throws Exception {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", bytes("{\"format_version\":2}"));
        files.put("textures/a.txt", bytes("alpha"));
        files.put("sounds/b.txt", bytes("bravo"));

        final byte[] firstArchive = zip(files, Deflater.BEST_SPEED, 1_000L);
        final Map<String, byte[]> reversed = new LinkedHashMap<>();
        final List<Map.Entry<String, byte[]>> entries = new ArrayList<>(files.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            reversed.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        final byte[] secondArchive = zip(reversed, Deflater.BEST_COMPRESSION, 2_000L);

        assertNotEquals(ArchiveDigest.compute(firstArchive), ArchiveDigest.compute(secondArchive));
        assertEquals(
                ContentDigest.compute(new ZipContent(firstArchive)),
                ContentDigest.compute(new ZipContent(secondArchive)));
        assertEquals(
                ArchiveDigest.compute(firstArchive),
                ArchiveDigest.compute(new ByteArrayInputStream(firstArchive)));
    }

    @Test
    void sameDeclaredAliasDoesNotHideContentDrift() {
        final ContentDigest first = digestOf("textures/value.txt", "first");
        final ContentDigest second = digestOf("textures/value.txt", "second");

        assertNotEquals(first, second);
        assertNotEquals(
                RuntimeStackKey.compute(List.of(new PackMount(BASE_ALIAS, first))),
                RuntimeStackKey.compute(List.of(new PackMount(BASE_ALIAS, second))));
        assertEquals(BASE_ALIAS, PackAlias.from(BASE_ALIAS.toResourcePackKey()));
    }

    @Test
    void completeAliasScopesEveryTrustedDeclarationField() {
        final ResourcePack.Key key = BASE_ALIAS.toResourcePackKey();
        final byte[] contentKey = bytes("secret");
        final PackAlias complete = PackAlias.from(
                "inet:10.0.0.1:19132", key, 123L, "content-id", contentKey);

        assertEquals("inet:10.0.0.1:19132", complete.backendScope());
        assertEquals(123L, complete.announcedSize());
        assertEquals("content-id", complete.contentId());
        assertEquals(PackAlias.fingerprintContentKey(contentKey), complete.contentKeyFingerprint());
        assertEquals(key, complete.toResourcePackKey());
        assertEquals(false, BASE_ALIAS.isComplete());
        assertEquals(true, complete.isComplete());
    }

    @Test
    void stackKeyPreservesOrderSubpackAndRepeatedMounts() {
        final PackMount base = new PackMount(BASE_ALIAS, digestOf("base.txt", "base"));
        final PackMount extra = new PackMount(EXTRA_ALIAS, digestOf("extra.txt", "extra"));
        final PackMount selectedSubpack = new PackMount(BASE_ALIAS, base.contentDigest(), "high_resolution");

        final RuntimeStackKey baseThenExtra = RuntimeStackKey.compute(List.of(base, extra));
        assertNotEquals(baseThenExtra, RuntimeStackKey.compute(List.of(extra, base)));
        assertNotEquals(RuntimeStackKey.compute(List.of(base)), RuntimeStackKey.compute(List.of(selectedSubpack)));
        assertNotEquals(baseThenExtra, RuntimeStackKey.compute(List.of(base, base, extra)));
        assertEquals(baseThenExtra, RuntimeStackKey.compute(List.of(base, extra)));
    }

    @Test
    void stackKeyIncludesDeclaredPackKey() {
        final ContentDigest digest = digestOf("same.txt", "same");
        final PackAlias alternateVersion = new PackAlias(
                BASE_ALIAS.id(), BASE_ALIAS.version() + ".1");

        assertNotEquals(
                RuntimeStackKey.compute(List.of(new PackMount(BASE_ALIAS, digest))),
                RuntimeStackKey.compute(List.of(new PackMount(alternateVersion, digest))));
    }

    @Test
    void stackKeyIncludesSchemaAndBundledDefinitionIdentity() {
        final PackMount mount = new PackMount(BASE_ALIAS, digestOf("value.txt", "value"));

        final RuntimeStackKey baseline = RuntimeStackKey.compute(List.of(mount), 1, List.of("motion-a"));
        assertEquals(baseline, RuntimeStackKey.compute(List.of(mount), 1, List.of("motion-a")));
        assertNotEquals(baseline, RuntimeStackKey.compute(List.of(mount), 2, List.of("motion-a")));
        assertNotEquals(baseline, RuntimeStackKey.compute(List.of(mount), 1, List.of("motion-b")));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeStackKey.compute(List.of(mount), -1, List.of("motion-a")));
    }

    @Test
    void artifactKeyIncludesConverterAndClientVariant() {
        final RuntimeStackKey stack = RuntimeStackKey.compute(List.of(
                new PackMount(BASE_ALIAS, digestOf("base.txt", "base"))));

        final ArtifactKey baseline = ArtifactKey.compute(stack, 6, false);
        assertNotEquals(baseline, ArtifactKey.compute(stack, 7, false));
        assertNotEquals(baseline, ArtifactKey.compute(stack, 6, true));
        assertEquals(baseline, ArtifactKey.compute(stack, 6, false));
        assertThrows(IllegalArgumentException.class, () -> ArtifactKey.compute(stack, -1, false));

        final ArtifactKey fingerprinted = ArtifactKey.compute(stack, 6, "rewriters-a", false);
        assertNotEquals(fingerprinted, ArtifactKey.compute(stack, 6, "rewriters-b", false));
        assertNotEquals(fingerprinted, ArtifactKey.compute(stack, 7, "rewriters-a", false));
        assertNotEquals(fingerprinted, ArtifactKey.compute(stack, 6, "rewriters-a", true));
        assertEquals(fingerprinted, ArtifactKey.compute(stack, 6, "rewriters-a", false));
    }

    @Test
    void digestTypesAreNotInterchangeableEvenWithTheSameHex() {
        final ContentDigest content = digestOf("same.txt", "same");
        final ArchiveDigest archive = new ArchiveDigest(content.hex());
        final ArtifactKey artifact = new ArtifactKey(content.hex());

        assertNotEquals(content, archive);
        assertNotEquals(content, artifact);
        assertNotEquals(archive, artifact);
    }

    @Test
    void contentDigestRejectsDuplicateAndMissingEntries() {
        final Map<String, byte[]> data = Map.of("duplicate.txt", bytes("value"));
        assertThrows(IllegalArgumentException.class, () -> ContentDigest.compute(
                new ListedContent(List.of("duplicate.txt", "duplicate.txt"), data)));
        assertThrows(IllegalArgumentException.class, () -> ContentDigest.compute(
                new ListedContent(List.of("missing.txt"), Map.of())));
    }

    @Test
    void contentDigestRejectsNonCanonicalAndTraversalPaths() {
        for (String path : List.of(
                "../escape.txt",
                "folder/../escape.txt",
                "./relative.txt",
                "/absolute.txt",
                "C:/drive.txt",
                "folder\\windows.txt",
                "folder//empty.txt",
                "folder/",
                "control\u0000.txt")) {
            assertThrows(IllegalArgumentException.class, () -> ContentDigest.compute(
                    new ListedContent(List.of(path), Map.of(path, bytes("value")))), path);
        }
    }

    @Test
    void contentDigestLengthFramesPathsAndValues() {
        assertNotEquals(
                digestOf("a", "bc"),
                digestOf("ab", "c"));
    }

    @Test
    void digestHexIsCanonicalAndValidated() {
        final String uppercase = "A".repeat(64);
        assertEquals("a".repeat(64), new ArchiveDigest(uppercase).hex());
        assertThrows(IllegalArgumentException.class, () -> new ContentDigest("0".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeStackKey("z".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PackAlias(BASE_ALIAS.id(), ""));
    }

    private static ContentDigest digestOf(final String path, final String value) {
        final InMemoryContent content = new InMemoryContent();
        content.put(path, bytes(value));
        return ContentDigest.compute(content);
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(final Map<String, byte[]> files, final int compressionLevel, final long timestamp) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setLevel(compressionLevel);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                final ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(timestamp);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static final class ListedContent extends Content {

        private final List<String> paths;
        private final Map<String, byte[]> data;

        private ListedContent(final List<String> paths, final Map<String, byte[]> data) {
            this.paths = paths;
            this.data = data;
        }

        @Override
        public List<String> getFilesShallow(final String path, final String extension) {
            return this.getFilesDeep(path, extension);
        }

        @Override
        public List<String> getFilesDeep(final String path, final String extension) {
            return this.paths;
        }

        @Override
        public boolean contains(final String path) {
            return this.data.containsKey(path);
        }

        @Override
        public byte[] get(final String path) {
            return this.data.get(path);
        }

        @Override
        public boolean put(final String path, final byte[] data) {
            throw new UnsupportedOperationException();
        }

    }

}
