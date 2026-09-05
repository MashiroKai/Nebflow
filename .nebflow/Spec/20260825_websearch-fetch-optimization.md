# WebSearch / WebFetch 失败根因排查与优化方案（修正版）

| | |
|---|---|
| **日期** | 2026-08-25（初版 14:45 排查后即时完成；**同日修正版**：用户 08-25 裁定纠正根因认知错误后重写） |
| **类型** | 阶段文档：根因取证 + 方案设计（完成即冻结） |
| **范围** | 只分析不碰代码；覆盖 WebSearch（Tier 2/3）+ WebFetch 抓取链 |
| **红线** | 服务端改动必须带冒烟测试；不引入无 SLA 的外部依赖作为主路径 |

---

## 零、修正记录（2026-08-25 用户裁定）

**初版错误认知**：把「Tier 2 LLM provider 配额 Down → 搜索不可用」当成了主根因链——**这是错误的**。

**用户裁定**：「付费模型的模型 API 和网络搜索一般是用了不同的额度，模型额度用完了不代表搜索额度没了」——搜索能力不应绑定在模型配额上。

**修正内容**：
1. 根因 R1 重写：从「provider 配额耗尽导致搜索死」改为「**搜索错误地绑定在模型配额上**」这一设计缺陷（Tier 2 把搜索路由进模型 chat API，消耗模型 token、受模型 health/配额门控）。
2. 独立搜索 API 调研（见第三节）：智谱官方有独立 Web Search API（按次计费、与模型 token 计费分开）；Nebflow `nebflow.json` 已预留 `search` 配置块未接线——正是解耦的接入点。
3. 阶段重排：新增 **P2「搜索与模型额度解耦（Tier 2a 独立搜索 API）」**，原 P2/P3 顺延。
4. 验收条件新增：**「模型配额 Down 时搜索仍可用」的自动化验收**。

---

## 一、用户可读摘要（TL;DR）

**你遇到的两件事，根因都查清了，而且本会话刚实时复现了一遍：**

**1. 搜索（WebSearch）为什么报「All search engines failed. Sogou/WeChat: No meaningful results」？**

三层原因叠加，每一层单独看都能解释一部分：

- **搜索请求被错误地路由进模型配额**（核心设计缺陷）：默认 preset `general` = zhipu → qwen → deepseek。搜索执行走「模型内置搜索」——即用模型 chat API 附带 web_search 参数。08-25 zhipu（GLM-5.3）模型配额耗尽（错误码 1310，周/月上限，08-26 重置）、qwen 模型配额耗尽、kimi 超时——**三家模型的调用额度全挂**，搜索请求落到没有搜索能力的 deepseek 上白跑一轮，再降级到「内置引擎聚合」。但注意：**这三家挂的是模型额度，不是搜索额度**——智谱的独立搜索 API（按次计费 ¥0.01/次）当时完全可以继续工作，只是 Nebflow 没接它，搜索被白白陪葬了。
- **兜底层的引擎竞速有设计缺陷**：它同时请求 Sogou/360/DDG 三个引擎，但**谁先出结果（包括「失败」）就采用谁，其余立刻取消**。实测里 Sogou 返回一个 415 字节的反爬拦截页（瞬间失败）→ 竞速直接判失败，**把正在正常返回 467KB 真实结果的 360 给取消了**。这是「fast-fail poison」：最快的失败者毒死整批。
- **引擎本身在你网络环境下大面积不可用**：Sogou 反爬拦截（antispider 页）、Baidu 验证码页（512B）、WeChat 结果是 JS 渲染的抓不到、DuckDuckGo 直连超时（你的系统代理 127.0.0.1:7890 已开启但 Java 程序没走它，走代理实测 DDG 能通）。**五个引擎里只有 360 真的能用**，而它恰好每次都被竞速取消。

**2. 抓网页（WebFetch）为什么 docs.langchain.com 只返回 1 行？**

- 这个站点是 Mintlify 的 JS 渲染站，静态抓取拿到的是空壳（页面正文靠浏览器 JS 生成）。WebFetch 的「静态抓取失败 → 换浏览器渲染」兜底逻辑**只在 HTTP 403 或验证码标题时触发**，对「200 但内容为空壳」的情况不触发——所以空壳被当成功结果返回。
- 更糟的是：**本机根本没装浏览器兜底组件**（Obscura 没装、Playwright 是 provided 依赖运行时不在 classpath）——就算触发了兜底也是死路。你访问的具体 URL `/oss/python/langgraph/how-tos` 本身还返回 404（文档站改版路径变了），所以只看到 1 行错误。

