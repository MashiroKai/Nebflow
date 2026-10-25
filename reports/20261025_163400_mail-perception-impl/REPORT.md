# REPORT — 实施批：邮件感知功能落地（mail-perception-impl）

- 批次：`20261025_163400_mail-perception-impl`（分支 `delegate/delegate-nebflow-9f913ccb`）
- 作者令（2026-10-25 15:0x 逐字）：「r3 可以 风格以及 canvas编辑的功能再强一点 对标真正的邮件系统的编辑器 现在用的风格还不是官网stand的配色风格 不用我确认了 直接执行」——两阶段豁免，直接实施。
- 设计基线：`design/mail-perception-r3`（r2 REPORT §4.5/§6/§7/§9/§11/§13/§16 + r3 四裁决 + mockup 三件）
- 加强项落实：①Canvas 编辑器对标真邮箱（收件人/抄送/密送 chips、主题行、排版工具栏、引用上下文、附件只读展示层）②配色 = 官网 stand 色单源 token（mystand.space 实抓）

---

## 1. 交付面总览

### 1.1 后端（感知域包 `src/main/scala/nebflow/perception/`，8 文件全绿）

| 文件 | 职责 |
|---|---|
| `MailboxPresets.scala` | 17 家域预设（QQ/163/126/Gmail/Outlook/iCloud…）两问自动识别 + `domainOf` 归一 |
| `OtpExtractor.scala` | 验证码提取：意图词（中英）门 + 4-8 位数字段 + 3 位分组「993 121」段 + 混合段；年份形排除 |
| `BillingClassifier.scala` | L0 启发式账单分类（L1 LLM 腿本批占位） |
| `DraftCodec.scala` | 草稿落盘编解码（front-matter + HTML 正文；引用块剥自尾部的往返契约） |
| `PerceptionModel.scala` | 域模型（EntryState 八态/四色投影、MailAccount、OtpWindow 单码槽、DraftRecord、SendFailure 语义失败枚举） |
| `PerceptionStore.scala` | `dataRoot/perception/` 存储（0600 纪律：entries/drafts.json/drafts//spool/billing/audit.jsonl） |
| `MailClient.scala` | IMAP 取信（RFC822 浅解析 + RFC2047 + multipart 深取）+ SMTP 发送（alternative 正文 + B 编码主题 + 点填充 + 隐式 TLS） |
| `PerceptionService.scala` / `PerceptionCore.scala` | 守护轮询（15s 窗内 / 60s 常态自适应）、OTP 窗口单码槽状态机、草稿注册表与快照一致性守则、审计、投递信封（`AgentCommand.ImmediateInput` 注入主代理，先例 = DeviceMailInbox） |

接线三处：`registry.scala`（`"EmailSend" -> PerceptionMailTool`，AskUserQuestion 行后）、`RestApiRoutes.scala:93`（`PerceptionRoutes.routes(ctx)`，SecretsRoutes 后）、`GatewayMain.scala`（`perceptionBoot` 链入 `*> memoryRecallDaemon *> perceptionBoot *> succeedPortGate`）。

REST 契约（`/api/perception` 前缀，全部鉴权后挂载）：`GET entries` / `POST mail.connect` / `POST mail.toggle` / `POST mail.remove` / `GET mail.preset?address=` / `POST otp.window.open` / `GET otp.consume` / `GET|PUT mail.drafts/:id` / `POST mail.drafts/:id/abandon` / `POST mail.send`。

WS 帧（出）：`perception.state.changed{entries}` / `perception.otp.code{windowId,requestId,from,fromDomain,receivedAt}`（**无码值**）/ `perception.otp.expired{requestId}` / `perception.draft.sent{draftId,messageId,sentAt}` / `perception.draft.updated{draftId,...}`。

安全纪律（沿用 r2 §11）：授权码只进 SecretsStore（`secrets/perception-<addr>`，0600）；OTP 码只存进程内存单码槽、一次性消费；audit.jsonl 不落全文/明码；快照卡发送 `snapshot=true` 时严格执行 `mtime > snapshotAt ⇒ 409 Stale`。

### 1.2 前端（三脸 + 官网 token 单源）

| 文件 | 职责 |
|---|---|
| `web/css/stand-tokens.css`（新） | 官网 token 单源 `--st-*`（亮/暗/`prefers-color-scheme` 三轨，`--nfd-*` 逐值镜像，零自拟） |
| `web/css/perception.css`（新） | 三脸样式（全部消费 `--st-*`；无 emoji） |
| `web/js/perception.js`（新） | 主模块：面板 / OTP 单码片 / 快照卡 / Canvas 邮件编辑器；`routePerceptionFrame` 单点帧路由；`answerAsk` 与 chat.js 卡面同帧形 |
| `web/js/chat.js`（改） | `renderAskUser` 内单一可选钩子 `window.__perceptionEnhanceAskCard`（renderAskAttachments 之后；perception.js 未装载 = 逐字节现状） |
| `web/js/main.js`（改） | `initPerception()` 挂 `initDaemons()` 后 |
| `web/index.html`（改） | secrets.css 后挂 stand-tokens.css + perception.css |
| `locales/zh-CN.js` / `en.js`（改） | `perception.*` 77 键（已交叉核验：代码引用键全覆盖、双语齐平） |
| `vendor-globals.d.ts`（改） | `__perceptionEnhanceAskCard` 全局声明 |

