# 分发器单例化——独立验证报告（2026-09-02）

- **验证对象**：主仓 `/Users/dev/Claude code/Nebflow` main 分支 commit `76f3808c`（基线 `2f32d0a6`）；`~/.nebflow` 设计文档 commit `f83f3b1`
- **验证节点**：独立验证（不信任上游自测结论，全部验收证据自行生成）
- **验证手段**：代码审查（git diff 逐行）＋ 隔离实例真实进程 E2E（mock LLM 可控门控 + REST headless turn 驱动）＋ 主仓全量 `sbt test` ＋ Mail 路径抽查

## 一、逐项验收结论

| # | 验收项 | 结论 | 关键证据 |
|---|--------|------|----------|
| 1 | 代码审查（范围/越界/并发防护） | **PASS** | 见 §1.1–§1.3 |
| 2① | 执行中连发 Task → 唯一 dispatcher 会话 + 注入形态 | **PASS** | 见 §2.4（验收①） |
| 2② | 无活跃分发器 → 正常 spawn | **PASS** | 见 §2.4（验收②） |
| 2③ | 终态后新 Task → 新会话（自然终态） | **PASS** | 见 §2.4（验收③）；另测 cancel 强制终态路径亦 PASS |
| 2④ | 旧多分发器拓扑路径不可达 | **PASS** | 代码层 + E2E 双重证据，见 §1.3 / §2.4（验收④） |
| 3 | 连发 ≥3 条排队按序消费 | **PASS** | 见 §2.4（排队） |
| 4 | 全量 sbt test 全绿 | **PASS** | 2013 用例全绿 / 0 失败 / 7 忽略 / 32 分 07 秒，见 §4 |
| 5 | Mail(project=) 路径抽查不回退 | **PASS** | 见 §2.4（验收5） |

**最终结论：可交付。** 全部验收项 PASS，未发现实施缺陷，无需 [verify-fix]。

---

## 二、代码审查（验收项 1）

### 2.1 改动范围核实（无越界）

`git show 76f3808c --numstat`：

```
166  42  src/main/scala/nebflow/core/project/ProjectActor.scala
309   0  src/test/scala/nebflow/core/project/ProjectDispatcherSingletonSpec.scala
```

- 仅 2 个文件，与上游声明一致。**TaskTool.scala / MailTool.scala 零改动**（两入口本就汇聚 `ProjectActor.TriggerDispatcher`，MailTool:195 / TaskTool:78 原样）。
- 未触碰前端 web/js、未触碰沙箱代码、未顺手修在飞卡死问题——均核实。
- `~/.nebflow` commit `f83f3b1`：仅 `docs/Nebflow/20260902_dispatcher-reentry-feedback-design.md` +2 行（§2.2 单例化修订引注），内容与实现一致。

### 2.2 并发 check-then-spawn 防护（代码行证据）

文件 `src/main/scala/nebflow/core/project/ProjectActor.scala`（HEAD 行号）：

- **L194–195**：`Behaviors.setup` 内创建 per-project `Ref.of[IO, Option[ActiveDispatcher]](None)`——单例判定状态载体。
- **L314–315（dispatchTask）**：`active.modify { case Some(a) => (Some(a.copy(pendingInjected = a.pendingInjected + 1)), Some(a)); case None => (None, None) }`——**原子 check-and-inject 占位**：有活跃会话 → 计数 +1 并注入（L318–323 `UserInput(source="task", replyTo=桥)`）；None → 落入 spawn 分支。
- **L350–351（dispatchReentry）**：同款 modify 原子占位——重入请求优先投递活跃会话（§2.2 修订落实）。
- **L291–293（观察桥 Completed 分支）**：`active.modify` 内原子裁决 `pendingInjected>0 → -1 保活；=0 → 拆除`。占位（+1）与终态清理（-1/清除）在同一 Ref 全序上不可交错——**占位成功则桥必见 >0，注入成功但会话被并发拆除的窗口不存在**。
- **L300（Failed/Cancelled 分支）**：`active.update(_.filterNot(_.sessionId == sessionId)) *> teardown`——致命/取消立即清登记。
- **L443（spawnDispatcher 顺序铁律）**：`active.set(Some(ActiveDispatcher(...)))` 先于首条 prompt 发送（L444）——占位在单 actor handler IO 内完成后，后续 Trigger/Reenter 一律注入，spawn 窗口内无竞态。
- **单 actor 串行**：ProjectActor 消息逐条处理（上一条 handler IO 跑完才取下一条），check-then-spawn 天然串行化，叠加 modify 原子性，双 spawn 竞态双层关闭。

### 2.3 旧 spawn 分支收口（验收④代码层）

