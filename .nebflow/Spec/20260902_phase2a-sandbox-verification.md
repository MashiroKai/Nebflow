# 阶段 2a 沙箱底座——独立验证报告（第四次派发续跑完成）

- **验证节点**：重派4（继承重派3 任务基线；重派3 因宿主清场误杀、输入永缺、从未开跑——非其自身失败；更早重派2 死于 LLM 传输层中断）
- **验证对象**：分支 `sandbox-2a`（worktree `.nebflow/worktrees/sandbox-2a`），merge-base `2f32d0a6`，HEAD `22d12ce7`，6 条提交：`efa9f86e`（实施）、`486f6a41`（测试）、`2a7649f9`/`a38d067d`/`4b39b06a`/`22d12ce7`（[verify-fix]×4）
- **设计依据**：`~/.nebflow/docs/Nebflow/20260902_project-architecture-phase2-design.md`（v3，35acaa0）§A.8、§G.1、§H（H-10①/H-5①/H-12①）——三条裁定原文已逐条调阅核对
- **验证日期**：2026-09-03（续跑完成 06:00 前后）
- **分支纪律**：全程只读验证，**本轮零新提交**（4 条 fix 复核后无需追加修改）；未 merge、未 push；宿主进程（PID 87216 / 端口 8080）未触碰；worktree 全程 status 干净
- **上游冲突窗口**：上游「实施-deps依赖连接·重派4」（n-b794ac96）已正常交付（merge `5eb9e815` 进 main），非 BLOCKED——按协议视为冲突窗口已清，本节点照常执行

---

## 0. 恢复说明（采认清单 + 证据来源）

前次会话在报告落盘前死亡，留下 180 行半成品：§1–§5 骨架、4 条 fix 复核结论、§10 分支转述已成文；§5 变异验红全是占位符（「见 §6.2 原文块」），§6.2–6.5 E2E、§7 回归、§9 结论全部空缺。

| 前次工作 | 处置 | 本节点再证 |
|---|---|---|
| 分支状态盘点（6 提交、merge-base） | **采认** | git log 复核一致：merge-base `2f32d0a6`、HEAD `22d12ce7`、工作区干净 |
| 4 条 [verify-fix] 复核结论（§2） | **采认+独立抽查** | probe 修正亲手复证：`/bin/true` 确不存在、`sandbox-exec -p '(version 1)(allow default)' -- /bin/bash -c true` → exit 0；forRoot/flag 修正由本轮 E2E flag 回滚（§6.4）+ JVM 断言（SandboxSpec G.1×2）双证；root 修正由 E2E 分发器/节点 SANDBOX_DENIED 消息中 root=worktree/workspace 确证 |
| sbt 全量 2025 绿（434s） | **重做**（验收项要求本会话证据） | 本轮重跑：**Total 2025, Failed 0, Errors 0, Ignored 7, 452s, exit=0**（05:17:50 完成）；SandboxSpec 单独重跑 **16/16 绿 3s** |
| 代码审查面（22 文件清单、无越界） | **采认+独立复核** | 净 diff 文件清单逐文件复核一致（22 文件 +1159/-143）；`dangerLevel`/`DangerousPatterns` 零触碰（grep 证空）；豁免面核对：MailTool/TaskTool/消息编排类工具不在 diff；沙箱核心三文件（SandboxPolicy/FileSandbox/SandboxBackend）+ AgentCore/GatewayMain/config hunks 本轮重读 |
| §5 变异验红原文 | **重做**（原为占位符） | 本轮 E2E 真实原文，见 §5 |
| §6 E2E | **重做** | 完整执行，见 §6 |
| §7 回归 | **重做** | 见 §7 |

环境残留复核（开工时）：8091/8092 空闲、无 `/private/tmp/nb-sbx-e2e*` 残留、worktree 干净。收尾清理（完工后）：见 §6.5。

---

## 1. 逐项验收表

