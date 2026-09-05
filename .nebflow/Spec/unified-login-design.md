> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow Desktop 统一登录入口方案

> **版本**: Nebflow v1.4.1-beta.39
> **日期**: 2026-08-13
> **状态**: 方案待确认

---

## 一、现状分析

### 当前有三套互不相通的认证系统

| 系统 | 入口 | 认证方式 | 能做用户登录 | 能做设备配对 | 状态 |
|------|------|---------|-------------|-------------|------|
| Nebflow Desktop | `localhost:8080` | 本地随机令牌 `auth.json` | 否 | 否 | 仅网关鉴权 |
| nebflow.space | `nebflow.space/connect` | 邮箱/密码 | 是 | 间接（靠重定向传参） | 代码保留（后期做邮箱注册） |
| NebLink Server | `neblink.nebflow.space` | GitHub OAuth + JWT | 是 | 是（Device Flow） | **唯一活跃认证方式** |

### 当前头像点击行为

```
点击头像
  ├─ 已登录 → 弹出"设备信息"面板（设备名/ID/平台/在线设备列表/断开连接）  ← 废弃
  └─ 未登录 → Device Flow 登录弹窗  ← 已修好（之前指向 nebflow.space/connect 死路）
```

**问题清单**：

1. **`showProfilePanel()` 显示的是设备信息**（设备名、设备 ID、平台、在线设备列表），不是用户个人主页（`activityBar.js:302-351`）
2. **"断开连接"按钮调用 `POST /api/neblink/config`，该端点不存在**（只有 `PATCH`，且仅处理 `syncIntervalSec`）——退出功能完全是坏的
3. **登录状态判断有误**：`renderAvatar()` 用 `st.device?.deviceId` 判断已登录，但 `DeviceIdentity` 在首次启动时自动创建、deviceId 永远存在，导致"已登录"判断恒为 true
4. **`fetchNeblinkStatus()` 不读取 `githubLogin` 字段**（`neblink.js:58-65`），虽然后端已返回（`RestApiRoutes.scala:381`）

### 凭证文件结构

| 文件 | 用途 | 生命周期 |
|------|------|---------|
| `~/.nebflow/auth.json` | 本地网关访问令牌（非用户身份） | 永久，程序生成 |
| `~/.nebflow/device.json` | 本地设备身份（deviceId/name/platform/avatarUrl/githubLogin） | 永久，程序生成 |
| `~/.nebflow/neblink/device.json` | **用户登录凭证 + 设备凭证**（serverUrl/networkId/deviceId/deviceToken） | 登录时写入，退出时删除 |
| `~/.nebflow/neblink/config.json` | NebLink 配置（enabled/server/deviceToken） | 登录时更新 |

**核心洞察**：`~/.nebflow/neblink/device.json`（DeviceCredential）的存在 = 用户已登录。这是唯一的登录状态判据。

---

## 二、统一认证体系架构

### 设计原则

**一个凭证、一个入口、一条链路**：
- **一个凭证**：`~/.nebflow/neblink/device.json` 同时是用户身份凭证和设备配对凭证——登录即配对
- **一个入口**：头像按钮（未登录时显示 Nebflow logo）
- **一条链路**：Device Flow → GitHub OAuth → 持久化凭证 → 自动连接 NebLink

废弃的链路：
- `POST /api/neblink/pair`（接收 nebflow.space/connect 重定向参数的旧端点）→ **删除**
- `checkPairingRedirect()` → 从初始化中移除调用，函数代码**保留**（后期邮箱注册功能会复用框架）

> **邮箱密码登录代码保留说明**：`nebflow.space/connect` 的邮箱验证登录 UI 和 `checkPairingRedirect()` 的框架代码保留，因为后期会做邮箱注册功能。当前阶段 GitHub OAuth Device Flow 是唯一活跃的认证方式，但邮箱路径不删除、不破坏，只从初始化流程中摘除。

![统一认证架构](assets/login-arch.svg)

### 登录完整链路（Device Flow）

