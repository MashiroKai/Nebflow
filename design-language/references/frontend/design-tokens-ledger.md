# 设计规格刻度 · 圆角 / 边距 / 文字离边缘的距离 / 字号（四表实测 → 定版 → 迁移清单）

> **作者令 2026-11-04 13:48（逐字）**：「把我们对圆角 边距 文字离边缘的距离 各文字的大小
> 写进spec和前端开发包」。
>
> 本文件 = 该四类规格的**唯一正文**（规范面与执行刻度合体；2026-11-18 两份同名「执行面」
> 件合一，独有条目并入下方 §7）。取值入口见下方 §7「取用四步」与本目录 `README.md`。
> **机器校验面**：本文末尾 `design-spec-scale` 块是本仓 `scripts/check-design-spec-sync.mjs`
> 唯一解析的规范表——改刻度先改本块，再改令牌源；两向漂移该闸即红。

## 0. 定位与单源纪律

| 角色 | 文件 | 关系 |
|---|---|---|
| 规范面（本文，唯一正文） | `references/frontend/design-tokens-ledger.md` | 定版刻度 + 判定理由 + 迁移清单 + 取值流；**表里有值取表里的** |
| 运行时单源 | `src/main/resources/web/css/design-system.css`（`--ds-*`，本仓） | 值真身。改值先改它，再回填本表 |
| 账本镜像 | `assets/design-system/tokens.css`（`--dl-*`，本目录） | 同值镜像，按 `--dl-r-* / --dl-fs-* / --dl-sp-*` 对位 `--ds-radius-* / --ds-fs-* / --ds-space-*` |
| 消费面纪律 | 本仓 `scripts/check-design-tokens.mjs` | 组件里禁裸字面量（存量护栏 + 严格面） |
| 规范↔代码闸 | 本仓 `scripts/check-design-spec-sync.mjs` | 本文 spec 块 ↔ 令牌源 ↔ 账本 三向同值 |

**四条铁律**

1. 刻度只有本文件 §2 的档；档外值出现即缺陷（不是「新档」，是跑偏）。
2. **同语义只能一档**——同一语义出现两个近似值，第二个就是跑偏（如 Ask 气泡 13.5px 对主对话气泡 14.5px），不是「面不同」。
3. **值的家是令牌层**——值只许 `css/design-system.css` 的 `--ds-*` 出生，消费面只写 `var(--ds-*)`；禁在组件里复制字面量，禁在消费面派生第二处近似值。
4. 档外但高频/语义确实独立的既有值，以**具名槽**收编（`--ds-*` 具名 + 本表登记），禁在消费面再派生第二处。
5. 「不动任何组件代码」只适用于**立档批**——现状与定版的差异进 §4 迁移清单，实施须另批（要作者批）。

## 1. 现状实测（时点 = 合并 main `0b1dcbb97`；复测于 `5e17ad65c`，主档数值零漂移：padding 原子 2304→2306、margin 607→609、字号 `var()` 209→210——仅 main 侧同一批 ask/mobileLayer 微调带来的 ±2 处，档位排序与判定不变；语料 = `web/css` 40 个 css + `web/js` 231 个 js）

计数口径：`border-radius` / `padding` / `margin` / `gap` / `font-size` 声明的**字面量**出现次数
（注释已剔除；`var(--…)` 引用单列）；数值档按出现次数降序，同属性近似值并存**如实列出、不抹平**。

### 1.1 圆角实测（字面量 862 处 / 32 种取值；`var()` 引用 115 处）

| 值 | 次数 | 主要落点（前 3） | 判定 |
|---|---|---|---|
| 8px | 168 | chat.css×31 sidebar.css×17 friends.css×16 | 阶梯 `input` 档，最大宗 |
| 50% | 148 | mobileLayer.css×20 split.css×17 chat.css×16 | **形态档**（正圆：头像/圆点/圆钮），不入刻度 |
| 6px | 101 | chat.css×17 flowCss.js×8 friends.css×7 | 阶梯 `chip` 档 |
| 4px | 81 | chat.css×20 flowMap.css×9 input.css×7 | 阶梯 `badge` 档 |
| 10px | 76 | friends.css×12 chat.css×10 modal.css×8 | 阶梯 `button`/`card-sm` 档（两槽同值） |
| 999px | 39 | chat.css×10 mobileLayer.css×10 split.css×6 | 阶梯 `pill` 档 |
| 12px | 39 | chat.css×9 mobileLayer.css×7 split.css×4 | 阶梯 `xl` 档 |
| **7px** | **26** | projectPanel.css×4 split.css×4 flowCss.js×4 | 档外 → 收敛 `input` 8px |
| **3px** | **21** | sidebar.css×4 split.css×3 chat.css×2 | 档外 → 收敛 `badge` 4px |
| 16px | 21 | chat.css×7 mobileLayer.css×5 flowCss.js×3 | 阶梯 `card-lg` 档 |
| **2px** | **21** | chat.css×6 split.css×6 friends.css×2 | 档外高频（滚动条拇指/进度条/微圆点）→ **具名例外槽** `--ds-radius-thin` 2px |
| **5px** | **17** | split.css×3 todo-face.css×3 delegatePanorama.css×2 | 档外 → 收敛 `chip` 6px |
| 0 | 17 | chat.css×5 split.css×5 design-system.css×2 | **形态档**（贴边/直角屏） |
| 14px | 15 | secrets.css×3 chat.css×2 delegatePanorama.css×2 | 阶梯 `card-md` 档 |
| **9999px** | **14** | friends.css×9 dropbox.css×2 chat.css×1 | 档外（胶囊变体写法）→ 收敛 `pill` 999px |
| 20px | 13 | input.css×2 chat.css×1 friends.css×1 | 阶梯 `shell` 档 |
| **9px** | **8** | split.css×2 chat.css×1 executors.css×1 | 档外 → 收敛 `button` 10px |
| 18px | 7 | delegatePanorama.css×3 flowMap.css×2 input.css×1 | 阶梯 `2xl` 档 |
| inherit | 6 | mobileLayer.css×4 glass.css×1 split.css×1 | **形态档**（承袭父级） |
| **0 !important** | **6** | mobileLayer.css×6 | 强制直角形态（手机层贴边件） |
| **1px** | **4** | split.css×2 cards.css×1 modal.css×1 | 档外 → 收敛 `badge` 4px |
| **11px** | **4** | delegatePanorama.css×1 perception.css×1 askAttachments.js×1 | 档外 → 收敛 `xl` 12px |
| **15px** / **13px** / **17px** / **3.5px** | 各 1 | ask.css×1 / mobileLayer.css×1 / split.css×1 / mobileLayer.css×1 | 档外孤立值 → 就近收敛（15→`card-lg` 16；13→`xl` 12；17→`2xl` 18；3.5→`badge` 4） |
| 组合四值（`0 12px 12px 0` 等 4 处） | 4 | split.css×2 todo-face.css×1 askAttachments.js×1（`24px 24px 19px 19px`） | 几何组合形态，逐值取档，不另立组合档 |
| `calc(...)` | 2 | mobileLayer.css×2 | 动态计算形态（`--msw-h`/`--mtap` 半值），不入刻度 |

