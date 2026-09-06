# CI run 34017870920 — 40 既有失败测试修复分析（四簇）

- **Run**: `Beta Release` v1.4.1-beta.56, job `Build & Test (jar)`
- **基线**: main HEAD `74eec77c`（worktree `.nebflow/worktrees/修复-beta.56CI测试漂移四簇-v2`）
- **失败**: `Total 2521, Failed 40, Errors 0, Passed 2475, Skipped 6, Ignored 7`
- **本文件**: 失败清单留痕 + 根因分类 + 修复处置。

## 一、失败清单（40 条，按 spec 归并）

| # | Spec | 用例 | 失败类型 | 根因分类 |
|---|------|------|----------|----------|
| 1 | AgentControlE2ESpec | C1 cancel | waitUntil 10s | ① Delegate 退役 |
| 2 | AgentControlE2ESpec | C2 restart | waitUntil 10s | ① Delegate 退役 |
| 3 | DeleteSessionCascadeSpec | R1 GREEN | waitUntil 30s | ① Delegate 退役 |
| 4 | DeleteSessionCascadeSpec | R2 RED | waitUntil 30s | ① Delegate 退役 |
| 5 | NodePluginChainSpec | §E.3 preset | NODE_AGENT_RETIRED | ② plugin 架构（已修） |
| 6 | SandboxSpec | A.2 writableRoots | /tmp 未归一 | ③ sandbox 跨平台（已修） |
| 7 | SandboxSpec | A.2 canonicalize | /tmp→/private/tmp | ③ sandbox 跨平台（已修） |
| 8 | SandboxSpec | A.8-1 /etc/hosts | /private/etc 硬编码 | ③ sandbox 跨平台（已修） |
| 9-15 | CompletionGateSpec | engine ①-⑧ | waitUntil 20s | ① engine（待查） |
| 16 | AgentConvergenceSpec | Glob default root | None.get | rg env（CI 装 rg） |
| 17 | AgentConvergenceSpec | Grep default root | None.get | rg env（CI 装 rg） |
| 18 | NodeMessageSpec | S6-TOOL | NODE_TERMINAL_NO_MESSAGE | ④ 终态拒绝（已修） |
| 19 | StuckDelegateReleaseSpec | #31 | waitUntil 15s | ① Delegate 退役 |
| 20 | StuckDelegateReleaseSpec | #418 | waitUntil 15s | ① Delegate 退役 |
| 21-23 | DispatcherContextCatalogSpec | capability 优先/缺省/双段 | 插件目录空 | ② plugin 架构（已修） |
| 24 | NestedDelegateNotifySpec | #25 | waitUntil 10s | ① Delegate 退役 |
| 25 | NodeSchemaSlimSpec | E2E | waitUntil 60s | ① engine（待查） |
| 26-29 | ToolLoaderSpec | 4 个 per-agent 层用例 | 注册失败/None.get | ④ per-agent 层退役（已修） |
| 30-40 | GlobToolSpec | crash1-5/description×2/form1-3 | rg not found | rg env（CI 装 rg） |

## 二、根因分类（修正任务初始假设）

### ② plugin 架构对齐（已修，3+1=4 用例）
- **DispatcherContextCatalogSpec**（#21-23）：fixture `plugin.json` 用旧 `$schema`
  `https://agent-plugins.org/schema/1.0.0`；PluginRegistry 拒绝非 canonical
  （`PLUGIN_SCHEMA_UNSUPPORTED`）→ 插件全拒载 → 插件目录段被压制 → 3 个目录断言失败。
  **修复**：fixture 指向 `PluginRegistry.CanonicalSchema`。
- **NodePluginChainSpec §E.3**（#5）：`nodeEdit` 传退役 `agent` 参数（`NODE_AGENT_RETIRED`）；
  节点执行 general agent。**修复**：删 `agent` 参数并补此前缺失的 `description`。

### ③ sandbox /tmp 归一化仅适配 macOS（已修，3 用例）
- **SandboxSpec**（#6-8）：断言硬编码 darwin 形态——
  - A.2：`!contains("/tmp")`（darwin /tmp 符号链归 /private/tmp；Linux 独立真实目录）。
  - A.2 canonicalize：`= "/private/tmp"`。
  - A.8-1：消息含 `/private/etc/hosts`（darwin /etc 符号链）。
  **修复**：改用 `SandboxPolicy.canonicalize(...)` 按当前平台基准断言（跨平台）。

