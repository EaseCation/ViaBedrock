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

## 2026-08-24 — Java USE_ITEM air-click → MOT CLICK_BLOCK

- **Goal:** NetEase MOT only runs `Item.onActivate` from CLICK_BLOCK. Java empty/filled buckets, glass bottles, boats, lily pads and frog spawn send USE_ITEM (air click) and previously did nothing.
- **Change:** `ItemUseAirClickTarget` raytraces fluids / placeable surfaces; `ExperimentalFeatures` converts those air clicks. Same-tick duplicate USE_ITEM_ON is dropped. Kelp / custom consumables that MOT cannot auto-complete send a second CLICK_AIR.
- **Risk:** Requires `enable-experimental-features`. Food/potion/bow/shield still need NukkitMOTJE on the MOT side. Offhand promotion can swap MOT hands without rewriting the Java inventory (`tryHandleSwapHands(user, false)`).

## 2026-08-24 — MOT 860 sequential palette overlay + leftover IDs

- **Goal:** Hashed `network_id` already matches MOT 860 (FNV-1a of LE `{name,states}`). Sequential `runtimeId` does not: ViaBedrock used hashed-name order, MOT stores sequential ids in `runtime_block_states_netease_860.dat`. `minecraft:micro_block` exists only in the MOT dump. JWT `GameVersion` also defaulted to international `1.21.124`.
- **Change:**
  - Bundle `data/bedrock/netease_860_block_runtime_ids.json` (15829 unique hashed → sequential pairs after dropping 410 MOT overload rows that share the same hash and runtimeId; extra `micro_block`).
  - Sequential NetEase sessions resolve runtime ids from that overlay; hashed sessions stay on `network_id`.
  - Default `netease.game-version` to MOT enum `1.21.124_NetEase`.
  - Register/cancel leftover MOT IDs 305 / 340 so unknown packets cannot abort a RakNet batch.
- **Refs:**
  - `decompiled/nukkit-mot/cn/nukkit/level/BlockPalette.java` (`runtimeId` / hashed FNV-1a)
  - `decompiled/nukkit-mot/cn/nukkit/utils/Hash.java`
  - `decompiled/nukkit-mot/cn/nukkit/GameVersion.java` (`V1_21_124_NETEASE`)
  - `decompiled/nukkit-mot/cn/nukkit/network/protocol/ProtocolInfo.java` (305 / 340)
- **Risk:** Overlay is vanilla MOT 860 only. Custom blocks still come from START_GAME `blockProperties` and are assigned ids after the MOT sequential max. Live `ITEM_REGISTRY` still overrides static item ids.

## Open risks (not patched here)

- Static `runtime_item_states.json` is still the international dump. MOT `runtime_item_states_netease_860.json` differs on 552 vanilla ids, but MOT always sends live `ITEM_REGISTRY`, so classification (`id <= 255`) is the remaining static risk.
- Geyser `block_palette.26_*.nbt` is Java↔Bedrock 1.21.x international, not NetEase 860. Do not replace ViaBedrock's hashed palette with it.
- EaseCation `upstream/main` is already at merge-base `fcf85f26`; no NetEase-only commits left to cherry-pick.
- Particle / VBU memory: custom entity payloads stay on `viabedrockutility:data`. Display-entity fallback can leak if VBU is missing and `enable-server-entity-animation` is on.
- NukkitMaster shop / urge callbacks (`UrgeShipEvent`, `StoreBuySuccServerEvent`) are still not synthesized from Java.
