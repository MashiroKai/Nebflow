---
type: design-note
status: shipped
---

# 错误态/冻结态对话框极简化 · 规格（errorfrozen-dialog-minimal 批）

> 批依据：作者令 2026-10-29 13:51「出现错误状态以及冻结状态的时候 对话框的设计比较丑 可以优化一下 往简约的方向走 结合stand的风格 要极简」。本稿为该批的设计规格与可断言验收点（防线①→③闭环），实施与证据同批落盘（见文末附注）。视觉上位法 = [[visual-style]] 铁律（弹窗禁 overlay 遮罩、面板毛玻璃、低调克制专业感）+ ui-status-redesign 既有极简范式（中性字 + 左缘 2px 描边 + 图标载体）；范式参考 Apple HIG Alerts（https://developer.apple.com/design/human-interface-guidelines/alerts）与 macOS 通知横幅（单一图标点缀 + 中性底，语义色不上正文、不上大面积底色）。

## 0. 一句话目标

错误态/冻结态的全部对话框与浮层收敛为「一行说明 +（至多）一个主操作」的静音形态：语义色只留一处点缀（图标或 2px 描边），装饰层（光环/发光/脉冲/光晕/大色块）全部退役，色相即状态。

## 1. 盘点清单（浮层现状 → 处置）

| # | 面 | 载体 | 处置 |
|---|---|---|---|
| 1 | 调度冻结输入条 | `#input-bar.frozen` / `.fa-input-bar.frozen`（input.css） | **减法**：ring/glow/光晕退役 |
| 2 | 错误恢复输入条 + 条带 | `.frozen-error` + `.error-recovery-strip`（input.css + errorRecovery.js） | **减法**：ring/glow/光晕/脉冲退役；按钮收敛文本 chip |
| 3 | 升级决策卡 | `.error-escalation-card`（chat.css + errorRecovery.js） | **重材质**：琥珀大底 → 中性 etched 底 + 琥珀左缘 2px 描边 |
| 4 | 会话过期横幅 | `#stand-session-gate`（sessionGate.js） | **去红**：实色红条 → 深霜玻璃 chip + 红图标点缀 |
| 5 | 子代理冻结 tile | `.flow-agent-footer.frozen[.error]`（flowAgentPopup.js POPUP_CSS） | **减法**：tile 外发光/状态点光环/图标脉冲退役 |
| 6 | 错误 toast | `.nebflow-toast-*`（modal.js + modal.css） | 盘点不改动（2026-09-03 毛玻璃重设计已达标态） |
| 7 | 断连横幅 | `#conn-banner`（ws.js + liveness.css） | 盘点不改动（已达标态：玻璃 + warn 图标） |
| 8 | 失败告警行 | `.bgt-error`（bgTaskOutputPopup.js） | 盘点不改动（ui-status-redesign 极简范式原样） |
| 9 | 接管/删除/冲突模态 | `#takeover-box` / `#delete-box` / `#conflict-box` | 盘点不改动（状态对话框非错误态；玻璃族达标） |
| 10 | 陈旧标签页提示 | `staleTabHint.js` | 盘点不改动（非错误态；其深霜 chip 材质反被 #4 采纳为范式） |
| 11 | /model 面板加载失败、onboarding 错误行、mic-orb 错误态、updateCard（friends 面板） | 各自模块 | 盘点不改动（已达标态/越界面） |

移动端零占用：mobile.css / mobileLayer.css 对上述面零覆盖（grep 证），无在飞手机端批冲突。

## 2. 减法规则（本批逐面落点）

1. **装饰层退役清单**：3px frost ring（`0 0 0 3px …`）、18px 外发光（`0 0 18px …`）、tile 外发光（`0 0 12px …`）、状态点光环（dot `box-shadow` 3px 环）、placeholder 光晕（`text-shadow`）、图标脉冲动画（`error-pulse`，定义与全部消费点一并退役）、顶缘冰线提亮（`.frozen::before` 覆盖）、卡内高光/外发光（inset highlight + glow）。
2. **状态表达收敛**：输入条状态 = 1px 描边 + 淡底（sapphire/amber 色相差），材质阴影回落常态玻璃卡三件套——「轮廓不再变化，只有色相变化」。
3. **信息层级**：一行说明（placeholder / strip 文本 / 卡标题）+ 一句原因（meta）+（至多）一个主操作（amber 字 chip）+ 静音次级出口（中性字 chip，hover 才起淡底）。三出口的升级卡（重启并续跑/取消任务/继续等待）为真实退出路径，全部保留但收敛为文本 chip——「至多一个主操作」约束的是**强调层级**，不裁真实功能。
4. **错误色预算**：每面至多一处语义色载体——输入条 = 描边+底纹（图案载体）；条带/卡 = 图标 + 2px 左缘描边；横幅 = 行首红图标。正文字恒中性（`--color-text*` / `--status-word`）。

