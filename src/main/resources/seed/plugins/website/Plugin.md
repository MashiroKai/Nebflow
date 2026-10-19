# website — 插件使用指导（Plugin.md）

> 本文件是 website 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

website 是官网（mystand.space）开发与发布包：节点获得 Next.js/Tailwind 前端实现、设计 tokens 复用与三端响应式排版、Vercel 生产部署双域发布三块能力。内含 3 个 skill：

- `skills/website-frontend/`——官网前端开发域知识：Next.js 16 App Router + React 19 + TypeScript strict、Tailwind v4 `@theme` tokens、i18n `[lang]` 动态路由与词典、组件复用纪律、API Route Handlers 与认证模式、性能规范、代码安全（XSS/JWT/env 变量）与验证流。
- `skills/website-design/`——官网视觉设计域知识：设计 tokens 复用纪律（永不裸写 hex）、层级/间距节奏/排版/动效规范、硬约束（无 emoji、克制高级感、wordmark 全小写）、Pop 目检与三端响应式验证流。
- `skills/deploy-website/`——官网发布纪律：push 生产分支即触发 Vercel 自动生产部署、手动兜底通道、发布验收（部署 API 状态或线上内容指纹）与缓存误判防线。

**包纪律一句话**：本包只服务 mystand.space 官域——结构与 API 归 frontend、视觉归 design、发布归 deploy；Nebflow 主仓前端不在本包（另配 `nebflow-frontend-dev`），其他网站不适用本包。

## 2. 安装与启用

- **安装**：本包不属 seed 官方分发，走**手工安装**：自插件仓获取整个包目录，拷入实例的插件目录即落位生效（拷包时连同本文件一起带走）。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（改官网、官网加页面改文案、官网视觉调整、官网发布上线等），派发时注入本 Plugin.md（SKILL.md 按需查阅）。
- **停用**：`POST /api/plugins/website/disable`（面板派发开关）/ `POST /api/plugins/website/revoke`（封禁，走 API / CLI——面板无封禁入口）。
- **内容变更**：包内任何字节改动都会漂移 digest，新内容对新派发即刻生效（面板「变更」提示条已随 2026-10-21 作者令④退役，不再展示）。

## 3. 目录结构

```
website/                       （包根）
  plugin.json
  Plugin.md                    ← 本文件（包根功能描述件 = 分配注入面；速览 / 安装启用 / 分工导览 / 总目录 / 决策树）
  skills/
    website-frontend/SKILL.md  ← 官网前端开发域知识（按需查阅）
    website-design/SKILL.md    ← 官网视觉设计域知识（按需查阅）
    deploy-website/SKILL.md    ← 官网发布纪律（按需查阅）
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内；官网仓库位置、远端与环境变量等实例侧信息以官网仓库的 AGENTS.md 为准（frontend/design 两个 skill 内有指涉），不随本包分发。

## 4. 包级核心用法（三块能力怎么配合）

> 全量口径以三份 SKILL.md 为准（按需查阅）；本节是分工速查。

| 任务面 | 用哪个 skill | 关键纪律（速查） |
|---|---|---|
| 页面/组件/API/TS 逻辑实现 | website-frontend | 新建前先查既有组件；i18n 永远 zh/en 双语同支持；改后验证三件套（页面渲染/构建检查/`tsc --noEmit`）；无法验证必须明说，不许声称已验证 |
| 视觉设计/配色/动效/布局 | website-design | 先复用后新建，tokens 从 `@theme` 取、永不裸写 hex；无 emoji、克制高级感；wordmark 全小写属既裁口径，改动前先低成本对比展示 |
| 发布上线 | deploy-website | push 生产分支 ⇒ Vercel 自动生产部署；发布验收走部署 API 状态或线上内容指纹；**禁用 `?cb=` 查询串绕缓存**（静态预渲染路由不参与缓存键，只会读到陈旧边缘缓存造成「没部署」误判） |

- **双域纪律**：需真实环境的功能（登录、设备互联）推测试域在线验证——localhost 预览交差已废弃；正式域严格两步发布（先测试域后生产域）。
- **目检**：视觉改动用 Pop 打开页面目检，**三端必测**（desktop 1440 / tablet 768 / mobile 375）。
- **交付口径**：展示改动 before/after diff；解释 WHY 不只 WHAT。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| website-frontend/SKILL.md | 官网前端域知识：技术标准、代码安全（XSS/JWT/env）、验证流与交付口径 |
| website-design/SKILL.md | 官网视觉域知识：tokens 复用、视觉原则、硬约束、三端验证流 |
| deploy-website/SKILL.md | 官网发布纪律：push 即部署、验收与缓存误判防线、实例侧信息指涉 |

## 6. 选 skill 决策树

- 改官网**页面结构/新页面/API 路由/TypeScript 逻辑** → `website-frontend`。
- 改官网**视觉**（配色、间距、动效、排版、响应式）→ `website-design`（结构性与 API 逻辑归 website-frontend）。
- 要**发布上线**（部署、发布验收）→ `deploy-website`（前端/设计改动先按各自验证流自验过再走发布）。
- 任务是 **Nebflow 主仓 web/ 前端** → 不在本包：另配 `nebflow-frontend-dev`。
- 目标**不是 mystand.space**（其他网站）→ 不适用本包。
- 三者常连用：一次官网迭代 ＝ frontend 改结构 ＋ design 对视觉 ＋ deploy 发布，各走各的验证流。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于三个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。部署链路的实例侧细节（仓库位置、Vercel 组织标识、安装脚本位）属官网仓库面，以官网仓库 AGENTS.md 为权威，不复制进本包。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 新增官网子站点/子域的部署纪律 | 该子域生产跑通且部署口径稳定沉淀 | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **分工边界同步**：frontend 与 design 的「结构性 vs 视觉」边界若调整，两侧 SKILL.md 与本文件 §4 同步。
- **部署口径更新**：自动部署/验收/缓存判据的口径变化随 `deploy-website/SKILL.md` 演进，本文件只保持速查与指针一致。
- **域边界不放宽**：「仅限 mystand.space 域」是包级硬约束，扩域须先改 description 与本文件 §1/§6。
- **本文件定位**：Plugin.md 是分配注入面（作者令 2026-10-21「plugin.md = 插件必要文件机制」批）——插件分配时整文注入给 agent；SKILL.md 是按需查阅权威，两者不一致时以 SKILL.md 为准并回改本文。
