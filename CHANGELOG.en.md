# Changelog

All notable changes to this layered Baritone fork, newest first.

The fork keeps upstream Baritone's layered builder (`#litematica`, `#selection fill`) and adds the fixes below on top of it.

---

## v1.21.11-layered.3

Two problems this time: the game crashed with a null pointer in the middle of a build, and the debug log stopped writing once its file was deleted.

### Fixed: the null pointer crash

The stack landed in `BuilderProcess.isRemovalHeavy(BuilderProcess.java:1345)` with a null target:

```
Cannot invoke "baritone.api.schematic.ISchematic.widthX()" because "target" is null
  at baritone.process.BuilderProcess.isRemovalHeavy(BuilderProcess.java:1345)
  at baritone.process.BuilderProcess.createLayerScheduler(BuilderProcess.java:1326)
  at baritone.process.BuilderProcess.onTick(BuilderProcess.java:836)
```

`onLostControl()` cleared `schematic`, `realSchematic` and `layerScheduler`, but left `stopAtHeight` at the value of the build that just ended (131 in that session). Another process taking control runs that cleanup in the middle of a tick, so the rest of the tick kept going with the old target: `realSchematic = schematic` produced null, `buildLayerSlicing && stopAtHeight > 0` was still true, and a layer scheduler was built for a target that no longer existed.

- `onLostControl()` now clears `stopAtHeight`.
- `onTick` checks the target right after resolving it: if it is still null it logs `ticked without a target, stopping`, calls `onLostControl()` and returns null. Reporting inactive first is what keeps `PathingControlManager` from throwing `actively returned null PathingCommand`.
- `createLayerScheduler()` returns null without a target, and the caller only wraps `LayerSliceSchematic` when it got one.
- `isRemovalHeavy(null)` returns false.

### Fixed: the debug log went silent

Two separate causes:

- The file was deleted while it was open. The handle stays valid on Windows and POSIX, so every later line went into an inode with no directory entry and no new file was ever created until the game restarted. It now checks that the file still exists before writing and recreates it, header included.
- The 8 MB cap. The message told the player to toggle `#debug` off and on, but nothing implemented that reset, so `capped` stayed true for the session. `enabled()` now remembers the previous toggle state and clears the byte count, `capped` and the truncation flag when the log is turned back on.

### Tightened while reviewing

Guards added during the review pass, with normal behaviour unchanged:

- `PathingControlManager.executeProcesses()` has a reentrancy guard and iterates a copy of `active`. A process starting a path calculation re-enters this method mid tick; the nested pass modified `active` and the outer iterator threw `ConcurrentModificationException`, which is another way this mod could take the client down.
- `PathingBehavior` got `destOf(PathExecutor)`; a freshly built executor can have a null path and this line is written on every path event.
- `trim()`, `selectSweepPocket()` and `assemble(...)` in `BuilderProcess` no longer assume the pending set is present, since `onLostControl` and the stall handling both clear it.
- `approxPlaceable` and `refreshApproxPlaceable` no longer assume 36 inventory slots, and `subList(0, 9)` became `hotbarOnly`, which keeps a short list as it is.
- `getSchematic` inside the calculation context checks its snapshot.
- `startAtHeight / layerHeight` divides by `Math.max(1, ...)`; `layerHeight` is a setting and a zero used to be a division by zero.

---

## v1.21.11-layered.2

This release is about one thing: the builder stopped being reliable on long, high-stress builds. It used to pause for no reason, misjudge which materials it had, fight itself over blocks it had just placed, and leave holes behind. Everything below addresses a concrete log or crash report from a real build session.

### Added — debug log (`#debug`)

`#debug true` writes `baritonelog.txt` next to the save (single player) or in the game directory, `#debug false` stops it. It is off by default.

- `src/main/java/baritone/utils/BaritoneLog.java` (new) — the whole logger: `chat/log/section/exception/goal/file/writtenLines/close`, one line per event prefixed `HH:mm:ss.SSS t=<gameTime> [CATEGORY] `, flushed per line, every write wrapped so logging can never break a build.
- Header block records what the session looked like: start time, target file, dimension, player, feet position, and a snapshot of **every** Baritone setting that is not `@JavaOnly`.
- The file follows the world: the first line is written while Baritone is being constructed, before a world exists, so the log used to be pinned to the game directory for the whole session. It now moves to the save directory as soon as one is loaded, and never moves back.
- Capped at 8 MB per file; on reaching the cap it writes one explanatory line and stops instead of filling the disk.
- `src/api/java/baritone/api/Settings.java` — new `debug` setting, registered automatically like every other public `Setting` field, which is what makes `#debug true` work through the existing command parser.
- `src/main/java/baritone/Baritone.java` — wraps `settings().logger` so chat messages also land in the file, and writes the `INIT` line.

