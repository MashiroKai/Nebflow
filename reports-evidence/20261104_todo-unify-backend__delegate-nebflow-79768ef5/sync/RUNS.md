# 合后复跑读数（原始日志索引）

工作树：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`
最终 HEAD：`f70dccb9f`（第二轮追平 main `995142a5f`）
所有日志在 `logs/`，文件名前缀 = 轮次与序号。

> 轮次说明：本节点执行期间本地 `main` ref **推进了两次**。
> 第一轮追平 `d2fc65c82`（日志 10–40），第二轮追平 `995142a5f`（日志 50–60）。
> **下表以第二轮（最终树）为准**；第一轮读数保留作对照。

## 一、五道门禁（最终树）

| 门禁 | exit | 日志 |
|---|---|---|
| `make frontend-gates`（design-tokens + msgstyle + self-explaining） | **0** | `logs/50_gate_frontend_gates.log` |
| `node scripts/check-design-spec-sync.mjs` | **0** | `logs/51_gate_design_spec_sync.log` |
| `node scripts/check-component-contract.mjs` | **0** | `logs/52_gate_component_contract.log` |
| `bash scripts/check-process-artifacts.sh` | **0** | `logs/53_gate_process_artifacts.log` |

`component-contract` 首跑（`logs/12_gate_component_contract.log`）曾 **exit 1 / 16 条**，
根因为本批引入（`todoFace.js` 裸 `innerHTML` 计数 13→15），已就地修（commit `f7dba5ef9`），
**baseline JSON 零改动**。

## 二、spec（最终树）

| 用例 | 结果 | 日志 |
|---|---|---|
| `tests/todo-capsule-create.spec.mjs` + `tests/todo-store-backend.spec.mjs` | **14 passed**，exit 0 | `logs/54_spec_capsule_and_store.log` |
| `tests/todo-split.spec.mjs` | **6 passed**，exit 0 | `logs/55_spec_todo_split.log` |
| `tests/todo-face-shots.mjs` | **96 PASS / 8 FAIL**，FAIL 集与 panel 基线**逐条同名** | `logs/57_shots_todo_face.log` |
| `tests/todo-page-shots.mjs` | `SHOTS OK`，exit 0 | `logs/58_shots_todo_page.log` |

`todo-face-shots` 的 8 条红 = `swipe-down deletes tag` ×4（四引擎/主题各一）+
级联的 `desktopScenario … threw: page.click Timeout` ×4。panel 节点改前/改后两次实测
同为 **96 PASS / 8 FAIL** 且 FAIL 集相同 ⇒ **既有存量标尺问题，非本批引入，非 merge 引入**。

## 三、胶囊让位链实测（最终树）

自足探针 `yield_chain_probe.mjs`（静态路由 + 最小 WS 夹具，零后端 / 零 sbt / 零 6138），
产出 `yield_chain_probe.json` + 4 张关键帧 `yield_{desktop,mobile}_{capsule,expanded}.png`。

判据全绿（`EXIT=0`，`logs/56_yield_chain_probe.log`）：

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
