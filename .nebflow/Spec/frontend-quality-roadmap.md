> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 前端质量路线图 — 根因分析与技术路线优化方案

> 2026-08-16 · 响应问题："后端没什么问题，前端就会报错，是我们的技术路线有问题吗？"
> 纯分析，未改动任何源码。数据全部来自本仓库实测，采集方法见附录。

---

## 0. 执行摘要

**结论：不是前端工程能力问题，是前端缺少后端已经拥有的三层防线，且仅有的验证设施存在"测试替身失真"。**

- 后端稳的原因可以精确复述：Scala 3 编译期类型系统（写错字段名编译不过）+ 857 个测试用例 CI 必跑（compile → test → assembly 四道 job）+ qa-backend 独立验收。
- 前端在对应的每一层都是空的：无类型检查（纯 JS）、无构建校验（浏览器直吃源文件）、7 个 Playwright 冒烟用例未进 CI 且需要手动起服务。
- **本轮 viewer 404 的精确根因**：后端静态路由是逐路径硬编码白名单（`WebSocketRoutes.scala:565` 的 `Root / "js" / file` 只匹配单段路径），昨晚插件化把 12 个 viewer 拆到了两层深的 `js/viewers/`——前端代码本身没有错（import 图实测 0 个未解析引用），是**前端发出的合法 URL 在后端路由表里没有条目**。这类跨语言契约失配，TypeScript 和 esbuild 都拦不住，只有"真实服务契约测试"能拦。
- 更刺眼的元问题：昨晚验证其实**做了**且全绿（node --check 12/12、Playwright 27/27、console 0 错、亮暗截图目检）——但 Playwright 用的是 `python http.server` 静态替身（任意深度路径都返回 200），不是真实的 http4s 路由。**测试环境与生产环境行为不一致，验证通过了但产品是坏的。**
- 推荐路线：**先 C（测试关卡，当天止血 + 本周建关卡）再 A（类型渐进，下周起）**；B（esbuild）拦不住本轮问题类型、性价比低，暂缓；D（纯人工纪律）已被昨晚证明不够。总投入约 4-6 个工作日，与后端质量投入对齐。

---

## 1. 质量不对称的量化事实

![质量不对称数据](assets/nb-fe-quality-density.svg)

| 维度 | 后端 (Scala) | 前端 (web/) | 对比 |
|---|---|---|---|
| 产品代码 | 223 文件 / 50,719 行 | 57 文件 / 24,890 行（另有 vendor 12 个库、CSS 16 文件） | 前端约为后端一半 |
| 测试代码 | 92 文件 / 14,067 行 / **857 个用例** | **7 个 Playwright 用例**（148 行） | 122 : 1 |
| 测试是否进 CI | 是（sbt test，每次 push/PR 必跑） | **否**（CI 无任何浏览器测试；用例硬编码 token 和 localhost:8080，需手动起服务） | 决定性差异 |
| 近 30 天 fix 提交 | 331 个 fix 中 142 个纯后端 | **189 个涉及前端（57%）** | 前端以一半的代码量贡献过半修复 |
| 近 7 天改动热度 | +370~3880 行/天 | +733~2570 行/天（8/10-8/15 累计 +9950 行） | 相当；前端改动占存量比例更高 |
| 独立 QA 关卡 | qa-backend | qa-frontend 存在，但 system.md 定位是 **"Rust 迁移前端兼容性审核员"**（对比 Rust 后端契约），不含 Scala 版常规合并回归职责 | 关卡错位 |

---

## 2. 本轮 viewer 404 事故解剖 — 五道关卡为何全部失效

![404穿透链](assets/nb-fe-quality-404-chain.svg)

### 2.1 精确机制（文件级证据）

1. **后端路由只认单段路径**。`src/main/scala/nebflow/gateway/WebSocketRoutes.scala:565`：
   ```scala
   case req @ GET -> Root / "js" / file =>   // 只匹配 /js/xxx.js 两段
   ```
   同文件 :576-577 的注释自己承认了这个坑："Uses manual path parsing because **http4s DSL only matches single path segments**"——monaco 为此手写了多段解析，但 `js/` 分支没有。历史上 `js/locales/` 子目录已经靠特判路由（:558）补过一次，**昨晚 `js/viewers/` 是同一脆弱模式第二次复发**。
