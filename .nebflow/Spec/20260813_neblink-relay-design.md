# NebLink Relay（中继）功能设计方案

> 目标：设备 A 跨网络（无 VPN/Tailscale）对设备 B 执行 remote-exec 时，
> 请求经 NebLink Server 中继转发。纯方案设计，不修改任何代码。

---

## 1. 现状分析

### 1.1 当前架构（纯 P2P）

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| NebLink Server | Rust / axum 0.8 / DashMap / rusqlite | 设备注册、OAuth、发现、心跳。**纯 HTTP，无 WebSocket** |
| Nebflow 客户端 | Scala / JDK HttpClient | 登录、心跳、peer 发现、P2P 工具调用 |
| P2P 通道 | 设备间直连 HTTP | `POST /api/neblink/remote-exec`（action+params）|

**问题**：`RemoteExecutor.p2pExecute`（`src/main/scala/nebflow/core/tools/RemoteExecutor.scala:320`）
直接 `POST ${peer.address}/api/neblink/remote-exec`。`peer.address` 来自 NebLink Server
返回的 endpoints（LAN IP）。当 A、B 不在同一网络时，IP 不可路由，直连失败。

**关键发现（可复用的已有能力）**：

1. **Scala 端已有 WebSocket 客户端**：`NeblinkPresenceService`
   （`src/main/scala/nebflow/neblink/NeblinkPresenceService.scala`）使用 JDK
   `java.net.http.WebSocket` 管理设备间 presence 连接，含自动重连、心跳。
   Relay 隧道的 WS 客户端可直接复用这套模式。
2. **Server 端已有 session 鉴权**：`extract_token` 解析 `Authorization: Bearer`，
   `Store.devices: DashMap<sessionToken, RegisteredDevice>` 维护在线设备（含
   `device_id`、`network_id`、`user_id`）。Relay 只需在此基础上加一张
   `deviceId → WS channel` 的映射表。
3. **remote-exec 端点已定义**：`RestApiRoutes.scala:694` 的 `POST /api/neblink/remote-exec`
   接收 `{action, params, projectRoot}`，本地执行 `ToolRegistry.call`，返回
   `{output}` 或 `{error}`。Relay 只需把同样的 payload 经 WS 转发给 B，B 端
   复用同一执行逻辑。

### 1.2 跨网络失败的根因

```
设备 A (LAN 192.168.1.5)  ──直连✗──>  设备 B (LAN 10.0.0.8)
         │                                    │
         └──── NebLink Server（仅发现） ───────┘
              返回 B 的 LAN IP，A 无法路由到
```

---

## 2. 推荐方案：WebSocket 隧道中继（方案 B）

### 2.1 方案对比与选择理由

| 维度 | A. HTTP 反向代理 | **B. WebSocket 隧道（推荐）** | C. Redis Pub/Sub |
|------|-----------------|------------------------------|------------------|
| 持久连接 | 需要（WS 或长轮询） | **WS，axum 原生支持** | 依赖 Redis 订阅 |
| 请求-响应匹配 | 连接级（一请求一连接） | **requestId + oneshot（多路复用）** | 需额外 correl. 机制 |
| Server 依赖 | 无新增 | 无新增（axum `ws` feature） | **需引入 Redis** |
| 与现有架构契合 | 需新建长轮询/WS | **复用 PresenceService 的 WS 模式** | 破坏单进程内存模型 |
| 延迟 | 每请求建连 | **单连接多路复用，最低** | Pub/Sub 跳数多 |
| 实现复杂度 | 中 | **中** | 高（新组件 + 运维） |

**选择方案 B 的核心理由**：

1. **axum 0.8 原生支持 WebSocket**（仅需启用 `ws` feature），服务端改动最小。
2. **Scala 客户端已验证 WS 可行**——`NeblinkPresenceService` 用 JDK WebSocket
   实现了设备间 presence，relay 隧道的客户端代码可高度复用其连接/重连/心跳逻辑。
