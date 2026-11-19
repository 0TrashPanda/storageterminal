# Decisions

One line each: what, why. Newest at the bottom.

## Answers to SPEC section 2 (2026-11-18)

- Base package `dev.trashpanda.storageterminal`, maven group `dev.trashpanda`. Why: the user's choice; `io.github.0trashpanda` is not a valid package name (starts with a digit).
- Author in `fabric.mod.json`: `0TrashPanda`. Why: the user's GitHub handle.
- License SPDX id `AGPL-3.0-only`. Why: the user's choice.
- Contact/sources URL `https://github.com/0TrashPanda/storageterminal`. Why: the user's choice; the repo may not exist yet.
- Mod icon: a generated 128x128 placeholder PNG, to be replaced later. Why: the user's choice.

## Fabric / Loom setup for 26.3 (to confirm at M0)

- Known from the user's working 26.3 ports (show-me-your-skin, LibrarianTradeFinder): plugin id `net.fabricmc.fabric-loom` (not `fabric-loom`), no `mappings` line, plain `implementation` instead of `modImplementation`, Fabric Loader 0.20.1, Fabric API `0.165.2+26.3`, Gradle 9.4.0. Re-check against fabricmc.net/develop at M0.
- Not yet verified: the exact Java version the 26.3 template targets. Check at M0.

## Language (2026-11-18)

