# 审计 #575 · 插件自动分配 + visual report×图谱联动 —— 逐层核对结论

日期 2026-10-19 · 只读审计 · 零源码改动 · 引擎实例 pid 39598（2026-10-18 22:00:04 起，构建=main@71452cd22）
原始读数快照见 `raw/`（ancestry / jev-allocation-classify / chain-rows / config-plugins-snapshot / vault-density）。

---

## 项 1 · delegate 插件自动分配「感觉没生效」

### 判定三元组

- **设计是什么**：delegate spawn 与链步（S9）空 `plugins` 声明时，由分配判定体自动选插件——三态链 Jev → LLM 小模型兜底 → fail-open（空集派发照走），审计落 `logs/jev-allocation.jsonl`，结构帧落 ChainStore `delegate-spawn` 行 `plugins`/`allocation` 字段与 `step-plugins` 行。本体 = #1226 批（7a9f42bfa，10-07）；#523（10-18 23:09）只补了 `plugins.autoAllocate` 专用闸旋钮（缺省 true=现行为逐字）与 web 面板 allocation 行。
- **实现到哪一层**：分配链本体（PluginAllocator/PluginCatalog/LlmAllocator/PluginAllocatorMachine/PluginInjection + spawn 装配点 `DelegateTool.scala:602`）**全部在运行构建 71452cd22 内**（7a9f42bfa 是其祖先，已实证）。#523 的闸旋钮与面板行**不在运行构建内**（34b6ce1c9/3e359bcc2 均非其祖先——23:09:41/50 提交，晚于 22:00:04 重启 ~70 分钟，main 侧合入点 0be2be9a5 23:10:52）。
- **根因（四选一）：已实现但有 bug（运行时两腿全败 → 空集注入）**——不是「没运行」：
  - **jev 腿必败（配置面缺口）**：`nebflow.json` `jev.keyRef="apikey_286bb7bb…"`，但 `~/.nebflow/secrets/` 下**没有以该 keyRef 命名的文件**（现有 `typesafe-api-key` 等，见 raw/config-plugins-snapshot.json）→ `JevSettings.scala:108` 每次告警 "has no secret file under secrets/ — decision calls will fail auth"（今日 stand.log 反复出现）→ jev 腿 auth 死。
  - **LLM 兜底腿 15s 预算内超时**：回落 zhipu `glm-5.3-flash`（llm.providers 已配置，回落**有**配置且能通）——但 `PluginAllocatorMachine.scala:158` 调 `LlmAllocator.fromRegistry(registry)` 未传 timeoutMs，取缺省 15000ms（`DecisionProvider.scala:283`），**无配置位**。审计 65 条=60 超时+5 成功；10-18 22:00 后 52 条=47 超时+5 成功。5 次成功全在 10-19 08:20:53（`via=llm-fallback`，S9 步级 node:tmdb/products/fees → web-search-toolkit）——证明兜底腿能通，delegate spawn 的任务文更长、15s 预算基本必超时。
  - **运行证据（ChainStore，10-18 22:00 后）**：46 条 delegate-spawn 行 **100% `allocation=fail-open, plugins=[]`**；step-plugins 行 6 条（5× web-search-toolkit/llm-fallback + 1× 空/fail-open）。作者看到的「没生效」= 每次分配都产出空集，插件从未进场。自查样本：本审计会话自身（delegate-nebflow-e33b815f）15:43:32 的分配也是 timeout→fail-open。
  - 配置闸核对：`plugins` 键下**只有 `trust`**（无 `enabled`、无 `autoAllocate`）→ 按缺省两闸全开；即便重启到 #523 后，`autoAllocate` 缺省 true，行为与现在逐字相同。**「候重启」不是本项感受的根因**（缺的旋钮未配置、缺省方向也不改变行为）。

### 候重启两面清单（针对 #523 本体）

- **重启后自然生效**：① `plugins.autoAllocate` 热读旋钮可用（`PluginsConfig.scala:31`，热读）；② 任务面板 plugins/allocation 行 + fm-detail 分配方式行（3e359bcc2，web bundle 随重启载入）。两者在现配置下均不改变分配行为（旋钮缺省开）。
- **重启后仍需修**：① jev keyRef→secrets 文件名不匹配（改 keyRef 指向既有 `typesafe-api-key`，或按 keyRef 名落 secret 文件）——jev 腿才能复活；② LLM 兜底 15s 超时预算无配置位（`PluginAllocatorMachine.scala:158`）——需代码/配置位或换更快候选模型；③（可选）「两腿全败仅 fail-open 空集、无重试/无退化告警面板」的观测面。

---

## 项 2 · visual report×图谱联动「感觉没生效」

**图谱 = wiki-graph**（项目 `<workspace>/docs/` vault 文档图谱：WikiIndex/WikiService/WikiRoutes 后端 + `web/js/wikiGraph.js` 星标页签），**不是** flow-map/链视图。设计出处两件：

