# 证据总索引 — 待办体系统一（todo-unify-backend / 链「验：全套机械验收」）

分支 `delegate/delegate-nebflow-79768ef5` · 工作区
`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
收口 merge 目标 = **本地 main `a0597efdd`**；merge 提交 **`333d17b2d`**（冲突唯一处
`css/todo-face.css`）。收口节点 = 收尾（land）节点，**未合 main / 未 push / 未重启任何进程**。

## 1. 结论（三行）

1. 待办只有一个可见入口 = 顶带待办胶囊；数据真源 = `Schedule/kind=todo` + `ScheduledTaskStore`（软删 `completedAt`）。
2. 本收口树上 **8 道前端门禁 EXIT=0**、Scala 编译 + 待办 spec 见 `final/logs/08_*.log`；
   `tests/type-baseline.json` **零改动**（`git diff main..HEAD` 为空）。
3. 范围核对：`git diff main..HEAD --stat -- src/main/resources/web src/main/scala tests` = **37 文件**
   （web 15 + tests 22；Scala 侧 = 0 —— 后端段已由作者合并点 `941a11a13` 带上 main）。

## 2. 收口节点新增证据（`final/`）

| 文件 | 内容 / 读数 |
|---|---|
| `final/logs/gates-summary.txt` | 八道门禁一行一读数（EXIT 汇总） |
| `final/logs/01_make_frontend_gates.log` | `make frontend-gates`（design-tokens + msgstyle + self-explaining + circular）EXIT=0 |
| `final/logs/02_check_circular.log` | `circular-deps PASS: 236 files, acyclic` EXIT=0 |
| `final/logs/03_check_component_contract.log` | `PASS 五条组件契约零新增`（TOTAL=937，R1/R4=0）EXIT=0 |
| `final/logs/04_check_design_spec_sync.log` | `PASS —— spec 表与令牌源逐条同值（39 条对位）` EXIT=0 |
| `final/logs/05_check_js_types.log` | `checkJs: 0 errors (baseline 0, zero-gate files: 3)` EXIT=0 |
| `final/logs/06_check_process_artifacts.log` | `git ls-files evidence/ reports/ docs/ = 0 违规` EXIT=0 |
| `final/logs/07_type_baseline_check.txt` | `git diff main..HEAD -- tests/type-baseline.json` = **空**（零豁免机械证明） |
| `final/logs/08_sbt_compile_and_todo_specs.log` | `sbt -batch compile scalafmtCheckAll "scalafix --check" testOnly <6 待办 spec>`（现役入口 `~/.stand/bin/sbt` = sbt-gate 包装，`ops/bin/sbt-gate` 已不在仓内） |
| `final/logs/09_postmerge_batch_specs.log` | merge 后本批四 spec 复跑：`22 passed (51.8s)` / `SPECS_EXIT=0`（store 6 + capsule-create 8 + entry-collapse 2 + split 6）——design-scale 令牌迁移合入后胶囊面零回归 |

## 3. 按节点列文件 + 一句话结论

| 节点 | 目录 | 关键文件 | 一句话结论 |
|---|---|---|---|
| recon（取证） | `recon/` | `EVIDENCE.md`、`10_runtime-proof.stdout.txt`、`11_anchors.txt`、`ws-frames-desktop.json`、`todo-shell-runtime-proof.mjs`、`f1..f8-*.png` | 三入口两数据源分裂 + `todoStore` 三无/seed/零写帧实证（PASS 33 / FAIL 0，含阳性对照） |
| backend（真源） | `backend/` | `CONTRACT.md`、`01_test_compile.log`、`02_spec_scheduler+frameface.log`、`03_spec_gateway_all.log`、`04/05_baseline_*.log` | 真源 = `kind=todo` + `ScheduledTaskStore`；补 priority / completedAt 软删 / `uncompleteTodo` / includeCompleted（纯增量，老库零迁移） |
| store（适配器） | `store/` | `README.md`、`05_new_spec_todo_store_backend.log`、`04/09_after_circular.log` | 261 行内存 mock → 后端真源适配器，新 spec 6/6 绿，静态环零增量 |
| panel（胶囊交互） | `panel/` | `README.md`、`shots-after/`、`logs/20-22_gate_*.log` | 加号 / 空白建 / 重要度落库 / 完成撤回 / 删除 / 手机层胶囊，三门前端门禁绿 |
| entries2（入口收敛） | `entries2/` | `README.md`、`10_new_spec_before_red.log`、`50_final_specs_and_shots.log` | 撤入口行 + 手机任务圆钮 + `#todo-page` 整面退役（零物理删除），信息全部映射到胶囊 |
| sync（四轮追平） | `sync/` | `README.md`、`RUNS.md`、`logs/`（四轮 merge + 门禁 + spec） | 每轮 merge main 后门禁与 spec 全绿，零回归 diff 逐轮留档 |
| treecheck（几何/让位链） | `treecheck/` | `README.md`、`capsule_giveway_probe.mjs`、`shots/` | 胶囊让位链（`#chat` padding ≥ 渐隐带底）与双端几何实测 |
| typefix（类型门禁） | `typefix/` | `README.md`、`01_before_check_js_types.log`、`06_postcommit_check_js_types.log` | checkJs 13 错 → 0 错，`type-baseline.json` 保持空基线（零豁免） |
| persist2（落库/跨端） | `persist2/` | `README.md`、`10_run_full.stdout.txt`、`97_final_store_artifact/` | 隔离实例 45 PASS / 0 FAIL：完成+取消完成/新建/删除/改重要度四断言 + 跨端一致 + 真源落盘 |
| accept2（上游终验） | `accept2/` | `README.md`、`30_scope.log`、`shots/`、`specs/` | `VERDICT: PASS`（七项 + 八门禁本树实测），6138 零接触 |
| **final（本收口）** | `final/` | 见 §2 | merge 到 `a0597efdd` 后的树复跑：八门禁 + Scala 全绿 |

