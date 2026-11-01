---
type: report
status: done
related: "[[20261101_193916_output-block-gate__delegate-nebflow-c7740727]]"
---

# output 块总闸·提示词面补批（组装点收口，默认关）

> 批令（作者令 2026-11-01，承接 output-block-gate 批）：「output 块相关的内容先关闭」——**提示词面也要进闸**。同一开关 `outputBlock.enabled`（nebflow.json 顶层，**缺席 = 关**）：闸关闭时，agent 提示词组装里所有要求/教 agent 写 `<output>` 块的段不再注入——任何 agent 的上下文零 output 块指令，agent 自然不写块，控制台/渠道不出现字面 `<output>` 标签。
> 图谱：[[index]] · 词表 [[glossary]]。

## 0. 结论先行

| 注入面（组装 seam） | 闸关（缺省） | 闸开（`"outputBlock":{"enabled":true}`） |
|---|---|---|
| 根会话 systemStable order-375 段（PromptSections） | 整段不进组装（条件 `isRootAgent && gate`，`&&` 短路非根会话连读数都不发生） | 今日行为：逐字 OutputBlockRule 独立段 |
| 根提示词基座 Voice bullet（BuiltinAgents） | 渲染期整行剥除（含行尾换行，其余字节零变） | 原文逐字（与总闸化前字节一致） |
| delegate brief（DelegateTool.assembleBrief） | OutputBlockRule 行不进 brief（行首换行随行剥除，行距字节零残留） | 今日行为（逐字节） |
| 节点脚注（NodeEngineContract.protocolFootnoteFor，经 NodeEngine 公开再导出） | 纪律行不进脚注（endsWith 身份锚与 verifier 附录保全） | 今日行为（逐字节） |
| subagent/workflow 步首条消息（BlockingSubagent.promptWithRule） | 铁律后不追纪律，铁律前缀 + 任务原文不变 | 今日行为（逐字节） |

**一个键三面收口**：渠道剥离面 / 前端渲染面（已合批 `7509af4d3`）+ 提示词组装面（本批）。后端读实现收拢单点 = `nebflow.shared.OutputBlockGate.enabled()`（渠道面 `OutputBlock.enabledFromConfig` 委托之——一个键，一份读实现）；前端仍走 featureFlags。热读语义三面一致：提示词是组装期产物，**闸在组装点判定**——五 seam 各自装配时刻求值（新会话 / 新派发 / 新节点即翻面，无重启门槛，隔离实例实测写键即生效）；已在飞的旧上下文不回改（下个生命周期节点自然刷新）。

## 1. 改动清单

**后端（8 文件）**
- `nebflow/shared/OutputBlockGate.scala`（**新增**）：热读单点（缺席 / 解析失败 / 类型不符 / 读盘异常 = 关，fail-safe 形态同 OB-G3）；提示词组装面从 shared 读，不依赖 social 包。
- `nebflow/social/outbound/OutputBlock.scala`：`enabledFromConfig()` 委托到共享读数（渠道面构造缝 `outputBlockGate: () => Boolean` 形态不变，行为逐字节不变）。
- `nebflow/agent/PromptSections.scala`：order-375 段条件加闸（段注补闸语义：翻闸后新装配即翻面，旧上下文生命周期节点刷新）。
- `nebflow/core/entity/BuiltinAgents.scala`：Voice 节 output bullet 抽常量 `NebulaOutputVoiceBullet`（**逐字 = 模板内该行**），`NebulaPrompt` 渲染闸感知剥除；剥除锚漂移（改模板行未同步常量）由 spec 判红。
- `nebflow/core/tools/DelegateTool.scala`：`assembleBrief` 增 `includeOutputRule` 参数（缺省 = 组装期热读；spec 判红锚保持纯函数——显式传参注入常值，与机器配置解耦）。
- `nebflow/core/project/NodeEngineContract.scala`：`protocolFootnoteFor` 增同款参数；`NodeEngine.scala` 公开再导出面同步转发 + 缺省（NodeEngineContract 为 `private[project]`，既有单参调用零波及）。
- `nebflow/core/tools/BlockingSubagent.scala`：`promptWithRule` 同款参数。

**Spec（3 改 + 1 新增）**
- `SleepPollStartupRuleSpec`：四面（brief / 节点脚注 / 步包装 / 根 Voice 接线面）× 两态全参数化（显式参数 + 临时 home 写键 OB-G3 同款形态）；闸关加断言：零 output 指令 + 行距字节零残留 + endsWith 身份锚保全 + Voice 节相邻 bullet 保全。
- `PromptSectionsSpec`：order-375 闸两态（ON = 逐字 + 段序钉；OFF = 缺省零注入 + 邻段零波及）；非根排除测试升级为**闸开下**证明 root-scoping；新增 **joined 全面断言**（`entry("Nebula")` 基座 + `buildConditionalBlocks` 拼装后 grep：OFF 全文零 output 块指令 / ON 基座 bullet 与 375 段双恢复）。
- `NebulaPromptOutputGateSpec`（**新增**）：根基座三态（ON 逐字 / OFF 整行剥除字节形态——相邻 bullet 间恰一换行 / 缺席配置 = 关 fail-safe）。
- `OutputBlockSpec` OB-G1/2/3 与 `DualModeBriefInjectionSpec` I1-I3（字节恒等判红锚）零改照绿——读数委托与缺省参数化均为行为保持重构。

## 2. 范围边界（申报）

- **插件静态内容未动**：visual-report 的 wiki-docs SKILL.md 有一处 `<output>` 标签说明——按需查阅件，**非组装注入面**（节点主动 Read 该文件才进上下文），且引擎闸不重写插件文件（内容过滤脆弱 + seed digest 漂移）。如需彻底静默，后续单独小批改 seed 插件文案。
- **主 agent 已注入的旧上下文不回改**（作者令语义）：翻闸后下个生命周期节点（新会话 / 压缩 / 重启）自然刷新。
- 前端渲染面 / 渠道剥离面已由前一批收口，本批 web/ 零改动。
- 翻闸成本提示：order-375 段在 systemStable 稳定前缀内，翻闸 = 根会话前缀缓存一次性失效（与全部 stable 段同语义，非经常性成本）。

## 3. 验证（全绿）

- Spec：8 个 spec **102 通过 / 0 失败**（PromptSectionsSpec 闸五测、SleepPollStartupRuleSpec 四面两态八测、NebulaPromptOutputGateSpec 三测、OB-G 三测回归、I2 字节恒等、ResetAudit / VoiceRouting / PopDelivery 防回退照绿）。
- 隔离真实例（临时 home + 独占端口 18473 + `GET /api/agents/Nebula` 读 live systemPrompt，PID 验身清理，端口复查 0 残留）：
  - **关态 PASS**：systemPrompt 零 `<output>` / 零 Output discipline / 零 User-visible output；`## Voice` 节与 terse bullet 保全；
  - **开态 PASS**：写 `{"outputBlock":{"enabled":true}}` → 指令恢复（bullet 原文逐字现形）——**免重启热翻面**（组装期求值实证）；
  - **回关 PASS**：删键回 `{}` → 零指令。

## 4. 回退 / 恢复

恢复今日行为 = nebflow.json 加 `"outputBlock": {"enabled": true}`（提示词面下个组装点即生效；渠道面下个 flush；前端刷新页面）。回退本批 = 删键（缺省即关）。全部代码保留，无删除面。
