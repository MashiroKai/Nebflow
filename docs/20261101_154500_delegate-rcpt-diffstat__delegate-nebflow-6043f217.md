---
type: batch
status: done
related: "[[20261101_143000_stand-dev-methods-design-merge__delegate-nebflow-cc246cd7]]"
---

# delegate 完工卡恢复 diff 统计 + 版式重排（2026-11-01）

> 批令（作者 11-01 15:06，附实拍）：完工卡恢复 diff 统计标识并按版式四条重排——①恢复该批合并的代码量变化（+新增 −删除）；②归因+状态合成一颗胶囊芯片，diff 统计旁边单独一颗块，「｜」分隔；③+ 绿 / − 红（现役语义 token，双主题达标）；④块 hover 态（c29b35be7 批样式家族）。验收：两形态实拍、双主题 × 双引擎、零 console error。验证素材取自 [[20261101_143000_stand-dev-methods-design-merge__delegate-nebflow-cc246cd7]] 批的真实完工行与真实合并提交（spec 真载荷帧）。

## 1. 考古结论（diff 数去哪了）

作者猜的「归因行瘦身批（46664fab2e）把它裁掉了」**不成立**——该批只裁 turn 归因行的「思考 Ns」段（`turnGroup.js`/`workline.js`），与完工卡零交集。真实链路：

- 完工卡渲染构造（`| +x −y` 小注）**至今健在**：`injectedRows.js` delegate 分支（10-31 12:32 重设计批 → 14:14 语义色批 → 22:29 单行化+hover 批三批累进）。
- 数据链 = 终态 ExternalEvent `metadata.fileChanges`（`FileChangeReport.delegateBatchMetadata`：worktree git status 面 + FileChangeLedger 会话桶）→ 注入帧/`.ui.json` 行 → 卡面小注。**载荷缺席 ⇒ 无小注**（零改动批/旧历史行的既有口径）。
- 宿主 `.ui.json` 实证（root 会话 d7c3cb54…）：11-01 14:13/14:22/14:37 三批照常带 fileChanges（4/2/13 文件）；作者 15:05 实拍的卡 = **15:03 按钮设计系统包批**，该批载荷 fileChanges=NO——其交付落 data root 插件包（仓外），旧数据链两盲区暴露：**worktree 提交后 git status 面看不见已提交工作；Bash 侧写盘 ledger 记不到**。

## 2. 数据源换轴（选稳的）

作者令给的选项二选一，本批选**现算合并 commit diff stat**（更稳，修旧链盲区）：

- `DelegateWorkReport.mergeDiffStat(rec)`（新增）：锚定合并提交第一亲 diff——①finish 申报尾哈希（与卡片摘要哈希段同源同值）②回落基分支 tip（双亲 merge 才认，归属防护与 `changedReportFiles` 同判据）→ `git diff --numstat <p1>..<merge>` 加删行总和（binary/畸形行不计）。fail-soft：任一环不可读 = None，绝不拖垮终态投递。
- 时序安全性：消费点 = BackoffSupervisor 终态序列的 fileChanges 计算点——验收闸（MergeAcceptance）已确证、PostMergeCleanup 支/worktree 回收未开跑（delegateCleanup 在其后），两级锚此刻都可读。
- 装配（`BackoffSupervisor.terminalDiffMetadata`）：统计以 **`fileChanges.merge` 键内嵌**进既有 fileChanges 载荷——线形状纯增量（引擎不解释内部字段原样落盘原样下发，UiMessageFileChangesSpec 契约不破），消费面/diff 面板/历史行/邮箱往返**零改动**；载荷整体缺席而统计在场 ⇒ 合成最小载荷 `{merge}` 单键。三个调用点（completed×2 / failed）统一切换。

## 3. 卡面版式（作者令四条逐条）

| 条 | 落地 |
|---|---|
| ① 恢复 diff 统计 | 有统计的批出「+N −M」（合并统计优先，files 加删和回落）；零改动/无统计的批只有归因块（两形态） |
| ② 版式 | 归因+状态合成一颗胶囊芯片 `.nf-rcpt-pill`（textContent 逐字「DELEGATE · INTERNAL · COMPLETED」）；统计旁边单独胶囊 `.nf-rcpt-note`；中间「\|」字面分隔段 `.nf-rcpt-pipe`（aria-hidden）；22:29 批右缘贴卡口径退役 |
| ③ 颜色 | + 绿 / − 红 = `--status-success-icon` / `--status-error-icon`（ui-status-redesign 批双主题 ≥4.5:1 token 对；亮档实测 rgb(46,125,50)/rgb(214,69,69)，暗档 rgb(102,187,106)/…）；hover 不再褪成正文色（语义色恒驻） |
| ④ hover | 两块各走 c29b35be7 家族：行底 sapphire 0.08 + inset hairline（整条）之下，胶囊块 hover 0.14/0.18 + 描边微变；分块 hover（22:29 批的 per-seg hover）退役归块 |

