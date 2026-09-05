# AskUserQuestion × Canvas 可视化选择 交互规格书

| 字段 | 值 |
|---|---|
| 状态 | dispatch v1.0 · 用户 2026-08-18 20:07 确认方向 C，实施与 Onboarding 重设计联动（onboarding 为首个消费场景） |
| 作者 | design-engineer |
| 日期 | 2026-08-18 |
| 实现约束 | 只设计不写代码；本文档为实现唯一依据 |

## 0 一句话目标

视觉类选择题（配色/布局/风格方案）从「先看 Canvas 里的图 → 回聊天框点选项」的两步割裂，收敛为「看图即选」：选项自带预览，且 Canvas 大图内可直接点选回传。

## 1 参考与依据

> 检索记录：2026-08-18 在线检索受限（Sogou/360 触发验证码；developer.apple.com 与 m3.material.io 正文为 JS-gated 无法抓取）。以下范式条目均给出**官方规范 URL**，内容基于这些来源的既有成文规范知识；标注 ⚠ 处为检索协议教训（见 §13）。

| # | 范式 | 来源 | 适用场景 | 交互细节 | 可借鉴点 |
|---|---|---|---|---|---|
| P1 | Apple HIG · Pickers / 图像化选项 | https://developer.apple.com/design/human-interface-guidelines/pickers | 系统级取值选择 | 「选项本身是视觉对象时用图形呈现而非纯文本」（如色板、字体预览）；选中态用明确的勾选/描边而非仅靠色块变化 | 预览缩略图是**选项的一部分**，不是附属装饰；选中态必须非颜色唯一编码 |
| P2 | Apple HIG · 克制的选中反馈 | https://developer.apple.com/design/human-interface-guidelines/selection | macOS/iOS 单选/多选 | 单选立即生效可再改；多选显式勾选标记 + 确认；始终提供「取消/其他」出口 | 与 Nebflow 现有 `showOptions` 单选点选 + 多选确认模型一致，不冲突 |
| P3 | 电商变体选择器（颜色/款式 swatch） | 通例（Apple Store / Nike 等）；模式定义见 Baymard Institute: https://baymard.com/blog/product-page-layout | 颜色/款式选择 | 色板 swatch 48×48 级可点区域；选中 swatch 加外描边环；缺货 swatch 降透明+斜线但**仍可见**；swatch 下方保留文字名（颜色名不可只靠色块传达） | 配色方案选项 = swatch 条 + 文字标签双层编码；选中环用描边不用填充 |
| P4 | 设计工具方案对比（Figma variants / 画廊式 A/B 对比） | Figma Help: https://help.figma.com/hc/en-us/articles/360056440594 | 方案并列对比后定稿 | 大图并排 + 每个方案角落有「选择此方案」的显式按钮；对比区与决策区同屏 | 方向 B 的范式原型：Canvas 大图对比区内嵌显式选择按钮，看图即选 |
| P5 | WAI-ARIA APG · Radio Group / Checkbox | https://www.w3.org/WAI/ARIA/apg/patterns/radio/ · https://www.w3.org/WAI/ARIA/apg/patterns/checkbox/ | 选择控件无障碍基线 | 单选=radiogroup 语义、方向键移动、选中项 aria-checked；预览图装饰性（空 alt）不干扰读屏 | §9 无障碍条款的直接依据 |
| P6 | Nebflow visual-style 铁律（最高优先级） | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` | 全部新增 UI | 可交互控件统一 `.glass-control` 玻璃质感；字重方案 B（按钮 500）；低调克制；不造新轮子 | 选项按钮、Canvas 内选择按钮**必须**复用 `.glass-control`，禁新视觉体系 |
| P7 | Nebflow card-design 规范 | `~/.nebflow/skills/card-design/SKILL.md` | Pop/Canvas HTML 内容 | 自包含 HTML、CSS 变量配色、SVG 优先、禁 emoji、禁外部引用 | Canvas 内嵌选择页（方向 B 的 HTML 产物）必须遵守 card-design 全部禁令 |

**冲突取舍**：P3 电商 swatch 通常用大色块高饱和大图，与 P6「低调克制」冲突时以 P6 为准——预览缩略图尺寸收敛（≤64px 高），选中态用细描边而非高亮填充。理由写入本规格书即冻结。

## 2 方案评估与推荐（方向 A / B / C）

| 维度 | A：选项内嵌预览 | B：Canvas 内嵌选择按钮 | C：混合（A+B） |
|---|---|---|---|
| 解决痛点 | 部分——小图可选，大图对比仍需切 Canvas | 完整——看图即选 | 完整——小图快选 + 大图细看即选 |
| 前端改动量 | 小（chat.js 选项渲染一处） | 中（新增 postMessage 类型 + 校验 + 状态回推） | A+B 之和，但共用状态机 |
| 真相源 | 单一（showOptions） | 需防双写（卡片↔Canvas） | 单一——Canvas 按钮只是 showOptions 状态的远程触发器 |
| 多选/dependsOn | 天然兼容 | 不兼容（§3.3） | 兼容——复杂题回落到卡片 |
| 无预览降级 | 天然（无 preview 字段即现状） | 需额外设计 | 天然 |
| 风险 | 低 | 中（iframe 不可信输入校验） | 中（同 B，已给出校验方案 §3.2） |

**推荐：方向 C（混合），分两层定义：**

- **Layer 1（A，必做，基础层）**：AskUserQuestion 选项 schema 扩展可选 `preview` 字段，聊天卡片选项按钮内嵌缩略预览（swatch 色板 / 图片缩略图）。无 `preview` 时渲染与现状逐像素一致（零回归）。
- **Layer 2（B，增强层）**：Canvas HTML 内可嵌「选择此方案」按钮，经新增 `_nfAskAnswer` postMessage 回传；**仅限当前可见的单选题**；回答后父窗口回推 `_nfAskState` 锁定 Canvas 内按钮。

**不推荐纯 B 的理由**：Canvas HTML 由 agent 产出（不可信），且多选/dependsOn/Other 的状态机活在 `showOptions`，纯 B 要么分裂真相源要么砍掉既有能力。**不推荐纯 A 的理由**：聊天卡片宽度有限（~0px 会话宽度可伸缩），64px 缩略图不足以支撑布局方案级对比，大图对比是真实需求。

### 2.1 工具 schema 扩展（供 tool-engineer 对齐，非前端改动）

```jsonc
// AskUserQuestion 输入：question 级与 option 级新增可选字段
{
  "question": "选择配色方案",
  "canvas": "/abs/path/compare.html",   // 可选：问题出现时自动 Pop 到 Canvas 的对比页
  "options": [{
    "label": "方案 A · 晨雾",
    "desc": "低饱和灰绿",
    "preview": {                         // 可选；缺省 = 现状纯文本按钮
      "type": "swatch",                  // "swatch" | "image"
      "colors": ["#7fa08a", "#e8e6df"],  // swatch: 1-5 个色值
      "src": "/abs/path/thumb.svg"       // image: 本地绝对路径（SVG 优先）
    }
  }]
}
```

## 3 能力边界评估（方向 B 技术可行性）

### 3.1 现有链路勘察（源码实证，2026-08-18）

| 环节 | 位置 | 现状 |
|---|---|---|
| 问题卡片渲染 | `web/js/chat.js` `renderAskUser()` (L1622) → `showOptions()` (L1369) | 选项为纯文本按钮 `.option-btn`（label + desc）；单选点选即答、多选 checkbox + 确认；dependsOn 由 `shouldShow()/updateVisibility()` 驱动；草稿持久化 `loadAskDrafts` |
| 回答回传 | `chat.js` L1647-1650 | `ws.send({type:'askUserAnswer', sessionId, answers})`——**唯一**能解除后端 AskUserQuestion 阻塞等待的通道 |
| Canvas HTML 渲染 | `web/js/viewers/html.js` `viewHtml()` | sandbox iframe（`allow-scripts allow-same-origin allow-forms allow-popups`），srcdoc 注入主题变量 `_nfThemeVars`（父→子） |
| iframe→父窗口通信 | `web/js/cardRegistry.js` | **已有三种 postMessage 协议**：`_nfCardH`（高度上报，L76）、`_nfThemeVars`（主题下发，L382）、`_nfCardAction` accumulate/submit（L131-154） |
| ⚠ 关键限制 | `cardRegistry.js` L148-152 | `_nfCardAction submit` 走 `injectUserMessage("[Card Interaction] {...}")`——注入的是**新用户消息**，不是 `askUserAnswer`，**无法解除挂起的 AskUserQuestion 等待**。方向 B 不能直接复用此通道 |

### 3.2 结论：方向 B 可行，需新增一条消息类型（最小改动）

Canvas iframe 具备 `allow-scripts`，`parent.postMessage` 畅通，父窗口 `window` 已有 message 监听基础设施。**新增一个 postMessage 类型即可打通**，无需改 WS 协议、无需改后端：

```
iframe 内（agent 产出的 HTML 里嵌入选择按钮）：
  parent.postMessage({ _nfAskAnswer: { sessionId, questionIndex, answer } }, '*')   // Canvas sandbox iframe 的 location.origin 为 opaque "null"，targetOrigin 必须用 '*'（实测：用 location.origin 直接抛 TypeError）

