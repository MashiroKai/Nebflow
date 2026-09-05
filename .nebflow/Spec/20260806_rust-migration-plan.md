# Nebflow Rust 迁移方案

> 基于 Actor 性能瓶颈分析 + Rust 迁移可行性分析，本文档定义了从 Scala/cats-effect 到 Rust/tokio 的全量迁移目标、分阶段计划和验收条件。

---

## 1. 迁移总目标

### 1.1 最终形态

Nebflow Rust 版是一个 **单二进制 + Agent 库** 的架构：

```
nebflow (单二进制 ~20MB)
├── nebflow-gateway    — HTTP + WebSocket 服务，托管前端 UI
├── nebflow-agent      — Agent 执行引擎（可独立作为库使用）
├── nebflow-core       — 工具系统、LLM 接口、协议层
└── nebflow-cli        — 命令行入口
```

**核心改变**：
- 不再有 JVM 启动开销（3-5s → 0.1-0.5s）
- 不再有 GC 暂停（消除 SSE 流处理中的 STW 中断）
- 内存占用从 300-500MB 降到 20-30MB
- Agent 作为可嵌入式库，支持 `Agent::new(config).spawn()` 式调用

### 1.2 与现有功能的对应关系

| Scala 模块 | Rust 模块 | 功能 | 迁移策略 |
|-----------|----------|------|---------|
| `actor/` (520 行) | `nebflow-agent/src/runtime/` | Agent 消息循环 | 重写为 tokio task + mpsc channel |
| `agent/` (5,536 行) | `nebflow-agent/src/` | Agent 执行引擎 | 核心重写，最大模块 |
| `core/tools/` (8,342 行) | `nebflow-core/src/tools/` | 20+ 工具 | 逐个迁移，trait 抽象 |
| `core/entity/` | `nebflow-core/src/entity/` | Agent/Team/Flow 定义 | 类型映射 |
| `core/flow/` | `nebflow-core/src/flow/` | Flow DAG 执行器 | 逻辑迁移 |
| `core/compact/` | `nebflow-core/src/compact/` | 上下文压缩 | 逻辑迁移 |
| `core/mcp/` | `nebflow-core/src/mcp/` | MCP 协议客户端 | JSON-RPC 重写 |
| `llm/` (2,350 行) | `nebflow-core/src/llm/` | LLM 接口 + Provider | reqwest + SSE 重写 |
| `gateway/` (7,791 行) | `nebflow-gateway/src/` | HTTP/WS 服务 | axum 重写 |
| `neblink/` (1,427 行) | `nebflow-core/src/neblink/` | 设备发现 | 逻辑迁移 |
| `cli/` (2,967 行) | `nebflow-cli/src/` | CLI | clap 重写 |
| `shared/` (1,292 行) | `nebflow-core/src/types.rs` | 共享类型 | enum + serde |
| 前端 (235 文件) | `nebflow-gateway/static/` | HTML/CSS/JS | 直接复制，零改动 |

### 1.3 架构对比

**当前 (Scala)**：
```
JVM (200-400MB)
└── cats-effect IO Runtime
    ├── ActorSystem (自研, Queue + Fiber)
    │   └── AgentActor (每个 ~2KB Fiber)
    │       ├── LLM 流式调用 (fs2 Stream)
    │       ├── 工具执行 (IO[Either[ToolError, String]])
    │       └── WS 事件发射 (json → wsSend)
    ├── http4s Ember Server
    │   ├── REST API (ChatRoutes, RestApiRoutes)
    │   └── WebSocket (WebSocketRoutes)
    └── sttp HttpClient (共享 backend)
```

**目标 (Rust)**：
```
Native Binary (5-15MB)
└── tokio Runtime
    ├── AgentRunner (tokio task + mpsc channel)
    │   ├── LLM 流式调用 (reqwest + async stream)
    │   ├── 工具执行 (async fn call → Result<String>)
    │   └── WS 事件发射 (serde_json → tx.send)
    ├── axum Server
    │   ├── REST API (Router + handlers)
    │   └── WebSocket (axum::extract::ws)
    └── reqwest Client (共享连接池)
```

---

## 2. 技术栈选型

| 组件 | Scala 当前 | Rust 选型 | 选型理由 |
|------|-----------|----------|---------|
| 异步运行时 | cats-effect IO (1,914 处) | **tokio** (1 worker thread pool) | Rust 异步生态事实标准，work-stealing 调度器，与 cats-effect 模型最接近 |
| Web 框架 | http4s Ember (37 处) | **axum** | tokio 团队出品，类型安全路由，与 tower 中间件生态无缝集成 |
| WebSocket | http4s WebSocket | **tokio-tungstenite** | 轻量纯 Rust WS 实现，axum 原生集成 |
| HTTP 客户端 | sttp + fs2 backend (68 处) | **reqwest** | 异步优先，连接池管理，SSE 流式读取，rustls TLS |
| JSON | circe (2,819 处) | **serde_json** (默认) + **simd-json** (热路径) | serde_json 比 circe 快 ~4.5x；simd-json 在 SSE 解析等批量场景进一步提速 ~1.4x |
| SSE 解析 | fs2 Stream + 手工行分割 | **eventsource-stream** + **tokio_stream** | 专为 SSE 设计的 stream 解析器，背压友好 |
| JSON-RPC (MCP) | 手工实现 | **jsonrpsee** 或自研 | MCP 协议简单，自研 < 200 行 |
| Agent 模型 | 自研 Actor (Queue + Fiber) | **自研** (tokio::mpsc + tokio::task) | 不用 Actix/actix-actor 等重量级框架，保持可嵌入式 |
| 配置 | circe JSON 解析 | **serde** + **toml** | 编译期派生，零反射 |
| 日志 | logback (XML) | **tracing** + **tracing-subscriber** | 结构化日志 + span 追踪，与 tokio 深度集成 |
| CLI 参数 | scopt (4.1.0) | **clap** (derive API) | Rust CLI 事实标准，编译期校验 |
| 文件操作 | os-lib (0.11.3) | **std::fs** + **tokio::fs** | 标准库足够，异步用 tokio::fs |
| 进程执行 | java.lang.ProcessBuilder | **tokio::process::Command** | 异步子进程，支持 stream stdout/stderr |
| Diff | java-diff-utils (4.12) | **similar** | 纯 Rust diff 库，性能优秀 |
| 搜索 (Grep/Glob) | java ProcessBuilder 调用 rg/find | **直接调用系统命令** (保持不变) | 维持调用 ripgrep / find 的策略 |
| 序列化 (Session 持久化) | circe JSON → 文件 | **serde_json** → **tokio::fs** | 原子写入 (write tmp + rename) |
| 加密/认证 | 自研 Token (HMAC) | **hmac** + **sha2** | 标准 crate |
| YAML | circe-yaml | **serde_yaml** | 配置文件解析 |

### 关键设计决策

**为什么不用 Actix / actix-actor？**

Actix 是一个完整的 Actor 框架，有独立的运行时和监督策略。Nebflow 的需求更简单：
- 每个 Agent 是一个 `tokio::task`，持有 `mpsc::Receiver<AgentMessage>`
- 不需要 ActorSystem 的全局注册表（改为 `HashMap<AgentId, AgentHandle>`）
- 不需要 death watch（改为 `tokio::task::JoinHandle` + `Drop` trait）
- 目标是**可嵌入式库**，不是独立运行时

---

## 3. Agent 库 API 设计

### 3.1 核心 Trait 和 Struct