**一句话总结**：搜索的三级防线（provider 搜索 → 内置聚合 → 引擎兜底）中，第一级因**搜索被错误绑定在模型配额上**而失效（智谱独立搜索 API 可用却未接入）、第三级因竞速缺陷 + 引擎反爬 + 无代理三连击失效；抓网页的浏览器兜底链因触发条件太窄 + 组件缺失完全失效。

---

## 二、根因分析（证据链）

### R1（重写）搜索错误地绑定在模型配额上——Tier 2 失效的真正机制

**证据（~/.nebflow/logs/nebflow.log，2026-08-25）：**

```
07:14:42 zhipu/GLM-5.3 marked DOWN: rate_limit_error code 1310
         [您已达到每周/每月使用上限，限额 2026-08-26 10:57:18 重置]
07:14:45 qwen/qwen3.8-max marked DOWN: insufficient_quota
         [1-week quota exhausted, reset 08-27 10:58:00 UTC]
08:22:47 kimi/k3-256k marked DOWN: timeout
13:27~13:47 kimi/moonshot-v1 marked DOWN ×5: error
13:30~14:44 provider search produced no search evidence (provider=deepseek, searchInfo=false) ×9
13:30~13:49 provider search produced no search evidence (provider=zhipu, searchInfo=false) ×5
```

**机制与初版认知的差别**：初版结论「三家有搜索能力的 provider 全挂 → 搜索死」把因果归在 provider 上。**修正后的因果**是：

- Tier 2 的实现（`SearchProviderResolver.executeProviderSearchFor`，#356）把搜索请求发进 **LlmHandle 模型管道**——`handle.send(LlmRequest(tools=Some(Nil), agentModel=ordered))`，由模型 chat API 附带 web_search 工具执行（zhipu `{"type":"web_search"}` / qwen `enable_search` / kimi `$web_search` 往返）。
- 这意味着搜索**消耗模型 token 配额**、**受模型 provider health 门控**（DOWN 即跳过）。08-25 三家模型额度/可用性全挂 → 搜索请求全部落到无搜索能力的 deepseek 白跑（9 次浪费）→ 降级 Tier 3。
- **但智谱的独立搜索额度没挂**：智谱把「网络搜索」作为独立产品按次计费（`POST /api/paas/v4/web_search`，¥0.01~0.05/次，见第三节），与模型 token 计费完全分开。08-25 只要 Nebflow 调独立搜索 API，搜索就能继续工作——搜索是被设计缺陷（绑模型配额）陪葬的，不是被搜索配额耗尽杀死的。

**次生问题（保留）**：`provider=zhipu, searchInfo=false` ×5 —— zhipu 在部分时刻「能响应但没带 web_search 证据」。注入代码（OpenAiAdapter `searchToolEntries`，`enable:true + search_result:true`，08-23 已对真实端点验证）正确，但 GLM-5.3 对 web_search 工具是否真正执行未冒烟确认——需要能力表标注 + 验证。

### R2 Tier 3 竞速缺陷：fast-fail poison（本会话 14:50 实时复现）

**证据（本会话自身）**：我的 `WebSearch("crawlee playwright render ...", engine=auto)` 返回与用户完全一致的失败：

```
All search engines failed.
Sogou: No meaningful results
WeChat: No meaningful results
```

**代码**（WebSearchTool.scala L460-465）：

```scala
def raceBatch(batch: List[SearchEngine]): IO[Either[String, (SearchEngine, String)]] =
  val ios = batch.map(e => IO.blocking(searchOne(query, e)))
  ios.reduce[IO[Either[String, (SearchEngine, String)]]] { (a, b) =>
    IO.race(a, b).map(_.merge)   // ← 首个完成者（无论成败）胜出，其余被 cancel
  }
```

**实测引擎响应（本机 curl 直连，2026-08-25 14:48）：**

