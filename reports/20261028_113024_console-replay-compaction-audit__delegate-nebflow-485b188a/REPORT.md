# 控制台两案只读排查报告——回放裸工具调用 / 无感压缩路径

- 授权：作者 2026-10-28 11:0x 令（附 10:28 / 10:26 两截图）。纯只读批次：未改任何代码/配置/数据，未触碰在产引擎（pid 70481 / gateway 6138），全部结论来自源码阅读、引擎日志（`~/.stand/logs/stand.log`）、会话落盘（`~/.stand/sessions/…`）与前端代码静态分析。
- 排查基线：repo `e2388da0c`（main @ 2026-10-28 11:0x），在产引擎构建 v2026.10.26-beta.2。
- 本批产物：本报告。两阶段纪律——只交付设计与根因，**不含实施**。

---

## 案一：刷新/断线后历史回放裸露工具调用

### 1.1 结论（根因）

**live 与回放是两条折叠路径，回放折叠不是数据驱动的，而是一次启发式"成功徽章"重推导；推导不出就整段平铺。**

- **live 路径**：轮终态（`done` 帧）→ `turnGroup.collapseTurn()` **无条件**把该轮的 thinking 行 + tool 行打 `.nf-tucked` 藏起，只留一枚 turn-header 摘要条（`✻ 字句 · 模型 · 思考 Ns · 工具 M 次`）。不依赖任何徽章启发式——每轮必折。
- **回放路径**（刷新页面 / historyPage 初载 / localStorage 恢复 / 上滑补页，四条腿同一渲染器）：先逐条平铺渲染（`persistence.js buildHistoryRow`），再跑 `turnGroup.buildTurnSummariesForHistory()` 补推导。**推导判据（turnGroup.js `findDoneBadge`）= 该轮段落里必须存在一枚带 `data-nf-phrase` 的时长徽章**；而这个徽章只有在某条 `ai` 条目同时满足「`text` 非空 且 `durationMs > 0`」时才会被渲染（`persistence.js`：badge 创建在 `if (m.text)` 分支内，`createDurationBadgeElement` 才带 `nf-phrase`）。任一条件不满足 ⇒ `summarizeSegment` 返回空 ⇒ **该轮无 header、无 tuck ⇒ 工具卡全部裸露**。

五类必裸面（对照今天会话实测数据）：

| # | 裸露条件 | 实测 |
|---|---|---|
| 1 | 段落末条 ai 有 `durationMs` 但 `text` 为空（纯工具轮）→ 徽章整个不渲染 | 会话 d7c3cb54 当前转录 800 条中 2 条（下标 744/763） |
| 2 | 段内没有一条 ai 带 `durationMs>0`（多气泡轮的中间气泡全部只有 plain footer，plain footer 刻意不带 `nf-phrase`） | 136 条 ai 有 text 无 durationMs |
| 3 | 失败轮（`failTurn` 语义：平铺排障，回放侧同判） | 设计如此 |
| 4 | 刷新时该轮尚未终态（busyTail：尾段刻意保持展开，等 live 终态来收）——**10:28 截图即此面**：刷新时根会话正处派出 #995 后的进行轮，尾段 open ⇒ Delegate 卡裸露 | stand.log 10:2x 显示会话持续在飞 |
| 5 | 错误跨页切割点落在轮中段等边角 | 分页切片按条数不按轮 |

