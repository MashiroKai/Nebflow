---
title: 前端范式评估——引入 Vue 类组件库开发范式（只评估不实现）
type: decision
status: draft
created: 2026-11-04
updated: 2026-11-04
author_question: 「另外我们前端开发能不能引入像vue这样的组件库的开发范式？」（2026-11-04 13:48）
scope: 决策件（零源码改动）；路线裁定权在作者
tags: [frontend, paradigm, vue, decision, design-system, gates]
related: ["[[20261102_234917_design-language-recon__dlc-frontend]]", "[[index]]"]
---

# 前端范式评估：引入 Vue 类组件库开发范式

> 只评估、不实现。本件全部读数在 `delegate/delegate-nebflow-79c40e17`（已 ff 到 main tip `0b1dcbb97`，全部读数在该 tip 复测）实测，复现命令见 §7。零源码改动：分支 diff 只含本文件。
>
> 追记（2026-11-04 晚，main tip `7f8051ce5`）：本件已随 merge `90d2a6491`（双亲 f979f6cd7 + 13772a8b5）入 main。此后 main 又落一批 ask 面改动（ask.css 413→417 行、mobile send/stop 截图与 resize spec 微调）——不触碰本件任何结论与推荐；试点面（Ask 面板）的规模读数按 +4 行口径理解即可。
>
> 上游与相邻件：设计语言现状勘察见 [[20261102_234917_design-language-recon__dlc-frontend]]；术语口径见 [[glossary]]；文档总入口见 [[index]]。

## 0. 结论先行

**推荐 A（vanilla 范式化）＝ 现在做；B（无构建 Vue 接入）＝ 条件性做，只在 A 的契约落地后对 1 个新面试点；C（Vue3 + Vite 正式构建链）＝ 冻结，写明触发条件再议。**

三条硬数据支撑这个排序：

1. **本站真正的痛点是视觉字面量漂移，不是渲染代码量。** 门禁实测存量：CSS 里 **822 处裸色值**、**2,997 个不同 class 选择器**、`border-radius` 曾达 28 种取值 / `font-size` 26 种（`scripts/check-design-tokens.mjs:1-40` 就是为此而写）。Vue 一行都不解决这个——解决它的是 token 定版 + 组件契约，而这两件事**不需要框架**。
2. **测试面几乎全部压在"真实 DOM 的 CSS 选择器"上。** 322 个 spec、6,157 处 `locator()/querySelector()`，其中 **198 个 spec 直接从磁盘伺服 `src/main/resources/web` 源码树**、**0 个**用 `build/web-dist`。任何引入构建步骤（路线 C）或影子边界（shadow DOM）的改动，都会同时打穿测试 harness 与 5 个以上静态门禁。
3. **在飞批的密度快到不能切地基。** main 自 2026-11-01 起有 **205 个提交、158 个 web/ 文件**被改（≈58 提交/天，含合并）。构建链换血与这个节奏并存 = 每批都要在移动的地基上 rebase。

---

## 1. 现状盘点（路径 + 行号举证）

### 1.1 控制台前端技术栈：**有构建链，但只用于生产产物；日常运行是"后端直接伺服源码树"**

| 事实 | 举证 |
|---|---|
| 仓根有 `package.json`，但它**只为生产 bundle 与类型闸存在**，不是应用依赖清单 | `package.json:4`（description 原文：「Runtime is vanilla JS served from src/main/resources/web; this package exists ONLY for the production bundle build … and the checkJs type gate」） |
| 依赖只有三个 devDependencies：`esbuild 0.25.9` / `typescript 5.5.4` / `ws 8.22.0` | `package.json:11-15` |
| 唯一的构建脚本 `npm run build` → `node scripts/build-web.mjs` | `package.json:6` |
| 构建器 = **esbuild**（不是 vite/webpack）：输入 `src/main/resources/web`，输出 `build/web-dist` | `scripts/build-web.mjs:45-46` |
| 产物布局：`assets/app-[hash].js`（单入口）+ `assets/chunks/*`（惰性动态 import 保边界）+ `assets/app-[hash].css`（43 个 `<link>` 按源序显式拼接，不信 esbuild 的 import 图）+ vendor 原样拷贝 | `scripts/build-web.mjs:10-32` |
| **孤儿守卫**：`web/js/**/*.js` 里任何文件没出现在 esbuild metafile inputs 就硬失败（豁免必须逐条写理由） | `scripts/build-web.mjs:33-37`、`:50-70` |
| 运行时伺服三源解析：① `-D<lowerName>.webdist=dev` 强制源码树 ② classpath `web-dist`（jar 内） ③ 磁盘 `build/web-dist`——**且仅当比 `web/` 源码新时才用**，否则回落源码树 | `src/main/scala/nebflow/gateway/StaticRoutes.scala:56-66`（`resolveStaticSource`）、`:72-77`（`diskDistRoot`）、`:79-88`（`distFresh`） |
| `sbt assembly` 只有在 `-D<lower>.webdist=1` 时把 `build/web-dist` 挂进 jar | `build.sbt:63-72` |
| CI 里构建发生在 assembly 作业：`npm ci && node scripts/build-web.mjs`，随后 `sbt -D<lower>.webdist=1 assembly` | `.github/workflows/ci.yml:336-341`、`:361-364` |
| 壳是**手写静态 HTML**（不是 JS 生成）：`index.html` 1,298 行，含 4 段 classic 内联脚本（防递归嵌入闸 / 独立窗口预绘闸 / 侧栏恢复 / 品牌槽） | `src/main/resources/web/index.html:4-40`、`:592-620`、`:1298`（总行数） |
| 启动面：5 个 vendor classic 脚本（lucide/lottie/marked/katex/highlight）+ 2 个 module 入口（`main.js`、`mobile/bootstrap.js`） | `index.html:1232-1237`、`:1296` |
| `main.js` 静态 import 83 条 | `grep -c "^import " js/main.js` |