```rust
// ════════════════════════════════════════════════════════════════
// nebflow-agent/src/lib.rs — Agent 库入口
// ════════════════════════════════════════════════════════════════

/// Agent 定义 — 对应 Scala AgentDef
/// 从 agent.json / system.md 加载，或代码直接构造
pub struct AgentDef {
    pub name: String,
    pub description: String,
    pub tools: Vec<String>,           // 工具白名单，"*" 表示全部
    pub system_prompt: String,
    pub avatar: Option<String>,
    pub display_name: Option<String>,
    pub voice_enabled: bool,
    pub model: Option<AgentModelConfig>,
    pub category: AgentCategory,      // standalone | team-lead | team-member | flow-node
    pub mcp_servers: Vec<String>,
}

/// Agent 运行时句柄 — spawn 后获得
/// 通过此句柄与 Agent 交互（发消息、收事件、停止）
pub struct AgentHandle {
    tx: tokio::sync::mpsc::Sender<AgentMessage>,
    event_rx: tokio::sync::mpsc::Receiver<AgentEvent>,
    join_handle: tokio::task::JoinHandle<()>,
}

impl AgentHandle {
    /// 发送用户输入
    pub async fn send_user_input(&self, text: &str) -> Result<()>;
    
    /// 发送中断
    pub async fn interrupt(&self) -> Result<()>;
    
    /// 接收下一个事件（流式输出、工具进度、完成等）
    pub async fn recv(&mut self) -> Option<AgentEvent>;
    
    /// 停止 Agent（graceful shutdown）
    pub async fn stop(self) -> Result<()>;
}

// ════════════════════════════════════════════════════════════════
// 消息类型 — 对应 Scala AgentCommand
// ════════════════════════════════════════════════════════════════

/// 发送给 Agent 的消息
pub enum AgentMessage {
    /// 用户输入（触发新一轮 LLM 调用）
    UserInput {
        text: String,
        blocks: Option<Vec<ContentBlock>>,
        chat_width: u32,
    },
    /// 即时输入（不触发新轮次，注入到下次 LLM 调用）
    ImmediateInput { text: String },
    /// 中断当前 LLM 调用
    Interrupt,
    /// LLM 流式调用完成（内部消息，LLM 轮次 → Agent 主循环）
    LlmComplete { result: LlmResult, turn_id: u64 },
    /// LLM 调用失败
    LlmFailed { error: LlmError, turn_id: u64 },
    /// 工具执行完成
    ToolsComplete {
        results: Vec<(ToolCall, ToolExecResult)>,
        turn_id: u64,
    },
    /// 用户回答了 AskUserQuestion
    UserAnswered { answers: Vec<String> },
    /// 用户批准/拒绝了权限请求
    PermissionAnswered { approved: bool },
    /// 触发上下文压缩
    TriggerCompaction { mode: CompactionMode },
    /// 压缩完成
    CompactionComplete { result: Result<Vec<Message>, String> },
    /// 停止信号
    Stop,
}

// ════════════════════════════════════════════════════════════════
// 事件类型 — 对应 Scala AgentStreamEvent
// ════════════════════════════════════════════════════════════════

/// Agent 发出的事件（通过 event channel 接收）
#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "type")]
pub enum AgentEvent {
    /// 流式文本增量
    TextDelta { delta: String, turn_id: u64 },
    /// 流式思考增量
    ThinkingDelta { delta: String, turn_id: u64 },
    /// 工具调用开始
    ToolCallStart { name: String, turn_id: u64 },
    /// 工具调用参数增量
    ToolArgDelta { tool_name: String, delta: String },
    /// 工具调用完成
    ToolCallComplete { tool_call: ToolCall, turn_id: u64 },
    /// 工具执行结果
    ToolResult { tool_use_id: String, result: String, is_error: bool },
    /// 轮次完成
    TurnComplete { turn_id: u64, usage: Option<TokenUsage> },
    /// Provider fallback 通知
    RetryStatus { message: String },
    /// 请求用户输入 (AskUserQuestion)
    AskUser { request_id: String, items: Vec<AskItem> },
    /// 请求权限确认
    PermissionRequest { description: String, changes: Vec<FileChange> },
    /// Agent 空闲（等待输入）
    Idle,
    /// 错误
    Error { message: String },
}

// ════════════════════════════════════════════════════════════════
// Agent 构建 — Builder 模式
// ════════════════════════════════════════════════════════════════

/// Agent 构建器
pub struct AgentBuilder {
    def: AgentDef,
    llm: Arc<dyn LlmProvider>,
    tools: Arc<ToolRegistry>,
    session_store: Option<Arc<SessionStore>>,
    config: AgentConfig,
}

impl AgentBuilder {
    pub fn new(def: AgentDef, llm: Arc<dyn LlmProvider>) -> Self;
    
    /// 注册工具集
    pub fn with_tools(mut self, registry: Arc<ToolRegistry>) -> Self;
    
    /// 设置 session 持久化
    pub fn with_session(mut self, store: Arc<SessionStore>, session_id: String) -> Self;
    
    /// 设置初始消息历史
    pub fn with_messages(mut self, messages: Vec<Message>) -> Self;
    
    /// 设置 Agent 深度（子 agent）
    pub fn with_depth(mut self, depth: usize) -> Self;
    
    /// 设置上下文窗口
    pub fn with_context_window(mut self, window: usize) -> Self;
    
    /// Spawn — 启动 Agent tokio task，返回句柄
    pub fn spawn(self) -> AgentHandle;
}

// ════════════════════════════════════════════════════════════════
// 使用示例
// ════════════════════════════════════════════════════════════════

/*
// 场景 1：作为库函数使用（嵌入式 Agent）
let llm = ProviderRegistry::new(config)
    .build_handle();

let agent = AgentBuilder::new(
    AgentDef {
        name: "my-agent".into(),
        system_prompt: "You are a helpful assistant.".into(),
        tools: vec!["*".into()],
        ..Default::default()
    },
    llm,
)
.with_tools(Arc::new(ToolRegistry::builtin()))
.spawn();

agent.send_user_input("hello").await?;

while let Some(event) = agent.recv().await {
    match event {
        AgentEvent::TextDelta { delta, .. } => print!("{delta}"),
        AgentEvent::TurnComplete { .. } => break,
        _ => {}
    }
}

// 场景 2：Gateway 集成（WS 事件桥接）
let agent = AgentBuilder::new(def, llm_handle)
    .with_tools(tools)
    .with_session(store, session_id)
    .spawn();

// 将 Agent 事件转发到 WebSocket
let ws_tx = ws_sender.clone();
tokio::spawn(async move {
    while let Some(event) = agent.event_rx.recv().await {
        let json = serde_json::to_value(&event).unwrap();
        ws_tx.send(WsMessage::Text(json.to_string())).await;
    }
});
*/
```

### 3.2 工具系统 Trait

```rust
// ════════════════════════════════════════════════════════════════
// nebflow-core/src/tools/mod.rs — 工具系统
// ════════════════════════════════════════════════════════════════

/// 工具定义（发送给 LLM 的 schema）
#[derive(Debug, Clone, serde::Serialize)]
pub struct ToolDefinition {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,  // JSON Schema
}

/// 工具调用上下文 — 对应 Scala ToolContext
pub struct ToolContext {
    pub project_root: PathBuf,
    pub llm: Option<Arc<dyn LlmProvider>>,
    pub session_store: Option<Arc<SessionStore>>,
    pub agent_handle: Option<AgentHandle>,
    pub context_window: usize,
    pub session_id: Option<String>,
    pub ws_sender: Option<WsEventSender>,
    pub read_tracker: Arc<RwLock<ReadTracker>>,
    pub file_history: Arc<RwLock<FileHistory>>,
    pub depth: usize,
    pub agent_def: Option<AgentDef>,
    pub file_lock_manager: Arc<FileLockManager>,
    pub messages: Vec<Message>,
    pub tool_call_id: String,
}

/// 工具 Trait — 所有工具实现此接口
/// 对应 Scala `trait Tool`
#[async_trait::async_trait]
pub trait Tool: Send + Sync {
    /// 工具名称（唯一标识）
    fn name(&self) -> &str;
    
    /// 工具描述（发送给 LLM）
    fn description(&self) -> &str;
    
    /// 输入 JSON Schema
    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value>;
    
    /// 执行工具
    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError>;
    
    /// 生成工具调用的摘要（用于 WS 进度显示）
    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String;
    
    /// 生成结果的摘要（用于 WS 显示）
    fn summarize_result(&self, input: &serde_json::Map<String, serde_json::Value>, result: &str) -> String;
    
    /// 结果大小上限（字符数）
    fn max_result_size(&self) -> usize { 50_000 }
    
    /// 从结果中提取图片（用于 vision 模型）
    fn extract_images(
        &self,
        _input: &serde_json::Map<String, serde_json::Value>,
        _result: &str,
    ) -> Option<Vec<ContentBlock>> { None }
}

/// 工具注册表
pub struct ToolRegistry {
    tools: HashMap<String, Arc<dyn Tool>>,
}

impl ToolRegistry {
    /// 注册所有内置工具
    pub fn builtin() -> Self {
        let mut tools = HashMap::new();
        // File operations
        tools.insert("Read", Arc::new(ReadTool) as Arc<dyn Tool>);
        tools.insert("Write", Arc::new(WriteTool));
        tools.insert("Edit", Arc::new(EditTool));
        // Search
        tools.insert("Glob", Arc::new(GlobTool));
        tools.insert("Grep", Arc::new(GrepTool));
        // Shell
        tools.insert("Bash", Arc::new(BashTool));
        // Web
        tools.insert("WebSearch", Arc::new(WebSearchTool));
        tools.insert("WebFetch", Arc::new(WebFetchTool));
        tools.insert("Curl", Arc::new(CurlTool));
        // ... 其余工具
        Self { tools }
    }
    
    pub fn get(&self, name: &str) -> Option<&Arc<dyn Tool>>;
    pub fn definitions(&self, allowed: &HashSet<String>) -> Vec<ToolDefinition>;
    pub fn register(&mut self, tool: Arc<dyn Tool>);
    pub fn unregister(&mut self, name: &str);
}
```

### 3.3 LLM Provider Trait

