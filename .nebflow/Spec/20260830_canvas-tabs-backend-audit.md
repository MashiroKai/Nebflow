# 2026-08-30 Canvas 标签服务端持久化（F1）——交付核对表

> 阶段：Backend F1 完成自测，待 qa-backend 验收。前端 F2/F3 由 Frontend 并行。
> 分支 feat/tabs-backend（worktree ~/Claude code/.nb-worktrees/nb-tabs-backend），基线 main @d5ad5227，commit **68da9981**（3 文件 +320）。

## 任务书契约 vs 实现

| # | 要求 | 实现 | 验证 |
|---|---|---|---|
| 1 | 新增存储 `~/.nebflow/canvas_tabs.json`（与 sessionStore 同生命周期） | `CanvasTabStore`（core）：path 由调用方传入，生产=`PathUtil.dataRoot / "canvas_tabs.json"` | E2E 盘上断言（v/tabs/activeTabId 精确） |
| 2 | `GET /api/canvas-tabs` → 200 `{v:2,tabs:[...]}` 或 404（无存档） | ✅ 路由 `GET -> Root / "canvas-tabs"`（withAuth） | E2E：无存档 404 / 重启后 200 且数据与 PUT payload 完全一致 |
| 3 | `PUT /api/canvas-tabs` → 200 `{ok:true}`；写失败 4xx/5xx 带错误 | ✅ 保存失败 → 500 + error 信息 | E2E 合法 PUT 200 ok |
| 4 | 原子写（AtomicJson 模式 ATOMIC_MOVE） | ✅ `AtomicJson.writeSync`（tmp+UUID+ATOMIC_MOVE） | 单测：写后磁盘内容精确=json.noSpaces + 目录零 `*.tmp.*` 残留 |
| 5 | schema 校验：v 必须 2；tabs 数组；条目字段类型；非法 400 不落盘 | ✅ `CanvasTabs.validate` 纯函数——v=2 / tabs 数组 / id·type·title 必填 string（id 非空）/ absPath null|string / pinned·closable boolean / activeTabId null|string；未知字段忽略（宽容向前兼容） | 单测 9 组拒绝矩阵 + E2E v=1→400 且不动盘上存档 |
| 6 | 大小限制 ≤256KB 超限 413 | ✅ `CanvasTabs.MaxBodyBytes=256*1024`；路由 `req.body.take(max+1)` 有界读（防大 payload 拉爆内存）→ 超限 413 | 单测 2 组（超限/合法 JSON 超限仍 413）+ E2E 413 |
| 7 | 路由挂载：跟随现有 REST 风格 | ✅ RestApiRoutes.routes（Router /api 前缀），withAuth（Bearer/query token，与前端 authHeaders() 对接） | E2E 无 auth→403 |

## 字段契约（与前端同步，2026-08-30 实测 canvas.js persistTabs/restoreTabs v2 schema）

任务书写「subject」，前端实际字段为 **title**——后端按前端实际 schema 校验（id/title/type/absPath/pinned/closable + 顶层 activeTabId）。未知字段忽略，不拒绝前端未来新增字段。

## 验证终态

- **定向单测**：CanvasTabStoreSpec 19/19（读写往返/覆盖写/原子写无残留/无存档 None/损坏容错×2/validate 接受×4/拒绝×5/parseBody 400+413×4）
- **隔离实例 E2E**：/private/tmp/nb-tabs-run/run-e2e.sh → ASSERT-FAILS: 0（11 项：404→PUT 200 ok→盘上断言→400 不落盘→413→kill 验身→重启同 home→GET 数据 identical→403）
- **全量**：1880/1880 Passed 0 Failed（/tmp/nb-tabs-fulltest.log；基线 1861+新增 19 吻合，零回归）
