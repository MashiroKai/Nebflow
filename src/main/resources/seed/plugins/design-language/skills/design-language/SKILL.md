---
name: design-language
description: Stand 统一设计语言规范——以 mystand.space 官网真实 design tokens 为基准的八节规范（配色/字体/字距行高/圆角阴影/间距栅格/动画/组件固定尺寸档/文案 tone），逐 token 给值+用途+反例，配套 assets/tokens.css 账本与 assets/components.css 组件原型。Use when 开发或整改 Stand web/ 前端的任何 UI（面板、弹窗、卡片、按钮、输入、列表、动效、文案）需要取色、取字号、取圆角、取间距、取动效参数或定文案。
audience: nebflow-project
status: active
language: zh
last_verified: 2026-10-21
---

# Stand 设计语言（基准：mystand.space 官网 tokens × Sapphire Glass）

改 Stand 客户端任何 UI 前通读本文。值的单源在客户端 CSS（`src/main/resources/web/css/base.css` 中性阶与语义载体、`sapphire.css` 玻璃体系、`chat.css --ask-*` 浮层卡族）；本 skill 与 `assets/tokens.css` 是账本。**表里有值取表里的，表里没档先补表；裸值直写 = 跑偏。**

基准来源：官网仓 `stand-website/app/globals.css` 的 @theme 与双主题 token（品牌绿 #07c160、GitHub-dark 族暗色中性阶、系统字体栈、16/12/8/6/4 圆角族、160/240ms ease + cubic-bezier(0.4,0,0.2,1) 动效族）；客户端玻璃层（--glass-*、--glass-control-*、--sapphire 91 127 191）是官网语言的客户端延伸。两处同源不同值时：**客户端实现值为准**（本表即记客户端值，并标注官网同源值）。

用户视觉硬裁定（弹窗禁 overlay 遮罩、面板必须毛玻璃、控件统一玻璃质感、主发送按钮微信绿实色族、字重方案 B：body 400/标题 600/按钮 500/代码 500、总基调低调克制）见 nebflow-frontend-dev 的 visual-style——与本表冲突时以裁定为准。

## 1. 配色

### 1.1 品牌绿（交互主色，官网 --color-pixel 同源）
| token | 值 | 用途 | 反例 |
|---|---|---|---|
| `--color-primary` | `#07c160`（不随主题变） | 主操作实底：发送/保存/确认/开关 on 态；每视口 ≤1 个绿强调事件 | 拿绿当大面积装饰底；两枚绿钮同屏 |
| `--color-primary-hover` | `#06ad56` | 主实底 hover | 用 opacity 做主钮 hover（禁） |

### 1.2 蓝宝石（次级强调，tint 拼色）
| token | 值 | 用途 | 反例 |
|---|---|---|---|
| `--sapphire` | `91 127 191`（RGB 三元组） | 一切 `rgb(var(--sapphire) / α)` tint 拼色的底数：focus 环、选中描边、外向气泡 15% 底 | 裸写 #5b7fbf；拿蓝做第二主按钮 |
| tint 档 | 4% / 8% / 10% / 15% | 4-8% = 底纹/hover；10% = 选中底；15% = 外向面（`--sapphire-tint-10/15` 已单源） | 自造 6%、12% 中间档 |

### 1.3 语义色（只上载体，不上正文）
| token | 亮 | 暗 | 载体 |
|---|---|---|---|
| `--status-error-icon` | `#d64545` | `#f26b6b` | 图标/描边/10% 底纹/危险钮 |
| `--status-warn-icon` | `#b45309` | `#ffa83c` | 同上 |
| `--status-success-icon` | `#2e7d32` | `#66bb6a` | 同上 |
| `--status-word` | = `--color-frame-text-muted`（中性） | 同左 | 11-13px 状态词、通知正文的**字色** |

反例：红/黄/绿直接做正文字色；状态词染语义色（词永远中性，色由同行的图标/底纹承担）。

