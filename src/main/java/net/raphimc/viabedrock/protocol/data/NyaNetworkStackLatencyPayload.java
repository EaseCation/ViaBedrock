/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.data;

/**
 * NyaAC 用这个特殊 NSL 载荷请求 ViaBedrock 定位处理完前序包后的首个 PlayerAuthInput。
 * 普通 Java Pong 只能证明客户端收到了 Ping，不能指出哪一帧移动体现了前面的传送和动量；
 * 载荷限制为 43 位，确保回写 Bedrock NSL 时乘以 1,000,000 仍在正 long 范围内。
 */
public final class NyaNetworkStackLatencyPayload {

    private static final long MAGIC = 0x4E59L;
    private static final int MAGIC_SHIFT = 27;
    private static final int VERSION_SHIFT = 24;
    private static final int KIND_SHIFT = 20;
    private static final long VERSION = 1L;
    private static final int JAVA_CLIENT_TICK_END_BOUNDARY = 1;
    private static final long MAGIC_MASK = 0xFFFFL << MAGIC_SHIFT;
    private static final long VERSION_MASK = 0x7L << VERSION_SHIFT;
    private static final long KIND_MASK = 0xFL << KIND_SHIFT;
    private static final long MAX_VALUE = (1L << 43) - 1L;

    private NyaNetworkStackLatencyPayload() {
    }

    public static boolean isJavaClientTickEndBoundary(final long value) {
        return value >= 0 && value <= MAX_VALUE
                && (value & MAGIC_MASK) == MAGIC << MAGIC_SHIFT
                && (value & VERSION_MASK) == VERSION << VERSION_SHIFT
                && (value & KIND_MASK) == (long) JAVA_CLIENT_TICK_END_BOUNDARY << KIND_SHIFT;
    }

}
