> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# W3-i 方案：esbuild 构建管线设计（含 P0 压缩独立先行）

> 纯设计文档，不改代码。源码基线：`archive/scala` @ 4a9775e1（W1+W2 合并后 HEAD）。
> 触发条件已满足：路线文档 §4 给 B 列的启动条件是"首屏性能成为实测痛点"——W3-m qa 实测
> 基线：**84 请求 / 6.32MB 传输（零压缩）/ networkidle 798ms**。瀑布 + 无压缩两个痛点都是实测值。
> 本文档回答 Manager 指定的七个决策，每项给推荐项 + 理由 + 被否项。

---

## 〇、现状事实清单（决策依据）

| 事实 | 来源 |
|---|---|
| web/ 18MB，其中 vendor/monaco 13MB（72%），js+css+其余 vendor ≈ 5MB | `du -sh` |
| index.html 静态加载：23 个 CSS（16 应用 + 3 vendor + 4 icon）、5 个 vendor `<script>`（lucide/lottie/marked/katex/hljs）、1 个 module 入口 `js/main.js` | index.html |
| 84 请求的主体 = ES module 瀑布（~40 js 模块逐跳）+ 23 CSS + vendor + 字体图标 | W3-m measure-load |
| 后端静态服务：`StaticFile.fromResource` + 每个响应 `Cache-Control: no-cache`，**无任何压缩** | WebSocketRoutes.scala:548-599、3935-3970 |
| `jsRoutes` 已是任意深度通配（W1-a 合并），`/vendor/monaco/**` 通配，`/css/<file>` 与 `/vendor/<file>` 仍是单段 | WebSocketRoutes.scala:3944+ |
| vendor 全局（lucide/marked/katex/hljs/lottie）经 `<script>` 挂 window，P2-3 已有 `vendor-globals.d.ts` 声明其调用面 | utils.js、index.html |
| 所有动态 import 均为字面量形式（`import('./chat.js')`），无字符串拼接 URL，无运行期 fetch('/js/...') | grep 全量核实 |
| locales 是静态 JS module（`import zhCN from './locales/zh-CN.js'`），进 bundle 图 | i18n.js:3-4 |
| Monaco 完全自治：自带 AMD loader（`/vendor/monaco/vs/loader.js`）+ blob worker + `window.require`，idle 时才 preload | monacoEditor.js:25-60 |
| C1 遍历 web/ 源码树推导 URL 断言 200；C2 smoke 断言 `/js/viewers/markdown.js` 具体请求 + 全程 `/js/`、`/vendor/` 404 监听 | verify-web-assets.mjs、smoke.spec.mjs:253-280 |
| no-cache 语义是 dev 契约：改 src 刷新即生效（浏览器重验证） | jsRoutes scaladoc |

---

## 一、分期总览（推荐）

| 期 | 内容 | 杠杆 | 依赖性 |
|---|---|---|---|
| **P0** | http4s GZip 压缩中间件 | **传输 -70%（6.32MB → ~1.9MB）** | 零前端改动，**可独立立即先行** |
| **P1** | esbuild 打包 JS+CSS（dev 直出源码 / prod 出 bundle 双模式） | 请求数 84 → ~12，消灭瀑布 | 依赖本文档全部设计 |
| **P2** | 指纹文件名 + 强缓存两级策略 | 二次访问近零传输 | 依赖 P1 |
| **P3** | Monaco 体积治理（**不打包**，裁剪语言包 + 保持懒加载） | 13MB 按需化 | 独立，任意时点 |

**P0 独立先行的可行性：确认成立。** GZip 中间件是纯后端改动（包一层 route），对源码直出和 bundle 产物同样有效，不阻塞也不依赖打包决策。qa 数据显示它单独就吃掉了最大的一块（-73% 传输）。建议本文档审过后，P0 直接派 Backend 实施，P1 再排期。

---

## 二、决策 1：产物布局

**推荐：web-dist/ 构建产物目录，gitignore 不入库；sbt 可选挂载；运行实例启动时单树探测。**

