package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerPositionModeComponent_PositionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherPlayerPacketsMovePlayerTest {

    @Test
    void localMotSendPositionBecomesJavaPlayerPosition() {
        assertTrue(OtherPlayerPackets.shouldRewriteLocalMovePlayerToJavaPosition(PlayerPositionModeComponent_PositionMode.Normal));
        assertTrue(OtherPlayerPackets.shouldRewriteLocalMovePlayerToJavaPosition(PlayerPositionModeComponent_PositionMode.Respawn));
        assertTrue(OtherPlayerPackets.shouldRewriteLocalMovePlayerToJavaPosition(PlayerPositionModeComponent_PositionMode.Teleport));
        assertFalse(OtherPlayerPackets.shouldRewriteLocalMovePlayerToJavaPosition(PlayerPositionModeComponent_PositionMode.OnlyHeadRot));
    }

    @Test
    void onlyDuplicateTeleportIsCancelled() {
        assertTrue(OtherPlayerPackets.shouldCancelDuplicateLocalTeleport(PlayerPositionModeComponent_PositionMode.Teleport, true));
        assertFalse(OtherPlayerPackets.shouldCancelDuplicateLocalTeleport(PlayerPositionModeComponent_PositionMode.Teleport, false));
        assertFalse(OtherPlayerPackets.shouldCancelDuplicateLocalTeleport(PlayerPositionModeComponent_PositionMode.Normal, true));
        assertFalse(OtherPlayerPackets.shouldCancelDuplicateLocalTeleport(PlayerPositionModeComponent_PositionMode.Respawn, true));
    }

    @Test
    void onlyResetModeUsesFakeJavaTeleportId() {
        assertTrue(OtherPlayerPackets.isFakeJavaTeleportForMovePlayer(PlayerPositionModeComponent_PositionMode.Respawn));
        assertFalse(OtherPlayerPackets.isFakeJavaTeleportForMovePlayer(PlayerPositionModeComponent_PositionMode.Normal));
        assertFalse(OtherPlayerPackets.isFakeJavaTeleportForMovePlayer(PlayerPositionModeComponent_PositionMode.Teleport));
    }
}
