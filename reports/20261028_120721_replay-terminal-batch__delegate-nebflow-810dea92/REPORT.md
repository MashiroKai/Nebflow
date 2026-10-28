# 回放折叠「A 根治」实施批——REPORT

- 授权：作者拍板（2026-10-28 11:44，选 A 根治）。设计依据：`reports/20261028_113024_console-replay-compaction-audit__delegate-nebflow-485b188a/REPORT.md` 案一（§1.3 方案 A）。
- 分支：`delegate/delegate-nebflow-810dea92`（工作树 `.nebflow/worktrees/delegate-nebflow-810dea92`）。在产引擎（pid 70481 / gateway 6138）全程未触碰——纯代码批，新行为随下一波包生效。
- 后端编译：`sbt compile` 641 Scala sources 全绿（exit 0，2026-10-28 12:02）。

---

## 1. 改动面（与设计逐条对应）

### ① 后端：`UiMessage.Ai` 增可选 `terminal` 标记（缺席即不落键）

- `src/main/scala/nebflow/shared/protocol.scala`
  - `case class Ai` 新增 `terminal: Option[Boolean] = None`（:354 一带），doc 注明判据语义（失败轮/进行中轮不写标记）。
  - Encoder `case m: Ai`：仅 `terminal.contains(true)` 时落 `terminal:true` 键——旧行字节形态逐字不变（intake/header/answerOf/quote/fileChanges/answerAttachments 先例同款条件编码）。
  - Decoder `case "ai"`：`terminal <- cursor.downField("terminal").as[Option[Boolean]]`，旧行无键 ⇒ None，宽松透传。
- 写入点两处（`src/main/scala/nebflow/gateway/WebSocketRoutes.scala`）：
  - `done`（:2707 一带）：append 分支 `UiMessage.Ai(..., terminal = Some(true))`（与 durationMs 同批）；空 text 缓冲的回填分支 `updateLastAiMeta(..., markTerminal = true)`（该分支本就 `durationMs.isDefined` 门控——「durationMs 有值的同批写」与设计逐字对应）。
  - `agentDone`（:2843 一带）：append 分支同上；回填分支无条件 `markTerminal = true`（轮已终态是事实，时长缺失只影响徽章形态不影响折叠判据；该分支本就不受 durationMs 门控，注释已有说明）。
- `src/main/scala/nebflow/core/SessionStore.scala` `updateLastAiMeta` 新增 `markTerminal: Boolean = false` 尾参：true 时同一回填批把 `terminal=Some(true)` 盖到末条 Ai 行，**绝不清除**既有标记（二次回填只加不减）。缺省 false ⇒ 形态不变；现役调用面只有 done/agentDone 两处（`SessionRecorder.scala` 的镜像实现无调用方=死代码，未动，注释登记）。

### ② 前端回放：done 判据数据面优先 + 旧转录回落

- `src/main/resources/web/js/turnGroup.js`
  - `summarizeSegment`：done 判据改为「段内存在 `data-nf-turn-terminal` 行」（新 helper `findTerminalRow`）**优先**，无标记回落现 `findDoneBadge`（nf-phrase 徽章）启发式——存量历史渐进兼容，不一次性改写。徽章继续只做展示元数据载体（phrase/model 进 header）；terminal 行无徽章时 header 缺 phrase 段照样成立，模型名回落行标 `data-nf-model`。
  - `exchangeComplete`（busyTail 完成判据）：terminal 行与 nf-phrase 徽章同权（数据面驱动尾段闭合）。
  - `buildTurnSummariesForHistory` 注入腿重开判据：`findDoneBadge || findTerminalRow`（与 summarizeSegment 同口径）。
  - 模块头注释同步改写（「Zero protocol changes/E4 heuristic」已过时）。
- `src/main/resources/web/js/persistence.js`（两条渲染腿同款——`restoreFromStorage` ai 分支 + `buildHistoryRow` ai 分支，四条回放腿单源）：
  - 行渲染条件 `m.text || isTurnTerminal || hasDuration`；徽章从 `if (m.text)` 提出：凡 `durationMs>0` 一律渲染（text 为空 ⇒ **无气泡纯徽章行**——审计裸面①根治，旧转录同享此修）；terminal 行盖 `dataset.nfTurnTerminal='1'`（+`dataset.nfModel` 模型回落）。
  - 旧行（无 terminal、无 dur、有 text）走原路径逐字节同形；quote 菜单/msgId 仍只挂有 text 行。

