---
type: batch
status: done
related: "[[20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56]]"
---

# Pop 交付卡补 footer 信息条批（2026-11-01）

> 批令（作者 11-01 16:19，原话铸牌投递时已展开）：会话里的文件卡片补 footer 信息条——**agent Pop 出来的文件卡片**和**作者自己发送的文件**，要有和文件/图片消息同款的 footer 信息条（时间 + 下载 + 引用，徽章家族样式）。同一徽章家族、同一数据链（能引用的可引用、可下载的可下载、时间照走现役三腿），视觉逐字同族不另起炉灶；图片类已有等价信息条则对齐差异，不双份堆叠。验收：Pop 文件卡 / 用户上传文件 / 普通文件消息三类卡片同框实拍（footer 齐整同族），深浅双主题 × 双引擎，下载/引用真实断言，零 console error。前批基线 = 手机端长按退役批（6053ba021，过程档 `reports/20261101_043400_mobile-longpress-retire__delegate-nebflow-e190ec0f.md`）给用户文件/图片消息补的同一族 footer；其裁定谱系见 [[20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56]]。

## 1. 考古结论（缺口的真实边界）

作者令里两类面，改前实况一缺一不缺：

- **① agent Pop 交付卡（`.row.pop-artifact`，LLM 侧）＝ 真缺口**：`buildPopArtifactRow`（popArtifacts.js）只装配卡片组 + 失败路径 chip——零 footer、零时间档、零下载、零引用、零 msgId（本批 PHASE=before 对 pristine HEAD 取证 **58 红**逐条坐实，六格引擎×主题全同）。live（chat.js renderTool）与两条重放腿（persistence.js restoreFromStorage / restoreFromBackendHistory）三处调用点同缺。
- **② 用户上传附件（`.row.user-media`）＝ 已有同款**：6053ba021 长按退役批已给 `buildUserMediaRow` 补齐 footer 三件套（时间/下载/引用）。本批不改其行为，spec 保真重钉实证（六格 PASS + 真下载字节落盘）——作者手机端若见缺 footer，系运行实例资源早于 12:38 合并构建，代码面无缺口。
- 桌面端现状如实申报：footer 家族本就是**共享聊天面**（`durationBadges.createMsgFooterBadge` 全站单点，桌面文本气泡/工具行早已在用），不存在「桌面无家族」——补齐即两端同惠（手机层 375 视口 + 桌面 1280 视口同框各存证）。

## 2. 改动面（全部复用现役件，零第二套形态）

- `popArtifacts.js`：
  - **装配单点提取** `buildMediaRowFooterBadge(items, ts)`：时间档 + 下载键（单件行宁缺毋假；字节两腿 path/内联 data: 皆缺不挂键）——`buildUserMediaRow` 原地切换调用（行为逐字节同），`buildPopArtifactRow` 同一装配器（零第二套装配）。
  - `buildPopArtifactRow(payload, opts)` 增 `opts.ts`（0/缺席 ⇒ 无时间档防御门）：行挂**自己的** msgId（live `nextChatMsgId()`，重放腿由调用方覆写 `historyMsgId(i)+'-pop'`——引用寻址落卡行本体，不与同轮工具行撞号）+ `attachQuoteMenu` 同一挂点（描述符 = 首项 kind/文件名/path，**全量 items 随带** ⇒ 引用块缩略带与用户媒体行同构；senderRole 'ai'/senderName rootDisplayName()）。引用钮由 `mountQuoteFooterButton` rAF 收敛进徽章——与全站引用钮同一条收敛链。
  - 新增 import `rootDisplayName`（rootName.js 叶子模块，零环；check-circular SCC 集与 pristine 逐字节同）。
- `chat.js` renderTool：live 腿传 `{ ts: Date.now() }`（到达时刻，与 tagQuotableRow 同刻度）。
- `persistence.js` 两条重放腿：传 `{ ts: m.timestamp }`（footer 时间档与消息行同源三腿）+ 覆写 msgId。
- `chat.css` 一条新规则：`.row.pop-artifact:not(.user) { flex-direction: column; align-items: flex-start; }`——LLM Pop 行转列向让徽章落卡下左对齐（AI 行族同侧）。改前该行唯一子元素是 `.pop-group`，行/列向视觉逐字等价，零既有面迁移；`:not(.user)` 闸保用户行（`.row.user` 的 column+flex-end）不触。

## 3. 验证实证