- merge-only 最小载荷（files 空）⇒ 统计块为 span 静态色块（hover 在场、无点击面）；files 面在场 ⇒ button（diff 面板入口/aria-pressed/stopPropagation 语义逐字承继）。
- 摘要行收展开态、点开详情、展开间距：22:29 批语义零改动。

## 4. 改动面

- 后端：`DelegateWorkReport.scala`（+MergeDiffStat/mergeDiffStat/declaredMergeSha/resolveTwoParent/numstat）、`BackoffSupervisor.scala`（+mergeDiffStatJson/terminalDiffMetadata，3 调用点切换）、`AgentActor.scala`（fileChanges 帧字段文档补 merge 键口径）。
- 前端：`injectedRows.js`（delegate 分支 pill/pipe/note 重排）、`diffView.css`（完工卡段重写）。
- 测试：`tests/delegate-receipt-card.spec.mjs` 随批重钉（122 断言，全绿）；`tests/chat-diff-canvas.spec.mjs` T1/T12 构造断言重钉（9/9 绿）；`tests/delegate-note-color-shots.mjs` 构造断言重钉（13 全绿）。

## 5. 验证实证

- `delegate-receipt-card.spec.mjs`（PHASE=after）：**122 PASS / 0 FAIL / 零 console**——八帧（合并统计优先帧用真 13 文件载荷 + 真实合并提交 b6ceb9d0e 实数 **+445 −51**（files 和 +449 −51 同帧可辨优先级）；merge-only span 帧；files 回落帧；外部/自定义/merged/failed 帧；**真实 15:03 行**（作者实拍的无 diff 形态原文））× 桌面暗/亮 × 768/390 × **Chromium+WebKit 双引擎**。
- 验红（before）：HEAD 版旧 spec 对新代码 = 71 PASS / **5 FAIL**，红的全是本批刻意退役的钉面（零构造×2 + 分段 hover×3）——其余 71 条旧契约承继不破。
- 邻族：chat-diff-canvas **9/9**；note-color-shots **13 全绿**；bgagent-injected-bubbles / delegate-panorama 随大跑全绿（26 中 24 直绿 + 2 重钉后绿）。
- 后端：sbt Compile/compile + Test/compile 全绿；delegate 轨定向回归（UiMessageFileChangesSpec + core.delegate.* + agent.* + core.mail.*）**941 绿 / 12 红——12 红经 HEAD 原样树对照实证全为 main 存量、本批零回归**：①11 个 `waitUntil condition not met`（MergeReceiptNotifyChain R1/R2、DelegateBgWaitHold A-1/A-2/A-4、BgWaitCapLedger C1、DelegateRevival V1/V2、EmptyShellNotify ×3）在 git archive 提取的 pristine HEAD 树**逐条同样复现**；②SubAgentInboxMirror ③-2 `emitInjectedUserEvent(` 计数钉 14 实为 16——HEAD 与工作区逐文件计数相同（3+3+1+4+5），漂移系先前批（邮箱化腿加发射点）未重钉，非本批。
- checkJs：本批触及件零错（injectedRows.js 0；instanceof 收窄修掉 `note.type` 联合类型错）；总量 362 全为 HEAD 存量（modelPanel/ws/main/scheduled-task 四文件 pristine 逐字节对照非本批），本批净 −1。
- 证据：`evidence/20261101_delegate-rcpt-diffstat__delegate-nebflow-6043f217/`（shots 14 张 + readings.after.json + before/ 验红留痕）。

## 6. 边界与申报

- 非合并轮 completed / failed / cancelled / 旧历史行：无合并锚 ⇒ 无统计键，卡面回落 files 和或单胶囊——**行为与旧版一致**，不追溯。
- 该批若真零改动（如纯 data-root 插件包批）：无 pipe 无统计块——「+0 −0」不显示（作者的无 diff 形态即此）。
- WebKit 腿首跑 TimeoutError 根因 = Safari bad-port 保护封 80 以下端口（localhost:1 在列）；mock 源换 8899（路由层拦截不发起真连接），spec 已注记。
- 已知非本批存量：checkJs 基线超量（main tip 四文件）、circular SCC、上节 12 个 Scala 红（时序脆弱用例 + 发射点计数钉漂移）——均 pristine 对照留待各自主批。
