---
type: research
status: 调研稿（只调研、只出方案，零代码改动；候选方案待作者裁定）
title: 网络调查循环插件——现状盘点、外部方案、循环骨架与候选架构
date: 2026-11-02
tags: [research, web-search, web-fetch, crawl, ptc, plugin, entity-graph, evidence, osint]
related:
  - "[[20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa]]"
  - "[[design/20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]"
  - "[[glossary#^plugin-package]]"
---

# 网络调查循环插件——现状盘点、外部方案、循环骨架与候选架构

> **一句话结论**：要做的不是"更好的搜索工具"，而是一台**可核查的调查循环**——抓取→建模→分析→补抓。
> 现有能力面只覆盖了"抓一次/搜一次"这一格：搜索是多引擎无 key 的赛跑聚合（`WebSearchTool.scala:90-99`），
> 抓取是 HTTP 直取加一个**实际不可用**的浏览器回退腿（`build.sbt:108` 把 Playwright 设成 `provided`），
> 而"建模/证据/缺口"三件在仓里**完全空白**。推荐形态 = **一个插件、一个常驻 MCP 工具服务器（search/fetch/extract/evidence/graph/gaps）＋ PTC 脚本主导的调查轮次**，
> 模型只在"选缺口、定判据、写结论"三处出手。
>
> **本批范围**：仅调研与方案，未改任何代码、未动 `plugins/` 下任何现有插件、未 push。

---

## 0. 结论速览

1. **搜索**：`WebSearchTool`（`core/tools/WebSearchTool.scala`）——360/DuckDuckGo/Sogou/Baidu/WeChat/arXiv/Crossref 七引擎，**无 API key 的 HTML 抓取 + 三路赛跑首胜**；上层还有三层路由（Tier 1 用户 MCP 未接线、Tier 2a 智谱独立搜索 API、Tier 2b 模型内置搜索、Tier 3 上者兜底）。
2. **抓取**：`WebFetchTool`（`core/tools/WebFetchTool.scala`）——sttp HTTP 直取 + 正则 HTML→文本 + 5 分钟 LRU；**没有 JS 渲染**，浏览器回退腿（Obscura→Playwright）在发行形态下不可用。
3. **插件体系**：能承载"自带 MCP server 的新工具面"，但**没有依赖声明机制**（未知键宽容忽略），`timeoutMs` 会被装载器丢弃（`PluginRegistry.scala:1129-1138`），子进程**没有任何出站策略**（PTC 的"联网一律拒"是 PTC 自己的审计钩子）。
4. **社交面**：NebLink 好友是结构化对象（`userId/username/displayName/avatar/blocked/remark`），群只有 `memberCount` **没有成员表**（`/api/groups/{id}/members` 仅做前端透传，客户端无解码）；飞书入站是**最强的关系信号**（`chatId + senderId + mentions[].id`）。
5. **循环骨架**：推荐 **证据表为唯一真相源（append-only）+ 实体关系图为投影 + 时间线作为边的一种**；缺口发现用**规则出候选、模型在候选里选**的混合档。
6. **证据**：三道机械护栏（quote 必须是原文子串、结论必须挂 evidence_ids、抽取器只搬原文不生成）+ 四级置信度（C3/C2/C1/C0），冲突**并列不合并**并转成核验查询。
7. **PTC 集成**：`tools.*` 命名空间天然可扩（`_Tools.__getattr__` 动态派发），真正的硬约束是 `ptc_executor.py:396` **只吃 `tool_servers[0]`** ⇒ 第一版必须**合并进同一个工具服务器**。
8. **循环边界**：推荐**混合**——脚本内跑"检索→抓取→抽取→入库"机械段，模型只在每轮末看增量图+缺口清单做决策；单轮 ≤30s、总轮数 ≤3。
9. **最小切片**：单人→账号发现的三轮循环（实抓 2-3 源 → 建最小图 → 规则找缺口 → 补抓 → 带证据分级结论），1-2 天。

---

## 1. 现状盘点（全部带 file:line）

### 1.1 模型现在能调的三件网络工具

| 工具 | 实现 | 后端 | key 来源 | 关键参数 |
|---|---|---|---|---|
| `WebSearch` | `src/main/scala/nebflow/core/tools/WebSearchTool.scala:12-603`（注册 `core/tools/registry.scala:27`） | 七引擎 keyless HTML 抓取 + 学术 API | **无**（仅 Semantic Scholar 可选 `SEMANTIC_SCHOLAR_API_KEY`，`:339`） | `query`（≥2 字）/`engine`/`max_results`(实为字符上限)/`allowed_domains`/`blocked_domains` |
| `WebFetch` | `src/main/scala/nebflow/core/tools/WebFetchTool.scala:12-298`（注册 `:28`） | sttp `SharedBackend` HTTP 直取 | **无** | `url`/`format`(markdown|text)/`maxChars`(默认 20000) |
| `Curl` | `src/main/scala/nebflow/core/tools/CurlTool.scala:11-165`（注册 `:29`） | 同上 | 调用方自带 header | `url`/`method`/`headers`/`body`/`timeout`，响应上限 10 万字符 |

**搜索细节**（`WebSearchTool.scala`）：

- 引擎表 `:90-99`：`360`（`https://www.so.com/s?q=`）、`DuckDuckGo`（`duckduckgo.com/html`）、`Sogou`、`Baidu`、`WeChat`（搜狗微信）、`arXiv`（Atom API）、`Crossref`（JSON API）。
- 排序 `:87-88` + `:108-113`：中文/英文查询分两组前缀序，`ANTI_BOT_PRONE = {Sogou, Baidu}`（`:81`）沉底。
- 赛跑语义 `:496-535` `raceFirstSuccess`：批内并发、**首个成功者胜、失败不取消同伴**（注释 `:486-495` 记录 2026-08-22 搜狗反爬页"毒死同伴"的旧缺陷）；批大小 3（`:15`），单引擎超时 10s（`:13`）。
- 反爬垃圾过滤 `:29-56`：`checksnuid`/`document.cookie`/`window.location`/`location.href`/`antispider` 命中即丢弃该条；整批全空 → 该引擎记失败（`:281-289` 分类为 anti-bot blocked / JS-rendered / no parseable results）。
- 结果解析全是正则：DDG `:194-213`、Bing `:216-233`、Baidu `:236-253`、通用 `<a>` 兜底 `:256-271`。
- 出处标注 `:67-70`：Tier 3 结果自带 "non-guaranteed — keyless HTML scraping, no SLA"。

**搜索的四层路由**（`src/main/scala/nebflow/llm/SearchProviderResolver.scala`）：

| Tier | 机制 | 位置 | 现状 |
|---|---|---|---|
| 1 | 用户装的搜索 MCP 工具 | 注释 `:77-79` | **未接线**（"P1 — not wired yet"） |
| 2a | 独立搜索 API（智谱 `POST /api/paas/v4/web_search`，按次计费） | `:58-62`、`:374-415` | 已实现；配置读 `nebflow.json` `search` 块（`shared/config.scala:245-252`：`provider/apiKey/engine/baseUrl/enabled`），`apiKey` **明文存配置** |
| 2b | 模型内置搜索（zhipu `web_search` 工具 / qwen `enable_search` / kimi `$web_search` 回环） | `:16-37`、`:454-539` | 已实现；无搜索证据则降级（`:542-547` 反幻觉判据） |
| 3 | 上述 `WebSearchTool` 多引擎聚合 | `WebSearchTool.scala` | 兜底，标注非保证 |

**这意味着新插件不需要从零造搜索**：Tier 3 的"多引擎赛跑 + 粒度反爬过滤"已是可用底座，真正缺的是**每条结果的结构化落库**（现在只有格式化文本，`:588-595`）。

### 1.2 抓取：HTTP 腿能干什么、浏览器腿为什么是空的

`WebFetchTool.scala` 全文只有两层：

