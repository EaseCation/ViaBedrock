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
package net.raphimc.viabedrock.protocol.provider;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.providers.Provider;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.api.model.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.modinterface.BedrockSkinUtilityInterface;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.MemoryTier;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.BuildPlatform;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GraphicsMode;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InputMode;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.UIProfile;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.storage.AuthData;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.ClientSettingsStorage;
import net.raphimc.viabedrock.protocol.storage.HandshakeStorage;
import net.raphimc.viabedrock.protocol.types.primitive.ImageType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SkinProvider implements Provider {

    public Map<String, Object> getClientPlayerSkin(final UserConnection user) {
        final HandshakeStorage handshakeStorage = user.get(HandshakeStorage.class);
        final AuthData authData = user.get(AuthData.class);
        final Map<String, Object> claims = new HashMap<>();

        { // Skin claims
            final ResourcePack.Content skinPackContent = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks().get("vanilla_skin_pack").content();
            final BufferedImage skin = skinPackContent.getImage("steve.png").getImage();
            final JsonObject skinGeometry = skinPackContent.getSortedJson("geometry.json");

            claims.put("SkinId", UUID.randomUUID().toString());
            claims.put("SkinData", Base64.getEncoder().encodeToString(ImageType.getImageData(skin)));
            claims.put("SkinImageWidth", skin.getWidth());
            claims.put("SkinImageHeight", skin.getHeight());
            claims.put("SkinGeometryData", Base64.getEncoder().encodeToString(skinGeometry.toString().getBytes(StandardCharsets.UTF_8)));
            claims.put("SkinGeometryDataEngineVersion", Base64.getEncoder().encodeToString("0.0.0".getBytes(StandardCharsets.UTF_8)));
            claims.put("SkinResourcePatch", Base64.getEncoder().encodeToString("{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8)));
            claims.put("SkinAnimationData", "");
            claims.put("SkinColor", "#0");
            claims.put("PremiumSkin", false);
            claims.put("PersonaSkin", false);
            claims.put("TrustedSkin", false);
            claims.put("OverrideSkin", false);
            claims.put("ArmSize", "wide");
            claims.put("AnimatedImageData", new ArrayList<>());
            claims.put("PersonaPieces", new ArrayList<>());
            claims.put("PieceTintColors", new ArrayList<>());
        }
        { // Cape claims
            claims.put("CapeId", "");
            claims.put("CapeData", "");
            claims.put("CapeImageWidth", 0);
            claims.put("CapeImageHeight", 0);
            claims.put("CapeOnClassicSkin", false);
        }
        { // Session claims
            claims.put("ServerAddress", handshakeStorage.hostname() + ":" + handshakeStorage.port());
            claims.put("ThirdPartyName", user.getProtocolInfo().getUsername());
        }
        { // Client claims
            claims.put("GameVersion", ProtocolConstants.BEDROCK_VERSION_NAME);
            final ClientSettingsStorage clientSettings = user.get(ClientSettingsStorage.class);
            claims.put("LanguageCode", convertLocaleFormat(clientSettings != null ? clientSettings.locale() : "en_us"));
            claims.put("GraphicsMode", GraphicsMode.Fancy.getValue());
            claims.put("GuiScale", -1);
            claims.put("UIProfile", UIProfile.Classic.getValue());
            claims.put("ClientRandomId", ThreadLocalRandom.current().nextLong()); // ?
            claims.put("SelfSignedId", UUID.randomUUID().toString()); // ?
            claims.put("IsEditorMode", false);
        }
        { // Device claims
            claims.put("DeviceId", authData.getDeviceId().toString().replace("-", ""));
            claims.put("DeviceModel", "");
            claims.put("DeviceOS", BuildPlatform.Google.getValue());
            claims.put("CurrentInputMode", InputMode.Mouse.getValue());
            claims.put("DefaultInputMode", InputMode.Touch.getValue());
        }
        { // Hardware claims
            claims.put("MemoryTier", MemoryTier.SuperHigh.ordinal());
            claims.put("MaxViewDistance", 96);
            claims.put("CompatibleWithClientSideChunkGen", false);
        }
        { // Platform claims
            claims.put("PlatformType", 0);
            claims.put("PlatformOfflineId", "");
            claims.put("PlatformOnlineId", "");
        }

        { // ViaProxy auth token
            final String authSecret = ViaBedrock.getConfig().getViaProxyAuthSecret();
            if (authSecret != null && !authSecret.isEmpty()) {
                final long timestamp = System.currentTimeMillis() / 1000;
                final String payload = user.getProtocolInfo().getUuid() + ":" + user.getProtocolInfo().getUsername() + ":" + timestamp;
                final String hmac = computeHmacSha256(authSecret, payload);
                claims.put("ViaProxyAuthToken", hmac + ":" + timestamp);
            }
        }

        return claims;
    }

    private static String computeHmacSha256(final String secret, final String data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static String convertLocaleFormat(final String locale) {
        final int underscoreIndex = locale.indexOf('_');
        if (underscoreIndex > 0 && underscoreIndex < locale.length() - 1) {
            return locale.substring(0, underscoreIndex + 1) + locale.substring(underscoreIndex + 1).toUpperCase();
        }
        return locale;
    }

    public void setSkin(final UserConnection user, final UUID playerUuid, final SkinData skin) {
        final ChannelStorage channelStorage = user.get(ChannelStorage.class);
        final boolean hasVBU = channelStorage.hasChannel(ViaBedrockUtilityInterface.CHANNEL);
        final boolean hasBSU = channelStorage.hasChannel(BedrockSkinUtilityInterface.CHANNEL);
        ViaBedrock.getPlatform().getLogger().info("setSkin: uuid=" + playerUuid
                + " persona=" + skin.persona()
                + " skinData=" + (skin.skinData() != null ? skin.skinData().getWidth() + "x" + skin.skinData().getHeight() : "null")
                + " hasVBU=" + hasVBU + " hasBSU=" + hasBSU);
        if (hasVBU) {
            ViaBedrockUtilityInterface.sendSkin(user, playerUuid, skin);
        } else if (hasBSU) {
            BedrockSkinUtilityInterface.sendSkin(user, playerUuid, skin);
        }
    }

}
