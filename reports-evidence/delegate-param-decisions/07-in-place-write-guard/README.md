# 07 · 就地写盘护栏（补充令：写文件必标红 / 只读不误报）

判据（作者 2026-11-04 21:03）：「不填 worktree 的任务是适合于只读型任务 工作还是要默认创建worktree」——
所以零分配通道**事后必须有人叫**：spawn 时落快照（HEAD + `git status --porcelain` 路径集），收尾比对，
一旦该任务写了东西（新增脏路径 / HEAD 移动 / 分支领先基分支）就**回执首行标红**，不许静默通过。

## 命令原文

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-dcdeaa46"
sbt -batch "testOnly nebflow.core.delegate.InPlaceWriteGuardSpec nebflow.core.delegate.DelegateWorkspaceWorktreeLegSpec \
  nebflow.core.delegate.ModeParamSpec nebflow.core.delegate.DeviceWorktreeResolutionSpec" \
  > /tmp/guard.log 2>&1
```

读数：`[info] Passed: Total 22, Failed 0, Errors 0, Passed 22`（`raw-output.log` 头部）。

## 原始输出（用例 A · 写入 ⇒ 标红）

```
── [用例A · 写入] inPlaceWriteAlarm ⇒ Some(
🔴 [IN-PLACE WRITE ALARM] 本任务未分配 worktree 但改动了项目工作区，路径清单如下 / this task was dispatched WITHOUT a worktree (the read-only channel) yet it changed the project workspace:
  - 1 new path(s): ?? sneaky.txt
── [用例A · 写入] )
  + A 标红：没分配 worktree 却写文件 ⇒ 回执行点名改动路径 0.447s
```

## 原始输出（用例 B · 只读 ⇒ 不误报）

```
── [用例B · 只读] inPlaceWriteAlarm ⇒ None
  + B 不误报：同一快照、确实只读 ⇒ None 0.156s
```

## 原始输出（用例 E · 只提交不写文件 ⇒ 也标红）

```
── [用例E · 只提交] inPlaceWriteAlarm ⇒ Some(🔴 [IN-PLACE WRITE ALARM] 本任务未分配 worktree 但改动了项目工作区，路径清单如下 / this task was dispatched WITHOUT a worktree (the read-only channel) yet it changed the project workspace: ⏎   - HEAD moved (was 29e85b3, now 69d87d8) — commits were created)
  + E 提交也算：没写文件但产生了提交（HEAD 移动）⇒ 标红 0.164s
```

## 五条用例的判据与读数

| 用例 | 判据 | 读数 |
|---|---|---|
| A 标红 | 零分配腿 + 写了文件 ⇒ 标红行 + **点名改动路径** | 通过（上面原文，`?? sneaky.txt`） |
| B 不误报 | 同一快照、只读（`git status`）⇒ `None` | 通过 |
| C 有树不检 | 绑定了 worktree 的记录（在自家树里写是本分）⇒ `None` | 通过 |
| D 回执位形 | 标红行缀在 `Outcome.Release` **正文首行**；无命中时正文逐字节不变 | 通过 |
| E 提交也算 | 不写文件但产生提交（HEAD 移动）⇒ 标红 | 通过 |

## 实现位置

- `src/main/scala/nebflow/core/delegate/DelegateTerminal.scala`：`InPlaceWriteAlarmPrefix`、
  `inPlaceWriteAlarm(record)`（只对「无 worktree 且带快照」的记录检）、`withInPlaceAlarm`
  （缀进 `Outcome.Release` 首行）；`onCompleted` 三个出口全部经它包裹，命中写 WARN 日志 +
  ChainStore `in-place-write-alarm` 行。
- `src/main/scala/nebflow/core/delegate/DelegateRegistry.scala`：`Record.seatSnapshotSha` /
  `seatSnapshotDirty`（spawn 侧落盘，解码器同步）。
- `src/main/scala/nebflow/core/delegate/DelegateWorkspace.scala`：`prepareInPlace` 采集快照。
