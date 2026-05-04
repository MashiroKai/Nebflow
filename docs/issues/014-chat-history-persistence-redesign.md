# Feature: 服务端全量持久化 UI 事件流 + 前端 IndexedDB + 按需分页加载

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @username |
| 状态 | 草稿 |
| 标签 | `backend`, `frontend`, `db` |
| 优先级 | P1-高 |
| 创建日期 | 2025-01-13 |
| 目标日期 | 未确定 |
| 预估工时 | 8h / 未评估 |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

将聊天记录持久化从浏览器 localStorage 迁移到后端磁盘全量存储 + 前端 IndexedDB 缓存，支持切换会话时从服务端实时拉取历史，解决 localStorage 超配额导致记录丢失的问题。

---

## 背景（可选）

当前前端 UI 聊天记录（tool cards、error bubbles、askUser 选项框、系统通知、agent badges 等）仅保存在浏览器 `localStorage` 的 `nebflow_sessions` key 中，与后端 `~/.nebflow/sessions/*.json` 存的 LLM 协议消息完全解耦。当 `localStorage` 因超配额（5MB）、Cookie 清理、无痕模式、端口变更等原因被整体清空后，用户所有历史 session 的 UI 聊天记录永久丢失，即使后端磁盘文件仍在。参见现有清理逻辑 `sidebar.js:168-176` 和 `persistence.js:10-23`。

---

## [创建] 动机

**为什么需要这个功能？**

- **用户场景**：用户昨天与 Nebflow 的完整对话（含工具执行卡片、错误提示、用户附件预览）在今天打开后全部不可见，只有今天的记录保留。后端 `~/.nebflow/sessions/` 中昨天的 LLM 消息文件仍然存在，但前端 UI 记录已因 localStorage 被清理而丢失。
- **不做的问题**：当前 workaround 是接受记录丢失。问题在于聊天记录是用户与 AI 协作的核心上下文，丢失后无法追溯之前的操作和结论，严重影响可用性。

---

## [创建] 目标

1. 后端持久化完整的 UI 事件流（与前端 `saveMsg` 的 entry 结构对等），作为聊天记录的唯一真理源。
2. 前端放弃 `localStorage`，改用 `IndexedDB` 做本地缓存，容量不再受 5MB 限制。
3. 切换 session 时前端从后端拉取历史记录重建聊天 DOM，不再依赖本地缓存。
4. 向上滚动加载更早消息时支持按需分页，避免一次性传输大量历史。
5. 保留向后兼容：现有后端 LLM 消息格式不变，UI 事件流作为独立层叠加。

---

## 优先级（可选）

| 属性 | 评估 |
|------|------|
| 优先级 | P1-高 |
| 目标版本 | 未确定 |
| 目标发布日期 | 无 |

---

## [创建] 范围

### In Scope

- [ ] 后端新增 UI 事件流持久化接口和存储（`~/.nebflow/sessions/<id>.ui.jsonl` 或等效方案）
- [ ] 后端 WebSocket 协议新增 `loadHistory` / `historyPage` 消息类型，支持分页拉取
- [ ] 前端 `persistence.js` 从 `localStorage` 迁移到 `IndexedDB`
- [ ] 前端 `switchSession` 时调用后端拉取历史并重建 DOM
- [ ] 前端聊天区域支持向上滚动分页加载更早消息
- [ ] 现有后端 `Message`（LLM 协议消息）持久化不受影响
- [ ] `sidebar.js` 删除激进的 localStorage 清理逻辑

### Out of Scope

- [ ] 本次不实现多设备实时同步（如手机与 PC 同时在线时的实时推送）
- [ ] 本次不实现消息端到端加密
- [ ] 本次不迁移现有 localStorage 中的旧 UI 记录（已丢失的无法恢复）
- [ ] 本次不改动后端 LLM 消息格式（`Message`、`ContentBlock` 等）

---

## [创建] 需求

### 功能需求