| # | 验收项 | 结果 | 证据（详见对应章节） |
|---|---|---|---|
| 1 | 代码审查（净 diff 逐文件） | **PASS** | 22 文件全部过目，改动面 = §G.1 所列，无越界；`dangerLevel`/`DangerousPatterns` 零触碰；豁免面正确；4 条 fix 逐条复核（§2）；沙箱核心不变量复审（写⊆读、双解析写闸、canonical 读闸、§A.5 消息模板与 E2E 原文逐字吻合） |
| 2 | 全量 sbt test | **PASS** | 本轮独立重跑：2025 用例全绿 452s（exit=0）；SandboxSpec 单独重跑 16/16 绿（flaky 证据强度见 §3.2） |
| 3 | §A.8 逐条（#9 SKIP） | **PASS** | 逐条见 §4（#9 Nebula MemoryEdit 属 2b，SKIP） |
| 4 | 变异验红 ≥3 条 | **PASS** | 5 条拒绝消息原文见 §5（含「probe 失败仍执行」反证不出现） |
| 5 | 隔离实例 E2E（OS 层 Seatbelt） | **PASS** | §6：真实子进程证据 + PID/kill 确认 + HOME 清理 |
| 6 | flag 回滚（22d12ce7 闭环） | **PASS** | §6.4：enabled=false 后界外写回旧行为放行（OS 层文件落盘实证） |
| 7 | 回归：会话生命周期一致 | **PASS** | §7：开关前后分发器会话均正常终态、registry 注销日志在案 |
| 8 | 新增测试与既有 spec 无冲突 | **PASS** | 全量 2025 绿 + SandboxSpec 单跑绿（含 22d12ce7 新增断言自身通过） |

---

## 2. 四条 [verify-fix] 独立复核结论

> 复核立场：fix 出自前次验证会话这一事实不构成豁免；每条按「不受信任的上游工作」独立重审 diff + 语义 + 实证。本轮结论：**四条全部认可**（采认前次复核框架 + 本轮独立再证）。

### 2.1 `2a7649f9` SandboxSpec 钉住 dataRoot 修全量 flaky —— **认可**

- **改动**：仅 SandboxSpec 测试文件（+28 行）。beforeEach 把 `PathUtil.dataRoot` 钉到 `~/.nb-sbx-dataroot-<nanoTime>`（home 下，不在任何 readExtras 读面内），afterEach 还原 + 删除（还原顺序正确：先删目录再 setDataRoot）。
- **根因核实**：`PathUtil.dataRoot` 是全局可变单例；`SandboxPolicy.nebflowReadExtras` 从 `PathUtil.dataRoot` 派生。若同 JVM 先跑的 suite 把 dataRoot 设到 /private/var 临时目录且不复位，readExtras 的 `/private/var` 读面令该临时目录整体可读，「根层拒读」断言（H-12①）随 dataRoot 落点偶发翻转。因果链完整。
- **语义核实**：生产环境 dataRoot=~/.nebflow 同样不在读面内，钉住行为与生产行为同构；测试-only 改动，无生产面影响。
- **实证**：本轮全量 2025 绿 + 单跑 16/16 绿（§3）。flaky 为概率性，修复后证据强度见 §3.2（中）。

### 2.2 `a38d067d` Seatbelt probe 改用 /bin/bash -c true —— **认可**

- **改动**：SandboxBackend.scala probe 一处（+6/-2），probe 命令从 `/bin/true` 改 `/bin/bash -c true`，与 wrap() 硬编码的执行二进制一致。
- **本轮亲手复证**：`ls /bin/true` → No such file or directory（Darwin 25.4 确无此文件）；`/usr/bin/sandbox-exec -p '(version 1)(allow default)' -- /bin/bash -c true` → **exit 0**。
- **结论**：原 probe 在本机恒败（execvp ENOENT）→ fail-closed 拒绝一切沙箱 Bash；修法正确且探针与真实执行路径一致。改动恰如其分。

### 2.3 `4b39b06a` 沙箱 root 改取 SessionContext.projectRoot —— **认可**

- **改动**：AgentCore.scala 一处（+10/-1）：`sandboxRootStr = state.projectRoot.filter(_.nonEmpty).getOrElse(effectiveProjectRoot)`。
- **§A.6 一致性**：§A.6 规定 root = node 自己的 projectRoot（NodeEngine.scala:159-161 spawn 写入：worktree 节点=`<workspace>/.nebflow/<wt>`、否则=workspace；分发器=workspace，H-5①）。`SessionContext.projectRoot` 正是 spawn 写入字段——唯一权威，采纳正确。原实现走 folderId 链（`effectiveProjectRoot`），节点会话无 folderId → 回落实例 os.pwd，与 §A.6 相悖。
- **边界保护**：`ToolContext.projectRoot` 的既有 folderId 语义不动（防回归）——只修沙箱根，改动面最小。
- **异常路径**：projectRoot 形态异常 → fail-open 到 off + WARN 留痕。projectRoot 由分发器写入、模型不可控，fail-open 不构成可利用面；会话可用性优先，合理（标注为可接受设计点）。
- **本轮 E2E 再证**：分发器会话 DENY 消息 root=`/private/tmp/nb-sbx-ws-r4`（workspace canonical）；worktree 节点 DENY 消息 root=`/private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e`——**root 语义与 §A.6 完全一致**（§6.2 原文）。