3. **单连接多路复用**：一台设备维持一条到 Server 的 WS，所有 relay 请求复用此连接，
   用 `requestId`（UUID）关联请求与响应。Server 用 `DashMap<requestId, oneshot::Sender>`
   完成异步等待——无需轮询，无需额外连接。
4. **不引入新依赖**：当前 Server 是单进程 + SQLite + 内存 DashMap 架构。Redis
   会增加运维负担且与现有模型不匹配。

> 注：方案 A（HTTP 反向代理）本质上需要同样的持久连接（否则就是低效的长轮询），
> 一旦用 WS 持久化，它与方案 B 的区别仅在于是否用 requestId 多路复用。B 是 A 的
> 超集且更高效，因此直接选 B。

### 2.2 架构对比图

![架构对比](/tmp/relay_compare.svg)

---

## 3. 技术设计

### 3.1 请求流转图

![请求流转](/tmp/relay_flow.svg)

### 3.2 Server 端改动（Rust）

#### 3.2.1 新增依赖

`Cargo.toml` 启用 axum 的 `ws` feature：

```toml
axum = { version = "0.8", features = ["ws"] }
```

#### 3.2.2 新增 RelayRegistry（内存状态）

在 `AppState` 中新增一个 `RelayRegistry`，与现有 `Store` 平级：

```rust
// 新增模块：src/relay.rs
use dashmap::DashMap;
use tokio::sync::{mpsc, oneshot};
use uuid::Uuid;

/// Server → 目标设备的 WS 出站消息
pub enum ServerToClient {
    /// 转发一个 relay 请求给目标设备
    RelayRequest {
        request_id: String,
        action: String,           // "Bash", "Read", ...
        params: serde_json::Value,
        project_root: String,
    },
    /// 主动断开（设备被吊销等）
    Disconnect,
}

/// 目标设备 → Server 的 WS 入站消息
#[derive(serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientToServer {
    RelayResponse {
        request_id: String,
        #[serde(default)]
        output: String,
        #[serde(default)]
        error: String,
    },
    Ping,
}

/// 全局 relay 状态，放进 AppState
#[derive(Clone, Default)]
pub struct RelayRegistry {
    /// deviceId → WS 出站 channel（目标设备在线时存在）
    pub tunnels: Arc<DashMap<String, mpsc::UnboundedSender<ServerToClient>>>,
    /// requestId → pending HTTP 响应的 oneshot（请求进行中）
    pub pending: Arc<DashMap<String, oneshot::Sender<ClientToServer>>>,
}
```

#### 3.2.3 新增端点

**端点 1：`GET /api/device/relay-ws`（WebSocket 升级）**

设备 B 启动后建立此连接，注册为"可中继"。

```rust
// src/routes.rs 新增
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};

pub async fn relay_ws(
    State(state): State<AppState>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    // 1. 验证 sessionToken（复用现有 extract_token + Store.devices）
    let token = extract_token(&headers)
        .ok_or_else(|| forbidden("Missing token"));
    let device = state.store.device_by_token(&token?)?;  // 新增 store 方法

    // 2. 升级 WebSocket
    ws.on_upgrade(move |socket| relay_ws_loop(state, device.device_id, socket))
}

async fn relay_ws_loop(state: AppState, device_id: String, mut socket: WebSocket) {
    let (tx, mut rx) = mpsc::unbounded_channel::<ServerToClient>();
    state.relay.tunnels.insert(device_id.clone(), tx);

    // 双向 pump：WS 入站消息 → 处理；registry 出站消息 → WS 发送
    loop {
        tokio::select! {
            // Server → 设备（从 registry channel 读，写 WS）
            Some(msg) = rx.recv() => {
                match msg {
                    ServerToClient::RelayRequest { .. } => {
                        let json = serde_json::to_string(&msg).unwrap();
                        if socket.send(Message::Text(json)).await.is_err() { break; }
                    }
                    ServerToClient::Disconnect => break,
                }
            }
            // 设备 → Server（读 WS）
            Some(Ok(ws_msg)) = socket.recv() => {
                match ws_msg {
                    Message::Text(text) => {
                        let parsed: ClientToServer = serde_json::from_str(&text).unwrap();
                        match parsed {
                            ClientToServer::RelayResponse { request_id, .. } => {
                                // 找到 pending oneshot，complete 它
                                if let Some((_, sender)) = state.relay.pending.remove(&request_id) {
                                    let _ = sender.send(parsed);
                                }
                            }
                            ClientToServer::Ping => { /* 发 pong 或忽略 */ }
                        }
                    }
                    Message::Close(_) => break,
                    _ => {}
                }
            }
            else => break,
        }
    }

    // 清理：移除隧道，fail 所有 pending（设备掉线）
    state.relay.tunnels.remove(&device_id);
    // pending 中属于此设备的请求由 relay_exec 的超时兜底
}
```