对会话 `d7c3cb54-dd90-4f3f-bbf6-e7898c425cff` 当前 800 条转录做启发式仿真（与前端同判据）：**166 个轮段落中 10 个不含任何成功徽章、内含 26 张工具卡——这些轮刷新后必然裸露**（例：seg[159..164] 内 Bash/Read/Delegate(引擎修复·neblink 上行背压) 三卡；seg[742..746] 内 AgentControl/TaskSearch/Mail 三卡）。作者截图里的「✓ Delegate(任务：Stand 知识图谱…)—Delegate task started in the background (task #995)…」整卡，与 `buildHistoryRow` tool 分支的静态卡形态（`✓ icon + label — summary`）逐形吻合。

补充事实：thinking 行在回放侧**已经**恒折叠（2026-10-20 重连折叠持久批，`tContent.style.display='none'` 无条件）——所以作者看到的是「思考过程 chip 折叠着，但工具卡摊着」的混合态，恰证折叠机制在、折叠判据缺。

### 1.2 证据（关键路径 + 行号）

- `src/main/resources/web/js/turnGroup.js`
  - :126-131 `isTuckableRow`：仅 `.tool` 与 `.thinking-row` 可藏；
  - :344-353 live 终态注释「pre-tucked by the render path … tuckRows here is idempotent insurance」——live 无条件折；
  - :541-562 `summarizeSegment`：`:548-549` E4 P0「success = some Ai row carries a done-badge … `if (!badge) return []` // failed/unfinished segment: flat, no header」——**根因判定点**；
  - :564-574 `findDoneBadge`：只认 `dataset.nfPhrase`。
- `src/main/resources/web/js/persistence.js`
  - `buildHistoryRow` ai 分支：`if (m.durationMs != null && m.durationMs > 0) createDurationBadgeElement(...)` 且整个包在 `if (m.text)` 内——空 text 终态行连徽章带 phrase 一起消失；
  - :958/:1467/:1570 三条恢复腿都收口到 `buildTurnSummariesForHistory`（启发式单点，四腿共享同一缺陷）。
- `src/main/resources/web/js/chat/durationBadges.js`
  - :65 注释自证「plain footers deliberately carry NO data-nf-phrase so turnGroup.findDoneBadge never mistakes them」；:126 `badge.dataset.nfPhrase = pickThinkingPhrase(...)`。
- `src/main/scala/nebflow/shared/protocol.scala:354-361`
  - `UiMessage.Ai(text, durationMs, model, thinking, timestamp)`——**落盘无 terminal/round 归属字段**；phrase 本就不落盘（live 专用元数据）。`.ui.json` 实读：`dur=230669 phrase=None`。
- 截图卡文本源：`DelegateTool.scala:835` / `ExternalSessionRunner.scala:192`「Delegate task started in the background (task #N)」→ 以 `UiMessage.Tool(content=...)` 落盘 → 回放 tool 分支原样渲出。
- 相关既有修复（边界确认）：`8a6c94000` 重连折叠持久批只治了 thinking 恒折叠与幂等重放，**没有**改 E4 徽章判据——WS 断线重连与整页刷新共用同一缺口。

### 1.3 修复设计案（待作者批，不在本批实施）

**目标**：回放折叠与 live 同判——「轮次结束」这一事实由数据承载，不靠渲染期徽章反推。

**方案 A（根治，数据面，推荐）**：给终态 ai 条目落显式轮终态标记。
- 后端：`UiMessage.Ai` 增加可选字段（建议 `terminal: Option[Boolean]`，沿用 `answerOf`/`quote` 的「缺席即不落键」兼容先例，protocol.scala :354）；在 `WebSocketRoutes` 轮终态落盘点（:2715-2739 / :2855-2879 两处 durationMs 计算点）一并写入——durationMs 有值的同批写 `terminal=Some(true)`。
- 前端：`buildHistoryRow` 对 `terminal` 行**无条件**渲染带 `nf-phrase` 的徽章（含空 text 行：渲染一条无气泡的纯徽章行或把徽章并入相邻行）；`summarizeSegment` 的 done 判据改为「段内存在 terminal 标记行」（徽章继续只做展示元数据载体）。存量旧转录无标记 ⇒ 回落现启发式（渐进兼容，不一次性改写历史）。
- 覆盖面：裸面 1/2/5 全消；裸面 3（失败轮）维持平铺（排障语义，作者 09-05 裁定）；裸面 4（busyTail）维持 open——该面由 live 终态收口，属瞬态。

**方案 B（快改，纯渲染面，1 文件级）**：`buildHistoryRow` 把徽章渲染从 `if (m.text)` 里提出来——凡 `durationMs>0` 即出徽章行；并放宽 `findDoneBadge` 接受「带 `data-ts` 的时长徽章」作为弱成功判据（时长徽章=终态产物，plain copy footer 依旧不认，不会误折失败轮）。零后端改动，当天可落地，治裸面 1/2 的大头。

**回放折叠形态建议**（两案通用）：与 live 同形——turn-header 摘要条常驻 + 工具/思考行 tucked，点 header 展开；不引入第二种折叠形态。Delegate 类工具卡（含「task started in the background」正文）在回放折叠后与 live 一致地藏进轮内，展开可见。

**验收口径**：对 d7c3cb54 转录重放，26 张裸卡归零；刷新后任意已完轮段落必有 header（失败轮除外）。

---

## 案二：无感压缩是否按「乒乓结构」在跑

### 2.1 ① 今天 10:26 横幅走的是哪条路径 —— **影子腿三连败回落同步压缩**（硬证据齐全）

引擎日志（`~/.stand/logs/stand.log`，会话 d7c3cb54-dd90-4f3f-bbf6-e7898c425cff，Nebula 根会话）逐条：

```
09:42:11.321 event=pp-fork    detail=T=337 est=246558 threshold=256000 shadow=ppc-d7c3cb54-…-337 queued=false
09:48:17.770 event=pp-failed  detail=err=All providers failed: zhipu/glm-5.3-flash: Unknown ×2, kimi/kimi-for-coding: Unknown ×2… failures=1/3
10:02:06.060 event=pp-fork    detail=T=349 est=257767 threshold=256000 shadow=…-349
10:05:57.961 event=pp-failed  detail=err=pingpong summary missing <summary>…</summary> pair (output truncated at the hard cap?) failures=2/3
10:13:17.651 event=pp-fork    detail=T=354 est=266006 threshold=256000 shadow=…-354
10:18:24.019 event=pp-failed  detail=err=All providers failed: zhipu/glm-5.3-flash: Unknown, zhipu/glm-5.3-flash: RateLimit, kimi/kimi-for-coding: Unknown ×2… failures=3/3 → next trigger falls back to legacy sync
10:21:47.898 event=pp-fallback detail=failures=3/3 → legacy sync compaction (§E-1)
10:21:47.903 event=compaction-window-start detail=phase=Compact
10:24:55.896 event=compaction-complete detail=before=359 after=4      ← 作者 10:26 看到的横幅数据源
```

旁证（磁盘）：压缩档案 `sessions/d7c3cb54…/compaction/20261028-102455-{before,after}.json`，after 头部为 `<context-compact mode="full" preservedRounds=2>` ——**legacy 同步全量压缩格式**（乒乓换入格式为「S 单条、无尾部保留」，可判别）；`pingpong/` 侧车目录存在且为空（三连败后 `PingpongStateFile.clear` 的落点，与 `AgentPingpong.handleShadowFailed` 一致）。

**判定**：乒乓影子腿**在跑且按设计在跑**——`pingpong.enabled=true`（nebflow.json 实读），门=根会话谓词（`AgentPingpong.gateOpen`），今天 3 次 fork 全部真实发起；三败全部是**影子腿的 LLM 调用/解析失败**，不是乒乓机制回归：
- 败 1/败 3：`All providers failed`——摘要调用沿供应商回落链（zhipu/glm-5.3-flash、kimi/kimi-for-coding）全灭，错误分类 `Unknown`（fallback.scala :296/:362 未分类异常——网络/代理/供应商侧拒绝类）+ 一次 `RateLimit`；同期主腿对 glm-5.3-flash 的调用是成功的（10:05:58/10:18:24 `message_start` 正常），提示与主腿差异在**巨 payload（est≈25-27 万 token 的 C₀′ 摘要请求）+ 当晨供应商不稳**的组合。
- 败 2：摘要产出缺 `<summary>…</summary>` 对（输出被硬帽截断的解析失败，`FullCompact.parsePingpongResponse`）。
- 三败触达 `circuitBreakerMax(3)` → 下个触发点 `pp-fallback` 走 legacy 同步压缩（`AgentSessionExecution`：`pp.ppFailures >= circuitBreakerMax` 分支，fallback 后计数归零）——与 10-19 裁定认定的同一回落形态**完全相同**，今天的横幅就是这条回落链的完成帧。

### 2.2 ② 10-19「进行中胶囊拆除批」落没落 —— **落了，三面俱在**

| 面 | 现状（代码证据） | 判定 |
|---|---|---|
| live「压缩中」胶囊 | `main.js compactStart` 处理器：帧保留、**不再起卡**（注释逐字「作者令 2026-10-09 10:23 ①（compaction-pill-remove 批）：『压缩中』可见指示已拆除」；提交 `5ce45a4cc`）；侧栏会话/文件夹行压缩指示已拆（`sidebar.js:2375-2382`，`compactingSessionIds` 仅剩输入闸/排队闸功能职责） | 已拆 |
| 重放「压缩中」胶囊 | `persistence.js restoreCompactCardRow`：`chat.compacting` 起始条目一律 `return null` 不渲染（孤儿/中断形态零渲染，09-15 F1 + 后续硬化） | 已拆 |
| 完成记录行 | **保留**：live `compactComplete → renderCompactDoneCard`（`.compact-card[data-state=done]`：「✓ 上下文已压缩：359 → 4 条消息」独立卡行）；落盘 `UiMessage.System(…, Some("chat.compacted"))`（WebSocketRoutes.scala:2947）；重放侧同卡重建（`restoreCompactCardRow` done 分支） | 按裁定保留 |
| 失败记录行 | `chat.compactFailed` → `.compact-card[data-state=error]`（live + 重放同形） | 保留 |

今天作者看到的横幅 = 第三行：**回落同步压缩的完成记录卡**——它正是 10-19 裁定「保留」的那个面，今天还在按 10-19 的设计渲染。

### 2.3 ③ 距「用户完全感知不到被压缩」还差哪几刀（设计案，待批）

先摆事实基线：**乒乓成功路径已经是零感知的**——`AgentPingpong` 全程不发 `CompactStart/CompactComplete/CompactFailed` 任何帧、不写 ui.json 记录（仅 pp-* 引擎日志 + compaction 档案）；今天所有可见面都来自**回落同步路径**。所以「完全无感」=两件事：把影子腿成功率做上去（回落尽量不发生）+ 把回落路径的可见面降级（发生了也别看见）。

**第一刀：影子腿可靠性（治回落的发生率）**
1. `pingpong.shadowModel`（规格 §H2 二期预留位）落地：摘要调用脱离主模型回落链，钉一个高可用摘要模型/专用链，避免与主腿共用「廉价快模型池」在高峰期一起翻车（今天两败的直接死因）。
2. 影子 fiber 内快速重试：单次 fork 的 LLM 调用先在链内重试 1-2 拍（跨供应商），失败才计 `ppFailures`；`circuitBreakerMax=3` 与 30s×2ⁿ 退避保持不变——等效把「三败」的容忍窗从 3 次跨轮失败放大到 6-9 次调用级失败。
3. 解析截断腿：`parsePingpongResponse` 失败（缺 `<summary>` 对）先走一次「续写/重问」小拍再判死（今天败 2 属此类）。
4. 触发提前量（pre-position）：fork 判据从「越过阈值」提前到「阈值 ×0.9 预 fork」（换入仍只在轮边界），给影子腿留出重试与退避的时间余量，避免三败后直接被 0.95 硬守卫逮住。

**第二刀：回落路径视觉静默（治回落的可见性）——含口径冲突，需作者裁决**
- 冲突点明示：10-19 裁定=回落时「进行中胶囊全拆、**完成记录行保留**」；今天口径=「完全感知不到被压缩」。两者在**完成记录卡**这一个面上直接冲突（今天横幅即该卡）。
- 推荐口径：**数据面保留、视觉面归零**——`chat.compacted` 记录照旧落 ui.json/引擎日志/压缩档案（审计与「留账」本意不丢），但 live `renderCompactDoneCard` 与重放 `restoreCompactCardRow` done 分支停渲染（或降为纯 log 级痕迹）。若作者要保留一丝可见性，折中形态：并入所在轮的 turn-header 统计尾注（「· 已压缩」一枚小 chip），不立独立卡。
- 同刀配套：`compactFailed` 错误卡同样静默化（引擎已有退避+熔断+应急兜底，UI 无需重复报错）；**唯一建议保留的可见例外**=0.95 硬守卫 emergencyClean 真正清了上下文（数据有损）时提示一次——这是「无感」的安全阀，避免用户在上下文被硬裁后无从解释质量变化。

**第三刀：验收口径定义**（建议随批定死）：「无感」= ①正常路径乒乓换入零帧零卡（已达成）；②回落同步压缩零卡零横幅（本设计）；③压缩全程仅引擎日志 + 档案可查；④仅 emergency 硬守卫触发时允许一次可见提示。

---

## 附：证据文件与位置

- 引擎日志：`~/.stand/logs/stand.log` L21700/L23277/L27023/L28033/L31975-31977/L33582/L34851-34853/L35804-35805（09:42-11:11 全时间线）。
- 压缩档案：`~/.stand/sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/compaction/20261028-102455-{before,after}.json`；pingpong 侧车目录（空）。
- 会话转录：`~/.stand/sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff.ui.json`（800 条滑动窗，读于 11:11-11:13；该窗随会话推进滑动，本报告统计为读时快照）。
- 前端：`web/js/turnGroup.js`、`web/js/persistence.js`、`web/js/chat/historyReplay.js`、`web/js/chat/durationBadges.js`、`web/js/main.js`（compactStart/compactComplete）、`web/js/sidebar.js`。
- 后端：`shared/protocol.scala`（UiMessage ADT）、`agent/AgentPingpong.scala`、`agent/AgentSessionExecution.scala`（maybeAutoCompact 乒乓门）、`agent/CompactionAbandon.scala`、`core/compact/{PingpongConfig,CompactThreshold,FullCompact,HistoryArchiver}.scala`、`gateway/WebSocketRoutes.scala`（帧与落盘）。
- 配置：`~/.stand/nebflow.json`（`pingpong.enabled=true`；zhipu/kimi 模型目录）。
