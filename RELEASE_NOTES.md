# 分层版 v1.21.11-layered.3

Minecraft 1.21.11 的 Baritone 分层建造修改版。

这一版处理两个问题：建造途中因为空指针崩掉游戏，以及调试日志删掉之后再也不写。

## 空指针崩溃

崩溃栈停在 `BuilderProcess.isRemovalHeavy`，`target` 是 null：

```
Cannot invoke "baritone.api.schematic.ISchematic.widthX()" because "target" is null
  at baritone.process.BuilderProcess.isRemovalHeavy(BuilderProcess.java:1345)
  at baritone.process.BuilderProcess.createLayerScheduler(BuilderProcess.java:1326)
  at baritone.process.BuilderProcess.onTick(BuilderProcess.java:836)
```

`onLostControl()` 把 `schematic`、`realSchematic`、`layerScheduler` 都置空了，唯独留下 `stopAtHeight`（日志里是 131）。别的进程在中途抢走控制权，剩下的代码仍按老目标往下走：`realSchematic` 变成 null，分层切片的条件却还成立，于是给一个不存在的目标创建层调度器，采样时解引用 null。

修法：`onLostControl()` 清掉 `stopAtHeight`；`onTick` 发现目标为空就先 `onLostControl()` 再返回 null；`createLayerScheduler()` 与 `isRemovalHeavy()` 各自判空。

## 调试日志不再写入

删除日志文件后，句柄仍然有效，后续每一行都写进没有目录项的 inode，新文件不再出现。现在写之前检查文件是否还在，不在就重建并补文件头。

另一个是 8 MB 上限：提示让用户 `#debug false` 再开，代码里没有对应重置。现在重新打开会清空计数和上限标记。

## 顺带收紧

复查主流程和寻路时补的约束：`PathingControlManager` 可重入守卫加副本遍历（避免 `ConcurrentModificationException` 崩游戏）、`PathingBehavior.destOf`、`BuilderProcess` 里 `trim`/`selectSweepPocket`/`assemble` 的空判断、背包槽位越界保护、`getSchematic` 判空、`layerHeight` 除零。正常路径行为不变。

---

> 英文版：[CHANGELOG.en.md](CHANGELOG.en.md)
