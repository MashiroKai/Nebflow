# dispatcher-ctx：分发器上下文增强——插件能力目录 + 预设场景描述注入

批次：dispatcher-ctx @ main 533a2a0a ｜ 2026-09-05 ｜ 分支 `dispatcher-ctx`（worktree `.nebflow/worktrees/dispatcher-ctx`）

## 0. 概述

分发器 spawn/重入 prompt 原本只注入 plugin 结构目录（`PluginRegistry.renderCatalog`，行内容 = manifest
description——内容清单+历史叙述，回答不了「该插件让节点具备什么能力」）。本批：

1. **协议**：`plugin.json` 新增可选字段 `capability`（能力向单行句）
2. **存量**：16 插件（2 落地 + 14 skill2plugins 队列）逐一补写 capability
3. **注入**：分发器上下文改双目录——插件能力目录（capability 优先）+ 预设场景目录（PresetStore
   description，08-20 先例数据源）
4. **消费链**：分发器 system.md 锚定节指引「按任务需求对照目录选配 plugins 与 preset」
5. **验收**：单测 + 真实全环 E2E + token 量化

## 1. 协议：plugin.json `capability` 字段

- **形态**：单行字符串，句式「<主题方法论>：节点获得 <能力 A>/<能力 B>/<能力 C>」——写能力，不写
  内容清单/历史/裁定记录
- **解析**（`PluginRegistry.scala`，4 个最小 hunk，共 +10/-1 行）：
  - `PluginDef` 新增 `capability: Option[String] = None`（默认值 → 存量具名构造零破坏）
  - `knownManifestKeys` 登记 `"capability"`（不发 unknown-field 告警，§B.8-5）
  - loadPlugin 解析：`as[Option[String]].toOption.flatten.map(_.trim).filter(_.nonEmpty)`
    ——absent/非字符串/空白 → None（宽容，旧版读到也只是 unknown-field 告警不炸）
  - 构造器接线 `capability = capability`
- **渲染规则**：**capability 优先，缺省回落 description**；行格式（单行/插件）：
  `- <name>: <capability|description> [skills: <ids> | mcp: <names> | tools: <申请>]`
  尾缀结构清单保留（与 renderCatalog 同款）——`tools: WebSearch…` 本身是能力信号
- **description 不再双显**：目录读者是分发器 LLM，两字段都注入会吃掉 token 减负收益（见 §6 量化）；
  description 面向审批人（面板 GET /api/plugins 审批清单原样保留，本批未动）

## 2. 渲染架构：DispatcherContextCatalog（新对象）

`src/main/scala/nebflow/core/plugin/DispatcherContextCatalog.scala`——渲染逻辑单点收口，
**不塞 PluginRegistry**（避让 plugin-protocol 批 +579 未提交改动）：

```
render()          双段拼装：pluginSection() × presetSection() mapN，非空段空行相接，全空 → ""
pluginSection()   PluginsConfig.enabled 总闸 → PluginRegistry.scan().filter(trust.trusted)
                  （过滤链与 renderCatalog 完全同源）→ capability 优先逐行渲染
presetSection()   PresetStore.catalogLines()（read-fresh，Try 降级 Nil=段省略）→ 全部 preset
```

- 停用（总闸关）/未信任插件**不出现**（§B.3 默认拒绝）
- **兼容注（plugin-panel-redesign @a89a1e7e 未落）**：该批引入每插件开关。落地后
  `pluginSection()` 过滤链须衔接为「trusted 且 per-plugin enabled」——衔接点 =
  DispatcherContextCatalog.pluginSection 的 filter 链（landing-order 说明见 §7）
- `PluginRegistry.renderCatalog` **保留未动**：仍有 2 个消费方（REST GET /api/plugins/catalog
  面板预览 + PluginRegistrySpec），后续 plugin-panel-redesign 重塑面板时再统一

### ProjectActor 挂接（最小 hunk，1 行级）