### 1.4 中性阶（亮/暗成对，暗档 = GitHub-dark 同族）
| 角色 | 亮 | 暗 | 用途 |
|---|---|---|---|
| 页面底 `--color-bg` | `#f5f6f8` | `#13161c` | 内容区 |
| 面板底 `--color-surface` / `--color-modal-bg` | `#ffffff` | `#1c1f27` | 卡片、弹窗实底兜底 |
| 强文字 `--color-frame-text-bright` | `#1b1e26` | `#e8eaed` | 标题、行名（永不写 color:#fff） |
| 正文 `--color-frame-text` | `#3a3d45` | `#c8cad0` | 表单标签、正文 |
| 弱文字 `--color-frame-text-muted` | `#717379` | `#81838a` | hint、meta、占位、状态词 |
| 更弱 `--color-frame-text-dim` | `#aaacb3` | `#7b7e88` | 仅幽灵按钮静息字色 |
| 描边 `--color-frame-border` / 轻 `…-light` | `#e0e1e4` / `#eeeff1` | `#282b33` / `#1c1f27` | hairline 边、行分隔 |

官网同源档（跨端对齐参照）：`#0d1117 / #161b22 / #e6edf3 / #98a0aa / #2d333b`——同族灰阶，客户端值不迁移、新面板不引入官网值。

### 1.5 玻璃（面板材质，官网 .glass 同构造）
| token | 亮 | 暗 | 用途 |
|---|---|---|---|
| `--glass-bg` + `--glass-blur` | `rgba(255,255,255,.55)` + `24px` | `rgba(24,28,38,.68)` | 弹窗外壳、浮层（blur+saturate(1.15) 必带） |
| `--glass-control-*` 族 | 见 sapphire.css | 见 sapphire.css | 一切可交互控件材质 |
| `--glass-etched-bg` | `rgba(0,0,0,.025)` | `rgba(255,255,255,.04)` | 输入/嵌入面 |
| `--overlay-bg` | `transparent` | `transparent` | 弹窗背层（**恒透明，禁暗化禁模糊**） |

反例：纯透明面板；给弹窗背层加 rgba(0,0,0,α) 遮罩或 backdrop blur。

## 2. 字体

- **字族**：UI = `-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', Roboto, sans-serif`（官网同栈）；等宽 = `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`（密钥值、代码、路径）。装饰 eyebrow 场景可用 JetBrains Mono（官网 hero 先例），客户端暂无消费面。
- **字重**：body 400 / 标题与行名 600 / 按钮 500 / 代码 500。禁 700；禁 300。

### 场景 → 字号对照表（阶梯只有 5 档）
| 场景 | 字号/字重 | 反例 |
|---|---|---|
| 面板标题（弹窗头、卡片问题行） | 15px / 600 | 14px、16px 混用；加粗到 700 |
| 正文/表单标签/输入与下拉/选项 | 13px / 400 | 12px 控件字（跑偏常见位）、13.5px 半档 |
| 按钮 | 12px / 500（对话发送族 13px 沿用裁定） | 11px 按钮 |
| hint/caption/次要说明 | 12px / 400 | 10px + opacity 0.6（既判跑偏，已整改） |
| meta/徽标/小节标签（uppercase） | 11px / 400-600 | 10px；meta 不加 tabular-nums |
| 页面级大标题（营销/空态主句） | 20px / 600 / letter-spacing -0.01em | 22px、24px 自造档 |

规则：数字面（尺寸/时间/计数）一律 `font-variant-numeric: tabular-nums`；可读下限 11px——11px 以下不存在合法场景。

## 3. 字间距与行高

| 场景 | 值 | 反例 |
|---|---|---|
| 正文行高 | 1.5（聊天/选项文案 1.55） | 1.2 挤压、1.8 松散 |
| 标题行高 | 1.3 | — |
| 单行控件/图标行 | line-height: 1（图标行 line-height: 0） | — |
| 小节标签（11px uppercase） | letter-spacing 0.5px | 1px+ 夸张字距 |
| 大标题（≥20px） | -0.01em（越大越紧，官网 h1 -0.02em） | 正文负字距 |
| 其余 | 0（不写字距） | 正文 0.2px 顺手字距 |

## 4. 圆角与阴影阶梯

