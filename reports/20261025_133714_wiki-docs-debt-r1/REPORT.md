# wiki-docs 纪律还账 · 第 1 轮批报告（台账 + 全量清偿 + 续还计划）

批：20261025_133714_wiki-docs-debt-r1（重派批，替代零产出的原批 #706/10-22 派）
分支：`delegate/delegate-nebflow-a80923f5`（3 个还账提交：52be2aff6 r1 / 0f9f918f0 r2 / e962b014a r3）
范围红线自检：改动面 = `docs/*.md` 文字面 14 篇（15 个 md 文档中 14 篇有改动，校验器按 15 篇计）＋本报告；**零触碰代码/spec/CI/脚本**；未新建任何词条页（glossary 零新增条目，只改 3 条既有定义的行内措辞）。

---

## 0. 结论先行

- **台账建成**：按 wiki-docs 四类缺口口径全量扫描 `docs/`，登记 **142 条**（A 类 4 + B 类 51 + C 类 4 + D 类 83），另有 8 条低价值/域外项判定**留观**（§4，不入账、留理由）。
- **本轮清偿 142/142**（≥60 的目标超额完成）：机械校验器 `check_wiki_docs.py --root docs` 从 **59 条违规 → 0 违规**（15 篇文档全绿），三轮各有提交与增量记录。
- 原批声称的「147 条」无在案台账可考（reports/、evidence/ 均无 #706 产物）；本台账为按同一纪律口径的**独立重建**，计数方法逐条可复核（§1），与 147 同量级。

## 1. 方法与口径

工具：仓内 `src/main/resources/seed/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py`（`--root docs`，三态退出码；每轮结束跑一遍 = 本批的「文档相关 spec」）。人工盘点面（D 类）以「概念首次出现处既无就地一句话解释（破折号/冒号/括注式）、又无 wiki-link 指向已解释它的文档」为准，含两类机器面盲区：中文复合词（如「重启窗外」不触发 R3 词表匹配）与非词表概念（Caddy/xerj 等 23 词表术语之外）。

四类缺口定义：

| 类 | 定义 | 机器面 | 条数 |
|---|---|---|---|
| A | 缺 front-matter（type/status） | R1 obsidian-format | 4 |
| B | 断链引用（指向库内不存在的目标） | R2 broken-link | 51 |
| C | 孤儿文档（零出链且零入链） | R2 orphan-doc | 4 |
| D | 概念首现未释未引 / 引用写成裸路径不成图谱边 | R3 面人工盘点 | 83 |
| | **合计** | | **142** |

B 类考古结论：index.md 的 51 条断链目标（architecture/issues/reviews 等）在 git 历史中存在过，v1.0.0 release 清仓提交整体移出仓库——index 引用的是旧库形态，故处置为**存目化**（转反引号普通文本、标注出处与找回方式、不作图谱边），而非删除或臆造目标。

排序依据（任务要求的「引用密度最高的枢纽文档优先」）：index.md 出链 51 条为全库最高枢纽 → 第 1 轮；四篇零纪律 spec（同时缺 A+C）→ 第 2 轮；其余存量正文 → 第 3 轮。

## 2. 台账（逐文件，全部已清 ✓）

### r1 枢纽层（56 条 = B51 + D5，commit 52be2aff6）

| 文件 | 类·条 | 明细 |
|---|---|---|
| docs/index.md | B·51 | architecture×2、behavior-parity×1、design 库外旧稿×24（analysis/design/architecture-optimization-proposal/actor-mailbox/flow-engine/flow-tree/flow-redesign-plan/context-memory-analysis/scala-context-management-report/memory-system/memory-v2/card-rendering-protocol/multi-view-refactor/20260928_nodeprogress/cli-design/agent-icon-design-guide/hook-system-demo.html/ci-testing/git-management/session-reminder/telemetry-dashboard/inter-agent-mail/sync-design/sync-mvp/mesh-sync-v2）、evidence×1、issues×18、reviews×3、（issues/templates/README 含在前）→ 全部存目化 ✓ |
| docs/index.md | D·2 | ①写作纪律节补 wiki-docs 条款与校验器指针（SKILL.md 路径 + check_wiki_docs.py 三态口径）；②四篇未挂接的设计稿（paragraph-ref/device-provenance/subagent-row/turn-liveness/delegate-supervision）补入在库索引（跨文档引用缺失） ✓ |
| docs/glossary.md | D·3 | ①vault 定义补 Obsidian 释；②重启窗定义补 sbt/JVM 释；③中继 ingress 平面定义补 link-server 释 ✓ |

