/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.camera;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CameraPreset_AudioListener;
import net.raphimc.viabedrock.protocol.model.Position3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CameraAudioTracker extends StoredObject {

    private List<Preset> presets = List.of();
    private Map<String, Integer> presetIds = Map.of();
    private boolean cameraActive;
    private boolean cameraListener;
    private Position3f cameraPosition;

    public CameraAudioTracker(final UserConnection user) {
        super(user);
    }

    public void setPresets(final String[] names, final String[] parents, final Float[] posXs,
                           final Float[] posYs, final Float[] posZs, final Byte[] audioListeners) {
        final List<Preset> newPresets = new ArrayList<>(names.length);
        final Map<String, Integer> newPresetIds = new HashMap<>(names.length);
        for (int i = 0; i < names.length; i++) {
            newPresets.add(new Preset(names[i], parents[i], posXs[i], posYs[i], posZs[i], audioListeners[i]));
            newPresetIds.put(names[i], i);
        }
        this.presets = List.copyOf(newPresets);
        this.presetIds = Map.copyOf(newPresetIds);
    }

    public void applyInstruction(final boolean hasSet, final int presetRuntimeId,
                                 final Position3f explicitPosition, final boolean hasClear) {
        if (hasClear) {
            this.cameraActive = false;
            this.cameraListener = false;
            this.cameraPosition = null;
        }
        if (!hasSet) {
            return;
        }

        final Preset preset = this.resolvePreset(presetRuntimeId, new HashSet<>());
        this.cameraActive = true;
        final Byte listener = preset != null ? preset.audioListener() : null;
        this.cameraListener = listener == null
                || listener == CameraPreset_AudioListener.Camera.getValue();
        this.cameraPosition = explicitPosition != null ? explicitPosition
                : preset != null ? preset.position() : null;
    }

    public Position3f bedrockListenerPosition(final Position3f playerPosition) {
        return this.cameraActive && this.cameraListener && this.cameraPosition != null
                ? this.cameraPosition : playerPosition;
    }

    public Position3f javaListenerPosition(final Position3f playerPosition, final boolean cameraRenderedByJava) {
        return cameraRenderedByJava && this.cameraActive && this.cameraPosition != null
                ? this.cameraPosition : playerPosition;
    }

    private Preset resolvePreset(final int id, final Set<Integer> resolving) {
        if (id < 0 || id >= this.presets.size() || !resolving.add(id)) {
            return null;
        }
        final Preset preset = this.presets.get(id);
        final Integer parentId = this.presetIds.get(preset.parent());
        if (parentId == null) {
            return preset;
        }
        final Preset parent = this.resolvePreset(parentId, resolving);
        if (parent == null) {
            return preset;
        }
        return new Preset(preset.name(), preset.parent(),
                preset.x() != null ? preset.x() : parent.x(),
                preset.y() != null ? preset.y() : parent.y(),
                preset.z() != null ? preset.z() : parent.z(),
                preset.audioListener() != null ? preset.audioListener() : parent.audioListener());
    }

    private record Preset(String name, String parent, Float x, Float y, Float z, Byte audioListener) {

        private Position3f position() {
            return this.x != null && this.y != null && this.z != null
                    ? new Position3f(this.x, this.y, this.z) : null;
        }

    }

}
