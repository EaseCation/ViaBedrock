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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Wire layout for NetEase ConfirmSkin (packet 228 / 0xE4).
 * <p>
 * MOT {@code ConfirmSkinPacket.encode()} writes an unsigned-varint array of
 * {@code {bool valid, UUID LE, byte[] skinBytes}} and then, in two extra passes,
 * {@code uidStr} and {@code geoStr} for every entry. {@code skinBytes} is raw
 * RGBA ({@code width * height * 4}), not the official IMAGE type that prefixes
 * width/height. Runtime dumps of play.mcscode.com match this layout, not
 * {@code SkinType}.
 */
public final class ConfirmSkinLayout {

    public static final String DEFAULT_RESOURCE_PATCH = "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}";
    public static final String SLIM_RESOURCE_PATCH = "{\"geometry\":{\"default\":\"geometry.humanoid.customSlim\"}}";

    private static final int[][] KNOWN_SKIN_SIZES = {
            {64, 32},
            {64, 64},
            {128, 64},
            {128, 128},
            {256, 256}
    };

    private ConfirmSkinLayout() {
    }

    public record Entry(boolean valid, UUID uuid, byte[] skinBytes, String uidStr, String geoStr) {
    }

    public record SkinSize(int width, int height) {
    }

    public static List<Entry> readPacket(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final boolean[] valid = new boolean[count];
        final UUID[] uuids = new UUID[count];
        final byte[][] skinBytes = new byte[count][];
        for (int i = 0; i < count; i++) {
            valid[i] = wrapper.read(Types.BOOLEAN);
            uuids[i] = wrapper.read(BedrockTypes.UUID);
            skinBytes[i] = wrapper.read(BedrockTypes.BYTE_ARRAY);
        }
        final String[] uidStr = new String[count];
        for (int i = 0; i < count; i++) {
            uidStr[i] = wrapper.read(BedrockTypes.STRING);
        }
        final String[] geoStr = new String[count];
        for (int i = 0; i < count; i++) {
            geoStr[i] = wrapper.read(BedrockTypes.STRING);
        }
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(valid[i], uuids[i], skinBytes[i], uidStr[i], geoStr[i]));
        }
        return entries;
    }

    public static List<Entry> readPacket(final ByteBuf buffer) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer);
        final boolean[] valid = new boolean[count];
        final UUID[] uuids = new UUID[count];
        final byte[][] skinBytes = new byte[count][];
        for (int i = 0; i < count; i++) {
            valid[i] = buffer.readBoolean();
            uuids[i] = BedrockTypes.UUID.read(buffer);
            skinBytes[i] = BedrockTypes.BYTE_ARRAY.read(buffer);
        }
        final String[] uidStr = new String[count];
        for (int i = 0; i < count; i++) {
            uidStr[i] = BedrockTypes.STRING.read(buffer);
        }
        final String[] geoStr = new String[count];
        for (int i = 0; i < count; i++) {
            geoStr[i] = BedrockTypes.STRING.read(buffer);
        }
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(valid[i], uuids[i], skinBytes[i], uidStr[i], geoStr[i]));
        }
        return entries;
    }

    public static void writePacket(final ByteBuf buffer, final List<Entry> entries) {
        BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, entries.size());
        for (Entry entry : entries) {
            buffer.writeBoolean(entry.valid());
            BedrockTypes.UUID.write(buffer, entry.uuid());
            BedrockTypes.BYTE_ARRAY.write(buffer, entry.skinBytes() != null ? entry.skinBytes() : new byte[0]);
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.uidStr() != null ? entry.uidStr() : "");
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.geoStr() != null ? entry.geoStr() : "");
        }
    }

    public static SkinSize inferSkinSize(final int byteLength) {
        if (byteLength <= 0 || (byteLength & 3) != 0) {
            return null;
        }
        final int pixels = byteLength / 4;
        for (int[] size : KNOWN_SKIN_SIZES) {
            if (size[0] * size[1] == pixels) {
                return new SkinSize(size[0], size[1]);
            }
        }
        final int square = (int) Math.round(Math.sqrt(pixels));
        if (square > 0 && square * square == pixels) {
            return new SkinSize(square, square);
        }
        return null;
    }

    public static BufferedImage imageFromRgba(final byte[] data) {
        if (data == null) {
            return null;
        }
        final SkinSize size = inferSkinSize(data.length);
        if (size == null) {
            return null;
        }
        final BufferedImage image = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size.height(); y++) {
            for (int x = 0; x < size.width(); x++) {
                final int index = (y * size.width() + x) * 4;
                final int argb = ((data[index + 3] & 0xFF) << 24)
                        | ((data[index] & 0xFF) << 16)
                        | ((data[index + 1] & 0xFF) << 8)
                        | (data[index + 2] & 0xFF);
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    public static SkinData toSkinData(final Entry entry) {
        final BufferedImage image = imageFromRgba(entry.skinBytes());
        final String uid = entry.uidStr() != null && !entry.uidStr().isEmpty()
                ? entry.uidStr()
                : (entry.uuid() != null ? entry.uuid().toString() : "netease-unknown");
        final String geo = entry.geoStr() != null ? entry.geoStr() : "";
        final boolean slim = isSlimGeometry(geo);
        final String resourcePatch = slim ? SLIM_RESOURCE_PATCH : DEFAULT_RESOURCE_PATCH;
        return new SkinData(
                uid,
                "",
                resourcePatch,
                image,
                Collections.emptyList(),
                null,
                geo,
                "",
                "",
                false,
                false,
                false,
                true,
                "",
                uid,
                slim ? "slim" : "wide",
                "#0",
                Collections.emptyList(),
                Collections.emptyList(),
                true
        );
    }

    /**
     * MOT ConfirmSkin {@code geoStr} is often a full {@code minecraft:geometry} document
     * that mentions both {@code custom} and {@code customSlim}. Substring checks therefore
     * mis-label Steve skins as Alex.
     */
    public static boolean isSlimGeometry(final String geo) {
        if (geo == null || geo.isBlank()) {
            return false;
        }
        final String trimmed = geo.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return isSlimGeometryName(trimmed);
        }
        try {
            final JsonElement root = JsonParser.parseString(trimmed);
            final Float armWidth = minArmWidth(root);
            if (armWidth != null) {
                return armWidth < 3.5f;
            }
            final String identifier = unambiguousHumanoidIdentifier(root);
            if (isSlimGeometryName(identifier)) {
                return true;
            }
            if (isWideGeometryName(identifier)) {
                return false;
            }
        } catch (final Exception ignored) {
        }
        return containsQuotedSlimIdentifier(trimmed) && !containsQuotedWideIdentifier(trimmed);
    }

    static boolean isSlimGeometryName(final String name) {
        return name != null && (name.equals("geometry.humanoid.customSlim") || name.startsWith("geometry.humanoid.customSlim."));
    }

    static boolean isWideGeometryName(final String name) {
        return name != null
                && (name.equals("geometry.humanoid.custom") || name.startsWith("geometry.humanoid.custom."))
                && !isSlimGeometryName(name);
    }

    private static boolean containsQuotedSlimIdentifier(final String geo) {
        return geo.contains("\"geometry.humanoid.customSlim\"") || geo.contains("'geometry.humanoid.customSlim'");
    }

    private static boolean containsQuotedWideIdentifier(final String geo) {
        return geo.contains("\"geometry.humanoid.custom\"") || geo.contains("'geometry.humanoid.custom'");
    }

    private static String unambiguousHumanoidIdentifier(final JsonElement element) {
        boolean slim = false;
        boolean wide = false;
        String lastSlim = null;
        String lastWide = null;
        for (final String identifier : collectIdentifiers(element, new ArrayList<>())) {
            if (isSlimGeometryName(identifier)) {
                slim = true;
                lastSlim = identifier;
            } else if (isWideGeometryName(identifier)) {
                wide = true;
                lastWide = identifier;
            }
        }
        if (slim && !wide) {
            return lastSlim;
        }
        if (wide && !slim) {
            return lastWide;
        }
        return null;
    }

    private static List<String> collectIdentifiers(final JsonElement element, final List<String> out) {
        if (element == null || element.isJsonNull()) {
            return out;
        }
        if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                collectIdentifiers(child, out);
            }
            return out;
        }
        if (!element.isJsonObject()) {
            return out;
        }
        final JsonObject object = element.getAsJsonObject();
        if (object.has("minecraft:geometry")) {
            collectIdentifiers(object.get("minecraft:geometry"), out);
        }
        if (object.has("description") && object.get("description").isJsonObject()) {
            final JsonObject description = object.getAsJsonObject("description");
            if (description.has("identifier") && description.get("identifier").isJsonPrimitive()) {
                out.add(description.get("identifier").getAsString());
            }
        }
        if (object.has("identifier") && object.get("identifier").isJsonPrimitive() && !object.has("description")) {
            out.add(object.get("identifier").getAsString());
        }
        return out;
    }

    private static Float minArmWidth(final JsonElement element) {
        final float width = scanArmWidth(element);
        return width == Float.MAX_VALUE ? null : width;
    }

    private static float scanArmWidth(final JsonElement element) {
        float minWidth = Float.MAX_VALUE;
        if (element == null || element.isJsonNull()) {
            return minWidth;
        }
        if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                minWidth = Math.min(minWidth, scanArmWidth(child));
            }
            return minWidth;
        }
        if (!element.isJsonObject()) {
            return minWidth;
        }
        final JsonObject object = element.getAsJsonObject();
        if (object.has("minecraft:geometry")) {
            minWidth = Math.min(minWidth, scanArmWidth(object.get("minecraft:geometry")));
        }
        if (object.has("bones")) {
            minWidth = Math.min(minWidth, scanArmWidth(object.get("bones")));
        }
        final String name = object.has("name") && object.get("name").isJsonPrimitive()
                ? object.get("name").getAsString()
                : "";
        if (isArmBone(name) && object.has("cubes") && object.get("cubes").isJsonArray()) {
            final JsonArray cubes = object.getAsJsonArray("cubes");
            for (final JsonElement cubeElement : cubes) {
                if (!cubeElement.isJsonObject()) {
                    continue;
                }
                final JsonObject cube = cubeElement.getAsJsonObject();
                if (!cube.has("size") || !cube.get("size").isJsonArray() || cube.getAsJsonArray("size").isEmpty()) {
                    continue;
                }
                final float width = Math.abs(cube.getAsJsonArray("size").get(0).getAsFloat());
                if (width > 0.1f && width < minWidth) {
                    minWidth = width;
                }
            }
        }
        return minWidth;
    }

    private static boolean isArmBone(final String name) {
        if (name == null) {
            return false;
        }
        final String lower = name.toLowerCase(Locale.ROOT).replace("_", "");
        return lower.equals("leftarm") || lower.equals("rightarm");
    }
}