Categories written today: `INIT`, `CHAT`, `PLAN`, `PATH`, `CALC`, `RECALC`, `TRIM`, `BREAK`, `PLACE`, `ASSEMBLE`, `MISSING`, `ORIENT`, `PAUSE`, `BUILD`, `STALL`, `LOG`.

### Added — directional states the inventory can never match

A hopper aimed at a chest, a log with `axis=x`, a stair facing east: the item in the inventory is the same either way, but the placement only comes out right for one facing. The builder used to call all of those "missing materials" and pause the whole build.

- **Material check is now about the block, not the state.** `couldPlaceAs(desired, placeable)` accepts any inventory state of the same `Block`; whether the placement can actually *produce* the wanted facing is checked later, at placement time, against the real world.
- **Orientation is recognised by shape, not by a list of `Property` instances.** `isPlacementOrientation(Property)` treats any property whose values are `Direction` or `Direction.Axis` as orientation, plus the known names in `ORIENTATION_PROPERTY_NAMES`. The old approach compared `Property` objects, and `HopperBlock.FACING` is a *different object* from `HorizontalDirectionalBlock.FACING`, so hoppers could never match. This also covers modded blocks, which bring their own property instances.
- `hasPlacementOrientation(BlockState)`, `placementRotations(Rotation)` (aim, +90, +180, +270), `simulatePlacement(...)`, `simulatePlacementState(...)`, `withPlayerRotation(...)`, `placeContext(...)` — the placement is re-simulated from each candidate look direction, so choosing "which side to stand on" became a question the code can answer.
- `placementSideFits(...)`, `computeFittingPlacementSides(...)`, `sideWouldPlace(...)`, cached in `fittingPlacementSides` — `placementGoal(...)` now walks to the side that can actually produce the wanted state instead of the first side that merely can be clicked.
- `Placement.strictAim` — states with an orientation only get clicked once the hit is confirmed on the right face; "the look direction is roughly right" is not good enough for a log's `axis`, because a click on the wrong face produces the same block with the wrong axis, which then has to be mined and redone.

### Added — the builder no longer stalls forever

A build that stops moving used to need a human to walk one block, because nothing in the code noticed. Four independent guards now notice:

| guard | limit | what it catches |
| --- | --- | --- |
| `CALC_FAIL_STREAK_LIMIT` | 60 | the pathfinder failing again and again for the same goal (`calcFailed` was previously ignored outright) |
| `SATISFIED_STALL_LIMIT` | 40 | standing inside the goal, no path, no clicks — the classic "walk one block and it continues" deadlock |
| `IDLE_STALL_LIMIT` | 200 | no path and no clicks at all |
| `STILL_TICK_LIMIT` | 200 | a path that is being followed but does not move the player; the segment is force-cancelled |

All four funnel into `escapeStall(reason)`: the positions the frozen goal was built from are added to `abandonedPlacements`, cached scans are dropped, counters reset, and the tick re-runs with the new plan. `resume` clears them again, so a manual "I moved, try now" works as before.

### Added — layer scheduling could not move on

`BuilderLayerScheduler` gained `deferSlice()`, `clearDeferred()` and `deferredCount()`. When a slice has nothing it can do from where the player stands, the builder marks it as deferred and continues with the next one instead of pausing; the deferred set is walked again on the next pass and cleared by `reset()`/`resume()`.

### Added — logging helpers and small APIs

- `BaritoneLog.goal(Object)` renders goals compactly. A `JankyComposite` used to be printed with all thirty `GoalAdjacent` children on every line, which is how a log reached 13 MB in eleven minutes.
- `Settings.debug` (see above).
- `PlacedBlocks.remember/isOurs/forget/clear` (new, `src/main/java/baritone/utils/PlacedBlocks.java`) — a 90-second registry of blocks the pathfinder placed for itself.

### Fixed

