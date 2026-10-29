---
type: design-note
status: implemented
---

# 插件面板与协议修复批 · 四件 spec 读数（作者令 2026-10-29 17:03）

> 交付批设计记录：双 toggle 合一、产品卸载流、两拒载包迁移、安全建议四件自裁修复。实施 = 主分支 delegate 链 `delegate-nebflow-4ccea858` 四个独立 commit；本文钉每件的根因 / 修法 / 判据面，回贴与本文同源。上游盘点见 [[20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]（§7 缝隙 + §8 建议），Plugin.md 机制正典见 [[plugin-md-required-file-spec.zh-CN]]，术语见 [[glossary]]。

## 1. 双 toggle 合一（件 1）

**作者令**：「为什么我们插件面板里有两个 toggle？一个 toggle 来控制插件启停不就行了吗」。

**根因（考古实证）**：面板上的「两个 toggle」不是两个控件——是**同一状态源的两处视图**。`web/js/plugins.js` 的 `renderPluginCard` 同时输出：①详情区 `.plugins-item-actions` 内 `[data-plugin-dispatch]`（2026-10-07 条目式批的既有钩子）；②收起态行尾 `.plugins-card-state` 内 `[data-plugin-dispatch-row]`（2026-10-07 14:13 作者令①加的「收起态可见」视图）。两视图读同一 `disp/cardSignature` 状态、乐观翻转与收敛管线遍历同一集合——一个状态、两处渲染。**不是** Plugin.md 被误渲染成开关（截图中部的 toggle 即详情区视图，位于 Plugin.md 入口行下方）。

**修法（单 toggle 定版）**：行尾第二视图**整组退场**——每卡恰一枚开关 = 详情区 `[data-plugin-dispatch]`。`dispatchToggles()` 只查该钩子；CSS 删 `.plugins-card-state .nb-toggle`；钩子语义 / 乐观翻转 / 收敛 / 阻断注记零变。

**判据面（spec 读数）**：`tests/plugins-panel-itemize.spec.mjs`（条目行零控件 + 每卡恰一开关 + `-row` 钩子零元素）、`tests/sidebar-plugins.spec.mjs`（状态区 = chevron 单子元素；`-row` 页面级零残留）、`tests/mobile-p10-plugins.spec.mjs`（开放点②「行内常驻」口径随批退役，唯一位 = 详情区）、`tests/uicopy-copy.spec.mjs`（geom 改读 + 点击前先展开）。附带修复：`plugins-panel-two-actions` / `plugins-panel-redesign` 两个 spec 的「条目行零控件」旧钉（14:13 令后被违反、长期红）随本批重新为真。

## 2. 产品卸载流（件 2）

**作者令**：「除了 toggle，我们应该还要多一个卸载」。

**语义三分（面板/CLI/REST 对等）**：

| 动作 | 语义 | 载体 | 可逆性 |
|------|------|------|--------|
| **remove 卸载** | 移除出册 | `plugins.removed.<name>`（nebflow.json 独立命名空间）+ 种子存在台账 userRemoved 墓碑 + 默认删目录 | 重装复职（`stand plugin add` 显式清标记） |
| **revoke 封禁**（语义分清，面板无入口） | 可逆停用 | `plugins.revoked.<name>` | unblock 即回 |
| **disable 停派发** | 只挡未来派发 | `plugins.dispatch.<name>` | enable 即回 |

**卸载流**：`POST /api/plugins/:name/remove {deleteDir?, reason?}`（默认 `deleteDir:true`）→ ①出册标记（digest+文件数记账）→ ②删目录（默认腿）→ ③审计 `logs/plugin-remove.jsonl`（append-only，与 plugin-block 族同款）→ ④`SeedService.markUserRemoved` 落台账墓碑（种子不自愈装回）。出册 = 注册表面整体消失（`snapshot` 对 plugins ∧ rejected 双过滤，拒载 WARN 打点之前）；`resolve` Left（闸 A/B/C/E 拒）；在飞 MCP 由既有 revalidate「包从注册表消失⇒停」腿 ≤30s 停。

**「仅停用」腿**：`deleteDir:false` = 目录保留 + 出册不变（目录在 ≠ 注册表可见——过滤面在 snapshot 层，不依赖目录缺失）。**二次确认极简档** = 既有 `#delete-box` 三键形态（`showConfirm` 新增可选第三动作 `opts.alt`，缺省 hidden 零形态变化）：主确认 danger「卸载并删除目录」/「仅停用（保留目录）」/取消。

**堵住的洞（本批考古发现）**：`installPluginFromSeed`（播种/自愈共用安装单点）此前**不读台账墓碑**——只有 reconcile 缺失分支读。fresh home 全量播种路径（projects 与 marker 同缺的存储级场景）会把卸载过的默认集包**静默装回**。本批在安装单点加墓碑闸（拒绝即不装、不 approve）。

**判据面**：`PluginRemoveSpec` 六腿（删目录 / 仅停用 / 不存在 Left 零静默 / revoked 表零触碰负控 / 墓碑不被自愈装回 / 重装复职）。

## 3. 两拒载包迁移（件 3）

**作者令**：「修复一下两个插件拒载的问题」（实例截图：nebflow-frontend-dev、nebflow-qa 均 OFFICIAL_IMPERSONATION 拒载）。

**考古结论（实证三点）**：
1. **不在默认集**：两包不在 `seed/manifest.json`（默认集仅 7 包），ledger seen 亦无——非产品预装面。
2. **不在官方面**：`validate_plugin.py` 的 `OFFICIAL_FACE` 9 包名单**明文排除**二者（注释点名「名单外包（engineering-methods / frontend-dev / qa）不属 seed 官方面」）。
3. **来源独立**：git 历史（`eef4c911` sync17 起）= 从独立上游仓 `nebflow-plugins` 同步的开发/验证工具知识包（frontend-dev：web/ 前端规范+视觉裁定+设计系统；qa：五维审核+隔离冒烟+验真）。

**拒载机理**：实例侧包内容被改（runtime plugin.json 缺 keywords+useWhen ⇒ 目录 digest 与分发出厂树不符），触发 `OfficialPackages` 保留前缀硬闸（`nebflow-` 前缀 ∧ digest ∉ 出厂允许列表 ⇒ fail-closed 拒载）。种子树内这两包出厂 digest 与实例内容一致吗——不一致也要被同一闸判（闸以 digest 逐字相等为准，装后被改即死）。

**修法（按考古实证走）= 移出官方命名空间**：`git mv` 两目录去前缀（`nebflow-frontend-dev`→`frontend-dev`、`nebflow-qa`→`qa`）+ plugin.json name + 各包 Plugin.md/SKILL.md 自指 + 全 seed 树交叉指针（22 文件 ~40 处，`nebflow-qa-backend` 等 **skill 名保留**——官方闸只判插件名）。不新增默认集条目、不把实例内容追认为官方种子。

**面板处置提示**：`renderRejectedCard` 对旧名 + `OFFICIAL_IMPERSONATION` 出「已迁移」提示条（点名新名 + 迁移动作，i18n zh/en 成对）——旧实例的存量目录不随分发改名，作者面需要看到去向而不是裸报错。

**判据面**：`OfficialIdentitySpec` 官方样本收敛为 `nebflow-plugin-creator` 一件（种子树保留前缀包自此唯一）+ jar 臂同步；`SeedPluginReconcileSpec` / `SeedPluginDeletionMarkerSpec` / `SeedServiceSpec` 的「非默认集种子包」名单更新；`NodePayloadSpec` 改非保留名。

**开放项（作者侧手工）**：实例 `~/.stand/plugins/` 的旧名目录须手工迁移（目录+plugin.json 改名，或删除后以新名重装）——种子树不做实例数据动作。

## 4. 安全四件（件 4，审计报告 §7/§8 自裁）

审计上游 = [[20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]。

### 4a. mcp.json env `${secret:<name>}` 指针形态
- 装载层：合法指针放行（缺失的 secret 运行期才解析——可后补）；含 `${secret:` 但非完整指针 ⇒ **entry 拒**（fail-closed，半截指针必是笔误）。判定单点 = `SecretEnvPointer`（新件，三消费方共用，勿各自造正则）。
- 运行期：`PluginMcpManager.expandRuntimeConfig`（对象级，`private[plugin]` spec 直读）经 `SecretInjection.materialize` 同款读取语义（raw bytes / UTF-8 / 去一个尾换行 / ≤64KiB）解析；**失败语义（裁定）= server 启动失败**（走 acquire 既有 Left 载体 → 回滚本批启动者 → PLUGIN_MCP_START_FAILED，点名 env 键与 secret 名、值零出现）而非跳过 env——半配置 server 会在请求期才崩，显式失败才可行动。
- 审批面：凭据类键名的红标启发对指针形态**不触发**（值不是明文凭据），出一条「secrets 库解析」信息旗；非指针明文凭据照旧红标（红标降为兜底）。审批面照旧只出键名。
- 判据：`PluginSecretPointerSpec` 五腿。

### 4b. approve 前移 seed 仲裁基准堵口（二选一裁定）
**选了「拒绝」方案**：REST `POST /plugins/:name/approve` 对「默认集包 ∧ runtime digest ≠ 种子 digest」**400 拒绝**并指引先显式接受种子版（删目录让种子自愈装回 = 自动 approve 出厂 digest；或恢复种子内容后再 approve）。判据单点 = `SeedService.approveWouldRewriteDefaultBaseline(name, runtimeDigest)`（非默认集恒 false）。选拒绝而非审计行的理由：approve 洗白基准后，下一次 boot 的 reconcile 判「runtime == trusted」⇒ 出厂镜像覆盖用户版——用户修改被**静默翻掉**（有 pre-sync 备份，但意图被否决）；审计行只是记录破坏，拒绝才阻断破坏。CLI approve 走同一 REST ⇒ 双面对等。

### 4c. 漂移可见面
`SeedService.defaultSetDriftLines()`：种子 ≠ 实例的默认集包一行一名（`[seed-drift] <name>: seed <12>… vs runtime <12>… (原因)`；原因分三态——无信任记录 / 实例漂移出信任记录 / 刷新已排程）；已卸载（墓碑在位）不出行（决策过的状态不是漂移）。挂接点与 `PluginRegistry.logHealthSummary` 相同（GatewayMain startup + NodeStarter rescan），同状态去重、干净场景零输出（`logDefaultSetDriftSummary`）。无基准漂移从不可见变可决策。

### 4d. sse 声明静态警告
装载告警措辞对齐「**声明合法但当前不可用**」（schema 层 sse 是合法 transport，不可用是本客户端能力边界，引导 streamable-http）；`validate_plugin.py` M14/C14 同款 WARN + secret 指针形态镜像（合法指针不出红标、残缺指针 FAIL 级 bad）。

### 不做（列报理由）
- **sandbox.json 审批声明档（P0-4）**：工作量大（审批模型 + 面板 + CLI 三面），随后续批。
- **viewers.json 服务端深校验**：viewers 声明是小体积控制面、仅内联下发受信包、渲染面在前端注册表——服务端深校验的边际风险低（审计已判），保持「schema 归前端」既有边界。

## 5. 验证与边界（诚实申报）

- `sbt Test/compile` exit 0；定向 `testOnly`（PluginRemoveSpec / PluginSecretPointerSpec / SeedServiceSpec / OfficialIdentitySpec / SeedPluginReconcileSpec / SeedPluginDeletionMarkerSpec / SeedManifestCoverageSpec / PluginRegistrySpec / PluginMcpManagerSpec）= **Total 78, Failed 0, Passed 78**（首轮 75 用例 74 绿 + SeedManifestCoverageSpec 因 sbt 资源拷贝陈旧目录误报 1，清理 `target/*/classes/seed/plugins/` 残留后 3/3 绿；非代码问题）。
- Python：`validate_plugin.py --profile user`（frontend-dev / qa / 指针+sse 夹具包）冒烟通过——残缺指针 FAIL 级 bad、合法指针「形态合规」、sse「声明合法但当前不可用」WARN 均按预期出牌。
- 前端：仓内无 node_modules（无 @playwright/test / typescript）⇒ 前端 spec 与 check-js-types **未执行**，静态核对 + `node --check` 申报。
- 分层门禁：`check-scala-layers.mjs` 在 main 上即因 `perception` 包未入 LAYER_ORDER 报 CONFIG ERROR（另一批的既有问题，非本批引入；本批零新增跨层边——core.plugin 内聚、gateway→core.seed 单向）。
- 实例数据零触碰：`~/.stand` 存量旧名包迁移归作者侧。