`pluginCatalogText()` 唯一一处改指 `DispatcherContextCatalog.render()`；newTaskPrompt /
reentryPrompt 双形态注入点零改动（spawn :362 / reentry :400 两处调用自动生效）。
与 dispatcher-to-nebula@57bd7606 的叠支关系：其改动在 ActiveDispatcher/观察桥/dispatch 注入区
（:158+/:307+/:343+），与本 hunk（:229 注释区）位置独立，git 可自动合并。

## 3. 两目录格式

```
# Plugin Catalog（可分配能力包，NodeEdit 的 plugins 参数按 name 引用；能力句 = 该插件让节点具备什么能力）
- explorer-toolkit: 代码库探索与方案规划方法论：节点获得入口定位/发散检索/路径+行号举证与方案五件套设计纪律 [skills: exploration-method, solution-planning | mcp: -]

# Model Preset Catalog（模型预设场景目录，NodeEdit/agent 定义的 preset 参数按 name 引用；场景句 = 该 preset 适配的任务性质）
- deep-analyze — 深度分析场景：调研/审阅/方案设计节点适用
- general
```

- 插件每条 1 行；preset 每条 1 行（无 description 只出 name——复用 catalogLines 既有行为）
- 预设目录**不受 plugins 总闸影响**（模型预设与插件是独立特性）；读失败整段省略不炸 prompt

## 4. 机制选型：维持 prompt 目录段注入（不新增查询工具）

**依据**（对齐 skillCatalog order 800 / phase2b 先例——参考数据目录非操作指令，进 prompt 不进
工具描述）：

1. 目录是**参考数据**不是操作指令；分发器消费方式 = 建节点时对照引用 name，一次读完即用
2. 分发器单次会话 + 目录规模有界（16 插件 ≈ 0.9k token，见 §6）——新增查询工具 = 多一次 LLM
   往返 + 工具面膨胀，负收益
3. read-fresh 数据源（scan mtime 缓存 / PresetStore 每次现读）保证改后即时生效，与 skill
   目录「改后即时生效」语义一致，无需失效机制

**反方考量**（已评估）：① 目录膨胀挤占上下文——由 capability 单行句压制（较 description
口径省 51%，§6）；超限拐点（估 >100 插件或 >5k token）再议按需查询；② 非活跃插件也占行——
由信任门/总闸过滤链兜底，停用即消失。

## 5. 消费链：分发器 system.md 锚定节

`staging/system-addendum-dispatcher-ctx.md`，锚定注释
`<!-- dispatcher-ctx-rules:start / end -->` 独立小节：

- Plugin Catalog 能力目录 → 按节点任务性质对照能力句选配，宁缺勿滥
- Model Preset Catalog 场景目录 → 场景句匹配任务性质，无匹配用默认档不硬凑
- 引用纪律：按目录 name 原文引用，目录没有的不编造

**并存协调（第三批！）**：node-flowmap-slim 批与 merge-node 批（n-9dde99c7）也在同一
`~/.nebflow/agents/project-dispatcher/system.md` 追加各自锚定节。宿主落地命令必须：
**落地前重读目标文件、只追加本锚定节（marker 对内内容），任意顺序、互不覆盖**。

## 6. token 量化

### 静态（生产形态：16 插件全量目录）

| 口径 | 字符数 | 估算 token | 备注 |
|---|---|---|---|
| capability（本批） | 2,389 | ≈921 | 16 行 + 段头 |
| description（旧行为对照） | 4,796 | ≈1,862 | — |
| **净省** | **2,407** | **≈941（-51%）** | |

估算方法：CJK ≈0.66 token/字 + ASCII ≈0.25 token/字符（cl100k 类分词经验值，混合文本偏保守）。

### E2E 实测（隔离 fixture：2 插件一信一未信 + 2 preset）

`scripts/e2e-dispatcher-context-catalog.mjs` 2026-09-05 实测（stub LLM 捕获分发器 spawn 请求，
12/12 PASS，日志 /tmp/dctx-e2e.log，证据 /tmp/dctx-dispatch-dump.json + /tmp/dctx-token-evidence.json）：

