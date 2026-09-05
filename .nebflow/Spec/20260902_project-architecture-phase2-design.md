# Nebflow 阶段 2 架构演进设计方案

> 撰写：2026-09-02 22:52（v2 重派节点 n-95271231 直读上游落盘产物，不依赖 barrier；原文日期误标 09-05，v3 更正）
> **v3 修订：2026-09-02 23:05 作者批准方案后对齐现状——① §H 15 条待确认点全部按建议值落账为「已裁定」；② 现状偏差审计：v2 行号基线 = 盘点时点（≈`2c0758a8`，09-02 20:04），此后 main 合入至 `2f32d0a6`（23:06）共 13 个 commit，全部行号锚点/文件路径/机制引用已逐节复核并对齐新基线，逐条依据见文末「v3 变更日志」。只改现状描述与锚点，不改任何已批准的设计决策。**
> 性质：**只出方案，不改任何产品代码**。主仓 `/Users/dev/Claude code/Nebflow` 只读；本文档落盘于 `~/.nebflow/docs/Nebflow/`。
> 上游依据（三份，均已全文通读）：
> ① 沙箱调研 `/tmp/nebflow-phase2/sandbox-research.md`（n-8a481bd0，下称【沙箱调研】）
> ② 现状盘点 `/tmp/nebflow-phase2/inventory.md`（n-7b56096d，含 8 项路径+行号，下称【盘点】；本文引用行号均经主仓现场抽查复核）
> ③ 调研报告 `~/.nebflow/docs/research/20260902_research-agent-plugins-protocol-nebflow-integration.md`（下称【调研报告】，其 §5.1 组件映射表 / §5.3 风险清单 / §5.4 验收点被直接复用）
> 作者裁定：2026-09-02 19:42 共 14 条（正文逐条落实，§0 附对照表）；2026-09-02 23:05 批准本方案整体——§H 15 条待确认点全部按建议值执行（v3 落账）。

---

## 0. 裁定落点对照表（14 条 → 章节）

| # | 裁定 | 落点 |
|---|------|------|
| 1 | 按阶段实施，逐渐取代传统 team/flow | §G 阶段划分；§F.4–F.6 退役时序与双轨边界 |
| 2 | 移除 Nebula 的 Write/Edit；memory 改专用编辑工具 | §C.2（MemoryEdit 接口语义 + memory 维护完整设计）；§D.1 移除清单 |
| 3 | 以 project 路径为中心的沙箱；agent 只在沙箱内工作；Nebula 无文件工具、不受沙箱约束 | §A 全章；§C.2（Nebula 工具集无任何文件工具） |
| 4 | agent 定义收敛为三个（Nebula、project-dispatcher、通用模版），其余归档 | §C 全章；§F.3 归档操作 |
| 5 | 通用 agent 固定工具 8 件：Read Glob Edit Write Grep Bash AskUserQuestion Pop | §C.4 / §C.5（逐一语义+沙箱关系）；§D.1（MultiEdit 不在 8 件内 → 删除） |
| 6 | Nebula 与分发器提示词精心设计；通用 agent 提示词只保留基础内容 | §C.2 / §C.3（精心设计要点清单）；§C.4（基础提示词骨架） |
| 7 | 按 Agent Plugins 1.0.0 协议统一 skill 和 MCP | §B.1–B.2（manifest/mcp.json 对齐）；§B.7（§5.1 映射表复用） |
| 8 | 专用 agent = 通用 + Plugins 动态分配；分发器见全部 plugin 描述；收到 task/上游结果自动注入 skill 全文与 MCP | §B.4 全链路五步（注册表→分发器目录→NodeEdit 分配→node 注入→会话回收） |
| 9 | 工具定义自包含精简；移除工具条件注入机制 | §D 全章（移除清单 + 自包含原则 + antipattern 示例） |
| 10 | explorer、design-engineer 能力蒸馏为 skill | §F.1 / §F.2（skill 包目录结构与 SKILL.md 要点） |
| 11 | 工具可配置全走 Plugins；SendFriendMessage/Task/ProjectCreate 等成为 Nebula 固定工具；分发器工具同理固定 | §C.1 角色-工具矩阵；§C.2 Nebula 固定集；§C.3 分发器固定集；§B.6（Web 系工具经 plugin 扩展授予） |
| 12 | Plugins 可以只有 skill、只有 mcp、或都有 | §B.2（装载校验规则：二选一即可，全无为非法） |
| 13 | 迁移旧 Agent.md 不留两套；AGENTS.md 自动注入系统提示词 | §E 全章（注入点代码级设计 + 迁移映射表） |
| 14 | 信任门：外部导入 plugin 的 MCP/组件默认拒绝或审批清单 | §B.3（审批流程 + 清单格式 + digest 失效重审） |

---

## A. 沙箱方案

### A.1 目标与总形状

裁定 3：任何 agent 只允许在沙箱内工作；Nebula 无文件工具、天然豁免。落地形状对齐【沙箱调研】§0 结论 1 的业界共识——**双层强制 + 单一策略源**：

```
                 SandboxPolicy { root = node 的 projectRoot, readExtras, tempRoots }
                        ↑ 唯一策略源，writableRoots()/readableRoots() 均从这里派生
        ┌───────────────┴───────────────────┐
        │ 文件工具层（JVM 进程内围栏）         │ Bash 层（OS 强制）
        │ Read/Write/Edit/Glob/Grep          │ /usr/bin/sandbox-exec -p <SBPL>
        │ 路径校验 + fresh 路径执行            │ 覆盖全部子进程树（sbt/git/任意 fork）
        └────────────────────────────────────┘
```

两点继承自调研的硬结论：
- **两层从同一个 `writableRoots()` 取根，永不漂移**（dsh 教训，【沙箱调研】§1.2）。
- **威胁模型如实声明**：JVM 围栏是 containment 不是内核安全边界；不可信代码的隔离由 Bash 层 OS 沙箱承担（【沙箱调研】§1.3 dsh 注释原话）。本轮不做网络隔离，文档明示"沙箱≠网络边界"（dsh 同款诚实声明）。

### A.2 SandboxPolicy：单一策略源

新增 `core/sandbox/SandboxPolicy.scala`：

```scala
case class SandboxPolicy(
  root: os.Path,          // canonical 后的沙箱根 = 节点 projectRoot（NodeEngine.scala:159-161 已算出）
  readExtras: List[os.Path]
)
object SandboxPolicy:
  // 可写根（唯一推导，JVM 围栏与 Seatbelt profile 共用）
  def writableRoots(p: SandboxPolicy): List[os.Path] =
    (p.root :: os.Path("/private/tmp") :: os.Path("/tmp") :: tempDir :: Nil).map(canonical).distinct
  // 可读根 = 沙箱根 + 系统工具链 + ~/.nebflow 白名单子路径（readExtras）
  def readableRoots(p: SandboxPolicy): List[os.Path] =
    (p.root :: p.readExtras).map(canonical).distinct
```

关键取值：
- `root` = **会话级不可变**，取 node 的 projectRoot（worktree 或 workspace，见 A.6）；分发器 root = project workspace。相对路径一律以该 root 为基准解析（禁止按 JVM `user.dir` 解析——现状 Glob/Grep 默认根正是 `user.dir`，GlobTool.scala:68 / GrepTool.scala:114，必须改）。
- `readExtras`（系统只读面）：`/usr`、`/System`、`/opt/homebrew`、`/private/etc`、`/private/var`（够编译器/工具链/系统命令使用）。
- `~/.nebflow` 读取白名单（见 H-12，已确认①）：只开放 `~/.nebflow/skills`、`~/.nebflow/prompts`、`~/.nebflow/docs` 三个子路径。理由：skill 的 `scripts/ references/` 需要 Read/Bash 触达（`${SKILL_DIR}` 替换后的具体路径）、docs 是落盘产出惯例位；而 `~/.nebflow` 根层的 `auth.json`、`device.json`、`logto-*.env`、`vps.env` 等凭据文件（现场核实存在）不暴露给任何节点 agent。`~/.nebflow` **不在可写根**（裁定 3：agent 只在 project 内写）。
- canonicalize 用 NIO 组件级解析语义（`toRealPath` 的等价物），新文件先 realpath 最深存在祖先再拼接剩余段；禁止自己折叠 `..`（【沙箱调研】§6.2-2）。macOS 上 `/tmp`→`/private/tmp` 归一后通常只剩 root + `/private/tmp` + java.io.tmpdir 三项去重。

### A.3 文件工具层：JVM 围栏

新增 `core/sandbox/FileSandbox.scala`，Read/Write/Edit/Glob/Grep 四族工具全部过闸（现状：要求绝对路径但无任何根校验——ReadTool.scala:109-110、WriteTool.scala:69-70、EditTool.scala:135-136、MultiEditTool.scala:153-154、GlobTool.scala:68、GrepTool.scala:114，【盘点】§7.3）：

- **写操作**（Write/Edit）：`resolve → writableRoots contain 检查 → 此刻 re-resolve（fresh 路径）→ 用 fresh 路径执行`（dsh `checkedTarget` 模式，消灭 check-then-use TOCTOU 窗口，【沙箱调研】§1.3-线 B）。
- **读操作**（Read/Glob/Grep）：`resolve → readableRoots contain 检查`。裁定"任何 agent 只允许在沙箱内工作"故读面也收（比 dsh 的读全直通更严，对齐【沙箱调研】§6.2-7 建议）。
- **contain 检查**：`target == root || target.startsWith(root + separator)`——必须带分隔符边界（防 `/foo/bar-baz` 伪匹配 `/foo/bar`）。
- **Glob/Grep 遍历逃逸**：默认**不跟随 symlink** 下钻；若实现需要跟随，则每个下钻目录 re-check contain（【沙箱调研】§6.2-8）。
- **symlink 逃逸**：root 内 symlink 指向外部 → 写被拒（fresh 解析后越界）；新文件祖先为 symlink → 按此刻解析结果判定。
- 工具入参相对路径：以 ToolContext 新增的 `sandbox: SandboxPolicy` 为基准（该对象从 node projectRoot 构造，随 spawn 传递，复用 ctx.projectRoot 现有传递链 SubTaskTool:182 / DelegateTool:340,357 / MailTool:780 / ScriptTool:25 的位置扩展）。

### A.4 Bash 层：Seatbelt 强制

现状防线只有内容黑名单（DangerousPatterns BashTool.scala:96-146 + dangerLevel 213-248），无路径审查，且会话 shell 初 cwd = JVM `user.dir`（ShellSession.forSession 不传 initialDir，BashTool.scala:332,383；shell.scala:1065-1072）。改造：

1. **cwd 锁定**：`ShellSession.forSession(sessionId, initialDir = sandbox.root)`——每个 node/分发器会话的 shell 初 cwd = 沙箱根；之后的 `cd` 不禁止（读面已在 JVM 围栏声明），写由 Seatbelt 兜底。
2. **profile 模板**（dsh 极简形状，【沙箱调研】§6.3-1；本轮不做读收窄与网络隔离）：

   ```lisp
   (version 1)
   (allow default)                        ; 读/进程/网络默认放行（诚实声明：Bash 读面本轮不设界）
   (deny file-write*)
   (allow file-write* (literal "/dev/null")
                      (subpath "<root>")
                      (subpath "/private/tmp")
                      (subpath "/tmp"))
   (deny file-write* (subpath "<root>/.git/hooks"))   ; 防 hook 注入持久化（srt/Codex 同款，保留 git commit 能力）
   ```

   路径参数化用 `(param)`+`-D` 传（codex 做法）；SBPL 字面量 ≤900B；profile 按 root 缓存（root 集合有限）。
