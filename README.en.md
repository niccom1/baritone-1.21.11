# Baritone · Layered Build (layer-by-layer building / filling fork)

> ## ⚠️ This is a modified build, made with the help of DeepSeek-V4.1-Flash 1017. It is not the official version.
>
> **Upstream: [cabaletta/baritone](https://github.com/cabaletta/baritone)** (by Brady / cabaletta, LGPL-3.0)
>
> This repository is an **unofficial build of Baritone for Minecraft 1.21.11** that does one thing: it turns `#litematica` / `#selection fill` from "run all over the whole volume" into "slice along Y and sweep one horizontal layer at a time".
> Everything outside the layer-scheduling code behaves like upstream. If you hit a problem, first work out whether this fork caused it before deciding where to report it.
>
> This fork does not promise to follow upstream releases. If all you want is standard Baritone, use the original project.

---

## 1. What this fixes

### Upstream behaviour

Baritone's building/filling logic lives in `BuilderProcess`. Whether the target is a litematica schematic or `#selection fill minecraft:air`, the flow is always:

1. `fullRecalc()` scans **every Y layer of the whole schematic** and dumps every wrong block into `incorrectPositions`;
2. `assemble()` turns that set **as a whole** into the pathing goal (`GoalComposite` / `JankyGoalComposite`);
3. the pathfinder picks the nearest block out of "everything that needs work, anywhere in the target".

The consequence: the goal set spans the entire 3D volume, so the player jumps around **between Y levels and between chunks** — mine one block at y=5, run off to place one at y=60, come back to another corner 30 blocks away. In practice that means:

- constant jumping up and down, ladder climbing, falling;
- long-distance pathing recalculated again and again, because every goal switch invalidates the path;
- when filling air, "dig and collapse" — you bury yourself in the shaft you just dug;
- when building a schematic, floating placements that fail and retry endlessly.

### Behaviour after the change

**The target is cut into horizontal layers along Y (1 block high by default), a layer is finished completely before the next one, and inside a layer only the "pocket" around the player is swept.**

```
upstream:  goal set for the whole volume ──────────► bouncing everywhere
this fork: ┌── layer n ──┐
           │ sweep pocket │  done ──► layer n±1
           └──────────────┘
```

**1. Y slicing (a cursor)**

New: `baritone.process.BuilderLayerScheduler`, responsible for "which layer am I on, which relative Y values does it cover, where is the next one".

- Layer thickness comes from `buildLayerSliceHeight` (default 1 layer = 1 block, can be thicker);
- every tick, `LayerSliceSchematic` (an inner class of `BuilderProcess`) **clips the schematic down to the current layer**: `inSchematic` returns true only for the current layer's relative Y;
- when the current layer has no wrong blocks left, `advance()` moves on and clears `incorrectPositions` to force a rescan of the next layer;
- layers exhausted → the upstream repeat-build / finish path runs.

**2. The scan range follows the layer**

The Y loop in `fullRecalc()` goes from `0..heightY-1` to the current layer's `sliceMinY..sliceMaxY`.
This part is essential: `distanceTrim` drops distant blocks every tick and a full rescan is triggered once the set is emptied — the upstream rescan refills the goal set with **every Y of the whole volume**, so the cross-region bouncing starts again. Now at most the current layer gets refilled.

**3. Pocket sweeping inside a layer (avoids long-distance pathing)**

`selectSweepPocket()`:

- picks the pending block with the smallest **horizontal** distance to the player as the anchor (on a tie it compares Y according to the layer's direction of travel, then z, then x, so the result is deterministic);
- takes only same-layer blocks within the anchor's horizontal radius `buildSliceSweepRadius` (default 8 blocks) and hands them to `assemble()` (`assemble` gained a `candidates` overload for this);
- if the pocket yields no goal → fall back to the whole layer; if the layer yields nothing → recalculate with the full inventory / skip the layer / pause, exactly as upstream does.

> **Why horizontal distance only?**
> `assemble()` decides whether a block can be placed by checking whether `pos.below()` is also in the pending set (otherwise it would be a floating placement). `pos.below()` shares its x/z with the block itself, so a horizontal pocket always includes or excludes the cell below together with it, and the decision stays consistent.

**4. Direction adapts (dig vs build)**

`isRemovalHeavy()` samples up to 8×8×8 points of the target (skipping unloaded chunks) and compares "blocks to remove" against "blocks to place":

| Target kind | Direction of travel | Why |
|---|---|---|
| mostly removal (`#selection fill air`, `cleararea`) | **top-down** | you cannot bury yourself in a shaft you just dug, and you never have to climb out |
| mostly placement (litematica build) | **bottom-up** | a placed block always has support below it, which reduces failed floating-placement checks |

With `buildLayerAutoOrder=false` the upstream `layerOrder` decides (false = bottom-up); `layerOrder=true` forces top-down.

---

## 2. New / modified files

| File | What it does |
|---|---|
| `src/main/java/baritone/process/BuilderLayerScheduler.java` | **New.** The layer cursor: `sliceIndex()`, `sliceMinY()`, `sliceMaxY()`, `containsLayer()`, `advance()`, `reset()` |
| `src/main/java/baritone/process/BuilderProcess.java` | Wires the cursor in; adds `LayerSliceSchematic`, `createLayerScheduler()`, `isRemovalHeavy()`, `selectSweepPocket()`, `slicingActive()`; `fullRecalc()` scans per layer; `assemble()` gained the `candidates` overload; the cursor is kept in sync on layer completion / layer skip / repeat build / control loss; `getMinLayer()` / `getMaxLayer()` report the current layer |
| `src/api/java/baritone/api/Settings.java` | Adds the 4 settings below |

**The upstream path is fully preserved**: with `buildLayerSlicing` off, behaviour matches upstream line for line; upstream's older `buildInLayers` branch is kept untouched as a fallback.

## 3. Settings

| Setting | Default | Meaning |
|---|---|---|
| `buildLayerSlicing` | `true` | Master switch. Off restores the upstream "whole-volume goal set" behaviour |
| `buildLayerSliceHeight` | `1` | How tall a layer is (Y blocks). Set 3 to work through 3 layers at a time |
| `buildSliceSweepRadius` | `8` | Horizontal radius of the in-layer pocket. 0 = no local limiting, hand the whole layer to the pathfinder |
| `buildLayerAutoOrder` | `true` | Decide top-down vs bottom-up from the removal/placement ratio |

Adjusting in game:

```
#set buildSliceSweepRadius 12
#set buildLayerSliceHeight 3
#set buildLayerSlicing false
```

The chat log reports progress:

```
Working through the build one horizontal layer at a time, starting at layer 1/64 (y 0-0)
Layer finished, moving on to layer 2/64 (y 1-1)
```

## 4. Building

Requires **JDK 21** (Gradle 8.14.4 does not run on JDK 25).

```bash
git clone <this repo>
cd baritone-layered

export JAVA_HOME=/path/to/jdk-21        # Windows: $env:JAVA_HOME="C:\path\to\jdk-21"

# just a usable mod jar (fast, recommended)
./gradlew :fabric:remapJar
# output: fabric/build/libs/baritone-layered-fabric-<version>.jar

# full build (includes the proguard distribution set, builds every loader)
./gradlew build
# output: dist/*.jar (api / standalone / unoptimized)
```

The first build downloads Minecraft, the mappings and every loader dependency (roughly 15–30 minutes); incremental builds after that take 1–2 minutes.

### CI

`.github/workflows/build-and-release.yml`:

- triggers: pushing a `v*` tag, or a manual `workflow_dispatch`;
- JDK 21 (Temurin) with Gradle caching, runs `./gradlew build`;
- on a tag it publishes `dist/*.jar` as a Release with `softprops/action-gh-release`.

## 5. Installing

1. Install Fabric Loader (for Minecraft 1.21.11) and Fabric API;
2. put the **standalone** jar from `dist/` (or the jar produced by `:fabric:remapJar`) into `.minecraft/mods`;
3. in game, `#selection fill minecraft:air` or `#litematica` now uses the layered strategy.

## 6. Licence and credits

- This project inherits upstream **LGPL-3.0**, see [LICENSE](LICENSE).
- Upstream: **[cabaletta/baritone](https://github.com/cabaletta/baritone)**. All core pathing, building and path-calculation logic comes from upstream; only the scheduling of build/fill targets was changed here.
- The modified parts (the layer-scheduling code in `BuilderLayerScheduler` and `BuilderProcess`) are released under LGPL-3.0 as well.
- If you reuse this change in your own branch, keep the same credits and licence notice.

---

> 中文版：[README.md](README.md)
