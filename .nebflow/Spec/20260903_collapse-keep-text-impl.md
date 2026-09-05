# 工具过程自动收起——保留 LLM 中间文字回复 · 实施报告

- 日期：2026-09-03
- 分支：`collapse-keep-text`（worktree `.nebflow/worktrees/collapse-keep-text`，基线 main@7710190c，开工已 merge main「已经是最新的」）
- 作者裁定：2026-09-03 19:05（收起范围修正：折叠集合只收工具块+工具结果+注入事件；assistant 文字一律原位保留）

---

## 一、现状盘点（代码行证据，改动前）

### 1. 收起判定所在环（根因）

收起分组/折叠渲染链全部在 `src/main/resources/web/js/turnGroup.js`（#346 引入，efa5e6a6 边界族 + #403 闭合裁定），无第二个实现点。

**「收起最后一条回复之前的所有内容」的判定由三环叠加构成：**

| 环节 | 位置（改动前行号） | 内容 |
|---|---|---|
| ① 过程行判定 | `turnGroup.js:47-53` `isAgentRow()` | 尾行 `return row.classList.contains('ai') && !!row.querySelector('.bubble.ai')` —— **中间 AI 文字行被归类为可折叠过程行** |
| ② 过程集合 | `turnGroup.js:57-61` `isProcessRow()` = 注入行 + `isAgentRow()`；`collapseTurn()` `scope.filter(isProcessRow)` | 文字A、文字B 全部进入 processRows |
| ③ 唯一豁免 | `turnGroup.js:73-81` `findFinalRow()`（最后一条非空 ai 行）+ `buildGroup()` `steps = processRows.filter(r => r !== finalRow)` | **只有最后一条文字豁免**，其余中间文字进 `stepsEl`，随 `built.steps.style.display='none'` 收进折叠 |

历史重建路径同构：`groupSegment()`（改动前 `turnGroup.js:330-359`）`seg.filter(isProcessRow)` + `findFinalRow(seg)`，刷新后重放同样把中间文字收进折叠。

### 2. 08-26 裁定（闭合后到达的外部事件开新组）位置确认

- 活体侧：`markClosed()`/`turnScope()`（closure cursor：child count + anchor 锚点自校验，`turnGroup.js:198-225`）；`collapseTurn()`/`failTurn()` 终态一律 `markClosed(chat)`，锚点之后的新内容 scope 从 cursor 起算 → 新组。
- 历史侧：`buildTurnGroupsForHistory()` `turnGroup.js:346`（改动前行号 312）——注入行到达时若当前段已含 done badge（`findDoneBadge`），`flush(i); segStart = i` 分段。
- 自愈：`ungroupTail()`（改动前 `turnGroup.js:271-282`）仅在「cursor 无效/无新增行」时溶解末尾全部 turn-group，同 turn 重终态（done 后 late error）重收一次。
- busyTail：`buildTurnGroupsForHistory` 尾段无 badge 不分组、cursor 落尾段首行（`markClosedAt`）。

### 3. 折叠区头尾渲染结构

- 折叠头 = `.turn-summary`（chevron `chat.js:2429 chevronSvg()` + `.turn-summary-text`，点击 toggle `chat.js:2407 bindCollapsibleToggle`）。
- 折叠体 = `.turn-steps`（收起时 `style.display='none'`；失败组无 summary、恒展开）。
- 样式：`css/chat.css:2170-2210`——`.turn-group { margin: 2px 0 }`，`.turn-summary` 静音文字形态、颜色 `var(--color-text-dim)`（#346 A13 双主题 4.5:1），主题经 `prefers-color-scheme`（`css/base.css:52`）自动切换。组间与文字行间距由各行自身 margin 承担，无专用头尾连接件。

---

## 二、根因结论

**把中间文字收进折叠的是「isAgentRow 把 ai 文字行算作过程行 + findFinalRow 只豁免最后一条」这一对判定**（上表①②③）。收起语义「最后一条回复之前的所有内容」并非显式写成一条规则，而是这两个函数组合的涌现结果。

