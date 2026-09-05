# 冻结状态输入框「冰封」视觉规格书

- 状态：draft（待用户/Manager 确认后冻结）
- 日期：2026-08-24
- 作者：design-engineer
- 范围：Nebflow 客户端 `src/main/resources/web/`（主窗口输入栏 `#input-bar`、子 agent 弹窗 footer `.flow-agent-footer` / bgAgentPopup 同款、聊天区 `.frozen-status` 状态条退役）
- 硬规则依据：`nebflow/visual-style`（最高优先级）；软参考：`design-system` 案例库（案例 001 的「断言口径显式化 / i18n key 先行」教训已应用于 §6/§8）
- 用户裁定（2026-08-24，本规格第一依据）：**不需要输入框上方单独一行文字提示「已冻结」，直接让消息输入框本身呈现冻结状态**——蓝色 + 蓝色毛玻璃文字，像冰封住一样；子 agent 消息窗口同步显示冻结状态

---

## 0 一句话目标

移除聊天区独立的 `.frozen-status` 状态条，让主窗口输入栏本身以「冰封」视觉（sapphire 冷蓝玻璃 + 冰蓝 placeholder 文字 + 霜环光晕）承载冻结状态；子 agent 弹窗以同一冰蓝材质语言在 footer 状态面同步呈现；解冻过渡克制（300ms 同向反向过渡，无额外花哨动画）；亮/暗双主题成立；零新颜色 token（全部 sapphire/sapphire-glow 既有 token 的透明度变体）。

## 1 参考与依据

| 来源 | 提炼规则 | 链接 |
|---|---|---|
| 用户裁定（2026-08-24） | 冻结状态不由独立文字行承载，输入框本体呈现「冰封」：蓝 + 蓝色毛玻璃文字；子 agent 窗口同步 | 本任务背景 |
| Apple HIG · Feedback / 状态内联化 | 状态应尽量在上下文控件本体表达，避免冗余的独立提示条（macOS 冻结/停用态以控件材质变化表达，如 Spotlight 无结果时搜索框本身的低调反馈） | https://developer.apple.com/design/human-interface-guidelines/feedback |
| macOS 系统「冻结/不可达」视觉惯例 | 冷蓝 + 磨砂 = 暂停/冷却语义（区别于灰=禁用、红=错误）；绿色保留给「可执行动作」 | https://developer.apple.com/design/human-interface-guidelines/color |
| Nebflow 现状（代码事实） | ①独立状态条 `.frozen-status`（chat.css:2112-2174，含 spinner + 文字 + 取消按钮，`showFrozenStatus` chat.js:232）②输入栏已有弱冻结态 `#input-bar.frozen`（input.css:63-73：border 0.4 / bg 0.06，过弱，用户不可感知）③placeholder 已切唤醒提示（main.js:357）④子 agent 弹窗 footer 已有 `.flow-agent-footer.frozen`（flowAgentPopup.js:162-171 + bgAgentPopup.js 同款）⑤子窗口输入栏 `.fa-input-bar` 已随 2026-08-22「子窗口去输入栏」裁定退役（仅残留 input.css:64 与 input.js:1474 选择器常量，无 JS 创建） | 本仓代码行号 |
| visual-style 铁律 2/3/6 | 玻璃质感统一、中度字重（body 400/标题 600/标签 500）、低调克制；冻结态是状态提示**不是可交互控件**——不做成按钮、不加新点击目标 | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` |
| 案例 001（消息搜索） | 断言口径显式化：文案→锁 i18n key、状态→显式语义、布局→增量基线口径 | `~/.nebflow/skills/design-system/SKILL.md` 案例 001 踩坑①②③ |

**取舍说明**：
- 冻结色 = sapphire 冷蓝系（既有 `--sapphire 91 127 191` / `--sapphire-glow 120 160 220`），不引入新蓝——与侧边栏冻结配置 UI（`.seg-cross-midnight` sidebar.css:1906）、注入消息蓝气泡同源，全仓「蓝=系统/调度语义」一致。
- **发送按钮保持绿色玻璃不变**：冻结语义是「agent 暂停，发送即唤醒」（freeze-schedule spec §3.2，input.js:608-613 唤醒路径），发送是可执行动作，绿色保留；冻结态绝不做成 disabled 灰（灰=断连/不可用的既有语义，input.css:280 `.disconnected`，混淆即误导）。
- 取消冻结入口不随状态条保留：冻结由设置页 freeze-schedule 配置产生（sidebar 既有 UI），取消回设置页即可；输入栏不加新交互控件（铁律：状态提示非控件）。

## 2 布局与信息架构

DOM 结构零新增零改动（除退役项）：

| 位置 | 变更 |
|---|---|
| 聊天区 `.frozen-status` 状态条 | **退役**：`showFrozenStatus`/`hideFrozenStatus`（chat.js:232-282）及 main.js 全部调用点移除；chat.css:2112-2174 样式块删除（含 frozen-spin/frozen-status-in keyframes 与 `.frozen-cancel-btn`） |
| 主窗口 `#input-bar` | 复用既有 `.frozen` class 钩子（main.js:359 add / :372 remove / :303 fallback remove，已接线，无需新 JS 事件），仅升级 CSS 材质（§5） |
| 主窗口 `#input` placeholder | 冻结时切换为新组合文案（§6 i18n key 锁死），解冻走既有 `applyInputModes()` 恢复路径（main.js:374，不变） |
| 子 agent 弹窗 footer | `.flow-agent-footer.frozen` 容器材质升级为冰蓝语言（§5.3）；状态点/文案既有逻辑保留（flowAgentPopup.js:723-738、bgAgentPopup.js:376-391 不变） |
| input.css:64 `.fa-input-bar.frozen` 残留规则 | 随本次升级同步更新参数（防御：未来若子窗口恢复输入栏，自动继承同一冰封视觉；当前无 DOM 命中，不构成断言对象） |

