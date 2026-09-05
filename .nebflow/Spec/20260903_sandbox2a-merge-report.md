# sandbox-2a 合并收编报告

- 日期：2026-09-03
- 执行节点：barrier「sandbox-2a 分支合并收编进 main」（上游「验证-2a沙箱底座·重派4」n-3d8128f9 八项验收全 PASS）
- 主仓：`/Users/dev/Claude code/Nebflow`，全程 main 分支操作
- **Merge commit：`69313552`**（main 新 HEAD）

## 一、基线盘点

| 项 | 任务书预期 | 实际 | 判定 |
|---|---|---|---|
| main HEAD | 45eb83d6 | 45eb83d6（"Fix FlowMap engine: 多 barrier 提前触发/归档补投递/清场工具缺口三合一"） | 一致 |
| sandbox-2a | 22d12ce7 | 22d12ce75991f2617b27f33cc09b4a4a5040017b | 一致 |
| worktree 状态 | 冻结干净 | `git status --porcelain` 零输出 | 一致 |
| merge-base | 76aa82d2 | **2f32d0a6**（2026-09-02 "fix(gateway): 文件浏览器 listDir 逐条目故障隔离"） | **偏差** |
| 工作区 | 仅 `?? CONTRIBUTING.md` / `?? CONTRIBUTING.zh-CN.md` | 相同（全程未动） | 一致 |

**merge-base 偏差说明**：`76aa82d2` 并非分叉点，而是 main 侧中段提交（`git merge-base --is-ancestor 76aa82d2 2f32d0a6` 为否）。真实分叉点 `2f32d0a6` 比 76aa82d2 更早，意味着潜在冲突写入面比任务书预判更大——已按真实 merge-base 重新盘点。

**两侧写入面**（以 2f32d0a6 为基）：
- sandbox-2a 侧：6 commits，22 文件 +1159/-143
- main 侧：18 commits（deps 边合并 5eb9e815、面板修复 6c9daba7、引擎三修 8554f566/d1222a63/45eb83d6、分发器单例化 76f3808c、web 修复等），34 文件 +3945/-424

**交集文件（3 个，全部双方动过）**：
1. `src/main/scala/nebflow/core/node/NodeRunner.scala`（沙箱 +6/-1：spawn 参数加 `sandboxEnabled` 并透传 `p.sandboxEnabled`；main +19：面板取消实时刷新接线）
2. `src/main/scala/nebflow/core/project/NodeEngine.scala`（沙箱 +5/-1：dev/merge 节点 spawn 置 `sandboxEnabled = true`；main +122/-：引擎三修事务性复核/liveness/reapStaleRunning/deps 闸门）
3. `src/main/scala/nebflow/core/project/ProjectActor.scala`（沙箱 +6/-1：分发器 spawn 置 `sandboxEnabled = true`；main +219/-：分发器单例化等）

任务书预判的 NodeTools.scala / TaskStuckWatcher.scala **不在沙箱侧写入面**（main 侧单方改动），无冲突风险。

**冲突预判 vs 实际**：预判 3 交集文件可能冲突 → **实际零冲突**，`git merge --no-ff`（ort 策略）三文件全部自动合并成功。与上游验证报告合并提示（"与 main 交集仅 NodeEngine.scala 异 hunk，上游实测零冲突"）一致。

## 二、合并与冲突解决

- 合并命令：`git merge sandbox-2a --no-ff -m "Merge sandbox-2a: 阶段 2a 沙箱底座（SandboxPolicy/FileSandbox/Seatbelt + 五工具过闸 + ToolContext 链，已过独立验证）"` → 一次成功，**无冲突文件清单（空）**，无需手工融合，无 [merge-fix]。
- **语义零丢失双向验证**（无文本冲突 ≠ 无语义丢失，故做并集证明）：
  - `git diff 45eb83d6..69313552` = **恰为沙箱侧 22 文件 +1159/-143**；3 个交集文件的 hunk 与沙箱侧原始 diff 逐块一致（仅 sandboxEnabled 增量，无 main 侧行被改写）→ main 侧全部语义（引擎事务性复核 `runWithAgent` 内 in⊆deliveredTo fresh 复核、liveness 字段、reapStaleRunning、depsSatisfied 闸门/settleDeps/D1-deps 补触发、面板取消刷新）原样保留；
  - `git diff 22d12ce7..69313552` = **恰为 main 侧 34 文件 +3945/-424** → 沙箱侧全部语义（五工具过闸、ToolContext 传递链、SandboxPolicy/FileSandbox/SandboxBackend、forRoot 装配）原样落进 main；
  - grep 实证：NodeEngine.scala 内 `deliveredTo`/`reapStaleRunning`/`depsSatisfied`/`settleDeps` 标记与 `sandboxEnabled = true`（:283）并存；ProjectActor.scala 单例分发器标记（:19/:153/:188/:254）与 `sandboxEnabled = true`（:420）并存。
- **API 面衔接核查**：旧 main 在 agent/tools 域仅动 `TaskStuckWatcher.scala` / `AgentControlTool.scala` / `NodeTools.scala`，均不在沙箱 22 文件写入面；沙箱 ToolContext 链文件（types.scala、AgentCore、agent/protocol 等）相对已验证的 22d12ce7 逐字节一致 → 无签名漂移风险。编译+全量测试为最终仲裁（见三）。

