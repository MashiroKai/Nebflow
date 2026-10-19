# 修复批 · 插件分配链两刀：密钥对齐 + 零超时化（作者四点拍板逐字落地）

日期 2026-10-19 · 分配链 jev 腿「通」的实测闭环 + 分配链 15s 超时拆除（不留配置位）
工作分支 `delegate/delegate-nebflow-b9a0937f` · 引擎运行构建 main@71452cd22（pid 39598，10-18 22:00 起）
原始读数快照见 `raw/`（keyref-alignment / mitm 引擎实测响应 ×2 / 引擎请求字节 / jev-allocation 尾窗）。

---

## ① 密钥对齐（jev.keyRef → 面板管的那把）

### 面板存储缝（代码事实）

- 设置页 Jev 区 `cfg-jev-keyref` 是**自由文本框**（`sidebar.js:884`，placeholder 恰是 `typesafe-api-key`），保存 = `PUT /api/config/jev` 原文直写 `jev.keyRef`（`ConfigService.setJevSection`，白名单键）。**面板不落 secret 文件**——面板里存的是什么，`nebflow.json` 里就是什么。
- 本部署实况：用户把**密钥值本身**贴进了面板 → `jev.keyRef` 存的是 `apikey_286bb7bb…`（107 字符原始值）。读面（`JevSettings.readSecretToken`）按「secrets 目录下的**名**」解析 → `~/.nebflow/secrets/apikey_286b…` 不存在 → 四修③ 腿自关（`JevSettings.scala:108` 必败，审计成立）。
- **关键哈希实证**：面板存的那把密钥值与盘上既有 `~/.nebflow/secrets/typesafe-api-key` **内容逐字节相同**（sha256 前 16 位同为 `e233951a97564733`，见 `raw/keyref-alignment.json`）——面板管的那把 = `typesafe-api-key` 那把，只是形态差一个「值 vs 名」。

### 方向裁定：补别名/对齐（数据面），不改读面（代码）

- **改读面**（让 readSecretToken 认「值形态」）会破坏两处既有纪律：凭据指针语义（config 存名不存值）+ 读侧遮蔽（`maskSecrets` 刻意不遮 `keyRef`，正因其是名——若允许值形态，密钥将经 GET /api/config 明文出网）。
- **补别名（选定）**：`jev.keyRef` 从原始值改为它 hash 相同的盘上名 `typesafe-api-key`。最小、零代码、不破坏面板（面板仍是一个回读直写的文本框；placeholder 本来就示范这个名）。原值留档 `nebflow.json.bak-alloc-key-20261019`（0600 同目录）。
- **生效方式：热读立即可见**——`JevSettings.resolve` 每次分配现读在位性，无需重启。

### 实测闭环（热读面，运行构建不动）

修前：jev 腿从未发起过调用（腿关闭，`jev-allocation.jsonl` 零 jev 记录，只有 llm 兜底腿的 timeout/failure）。
修后真实 spawn 探针链 ×5（AgentFlow，node:probe…probe7）：**jev 腿复活并 auth 通过**——新记录出现 `event=failure, reason="decision API malformed response: answer 'fit_…': score answer has no numeric 'score'"`。这条分类只可能来自「jev 腿发出了请求、API 返回 200 且应答 JSON 可解析、解析在 score 字段上失败」——即密钥/端点/请求/应答四环全通，卡在第五环（见下）。
**`via=jev` 成功记录候重启**：卡点在运行构建的旧 codec（见 ②-b），热读面无法换——codec 修复合入并过重启窗后，密钥已在位，`via=jev` 必达（今日 5 次采样无一全整数，不赌运气路径）。

## ② 分配链零超时化（拆除，不留配置位）

### 拆除清单（全部在本次提交内）

| 位点 | 拆除内容 |
|---|---|
| `DecisionProvider.scala:283` | `DefaultTimeoutMs = 15_000L` 常量删除，轴注警示改零超时口径 |
| `JevSettings` | `timeoutMs` 字段 + resolve 解析行删除 |
| `TypesafeJevProvider` / `LayaProvider` | `timeoutMs` 参数与 `settings.timeoutMs` 装配删除 |
| `DecisionHttpTransport.post` | `timeoutMs` 参数与 `.readTimeout(15s)` 删除，改**显式 `readTimeout(Duration.Undefined)`**（sttp 自带 60s 缺省，不显式 Undefined 拆不干净）；`SharedBackend` 的 connectTimeout(10s) 是建连基础设施，不动 |
| `JevAllocator` | `timeoutMs` 参数（本就未参与计时）/`apply`/`fromConfig` 装配删除 |
| `LlmAllocator` | `timeoutMs` 参数、`DefaultTimeoutMs`、`.timeout(15s)` 定时器杀全部删除；TimeoutException 特判分支随之失义，统一 Failure |
| `PluginAllocatorMachine.scala:158` | `LlmAllocator.fromRegistry(registry)` 不再带预算；doc 改零超时口径 |
| `config.scala` `JevConfig` | `timeoutMs` 字段删除；旧文件残留键被忽略（deriveDecoder 只读在册键，**零迁移**） |
| `JevGate`/`JevConfigReader` | 快照 `timeoutMs` 字段删除；`JevGateSpec` 断言旧键容忍 |
| `ConfigService.JEV_WRITABLE_KEYS` | `timeoutMs` 移出写白名单（越界 400，防超时位静默回归） |
| `DecisionProviders.resolvedView` | 诊断投影去 `timeoutMs` 键 |