`var()` 引用 115 处的集中度：`--ds-radius-badge` 22、`--ds-radius-input` 15、`--ds-radius-chip` 12、
`--ds-radius-pill` 12、`--ds-radius-card-md` 9、`--ds-radius-shell` 8、`--ds-radius-button` 5、
`--ds-radius-xl` 3、`--ds-radius-card-lg` 2、`--ds-radius-card-sm` 2；其余为 `--cv-radius-*`（玻璃条族）、
`--st-radius-*`（感知域）、`--r-*`（delegatePanorama 私有族）、`--mcap-radius`（手机胶囊）。

### 1.2 边距/间距实测（padding 原子 2304 处 / gap 690 处 / margin 原子 607 处）

| 值 | padding 原子 | gap | margin 原子 | 判定 |
|---|---|---|---|---|
| 0 | 363 | 7 | 264 | 合法（清零） |
| 8px | 239 | 186 | 24 | 阶梯 `--ds-space-2` |
| **10px** | **235** | **53** | **22** | 档外高频；按布局角色分六槽（`--ds-space-{inline-x,block-y,inset,inline-gap,stack-gap,indent-x}`，2026-11-04 命名债批） |
| 12px | 204 | 31 | 12 | 阶梯 `--ds-space-3` |
| **6px** | **168** | **137** | **44** | 档外最高频（合计 349）→ 具名半步档（见 §3-②） |
| 4px | 161 | 72 | 44 | 阶梯 `--ds-space-1` |
| 2px | 145 | 46 | 32 | 阶梯 `--ds-space-half` |
| 16px | 122 | 2 | 10 | 阶梯 `--ds-space-4` |
| **14px** | **113** | 6 | 17 | 档外（多为 `10px 14px` 气泡内距）→ 不立档，属 `--ds-bubble-pad` 组成部分 |
| **5px** | **74** | **35** | 5 | 档外 → 收敛 4px（紧排）/6px（件缝） |
| 1px | 63 | 9 | 7 | 微缝（分隔线位置），形态值 |
| **3px** | **62** | **26** | 5 | 档外 → 收敛 2px（半步）/4px |
| **7px** | **49** | **16** | 1 | 档外 → 收敛 6px（半步）/8px |
| 20px | 33 | 1 | 3 | 阶梯 `--ds-space-5` |
| **9px** | **28** | **3** | 2 | 档外 → 收敛 8px |
| 24px | 28 | 0 | 2 | 阶梯 `--ds-space-6` |
| **18px** | **18** | **2** | **9** | 档外 → 收敛 16px/20px（按语义） |
| **11px** | **11** | **2** | 0 | 档外 → 收敛 12px |
| 36px | 7 | 0 | 0 | 密度行带既有档（`--ds-density-row-compact`）内距，不属栅格 |
| **13px** | **6** | 0 | 0 | 档外 → 收敛 12px |
| 40px / 32px / 28px / 22px | 6/5/5/4 | — | 3/0/0/0 | 区块级/大面积留白，按 32/24 档复核 |

### 1.3 文字离边缘的距离实测（四类关键场景；每行 = 场景 · 选择器/变量 · 现盘值）