**体量读数**

| 面 | 文件数 | 行数 | 备注 |
|---|---|---|---|
| `web/js/**` | 231 | 138,985 | 含 locales（zh-CN 3,124 + en 3,208） |
| `web/css/**` | 40 | 38,176 | 最大面 `flowMap.css`/`friends.css` 同用 `.fm-` 前缀 |
| `web/index.html` | 1 | 1,298 | 手写壳 |
| 生产产物（本机 `build/web-dist`，2026-11-03 09:09） | — | — | 入口 JS **241,007 B**；CSS **673 KB**；惰性 chunk **125 个 / 12 MB**；`vendor/` **25 MB**；`index.html` 87,058 B |

最大的 5 个模块：`js/messages.js` 6,928 行、`js/canvas.js` 5,384、`js/main.js` 5,215、`js/chat.js` 4,562、`js/sidebar.js` 3,434。

### 1.2 手写 DOM 操作模块清单与通信方式

**这不是几个模块的规模，是整座应用的组织方式。** 全仓 `web/js/**` 读数：

| 操作 | 出现次数 |
|---|---|
| `querySelector(All)` | 1,519 |
| `getElementById` | 673 |
| `createElement` | 1,461 |
| `innerHTML =` | 670 |
| `classList.add/remove/toggle` | 933 |
| `addEventListener` | 1,310 |

**146 个模块（占 231 的 63%）自己造 DOM。** 造得最多的：`chat.js`(142)、`persistence.js`(96)、`explorer.js`(78)、`delegatePanoramaView.js`(73)、`mobile/navController.js`(64)、`perception.js`(62)、`projectTab.js`(59)、`canvas.js`(59)、`scheduled-task.js`(56)、`sidebar.js`(54)。

典型手写面（任务书点名的 askDock 一类）：

| 模块 | 行数 | 职责 | 通信方式 |
|---|---|---|---|
| `js/askDock.js` | 1,010 | Canvas 文件面 Ask 模式：FAB 入口 → 毛玻璃输入条 → 弹幕式气泡列；含拖拽改尺寸、未读绿点、每文件会话持久 | import 7 条（branding/state/ws/canvas/utils/mdHtmlGate/input-composer），**只读不被读**；挂载点 `document.getElementById('canvas-panel')`（`askDock.js:200-205`） |
| `js/todoFace.js` | 1,601 | 待办胶囊（静默态 8 字胶囊 / 逾期翻面）+ 展开态全列表（分组/筛选/行内编辑/拖排）、SwiftUI 弹簧动画在手实现 | 被 `sidebar.js:15`、`mobile/taskPanel.js:31` 反向 import 挂载；数据面 `todoStore.js`(261) |
| `js/input/composer.js` | — | 键盘/IME 组字态/发送判定/中断状态机，主会话与 ask 面同源 | 被 `input.js`、`chat.js`、`askDock.js` 共用（注释 `input/composer.js:30-36`） |
| `js/state.js` | 337 | 共享可变状态单例（`export default {...}`，`:56`） | **60 个模块 import** —— 事实上就是全局 store，但没有订阅机制 |
| `js/ws.js` | — | WebSocket 生命期 + 帧路由 | **46 个模块 import**；ask 面经 `setAskFrameSink` 注入 |

**通信模式实况：三条腿并存，没有一条是"组件契约"。**
- **单例状态**：`state.js` 60 个消费者，读改都直接摸对象，无 notify/subscribe（`state.js:56` 的对象里没有一个 `subscribe`）。
- **自定义 DOM 事件**：117 处 `new CustomEvent(...)` / 123 处 `dispatchEvent`，热点名 `workspace-open-item`(16)、`fm-groups-changed`(8)、`fm-friends-changed`(8)、`session-attention`(6)、`locale-changed`(25 个监听点)——**事件名是字符串字面量，无集中注册表**。
- **直接 DOM 查询**：跨模块取元素靠 `document.querySelector`/`getElementById`，而不是 props/回调（1,519 + 673 处即此模式的总量）。

**这就是"没有组件范式"的真实成本：状态无订阅、事件名无注册表、跨模块靠查 DOM。** 它同时说明：**框架能治的病是真实存在的**（§6 给出不引框架的等价处方）。

### 1.3 既有设计系统底座：**token 已是单源，且有机器门禁；缺的是"组件契约"这一层**

| 层 | 位置 | 读数 |
|---|---|---|
| 感知域 token（真官网色） | `css/stand-tokens.css:1-40` | 173 行 token 声明；来源=mystand.space 官网 CSS 实抓，`--nfd-*` 镜像为 `--st-*` |
| Sapphire Glass 体系 + 共享消息面 | `css/sapphire.css:1-30` | 1,325 行；`--sapphire: 91 127 191` 三元组，一切 tint 拼色的底数 |
| 设计体系「令牌层 + 组件类层」单源 | `css/design-system.css:1-35` | 1,302 行；**189 个 `--ds-*` 声明、38 个 `.ds-*` 组件类**（首个类定义 `:444`）；三条纪律=追加不改写 / 消费即引用 / 值域即规范 |
| 规范权威（人读表） | `seed/plugins/design-language/skills/design-language/SKILL.md` | 193 行；§2 字号 5 档、§4 圆角 8 档、§5 4px 栅格、§7 组件原型与固定尺寸档（`:149`）、§10 使用流（`:189`） |

**两道设计门禁怎么工作**

- `scripts/check-design-tokens.mjs`：**两档判据**。
  ① **存量护栏**——`scripts/design-token-baseline.json` 记每个 css/js 文件的字面量计数，「超过基线 ⇒ 红」，「基线里没有的新文件 ⇒ 一个字面量都不许有」⇒ **只能减不能增**（`:20-25`）。
  ② **严格面**——设置页结构面 + `.ds-*` 设计体系类层必须零裸色值；字号落 5 档阶梯、圆角落 8+2 档阶梯（`:52-60`）。
  夹具口径：字号阶梯 `{11,12,13,15,20}px`，圆角阶梯 `{0,4,6,8,10,14,16,20,999px,50%}`，11px 以下无合法场景。
