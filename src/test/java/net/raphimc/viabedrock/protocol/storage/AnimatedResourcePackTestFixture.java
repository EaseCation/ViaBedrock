/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import net.easecation.bedrockmotion.pack.PackManager;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.experimental.model.animation.ServerEntityTicker;
import net.raphimc.viabedrock.experimental.model.animation.SimpleBone;
import net.raphimc.viabedrock.experimental.model.animation.SimpleBoneModel;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class AnimatedResourcePackTestFixture {

    static final String ENTITY_IDENTIFIER = "test:animated";
    static final String GEOMETRY_IDENTIFIER = "geometry.test.animated";
    static final String STANDALONE_ANIMATION_IDENTIFIER = "animation.test.idle";
    static final String CONTROLLED_ANIMATION_IDENTIFIER = "animation.test.controlled";
    static final String CONTROLLER_IDENTIFIER = "controller.animation.test";

    private AnimatedResourcePackTestFixture() {
    }

    static ResourcePack pack(final UUID id, final int payloadBytes) {
        final InMemoryContent content = new InMemoryContent();
        content.putString("manifest.json", """
                {"format_version":2,"header":{
                  "name":"animated-test","description":"animated-test",
                  "uuid":"%s","version":[1,0,0],"min_engine_version":[1,20,0]
                }}
                """.formatted(id));
        content.putString("entity/animated.json", """
                {"format_version":"1.10.0","minecraft:client_entity":{"description":{
                  "identifier":"test:animated",
                  "materials":{"default":"entity_alphatest"},
                  "textures":{"default":"textures/entity/animated"},
                  "geometry":{"default":"geometry.test.animated"},
                  "animations":{
                    "idle":"animation.test.idle",
                    "controlled":"animation.test.controlled",
                    "controller":"controller.animation.test"
                  },
                  "scripts":{"animate":["idle","controller"]},
                  "render_controllers":[]
                }}}
                """);
        content.putString("models/entity/animated.geo.json", """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{
                    "identifier":"geometry.test.animated","texture_width":16,"texture_height":16
                  },
                  "bones":[{
                    "name":"root","pivot":[0,0,0],"rotation":[0,0,0],
                    "cubes":[{
                      "origin":[0,0,0],"size":[1,1,1],"pivot":[0,0,0],
                      "rotation":[0,0,0],"uv":[0,0]
                    }]
                  }]
                }]}
                """);
        content.putString("animations/animated.json", """
                {"format_version":"1.8.0","animations":{
                  "animation.test.idle":{
                    "loop":true,"bones":{"root":{"rotation":[10,0,0]}}
                  },
                  "animation.test.controlled":{
                    "loop":true,"bones":{"root":{"rotation":[0,20,0]}}
                  }
                }}
                """);
        content.putString("animation_controllers/animated.json", """
                {"format_version":"1.10.0","animation_controllers":{
                  "controller.animation.test":{
                    "initial_state":"default",
                    "states":{"default":{"animations":["controlled"]}}
                  }
                }}
                """);
        if (payloadBytes > 0) {
            final byte[] payload = new byte[payloadBytes];
            new Random(0x5642_524FL).nextBytes(payload);
            content.put("payload.bin", payload);
        }
        return new ResourcePack(content);
    }

    static ResourcePack loadCanonical(final ResourcePackArchiveStore archiveStore, final UUID id,
                                      final int payloadBytes) throws Exception {
        final ResourcePack source = pack(id, payloadBytes);
        final byte[] archive = source.content().toZip();
        return archiveStore.loadFromSource(
                        "test://animated/" + id, source.key(), new byte[0], "", () -> archive)
                .get(30L, TimeUnit.SECONDS);
    }

    static ServerEntityTicker ticker(final ResourcePackStorage storage) {
        final EntityDefinitions.EntityDefinition entity = Objects.requireNonNull(
                storage.getEntities().get(ENTITY_IDENTIFIER), "animated entity definition");
        final BedrockGeometryModel geometry = Objects.requireNonNull(
                storage.getModels().getEntityModel(GEOMETRY_IDENTIFIER), "animated geometry definition");
        final PackManager packManager = Objects.requireNonNull(
                storage.getBedrockMotionPackManager(), "shared BedrockMotion PackManager");
        return new ServerEntityTicker(entity.entityData(), new SimpleBoneModel(geometry), packManager);
    }

    static SimpleBone rootBone(final ServerEntityTicker ticker) {
        final Object root = ticker.getBoneModel().getBoneIndex().get("root");
        if (root instanceof SimpleBone bone) {
            return bone;
        }
        throw new IllegalStateException("Animated test geometry has no root SimpleBone");
    }

}
