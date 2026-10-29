# Changelog

For Minecraft 1.21.11. Newest first.

## v1.21.11-layered.4

- The progress hint moved to the action bar, refreshes on band changes, and is cleared when the build finishes, pauses or sets a band aside. It no longer sits there claiming it is starting at layer 1.
- Before clicking, the state the current crosshair would produce is compared with the wanted state; if it differs the click waits for the aim. The place-askew-then-mine loop is essentially gone.
- Band completion is checked against the whole band instead of "the local scan came up empty". Finished bands advance; unfinished ones are recorded and revisited after the rest of the sweep.
- States decided by the world (rails and the like) are recognised and accepted, with a round limit as a hard stop so a cell cannot be mined and replaced forever.
- `#mine` searches again without the exposure filter when that filter returns nothing, so an ore under a cover is dug out instead of being treated as absent.
- A handful of crash guards: null sets before scanning, nulls in the placement progress, empty mine target lists.

## v1.21.11-layered.3

- Fixed the null pointer in `isRemovalHeavy`. `stopAtHeight` survived after control was taken by another process, and the next tick tried to build a layer scheduler for a target that no longer existed.
- The debug log recreates its file when the file has been deleted, and toggling `#debug` off and back on starts a fresh one.
- Fixed a `ConcurrentModificationException` from the process list being modified while iterated.
- Tightened null and bounds handling in `trim`, `approxPlaceable`, `getSchematic` and a few neighbouring spots.

## v1.21.11-layered.2

- Material checks no longer demand an exact state match, so hoppers, logs and stairs stop being reported as missing.
- Long jobs no longer sit still: no progress, repeated path failures and paths that do not move the player all have an exit now.
- Added `#debug`, which writes a debug log into the save directory.
- Placement happens before mining, and nothing is mined while walking.
- Scaffolding the pathfinder placed for itself is left alone.
- Removed four classes with no references.

## v1.21.11-layered.1

- Building and filling advance along Y one band at a time, sweeping only the area around the player inside the band.
- Added the `buildLayerSlicing`, `buildLayerSliceHeight`, `buildSliceSweepRadius` and `buildLayerAutoOrder` settings.
- The band direction is picked from the removal to placement ratio.
- Turning the slicing switch off restores upstream behaviour.

---

> 中文版：[CHANGELOG.md](CHANGELOG.md)