```
src/main/resources/web/        ← 源码树（dev 直出，永远是可运行的真实源）
build/web-dist/                ← esbuild 产物（gitignore），结构：
  index.html                   ← 构建期改写资产 URL 的版本
  assets/app-[hash].js         ← JS 入口 chunk
  assets/chunks/*-[hash].js    ← 懒加载 chunks（viewers 等）
  assets/app-[hash].css        ← CSS 单 bundle
  vendor/...                   ← 原样拷贝（vendor *.min.js + monaco 全树 + fonts）
  locales 已并入 JS chunk（它们是静态 module）
```

- **构建命令**：根目录 `package.json`（新建，仅 devDependency: esbuild）+ `scripts/build-web.mjs`：
  `node scripts/build-web.mjs` → 输出 `build/web-dist/`。
- **sbt 集成**：build.sbt 加一行 `Compile / unmanagedResources ++= (baseDirectory.value / "build" / "web-dist" ** "*").get`——**目录不存在时为空集，sbt compile/assembly/run 永不因缺 node 失败**。产物在 jar 里落在 `web-dist/` classpath 前缀。
- **运行时探测**：实例启动时探测 classpath 资源 `web-dist/index.html` 是否存在——存在则静态路由整体指向 web-dist 前缀（prod 模式），否则指向 web/（dev 模式）。单开关、单树服务，无逐文件 fallback 的混合态。
- **C1 适配**：`verify-web-assets.mjs` 加 `--root <dir>` 参数，遍历对象 = **当前发布的那棵树**。dev 跑源码树、CI 对 prod jar 跑 dist 树。契约不变："发布的每个文件必须可达"。

**理由**：源码树永远是 dev 的单一事实来源（edit-refresh 不动）；产物可重现（CI 从源码构建），入库只会制造 diff 噪音与"源码/产物谁为准"的分叉；sbt 可选挂载保住"无 node 环境也能编后端"的底线（CI 的 compile/test job、贡献者机器）。

**被否项**：
- ❌ dist 进 git——二进制式 churn + merge 冲突 + 双事实源，无一利好。
- ❌ sbt 打包时调 esbuild（sbt-web 模式）——把 JVM 构建硬绑到 node 运行时；本地 `sbt run` 开发循环被污染；CI compile job 凭空多一个 node 依赖。
- ❌ 运行期逐文件 fallback（先试 dist 再试源码）——每个静态请求翻倍查找，且"半新半旧"混合态是最难排查的失配面（W1 的 404 事故教训就是契约要单一）。

---

## 三、决策 2：开发工作流

**推荐：dev 永远直出源码（现状零改动）；prod 由 CI 构建。不做 esbuild watch 双轨。**

- 本地开发 = 今天完全一样：`sbt run` + 改 src + 刷新。**edit-refresh 零构建循环是本项目前端的核心生产力，不退化。**
- 本地需要验证 bundle 行为时（大改前/合并前）：`node scripts/build-web.mjs && sbt assembly` 手动 opt-in。
- **dev/prod 漂移风险**（打包才暴露的 bug 在 dev 不可见）由现有 CI 兜住：frontend-smoke job 本来就是"真 jar + fresh home"跑 Playwright——P1 后 CI 的 jar 含 dist，smoke 套件自动转为验证 bundle 产物，不需要新机制。

**被否项**：
- ❌ esbuild watch 常驻——多一个要记的进程，且 watch 产物与源码双份可服务对象会制造"我改的到底生没生效"的日常困惑（Turbopack 缓存坑的记忆条目就是这个教训的官网版）。
- ❌ dev 也强制 bundle——直接违反硬约束。

---

## 四、决策 3：压缩策略（P0，独立先行）

**推荐：http4s GZip 服务器中间件。预压缩 .br/.gz 留到 P2 指纹时代。**

实施要点（Backend 协同单）：
1. `org.http4s.server.middleware.GZip` 包静态资源路由（+ 可含 REST JSON）。
2. **排除清单**：`/ws`（WebSocket upgrade 绝不可压缩）、SSE/流式响应（stream 事件）、已压缩二进制（png/ico/woff2——GZip 中间件按 content-type 谓词过滤即可）。
3. **ETag/Vary 正确性**（验收要点）：StaticFile 的强 ETag 在压缩后必须变 weak 或剥离，响应必须带 `Vary: Accept-Encoding`——否则 no-cache 重验证的 304 逻辑会错。验收用 curl 断言：`curl -H 'Accept-Encoding: gzip'` → `Content-Encoding: gzip`；随后带 If-None-Match 重验证 → 304 仍正确。
4. measure-load.mjs 已采集 `transferSize`（线上字节）与 `decodedBodySize`（解压后）双字段——P0 前后对比零改动可测。