```rust
// ════════════════════════════════════════════════════════════════
// nebflow-core/src/llm/mod.rs — LLM 接口层
// ════════════════════════════════════════════════════════════════

/// LLM 请求参数 — 对应 Scala LlmRequest
pub struct LlmRequest {
    pub messages: Vec<Message>,
    pub session_id: String,
    pub agent_id: String,
    pub tools: Option<Vec<ToolDefinition>>,
    pub max_tokens: Option<usize>,
    pub thinking: Option<serde_json::Value>,
    pub system_stable: Option<String>,
    pub system_dynamic: Option<String>,
    pub agent_model: Option<AgentModelConfig>,
}

/// 流式输出 chunk — 对应 Scala StreamChunk
#[derive(Debug, Clone)]
pub enum StreamChunk {
    TextDelta(String),
    ThinkingDelta(String),
    ThinkingSignature(String),
    ToolCallStart { name: String },
    ToolArgDelta { tool_name: String, delta: String },
    ToolCallComplete(ToolCall),
    Done {
        stop_reason: Option<String>,
        usage: Option<TokenUsage>,
        context_window: Option<usize>,
    },
}

/// LLM Provider Trait — 对应 Scala `trait LlmHandle[F[_]]`
/// 实现方：AnthropicAdapter, OpenAiAdapter
#[async_trait::async_trait]
pub trait LlmProvider: Send + Sync {
    /// 非流式请求
    async fn send(&self, req: &LlmRequest) -> Result<LlmResponse, LlmError>;
    
    /// 流式请求 — 返回异步迭代器
    fn send_stream(
        &self,
        req: &LlmRequest,
        on_attempt: Option<Box<dyn Fn(FallbackAttempt) + Send + Sync>>,
    ) -> Pin<Box<dyn Stream<Item = Result<StreamChunk, LlmError>> + Send>>;
}

/// Provider 注册表 — 管理 fallback 链 + 健康检查
pub struct ProviderRegistry {
    candidates: Vec<ModelCandidate>,
    health_monitor: HealthMonitor,
    client: reqwest::Client,  // 共享连接池
}

/// Provider Adapter Trait — 对应 Scala `trait ProviderAdapter[F[_]]`
/// 直接与 LLM API 通信的适配器
#[async_trait::async_trait]
pub trait ProviderAdapter: Send + Sync {
    async fn send_message(&self, params: &SendMessageParams) -> Result<AdapterResponse, LlmError>;
    
    fn send_message_stream(
        &self,
        params: &SendMessageParams,
    ) -> Pin<Box<dyn Stream<Item = Result<StreamChunk, LlmError>> + Send>>;
}

/// Anthropic Claude Adapter
pub struct AnthropicAdapter {
    client: reqwest::Client,
    base_url: String,
    api_key: String,
}

/// OpenAI-compatible Adapter (支持 OpenAI, DeepSeek, GLM, 等)
pub struct OpenAiAdapter {
    client: reqwest::Client,
    base_url: String,
    api_key: String,
}
```

### 3.4 Agent 运行时内部结构

```rust
// ════════════════════════════════════════════════════════════════
// nebflow-agent/src/runtime.rs — Agent 内部运行时
// ════════════════════════════════════════════════════════════════

/// Agent 运行时状态 — 对应 Scala AgentState
pub(crate) struct AgentState {
    pub messages: Vec<Message>,           // 使用 Vec 而非 List
    pub status: AgentStatus,
    pub depth: usize,
    pub session_id: Option<String>,
    pub session_name: Option<String>,
    pub pending_compaction: Option<CompactionTask>,
    pub latest_usage: Option<TokenUsage>,
    pub pending_ask_user: Option<AskRequest>,
    pub pending_permission: Option<PermissionRequest>,
    pub ws_sender: WsEventSender,
    pub read_tracker: Arc<RwLock<ReadTracker>>,
    pub file_history: Arc<RwLock<FileHistory>>,
    pub context_window: usize,
    pub project_root: Option<PathBuf>,
    pub language: Option<String>,
    pub current_turn_id: u64,
    pub agent_sessions: Vec<AgentSessionInfo>,
    pub plan_mode: Option<PlanModeState>,
}

/// Agent 运行器 — spawn 后在 tokio task 中运行的消息循环
/// 对应 Scala AgentActor 的 idle/processing 行为
pub(crate) struct AgentRunner {
    def: AgentDef,
    state: AgentState,
    resources: SharedResources,
    rx: tokio::sync::mpsc::Receiver<AgentMessage>,
    event_tx: tokio::sync::mpsc::Sender<AgentEvent>,
    parent: Option<AgentHandle>,
}

impl AgentRunner {
    /// 消息循环主入口
    pub async fn run(mut self) {
        while let Some(msg) = self.rx.recv().await {
            match msg {
                AgentMessage::UserInput { text, blocks, chat_width } => {
                    self.handle_user_input(text, blocks, chat_width).await;
                }
                AgentMessage::LlmComplete { result, turn_id } => {
                    self.handle_llm_complete(result, turn_id).await;
                }
                AgentMessage::ToolsComplete { results, turn_id } => {
                    self.handle_tools_complete(results, turn_id).await;
                }
                AgentMessage::Stop => break,
                // ... 其他消息
            }
            // 检查是否需要自动压缩
            self.maybe_auto_compact().await;
        }
    }
    
    /// 处理用户输入 → 构建 system prompt → 调用 LLM
    async fn handle_user_input(&mut self, text: String, blocks: Option<Vec<ContentBlock>>, chat_width: u32);
    
    /// 处理 LLM 完成 → 解析工具调用 → 执行工具
    async fn handle_llm_complete(&mut self, result: LlmResult, turn_id: u64);
    
    /// 处理工具完成 → 持久化 → 决定下一轮
    async fn handle_tools_complete(&mut self, results: Vec<(ToolCall, ToolExecResult)>, turn_id: u64);
}
```

---

## 4. 分阶段迁移计划

### 阶段总览

| 阶段 | 名称 | 目标 | 预计工作量 | 依赖 |
|------|------|------|-----------|------|
| Phase 0 | 项目初始化 + 核心抽象 | Cargo workspace + 类型系统 + Agent trait | 2-3 天 | 无 |
| Phase 1 | 共享类型和协议层 | 所有 ADT / enum / struct 定义 | 3-4 天 | Phase 0 |
| Phase 2 | LLM 接口层 | Provider adapter + SSE 解析 + fallback | 5-7 天 | Phase 1 |
| Phase 3 | Agent 执行引擎 | AgentRunner 消息循环 + system prompt 构建 | 7-10 天 | Phase 2 |
| Phase 4 | 工具系统 | 20+ 内置工具 + 外部工具 + MCP | 10-15 天 | Phase 3 |
| Phase 5 | Gateway 层 | HTTP 路由 + WebSocket + 前端静态文件 | 5-7 天 | Phase 3, 4 |
| Phase 6 | Flow/Team 实体系统 | DAG 执行 + Team 管理 + Mail/Delegate | 5-7 天 | Phase 3, 4 |
| Phase 7 | CLI + 配置 + 集成测试 | CLI 命令 + 配置加载 + 端到端测试 | 3-5 天 | Phase 5, 6 |
| Phase 8 | 性能验证 + 前端适配 | 基准测试 + 前端 WS 适配 + NebLink | 3-5 天 | Phase 7 |

**总工作量：43-63 人天（~2-3 人月全职）**

---

### Phase 0: 项目初始化 + Agent 库核心抽象（2-3 天）

#### 目标
搭建 Rust Cargo workspace，定义核心 trait 和类型签名，建立可编译的空壳项目。

#### 涉及模块
- 新建 Cargo workspace 结构
- 定义 `Agent`、`Tool`、`LlmProvider` 三大核心 trait
- 定义消息/事件枚举签名（不含完整实现）

#### 涉及文件（新建）
```
nebflow-rs/
├── Cargo.toml                    # workspace 根
├── nebflow-core/
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs
│       └── types.rs              # 共享类型（Message, ContentBlock, ...）
├── nebflow-agent/
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs                # AgentBuilder, AgentHandle
│       └── runtime.rs            # AgentRunner 签名
├── nebflow-gateway/
│   ├── Cargo.toml
│   └── src/
│       └── lib.rs
└── nebflow-cli/
    ├── Cargo.toml
    └── src/
        └── main.rs
```

#### 验收条件
- [ ] `cargo build --workspace` 编译通过（0 error, 0 warning）
- [ ] `cargo test --workspace` 通过（至少 1 个 smoke test）
- [ ] 核心 trait 定义存在：`Agent`, `Tool`, `LlmProvider`
- [ ] `AgentBuilder::new(def, llm).spawn()` 返回 `AgentHandle`（空实现）
- [ ] `AgentHandle::send_user_input("hello")` 和 `recv()` 可编译（不需要真正执行）
- [ ] CI 流水线配置（GitHub Actions: fmt + clippy + test）

#### 预计工作量：2-3 天

---

### Phase 1: 共享类型和协议层（3-4 天）

#### 目标
迁移 `shared/protocol.scala`（371 行）和 `shared/Defaults.scala`（83 行）中的所有共享类型。

#### 涉及模块（Scala → Rust）
| Scala 文件 | 行数 | Rust 目标 | 说明 |
|-----------|------|----------|------|
| `shared/protocol.scala` | 371 | `nebflow-core/src/types.rs` | ContentBlock, Message, ToolCall, StreamChunk, LlmRequest/Response |
| `shared/Defaults.scala` | 83 | `nebflow-core/src/config.rs` | 所有常量值 |
| `shared/AgentModelConfig.scala` | - | `nebflow-core/src/types.rs` | 模型配置 |
| `shared/SessionMeta.scala` | - | `nebflow-core/src/types.rs` | Session 元数据 |
| `agent/protocol.scala` (部分) | 771 | `nebflow-core/src/types.rs` | AgentCommand, AgentEvent |
| `llm/config.scala` (部分) | 212 | `nebflow-core/src/config.rs` | ProviderConfig, ModelConfig |
| `core/entity/EntityTypes.scala` | 256 | `nebflow-core/src/entity.rs` | AgentEntry, TeamDef, FlowDagDef |

