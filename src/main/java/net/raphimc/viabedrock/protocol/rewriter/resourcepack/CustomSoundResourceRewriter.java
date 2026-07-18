/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class CustomSoundResourceRewriter implements ResourcePackRewriter.Rewriter {

    public static final String CUSTOM_SOUNDS_KEY = "custom_sound_names";

    @Override
    public void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        this.collectCustomSoundNames(resourcePackStorage, javaContent);
    }

    @Override
    public void initRuntimeData(final ResourcePackStorage resourcePackStorage) {
        final Set<String> sharedNames = resourcePackStorage.getSharedCustomSoundNames();
        resourcePackStorage.putRuntimeData(
                CUSTOM_SOUNDS_KEY, sharedNames != null
                        ? sharedNames : Set.copyOf(this.collectCustomSoundNames(resourcePackStorage, null)));
    }

    @Override
    public ResourcePackRewriter.RuntimeDataScope runtimeDataScope() {
        return ResourcePackRewriter.RuntimeDataScope.SHARED;
    }

    private Set<String> collectCustomSoundNames(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        final SoundDefinitions sounds = resourcePackStorage.getSounds();
        final Set<String> customSoundNames = new HashSet<>();
        final JsonObject javaSoundsJson = new JsonObject();

        for (Map.Entry<String, SoundDefinitions.SoundDefinition> entry : sounds.soundDefinitions().entrySet()) {
            final String soundName = entry.getKey();

            // Skip sounds that already have a Java mapping
            if (BedrockProtocol.MAPPINGS.getBedrockToJavaSounds() != null
                    && BedrockProtocol.MAPPINGS.getBedrockToJavaSounds().containsKey(soundName)) {
                continue;
            }

            final SoundDefinitions.SoundDefinition definition = entry.getValue();
            if (definition.soundFiles().isEmpty()) {
                continue;
            }

            // Copy .ogg files from Bedrock packs and build Java sounds.json entry
            final JsonArray javaSoundEntries = new JsonArray();
            boolean hasAnyFile = false;

            for (SoundDefinitions.SoundFile soundFile : definition.soundFiles()) {
                final String bedrockOggPath = soundFile.path() + ".ogg";
                final String javaOggPath = "assets/bedrock/sounds/" + soundFile.path() + ".ogg";

                // Search through pack stack for the .ogg file
                boolean found = false;
                for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
                    if (pack.content().contains(bedrockOggPath)) {
                        if (javaContent != null) {
                            javaContent.copyFrom(pack.content(), bedrockOggPath, javaOggPath);
                        }
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    continue;
                }

                hasAnyFile = true;
                if (javaContent != null) {
                    final JsonObject soundEntry = new JsonObject();
                    // Java sounds.json references sounds relative to assets/<namespace>/sounds/
                    // The "name" field is <namespace>:<path_without_sounds_prefix_and_extension>
                    soundEntry.addProperty("name", "bedrock:" + soundFile.path());
                    if (soundFile.volume() != 1F) {
                        soundEntry.addProperty("volume", soundFile.volume());
                    }
                    if (soundFile.pitch() != 1F) {
                        soundEntry.addProperty("pitch", soundFile.pitch());
                    }
                    javaSoundEntries.add(soundEntry);
                }
            }

            if (hasAnyFile) {
                if (javaContent != null) {
                    final JsonObject soundDef = new JsonObject();
                    soundDef.add("sounds", javaSoundEntries);
                    javaSoundsJson.add(soundName, soundDef);
                }
                customSoundNames.add(soundName);
            }
        }

        if (javaContent != null && !javaSoundsJson.isEmpty()) {
            javaContent.putJson("assets/bedrock/sounds.json", javaSoundsJson);
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Added " + customSoundNames.size() + " custom sound(s) to Java resource pack");
        }

        return customSoundNames;
    }

    /** Computes stack-derived sound names while runtime construction still owns its temporary pack stack. */
    public static Set<String> findCustomSoundNames(final SoundDefinitions sounds,
                                                   final Collection<ResourcePack> packsTopToBottom) {
        final Set<String> customSoundNames = new HashSet<>();
        soundDefinitions:
        for (Map.Entry<String, SoundDefinitions.SoundDefinition> entry : sounds.soundDefinitions().entrySet()) {
            if (BedrockProtocol.MAPPINGS.getBedrockToJavaSounds() != null
                    && BedrockProtocol.MAPPINGS.getBedrockToJavaSounds().containsKey(entry.getKey())) continue;
            for (SoundDefinitions.SoundFile soundFile : entry.getValue().soundFiles()) {
                final String path = soundFile.path() + ".ogg";
                for (ResourcePack pack : packsTopToBottom) {
                    if (pack.content().contains(path)) {
                        customSoundNames.add(entry.getKey());
                        continue soundDefinitions;
                    }
                }
            }
        }
        return Set.copyOf(customSoundNames);
    }

}
