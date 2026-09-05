> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# NebLink 好友与消息系统 · 后端架构方案（A2A 一期）

> 状态：**frozen（v1.2，2026-08-18 09:52 用户终审）** ｜ 日期：2026-08-17（v1.2 修订 2026-08-18） ｜ 作者：Explorer（v1.2 修订：design-engineer）
> 用户裁定：方案整体批准；agent 发消息默认档位 **auto**；一期聊天窗载体**仅桌面客户端**。
> **[U3 2026-08-18 09:52 用户裁定]**：NebLink 号规则修订——**"NL号可以自己设置（要进行重复检测，只能是数字和字母，不能有特殊字符），默认就是注册的邮箱号。"** §4 重写（原 `NL-XXXX-XXXX` 固定生成方案作废），§3.1 DDL 注释、§6.1 API 清单、§12 集成点、§13 验收、时序图同步更新。
> 存放说明（2026-08-17 深夜批次②更新）：docs/ 规范（agent-doc-management.md 附录 A）已落地，本方案已迁至 `~/.nebflow/docs/Nebflow/friends-messaging-arch.md`（活文档，无日期前缀），配图已随迁 `assets/friends-*.svg`（dot 源同目录）。

---

## 0 · TL;DR 与核心裁定

一期目标：**人类在环的半自动 A2A**——好友（查号→请求→同意）→ 1v1 文本消息（服务端中转存储）→ agent 可发消息（默认需用户确认）→ 收到的消息由人决定是否转发给自己的 agent。

| # | 问题 | 裁定 |
|---|---|---|
| 1 | 数据模型 | 4 张新表：`friendships`（状态机单表，pending/accepted/declined/blocked）· `conversations`（1v1）· `messages`（**单表不分表**）· `read_cursors`；`users` 扩 2 列 |
| 2 | NebLink 号 | **[U3 2026-08-18 用户裁定]**：默认值 = 注册邮箱（邮箱即初始 NL 号）；用户可自定义——字符集限字母+数字（无特殊字符）、长度 3-32、唯一性实时检测（唯一索引 + available 检查端点 + 409 冲突）；查询简化为 neblink_id 单字段精确匹配（二合一） |
| 3 | 消息路由 | 在线推送**复用既有设备 relay-ws 隧道**（新 `friend_event` 消息类型）+ 离线 store-and-forward + REST 补拉；本地未读数做，对端回执不做；**不做 E2E**（TLS+服务端可见，见 §5.4） |
| 4 | API | 15 个 REST 端点（v1.2 [U3] +2：NL 号自定义与唯一性检测）+ 3 类 WS 推送事件（§6） |
| 5 | agent 发消息 | 新工具 `SendFriendMessage(to, message)`；权限三档 **auto（用户裁定默认）/ ask / off**；双层限速 |
| 6 | 转发给 agent | **纯客户端**行为：消息文本包装后走既有用户输入管线注入本机会话，不经服务端 |
| 7 | Logto 集成 | 好友关系挂**内部 uuid user_id**（非 Logto id 非 NebLink 号）；JWKS 双轨验签落地后好友 API 零改动 |
| 8 | 安全 | 查号 20/min；好友请求出站 pending≤5、10/天、被拒冷却 48h；被拉黑后请求**静默吞**；消息 ≤4000 字符、30/min、必须 accepted |
| 9 | 分期 | 一期：文本/1v1/本地未读/无回执/无E2E/auto默认/转发按钮。二期展望见 §10 |

**最关键的三个架构决策**（详情见对应章节）：

1. **推送复用 relay-ws 隧道而非新建 user WS**（§5.1）——桌面客户端没有 user JWT 只有 device session token，且隧道常驻已解决连接管理；服务端 `push_to_user` 经 sessions 表反查用户设备集。
2. **friendships 单表状态机而非 请求表+关系表 双表**（§3.2）——1v1 关系一行一状态，pending 行即请求，declined/blocked 行复用为冷却与拉黑判定，省一张表和跨表事务。
3. **转发给 agent 是纯客户端行为**（§8）——agent 会话是客户端本地概念；服务端注入会制造「服务器可向 agent 上下文写任意文本」的 prompt injection 攻击面；人在环的本质就是由人选择转发什么。

---

## 1 · 现状盘点（代码事实）

### 1.1 服务端 neblink-server（`~/.nebflow/projects/neblink-server/`，Rust/axum/SQLite，~5300 行）

