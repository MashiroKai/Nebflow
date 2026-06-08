# Nebflow Mesh — 设计文档

> 状态：Phase 1 实施中
> 分支：feature/mesh
> 更新：2026-06-08

## 1. 目标

让多台 Nebflow 实例通过一个共享 token 自动发现彼此、直连通信。

核心场景：用户在 Mac 上写 Verilog 代码，agent 通过 MeshTool 直接在 Windows 上执行 Vivado。

设计原则：

1. **零配置配对** — 用户只需输入一个 token，不填 IP、不填邀请码
2. **云当电话簿不当中转站** — 腾讯云只做发现服务和用户数据存储，实时通信走 P2P
3. **设备平等** — 没有主从关系，每台设备地位相同
4. **数据本地** — 每台设备存储完整数据副本，同步只传差异

## 2. 架构

```
 LAN（UDP 广播自动发现）           腾讯云（仅发现 + 数据存储）
┌──────────┐◄───────────────────►┌──────────┐     ┌──────────────────┐
│  Mac     │                     │ Windows  │     │  Cloud Function  │
│  :8082   │◄── HTTP 直连 ─────►│  :8080   │     │  - register()    │
└──────────┘                     └──────────┘     │  - lookup()      │
      ▲                                           │  - userData()    │
      │  token = "kaiyu-lab-2025"                 └──────────────────┘
      ▼
┌──────────┐
│  Linux   │   数据流：设备 ←→ 设备（直连）
│  :8083   │   发现：UDP 广播（LAN）或 云查询（跨网）
└──────────┘
```

### 2.1 配对流程

**用户操作（1 步）：**

每台设备的 Mesh 面板输入同一个 token（如 `kaiyu-lab-2025`）。

**自动流程：**

1. 设备计算 `SHA-256(token)` 作为 tokenHash
2. LAN：每 10 秒 UDP 广播 `{ tokenHash, port }` 到 `255.255.255.255:19876`
3. 同 token 设备收到广播后回应自己的 Nebflow 地址
4. 双方 HTTP 直连，Bearer token 认证
5. 建立信任关系，开始同步和远程执行

### 2.2 设备发现

**LAN 发现（默认，Phase 1）：**

```
设备 A 每隔 10 秒广播：
  UDP → 255.255.255.255:19876
  Body: { "type": "discover", "tokenHash": "sha256(token)", "port": 8082 }

设备 B 收到广播：
  1. 计算 sha256(自己的 token)
  2. 匹配 → 回复 HTTP handshake
  3. POST → http://{sender_ip}:{sender_port}/api/mesh/handshake
     Authorization: Bearer {token}
     Body: { "deviceId": "...", "deviceName": "...", "platform": "..." }

设备 A 收到 handshake：
  1. 验证 token 一致
  2. 添加到 peer 列表
  3. 开始同步
```

**跨网络发现（Phase 3）：**

设备通过 HTTPS 端点注册和查询：
- `POST /register { tokenHash, address }` — 注册自己的地址
- `POST /lookup { tokenHash }` — 查找同组其他设备的地址

只交换地址，不传输数据。

### 2.3 通信模型

所有设备间通信走 HTTP，Bearer token 认证：

```
POST /api/mesh/remote-exec
Authorization: Bearer kaiyu-lab-2025
{ "action": "Bash", "params": { "command": "vivado ..." } }
```

```
GET /api/mesh/fingerprints
Authorization: Bearer kaiyu-lab-2025
→ 返回所有同步文件的 SHA-256 哈希 + mtime
```

```
GET /api/mesh/file?path=agents/Nebula/memory.md
Authorization: Bearer kaiyu-lab-2025
→ 返回文件内容
```

### 2.4 同步协议（Phase 2）

1. 设备 A 请求设备 B 的文件指纹表（路径 → SHA-256 + mtime）
2. 对比本地指纹，找出差异文件
3. 下载缺失/过期的文件，上传新增/更新的文件
4. 冲突：mtime 更新的覆盖旧的（last-write-wins）
5. 被覆盖版本存入 `~/.nebflow/mesh/history/` 保留 7 天

**同步范围：**

| 类别 | 路径 | 说明 |
|------|------|------|
| 用户记忆 | `NEBFLOW.md` | 全局用户记忆 |
| Agent 记忆 | `agents/{name}/memory.md` | 每个 agent 的记忆 |
| 文件夹记忆 | `folders/{id}.memory.md` | 项目级记忆 |
| 记忆详情 | `memory/{hash}.md` | 记忆详情 |
| 技能 | `skills/{name}/skill.md` | 技能定义 |

**不同步的：** Session 数据、认证 token、MCP 配置、PID 文件、运行时状态

## 3. 数据模型

### 3.1 DeviceIdentity

```scala
case class DeviceIdentity(
  deviceId: String,       // UUID，设备唯一标识（首次启动生成）
  deviceName: String,     // "MacBook-Pro (macOS)"
  platform: String,       // "macOS" / "Windows" / "Linux"
  groupId: Option[String] // 共享 token（None = 未配对）
)
```

存储：`~/.nebflow/device.json`

### 3.2 PeerInfo

```scala
case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,        // "192.168.1.100:8080"
  lastSeen: Long          // 最后在线时间戳
)
```

### 3.3 MeshConfig

```scala
case class MeshConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 300
)
```

不再需要 `cloudBaseUrl` 和 `relayPollIntervalSec`。

