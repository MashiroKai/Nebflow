# design-language — Stand 统一设计语言（Plugin.md）

## 1. 速览

design-language 是 Stand 客户端的**唯一视觉基准包**：以官网 mystand.space 的真实 design tokens（`stand-website` 仓 `app/globals.css` @theme 块）为基准、与客户端 Sapphire Glass 玻璃体系对齐后固化的设计语言。节点装载后获得「按档取值」的能力——配色、字体阶梯、圆角阴影阶梯、间距栅格、动画时长缓动、组件固定尺寸档、文案 tone，全部给了值 + 用途 + 反例。内含 1 个 skill：

- `skills/design-language/`——设计语言规范本体（SKILL.md 规则面）+ `assets/tokens.css`（token 账本，可直接引用）+ `assets/components.css`（组件原型参考实现）+ `references/`（各条规范的单主题正文：几何刻度 / 按钮 / 毛玻璃 / 输入面 / 面板族 / **渐进式披露与无重复 / 解释性文字写作**）。

**包纪律一句话**：改任何 UI 前先查本包 token 表——值从表里取，档从表里选；表里没有的先补表再使用，禁止裸值直写。

## 2. 安装与启用

- **安装**：本包随 Stand 主仓分发（`src/main/resources/seed/plugins/design-language/`），随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（改前端样式、新 UI 组件、面板整改、设计语言、视觉规范、截图走查等）。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/design-language/revoke` / `nebflow plugin revoke design-language`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载）。

## 3. 目录结构

```
design-language/                 （包根）
  plugin.json
  Plugin.md                      ← 本文件（包根使用指导件）
  skills/
    design-language/
      SKILL.md                   ← 规范规则面：十节规范 + 前端设计四条要求（§11）+ 使用规则表 + 反例（注入面）
      assets/
        tokens.css               ← token 账本：--dl-* 全量定义，亮/暗双主题，逐 token 注释值+用途+反例
        components.css           ← 组件原型参考实现：弹窗/卡/按钮/输入/行/徽标 的 sm/md/lg 固定档
      references/                ← 单主题规范正文（改判据改这里；脚本只是执行器）
        design-language-tokens.md   ← 四类几何刻度定版（圆角/间距/内距矩阵/字号阶梯）
        flat-button-spec.md         ← 按钮规范：①扁平六态 §1–§4 ②统一来源 §5/§6（B1/B2 + 族登记表）
        glass-spec.md               ← 毛玻璃唯一标准
        input-surface-spec.md       ← 输入面唯一造型
        panel-spec.md               ← 面板族唯一版式
        disclosure-dedup-spec.md    ← 渐进式披露 + 单一来源无重复（Q1–Q6 / U1–U6 / 折叠预算表 / 判定面登记表）
        explanatory-copy-spec.md    ← 解释性文字写作规范（位置档 / 长度预算表 / W1–W6）
```

## 4. 三件怎么配合

| 件 | 角色 | 什么时候读 |
|---|---|---|
| SKILL.md | 规则与判例（值+用途+反例、场景→字号/圆角/时长 对照表、文案 tone） | 每次动 UI 前通读；拿不准哪档时查表 |
| tokens.css | 机器可读账本（`--dl-*`，亮暗双档） | 写 CSS 时对着抄值；客户端已有同名 token 的，以客户端 `base.css`/`sapphire.css` 为准、此处为账本镜像 |
| components.css | 组件原型参考实现 | 新建组件前先查有没有等价原型，有则按档套用 |

- **与客户端实现的关系**：客户端 `src/main/resources/web/css/`（base.css 中性阶 / sapphire.css 玻璃体系）是 token 的**运行时单源**；本包 tokens.css 是同值镜像账本。改值先改客户端 CSS，再回填本包（两处必须同值，发现漂移以客户端为准并回改本包）。
- **包内分工**：本包自包含——值/档（取值取形）在本包 SKILL.md + tokens.css + components.css；「能与不能」的硬裁定（弹窗禁 overlay 遮罩、面板必须毛玻璃、控件统一玻璃质感、主操作实色族、字重方案 B）同在本包 §2「视觉硬裁定」节，冲突时以该节为准。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| design-language/SKILL.md | 设计语言十节规范（配色/字体/字距行高/圆角阴影/间距/动画/组件尺寸档/文案 tone）＋前端设计四条要求（§11 渐进式披露 / 单一来源无重复 / 统一按钮来源 / 解释性文字），逐 token 值+用途+反例 |

### 5.1 四条前端设计要求的机检对照（改判据改 references 正文）

| 要求 | 正文 | 机检脚本（`make frontend-gates` 已含；单闸 target 同名） |
|---|---|---|
| 渐进式披露 · 无重复 | `references/disclosure-dedup-spec.md` | `scripts/check-ui-structure.mjs`（`make ui-structure`） |
| 统一按钮来源 | `references/flat-button-spec.md` §5/§6 | `scripts/check-button-source.mjs`（`make button-source`）＋`check-flat-buttons.mjs` |
| 解释性文字写作 | `references/explanatory-copy-spec.md` | `scripts/check-copy-budget.mjs`（`make copy-budget`） |

## 6. 选 skill 决策树

- 改/写任何 Stand 客户端 UI（面板、弹窗、卡片、按钮、列表、动效、文案）→ `design-language`。
- 新建组件 → 先查 `components.css` 原型表，有等价物按档复用；没有 → 按 token 表补一套，并把原型回填 components.css。
- 出设计规格书 / 做视觉评审 → 按 SKILL.md §10 使用流三步（取值 → 取形 → 截图验证）执行；规格书的「视觉规格」节全部取值来自本包 token 表。

## 7. 共享件专节

- `assets/tokens.css`：token 账本（唯一共享件之一）。节：①配色（品牌绿/蓝宝石/语义载体/中性阶/玻璃）②字体（字族+阶梯）③字距行高 ④圆角阴影 ⑤间距栅格 ⑥动画。
- `assets/components.css`：组件原型（唯一共享件之二）。弹窗 sm/md/lg、卡 sm/md/lg、按钮 md/sm/primary/ghost/danger/chip、输入、列表行、徽标 pill、空态。

## 8. 扩展位

暂无登记候选。新增 token 或组件档：先在客户端 CSS 落地验证 → 回填 tokens.css/components.css → SKILL.md 对应表加行（值+用途+反例三件套齐才准入表）。

## 9. 维护约定

- **单源纪律**：值改客户端、账本跟随；本包与客户端 CSS 值漂移视为 bug。
- **反例即判例**：每次视觉整改发现的跑偏值（如 10px hint、13.5px 输入、5px 按钮圆角）沉淀进 SKILL.md 反例列，防复发。
- **判据改正文（2026-11-18 起）**：本包 `references/*.md` 是各条规范判据的**唯一正文**，
  可执行脚本（`check-ui-structure` / `check-copy-budget` / `check-button-source` /
  `check-flat-buttons`）逐行读正文里的**机检表**（折叠预算表 / 判定面登记表 / 族登记表 /
  长度预算表 / 例外清单）。改判据的顺序永远是 **正文 → 脚本 → 重锚存量基线**，反了就是放水。
- **新面先登记**：新出现一个「同一信息多处渲染」的页面面、一个新区块、一个按钮族，先把它
  登记进对应正文的机检表，再让门禁判红；不登记的面**不受约束**（这是明示的覆盖边界，不是
  「全站已干净」）。
- **本文件定位**：Plugin.md 是按需 Read 面——SKILL.md 是注入面权威；两者不一致时以 SKILL.md 为准并回改本文。
