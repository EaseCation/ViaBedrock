/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.platform.ViaBedrockPlatform;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UITextureResourceRewriterTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP1sAAAAASUVORK5CYII=");
    private Field platformField;
    private Object previousPlatform;

    @BeforeEach
    void installLogger() throws Exception {
        platformField = ViaBedrock.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        previousPlatform = platformField.get(null);
        platformField.set(null, new ViaBedrockPlatform() {
            @Override
            public Logger getLogger() {
                return Logger.getLogger(UITextureResourceRewriterTest.class.getName());
            }

            @Override
            public File getDataFolder() {
                throw new UnsupportedOperationException("Resource copying has no platform data directory");
            }
        });
    }

    @AfterEach
    void restorePlatform() throws Exception {
        platformField.set(null, previousPlatform);
    }

    @Test
    void convertedPackContainsTheOriginalNavigationImageAndTopPriorityFrames() {
        var top = content("00000000-0000-0000-0000-000000000001");
        var lower = content("00000000-0000-0000-0000-000000000002");
        byte[] frames = "{\"frames\":[{\"frame\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}}]}"
                .getBytes(StandardCharsets.UTF_8);
        top.put("sfx/frame/navi_yellow.png", PNG);
        top.put("sfx/frame/navi_yellow.json", frames);
        lower.put("sfx/frame/navi_yellow.png", new byte[]{0});
        lower.put("sfx/frame/navi_yellow.json", "{}".getBytes(StandardCharsets.UTF_8));
        var output = new InMemoryContent();

        new UITextureResourceRewriter().apply(
                new ResourcePackStorage(List.of(new ResourcePack(top), new ResourcePack(lower))), output);

        assertArrayEquals(PNG, output.get("assets/minecraft/sfx/frame/navi_yellow.png"));
        assertArrayEquals(frames, output.get("assets/minecraft/sfx/frame/navi_yellow.json"));
    }

    @Test
    void keepsUiTexturesAndLimitsSfxCopyingToFrameAssets() {
        var source = content("00000000-0000-0000-0000-000000000003");
        source.put("textures/ui/button.png", PNG);
        source.put("textures/gui/panel.png", PNG);
        source.put("sfx/frame/Navi_Yellow.png", PNG);
        source.put("sfx/unrelated.png", PNG);
        source.put("sfx/frame/private.bin", new byte[]{1});
        var output = new InMemoryContent();

        new UITextureResourceRewriter().apply(new ResourcePackStorage(List.of(new ResourcePack(source))), output);

        assertArrayEquals(PNG, output.get("assets/minecraft/textures/ui/button.png"));
        assertArrayEquals(PNG, output.get("assets/minecraft/textures/gui/panel.png"));
        assertArrayEquals(PNG, output.get("assets/minecraft/sfx/frame/navi_yellow.png"));
        assertFalse(output.contains("assets/minecraft/sfx/unrelated.png"));
        assertFalse(output.contains("assets/minecraft/sfx/frame/private.bin"));
    }

    private static InMemoryContent content(String uuid) {
        var content = new InMemoryContent();
        content.put("manifest.json", ("{\"format_version\":2,\"header\":{\"uuid\":\"" + uuid
                + "\",\"name\":\"Navigation fixture\",\"version\":[1,0,0]}}")
                .getBytes(StandardCharsets.UTF_8));
        return content;
    }
}
