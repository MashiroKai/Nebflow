# Nebflow Mesh — 设计文档

> 状态：账号系统实施中
> 分支：feature/mesh
> 更新：2026-06-08

## 1. 目标

让多台 Nebflow 实例通过 Nebflow 账号自动发现彼此、直连通信。

核心场景：用户在 Mac 上写 Verilog 代码，agent 通过 MeshTool 直接在 Windows 上执行 Vivado。

设计原则：

1. **账号登录** — 注册 Nebflow 账号，任意设备登录即自动配对
2. **云当电话簿不当中转站** — 腾讯云只做账号、发现和用户数据存储，实时通信走 P2P
3. **设备平等** — 没有主从关系，每台设备地位相同
4. **数据本地** — 每台设备存储完整数据副本，同步只传差异

## 2. 架构

```
 LAN（UDP 广播自动发现）           腾讯云（账号 + 发现）
┌──────────┐◄───────────────────►┌──────────┐     ┌──────────────────┐
│  Mac     │                     │ Windows  │     │  Cloud Function  │
│  :8082   │◄── HTTP 直连 ─────►│  :8080   │     │  - register()    │
└──────────┘                     └──────────┘     │  - login()       │
      ▲                                           │  - discover()    │
      │  userId = "abc123"                        └──────────────────┘
      ▼
┌──────────┐
│  Linux   │   数据流：设备 ←→ 设备（直连）
│  :8083   │   发现：UDP 广播（LAN）或 云查询（跨网）
└──────────┘
```

### 2.1 账号系统

**注册流程：**

1. 用户在 Mesh 面板输入用户名 + 密码
2. Nebflow 调用云函数 `auth/register`
3. 云函数检查用户名唯一性，bcrypt 哈希密码，创建账号
4. 返回 `{ userId, sessionToken }`
5. Nebflow 本地保存 `~/.nebflow/mesh/account.json`

**登录流程：**

1. 用户在 Mesh 面板输入用户名 + 密码
2. Nebflow 调用云函数 `auth/login`
3. 云函数验证密码，生成 sessionToken（有效期 30 天）
4. 返回 `{ userId, sessionToken }`
5. Nebflow 本地保存账号信息，开始 UDP 广播 + 云发现

**认证模型：**

- `userId` 替代原来的 `token` 作为设备分组标识
- `sessionToken` 用于调用云函数（注册/查询设备）
- 设备间直连认证用 `userId`（同账号即信任）
- UDP 广播用 `SHA-256(userId)` 匹配

### 2.2 设备发现

**LAN 发现：**

```
设备 A 每隔 10 秒广播：
  UDP → 255.255.255.255:19876
  Body: { "type": "discover", "userIdHash": "sha256(userId)", "port": 8082 }

设备 B 收到广播：
  1. 计算 sha256(自己的 userId)
  2. 匹配 → 回复 HTTP handshake
  3. POST → http://{sender_ip}:{sender_port}/api/mesh/handshake
     Authorization: Bearer {userId}
     Body: { "deviceId": "...", "deviceName": "...", "platform": "...", "port": ... }
```

**跨网络发现（云函数）：**

```
POST cloud-function { action: "discover/register", userId, sessionToken, address, deviceId, ... }
POST cloud-function { action: "discover/lookup", userId, sessionToken, deviceId }
```

### 2.3 通信模型

所有设备间通信走 HTTP，`userId` 做 Bearer 认证（同账号即信任）：

```
POST /api/mesh/remote-exec
Authorization: Bearer {userId}
{ "action": "Bash", "params": { "command": "vivado ..." } }
```

### 2.4 同步协议

与之前相同：指纹对比 → 差异传输 → mtime wins。

## 3. 数据模型

### 3.1 AccountInfo（新增）

```scala
case class AccountInfo(
  userId: String,
  username: String,
  sessionToken: String,
  loggedInAt: Long
)
```

存储：`~/.nebflow/mesh/account.json`

### 3.2 DeviceIdentity

```scala
case class DeviceIdentity(
  deviceId: String,       // UUID
  deviceName: String,     // "MacBook-Pro (macOS)"
  platform: String,       // "macOS" / "Windows" / "Linux"
)
```

不再有 `groupId` — 分组信息由 `AccountInfo.userId` 提供。

### 3.3 MeshConfig

```scala
case class MeshConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 300,
  cloudUrl: Option[String] = None  // 云函数地址
)
```

## 4. API 端点

### Mesh 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mesh/status` | 账号状态 + peer 列表 |
| POST | `/api/mesh/register` | 注册 Nebflow 账号 |
| POST | `/api/mesh/login` | 登录 Nebflow 账号 |
| POST | `/api/mesh/logout` | 退出登录，停止发现 |
| POST | `/api/mesh/handshake` | 被发现设备调用，验证 userId |
| PATCH | `/api/mesh/config` | 更新配置（cloudUrl 等） |

### 同步 & 远程执行（不变）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mesh/fingerprints` | 返回本机文件指纹表 |
| GET | `/api/mesh/file` | 下载指定文件 |
| PUT | `/api/mesh/file` | 上传文件 |
| POST | `/api/mesh/sync` | 触发完整同步 |
| POST | `/api/mesh/remote-exec` | 在本机执行工具 |

## 5. 云函数 API

```
POST cloud-url
Content-Type: application/json

auth/register: { username, password }                     → { userId, sessionToken }
auth/login:    { username, password }                     → { userId, sessionToken }
discover/register: { userId, sessionToken, deviceId, ... } → { ok }
discover/lookup:   { userId, sessionToken, deviceId }      → { peers: [...] }
```

数据库集合：
- `mesh_users`: { userId, username, passwordHash, createdAt }
- `mesh_discovery`: { userId, deviceId, deviceName, platform, address, expiresAt }

## 6. 前端 UI

**未登录时：**

- 两个 tab：注册 / 登录
- 注册：用户名 + 密码 + 确认密码
- 登录：用户名 + 密码
- 底部：云函数 URL 配置（高级选项）

**已登录时：**

- 用户名显示
- 已发现的设备列表
- Sync Now 按钮
- Logout 按钮

## 7. 关键决策记录

| 决策 | 理由 |
|------|------|
| 用户名+密码（无邮箱验证） | 零外部依赖，面向开发者，后续可扩展 OAuth |
| sessionToken 有效期 30 天 | 减少登录频率，设备长期运行 |
| userId 做设备间 Bearer 认证 | 简单直接，同账号即信任 |
| 密码 bcrypt 存储 | 标准安全实践 |
