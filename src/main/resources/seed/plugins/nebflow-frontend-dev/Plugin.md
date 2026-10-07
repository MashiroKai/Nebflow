# nebflow-frontend-dev — 插件使用指导（Plugin.md）

> 本文件是 nebflow-frontend-dev 插件的**包根使用指导件**（Plugin.md）：装载器不注入本文件、不参与选配；维护者与按需 Read 的节点/用户以此为包内地图（用户＋模型可见的按需 Read 面）。选配判定只看 plugin.json 的 description；作业指令在各 `skills/<skill>/SKILL.md`（spawn 时全文注入）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`，该替换仅发生在 SKILL.md 注入文本）。

## 1. 速览

nebflow-frontend-dev 是客户端前端开发域知识包：节点获得三层分明的域知识——实现规范（结构与标准）、视觉裁定（用户成文硬规则，最高优先级）、软参考案例库（成熟范式适配与踩坑沉淀）。内含 3 个 skill：

- `skills/nebflow-frontend/`——前端实现规范：web/ 无构建 vanilla JS 技术标准（无 bundler、CSS 变量、语义标签、XSS 防护）、Sapphire Glass 设计系统（玻璃组件/双主题/`--sapphire` 强调色）、设计原则六条（层级/深度/间距/排版/一致性/打磨）、硬约束（无 emoji/无 accent bars/先复用后新建）、截图驱动验证流与响应式三档。
- `skills/visual-style/`——用户视觉裁定：弹窗禁 overlay 遮罩但面板必须毛玻璃、可交互控件统一玻璃质感（主发送按钮微信绿实色族）、字重方案 B（中度）、消息气泡与侧边栏激活态语义、总基调（低调克制专业感）。**最高优先级**——与本包其他内容冲突时以它为准。
- `skills/design-system/`——UI/UX 软参考案例库：功能类型 → 成熟范式（微信/Spotlight/HIG 等）→ Nebflow 适配规则 → 可断言点 → 踩坑记录；案例模板六件套与 [DESIGN-LESSON] 沉淀载体。

**包纪律一句话**：三层不混装——visual-style 管视觉裁定（冲突终裁）、nebflow-frontend 管结构与实现规范、design-system 只做软参考（冲突时以 visual-style 与用户裁定为准）；自验方法论不在本包（另配 `nebflow-qa` 插件的 frontend-verification）。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发，随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（改 web/ 前端页面与组件、写 JS/CSS/HTML、调弹窗与交互样式、落地毛玻璃视觉风格、设计/评审新 UI 交互等），派发时三份 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/nebflow-frontend-dev/revoke` / `nebflow plugin revoke nebflow-frontend-dev`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
nebflow-frontend-dev/                （包根）
  plugin.json
  Plugin.md                          ← 本文件（包根使用指导件：速览 / 安装启用 / 分层导览 / 总目录 / 决策树）
  skills/
    nebflow-frontend/SKILL.md        ← 前端实现规范：技术标准·Sapphire Glass·设计原则·硬约束·验证流（注入面）
    visual-style/SKILL.md            ← 用户视觉裁定：铁律六条·Evidence·维护条款（注入面·最高优先级）
    design-system/SKILL.md           ← UI/UX 软参考案例库：案例模板·已沉淀案例·追加纪律（注入面）
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内。

## 4. 包级核心用法（三层怎么配合）

> 全量口径以注入的三份 SKILL.md 为准；本节是分层速查。

| 层 | skill | 管什么 | 冲突裁决 |
|---|---|---|---|
| 视觉裁定 | visual-style | 弹窗/控件/字重/气泡/侧边栏的成文硬规则；改前端样式前必读 | 终裁——与任何旧做法、旧记忆冲突时以它为准 |
| 实现规范 | nebflow-frontend | 技术标准、设计系统、设计原则、硬约束、验证流 | 让位于 visual-style |
| 软参考 | design-system | 同类功能的成熟范式与适配规则、可断言点先例、踩坑 | 让位于 visual-style 与用户裁定 |

**实现规范速查（nebflow-frontend）**：无构建 vanilla JS（ES modules，无框架无 bundler）；颜色/尺寸一律 CSS 变量禁裸 hex；动态文本用 `textContent` 防 XSS；每视口至多一个彩色强调事件；间距 4px 基数；标题字重 500-600 封顶正文 400 禁 700；无 emoji、无装饰强调条；新建组件前先查既有等价物。**验证流**：改前截图现状＋查 console 既有错误，改后截图＋零新增错误——不像样立即迭代，不验证不许报完成；无法截图时必须明说；响应式三档（desktop/tablet/mobile）都要看。

**视觉裁定语义（visual-style）**：禁的是弹窗**背后的 overlay 层**（背景暗化/模糊），面板**本身**必须毛玻璃——两个层次不可混淆；具体数值参数以本项目前端 CSS 实现为准（本 skill 锁定语义与出处，不复制可从代码读到的值）。

**案例库用法（design-system）**：产出设计规格书前必读——先检索同功能类型案例，复用范式与适配规则，规避已记录的坑；视觉评审可查可断言点先例；UI 被打回归因后按案例模板追加新案例（Evidence 引用必须真实可溯源，终审会抽查）。

- **与其他包的分工**：只设计不实现（规格书/视觉评审）另配 `design-spec`；功能自验方法论（Playwright harness/契约断言）另配 `nebflow-qa`；HTML 卡片与社交图另配 `design-cards`。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| nebflow-frontend/SKILL.md | 前端实现规范：无构建技术标准、文件结构、Sapphire Glass 设计系统、设计原则六条、硬约束、截图驱动验证流与响应式三档 |
| visual-style/SKILL.md | 用户视觉裁定：铁律六条（弹窗/控件/字重/气泡/侧边栏/基调）、Evidence 依据、替换式维护条款 |
| design-system/SKILL.md | UI/UX 案例库：分层纪律、案例模板六件套、已沉淀案例（弹窗内搜索、图谱视图）、追加与溯源纪律 |

## 6. 选 skill 决策树

- **改 web/ 前端代码**（布局/组件/交互/主题）→ `nebflow-frontend`；凡碰样式（弹窗/按钮/气泡/侧边栏或新增任何 UI 组件）→ **必读** `visual-style`。
- **设计或评审新 UI/交互**（弹窗、搜索、列表、面板、动效——产出规格书前）→ `design-system`（检索同功能类型案例）。
- **UI 被用户打回**（归因后）→ `design-system`（按模板追加案例，Evidence 可溯源）。
- 只设计不实现（出规格书/做视觉评审）→ 不进本包：另配 `design-spec`。
- 深度自验（Playwright harness/wire 探针/契约断言）→ 不进本包：另配 `nebflow-qa` 的 frontend-verification。
- 产出 HTML 卡片/社交图 → 不进本包：另配 `design-cards`。
- 三 skill 常连用：一次前端改动＝visual-style 定裁定 ＋ nebflow-frontend 管实现 ＋ design-system 供范式与坑。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于三个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。

## 8. 扩展位

暂无登记候选。案例库条目随设计闭环在 `design-system` skill 内自然沉淀（不入本表）；新视觉裁定按 visual-style 的维护条款走替换流程（不并存），亦不入本表。

## 9. 维护约定

- **分层纪律不混装**：visual-style（硬裁定）与 design-system（软参考）的边界是本包的骨架——范式冲突裁决链、追加案例的溯源要求变更时，三侧表述同步核对。
- **裁定替换制**：新视觉裁定到达 → 按维护条款**替换**对应铁律，不与旧条目并存；同步核对其余铁律未被波及。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