| 场景 | 现场 | 现盘内距 | 字号 | 圆角 |
|---|---|---|---|---|
| **气泡**（主对话） | `chat.css:1107 .bubble` → `--ds-bubble-pad` | **10px 14px** | `--ds-fs-bubble` 14.5px | `--ds-radius-card-lg` 16px |
| 气泡（Ask 面） | `ask.css:217 .ask-bub` | **9px 13px**（近似值并存） | 13.5px（档外） | 15px（档外） |
| 气泡（手机层） | `ask.css` `body.mobile-layer .ask-bub` | **8px 12px** | 13px | — |
| **卡片**（浮层卡） | `chat.css:1837 .option-box` | **12px 16px** | 15px/600 问题行 | 16px |
| 卡片（面板内卡头） | `sidebar.css:1147 .cfg-card-header` | **8px 10px** | `--ds-fs-body` 13px | 容器 `--ds-radius-chip` |
| 卡片（卡内动作区） | `sidebar.css:617 .card-actions` | **10px 14px 12px** | `--ds-fs-meta` 11px | — |
| 卡片（工具卡紧档） | `--ds-card-pad-compact` | **6px 10px** | 12px | `--ds-radius-button` |
| 卡片（弹窗头/体） | `--ds-modal-head-pad` / `--ds-modal-body-pad` | **12px 16px 12px 20px** / **12px 20px 20px** | `--ds-fs-title` 15px | `--ds-radius-shell` 20px |
| **列表行**（标准行） | `--ds-row-pad` | **8px 12px** | 13px | 行面 `10px` |
| 列表行（紧凑行） | `--ds-row-pad-dense` | **6px 12px** | `--ds-fs-row` 13.5px | — |
| 列表行（设置行·卡内变体） | `sidebar.css:580 .settings-row` | **10px 0**（卡内行变体，行距由卡片给） | `--ds-fs-body` 13px | — |
| 列表行（菜单行） | `sidebar.css:294 .ctx-item` | **4px 16px** | `--ds-fs-body` 13px | — |
| **按钮**（面板档） | `sidebar.css:1247 .cfg-btn` | **6px 16px** | `--ds-fs-btn` 12px | `--ds-radius-pill` 999px |
| 按钮（小档） | `sidebar.css:1260 .cfg-btn-sm` | **4px 12px** | 12px | pill |
| 按钮（主档） | `sidebar.css:1262 .cfg-btn-primary` | **6px 18px** | 12px | pill |
| 按钮（发送族） | `sapphire.css` 发送族 | **5px 12px** | 13px | 10px |
| 输入（面板档/卡内档） | `--ds-input-pad` / `--ds-input-pad-lg` | **6px 10px** / **8px 12px** | 13px | `--ds-radius-input` 8px |
| 徽标 pill | `.cfg-card-badge` 等 | **1px 6px**（`1px 7px`/`1px 5px` 并存） | **10px**（低于可读下限） | **3px**（档外） |

### 1.4 字号实测（字面量 779 处 / 37 种取值含 em·calc 形态；`var()` 引用 209 处）

| 值 | 次数 | 主要落点（前 3） | 判定 |
|---|---|---|---|
| 12px | 228 | chat.css×56 friends.css×36 split.css×29 | 阶梯 `btn`/`hint` |
| 11px | 160 | chat.css×27 friends.css×22 split.css×21 | 阶梯 `meta` |
| 13px | 110 | chat.css×24 split.css×13 friends.css×11 | 阶梯 `body` |
| **10px** | **59** | chat.css×14 diffView.css×6 taskList.css×6 | **低于 11px 可读下限** → 收敛 `meta` 11px |
| **14px** | **45** | modal.css×11 chat.css×8 split.css×4 | 具名槽 `capsule`/`card-body`（同值两槽） |
| 15px | 33 | chat.css×7 modal.css×5 friends.css×3 | 阶梯 `title` |
| **12.5px** | **24** | perception.css×8 chat.css×5 split.css×5 | 半档（官网 `--st-fs-btn` 转录）→ 收敛 12px |
| **11.5px** | **23** | perception.css×9 projectPanel.css×4 split.css×4 | 半档（官网 `--st-fs-label` 转录）→ 收敛 12px（说明档） |
| 16px | 18 | friends.css×4 split.css×4 modal.css×3 | 具名槽 `mobile-callout`（手机正文/输入件） |
| **9px** | **14** | reminder.css×4 chat.css×3 taskList.css×2 | 下限外 → 收敛 11px |
| **10.5px** | **13** | split.css×7 ask.css×1 chat.css×1 | 半档 → 收敛 11px |
| **13.5px** | **7** | chat.css×5 ask.css×1 perception.css×1 | 具名槽 `row`（会话行）+ ✗ Ask 气泡借用 → 气泡归 `bubble` 14.5 |
| **18px** | **6** | split.css×2 dropbox.css×1 nav.css×1 | 无档 → 分语义：标题类 `display` 20px / 图标字形 `lead` 17px |
| 20px | 4 | mobileLayer.css×2 modal.css×1 | 阶梯 `display` |
| **17px** | **3** | friends.css×2 mobileLayer.css×1 | 阶梯 `lead`（Apple body 17） |
| **8px** | **3** | delegatePanorama.css×1 input.css×1 reminder.css×1 | 下限外 → 收敛 11px |
| **14.5px** | **3** | input.css×3 | 具名槽 `bubble`（主对话气泡裁定档） |
| **22px** | **3** | mobileLayer.css×1 perception.css×1 split.css×1 | 无档 → 收敛 `display` 20px（`otp-chip` 码面可走 20px） |
| **9.5px** / **8.5px** / **15.5px** | 2/2/2 | chat.css / keyboard.css / perception.css·activityBar.js | 下限外 / 半档 → 11px / 15px |
| **19px** / **26px** / **28px** / **7px** | 1/1/1/1 | mobileLayer.css / activityBar.js / chat.css / chat.css | 19→`display` 20；26→`title-lg` 28；28=`title-lg`；7→下限外 11 |

`var()` 引用 209 处的集中度：`--ds-fs-meta` 46、`--mfs-min` 44（= `--ds-fs-body` 13）、`--ds-fs-body` 28、
`--ds-fs-hint` 22、`--mfs-body` 15、`--mfs-sub` 12、`--mfs-callout` 9、`--ds-fs-btn` 5、`--ds-fs-title` 4、
`--ds-fs-bubble` 1、`--ds-fs-row` 1、`--ds-fs-subtitle` 14（= 原胶囊/卡正文两槽合并后的单槽）；感知域走 `--st-fs-*`（11/11.5/12.5/13/15 官网族）。

## 2. 定版刻度（档 = 变量名 + 值 + 用在哪）