**端点 2：`POST /api/relay/{target_device_id}/exec`**

设备 A 发起中继请求。

```rust
pub async fn relay_exec(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(target_device_id): Path<String>,
    Json(req): Json<RelayExecRequest>,  // {action, params, project_root}
) -> Response {
    // 1. 验证 A 的身份
    let caller_token = extract_token(&headers).ok_or(forbidden("Missing token"))?;
    let caller = state.store.device_by_token(&caller_token)?;
    let target = state.store.device_by_id(&target_device_id)
        .ok_or(not_found("Target device offline or not found"))?;

    // 2. 安全校验：同账号（user_id 一致）
    if caller.user_id != target.user_id {
        return forbidden("Cross-account relay denied").into_response();
    }

    // 3. 检查目标有活跃隧道
    let tunnel = state.relay.tunnels.get(&target_device_id)
        .ok_or(service_unavailable("Target device has no relay tunnel"))?;

    // 4. 生成 requestId，建立 oneshot
    let request_id = Uuid::new_v4().to_string();
    let (tx, rx) = oneshot::channel();
    state.relay.pending.insert(request_id.clone(), tx);

    // 5. 通过 WS 转发给目标
    tunnel.send(ServerToClient::RelayRequest {
        request_id: request_id.clone(),
        action: req.action,
        params: req.params,
        project_root: req.project_root,
    }).ok();

    // 6. 等待响应，超时 120s
    match tokio::time::timeout(Duration::from_secs(120), rx).await {
        Ok(Ok(resp)) => {
            state.relay.pending.remove(&request_id);  // 已 complete，清理
            Json(serde_json::json!({
                "output": resp.output,
                "error": resp.error
            })).into_response()
        }
        _ => {
            state.relay.pending.remove(&request_id);
            (StatusCode::GATEWAY_TIMEOUT,
             Json(ErrorResponse::new("Relay request timed out (device offline?)")))
                .into_response()
        }
    }
}
```

#### 3.2.4 Store 新增方法

`store.rs` 需补充两个查询（从现有 `devices: DashMap` 中查找）：

```rust
impl Store {
    /// 按 sessionToken 查在线设备（返回 clone）
    pub fn device_by_token(&self, token: &str) -> Option<RegisteredDevice> {
        self.devices.get(token).map(|d| d.clone())
    }

    /// 按 deviceId 查在线设备（遍历 DashMap）
    pub fn device_by_id(&self, device_id: &str) -> Option<RegisteredDevice> {
        self.devices.iter()
            .find(|d| d.device_id == device_id)
            .map(|d| d.clone())
    }
}
```

#### 3.2.5 路由注册

`main.rs` 的 Router 新增：

```rust
.route("/api/device/relay-ws", get(routes::relay_ws))
.route("/api/relay/{target_device_id}/exec", post(routes::relay_exec))
```

#### 3.2.6 发现端点增强（可选但推荐）

`get_peers` / `heartbeat` 返回的 `PeerInfo` 增加 `relay_available: bool` 字段，
从 `RelayRegistry.tunnels` 查询。客户端据此知道某 peer 是否可中继。

```rust
// model.rs PeerInfo 增加字段
pub struct PeerInfo {
    // ...existing fields...
    #[serde(default)]
    pub relay_available: bool,
}
```

### 3.3 Client 端改动（Scala）