### r2 孤儿复苏层（32 条 = A4 + C4 + D24，commit 0f9f918f0）

| 文件 | 类·条 | 明细 |
|---|---|---|
| design/delegate-supervision-revival-design.zh-CN.md | A·1 + C·1 + D·9 | front-matter（design-note/done）；相关文档出链（index/subagent-row/glossary）；D：F2 注入队、transcript rehydrate、ready-to-merge 停靠、座位目录、kill-switch、nudge、ChainStore 审计行、stub LLM、verdictLine ✓ |
| design/device-provenance-spec.zh-CN.md | A·1 + C·1 + D·7 | front-matter；相关文档出链（index/glossary/psr）+ 重启窗词表引用；D：NebLink、两 face 分离、ws 帧、UA、fail-open、XSS、重启窗→[[glossary#^restart-window]] ✓ |
| design/subagent-row-display-name-spec.zh-CN.md | A·1 + C·1 + D·3 | front-matter；相关文档出链（index/delegate-supervision/glossary）；D：恢复脸、spawn、checkJs ✓ |
| design/turn-liveness-spec.zh-CN.md | A·1 + C·1 + D·5 | front-matter；相关文档出链（index/subagent-row）+ Sub-Agents 行跨链；D：heldReceipts、drain、qattachvault、指数退避 ✓ |

### r3 存量正文层（54 条 = D54，commit e962b014a）

| 文件 | 类·条 | 明细 |
|---|---|---|
| parallel-session-resources.zh-CN.md | D·9 | zcode 审计出处+检查单跨链、worktree、lsof、会话短 id 约定（§0 台账记法注）、sbt、QA 实例、Nebula 心跳 daemon、neblink-server+provenance 跨链、裸路径→production-ops 链接 ✓ |
| production-ops-checklist.zh-CN.md | D·12 | Caddy（含 Caddyfile）、QA 隔离实例+psr 跨链、配套两条裸路径转链、ConnGuard、staging、vercel、Logto、B3 转链、E1 的 307、附注两条裸路径转链 ✓ |
| design/plugin-md-required-file-spec.zh-CN.md | D·4 | seed 包、MCP、Canvas + 相关文档出链（index/gate-plan/glossary，半孤儿补出链）✓ |
| design/visual-report-gate-plan.zh-CN.md | D·5 | vault→词表引用、论文式→citation-edge 引用、frontmatter→词表引用、审计会话短 id 释、paragraph-ref 跨链 ✓ |
| design/wiki-graph-paragraph-ref-design.zh-CN.md | D·3 | #744-A 台账编号、xerj、peer ✓ |
| design/wiki-graph-redesign-design.zh-CN.md | D·7 | 力导向→[[glossary#^force-layout]] 引用、raf、CSS token、container query、cover-flow、CC1–CC3 契约、#717 ✓ |
| templates/console-403-diagnosis.md | D·7 | digest、crash-loop、Logto、OIDC、onboarding（首现移至⑧症状行）、ETag/304、conn-guard/per-ip-cap ✓ |
| templates/console-load-perf.md | D·7 | W3、304、brotli（首现 A1 行）、immutable、chunk、checkJs、打包孤儿 ✓ |
| （小计 D） | **54** | |

**三轮合计：D 类 = 5（r1：index 2 + glossary 3）+ 24（r2 四篇：9+7+3+5）+ 54（r3 八篇：9+12+4+5+3+7+7+7）= 83；总计 A4 + B51 + C4 + D83 = 142 条，全部 ✓。**

## 3. 每轮校验器读数（增量记录）

| 轮 | 提交 | check_wiki_docs 读数 | 违规变化 |
|---|---|---|---|
| 基线 | — | 59 条违规（R1×4 / broken-link×51 / orphan×4） | — |
| r1 枢纽层 | 52be2aff6 | 4 条违规（R1×4） | −55 |
| r2 孤儿复苏 | 0f9f918f0 | **0 违规（15 篇全绿）** | −4 |
| r3 存量正文 | e962b014a | 0 违规（维持） | 0（机器面已绿；r3 清的是人工盘点 D 类） |

其他自检：改动文件全部为 `docs/*.md`（git status 无代码/spec/CI 面文件）；两处编辑曾误吞换行（production-ops B1/B2、load-perf A3/A4 边界）当场发现并复原，终稿经全量 diff 自审 + 首现位置复核（Logto/onboarding/brotli 三处词解释归位到各自文件内的真正首现行）。

## 4. 留观清单（识别但故意不入账，8 条）

| # | 位置 | 概念 | 留观理由 |
|---|---|---|---|
| 1 | psr §2.2 | fork-proof 主保险 | 语义权威在 AGENTS.md 第 9 条，域外定义，不越权转述 |
| 2 | poc B1/D 区 | 环境表宿主 PID | 同上（AGENTS.md 域概念，句内已有出处引用） |
| 3 | device-provenance / console-403 | localStorage | 标准 Web API，受众为引擎/前端开发者，过释增噪 |
| 4 | plugin-md spec | fail-closed / no-silent-degradation | 就地表格已自释（「不静默降级」同格在列） |
| 5 | psr §0 | 「A 实例 / B 机」语义 | 无在案定义可考，不臆造；候作者确认后补台账记法注 |
| 6 | wgr §9.8 | CC4 / #716 / #566 批号 | 同族编号已各释一处（CC1–CC3/#717），逐条补释边际价值低 |
| 7 | wgp §6 | pane-local | 句内「不遮挡画布操作」已承载语义 |
| 8 | dsr §5 | #566 判词退役 | 判词编号引用，出处批在案（验证纪要），低风险 |

## 5. 续还计划（剩余工作，按优先序）

1. **词表扩条裁定（候作者）**：本轮高频跨文档概念——Caddy/Caddyfile、ConnGuard、Logto、xerj、F2 注入队、会话短 id 约定、QA 隔离实例——目前以「首现行内一句释」还账；若作者裁定入 `glossary.md` 作唯一定义源，则把行内释改为 `[[glossary#^块锚]]` 引用（每条约 5 分钟，10 条内）。
2. **库外审计原件持久化**：`/tmp/zcode-audit-report.md`、`/tmp/zcode-audit/audit-*.md` 两份被 psr/poc 引用的审计原件仍在 /tmp（易失）。建议后续批把原件落 `docs/attachments/` 或 evidence 后，回填两文档的引用路径（属内容搬运，超出本轮「只动文字面」红线，未做）。
3. **校验器中文复合词盲区**：R3 词表匹配对「重启窗外」类复合词不触发（term_regex 负向环视所致）。属 `check_wiki_docs.py` 代码面，本批红线不碰；建议后续工具批评估「词表术语命中后向一词窗口」的放宽口径。
4. **存目条目复活**：§存目区条目若重新入库（如 issues/ 系列、reviews/parity），把反引号路径改回 wiki-link 并删除存目头注的「不作图谱边」声明。
5. **新文档防线**：新文档入库时跑 `check_wiki_docs.py --root docs`（exit 0 即可合入）——本轮后基线为零，任何新增违规即增量可辨。

## 6. 关键假设

- 原批 #706 的「147 条」台账无任何在案产物，本报告台账为独立重建；两数不可直接对表，计数口径以 §1 为准。
- 「存目化」是断链处置的设计决策（保留信息 + 消除假边），依据是 git 考古（v1.0.0 清仓删库）而非臆测；若作者 prefer 直接删除死条目，存目区可整段移除（一处编辑）。
- glossary 既有 23 词条未增减；「唯一定义源」原则下新增词条一律留给作者裁定（续还计划 1）。
