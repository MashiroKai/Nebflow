# treecheck —— 验：全套机械验收（入口收敛/落库/跨端/动画/门禁/截图）

接手节点取证（todo-unify-v2 链 · 2026-11-04）。坐标：worktree `delegate-nebflow-79768ef5`，
分支 `delegate/delegate-nebflow-79768ef5`。本目录只放**读数、探针与日志**，不改产品实现。

## 0. 收束态（先读这一张表）

| 事实 | 读数 |
|---|---|
| HEAD（证据提交前） | `096408f90`（证据归档）← `f70dccb9f`（再合 main）← `f7dba5ef9`（R5 修复）← `fa6526fb8`（首次合 main）← `6b883302f`（本批 panel 段） |
| `main` tip（收束时） | `995142a5f Merge branch 'delegate/delegate-nebflow-1d300d2e'` |
| `git merge-base --is-ancestor main HEAD` | **成立**（`main` 已是 HEAD 的祖先 ⇒ 范围核对无漂移噪声） |
| `git merge-base HEAD main`（接手时） | `d2fc65c828960595c81dd3cdffdcad7e37b028e6` |

会话内现场两次变动（都记录在案，判定口径以**收束态**为准）：

1. **`main` 被作者前移两次**：`d2fc65c82` → `1bfe6892d` → `995142a5f`。
   前移 `1bfe6892d` 那一步曾把 `web/js/refModel.js` / `web/js/mobile/navEvents.js`
   重新带回 main（本分支相对 main 一度表现为删除这两个模块）；本分支再次合入 main
   （`f70dccb9f`）后该差集消失，收束态 `main..HEAD` 只剩本批 9 个文件。
2. **R5 修复由并行会话在接手期间落地**。接手瞬间 worktree 带一份未提交的
   `todoFace.js` 改动（把本批新增的两个 `innerHTML =` 改成 DOM API 装配）。本节点
   复算 `\binnerHTML\s*=` 命中 = 13 = 基线并跑门禁确认红转绿，随后并行会话自行提交为
   `f7dba5ef9`（与本节点核验过的 working tree 逐字节相同：`git diff -- todoFace.js` 为空）。
   **本节点未改产品代码。**

## 1. 门禁表（收束态全跑，全 EXIT=0）

| # | 命令 | exit | 日志（收束态） |
|---|---|---|---|
| 1 | `make frontend-gates` → `check-design-tokens.mjs` | 0 | `logs/20_postmerge_frontend_gates.log` |
| 2 | 同上 → `check-msgstyle-single-source.mjs` | 0 | 同上 |
| 3 | 同上 → `check-self-explaining.mjs`（27 命中 = 基线 27） | 0 | 同上 |
| 4 | 同上 → `check-circular-deps.mjs`（236 文件，无环） | 0 | 同上 |
| 5 | `node scripts/check-design-spec-sync.mjs`（56 行 spec / 37 条对位） | 0 | `logs/21_postmerge_design_spec_sync.log` |
| 6 | `node scripts/check-component-contract.mjs`（R1 0 / R2 159 / R3 113 / R4 0 / R5 667，TOTAL 939） | 0 | `logs/22_postmerge_component_contract.log` |
| 7 | `bash scripts/check-process-artifacts.sh`（0 违规，白名单 0 条生效） | 0 | `logs/23_postmerge_process_artifacts.log` |

- 任务书点名的五道前端门禁（design-tokens / msgstyle / self-explaining / design-spec-sync /
  component-contract）在上表内全部 exit 0；`make frontend-gates` 在 main 合入后还多了
  第 4 腿 `circular`（他批新增），同样 exit 0。
- 同一组命令在**再合 main 之前**也跑过一遍（`logs/06_gate_frontend_gates.log`、
  `07_gate_design_spec_sync.log`、`08_gate_component_contract.log`、`09_gate_process_artifacts.log`），
  同样全 0 —— 两套读数并存，可见合并没有改变门禁结论。

### R5 红 → 绿的前后对照（本批唯一一处红）

| 状态 | 读数 | 日志 |
|---|---|---|
| Sync 中断前 | `EXIT=1`，16 项 FAIL，全在 `js/todoFace.js`，基线 13 → 15 | `../sync/logs/12_gate_component_contract.log` |
| 接手首跑 | `EXIT=1`，17 项 FAIL（含注释里 `innerHTML =` 的文本级命中），基线 13 → 16 | `logs/01_gate_component_contract.log` |
| 修复后 | `EXIT=0`，R5 合计 667，`js/todoFace.js` 回 13 = 基线 | `logs/03_gate_component_contract_after_fix.log`、`logs/22_postmerge_component_contract.log` |