### 2.4 `22d12ce7` forRoot 尊重 cfg.enabled —— **认可**

- **改动**：SandboxPolicy.forRoot 首行短路 `if !cfg.enabled then off`（+21/-7）；SandboxSpec 新增 config→forRoot 全链回归断言（+14）。
- **语义核实**：原 forRoot 硬编码 enabled=true，全局 flag 只停 Bash probe（SandboxRuntime.init），文件五工具闸门照常激活——§G.1「sandbox.enabled=false 一键回旧行为」失效。修复后 off 策略全闸门旁路（FileSandbox 各闸首查 enabled），语义正确。
- **回归断言质量**：新用例断言三点——①policy.enabled==false；②flag off 时界外绝对路径写放行（旧行为）；③flag off 时 `../` 相对路径不再有根校验、按旧行为（绝对路径判定后）放行。断言与本轮 §6.4 进程级复验互相印证。
- **本轮 E2E 再证**：隔离实例 sandbox.enabled=false 重启后，节点界外写 `/Users/dev/nb-sbx-rollback-ok.txt` 实际落盘（OS 层）、相对路径按旧行为拒（"Path must be absolute"）——与 JVM 断言逐字吻合（§6.4）。

---

## 3. 全量测试与新增测试冲突检查

### 3.1 全量 sbt test（worktree 内真实执行，前台）

- 命令：`sbt test`（worktree `.nebflow/worktrees/sandbox-2a`，含全部 4 条 fix）
- 结果：**Passed: Total 2025, Failed 0, Errors 0, Passed 2025, Ignored 7 — Total time: 452 s (0:07:32)**，exit=0
- 完成时间：2026-09-03 05:17:50
- SandboxSpec 单独重跑：`sbt "testOnly nebflow.core.sandbox.SandboxSpec"` → **Total 16, Failed 0, Errors 0 — 3 s**，exit=0

### 3.2 flaky 证伪强度与冲突确认

- 按验收清单口径：全量通过 + SandboxSpec 单独重跑一次均绿，**证据强度：中**——单次全量绿 + 单 spec 复跑绿可证当前全局状态下稳定，但 flaky 本质是概率性的，只有 CI 级多次全量才能给出强证据。建议合并后连续观察数次 CI 全量。
- 22d12ce7 新增「G.1 回滚: 配置链 forRoot」断言在两轮运行中均通过。
- 无既有 spec 与沙箱新增测试的资源冲突迹象（全量零失败零 error；SandboxSpec 的 dataRoot 钉住/还原逻辑完整还原，不影响其他 suite）。

---

## 4. §A.8 逐条验证

> JVM 层证据 = SandboxSpec（本轮重跑 16/16）；OS 层证据 = 隔离实例 E2E（§6，真实子进程 + sandbox-exec）。两项独立证据源。