> 全部变量均已在 `design-system.css` 存在（除标注「待建」者）。**待建槽需作者批后才落代码**，
> 本批只登记，不实施。

### 2.1 圆角刻度（11 档 + 1 例外槽 + 3 形态）

| 档 | 变量 | 值 | 用在哪 |
|---|---|---|---|
| badge | `--ds-radius-badge` | 4px | 徽标 pill、行内 code、极小微片 |
| chip | `--ds-radius-chip` | 6px | 28px 图标钮、小 chip、路径片、卡片容器（cfg-card） |
| input | `--ds-radius-input` | 8px | 输入/下拉、面板档按钮、玻璃小钮（原 7px 档归此） |
| button | `--ds-radius-button` | 10px | 对话档按钮、行卡面、列表行 hover 面、条状小件 |
| xl | `--ds-radius-xl` | 12px | 条状容器、看板页签轨、次卡 |
| card-md | `--ds-radius-card-md` | 14px | 面板内卡（cfg-card 展开态、secrets 展开卡） |
| card-lg | `--ds-radius-card-lg` | 16px | 聊天浮层卡（option-box）、气泡本体 |
| 2xl | `--ds-radius-2xl` | 18px | 工具卡、大卡（Apple rounded.lg） |
| shell | `--ds-radius-shell` | 20px | 弹窗外壳（全站唯一弹窗圆角） |
| pill | `--ds-radius-pill` | 999px | 胶囊、开关、圆钮、按钮族现行形态（9999px 写法归此） |
| 例外槽（2026-11-04 实施批出生） | `--ds-radius-thin` | 2px | 滚动条拇指、进度条、微圆点（细件专属，禁他处复用） |
| 形态（不入刻度） | — | `50%` / `0` / `inherit` / `calc()` | 正圆（头像·圆点·圆钮）、贴边直角屏、承袭父级、动态计算 |

### 2.2 间距刻度（4px 栅格 10 档 + 2 例外档）

| 档 | 变量 | 值 | 用在哪 |
|---|---|---|---|
| half | `--ds-space-half` | 2px | 4px 栅格外半步微缝（图标与角标、紧贴件） |
| 1 | `--ds-space-1` | 4px | 紧排件缝、行内图标与文字 |
| 例外·半步（`--ds-space-1h`，2026-11-04 实施批出生） | `--ds-space-1h` | 6px | 图标+文字小缝、chip 内距（实测 349 处，见 §3-②） |
| 例外·发丝（2026-11-04 间距例外批） | `--ds-space-hair` | 1px | 发丝缝/发丝偏移（99 处原子；禁作内距，内距走 4px 栅格） |
| 2 | `--ds-space-2` | 8px | 控件间 gap、行内距、按钮行 |
| 例外·10px 内横距（2026-11-04 命名债批） | `--ds-space-inline-x` | 10px | 块/控件与内容的左右内距（151 处） |
| 例外·10px 内纵距 | `--ds-space-block-y` | 10px | 块上下内距（95 处） |
| 例外·10px 四边等内距 | `--ds-space-inset` | 10px | `padding` 单值（5 处） |
| 例外·10px 行内件缝 | `--ds-space-inline-gap` | 10px | 同一行相邻件之间 `gap`（56 处） |
| 例外·10px 块间纵距 | `--ds-space-stack-gap` | 10px | 块与块之间纵向 `margin`（49 处） |
| 例外·10px 行缩进 | `--ds-space-indent-x` | 10px | 相对容器左缘的横向偏移（9 处） |
| 3 | `--ds-space-3` | 12px | 卡内块距、行横距、节内块距 |
| 4 | `--ds-space-4` | 16px | 节间距（`--ds-section-gap`）、弹窗头横距 |
| 5 | `--ds-space-5` | 20px | 面板左右留白、弹窗体左右 |
| 6 | `--ds-space-6` | 24px | 页面级留白 |
| 8 | `--ds-space-8` | 32px | 大区块留白 |
| 12 / 16 | `--ds-space-12` / `--ds-space-16` | 48px / 64px | 区块级/大区块级留白（长页、空态主句区） |
| 派生 | `--ds-section-gap` / `--ds-card-gap` | = `space-4` / `= space-3` | 节间距、卡内块距（禁复制字面量） |

### 2.3 文字离边缘的距离（四场景内距矩阵）

| 场景 | 变量 | 值 | 说明 |
|---|---|---|---|
| 气泡·主对话 | `--ds-bubble-pad` | **10px 14px** | 唯一气泡内距；Ask 面/手机层按下表覆盖 |
| 气泡·手机层 | 待建 `--ds-bubble-pad-mobile` | **8px 12px** | 手机密度档（现盘读数额外具名） |
| 卡片·浮层卡 | 待建 `--ds-card-pad` | **12px 16px** | option-box 现盘值（本批只登记） |
| 卡片·面板内卡头 | `--ds-card-pad-compact`（紧档）+ 卡头 **8px 10px** | 6px 10px / 8px 10px | 紧档已具名；卡头值待建 `--ds-card-head-pad` |
| 卡片·卡内动作区 | 待建 `--ds-card-actions-pad` | **10px 14px 12px** | 三值不对称（上 10 / 横 14 / 下 12），沿用现盘 |
| 卡片·弹窗头 | `--ds-modal-head-pad` | **12px 16px 12px 20px** | 左 20 光学对齐内容 |
| 卡片·弹窗体 | `--ds-modal-body-pad` | **12px 20px 20px** | — |
| 列表行·标准 | `--ds-row-pad` | **8px 12px** | 行卡头、文件行 |
| 列表行·紧凑 | `--ds-row-pad-dense` | **6px 12px** | 树行/文件密集行 |
| 列表行·紧档 | `--ds-row-pad-tight` | **8px 10px** | 双行名+副文本行 |
| 列表行·左右内缩 | `--ds-row-inset` | **6px** | 行 hover 面左右内缩量 |
| 列表行·卡内行变体 | 待建 `--ds-row-pad-card` | **10px 0** | 设置页 `.settings-row`（横距由卡片给） |
| 列表行·菜单行 | 待建 `--ds-menu-item-pad` | **4px 16px** | `.ctx-item` 类菜单项 |
| 按钮·面板档 | 待建 `--ds-btn-pad` | **6px 16px** | `.cfg-btn` |
| 按钮·小档 | 待建 `--ds-btn-pad-sm` | **4px 12px** | `.cfg-btn-sm` |
| 按钮·主档 | 待建 `--ds-btn-pad-primary` | **6px 18px** | `.cfg-btn-primary` |
| 按钮·发送族 | 待建 `--ds-btn-pad-send` | **5px 12px** | 主对话发送键（微信绿实色族） |
| 输入·面板档 | `--ds-input-pad` | **6px 10px** | 面板内输入/下拉 |
| 输入·卡内档 | `--ds-input-pad-lg` | **8px 12px** | 卡内输入 |
| 徽标 pill | 待建 `--ds-badge-pad` | **1px 6px** | 现盘 `1px 5px`/`1px 6px`/`1px 7px` 三值并存 |

