# 对话历史建库+检索 CLI（stand-recall）+ xerj 成果复用 —— 交付报告

批次：记忆检索工具批（作者令 2026-10-21）· 执行：delegate-nebflow-c00e0fd9
交付物：`~/.stand/memory-db/`（SQLite FTS5 语料库，存量主会话全量已索引）+ `tools/stand-recall`（零依赖检索 CLI）+ Grep 优化提案（另文）。

---

## 1. 会话存储格式（摸底结论）

数据根 `~/.stand`（只读消费，全程零写入；索引产物只落 `~/.stand/memory-db/`）：

| 文件 | 格式 | 要点 |
|---|---|---|
| `sessions/_index.json` | `{activeId, sessions:[SessionMeta]}` | 会话注册表；`agentName` 键判归属（`SessionMeta.scala:12`） |
| `sessions/<id>.json` | `List[Message]` | Message = `{timestamp, role, content, source?}`；content = 字符串 或 块列表（`text/image/tool_use/tool_result/thinking`，`protocol.scala:8-65`）；`source` = 注入源（mail/delegate/subtask/flow/skill/tool） |
| `sessions/<id>.ui.json` | UI 渲染消息 | 不入语料（含 thinking 等非对话面） |
| `sessions/<id>/compaction/*-before.json` | 压缩前全量快照 | **压缩掉的真历史在这里**——本次从 before 快照追回主会话 ~695 条当前文件已不存在的消息 |

主 agent（Nebula 根会话）判据：引擎单点 `RootAgentIdentity.isRootAgent = name=="Nebula" && depth==0`（`RootAgentIdentity.scala:47`）；盘面落地 = `_index.json` 里 `agentName=="Nebula"` 的**唯一**会话 `d7c3cb54`（2026-10-10 起，单会话架构）。

## 2. 语料范围与分类（严格按作者口径）

只收主 agent 对话历史；teams/nodes/delegate/kernel/flow/外部执行器一律不收。分类规则（`tools/stand-recall` 内单点，全程透明可查）：

- **main-current**（1 会话）：`agentName=="Nebula"`。
- **main-legacy**（160 会话）：多会话时代（2026-05~10）用户↔主 agent 聊天。排除：`external-*` id、名前缀 `Recovered Session`/`=== Flow`/`📬 Mail from`、首条真实用户文本命中派发正则（`You are`/`## `/`[TASK]`/`[INTERRUPT]`/`Inventory the`/`用户要求`/`测试通信` 等 inter-agent 工件）。无真实用户文本（工具结果/思考碎片）的孤儿恢复件一律排除。
- **main-compacted**（36 会话）：`<context-compact` 压缩续篇，抽样验证均为主 agent 对话，收录。

消息级排除（口径单点在 `extract_messages`）：工具结果（tool_use/tool_result 块）、thinking 块、`<system-reminder>` 注入、带 `source` 的注入消息、`<context-compact>` 摘要本体（真历史由 before 快照承载）、system role。附件（`[用户附加文件: …]`/`[用户附加图片: …]`，标记定义在 `UnifiedMedia.scala:63`）单独入 `attachments` 登记表，图片内联块登记 mediaType。

**全量索引结果**：9,747 条消息 / 197 会话（1+160+36）/ 287 条附件登记；时间跨度 2026-05-28 → 2026-10-21；排除 645 个非主会话。库 `~/.stand/memory-db/memory.db`（27.5MB）+ `index-manifest.json`（逐会话收录/排除原因，162KB）。泄漏自检：库内 `<context-compact`/`<system-reminder`/toolUseId 命中均为人读正文中提及，零引擎注入泄漏。

## 3. xerj 成果复用（哪些用了什么、哪些没用为什么）

**前情**：全数据根检索确认**不存在**既有 xerj 分析报告（`~/.stand/docs`、`~/.nebflow/projects`、各工作区仅 HTML 内随机串假阳性；对话库自检亦无 xerj 讨论）——作者记忆有偏差，按预案走代理拉仓直接分析（`github.com/xerj-org/xerj`，Rust 本地检索引擎：BM25+kNN+hybrid、ES 兼容 API、MCP server、agent memory）。

