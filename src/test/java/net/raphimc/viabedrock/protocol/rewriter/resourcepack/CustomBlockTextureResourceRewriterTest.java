/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomBlockTextureResourceRewriterTest {

    @Test
    void convertsBlocksJsonTerrainTexturesToJavaCubeModels() throws Exception {
        final InMemoryContent bedrock = new InMemoryContent();
        bedrock.putString("manifest.json", """
                {"format_version":2,"header":{
                  "name":"test","description":"test","uuid":"%s","version":[1,0,0],"min_engine_version":[1,21,0]
                }}
                """.formatted(UUID.randomUUID()));
        bedrock.putString("blocks.json", """
                {"format_version":"1.21.0",
                  "askyblockwar:war_hall":{"textures":"war_hall","sound":"stone"},
                  "askyblockwar:landing_locator":{"textures":{"up":"landing_locator","side":"war_hall"}}
                }
                """);
        bedrock.putString("textures/terrain_texture.json", """
                {"texture_name":"atlas.terrain","texture_data":{
                  "war_hall":{"textures":"textures/blocks/war_hall"},
                  "landing_locator":{"textures":"textures/blocks/landing_locator"}
                }}
                """);
        bedrock.put("textures/blocks/war_hall.png", pngBytes(0xFF336699));
        bedrock.put("textures/blocks/landing_locator.png", pngBytes(0xFF993333));

        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of(new ResourcePack(bedrock)));
        final CustomBlockTextureResourceRewriter rewriter = new CustomBlockTextureResourceRewriter();
        rewriter.initRuntimeData(storage);
        final InMemoryContent javaContent = new InMemoryContent();
        rewriter.apply(storage, javaContent);

        assertTrue(CustomBlockTextureResourceRewriter.hasConvertedTexture(storage, "askyblockwar:war_hall"));
        assertTrue(CustomBlockTextureResourceRewriter.hasConvertedTexture(storage, "askyblockwar:landing_locator"));

        final JsonObject itemDefinition = javaContent.getJson("assets/viabedrock/items/block_textures/askyblockwar/war_hall.json");
        assertNotNull(itemDefinition);
        final JsonObject cube = javaContent.getJson("assets/viabedrock/models/block_textures/askyblockwar/war_hall/0.json");
        assertNotNull(cube);
        assertEquals("minecraft:block/cube", cube.get("parent").getAsString());
        assertEquals("viabedrock:item/block_textures/blocks/war_hall",
                cube.getAsJsonObject("textures").get("up").getAsString());
        assertEquals("viabedrock:item/block_textures/blocks/war_hall",
                cube.getAsJsonObject("textures").get("north").getAsString());
        assertNotNull(javaContent.get("assets/viabedrock/textures/item/block_textures/blocks/war_hall.png"));

        final JsonObject mixed = javaContent.getJson("assets/viabedrock/models/block_textures/askyblockwar/landing_locator/0.json");
        assertNotNull(mixed);
        assertEquals("viabedrock:item/block_textures/blocks/landing_locator",
                mixed.getAsJsonObject("textures").get("up").getAsString());
        assertEquals("viabedrock:item/block_textures/blocks/war_hall",
                mixed.getAsJsonObject("textures").get("north").getAsString());
    }

    private static byte[] pngBytes(final int argb) throws Exception {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, argb);
            }
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
