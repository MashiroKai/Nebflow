# REST History 真分页 — ts 游标评估一页纸（2026-08-17，Backend）

对齐 Frontend v3.1 契约：**before/after/limit**（epoch ms；before 含端点 ≤ 锚点日
23:59:59.999.999，after 对称；limit 默认 100）。前端已发参、后端忽略返回全量
（客户端切片过渡）——真分页落地后前端零改动切换。本文只评估，实施另派。

## 1. 存储层现状（ts 索引可查性）

- **存储格式**：每会话一个 `.ui.json`（JSON 数组，UiMessage 序列化），
  `loadUiMessages` 全量读入 + `uiCacheRef` 内存缓存。**文件内无 ts 索引、
  无按 ts 排序保证**（文档序=追加序，ts 单调但不严格——跨设备/回拨时钟）。
- **已有分页 API 均为索引式**（SessionStore:1435/1450）：
  - `getUiMessages(offset, limit)` → (slice, total)
  - `getHistoryPage(limit, beforeIndex)` → (slice, total, offset, hasMore)
  - 两者都是**全量加载后内存切片**——大历史会话（千条级）每次翻页全读，
  这是真分页要解决的成本点，但当前缓存缓解了重复读。
- **ts 分布**：`UiMessage.User/Ai` 有 `timestamp: Long`（录制点
  WebSocketRoutes 多处 `System.currentTimeMillis`，>0 才编码进 JSON）；
  **Tool/AskUser/Ask/AskPermission/Agent/System 六类无 ts 字段**（wire 上恒缺）。

## 2. ts 游标实现路径（推荐：内存游标，不动存储格式）

**方案 A（推荐）：加载后二分切片**。`loadUiMessages` 已全量进缓存，在缓存
List 上对 User/Ai 的 ts 做二分（列表近似 ts 有序）定位 before/after 边界，
中间夹的六类无 ts 消息按**文档序归属**（跟随前后锚点，不单独判定）。复杂度
O(log n) 定位 + O(page) 切片，零存储改动、零迁移，REST 层直接加参数即成。
- 边界：时钟回拨导致的局部乱序——二分结果取**保守窗口**（边界 ± 若干条
  扫描校正），或文档声明游标语义为"锚点消息的 ts"（见 §4 建议）。
- 大会话首次加载成本不变（本来就全量读）——真正的 IO 分页需要存储拆文件
  （按日分段 jsonl），属独立优化项，不建议与游标语义同批做。

**方案 B（不推荐）**：`.ui.json` 拆按日分段 + ts 索引文件——改动大（写入路径
appendUiMessages 要路由分段、缓存失效复杂化）、收益只在千条以上会话。

## 3. REST 暴露方案

- 端点：`GET /api/sessions/{id}/history?before=<ms>&after=<ms>&limit=100`
  （RestApiRoutes:132 现走 `getUiMessages(0,0)` 全量——改造点单点）。
- 语义对齐 v3.1：before 含端点（`ts <= before`）；after 对称（`ts >= after`）；
  同 anchor 双向翻页；limit 默认 100、<=0 视为不限（兼容现网调用）。
- 返回体建议：`{messages, anchorTs, hasMoreBefore, hasMoreAfter}`——anchorTs
  回传本页首/末**实际消息 ts**，前端下一页用它做游标（避免用日历边界累积漂移）。
- WS 侧 `getHistoryPage`（WebSocketRoutes:2111）保持索引式不动（会话内滚动
  加载语义与日历翻页不同）——两套分页并存但语义分离。

## 4. ts=0（Tool/ask/agent）的后端侧建议

- **不建议给六类 UiMessage 补录制 ts**：wire 兼容代价（旧档无字段→decoder
  兜底 0，新旧档混排反而引入第二种"无 ts"语义）；且 Card 栏目（依赖这六类）
  前端已有文档序伪时间兜底，补 ts 不解决"栏目按业务日分组"的真实诉求。
- **建议后端只对 User/Ai 做 ts 游标**，六类消息按文档序夹带返回（方案 A 的
  归属规则）；若未来 Card 栏目需要精确日界，正确做法是**录制时给全部类型补
  ts**（新档渐进，旧档文档序兜底），作为独立决策另议——那是对 `.ui.json`
  schema 的演进，不是分页的前置。

## 5. 工作量估计

方案 A：SessionStore 新增 `getHistoryByTs(sessionId, before, after, limit)`
（~60 行含边界扫描）+ RestApiRoutes 端点改造（~30 行）+ spec（双读语义、
ts=0 归属、含端点边界、limit 边界，~8 用例）。约半天。