3. **调用**：硬编码 `/usr/bin/sandbox-exec -p <profile> -- /bin/bash -c <command>`（防 PATH 注入，codex 同理由）。Seatbelt 策略随 fork/exec 自动传播，shell 内再起的 sbt/git 任何子进程全部受限，无需逐命令包装。
4. **probe 与降级**：GatewayMain 启动时功能 probe 一次并缓存——`sandbox-exec -p '<read-only profile>' -- true` exit 0 才可用；probe 失败**默认 fail-closed**（Bash 返回 `SANDBOX_UNAVAILABLE`，绝不静默放行，dsh 先例）；`nebflow.json` 提供 `sandbox.bash.failIfUnavailable=false` 显式开关降为"WARN 日志 + 结果前缀 `[unsandboxed]`"（两层语义都做，默认严格侧，对齐【沙箱调研】§6.4）。
5. **违规归因**：Seatbelt 方言 `Operation not permitted` 出现在 stderr 时，工具结果标注 `[sandbox: bash write denied outside sandbox root <root>]`，防模型误诊为环境故障（【沙箱调研】§6.3-5）。
6. DangerousPatterns / dangerLevel 内容审查**保留**，与路径沙箱正交。

Linux 可移植性：按【沙箱调研】§5 建议预留 `SandboxBackend` trait（`wrap(argv, policy) → wrapped | Unavailable`），本轮只实现 Seatbelt 后端。

### A.5 越界拒绝语义（自纠引导）

结构化错误码 `SANDBOX_DENIED`（区别宿主 EACCES），消息模板（恢复指令出现在错误消息里，dsh `2026-08-03-fs-tool-error-remedy.md` 教训）：

```
[sandbox: file access denied under workspace-write mode]
cannot write "/etc/hosts" (resolves to /private/etc/hosts, outside sandbox root
/Users/dev/Claude code/Nebflow/.nebflow/wt-fix-auth).
Writable roots: <root>, /private/tmp, /tmp. Readable roots: <root>, /usr, /System, ... .
Write within the sandbox root, or report to the dispatcher if the task genuinely
requires a path outside the project.
```

### A.6 worktree 场景裁定

**root = node 自己的 projectRoot（NodeEngine.scala:159-161 已有该语义），不做双根**：

- dev/修复类节点：`node.worktree=Some(wt)` → root = `<workspace>/.nebflow/<wt>`，物理上不可能误写主仓或其他 worktree（最小权限）。
- merge 类节点：`node.worktree=None` → root = workspace（主仓）——merge 节点的职责就是在主仓操作，root 跟随会话 cwd 而非全局一个（dsh per-call policy 同形态）。
- 不推荐 dev 节点配 `[worktree, 主仓]` 双根（Codex `writable_roots` 支持但违背最小权限）；确有个别需要走显式 `additionalRoots` 配置而非默认（H-5 已确认①、预留③）。
- worktree 布局不由 nebflow 控制且可能含 symlink → 采用 **canonicalize 代替拒绝**（dsh 折中，【沙箱调研】§6.5）；NodeEdit 建 node 时已校验 `workspace/.nebflow/<wt>` 存在（NodeTools.scala:308-314 createNode worktree 存在性检查，v3 锚点更新——NodeTools 经 1917463d/c467592f 大幅扩展后行号漂移），故 worktree 天然落在 workspace root 内。

### A.7 豁免面

| 工具 | 文件效果 | 纳入沙箱 | 说明 |
|---|---|---|---|
| Read/Write/Edit/Glob/Grep（通用 agent 8 件中的 5 件） | 有 | **是（JVM 围栏）** | §A.3 |
| Bash | 有（经子进程） | **是（Seatbelt）** | §A.4 |
| Nebula 全部工具（Task/Mail/AgentControl/MemoryEdit 等） | 无（MemoryEdit 见 H-1 已确认①：工具内建路径校验，非沙箱对象） | 否 | 裁定 3：Nebula 无文件工具、不受沙箱约束 |
| AskUserQuestion / Pop | 无 | 否 | Pop 只展示已存在文件；未来加导出能力再评估 |
| Mail / SendFriendMessage / Task / NodeList 等 | 无 | 否 | 纯消息/编排面 |
| WebSearch/WebFetch/Curl | 无（网络效果） | 否（本轮） | 文档明示"沙箱不是网络边界"；未来参考 srt 代理方案 |
| 分发器 Bash git worktree 管理 | 有 | **是（root=workspace）** | worktree 建在 `<workspace>/.nebflow/` 内 → 天然在界内；`git worktree add` 写主仓 `.git`（worktree 元数据）也在 workspace root 内，合法 |

### A.8 验收点（可断言）

1. Write `/etc/hosts` → `SANDBOX_DENIED`，消息含 canonical 路径 + 合法 root 列表 + 自纠指引。
2. Write `<root>/../escape.txt` → 拒绝；Write `<root>//sub//new.txt`（词法冗余拼写）→ 归一后放行。
3. root 内 symlink 指向外部文件：写拒绝、读拒绝；root 内不存在深层新文件创建成功。
4. Glob/Grep 不跟随 root 内指向外部的 symlink 目录。
5. node（worktree）Bash `touch <主仓>/src/x` → `Operation not permitted` 且结果带 `[sandbox: ...]` 归因；`cd /tmp && touch x` → 成功。
6. 分发器 Bash `git worktree add .nebflow/wt2 -b b2` → 成功；`git worktree add /tmp/x` → 被拒。
7. 模拟 probe 失败（PATH 注入假 sandbox-exec）→ Bash 报 `SANDBOX_UNAVAILABLE`，不执行命令。
8. 相对路径 `Read a.txt` → 按 node root 解析（非 JVM user.dir）。
9. Nebula 调 MemoryEdit 写 `~/.nebflow/auth.json` → 工具拒绝（H-1 验证）；Nebula 无任何文件工具可越界。

---

## B. Plugins 层设计

### B.1 定位与协议锚点

plugin = **skill + mcp.json 的可分配能力包**（裁定 12：可只用其一）。协议锚点 = Agent Plugins 1.0.0（【调研报告】§2.1）：根级 `plugin.json`（闭合 schema，`$schema` 必须为 canonical 值，仅 `name` 必填）+ `skills/`（agentskills.io 规范，nebflow SkillService 已解析同名字段）+ 根级 `mcp.json`（`$schema` + `mcpServers`，stdio / streamable-http / legacy sse 三 transport）。nebflow 现有 MCP 双层（全局 nebflow.json + agent 专属 `mcp__agent-<n>-<s>` 前缀，AgentMcpLoader.scala:33-58）与 skill 三层加载（SkillService.scala:275-288）均为映射落点。

裁定 11 的落地口径：**agent 不再有任何工具可配置字段——`tools` 字段退役，能力差异只来自 plugins**。Builtin 工具按角色固定（§C.1）；Web 系等内建工具的授予经 §B.6 扩展机制走 plugin。

### B.2 本地目录格式（~/.nebflow/plugins/<name>/）

```
~/.nebflow/plugins/
  web-research/                      # 示例：只有 mcp + 工具扩展
    plugin.json                      # {"$schema":"https://agent-plugins.org/schema/1.0.0",
                                     #  "name":"web-research","version":"1.0.0",
                                     #  "description":"Web 检索：WebSearch/WebFetch 授予 + 用法"}
    mcp.json                         # {"$schema":"...","mcpServers":{"fetch":{"url":"http://..."}}}
    org.nebflow/
      tools.json                     # nebflow 扩展命名空间：{"tools":["WebSearch","WebFetch"]}
  explorer-toolkit/                  # 示例：只有 skills（§F.1 蒸馏产物）
    plugin.json
    skills/
      exploration-method/SKILL.md
      solution-planning/SKILL.md
  design-spec/                       # 示例：skill + mcp 都有
    plugin.json
    skills/design-spec/SKILL.md
    mcp.json
```

装载校验规则（裁定 12）：`skills/` 与 `mcp.json` 至少有其一，全无 → 拒载 + 告警；manifest 缺 `name` → 拒载；未知字段/未知目录 → 宽容忽略 + 告警（【调研报告】风险 2 缓解）；`plugin.json` 的 `version` 记录进信任摘要。命名空间：plugin 内 skill id = `<plugin>/<skill>`（复用 nebflow 两级 namespace 先例 `nebflow/visual-style`）；同名冲突仲裁 **user > project > plugin**（【调研报告】风险 3 建议沿用）。

### B.3 注册表与信任门（裁定 14）

新增 `core/plugin/PluginRegistry.scala`：

- **扫描**：启动时 + 每 turn mtime 检查（对齐 skill「改后即时生效」机制，【盘点】§8.2），产出注册表：`{name, manifest, description, skills[<id, desc, path>], mcpServers[<name, config>], toolsExtension, trustStatus}`。
- **信任状态机**：目录存在但未审批，或目录内容 digest ≠ 审批记录 → `untrusted`；untrusted plugin **不进分发器目录、不可被分配、MCP 不启动、skill 不注入**（默认拒绝）。审批记录存 `nebflow.json`：

  ```json
  "plugins": { "trust": {
    "web-research": { "sha256": "<目录内容树 digest>", "approvedAt": 1757000000, "scope": "all" }
  } }
  ```

- **审批流程**：① 新目录出现 → untrusted，面板 Plugin 页显示待审徽标；② 用户点开**审批清单**（面板渲染，格式见下）；③ 确认 → 计算 digest 写入 trust 表 → 下个 turn 生效；④ 后续每次重扫对比 digest，不一致（含版本 bump）→ 自动回落 untrusted 待重审——**升级即重审**，堵住"先审批后改内容"的绕过。
- **审批清单格式**（用户审什么）：

  | 区块 | 内容 | 红标项 |
  |---|---|---|
  | 元信息 | name / version / description / author | 无 author 或 version |
  | skills | 每个 skill 的 name + description + 正文前 20 行 | 提示词含"忽略之前指令"类模式（后续可加启发式扫描） |
  | mcp.json | 逐 server：transport / command+args / url / **env 键名列表（值打码）** | env 含凭据类键名、command 指向 curl|sh 类 |
  | org.nebflow/tools | 申请授予的 builtin 工具名 | 超出固定安全集（§B.6）|
  | 变更摘要 | 与上次审批版本的 diff（首审为"新装"） | |

- CLI 对等：`nebflow plugin list|approve <name>|revoke <name>`（面板为主、CLI 供脚本化，对齐 Claude Code `claude plugin` 形态，【调研报告】§1.4）。
- 外部导入：`nebflow plugin add <git-url|本地路径>` → clone/copy 进 `~/.nebflow/plugins/<name>` → 落为 untrusted 待审（默认拒绝；【调研报告】§5.3 风险 1：不设门 = 绕过上游安全设计）。

### B.4 动态分配全链路（裁定 8，五步）

**第 1 步：注册表**（§B.3）。

**第 2 步：分发器可见**——`ProjectActor.spawnDispatcher` 的 prompt 组装（ProjectActor.scala:193-201 `newTaskPrompt`，现为 Flow Map snapshot + 任务文本；2026-09-02 起新增 blocked 重入组装 `reentryPrompt` :205-224，两处同样追加） `# Plugin Catalog` 段（对齐 skillCatalog order 800 先例，PromptSections.scala:382-386）：

```
# Plugin Catalog（可分配能力包，NodeEdit 的 plugins 参数按 name 引用）
- web-research: Web 检索能力 [skills: - | mcp: fetch | tools: WebSearch, WebFetch]
- explorer-toolkit: 代码库探索与方案规划方法论 [skills: exploration-method, solution-planning]
```

设计取舍：用**注入目录段**而非新增 `PluginList` 查询工具——分发器是单次会话、目录规模小，注入即可，不多造工具（工程哲学：删大于加）。untrusted plugin 不出现在目录。