2. **昨晚变更**（commit `07528f67`）：fileViewers.js 906→56 行，12 个 viewer 拆为 `js/viewers/<name>.js`（两层路径），ES module 静态 import。
3. **触发时机是懒加载**：`canvas.js:604` 动态 `await import('./fileViewers.js')` → 首屏不请求 viewers 文件，只在打开 Canvas 面板查看文件时才发 `GET /js/viewers/markdown.js` → 三段路径无路由匹配 → 404。
4. **昨晚验证为什么是绿的**（`/tmp/nb-f3-verify.js` 第 2 行注释自述）："Boots the real web UI from the worktree (**python http.server :8978**), WS fully mocked"。python 静态服务器对任意深度路径都返回 200，服务端日志（`/tmp/nb-f3-server.log`）显示 `GET /js/viewers/epub.js 200`。**功能逻辑确实是对的，错的是验证环境没仿真生产路由。**

### 2.2 五道关卡失效表

| # | 关卡 | 昨晚状态 | 失效原因 |
|---|---|---|---|
| 1 | `node --check` | 过（12/12） | 只查语法，不查引用、不查 URL 可达性 |
| 2 | Playwright 回归 27/27 | 过 | 跑在 python http.server 理想路由替身上，非真实 http4s 路由 |
| 3 | `tests/smoke.spec.mjs:64` "loads all JS modules without 404" | **没跑** | 未进 CI；且只监听首屏 networkidle——viewers 懒加载根本不触发，即使跑了也拦不住这次 |
| 4 | qa-frontend | 没介入 | 其 system.md 定义是 Rust 迁移契约审核，不是 Scala 版合并关卡 |
| 5 | CI smoke-test job | 过 | 只 `curl /` 和 `/api/health`，不遍历 JS 资源 |

**结论：这不是"没验证"，是每一道验证都存在保真度缺口。** 人工纪律（选项 D）解决不了"替身失真"这种元问题——人会照着清单打勾，但清单里没有"确认静态服务器行为与生产路由一致"这一项。

---

## 3. 根因结构化（R1-R5，逐项核实）

### R1 无类型检查 — 属实，但需诚实定价

实测（`tsc --checkJs` 零侵入探测，命令见附录）：57 个 JS 文件报 **420 个类型错误**，分布：

| 错误族 | 数量 | 性质 | 对应真实风险 |
|---|---|---|---|
| TS2339 属性不存在（`Element` 上访问 `onclick/hidden/src/dataset` 等） | 337 | DOM 类型精度噪音——缺 JSDoc 标注，**大多数不是真 bug** | 低（会掩盖真信号，需基线治理） |
| TS2304 未声明名字（`lucide`、`marked` 等 vendor 全局） | 47 | 缺 ambient 声明；**与"引用不存在的变量/typo"同族** | 中——这正是后端编译器免费提供的检测 |
| TS2345/TS2554/TS2363 等（参数类型/数量不匹配、签名冲突） | ~36 | 部分是真风险（调用约定漂移） | 中 |

解读：TS 化的收益主要是**防新增错误 + 契约自文档化**（state/ws 消息格式、viewer 协议对象），不是立刻消灭存量 bug。历史 bug 里"对话窗口串台"（session 状态混淆）这类问题，类型化 state（`@typedef {Session}`）能明显缓减；但内存泄漏、性能、竞态类拦不住。

### R2 无构建管线 — 属实，但**本轮 404 的锅它不背

- 事实：`web/` 下无 package.json、无任何构建配置；`index.html:333` 以 `<script type="module" src="js/main.js">` 直出，浏览器运行时解析 57 个 ES module。
- **关键澄清**：实测 import 图（脚本见附录）**0 个未解析 import、模块路径全对**。本轮 404 不是前端把 import 路径写错，而是"前端合法 URL ↔ 后端路由白名单"这份**跨语言契约没有单一事实来源**。esbuild 打包时所有文件都存在会构建成功——**即使昨晚有 esbuild，今天照样 404**。
- 无构建的真实代价（次要但存在）：模块路径错误/循环依赖只能运行时暴露。实测循环依赖 **4 组**：`chatView↔cardRegistry`、`chatView↔utils(经cardRegistry)`、`sidebar↔modal`、`neblink↔dropbox`——ES module 循环在初始化顺序上是隐患。
- 57 个 module = 首屏 57+ 个请求，无 minify/hash——性能上限问题，非正确性问题。

### R3 测试不对称与时机错位 — **主因，决定性**

见第 1 节表格。三个叠加缺陷：
1. **量**：7 vs 857，且 7 个里只有 2 个真正覆盖"页面可用性"。
2. **关卡**：不在 CI、不在合并流程。后端 bug 活不过 `sbt test`；前端 bug 活到用户早上打开 Canvas。
3. **保真度**：唯一的重型验证（昨晚 harness）用 python 静态服务器 + 全 mock WS，与生产路由行为不一致。**qa-frontend 的角色定义还停留在 Rust 迁移专项**，常规前端变更没有强制验证环节。