- **第 1 层 HTTP**（`:162-250`）：30s 超时（`:14`）、750KB 体积闸（`:16`）、5 分钟 LRU 100 条（`:18-19`）、`followRedirects` ≤5 跳（`:173-175`）；403 或挑战页标题（`:82-85` "just a moment"/"请稍候"/"access denied"）→ 转第 2 层（`:189-191`、`:214-220`）。
- **HTML→文本（`:89-125`）**：纯正则——去 `script/style/nav/header/footer/aside/comment`，取 `main`>`article`>`body`，块级标签换行，剥标签 + 六个实体反转义。**没有 DOM、没有 Readability 打分、没有正文密度启发**。
- **第 2 层浏览器**（`:254-268` → `shared/BrowserManager.scala:94-114`）：Obscura（`which obscura`，`:56-66`；CLI `obscura fetch <url> --stealth --dump markdown`，`:73-89`）→ Playwright（`shared/PlaywrightFetcher.scala`）→ 两者皆无则 `Unavailable`。

**结论：第 2 层在本产品发行形态下不可用**，两条独立证据：

1. `build.sbt:108` 把 `playwright` 声明为 `provided`（版本 `project/Dependencies.scala:68-69` = 1.52.0）⇒ **不随发行包分发**；`BrowserManager.scala:42-51` 用 `Class.forName` 探测，缺 JAR 即静默降级为"Obscura-only"。
2. 仓内文档已明文登记：`src/main/resources/seed/plugins/browser-use/skills/control-browser/references/overview.md:36`——"**回退层在本产品当前发行形态不可用** …… 实际只剩第 1 层（HTTP 直取）可用；挑战页取不到正文 ⇒ 如实记为能力缺口"。
   而 `WebFetchTool.scala:42-44` 的工具描述仍写着 "falls back to Obscura or Playwright (if available)" —— **描述与事实的落差本身就是一处待修的口径债**。

本机实测（2026-11-02，本 delegate 会话）：

| 探测项 | 结果 | 影响 |
|---|---|---|
| `which obscura` | 命令不存在 / 不在 PATH | `BrowserManager.scala:58` 用 `ProcessBuilder("which", …)`——**Windows 上无 `which`**，异常被 `catch` 吞掉 ⇒ 该探测在 Windows 恒为 None |
| `node_modules/playwright` | 不存在（`package.json` 只有 esbuild/typescript/ws） | browser-use 的交互腿（Bash + node 脚本）在本机也跑不起来 |
| `~/AppData/Local/ms-playwright` | 不存在 | 无浏览器二进制 |
| 系统 Chrome | 默认路径无 | `PlaywrightFetcher.scala:49-60` 只探测 macOS/Linux/Windows 标准路径的三档 |
| `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe` | **在** | 现有探测列表不含 Edge ⇒ 明明有 Chromium 内核却用不上 |
| `python3`（PATH） | `WindowsApps\python3.exe` → 商店重定向器，执行报 "Python was not found" | 任何 `"command": "python3"` 的 stdio MCP 插件（含 `ptc-python/mcp.json`）在本机**起不来**；真解释器只有 `C:\Users\...\Python312\python.exe` |

网络面实测（`curl -m 8`，直连）：`github.com` 200/1.8s、`douban.com` 200/0.3s、`bing.com` 302、`zhihu.com` 302（登录墙）、`weibo.com` 302、**`html.duckduckgo.com` 超时**、`api.crossref.org` 302/3.4s。
但同一时刻 `WebFetch`（走 `SharedBackend` 的 JVM 代理选择器，`shared/http.scala:23-30`）取 `github.com` **连接超时**——**两条出网路径行为不同**，这解释了 `WebSearchTool.scala:83-87` 注释里 "DDG is reachable through the platform proxy" 的由来。新插件的抓取腿必须明确选一条路径并在证据里记下"哪条腿、什么时间"。

### 1.3 插件体系能承载什么形态

**结构面**（`core/plugin/PluginRegistry.scala`）：

- manifest 闭合 schema：`$schema/name/version/description/capability/author/homepage/repository/license/keywords/useWhen/extensions`（`:133-146`）；**未知顶层键 = 宽容忽略 + 告警**（`:695-696`；`mcp.json` 侧同语义 `:1007-1011`）。
- 装载闸：`skills/` 与 `mcp.json` **至少其一**（`:896-899`）；名字以 `nebflow-` 开头者必须命中官方 digest 允许表（`OfficialPackages.admits`，`:912-913`）。
- 包根已知条目：`plugin.json / mcp.json / skills / org.nebflow / Plugin.md`（`:661`），其余条目告警忽略。

**mcp.json**（`:993-1175`）：

- 顶层只认 `$schema` + `mcpServers`（`:1007-1016`），`$schema` 必须是 canonical 1.0.0，否则**整个 MCP 组件作废**（插件其余部分照装）。
- 单 server entry 字段白名单 **恰为** `{type, command, args, env, cwd, url, headers}`（`:1061-1064`）——**`timeoutMs` 不在白名单**：它只会产出一条 "unknown field ignored" 告警，随后 `timeoutMs = None` 被硬编码写入（stdio `:1136`、http `:1168`）。字段值 `None` 在 `McpManager.scala:82-84` 的语义是 **unbounded**（无限等待）⇒ 长任务不会被引擎叫停，也**不会**被引擎保护。
- `type` ∈ `stdio | streamable-http | sse`；`sse` 声明合法但本客户端不支持，**整条 entry 跳过 + 告警**（`:1148-1157`）。
- `command` 必须是**单 token**（有空白即拒，`:1072-1075`）——`"python3 x.py"` 这种写法非法，脚本必须走 `args`；`./` 相对命令按插件根解析（`:1076-1082`）。
- `env` 支持 `${secret:<name>}` 指针（`:1100-1108` + `PluginMcpManager.scala:246-277`），值**永不落 mcp.json/日志**，缺失 fail-closed（启动失败回滚，`PluginMcpManager.scala:71-98`）；`PLUGIN_ROOT`/`PLUGIN_DATA` 占位符由装载期展开，`cwd` 缺省 = 插件根。
- 生命周期：按会话**引用计数**（`PluginMcpManager.scala:44-102`），启动超时 15s（`:66`），启动失败 = 节点失败（不静默跳过）；被封禁的包在飞 server ≤30s 内停用（`:160-198`）。

**"工具包"与插件的关系**：仓内已有 `web-search-toolkit` / `browser-use` / `explorer-toolkit` 这类"**方法 + 授权**包"（`Plugin.md` + `skills/`，`org.nebflow/tools.json` 只授内建三件 `WebSearch/WebFetch/Curl`，白名单 `PluginRegistry.scala:104`）。它们**不含实现**。要新增实现只有一个合法通道：**自带 MCP server**（`mcp.json`），因为内建工具面是封闭白名单。

**没有的东西**（逐条确认）：

| 需不需要 | 现在有没有 | 证据 |
|---|---|---|
| 外部依赖声明（Python/Playwright/独立二进制） | **无机制**。manifest 无 `dependencies` 键（未知键忽略），`extensions` 是唯一逃生口，但只消费 `org.nebflow/tools` 与 `org.nebflow/viewers`（`:799-875`） | 只能写进 `Plugin.md`/`SKILL.md` 文字 + 运行期自检（PTC 的 `PTC_RUNTIME_UNAVAILABLE` 就是这套） |
| 插件网络访问策略 | **引擎侧零策略**。插件 MCP 就是宿主子进程，无沙箱、无出站闸 | `core/plugin/` 全目录无 allowlist/egress 实现；对照 Rust 侧的 `cap/crates/netfetch/src/policy.rs:41-104`（https-only/仅公网/DNS 钉死）**是另一条线的事** |
| "联网一律拒" | **PTC 自己的政策**，不是引擎的 | `plugins/ptc-python/skills/ptc-python/scripts/ptc_runner.py:140-168`（审计钩子拦 `socket.connect` 等，抛 `PTC_POLICY_NETWORK`） |
| 插件如何自检依赖 | 只能在 skill 里写自检 | `browser-use/.../control-browser/SKILL.md:34-36`（`node_modules/playwright` 缺失即停） |

**本机可装性判断**：一个"Python + Playwright"的插件**能装**（mcp.json 合法），但会踩三个现实坑——`python3` 是商店占位符（本机实测）、浏览器二进制要另装 100-300MB、`timeoutMs` 声明无效（长抓取无引擎兜底）。⇒ **第一版不要依赖 Python 解释器在 PATH 上**，用 `./bin/xxx` 相对命令包装或直接走 Node（本机 node 可用）。