- `tests/pop-footer-family.spec.mjs`（新建，独立 node 脚本，双引擎显式）：**PHASE=after 107 断言 ALL PASS / 零 console**——
  - T1 三类卡片同框（用户上传文件行 / Pop 多件行 / Pop 文件卡行 / Pop 图片行）：`.duration-badge` 同族齐、data-ts 三腿同值（live≈now / 重放=m.timestamp）、下载键档位（多件行零下载=宁缺毋假）、引用钮齐、msgId 齐；手机 375×812 × **chromium/webkit × dark/light** 四格 + 桌面 1280×900 dark 双引擎存证，同框实拍 6 张。
  - T2 下载真实断言：Pop 文件卡/图片/用户行三键各真落盘（suggestedFilename 逐字 + 字节与夹具同长 + `.copied` 绿勾反馈采样）；字节走 nf-ticket 铸票 + nf-file 票链（ticketGuard 无票拒答，机制本身在测）。
  - T3 引用真实断言：Pop 行 footer 引用钮 → 引用 chip 入条（`.att-ref[data-ref-type=chat-message]`）→ 发送 → 乐观气泡引用块（摘要 `[文件] quarterly-report.pdf` + `.msg-quote-atts` 文件 chip）→ 点块跳转原 Pop 行（`.quote-target-flash` 1.8s 高亮命中）。
  - T4 live 腿：mock ws 真推 `toolEnd` 帧（客户端→mock 回显服务器帧）→ renderTool 原位直出 Pop 行（时间档 drift<15s、下载/引用/msgId 齐备、下载真落盘）。
- 验红（PHASE=before，**git archive pristine HEAD 树**实跑）：58 红，全部落在本批新钉面（Pop 行零徽章/零时间档/零下载/零引用/零 msgId ×六格 + live 腿）；用户行 6053ba021 既有面六格全 PASS（保真重钉基线）。
- 邻族回归全绿：pop-artifacts **22** / pop-media-alignment **16**（CSS 列向规则无回归）/ pop-stack-fan **7** / attach-bubble-unify **13** / quote-message **16** / pop-image-single-fetch **4** / pop-media-index **10** / mobile-v3+mobile-popup-retire **13** / mobile-msg-longpress **7**（用户 footer 三腿原钉不破）/ imagegen-blocking **4**（Pop live 直出路径）。msgmenu **1 红 = pristine HEAD 同样复现**（S6 mic orb 计数 ×2，main 存量，非本批）。
- 静态闸：checkJs 365 错与 pristine HEAD **逐字节同集**（ws/modelPanel/mobile-navController/main/scheduled-task 五项 main 既有欠账，本批触及件零新增）；check-circular SCC 两条与 pristine 全同（零新增环）；check-msgstyle-single-source PASS；esbuild 生产 bundle 构建 PASS（202 sources，orphans 0）。零 Scala 改动（sbt 面零触）。

## 4. 边界与申报

- 范围口径（作者令第 3 条）：以消息流所在端为准——家族在共享聊天 DOM 上，两端同惠；未扩任何面板/弹窗面（bgAgentPopup 的 Pop 行走同一 renderTool/buildPopArtifactRow 路径，自动随批受益，无专门改面）。
- Pop 载荷无 `attachmentsSaved` 补丁腿（items 恒带 path，PopTool 契约）⇒ 时间档两腿（live/重放）；用户媒体行的补丁腿三腿口径不受本批影响。
- legacy Pop 行（pre-pop-upgrade 历史的彩虹名卡）不补 footer——与改前逐字一致（重放退化面，不追溯）。
- spec 双引擎腿两处 harness 级发现（已注记在 spec 内）：① **WebKit 的 SW fetch 管道绕过 Playwright page.route 拦截**（chromium 可拦）——SW 控页后铸票/nf-file 请求打穿 mock 直奔真网 ⇒ harness 用 swReg 既有逃生门（`stand_sw='0'`）+ register 桩双保险关闸（sw.js 是温访问秒开缓存，零功能依赖）；② WebKit 以 pageerror 浮出「ResizeObserver loop completed」浏览器级帧预算信号（app 内 attachStripFold/askDock 等 RO 链既有形态，非 app 异常）——spec NOISE 过滤并注明。
- 已知非本批存量（pristine 对照留待各自主批）：checkJs 基线超量（main tip 五文件）、circular SCC 两条、msgmenu S6 mic orb 计数。
- 证据：`evidence/20261101_165332_pop-footer-family__delegate-nebflow-a4d10fbb/`（after 六格 shots + readings）与 `evidence/20261101_163000_pop-footer-family-before__delegate-nebflow-a4d10fbb/`（验红六格 shots + readings.before.json，58 红）。
