# treecheck —— 验：全套机械验收（入口收敛/落库/跨端/动画/门禁/截图）

接手节点取证（todo-unify-v2 链 · 2026-11-04）。坐标：worktree `delegate-nebflow-79768ef5`，
分支 `delegate/delegate-nebflow-79768ef5`。本目录只放**读数与日志**，不改产品实现。

## 0. 会话内现场变化（必须先读）

| 事实 | 读数 |
|---|---|
| 接手时 HEAD | `fa6526fb8`（merge main into branch） |
| 收束时 HEAD | `f7dba5ef9 fix(todo): 胶囊面两个新控件改由 DOM API 装配（component-contract R5）` |
| `main` tip（接手时） | `d2fc65c82` |
| `main` tip（收束时） | `1bfe6892d Merge branch 'delegate/delegate-nebflow-b1d91721'`（**会话内被作者前移**） |
| `git merge-base HEAD main` | `d2fc65c828960595c81dd3cdffdcad7e37b028e6` |

两件并发事实（都影响判定口径，故单列）：

1. **R5 修复由并行会话在接手期间完成**。接手瞬间 worktree 带一份未提交的 `todoFace.js`
   改动（把本批新增的两个 `innerHTML =` 改成 DOM API 装配）；本节点复算 `\binnerHTML\s*=`
   命中 = 基线 13、跑门禁核红转绿，随后该改动由并行会话自行提交为 `f7dba5ef9`
   （内容与本节点核验过的 working tree 逐字节相同：`git diff -- todoFace.js` 为空）。
   **本节点未再改产品代码。**
2. **`main` 前移导致 `main..HEAD` 差集变大**。范围核对因此给了两条腿：
   `main..HEAD`（含 main 侧他批漂移）与 `d2fc65c82..HEAD`（本批自身足迹）。

## 1. 门禁表（六道，全 EXIT=0）

| # | 命令 | exit | 日志 |
|---|---|---|---|
| 1 | `node scripts/check-design-tokens.mjs`（`make frontend-gates` 第 1 腿） | 0 | `logs/06_gate_frontend_gates.log` |
| 2 | `node scripts/check-msgstyle-single-source.mjs`（第 2 腿） | 0 | 同上 |
| 3 | `node scripts/check-self-explaining.mjs`（第 3 腿） | 0 | 同上（27 命中 = 基线 27） |
| 4 | `node scripts/check-design-spec-sync.mjs` | 0 | `logs/07_gate_design_spec_sync.log` |
| 5 | `node scripts/check-component-contract.mjs` | 0 | `logs/08_gate_component_contract.log`（R1 0 / R2 159 / R3 113 / R4 0 / R5 667，TOTAL 939） |
| 6 | `bash scripts/check-process-artifacts.sh` | 0 | `logs/09_gate_process_artifacts.log` |

- 1–3 = `make frontend-gates` 一条命令三腿，`EXIT=0`（日志首行记了命令原文）。
- **R5 曾红**：Sync 中断前的 `sync/logs/12_gate_component_contract.log` 是 `EXIT=1`、16 项
  FAIL，全部落在 `js/todoFace.js`（基线 13 → 15）：本批新增两处裸 `innerHTML =`
  （右下角加号的 `<i data-lucide="plus">`、行尾删除钮的 `X_SVG`）。
  `f7dba5ef9` 改用 `lucideIcon()`（`createElement('i')` + `data-lucide`）与
  `xIconEl()`（`createElementNS` 逐属性装配，viewBox/描边/圆头与 `X_VIEWBOX`/`X_PATH_D`
  单源逐字对应）后计数回 13 = 基线，门禁转绿。
  `logs/01_gate_component_contract.log`（接手首跑，红）与 `logs/03_gate_component_contract_after_fix.log`
  （同内容转绿）留作前后对照。
- **未动基线文件**：`scripts/component-contract-baseline.json`、`scripts/design-token-baseline.json` 零改动。

## 2. spec 读数（`logs/04_specs_todo_batch.log`，EXIT=0）

```
node "<stand>/node_modules/@playwright/test/cli.js" test \
  tests/todo-store-backend.spec.mjs tests/todo-capsule-create.spec.mjs tests/todo-split.spec.mjs
→ 20 passed (1.3m)，EXIT=0
```

- `todo-store-backend.spec.mjs` 6/6（① 惰性拉真源与后端排序权威 … ⑥ 服务端显式拒绝 ⇒ 回滚 + console.error）
- `todo-capsule-create.spec.mjs` 8/8（① 右下角加号 … ⑧ 手机 375 胶囊可见可展开、加号 44px 触摸档）
- `todo-split.spec.mjs` 6/6 —— **含「消息列表顶部待办入口行存在」腿，当前仍绿**
  （= 下一步撤入口尚未被执行节点越界改掉；红线守住）
