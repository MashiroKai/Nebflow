# delegate 结果气泡 UI 紧凑化（作者报障 2026-10-28 17:3x）

- 批次：delegate-nebflow-8a509c8d（纯前端，零引擎触碰；随下一波包生效）
- 报障原文：气泡读作「DELEGATE · NEBULA · NEBFLOW · COMPLETED」+ 右侧「12 files +908 −202」胶囊，整泡肥大。

## ① 信息提纯（执行器段）

**改动面**：`src/main/resources/web/js/chat/injectedRows.js`
- 新增 `delegateResultLabel(headerText, sender, eventType)`（injectedRows.js:109-117，判据表 :107）：把已渲染 delegate 头部（KIND · PROJECT · SUBJECT · STATE）重排为 `KIND · EXECUTOR · STATE`：
  - 内置执行器（sender = executor id `nebflow`，引擎 `ExecutorRegistry.DefaultId`）⇒ **一律 `INTERNAL`** ⇒ `DELEGATE · INTERNAL · COMPLETED`；
  - 外部执行器（`zcode` / `claude-code` / `codex` / `hermes`）⇒ 各自 id 全大写（同四段式排版），如 `DELEGATE · CLAUDE-CODE · COMPLETED`；
  - PROJECT 段不再进头部（会话/项目上下文已由气泡外 chrome 展示）。
- **只改显示文案组装，不删数据**：帧/落盘行上的 `header`、`sender`（executor id）、project 字段原样保留（`attachQuoteMenu` 的 senderName、持久化往返均未动——`evfmt-rc-header-persist` 3/3 绿证实）。
- **边界（收窄判据）**：仅 sender ∈ 已知 executor id 全集才重排。自定义 agent 委托（DelegateTool `agentName = agentDef.name`，如 `Explorer`）的 SUBJECT 段有信息量 ⇒ 逐字不动（PROJECT 段同保留）；sender 缺席（旧历史行/旧夹具）⇒ 逐字降级（`diff-chip-headline-shots` 的 `DELEGATE · stand · …` 夹具零漂移）。
- 应用点在 `buildInjectedRow`（injectedRows.js:287；live 帧 `main.js:2643` 与历史恢复 `persistence.js:637/:1029` 共用同一 builder）⇒ 三路渲染逐字同构。

## ② 尺寸回归（量化）

**肥大根因**（改前实测，1440 暗色）：
| 项 | 改前 | 改后 |
|---|---|---|
| 单行 delegate 泡高（带 chip） | **53.39px** | **35.50px** |
| 一行普通消息泡基线 | 42.47px | 42.47px（不动） |
| chip 高 | 23.39px（padding 3px + line-height 1.4 + 1px 双边，撑行） | **15.00px**（padding 1px 8px + line-height 1 + svg 11px） |
| label 行高 | 15.50px | 15.50px（不动，chip 不再撑行） |
| headline 残留 margin | 2px + 8px（折叠态也占位） | **0**（展开态间距移到 content margin-top 6px，`display:none` 时零贡献） |

**改动面**：`src/main/resources/web/css/diffView.css`
- `.nf-diff-chip`：padding 3px 10px → **1px 8px**、line-height 1.4 → **1**、gap 6→5、svg 12→11px（injectedRows builder 侧零几何改动）。
- `.nf-diff-headline`：margin 2px 0 8px → **0**；新增 `.nf-diff-headline + .injected-content { margin-top: 6px }`（builder 给 content 加类 `injected-content`，injectedRows.js:249-251）。

## 验收（spec 钉 + 回归）

