package net.raphimc.viabedrock.experimental.pyrpc;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;

/**
 * Marks that the proxy-side ModUI lifecycle (ScreenInfoEvent + RequestHudNodeDataEvent)
 * has been started for this connection. Per-connection because the previous static flag
 * leaked across sessions and suppressed the handshake on every reconnect after the first.
 */
public class ModUiLifecycleStorage extends StoredObject {

    public ModUiLifecycleStorage() {
        super(null);
    }

    public ModUiLifecycleStorage(final UserConnection user) {
        super(user);
    }

}

/**
 * One-shot / retry state so {@code ClientLoadAddonsFinishedFromGac} is not
 * re-sent on PLAY_STATUS PlayerSpawn reloads, but can still be retried when
 * the Netty channel is not active at the first spawn notification.
 */
class NetEaseAddonsFinishedStorage extends StoredObject {

    static final int MAX_ATTEMPTS = 8;

    volatile boolean sent;
    volatile boolean scheduled;
    volatile int attempts;

    NetEaseAddonsFinishedStorage(final UserConnection user) {
        super(user);
    }

}
