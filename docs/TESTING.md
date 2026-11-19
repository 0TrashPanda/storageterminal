# Testing Storage Terminal

## Automated

- `./gradlew test`: unit tests for the pure logic (`core`): cache file, totals, search and sort, take and store planning, config file, rate limit, job queue.
- `./gradlew runClientGameTest`: opens a game window and runs two in-game tests, about a minute in total. Screenshots land in `build/run/clientGameTest/screenshots/`.
  - `ScanGameTest` (singleplayer): builds a test room (single, double and trapped chests, a barrel, a placed shulker box, a blocked chest, a chest behind a wall, an ignored chest, one out of reach) and checks scanning, the cache across a restart, the terminal (real key presses, search, sort, small window), taking and storing with real clicks, full inventory, full storage and closing the terminal mid-job. Every take and store counts the items on the server before and after.
  - `MultiplayerGameTest`: the same take and store flow against a dedicated server over a real connection.

## Manual checklist

Run before a release, in `./gradlew runClient` or a normal install. Use a survival world and, at least once, a dedicated server (a vanilla `server.jar` works: the mod is client-only).

Set up a room with: a single chest, a double chest, a trapped chest, a barrel, a placed shulker box, a chest with a block on top, a chest behind a wall, and a chest just outside reach. Put different items in each, some spread over several containers.

### Terminal

- [ ] **B** opens the terminal at once with what was seen before; "Scanning n / m" runs and the grid updates.
- [ ] No vanilla chest screen flashes up during the scan.
- [ ] Totals match what is really in the containers (check a few by hand).
- [ ] The blocked chest and the one out of reach are not counted; the status line says how many were skipped (hover for details).
- [ ] Search by name, `@minecraft`, `#c:ingots`; a search with no results shows "Nothing matches".
- [ ] The sort button cycles Count, Name, Mod, Recent; reopen the game: the last sort is kept.
- [ ] Tooltip shows the item, "N stored" and the click hints.
- [ ] Very small window and very large GUI scale: the grid gets fewer rows, nothing overlaps badly, no crash.

### Take

- [ ] Click: one stack (or all if less) onto the cursor, taken from several containers if needed. Its parking slot shows empty while held.
- [ ] Holding: click the grid stores it, right-click stores one. Click outside the window drops it (right: one). Closing the terminal keeps it in the inventory.
- [ ] In the inventory panel: pick up, put down, swap, right-click split, and a bundle taking a stack in, all like vanilla.
- [ ] Right-click: half a stack. Shift-click: one stack. Alt-click: as much as fits.
- [ ] Shift + wheel down over an entry: 1 item per notch; quick notches are added up. Wheel up stores 1.
- [ ] Change a chest by hand after the scan, then take from it: you get what is really there, the rest comes from other containers or is reported.
- [ ] Full inventory: "Inventory full", nothing moves.
- [ ] Cancel during a take: it stops, nothing is lost, nothing stays on the cursor.

### Store

- [ ] Shift-click an inventory stack: it goes to a container with a partial stack of the same item first.
- [ ] Shift-click: only that stack is stored. Alt-click: every stack of that item is stored.
- [ ] Shift-drag over several inventory stacks: each is dimmed, then stored. Over several grid entries: a stack of each is taken. Closing the terminal mid-drag stops the rest.
- [ ] Shift + wheel over an inventory stack: up stores 1 of it, down adds 1 from storage to it. Cursor empty afterwards.
- [ ] Deposit all: the main inventory is stored, the hotbar stays.
- [ ] All containers full: "no free space", the items stay in the inventory.
- [ ] Ignored container (**K**): never opened, never used, even when everything else is full. Press K again: used again.

### Interruptions (none may leave a container open or an item on the cursor)

- [ ] Esc during a scan, a take and a store.
- [ ] Take damage during a job (with `abortOnDamage` on).
- [ ] Open the inventory (E) or chat during a job.
- [ ] Walk out of reach during a job.
- [ ] Die, change dimension, disconnect during a job.
- [ ] Sneak: the terminal says "Stop sneaking to scan containers" and waits.

### Lag and settings

- [ ] On a laggy server (or with `syncTimeoutMs` set to 200), slow containers are marked unreachable instead of hanging, and a late-opening one is closed again without showing.
- [ ] Edit `config.json` with a wrong value: the game starts, the log names the bad value, the default is used.
- [ ] `debugLogging` false: no Storage Terminal lines in the log besides "loaded"; `/storageterminal scan` and `dump` are not available.

### Long session

- [ ] Play 30 minutes on a dedicated server using the terminal normally: no stuck or invisible containers, no lost items, no exceptions from Storage Terminal in the log.
