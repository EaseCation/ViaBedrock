package net.raphimc.viabedrock.api.model.entity;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CustomEntityModelRequestTest {
    @BeforeAll
    static void initializeTypes() {
        assertNotNull(StructuredDataKey.CUSTOM_DATA);
    }

    @Test
    void birthWaitsForMetadataAndSendsOneCompleteSnapshotAfterHostSpawn() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CustomEntity entity = f.entity(1);
            entity.setPosition(Position3f.ZERO);
            assertTrue(f.models().isEmpty(), "No provisional model before initial metadata");
            f.packets.add(new PacketWrapperImpl(ClientboundPackets26_1.ADD_ENTITY, null, f.user));
            entity.updateEntityData(new EntityData[]{scale(2F), data(ActorDataIDs.SKIN_ID, 7),
                    data(ActorDataIDs.VARIANT, 3), data(ActorDataIDs.MARK_VARIANT, 4),
                    flags(ActorDataIDs.RESERVED_0, Long.MIN_VALUE), flags(ActorDataIDs.RESERVED_092, 1L)});
            assertEquals(1, f.models().size());
            PacketWrapper model = f.models().getFirst();
            assertEquals(List.of(ClientboundPackets26_1.ADD_ENTITY, ClientboundPackets26_1.CUSTOM_PAYLOAD,
                    ClientboundPackets26_1.SET_ENTITY_DATA), f.packets.stream().map(PacketWrapper::getPacketType).toList());
            assertEquals(entity.javaUuid(), model.get(Types.UUID, 0));
            ByteBuf encoded = Unpooled.buffer();
            try {
                model.writeToBuffer(encoded);
                assertEquals(ClientboundPackets26_1.CUSTOM_PAYLOAD.getId(), Types.VAR_INT.readPrimitive(encoded));
                assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(encoded));
                assertEquals(1, encoded.readInt()); // Existing MODEL_REQUEST ordinal.
                assertEquals(entity.javaUuid(), Types.UUID.read(encoded));
                assertEquals("test:model", encoded.readCharSequence(encoded.readInt(), StandardCharsets.UTF_8).toString());
                assertTrue(encoded.readBoolean());
                assertEquals(Long.MIN_VALUE, encoded.readLong());
                assertTrue(encoded.readBoolean());
                assertEquals(1L, encoded.readLong());
                assertTrue(encoded.readBoolean());
                assertEquals(3, encoded.readInt());
                assertTrue(encoded.readBoolean());
                assertEquals(4, encoded.readInt());
                assertTrue(encoded.readBoolean());
                assertEquals(7, encoded.readInt());
                assertTrue(encoded.readBoolean());
                assertEquals(2F, encoded.readFloat());
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }
            assertEquals(List.of(1.2F, 3.6F), f.collisionValues());
        }
    }

    @Test
    void emptyInitialMetadataStillSendsModelOnce() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CustomEntity entity = f.spawn(1, new EntityData[0]);
            assertEquals(1, f.models().size());
            for (int i = 0; i < 6; i++) assertFalse(f.models().getFirst().get(Types.BOOLEAN, i));
            entity.updateEntityData(new EntityData[0]);
            entity.setPosition(new Position3f(1F, 2F, 3F));
            assertEquals(1, f.models().size());
        }
    }

    @Test
    void identicalAndCollisionOnlyChangesDoNotResendModels() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CustomEntity entity = f.spawn(1, scale(2F));
            f.packets.clear();
            entity.updateEntityData(new EntityData[]{scale(2F)});
            entity.updateEntityData(new EntityData[]{floatData(ActorDataIDs.RESERVED_053, 3F),
                    floatData(ActorDataIDs.RESERVED_054, 4F)});
            assertTrue(f.models().isEmpty());
            assertEquals(List.of(6F, 8F, 6F, 8F), f.collisionValues());
        }
    }

    @Test
    void everyWireFieldChangeIsSentOnceAndMutatedMetadataIsNotAliased() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CustomEntity entity = f.spawn(1, new EntityData[0]);
            f.packets.clear();
            List<EntityData> updates = List.of(flags(ActorDataIDs.RESERVED_0, 1L),
                    flags(ActorDataIDs.RESERVED_092, Long.MIN_VALUE), data(ActorDataIDs.VARIANT, 0),
                    data(ActorDataIDs.MARK_VARIANT, 2), data(ActorDataIDs.SKIN_ID, 3), scale(2F));
            for (int i = 0; i < updates.size(); i++) {
                entity.updateEntityData(new EntityData[]{updates.get(i)});
                assertEquals(i + 1, f.models().size());
                entity.updateEntityData(new EntityData[]{updates.get(i)});
                assertEquals(i + 1, f.models().size());
            }
            EntityData scale = updates.getLast();
            scale.setValue(3F);
            entity.updateEntityData(new EntityData[]{scale});
            assertEquals(7, f.models().size());
            assertEquals(3F, f.models().getLast().get(Types.FLOAT, 0));
        }
    }

    @Test
    void entitiesAndReplacementLifetimesHaveIndependentSnapshots() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CustomEntity first = f.spawn(1, scale(1F));
            f.spawn(2, scale(1F));
            first.remove();
            CustomEntity replacement = f.spawn(1, scale(1F));
            assertEquals(3, f.models().size());
            assertNotEquals(first.javaUuid(), replacement.javaUuid());
        }
    }

    @Test
    void burstOf36BirthsProduces36CompleteSnapshotsInsteadOf108() throws Exception {
        try (Fixture f = new Fixture(true)) {
            for (int i = 0; i < 36; i++) {
                f.spawn(i, scale(1F), data(ActorDataIDs.SKIN_ID, i));
            }
            assertEquals(36, f.models().size());
            assertEquals(36, f.models().stream().map(p -> p.get(Types.UUID, 0)).distinct().count());
        }
    }

    @Test
    void failedSendDoesNotSuppressRetry() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CustomEntity entity = f.spawn(1, scale(1F));
            f.packets.clear();
            f.failNextModel = true;
            assertThrows(IllegalStateException.class, () -> entity.updateEntityData(new EntityData[]{scale(2F)}));
            entity.updateEntityData(new EntityData[]{scale(2F)});
            assertEquals(1, f.models().size());
            assertEquals(2F, f.models().getFirst().get(Types.FLOAT, 0));
        }
    }

    @Test
    void nonVbuClientKeepsCollisionUpdatesWithoutUtilityPayload() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.spawn(1, scale(2F));
            assertTrue(f.models().isEmpty());
            assertEquals(List.of(1.2F, 3.6F), f.collisionValues());
        }
    }

    private static EntityData data(ActorDataIDs id, int value) {
        return new EntityData(id.getValue(), EntityDataTypesBedrock.INT, value);
    }
    private static EntityData flags(ActorDataIDs id, long value) {
        return new EntityData(id.getValue(), EntityDataTypesBedrock.LONG, value);
    }
    private static EntityData floatData(ActorDataIDs id, float value) {
        return new EntityData(id.getValue(), EntityDataTypesBedrock.FLOAT, value);
    }
    private static EntityData scale(float value) { return floatData(ActorDataIDs.RESERVED_038, value); }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final List<PacketWrapper> packets = new ArrayList<>();
        final Map<Field, Object> previous = new LinkedHashMap<>();
        boolean failNextModel;

        Fixture(boolean vbu) throws Exception {
            ProtocolManager protocols = (ProtocolManager) Proxy.newProxyInstance(ProtocolManager.class.getClassLoader(),
                    new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("createPacketWrapper")) throw new UnsupportedOperationException(method.getName());
                        return new PacketWrapperImpl((PacketType) args[0], null, (UserConnection) args[2]) {
                            @Override
                            public void send(Class<? extends Protocol> protocol, boolean skip) {
                                if (getPacketType() == ClientboundPackets26_1.CUSTOM_PAYLOAD && failNextModel) {
                                    failNextModel = false;
                                    throw new IllegalStateException("Test send failure");
                                }
                                packets.add(this);
                            }
                        };
                    });
            ViaManager manager = (ViaManager) Proxy.newProxyInstance(ViaManager.class.getClassLoader(),
                    new Class[]{ViaManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getProtocolManager")) return protocols;
                        throw new UnsupportedOperationException(method.getName());
                    });
            replace(Via.class, "manager", manager);
            replace(ViaBedrock.class, "config", new ViaBedrockConfig(new File("unused-model-request-test.yml"), Logger.getAnonymousLogger()));
            replace(BedrockMappingData.class, "javaEntityDataFields", Map.of(EntityTypes1_21_11.INTERACTION,
                    List.of(EntityDataFields.WIDTH, EntityDataFields.HEIGHT)));
            replace(BedrockMappingData.class, "bedrockEntityDataTypes", Map.of());
            ChannelStorage channels = new ChannelStorage();
            if (vbu) channels.addChannels(List.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL));
            user.put(channels);
        }

        CustomEntity entity(int id) {
            return new CustomEntity(user, id, id, "test:model", id,
                    new EntityDefinitions.EntityDefinition("test:model", null));
        }
        CustomEntity spawn(int id, EntityData... metadata) {
            CustomEntity entity = entity(id);
            entity.setPosition(Position3f.ZERO);
            entity.updateEntityData(metadata);
            return entity;
        }
        List<PacketWrapper> models() {
            return packets.stream().filter(p -> p.getPacketType() == ClientboundPackets26_1.CUSTOM_PAYLOAD).toList();
        }
        List<Float> collisionValues() {
            return packets.getLast().get(VersionedTypes.V26_1.entityDataList, 0).stream()
                    .map(data -> data.<Float>value()).toList();
        }
        void replace(Class<?> type, String name, Object value) throws Exception {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            Object target = type == BedrockMappingData.class ? BedrockProtocol.MAPPINGS : null;
            previous.put(field, field.get(target));
            field.set(target, value);
        }
        @Override
        public void close() throws Exception {
            for (var entry : previous.entrySet()) {
                entry.getKey().set(entry.getKey().getDeclaringClass() == BedrockMappingData.class ? BedrockProtocol.MAPPINGS : null, entry.getValue());
            }
            channel.finishAndReleaseAll();
        }
    }
}
