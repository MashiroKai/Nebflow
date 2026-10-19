# design-cards — 插件使用指导（Plugin.md）

> 本文件是 design-cards 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

design-cards 是卡片与社交图设计制作包：节点获得两块能力——HTML 卡片视觉规范执行（颜色/字体/圆角/SVG 嵌入/暗色模式）与社交媒体图集生产（小红书图文、公众号封面、文章封面：杂志风与瑞士国际主义两套排版系统）。内含 2 个 skill：

- `skills/card-design/`——Card 工具 HTML 输出的视觉设计规范：CSS 变量配色、SVG 嵌入两策略（`<img>` 引用可靠 / 内联支持暗色覆盖）、自包含 HTML 纪律、Canvas 直答回传按钮规范。
- `skills/guizang-social-card-skill/`——社交图集生产管线：intake → 提炼故事 → 选风格模式 → 逐页规划 → 种子模板复制 → 渲染导出 → 交付；携带版式库 `references/`（15 件）、双风格种子模板与背景资产 `assets/`、自动校验脚本 `validate-social-deck.mjs`。

**包纪律一句话**：HTML 一律自包含（样式内联、禁外部 CSS/JS、图表用 SVG 不用栅格 `<img>`）；同一图集内两种视觉系统不混用；本包借用的上游风格系统与外来源 skill 永不回改。

## 2. 安装与启用

- **安装**：本包不属 seed 官方分发，走**手工安装**：自插件仓获取整个包目录，拷入实例的插件目录即落位生效（拷包时连同本文件一起带走）。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（做 HTML 卡片、做小红书图文、做公众号封面、社交图配图、卡片视觉规范落地等），派发时两份 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/design-cards/revoke` / `nebflow plugin revoke design-cards`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
design-cards/                          （包根）
  plugin.json
  Plugin.md                            ← 本文件（包根使用指导件：速览 / 安装启用 / 管线导览 / 总目录 / 资产登记）
  skills/
    card-design/SKILL.md               ← HTML 卡片视觉规范（注入面）
    guizang-social-card-skill/
      SKILL.md                         ← 社交图集生产管线（注入面）
      validate-social-deck.mjs         ← 图集自动校验（R1-R7，退出码判 FAIL）
      references/                      ← 版式库（15 件，索引见 §5）
      assets/
        template-editorial-card.html   ← Editorial 杂志风种子模板
        template-swiss-card.html       ← Swiss 瑞士风种子模板
        magazine-bg-webgl.js           ← 电子杂志 WebGL 墨流背景层
        screenshot-backgrounds/        ← 截图衬底素材（style-a 纸墨 5 底 / style-b 高饱和 4 底）
```

## 4. 管线导览（两 skill 怎么配合）

> 全量口径以注入的两份 SKILL.md 为准；本节是分工速查。

- **规范面（card-design）**：凡用 Card/Pop 工具产出 HTML 卡片、内联 SVG 图表、可视化报告——按其规范执行：禁 emoji、禁硬编码色值、禁装饰性强调条；配色一律走 CSS 变量；SVG 嵌入默认 Strategy A（`<img>` 引用，可靠），需要暗色模式 CSS 覆盖时走 Strategy B（内联）。
- **生产面（guizang-social-card-skill）**：从需求到成图的完整管线——

| 步 | 做什么 | 关键判据 | 全量口径 |
|---|---|---|---|
| ① Intake | 收平台/比例/素材/品类 | 小红书 11 品类先过能力圈（圈外如实推回，不硬套版式）；纯文字无图先过「三选一」图源问询（一次性，不反复催） | SKILL intake 与能力圈节 |
| ② 提炼故事 | 源文本 → 逐页计划 | 每页只承载一个观点；小红书常规 5-9 页；公众号固定 21:9＋1:1 封面对（同一 HTML 成对检查） | SKILL 故事提炼节 |
| ③ 选风格 | Editorial 杂志风或 Swiss 瑞士风二选一 | 按 editorial intent 选，不按品类查表；同一图集不混用两套系统 | SKILL 风格模式节 |
| ④ 规划页面 | 内部 page plan | 3:4 竖版先查防欠填口径；内容须覆盖 ≥75% 画布高度，大留白要有理由 | SKILL 页面规划节 |
| ⑤ 复制种子模板 | 拷对应模板为任务 `index.html` | 禁从零写 HTML；不串用对方风格的 class 体系（串用即碎版） | SKILL 种子模板节 |
| ⑥ 渲染导出 | 截图工具逐 `.poster`/`.cover` 导出 | 文字压照片走图文叠合口径（主体映射、裁剪纪律、缩略图测试）；用户截图不拉伸不重设计 | SKILL 构建渲染与图片处理节 |
| ⑦ 交付 | 先给用户看，按需自动核查 | 校验脚本按请求才跑（不静默预跑）；FAIL 修复后重导再交付 | SKILL 交付节 |