### 圆角（8 档，禁止档外值）
| 档 | 值 | 用途 | 反例 |
|---|---|---|---|
| badge | 4px | 徽标 pill、行内 code | — |
| chip | 6px | 28px 图标钮、mchain chip、复制钮 | 图标钮 4px（热区观感碎） |
| input | 8px | 输入/下拉/面板档按钮（cfg-btn 族、cfg-btn-primary） | **5px 按钮（既判跑偏，已整改）** |
| button | 10px | 对话档按钮（发送族裁定值）、列表行 hover 面 | 面板按钮跟风 10px（两档并存是刻意的：面板档 8 / 对话档 10） |
| card-sm | 10px | 列表行卡面 | — |
| card-md | 14px | 面板内卡（cfg-card、secrets 展开卡、卡槽钮） | 12px/16px 混用 |
| card-lg | 16px | 聊天浮层卡（option-box） | — |
| shell | 20px | 弹窗外壳（全站弹窗统一值） | 18px、24px |
| pill | 999px | 圆点、胶囊开关 | — |

### 阴影（3 族，禁族外自造）
| 族 | 构成 | 用途 |
|---|---|---|
| 面板族 | `inset 0 1px 0 rgba(255,255,255,.25)`（暗 .06）+ `0 2px 8px 4%` + `0 8px 24px 6%`（暗档加深至 20%/35%） | 弹窗外壳、浮层卡 |
| 控件族 | glass-control 双 inset 边 + `0 1px 3px` + `0 2px 8px`（hover 抬升 `0 2px 8px`+`0 4px 12px`） | 一切玻璃控件 |
| 平面族 | 无阴影，1px hairline 承担边界 | 面板内卡、行、徽标 |

反例：卡片单枚大 drop-shadow；hover 给平面卡加影（平面族 hover 只做底色 wash）。

## 5. 间距系统（4px 栅格）

合法值：**4 / 8 / 12 / 16 / 20 / 24 / 32**（面板左右留白 20，页面级 24-32）。

| 场景 | 值 | 反例 |
|---|---|---|
| 弹窗头 padding | `12px 16px 12px 20px`（左 20 光学对齐内容） | 14px 头距（既判跑偏，已整改） |
| 弹窗体 padding | `12px 20px 20px` | 13px/14px 斜数 |
| 面板内节间距 | 16px（section margin-bottom） | 10px、14px |
| 行内距（列表行、行卡头） | `8px 12px` | 9px 10px（既判跑偏，已整改） |
| 控件间 gap | 8px（按钮行、图标+文字）；紧排 4px | 6px 随手值 |
| 卡内块间距 | 12px | 10px |
| 输入 padding | `6px 10px`（面板档）/ `8px 12px`（卡内档） | 5px 12px |

## 6. 动画

### token（base.css :root）
| token | 值 | 用途 |
|---|---|---|
| `--dur-1` | `120ms` | 微变换（icon scale、按下反馈） |
| `--dur-2` | `160ms` | hover/focus 的色彩、边框、底纹（官网 sel-btn 160ms 同源） |
| `--dur-3` | `240ms` | 主题切换、表面/展开过渡（官网 body 240ms 同源） |
| 入场 | `200ms cubic-bezier(0.16,1,0.3,1)`（modalIn 家族） | 弹窗、卡、行内展开内容的入场 |
| 面板开合 | `320ms cubic-bezier(0.32,0.72,0,1)`（--panel-ease，既有基准） | 侧栏/画布几何开合 |
| `--ease-std` | `cubic-bezier(0.4, 0, 0.2, 1)` | 几何属性（width/位移）的统一缓动（官网 360ms nav 同源） |

### 什么时候动 / 什么时候克制
- **动**：状态反馈（hover/focus/active/on-off）、入场（fade + ≤10px 位移）、保存成功闪现（1.6s 后归位）。
- **克制**：布局跳变不动画（行内展开 = 高度单步、仅 detail 内容入场）；数据加载禁骨架闪烁；常驻循环动效只给「活着」的语义（终端光标 blink），装饰性循环禁。
- `prefers-reduced-motion: reduce` 下全局压到 0.01ms（官网同款兜底）。
- **transition 禁 `all`**——逐属性列出（既判跑偏：`transition: all 0.25s ease` → `background var(--dur-2) ease, border-color var(--dur-2) ease, box-shadow var(--dur-2) ease`）。

## 7. 组件原型与固定尺寸档（components.css 为参考实现）

