---
type: design
status: implemented
---

# output 块纪律渠道接线批：微信 iLink / 飞书出站剥离（2026-11-01 作者令）

## 裁定（作者令原话铸牌）

「微信端和飞书端收到的消息也要做 output 块剥离，只显示 output 块内部的
内容。」附四点要求：单一实现源（渠道侧不做第二套解析）；语义与控制台逐字
对齐（只投块内拼接、块外不出站、无块不回退全文）；代码围栏安全（``` 围栏
内的字面 `<output>` 不得触发剥离——「块形态当初就是为了避开 md 语法冲突，
提取器不能反被围栏骗了」）；单测 + 管线级测试证明出站 payload 只含块内内容。

## 出站路径清点（逐条，别漏腿）

仓内渠道出站腿 = `nebflow.bridge.BridgePlugin` 的全部两个实现，逐点核过：

| 渠道 | agent 回复文本出站点 | 本批接线 | 引擎文本（不剥） |
|---|---|---|---|
| 微信 iLink | `WeixinIlinkBridgePlugin.flushSegment`（段推送）、`flushTurnEnd`（轮尾文字腿 + 图文同发 caption） | ✔ | ask 卡、拉取提示、失败注明、降级线、死信补投（补投的是已剥文本） |
| 飞书 | `FeishuBridgePlugin.flushSegment`（段推送）、`done` 臂文字腿 | ✔ | ask 拒答/取消/回执线、失败注明、`sendTextFallback`、`sendVisibleLine`、rich 降级线 |
| telegram / wechat 公众号卡 | **无实现**（仅 SocialChannels 卡槽注册，无 BridgePlugin 代码） | — | — |
| FeishuOutboundAdapter / RichFeishuAdapter | rich 媒体腿（图片/文件/降级文本），不承载 agent 回复正文 | — | — |

剥离只挂在 **agent 回复文本** 的入口；引擎生成的提示线（无块、必须送达）
不经剥离——若挂在共享 `reply`/`deliverText` 缝上，失败注明等引擎文本会被
「无块 ⇒ 不投递」规则吞掉，这是把剥离钉在入口而非传输缝的原因。

## 单一实现源与语义

新增 `nebflow.social.outbound.OutputBlock`（纯函数，零 IO）：渠道侧唯一
提取实现，两渠道同源。与控制台面（`web/js/utils.js`）逐字对齐的三态判定：

| 判定 | 条件 | 段推送腿 | 轮终腿 |
|---|---|---|---|
| `Extracted(inner)` | 围栏外 ≥1 成对块 | 弹窗，只推块内拼接（空块消费不推） | 只投块内拼接（空 ⇒ 文字腿熄灭） |
| `NoBlocks` | 围栏外零标签 | 弹窗，不推（**不回退成发全文**） | 文字腿熄灭，纯媒体轮照发 |
| `Unbalanced` | 标签不成对（流式开标签/散签/嵌套） | **HOLD**：不弹窗不推——块可能在后续 delta 闭合，此刻弹出即永远丢失 | 无可投递，文字腿熄灭 |

- 块内拼接 = 每块 body trim 后按出现序以 `\n\n` 连接（含空块占位，与控制台
  `extractOutputInner` 逐字节同形）。
- HOLD 是控制台流式纪律「不炸不吞」的渠道面：判定在 `Ref.modify` 内原子完
  成（并发 delta 与弹窗互不吞段；pacer 任务只在 modify 结果上运行一次）。
- caption（图文同发）与轮尾文字腿吃同一个剥离值（`visible`），剥离先于
  `OutboundText.toPlain` 去标记化，两层次序与既有管线一致。

## 围栏安全（控制台同步升级，两侧同一条规则）

掩码扫描（`OutputBlock.maskFences` / utils.js `_maskCodeFences`）：``` 围栏
区逐字符替换为 NUL（换行保留 ⇒ 偏移与原文 1:1）；开栏 = trim 后以 ```
开头，关栏 = trim 后仅 ≥3 反引号（CommonMark；同 ```human 闸读法），未闭合
跑到底。围栏内字面标签：不触发剥离、不入平衡闸、不吃散标签转义（转义会
腐蚀围栏体）。控制台 `utils.js` 同批升级（`_outputTagsBalanced`/`extractOutputInner`
/管线 pass 全走掩码），修复原提取器会被围栏欺骗的缺陷——outputTag.js
/chat/persistence 各面经单一 `extractOutputInner` 自动同享。语义基线 =
[[20261030_human-readable-presentation.zh-CN]] §11 的单管线五面。

## 改动面

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/social/outbound/OutputBlock.scala` | 新增：单一提取源（三态判定 + 围栏掩码） |
| `src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala` | `flushSegment` / `flushTurnEnd` 接线（HOLD 语义、caption 剥离） |
| `src/main/scala/nebflow/social/FeishuBridgePlugin.scala` | `flushSegment` / `done` 臂接线（同源同语义） |
| `src/main/resources/web/js/utils.js` | 围栏安全升级：`_maskCodeFences`/`_outputTagsBalancedOn` 新增，`_outputTagsBalanced`/`extractOutputInner`/管线 pass 走掩码 |
| `src/test/scala/nebflow/social/outbound/OutputBlockSpec.scala` | 提取器判定表：无块/单块/多块/围栏含标签/空块 + 散标签族 |
| `src/test/scala/nebflow/social/OutputBlockChannelWireSpec.scala` | 管线级：两渠道真 `onAgentEvent` 管线 + 录制 send 缝，断言出站 payload 只含块内拼接、无块轮零出站、跨段块 HOLD 后一次成投 |
| `scripts/test-output-block.mjs` | JS 侧单测（真 utils.js 源提取，test-model-badge.mjs 同形）：同一判定表 + 掩码契约 + 管线围栏接线钉 |

## 验证

- `node scripts/test-output-block.mjs` — ALL PASS。
- sbt `testOnly` OutputBlockSpec / OutputBlockChannelWireSpec / WeixinIlinkBridgeSpec /
  FeishuBridgePluginSpec / WeixinOutboundSpec / WeixinOutboundLogSpec — 合入前
  以运行结果为准（见合并报告）。

## 边界与不做

- 引擎改动需重启生效——本批只管合入，重启由根会话统一安排。
- 控制台气泡面的「无块旧内容照常全显」降级语义**保留**（吞历史消息是控制台
  明令禁止的降级面）；渠道侧无块 ⇒ 零投递是作者令的显式新规则，两面向上
  不互改。
- 围栏规则只认 ``` 行级围栏（与控制台/`toPlain` 同一读法）；行内单反引号
  code span 内的字面标签不在本批范围（控制台行为不变，两侧仍一致）。
