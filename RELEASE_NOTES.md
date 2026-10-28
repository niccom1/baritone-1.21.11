# 分层版 v1.21.11-layered.2

Minecraft 1.21.11 的 Baritone 分层建造修改版 —— `#litematica` 与 `#selection fill` 按水平 Y 层逐层推进。

这个版本只干一件事：让建造器在长时间、高压力的工程里重新变得可靠。它以前会莫名其妙地暂停、误判自己有没有材料、跟自己刚放下的方块打架、并且留下空洞。下面每一条都对应一次真实建造会话里的日志或崩溃报告。

**把 `standalone` jar（或 Fabric 重映射 jar）放进 `.minecraft/mods` 即可。** 许可证沿用上游 LGPL-3.0。

---

## 新增 —— 调试日志（`#debug`）

`#debug true` 会在存档旁边（单人）或游戏目录下写 `baritonelog.txt`，`#debug false` 停止，默认关闭。

- `src/main/java/baritone/utils/BaritoneLog.java`（新文件）—— 整个日志器：`chat/log/section/exception/goal/file/writtenLines/close`，每事件一行，前缀 `HH:mm:ss.SSS t=<gameTime> [CATEGORY] `，逐行 flush，所有写入都包在 try/catch 里，保证记录日志永远不会弄坏一次建造。
- 文件头记录会话快照：启动时间、目标文件、维度、玩家、脚下坐标，以及**每一条**不是 `@JavaOnly` 的 Baritone 设置。
- 文件跟着世界走。第一行是在 Baritone 构造期写的（那时世界还不存在），所以以前日志整个会话都被钉在游戏目录；现在世界一加载就迁移到存档目录，且不再迁回。
- 单文件 8 MB 上限，达到上限写一行说明后停止，而不是把盘写满。
- `Settings.debug` 是一个普通 `Setting` 字段，所以 `#debug true` 直接通过现有命令解析器生效。
- `Baritone.java` 包装 `settings().logger`，聊天栏消息同时落盘，并写 `INIT` 行。

类别：`INIT`、`CHAT`、`PLAN`、`PATH`、`CALC`、`RECALC`、`TRIM`、`BREAK`、`PLACE`、`ASSEMBLE`、`MISSING`、`ORIENT`、`PAUSE`、`BUILD`、`STALL`、`LOG`。

## 新增 —— 背包里永远匹配不上的朝向状态

对着箱子放的漏斗、`axis=x` 的原木、朝东的楼梯：物品一样，但只有特定朝向才放得对。以前这些统统被判成"缺少材料"，然后整个建造暂停。

- **判料看方块，不看状态。** `couldPlaceAs(desired, placeable)` 接受背包里任何同 `Block` 的状态，能不能真放成那个朝向留到后面拿真实世界校验。
- **朝向属性按形状识别，不按 `Property` 实例枚举。** `isPlacementOrientation(Property)` 把值域为 `Direction`/`Direction.Axis` 的属性一律当朝向属性，再认 `ORIENTATION_PROPERTY_NAMES` 里的名字。旧代码比的是 `Property` 对象，而 `HopperBlock.FACING` 与 `HorizontalDirectionalBlock.FACING` 是**两个不同对象**，所以漏斗永远匹配不上；这一改也顺带覆盖自带属性实例的 mod 方块。
- 新增 `hasPlacementOrientation`、`placementRotations(Rotation)`（视线角、+90、+180、+270）、`simulatePlacement`、`simulatePlacementState`、`withPlayerRotation`、`placeContext` —— 每个候选视线方向都重算一次放置，于是"我该站哪一面"变成代码能自己回答的问题。
- 新增 `placementSideFits`、`computeFittingPlacementSides`、`sideWouldPlace`，用 `fittingPlacementSides` 缓存；`placementGoal` 走向"能放成目标朝向"的那一面，而不是"随便能点到"的第一面。
- `Placement.strictAim` —— 带朝向的状态只在确认点中正确的面之后才右键。对原木的 `axis` 来说"大致瞄对"不够：点到错的面会放出同方块错朝向，之后还得挖掉重来。