| 条目 | 结果 | 证据 |
|---|---|---|
| #1 Write /etc/hosts → SANDBOX_DENIED 含 canonical+roots+指引 | **PASS** | JVM：SandboxSpec「A.8-1」；E2E：节点 Write /etc/hosts-nebflow-test → 完整原文见 §5 第 1 条（canonical `/private/etc/hosts-nebflow-test` + Writable/Readable roots + 「Write within the sandbox root, or report to the dispatcher…」指引） |
| #2 `<root>/../escape.txt` 拒；`<root>//sub//new.txt` 归一放行 | **PASS** | JVM：SandboxSpec「A.2 canonicalize」+「A.8-2」（fresh 路径=canonical 形态；`..` 经 realpath 真实解析出界） |
| #3 root 内 symlink 指外写/读拒；深层新文件创建成功 | **PASS** | JVM：SandboxSpec「A.8-3」；E2E：节点 symlink `escape-link.json → /Users/dev/.nebflow/auth.json`，Read/Write 双拒（§5 第 2/3 条），深层 `e2e-in.md` 创建成功（OS 落盘核验内容=`in-bounds-ok`） |
| #4 Glob/Grep 不跟随外部 symlink 目录 | **PASS** | JVM：SandboxSpec「A.8-4」（leak.txt 不出现；rg 无 --follow 不下钻 symlink 目录；搜索根在 readableRoots 外 → SANDBOX_DENIED） |
| #5 worktree 节点 Bash touch 主仓 → Operation not permitted + [sandbox: ...]；cd /tmp && touch 成功 | **PASS** | E2E：节点 `touch "<主仓>/nb-sbx-node-touch-main.txt"` → exit 1 + `Operation not permitted` + `[sandbox: bash write denied outside sandbox root …/wt-e2e]`（§5 第 4 条）；`cd /tmp && touch nb-sbx-node-tmp-ok.txt` → 成功（OS 核验文件存在） |
| #6 分发器 git worktree add 界内成功；/tmp/x 被拒 | **PASS(部分口径见注)** | E2E：分发器 `git worktree add -b wt-e2e <workspace>/.nebflow/wt-e2e` → 成功（HEAD 现在位于…）；`touch /private/tmp/...` → 实际成功未被拒：实现 writableRoots 含 tempRoots(/private/tmp、/tmp、java.io.tmpdir)（§A.4 profile sketch 本身就 allow /tmp），设计 §A.8-6 与 §A.4 自相矛盾，实现取 §A.4 口径。详注见 §8 L1 |
| #7 probe 失败模拟 → SANDBOX_UNAVAILABLE 不执行 | **PASS** | JVM：SandboxSpec「§A.4-4」两用例（假后端注入：fail-closed 断言 `!err.message.contains("should-not-run")`；显式降级带 `[unsandboxed]` 前缀）。a38d067d 后 probe 路径硬编码 /usr/bin/sandbox-exec，PATH 注入模拟失效属预期——注入面已被硬编码消灭（更强保证）；本验证另以 `/bin/true` 缺失实证 probe 恒败路径真实存在过 |
| #8 相对路径 Read 按 node root 解析 | **PASS** | JVM：SandboxSpec「A.8-8」+「A.8-8b」；E2E：节点相对路径 Write `e2e-in.md` 落在 worktree 内（root=`…/wt-e2e`，OS 核验）；`pwd` 返回 worktree 路径 |
| #9 Nebula MemoryEdit 属 2b | **SKIP** | 按验收清单注明 |

---

## 5. 变异验红——拒绝消息原文（E2E 真实采集）

> 生成方式：隔离实例（真实进程 + OS 层 Seatbelt）节点/分发器会话工具返回原文，逐字摘录。

**1. 界外写**（worktree 节点，Write `/etc/hosts-nebflow-test`）：

```
SANDBOX_DENIED
[sandbox: file access denied under workspace-write mode]
cannot write "/etc/hosts-nebflow-test" (resolves to /private/etc/hosts-nebflow-test, outside sandbox root /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e).
Writable roots: /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e, /private/tmp, /private/var/folders/ym/rfvzq57x4rgdq7cqcy9z68vc0000gn/T. Readable roots: /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e, /usr, /System, /opt/homebrew, /private/etc, /private/var, /private/tmp/nb-sbx-e2e-r4/skills, /private/tmp/nb-sbx-e2e-r4/prompts, /private/tmp/nb-sbx-e2e-r4/docs, /private/tmp, /private/var/folders/ym/rfvzq57x4rgdq7cqcy9z68vc0000gn/T.
Write within the sandbox root, or report to the dispatcher if the task genuinely requires a path outside the project.
```

**2. symlink 逃逸读**（worktree 节点，`ln -s /Users/dev/.nebflow/auth.json escape-link.json` 后 Read escape-link.json）：

```
SANDBOX_DENIED
[sandbox: file access denied under workspace-write mode]
cannot read "escape-link.json" (resolves to /Users/dev/.nebflow/auth.json, outside sandbox root /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e).
```

**3. symlink 逃逸写**（同路径 Write escape-link.json）：

```
SANDBOX_DENIED
[sandbox: file access denied under workspace-write mode]
cannot write "escape-link.json" (resolves to /Users/dev/.nebflow/auth.json, outside sandbox root /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e).
```

**4. 白名单外 ~/.nebflow 路径读**（worktree 节点，Read 真实凭据绝对路径）：

