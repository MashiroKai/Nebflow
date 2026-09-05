# skills → plugins 蒸馏迁移映射清单（标准源）

- 批次：skill2plugins（Caller: project-dispatcher，作者 2026-09-05 08:40 裁定「skills 体系整体并入 plugins」）
- 基线：主仓 main@533a2a0a；本文件为本批唯一标准源（映射表 + 逐项判定依据 + 插件分组与理由 + 注入体量评估）
- 协议依据：`~/.nebflow/docs/Nebflow/20260902_project-architecture-phase2-design.md` §B.2/§B.4 + `PluginRegistry.scala` 实际装载语义（已知 manifest 键集、顶层条目白名单、SKILL.md 解析）

## 1. 全量盘点（37 个 skill，零遗漏）

`find ~/.nebflow/skills -iname "skill.md"` 全深度盘点 = **37 个**（分发器预侦察的 29 为大小写敏感结果，另有 8 个小写 `skill.md`）：

| 组 | 条目 |
|---|---|
| 顶层大写 SKILL.md（25） | academic-pdf-fallback-chain, card-design, design-system, flow-execute, memory-consolidation, merge-pipeline, nebflow-backend, nebflow-cache, nebflow-docs, nebflow-frontend, nebflow-prompt-engineering, nebflow-qa-backend, nebflow-qa-frontend, nebflow-tool-dev, parallel-research, release-pipeline, review, skill-creator, slideblocks, slideblocks-backend, slideblocks-docs, slideblocks-frontend, visual-report, website-design, website-frontend |
| nebflow/ 嵌套大写（4） | frontend-verification, isolated-smoke-verification, verification-rigor, visual-style |
| 小写 skill.md（8，预侦察「疑似无 SKILL.md」甄别结论） | _example(28行,系统再生模板), academic-survey(484), deploy-website(53), grill(112), guizang-social-card-skill(300,独立git repo 66文件), learn-anything-skill(229), phd-note(273), thesis-review(566) |

8 个「疑似」目录逐一甄别：**全部为真 skill**（小写文件名致 depth≤3 大小写敏感 find 漏检），无空壳/纯资源目录。特殊归属证据：
- `_example`：`.gitignore` 尾注 `system-generated starter template, recreated on first run` + 显式 `skills/_example/` 忽略行 → 系统再生模板，未 tracked；
- `guizang-social-card-skill`：`.gitignore` 尾注 `skills that carry their own independent git repos (versioned separately)` → 第三方引入独立 git repo（LICENSE/README.en.md/agents/openai.yaml/references×20/assets）。

## 2. 映射表（14 个新插件，33 个 skill 直接转）

判定分布：**直接转 33 ｜ 归档 1（flow-execute）｜ 待裁定保留 3（skill-creator, memory-consolidation, _example）**。无内容合并重写（主题聚合按插件分组实现，skill 正文一律保真迁移）。

