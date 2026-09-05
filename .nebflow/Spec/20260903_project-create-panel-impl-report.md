# ProjectCreate「未知路径 AskUser 式交互面板」实施报告

> 节点：ProjectCreate 工具补齐「未知路径 AskUser 式交互面板」——实施（worktree 独占施工）
> 分支：`proj-create-tool`（worktree `.nebflow/worktrees/proj-create-tool`，基线 main@bc95dec1 + merge main→7ba589f3）
> 日期：2026-09-03　设计依据：`20260903_stage2-migration-plan.md`（v2）§6.2
> 状态：**实施+测试完成，全量 sbt 绿；生效需重建+重启（本次未做）；未合并回 main（由下游「合并」节点执行）**

---

## 0. 上游结果要点转述（一句话）

上游「合并-面板归档按钮进main」BLOCKED——验证节点未产出判定（result 0 字符），**归档按钮未进 main**；本节点任务与该功能无耦合（改动隔离在 proj-create-tool 分支且严禁自行合并），照常完成，仅此如实记录。

## 1. 现状盘点（带代码行证据）

| 项 | 盘点结论 | 证据 |
|---|---|---|
| worktree 基线 | `proj-create-tool` @ bc95dec1（分发器预建），开工 `git merge main` fast-forward → `7ba589f3`（toast 毛玻璃重设计），**零冲突**；main 当前不含归档按钮合并（上游 BLOCKED） | git log |
| ProjectCreateTool 现状 | **已存在**（NodeTools.scala:1038 注册于 registry.scala:71）。参数 schema：`name`+`workspace` **双必填**，缺省运行时直接 `ToolError("'name' and 'workspace' are required")`——**无任何交互兜底**。创建链：`ProjectStore.create`（project.json + workspace AGENTS.md 模板 + .gitignore 防 `.nebflow/` 污染）→ `ProjectRuntimeRegistry.mount`（幂等，rootSessionId=上链根）。幂等路径缺陷：同名已存在时**无论 workspace 是否一致**直接 load+mount——异 workspace 同名被静默复用旧定义 | NodeTools.scala:1064-1106（改前）、ProjectStore.scala:66-97 |
| AskUserQuestion 机制 | 工具构造 AskItems → `ctx.agentActorRef.?(AgentCommand.AskUser(requestId, items, replyTo))`（timeout=None 阻塞）→ AgentActor handler（AgentActor.scala:2458-2503）标记 WaitingForUser + 投真实 InteractionHub → hub 注册 pending 槽 + 向根窗口渲染 `askUser` 帧（InteractionHub.scala renderAskUser）→ 前端 `renderAskUser`/`showOptions` 渲染卡片（chat.js:1890-1956，`data-request-id` 标签、`.option-btn`、`.option-custom-input` Other 输入、确认/取消键）→ 用户点选发 `askUserAnswer` 帧（chat.js:1940；取消=answers:['__cancelled__'] 哨兵，chat.js:1946）→ WebSocketRoutes.scala:944-956 转发 → hub `Answered` 按 requestId 匹配 + **形状校验**（answerCompletes：AskUser 槽只认 answers 字段，InteractionHub.scala:276-281）→ 回填 replyTo → 工具恢复 | 全链实读 |
| #43 修复（刚落 main） | 三 commit：`3c551f76`（前端 persistence.js `askUserAnswerText` 排除 injected:true 的 agent 注入气泡——只有用户发起的非 injected 相邻 user 消息才算已录答案）、`2a251ade`（后端契约 spec：agent 形态负载【无 answers 字段】不完成 pending 槽 + 排队 FIFO drainBarrier）、`5e4f2ddf`（pending 判定回看跳过注入气泡，刷新后卡片重建可交互）。语义：**pending 期间 agent 侧消息排队、仅用户发起输入可作答** | git show 3c551f76 / AskUserPendingInjectionSpec.scala |
| TaskTool 触发链 | `Task(project=…)` → `ProjectRuntimeRegistry.get(p)`（未挂载即报错提示 ProjectCreate）→ `rt.actorRef ! TriggerDispatcher`（fire-and-forget）→ ProjectActor 单例化分发器（无活跃 → spawn 会话；有 → 注入排队） | TaskTool.scala:57-80、ProjectActor.scala:202-203/324-347 |
| GatewayMain startupMount | 启动幂等挂载磁盘项目（GatewayMain.scala:424-445），与 ProjectCreate 运行时主动挂载互补——新建 project 无需重启即已挂载，重启后也由 startupMount 兜住 | GatewayMain.scala:420-423 注释 |