### ④ ToolLoader·Glob / NodeMessage（已修，4+1=5 用例）
- **ToolLoaderSpec**（#26-29）：断言 `agents/*/tools`（含 team/flow 嵌套）**被扫描**；
  实际 per-agent 工具层 2026-09-06 已退役（ToolLoader.scala 注释），不再加载。
  **修复**：翻转断言为「不再扫描/不再加载」。
- **NodeMessageSpec S6-TOOL**（#18）：seed 节点为 `Completed`（终态）却期待合法追加；
  引擎对终态节点拒收消息（`NODE_TERMINAL_NO_MESSAGE`）。**修复**：分身—Pending 节点走
  合法追加、独立 Completed 节点走终态拒绝断言。

### rg-absent（环境性，12 用例；CI 装 rg 修复）
- **GlobToolSpec**（#30-40，10 用例）+ **AgentConvergenceSpec**（#16-17，2 用例）：
  CI ubuntu-latest 测试 JVM PATH 无 `rg`（RgHelper 查 bundled/PATH/~/.nebflow/bin 均空）
  → Glob/Grep 返回 `ToolError(rg not found)` → `isRight`/`toOption.get` 断言失败。
  **本地全绿**（macOS 有 `/opt/homebrew/bin/rg`）。**非 default-root 漂移**（任务④假设不成立）。
  **修复**：三个 workflow 测试任务加 `sudo apt-get install -y ripgrep`（Glob/Grep 运行时依赖）。

### ① 异步 waitUntil 超时簇（未修，待决策，≈16 用例）
两层独立根因：

**(a) Delegate 退役（7 用例）**：AgentControlE2ESpec(×2)、DeleteSessionCascadeSpec(×2)、
StuckDelegateReleaseSpec(×2)、NestedDelegateNotifySpec(×1)。
Nebula 是 `ConvergedAgentName` → `agent.json` tools 声明整体失效（base=∅）；`Delegate`
已从 `NebulaOrchestrationTools` 移除（AgentCore.scala:2200「−Mail/Delegate/FlowTrigger/
FlowExecute 旧体系退役」）→ `buildAllowedToolSet` 过滤 Delegate toolcall（日志
`Tool calls filtered (not in allowed set): Delegate`）→ 子代理从不 spawn → `delegate-*`
永不注册 → waitUntil 超时。
**这是刻意的架构退役，不是 bug**。修复需决策：恢复 Delegate 入 Nebula 固定面（违背作者
裁定）或把 6 个 fixture 重写为 project-node/SubTask 模型（大改）。

**(b) engine 测试（8 用例）**：CompletionGateSpec engine ①-⑧(×7)、NodeSchemaSlimSpec E2E(×1)。
用 NodeEngine+stubRunner 建节点后 `waitStatus(...)` 超时——节点状态不流转；gate 警告
`git exploded / not a git repository`（非 git workspace 上跑真 git，stubRunner 未接管）。
待进一步排查：是真产品 bug（engine 忽略 gateRunner）还是 fixture 不匹配。

## 三、处置与提交

已修 & 提交（worktree 内）：
- `8603f163` fix(plugin): canonical $schema + drop retired agent param（②）
- `d46c8af1` fix(sandbox): SandboxSpec 跨平台（③）
- `1978d3fc` fix(tools): ToolLoader per-agent 退役 + NodeMessage 终态分身（④）
- `9961e49c` ci: 安装 ripgrep（rg env，12 用例）

本地验证（sbt testOnly 定向，全绿）：
- DispatcherContextCatalogSpec / NodePluginChainSpec / SandboxSpec(带上层逃生门
  NB_SANDBOX_SPEC_DATAROOT) / ToolLoaderSpec / NodeMessageSpec / GlobToolSpec /
  AgentConvergenceSpec —— 合并跑 `Passed 109, Failed 0`。
- AgentControlE2ESpec、CompletionGateSpec 单独跑，确认仍红（根因如上）。

未修（待合并/发布链裁定）：① (a) Delegate 退役 7 用例 + ① (b) 8 用例 engine 排查。
