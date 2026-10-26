# REPORT — 收口批 B：邮件感知 r3 落地（设计与验收收口）

- 批次：`20261027_054745_mail-perception-r3-land`（分支 `delegate/delegate-nebflow-1caa7b0f`）
- 任务令：作者 2026-10-25 已明批「r3 落地」；本批 = 合并 r3 设计入 main + r3 裁决落地核验/补齐 + 回归 + 截图证据。

## 0. 现状更正（任务令前提修正）

任务令称「至今零实现 commit」——核实为**陈旧信息**：r3 落地的主体实施已于 2026-10-25 16:36 以 `183c375c2`（实施批·邮件感知功能落地：感知守护全栈 + 三脸前端 + 官网色单源）入 main，后续 `127ff2c59`（P0 boot 修复 + 4 回归钉）、`47cef9cf8`（canvas 六件 + boot 脱链加固）相继落地。**真正欠账的是设计侧**：`design/mail-perception-r2`/`r3` 两支（设计稿 + 样张三件套 + 18 实截）从未并入 main。本批把它补上，并对落地代码做 r3 裁决符合性核验与收口补齐。

## 1. 本批改动（三件）

### 1.1 r2+r3 设计稿入 main

`e2951b171` merge design/mail-perception-r3 —— `reports/20261025_115023_mail-perception-design-r2/`（r2 规格书 §1-§16）+ `reports/20261025_141024_mail-perception-design-r3/`（r3 四裁决落实 + 样张三件套 + 18 实截 + shots.mjs）入 main。零生产码。

### 1.2 r3 裁决符合性补齐（生产码，3 文件）

核验基线 = r3 REPORT §一 四裁决 + 样张三件套，逐条对照 `perception.js`/`perception.css`/两 locale（核验结论见 §2）。补齐两处偏差：

| 文件 | 改动 | 依据 |
|---|---|---|
| `web/js/locales/zh-CN.js` + `en.js` | ① `footerPrivacy`「邮件只在本机处理」→「本地处理 · 邮件全文不出本机」（样张页脚逐字）② `backoffTitle/Desc` →「网络不稳定 / 正在自动恢复，新邮件先存本机，恢复后即刻补上」（r3 异常态人话口径：无次数无倒计时）③ `quietLine`「免打扰时段不提醒」→「新邮件自动交给代理处理，广告与垃圾已被过滤。」（样张价值说明逐字）④ `setupDesc` → 两步即成口径（裁决 1 首启到可用 = 2 步） | 裁决 1/2 + 样张 |
| `web/js/perception.js` | 成功回执口径从「已发送 · 时间 · Message-ID」补齐为 **时间 + 收件人数 + 附件数 + Message-ID**（`showReceipt` 增 `doc` 参数，快照 doc 经 `loadedDoc` 闭包传入；doc 缺席时段级省略） | 裁决 4 引申「成功回执精简为 时间+收件人+附件数+Message-ID」 |

### 1.3 落地面实截证据（本目录 12 张）

`shots.spec.mjs`（Playwright，独立 `pw.config.mjs` 不进 tests 收集面）驱动 **tests/fixtures/mail-perception/harness.html = REAL perception.js + 真 canvas.js**（fetch 全量 mock，WS 帧单点投递）——截的是落地代码真实渲染，非设计样张。`npx playwright test --config reports/20261027_054745_mail-perception-r3-land/pw.config.mjs` 复跑，8/8 绿。