## 2. 改动清单（文件/函数级，分支 `proj-create-tool`）

### c8f60d9a feat(ProjectCreate)：后端（4 文件，+709/-21）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/tools/NodeTools.scala` | **ProjectCreateTool 重写**：① `call`：workspace 缺省→`pathPanel`；workspace 给定但 `os.Path` 解析失败（不可用）→`pathPanel` 兜底；正常→`createChain`。② `createChain`（新，直建与面板共用）：name 缺省=basename 派生（空→明确报错）；幂等路径加**同名冲突语义**（`sameWorkspace` 归一化比较：同 workspace→already-exists 幂等挂载；异 workspace→明确 ToolError）；成功消息附 `Task(project='<name>', task=...)` 触发提示。③ `pathPanel`（新）：`AskUserQuestionTool.askGuard()` headless 拦截 → 候选扫描（`scanCandidates`：candidatesRoot 一级目录、排除点目录与已占用 workspace、排序、max=8）→ 单问 AskItem（候选路径为选项、`allowOther=true` 自由输入兜底）→ `AgentCommand.AskUser`（timeout=None）→ 答案落定 `restoreRegistryAfterAnswer` 配对恢复 → `applyPanelAnswer`。④ 纯函数（spec 覆盖）：`scanCandidates`/`expandTilde`/`parsePanelAnswer`（首槽空/`__cancelled__`→Shelved；`~` 展开后非绝对→BadPath；合法→Chosen）。⑤ `candidatesRoot` 触点：默认 `~/Claude code`，系统属性 `nebflow.projectcreate.candidates-dir` 可注入（spec 隔离）。⑥ `stripTrailingSlashes` 辅助。⑦ description/inputSchema 自包含更新（已知路径直建/未知路径弹面板/创建后 Task 触发/冲突与取消语义），`required` 清空（name/workspace 均可选）。⑧ 新增 import：`ActorRef`/`AgentCommand`/`AskItem`/`AskOption`/`Try` |
| `src/main/scala/nebflow/core/tools/AskUserQuestionTool.scala` | `restoreRegistryAfterAnswer` 可见性 `private`→`private[tools]`（ProjectCreate 面板复用同一配对恢复实现——**零语义改动**，注释说明） |
| `src/main/scala/nebflow/core/tools/registry.scala` | 注册点注释同步（workspace 缺省弹 AskUser 式路径面板） |
| `src/test/scala/nebflow/core/project/ProjectCreatePanelSpec.scala` | **新 spec（7 用例）**，见 §4 |

### 0f71bb6b test(web)：前端验证（3 文件，+318）

| 文件 | 改动 |
|---|---|
| `tests/fixtures/project-create-panel/harness.html` | 静态 harness：导入真实渲染模块（chatView/state/chat），捕获 WS 帧，`__panel()` 走与 onMessage 'askUser' 完全相同的 `renderAskUser` 活路径 |
| `tests/fixtures/project-create-panel/project-create-panel-fixture.json` | 面板形态载荷（3 候选路径 + allowOther） |
| `tests/project-create-panel.spec.mjs` | Playwright 5 用例：渲染/点选载荷/自由输入载荷/取消哨兵/亮暗截图 |

**前端产品代码：零改动**（面板 = AskUserQuestion 卡片本体；**i18n 零新增 key**——卡片 chrome key `chat.confirm`/`chat.cancel`/`chat.other` 中英已成对（zh-CN.js:502-512 / en.js:498-508），问题与选项文本是后端数据非 UI chrome）。

## 3. 面板交互设计说明

