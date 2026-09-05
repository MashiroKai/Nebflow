# 阶段 2b·Plugins 插件体系实施报告（phase2b-plugins）

- 日期：2026-09-04
- 分支：`phase2b-plugins`（基线 main@403e0274 + 冲突收敛说明见 §3）
- worktree：`.nebflow/worktrees/phase2b-plugins`
- 蓝图：`~/.nebflow/docs/Nebflow/20260902_project-architecture-phase2-design.md`（§B 唯一权威 + §G/§H）

## 1. 实施范围与蓝图符合性对照（§B 全链）

| 蓝图条款 | 实现 | 落点 |
|---|---|---|
| §B.2 目录格式（Agent Plugins 1.0.0） | 扫描/解析/装载校验；skill 与 mcp.json 可只用其一（裁定 12）；缺 name / 双全无 / 白名单外工具 → 拒载；未知字段与目录宽容+告警（§B.8-5/7/8） | `core/plugin/PluginRegistry.scala` |
| §B.3 注册表+信任门（默认拒绝 / digest 审批清单 / 升级即重审） | SHA-256 目录内容树 digest；trust 表 `nebflow.json plugins.trust.<name>={sha256,approvedAt,scope}`；digest 失配 → untrusted；审批 REST/CLI/面板单点 | 同上 + `RestApiRoutes` `/api/plugins*` + `cli/PluginCommand` |
| §B.4 五步动态分配 | ① 注册表 ② 分发器目录注入（newTaskPrompt+reentryPrompt 双形态）③ NodeEdit plugins 参数（schema/存在性/信任校验，replace-on-provide）④ node 首条消息 skill 全文注入（${SKILL_DIR} 替换）+ MCP refcount 注册 + allowedSet 追加 ⑤ 全终态回收 | `ProjectActor` / `NodeTools` / `NodeEngine` / `PluginMcpManager` |
| §B.5 plugin MCP 独立管理器 | 独立 McpManager 实例（不复用全局 enable/disable 面）；引用计数；启动失败 → failNode（§B.5 无静默跳过）；信任运行时重验（TtlTick 30s 驱动）→ 停用 + 系统提醒 | `core/plugin/PluginMcpManager.scala` + `NodeEngine.revalidatePluginTrust` |
| §B.6 内建工具授予 org.nebflow/tools | 白名单 {WebSearch, WebFetch, Curl, Pop}（装载层强制 + buildAllowedToolSet 再过滤，纵深防御）；授予追加在全部角色过滤之后（信任门=授权权威）；编排类永不进白名单（§C.1 矩阵不破） | `PluginRegistry` + `AgentCore.buildAllowedToolSet` |
| §B.7 查询工具裁定 12 | 未新增 PluginQuery/PluginInspect 类工具——目录注入 + NodeList plugins 字段承载可见性（按裁定少造工具） | — |
| §G.2 feature flag | `nebflow.json plugins.enabled`（热读，缺省 true）：NodeEdit 忽略 plugins（不校验不存储）、目录不注入、spawn 不注入不启动 MCP、信任重验 no-op | `core/plugin/PluginsConfig.scala` |
| §H 待确认点 | 15 条已于 2026-09-02 23:05 全部裁定（按建议值执行）——无遗留「待作者确认」项 | — |

### §B.8 逐项断言对照