| # | 插件（v1.0.0） | skills（原路径 → 插件内 skills/<dir>） | 行数 | 判定与依据 |
|---|---|---|---|---|
| 1 | nebflow-backend-dev | nebflow-backend, nebflow-cache, nebflow-tool-dev | 157 | 同域聚合：主仓 Scala 开发（怎么写）+ cache 命中率 + 工具系统 |
| 2 | nebflow-frontend-dev | nebflow-frontend, nebflow/visual-style→visual-style, design-system | 193 | 同域聚合：Nebflow 客户端 UI 三层（实现规范/视觉硬裁定/范式案例库）；design-system 归此不归 design-cards（其内容是 Nebflow 产品 UI 案例库，非卡片制作），design-spec 跨插件按名引用保持成立 |
| 3 | nebflow-docs-prompt | nebflow-docs, nebflow-prompt-engineering | 112 | 同域聚合：文档规范 + 提示词工程 |
| 4 | nebflow-qa | nebflow-qa-backend, nebflow-qa-frontend, nebflow/frontend-verification→frontend-verification, nebflow/isolated-smoke-verification→isolated-smoke-verification, nebflow/verification-rigor→verification-rigor | 432 | 5 skills≤6 上限、432<600 红线：五维验证域全家族（qa 双域+三个方法论深化），互引最密整体迁移消除跨包引用 |
| 5 | nebflow-pipelines | merge-pipeline, release-pipeline, review | 446 | 工程闸门流水线（合并/发版/审查）；review 归此不归 nebflow-qa（并入即 758 行超红线，且 review 是 check-fix 循环方法论非验证域） |
| 6 | design-cards | card-design, guizang-social-card-skill（去 .git） | 454+资源 | 卡片/社交图制作域；guizang 38 文件含 references/assets 整体随迁（skill 目录附属资源不参与装载校验，注册表只查顶层条目） |
| 7 | visual-report | visual-report | 245 | **单 skill 理由**：通用横切汇报能力（matplotlib/graphviz/plotly→Pop），全项目复用；并入 design-cards 则 699 行超红线 |
| 8 | website | website-design, website-frontend, deploy-website | 130 | nebflow-website 项目三件套（前端/视觉/部署） |
| 9 | slideblocks | slideblocks, slideblocks-backend, slideblocks-frontend, slideblocks-docs | 287 | slideblocks 项目全家桶（deck 制作+平台三域） |
| 10 | engineering-methods | grill, parallel-research | 177 | 工程方法论：架构设计（grill）与并行调研（parallel-research）实践上前后衔接（调研→选型→架构） |
| 11 | learn-anything | learn-anything-skill | 229 | **单 skill 理由**：独立通用陪学能力域（学习导师），与工程/学术产出类无主题聚合；229 行自足 |
| 12 | academic-research | academic-survey, academic-pdf-fallback-chain | 511 | 学术调研生产链：survey 编排调研-校验迭代，执行中直接消费 pdf fallback chain（论文 PDF 获取），聚合力最强组合 |
| 13 | thesis-review | thesis-review | 566 | **单 skill 理由**：566 行自足四层面审阅工作流，与任何组合必超 600 红线 |
| 14 | phd-note | phd-note | 273 | **单 skill 理由**：持续型个人知识库能力（素材积累），并入 academic-research 则 784 行超红线 |

**体量评估**：14 插件全部 <600 行红线，最大 thesis-review 566、次大 academic-research 511；常规粒度 2-4 skills，nebflow-qa 5 skills（≤6 上限内）。注入语义（§B.4 分配即全文注入节点首条消息）下全部可控。

**既有插件**：explorer-toolkit / design-spec **本体不动**，唯 design-spec 因「user 层 skill」配合关系段失效做最小必要修订（见 §4 引用修复 #13）。

## 3. 归档与待裁定

### 归档（随宿主命令 ③ 删除，git 历史可复活）
- **flow-execute**（244 行）：flows 体系退役证据 ①编排主链路已迁 Project+Node 分发器（AGENTS.md:24「按方向组建节点」、20260902 phase2 设计全链路五步）；②flows/ 仅存量封存（`presentation-prep.archived` 改名归档先例，无新增 flow 开发）；③FlowExecuteTool.scala 虽在役（向后兼容），但其受众「FlowExecute DAG 编写者」已不存在于主链路——flow 执行为引擎内部行为，flow 定义内 agent 不经 skill 目录注入。244 行注入体量 vs 零受众，不占插件目录。

### 待裁定（保留 skills/ 不删，不迁移）
- **skill-creator**（94 行）：涉 meta 系统——skill 体系并入 plugins 后，「skill 创作规范」本身需重写为 plugin-skill 规范（承载层从 skills/ 白名单层变为 plugins/<name>/skills/，目录/frontmatter 语义变化），超出纯蒸馏迁移批次，留作者裁定。
- **memory-consolidation**（62 行）：Nebula 专属记忆维护方法论（2026-08-31 裁定 team agent 无记忆；node 注入链路无其触发场景），非分发器可分配能力，归 Nebula 侧处理。
- **_example**（28 行）：系统再生 starter 模板（首次运行再生，物理删除无意义），保留原地，未 tracked 不涉 git rm。

## 4. 引用修复清单（逐条，迁移时实施）