| 引擎 | HTTP | 内容 | 结果 |
|---|---|---|---|
| Sogou | 200 1.75s | **415B antispider 拦截页**（`antispider:true`，跳 `sogou.com/antispider/`） | 必失败，且失败最快 |
| 360 | 200 1.27s | **467KB 真实结果，250 链接，6+ 可解析条目**（含 LangGraph 教程、CSDN、CSDN 博客） | 唯一可用引擎 |
| Baidu | 200 0.19s | **512B 验证码页**（wappass.baidu.com captcha） | 必失败 |
| WeChat | 200 0.49s | 32KB 页但文章链接全是 `javascript:void(0)`（JS 渲染列表） | 提取为空 → 失败 |
| DuckDuckGo | **000 超时 8s** | 直连不可达；**走代理 127.0.0.1:7890 → 302 可达** | 直连必失败 |

**结论**：批 1（Sogou/360/DDG）中 Sogou 瞬间失败 → 360 被取消；批 2（Baidu/WeChat）中 WeChat 先失败 → Baidu 被取消（本来也是验证码）。总耗时 4.9s，错误列表恰好 2 条（每批 1 条）——与用户报告完全吻合。**360 每轮都被竞速谋杀**。

![AS-IS 失败链路](/tmp/ws-failure.svg)

### R3 引擎适配器对当前反爬形态失效

- Sogou：08-22 是 checkSNUID cookie 脚本（垃圾指纹已拦截 ✓），08-25 升级为 **antispider 硬拦截页**——指纹过滤后为空 → 判失败（行为正确，但引擎本身已不可用）。
- Baidu：wappass 验证码页（512B，无结果块）→ `extractBaidu` 拿不到 result/c-container → 回退 generic → 空 → 失败。
- WeChat：wx.sogou.com 结果列表 JS 渲染，`extractGenericLinks` 只收 `href^="http"` 的链接 → 全丢 → 失败。
- **引擎列表与实际可用性严重脱节**：5 个通用引擎只有 360 可用（DDG 需代理）。

### R4 WebFetch 浏览器兜底链失效（双重失效）

**证据 1 — 触发条件太窄**（WebFetchTool.scala L189-221）：`NeedBrowser` 只在 `HTTP 403` 或标题命中 `isChallengeTitle`（just a moment/请稍候/attention required/access denied）时触发。实测 docs.langchain.com：

```
/oss/python/langgraph/how-tos → 404 (96KB, Mintlify JS 壳, 0 body 文本) → 1 行 Error
/oss/python → 307 → JS 壳 (1 div, 0 body 文本) → 空结果
```

Mintlify/Next.js 站返回 200/30x + JS 空壳，标题正常 → 不触发兜底 → 空壳当成功。**缺「thin-content + JS-shell 标记」启发式**（`__NEXT_DATA__`、`__NUXT__`、`<div id="__next|root|app">`、低文本密度）。

**证据 2 — 浏览器后端不存在**：

```
which obscura → 空（未安装）
build.sbt: playwright % "provided"（编译期有，运行时不在 classpath）
BrowserManager → "Playwright not on classpath, Obscura-only mode"
→ fetch() 恒返回 status=0 → tryBrowser 恒返回 Left("Anti-bot page could not be fetched...")
```

**即使触发兜底也是死路**。WebFetch 的「反爬降级」目前 100% 空转。

### R5 网络层：系统代理未接入

- `scutil --proxy` 显示 HTTPEnable=1（127.0.0.1:7890，实测 DDG 走代理可达）。
- `SharedBackend`（http.scala L19-25）`HttpClient.newBuilder()` 未设 `.proxy()`，JVM 未设 `java.net.useSystemProxies=true` → **直连**。
- curl 同样不读系统代理（无 env var）。**全链路绕过代理**：DDG 不可达、部分反爬判定（数据中心 IP）加剧。

---

## 三、独立搜索 API 调研（2026-08-25 修正版新增）

### 3.1 zhipu（GLM）——有独立搜索 API，按次计费，与模型 token 额度分开【核心证据】

智谱把「网络搜索」作为**独立产品**提供（工具 API 分类），与模型推理（对话补全）是两套独立计费/配额：

- **端点**：`POST https://open.bigmodel.cn/api/paas/v4/web_search`（文档：docs.bigmodel.cn/cn/guide/tools/web-search；api-reference/工具-api/网络搜索）
- **认证**：`Authorization: Bearer <token>`——**复用现有 zhipu API key，零新增 key**
- **参数**：`search_query`、`search_engine`（`search_std` 基础版 / `search_pro` 高级版 / `search_pro_sogou` 搜狗 / `search_pro_quark` 夸克）、`search_intent`（意图增强）、`count`、`search_domain_filter`、`search_recency_filter`
- **返回**：结构化 `search_result[]`（title / content / link / media / icon / refer / publish_date）——比爬 HTML 更适合直接喂给 agent
- **定价独立于模型**（bigmodel.cn/pricing「搜索工具服务」区，2026-08-20 抓取）：