```
1. 用户点头像 → Login Modal 启动
2. 前端 → POST /api/neblink/device-flow/start
3. Nebflow 后端 → 代理到 neblink-server /api/device/code
4. neblink-server → 返回 {deviceCode, userCode, verificationUri}
5. 前端 → 打开 verificationUri（浏览器中显示 userCode + "GitHub 登录授权"按钮）
6. 用户点击 → neblink-server 跳转 GitHub OAuth → 用户授权 → GitHub 回调
7. neblink-server → upsert 用户信息（含 avatar_url）→ approve device code
8. 前端轮询 → POST /api/neblink/device-flow/poll
9. Nebflow 后端 → 代理到 neblink-server /api/device/token
10. neblink-server → 返回 {deviceToken, networkId, avatarUrl, githubUsername}
11. Nebflow 后端 → 持久化到 device.json + config.json → 热重载 NebLink 客户端 → 保存 avatarUrl + githubLogin 到 DeviceIdentity
12. 前端 → 状态刷新 → 头像切换为 GitHub 头像 → Modal 显示成功 → 1.5s 后自动关闭
```

> **依赖**：步骤 10 中 neblink-server 需在 `/api/device/token` 响应中包含 `avatarUrl` 和 `githubUsername` 字段。Nebflow 后端已解析这两个字段（`RestApiRoutes.scala:624-626`），但 neblink-server 是否返回取决于其版本。若未返回，头像 URL 降级为 logo，githubLogin 降级为 deviceName。

---

## 三、头像点击交互设计（状态机）

![头像点击状态机](assets/login-state.svg)

### 状态定义

| 状态 | 判断条件 | 头像外观 | 点击行为 |
|------|---------|---------|---------|
| **未登录** | DeviceCredential 不存在（`loggedIn: false`） | Nebflow logo（灰色/默认） | 打开 Login Modal（Device Flow） |
| **登录中** | `pairing: true`（Device Flow 轮询中） | logo + pairing 动画 | 忽略点击 |
| **已登录** | DeviceCredential 存在且 `enabled: true` | GitHub 头像 | **跳转官网个人主页** |

### 已登录点击行为

**不做本地弹窗**。个人主页是官网（nebflow.space）的职责——官网需要新建一个个人主页页面。

已登录点击头像 → `window.open('https://nebflow.space/profile', '_blank')`（具体 URL 待官网确认）。

退出登录入口放在**设置面板 NebLink 区域**（见第五节）。

> **设计理由**：头像点击只做两件事——登录 或 去官网个人主页。设备信息、退出登录在设置面板。避免本地弹窗承载过多职责。

### Login Modal 状态流

| 子状态 | UI | 退出条件 |
|--------|-----|---------|
| `starting` | "正在启动设备授权…" | API 返回 → `waiting` |
| `waiting` | userCode + "打开授权页面"按钮 + "等待授权完成…" | poll 成功 → `success` / poll 失败 → `error` / 超时 → `error` |
| `success` | "✓ 连接成功，设备已加入网络" | 1.5s 后自动关闭 + 状态刷新 |
| `error` | 错误消息 + "重试"按钮 | 用户点击重试 → `starting` / 用户关闭 |

> 关闭 Modal 时若仍在 `waiting`，调用 `cancelDeviceFlow()` 停止轮询。

---

## 四、头像外观变化

```
未登录                    登录中                    已登录
┌─────┐                 ┌─────┐                 ┌─────┐
│     │                 │     │                 │ ◉◉◉ │
│ logo│  ──点击──►      │ logo│  ──完成──►      │ GH  │
│     │   Modal弹出     │ +pulse              │ 头像 │
└─────┘                 └─────┘                 └─────┘
Nebflow logo            Nebflow logo            GitHub avatar
title: "登录"          title: "Pairing…"       title: "个人主页"
```

---

## 五、设置面板 NebLink 区域设计

### 未登录状态

```
┌──────────────────────────────────┐
│  NebLink                          │
│                                   │
│  ┌────┐                           │
│  │logo│  未登录，NebLink 不可用   │
│  └────┘                           │
│                                   │
│  点击左上角头像登录                │
│                                   │
└──────────────────────────────────┘
```

