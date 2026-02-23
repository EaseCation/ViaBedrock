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
package net.raphimc.viabedrock.experimental.camera;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.nio.charset.StandardCharsets;

public class CameraInterface {

    public static final String CONFIRM_CHANNEL = "becamera:confirm";
    public static final String CHANNEL = "becamera:data";

    public static void confirmPresence(final UserConnection user) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CONFIRM.ordinal());
        pluginMessage.send(BedrockProtocol.class);
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CAMERA_SHAKE, null, wrapper -> {
            wrapper.cancel();
            final float intensity = wrapper.read(BedrockTypes.FLOAT_LE);
            final float duration = wrapper.read(BedrockTypes.FLOAT_LE);
            final byte type = wrapper.read(Types.BYTE);
            final byte action = wrapper.read(Types.BYTE);

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                return;
            }
            sendCameraShake(wrapper.user(), intensity, duration, type, action);
        });

        protocol.registerClientbound(ClientboundBedrockPackets.CAMERA_PRESETS, null, wrapper -> {
            wrapper.cancel();
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

            final String[] names = new String[count];
            final String[] parents = new String[count];
            final Float[] posXs = new Float[count];
            final Float[] posYs = new Float[count];
            final Float[] posZs = new Float[count];
            final Float[] rotXs = new Float[count];
            final Float[] rotYs = new Float[count];

            for (int i = 0; i < count; i++) {
                names[i] = wrapper.read(BedrockTypes.STRING);
                parents[i] = wrapper.read(BedrockTypes.STRING);

                posXs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                posYs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                posZs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                rotXs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                rotYs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;

                // Skip remaining optional fields we don't use yet
                skipOptionalFloat(wrapper); // rotation_speed
                skipOptionalBoolean(wrapper); // snap_to_target
                skipOptionalVec2f(wrapper); // horizontal_rotation_limit
                skipOptionalVec2f(wrapper); // vertical_rotation_limit
                skipOptionalBoolean(wrapper); // continue_targeting
                skipOptionalFloat(wrapper); // block_listening_radius
                skipOptionalVec2f(wrapper); // view_offset
                skipOptionalVec3f(wrapper); // entity_offset
                skipOptionalFloat(wrapper); // radius
                skipOptionalFloat(wrapper); // yaw_limit_min
                skipOptionalFloat(wrapper); // yaw_limit_max
                skipOptionalByte(wrapper); // audio_listener
                skipOptionalBoolean(wrapper); // player_effects
                // aim_assist (optional nested)
                if (wrapper.read(Types.BOOLEAN)) {
                    skipOptionalString(wrapper); // preset_id
                    skipOptionalByte(wrapper); // target_mode
                    skipOptionalVec2f(wrapper); // angle
                    skipOptionalFloat(wrapper); // distance
                }
                skipOptionalByte(wrapper); // control_scheme
            }

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                return;
            }
            sendCameraPresets(wrapper.user(), count, names, parents, posXs, posYs, posZs, rotXs, rotYs);
        });

        protocol.registerClientbound(ClientboundBedrockPackets.CAMERA_INSTRUCTION, null, wrapper -> {
            wrapper.cancel();

            boolean hasSet = false;
            boolean hasClear = false;
            boolean hasFade = false;

            // Set instruction fields
            int presetRuntimeId = 0;
            byte easingType = 0;
            float easeDuration = 0;
            boolean hasEase = false;
            Position3f setPos = null;
            float rotX = 0, rotY = 0;
            boolean hasRot = false;
            Position3f facing = null;
            boolean isDefault = false;

            // Fade instruction fields
            float fadeIn = 0, fadeStay = 0, fadeOut = 0;
            boolean hasFadeTime = false;
            float fadeR = 0, fadeG = 0, fadeB = 0;
            boolean hasFadeColor = false;

            // CameraSetInstruction (optional)
            hasSet = wrapper.read(Types.BOOLEAN);
            if (hasSet) {
                presetRuntimeId = wrapper.read(BedrockTypes.INT_LE);

                hasEase = wrapper.read(Types.BOOLEAN);
                if (hasEase) {
                    easingType = wrapper.read(Types.BYTE);
                    easeDuration = wrapper.read(BedrockTypes.FLOAT_LE);
                }

                if (wrapper.read(Types.BOOLEAN)) {
                    setPos = wrapper.read(BedrockTypes.POSITION_3F);
                }

                hasRot = wrapper.read(Types.BOOLEAN);
                if (hasRot) {
                    rotX = wrapper.read(BedrockTypes.FLOAT_LE);
                    rotY = wrapper.read(BedrockTypes.FLOAT_LE);
                }

                if (wrapper.read(Types.BOOLEAN)) {
                    facing = wrapper.read(BedrockTypes.POSITION_3F);
                }

                skipOptionalVec2f(wrapper); // view_offset
                skipOptionalVec3f(wrapper); // entity_offset

                if (wrapper.read(Types.BOOLEAN)) {
                    isDefault = wrapper.read(Types.BOOLEAN);
                }

                wrapper.read(Types.BOOLEAN); // remove_ignore_starting_values_component
            }

            // Clear instruction (optional)
            hasClear = wrapper.read(Types.BOOLEAN);
            if (hasClear) {
                wrapper.read(Types.BOOLEAN); // clear value
            }

            // Fade instruction (optional)
            hasFade = wrapper.read(Types.BOOLEAN);
            if (hasFade) {
                hasFadeTime = wrapper.read(Types.BOOLEAN);
                if (hasFadeTime) {
                    fadeIn = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeStay = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeOut = wrapper.read(BedrockTypes.FLOAT_LE);
                }
                hasFadeColor = wrapper.read(Types.BOOLEAN);
                if (hasFadeColor) {
                    fadeR = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeG = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeB = wrapper.read(BedrockTypes.FLOAT_LE);
                }
            }

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                return;
            }
            sendCameraInstruction(wrapper.user(),
                    hasSet, presetRuntimeId, hasEase, easingType, easeDuration,
                    setPos, hasRot, rotX, rotY, facing, isDefault,
                    hasClear,
                    hasFade, hasFadeTime, fadeIn, fadeStay, fadeOut,
                    hasFadeColor, fadeR, fadeG, fadeB);
        });
    }

    // --- Payload sending methods ---

    public static void sendCameraShake(final UserConnection user, final float intensity, final float duration, final byte type, final byte action) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CAMERA_SHAKE.ordinal());
        pluginMessage.write(Types.FLOAT, intensity);
        pluginMessage.write(Types.FLOAT, duration);
        pluginMessage.write(Types.BYTE, type);
        pluginMessage.write(Types.BYTE, action);
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    public static void sendCameraPresets(final UserConnection user, final int count,
                                         final String[] names, final String[] parents,
                                         final Float[] posXs, final Float[] posYs, final Float[] posZs,
                                         final Float[] rotXs, final Float[] rotYs) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CAMERA_PRESETS.ordinal());
        pluginMessage.write(Types.INT, count);
        for (int i = 0; i < count; i++) {
            writeString(pluginMessage, names[i]);
            writeString(pluginMessage, parents[i]);
            writeOptionalFloat(pluginMessage, posXs[i]);
            writeOptionalFloat(pluginMessage, posYs[i]);
            writeOptionalFloat(pluginMessage, posZs[i]);
            writeOptionalFloat(pluginMessage, rotXs[i]);
            writeOptionalFloat(pluginMessage, rotYs[i]);
        }
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    public static void sendCameraInstruction(final UserConnection user,
                                              final boolean hasSet, final int presetRuntimeId,
                                              final boolean hasEase, final byte easingType, final float easeDuration,
                                              final Position3f setPos, final boolean hasRot, final float rotX, final float rotY,
                                              final Position3f facing, final boolean isDefault,
                                              final boolean hasClear,
                                              final boolean hasFade, final boolean hasFadeTime,
                                              final float fadeIn, final float fadeStay, final float fadeOut,
                                              final boolean hasFadeColor, final float fadeR, final float fadeG, final float fadeB) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets1_21_11.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CAMERA_INSTRUCTION.ordinal());

        // Flags
        pluginMessage.write(Types.BOOLEAN, hasSet);
        pluginMessage.write(Types.BOOLEAN, hasClear);
        pluginMessage.write(Types.BOOLEAN, hasFade);

        if (hasSet) {
            pluginMessage.write(Types.INT, presetRuntimeId);
            pluginMessage.write(Types.BOOLEAN, hasEase);
            if (hasEase) {
                pluginMessage.write(Types.BYTE, easingType);
                pluginMessage.write(Types.FLOAT, easeDuration);
            }
            pluginMessage.write(Types.BOOLEAN, setPos != null);
            if (setPos != null) {
                pluginMessage.write(Types.FLOAT, setPos.x());
                pluginMessage.write(Types.FLOAT, setPos.y());
                pluginMessage.write(Types.FLOAT, setPos.z());
            }
            pluginMessage.write(Types.BOOLEAN, hasRot);
            if (hasRot) {
                pluginMessage.write(Types.FLOAT, rotX);
                pluginMessage.write(Types.FLOAT, rotY);
            }
            pluginMessage.write(Types.BOOLEAN, facing != null);
            if (facing != null) {
                pluginMessage.write(Types.FLOAT, facing.x());
                pluginMessage.write(Types.FLOAT, facing.y());
                pluginMessage.write(Types.FLOAT, facing.z());
            }
            pluginMessage.write(Types.BOOLEAN, isDefault);
        }

        if (hasFade) {
            pluginMessage.write(Types.BOOLEAN, hasFadeTime);
            if (hasFadeTime) {
                pluginMessage.write(Types.FLOAT, fadeIn);
                pluginMessage.write(Types.FLOAT, fadeStay);
                pluginMessage.write(Types.FLOAT, fadeOut);
            }
            pluginMessage.write(Types.BOOLEAN, hasFadeColor);
            if (hasFadeColor) {
                pluginMessage.write(Types.FLOAT, fadeR);
                pluginMessage.write(Types.FLOAT, fadeG);
                pluginMessage.write(Types.FLOAT, fadeB);
            }
        }

        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    // --- Helper methods ---

    private static void writeString(final PacketWrapper wrapper, final String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        wrapper.write(Types.INT, bytes.length);
        wrapper.write(Types.REMAINING_BYTES, bytes);
    }

    private static void writeOptionalFloat(final PacketWrapper wrapper, final Float value) {
        wrapper.write(Types.BOOLEAN, value != null);
        if (value != null) {
            wrapper.write(Types.FLOAT, value);
        }
    }

    private static void skipOptionalFloat(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    private static void skipOptionalBoolean(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(Types.BOOLEAN);
        }
    }

    private static void skipOptionalByte(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(Types.BYTE);
        }
    }

    private static void skipOptionalString(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.STRING);
        }
    }

    private static void skipOptionalVec2f(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    private static void skipOptionalVec3f(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
            wrapper.read(BedrockTypes.FLOAT_LE);
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    private enum PayloadType {
        CONFIRM, CAMERA_INSTRUCTION, CAMERA_SHAKE, CAMERA_PRESETS
    }

}