### ③ 顺带修独立 bug（任务面 3）

即 ②的「徽章出 `if (m.text)`」半面：对**旧引擎写出的存量转录**（无 terminal 字段），「有 durationMs 但 text 为空」的纯工具轮徽章不再整个消失——spec `B1` 面对基线树红、本树绿（见 §3）。

### ④ live 缓存腿补数（四腿同判的闭环）

- `src/main/resources/web/js/main.js` `done` 处理器三个 saveMsg 落盘点（非激活会话 flush :1273、激活会话 `finishAi` data :1310、thinking-only :1321）盖 `terminal: true`——localStorage 恢复腿与后端历史腿同判据（`sanitizeForCache` 展开保留任意字段，无需白名单改动）。`textDone` 等中间 flush 点**不盖**（与后端中间 flush 不写 terminal 同批语义）。
- 纯工具轮（无 text 无 thinking）live 缓存腿不写终态条目（现状即无 ai 条目，不新增条目形态）——该腿此面维持启发式，后端历史（source of truth）已带 terminal；残余面见 §5。

## 2. 形态红线核对（任务面 4）

- 与 live 同形：回放折叠仍走 `summarizeSegment` → 同一枚 `.turn-header`（常驻 + `data-turn-state=done` + 点击 toggle）+ `tuckRows` 原位压藏，无第二套折叠形态——spec `X` 面钉「点 header 展开 → 工具卡原位可见 → 再点收起复原」。
- 失败轮平铺：error 帧不走 done 落盘点 ⇒ 无 terminal 无徽章 ⇒ 平铺——spec `F` 面守卫（两树同绿）。
- busyTail 尾段 open：无标记尾段维持展开——spec `BT` 面守卫（两树同绿）；有 terminal 的尾段按数据面闭合（`BT2` 新面，基线树红）。

## 3. 验收证据（任务面 5）

### 3.1 DOM 级 spec：`tests/turn-terminal-replay.spec.mjs`（新增，10 面）

真实 `restoreFromBackendHistory` + `turnGroup` 链路（无 stub），harness `tests/fixtures/turn-terminal-replay/harness.html`。双向跑法（reconnect-collapse 同款约定）：

- **绿腿（本树）：10 passed**。
- **红腿（main 导出基线树 + `PIN_EXPECT=red`）：10 passed**——四枚新面钉（B1 纯工具轮徽章·legacy / B2·terminal / BT2 busyTail+terminal 闭合 / M2 空 text 无 dur header 成立）在基线树证实在场缺陷、在本树证实缺席；六枚守卫钉（T2 旧转录回落 / F 失败轮平铺 / BT 尾段 open / T1 / M / X）两树同绿。
- 命令：`node <main-repo>/node_modules/@playwright/test/cli.js test tests/turn-terminal-replay.spec.mjs`（红腿加 `NB_UX_ROOT=/tmp/... PIN_EXPECT=red`，spec 头注有完整跑法）。

### 3.2 同判据仿真：`sim-replay-fold.mjs`（本报告目录，输出 `sim-output.json`）

对作者会话 `d7c3cb54-dd90-4f3f-bbf6-e7898c425cff` 的 `.ui.json` 快照（只读；12:01 读时 800 条滑动窗——**窗已随会话推进滑动**，与 11:11 审计快照不同窗）按前端同判据分三相重跑：

| 相位 | 判据 | 轮段落 | 裸段 | 裸工具卡 |
|---|---|---|---|---|
| A 现状回放（旧渲染器×旧数据） | 徽章裹在 `if (m.text)` 内 | 158 | 6 | 21 |
| B 新渲染器×旧数据（本批前端随包即可回收面） | durationMs>0 即渲染徽章 | 159 | 6 | 19 |
| C 新渲染器×新写者仿真（terminal 随 done 落盘；快照时刻在飞的尾段除外） | 徽章 ∪ terminal 行 | 159 | **1** | **1** |

- 审计时点（11:11 窗）为 166 段/10 裸/26 卡；本窗（12:01）同判据为 158/6/21——同一缺陷家族，窗口滑动所致数字漂移，方向与量级一致。
- C 相位唯一残余 1 段 1 卡 = **段内零 ai 行**的退化全工具轮（实测：user 行 + 单张 `Mail(→delegate…)` 工具卡，无任何 text/thinking ⇒ done 落盘点无 Ai 载体可盖 terminal，回填落到上一轮末条 Ai 行——幂等但本段无标记）。设计内平铺面（失败轮、busyTail 尾段）本窗内 0（尾段自带徽章闭合）。
- 面分布：6 个裸段全部为「ai 行只有 thinking-only 且全段无 durationMs>0」形态（审计裸面②），C 相位全部由 terminal 标记闭合；「durationMs>0 但 text 空」的 2 条存量行（审计裸面①）B 相位即回收。

