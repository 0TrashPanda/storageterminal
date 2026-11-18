# CLAUDE.md: Storage Terminal

Fully client-side Fabric mod (Minecraft 26.3, Java, Mojang mappings) that shows all nearby containers as one storage screen and moves items behind the scenes. Full details are in `SPEC.md`. Read it completely before doing anything.

## How to work here

- **Start by asking the questions in SPEC.md section 2.** Do not write code before the user has answered.
- **Build one milestone at a time** (M0 to M5). After each: summarize what works, how to test it manually, what you decided. Then stop and wait for the user.
- **Ask before big decisions** (architecture changes, new dependencies, deviations from the spec). Small reversible choices: decide, and log one line in `DECISIONS.md`.
- **If something is unclear, ask in chat first.** Do not start editing files to explore. Wasted edits cost the user tokens.
- **Verify APIs against the real 26.3 sources.** SPEC.md names are hints, not facts. If the real API differs, the real API wins; note it in `DECISIONS.md`.
- Client-only. No server component, no custom packets, no bypassing server rules or anticheat. Interactions must be plausible for a real player: valid range, one container at a time, rate limited.
- Never drop, void or duplicate items. If state looks wrong, abort the job and re-sync.
- Keep pure logic (`core`) free of `net.minecraft` imports where practical, and unit test it.
- Do not implement anything from the roadmap (SPEC.md section 12) yet. Only leave the hooks listed there.
- Test versions or debug-only things must be clearly marked; final builds must not contain debug noise.

## Conventions

- Java, official Mojang names, 4-space indent. Prefer records and `final` fields for `core` data types. Use a formatter only if one is already configured.
- Mod id `storageterminal`. License AGPL-3.0 (headers/SPDX per the answer to the license question).
- Git repo is set up (branch `main`, no remote yet). Commit small and often with clear messages.

## Commands

- Build: `./gradlew build` (jar in `build/libs/`; also copied to `../../jars/fabric-26.3` by the workspace init script)
- Run client: `./gradlew runClient` (game dir `run/`)
- Unit tests (`core`, no game): `./gradlew test`
- In-game tests: `./gradlew runClientGameTest` (opens a game window, creates a test world, about 30 s; fails the build on a failed check). Tests live in `src/gametest`.
- Decompiled 26.3 sources for checking APIs: `./gradlew genSources`, then the `minecraft-merged-*-sources.jar` under `.gradle/loom-cache/minecraftMaven/`.
