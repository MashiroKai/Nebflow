# REPORT — stand-plugins CLI + 分配面多选考古 + 各级提示词接线

批次：stand-plugins 批（作者令 2026-10-23 19:5x 微信：插件分配没挂够怎么办——「jev 好像只能单选……像记忆检索一样做成 cli，在各级提示词里告诉他们用法。查记忆，查插件能力。这样除了自动分配，还能主动获取」）
分支：`delegate/delegate-nebflow-1d83a61e` ｜ 红线遵守：CLI 只读零副作用、最小改动、不重启引擎。

## 0. 交付清单

| # | 件 | 路径 |
|---|---|---|
| 1 | stand-plugins CLI（新） | `tools/stand-plugins`（可执行 python3，零第三方依赖，只读） |
| 2 | CLI 单测（新） | `tests/stand_plugins_cli_test.py`（7 例，subprocess 驱动真 CLI + 夹具数据根） |
| 3 | 查询行常量（单点） | `src/main/scala/nebflow/shared/Defaults.scala` `PluginDiscoveryLine` |
| 4 | delegate brief 接线 | `src/main/scala/nebflow/core/tools/DelegateTool.scala` `assembleBrief` |
| 5 | 节点脚注接线 | `src/main/scala/nebflow/core/project/NodeEngineContract.scala` `protocolFootnoteFor` |
| 6 | root Nebula / 分发器提示词接线 | `src/main/scala/nebflow/core/entity/BuiltinAgents.scala`（NebulaPrompt `## Plugins` 段 + DispatcherPrompt §5 Capability allocation 段） |
| 7 | 接线钉 + 集合语义 spec | `SleepPollStartupRuleSpec.scala`（+2 例）、`PluginInjectionSpec.scala`（+2 例） |

## 1. stand-plugins CLI（item 1）

形态对齐 `tools/stand-recall` 调用约定：仓根 `tools/` 落位、可执行单文件、Python3 标准库零依赖、`--json` 机器形状、`--data-root` 数据根旗标（`STAND_DATA_ROOT` > `STAND_HOME` > `NEBFLOW_HOME` > `~/.stand`，旧机回落 `~/.nebflow`）、中文头注说明数据源与纪律。**只读**：仅 open/read，绝不写任何文件（单测有夹具树 before/after 零变化钉）。

三条命令（与引擎 PluginRegistry 一族同盘同源）：

- `stand-plugins list [--json]` — 注册面 `<data-root>/plugins/<name>/`：名称/版本/描述/keywords、skills（id + frontmatter name/description）、mcp.json servers、org.nebflow/tools.json 工具授予、Plugin.md 在场、状态标签（`[dispatchable]` / `[dispatch-off]` / `[blocked: reason]`）。信任语义 = 引擎现状（无审批批 2026-09-13「扫到即信任」：`plugins.revoked` 是唯一点名止损，`plugins.trust` 只作审计 approvedAt）；dispatchable = 未封禁 ∧ (authorEnabled ∨ transition 未过期)，与 `PluginDispatchPolicy.effective` 同式。拒载目录（坏 manifest / 非 canonical $schema / 无 skills 且无 mcp）进 rejected 段点名+原因。
- `stand-plugins search <关键词…> [--json]` — 按名称/keywords/描述/技能（id+name+description）/MCP server/工具授予模糊搜，多词 AND；命中行带 `matched:` 字段归因；无命中 `(no hits)` rc=0。
- `stand-plugins mounts [--project X] [--json]` — 各项目当前挂载：声明集（`<ws>/.nebflow/flow-map.json` nodes[].plugins + delegate 作用域图 `flow-maps/*/`）+ 分配台账（`<data-root>/chains/*.json` 的 `delegate-spawn` / `step-plugins` 行：plugins 集 + allocation 模式词）。项目桶按 `payload.project` 精确归；step 行/无项目 spawn 行归 `(unattributed)` 段。`--project` 未挂载 → rc=1 + stderr 列挂载候选（显式错，不静默）。

宿主实测（只读跑真数据根）：21 插件全列出；`search 论文` 命中 academic-research/browser-use 等；`mounts` 见 `delegate-nebflow-672fc4e7 [jev]: engineering-methods, visual-report`——**JeV 一派发分配两插件的实录**（§2 的实证）。

## 2. 分配面「单选」考古（item 2）——结论：全链本就是集合语义，无结构单选，无需拆面

考古范围（能力分配路径逐环读源码）：

| 环节 | 位置 | 形状 |
|---|---|---|
| JeV 决策腿 | `llm/decision/JevAllocator.scala:86-192` | 每包一对问句（`need_<p>` choice yes/no + `fit_<p>` score）**一批一调**；成员资格 R2 主规则 `J = {p | P(yes)≥0.5}`——**集合** |
| LLM 兜底腿 | `llm/decision/LlmAllocator.scala:107-132` | 单次 JSON `{"plugins": [...]}` **数组**——集合 |
| 分配编排 | `llm/decision/PluginAllocatorMachine.scala` / `core/jev/PluginAllocator.scala` | 集合透传（`Allocated(ns: List)`） |
| 声明面 | `core/tools/NodeEditTool.scala` plugins 参数 | `string \| array`（parseIn 三形态宽容）——集合 |
| 节点模型 | `core/project/ProjectTypes.scala:840` | `NodeDef.plugins: List[String]` |
| 注入面 | `core/project/PluginInjection.scala:156` | `resolvePreparation(names: List)` 逐包 traverse，集合全挂 |