#### 3.3.1 新增 `NeblinkRelayTunnel`（WS 隧道客户端）

复用 `NeblinkPresenceService` 的 WS 连接/重连/心跳模式，但目标是 Server 而非 peer：

```scala
// src/main/scala/nebflow/neblink/NeblinkRelayTunnel.scala（新增）
final class NeblinkRelayTunnel(
  serverUrl: String,       // https://neblink.nebflow.space
  neblinkService: NeblinkService,
  sessionTokenRef: Ref[IO, Option[String]]  // 从 NeblinkClient 同步
)(dispatcher: Dispatcher[IO]):
  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  @volatile private var ws: Option[WebSocket] = None

  /** 连接到 Server 的 relay WS（带自动重连） */
  def connect(): IO[Unit] = ...

  /** 收到 relay_request → 本地执行 → 回 relay_response */
  private def handleRelayRequest(msg: Json): IO[Unit] =
    val requestId = msg.hcursor.downField("requestId").as[String].getOrElse("")
    val action    = msg.hcursor.downField("action").as[String].getOrElse("")
    val params    = msg.hcursor.downField("params").as[JsonObject].getOrElse(JsonObject.empty)
    for
      // 复用 RestApiRoutes 中 remote-exec 的执行逻辑
      result <- executeLocally(action, params)
      resp = Json.obj(
        "type" -> "relay_response".asJson,
        "requestId" -> requestId.asJSON,
        "output" -> result.output.asJson,
        "error"  -> result.error.asJson
      )
      _ <- sendWs(resp)
    yield ()
```

**关键复用**：`executeLocally` 调用 `ToolRegistry.TOOL_MAP.get(action).call(params, ctx)`，
与 `RestApiRoutes.scala:702-714` 的 `remote-exec` 端点完全相同的执行路径——
只是输入来自 WS 而非 HTTP。

#### 3.3.2 `RemoteExecutor` 增加 relay 回退路径

`RemoteExecutor.scala` 的 `execute` 方法增加策略：

```scala
def execute(deviceName: String, toolName: String, params: JsonObject, ...): IO[...] =
  neblinkService.peers.flatMap { peers =>
    resolvePeer(deviceName, peers) match
      case Right(peer) =>
        // 新增：尝试 P2P，失败则回退 relay
        p2pExecuteWithRetry(peer, toolName, params, SyncTimeout).flatMap {
          case Right(result) => IO.pure(Right(result))
          case Left(err) if isConnectionError(err) && relayAvailable(peer) =>
            // P2P 连不通（跨网络），尝试中继
            logger.info(s"P2P failed for ${peer.deviceName}, falling back to relay") *>
              relayExecute(peer, toolName, params)
          case Left(err) => IO.pure(Left(err))
        }
      case Left(err) => IO.pure(Left(err))
  }

/** 通过 Server 中继执行 */
private def relayExecute(peer: PeerInfo, toolName: String, params: JsonObject): IO[...] =
  relayClient match
    case None => IO.pure(Left(ToolError("Relay not configured")))
    case Some(client) => client.exec(peer.deviceId, toolName, params)
```

**回退策略**：
- `isConnectionError`：匹配 "cannot reach" / connection refused（**不**匹配 timeout——
  timeout 意味着命令在跑但慢，不应中继重试）。
- `relayAvailable(peer)`：peer 的 `relay_available == true`（来自发现端点）。

#### 3.3.3 启动集成

`GatewayMain` 在初始化时，如果配置了 NebLink Server 且登录成功，
启动 `NeblinkRelayTunnel.connect()`（后台 fiber，自动重连）。

`sessionTokenRef` 与 `NeblinkClient` 的 `@volatile sessionToken` 同步——
当 client 登录/重新登录时更新 tunnel 的 token。

### 3.4 设备 B 如何注册为"可中继"

**设计决策：默认开启（always-on），无需用户配置。**

理由：
1. WS 连接到 Server 开销极低（单连接、空闲时只有心跳）。
2. 设备 B 只有连了隧道才能被中继——如果需要用户手动开启，跨网络协作时
   经常会遇到"目标设备没开 relay"的困惑。