**尺寸档是硬约束**：新面板从档里选，禁自造宽度/高度/圆角组合。

| 组件 | sm | md | lg | 硬规则 |
|---|---|---|---|---|
| 弹窗 | 400px（确认框） | 560px（设置页） | 640px（列表面板：密钥/搜索/社交） | radius 20、玻璃外壳、max-height 85vh、无 overlay 遮罩；禁档外宽度 |
| 卡 | 行卡 radius 10（列表行面） | 面板内卡 radius 14（cfg-card） | 浮层卡 radius 16（option-box） | 平面族无影；卡内块距 12px |
| 按钮 | 12px 字 / `4px 10px`（btn-sm） | 12px 字 / `6px 12px`（面板档，radius 8） | 发送族 13px / `5px 12px`（radius 10，微信绿实色族裁定值） | 主实底 = 绿；次级 = 玻璃；危险 = ghost + hover 红；禁第二实色族 |
| 输入/下拉 | — | 13px 字 / `6px 10px` / radius 8 | 卡内档 `8px 12px` | etched 材质；focus = 1px 强边 + 2px ring（绿面绿环、面板档灰环） |
| 列表行 | rest `8px 12px` + hairline 分隔 + hover wash | 展开卡 radius 14 | — | 静息行无按钮无边框盒；展开才升卡材质（Reminders 式） |
| 徽标 pill | 10-11px / `1px 7px` / radius 4 | — | — | tint 底 + 25% 边；字中性（--status-word），色在图标/底上 |
| 空态 | 一句现状（12px muted）+ 一个动作入口 | — | — | 「还没有 X」+ 尾行动钮；禁插图堆砌、禁「暂无数据」孤句 |

图标：lucide 线稿 16px（行内 10-14px），stroke=currentColor，色由 token 给；禁 emoji、禁 accent bars。

## 8. 语言风格（文案 tone：中文极简）

| 场景 | 规则 | 正例 | 反例 |
|---|---|---|---|
| 按钮 | 单动词，≤4 字 | 保存 / 删除 / 新建密钥 / 重启服务 | 「点击这里保存」「确定要保存吗」 |
| 空态 | 一句现状 + 动作入口 | 「还没有密钥」+「＋ 新建密钥」尾行 | 「抱歉，暂无任何数据哦」 |
| 错误态 | 发生了什么 + 怎么办，一句 | 「密钥请求失败（io）。请检查网关连接后重试。」 | 堆栈、裸异常词、感叹号 |
| 状态词 | 中性、≤6 字 | 已连接 / 已保存 ✓ / 权限过宽 | 「警告：存在安全风险！！」 |
| 占位符 | 示例值或遮蔽说明 | `sk-…` /「输入密钥 / 验证码（不显示明文）」 | 「请在此输入内容」 |
| hint | 一句，说清约束或后果 | 「该值将加密存入本机 secrets（仅所有者可读）」 | 三行以上说明文 |

标点：中文界面用全角标点；技术词（io、0600、API Key）保持半角原样。数字与单位间空格（40 B）。

## 9. 整改判例（本语言落地首批，2026-10-21 三面板批）

以下为从现状里清出的跑偏值，整改后进入反例清单，再次出现 = 回归：

1. `cfg-hint` 10px + opacity .6 → 12px 纯 muted（可读性下限与 hint 档对齐）。
2. `cfg-btn` / `cfg-btn-primary` 11px 字、radius 5px → 12px、radius 8（面板按钮档）。
3. 面板输入 12px → 13px（表单控件与正文同档）。
4. 弹窗头 14px 距 → 12/16/20 栅格距（设置/密钥两头同值）。
5. AskUser 密钥行内联样式 + 文字眼按钮 → `.option-secret-*` 类 + 图标眼（与密钥面板同语言）。
6. `transition: all` → 逐属性 + 时长 token。

## 10. 使用流（照此三步）

1. **取值**：查 §1-6 token 表拿值；客户端同名字 token 直接 `var(--…)` 引用，禁复制字面量。
2. **取形**：查 §7 组件表选档；components.css 有原型则套用。
3. **验证**：截图驱动（改前/改后、亮/暗、三断点 1440/768/375），console 无新增错误；不像样立即迭代。
