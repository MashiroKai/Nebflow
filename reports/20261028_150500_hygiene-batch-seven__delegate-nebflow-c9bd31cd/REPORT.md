# 卫生批打包（七件一次清）——REPORT

- 授权：作者拍板 2026-10-28 14:0x「按推荐打包做」。依据 = 三份审计报告：KG 审计（`reports/20261028_103500_knowledge-graph-audit__delegate-nebflow-fec22d25`）、插件审计（`reports/20261028_115822_plugin-system-audit__delegate-nebflow-67b2a760`）、回放批残余（`reports/20261028_120721_replay-terminal-batch__delegate-nebflow-810dea92` §5）。
- 分支：`delegate/delegate-nebflow-c9bd31cd`（工作树 `.nebflow/worktrees/delegate-nebflow-c9bd31cd`）。**在产引擎（pid 70481 / gateway 6138）全程零触碰**——纯代码批，未向 :6138 发任何请求；`~/.stand` 只读（stand-plugins CLI 查询、validator 实测）。
- 基础面：`sbt compile` 641 sources 全绿（exit 0）；`npm run build` 全绿（188 js 源全图入包、orphans 0）。

---

## ① KG·落点约束（分发简报/收尾模板写死边界）

**改了什么**（三处模型可见模板位）：
- `src/main/scala/nebflow/core/entity/BuiltinAgents.scala`：`NebflowNodePrompt` 与 `NebflowDelegatePrompt` 两面的「## Report (your final text)」收尾段各追加一段 **Knowledge-graph landing discipline**——知识文档（designs/audits/research/conclusions）必落注册项目 workspace 的 `docs/` 子树（Obsidian 格式：frontmatter `type:`/`status:` + ≥1 `[[wikilink]]`），`reports/` 只收纯过程件；附一行校验命令 `python3 <dataRoot>/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --root <workspace>/docs`（exit 0 = 合规）。
- `src/main/scala/nebflow/core/tools/DelegateTool.scala`：description 的 **Rules** 段（分发简报模板位）追加同口径一行——任务产出知识文档时，简报须写明落点纪律与校验命令。

**证据**：三处提示词判据门全绿——`NodeSeedOutputContractSpec`（五要素段在位、首行语域、零 HTML 注释）、`PromptFaceSplitSpec`（双面分离、delegate 面零 node_report）、`DelegateToolSpec`（description 硬事实面）随批重跑全过（见 §验收合计 59/59）。追加为纯增量行，零既有断言改动。

## ② KG·viewer backlinks（文档页底部折叠小节）

**改了什么**：
- `src/main/resources/web/js/viewers/markdown.js`：新增 `renderBacklinks(mdViewer, absPath)`——`/api/wiki/graph` 载荷（30s TTL 共享缓存）里按 absPath→vault 前缀匹配节点，取 `edges[to===node.id]` 的 from 端（滤 stub），在文档底部渲染 `<details class="nf-backlinks">`（默认收起）：计数摘要 + 条目列表。条目复用 `a.nf-wikilink` 委派点击腿（`data-wiki-resolved` 直填解析结果 ⇒ `openLocalFileTab` / remote-doc 腿零新增）；边锚 `^blk` 原样、`#Heading` 去井号透传（点开即跳引用落点段），`L42` 无点击映射不透传；跨项目入链带项目标签。**克制面**：文档不在任何 vault 或零入链 ⇒ 整节不渲染；图谱 fetch 失败 ⇒ 静默无节（#762 渲染腿纪律）。
- `src/main/resources/web/css/split.css`：`.nf-backlinks` 折叠节样式（细上边框、dim 文字、12.5px）。
- `locales/zh-CN.js` / `en.js`：`wiki.backlinks`（反向链接 / Backlinks）。

**证据**：新 spec `tests/md-backlinks.spec.mjs` + `tests/fixtures/md-backlinks/harness.html`（真实 viewMarkdown 链路，唯一替身 = graph fetch stub，载荷与 WikiIndex JSON 逐字段同构），7 面：
- 绿腿（本树）**6 passed**：BL1 折叠区块/计数/本地+远端解析形状/锚形态/chip/项目标签；BL2 点 summary 展开；BL3 零入链不渲染；BL4 图外路径不渲染；BL5 403 静默降级正文照常；BL6/7 委派契约。
- 红腿（merge-base 基线树 `NB_UX_ROOT=/tmp/nb-hygiene-base`）：**3 failed（BL1/BL2/BL6-BL7——缺陷在场的证据：基线树无此区块）+ 3 passed（BL3/BL4/BL5 缺席面两树同绿）**。