**第 3 步：分配决策**——`NodeEdit` 新增 `plugins: List[String]` 参数；`NodeDef` 新增 `plugins: List[String] = Nil` 字段（ProjectTypes.scala:41-65 处扩展，v3 锚点更新）。旧字段 `skill/mcp` 的处置见 H-11 已裁定①：标记 deprecated，NodeEdit 拒绝再写，存量 flow-map 中的值加载时告警并仅作展示。NodePayload（ProjectTypes.scala:76-107，v3 锚点更新）与 WS 事件增 `plugins` 字段（用户可见性 H-3 已确认①+面板注入清单摘要）。

**第 4 步：node 注入**——`NodeEngine.runWithAgent`（NodeEngine.scala:156-267，v3 锚点更新）在 spawn 前执行：

1. `node.plugins` → PluginRegistry 解析（untrusted/不存在 → failNode，错误消息列明原因——分配失败是节点级失败，不静默降级）；
2. **skill 全文注入**：逐 plugin 逐 skill 读 SKILL.md 全文，`${SKILL_DIR}` 替换为实际绝对路径（SkillService.loadSkill:313-323 同逻辑，修复"模型自读拿不到替换"的现状缺口，【盘点】§8.2），组装为结构化块注入**首条 user 消息**（即 buildInput 的 task + 上游结果文本之后）：

   ```
   <injected-plugins>
   <plugin name="explorer-toolkit" skill="exploration-method">
   （SKILL.md 全文，含 frontmatter 去除后的正文）
   </plugin>
   </injected-plugins>
   ```

   选**消息注入**而非 system prompt 注入的理由：systemStable 字符串只在 lifecycle 节点重建（AgentCore.scala:573-580），消息注入天然每次 spawn 新鲜生成、零缓存失效问题；且符合裁定 8"收到消息（task 或上一节点结果）后自动注入"的字面语义。**无需 agent 再读**——目录注入（order 800 skillCatalog）对新模型 node 会话不再注入（§D.1 第 12 项）。
3. **MCP 注册**：该 plugin 的 mcp.json servers 交 `PluginMcpManager` 启动（§B.5），工具注册名 `mcp__plugin_<plugin>_<server>__<tool>`；该会话的 allowedSet 追加对应前缀（`buildAllowedToolSet` MCP 过滤段 AgentCore.scala:1486-1494 扩展一个 plugin 前缀来源）。

**第 5 步：会话回收**——node 终态（Completed/Failed/Cancelled，bridge 捕获 NodeEngine.scala:187-201，v3 锚点更新；2026-09-02 起新增第四终态 **blocked**——turn 正常结束但输出以 BLOCKED 锚定，经 completeNode→BlockedReader 分流 NodeEngine.scala:279-283，会话同样终结，协议见 §C.3 交叉引用）→ `PluginMcpManager.release(sessionId)` 引用计数归零即 `unregisterToolsByPrefix`（registry.scala:88-100 现成机制）+ 停 server。

### B.5 MCP 生命周期（PluginMcpManager）

- **引用计数**：同一 plugin 被多个并发 node 分配 → server 只起一份，refcount 管理；最后一个会话释放后关闭（stdio 进程 kill / http 连接断开）。
- **启动失败语义**：MCP server 起不来 → failNode（不静默跳过——分配了 MCP 却没有 = 节点能力残缺，任务书语义要求"自动注入"，注入失败必须可见）。
- 与全局 McpManager 的关系：全局 server（nebflow.json 顶层）维持现状供 Nebula/双轨期使用；plugin MCP 独立管理器，不复用全局 enable/disable 面（避免互相污染开关状态）。
- 信任联动：digest 失效 → refcount>0 的运行中 server **立即停用** + 受影响 running node 收到系统提醒消息（trust 是运行时属性，不只是装载时属性）。

### B.6 内建工具授予扩展（org.nebflow/tools）

裁定 5 的 8 件之外，通用 agent 还有 Web 系（WebSearch/WebFetch/Curl）等内建工具需求（Explorer 蒸馏后其检索能力需要 Web 工具载体）。Plugins v1.0.0 只定义 skills+mcp 两种组件，但协议明示其余能力走 reverse-domain 扩展命名空间（【调研报告】§2.1）。设计：

- manifest `extensions: {"org.nebflow/tools": "tools.json"}` + 顶层 `org.nebflow/tools.json`，内容 `{"tools": ["WebSearch","WebFetch","Curl"]}`；
- **固定安全集校验**：只允许引用既有 builtin 工具名，且限定白名单 `{WebSearch, WebFetch, Curl, Pop}`——plugin 不能发明新工具、不能授予编排类工具（Task/Mail/NodeEdit 等永不进白名单，角色边界由 §C.1 静态矩阵守住）；
- 信任门覆盖：申请的工具清单是审批清单必审区块（§B.3）。

### B.7 协议映射（复用【调研报告】§5.1 表）

| 组件 | nebflow 对应 | 本方案映射结论 |
|---|---|---|
| `skills/` + SKILL.md | SkillService 三层加载 | **近 1:1**（§B.2）；超集字段丢弃+告警 |
| 根级 `mcp.json` | PluginMcpManager | **格式兼容**（stdio+URL 一致）；命名 `mcp__plugin_<n>_<s>__<t>` 复用 agent 级前缀先例 |
| `commands/` | legacy commands（已扫 `.claude/commands`） | 已兼容，不动 |
| `hooks/hooks.json` | nebflow.json hooks（8⊆29 事件） | **本轮不导入**——hooks 可拦截/改写工具调用，信任敏感度最高，且 plugin 级 hook 与 nebflow.json 全局 hook 的叠加语义未定义；H-15 已确认①：本轮不导入，后续评估 |
| `agents/*.md` | 三定义体系 | **不映射**（协议禁止插件 agent 携带 mcpServers/hooks；nebflow 侧 agent 已收敛为三定义，无插件 agent 概念） |
| workflows/themes/monitors/.lsp.json/bin/userConfig/channels | 无 | 显式跳过 + 告警 |
| marketplace.json 分发 | 无（本地目录 + git 导入） | 阶段 2 不实现市场；`plugin add <git-url>` 已覆盖团队内分发 |

### B.8 验收点（改写自【调研报告】§5.4）

1. 【A-skills】plugin 含 `skills/<s>/SKILL.md` → 分发器 NodeEdit 分配后，node 首条消息含 `<injected-plugins>` 全文，`${SKILL_DIR}` 已替换为实际路径；agent 不需要 Read SKILL.md。
2. 【A-MCP 信任门】plugin 的 mcp server 工具以 `mcp__plugin_<n>_<s>__<t>` 注册；**未经审批（untrusted）的 plugin 不出现在分发器目录、NodeEdit 引用即 failNode**。
3. 【digest 失效】审批后修改 plugin 任一文件 → 重扫后回落 untrusted；运行中会话的该 MCP 被停用并收到系统提醒。
4. 【命名空间】user 层与 plugin 层同名 skill → user 层胜出（装载与注入两处一致）。
5. 【前向兼容】喂含未知 manifest 字段/未知组件目录的 plugin → 不崩溃、字段忽略、目录跳过、各产生一条告警。
6. 【refcount】两个并发 node 分配同一 plugin → server 进程仅 1 份；两会话先后结束后进程退出。
7. 【只有其一】只有 mcp.json 无 skills 的 plugin 可分配生效；两者全无 → 拒载 + 告警。
8. 【工具扩展】org.nebflow/tools 申请 `WebSearch` → 该 node 工具清单出现 WebSearch；申请 `Task` → 装载校验拒绝。

---

## C. agent 收敛设计

### C.1 总览：三定义 + 角色-工具静态矩阵

| | Nebula | project-dispatcher | 通用模版（general） |
|---|---|---|---|
| 位置 | `~/.nebflow/agents/Nebula/` | `~/.nebflow/agents/project-dispatcher/` | `~/.nebflow/agents/general/` |
| 提示词 | 精心设计（§C.2） | 精心设计（§C.3） | 基础内容（§C.4，裁定 6） |
| 工具来源 | 机制固定（下表），零配置 | 机制固定，零配置 | 机制固定 8 件，零配置 |
| Plugins | 无（编排层不需要；需要能力就开 project） | 无（目录注入只读） | **由 node.plugins 动态分配**（§B.4） |
| 沙箱 | 不适用（无文件工具，裁定 3） | root=project workspace | root=node projectRoot |
| 会话形态 | 持久根会话 | 单次（bridge turn 完即清，ProjectActor.scala:292-302，v3 锚点更新） | 单次 node 会话 |
| 记忆 | MemoryEdit（§C.2） | 无 | 无 |

**Nebula 固定工具集（裁定 11 + 2 + 3，全部机制注入不可配置）**：

| 组 | 工具 | 说明 |
|---|---|---|
| 编排触发 | `Task`、`ProjectCreate`、`NodeList`（只读观测）、`AgentControl`（list/status/cancel/restart） | Task/Mail(→project) 同内核双入口（TaskTool.scala:8-20）——**✅ Task 已落地 `e53ebde9`**（Nebula agent.json 声明注入，机制固定化仍属 2c）；NodeList 是 dispatcher 描述承诺的观测面（"Nebula 用 NodeList 只读查看"，现状 NebulaOrchestrationTools 缺它，补上）。**【2026-09-06 00:48 增补】NodeList 已从 Nebula 工具面摘除**（作者裁定：节点结果沿 out 边自动投递 Nebula，主动查图与「全量派发 + pending 节点、不维护状态清单」的裁定职责重叠；dispatcher 自身面/服务端 API/前端 Flow Map 不受影响）——NebulaOrchestrationTools 恰十三件 |
| 通信 | `Mail`、`SendFriendMessage` | SendFriendMessage 从"agent.json 声明注入"（FriendMessageTool.scala:28，现状仅 Nebula 声明）改为机制固定 |
| 双轨期过渡 | `Delegate`、`FlowTrigger`、`FlowExecute` | 旧 standalone/team/flow 触达保留至阶段 3（裁定 1），阶段 3 拆除（去留 H-7 已确认①：Delegate 退役） |
| 用户面 | `AskUserQuestion`、`Pop` | 不变 |
| 平台 | `Schedule`、`TransferFile` | 不变 |
| 记忆 | `MemoryEdit`（新） | §C.2 |
| **显式移除** | `Read Glob Grep Write Edit Bash MultiEdit`、`WebSearch/WebFetch/Curl`、`TeamTask*`、`SubTask`、`NodeEdit/NodeCancel` | 裁定 2/3：无文件工具；快速检索下沉给 project 节点做；Nebula 不写代码不建节点（那是 dispatcher 职责） |

**分发器固定工具集**：`NodeList / NodeEdit / NodeCancel`（Node 三件）+ `Read / Glob / Grep / Bash`（读现状 + git worktree 管理）。不给 `Write/Edit`（分发器只分解不产内容，内容写入是节点职责）、不给 `AskUserQuestion`（单次会话不阻塞等用户，歧义写进 node task 或会话结果回 Nebula，与现状 isFlowNode 剥离一致，NodeEngine.scala:220，v3 锚点更新）。

**通用模版固定工具集（裁定 5 原文顺序）**：`Read / Glob / Edit / Write / Grep / Bash / AskUserQuestion / Pop`——8 件机制固定；`MultiEdit` 不在 8 件内 → 从 ToolRegistry 删除（registry.scala:15-18），其能力由 Edit 的 replace_all 覆盖。Web 系 3 件不在 8 件内 → 经 §B.6 plugin 扩展授予。

### C.2 Nebula：精心设计提示词要点 + MemoryEdit 完整设计

**提示词要点清单**（重写 `agents/Nebula/system.md`，现 210 行含大量已退役机制描述）：