| # | 文件 | 位置 | 原文 → 修复 | 类型 |
|---|---|---|---|---|
| 1 | nebflow-backend | when_to_use | 「与 nebflow/verification-rigor（验证方法论）互补」→「与 nebflow-qa 插件的 verification-rigor（验证方法论）互补」 | 跨插件 |
| 2 | nebflow-frontend | when_to_use | visual-style→「同插件 visual-style」；frontend-verification→「nebflow-qa 插件的 frontend-verification」 | 同包+跨插件 |
| 3 | nebflow-frontend | :50 | 「见 `nebflow/visual-style`」→「见同插件 `visual-style`」 | 同包 |
| 4 | nebflow-frontend | :75 | 「见 `nebflow/frontend-verification`」→「见 `nebflow-qa` 插件的 `frontend-verification`」 | 跨插件 |
| 5 | nebflow-qa-backend | when_to_use | 「见 nebflow/verification-rigor 与 nebflow/isolated-smoke-verification」→「见同插件 verification-rigor 与 isolated-smoke-verification」 | 同包 |
| 6 | nebflow-qa-frontend | when_to_use | frontend-verification→「同插件」；「视觉硬规则见 nebflow/visual-style」→「见 nebflow-frontend-dev 插件的 visual-style」 | 同包+跨插件 |
| 7 | review | :197 | 「对照 `nebflow/visual-style`」→「对照 `nebflow-frontend-dev` 插件的 `visual-style`」 | 跨插件 |
| 8 | visual-report | :16/:197/:214 | 「参见 `card-design` skill」×3 →「参见 `design-cards` 插件的 `card-design`」 | 跨插件 |
| 9 | website-frontend | when_to_use | 「deploy-website skill」→「同插件 deploy-website」 | 同包 |
| 10 | design-system | when_to_use | 「与 nebflow/visual-style 分层」→「与同插件 visual-style 分层」 | 同包 |
| 11 | 4 个 nebflow/ 前缀 skill | frontmatter name | `nebflow/visual-style`→`visual-style`、`nebflow/frontend-verification`→`frontend-verification`、`nebflow/isolated-smoke-verification`→`isolated-smoke-verification`、`nebflow/verification-rigor`→`verification-rigor`（插件 id=<plugin>/<dir> 已含命名空间，不同步则 id/name 不一致） | name 规范化 |
| 12 | 7 个小写文件 | 文件名 | `skill.md`→`SKILL.md`（academic-survey/deploy-website/grill/guizang-social-card-skill/learn-anything-skill/phd-note/thesis-review；§B.2 canonical，registry 有容错但按规范） | 规范化 |
| 13 | design-spec（既有插件） | :28 + §6 | 「user 层 skill」配合关系段改写为「跨插件按名引用」并注明去向（visual-style/design-system→nebflow-frontend-dev 插件，card-design→design-cards 插件）；:28 `nebflow/visual-style`→`visual-style`（nebflow-frontend-dev 插件）。宿主命令单独落地；注意改文件即变 digest，若 author 曾 approve 过 design-spec 需重审（机制内建行为） | 既有插件同步 |
| 14 | guizang-social-card-skill | 目录 | 去 `.git/`（嵌入 repo 会使 git add 产生 gitlink 破坏白名单跟踪；源 repo 在 GitHub，本地历史随退役弃置）；`agents/openai.yaml` 保留（skill 内层不校验，第三方配置惰性存在） | 结构处置 |

## 5. plugin.json 字段策略（与实现对齐）

`{$schema canonical, name, version:"1.0.0", description, author:"Nebula distill"}`；description 含能力语义 + skills 清单 + 「无 mcp.json、无工具扩展」注明（§B.4 catalog 分发语义）。

⚠️ **与设计文档 §B.2 的实现差异**：设计文档建议填 `author/license/keywords`，但 `PluginRegistry.scala:244` knownManifestKeys 仅认 `$schema/name/version/description/author/extensions`——`license`/`keywords` 会触发「ignored unknown manifest field」告警，破坏本批「warnings 为空」零告警验收。以实现为准：**不填 license/keywords**，差异记录在案供 plugin-protocol 批次参考。

## 6. 迁移记录（实施于 2026-09-05）

**staging 树**：`/tmp/nb-skill2plugins/plugins/<14 插件>/`（plugin.json + skills/…）+ `/tmp/nb-skill2plugins/design-spec-patch/`（既有插件补丁）。

自检结果：14 个 plugin.json 全部 JSON 可解析 ✓；33 个 SKILL.md frontmatter 均含 `---` 界符与 name 字段 ✓；残留引用扫描（nebflow/ 前缀四兄弟、user 层 skill、skills/nebflow）零命中 ✓；各插件 SKILL.md 合计行数与 §2 映射表逐一相符 ✓；guizang 38 文件完整随迁且 .git 已剥离 ✓。

逐 skill 处置与引用修复核对：

