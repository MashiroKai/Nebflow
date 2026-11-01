---
type: report
status: done
related: "[[20261028_canvas-ask-mode-design]]"
---

# ask 输入行为包批（IME 回车统一 + interrupt 状态）

> 批令（作者令 2026-11-01 逐字）：「把**文字输入框的逻辑做成一个统一包**，参考主 agent 会话窗口的输入模式」——canvas 的 ask 输入与主会话统一：①中文组词期间回车不发送（compositionstart/end 期间 Enter 只做候选确认，组词结束后的 Enter 才发送；Shift+Enter 换行等键位契约逐一对齐）；②ask 生成中加 interrupt 状态（点击即停当前回答流，已生成部分保留、可继续追问，与主会话一致）。
> 图谱：[[index]] · 词表 [[glossary]]。纪律：只动输入层；主会话行为是基准，包落地后主会话自身行为零变化（纯重构提取 + ask 接入）。

## 0. 结论先行

| 项 | 改前 | 改后 |
|---|---|---|
| ask 输入条键盘契约 | 裸 `keydown → if Enter submit()`——**组词期确认候选的回车直接发送**（作者点名的缺陷） | 与主会话同一实现：组字期全键让渡输入法、组字结束才发送；Shift+Enter 换行（input→textarea） |
| ask 生成中 | 无中断手段，只能等 done | 发送钮 ↔ 红色中断钮互换（主会话 #stop-btn 同律）；点停 → `interrupt` 帧 + 已生成部分保留 + 可继续追问 |
| 实现份数 | 主会话（input.js 内联）/ ask（无契约）各一套 | **一份**：`web/js/input/composer.js`（键盘装配 + 发送判定 + 中断状态机 + interrupt 帧单点），主会话与 ask 共用 |
| 主会话行为 | 基准 | 零变化（搬位律：同一语句同一顺序；ime-guard.spec A1–A7 全绿 + busy-lifecycle / queue-idle-flush 全绿实证） |

一句话：**键盘/IME/发送判定/中断状态机从此只有一个家（`input/composer.js` → 语义仍单源于 `imeGuard.js`），两个输入面都是它的消费者。**

## 1. 包的形状（三条腿）

`web/js/input/composer.js`（新，135 行）：

1. **键盘 / IME 组合态**——`bindComposerKeys(input, { preamble, cascade, onSend })`：装配 `imeGuard.js`（组字语义唯一来源，本包只装配不复制）+ 冻结的决策序：`preamble`（主会话 = setActiveView）→ 组字前置短路（组字期每个键都属于输入法）→ `cascade`（面级级联：mention/模式取消/斜杠/历史，消费即 true）→ `commitEnter` 裸 Enter 发送。
2. **中断状态机**——`applyTurnChrome(chrome, busy)`（主会话 setBusy/clearBusy 的按钮互换语句逐字搬入）+ `createTurnToggle(chrome)`（幂等 busy/idle 状态机，ask 消费）。
3. **interrupt 帧单点**——`requestTurnInterrupt(sessionId)`：`{type:'interrupt', sessionId}` 唯一出口（主会话 #stop-btn 与 ask .ask-stop 逐字节同帧）。

## 2. 两个消费面

- **主会话**（`input.js`）：keydown 处理器主体原样搬进 `surfaceKeydownCascade`（面级级联钩子），IME 短路与 Enter 判定交包；`view.composing` 弃用绑定对（哨兵允许清单锚定）逐字保留；`chat.js` setBusy/clearBusy 的按钮互换腿改走 `applyTurnChrome`。
- **ask**（`askDock.js`）：`bindComposerKeys(input, { onSend: submit })`；`<input>` → `<textarea rows=1>`（Shift+Enter 换行，单行视觉不变、超行封顶 120px 自动长高）；新增 `.ask-stop` 中断钮（32px 红圆白方停标，`--color-error`，主会话同语言）；busy 臂 = submit + 首个流帧（他机发问现场也臂），收口 = done/error/interrupted 终态帧 + 用户点停（本地先收、终态幂等再收）+ askState 回放见「转录已答完」+ 180s 静默看门（纯 UI 收口，不掐服务端——freezetimeout B2 同哲学）。组字期 Esc 不收起 dock（Esc 属于输入法，主会话同律）。

