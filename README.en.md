# Baritone 1.21.11 pathing-optimised build

An unofficial build of [cabaletta/baritone](https://github.com/cabaletta/baritone) for Minecraft 1.21.11, built for Fabric, Forge and NeoForge.

The pathfinder itself is unchanged. What changed is how a build task asks the pathfinder for goals, and how a placement decides whether a cell counts as done.

## What changed

Upstream hands the whole build target to the pathfinder in one go. With any decent sized target the player ends up running between different heights and far corners, paths get recalculated over and over, and a layer is abandoned halfway to patch a single block somewhere else.

This build slices the target along Y (one block tall by default), finishes a band before moving to the next, and only works on the small area around the player inside that band. Removal heavy jobs (filling air, cleararea) run top down; placement heavy jobs (litematica) run bottom up.

On top of that, the things that made long jobs stop, or made the builder fight itself, are fixed:

- Material checks no longer demand that a carried block match the schematic state exactly. Hoppers, logs and stairs get their state when they are placed, so upstream called them missing and paused the build.
- Before clicking, the state that the face under the crosshair would produce is compared with the wanted state. If it does not match, the click waits until the aim is right, so blocks are no longer placed askew and mined out again.
- Placing happens before mining, and nothing is mined while walking. Upstream used to break a few blocks on the way past and come back for them near the end of the layer.
- Scaffolding the pathfinder placed for itself is left alone instead of being torn down, which used to loop forever.
- A few seconds without progress, repeated path failures, or a path that never moves the player now change the goal or drop those cells instead of waiting for a nudge.
- If some cells in a band cannot be built yet (material, reach, no clickable face), that band is set aside and comes back after the rest of the sweep instead of being skipped.
- `#mine` digs through a cover layer over an ore instead of treating the target as absent.
- Three crash sources got guards: the `stopAtHeight` left behind after control is taken away, sets being nulled mid scan, and the process list being modified while iterated.

## New settings

- `buildLayerSlicing`, default `true`. Master switch for the band scheduling; off returns to upstream behaviour.
- `buildLayerSliceHeight`, default `1`. Blocks per band.
- `buildSliceSweepRadius`, default `8`. Horizontal radius of the local pocket; `0` disables the restriction.
- `buildLayerAutoOrder`, default `true`. Picks top down or bottom up from the removal to placement ratio; off uses `layerOrder`.
- `debug`, default `false`. Writes the debug log described in [GUIDE.en.md](GUIDE.en.md).

In game:

```
#set buildSliceSweepRadius 12
#set buildLayerSliceHeight 3
#set debug true
```

## Install and build

Install: Java 21 plus the matching loader, then drop the jar for your loader into `mods`. Fabric also needs Fabric API.

Building needs JDK 21; Gradle 8.14.4 does not run on JDK 25.

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :fabric:remapJar     # a single usable mod jar
./gradlew build                # every loader, output in dist/
```

The first build downloads Minecraft, mappings and all loader dependencies.

Install steps, common commands and the debug log are covered in [GUIDE.en.md](GUIDE.en.md).

## License

LGPL-3.0, inherited from upstream. See [LICENSE](LICENSE). The pathfinder, path calculation and builder flow all come from upstream; this repository changes the scheduling of build targets and the placement decisions. Those changes are released under LGPL-3.0 as well. This build does not promise to follow upstream releases; use upstream if you want standard Baritone.

---

> 中文版：[README.md](README.md)