| 搜索服务 | 单价 | 说明 |
|---|---|---|
| Search-Std | **¥0.01/次** | 智谱自研基础版，快速高性价比 |
| Search-Pro | ¥0.03/次 | 自研 Pro 版，召回率更高 |
| Search-Pro-Sogou | ¥0.05/次 | 搜狗引擎，支持搜狗百科/问问 |
| Search-Pro-Quark | ¥0.05/次 | 夸克引擎，时效性强 |

而「模型推理」区（GLM-5.3 等）是独立的 **¥/M tokens 计费**。两条产品线分开——**模型配额耗尽（1310）不影响独立搜索 API**。编码套餐（Coding Plan）也把「联网搜索、网页读取」列为独立权益。

**结论**：用户裁定在 zhipu 上直接成立。08-25 正确做法是调独立 web_search API，搜索本可继续工作。

### 3.2 qwen（DashScope）——模型级 enable_search 绑模型额度；独立搜索在另一产品线

- `enable_search: true`（Chat Completions / DashScope Generation）是**模型调用级参数**——走 chat completions 管道，消耗模型 token 配额、受模型 quota 门控（08-25 qwen `insufficient_quota` 时同样不可用）。且 DashScope OpenAI 兼容模式曾有**静默忽略该字段**的报告（代码注释已记录为 ACCEPTED degrade）。
- 阿里云另有「AI 搜索开放平台 / OpenSearch」的独立联网搜索 API（help.aliyun.com/zh/open-search/search-platform/developer-reference/web-search）——但属于**另一产品线**：需单独开通、单独 API key、单独计费，不是现有 qwen key 能直接用的。
- **结论**：qwen 主路径的搜索仍绑模型额度；独立搜索需另开 OpenSearch 产品线（成本高于 zhipu 复用现有 key 的方案）。

### 3.3 deepseek——官方无任何搜索能力

- DeepSeek 官方 API（api.deepseek.com）无内置搜索、无独立搜索 API。
- 在 DashScope 上调用 DeepSeek 模型时（deepseek-v3/v3.2/v4 等）可附 `enable_search`——但那是 DashScope 的模型级能力，仍走 DashScope 模型额度。
- **结论**：deepseek 链永远无法自己搜索，只能靠 Tier 2a（独立 API）或 Tier 3。

### 3.4 主流独立搜索 API 对比

| API | 接入成本（新 key/费用） | 中文 | 英文 | 限流/免费额度 |
|---|---|---|---|---|
| **智谱 web_search API** ★推荐 | **复用现有 zhipu key，零新增**；¥0.01~0.05/次 | 强 | 中 | 账户级 QPS（无免费额度，按量计费） |
| **博查 Bocha** | 新 key（微信扫码，国内备案）；AI Search 36元/1000次（¥0.036/次）；有免费资源包 | 强（自然语言搜索，语义排序） | 中 | Tier 0 账户 1 QPS / 30 QPM / 1000 QPD |
| **Serper.dev** | 新 key；$1/1K queries 起，2,500 次免费试用 | 中（Google 索引） | 强 | 免费 2500 credits（不过期，试用） |
| **Tavily** | 新 key；免费 1000 credits/月，$0.008/credit 起（≈¥0.06/次） | 中 | 强 | 免费 1000/月，99.99% SLA |
| **Brave Search API** | 新 key；免费 2000 queries/月（2026 起改 $5 预付计量，约 1000 次/月） | 弱 | 强 | 免费档已缩水 |
| **Bing Web Search** | 新 key（Azure AI Foundry）；2025-08 起对新客户关停旧 API | 中 | 强 | 迁移到 AI Foundry，成本上升 |
| **百度搜索开放平台** | **企业认证**才能接入 | 强 | 弱 | 商用授权制，个人不适用，不推荐 |