| §B.8 条款 | 断言 | 证据 |
|---|---|---|
| 1 分配后首条消息注入全文+SKILL_DIR 替换 | NodePluginChainSpec §B.8-1 | 录制 LlmRequest 首条 user 消息含 `<injected-plugins>` 全文 + 插件标签 + frontmatter 已去除 + `${SKILL_DIR}` 已替换为绝对路径 |
| 2 未审批默认拒绝+审批指引 | PluginRegistrySpec §B.8-2 / NodePluginChainSpec NodeEdit 校验 | resolve Left 含 `PLUGIN_UNTRUSTED` + approve 指引；NodeEdit 0-spawn 拒绝 |
| 3 升级即重审 | PluginRegistrySpec §B.8-3（resolve 侧）+ NodePluginChainSpec §B.8-3（spawn 侧 failNode） | 审批后改任一文件 → 旧 digest 拒用，错误含双 digest 对照 |
| 5 前向兼容 | PluginRegistrySpec §B.8-5 | 未知 manifest 字段/未知组件目录/未知 org.nebflow 条目 → 各自告警、不崩溃 |
| 6 refcount | PluginMcpManagerSpec §B.8-6（真实 python stdio MCP） | 两会话并发分配 → refcount=2 仅 1 份 server；先后 release → 最后一个退出 |
| 7 双全无拒载 | PluginRegistrySpec §B.8-7 | rejected 留痕含原因 |
| 8 白名单外工具拒载（Task 申请） | PluginRegistrySpec §B.8-8 | PLUGIN_TOOLS_ILLEGAL，错误点名违规工具+白名单 |

## 2. 文件改动清单

新增：
- `src/main/scala/nebflow/core/plugin/PluginRegistry.scala` — 注册表/信任门/digest/审批/catalog
- `src/main/scala/nebflow/core/plugin/PluginMcpManager.scala` — plugin MCP 引用计数生命周期 + 信任运行时联动
- `src/main/scala/nebflow/core/plugin/PluginsConfig.scala` — §G.2 flag（热读）
- `src/main/scala/nebflow/cli/PluginCommand.scala` — CLI 对等（list/approve/revoke，经 REST 单点）
- `src/test/scala/nebflow/core/plugin/PluginRegistrySpec.scala`（14 用例）
- `src/test/scala/nebflow/core/plugin/PluginMcpManagerSpec.scala`（6 用例，真实 python stdio fixture server）
- `src/test/scala/nebflow/core/plugin/NodePluginChainSpec.scala`（5 用例，NodeEdit→NodeEngine 全链）

修改：
- `agent/AgentDef.scala` — +pluginMcpServers/pluginTools（运行时注入字段，默认 Nil，不落 agent.json）
- `agent/AgentCore.scala` — buildAllowedToolSet：plugin MCP 前缀「追加」路径（注册表捞取并入，非仅保留）+ pluginTools 白名单内授予（过滤后追加）
- `agent/ContextRefresher.scala` — applyRuntimeOverrides 保活 plugin 两字段（每 turn 热重载不冲掉 spawn 时分配；flowContract 同款先例）
- `agent/SharedResources.scala` — +pluginMcp 默认实例（存量构造零改动）
- `core/project/ProjectTypes.scala` — NodeDef.plugins（末位默认 Nil，存量 flow-map 零迁移）+ NodePayload 条件序列化（NodeListKeys 断言零改动）
- `core/project/NodeEngine.scala` — spawnAndRun 重构：解析→注入→acquire→runWithAgent→guarantee 回收；revalidatePluginTrust（TtlTick 挂钩）
- `core/project/ProjectActor.scala` — Plugin Catalog 注入（新任务/重入双形态 prompt）+ TtlTick 信任重验
- `core/project/FlowMapStore.scala` — 存量 skill/mcp 加载告警（H-11①，仅展示）
- `core/tools/NodeTools.scala` — NodeEdit：plugins 参数（三形态解析/replace-on-provide/0-spawn 校验）；skill/mcp 拒写（H-11①）；归档节点禁编 plugins
- `core/tools/registry.scala` — +registeredToolNames（轻量动态名快照）
- `gateway/RestApiRoutes.scala` — GET /api/plugins（审批清单+拒载留痕）/ GET /api/plugins/catalog / POST approve / POST revoke
- `cli/CommandRegistry.scala` — 注册 PluginCommand

## 3. 冲突预检记录（第〇步）

- 在飞批次清单（开工时）：dispatch-reliability（5 文件：NodeEngine.scala + 4 个新 spec）、sandbox-readlist（**0 文件**，与 main 同点）
- 交集分析：
  - sandbox-readlist：空交（其域 SandboxPolicy/FileSandbox 本批零触碰）
  - dispatch-reliability：NodeEngine.scala 同文件——其 hunk：①:61 类字段区 ②:112-135 deliverOutTo ③:258-283 bridge spawn 重构（含 `agentDef = entry.toAgentDef` 行挪动）④:695+ deliverToNebula 去重
