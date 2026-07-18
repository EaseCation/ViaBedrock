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

import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentStreamingTest {

    @Test
    void hashingAndZipWritingUseStreamingEntryAccess() throws Exception {
        final byte[] data = "streamed resource data".getBytes(StandardCharsets.UTF_8);
        final Content streaming = new StreamingContent("assets/value.txt", data);
        final InMemoryContent expected = new InMemoryContent();
        expected.put("assets/value.txt", data);

        assertEquals(ContentDigest.compute(expected), ContentDigest.compute(streaming));
        final ByteArrayOutputStream zip = new ByteArrayOutputStream();
        streaming.writeZip(zip);
        assertNotNull(new ZipContent(zip.toByteArray()).get("assets/value.txt"));
    }

    @Test
    void frozenContentDoesNotExposeOrRetainBorrowedArrays() throws Exception {
        final byte[] sourceBytes = "immutable".getBytes(StandardCharsets.UTF_8);
        final InMemoryContent source = new InMemoryContent();
        source.put("value.txt", sourceBytes);
        final FrozenContent frozen = new FrozenContent(source);

        sourceBytes[0] = 'X';
        final byte[] borrowed = frozen.get("value.txt");
        borrowed[1] = 'X';

        assertEquals("immutable", new String(frozen.get("value.txt"), StandardCharsets.UTF_8));
        try (InputStream input = frozen.open("value.txt")) {
            assertEquals("immutable", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void directoryCopyStreamsWithoutMaterializingSource(@TempDir final Path tempDir) throws Exception {
        final byte[] data = "large binary asset".getBytes(StandardCharsets.UTF_8);
        final Content source = new StreamingContent("sounds/example.ogg", data);
        final DirectoryContent target = new DirectoryContent(tempDir.toAbsolutePath());

        target.copyFrom(source, "sounds/example.ogg", "assets/bedrock/sounds/example.ogg");

        assertEquals("large binary asset", Files.readString(
                tempDir.resolve("assets/bedrock/sounds/example.ogg"), StandardCharsets.UTF_8));
    }

    @Test
    void directoryDecryptReplacesOwnedFile(@TempDir final Path tempDir) throws Exception {
        final byte[] key = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final byte[] plain = "encrypted binary asset".getBytes(StandardCharsets.UTF_8);
        final SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        final IvParameterSpec iv = new IvParameterSpec(key);
        final Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, secretKey, iv);
        final Path file = tempDir.resolve("textures/large.bin");
        Files.createDirectories(file.getParent());
        Files.write(file, encrypt.doFinal(plain));

        final Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, secretKey, iv);
        new DirectoryContent(tempDir.toAbsolutePath()).decryptFile("textures/large.bin", decrypt);

        assertEquals("encrypted binary asset", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void boundedDirectoryDecryptLeavesOriginalFileOnOverflow(@TempDir final Path tempDir) throws Exception {
        final byte[] key = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final byte[] plain = "larger than limit".getBytes(StandardCharsets.UTF_8);
        final SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        final IvParameterSpec iv = new IvParameterSpec(key);
        final Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, secretKey, iv);
        final Path file = tempDir.resolve("contents.json");
        final byte[] encrypted = encrypt.doFinal(plain);
        Files.write(file, encrypted);

        final Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, secretKey, iv);
        assertThrows(java.io.IOException.class,
                () -> new DirectoryContent(tempDir.toAbsolutePath()).decryptFile(
                        "contents.json", decrypt, 0L, 4L));

        assertArrayEquals(encrypted, Files.readAllBytes(file));
    }

    @Test
    void diskBackedMissingImagesPreserveContentContract(@TempDir final Path tempDir) throws Exception {
        final Path archive = tempDir.resolve("content.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("textures/not-an-image.png"));
            output.write("not an image".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        final ZipFileContent content = new ZipFileContent(archive);
        assertNull(content.getShortnameImage("textures/missing"));
        assertNull(content.getShortnameImage(null));
        assertNull(content.getImage("textures/not-an-image.png"));
        assertNull(content.get(null));
        assertNull(content.open(null));
        assertEquals(-1L, content.size(null));
        assertFalse(content.contains(null));
    }

    private static final class StreamingContent extends Content {
        private final String path;
        private final byte[] data;

        private StreamingContent(final String path, final byte[] data) {
            this.path = path;
            this.data = data;
        }

        @Override
        public List<String> getFilesShallow(final String path, final String extension) {
            return this.getFilesDeep(path, extension);
        }

        @Override
        public List<String> getFilesDeep(final String path, final String extension) {
            return this.path.startsWith(path) && this.path.endsWith(extension) ? List.of(this.path) : List.of();
        }

        @Override
        public boolean contains(final String path) {
            return this.path.equals(path);
        }

        @Override
        public byte[] get(final String path) {
            throw new AssertionError("Streaming callers must not materialize the entry");
        }

        @Override
        public InputStream open(final String path) {
            return this.contains(path) ? new ByteArrayInputStream(this.data) : null;
        }

        @Override
        public long size(final String path) {
            return this.contains(path) ? this.data.length : -1L;
        }

        @Override
        public boolean put(final String path, final byte[] data) {
            throw new UnsupportedOperationException();
        }
    }
}
