# 手机小批 · 手机端 subagent/后台任务按钮改「图标常驻 + 角标计数」（作者令 2026-10-21）

作者原话：「手机端的subagent 面板按钮和后台任务按钮可能还要跟桌面端的做区分 手机端的要改为图标常驻加右上角角标数字的方式」。

## 改了什么（3 文件 + 2 测试 + 1 截图脚本）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/css/mobileLayer.css` | bg/bgagent 键在 `body.mobile-layer .mh-dd-bd` 列内作用域反转 `.hidden` 呈现语义：键本体 `display:flex` 常驻（ID 特异性压过 `.mh-key.hidden` 与 chat.css `#bg-indicator.hidden` 两条 display:none）；角标（`.bg-count`/`.bgagent-count`，② 件已立的外挂白描边徽标语言）随父级 `.hidden` 隐藏 |
| `src/main/resources/web/js/mobile/floatController.js` | 收编键时注入图标（`#bg-indicator` → `square-terminal`，`#bgagent-indicator` → `bot`，`mh-persist-ico` 类、aria-hidden）+ `data-mh-persist` 常占位声明；拆层摘除图标与声明，桌面形态零残留。附带：该文件 4 处既有 checkJs 错误随触清除（inputRo 注错型/attRow.more 型/chips 元素型） |
| `src/main/resources/web/js/mobile/headerController.js` | `observeList` 列高/stagger 同步：`data-mh-persist` 键带 `.hidden` 仍计入占位（否则列高少算两键、末键被 overflow 裁掉）。附带：memoryFace.js 1 处同类 checkJs 对位 |
| `tests/mobile-v3.spec.mjs` | T1 断言翻转：bg/bgagent 0 计数**可见**（原断言 hidden 退场）+ 注入图标在场；memory-btn 退场语义保留 |
| `tests/mobile-p6-bgstatus.spec.mjs` | T2 加角标=后台任务数（`·3`）断言；T3 seedAgent 加角标=1 断言；T4 排空后加「键常驻 + 角标隐藏 + 图标在场」三断言 |
| `tests/mobile-keybadge-shots.mjs`（新） | 前后对照截图脚本：375/430 × 空态/任务态 + 桌面对照脚（数据面 = 真实 WS `activeBgTasks`/`activeAgents` 快照帧） |

## 数据面（零新轮询、零新监听）

角标数字 = 桌面既有计数真源，WS 事件流驱动（`agentStart`/`agentDone`/`activeAgents` → `updateBgAgentIndicator`；`backgroundTaskUpdate`/`activeBgTasks` → `updateBgTasksUI`），手机层只**反转呈现语义**：桌面 `count=0` 时打的 `.hidden` 类在手机层不再藏键、只藏角标。计数语义原样：subagent 角标 = 运行中行数（2026-09-14 author ruling），后台任务角标 = 活跃 + 保留窗终态行数（桌面 §3 不变量 badge == 面板行数）。

## 默认设计点（作者可改）

角标 = 0 时**藏数不藏键**（图标保留）—— 常规平台惯例（iOS/Android badge 语言）。若要「0 时连键一起退场」，撤掉 mobileLayer.css 中两条 `display:flex` 覆盖即可回到旧行为。

## 图标选型

- 后台任务 = `square-terminal`（后台 shell/远端任务）
- 子智能体 = `bot`（navController 设置页 profile 入口同款词形）
- 取色 = 桌面胶囊色语义原样继承（`--color-primary` 绿 / sapphire）—— 与桌面「绿=后台任务、sapphire=子智能体」的既定区分一致（chat.css 同源注释）。

## 桌面端零触碰（证据）

`body.mobile-layer` 全部规则桌面永不命中；floatController/headerController 仅手机视口 import（bootstrap.js media-query 门）。桌面对照脚（1280 视口 busy 态 header）前后像素对比：唯一差异 = 两枚 `.bg-dot`/`.bgagent-dot` 脉冲动画相位（max Δ 9/765，同代码两次运行间噪声同量级），胶囊/布局零差异。

## 验收

- 截图：`shots/before/` vs `shots/after/`（keybadge-{empty,busy}-{375,430}[-col].png + keybadge-desktop-busy-header.png）。空态：两键图标常驻无角标；任务态（2 任务 + 2 子智能体）：角标「2/2」。generate: `node tests/mobile-keybadge-shots.mjs <phase>`。
- 回归：mobile-v3 11/11 ✅、mobile-p2..p12 全族（p11 一例 + p12 三例 **HEAD 上即红**，与本批无关——失败原因 = console-load 批 vendor 按需化后 harness 路由对按需 chunk 回 404 的「An unknown error occurred when fetching the script」，stash 对照复现同 4 例）✅、uifix-quad 33/0 ✅、uifix-glass 18/0 ✅、uifix3-onboarding-faces ✅。
- checkJs：触文件全清，总错误 283→277（基线只降未动）；**仓门在 HEAD 上本就红**（agentProfile/socialPanel/canvas.js 等 277>273 基线漂移，前批合并遗留，不在本批范围）。

## 注记

- 引擎静态面真源 = boot jar：本批改动候重启窗生效。
- `pending-asks-indicator`（待答胶囊）不在作者令范围，保持内容驱动显隐原样。
- 附带发现：`evidence/20261021_014642_uifix-quad/` 为 uifix-quad 回归运行的截图产物（untracked，随批提交与否均可）。