- **触发条件**：`workspace` 缺省（未知）或解析失败（不可用）。`name` 给定则创建时沿用；缺省则取所选路径 basename。
- **候选路径来源**：`~/Claude code/` 一级子目录（用户项目惯例根），排除：点目录、已被任何 project.json 登记为 workspace 的目录（`ProjectStore.list()` 实时比对）、按名称排序、最多 8 个。候选为空不阻塞——卡片仍有 Other 自由输入。
- **自由输入兜底**：卡片内建 `其他…`（allowOther），接受绝对路径，支持 `~` 展开；非绝对输入→明确报错不创建。
- **取消语义**：卡片取消键→`answers=['__cancelled__']` 哨兵（既有前端行为）→工具返回 Right + 明确搁置消息（"shelved … no project created"），零创建零挂载；空答案同搁置。**无悬挂**：headless→askGuard 报错；无 agent 会话（REST 直调/harness）→立即报错；hub 缺席→AgentActor 取消 ask 回 Nil→搁置消息。
- **超时语义**：与 AskUserQuestion 完全一致——**无人工超时**（pending 期间会话标 WaitingForUser，豁免 TaskStuckWatcher；退出路径=作答/取消哨兵/会话中断）。不另起一套并行问答通道（任务红线）。

## 4. 与 #43 修复的兼容性说明（逐条）

| #43 语义点 | 本面板关系 | 兼容性证据 |
|---|---|---|
| pending 槽形状校验：AskUser 槽只被带 `answers` 字段的帧消费 | 面板槽 = `InteractionKind.AskUser` pending，复用同一 `answerCompletes` | spec ③：真实 requestId + agent 形态负载（`{"text":…}` 无 answers）→ hub 日志 `answer shape does not match kind=AskUser — card RETAINED`，工具未返回（race 判定 still-pending）；随后用户 answers 帧正常完成创建。**未改 #43 任何代码** |
| 排队语义：pending 期间 agent 侧消息入 pendingEvents 队列、turn 恢复后 drainBarrier 按序完整注入 | 面板派发的就是 `AgentCommand.AskUser`，排队机制原样适用（同一 handler） | 零改动；AskUserPendingInjectionSpec (d) 用例继续钉住 |
| 前端 restore 答案来源校验（injected 气泡非答案） | 面板答案来自用户点选（非 injected user 消息），是唯一合法来源 | 前端零改动；Playwright 用例 2/3/4 断言回帧即用户点选/键入值 |
| WaitingForUser 配对恢复 | 面板答案落定后调用**同一个** `restoreRegistryAfterAnswer`（仅放开可见性） | 单实现共用，无分叉副本 |

## 5. 四条验收口径逐条自证

| 口径 | 结论 | 证据 |
|---|---|---|
| ① 已知路径直建成功 | ✅ | spec ①：`ProjectCreate(workspace=…)`（name 缺省）→ project.json 落盘（name=basename `ws-alpha`、workspace/agentFile/createdAt/description 全断言）+ AGENTS.md/.nebflow/.gitignore 脚手架 + 幂等挂载（rootSessionId=上链根）+ 返回消息含 `Task(project='ws-alpha'` |
| ② 未知路径弹交互面板 | ✅ | spec ②：缺省 workspace → 真实 InteractionHub pending 产生（根窗口渲染帧带 requestId + 候选项）；候选=排序后一级目录且排除已占用（alpha）与点目录（.hidden）；问题文本含候选根；allowOther 兜底。样式=AskUserQuestion 卡片本体（复用即对齐）；Playwright 用例 1 断言卡片结构（`data-request-id`、3+1 按钮、确认禁用/取消可用） |
| ③ 面板选择后创建成功 | ✅ | spec ③：点选候选路径 → 创建链直通（project.json workspace=所选路径、name=basename、已挂载）；#43 兼容断言内嵌（见 §4）；Playwright 用例 2 断言回帧载荷逐字段正确 |
| ④ 创建后 Task 可触发 | ✅ | spec ④：ProjectCreate → `TaskTool.call(project='trigger-proj')` 返回 "dispatcher triggered" → **真实分发器会话拉起证据**：engine wsSend 帧出现 `agentStart.nodeSessionId=dispatcher-104ec4af`（前缀 DispatcherSessionPrefix），录制 LLM 单 delta 终态后观察桥拆除、registry 清空——全链真实（隔离 HOME + 预置分发器定义，照 ProjectDispatcherLifecycleSpec harness） |