```
SANDBOX_DENIED
[sandbox: file access denied under workspace-write mode]
cannot read "/Users/dev/.nebflow/auth.json" (resolves to /Users/dev/.nebflow/auth.json, outside sandbox root /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e).
```

**5. Bash 界外写（OS 层 Seatbelt）**——分发器会话（root=workspace）`touch "<主仓>/nb-sbx-e2e-mainrepo-probe.txt"`：

```
(exit 1)
[stderr]:
touch: /Users/dev/Claude code/Nebflow/nb-sbx-e2e-mainrepo-probe.txt: Operation not permitted
[sandbox: bash write denied outside sandbox root /private/tmp/nb-sbx-ws-r4]
```

worktree 节点同型探针 `touch "<主仓>/nb-sbx-node-touch-main.txt"` → `Operation not permitted` + `[sandbox: bash write denied outside sandbox root /private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e]`。OS 层交叉核验：主仓两个探针文件**均不存在**（写入确实被内核拒绝）。

**「probe 失败仍执行」反证**（JVM，SandboxSpec §A.4-4 fail-closed 用例）：probe 失败下命令返回 SANDBOX_UNAVAILABLE 且断言 `!err.message.contains("should-not-run")` 通过——**未执行**是断言级事实；显式降级（failIfUnavailable=false）时命令执行且带 `[unsandboxed]` 前缀（第二个用例）。全量测试中该断言通过，反证成立。

---

## 6. 隔离实例 E2E（真实进程 + OS 层 Seatbelt）

### 6.1 环境与进程管理

- 临时 HOME：`/tmp/nb-sbx-e2e-r4`（任务指定新名，避让旧名）。最小结构：`nebflow.json`（llm.providers 复制自真实配置——**代码事实：模型调用凭证在 providers 内联 apiKey，不读 auth.json**；任务描述「软链真实 auth.json（模型调用）」与代码不符，按代码事实执行并在此注明）+ `model-presets.json`（复制真实——缺省时默认 preset 回落 kimi/kimi-k3，其供应商侧周配额耗尽导致首轮 E2E 失败，补齐后切 zhipu/GLM-5.3-Flash）+ `safety.defaultMode=auto-all`（E2E 便利开关：隔离实例无 UI 应答权限卡，生产中该卡由根会话/UI 应答——harness 配置，非产品改动）+ 根层假 `auth.json`（`"e2e-fake-token-for-deny-test"`——同时充当 gateway 访问令牌与根层拒读测试目标）+ `skills/skill-creator` 真实副本（白名单读测试）+ `agents/{Nebula,project-dispatcher}` + `projects/e2e-sbx/project.json`（**注意：agentFile 为 ProjectDef 必填字段**，缺失时 ProjectStore.list 静默跳过 → 项目不挂载）
- 项目 workspace：`/tmp/nb-sbx-ws-r4`（git 仓库，2 commits）
- 启动：分支 assembly jar（`sbt assembly`，worktree 冻结无源码变动，产物=HEAD 22d12ce7），`java -jar … --home /tmp/nb-sbx-e2e-r4 --port 8091 --no-browser`。注：端口机制实测为 CLI `--port` / env 双通道，nebflow.json 无端口键——与任务描述「nebflow.json 端口 8091」的机制表述有偏差，隔离效果等价
- 启动日志首证：`sandbox backend ready: seatbelt`；flag 关闭后重启该行消失（probe 路径被旁路，§G.1 旁证）
- 驱动链：根会话（agentName=Nebula，Task 工具）→ `Task(project="e2e-sbx")` → ProjectActor.TriggerDispatcher → 分发器会话（sandboxEnabled=true，root=workspace）→ Bash worktree add + NodeEdit 建节点（agent=project-dispatcher，worktree=wt-e2e，out=Nebula）→ 节点会话（sandboxEnabled=true，root=worktree）执行 a–j 探针步
- 进程身份确认（kill 前）：`lsof -p <pid> | grep cwd` → `/private/tmp`（启动目录），**非宿主**（宿主 PID 87216、端口 8080，全程未触碰）；kill 时另以 `ps -o command=` 复核 cmdline 含 `--home /tmp/nb-sbx-e2e-r4`
- 实例生命周期：首轮因平台后台看门狗（30s 无输出 SIGTERM）与 gateway 交互 stdin EOF 两度退出后，改 `nohup sh -c 'tail -f /dev/null | exec java …' & disown` 脱管启动成功。共 4 次有效运行（沙箱开×3、关×1），PID 序列 46542/47211/49511/52081/53667，用毕逐一 kill（SIGTERM 宽限 6–10s，超时 SIGKILL——本分支 gateway 优雅退出钩子会挂起，SIGKILL 后端口即时释放）