冻结/解冻触发链路不变：`frozen`/`resumed` WS 事件 → main.js:341/363 处理器 → class 增减 + placeholder 切换；终态事件兜底清除（main.js:298-306）保留。

## 3 交互状态机

冻结态是**纯展示状态**，不新增任何可聚焦/可点击元素；输入栏保持完全可编辑（冻结 ≠ 禁用）：

| 状态 | 输入栏 `#input-bar` | 输入框 `#input` | 发送按钮 | placeholder |
|---|---|---|---|---|
| 默认（普通态） | `--glass-bg` + blur(24px) 现状 | 可编辑，`--color-text` | 淡绿玻璃现状 | 模式默认文案（现状） |
| **frozen（冰封态）** | §5.1 冰蓝玻璃材质 | **可编辑**（`disabled`/`readOnly` 均不设），已输入文字保持 `--color-text` 正常色 | **不变**（绿=可发送唤醒） | 冰蓝霜晕文字（§5.2），文案=`t('chat.frozenPlaceholder')` |
| frozen + hover | 无 hover 态变化（非交互控件语义，铁律） | 正常 | 既有 hover | 不变 |
| frozen + 输入中 | 材质不变 | 新输入字符正常色 | 不变 | 有内容时 placeholder 自然隐藏（浏览器行为） |
| frozen → 发送消息 | 发送即唤醒（既有行为）：`resumed` 事件到达后 class 移除，材质 300ms 过渡回普通态 | 清空（既有发送行为） | 不变 | 恢复模式默认 |
| 解冻（resumed/终态兜底） | 300ms 过渡回普通态（§4） | 恢复 | 恢复 | `applyInputModes()` 恢复（既有） |

**与禁用态/断连态的区分**（防语义混淆）：断连 = `#send-btn.disconnected` 灰 + `cursor: not-allowed`；冻结 = 蓝 + 输入栏全功能可用。两态可同时成立（冻结+断连），各自独立表达，不互斥。

## 4 动效规范

