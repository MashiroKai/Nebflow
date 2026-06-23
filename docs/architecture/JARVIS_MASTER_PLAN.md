# Nebflow 架构演进计划：Jarvis 编排平台

> 基于讨论整理，分阶段实施。每个阶段独立可测，验收通过后再进入下一阶段。

---

## 总体愿景

将 Nebflow 从「多 Agent 聊天应用」转变为「以 Jarvis 为核心的自主编排平台」。

| | 现状 | 目标 |
|---|---|---|
| **交互方式** | 人 → Agent → 执行 → 完成 | 人 ↔ Jarvis（聊需求）→ Jarvis 规划 → 调度器/Delegate 执行 |
| **Session 归属** | 每个 Agent 独立 session 列表，靠 nav bar 切换 | 统一 session 池，按 Agent 分组，Jarvis 常驻左侧 |
| **Agent 定义** | 静态配置文件 | 暂缓动态创建，先用现有 Agent（Jarvis 通过 Delegate 调用） |
| **执行时机** | 立即执行 | 立即（Delegate）/ 定时 / 顺序队列（调度器），支持 5h API 限额轮转 |
| **Sub-agent 记录** | 不持久化 | 完整 session 记录（只读） |
| **UI 布局** | 左 nav bar + session 列表 + 单聊天区 | 左 session 栏（Schedule 上 + Session 下）+ Jarvis 主区 + 右画板（分屏） |

---

## Phase 1：Jarvis 主 Agent + 统一 Session 模型（进行中）

### 目标
Jarvis 作为唯一对话入口，去除 Agent 导航栏，Session 统一展示，右侧画板分屏显示其他 Session。

### 任务清单

#### 后端

- [x] AgentLibrary.seedDefaults 添加 Jarvis（orchestrator 提示词）
- [x] listAgentSessions 返回全部 session + folder（不再按 agent 过滤）
- [x] listAgentSessions 时自动创建 Jarvis session（如不存在）
- [x] deleteSession 保护 Jarvis session 不可删除
- [x] createSession 默认 agent 为 Nebula（非 active agent）
- [ ] SessionMeta 新增 `description` 字段 + Encoder/Decoder
- [ ] TurnContext 新增 `sessionInfo` 字段
- [ ] ContextRefresher 新增 `buildSessionInfo`，per-turn 注入到 Jarvis 提示词
- [ ] AgentCore systemStable 拼接 sessionInfo

#### 前端 — 布局

- [x] 删除 `#nav-bar`，settings 按钮迁移到 panel-header
- [x] sidebar 上下一分为二：Schedule（上）+ Session 列表（下）
- [x] `#secondary-panel`（画板）HTML 结构 + CSS
- [x] Session 列表按 Agent 分组显示（agent-group-header）

#### 前端 — Schedule 迁移

- [x] 删除 header 的 `#reminder-btn`（时钟按钮）
- [x] 删除 `#reminder-panel`（浮窗）
- [x] scheduled-task.js 迁移到 sidebar `#schedule-section`
- [x] 自动加载定时任务（不再需要点击展开）

#### 前端 — 画板分屏（核心难点）

- [x] 点击 Jarvis session → 主区域显示，关闭画板
- [x] 点击非 Jarvis session → 右侧画板展开
- [x] 画板加载聊天历史（getHistory）
- [x] **ws.js swap 架构**：消息分发层自动将 secondary session 消息的渲染目标 swap 到 `#secondary-chat`，所有现有 handler 无需修改即支持双面板
- [x] isActive() 支持 `_secondaryActive` 标志
- [x] historyPage handler 放行 secondary session
- [x] secondary-chat.js 精简为仅 loadSecondary / sendSecondary / initSecondaryChat
- [x] 画板输入框（textarea + send + stop）
- [x] 画板状态栏（spinner + status text）
- [x] `#secondary-chat` CSS 匹配 `#chat`（padding、flex、scrollbar）
- [ ] **画板输入区完全复刻主输入区**（斜杠命令、附件、ask/skill 指示器）
- [ ] **画板前端 UI 完全一致**（检查所有视觉差异并修复）
- [ ] **画板 stop 按钮显示/隐藏**（跟随 busy 状态）

#### 前端 — 默认行为

- [x] main.js 默认 Agent 从 Nebula 改为 Jarvis
- [x] main.js 去除 session 列表按 agent 过滤
- [x] main.js 去除 activeId 按 agent 覆盖

### 验收标准

1. 启动后看到 Jarvis session，可以直接对话
2. Session 列表按 Agent 分组（Jarvis 在最上）
3. 点击非 Jarvis session → 右侧画板展开，内容与主区域完全一致
4. 画板支持发送消息、接收流式回复、工具调用、AskUser、AskPermission
5. Schedule 区域显示定时任务，可创建/删除
6. Settings 从 panel-header 访问
7. 画板输入支持斜杠命令

---

## Phase 2：Sub-agent Session 持久化

### 目标
Delegate 创建的子 Agent 对话完整持久化，以只读 session 形式在列表中展示。

### 任务清单