### 1.4 PTC 承载面（调查循环的天然宿主，但有硬约束）

- 插件面：`ptc_run`（只读档）/ `ptc_run_rw`（读写档），`plugins/ptc-python/plugin.json` `extensions.ptc` 声明 `contract: ptc/v1`、`profiles.readonly.tools = [read, glob, grep, stat]`、`readwrite += [write, edit, remove]`、`tool_servers[0] = {id: fs, stdio, command: python3, args: [ptc_tools_fs.py]}`、`limits{default_timeout_ms 30000, max 300000, max_tool_calls 64, max_output_bytes 65536}`、`workspace.fs_roots = ["${PLUGIN_DATA}/workspace"]`、`policy{network: deny, subprocess: deny, env: scrubbed}`。
- 代码里的绑定：`tools.<name>(args)` 由 `_Tools.__getattr__` 动态派发（`ptc_runner.py:109-135`）⇒ **新增工具名不需要改 runner**，只需工具服务器真的实现了它 + 档位允许。
- 请求契约：`contract/ptc-request.schema.json`——`allowed_tools` 只能取"档位天花板"的子集（执行器取交集，永不放大）；`fs_roots` 必须是 manifest `workspace.fs_roots` 的子集。
- **硬约束 A（本轮最要命）**：`ptc_executor.py:396` 是 `ToolServer(tool_servers()[0], …)` —— **只起第一个工具服务器**。要加 `tools.search/fetch/...` 而**不改引擎、不改 PTC 代码**，唯一做法是把新工具**合并进现有那一个 fs server 进程**（或另起一个 server 并让它成为 `[0]`，代价是丢掉 fs 面）。
- **硬约束 B**：`mcp.json` 里那句 `"timeoutMs": 600000` **被装载器丢弃**（`PluginRegistry.scala:1129-1138`）⇒ 宿主侧 MCP 调用无上限，PTC 的实际时间闸只有自己的 `timeout_ms`（默认 30s，上限 300s，`limits`）。
- **硬约束 C**：`fs_roots` 只有 `${PLUGIN_DATA}/workspace`（manifest 与请求 schema 双重限制）⇒ PTC 脚本**读不到项目工作区**，调查产物只能落在插件数据目录。
- **既定待修缺陷（作者告知，本轮未复验，按约束处理）**：① 60 秒分配超时 + fail-open 静默降级；② `timeoutMs` 被丢；③ `fs_roots` 与会话工作区不一致。⇒ 设计上必须假设"PT C 可能静默失败"，用**回执自证**（`tool_calls[]` 台账条数 = 脚本预期）+ 单轮预算 ≪ 60s 来兜。

### 1.5 社交识别面：现在到底有什么（字段级）

**NebLink（宿主引擎侧，跨仓服务 `neblink-server`）**

| 面 | 字段 | 出处 |
|---|---|---|
| 好友 | `userId, username, displayName, avatar, since, blocked, remark` | `shared/PeerModels.scala:130-138` |
| 好友请求 | `requestId, from(FriendSummary), note, createdAt` | `:144-149` |
| 好友消息 | `GET /api/friends/{uid}/messages` / 发送 | `neblink/NeblinkClient.scala:675`、`:776`、`:824` |
| 群会话 | `groupId, title, role, memberCount, unreadCount, lastMessageId, createdAt, selfUserId` | `:186-195`；列表 `NeblinkClient.scala:723` |
| **群成员表** | **客户端无解码**——`GET /api/groups/{id}/members` 只作为前端透传（`gateway/SocialRoutes.scala:949` `groupProxy`），宿主侧拿不到结构化成名册 | `NeblinkClient.scala` 全文无 members 方法 |
| 模型可见工具 | `ListFriends`（Nebula 专属、零参数，`core/tools/registry.scala:109`）、`SendMessage`（`FriendMessageTool`，支持 `friend`/`group:`/设备寻址） | 寻址解析链 `neblink/FriendRoster.scala:75-160`（备注 → username → displayName → 前缀） |

**飞书桥（入站，最强的结构化关系信号）**

`social/FeishuMessage.scala:64-78` `Inbound(eventId, messageId, chatId, messageType, contentRaw, text, createTime, senderId, parentId, rootId, mentions[], parsed)`；
`social/InboundParse.scala:46` `Mention(key, name, id)`，其中 `id` 是 `user_id ?? open_id ?? union_id` 的归一化解析结果。
另有成员白名单闸（`FeishuBridgePlugin.scala:288-300`：非允许列表成员的群消息**直接丢弃并留日志**）——这本身就是一份**已存在的人际关系边表**（谁能对谁说话）。

**微信 / iLink 侧车**：`WeixinIlinkBridgePlugin.scala:1959-1978` `Inbound(senderId, text, messageId, mediaPath, mediaKind, mediaName, mediaMime, mediaError, mediaExtra)`——**只有 senderId，没有昵称、没有联系人表、没有群名册**。群聊维度读不出来。

**Rust 线 linkd**（`triple-star/cap/crates/linkd/`）：能力表 `src/lib.rs:29-59` 含 `friend.list / friend.message.send / conversation.list / conversation.messages / social.feishu.connection / social.ingress / social.bridge.poll / social.send`；`friend.list`（`:1262-1274`）**原样透出 friends.json 里的对象**（不做身份富化），`conversation.list`（`:1276-1340`）只给 `friend:<id>` / `dev:<id>` 两类会话，`conversation.messages`（`:1382-1400`）支持 `after`+`limit≤200` 增量拉取。⇒ Rust 侧是**消息数据面**，社交身份面仍在 Scala 侧。

### 1.6 Rust 线 SC-04「浏览器渲染」到底用了什么

`triple-star/cap/crates/netfetch/`（3644 行）已把 scala 三件的行为抄齐：

- **浏览器腿**（`src/browser.rs`）：触发面 = "静态取数的 HTML 标题命中挑战页特征串"（`:9-11`，与 `WebFetchTool.scala:82-85` 同四条）；阶梯 = **Obscura CLI**（`:236-259`，命令行与 scala 一致）→ **系统 Chrome `--dump-dom`**（`:184-233`，`--headless --disable-gpu --no-sandbox --virtual-time-budget=<ms> --dump-dom`，每次独立 `--user-data-dir`）→ `Unavailable`；超时 = `max_wait + 15s` 硬顶（`:37`、`:159-164`）；进程探测 `NF_OBSCURA_BIN`/`NF_BROWSER_BIN` 覆盖优先、否则扫 PATH（`:40-106`）。
- **有意差异两条**（`:22-28`）：不移植 Playwright 的 stealth 注入（`--dump-dom` 没有可注入的页面上下文）；不移植上下文复用（每次一进程，无跨站状态）。
- **HTML 解析**：`src/html.rs`（504 行）自己写的 `render()`——DOM→Markdown，**可复用**，但不是"正文抽取算法"（无 Readability 打分）。
- **出站策略**：`src/policy.rs:41-104`——仅 https、仅公网（回环/私网/链路本地/CGNAT/保留段全拒）、DNS 一次钉死防重绑定、重定向 ≤5、响应头 ≤64KiB。**这套 deny 名单是本仓现成的、可直接照搬的策略母本**（插件侧要自己实现，因为引擎不给插件策略）。
- **检索引擎**（`src/search.rs:45-60`）：只有 `duckduckgo`（html 端点）与 `bing`（`format=rss` RSS/XML）两家，同样是 keyless 抓取（`:1-12` 顶部注释写明了这套降级口径与"`max_results` 实为字符上限"）。**比 Scala 侧少 5 家**（360/搜狗/百度/微信/arXiv/Crossref），覆盖率不如 Scala 线。

⇒ **可复用清单**：`html.rs` 的 DOM→Markdown（若将来走 Rust 面）、`policy.rs` 的出站 deny 名单、`browser.rs` 的"Chrome `--dump-dom` 一进程一页"实现（比自研 CDP 便宜）。**不可复用**：搜索覆盖、任何"正文抽取/自适应选择器"能力（两边都没有）。

### 1.7 缺口小结

