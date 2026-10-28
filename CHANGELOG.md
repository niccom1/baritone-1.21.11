# 更新日志

本仓库是 Minecraft 1.21.11 的 Baritone 分层建造修改版，保留上游 `#litematica` / `#selection fill` 的分层调度，下面记录在其之上做的改动，最新的在最前。

---

## v1.21.11-layered.2

这一版主要在修建造器长时间运行时的可靠性：无故暂停、误判材料、拆掉自己刚放下的方块、留下空洞。每条都能对上一次实际建造的日志或崩溃报告。

### 新增：调试日志（`#debug`）

`#debug true` 在存档旁（单人）或游戏目录写 `baritonelog.txt`，`#debug false` 停止，默认关闭。

- 新文件 `src/main/java/baritone/utils/BaritoneLog.java`，提供 `chat`、`log`、`section`、`exception`、`goal`、`file`、`writtenLines`、`close`。每行一个事件，前缀 `HH:mm:ss.SSS t=<gameTime> [CATEGORY] `，逐行 flush，所有写入包在 try/catch 里，日志出问题不会影响建造。
- 文件头记录启动时间、目标文件、维度、玩家、脚下坐标，以及所有非 `@JavaOnly` 的 Baritone 设置。
- 首行在 Baritone 构造时写入，那时还没有世界，所以以前整个会话都落在游戏目录。现在世界加载后会迁到存档目录，之后不再变动。
- 单个文件上限 8 MB，写满后追加一行说明并停止。
- `Settings.debug` 是普通 `Setting` 字段，`#debug true` 由现有命令解析器处理。
- `Baritone.java` 包装 `settings().logger`，聊天栏内容同时写入文件，并输出 `INIT` 行。

类别：`INIT`、`CHAT`、`PLAN`、`PATH`、`CALC`、`RECALC`、`TRIM`、`BREAK`、`PLACE`、`ASSEMBLE`、`MISSING`、`ORIENT`、`PAUSE`、`BUILD`、`STALL`、`LOG`。

### 新增：朝向状态的处理

漏斗要朝箱子、原木要指定轴、楼梯要指定朝向。背包里的物品本身没有这些区别，但放出来的状态有。以前这类方块统一按"缺少材料"处理，然后暂停。

- `couldPlaceAs(desired, placeable)` 只比方块类型，同 `Block` 就算有；能不能放成目标朝向，留到放置阶段用真实世界判断。
- 朝向属性改为按值类型识别。`isPlacementOrientation(Property)` 认值域为 `Direction` 或 `Direction.Axis` 的属性，再补上 `ORIENTATION_PROPERTY_NAMES` 里的名字。旧实现比较的是 `Property` 对象，而 `HopperBlock.FACING` 和 `HorizontalDirectionalBlock.FACING` 是两个不同的对象，漏斗因此永远匹配不上。mod 方块自带属性实例，也随之可用。
- 新增 `hasPlacementOrientation`、`placementRotations(Rotation)`（原视线角及 +90、+180、+270）、`simulatePlacement`、`simulatePlacementState`、`withPlayerRotation`、`placeContext`，对每个候选视线方向重算一次放置状态。
- 新增 `placementSideFits`、`computeFittingPlacementSides`、`sideWouldPlace`，结果缓存在 `fittingPlacementSides`。`placementGoal` 优先选能放成目标朝向的那一面，取不到才退回第一个可点击的面。
- `Placement.strictAim`：带朝向的状态必须点中正确的面才右键。大致瞄对时容易点到相邻的面，放出来的轴或朝向是错的，之后还得挖掉重做。

### 新增：卡住时的处理

建造停住以前只能靠人走一格恢复，代码里没有任何检测。现在有四道判断：

| 判断 | 阈值 | 触发条件 |
| --- | --- | --- |
| `CALC_FAIL_STREAK_LIMIT` | 60 | 寻路器对同一目标连续失败（`calcFailed` 以前完全没被使用） |
| `SATISFIED_STALL_LIMIT` | 40 | 站在目标范围内，没有路径，也没有点击 |
| `IDLE_STALL_LIMIT` | 200 | 既没有路径也没有点击 |
| `STILL_TICK_LIMIT` | 200 | 有路径在跟，但玩家位置不动，取消这一段 |

四条都调到 `escapeStall(reason)`：把当前目标涉及的坐标加入 `abandonedPlacements`，清掉扫描缓存和计数器，重新跑本 tick。`resume` 会清空这些记录，所以挪个位置再试仍然有效。

### 新增：层调度可以跳过

`BuilderLayerScheduler` 增加 `deferSlice()`、`clearDeferred()`、`deferredCount()`。从玩家当前位置做不了的那一层会被标记挂起，直接试下一层，不再暂停整个建造。挂起的层下一轮会重试，`reset()` 和 `resume()` 会清空。

### 新增：辅助方法

- `BaritoneLog.goal(Object)` 用紧凑格式渲染目标。一个 `JankyComposite` 以前会把三十个 `GoalAdjacent` 子目标全部打印出来，单行约 4 KB。
- `PlacedBlocks.remember` / `isOurs` / `forget` / `clear`（新文件），记录寻路器自己放下的方块，保留 90 秒。

### 修复