- **同 hunk 收敛过程**：我的插件注入链与③同函数交错（协议定义「同 hunk 不可并行」）。首轮处置尝试 ff-merge 对方提交 596acc72——**沙箱拒绝**（本会话 root=worktree，共享 .git 界外，git 元数据写被 OS 级拦截，ORIG_HEAD.lock 写失败，merge 干净中止，工作区无残留——`git status` 复核 0 脏）。
- **最终收敛方案 = 异 hunk 避让**：本批 NodeEngine 编辑全部落在③未触及区域，且与③变更行相隔 ≥4 行干净上下文：
  - 本批 hunk：spawnAndRun 重构（:198-217）+ runWithAgent 签名（:219-226）/ sessionId val 删除（:272）/ agentDef copy（:282-290）/ grant 传参——与③旧位置变更区（:258-283 内 261-277 为删除区）在 282 行处仅余 4 行未变更行（278-281），git 3-way 可无冲突合并
  - 其余编辑：UserInput text 行（:322+）距③插入点 17 行；release 挂接点经 spawnAndRun guarantee（不在③域内）
- **合并评审提示（终态更新）**：交付时发现投递批次已完成合并清库——main 已推进至 `c391415b`（Merge branch 'dispatch-reliability'，含 596acc72）。后续本分支合并回 main 时：base=403e0274、本侧=插件链 hunk、对侧=bridge 重构 hunk，同函数异区（≥4 行干净上下文隔离），预期文本级可自动合并；如出现冲突按上述行号对照手工合入，语义无冲突（对侧动 bridge 生命周期，本侧动插件注入链）
- **遗留约束申报**：沙箱 git 元数据写禁令 → 本批 commit 无法在会话内完成（见 §7 宿主侧命令）；分支停留在工作区就绪态，`git status` 干净可提交
- 轮询纪律：期间 3 次轮询（sbt 错峰同步进行），sandbox-readlist 始终 0 提交

## 4. 验收证据

### 4.1 JVM spec（25 用例全绿）

- `PluginRegistrySpec`：14/14 通过——扫描/解析/digest/信任门四态转换/白名单拒载/前向兼容/catalog 格式/flag
- `PluginMcpManagerSpec`：6/6 通过——**真实 python stdio MCP fixture**：acquire 注册 `mcp__plugin_<p>_<s>__<t>`、§B.8-6 refcount（两会话=2 计数单进程、先后 release 依次注销）、release 幂等、启动失败 Left、revalidate digest 失效停用+系统提醒/未变不停用
- `NodePluginChainSpec`：5/5 通过（NodeEdit→NodeEngine 真实链，RecordingLlm 捕获 LlmRequest）——注入全文+SKILL_DIR 替换、MCP 工具进会话清单、终态回收归零、spawn 侧旧 digest 拒用、flag off 忽略

### 4.2 全量 sbt test（worktree 内）

- `Test / test`：**Total 2251 / Passed 2231 / Failed 20 / Ignored 7**
- 20 个失败全部为环境性：`~/.nb-sbx-dataroot-*`、`~/.nebflow-pop-test-tmp.png` 等 `~/` 写创建被本会话沙箱（OS 级 seatbelt）拒绝（SandboxSpec×16 beforeEach、PopToolSpec×1）、沙箱 CPU 记账失真（BashBackgroundHardTimeout/BashActivityBridge/ShellStuckDetector×3）——失败类均为 2a 沙箱批域，与本批改动零交集
- **金标准对照**：`git archive 403e0274` 提取净基线（零插件改动）到 /tmp 独立树，同 5 个 spec 类同环境运行 → **完全相同的 20 个失败、同类异常**。证明与本批无关；插件域 spec 25/25 绿
- 日志写入面差异（LlmLogWriter Operation not permitted）为同因噪音，不影响断言

### 4.3 隔离实例 E2E（验收 5，端口 8291）

