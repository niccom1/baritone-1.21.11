# 更新日志

本仓库（Baritone 1.21.11 分层建造修改版）的所有重要改动，最新的在最前面。

这个 fork 保留了上游 Baritone 的分层建造逻辑（`#litematica`、`#selection fill`），并在其之上加下面这些修复。

---

## v1.21.11-layered.2

这个版本只干一件事：让建造器在长时间、高压力的工程里重新变得可靠。它以前会莫名其妙地暂停、误判自己有没有材料、跟自己刚放下的方块打架、并且留下空洞。下面每一条都对应一次真实建造会话里的日志或崩溃报告。

### 新增 —— 调试日志（`#debug`）

`#debug true` 会在存档旁边（单人）或游戏目录下写 `baritonelog.txt`，`#debug false` 停止。默认关闭。

- `src/main/java/baritone/utils/BaritoneLog.java`（新文件）—— 整个日志器：`chat/log/section/exception/goal/file/writtenLines/close`，每个事件一行，前缀 `HH:mm:ss.SSS t=<gameTime> [CATEGORY] `，逐行 flush，所有写入都包在 try/catch 里，保证记录日志永远不会弄坏一次建造。
- 文件头记录本次会话的样子：启动时间、目标文件、维度、玩家名、脚下坐标，以及**每一条**不是 `@JavaOnly` 的 Baritone 设置的快照。
- 文件跟着世界走：第一行是在 Baritone 构造期间写的，那时世界还不存在，所以以前日志会整个会话被钉在游戏目录里。现在只要世界加载完成就会迁移到存档目录，并且不再迁回去。
- 单文件上限 8 MB，达到上限时写一行说明就停止，而不是把盘写满。

### 新增 —— 背包里永远匹配不上的朝向状态

对着箱子放的漏斗、`axis=x` 的原木、朝东的楼梯：物品本身一样，但只有特定的朝向才能放成目标状态。以前这些统统被判定成"缺少材料"，然后把整个建造暂停。

- **判料看的是方块，不是状态。** `couldPlaceAs(desired, placeable)` 接受背包里任何同 `Block` 的状态；能不能真放成那个朝向，留到后面拿真实世界去校验。
- **朝向属性按"形状"识别，不再按 `Property` 实例枚举。** `isPlacementOrientation(Property)` 把值域是 `Direction`/`Direction.Axis` 的属性一律视为朝向属性，另外再认 `ORIENTATION_PROPERTY_NAMES` 里的名字。旧代码比的是 `Property` 对象，而 `HopperBlock.FACING` 跟 `HorizontalDirectionalBlock.FACING` 是**两个不同的对象**，所以漏斗永远匹配不上。这一改也顺带覆盖了自带属性实例的 mod 方块。
- 新增：`hasPlacementOrientation`、`placementRotations(Rotation)`（视线角、+90、+180、+270）、`simulatePlacement`、`simulatePlacementState`、`withPlayerRotation`、`placeContext` —— 每个候选视线方向都会重新模拟一次放置，于是"我该站哪一面"变成了代码能自己回答的问题。
- 新增：`placementSideFits`、`computeFittingPlacementSides`、`sideWouldPlace`，用 `fittingPlacementSides` 缓存；`placementGoal` 会走向"能放成目标朝向"的那一面，而不是"随便能点到"的第一面。
- `Placement.strictAim` —— 带朝向的状态只有确认点中正确的面之后才会右键。对原木的 `axis` 来说"大致瞄对"是不够的：点到错的面会放出同一个方块的错误朝向，之后还得挖掉重来。

### 新增 —— 不再无声卡死

一个停住不动的建造，以前需要人肉走一格才能继续，因为代码里没有任何东西会注意到它。现在有四道互相独立的保险丝：

| 保险丝 | 阈值 | 它抓的是什么 |
| --- | --- | --- |
| `CALC_FAIL_STREAK_LIMIT` | 60 | 寻路器对同一个目标连续失败（`calcFailed` 以前被完全忽略） |
| `SATISFIED_STALL_LIMIT` | 40 | 站在目标里、没路径、没点击 —— 经典的"走一格就好了"死锁 |
| `IDLE_STALL_LIMIT` | 200 | 完全没有路径也没有点击 |
| `STILL_TICK_LIMIT` | 200 | 有路径在跟，但玩家不动；直接强制取消这一段路径 |