3. 安全由 Server 端的同账号校验保证，不依赖 B 端开关。

**实现**：`GatewayMain` 启动 `NeblinkRelayTunnel` 即注册。
Server 的 `relay_ws_loop` 在 WS 建立时 `tunnels.insert(deviceId, tx)`，
断开时自动移除。设备掉线后 `pending` 中的请求由 120s 超时兜底。

> 如果未来需要"设备可拒绝被中继"的能力，在 `NeblinkConfig` 加
> `allowRelay: Boolean`（默认 true），tunnel 连接时在 WS URL 带
> `?relay=server` 参数告知意图即可。

---

## 4. WebSocket 消息协议

### 4.1 Server → 设备 B

```json
{
  "type": "relay_request",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "action": "Bash",
  "params": { "command": "ls -la", "timeout": 30000 },
  "projectRoot": "/home/user/project"
}
```

### 4.2 设备 B → Server

```json
{
  "type": "relay_response",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "output": "total 42\ndrwxr-xr-x ...",
  "error": ""
}
```

成功时 `error` 为空字符串，`output` 含结果。失败时 `error` 非空。

### 4.3 心跳

复用 Presence 模式：`{"type":"ping"}` / `{"type":"pong"}`，每 10s。
超过 30s 无 pong → Server 主动关闭隧道并清理。

---

## 5. 安全考虑

| 威胁 | 防护措施 |
|------|----------|
| 未授权设备发起中继 | A 必须携带有效 `sessionToken`（`Store.devices` 验证） |
| 跨账号中继（A 中继到别人的 B） | Server 校验 `caller.user_id == target.user_id`（同账号模型） |
| 伪造 requestId 窃取他人响应 | requestId 为 Server 生成的 UUID，设备端无法预测/枚举；pending map 按 requestId 精确匹配 |
| relay WS 被劫持 | WS 升级时验证 Bearer token；生产环境走 `wss://`（Caddy 已提供 TLS） |
| 请求过大（DoS） | Server 对 relay body 设 10MB 上限（axum `DefaultBodyLimit`）；后续 file-transfer 单独设限 |
| 命令注入 | relay 不改变执行语义——B 端复用 ToolRegistry，与直接收到 `/remote-exec` 的安全级别一致 |
| 中继无限等待 | Server 端 120s 超时（`tokio::time::timeout`），超时返回 504 Gateway Timeout |
| 设备掉线后 pending 堆积 | WS 断开时 `tunnels.remove`；pending 由各自的 120s 超时自动清理 |

**信任模型**：NebLink Server 是信任边界（与现有发现/心跳一致）。Server 能看到
relay 的 action 和 params 明文——这与"设备间直连时 Server 不知道内容"不同。
如果未来需要端到端加密（Server 看不到明文），可用设备间 ECDH 协商的会话密钥
加密 relay payload，但这超出最小实现范围。

---

## 6. 最小实现范围

### Phase 1（本方案）：remote-exec 中继

- [x] Server：`relay-ws` WS 端点 + `relay/{id}/exec` HTTP 端点
- [x] Server：RelayRegistry（tunnels + pending）
- [x] Client：`NeblinkRelayTunnel`（WS 连接 + 本地执行 + 自动重连）
- [x] Client：`RemoteExecutor` P2P→relay 自动回退
- [x] 支持 Bash / Read / Write / Edit / Glob / Grep（与现有 remote-exec 工具集一致）

### Phase 2（后续）：file-transfer 中继

- 需要分块传输（WS 单帧有大小限制，大文件需 chunk + sequence）
- 单独的 `relay/{id}/file-transfer` 端点或复用 exec 通道加 `type: file_chunk`
- 请求体大小限制放宽或改为流式

### 不在本方案范围

- 端到端加密（Server 不可见明文）
- 跨账号中继（需显式授权机制）
- 多 Server 实例水平扩展（当前单实例，RelayRegistry 在内存）

---

## 7. 涉及文件

### Server（Rust）— `/tmp/neblink-server-standalone/`