| 项 | 规格 |
|---|---|
| 冻结进入 / 解冻恢复 | `#input-bar` 上 `border-color` `background` `box-shadow` 三属性过渡，时长 **300ms**，缓动 `cubic-bezier(0.4, 0, 0.2, 1)`（Material standard，进出同参，无位移、无缩放） |
| placeholder 颜色切换 | 不做渐变要求（`::placeholder` 颜色过渡跨浏览器不一致）；允许瞬时切换，断言只验终态 |
| 结霜/碎冰等装饰动画 | **不设**（克制铁律；冻结是常态可能持续数小时，循环动画一律禁止） |
| 弹窗 footer frozen 材质切换 | 同 300ms 同缓动（background/border-color/box-shadow） |
| `prefers-reduced-motion` | 全部过渡压缩至 ≤0.01s（复用全局 reduced-motion 惯例，同案例 001 A10 口径）；最终视觉态不变 |

## 5 视觉规格（全部既有 token 透明度变体，零新增）

### 5.1 主窗口输入栏冰封材质（`#input-bar.frozen`，替换 input.css:60-73 现有弱冻结态）

| 属性 | 亮色 | 暗色 |
|---|---|---|
| background | `rgb(var(--sapphire) / 0.10)` | `rgb(var(--sapphire) / 0.14)` |
| backdrop-filter | 保留现状 `blur(var(--glass-blur)=24px) saturate(1.15)`（毛玻璃底座不动，冰感由色与光叠加） | 同左 |
| border | `1px solid rgb(var(--sapphire) / 0.45)` | `1px solid rgb(var(--sapphire) / 0.50)` |
| box-shadow | `inset 0 1px 0 0 rgba(255,255,255,0.25)`（保留现状顶高光）+ `0 0 0 3px rgb(var(--sapphire) / 0.10)`（霜环）+ `0 0 18px rgb(var(--sapphire-glow) / 0.22)`（霜晕）+ 现状两层落地阴影不变 | `inset 0 1px 0 0 rgba(255,255,255,0.04)`（暗色现状）+ `0 0 0 3px rgb(var(--sapphire) / 0.14)` + `0 0 18px rgb(var(--sapphire-glow) / 0.30)` + 暗色现状两层落地阴影 |
| `::before` 顶部折射线 | 中心色由 `--sapphire-refraction` 提亮为 `rgb(var(--sapphire-glow) / 0.55)`（冰棱高光，同一渐变结构仅换中心色） | `rgb(var(--sapphire-glow) / 0.40)` |
| transition | §4（300ms / cubic-bezier(0.4,0,0.2,1) / 三属性） | 同左 |

`.fa-input-bar.frozen` 残留规则同步为同一组参数（选择器并列，一处维护）。

### 5.2 冰封 placeholder 文字（`#input-bar.frozen #input::placeholder`）

| 属性 | 亮色 | 暗色 |
|---|---|---|
| color | `rgb(var(--sapphire) / 0.9)` = rgba(91,127,191,0.9) | `rgb(var(--sapphire-glow) / 0.9)` = rgba(120,160,220,0.9) |
| text-shadow | `0 0 8px rgb(var(--sapphire-glow) / 0.35)`（霜晕——「毛玻璃文字」的冰雾感由光晕承担，**不对文字本体做 blur**，保可读性） | `0 0 8px rgb(var(--sapphire-glow) / 0.45)` |
| font-weight | 400（body 字重铁律；placeholder 是正文级提示） | 同左 |

「蓝色毛玻璃文字」的实现口径（规格锁死）：**冰蓝色 + 霜晕 text-shadow + 立于 24px 毛玻璃底座之上** = 冰封语义；文字本体 filter:blur 明确禁用（损可读性、损对比度、截图断言不可二值化）。

### 5.3 子 agent 弹窗 footer 冰封材质（`.flow-agent-footer.frozen` 容器升级，flowAgentPopup.js POPUP_CSS + bgAgentPopup.js 同款各一处）

