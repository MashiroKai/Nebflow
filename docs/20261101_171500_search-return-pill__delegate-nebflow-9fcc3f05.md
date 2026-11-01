---
type: batch
status: done
related: "[[index]]"
---

# 搜索跳转回位胶囊批：真锚回位 + 诚实降级 + 现役胶囊家族逐字复用（2026-11-01）

> 作者令 2026-11-01（聊天记录搜索跳转回位胶囊）：现状 = 搜索→点结果→跳到那条消息；作者要的后半句 = 跳转落地后出现一颗**胶囊**，点击平滑滚回跳转前正在读的位置（作者口中「回到开头」）。四条硬要求：① 胶囊样式**逐字复用**「↓ N 条新消息」胶囊的现役组件与样式（同一颗家族，不另起炉灶）；② 行为三时机（落地出现于流下沿/点击平滑回位/消失 = 点击后·用户手动滚离后·再次新跳转替换）；③ 跳转前位置**真锚**——重放/补丁等流重建场景锚失效就诚实降级「回到最新」，不假装回到错误位置；④ 桌面+手机两端一致。验收：搜索→跳转→胶囊→回位全链路实拍（双引擎×双主题），锚点位置断言，零 console error。图谱：[[index]] · 词表 [[glossary]]。

## 0. 结论先行

