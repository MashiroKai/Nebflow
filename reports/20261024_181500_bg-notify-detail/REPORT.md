# 后台任务完成通知详情修复（作者报障 2026-10-24 17:58）

## 报障与根因

作者逐字：「我觉得我们后台任务通知 的很不详细甚至有截断好像。这里是不是存在问题。」
截图现场：通知气泡只显示 `[Background task completed] "Render v4.pptx to PDF then PNGs in background";` 一行——无退出码、无耗时、无输出，作者无法判断任务成败。

### 根因（两处叠加，都在引擎负载侧；前端渲染面无截断）

1. **负载缺料**（`BashTool.makeNotifyCallback`）：
   - 完成通知**只在 exit≠0 时才带退出码**（`exitTxt = if pr.exitCode != 0 …`）——成功任务（截图现场）的通知只剩描述行 + 一个悬空冒号，零输出时连正文都没有；
   - 无耗时信息；
   - 失败通知只带异常 message 一行（无耗时、无尾段截断）。
2. **远程腿更薄**（`RemoteExecutor.notifyRemoteBgResult`）：完成通知连退出码位都没有，失败同理；亦无耗时。
3. 「甚至有截断」= 悬空冒号 + 单行描述的观感；前端 `buildInjectedRow` 渲染全量 payload、无行数/字符限制（排查结论：前端无截断缺陷）。
4. 反向缺陷同点存在：完成通知把 **stdout 全量**塞进 payload（无 tail 上限）——通知面不该背大输出；全文本就有 `BgTaskOutputStore` 落盘（REST 详情卡，2026-09-09 批）。

## 修法

| # | 件 | 内容 |
|---|---|---|
| 1 | `BgNotifyPayload.scala`（新增） | 终态通知负载**纯组装单点**：完成 = `[Background task completed] "desc" (exit N · dur):\n<尾段>`（**退出码恒在**，exit 0 = 显式成功信号）；失败 = `[Background task failed] "desc" (dur):\n<错误尾段>`；正文截末尾 N 行 + 截断标注（总行数 + jobId 指回详情卡）；零输出 = `[no output]` 占位（悬空冒号消灭）；metadata 只增键 durationMs/truncated/totalLines/taskId |
| 2 | `Defaults.BgNotifyTailLines` | 尾段行数上限，prop `nebflow.bg.notifyTailLines`，默认 20（可配置，I2 缝先例） |
| 3 | `BashTool.makeNotifyCallback` | 负载走 BgNotifyPayload；耗时 = 注册→终态墙钟差（新 `BgTaskRegistry.unregisterReturning` 一拍原子取 startedAtMs）；WS `backgroundTaskUpdate` 终态帧只增键 `exitCode`/`durationMs`/`finishedAt` |
| 4 | `RemoteExecutor.notifyRemoteBgResult` | 同一负载单点（exitCode=None——远程无进程退出码，耗时段仍在）；终态帧同三键 |
| 5 | `WsSystemHandlers` 取消腿 | 取消通知补原因段 `(Cancelled by user)` |
| 6 | `main.js` 面板 | 终态行 exit chip（复用 `bg.detail.exit` 键，零新 i18n）；终态耗时**冻结**（durationMs 权威，缺省回落 finishedAt−startedAt）——旧行为终态后仍在走表谎报「已运行 N 分钟」；ticker 加终态守卫；帧缺键 = 缺读零回归 |
| 7 | 「跳全文」入口 | 已有面复用：通知尾段 + 标注行指 jobId；面板行点击 → REST `/api/bg-tasks/:id/output` 详情卡全量（`BgTaskOutputStore` 照旧落全文） |

不改动：`AgentCommand.BackgroundTaskNotification`（无生产构造点，死命令面）；前端气泡渲染（无缺陷）。

## 验证

- `BgNotifyPayloadSpec`（新增，14 断言）：spec 三钉——①完成通知含 exit code 与输出尾段（成功 exit 0 / 非零 / 远程无码 / 零输出占位）；②长输出截尾（30 行 tail=20 只带末段 + 标注；全文经 BgTaskOutputStore 整段读回；prop 改 3 行生效）；③失败通知含错误尾段 + 截尾 + 耗时文案。
- `scripts/e2e-bgtask-notify-detail.cjs`（新增，14 断言 ALL PASS，截图 `evidence/20261024_bg-notify-detail/panel.png`）：P1 完成行 exit chip + 耗时冻结（1.6s 不变）；P2 失败行无码缺读；P3 气泡折叠→展开含 exit/耗时/截断标注、正文不含头部行；P4 行点击详情卡 40 行全文（tail/full 配对）；P5 零 JS 错误。
- 既有面回归：sbt 全测试（见下）；checkJs 260 = 冻结基线 257 + 3（全来自未跟踪 `nearpath.js`，其他在活会话在制件，非本批产物；main.js 零新增错误）。

## 结论

（测试结果跑完后回填）
