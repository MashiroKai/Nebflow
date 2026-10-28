# 插件系统现状审计（作者令 2026-10-28 11:4x）——协议格式 / 内置 creator / 现状盘点 / 差距补齐

- 审计人：delegate-nebflow-67b2a760（stand）。纯只读：未改任何代码/配置/数据，未触在产引擎（pid 70481/6138）。
- 代码基线：worktree HEAD `c59212795`（与主 checkout 同 commit；在产引擎构建自更早的 `8989391ef`，两 commit 间 `PluginRegistry.scala` 零 diff，代码证据对引擎同样成立）。
- 路径缩写：**W** = `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-67b2a760`；**D** = 数据根 `/Users/kaiyu/.stand`。行号均出自 W@`c59212795`。
- 与报告二（`reports/20261028_104800_report2-…__flow-4a0918e2/` 板块②）的分工：分配链（Catalog 渲染→点名→DispatchPolicy→spawn 注入→deny-list 五点阻断→delegate 腿 fail-open）不重复；本篇只在盘点表引用其口径的台账读数。

---

## ① 插件协议速查表（plugins/<name>/ 规范全貌）

权威实现：`W/src/main/scala/nebflow/core/plugin/PluginRegistry.scala`（1540 行，单一装载器）；辅以 `PluginBlockPolicy/PluginDispatchPolicy/PluginMcpManager/OfficialPackages/PluginsConfig` 同目录五件。

### 1.1 目录布局

