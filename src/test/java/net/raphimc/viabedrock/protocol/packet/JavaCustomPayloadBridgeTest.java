package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaCustomPayloadBridgeTest {

    @Test
    void preservesChannelAndBinaryPayloadInBothDirections() {
        final byte[] channel = "easecation:launcher_commerce".getBytes(StandardCharsets.UTF_8);
        final byte[] payload = new byte[]{0, 1, -1, 42};

        final String encoded = JavaCustomPayloadBridge.encodeEnvelope(channel, payload);
        final JavaCustomPayloadBridge.DecodedPayload decoded = JavaCustomPayloadBridge.decodeEnvelope(encoded);

        assertEquals("easecation:launcher_commerce", decoded.channel());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void rejectsMalformedEnvelopeBeforeItCanReachEitherEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> JavaCustomPayloadBridge.decodeEnvelope("AA"));
    }

    @Test
    void copiesPayloadWithoutAdvancingTheOriginalPacketBuffer() {
        final ByteBuf input = Unpooled.wrappedBuffer(new byte[]{7, 8, 9});

        final byte[] copied = JavaCustomPayloadBridge.copyRemainingPayload(input);

        assertArrayEquals(new byte[]{7, 8, 9}, copied);
        assertEquals(0, input.readerIndex());
    }
}