环境：NEBFLOW_HOME=/tmp/nb-2b-plugins/e2e-home（providers 复制自真实配置、safety.defaultMode=auto-all、agents/Nebula+project-dispatcher 副本、projects/e2e-plugins 注册、assembly jar 启动 `--home --port 8291 --no-browser`）；fixture plugin（skills/howto + mcp.json(python stdio) + org.nebflow/tools 申请 WebSearch）

**正向全链（真实 LLM 真实节点）**：
1. REST approve → `"digest c91aad4dfa9be221… recorded"` ✓
2. `GET /api/plugins/catalog` → 蓝图格式目录行 ✓
3. Nebula 根会话 turn（真实 GLM）→ Task(project=e2e-plugins) → 分发器会话 prompt 含 Plugin Catalog → 分发器真实调用 `NodeEdit(plugins=["e2e-plugin"])` 建节点 probe ✓
4. probe 节点 completed；**节点自身报告**（真实模型自证）：
   - 首条消息 `<injected-plugins>` 块内可见 Body Marker 原文（skill 全文注入）✓
   - `${SKILL_DIR}` 已替换为绝对路径 ✓
   - 工具清单含 `mcp__plugin_e2e-plugin_srv__echo` ✓ 与 `WebSearch` ✓
5. flow-map.json：节点 `plugins: ['e2e-plugin']` ✓（H-3①）
6. 实例日志 MCP 生命周期：`12:53:13 connected, 1 tools registered` → `12:53:39 stopped`（节点终态后回收）✓

**负向（信任门撤销）**：`POST /api/plugins/e2e-plugin/revoke` → ok；catalog 立即清空（untrusted 不注入）✓；registry 条目回落 `untrusted` ✓

**flag 回滚（§G.2）**：杀实例（lsof PID≠94384 核实）→ `plugins.enabled=false` → 重启 → 已批准插件 catalog 仍为空（分配链整体抑制）✓；approve/revoke 管理面不受 flag 影响 ✓；还原 flag
- 全程 kill 前均 `lsof -ti :8291` 核实 PID≠94384，两次实例均已停，端口释放 ✓

### 4.4 变异验红（验收 6，≥2 条）

| 变异 | 注入点 | 变异内容 | 红 | 恢复 |
|---|---|---|---|---|
| M1 信任门绕过 | `PluginRegistry.resolve` untrusted 分支 | `Left(审批指引)` → `Right(p)` | **5 个 spec 转红**：PluginRegistrySpec §B.8-2 默认拒绝 / §B.8-3 升级即重审 / §B.3 撤审；NodePluginChainSpec §B.8-3 spawn 侧 failNode / NodeEdit 校验（未审批插件被接受 `Right(created)`） | 回改后 25/25 绿 |
| M2 回收遗漏 | `NodeEngine.spawnAndRun` guarantee | 删除 `.guarantee(release(sessionId))` | NodePluginChainSpec §B.4 全链转红（完成后 sessionHolds 不清空 → waitUntil 超时；工具不注销、server 不停） | 回改后 25/25 绿 |

### 4.5 验收清单核销

| # | 验收项 | 状态 |
|---|---|---|
| 1 | 注册表扫描/解析/digest 正确；未审批默认拒绝含审批指引 | ✅ 4.1 + 4.3-1 |
| 2 | NodeEdit plugins → 注入+refcount 注册 → 终态回收 | ✅ 4.1 NodePluginChainSpec + 4.3-正向 |
| 3 | 信任门 digest 改动即重审（spec 断言） | ✅ 4.1 §B.8-3 双侧（resolve+spawn failNode） |
| 4 | org.nebflow/tools 协议扩展生效 | ✅ 4.1 + 4.3（WebSearch 出现在真实节点工具清单） |
| 5 | 隔离实例 E2E 真实 plugin→真实节点全链（含回收），NEBFLOW_HOME 隔离 + fixture + 8290 区间端口 | ✅ 4.3 |
| 6 | feature flag 回滚生效 + 变异验红 ≥2 条 | ✅ 4.3-flagoff + 4.4（M1/M2 双红，验后恢复） |
| 7 | 全量 sbt test 前台绿（worktree 内） | ✅ 2231/2251 + 4.2 金标准对照（20 环境性失败净基线同现） |