### 6.2 E2E 节点会话结果（sandbox root = worktree，会话 node-ffec6acd）

逐步工具结果（原文全量见 §5 及会话存档，此处列断言点）：

| 步 | 工具调用 | 结果 |
|---|---|---|
| a | Bash `pwd` | `/private/tmp/nb-sbx-ws-r4/.nebflow/wt-e2e`——会话初 cwd=root ✓ |
| b | Write 相对路径 `e2e-in.md` | `File created`；OS 核验：worktree 内文件存在、内容 `in-bounds-ok`（#8 相对路径按 node root 解析 ✓） |
| c | Write `/etc/hosts-nebflow-test` | **SANDBOX_DENIED**（§5-1 完整原文）；OS 核验：`/etc/hosts-nebflow-test` 不存在 ✓ |
| d | Read `/Users/dev/.nebflow/auth.json` | **SANDBOX_DENIED**（§5-4）✓ |
| e | Read `<ISO-HOME>/skills/skill-creator/SKILL.md` | **成功**，返回 SKILL.md 正文（H-12① 三子目录白名单 ✓） |
| f | Bash `ln -s ~/.nebflow/auth.json escape-link.json` | 成功（建链接本身是界内写——语义正确） |
| g | Read `escape-link.json` | **SANDBOX_DENIED**，canonical 解析出界（§5-2）✓ |
| h | Write `escape-link.json` | **SANDBOX_DENIED**（§5-3）✓ |
| i | Bash `touch "<主仓>/nb-sbx-node-touch-main.txt"` | exit 1 + `Operation not permitted` + `[sandbox: …]` 归因（§5-5）；OS 核验主仓无此文件 ✓ |
| j | Bash `cd /tmp && touch nb-sbx-node-tmp-ok.txt` | 成功；OS 核验文件存在 ✓ |

节点最终状态 completed（result 6399 chars），`Node 'sandbox-probe2' completed (result 6399 chars)` 日志在案；out=Nebula 投递路径打通（分发器 NodeEdit 返回 `out → Nebula`）。

### 6.3 E2E 分发器会话结果（sandbox root = workspace，会话 dispatcher-16db033e / ff49c6c7）

1. `git -C /tmp/nb-sbx-ws-r4 worktree add -b wt-e2e <workspace>/.nebflow/wt-e2e` → **成功**（「HEAD 现在位于 2edb49f」）——§A.8-6 前半 ✓（worktree 天然建在 workspace/.nebflow/ 内，H-5①）
2. `touch /private/tmp/nb-sbx-e2e-r4-tmp-probe.txt` → 成功（tempRoots 可写——§A.8-6 后半「/tmp/x 被拒」与 §A.4 口径冲突，见 §8 L1，非实施缺陷）
3. `touch "<主仓>/nb-sbx-e2e-mainrepo-probe.txt"` → **exit 1 + Operation not permitted + [sandbox: …]**（§5-5）——分发器沙箱 root=workspace 确证（H-5①）✓
4. NodeEdit 建节点两次均成功（n-348c4134 / n-09cb4c3f），入口节点即启

### 6.4 flag 回滚验证（sandbox.enabled=false，22d12ce7 闭环）

- 修改 `nebflow.json` 增 `"sandbox":{"enabled":false}` → 重启实例（启动日志**无** `sandbox backend ready` 行——probe 路径被旁路的旁证）
- 新探针节点（sandbox-probe3，root=workspace）三步全为旧行为，会话 node-24932dfa 原文：
  1. Write 相对路径 `e2e-off.md` → `Path must be absolute, got: e2e-off.md`（旧行为：相对路径拒——与 SandboxSpec G.1 断言逐字吻合）
  2. Write `/Users/dev/nb-sbx-rollback-ok.txt`（界外）→ `File created`；**OS 核验：文件真实落盘**，内容 `rollback-ok` ✓
  3. Read `/Users/dev/.nebflow/auth.json` → 读成功（旧行为无读围栏）✓
- **结论：flag 回滚语义进程级闭环**——关 flag 后文件闸与 Bash 围栏全部回旧行为，与 22d12ce7 的 JVM 回归断言互相印证