**被否项**：
- ❌ 现在就上预压缩 .gz/.br sidecar——没有指纹文件名的时代，sidecar 与 no-cache 重验证的组合要在 Scala 路由里手写协商逻辑，复杂度全在后端且收益与 GZip 中间件相同。P2 有了不可变指纹资产后，sidecar + immutable 才是它的正确战场。
- ❌ Brotli 运行时压缩——CPU 成本高，本地应用无带宽极端约束，gzip 的 -70% 已够；.br 留给 P2 预压缩。

---

## 五、决策 4：Monaco（P3，二期且不换打法）

**推荐：永不打包 monaco，P3 做体积裁剪。**

monacoEditor.js:25-60 的机制已是最优形态：AMD loader 自举 + blob worker + idle 才 preload——13MB 全在懒加载链上，不进首屏瀑布（measure-load 里 monaco 条目都在 `afterInteractive`）。esbuild 打包 monaco 是出了名的配置泥潭（worker entry、AMD vs ESM 互操作），收益为零（它本来就不阻塞首屏）。

P3 真正该做的：
1. 裁剪 `editor.main` 引入的语言 contribution（当前全量 ~60 语言，实际高频 ~15）；
2. 确认 idle preload 策略仍合理（或改为首次打开代码标签时才加载）；
3. gzip 后 monaco 文本资产本身 -70%（P0 已覆盖）。

---

## 六、决策 5：代码分割边界与 C1/C2 适配（本设计的核心联动）

**推荐：esbuild `splitting: true, format: 'esm'`，所有字面量动态 import 自动保留为独立 chunk；C1/C2 随发布树切换适配。**

### 分割边界