`dispatchTask` / `dispatchReentry` 中 `spawnDispatcher` 只在 `active.modify` 结果为 `None` 分支可达（L327–329、L363–365）；「无条件 spawn」旧路径已不存在于代码。E2E 侧证据见 §3.4 验收④。

---

## 三、隔离实例 E2E（验收项 2/3/5）

### 3.1 环境与进程管理

| 项 | 值 |
|----|----|
| 隔离 HOME | `/tmp/nb-singleton-e2e-home`（auth.json 预置 token；nebflow.json 仅 mock provider 指向 `http://127.0.0.1:8099`，protocol=anthropic；model-presets 默认链 `mock/mock-1`） |
| 测试项目 | `e2e-proj`（workspace `/tmp/nb-singleton-e2e-ws`），启动自动挂载：`Startup mount: 1 project(s) mounted` |
| 网关端口 | **8093**（≠宿主 8080，≠其他在跑实例；启动前 lsof 确认空闲） |
| mock LLM | 本机 8099，anthropic SSE 协议 mock：外层会话回 Task/Mail tool_use；分发器会话回「任务完成」；prompt 含 HOLD-MARKER 且门控关闭时**阻塞响应**（模拟 turn 执行中）；全程请求日志（时间戳/sid/分支/任务标记） |
| 实例 PID | 50782（首次，因日志落点问题被主动重启）→ **52183**；kill 前 `lsof -p <pid> | grep cwd` 核对为我起的 java 进程（cwd=主仓 target classpath），**非宿主（宿主 PID 17392 / 端口 8080，全程未触碰）** |
| 驱动方式 | REST：`POST /api/sessions`（agentName=e2e-outer，tools=[Task,Mail]）建外层会话 `ee9dfe61`；`POST /api/sessions/<sid>/turn`（headless 同步 turn）连发任务 |

> 环境事故记录：首次实例未设 `NEBFLOW_HOME` env，logback 文件落点回退到宿主 `~/.nebflow/logs/nebflow.log`（追加了约 2 分钟启动期日志行，无写入/配置副作用）。发现后立即 kill 该实例、带 `NEBFLOW_HOME=/tmp/nb-singleton-e2e-home` 重启，日志改落隔离目录。

### 3.2 验收①：执行中连发两条 Task（唯一会话 + 注入形态）

时序（隔离实例日志时间戳）：

1. `01:11:18` 外层 turn 1：`TRIGGER-A: 项目初始化任务 HOLD-MARKER` → mock 回 `Task(project=e2e-proj)` tool_use → TaskTool 触发。
2. `01:11:19.835` **spawn 唯一分发器**：`Project 'e2e-proj' dispatcher session spawned: dispatcher-288cfc4d`；其 turn 的 LLM 请求被 mock **HOLD**（会话 Processing 中）。
3. `01:11:42.520` 外层 turn 2（A 仍在执行）发 `TRIGGER-B` → 日志：`task injected into active dispatcher dispatcher-288cfc4d (pending=0)`；agent 生命周期日志：`event=user-input-queued detail=textLen=63 pending=1`。
4. `01:12:00` 放行门控 → `01:12:00.960–997` turn 串行消费（msgs=1→3→5）→ `01:12:00.999 finished — unregistered`。

**断言成立**：全程仅 `dispatcher-288cfc4d` 一个 dispatcher-\* 会话；第二条任务以注入形态进入同一会话——会话落盘文件（`sessions/dispatcher-288cfc4d.json`）6 条消息逐条核验：

```
user      | 你是项目「e2e-proj」的任务分发器。…（spawn 首条 prompt，任务A）
assistant | 任务完成
user      | ── 新任务到达（注入现有分发器会话；与进行中工作按 turn 串行执行）── TRIGGER-B: 补充文档任务（第二条）
assistant | 任务完成
user      | ── 新任务到达（注入现有分发器会话；与进行中工作按 turn 串行执行）── TRIGGER-D: 第三条排队任务
assistant | 任务完成
```

### 3.3 验收②：无活跃分发器 → 正常 spawn

- 初始无任何分发器，任务 A 直接走 spawn：`spawned: dispatcher-288cfc4d`（01:11:19.835）。
- 终态拆除后（§3.4）任务 E 同样直接 spawn：`spawned: dispatcher-7c4904b6`（01:13:20.452）。

### 3.4 验收③：分发器终态后 → spawn 全新会话（自然终态路径）

- `dispatcher-288cfc4d` 于 `01:12:00.999` 自然终态拆除（`finished — unregistered`，无 cancel 干预——**未启用 fallback 路径**）。
- `01:13:20.452` 任务 E spawn **全新 sessionId** `dispatcher-7c4904b6`，mock 日志可见其独立 LLM 请求（非注入形态），`01:13:20.481` 自然终态。