### 附带发现并修（同一腿的第三处断点，不修则 ① 的验收不可能达成）

- **实测定位过程**：引擎实发请求字节经热读 endpoint 置换被抓包（`raw/req-…json`，42 问 dict 形态、与源码 `JevWireCodec.requestBody` 逐字一致）；同字节回放真实端点 ×5 全部干净；转发 MITM 抓到**引擎实际收到的响应**（`raw/mitm-resp-…json` ×2）——响应全部 200 可解析，但 `fit_` score 呈**连续刻度小数**（0.01/0.52/1.92…，一份 21 答中 4 个小数、另一份 14 个），而运行构建 `DecisionProvider.uintOf` 只收**整数位**（含整值浮点）→ 任一小数分即废掉整批 42 答 → jev 腿每次都 Malformed 兜底。密钥修好后腿已通到最后一厘米，是 codec 合同与 jev-1.13.0 实测行为不符。
- **修**（`JevWireCodec` Score 分支）：整数/整值浮点照读；小数分**就近取整**（`math.rint`，负值钳 0）。成员资格从不读 fit 分（`select()` 只读 `need_` choice 应答），强制只动敏感性轴，不碰分配结果。判红锚：`DecisionProviderSpec` 新增 0.52→1 / 0.01→0 / 1.92→2 三例。

### 决策家族 wall-clock 预算盘点（**只拆了分配链的**，其余候作者逐项拍）

| 预算 | 位点 | 现状 |
|---|---|---|
| ~~分配调用预算 15s~~ | jev 腿 transport readTimeout + llm 腿 `.timeout()` | **已拆**（本批） |
| 判定机每腿 15s（原借 `jev.timeoutMs`） | `DelegateJudgeMachine.jevLeg` 元组第二元 | **改挂判定机自己的预算** `judge.timeoutMs`（缺省 30s）——判定族预算不随分配链拆除而消失；是否进一步零超时候作者拍 |
| 判定机整体 30s | `DelegateJudgeMachine.DefaultTimeoutMs` + `judge.timeoutMs` 配置位 | **保留**（候拍） |
| LLM 模型适配器读超时 600s | `Defaults.LlmReadTimeoutSec`（OpenAi/AnthropicAdapter） | 保留——网络层基础设施（候拍） |
| LLM fallback 链预算 | `nebflow.llm.fallback.DefaultTimeoutMs = Defaults.LlmTimeoutMs` | 保留——模型候选链基础设施（候拍） |
| 建连超时 10s | `SharedBackend.httpClient.connectTimeout` | 保留——建连基础设施，非决策预算 |
| 独立搜索腿 15s | `StandaloneSearchConfig.TimeoutMs` | 保留——search 面，非决策家族（候拍） |
| embedding 节 `timeoutMs` 20s | `config.scala` embedding | 保留——检索面，非决策家族（候拍） |

## ③ 验证闭环读数

- **真实 spawn → 分配行非空**：探针链 ×7 全部产出分配行（`raw/jev-allocation-tail.jsonl`）；修后全部至少到 jev 腿真实调用（不再腿关闭）。
- **via=jev 成功记录**：候重启窗（codec 修复生效）+ 密钥已在位 → 必达；热读面今日 5 次采样无一全整数，如实报告不虚报。
- **visual report 插件自带 wiki-docs 纪律：在**。`plugins/visual-report/skills/wiki-docs/SKILL.md`（175 行）完整承载作者令的纪律面：vault 布局（`docs/` + `docs/attachments/`）、wikilink 互引、概念首现必释、frontmatter 场景元数据、glossary 块 id；`plugin.json` description 亦明写 wiki-docs 条目。**插件侧不缺，无需补。**

## ④ 纪律面

- **热读立即可见（无需重启）**：① keyRef 对齐——jev 腿已复活、auth 已通、jev 真实调用已发生（本批实测）；② 旧文件残留 `jev.timeoutMs` 键被忽略（零迁移，spec 锚定）。
- **候重启窗生效（引擎侧，本批提交合入并过窗后）**：零超时拆除全套（两腿再无定时器杀）、fit 小数分就近取整（`via=jev` 成功记录随之可达）、判定机每腿预算改挂 `judge.timeoutMs`、写白名单去 `timeoutMs`、诊断投影去 `timeoutMs`。
- **spec**：决策/jev 族 133+115 全绿（含新判红锚）；全仓 Test/test 见提交时点运行记录。
- 证据：`raw/keyref-alignment.json`（哈希实证 + 现场对齐 + 非目标节未动）、`raw/req-*.json`（引擎实发请求字节）、`raw/mitm-resp-*.json`（引擎实收响应，小数分实证）、`raw/jev-allocation-tail.jsonl`（修前 timeout 全败 → 修后 jev 腿真实调用）。