1. `~/.nebflow/docs/Nebflow/20261014_wiki-graph-design.md`（实施中，本批交付）——目标⑤即「visual report 插件优化：md/html 汇报必须走插件；论文式写作；文档间引用构建图谱」；§3 插件面 = 新 skill `wiki-docs`（vault 布局/wikilink 纪律/互链义务）+ visual-report SKILL 增节。
2. `docs/design/visual-report-gate-plan.zh-CN.md`（db762c251，10-14，**自declare「仅方案，未实现」**）——把图谱体例从 skill 行为约束升级为机械门禁（R1–R13 特征 × 三挂点，推荐挂点②=完成核验器加检）。

### 判定三元组

- **设计是什么**：插件产出的 md/html 落 `<ws>/docs/` vault、按 wiki-docs 体例互链 → WikiIndex 解析入图（节点/边，html 报告也入图）→ web 图谱可视 + wikilink 点击跳转行级高亮；体例遵守靠 wiki-docs skill 注入（行为级），硬约束靠 gate-plan 机械门禁（方案层）。
- **实现到哪一层**：
  - **图谱面全实现且在运行构建内**：WikiIndex.scala / WikiService.scala / WikiRoutes.scala / `web/js/wikiGraph.js` / `web/js/viewers/markdown.js`（nf-wikilink 渲染跳转）均在 71452cd22；六个 wiki-graph 相关提交（353f903e8·visual-report 1.4.0 wiki-docs 体例、117080bd1·wiki 后端 scenario metadata/attachment nodes/html docs、bc90b5e2d·visual-report 1.5.0 scenario packs、3518c215b、4cd4d2ab3、05af870c4）祖先判定全 YES。**非候重启**。
  - **机械门禁面从未实现**：gate-plan R1–R13/挂点②无任何落码（仓内无 DocLint/vault lint；`scripts/human_doc_lint.py` 是另一规范 `visual-report-human-readable-spec.md` 的 R1–R10 人类可读面，走 `scripts/verify-human-docs.mjs`，与图谱门禁无关）。
- **根因（四选一）：已实现但有 bug——联动的内容生产半边失效，传导自项 1 + 强制半边缺失**：
  - 联动的行为级载体（wiki-docs skill）**只随 visual-report 插件注入进场**；而 10-18 22:00 后全部 delegate spawn `plugins=[]`（项 1 根因）→ 纪律从未到达会话。
  - 运行证据（产物/密度）：`Stand/docs` vault 81 md 仅 11 条 wikilink、`~/.nebflow/docs/Nebflow` 1424 md 仅 78 条——互链密度极薄；近期 delegate html 产物（如 evidence/20261019_154300_external-executor-detail-mockup）落在 `.nebflow/evidence/` 而非 docs vault，不入图；今日 stand.log 无任何真实 `/api/wiki` 请求记录（弱证据，路由未必逐请求落日志）。
  - 图谱渲染/入图/跳转实现本体无缺陷证据（代码在场、前端 10-18 上午还连修三笔 wiki-graph UI 且均在本构建内）；若说「缺陷」，是联动链缺强制与激励：无门禁（方案件未实施）+ 上游插件分配空集（项 1），二者叠加使「联动」在产物层几乎不发生。

### 候重启两面清单（本项）

- **重启后自然生效**：无——图谱实现链已在运行构建内，重启不带来增量。
- **重启后仍需修**：①项 1 的分配链（修好才有插件进场、才有 wiki-docs 纪律）；②gate-plan 挂点①脚本＋挂点②核验器接线（从未实现，方案件排期待作者裁定）；③（可选）vault 冷启动扶植（存量文档补 frontmatter/wikilink）。

---

## 附：关键 file:line / 证据位

| 断言 | 证据 |
|---|---|
| 运行构建=71452cd22（21:19:09） | ps 39598 起于 22:00:04；`raw/ancestry.txt` |
| #523 不在运行构建 | `git merge-base --is-ancestor 34b6ce1c9/3e359bcc2 71452cd22` → NO |
| #1226 分配链在运行构建 | 同上 7a9f42bfa → YES；`DelegateTool.scala:602` |
| spawn 装配点 | `src/main/scala/nebflow/core/tools/DelegateTool.scala:602`（71452cd22） |
| jev 腿死因 | `JevSettings.scala:108`（keyRef→`secrets/<name>` 不存在）；raw/config-plugins-snapshot.json |
| LLM 兜底超时 | `PluginAllocatorMachine.scala:158` + `DecisionProvider.scala:283`（15s 缺省无配置位）；raw/jev-allocation-classify.txt |
| 分配全空集 | `~/.nebflow/chains/*`：46 spawn 行全 fail-open/[]；raw/chain-rows.md |
| 图谱=wiki-graph 且在运行构建 | `core/wiki/Wiki{Index,Service}.scala`、`gateway/WikiRoutes.scala`、`web/js/wikiGraph.js`、`web/js/viewers/markdown.js`；六提交祖先全 YES |
| 门禁未实现 | `docs/design/visual-report-gate-plan.zh-CN.md` 第 1/4/187 行自declare；无 DocLint/vault-lint 落码 |
| vault 密度 | raw/vault-density.txt（Stand 11/81；Nebflow vault 78/1424） |
