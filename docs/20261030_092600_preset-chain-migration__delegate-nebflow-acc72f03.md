---
type: design
status: current
date: 2026-10-30
scope: preset→model-chain 启动迁移腿 + general 预设多腿化（本机实例已落配置）
---

# preset→model-chain 启动迁移批：设计、落点与实例配置（2026-10-30）

> 作者令 2026-10-30 08:29 拍板「做」。前置事实面：/model 已是运行时 fallback 锚
> （[[model-chain-anchor-spec.zh-CN]]，`1d4f68aba` 落 `registry.scala`
> `policy.resolveModel(agent, policy.nebulaChain())`）；cmdcode 死 key 摘除见
> [[20261030_cmdcode-provider-removal]]。本批把最后的 preset 旧面（`model-presets.json`
> 目录 + SubTask `preset` 参数）迁到 model 链形态，并给生产 general 预设上多腿。

## 1. 迁移形态：`model-chains.json` 链注册表

preset 定义的目标形态 = **命名链注册表** `~/.stand/model-chains.json`：

```json
{"version":1, "migratedAt":"<iso 迁移完成戳>", "defaultPreset":"general",
 "chains": {"general": {"name":"general","description":"…","preferred":"zhipu/glm-5.3-flash","fallbacks":["kimi/kimi-for-coding","kimi/kimi-for-coding-highspeed"]}}}
```

- 旧 preset 名**逐字随迁移带走**（无别名表）⇒ SubTask `preset` 参数旧名兼容是结构事实，不是映射表；
- 写面全部 `AtomicJson`（UUID tmp + ATOMIC_MOVE）；读面 fail-soft（缺失/坏文件 ⇒ None，退回 legacy 面）。

## 2. 启动迁移腿（幂等，write-ahead）

`ModelChainMigration.migrateLegacyPresetRefs()`（原实现自 model-chain anchor 批，全树零调用点）本批接线进 `GatewayMain` 启动序列（seed / llm.model 两腿之后，失败不阻断启动）：

1. **角色 preset 引用迁移（无条件，与旧行为一致）**：Nebula 的引用变自身 `model` 链（目录可读则逐字、LowCost 不可读落内置默认链、其余不可读 key-removal）；project-dispatcher / executor（含 retired kernel/general 拼写）的 `preset` 键摘除（跟随者语义）。
2. **preset 定义 → 链注册表（write-ahead 先行，对齐 R5 先例 `c30e6945c`）**：先 `ModelChainRegistry.mergeChains` 合并落盘，后封存原目录文件；进程死在两者之间 ⇒ 二次启动重放合并（**既有条目恒胜**——注意 Scala `Map ++` 右侧优先，既有表必须放右侧，本批 spec 钉死这条）。
3. **封存 = 迁移完成戳**：`model-presets.json` → `model-presets.json.sealed-<yyyyMMdd'T'HHmmss>`（原字节保留可审计）；二次启动原路径缺席 ⇒ 全腿 no-op（幂等的另一半）。

## 3. 读面切换：PresetStore 默认路径切注册表

`PresetStore.load/save` 在 `ModelChainRegistry.activeFor(configPath)`（默认路径 + 注册表实存）时读写 `model-chains.json`：

- SubTask `preset` 参数（`PresetResolver.applyPreset(PresetStore(), …)`）、工具 schema 目录行（`catalogLines`）、`resolveExplicit` 自描述错误面**单源**切到迁移后的链——不存在「工具一套、设置一套」分叉；
- repair/ensureDefaultPreset 在已迁移 home 上写回注册表，**绝不重建**被封存的 `model-presets.json`（否则二次启动会把 seed 单腿目录又迁一遍——churn + 覆盖用户后编辑）；
- 自定义 configPath（测试注入临时目录）永不切面，legacy 语义原样；
- `ConfigService` 的 provider 删除/改名链清洗（B2 不变量）**双面**等价落地（legacy `presets` 槽 + 注册表 `chains` 槽），迁移不产生清洗死角。

## 4. 本机实例配置（~/.stand，已执行 2026-10-30 09:26）