| 条目 | 必选性 | 语义 | 校验失败行为 | 证据 |
|---|---|---|---|---|
| `plugin.json` | **必需** | 根 manifest，闭合 schema 十一字段（§5.2） | 缺失/unparseable/非 object/类型违规/**拒载** | PluginRegistry.scala:594-612, :874-878 |
| `skills/<skill>/SKILL.md` | 可选 | skill，id=`<plugin>/<skill>`；frontmatter 须含非空 name+description（agentskills.io §7.1） | 目录缺 SKILL.md / frontmatter 缺键 → **skip+告警**，插件继续 | :678-701, :895-903 |
| `mcp.json` | 可选 | `{"$schema":<canonical mcp>,"mcpServers":{…}}`（§7.2.1 闭合） | 任何组件级违规 → **MCP 组件 invalid（告警+置空），插件继续装载**（§6.2 边界） | :703-713, :911-950 |
| `org.nebflow/tools.json` | 可选 | `{"tools":[…]}` 内建工具授予声明 | 请求白名单外工具 → **整包拒载**（`PLUGIN_TOOLS_ILLEGAL`）；parse 失败 → 告警+忽略 | :104, :741-764 |
| `org.nebflow/viewers.json` | 可选 | canvas viewer 贡献声明（控制面 JSON，≤32KB 内联下发） | 超限/unparseable/非 object → 告警+无贡献，插件继续 | :124, :766-812 |
| `Plugin.md` | **必要**（作者令 2026-10-21「plugin.md = 插件必要文件机制」） | 分配注入体：插件功能描述单源，`{{data_root}}` 渲染 | 注入期缺失/不可读 → **分配拒**（Left，no-silent-degradation）；⚠️ 装载器 known-set **尚未收录** → 每包常驻一条 forward-compat 告警（见 §④-G1） | PluginInjection.scala:203-221; PluginRegistry.scala:583 |
| 其他顶层条目 | — | 未知条目/未知目录 | **宽容忽略+告警**（§B.8-5 前向兼容） | :583-592 |

### 1.2 plugin.json canonical schema 逐字段（闭合 11 键，KnownManifestKeys :133-145）

| 字段 | 必选 | 类型 | 语义/校验 | 违规行为 | 证据 |
|---|---|---|---|---|---|
| `$schema` | **必填** | string | 须**全等** canonical：`https://agent-plugins.org/schemas/1.0.0/plugin.schema.json`（:110） | 缺失/非 canonical → **拒载**（`PLUGIN_SCHEMA_UNSUPPORTED`，§5.2 客户端 MUST reject） | :623-630 |
| `name` | **必填** | string | §5.5：1-64 字符；`a-z 0-9 - .`；首尾字母数字；禁连续 `--`/`..`（:477-482） | 违规 → 拒载（`PLUGIN_NAME_ILLEGAL`）；目录名≠manifest 名 → 以 manifest 为准+告警 | :633-644 |
| `version` | 可选 | string | **不做 semver 约束**（协议明示 MUST NOT 因非 semver 拒载）；纳入 digest，version bump 即重审 | 类型违规 → 拒载；缺失 → 审批清单红标 flag | :646-649, :344 |
| `description` | 可选 | string | **唯一描述源**（选配判定 + 目录行渲染）；`capability` 键已 deprecated（登记容忍、零读取） | 类型违规 → 拒载；缺失 → 目录行回落 name | :649-652, :127-131, :1493-1495 |
| `capability` | 已废 | — | deprecated 容忍键：不报错、不告警、不读取（描述单源批 2026-09-10） | — | :127-145 |
| `author` | 可选 | object | 仅 `name/email/url` 三个 string 字段（闭合） | 非 object/多余键/空值 → **拒载**（§5.4） | :664-676 |
| `homepage` / `repository` / `license` | 可选 | string | 元数据，审批清单渲染 | 类型违规 → 拒载 | :653-655 |
| `keywords` | 可选 | string[] | 元数据 + `stand-plugins search` keyword 通道 | 非 string[] → 拒载 | :656-661 |
| `extensions` | 可选 | object | `{"org.nebflow/tools":"<文件名>", "org.nebflow/viewers":"<文件名>"}` — 声明命名空间内文件名（缺省回落 `org.nebflow/tools.json`/`viewers.json`）；解析次序 = org.nebflow/ 相对为主、插件根相对兼容，两处均过 §4.1 围栏 | 声明了但文件不可读 → 告警+该扩展忽略 | :718-740, :772-794 |

未知 manifest 键：宽容忽略+告警（:614-619）。§4.1 路径围栏：manifest/SKILL.md/tools.json/viewers.json/`./`command/cwd 全部要求 realpath 后仍在插件根内（:459-471）。

### 1.3 装载门槛与失败面分级

- **硬门槛（裁定 12/§B.8-7）**：`skills/` 与 `mcp.json` 至少其一，全无 → 拒载（:815-818）。
- **拒载（整包出局）**：manifest fatal / name 违规 / schema 非 canonical / tools 越白名单 / 无 skills 无 mcp / 官方身份闸命中 / digest 计算失败。表现：逐包 WARN、目录缺席注记、启动健康摘要 `[load-failed]`、`listWithRejected`（REST `GET /api/plugins` 的 `rejected` 段，PresenceRoutes.scala:1395-1403）。**不新增第四种错误面**。
- **组件级降级（插件继续）**：mcp.json 任意违规 / viewers 超限 / 单 skill frontmatter 缺键 / 未知条目 — 各自告警+置空该组件。
- **信任判定顺序（不可交换，:836-846）**：① `plugins.revoked.<name>`（deny-list，PluginBlockPolicy）命中 ⇒ `Blocked`；② 否则 ⇒ `Trusted`（**在位即信任**，2026-09-13 作者令「装了就是信任」）。`plugins.trust.<name>` 审批记录**不再决定装载**，只剩审计 + seed 覆盖仲裁基准两个用途；digest 漂移 ⇒ `contentChanged` 非拦截可见性（:842-843）。
- **官方身份闸（OfficialPackages.scala:51-54, :115-116）**：`nebflow-` 保留前缀 ∧ 目录 digest ∉ 分发内置允许列表（对 `seed/plugins/` 资源树**现算**，算法与 computeDigest 同款，零漂移）⇒ 拒载 `OFFICIAL_IMPERSONATION`。资源树不可读 ⇒ 空表 ⇒ 保留前缀一律拒载（fail-closed）。非保留前缀不经此闸。
- **扫描节奏**：进程内目录树 mtime 缓存，任一 mtime 变化全量重扫（:486-527）；封禁/解封写面须显式失效缓存（PluginBlockPolicy 头注）。

### 1.4 MCP server 配置格式（§7.2.1/§7.2.2，:956-1076）

| 项 | 规则 | 违规 |
|---|---|---|
| 顶层 | `{$schema, mcpServers}` 闭合；未知顶层键告警忽略 | 非 canonical `$schema` / `mcpServers` 缺失非 object → **MCP 组件整体 invalid** |
| `type` | 必填 ∈ `stdio` \| `streamable-http` \| `sse` | 缺失/未知 → 跳过该 entry |
| stdio | `command` 必填、单可执行 token（无空白）；`./` 前缀按插件根解析+围栏；裸名走 PATH | 违规 → 跳过该 entry |
| `env` | string→string object；**禁声明 `PLUGIN_ROOT`/`PLUGIN_DATA`**（客户端保留占位键，§9.1，:151） | 声明保留键 → 跳过该 entry |
| `cwd` | 须 `./`（插件相对）或 `${PLUGIN_ROOT}`/`${PLUGIN_DATA}`；`./` 归一为占位形式 | 违规/越围栏 → 跳过该 entry |
| http 类 | `url` 必填：绝对 http/https、无 userinfo、无 fragment、非 loopback 须 https（:1082-1093） | 违规 → 跳过该 entry |
| `sse` | legacy wire 未实现（OPTIONAL）→ **MUST skip+report**（:1052-1058） | 显式告警跳过 |
| entry 未知字段 | 告警+忽略（前向兼容） | — |
| 隔离边界 | 单 entry 违规只跳该条，其余 entry 与组件继续（§7.2.2） | — |

运行期（PluginMcpManager.scala）：§9 占位符展开 `PLUGIN_ROOT`=插件根、`PLUGIN_DATA`=`<dataRoot>/plugin-data/<plugin>/`（启动前创建，:107-119）；引用计数复用单实例（:14-18）；启动失败 → 本批回滚 + `Left` → **failNode 不静默**（`PLUGIN_MCP_START_FAILED`，:69-82）；15s timeout（:62）；封禁 ⇒ 在飞 MCP ≤30s 停（类注 :23-27）。工具注册名 `mcp__plugin_<plugin>_<server>__<tool>`。

### 1.5 工具授予面（org.nebflow/tools.json）

- 白名单 `BuiltinToolWhitelist = {WebSearch, WebFetch, Curl}`（:104）——只能授予既有 builtin 工具，不能发明新工具、不能授予编排类（Mail/NodeEdit 永不进白名单，§C.1 角色边界）；Pop 已移出（2026-09-10 作者裁定，:96-103）。
- 双重执行：装载层越界 = 整包拒载（:754-760）；会话层再过滤一次（AgentSessionExecution.scala:2334 `agentDef.pluginTools.filter(BuiltinToolWhitelist)`）。
- 在野实况：21 包中仅 web-search-toolkit 声明（WebSearch+WebFetch），与其余 20 包的「无工具扩展」声明一致。

### 1.6 版本化与兼容策略

- **单版本硬断点**：客户端只识别 canonical 1.0.0 全串；任何其他 `$schema` 值 = 拒载（manifest）/组件 invalid（mcp.json）。无版本协商、无多版本共存（§5.2「不支持声明的版本 → 拒绝并报告 unsupported version」）。
- 前向兼容全靠「未知即宽容告警」：未知 manifest 键、未知顶层条目、mcp 未知字段、未知 transport type（后者是 skip 非 load）。
- 组织扩展正门 = `extensions` + `org.nebflow/` 命名空间；但 loader 的 `known` 顶层集与 `org.nebflow` 子条目集是硬编码白名单——**每扩一个新声明类型都要改装载器**，漏扩 = 贡献静默失效（viewers.json 批已补过一次，:581-591 注释自证）。

### 1.7 周边管理面（速查）

- REST（PresenceRoutes.scala）：`GET /api/plugins`（全量+rejected，:1395）、`GET /api/plugins/catalog`（目录段同字节，:1408）、`POST /api/plugins/:name/approve`（兼容保留，:1420）、`/revoke`（封禁，:1435）、`/unblock`（:1457）、`/disable|/enable`（派发开关，:1473）、`/dispatch/grant`（过渡授权）、`/api/plugin-assets/<name>/…`（执行体资产令牌路由，StaticRoutes.scala:672）。
- CLI：`stand plugin add|approve|revoke|unblock|enable|disable`；只读查询 CLI `tools/stand-plugins`（list/search/mounts，见 §③）。
- 安装：`PluginRegistry.installFrom`（:1202-1276）git/本地路径 → 拷入 `plugins/<manifest name>/`，同名拒覆盖，点路径/cwd 祖先守卫（#344）；落盘即生效。
- 分发注入：`seed/manifest.json` 声明 7 个受管包（visual-report、slideblocks、nebflow-plugin-creator、web-search-toolkit、browser-use、computer-use、document-production）；SeedService.reconcilePlugin（SeedService.scala:456-545）语义：保留前缀包 runtime≠seed ⇒ **出厂字节赢**（备份+镜像+重审）；非保留包干净（=trust 记录）⇒ 刷新，用户改过 ⇒ 保留用户版；用户删除 ⇒ presence ledger 标记不装回（`.plugin-presence.json`）。

---

## ② 内置 plugin creator 审计

### 2.1 它在哪、是什么

- **本体 = seed 官方包 `nebflow-plugin-creator`**：分发树 `W/src/main/resources/seed/plugins/nebflow-plugin-creator/`（10 文件）与盘上 `D/plugins/nebflow-plugin-creator/` **逐字节一致**（`diff -rq` 空；digest 同为 `f0b97c9f90cc`，2026-10-28 09:37 boot 由 seed 镜像刷新+重审，stand.log:43-44）。它是唯一 `nebflow-` 保留前缀包，走 OfficialPackages digest 身份闸 + seed 出厂字节赢语义。
- **不是进程/服务，是一个提示词包+校验脚本**：creator「工作原理」= 节点被分配该插件后，注入 `Plugin.md`（导览）→ 按需读 `skills/plugin-packaging/SKILL.md`（176 行执行手册）执行。
- 组成：
  - `skills/plugin-packaging/`：主手册 + `assets/manifest.template.json` + `assets/skill-template/`（SKILL.md+references/+assets/ 骨架）+ `assets/pluginmd.template.md` + `scripts/validate_plugin.py`（52KB 机械校验器）；
  - `skills/description-quality/`：manifest 五段式描述规范（115 行，M1-M15 判定口径+四例好坏对照）。

### 2.2 全流程（plugin-packaging SKILL.md 六步）

1. **输入形态三判定**（SKILL.md:21-30）：A 既有 skill 迁移 / B MCP server 配置（**实验性**）/ C 口头能力描述；可组合。
2. **能力域分析与 skill 拆分**：one skill = one purpose；深内容下沉 references/、脚本 scripts/、模板 assets/。
3. **逐件生成**：manifest（五段式 description：定位句含谓词/触发场景句核心词全落字/skills 明细/自足边界句/组件面句，≤400 字符）；skill 目录（frontmatter 下限 name+description）；mcp.json（仅形态 B）；**Plugin.md 包根必要文件**（:76-95）。
4. **落盘**：直写 `{{data_root}}/plugins/<name>/`，先同名探测（命中走分叉①，绝不静默覆盖）。
5. **机械自检**：`python3 ${SKILL_DIR}/scripts/validate_plugin.py --profile creator <pkg>` — M1-M15 全量 + C 组（C1-C16，C7 缓）装载器镜像 + digest 复算；**退出码 0 = 交付闸**；FAIL 自修 ≤2 轮，仍败 = blocked 上报，禁降级交付。
6. **终检报告+冻结目录**：包结构/来源/digest/停止手段（面板封禁 / REST revoke / CLI revoke）。

三档 profile（validate_plugin.py:30-38）：`official`（OFFICIAL_FACE 9 包硬闸）/ `creator`（默认交付闸）/ `user`（用户自装包提示级，退出码恒 0）。校验器自我定位 =「PluginRegistry 装载规则的上游前置镜像，不是平行第二权威」（:4-5），头部带 Scala 锚点行号。

### 2.3 与 skill-creator（~/.stand/skills/skill-creator）的关系：**近亲，不是同一物**

| | nebflow-plugin-creator（插件层） | skill-creator（agent skill 层） |
|---|---|---|
| 载体 | seed 插件包（`D/plugins/`），分配注入 | 内置 skill（`D/skills/skill-creator/`，SkillService.ensureDefaults 写入，SkillService.scala:71-77） |
| 产物 | `plugins/<name>/` 合规**插件包**（plugin.json+skills/+Plugin.md…） | `skills/<name>/SKILL.md` **agent skill**（frontmatter+scripts/references/assets） |
| 消费面 | 分发器选配 → 节点分配 | agent.json `skills` 订阅 → catalog 注入 + 按需 Read + `/slash` |
| 格式规范 | Agent Plugins 1.0.0 + 五段式 description | agentskills.io frontmatter（name/description 必填 + language/status/audience 等可选表） |

两层共享 agentskills.io 的 SKILL.md 格式与 `${SKILL_DIR}` 约定（skill 层替换实现：SkillService.scala:314-315），creator 的 skill-template 即沿用该惯例——**creator 生成的 skill 目录天然是合法 skill 层形状**，但封装成品只装进插件层。

### 2.4 产出格式与协议的一致性；creator 能力边界

- **天然合法**：模板只出 canonical `$schema`+合法 name 占位（禁保留前缀在生成期拦截，SKILL.md:50-52）；M1-M15/C 组镜像装载规则，M14 逐 entry 镜像 mcp 校验，C9 镜像官方身份闸——按手册走完交付闸的包不会出现「格式拒载」（分叉⑤仅作兜底）。所有 21 个在装包全部可装载即旁证。
- **MCP 型插件能建**：形态 B 显式支持（SKILL.md §3.3「生成 mcp.json（实验性）」），校验器 M14/C14 齐备；但明确标注实验性——「装载校验规则完备，磁盘上尚无生产样本，首例产出建议在隔离实例验证 MCP 启动链后再交付」（SKILL.md:61-63；Plugin.md §8 同）。
- **边界/欠账**（证据见 §④-G2）：模板未覆盖协议可选元数据字段；creator 自身文档存在 10-21 批后的「spawn 注入」措辞残留与自相矛盾；`pluginmd.template.md` 资产已在 `assets/` 但 Plugin.md §8 仍登记「规划中」。

---

## ③ 现状盘点

### 3.1 21 个已装插件逐个体检表

数据来源：盘上实测（`D/plugins/*/`）+ `D/nebflow.json` plugins 段 + 分配台账（`stand-plugins mounts --json` 聚合）+ 引擎健康摘要（`D/logs/stand.log` 2026-10-28 09:37:16 startup：**21 on disk / 21 loaded / 21 catalog-visible / 0 absent / 3 content-changed**）。

通用面：21/21 manifest 可解析、`$schema` canonical、name==目录名、version 在位、author object、无 `capability` 残留、skills frontmatter 全合规、`Plugin.md` 全在位（盘上 mtime 多为 10-20/10-28 补齐批）。**孤儿文件/死引用：无**（design-spec 坏例①的「user 层 skill nebflow/visual-style」死引用已改为指 Plugin Catalog，实测 description 现文）。mcp.json：**0 例**；org.nebflow/：仅 web-search-toolkit 有 tools.json；viewers.json：0 例。

| 插件 | ver | files | KB | 盘上 mtime | skills | mcp | org.nebflow | author | trust 记录 | delegate-spawn 台账分配次数 |
|---|---|---|---|---|---|---|---|---|---|---|
| academic-research | 1.0.1 | 4 | 40 | 09-10 | 2 | – | – | Nebflow | 无 | 7 |
| backend-dev | 1.0.1 | 5 | 32 | 10-14 | 3 | – | – | Nebflow | 无 | **134** |
| browser-use | 2.0.0 | 22 | 192 | 10-14 | 4 | – | – | Nebflow | 10-08 ⚠︎changed | 8 |
| computer-use | 0.1.0 | 8 | 68 | 10-14 | 1 | – | – | Nebflow | 10-08 ⚠︎changed | 4 |
| design-cards | 1.0.1 | 41 | 2244 | 10-14 | 2 | – | – | Nebflow | 无 | 4 |
| design-spec | 1.0.1 | 3 | 20 | 10-14 | 1 | – | – | Nebflow | 无 | 9 |
| docs-prompt | 1.0.1 | 4 | 24 | 10-14 | 2 | – | – | Nebflow | 无 | 4 |
| document-production | 1.0.0 | 112 | 2092 | **10-28**（seed 刷新） | 5 | – | – | Nebflow | 10-28 | 17 |
| engineering-methods | 1.0.1 | 4 | 20 | 10-14 | 2 | – | – | Nebflow | 无 | 16 |
| explorer-toolkit | 1.0.1 | 4 | 24 | 10-14 | 2 | – | – | Nebflow | 无 | 29 |
| learn-anything | 1.0.1 | 13 | 60 | 09-10 | 1 | – | – | Nebflow | 无 | **0（从未分配）** |
| nebflow-frontend-dev | 1.0.1 | 5 | 44 | 10-14 | 3 | – | – | Nebflow | 无 | 91 |
| nebflow-plugin-creator | 1.0.0 | 10 | 116 | **10-28**（seed 刷新） | 2 | – | – | Nebflow | 10-28（官方闸受管） | 2 |
| nebflow-qa | 1.0.1 | 7 | 56 | 10-14 | 5 | – | – | Nebflow | 无 | 89 |
| phd-note | 1.0.1 | 3 | 20 | 09-10 | 1 | – | – | Nebflow | 无 | **0（从未分配）** |
| pipelines | 1.0.1 | 5 | 40 | 10-14 | 3 | – | – | Nebflow | 无 | 46 |
| slideblocks | 1.0.1 | 43 | 924 | **10-28**（seed 刷新） | 1 | – | – | **UniUni2000/slideblocks-skill** | 10-28 | 2 |
| thesis-review | 1.0.1 | 3 | 32 | 09-10 | 1 | – | – | Nebflow | 无 | **0（从未分配）** |
| visual-report | 1.5.0 | 82 | 1784 | 10-14 | **21** | – | – | Nebflow | 10-08 ⚠︎changed | 32 |
| web-search-toolkit | 1.0.0 | 4 | 20 | **10-28**（seed 刷新） | 1 | – | **tools.json**（WebSearch,WebFetch） | Nebflow | 10-28 | 17 |
| website | 1.0.1 | 5 | 24 | 10-20 | 3 | – | – | **Stand** | 无 | 18 |

健康度总评：**零拒载、零封禁（`plugins.revoked` 空）、零 dispatch-off（`plugins.dispatch` 表不存在=全缺省 enabled）、零 schema 缺字段**。唯一非绿灯 = 3 个 `content-changed`（browser-use/computer-use/visual-report：10-08 审计基准后内容被改过，seed 判「用户改过保留」，非拦截）。author 面两处变体（slideblocks=上游仓库名、website=Stand）非违规，仅审批清单可读性差异。

seed 面对账：分发树 18 包 vs 在装 21 包 —— 差集 = 在装有 4 个非 seed 包（academic-research/learn-anything/phd-note/thesis-review，本地安装、无 trust 记录、不经官方闸）+ seed 有 1 个未装包 **design-language**（在分发树但不在 `seed/manifest.json` items ⇒ reconcile 不自装，dormant 可用件；presence ledger `.plugin-presence.json` 仅 7 seen、userRemoved 空）。

### 3.2 `tools/stand-plugins` CLI 与真实数据根对账

- `list`：**21 plugins, 21 dispatchable, 0 blocked, 0 rejected** —— 与引擎稳态一致（manifest 可解析+canonical schema+skills/mcp 至少其一的轻量镜像，tools/stand-plugins:128-183）。两处镜像缺口：① **无官方身份闸镜像**——保留前缀包盘上漂移时 CLI 假绿（今天 boot 窗口即实况：引擎 09:37:05 两次拒 creator，CLI 全程 dispatchable；稳态后两边一致）；② warnings/深检不镜像（by design「只报形状」）。
- `search`：AND 语义六字段检索正常；**噪音点**——`search mcp` 命中 **21/21**：每包描述按五段式收尾「组件面：无 mcp.json…」，反句里的「mcp」参与字面匹配（stand-plugins:278-289 不区分正/反语境）。
- `mounts`：28 projects scanned，**declared（flow-map nodes[].plugins）= 0 项目 0 行——项目级挂载零实例，验证成立**；allocated（chain ledger delegate-spawn 行）9 项目有分配，模式分布 jev 341 / fail-open 96 / llm-fallback 17 / 空 7；unattributed（step-plugins 等）191 行。分配频次见 §3.1 末列；**learn-anything / phd-note / thesis-review 三包在两种台账里都零出现**（从未被分配过）。

### 3.3 skill 层与 plugin 层关系澄清

| | agent skill 层（`<dataRoot>/skills/`） | 插件 skill 层（`plugins/<p>/skills/<s>/SKILL.md`） |
|---|---|---|
| 装载器 | SkillService（`W/src/main/scala/nebflow/core/skill/SkillService.scala`，根目录 :65-66） | PluginRegistry（§①-1.3） |
| 进入 agent 的路径 | agent.json `skills` 订阅 → catalog 注入系统提示（name+description，**每 turn 在场**）→ 按需 Read 全文 + `${SKILL_DIR}` 替换（:314-315）→ `/slash` 用户调用 | **不进 agent catalog、不可 slash**；10-21 批后分配注入体 = 包根 Plugin.md，SKILL.md 降为按需查阅件（面板胶囊深链） |
| 生命周期 | 用户/agent 直接增删改，改后即时生效 | 整包分配/封禁；digest 审计 + seed 仲裁 |
| 现存实例 | `_example`、`memory-consolidation`、`skill-creator`（3 个） | 66 个（21 包合计） |

结论：同名格式（frontmatter name+description、${SKILL_DIR}、scripts/references/assets 约定）双通用于两层，但**装载、注入、检索、撤销四条管线完全独立**；skill-creator 与 plugin-packaging 分治两层，无互相生成通道。

---

## ④ 差距与补齐（四列表，三档）

| # | 设计目标 | 现状 | 差距 | 补齐动作 | 档 |
|---|---|---|---|---|---|
| G1 | Plugin.md = 插件必要文件（作者令 2026-10-21） | 注入面已切（PluginInjection.scala:203-221）、validator 已收（KNOWN_ROOT_ENTRIES 第五件，validate_plugin.py:97-99） | **装载器 known-set 未扩**：PluginRegistry.scala:583 `known = Set(plugin.json, mcp.json, skills, org.nebflow)` 不含 Plugin.md/plugin.md ⇒ 21 包每包常驻告警 `ignored unknown entry 'Plugin.md'`，且小写 plugin.md 同样不认（注入面却同认，PluginInjection.scala:206）；validator 头注自证「装载器侧扩面随下个引擎批（六-1）」 | known-set 加入 `Plugin.md`（可同时收 `plugin.md`），一个 setter 级 diff + 断言更新 | 顺手修 |
| G2 | creator 自身文档/资产自洽 | (a) creator Plugin.md:17「派发时两份 SKILL.md 全文注入节点」、:28/:35「（注入面）」、:74「spawn 已注入」 vs :3「SKILL.md 回归按需查阅件」——同文件新旧机制并存；(b) plugin-packaging SKILL.md:35「SKILL.md 正文在 spawn 时一次性全文注入」vs 同文件 :84-89「不再随分配整文注入」——同文件自相矛盾；(c) skill-template/SKILL.md:10「spawn 时一次性收到本文全文注入」stale；(d) Plugin.md §8 把 pluginmd.template.md 登记为「规划中」，而 `assets/pluginmd.template.md` 已在位 | 按旧机制理解的节点会在交付报告/导览里写错注入语义 | 四处措辞统一为「Plugin.md=注入体、SKILL.md=按需查阅」；§8 该行改「已在位」 | 顺手修 |
| G3 | search 通道可信 | `stand-plugins search mcp` 21/21 全中（反句污染） | 组件面声明句进检索字段 | CLI 检索字段剥离「组件面：…」句（或按「无 mcp.json」负模式降权） | 顺手修 |
| G4 | CLI 对引擎的可见性 | CLI 无官方身份闸镜像：creator 类保留前缀包盘上漂移 ⇒ CLI 假绿 dispatchable（今日 boot 实况） | 轻量镜像承诺（「与 PluginRegistry.load 同向」）在官方闸上断档 | list 对 `nebflow-` 前缀包加「official-gate: unverified」注记，或镜像现算 seed digest 比对 | 一批 |
| G5 | keywords 元数据通道 | 协议支持（KnownManifestKeys）+ CLI keyword 检索字段就绪；**21/21 包零 keywords**，creator 模板亦无此字段 | 通道整体空转 | 二选一：creator 模板/描述规范补 keywords 段并给存量包补齐；或声明该通道退役并从 CLI 检索字段摘除 | 一批 |
| G6 | validator 锚点可靠性 | validate_plugin.py 头部锚点行号已漂移（如标 `PluginRegistry.scala:66 CanonicalSchema`，实际 :110；`computeDigest :237-248` 实际 :417-429） | 行号锚点随源文件增长静默失效，镜像对照退化 | 锚点改符号引用（对象+成员名），或加一个「锚点行号现算」的 spec 防漂移 | 一批 |
| G7 | MCP 型插件协议→实跑 | 协议/装载/生命周期/失败语义/测试全备（§①-1.4；PluginMcpManager.scala 261 行；PluginMcpProtocolSpec 234 行+PluginMcpManagerSpec）；**运行痕迹为零**：`D/plugin-data/` 不存在（acquire 首跑即建目录）＝从未有任何 plugin MCP server 启动过；21 包+seed 树 mcp.json/viewers.json 均 0 例 | 差的不是代码，是**首例实跑**：① 真实 mcp.json 包（creator 形态 B）；② 隔离实例走通 acquire→stdio 启动→工具注册（mcp__plugin_* 命名）→release/封禁停飞；③ 红标项（凭据 env/shell command）人审流程演练 | author 令单立一包一实例的验证批（Plugin.md §8 已预留该扩展位） | 一批 |
| G8 | 协议版本演进能力 | `$schema` 全等判定、单版本（1.0.0）；未来 1.1.0 = 存量包全量拒载/组件 invalid，无协商窗口 | 演进 = 引擎先行的硬断点；mcp.schema 同步约束（§10.1）叠加 | 版本协商/双版本过渡策略设计（含 creator 模板、validator、CLI 三面同批） | 专项 |
| G9 | 扩面机制可维护性 | 新增一个 org.nebflow 声明类型需同批改 loader known-set（:583-591）+ validator（C11/C13）+ CLI（display）三处；已有两次教训（viewers 补扩 :581-583 注释；Plugin.md 六-1 未落地） | 静默失效风险结构性存在 | 声明类型注册表化（单点枚举 + 测试锁三面一致） | 专项 |
| G10 | trust 记录语义纯度 | `plugins.trust` 双用途（审计 + seed「干净快照」仲裁基准）：任何 approve（含 seed 自愈自动 approve）都会把基准前移（PluginRegistry.scala:1155-1160 已如实登记「已知代价」） | 手动 approve 用户改过的包 ⇒ 下次 boot seed 覆盖被判「干净」 ⇒ 出厂字节赢 | 审计基准与 seed 仲裁基准拆表，或 approve 增加「前移基准」显式确认 | 专项 |

### 特别评估

**MCP 型插件从协议到实跑还差几步**：代码侧 **0 步**（协议 schema、entry 校验、占位符展开、引用计数、失败 failNode、封禁停飞、12 个 plugin 测试 spec 全在位）；实跑侧差 3 步 = 一个真实包（creator 形态 B 就能产）、一次隔离实跑（stdio 启动链从未在生产走过，`plugin-data/` 缺位是零实跑的硬证据）、一轮红标人审演练。风险集中在「规则完备但无在野样本」——任何首例都可能暴露 stdio 环境/PATH/cwd 的现实差异，故建议隔离实例先行（与 creator 手册的实验性标注一致）。

**creator 模板对 1.0.0 协议全字段的覆盖**：必填面 100%（$schema/name/version/description/author 全在 manifest.template.json），可选元数据面 0%（homepage/repository/license/keywords/extensions 未入模板——extensions 对纯 skill 包本就不需要，keywords 是唯一有实际消费者（CLI 检索）的缺项，见 G5）；skill 面 100%（§7.1 下限 + ${SKILL_DIR}/references/scripts/assets 惯例）；mcp 面=校验器齐、模板无起步样例（形态 B 靠手册文字+M14 期望值输出，可补一个 `mcp.template.json`，归入 G7 批次）。结论：**模板覆盖「能合法装载」全字段，未覆盖「协议允许」全字段**——按五段式哲学（描述即选配面）这是有意收敛，不算缺陷；唯一建议补的是 keywords（G5）。

**协议版本演进压力点**：① 单 canonical 全串判定（G8）是最大硬点；② `capability` deprecated 键需永久占位 KnownManifestKeys（内存小、但语义债长存）；③ 白名单族（tool grants 3 件硬编码、known 顶层集、org.nebflow 子条目集）三处分散（G9）；④ trust 模型「在位即信任+deny-list」本体自洽，压力在 trust 记录双用途耦合（G10）；⑤ 官方身份闸与 seed 刷新的 boot 竞态——今日 09:37:05 creator 在 seed 镜像刷新窗口被 OFFICIAL_IMPERSONATION 拒载两次（旧盘 `9203c1a86f10` vs 新种子 `f0b97c9f90cc`，stand.log:29/:41-44），刷新后自愈、健康摘要干净；该窗口内若有**声明集点名 creator** 的 spawn 会以 PLUGIN_NOT_FOUND 失败（分配腿不受影响——目录不含即不分配）。属低概率噪音，登记不立项。

---

## ⑤ 证据索引（关键文件）

- 装载器/协议：`W/src/main/scala/nebflow/core/plugin/PluginRegistry.scala`（canonical schema :110、known-set :583、name :477-482、fields :646-676、skills :678-701、mcp :911-1076、tools 白名单 :104/:754-760、viewers :766-812、digest :417-429、trust :836-846、installFrom :1202-1276、health :1412-1473、catalog :1478-1540）
- 信任/派发/封禁：`PluginBlockPolicy.scala`（revoked 独立命名空间）、`PluginDispatchPolicy.scala`（dispatch 表 :23/:113-127）
- 官方身份：`OfficialPackages.scala`（保留前缀 :51-54、admits :115-116、fail-closed :89-90）
- MCP 生命周期：`PluginMcpManager.scala`（acquire :44-98、占位符 :107-119、revalidate 停飞）
- 注入：`W/src/main/scala/nebflow/core/project/PluginInjection.scala`（Plugin.md 注入体 :189-221、分配三态 :52-105）、`NodeStarter.scala:334-347`、工具授予执行 `AgentSessionExecution.scala:2334`
- seed：`W/src/main/scala/nebflow/core/seed/SeedService.scala:437-545`、`W/src/main/resources/seed/manifest.json`、`D/.plugin-presence.json`
- creator：`W/src/main/resources/seed/plugins/nebflow-plugin-creator/`（Plugin.md:17/:28/:35/:74/:99-105；plugin-packaging/SKILL.md:35 vs :84-89；skill-template/SKILL.md:10；scripts/validate_plugin.py:30-99）
- skill 层：`W/src/main/scala/nebflow/core/skill/SkillService.scala`（:65-77 ensureDefaults、:314-315 SKILL_DIR）、`D/skills/skill-creator/SKILL.md`
- CLI：`W/tools/stand-plugins`（list :460-490、search :278-312、mounts :315-424、镜像判据 :128-183）
- 运行证据：`D/logs/stand.log`:29/:41-49（boot seed 刷新与瞬时拒载）、:912-917（健康摘要 21/21/0 absent/3 changed）；`D/nebflow.json` plugins 段（trust 7 条、revoked 空）；REST 面 `W/src/main/scala/nebflow/gateway/PresenceRoutes.scala:1391-1473`
- 测试面：`W/src/test/scala/nebflow/core/plugin/` 12 个 spec（Registry/Manifest/Mcp/Manager/Official/Dispatch/Approval/Viewer/InstallGuard/LoadVisibility/Chain/DispatcherCatalog）

### 开放项（本审计未做/无法做）

- 未触在产引擎 REST（作者令禁碰），`GET /api/plugins` 的 warnings 字段实况由代码推断（known-set 缺 Plugin.md ⇒ 每包告警必然在 `approvalManifest.warnings` 中），未逐包目验。
- 隔离实跑 MCP 启动链未做（属 G7 补齐动作，非只读审计范围）。
- 报告二已覆盖的分配链细节（Jev/LLM 分配、spawn 注入逐行）本篇仅引用读数，未重审。
