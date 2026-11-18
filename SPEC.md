# Storage Terminal: SPEC (v1)

A fully client-side Fabric mod for vanilla servers. Stand among many containers, press a key, and get one AE2/Refined-Storage-style screen that shows everything stored in the containers around you. Taking and depositing items is translated into real container interactions behind the scenes.

This document is the source of truth. `CLAUDE.md` holds the working rules. Read both before doing anything.

---

## 1. Fixed decisions (do not re-litigate)

| Item | Decision |
|---|---|
| Name / mod id | **Storage Terminal** / `storageterminal` |
| Minecraft | **26.3** (year-based versioning) |
| Loader | Fabric (Fabric API) |
| Language | Java (the version the official 26.3 Fabric template targets) |
| Mappings | Mojang official names (the game ships unobfuscated for this version line; verify the Loom setup in the Fabric docs, do not assume the old Yarn/remap setup) |
| Side | **Client only.** `"environment": "client"`. Must work on unmodified vanilla servers, multiplayer and singleplayer. No server component, no custom packets, ever. |
| License | **AGPL-3.0** (see section 2 for the exact SPDX id question) |
| Config | Plain JSON file. Mod Menu integration optional (soft dependency, must not be required). No Cloth Config. |
| Scope of this spec | **v1 only**: scanner, cache, read-only screen, take, put. Everything else is roadmap (section 12) and must NOT be built yet. |
| Containers in v1 | Chests, trapped chests, barrels, and shulker boxes **already placed in the world**. |
| Trigger in v1 | A keybind (default unbound-safe key chosen by you, rebindable). Right-clicking a configured block is roadmap. |
| Hidden vanilla GUI | While the mod works, the vanilla container screens are suppressed. The player only sees the Storage Terminal screen. |

## 2. Ask the user before M0

Ask these in chat, wait for answers, record them in `DECISIONS.md`:

1. Group id / base package (e.g. `io.github.<name>`)? Author name for `fabric.mod.json`?
2. SPDX id: `AGPL-3.0-only` or `AGPL-3.0-or-later`?
3. GitHub repo URL (for `fabric.mod.json` contact info) or leave blank?
4. Mod icon: placeholder for now?
5. Anything about the Fabric / Loom setup for 26.3 that you cannot verify (see section 3, rule 1)?

## 3. Ground rules

1. **Verify before assuming.** 26.3 is newer than anything you can rely on from memory. Class names, method names and signatures below are *hints in Mojang naming*, not facts. Check the actual Fabric API docs, the Fabric template for this version, and the game's own classes (decompile/sources in the dev environment) before you write code against them. If something differs from this spec, the real API wins; note it in `DECISIONS.md`.
2. **Ask before big decisions.** Anything that changes architecture, adds a dependency, or deviates from this spec: stop and ask the user in chat. Small, reversible choices: pick a sane default and log it in `DECISIONS.md` (one line each: what, why).
3. **Do not code while things are unclear.** If a requirement is ambiguous, ask first. Do not start editing files to "see what happens".
4. **One milestone at a time** (section 11). At the end of each milestone: summarize what works, how to test it manually, what you decided, then stop and wait.
5. **Client-only and vanilla-safe.** No mixin or hack may require anything on the server. Do not try to bypass server rules or anticheat. Interaction must look like what a human player could send: valid range, plausible hit positions, one container at a time, rate limited.
6. **Never destroy items.** No operation may drop, void or duplicate items. If state looks wrong, abort the job, re-sync, tell the user.
7. **Separate pure logic from Minecraft glue** (section 5) so the logic is unit-testable without launching the game.
8. Keep dependencies minimal. Fabric API only. A JSON library only if the game's bundled one (Gson) is not enough.

## 3b. Interaction facts (verify all of these)