| 事实 | 位置 | 对本方案的意义 |
|---|---|---|
| axum 0.8 + tokio + rusqlite(bundled) + dashmap + jsonwebtoken(HS256) | Cargo.toml | 无需新依赖即可实现全部功能 |
| SQLite 经 `Mutex<Connection>` 单写 | store.rs:51 | 所有新表操作走既有模式，无并发写问题 |
| users 表 `github_id INTEGER UNIQUE NOT NULL` | store.rs:101-109 | 登录重构将改 nullable；本方案加 `neblink_id`/`logto_id` 列 |
| JWT：`sub`=内部uuid 或 `github:{id}`，access 15min | auth.rs:18-73, store.rs:298-330 | `require_user` 已隔离鉴权，好友 API 直接复用 |
| relay-ws：`GET /api/device/relay-ws`，device session token 鉴权，`RelayRegistry.tunnels: DashMap<device_id, UnboundedSender<ServerToClient>>` | routes.rs:927-941, relay.rs:62-67 | **推送通道现成**：新增 `ServerToClient::FriendEvent` 变体即可 |
| sessions 表含 `user_id` 列（设备→用户映射持久化） | store.rs:124-134 | `push_to_user(user_id)` 的数据源：查设备集→逐个 tunnels 发送 |
| ServerToClient 已是 tag=type 的 JSON 协议（relay_request/disconnect） | relay.rs:17-29 | 客户端按 `type` 分发，加 `friend_event` 是无破坏扩展 |
| 部署：Vultr 单实例 Docker+Caddy，将迁腾讯云 | NEBLINK_HANDOVER.md | 单实例假设成立，内存限速器可行 |
| Hub 模块 precedent：routes.rs 已 1227 行 | routes.rs | 好友/消息放**新模块 `src/friends.rs`**，避免继续膨胀 |

### 1.2 客户端（Scala 主仓库）

| 事实 | 位置 | 对本方案的意义 |
|---|---|---|
| NeblinkRelayTunnel：常驻 WS + 重连循环 + 心跳，按 `type` 分发（relay_request/ping/pong） | neblink/NeblinkRelayTunnel.scala:239-266 | 加 `"friend_event"` case → 回调 FriendService |
| 客户端持 device credential（长期）↔ session token（90s 刷新），无 user JWT | neblink/DeviceCredentialStore.scala, NeblinkClient.scala | REST 调用统一走 Bearer session token，服务端映射 user_id |
| ToolRegistry.registerTool 动态注册工具 | core/tools/registry.scala:68 | SendFriendMessage 工具注册点 |
| `AgentCommand.ExternalEvent` 注入机制（bg-task 先例） | core/tools/RemoteExecutor.scala:316-346 | 转发机制**不**用它（走用户输入管线，见 §8） |
| Gateway 本地代理模式：`/api/neblink/*` → neblink-server | gateway/RestApiRoutes.scala:577-604, 2275 | 好友 API 同模式加 `/api/friends/*` 代理，前端零跨域 |
| 客户端已有 RateLimiter（滑动窗口） | gateway/ratelimit.scala | 服务端限速器参考此实现模式 |
| WS push 先例：relay tunnel 的 Notify 动作 → 桌面通知 | NeblinkRelayTunnel.scala:152 | 好友请求/新消息通知复用通知中心 |

### 1.3 登录重构（并行项，调研已定 Logto）

调研报告 `/tmp/auth-redesign-survey.md`：Logto 自托管 + JWKS RS256 替换共享 HS256；`users.github_id` 改 nullable；`sub` 映射改 Logto user id。**本方案与该重构正交但接口对齐**（§9）。

---

## 2 · 总体架构

![总体架构](assets/friends-arch.svg)

要点：

- 消息与好友关系全部落 neblink-server SQLite（store-and-forward，服务端中转）。
- 在线推送走**既有 relay-ws 隧道**（红色边）：服务端 `push_to_user(user_id)` → sessions 表查该用户全部在线设备 → 逐个经 `RelayRegistry.tunnels` 发 `friend_event`。
- 桌面客户端新增 FriendService（收发/补拉/未读）+ SendFriendMessage 工具 + 聊天窗。
- 转发给 agent（红色虚线）是 B 客户端内部行为，不经服务端。
- Logto 只影响 `require_user` 内部验签逻辑（双轨过渡），好友/消息 API 表面不变。

---

## 3 · 数据模型

![ER 图](assets/friends-er.svg)

### 3.1 DDL（进入 store.rs `execute_batch`，与既有建表并列）

