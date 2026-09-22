# 工具面逐项对账（30 项）

本文件是**本包工具面的真源**：把完整工具面逐项映射到 Nebflow 的真实载体，并逐项给出今天的可用性。
「暂缺」不是「没有这个工具名」，而是**通道不需要发明、承载它的平台能力尚未就绪**——缺什么、补到什么程度即可用，见 `host-dependencies.md`。

## A · 逐项表

「源分级」= 工具面自带的分级（`read_only` / `t1_input` / `safety_control`），**不是本包新造的档位**；它与本文 §C 的平台分级（L1/L2/L3）是两套正交口径：前者定「只读还是写」，后者定「作用于哪一层」。
「只读注解」= 工具面对外声明的 MCP 注解（`readOnlyHint` / `destructiveHint`），按分级机械派生。

| # | 工具名 | 源分级 | 只读注解 | Nebflow 载体 | 今天可用性 |
|---|---|---|---|---|---|
| 1 | `list_apps` | read_only | 只读 | Bash（文件系统 / Spotlight；`osascript` 取运行中） | **可用** |
| 2 | `open_application` | t1_input | 破坏性 | Bash `open -a` / `open -b` | **可用**（抢焦点属 L2，需理由） |
| 3 | `list_windows` | read_only | 只读 | Bash(`osascript` System Events) | **可用**（需辅助功能授权） |
| 4 | `get_app_state` | read_only | 只读 | 需平台能力（辅助功能元素树） | **暂缺**（今天最近替代：窗口清单 + 截图视觉） |
| 5 | `screenshot` | read_only | 只读 | Bash(`screencapture`) + `Read`（视觉）；对端 = `Bash(device=)` + `NEBFLOW_PULL:` | **可用**（需屏幕录制授权） |
| 6 | `zoom` | read_only | 只读 | Bash(`screencapture -R` 区域截图) | **可用**（替代形态：区域而非子图） |
| 7 | `list_displays` | read_only | 只读 | Bash(`system_profiler SPDisplaysDataType`) | **可用** |
| 8 | `switch_display` | t1_input | 破坏性 | 需平台能力（无系统自带命令行通道） | **暂缺** |
| 9 | `cursor_position` | read_only | 只读 | 需平台能力（Quartz 事件位置查询） | **暂缺**（可探：`python3 -c "import Quartz"` 是否可导入） |
| 10 | `left_click` | t1_input | 破坏性 | 需平台能力（事件注入 + 门控面） | **暂缺** |
| 11 | `double_click` | t1_input | 破坏性 | 同上 | **暂缺** |
| 12 | `triple_click` | t1_input | 破坏性 | 同上 | **暂缺** |
| 13 | `right_click` | t1_input | 破坏性 | 同上 | **暂缺** |
| 14 | `middle_click` | t1_input | 破坏性 | 同上 | **暂缺** |
| 15 | `scroll` | t1_input | 破坏性 | 同上 | **暂缺** |
| 16 | `left_click_drag` | t1_input | 破坏性 | 同上 | **暂缺** |
| 17 | `mouse_move` | t1_input | 破坏性 | 同上 | **暂缺** |
| 18 | `left_mouse_down` | t1_input | 破坏性 | 同上 | **暂缺** |
| 19 | `left_mouse_up` | safety_control | 只读 | 同上（配对收尾动作，故归安全控制档） | **暂缺** |
| 20 | `type` | t1_input | 破坏性 | 系统脚本通道（辅助功能）**存在**，但门控面未就绪 | **暂缺**（门控缺口，非通道缺口） |
| 21 | `set_value` | t1_input | 破坏性 | 需平台能力（元素属性写） | **暂缺** |
| 22 | `select_text` | t1_input | 破坏性 | 需平台能力（元素能力调用） | **暂缺** |
| 23 | `key` | t1_input | 破坏性 | 系统脚本通道**存在**，但门控面未就绪 | **暂缺**（门控缺口） |
| 24 | `hold_key` | t1_input | 破坏性 | 需平台能力（按住时长） | **暂缺** |
| 25 | `perform_action` | t1_input | 破坏性 | 需平台能力（元素语义动作） | **暂缺** |
| 26 | `request_access` | read_only | 只读 | **已换载体**：`AskUserQuestion` 引导卡（`assets/permission-card.json`），授权由使用者完成 | **可用**（形态已变，见 §B） |
| 27 | `stop_computer_control` | safety_control | 只读 | **已换载体**：本平台无控制会话对象 ⇒ 等价物 = 停手 + 不重试（SKILL §8） | **可用**（形态已变） |
| 28 | `wait` | read_only | 只读 | Bash(`sleep <秒>`) | **可用** |
| 29 | `read_clipboard` | read_only | 只读 | Bash(`pbpaste`) | **可用** |
| 30 | `write_clipboard` | t1_input | 破坏性 | Bash(`pbcopy`) | **可用**（写类，分级照走） |