**新 spec** `tests/delegate-bubble-compact.spec.mjs`（真 web 树 + 真 WS 桩；PHASE=before/after 双相）：
- **钉① 内置执行器文案 = INTERNAL**：`DELEGATE · INTERNAL · COMPLETED` 精确相等（1440/768/390 三档全绿）；外部 `DELEGATE · CLAUDE-CODE · COMPLETED`；自定义 agent `DELEGATE · NEBFLOW · EXPLORER · RESULT` 逐字；旧历史行逐字降级。
- **钉② 单行泡高 ≤ 一行基线 + 1px（量化断言）**：35.50 ≤ 42.47+1 ✓（三档）；折叠泡高 = 常规 padding(20) + 单行 header 行高(15.5) ±1 ✓（无徽章行独立占行/无残留 margin）；chip 高 15.00 ≤ label 15.50+1（不撑行）；展开自然生长（35.5→64.0；三行正文 108.9）；窄 320px 仍单行 + chip 本征宽零变形 + label 省略号。
- 验红：PHASE=before 14 FAIL（53.39px、chip 撑行、旧文案）留档 `evidence/…/readings.before.json`；after **28/28 PASS**（`readings.after.json`）。

**对照截图（暗色）**：`evidence/20261028_delegate-bubble-compact__delegate-nebflow-8a509c8d/shots/`
- `compare-before-after-dark.png`（前后对照合图）/ `before-2-internal-closeup-dark.png` vs `after-2-internal-closeup-dark.png`
- 全景与分档：`after-1-desktop-chat-dark.png`、`after-6-tablet768-dark.png`、`after-6-mobile390-dark.png`、`after-4-internal-expanded-dark.png`、`after-5-narrow320-dark.png`（另有 before-1/3 同位对照）。

**既有气泡面回归（零新增失败）**：
- `chat-diff-canvas.spec.mjs` **14/14**、`node-bubble-header.spec.mjs` **5/5**（同轮 14 passed）
- `bgagent-injected-bubbles.spec.mjs` + `badge-injected-turn.spec.mjs` **12/12**
- `evfmt-rc-header-persist.spec.mjs` **3/3**（持久化往返逐字）
- `diff-chip-headline-shots.mjs`（16 断言，dark+light）、`diff-chip-align-shots.mjs`、`diff-summary-split-shots.mjs`、`diff-filehead-capsule-shots.mjs` 全 **ALL PASS**
- `chat-autoscroll-follow.spec.mjs`（exit 0）、`remote-bg-agents.spec.mjs` ALL PASS
- 两处旧断言随批重钉（语义不变）：`chat-diff-canvas.spec.mjs:213` 与 `diff-chip-headline-shots.mjs:157/:186/:199` 的「行高=chip 高」代理 ⇒ 「行高=max(label,chip) 高」（chip 不再撑行后行高由 label 决定，单行语义不变）。
- 既有 shots 证据图随重跑刷新（20261022 三批 evidence png = 当前 UI 形态，同批 spec 全绿背书）。
- **存量失败 1（非本批）**：`tests/injected-intake-label.mjs` 改前即红——它从 `chat.js` 抽取 `INJECTED_SOURCE_LABELS`，而该表在 FE 组件化批（2026-09-27）已迁 `chat/injectedRows.js`；与本批无关，未修（超出裁定面，留待归属批）。

**checkJs**：`tsc -p jsconfig.json` 全量 260 错 = 基线 `tests/type-baseline.json` total 260（**零新增**）；触及 JS 文件 `injectedRows.js` 零错（spec/css 不在 include 面）。

## 假设与登记
- 「INTERNAL」取全大写与四段式全大写排版一致（label CSS text-transform:uppercase，视觉与 decree 示例逐字同形）。
- 外部执行器 id 全大写（CLAUDE-CODE 等）同四段式排版；executor id 集合硬编码于前端表（引擎 `ExecutorRegistry.defs` 全集：nebflow/zcode/claude-code/codex/hermes；hermes adapter-pending 亦收录，无帧源不渲染）。
- PROJECT 段对 executor 委托一律省略（作者示例 `DELEGATE · INTERNAL · COMPLETED` 无 project 段）；自定义 agent 委托保留四段式（超出 decree 面，信息量保留）。
- 未做：引擎侧 `NotificationHeader` 单一来源未动（纯前端重排在渲染面，帧/落盘数据原样）；生产实例（pid 70481/6138）零触碰。