四条都汇入 `escapeStall(reason)`：把那个冻住的目标所用的坐标塞进 `abandonedPlacements`、丢掉缓存扫描结果、清零计数器，然后带着新计划重跑这一 tick。`resume` 会把这些清空，所以"我挪位置了，再试一次"依然有效。

### 新增 —— 层调度可以往前走了

`BuilderLayerScheduler` 增加了 `deferSlice()`、`clearDeferred()`、`deferredCount()`。从玩家当前位置什么都做不了的那一层会被标记挂起、直接试下一层，而不是暂停；挂起的层会在下一轮重新走一遍，并由 `reset()`/`resume()` 清空。

### 新增 —— 辅助方法

- `BaritoneLog.goal(Object)` 紧凑地渲染目标。一个 `JankyComposite` 以前会把三十个 `GoalAdjacent` 子目标全部打印出来 —— 这就是日志十一分钟能写到 13 MB 的原因。
- `PlacedBlocks.remember/isOurs/forget/clear`（新文件）—— 一份 90 秒的登记表，记录寻路器为自己放下的方块。

### 修复

- **背包里明明有材料却报"缺少材料"。** 判料拿整个 `BlockState` 去比"按单个 `Direction.UP` 猜出来的状态"，所以任何带轴的方块（`oak_wood[axis=x]`、`[axis=z]`）永远匹配不上，建造就为一个自己背包里有的方块停住。
- **`allowInventory true` 依然报缺少材料。** 只查了快捷栏，主背包 9–35 格被忽略，而这个设置存在的意义就是用它。
- **`resume` 之后立刻又暂停。** 它保留了被裁剪过的 `incorrectPositions`（约 14 格外的东西全被删掉），于是下一 tick 就以为这层完工了。现在它从干净状态开始，并清空挂起的层。
- **为一个够不到的方块把整个建造停掉。** 这类坐标会记进 `placementStruggles`，失败达到 `PLACEMENT_STRUGGLE_LIMIT` 次后加入 `abandonedPlacements` 并留一行解释，建造继续，`resume` 时再试。
- **挖自己搭的脚手架。** `MovementHelper.attemptToPlaceABlock` 会垫一个临时方块好走过去；`assemble` 随后把那一格看成"应该是空气"把它挖了，寻路器再垫一次 —— 无限循环。现在寻路器放的脚手架登记在 `PlacedBlocks` 里，并且**只在目标要求空气的地方**才放过它；目标要求真方块的地方，脚手架跟别的障碍一样会被挖掉。
- **挖掉根本不在路线上的方块。** 破坏以前跑在放置之前、和放置共用 5 格半径，而且从不判断方块是否真的挡路：建造器会走到一半停下、啃掉几个无关方块、把洞留在那里。现在破坏有自己的 `BREAK_SCAN_RADIUS = 2`、跑在放置**之后**，并且在跟随路径时整段跳过 —— 路线上的障碍是寻路器的事，由 `breakCostMultiplierAt` 计价。
- **放了挖、挖了又放。** 错误朝向来自"大致瞄对就点"、结果点到别的面，那些格子随后又得挖掉重做，无限折腾就是从这里来的。由 `Placement.strictAim` 修掉（见上）。
- **对着一个位置点个没完，方块就是不出现。** `searchForPlacables` 把"找到放置方案"当成成功并清掉所有计数器，于是客户端悄悄拒绝的位置（玩家自己的碰撞箱就在格子里、支撑被挖掉、射线差一点点）每 tick 都在重试，而且没有任何记录。现在与玩家碰撞箱相交的格子直接跳过（`playerIsStandingIn`），`[PLACE]` 每个位置只写一行，而不是每 tick 一行。
- **进世界时崩溃：** `PathingBehavior.dispatchEvents` → `pathStart` 抛出 `IPlayerContext.playerFeet` 里的 `NullPointerException`。Java 在调用前先求值参数，所以即使日志关着 `pathStart()` 也照样跑了，而它在没有玩家时去读玩家坐标。现在 `dispatchEvents` 在日志关闭时提前返回，`safePathStart()` 会渲染成 `<no player>`，`onTick` 在 `ctx.player() == null` 时直接退出。
- **被反复裁剪吃掉的 tick。** `trim()`（按距离裁剪）每 tick 都跑，把候选集缩小到玩家周围；再加上 `incorrectSize = 100` 会让 `fullRecalc` 提前返回，"这层没活了"只在玩家附近成立。`assembleWholeBand` 会重扫整个层带，并用 `WHOLE_BAND_RESCAN_TICKS = 10` 的冷却避免兜底逻辑把 tick 循环抖爆。

