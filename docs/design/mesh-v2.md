# Nebflow Mesh v2 — 设计文档

> 状态：设计阶段
> 分支：feature/mesh
> 日期：2025-06-08

## 1. 目标

让多台 Nebflow 实例通过一个共享 token 自动发现彼此、直连通信，无需任何基础设施。

核心场景：用户在 Mac 上写 Verilog 代码，agent 通过 MeshTool 直接在 Windows 上执行 Vivado。

## 2. 设计原则

1. **零基础设施**：LAN 场景下不需要任何云服务或中心节点
2. **即插即用**：用户只需输入一个 token，不需要填写 IP 地址
3. **设备平等**：没有主从关系，每台设备地位相同
4. **数据本地**：每台设备存储完整数据副本，同步只传差异
5. **安全最小化**：token 既是身份标识也是认证密钥

## 3. 架构

```
                    LAN（UDP 广播自动发现）
    ┌──────────┐◄──────────────────────►┌──────────┐
    │  Mac     │                         │ Windows  │
    │  :8082   │◄──── HTTP 直连 ────────►│  :8080   │
    └──────────┘                         └──────────┘
         ▲
         │  token = "kaiyu-lab-2025"
         ▼
    ┌──────────┐
    │  Linux   │
    │  :8083   │
    └──────────┘

    所有设备持有完整数据副本
    同步 = 交换文件哈希 → 传输差异
    冲突 = 最新 mtime wins
```

### 3.1 配对流程

**用户操作（仅需 1 步）：**

1. 在每台设备的 Mesh 面板输入同一个 token（如 `kaiyu-lab-2025`）

**自动流程：**

2. 设备在局域网内 UDP 广播 token 的哈希值（端口 19876）
3. 其他设备收到广播，核对自己的 token 是否匹配
4. 匹配则回应自己的 Nebflow 地址（IP + 端口）
5. 双方通过 HTTP 直连，用 token 做 Bearer 认证
6. 建立信任关系，开始同步和远程执行

### 3.2 Token

- 用户自选字符串，长度 >= 6 字符
- 全网唯一性由用户保证（类似 WiFi 密码）
- 同时作为 group ID 和认证密钥
- 存储：`~/.nebflow/mesh/identity.json` 中的 groupId 字段
- 传输：HTTP Bearer token + UDP 广播用 SHA-256(token) 避免明文暴露

### 3.3 设备发现

**LAN 发现（默认）：**

```
设备 A 每隔 10 秒广播：
  UDP → 255.255.255.255:19876
  Body: { "type": "mesh-discover", "tokenHash": "sha256(token)", "port": 8082 }

设备 B 收到广播：
  1. 计算 sha256(自己的 token)
  2. 匹配 → 回复自己的地址
  3. HTTP POST → http://{sender_ip}:{sender_port}/api/mesh/handshake
     Body: { "token": "kaiyu-lab-2025" }
     Header: Authorization: Bearer kaiyu-lab-2025

设备 A 收到 handshake：
  1. 验证 token 一致
  2. 添加到 peer 列表
  3. 开始同步
```

**跨网络发现（可选，Phase 3）：**

设备通过一个轻量 HTTPS 端点注册和查询：
- `POST /register { tokenHash, address }` — 注册自己的地址
- `POST /lookup { tokenHash }` — 查找同组其他设备的地址

只交换地址，不传输数据。数据仍然直连传输。

### 3.4 通信模型

所有设备间通信走 HTTP，Bearer token 认证：

```
POST /api/mesh/remote-exec
Authorization: Bearer kaiyu-lab-2025
{ "action": "Bash", "params": { "command": "vivado ..." } }
```

```
GET /api/mesh/fingerprints
Authorization: Bearer kaiyu-lab-2025
→ 返回所有同步文件的 SHA-256 哈希
```

```
GET /api/mesh/file?path=agents/Nebula/memory.md
Authorization: Bearer kaiyu-lab-2025
→ 返回文件内容
```

### 3.5 同步协议