## 三、全量复跑（真实前台 sbt test）

| 指标 | 值 |
|---|---|
| Total | **2052** |
| Failed | **0** |
| Errors | **0** |
| Ignored | 7 |
| 耗时 | **2246 s（0:37:26）** |
| 结果 | `[success]`，exit 0 |

2052 = main 全量绿基线 2036 + 沙箱新增 16（SandboxSpec），符合 ~2030+ 预期。零失败 → 零 [merge-fix] commit、零重跑。

## 四、沙箱 flag / 默认开关状态（代码证据）

**结论：宿主重建 + 重启后沙箱默认激活**（当前运行时配置未显式关闭）。

证据链：
1. `SandboxPolicy.scala:152-155`：`final case class SandboxConfig(enabled: Boolean = true, ...)` — 字段默认 true；
2. `SandboxPolicy.scala:170-178`（Decoder）：nebflow.json 的 `sandbox` 节存在但缺 `enabled` 键 → `c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(true))` → true；
3. `SandboxPolicy.scala:181-184`（`SandboxConfig.load`）：`sandbox` 节整体缺席/非法/garbage → fail-safe 回 `SandboxConfig()` → enabled=true（config.scala:192-195 顶层字段注释同口径）；
4. `GatewayMain.scala:327-328`（forRoot 装配点，启动主链无条件执行）：`val sandboxCfg = SandboxConfig.load(config.sandbox); SandboxRuntime.init(sandboxCfg)`；
5. 运行时实际配置 `~/.nebflow/nebflow.json` 顶层键实测无 `sandbox` 键 → 走默认 → **重启后文件闸 + Bash Seatbelt probe 全开**。
   - 注：主仓 repo 根无 nebflow.json（配置经 `PathUtil.configJsonReadPath(NebflowHome)` 读数据根）。

**22d12ce7 的 flag 回滚语义**（`sandbox.enabled=false` → 全停，一键回旧行为）：
- `FileSandbox.scala:31-37`（checkWrite）、`:53-58`（checkRead）、`:68-70`（checkReadRoot）：`if !policy.enabled` → 回「仅要求绝对路径」旧行为，文件写/读/读根三闸全停；
- `shell.scala:339-343`：`if !isWindows && sandbox.exists(_.enabled)` 才 Seatbelt wrap，否则 plain 执行 → Bash probe/wrap 全停。
- 与上游验证 §六（enabled=false 后界外写 OS 层真实落盘、读围栏消失）互证。

## 五、清理记录

- `git worktree remove ".nebflow/worktrees/sandbox-2a"` → 成功，无需 --force；
- `git branch -d sandbox-2a` → 成功（"已删除分支 sandbox-2a（曾为 22d12ce7）"；`-d` 而非 `-D` 成功本身证明 merge 完整）；
- `git worktree list` 复查：sandbox-2a 已不在列，其余 6 个 worktree 属其他工作流，未触碰；
- 收尾工作区状态：仅剩 `?? CONTRIBUTING.md` / `?? CONTRIBUTING.zh-CN.md`（他人 WIP，未动）。

## 六、边界与纪律确认

- 未 push、未动 origin；未 commit 两个 CONTRIBUTING 文件；未用 git add -A/.；
- 全程未触碰宿主进程（PID 87216、端口 8080）及任何 8080 相关进程；未发任何信号；
- **运行时生效需前端产物重建 + 宿主重启——本次未执行重启**（按任务红线，重启后沙箱默认激活的结论见 §四，由下次维护窗口执行）；
- 图上 n-4f749677 / n-2a1524ab 残留 running 状态未触碰（已死会话，等待 liveness 收殓，不产生写入）。

## 七、遗留问题

1. **L1（上游带入）**：设计文档 §A.8-6「/tmp 被拒」vs §A.4 tempRoots 可写的口径矛盾（E2E 实证 /tmp 可写）——需作者裁定，不阻塞合并；
2. **L2（上游带入）**：SandboxConfig 整节解码回退方向为安全侧（absent/非法 → enabled=true 默认开）——hardening 备注：非法配置建议显式告警；
3. **L3（上游带入）**：SandboxSpec flaky 证据强度「中」，建议 CI 多轮观察（本轮验证节点已做 2a7649f9 dataRoot 钉住修复，全量+单跑双绿）；
4. **重启生效**：沙箱与 deps/引擎/面板修复均需前端产物重建 + 宿主重启才在运行实例生效，本次未做；
5. 主仓新增 `.nebflow-qc.log`（合并钩子 QC 产物）为未跟踪文件，未提交、未删除。

## 八、上游验证结论转述

「分支 sandbox-2a（22d12ce7）通过全部 8 项验收（代码审查/全量 2025 绿 452s/§A.8 逐条/变异验红 5 条/隔离实例真实 Seatbelt E2E/flag 回滚/会话生命周期回归/新测试无冲突），4 条 verify-fix 复核全部认可，可进入合并评审。」——本节点据此完成合并收编，全量 2052 绿复核通过。

— Coder（合并执行节点）