- 全部零后端、零 sbt、零 6138（WS 夹具 + `page.route` 静态回填）。

## 3. 范围核对（`logs/03_range_check.log`）

**本批自身足迹**：`git diff --name-status d2fc65c82..HEAD -- src/main/resources/web src/main/scala tests Makefile`
= 9 个文件：

```
M web/css/mobileLayer.css   M web/css/todo-face.css   M web/index.html
M web/js/locales/en.js      M web/js/locales/zh-CN.js M web/js/main.js
M web/js/todoFace.js        A tests/todo-capsule-create.spec.mjs
M tests/todo-capsule-redesign-shots.mjs
```

对任务书清单的两点解释（都不算越界）：

1. `web/js/todoStore.js` 与 `src/main/scala/**` **零 diff 是预期**——main 已含本批
   `0d9b1b874`（todoStore 后端真源适配器）段（作者合并点 `941a11a13`）。
2. `tests/todo-capsule-redesign-shots.mjs`（+10/−3）**在任务书清单之外但属本批**：
   6b883302f 把手机腿断言从「桌面胶囊槽位整条 display:none」改成
   「槽位可见 + `.tfl-capsule` 在场 + `:visible` 计数 1」，即 panel 段「手机层胶囊必须可见」
   新口径的机械判据；文件头注释已写明口径翻新与退役归属。

**`main..HEAD` 的额外 9 项全部是 main 侧漂移，不是本批改动**：
`git diff --name-status d2fc65c82..main` 逐条对得上（`Makefile`、`web/js/mobile/{chatChrome,navController,searchFace,socialSearch,taskPanel}.js`、
`web/js/msgQuote.js`、`web/js/reference.js` 改；`web/js/mobile/navEvents.js`、`web/js/refModel.js` **新增（main 侧把这两个模块又带回来了）**）。
⇒ 待合并时须注意：本分支相对 main 表现为删除 `navEvents.js` / `refModel.js`，
若直接合入会把 main 刚恢复的两个模块再删一次，合并方需按 merger 裁定处理。

## 4. 胶囊让位链实测（`logs/05_capsule_giveway_probe.log`，18/18 PASS，EXIT=0）

探针：`capsule_giveway_probe.mjs`（只读数；零后端、零端口绑定、`page.route` 回填 `web/`）。

```
[desktop 1280×800]  #task-list 祖先链 = task-list → top-overlays → main   ① PASS
  胶囊（8 待办 / 1 逾期）: box top=74 bottom=100 h=26
  #top-overlays: top=68 bottom=114 h=46      bandBottom()=114     #chat top=0
  #chat padding-top = 122（inline "122px"）= max(8, ceil(114−0)+8) = 122   ✓ 公式命中
  内容起点 122 > 胶囊下缘 100  ⇒ 不让位列叠                                  ② PASS
[观察器链] 往 #top-overlays 注入一条 ask-pending 行（该带真实产品元素）
  bandBottom 114 → 152.2   #chat padding 122 → 161 = ceil(152.2)+8 → 跟随 ±2px 内  ③ PASS
  复位 ⇒ padding 回 122                                                     ④ PASS
[面板] 点胶囊展开（.tfl-card h=387, top=114）
  顶带高度不动、padding 不动（浮层语义），面板顶缘贴带底                      ⑤ PASS
[手机 375×812] body.mobile-layer=true, #task-list display=block（非 none）
  胶囊 top=102 bottom=128 可见，`:visible` 计数 = 1，bandBottom=142 padding=150  ④ PASS
零 pageerror / console error（桌面 + 手机）
```

截图：`shots/d-1280-capsule-giveway.png`、`shots/d-1280-band-grown-giveway.png`、
`shots/d-1280-capsule-panel-giveway.png`、`shots/m-375-capsule-visible.png`。

## 5. 遗留 / 待裁定

- `main` 在会话内前移并**恢复**了 `navEvents.js` / `refModel.js`（本分支相对 main = 删除），
  合并方须裁定是保留删除（本侧正确）还是接受 main 的恢复；本节点未动。
- 空态（`openCount=0`）下胶囊仍渲染并挂 `has-tasks`（`renderTodoTaskList` 只按挂载置位、
  不看条数）——`todoFace.js` 该段非本批改动（本批 diff 不含 `has-tasks` 逻辑），
  属既有行为，未修，仅记录。