- Java instead of Kotlin (overrides SPEC section 1, with the user's OK; they had no preference). Why: no Fabric Language Kotlin dependency to verify or require, mixins are Java anyway, decompiled game sources read 1:1, matches the user's other Fabric 26.3 projects.

## M0 (2026-11-18)

- Versions verified against meta.fabricmc.net and maven.fabricmc.net: Fabric Loader 0.20.1, Fabric API 0.166.2+26.3 (newest for 26.3), Loom 1.19.4 (newest stable; 1.20 is alpha), Gradle 9.8.0. Why: newest stable at the time.
- Java 25 (`options.release = 25`). Why: what 26.3 requires and what the user's other 26.3 projects use.
- Groovy `build.gradle` modeled on the user's LibrarianTradeFinder 26.3 setup rather than the generator's zip. Why: known to build on this machine; same plugin and layout as the template.
- Single source set, no `splitEnvironmentSourceSets`. Why: the mod is client-only, so there is nothing to split.
- Packages: `dev.trashpanda.storageterminal.core` (pure logic, from M1) and `.mc` (Minecraft glue). Entrypoint `mc.StorageTerminalClient`.
- Client commands use Fabric API `ClientCommandRegistrationCallback` + `ClientCommands.literal` (checked in fabric-command-api-v2 3.1.2; the old `ClientCommandManager` name is gone).
- SPDX header `// SPDX-License-Identifier: AGPL-3.0-only` on every source file instead of the long GPL boilerplate. Why: short, and the full text is in LICENSE.
- Mod version format `0.1.0+26.3`. Why: semver plus the Minecraft version, like the user's ports.

## UI mockups (2026-11-18)

- Terminal screen mockups made before M2, at the user's request: https://claude.ai/artifact/DajJgNFdufS674USGEvLtf. Layout A (compact, 9 columns, chest width) vs layout B (13 columns plus a sidebar). Not chosen yet; M2 waits for that choice.
- **Layout A chosen** (compact, 9 columns, controls in a top bar). Layout B (sidebar) stays the fallback if settings and controls outgrow the top bar. So M2 builds the controls (search, sort, rescan, deposit all, status line) as separate widgets placed by one layout function, not hard-wired to top-bar positions, so moving them to a sidebar later is a layout change only.
- The mockups also suggest three additions to section 8: click hints in the item tooltip, an amber corner marker for slots whose saved contents haven't been rescanned yet, and a dimmed "busy" look for items in a running job. Confirmed by the user and added to SPEC section 8.

## M1 (2026-11-18)

- Screen suppression: a mixin cancels `MenuScreens.create` at HEAD while a session waits, and creates the menu itself (`type.create(id, inventory)`, assigned to `player.containerMenu`). Why: same effect as cancelling `setScreen`, but no mixin into the `ScreenConstructor` interface default method. In 26.3 screens are set via `minecraft.gui.setScreen`.
- Server-side close of one of our hidden menus skips vanilla's `handleContainerClose`. Why: vanilla calls `clientSideCloseContainer`, which does `gui.setScreen(null)` and would close the terminal screen (M2).
- Closing a session menu: send `ServerboundContainerClosePacket` and reset `containerMenu`; `menu.removed()` is not called. Why: we never hold a cursor item in M1, and it keeps the current screen untouched.
- Late-open guard: for 5 s after a session ended without a menu, a container menu that arrives (and was not clicked by the player in the last 2 s) is closed at once without a screen. Why: a slow server must never leave an invisible container open.
- Passive caching: the player's own `useItemOn` is remembered for 2 s; a matching vanilla container screen is tracked and snapshotted on content sync and again when it closes.
- `ItemKey.components` = canonical JSON (sorted keys) of the `components` field from `ItemStack.CODEC`. Why: exact identity incl. damage, names, enchantments; decodable back to a stack for display.
- Cache file: `config/storageterminal/cache/sp_<world folder>.json` or `mp_<server address>.json`, written atomically (temp file + move). Unreadable or unknown schema: renamed to `.unsupported-<time>` and a fresh cache starts.
- Config defaults (file comes in M5): `openCooldownTicks` 4, `maxOpensPerSecond` 4, `syncTimeoutMs` 1000, `minRefreshIntervalMs` 30000, `reachMargin` 0.25.
- Containers whose last open failed (UNREACHABLE) or that are MISSING or ignored are left out of totals, even if older contents are cached.
- Copper chests count as CHEST (they extend `ChestBlock` and use the same menus). Ender chests are not containers here.
- Blocked chests follow vanilla `ChestBlock.isChestBlockedAt` (solid block or sitting cat on top), for either half of a double chest. Shulker lid check copies vanilla's private `ShulkerBoxBlock.canOpen`.
- Sneaking: `scan` refuses to start; during a scan the next open waits until the player stops sneaking.
- Ignore-toggle key defaults to K. In 26.3 key codes are Minecraft's own (`InputConstants.KEY_K` = 14), not GLFW codes.
- The generic `JobQueue` arrived in M1 instead of M3, because a scan is already a job.
- `/storageterminal scan` always rescans (ignores `minRefreshIntervalMs`). `scan`, `dump` and `cache clear` are marked DEBUG; M5 decides which stay.
- Tests: JUnit 5.11.4 for `core`; an in-game acceptance test (`src/gametest`, Fabric client gametest, `./gradlew runClientGameTest`) builds the M1 test room and checks it automatically.

## M2 (2026-11-18)

- Tooltip shows the item's own tooltip, the exact total ("1,180 stored"), the not-rescanned note and click hints. No per-container breakdown or positions: the user does not care which chest holds what (overrides SPEC section 8 and the mockup; SPEC updated).
- Open-terminal key defaults to B. Vanilla 26.3 uses G for quick actions; B is free.
- The terminal lists only containers in reach (found when it opens and after each refresh). Opening shows the cache at once, then refreshes in the background without forcing (containers refreshed in the last 30 s are skipped). The Scan button forces a full rescan.
- Closing the terminal cancels the running scan and saves; the scan no longer aborts for the terminal screen itself, only for other screens.
- Compact counts round down (1,180 shows as 1.1k), so the grid never shows more than there is. Exact count is in the tooltip.
- "Recent" sort: each container remembers when each item's count last changed (new optional `changedAt` field in the cache file, still schema 1: older files without it load fine).
- Sort order is remembered for the session only; saving it to the config file comes with the config in M5.
- Grid rows shrink to fit small windows (6 max, 1 min). The panel and slots are drawn with plain fills in the vanilla colours, no textures.
- Status line falls back to "N containers · M skipped" when the full text is too wide, with the breakdown on hover.
- Deposit all is shown but disabled until M4; grid clicks do nothing until M3.
- In 26.3 screens draw in `extractRenderState(GuiGraphicsExtractor, ...)`, mouse buttons are `InputConstants.MOUSE_BUTTON_LEFT` = 1 / RIGHT = 3, and input arrives as `MouseButtonEvent` / `KeyEvent`.
- The in-game test now also checks the terminal (real B key, typing into search, `#c:ingots`, `@minecraft`, name sort, a small window, closing mid-scan with no container left open on client or server) and saves screenshots to `build/run/clientGameTest/screenshots/`.

## M3 (2026-11-18)

- Clicks: left = one stack (or all if less), right = half a stack rounded up (`ceil(min(maxStack, stored) / 2)`), shift + left = as much as fits. Shift is read from the click event or, as a fallback, from the keyboard state.
- 26.3 names: `ClickType` is now `ContainerInput`, and the click call is `MultiPlayerGameMode.handleContainerInput(containerId, slot, button, input, player)`; it applies the click on the client and sends the changed slots.
- Whole stacks move with shift-click (QUICK_MOVE), so items land where vanilla puts them (hotbar first). Part of a stack goes through the cursor: right-click picks up exactly half when that is the amount, otherwise pick up all and drop/return one at a time, whichever needs fewer clicks; then the cursor is emptied.
- At most 6 clicks per tick, so a take looks like a fast player rather than a burst.
- The result is measured: the item count in the inventory after the job minus before, never the planned amount. Each container's contents are read back after waiting 2 ticks plus the player's ping, and stored in the cache.
- Re-verify: slots are chosen from each container's fresh contents when it opens; if it holds less than the cache said, the rest is taken from the next container. The planner from cached data decides only which containers to visit, nearest first.
- Cursor safety: before a container closes (normally or on abort), anything on the cursor is put back: into the slot it came from, else an inventory slot that takes it whole, else a free container slot. Never dropped.
- A click on an entry cancels a running background scan; a second click while a take runs is refused with a message.
- Feedback (success / partial / failed, with a check, "!" or cross icon) shows in the status line for 5 s, or in the action bar when the terminal is closed. Status during a take: "Taking 18 Iron Ingot (1/3)" with a Cancel button; the item's entry is dimmed.
- Abort checks moved into a shared `JobGuard` used by scan and take.
- In-game test for M3: one stack from three containers, half a stack that splits a stack, a shift-click on a chest emptied after the scan (takes only what is really there), a full inventory, and closing the terminal right after a click. Each check counts the item on the server (containers, inventory, cursor) before and after.

## M4 (2026-11-18)

- Clicks in the inventory panel: click a stack to store it, shift-click to store every stack of that item (hotbar included). "Deposit all" stores the main inventory only; the hotbar only with `depositIncludesHotbar` (default false). Armor and offhand are never touched.
- Where items go, per item type: containers with a partial stack of it that has room, then containers that hold it and have an empty slot, then any container with an empty slot; nearest first within each group (`PutPlanner`). Containers never looked into (no snapshot) are not used, since their free space is unknown. Ignored containers are never used. A shulker box item is never planned into a shulker box.
- Items move with shift-click (QUICK_MOVE) from the inventory slot in the open container's menu, one click per stack, at most 6 per tick. Inside one container vanilla fills matching stacks first, then empty slots; so a container chosen for its partial stack may also get the rest in its empty slots before the next container's partial stacks are used. Accepted: simpler and fewer clicks than placing exact amounts with the cursor.
- Whatever does not fit stays in the inventory and goes on to the next planned container; if the cache was wrong about free space, the job finds out on the spot and reports "no free space". If the cache already shows no room, nothing is opened.
- The amount stored is measured from the inventory. One container visit stores all pending stacks planned for it, so "Deposit all" with many item types opens each container once.
- `ContainerJob` interface: scan, take and put expose their current session to the menu hooks the same way.
- In-game test for M4: gold joins an existing partial stack; iron goes to free space and never into the ignored chest; shift-click stores all cobblestone stacks; Deposit all keeps the hotbar; full storage is reported both when the cache is out of date and after a rescan, and the ignored chest stays untouched. Each check counts items on the server before and after.

## M5 (2026-11-18)

- Config file `config/storageterminal/config.json`: JSON with `//` comments on every key, read with Gson's lenient mode. Created with defaults on first start. A missing key uses its default quietly; a wrong type or out-of-range value uses its default and is logged; a broken file gives all defaults. Never crashes.
- `showRememberedOutOfReach` (a stretch goal in SPEC section 13) is not implemented, so it is not in the config.
- The sort order is saved to `defaultSort` whenever it changes (the file is rewritten from the template, so hand-written comments are not kept).
- Mod Menu integration skipped: it is optional in the spec and needs a config screen to be useful; the commented file covers v1. Can be added later as a soft dependency.
- Debug noise: `/storageterminal scan` and `dump` exist only with `debugLogging` on; `version` and `cache clear` always. Routine log lines (cache loaded, take/store results, dump) go to DEBUG unless `debugLogging` is on; only "loaded", warnings and the rare late-open close stay at INFO.
- Chunk unloads: a job stops cleanly ("the area was unloaded") if its current container's chunk unloads; containers in unloaded chunks are never chosen.
- Lag: covered by `syncTimeoutMs` (late opens are closed by the late-open guard) and by waiting 2 ticks plus ping before reading a container back after clicks.
- Multiplayer: a second in-game test runs scan, take-all and store-all against a dedicated server over a real connection (Fabric's `createServer()`); the mod is not loaded on the server. The 30-minute play session from the spec is in `docs/TESTING.md` as a manual step; it was not done here.
- README rewritten for players (keys, clicks, search, settings table, commands, caveats: trapped chests, visible lid animations, anti-cheat). Manual checklist in `docs/TESTING.md`.

## New controls (2026-11-19, SPEC 12b)

- Alt-click moves all of an item (take: as much as fits; store: every stack of it). Shift-click now moves one stack, like a plain click for now; plain clicks are kept for the cursor design, which is not built yet.
- Shift + wheel moves one item per notch. Up always stores, down always takes, over grid entries and inventory stacks alike. Sideways scroll counts too, since some systems turn Shift + wheel into it. Plain wheel only scrolls the grid.
- Notches that come in while a wheel job runs (same item) are added up and done as one job right after; a different item while busy gets "Busy". A failed wheel job drops the queued notches.
- Storing a limited number of items: pick the stack up, right-click single items into the container (partial stacks of it first, then empty slots, `Slot.mayPlace` respected), put the rest back where it came from. Cleanup puts the cursor back into that slot if anything goes wrong.
- Taking onto a given stack: the take uses the pick-up/place path and places onto that inventory slot when the items fit there; else the usual target.
- Wheel-up over a grid entry stores from the smallest stack of that item, main inventory before hotbar.
- In-game tests: Shift + wheel take (two quick notches) and store with server-side counts; Shift-click stores one stack, Alt-click the rest; the dedicated-server test uses Alt-click and a Shift + wheel round trip.

## Shift-drag (2026-11-19, SPEC 12b)

- Shift-drag with the left button: every inventory stack the mouse passes over is stored, every grid entry gets one stack taken; each at most once per drag. Shift-click goes the same way, so it is queued too instead of saying "Busy".
- Work that arrives while a job runs waits in a queue in `StorageService` and runs one job after another; stores dragged over in a row are merged into one put job, so each container is opened once. A queued stack is skipped if its slot now holds a different item. Waiting stacks and entries are dimmed like running ones. Closing the terminal or Cancel drops the queue.
- Fix found by the new test: a job only becomes current on the next tick, so between two queued jobs nothing looked "running". `runningTake()`/`runningPut()` now include a waiting job (`JobQueue.currentOrPending`), so Busy checks, the status line and the tests see it.
- In-game tests: Shift-drag over three inventory stacks stores all three, over two grid entries takes both, with server-side counts and a check that server and client have no container left open (also after Shift + wheel). The Deposit all test now waits until the client sees its test items instead of a fixed 3 ticks.

## Cursor (2026-11-19, SPEC 12b)

- User choices: a plain click / right-click on a grid entry puts a stack / half onto the cursor (Shift-click still goes straight into the inventory); clicking outside the panel while holding drops it like vanilla.
- The held stack is never on the real cursor between clicks: it stays in an inventory slot (`heldSlot`), drawn at the mouse and hidden in its slot. Picking up a whole stack needs no packet at all. Every other click on it is sent at once as real clicks in the player's own inventory menu (pick up, the player's click, put the rest back), so vanilla does placing, swapping, merging, splitting, dropping and bundles. Nothing can be lost when a container closes, because nothing waits on the cursor.
- Those clicks go to menu 0, which the server only accepts while no other menu is open; they wait (a few ticks at most) while one of our containers is open or opening, and run before the job ticks so the order of packets stays right.
- Take to cursor: an empty slot (main inventory first) is chosen as the parking slot and the take puts the items there (`preferredInventoryIndex`); with no empty slot it says so instead of taking. Storing the held stack is a normal put of that slot (right-click: limit 1).
- Deposit all, Alt-click, Shift-click, Shift-drag and Shift + wheel never store the held stack. Closing the terminal just forgets the hold; the items stay in the slot.
- Not done: vanilla's plain drag to spread a held stack over slots, and the grid bundle fill/empty actions from SPEC 12b.
- Tests: the existing in-game tests use Shift-click where they meant "store this stack" and put a held stack down before the next take. New checks: take to cursor, store one / all from the cursor, swap, split and merge, a held bundle taking a stack in (vanilla logic, checked on the server), dropping outside the panel (6 item entities on the ground), cursor empty and no container open on both sides. The dedicated-server test holds 64 iron and stores it again.
