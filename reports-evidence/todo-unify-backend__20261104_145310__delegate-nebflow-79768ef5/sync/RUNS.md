# 合后复跑读数（原始日志索引）

工作树：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
**最终验收树**：第三轮追平 main `0960a0898`（`git rev-list --count HEAD..main` = 0）
所有日志在 `logs/`，文件名前缀 = 轮次与序号。

> 轮次说明：本节点执行期间本地 `main` ref **推进了四次**（本机 `git fetch` 不通，
> main 由并发节点持续推进）。四轮分别追平 `d2fc65c82` → `995142a5f` → `0960a0898`
> → `51309ec51`，**四次 `git merge` 全部 exit 0、零冲突**。
> **下表以第四轮（最终树，日志 90–97）为准**；前三轮（10–88）保留作对照。
>
> 第四轮唯一增量 = main 的 `c00c8db47 fix(mobile-search): 裸 innerHTML 改 DOM 构建器`
> + 其合并 `51309ec51`（仅 `js/mobile/socialSearch.js` 12 行），恰好修掉第三轮我归因为
> **main 引入**的那条 `component-contract` 红 —— 追平后该门禁**转绿**，闭环见 §1.1。

## 一、门禁（最终树 51309ec51）

| 门禁 | exit | 日志 |
|---|---|---|
| `make frontend-gates`（design-tokens + msgstyle + self-explaining） | **0** | `logs/91_gate_frontend_gates.log` |
| `node scripts/check-design-spec-sync.mjs` | **0** | `logs/92_gate_design_spec_sync.log` |
| `node scripts/check-component-contract.mjs` | **0** | `logs/93_gate_component_contract.log` |
| `bash scripts/check-process-artifacts.sh` | **0** | `logs/94_gate_process_artifacts.log` |

**五道门禁全绿（最终树）。** 前三轮的两次红与处置见 §1.1。

### 1.1 `component-contract` 的两次红：一次本批、一次 main

**第一次红（本批引入，已修）**：`logs/12_gate_component_contract.log` 16 条，根因
`js/todoFace.js` 裸 `innerHTML` 计数 13→15（基线 13）。新增的两处 = 右下角加号
`.tfl-fab-add` 与行尾删除 `.tfl-del`。已就地修（commit `f7dba5ef9`）：加号走
`lucideIcon('plus')`、删除 × 走 `xIconEl()`（`createElementNS` 逐属性装配，与原
`X_SVG` 字符串逐属性等价 ⇒ 渲染不变）。**baseline JSON 一行未动。**
第一/二轮复跑均 **exit 0**（`logs/52`、`logs/14`）。

**第二次红（main 引入，不阻塞）**：第三轮追平 `0960a0898` 后为 **exit 1 / 3 条**，
全部落在 **`js/mobile/socialSearch.js`**（本批面之外）：
`[baseline-bareInnerHtml] 从基线 1 增到 2`。归因证据
（`logs/89_component_contract_main_regression_attribution.log`）：

| 判据 | 读数 |
|---|---|
| 本分支对 `socialSearch.js` 的改动 | **零**（`git diff main..HEAD --stat -- …socialSearch.js` 为空） |
| 本分支对 `component-contract-baseline.json` 的改动 | **零**（同上为空） |
| main 自己的基线值 | `js/mobile/socialSearch.js bareInnerHtml = 1` |
| main 自己的文件实际计数 | **2** |

⇒ **main tip `0960a0898` 自身在该门禁上即为红**，与合并无关；引入提交 =
`8847eb940 feat(ui): 收尾四件 … 手机搜索扁平化+收起`。按批令「是 main 引入的贴证据
单列并说明不阻塞」处置：**不在本节点修**（该文件归另一批所有）、**不动 baseline**。

**闭环**：第四轮追平时 main 已带 `c00c8db47 fix(mobile-search): 裸 innerHTML 改 DOM
构建器，component-contract 由红转绿`（`51309ec51` 合入）。追平后本树
`logs/93_gate_component_contract.log` = **exit 0**。即：该红由 main 侧另一批自行修复，
本节点全程未碰 `socialSearch.js`、未碰 baseline。

## 二、spec（最终树 51309ec51）