1. 设备 A 请求设备 B 的文件指纹表（路径 → SHA-256 + mtime）
2. 对比本地指纹，找出差异文件
3. 下载缺失/过期的文件，上传新增/更新的文件
4. 冲突：mtime 更新的覆盖旧的（最后写入胜出）
5. 更新本地快照

**同步范围：**

| 类别 | 路径 | 说明 |
|------|------|------|
| 用户记忆 | `NEBFLOW.md` | 全局用户记忆 |
| Agent 记忆 | `agents/{name}/memory.md` | 每个 agent 的记忆 |
| 文件夹记忆 | `folders/{id}.memory.md` | 项目级记忆 |
| 记忆详情 | `memory/{hash}.md` | 记忆详情 |
| 技能 | `skills/{name}/skill.md` | 技能定义 |

**不同步的：**

- Session 数据（设备本地，不需要同步）
- 认证 token（每台设备独立）
- MCP 配置（设备本地）
- PID 文件、运行时状态

## 4. 数据模型

### 4.1 DeviceIdentity（简化）

```scala
case class DeviceIdentity(
  deviceId: String,       // UUID，设备唯一标识
  deviceName: String,     // "MacBook-Pro (macOS)"
  platform: String,       // "macOS" / "Windows" / "Linux"
  groupId: Option[String] // 共享 token（None = 未配对）
)
```

### 4.2 PeerInfo

```scala
case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,        // "192.168.1.100:8080"
  lastSeen: Long          // 最后在线时间
)
```

### 4.3 MeshConfig

```scala
case class MeshConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 300
)
```

不再需要 `cloudBaseUrl`。

## 5. API 端点

### Mesh 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mesh/status` | 本机配对状态 + peer 列表 |
| POST | `/api/mesh/pair` | 设置 token 并开始广播 |
| POST | `/api/mesh/handshake` | 被发现设备调用，验证 token |
| POST | `/api/mesh/leave` | 清除 token，退出 mesh |
| GET | `/api/mesh/health` | 健康检查（供其他设备探测） |

### 同步

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mesh/fingerprints` | 返回本机文件指纹表 |
| GET | `/api/mesh/file` | 下载指定文件（?path=xxx） |
| PUT | `/api/mesh/file` | 上传文件（body = 内容） |
| POST | `/api/mesh/sync` | 触发完整同步 |

### 远程工具执行

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mesh/remote-exec` | 在本机执行工具 |

## 6. MeshTool（agent 工具）

Agent 通过 MeshTool 操作远程设备。接口不变，只改发现方式：

```
action: "Bash", device: "windows-pc", command: "vivado ..."
```

执行流程：
1. `resolvePeer` — 按 deviceName 在 peer 列表中匹配
2. `checkReachable` — GET `{address}/api/mesh/health`
3. `callRemote` — POST `{address}/api/mesh/remote-exec`

## 7. 前端 UI

Mesh 面板简化为：

**未配对时：**
- 一个输入框：输入 Group Token
- 一个按钮：Join
- 说明文字："在所有设备上输入相同的 token 即可自动配对"

**已配对时：**
- 当前 token 显示（脱敏）
- 已发现的设备列表（名称、平台、在线状态）
- 上次同步时间
- Sync Now 按钮
- Leave 按钮

## 8. Phase 计划

### Phase 1 — 核心配对 + 远程执行

- 共享 token 配对
- UDP 广播自动发现
- `/api/mesh/handshake` 验证
- MeshTool 远程工具执行
- 前端简化面板

**删除：**
- `cloud/functions/nebflow-mesh/` — 整个云函数
- MeshService 中所有 `callCloud` 方法
- 邀请码生成/验证逻辑
- relay 轮询机制
- Cloud URL 配置项

**工作量：** 删 ~500 行，改 ~300 行，新增 ~150 行（UDP 发现）

### Phase 2 — 文件同步

- 文件指纹计算（SHA-256）
- 增量同步（差异传输）
- mtime 冲突解决
- 后台定时同步

### Phase 3 — 跨网络 + 进阶

- 云发现服务（只交换地址）
- NAT 穿透（可选 relay）
- UDP 自动发现优化
- 多设备拓扑管理
