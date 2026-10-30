# preset→model-chain 启动迁移批 · 批报告（2026-10-30）

- 分支：`delegate/delegate-nebflow-acc72f03`，code commit `426dd8fc3`（docs/report 随后同批落）
- 作者令：2026-10-30 08:29 拍板「做」；上一实例死于 Bash 同参连击循环，本批全程后台单发 + 零轮询
- 引擎：未重启、未 kill 任何进程；gateway PID 63637 全程存活；生产配置只动 `~/.stand` 两个 JSON + 一个备份

## 件一 · 启动迁移腿（preset 定义 → model 链注册表）

**落点**（worktree，commit `426dd8fc3`）：

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala` | **新建**。命名链注册表 `model-chains.json`（version 1 + `migratedAt` 迁移完成戳 + `defaultPreset` + `chains` 槽）；旧 preset 名逐字随迁移带走（**无别名表**，名即兼容）；write-ahead 合并（**既有条目恒胜**——Scala `Map ++` 右侧优先，既有表放右侧，spec 钉死）；AtomicJson 原子写；读面 fail-soft |
| `src/main/scala/nebflow/core/ModelChainMigration.scala` | 启动腿本体扩展：preset 定义先 write-ahead 迁注册表、后加戳封存 `model-presets.json`（`model-presets.json.sealed-<ts>`，原字节保留，对齐 R5 write-ahead 先例 `c30e6945c`）；**角色 preset 引用迁移保持无条件执行**（无目录文件的家仍落内置默认链/key-removal——首版包进 exists 守卫曾拆这条，spec 红后回正）；无链 preset 不进注册表（WARN） |
| `src/main/scala/nebflow/core/presets/PresetStore.scala` | 默认路径读面切注册表（`ModelChainRegistry.activeFor` 判定：默认路径 + 注册表实存）——`resolve`/`resolveExplicit`/`availableNames`/`catalogLines` 全部零改动地解析到迁移后的链（**SubTask preset 参数、工具 schema 目录、自描述错误面单源**）；`save`/repair 写回注册表，**绝不重建**被封存的 legacy 目录（否则二次启动会从 providers 重新播种单腿 general——churn + 覆盖用户后编辑）；registry-load 也过 `repair` 不变量；自定义 configPath（测试注入）永不切面 |
| `src/main/scala/nebflow/service/ConfigService.scala` | provider 删除/改名链清洗（B2 不变量）**双面**等价落地：legacy `presets` 槽 + 注册表 `chains` 槽（`mapPresetChains` 按槽位名参数化），迁移不产生清洗死角 |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | 启动序列接 `ModelChainMigration.migrateLegacyPresetRefs()`（seed/llm.model 两腿之后，try/catch 不阻断），替换「⚠ 未接腿（零生产调用点）」批注——该批注担忧的「封存拆掉 SubTask preset 面」已由 PresetStore 切面根除 |
| `src/main/scala/nebflow/core/tools/SubTaskTool.scala` | 注释锚点（preset 参数解析面说明），代码路径经 PresetStore 切面自动获得迁移语义 |

## 件二 · general 预设多腿化（本机实例，已执行 09:26）

**改前 → 改后**（`~/.stand`，均留备份，原子写）：

| 文件 | 改前 | 改后 |
|---|---|---|
| `model-presets.json` → `presets.general` | preferred `zhipu/glm-5.3-flash`；fallbacks `["kimi/kimi-for-coding"]` | fallbacks `["kimi/kimi-for-coding", "kimi/kimi-for-coding-highspeed"]`（头与 description 不动） |
| `model-chains.json` | 不存在 | 预写 version 1 + `migratedAt=2026-10-30T09:26:04+08:00` + 同一条 general 链（重启后迁移腿封存原文件时的 write-ahead 目标；运行中旧码不读它，零风险） |
| `model-presets.json.bak-20261030-preset-chain-migration` | — | 改前完整字节备份 |

**健康候选清单**（registry 实读 `~/.stand/nebflow.json`）与取舍：zhipu/glm-5.3-flash（头）；kimi/kimi-for-coding（任务书点名主后备）；kimi/kimi-for-coding-highspeed（**补第三腿**：同族 highspeed 变体，不引入新成本画像）；kimi/k3.1-pro-highspeed（**未收**：pro 档，成本/时延画像不同，留作者裁决）；cmdcode/deepseek/deepseek-v4.1-flash（**不收**：provider `disabled:true` 厂家 key 失效，见 docs/20261030_cmdcode-provider-removal.md）。

**热读结论（分面，证据落行）**：
- **preset 链面 = 改完即生效，无需重启。** `PresetStore` 类注「reads the file fresh on every call … without restart」，`resolveExplicit` → `load()` → 每次 `os.read`（PresetStore.scala）；工具 schema 逐 LLM 调用重建（PresetCatalogInToolDocSpec「dynamic: rendering path re-reads the store」钉死）。运行引擎（旧码）只读 `model-presets.json` ⇒ 本批热改即刻生效。
- **封存 + 注册表读面 = 待重启生效。** 新码路径（启动迁移腿 + PresetStore 切面）随下一次引擎重启自然接管；本批**故意不手工封存** `model-presets.json`——旧码 `PresetStore.load()` 在文件缺席时会从 providers 重新播种单腿 general，手工封存等于拆运行实例的 preset 面。重启时迁移腿发现原文件仍在 ⇒ 幂等合并（既有恒胜）⇒ 加戳封存。

## 件三 · Spec 读数

**定向面（本批改动直接触及）——全绿**：
- `ModelChainMigrationSpec`（含新增 3 例：注册表落盘 + 旧名兼容经 `PresetStore.resolveExplicit` 与 `PresetResolver` 两腿断言 SubTask 参数面；已迁移 home 零 churn——ensureDefaultPreset 不重建 legacy、repair 写回注册表；write-ahead 重放既有恒胜 + 迁移完成戳不重写）+ presets 全家 + PresetResolver + PresetCatalogInToolDoc + ConfigServiceSpec + GeneralChainFallbackSpec + ProviderChainDepthSpec = **117/117（53 + 64）Passed, 0 Failed, 0 Errors**。
- `GeneralChainFallbackSpec`（新，生产同形 general 链）：**T1** 端到端换链——头 mock 以 zhipu 真实死法族（429 + code 1308 配额）应答 ⇒ 答案由 kimi-for-coding 交付、头至少被尝试一次（失败→换链非绕过头），本地 Anthropic mock 零外部 API；**T2** 候选序 `[zhipu 头, kimi-for-coding, kimi-for-coding-highspeed, …储备层]`，序即 failover 语义。

**全量（合并闸门）——8248 总 / 8201 过 / 42 红 / 0 Errors**（首跑 OOM Metaspace 属机器内存面——生产引擎 2.6GB RSS 同机 + sbt 测试 JVM 类加载撑爆——`-J-XX:MaxMetaspaceSize=1536m` 复跑完成）：

**42 红全部归因在本批改动面之外**。对撞证据：干净 main worktree（detached `000637d84`，零本批代码）重跑其中 8 个 suite → **同样 10 红、同断言同消息**（UserTextGateSpec 2 / ProviderCommandSpec H6 2 / ConfigEditHeadlessSpec H9 1 / HealthRoutesSpec 1 / DataRootPlaceholderSpec 1 / NfFileDenyLogSpec 2 / PasteAttachmentGuardSpec 1）= main 尖存量 spec 债；其余（UpdateOrchestratorSpec/HotRestartSpec 30s 超时族、SingleInstanceGuardSpec ForeignOccupant 端口被占、DetachedApplySpec launchctl uid、NeblinkPresence 188s 时序）为机器重载下的环境波动——同一 HotRestartSpec 在轻载对照中即绿。零新增红 ⇒ 按仓惯例「存量红不动、开放问题入报告」推进合并，存量债登记为开放项。

## 合并

- 合并前 fetch；撞 MERGE_HEAD 检查（一次性，无轮询）。
- 合并动作与冲突面：见本文件末节补记（合并后回填 commit）。

## 偏离申报

1. 全量闸门非字面全绿（main 尖存量 42 红）——以「干净 main 对撞同红」作归因证据，零新增红推进；未擅自修存量 spec（纪律：只报不修）。
2. 任务书称「general = zhipu 单腿」为摘除批前旧状；本批接手时已是双腿（cmdcode 摘除批所留），本批在其上补第三腿至三腿。
3. 实例配置改动只落 `~/.stand` 两个 JSON + 备份，未入 git（仓惯例：实例数据面不入仓）。

## 开放项

1. `kimi/k3.1-pro-highspeed` 是否进 general 后备（pro 档画像，作者裁决）。
2. Nebula `/model` 主链（全员跟随）是否同步补第三腿——本批未动（已是双腿）。
3. main 尖 42 存量红清欠（对撞证据已备，见 /tmp/fulltest-acc72f03.log 与 /tmp/maincheck-specs.log）。
4. 引擎下次重启后观察启动日志 `model-chain migration:` 系列确认封存戳落地（严禁重启约束下以 spec + 幂等设计担保）。