| 目录段 | 字符数 | 估算 token | 内容 |
|---|---|---|---|
| Plugin 段（1 受信插件） | 136 | ≈56 | 段头 + `- cap-a: E2E 能力探针… [skills: probe \| mcp: -]` |
| Preset 段（2 preset） | 142 | ≈56 | 段头 + `- deep-analyze — 深度分析场景…` + `- general` |

外推：16 插件全量生产形态 ≈ 921 tokens（静态表）；当前真实部署（插件 2-6 个 + preset 数个）单次
spawn 注入 ≈ 0.1-0.5k token，占分发器上下文 <2%。提取锚点说明：段定位用行首 `\n# Plugin Catalog`
——裸 `# Plugin Catalog` 会命中分发器 system.md「## Plugin Catalog 认知」小节标题（E2E 初版踩坑，
断言与提取均已锚定）。

## 7. 落地顺序与撞支处置

**Scala 分支合并顺序：plugin-protocol 先落 main → 本支（dispatcher-ctx）再合并**：

1. `PluginRegistry.scala` 与 plugin-protocol 批（+579 未提交，同文件）叠——本支解析 hunk 已
   最小化（+10/-1，4 个位置独立：PluginDef 字段 / knownManifestKeys / 解析 / 构造器）；
   若 plugin-protocol 落地后 git 无法自动合并，按 `staging/patch/dispatcher-ctx-scala-minimal.patch`
   逐 hunk 重放，**逐 hunk 校验清单**：
   - [ ] `PluginDef` 含 `capability: Option[String] = None`（description 之后、author 之前）
   - [ ] `knownManifestKeys` 含 `"capability"`
   - [ ] loadPlugin 有宽容解析三连（`as[Option[String]] → flatten → trim+filter`）
   - [ ] 构造器 `capability = capability`
   - [ ] `ProjectActor.pluginCatalogText()` → `DispatcherContextCatalog.render()`
   - [ ] `sbt compile` 绿
2. ProjectActor 与 dispatcher-to-nebula@57bd7606：改动区位置独立（§2），预期 git 自动合并
3. plugin-panel-redesign 落地后：pluginSection 过滤链补 per-plugin enabled 检查（§2 兼容注）
4. **capability 改动 = plugin.json 内容变化 = 目录 digest 变化**——已审批插件（现 2 个）落地后
   会回落 untrusted，**需在 Plugin 面板/REST/CLI 重新审批**（「升级即重审」§B.8-3 语义，非 bug）

## 8. 存量 16 插件 capability 交付（staging/plugins/）

16 份 `staging/plugins/<name>/plugin.json` 全量交付（JSON 已校验）：

- **2 份已落地**（design-spec / explorer-toolkit）：基于真身 manifest 追加 capability
- **14 份队列**（academic-research, design-cards, engineering-methods, learn-anything,
  nebflow-backend-dev, nebflow-docs-prompt, nebflow-frontend-dev, nebflow-pipelines,
  nebflow-qa, phd-note, slideblocks, thesis-review, visual-report, website）：源
  manifest（/tmp/nb-skill2plugins/plugins/，skill2plugins 批 n-12e2b5f9）原样 + capability
- **双分支处理**：skill2plugins 先落 → cp 本批 14 份追加 capability（本批文件 = 其源 + capability
  字段，逐字段等价）；skill2plugins 未落 → 本批 14 份可先 cp（本身是合法完整 manifest），
  其批落地时以本批文件为基线核对其余字段

宿主落地命令（三处 cp 全集见本文档配套结果文本，此处存档插件目录部分）：