**补充（cancel 强制终态路径，验收③注的 fallback）**：任务 F（HOLD 挂起，`dispatcher-c25c6a8e`）→ 面板路径 `cancelAgent`（`POST /api/command {"type":"cancelAgent",...}`）→ `01:13:45.925` cancel → `01:13:45.927` **立即拆除（2ms，无延迟等待）** `finished — unregistered` + `event=stop detail=reason=user` → 任务 G `01:13:57.724` 正常 spawn 新会话 `dispatcher-b9af178f`——**Failed/Cancelled 立即拆除 + activeRef 清理 + 后续可正常 spawn，全链路 PASS**。

### 3.5 验收④：旧多分发器并行拓扑路径不可达

- 代码层：spawn 仅可达于 `active.modify` 的 `None` 分支（§2.3）。
- E2E：A 执行中连发 B、D 两条（间隔 9s），gateway 日志 spawn 行**仅 1 条**、injected 行 2 条；mock 日志中 dispatcher 分支请求全部 `sid=dispatcher-288cfc4d`；会话落盘仅 1 个 dispatcher-\*.json。**全程未出现第二个 dispatcher-\* 会话。**

### 3.6 验收（排队项）：连发 3 条按序消费

- 3 条任务：A（执行中/HOLD）、B、D（排队）。B/D 注入后 mock **未收到任何**分发器 LLM 请求（pendingUserInputs 排队，`user-input-queued pending=1/2` 日志为证）。
- 放行后 mock 请求顺序：`A RELEASED (01:12:00.960)` → `immediate (00.979)` → `immediate (00.992)`，全部 `sid=dispatcher-288cfc4d`；gateway 生命周期日志 turn 串行（turn-complete msgs=1→3→5）；会话文件消息序 = A→B→D。**一 turn 一条、按序消费，证据闭合。**

### 3.7 验收5：Mail(project=) 路径抽查

- 外层会话发 `TRIGGER-MAIL` → mock 回 `Mail(address=e2e-proj)` tool_use → 日志 `Tool Mail(→e2e-proj) OK (4ms)` → `01:14:08.100 spawned: dispatcher-03f679d7` → `01:14:08.124` 自然终态。**Mail 入口与 Task 入口行为一致，未回退。**

### 3.8 清理

- 实例（52183）与 mock（51669）kill 前 PID/cwd 双核对（非宿主），kill 后 `lsof` 确认 8093/8099 均无监听（双端口 exit=1）。
- `rm -rf /tmp/nb-singleton-e2e-home /tmp/nb-singleton-e2e-ws`——临时 HOME 与工作区已删除（复核 ls 报 No such file or directory）。

---

## 四、全量回归（验收项 4）

- 主仓真实跑：`sbt test`（Java 23.0.1 / sbt 1.10.10）——**`Passed: Total 2013, Failed 0, Errors 0, Passed 2013, Ignored 7`，总耗时 1927s（0:32:07）**。
- 单例化 spec 直接复跑（本验证节点独立执行）：`Test/testOnly nebflow.core.project.ProjectDispatcherSingletonSpec` → **4/4 PASS**（连发注入 9.7s / 并发单 spawn 8.2s / 终态后重 spawn 7.5s / 重入优先投递 8.4s）。
- 工作区状态（`git status --short`）：仅 2 个未跟踪文件 `CONTRIBUTING.md`、`CONTRIBUTING.zh-CN.md`（并行节点遗留文档，未编译进测试，对结果纯度无影响）；无未提交的代码改动混入。

## 五、实施缺陷处置

未发现逻辑错误或结构性问题，无需 [verify-fix]，无 FAIL 上报项。

## 六、上游实施要点转述

- **改动范围**：主仓仅 `ProjectActor.scala`（+166/−42）＋ 新增 `ProjectDispatcherSingletonSpec.scala`（309 行）；`TaskTool`/`MailTool`/前端/沙箱零改动；`~/.nebflow` 仅设计文档 +2 行（f83f3b1）。
- **机制一句话**：ProjectActor 持 per-project `activeRef`（spawn 置位、观察桥终态清除），Task/Reenter 到达时 `modify` 原子 check-and-inject——有活跃会话注入排队（turn 边界串行，`pendingInjected` 每任务恰配一个 Completed 的延迟拆除），无则 spawn；单 actor 串行 + Ref 全序关闭双 spawn 竞态。
- **MailTool 冲突面清单（供 sandbox-2a 合并参考）**：`MailTool.scala` 零改动、`TaskTool.scala` 零改动——两入口在 MailTool:195 / TaskTool:78 原样汇聚 `ProjectActor.TriggerDispatcher`，单例判定收口在 ProjectActor 内部；本实施与 sandbox-2a（ToolContext 传递链）**无合并冲突面**。

## 七、结论

**分发器单例化实施（76f3808c）通过全部独立验证，可交付。** 建议下游按原计划推进。