**推荐结论**：
- **首选 = 智谱 web_search API（search_std ¥0.01/次）**——复用现有 key 零新增成本、中文强、按次计费与模型配额天然解耦、结构化输出直接可用、支持多引擎（含搜狗/夸克，可覆盖英文 query 用 search_pro）。
- **第二候选 = 博查 Bocha**（国内备案稳定、免费资源包、自然语言搜索质量高；代价是新 key + Tier0 限流较严）。
- Serper/Tavily/Brave 为英文场景备选（需新 key + 信用卡，中文弱），暂不主推。
- 与红线一致：独立搜索 API 有 SLA/计费协议，可作为**主路径**（区别于无 SLA 的 Tier 3 爬取）。

### 3.5 Nebflow 现状与接入点

- **`nebflow.json` 已预留 `search` 配置块**（config.scala L102-110 `SearchConfig(provider, apiKey, engine, model)`、L151 `NebflowServiceConfig.search`）——schema 已存在但**未接线**（SearchProviderResolver 注释「Tier 1 MCP — not wired yet」）。这正是独立搜索 API 的天然接入位：`search: {provider: "zhipu", apiKey: "<现有 key>", engine: "search_std"}`。
- 当前 `SearchConfig` 无 `baseUrl` 字段——zhipu 端点固定可省，但为冒烟测试可控性（指向本地 mock）建议加可选 `baseUrl`。

---

## 四、参考开源方案评估（保留初版结论）

| 方案 | 机制 | 对 Nebflow 的价值 | 集成成本 |
|---|---|---|---|
| **crawlee**（Node.js） | 混合爬取：CheerioCrawler（静态快）→ PlaywrightCrawler（JS 渲染）自适应升级；request queue + 重试退避 + session 池 + 代理轮换 | **模式借鉴**：静态优先 + thin-content 检测升级渲染；session/代理管理。全量集成（Node 运行时 + 常驻 crawler）对单次 WebFetch 工具过重 | 高（需 Node 服务） |
| **r.jina.ai**（Jina Reader） | `https://r.jina.ai/<url>` 免费无 key 渲染为 Markdown，自带反爬处理 | **零安装渲染兜底**——一条 HTTP 调用解决 JS 壳；作为 WebFetch Layer 2 的替代/补充 | 极低（一个 URL 前缀） |
| **firecrawl** | API key 云服务，渲染 + 结构化 | 与红线（不依赖外部无 SLA 主路径）冲突，仅可作可选增强 | 低（但有 key/成本） |
| **Obscura**（Rust 自托管浏览器） | 已有代码路径（BrowserManager.fetchWithObscura），本机未安装 | **优先补齐**——代码已写好，装二进制即可复活 | 低（装二进制） |

**结论**：crawlee 不直接引入（重量级、Node 依赖、与 Scala 单体架构割裂），但采纳其**「静态 → thin-content 启发式 → 渲染」两级升级**模式；渲染后端按「Obscura（已编码，装即用）→ r.jina.ai（零安装兜底）→ Playwright（bundle 运行时）」排序。这同时满足红线（主路径仍是自家静态抓取，外部服务只做兜底）。

---

## 五、优化方案（分四阶段）

### Phase 1 — Tier 3 引擎竞速修复 + 代理接入（与额度无关，保留初版，最高 ROI）

**1a. 竞速语义修复（fast-fail poison 根治）**
`raceBatch` 从「首个完成者胜出」改为「**批内全部引擎并发执行（各自超时），取首个成功；全失败才判批失败**」。实现：`batch.map(e => IO.blocking(searchOne(query,e)).timeout(...))` 并发跑 + `parTraverse`/`racePair` 聚合，成功即短路返回，等待期不取消仍在跑的引擎。**行为变更**：一个引擎失败不再杀死同批可能成功的引擎。

**1b. 引擎优先级按查询语言 + 可用性重排**
当前 `ENGINES` 固定顺序 Sogou→360→DDG→Baidu→WeChat。改为：
- 英文/技术类 query（含拉丁字符比例启发式）：**360 优先**（实测唯一稳定可用）+ DDG（走代理）→ Sogou → Baidu → WeChat；
- 中文 query：360 → Sogou → Baidu → WeChat → DDG；
- Sogou/Baidu 标注 `antiBotProne`，不进首批（当前形态必被拦截，进首批只会毒批——1a 修后不再毒批，但仍浪费批次）。

