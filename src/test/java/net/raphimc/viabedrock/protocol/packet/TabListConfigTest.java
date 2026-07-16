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
package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.ViaBedrockConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabListConfigTest {

    @Test
    void resolvesAllSupportedPlaceholders() {
        final String template = "%level_name% | %version% | %level_name%";

        assertEquals("Bedrock World | " + ViaBedrock.VERSION + " | Bedrock World",
                JoinPackets.resolveTabListPlaceholders(template, "Bedrock World"));
    }

    @Test
    void preservesEmptyTemplates() {
        assertEquals("", JoinPackets.resolveTabListPlaceholders("", "Bedrock World"));
    }

    @Test
    void loadsCustomTabListSettings(@TempDir final Path tempDir) throws IOException {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                tab-list:
                  enabled: false
                  header: "Custom %level_name%"
                  footer: ""
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertFalse(config.shouldSendTabList());
        assertEquals("Custom %level_name%", config.getTabListHeader());
        assertEquals("", config.getTabListFooter());
    }

    @Test
    void loadsSwordBlockingAnimationSetting(@TempDir final Path tempDir) throws IOException {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, "enable-sword-blocking-animation: true\n");

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertTrue(config.shouldEnableSwordBlockingAnimation());
    }

}
