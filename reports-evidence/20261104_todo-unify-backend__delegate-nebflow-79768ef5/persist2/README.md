# persist2 —— 落库验收（五项真落库 + 刷新 + 跨端）证据索引

批：`todo-unify-backend` · 链「验：全套机械验收」· 节点 = **落库验收（判定节点）**
工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
分支 `delegate/delegate-nebflow-79768ef5` · **验证树 = `e3aac6ea6`**（`06_tree_at_final_run.txt`）
本节点**未改 `src/`**（`git status --porcelain` 仅本证据目录未跟踪；见 `02_tree_during_build.txt` / `06_tree_at_final_run.txt`）。

## 0. 一句话结论

**VERDICT: PASS** —— 完成 / 取消完成 / 新建 / 删除 / 改重要度 五项**全部真落库**（隔离实例
`scheduledTaskStore` 落盘文件直查 + DoneRecord JSONL 直查），刷新后全部存活/消解正确，两视口
两 context 跨端一致。实测 **45 PASS / 0 FAIL，EXIT=0**（`10_run_full.stdout.txt` 末行）。

## 1. 真源口径（为什么不是 curl）

本仓待办**没有 REST 端点**（HTTP 面只有 `/ws` 等）⇒ 真源 = 引擎落盘文件，直查比任何 HTTP 面更强：

| 面 | 绝对路径 | 形态 |
|---|---|---|
| 待办真源 | `<隔离HOME>/scheduled-tasks/<sessionId>.json` | 单行 JSON 数组，`ScheduledTaskStore.saveUnlocked` 整文件覆写（`src/main/scala/nebflow/core/scheduler/ScheduledTaskStore.scala:59-61`、`:36-37` `sessionFile`） |
| 完成痕迹 | `<隔离HOME>/checkin-records/done-records.jsonl` | 只增 JSONL（`core/scheduler/DoneRecordStore.scala:90-93`） |

本轮实跑路径：`/var/folders/ym/rfvzq57x4rgdq7cqcy9z68vc0000gn/T/qa-todo-persist2/scheduled-tasks/43c37951-8f8d-49d4-a0e2-a2c7b1084616.json`
（手工 `cat` + `jq` 原文见 `05_handcat_jq_final_store.txt`）。

## 2. 载体（隔离实例，零 6138 / 零 sbt / 零 kill）

| 项 | 值 |
|---|---|
| 实例 | 本 worktree 的 assembly jar（`target/scala-3.5.2/stand-assembly-2026.10.26-beta.2.jar`，17:53 构建，`00_sbt_assembly.log` EXIT=0） |
| 启动 | `java … nebflow.Main --home <隔离HOME> --port 8119 --no-browser --foreground start` |
| 端口 | 8119（脚本 pre-flight 断言空闲）；**6138 全程 untouched**（before/after 监听集均 `78176`，见断言 2 与 51） |
| HOME | `$(mktemp 系 tmpdir)/qa-todo-persist2`（delguard 守卫，`CLEAN=1` 才拆） |
| 浏览器 | Playwright chromium（`/opt/homebrew/lib/node_modules/playwright`），两个独立 context：A 桌面 1280×800 / B 手机 375×812 |
| 前端资源 | 实例日志 `static tree: dev web/ sources` ⇒ 服务的是工作树 `src/main/resources/web`（指纹见 `06_tree_at_final_run.txt`） |

**环境偏差申报（1 条，非产品改动）**：跨端腿需要两个控制台**同时在线**，而产品「控制台单实例接管」
会让后连者顶掉先连者（先连者被 `#modal-overlay.takeover-live` 盖住、指针被拦）。隔离实例因此用
**总闸 env** `STAND_CONSOLE_SINGLE_INSTANCE=off`（`NEBFLOW_` 双前缀同给）关掉它 —— 该开关在
`src/main/scala/nebflow/gateway/ConsoleTakeover.scala:78-81` 载明「off ⇒ onConnect/onDisconnect/
handleRetake 全 no-op、零漂移」；**生产 6138 不受影响**（未触碰、未重启）。

## 3. 读数表（断言 / 证据文件 / 关键行）

