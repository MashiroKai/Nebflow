---
type: batch
status: final
date: 2026-11-01
topic: 手机端联系人页顶「手机接入」入口撤除批重派核验（原批零产出定论证伪 + main tip 回归全绿）
---

# 「手机接入」入口撤除批·重派核验（2026-11-01）

关联：作者令 2026-10-31 22:55（手机端联系人页顶「手机接入」行/卡撤除，仅手机端；桌面同入口不动；连带清专属样式/i18n）；原批产物 commit `c77836cdf`；待办改版批 commit `e51dabcdd`（设计稿未落 docs，D14/D14b 存量红来源）；dock 消息徽标面 [[20261030_084500_dock-message-badge__delegate-nebflow-12dfb591]]（D14/D14b 所钉的徽标判据面）；证据目录 `evidence/20261031_phone-access-entry-removal/`。

## 重派前提澄清：原批并非零产出

派单定论「原批 10-31 深夜死于供应商掉线，零产出」与仓内事实不符：原批在掉线前已完成并提交 `c77836cdf`（2026-10-31 23:44:34，撤除 + 样式清 + D6 换代 + 截图取证），且已经合并队列于 `c7b3bcdc6` 落入 main。本批重派的实际工作 = 在 main 现位（c29b35be7）复核撤除全链 + 证据重拍 + spec 复绿，非重新实现。

## 核验结论（main tip c29b35be7）

1. **撤除链完整**：`socialFaces.js` 无 `ensureSocialEntry`/`.mf-social-entry` 代码（仅历史注释）；`navController.js` social 详情页六腿全无（PAGES 注册/收编/内容分派/弹栈/CLOSE_WATCH/事件监听，仅历史注释）；`mobileLayer.css` 三条 mobile 伴生死样式零残留（现存 social-modal/social-overlay 字样均为历史注释）。联系人页顶 = 新的朋友/设备/发起群聊，无「手机接入」行。
2. **i18n 无孤儿**：入口行原实现为内联 i18n（随 PAGES 注册同撤）；locales 的 `social.*` 键族属桌面 `#social-overlay`（仍在用，`socialChannels.js`），按「无他处引用时才清」判据不清。
3. **桌面同入口零改动**：`#social-btn`（activityBar/index.html）在位，点按 `#social-overlay` 正常弹出社交接口面（微信 iLink/飞书渠道卡）——三张重拍截图为证。
4. **spec 全绿**：`tests/mobile-dock.spec.mjs` 21/21（D6 撤除判据在列）；`tests/phone-access-removal-shots.mjs` probe 全绿、零 console error。

## 本批唯一代码改动：D14/D14b 存量红修复（测试判据收窄）

main tip 上 mobile-dock 19/21：D14/D14b 失败，根因是待办改版批（e51dabcdd）在 `#fm-conversations` 顶加「待办事项」入口行（`scheduled-task.js buildTodoEntryRow`），该行自带**恒在 DOM** 的 hidden `.fm-row-badge`（无待办时 `hidden` 但节点在）；D14/D14b 的页级宽选 `.mobile-page[data-page="messages"] .fm-row-badge` 将其误计（expect 1 得 2 / expect 0 得 1）。修复 = 判据收窄到 `.fm-conv-row .fm-row-badge`（会话行徽标面，与待办行「不挂 `.fm-conv-row`」的定位面约定正交），断言语义不变；spec `mobile-dock.spec.mjs` 三处（D14 evaluate + D14b count/text）。与撤除批产物无因果（撤除面 D6 全程绿）。

## 证据

`evidence/20261031_phone-access-entry-removal/`：三图 2026-11-01 08:17 于 main tip 重拍覆盖；probe 明细见该目录 README「重派核验重拍」节。

## 遗留

无。本批交付后 main tip spec 面回归全绿；`tests/tmp-verify-friends.mjs:332` 的 `#fm-conversations .fm-row-badge` 首匹配语义同受待办入口行影响，但属 tmp 前缀验证脚本（非 spec 面），不在本批范围，留观。
