/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack.cache;

import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ResourcePackPipelineTestFixture {

    static final ResourcePack.Key PACK_KEY = new ResourcePack.Key(
            UUID.fromString("12345678-1234-5678-90ab-cdef12345678"), "1.0.0");

    private ResourcePackPipelineTestFixture() {
    }

    static ViaBedrockConfig config(final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, """
                enable-server-entity-animation: true
                resource-pack-cache:
                  memory-budget-mib: 64
                  memory-hard-limit-mib: 128
                  cpu-workers: 4
                  io-workers: 4
                  queue-capacity: 64
                """);
        final ViaBedrockConfig config = new ViaBedrockConfig(
                configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        return config;
    }

    static byte[] resourcePackArchive(final String marker) throws IOException {
        final String manifest = """
                {"format_version":2,"header":{"name":"%s","uuid":"%s","version":[1,0,0]}}
                """.formatted(marker, PACK_KEY.id());
        final String item = """
                {"minecraft:item":{"description":{"identifier":"test:%s"},
                "components":{"minecraft:icon":"%s"}}}
                """.formatted(marker, marker);
        final String animation = """
                {"format_version":"1.8.0","animations":{"animation.test.%s":{"loop":true}}}
                """.formatted(marker);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            writeEntry(zip, "manifest.json", manifest);
            writeEntry(zip, "items/" + marker + ".json", item);
            writeEntry(zip, "animations/" + marker + ".json", animation);
        }
        return output.toByteArray();
    }

    static void writeJavaArtifact(final Path target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":75,\"description\":\"test\"}}");
        }
    }

    private static void writeEntry(final ZipOutputStream zip, final String path, final String value)
            throws IOException {
        final ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