### 2.4 字号刻度（8 档阶梯 + 6 具名槽 + 手机映射）

| 档 | 变量 | 值 | 用在哪 |
|---|---|---|---|
| meta | `--ds-fs-meta` | 11px | meta/徽标/小节标签（uppercase + `--ds-ls-label` 0.5px）；**11px = 可读硬下限** |
| hint | `--ds-fs-hint` | 12px | hint/caption/次要说明 |
| btn | `--ds-fs-btn` | 12px | 按钮文字（500） |
| body | `--ds-fs-body` | 13px | 正文/表单标签/输入/选项 |
| title | `--ds-fs-title` | 15px | 面板标题、卡片问题行（600） |
| lead | `--ds-fs-lead` | 17px | 长文/引导正文（Apple body 17；手机正文档） |
| display | `--ds-fs-display` | 20px | 页面级大标题/空态主句（600，-0.01em） |
| title-lg | `--ds-fs-title-lg` | 28px | 手机大标题档 |
| 具名槽·气泡 | `--ds-fs-bubble` | 14.5px | 主对话气泡（2026-09-12 裁定值） |
| 具名槽·会话行 | `--ds-fs-row` | 13.5px | 侧栏会话行主文案 |
| 具名槽·次标题/次级正文 | `--ds-fs-subtitle` | 14px | body↔title 之间一档（原胶囊/卡正文两槽同值合并，2026-11-04） |
| 具名槽·卡主标题 | `--ds-fs-card-hero` | 21px | 过渡卡主标题（官网 auth 域档） |
| 具名槽·手机次说明 | `--ds-fs-mobile-sub` | 15px | 手机档次正文 |
| 具名槽·手机正文/输入 | `--ds-fs-mobile-callout` | 16px | 手机正文/输入件（iOS 不缩放硬下限） |
| 具名槽·桌面 callout | `--ds-fs-callout` | 16px | 桌面语境 16px（文档正文/弹窗标题/图标字形；与 mobile-callout 同值异域） |
| 具名槽·图标盒字形 | `--ds-fs-glyph` | 8px | 固定尺寸图标盒内 2–3 字符（14–18px 盒；禁作正文） |
| 手机映射 | `--mfs-min/sub/body/callout/title` | 13/15/17/16/28 | 一跳别名指向上面同值槽（禁复制字面量） |

## 3. 收敛判定（近似档为何归这一档）

① **圆角：以「1px 不可辨 + 语义同族」为归并判据。**
7px/9px/11px/13px/15px/17px 六个孤立档与相邻阶梯只差 1–2px，在 28px 级小件上肉眼不可辨
⇒ 归到语义最近的正档（小片→chip/input；卡片→xl/card-lg；大卡→2xl）。3px/5px/1px 同理归 badge/chip。
2px **不归并**：它不是「差一点的 badge」，而是细件专属几何（滚动条拇指 8px 宽上的圆角），
归到 4px 会把拇指变成药丸、改变滚动条观感 ⇒ 立具名例外槽 `--ds-radius-thin`，且只许细件用。
9999px 是「胶囊」的第二种写法（无视觉差异）⇒ 统一到 `--ds-radius-pill`。
`50%` 与 `0` 不是圆角档而是**形态**（正圆/直角），单列形态档，不进刻度、不算跑偏。

② **间距：4px 栅格为纲，但承认一个「半步」实测事实。**
6px 实测 349 处（padding 168 + gap 137 + margin 44），集中在「图标与文字的缝」「chip 内距」两类
场景——收敛到 4px 会让密集控件贴死、收敛到 8px 会让每行膨胀 2px×N 处，两者都是全局视觉回归。
故 6px 以**具名半步档**收编（待建 `--ds-space-1h`），限定小件内缝；10px 同理是既有半步例外，
2026-11-04 起按布局角色分六槽（`inline-x/block-y/inset/inline-gap/stack-gap/indent-x`）。其余档外值（5/7/9/11/13/14/18/22…）无独立语义，一律就近归栅格档。