- 背包里有材料却报缺少材料。判料拿整个 `BlockState` 去比"按单个 `Direction.UP` 猜出来的状态"，带轴的方块（`oak_wood[axis=x]`、`[axis=z]`）永远匹配不上，建造就为自己背包里有的方块停住。
- `allowInventory true` 依然报缺少材料。只查快捷栏，主背包 9–35 格被忽略，而这个设置存在的意义就是用它。
- `resume` 之后立刻又暂停。它保留了被裁剪的 `incorrectPositions`（约 14 格外的坐标全被删掉），下一 tick 就认为这层完工。现在从空集合开始，并清空挂起的层。
- 为一个够不到的方块停掉整个建造。这类坐标记进 `placementStruggles`，失败满 `PLACEMENT_STRUGGLE_LIMIT` 次后加入 `abandonedPlacements`，并输出一行说明，建造继续，`resume` 时再试。
- 挖掉自己搭的脚手架。`MovementHelper.attemptToPlaceABlock` 会垫一个临时方块好走过去，`assemble` 随后把那一格当作"应该是空气"挖掉，寻路器再垫一次，如此循环。现在寻路器放的脚手架记录在 `PlacedBlocks` 里，只在目标要求空气的位置跳过它；目标要求真方块的位置，它和别的障碍一样会被挖掉。
- 挖掉不在路线上的方块。破坏以前排在放置之前、和放置共用 5 格半径，也不判断方块是否挡路，于是建造器走到一半停下，啃掉几个无关方块，把洞留在那里。现在破坏有独立的 `BREAK_SCAN_RADIUS = 2`，排在放置之后，跟随路径时整段跳过。路线上的障碍交给寻路器，由 `breakCostMultiplierAt` 计价。
- 放了挖、挖了又放。错误的朝向来自"大致瞄对就点"、结果点到别的面，那些格子随后又得挖掉重做。由 `Placement.strictAim` 修掉。
- 对着一个位置反复点击，方块不出现。`searchForPlacables` 把"找到放置方案"当作成功并清空计数器，于是客户端悄悄拒绝的位置（玩家碰撞箱就在格子里、支撑被挖掉、射线差一点）每 tick 都在重试，且没有任何记录。现在与玩家碰撞箱相交的格子直接跳过（`playerIsStandingIn`），`[PLACE]` 每个位置只写一行。
- 进入世界时崩溃。`PathingBehavior.dispatchEvents` 调 `pathStart` 时抛 `IPlayerContext.playerFeet` 的 `NullPointerException`。Java 先求值参数再调用，所以即使日志关着 `pathStart()` 也照跑，而它在没有玩家时读取玩家坐标。现在 `dispatchEvents` 在日志关闭时提前返回，`safePathStart()` 渲染成 `<no player>`，`onTick` 在 `ctx.player() == null` 时直接退出。
- 反复裁剪吃掉 tick。`trim()` 每 tick 把候选集缩到玩家周围，加上 `incorrectSize = 100` 让 `fullRecalc` 提前返回，"这层没活了"只在玩家附近成立。`assembleWholeBand` 会重扫整个层带，用 `WHOLE_BAND_RESCAN_TICKS = 10` 的冷却避免兜底逻辑拖慢 tick。

### 变更：性能与调度

- `assemble(...)` 先建可放置位置的 `HashSet`，不再对每个候选调 `List.contains`，原来是每 tick O(n²)。
- `approxPlaceable` 按物品列表快照缓存（`refreshApproxPlaceable`），不再每 tick 用 36 个槽位重建。
- `BuilderCalculationContext.placeable` 跟随 `allowInventory`，`costOfPlacingAt` 改用 `couldPlaceAs`。以前不在快捷栏的方块会让自己的位置变成 `COST_INF`，A* 因此丢弃并重算整段路径。
- `toBreakNearPlayer` 和 `searchForPlacables` 改成由近到远的环形扫描（`VERTICAL_ORDER_PLACE`、`VERTICAL_ORDER_BREAK*`），不再是从扫描盒远角开始的固定 `dx → dy → dz` 遍历。
- `hasAnyItemThatWouldPlace` 先按方块类型过滤快捷栏槽位，再模拟放置。
- `onTick` 顺序为：放置、破坏、搬背包、局部 assemble（口袋）、局部 assemble（整层）、缺料 assemble、整层带 assemble、进层、挂起该层、暂停。日志并进同一趟，不再二次遍历。

### 变更：日志量

第一版日志什么都写，十一分钟产出 13.6 MB、44,018 行：`[TICK]` 每 10 tick，`[BEAT]` 每 100 tick，`[RECALC]`、`[ASSEMBLE]`、`[PLACE]` 每 tick，每个目标完整展开。

- `[PLAN]` 只在计划变化时写（层、待处理数、被放弃数），另外每 200 tick 一次心跳。签名刻意排除每 tick 都会变的值。
- `[PATH]` 去重，目标统一由 `BaritoneLog.goal` 渲染，包括以前会打印原始复合目标的 `findPathInNewThread`。
- `[BREAK]` 每个目标一行，`[PLACE]` 每个位置一行，`[ASSEMBLE]` 只在计数变化时写。
- `#debug` 在本 fork 的经历是：全量细节（太多），精简版（太少，且 2 MB 上限会在会话中途把文件静音），到现在这套：配置、决策、异常。

### 回滚

放置一律蹲下的行为恢复了。中间试过去掉蹲起改用走搭，结果点击落点常常不同（支撑方块把点击吃掉、玩家在点击发出的瞬间贴边滑走），放出来的方块状态是错的，要挖掉重做，比蹲下花的那一 tick 亏得多。

---

## v1.21.11-layered.1

分层寻路的基线版本。`#litematica` 与 `#selection fill` 按水平 Y 层逐层推进（`buildLayerSlicing`、`buildLayerSliceHeight`、`buildSliceSweepRadius`、`buildLayerAutoOrder`、`skipFailedLayers`），不再一次性在整个三维体积上规划。

---

> English version: [CHANGELOG.en.md](CHANGELOG.en.md)
