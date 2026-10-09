# 快照锚：discofix-impl + discofix-impl2（同份，双署名）

- 快照时间：2026-10-06 16:28 CST（复制 + 双端逐文件核对完成于同刻）
- 快照原因：作者六裁清算令 —— 分支清算执行批，阶段 1 快照（先于一切删除动作）
- 来源分支（双署名）：
  - discofix-impl，worktree：/Users/kaiyu/Claude code/Nebflow/.nebflow/worktrees/discofix-impl，HEAD sha：651759121f3d026f6b21f9213e9bac63b8359979
  - discofix-impl2，worktree：/Users/kaiyu/Claude code/Nebflow/.nebflow/worktrees/discofix-impl2，HEAD sha：dc6c853d7f8ef2d6b409c09dfbc0f1c9367da780
- 同份证实：两 worktree 各有 5 个已修改脏文件，路径一致；`diff` 逐文件比对字节级相同，故一份快照双署名，不分开存。

## 原文件相对路径清单（5 个，均相对 worktree 根）

| 相对路径 | 字节数（快照 = impl = impl2，cmp 全 OK） |
|---|---|
| src/main/scala/nebflow/neblink/NeblinkClient.scala | 94042 |
| src/main/scala/nebflow/neblink/NeblinkDiscovery.scala | 10366 |
| src/main/scala/nebflow/neblink/NeblinkPresenceService.scala | 68409 |
| src/test/scala/nebflow/neblink/NeblinkPresenceConvergenceSpec.scala | 15891 |
| src/test/scala/nebflow/neblink/NeblinkPresenceDialBudgetSpec.scala | 43497 |

## 核对记录

- 快照复制自 discofix-impl worktree；逐文件 `cmp` 同时对照 discofix-impl 与 discofix-impl2 两个 worktree 源文件，10 组比对全部 OK，字节数三方一致。

## 附注

- discofix-impl2 worktree 内另有 src/main/scala/nebflow/neblink/AttachmentPreviewContract.scala，为 impl2 HEAD（dc6c853d7）已提交内容（blob e733701cb1ebf878240240d64bdfe0326b521fd0），非脏件，不属快照对象；删支动作对该提交对象的处置属作者清算令授权范围。
- 目录名使用 `discofix-impl+discofix-impl2` 以明示一份快照覆盖两支。