已全量核实：所有动态 import 均为字面量形式（main.js:286/1556/1584/1595/1738/2310、sidebar.js、neblink.js、cardRegistry.js、fileViewers.js 等），esbuild 静态可解析，自动成为 split chunk。分割后懒加载语义**逐边保留**：viewers/*（12 个查看器）、modal.js、dropbox.js、chat.js、input.js、sidebar.js、persistence.js、monacoEditor.js 等现状懒边界全部继续懒。

vendor 全局库（lucide/lottie/marked/katex/hljs）**不进 bundle**：它们是 `<script>` 挂 window 的契约（vendor-globals.d.ts 就是按此声明的），原样拷贝到 dist 的 vendor/。index.html 构建期改写：23 个 CSS link → 1 个 bundle link；5 个 vendor script 保留；`js/main.js` module 入口 → `assets/app-[hash].js`。

### 新失败模式与对策

bundle 引入了源码时代没有的失败模式——**孤儿文件**：一个从未被 import 链触及的源文件会被 esbuild 静默丢弃。对策：build-web.mjs 用 esbuild **metafile** 反向校验——web/js/ 下每个文件必须出现在 metafile inputs 里，否则构建失败并列出孤儿清单（豁免走显式列表，与 C1 EXEMPTIONS 同纪律）。这把"URL 契约"的前半截（文件必须在发布物里）前移到了构建期。

### C1 适配（verify-web-assets.mjs）

- 加 `--root` 参数：dev 默认遍历 `src/main/resources/web/`，CI 对 prod 实例遍历 `build/web-dist/`。
- URL 推导规则按模式切换：dist 模式下 `assets/**` → `/assets/**`（新通配路由），`vendor/**`、`favicon*` 等拷贝资产规则不变。
- 契约语义不变，对象从"源码树"换成"发布树"——这正是 Manager 要求的"门槛守卫发布的东西"。

### C2 适配（smoke.spec.mjs）

- **断言行为而非 URL**：`tests/smoke.spec.mjs:280` 当前断言 `200 /js/viewers/markdown.js`——bundle 后该 URL 变为 `assets/chunks/markdown-[hash].js`。改为断言渲染结果（Canvas 标签内出现 markdown 渲染 DOM 节点）+ 保留全程 404 监听（对任何 URL 生效，bundle 后同样兜住）。此适配与 P1 同 commit 完成。
- 后端路由：新增 `/assets/**` 任意深度通配（照抄 jsRoutes 的 W1-a 模式，~15 行 Scala）。

---

## 七、决策 6：CI

**推荐：构建步骤进 release/PR 流水线，三道门槛分工明确——checkJs/循环检查跑源码树（类型与图语义与打包无关），assets 契约 + smoke 跑产物。**

```
PR / push 流水线增量：
  frontend-smoke job（已是真 jar + fresh home）：
    + setup-node
    + npm ci                      ← 唯一新 devDependency: esbuild
    + node scripts/build-web.mjs  ← 含 metafile 孤儿校验
    → sbt assembly（web-dist 已存在 → 打进 jar）
    → 启动 prod 实例 → smoke 套件（自动验证 bundle）
    + node scripts/verify-web-assets.mjs --root build/web-dist http://localhost:8080
  js-types job：不动（checkJs + check-circular 继续跑源码树）
```

- 无 node 的 compile/test/assembly 基础 job 不受影响（sbt 可选挂载）。
- **P0 压缩的验收并进 smoke 套件**：新增一条用例断言 `Accept-Encoding: gzip` 请求拿到 `Content-Encoding: gzip` + transferSize < decodedBodySize。

---

## 八、决策 7：验收指标（measure-load.mjs 前后对比）

基线（W3-m 实测）：84 请求 / 6.32MB 传输 / networkidle 798ms。

| 指标 | 基线 | P0（gzip 单独）目标 | P1（+bundle）目标 |
|---|---|---|---|
| 传输总量 | 6.32MB | **≤ 2.0MB（-68%）** | **≤ 1.5MB** |
| 首屏请求数 | 84 | 84（不变，可接受） | **≤ 15** |
| networkidle | 798ms | ≤ 700ms（本地延迟主导，传输占比小） | **≤ 400ms** |
| 全门槛 | 绿 | 绿 + gzip 断言用例 | 绿（C1 走 dist、C2 行为断言） |

networkidle 说明：本地服务延迟极低，798ms 的大头是瀑布的串行 round-trip 调度而非传输——所以 gzip（砍传输）对 networkidle 改善有限，bundle（砍请求数）才是它的主杠杆。两个指标各归各的期，不在 P0 验收里苛求请求数。

---

## 九、风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| CSS bundle 改变加载顺序（**sapphire.css 必须最后**——设计系统标准层，等优先级冲突必胜） | 高 | build-web.mjs 不用 CSS import 图，按 index.html 现有 link 顺序显式拼接；验收用例断言最终计算样式（如玻璃控件 backdrop-filter 生效） |
| dev/prod 行为漂移 | 中 | CI frontend-smoke 跑 prod jar（现状机制自动覆盖）；大改前手动 opt-in 本地构建验证 |
| gzip 破坏 304 重验证（ETag/Vary） | 中 | P0 验收含 curl 级 304 断言（见决策 4） |
| 孤儿文件被 bundle 静默丢弃 | 中 | metafile 反向校验，构建期硬失败 |
| `/assets/**` 新通配路由的路径穿越 | 低 | 照抄 jsRoutes 的 `..`/反斜杠拒绝模式；JsStaticRoutesSpec 加对应用例 |
| package.json/npm 引入的供应链面 | 低 | 唯一依赖 esbuild，lock file 入库，`npm ci` 确定性安装 |

---

## 十、Backend 协同点清单（P0/P1 需要 Backend 配合的全部内容）

1. **P0**：GZip 中间件 + 排除清单（/ws、流式、二进制）+ ETag weak 化 + Vary 头（决策 4 的 4 个验收断言）。
2. **P1**：启动时 `web-dist/index.html` 资源探测 → 静态路由模式开关（单树服务）。
3. **P1**：`/assets/**` 任意深度通配路由（复用 jsRoutes 模式）。
4. **P2**（远期）：指纹资产 `Cache-Control: immutable` + index.html 保持 no-cache 的两级策略。

---

## 十一、实施顺序建议

1. 本文档审过 → **P0 立即派 Backend**（独立、纯后端、-73% 传输、验收含 measure-load 前后对比）。
2. P1 派 Frontend（build-web.mjs + index.html 改写 + metafile 校验 + C1/C2 适配）+ Backend 配合位 2/3，同分支或并行 worktree。
3. P2/P3 挂低优先级队列，等 P1 上线实测后按需启动。
