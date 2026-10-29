# 安装与使用

适用 Minecraft 1.21.11。

## 需要什么

- Java 21 或更高版本（构建必须用 21，运行 21 及以上都行）
- 对应加载器：Fabric Loader、Forge 或 NeoForge
- Fabric 还需要 Fabric API

## 装哪个 jar

Release 里的文件按加载器分好了，一般只需要一个：

- `baritone-layered-standalone-<加载器>-<版本>.jar`，正常玩就用这个
- `baritone-layered-api-<加载器>-<版本>.jar`，只给别的 mod 调用 Baritone API 的场合用
- `baritone-layered-unoptimized-<加载器>-<版本>.jar`，关了优化的构建，排查问题或者需要调试时用

把对应加载器的 jar 丢进 `.minecraft/mods` 就行。不要同时放两个，会打架。

## 自己构建

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :fabric:remapJar     # 只出 Fabric 的 jar
./gradlew build                # 全部加载器，产物在 dist/
```

`dist/` 里是正式分发的三套（api、standalone、unoptimized），每个加载器一份。

## 常用命令

```
#litematica                 按当前投影开始建造
#sel fill minecraft:air     把选区填成空气（清空）
#mine diamond_ore           挖指定方块，支持带数量
#stop                       停下当前任务
#resume                     继续
#cancel                     取消
#set <名称> <值>            改设置，例如 #set buildSliceSweepRadius 12
#debug true                 开始写调试日志
```

`#litematica` 用的是当前选中的投影，需要先装 litematica。

## 调试日志

`#debug true` 之后，日志写到当前存档目录下的 `baritonelog.txt`。如果在世界加载完之前就已经在写，会先落在游戏目录，世界加载完后自动切到存档目录。

几点需要注意：

- 单次会话上限 8 MB，写满就停。删掉文件，或者把 `#debug` 关掉再开，会重新建文件继续写。
- 文件开头是一份完整设置的快照，出问题先看这里，很多现象是设置造成的。
- 主要看这几个前缀：`BUILD`（层进度、完成、搁置、暂停原因）、`PLACE` 和 `AIM`（放置与瞄准）、`STATE`（放置结果与状态的判定）、`MINE`（挖矿）、`PERF`（扫描耗时）、`CALC` 和 `PATH`（算路）。
- 排障顺序一般是先 `BUILD` 看它认为自己在做哪一层、为什么停，再看 `PLACE` 或 `MINE` 看那一格具体卡在哪一步。

## 已知限制

- 分层是按 Y 走的。建筑特别高的时候层数就多，总耗时主要由方块数量决定，分层不会让它变快，只是让它不来回乱跑。
- 放置走的是原版点击，速度受 `rightClickSpeed` 控制，默认 3 tick 一下。调小会更快，但太快在服务器上容易被判定为异常操作。
- 有些格子在当前站位就是放不出目标朝向（比如周围全空、没有可以点的面），这种格子会被记下来跳过；换个位置 `#resume` 会再试一次。
- 反作弊严格的服务器上，视角是真实转动的，点击也是原版点击，但整体移动和建造速度仍然可能触发服务器自己的判定，这部分不在本修改能控制的范围内。

---

> English version: [GUIDE.en.md](GUIDE.en.md)
