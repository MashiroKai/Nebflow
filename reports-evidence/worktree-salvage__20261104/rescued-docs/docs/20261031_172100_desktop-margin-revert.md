---
type: bugfix-report
status: published
date: 2026-10-31
scope: 桌面边距还原批（#chat 水平内距 16px → 0，手机端保形不动）
---

# 桌面边距还原批（作者令 2026-10-31 17:15）

作者令：「电脑端边距不需要调整，还原成之前靠边的形态。」手机端边距不在本批范围，不动。

## 考古定位

近一两日唯一改动桌面消息区水平边距的提交：

- `5f8657cfc`（2026-10-30 冷启动批⑦，作者令「消息列表左右留白，不再贴满屏幕」）：`chat.css #chat` 水平内距 `0 → 16px`（`padding: 8px 0 36px → 8px 16px 36px`）。此前该值自 `eb8832dd2`（2026-10-22）起即为 0（靠边）。原批报告：[[20261030_211200_coldstart-batch-8fixes__delegate-nebflow-7ceafc0d]]。
- 最近三次触碰 `#chat` 规则的提交：`5f8657cfc`（水平内距 0→16）、`eb8832dd2`（顶部消失点遮罩）、`9542d73ff`（margin-top 2px→0，uifix ⑤）。

属近期改动，故整段回退，无需作者再指认。

## 改动

- `chat.css #chat`：`padding: 8px 16px 36px → 8px 0 36px`（桌面回归靠边），注释换代。
- `mobile.css ≤768px` 媒体查询内新增 `#chat { padding-left/right: 16px }`：手机端现行 16px 为共享规则继承而来，本批**不动手机端** ⇒ 由媒体查询承接保形（双 TAG 实测 390×844 恒 16px）。
- `cs1030-coldstart-batch.mjs` B4 按换代语义重锚：`=== 16` → `=== 0` 且行左缘贴 #chat 左缘（rowX == chatX）。

## 验证

- 前后对照证据脚本 `tests/desktop-margin-revert-shots.mjs`（TAG=before/after 双跑 ALL PASS）：
  - 桌面 1440×900 × 亮/暗：before 内距 16px / after 0px 且消息行贴边；
  - 手机 390×844：双 TAG 恒 16px（不动断言）；
  - console 零新增错误。
- 截图：`evidence/20261031_desktop-margin-revert/{before,after}/desktop-{dark,light}.png` + `mobile-390.png`。
- `cs1030-coldstart-batch.mjs`：29 PASS / 2 FAIL —— 2 FAIL 为 A7a（缩放条阴影 == #input-bar），pristine HEAD 对照同款同败（后续液体玻璃批漂移欠账，非本批引入）；B4 新锚 PASS。
- 回归：canvas-capsule-ring / canvas-capsule-six / canvas-cardhead-capsule / canvas-glass-unify / canvas-stack / canvas-html-zoom / msgmenu / chat-diff-canvas 全绿（见提交消息）。