```sql
-- users 扩展（ALTER 迁移，照 github_login 先例 store.rs:170）
ALTER TABLE users ADD COLUMN neblink_id TEXT;   -- UNIQUE 索引单独建；[U3] 默认值=注册邮箱（get-or-create 用户落库时若 email 非空则填入；无 email 的存量/GitHub 账号留 NULL，待用户自定义后获得 NL 号）
ALTER TABLE users ADD COLUMN logto_id   TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_neblink ON users(neblink_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_logto   ON users(logto_id);
-- email 查号需要（存量 email 允许 NULL，查询时排除；[U3] 后 email 仅作 neblink_id 默认值来源与账号资料，不再是独立查询键）
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

CREATE TABLE IF NOT EXISTS friendships (
    id           TEXT PRIMARY KEY,           -- uuid
    user_lo      TEXT NOT NULL,              -- 规范序：min(双方uuid)
    user_hi      TEXT NOT NULL,              -- 规范序：max(双方uuid)
    requester_id TEXT NOT NULL,              -- pending 的发起方
    status       TEXT NOT NULL,              -- pending|accepted|declined|blocked
    blocked_by   TEXT,                       -- 仅 blocked：拉黑者 uuid
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    declined_at  INTEGER,                    -- 被拒时间，冷却判定
    UNIQUE (user_lo, user_hi)
);
CREATE INDEX IF NOT EXISTS idx_friendships_lo ON friendships(user_lo, status);
CREATE INDEX IF NOT EXISTS idx_friendships_hi ON friendships(user_hi, status);

CREATE TABLE IF NOT EXISTS conversations (
    id              TEXT PRIMARY KEY,        -- uuid
    user_a          TEXT NOT NULL,           -- 规范序
    user_b          TEXT NOT NULL,
    last_message_id INTEGER,                 -- 列表页排序（冗余，免子查询）
    created_at      INTEGER NOT NULL,
    UNIQUE (user_a, user_b)
);

CREATE TABLE IF NOT EXISTS messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,  -- keyset 分页锚点
    conversation_id TEXT NOT NULL,
    sender_id       TEXT NOT NULL,
    kind            TEXT NOT NULL DEFAULT 'text',       -- 二期扩展：file/task/order
    body            TEXT NOT NULL,                      -- ≤4000 字符，服务端明文
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, id);

CREATE TABLE IF NOT EXISTS read_cursors (
    user_id              TEXT NOT NULL,
    conversation_id      TEXT NOT NULL,
    last_read_message_id INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, conversation_id)
);
```

### 3.2 friendships 状态机（单表裁定）

```
        请求                        同意
  ──> pending ──────────────────> accepted ────> (删除行=解除好友)
        │                              │
        │ 拒绝                         │ 任一方拉黑
        v                              v
     declined ──(48h冷却后可再请求)   blocked(blocked_by=X)
        │                              │
        └──(48h内再请求→429)           └──(unblock→删除行，可重新请求)
```

**不建独立 friend_requests 表的理由**：1v1 好友关系天然是「一行一状态」；独立请求表会引入「accept 时删请求行+建关系行」的跨表事务与两表状态一致性问题（declined 后冷却判定还要查历史表）。pending 行本身就是请求（`requester_id` 区分方向）；declined/blocked 行复用为防骚扰判定数据。单实例 SQLite 单写锁下，状态迁移全部是单行 UPDATE/DELETE，原子性免费。

**规范序（user_lo < user_hi）**：防止 (A,B)/(B,A) 双行；所有查询按「我的 id 出现在 lo 或 hi」+ 索引覆盖。

### 3.3 messages 单表 vs 按会话分表（裁定：单表）

量级评估：一期定位个人使用（<100 用户）。按 50 用户 × 200 条/天 = 1 万条/天，一年 365 万行、body 平均 200B 时库体积 <1.5GB——SQLite 单表 + `idx(conversation_id, id)` 下会话内 keyset 分页查询恒为毫秒级（B-tree 定位 + 顺序读）。分表（按会话或按月）在 SQLite 无原生分区支持、单写锁架构下收益为零、复杂度全负，是典型过度设计。**触发重设计的量级信号**：日消息量 >100 万或库 >20GB——那时应先迁 PostgreSQL 再谈分表（写进二期展望，不写代码）。

### 3.4 未读模型（cursor 裁定）

未读数 = `COUNT(messages WHERE conversation_id=? AND id > cursor.last_read_message_id AND sender_id != me)`。cursor 是「我读到哪了」的本地状态：**给自己看未读角标（做），不给对方发已读回执（不做）**。理由：回执需要推送确认+多设备 cursor 同步+离线补偿，UX 收益（对方知道你看了）与一期「人在环、防打扰」的产品气质相反；cursor 机制天然兼容二期回执（把 cursor 更新事件推给对方即可，无 schema 变更）。

---

## 4 · NebLink 号设计（[U3 2026-08-18 09:52 用户裁定] 重写——原 `NL-XXXX-XXXX` 固定生成方案作废）

用户原话：**"NL号可以自己设置（要进行重复检测，只能是数字和字母，不能有特殊字符），默认就是注册的邮箱号。"**

