# Storage Terminal

A client-side Fabric mod for Minecraft 26.3. Stand among your chests, press a key, and see everything stored around you on one screen. Take items out and put them away from there, without opening each chest yourself.

It works on unmodified vanilla servers. Nothing is installed on the server: the mod only does what you could do by hand, opening the containers in reach one at a time with normal clicks, while their screens stay hidden.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- Fabric API

## Install

Put the Storage Terminal jar and Fabric API in your `mods` folder. Only you need it; the server doesn't.

## Use

| Key or click | What it does |
|---|---|
| **B** | Open the terminal. It shows what is in every chest, trapped chest, barrel and placed shulker box in reach, then rescans them in the background. |
| **K** (looking at a container) | Ignore it, or stop ignoring it. Ignored containers are never opened or used. |
| Click an item | Take one stack (or all of it, if there is less). |
| Right-click an item | Take half a stack. |
| Shift-click an item | Take as much as fits in your inventory. |
| Click a stack in your inventory | Store it. |
| Shift-click a stack in your inventory | Store every stack of that item. |
| **Deposit all** | Store your main inventory. The hotbar stays. |
| **Scan** | Look into every container in reach again. |
| **Cancel** | Stop the running take or store. |

Both keys can be changed in Options > Controls > Key Binds > Storage Terminal.

**Search:** type part of a name (`iron`), `@mod` for a mod (`@minecraft`), or `#tag` for an item tag (`#c:ingots`). Several words must all match.

**Sort:** the button cycles through Count, Name, Mod and Recent (recently changed). Your choice is remembered.

A small amber corner on an item means part of its count comes from what the mod saw earlier and hasn't rescanned yet.

Items are stored in containers that already have a partial stack of the same item first, then in containers that hold that item, then in any free space, nearest first. A shulker box is never stored inside a shulker box.

## Good to know

- **Reach:** only containers within your normal reach are used, and the mod stays a little inside it. Walls don't matter (vanilla has no line-of-sight check for opening containers).
- **Blocked containers** are skipped: a chest with a solid block or a sitting cat on top, or a shulker box with no room to open its lid.
- **Trapped chests** send a redstone signal while open, so anything wired to them triggers. Turn them off with `includeTrappedChests` if that matters.
- **Others can see it:** containers open and close (lid and sound) for everyone nearby, like when you open them yourself.
- **Speed:** at most 4 containers per second by default. Servers with strict anti-cheat may still dislike automated interaction; use it where that is allowed.
- **Safety:** the mod never drops or deletes items. Every take and store is checked against what really happened, and anything picked up with the cursor is put back before a container closes. Taking damage, opening another screen, moving out of reach, dying, changing dimension or disconnecting stops the current job cleanly.
- What the mod has seen is saved per world or server in `config/storageterminal/cache/`.

## Settings

The file is `config/storageterminal/config.json`, created on first start with a comment for every setting. Remove a line (or the file) to get the default back; wrong values fall back to their default and are reported in the log.

| Setting | Default | Meaning |
|---|---|---|
| `reachMargin` | 0.25 | Stay this many blocks inside your reach (0 to 2). |
| `openCooldownTicks` | 4 | Minimum ticks between opening two containers (0 to 100). |
| `maxOpensPerSecond` | 4 | Hard limit on containers opened per second (1 to 20). |
| `syncTimeoutMs` | 1000 | How long to wait for a container's contents before giving up on it. Raise it on laggy servers (200 to 30000). |
| `minRefreshIntervalMs` | 30000 | When the terminal opens, containers looked into less than this long ago are not opened again. The Scan button always rescans. |
| `includeTrappedChests` | true | Use trapped chests (they send a redstone signal while open). |
| `abortOnDamage` | true | Stop the current job when you take damage. |
| `depositIncludesHotbar` | false | Let Deposit all store the hotbar too. |
| `defaultSort` | COUNT | COUNT, NAME, MOD or RECENT. Updated when you change the sort. |
| `debugLogging` | false | Extra log output, and the commands `/storageterminal scan` and `dump`. |

## Commands

- `/storageterminal version`: the installed version.
- `/storageterminal cache clear`: forget everything seen in this world or server.
- With `debugLogging` on: `/storageterminal scan` (rescan everything in reach) and `/storageterminal dump` (print the totals to chat and the log).

## Building

```bash
./gradlew build
```

The jar ends up in `build/libs/`. Tests: `./gradlew test` (logic) and `./gradlew runClientGameTest` (in-game, opens a game window). The manual checklist is in [docs/TESTING.md](docs/TESTING.md).

## License

AGPL-3.0-only. See [LICENSE](LICENSE).