**「JeV 只能单选」无结构依据**。作者感知的可能来源（考古附注）：① JeV 问句形态是逐包单选（每问 yes/no）——但批内多包可同时 yes，输出仍是集合；② 分发器提示词既有「Sparse over crowded」选配纪律读起来像「只挑一个」——那是纪律不是机制；③ AskUserQuestion 题卡缺省单选——通用问询基建，非分配链环节。宿主 `mounts` 实录（一次派发挂 2-5 包，allocation=jev）即生产集合语义的直接证据。

按任务预案「若结构上只吃单选则拆出 JeV」——**不触发**（无拆面）。落的是防回归钉：`PluginInjectionSpec` +2 例——
- *set semantics: one allocation carrying TWO packages mounts both*：分配集两包 → 同一 `<injected-plugins>` 块双 `<plugin>` 块、目录序保留；
- *set semantics: one DECLARATION of two capabilities mounts both*：`NodeDef.plugins = List("p1","p2")` 声明权威 → 两包都挂、分配面零调用。

既有 JevAllocatorSpec / LlmAllocatorSpec 已钉分配腿集合形状（两包同选、数组值域），本批不重复。

## 3. 各级提示词接线（item 3）

单点常量 `Defaults.PluginDiscoveryLine`（`[plugin discovery]` 前缀戳，与 `[reset hazard]` 同族单行形态），两半语义：查询人人可用（只读零副作用）+ **挂载权仍在派发侧**（缺能力 → 在结果中声明所需插件与用途；绝不自挂——安全边界不动）。

| 面 | 接线点 | 位置 |
|---|---|---|
| delegate brief | `DelegateTool.assembleBrief` | 铁律行之后、任务正文之前 |
| 节点首条消息 | `NodeEngineContract.protocolFootnoteFor` | 铁律之后、ProtocolFootnote 基线之前（仍前缀注入——`endsWith(ProtocolFootnote)` 身份断言零波及） |
| root Nebula | `BuiltinAgents.NebulaPromptTemplate` `## Plugins` 段新增 Query 行 | list/search/mounts 用法 + 「query first, declare by exact name」 |
| 项目分发器 | `BuiltinAgents.DispatcherPromptTemplate` §5 Capability allocation | 「allocation is a SET」明示集合语义 + CLI 查询 + 目录里也没有 → §7 升级上报 |

**subagent 只读侦察面不注入**（`BlockingSubagent.promptWithRule` 形态不变）：无 Bash，查询通道不可达，注入即纯噪音（既有「规则是纯提示成本」同款考量）。

接线钉（`SleepPollStartupRuleSpec` +2 例）：delegate brief 位形钉（discovery 行在铁律后、任务前）+ 节点脚注位形钉（discovery 行在场、endsWith 断言保持）。

## 4. 验证

- **CLI 单测**：`python3 tests/stand_plugins_cli_test.py` — 7 例 ALL PASS（list json/text 形状、search 命中归因+AND 语义+无命中、mounts 声明集/scope/台账/过滤/未挂载显式错、只读纪律零写入钉）。
- **Scala**：`sbt compile` 全绿；`SleepPollStartupRuleSpec`（11 例）、`PluginInjectionSpec`（含 2 新钉）ALL PASS；`ResetAuditSpec` 邻族回归绿。
- **scalafmt**：本批 hunk formatter-clean（新增/改动的 hunk 逐行过 formatter 零改写）。仓级 `scalafmtCheck` 有 121 个**历史遗留**未格式化文件（如 `BrowseGetCursor`、`SecretsStore`），不在本批范围、未触碰；`Defaults.scala`/`DelegateTool.scala` 自带的遗留脏注释亦保持原样（最小 diff 纪律）。
- 引擎零重启；主实例零触碰。

## 5. 边界与开放项

- CLI 的 `mounts` 读活动区 flow-map 与 chains 台账；归档区（`flow-map-archive/`）不入——挂载查询的主用途是「现在有什么在用」，历史集走归档面按需另批。
- `contentChanged`（digest 漂移）不在 CLI 状态里——需复刻引擎内容树 digest，对查询面价值低；面板/REST `GET /api/plugins` 仍是该面的权威显示。
- 分发器 Bash 自限「worktree and git only」——CLI 查询是只读 python3 调用，与该自限的读纪律同向；若作者裁定分发器不应跑它，摘 DispatcherPrompt 一行即可（单行退场）。
- 挂载权守恒：CLI 无任何写通道；delegate/节点按查询行纪律在**结果**里声明需求，挂载仍只发生在派发侧（NodeEdit plugins / spawn 分配链）。