| 用例需要的能力 | 现状 | 差多少 |
|---|---|---|
| 多源检索（广度） | 7 引擎 keyless 赛跑，**文本输出** | 缺：结构化结果（title/url/snippet/时间/引擎）落库；缺垂直源（社交/RSS/论坛） |
| 正文抓取（深度） | HTTP 直取 + 正则去标签 | 缺：正文抽取质量、JS 渲染、重试/限速、编码/反爬处理 |
| 建模 | **无** | 全缺 |
| 证据/出处 | **无**（只有"Search source: xxx"一行） | 全缺 |
| 缺口发现 | **无** | 全缺 |
| 社交身份 | 好友/群/入站消息字段齐（群成员缺） | 缺：把"人"和"公开网络账号"连起来的任何东西 |

---

## 2. 外部方案对比

### 2.1 Scrapling（Python，v0.5）

- **定位**：一个库吃掉整个抓取工作流——HTTP 请求、隐身浏览器、解析、并发爬虫、代理轮换、导出。
- **核心机制**（官方 PyPI 页与 docs 首页）：`Fetcher`（模拟浏览器 TLS 指纹/头/HTTP3 的快速 HTTP）、`StealthyFetcher`/`StealthySession`（Playwright 的 Chromium 或 Google Chrome 做反检测，`solve_cloudflare=True` 解 Turnstile/Interstitial）、`FetcherSession`/`StealthySession`（cookie/状态复用）、`ProxyRotator`、`Spider` API（并发/暂停续跑/AutoThrottle/robots.txt/导出）。
- **"自适应选择器"的真身**：官方措辞是 *Smart Element Tracking — relocate elements after website changes using intelligent similarity algorithms*，外加 *Find Similar Elements* 与 *Auto Selector Generation*。即：元素定位不是硬编码 CSS，而是"指纹（标签路径/属性/文本相似度）在页面结构变动后重新定位"。**内部算法细节官方未展开，本轮未核实实现**——不要在设计里假设"永不失效"。
- **依赖与成本**：Python 3.10+；浏览器腿需要 Chromium/Chrome 二进制（官方提供含浏览器的 Docker 镜像，也可 `executable_path` 指自己的 Chromium；`cdp_url` 可连远端浏览器）。⇒ **一次装包 + 一次装浏览器 ≈ 300MB 量级**（未核实具体体积）。
- **Windows/WSL**：纯 Python，Playwright 官方支持 Windows；本轮**未实测**在 Windows 上跑通（本机无浏览器二进制、`python3` 还是商店占位符）。
- **对我们的价值**：三点可借——① 分层 fetcher（HTTP 优先、隐身腿兜底）与我们现有 `WebFetch` 同构，替换成本低；② selector 指纹缓存 = 我们要的"每域规则 + 运行时回退"；③ 自带 MCP server + Agent Skill，形态与我们插件体系兼容。
  **不建议**直接把它当插件依赖（一个 300MB 的 Python 依赖装进 stdio MCP 的子进程里，冷启动与发布面都不划算）——**借机制，不借包**。

### 2.2 Agent Reach（真实项目，本轮已核实存在并读到 README）

仓库：`Panniantong/agent-reach`（镜像 `web3-ops/Agent-Reach`，MIT，Python 3.10+）。中文媒体口中的"给 OpenClaw 一键装上全网搜索"就是它。

- **定位**：**脚手架，不是框架**（README 原话）。它不自己实现抓取，而是把每个平台的**上游 CLI 工具**装好、配好、写好 `SKILL.md`，让 agent 直接调上游。
- **渠道表**（README「支持的平台」+「设计理念」的 `channels/` 目录）：网页→Jina Reader、YouTube/B站→yt-dlp、RSS→feedparser、全网语义搜索→**Exa（经 mcporter MCP，免费无 key）**、GitHub→gh CLI、Twitter→twitter-cli、Reddit→rdt-cli、小红书→xhs-cli、抖音→douyin-mcp-server、LinkedIn→linkedin-scraper-mcp、微信公众号→Exa+Camoufox。
- **凭据模型**：Cookie/Token 只落本机 `~/.agent-reach/config.yaml`（600 权限），明确提示"Cookie 登录的平台用**专用小号**，有封号风险"。
- **可运维性**：`agent-reach doctor` 一条命令报每个渠道通/不通/怎么修；`install --safe`/`--dry-run`；每个 channel 是一个独立 py 文件，**可整块替换**。
- **对我们的价值**：这是目前看到的**最接近"多信息源接入层"的成熟形态**，且它的抽象（`channel.check()` + 上游工具 + doctor）可以直接映射成我们的"渠道表 + 健康检查 + 证据里记渠道名"。
  **但**它的搜索腿是 Exa（付费/额度）、社交腿依赖 Cookie 与第三方 CLI 的存活——**与我们"不绕登录、不打码"的边界直接冲突**，只能借"渠道注册 + doctor"这套骨架，不能照搬渠道清单。

### 2.3 搜索/抓取 API 三家（结论式）

| | 形态 | 对我们有用的点 | 代价/风险 |
|---|---|---|---|
| **Tavily** | `POST /api/tavily.com/search`，Bearer key；`search_depth`(basic/advanced)、`max_results`、`include_raw_content`、`include_domains`/`exclude_domains`、`time_range`/`start_date`/`end_date`、`include_answer`（接口面本轮已核） | 一次调用同时给"结果 + 正文"，`include_domains` 正好对上"权威源优先"的调查纪律 | 要 key、按量计费（价目本轮**未核实**）；中文源覆盖未知 |
| **Firecrawl** | `POST /api.firecrawl.dev/v2/search`，`scrapeOptions.formats=['markdown']` 可在**同一次调用**里拿到每条结果的整页 markdown（本轮已核文档）；无 key 可起步、有 key 提额度 | "搜索+抓正文"合并，正好是我们机械段的第一步 | 同上；自托管成本高 |
| **Exa** | 语义/神经搜索，Agent Reach 用作免 key 搜索腿 | 线索召回质量高（尤其长尾） | 文档页本轮**未能核实**（响应体超出抓取上限）；额度与稳定性未知 |

**结论**：三家解决的是"检索质量/正文可得性"，**都不解决**"建模/证据/缺口"——即我们要做的循环，外部没有现成件。

### 2.4 对比结论

- 抓取引擎：**Scrapling 机制可借（分层 + selector 指纹），包不建议引**。
- 渠道编排：**Agent Reach 的"渠道注册 + doctor"骨架可借**，渠道清单不可照搬（合规红线）。
- 检索后端：**现有 7 引擎 keyless 赛跑够用**；商业 API 作为"权威源定向检索"的可选增强（配置化，缺省关）。
- 建模/证据/缺口：**外部无现成件，必须自建**——这也是本插件的真正价值所在。

---

## 3. 循环骨架与数据模型

### 3.1 一个"调查轮次"的五段

```
round(n):
  ① 生成检索式   ← 来自：原问题 + 上一轮缺口清单（规则产出候选，模型挑选/改写）
  ② 检索         → 候选 URL（多引擎/多源，去重，记引擎与时间）
  ③ 抓取 + 抽取  → 正文 + 结构化字段 + 每个字段的原文片段（quote）
  ④ 入库/建边    → evidence 表 append；entity/edge 表投影更新
  ⑤ 评估         → 规则算缺口 + 冲突 + 置信度；产出 round report（增量图 + 缺口 + 冲突）
  → 若 缺口非空 且 round < 上限：进 round(n+1)；否则 ⑥ 出结论报告
```

关键设计取舍：**②③④ 全在脚本里跑完**（一次进程、一个出口），⑤ 也是纯代码（不含 LLM），只有"①选缺口"和"⑥写结论"回到模型。

### 3.2 三种数据结构（可并存，但要定主次）

| 结构 | 一行/一节点是什么 | 直接支撑的问题 | 弱点 |
|---|---|---|---|
| **证据表**（evidence，append-only） | 一条"某 URL 在某时刻的某段原文"：`evidence_id, url, fetched_at, http_status, engine, extractor, quote(≤300字), quote_hash, page_hash, entity_ids[]` | 一切结论的回溯；"这句话到底哪来的" | 不直接表达关系，要现算 |
| **实体-关系表**（graph projection） | 节点 `person/org/account/domain/event/url`（带 `attrs{}`）；边 `co_mention / cross_link / same_org / follows / timeline_overlap / owns`（带 `evidence_ids[]`、`confidence`） | "这人和那人什么关系""他还有哪些账号" | 边是**推断**，没有证据就是幻觉温床 |
| **时间线**（events） | `event(ts, actor, action, target, evidence_id)` | 时间线交集、先/后关系（"这两人 2023 在同一家公司"） | 覆盖稀薄，公开源常缺时点 |