补充语义（任务明示）：⑤ 幂等/冲突——spec ⑤：同名同 workspace 重复调用→"already exists" 且保持挂载；同名异 workspace→明确报错（含既有 workspace 路径）。⑥ 取消/超时——spec ⑥（取消哨兵/空答案→搁置不创建；非绝对输入→报错不创建）+ spec ⑦（无 agent 会话→立即报错不悬挂）；超时=与 AskUserQuestion 同语义（§3）。

## 6. 测试结果（全部真实执行，前台）

| 项 | 结果 |
|---|---|
| 新 spec（隔离临时 NEBFLOW_HOME，`PathUtil.setDataRoot(tempRoot)`，禁碰真实 `~/.nebflow/projects`；既有 spec 零改动） | **7/7 绿**（①-⑦ 如上），耗时 ~13s |
| 变异验红 | 短路面板分支（未知 path 直接报错 `'workspace' is required (panel short-circuited)`）→ **②③⑥ 红**（waitUntil 面板 pending 超时）+ **⑦ 红**（报错文案断言失败）= Failed 4 / Passed 3（①④⑤ 直建路径保持绿）；恢复后 **7/7 绿** |
| Playwright（真实浏览器，临时静态服务随机高位端口，非 8080） | **5/5 绿**（渲染/点选载荷/自由输入/取消哨兵/亮色截图），3.2s |
| 截图（亮暗双主题，fullPage） | `~/.nebflow/docs/Nebflow/20260903_project-create-panel-dark.png`（44KB）/ `…-light.png`（42.5KB），已 commit ~/.nebflow@76632fa |
| **全量 sbt test**（worktree 内前台一次跑完） | **Total 2188, Failed 0, Errors 0, Ignored 7 —— 全绿，耗时 489s（8m09s）** |

## 7. 生效说明

需 **重建 + 重启** Nebflow 实例后生效（工具定义与后端行为随 jar 打包；`sbt package`/发行构建 + 重启）。本次未做（任务边界：全量绿后停手，合并由下游节点执行）。前端无改动，无需单独构建。

## 8. 分支 commit 与报告

| 项 | 值 |
|---|---|
| 分支 commits（`proj-create-tool`，main..HEAD） | `c8f60d9a`（后端实现+spec）、`0f71bb6b`（Playwright 验证） |
| ~/.nebflow commits | `76632fa`（截图 commit）、本报告 commit（见文件同名节点） |
| 报告路径 | `~/.nebflow/docs/Nebflow/20260903_project-create-panel-impl-report.md` |
| 环境纪律 | 零 8080/宿主进程接触；静态服务随机端口用毕即 kill（afterAll + 已验证无残留）；隔离 HOME spec 全程，真实 `~/.nebflow/projects` 未被测试触碰 |

## 9. 遗留问题

1. **未合并回 main**——按任务边界留给下游「合并」节点（QA 通过后执行）。合并时注意：本分支基于 main@7ba589f3，若归档按钮分支（proj-archive-btn@5654aca9）先行合并，二者前端改动无交集（本分支前端仅新增 tests/，零产品代码），预期零冲突。
2. `candidatesRoot` 硬编码默认 `~/Claude code`（本机惯例根）。未来若需用户可配置，可挂 nebflow.json 顶层键（模式同 BashResilienceConfig）。
3. 面板问题文本为中文（该面板的用户即本机用户）；若未来多用户/多语言需求，需把问题文本改经 i18n 或 Nebula 提示词注入。
4. 候选排除依赖 project.json 的 workspace 字段登记——手工目录（无 project 定义）会照常出现在候选里，属预期（用户点选即确认）。
