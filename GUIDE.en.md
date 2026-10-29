# Install and usage

For Minecraft 1.21.11.

## Requirements

- Java 21 or newer (building needs 21 specifically; running works on 21 and above)
- Matching loader: Fabric Loader, Forge or NeoForge
- Fabric also needs Fabric API

## Which jar

Release assets are split per loader. One file is enough:

- `baritone-layered-standalone-<loader>-<version>.jar`, the normal choice
- `baritone-layered-api-<loader>-<version>.jar`, only when another mod calls the Baritone API
- `baritone-layered-unoptimized-<loader>-<version>.jar`, the build with optimisations off, for debugging

Drop the jar for your loader into `.minecraft/mods`. Never install two of them at once.

## Building it yourself

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :fabric:remapJar     # Fabric jar only
./gradlew build                # every loader, output in dist/
```

`dist/` holds the three distributions (api, standalone, unoptimized) for each loader.

## Common commands

```
#litematica                 build from the selected litematica schematic
#sel fill minecraft:air     fill the selection with air
#mine diamond_ore           mine the named block, a count is optional
#stop                       stop the current task
#resume                     continue
#cancel                     cancel
#set <name> <value>         change a setting, e.g. #set buildSliceSweepRadius 12
#debug true                 start the debug log
```

`#litematica` works on the selected schematic, so litematica has to be installed.

## Debug log

After `#debug true` the log goes to `baritonelog.txt` in the current save directory. If logging starts before a world is loaded it begins in the game directory and moves to the save once the world is up.

A few things worth knowing:

- One session is capped at 8 MB. Delete the file, or turn `#debug` off and back on, to get a fresh one and keep logging.
- The file starts with a full snapshot of the settings. Read that first; plenty of odd behaviour is a setting.
- Useful prefixes: `BUILD` (band progress, completion, being set aside, pause reason), `PLACE` and `AIM` (placement and aiming), `STATE` (placement results against the wanted state), `MINE`, `PERF` (scan cost), `CALC` and `PATH` (path calculation).
- The usual order when something stops: `BUILD` to see which band it thinks it is on and why it stopped, then `PLACE` or `MINE` for the cell it is stuck on.

## Known limits

- Bands run along Y. A tall build has many bands, and total time is driven by block count; banding does not make it faster, it stops the running back and forth.
- Placement uses vanilla clicks, throttled by `rightClickSpeed` (3 ticks by default). Lowering it is faster, but too fast gets flagged on servers.
- Some cells cannot produce the wanted orientation from where the player stands (open air on every side, nothing to click against). Those are recorded and skipped; move somewhere else and `#resume` retries them.
- On anti-cheat heavy servers the look is real and the click is vanilla, but the overall movement and build speed can still trip the server's own checks. That part is outside what this build controls.

---

> 中文版：[GUIDE.md](GUIDE.md)