- 显示 Nebflow logo（小尺寸）+ "未登录，NebLink 不可用" 文字
- 提示用户通过头像登录
- **没有登录按钮**——头像点击是唯一登录入口

### 已登录状态

```
┌──────────────────────────────────┐
│  NebLink                          │
│                                   │
│  ┌──────────────────────────────┐ │
│  │ ◉ Kaiyu-MBP    [本机]        │ │
│  │ ◉ Windows-PC   [更新]        │ │
│  │ ◉ Linux-Server [更新]        │ │
│  └──────────────────────────────┘ │
│                                   │
│  NebLink Server: neblink.nebflow… │
│                                   │
│  [      退出登录      ]           │
│                                   │
└──────────────────────────────────┘
```

- 设备列表（本机 + Peers）——保持现状
- 设备 Dropbox / 描述编辑 / 跨设备更新——保持现状
- NebLink Server 地址——保持现状
- **新增**：退出登录按钮（底部，红色边框）

### 退出登录交互

点击"退出登录" → 确认提示（可选）→ `POST /api/neblink/logout` → 刷新设置面板 + 头像恢复 logo。

---

## 六、个人主页 vs 设置面板的职责边界

| 功能 | 头像点击 | 设置面板 NebLink 区域 | 官网个人主页 |
|------|---------|---------------------|------------|
| 登录（Device Flow） | ✅ 唯一入口 | ❌ | ❌ |
| 退出登录 | ❌ | ✅ | （未来） |
| 个人资料/头像管理 | ❌ | ❌ | ✅（未来新建） |
| NebLink 在线状态 | ❌ | ✅ 详细 | ❌ |
| 设备列表 + Peers | ❌ | ✅ 完整列表 | ❌ |
| 设备 Dropbox / 描述编辑 | ❌ | ✅ | ❌ |
| NebLink Server 地址 | ❌ | ✅ | ❌ |
| 跨设备更新（remoteUpdate） | ❌ | ✅ | ❌ |
| 跳转官网 | ✅ 已登录点击 | ❌ | — |

**三层职责**：
- **头像 = 认证入口**：登录 / 去官网个人主页
- **设置面板 = 设备层**：设备管理、网络配置、退出登录
- **官网个人主页 = 身份层**：个人资料、头像管理（未来）

---

## 七、登录后的自动行为

| 行为 | 触发点 | 当前状态 | 需要改动 |
|------|--------|---------|---------|
| NebLink 客户端自动启动 | Device Flow poll 成功 | ✅ 已实现（`RestApiRoutes.scala:643-644` 热重载） | 无 |
| 触发重新发现 Peers | poll 成功后 | ✅ 已实现（`RestApiRoutes.scala:646`） | 无 |
| 头像切换为 GitHub 头像 | poll 成功 → 状态刷新 | ✅ 已实现（avatarUrl 保存到 DeviceIdentity → status 返回 → 前端渲染） | 无 |
| 用户名同步 | poll 成功 → 状态刷新 | ⚠️ 后端已返回 `githubLogin`，但前端 `fetchNeblinkStatus` 未读取 | 前端读取 `githubLogin` |
| NebLink 服务自动启用 | poll 成功 | ✅ 已实现（config.enabled = true） | 无 |

**唯一前端缺口**：`neblink.js` 的 `fetchNeblinkStatus()` 需要增加读取 `loggedIn` 和 `githubLogin`。

---

## 八、需要改动的前端文件清单

### 1. `js/activityBar.js`

| 函数 | 改动 | 说明 |
|------|------|------|
| `bindAvatar()` | 修改判断 + 已登录行为 | 未登录 → `showLoginModal()`；已登录 → `window.open(官网个人主页)` |
| `showProfilePanel()` | **删除** | 不再做本地弹窗 |
| `renderAvatar()` | 修改状态判断 | `st.loggedIn` 替代 `st.device?.deviceId`；已登录 title 改为"个人主页" |
| 注释头 | 更新 | 反映新的已登录行为（跳转官网，不再弹设备面板） |

