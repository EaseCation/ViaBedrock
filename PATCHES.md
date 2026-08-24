# ViaBedrock_NetEase patch log

Protocol truth: `decompiled/nukkit-mot` encode/decode, then `decompiled/nukkitmaster` for PyRpc / ModUI. Do not treat international Bedrock wiki or Geyser palettes as MOT 860.

## 2026-08-24 — ClientLoadAddonsFinishedFromGac

- **Goal:** Java clients never emit NukkitMaster's engine-call gate, so HUD / player-info stayed empty after join.
- **Change:** After PLAY_STATUS PlayerSpawn sends `SET_LOCAL_PLAYER_AS_INITIALIZED`, schedule `ClientLoadAddonsFinishedFromGac` (msgId `98247598`) 250ms later.
- **Refs:**
  - `decompiled/nukkitmaster/com/neteasemc/nukkitmaster/eventListener/ClientEventListener.java` (`listenForClientEngineCall("ClientLoadAddonsFinishedFromGac")`)
  - `decompiled/nukkitmaster/com/neteasemc/nukkitmaster/pyrpc/PyRpcMessageListener.java` (C2S msgId `98247598`, engine callback vs ModEvent)
  - `decompiled/nukkit-mot/cn/nukkit/network/process/processor/v282/SetLocalPlayerAsInitializedProcessor_v282.java` (`doFirstSpawn` → `PlayerJoinEvent`)
  - `decompiled/nukkit-mot/cn/nukkit/network/protocol/netease/PyRpcPacket.java`
- **Risk:** 250ms delay assumes MOT finishes `PlayerJoinEvent` / `PlayerInfo` before the engine call. If Master still logs a missing PlayerInfo, raise the delay rather than sending on the same tick.

## Open risks (not patched here)

- `block_palette.nbt` / `runtime_item_states.json` are ViaBedrock international dumps, not MOT `mapping_netease_860`. Custom NetEase items still need a MOT dump.
- Geyser `block_palette.26_*.nbt` is Java↔Bedrock 1.21.x international, not NetEase 860.
- EaseCation `upstream/main` is already at merge-base `fcf85f26`; no NetEase-only commits left to cherry-pick.
- Particle / VBU memory: custom entity payloads stay on `viabedrockutility:data`. Display-entity fallback can leak if VBU is missing and `enable-server-entity-animation` is on.