| 项 | 设计（v1.2 裁定） | 理由 |
|---|---|---|
| 默认值 | **注册邮箱**（`neblink_id = users.email`，用户落库时填入） | 用户裁定：邮箱即初始 NL 号——零额外生成逻辑、用户对自己的号天然已知、查号即查邮箱（体验直觉） |
| 自定义 | 用户可改设自己的 NL 号；**一期数据模型 + API 支持，自定义 UI 阶段 2**（边界见下「自定义入口」） | 一期先把正确性做进服务端，UI 归属待定不阻塞消息功能主线 |
| 字符集 | 自定义值**仅限字母+数字**（`[a-zA-Z0-9]`），无任何特殊字符（用户裁定原话）；**默认邮箱值豁免此规则**（邮箱天然含 `@`/`.`，豁免是裁定的必然推论，否则默认值自相矛盾） | 防混淆、防注入面、口播友好 |
| 长度 | **3-32 字符**（design-engineer 裁定，[U3] 未指定） | 3 下限：过短号（1-2 字符）稀缺且易撞名争抢；32 上限：与常见用户名体系（GitHub 39/微信 20）同量级，UI 单行展示无压力 |
| 大小写 | 存储保留用户输入原样；**唯一性比较与查询均大小写不敏感**（lowercase normalize 后比对） | 防 `Alice`/`alice` 双号冒充；查询体验容错 |
| 唯一性（重复检测） | 三层：① DB 唯一索引 `idx_users_neblink` 兜底（大小写不敏感靠 normalized 比较列或应用层 normalize 后写入检查——实现时二选一，推荐应用层 normalize 存储比较键）；② `GET /api/users/me/neblink-id/available?q=` 实时检测端点（供阶段 2 自定义 UI 即时反馈）；③ `PUT` 冲突返回 409 `{error:"neblink_id_taken"}` | 用户裁定「要进行重复检测」的可执行形式；实时检测服务未来 UI，索引兜底防并发抢注 |
| 查询语义（二合一简化） | `/api/users/lookup` **只查 `neblink_id` 单字段**精确匹配（lowercase normalize 后比对）——邮箱是默认 NL 号，「按 NL 号搜」与「按邮箱搜」天然是同一查询；**用户自定义后邮箱退出查询键**（搜原邮箱不再命中，隐私回升，防通讯录撞库加分） | 原方案的「NL 号或邮箱双键查询」在 [U3] 后语义合一，接口与实现同时简化 |
| 生成时机 / 存量迁移 | 用户落库（upsert/get-or-create）时：若 `email` 非空且 `neblink_id IS NULL` → 填 email；无 email（存量 GitHub 账号）→ 留 NULL，该用户暂不可被查号命中，待其自定义后获得 NL 号 | 照 `github_login` backfill 先例（store.rs:167-170），零迁移脚本；NULL 态语义显式 |
| 自定义入口（一期边界裁定） | 一期：**数据模型 + API 全量支持**（PUT/available 两端点随一期交付并验收）；**自定义 UI 不做**——理由：① 账号资料编辑属 Logto Account Center / 账号设置范畴（登录重构并行项），UI 落点需与 Logto 落地形态对齐，一期硬做会返工；② 默认邮箱号已满足「可被搜索」核心需求，自定义是体验增强非闭环必需。阶段 2 候选落点：Logto Account Center 自定义资料字段，或客户端 Settings/账号弹窗内嵌表单（届时按 Logto 集成结论二选一） | 边界显式，防实现方自由发挥 |
| 与 Logto 映射 | `users` 行三标识并存：`id`(内部uuid，好友关系外键) / `logto_id`(IdP 映射) / `neblink_id`(对外展示+查号，默认=email) | 职责分离不变：关系挂 uuid（稳定）、登录挂 logto_id（可换 IdP）、社交挂 neblink_id（可读、可自定义） |
| 改号频率 | 一期**不限次数**（API 层无冷却）；滥用风险由「好友关系挂内部 uuid」吸收——改号不影响既有好友关系与会话，仅影响后续查号 | 个人规模下改号骚扰面小；若二期出现抢注/频繁换号问题再加冷却期 |
| 隐私注意（新） | 默认态 NL 号=邮箱 → 结果卡展示的号即邮箱，等于**邮箱默认公开为社交标识**（用户裁定，已同步 UI 规格 R3 修订：原「查询键不回显邮箱」条款对默认态失效）；不希望公开邮箱的用户路径 = 自定义 NL 号（阶段 2 UI 落地后）或一期内由 API 直改 | [U3] 裁定的必然推论，显式记录防评审误报 |

~~原 v1.1 方案备查（作废）~~：`NL-` 前缀 + 8 位 Crockford Base32（40 bit 熵、防视觉混淆、随机防枚举）——[U3] 以「邮箱即默认号 + 用户自定义」覆盖，随机生成/backfill 逻辑全部移除；防枚举职责转交「单字段精确匹配 + 限速 20/min + 无模糊搜索」不变。

---

## 5 · 消息路由

### 5.1 在线推送：复用 relay-ws 隧道（裁定）

**方案**：`ServerToClient` 新增变体（relay.rs）：

```rust
pub enum ServerToClient {
    RelayRequest { ... },          // 既有
    Disconnect,                    // 既有
    FriendEvent {                  // 新增：JSON tag = "friend_event"
        #[serde(rename = "eventId")]
        event_id: String,          // 幂等去重
        event: serde_json::Value,  // §6.2 的事件 payload
    },
}
```

服务端 `push_to_user(user_id, event)`：`SELECT device_id FROM sessions WHERE user_id=?` → 对每个 device_id 查 `RelayRegistry.tunnels` → 逐个 `send(FriendEvent)`（离线设备自然跳过）。

**否决的备选**（新建 `/api/user/ws` user-JWT WS）：