- [ ] 后端 `SessionStore` 新增 `saveUiEvent(sessionId, entry)` 和 `loadUiEvents(sessionId, offset, limit)` 接口
- [ ] UI 事件 entry 结构与前端当前 `saveMsg` 的 JSON 格式对等（`type: user | ai | tool | askUser | agent | error | system`）
- [ ] 后端 WebSocket 路由处理 `loadHistory` 请求，返回分页的 UI 事件列表
- [ ] 前端 `IndexedDB` 初始化：按 `sessionId` 分 object store，schema 与现有 entry 结构一致
- [ ] 前端 `switchSession` 时：先清空 DOM，再调用后端 `loadHistory` 拉取最近 N 条（如 50 条），重建聊天区域
- [ ] 前端聊天区域滚动到顶部时，触发分页加载更早的 UI 事件（如每次 30 条）
- [ ] 前端运行中收到的实时事件继续写入 IndexedDB（作为缓存）
- [ ] 前端 IndexedDB 定期清理已删除 session 的记录（仍保留后端清理逻辑，但不再删除当前存活的 session）

### 非功能需求

- [ ] 单次 `loadHistory` 响应延迟 < 200ms（假设单 session 50 条记录）
- [ ] IndexedDB 容量不触发浏览器配额警告（通常 > 50MB）
- [ ] 向后兼容：老版本浏览器无 IndexedDB 时降级到 localStorage（可选）

---

## 设计

### 接口/行为变更

- **新增接口（后端 WS 协议）**：
  - `loadHistory`（Client → Server）：`{ type: "loadHistory", sessionId, offset, limit }`
  - `historyBatch`（Server → Client）：`{ type: "historyBatch", sessionId, entries: [...], hasMore }`
- **新增接口（后端 Scala）**：
  - `SessionStore.saveUiEvent(id: String, entry: Json): IO[Unit]`
  - `SessionStore.loadUiEvents(id: String, offset: Int, limit: Int): IO[List[Json]]`
- **变更接口**：
  - `WebSocketRoutes` 新增 `loadHistory` 消息处理分支
- **废弃接口**：
  - 前端 `localStorage` 中的 `nebflow_sessions` key（不再作为真理源，仅保留降级/迁移用途）

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| 后端 UI 事件存储格式 | 每个 session 一个 JSON Lines 文件（`.ui.jsonl`） | 每个 session 一个 SQLite 文件 | **待决策** | JSON Lines 实现简单、易读；SQLite 支持分页和索引更高效 |
| IndexedDB schema 设计 | 单 object store，按 sessionId + timestamp 复合索引 | 每个 session 一个 object store | **待决策** | 单 store 易于管理；多 store 查询隔离性好 |
| 首次加载策略 | 拉取最近 50 条，滚动分页 | 拉取全部历史 | **待决策** | 分页避免大 session 首次加载过慢；全部加载实现更简单 |
| 后端是否同时推送 LLM Message 重建 | 只推送 UI 事件流 | UI 事件流 + LLM Message 双轨 | **待决策** | 双轨冗余；UI 事件流已包含足够信息重建 DOM |

### 待决策项

- [ ] UI 事件流文件格式：`.ui.jsonl` vs SQLite？
- [ ] IndexedDB 分页查询策略：游标 vs 复合索引范围查询？
- [ ] 向后兼容方案：是否需要保留 localStorage 降级路径？

### 代码示例

```scala
// 后端：SessionStore 新增接口
def saveUiEvent(id: String, entry: Json): IO[Unit] =
  IO.blocking {
    val f = uiEventFile(id)
    os.write.append(f, entry.noSpaces + "\n", createFolders = true)
  }

def loadUiEvents(id: String, offset: Int, limit: Int): IO[List[Json]] =
  IO.blocking {
    val f = uiEventFile(id)
    if !os.exists(f) then Nil
    else
      val lines = os.read.lines(f).toList.reverse.drop(offset).take(limit)
      lines.flatMap(line => decode[Json](line).toOption)
  }
```