### 1.3 测试

- 后端：`src/test/scala/nebflow/perception/PerceptionCoreSpec.scala` — 30 测试（预设/OTP 提取与误报排除/账单分类/草稿往返与容错/MIME 组装与点填充/RFC822+multipart 深取/toIncomingMail/存储 0600/单码槽状态机/四色投影/stale 一致性/附件拒绝/abandon）。
- 前端：`tests/mail-perception.spec.mjs` — 7 钉（面板零统计/预留位两枚/两问配置卡/OTP 三钉/快照卡两钉/Canvas 编辑器），harness = `tests/fixtures/mail-perception/harness.html`（真模块 + fetch mock + 帧单点投递 + WS 捕获桩；127.0.0.1 临时端口）。

---

## 2. 色板对照表（加强项②：v3 样张自拟值 → 官网实抓值）

来源：mystand.space 主页 + `/_next/static/chunks/05v9w7~p~o9n_.css`（`.nfd` 块 + `html[data-theme=dark] .nfd`），2026-10-25 走代理抓取，逐值转录进 `stand-tokens.css`（`--nfd-*` → `--st-*` 镜像）。

| token | v3 样张（自拟） | 官网实抓（本批落定） | 归因 |
|---|---|---|---|
| primary（品牌绿） | `#31c48d`（近似 guess） | **`#07c160`** | 样张凭记忆；官网 WeChat 系品牌绿 |
| 玻璃亮档 glass-bg | `rgba(255,255,255,.72)` | **`#ffffff8c`**（= rgba(255,255,255,.55)） | 样张偏白 |
| 玻璃暗档 glass-bg | `rgba(24,28,38,.72)`（近似） | **`#181c26ad`**（≈ rgba(24,28,38,.68)） | 微差，官网 alpha 略低 |
| blur | `28px` | **`24px`** | 样张偏大 |
| 玻璃描边（亮） | `rgba(255,255,255,.45)` | **`#ffffff59`**（.35） | 收敛 |
| bg 亮 | `#f7f8fa` | **`#f5f6f8`** | 微差 |
| bg 暗 | `#15181f` | **`#13161c`** | 微差 |
| surface 暗 | `#1e222b` | **`#1c1f27`** | 微差 |
| sapphire（玻璃结构色） | 未成体系（散落 rgba） | **`rgb(91 127 191)`** + glow `rgb(120 160 220)`，refraction 亮 `/.45` 暗 `/.22` | 官网玻璃 tint 全值齐 |
| control 族（亮） | 未定义 | bg `#ffffff73` / hover `#ffffffa6` / border `#fff6` / highlight `#ffffff80` / underedge `#00000014` / glow `#0000000f` / shadow `#0000000a` / blur `10px` | 官网控件材质全族收录 |
| control 族（暗） | 未定义 | bg `#ffffff0d` / hover `#ffffff1a` / border+highlight `#ffffff14` / underedge `#00000040` / glow `#0003` / shadow `#00000026` | 同上 |
| 文字（亮/暗） | 近似 | `#1b1e26` / `#e8eaed`（muted `#8b8e96` / `#7b7e88`） | 收敛 |
| amber | 无 | 亮 `255 152 0` / 暗 `255 168 60` | warn 载体备用 |
| bubble-user | 无 | `#95ec69` | 语义备用 |

规则：感知域新面**只**消费 `--st-*`；本文件不覆盖任何既有变量名 ⇒ 存量组件零影响。

## 3. 九假设处置（r3 REPORT §假设 → 本批落地）

1. 授权码配置卡直存 secrets（store 零读值）→ **照做**（`mail.connect` 写 `secrets/perception-<addr>` 0600）。
2. 未配置主按钮 = 连接邮箱 → **照做**（占位条目 + 两问配置卡）。
3. 过期零 UI → **照做**（`otp.expired` 仅静默撤片，spec O2 钉死零残留零弹层）。
4. 域表 17 家 → **照做**（MailboxPresets，spec 覆盖 QQ/163/outlook/Gmail/icloud 形态）。
5. 信任发件人 = 高级折叠；LLM/账单/OTP 窗口零 UI 锁定 → **照做**（配置卡 `<details class="pp-adv">` 默认收起，spec P2 钉「主面输入恰好 2 个」）。
6. 预留位 2 个（文件/日历）→ **照做**（`reservedEntryHtml`，spec P1 钉「恰好两枚 + 名字」）。
7. 审计入口移除 → **照做**（面板无任何审计/统计行，spec P1 钉零统计）。
8. 回执精简 → **照做**（`已发送 · 时间 · message-id` 单行，快照卡 + 画布双路由一致）。
9. Canvas 载体 = 平台壳 + 专用组件（`openTab` 建 pane，草稿文件走 REST 读写）→ **照做**（`openMailEditor`；spec E1 经真 `canvas.openTab` 开标签验证）。

