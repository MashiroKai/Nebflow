# 工具过程收起保留中间文字 · QA 独立验收报告

- 日期：2026-09-03
- 验收人：qa-frontend（独立验收，不信任上游自测，全部证据自行生成）
- 对象：分支 `collapse-keep-text`（worktree `.nebflow/worktrees/collapse-keep-text`），上游 commit `d99de687` + `e50ec989`，基线 main@7710190c
- 开工时 main HEAD：`2fadf6f6`（QC worktree 保留名守卫；sendbtn 链在 sendbtn-green worktree，与本分支 input.css/chat.js 域无交集）
- 验收口径：作者 2026-09-03 19:05 裁定——收起对象 = 工具调用块 + 工具结果 + 注入事件；LLM 全部文字回复一律原位原序保留；turn 级收起/闭合语义不变（含 08-26 裁定）

---

## 逐项验收表

| # | 验收项 | 判定 | 证据摘录 |
|---|---|---|---|
| 1 | 独立重放核心场景 | **PASS** | 自建 fixture（`tests/fixtures/qa-verify-tmp.html`，真实模块零桩）+ 自建 spec（`tests/qa-verify-tmp.spec.mjs`，9 用例 9 passed / 2.5s，验后已删）。S1：`文字A→工具1→文字B→工具2→最终`，collapseTurn 后断言顶层序列 `user, ai(A), group, ai(B), group, ai(最终)`；三段文字 `aiVisible=[true,true,true]` 且 compareDocumentPosition 有序；两组 `.turn-steps` 内 `.bubble.ai`=0、`.bubble.injected`=0、仅 `.row.tool`×2；展开组1后工具仍居 A/B 之间（回放保真）；summary 各含「✻ 独立验收短语 · test-model · 工具 1 次」 |
| 2 | 交替形态视觉（暗亮双主题） | **PASS** | 自采截图（非上游证据）：`/Users/dev/.nebflow/docs/Nebflow/20260903_collapse-keep-text-verify-dark.png`、`...-light.png`。目检：文字→[✻ 折叠头]→文字→[✻ 折叠头]→文字 交替渲染，无断裂/错位/空隙；summary 色≠背景色断言暗亮双过（dark: `rgb(...)`≠`rgb(...)`，light 同） |
| 3 | 边界回归独立复跑 | **PASS** | ① 上游 spec 复跑 `turn-collapse-keep-text.spec.mjs` + `history-replay-cards.spec.mjs` → **10 passed (4.5s)** 全绿（busyTail badge-less 尾段平铺/failed 组、ungroupTail 重终态治愈、E5 纯文字 0 组、E6 思考独占可见、badge 后注入开新组均在其中）。② 自建独立场景：S3 活体 08-26 裁定（组数 2→3、前两组 innerHTML 逐字节不变、四段文字全可见）；S4 ungroupTail（done 后 failTurn 全部 run 组溶解重收 failed、无 summary、恒展开、文字原位）；S5a busyTail=true（无 badge 尾段平铺、closed 前段正常收）；S5b busyTail=false（尾段 failed 组、无 summary、展开）；S5c 历史 ≥3 轮注入（3 条注入+2 工具全进折叠区按序、badge 后注入开新段 failed 组）；S2b 文字分隔注入 run（注入1+工具1→文字D→注入2+工具2 = 2 组，注入按序） |
| 4 | 改动面审查 | **PASS** | `git diff 7710190c..HEAD --name-only` 仅 3 文件：`turnGroup.js`（+119/-38 含注释）、`tests/fixtures/turn-collapse/harness.html`（新增）、`tests/turn-collapse-keep-text.spec.mjs`（新增）。逐 hunk 过：`isProcessRow`→`isCollapsibleRow`（ai 文字显式 false）+ `collapsibleRuns`（文字作分隔符切连续 run）+ `buildGroup` steps=run + `collapseTurn`/`failTurn`/`groupSegment` 逐 run 建组。未动 WS 链/数据结构/会话存储/chat.js/persistence/scala；`turnScope`/`markClosed`/`markClosedAt`/`ungroupTail`/`findDoneBadge`/`expandGroupContaining` 零改动。返回值语义单组→最后一组：核实调用方 main.js 4 处均为语句调用不接收返回值、chatSearch.js 仅用未改动的 expandGroupContaining——无行为影响 |
| 5 | check-js-types + i18n sweep | **PASS** | 分支 `node scripts/check-js-types.mjs` 输出与基线 7710190c（临时 worktree 检出实跑）**逐字节一致**（diff 空）：total 343 > baseline 326 为 main@7710190c 存量漂移（flowAnim/flowMapTab/micOrb/orbSettingsUI/chat.js TS2339），**本批零新增**；`grep turnGroup` 输出 0 命中——turnGroup.js 自身 0 错误。无新增文案（fillSummary 复用既有键 `chat.turnSummaryTools(One)`）；`scripts/` 下无 verify-i18n-sweep 脚本，i18n sweep 不适用 |
| 6 | 既有失败归因核对 | **PASS** | ① bgagent-dedupe 复跑 1 failed（`进行中Team0sFrontend · …` vs 期望 `running Frontend · …`，bgAgentPopup 下拉 i18n 文案断言）——**基线 7710190c 临时 worktree 实跑同样 1 failed 同用例**，归因做实：既有失败，与本批无关。② smoke×5 / askuser 家族需真实后端（spec 头注明 `sbt run --port 8094`，`ERR_CONNECTION_REFUSED`）——本批纯前端纪律禁跑 sbt，历史归因，未复跑 |