```javascript
// 前端：IndexedDB 初始化
const DB_NAME = 'nebflow_chat';
const DB_VERSION = 1;

function initDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains('messages')) {
        const store = db.createObjectStore('messages', { keyPath: 'id', autoIncrement: true });
        store.createIndex('sessionId_timestamp', ['sessionId', 'timestamp'], { unique: false });
      }
    };
    req.onsuccess = (e) => resolve(e.target.result);
    req.onerror = (e) => reject(e.target.error);
  });
}
```

---

## [创建] 兼容性

- **向后兼容：** Yes
- **迁移指南：** 无需用户操作。前端首次升级时自动创建 IndexedDB，localStorage 中的旧记录可选择性导入或静默丢弃。
- **废弃计划：** `localStorage` 中的 `nebflow_sessions` key 在本功能发布后的下一个版本中完全移除写入逻辑，仅保留读取用于一次性迁移。

---

## [结束] 成功标准

**如何量化地判断是否成功？**

- [ ] 切换 session 后聊天记录完整重建，与后端磁盘记录一致
- [ ] 浏览器清空 Cookie / localStorage 后，重新打开页面仍能加载所有历史 session 记录
- [ ] 单 session 1000 条 UI 事件，首次加载 < 500ms，分页加载 < 200ms
- [ ] IndexedDB 存储 50 个 session × 200 条记录不触发浏览器配额警告
- [ ] 现有功能（发送消息、工具调用、任务列表）不受影响

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| IndexedDB 在部分浏览器/隐私模式下不可用 | Medium | 降级到 localStorage 或禁用本地缓存，每次从后端全量拉取 |
| 大量历史消息导致首次加载缓慢 | Medium | 限制首次拉取条数（50条），滚动分页加载 |
| UI 事件流与 LLM Message 数据不一致 | Medium | 明确分工：UI 事件流只用于前端渲染，LLM Message 只用于后端上下文 |
| 向后兼容：老用户 localStorage 数据无法恢复 | Low | 在 release note 中说明，提供手动迁移脚本（可选） |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：发送消息并执行工具，确认后端 `.ui.jsonl` 文件生成且包含完整事件
- [ ] 步骤二：清空浏览器 localStorage 和 IndexedDB，刷新页面，切换 session，确认聊天记录从后端重建
- [ ] 步骤三：创建超过 50 条消息的 session，滚动到顶部触发分页加载，确认更早消息按需加载
- [ ] 步骤四：删除一个 session，确认前端 IndexedDB 中该 session 记录被清理

### 测试策略

- [ ] 单元测试覆盖 `SessionStore.saveUiEvent` / `loadUiEvents` 接口
- [ ] 集成测试覆盖 WebSocket `loadHistory` → `historyBatch` 端到端流程
- [ ] 前端测试覆盖 IndexedDB 读写和分页查询

### 回归检查

- [ ] 现有功能不受影响：消息发送、工具调用渲染、session 切换、任务列表
- [ ] 后端 LLM 消息持久化格式不变
- [ ] 多端（不同浏览器标签页）同时打开同一 session 无冲突

---

## 关联

- Depends on `013-system-reminders-not-injected.md` — 前置依赖（已修复）
- Related to `005-tool-result-cross-session-leak-and-detail-toggle-broken.md` — 涉及 session 隔离相关
- Related to `009-compaction-observability-and-auto-resume.md` — compaction 事件也需存入 UI 事件流

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `SessionStore.scala` | **Modify** | 新增 `saveUiEvent` / `loadUiEvents` 接口 |
| `WebSocketRoutes.scala` | **Modify** | 新增 `loadHistory` 消息处理分支 |
| `persistence.js` | **Rewrite** | localStorage 迁移到 IndexedDB |
| `sidebar.js` | **Modify** | 删除激进清理逻辑，切换 session 时调用后端拉取 |
| `main.js` | **Modify** | 新增 IndexedDB 初始化和历史加载触发 |
| `chat.js` | **Modify** | 新增分页加载触发逻辑（滚动监听） |
| `SessionService.scala` | **Modify** | 新增历史加载服务方法 |
| `SessionMeta.scala` | **Modify** | 可选：新增 UI 事件数量/最后更新时间字段 |

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已发布
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 用户反馈如何？ | |
| 工时预估偏差原因？ | |