- **自动校验**：`node skills/guizang-social-card-skill/validate-social-deck.mjs <task-dir>`——R1 溢出 / R2 页脚碰撞 / R3 Swiss 大字重 / R4 最小字号 / R5 四横带密度 / R6 大标题行数 / R7 figure 默认边距漂移；退出码 1 即有 FAIL，WARN 逐条读。交付节拍以 SKILL 为准（先给用户看，不先跑校验拖慢反馈）。
- **网络取图边界**：用户无图时的 web 取图是 fallback 路径（先问后取、来源留痕、版权交用户裁定）；不预设任何环境的网络可达性，取不到如实降级为纯排版方案，不凑图。

## 5. 各技能总目录与按需 Read 索引

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| card-design/SKILL.md | Card 工具 HTML 输出视觉规范：配色变量、SVG 嵌入两策略、暗色适配、自包含纪律、Canvas 直答回传按钮 |
| guizang-social-card-skill/SKILL.md | 社交图集生产管线：能力圈、双风格系统、种子模板、图文叠合处理、交付与校验 |

`guizang-social-card-skill/references/`（何时读什么）：

| 件 | 何时读 |
|---|---|
| platform-specs.md | 定输出比例/尺寸/命名时 |
| style-system.md | 落 Editorial/Swiss 视觉规则与反模式时 |
| theme-presets.md | 选配色主题（6 杂志主题 / 4 Swiss 强调色）时 |
| layout-recipes.md | 选逐页版式结构（M01-M16 / S01-S12）时 |
| components.md | 需要共享组件规格（字阶/最小可读字号/间距 token/图标规则）时 |
| background-systems.md | 做电子杂志 WebGL/纸墨背景层时 |
| portrait-fill.md | 3:4 竖版防欠填适配时 |
| content-planning.md | 封面钩子/分页/文案压缩时 |
| production-workflow.md | HTML/CSS 渲染与图片处理实现时 |
| image-overlay.md | 文字压照片（照片合格判定/局部压暗/主体映射）时 |
| screenshot-treatment.md | 用户供截图的衬框与设备 chrome 处理时 |
| map-component.md | 内容含空间关系（路线/位置/漫步图）时 |
| title-shortener.md | 公众号 21:9＋1:1 封面对或跨平台复用标题时 |
| category-cookbook.md | 用户点名小红书品类、需路由 recipe 与确认范围时 |
| qa-checklist.md | 交付前最终核查时 |

card-design 无 references（规范全量在其 SKILL.md 单文件内）。

## 6. 选 skill 决策树

- 给 **Card/Pop 工具的 HTML 产出**定样式、嵌图表、做暗色适配 → `card-design`。
- 要**成套社交图**（小红书图文、公众号封面对、轮播图、文章封面）→ `guizang-social-card-skill`（品类先过能力圈）。
- 图表/架构图进卡片 → 图本体由可视化工具链生成，嵌卡规范回 `card-design`；复杂可视化生产另配 `visual-report`。
- 要**设计规格书/视觉评审**（只设计不产出图）→ 不进本包：另配 `design-spec`。
- 需求落在能力圈外（纯摄影大片摆盘、日常 OOTD 全身、氛围感装饰风等）→ intake 即如实推回，不硬套版式。

## 7. 共享件专节（模板与资产）

| 资产 | 用途 | 使用方 |
|---|---|---|
| `skills/guizang-social-card-skill/assets/template-editorial-card.html` | Editorial 杂志风种子模板（主题 token/三种海报尺寸/封面对预览框/背景层全预接线） | guizang 管线第⑤步 |
| `skills/guizang-social-card-skill/assets/template-swiss-card.html` | Swiss 瑞士风种子模板（accent token 版，预接线同上） | guizang 管线第⑤步 |
| `skills/guizang-social-card-skill/assets/magazine-bg-webgl.js` | 电子杂志 WebGL 墨流背景层 | Editorial 氛围层 |
| `skills/guizang-social-card-skill/assets/screenshot-backgrounds/` | 截图衬底素材两组（style-a 纸墨系 5 底 / style-b 高饱和系 4 底） | 截图衬框 |
| `skills/guizang-social-card-skill/validate-social-deck.mjs` | 图集自动校验（R1-R7，退出码判 FAIL） | guizang 管线第⑦步（按请求） |

> 用法与全量口径以 `guizang-social-card-skill/SKILL.md` 为准；card-design 的 CSS 覆盖模板内嵌其 SKILL.md，不设独立资产件。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 新平台规格 | 平台官方规格权威来源确认后补 `references/platform-specs.md` | 落地后同步 §5 索引 |
| 新品类 recipe | 该品类生产中跑通可复现配方 | 补 `references/category-cookbook.md` 并同步 SKILL 能力圈表 |
| 新主题/强调色 preset | 主题 token 成套齐备（背景/文字/对比度） | 补 `references/theme-presets.md` 与对应背景资产 |

> 只登记候选与前置条件；候选落地后迁出本表。

## 9. 维护约定

- **上游不可改性**：guizang skill 借用外部风格系统的视觉原则，但永不回改上游/外来源 skill 及其模板与 references；本包内的修改只落本包文件。
- **模板与手册同步**：改种子模板 class 体系须同步 layout-recipes.md / components.md 的引用；改校验规则须同步 SKILL 的 R1-R7 描述。
- **能力圈即契约**：品类能力圈变化（新增/退出）须同步 SKILL 能力圈表与 category-cookbook.md，不得只改其一。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
