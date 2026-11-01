---
type: fix-report
status: done
batch: 手机端 AskUser 白屏修复批（作者 10-31 15:38 截图令）
tags: [mobile, askuser, chat-top-clip, mask, 白屏]
---

# 手机端 AskUser 卡白屏修复批（2026-10-31 作者 15:38 截图令）

关联：[[20261031_owner-lost]] 同日批次；桌面顶带契约见 `css/chat.css`「顶部消失点批」（2026-10-22）。

## 症状（作者实拍截图）

手机端（微信 webview，iOS）存在 AskUser 问题时：消息区上方一大段白；ask 卡只剩
「取消/确认」浮在半空，题干/选项区整体不可见。

## 根因

`#chat` 顶部滚动遮罩的锚点变量 `--chat-top-clip` 由 `main.js` 顶带观察器按
`header.top − chat.top` 写入（桌面：内容在 Header 顶缘处消失、穿玻璃下缘而过）。

手机层（`body.mobile-layer`）下桌面 `#header` 是 `display:none`（液体玻璃 v2 批
顶栏退役，[[design/20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]] 语义），其 `getBoundingClientRect()` 全零、
无锚意义。此时任何让 `#chat` 盒**瞬态上移**的读数——iOS 固定层橡皮筋回弹、键盘
位移等（`getBoundingClientRect` 含 transform）——都会把 clip 写成数百 px 的垃圾
值；而观察器只挂在 header/overlays 的 resize 上，瞬态结束后没有重测来源 ⇒ 垃圾
值持久化。`#chat` 的 mask 随变量把消息区顶部整段裁没：mask 区元素全透明、露出
页面底色 = 均匀「白屏」；AskUser 大卡（题干+选项 ~460px）被整段裁没，只剩裁切线
之下的按钮行 = 作者所见「取消/确认浮在半空」。

取证（双引擎复现 + 作者会话真实载荷）：`evidence/20261031_mobile-askuser-whitescreen/`

- `askuser-mobile-garbage-clip-light.png` —— 强写 `--chat-top-clip:390px` 即 100%
  复现作者截图（胶囊行 → 整段白 → 卡底按钮行）。
- `askuser-mobile-fixed-light.png` / `askuser-mobile-fixed-dark.png` —— 修复后
  双主题手机视口：题干/选项完整渲染，双待办卡正常。
- 作者会话 `.ui.json`（`~/.stand/sessions/d7c3cb54-…ui.json`）ask 行 #783
  `asknb-35237e423cfb4d8c` 实载荷重放，确认与卡片 DOM 结构无关。

## 修复（两件）

1. `js/main.js` `syncTopReserve`：手机层 clip **恒 0**——顶缘渐隐带
   `#g2-edge-fade` 自带「滚入渐隐」（先糊后没）语义，`#chat` 顶部不再需要
   mask；桌面公式逐字不变。并把 `#g2-edge-fade`（bandBottom 成员、随手机层
   显隐）挂进同一 ResizeObserver：层翻转/带显隐后必有一拍重测，不依赖 resize
   时序巧合。
2. `css/chat.css` `.option-confirm:disabled`：作者截图核对「确认钮淡显」——
   半透绿禁用档是既有设计语言（空输入闸语义，**非 bug**），但白字压 30% 绿底
   对比度过低（洗白薄荷、字不可读）。字改绿深档 `--ask-green-deep`（两档恒定
   token），tint 0.30→0.35：gate 语义与可读性兼得。

## 验证

- 新回归钉 `tests/mobile-chat-topclip.spec.mjs` 4/4（双主题 × C1 初始零值 /
  C2 垃圾瞬态重测归零 + mask 机制在场）。
- `uifix-glass` 29/29（桌面 clip 公式 preserved：clip=16=header.top−chat.top；
  手机 G7 clip=0）。
- `mobile-topline-clear` 3/3、`askuser-expandfix` 31/31、
  `askuser-badge-mirror/conditional/bubblename/dup-card` 全绿、
  `mobile-dock + mobile-msg-longpress + askuser-answer-source +
  askuser-history-attach` 41/41（一批次内 T1 有一次计时 flake，孤立重跑
  HEAD 与本树均 5/5）。
- `build-web` 绿（201 sources orphans 0）；checkJs 门因 worktree 无
  node_modules/typescript 环境性跳过（main.js 全 app 引导零页错误实证）。

## 遗留 / 边界

- 手机端待办条（#ask-pending-bar）悬浮于消息区上方、内容可滚入其下（透玻璃
  看到糊影）——与桌面「带 in-flow」契约不同，为手机层既定形态；本次未动。
- 瞬态垃圾值的写入路径已被手机层 clip=0 关闭；桌面 `#header` 始终在场，无此
  失效面。