**推荐主次**：**证据表是唯一真相源，图和时间线都是它的投影**（可以从证据表重建）。这样"删掉一条证据 → 相关边自动重算/降级"，避免图里长出无源之边。

落地形态（第一版）：单文件 **SQLite**（`${PLUGIN_DATA}/inv/<case_id>.db`，三张表 + 一张 round 日志），或极简 JSONL ×3 文件。选 SQLite 的理由是"按 entity_id 反查证据""按 predicate 找冲突"这类查询会立刻用到；JSONL 只适合一天的原型。

### 3.3 "发现缺口"怎么触发下一轮：两种做法与代价

| | 规则派（代码算） | 模型派（喂图给模型问） |
|---|---|---|
| 输入 | 图 + 证据表 + 用例模板 | 上述的压缩摘要（≤4k token）+ 原问题 |
| 能发现 | **结构性缺口**：①某实体关键字段为空（无 avatar/handle/bio）②某条 high-confidence 边只有 1 条证据 ③同字段两值冲突 ④某候选账号从未被抓过 ⑤某域名出现在 bio 里但未被抓 | **语义缺口**：还缺哪一环证据才能闭环、什么假设值得验 |
| 成本 | **0 token**，毫秒级，确定性可复现 | 每轮 1 次轻量调用（2-4k token，1-3s） |
| 缺点 | 发现不了"我们还不知道该问什么"；规则写多了会僵 | 会发散/重复已抓过的 URL；不可复现（同一图两次跑出不同查询） |
| 风险 | 无 | **幻觉缺口**（编一个不存在的疑点，然后编证据去填） |

**推荐：混合，且硬性划界**——规则产出**带类型的缺口候选**（每条形如 `{gap_id, kind, entity_id|edge_id, why, suggested_seeds[]}`），模型**只能在候选里选 + 改写检索式**（不许新增 kind 之外的缺口），选完的空缺记回证据表。这样既有语义灵活性，又保证每轮动作都能追溯到"哪条规则觉得缺"。

代价粗估（10 轮以内的调查任务）：纯模型主导 ≈ 每轮 3-8 次工具调用 × 每调用一次完整往返；混合后**LLM 往返次数从 O(步数) 降到 O(轮数)**（约 1/5 ~ 1/10 token），且每轮的机械段可以重跑而不再烧 token。

### 3.4 一轮的产出契约（round report）

```json
{
  "round": 2, "case_id": "...", "started_at": "...", "elapsed_ms": 18400,
  "queries": [{"q": "...", "engine": "360", "hits": 9}],
  "fetches": [{"url": "...", "status": 200, "bytes": 81234, "extractor": "generic_v1"}],
  "new_findings": {"entities": 4, "edges": 3, "evidence": 11},
  "conflicts": [{"predicate": "employer", "entity": "P1", "values": ["A 公司","B 公司"], "evidence_ids": ["e12","e31"]}],
  "gaps": [{"gap_id": "g7", "kind": "edge_single_source", "edge": "E-12", "suggested_seeds": ["https://..."]}],
  "tool_calls": 17, "notes": []
}
```

`tool_calls` 是**回执自证**字段：PTC 有 fail-open 静默降级的既定缺陷，脚本必须以"我预期调了 N 次工具"对账，不一致就在报告里置 `"degraded": true`，让模型和用户都看得见，而不是假装成功。

---

## 4. 证据与可信度（成败关键）

### 4.1 三道机械护栏（不依赖模型自觉，代码可判）

1. **quote 必须是原文子串**：抽取器返回的每个字段都带 `quote`，入库前做 `quote in page_text`（规范化空白后）校验；**不是子串就直接丢弃该 claim**（并计入 `rejected_claims`）。这一条把"模型脑补字段"变成**结构上不可能**——因为抽取器是代码，模型根本没机会往里写。
2. **结论必须挂 evidence_ids**：报告里每条 fact 必须引用 ≥1 个真实存在的 `evidence_id`；引用不存在的 id ⇒ 报告校验失败、该条降级为 `inferred`（C0）。
3. **抽取器只搬不造**：字段值只从三类确定性来源取——页面 `<title>/<meta>/OG/JSON-LD`、可见文本正则（邮箱/URL/handle 模式）、结构化 API（GitHub 等公开 API 返回的字段）。**没有任何一步"让模型从正文里总结一个值"**。

### 4.2 置信度分级（每条结论必须带级）

| 级 | 判据（代码可判） | 允许出现在 |
|---|---|---|
| **C3** | 一手来源自述 + 2 条独立证据（如本人主页 + 官方 API） | 结论正文 |
| **C2** | ≥2 个**互不复制**的来源一致（不同注册域，或不同站点各自独立发布） | 结论正文 |
| **C1** | 单一来源 | 结论正文，但必须标 "single source" |
| **C0** | 无来源 / 引用不成立 / 只有模型推断 | **只能进"待验证假设"栏**，禁止进事实栏 |

"互不复制"的机械判据：不同 registrable domain + 正文相似度低于阈值（Shingle 或简单 8-gram Jaccard）；同一新闻稿被转载十次**仍算 1 个来源**。

### 4.3 冲突怎么表示（建议）

**不合并、不投票、不静默取一个**。属性按 `(entity_id, predicate, value, evidence_ids[], confidence, first_seen)` **多行并存**，报告里并列输出：

```
雇主：A 公司（C2，2 源：ev-12/(2024-03 官网) · ev-31/(2025-01 新闻)）
      B 公司（C1，1 源：ev-44/(2023-08 招聘页)）   ⚠ 未解冲突
→ 核验查询（自动生成）："<name> A公司 B公司 离职/入职 时间"
```

冲突本身是**下一轮抓取的输入**，这是循环自洽的地方：冲突不消失，就说明"还缺一条把两者连起来的时间线证据"。

### 4.4 明确禁止（写成插件的能力边界，不是建议）

- 禁止"模型自由发挥填充"任何字段——没有 quote 就没有值。
- 禁止把"检索摘要（snippet）"当证据——snippet 只能当**线索**，必须抓到原文才入库（现有 `WebSearchTool` 的输出正是 snippet 级，这也是为什么新插件必须自带抓取腿，不能只包一层搜索）。
- 禁止把无源的"合理推断"写进事实栏（C0 只进假设栏）。
- 禁止隐去失败：抓不到的 URL、被反爬拦的页面、被 robots 拒的路径，全部作为 `gap` 记录并出现在报告"还缺什么"里。

---

## 5. 与 PTC 的集成点

### 5.1 为什么这个循环适合 PTC

机械段（检索→抓取→解析→入库→建边→算缺口）是**纯代码、可重放、无判断**；模型只在"选哪条缺口、判据够不够硬、结论怎么写"出手。走 PTC 的收益是数量级的：一次 `ptc_run` = 一次完整 LLM 往返 + 进程内跑完 N 步；不走 PTC 则是 N 次往返。而 PTC 的代价（每次调用要起进程、脚本得自己限时）在"一轮 30s 内"的量级上可以接受。

**注意反向约束**：PTC runner 内部**禁止联网**（`ptc_runner.py:140-168`，`PTC_POLICY_NETWORK`）。这不是障碍而是**正确的架构提示**——所有网络 I/O 必须发生在**工具服务器侧**（宿主 MCP 子进程，不受该策略约束），脚本只做 `tools.fetch(...)`。这条边界正好把"能联网的代码"与"跑模型代码的沙箱"分开，安全性优于把 HTTP 客户端放进 runner。

### 5.2 `tools.*` 需要扩哪些（最小集）

