# accept2 —— 最终验收（判定节点）证据索引

批：`todo-unify-backend` · 链「验：全套机械验收（入口收敛/落库/跨端/动画/门禁/截图）」· 节点 = **最终验收**
工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
分支 `delegate/delegate-nebflow-79768ef5` · 验证树 = HEAD `da0f3255f`（本节点**未改 `src/`**、未跑 reset 家族、未 kill/signal 任何进程）

**结论：VERDICT: PASS** —— 七项逐条全绿，八道门禁本树实测全 EXIT=0。

## 0. 本节点自跑读数总表

| 项 | 读数 | 证据 |
|---|---|---|
| 八道门禁 | 8/8 EXIT=0（含 `check-js-types` 0 errors） | `10_gates_summary.txt` + `11..19_*.log` |
| 改前红（同口径复跑） | 桌面完成→7 行、刷新回 8、点击窗口 **0 写帧**、三入口在场 | `before/10_rerun_stdout.log`（PASS 33 / FAIL 0） |
| 改后落库（隔离实例） | **45 PASS / 0 FAIL**，真源 JSON 直查 | `live/00_run_full.stdout.txt`、`live/95_handcat_store.log` |
| 本批 spec | 22 passed / 0 failed（store 6 + capsule 8 + collapse 2 + split 6） | `specs/10..13_*.log` |
| UI 套件 | dock 76 pass/0 fail、grow 14 PASS/0 FAIL、resize 2 passed | `specs/21_*`、`specs/30_*` |
| 入口收敛（选择器级） | 25 PASS / 0 FAIL（双端） | `40_entry_and_shots.log` |
| 截图 | 双端合并后 8 张（含新建流程 4 帧） | `shots/` |
| 范围 | `git status` 干净；本批 37 文件 | `30_scope.log`、`50_final_git.log` |

## 1. 门禁（本树实测，节点 brief 要求「必须有一次是你本树实测」）

命令 / exit / 日志：见 `10_gates_summary.txt`。八道 = `make frontend-gates`（design-tokens / msgstyle /
self-explaining / circular 四闸合跑）+ design-spec-sync + component-contract + process-artifacts + check-js-types。

## 2. 改前红复跑口径

`before/`：`git worktree add --detach /tmp/acc2-base 34387c557`（后端适配器提交 `0d9b1b874` 之前的树）
+ 拷贝 recon 脚本原样执行 ⇒ 读数与 recon 逐条同值（8 条 mock → 点完成 7 → 刷新回 8 → 写帧 0 →
三入口齐在：桌面入口行 / 手机左上圆钮 / 手机消息页入口行）。证明本次复跑与 recon 同环境同口径。

## 3. 改后落库口径

`live/`：`GW_PORT=8137 MOCK_PORT=19003 HOME_DIR=<tmpdir>/qa-acc2-todo-accept CLEAN=0` 跑 persist2 的
`todo-persist-live.mjs`（隔离实例 = 本树 assembly jar；**6138 全程 before=[78176] / after=[78176]**）。
真源 = `<隔离HOME>/scheduled-tasks/<sessionId>.json`（本节点手工 `cat|jq` 直查，见 `95_handcat_store.log`）。

## 4. 截图

`shots/a1..a2` 桌面 1280×800（单胶囊 / 展开+右下加号）；`b1..b4` 新建四帧（加号 → 填标题选重要度 →
提交 → 刷新仍在）；`c1..c2` 手机 375×812（单胶囊 / 展开+44px 加号）。
改前对照：`before/f1,f5,f6*.png`（本节点在基点树重拍的同样三处入口）。

## 5. 复跑

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5"
bash /tmp/acc2_gates.sh                                  # 八道门禁（脚本正文见 10_gates_summary.txt 头）
node reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/accept2/shots.mjs
GW_PORT=8137 MOCK_PORT=19003 EVID=<此目录>/live HOME_DIR=<tmpdir>/qa-acc2-todo-accept CLEAN=0 \
  node reports-evidence/20261104_todo-unify-backend__delegate-nebflow-79768ef5/persist2/todo-persist-live.mjs
```