| xerj 技术（出处） | 用了什么 | 怎么用的 |
|---|---|---|
| CJK bigram 分词（`xerj-fts/src/analyzer.rs:797-870`，Lucene CJKAnalyzer 同款：CJK 连续串交叠 bigram、单字独立成 token、ASCII 按词切） | ✅ 核心采用 | SQLite FTS5 无自定义 tokenizer、trigram 需 ≥3 字查询（中文 2 字词退化），故以 **bigram 影子列 + 查询侧同变换**在 unicode61 上仿真；索引/查询变换单点 `cjk_bigram_text`/`fts_query` |
| BM25 排序（`bm25.rs`，k1=1.2/b=0.75 与 Lucene/ES 全等） | ✅ 直接复用 | FTS5 内置 `bm25()` 同公式；并列时新消息优先（对话回溯的弱 recency 偏好） |
| phrase 保序邻接（positions 语义） | ✅ | CJK 查询串折叠成 bigram 序列的 quoted phrase（如 `"对话 话历 历史"`），精度即邻接 |
| 检索-放宽策略（`search.rs` Bool/DisMax/tie_breaker） | ✅ 简化版 | AND 全词 → 零命中降级 OR → 排序不变 |
| ranked passages（返回片段而非裸行号） | ✅ | 命中消息 ±N 上下文邻句（会话内 (ts,id) 窗口序） |
| 增量重索引（LIVE_REINDEXING 思路） | ✅ 幂等版 | `UNIQUE(session,ts,role,hash)` + `INSERT OR IGNORE`；`--update` 全扫秒级、无守护进程 |
| 向量/kNN + 神经 embedder（`vectors.html`：默认离线 lexical、neural 需下载 ~90MB 模型） | ❌ 未采用 | 零依赖约束（纯 Python 标准库）+ 语料量级（万条）BM25 足够；留作未来 opt-in |
| 外部 rerank 判卷（`RERANK.md`：问题+命中文本 POST 第三方） | ❌ 未采用 | **数据出机红线**；且 xerj 自己实测概率未校准（ECE 0.10-0.31），只排序不阈值 |
| ES 兼容 API / MCP server / 集群 / second-brain 关系层 | ❌ 未采用 | 服务形态（常驻进程）与作者「不驻守护进程」令直接冲突；关系层属二期候选 |
| autoindex 目录自动巡索引 | ❌ 未采用 | 语料是结构化会话存储而非任意目录；`--update` 显式增量已覆盖 |

## 4. 检索 CLI（`tools/stand-recall`，已入库）

零第三方依赖（Python3 标准库）。`--update` 增量（幂等，实测 +0）；`--rebuild` 全量重建（实测 3.6s）；`--stats`/`--doctor` 概况与逐会话分类明细；检索默认输出 命中+时间+角色+会话名+上下文邻句+附件路径，`--json` 机读；`--session/--role/--since/--until/-n/--context` 过滤。单字 CJK 查询（bigram 索引无谓词）自动退化 LIKE 子串扫描。

## 5. 验收自证（5 个真实回溯查询，全命中）

均从既有真实历史对话回溯（查询 → 命中摘录）：

1. **`stand-recall "电离能损公式"`** → 命中 08b63a37「如何理解电离能损公式？」(2026-06-14)，邻句给出 Bethe-Bloch 公式全文回复 ✓
2. **`stand-recall "自生长的agent流"`** → 命中 aa5a0c08/d275eca7「我现在设计flow的目的，是为了实现一个自生长的agent流…」(2026-07-20 16:10) ✓
3. **`stand-recall "视觉空间学习者 书"`** → 命中 849c80f6「我一直觉得自己是视觉空间学习者，有没有什么书…」(2026-06-19)，含助手推荐书单邻句 ✓
4. **`stand-recall "delegate 主agent只做任务派发"`** → 命中 6d46110c「检查一下目前delegate工具的设计，我后期的想法是，主agent只做任务派发…」(2026-06-20 20:24) ✓
5. **`stand-recall "带我读 纳瓦尔宝典"`** → 命中 5ec5d6ac「带我读一下这本书」(2026-06-11)，**附件路径** `📎 /Users/kaiyu/Downloads/纳瓦尔宝典…epub` 同行给出 ✓

（附加验证：`"幂"` 单字退化 LIKE 命中 2026-10-21 主会话「幂等」段落；`--since/--until` 时间过滤命中窗口正确；`xerj` 零命中佐证报告不存在。）

## 6. Grep 优化提案（子项）

另文一页：`reports/20261021_232400_grep-xerj-optimization-proposal.md`（两小步已实现带测试；四类不采用项写明理由候批）。

## 7. 未尽事项 / 假设

- 「既有 xerj 分析报告」不存在（作者记忆偏差），已按预案直接分析仓库；如后续找到旧报告，可再对表增补。
- main-legacy 分类是规则+抽样裁定（645 个排除逐条原因落 manifest `--doctor` 可查）；误伤可加 `OVERRIDES` 显式翻案，不必改规则。
- 跨会话重复消息（同文双会话/续篇碎片）未跨会话去重——检索侧噪声可忍，库侧保持会话忠实。
- `~/.stand/memory-db/` 为数据产物不入 stand 仓；`tools/stand-recall` 入仓（本分支，随合并落 main）。