---

## 三、改动清单（文件/函数级）

全部改动仅 `src/main/resources/web/js/turnGroup.js`（+71/-32，两笔 commit）+ 新增测试两件。**未动 WS 事件链 / 数据结构 / 会话存储 / chat.js / persistence.js / scala。**

| 函数 | 改动 |
|---|---|
| `isProcessRow()` | **删除**，拆为 ↓ |
| `isCollapsibleRow()`（新） | 注入行 / `row.tool` / `row.card-content` / `thinking-row` → true；**ai 文字行显式 false**（裁定核心） |
| `collapsibleRuns()`（新） | 把行序列按连续可折叠段切 run；文字行作分隔符原位保留 |
| `buildGroup()` | 签名 `(chat, run, finalRow, meta, withSummary)`；steps=run（去掉 finalRow 过滤，finalRow 仅用于 E6 豁免判定） |
| `collapseTurn()` | 逐 run 建组；`builtGroups` 收集后**逐组** fillSummary/`display='none'`/`turnState='done'`；viewport pin、`markClosed` 语义不变 |
| `failTurn()` | 同上逐 run、逐组 `turnState='failed'`（A5：无 summary） |
| `groupSegment()`（历史重建） | 逐 run 建组，badge/success/meta 判定保持段级不变 |
| 文件头注释 | 补 2026-09-03 裁定与 文字→[折叠]→文字 形态说明 |

不变项：`turnScope`/`markClosed`/`markClosedAt`/`ungroupTail`/`findDoneBadge`/`expandGroupContaining`、`busyTail` 语义、i18n 键（复用 `chat.turnSummaryTools(One)`，零新增文案）。

**新收起形态**：`文字A → [✻ 短语 · model · 工具1次] → 文字B → [✻ …] → 最终回复`——文字不进折叠、不挪位、顺序不变；每组摘要条统计**本组**工具数。

### commit

- `d99de687` 收起范围修正主 commit
- `e50ec989` 多 run 收起状态逐组落位修复 + 新 spec（8 用例）

---

## 四、设计要点：为何按连续段切多组

文字夹在工具之间时，若把全部工具并进一组（置于首个工具位），展开后工具会出现在其真实发生位置之前——破坏回放保真。按连续段切组后，折叠/展开两种状态下 DOM 顺序都等于真实事件顺序；折叠头（summary）+ 折叠体（steps）每自成一组，正好构成验收要求的「文字→[折叠头…折叠尾]→文字」交替形态。`ungroupTail` 循环溶解末尾**全部**组、历史重建按段重切，多组对既有机制天然兼容。

---

## 五、Spec 逐条结果（新 spec `tests/turn-collapse-keep-text.spec.mjs`，8/8 PASS）

真实模块驱动（chatView/chat/turnGroup/persistence 无桩），harness `tests/fixtures/turn-collapse/harness.html`，mock 数据形态对齐真实 ui.json（`durationMs` badge 只落在回合末条 ai 上）。

| # | 验收 | 用例 | 结果 |
|---|---|---|---|
| a | 交替形态 | 文字→工具→文字→注入→工具→最终：顶层序列 `user, ai(文字A), group:tool, ai(文字B), group:injected+tool, ai(最终)`；三段文字均可见且顺序正确；折叠区内 0 个 `.bubble.ai`；双组 `display=none`；摘要条各含「工具 1 次 · test-model」；展开组1后工具仍在文字A/文字B 之间（compareDocumentPosition 断言） | PASS |
| b | 注入在折叠区内 | 同上组2 = 注入+工具；历史路径中途注入入组、**badge 后注入开新段**（组3 纯注入） | PASS |
| b | 08-26 闭合回归 | 闭合后外部注入+新回合：组数 2→3，前两组 innerHTML 逐字节不变，四段文字全可见有序 | PASS |
| b/c | ungroupTail 治愈 | done 后 failTurn 重终态：全部 run 组溶解重收为 failed（无 summary、恒展开），文字全程原位可见 | PASS |
| c | busyTail | 无 badge 尾段不分组（`other:row tool` 平铺可见，cursor 落尾段首行）；busyTail=false 同尾段按 failed 组展开、无 summary；两组间 summary 计数 1 | PASS |
| c | E5/E6 | 纯文字回合 0 组；思考独占回合 thinking 行保持可见 | PASS |
| d | 暗主题截图 | `20260903_collapse-keep-text-dark.png`（断言 summary 色≠背景色后截图） | PASS |
| d | 亮主题截图 | `20260903_collapse-keep-text-light.png` | PASS |