## 3. 验证（全绿面）

| 门/spec | 结果 |
|---|---|
| `scripts/check-js-types.mjs` / `check-ime-guard.mjs` / `build-web.mjs` | PASS（ime-guard 允许清单行号元数据随搬位刷新 1442→1698） |
| `tests/ime-guard.spec.mjs`（主会话输入回归 + IME 契约钉死） | **ALL PASS**（修好两处既有失败：G4-1/G4-3 行号元数据过期，HEAD 上即红） |
| `tests/ask-input-composer.spec.mjs`（本批新增） | **ALL PASS 56**：chromium + webkit × dark + light 四 combo 全绿——K1 组字期 Enter 不发送（拦截证据）/K2 同块候选确认不发送/K3 组字后发送/K4 英文直发/K5 Shift+Enter 换行/K6 组字期 Esc 不收 dock；I1 busy 臂/I2 部分泡在场/I3 点停 interrupt 帧出+部分保留/I4 interrupted 幂等+可续问/I5 done 自然收口；每 combo 零 pageerror |
| 实拍（验收①②证据） | `evidence/ask-input-composer/<engine>-<theme>/`：6 场景 × 4 combo PNG + manifest.json（组词态回车拦截现场、英文直发、换行、流中中断钮上位、中断后部分保留、续问完成） |
| `tests/canvas-ask-dock.spec.mjs` | 74 pass / 1 fail——唯一 fail = T12c 气泡材质，**HEAD 上原样失败**（/tmp 干净树探针实证），属气泡材质批（非输入层，本批不碰） |
| `tests/busy-lifecycle.spec.mjs` / `queue-idle-flush.spec.mjs`（主会话 busy/stop 回归） | ALL PASS |
| `tests/mobile-canvas-sheet.spec.mjs`（ask 移动档） | 10 passed |

既有红（与本批无关、未碰）：`check-circular` 两处 SCC（popArtifacts/reference/msgQuote、navController/taskPanel，main HEAD 即红）；`button-system-monochrome.spec`（钉旧墨族档，v3 绿回归批后整体过期）；`canvas-ask-dock` T12c（同上）。

## 4. 文件清单

- 新：`web/js/input/composer.js`、`tests/ask-input-composer.spec.mjs`、`evidence/ask-input-composer/**`
- 改：`web/js/input.js`（键盘契约 + stop 帧出口换包）、`web/js/chat.js`（busy chrome 换包）、`web/js/askDock.js`（包接入 + textarea + 中断钮 + 终态收口）、`web/css/ask.css`（textarea/中断钮样式）、`scripts/check-ime-guard.mjs`（行号元数据）、`tests/ime-guard.spec.mjs`（G4-3/G4-4 行号随搬位）、`tests/canvas-ask-dock.spec.mjs`（选择器 input→textarea + T6a 新按钮契约）

## 5. 设计取舍备查

- **textarea 化**：Shift+Enter 换行契约在 `<input>` 上无法成立，故换 `<textarea rows=1>`（视觉不变）；连带更新既有 spec 选择器。
- **中断帧不发新类型**：ask 会话是真实 agent 会话（sid=ask-<hash>），`interrupt` 走既有 handleInterrupt → AgentCommand.Interrupt → `interrupted` 终态帧回流（ws.js ask 前缀拦截面直达 dock），后端零改动。
- **看门只收口不掐流**：与主会话看门「到点只呈现，不发 interrupt」同一哲学；ask 形态下连呈现都不需要，只还按钮。