## 5. §H 标注

无——§H 15 条已于 2026-09-02 23:05 全部拍板（按建议执行），本批无新增待确认项。

## 6. 简化与偏差申报（蓝图忠实度）

1. **审批清单 diff 简化**：§B.3「与上次审批版本的 diff（文件级）」——现实现提供「新装/已变更」状态 + 当前目录树 digest；未存旧内容快照故无逐文件文本 diff（逐文件 sha 清单已够信任判断；文本 diff 面板迭代项）
2. **信任运行时重验驱动**：§B.5 未指定周期——挂接 ProjectActor.TtlTick（30s，既有 projectTtlScanner），无 server 运行时零扫描成本（快速路径）
3. **allowedSet 追加语义修正**：蓝图 §B.4-③「追加对应前缀」——原 AgentCore MCP 过滤段只有「保留」无「添加」（宇宙=agentDef.tools），按蓝图语义实现为注册表捞取并入（`pluginAppended`），这是对现状能力的必要扩展而非行为变更（agentDef.mcpServers 既有过滤语义未动）
4. **applyRuntimeOverrides 保活**：热重载冲掉 spawn 注入是既有机制（flowContract 为先例）——plugin 两字段加入保活清单，否则分配在第二个 turn 即失效（实施中发现并修复，有 spec 间接覆盖）
5. **NodePayload 会话面板注入清单摘要（H-3①前端半）**：payload 已带 plugins 字段（本批）；面板渲染属前端域，未在本批（Scala 后端）范围

## 7. 生效说明与宿主侧落地命令

- **生效提醒**：插件体系改动需重启宿主实例才生效（本批未动宿主；目录热扫描是进程内 mtime 缓存，代码级改动仍需重启加载）
- 本批不合并回 main（合并由后续触发）；分支 commit 由宿主侧执行（沙箱 .git 写禁令，见 §3）：

```bash
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/phase2b-plugins"
git add src/main/scala/nebflow/core/plugin/PluginRegistry.scala \
        src/main/scala/nebflow/core/plugin/PluginMcpManager.scala \
        src/main/scala/nebflow/core/plugin/PluginsConfig.scala \
        src/main/scala/nebflow/cli/PluginCommand.scala \
        src/main/scala/nebflow/agent/AgentDef.scala \
        src/main/scala/nebflow/agent/AgentCore.scala \
        src/main/scala/nebflow/agent/ContextRefresher.scala \
        src/main/scala/nebflow/agent/SharedResources.scala \
        src/main/scala/nebflow/core/project/ProjectTypes.scala \
        src/main/scala/nebflow/core/project/NodeEngine.scala \
        src/main/scala/nebflow/core/project/ProjectActor.scala \
        src/main/scala/nebflow/core/project/FlowMapStore.scala \
        src/main/scala/nebflow/core/tools/NodeTools.scala \
        src/main/scala/nebflow/core/tools/registry.scala \
        src/main/scala/nebflow/gateway/RestApiRoutes.scala \
        src/main/scala/nebflow/cli/CommandRegistry.scala \
        src/test/scala/nebflow/core/plugin/PluginRegistrySpec.scala \
        src/test/scala/nebflow/core/plugin/PluginMcpManagerSpec.scala \
        src/test/scala/nebflow/core/plugin/NodePluginChainSpec.scala
git commit -m "feat(plugins): phase 2b plugin system — registry/trust-gate/dispatch-injection/node-injection/refcount-mcp/trust-revalidate/flag-rollback (§B full chain)"
cp /tmp/nb-2b-plugins/report.md ~/.nebflow/docs/Nebflow/20260904_phase2b-plugins-implementation.md
cd ~/.nebflow && git add docs/Nebflow/20260904_phase2b-plugins-implementation.md && git commit -m "docs: phase 2b plugins implementation report"
```
