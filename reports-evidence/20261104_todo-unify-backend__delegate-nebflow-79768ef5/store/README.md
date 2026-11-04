# 证据目录 — 前端数据面换血节点（todoStore.js → 后端真源适配器）

批次 `todo-unify-backend` · 链「验：全套机械验收」· 本节点 = store 适配器
工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
分支 `delegate/delegate-nebflow-79768ef5`（改前 HEAD `0fbb64b68` = backend 节点收口）

## 改动面（数据面换血；渲染结构零改动）

| 文件 | 动向 |
|---|---|
| `src/main/resources/web/js/todoStore.js` | 261 行纯内存 mock 仓 → 758 行后端真源适配器 |
| `tests/todo-store-backend.spec.mjs` | 新增 522 行 / 6 例（零后端零 sbt，`routeWebSocket` **有状态夹具后端**） |
| `src/main/resources/web/js/mobile/chatChrome.js` | +7 行：FAB 徽标接 `todoStore.subscribe`（唯一渲染侧触点，只加订阅，零 DOM/CSS） |
| `js/todoFace.js` / `js/mobile/taskPanel.js` / `js/sidebar.js` | **零改动** |

## 读数（原始日志 = 本目录同名文件）

| # | 日志 | 口径 | 读数 |
|---|---|---|---|
| 00 | `00_baseline_todosplit.log` | 改前 `todo-split.spec.mjs` | `6 passed` · EXIT=0 |
| 01 | `01_baseline_faceshots.log` | 改前 `todo-face-shots.mjs`（原 todoStore.js） | `96 pass / 8 fail` · EXIT=1（**改前既有红**） |
| 02 | `02_baseline_pageshots.log` | 改前 `todo-page-shots.mjs` | `SHOTS OK` · EXIT=0 |
| 03 | `03_baseline_circular.log` | 改前 `scripts/check-circular.mjs` | 2 SCC（`popArtifacts·reference·msgQuote`、`navController·taskPanel`）· EXIT=1 既有 |
| 04 / 09 | `04_after_circular.log`、`09_after_circular_badgewire.log` | 改后同闸（两次：适配器后 / 徽标接线后） | **SCC 集合逐字相同** ⇒ 新增 `todoStore→ws.js`、`chatChrome→todoStore` 两条静态边**零环** |
| 05 | `05_new_spec_todo_store_backend.log` | 新增 spec | **`6 passed`** |
| 10 | `10_final_todosplit.log` | 改后 `todo-split.spec.mjs` | `6 passed` · EXIT=0（未转红） |
| 11 | `11_final_faceshots.log` | 改后 `todo-face-shots.mjs` | `96 pass / 8 fail` · **FAIL 集与 01 逐条同名同因**（comm 差异 = 空） |
| 12 | `12_final_pageshots.log` | 改后 `todo-page-shots.mjs` | `SHOTS OK` · EXIT=0 |
| 13 | `13_final_mobiledock.log` | 改后 `mobile-dock.spec.mjs`（徽标触点回归） | `20 passed / 1 failed`（D18 `titlePosition` static→sticky） |
| 14 | `14_baseline_mobiledock.log` | 改前同 spec（原 chatChrome.js） | `20 passed / 1 failed`（**同一条 D18**）⇒ 既有红 |

## 改前 / 改后逐条比对

`todo-face-shots.mjs` 的 8 条红 = 4×`swipe-down deletes tag` + 4×`desktopScenario … threw
(page.click Timeout)`，根因同一：`#modal-overlay.ds-overlay.nf-trap-root` 盖在 `#task-list`
上，Playwright 点击被 `intercepts pointer events` 拦下。改前改后 PASS 数（96）与 FAIL 集
逐条相同（`comm -13/-23` 双向为空）⇒ **本批零新增红**。

## 中间轮次（06–08，留档说明）

`06_after_todosplit.log` / `07_after_faceshots.log` / `08_after_pageshots.log` 是**徽标接线前**的
中间轮次：`07` 里多出 4 条 `FAB badge = 8 … — 0`（手机 FAB 徽标只在创建那一刻读一次
`openCount()`，换血前靠 `todoStore` 模块加载期 mock seed 对齐；mock 退役后徽标停在 0）。
处置 = `mobile/chatChrome.js` 加一条 `todoStore.subscribe(syncTaskBadge)`；`11_final_*`
即修复后的终轮读数（该 4 条全绿）。

## 结论

新 spec 6/6 绿；既有三套 + mobile-dock 全部未因本批转红（红点均在改前基线同名存在）；
静态环零增量。