1. **身份与边界**：你是编排者，不执行。所有执行通过 Project（Task/ProjectCreate）触达 project-dispatcher；你不读文件、不写文件、不跑命令（工具集已从机制上保证——提示词只需一句自我认知，无需恳求式约束）。
2. **项目生命周期协议**：理解意图 → 无对应 project 则 ProjectCreate（workspace 路径与意图对齐）→ Task(project, 任务文本) → NodeList 观测拓扑与状态 → 节点 out=Nebula 的结果接收与综合 → 完成后向用户汇报；失败节点优先 AgentControl restart / 重新 Task 补充上下文，两次失败升级 AskUserQuestion。**【2026-09-06 00:48 增补】「NodeList 观测拓扑」一步已随 00:48 裁定从 Nebula 工具面退役**——拓扑观测由 out 边结果自动投递承接，Nebula 不再主动查图。
3. **memory 维护纪律**（裁定 2 的完整闭环）：
   - 写什么：用户事实（身份/偏好/工作风格/环境）→ `target=user`（User.md）；路由经验/技术教训/领域知识 → `target=agent`（agents/Nebula/memory.md）。条目格式沿用现状：`- <fact>（→<id> 详情在 ~/.nebflow/memory/<id>.md）`（ContextRefresher buildMemoryBlock 头注同款）。
   - 何时写：用户明示偏好、任务中验证的新路由知识、压缩前 NebulaMemoryHook 继续负责自动抽取（NebulaMemoryHook.scala:17-47 不受本裁定影响，其写路径切换到 MemoryStore 同一写函数）。
   - 生效时机认知：memory 注入在下一 lifecycle 节点生效（AgentCore.scala:573-580），提示词明示"写完不必重读验证"。
4. **双轨期知识**（阶段 3 删除该节）：Mail(team) 与 FlowTrigger/FlowExecute 仍可用；新工作一律走 Project。
5. **工具自包含认知**：所有工具用法以工具定义内描述为准；无外部手册。
6. **消息纪律**：结果综合后主动汇报；不确定即 AskUserQuestion；不空转轮询（NodeList 变化由投递事件驱动，不循环刷新）。

**MemoryEdit 工具接口语义**（新 `core/tools/MemoryEditTool.scala`（v3 更正——工具均在 core/tools/，无 agent/tools/ 目录）；替代 Nebula 的 Read/Write/Edit 记忆维护通道，现状主通道注释见 MemoryStore.scala:25）：

```
参数：
  target : "user" | "agent"        # → ~/.nebflow/User.md | ~/.nebflow/agents/Nebula/memory.md
  action : "append" | "update" | "remove" | "replace_section"
  section? : string                # ## 标题精确匹配；append 缺省 = 文件尾
  match? : string                  # update/remove 定位：条目内精确子串（首个命中）
  content? : string                # append/update 的新文本（条目级，不是整文件）
语义：
  append          = section 尾（无 section 则文件尾）追加一条目
  update          = 按 match 定位首个命中条目，替换为 content
  remove          = 删除定位条目
  replace_section = 整节替换（memory-consolidation 清理用，防多次 remove 漏删）
校验：
  目标路径白名单硬编码为上述两个文件——工具自身即路径校验层，**无任意路径参数**
  （H-1 已确认①的答案：不套 project 沙箱——Nebula 本就在沙箱外，约束内建在工具里）
结果：
  返回变更 diff 摘要 + "下一 lifecycle 节点生效" 提示；match 无命中 → 结构化报错并列出该 section 现有条目前缀供自纠
```

设计取舍：**条目化操作而非全文重写**——全文重写参数（整文件 content）不提供，杜绝一次幻觉抹掉全部记忆；`replace_section` 是唯一大粒度操作且限定节级。User.md / memory.md 双文件沿用 MemoryStore 既有读取注入链（MemoryStore.scala:33-39 → ContextRefresher.scala:254-295），零改动。

### C.3 project-dispatcher：精心设计提示词要点

重写 `agents/project-dispatcher/system.md`（现 59 行，职责描述已对但缺操作协议），要点：

1. **单次会话协议**（严格时序）：① `NodeList` 读 Flow Map 现状（活动区拓扑 + 各节点状态结果）→ ② `Read AGENTS.md`（工作区根，项目级 agent 指令）+ 必要时 Glob/Grep 工作区代码现状 → ③ 分解：按依赖关系产出节点集（粒度判据：单节点 = 一个 agent 一次会话可完成的最小可验收单元；有产出依赖才连 in/out，能并行则并行）→ ④ **worktree 评估**：多个节点会写同一批文件 → 各自建 worktree（`git worktree add .nebflow/<wt> -b <branch>`）；纯读取或互不冲突 → 直接 workspace → ⑤ `NodeEdit` 建节点/接线（含 plugins 分配，§B.4）→ ⑥ 自检（拓扑无环、入口节点有 task、下游 in 引用存在、plugins 均在目录内）→ ⑦ 结束会话（最终文本 = 分发摘要，out=Nebula 自动投递）。
2. **无持久上下文自我认知**：状态全部落 Flow Map（NodeEdit 即持久化）；不试图 Mail、不等待、不追问（歧义 → 写进节点 task 让节点自行决策，或会话结果里向 Nebula 说明假设）。
3. **Plugin Catalog 认知**：目录段里的 plugin 是唯一能力分配手段；按节点任务性质选配（检索类 → web-research；探索规划类 → explorer-toolkit；设计类 → design-spec）；宁缺勿滥（plugin 注入消耗上下文）。
4. **AGENTS.md 优先**：项目指令与本协议冲突时，项目指令约束节点执行内容，本协议约束分发动作本身。
5. **固定工具认知**：Node 三件 + 读四件；不写文件（节点干活）；Bash 仅用于 worktree/git 查询类操作，不做实际开发。

> **交叉引用（v3）**：Node→分发器反馈路径与分发器重入协议已**独立成文并实施中**——`20260902_dispatcher-reentry-feedback-design.md`（采纳方案③「结束文本结构化约定」：最终输出以 `BLOCKED` 锚定 + JSON 体，NodeEngine 终态化时解析、失败优雅降级 completed；该文档 §7 的 12 条待确认点 2026-09-02 已按建议值确认）。后端 blocked 终态全量已落地（主仓 `54c22d5b`：NodeLifecycle.Blocked + BlockedReader + FeedbackRouter + 重入 spawnDispatcher）。本节 ①-⑦ 单次会话协议不变——重入 = 同一分发器身份的新单次会话（reentryPrompt 注入反馈与 Flow Map 快照）。

### C.4 通用 agent 模版（general）：基础提示词（裁定 6"只保留基础内容"）

新建 `~/.nebflow/agents/general/`（命名 H-9 已确认① `general`）：`agent.json` 仅 `{"name":"general","description":"通用执行 agent——能力由分配的 plugins 决定"}`（无 tools 字段、无 skills、无 mcpServers、无 category 特殊分支——工具与能力全部机制决定）。`system.md` 只保留四节（目标 ≤40 行，对比 Explorer 417 行 / Coder 66 行）：

1. **会话纪律**：单次交付——收到任务（含注入的 plugin 内容与上游节点结果）后在本次会话内完成；最终一条 assistant 文本就是交付物（NodeEngine 取最后 assistant 文本为 result，extractLastAssistantText NodeEngine.scala:461-466、调用点 :262-263，v3 锚点更新），把结论、关键路径+行号证据、未尽事项写清楚。
2. **工具自包含认知**：工具用法一切以工具定义描述为准；被注入的 `<injected-plugins>` 内容是你的操作规程，直接遵循，无需再读任何 skill 文件。
3. **沙箱认知**：你的工作区是当前 project；越界操作会收到 `SANDBOX_DENIED`，按错误消息里的指引自纠；确需界外路径 → 在结果中说明而非反复重试。
4. **无团队上下文**：没有 Mail/团队/看板；需要什么信息都在任务文本与注入内容里；完成后结束即可。

**不写**的：任何领域方法论（那是 skill/plugin 的事）、任何工具清单背诵（工具定义自包含，裁定 9）、任何人格设定。

### C.5 通用 agent 8 件固定工具：语义确认与沙箱关系（裁定 5 逐一）

| 工具 | 语义要点（进工具定义，§D.2） | 沙箱关系 |
|---|---|---|
| Read | 绝对或相对路径；相对基准 = node root；live 语义（读到的永远是最新内容，无需重读） | 读围栏：root + 系统只读面 + ~/.nebflow 三子目录 |
| Glob | pattern + 可选 path；默认根 = **node root**（现状 user.dir，GlobTool.scala:68，改） | 读围栏 + 不跟随外向 symlink |
| Edit | 精确串替换；replace_all；先 Read 后 Edit 纪律写进定义 | 写围栏 + fresh 路径执行 |
| Write | 覆盖写；新文件先建祖先目录 | 写围栏 |
| Grep | 正则 + glob 过滤；默认根 = **node root**（现状 user.dir，GrepTool.scala:114，改） | 同 Glob |
| Bash | 持久会话 shell；初 cwd = node root；危险命令内容审查照旧 | Seatbelt 写强制 + cwd 锁定（§A.4） |
| AskUserQuestion | 阻塞等用户；仅真歧义时用；选项式提问 | 非文件面，豁免 |
| Pop | 展示已存在文件/URL 到 Canvas；汇报可视化产物 | 豁免（只读展示） |

---

## D. 工具体系改造

### D.1 条件注入机制移除清单（基于【盘点】§1.2 十项，逐项去向）

| # | 现状机制（位置） | 移除后去向 |
|---|---|---|
| 1 | `fixedToolsFor` BaseTools 全员注入（AgentCore.scala:1886-1893, 1923-1935） | 替换为三角色**静态集常量**（§C.1 矩阵）：NebulaSet / DispatcherSet / GeneralSet；函数本体阶段 2d 删除 |
| 2 | `category=="team"` 分支（:1928） | 双轨期保留（旧 team 会话继续跑）；阶段 3 随 team 退役删除 |
| 3 | `category=="flow"` 分支（:1929 FlowReport） | 双轨期保留；阶段 3 删除（flow 节点退役；node 结果 = 最后 assistant 文本，无需 verdict 工具） |
| 4 | `name=="Nebula"` 分支（:1930-1934，含死名 Issue） | 2c 替换为 NebulaSet 静态集；`"Issue"` 死名清理（见 §D.4） |
| 5 | FlowTrigger flows 白名单（:1454-1455；FlowTriggerTool.scala:17-20） | 双轨期保留（Nebula/Manager 用）；阶段 3 删除 |
| 6 | NebulaExclusiveTools 剥离（:1456-1457, 1851-1856） | 新模型下三角色静态集**结构上**不含他人工具（无剥离需求）；legacy 路径保留至阶段 3；`Issue` 死名清除 |
| 7 | TeamTask 防逃逸剥离（:1458-1464, 1476-1481） | 双轨期保留；阶段 3 删除 |
| 8 | AgentControl isTeamLead grant（:1465-1472；AgentControlTool.scala:175-185） | Nebula 进静态集；Manager subtree grant 双轨期保留；阶段 3 删除 grant 逻辑 |
| 9 | MCP 过滤（:1482-1494） | **保留并扩展**：追加 `mcp__plugin_<p>_<s>__` 会话级前缀来源（§B.4）；agent.json mcpServers 字段随 tools 字段一并退役 |
| 10 | 叶子剥离 isSubTaskWorker / isFlowNode / guardrails（:1495-1526；Guardrails.scala:34-48） | 新模型节点 = general 模版，静态集天然无 Mail/SubTask/Delegate/Node 工具（结构即隔离，无需剥离）；legacy 路径保留至阶段 3；guardrails `dedicatedAgents.enabled` 开关被静态集取代 |
| 11 | SendFriendMessage 声明式注入（FriendMessageTool.scala:28） | 改 Nebula 机制固定（裁定 11）；声明通道删除 |
| 12 | skill 目录注入 order 800（PromptSections.scala:382-386；SkillService buildPerAgentCatalog） | **新模型 node 会话停注**（plugin 全文注入取代"目录+自读"，裁定 8/9）；Nebula 保留目录（skill-creator alwaysVisible，Nebula 用它造 skill）；legacy 会话保留至阶段 3 |
| 13 | FlowReport 动态 append（FlowDagExecutor.scala:350-354；ContextRefresher.scala:359-370；AgentDef.scala:22-33） | 双轨期保留；阶段 3 与 flow 引擎裁决语义一起评估（node 语义=无 verdict 节点，§F.5） |
| 14 | PromptSections 条件工具段落 order 400/410/415/630/999 等（PromptSections.scala:312-416） | 见 §D.2——内容下迁进工具定义或随角色消失 |