归因：本批新增两处裸 `innerHTML =` —— 右下角加号 `<i data-lucide="plus">`、
行尾删除钮 `X_SVG`。`f7dba5ef9` 改走 `lucideIcon()`（`createElement('i')` + `data-lucide`）
与 `xIconEl()`（`createElementNS` 逐属性装配；viewBox / `stroke-width=1.5` / `linecap=round` /
`fill=none` 与 `X_VIEWBOX`、`X_PATH_D` 单源逐字对应）后计数回落。
**未动基线**：`scripts/component-contract-baseline.json`、`scripts/design-token-baseline.json` 零改动。

## 2. 本批 spec（收束态，`logs/24_postmerge_specs.log`，EXIT=0）

```
node "<stand>/node_modules/@playwright/test/cli.js" test \
  tests/todo-store-backend.spec.mjs tests/todo-capsule-create.spec.mjs tests/todo-split.spec.mjs
→ 20 passed (45.2s)，EXIT=0
```

| spec | 腿数 | 覆盖 |
|---|---|---|
| `todo-store-backend.spec.mjs` | 6/6 | ① 惰性拉真源 + 后端排序权威 … ⑥ 服务端显式拒绝 ⇒ 回滚 + `console.error` |
| `todo-capsule-create.spec.mjs` | 8/8 | ① 右下角加号 34px 贴角不压末行 … ⑧ 手机 375 胶囊可见可展开、加号 44px 触摸档 |
| `todo-split.spec.mjs` | 6/6 | 含「消息列表顶部待办入口行存在」腿 —— **当前仍绿**（下一步撤入口尚未被越界执行） |

同一组合在再合 main 之前也跑过：`logs/04_specs_todo_batch.log` = 20 passed (1.3m)，EXIT=0。
全部零后端、零 sbt、零 6138（WS 夹具 + `page.route` 静态回填，不绑定任何端口）。

## 3. 范围核对（收束态，`logs/26_final_range_check.log`）

```
$ git rev-parse --short main                → 995142a5f
$ git merge-base --is-ancestor main HEAD     → main IS ancestor of HEAD
$ git diff --name-status main..HEAD -- src/main/resources/web src/main/scala tests Makefile
M  src/main/resources/web/css/mobileLayer.css
M  src/main/resources/web/css/todo-face.css
M  src/main/resources/web/index.html
M  src/main/resources/web/js/locales/en.js
M  src/main/resources/web/js/locales/zh-CN.js
M  src/main/resources/web/js/main.js
M  src/main/resources/web/js/todoFace.js
A  tests/todo-capsule-create.spec.mjs
M  tests/todo-capsule-redesign-shots.mjs
```

= **9 个文件，零清单外 diff**。两点需要解释（都不算越界）：

1. `web/js/todoStore.js` 与 `src/main/scala/**` **零 diff 是预期**：main 已含本批
   `0d9b1b874`（todoStore 后端真源适配器）段（作者合并点 `941a11a13`），故两侧同值。
2. `tests/todo-capsule-redesign-shots.mjs`（+10/−3）**在任务书清单之外但属本批**：
   6b883302f 把手机腿断言从「桌面胶囊槽位整条 `display:none`」改成
   「槽位可见 + `.tfl-capsule` 在场 + `:visible` 计数 = 1」，即 panel 段
   「手机层胶囊必须可见」新口径的机械判据，文件内注释已写明口径翻新与退役归属。

会话中段的 `main` 漂移（`1bfe6892d` 曾使 `main..HEAD` 多出 `Makefile`、
`web/js/mobile/{chatChrome,navController,searchFace,socialSearch,taskPanel}.js`、
`web/js/msgQuote.js`、`web/js/reference.js`、以及 main 侧新增的 `navEvents.js` / `refModel.js`）
已在 `f70dccb9f` 再合 main 后消失，留档见 `logs/03_range_check.log`。

## 4. 胶囊让位链实测（`logs/25_postmerge_giveway_probe.log`，18/18 PASS，EXIT=0）

探针：`capsule_giveway_probe.mjs`（只读数；零后端、零端口绑定、`page.route` 回填 `web/`、
`routeWebSocket` 供 config/sessionList）。