### R4 规模与模块边界 — 部分属实，中危

- 57 文件 / 24,890 行本身不算失控（vanilla 架构在 3 万行内可维护）。
- 但存在热点巨石：`sidebar.js` **3000 行**、`main.js` **2551 行**、`chat.js` 1950 行——这三个恰是近 7 天改动最频繁的文件（main.js 被改 12 次）。巨石 + 高频改动 + 无类型 = 回归温床。
- `state.js` 被 27 个文件 import（god module，全局单例），`i18n.js` 24 个、`utils.js` 21 个——高扇入意味着这几个文件的任何签名变化影响面是全应用，而当前没有编译器帮忙检查调用方。

### R5 改动密度 vs 验证投入 — 属实

近 30 天 189 个 fix 涉及前端（57%），近 7 天前端日均 +700~2600 行（占 2.5 万行存量的 3%-10%），昨夜一批合入 12+ 前端文件。**后端同等改动密度配了 857 用例 + CI 四道 job + qa-backend；前端同等密度配了 0 道自动关卡。** 修复速度本身不慢（昨晚 23:xx 合入，今早 10:32 已发现），问题全部由用户肉眼发现——质量反馈回路完全外置给了用户。

### 历史前端 bug 归因映射（哪层防线本可拦）

| 历史 bug（用户提供，git 已佐证） | 本可拦截的防线 |
|---|---|
| viewer 插件 404（本轮） | **C1 资源契约测试** / 后端路由通配化 |
| 对话窗口串台（`49233fad`） | TS 类型化 state/session（A）+ 交互 e2e（C3） |
| cardRegistry IntersectionObserver 泄漏（`73c2c9b0` 一次修 5 类内存问题） | C3 长时运行 e2e / perf 断言 |
| persistence.js 注入气泡同步渲染卡死 | C3 交互 e2e |
| 双 DOWN 前端卡死 18 分钟 | C3 + 状态机梳理 |
| lucide.createIcons 全 DOM 重扫（现已收敛至 4 处调用点） | 代码评审/perf 意识（类型与打包均拦不住） |
| 历史消息卡片空白（`78591dcd`） | C3 |

→ 约 2/3 的历史 bug 需要**测试关卡**拦截，约 1/3 可被**类型层**缓减。**这决定了 C 优先于 A。**

---

## 4. 技术路线选项对比

| 维度 | A 渐进 TypeScript | B 轻构建管线 (esbuild/vite) | C 测试关卡加强 | D 人工纪律 |
|---|---|---|---|---|
| 拦住本轮 404 | 否（文件都在，类型/打包全过） | **否**（构建成功，URL 契约仍在后端） | **是**（C1 遍历 252 文件 curl 200，昨晚即可拦） | 否（清单没人跑 252 个 URL） |
| 拦住历史 bug 类型 | ~1/3（串台、契约漂移、typo 族） | 少（import 级错误目前实测为 0） | ~2/3（渲染、内存、交互、资源） | 依赖人，不稳定 |
| 实施成本 | A0/A1：1.5-2.5 天；A2 全量：4-8 周 | 2-3 天 + 部署模型改造（产物进 resources） | C1：0.5 天；C2：1-2 天；C3：2-3 天 | 0 天 |
| 对现有流程侵入 | 低（JSDoc 起步零构建） | 中（引入 node_modules、dist 同步、开发工作流变化） | 低（不碰产品代码） | 无 |
| 风险 | 420 存量错误需基线化治理，否则全是噪音 | 改变"resources 直出"部署模型；vendor/monaco 多级路径打包配置复杂 | Playwright 稳定性需维护（flaky 治理） | 会被Deadline 冲掉 |
| 附带收益 | 契约自文档化、IDE 补全、重构安全 | 57 请求→1 bundle、minify、依赖管理 | 同一套合入后端 CI，流程统一 | 无 |
| 何时见效 | A1 后即防新增；A2 需数周 | 即时（性能） | **C1 当天** | 不可度量 |

**关键判断**：
- **B 是最容易被误买的药**——直觉上"上构建管线"最像"正规化"，但它拦不住本轮 404（import 图本来就是对的），其主要收益是性能与依赖管理，而这两项当前不是痛点。且它会改变 Scala resources 直出的部署模型（产物同步、vendor 深路径打包），反而引入新的失配面。**建议仅当首屏性能成为实测痛点时再评估。**
- **D 已被证伪**：昨晚的流程有验证、有截图、有 console 检查，全绿——问题不是没纪律，是验证环境的保真度没人质疑。人工清单无法发现"替身 ≠ 生产"这类元问题。

---