### 6.5 清理确认

- 实例 kill：末次 PID 53667（cmdline 复核含 `--home /tmp/nb-sbx-e2e-r4`），SIGTERM→SIGKILL，`lsof -ti :8091` 复核已释放
- `rm -rf /tmp/nb-sbx-e2e-r4 /tmp/nb-sbx-ws-r4`（临时 HOME 与 workspace，含全部会话存档与 providers 凭证副本）
- 探针残留清理：`/tmp/nb-sbx-e2e-r4-tmp-probe.txt`、`/tmp/nb-sbx-node-tmp-ok.txt`、`/Users/dev/nb-sbx-rollback-ok.txt`、`/Users/dev/e2e-off.md`、实例日志（含会话内容，一并删除）
- 复查：`/private/tmp/nb-sbx-e2e*` 无残留、主仓无探针文件、8091/8092 空闲、worktree status 干净

---

## 7. 回归：dispatcher 会话生命周期一致

- 沙箱开启轮：分发器会话 dispatcher-ff49c6c7 完成后日志 `Project 'e2e-sbx' dispatcher session dispatcher-ff49c6c7 finished — unregistered`——bridge 清 registry + 停 agent 链路工作正常；会话列表无滞留 Processing dispatcher
- 沙箱关闭轮：分发器会话同样正常触发→完成（probe3 节点 completed）——开关前后生命周期语义一致 ✓
- 说明：首轮分发器 dispatcher-16db033e 的 finished 日志在被覆盖的早期实例日志中，其会话存档显示完整工具链后正常收尾；口径以 ff49c6c7 在案日志为准
- 观察项（非本批 FAIL）：上轮实例重启遗留的 running 节点 `sandbox-probe`（n-348c4134）——NodeCancel 发出 cancel signal 但 store 状态未被更新（agent actor 已随重启消亡，本分支无节点崩溃恢复），状态滞留 running 直至 24h TTL 归档。属既有 Project 基建行为（崩溃恢复缺失），与沙箱无关，记录为 §8 L4

---

## 8. 遗留问题清单

- **L1（设计文档内部矛盾，非实施缺陷）**：§A.8-6 期望「分发器 /tmp/x 被拒」，但 §A.4 profile sketch 与实施均把 /private/tmp、/tmp（+java.io.tmpdir）列入可写根（工具链/编译器临时文件所需，dsh workspace-write 同形态）。E2E 实测 /tmp 写成功。实现与 §A.4 一致、与 §A.8-6 字面冲突。需作者裁定：改 §A.8-6 口径（推荐——/tmp 可写是设计意图）或收紧 tempRoots。
- **L2（minor，fail-safe 方向说明）**：`SandboxConfig` 解码整节 all-or-nothing——`sandbox` 子键形态非法时 load() 回落整节默认值（enabled=true）。默认方向是安全侧（沙箱开启），不损失保证；但用户同时写 `enabled=false` + 非法子键时 flag 会被忽略。可留 hardening：分键解码。
- **L3（flaky 证据强度）**：见 §3.2——建议 CI 连续多次全量观察。
- **L4（E2E 观察项，既有基建行为）**：节点无崩溃恢复——实例重启后 running 节点永久滞留（NodeCancel 信号发往已消亡 actor，store 不更新），直至 24h TTL 归档。与沙箱无关，建议归口 Project 基建节点。
- **L5（E2E harness 观察）**：子 agent 会话在无 bucket/无 UI 应答者时权限卡（InteractionRequest）永等（fallback 标志仅是前端路由标记，无超时裁决）；生产由根会话/UI 应答，隔离环境以 global auto-all 便利绕过。非缺陷，记录以备后续 E2E 复用。
- 无实施缺陷需 [verify-fix]（本轮零新提交）。

---

## 9. 结论

**分支 sandbox-2a（22d12ce7）通过全部 8 项验收，可进入合并评审。**

- 实施面 = §G.1 所列，无越界；4 条 [verify-fix] 独立复核全部认可；JVM（16 用例）与 OS 层（隔离实例真实 Seatbelt）双证据链完整；flag 回滚进程级闭环。
- 遗留 L1 为设计文档内部矛盾（需作者裁定 §A.8-6 口径，不阻塞合并）；L2/L5 为 hardening 备注；L3 建议 CI 观察收强证据；L4 归口 Project 基建。
- 合并提示：与 main 现 Head 的交集仅 NodeEngine.scala 异 hunk（2a 动 SpawnParams ~:220 区域；deps-edge 已并入 main 的 5eb9e815 动 startNode/completeNode/settleDeps）——上游 deps 节点实测分支合并零冲突，合并评审时照常注意即可。