### 3.3 既有 spec（含 thinking 折叠 10-20 批）

- 全绿（本树，2026-10-28 12:1x-12:5x，本机 playwright）：
  - `reconnect-collapse`（10-20 重连折叠持久批）+ `turn-collapse-keep-text` + `turn-single-badge` + `turn-header-persistent` + `turn-activity-stream` + `badge-injected-turn` —— **32 passed**；
  - `askuser-badge-conditional` + `askuser-lockline-history` + `chat-history-perf` + `askuser-dup-card` —— 全绿；
  - 第二轮扫（`quote-message` / `search-realshape` / `busy-lifecycle` / `send-bubble-independence` / `queue-idle-return` / `queue-one-per-turn` / `f1-send-queue` / askuser 家族 4 件）—— **39 passed**。
- 三处非绿，逐一证实**与本批无关**（各有 pristine 基点树对照/机制证据）：
  1. `history-replay-cards` replay-vs-live parity 腿：对本分支基点 pristine 树（merge-base 全量导出 + NB_UX_ROOT 指入）跑同 spec 同腿**同形失败**（`.option-confirm`/textarea 的 `disabled=""`/`style=""` 属性序列化次序差异——字节相等钉对 chromium 版本脆）。本批 diff 对 askUser/option 渲染路径零触碰（diff 全文 grep 0 命中）。
  2. `search-realshape` T1/T2/T3：`#search-btn` 点击 180s 超时（app 顶栏搜索键不可见——壳层面）。对 pristine 基点树跑同 spec：**同 3 面、同失败点**（openSearchDesktop :122）——预先存在的环境性失败，与本批无关。
  3. `askuser-refresh-survive` T1：`ECONNREFUSED 127.0.0.1:18923`——playwright.config.mjs `instanceBacked` 名单在册（:42，需隔离 home + mock-LLM 夹具 runner + 活实例），自足环境按项目自身 CI 约定不可跑。
- 附：生产 bundle 构建 `npm run build` 全绿（188 js 源全图入包、orphans 0）——本批改动无模块级破损。

## 4. 基础面核对

- 兼容纪律：旧 `.ui.json` 行解码/编码逐字不变（条件编码 + 宽松解码）；旧转录回放走启发式回落（spec T2 守卫）；四条回放腿（historyPage 初载 / 上滑补页 / localStorage 恢复 / 刷新重放）收敛于 `restoreFromBackendHistory`/`restoreFromStorage` 两个同款改动点 + `mountHistoryPageChunked` 经 `buildHistoryRow` 单源自动继承。
- 在产引擎零接触：所有验证走静态 harness/仿真/编译，未向 :6138 发任何写请求；`~/.stand/sessions/*.ui.json` 只读。

## 5. 残余面（明示，不属本批验收口径）

1. **段内零 ai 行的退化全工具轮**（§3.2 C 相位唯一残余）：无 terminal 载体行，回放维持平铺。根治需给 `UiMessage.Tool` 或轮级容器落标记——超出本批设计面（案一只动 `UiMessage.Ai`），量级 1 段/1 卡。
2. **localStorage 腿的纯工具轮**：live 缓存不写终态 ai 条目（现状形态，不新增条目），该腿此面待 historyPage 初载以带 terminal 的后端历史整替。瞬态。
3. **startTime 未跟踪的 done**（网关重启等）：「done」回填分支维持原 `durationMs.isDefined` 门控，该退化轮回放回落启发式——与现状同，不回归。
4. done-then-error 同轮再终态：回放按 terminal/徽章折叠、live 溶解为平铺——既有分歧（徽章启发式时代即存在），未扩大。

## 6. 产物清单

- 代码：`protocol.scala` / `WebSocketRoutes.scala` / `SessionStore.scala` / `persistence.js` / `turnGroup.js` / `main.js`（git diff main 共 6 文件，+197/−532 中 −532 为分支基点落后 main 的无关面，本批实际净增约 200 行含注释）。
- 测试：`tests/turn-terminal-replay.spec.mjs` + `tests/fixtures/turn-terminal-replay/harness.html`（新增）。
- 仿真：`sim-replay-fold.mjs` + `sim-output.json`（本目录）。