## ③ KG·4 篇补 frontmatter（stand 库）

**改了什么**（审计「4 篇缺 fm」清单 = 本批实测校验器同名单，四篇兼为孤儿）：`docs/design/` 下
1. `20261026_detached-apply-restart-update-spec.zh-CN.md`（fm `type: design-note / status: done`）
2. `20261027_cli-isolation-and-detached-restart-spec.zh-CN.md`（同上）
3. `20261027_hot-reload-foundation-spec.zh-CN.md`（`status: open`——地基已落地、全量热更未承诺）
4. `imagegen-dual-layer-spec.zh-CN.md`（`status: open`——调试中/experimental）

各补 1-2 条真实相关件 wikilink（前三篇互接成 #916 更新/重启族，imagegen 接 `[[glossary]]`）——同时清 R1（缺 fm）与 orphan（零链）两个违规面。

**证据**：`check_wiki_docs.py --root docs` 改前 **8 条违规**（4 R1 + 4 orphan）→ 改后 **「通过（docs，19 篇文档，零违规）」exit 0**；`--graph` 导出与引擎同构：23 nodes / 106 edges / 0 stub，四篇全部带 fm 入图（出链 2/2/2/1，前三篇另有互引入链）。

## ④ 插件 G1·装载器 known-set 补 Plugin.md

**改了什么**：
- `src/main/scala/nebflow/core/plugin/PluginRegistry.scala:583`：`known` 集合 `{plugin.json, mcp.json, skills, org.nebflow}` → **+`Plugin.md`**（恰一文件、逐字命名，与 validator `KNOWN_ROOT_ENTRIES` 逐字镜像）；小写 `plugin.md` **不收**——注入面（PluginInjection）回退同认 ≠ 装载面放行，与 validator C11b「改名提示」同向。批注登记六-1 欠账清偿。
- `validate_plugin.py` 两处头注（锚点注释「装载器侧扩面随下个引擎批」→「已于卫生批 2026-10-28 落地，逐字镜像」）——镜像文档面同步归真。

**证据**：
- `PluginRegistrySpec` 新增 fixtures（`with-plugin-md` / `lower-plugin-md`）+ 两条断言：**「包根第五件（正字）装载零 unknown-entry 告警」**与**「小写 plugin.md 仍告警」**均 PASS；21 包常驻 `ignored unknown entry 'Plugin.md'` 的成因面（canonical 正字进 known-set）由本测试钉死。
- validator 镜像实测：`KNOWN_ROOT_ENTRIES` 含 `Plugin.md`，对在装包（backend-dev）逐条目对账 unknowns=NONE。
- 健康摘要计数断言随 fixtures 增至 8 包同步更新（8 on disk / 6 loaded / 5 visible / 3 absent——注释登记缘由）。

## ⑤ 插件 G2·creator 文档自洽

**改了什么**（审计 G2 (a)(b)(c)(d) 四处 + 同族 1 处，统一为「Plugin.md=分配注入体、SKILL.md=按需查阅件」）：
- `nebflow-plugin-creator/Plugin.md`：:17「派发时两份 SKILL.md 全文注入节点」→「分配时注入本文件，两份 SKILL.md 为按需查阅件」；目录结构两处「（注入面）」→「（按需查阅件）」；:41「以注入的 SKILL.md 为准」→ 去注入措辞；:74「注入面权威，spawn 已注入」→「按需查阅件」；§8 表格 `pluginmd.template.md`「规划中」→「**已在位**（附路径与用法）」；:110 维护约定「Plugin.md 是按需 Read 面——SKILL.md 是注入面权威」（新旧倒置）→「Plugin.md 是分配注入体——SKILL.md 是按需查阅件（内容权威）」。
- `skills/plugin-packaging/SKILL.md`:35「SKILL.md 正文在 spawn 时一次性全文注入」→ 按需查阅口径（与同文件 :84-89 的矛盾消除）。
- `skills/plugin-packaging/assets/skill-template/SKILL.md`:10「节点在 spawn 时一次性收到本文全文注入」→ 按需查阅口径。
- `skills/description-quality/SKILL.md`（同族顺手）：坏例①「节点侧已被插件全文注入取代」→「节点侧的插件注入面 = 包根 Plugin.md，不含 user 层 skill」。
- `nebflow-plugin-creator/plugin.json`：description 收尾句补回五段式正字前缀「组件面：」（原为裸「无 mcp.json、无工具扩展。」——正是 G3 反句剥离漏网的第二处，见 ⑥）。