| # | 断言 | 结果 | 证据文件 | 关键原始读数 |
|---|---|---|---|---|
| ①-1 | 新建行真落盘 | PASS | `10_new_store.txt` | `"content":"PERSIST-①-新建高重要度"` 出现在 `scheduled-tasks/43c37951…json` |
| ①-2 | 新建 **priority=3** 落库 | PASS | `10_new_store.txt` | 原文 `…,"priority":3,"completedAt":null}` |
| ①-3 | `kind=todo`、未完成 | PASS | `10_new_store.txt` | `kind=todo completedAt=null` |
| ①-4/5/6 | 刷新后真源/活体/DOM 仍在 | PASS | `11_new_after_reload.txt` | `id=45404d4e priority=3`；DOM `["PERSIST-①-新建高重要度"]` |
| ②-1 | 完成 ⇒ `completedAt` 置位 | PASS | `20_complete_store.txt` | `completedAt=1793787012060` |
| ②-2 | DoneRecord 落盘 | PASS | `20_complete_done_records.txt` | `{"taskId":"45404d4e",…,"via":"ws"}`，行数 0→1 |
| ②-3/5 | 刷新后仍在已完成态 | PASS | `21_complete_after_reload.txt` | 真源 `completedAt` 仍在；`includeCompleted` 归档读口 `completed=[PERSIST-①-新建高重要度]` |
| ②-4/6 | 刷新后未完成面/DOM 摘掉 | PASS | `21_complete_after_reload.txt` | `visible=[]`（收件箱默认面不含已完成） |
| ③-0 | 前置：完成帧落盘 | PASS | `30_uncomplete_store.txt` | `id=84497934` 先 completedAt 置位 |
| ③-1 | 取消完成 ⇒ `completedAt` 清空 | PASS | `30_uncomplete_store.txt` | `completedAt=null`；DoneRecord 追加 `via:"undo"`（`05_handcat_jq_final_store.txt` 末三行） |
| ③-2/3/4 | 刷新后回到未完成 | PASS | `31_uncomplete_after_reload.txt` | 真源 `null`；DOM 含 `PERSIST-③-取消完成` |
| ④-1 | 删除 ⇒ 行物理消失 | PASS | `40_delete_store.txt` | 文件内 `remaining=["45404d4e"]`，`84497934` 不存在 |
| ④-2/3/4 | 刷新后不复现 | PASS | `41_delete_after_reload.txt` | 真源无该 id；活体 `visible=[]`、`completed` 无该行 |
| ⑤-0 | 改前排序（3 在 1 之前） | PASS | `50_priority_order_before.txt` | `["PERSIST-⑤-高重要度","PERSIST-⑤-低重要度"]` |
| ⑤-1 | 改重要度 3→0 落库 | PASS | `51_priority_order_after.txt` | `priority=0` |
| ⑤-2 | 排序位置随之变化 | PASS | `51_priority_order_after.txt` | after `["PERSIST-⑤-低重要度","PERSIST-⑤-高重要度"]`（低(1) 反超高(0)） |
| ⑤-3/4 | 刷新后仍为改后态 | PASS | `52_priority_after_reload.txt` | `priority=0`；DOM 顺序同改后 |
| ⑥-a | **新建**跨端 | PASS | `61_cross_create.txt` | A 建 priority=2 ⇒ B reload `[{"title":"PERSIST-⑥-跨端新建","priority":2}]` |
| ⑥-b | **完成**跨端 | PASS | `62_cross_complete.txt` | B `visible` 无该行、`completed` 含该行、`completedAt=1793787044794` |
| ⑥-c/c2 | **重要度**跨端（含排序） | PASS | `63_cross_priority.txt` | A.priority=3 = B.priority=3；B 端该行排在 priority=0 行之前 |
| — | 写帧五类全在线上出现 | PASS | `92_ws_frames.txt` | `create=5 / complete=3 / uncomplete=1 / delete=1 / update=2` |
| — | 零 JS 异常 / 零待办面 HTTP 失败 | PASS | `91_page_errors.txt` | 仅剩隔离实例的 `/api/portal/status`、`/api/canvas-tabs` 404（待办面之外，两端同现，非本批面） |
| — | 6138 未受影响 | PASS | 断言 2/51 | before=`[78176]` after=`[78176]` |

全量断言清单（45 条）：`10_run_full.stdout.txt`；结构化汇总：`99_summary.json`；截图：`shots/`。

## 4. 文件清单

| 文件 | 内容 |
|---|---|
| `todo-persist-live.mjs` | 本节点实测脚本（隔离实例 + 真源直查 + 双 context；可复跑） |
| `00_sbt_assembly.log` | 隔离实例用的 assembly 构建（EXIT=0，57s） |
| `00_gateway_boot_console_takeover.txt` | 总闸 env 申报（boot 日志行不在 data 流里，故以行为证据为准） |
| `01_tree_registration.txt` / `02_tree_during_build.txt` / `06_tree_at_final_run.txt` | 验证树 sha、构建期并发改动（Typefix 类型标注）、最终树与进程面 |
| `03_*` | 基线（空真源 + 页面活体） |
| `10_*`–`52_*` | ①–⑤ 每步真源原文 / 刷新后 / DOM 顺序 |
| `60_*`–`63_*` | ⑥ 跨端四拍 |
| `05_handcat_jq_final_store.txt` | 手工 `ls -la` / `cat` / `jq` 直查（绝对路径 + 原始片段） |
| `90_*` / `91_*` / `92_*` / `99_*` | 收尾总快照、页面错误、写帧计数、结构化汇总 |
| `shots/*.png` | 桌面/手机各步截图 |

## 5. 未覆盖 / 开放项（不影响本节点判定）

1. **已完成行无持久归档 UI**：刷新后只能经 `todoStore.refresh({includeCompleted:true})` 读口看到已完成行；
   完成后的可回退路径只有 6 秒「撤回条」（`todoFace.js buildUndoBar`）。属面板面产品取舍，本节点不判。
2. **两条既有 404**（`/api/portal/status`、`/api/canvas-tabs`）在隔离实例全新 HOME 下同现于两端；
   与待办面无关，已原样留档（`91_page_errors.txt`），未计入红。
3. **`notes` / 分组颜色 / chip 顺序** 按 `todoStore.persistence` 自申报为「仅本地」，本节点未判其落库
   （节点任务书五项不含）。
4. 并发节点 `Typefix` 在我构建期间改过 `web/js/todoStore.js`、`web/js/ws.js`（仅 JSDoc 类型标注，
   `02_tree_during_build.txt` 记账）；最终绿轮的实例直读工作树指纹见 `06_tree_at_final_run.txt`。
   未观察到任何行为差异（45 条断言全绿、零 JS 异常）。

## 6. 复跑

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5"
node reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/persist2/todo-persist-live.mjs
# 或自检面：SMOKE=1 …（只跑 boot + 双 context 就绪）
# jar 缺失时先：sbt assembly（本机 sbt 闸会排队）
```