## 5. 参照系 — 同类产品怎么解

**VS Code / Theia**（与 Nebflow 最相关的参照，因为 Nebflow 前端直接 vendor 了 monaco）：全量 TypeScript 严格模式 + webpack/esbuild 构建 + 分层测试（单元 thousands + smoke test 套件在真实 Electron 里跑、发布前必过）。它们的核心经验不是"用了框架"，而是**每一层防线都自动化**：类型拦契约错、构建拦引用错、smoke 拦集成错。这是百人团队的配置，Nebflow 不需要照搬体量，但"类型 + 真实冒烟"双支柱是下限。

**Open WebUI / LibreChat**（体量与 Nebflow 相当的本地优先 LLM 前端）：React+TS+Vite + Playwright e2e + 严格 CI。它们选框架是因为组件状态复杂度；Nebflow 的 vanilla + `state.js` 单例在 2.5 万行规模仍可控（import 图健康、0 未解析），**不值得为此重写**——需要的是给现有架构补类型层和关卡层。

**Claude Code CLI**：无 web UI 所以无此类问题——反证问题的本质是**浏览器是弱约束运行时**（无编译期、运行时才解析、部分错误静默吞掉），不是前端代码写得差。

**体量匹配建议**：2.5 万行 / 57 文件 / 单人+agent 开发 → 目标不是 VS Code 级防线，而是"**后端已有的东西前端对齐**"：后端有编译器 → 前端上 checkJs/tsc；后端有 sbt test CI → 前端冒烟进同一个 CI；后端有 qa-backend → qa-frontend 职责重定义。

---

## 6. 推荐分阶段路线

![分阶段路线](assets/nb-fe-quality-phases.svg)

### Phase 0 — 止血（当天，0.5 天）

| 项 | 内容 | 说明 |
|---|---|---|
| P0-1 | 路由通配修复 | **nebflow-project 已派单，本方案不重复**。建议方向：`js/**` 递归多段服务 + 路径穿越防护（参照同文件 monaco 分支的手写多段解析），从根上消灭"每加子目录要改路由"（locales 一次、viewers 一次的第三次复发机会） |
| P0-2 | **C1 静态资源契约测试** | 脚本遍历 `web/` 全部 252 个文件 → 对真实启动的服务逐个 `curl -o /dev/null -w '%{http_code}'` 断言 200 → 进 CI（可挂现有 smoke-test job） |

**验收标准（二值，可自动化）**：
- [ ] `find src/main/resources/web -type f` 的每个文件，对真实服务请求均返回 200（含两层深的 `js/viewers/*`、`js/locales/*`、monaco 多级路径）
- [ ] 该断言在 CI smoke-test job 中每次 push 自动执行，失败即红
- [ ] 新增一个静态文件（如 `js/example/test.js`）后不碰后端代码，CI 中该测试自动覆盖到它（证明是遍历而非白名单）

### Phase 1 — 关卡重建（本周，1-2 天）

| 项 | 内容 |
|---|---|
| P1-1 | **C2 Playwright 冒烟进 CI**：CI 里启动真实 jar（现有 smoke-test 已会起服务，直接复用）→ 跑改造版 `smoke.spec.mjs`：首屏零 console error + 零 pageerror；**关键懒加载路径必须点击触发**（打开 Canvas 面板并渲染一个文件、打开任务列表、打开设置）→ 资源 404 监听覆盖整个会话而非仅 networkidle |
| P1-2 | **token 方案**：测试用 token 由 CI 内启动的实例生成/读取，替代硬编码（当前 smoke.spec.mjs:15 硬编码 token 是它无法进 CI 的直接原因之一） |
| P1-3 | **qa-frontend 职责重定义**：system.md 从"Rust 迁移审核员"扩为双职责——(a) Scala 版常规前端合并回归（跑 C2 套件），(b) 原 Rust 契约审核。**规则明确写入：前端验证禁止使用 python http.server 等理想路由替身，必须打真实 http4s 服务** |

**验收标准**：
- [ ] CI 新增 frontend-smoke job：真实 jar 启动 → Playwright 4-6 个用例全绿，push 触发
- [ ] 用例覆盖：首屏加载、Canvas 打开 + 文件渲染（触发 viewers 懒加载链）、console/pageerror 为零、`/js/` 与 `/vendor/` 全会话 404 监听
- [ ] 人为注入回归实验：删掉一个 viewers 文件 / 改错一个 import 路径 / 后端注释掉一条静态路由 → CI 三种情况都必须变红（防"形式化关卡"回归）
- [ ] qa-frontend system.md 更新并含"禁替身"规则

### Phase 2 — 类型渐进（下周起，2-3 天启动，持续消化）