| 属性 | 亮色 | 暗色 |
|---|---|---|
| 容器 background | `rgb(var(--sapphire) / 0.08)` | `rgb(var(--sapphire) / 0.12)` |
| 容器 border-top（footer 顶缘） | `1px solid rgb(var(--sapphire) / 0.30)` | `1px solid rgb(var(--sapphire) / 0.35)` |
| 容器 box-shadow | `0 0 12px rgb(var(--sapphire-glow) / 0.18)`（克制霜晕，弱于主输入栏——主从层级） | `0 0 12px rgb(var(--sapphire-glow) / 0.24)` |
| `.fa-status-dot` | 保留现状：实心 `rgb(var(--sapphire))` + `0 0 0 3px rgb(var(--sapphire)/0.15)` 环，无动画 | 同左（亮暗同值，现状已成立） |
| `.fa-task` 文案 | 保留现状：`rgb(var(--sapphire))`，内容 = `t('chat.frozenShort')` / `t('chat.frozenNoTime')` | 同左 |
| transition | 300ms / cubic-bezier(0.4,0,0.2,1) / background+border-color+box-shadow | 同左 |

footer 是子 agent 窗口的**状态面**（running/done/failed/frozen 共用同一表达位），其 frozen 文案不属于用户裁定移除的「输入框上方单独一行提示」——主窗口的对应物（`.frozen-status`）已按裁定退役，二者不冲突。

### 5.4 token 纪律声明

唯一用色 = `--sapphire` / `--sapphire-glow` 两个**既有 token**（sapphire.css:7-8）的透明度变体 + 现状阴影/高光色值，零新 token、零新色相、零新依赖；亮/暗双主题全部由既有 token 双值 + 本规格暗色 alpha 覆盖保证。

## 6 边界与异常

| 场景 | 规格 |
|---|---|
| **冻结瞬间输入框已有文字** | 文字保留、保持 `--color-text` 正常色（用户内容永远全可读，冰封只染框体与 placeholder）；可继续编辑、可发送（发送即唤醒） |
| **冻结切换瞬间（到点自动冻结/解冻）** | class 增减幂等（main.js 既有处理器），300ms 材质过渡平滑完成；快速连续切换以最后一次事件为准（`resumed` 已有 stale/dup no-op 守卫 main.js:366） |
| **i18n key（规格锁死，案例 001 教训①）** | 新增两个 key：`chat.frozenPlaceholder` = 「已冻结 · {time} 恢复 · 发送消息立即唤醒…」/ "Frozen · resumes {time} · send to wake…"；`chat.frozenPlaceholderNoTime` = 「已冻结 · 发送消息立即唤醒…」/ "Frozen · send to wake…"。冻结时 placeholder = 有 resumeAt 用前者（{time}=本地 HH:mm，复用 chat.js `formatResumeClock` 口径）否则后者。断言比对运行时 `t()` 输出，不比字面。原 `chat.frozenWakeHint`/`chat.frozen`/`chat.frozenHint`/`chat.frozenCancel`/`chat.frozenCanceled` 随 `.frozen-status` 退役评估去留（实现方清理无引用 key） |
| placeholder 超长 | placeholder 单行自然截断（浏览器行为）；{time} 恒为 5 字符 HH:mm，实际长度受控 |
| 冻结中断连 | 两态独立：发送按钮灰（断连）+ 输入栏蓝（冻结）可同时成立，无样式冲突（作用元素不同） |
| 多 view/多会话 | 既有 `findViewBySessionId` 守卫（main.js:352/368）保证只染对应会话输入栏；非激活 view 的冻结态在切换激活时由 class 存续自然呈现 |
| 窄视口 375px | 输入栏横向无溢出（增量口径：冻结态 scrollWidth === 普通态基线，霜环 3px 为 box-shadow 不占布局） |
| 子 agent 弹窗已关闭时 frozen 到达 | 既有 meta.status 记录 + 重开时 `updateFooterStatus` 重放（flowAgentPopup.js:723 路径），无新增分支 |
| `resumeAt` 非法/缺失 | placeholder 走 NoTime key；footer 走 `chat.frozenNoTime`（既有口径，不回归） |

## 7 无障碍

