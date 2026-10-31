---
type: design-spec
status: implemented
title: 液体玻璃 v3 · 两族分场景按钮重设计（效果图呈批稿）
created: 2026-10-31
author: stand (delegate-nebflow-9e155df5)
mockup: "[[liquid-glass-effect-mockup-v3.html]]"
supersedes: "[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]"
tags: [design, visual, liquid-glass, v3, buttons]
---

# 液体玻璃 v3 · 两族分场景按钮重设计（只设计不实施，待作者点 variant）

> **状态：implemented（2026-10-31 v3 实现批）**。作者 12:32 点选（逐字）：
> 「用F1 然后按钮的配色风格使用简约的stand 风格吧 然后主对话窗口的发送按钮 改回之前的绿色」。
> 落地摘要见文末 §5 实施记录；回归证据 `evidence/20261031_liquid-glass-v3-impl/`。

配套效果图：[[liquid-glass-effect-mockup-v3.html]]（亮/暗双版 · 悬浮族 3 变体 + 控件族 3 变体 + 同屏混排示意图）。
截图证据：`evidence/20261031_liquid-glass-v3-mockup/`（v3-A/B/C 六张 PNG）。
前版：[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]（v2，已上线，按钮被作者否）。
关联修复批：头像 + agent 名恢复（作者 10-31 11:38 令②，见本批 fix 与回归，不在本稿范围）。

## §0 作者裁定（本稿依据，逐字摘要）

- 11:38 —— ① 上线版（v2）按钮仍未达预期，重新设计；先出效果图呈挑，点中哪个改哪个。
- 11:38 —— ② 主对话中间的头像 + agent 名被顶缘渐隐改造误删 → 恢复（另条修复批，已修）。
- 11:43 —— 按钮按场景分族，不全场一种：**悬浮按钮 = 液态玻璃**；**按钮控件（发送/确认/列表内按钮等）= Apple 扁平**（纯色/微圆角/无高光无模糊，System Blue 档）；A 节变体数量照原令（悬浮族 2–3 + 控件族 2–3）；另给同屏混排示意图。

## §1 悬浮族 · 液态玻璃（三变体，差异肉眼可辨）

| 变体 | 一句话 | 与 v2 的差异轴 |
|---|---|---|
| F1 轻霜全透 | App Library 式最透：填充 .30、blur 20/sat 2.2、hairline 亮边 | 大幅降填充、提 blur/sat、收高光带 → 近亮参考图 |
| F2 深灰玻璃芯+亮边 | 深透芯 .44 压花底 + 亮边 .55 + 外圈光环 + 白字 | 换深色芯、加亮边光环、白字 → 暗参考图 Select 感 |
| F3 厚玻璃·强高光 | 高填充 .68 + 双层边 + 宽高光带 55% + 大浮影 | 全面加厚 → 控制中心质感 |

## §2 控件族 · Apple 扁平（三变体，System Blue 档）

C1 实底主钮（`#007AFF`/`暗 #0A84FF` 白字）· C2 浅底蓝字（`#E8F0FE`/`暗 #16283D`）· C3 灰阶中性（`#ECECEE`/`暗 #2C2C31`）。
族纪律：纯色 / 微圆角（sm8/md10/lg12）/ 无高光 / 无模糊 / 无浮影；按压 = 色深一档（无玻璃式弹缩）。

## §3 同屏混排原则

玻璃浮在内容边缘（顶缘圆钮、输入条本体），扁平坐在内容里面（发送、卡片动作、列表小钮）；
玻璃保持中性色，System Blue 只出现在内容区控件上——两族同屏不打架。

## §4 落地映射与下一步

拍板后开实现批：悬浮族 → `--g2-fab-*` token 三态化（现 v2 单档升级为 F1/F2/F3 中选一档或分场景两档）；
控件族 → 新增 `--ctl-*` token 族，发送键（`#send-btn`）由墨玻璃药丸换代为 C1 扁平实底；
`cfg-btn` 族、卡片/列表内按钮同走控件族。配方全表与界面件映射见 mockup §D。

## §5 实施记录（v3 实现批，作者 12:32 点选后落地）

| 点选项 | 落地 | 与样张原案的差异 |
|---|---|---|
| 悬浮族 = F1 | `--g2-fab-*` 全量升 F1 值（stand-tokens.css）；liquid-glass.css 组装改 F1（单层 0 10px 26px 投影、高光 @40%、去底缘层）；扩面到全部悬浮钮 = 手机顶缘任务/搜索圆钮（g2-fab 族）+ ask-dock 开合钮 `.ask-fab` + canvas 圆钮族（#deck-fab / source / ref / find / graph / refresh，liquid-glass.css ④ 块） | 无差异（F1 双档逐值照 §D） |
| 控件族配色 = 简约 Stand 风 | 新增 `--ctl-*` token 族（stand-tokens.css）：主钮 = 墨族单源（`--btn-ink-*` 引用），次钮 = C3 中性灰（亮 #ECECEE 系 / 暗 #2C2C31 系）；sapphire.css Pattern B 全 roster（cfg-btn / config-actions / option / epub / code-copy / modal cancel 等）玻璃底材 → C3 扁平实底（无 blur/高光/浮影，按压 = 色深一档无弹缩）；cfg-btn 次档退出 g2 玻璃族，cfg-btn-add 退幽灵虚线档 | 样张（= [[liquid-glass-effect-mockup-v3.html]]，供作者点选的效果图演示稿）System Blue 档被作者改点：扁平形态保留、配色换成墨族单色（设计稿 §2 C1–C3 不实施） |
| 主对话发送钮改回绿色 | `#send-btn` 恢复 10-30 墨族换代前微信绿考古值（git `c91de8456^`：rest #07C160 / hover #06AD56 / active #059A4C，白字，1px 深绿缘），token 化 `--ctl-send-*`；v2 药丸几何保留、材质改 C1 扁平（spring 弹缩与镜面高光层退役） | 无差异（历史色逐值，非样张蓝色发送档） |
| 其它面板发送钮跟随主对话 | sapphire.css 发送键族 `:is(#dropbox-send-btn, .cfg-btn.fm-send-btn, button.fm-verify-send, button.fm-send-chip, button.fm-create-submit)` 同走 `--ctl-send-*` 绿三档（玻璃 blur/投影反馈层退役）；ask-dock `.ask-send` 同恢复考古绿（ask.css） | 族几何（r10 芯片 / 32px 圆）各自保留，只随配色 |

未动面（维持 v2/既有）：输入条本体 `#input-bar` 与顶缘渐隐带 `#g2-edge-fade`（v3 §D 映射表原案即「维持 v2」）；
墨族主操作钮体系（10-30 批 `--btn-ink-*`，本批即「简约 stand 风」所指）；focus ring 蓝（10-30 裁定：蓝 = focus/链接专用）；
掉线灰 / 禁用灰玻璃档语义（09-15 作者裁定「正常绿 / 掉线灰」）。

证据：`evidence/20261031_liquid-glass-v3-impl/`（双端双主题截图 + computed-style 断言：发送钮绿三档、F1 悬浮 token、中性次钮、族键跟随）。
