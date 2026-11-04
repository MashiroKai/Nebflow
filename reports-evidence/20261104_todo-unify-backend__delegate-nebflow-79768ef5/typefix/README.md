# check-js-types 类型门禁清零（todo-unify 批 · 类型门禁修复节点）

工作区 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
分支 `delegate/delegate-nebflow-79768ef5`。起始 HEAD `8ba3fcd01`。

## 0. 红线遵守

- `tests/type-baseline.json` **零改动**（仍是 `{"total":0,"files":{}}` 零容忍空基线）——`git diff --stat tests/type-baseline.json` 为空。
- 零 `// @ts-nocheck`、零 `@ts-ignore`、零 `@ts-expect-error`（`git diff -U0 | grep '@ts-(nocheck|ignore|expect-error)'` 无命中）。
- 改动面 = 纯注释（JSDoc）+ 一处 `/** @type */ (expr)` 断言包裹；无一行运行时逻辑变更（见 §4 diff）。

## 1. 改前红（原文）

`01_before_check_js_types.log` / `02_before_tsc_raw.log`：

```
$ node scripts/check-js-types.mjs
checkJs: 13 errors (baseline 0, zero-gate files: 3)
checkJs GATE FAILURES:
  src/main/resources/web/js/ws.js: 2 errors — this file is a P2-3 core contract and must be checkJs-clean
  src/main/resources/web/js/todoStore.js: new file with 11 errors — new/touched files must be checkJs-clean
  total errors 11 > baseline 0
EXIT=1
```

**环境注记**：本工作树首次执行时 `EXIT=2`（`checkJs gate: TypeScript not found at node_modules/typescript`）——worktree 不共享主仓的 `node_modules`（`.gitignore` 第 69/90 行忽略），而 `scripts/check-js-types.mjs` 只认本树 `node_modules/typescript`（拒绝 npx 兜底）。处置：软链 `ln -s "<main>/node_modules" node_modules`（被 .gitignore 覆盖，不进 git status），此后读数与作者一致（13 错 / EXIT=1）。

## 2. 改后绿（原文）

`03_after_check_js_types.log`：

```
$ node scripts/check-js-types.mjs
checkJs: 0 errors (baseline 0, zero-gate files: 3)
checkJs PASS: no new errors above baseline.
EXIT=0
```

## 3. 逐条映射（11 + 2 → 0）

### todoStore.js（11 条，全部由本批 `0d9b1b874` 引入）

根因：行形状的 `@type` 里**没有 `completedAt`**（该字段是 `0d9b1b874` 新增的软删/撤回面），
且 `__seed` 的解构参数是裸 `= {}`（TS2525：默认 `{}` 不提供解构键的值）。

| 原读数 | TS 码 | 修法 |
|---|---|---|
| 530:61 / 532:6 / 537:10 / 552:61 / 554:6 / 559:10 | TS2551 ×6 | 行形状收敛为单点类型别名 `TodoRow`（L109-113），补 `completedAt:number|null` |
| 744:24 / 744:47（`completedItems` 排序） | TS2551 ×2 | 同上（同一别名） |
| 693:17 / 693:26 / 693:36（`function seed({tagList,itemList,order} = {})`） | TS2525 ×3 | `@param {{tagList?:Array<any>, itemList?:Array<any>, order?:string[]}} [seed]`（三个面声明为可选 ⇒ 默认 `{}` 合法） |

### ws.js（2 条，存量引入，见 §5）

| 原读数 | TS 码 | 修法 |
|---|---|---|
| 967:25 `const deviceId = g1.deviceId` | TS2339 | 新增 `Gate1Result` 类型别名（L921-927），并在 `Promise.all` 收敛点用 `/** @type {[Gate1Result, Gate2Result]} */` 断言包裹 |
| 968:21 `const port = g2.port` | TS2339 | 同上（`Gate2Result`） |

**为什么 typedef 把对侧字段写成可选**：本仓 checkJs 跑在 `strict:false`（无 strictNullChecks），
该配置下 `if (!r.ok)` 的**假分支不做判别式收敛**（falsy 收敛依赖 strictNullChecks，实测
`--strict true` 无错、`--strict false` 报错）。因此把 `ok:true`/`ok:false` 两支持有的字段互相
声明为可选，让「失败支读 reason / 成功支读 deviceId」这两句合法写法通过；运行时形状不变
（回执只带自己那侧的字段，缺省键仍是 undefined）。理由写在 L921-926 注释里。