### 3.4 FileFingerprint

```scala
case class FileFingerprint(mtime: Long, size: Long, hash: String)
```

`hash` = SHA-256 前 12 hex 字符。

### 3.5 SyncDiff

```scala
case class SyncDiff(needUpload: List[String], needDownload: List[String], unchanged: List[String])
```

## 4. API 端点

### Mesh 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mesh/status` | 本机配对状态 + peer 列表 |
| POST | `/api/mesh/pair` | 设置 token 并开始广播发现 `{ "token": "..." }` |
| POST | `/api/mesh/handshake` | 被发现设备调用，验证 token 并添加 peer |
| POST | `/api/mesh/leave` | 清除 token，退出 mesh |
| GET | `/api/mesh/health` | 健康检查（供其他设备探测可达性） |

### 同步（Phase 2）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mesh/fingerprints` | 返回本机文件指纹表 |
| GET | `/api/mesh/file` | 下载指定文件 `?path=xxx` |
| PUT | `/api/mesh/file` | 上传文件（body = 内容） |
| POST | `/api/mesh/sync` | 触发完整同步 |

### 远程工具执行

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mesh/remote-exec` | 在本机执行工具（Bearer token 认证） |

## 5. MeshTool（agent 工具）

Agent 通过 MeshTool 操作远程设备，接口不变：

```
action: "list_peers"                         → 列出设备
action: "Bash", device: "windows-pc", ...    → 远程执行
action: "Read" / "Write" / "Edit" / "Glob" / "Grep"  → 远程文件操作
```

执行流程：
1. `resolvePeer` — 按 deviceName 在 peer 列表中匹配
2. `checkReachable` — GET `{address}/api/mesh/health`
3. `callRemote` — POST `{address}/api/mesh/remote-exec`

## 6. 前端 UI

**未配对时：**
- 一个输入框：输入 Group Token
- 一个按钮：Join
- 说明文字："在所有设备上输入相同的 token 即可自动配对"

**已配对时：**
- 当前 token 显示（脱敏）
- 已发现的设备列表（名称、平台、在线状态）
- 上次同步时间
- Sync Now 按钮（Phase 2）
- Leave 按钮

## 7. 实施计划

### Phase 1 — 核心配对 + 远程执行（当前）

**删除：**

| 内容 | 行数 |
|------|------|
| `MeshService` 中 `callCloud` / `callCloudAuth` / `getCloudUrl` | ~60 |
| `MeshService` 中 `createGroup` / `joinGroup`（邀请码配对） | ~70 |
| `MeshService` 中 relay 相关（`pollRelay` / `sendRelay` / `startRelayLoop`） | ~60 |
| `MeshService` 中云同步（`uploadFile` / `downloadFile` / `fetchRemoteFingerprints`） | ~90 |
| `MeshModel` 中 `RelayMessage` / `RelayPayload` / `SessionSummary` | ~80 |
| `MeshModel` 中 `MeshConfig.cloudBaseUrl` / `relayPollIntervalSec` | ~5 |
| `RestApiRoutes` 中 create-group / join-group / relay / config-cloud 端点 | ~40 |
| `GatewayMain` 中 relay loop + `handleRelayMessage` | ~30 |
| `mesh.js` 中邀请码 UI（create-group / join-group / pairing-code / cloud-url） | ~80 |
| `cloud/functions/nebflow-mesh/index.js` | ~338 |

**新增：**

| 内容 | 行数 |
|------|------|
| `UdpDiscovery.scala` — UDP 广播发现 + 监听 | ~150 |
| `MeshService` 中 token 配对（`pair`）+ handshake + 直连 peer 管理 | ~100 |
| `RestApiRoutes` 中 pair / handshake 端点 | ~30 |
| `mesh.js` 中 token 输入 UI | ~40 |

**修改：**

| 内容 | 说明 |
|------|------|
| `MeshService` | 配对改为 token-based，peer 管理改为本地发现 |
| `MeshConfig` | 删除 `cloudBaseUrl`、`relayPollIntervalSec` |
| `MeshModel` | 删除 relay 相关类型 |
| `GatewayMain` | 删除 relay loop，添加 UDP 发现启动 |
| `mesh.js` | 简化为 token 输入 |

### Phase 2 — 文件同步

- 文件指纹计算（复用现有 `computeLocalFingerprints`）
- 直连同步：请求 peer 的 fingerprints → 对比 → 直连下载/上传
- mtime 冲突解决 + 历史版本保留
- 后台定时同步

### Phase 3 — 跨网络发现

- 轻量云函数（~80 行）：register + lookup
- Tailscale 虚拟 LAN（100.x.x.x）优先
- NAT 穿透（可选 STUN relay）

## 8. 关键决策记录

| 决策 | 理由 |
|------|------|
| Token 明文做 Bearer 认证 | 威胁模型是防止同网段随机用户连接，SHA-256(token) 做广播发现已避免明文暴露 |
| mtime wins 冲突解决 | 同步范围是记忆文件，冲突概率极低；被覆盖版本保留 7 天可恢复 |
| LAN UDP 广播优先于云发现 | 零延迟、零成本、零依赖；跨网才需要云 |
| 不用邀请码 | 用户只需记住一个 token，不需要两步操作 |
| Tailscale 做 NAT 穿透 | 已在用，零配置，Phase 3 再考虑通用 NAT 穿透 |
