---
type: report
status: done
related: "[[20261101_183000_nebula-retired__delegate-nebflow-cd7a9fbd]]"
---

# output 块特性总闸批（两面收口一个键，默认关）

> 批令（作者令 2026-11-01 逐字）：「把 output 块相关的内容先关闭，因为我现在没有体会出来有什么区别」。加配置开关 `outputBlock.enabled`（nebflow.json 顶层，**缺席 = 关**），代码零删除，闸一开两面行为全回来。
> 图谱：[[index]] · 词表 [[glossary]]。

## 0. 结论先行

| 面 | 开关关（缺省） | 开关开（`"outputBlock":{"enabled":true}`） |
|---|---|---|
| 前端渲染面（控制台气泡） | **全文直出**：块外叙述可见、`<output>` 字面标签按散标签同款转义现形、无掩码无徽标无折叠（`data-nf-output` / `data-nf-defer-output` 不落标） | 今日行为：只显块内拼接 + turn-header 角标展开原文 |
| 渠道剥离面（微信 iLink / 飞书出站） | **全量原样**：提取 / HOLD / 「不回退成发全文」整体旁路，段与轮尾按原文出站（标签随文） | 今日行为：只出站块内拼接，叙述不出站 |
| 热读 | 引擎每次渠道 flush 现读（无重启门槛）；前端实时读 `state.parsedConfig`（configData 帧即生效，旧气泡不追溯重渲染） | 同左 |

**一个键两面收口**：引擎侧读点 = `OutputBlock.enabledFromConfig`（scala，`social/outbound/OutputBlock.scala`），前端读点 = `outputBlockEnabled()`（`js/featureFlags.js`）——读同一文件同一键。

## 1. 改动清单

**后端（3 文件）**
- `src/main/scala/nebflow/social/outbound/OutputBlock.scala`：新增 `enabledFromConfig()`（热读 `outputBlock.enabled`，缺席/解析失败/类型不符/读盘异常一律 **false**，GlobalSafety/MsgTokenRegistry 同款 fail-safe 形态）与 `extractGated(text, gateOn)`（四渠道出站 seam 的唯一收口；闸关 ⇒ 原文以 `Extracted` 直通，HOLD 随之旁路）。`extract` 纯函数本体零改动。
- `src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala`：构造缝 `outputBlockGate: () => Boolean = OutputBlock.enabledFromConfig`（生产缺省热读，离线 spec 注入常值）；`flushSegment` / `flushTurnEnd` 两处 `extract` → `extractGated(text, outputBlockGate())`。
- `src/main/scala/nebflow/social/FeishuBridgePlugin.scala`：同款缝 + 同款两处收口。

**前端（2 文件改 + 1 文件注释）**
- `src/main/resources/web/js/featureFlags.js`：新增 `outputBlockEnabled()` = `state.parsedConfig?.outputBlock?.enabled === true`（缺席 = 关；实时读，consoleInputMode 同款不 latch）。
- `src/main/resources/web/js/utils.js`：① `renderMarkdownWithMath` 提取分支闸 `outputBlockEnabled() && _outputTagsBalancedOn(masked)`——关 ⇒ 全部标签走散标签转义腿（字面现形，marked 吞内容防线保留）；② `extractOutputInner` 头部闸——关 ⇒ null，`hasOutputBlock`/`applyOutputFace`/defer 标记整链熄灭；③ 渲染 LRU key 加入闸位（防翻闸后命中旧 HTML）。
- `src/main/resources/web/js/outputTag.js`：仅头注申报（全链经 `extractOutputInner` 单点，零改码随闸翻面）。

**Spec（9 文件）**
- `OutputBlockChannelWireSpec`：7 场景 × `gate ∈ {true,false}` 全参数化（gate=true = 剥离行为逐字保留；gate=false = 全量原样直出对照，含标签字面、段边界照旧切段、无 HOLD）。
- 7 个既有渠道 spec（`WeixinIlinkBridgeSpec` / `WeixinOutboundSpec` / `WeixinOutboundLogSpec` / `WeixinAskLegSpec` / `FeishuBridgePluginSpec` / `FeishuOutboundSpec` / `FeishuRichMainLinkSpec`）构造点钉 `outputBlockGate = () => true`——继续钉死剥离行为，与机器真实配置解耦。
- `OutputBlockSpec` 增 OB-G1/G2/G3：`extractGated` 两态纯函数判定 + `enabledFromConfig` 缺席/显式 true/false/类型错/坏 JSON 五态判定（临时 home + `PathUtil.setDataRoot`，测试顺序执行已由 `Test / parallelExecution := false` 保证）。

## 2. 范围边界（申报）

- **提示词面未动**（作者令范围只圈两面）：`Defaults.OutputBlockRule` 注入链（PromptSections order-375 / BuiltinAgents / DelegateTool / BlockingSubagent）照旧——闸关时 agent 仍会被要求写 `<output>` 块，用户在控制台/渠道看到的是带字面标签的全文。若要连提示词一起关，后续小批在上述四处注入点挂同一闸即可（一行一处）。
- 设置面板**未加 UI 开关**（无现成特性开关面板，按作者令「没有就先只留配置」）；开关走 nebflow.json（设置页 JSON 编辑器或手改，`PATCH /api/config` 深合并天然可写）。
- `NebflowServiceConfig` 未加类型化节：GET /api/config 与 WS configData 本就原样透传 raw JSON、PATCH 深合并天然保留未知顶层键，类型化节无消费者（msgTokens 节的三职能里前两面不需要它）。

## 3. 验证（全绿）

- `sbt compile` 干净；`Test/testOnly` 九个 spec **254 通过 / 0 失败**（含 wire spec 14 例两态、OB-G 三闸单测、全部钉住 spec 回归）。
- 隔离真实例三步（临时 home + 独占端口 18471 + playwright，种子会话 = 块外叙述 + 双平衡块 + 围栏字面标签 + 空块的复合样例）：
  - **关态 DOM 断言 PASS**：气泡无 `data-nf-output`、块外叙述/收束行可见、字面 `<output>` 可见、围栏标签未被误提取；
  - **开态 DOM 断言 PASS**：写 `"outputBlock":{"enabled":true}` 重载 → `data-nf-output=1`、只显块内、叙述与标签不可见（今日行为恢复）；
  - 双主题关态对照实拍一张（OS prefers-color-scheme 驱动，主题真实换档）：
    `evidence/20261101_output-block-gate/off-dual-theme.png`（dark | light 并排；单主题原件 off-dark.png / off-light.png 同目录）——样例消息全文直出：块外叙述可见、`<output>` 字面标签现形、围栏内标签照代码渲染，无掩码无徽标无折叠。

## 4. 回退 / 恢复

恢复今日行为 = nebflow.json 加 `"outputBlock": { "enabled": true }`（引擎下个 flush 即生效；前端刷新页面生效）。回退本批 = 删键（缺省即关）。全部代码保留，无删除面。