```
[桌面 1280×800]
  #task-list 祖先链 = task-list → top-overlays → main                        ① PASS
  胶囊（8 待办 / 1 逾期）box top=74 bottom=100 h=26，文案 "8 待办1 逾期"
  #top-overlays top=68 bottom=114 h=46    bandBottom()=114    #chat top=0
  #chat padding-top = 122（inline "122px"）= max(8, ceil(114−0)+8) = 122      ✓ 公式命中
  #chat 内容起点 122 > 胶囊下缘 100 ⇒ 不让位列叠                             ② PASS
  胶囊整体落在顶带内（capBottom 100 ≤ bandBottom 114）                       ② PASS
[观察器链] 往 #top-overlays 注入一条 ask-pending 行（该带真实产品元素 #ask-pending-bar）
  bandBottom 114 → 152.2；#chat padding 122 → 161 = ceil(152.2)+8 ⇒ 跟随 ±2px 内    ③ PASS
  变高后仍满足让位公式                                                       ③ PASS
  复位 ⇒ padding 回 122                                                      ④ PASS
[面板] 点胶囊展开（.tfl-card h=387，top=114）
  浮层语义：顶带高度不动、padding 不动（122 不变），面板顶缘贴带底             ⑤ PASS
[手机 375×812]
  body.mobile-layer=true；#task-list display=block（不是 none，旧规则已退役）
  胶囊 top=102 bottom=128 可见，`:visible` 计数 = 1；bandBottom=142 padding=150    ④ PASS
桌面 + 手机零 pageerror / console error
```

结论：`#task-list` 仍挂在 `#top-overlays`；`bandBottom()` 单一真值链是活的
（注入刺激即跟随、复位即回落）；胶囊下缘永远在 `#chat` 内容起点之上；手机层胶囊可见
（panel 段新口径），且走同一条入口链。

截图（`shots/`）：
`d-1280-capsule-giveway.png`（胶囊在位 + 让位 122）、
`d-1280-band-grown-giveway.png`（顶带变高 + 让位 161）、
`d-1280-capsule-panel-giveway.png`（展开浮层）、
`m-375-capsule-visible.png`（手机 375 胶囊可见）。

## 5. 附加回归对照：`tests/todo-capsule-redesign-shots.mjs`（非本批验收面）

`logs/10_capsule_geometry_shots.log`：**153 PASS / 27 FAIL，EXIT=1**。
结论 = **既有红，与本批 R5 修复无关**：

| 运行 | PASS / FAIL |
|---|---|
| 本节点（含 f7dba5ef9） | 153 / 27 |
| panel 节点自己的 after 跑（`../panel/logs/15_after_capsule_shots_final.log`） | 151 / 27 |
| panel 节点 after 新断言跑（`../panel/logs/17_after_capsule_shots_newassert.log`） | 152 / 28 |

- FAIL 全部落在 ⑥「展开/收起」族，外加 5 条 `page.click: Timeout` 的 leg 异常；
  **①–⑤ 几何族（宽度比 / 居中 / 行盒 / 内距 / 圆角）零 FAIL** ⇒ R5 的 SVG 装配替换
  没有改变渲染几何。
- panel 节点 README 已载明「差异全部落在 ⑥「收起」族，且改前两次同样红」，属该批遗留；
  本节点复跑出的 FAIL 集合与其 after 跑近似同名（残差为同族内的时序抖动）。

## 6. 遗留 / 待裁定

- **空态胶囊仍显**：`openCount=0` 时 `#task-list` 仍带 `has-tasks` 且胶囊在场
  （`renderTodoTaskList` 只按「挂载」置位，不看条数）。本批 diff 不含 `has-tasks` /
  `renderTodoTaskList` 逻辑（`git diff main..HEAD -- todoFace.js | grep -c has-tasks` = 0），
  结论：**既有行为，非本批引入**；未修，仅记录。
- `tests/todo-capsule-redesign-shots.mjs` 的 ⑥ 族红（见 §5）：既有债，不在本次修复范围。
- 下一步「撤入口行」尚未执行 —— `todo-split.spec.mjs` 入口行断言仍绿即为其证据。

## 7. 本目录文件

| 文件 | 内容 |
|---|---|
| `capsule_giveway_probe.mjs` | 让位链只读探针（桌面 1280 + 手机 375，含截图） |
| `logs/01`–`05` | 接手期：component-contract 首跑（红）、process-artifacts、修复后、specs、让位探针 |
| `logs/06`–`10` | 再合 main 前：五道门禁、范围核对、胶囊几何 spec |
| `logs/20`–`26` | 收束态（最终判定依据）：门禁四跑、specs、让位探针、范围核对 |
| `shots/*.png` | 让位链四帧截图 |