- 桌面客户端**没有 user JWT**——device flow 换来的是 device credential，若新建 user WS 需要客户端再走一套用户登录态，重复且易过期；
- relay 隧道常驻（心跳 45s + 自动重连已解决连接管理、半开驱逐、服务端重启恢复），推送搭车零新增连接代码；
- Web 控制台（cookie 用户态）的实时消息是二期需求，届时再评估独立 WS，不倒逼一期。

### 5.2 离线存储与补拉

- 消息先落库再推送（store-and-forward），推送失败/离线无影响。
- 客户端两个补拉时机：启动时、relay 隧道重连成功时（`NeblinkRelayTunnel` 重连回调触发 FriendService）→ `GET /api/conversations/{id}/messages?after=<本地最大id>`（keyset 分页，逐会话或聚合端点均可，见 §6.1）。
- `friend_event.event_id` 供客户端幂等去重（推送与补拉可能重叠）。

### 5.3 已读（裁定：本地未读数，无回执）

见 §3.4。`POST /api/conversations/{id}/read` 仅更新自己的 cursor。

### 5.4 不做 E2E 加密（裁定：TLS + 服务端可见）

**理由**：(a) E2E 需要设备密钥分发、多设备同步、丢失恢复三套机制，工程量超过一期消息功能本体；(b) 一期是单运营方（自部署）个人规模社交，信任边界=服务端运维者，TLS 已防外部窃听；(c) 服务端可见是**功能特性**而非纯妥协——好友请求通知、未读数、二期服务端智能路由（按消息内容判断 urgency）都依赖服务端可读。

**二期路径**（预留不实现）：`friendships` 表加 `key_a`/`key_b` 列存双方设备公钥（好友建立时交换），`messages.body` 存密文+`kind=encrypted`，客户端 X25519+AES-GCM 解密；服务端仅中转。schema 不需要破坏性变更。

---

## 6 · API 设计

鉴权统一 `require_user_or_device`：先按 user JWT 验（Web/官网路径），失败再按 Bearer device session token 查 `device_by_token` 映射 user_id（桌面客户端路径）。两者最终都得到内部 user_id。

### 6.1 REST 端点清单

| 端点 | 方法 | 说明 | 关键请求/响应字段 |
|---|---|---|---|
| `/api/users/lookup?q=` | GET | 查号（**[U3] 二合一：仅查 `neblink_id` 单字段**，默认=邮箱、自定义后=自定义号；精确匹配、大小写不敏感），限速 20/min | → `{found, neblinkId, name, avatarUrl}` |
| `/api/users/me/neblink-id` | PUT | **[U3] 新增**：自定义 NebLink 号——校验 `[a-zA-Z0-9]` + 长度 3-32 + 唯一性（normalize 后比对）；冲突 409；一期 API 交付，自定义 UI 阶段 2 | `{neblinkId}` → 200 `{neblinkId}` / 409 `{error:"neblink_id_taken"}` / 422 `{error:"invalid_format"}` |
| `/api/users/me/neblink-id/available?q=` | GET | **[U3] 新增**：唯一性实时检测（供阶段 2 自定义 UI 即时反馈）；限速并入查号 20/min | → `{available: true\|false, reason?}` |
| `/api/friends` | GET | 好友列表（accepted）+ 双向 pending 分组 | → `{friends:[{userId,neblinkId,name,avatarUrl,since}], incoming:[…], outgoing:[…]}` |
| `/api/friends/requests` | POST | 发好友请求 | `{query:"<NebLink号（默认=邮箱）>", note?}` → 201 `{requestId}` |
| `/api/friends/requests/{id}/accept` | POST | 同意 | → 200 `{friendshipId, conversationId}` |
| `/api/friends/requests/{id}/decline` | POST | 拒绝（行转 declined，记 declined_at） | → 200 |
| `/api/friends/{friendUserId}` | DELETE | 解除好友（删 friendships 行；会话与消息保留只读） | → 200 |
| `/api/friends/{friendUserId}/block` | POST | 拉黑（行转 blocked+blocked_by） | → 200 |
| `/api/friends/{friendUserId}/unblock` | POST | 解除拉黑（删行，可重新请求） | → 200 |
| `/api/conversations` | GET | 会话列表（按 last_message_id 倒序） | → `[{conversationId, friend:{…}, lastMessage:{…}, unreadCount}]` |
| `/api/conversations/{id}/messages` | GET | 拉消息，keyset 分页 | `?after=<msgId>&limit=50` → `[{id, senderId, kind, body, createdAt}]`（升序） |
| `/api/friends/{friendUserId}/messages` | POST | **发消息**（get-or-create conversation） | `{body}` → 201 `{messageId, conversationId, createdAt}` |
| `/api/conversations/{id}/read` | POST | 更新未读 cursor | `{lastReadMessageId}` → 200 |
| `/api/neblink/sync` | GET | 聚合补拉：启动/重连后一次拉全量增量（各会话 after 本地最大 id）+ 好友全量快照 | `?cursors=<convId:maxId,…>` → `{friends:[…], conversations:[{…, messages:[…]}]}` |

