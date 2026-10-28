# Baritone · Layered Build （分层建造 / 分层填充修改版）

> ## ⚠️ 这是修改版，不是官方版本
>
> **原项目： [cabaletta/baritone](https://github.com/cabaletta/baritone)** （作者 Brady / cabaletta，LGPL-3.0）
>
> 本仓库是 **Baritone 1.21.11 的非官方修改版**，只做了一件事：**把 `#litematica` / `#selection fill` 的"整片乱跑"改成"按 Y 层切片、逐层清扫"**。
> 除分层调度相关代码外，其余逻辑与原版保持一致。遇到任何问题请先确认是否来自本修改，再决定反馈给上游还是这里。
>
> 上游更新后本仓库不保证跟随；如果你只是想要标准 Baritone，请直接使用原项目。

---

## 一、这个修改解决了什么问题

### 原版行为

Baritone 的建造/填充逻辑集中在 `BuilderProcess`。无论目标是 litematica 投影还是 `#selection fill minecraft:air`，最终都是：

1. `fullRecalc()` 扫描**整个 schematic 的所有 Y 层**，把所有错误方块塞进 `incorrectPositions`；
2. `assemble()` 把这个集合**整体**变成寻路目标（`GoalComposite` / `JankyGoalComposite`）；
3. 寻路器在"全图所有待处理方块"里挑最近的一个。

后果：目标集横跨整个三维体积，玩家会在**不同 Y 层、不同区块之间来回横跳**——先在 y=5 挖一格，再跑到 y=60 放一格，再回到 30 格外的另一个角落。表现为：

- 频繁上下跳、爬梯、落地；
- 长距离寻路反复触发，每次目标切换都要重算路径；
- 填充 air 时"边挖边塌"，容易把自己埋进竖井；
- 建造投影时出现悬空放置，反复失败重试。

### 修改后的行为

**把目标按 Y 轴切成水平层（默认每层 1 格高），一层彻底做完才进入下一层；在一层内只清扫玩家附近的"口袋"。**

```
原版：  全图目标集 ──────────► 到处乱跳
本版：  ┌── 第 n 层 ──┐
        │  口袋里清扫  │  做完 ──► n±1 层
        └─────────────┘
```

**1. Y 轴切片（游标）**

新增 `baritone.process.BuilderLayerScheduler`，负责"当前第几层、这层覆盖哪些相对 Y、下一层在哪"。

- 层厚由 `buildLayerSliceHeight` 决定（默认 1 层 = 1 格，可设更厚）；
- 每 tick 用 `LayerSliceSchematic`（`BuilderProcess` 的内部类）把 schematic **裁剪成只有当前层**：`inSchematic` 只对当前层的相对 Y 返回 true；
- 当前层再也找不到错误方块时，`advance()` 前进，并清空 `incorrectPositions` 强制重扫下一层；
- 层数用尽 → 走原版的重复建造 / 结束流程。

**2. 扫描范围跟着层走**

`fullRecalc()` 的 Y 循环从 `0..heightY-1` 改成当前层的 `sliceMinY..sliceMaxY`。
这是关键一环：`distanceTrim` 每 tick 会裁掉远处方块、裁空后触发全量重扫——原版重扫会把**全图全 Y** 的远端方块重新灌进目标集，于是又开始跨区跑；现在最多只灌当前这一层。

**3. 层内"口袋"清扫（避免长距离寻路）**

`selectSweepPocket()`：

- 先按**水平**距离找离玩家最近的待处理方块作为锚点（同距离时按层的推进方向比 Y，再比 z、x，保证确定性）；
- 只取锚点水平半径 `buildSliceSweepRadius`（默认 8 格）内的同层方块，交给 `assemble()`（`assemble` 为此新增了 `candidates` 重载）；
- 口袋做不出目标 → 退回整层；整层也不行 → 用完整背包重算 / 跳层 / 暂停，流程与上游一致。

> **为什么只用水平距离？**
> `assemble()` 判断"这块能不能放"要看 `pos.below()` 是否也在待放置集合里（否则会悬空放置）。`pos.below()` 的 x/z 与自身相同，按水平距离收口袋时下方那格一定同进同出，判定保持一致。

**4. 方向自适应（挖 vs 建）**

`isRemovalHeavy()` 对目标最多采样 8×8×8 个点（跳过未加载区块），比较"要挖掉的"和"要放下的"数量：

| 目标类型 | 推进方向 | 原因 |
|---|---|---|
| 挖得多（`#selection fill air`、`cleararea`） | **自上而下** | 不会边挖边把自己埋进刚挖出的竖井，也不用爬出来 |
| 放得多（litematica 建筑） | **自下而上** | 放下的方块永远有下方支撑，减少悬空放置判定失败 |

`buildLayerAutoOrder=false` 时改由原版 `layerOrder`（false = 从下往上）决定；`layerOrder=true` 则强制自上而下。

---

## 二、新增 / 修改的文件

| 文件 | 说明 |
|---|---|
| `src/main/java/baritone/process/BuilderLayerScheduler.java` | **新增**。分层游标：`sliceIndex()`、`sliceMinY()`、`sliceMaxY()`、`containsLayer()`、`advance()`、`reset()` |
| `src/main/java/baritone/process/BuilderProcess.java` | 接入游标；新增 `LayerSliceSchematic`、`createLayerScheduler()`、`isRemovalHeavy()`、`selectSweepPocket()`、`slicingActive()`；`fullRecalc()` 按层扫描；`assemble()` 增加 `candidates` 重载；层完成 / 跳层 / 重复建造 / 失联清理时同步游标；`getMinLayer()` / `getMaxLayer()` 汇报当前层 |
| `src/api/java/baritone/api/Settings.java` | 新增 4 个配置项（见下） |

**上游路径完整保留**：关掉 `buildLayerSlicing` 后行为与上游逐行一致；上游的 `buildInLayers` 老分层分支也原样保留，作为回退。

## 三、配置项

| 设置 | 默认 | 说明 |
|---|---|---|
| `buildLayerSlicing` | `true` | 总开关。关掉即恢复原版"整片目标集"行为 |
| `buildLayerSliceHeight` | `1` | 每层多高（Y 格数）。想一次处理 3 层厚就设 3 |
| `buildSliceSweepRadius` | `8` | 层内口袋的水平半径。0 = 不做局部限制，整层直接交给寻路器 |
| `buildLayerAutoOrder` | `true` | 按"挖/建"占比自动决定从上往下还是从下往上 |

游戏内调整示例：

```
#set buildSliceSweepRadius 12
#set buildLayerSliceHeight 3
#set buildLayerSlicing false
```

运行时聊天栏会输出：

```
Working through the build one horizontal layer at a time, starting at layer 1/64 (y 0-0)
Layer finished, moving on to layer 2/64 (y 1-1)
```

## 四、构建

要求 **JDK 21**（Gradle 8.14.4 无法运行在 JDK 25 上）。

```bash
git clone <this repo>
cd baritone-layered

export JAVA_HOME=/path/to/jdk-21        # Windows: $env:JAVA_HOME="C:\path\to\jdk-21"

# 只要一个能用的 mod jar（快，推荐）
./gradlew :fabric:remapJar
# 产物：fabric/build/libs/baritone-layered-fabric-<version>.jar

# 完整构建（含 proguard 分发产物，会构建全部 loader）
./gradlew build
# 产物：dist/*.jar（api / standalone / unoptimized）
```

首次构建需要下载 Minecraft、映射表和全部 loader 依赖，约 15–30 分钟；之后增量构建 1–2 分钟。

### CI

`.github/workflows/build-and-release.yml`：

- 触发：推送 `v*` tag，或手动 `workflow_dispatch`；
- JDK 21（Temurin）+ Gradle 缓存，执行 `./gradlew build`；
- 打 tag 时用 `softprops/action-gh-release` 把 `dist/*.jar` 发布为 Release。

## 五、安装

1. 安装 Fabric Loader（对应 Minecraft 1.21.11）与 Fabric API；
2. 把 `dist/` 里的 **standalone** jar（或 `:fabric:remapJar` 产出的 jar）放进 `.minecraft/mods`；
3. 游戏内 `#selection fill minecraft:air` 或 `#litematica` 即可使用分层策略。

## 六、许可证与致谢

- 本项目继承上游 **LGPL-3.0**，见 [LICENSE](LICENSE)。
- 原项目：**[cabaletta/baritone](https://github.com/cabaletta/baritone)**。所有核心寻路、建造、路径计算逻辑均来自上游；本仓库只修改了建造/填充的目标调度方式。
- 修改部分（`BuilderLayerScheduler` 与 `BuilderProcess` 的分层调度代码）同样以 LGPL-3.0 发布。
- 若你在自己的分支里复用本修改，请保留同样的致谢与许可证声明。