③ **字号：11px 是硬下限，下限外一律收敛。**
10px 59 处 + 10.5px 13 + 9px 14 + 8.5/9.5/8/7px 9 处 = **95 处低于可读下限**，这是 §9 整改判例
第 1 条（`cfg-hint` 10px）的同类回归 ⇒ 全部收敛 meta 11px（徽标内文可走 11px，徽标高随之 +2px，
迁移清单已单列）。半档 11.5/12.5px 是官网 `--st-fs-label`/`--st-fs-btn` 逐值转录，官网侧不改，
客户端侧收敛 12px（hint/btn 两档本就同值 12）。14px 保留为具名槽（胶囊/卡正文同值两槽），
不并入 13 或 15：胶囊与卡正文在 14 是既有作者认读档，上下一档都会破坏已定稿的面。
18px 无档且语义分裂（epub 标题 vs 图标字形）⇒ 分语义收敛（标题 display 20 / 图标 lead 17）。

④ **文字离边：四场景各自一个内距槽，禁跨场景借值。**
气泡 10×14 与卡片 12×16 与行 8×12 与按钮 6×16 四个值都**不是**栅格对齐的巧合，而是
「阅读容器的呼吸量」：气泡要贴字（横 14 因为气泡本身有 16px 圆角外沿）、卡片要撑开（横 16）、
行要密（横 12）、按钮要显手感（横 16 配 pill）。收敛方向 = **冻结为四个具名槽**，
而不是把它们统一成 8/12/16 的单值——统一会同时改掉气泡与按钮的观感，属视觉回归。
Ask 气泡的 9×13 是主对话 10×14 的近似复制 ⇒ 收敛主对话槽（同语义不得两档）；
手机层 8×12 独立成槽（手机密度档）。

## 4. 迁移清单（本批不改组件代码；待实施批，需作者批）

> 口径：迁移 = 把档外字面量替换为对应 `var(--ds-*)` 槽。**不含**目标值本身的修改。
> 待建槽（`待建` 标注）须先由作者批新增令牌，再替换。

### 4.1 圆角（合计 63 处档外字面量待收）

| 现值 | 处数 | 目标 | 代表落点 |
|---|---|---|---|
| 7px | 26 | `var(--ds-radius-input)` 8px | `.project-open-btn` projectPanel.css:75 / `.fwd-tip` chat.css:4701 / `.path-chip` projectPanel.css:725 |
| 3px | 21 | `var(--ds-radius-badge)` 4px | `.bubble.ai code` chat.css:1452 / `.cfg-card-badge` sidebar.css:1159 / `.reminder-overdue-tag` reminder.css:453 |
| 2px | 21 | `--ds-radius-thin`（实施批已出生） | 滚动条拇指规则 12 处 / `.dropbox-file-progress` dropbox.css:254 / `.pa-dot` chat.css:3114 |
| 5px | 17 | `var(--ds-radius-chip)` 6px | `.ask-cite` ask.css:231 / `.dvp-chip` delegatePanorama.css:316 / `.tfl-chip` todo-face.css:395 |
| 9999px | 14 | `var(--ds-radius-pill)` 999px | friends.css×9 / dropbox.css×2 |
| 9px | 8 | `var(--ds-radius-button)` 10px | `.fc-icon` chat.css:4667 / `.att-chip` perception.css:363 |
| 1px | 4 | `var(--ds-radius-badge)` 4px | split.css×2 / cards.css×1 / modal.css×1 |
| 11px | 4 | `var(--ds-radius-xl)` 12px | `.dvp-sub` delegatePanorama.css:266 / `.pp-toggle` perception.css:167 |
| 15 / 13 / 17 / 3.5px | 4 | card-lg 16 / xl 12 / 2xl 18 / badge 4 | ask.css:231 · mobileLayer.css · split.css · mobileLayer.css |

### 4.2 间距（档外原子合计 ≈ 315 处）

| 现值 | 处数（padding+gap+margin） | 目标 |
|---|---|---|
| 6px | 349 | `--ds-space-1h`（半步档；2026-11-04 实施批已出生并迁移） |
| 5px | 114 | 4px（紧排）/ 6px（件缝），逐处按语义复核 |
| 3px | 93 | 2px（半步微缝）/ 4px |
| 7px | 66 | 6px（半步）/ 8px |
| 9px | 33 | 8px |
| 18px | 29 | 16px / 20px |
| 11px / 13px | 13 / 6 | 12px |
| 22px / 28px / 36px / 40px | 4 / 5 / 7 / 6 | 24px / 32px 档（密度行带 36px 走 `--ds-density-row-compact`） |

### 4.3 文字离边缘（四场景内距落 token）

| 场景 | 现值 | 目标 |
|---|---|---|
| 气泡·Ask 面 | `9px 13px`（ask.css:217） | `var(--ds-bubble-pad)` 10px 14px |
| 气泡·手机层 | `8px 12px` | `待建 --ds-bubble-pad-mobile` |
| 卡片·浮层卡 | `12px 16px`（chat.css:1837） | `待建 --ds-card-pad` |
| 卡片·面板内卡头 | `8px 10px`（sidebar.css:1147） | `待建 --ds-card-head-pad` |
| 卡片·卡内动作区 | `10px 14px 12px`（sidebar.css:617） | `待建 --ds-card-actions-pad` |
| 列表行·设置行 | `10px 0`（sidebar.css:580） | `待建 --ds-row-pad-card` |
| 列表行·菜单行 | `4px 16px`（sidebar.css:294） | `待建 --ds-menu-item-pad` |
| 按钮四档 | 6×16 / 4×12 / 6×18 / 5×12 | `待建 --ds-btn-pad` / `-sm` / `-primary` / `-send` |
| 徽标 pill | `1px 5px`/`1px 6px`/`1px 7px` 三值并存 | `待建 --ds-badge-pad` 1px 6px |

### 4.4 字号（95 处低于下限 + 52 处半档）