**读表口径**：`可用` = 今天就有可执行命令，命令原文在 SKILL.md §3/§4；`暂缺` = 如实登记，**不得**在结果里当作已适配。

## B · 三处形态变化（换了载体，不是删了能力）

1. **`request_access` → `AskUserQuestion` 引导卡。** 原形态是「工具内申请权限」；macOS 不提供程序化授权通道，本平台也不允许节点自行提权 ⇒ 换成**引导卡 + 使用者手动授权 + 失败即收口**。这不是弱化：原形态最关键的纪律（只在失败点名时申请一次、`denied`/已失效即终止、不承诺自动续跑）逐条保留。
2. **`stop_computer_control` → 无会话对象下的收口纪律。** 原形态停的是一个常驻控制会话；本平台节点是**一次性、无跨回合会话**的，不存在可停的会话对象 ⇒ 等价语义 = 命中 SKILL §8 任一收口条件即停手，且**不得重试**。
3. **`zoom` → 区域截图。** 原形态返回子图坐标空间；本平台用 `screencapture -R` 取区域，坐标直接来自该区域图像。**纪律不变**：坐标只能来自最新一帧、只提交 `x`/`y`、不带任何附加变换。

## C · 平台分级映射（L1/L2/L3）

| 平台级 | 面 | 本工具面落点 |
|---|---|---|
| L1 | 应用级 | `list_apps` `open_application` `screenshot` `list_displays` |
| L2 | 窗口级 | `list_windows` `get_app_state` `mouse_move` `scroll`（窗口内）；抢焦点 |
| L3 | 桌面级 | `switch_display` `left_click_drag`（跨窗口）及一切坐标级注入 |

**写类一律逐条确认，不设会话级放行**（SKILL §6）。

## D · 工具面 schema 的登记（如实）

- 本工具面的**工具名与分级是完整的 30 项**（本文 §A 全列）。
- **逐工具的入参 schema 不在本包内**：源件自陈「工具 schema 是权威」，但**包里没有可分离的 schema 文件**——schema 存在于其构建产物中，而构建产物依赖一套取不到的私有运行库（见 `host-dependencies.md` §A-1）。
- ⇒ 本包提交的**工具面形态 = 登记表**（名称 + 分级 + 注解 + 载体 + 可用性）。**server 与逐工具 schema 的落点是延后项**，登记在 `host-dependencies.md`，含「补到什么程度即可用」的判定。
- 🔴 **不写指向不存在实现的文件**：没有 server 就不写「指向某个 server」的声明文件——声明一个取不到的入口等于把一个坏引用交给使用者。

## E · 宿主保护（永不开放项）

以下目标**不在本工具面的任何一条里**，且由脚本机械拒绝（退出码 `3`）：

- 宿主进程自身（服务端口监听进程、进程号经现读确认）；
- 宿主应用窗口；
- 调用者自身及其祖先链上的进程。

理由：平台已裁「对宿主操作 100% 被拒」是任何桌面操控实现批的**合并前置**，且这道拦截必须在审批流**之前**发生——使用者永远不该看到一张可以伤害宿主的卡。