#### 关键类型映射
```rust
// ContentBlock (Scala sealed trait → Rust enum)
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type")]
pub enum ContentBlock {
    #[serde(rename = "text")]
    Text { text: String },
    #[serde(rename = "image")]
    Image { data: String, media_type: String },
    #[serde(rename = "tool_use")]
    ToolUse { id: String, name: String, input: serde_json::Map<String, serde_json::Value> },
    #[serde(rename = "tool_result")]
    ToolResult { tool_use_id: String, content: String, is_error: Option<bool> },
    #[serde(rename = "thinking")]
    Thinking { thinking: String, signature: Option<String> },
}

// MessageRole (Scala enum → Rust enum)
#[derive(Debug, Clone, Copy, serde::Serialize, serde::Deserialize)]
pub enum MessageRole {
    System, User, Assistant,
}

// Message (Scala case class → Rust struct)
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct Message {
    pub role: MessageRole,
    pub content: MessageContent,  // Either<String, Vec<ContentBlock>>
    pub timestamp: u64,
}

// Either 的 Rust 等价
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(untagged)]
pub enum MessageContent {
    Text(String),
    Blocks(Vec<ContentBlock>),
}
```

#### 验收条件
- [ ] `cargo build` 编译通过
- [ ] `cargo test` — 所有类型序列化/反序列化测试通过
- [ ] 每个类型至少 1 个 serde round-trip 测试（`serde_json::from_str(serde_json::to_string(x))` 等于 x）
- [ ] ContentBlock 的 JSON 序列化格式与 Scala circe 输出完全一致（字节级比对）
- [ ] Message 的 JSON 序列化格式与 Scala 一致
- [ ] Defaults 常量值与 Scala 完全一致（`ContextWindow=128000`, `MaxTokens=16384`, 等）
- [ ] `cargo clippy` 无 warning

#### 预计工作量：3-4 天

---

### Phase 2: LLM 接口层（5-7 天）

#### 目标
迁移 `llm/` 模块（2,350 行），实现 Provider Adapter、SSE 流式解析、fallback 链和 Health Monitor。

#### 涉及模块（Scala → Rust）
| Scala 文件 | 行数 | Rust 目标 | 说明 |
|-----------|------|----------|------|
| `llm/interface.scala` | 523 | `nebflow-core/src/llm/handle.rs` | LlmHandle 创建 + fallback 逻辑 |
| `llm/providers/AnthropicAdapter.scala` | 431 | `nebflow-core/src/llm/anthropic.rs` | Anthropic API + SSE 解析 |
| `llm/providers/OpenAiAdapter.scala` | 454 | `nebflow-core/src/llm/openai.rs` | OpenAI-compatible API + SSE |
| `llm/adapter.scala` | 30 | `nebflow-core/src/llm/adapter.rs` | ProviderAdapter trait |
| `llm/registry.scala` | - | `nebflow-core/src/llm/registry.rs` | Provider 注册 + 候选链构建 |
| `llm/fallback.scala` | - | `nebflow-core/src/llm/fallback.rs` | Fallback 重试 + 错误分类 |
| `llm/HealthMonitor.scala` | - | `nebflow-core/src/llm/health.rs` | 健康检查 + probe |
| `llm/config.scala` | 212 | `nebflow-core/src/config.rs` | 配置加载 |

#### 关键实现

**SSE 流式解析（替代 fs2 Stream）**：
```rust
// 使用 reqwest 的 stream + eventsource-stream
use reqwest::Response;
use tokio_stream::StreamExt;
use eventsource_stream::Eventsource;

async fn stream_anthropic(
    client: &reqwest::Client,
    url: &str,
    body: &serde_json::Value,
) -> impl Stream<Item = Result<StreamChunk, LlmError>> {
    let resp = client.post(url).json(body).send().await?;
    resp.bytes_stream()
        .eventsource()  // SSE 解析
        .filter_map(|event| async move {
            match event {
                Ok(ev) => parse_anthropic_event(&ev).ok(),
                Err(e) => Some(Err(LlmError::Stream(e.to_string()))),
            }
        })
}
```

**Fallback 链（替代 fs2 Stream handleErrorWith）**：
```rust
// Rust 没有 fs2.Stream.handleErrorWith 的直接等价
// 使用 async fn + 循环实现 provider 切换
async fn send_stream_with_fallback(
    candidates: Vec<ModelCandidate>,
    req: &LlmRequest,
    on_attempt: &dyn Fn(FallbackAttempt),
) -> Result<Pin<Box<dyn Stream<Item = Result<StreamChunk, LlmError>> + Send>>, LlmError> {
    let mut remaining = candidates;
    loop {
        match try_candidate(&remaining[0], req).await {
            Ok(stream) => return Ok(stream),
            Err(e) => {
                let classification = classify_error(&e);
                match classification.permanence {
                    ErrorPermanence::Fatal => return Err(e),
                    ErrorPermanence::Permanent => {
                        remaining.remove(0);
                        if remaining.is_empty() { return Err(e); }
                    }
                    ErrorPermanence::Transient => {
                        // retry with backoff
                        tokio::time::sleep(backoff).await;
                    }
                }
            }
        }
    }
}
```

#### 验收条件
- [ ] `cargo build` 编译通过
- [ ] `cargo test` — 至少 20 个测试通过
- [ ] Anthropic Adapter：能成功调用真实 Claude API（或 mock server），接收流式 SSE 响应
- [ ] OpenAI Adapter：能成功调用真实 OpenAI-compatible API（或 mock server）
- [ ] SSE 解析：正确解析 `event:` / `data:` 行，提取 `StreamChunk::TextDelta` / `ToolCallComplete` / `Done`
- [ ] Fallback 链：Provider A 失败 → 自动切换到 Provider B，`on_attempt` 回调被调用
- [ ] Health Monitor：Provider 标记为 Down 后，请求不会路由到它
- [ ] 流式超时：首 token 超时 90s → 自动 fallback；中途无活动 60s → fallback
- [ ] JSON 序列化：Anthropic 请求 body 格式与 Scala 版完全一致（可通过抓包比对）
- [ ] Token 使用量：正确解析 `input_tokens`, `output_tokens`, `cache_read_tokens`
- [ ] `cargo clippy` 无 warning

#### 预计工作量：5-7 天

---

### Phase 3: Agent 执行引擎（7-10 天）

#### 目标
迁移 `agent/` 模块（5,536 行）和 `actor/` 模块（520 行），实现完整的 Agent 消息循环、system prompt 构建、上下文压缩。

#### 涉及模块（Scala → Rust）
| Scala 文件 | 行数 | Rust 目标 | 说明 |
|-----------|------|----------|------|
| `actor/ActorSystem.scala` | 218 | `nebflow-agent/src/runtime.rs` | Agent 消息循环（mpsc + task） |
| `actor/Behavior.scala` | - | 内联到 AgentRunner | 行为模式不再需要独立抽象 |
| `agent/AgentActor.scala` | 2223 | `nebflow-agent/src/runner.rs` | Agent 状态机 + 消息处理 |
| `agent/AgentCore.scala` | 889 | `nebflow-agent/src/core.rs` | pipeLlmCall + pipeToolExecutions |
| `agent/AgentDef.scala` | 23 | `nebflow-core/src/types.rs` | AgentDef struct |
| `agent/AgentLibrary.scala` | 428 | `nebflow-agent/src/library.rs` | Agent 库加载 |
| `agent/ContextRefresher.scala` | 399 | `nebflow-agent/src/context.rs` | System prompt 构建 |
| `agent/PromptSections.scala` | 389 | `nebflow-agent/src/prompt.rs` | 条件提示词注入 |
| `agent/protocol.scala` | 771 | `nebflow-core/src/types.rs` | AgentCommand/Event (Phase 1 已迁移类型) |
| `agent/SharedResources.scala` | 58 | `nebflow-agent/src/resources.rs` | 共享资源容器 |
| `agent/InjectionSource.scala` | - | `nebflow-agent/src/context.rs` | 注入源 |
| `agent/LanguageDetector.scala` | - | `nebflow-agent/src/language.rs` | 语言检测 |
| `agent/MaintenanceService.scala` | - | `nebflow-agent/src/maintenance.rs` | 梦境模式 |
| `core/compact/*` (全部) | ~1000 | `nebflow-core/src/compact/` | 上下文压缩 |
| `core/reminders.scala` | - | `nebflow-core/src/reminders.rs` | 系统提醒 |
| `core/UsageTracker.scala` | - | `nebflow-core/src/usage.rs` | Token 使用追踪 |

#### 核心运行循环设计