设计说明：发送按**好友寻址**（`/api/friends/{id}/messages`）而非会话寻址——UI 无需先建会话，服务器 get-or-create，首条消息即建会话；拉取/已读按会话寻址。`sync` 聚合端点避免客户端逐会话 N 次往返（个人规模会话数少，一期也可先不做、二期按需加——**实现时二选一，推荐先只做逐会话补拉**）。

### 6.2 WS 推送事件（relay 隧道 `type:"friend_event"`）

| event | 触发 | payload | 接收方 |
|---|---|---|---|
| `friend_request` | 有人向我发请求 | `{requestId, from:{neblinkId,name,avatarUrl}, note}` | 被请求方全部在线设备 |
| `friend_accepted` | 我发出的请求被同意 | `{friendshipId, friend:{…}}` | 请求方全部在线设备 |
| `message_new` | 好友发来消息 | `{messageId, conversationId, sender:{neblinkId,name}, kind, body, createdAt}` | 接收方全部在线设备 |

推送是**尽力而为**优化（在线秒达+通知），REST 是事实来源（补拉兜底）——推送丢了不影响正确性。

---

## 7 · Agent 发消息的工具接口

### 7.1 工具定义（客户端注册）

```
SendFriendMessage
  参数: to      string  好友名（备注名/neblinkId，客户端本地模糊解析，照 RemoteExecutor.resolvePeer 模式）
        message string  消息正文（≤4000 字符）
  返回: "已发送给 {好友名}（{时间}）" / 错误说明（非好友/被限速/用户拒绝/功能关闭）
```

工具 description 写明用途约束：「向好友发送文本消息。仅限已建立的好友关系。消息将以其主人（用户）身份送达对方聊天窗口。」

### 7.2 权限模型（三档，配置项 `neblink.agentMessaging.mode`）

| 档位 | 行为 | 适用 |
|---|---|---|
| **auto（一期默认，用户裁定）** | 直接发送，不打扰用户；受双层限速（20 条/h/好友、60 条/h 全局），**超限自动降级为 ask**（弹确认）而非硬失败 | 顺畅优先：信任自己的 agent，异常量才打扰 |
| ask | 工具调用触发客户端确认弹窗（复用既有 AskUser 交互/桌面通知+按钮），用户点「发送」才真正发出；超时 60s 视为拒绝 | 谨慎档：用户显式开启 |
| off | 工具直接返回「用户已禁用 agent 发消息」 | 完全关闭 |

### 7.3 防 agent 滥发（双层）

- **客户端层**（FriendService）：auto 模式下每好友 20 条/小时、全局 60 条/小时，超限自动降级为 ask（弹确认）而非硬失败；
- **服务端层**（friends.rs 内存限速器）：30 msg/min/user token bucket 兜底（防绕过客户端的直连 API 滥用），超限 429。

风险边界说明：agent 只能给**已接受的好友**发消息（服务端强制 friendship=accepted），冒充面被好友关系收敛；消息以用户身份发出，收方看到的是好友本人——auto 默认档位下超限自动降级 ask + 服务端 30/min 兜底保证异常量可拦截。

---

## 8 · 转发给 agent 的机制（裁定：纯客户端）

**机制**：聊天窗每条消息带「转发给 agent」按钮 → 客户端取消息文本 → 包装为：

```
[来自好友 {好友名} 的转发消息 | {日期}]
{原消息文本}
```

→ 走**既有用户输入管线**（等同用户在聊天框手敲后回车）注入当前 agent 会话。目标会话选择：一期=当前活跃会话（用户在哪开会话就注入哪）；后续可弹选择器。

**纯客户端的理由**：

1. agent 会话是客户端本地概念，服务端不知道会话结构、无路由依据；
2. **人是环的本质**：转发什么由人逐条决定。若经服务端（如服务端把消息标记为「待注入」再推给 agent 通道），等于制造「服务器可向 agent 上下文写入任意文本」的攻击面——服务器被攻破或好友消息含恶意指令时，注入即 prompt injection；
3. 走用户输入角色（而非 `AgentCommand.ExternalEvent` 系统事件通道）：语义上是「我（用户）想让你看这个」，保持用户消息角色，agent 权限不变；
4. 零服务端改动，转发逻辑全部收敛在客户端 UI 层。

---

## 9 · 与 Logto 集成

