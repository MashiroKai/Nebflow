# delegate 完工通知卡重设计批 — 交付报告

- 批令：作者 2026-10-31 12:32「这个delegate完成通知优化一下 那个diff胶囊很丑 你来决定吧」（设计决定已下，本批落地）
- 链：delegate-nebflow-670cc1e1
- 改动面：完工通知渲染组件（delegate 回执气泡）——**投递内容本身零改动**（引擎/帧/落盘字段全不动）

## 设计决定（作者批，逐字落地）

1. diff 胶囊（改动文件清单 chip `.nf-diff-chip`）**整个移除**；
2. 首行 = 批次短名 + 判词（completed/merged/failed **单色徽标，墨族语言**）；
3. 次行 = 一句话结果摘要（有 merge 给 merge commit 短哈希，无给失败原因一行）；
4. 文件改动数（+x/−y）保留为一行**灰字小注**，不做 chip 不做胶囊；
5. 整体对齐现行气泡/通知设计语言（圆角/留白/暗档），**双主题**。

## 实现（前端单点，`injectedRows.buildInjectedRow` 的 `source==='delegate'` 分支）

- **卡头行**（`.nf-rcpt-head`）：批次短名 `.nf-rcpt-name`（10px/600/uppercase、sapphire 0.85——
  与 `.injected-source-label` 同族语言；口径承继 2026-10-28 信息提纯批：内置执行器→INTERNAL、
  外部执行器→各自 id 全大写、自定义 agent/sender 缺席→header 去尾段判词逐字保留）+
  判词单色徽标 `.nf-rcpt-badge`（hairline 边框 + `--color-text-muted`，**零填充零色相**——
  completed/merged/failed 同色，判词差语义全在字面；completed 不绿、failed 不红）。
- **判词推导**：`eventType` 词表（`nodeStatusLabel` 面，含 cancelled→CANCELED 单 L 历史口径）；
  `merged` = completed × payload 含 `[delegate-finish] merge status:` 行（合并轮工作结果附页
  行标，`DelegateWorkReport` 面文案——帧面无结构化 merge 结论字段，这是客户端唯一可靠判据）。
- **摘要行**（`.nf-rcpt-summary`）：merged ⇒ merge status 行正文 + 短哈希独立右缘 mono 段
  （7 位 hex；**尾段括号包裹哈希剥出防双显**，串中哈希不另立段；哈希段不随省略号截断——
  作者令「给 merge commit 短哈希」的硬保证）；其余 ⇒ 首条非空且非 `[delegate-judge]`/
  `[delegate-finish]` 注记行（completed=模型自写摘要首行；failed/cancelled=失败原因首行）。
  一行 CSS 省略号截断。
- **灰字小注**（`.nf-rcpt-note`）：`+a −r` 单行，mono 11px `--color-text-muted`，
  **零背景零边框零 padding**（非 chip 非胶囊的构造性形态）；点击唤起 diff 面板的入口
  承位（行为与旧胶囊同：左栏 #panel-filechanges + Canvas 阅读面 + toggle 收起；
  `data-fc-batch`/`aria-pressed` 激活态同步保留，`diffPanel.syncChipActive` 选择器随迁）。
- **折叠语义**：卡头/摘要行（`.nf-rcpt-top`）点击展开正文，默认折叠（承继 08-23 裁定）。
- 非 delegate 源 ⇒ 通用 label 路径逐字节不动；schedule 专属卡分支零触碰。

## 验证

- **新 spec** `tests/delegate-receipt-card.spec.mjs`（48 断言）：A1 短名口径六帧 / A2 墨族单色
  （completed==failed 同色 computed 断言）+ merged 推导 / A3 摘要+短哈希 / A4 小注形态与计数 /
  A5 零 chip/headline / A6 折叠语义 / A7 几何（纵列不重叠、徽标贴行右缘、窄 200px 省略号）/
  A8 暗亮双档结构同构 + **console/pageerror 零新增**。
  - `PHASE=after` ALL PASS；`PHASE=before`（pristine HEAD 源码）**27 FAIL 验红实证**。
  - 双端截图：暗/亮 × 桌面 1440/平板 768/移动 390 + 特写 + 窄面板，10 张进
    `evidence/20261031_delegate-receipt-card__delegate-nebflow-670cc1e1/shots/`。
- **链路回归** `chat-diff-canvas.spec.mjs` 9/9（T1/T2/T12 随批重钉卡面形态；T3–T11、T13 面板/
  阅读面断言面零改动全绿）。
- **邻族**：`bgagent-injected-bubbles` 9/9（T5 delegate fixture 改钉新契约：卡名=header 去判词段
  + 判词徽标；probe 增 `rcptNames/rcptBadges`）；`node-bubble-header` 5/5；
  `badge-injected-turn` 3/3；`injected-intake-label` 随上跑通过。
- **证据脚本**：`diff-summary-split-shots` 全 PASS（暗/亮，小注承位点击链）；
  `diff-filehead-capsule-shots` 桌面暗/亮 18/18 PASS。
- **门**：checkJs 350=基线 350 零新增；build 201 sources orphans 0；check-circular 输出与
  pristine HEAD 逐字节同（见存量清单）。

## 退役与存量（如实登记）

- 随胶囊设计退役：`tests/diff-chip-align-shots.mjs`、`tests/diff-chip-headline-shots.mjs`、
  `tests/delegate-bubble-compact.spec.mjs`（10-28 紧凑化批的 label 串行+chip 同线契约被本批
  设计决定取代，spec 换代为本批新 spec）。
- **存量失败（与 pristine HEAD 对照实证，非本批引入）**：
  ① `diff-filehead-capsule-shots.mjs` 移动腿（mf-cards-live 超时）——HEAD 源码复现同失败；
  ② `check-circular.mjs` GATE FAIL（SCC[3] popArtifacts/reference/msgQuote + SCC[2]
  mobile/navController↔taskPanel）——HEAD 输出逐字节相同。建议另立批处置。

## 关键取舍（作者「你来决定吧」的受托决定）

1. 小注**保留点击入口**（灰字但可点，hover 升主文字色）——纯文本化会丢失 10-22 批建的
   diff 面板唤起链，决定保留功能、只杀胶囊形态。
2. 短哈希独立成段而非埋在摘要文本里——长 status 被省略号截断时哈希恒可见（作者令硬判据），
   尾段括号哈希剥出防「hash 双显」。
3. 徽标右缘对齐（卡头 flex，短名弹性省略号）——卡面「标题+状态」语言，窄面板徽标本征宽零变形。
4. 判词全单色墨族（含 failed 不红）——作者令「墨族语言」按字面执行，色差语义零引入。
5. 批次短名沿用 10-28 提纯口径（INTERNAL/各 id），不新造命名。
