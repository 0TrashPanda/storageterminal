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