运行：`npx playwright test tests/turn-collapse-keep-text.spec.mjs` → **8 passed (2.4s)**。

---

## 六、前端套件宽跑（--workers=1，逐文件显式跑）

| 批次 | 文件 | 结果 |
|---|---|---|
| 1 | askuser-answer-source / askuser-refresh-survive / askuser / history-replay-cards / paste-inline / node-bubble-header / reftag-redesign / project-agents-md | 27 passed；5 failed + 1 did not run 全部为 **askuser 家族需真实后端**（`BASE_URL=localhost:8094 ERR_CONNECTION_REFUSED`，spec 头注明需 `sbt run --port 8094`；本批纯前端不跑 sbt，历史归因） |
| 2 | bgagent-project-sessions / bgagent-toolline-truncate / branding-migration / explorer-nebflow-dir / flowmap-realtime / micorb-presets / micorb-volume / micorb-settings-hidden / orbit-anim / tasklist-nodes | **41 passed** |
| 3 | verify-micorb-hide-qa / voice-dedupe / subagent-panel-cancel-realtime | **18 passed** |
| 4 | pr41-locale-search-jump（plain node 脚本，非 playwright spec） | `node tests/pr41-locale-search-jump.spec.mjs` → 11/11，exit 0 |
| 已知既有失败 | bgagent-dedupe | 1 failed：`running Frontend · …` vs `进行中Team0sFrontend · …` **ghost-dup 文案断言**（bgAgentPopup 下拉行，与收起域无关，历史归因） |
| 已知既有失败 | smoke | 5 failed：页面加载 console / WS auth probe / 真实后端 lazy-load ×3——**缺真实后端**，历史归因 |

**改动域相邻 spec（history-replay-cards 27 内全绿、新 spec 8/8、turnGroup 相关全链路）无回归。**

`node scripts/check-js-types.mjs`：输出与**未改动的 main 基线逐字节一致**（total 343，flowAnim/flowMapTab/micOrb/orbSettingsUI「new file」+ chat.js TS2339 7>6 —— 均为 main@7710190c 上的存量基线漂移，**本批零新增**；turnGroup.js 自身 0 错误）。

verify-i18n-sweep：`scripts/` 下无该脚本；本批零新增文案（复用既有键），不适用。

## 七、截图

- `/Users/dev/.nebflow/docs/Nebflow/20260903_collapse-keep-text-dark.png`
- `/Users/dev/.nebflow/docs/Nebflow/20260903_collapse-keep-text-light.png`
- ~/.nebflow commit：`3aaf181`（只 add 两个具体文件）

## 八、生效说明

**本批未生效到运行中的宿主**：前端产物需 `node scripts/build-web.mjs` 重建 + 宿主重启（随重启包），本批不做。验证全部基于源模块静态 harness，未触碰 8080 宿主。

## 九、遗留问题

1. 摘要条按组各显示一次「✻ 短语 · model · 本组工具数」，同 turn 多组时短语重复——按裁定「最小改动」保留，如需「仅首组带短语」后续可加策略。
2. `check-js-types` 存量漂移（main 上 flowAnim/flowMapTab/micOrb/orbSettingsUI/chat.js）需基线 `--update` 或补类型，非本批范围。
3. askuser/smoke 家族需真实后端才能在本机跑绿（依赖 sbt 实例），本批按纪律未起后端。