| 文件 | 改前 | 改后 |
|---|---|---|
| `model-presets.json` → `presets.general` | preferred `zhipu/glm-5.3-flash`，fallbacks `["kimi/kimi-for-coding"]` | fallbacks `["kimi/kimi-for-coding","kimi/kimi-for-coding-highspeed"]`（头不变，description 不动） |
| `model-chains.json`（新建，预写） | — | version 1 + `migratedAt` 戳 + 上表同一条 general 链（重启后迁移腿封存原文件时的 write-ahead 目标，幂等兼容） |
| `model-presets.json.bak-20261030-preset-chain-migration` | — | 改前完整备份 |

**健康候选清单与取舍**（registry 实读 `~/.stand/nebflow.json`）：zhipu/glm-5.3-flash（头）、kimi/kimi-for-coding（主后备，任务书点名）、kimi/kimi-for-coding-highspeed（补第三腿：同族 highspeed 变体，额度耗尽时同账户未必可换——但与 k3.1-pro-highspeed 相比不引入 pro 档成本/时延画像）；kimi/k3.1-pro-highspeed（**未收**：pro 档，留给作者裁决）；cmdcode/deepseek/deepseek-v4.1-flash（**不收**：provider `disabled:true`，厂家 key 失效，见 [[20261030_cmdcode-provider-removal]]）。

Nebula 自身链（`/model` 主链，全员跟随）本批**未动**：已是 zhipu + kimi-for-coding 双腿；是否同步补 highspeed 第三腿留作者裁决（开放项）。

## 5. 热读结论（分面）

- **preset 链面（SubTask `preset` 参数、工具目录）= 改完即生效，无需重启。** 证据：`PresetStore` 类文档明确「reads the file fresh on every call … without restart」（`PresetStore.scala` 类注），`resolveExplicit` → `load()` → 每次 `os.read`；工具 schema 逐 LLM 调用重建（`PresetCatalogInToolDocSpec`「dynamic: rendering path re-reads the store」钉死）。运行中的引擎（旧码）读的是 `model-presets.json` ⇒ 本批对它的热改即刻生效。
- **封存 + 注册表读面 = 待重启生效。** `model-chains.json` 预写对运行进程零影响（旧码不读它）；`ModelChainMigration` 启动腿与 `PresetStore` 切面都是新码路径，下一次引擎重启自然接管：迁移腿会发现 `model-presets.json` 仍在（本批**故意不手工封存**——旧码的 `PresetStore.load()` 在文件缺席时会从 providers 重新播种单腿 general，手工封存等于拆运行实例的 preset 面），幂等合并后加戳封存。
- provider 面热读先例（`reloadConfig`）不受影响，本批未动 `nebflow.json`。

## 6. 验证面（spec 级，零外部 API）

- `ModelChainMigrationSpec`：迁移表 / 幂等（二次启动零文件变化）/ 降级目录 / dangling 引用 / 非角色不动 + 本批新增：注册表落盘与旧名兼容（经 `PresetStore.resolveExplicit` + `PresetResolver` 两腿断言 SubTask 参数面）、已迁移 home 零 churn（ensureDefaultPreset 不重建 legacy 文件、repair 写回注册表）、write-ahead 重放既有恒胜；
- `GeneralChainFallbackSpec`（新）：生产同形 general 链（zhipu 头 + kimi 双后备）——T1 端到端换链：头 mock 以 zhipu 真实死法族（429 + code 1308 配额）应答 ⇒ 答案由 kimi 后备交付、头至少被尝试一次；T2 候选序 `[zhipu 头, kimi-for-coding, kimi-for-coding-highspeed, …储备层]`（复用 QuotaChainSwitchSpec 的本地 Anthropic mock 台，零外部 API）。

## 7. 开放项

1. `kimi/k3.1-pro-highspeed` 是否进 general 后备（pro 档成本/时延画像，作者裁决）。
2. Nebula `/model` 主链是否同步补第三腿（本批未动）。
3. 引擎下一次重启时观察启动日志 `model-chain migration:` 系列确认封存戳落地（本批因严禁重启未实测启动腿在真实例上的日志，以 spec 覆盖 + 幂等设计担保）。