| 工具名 | 入参 | 出参 | 实现位置 | 备注 |
|---|---|---|---|---|
| `tools.search` | `query, engine?, max=10, domains?` | `[{title,url,snippet,engine,rank}]` | 工具服务器内复用引擎赛跑（可平移 `WebSearchTool` 的引擎表与赛跑语义） | 返回**结构化**条目，不是格式化文本 |
| `tools.fetch` | `url, timeout_s=8, render=false` | `{url, final_url, status, fetched_at, content_type, text, sha256, bytes}` | 工具服务器 | `render=true` 时走浏览器腿（v1 不支持，见 §6） |
| `tools.extract` | `text, url, schema?` | `{title, meta{}, links[], emails[], handles[], jsonld[], quotes{}}` | 工具服务器（纯本地、无网络） | 只搬原文 + 返回每个字段的 `quote` 与偏移 |
| `tools.evidence_add` | `rows[]` | `{inserted, rejected}` | 工具服务器（写 `${PLUGIN_DATA}`） | 内置 quote 子串校验，rejected 附原因 |
| `tools.graph_put` | `nodes[], edges[]` | `{nodes, edges, conflicts}` | 同上 | 边的 `evidence_ids` 必须已存在，否则拒 |
| `tools.gaps` | `case_id, ruleset` | `[{gap_id, kind, entity|edge, why, suggested_seeds}]` | 同上 | 规则派缺口 |
| `tools.report` | `case_id, format` | `{path, markdown}` | 同上 | 生成人读报告（findings + 假设 + 冲突 + 缺口） |

可选（第二版）：`tools.robots(url)`、`tools.ratelimit(key)`、`tools.dedupe(queries)`、`tools.timeline(case_id)`。

### 5.3 落地方式（受 `tool_servers[0]` 限制）

现状：`ptc_executor.py:396` 只吃 `tool_servers()[0]`，而现有 `[0]` 是 `fs`（read/write/edit/glob/grep/stat/remove）。

| 方案 | 做法 | 代价 |
|---|---|---|
| **甲（推荐）合并进同一个 server** | 把上面的工具**加进现有 `ptc_tools_fs.py` 那个进程**（或让一个 `ptc_tools_all.py` 同时承载 fs + net 两组），`extensions.ptc.tool_servers` 仍只有 1 条 | 零 PTC 代码改动；缺点是单进程职责变多、fs 与 net 的失败会互相影响（用分线程/分 try 隔离） |
| 乙 改 PTC 执行器支持多 server | `ptc_executor.py` 遍历全部 `tool_servers`，工具名全局唯一 | 要改 PTC 代码（本批红线外），且要处理重名与启动顺序 |
| 丙 只做净 server，牺牲 fs | 把 `[0]` 换成 net server | 丢掉读写本地面（调查产物落不了盘）⇒ 不可接受 |

档位声明同步：`profiles.readonly.tools` 追加 `search, fetch, extract, evidence_add, graph_put, gaps, report`（写的是**插件自己的数据目录**，不是用户文件系统，所以只读档放行是合理的；若作者认为"写=写"，则这些只能进 `readwrite`，代价是模型每次都得选 `ptc_run_rw`）。

### 5.4 循环边界：三选一

| 边界 | 形态 | 优点 | 缺点 |
|---|---|---|---|
| **A 模型主导** | 模型每步调一个工具（`search` → 看 → `fetch` ×3 → 看 → `evidence_add` …） | 零 PTC 依赖、可解释、随时纠偏 | N 步 = N 次完整往返；10 步 ≈ 10×(上下文+工具schema) token；慢且贵；纪律全靠提示词，最易"自由发挥" |
| **B 脚本主导** | 一次 `ptc_run` 脚本内自己循环到底（while gaps） | 最省 token、可重放 | 中途无法判断"这条线索值得追"；脚本要预判所有分支；一轮失败全盘重跑；单次执行时长容易撞 PTC 的 60s 分配超时 |
| **C 混合（推荐）** | **一轮 = 一次 `ptc_run`**：脚本跑完 ②③④⑤（检索/抓取/抽取/入库/缺口），回一份 round report；**模型看增量图 + 缺口候选，决定下一轮的检索式** | 往返次数 = 轮数（3 轮 = 3 次）；机械段可重跑不烧 token；每轮 ≤30s，天然规避 60s 超时；脚本不必预判分支 | 需要两份契约（round report + 缺口候选 schema）；模型决策粒度是"轮"不是"步" |

**推荐 C**，理由三条：
1. **对齐 PTC 已测的时间量级**——单轮 30s 上限，远低于 60s 分配超时线，也低于 MCP 层（因 `timeoutMs` 被丢而**无上限**）的失控风险。
2. **对齐成本结构**——检索/抓取/解析是确定性工作，交给模型只会烧 token 且不可复现；而"该追哪条线"是真正的判断，非模型不可。
3. **对齐证据纪律**——机械段的每一步都留机器可读台账（`tool_calls`/`fetches`），结论才有回执可核；模型主导形态下这些台账散落在几十条工具结果里，反而不可审计。

> 附带读数（说明"为什么不能在工具调用路径上按需起进程/浏览器"）：另一条线（Rust 执行面）实测 **进程内直调 0.23ms / 常驻 MCP 0.83ms / 每调用冷启 41.3ms**（`triple-star/docs/20261102_142212_exec-plane-tool-separation__delegate-nebflow-ed10797a.md:22`）。冷启比常驻贵 **≈50 倍**——所以浏览器/大依赖**绝不能**做成"每次工具调用起一个进程"，只能"常驻一个，或用一次调用批量做完 N 页"。

### 5.5 既定缺陷的规避设计（不假设它们已修）

| 缺陷 | 设计上的规避 |
|---|---|
| 60s 分配超时 | 单轮预算 **≤30s**（`timeout_ms: 20000` 显式传，脚本内部按 deadline 收尾）；轮数上限 3；超预算即返回"部分完成 + 缺口"，不重跑 |
| fail-open 静默降级 | 脚本自己算 `tool_calls` 预期值并写进 report；`round report.degraded` 为 true 时禁止出结论（只能出"本轮未完成"） |
| `timeoutMs` 被丢 | 不依赖 mcp.json；所有超时在**脚本内**（`requests` 级 timeout + deadline 检查） |
| `fs_roots` 与会话工作区不一致 | 调查产物一律落 `${PLUGIN_DATA}`；需要给用户看的报告再用 `Write`/`Pop` 由宿主侧搬到工作区（不走 PTC） |

---

## 6. 抗反爬与合规边界

### 6.1 站点三档（写死在插件里，不是提示词）

| 档 | 判据 | 手段 | 例 |
|---|---|---|---|
| **A 规则化** | 有公开结构化出口：JSON-LD / OG / 官方 API / RSS | 直接取结构化字段，几乎零解析成本、高置信 | GitHub（API）、Crossref（API）、RSS/Atom、豆瓣/知乎的 OG 标签 |
| **B 通用提取** | 静态 HTML 可读，但结构不稳定 | 通用正文启发（Readability 式块打分）+ 每域 selector 指纹缓存（首次自动生成、后续优先命中、miss 则回退通用并重建） | 新闻站、个人主页、公司官网 |
| **C 不碰** | 任何要求登录/付费/验证码/客户端渲染才出正文的 | **直接记 gap 并如实报告"取不到"，绝不尝试绕过** | 微博正文、小红书、公众号部分页、LinkedIn、需登录的论坛 |

判定实现要**代码化**（不是模型判断）：`robots.txt` 解析 + `login_wall` 启发式（表单含 password / 关键词 "登录后查看" / 401/403）+ 内容密度阈值。

### 6.2 Scrapling 那套"自适应选择器"在我们这里怎么落

**可行的落法**：不是"运行时万能启发式"，而是 **每域 selector 预案 + 指纹回退**：

1. 首次抓某域 → 用通用启发定位"标题/正文/作者/时间"候选，记录 `selector + 页结构指纹（DOM 标签序列的 simhash）` 进 `selectors(domain)` 表。
2. 后续抓该域 → 先按缓存的 selector 取；`quote` 取不到或长度异常 → 判 miss。
3. miss → 回退通用启发重新定位 + 更新缓存，并在 evidence 里记 `extractor_version`（可统计命中率）。
4. 连续 N 次 miss → 该域降级为"只走通用提取，不建预案"（避免维护幻觉）。