## 3. 设计 token（全量既有，零新色值）

`--sapphire`（91 127 191）/ `--amber`（255 152 0，暗档 255 168 60）/ `--glass-etched-bg|border` / `--color-text-muted` / `--status-word` / `--status-error-icon`（#d64545，暗档 #f26b6b）/ `--color-frame-hover`。rgb-triplet tint 组合语法与 sapphire.css 同源；无新增 token、无新增字面色值（红仅 #b62324 退役删除）。

## 4. 交互状态机（不变项）

冻结条「跳过本次」/ 错误条「立即重试」「取消任务」/ 升级卡三出口 / 横幅自愈 reload（1.5s 留窗）——行为与 WS 契约零改动；sessionGate 契约属性（`id` / `data-session-gate` / `data-source` / `role=alert`）逐字保留。禁 overlay 遮罩铁律不受影响（本批浮层均为无遮罩浮层）。

## 5. 动效

`error-pulse` 全链退役（定义 + 输入条/卡/tile 三消费点）；玻璃材质 300ms transition 保留；`prefers-reduced-motion` 规则随消费点同步收敛。

## 6. 可断言验收点（= tests/errorfrozen-minimal.spec.mjs，39/39 PASS）

- S1 冻结条：无 ring/glow 图层；placeholder `text-shadow: none`；描边 sapphire α≤0.5；底纹 α≤0.15；结构 = placeholder 一行 + 唯一动作 chip；composer disabled。
- S2 错误条+条带：无 ring/glow；条带结构 = 1 图标 + 1 说明行 + ≤2 按钮；图标 `animation: none`；按钮 borderless 透明底（文本载体）；abandon 非红（中性字）。
- S3 升级卡：底 = `--glass-etched-bg` 逐值相等（非琥珀块）；琥珀唯一载体 = 2px 左缘描边 + 图标；无 glow/inset 高光；≤3 出口全 borderless 文本 chip；结构 ≤4 块。
- S4 会话横幅：深霜 chip（rgba(28,30,38,.92)）非实色红；唯一红 = `--status-error-icon` 图标；结构 ≤2 节点；契约属性齐全。
- S5 子代理 tile：无外发光；状态点无光环；错误图标静态。
- S6 token 纪律（源级）：ring/glow/光晕/`error-pulse`/tile 发光/`#b62324` 字面量全仓退役；红走 `--status-error-icon` token。

## 7. 边界与异常

- 旧引擎/无 CSS 场景：横幅与条带为 JS 直渲染，内联样式带 token 回退值（`var(--status-error-icon, #f26b6b)`），CSS 缺席仍可读。
- 对比度：amber 字 chip 沿用既有 `--amber` 档（亮档图形 2.2:1 为既有基线；本批未降级任何对比度——退役的全是装饰层）。

## 8. 证据与回归

- 前后对比（亮暗双模 × 7 面）：`evidence/20261029_142700_errorfrozen-dialog-minimal__delegate-nebflow-76727b4a/{before,after}/`（capture 驱动 = `tests/errorfrozen-capture.mjs`，harness = `tests/fixtures/errorfrozen-minimal-harness.html`，真实生产 CSS 链 + 真实渲染器）。
- 回归：toast-glass 27/27、session-gate 23/23、conn-banner ALL PASS、console-takeover ALL PASS、wait-timeout-frozen-timer 3/3、checkJs 触及件零错、build-web 193 源 orphans 0。

## 9. 参考链接

- Apple HIG · Alerts：https://developer.apple.com/design/human-interface-guidelines/alerts
- Apple HIG · Color（语义色克制）：https://developer.apple.com/design/human-interface-guidelines/color
- 仓内先例：ui-status-redesign 批（base.css 状态载体 token 注记）、bgTaskOutputPopup `.bgt-error`、staleTabHint 深霜 chip。