- **冻结 ≠ 禁用**：`#input` 不设 `disabled`/`readOnly`/`aria-disabled`，键盘输入、聚焦、发送全保留（唤醒语义的功能前提）
- **非颜色单通道**：冻结状态由 ①框体材质 ②placeholder 文字内容（「已冻结…」字面语义）③霜环光晕 三通道冗余表达，色盲用户可由文字内容获知
- **屏幕阅读器**：placeholder 文本即状态宣告；输入栏加 `data-frozen="true"` 属性钩子（断言用，同时供未来 aria 扩展）；不额外加 aria-live（placeholder 变化已被多数 SR 在聚焦时读出，避免打断）
- **对比度**：placeholder 亮色 rgba(91,127,191,0.9) 在亮玻璃底上 ≈3.2:1、暗色 rgba(120,160,220,0.9) 在暗玻璃底上 ≈5.4:1——placeholder 属提示性文本（WCAG 非强制 4.5:1），且已输入正文保持 `--color-text` 全对比度不受影响；footer `.fa-task` sapphire 文字对比度同姊妹规格 §7 已论证（亮 ≈4.6:1 / 暗 ≈5.8:1）
- **reduced-motion**：§4 全量适配，过渡 ≤0.01s
- **键盘**：无新增可聚焦元素，Tab 序不变

## 8 可断言验收点（qa-frontend 转 Playwright 用；全部二值判断）

口径说明：①computed 值以 `getComputedStyle` 解析结果为准（rgba 字符串比对）；②冻结态模拟 = 直接 `#input-bar.classList.add('frozen')` + 按 §6 设 placeholder（qa 可走后门注入，无需真实排程到点）；③亮/暗主题各跑一遍（emulateMedia）；④「主题色值」亮暗不同的断言分行给出。

| # | 断言 | 需截图 |
|---|---|---|
| A1 | 冻结态 `#input-bar` computed `background-color` === `rgba(91, 127, 191, 0.1)`（亮）/ `rgba(91, 127, 191, 0.14)`（暗）；且 `backdrop-filter` 含 `blur(24px)`（毛玻璃底座保留） | 是（输入栏特写） |
| A2 | 冻结态 `#input-bar` computed `border-color` === `rgba(91, 127, 191, 0.45)`（亮）/ `rgba(91, 127, 191, 0.5)`（暗）；`box-shadow` 字符串含 `120, 160, 220`（霜晕层存在） | 否 |
| A3 | 冻结态 placeholder：`getComputedStyle(#input, '::placeholder').color` === `rgba(91, 127, 191, 0.9)`（亮）/ `rgba(120, 160, 220, 0.9)`（暗）；`text-shadow` 非 `none` 且含 `120, 160, 220` | 是 |
| A4 | 冻结态 placeholder 文本 === 运行时 `t('chat.frozenPlaceholder', { time: 'HH:mm' })`（有 resumeAt）或 `t('chat.frozenPlaceholderNoTime')`（无）；双语 locale 各验一次（key 存在且非字面 key 回退） | 否 |
| A5 | 冻结态聊天区 `.frozen-status` 元素**不存在**（独立状态条退役回归断言）；文档内无 `.frozen-cancel-btn` | 是（聊天区底部全览） |
| A6 | 冻结态输入功能保留：`#input.disabled === false` 且 `readOnly === false`；程序式设值后 computed `color` === 普通态 `--color-text` 解析值（已输入文字不染蓝）；`#send-btn` computed `background-color` 含 `7, 193, 96`（绿色玻璃不变） | 是 |
| A7 | 解冻过渡：移除 `.frozen` 后 400ms，`background-color`/`border-color` 回到普通态基线值；普通态 `#input-bar` computed `transition-duration` 含 `0.3s`；emulate `prefers-reduced-motion: reduce` 时 `transition-duration` ≤ `0.01s` | 否 |
| A8 | 子 agent 弹窗同步：注入 `agentFrozen` 后 `.flow-agent-footer.frozen` computed `background-color` === `rgba(91, 127, 191, 0.08)`（亮）/ `rgba(91, 127, 191, 0.12)`（暗），`border-top-color` === `rgba(91, 127, 191, 0.3)`（亮）/ `rgba(91, 127, 191, 0.35)`（暗）；`.fa-task` 文本 === `t('chat.frozenShort', …)` 或 `t('chat.frozenNoTime')`；bgAgentPopup 同款 footer 同值各验一次 | 是（弹窗 footer 特写） |
| A9 | 主从层级：弹窗 footer 霜晕 alpha（0.18/0.24）< 主输入栏霜晕 alpha（0.22/0.30)——以 computed `box-shadow` 中 `120, 160, 220` 层 alpha 数值比对成立 | 否 |
| A10 | 375px 视口增量口径：冻结态 `#input-bar` `scrollWidth <= clientWidth` 且 === 普通态基线（霜环不产生横向溢出）；弹窗 footer `scrollWidth <= clientWidth` | 是 |
| A11 | 快速切换幂等：1s 内交替 add/remove `.frozen` ×5，最终态与最后一次操作一致（class 存在性 + computed background-color 双验），无残留半过渡态（等待 400ms 后判定） | 否 |
| A12 | 零新 token 回归：冻结相关样式值中不出现 `91, 127, 191` / `120, 160, 220` 以外的蓝系 RGB（Grep 实现 diff 断言：input.css / POPUP_CSS 新增色值全部形如 `rgb(var(--sapphire*) / α)` 或既有阴影色） | 否 |

