/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.sound;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;
import net.raphimc.viabedrock.experimental.camera.CameraAudioTracker;
import net.raphimc.viabedrock.experimental.camera.CameraInterface;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class SoundPacketProjection {

    private SoundPacketProjection() {
    }

    public static Result project(final UserConnection user, final String bedrockIdentifier,
                                 final String javaIdentifier, final Position3f sourcePosition,
                                 final float eventVolume, final float eventPitch,
                                 final boolean global, final boolean generatedCustomSound,
                                 final long seed) {
        final ResourcePackStorage resourcePacks = user.get(ResourcePackStorage.class);
        final SoundDefinitions.SoundDefinition definition = resourcePacks != null
                && resourcePacks.getSounds() != null
                ? resourcePacks.getSounds().soundDefinitions().get(bedrockIdentifier) : null;
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final ClientPlayerEntity player = entityTracker != null ? entityTracker.getClientPlayer() : null;
        final Position3f playerPosition = player != null ? player.position() : null;
        final CameraAudioTracker cameraAudio = user.get(CameraAudioTracker.class);
        final Position3f bedrockListenerPosition = playerPosition != null && cameraAudio != null
                ? cameraAudio.bedrockListenerPosition(playerPosition) : playerPosition;
        final ChannelStorage channels = user.get(ChannelStorage.class);
        final boolean cameraRenderedByJava = channels != null && channels.hasChannel(CameraInterface.CONFIRM_CHANNEL);
        final Position3f javaListenerPosition = playerPosition != null && cameraAudio != null
                ? cameraAudio.javaListenerPosition(playerPosition, cameraRenderedByJava) : playerPosition;
        return project(definition, user.getProtocolInfo().protocolVersion(), javaIdentifier, sourcePosition,
                eventVolume, eventPitch, global, generatedCustomSound, seed,
                bedrockListenerPosition, javaListenerPosition);
    }

    static Result project(final SoundDefinitions.SoundDefinition definition,
                          final ProtocolVersion protocolVersion, final String javaIdentifier,
                          final Position3f sourcePosition, final float eventVolume, final float eventPitch,
                          final boolean global, final boolean generatedCustomSound, final long seed,
                          final Position3f bedrockListenerPosition, final Position3f javaListenerPosition) {
        final boolean invalidSource = !Float.isFinite(sourcePosition.x())
                || !Float.isFinite(sourcePosition.y()) || !Float.isFinite(sourcePosition.z());
        if (bedrockListenerPosition == null || javaListenerPosition == null) {
            final float safeVolume = Float.isFinite(eventVolume) ? Math.max(0F, eventVolume) : 0F;
            final float safePitch = Float.isFinite(eventPitch) ? Math.max(0F, eventPitch) : 1F;
            return new Result(true, sourcePosition, safeVolume, safePitch, seed);
        }

        final JavaSoundCatalog.Variant javaVariant = generatedCustomSound
                ? SoundAcousticProjector.resolveGeneratedJavaVariant(definition, seed)
                : JavaSoundCatalog.resolve(protocolVersion, javaIdentifier, seed);
        final float distance = invalidSource ? 0F : bedrockListenerPosition.distanceTo(sourcePosition);
        final SoundAcousticProjector.Projection projection = SoundAcousticProjector.project(
                definition, eventVolume, eventPitch, distance, global || invalidSource, javaVariant,
                generatedCustomSound ? new Random(seed) : ThreadLocalRandom.current());
        if (!projection.send()) {
            return Result.CANCELLED;
        }
        final Position3f projectedSource = invalidSource ? javaListenerPosition
                : sourcePosition.add(javaListenerPosition.subtract(bedrockListenerPosition));
        return new Result(true, projection.nonSpatial() ? javaListenerPosition : projectedSource,
                projection.volume(), projection.pitch(), seed);
    }

    public record Result(boolean send, Position3f position, float volume, float pitch, long seed) {

        private static final Result CANCELLED = new Result(false, Position3f.ZERO, 0F, 0F, 0L);

    }

}