**`bindAvatar()` 改动后逻辑**：
```javascript
avatar.addEventListener('click', () => {
  const st = getNeblinkState();
  if (st.pairing) return;          // pairing → 忽略
  if (st.loggedIn) {
    // 已登录 → 跳转官网个人主页
    window.open('https://nebflow.space/profile', '_blank');
  } else {
    // 未登录 → Device Flow 登录弹窗
    showLoginModal();
  }
});
```

### 2. `js/neblink.js`

| 函数/区域 | 改动 | 说明 |
|-----------|------|------|
| `fetchNeblinkStatus()` | 新增字段读取 | 在 `neblinkState.device` 中增加 `githubLogin`；在 state 顶层增加 `loggedIn` |
| `neblinkState` | 新增字段 | 增加 `loggedIn: false` 初始值 |
| `checkPairingRedirect()` | 保留代码，加注释 | 函数保留（未来邮箱注册功能复用），但从 `main.js` 初始化中移除调用 |
| `neblinkSettingsHTML()` 未登录分支 | 改为提示 | 显示 Nebflow logo + "未登录，NebLink 不可用" + "点击左上角头像登录"提示，无登录按钮 |
| `neblinkSettingsHTML()` 已登录分支 | 新增退出按钮 | 在设备列表底部增加"退出登录"按钮 |
| `bindNeblinkEvents()` | 新增退出绑定 | 绑定退出按钮 → `POST /api/neblink/logout` → 刷新状态 |

### 3. `js/sidebar.js`

| 区域 | 改动 | 说明 |
|------|------|------|
| NebLink settings 渲染 | 配合 neblink.js 改动 | 未登录时显示提示；已登录时设备列表 + 退出按钮 |

### 4. `js/main.js`

| 区域 | 改动 | 说明 |
|------|------|------|
| 初始化序列 | 移除 `checkPairingRedirect()` 调用 | 函数保留在 neblink.js 中，但不再调用 |

### 5. `css/nav.css`

| 区域 | 改动 | 说明 |
|------|------|------|
| `.nebflow-profile-panel` 及相关 | **删除** | 不再使用 Profile Modal |
| `.profile-*` 样式 | 删除 | `.profile-header`, `.profile-body`, `.profile-item`, `.profile-peers`, `.peer-item`, `.peer-empty`, `.profile-logout` |

---

## 九、需要改动的后端 API 清单

### 1. 修改 `GET /api/neblink/status`（`RestApiRoutes.scala:368-400`）

**改动**：响应中增加 `loggedIn` 字段。

```scala
case req @ GET -> Root / "neblink" / "status" =>
  withNeblink(req) { ms =>
    for
      id <- ms.identity
      peersList <- ms.peers
      cred <- DeviceCredential.load
      cfg <- ms.neblinkConfig
      loggedIn = cred.isDefined && cfg.enabled
      yield Ok(Json.obj(
        "loggedIn" -> loggedIn.asJson,
        "device" -> Json.obj(/* 不变 */),
        "peers" -> /* 不变 */
      ))
  }
```

已登录 = `DeviceCredential` 存在 AND `config.enabled` 为 true。

### 2. 新增 `POST /api/neblink/logout`（新端点）

**职责**：清除用户登录状态 + 停止 NebLink 连接 + 清除用户信息。

```scala
case req @ POST -> Root / "neblink" / "logout" =>
  withNeblink(req) { ms =>
    for
      // 1. 删除设备凭证文件 (~/.nebflow/neblink/device.json)
      _ <- DeviceCredential.clear
      // 2. 更新配置：禁用 NebLink，但保留 neblinkServer 地址（自建服务器用户方便下次登录）
      current <- NeblinkConfig.load
      updated = current.copy(enabled = false)
      _ <- NeblinkConfig.save(updated)
      // 3. 停止 NebLink 客户端
      _ <- neblinkDiscovery.fold(IO.unit)(d => d.setClient(None))
      // 4. 清除 DeviceIdentity 上的用户信息（avatarUrl, githubLogin）
      _ <- ms.updateDeviceInfo(avatarUrl = Some(""), githubLogin = Some(""))
      // 5. 清空 peers 列表
      _ <- ms.clearPeers  // 需在 NeblinkService 中新增
      r <- Ok(Json.obj("ok" -> true.asJson))
    yield r
  }
```

