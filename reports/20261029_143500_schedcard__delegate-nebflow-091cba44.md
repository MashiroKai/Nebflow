# schedcard 批报告（作者令 2026-10-29 13:41：「现在定时任务的卡片会退化成消息啊 我们可能要对定时任务的卡片进行标记一下 这样保证它不会退化为绿色的消息气泡 另外把卡片风格修改为stand的配色风格」）

分支 `delegate/delegate-nebflow-091cba44`；单一主题（定时任务触发行防退化 + 卡片化），独立 commit；在产实例（:8080/:6138、`~/.stand`）零触碰——LIVE 验证走隔离实例（`/tmp/qa-schedcard-live`，:8119，stub LLM :19001，assembly jar 自建构建）。

## 根因（两条，叠加成退化）

1. **落盘/广播裸 User**：`ScheduledTaskService.triggerTask` 触发时落盘一条**零标记**的 `UiMessage.User`（无 `injected`、无 `source`），广播帧同为裸 `userMessage`（且前端零消费者——死帧）。历史恢复（localStorage 缓存 + 后端 .ui.json）都拿不到任何「这不是用户手打消息」的信号，前端只能按无标记 user 行渲染 → **绿色消息气泡**。
2. **瞬态 toast 有 DOM 无锚**：改前 live 呈现依赖 `scheduledTaskTriggered` 处理器注入的瞬态 toast DOM（刷新即逝，且样式族 `.reminder-trigger-bubble` 是孤儿死样式）。live 面不救历史面，历史面无信号可救。

## 改动（标记 + 卡片两半）

### 后端（标记的唯一写点）

- `protocol.scala`：`UiMessage.User` 尾增 `schedule: Option[Json] = None`（quote/fileChanges 先例：缺席即不落键，wire 向后兼容——decoder 可选读，旧帧/旧行零影响）。
- `ScheduledTaskActor.scala`：`triggerTask` 落盘行带 `injected=true, source=Some("schedule"), eventType=Some("trigger"), schedule=Some(scheduleMeta)`；meta = `{id, name?, triggerAt, repeat?, referencePath?}`。广播帧改注入 user 标准形（`{type:"user", text, injected, source:"schedule", eventType:"trigger", timestamp, schedule, sessionId}`）。**唯一写点**——Schedule 任务的触发行不可能绕开此处。
- `AgentCommand.scala`：`BackendNamedSources += "schedule"`（含出处注释；ExternalEvent 面源名 `scheduled-task` 带连字符，不属本集合，不受影响）。
- `NotificationHeader.scala`：`KindLabels += "schedule" -> "SCHEDULE"`（通知头词表）。
- `WsSessionChatHandlers.scala`：handleUserMessage 附近过时 NOTE 注释更新。

### 前端（专属卡 + 防退化钉面）

- `injectedRows.js`：`INJECTED_SOURCE_LABELS += schedule: 'Schedule'`；`buildInjectedRow` 第 12 参 `schedule`，**顶部 `source==='schedule'` 分支先行 return** 走 `buildScheduleCardRow`（防退化钉面：带标记永不落通用绿泡/通用注入泡分支）；新 `buildScheduleCardRow` —— `.bubble.injected.schedule-card[data-nf-schedule=1]`，头行 bell-ring 图标 + chip（`task.cardChip`）+ 任务名 + 重复小标，正文走 markdown 面（剥引擎注记尾行「（实际触发…，预定…）」），`referencePath` 引用行，clock 图标 + `task.cardScheduledAt`（预定 {time}，triggerAt 缺席回落行 timestamp）；footer pill + quote menu 同通用注入族。`renderInjectedBubble` 透传。
- `main.js` onMessage('user')：saveMsg 缓存条目携带 `schedule`；渲染调用透传（live 腿）。
- `persistence.js`：restoreFromStorage + buildHistoryRow 两史恢复腿同样透传——**三条渲染腿同 builder 同构**。
- `scheduled-task.js`：`scheduledTaskTriggered` 瞬态 toast DOM 注入段删除（卡片唯一呈现 = 注入帧专属卡分支，live/史一致）；未用 import 摘除。
- `reminder.css`：死样式 `.reminder-trigger-bubble` 整段删除；新增 `.bubble.schedule-card` 整族样式——**console 统一 tokens**（`--sapphire` 族 + `--glass-*` + `--glass-etched-*`，零新色值），亮暗双档（prefers-color-scheme 同机制）。
- locales `zh-CN.js`/`en.js`：`task.cardChip`（定时任务 / Scheduled Task）、`task.cardScheduledAt`（预定 {time} / Scheduled {time}）。

### 兼容语义（不追溯）