### D.2 工具定义自包含改造（裁定 9）

**原则**：功能 + 用法 + 约束 + 反模式全部进工具定义（`name/description/inputSchema`，types.scala:67-84），提示词层不再按"是否有该工具"注入用法段落。改造项：

| 现状条件段（PromptSections.scala） | 内容 | 去向 |
|---|---|---|
| order 400 提问指南（:329-333） | AskUserQuestion 用法 | 并入 AskUserQuestion 工具 description |
| order 410 Read live 语义（:334-338） | 读即最新 | 并入 Read 工具 description |
| order 415 Pop 指南（:339-343） | 可视化汇报 | 并入 Pop description（含"专业工具→SVG→Pop"一句） |
| order 630 team 任务协议（:644+） | TeamTask 用法 | 并入 TeamTask 三件 description（双轨期） |
| order 999 Worker Identity Block（:410-430） | worker 身份 | 由 general 模版 §C.4 第 4 节取代 |
| order 395 身份条款（:319-326） | flow/team 成员条款 | 双轨期保留；阶段 3 删 |

**Antipattern 示例**（写进改造规范，逐工具执行时照此样式）：

```
反模式（提示词补丁式）：
  PromptSections: if hasTool("Bash") then "Bash 支持持久会话，cd 会保留……"
正例（自包含）：Bash 工具 description 首段：
  "在持久 shell 会话中执行命令。会话初 cwd 为项目沙箱根；cd 之后的工作目录跨调用保留
   （执行后回读 pwd 维持）。越界写盘会被 OS 沙箱拒绝并返回 [sandbox: ...] 归因——
   此时不要重试同一路径，改写沙箱根内路径。反例：不要用 Bash cat 读取大文件（用 Read）。"
```

判据：**删掉某个条件注入段后，从未见过该段的 agent 行为不应退化**——凡做不到即工具描述没写全。

### D.3 受影响代码清单（改造涉及面）

| 文件 | 改动 |
|---|---|
| `core/tools/registry.scala` | 删 MultiEdit 注册；增 MemoryEdit；死名清理（§D.4） |
| `core/tools/{Read,Write,Edit,Glob,Grep,Bash,AskUserQuestion,Pop,TeamTask*,Mail}Tool.scala`（v3 更正——工具全部在 core/tools/，无 agent/tools/ 目录） | description 自包含化；文件五件+ScriptTool 接 FileSandbox |
| `core/tools/{Read,Write,Edit,MultiEdit,Glob,Grep}Tool.scala` | 路径校验接入（ReadTool.scala:109-110 等处）；默认根改 node root |
| `agent/AgentCore.scala` | buildAllowedToolSet/buildToolList 加新模型分支（静态集 + plugin 前缀）；2d/阶段 3 删 legacy；NebulaExclusiveTools 等集合清理 |
| `agent/PromptSections.scala` | §D.2 段落下迁/删除；新模型 node 会话停注 order 800 |
| `agent/PromptSections.scala`（PromptContext case class，skillCatalog 字段同域 :59-60；v3 更正——独立 PromptContext.scala 文件不存在） | 增 `agentsMd`、`pluginCatalog` 字段 |
| `core/sandbox/`（新） | SandboxPolicy.scala、FileSandbox.scala、SeatbeltBackend.scala（+SandboxBackend trait）、probe |
| `core/tools/BashTool.scala` + `core/tools/shell.scala`（v3 更正路径） | initialDir 传参（:332,383 → shell.scala:1065-1072）；argv 包 sandbox-exec；stderr 归因 |
| `core/plugin/`（新） | PluginRegistry.scala、PluginMcpManager.scala、trust 存储 |
| `core/project/{ProjectTypes,NodeEngine}.scala` + `core/tools/NodeTools.scala`（v3 更正——NodeTools 在 core/tools/） | NodeDef.plugins 字段 + NodeEdit 参数 + spawn 注入（NodeEngine.scala:156-267，v3 锚点更新）+ NodePayload/WS 事件 |
| `core/project/ProjectActor.scala` | spawnDispatcher prompt 追加 Plugin Catalog（newTaskPrompt :193-201 / reentryPrompt :205-224，v3 锚点更新） |
| `core/tools/MemoryEditTool.scala`（新） | §C.2 |
| `core/compact/NebulaMemoryHook.scala` | 写路径对齐 MemoryStore 写函数（行为不变） |
| `RestApiRoutes.scala` / WebSocketRoutes.scala | agent.md 路由简化（§E.3）；plugin 审批面板 API（list/approve/revoke） |

### D.4 死引用清理

`"Issue"`：不在 ToolRegistry 注册（registry.scala:12-75 无）但残留于 NebulaExclusiveTools（AgentCore.scala:1855）与 fixedToolsFor（:1934）；agent.json 死声明：Nebula `CheckIssues`、Explorer `CheckIssues`、design-engineer `Screenshot`（各 agent.json 实文核实）。~/.nebflow/tools/ 下的 check-issues/issue/screenshot 外部工具残留一并归档。全部在 2c 一次清掉。

---

## E. AGENTS.md 自动注入

### E.1 现状锚点

AGENTS.md 是 **project workspace 的项目级 agent 指令**（【盘点】§3.2）。**2026-09-02 迁移后现状（v3 更正）**：canonical 位置 = 工作区根 `AGENTS.md`——ProjectCreate 脚手架写根目录（ProjectStore.scala:92-93；NodeTools.scala:796-804 ProjectCreateTool 内 agentMdTemplate；2a0dada0/e83da58b，主仓已完成迁移、旧路径 `.nebflow/Agent.md` 留 symlink 兼容），面板走 REST `GET/PUT /projects/<name>/agent.md`（RestApiRoutes.scala:322-361，v3 锚点更新；读取优先级经 97a67f3d/09c8dde0 反转——`.nebflow/Agent.md` 真文件或 symlink 存在即优先读（os.exists 跟随 symlink，已迁移项目经链接读到根文件同一内容；悬空 symlink 兜底视为不存在），缺失回落工作区根 AGENTS.md；PUT 落点与读一致，symlink 穿透写真实目标）；**无任何代码注入系统提示词**（v3 复核：ContextRefresher/PromptSections 零 AGENTS.md 引用），仅"分发器任务文本可引用"惯例。注入通道已存在：rulesMd（order 900，`~/.nebflow/projects/<folder>/NEBFLOW.md` + folder 链 rules.md，ContextRefresher.scala:407-415 / RulesStore.scala:99-101）。

### E.2 注入设计（代码位置级）

1. **读取**：`ContextRefresher.refreshTurn`（ContextRefresher.scala:372-443）内、rulesMd 读取点旁，新增 `resolveAgentsMd(projectRoot)`：`<projectRoot>/.nebflow/Agent.md` 存在 → **迁移**（见 E.3）后回落；否则读 `<projectRoot>/AGENTS.md`；结果进 `PromptContext.agentsMd`。
2. **注入**：`PromptSections` 注册表（:312-416）新增 **order 895** `# Project Instructions (AGENTS.md)`，置于 rulesMd（900）之前——AGENTS.md 是工作指令、NEBFLOW.md/rules.md 是平台规则，规则优先级更高故靠后。每个 turn 重读盘（对齐"数据源每 turn 刷新"机制，【盘点】§9），随 systemStable 在 lifecycle 节点生效。
3. **接收面**：该 project 的分发器会话 + 全部 node 会话（有 projectRoot 的新模型会话）。**不注入 Nebula**（跨多 project 编排，单 project 指令对它无意义；它需要的是 NodeList 状态与节点结果摘要）。双轨期 team/flow 会话不注入（保持旧体系行为不变）。**【2026-09-06 00:48 增补】「Nebula 需要 NodeList 状态」已过时**——00:48 裁定摘除 Nebula 面的 NodeList，节点状态经 out 边自动投递；仅分发器会话仍持 NodeList。
4. 长度护栏：>16KB 截断 + 尾注 `[AGENTS.md truncated]`（提示词膨胀防护，与 skill 目录截 200 字符同思路）。

### E.3 不留两套（裁定 13）——迁移映射

**canonical = workspace 根 `AGENTS.md`**（对齐 agents.md 行业约定与 ProjectCreate 模板）。`.nebflow/Agent.md` 双轨支持**在 2d 移除**：REST 路由简化为只读写 AGENTS.md；迁移动作在 ProjectStore.load 时执行——`.nebflow/Agent.md` 存在且根 AGENTS.md 不存在 → git mv/移动内容到根并删除旧文件；两者并存 → AGENTS.md 胜出 + WARN 日志。主仓根部现存 AGENTS.md（8.2KB，nebflow 试点 project 的 workspace 指令）即首个受益者：2d 上线后自动进入分发器与全部节点会话。

**agent 定义 → 三定义 + AGENTS.md 的迁移映射**（裁定 13"现 agent.json prompt 字段 vs AGENTS.md"）：

| 旧定义组成 | 去向 |
|---|---|
| agent.json `name/description/useWhen` | 三定义保留 name/description；其余 agent 的 useWhen 语义由 **plugin manifest description** 承接（分发器按描述分配） |
| `system.md`（领域方法论） | 蒸馏为 plugin skill（§F.1/F.2）；纯角色纪律 → 三定义之一 |
| agent.json `tools` | **字段退役**：角色静态集（§C.1）+ plugins（§B） |
| `mcpServers` / `tools/mcp/*.json` | 迁为 plugin mcp.json（trust 审批后生效）；agent 专属 MCP 装载（AgentMcpLoader）阶段 3 删 |
| `skills` 订阅 | node 级 plugins 分配；Nebula 保留订阅 |
| `preset` | node.preset（已有字段，消费待实现与 skill/mcp 同批，【盘点】§7.2——顺带在 2b 一起接通） |
| `flows` | 阶段 3 退役 |
| team `rules.md` | 迁为对应 project 的 AGENTS.md 正文（§F.4 迁移操作） |
| 全局 `prompts/system-prefix-for-*.md` | 保留为提示词前缀层（与 AGENTS.md 正交：prefix=平台行为契约，AGENTS.md=项目指令） |

### E.4 全局层裁定

**不设 `~/.nebflow/AGENTS.md` 全局注入层**。理由：全局惯例已由 `prompts/system-prefix-for-all.md`（JAR 内 fallback + prompts/ 覆盖，ContextRefresher.scala:39-61）承载，再加一层全局 AGENTS.md 是第三套全局指令面，违背"不留两套"。AGENTS.md 严格保持 **repo/project 作用域**（与 agents.md 开放标准语义一致）。此裁定与任务书"AGENTS.md 全局注入"表述的出入列入 H-8——已确认（2026-09-02 23:05 按建议①执行）：不设全局层。

---

## F. 迁移阶段计划（能力蒸馏 / 归档 / 退役时序）

### F.1 explorer → skill 蒸馏（裁定 10；素材=【盘点】§5.1）

三能力拆分，产出 **1 个 plugin `explorer-toolkit`（2 skills）+ 增强既有 visual-report**：

```
~/.nebflow/plugins/explorer-toolkit/
  plugin.json                     # name=explorer-toolkit, description="代码库探索与方案规划方法论
                                  #  （检索举证纪律 + 方案结构与验收条件设计）；可视化配 visual-report"
  skills/
    exploration-method/SKILL.md   # 要点：Read/Grep/Glob 探索路径（先入口后发散）；WebSearch/WebFetch
                                  #  外部检索；「路径+行号」举证纪律（L19-26）；结论先行的汇报结构
    solution-planning/SKILL.md    # 要点：方案结构（现状/方向/涉及文件/预期变更展示/验收条件）；
                                  #  验收条件方法论——冒烟红线（L265-277）、端到端（L279-287）、
                                  #  后端（L288-296）、前端三层 curl/Playwright/交互（L298-359）、
                                  #  自查清单（L375-393）；落盘 ~/.nebflow/docs/<folder>/ 活文档规范（L400-409）
```