- `scripts/check-msgstyle-single-source.mjs`：**字面量单源**——每条规则 =「一个 CSS 字面量 + 允许出现的文件与次数」，shared layer 钉在 `sapphire.css`，其余任何 css 文件命中即红（`:1-60`）。
- **注意：这两道门禁都不在 GitHub CI 里**（`grep design-token .github/workflows/*.yml` 零命中；视觉基线同理，只在 `Makefile:79-83` 的 `make check-visual` 手动腿）。当前 CI 覆盖的前端门禁 = checkJs / circular / ime-guard / takeover-identify / msgstyle / local-coupling / legacy-domain（`.github/workflows/ci.yml:32-124`、`:202-207`）。

**组件样式与 token 的耦合方式**：CSS 文件按**面**分家，每面自带前缀（40 个 css 文件里 29 个有主导前缀：`.fm-` 393 处、`.ds-` 121、`.tfl-` 152、`.canvas-` 149、`.cfg-` 87、`.tw-` 60、`.ask-` 43 …）。也就是说——**"组件前缀契约"事实上已经在跑，只是没写进规范、没有门禁守**。

### 1.4 测试面怎么选 DOM：**全部直查真实 DOM 的 CSS 选择器，零组件边界**

| 读数 | 值 |
|---|---|
| `tests/*.spec.mjs` | **322** 个（`tests/` 目录 469 条目的其余是夹具/工具） |
| 含 DOM 选择器的测试文件 | 440 个文件（含夹具模块） |
| `locator()/querySelector()` 调用 | **6,157** |
| 直接从磁盘伺服源码树 `src/main/resources/web` 的 spec | **198**（自带 MIME 表现场应答，例 `tests/todo-split.spec.mjs:23-30`） |
| 引用 `index.html` 自举页面的 spec | 244 |
| 用 `page.route`/mock WS 夹具的 spec | 234 |
| 用 `build/web-dist` 产物的 spec | **0** |
| 视觉基线场景 | 12，**全部按选择器截图**（`#bg-dropdown`、`#canvas-panel`、`#explorer-tree`、`#modal-confirm`…，`scripts/visual-baseline.mjs:200-461`、`:535`） |
| Playwright 配置 | `workers: 1`（固定端口冲突）、instance-backed 清单、`PW_SELFCONTAINED_ONLY`（`playwright.config.mjs:44-60`） |

**逐个门禁对「shadow DOM / Vue 挂载会不会打穿」的结论**

| 门禁 | 判据形态 | shadow DOM | Vue 挂载（light DOM） | 备注 |
|---|---|---|---|---|
| `check-js-types.mjs`（CI `:32`） | `tsc --noEmit` 过 `jsconfig.json`（`include: js/**/*.js`），对照基线**只许降**；当前基线 **total 0**，即 JS 全绿 | ✅ 不受影响（纯文本） | ✅ `.js` 里的 Vue 代码照检；⚠️ `.vue` SFC 不在 `include`，需换 `vue-tsc` | 现状零类型债是资产，别浪费 |
| `check-circular.mjs`（CI `:36`） | 自解析静态 `import` + Tarjan SCC | ✅ 不受影响 | ⚠️ SFC 内 import 门禁看不见（`check-circular.mjs:1-20` 只扫 `.js`） | **现状已 RED：2 个 SCC**（`popArtifacts→msgQuote→reference→popArtifacts`、`mobile/navController↔mobile/taskPanel`）；文件与 main 逐字节相同 ⇒ 既有红，非本件引入 |
| `check-ime-guard.mjs`（CI `:40`） | `web/js/**` 里任何文件碰 `isComposing`/`keyCode 229`/`compositionstart` 必须 import `imeGuard.js`（单一判定点） | ✅ | ⚠️ `.vue` 不在扫描面 ⇒ **"IME 只有一条腿"这条硬规矩在新面失守** | 这是三条铁律里最容易**静默退化**的一条 |
| `check-msgstyle-single-source.mjs`（CI `:59`） | 逐字面量计数，钉在 `sapphire.css` | ⚠️ 组件迁进 shadow 后样式必须自带副本 ⇒ 同一字面量出现第二处 ⇒ **必红** | ✅ 样式仍在 `css/` 里 | 采用 shadow DOM 这条路线的头号成本 |
| `check-design-tokens.mjs`（**未进 CI**） | 存量基线只降不升 + 严格面零字面量 | ⚠️ 同一字面量在组件内部复写 = 基线上升 | ⚠️ 若走 SFC scoped CSS，`.vue` 不在扫描路径 ⇒ **新组件可自由写字面量，门禁对自己新面失明** | 路线 C 必须扩门禁，否则等于放水 |
| `check-takeover-identify.mjs`（CI `:57`） | 身份三腿同源静态哨兵 | ✅ | ✅ | 与渲染范式无关 |
| `build-web.mjs` 孤儿守卫 | 每个 `web/js/**/*.js` 必须在 metafile inputs | ✅ | ⚠️ `.vue` 不是 `.js`，守卫覆盖面缺口 | 需扩扩展名 |
| `verify-web-assets.mjs`（C1，CI `:453`/`:568`） | **遍历 `web/` 下每一个文件**，必须 HTTP 200；「文件在盘上但没路由 = 硬失败」，豁免须逐条写理由（`:1-25`） | ✅ | ⚠️ **`.vue` 源文件放进来就 404 ⇒ 必红**；必须重定豁免口径 | 这条对路线 C 是硬墙 |
| 视觉基线 `make check-visual`（未进 CI） | 12 场景按 `selector` 截图 + 像素比 | ❌ **必破**（`#canvas-panel` 内部元素移进 shadow 后，父页 `querySelector` 取不到） | ✅ 需防 `nextTick` 抖动 | — |
| 322 个 Playwright spec | 类名/ID 直查 | ❌ **大面积破**（选择器穿不透 shadow 边界） | ✅ 结构类名保留即断言存活 | 这是"要不要 shadow"的唯一裁判 |

