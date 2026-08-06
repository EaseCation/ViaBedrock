package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaCustomPayloadForwarderTest {

    @Test
    void preservesChannelAndBinaryPayloadInTheVersionedEnvelope() {
        final byte[] channel = "minecraft:storemod".getBytes(StandardCharsets.UTF_8);
        final byte[] payload = new byte[]{0, 1, -1, 42};

        final String encoded = JavaCustomPayloadForwarder.encodeEnvelope(channel, payload);
        final JavaCustomPayloadForwarder.DecodedEnvelope decoded = JavaCustomPayloadForwarder.decodeEnvelope(encoded);

        assertEquals("minecraft:storemod", decoded.channel());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void rejectsMalformedEnvelopeBeforeItCanReachTheBackend() {
        assertThrows(IllegalArgumentException.class,
                () -> JavaCustomPayloadForwarder.decodeEnvelope("AA"));
    }

    @Test
    void copiesPayloadWithoutAdvancingTheOriginalPacketBuffer() {
        final ByteBuf input = Unpooled.wrappedBuffer(new byte[]{7, 8, 9});

        final byte[] copied = JavaCustomPayloadForwarder.copyRemainingPayload(input);

        assertArrayEquals(new byte[]{7, 8, 9}, copied);
        assertEquals(0, input.readerIndex());
    }
}