带标记行（本批起落盘）三腿专属卡；无标记旧行（改批前落盘）照旧绿泡——旧历史行不追溯改写，回归不破；非 schedule 源（mail 等）注入照走通用注入泡。

## 钉（测试）

- `UiMessageScheduleSpec.scala`（新，4 测）：`schedule` 编解码往返 / 缺席不落键 / 旧 payload 可选读。
- `ScheduleCardContractSpec.scala`（新，8 测）：防退化文本契约门——① buildInjectedRow 的 schedule 分支先于通用组装 ①b 结构面（data-nf-schedule/卡族 class）② 改批前形态（无标记）回归不破 ③ 全链贯通（ScheduledTaskActor 落盘标记 ↔ 前端透传 ↔ 分支命中）③b main.js saveMsg `msg.schedule` ③c persistence.js 两处 `m.schedule` ④ 词表登记（BackendNamedSources/KindLabels/INJECTED_SOURCE_LABELS）⑤ 瞬态 toast 零复活（scheduledTaskTriggered 无 DOM 注入）⑥ i18n 两 locale 键齐。
- `InjectionSourceContractSpec.scala`：pin① 加 `"schedule"`。
- `NotificationHeaderSpec.scala`：expected map 加 `"schedule"->"SCHEDULE"`。
- `tests/schedule-card.spec.mjs`（新，playwright 8 测，真模块三腿夹具 harness.html）：NEW/ANON（匿名）/REF（带引用）三形态卡 + OLD（无标记绿泡回归）+ MAIL（非 schedule 注入泡回归锚）× 三腿（缓存恢复/后端历史/live 帧）防退化主钉 + 亮暗 before/after 截图。

## 证据（evidence/20261029_143500_schedcard__delegate-nebflow-091cba44/）

- `harness/`：before/after × 亮暗 4 张（playwright 夹具实拍：退化绿泡 vs sapphire 玻璃卡）。
- `live/`：LIVE 隔离实例真触发实拍——light / dark / after-reload 3 张（1 分钟一次性任务经 stub LLM 脚本化 Schedule 工具调用创建 → scheduler 真轮询到点 → 注入帧 → 卡渲染；reload 后自 .ui.json 历史重建同构）。

## 验证台账

- `sbt compile` 绿；sbt test（本批 4 spec：UiMessageScheduleSpec + ScheduleCardContractSpec + InjectionSourceContractSpec + NotificationHeaderSpec）28 测全绿。
- `tests/schedule-card.spec.mjs` 8/8 绿；`badge-injected-turn` / `bgagent-injected-bubbles` 既有 spec 绿（badge spec 以 source='schedule' 作夹具仍绿——卡保留 `.bubble.injected` 基类，turnGroup/workline 消费面不破）。
- LIVE e2e（`scripts/e2e-schedule-card-live.mjs`，隔离实例 :8119）全绿：LIVE① 触发即渲染（卡 110s 窗内出现，chip=定时任务/任务名/预定时间全对）①b 卡头 ①c 正文剥引擎注记 ①d 注入族基类保留；LIVE② 零绿泡承载（green=0）②b 退役 toast 零复活 ②c 恰一张卡（cards=1）②d 注入帧带 `source=schedule`+`schedule` 载荷；LIVE③ reload 后卡自后端历史重建 ③b 重建零退化。console 清洁（白名单=fresh home 既有探针噪音，同 e2e-askuser-source-label 口径）。
- 凭据链备忘：页面 WS 握手只认 HttpOnly 会话 cookie（ws.js「handshake authenticates with the HttpOnly session cookie」，stage D 已拔 legacy 轨）——LIVE 脚本走真实链 `POST /api/auth/console-ticket`（AuthAdmin 面，BearerFileOnly=auth.json token）→ `GET /?ticket=` 303 兑换 cookie → 页面带会话启动。localStorage token 不进握手。
- `node --check` 全部改动 JS 过；scalafmtOnly 本批 5 个 Scala 文件合规（`Compile / scalafmtCheck` 失败清单 HEAD=201 → 现在=199，本批文件清零，顺带修好 2 个 HEAD 偏差）。

## 预存在红申报（HEAD 复跑同红，非本批引入，不碰不修）

1. `SubAgentInboxMirrorSpec` ③-2 `emitInjectedUserEvent` 钉值 15≠14——他批钉区漂移，HEAD 即红。
2. `tests/history-replay-cards.spec.mjs` compact-card 腿——HEAD 同红。
3. checkJs gate：`canvas.js`「new file with 2 errors」（`_pmInstalled`）——来自已并批 6a9f17241；本批所碰 JS 文件全零错，总数 256 < baseline 260。
4. repo 级 scalafmt 大面积不合规（HEAD 201 文件）——疑环境 scalafmt 版本漂移；本批文件全部清零。