| 项 | 裁定 | 理由 |
|---|---|---|
| 好友关系挂什么 ID | **内部 uuid `users.id`**（friendships/conversations/messages 的外键全是它） | uuid 已经历 github→logto 一次 IdP 更替仍稳定（github_id→logto_id 都只是映射列）；挂 Logto id 则换 IdP 全量迁移；挂 neblink_id 则展示层变更侵蚀关系层 |
| users.logto_id 列 | JWKS 验签后 `sub`（=Logto user id）→ get-or-create users 行，写 logto_id | 登录重构方案步骤 3 的既有计划，本方案只确认兼容 |
| 好友 API 鉴权 | `require_user` 内部演进为双轨（HS256 legacy / JWKS RS256），**好友/消息 API 表面与调用方式零改动** | 鉴权已被 helper 隔离（routes.rs:111-120），IdP 更替不触好友域 |
| 桌面客户端 | 继续走 device credential → session token → user_id，**不受 Logto 影响** | device flow 与用户 IdP 正交（auth-redesign-survey §5 已论证） |
| 并行风险 | 好友系统先上线时 Logto 未落地：一切照旧（HS256）；Logto 落地后无感切换 | 双轨验签保证两个任务可任意先后 |

---

## 10 · 安全与滥用清单

| 场景 | 措施 | 实现位置 |
|---|---|---|
| 查号枚举 | 仅精确匹配、无模糊、20/min/用户、不命中统一 found:false | friends.rs + 限速器 |
| 好友请求骚扰 | 出站 pending ≤5；对同一用户重复请求且存在 declined 48h 内 → 429；10 请求/天/用户 | friendships 查询 + 限速器 |
| 被拉黑探测 | 被 block 后发请求：返回 200 但**静默丢弃**（不建行不推送）——请求方无法区分「对方没看到」与「被拉黑」 | create_friend_request 分支 |
| 消息轰炸 | ≤4000 字符（服务端截断/422）；30 msg/min/用户；仅 accepted 好友可发（403） | send_message 校验 |
| 伪造身份 | REST 鉴权绑定 user_id，sender_id 服务端写入（不收客户端字段）；WS 推送无上行消息通道 | require_user_or_device |
| SQL 注入 | 全部参数化查询（rusqlite params!，既有惯例） | store.rs 模式 |
| agent 滥发 | §7.3 双层限速 + auto 默认（超限降级 ask） | 客户端 + 服务端 |
| 限速器实现 | DashMap<key, TokenBucket>（key=user_id+action），单实例内存态，照客户端 ratelimit.scala 模式；无持久化需要（重启清零可接受） | src/friends.rs 或独立 src/limiter.rs |
| 内容安全 | 一期不做过滤（个人规模）；body 原样存储，`kind` 列预留二期结构化消息 | — |

---

## 11 · 分期表

| 能力 | 一期 MVP | 二期展望 |
|---|---|---|
| 消息类型 | 纯文本 ≤4000 字符 | 文件（复用 FileTransfer 隧道）、图片、结构化卡片（订单/任务语义 kind=task/order） |
| 会话形态 | 1v1 | 群聊（conversations 加 members 表，messages 不变） |
| 已读 | 本地未读数（cursor） | 已读回执（cursor 更新事件推对方）、多设备 cursor 合并 |
| 加密 | TLS + 服务端可见 | E2E（设备公钥交换挂 friendships，见 §5.4 路径） |
| agent 发送 | SendFriendMessage + **auto 默认（用户裁定）** + 双层限速超限降级 ask | 任务委派语义（对方 agent 自动应答确认卡，仍终审于人）、A2A 全自动模式（白名单好友对） |
| 通知 | 客户端通知中心（Notify 先例） | 系统级推送、免打扰时段 |
| 消息管理 | 不可撤回不可编辑 | 撤回（软删 tombstone，双方可见） |
| 载体 | 桌面客户端聊天窗 | Web 控制台（cookie 用户态 WS）、移动端 |
| 存储扩展 | SQLite 单表 | 日消息 >100 万或库 >20GB 时迁 PostgreSQL（§3.3 信号） |

---

## 12 · 与现有代码的集成点（文件级）

### 服务端 `~/.nebflow/projects/neblink-server/`

| 文件 | 改动 |
|---|---|
| `src/store.rs` | execute_batch 加 4 表 DDL + users 2 列 ALTER 迁移 + neblink_id **默认填邮箱（[U3]，原随机生成/backfill 逻辑移除）** + 自定义校验与 normalize 存储 + friendships/conversations/messages/cursors 全部 CRUD + push 用的「user→devices」查询 |
| `src/friends.rs`（新） | 好友/消息全部 handler + **NL 号自定义 PUT / available 检测（[U3]）** + `require_user_or_device` + 内存限速器 + `push_to_user`（约 600-800 行，路由与逻辑独立成模块避免 routes.rs 继续膨胀） |
| `src/relay.rs` | `ServerToClient::FriendEvent` 变体 + `relay_ws_loop` 序列化分支（既有 match 加一臂） |
| `src/model.rs` | 好友/消息域请求响应类型（照 HubItem 命名惯例） |
| `src/routes.rs` | `AppState` 无需改（Store/RelayRegistry 复用）；仅 re-export friends 路由 |
| `src/main.rs` | Router 挂载 `/api/friends/*`、`/api/conversations/*`、`/api/users/lookup` |