**证据**：creator 自校验 `validate_plugin.py --profile creator <自包>` **31 checks / 31 PASS / 0 FAIL，exit 0**（含 M15 全包零占位残留、C11 包根五件镜像、C11b Plugin.md 在位）；digest 复算在位（内容变更 ⇒ 面板「内容已变更」可见性提示，seed 出厂字节赢语义随下波包刷新盘上副本）。

## ⑥ 插件 G3·stand-plugins search 反句

**改了什么**：
- `tools/stand-plugins`：`searchable_fields` 的 description 字段改走 `search_description()`——按五段式固定收尾标记 `组件面：` 剥离该句（**反句不进检索**；展示面 `render_plugin` 整段 description 原样保留）。真正带 MCP 的包由 `mcp_servers` 字段（server 名）自然命中。
- 配套（creator seed manifest，见 ⑤）：creator 自身 description 收尾句补「组件面：」前缀——否则其裸尾句成为剥离规则外的漏网反句。

**证据**（对真实数据根 `~/.stand` 实测，只读）：
- 改前 `search mcp`：**22/22 全中**（审计时 21 包 + 审计后新装的 `dbm-memory`——首个真实 mcp.json 包），每包 matched 都是 description 里的「组件面：无 mcp.json」反句。
- 改后 `search mcp`：**2 hits，均有正句依据**——`dbm-memory`（`matched: keyword:MCP`，真 MCP 包 + keywords 通道）与 `nebflow-plugin-creator`（`matched: skill:` 形态 B MCP 打包能力，正句）。
- 对「seed 刷新后盘上副本同批」的终态模拟（临时根拷贝 + manifest 正字化）：同样 2 hits、零噪音。正例回归：`search 浏览器` 2 hits、`search 论文` 6 hits 不受影响。

## ⑦ 回放·Tool 终态小刀（退化全工具轮归零）

**改了什么**（回放批 §5 残余①根治——段内零 ai 行的退化全工具轮，如 Mail 单卡委托轮）：
- `src/main/scala/nebflow/shared/protocol.scala`：`UiMessage.Tool` 增 `terminal: Option[Boolean] = None`——编码只在 `Some(true)` 落键（Ai.terminal 先例同款条件编码 ⇒ 旧行字节形态逐字不变）；解码宽松透传（旧行无键 ⇒ None）。
- `src/main/scala/nebflow/core/SessionStore.scala`：新增 **`markRoundTerminal`**（段感知终态落点）——段边界 = 最后一条 User 行：①段内有 ai 行 → 照旧回填 durationMs/model/timestamp + terminal；②段内零 ai 行 → terminal 盖到段内最后一行 **Tool** 行（等价终态面）；③空段 no-op。**消灭旧行为的越段面**：旧 `updateLastAiMeta`（全局最后一条 Ai）会把新时值盖到上一轮的行上。
- `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`：`done`/`agentDone` 空缓冲回填分支两处调用点切换到 `markRoundTerminal`（`updateLastAiMeta` 本体保留——SessionRecorder 镜像调用面未动）。
- 前端：`persistence.js` 两条回放腿（`restoreFromStorage` + `buildHistoryRow`）tool 分支 `m.terminal === true` ⇒ `row.dataset.nfTurnTerminal='1'`；`turnGroup.js` `summarizeSegment` E6 例外收窄——**writer 盖戳的 terminal 行是轮闭合事实**，全工具段照折（与 live 路径同形：live 无 E6 例外），纯启发式（badge）段维持 E6 平铺守卫。
- 形态红线：失败轮（error 帧不走 done 落盘点）无标记 ⇒ 平铺；busyTail 无标记尾段 ⇒ open——两守卫零回归（下方 spec 钉证）。

**证据**：
- `tests/turn-terminal-replay.spec.mjs` 扩至 **14 面**：新增 TO1（terminal 工具行 ⇒ header + 末张裸卡 tucked）/TO2（legacy 无键维持平铺）/TO3（busyTail + terminal 工具尾段闭合）/TO4（header 点开可展，与 live 同形）。绿腿 **14/14 passed**；红腿（merge-base 基线树，`PIN_EXPECT=red --grep TO`）**4/4 passed**（基线树证实在场缺陷：无行标、无折叠；守卫面 TO2 同绿）。
  - 注：前批 10 面在 merge-base 上已是绿面（该基点已含回放批），其红腿语义仅对本批新面（TO 族）成立——红腿按 `--grep TO` 取证。