- The server validates container opens by **distance only**: the block must be within the player's block interaction range (plus a small tolerance). There is **no line-of-sight check** in vanilla, so containers behind walls can be opened. Read the real range from the player's attributes (block interaction range) instead of hardcoding 4.5, and subtract a safety margin (config, default 0.25 blocks). Distance is measured from the eye position to the nearest point of the block.
- Only **one container menu** can be open at a time. Opening a second one closes the first. All work is strictly sequential: open, wait for content sync, read/click, close, next.
- Open a container by sending the normal "use item on block" interaction with a `BlockHitResult` you build yourself (target pos, a face pointing toward the player, a hit location on that face, `inside = false`). Do not change the player's real look direction. Hint: `MultiPlayerGameMode.useItemOn(...)`.
- The player must not be **sneaking** with a non-empty hand or the interaction may try to place/use the held item instead of opening the container. If the player is sneaking when a scan starts, wait/refuse with a message; do not force-change sneak state.
- A chest with a **solid block above it** cannot be opened (barrels and shulkers have their own rules: shulker boxes need their lid space clear). Detect from the client world and skip; also handle "open did nothing" via timeout and mark the container unreachable.
- **Double chests** are one 54-slot menu. Treat the pair as one container keyed by a canonical position.
- Trapped chests emit a redstone signal while open. Config option `includeTrappedChests` (default true) with a clear warning in the config comments.
- Opening/closing plays lid animation and sound for everyone nearby. That cannot be avoided; do not try.
- Clicks inside an open container go through the container click path with the correct container id and state id. Hint: `MultiPlayerGameMode.handleInventoryMouseClick(...)`. If state ids desync, abort the job and re-open.
- To open a container **without showing the screen**: the menu must still be created and synced. Hint: let the open-screen packet handler run, but cancel the final `Minecraft.setScreen(...)` while the scanner/mover is active (mixin), and keep a reference to `player.containerMenu`. Close with the normal container-close path. Verify how the real code assigns `containerMenu` before relying on this.

## 4. Behaviour overview

1. Player presses the keybind. The mod computes the set of **reachable containers** (in range now), then opens the Storage Terminal screen immediately from the **cache**.
2. In the background it **refreshes** every reachable container (sequentially, nearest first). The screen updates live as snapshots come in, with a small "scanning n/m" indicator.
3. The screen shows an aggregated list of all items across reachable containers, with search and sort.
4. **Take:** clicking an item in the list moves items from the right container(s) into the player's inventory.
5. **Put:** clicking a stack in the player's inventory panel (or a "deposit all" button) moves it into the best container(s).
6. Closing the screen cancels any pending work cleanly.

Scope of the list: only **reachable** containers are actionable. Optionally (config `showRememberedOutOfReach`, default false, stretch goal) cached containers that are out of reach may be shown greyed out and read-only.

## 5. Architecture

Two layers, strictly separated:

```
core/   pure Java, no net.minecraft imports (where practical)
  ItemKey, StackEntry          item identity + count
  ContainerSnapshot            slots as ItemKey+count, capacity, type, tags
  StorageIndex                 aggregation across snapshots, search, sort
  Planner                      "take N of X" / "deposit stack" -> ordered list of ops
  Job, JobQueue                generic sequential job runner (will be reused for crafting later)
  CacheModel + serialization   versioned schema

mc/     Minecraft/Fabric glue
  Discovery                    find reachable containers from the client world
  ContainerSession             open -> await sync -> snapshot -> click -> close state machine
  ScreenSuppression (mixin)    hide vanilla container screens during sessions
  TerminalScreen               the custom UI
  Input                        keybind, tag-toggle keybind
  ConfigManager, CacheStore    JSON on disk
  Adapters                     convert ItemStack <-> ItemKey, menu slots <-> core slots
```

Rules:
- `core` must be testable with plain JUnit, no game launch.
- `StorageSource` abstraction: the planner and index talk to "sources of items", of which a physical container is one kind. Later, shulker contents, bundles and machines are new kinds of source. Do not hardcode "container == chest".
- `JobQueue` is generic (a job is a list of steps with preconditions, progress and cancel). Crafting will reuse it.

## 6. Data model

**ItemKey**: item + data components, *excluding count*. Two stacks are the same key if `ItemStack.isSameItemSameComponents` says so (verify the current name). Damaged tools, named items, enchanted items, potions etc. are separate keys. Shulker boxes in an item slot are opaque items in v1 (their contents are not indexed).

**ContainerKey**: dimension + canonical block position (for double chests: the lower of the two positions by a fixed ordering).