- **展示能力**（Explorer system.md 的 46%，L28-217）**不新建 skill**——与既有 `visual-report`（220 行，matplotlib/graphviz→SVG→Pop）高度重叠，把 Explorer 独有的专业工具对应表（L75-94）、可读性参数（L111-128）、暗色模板（L144-200）**合并进 visual-report**（删除重复，二选一，【盘点】§5.3 判断）；`card-design` 不动。
- Explorer 原 `academic-pdf-fallback-chain` skill 订阅转 plugin（该 skill 本身已在 ~/.nebflow/skills，保留，plugin 内 reference 或保持 user 层均可——按 §B.2 冲突规则 user 层胜出，无需搬迁）。
- AskUserQuestion 澄清纪律（L219+）：蒸馏进 solution-planning 的"方案前置澄清"节。

### F.2 design-engineer → skill 蒸馏（素材=【盘点】§5.2）

```
~/.nebflow/plugins/design-spec/
  plugin.json                     # description="UI/UX 设计规格书方法论与视觉评审流程（只设计不实现）"
  skills/
    design-spec/SKILL.md          # 要点：角色边界表（L5-15，与实现/qa 分工）；四道防线（L18-37：
                                  #  设计先行→规格化实现→双重验收→教训沉淀）；规格书模板 §0-9（L41：
                                  #  目标/参考依据带链接/布局与信息架构 token 引用/交互状态机表/动效规范/
                                  #  视觉规格/边界异常/无障碍/≥5 条二值可断言验收点/参考链接）；
                                  #  检索源清单（L43-48：HIG/Material 3/Fluent 2/成熟产品范式）；
                                  #  硬约束（L53-59：只设计不写产品代码；结论二值化 PASS/FAIL+证据）
  mcp.json                        # 可选：无外部依赖则省略（裁定 12 允许只有 skill）
```

既有 `design-system`（案例库，防线④载体）、`nebflow/visual-style`（铁律）、`card-design` 保持 user 层 skill 身份，design-spec 正文引用之（按名引用 + ${SKILL_DIR} 不跨包，故正文写明"user 层 skill design-system"供注入后 agent 知悉配合关系；或在 plugin references/ 内附索引文件）。

### F.3 旧 agent 归档方式

**机制：入口文件改名（git mv），目录与历史原地保留**——`agent.json → agent.json.archived`（team 同理 `team.json → team.json.archived`；flow 目录式 `flow.json → flow.json.archived`、单文件式 `flows/<name>.json → flows/<name>.json.archived`）。

- 装载即失效：AgentLibrary.loadFromDir 以 agent.json 存在为准（AgentLibrary.scala:159-162），改名后目录成惰性残留，运行时不存在——**零代码改动、零 whitelist 改动**（~/.nebflow git 白名单 tracked 顶层不变，【盘点】§9）、`git mv` 保全历史（`git log --follow` 可追溯）。
- 相比"挪 ~/.nebflow/archived-agents/"的优势：不需要白名单扩项（whitelist 模式下新顶层目录默认不跟踪，挪出去反而丢版本保护）、回滚 = 一次 `git mv` 反向。
- 操作序列（每 agent）：`git mv agents/<n>/agent.json agents/<n>/agent.json.archived` → commit（message 注明"归档 <n>，能力已蒸馏至 <plugin>"）。team 整体退役时对 `teams/<t>/team.json` 与 `teams/<t>/agents/*/agent.json` 逐个执行。
- 归档范围：全局 26 目录中除 Nebula / project-dispatcher / 新增 general 外全部（含 15 个空壳残留目录、6 个只有 system.md 的不生效目录——空壳无 agent.json 的直接 `git rm` 清掉，只留有内容的）；51 个 team domain agents 随各 team 退役时归档。

### F.4 team 迁移归类表（9 个；任务书"10 teams"与盘点/实盘 9 个的出入在此注明，第 10 个未找到实体）

| team | 状态判断 | 迁移路径 | 优先级 |
|---|---|---|---|
| nebflow-project | 活跃；主仓即试点 project（根 AGENTS.md 已是 workspace 指令） | **首个迁移**：team rules.md 并入主仓 AGENTS.md；Backend/Frontend/Docs/qa-*/prompt-engineer/tool-engineer/cache-engineer 领域知识 → `nebflow-dev` plugin（skills 按 domain 拆）；Manager 职责 → dispatcher | 1（阶段 3 首批） |
| ReminderIsland | 活跃开发 | ProjectCreate(workspace=其 repo)；焦点链/键盘/Timer 等 swift-dev 领域知识 → plugin skills；qa-swift 验证流程 → nebflow/frontend-verification 等既有 skill 复用 | 2 |
| slideblocks | 活跃开发 | 同上（Frontend/Backend/Docs → skills 按域拆；registry 文档 → AGENTS.md） | 2 |
| nebflow-rust | 开发中 | 同上（与 nebflow-project 完全独立 repo → 独立 project） | 3 |
| nebflow-website | 维护 | 同上；Designer 蒸馏已由 §F.2 覆盖 | 3 |
| html-deck-studio | 按需 | deck-* 家族 10 agent 是**同一个能力的参数化**（正是"改提示词做专业化"的反面教材）→ 1 个 plugin（deck 制作流程 skill）+ general 节点参数化 | 4 |
| voice-recognition-test | 实验 | 迁移或随实验结束归档（H-13 已确认②：实验结束直接归档不迁移） | 5（H-13 关联） |
| czt-project | 科研长尾 | 保留至 paper 周期结束归档；物理仿真领域知识 → czt plugin | 6 |
| sipm-paper | 审稿修改中 | 同上，二轮修改完成后归档 | 6 |

通用迁移动作（每 team）：① ProjectCreate（workspace=team 对应 repo）→ ② rules.md 正文并入 AGENTS.md（§E.3 映射）→ ③ domain agents 的 system.md 方法论蒸馏 plugin skills（§F.1/F.2 样式）→ ④ 验证一个真实任务走通 project 链 → ⑤ team.json 改名归档（§F.3）。

### F.5 flow 迁移归类表（9 目录 + presentation-prep.zip 残留）

| flow | 归类 | 说明 |
|---|---|---|
| code-review | → project 节点链 | review 方法论已是 skill（review 290 行）→ 节点分配该 plugin；verdict/slots 语义由节点结果文本承载（Node 无 FlowReport，结果=末条文本） |
| git-merge / nebflow-review-merge | → project 节点链 | merge 节点 root=主仓（§A.6）；扫描/编译/审查/合并 = 4 节点 DAG |
| release-stable | → project 节点链（nebflow-project） | bump→merge→tag→build→verify→stage 序列即节点链 |
| research | → project 节点链 | planner→2×researcher 并行→verifier→writer 与 Node fanout/ Barrier join 语义同构（maxFanout 机制已在 FlowDagExecutor，node 层对应多下游 in 接线） |
| entity-creator | **双轨期保留最久** | 它创建 nebflow 实体本身（agent/team/flow），而收敛期仍在产 plugin/三定义——自举依赖；待 entity-creator 自身改造为 plugin 管理工具后归档 |
| memory-consolidation | → Nebula + MemoryEdit | 清理逻辑改由 Nebula 调 MemoryEdit replace_section/remove 完成（§C.2）；方法论做成 memory-consolidation skill |
| weekly-summary | → Schedule 触发的 project | 周日 22:00 Schedule 已有；任务文本改指 project |
| presentation-prep | → project（html-deck-studio 迁移后） | 4 路 researcher 并行 + 逐页规划与 node fanout 同构 |
| presentation-prep.zip | git rm | 残留 |

### F.6 双轨期语义边界（裁定 1 的并行期规则）

- **Mail 路由**：现状"团队名优先、project 名兜底"（MailTool routeToProject:182-197）保持不动——旧 team 在则路由 team，team 归档后自然落到 project。收敛完成的标志之一：`Mail(team)` 全部无目标。
- **新工作一律 Project**：阶段 2 起 Nebula 提示词（§C.2 第 4 节）即规定新任务走 Task/ProjectCreate；team/flow 仅承接存续期任务。
- **Task vs Mail**：Task 是 project 触发的正入口（TaskTool.scala:8-20），Mail(→project) 保留为兼容入口（不改代码，双轨自然语义）。
- **何时算阶段 3 完成**：9 team.json 与 9 flow.json 全部 `.archived`、fixedToolsFor/buildAllowedToolSet legacy 分支删除、system-prefix-for-teams/manager-prefix 退役（【盘点】§6.3 拆除清单逐项核对）。

### F.7 任务列表 team 区块退役清理清单（来源：任务列表×节点整合迭代，2026-09-02）

> 作者反馈①（2026-09-02 22:19）：任务列表面板上方的任务条目是 team 域任务，后期随 team 体系退役；退役时须可一次性删除且不伤 Flow Map 节点条目区块。**本节为速览；逐项可操作清单（含勾选框/SOP/grep 复核命令）以独立文档 `20260902_tasklist-team-retirement-cleanup.md` 为准（自动恢复源）。**

**解耦基线（已完成，v2 迭代）**：节点区块已完全独立命名——数据 `nodeCache` + 节点 WS 帧、判定 `sessionShowsNodeEntries`、渲染 `buildNodeRow/buildNodeGlyph/buildNodeSection`、CSS 全部 `.task-node-*` 前缀 + 自有 keyframes（`task-node-spin-rot`/`task-node-enter`）；与 team 区块（`sessionShowsTeamTasks`/`mergeTeamTasks`/`state.teamTasks`/任务行渲染/`.task-subgroup*`/`.task-meta` 等）零交叉引用。面板基建（`#task-list`/`.task-card`/`.task-header`/`.task-body*`/60s ticker `refreshLastActive`）为两层共用，**保留不删**。

**删除面速览**（退役时一次性执行）：

| 位置 | 删除项 |
|---|---|
| taskList.js | `sessionShowsTeamTasks`（导出）、`mergeTeamTasks`、`taskTeam`/`taskMember`、`isVisible`、`kindOf`、`taskTs`/`sortByActiveDesc`/`sortProgress`、`buildCheck`/`buildRow`、`buildGroupHeader`/`buildSubgroupHeader`/`buildEmpty`；`renderTaskList` 的 merge/filter 分支、`redraw` 的 progress 装配段与 stats 计数 |
| main.js | `onMessage('teamTaskListUpdate')` 处理器 + `sessionShowsTeamTasks` import |
| state.js | `teamTasks: {}` 缓存字段 |
| ws.js | `GLOBAL_MSG_TYPES` 的 `'teamTaskListUpdate'` |
| taskList.css | 任务行系（`.task-item*`/`.task-label`/`.task-desc`/`.task-meta`/`.task-active`）、glyph（`.task-check*` + `@keyframes spin`）、词/时间（`.task-status-word`/`.task-last-active`）、入场（`.task-entering` + `@keyframes task-enter`）、分组/空态（`.task-group-header`/`.task-subgroup*`/`.task-member-group`/`.task-empty`）、窄屏与 reduced-motion 中任务行两条 |
| spec/脚本 | smoke.spec renderTaskList 打桩段、verify-task-progress.cjs team 用例、verify-unified-panel.cjs 同步、截图脚本 team mock 段；**tasklist-nodes.spec 不动**（只断言节点区块） |
| i18n | 孤儿 key 候选：`task.sectionProgress`/`task.progressEmpty`/`task.statsProgress`/`task.globalSource`/`task.inProgressShort`（删前逐 key 全仓 grep 复核；`task.pendingShort`/`task.justNow`/`task.expand`/`task.collapse`/`flowmap.*` 保留） |