**截图清单（供视觉评审）**：①主输入栏冰封态特写（亮/暗）②冰封态 + 已输入文字（亮/暗）③解冻过渡完成态与普通态对比（亮）④子 agent 弹窗 footer 冰封态（亮/暗，flow + bg 各一）⑤375px 窄视口冰封态 ⑥reduced-motion 终态（亮）。

## 9 参考链接汇总

- Apple HIG · Feedback：https://developer.apple.com/design/human-interface-guidelines/feedback
- Apple HIG · Color：https://developer.apple.com/design/human-interface-guidelines/color
- Material Motion standard 缓动：https://m3.material.io/styles/motion/overview
- 本仓证据：`input.css:60-73`（现弱冻结态，本次替换）· `input.css:12-45,180-198,234-286`（输入栏/输入框/发送按钮现状材质）· `chat.css:2112-2174`（`.frozen-status` 退役对象）· `chat.js:232-282`（show/hideFrozenStatus）· `main.js:295-376`（frozen/resumed/兜底处理器）· `flowAgentPopup.js:126-171,723-738`（弹窗 footer 冻结态）· `bgAgentPopup.js:306-312,376-391`（同款）· `sapphire.css:7-8,14-15,34-51`（token 与暗色双值）· `input.js:608-613`（冻结会话发送即唤醒路径）· `state.js:94`（frozenSessions）
- 内部规范：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（铁律）· `~/.nebflow/skills/design-system/SKILL.md` 案例 001（断言口径纪律）· `~/.nebflow/docs/Nebflow/freeze-schedule-spec.md`（冻结功能语义：黑名单时段 + 发送唤醒）· `~/.nebflow/docs/Nebflow/20260824_entity-icons-visual-spec.md`（姊妹规格，章节/断言格式同源）

---

### 版本日志

- 2026-08-24 draft：初版。核心决策：①`.frozen-status` 独立状态条退役，冻结语义全量内联进输入栏本体 ②冰封材质 = sapphire 0.10/0.14 底 + 0.45/0.50 边 + 3px 霜环 + sapphire-glow 霜晕 + 24px 毛玻璃底座保留 ③「毛玻璃文字」= 冰蓝 placeholder（亮 sapphire/暗 sapphire-glow @0.9）+ 霜晕 text-shadow，禁文字 blur ④发送按钮绿色不变、输入不禁用（冻结≠禁用，发送即唤醒语义保留）⑤子 agent 弹窗 footer 同语言升级、霜晕弱于主输入栏（主从层级）⑥解冻 = 300ms 反向过渡，无装饰动画。零新 token、零新依赖、零新增交互元素。