| 原路径 | 去处 | 修复 | 宿主落地 |
|---|---|---|---|
| skills/nebflow-backend | nebflow-backend-dev/skills/nebflow-backend | 修复#1 | 待宿主命令② |
| skills/nebflow-cache | nebflow-backend-dev/skills/nebflow-cache | 无引用需修 | 待② |
| skills/nebflow-tool-dev | nebflow-backend-dev/skills/nebflow-tool-dev | 无引用需修 | 待② |
| skills/nebflow-frontend | nebflow-frontend-dev/skills/nebflow-frontend | 修复#2#3#4 | 待② |
| skills/nebflow/visual-style | nebflow-frontend-dev/skills/visual-style | 修复#11(name)+#10 关联 | 待② |
| skills/design-system | nebflow-frontend-dev/skills/design-system | 修复#10 + 正文:14 同插件化 | 待② |
| skills/nebflow-docs | nebflow-docs-prompt/skills/nebflow-docs | 无引用需修 | 待② |
| skills/nebflow-prompt-engineering | nebflow-docs-prompt/skills/nebflow-prompt-engineering | 无引用需修 | 待② |
| skills/nebflow-qa-backend | nebflow-qa/skills/nebflow-qa-backend | 修复#5 | 待② |
| skills/nebflow-qa-frontend | nebflow-qa/skills/nebflow-qa-frontend | 修复#6 | 待② |
| skills/nebflow/frontend-verification | nebflow-qa/skills/frontend-verification | 修复#11(name) | 待② |
| skills/nebflow/isolated-smoke-verification | nebflow-qa/skills/isolated-smoke-verification | 修复#11(name) | 待② |
| skills/nebflow/verification-rigor | nebflow-qa/skills/verification-rigor | 修复#11(name) | 待② |
| skills/merge-pipeline | nebflow-pipelines/skills/merge-pipeline | 无引用需修 | 待② |
| skills/release-pipeline | nebflow-pipelines/skills/release-pipeline | 无引用需修 | 待② |
| skills/review | nebflow-pipelines/skills/review | 修复#7 | 待② |
| skills/card-design | design-cards/skills/card-design | 无引用需修 | 待② |
| skills/guizang-social-card-skill | design-cards/skills/guizang-social-card-skill | 修复#14（去.git，38文件随迁；skill.md→SKILL.md） | 待②（宿主 rm 原 untracked 目录） |
| skills/visual-report | visual-report/skills/visual-report | 修复#8（×3 处 replace_all） | 待② |
| skills/website-design | website/skills/website-design | 无引用需修 | 待② |
| skills/website-frontend | website/skills/website-frontend | 修复#9 | 待② |
| skills/deploy-website | website/skills/deploy-website | 修复#12（改名） | 待② |
| skills/slideblocks | slideblocks/skills/slideblocks | 无（assets/references/scripts 26 文件随迁，含 4 个未提交修改的 workbench 文件——staging 取当前盘上态） | 待② |
| skills/slideblocks-backend | slideblocks/skills/slideblocks-backend | 无引用需修 | 待② |
| skills/slideblocks-frontend | slideblocks/skills/slideblocks-frontend | 同插件化（slideblocks-frontend when_to_use） | 待② |
| skills/slideblocks-docs | slideblocks/skills/slideblocks-docs | 无引用需修 | 待② |
| skills/grill | engineering-methods/skills/grill | 修复#12（改名） | 待② |
| skills/parallel-research | engineering-methods/skills/parallel-research | 无引用需修 | 待② |
| skills/learn-anything-skill | learn-anything/skills/learn-anything-skill | 修复#12（改名；references/assets 10 文件随迁） | 待② |
| skills/academic-survey | academic-research/skills/academic-survey | 修复#12（改名） | 待② |
| skills/academic-pdf-fallback-chain | academic-research/skills/academic-pdf-fallback-chain | 无引用需修 | 待② |
| skills/thesis-review | thesis-review/skills/thesis-review | 修复#12（改名） | 待② |
| skills/phd-note | phd-note/skills/phd-note | 修复#12（改名） | 待② |
| skills/flow-execute | **归档，不迁移**（§3 证据） | — | 待③ git rm |
| skills/skill-creator | **待裁定，保留原位** | — | 不动 |
| skills/memory-consolidation | **待裁定，保留原位** | — | 不动 |
| skills/_example | **待裁定（系统再生），保留原位** | — | 不动（untracked） |

既有插件：design-spec 补丁（修复#13：:28 + §6 改写为跨插件按名引用并注明去向）经 `/tmp/nb-skill2plugins/design-spec-patch/` 由宿主命令单独落地（digest 变更→若曾 approve 需重审，机制内建）；explorer-toolkit 零引用零改动。

主仓改动：.gitignore:18 `.nebflow/` → `.nebflow/*` + `!.nebflow/Spec/`（check-ignore 实测验证）；AGENTS.md:22 一行指向本标准源。
