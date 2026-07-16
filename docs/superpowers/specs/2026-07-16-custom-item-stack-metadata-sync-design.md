# Custom item stack metadata sync design

## Context

BedrockLoader schema 4 synchronizes custom item identifiers and their final Java raw IDs. ViaBedrock allocates source-side custom Java IDs and uses the runtime overlay to translate those IDs to the BedrockLoader registry. This is sufficient for clientbound rendering, but the snapshot does not carry the item's maximum stack size.

`JavaItemStackLimits` intentionally rejects a translated Java item when it has neither an explicit `MAX_STACK_SIZE` component nor a vanilla identifier with generated stack-size data. Every synchronized custom source ID is outside the vanilla item table, so a normal container pickup returns `null`. `ClientAuthInventoryModule` then restores the authoritative inventory without sending a Bedrock transaction. The result is a correctly rendered custom shop item that cannot be clicked.

## Goals

- Synchronize the authoritative maximum stack size for every BedrockLoader custom item.
- Make synchronized custom items usable by all inventory simulations that require a stack limit.
- Preserve the conservative rollback behavior for unknown, invalid, or legacy custom item limits.
- Cover both BedrockLoader client variants, 1.21.8 and 26.1.
- Keep block-state rendering, item identity, custom consumables, and vanilla stack-limit behavior unchanged.

## Non-goals

- Do not add BedWars- or HXGZ-specific identifiers or hard-coded limits.
- Do not default unknown custom items to 64.
- Do not change Nukkit shop, pricing, purchase, or inventory transaction logic.
- Do not redesign server-authoritative `ItemStackRequest` support.

## Chosen approach

Extend the BedrockLoader snapshot to schema 5. Each custom item entry carries:

1. the Bedrock identifier;
2. the final Java raw ID;
3. the effective Java maximum stack size read from the registered Java item's default stack.

ViaBedrock validates and retains that value through `CustomMappingSnapshot`, `SnapshotProfile`, and `CustomMappingAccess`. When `ItemRewriter` creates the synchronized source-side `StructuredItem`, it adds `StructuredDataKey.MAX_STACK_SIZE`. Existing `JavaItemStackLimits` then resolves the value through its highest-priority component path without needing custom-ID exceptions.

As a supplemental path, `ItemDefinitions` also parses `minecraft:max_stack_size` from resource definitions and `item_properties.max_stack_size` or `minecraft:max_stack_size` from network component definitions. This improves paper fallback and non-BedrockLoader custom items, but it is not the source of truth for synchronized custom block items because Nukkit registers those items without component data.

## Wire format and compatibility

Schema 5 keeps the schema 4 field order and appends one unsigned VarInt to every item entry:

```text
itemCount: VarInt
repeated itemCount times:
  bedrockIdentifierStringId: VarInt
  targetJavaRawId: VarInt
  maxStackSize: VarInt
```

The accepted range is `1..99`, matching Java protocol component limits and `JavaItemStackLimits.MAX_SUPPORTED`.

- BedrockLoader 1.21.8 and 26.1 emit schema 5.
- ViaBedrock continues to decode schemas 2, 3, and 4.
- Schema 2 and 3 have no custom item table.
- Schema 4 custom items decode with an unknown stack limit. They may still render, but inventory operations requiring a limit continue to roll back safely.
- Schema 5 rejects zero, values above 99, duplicate identifiers, duplicate target raw IDs, and malformed/trailing data.
- Cache keys and profile equality include the stack limit so profiles with different item semantics cannot share a stale projection.

No protocol negotiation is added. Existing ViaBedrock builds reject schema 5, so deployment must update ViaBedrock before the BedrockLoader addon. The bbdev rollout therefore publishes and starts the new ViaProxy image first, then publishes the addon before the next connection test.

## Components

### BedrockLoader

`SnapshotItem` gains `maxStackSize`. `CustomMappingSnapshotBuilder` reads it from each registered Java item's default stack, validates `1..99`, includes it in sorting/validation semantics, and emits schema 5. Codec tests cover deterministic bytes, invalid limits, and both supported client source sets.

### ViaBedrock snapshot and projection

`CustomMappingSnapshot.ItemEntry` and `SnapshotProfile.ItemMapping` gain the limit. `CustomMappingAccess` exposes custom item metadata by Bedrock identifier rather than only a raw ID. Runtime projections carry the metadata unchanged; final-stage ID remapping changes only the raw ID, never the limit.

Schema 4 uses an explicit unknown sentinel, not 64. The sentinel is never written as a Java component.

### Item translation

When a Bedrock identifier resolves to synchronized custom metadata, `ItemRewriter` creates the custom source item as before and adds `MAX_STACK_SIZE` only when the validated limit is known. The existing consumable, armor, name, lore, and model component passes continue afterward.

`ItemDefinitions` stores an optional validated stack size. Network definition fields override resource-pack presentation data for gameplay semantics. Malformed values warn once per identifier and remain unknown.

### Inventory simulation

No click algorithm changes are required. `JavaItemStackLimits` already prioritizes `MAX_STACK_SIZE`, and `ClickSimulator` already copies authoritative Bedrock items into outgoing actions. Once the component exists, normal pickup, merge, quick move, clone, drag, pickup-all, and crafting paths use the correct limit.

## Error handling

- BedrockLoader refuses to emit a schema 5 snapshot containing an invalid registered stack size.
- ViaBedrock rejects malformed schema 5 snapshots before installing an overlay.
- Legacy or incomplete metadata stays unsupported for amount-increasing inventory operations and follows the existing authoritative rollback path.
- No global fallback is introduced; an incorrect guessed limit is more dangerous than a rejected click because it can create ghost stacks or server/client count divergence.

## Tests

### BedrockLoader

- Schema 5 round-trip includes item stack sizes for both 1.21.8 and 26.1 sources.
- Ordering and encoded output remain deterministic.
- Limits `1`, `16`, `64`, and `99` are accepted; `0` and `100` are rejected.
- Custom block items are represented even when they have no network item components.

### ViaBedrock

- Schema 4 remains decodable and produces unknown custom stack metadata.
- Schema 5 decodes, validates, caches, and projects `1`, `16`, `64`, and `99` unchanged.
- A synchronized custom `StructuredItem` receives `MAX_STACK_SIZE`; legacy unknown metadata does not.
- `JavaItemStackLimits` resolves a custom source ID beyond the vanilla table through the explicit component.
- A normal pickup of a synchronized custom GUI item produces slot and cursor actions instead of `null`.
- Existing tests keep unknown limits on authoritative rollback and keep vanilla armor, pearl, block, crafting, and quick-move limits unchanged.

### Runtime acceptance

- bbdev logs show schema 5 accepted and the expected custom item count installed.
- HXGZ planks and all 16 wool colors remain correctly rendered and can be purchased with one click.
- Candy ore and corruption block item forms can be picked up, split, and shift-moved.
- Test custom items with limits 1, 16, and 64 never exceed their registered limits.
- The Nukkit purchase event fires once per click, currency is deducted once, and no duplicate or ghost item appears.

## Delivery

Implement and commit BedrockLoader and ViaBedrock independently, then update their parent gitlinks. Build BedrockLoader's supported variants and the ViaProxy composite with Java 21. Push the ViaBedrock feature branch and parent test branch, publish the matching BedrockLoader addon through the asset deployment flow, wait for CI, roll out bbdev, and verify the running image digest, addon version, schema log, and live purchase behavior.
