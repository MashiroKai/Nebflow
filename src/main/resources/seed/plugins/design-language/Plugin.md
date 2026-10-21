# design-language — Stand 统一设计语言（Plugin.md）

## 1. 速览

design-language 是 Stand 客户端的**唯一视觉基准包**：以官网 mystand.space 的真实 design tokens（`stand-website` 仓 `app/globals.css` @theme 块）为基准、与客户端 Sapphire Glass 玻璃体系对齐后固化的设计语言。节点装载后获得「按档取值」的能力——配色、字体阶梯、圆角阴影阶梯、间距栅格、动画时长缓动、组件固定尺寸档、文案 tone，全部给了值 + 用途 + 反例。内含 1 个 skill：

- `skills/design-language/`——设计语言规范本体（SKILL.md 规则面）+ `assets/tokens.css`（token 账本，可直接引用）+ `assets/components.css`（组件原型参考实现）。

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
      SKILL.md                   ← 规范规则面：八节规范 + 使用规则表 + 反例（注入面）
      assets/
        tokens.css               ← token 账本：--dl-* 全量定义，亮/暗双主题，逐 token 注释值+用途+反例
        components.css           ← 组件原型参考实现：弹窗/卡/按钮/输入/行/徽标 的 sm/md/lg 固定档
```

## 4. 三件怎么配合

| 件 | 角色 | 什么时候读 |
|---|---|---|
| SKILL.md | 规则与判例（值+用途+反例、场景→字号/圆角/时长 对照表、文案 tone） | 每次动 UI 前通读；拿不准哪档时查表 |
| tokens.css | 机器可读账本（`--dl-*`，亮暗双档） | 写 CSS 时对着抄值；客户端已有同名 token 的，以客户端 `base.css`/`sapphire.css` 为准、此处为账本镜像 |
| components.css | 组件原型参考实现 | 新建组件前先查有没有等价原型，有则按档套用 |

- **与客户端实现的关系**：客户端 `src/main/resources/web/css/`（base.css 中性阶 / sapphire.css 玻璃体系）是 token 的**运行时单源**；本包 tokens.css 是同值镜像账本。改值先改客户端 CSS，再回填本包（两处必须同值，发现漂移以客户端为准并回改本包）。
- **与其他包的分工**：只设计不实现（规格书/视觉评审流程）另配 `design-spec`；用户视觉裁定（弹窗无遮罩、玻璃质感、字重方案 B 等硬规则）在 `nebflow-frontend-dev` 的 visual-style——**裁定冲突时以 visual-style 为准**；本包管「值与档」，visual-style 管「能与不能」。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| design-language/SKILL.md | 设计语言八节规范：配色/字体/字距行高/圆角阴影/间距/动画/组件尺寸档/文案 tone，逐 token 值+用途+反例 |

## 6. 选 skill 决策树

- 改/写任何 Stand 客户端 UI（面板、弹窗、卡片、按钮、列表、动效、文案）→ `design-language`。
- 新建组件 → 先查 `components.css` 原型表，有等价物按档复用；没有 → 按 token 表补一套，并把原型回填 components.css。
- 出设计规格书 / 做视觉评审 → 另配 `design-spec`；本包为其提供「视觉规格」节的全部取值。

## 7. 共享件专节

- `assets/tokens.css`：token 账本（唯一共享件之一）。节：①配色（品牌绿/蓝宝石/语义载体/中性阶/玻璃）②字体（字族+阶梯）③字距行高 ④圆角阴影 ⑤间距栅格 ⑥动画。
- `assets/components.css`：组件原型（唯一共享件之二）。弹窗 sm/md/lg、卡 sm/md/lg、按钮 md/sm/primary/ghost/danger/chip、输入、列表行、徽标 pill、空态。

## 8. 扩展位

暂无登记候选。新增 token 或组件档：先在客户端 CSS 落地验证 → 回填 tokens.css/components.css → SKILL.md 对应表加行（值+用途+反例三件套齐才准入表）。

## 9. 维护约定

- **单源纪律**：值改客户端、账本跟随；本包与客户端 CSS 值漂移视为 bug。
- **反例即判例**：每次视觉整改发现的跑偏值（如 10px hint、13.5px 输入、5px 按钮圆角）沉淀进 SKILL.md 反例列，防复发。
- **本文件定位**：Plugin.md 是按需 Read 面——SKILL.md 是注入面权威；两者不一致时以 SKILL.md 为准并回改本文。
