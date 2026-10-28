（假设你已经[安装](SETUP.md)好了 Baritone）

# 前缀

Baritone 的聊天控制前缀默认是 `#`。在 Impact 中，你还可以用 `.b` 作为前缀。（例如用 `.b click` 代替 `#click`）

默认情况下，Baritone 命令也可以直接在聊天框中输入。但如果你打错了字，比如把 "goal" 打成 "gola 10000 10000"，它就会被发到公共聊天里，这很糟糕，所以建议使用 `#`。

要禁用直接聊天控制（不带前缀），请关闭 `chatControl` 设置。要禁用带 `#` 前缀的聊天控制，请关闭 `prefixControl` 设置。在 Impact 中，`.b` 无法被禁用。注意别让自己把所有控制方式都禁用了（如果真的这样了，删除 `minecraft/baritone/settings.txt` 文件并重新启动游戏即可重置设置）。

# 适用于 Baritone 1.2.10+、1.3.5+、1.4.2+

很多命令都变了，但 `#help` 得到了极大的改进（它可以点击！命令有 Tab 补全！天哪！）

试试 `#help`，我保证它不会只是把你送回这里 =)

"wtf，cleararea 去哪了" -> 看 `#help sel`

"wtf，goto death、goto waypoint 去哪了" -> 看 `#help wp`

就看看 `#help` 吧 哈哈