### 1.5 对照面：stand-website 已经证明/没证明什么

| 事实 | 举证 |
|---|---|
| Next.js **16.2.2** + React **19.2.4** + TypeScript 5 + Tailwind v4 | `~/..stand/projects/stand-website/package.json:20-40` |
| 生态：`three 0.183` / `@react-three/fiber 9.5` / `framer-motion 12` / `gsap 3.15` / `recharts 3.8` | 同上 |
| 规模：`components/` 40 个 `.tsx`、`app/` 35 个页面、合计 **15,263 行 tsx** | `find components app -name "*.tsx" \| wc -l` = 75 文件 |
| token 面：`app/globals.css` **181 个** `--*` 声明（Tailwind v4 `@theme`） | `grep -c "^\s*--" app/globals.css` |
| 交付链：Vercel 双域（staging 测试道 / 生产两跳），`vercel.json` host 条件重定向 | `AGENTS.md`「双域发布铁律」节 |
| 测试：`node --test tests/unit/*` + Playwright e2e | `package.json:10-11` |

**它证明了的**：这套团队**有能力**跑完整的组件化范式——SFC 级组件化、props/slots、token 单源、Three.js/动效库、构建+CI+部署一条龙；而且**它就是我们设计语言的基准来源**（`stand-tokens.css:1-16` 逐值转录官网 CSS chunk）。所以「团队不会写组件」不是风险。

**它没有证明的（关键差异）**：
1. **规模与形状不同**：15k 行 tsx（含页面）vs 138k 行命令式 JS + 40 个 CSS 面 + 手写 1,298 行 HTML 壳。
2. **运行形态不同**：官网是 Vercel 上的 Next 应用（SSR/ISR + 边缘 CDN）；控制台是 **jar 内被 Scala http4s 静态伺服的单页应用**，带 Service Worker 温启动优化（`sw.js:1-45`，allowlist=`/js/ /css/ /vendor/ /brand/ /assets/`）。
3. **测试形态不同**：官网 e2e 数量级与自足夹具形态都不同；控制台有 198 个 spec 直接从源码树起页面、0 个碰构建产物。
4. **门禁形态不同**：控制台的 9 个前端静态门禁（少值单源 / IME 单点 / 字面量基线 / 孤儿守卫 / 资源 200）在官网仓里没有对应物。
5. **vendor 契约不同**：console 的 monaco 走 AMD loader、六个库是 `window` 全局 classic script（`build-web.mjs:29-31` 明写「window-global script contract」），官网是纯 npm ESM 世界。

**结论：官网证明的是"范式可行"，不是"迁移可行"。** 拿它当路线 C 的成本依据会系统性低估。

---

## 2. 三条路线评估

评估五维（每条都答）：**范式收益 / 接入成本 / 对在飞批的冲击 / 测试与门禁改造量 / 回滚方式**，外挂「第一刀试点形状」。
工作量级为**估算**（本仓无同类实测基线，按近期同类批次规模比推断，标注 = 估算）。

### 2.1 路线 A — 不引框架，vanilla 范式化

**形态**：token 定版（`--ds-*` 收口 + 基线只降不升）＋ 组件契约（命名/结构/状态类规范：`.<face>-block__elem--state` 三档，写进 design-language SKILL 并配一条门禁）＋ **Web Components 只用于新组件，且默认 light DOM**（`<nf-xxx>` 不 `attachShadow`）。

- **范式收益**：拿到的不是"响应式"，而是**可复用与不可漂移**——① 组件契约把 2,997 个 class 收敛成可检索的族；② 面前缀已有惯例（29/40 文件）只是没守门；③ light-DOM 自定义元素拿到生命周期与封装 API，却不付出 shadow 的测试/门禁代价（§1.4 表：shadow 会破 322 spec + 视觉基线 + msgstyle 门禁三处）。**收益口径诚实说：这是"止血 + 提可维护"，不是"提速"。**
- **接入成本（估算 6-10 人日，一次性）**：契约文档 1-2 人日；契约门禁（新文件命名/结构校验，参照 `check-msgstyle-single-source.mjs` 形态）2-3 人日；token 存量收敛第一波（822 裸色值里挑 top-3 面）3-5 人日。
- **对在飞批的冲击：近零。** 不改构建、不改入口、不改 index.html、不动 vendor 契约。新批只需要"照契约写"，门禁只对新文件生效（`check-design-tokens.mjs` 的「新文件零字面量」就是这个模型，已被验证可用）。
- **测试与门禁改造量：0 个已有门禁需改。** 新增 1 条契约门禁（可后置）。322 spec 零影响（类名结构保留）。
- **回滚**：逐组件回滚（每个 `<nf-*>` 是一个独立文件）；门禁可单条开关。
- **第一刀试点形状**：见 §4。

### 2.2 路线 B — petite-vue / Vue3 ESM 无构建接入

**形态**：vendor 自托管（**不用 CDN**——控制台必须离线/jar 内可用，`sw.js:43` 的 allowlist 与 jar 打包都不认外部域），新面用 `Vue.createApp` + 字符串模板或 in-DOM 模板，与命令式 JS 并存。