**1c. SharedBackend 代理接入**
`HttpClient.newBuilder().proxy(ProxySelector.getDefault())` 或按配置 `nebflow.json.http.proxy`（缺省读系统代理 `java.net.useSystemProxies`）。CN 引擎直连、DDG 走代理。**行为变更**：DDG 从「必超时」变为「可用」，英文技术 query 命中率显著提升。

**1d. 错误信息可诊断化**
`searchOne` 失败原因分类标注：`Sogou: anti-bot blocked (antispider)` / `DuckDuckGo: timeout (proxy required)` / `WeChat: no parseable links (JS-rendered)`。用户/agent 不再面对空洞的 "No meaningful results"。

### Phase 2 —【新增·认知修正核心落地】搜索与模型额度解耦：Tier 2a 独立搜索 API

**2a. 新增 `StandaloneSearch` 执行器（新 `SearchProviderKind.StandaloneApi` 分支）**
在 `SearchProviderResolver` 新增独立搜索路由，直接 HTTP POST 独立搜索端点，**不走 LlmHandle 管道、不消耗模型 token、不受模型 provider health/配额门控**：
- 首选 zhipu：`POST https://open.bigmodel.cn/api/paas/v4/web_search`，`Bearer <zhipu key>`，`search_engine: search_std`（可选 search_pro/search_pro_sogou/search_pro_quark 覆盖多引擎）；
- 解析结构化 `search_result[]`（title/content/link）→ 复用现有 `formatEntries` 格式化 + provenance 标注 `Search source: search-api:zhipu (standalone, per-call SLA)`；
- 超时/失败 → 返回 None → 走既有降级（Tier 2b 模型内置 → Tier 3）。
- **行为变更**：模型配额全 DOWN 时搜索不再死——Tier 2a 独立可用。

**2b. 配置接线（`nebflow.json` 已有 schema）**
`search: {provider: "zhipu", apiKey: "<复用现有 zhipu key>", engine: "search_std"}`。`SearchConfig` 增加可选 `baseUrl`（冒烟测试指向本地 mock 用）与 `enabled` 开关。**无 key / 未配置 → 优雅跳过 2a，走原降级链**（向后兼容，现有用户零迁移）。

**2c. 独立搜索 API 健康联动**
`HealthMonitor` 增加「搜索 API 状态」通道（独立于模型 provider health）：最近一次 2a 调用成败 + 失败原因 + 累计次数。`/api/health` 或日志暴露「模型配额 DOWN 但搜索 API 正常」的对比信息，让用户一眼确认「模型挂了≠搜索挂了」。

**2d.（可选增强）博查 Bocha 作为第二候选**
`search.provider = "bocha"` 时走 `POST https://api.bochaai.com/v1/web-search`（新 key，¥0.036/次）。与 zhipu 同为结构化输出，适配器模式相同，成本低。英文场景可后续加 Serper/Tavily（新 key，暂缓）。

### Phase 3 — WebFetch JS 渲染 fallback 链修复（原 P2 顺延）

**3a. thin-content 启发式触发渲染**
静态提取后加判定：`body 文本 < 200 字符` 且 HTML 含 JS-shell 标记（`__NEXT_DATA__`/`__NUXT__`/`<div id="__next">`/`id="root"`/`id="app"`/Mintlify 特征）→ `NeedBrowser`。**行为变更**：Mintlify/Next.js/Nuxt 文档站从「返回空壳」变为「自动渲染」。

**3b. 渲染后端复活（按序）**
1. 安装 **Obscura** 二进制（代码路径已存在，零改动）；验收：`obscura fetch https://docs.langchain.com/oss/python --stealth --dump markdown` 返回正文；
2. 兜底接入 **r.jina.ai**：`BrowserManager` 之后/之前加 `https://r.jina.ai/<url>` 静态调用（无 key），失败才走浏览器；
3. Playwright 从 `provided` 改为运行时依赖 + 浏览器安装（重，P3 评估）。

**3c. 触发条件补全**
`NeedBrowser` 增加：HTTP 403 之外的 401/429/302-to-captcha、`isChallengeTitle` 扩充（`verify`/`captcha`/`cf-challenge`）。当前 404 场景保持报错（工具不猜 URL），但在错误信息里提示「文档站路径可能已改版」。

### Phase 4 — Tier 2 韧性 + 可观测性（原 P3 顺延，语义更新）

