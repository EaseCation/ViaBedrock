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