- **范式收益**：真正的响应式与声明式模板，对**流式面**（ask 气泡列逐帧追加、待办列表重排）收益最直观；`nextTick` 自动批渲染省掉手写 diff。petite-vue 约 6KB gz 起步，Vue3 浏览器 ESM 全量约 150KB min（≈60KB gz）——相对 241KB 入口 + 12MB 惰性 chunk 不算负担。
- **接入成本（估算 4-7 人日）**：vendor 落盘与类型声明 1 人日；模板形态定型（字符串 vs in-DOM，**建议字符串**，避免手写壳再涨）1-2 人日；一个面的重写 2-4 人日。
- **两范式并存的真实成本（这是 B 的主要代价，必须点明）**：
  1. **双真相**：`state.js` 单例（60 个消费者）与 Vue 的 `reactive()` 会成为两个可变源。必须定一条硬规矩——**Vue 面只读 state，写入一律回调到既有单例**，否则跨面不同步的 bug 会以"只在某面复现"的形态长期存在。
  2. **生命周期错位**：命令式模块在 `main.js` 启动链同步跑；Vue 挂载是异步的（`createApp().mount()` 后首帧才落 DOM）。已有 198 个 spec 是"页面 load 后立刻查 DOM"，异步挂载会引入偶发空断言。
  3. **事件面**：117 处 `CustomEvent` 得继续用（Vue 世界不认），等于两个事件系统。
- **对在飞批的冲击：低-中。** 不动构建链、不动 index.html、不动 vendor 契约；但任何批动到已迁移面时必须理解两套范式。
- **测试与门禁改造量：小。** light DOM ⇒ 选择器存活（前提：类名与 DOM 结构不改）；需加一条「等待挂载」的夹具约定。门禁全部照跑（`check-js-types` 会检 `.js` 里的 Vue 用法，**当前类型基线 total=0 会变成非 0**，需要一次带理由的 `--update`——这是"只许降"纪律的一次合法例外，必须写进批报告）。
- **回滚**：删 vendor 目录 + 摘掉 `mount()` 调用 = 面回退到命令式；面级独立，不牵连。
- **第一刀试点形状**：见 §4。

### 2.3 路线 C — Vue3 + Vite 正式构建链

**形态**：SFC + `<script setup>` + scoped CSS；Vite 取代 esbuild；`index.html` 变成 Vite 入口模板；产物进 `build/web-dist`（沿用 `StaticRoutes` 三源解析）。

- **范式收益**：完整组件库范式——SFC/props/slots/组合式 API、HMR 开发体验、类型化组件边界。**这是唯一能拿到"真正的组件库"的路线。**
- **接入成本（估算 25-40 人日 ＋ 一个长期并存期）**，且成本集中在**不在框架本身**的地方：
  1. **198 个自足 spec 的伺服模型要重写**：它们从源码树应答 `/js/**`（例 `tests/todo-split.spec.mjs:23-30`）。SFC 需要编译 ⇒ 要么每个 spec 先跑一次构建（322 spec × 构建时间），要么新增"内存编译伺服"夹具。**这是路线 C 最大的一笔账，且它在任何路线对比表里都容易被漏掉。**
  2. **C1 资源面门禁重定口径**：`verify-web-assets.mjs` 遍历 `web/` 下**每一个文件**要求 200（`:1-25`）。`.vue` 源文件必须列入豁免（豁免策略是"逐条写理由"，不是通配放行）。
  3. **四道门禁变盲**：`check-circular`（只看 `.js` import）、`check-ime-guard`（只扫 `web/js/**`）、`check-design-tokens`（只扫 `css/`+`js/`）、孤儿守卫（只看 `.js`）。**其中 IME 单点是铁律**（15 处手写复制的历史教训，`check-ime-guard.mjs:1-16`）；SFC 面上它会失守且不会有人察觉。
  4. **类型闸换引擎**：`check-js-types.mjs` 的 `jsconfig.json`（`include: js/**/*.js`）要换 `vue-tsc` + 改 include，基线 total=0 的"零债"状态会被打散重锚。
  5. **手写壳要移植成模板**：`index.html` 1,298 行里的 4 段 classic 预绘脚本（防递归嵌入、独立窗口模式类、侧栏恢复、品牌槽）必须变成 Vite 的 HTML transform 插件，否则"首帧正确"保证（2026-10-22 独立窗口批的整条修复）会退化。
  6. **vendor 契约**：monaco AMD loader + 5 个 window 全局脚本必须保持 classic `<script>`；Vite 对非模块 vendor 需要显式外置。
  7. **Service Worker**：allowlist 已含 `/assets/`（`sw.js:43`）正好覆盖 Vite 默认产物目录，索引哈希对账链可继续用——**这一条是唯一"意外地已经就绪"的集成点**。
- **对在飞批的冲击：高。** main 自 11-01 起 205 个提交 / 158 个 web 文件。切构建链的窗口期内，**任何改 `web/` 的在飞批都要 rebase 到换血后的地基**；且新旧两套构建并存期间，`StaticRoutes:79-88` 的"新鲜度"判定会让 dist 频繁失效回落源码树（该逻辑本身没错，但会让"到底跑的哪棵树"变成日常排障项）。
- **测试与门禁改造量：5 个门禁 + 198 个 spec harness + 2 个 CI job**（`ci.yml` 的 assembly 构建步与 frontend-specs 步）。
- **回滚**：只能"整批回滚分支"或"双构建并存期回退到 esbuild 产物"；不存在面级回滚。
- **第一刀试点形状**：见 §4。

### 2.4 三路线对比表

| 维度 | A vanilla 范式化（+ light-DOM Web Components） | B 无构建 Vue ESM | C Vue3 + Vite |
|---|---|---|---|
| 拿到什么 | 命名/结构/状态契约 + token 收口；新组件有生命周期封装 | 响应式 + 声明式模板，声明式面级试点 | 完整组件库范式（SFC/props/slots/HMR） |
| 构建链改动 | **无** | 无（vendor 自托管） | **换血**（esbuild→Vite，产物链重定义） |
| 接入成本（估算） | 6-10 人日 | 4-7 人日 | 25-40 人日 + 长期并存期 |
| 322 spec 影响 | 0 | 低（异步挂载时序约定） | **高**（198 个自足 harness 需重写） |
| 门禁改动 | 0 已有 + 新增 1 条契约 | 0 已有（类型基线一次重锚） | **5 个门禁变盲需扩 + C1 口径重定 + CI 两 job** |
| shadow DOM / 视觉基线 | 不碰（light DOM） | 不碰 | 不碰（Vue 是 light DOM）——但若选 scoped CSS，字面量门禁对自己新面失明 |
| 在飞批冲击 | 近零 | 低-中 | **高** |
| 回滚粒度 | 逐组件 | 逐面 | 只能整分支/整链 |
| 最大风险 | 规范写了没人守 ⇒ 无效 | 双真相 + 双事件系统长期并存 | **半迁移态**（两套构建+两套门禁+两套范式永久并存） |
| 首刀试点 | Ask 气泡列 | Ask 气泡列（同面不同范式，直接可比） | 不建议现在试点 |