**4a. 全 DOWN 时跳过 Tier 2b 往返**
`executeProviderSearchFor` 入口：Tier 2a（独立 API）不可用 **且** 链内无任何 UP 的模型搜索 provider 时，直接走 Tier 3，不再把搜索请求打到 deepseek 白跑一轮（今日 9 次浪费）。P2 落地后此判断天然成立（2a 优先，2b 仅在 2a 失败且链内有 UP 搜索 provider 时尝试）。

**4b. zhipu GLM-5.3 证据验证**
冒烟确认 GLM-5.3 是否真的执行 web_search（今日 5 次「响应但无证据」）。不支持的模型从能力表剔除或降级标注，避免「假装搜索成功」的静默路径。

**4c. 健康联动面板（升级）**
`/api/health` 或日志暴露分层状态：① 独立搜索 API（2a）UP/DOWN/未配置；② 模型搜索 provider（2b）UP/DOWN 含配额重置时间；③ 当前生效 tier 及最近降级原因。用户恢复配额后能立即确认搜索恢复，也能看到「模型挂、搜索 API 正常」的解耦事实。

---

## 六、分阶段验收条件（全部二值、可自动化）

### Phase 1 验收（保留初版）

- [ ] **P1-1 竞速语义单测**：构造「快失败引擎 + 慢成功引擎」批 → 断言返回成功（fast-fail poison 回归锁死）
- [ ] **P1-2 竞速语义单测**：全失败批 → 断言收集全部错误而非 1 条
- [ ] **P1-3 真实验收（冒烟，硬性）**：真实 `sbt run` 后 WebSearch(`langchain.com langgraph map-reduce how-to "Send" API parallelize fan-out aggregation`, engine=auto) → **断言返回含 "Search engine: 360" 或 DDG 且有 ≥1 条 http 结果**（本机实测 360 必出结果）
- [ ] **P1-4 代理验收**：`curl -x http://127.0.0.1:7890 https://duckduckgo.com/html/?q=test` 返回非 000；Java HttpClient 侧 DDG 不再超时
- [ ] **P1-5 引擎优先级单测**：英文 query 的 enginesToTry 首元素 == 360；中文 query 首元素 ∈ {360, Sogou}
- [ ] **P1-6 错误可诊断**：全失败错误信息含分类原因（anti-bot/timeout/JS-rendered），不再只有 "No meaningful results"

### Phase 2 验收（新增——解耦核心，含用户裁定要求的自动化断言）

- [ ] **P2-1 独立 API 不走模型管道（单测，核心）**：stub HealthMonitor 全部模型 provider DOWN + stub 独立搜索端点（本地 mock HTTP server 返回结构化 `search_result[]`）→ 断言 `executeProviderSearchFor` **仍返回 Some(结果)** 且结果带 `Search source: search-api:` provenance，且**零次 LlmHandle.send 调用**——锁死「搜索不依赖模型配额」
- [ ] **P2-2 模型配额 DOWN 时搜索仍可用（冒烟，硬性）**：真实 `sbt run`，把模型 provider（如 zhipu）baseUrl 指向 `http://127.0.0.1:9/`（死端口，模拟模型 API 不可达/配额 DOWN），`search` 配置指向真实独立搜索端点 → WebSearch("...") 返回 ≥1 条结果且 provenance 为独立搜索 API；然后恢复模型 baseUrl → 行为不变（回归）
- [ ] **P2-3 未配置优雅降级**：`nebflow.json` 无 `search` 块 → 启动无异常、WebSearch 走原 Tier 2b/Tier 3 链（现有用户零迁移）
- [ ] **P2-4 配置接线单测**：`search: {provider: "zhipu", apiKey: "...", engine: "search_std"}` → resolver 解析出 StandaloneApi(zhipu) 且请求体含 `search_query`/`search_engine`/`count`
- [ ] **P2-5 错误可诊断**：独立 API 失败（401/429/超时）→ 降级日志含分类原因，不吞错
- [ ] **P2-6 健康联动**：模型 provider DOWN 时 `/api/health` 同时显示「搜索 API: UP」与「模型: DOWN」——解耦状态可见

### Phase 3 验收（原 P2 顺延）