> **保留 neblinkServer 地址**：`enabled = false` 但 `neblinkServer = None` → **改为只设 `enabled = false`，保留 neblinkServer 不变**。这样自建 NebLink Server 用户下次登录时不用重新配置。

### 3. 删除 `POST /api/neblink/pair`（`RestApiRoutes.scala:465-494`）

删除该端点。它接收 nebflow.space/connect 重定向的 `server/networkId/secret` 参数，已无人调用。

> 前端 `checkPairingRedirect()` 代码保留（标注注释 `// 暂时禁用，邮箱注册功能完成后重新启用`），但从 `main.js` 初始化中移除调用。

### 4. NeblinkService 新增方法

`NeblinkService.scala`：
```scala
/** Clear all peers (used on logout). Triggers peerListChanged broadcast. */
def clearPeers: IO[Unit] =
  peersRef.set(Map.empty) *> broadcastPeerListChanged
```

### 5. 依赖：neblink-server 确认

neblink-server 的 `/api/device/token` 响应需包含 `avatarUrl` 和 `githubUsername` 字段。需确认 neblink-server 当前版本是否返回它们（若不返回，头像降级为 logo）。

---

## 十、验收标准

### 10.1 冒烟测试（硬性条件）

```bash
# 1. 编译启动
sbt compile && sbt run

# 2. 验证服务启动 + 关键端点
sleep 5
TOKEN=$(cat ~/.nebflow/auth.json | tr -d '"')
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/neblink/status | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print('loggedIn:', d.get('loggedIn'))"

# 3. 验证 logout 端点存在（未登录时调用不应崩溃）
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/neblink/logout | python3 -m json.tool

# 4. 验证旧 pair 端点已删除
curl -s -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' \
  http://localhost:8080/api/neblink/pair
# 预期：404

# 5. 验证静态资源
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/js/activityBar.js   # → 200
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/js/neblink.js        # → 200
```

**通过标准**：
- `sbt compile` 无错误
- 服务启动后 `curl /api/neblink/status` 返回 JSON，包含 `loggedIn` 字段（未登录时为 `false`）
- `POST /api/neblink/logout` 返回 `{ok: true}`
- `POST /api/neblink/pair` 返回 404
- 静态资源返回 200

### 10.2 前端渲染验证（Playwright）

```javascript
// test: 未登录头像显示 logo，点击弹出 Login Modal
await page.goto('http://localhost:8080/?token=' + TOKEN);
await page.waitForLoadState('networkidle');
await page.screenshot({ path: '/tmp/avatar-logged-out.png' });

// 头像存在
await expect(page.locator('#activity-avatar')).toBeVisible();
// 未登录 → 无 .paired class
await expect(page.locator('#activity-avatar')).not.toHaveClass(/paired/);

// 点击 → Login Modal 出现
await page.click('#activity-avatar');
await expect(page.locator('#nebflow-login-modal')).toBeVisible();
await expect(page.locator('.login-user-code')).toBeVisible();
await page.screenshot({ path: '/tmp/login-modal.png' });
```

```javascript
// test: 设置面板 NebLink 区域未登录状态
await page.click('#settings-btn'); // 打开设置
// NebLink 区域显示"未登录，NebLink 不可用"
const neblinkText = await page.locator('.neblink-login-section').textContent();
expect(neblinkText).toContain('未登录');
// 无登录按钮（旧的 #neblink-device-flow-btn 不存在）
await expect(page.locator('#neblink-device-flow-btn')).toHaveCount(0);
```