```rust
// AgentRunner 的核心循环（简化版）
impl AgentRunner {
    pub async fn run(mut self) {
        while let Some(msg) = self.rx.recv().await {
            let result = match msg {
                AgentMessage::UserInput { text, blocks, chat_width } => {
                    self.process_user_input(text, blocks, chat_width).await
                }
                AgentMessage::LlmComplete { result, turn_id } => {
                    self.process_llm_complete(result, turn_id).await
                }
                AgentMessage::ToolsComplete { results, turn_id } => {
                    self.process_tools_complete(results, turn_id).await
                }
                AgentMessage::Stop => { self.cleanup().await; return; }
                _ => Ok(())
            };
            
            if let Err(e) = result {
                self.emit_event(AgentEvent::Error { message: e.to_string() }).await;
            }
        }
    }
    
    async fn process_user_input(&mut self, text: String, ..) -> Result<()> {
        // 1. 追加 User message
        self.state.messages.push(Message::user(text));
        
        // 2. 构建 system prompt (ContextRefresher)
        let system_prompt = self.build_system_prompt().await?;
        
        // 3. 构建 tool list
        let tools = self.build_tool_list();
        
        // 4. Fast micro-compact (如果需要)
        if let Some(compacted) = fast_micro_compact(&self.state.messages) {
            self.state.messages = compacted;
        }
        
        // 5. 发起 LLM 流式调用（后台 task）
        let turn_id = self.next_turn_id();
        let llm_stream = self.resources.llm.send_stream(&LlmRequest {
            messages: self.state.messages.clone(),
            tools: Some(tools),
            system_stable: Some(system_prompt),
            ..
        });
        
        // 6. 在后台消费 stream，完成后发 LlmComplete 消息回主循环
        self.spawn_llm_consumer(llm_stream, turn_id);
        
        Ok(())
    }
    
    async fn process_llm_complete(&mut self, result: LlmResult, turn_id: u64) -> Result<()> {
        // 1. 追加 Assistant message
        self.state.messages.push(Message::assistant(result.content));
        
        // 2. 如果有工具调用，执行工具
        if !result.tool_calls.is_empty() {
            self.execute_tools(result.tool_calls, turn_id).await?;
        } else {
            // 无工具调用 = 轮次完成
            self.emit_event(AgentEvent::TurnComplete { turn_id, usage: result.usage }).await;
            self.state.status = AgentStatus::Idle;
        }
        
        Ok(())
    }
    
    async fn process_tools_complete(&mut self, results: Vec<(ToolCall, ToolExecResult)>, turn_id: u64) -> Result<()> {
        // 1. 追加 ToolResult messages
        for (call, result) in &results {
            self.state.messages.push(Message::tool_result(call.id, &result.content));
        }
        
        // 2. 持久化 session
        if let Some(sid) = &self.state.session_id {
            self.resources.session_store.save_messages(sid, &self.state.messages).await?;
        }
        
        // 3. 自动触发下一轮 LLM 调用
        self.process_user_input(String::new(), None, 0).await?;
        
        Ok(())
    }
}
```

#### 验收条件
- [ ] `cargo build` 编译通过
- [ ] `cargo test` — 至少 30 个测试通过
- [ ] **基本对话**：Agent 收到 "Hello" → 调用 LLM → 返回文本回复 → 发出 `TextDelta` + `TurnComplete` 事件
- [ ] **工具调用循环**：Agent 收到 "Read file X" → LLM 返回 ToolUse → 执行 ReadTool → 将结果追加到消息 → 再次调用 LLM → LLM 返回文本 → `TurnComplete`
- [ ] **多轮工具**：连续 3+ 轮工具调用（Read → Edit → Bash）正常完成
- [ ] **System prompt 构建**：包含 agent system.md、条件块（tools/devices/sessions）、memory block、skill catalog
- [ ] **条件提示词注入**：根据 `depth`、`agentName`、`hasDevices` 等条件正确启用/禁用提示词块
- [ ] **上下文压缩**：消息超过阈值时触发 FastMicroCompact，压缩后继续工作
- [ ] **Session 持久化**：工具调用完成后，消息列表写入 JSON 文件，重启后可恢复
- [ ] **中断**：收到 `Interrupt` 消息时取消正在进行的 LLM 调用
- [ ] **子 Agent**：`depth > 0` 的 Agent 正确运行，工具列表受限
- [ ] **错误恢复**：LLM 返回空回复 → 重试（最多 5 次）；所有 Provider 耗尽 → 发出 Error 事件
- [ ] **WS 事件**：所有 Agent 事件可通过 `ws_sender` 转发为 JSON
- [ ] `cargo clippy` 无 warning

#### 预计工作量：7-10 天

---

### Phase 4: 工具系统（10-15 天）

#### 目标
迁移 `core/tools/` 模块（8,342 行），实现全部 20+ 内置工具 + 外部工具加载 + MCP 协议。

#### 涉及模块（Scala → Rust）

**内置工具（按优先级排序）**：

| 工具 | Scala 行数 | 复杂度 | Rust 文件 | 说明 |
|------|-----------|--------|----------|------|
| ReadTool | 223 | 低 | `tools/read.rs` | 文件读取 + 图片检测 |
| WriteTool | 146 | 低 | `tools/write.rs` | 文件写入 |
| EditTool | 260 | 中 | `tools/edit.rs` | 精确匹配编辑 + diff |
| GlobTool | 141 | 低 | `tools/glob.rs` | glob 模式匹配 |
| GrepTool | 218 | 中 | `tools/grep.rs` | ripgrep 调用 |
| BashTool | 741 | 高 | `tools/bash.rs` | 子进程 + 后台任务 + 超时 |
| WebSearchTool | 446 | 中 | `tools/web_search.rs` | 搜索 API |
| WebFetchTool | 299 | 中 | `tools/web_fetch.rs` | URL 抓取 + Playwright |
| CurlTool | 165 | 低 | `tools/curl.rs` | HTTP 请求 |
| PopTool | 182 | 低 | `tools/pop.rs` | Canvas 文件展示 |
| CardTool | 471 | 中 | `tools/card.rs` | HTML 卡片 |
| AskUserQuestionTool | 151 | 低 | `tools/ask_user.rs` | 用户交互 |
| DelegateTool | 492 | 高 | `tools/delegate.rs` | 子 Agent 生成 |
| MailTool | 484 | 高 | `tools/mail.rs` | 跨 Agent 通信 |
| FlowReportTool | - | 低 | `tools/flow_report.rs` | Flow 结果上报 |
| LoadTool | 190 | 中 | `tools/load.rs` | 实体加载 |
| TransferFileTool | 308 | 中 | `tools/transfer.rs` | 跨设备传输 |
| TaskCreateTool | 104 | 低 | `tools/task_create.rs` | 任务创建 |
| TaskUpdateTool | 185 | 低 | `tools/task_update.rs` | 任务更新 |
| ScheduleTool | 122 | 低 | `tools/schedule.rs` | 定时任务 |
| SaveWorkspaceItemTool | 86 | 低 | `tools/save_workspace.rs` | 工作区存储 |
| RemoveUnnecessaryTool | 81 | 低 | `tools/remove_unnecessary.rs` | 上下文清理 |

**辅助模块**：
| 模块 | Scala 文件 | 说明 |
|------|-----------|------|
| shell.scala (824 行) | `tools/shell.rs` | Bash 子进程管理 |
| DiffUtil.scala | `tools/diff.rs` | similar 库封装 |
| FileHistory.scala | `tools/file_history.rs` | 文件编辑历史 |
| ReadTracker.scala | `tools/read_tracker.rs` | 文件读取追踪 |
| ToolResultGuard.scala | `tools/guard.rs` | 结果大小截断 |
| ToolLoader.scala (244 行) | `tools/loader.rs` | 外部工具加载 |
| ScriptTool.scala | `tools/script.rs` | 脚本工具 |
| ExternalToolConfig.scala | `tools/external.rs` | 外部工具配置 |
| RemoteExecutor.scala (460 行) | `tools/remote.rs` | 跨设备执行 |
| FileLockManager.scala | `tools/file_lock.rs` | 文件锁 |
| StringMatcher.scala | `tools/string_matcher.rs` | 字符串匹配 |
| BgTaskRegistry.scala | `tools/bg_task.rs` | 后台任务注册 |

**MCP 协议**：
| Scala 文件 | 行数 | Rust 文件 | 说明 |
|-----------|------|----------|------|
| `mcp/McpClient.scala` | 122 | `mcp/client.rs` | MCP 客户端 |
| `mcp/McpManager.scala` | - | `mcp/manager.rs` | MCP 服务管理 |
| `mcp/JsonRpc.scala` | - | `mcp/jsonrpc.rs` | JSON-RPC 协议 |
| `mcp/transports.scala` | - | `mcp/transport.rs` | stdio/SSE 传输 |
| `mcp/AgentMcpLoader.scala` | - | `mcp/loader.rs` | Agent MCP 配置加载 |

#### 验收条件

**文件操作工具**：
- [ ] Read：读取文本文件 → 返回内容；读取图片文件 → 返回 ContentBlock::Image
- [ ] Write：创建/覆盖文件；自动创建父目录
- [ ] Edit：精确匹配替换；不匹配时报错；支持多行编辑
- [ ] Glob：glob 模式匹配文件路径
- [ ] Grep：调用 ripgrep，返回匹配行 + 行号

**Shell 工具**：
- [ ] Bash：执行命令 → 返回 stdout/stderr/exitCode
- [ ] Bash 后台任务：`run_in_background: true` → 立即返回 job ID
- [ ] Bash 超时：超过指定时间 → 杀死进程
- [ ] Bash 工作目录：正确设置 cwd

**Web 工具**：
- [ ] WebSearch：调用搜索 API → 返回结果
- [ ] WebFetch：抓取 URL → 返回 markdown/text
- [ ] Curl：发送 HTTP 请求 → 返回响应

**Agent 工具**：
- [ ] Delegate：生成子 Agent → 子 Agent 执行任务 → 结果通过 ExternalEvent 返回
- [ ] Mail：发送消息到目标 Agent/Team → 异步投递
- [ ] Mail fork=true：fork 目标 Agent 上下文 → 返回即时响应
- [ ] FlowReport：上报 Flow 节点结果