| 用例 | 结果 | 日志 |
|---|---|---|
| `tests/todo-capsule-create.spec.mjs` + `tests/todo-store-backend.spec.mjs` | **14 passed**（8 + 6），exit 0 | `logs/96_spec_capsule_and_store.log` |
| `tests/todo-split.spec.mjs` | **6 passed**，exit 0 | `logs/97_spec_todo_split.log` |
| `tests/todo-face-shots.mjs` | **96 PASS / 8 FAIL**，FAIL 集与 panel 基线**逐条同名**（`diff` 为空） | `logs/87_shots_todo_face.log`（第三轮树；第四轮 main 增量只动 `socialSearch.js`，与本批面零交集，未重跑 shots） |
| `tests/todo-page-shots.mjs` | `SHOTS OK`，exit 0 | `logs/88_shots_todo_page.log` |

`todo-face-shots` 的 8 条红 = `swipe-down deletes tag` ×4（四引擎/主题各一）+
级联的 `desktopScenario … threw: page.click Timeout` ×4。panel 节点改前/改后两次实测
同为 **96 PASS / 8 FAIL** 且 FAIL 集相同（`panel/logs/14_after_face_shots_final.log`），
第三轮 `diff` 亦为空 ⇒ **既有存量标尺问题，非本批引入，非 merge 引入**。

## 三、胶囊让位链实测（最终树 51309ec51）

自足探针 `yield_chain_probe.mjs`（静态路由 + 最小 WS 夹具，零后端 / 零 sbt / 零 6138），
产出 `yield_chain_probe.json` + 4 张关键帧 `yield_{desktop,mobile}_{capsule,expanded}.png`。

判据全绿（`EXIT=0`，`logs/95_yield_chain_probe.log`；JSON 见 `yield_chain_probe.json`）：

| 判据 | 结果 |
|---|---|
| 胶囊 DOM 归属 `#top-overlays` | **true** |
| 胶囊 DOM 归属 `#task-list` | **true** |
| 让位跟随带底（`padding-top` == `max(8, ⌈bandBottom − chatTop⌉ + 8)`） | **true**（四腿 delta 全 0） |
| 内容起点不高于带底（clearance ≥ 0） | **true**（四腿 clearance = 8 = BASE_TOP_PAD） |
| 展开面板 = 浮层不触发让位重排 | **true**（设计契约，见下） |
| 手机层胶囊可见（`#task-list` 非 none） | **true** |
| 零 console error | **true** |

原始几何读数：

| 腿 | bandBottom | chatTop | padding-top | expected | clearance | 胶囊 rect |
|---|---|---|---|---|---|---|
| 桌面 1440 静默 | 114 | 0 | 122 | 122 | 8 | 71.2 × 26 @ top 74 |
| 桌面 1440 展开 | 114 | 0 | 122 | 122 | 8 | （同一行） |
| 手机 375 静默 | 142 | 0 | 150 | 150 | 8 | 71.2 × 26 @ top 102 |
| 手机 375 展开 | 142 | 0 | 150 | 150 | 8 | （同一行） |

展开面板几何（证明「浮层、自胶囊向下生长、不占流内高度」）：
`position:absolute`、`data-state=open`、桌面 `top 114 / height 160.78`、手机 `top 142 / height 190.91`，
`card.top − capsule.bottom = 14`（= 槽位 `top: calc(100% + 8px)`）。
`--chat-top-clip`：桌面 `16px`、手机 `0px`（与 `main.js` 的 `mobileLayer ? 0 : …` 分支一致）。

> **口径说明**：探针首版把「展开 ⇒ 带高变大」写成判据，实测 bandBottom 不变而判 false。
> 复核设计契约（`css/todo-face.css:48-56` 与 `:231-237`：展开面板 `position:absolute`
> 锚在胶囊行下缘、自胶囊**向下生长**、**浮在聊天体之上不占流内高度 ⇒ 展开/收起不触发
> 聊天体让位重排**）后确认这是**设计如此**，故判据改为「展开后带底与让位均不变 +
> 卡片为 absolute 且在胶囊下方」。这是探针口径纠错，不是产品缺陷。

## 四、Scala 侧

见 `logs/60_sbt_batch_specs.log`（`Test/compile` + 本批 scheduler/gateway spec）。
口径：**`ops/bin/sbt-gate` 已不在产品仓**（main `3e06b7b4a` 把本机构建预算外置到
`~/.local/share/nebflow-local/bin/sbt-gate`，仓内只留指针，见 `ops/README.md`）。
本机 `which sbt` → `/Users/kaiyu/.stand/bin/sbt` → symlink 到该外部 gate，
**命令原文即 `sbt …`，已自动经闸**（日志首行有 `which sbt` 与 symlink 读数）。