| 证据 | 面 | r3 锚点 |
|---|---|---|
| shot-panel-run-light/dark | 面板条目化展开：身份行 + 启停 + 价值说明 + [邮箱设置]；零统计零协议词；页脚「本地处理 · 邮件全文不出本机」 | 裁决 2+3 |
| shot-panel-collapsed-light | 条目单行摘要（i@kaiyu.me · 已连接） | §9.1 |
| shot-panel-setup-light | 未配置 hero → 两步直达 | 裁决 1 |
| shot-panel-error-auth-light | 授权失效：重新连接 / 移除邮箱 | 裁决 2 |
| shot-panel-error-conn-light | 网络不稳定：只说人话给动作，无次数无倒计时 | 裁决 2 |
| shot-panel-config-light / -adv | 两问配置卡 + 高级折叠（默认收起/展开态） | 裁决 1 |
| shot-otp-chip-light / -filled | 等待零痕迹；码到出片；点击填入撤出 | 裁决 2 |
| shot-snapshot-light / -receipt | 快照卡 + 回执「已发送 · 时间 · 收件人 2 · 附件 1 · Message-ID」 | 裁决 4 |
| shot-canvas-light / dark | 正牌邮件编辑器：收件人/抄送/密送 chips + 大字主题 + 排版工具栏 + 富文本正文 + 附件 chip + [放弃][发送] 玻璃胶囊 | 裁决 4 |

## 2. r3 四裁决符合性核验结论（落地代码 vs 设计）

| 裁决 | 结论 | 证据 |
|---|---|---|
| 1 配置两问极简 | ✅ 符合（183c375c2 已落；主面输入恰好 2 个、高级折叠默认收起、连接=测试+保存一体） | spec P2 + shot-panel-config-* |
| 2 过程态全隐 | ✅ 符合（OTP 等待零 UI、码到出片、点击填入撤出、过期零 UI；面板无运行态/倒计时/统计） | spec O1/O2 + shot-otp-* |
| 3 零统计 | ✅ 符合（面板无统计格/事件日志/页脚计数；后端 summary 无计数投影） | spec P1 + shot-panel-run-* |
| 4 Canvas 正牌邮件编辑器 | ✅ 符合（四件套 + chips + 工具栏 + 附件 chip；快照卡一致性守则 409⇒stale） | spec E1/S2 + shot-canvas-* / shot-snapshot-* |

**两处有意偏离样张（保留实施批裁决，非本批引入）**：
1. 码片数字显示为掩码 `····` 而非样张的明文大号数字——r3 REPORT §6.4 明示二选一「码值随点击后由引擎拉取一次性令牌填充」，实施批取点击后拉取（WS 帧不含码值），spec O1 钉 `codeLeak=false`。安全性取强侧。
2. 样张 HTML 异常态有 [立即重试] 按钮——后端无对应端点，r3 REPORT §二 异常态规格表亦未列该动作（无次数无倒计时无转轮询），不落死按钮。

## 3. 回归证据

| 项 | 命令 | 结果 |
|---|---|---|
| **P0 boot 钉（127ff2c59）** | `sbt "testOnly nebflow.perception.PerceptionCoreSpec"` | ✅ **34/34 绿**；4 条 boot 回归钉全绿（sweepLoop/spoolLoop 构造期求值不炸栈 / fiber 可启动取消 / 跨 sleep 边界推进）；`PerceptionService.scala:157-173` IO.defer 修复在位未回退 |
| 前端三脸钉 | `npx playwright test tests/mail-perception.spec.mjs` | ✅ **12/12 绿**（面板/单码片/快照卡/鉴权伞/Canvas 编辑器，含本批回执口径改动后 S1 仍绿） |
| 前端 build | `node scripts/build-web.mjs` | ✅ OK |
| checkJs 门 | `node scripts/check-js-types.mjs` | ✅ PASS（257 < 基线 260） |
| 实截套件 | `npx playwright test --config reports/…/pw.config.mjs` | ✅ 8/8 绿（12 张 PNG 入本目录） |

引擎零触碰：不重启、不动传输层；sbt/playwright 均为只读验证面（临时 127.0.0.1 端口静态服务 + 隔离浏览器上下文）。

## 4. 假设与残留

- 台账 #859（落地实施 open）的收口以本批报告为凭；台账本体在会话外，未直接改写。
- mock 证据里 toggle 呈 off 态：harness 的 entries mock 未带 `enabled` 字段（真后端 payload 有），仅为证据脚本取数省略，非产品缺陷。
- 残余清单沿用 183c375c2 REPORT §5（附件发送/多邮箱 UI/IMAP IDLE/L1 腿等）不变，本批未扩大范围。