---

## 3. 附加维度：要不要配现成 UI 组件库（Element Plus / Vant 类）

**结论：不配。** 理由是量化的，不是审美偏好。

| 冲突点 | 本站铁律（有出处） | Element Plus 类默认 | 后果 |
|---|---|---|---|
| 字号 | 5 档阶梯 `11/12/13/15/20`，11px 以下无合法场景（`check-design-tokens.mjs:52-60`） | 12/14px 体系 + 自成一套阶 | 每个组件都要覆盖；覆盖即写死 ⇒ **设计门禁报红** |
| 圆角 | 8 档 + 2 特殊值（同上） | 4px 体系 | 同上 |
| 弹窗 | **禁 overlay 遮罩**，面板必须毛玻璃（design-language SKILL.md §0 视觉硬裁定） | `el-dialog`/`el-drawer` 默认带遮罩层 | 组件默认行为**与铁律正面冲突**，只能全覆盖改写 |
| 材质 | 玻璃 `--glass-*` + `--sapphire` tint 拼色（`sapphire.css:1-30`） | 平面实底 + 自有阴影族 | 视觉语言整体走样 |
| 色板 | 品牌绿只做身份/状态、主操作走墨族（2026-10-30 降级绿裁定） | 自有 `--el-color-primary` 蓝 | 语义错位 |
| 门禁 | 字面量单源（`sapphire.css` 唯一定义处） | 库自带全套字面量 | **`check-msgstyle-single-source` 直接红** |
| 体积 | 入口 241KB / 惰性 125 chunk | 按需引入也需 tree-shaking 管线 + 样式按需 | 与 esbuild 手动拼接 CSS 的现状（`build-web.mjs:10-32`）耦合成本高 |

**改写成本估算**：把 Element Plus 的 30+ 组件调到符合本站铁律 = 每个组件 ≈ 写一个自己的组件的工作量（至少是重写样式层）。**那不如直接自建**——本站已经有自建的底座：189 个 `--ds-*` token + 38 个 `.ds-*` 组件类 + design-language §7 的组件原型表（`SKILL.md:149`）+ `design-language/assets/components.css` 参考实现。

**正确形态 =「框架自建组件 + token」**：令牌层（已有）+ 组件类层（已有骨架）+ 面前缀契约（已有惯例，缺门禁）。UI 库进来只会把这三层推倒重来。

---

## 4. 第一刀试点形状（每条路线各一份）

### 4.1 试点面的选择理由（先说为什么不是别的）

| 候选 | 规模 | spec 爆炸半径 | 活跃度（近 3 天） | 判断 |
|---|---|---|---|---|
| Canvas / 消息流 | `canvas.js` 5,384 + `messages.js` 6,928 | 45 spec 引 `#canvas*` | 极高 | ❌ 太大、太热 |
| **Ask 面板** | `askDock.js` 1,010 + `ask.css` 413 | **15 spec** 引 `.ask-dock/.ask-fab/.ask-col/.ask-send`（`.ask-` 宽口径 64，但其中多为 AskUser 卡，非本面） | 高（11-03/11-04 有 resize / fade / send-stop 三批；合并当晚 main 又落一批微调——活跃度佐证） | ✅ **首选**（收益可测），但需冻结窗口 |
| **待办胶囊（静默态）** | `todoFace.js` 1,601（胶囊段为其一部分）+ `todo-face.css` 943 | 11 spec 引 `todo` | 中（11-02 重做批已收口） | ✅ **低风险备选**（收益小） |

**选 Ask 面板当首刀，四条理由**：
1. **依赖面是"只读"的**——`askDock.js` import 7 条（branding/state/ws/canvas/utils/mdHtmlGate/input-composer），**没有模块反向 import 它**（`grep -rl askDock js/` 命中的都是注释或同层调用，无环）。这意味着重写它可以不牵动别面。
2. **它天然是"流式响应式"的考题**：`textDelta` 逐帧追加 → `done` 收口，正是 Vue 响应式与手写 DOM 差异最大的地方，**试点能给出真实的收益读数**（不是感觉）。
3. **爆炸半径可控**：15 个 spec，且样式被 `.ask-*` 前缀圈死（43 处，`ask.css`）。
4. **有天然开关位**：`syncGate()`（`askDock.js:566`）已经是"挂不挂载"的单点，改造为 `featureFlags` 级 A/B 切换只加几行。

### 4.2 路线 A 的试点（推荐落这条）

- **选面**：Ask 气泡列 + 输入条（`askDock.js` 的 mount/render/bubble 段 + `ask.css` 的 `.ask-*`），**不改** 后端协议、不改 WS 帧、不改拖拽几何状态机（那块保持命令式，正好验证"共存"）。
- **形状**：抽 `js/component.js`（一个 60-100 行的极小基座：`define({ tag, template, state, mount, unmount })`，渲染用模板字面量 + `textContent`/`h()` 而非裸 `innerHTML` 拼接）→ Ask 面改用 `<nf-ask-dock>`，**light DOM，不 attachShadow**；样式仍落 `ask.css`，只把字面量换成 `var(--ds-*)`。
- **预估工作量（估算）**：1.5-2.5 人日。
- **验收怎么测**：① 15 个既有 spec 全绿（类名/结构保持，断言原样）；② `make check-visual` 12 场景 AR 0（视觉零漂移）；③ `check-design-tokens` 对 `ask.css` 的基线**下降**（字面量换 token 的直接读数）；④ 手工三条：流式逐帧追加无闪烁、切文档会话不串、拖柄改尺寸仍生效。
- **新旧共存不污染**：`<nf-ask-dock>` 组件通过 7 条既有 import 反向调用命令式模块（单向），命令式模块**永不**查组件内部 DOM；组件对外只暴露 `open/close/syncSession` 三个方法（替代现在散落的 `el.dock` 直查）。

