# Changelog

## 1.21.11-layered.7

Mining only, limited to what touches the schematic overlay and clearing a selection.

### Schematic overlay

- The mine target set used to be truncated. Each scan was sorted by distance and cut down to the first `mineMaxOreLocationsCount` entries (64 by default), so anything past that never entered `knownOreLocations`: those ores showed no box in the overlay and were never scheduled. Scan results are now kept in full, and only the number of targets handed to the pathfinder is limited, so boxes and real targets line up.
- The break target now survives across ticks. It used to be recomputed every tick, and two equally distant ores made it flip back and forth, which kept the crosshair moving and the aim from ever settling. It is now re-chosen only when the old target stops being valid, with a stable coordinate tie-break.
- When the faces of a target are covered, a stance is found first. The old code simply did nothing while no face was visible from where the player stood, and the task waited until the player moved by hand. It now enumerates cells around the target that are standable and can see one of its faces, confirms with a ray trace, and walks there.
- A cell with no visible face is skipped on a 30 second cooldown instead of being deleted for good.
- Blacklisting an unreachable target now expires after about 60 seconds, instead of giving up on a 3 block cluster forever after a single failure.
- One extra log line reports the target count: `[MINE] tracking N target position(s), the closest K of them are goals right now`.

### Clearing a selection

- `onLostControl` and a fresh mine command now clear everything added here: break target, stance cache, no-sight cooldown, blacklist expiry table, target-count log baseline. Starting again after clearing a selection no longer inherits the previous run's skip list.
- A leftover `stopAtHeight` was not reset when a selection was cleared, which made the next tick build a layer scheduler from an empty target and throw a null pointer. That one was fixed in layered.6 and stays fixed.

> 中文版：[CHANGELOG.md](CHANGELOG.md)