- **零新 CSS、零新色**：胶囊 = 现役 `.scroll-new-pill` 同一 class（chat.css「↓ N new messages pill」段），落位公式同 utils.js `positionScrollPill`（#input-area 上缘 +12px，↓N 在场再上抬 40px 防同槽叠影）（[[#2 实现]]）。
- **真锚模型**：视口顶缘穿越行 + 行级 `data-msg-id`（缺则 duration-badge `data-ts`）二档身份 + **视口相对偏移**（rowRect.top − chatRect.top，两侧同坐标系不依赖 offsetParent 链）；点击时现场重解析，解析失败当场换挡「回到最新」（`dataset.state='latest'`，QA 断言面），点击贴底——绝不滚到假装正确的位置（[[#3 锚点契约]]）。
- **验收全绿**：双引擎（Chromium+WebKit）39/39 + 39/39；桌面亮/暗 + 手机亮/暗（390×844 独立交互层）四形态 × 2 引擎 = 32 帧实拍；锚点复原断言 |Δ|≤3px；全程零 console error（[[#4 验收与证据]]）。
- **顺带观察（非本批判据，留给作者裁）**：跨会话搜索命中的「跳转定位」本身在弹窗退场批①现役腿上是**静默丢失**的——切会话完成后用户落在另一条会话的**底部**而非命中行（scrollToMessage 首次调用时 activeSessionId 尚未切换，静默 return 后无重试链）。本批跨会话跳转因此「诚实缺席」不设胶囊（[[#5 顺带观察]]）。

## 1. 考古（消费什么）

- **现役胶囊家族**：utils.js `ensureScrollPill`/`positionScrollPill`/`syncScrollPill`（↓ N 条新消息，per-view WeakMap 记录 + MutationObserver 计数）+ chat.css `.scroll-new-pill` 段（玻璃材质/nf-pill-in 入场/hidden 态/reduced-motion）。本批只消费**样式与落位式**，不碰计数模型。
- **跳转链**：chatSearch.js `jumpToResult → scrollToMessage（文本 snippet 匹配 + data-ts 兜底 + WS 翻页驱动）→ flashRow（就近滚容器平滑居中 + search-hit-flash）`。胶囊钩点 = `jumpToResult` 开场（捕获）与 `flashRow`（落地）——两处各 +3 行。
- **滚动纪律**：msgScrollAnchor.js 的 `firstVisibleMessage`/`AT_BOTTOM_PX` 语义、msgQuote.js 的「就近滚容器、禁 scrollIntoView」、utils.js 的 B4 单探针纪律（本胶囊可见期才挂监听、隐藏即摘、不做第二份贴底判定）。
- **寻址身份**：chat.js 行级 `data-msg-id`（user/tool/ask 行诞生即挂；AI 行仅被引用后补挂）+ durationBadges.js 的 `data-ts`（AI 行终答 footer，scrollToMessage 既有兜底键）。⇒ 锚身份必须**二档**，AI 行才能真锚。

## 2. 实现（落点）

| 文件 | 变更 |
|---|---|
| `web/js/searchReturnPill.js`（新，279 行） | 全部胶囊逻辑：捕获/冻结快照/点击回位/降级换挡/可见期监听/会话切换即隐 |
| `web/js/chatSearch.js`（+9） | import + `jumpToResult` 开场 `captureReturnAnchor(res)` + `flashRow` 落地 `armReturnPill(top)` |
| `web/js/locales/zh-CN.js`（+4）/ `en.js`（+5） | `chat.backToPrevPosition`（回到跳转前位置 / Back to where you were）、`chat.backToLatest`（回到最新 / Back to latest） |

设计要点：

- **冻结快照**：`armReturnPill` 把当次捕获定格成 active 锚快照，后续捕获不回写——旧胶囊的点击永远回到**它自己**的跳转前位置；再次新跳转 = hide→re-arm（nf-pill-in 重放，「替换」语义）。
- **消失时机②的到位豁免**：落地平滑滚动用 `scrollend`（或 scrollTop 与 flashRow 目标的数值比较）判「程序滚动终结」；终结后的任何 scroll、以及 wheel/touchmove/键盘滚行键，都算用户滚离。时限（1.2s）只兜底不支持 scrollend 的引擎——**长距离跳转的平滑段可超 1.2s**，只靠时限会把「还在飞」误判成「滚离」，胶囊落地即消失（实拍阶段实测抓出并修复的真缺陷）。
- **降级换挡观察器**：childList 观察器只在胶囊可见期盯守 #chat，锚行被逐出（流重建/删除）⇒ `dataset.state='latest'` + 文案当场换「回到最新」——点击前就说真话，不做点击后才改口的马后炮。
- **QA 断言面**：`data-nf-search-return="1"`（区分符）+ `dataset.state`（anchor/latest 契约，与文案/i18n 解耦）+ `dataset.msgId`/`dataset.ts`（锚身份上墙，验收侧可独立验证「真锚 = 身份在 #chat 解析得到一行」）。
- **跨会话不设胶囊**：锚住在另一条会话的流里，切回即整树重建（resetChatForActiveSession 清空 #chat），任何「回去」都是重放不可保真的位置 ⇒ `captureReturnAnchor` 主动清锚，诚实缺席优于虚假锚点。`nebflow-session-change` 永久监听（幂等）兜底胶囊即隐。

## 3. 锚点契约（真锚）

- 捕获：视口顶缘穿越行（`rect.bottom > chatTop+1`，第一条可寻址行；身份缺位继续下探，不锚假行）+ `offsetFromChatTop = rowRect.top − chatRect.top`。
- 回位：`target = chat.scrollTop + (rowNow.top − chatNow.top) − offsetFromChatTop`（夹取到 [0, max]），把锚行带回**捕获时的视口相对位置**。折叠展开/翻页 prepend/就地替换改的都是行高与绝对 scrollTop，视口相对差值是唯一稳定语义。
- 重解析：`data-msg-id` 优先，`data-ts` 兜底（'0' 归一为无身份，tool/agent/ask 既有口径）；失败 ⇒ 贴底（回到最新）。

## 4. 验收与证据

- spec：`tests/search-return-pill.spec.mjs`（standalone，路由拦截静态文件 + mock REST/WS，零后端；`ENGINE=chromium|webkit` 可单跑）。
- 判据：P1 桌面全链路（落位 +12px/水平居中/浮层三连/真锚解析/回位 |Δ|≤3px/点击后消失/零 console error）；P2 锚逐出 ⇒ state=latest + 点击贴底 ≤3px；P3 wheel 滚离即隐；P4 换锚（单节点 + 回位落点 = 第二次跳转前位置）；P5 跨会话不出胶囊；P6 双主题 × 双引擎 × 手机视口。
- 结果：**Chromium 39/39 + WebKit 39/39，ALL GREEN × 2**；证据 32 帧 = 2 引擎 ×（桌面亮/暗 + 手机亮/暗）×（跳转前/搜索面/落地+胶囊/回位后），`evidence/search-return-pill/`。
- checkJs 门：`node scripts/check-js-types.mjs` 失败集与改前**逐字节一致**（ws.js/navController/modelPanel/main/scheduled-task = main 既有漂移，非本批文件）——本批两文件零新增类型错。

## 5. 顺带观察（非本批判据）

跨会话命中点击后（mock 双会话实证）：`activeSessionId` 已切到目标会话，但 `#chat .search-hit-flash` 从未出现 = 命中定位静默丢失，用户落在目标会话底部。根因形态：`jumpToResult` 里 `switchToSession` 是动态 import 的异步腿，而同步紧随的 `scrollToMessage` 首次调用时 `activeSessionId !== res.sessionId` 即静默 return，且无重试链。属弹窗退场批①既有跳转腿行为，spec 以 OBSERVE 行记录不判；跨会话胶囊因此天然缺席（与本批「真锚」口径一致）。若作者裁定要修，修复点在跳转链的跨会话等待/重放缝（独立小批）。

## 6. 关联

- 前批：[[20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f]]（点结果改主会话直跳 = 本批的消费面）；mobile dock 批（手机搜索页收编 = 手机腿入口）；follow 批 2026-10-08（好友面 ↓N 胶囊 = 同一家族的另一落面）。
- 纪律：在飞批（按钮玻璃/卡片家族/浮钮单行/时间滚轮稿/toggle 回绿/文件卡 footer）工作区零触碰——本批 diff = 3 个既有文件 +18 行、1 个新模块、1 个 spec、证据目录。