### 4.3 路线 B 的试点

- **选面**：同 Ask 气泡列（与 A 同面不同范式 ⇒ **A/B 收益可直接对照**，这是选 B 的唯一理由）。
- **形状**：`vendor/vue.esm-browser.prod.js` 自托管 + `js/askDockVue.js`（`createApp` + 字符串模板），`featureFlags` 二选一挂载。
- **预估工作量（估算）**：2-3 人日。
- **验收**：同 A 的 ①②③；另加 ⑤ `state.js` 单向依赖检查（Vue 面不得写 state，用 grep 哨兵断言）；⑥ 挂载时序：所有断言加 `await expect(...).toBeVisible()` 形态，消灭"load 后立刻查 DOM"的偶发空断言。
- **共存不污染**：明确的边界规矩——**Vue 面只读 state、事件只往外发 `CustomEvent`、命令式面不感知 Vue 存在**；反向（命令式面驱动 Vue 面）只允许经 `mount()` 返回的三个方法。

### 4.4 路线 C 的试点（不建议现在做，若做则这样切）

- **选面**：不选既有面。选**一个新面**（如未来的某个独立面板），SFC 单文件、scoped CSS、独立 Vite **第二入口**，产出独立 chunk 由 esbuild 链当作 vendor 拷贝。
- **理由**：既有面迁移会同时撞 198 个 spec harness + 5 个门禁 + 视觉基线；新面迁移只撞"门禁盲区"一项，可以边做边补门禁。
- **预估工作量（估算）**：8-12 人日（含第二入口的构建编排、type 闸扩 `.vue`、C1 豁免口径、circular/ime/token 三门禁扩扩展名）。
- **验收**：新增面自身 spec 全绿 + 既有 322 spec 零回归 + `verify-web-assets` 在 source/dist 两模式下都绿（`.vue` 不入源码树或已豁免）+ jar 内 served 树仍是 `web-dist`。
- **回滚**：删第二入口与 SFC 目录；esbuild 链零改动 ⇒ 回滚成本等于 0（这是"第二入口"切法的唯一价值）。

---

## 5. 结论件：推荐排序、理由、风险清单

### 5.1 排序

| 序 | 路线 | 裁定建议 | 触发条件 |
|---|---|---|---|
| **1** | **A：vanilla 范式化（light-DOM Web Components 只用于新组件）** | **现在做，不待额外裁定**（它是纯增量：0 构建改动、0 已有门禁改动、逐组件可回滚） | — |
| 2 | B：无构建 Vue 接入 | **条件性做**：A 的组件契约与 token 收口落地后，对 1 个新面（不是既有热面）试点 | ① A 的契约门禁已在 CI 跑绿 ② 该新面的 DOM 结构在试点期可冻结 |
| 3 | C：Vue3 + Vite 正式构建链 | **冻结**，写明复盘触发条件 | ① 前端日均改动降到当前 1/3 以下（现 205 提交/3.5 天）② 或出现"跨 ≥5 个面复用同一复杂组件"的硬需求 ③ 且有人愿意先做 §4.4 的第二入口试点拿到真实成本读数 |

### 5.2 理由（为什么不是 C）

- 收益与成本的**错配**：C 的成本项（198 spec harness、5 个门禁变盲、1,298 行手写壳移植、vendor AMD 契约、C1 口径）里，**没有一项是"因为用了 Vue"而必须付的，全是"因为换构建链"而必须付的**。
- **当前唯一被量化出来的痛**（822 处裸色值 / 2,997 class / 28 种圆角）**Vue 不治**，token + 契约治。
- 唯一真正能治的痛（`state.js` 无订阅、1518+673 处跨模块 DOM 直查、117 处字符串事件名）**在 A 里都有 100 行量级的替代处方**（§6）。
- **在飞批密度**决定时机：205 提交 / 158 文件 / 3.5 天。这不是换地基的窗口。

### 5.3 风险清单（含最坏情况）

| 风险 | 概率 | 最坏情况 | 缓解 |
|---|---|---|---|
| A 的契约"写了没人守" | 中 | 多一份 193 行规范文档，一年后没人看 ⇒ 试点白做 | 契约必须**配门禁**（参照 `check-msgstyle-single-source.mjs` 形态，逐条规则 + 命中即红）；只写文档的 A 等于没做 |
| A 的 light-DOM 自定义元素仍被误用成 shadow | 中 | 某批为了"封装干净"加了 `attachShadow` ⇒ 322 spec 与视觉基线批量红 | 契约门禁加一条：`attachShadow` 出现即红（除非白名单+理由） |
| B 的双真相漂移 | 高（若跳过纪律） | 同一状态在两个面显示不同值，bug 只在某面复现，排障以"天"计 | 单向铁律：Vue 面只读 state、写走回调；加 grep 哨兵 |
| B 的类型基线破窗 | 高 | `check-js-types` 的 total=0 变成非 0，之后"只许降"的纪律出现一次例外 ⇒ 后续例外越来越多 | 一次带理由重锚，写进批报告；且只允许在试点分支发生 |
| **C 的半迁移态** | 高（若开工） | 两套构建 + 两套门禁 + 两套范式永久并存；前端批次速度实际下降，"组件库"只覆盖新面；`StaticRoutes:79-88` 的新鲜度判定让"跑的是哪棵树"变成日常排障项 | 若一定要做：只做"第二入口"切法（§4.4），esbuild 链零改动 ⇒ 回滚成本 = 0 |
| C 的门禁失明静默退化 | 高（若开工） | `check-ime-guard` 只扫 `web/js/**`，SFC 面不再受"IME 单点"约束 ⇒ 回到 15 处手写复制的时代，且**没有任何东西变红** | 每条门禁必须扩扩展名**在同一批**里完成，不得分期 |
| 现成 UI 库引入 | 低（若采纳 §3 结论） | 30+ 组件改造 = 重写一遍，且字面量单源门禁全红 | 不引入 |