**通过标准**：
- 未登录时头像显示 logo（截图确认）
- 点击头像弹出 Login Modal，显示 userCode
- 设置面板 NebLink 区域显示"未登录"提示，无登录按钮
- 无 JS 控制台错误

### 10.3 登录流程 E2E

```bash
# 前提：neblink-server 运行中，GitHub OAuth 配置有效

# 1. 启动 device flow
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/neblink/device-flow/start | python3 -m json.tool
# 预期：{deviceCode, userCode, verificationUri}

# 2. 浏览器完成 GitHub 授权（手动或自动化）

# 3. 轮询
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceCode\":\"$DEVICE_CODE\"}" \
  http://localhost:8080/api/neblink/device-flow/poll | python3 -m json.tool
# 预期：{ok: true, networkId: "..."}

# 4. 验证登录后状态
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/neblink/status | python3 -m json.tool
# 预期：loggedIn: true, device.avatarUrl 非空, device.githubLogin 非空

# 5. 验证凭证持久化
cat ~/.nebflow/neblink/device.json | python3 -m json.tool
```

**通过标准**：
- Device Flow 完整链路走通
- `loggedIn` 从 `false` 变为 `true`
- `device.json` 持久化成功
- 头像切换为 GitHub 头像（Playwright 截图确认）

### 10.4 已登录头像点击验证（Playwright）

```javascript
// 前提：已登录（device.json 存在）
await page.goto('http://localhost:8080/?token=' + TOKEN);
await page.waitForLoadState('networkidle');

// 头像显示 GitHub 头像（非 logo）
await expect(page.locator('.activity-avatar-photo')).toBeVisible();
await expect(page.locator('.activity-avatar-logo')).not.toBeVisible();

// 头像有 .paired class
await expect(page.locator('#activity-avatar')).toHaveClass(/paired/);

// 点击 → 打开官网（验证 window.open 被调用）
const popupPromise = page.waitForEvent('popup');
await page.click('#activity-avatar');
const popup = await popupPromise;
expect(popup.url()).toContain('nebflow.space');
```

### 10.5 设置面板退出登录验证

```bash
# 前提：已登录（device.json 存在，neblinkServer 已配置）

# 1. 调用 logout
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/neblink/logout | python3 -m json.tool
# 预期：{ok: true}

# 2. 验证凭证已删除
test ! -f ~/.nebflow/neblink/device.json && echo "PASS: device.json removed"

# 3. 验证状态已更新
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/neblink/status | python3 -m json.tool
# 预期：loggedIn: false

# 4. 验证 neblinkServer 地址保留
cat ~/.nebflow/neblink/config.json | python3 -c "import sys,json; d=json.load(sys.stdin); print('neblinkServer:', d.get('neblinkServer'))"
# 预期：neblinkServer 仍然存在（仅 enabled: false）

# 5. 验证 DeviceIdentity 用户信息已清除
cat ~/.nebflow/device.json | python3 -c "import sys,json; d=json.load(sys.stdin); print('avatarUrl:', d.get('avatarUrl')); print('githubLogin:', d.get('githubLogin'))"
# 预期：avatarUrl: null, githubLogin: null
```

```javascript
// Playwright: 设置面板退出登录
await page.click('#settings-btn');
// 找到退出按钮
const logoutBtn = page.locator('.neblink-logout-btn');
await expect(logoutBtn).toBeVisible();
await logoutBtn.click();
// 等待状态刷新
await page.waitForTimeout(2000);
// 头像恢复 logo
await expect(page.locator('.activity-avatar-logo')).toBeVisible();
await expect(page.locator('.activity-avatar-photo')).not.toBeVisible();
// 设置面板 NebLink 区域变为"未登录"
const neblinkText = await page.locator('.neblink-login-section').textContent();
expect(neblinkText).toContain('未登录');
await page.screenshot({ path: '/tmp/after-logout.png' });
```

**通过标准**：
- `POST /api/neblink/logout` 返回 `{ok: true}`
- `device.json` 被删除
- `loggedIn` 从 `true` 变为 `false`
- `neblinkServer` 地址保留，仅 `enabled: false`
- DeviceIdentity 的 `avatarUrl` 和 `githubLogin` 被清空
- 头像恢复为 logo
- 设置面板 NebLink 区域变为"未登录"状态

