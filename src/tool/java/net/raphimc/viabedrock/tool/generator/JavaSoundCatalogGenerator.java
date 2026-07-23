/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.tool.generator;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class JavaSoundCatalogGenerator {

    private static final URI VERSION_MANIFEST = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final Path OUTPUT_DIRECTORY = Path.of(
            "src/main/resources/assets/viabedrock/data/java");

    private JavaSoundCatalogGenerator() {
    }

    public static void main(final String[] versions) throws Exception {
        if (versions.length == 0) {
            throw new IllegalArgumentException("Pass one or more Java version names");
        }

        final JsonObject manifest = readJson(VERSION_MANIFEST);
        final Map<String, URI> versionMetadata = new HashMap<>();
        for (JsonElement versionElement : manifest.getAsJsonArray("versions")) {
            final JsonObject version = versionElement.getAsJsonObject();
            versionMetadata.put(version.get("id").getAsString(), URI.create(version.get("url").getAsString()));
        }

        Files.createDirectories(OUTPUT_DIRECTORY);
        for (String version : versions) {
            final URI metadataUri = versionMetadata.get(version);
            if (metadataUri == null) {
                throw new IllegalArgumentException("Unknown Java version: " + version);
            }
            final JsonObject metadata = readJson(metadataUri);
            final URI assetIndexUri = URI.create(metadata.getAsJsonObject("assetIndex")
                    .get("url").getAsString());
            final JsonObject soundsAsset = readJson(assetIndexUri).getAsJsonObject("objects")
                    .getAsJsonObject("minecraft/sounds.json");
            if (soundsAsset == null) {
                throw new IllegalStateException("Asset index has no sounds.json: " + version);
            }
            final String hash = soundsAsset.get("hash").getAsString();
            final URI soundsUri = URI.create("https://resources.download.minecraft.net/"
                    + hash.substring(0, 2) + '/' + hash);
            Files.writeString(OUTPUT_DIRECTORY.resolve("sounds-" + version + ".json"),
                    GsonUtil.getGson().toJson(acousticProjection(readJson(soundsUri))),
                    StandardCharsets.UTF_8);
        }
    }

    static JsonObject acousticProjection(final JsonObject sounds) {
        final JsonObject catalog = new JsonObject();
        for (Map.Entry<String, JsonElement> event : sounds.entrySet()) {
            if (!event.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject eventData = event.getValue().getAsJsonObject();
            if (!eventData.has("sounds") || !eventData.get("sounds").isJsonArray()) {
                continue;
            }

            final JsonArray entries = new JsonArray();
            for (JsonElement soundElement : eventData.getAsJsonArray("sounds")) {
                if (soundElement.isJsonPrimitive()) {
                    entries.add("");
                    continue;
                }
                if (!soundElement.isJsonObject()) {
                    continue;
                }
                final JsonObject source = soundElement.getAsJsonObject();
                final JsonObject projected = new JsonObject();
                if (source.has("type") && "event".equals(source.get("type").getAsString())) {
                    copy(source, projected, "name");
                    copy(source, projected, "type");
                }
                copy(source, projected, "volume");
                copy(source, projected, "pitch");
                copy(source, projected, "weight");
                copy(source, projected, "attenuation_distance");
                entries.add(projected);
            }
            final JsonObject projectedEvent = new JsonObject();
            projectedEvent.add("sounds", entries);
            catalog.add(event.getKey(), projectedEvent);
        }
        return catalog;
    }

    private static JsonObject readJson(final URI uri) throws Exception {
        try (InputStream input = uri.toURL().openStream();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return GsonUtil.getGson().fromJson(reader, JsonObject.class);
        }
    }

    private static void copy(final JsonObject source, final JsonObject target, final String key) {
        if (source.has(key)) {
            target.add(key, source.get(key));
        }
    }

}