**验收**：删后 `tests/tasklist-nodes.spec.mjs` 6/6 全绿（节点区块无感）；`grep` 复核 team 域类名/函数名/帧名零残留；截图只剩节点条目区。触发时机：§G.6 阶段 3 中 team 域任务停止下发后，或作者拍板「任务列表只留节点条目」时提前执行。

---

## G. 分阶段实施计划

### G.0 顺序理由

沙箱（2a）最先：它是后续一切执行面的安全底座，且越晚上风险敞口越大。Plugins（2b）第二：agent 收敛（2c）的"专用化=分配 plugins"依赖 plugin 机制先就绪。收敛（2c）第三：三定义 + 工具静态化，此后条件注入的移除（2d）才有静态语义可回落。蒸馏归档（2e）在机制全部就位后做内容工程。阶段 3（team/flow 退役）独立成序，按 F.4/F.5 表逐个执行、可跨数周。

Feature flag 约定（每阶段一个，独立可回滚）：`nebflow.json` 增 `sandbox.enabled`、`plugins.enabled`、`agents.v2`（三定义与静态工具集切换）、`agents.legacyInjection`（false 即关 legacy 条件注入，阶段 3 删代码）。

### G.1 阶段 2a：沙箱

- **范围**：SandboxPolicy/FileSandbox/Seatbelt 后端 + probe + fail-closed；文件五工具接入；Bash cwd 锁定 + sandbox-exec 包装 + stderr 归因；SANDBOX_DENIED 消息；ToolContext 传递链。
- **验收点**：§A.8 全部 9 条 + 「AgentControl list 无滞留 Processing dispatcher 会话」（沙箱不改变会话生命周期语义的回归断言）+ 现有测试全绿。
- **回滚**：`sandbox.enabled=false` 一键回旧行为（代码路径保留一个版本周期）；git revert 粒度 = 单 PR。
- **工作量**：6–8 人日（FileSandbox 2、Seatbelt+probe 2、工具接入 2、测试 1–2）。

### G.2 阶段 2b：Plugins 层

- **范围**：PluginRegistry + trust 门 + 目录格式；NodeDef.plugins + NodeEdit + NodePayload；node 注入（skill 全文 + MCP refcount 生命周期）；分发器 Plugin Catalog；org.nebflow/tools 扩展；顺带接通 node.skill/mcp/preset 的 deprecation 与 preset 消费。
- **验收点**：§B.8 全部 8 条。
- **回滚**：`plugins.enabled=false` → NodeEdit 忽略 plugins 参数、目录不注入（NodeDef.plugins 字段留存无害）；按 PR revert。
- **工作量**：6–9 人日（Registry/trust 2、注入链 2、MCP 生命周期 2、NodeEdit/payload 1、测试 1–2）。

### G.3 阶段 2c：agent 收敛与工具固定化

- **范围**：三定义重写（Nebula/dispatcher/general）；MemoryEdit；Nebula 工具集切换（去 Write/Edit/文件六件，加 Task/ProjectCreate/NodeList/SendFriendMessage/MemoryEdit 固定）；dispatcher 固定集；general 模版 8 件；`agents.v2` flag 切换；死引用清理（§D.4）；AgentControl list 断言收敛后无游离会话。
- **验收点**：①Nebula 工具清单 = §C.1 NebulaSet 逐项断言（日志/调试接口输出工具列表比对）；②MemoryEdit 越权路径拒绝；③general node 会话工具 = 8 件 + 分配的 plugin MCP/工具扩展；④一个真实任务全链路（Task→dispatcher→2 节点→结果回 Nebula）走通；⑤旧 team/flow 会话在 flag 下行为不变（双轨回归）。
- **回滚**：`agents.v2=false` 回旧定义与旧工具装配（两套装配共存于 flag 两侧）；Nebula 定义本身在 ~/.nebflow git 内，定义级回滚 = git revert。
- **工作量**：4–6 人日。

### G.4 阶段 2d：条件注入移除 + AGENTS.md 注入

- **范围**：新模型路径的 PromptSections 段落下迁/停注（§D.1 #14、§D.2 表）；AGENTS.md 读取注入（order 895）+ REST 路由简化 + `.nebflow/Agent.md` 迁移（§E.2/E.3）；主仓 AGENTS.md 作为首个验证对象。
- **验收点**：①删段后 agent 行为判据（§D.2）抽查 5 工具通过；②分发器与节点 system prompt 含 AGENTS.md 段、Nebula 不含（调试接口断言）；③`.nebflow/Agent.md` 存量项目迁移后 REST 读写根 AGENTS.md 成功；④`agents.legacyInjection=false` 时 team/flow 会话保持可用（双轨期旧逻辑仍在，仅新模型路径清爽）。
- **回滚**：order 895 段独立注册，删段即回滚；REST 改动单 PR revert。
- **工作量**：3–5 人日。

### G.5 阶段 2e：能力蒸馏与归档

- **范围**：explorer-toolkit / design-spec plugin 制作（§F.1/F.2）；visual-report 合并增强；~/.nebflow 全局 agents 归档（§F.3 操作序列，Nebula/dispatcher/general 除外）；tools/ 死目录清理。
- **验收点**：①两个 plugin 通过 §B.8 的 1/2/5/7 条；②归档后 AgentLibrary 装载列表 = 恰好 3 个全局 agent；③`git log --follow` 可追溯任一归档 agent 历史；④蒸馏后以原 Explorer 类任务实测一轮（探索+方案产出质量不退化）。
- **回滚**：`git mv` 反向 + plugin 目录删除；无代码面。
- **工作量**：3–4 人日（skill 撰写 2、归档操作 0.5、验证 1）。

### G.6 阶段 3：team/flow 退役（独立时序，按 F.4/F.5 优先级逐个执行）

- **范围**：9 team + 9 flow 逐个迁移归档（§F.4/F.5 表）；每归档一批删对应 legacy 分支（§D.1 标"阶段 3"项）；拆除清单核对（【盘点】§6.3：system-prefix-for-teams 233 行、manager-prefix 92 行、TeamCatalog、rules.md 通道、TeamTask*/SubTask、Mail team 路由、FlowReport 契约注入）。
- **验收点**：每 team 迁移后「Mail(team 名) 返回不可路由」+「该 team 无 mounted 会话（FlowTreeRegistry）」；全部完成后「buildAllowedToolSet legacy 分支代码删除、编译零残留引用」。
- **回滚**：归档是 git mv 可逆；每 team 一个 commit，单 team 粒度回滚；已删 legacy 代码按批 revert。
- **工作量**：8–12 人日（含每 team 验证任务跑通）。

### G.7 总量

阶段 2（2a–2e）：22–32 人日；阶段 3：8–12 人日。关键路径 = 2a→2b→2c；2d/2e 可与 2c 尾部并行。

---

## H. 待确认点清单（✅ 已全部裁定：2026-09-02 23:05 作者批准方案，15 条均按建议值执行）

> **v3 落账**：下表 15 条**已确认（2026-09-02 23:05 作者批准方案，按各条建议值执行）**——「建议」列即生效裁定值，原始选项保留备查；正文相应已改为「已裁定」表述。

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| H-1 ✅ | 沙箱对 Nebula memory 编辑工具的适用：MemoryEdit 要不要按沙箱做路径校验到 ~/.nebflow | ① 工具内建白名单（仅 User.md + agents/Nebula/memory.md 两文件，无路径参数）；② 套用沙箱机制给 Nebula 配 ~/.nebflow 读写根 | **①**。Nebula 无其他文件工具，为它引入沙箱对象是过度设计；工具自身即校验层（§C.2），更简单且攻击面更小 |
| H-2 ✅ | 通用 agent 保留 AskUserQuestion（裁定 5 已含），双轨期旧 team 内 agent 是否也允许 | ① 旧 team 会话维持现状（guardrails 关闭时 flow 节点已剥 Pop/AskUserQuestion，team 成员保留）；② 双轨期统一放开 | **①**。双轨期不动旧体系（裁定 1 的"逐渐"取代语义）；新模型节点按裁定 5 全有 |
| H-3 ✅ | plugins 分配结果对用户可见性 | ① NodePayload/WS 事件增 plugins 字段 + 前端卡片徽标（沿用 skill/mcp 徽标先例）；② 仅会话面板显示注入清单；③ 仅日志 | **①**+会话面板注入清单摘要。分配是编排决策，应与 skill/mcp 同等可见（ProjectTypes.scala:56-57 先例） |
| H-4 ✅ | Nebula 移除 Write/Edit 后，临时文件/落盘产出类需求如何覆盖 | ① 全部下沉 project 节点（含"开 scratch project"模式）；② 给 Nebula 保留一个受限 Write（仅 ~/.nebflow/docs/）；③ Pop 前置：所有对外产出由节点落盘后 Pop | **①+③**。保持 Nebula 纯编排的干净边界；偶发小产出让 general 节点代写。若作者常有"Nebula 直接记笔记"需求则选②（但违背裁定 3 字面） |
| H-5 ✅ | project-dispatcher 是否也受某 project 沙箱约束（它要跨仓 git worktree 管理） | ① 受，root=其 project workspace（worktree 天然在 workspace/.nebflow/ 内）；② 不受（豁免）；③ 受+可配 additionalRoots 跨仓 | **①，预留 ③**。现架构 worktree 只建在 workspace 内（NodeTools.scala:308-314，v3 锚点更新），①无功能损失；真出现跨仓需求走显式 additionalRoots 配置（默认关） |
| H-6 ✅ | 双轨期旧 team 的 domain agents 在收敛后如何映射 | ① 双轨期 team 会话继续用旧定义不动，team 迁移时才按 §F.4 蒸馏；② 立即把 51 个 domain agent 改为 general+plugins | **①**。双轨期的定义是"旧体系原样跑到归档"；② 等于提前做阶段 3，风险与工作量双输 |
| H-7 ✅ | （补充）Delegate 在阶段 3 的去留 | ① 退役——所有执行唯一入口 = Project/Node；② 保留为"快速临时 general 会话"入口 | **①**。单一执行入口保证可观测性（一切执行有 Flow Map 状态）；快速任务 = 一个单节点 project，成本可接受 |
| H-8 ✅ | （补充）AGENTS.md 注入范围 | ① 仅 project 会话（dispatcher+nodes）；② 另设 ~/.nebflow/AGENTS.md 全局层注入一切会话 | **①**。全局惯例已由 prompts/system-prefix 承载，第三套全局指令面违背"不留两套"（§E.4）；任务书"全局注入"若另有所指请明示 |
| H-9 ✅ | （补充）通用模版 agent 命名 | ① `general`；② `worker`；③ 保留目录名 `coder` 复用 | **①**。语义中性、与 dispatcher 对仗；③ 会与已归档 Coder 混淆 |
| H-10 ✅ | （补充）Bash 读面边界强度 | ① 2a 接受 Bash 读全盘（dsh 形状，写强制+JVM 读围栏），读收窄列为 hardening；② 2a 即做 srt 形状读收窄（deny-then-allow+last-match-wins 重放） | **①**。②复杂度高且易断 realpath（需 file-read-metadata DIRECTORY 放行等细节）；写边界已 OS 级强制，读面先由 JVM 围栏承担并如实文档化 |
| H-11 ✅ | （补充）存量 flow-map 中 node.skill/mcp 旧值处置 | ① deprecated 保留展示 + NodeEdit 拒写 + 加载告警；② 加载时自动映射为等价 plugin | **①**。无自动映射保证（skill 名 ≠ plugin 名），静默转换风险大于价值 |
| H-12 ✅ | （补充）~/.nebflow 读白名单范围 | ① 仅 skills/prompts/docs 三子目录；② 全 ~/.nebflow 只读；③ 全禁 | **①**。根层有 auth.json/logto-*.env 等凭据（现场核实），②泄露面不可接受；③ 断 skill scripts/references 触达 |
| H-13 ✅ | （补充）voice-recognition-test 与两个科研 team 的归档时点 | ① 一并按 §F.4 优先级迁移；② 实验结束/论文周期结束直接归档不迁移 | **②**。无存续任务的 team 迁移是负产出；归档（§F.3）已保全部历史 |
| H-14 ✅ | （补充）plugin trust 的审批主体 | ① 仅面板人工审批；② 面板 + CLI approve；③ 支持信任链（签名的官方 plugin 免审） | **②**。③ 留给未来（无签名基础设施，先不做）；默认拒绝原则不因来源动摇 |
| H-15 ✅ | （补充）plugin hooks 组件是否纳入（调研报告 §5.1 同构项） | ① 本轮不导入（§B.7 裁定）；② 导入但走最严信任门 | **①**。hooks 可拦截/改写工具调用，叠加语义未定义；等 plugins 体系稳定后单独立项 |