看这个[演示视频](https://youtu.be/CZkLXWo4Fg4)！

# 命令

[教程播放列表](https://www.youtube.com/playlist?list=PLnwnJ1qsS7CoQl9Si-RTluuzCo_4Oulpa)

**所有**这些命令前面都可能需要加一个前缀，如上文所述 ^。

`help`

要切换一个布尔设置，只要在聊天里说出它的名字即可（例如说 `allowBreak` 就会切换 Baritone 是否考虑破坏方块）。对于数值设置，说出它的名字然后跟上新值（例如 `primaryTimeoutMS 250`）。它不区分大小写。要把某个设置重置为默认值，说 `acceptableThrowawayItems reset`。要重置所有设置，说 `reset`。要查看所有被改过默认值的设置，说 `modified`。

Baritone 中的命令：

- `thisway 1000` 然后 `path`，朝你面朝的方向前进一千格
- `goal x y z` 或 `goal x z` 或 `goal y`，然后 `path`，把目标设到某个坐标再寻路过去
- `goto x y z` 或 `goto x z` 或 `goto y`，前往某个坐标（一步到位，立即开始走）
- `goal` 把目标设为玩家脚下
- `goal clear` 清除目标
- `cancel` 或 `stop` 停止一切，`forcecancel` 也是一个选项
- `goto portal` 或 `goto ender_chest` 或 `goto block_type` 前往某个方块。（在 Impact 中，`.goto` 大体上是 `.b goto` 的别名）
- `mine diamond_ore iron_ore` 挖掘钻石矿或铁矿（打开 `legitMine` 设置可以只挖掘它真正能看到的矿石。它会在 y=11 附近随机探索直到找到它们。）也可以指定数量，例如 `mine 64 diamond_ore`。
- `click` 在屏幕上点击你的目的地。右键点击可寻路到方块顶部，左键点击可寻路进入该方块（脚部或眼睛高度均可），左键点击并拖动可选择一个区域（用 `#help sel` 查看你能对这个选择做什么）。
- `follow player playerName` 跟随某个玩家。`follow players` 跟随范围内的任意玩家（配合杀戮光环会很有趣）。`follow entities` 跟随任意实体。`follow entity pig` 跟随特定类型的实体。
- `wp` 用于路径点。一个"标签"（tag）就像 "home"（右键点击床时自动创建）、"death"（死亡时自动创建）或 "user"（必须手动创建）。所以你可能会想先 `#wp save user coolbiome`，然后设目标 `#wp goal coolbiome`，再 `#path` 寻路过去。对于 death，`#wp goal death` 会列出 "death" 标签下的路径点（记住内容是可点击的！）
- `build` 用于建造结构投影（schematic）。`build blah.schematic` 会加载 `schematics/blah.schematic`，并以你的玩家脚部为原点来建造它。`build blah.schematic x y z` 用于设置原点。其中任何一个坐标都可以相对于你的玩家（`~ 69 ~-420` 表示在 x=玩家 x、y=69、z=玩家 z-420 处建造）。
- `schematica` 用于建造当前在 schematica 中打开的结构投影
- `tunnel` 用于挖掘并开出一条 1x2 的隧道。它只会在必要时（例如为了避开岩浆）偏离直线。如果你想要一条更笨的、其实只是 cleararea 的隧道，可以用 `tunnel 3 2 100`，清理一个高 3、宽 2、深 100 的区域。
- `farm` 用于自动收获、补种作物或使用骨粉。用 `farm <range>` 或 `farm <range> <waypoint>` 来限制与起点或某个路径点的最大距离。
- `axis` 前往 y=120 处的某条轴或对角轴（`axisHeight` 是可配置设置，默认为 120）。
- `explore x z` 从 x、z 原点开始探索世界。省略 x 和 z 则默认为玩家脚部。它会不断朝原点附近它从未见过的最接近的区块寻路。`explorefilter filter.json` 配合可选的 invert 可用于加载一个要加载的区块列表。
- `invert` 反转当前的目标和路径。它会尽可能远离目标，而不是尽可能靠近。例如，执行 `goal` 然后 `invert`，就能从你起始时所站的位置尽可能跑远。
- `come` 告诉 Baritone 朝你的摄像机方向前进，当自由视角（freecam）没有移动你的玩家位置时很有用。
- `blacklist` 会让 baritone 停止前往最近的方块，这样它就不会尝试去那里了。
- `eta` 获取关于到下一段以及到目标的预计时间的信息，请注意到目标的 ETA 非常不精确。
- `proc` 查看关于当前控制 Baritone 的进程的杂项信息。
- `repack` 重新缓存你周围的区块。
- `gc` 调用 `System.gc()`，可能会释放一些内存。
- `render` 修复出错的区块渲染，而不必重新加载所有区块。
- `reloadall` 重新加载 Baritone 的世界缓存，或 `saveall` 保存 Baritone 的世界缓存。
- `find` 在 Baritone 的缓存中搜索，并尝试找到该方块的位置。
- `surface` 或 `top` 告诉 Baritone 朝最近的类地表区域前进，这可以是地表或可用的最高空气空间。
- `version` 获取你正在运行的 Baritone 版本
- `damn` daniel

所有设置和文档都在<a href="https://github.com/cabaletta/baritone/blob/master/src/api/java/baritone/api/Settings.java">这里</a>。如果你觉得 HTML 比 Javadoc 更好读，可以看<a href="https://baritone.leijurv.com/baritone/api/Settings.html#field.detail">这里</a>。

大约有一百个设置，但在日常使用 Baritone 时，下面这些是你可能会想改的有趣/有意思/重要的设置。每一项的文档都可以在上面这些链接中找到。

- `allowBreak`
- `allowSprint`
- `allowPlace`
- `allowParkour`
- `allowParkourPlace`
- `blockPlacementPenalty`
- `renderCachedChunks`（以及 `cachedChunksOpacity`）<-- 非常有趣，但你需要一台性能强劲的电脑
- `avoidance`（躲避怪物 / 怪物刷怪笼）
- `legitMine`
- `followRadius`
- `backfill`（在你身后回填隧道）
- `buildInLayers`
- `buildRepeatDistance` 和 `buildRepeatDirection`
- `worldExploringChunkOffset`
- `acceptableThrowawayItems`
- `blocksToAvoidBreaking`
- `mineScanDroppedItems`
- `allowDiagonalAscend`

# 疑难解答 / 常见问题

## 为什么 Baritone 不响应我的任何聊天命令？

这可能是很多原因之一。

首先，确认它确实安装了。一个简单的检查方法是看它是否在你的 Minecraft 文件夹中创建了 `baritone` 文件夹。

其次，确认你正确使用了前缀，并且聊天控制按你预期的方式启用了。

例如，Impact 会禁用直接聊天控制。（也就是说，聊天中输入的任何不带前缀的内容都会被忽略并公开发送）。**这是一个会被保存的设置**，所以如果你运行过一次 Impact，`chatControl` 从此就会是关闭的，**即使在其他客户端中也是如此**。
因此你需要使用 `#` 前缀，或者编辑 Minecraft 文件夹中的 `baritone/settings.txt` 来撤销这一设置（具体来说，删除 `chatControl false` 这一行，然后重启你的客户端）。

## 为什么我在 Impact 里可以用 `.goto x z`，别处却不行？为什么我在 KAMI 里可以用 `-path to x z`，别处却不行？

这些是它们各自添加的自定义命令；它们并非来自 Baritone。
你要找的等价物是 `goto x z`。

> English version: [USAGE.en.md](USAGE.en.md)