## 4. 验证证据

| 项 | 命令 | 结果 |
|---|---|---|
| 后端编译 | `sbt compile` | ✅ success（全量，无 warning 级新增） |
| 后端 spec | `sbt "testOnly nebflow.perception.PerceptionCoreSpec"` | ✅ **30/30 绿** |
| 前端 build | `node scripts/build-web.mjs` | ✅ OK（183 sources，orphans 0） |
| checkJs 门 | `node scripts/check-js-types.mjs` | ✅ PASS（260 = 基线 260；**perception.js 0 错**） |
| checkJs 对照实验 | `git archive ab81fbc02` 纯净树复跑 tsc | 纯净树 = 260 错（nearpath 3 错在其中）⇒ 本批零净新增；基线按脚本内 2026-09-27 先例 re-anchor 257→260 |
| 前端 spec | `npx playwright test tests/mail-perception.spec.mjs` | ✅ **7/7 绿** |
| locale 键交叉核验 | 代码引用键 ↔ 两 locale 表比对 | ✅ 全覆盖（含补 `perception.abandoned`） |
| 既有族回归 | askuser-dup-card / history-replay / badge-mirror / bubblename / expandfix | 28 绿 + 1 败（history-replay replay/live innerHTML 属性序漂移）——**主 checkout（无本批改动）同败**，对照实证为既有漂移非本批回归 |

截图证据（`.nebflow/evidence/20261025_mail-perception-impl/`，不入 repo）：三脸亮暗双轨 10 张（panel/config/otp/snapshot/editor × light/dark）+ spec 钉面 6 张。

## 5. 残余清单（边角，按优先级）

1. **附件发送**：后端 `sendDraft` 已语义拒绝（spec 钉「不静默丢弃」）；画布附件面只读展示，发送腿未开放。
2. **多邮箱 UI**：后端 entries 已多账户就绪；面板展开区动作位已留，多账户增删 UI 未做（本批单账户主链）。
3. **IMAP IDLE**：本批自适应轮询（15s 窗内 / 60s 常态）；IDLE 推送未做。
4. **L1 LLM 分类腿**：BillingClassifier L0 启发式占位；L1 腿待接。
5. **快照卡历史重放换肤**：重放的 ask 卡按 id 增强钩子已通，但历史重放腿的快照卡降级形态（无 fetch 上下文）未专项打磨。
6. **HTML 卡在 history replay 的降级形态**：同上族。
7. **移动端面**：三脸桌面优先；mobile bootstrap 未适配感知面。
8. **billing digest 时区细节**：日切边界暂按本机时区。
9. **既有漂移**（非本批）：history-replay-cards 的 replay/live innerHTML 属性序（`style`/`disabled` setAttribute 次序）漂移在 main 上已存在，建议另开小批收敛。

## 6. 假设声明（实施中自决的小决策）

- `answerAsk` 自答通道：chat.js 的 showOptions 回调为匿名内联（无法引用），perception.js 按同帧形自发自答（`askUserAnswer + answers + requestId` + `removePendingAsk` + session-attention 事件），不触达 chat.js 内部状态（broadcastAskState 为 chat.js 私有，跨窗口同步略过——单窗口语义完整）。
- OTP 片点击只填值不自动提交（与手输路径语义一致，用户仍点确认）——spec O1 钉死「无出站 askUserAnswer 帧」。
- 严格式 stale 判据（`mtime > snapshotAt`，无容差）：`createDraft` 的 snapshotAt 取落盘后文件 mtime（基线与文件状态严格对齐）；r2 §7 契约逐字落地。
- `routePerceptionFrame` 单点帧路由 + `openPerceptionPanel` / `openPerceptionConfig` 导出：为静态 harness 直驱面，行为与 WS/入口腿逐字节一致。

## 7. 文件清单

新增：`src/main/scala/nebflow/perception/`（8 文件）、`src/main/scala/nebflow/gateway/PerceptionRoutes.scala`、`src/main/scala/nebflow/core/tools/PerceptionMailTool.scala`、`src/test/scala/nebflow/perception/PerceptionCoreSpec.scala`、`web/css/stand-tokens.css`、`web/css/perception.css`、`web/js/perception.js`、`tests/mail-perception.spec.mjs`、`tests/fixtures/mail-perception/harness.html`、本报告。

修改：`registry.scala`、`RestApiRoutes.scala`、`GatewayMain.scala`、`index.html`、`main.js`、`chat.js`（12 行钩子）、`locales/zh-CN.js`、`locales/en.js`、`vendor-globals.d.ts`、`tests/type-baseline.json`（re-anchor 257→260，对照实验背书）。