**可行边界（必须写进文档，防止过度承诺）**：
- 能对抗的：**结构微调**（class 改名、多包一层 div、位置挪动）——指纹相似度能救回来。
- 不能对抗的：整站改版、客户端渲染（正文根本不在初始 HTML 里）、A/B 实验分桶、反爬挑战页。
- 因此**不做"每站手写规则"的承诺**，只承诺三档判定 + 命中率可见。

### 6.3 红线（写进插件的能力边界）

1. **不绕过登录**：不用 Cookie 注入去读需登录的内容（与 Agent Reach 的 Cookie 路线相反，这是我们的取舍）。
2. **不打码**：不接任何验证码/人机校验的求解服务，遇到即记 gap。
3. **不做高频**：全局并发 ≤4，单域 ≤1 req/2s（缺省可调），单任务 ≤30 页；命中 429/403 即退避并记账。
4. **遵守 robots.txt**（缺省 true，可显式关但会在报告里留痕）；`Disallow` 命中记 gap，不静默跳过。
5. **不采集非公开信息**：只处理公开可访问页面；不做凭据填充、不扫内网、不查社工库。
6. **隐私面**：调查对象若是真人，报告只输出"公开可见信息的汇总与出处"，不推断敏感属性（健康/性向/政治/精确住址），命中此类内容不落库。

---

## 7. 候选架构（3 个，标推荐）

### 甲：工具面派（无 PTC）——最小、最快、最脆

一个 stdio MCP 插件出三件工具：`net_search / net_fetch / net_note`，证据落 `${PLUGIN_DATA}` 的 JSONL，模型自己多轮跑循环，纪律靠 `SKILL.md`。

- 好：**零 PTC 依赖**（不受那三条缺陷影响）；1 天可跑；任何会话都能挂。
- 坏：每步一次完整往返（10 步 ≈ 10 次），token 成本最高；循环是否收敛取决于模型自觉；证据纪律只能"建议"不能"强制"。
- 适用：验证"证据纪律写进提示词到底管不管用"。

### 乙：调查台（脚本主导 + 模型定调，**推荐**）

一个插件、**一个 MCP server 进程**同时承载两面：
- 面 1（直调）：`net_search / net_fetch`——给"就问一句/抓一页"的轻场景，不必起 PTC；
- 面 2（PTC）：`ptc_run` 的 `tools.*` 扩到 §5.2 那七件，脚本内跑完一轮机械段。

证据表（SQLite）为唯一真相源，图/时间线是投影；规则出缺口候选、模型在候选里选；报告由 `tools.report` 生成，每条结论带 evidence_id + 置信度。

- 好：往返次数从 O(步) 降到 O(轮)；机械段可重放、可审计；证据纪律**由代码强制**（quote 子串校验、evidence_ids 外键）；单轮 30s 天然规避 60s 超时；工具面与 PTC 面共用一份实现（不必写两遍）。
- 坏：依赖 PTC（三条缺陷要按 §5.5 绕）；`tool_servers[0]` 逼着把 net 工具塞进 fs 那个进程（职责变杂）；进程常驻意味着要管好它的生命周期（插件引用计数已覆盖）。
- 风险中低，收益最大。

### 丙：常驻调查服务（daemon）

独立进程常驻，持浏览器池 + 任务队列 + 证据库；MCP 只做控制面（`submit / status / report`），可跨小时跑批。

- 好：能跑长任务与批量（一批 100 个种子）；浏览器可常驻（避开冷启动 41.3ms/次）；多案例并行。
- 坏：**与插件模型不匹配**（插件按会话引用计数起停，daemon 要自己管守护/端口/日志）；运维面陡增；v1 完全没有必要。
- 结论：**留给 v3**（真有批量调查需求时再上）。

**推荐：乙。甲作为乙的第一天形态**（乙的面 1 就是甲，先把证据纪律用代码立起来，再挂 PTC 面）。

---

## 8. 最小可运行切片（必须是完整循环，1-2 天）

### 8.1 窄场景

> 输入：一个人名 + 一个已知账号（例：`{name: "张三", known: {site: "github", handle: "zhangsan"}}`）
> 输出：**这人还有哪些可能的公开账号**——每条带依据（URL + 抓取时间 + 原文片段）、置信度（C3/C2/C1/C0）、支持点/反证点、"还缺什么"。

### 8.2 三轮跑完（每轮 = 一次 `ptc_run`，≤30s）

| 轮 | 脚本内机械动作 | 出口 |
|---|---|---|
| **R1 已知面** | `fetch` 已知账号页（GitHub 用户 API + 主页）→ `extract`（displayName / bio / avatar_url / blog / company / 交叉链接）→ `evidence_add`(N 条) → `graph_put`（person + account + `owns` 边）→ `gaps` | round report：3-6 条证据 + 缺口候选（"bio 里的 blog 域名未抓"、"同名账号未验"、"avatar 未做哈希比对"） |
| **判定（模型，1 次轻量调用）** | 从缺口候选里选 2 条 + 改写检索式 | 下一轮的 `queries[]` |
| **R2 扩散** | `search` ×2 → 取 top3 URL → `fetch` → `extract`（找 handle 模式 / 交叉链接 / 同 avatar 哈希 / 同 bio 关键词）→ `evidence_add` → `graph_put`（候选账号 + `co_mention`/`cross_link` 边，边必带 evidence_ids）→ `gaps` | 候选账号表（每个带证据与 C 级）+ 冲突/单源标记 |
| **R3 补证** | 对 R2 的 top2 候选**专门抓其 profile 页**，比对 avatar 哈希 / bio 关键词 / 反向链接 → 升级或降级置信度 → 若证据仍不足，把该候选写进"待验证假设" | `report`：findings + assumptions + conflicts + gaps |

### 8.3 验收（跑通即过关）

1. **至少 1 条 C2 及以上结论**，且其 evidence_id 能顺着查到 URL + 抓取时间 + 原文片段（人工点开 URL 对得上）。
2. **至少 1 条被降级/推翻的假设**（证明"反证"链路真的在工作，不是只堆正面证据）。
3. **至少 1 处未解冲突**并列输出 + 自动生成的核验查询。
4. **至少 1 个 gap 是"取不到"**（登录墙/robots/超时），且在报告"还缺什么"里如实出现。
5. 三轮 `tool_calls` 台账与脚本预期一致（无 fail-open 静默）。

### 8.4 两天怎么分

- **D1**：MCP server（合并进 PTC `[0]` 进程）实现 `search/fetch/extract/evidence_add/graph_put/gaps` 六件 + SQLite 三表 + quote 子串校验；对 3 个真实站点跑通 fetch→extract→evidence。
- **D2**：一次 `ptc_run` 打通 R1→R2（含缺口规则集第一版）+ 判定点的提示词 + `tools.report` 出 markdown；用 2 个真实案例各跑一遍，核对 8.3 的五条验收。

> 切片刻意**不包含**：JS 渲染、多引擎扩展、图可视化、批量调查。JS 渲染留到 v2，且形态是"常驻浏览器"不是"每调用一起"（§5.4 的 41.3ms/0.83ms 读数）。

---

## 9. 原六问逐条答复（保留）

**Q1 搜索与抓取要不要拆两个插件？** —— **不拆包，拆面**。一个插件包里两个工具面（search / fetch），但**同一个进程**：两者共享 HTTP 客户端、限速器、selector 缓存、robots 判定与证据库；拆两个包会导致同一套限流与缓存跑两份、版本不同步、派发面翻倍。协议上"一插件多组件"是既有形态（`skills/` + `mcp.json` + `org.nebflow/tools.json`）。

**Q2 后端怎么选？** —— **混合**，三层：

| 层 | 选型 | 延迟 | 覆盖率 | 维护量 |
|---|---|---|---|---|
| 检索 | 现有 7 引擎 keyless 赛跑（免费） | 1-3s（实测 360 单页 467KB 可达） | 中文好、英文靠 DDG（需代理）、垂直源差 | 低（但随各家反爬漂移，已有垃圾指纹兜底） |
| 检索增强（可选） | 商业 API（Tavily/Firecrawl/Exa），配置化开关 | 1-2s | 高，且能按域名定向 | 低（付费换稳定） |
| 正文抓取 | **本地**（HTTP + 通用提取 + 每域 selector 指纹） | 0.2-2s/页 | 静态页 ≈ 全覆盖；JS 页 0 | 中（selector 会漂，靠命中率统计） |
| JS 渲染 | 暂不做（v2 常驻浏览器） | 常驻 ~0.8ms/页 vs 冷启 41ms/次起 | +JS 页 | 高（二进制 300MB、进程守护） |