**交互工具**：
- [ ] AskUserQuestion：发出 AskUser 事件 → 等待 UserAnswered
- [ ] Pop：将文件路径注册到 Canvas → 发出 WS 事件
- [ ] Card：将 HTML 内容注册到 Canvas → 发出 WS 事件

**外部工具**：
- [ ] 外部 JSON+脚本工具：从 `~/.nebflow/tools/` 加载 JSON 定义 + 执行脚本
- [ ] 外部工具注册到 ToolRegistry，可在 LLM tool list 中使用

**MCP**：
- [ ] stdio transport：启动 MCP server 子进程 → 通过 stdin/stdout 通信
- [ ] HTTP transport：连接 MCP HTTP endpoint
- [ ] tools/list：获取 MCP server 提供的工具列表
- [ ] tools/call：调用 MCP 工具 → 返回结果
- [ ] MCP 工具注册为 `mcp__<server>__<tool>` 格式

**通用**：
- [ ] ToolResultGuard：结果超过 50,000 字符 → 截断 + 提示文件路径
- [ ] FileHistory：每次 Edit/Write 记录编辑前内容，支持回滚
- [ ] ReadTracker：记录已读取的文件，Edit 时验证已读取
- [ ] `cargo test` — 每个工具至少 1 个单元测试
- [ ] `cargo clippy` 无 warning

#### 预计工作量：10-15 天

---

### Phase 5: Gateway 层（5-7 天）

#### 目标
迁移 `gateway/` 模块（7,791 行），实现 HTTP 路由、WebSocket 服务、Session 管理。

#### 涉及模块（Scala → Rust）
| Scala 文件 | 行数 | Rust 目标 | 说明 |
|-----------|------|----------|------|
| `gateway/GatewayMain.scala` | 572 | `nebflow-gateway/src/main.rs` | 服务启动 + 资源初始化 |
| `gateway/WebSocketRoutes.scala` | 3554 | `nebflow-gateway/src/ws.rs` | WS 连接 + 消息路由 |
| `gateway/ChatRoutes.scala` | - | `nebflow-gateway/src/routes/chat.rs` | /api/chat REST |
| `gateway/RestApiRoutes.scala` | 1539 | `nebflow-gateway/src/routes/api.rs` | 全部 REST API |
| `gateway/SessionStore.scala` | 1379 | `nebflow-gateway/src/session.rs` | Session 持久化 |
| `gateway/WsHub.scala` | - | `nebflow-gateway/src/ws_hub.rs` | WS 广播 |
| `gateway/gatewayConfig.scala` | - | `nebflow-gateway/src/config.rs` | 端口 + 认证配置 |
| `gateway/auth.scala` | - | `nebflow-gateway/src/auth.rs` | Token 认证 |
| `gateway/ratelimit.scala` | - | `nebflow-gateway/src/ratelimit.rs` | 速率限制 |
| `gateway/SessionRecorder.scala` | - | `nebflow-gateway/src/recorder.rs` | 会话录制 |
| `gateway/SttService.scala` | - | `nebflow-gateway/src/stt.rs` | 语音转文字 |
| `gateway/TtsService.scala` | - | `nebflow-gateway/src/tts.rs` | 文字转语音 |

#### 路由映射

| Scala (http4s) | Rust (axum) | 方法 | 说明 |
|---------------|-------------|------|------|
| `/ws` | `/ws` | WS | WebSocket 连接 |
| `/api/chat` | `/api/chat` | POST | 发送聊天消息 |
| `/api/sessions` | `/api/sessions` | GET/POST | Session 管理 |
| `/api/sessions/:id` | `/api/sessions/:id` | GET/DELETE | 单个 Session |
| `/api/config` | `/api/config` | GET/PUT | 配置管理 |
| `/api/agents` | `/api/agents` | GET | Agent 列表 |
| `/api/skills` | `/api/skills` | GET | Skill 列表 |
| `/api/models` | `/api/models` | GET | 模型列表 |
| `/api/health` | `/api/health` | GET | 健康检查 |
| `/api/stt` | `/api/stt` | POST | 语音输入 |
| `/api/tts` | `/api/tts` | POST | 语音输出 |
| `/` (static) | `/` | GET | 前端静态文件 |

#### 验收条件
- [ ] `cargo build` 编译通过
- [ ] 服务启动：`./nebflow` → 监听端口 → 浏览器可访问 `http://localhost:8080`
- [ ] 前端静态文件：index.html + CSS + JS + vendor 库全部正确服务
- [ ] WebSocket 连接：浏览器建立 WS 连接 → 收到欢迎消息
- [ ] 聊天消息：通过 WS 发送 `{"type":"userInput","text":"Hello"}` → Agent 开始处理 → 流式返回
- [ ] 流式输出：前端正确显示 TextDelta 增量、工具调用进度、Thinking 增量
- [ ] Session 管理：创建新 Session → 切换 Session → 删除 Session
- [ ] 配置管理：通过 REST API 读取/更新 nebflow.json 配置
- [ ] Token 认证：无 token → 401；错误 token → 401；正确 token → 200
- [ ] 速率限制：超过限制 → 429
- [ ] 单实例锁：重复启动 → 第二个进程检测到已运行实例并退出
- [ ] `cargo clippy` 无 warning

#### 预计工作量：5-7 天

---

### Phase 6: Flow/Team 实体系统（5-7 天）

#### 目标
迁移 `core/entity/` 和 `core/flow/` 模块，实现 Agent/Team/Flow 三层架构和 DAG 执行器。

#### 涉及模块（Scala → Rust）
| Scala 文件 | 行数 | Rust 目标 | 说明 |
|-----------|------|----------|------|
| `core/entity/EntityTypes.scala` | 256 | Phase 1 已迁移 | 类型定义 |
| `core/entity/EntityLoader.scala` | 419 | `nebflow-core/src/entity/loader.rs` | 从磁盘加载 agent.json/team.json/flow.json |
| `core/entity/FlowDagExecutor.scala` | 438 | `nebflow-core/src/flow/executor.rs` | DAG 节点执行 + 路由 |
| `core/entity/TeamCatalog.scala` | - | `nebflow-core/src/entity/team_catalog.rs` | Team 目录构建 |
| `core/flow/FlowTreeActor.scala` | 672 | `nebflow-core/src/flow/tree.rs` | Flow 树管理 Actor |
| `core/flow/FlowTreeRegistry.scala` | - | `nebflow-core/src/flow/registry.rs` | 运行中 Flow 注册 |
| `core/flow/FlowTreeTypes.scala` | - | Phase 1 已迁移 | 类型定义 |
| `core/flow/FlowAgentActivator.scala` | - | `nebflow-core/src/flow/activator.rs` | Team/Flow Agent 激活 |
| `core/flow/FlowDagRunner.scala` | 48 | `nebflow-core/src/flow/runner.rs` | DAG 运行入口 |
| `core/flow/FlowMailStore.scala` | - | `nebflow-core/src/flow/mailbox.rs` | Team 邮箱 |
| `core/flow/FlowMembership.scala` | - | `nebflow-core/src/flow/membership.rs` | 成员管理 |
| `core/flow/MountedFlowStore.scala` | - | `nebflow-core/src/flow/mounted.rs` | 挂载 Flow 存储 |
| `core/flow/TurnStateStore.scala` | - | `nebflow-core/src/flow/turn_state.rs` | 轮次状态 |
| `core/flow/RunningFlowRegistry.scala` | - | `nebflow-core/src/flow/running.rs` | 运行中 Flow 注册 |
| `core/flow/EphemeralAgentRunner.scala` | - | `nebflow-core/src/flow/ephemeral.rs` | 临时 Agent 运行 |

#### 验收条件
- [ ] **Team 管理**：从 `~/.nebflow/teams/` 加载 team.json → 创建 Team Manager Agent + Member Agents
- [ ] **Team 通信**：Manager 通过 Mail 工具向 Member 发送任务 → Member 处理 → 返回结果
- [ ] **Flow DAG 执行**：从 `~/.nebflow/flows/` 加载 flow.json → 从 entry 节点开始 → 按 `onComplete` 路由
- [ ] **Flow Switch 路由**：`onComplete: { switch: "$verdict", cases: { "pass": "next", "fail": "fix" } }` 正确求值
- [ ] **Flow 循环保护**：maxLoop 到达时终止执行
- [ ] **Flow 错误处理**：节点失败 → 按 `onError` 策略（resume/restart/stop）处理
- [ ] **Delegate + Flow**：`Delegate(flow="code-review")` → 触发 Flow DAG 执行
- [ ] **Team Member 生命周期**：Member Agent 持久存活（只要 Team session 存在）
- [ ] **Flow 节点 Agent 生命周期**：DAG 节点 Agent 临时存活（执行完即停）
- [ ] `cargo test` — DAG 执行器至少 5 个测试
- [ ] `cargo clippy` 无 warning

#### 预计工作量：5-7 天

---

### Phase 7: CLI + 配置 + 集成测试（3-5 天）

#### 目标
迁移 `cli/` 模块（2,967 行），实现命令行入口、配置加载和端到端集成测试。

