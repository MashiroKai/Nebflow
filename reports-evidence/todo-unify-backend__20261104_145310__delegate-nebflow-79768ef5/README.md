# 证据总索引 — 待办体系统一（todo-unify-backend / 链「验：全套机械验收」）

分支 `delegate/delegate-nebflow-79768ef5` · 工作区
`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
**报告口径 = 本地 main `579af6acf`**（`git log -1 --oneline main`：`Merge branch 'delegate/delegate-nebflow-cff8a531'` = 断连风暴修复批）。
本批收口期间 main 一路推进，共合四轮：`a0597efdd`→`333d17b2d`；`9a134bd5b`→`633402681`；
`a01b24cd3`→`dd109b130`；**`579af6acf` → `f17442426`（终轮，`git rev-list --count HEAD..main` = 0）**。
收口节点 = 收尾（land）节点，**未合 main / 未 push / 未重启任何进程**。

### merge 口径（逐文件，谁让谁）

| 文件 | 取谁 | 为什么 |
|---|---|---|
| `css/todo-face.css` `.tfl-list` padding | 本批（加号让位）+ main 令牌 | 加号让位是本批功能（`todo-capsule-create` ①⑧ 判据）；main `--ds-space-*` 是等值迁移 ⇒ 合成 `calc(var(--ds-space-1h) + var(--tfl-fab-size) + var(--ds-space-1))`，像素等值 |
| `web/js/ws.js` | **main 逻辑 + 本批类型标注重新 applicate** | main「断连风暴修复批」抽 `wsBackoff.js`（`attemptsAfterClose` / `connOpenedAt`）是更新的正确逻辑；本批 Typefix 的 `Gate1Result`/`Gate2Result` typedef 与 `/** @type {...} */` 收敛 cast 重新贴回同一文件（自动合成零冲突）⇒ `check-js-types` 仍 EXIT=0 |
| `src/main/scala/**`（含 `GatewayMain.scala`） | main（已含本批） | 本批 `AskTodoBridge` 广播接线已由作者合并点 `941a11a13` 带上 main ⇒ 本批 Scala 面**零 diff**，分发器预期的 `GatewayMain` 冲突未发生 |
| 其余 web/tests 本批面 | 本批 hunk，main 其余全收 | 本批 = 入口收敛 / 数据面换血 / 胶囊交互；main = 令牌等值迁移 + 命名迁移，均不相交 ⇒ `git diff main..HEAD` 只列本批 hunk |
| `scripts/process-artifacts-allowlist.txt`、`tests/type-baseline.json`、各门禁基线 | main（零改动） | 门禁面禁改；sha256 与内容双证见 §2 |

## 1. 结论（三行）

1. 待办只有一个可见入口 = 顶带待办胶囊；数据真源 = `Schedule/kind=todo` + `ScheduledTaskStore`（软删 `completedAt`）。
2. 本收口树上 **8 道前端门禁 EXIT=0**、Scala 编译 + 待办 spec 见 `final/logs/08_*.log`；
   `tests/type-baseline.json` **零改动**（`git diff main..HEAD` 为空）。
3. 范围核对：`git diff main..HEAD --stat -- src/main/resources/web src/main/scala tests` = **37 文件**
   （web 15 + tests 22；Scala 侧 = 0 —— 后端段已由作者合并点 `941a11a13` 带上 main）。

## 2. 收口节点新增证据（`final/`，五轮复跑：`01..09` = main `a0597efdd` 树；`r2_*` = `9a134bd5b`；`r3_*` = 改截图落点树；`r4_*` = `a01b24cd3`；**`r5_*` = 终树 main `579af6acf`（含 `ws.js` 退避重写）**）

> 八道门禁在**每一轮**都是本节点在自己这棵树上跑出来的绿；报告读数以 `r5_*` 为准。
> `r5` 汇总：`final/logs/gates-summary-r5.txt`（6 条命令全 EXIT=0）+ `r5_01..r5_06_*.log`；
> 基线零改动：`r5_07_baseline_diff.txt`（type-baseline diff 0 行且内容仍 `{"total":0,"files":{}}`；allowlist sha256 与 main 同值）；
> 范围：`r5_08_scope.txt`（37 文件，`src/main/scala` / `src/test/scala` **空**）；
> 批 spec：`r5_10_batch_specs.log`（22 passed）；**Scala（终树实测）**：
> `r5_09_sbt_compile_and_todo_specs.log` ⇒ `compile [success]` + `Passed: Total 52, Failed 0, Errors 0` + `SBT_EXIT=0`
> （入口 `~/.stand/bin/sbt` 自报 `[sbt-gate]`；`ops/bin/sbt-gate` 已不在仓内）。
> 注：`r4_09_*.log` 的 `SBT_EXIT=2` ≠ 测试红 —— 那是 `sbt-gate` 包装脚本在 900s 等锁超时分支自身的
> shell 语法错（`~/.stand/bin/sbt:202`），`sbt` 根本没被执行；锁空出后重跑即 `r5_09`（绿）。

| 文件 | 内容 / 读数 |
|---|---|
| `final/logs/gates-summary.txt` | 八道门禁一行一读数（EXIT 汇总） |
| `final/logs/01_make_frontend_gates.log` · `r2_01_…` | `make frontend-gates`（design-tokens + msgstyle + self-explaining + circular）两轮均 EXIT=0 |
| `final/logs/02_check_circular.log` · `r2_02_…` | `circular-deps PASS: 236 files, acyclic` EXIT=0 |
| `final/logs/03_check_component_contract.log` · `r2_03_…` | `PASS 五条组件契约零新增`（TOTAL=937，R1/R4=0）EXIT=0 |
| `final/logs/04_check_design_spec_sync.log` · `r2_04_…` | `PASS —— spec 表与令牌源逐条同值（39 条对位）` EXIT=0 |
| `final/logs/05_check_js_types.log` · `r2_05_…` | `checkJs: 0 errors (baseline 0, zero-gate files: 3)` EXIT=0 |
| `final/logs/06_check_process_artifacts.log` · `r2_06_…` | `git ls-files evidence/ reports/ docs/ = 0 违规（0 个被白名单豁免；清单 0 条生效）` EXIT=0 |
| `final/logs/07_type_baseline_check.txt` · `r2_08_…` | `git diff main..HEAD -- tests/type-baseline.json` = **空**（零豁免机械证明） |
| `final/logs/r2_07_allowlist_check.txt` | `scripts/process-artifacts-allowlist.txt` 与 main **逐字节相同**（sha256 `ea95c18b4c0b8261951e6502098377549c081ecfb3c81c859adb82f1b31ec90e`，diff 空） |
| `final/logs/08_sbt_compile_and_todo_specs.log` | 第一轮（含 `scalafmtCheckAll`）`SBT_EXIT=1` —— **红点在 main 侧既有文件**（`PerceptionService.scala:1211` / `PerceptionDaemonLifecycleSpec.scala:62` 等），`compile` 本身 `[success]`；本批 `src/main/scala` / `src/test/scala` 相对 main **零 diff** |
| `final/logs/r2_09_sbt_compile_and_todo_specs.log` | **Scala 真绿证据**：`sbt -batch compile "testOnly <6 待办 spec>"` ⇒ `compile [success] Total time: 19 s` + `Passed: Total 52, Failed 0, Errors 0, Passed 52` + `SBT_EXIT=0`（入口 = `~/.stand/bin/sbt`，脚本自报 `[sbt-gate]`；`ops/bin/sbt-gate` 已不在仓内） |
| `final/logs/09_postmerge_batch_specs.log` · `r2_10_…` | 本批四 spec 复跑：`22 passed` / `SPECS_EXIT=0`（store 6 + capsule-create 8 + entry-collapse 2 + split 6） |

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

## 7. 已并入 main（手动合并回执 · 收口后补记）

- main `579af6acf` → **`9ea4b3748 Merge branch 'delegate/delegate-nebflow-79768ef5'`**（`--no-ff`，双亲 `579af6acf` + `1eacdfd2b`，自动合并零冲突）。
- 分支已全量入 main：`git rev-list --left-right --count main...delegate/delegate-nebflow-79768ef5` = `1  0`。
- 合并后 main 树上复核：产品范围仍 **37 文件**（web 15 + tests 22），`src/main/scala` / `src/test/scala` **零 diff**；`tests/type-baseline.json` 仍为 `{"total":0,"files":{}}`（空基线、零豁免）。
- 手动合并原因：merge-queue 放行投递通道为进程内闭包、实例重启后无人接（修复 `d989779a8` / `ee415538b` 已在 main，需重启生效），按队列自带替代路径执行。
- 细节见 `final/MERGE-LANDED.md`（含重启后自检四条）。
- 本条与 `final/MERGE-LANDED.md` **留在工作树未提交**：分支已全量并入 main，再提交会使分支重新分叉（ahead=1）需再走一轮合并；纯证据、零产品代码，可随时一条提交并入。

