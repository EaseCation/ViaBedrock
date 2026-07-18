/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.api.resourcepack.content.DirectoryContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackDecryptionTest {

    private static final byte[] CONTENTS_MAGIC = {
            0, 0, 0, 0, (byte) 0xFC, (byte) 0xB9, (byte) 0xCF, (byte) 0x9B
    };

    @Test
    void directoryPackDecryptsContentsMetadataAndEntriesWithoutMaterializingContentsJson(
            @TempDir final Path tempDir) throws Exception {
        final byte[] contentKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final byte[] fileKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII);
        final byte[] plaintext = "streamed encrypted resource".getBytes(StandardCharsets.UTF_8);
        final String contentId = "test-content";
        writeManifest(tempDir);
        final Path resource = tempDir.resolve("textures/test.bin");
        Files.createDirectories(resource.getParent());
        Files.write(resource, encrypt(fileKey, plaintext));
        Files.write(tempDir.resolve("contents.json"), encryptedContents(
                contentKey, contentId, new Entry("textures/test.bin", fileKey)));

        final ResourcePack pack = new ResourcePack(new NoContentsMaterializationDirectory(tempDir.toAbsolutePath()));
        assertTrue(pack.isContentEncrypted());
        pack.decryptContent(contentKey, contentId);

        assertArrayEquals(plaintext, Files.readAllBytes(resource));
        assertTrue(Files.readString(tempDir.resolve("contents.json"), StandardCharsets.UTF_8)
                .startsWith("{"));
    }

    @Test
    void rejectsNonCanonicalDeclaredPathBeforeDecryptingAnyEntry(@TempDir final Path tempDir) throws Exception {
        final byte[] contentKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final byte[] fileKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII);
        final byte[] plaintext = "must remain encrypted".getBytes(StandardCharsets.UTF_8);
        final byte[] ciphertext = encrypt(fileKey, plaintext);
        writeManifest(tempDir);
        final Path resource = tempDir.resolve("textures/test.bin");
        Files.createDirectories(resource.getParent());
        Files.write(resource, ciphertext);
        Files.write(tempDir.resolve("contents.json"), encryptedContents(
                contentKey, "test-content",
                new Entry("textures/test.bin", fileKey),
                new Entry("alias/../textures/test.bin", fileKey)));

        final ResourcePack pack = new ResourcePack(new DirectoryContent(tempDir.toAbsolutePath()));
        assertThrows(RuntimeException.class, () -> pack.decryptContent(contentKey, "test-content"));
        assertArrayEquals(ciphertext, Files.readAllBytes(resource));
    }

    @Test
    void rejectsMetadataSelfReferenceAndDirectoryBeforeDecryptingEntries(@TempDir final Path tempDir) throws Exception {
        final byte[] contentKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final byte[] fileKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII);
        final byte[] plaintext = "must remain encrypted".getBytes(StandardCharsets.UTF_8);
        final byte[] ciphertext = encrypt(fileKey, plaintext);
        writeManifest(tempDir);
        final Path resource = tempDir.resolve("textures/test.bin");
        Files.createDirectories(resource.getParent().resolve("folder"));
        Files.write(resource, ciphertext);

        Files.write(tempDir.resolve("contents.json"), encryptedContents(
                contentKey, "test-content",
                new Entry("textures/test.bin", fileKey),
                new Entry("textures/folder", fileKey)));
        final ResourcePack directoryPack = new ResourcePack(new DirectoryContent(tempDir.toAbsolutePath()));
        assertThrows(RuntimeException.class, () -> directoryPack.decryptContent(contentKey, "test-content"));
        assertArrayEquals(ciphertext, Files.readAllBytes(resource));

        Files.write(tempDir.resolve("contents.json"), encryptedContents(
                contentKey, "test-content", new Entry("contents.json", fileKey)));
        final ResourcePack selfReferencingPack = new ResourcePack(
                new DirectoryContent(tempDir.toAbsolutePath()));
        assertThrows(RuntimeException.class,
                () -> selfReferencingPack.decryptContent(contentKey, "test-content"));
    }

    private static void writeManifest(final Path root) throws Exception {
        Files.writeString(root.resolve("manifest.json"), """
                {"format_version":2,"header":{"uuid":"%s","version":[1,0,0],"name":"test"}}
                """.formatted(UUID.randomUUID()), StandardCharsets.UTF_8);
    }

    private static byte[] encryptedContents(final byte[] contentKey, final String contentId,
                                            final Entry... declaredEntries) throws Exception {
        final JsonArray entries = new JsonArray();
        for (Entry declaredEntry : declaredEntries) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("path", declaredEntry.path());
            entry.addProperty("key", new String(declaredEntry.key(), StandardCharsets.ISO_8859_1));
            entries.add(entry);
        }
        final JsonObject root = new JsonObject();
        root.add("content", entries);
        final byte[] encryptedJson = encrypt(contentKey,
                GsonUtil.getGson().toJson(root).getBytes(StandardCharsets.UTF_8));

        final byte[] header = new byte[256];
        System.arraycopy(CONTENTS_MAGIC, 0, header, 0, CONTENTS_MAGIC.length);
        final byte[] contentIdBytes = contentId.getBytes(StandardCharsets.UTF_8);
        header[16] = (byte) contentIdBytes.length;
        System.arraycopy(contentIdBytes, 0, header, 17, contentIdBytes.length);
        final byte[] result = Arrays.copyOf(header, header.length + encryptedJson.length);
        System.arraycopy(encryptedJson, 0, result, header.length, encryptedJson.length);
        return result;
    }

    private static byte[] encrypt(final byte[] key, final byte[] plaintext) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
        return cipher.doFinal(plaintext);
    }

    private static final class NoContentsMaterializationDirectory extends DirectoryContent {

        private NoContentsMaterializationDirectory(final Path dir) {
            super(dir);
        }

        @Override
        public byte[] get(final String path) {
            if (path.equals("contents.json")) {
                throw new AssertionError("contents.json must be streamed from disk");
            }
            return super.get(path);
        }
    }

    private record Entry(String path, byte[] key) {
    }

}