**ContainerRecord**:
- `key`, `type` (CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX), `positions` (1 or 2)
- `slots`: list of (ItemKey?, count)
- `lastRefreshed` (game or wall time, store both if useful)
- `tags`: set of `ContainerTag`. v1 defines the enum with **IGNORE** only. Design it so INPUT, OUTPUT, SMELTER, BREWER, etc. can be added later without a schema break (store tags as strings).
- `state`: FRESH, STALE, UNREACHABLE (could not be opened), MISSING (block no longer exists)

**Cache file**: JSON, one file per world/server, under the game's config or data dir (e.g. `storageterminal/<world-or-server-id>.json`). Include `schemaVersion`. Key worlds by server address for multiplayer and by world folder name for singleplayer (verify what is available on the client). Unknown schema version: start fresh and keep the old file renamed, never crash.

The cache is also **passively updated**: whenever the player opens any supported container manually, record its contents.

## 7. Scanner and sessions

**Discovery**
- From the eye position and the effective reach, enumerate block positions in the bounding box, test the real distance to the block's nearest point, keep those that are a supported container type (use block / block-entity type from the client world, only in loaded chunks).
- Skip ignored (tag IGNORE) containers entirely: never open them.
- Skip chests blocked from above; skip shulker boxes whose lid space is blocked. Skip containers already refreshed within `minRefreshIntervalMs` (config) unless the user pressed the explicit refresh button.
- Order: nearest first.

**ContainerSession state machine** (one at a time):
`IDLE -> OPENING -> AWAIT_SYNC -> (SNAPSHOT | OPERATE) -> CLOSING -> COOLDOWN -> IDLE`
- OPENING: send the interaction.
- AWAIT_SYNC: wait for the menu of the expected type with a fresh content sync. Timeout (config, default ~1000 ms) -> mark UNREACHABLE, close if anything opened, move on.
- SNAPSHOT: copy the menu's container slots into a `ContainerSnapshot`, write to cache, notify the UI.
- OPERATE: used by take/put (section 9, 10), after the same sync.
- COOLDOWN: configurable minimum delay between opens (default: a couple of ticks) and a hard cap on opens per second. Defaults must be conservative; the user can raise them.

**Abort conditions** (cancel session and queue, close the container, never leave a menu open): the user opens any other screen, player dies or changes dimension, disconnects, moves out of range of the current target, takes damage (config, default on), or presses Esc on the terminal screen.

**Disconnect/dimension change**: persist cache, drop all in-memory state.

## 8. Terminal screen (UI)

Custom `Screen` (not a container screen), vanilla look (use vanilla GUI textures/sprites, no custom art needed yet).

- **Top**: search box (focused on open), sort button/cycle, status line (`scanning 12/40`, `stale` indicators, errors).
- **Middle**: scrollable grid of aggregated entries: item icon + count (use compact formatting like `1.2k` for display; exact count in tooltip).
- **Bottom**: the player's inventory (main + hotbar) in vanilla layout, used for deposit and as the take target display.
- **Search**: plain text matches item display name; `@modid` matches namespace; `#tag` matches item tags. Case-insensitive. Keep it simple.
- **Sort**: by name, by count (desc), by mod id, by last-changed. Remember the last choice in config.
- **Tooltip** on a grid entry: the item's normal tooltip plus the exact total count. No per-container breakdown (the user does not care which chest holds what).
- **Click hints** at the bottom of that tooltip, in a muted color: `Click: 1 stack · Right-click: half` / `Shift-click: fill inventory`.
- **Not-rescanned marker**: a grid entry whose count comes (partly) from saved contents that have not been rescanned this session gets a small amber square in its top-left corner.
- **Busy look**: entries that are part of a running take/put job are dimmed until the job finishes.
- **Interaction**:
  - Left click on a grid entry: take one full stack (or all if less).
  - Right click: take half a stack (rounded up).
  - Shift + left click: take as many full stacks as fit in the inventory.
  - Click a stack in the inventory panel: deposit that stack.
  - Shift + click in the inventory panel: deposit everything of that item type.
  - "Deposit all" button: deposit the inventory (excluding hotbar by default, config).
  - A **cancel** control while a job is running.
- Show clear, non-spammy feedback: success, partial (inventory full / no space), failed (container changed or unreachable).
- Handle both GUI scale extremes gracefully. No crashes on tiny windows.

## 9. Take

Input: `ItemKey`, requested amount (a stack, half stack, or fill-inventory).

