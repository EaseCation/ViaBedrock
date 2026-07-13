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
package net.raphimc.viabedrock.protocol.data;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockArmorDataTest {

    private static final String DATA_ROOT = "/assets/viabedrock/data/bedrock/";

    @Test
    void vanillaArmorProtectionMatchesCurrentBedrockArmorTag() {
        final Map<String, Integer> protection = loadProtection();
        final JsonArray armorTag = loadJson("item_tags.json").getAsJsonArray("minecraft:is_armor");
        final Set<String> expectedArmor = new HashSet<>();
        for (JsonElement item : armorTag) {
            expectedArmor.add(item.getAsString());
        }
        expectedArmor.remove("minecraft:elytra");

        assertEquals(expectedArmor, protection.keySet());
        assertFalse(protection.containsKey("minecraft:elytra"));
        assertTrue(protection.values().stream().allMatch(value -> value >= 0 && value <= 20));
    }

    @Test
    void vanillaSetTotalsMatchBedrockProtection() {
        final Map<String, Integer> protection = loadProtection();

        assertEquals(7, setTotal(protection, "leather"));
        assertEquals(12, setTotal(protection, "chainmail"));
        assertEquals(10, setTotal(protection, "copper"));
        assertEquals(11, setTotal(protection, "golden"));
        assertEquals(15, setTotal(protection, "iron"));
        assertEquals(20, setTotal(protection, "diamond"));
        assertEquals(20, setTotal(protection, "netherite"));
        assertEquals(2, protection.get("minecraft:turtle_helmet"));
    }

    private static int setTotal(final Map<String, Integer> protection, final String material) {
        return protection.get("minecraft:" + material + "_helmet")
                + protection.get("minecraft:" + material + "_chestplate")
                + protection.get("minecraft:" + material + "_leggings")
                + protection.get("minecraft:" + material + "_boots");
    }

    private static Map<String, Integer> loadProtection() {
        final JsonObject json = loadJson("armor_protection.json");
        final Map<String, Integer> protection = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            assertTrue(entry.getValue().isJsonPrimitive());
            assertTrue(entry.getValue().getAsJsonPrimitive().isNumber());
            assertEquals(entry.getValue().getAsDouble(), Math.rint(entry.getValue().getAsDouble()));
            assertNull(protection.put(entry.getKey(), entry.getValue().getAsInt()), "Duplicate armor identifier " + entry.getKey());
        }
        return protection;
    }

    private static JsonObject loadJson(final String name) {
        final InputStream stream = BedrockArmorDataTest.class.getResourceAsStream(DATA_ROOT + name);
        assertNotNull(stream, "Missing test resource " + name);
        return GsonUtil.getGson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
    }

}