| 项 | 内容 |
|---|---|
| P2-1 | **A0 checkJs 基线**：落 `jsconfig.json`（checkJs + allowJs），420 个存量错误用 baseline 文件冻结（或 `// @ts-nocheck` 逐文件），**新改动文件必须清零** |
| P2-2 | **A1 tsc 门槛进 CI**：`tsc --noEmit` 与 baseline diff，新增错误即红（node 环境，秒级，无需浏览器） |
| P2-3 | **核心模块 JSDoc 类型化**：优先 `state.js`（fan-in 27）、`ws.js`（18）、`utils.js`（21）——定义 `@typedef` 的 Session/WSMessage/viewer 协议，把"串台"类 bug 的温床封掉 |
| P2-4 | **循环依赖治理**：消灭 4 组循环（chatView↔cardRegistry、sidebar↔modal、neblink↔dropbox），可用 madge 进 CI 防复发 |

**验收标准**：
- [ ] `tsc --noEmit` 在 CI 运行且与 baseline 比对，新增错误失败
- [ ] `state.js`/`ws.js`/`utils.js` 有导出的 `@typedef`，checkJs 在这三个文件零错误
- [ ] `madge --circular`（或等效）在 CI 为 0 循环
- [ ] 基线错误数只降不升（每周统计）

### Phase 3 — 按需评估（1 个月后决策点）

| 项 | 触发条件 |
|---|---|
| A2 完整 TS 化（按文件迁移，新文件强制 .ts） | Phase 1-2 运行一个月后，若 checkJs 基线消化过半、且类型层确实拦到过回归 → 启动；否则 JSDoc 模式可长期够用 |
| B esbuild/vite 打包 | 首屏加载时间成为实测痛点（57 请求 waterfall）或需要 npm 生态依赖管理时；**不是为了拦 bug** |

---

## 7. 工作量与投入汇总

| 阶段 | 工作量 | 产出 | 拦截能力增量 |
|---|---|---|---|
| Phase 0（C1） | 0.5 天 | 资源契约测试进 CI | **本轮 404 类问题：从"用户发现"变为"CI 变红"** |
| Phase 1（C2+流程） | 1-2 天 | 真实服务冒烟进 CI + qa-frontend 双职责 | 渲染/集成类回归（历史 bug 的 ~2/3 族） |
| Phase 2（A0/A1/P2-3/P2-4） | 2-3 天 + 持续 | 类型基线 + tsc 门槛 + 核心契约类型化 + 循环清零 | 契约漂移/typo/状态混淆（~1/3 族）+ 重构安全网 |
| Phase 3（可选） | 4-8 周（A2）/ 2-3 天（B） | 按 Phase 1-2 实效决策 | 增量收益 |

**总计：4-6 个工作日拿到与后端对齐的三层防线（编译期/部署期/合并期）**——这正是后端 836 测试 + qa-backend 已经验证过有效的配置，只是搬到前端。

---

## 附录 — 数据采集方法（全部可复现）

| 数据 | 命令/来源 |
|---|---|
| 前端规模 57 文件 / 24,890 行 | `find src/main/resources/web/js -name "*.js" \| xargs cat \| wc -l`（排除 vendor） |
| 无构建配置 | `find web -name "package.json" -o -name "tsconfig.json" -o -name "*.config.js"` → 0 结果 |
| 路由白名单 | `WebSocketRoutes.scala:548-599`（css/js/locales/vendor/monaco/fonts 逐路径 case） |
| 昨晚 harness 用 python http.server | `/tmp/nb-f3-verify.js` 头部注释 + `/tmp/nb-f3-server.log`（viewers 请求 200） |
| import 图 0 未解析 / 4 循环 / fan-in | 自写 Python 脚本解析 `from '...'` 静态 import 构图 DFS（含相对路径解析到 .js） |
| tsc checkJs 420 错误 | `npx -p typescript@5.5 tsc --noEmit -p /tmp/tsconfig.probe.json`（checkJs+allowJs+skipLibCheck，strict=false） |
| 后端 857 用例 | `grep -rc "test(" src/test --include="*.scala" \| awk` 求和 |
| fix 提交分布 | 近 30 天 commit 主题含 fix 的 331 个，逐个 `git show --name-only` 检查是否触及 `web/` → 189 个 |
| CI 无浏览器测试 | `.github/workflows/ci.yml`：compile/test/assembly/smoke-test/docker-test 五个 job，smoke-test 仅 curl `/` 与 `/api/health` |
| qa-frontend 定位 | `~/.nebflow/teams/nebflow-project/agents/qa-frontend/system.md:1-9` |