Planner (core):
1. From the current index, list source slots holding that key, within reachable, non-ignored containers.
2. Prefer **nearest container first**, and **partial stacks before full stacks** (this tends to empty containers and free space).
3. Compute the amount that actually fits in the player's inventory (account for existing partial stacks). If it is 0, abort with "inventory full".
4. Output an ordered list of ops: `(container, slot, count)`.

Execution: for each distinct container, one session: open, await sync, **re-verify** the slot still holds the expected key and count (the cache may be stale). If it differs, re-plan once from the fresh data; if it still fails, report partial result. Use the standard quick-move for whole stacks; for partial counts use pickup/place click sequences and return any leftover to the same slot. After clicks, read the resulting slot contents from the synced menu and update the cache. Close the container.

Never leave the cursor holding an item when a job ends. If it does, put it back into the source slot or an inventory slot, log it, tell the user.

## 10. Put

Input: a stack (or all stacks of a key, or the whole inventory).

Planner (core), per stack:
1. Containers that already hold the same key and have room in a partial stack (nearest first).
2. Containers that already hold the same key and have an empty slot.
3. Any non-ignored container with an empty slot (nearest first).
4. No space anywhere: report, leave the item in the inventory.

v1 has no category sorting and no hot/cold logic. Containers that are shulker boxes follow the same rules; the game itself rejects putting a shulker box into a shulker box, so never plan that.

Execution mirrors Take: open, await sync, re-verify free space, move the stack with quick-move from the player's inventory slot in that menu (find the player-inventory slot index in the open menu's slot list), read back what moved, update the cache, close. If it moved less than planned, plan the remainder against the next container.

## 11. Milestones (build one, then stop and report)

**M0: Scaffold.** Project from the official Fabric template for 26.3, Java, Mojang mappings, client-only entrypoint, `fabric.mod.json` (id `storageterminal`, license, `environment: client`), `LICENSE` (AGPL-3.0 full text), `README.md` (short), `DECISIONS.md`, gradle wrapper, a client command namespace `/storageterminal` (client-side command, e.g. `version`). Builds and launches. *Done when:* the client starts with the mod loaded and `/storageterminal version` prints something.

**M1: Discovery, session, cache.** `core` model + serialization with unit tests. `Discovery`, `ContainerSession` (open, sync, snapshot, close), screen suppression, cache store, passive caching of manually opened containers, IGNORE tag toggle keybind (look at a container, press the key) with feedback, debug client commands: `scan` (refresh reachable containers), `dump` (print aggregated totals to chat/log), `cache clear`. *Done when:* in a test room with ~20 mixed containers (single/double chests, trapped, barrels, a placed shulker, one blocked chest, one behind a wall), `scan` snapshots all reachable ones with no vanilla GUI flashing, `dump` totals match reality, ignored containers are never opened, and the cache survives a restart.

