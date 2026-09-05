# 前端质量路线实施报告 — 2026-08-16

> 源起：Canvas viewer 插件 404 事故（P0 另线修复中）。
> 用户 10:46 拍板：路线文档 /tmp/frontend-quality-roadmap.md 的 **Phase C + A + B 全部实施**（覆盖文档"B 暂缓"的原建议——性能痛点由用户直接认定），另追加**首屏加载性能专项**并入 Phase B。
> 验收标准全部以路线文档第 6 节为准（二值可自动化）。

## 波次与路由

| 波次 | 内容 | 负责人 | 前置 | 状态 |
|---|---|---|---|---|
| P0-a（另线） | viewers 404 修复（4281f720） | Backend | — | ✅ 已合并 archive/scala（待用户重启生效） |
| P0-b（另线） | WS probe-first 无感 fallback（1206d645，含 cookie 写入竞态守卫） | Frontend | — | ✅ 已合并（187227dc） |
| P1-3 | qa-frontend 职责重定义（9c39c23 entities 仓） | prompt-engineer | 无 | ✅ 验收 PASS |
| P1-ghost | Teams 幽灵 running（getActiveAgents busy 过滤） | Backend | — | ✅ 已合并（4cc0530c，含存量清扫） |
| 追加 | AskUserQuestion multiple 多选（6f687839） | tool-engineer | — | ✅ 已合并（E2E 4/4 + Scala 42 绿） |
| W1-a | 路由通配架构修复（js/** 递归 + 穿越防护 + trailing-slash 守卫） | Backend | ghost 之后 | ✅ 已合并（1ffa9b81 → 29ded008） |
| W1-b | C1 资源契约测试（ce56d9fe：verify-web-assets.mjs 遍历式 + CI 双 runner，249/249 文件 200 + 注入实验证明非白名单） | Frontend | P0-b 合并后 | ✅ 交付，随 W1 整线合并 |
| W1-c | C2 Playwright 打真实实例（96d274e3：frontend-smoke job + 懒加载真链路 + 全程 404 监听 + 注入三连全红 + token 硬编码消灭） | Frontend | W1-b | ✅ 已合并（cd649956） |
| W2 | Phase A 类型渐进（W1-d×2 + A0/A1 + P2-3 + P2-4，feat/fe-quality-w2 五 commit → 4a9775e1） | Frontend | W1 合并后 | ✅ 已合并，四 checkbox 全绿（基线 420/40→372/39，静态图无环进 CI） |
| W3-m | 首屏加载摸底量化（请求数/传输大小/瀑布瓶颈） | qa-frontend | P0 全合并后 | ✅ 完成（数据见「W3-m 首屏摸底」段，脚本 /tmp/measure-load.mjs 可复测） |
| W3-x | awaitRestore 固定 3s 快速路径（W3-m 瓶颈 #1 服务端半） | Backend | — | ✅ 已合并（c3c30024，networkidle 3680→798ms −78%，870/870 全绿） |
| W3-i | esbuild 构建管线（设计 ✅ /tmp/esbuild-design.md 已审过转 Nebula；实施：P0 gzip=Backend、P1 build 侧=Frontend 并行） | Frontend+Backend | W2 ✅ | 实施进行中 |
| W4-bench | Benchmark headless 基建 P0（同步 turn 端点 + nebflow run -p + userMessage 断链 + NEBFLOW_HEADLESS；预排不抢质量线） | Backend | gzip 之后 | 排队（设计文档先行） |
| P1-noop | 压缩周期 no-op `true`（save turn 措辞"END THIS TURN"祈使句→模型跑仪式性命令） | Backend | — | 进行中（/tmp/nb-compaction-reminder） |
| 合并关卡 | 前端改动合并前必过 C1（verify-web-assets.mjs）+ C2 | Manager | W1 落地后 | ✅ Team Rules 已写入（11:37），Manager 审合即生效（12:09 首次执行 249/249） |

## 诊断记录

- **Teams 幽灵根因（Backend 4cc0530c 实证，修正 Manager 初判）**：6 行 = 3 live（agentStart，actor-path key，带 taskDescription→带前缀行）+ 3 重建快照（getActiveAgents 返回全部 Team registry 条目且无 busy 过滤，task=''→裸名行 done=false→running 徽标；sessionId key 与 live 行双 key 并存）。team agent 长驻 registry 是设计使然（等下封 Mail），"在 registry"≠"在跑 turn"。重启后首封 Mail 重新注册→复发。subagent-tasks 文件与本 bug 无关（Mail 路径不写它；陈旧 running 是 8月13 SubTask 遗留，已清扫 2 条→failed）。修法：filterActiveAgents 按 TeamSessionRegistry.isBusy 过滤（与 /api/teams/mounted 同口径）。**遗留边缘（W1-c 前端收编）**：刷新瞬间在跑的 agent 重建条目（sessionId key）turn 结束后不被 actor-path key 的 agentDone 清掉，残留到下次刷新——前端 agentDone 双 key 清理。

## CI 说明

CI 验收项（workflow yaml + 脚本）随代码落地并本地端到端验证；GitHub Actions 实际运行验证挂到下一次 push（push 由用户把关，beta.52 窗口）。

---

## W3-m 首屏摸底（qa-frontend，11:31 数据）

**对象**：archive/scala HEAD 187227dc（含今早三修复），隔离实例 `sbt run --home /tmp/qa-perf-base --port 8095 --no-browser`。
**方法（复测口径，esbuild 前后对比必须同口径）**：脚本 `/tmp/measure-load.mjs`（Playwright + CDP，全局 playwright 以绝对路径 import）。每次**全新 browser context + `Network.setCacheDisabled(true)` = 纯冷加载**，连续 3 次取中位数；token 从 `/tmp/qa-perf-base/auth.json` 读、addInitScript 写 localStorage。**可交互信号** = `#input` 可用且 `#send-btn svg` 已替换（即 main.js 主体执行完，lucide.createIcons 已跑）——textarea 是静态 HTML（50ms 就"可见"），不能用静态元素当可交互。模块图静态分析脚本 `/tmp/module-graph.mjs`。
**环境注意**：localhost 回环传输近零成本，下列 FCP/DCL 数字是下界；真实网络下 6.3MB 未压缩传输才是痛点放大器。

### 关键数字（中位数）

| 指标 | 数值 |
|---|---|
| TTFB / FCP / DCL / Load | 4ms / 52ms / 118ms / 120ms |
| 可交互（app boot） | **119ms**（localhost 下界） |
| networkidle（墙钟） | **3682ms** ← 被长轮询污染 |
| 静态资源最后字节 | **3125ms** |
| 全程含 Monaco idle preload | **6683ms** |
| 总请求数 | **84**（API 10、app JS 模块 45、CSS 16、vendor 5、Monaco 3、其他 5） |
| 总传输 / 解码 | **6.32MB / 6.30MB**（**全程零压缩**，compressedCount=0，http/1.1） |
| 可交互前必须到达 | 75 请求 / 2.34MB |

### 体积构成（解码）

| 部分 | 体积 | 占比 | 状态 |
|---|---|---|---|
| Monaco（editor.main.js 3.6MB + css + loader） | **3.93MB** | **62%** | 已 minify（min build），idle preload，**全量 editor.main**（全部 basic-languages + 全特性），worker/语言服务按需（tsWorker 5.4MB 未加载） |
| vendor 非 Monaco 5 库（lucide 347K / lottie 298K / katex 269K / highlight 122K / marked 35K） | 1.12MB | 18% | 均已 minify，**首屏经典 script 全量加载** |
| app JS 45 模块 | 0.99MB | 16% | **未 minify**（main.js 2551 行/sidebar.js 3000 行），零代码分割，44 文件静态全图首屏全下 |
| CSS 16 文件 | 0.24MB | 4% | 未 minify，渲染阻塞 |
| 懒加载未触发 | mammoth 628K + xlsx 864K | — | docx/xlsx viewer 内 fetch/script 标签懒加载，首屏不加载，正确 |

### 瀑布瓶颈定位

1. **TOP1（协议层，非体积）：`/api/teams/mounted` 6 并发长占 socket**。服务端 `RestApiRoutes.scala:985` `awaitRestore(3000L)` 固定占 3s；前端 flowCanvas autoRestore 在启动路径被并发触发 6 次（init/onSessionChange/onReconnect/restoreTabs 多触发源，flowCanvas.js:318 唯一 fetch 点）。http/1.1 每 origin 仅 6 socket → **连接池 100% 被长轮询占满 ~3s**，直接证据：Monaco editor.main.css（129KB）`requestStart-fetchStart` stall **2868ms**——排队等 socket。这是 networkidle 3.7s 的唯一成因，真实网络下还会拖累首屏模块。
2. **TOP2（体积）：Monaco 3.93MB 占 62%**。idle preload 不阻塞可交互，但冷启动后 ~6.7s 才就绪，用户秒开 Canvas 文件会撞上"editor 还在加载"。
3. **TOP3（请求数）：45 个 app 模块 + 16 CSS 无打包**。模块图静态链最深 4（main→sidebar→taskList→taskArchive→canvas），http/1.1 下 45 个模块抢 6 socket；localhost 下总计 ~100ms，真实网络（RTT 100ms+）下 = 数秒。
4. **无压缩直出铁证**：84 请求 content-encoding 全为 none；`curl -H 'Accept-Encoding: gzip' /js/main.js` 无 Content-Encoding 头。服务端无 gzip middleware。
5. **缓存策略分裂**：app 文件 `Cache-Control: no-cache + Last-Modified`（支持 304 再验证——但热加载也要 45+ 次再验证 RTT）；**vendor/ 无任何缓存头**（靠浏览器启发式）。无 ETag、无 content-hash 文件名、无 immutable。
6. 无重复加载证据；懒加载链（viewers/mammoth/xlsx/KaTeX 字体）首屏均未触发，无浪费。

### esbuild 该切哪里（按数据排优先级）

| 优先级 | 动作 | 预计收益 | 依据 |
|---|---|---|---|
| **P0** | **gzip/brotli**（esbuild 预压缩 .gz/.br + 服务端 Content-Encoding，或 http4s GZip middleware） | 6.3MB → **~1.7MB**（-73%；JS/CSS 文本压缩率 70-75%，Monaco min js ~3.9MB→~950KB） | 零压缩直出是单一体积杠杆，比打包收益大 3 倍 |
| **P1** | **打包合并**：45 app 模块→1 bundle、16 CSS→1 | 请求 84→~20；消除 45 次串行握手/再验证；真实网络省 1-3s | 模块图 44 文件 950KB 全量首屏，深度 4 |
| **P2** | **minify app JS/CSS**（vendor/Monaco 已 minify） | JS 985KB→~590KB、CSS 241KB→~180KB（打包时顺带，零额外成本） | 源码未压缩 |
| **P3** | **Monaco 裁剪/分包**：自定义 bundle 砍未用语言与 nls（103 文件里 3 个 nls.messages.* 各 200-500KB 等），或 preload 推迟到首次打开文件 | 首屏传输 -3.9MB（若从 preload 移出）或 Monaco 本体 -30~50%（裁剪） | 62% 占比；但改动面最大、AMD loader 兼容需验证，建议放打包稳定后 |
| **P4** | **content-hash 文件名 + immutable** | 热加载近零传输 | 需打包产物带 hash，依赖 P1 |
| 另线（非 esbuild） | autoRestore 单飞去重 + awaitRestore 空 home 快速返回 | networkidle 3.7s→~0.2s；释放 socket 池 | TOP1 瀑布瓶颈，后端/前端小改，建议并行派单 |

**给 W3-i 设计文档的一句话**：先把 gzip + 单 bundle + minify 做掉（P0-P2，预计冷加载 6.3MB→~1.7MB、请求 84→~20，吃掉 80% 收益），Monaco 按需 chunk 作为第二步独立验证。

### W3-i P0 gzip 分期复测（qa-frontend，13:46，PASS）

对象：archive/scala 7f45a222（gzip middleware 合并后，detach worktree 已清理），隔离实例 8093 + /tmp/qa-gzip home，同脚本同口径（/tmp/measure-load.mjs，冷加载 ×3 中位）。按「验收分期归因」原则：gzip 只看传输体积，networkidle 不归它管。

| 指标 | W3-m 基线（187227dc） | c3c30024 后 | **gzip 复测（7f45a222）** | 判定 |
|---|---|---|---|---|
| wire 总量（transferSize 合计） | 6.32MB | — | **1.64MB（−74.1%）** | ✅ ≤2.0MB 目标线 |
| decoded 总量 | 6.30MB | — | 6.31MB | ✅ 持平，内容未变 |
| 请求数 | 84 | — | 79（mounted 6→1） | ✅ |
| networkidle | 3682ms | 798ms | 889ms | ✅ 持平（+91ms 噪声内，无恶化红旗） |
| 静态资源最后字节 | 3125ms | — | **363ms（−88%）** | ✅ |
| 全程含 Monaco preload | 6683ms | — | **3890ms（−42%）** | ✅ |
| 压缩覆盖 | 0/84 | — | 74/79（全部 text 资产） | ✅ |
| socket stall | monaco css 2868ms | 清零 | 清零 | ✅ |

单资产抽样：Monaco editor.main.js wire 3678KB→932KB；lucide 347→81KB；app JS 普遍 −75%。
响应头抽查：text 资产 `Content-Encoding: gzip` + `Vary: Accept-Encoding` ✓；不带 Accept-Encoding 回退未压缩 ✓；png/woff2 未压 ✓。
微基准外推 1.55MB vs 全链路实测 1.64MB（+6%，差额=未进微基准的小资产），外推成立。

### W3-i P1 bundle 复测（qa-frontend，14:09，PASS——W3-i 末道验收）

对象：archive/scala 1ef075cd（webdist：`node scripts/build-web.mjs` → `sbt -Dnebflow.webdist=1`，隔离实例 8096 + /tmp/qa-p1-recheck-home，已清理），同脚本同口径。

| 指标 | W3-m 基线 | P0 gzip 复测 | Frontend 自测 | **P1 独立复测** | 吻合判定 |
|---|---|---|---|---|---|
| 请求数 | 84 | 79 | 34 | **36** | ✅（+2 已定位：/api/neblink/status 双发；±5% 边缘但有因） |
| wire 总量 | 6.32MB | 1.64MB | 1,463,920B | **1,465,985B（+0.14%）** | ✅ |
| decoded 总量 | 6.30MB | 6.31MB | — | **5.76MB**（−0.55MB=minify+chunk 未全载收益） | ✅ |
| networkidle | 3682→798ms | 889ms | 795ms | **821ms（+3.3%）** | ✅ 持平 |
| 可交互（app boot） | 119ms | 143ms | — | **96ms** | bundle 单文件解析更快 |
| socket stall | 2868ms | 清零 | — | 清零 | ✅ |

**归因独立验证（两个未达设计目标项）**：
- 请求数未达 ≤15 → **归因成立**：esbuild splitting 保留动态 import 边界，首屏触达 16 个 lazy chunks + 2 entry = 18 JS 请求（build 产出 50 chunks 中首屏 16）。是懒加载设计取向，非缺陷。
- networkidle 未达 ≤400ms → **归因成立**：assetsLastByte 295ms，networkidle 821 ≈ 295 + Playwright 500ms 静默判定窗；瀑布尾部 monaco idle preload 109ms 起 220ms 完，之后零 HTTP 流量。800ms 大头是 networkidle 定义本身的静默窗，非仍在加载。
- gzip 对 dist 树继续生效：hashed entry app-F2T6TU5B.js 208KB→53KB wire，Content-Encoding: gzip + Vary ✓；压缩覆盖 31/36。

**观察项（非本期范围）**：hashed 资产响应头仍 `Cache-Control: no-cache`——content-hash 文件名已具备 immutable 条件但未配缓存策略，热加载仍需逐文件 304 再验证。P4 缓存策略可单独派小单。

方法注记：复测的 `totalWallInclMonacoPreload` 3827ms 中 3000ms 是脚本固定等待（monaco 实际 220ms 已就绪），与基线 6683ms（真实被长轮询阻塞）含义不同，不作对比依据。

---

## 执行记录（各波次按时间追加）

### 13:16 W3-i 设计审过 + 双线实施启动 + benchmark 预排
设计文档（/tmp/esbuild-design.md）Manager 逐节审核通过并转 Nebula。要点：P0 gzip 中间件独立先行（纯后端 -68%）；P1 单树切换（拒绝混合 fallback）+ metafile 孤儿校验 + C1/C2 适配守卫发布物；Monaco 永不打包；验收分期归因（P0 传输 ≤2.0MB / P1 ≤15 请求 networkidle ≤400ms）。派单：Backend 插单 P0 gzip（P1-noop 后、benchmark 前——质量线优先）；Frontend P1 build 侧（package.json+build-web.mjs+metafile+C1 --root+C2 行为断言，运行时两件等 Backend）；qa 待 P0/P1 分期复测。benchmark 预排（Nebula 13:14）：headless P0 四件（turn 端点/run -p/userMessage 断链/NEBFLOW_HEADLESS），设计文档先行，Backend 队列第三位。

### 13:08 W2 整线合并（Manager）+ 第二次关卡执行（三关）
feat/fe-quality-w2 五 commit fast-forward 合并（cd649956→4a9775e1）。**合并树三关全绿**：verify-web-assets 249/249 + check-circular（静态边 Tarjan，57 文件无环）+ checkJs（372 基线内 + 3 零错钉子文件）。Phase A 四 checkbox 闭环。关键侦察：madge 动态 import 假阳性（25 路径 illusion），静态真图 3 SCC；check-circular.mjs 静态边专用方案替代 madge。四刀薄切口：state.getActiveView 依赖注入（chatView 注册 + 可选链防御）/dropbox 动态化/批量删除 modal lazy/死 import 清除。W3-i esbuild 设计文档已派（七决策 + 门槛必须守卫发布物的适配方案）。

### 12:09 W1 整线合并（Manager）+ 首次合并关卡执行
ce56d9fe + 96d274e3 合并进 archive/scala（c3c30024→cd649956）。**Team Rules 合并关卡首次真实执行**：合并树隔离实例 8096 + verify-web-assets.mjs → 249/249 全绿。C2 注入三连证据：删 viewers 文件/改错 import/注释路由 → Canvas 用例或 C1 三路全红——关卡有效性实证（非形式化）。token 硬编码消灭（env→auth.json 回退）。新基建知识：worktree sbt run 服务走 bg-jobs 打包 jar + JVM 缓存 JarFile——资源注入实验必须重启实例（连踩两次实证）。fresh home onboarding 浮层拦截点击 → dismissOnboarding helper（点真实 #ob-skip，skipped 豁免探活门）。
### 12:55 W2 进行中：P2-3 交付（Frontend，92b72812）
state/ws/utils 导出 @typedef + ZERO_ERROR_FILES 三钉（核心文件永不退步）；vendor-globals.d.ts 消 19 个 TS2304；**顺手修真 bug：EXT_TO_LANG dockerfile 键重复（TS1117）**；协议发现：用户输入消息刻意无 type 字段（typedef 按可选记录）。**基线烧低 420/40 → 372/39（−48）**。门槛双向 PASS + smoke 11/11 + assets 249/249。下一步 P2-4：实测 25 条循环路径（比文档 4 组更深），逐边最薄切口，重构面超限的边走显式豁免。

### 12:42 W2 进行中：W1-d 热身 + A0/A1 交付（Frontend）
- W1-d #1 fa19e119：agentDone 双 key 清理（snapshot 行 key=sessionId vs 实时事件 key=actor path，双清+2s 删除）——Teams 幽灵最后边缘闭环
- W1-d #2 1d7b90b3：autoRestore 单飞 + 1.5s 合并窗（波次到达实测 6→3→精确 1）；refresh force 移除（4 caller 全 WS 事件驱动）；新鲜度权衡（1.5s 陈旧快照可接受+自愈）注释在案
- A0+A1 5b643f44：jsconfig + check-js-types.mjs（(file,TScode) 计数比对抗行号漂移 + --update 烧低 + ZERO_ERROR_FILES 硬闸门空位）+ CI js-types job。**基线起点：420 错误 / 40 文件**（checkbox ④ 起点）。注入双向变红验证
- 下一步 P2-3：state/ws/utils @typedef 至零错误烧低基线

### 11:52 W3-x 合并（Manager）
c3c30024 fast-forward 进 archive/scala（51cd6d78→c3c30024）。根因比预期更深：restoreDone 信号唯一生产者是 FlowTreeActor setup，而 actor 只经 LoadTool→getOrCreate 创建——空 home 信号永不发（主实例重启后到首次 LoadTool 间同病）。修法：restoreStarted 标志 + 两级 fast-path（已完成/从未启动→立即；在途→等，3s 上限不变）。**networkidle 3680→798ms（−78%）、socket stall 清零、mounted 每请求 4-10ms**；全套 870/870 全绿（Crossref 本次也通）。调用点盘点：全仓唯一调用方=mounted 本尊（Deferred 注释里 /api/flows 的说法是陈旧注释，grep 实证无调用）。用户重启后首屏可感知提速。

### 11:35 W1 Step0 P0 终验 PASS（Frontend）
真实后端隔离实例 8093：md 渲染 + 二进制图片渲染 + **viewers 懒加载链 11/11 全 200**，零 404 零 pageerror（仅 2 条已知良性 NebLink 探测 403）；WS probe-first 合并后复跑 2/2（cookie 正常首连零拒绝 / cookie 阻止首连即 ?token=）。**P0 正式闭环，已报 Nebula + 提醒用户重启**。测试设计教训：Canvas preview 标签是 VS Code 替换语义（第二个文件替换第一个），多文件断言必须逐个开逐个断言。C1/C2 继续进行中。

### 11:28 AskUserQuestion 多选合并（Manager）
6f687839 三方合并进 archive/scala，零冲突。审查要点：parseItems 手写 cursor 解析容忍缺省 multiple（无 circe deriveCodec 陷阱）；WS payload 仅 multiple=true 时携带字段（存量流量逐字节不变）；LLM 侧三层教学（tool description + inputSchema + PromptSections）。验证：Scala 42 绿（15 新例含三态解码/legacy 回归）+ E2E 4/4（单选回归/多选数组 payload/混合槽位对齐/490px 布局，零 LLM 消耗驱动）。设计决策备案：answers 线格式保持 List[String]，多选槽内 JSON 数组字符串编码——原生 Json 线格式需 WebSocketRoutes+InteractionHub 协同（另开任务）。

### 11:23 W1-a 合并（Manager）
1ffa9b81 三方合并进 archive/scala（187227dc→29ded008），零冲突零残留引用。合并树验证（独立 worktree 避主仓 sbt 锁）：Test/compile 223+94 源零错 + gateway 三 spec 21/21（JsStatic 8 + ActiveAgents 6 + Uploads 7）。js/** 递归路由 + trailing-slash 目录守卫（活实例 junk 200 实测复现后堵掉）+ locales no-cache 补齐。**新增 js 子目录从此零路由改动**——C1 遍历式契约测试的服务端前置就绪。

### 11:18 P0-b 合并（Manager）+ W1/W3-m 开工
1206d645 经 ort 三方合并进 archive/scala（HEAD=187227dc，今早三修复全齐）。Frontend 复审要点：探针门控（首连 defer 到探针 settle）、cookie 值比对守卫（消灭 purge→set 空窗竞态）、onclose 兜底链保留。worktree/分支已清。
- Frontend → /tmp/nb-w1-quality（feat/fe-quality-c1-c2 @187227dc）：Step0 P0 终验（viewers 加载+文件打开+WS 干净连接）→ C1 资源契约测试 → C2 Playwright 真实实例升级
- qa-frontend → W3-m 首屏性能摸底（隔离实例 8095，只测不改，报告追加本文件）

### 11:15 P1-ghost 合并（Manager）
4cc0530c fast-forward 进 archive/scala（4281f720→4cc0530c）。filterActiveAgents + ActiveAgentsFilterSpec 6 用例（真实 TeamSessionRegistry）；847/848 绿（Crossref 活网豁免有证据）。存量清扫已执行：cleanup-subagent-tasks.py 真跑，2 条 8月13 陈旧 running→failed + 垃圾文件删除，剩余 4 文件全终态。markTeamBusy/markIdle AgentActor 5 调用点实证在位（flowCanvas.js:329 旧注释过时，W1-c 时顺手修正）。

### 10:52 P1-3 完成（prompt-engineer）
entities 仓 9c39c23：qa-frontend system.md 双职责重写（122→64 行）。Manager 抽查 PASS：双职责齐全、禁替身规则 CRITICAL 段可执行、只验收不修改边界、Rust 契约 4 维度保留。

### 10:58 P0-a 合并（Manager）
4281f720 fast-forward 进 archive/scala（c1a0523a→4281f720）。companion viewersRoutes + ViewersRoutesSpec 6 用例；841/842 绿（Crossref 活网红豁免有证据）；隔离实例 11/11 viewers URL 200+no-cache。worktree/分支已清。**8080 生效待用户重启**。