- **"Missing materials" for blocks that were in the inventory.** Material checks compared whole `BlockState`s against states guessed from a single `Direction.UP` hit, so any block with an axis (`oak_wood[axis=x]`, `[axis=z]`) could never match, and the build paused over blocks it was holding.
- **`allowInventory true` still reported missing materials.** Only the hotbar was consulted; the main inventory (slots 9–35) was ignored even though the setting exists to use it.
- **`resume` immediately paused again.** It kept the trimmed `incorrectPositions` set (everything beyond ~14 blocks removed), so the next tick believed the layer was finished. It now starts clean and clears deferred slices.
- **The build paused over a single unreachable block.** Such positions are counted in `placementStruggles`; after `PLACEMENT_STRUGGLE_LIMIT` failures they are added to `abandonedPlacements` with one explanatory line, so the rest of the build proceeds and a `resume` retries them.
- **Mining its own scaffolding.** `MovementHelper.attemptToPlaceABlock` places a throwaway block to walk on; `assemble` then saw that cell as "should be air" and mined it, after which the pathfinder placed it again — forever. Scaffolding placed by the pathfinder is now registered in `PlacedBlocks`, and it is only left alone **where the target wants air**; where the target wants a real block the scaffold is an obstruction like any other and gets broken.
- **Breaking blocks that were nowhere near the route.** Breaking ran before placing and shared the placement radius (5 blocks), with no test for whether the block was actually in the way. The builder would stop mid-walk, chew through a few unrelated blocks, and leave the holes open. Breaking now has its own `BREAK_SCAN_RADIUS = 2`, runs **after** placing, and is skipped entirely while a path is being followed — obstacles on the route are the pathfinder's business, priced by `breakCostMultiplierAt`.
- **Placing a block, mining it, placing it again.** Wrong-axis states came from clicking "approximately" and landing on the wrong face; those cells then had to be mined and redone, which is where the endless churn came from. Fixed by `Placement.strictAim` (see above).
- **Clicking a spot forever without anything appearing.** `searchForPlacables` counted a *found* placement as a success and cleared every counter, so a position the client quietly refuses (the player's own bounding box is inside the cell, the support got dug out, the ray lands a hair off) was retried every tick with nothing recording it. Positions intersecting the player's bounding box are now skipped (`playerIsStandingIn`), and `[PLACE]` lines are written once per position instead of once per tick.
- **Crash on the way into a world:** `NullPointerException` in `IPlayerContext.playerFeet` from `PathingBehavior.dispatchEvents` → `pathStart`. Java evaluates arguments before the call, so `pathStart()` ran even with the log disabled, and it reads the player position while no player exists. `dispatchEvents` now returns early when logging is off, `safePathStart()` renders `<no player>`, and `onTick` bails out while `ctx.player() == null`.
- **Ticks lost to repeated trimming.** `trim()` (distance-based) ran every tick and shrank the candidate set to the player's surroundings; combined with `incorrectSize = 100` short-circuiting `fullRecalc`, "nothing left in this layer" was only ever true near the player. `assembleWholeBand` rescans the whole band, on a `WHOLE_BAND_RESCAN_TICKS = 10` cooldown so the fallback cannot stutter the tick loop.

### Changed — performance and scheduling

- `assemble(...)` builds a `HashSet` of placeable positions instead of calling `List.contains` per candidate (was O(n²) on every tick).
- `approxPlaceable` is cached by an item-list snapshot (`refreshApproxPlaceable`) instead of being rebuilt from 36 slot stacks every tick.
- `BuilderCalculationContext.placeable` follows `allowInventory` instead of always using the hotbar, and `costOfPlacingAt` uses `couldPlaceAs`; before, every block that was not in the hotbar gave its position `COST_INF`, which made A* discard and recompute whole path segments.
- `toBreakNearPlayer` / `searchForPlacables` scan rings (closest first) with `VERTICAL_ORDER_PLACE` / `VERTICAL_ORDER_BREAK*` rather than a fixed `dx → dy → dz` sweep that started in the far corner of the box, so the block next to the player wins over one on the other side of the scan volume.
- `hasAnyItemThatWouldPlace` filters hotbar slots by block identity before simulating a placement.
- `onTick` order is now: place → break → inventory shuffle → local assemble (pocket) → local assemble (full) → missing-materials assemble → whole-band assemble → layer advance → defer slice → pause. Logging is folded into the same pass instead of a second traversal.

### Changed — logging volume

The first version of the log wrote everything and produced 13.6 MB / 44,018 lines in eleven minutes: `[TICK]` every 10 ticks, `[BEAT]` every 100, `[RECALC]`/`[ASSEMBLE]`/`[PLACE]` every tick, and every goal expanded in full.

- `[PLAN]` is written only when the plan changes (layer, pending work, abandoned count) plus a heartbeat every 200 ticks — the signature deliberately excludes values that change every tick.
- `[PATH]` lines are deduplicated and goals are rendered by `BaritoneLog.goal`.
- `describeGoal` → `BaritoneLog.goal` everywhere, including `findPathInNewThread`, which printed the raw composite goal.
- `[BREAK]` is written once per target, `[PLACE]` once per position, `[ASSEMBLE]` only when the counts change.
- The `#debug` history in this fork is exactly: full detail (too much) → trimmed (too little, and capped at 2 MB which silenced files mid-session) → the current set: configuration, decisions, and exceptions.

### Reverted

- Placements always sneak. Removing the crouch was tried in `v1.21.11-layered.2` development; without it the click lands differently often enough (the support block eats it, the player edge-slides as the click goes out) that blocks came out in the wrong state and had to be mined and redone, which cost more time than the tick the crouch takes. The unconditional sneak is back.

---

## v1.21.11-layered.1

Layered pathfinding baseline: `#litematica` and `#selection fill` work through the target one horizontal Y layer at a time (`buildLayerSlicing`, `buildLayerSliceHeight`, `buildSliceSweepRadius`, `buildLayerAutoOrder`, `skipFailedLayers`), instead of planning across the whole volume at once.

---

> 中文版：[CHANGELOG.md](CHANGELOG.md)