| 文件 | 改动 |
|------|------|
| `Cargo.toml` | axum 加 `features = ["ws"]` |
| `src/main.rs` | Router 注册 2 个新路由；`AppState` 增加 `relay: RelayRegistry` |
| `src/relay.rs`（**新增**） | `RelayRegistry`、`ServerToClient`/`ClientToServer` 消息类型、`relay_ws_loop` |
| `src/routes.rs` | `relay_ws`、`relay_exec` handler；`AppState` 加 relay 字段 |
| `src/store.rs` | `device_by_token`、`device_by_id` 方法 |
| `src/model.rs` | `PeerInfo` 加 `relay_available`；`RelayExecRequest` 结构 |

### Client（Scala）— `src/main/scala/nebflow/neblink/`

| 文件 | 改动 |
|------|------|
| `NeblinkRelayTunnel.scala`（**新增**） | WS 隧道客户端：连接、重连、心跳、收发 relay 消息 |
| `RemoteExecutor.scala` | `execute` 增加 P2P→relay 回退；新增 `relayExecute` 方法 |
| `NeblinkDiscovery.scala` / `GatewayMain.scala` | 启动时初始化 `NeblinkRelayTunnel`，注入 `RemoteExecutor` |
| `NeblinkClient.scala` | `sessionToken` 暴露给 tunnel（同步认证状态） |

---

## 8. 验收标准

### 8.1 编译与启动（硬性）

- [ ] `cd /tmp/neblink-server-standalone && cargo build` 通过（含 `ws` feature）
- [ ] `cargo run` 启动 Server，日志输出 "NebLink Server started on :9090"
- [ ] `curl http://localhost:9090/api/health` 返回 `{"status":"ok"}`
- [ ] Scala 客户端 `sbt compile` 通过
- [ ] `sbt run` 启动网关，无异常

### 8.2 功能验收

- [ ] **跨网络 relay 执行成功**：设备 A（模拟跨网络，peer 无可达 endpoint）对设备 B
      执行 `Bash` 工具，请求经 Server 中继，B 执行后结果返回 A 的 LLM
- [ ] **P2P 回退 relay**：同网络两台设备先走 P2P（直连成功），断开网络后自动回退 relay
- [ ] **同网络无回归**：不启用 relay 时，现有 P2P remote-exec 正常工作
- [ ] **requestId 关联正确**：并发 5 个 relay 请求，响应一一对应无错配

### 8.3 安全验收

- [ ] **无效 token 被拒**：`POST /api/relay/{id}/exec` 无 Bearer → 403
- [ ] **跨账号被拒**：A（user1）对 user2 的设备发起 relay → 403
- [ ] **目标离线超时**：目标设备无 WS 隧道 → 503；有隧道但设备不响应 → 120s 后 504

### 8.4 端到端测试

```bash
# 1. 启动 Server
cd /tmp/neblink-server-standalone && cargo run &

# 2. 启动两台设备（不同配置/网络模拟），登录同一账号
#    设备 A:
sbt run  # 配置 NebLink Server
#    设备 B:
sbt run  # 配置 NebLink Server（同账号）

# 3. 验证 B 的 WS 隧道已连接（Server 日志出现 "relay tunnel registered: <deviceId>"）

# 4. 在 A 上请求对 B 执行
curl -X POST http://localhost:8080/api/relay/{B.deviceId}/exec \
  -H "Authorization: Bearer {A.sessionToken}" \
  -H "Content-Type: application/json" \
  -d '{"action":"Bash","params":{"command":"hostname"},"projectRoot":"/tmp"}'
# 期望：返回 B 的 hostname

# 5. 断开 B 的网络（或 kill B），A 再次执行 → 120s 超时返回 504
```

### 8.5 WS 连接稳定性

- [ ] B 的 WS 断开后（网络抖动）自动重连，重连后 relay 恢复可用
- [ ] Server 重启后，B 自动重新建立隧道（tunnel 重连机制）
- [ ] WS 空闲 5 分钟无请求，连接仍存活（心跳保活）