## 新增 —— 不再无声卡死

一个停住的建造，以前需要人肉走一格才能继续，因为代码里没有任何东西会注意到它。现在四道互相独立的保险丝：

| 保险丝 | 阈值 | 抓什么 |
| --- | --- | --- |
| `CALC_FAIL_STREAK_LIMIT` | 60 | 寻路器对同一目标连续失败（`calcFailed` 以前被彻底忽略） |
| `SATISFIED_STALL_LIMIT` | 40 | 站在目标里、没路径、没点击 —— 经典"走一格就好"死锁 |
| `IDLE_STALL_LIMIT` | 200 | 完全没有路径也没有点击 |
| `STILL_TICK_LIMIT` | 200 | 有路径在跟但玩家不动；强制取消该段 |

四条都汇入 `escapeStall(reason)`：把冻住目标用到的坐标塞进 `abandonedPlacements`、丢掉缓存扫描、清零计数器，然后带着新计划重跑本 tick。`resume` 会清空它们，所以"我挪位置了，再试"依然有效。

## 新增 —— 层调度可以往前走

`BuilderLayerScheduler` 增加 `deferSlice()`、`clearDeferred()`、`deferredCount()`。从玩家当前位置什么都做不了的那一层被标记挂起、直接试下一层，而不是暂停；挂起的层下一轮重新走，并由 `reset()`/`resume()` 清空。

## 新增 —— 辅助方法

- `BaritoneLog.goal(Object)` 紧凑渲染目标。一个 `JankyComposite` 以前会把三十个 `GoalAdjacent` 子目标全打印出来 —— 这就是日志十一分钟写到 13 MB 的原因。
- `PlacedBlocks.remember/isOurs/forget/clear`（新文件）—— 一份 90 秒的登记表，记录寻路器为自己放下的方块。

## 修复

- **背包里明明有却报"缺少材料"。** 判料拿整个 `BlockState` 去比"按单个 `Direction.UP` 猜出来的状态"，任何带轴的方块（`oak_wood[axis=x]`、`[axis=z]`）永远匹配不上，建造就为一个自己有的方块停住。
- **`allowInventory true` 依然报缺少材料。** 只查快捷栏，主背包 9–35 格被忽略，而这个设置存在的意义就是用它。
- **`resume` 之后立刻又暂停。** 它保留了被裁剪过的 `incorrectPositions`（约 14 格外全被删），下一 tick 就以为这层完工。现在从干净状态开始并清空挂起的层。
- **为一个够不到的方块把整个建造停掉。** 这类坐标记进 `placementStruggles`，失败达到 `PLACEMENT_STRUGGLE_LIMIT` 次后加入 `abandonedPlacements` 并留一行解释，建造继续，`resume` 时再试。
- **挖自己搭的脚手架。** `MovementHelper.attemptToPlaceABlock` 会垫一个临时方块好走过去；`assemble` 随后把那一格看成"应该是空气"挖掉，寻路器再垫一次 —— 无限循环。现在寻路器放的脚手架登记在 `PlacedBlocks`，并且**只在目标要求空气的地方**才放过它；目标要求真方块的地方，脚手架跟别的障碍一样会被挖掉。
- **挖掉根本不在路线上的方块。** 破坏以前跑在放置之前、和放置共用 5 格半径、且从不判断是否真的挡路：建造器会走到一半停下、啃掉几个无关方块、把洞留在那里。现在破坏有自己的 `BREAK_SCAN_RADIUS = 2`、跑在放置**之后**，并在跟随路径时整段跳过 —— 路线上的障碍是寻路器的事，由 `breakCostMultiplierAt` 计价。
- **放了挖、挖了又放。** 错朝向来自"大致瞄对就点"结果点到别的面，那些格子又得挖掉重做，无限折腾就是从这里来的。由 `Placement.strictAim` 修掉。
- **对着一个位置点个没完，方块就是不出现。** `searchForPlacables` 把"找到放置方案"当成功并清掉所有计数器，于是客户端悄悄拒绝的位置（玩家碰撞箱就在格子里、支撑被挖掉、射线差一点）每 tick 重试且没有任何记录。现在与玩家碰撞箱相交的格子直接跳过（`playerIsStandingIn`），`[PLACE]` 每位置一行而不是每 tick 一行。
- **进世界时崩溃：** `PathingBehavior.dispatchEvents` → `pathStart` 抛出 `IPlayerContext.playerFeet` 里的 `NullPointerException`。Java 调用前先求值参数，所以即使日志关着 `pathStart()` 也照跑，而它在没有玩家时读玩家坐标。现在 `dispatchEvents` 在日志关闭时提前返回，`safePathStart()` 渲染成 `<no player>`，`onTick` 在 `ctx.player() == null` 时直接退出。
- **被反复裁剪吃掉的 tick。** `trim()` 每 tick 把候选集缩到玩家周围；加上 `incorrectSize = 100` 让 `fullRecalc` 提前返回，"这层没活了"只在玩家附近成立。`assembleWholeBand` 重扫整层带，并用 `WHOLE_BAND_RESCAN_TICKS = 10` 冷却避免兜底把 tick 循环抖爆。