#### 涉及模块
| Scala 文件 | 行数 | Rust 目标 | 说明 |
|-----------|------|----------|------|
| `Main.scala` | - | `nebflow-cli/src/main.rs` | 入口 |
| `cli/CliRouter.scala` | - | `nebflow-cli/src/router.rs` | 命令路由 |
| `cli/AgentCommand.scala` | - | `nebflow-cli/src/commands/agent.rs` | nebflow agent |
| `cli/ChatCommands.scala` | - | `nebflow-cli/src/commands/chat.rs` | 聊天命令 |
| `cli/ConfigCommand.scala` | - | `nebflow-cli/src/commands/config.rs` | 配置命令 |
| `cli/SessionCommand.scala` | - | `nebflow-cli/src/commands/session.rs` | Session 命令 |
| `cli/ProcessManager.scala` | - | `nebflow-cli/src/process.rs` | 进程管理 |
| `cli/GatewayClient.scala` | - | `nebflow-cli/src/client.rs` | Gateway HTTP 客户端 |
| 其余 cli/*.scala | - | 对应 Rust 文件 | 各子命令 |

#### 验收条件
- [ ] `./nebflow` → 启动 Gateway 服务（等价于 `nebflow agent`）
- [ ] `./nebflow --help` → 显示所有可用命令
- [ ] `./nebflow agent` → 启动 Gateway + 浏览器
- [ ] `./nebflow config set model.default "anthropic/claude-sonnet-4"` → 更新配置
- [ ] `./nebflow config show` → 显示当前配置
- [ ] `./nebflow session list` → 列出所有 session
- [ ] `./nebflow session delete <id>` → 删除 session
- [ ] **端到端测试 1**：启动服务 → WS 连接 → 发送 "Hello" → 收到流式回复 → 关闭
- [ ] **端到端测试 2**：启动服务 → 通过 Delegate 触发子 Agent → 子 Agent 完成 → 结果返回
- [ ] **端到端测试 3**：启动服务 → 创建 Team → Manager 分配任务 → Member 执行 → 结果汇总
- [ ] **端到端测试 4**：启动服务 → 通过 REST API 配置 Provider → 浏览器中对话正常
- [ ] **配置文件兼容**：现有 `nebflow.json` 可被 Rust 版直接读取（格式 100% 兼容）
- [ ] **Session 文件兼容**：现有 session JSON 文件可被 Rust 版直接读取
- [ ] **Agent/Team/Flow 定义兼容**：现有 `~/.nebflow/agents/*/agent.json`、`teams/*/team.json`、`flows/*.json` 可被直接读取
- [ ] `cargo clippy` 无 warning

#### 预计工作量：3-5 天

---

### Phase 8: 性能验证 + 前端适配 + NebLink（3-5 天）

#### 目标
性能基准测试、前端 WS 协议适配、NebLink 设备发现迁移。

#### 涉及模块
| 模块 | 说明 |
|------|------|
| `neblink/*.scala` (1,427 行) | 设备发现 + P2P |
| `dropbox/*.scala` (598 行) | 跨设备文件传输 |
| 性能基准 | criterion 基准测试 |
| 前端 WS 适配 | 确认 WS 事件格式与前端 JS 兼容 |

#### 验收条件

**性能**：
- [ ] 启动时间 < 1s（Scala 版 3-5s）
- [ ] 内存占用 < 50MB 空载（Scala 版 200-400MB）
- [ ] 10 Agent 并发时 CPU < 30%（Scala 版 90-100%）
- [ ] SSE 流式延迟 < 20ms per delta（Scala 版 50-100ms）
- [ ] criterion 基准测试报告：JSON 序列化/反序列化比 Scala circe 快 3x+

**前端兼容**：
- [ ] 所有 WS 事件 JSON 格式与前端 JS 代码期望完全一致
- [ ] 流式文本输出（TextDelta）前端正常渲染
- [ ] 工具调用进度前端正常显示
- [ ] AskUserQuestion 前端正常弹出
- [ ] Delegate 子 Agent 事件正确路由到子窗口
- [ ] Team/Flow 管理界面正常工作
- [ ] Session 切换正常
- [ ] 配置界面正常

**NebLink**：
- [ ] 设备发现：同一网络下两个 Nebflow 实例互相发现
- [ ] 设备列表：前端正确显示在线/离线设备
- [ ] 跨设备工具执行：通过 RemoteExecutor 在远程设备执行命令
- [ ] 跨设备文件传输：TransferFile 工具正常工作

**Dropbox**：
- [ ] 跨设备消息投递
- [ ] 跨设备文件同步

#### 预计工作量：3-5 天

---

## 5. 功能完整性检查清单

### 5.1 工具系统

| 功能 | Scala 文件 | 迁移阶段 | 验收条件 | 状态 |
|------|-----------|---------|---------|------|
| Read | `ReadTool.scala` (223行) | Phase 4 | 读取文本/图片文件，返回内容或 ContentBlock::Image | ☐ |
| Write | `WriteTool.scala` (146行) | Phase 4 | 创建/覆盖文件，自动建父目录 | ☐ |
| Edit | `EditTool.scala` (260行) | Phase 4 | 精确匹配编辑，不匹配报错，生成 diff | ☐ |
| Glob | `GlobTool.scala` (141行) | Phase 4 | glob 模式匹配，按修改时间排序 | ☐ |
| Grep | `GrepTool.scala` (218行) | Phase 4 | ripgrep 调用，支持正则 + glob 过滤 + 多种输出模式 | ☐ |
| Bash | `BashTool.scala` (741行) | Phase 4 | 子进程执行，后台任务，超时控制，工作目录 | ☐ |
| WebSearch | `WebSearchTool.scala` (446行) | Phase 4 | 搜索 API 调用，多引擎支持 | ☐ |
| WebFetch | `WebFetchTool.scala` (299行) | Phase 4 | URL 抓取，markdown 转换，Playwright fallback | ☐ |
| Curl | `CurlTool.scala` (165行) | Phase 4 | HTTP 请求，可自定义 method/headers/body | ☐ |
| Pop | `PopTool.scala` (182行) | Phase 4 | 文件在 Canvas 中打开 | ☐ |
| Card | `CardTool.scala` (471行) | Phase 4 | HTML 卡片在 Canvas 中展示 | ☐ |
| AskUserQuestion | `AskUserQuestionTool.scala` (151行) | Phase 4 | 发出 AskUser 事件，等待用户回答 | ☐ |
| Delegate | `DelegateTool.scala` (492行) | Phase 4/6 | 生成子 Agent (background/persistent)，支持 agent/flow 参数 | ☐ |
| Mail | `MailTool.scala` (484行) | Phase 4/6 | 跨 Agent 通信，支持 fork 模式，TYPE 标签 | ☐ |
| FlowReport | `FlowReportTool.scala` | Phase 4/6 | 上报 Flow 节点 verdict + output | ☐ |
| Load | `LoadTool.scala` (190行) | Phase 4/6 | 验证并加载 Team/Flow 定义 | ☐ |
| TransferFile | `TransferFileTool.scala` (308行) | Phase 4/8 | 跨设备文件传输 | ☐ |
| TaskCreate | `TaskCreateTool.scala` (104行) | Phase 4 | 创建任务 | ☐ |
| TaskUpdate | `TaskUpdateTool.scala` (185行) | Phase 4 | 更新任务状态 | ☐ |
| Schedule | `ScheduleTool.scala` (122行) | Phase 4 | 定时任务（Nebula 专属） | ☐ |
| SaveWorkspaceItem | `SaveWorkspaceItemTool.scala` (86行) | Phase 4 | 保存工作区知识 | ☐ |
| RemoveUnnecessary | `RemoveUnnecessaryTool.scala` (81行) | Phase 4 | 上下文清理 | ☐ |
| 外部工具 (JSON+脚本) | `ToolLoader.scala` (244行) | Phase 4 | 从 `~/.nebflow/tools/` 加载 JSON 定义 + 脚本 | ☐ |
| MCP 工具 | `mcp/*.scala` | Phase 4 | stdio/HTTP transport，tools/list，tools/call | ☐ |

### 5.2 架构系统

| 功能 | 迁移阶段 | 验收条件 | 状态 |
|------|---------|---------|------|
| Agent 定义和加载 | Phase 1/3 | 从 `~/.nebflow/agents/*/` 加载 agent.json + system.md | ☐ |
| Agent 执行循环 | Phase 3 | UserInput → LLM → Tools → LLM → ... → TurnComplete | ☐ |
| Agent 子代理 (depth) | Phase 3 | depth > 0 的子 Agent 正确运行，工具列表受限 | ☐ |
| Team 架构 | Phase 6 | Manager + Members，Mail 通信，共享 session | ☐ |
| Flow DAG 架构 | Phase 6 | entry → node → onComplete 路由 → Switch → maxLoop | ☐ |
| Agent Library | Phase 3 | 从磁盘加载 agent 定义，支持运行时注册 | ☐ |

### 5.3 通信系统

| 功能 | 迁移阶段 | 验收条件 | 状态 |
|------|---------|---------|------|
| WebSocket 服务 | Phase 5 | 浏览器建立 WS 连接，收发消息 | ☐ |
| 流式文本输出 | Phase 3/5 | TextDelta 事件 → 前端实时渲染 | ☐ |
| 流式思考输出 | Phase 3/5 | ThinkingDelta 事件 → 前端显示 | ☐ |
| 工具调用进度 | Phase 3/5 | ToolCallStart / ToolArgDelta / ToolResult 事件 | ☐ |
| AskUserQuestion WS | Phase 3/5 | AskUser 事件 → 前端弹出 → UserAnswered 回传 | ☐ |
| 权限确认 WS | Phase 3/5 | PermissionRequest → 前端确认 → PermissionAnswered | ☐ |
| Provider fallback WS | Phase 3/5 | RetryStatus 事件 → 前端显示 "正在切换模型..." | ☐ |
| 子 Agent 事件路由 | Phase 3/5 | Delegate 子 Agent 的 WS 事件携带 nodeSessionId | ☐ |

### 5.4 LLM 系统

| 功能 | 迁移阶段 | 验收条件 | 状态 |
|------|---------|---------|------|
| Anthropic Claude | Phase 2 | /v1/messages 流式调用，SSE 解析，thinking 支持 | ☐ |
| OpenAI-compatible | Phase 2 | /v1/chat/completions 流式，支持 DeepSeek/GLM 等 | ☐ |
| Provider fallback 链 | Phase 2 | default → fallbacks 依次尝试，错误分类 | ☐ |
| Health Monitor | Phase 2 | 健康检查，Down 标记，自动恢复探测 | ☐ |
| 模型 fallback 通知 | Phase 2 | onAttempt 回调，前端显示 fallback 链 | ☐ |
| Prompt caching | Phase 2/3 | cache_control: ephemeral 正确设置 | ☐ |
| 流式超时检测 | Phase 2 | 首 token 90s + 中途 60s，超时自动 fallback | ☐ |
| Token 使用追踪 | Phase 2/3 | input/output/cache_read/cache_write 正确解析 | ☐ |
| 上下文压缩 | Phase 3 | FastMicroCompact + FullCompact + DreamMode | ☐ |
| Session 模型覆盖 | Phase 2 | 每个 session 可独立设置模型 | ☐ |

### 5.5 持久化系统

| 功能 | 迁移阶段 | 验收条件 | 状态 |
|------|---------|---------|------|
| Session 消息持久化 | Phase 3 | 工具调用后写 JSON 文件，原子写入 (tmp + rename) | ☐ |
| Session 索引 | Phase 5 | 创建/删除 session 时更新索引 | ☐ |
| Session 恢复 | Phase 5 | 重启后加载历史 session | ☐ |
| 配置文件 | Phase 7 | nebflow.json 读取/写入，向后兼容 | ☐ |
| 配置快照 | Phase 7 | 自动保存/恢复配置快照 | ☐ |

### 5.6 设备系统

| 功能 | 迁移阶段 | 验收条件 | 状态 |
|------|---------|---------|------|
| NebLink 设备发现 | Phase 8 | 同网络设备互相发现，trusted IP 管理 | ☐ |
| NebLink Presence | Phase 8 | WS 持久连接，在线/离线实时通知 | ☐ |
| NebLink Server 心跳 | Phase 8 | 30s 心跳，session liveness | ☐ |
| 跨设备工具执行 | Phase 8 | RemoteExecutor 通过 P2P 派发工具 | ☐ |
| 跨设备文件传输 | Phase 8 | TransferFile / Dropbox 服务 | ☐ |
| 设备能力声明 | Phase 8 | capabilities (CPU/GPU/OS) 正确声明和查询 | ☐ |

### 5.7 其他系统

| 功能 | 迁移阶段 | 验收条件 | 状态 |
|------|---------|---------|------|
| 条件提示词注入 | Phase 3 | 根据 depth/agentName/devices/sessions 等条件构建提示词 | ☐ |
| Memory 系统 | Phase 3 | User.md + Agent memory.md 注入 system prompt | ☐ |
| Skills 系统 | Phase 3 | SKILL.md 加载，catalog 注入，skill 激活 | ☐ |
| 系统提醒 | Phase 3 | SystemReminders 在特定条件下注入 | ☐ |
| 语言检测 | Phase 3 | 检测用户语言，影响压缩提示词语言 | ☐ |
| Hooks 系统 | Phase 3 | 钩子配置加载 + 生命周期事件触发 | ☐ |
| Telemetry | Phase 7 | opt-out 遥测，任务推断 | ☐ |
| 守护进程 | Phase 5 | 后台守护进程模式 | ☐ |
| 定时任务 | Phase 4 | ScheduledTaskActor → tokio task 定时执行 | ☐ |
| 语音 STT/TTS | Phase 5 | 语音输入/输出 | ☐ |
| 权限模式 | Phase 3 | confirm-edits / auto / yolo 三种安全模式 | ☐ |
| Bridge 插件 | Phase 8 | Telegram 等外部平台桥接 | ☐ |

---

## 6. 风险和缓解措施

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| **cats-effect IO 范式翻译错误** — 1,914 处 IO 使用，语义复杂（并发、取消、资源管理），翻译时容易遗漏边界条件 | 高 | 高 | 每个 Phase 完成后做端到端回归测试；重点测试取消语义（Interrupt）、资源释放（Drop trait）、并发安全（Send + Sync） |
| **SSE 流式解析差异** — fs2 Stream 的背压、错误恢复、并发组合（concurrently）在 Rust 中无直接等价 | 高 | 高 | 使用 `tokio_stream` + `futures::StreamExt`；为每个 provider 写集成测试，验证流式 chunk 顺序和内容 |
| **circe → serde JSON 格式差异** — 某些边缘情况（null 处理、空对象、数字精度）格式可能不同 | 中 | 高 | Phase 1 完成后做 JSON 字节级比对测试；用真实 session 文件做 round-trip 测试 |
| **前端 WS 兼容性** — 前端 JS 期望特定的 JSON 字段和格式，任何偏差都会导致 UI 异常 | 高 | 高 | Phase 5 完成后用现有前端做完整功能测试；维护 WS 事件 JSON schema 文档 |
| **工具行为差异** — Bash 工具的后台任务管理、文件锁、进程超时等行为在不同语言中实现差异大 | 中 | 中 | 为 Bash 工具编写详尽的集成测试（前台/后台/超时/取消）；使用 `tokio::process` 替代 `ProcessBuilder` |
| **编译时间过长** — 47K 行 Rust 代码可能编译很慢，影响开发效率 | 中 | 低 | 使用 `cargo check`（不生成代码）做快速验证；sccache 缓存；分 crate 编译 |
| **tokio 任务取消语义** — Rust 的 task 取消（`JoinHandle::abort()`）与 cats-effect Fiber 取消语义不完全相同 | 中 | 中 | 文档化取消语义差异；Agent 的 `stop()` 使用 channel close + graceful drain |
| **工作量和工期超预期** — 47K 行代码迁移，某些模块复杂度被低估 | 高 | 中 | 每个 Phase 完成后评估进度，必要时调整后续 Phase 的范围；优先保证核心路径（对话 + 工具 + WS），非核心功能可延后 |
| **MCP 协议兼容性** — MCP 是较新的协议，Rust 生态缺乏成熟库 | 低 | 中 | 自研 MCP 客户端（< 500 行），严格遵循 MCP 1.0 规范；用现有 MCP server 做集成测试 |
| **Rust 异步学习曲线** — 团队如果对 Rust async 不熟悉，初期效率低 | 中 | 中 | Phase 0 投入时间学习 tokio 模式；编写内部 async Rust 编码规范 |

---

## 7. 开发节奏建议

### 7.1 Worktree 开发流程

```bash
# 创建 worktree
cd /Users/dev/Claude\ code/Nebflow
git worktree add ../nebflow-rust rust-migration