```bash
# 1) 插件 manifests（16 份；capability 版本）
cd "<主仓>/Nebflow"   # main 侧执行，非 worktree
for d in design-spec explorer-toolkit academic-research design-cards engineering-methods \
         learn-anything nebflow-backend-dev nebflow-docs-prompt nebflow-frontend-dev \
         nebflow-pipelines nebflow-qa phd-note slideblocks thesis-review visual-report website; do
  cp ".nebflow/worktrees/dispatcher-ctx/staging/plugins/$d/plugin.json" \
     -T "$HOME/.nebflow/plugins/$d/plugin.json" 2>/dev/null || \
  { mkdir -p "$HOME/.nebflow/plugins/$d"; cp ".nebflow/worktrees/dispatcher-ctx/staging/plugins/$d/plugin.json" "$HOME/.nebflow/plugins/$d/plugin.json"; }
done
# 2) 重审（capability 改动 → digest 变化 → 「升级即重审」）
for p in design-spec explorer-toolkit; do curl -X POST -H "Authorization: Bearer $(cat ~/.nebflow/auth.json)" http://localhost:8080/api/plugins/$p/approve; done
# 3) plugins/ 目录入 ~/.nebflow git repo
cd ~/.nebflow && git add plugins/ && git commit -m "plugins: 16 插件 manifest 补 capability 字段（dispatcher-ctx 批）"
```

⚠️ 14 份队列若 skill2plugins 批落地命令包含整目录 cp（会覆盖本批 capability 版），落地顺序应为
**skill2plugins 先、本批 capability 后**（或其批直接采用本批 staging 文件）。

## 9. .gitignore（spec 入 track）

照 memory-plan 批配方：`.nebflow/` → `.nebflow/*` + `!.nebflow/Spec/`。本文件即经此配方入
git track。⚠️ 与 memory-plan / merge-node 批同文件 hunk——宿主合并时**各自基于 main 版本重放**
（三方同配方，内容一致，重放任意顺序无冲突语义）。

## 10. E2E 与测试

- **单测**：`DispatcherContextCatalogSpec`（8 断言组）——capability 优先 / 缺省·空白回落 /
  untrusted 不出现 / preset name—description 与 name-only / 双段拼装 / 总闸只压制插件段
  （预设段独立）；capability 不发 unknown-field 告警
- **E2E**：`scripts/e2e-dispatcher-context-catalog.mjs`——stub LLM（内嵌 OpenAI 兼容流式 mock，
  捕获全部请求）+ 隔离 NEBFLOW_HOME fixture（cap-a 审批/beta-untrusted 从不审批 + deep-analyze
  含 description/general 不含）+ 隔离实例（sbt run --home/--port 8097，非宿主）。
  真实全环：REST turn → Nebula → Mail(→e2e-proj) → ProjectActor.TriggerDispatcher →
  分发器 spawn → newTaskPrompt 打到 stub → 断言上下文内容（§10.1）+ token 实测回填 §6
- 清理纪律：trap cleanup 模式——子进程登记 → EXIT/信号逐 kill（进程组）+ lsof 端口复查 +
  fixture home 删除（KEEP=1 可保留）

### 10.2 E2E 踩坑记录（重跑前必读）

1. **fixture 模型须显式 `maxTokens`/`contextWindow`**——ModelConfig 用裸 deriveDecoder，
   无默认值回退，缺字段 = 实例启动即 Config parse error
2. **分发器请求匹配必须双标记**（`你是项目` + `任务分发器`）——单查「任务分发器」会误中
   Nebula 系统提示词（其 standalone-agents 目录段含「project-dispatcher: 项目任务分发器…」）
3. **段定位锚点带行首 `\n`**——裸 `# Plugin Catalog` 命中分发器 system.md「## Plugin Catalog
   认知」小节标题，token 提取与断言都会失真
4. 监控器对 30s 无 stdout 的后台任务发 SIGTERM——重定向到文件会触发；用 `tee` 保持
   stdout 流动；被杀运行可能留下孤儿 fixture home（trap 没机会跑），跑完顺手清

### 10.1 E2E 断言清单

1. 分发器 spawn 请求被 stub 捕获
2. `- cap-a: <capability>` 行出现；cap-a description 被压制
3. `beta-untrusted` 整体不出现（未信任）
4. `- deep-analyze — <场景句>` 出现；`- general` 只出 name（无 `general —`）
5. 两段头部齐全、插件段在前
6. token 量化实测输出（plugin/preset 两段 chars + 估算 token）
