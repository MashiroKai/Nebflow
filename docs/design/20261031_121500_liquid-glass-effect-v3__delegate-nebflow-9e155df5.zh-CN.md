---
type: design-spec
status: draft
title: 液体玻璃 v3 · 两族分场景按钮重设计（效果图呈批稿）
created: 2026-10-31
author: stand (delegate-nebflow-9e155df5)
mockup: "[[liquid-glass-effect-mockup-v3.html]]"
supersedes: "[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]"
tags: [design, visual, liquid-glass, v3, buttons]
---

# 液体玻璃 v3 · 两族分场景按钮重设计（只设计不实施，待作者点 variant）

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
