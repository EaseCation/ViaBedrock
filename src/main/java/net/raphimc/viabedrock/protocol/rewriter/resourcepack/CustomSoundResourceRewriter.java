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
import net.raphimc.viabedrock.api.model.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.model.resourcepack.SoundDefinitions;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class CustomSoundResourceRewriter implements ResourcePackRewriter.Rewriter {

    public static final String CUSTOM_SOUNDS_KEY = "custom_sound_names";

    @Override
    public void apply(final ResourcePacksStorage resourcePacksStorage, final ResourcePack.Content javaContent) {
        final SoundDefinitions sounds = resourcePacksStorage.getSounds();
        final Set<String> customSoundNames = new HashSet<>();
        final JsonObject javaSoundsJson = new JsonObject();

        for (Map.Entry<String, SoundDefinitions.SoundDefinition> entry : sounds.soundDefinitions().entrySet()) {
            final String soundName = entry.getKey();

            // Skip sounds that already have a Java mapping
            if (BedrockProtocol.MAPPINGS.getBedrockToJavaSounds().containsKey(soundName)) {
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
                for (ResourcePack pack : resourcePacksStorage.getPackStackTopToBottom()) {
                    if (pack.content().contains(bedrockOggPath)) {
                        javaContent.copyFrom(pack.content(), bedrockOggPath, javaOggPath);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    continue;
                }

                hasAnyFile = true;
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

            if (hasAnyFile) {
                final JsonObject soundDef = new JsonObject();
                soundDef.add("sounds", javaSoundEntries);
                javaSoundsJson.add(soundName, soundDef);
                customSoundNames.add(soundName);
            }
        }

        if (!javaSoundsJson.isEmpty()) {
            javaContent.putJson("assets/bedrock/sounds.json", javaSoundsJson);
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Added " + customSoundNames.size() + " custom sound(s) to Java resource pack");
        }

        resourcePacksStorage.getConverterData().put(CUSTOM_SOUNDS_KEY, customSoundNames);
    }

}