### 变更 —— 性能与调度

- `assemble(...)` 先建一个可放置位置的 `HashSet`，不再对每个候选调 `List.contains`（以前每 tick 都是 O(n²)）。
- `approxPlaceable` 按物品列表快照缓存（`refreshApproxPlaceable`），不再每 tick 拿 36 个槽位重建。
- `BuilderCalculationContext.placeable` 跟随 `allowInventory`，不再固定只用快捷栏；`costOfPlacingAt` 改用 `couldPlaceAs`。以前任何不在快捷栏的方块都会让自己的位置变成 `COST_INF`，逼着 A* 丢弃并重算整段路径。
- `toBreakNearPlayer` / `searchForPlacables` 改成由近到远的环形扫描（`VERTICAL_ORDER_PLACE` / `VERTICAL_ORDER_BREAK*`），而不是从扫描盒子的远角开始的固定 `dx → dy → dz` 遍历，于是玩家身边那块会优先于对面那块。
- `hasAnyItemThatWouldPlace` 先按方块身份过滤快捷栏槽位，再去模拟放置。
- `onTick` 现在的顺序是：放置 → 破坏 → 搬背包 → 局部 assemble（口袋）→ 局部 assemble（整层）→ 缺料 assemble → 整层带 assemble → 进层 → 挂起该层 → 暂停。日志并进同一趟，不再二次遍历。

### 变更 —— 日志量

第一版日志什么都写，十一分钟产出 13.6 MB / 44,018 行：`[TICK]` 每 10 tick、`[BEAT]` 每 100 tick、`[RECALC]`/`[ASSEMBLE]`/`[PLACE]` 每 tick，而且每个目标都完整展开。

- `[PLAN]` 只在计划变化时写（层、待处理数、被放弃数），外加每 200 tick 一次心跳 —— 签名刻意排除了每 tick 都会变的值。
- `[PATH]` 去重，目标用 `BaritoneLog.goal` 渲染。
- `describeGoal` 全部换成 `BaritoneLog.goal`，包括以前会打印原始复合目标的 `findPathInNewThread`。
- `[BREAK]` 每个目标一行、`[PLACE]` 每个位置一行、`[ASSEMBLE]` 只在计数变化时写。
- 这个 fork 里 `#debug` 的历史正好是：全量细节（太多）→ 精简（太少，而且 2 MB 上限会在会话中途把文件静音）→ 现在这一套：配置、决策、异常。

### 回滚

- 放置一律蹲下。曾经试过去掉蹲起，结果点击落点常常不一样（支撑方块把点击吃掉、玩家在点击发出的瞬间贴着边缘滑走），放出来的方块状态是错的、要挖掉重做，比蹲下花的那一个 tick 亏得多。所以无条件蹲下又回来了。

---

## v1.21.11-layered.1

分层寻路基线：`#litematica` 与 `#selection fill` 按水平 Y 层逐层推进（`buildLayerSlicing`、`buildLayerSliceHeight`、`buildSliceSweepRadius`、`buildLayerAutoOrder`、`skipFailedLayers`），而不是一次性在整个三维体积上规划。

---

> English version: [CHANGELOG.en.md](CHANGELOG.en.md)