父窗口（chat.js 新增一个 message listener，~20 行）：
  校验 → 该 session 存在未锁定的 AskUserQuestion 卡片
       → answer 必须是该问题 options 中的合法 label
       → 复用 showOptions 既有 answers/onConfirm 路径 → ws.send askUserAnswer
```

**安全约束（必须实现）**：`_nfAskAnswer` 只接受**当前 DOM 中存在且未锁定**的问题卡片所对应 sessionId/label；payload 中不存在的 label 一律丢弃。iframe 内容不可信（agent 产出），父窗口是唯一校验点，绝不裸转发。

### 3.3 方向 B 不能覆盖的场景（决定必须混合）

- **多选**：多选状态机（syncMulti + 确认按钮）在 `showOptions` 内，Canvas 侧按钮复刻会分裂真相源 → Canvas 直答仅限单选
- **dependsOn 分支题**：可见性由前序答案驱动，Canvas 不知道依赖状态 → 仅允许回答**当前可见**的问题
- **自由输入（Other）**：文本输入框在聊天卡片内，Canvas 不承接

## 4 布局与信息架构

**原则：复用既有结构，零新面板、零新 token（除 §7 显式新增说明）。**

### 4.1 聊天卡片内（Layer 1）

```
.option-btn（既有，不重建）
┌──────────────────────────────────────┐
│ [preview 缩略区]  方案 A · 晨雾        │  ← preview 在左，label/desc 在右
│                  低饱和灰绿（desc）     │
└──────────────────────────────────────┘
```

- preview 缩略区固定槽位：宽 56px × 高 40px（swatch 为整槽填充色条，image 为等比缩略 `object-fit:cover` + 圆角 6px）
- swatch 多色时横向均分（≤5 色），色间 1px 分隔线用 `var(--color-surface)`
- 选中态：既有 `.picked` 样式基础上，preview 槽外加 1.5px 描边环（`var(--color-primary)`），非颜色唯一编码（勾选/边框同现有逻辑）
- `canvas` 字段存在时：问题卡片标题行右侧加一个玻璃质感小按钮「查看对比图」（复用 `.glass-control`），点击/问题出现时自动 Pop 该文件到 Canvas

### 4.2 Canvas 内（Layer 2）

Canvas 内容仍是 agent 自包含 HTML（card-design 规范），选择按钮是 HTML 作者按 §11.2 片段手工嵌入的普通元素 + 一行 postMessage。**Nebflow 前端不在 Canvas 内注入任何选择 UI 控件**——只提供消息通道与状态回推。这保证 Canvas 面板职责单一（展示容器，canvas.js 头注释明确定位），不破坏既有架构。

## 5 交互状态机

### 5.1 状态表

| 状态 | 进入条件 | 出口事件 | 备注 |
|---|---|---|---|
| S0 无问题 | 初始 | 收到 askUser 消息 → S1 | 现状不变 |
| S1 问题打开 | `renderAskUser` 渲染卡片 | 点选 → S2；取消 → S5 | 若带 `canvas` 字段：自动 `openTab` 该文件到 Canvas（preview 组 tab，可复用既有 preview 机制） |
| S2 预览/看图 | 用户浏览卡片缩略图或 Canvas 大图 | 卡片点选 → S3a；Canvas 按钮点选 → S3b | 非阻塞状态，可反复进出 |
| S3a 卡片点选 | 单选 `.option-btn` click（既有路径） | 全部可见题已答 → S4 | 走既有 answers/checkAllAnswered |
| S3b Canvas 点选 | 收到 `_nfAskAnswer` postMessage | 校验通过 → 写入对应 answers 槽位 → S4；校验失败 → 丢弃停留 S2 | 仅限可见单选题（§3.3）；Canvas 直答视为该题「已答」 |
| S4 回传 | 单选题即时 / 多题时确认按钮（既有） | `ws.send askUserAnswer` 成功 → S5 | 唯一回传路径，与现状一致 |
| S5 锁定关闭 | 回答已发送 | 终态 | 卡片选项锁定（既有行为）；父窗口向所有 Canvas iframe 广播 `_nfAskState:{sessionId, answered:true}`，Canvas 内选择按钮置灰 |

### 5.2 关键转换规则

1. **双入口写同一状态**：S3a/S3b 都写入 `showOptions` 的 `answers[]` 数组——Canvas 按钮是远程触发器，不持有状态。
2. **S3b 到达时卡片已被锁定（S5）→ 静默丢弃**（竞态防护：用户同时点卡片和 Canvas）。
3. **dependsOn 隐藏某题时其 Canvas 直答无效**：父窗口校验 `shouldShow(qi)` 为 false 的 questionIndex 一律丢弃。
4. **会话切换**：AskUserQuestion 卡片绑定的 sessionId 与当前激活会话无关也能回答（沿用 L1645 `targetSid` 语义），Canvas 回传同样按 payload sessionId 定位卡片。
5. **草稿兼容**：Canvas 直答的 label 与卡片点选等价，同样进 `saveAskDraft` 路径。

## 6 动效规范

| 动效 | 触发 | 时长 | 缓动 | 说明 |
|---|---|---|---|---|
| preview 缩略图淡入 | 图片 onload | 200ms | ease-out | opacity 0→1，防加载闪烁 |
| 选中描边环出现 | 点选 | 150ms | ease-out | 仅描边过渡，无位移无缩放（克制） |
| Canvas 自动打开 | `canvas` 字段问题出现 | 沿用 canvas.js 既有 panel 展开过渡 | 既有 | 不新增动画 |
| Canvas 按钮锁定 | 收到 `_nfAskState answered` | 200ms | ease-out | opacity→0.5 + `pointer-events:none` |
| reduced-motion | `prefers-reduced-motion: reduce` | — | — | 以上全部退化为瞬时切换（@media 查询，card-design 规范既有要求） |

## 7 视觉规格

**全部引用既有 token / 类，唯一新增为一个尺寸类（§7.3，显式说明理由）。**

### 7.1 选项按钮与预览槽

| 属性 | 值 | 来源 |
|---|---|---|
| 按钮质感 | `.option-btn` 既有样式（玻璃控件体系） | 不改动 |
| preview 槽尺寸 | 56×40px，圆角 6px，`object-fit:cover` | 新增尺寸类（§7.3） |
| swatch 分隔线 | 1px `var(--color-surface)` | 既有 token |
| 选中描边环 | 1.5px `var(--color-primary)`，叠加在既有 `.picked` 之上 | 既有 token |
| 字体/字重 | label 13-14px/400；desc 12px/400 muted | 字重方案 B（visual-style 铁律 3） |

### 7.2 Canvas 内选择按钮（HTML 作者侧规范，写入 card-design 引用）

- 按钮必须使用 iframe 内注入的主题变量：`background: var(--color-surface)` + `border: 1px solid var(--color-text-muted)` 级克制描边；**禁止**高饱和实心大按钮（visual-style 铁律 6 克制基调）
- 圆角 10px、字号 13px/500（card-design 视觉默认值）
- 选中/锁定反馈由 `_nfAskState` 驱动，HTML 片段内置 5 行 listener（§11.2 给出标准片段）

### 7.3 新增 token 说明（唯一一处）

新增 `.option-preview` 尺寸类（56×40px 槽位）。**理由**：preview 槽是选项按钮内部件，现有 CSS 无对应尺寸抽象；颜色/字重/圆角全部复用既有 token，仅此几何尺寸为新增。若实现方发现可复用既有类则回填本节。

## 8 边界与异常

| # | 场景 | 行为（二值可验） |
|---|---|---|
| E1 | 选项无 `preview` 字段 | 渲染与现状逐像素一致——不出现空槽位、不改变按钮布局 |
| E2 | preview 图片加载失败（onerror / 404） | 隐藏该 preview 槽，label/desc 保留，按钮仍可点选——**绝不**显示裂图图标 |
| E3 | swatch `colors` 为空数组 | 视同无 preview（同 E1） |
| E4 | 多选题（`multiple:true`） | Canvas 直答不生效：`_nfAskAnswer` 对多选题一律丢弃；多选只能走卡片确认路径 |
| E5 | dependsOn 隐藏题 | 其 `_nfAskAnswer` 丢弃（校验 `shouldShow`）；隐藏题 preview 随卡片 `display:none` 不渲染 |
| E6 | 问题已回答（卡片锁定）后收到 `_nfAskAnswer` | 静默丢弃，不报错不重复发送 WS |
| E7 | `_nfAskAnswer` 的 answer 不在该题 options label 集合内 | 丢弃（含 Other 自由文本——Canvas 通道不接受自由文本，自由输入只走卡片输入框） |
| E8 | `canvas` 字段指向的文件不存在/读取失败 | 不阻断问题卡片；「查看对比图」按钮不渲染；控制台 warn 一条 |
| E9 | sessionId 无对应未锁定卡片（会话已删/已答） | 丢弃 |
| E10 | 超长 label/desc | 沿用既有 `.option-btn` 文本换行行为，preview 槽尺寸不随文本变化（固定 56×40） |
| E11 | 窄视口（卡片宽 <320px） | preview 槽保持 56×40，label 区压缩换行；不横向滚动 |
| E12 | 明暗主题切换 | swatch 色值是方案本身内容不随主题变；描边环/按钮质感随 `_nfThemeVars` 既有机制切换 |

## 9 无障碍

| 项 | 规范 |
|---|---|
| 键盘导航 | preview 槽不新增 Tab 停靠点——焦点仍在 `.option-btn` 本身，Enter/Space 点选（沿用既有 button 语义） |
| 读屏 | preview 图 `alt=""`（装饰性，APG radio 模式：信息由 label 文本承载）；swatch 加 `aria-hidden="true"`——颜色名必须在 label/desc 文本中（P3：颜色不可只靠色块传达） |
| 选中态 | 不依赖颜色唯一编码：既有 `.picked` 勾选/边框 + `aria-pressed`/`aria-checked` 与现状保持一致 |
| 对比度 | label 文本 `var(--color-text)` 对按钮底 ≥4.5:1（既有 token 已保证，回归验证即可） |
| Canvas 按钮 | HTML 片段标准模板中按钮为原生 `<button>`，焦点环可见；锁定后 `disabled` 属性（不只是样式置灰） |
| 非鼠标可达 | Canvas 直答是增强路径；键盘-only 用户始终可通过卡片完成全部回答（E4/E5 回落保证） |

## 10 可断言验收点（供 qa-frontend 转 Playwright）

| # | 断言（二值） | 需截图 |
|---|---|---|
| V1 | 带 `preview.type=swatch` 的选项按钮内存在 `.option-preview` 节点且包含与 `colors` 等数的色块子节点 | 是（明暗双主题） |
| V2 | 无 `preview` 的选项按钮内**不存在** `.option-preview` 节点，且按钮 classList 与现状快照一致（E1 零回归） | 是 |
| V3 | preview 图片 404 时触发 onerror 后 `.option-preview` 计算样式 `display:none`，label 文本仍可见（E2） | 是 |
| V4 | Canvas iframe 内 `postMessage({_nfAskAnswer:{sessionId,questionIndex,answer:"<合法label>"}})` 后，WS 发出**恰好一帧** `{type:'askUserAnswer'}` 且 sessionId/answers 与 payload 一致 | 否（WS 帧断言） |
| V5 | 对多选题发送 `_nfAskAnswer` 后，WS **未**发出任何 `askUserAnswer` 帧（E4） | 否 |
| V6 | 对 dependsOn 隐藏题 / 已锁定卡片 / 非法 label 发送 `_nfAskAnswer`，均被丢弃无 WS 帧（E5/E6/E7/E9，四子例） | 否 |
| V7 | 回答提交后 Canvas iframe 收到 `_nfAskState{answered:true}` 且按钮 `disabled===true` | 是（锁定态） |
| V8 | 问题带 `canvas` 字段时 Canvas 面板出现对应 tab；文件不存在时卡片正常渲染且无「查看对比图」按钮（E8） | 是 |
| V9 | 键盘 Tab 序列中 preview 槽不形成独立停靠点；`.option-btn` 可 Enter 选中 | 否（focus 断言） |
| V10 | 主题切换后 swatch 色块计算色不变、选中描边环颜色跟随 `var(--color-primary)`（E12） | 是（明暗对比） |

## 11 实现路径（最小改动）

### 11.1 前端改动文件范围（仅此三处 + 文档）

| 文件 | 改动 | 量级 |
|---|---|---|
| `src/main/resources/web/js/chat.js` | ① `showOptions` 选项渲染分支：有 `preview` 时 prepend `.option-preview` 槽（swatch/image 两型 + onerror 隐藏）；② 新增 `window` message listener 处理 `_nfAskAnswer`：校验（§3.2 安全约束 + E4-E7/E9）→ 写 `answers[qi]` + 同步 DOM picked 态 → 走既有 confirm 路径；③ 回答后广播 `_nfAskState` 到 `.canvas-tab-pane iframe`；④ `renderAskUser` 读 `canvas` 字段 → 自动 openTab + 渲染「查看对比图」按钮 | ~120 行 |
| `src/main/resources/web/css/`（选项样式所在文件，实现方 Grep `.option-btn` 定位） | 新增 `.option-preview` 尺寸类（§7.3）+ 选中描边环 + onerror 隐藏规则 | ~25 行 |
| `~/.nebflow/skills/card-design/SKILL.md` 或新参考页 | 沉淀 §11.2 标准片段（HTML 作者规范） | 文档 |
| 后端 / WS 协议 | **零改动**（复用 `askUserAnswer`） | 0 |
| AskUserQuestion 工具 schema 文档 | tool-engineer 对齐 §2.1 可选字段（向后兼容，纯新增可选） | 文档 |

### 11.2 Canvas 侧标准片段（agent 产出 HTML 时嵌入，写进 card-design 引用）

```html
<button class="nf-pick" data-answer="方案 A · 晨雾">选择此方案</button>
<script>
(function(){
  var SESSION_ID = '...';      // agent 生成 HTML 时注入
  var QUESTION_INDEX = 0;
  document.querySelectorAll('.nf-pick').forEach(function(btn){
    btn.addEventListener('click', function(){
      parent.postMessage({ _nfAskAnswer: {
        sessionId: SESSION_ID, questionIndex: QUESTION_INDEX,
        answer: btn.getAttribute('data-answer')
      } }, '*');   // sandbox iframe origin 为 opaque（"null"），targetOrigin 必须用 '*'，见下方教训
    });
  });
  window.addEventListener('message', function(e){
    if (e.data && e.data._nfAskState && e.data._nfAskState.answered) {
      document.querySelectorAll('.nf-pick').forEach(function(b){ b.disabled = true; });
    }
  });
})();
</script>
```

> **⚠ 实测教训（2026-08-25 用户实测定位，根因已确认）**：Canvas HTML 由 html viewer（`web/js/viewers/html.js`）以 **sandbox iframe**（`srcdoc` + `sandbox="allow-scripts allow-same-origin allow-forms allow-popups"`）渲染。**sandbox iframe 的 `window.location.origin` 是 opaque 字符串 `"null"`**，作为 `postMessage` 的 `targetOrigin` 参数直接抛 TypeError——「The string did not match the expected pattern」，消息**未发出**，Canvas 内点选完全失效。**必须改用 `parent.postMessage(payload, '*')`**：父窗口 chat.js 的 `_nfAskAnswer` listener **不校验 `e.origin`**，靠 payload 内部校验 `sessionId`/`questionIndex`/`label`（§3.2 安全约束），因此用 `'*'` 安全。若父窗口未来改为校验 `e.origin`，本条需重新评估——目前 Chat 侧校验点在 payload 内部，`'*'` 成立。

## 12 参考链接

- Apple HIG Pickers: https://developer.apple.com/design/human-interface-guidelines/pickers
- Apple HIG Selection: https://developer.apple.com/design/human-interface-guidelines/selection
- WAI-ARIA APG Radio: https://www.w3.org/WAI/ARIA/apg/patterns/radio/
- WAI-ARIA APG Checkbox: https://www.w3.org/WAI/ARIA/apg/patterns/checkbox/
- Baymard 变体选择研究: https://baymard.com/blog/product-page-layout
- Figma variants: https://help.figma.com/hc/en-us/articles/360056440594
- Material 3 Segmented buttons: https://m3.material.io/components/segmented-buttons/overview
- 内部：`~/.nebflow/skills/nebflow/visual-style/SKILL.md` · `~/.nebflow/skills/card-design/SKILL.md`
- 源码实证：`web/js/chat.js` (L1369 showOptions / L1622 renderAskUser / L1647 askUserAnswer) · `web/js/cardRegistry.js` (L76/L131 postMessage 协议) · `web/js/viewers/html.js` (L77 viewHtml)

## 13 检索协议教训 [DESIGN-LESSON]

⚠ 2026-08-18：Sogou/360 对本机 IP 触发验证码；developer.apple.com 与 m3.material.io 正文 JS-gated（WebFetch 返回壳页）；w3.org APG 抓取报 MODULE$ 错误。**补条**：检索 HIG/Material 类 JS-gated 页面时，改用其纯文本镜像（如 Apple HIG 的 non-JS 摘要、web.archive.org 快照）或接受「URL 引用 + 既有规范知识」并在规格书检索记录中显式标注未能抓取正文——禁止静默当作已核实引用。