| 现值 | 处数 | 目标 |
|---|---|---|
| 10px | 59 | `var(--ds-fs-meta)` 11px（徽标高 +2px 随之复核） |
| 10.5px | 13 | 11px |
| 9px / 9.5px / 8.5px / 8px / 7px | 14 / 2 / 2 / 3 / 1 | 11px |
| 11.5px / 12.5px | 23 / 24 | 12px（hint / btn） |
| 15.5px | 2 | 15px（title） |
| 18px | 6 | 20px（标题类）/ 17px（图标字形） |
| 19px / 22px | 1 / 3 | 20px（display） |
| 26px | 1 | 28px（title-lg） |
| 13.5px（Ask 气泡借用） | 1（ask.css） | `var(--ds-fs-bubble)` 14.5px |

### 4.5 账本补登（`assets/design-system/tokens.css`）

档外具名槽此前只在运行时（`--ds-*`）出生、账本（`--dl-*`）缺行 7 条，已在本批补登：
`--dl-fs-bubble` 14.5px、`--dl-fs-row` 13.5px、`--dl-fs-capsule` 14px、`--dl-fs-card-body` 14px、
`--dl-fs-card-hero` 21px、`--dl-fs-mobile-sub` 15px、`--dl-fs-mobile-callout` 16px。

## 5. 机器校验（常驻防漂移闸）

```sh
node scripts/check-design-spec-sync.mjs          # 红/绿
node scripts/check-design-spec-sync.mjs --json   # 机器读
```

三组判据（任一不满足即 exit 1）：

1. **spec → 源**：下面 `design-spec-scale` 块每行的（变量名, 值）必须在 `web/css` 中存在，
   且**所有**声明点同值（亮/暗同值只算一次；`var()` 别名解析后比对）。
2. **源 → spec**：令牌源里每个 `--ds-fs-*` / `--ds-radius-*` / `--ds-space-*` 变量必须在块里有行
   ——**新档不许只在代码里出生**。
3. **账本 → 源**：`assets/design-system/tokens.css` 的 `--dl-{fs,r,sp}-*` 与对位 `--ds-{fs,radius,space}-*` 同值。

**改刻度的顺序**：本块（规范）→ `design-system.css`（运行时）→ `assets/design-system/tokens.css`（账本）→ 跑闸。
反向操作（先改代码后补表）会让闸红一次，这是设计意图（漂移必须被人看见）。

### 5.1 机械校验块（本块被 `scripts/check-design-spec-sync.mjs` 解析，禁改格式）

<!-- design-spec-scale:begin -->
| 变量 | 值 | 用在哪 |
|---|---|---|
| `--ds-radius-badge` | `4px` | 徽标 pill、行内 code |
| `--ds-radius-chip` | `6px` | 图标钮、小 chip、卡片容器 |
| `--ds-radius-input` | `8px` | 输入/下拉、面板档按钮 |
| `--ds-radius-button` | `10px` | 对话档按钮、行卡面 |
| `--ds-radius-card-sm` | `10px` | 行卡面小档（与 button 同值两槽） |
| `--ds-radius-xl` | `12px` | 条状容器、页签轨 |
| `--ds-radius-card-md` | `14px` | 面板内卡 |
| `--ds-radius-card-lg` | `16px` | 聊天浮层卡、气泡 |
| `--ds-radius-2xl` | `18px` | 工具卡、大卡 |
| `--ds-radius-shell` | `20px` | 弹窗外壳 |
| `--ds-radius-pill` | `999px` | 胶囊、开关、圆钮 |
| `--ds-radius-thin` | `2px` | 细件例外槽：滚动条拇指/进度条/微圆点（禁他处复用） |
| `--ds-space-half` | `2px` | 半步微缝 |
| `--ds-space-1` | `4px` | 紧排件缝 |
| `--ds-space-2` | `8px` | 控件间 gap、行内距 |
| `--ds-space-3` | `12px` | 卡内块距、行横距 |
| `--ds-space-4` | `16px` | 节间距、弹窗头横距 |
| `--ds-space-5` | `20px` | 面板左右留白 |
| `--ds-space-6` | `24px` | 页面级留白 |
| `--ds-space-8` | `32px` | 大区块留白 |
| `--ds-space-12` | `48px` | 区块级留白 |
| `--ds-space-16` | `64px` | 大区块级留白 |
| `--ds-space-inline-x` | `10px` | 10px 档·内横距（左右内距，151 处） |
| `--ds-space-block-y` | `10px` | 10px 档·内纵距（上下内距，95 处） |
| `--ds-space-inset` | `10px` | 10px 档·四边等内距（padding 单值，5 处） |
| `--ds-space-inline-gap` | `10px` | 10px 档·行内件缝（gap，56 处） |
| `--ds-space-stack-gap` | `10px` | 10px 档·块间纵距（margin 纵轴，49 处） |
| `--ds-space-indent-x` | `10px` | 10px 档·行缩进（margin 横轴，9 处） |
| `--ds-space-1h` | `6px` | 半步件缝例外档：图标+文字小缝、chip 内距 |
| `--ds-space-hair` | `1px` | 发丝缝例外档：分隔线/指示条偏移/发光描边 inset（禁作内距） |
| `--ds-fs-meta` | `11px` | meta/徽标/小节标签（可读下限） |
| `--ds-fs-hint` | `12px` | hint/caption |
| `--ds-fs-btn` | `12px` | 按钮文字 |
| `--ds-fs-body` | `13px` | 正文/表单标签/输入 |
| `--ds-fs-row` | `13.5px` | 侧栏会话行主文案（具名槽） |
| `--ds-fs-subtitle` | `14px` | 次标题/次级正文（具名槽；原 capsule+card-body 同值合并） |
| `--ds-fs-bubble` | `14.5px` | 主对话气泡（具名槽） |
| `--ds-fs-title` | `15px` | 面板标题、卡片问题行 |
| `--ds-fs-mobile-sub` | `15px` | 手机次正文（具名槽） |
| `--ds-fs-mobile-callout` | `16px` | 手机正文/输入件（具名槽） |
| `--ds-fs-callout` | `16px` | 桌面语境 16px callout（具名槽，与 mobile-callout 同值异域） |
| `--ds-fs-glyph` | `8px` | 图标盒字形（具名例外槽：14–18px 盒内字符；禁作正文，文本下限仍是 11） |
| `--ds-fs-lead` | `17px` | 长文/引导正文 |
| `--ds-fs-display` | `20px` | 页面级大标题、空态主句 |
| `--ds-fs-card-hero` | `21px` | 过渡卡主标题（具名槽） |
| `--ds-fs-title-lg` | `28px` | 手机大标题档 |
| `--ds-fs-page-back` | `30px` | 页面返回钮图标字形（返回钮单源） |
| `--ds-bubble-pad` | `10px 14px` | 气泡内距（文字离边·气泡） |
| `--ds-row-pad` | `8px 12px` | 列表行内距（标准） |
| `--ds-row-pad-tight` | `8px 10px` | 列表行内距（双行名） |
| `--ds-row-pad-dense` | `6px 12px` | 列表行内距（树/文件密集行） |
| `--ds-row-inset` | `6px` | 行 hover 面左右内缩 |
| `--ds-input-pad` | `6px 10px` | 面板档输入内距 |
| `--ds-input-pad-lg` | `8px 12px` | 卡内档输入内距 |
| `--ds-modal-head-pad` | `12px 16px 12px 20px` | 弹窗头内距 |
| `--ds-modal-body-pad` | `12px 20px 20px` | 弹窗体内距 |
| `--ds-card-pad-compact` | `6px 10px` | 工具卡紧档内距 |
| `--ds-capsule-pad` | `6px 8px 6px 14px` | 标题胶囊内距（手机/基线档） |
| `--ds-capsule-pad-desk` | `4px 7px 4px 12px` | 36px 固定高胶囊内距（桌面腿） |
| `--ds-capsule-gap` | `6px` | 胶囊内件缝 |
| `--ds-capsule-end-inset` | `4px` | 桌面胶囊两端对称内缩 |
| `--ds-capsule-end-size` | `26px` | 端部动作视觉盒 |
| `--ds-capsule-end-icon` | `13px` | 端部图标视觉盒 |
| `--ds-capsule-end-radius` | `calc(var(--capsule-h) / 2 - var(--ds-capsule-end-inset) - var(--ds-border-width))` | 同心内角 |
| `--ds-media-pill-pad` | `10px 18px` | 「展开 N」钮内距 |
| `--ds-tab-pad-x` | `10px` | 看板页签横内距 |
| `--ds-tab-track-pad` | `3px` | 页签轨内距（微缝） |
| `--ds-card-gap` | `var(--ds-space-3)` | 卡内块距（派生） |
| `--ds-section-gap` | `var(--ds-space-4)` | 面板节间距（派生） |
| `--ds-density-row-compact` | `36px` | 紧凑行带 |
<!-- design-spec-scale:end -->

