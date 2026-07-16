# ViaBedrock Java inventory stack limits design

## Context

Java clients predict container clicks locally, while ViaBedrock translates those clicks into Bedrock inventory transactions and keeps an optimistic mirror until the Bedrock server corrects it. A prediction that ViaBedrock accepts with the wrong stack limit can therefore be shown to the player even when the Bedrock server later rejects it. Equipment predictions are especially visible because the client renders the predicted item in an armor slot; a later authoritative sync makes the item disappear, producing a ghost item.

The current `main` already contains three relevant fixes, but they solve different failures:

- `Fix pre-play inventory packet loss` queues early inventory packets until PLAY and the item registry are ready. It protects login/bootstrap ordering, not click semantics.
- `Allow authoritative armor quick moves` accepts a count-conserving Java quick-move prediction even when component hashes differ, while keeping the Bedrock mirror authoritative.
- `Respect Java item stack limits` adds the vanilla Java stack-size data and applies it to predicted player quick moves and generic shift-click moves.

The last change is incomplete. `ClickSimulator` still hard-codes `64` for normal pickup merges, right-click merges, creative clone, quick-craft drag, and double-click collection. `CraftingSimulator` also hard-codes `64` when taking or shift-clicking recipe output. These paths can construct a Bedrock transaction whose target stack exceeds the Java item's real limit. Armor has a limit of one, so the same gap permits stacked armor and can leave a transient local armor prediction until authoritative rollback.

## Contract

Every simulated Java inventory operation that increases a target stack must use the effective Java maximum stack size for that item. The effective value is resolved in this order:

1. A present Java `MAX_STACK_SIZE` component, if it is an integer in the protocol-supported range `1..99`.
2. An explicitly removed/empty `MAX_STACK_SIZE` component resolves conservatively to `1`.
3. The generated vanilla Java item stack-size table for the translated Java item identifier.

An empty translation, unknown identifier, invalid component value, or conversion failure is unsupported. The simulator returns `null`; the existing `ClientAuthInventoryModule` path then resends the authoritative container instead of committing a guessed transaction. Existing over-limit authoritative source stacks are never silently truncated or deleted. Operations may split them into legal target stacks when the normal operation supports that, but no target may exceed its limit.

## Components

### `JavaItemStackLimits`

Add a package-private inventory utility with two responsibilities:

- Resolve the maximum from a translated Java `Item`, with a testable fallback lookup.
- Translate a copied `BedrockItem` through the connection `ItemRewriter` and resolve its effective Java limit. Translation failures return an unsupported sentinel instead of assuming `64`.

The utility is the single source of stack-limit truth for both click and crafting simulation.

### `ClickSimulator`

Pass a stack-limit resolver through the simulator so focused tests can use deterministic limits without bootstrapping the full protocol mapping stack. Production calls use `JavaItemStackLimits`.

Apply the resolved limit to:

- left-click and right-click merge capacity;
- generic and predicted quick moves;
- creative middle-click clone size;
- left, right, and creative quick-craft drag targets;
- double-click collection capacity.

Moves that do not increase a stack, such as swap and throw, retain their existing behavior. If an operation requires a limit and it cannot be resolved, return `null` before emitting any action.

### `CraftingSimulator`

Resolve the primary output limit once per craft attempt and use it for cursor pickup, existing-stack merges, and empty-slot placement. A full cursor must leave the recipe and inventory untouched. Shift-click output may distribute across multiple legal target stacks but must fail atomically when the complete output cannot fit.

### Authoritative rollback

No new rollback protocol is needed. `ClientAuthInventoryModule` already treats a `null` simulation result or runtime failure as unsupported and resends authoritative contents without applying mirror updates. Tests will retain this contract and verify that over-limit predictions are rejected before optimistic commit.

## Tests

Add focused tests for both resolution and each amount-increasing operation:

- vanilla data anchors: netherite boots/armor `1`, ender pearls `16`, ordinary blocks `64`;
- explicit, empty, invalid, and unknown Java stack-size resolution;
- normal left/right merge of armor does not create amount `2`;
- generic quick move splits or leaves a source remainder without an over-limit target;
- creative clone uses the item limit;
- quick-craft drag and double-click collection stop at the item limit;
- crafting pickup does not consume inputs when the cursor is full, and crafting quick move places only legal stacks;
- an unresolved limit returns `null`, preserving the existing authoritative rollback path.

Tests use a real `InventoryTracker` with an embedded connection where practical and inject a deterministic stack-limit resolver. This exercises slot mapping and generated `InventoryActionData`, not just arithmetic helpers.

## Verification and delivery

Run Gradle only from the ViaProxyWorkspace composite root with Java 21:

1. the focused ViaBedrock test classes;
2. the full ViaBedrock test task;
3. the composite workspace build/check required by repository guidance;
4. `git diff --check` and parent/submodule status audits.

Commit the ViaBedrock implementation and tests on `fix/viabedrock-inventory-stack-limits`, then commit the parent gitlink on the same-named parent branch. Do not push, merge, deploy, restart, or modify `SERVICE.md`. Runtime acceptance remains explicitly unverified until a later authorized deployment; provide manual armor stacking and lobby ghost-item checks with the handoff.