- 后端新 spec `src/test/scala/nebflow/shared/UiMessageToolTerminalSpec.scala` **8 tests 全绿**：①无标记 Tool 行编码零新键（旧行字节形态逐字不变）②round-trip ②b 旧行 decode None ③a 退化轮盖末行 Tool ③b 有 ai 载体时回填照旧 ③c **不越段**（上一轮 durationMs/model 零改写——旧行为的 bug 面）③d 空段 no-op ④只加不减 ⑤每轮恰一 terminal 载体守卫。

---

## 验收合计

| 项 | 证据 | 结果 |
|---|---|---|
| ① 落点约束 | NodeSeedOutputContractSpec / PromptFaceSplitSpec / DelegateToolSpec（含新行） | 全过（59/59 合计内） |
| ② viewer backlinks | md-backlinks.spec 绿 6 / 红 3✘+3✓ | 绿全过、红=缺陷在场 |
| ③ 4 篇 fm | check_wiki_docs.py | 8 违规 → **0**（19 篇通过） |
| ④ G1 known-set | PluginRegistrySpec +2 面 + validator 镜像实测 | 全过 / unknowns NONE |
| ⑤ creator 自洽 | 自包 validate --profile creator | **31/31 PASS exit 0** |
| ⑥ search 反句 | 真实数据根实测 | 22/22 → **2（均正句）** |
| ⑦ Tool 终态 | turn-terminal 绿 14 / 红（TO）4；UiMessageToolTerminalSpec 8 | 全绿 |
| 既有套件 | replay/collapse 家族 9 套 **44 passed**；md 家族 mermaid-md + canvas-preview-nav-parity + mdscroll **30 PASS / 0 FAIL** | 零新增失败 |
| 编译/构建 | `sbt compile`（641 sources）/ `npm run build`（188 源、orphans 0） | 双绿 |

## 纪律核对

- 在产引擎零接触：验证全走静态 spec / 临时根模拟 / 只读 CLI；`~/.stand` 只读（无写请求、无 seed 触发）。
- 兼容纪律：三处协议面新增键全部「缺席即不落键 + 宽松解码」（旧 `.ui.json` 行字节形态与读法逐字不变，spec ①/②/②b 钉死）；旧转录回放回落启发式（T2/TO2 守卫）。
- 不用 sleep/轮询等待：长任务全走后台作业通知。

## 开放项（明示，不属本批验收口径）

1. `search mcp` 的 2 个正例 hit 属**正确结果**（真 MCP 包 + creator 的 MCP 打包能力）；若希望 creator 不出现在 mcp 检索，属 G5（keywords 通道治理）范畴。
2. localStorage 腿的纯工具轮仍为瞬态平铺（回放批 §5 残余②原样——historyPage 初载以带 terminal 的后端历史整替，本批未扩大）。
3. creator 盘上副本（`~/.stand/plugins/nebflow-plugin-creator`）的 description/Plugin.md 新文案随下次 boot seed「出厂字节赢」刷新（保留前缀包语义），盘上 digest 漂移会短暂显示「内容已变更」——既有机制，非本批行为。
4. KG 审计的「一批/专项」档（35 孤儿清零后续、三库补齐、reports 回填、收录率可观测、`__user__` 图谱、人读件口径）未动——本批只做「顺手修」三件 + 插件三件 + 回放小刀。

## 产物清单

- 代码 22 文件：`protocol.scala` / `SessionStore.scala` / `WebSocketRoutes.scala` / `BuiltinAgents.scala` / `DelegateTool.scala` / `PluginRegistry.scala` / `markdown.js` / `persistence.js` / `turnGroup.js` / `split.css` / `zh-CN.js` / `en.js` / `stand-plugins` / creator 包 6 件 / docs 4 篇。
- 测试 4 件：`UiMessageToolTerminalSpec.scala`（新）、`PluginRegistrySpec.scala`（+2 面 +2 fixtures +计数更新）、`md-backlinks.spec.mjs` + `fixtures/md-backlinks/harness.html`（新）、`turn-terminal-replay.spec.mjs` + harness（+4 面 +读数）。
