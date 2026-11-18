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