**M2: Read-only terminal screen.** Before any UI code: the user picks a layout from the mockups (https://claude.ai/artifact/DajJgNFdufS674USGEvLtf) Chosen: layout A (compact, chest width), see `DECISIONS.md`; the screen follows it. The keybind opens the screen from cache instantly; background refresh updates it live; search, sort, scrolling, tooltips, status line, inventory panel (display only). *Done when:* the totals on screen match the real contents, search/sort work, closing the screen mid-scan leaves no container open.

**M3: Take.** Planner (unit tested), job runner, left/right/shift take behaviour, verification, partial results, cancel. *Done when:* taking stacks from items spread over several containers works, a deliberately changed container (edit it manually between scan and click) is handled by re-verify, a full inventory is reported, no item is lost or duplicated (check counts before and after).

**M4: Put.** Planner (unit tested), deposit stack / all-of-type / deposit all, free-space handling, cancel. *Done when:* depositing fills existing stacks first, then same-item containers, then free containers; a full storage is reported; ignored containers are never used; counts balance before and after.

**M5: Hardening and polish.** Config file with all values documented in comments; Mod Menu soft integration (optional); manual test checklist in `docs/TESTING.md`; handle disconnects, dimension changes, lag spikes (simulate by raising timeouts), chunk unloads while running; remove debug noise; README with install/usage. *Done when:* a 30-minute play session in a real multiplayer-style setup (e.g. a local dedicated server) finds no stuck menus, no lost items and no exceptions in the log.

## 12. Roadmap (design hooks only, do NOT implement)

- **Carried shulker boxes**: take a shulker out of a chest, place it on a user-designated spot, open it, move items, break it with a tool, pick it up, return it. Manual first; redstone-assisted breaking later as an option.
- **Bundles**: index bundle contents (synced with the item data), take/put via the selected-item mechanism, use for small quantities of many item types.
- **Usage tracking and hot/cold rebalance**: log withdrawals/deposits per item with decay, suggest moving cold bulk into shulkers, keep hot items loose in chests. Suggestions first, automation later.
- **Crafting**: recipe + target amount; dependency resolver that expands the recipe tree (log -> planks -> sticks) into an ordered job list; uses a crafting table in reach, filled via slot clicks; builds on `JobQueue`.
- **Machines**: furnaces/blast furnaces/smokers and brewing stands, either directly (in reach) or via **container tags** (INPUT/OUTPUT) for a chest-to-smelter-array setup, where the mod fills input containers and collects from output containers.
- Right-click a configured block to open the terminal; highlight the matching containers in the world; compatibility with item-list/recipe viewer mods; showing remembered out-of-reach containers.

Hooks to leave now: extensible string-based tags, `StorageSource` abstraction, generic `JobQueue`, `ItemKey` that includes components, a version field in the cache schema.

## 12b. Interaction improvements (after v1, do NOT implement yet)

Feedback from the first manual play test (2026-11-18): the terminal's clicks work but feel limited. **Before designing any of this, compare how Refined Storage and AE2 handle each point** (their grid/terminal controls, modifier keys, what feels natural to players used to them) and write the chosen design here. Then decide with the user.

- **Shift-drag.** Hold shift and drag the mouse over several stacks to move them all, like vanilla's shift-drag in inventories: over inventory stacks to store each one, and possibly over grid entries to take a stack of each.
- **Mouse-wheel moves (Item Scroller style).** Scrolling over an entry or an inventory stack moves items one at a time (or a stack at a time with a modifier), the way the Item Scroller mod works in vanilla screens. Must not clash with scrolling the grid: decide which wins where, and check compatibility when Item Scroller itself is installed.
- **Take exactly 1 item.** A click to take a single item (which button or modifier: see how RS/AE2 do it, e.g. a modifier + click, middle click, or the scroll wheel above).
- **Move to the best spot when the terminal opens.** On opening, move the player to the position from which they can reach all the containers of the storage area at once (or as many as possible), usually the center of the chest area, so nothing is left out of reach.
  - Finding the spot: from the cached containers near the player, find standable positions (solid floor, 2 blocks of air, not in lava/water) whose eye position has every container within reach (with `reachMargin`); if none covers all, take the one covering the most, ties to the shortest walk.
  - Moving there must look like a player: walk with normal movement (server-validated, no teleport, no flying), only over safe ground, short distances only (a few blocks), cancel on any player input, damage, or when blocked. Then the terminal opens or refreshes from there.
  - Open questions: walk automatically or only show the spot (highlight/arrow) and let the player step there; what counts as "the storage area" (all cached containers within N blocks? a user-marked area?); how this fits the roadmap item "right-click a configured block to open the terminal" (a terminal block placed at the center would make the spot obvious).
  - RS and AE2 don't need this (wireless/network range), so the comparison is mostly about the other points.

## 13. Config (JSON, every key commented in the README)

`reachMargin` (0.25), `openCooldownTicks`, `maxOpensPerSecond`, `syncTimeoutMs` (1000), `minRefreshIntervalMs`, `includeTrappedChests` (true), `abortOnDamage` (true), `depositIncludesHotbar` (false), `defaultSort`, `showRememberedOutOfReach` (false), `debugLogging` (false). Missing/invalid keys fall back to defaults; never crash on a bad config.

## 14. Definition of done for v1

- Works on a vanilla multiplayer server and in singleplayer without any server-side component.
- No lost or duplicated items in any tested scenario.
- No stuck or invisible open containers after cancel, disconnect, death or dimension change.
- Pure logic covered by unit tests; manual test checklist written and run.
- AGPL-3.0 license file present, `DECISIONS.md` complete, README written.