## 6. 反例与纪律

- ✗ 在组件里写 `border-radius: 7px` / `font-size: 10px` / `padding: 9px 13px`——档外值，闸与
  `check-design-tokens.mjs` 双向会红。
- ✗ 「表里没有但我觉得好看」的新档：要立档先立具名槽（`--ds-*`）+ 本表登记 + 账本回填，三步齐才算档。
- ✗ 同一语义两档并存（例如 Ask 气泡 13.5 与主对话气泡 14.5）：同语义只能一档，第二档 = 跑偏。
- ✗ 账本与运行时各写一个值（`--dl-*` 与 `--ds-*` 不同值）：这是本闸 ③ 组的红。

## 7. 取值、取形与立槽（原「执行面」独有条目并入 · 2026-11-18）

> 本节是原先分居 stand 仓与 stand-dev 的同名「执行面」件的独有条目，随两件合一并入本文。
> 与上文冲突时**以上文四表为准**——本节只讲怎么用，不另立值。

**取用四步**

1. **取值**：从 §2.1–§2.4 取变量名，写 `var(--ds-*)`；表里没有档 → 停手，先按下方「立槽三步」补表。
2. **取形**：形状（宽度/高度/三档组合）按设计规格书或既有等价组件套用，禁自造组合。
3. **自检**：`node scripts/check-design-spec-sync.mjs`（规范↔代码三向同值）与
   `node scripts/check-design-tokens.mjs`（禁裸字面量）**双绿**；前端改动再走截图驱动三断点目检。
4. **写规格书时**：「视觉规格」节逐项落到四张表的**变量名**，不写裸值——qa 转 Playwright 断言
   用变量名最稳（改值不改断言）。

**立槽三步（表里没档时的唯一合法路径）**

1. 在运行时令牌层出生具名槽（`css/design-system.css` 的 `--ds-*`，带注释写「值 + 用途 + 反例」）；
2. 规范面登记（本文 §2 的 spec 表 + 账本 `assets/design-system/tokens.css` 的同值镜像）；
3. 跑 `check-design-spec-sync` 转绿——三项齐才算「档」，缺任一 = 跑偏。

**反例（再次出现 = 回归）**

- ✗ `border-radius: 7px` / `font-size: 10px` / `padding: 9px 13px`——档外裸值。
- ✗ 同语义两档并存（Ask 气泡 13.5px 与主对话气泡 14.5px）。
- ✗ 「我觉得 14px 更好看」式的单点改动：改值要改令牌层，且同步规范表与账本，不是改组件。
- ✗ 把四场景内距统一成单值（气泡/卡片/行/按钮各有呼吸量，统一即视觉回归）。