- [ ] **P3-1 thin-content 单测**：喂入 Mintlify JS 壳 HTML（`__NEXT_DATA__` + 空 body）→ 断言触发 NeedBrowser
- [ ] **P3-2 真实渲染验收（冒烟，硬性）**：WebFetch(`https://docs.langchain.com/oss/python`) → 断言返回非空正文且含标题（走 r.jina.ai 或 Obscura 渲染）
- [ ] **P3-3 渲染后端可用性**：`which obscura` 非空 或 r.jina.ai 连通；`BrowserManager.fetch` 不再恒返回 status=0
- [ ] **P3-4 回归**：普通静态站（非 JS 壳）行为不变（不触发渲染，返回正常文本）；403 挑战页仍走兜底

### Phase 4 验收（原 P3 顺延）

- [ ] **P4-1 跳过逻辑单测**：2a 不可用且链内模型搜索 provider 全 DOWN → `executeProviderSearchFor` 直接 None（无 deepseek 白跑）；2a 可用 → 不依赖链内 health
- [ ] **P4-2 日志断言**：全 DOWN 场景日志出现 "skip Tier 2b (no search-capable provider UP)"，不再出现 deepseek no-evidence 噪声
- [ ] **P4-3 zhipu 冒烟**：zhipu 恢复配额后真实搜索返回 `searchInfo` 带 URL（或确认 GLM-5.3 不支持并剔除能力）
- [ ] **P4-4 状态可观测**：健康/状态输出含 ① 独立搜索 API 状态 ② 模型搜索 provider 配额信息 ③ 生效 tier

**红线回滚**：每阶段独立 commit、独立可回滚；Phase 1 只改 `WebSearchTool.scala` + `SharedBackend`；Phase 2 只改 `SearchProviderResolver.scala` + `config.scala` + `HealthMonitor.scala`；Phase 3 只改 `WebFetchTool.scala` + `BrowserManager`；互不耦合。

---

## 七、涉及文件清单

| 文件 | 阶段 | 改动 |
|---|---|---|
| `src/main/scala/nebflow/core/tools/WebSearchTool.scala` | P1 | raceBatch 语义、ENGINES 重排 + 语言启发式、错误分类 |
| `src/main/scala/nebflow/shared/http.scala` | P1 | SharedBackend 代理接入 |
| `src/test/scala/nebflow/core/tools/WebSearchToolGarbageSpec.scala` | P1 | 竞速语义测试扩展 |
| `src/main/scala/nebflow/llm/SearchProviderResolver.scala` | P2 | 新增 StandaloneApi 路由 + StandaloneSearch 执行器（zhipu web_search / bocha 适配）+ 全 DOWN 跳过 2b |
| `src/main/scala/nebflow/llm/config.scala` | P2 | SearchConfig 增加 baseUrl/enabled；接线 nebflow.json `search` 块 |
| `src/main/scala/nebflow/llm/HealthMonitor.scala` | P2/P4 | 独立搜索 API 状态通道 + 分层健康输出 |
| `src/main/scala/nebflow/core/tools/WebFetchTool.scala` | P3 | thin-content 启发式、NeedBrowser 条件补全、r.jina.ai 兜底 |
| `src/main/scala/nebflow/shared/BrowserManager.scala` | P3 | 渲染后端顺序（Obscura → r.jina.ai → Playwright） |

**附录 — 本排查关键日志/证据文件**：
- `~/.nebflow/logs/nebflow.log`（2026-08-25 07:14 配额 DOWN 标记、13:30-14:44 provider search 降级、14:44 用户失败、14:50 本会话复现）
- 实测 curl 结果：Sogou 415B antispider / 360 467KB 真实结果 / Baidu 512B captcha / WeChat 32KB JS 列表 / DDG 直连超时·代理 302
- 架构图：`/tmp/ws-failure.svg`（AS-IS 失败链路）、`/tmp/ws-tobe.svg`（TO-BE：Tier 2a 独立搜索 API 解耦）
- 独立搜索 API 证据：智谱官方文档 docs.bigmodel.cn/cn/guide/tools/web-search + api-reference/工具-api/网络搜索；定价页 bigmodel.cn/pricing（「搜索工具服务」区：Search-Std ¥0.01/次 等）；阿里云百炼 help.aliyun.com/zh/model-studio/web-search（enable_search 模型级）；OpenSearch 联网搜索 API help.aliyun.com/zh/open-search/search-platform/developer-reference/web-search；博查 open.bochaai.com（web-search API，¥0.036/次）；Serper serper.dev（$1/1K，2500 免费）；Tavily docs.tavily.com（1000 credits/月免费）
