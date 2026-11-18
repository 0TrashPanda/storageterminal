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