---

## 自检附录

**14 条裁定核对**：#1→G/F.4-F.6；#2→C.2/D.1；#3→A 全章/C.1/C.2；#4→C/F.3；#5→C.1/C.4/C.5/D.1；#6→C.2/C.3/C.4；#7→B.1-B.2/B.7；#8→B.4（五步全链路）；#9→D.2/D.1；#10→F.1/F.2；#11→C.1 矩阵/B.6/C.2/C.3；#12→B.2（二选一校验）/B.8-7；#13→E.2-E.4；#14→B.3。无缺漏。

**A–H 覆盖核对**：A（§A.1–A.8，引用沙箱调研 §0/§1.2/§1.3/§6.2-9/§6.3/§6.4/§6.5/§6.6）；B（§B.1–B.8，引用调研报告 §2.1/§5.1/§5.3/§5.4 与盘点 §8）；C（§C.1–C.5，引用盘点 §1.2/§2/§7 与实读 agent.json）；D（§D.1–D.4，逐项对应盘点 §1.2 十项机制+行号）；E（§E.1–E.4，锚点盘点 §3.2 全部行号）；F（§F.1–F.6，盘点 §5/§6 证据+9 team/9 flow 逐个归类）；G（六阶段，均有范围/可断言验收/回滚/人日估算）；H（15 条：6 条指定 + 9 条补充——v3 已全部按建议值确认）。工作量估算齐备（G.7）。

**事实核对说明**：①任务书"10 teams"与盘点及实盘 9 个 team.json 不符（F.4 注明，第 10 个未寻获实体）；②行号引用 v3 起以主仓 main @ `2f32d0a6`（2026-09-02 23:06）为基线逐锚点复核——未变动文件（AgentCore/PromptSections/工具族/ContextRefresher 等，最后变更均早于盘点）与盘点一致；基线后变动文件（NodeEngine/ProjectActor/ProjectTypes/NodeTools/RestApiRoutes）锚点已更新，逐条依据见文末 v3 变更日志；③`~/.nebflow/plugins/` 现不存在（现场核实），为本方案新建；`~/.nebflow/tools/` 存在 check-issues/issue/screenshot 残留（§D.4 归档对象）。

**无占位符声明**：全文无 TODO/待补/XXX；原"可配置/待定"项均落在 H 清单且已于 2026-09-02 23:05 按建议值确认。

---

## v3 变更日志（2026-09-02，对齐基线：主仓 main @ `2f32d0a6`）

> v2 行号基线 = 盘点时点（≈`2c0758a8`，09-02 20:04，inventory.md 落盘时刻）。此后 main 合入 13 个 commit（`c467592f`…`2f32d0a6`）。本日志逐条记录 v3 相对 v2 的全部改动与依据 commit。

### 一、状态落账

| 位置 | 改动 |
|---|---|
| 头部 | v3 修订说明 + 基线 commit 标注；v2 撰写日期更正（原文误标 2026-09-05，实际 09-02 22:52）；裁定行补记 23:05 批准 |
| §H | 标题与引言改「已全部裁定」；15 条逐条标 ✅ 并加确认批注（已确认 2026-09-02 23:05 作者批准方案，按各条建议值执行） |
| 正文 10 处 | 「待作者拍板/作者定」→「已裁定/已确认」：§A.2（H-12①）、§A.6（H-5①预留③）、§A.7（H-1①）、§B.4 第3步（H-11①、H-3①）、§B.7（H-15①）、§C.1（H-7①）、§C.2（H-1①）、§C.4（H-9①）、§E.4（H-8①）、§F.4（H-13②） |
| §C.1 编排触发行 | **✅ 已落地标注**：Task 工具已落地 `e53ebde9`（Nebula agent.json 声明注入；机制固定化仍属 2c）——NodeList 缺位现状经复核仍准确（NebulaOrchestrationTools 未含，AgentCore.scala:1864-1872） |

### 二、交叉引用（新增，不重复内容）

- §C.3 末尾：Node→分发器反馈路径与分发器重入协议——独立成文 `20260902_dispatcher-reentry-feedback-design.md`（方案③「结束文本结构化约定」；其 §7 共 12 条待确认点按建议值确认）；后端 blocked 终态全量已落地 `54c22d5b`（2026-09-02 22:51），实施中
- §B.4 第 5 步：node 终态集补第四终态 blocked（机制引用，不展开设计）

### 三、现状偏差修订（过时锚点/路径/机制说法 → 当前态）

| 位置 | v2 原文 | v3 修订 | 依据 |
|---|---|---|---|
| §A.2 / §A.6 | node projectRoot 计算 NodeEngine.scala:142-144 | → 159-161 | NodeEngine 重排（`c467592f` barrier 修复、`54c22d5b` blocked） |
| §A.6 / §H-5 | worktree 存在性校验 NodeTools.scala:269-274 | → 308-314（createNode） | NodeTools 大幅扩展（`1917463d` NodeEdit 后台化、`c467592f`） |
| §B.4 第2步 / §D.3 | 分发器 prompt 组装 ProjectActor.scala:196-203 | → 193-201 `newTaskPrompt`；补重入组装 `reentryPrompt` :205-224 | `54c22d5b`（重入路径新增） |
| §B.4 第3步 | NodeDef ProjectTypes.scala:27-47；NodePayload :58-78 | → NodeDef 41-65；NodePayload 76-107 | `13107332`（skill/mcp/preset 字段）、`54c22d5b`（blocked 字段） |
| §B.4 第4步 / §D.3 | runWithAgent NodeEngine.scala:139-234 | → 156-267 | 同上重排 |
| §B.4 第5步 | 终态 bridge NodeEngine.scala:155-169 | → 187-201 | 同上重排 |
| §C.1 | 分发器 bridge turn 完即清 ProjectActor.scala:232-242 | → 292-302 | `1917463d`（NodeEdit 后台化）、`e62b65fc`（观察桥升格监督终态载体） |
| §C.1 | isFlowNode 剥离 NodeEngine.scala:188 | → 220 | 同上重排 |
| §C.4 | 末条 assistant 文本抽取 NodeEngine.scala:354-360 | → extractLastAssistantText 461-466（调用点 :262-263） | 同上重排 |
| §D.3（两处） | `agent/tools/*Tool.scala` | → `core/tools/*Tool.scala`——工具全部在 core/tools/，agent/tools/ 目录不存在 | 路径核实（v3） |
| §D.3 | `agent/PromptContext.scala`（或同域） | → agent/PromptSections.scala 内 PromptContext（skillCatalog 字段同域 :59-60）——独立文件不存在 | 路径核实（v3） |
| §D.3 | `core/shell/shell.scala` | → `core/tools/shell.scala` | 路径核实（v3） |
| §D.3 | `core/project/{ProjectTypes,NodeTools,NodeEngine}.scala` | → core/project/{ProjectTypes,NodeEngine} + core/tools/NodeTools | 路径核实（v3） |
| §E.1 | ProjectCreate 模板 NodeTools.scala:626-633；REST 322-358；「优先 .nebflow/Agent.md 回落根 AGENTS.md」一句带过 | → 模板 796-804（ProjectCreateTool 内 agentMdTemplate）；REST 322-361；现状段重写：canonical=工作区根 AGENTS.md + 主仓已迁移（旧路径 symlink 兼容）+ 读取优先级反转细节 | `2a0dada0`（迁移根 AGENTS.md）、`e83da58b`（symlink 兼容）、`97a67f3d`/`09c8dde0`（读取优先级反转：.nebflow/Agent.md 优先、PUT 穿透写目标） |

### 四、复核未变项（v3 逐锚点抽查通过，未改动）

- **未变动文件全部锚点一致**（最后变更均早于盘点）：AgentCore.scala（573-580 / 1454-1455 / 1456-1457 / 1458-1464 / 1465-1472 / 1482-1494 / 1495-1526 / 1851-1856 / 1855 / 1886-1893 / 1923-1935 / 1928-1934）、PromptSections.scala（312-416 / 319-326 / 329-333 / 334-338 / 339-343 / 382-386 / 410-430 / 644+）、工具族（ReadTool 109-110 / WriteTool 69-70 / EditTool 135-136 / MultiEditTool 153-154 / GlobTool 68 / GrepTool 114 / BashTool 96-146·213-248·332·383 / shell.scala 1065-1072 / registry 12-75·15-18·88-100）、projectRoot 传递链（SubTaskTool 182 / DelegateTool 340,357 / MailTool 780 + routeToProject 182-197 / ScriptTool 25）、其余（AgentMcpLoader 33-58 / SkillService 275-288·313-323 / ContextRefresher 39-61·254-295·359-370·372-443·407-415 / RulesStore 99-101 / MemoryStore 25·33-39 / NebulaMemoryHook 17-47 / FlowDagExecutor 350-354 / AgentDef 22-33 / AgentLibrary 159-162 / TaskTool 8-20 / FriendMessageTool 28 / FlowTriggerTool 17-20 / AgentControlTool 175-185 / Guardrails 34-48 / ProjectStore 92-93 / types.scala 67-84）
- **定义库现状数字仍准确**：Nebula system.md 210 行 / dispatcher 59 / Explorer 417 / Coder 66；死声明三处（Nebula·Explorer CheckIssues、design-engineer Screenshot）；全局 26 目录 / 9 team / 9 flow + presentation-prep.zip / 51 team domain agents；`~/.nebflow/plugins/` 不存在；`~/.nebflow/tools/` 残留；主仓根 AGENTS.md 8.2KB
- **§F.7 解耦基线复核通过**（`aca03858` 后仍准确，内容保留未改）：taskList.js `sessionShowsNodeEntries`(:108) / `nodeCache`(:115) / `buildNodeGlyph`(:369) / `buildNodeRow`(:384) / `buildNodeSection`(:448)；taskList.css `.task-node-*` 30 处 + keyframes `task-node-spin-rot`/`task-node-enter`

### 五、文档未涉及的新合入功能（审计边界，无对应正文可改）

引用块重设计 `d8b09d3d`、历史回放修复 `5473ccb2`、压缩报告落盘迁移 `663d23d3`（HistoryArchiver → `~/.nebflow/sessions/<sid>/compaction/`——文档无 HistoryArchiver/压缩路径引用；§C.2 仅提 NebulaMemoryHook 压缩前抽取，该机制未变仍准确）、语音修复 `c7d8980d`、终态节点显示 TTL 24h `4dfc4a39`（NodeEngine.TtlDisplayMs:473——文档未引 TTL 机制）、Flow Map 实时更新/动画/导航/节点配置系列（`49ecddc4`/`72aaf7fd`/`44b8d4ef`/`13107332`/`11e191c0`/`2bf3395f`）、micOrb（`2c0758a8`/`2c62861d`）、pure-paste 输入 `e5688808`、listDir 故障隔离 `2f32d0a6`、回放基线测试 `4348c772`/`1353f4d9`。