## 4. 改动全文（`git diff -U0` 摘录：注释 + 断言包裹，无逻辑改动）

- todoStore.js：`@type {Array<{...}>}` → 具名 `TodoRow` 别名（补 `completedAt`）+ `@type {Array<TodoRow>}`；`seed` 加 `@param`。
- ws.js：新增 10 行注释/类型别名；`const [g1, g2] = /** @type {[Gate1Result, Gate2Result]} */ (await Promise.all([gate1P, gate2P]));`
  （`(expr)` 包裹对运行时零影响，求值结果与括号前逐字相同）。

## 5. ws.js 2 条的归属判决：**存量引入（近路批 `3858e3eb7`），非本批**

机械证据（`04_ws_attribution.log`）：

1. `git log --oneline -8 -- src/main/resources/web/js/ws.js` 最近一次触碰 = `3858e3eb7`
   「近路批·四道闸根因修复…闸①∥闸② 并行…」（2026-11-04 12:18），**不在**本批提交序列里。
2. `git show --stat 0d9b1b874 -- src/main/resources/web/js/ws.js` = **空**（本批 todoStore 换血提交未碰 ws.js）。
3. `git log --oneline main..HEAD -- src/main/resources/web/js/ws.js` 只列出 merge 提交 `fa6526fb8`
   （合入 main 带来的内容），**无本分支自有提交**。
4. `git merge-base --is-ancestor 0d9b1b874 3858e3eb7` = 否（两条不同批次线）；`3858e3eb7` 在 `main` 上。
5. `git log -L 963,974:.../ws.js` 显示 `+ const deviceId = g1.deviceId; + const port = g2.port;` 正是 `3858e3eb7` 引入。
6. 探针复算（`05_probe_at_3858e3eb7.log`，临时 detached worktree，用后即 `git worktree remove`）：
   在 `3858e3eb7` 树上跑同一门禁 ⇒ `ws.js: 2 errors` 同样存在（该树 `todoStore.js` 仍是旧内存态 3 错、总计 32 错）。
   ⇒ 2 条 TS2339 **先于本批存在**。

结论：本批 diff 对 ws.js 为空，2 条由近路批带进 main。**按任务书「无论归属都要修到零」已在本批内顺手修掉**（单列开放项：请 dispatcher 按 commit 归属裁定是否回写到近路批的账上）。

## 6. 门禁表（改后全跑，全 EXIT=0）

| # | 命令 | exit | 日志 |
|---|---|---|---|
| 1 | `make frontend-gates`（design-tokens / msgstyle / self-explaining / circular） | 0 | `logs/10_frontend_gates.log` |
| 2 | `node scripts/check-design-spec-sync.mjs` | 0 | `logs/11_design_spec_sync.log` |
| 3 | `node scripts/check-component-contract.mjs`（R5 bare-innerhtml 666，TOTAL 938，零新增） | 0 | `logs/12_component_contract.log` |
| 4 | `bash scripts/check-process-artifacts.sh`（0 违规） | 0 | `logs/13_process_artifacts.log` |
| 5 | `node scripts/check-circular.mjs`（236 文件无环） | 0 | `logs/14_circular.log` |

## 7. 行为零回归（`logs/15_specs.log`）

```
$ node node_modules/@playwright/test/cli.js test tests/todo-store-backend.spec.mjs tests/todo-capsule-create.spec.mjs
  14 passed (36.2s)      EXIT=0
```
store 6/6（惰性拉真源 / 完成·撤回 / 字段级 patch / 广播收敛 / `__seed` 测试缝 / 服务端拒绝回滚）
+ capsule-create 8/8（加号 / 空白建 / 防误触 / 重要度落库 / 三帧 / 动画护栏 / 手机 375）。
零后端、零 sbt、未触碰 6138。

## 8. 未做 / 开放项

1. ws.js 2 条的归属：存量（近路批 `3858e3eb7`），本批已修但**未**回写/挑拣到近路批分支——等 dispatcher 裁定。
2. 未跑 sbt / 后端测试（本节点改动面为纯注释，零逻辑变更）。
3. 未 push、未合并、未 kill / 重启任何 sbt / java / nebflow 进程。
4. 提交时工作树里另有 **5 张 `panel/*.png` 的未提交改动**（mtime 17:58，由并行节点写盘，非本节点产物）——未纳入本次提交，原样留置。