---

## 6. 哪些事不需要框架也能解决（别为了范式而范式，也别因保守漏掉真收益）

先把"框架能治的病"逐条对账，再给不引框架的等价处方：

| 真痛点（有读数） | Vue 能给什么 | 不引框架的处方 | 处方规模（估算） |
|---|---|---|---|
| `state.js` 单例无订阅，60 个消费者靠自觉 | `reactive()` 自动依赖追踪 | 给 `state.js` 加 `subscribe(fn)` / `set(path,val)` 两个方法（Proxy 或显式 setter），20-40 行 | 极小 |
| 1,461 处 `createElement` + 670 处 `innerHTML` 手工拼装 | 声明式模板 | 抽 `js/dom.js`：`h(tag, attrs, children)` + `frag(fn)`，60-80 行；新代码强制用它 | 小 |
| 117 处字符串事件名、无注册表 | 组件 props/emit（有类型） | `js/events.js` 集中常量 + 一条 grep 门禁（事件名必须来自该文件） | 小 |
| 1,519+673 处跨模块 DOM 直查 | props/ref 传值 | 组件契约规定：**组件对外只暴露方法，不暴露 DOM**；契约门禁扫 `document.getElementById('#<face>-` 形式的跨面直查 | 中（存量收敛是长期活） |
| 列表局部刷新/重排手写 | 虚拟 DOM diff | 已有 `chat/renderGate.js` + `listFlip.js` 的既有形态，继续抽 | 小 |
| 222 处裸色值/字面量漂移 | **无收益** | **已有**：token 定版 + `check-design-tokens` 基线只降不升 | 已在做 |
| 组件命名/结构混乱（2,997 class） | **无收益**（框架不管命名） | **已有惯例 + 缺门禁**：面前缀 29/40 文件已成事实，补一条契约门禁即可 | 小 |

**反向诚实一句**：框架真正独占、上述处方都给不了的，只有两样——**① 大列表的自动化最小 diff（现在靠手写）② 组件级 scoped 样式**。第二样在本站**恰恰是有害的**（会让字面量单源与 token 门禁失明，§2.3）。所以真实增量只剩第一样，而它在本仓的现状是"有手写 fixture 但没出过大事故"。

**因此：现在做 A，把框架留给"真的被卡住"的那天，而不是"范式更先进"的那天。**

---

## 7. 复现（全部读数可一键重跑）

```bash
cd "<worktree 根>"
# 体量与模块
find src/main/resources/web/js -name '*.js' | wc -l
find src/main/resources/web/js -name '*.js' -exec cat {} + | wc -l
find src/main/resources/web/js -name '*.js' -exec wc -l {} + | sort -rn | head -6
# 命令式 DOM 强度
grep -ro "querySelector\(All\)\?(" src/main/resources/web/js --include=*.js | wc -l   # 1519
grep -ro "getElementById("         src/main/resources/web/js --include=*.js | wc -l   # 673
grep -ro "createElement("          src/main/resources/web/js --include=*.js | wc -l   # 1461
grep -ro "innerHTML\s*="           src/main/resources/web/js --include=*.js | wc -l   # 670
grep -ro "new CustomEvent("        src/main/resources/web/js --include=*.js | wc -l   # 117
# 测试面
ls tests/*.spec.mjs | wc -l                                                           # 322
grep -ro "page\.locator(\|page\.querySelector\|\.querySelector(" tests/ | wc -l        # 6157
grep -rl "main', 'resources', 'web'" tests/*.spec.mjs | wc -l                          # 198
grep -rl "web-dist" tests/*.spec.mjs | wc -l                                           # 0
# 设计系统
grep -o -- "--ds-[a-z0-9-]*:" src/main/resources/web/css/design-system.css | sort -u | wc -l   # 189
grep -ro "#[0-9a-fA-F]\{3,8\}\b" src/main/resources/web/css/*.css | wc -l                       # 822
# 门禁现状（本机实测）
node scripts/check-circular.mjs            # exit 1（2 SCC，既有红，与 main 逐字节相同）
node scripts/check-js-types.mjs            # 基线 total=0
node scripts/check-design-tokens.mjs       # 未进 CI，手动腿
make check-visual                          # 12 场景，按选择器截图
# 在飞批密度
git log main --since="2026-11-01" --oneline -- src/main/resources/web | wc -l          # 205
git log main --since="2026-11-01" --name-only --format="" -- src/main/resources/web | sort -u | wc -l  # 158
```

---

## 8. 未决项（需作者裁定）

1. **A 是否现在就开工**（推荐：是；它是纯增量，不阻塞任何在飞批）。
2. **组件契约的粒度**：BEM-三档（`block__elem--state`）还是"面前缀 + 状态类后缀"两档？推荐后者（贴合 29/40 文件的现状，迁移成本≈0）。
3. **Ask 面板试点窗口**：该面 11-03/11-04 有三批在改（resize / fade / send-stop），试点需要 2 天冻结窗；若不愿冻结，改选待办胶囊（收益小但零冲突）。
4. **B 是否值得做**：若 A 落地后 `state.subscribe` + `dom.h()` 已经把流式面写顺手，B 的边际收益可能为零——建议到那时用"写一个新面对比 2 小时"的实际读数来决定，而不是现在拍。