## 变更 —— 性能与调度

- `assemble(...)` 先建可放置位置的 `HashSet`，不再对每个候选调 `List.contains`（以前每 tick O(n²)）。
- `approxPlaceable` 按物品列表快照缓存（`refreshApproxPlaceable`），不再每 tick 拿 36 个槽位重建。
- `BuilderCalculationContext.placeable` 跟随 `allowInventory`，`costOfPlacingAt` 改用 `couldPlaceAs`；以前任何不在快捷栏的方块都会让自己的位置变成 `COST_INF`，逼 A* 丢弃并重算整段路径。
- `toBreakNearPlayer` / `searchForPlacables` 改成由近到远的环形扫描（`VERTICAL_ORDER_PLACE` / `VERTICAL_ORDER_BREAK*`），不再是从扫描盒远角开始的固定 `dx → dy → dz` 遍历。
- `hasAnyItemThatWouldPlace` 先按方块身份过滤快捷栏槽位，再模拟放置。
- `onTick` 顺序现在为：放置 → 破坏 → 搬背包 → 局部 assemble（口袋）→ 局部 assemble（整层）→ 缺料 assemble → 整层带 assemble → 进层 → 挂起该层 → 暂停。

## 变更 —— 日志量

第一版日志什么都写，十一分钟产出 13.6 MB / 44,018 行（`[TICK]` 每 10 tick、`[BEAT]` 每 100、`[RECALC]`/`[ASSEMBLE]`/`[PLACE]` 每 tick，每个目标完整展开）。

- `[PLAN]` 只在计划变化时写（层、待处理数、被放弃数）+ 每 200 tick 一次心跳；签名刻意排除每 tick 都变的值。
- `[PATH]` 去重，目标由 `BaritoneLog.goal` 渲染，包括以前会打印原始复合目标的 `findPathInNewThread`。
- `[BREAK]` 每目标一行、`[PLACE]` 每位置一行、`[ASSEMBLE]` 只在计数变化时写。
- `#debug` 在本 fork 的历史：全量细节（太多）→ 精简（太少，且 2 MB 上限会在会话中途把文件静音）→ 现在这一套：配置、决策、异常。

## 回滚

- 放置一律蹲下。曾经试过去掉蹲起改用走搭，结果点击落点常常不一样（支撑把点击吃掉、玩家在点击发出瞬间贴边滑走），放出来的方块状态是错的、要挖掉重做，比蹲下花的那一个 tick 亏得多。无条件蹲下已恢复。

---

**完整改动：** [`CHANGELOG.md`](https://github.com/niccom1/baritone-1.21.11/blob/main/CHANGELOG.md) · 提交 [`e7aa3d3`](https://github.com/niccom1/baritone-1.21.11/commit/e7aa3d3)（建造修复）、[`adb9202`](https://github.com/niccom1/baritone-1.21.11/commit/adb9202)（更新日志）。

> English version: [CHANGELOG.en.md](https://github.com/niccom1/baritone-1.21.11/blob/main/CHANGELOG.en.md)