### 客户端主仓库（`/Users/dev/Claude code/Nebflow/`）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/NeblinkRelayTunnel.scala` | RelayWsListener 加 `"friend_event"` case（:253 分发处）→ 回调注入的 FriendService |
| `src/main/scala/nebflow/neblink/FriendService.scala`（新） | REST 收发 + 未读 cursor + 重连补拉 + 事件去重（eventId）+ agent 发送限速 |
| `src/main/scala/nebflow/neblink/NeblinkClient.scala` | 加 friends/messages API 方法（照 heartbeat/relayExec 模式） |
| `src/main/scala/nebflow/core/tools/FriendMessageTool.scala`（新） | SendFriendMessage 工具 + ask 确认交互 |
| `src/main/scala/nebflow/core/tools/registry.scala` | 注册工具 |
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | `/api/friends/*` 本地代理（照 device-flow 的 server URL 解析 + token 注入 :2275 模式） |
| `src/main/scala/nebflow/neblink/NeblinkModel.scala` | `agentMessaging.mode` 配置（ask/auto/off） |
| `src/main/resources/web/js/friends.js`（新）+ `main.js` 挂载 | 联系人/会话面板、聊天窗、转发按钮、未读角标 |

### 部署

无新组件、无新环境变量（复用 NEBLINK_DB_PATH / NEBFLOW_JWT_SECRET）；腾讯云迁移不受影响。

---

## 13 · 验收条件

**红线：冒烟测试是第一项，不通过则全部无效。**

1. **服务端冒烟（硬性）**：`cargo run`（或 docker compose up）真实启动 → `curl /api/health` 200 → 用两个测试账号（dev 辅助端点或直接 SQL 造号+token）跑全链路脚本：lookup（精确命中/不命中两种）→ A 发好友请求 → B 拉列表见 incoming → B accept → A 收到 friend_accepted（WS 或轮询）→ A 发消息 → B 拉到消息 → B read 后 A 侧 unread 归零口径正确。每步断言 JSON 字段与状态码。
2. **编译与单测**：`cargo test` 通过，至少覆盖：friendships 状态机全迁移路径（含 48h 冷却边界、blocked 静默吞）、messages keyset 分页边界（after=0/中间/最大 id）、read_cursors 未读计算（含自己发的消息不计未读）、限速器触发 429。
3. **WS 推送端到端**：tokio 集成测试——起真实 axum 实例，两个 WS 客户端持 device token 连 relay-ws，A 发消息 → B 收到 `type:"friend_event"` 且 payload 完整；B 掉线时 A 发消息 → B 重连补拉无丢失、eventId 去重生效。
4. **持久性**：发 N 条消息 → 重启 server → 全量拉取完整（store-and-forward 不依赖内存态）。
5. **迁移兼容**：旧库（无新表）启动自动建表不报错；存量用户首次调用任意好友 API 后 neblink_id 已填邮箱（[U3] 新语义；无 email 的存量账号保持 NULL 为合法态）。
6. **NL 号自定义（[U3] 新增断言）**：`PUT /api/users/me/neblink-id` ——合法值（`abc123`，3-32 字母数字）→ 200 且 lookup 新号命中、原邮箱不再命中（查询键切换）；非法值（含特殊字符 `a-b`/`a_b`/`a.b`、长度 2、长度 33）→ 422；占用冲突（seed 另一用户同号，大小写变体 `ABC123` 亦判冲突）→ 409；`available` 端点对占用/空闲/非法三种输入分别返回正确分支。
7. **安全断言**：非好友发消息 403；超长消息 422；超速 429；被拉黑方发请求返回 200 但库中无 pending 行。
8. **客户端（后续独立任务验收）**：sbt compile + UI 冒烟（好友面板渲染、聊天窗收发、转发按钮注入当前会话）——本方案只裁定接口，客户端实现另立任务。

---

## 14 · 时序图

### 14.1 好友请求流

![好友请求流](assets/friends-seq-friend.svg)

### 14.2 消息收发流（在线推送 + 离线补拉）

![消息收发流](assets/friends-seq-message.svg)

### 14.3 Agent 发消息流（人在环闭环）

![Agent 发消息流](assets/friends-seq-agent.svg)

---

## 版本日志

- v1（2026-08-17 21:55）：初稿，九项裁定全量给出。
- v1.1（2026-08-17 22:00）：用户批准——整体通过；agent 默认档位裁定为 auto（原推荐 ask，超限降级 ask 保留为自动护栏）；一期聊天窗载体裁定为仅桌面客户端。状态 draft → frozen，可派发实施（服务端先行，客户端另立任务）。
- v1.2（2026-08-18 09:52 用户终审 · [U3]）：NebLink 号规则修订——**默认值=注册邮箱，用户可自定义（字母+数字、3-32、唯一性实时检测）**，原 `NL-XXXX-XXXX` 固定生成方案作废；§4 重写、§3.1 DDL 注释、§6.1 API 清单（13→15 端点，+PUT/available）、§12 集成点、§13 验收（+NL 自定义断言）、时序图同步。状态保持 frozen，修订后立即派发实施。
