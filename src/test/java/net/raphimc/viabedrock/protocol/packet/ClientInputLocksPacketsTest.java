package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AbilitiesIndex;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.data.enums.java.InputFlag;
import net.raphimc.viabedrock.protocol.model.Position2f;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.JavaPlayerStateStorage;
import net.raphimc.viabedrock.protocol.storage.PacketSyncStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClientInputLocksPacketsTest {
    @Test
    void movementLockReachesTheModAndSuppressesKeysWithoutSuppressingServerMotionOrViewRotation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.lock(4);
            PacketWrapper message = fixture.last(ClientboundPackets26_1.CUSTOM_PAYLOAD);
            assertEquals("viabedrockutility:data", message.get(Types.STRING, 0));
            assertEquals(11, message.get(Types.INT, 0));
            assertEquals(4, message.get(Types.INT, 1));

            fixture.move(fixture.client.runtimeId(), new Position3f(12.5F, 66.62F, -4.5F), Position3f.ZERO, (byte) 2);
            final PacketWrapper correction = fixture.last(ClientboundPackets26_1.PLAYER_POSITION);
            assertTrue(fixture.packets.indexOf(message) < fixture.packets.indexOf(correction));
            fixture.client.confirmTeleport(correction.get(Types.VAR_INT, 0));
            fixture.client.abilities().getOrCreateCacheLayer().setAbility(AbilitiesIndex.Flying, true);

            final PacketWrapper moved = fixture.packet(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT);
            moved.write(Types.DOUBLE, 14.5D);
            moved.write(Types.DOUBLE, 66D);
            moved.write(Types.DOUBLE, -4.5D);
            moved.write(Types.FLOAT, 75F);
            moved.write(Types.FLOAT, 15F);
            moved.write(Types.UNSIGNED_BYTE, (short) 0);
            moved.resetReader();
            fixture.protocol.handle(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT, moved);
            fixture.input((byte) 17); // forward and jump
            fixture.client.addAuthInputData(PlayerAuthInputPacket_InputData.MissedSwing);

            final PacketWrapper auth = fixture.tick();
            assertEquals(new Position3f(14.5F, 67.62F, -4.5F), auth.get(BedrockTypes.POSITION_3F, 0));
            assertEquals(new Position3f(2F, 1F, 0F), auth.get(BedrockTypes.POSITION_3F, 1));
            assertEquals(75F, auth.get(BedrockTypes.FLOAT_LE, 1));
            assertEquals(15F, auth.get(BedrockTypes.FLOAT_LE, 0));
            assertEquals(new Position2f(0F, 0F), auth.get(BedrockTypes.POSITION_2F, 0));
            assertEquals(new Position2f(0F, 0F), auth.get(BedrockTypes.POSITION_2F, 2));
            final BigInteger flags = auth.get(BedrockTypes.UNSIGNED_VAR_BIG_INTEGER, 0);
            assertFalse(flags.testBit(PlayerAuthInputPacket_InputData.Up.getValue()));
            assertFalse(flags.testBit(PlayerAuthInputPacket_InputData.JumpDown.getValue()));
            assertTrue(flags.testBit(PlayerAuthInputPacket_InputData.MissedSwing.getValue()));
            assertFalse(fixture.client.hasEntityFlag(ActorFlags.NOAI));
        }
    }

    @Test
    void cameraOnlyLockPreservesKeysAndUnlockMessageRestoresMovement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.lock(2);
            fixture.input((byte) 17);
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
            assertTrue(fixture.client.inputFlags().contains(InputFlag.JUMP));
            assertEquals(2, fixture.last(ClientboundPackets26_1.CUSTOM_PAYLOAD).get(Types.INT, 1));

            fixture.lock(4);
            fixture.input((byte) 17);
            assertTrue(fixture.client.inputFlags().isEmpty());
            fixture.lock(0);
            fixture.input((byte) 17);
            assertEquals(0, fixture.last(ClientboundPackets26_1.CUSTOM_PAYLOAD).get(Types.INT, 1));
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
            assertTrue(fixture.client.inputFlags().contains(InputFlag.JUMP));
        }
    }

    private static final class Fixture extends OtherPlayerCarryPacketsTest.Fixture {
        final Field configField = ViaBedrock.class.getDeclaredField("config");
        final Object previousConfig;

        Fixture() throws Exception {
            super();
            configField.setAccessible(true);
            previousConfig = configField.get(null);
            configField.set(null, Proxy.newProxyInstance(ViaBedrockConfig.class.getClassLoader(), new Class[]{ViaBedrockConfig.class}, (proxy, method, args) -> {
                if (method.getName().equals("getMovementWatchdogMode")) return ViaBedrockConfig.MovementWatchdogMode.OFF;
                throw new UnsupportedOperationException(method.getName());
            }));
            ClientPlayerPackets.register(protocol);
            final ChannelStorage channels = new ChannelStorage();
            channels.addChannels(Set.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL));
            user.put(channels);
            user.put(new JavaPlayerStateStorage());
            user.put(new PacketSyncStorage(user));
            client.setInitiallySpawned();
        }

        void lock(int flags) throws Exception {
            final PacketWrapper packet = packet(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS);
            packet.write(BedrockTypes.UNSIGNED_VAR_INT, flags);
            packet.resetReader();
            protocol.handle(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS, packet);
            assertTrue(packet.isCancelled());
        }

        void input(byte flags) throws Exception {
            final PacketWrapper packet = packet(ServerboundPackets26_1.PLAYER_INPUT);
            packet.write(Types.BYTE, flags);
            packet.resetReader();
            protocol.handle(ServerboundPackets26_1.PLAYER_INPUT, packet);
        }

        PacketWrapper tick() throws Exception {
            final PacketWrapper packet = packet(ServerboundPackets26_1.CLIENT_TICK_END);
            protocol.handle(ServerboundPackets26_1.CLIENT_TICK_END, packet);
            assertFalse(packet.isCancelled());
            return packet;
        }

        @Override
        public void close() throws Exception {
            configField.set(null, previousConfig);
            super.close();
        }
    }
}