### 10.6 旧路径清理验证

```bash
# checkPairingRedirect 不在初始化流程中调用
grep "checkPairingRedirect" src/main/resources/web/js/main.js
# 预期：无匹配（函数定义在 neblink.js 中保留，但不从 main.js 调用）

# 函数代码保留
grep "checkPairingRedirect" src/main/resources/web/js/neblink.js
# 预期：函数定义存在（带注释标注暂时禁用）
```

### 10.7 暗色/亮色模式验证（Playwright）

```javascript
await page.emulateMedia({ colorScheme: 'dark' });
await page.click('#settings-btn');
await page.screenshot({ path: '/tmp/settings-neblink-dark.png' });

await page.emulateMedia({ colorScheme: 'light' });
await page.screenshot({ path: '/tmp/settings-neblink-light.png' });
// 确认文字可读、退出按钮可见
```

---

## 十一、改动文件汇总

### 前端（5 个文件）

| 文件 | 改动量 | 改动类型 |
|------|--------|---------|
| `src/main/resources/web/js/activityBar.js` | 中 | `bindAvatar` 已登录改为跳转官网 + 删除 `showProfilePanel` + 修改状态判断 |
| `src/main/resources/web/js/neblink.js` | 中 | `fetchNeblinkStatus` 增加字段 + 未登录 UI 改提示 + 已登录增加退出按钮 + `checkPairingRedirect` 保留但标注禁用 |
| `src/main/resources/web/js/sidebar.js` | 小 | 配合 NebLink 区域渲染改动 |
| `src/main/resources/web/js/main.js` | 极小 | 移除 `checkPairingRedirect()` 调用 |
| `src/main/resources/web/css/nav.css` | 小 | 删除 `.nebflow-profile-panel` 及 `.profile-*` 样式 |

### 后端（2 个文件 + 1 个 Service 方法）

| 文件 | 改动量 | 改动类型 |
|------|--------|---------|
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | 中 | status 增加 `loggedIn` + 新增 logout 端点 + 删除 pair 端点 |
| `src/main/scala/nebflow/neblink/NeblinkService.scala` | 小 | 新增 `clearPeers` 方法 |

### 外部依赖（2 项确认）

| 依赖 | 动作 |
|------|------|
| neblink-server `/api/device/token` 响应 | 确认是否返回 `avatarUrl` + `githubUsername` |
| nebflow.space 官网 | 未来新建个人主页页面（`/profile`），当前阶段头像跳转先占位 |

---

## 附录：关键代码位置速查

| 功能 | 文件 | 行号 |
|------|------|------|
| 头像点击事件 | `activityBar.js` | 95-109 |
| Login Modal | `activityBar.js` | 192-299 |
| Profile Panel（删除） | `activityBar.js` | 302-351 |
| 头像渲染 | `activityBar.js` | 359-392 |
| NebLink 状态获取 | `neblink.js` | 48-70 |
| 旧 pair 重定向（保留代码，摘除调用） | `neblink.js` | 81-137 |
| Device Flow 启动 | `neblink.js` | 268-276 |
| Device Flow 轮询 | `neblink.js` | 287-337 |
| NebLink 设置面板 HTML | `neblink.js` | 140-244 |
| GET /api/neblink/status | `RestApiRoutes.scala` | 368-400 |
| POST /api/neblink/pair（删除） | `RestApiRoutes.scala` | 465-494 |
| POST /api/neblink/device-flow/start | `RestApiRoutes.scala` | 571-594 |
| POST /api/neblink/device-flow/poll | `RestApiRoutes.scala` | 598-665 |
| DeviceIdentity 模型 | `NeblinkModel.scala` | 15-87 |
| DeviceCredential 持久化 | `DeviceCredentialStore.scala` | 20-57 |
| NeblinkService.updateDeviceInfo | `NeblinkService.scala` | 91-114 |