---

## 10. 上游实施要点转述（分支 6 提交链）

- `efa9f86e` 实施本体：SandboxPolicy（单一策略源：writableRoots/readableRoots 唯一推导，写⊆读不变量；canonicalize=最深存在祖先 realpath + 剩余段折叠）/ FileSandbox（五工具 JVM 围栏：写闸 dual-resolve（canonical+fresh，dsh checkedTarget 防 TOCTOU）、contain=NIO startsWith 逐段、off 态仅要求绝对路径）/ SandboxBackend.Seatbelt（/usr/bin/sandbox-exec + /bin/bash 硬编码防 PATH 注入；profile (allow default)(deny file-write*)(allow 白名单)(deny .git/hooks) last-match-wins；(param)+-D 参数化；900B 预算 + profile 缓存）/ BashTool+ShellSession（fail-closed 入口、[unsandboxed] 显式降级、stderr 「Operation not permitted」归因、会话初 cwd=root）/ ToolContext.sandbox 传递链（GatewayMain load+probe 一次 → SharedResources → AgentCore 从 SessionContext.projectRoot 派生，仅 project 节点+分发器 sandboxEnabled=true；Nebula 无文件工具天然豁免、team/flow/Delegate 双轨会话默认旧行为 §A.7）
- `486f6a41` JVM 层可断言用例（SandboxSpec 最终 16 用例：A.2×3、A.8-1/2/3/4/8/8b、G.1×2、H-12①、§A.4-4×2、§A.4-2、§A.4-7）
- `2a7649f9`/`a38d067d`/`4b39b06a`/`22d12ce7`：见 §2（全部认可）
- 改动范围：22 文件 +1159/-143；无前端、无无关模块、无 Nebula/消息编排类工具触碰（豁免面正确）；DangerousPatterns/dangerLevel 原样保留

---

## 11. 上游 deps 依赖连接实施要点转述（n-b794ac96 正常交付，merge 5eb9e815）

- **交付形态**：正常交付（非 BLOCKED）——deps 边已合并进本地 main，merge commit `5eb9e815`（`git merge deps-edge --no-ff`，13 文件 +1428/-274，与 main 并行面板修复 6c9daba7 零冲突）；分支 commit `39fc2c7d`（后端）+ `13ddb31b`（前端+harness）；worktree/软链/分支已清理，未 push
- **后端**：NodeDef.deps 字段（条件序列化，无 deps 节点 payload 字段集不变）；FlowMapStore.wouldCreateCycle 并入 deps 反向索引（多后继 DFS）；NodeTools NodeEdit 全链（create/proceed/edit/abandon 扩展：deps oneOf string|array、存在性+环检、running 冻结、earlyReject、replace-on-provide、D1-deps 补触发）；NodeEngine startNode 闸门内 in-barrier 归零检查 + completeNode 后挂 settleDeps（反向扫描活动区依赖者补启动）；附带修复 editNode out 未传误断开存量缺陷
- **前端**：flowMapTab deps 边布局/渲染三态/等待脚注/图例六档；flowMap.css 三态虚线+空心箭头；locales 双语键成对（921=921 parity）；verify-flowmap-deps.cjs harness 11/11
- **测试**：NodeDepsSpec T1–T8 8/8 绿；存量适配 63/63 绿（只补创建参数，断言一字未动）；worktree 全量 **2021 绿 451s**；合并后主仓子集 71/71 绿；check-js-types 零新增错误（main 既有 5 个 flowMapTab checkJs 错误非其引入）
- **与本验证的关系**：deps 改动面与 sandbox-2a 在 NodeEngine.scala 异 hunk（上游实测零冲突）；主仓清场信号有效，本验证在合并前基线上独立完成
- 上游遗留建议（转述）：sweepExpired 端到端归档变体可调 sweepExpired 直测；checkJs 既有 5 错建议顺手收敛

---

*验证命令全部前台真实执行（唯一例外：隔离实例为长驻服务器进程，以脱管后台方式运行并逐一 kill 确认）；所有验收证据由本验证节点独立生成；报告落盘后即提交 ~/.nebflow。*