## 4. 改前红 → 改后绿 对照表

| # | 口径 | 改前（红） | 改后（绿） | 证据 |
|---|---|---|---|---|
| 1 | 胶囊点完成是否落库 | 点完成 8→7 行，刷新回 8（回滚）；点击窗口 `client→server 帧数 = 0`，写帧 create/complete/delete/update/toggle 各 0 | 刷新后仍为已完成，真源 `"completedAt":1793791482392`、`"priority":3` | `recon/10_runtime-proof.stdout.txt` / `persist2/10_run_full.stdout.txt` / `persist2/95_handcat_store.log` |
| 2 | 数据源 | `todoStore` 261 行内存 mock + 加载即 `seed()` 8 条假待办 | 适配器（`listScheduledTasks scope:all` + 五写帧 + 广播收敛） | `store/README.md`、`05_new_spec_todo_store_backend.log` |
| 3 | 入口数 | 三入口并存（桌面胶囊 / 消息列表入口行 / 手机任务圆钮），两套数互不同步 | 单入口胶囊；`.fm-todo-entry`、`[data-chrome="tasks"]`、`#mobile-task-panel` 零命中 | `entries2/10_new_spec_before_red.log`（2 failed）→ `entries2/50_final_specs_and_shots.log`（绿） |
| 4 | 类型门禁 | `web/js/todoStore.js` 11 条 + `web/js/ws.js` 2 条 checkJs 错 | 0 错，空基线零豁免 | `typefix/01_before_check_js_types.log` → `final/logs/05_check_js_types.log` |
| 5 | 取消完成 / 改重要度 | 后端无帧（完成 = 物理删行；模型无 priority） | `uncompleteTodo` 新增；`updateScheduledTask{priority}` 字段级 patch；已完成不被 scheduler 浮出 | `backend/CONTRACT.md` §2/§5、`persist2/30_uncomplete_store.txt` |
| 6 | 门禁集合 | 本批初期只跑四道 | 八道（+ circular / js-types / design-spec-sync / process-artifacts）全 EXIT=0 | `final/logs/gates-summary.txt` |

## 5. 范围核对（收口树实测）

```
$ git diff main..HEAD --stat -- src/main/resources/web src/main/scala tests
 37 files changed, 1947 insertions(+), 435 deletions(-)
   web 15：mobileLayer.css / todo-face.css / todo-page.css / index.html / locales{zh-CN,en}.js /
           main.js / messages.js / mobile/{chatChrome,navController,taskPanel}.js /
           scheduled-task.js / todoFace.js / todoStore.js / ws.js
   tests 22：todo-capsule-create.spec.mjs(A) / todo-entry-collapse.spec.mjs(A) +
             20 个既有 spec 的入口收敛适配（cognition-phase2 / todo-split / mobile-* / liquid-glass-* 等）
   src/main/scala：空
```

**清单外出现的 diff 逐项解释**（简报清单只列了 todo-face/todoStore/mobileLayer/index/main/locales/
三个新 spec + core/scheduler/gateway）：

| 文件 | 归属 | 为什么必须在本批 |
|---|---|---|
| `web/js/messages.js` / `web/js/mobile/{chatChrome,navController,taskPanel}.js` / `web/js/scheduled-task.js` | 入口收敛（entries2） | 撤消息列表入口行 + 手机任务圆钮 + 两个导航页 = **入口收敛本身就是本批验收项**，不动这五个文件无法达成单入口 |
| `web/css/todo-page.css` | 入口收敛 | `#todo-page` 整面退役的文件头注记（`.fm-todo-entry` 规则无宿主） |
| `web/js/ws.js` | 类型门禁（typefix） | 分发器明确要求 check-js-types EXIT=0；该文件 2 条存量 TS2339 与 todoStore 11 条同批清零，空基线零豁免 |
| `tests/` 20 个既有 spec | 入口收敛适配 | 断言面从「入口行 + `#todo-page`」改写到「胶囊 + 展开面板」（badge → `.tfl-capsule-count`、tasks/todo 页面腿退役），不改成既红 |
| `src/main/scala` | **空** | 后端段（`0d9b1b874`）已由作者合并点 `941a11a13` 带上 main；本批合 main 后 Scala 面零 diff —— 与「范围只应含本批文件」一致 |

## 6. 纪律申报

- 未合 main、未 push main、未跑 reset 家族、未 kill/signal 任何 sbt/java/nebflow 进程。
- 6138（作者生产实例）全程零接触：accept2 实测 `before=[78176] after=[78176]`。
- 未改任何 baseline（`tests/type-baseline.json`、component-contract 基线、self-explaining 基线）。
- 证据目录按 2026-11-04 文档命名新制（`<topic>__<time>`）单点落地：本目录原为
  `reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/`（旧制，已被 main 的
  命名迁移批改名），收口时把旧制目录下的 accept2/entries2/panel/persist2/sync/treecheck/typefix
  全部并入本目录，旧目录已删除（内容零丢失）。
