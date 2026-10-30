---
name: visual-style
description: Nebflow 客户端 UI 视觉裁定——弹窗禁 overlay 遮罩但面板必须毛玻璃、可交互控件统一玻璃质感、中度字重、克制的专业感。Use when 修改 Nebflow 前端（src/main/resources/web/）的弹窗、按钮、气泡、侧边栏样式，或新增任何 UI 组件。
audience: nebflow-project
status: active
language: zh
---

# Nebflow Visual Style — 用户视觉裁定

用户对 Nebflow 客户端 UI 的成文视觉裁定。改 Nebflow 前端样式前必读；与本 skill 冲突的旧做法、旧记忆一律以本 skill 为准。

## 铁律

1. **弹窗（dialog/modal）**
   - 禁止 overlay 遮罩层：不要背景暗化、不要背景模糊，弹窗直接浮在界面上方
   - 面板本身必须是毛玻璃质感（`backdrop-filter: blur()` + 半透明背景），绝不许纯透明面板
   - 适用于**所有**弹窗（设置、聊天搜索、Agent 对话……），不是某个弹窗的特例
2. **可交互控件**：统一玻璃质感标准（`.glass-control`）——主发送按钮 `#send-btn` 及**全部主操作控件**用**墨族（ink family）**：亮面板近黑 `#111827` 白字、暗面板浅墨 `#e6edf3` 深字 `#0d1117`（ink-swap，值逐字取自官网 `--auth-btn-*` 双档），token 单源 `--btn-primary-*` → `--btn-ink-*`；具体数值以本项目前端 CSS 实现为准（`#send-btn` 规则）。**绿整体降级为身份/状态专用色**（终端 glyph、块光标/caret、复制成功、录音脉动、通知/运行点——非可交互控件场景保留，同官网 `--color-pixel` identity-only 裁定）；**蓝仅作 focus ring 与链接**（单源 `--btn-focus-ring`，亮 `#3b82f6` / 暗 `#60a5fa`），禁止入主操作/危险；**红仅作危险**（单源 `--color-danger`）。控件 token 按 `html[data-theme]` 三态解析（面板强制亮 / 强制暗 / 无属性回退 OS）。锚：docs/design/20261030_201846_button-system-monochrome-redesign（作者令 2026-10-30 20:09「按钮不要绿色，官网 logo 那种科技感黑白，像 SpaceX」）
   - **灰族语义登记（中性灰玻璃族）**：三处次级「发送」语义控件——dropbox 发送（`#dropbox-send-btn`）、好友消息发送（`.fm-send-btn`）、联系人验证发送（`.fm-verify-box` 内 `.glass-control`）——保持**中性灰**：值域 rest `rgba(255,255,255,0.45)`（暗档 `0.05`），四态 rest/hover/active/disabled 齐备，规则与 token（`--glass-send-*`）落本项目前端 CSS 的灰族块；**严禁并入墨族以外的任何色相族**（灰族无色相）
   - **亮档反馈用「边框 + 投影」，禁用白 alpha 填充递进**：亮底衬上白玻璃填充的 rest/hover/active 合成亮度为 253.00 / 253.33 / 254.00（Δ ≤ 0.67/255）肉眼不可辨——亮档 hover/active 只用**边框**（白 → 黑 alpha）与**投影**（外投影抬升 / inset 内陷）表达，填充值域与 rest 边框保持不动；暗档不受此限（暗底衬同组值 Δ 13–27 可辨）。落地实现 `sapphire.css` 灰族块（亮档专用 `--glass-send-shadow*`，规则限 `@media (prefers-color-scheme: light)`）
3. **字重（方案 B 中度）**：body 400 / 标题 600 / 按钮 500 / 代码 500
4. **消息气泡**：边缘自然不刻意——rgba(0,0,0,0.04) 极淡 border；注入消息（蓝色气泡）顶栏显示来源 `SOURCE · AGENT_NAME · EVENT_TYPE`（Team 消息标注 Team name），内容区不重复这些信息
5. **侧边栏激活态**：用阴影，不用竖线
6. **总基调**：低调克制、专业感优先（Apple 式简约）；弹窗、按钮、字体、配色复用既有设计语言，不造新轮子

## Evidence

裁定依据（会话记录转述）：

- 面板须为毛玻璃质感；透明面板为实施偏差（曾误把面板毛玻璃一并去掉，仅留 overlay 透明）。
- 弹窗不模糊背景：禁的是弹窗背后的 overlay 层（背景暗化/模糊），面板本身必须毛玻璃——两个层次不可混淆。
- 玻璃质感全 UI 统一、清透。
- 发送按钮用绿色，带玻璃质感、边缘立体感明显（基准后续收敛为微信绿实色族，见上）。→ **2026-10-30 20:09 作者令取代**：「按钮不要绿色，官网 logo 那种科技感黑白，像 SpaceX」——主操作全面换墨族（见铁律 2），绿降级为身份/状态色。
- 侧边栏激活态用阴影，不用竖线。
- 字重调细须提供方案供确认（收敛为方案 B 中度）。
- 注入消息（蓝色气泡）须显示内容来源；Team 消息须标注 Team name。
- 多选交互参考 Apple，设计从简。

补充语义（同轮二次澄清）：禁的是弹窗**背后的 overlay 层**（背景暗化/模糊）；面板**本身**反而必须毛玻璃。首次实施曾误把面板毛玻璃一起去掉导致透明面板（即第一条 Evidence 的纠正场景）——两个层次不可混淆。

现行基准：主操作 = **墨族**（亮 `#111827` 白字 / 暗 `#e6edf3` `#0d1117` 字，ink-swap，`--btn-primary-*` → `--btn-ink-*` 单源；微信绿实色族口径作废，绿降级身份/状态专用——终端 glyph/caret/复制成功/录音脉动/通知点）；蓝 = focus ring 与链接专用（`--btn-focus-ring`）；红 = 危险专用（`--color-danger`）；三处次级「发送」语义控件（dropbox / 好友消息 / 联系人）保持**中性灰**、只补缺失状态档，不并入任何色相族。

## 维护

- 新视觉裁定到达 → [USER-RULING] 上报 → Nebula 终审后**替换**对应铁律，不并存（用户改主意 = 最高优先级删除信号）
- 具体数值参数以本项目前端 CSS 实现为准（`.glass-control` 等类名在本项目前端资源下 Grep 可得）——本 skill 锁定语义与出处，不复制可从代码读到的东西
