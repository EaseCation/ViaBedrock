/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.JavaCustomPayloadRateLimitStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Bridges a Java C2S custom payload into a ScriptMessage understood by the EaseCation Synapse
 * backend. It is transport only: no purchase event is emulated and no client data is interpreted.
 *
 * <p>The ScriptMessage id is fixed to {@value #SCRIPT_MESSAGE_ID}. Its value is Base64 URL-safe
 * bytes: one unsigned channel-length byte, UTF-8 channel bytes, then the untouched payload.</p>
 */
public final class JavaCustomPayloadForwarder {

    public static final String SCRIPT_MESSAGE_ID = "easecation:java_custom_payload_v1";

    private JavaCustomPayloadForwarder() {
    }

    /**
     * Copies a Java custom payload when forwarding is enabled. It must not consume the wrapper:
     * existing channel registration and experimental payload handlers still own the original packet.
     */
    public static void forward(final String channel, final PacketWrapper wrapper) {
        if (!ViaBedrock.getConfig().shouldForwardJavaCustomPayloads()) {
            return;
        }
        if (wrapper.user().getProtocolInfo().getClientState() != State.PLAY
                || wrapper.user().getProtocolInfo().getServerState() != State.PLAY) {
            return;
        }

        try {
            final byte[] channelBytes = channel.getBytes(StandardCharsets.UTF_8);
            if (channelBytes.length == 0
                    || channelBytes.length > ViaBedrock.getConfig().getJavaCustomPayloadMaxChannelBytes()) {
                return;
            }
            if (!(wrapper instanceof PacketWrapperImpl packetWrapper)) {
                return;
            }
            final ByteBuf inputBuffer = packetWrapper.getInputBuffer();
            if (inputBuffer == null || inputBuffer.readableBytes()
                    > ViaBedrock.getConfig().getJavaCustomPayloadMaxPayloadBytes()) {
                return;
            }
            final byte[] payload = copyRemainingPayload(inputBuffer);

            final JavaCustomPayloadRateLimitStorage rateLimit = wrapper.user().get(JavaCustomPayloadRateLimitStorage.class);
            if (rateLimit == null || !rateLimit.tryAcquire(System.nanoTime(), payload.length,
                    ViaBedrock.getConfig().getJavaCustomPayloadMaxMessagesPerMinute(),
                    ViaBedrock.getConfig().getJavaCustomPayloadMaxBytesPerMinute())) {
                return;
            }

            final PacketWrapper scriptMessage = PacketWrapper.create(ServerboundBedrockPackets.SCRIPT_MESSAGE, wrapper.user());
            scriptMessage.write(BedrockTypes.STRING, SCRIPT_MESSAGE_ID);
            scriptMessage.write(BedrockTypes.STRING, encodeEnvelope(channelBytes, payload));
            scriptMessage.sendToServer(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().warning("Unable to forward a Java custom payload: " + e.getClass().getSimpleName());
        }
    }

    static byte[] copyRemainingPayload(final ByteBuf inputBuffer) {
        final byte[] payload = new byte[inputBuffer.readableBytes()];
        inputBuffer.getBytes(inputBuffer.readerIndex(), payload);
        return payload;
    }

    static String encodeEnvelope(final byte[] channelBytes, final byte[] payload) {
        if (channelBytes.length == 0 || channelBytes.length > 255) {
            throw new IllegalArgumentException("Channel must fit in an unsigned byte");
        }
        final byte[] envelope = new byte[1 + channelBytes.length + payload.length];
        envelope[0] = (byte) channelBytes.length;
        System.arraycopy(channelBytes, 0, envelope, 1, channelBytes.length);
        System.arraycopy(payload, 0, envelope, 1 + channelBytes.length, payload.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }

    static DecodedEnvelope decodeEnvelope(final String encodedValue) {
        final byte[] envelope = Base64.getUrlDecoder().decode(encodedValue);
        if (envelope.length < 2) {
            throw new IllegalArgumentException("Envelope is too short");
        }
        final int channelLength = Byte.toUnsignedInt(envelope[0]);
        if (channelLength == 0 || envelope.length < 1 + channelLength) {
            throw new IllegalArgumentException("Envelope has an invalid channel length");
        }
        return new DecodedEnvelope(
                new String(envelope, 1, channelLength, StandardCharsets.UTF_8),
                Arrays.copyOfRange(envelope, 1 + channelLength, envelope.length)
        );
    }

    record DecodedEnvelope(String channel, byte[] payload) {
    }
}