# 在 nebflow-rs/ 目录下创建 Rust workspace
cd ../nebflow-rust
mkdir -p nebflow-rs && cd nebflow-rs
cargo init --workspace
```

### 7.2 阶段里程碑

| 里程碑 | 完成阶段 | 可演示能力 |
|--------|---------|-----------|
| **M1: 类型完备** | Phase 0-1 | 所有类型编译通过，serde round-trip 测试通过 |
| **M2: 可以对话** | Phase 2-3 | CLI 中与 Agent 对话（无 UI），流式输出正常 |
| **M3: 可以用工具** | Phase 4 | Read/Write/Edit/Bash 工具正常工作 |
| **M4: 有界面** | Phase 5 | 浏览器打开，完整 UI 可用，流式输出 + 工具进度 |
| **M5: 全功能** | Phase 6-7 | Team/Flow 架构工作，端到端测试通过 |
| **M6: 可上线** | Phase 8 | 性能达标，前端完全兼容，NebLink 工作 |

### 7.3 测试策略

| 层级 | 测试类型 | 工具 | 目标覆盖率 |
|------|---------|------|-----------|
| 单元测试 | 类型序列化、工具逻辑、DAG 路由 | `cargo test` | > 80% |
| 集成测试 | LLM 调用（mock）、工具执行（真实文件系统） | `cargo test` (integration) | 关键路径 100% |
| 端到端测试 | 启动服务 → WS 对话 → 工具调用 → 断言结果 | 自研脚本 | 核心场景全覆盖 |
| 性能基准 | JSON 序列化、SSE 解析、内存占用 | `criterion` + `hyperfine` | Phase 8 验收 |
| 兼容性测试 | 现有 session/agent/team/flow 文件读取 | 自动化脚本 | 100% |

### 7.4 每个 Phase 的 DoD (Definition of Done)

1. `cargo build --workspace` 零 error
2. `cargo clippy --workspace` 零 warning
3. `cargo test --workspace` 全部通过
4. 该 Phase 的验收条件全部满足
5. 代码已提交到 `rust-migration` 分支
6. 与前一阶段的功能做回归验证（不引入退化）
