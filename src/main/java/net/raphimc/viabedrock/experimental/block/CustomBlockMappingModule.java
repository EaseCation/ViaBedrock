/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 */
package net.raphimc.viabedrock.experimental.block;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.ViaBedrock;

import java.util.Set;

/**
 * BedrockLoader custom block mapping sync entry point.
 * The old FabricRock disk JSON/global mapping path is intentionally disabled; mappings are now per UserConnection.
 */
public class CustomBlockMappingModule implements FeatureModule {

    @Override
    public void onChannelRegistered(final UserConnection user, final Set<String> channels) {
        if (channels.contains(CustomMappingSyncStorage.CHANNEL)) {
            ViaBedrock.getPlatform().getLogger().info("BedrockLoader custom mapping sync channel registered by Java client");
            user.get(CustomMappingSyncStorage.class).beginRequestIfNeeded();
        }
    }

    @Override
    public boolean handleCustomPayload(final String channel, final PacketWrapper wrapper) {
        if (!CustomMappingSyncStorage.CHANNEL.equals(channel)) {
            return false;
        }
        final byte[] payload = wrapper.read(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA);
        wrapper.user().get(CustomMappingSyncStorage.class).handlePayload(payload);
        return true;
    }
}