- [ ] DelegateTool 修改：生成 sessionId，创建 SessionMeta（agentName = 子 agent 名）
- [ ] Sub-agent 的 `sessionId` 从 `None` 改为 `Some(generatedId)`
- [ ] Sub-agent 消息走正常的 `persistIfSession()` 路径持久化
- [ ] UI messages 保存到 `{sessionId}.ui.json`
- [ ] SessionMeta 新增 `readOnly: Boolean` 字段
- [ ] 前端：只读 session 禁用输入框
- [ ] 前端：session 列表中 sub-agent session 标记来源

### 设计约束

- Delegate 的正常使用不受影响（同步/后台模式、fork、depth limit）
- Sub-agent actor 生命周期不变（执行完即停），只是对话被保留
- readOnly session 可被「提升」为可交互（future feature）

---

## Phase 3：调度器（Scheduler）

### 目标
构建持久化任务队列 + 规则引擎，支持定时执行、顺序执行、API 限额轮转。Jarvis 写计划 → 调度器控制时机 → Agent 无感执行。

### 核心原则

- **调度器只管时机**：不解析任务内容，不执行 agent 逻辑
- **Agent 感知不到调度器**：从 agent 角度看就是收到了一条用户消息
- **规则可配置**：cost window、rate limit 用 YAML 定义

### 任务清单

#### 调度器核心

- [ ] 任务对象定义：`ScheduledTask(id, title, instruction, target, schedule, status, ...)`
- [ ] 持久化任务队列（JSON 文件 or SQLite）
- [ ] 规则引擎（YAML 配置）：
  - costWindows：跳过高消耗时段（如 14:00-18:00 3倍消耗）
  - rateLimits：zai/GLM-5.2 五小时限制 → 429 → 等 5h → 重试
  - concurrency：并发控制
  - retry：失败重试策略
- [ ] 定时器：cron-like 时间触发
- [ ] 顺序控制：任务依赖链（task B after task A）
- [ ] 任务状态机：pending → scheduled → running → (paused) → completed/failed

#### 429 透明重试

- [ ] LLM client 包装层：收到 429 → 不传播错误 → 查规则 cooldown → `IO.sleep` → 重试同一请求
- [ ] Agent loop 代码完全不改
- [ ] UI 显示「paused (rate limit, resumes at HH:MM)」状态

#### 调度器触发机制

- [ ] 调度器向目标 session 发送 `UserInput(instruction)` 消息
- [ ] 支持指定已有 session 或创建新 session
- [ ] 调用方仍是 agent，调度器只控制生命周期

#### ScheduleTask 工具（给 Jarvis 用）

- [ ] 新工具：创建调度任务（instruction + target session + schedule type）
- [ ] 紧急任务：立即执行（等同于 Delegate）
- [ ] 延迟任务：放入调度器队列

#### 前端

- [ ] Schedule 面板完善：任务列表、状态显示、手动添加/删除
- [ ] 现有 reminder 系统（per-session 定时任务）迁移到统一调度器
- [ ] 任务详情视图（instruction、agent、状态、时间）

#### 规则配置格式（草案）

```yaml
# ~/.nebflow/scheduler/rules.yaml
costWindows:
  - start: "14:00"
    end: "18:00"
    timezone: "Asia/Shanghai"
    action: skip

rateLimits:
  - provider: "zai"
    model: "GLM-5.2"
    onStatus: 429
    cooldownHours: 5

concurrency:
  maxConcurrent: 1

retry:
  maxAttempts: 3
  initialDelay: "30s"
```

### 验收标准

1. Jarvis 写一个执行计划 md，通过 ScheduleTask 工具放入调度器
2. 调度器根据规则选择执行时机（跳过高消耗时段）
3. 任务执行时 Agent 正常工作，感觉不到调度器
4. zai/GLM-5.2 收到 429 → 任务暂停 → 5h 后自动恢复 → Agent 继续执行
5. 多个任务按顺序执行
6. Schedule 面板显示任务状态

---

## Phase 4：动态 Agent 创建（暂缓）

### 目标
Jarvis 自主创建 Agent，拼装 prompt / tools / memory / model。

### 说明

此阶段在 Phase 1-3 稳定后再讨论。当前用现有 Agent（Nebula、Explorer、Planner）通过 Delegate 调用即可满足需求。

### 预研方向

- Agent 定义从文件变为数据（in-memory AgentDef）
- Memory 分区选择机制（Jarvis 指定 agent 可见哪些 memory）
- 一次性 vs 持久化 agent 生命周期
- 工具权限动态分配

---

## 技术决策记录

| 决策 | 选择 | 理由 | 日期 |
|---|---|---|---|
| 画板渲染架构 | ws.js 消息分发层 swap `state.dom.chat` | 零代码重复，所有现有 handler 自动支持双面板 | 2026-06-23 |
| onMessage 多 handler | 改为数组追加 | 支持 main.js 和其他模块同时监听同一消息类型 | 2026-06-23 |
| 调度器任务格式 | 待定（YAML frontmatter + markdown 模板 vs 纯结构化） | Phase 3 启动时决定 | — |
| API 限额暂停粒度 | 任务间暂停 + LLM client 层 429 透明重试 | 不做 agent 状态冻结，复杂度可控 | 2026-06-23 |
| 动态 Agent | 暂缓 | 先用现有 Agent + Delegate 满足需求 | 2026-06-23 |
