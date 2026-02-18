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

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Copies Bedrock UI textures (textures/ui/, textures/gui/) and their nine-slice JSON
 * definitions into the Java resource pack so that ModUIClient can render HUD elements.
 * Always active — the extra files have no side effects for clients without ModUIClient.
 * (Cannot gate on ModUIClient channel because resource pack conversion runs in
 * Configuration phase, before ModUIClient registers its channel in Play phase.)
 */
public class UITextureResourceRewriter implements ResourcePackRewriter.Rewriter {

    private static final String[] TEXTURE_PREFIXES = {"textures/ui/", "textures/gui/"};
    private static final String[] EXTENSIONS = {".png", ".json"};

    @Override
    public void apply(final ResourcePacksStorage resourcePacksStorage, final ResourcePack.Content javaContent) {
        int count = 0;
        for (final ResourcePack pack : resourcePacksStorage.getPackStackTopToBottom()) {
            final ResourcePack.Content bedrockContent = pack.content();

            for (final String prefix : TEXTURE_PREFIXES) {
                for (final String extension : EXTENSIONS) {
                    final List<String> files = bedrockContent.getFilesDeep(prefix, extension);
                    for (final String bedrockPath : files) {
                        // Java resource identifiers must be lowercase
                        final String javaPath = "assets/minecraft/" + bedrockPath.toLowerCase(Locale.ROOT);
                        // Higher-priority packs take precedence (top-to-bottom order)
                        if (!javaContent.contains(javaPath)) {
                            javaContent.copyFrom(bedrockContent, bedrockPath, javaPath);
                            count++;
                        }
                    }
                }
            }
        }

        if (count > 0) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Copied " + count + " UI texture files for ModUIClient");
        }
    }

}
