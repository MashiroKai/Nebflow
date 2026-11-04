# 收口快照 · 已并入 main（手动合并回执）

> 本文件是 `land2` 收口之后补记的**合并事实**，不是门禁读数。
> 未提交到分支（见文末「为何不提交」）。

## 1. 合并事实

| 项 | 值 |
|---|---|
| main 前 | `579af6acf` |
| main 后 | `9ea4b3748` |
| 合并提交 | `9ea4b3748 Merge branch 'delegate/delegate-nebflow-79768ef5'` |
| 双亲 | `579af6acf`（main 侧） + `1eacdfd2b`（本批终 HEAD） |
| 方式 | `--no-ff`，自动合并零冲突 |
| 规模（作者读数） | 390 文件，+19155 / −435（含 main 侧漂移） |
| 合并后工作树 | 0 脏文件 |
| 分支是否全入 main | `git rev-list --left-right --count main...delegate/delegate-nebflow-79768ef5` = `1  0` |

**为何手动合并**：merge-queue 的放行投递通道是进程内闭包，只装在会话收尾那一刻；实例重启后无人接放行
（修复 `d989779a8` / `ee415538b` 早在 main，但需重启生效）。按队列自带的替代路径（merge the branch
manually，队列事后核对补记 `merged`）执行。

## 2. 合并后独立复核（本批负责人亲自跑的只读核对）

```
$ git log -1 --format='%h %p%n%s' 9ea4b3748
9ea4b3748 579af6acf 1eacdfd2b
Merge branch 'delegate/delegate-nebflow-79768ef5'

$ git diff --stat 9ea4b3748^1..9ea4b3748 -- src/main/resources/web src/main/scala tests
 37 files changed, 1951 insertions(+), 435 deletions(-)

$ git show 9ea4b3748:tests/type-baseline.json
 {"generatedBy": "scripts/check-js-types.mjs --update", "total": 0, "files": {}}

$ git status --short | wc -l
0
```

即：产品代码范围仍为 37 文件（web 15 + tests 22），`src/main/scala` / `src/test/scala` **零 diff**；
main 树上的 `type-baseline.json` 仍是**空基线**（`total:0, files:{}`），零豁免。

## 3. 本批验收读数索引（终树 = 已入 main 的那棵树，字节相同）

| 断言 | 读数 | 出处 |
|---|---|---|
| 八道门禁 | **8/8 EXIT=0** | `final/logs/r5_0[1-6]*.log`、`gates-summary-r5.txt` |
| `check-js-types` | `checkJs: 0 errors (baseline 0)` | `typefix/01_before_check_js_types.log`（改前 13 错）→ `final/logs/05_check_js_types.log`（EXIT=0） |
| Scala | `compile [success]` + `Passed: Total 52, Failed 0, Errors 0` | `final/logs/08_sbt_compile_and_todo_specs.log` |
| 本批 spec | 22 passed（store 6 + capsule 8 + collapse 2 + split 6） | `final/logs/09_postmerge_batch_specs.log` |
| 隔离实例落库 | 45 PASS / 0 FAIL | `persist2/`、`accept2/live/` |
| 入口收敛（选择器级） | 25 PASS / 0 FAIL | `accept2/40_entry_and_shots.log` |
| 6138 生产实例 | `before=[78176] after=[78176]` 未触碰 | `accept2/60_closing_state.log` |
| vault 文档校验 | `check_wiki_docs.py --project stand` **EXIT=0**（354 篇零违规） | 本文档同批落库的 `todo-unify-backend__20261104_193357__delegate-nebflow-79768ef5.md` |

## 4. 需重启生效 + 重启后自检

本批含 `src/main/scala/nebflow/{core/scheduler,gateway}` 改动 ⇒ **生产实例必须重启才生效**；本批全程未重启/未 kill。

重启后四条自检：
1. 桌面 1280×800 顶栏下方只见胶囊；消息列表**零**入口行；手机 375×812 同一胶囊可见，FAB 层只剩 `[data-chrome="search"]`。
2. 点完成 → 行退场、计数 −1 → **刷新后仍已完成**（改前红正是刷新回滚）。
3. `~/.stand/scheduled-tasks/<sessionId>.json` 该行有 `"completedAt":<ms>` 与 `"priority":<0..3>`；`~/.stand/checkin-records/done-records.jsonl` 追加一行。
4. 取消完成 → `completedAt:null` 写回、行回未完成面、`done-records.jsonl` 追加 `via:"undo"`。

## 5. 为何不提交

分支 `delegate/delegate-nebflow-79768ef5` 已在 `9ea4b3748` 全量并入 main。此刻在本分支再提交一条纯证据，会让分支与 main 重新分叉（ahead=1），需要再走一轮合并——与「不再等队列放行、直接收位」的裁定相悖。

因此本文件与 `README.md` 的 §7 追加**留在工作树未提交**：内容正确、可随时 `git add` 后一条提交并入 main（纯证据，零产品代码）。若需持久化到 main，请任选其一：
- `git cherry-pick` 这条单文件提交；或
- 让本分支继续存活到下一轮合并窗口。

本批的分支字节与 main 已在 `9ea4b3748` 逐字节一致；本文件不改变任何产品代码。