## 验收过程说明

- 独立验证 spec 首跑 7 用例 6 过 2 失败（S2/S5c），归因为**验收人自建场景的断言设计错误**（注入与工具之间无文字分隔时应为 1 个连续 run，我误设 2 组期望；S5c 段1 同理）——非产品缺陷。修正断言并补「文字分隔多 run」形态（S2b）后重跑 9/9 全绿。产品代码零改动，无 [verify-fix] commit。
- 临时验收文件（fixture + spec）验后已删除，分支工作区干净（`git status` 空），分支 HEAD 保持 `e50ec989`。
- Playwright 全程一次成功未重试；静态服务由 spec 内 node:http 起（127.0.0.1:8101，进程随测试结束退出）；基线对比用临时 `git worktree add /tmp/qa-baseline-wt(2) 7710190c`，用毕 remove。
- 宿主 8080 / PID 87216 全程未触碰；未跑 sbt；未合并、未 push。

## 产物与 commit

- 独立截图（~/.nebflow commit **aef85b2**）：
  - `/Users/dev/.nebflow/docs/Nebflow/20260903_collapse-keep-text-verify-dark.png`
  - `/Users/dev/.nebflow/docs/Nebflow/20260903_collapse-keep-text-verify-light.png`
- [verify-fix] commit：**无**（产品代码无问题需修）
- 报告路径：`/Users/dev/.nebflow/docs/Nebflow/20260903_collapse-keep-text-verify-report.md`

## 回归结论

收起范围修正（折叠集合只收工具块/工具结果/注入事件，LLM 文字一律原位保留）在活体流、历史重建、busyTail、ungroupTail、08-26 闭合裁定、多轮注入、暗亮双主题七个面上独立复核全部通过；既有机制零回归，改动面无越界，check-js-types 零新增。

## 转述上游实施要点

- **根因一句话**：「收起最后一条回复之前的所有内容」是 `isAgentRow()` 把 ai 文字行归为过程行 + `findFinalRow()` 只豁免最后一条文字这对判定的涌现结果，并非显式规则。
- **改动面一句话**：仅 `turnGroup.js`——`isProcessRow` 拆为 `isCollapsibleRow`（注入/工具/card-content/思考，ai 文字显式 false）+ `collapsibleRuns`（连续可折叠段切 run、文字作分隔符原位保留），`collapseTurn`/`failTurn`/`groupSegment` 逐 run 建组逐组落收起状态，机制函数与 WS/存储链零改动。

---

**最终判定：PASS（可合并）**