自建全量爬虫（成本高、易被封）**不推荐**：我们要的是"针对一个问题的 30 页以内定向抓取"，不是索引整个网。

**Q3 JS 渲染要不要做？** —— **v1 不做**。理由：① 现有浏览器腿在发行形态不可用（§1.2），要做得先补 300MB 二进制与守护进程；② 已测"每调用冷启 41.3ms vs 常驻 0.83ms"意味着**按需启停在工具调用路径上不可接受**（一轮 10 页 = 410ms 纯冷启 + 每页真实渲染 1-3s）；③ 目标用例（找人/关系推断/关键词情报）的主要源（官网、新闻、GitHub、论坛、RSS）大多有静态 HTML。v2 形态 = **一个常驻 Chromium（CDP）**，且只在"正文为空/挑战页"时按需借用，并在证据里标 `rendered: true`。

**Q4 自适应选择器怎么落地？** —— 见 §6.2：**每域 selector 预案 + 结构指纹回退 + 通用提取兜底 + 命中率统计**；边界是"结构微调可救、整站改版/客户端渲染不可救"，不承诺"永不坏"。

**Q5 "社交识别"具体指什么？** —— 从代码现状出发有三种解释，代价从低到高：

| 解释 | 现状基础 | 实现代价 | 建议 |
|---|---|---|---|
| ①**识别网页上的社交账号/联系方式**（handle、邮箱、主页、交叉链接） | 零（现在只有 snippet 文本） | 低：正则 + 链接分类 + 平台白名单映射 | **首选**，直接服务"找人"与"关系推断" |
| ②**识别 NebLink/飞书内的社交关系** | 中：好友对象字段齐（`PeerModels.scala:130-138`）、飞书入站有 `chatId/senderId/mentions[].id`（`FeishuMessage.scala:64-78`）；**群成员表在宿主侧不可得** | 中：要么补 `NeblinkClient` 的 members 解码（一个方法），要么走前端透传面；涉隐私需用户授权 | 二期，作为"内部关系图"的私人数据源 |
| ③**抓公开社交平台内容**（微博/小红书/X） | 零 | **最高**：登录墙 + 反爬 + 封号风险 | **不做**（撞 §6.3 红线） |

作者需要在这三者间拍板——**我认为 ① 是必做的核心（它决定了 C2 级证据能不能成立），② 是差异化（别人没有的真实关系数据），③ 是纯风险**。

**Q6 最小切片** —— 见 §8（已升级为"能演示完整循环"的窄场景，不是"能搜到东西"）。

---

## 10. 未核实项与假设（诚实清单）

| 项 | 状态 |
|---|---|
| Scrapling 自适应选择器的**内部算法** | 官方只写"智能相似度算法"，**未核实**实现细节；本文只引用其对外承诺 |
| Scrapling / Playwright 在本机 Windows 上能否跑通 | **未实测**（本机无浏览器二进制、`python3` 为商店占位符）；仅确认依赖形态（Python 3.10+ / Chromium 或系统 Chrome / Docker 镜像含浏览器） |
| Exa 的接口面与额度 | **未核实**（文档页响应体超出抓取上限） |
| Tavily / Firecrawl 的价目 | **未核实**（只核实了接口参数形态） |
| Agent Reach 的渠道可用性（各 CLI 是否仍活） | 只读了 README（渠道表/凭据模型/doctor/可插拔目录结构），**未实装验证** |
| PTC 三条既定缺陷 | 按作者告知的**既定约束**处理（`timeoutMs` 丢弃已在本仓核实：`PluginRegistry.scala:1129-1138`；60s 分配超时与 fs_roots 不一致**本轮未复验**） |
| 群成员表在宿主侧可得性 | 已核实 `NeblinkClient` 无解码、仅有前端透传（`SocialRoutes.scala:949`）；**服务端 `neblink-server` 源码不在本仓**，其返回字段未核实 |
| 本机能出网的全貌 | 只测了 9 个域名（§1.2）；代理路径（JVM proxy selector）与直连行为**不一致**这一点已实测，但成因（代理规则集）未深挖 |

**明确假设**：① 新插件以"自带 MCP server"形态交付，不改引擎、不改 PTC 代码（乙方案靠"合并进 `[0]` 进程"实现）；② 商业搜索 API 属于可选增强，缺省关闭（作者未表达付费意愿）；③ 调查对象仅为公开信息，不涉登录态数据。

---

## 11. 出处索引

**仓内（本仓 `nebflow-stand`）**

- 搜索：`src/main/scala/nebflow/core/tools/WebSearchTool.scala:12-603`、`src/main/scala/nebflow/llm/SearchProviderResolver.scala:41-598`、`src/main/scala/nebflow/shared/config.scala:245-252`
- 抓取：`src/main/scala/nebflow/core/tools/WebFetchTool.scala:12-298`、`src/main/scala/nebflow/shared/BrowserManager.scala:34-122`、`src/main/scala/nebflow/shared/PlaywrightFetcher.scala:26-253`、`build.sbt:108`、`project/Dependencies.scala:68-69`
- 浏览器腿不可用的登记：`src/main/resources/seed/plugins/browser-use/skills/control-browser/references/overview.md:36`
- 工具注册表：`src/main/scala/nebflow/core/tools/registry.scala:13-203`
- 插件装载：`src/main/scala/nebflow/core/plugin/PluginRegistry.scala:104/133-146/661/993-1175`、`PluginMcpManager.scala:44-277`、`src/main/scala/nebflow/core/mcp/McpManager.scala:82-84`
- PTC：`plugins/ptc-python/plugin.json`、`plugins/ptc-python/mcp.json`、`plugins/ptc-python/skills/ptc-python/scripts/ptc_executor.py:396`、`.../ptc_runner.py:109-168`、`.../contract/ptc-request.schema.json`
- 社交：`src/main/scala/nebflow/shared/PeerModels.scala:130-195`、`src/main/scala/nebflow/neblink/NeblinkClient.scala:675/723/776/831`、`src/main/scala/nebflow/neblink/FriendRoster.scala:75-160`、`src/main/scala/nebflow/social/FeishuMessage.scala:64-78`、`.../InboundParse.scala:46`、`.../FeishuBridgePlugin.scala:288-300`、`.../WeixinIlinkBridgePlugin.scala:1959-1978`、`src/main/scala/nebflow/gateway/SocialRoutes.scala:949`

**仓外（`triple-star`，Rust 线）**

- `cap/crates/netfetch/src/browser.rs:1-272`（浏览器腿阶梯）、`search.rs:45-60`（两引擎）、`html.rs`（DOM→Markdown 504 行）、`policy.rs:41-104`（出站 deny 母本）
- `cap/crates/linkd/src/lib.rs:29-59`（能力表）/`:1262-1400`（friend/conversation 面）
- `docs/20261102_142212_exec-plane-tool-separation__delegate-nebflow-ed10797a.md:22`（0.23ms / 0.83ms / 41.3ms 三点读数）

**外部**

- Scrapling：`https://pypi.org/project/scrapling/`、`https://scrapling.readthedocs.io/en/latest/`（v0.5，2026-11-02 取）
- Agent Reach：`https://raw.githubusercontent.com/web3-ops/Agent-Reach/main/README.md`（同 `Panniantong/agent-reach`，2026-11-02 取）
- Firecrawl Search：`https://docs.firecrawl.dev/features/search`（2026-11-02 取）
- Tavily Search：`https://docs.tavily.com/documentation/api-reference/endpoint/search`（2026-11-02 取）
- Exa：`https://docs.exa.ai/reference/search`（**未取到**，超出抓取上限）

**相关文档**

- 本文的载体形态（一个插件包里同时挂 skills 与 MCP 工具面）沿用 [[glossary#^plugin-package]] 的定义与装载语义。
- PTC 面的现状与契约以 [[20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa]] 为准；插件协议面（manifest/skills/MCP/工具授予）以 [[design/20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]] 为准。
- 文档库入口见 [[index]]。
