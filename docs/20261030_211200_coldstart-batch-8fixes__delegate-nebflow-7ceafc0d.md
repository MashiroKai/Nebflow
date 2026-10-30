---
type: bugfix-report
status: published
date: 2026-10-30
scope: 冷启动调试批①-⑧（图片查看器交互 / 毛玻璃统一 / SVG 出图链 / markdown 渲染面 / 会话引导链 / 消息留白 / mobile dock）
---

# 冷启动调试 bug 修复批（①-⑧，2026-10-30 作者实证）

作者 2026-10-30 20:09 冷启动逐条调试实证批，八件一次清。修件锚点、根因、验证全绿记录。验收 spec：`tests/cs1030-coldstart-batch.mjs`（31/31），截图 `evidence/20261030_coldstart-batch/`。

## ① 图片查看器默认非拖拽

- 根因：`viewers/image.js` 打开即 armed pan（viewport `cursor:grab` + 任意 pointerdown 拖动画布）。
- 修：默认非拖拽；**抓手钮**（缩放条首钮，latch）或**按住 Space**（仅指针在本查看器上时）进入拖拽态，`pan-enabled` 类驱动光标 grab/grabbing。拆分.css `.canvas-image-viewport` 光标语义随门切换。

## ② 查看器控件 hover 可见性

- 根因：`.canvas-zoom-btn:hover` 只换底色（`--cv-hover-bg` 一档），毛玻璃条上 hover 态近乎不可辨（作者实证「hover 按钮直接消失」）。
- 修：hover 升级为完整玻璃控件材质（抬亮底 + 描边 + 内外缘），rest 态 1px 透明边框占位零布局位移。rest 透明 ghost 语义保留（canvas-glass-unify GU2-5b 钉）。

## ③ 毛玻璃对齐输入框那款

- 根因：`--cv-shadow` 取的是 chat `#header` 的 inset-only 档，与主对话输入框 `#input-bar`（inset 高光 + 两档外投影）不同款，控件条在内容面上「飘不住」。
- 修：`sapphire.css --cv-shadow` 亮/暗两档逐字改锚 `#input-bar`（单源 token，胶囊/圆钮/查找条/缩放条整族跟随）；`.canvas-zoom-bar::before` / `.nf-findbar::before` 补顶缘 sapphire 折射细线（input.css 同配方）。`canvas-glass-unify.spec` 模板参考件 `#header` → `#input-bar`（31/31 绿）。

## ④ 聊天搜索 SVG 缩略图「?」占位

- 根因双层：`nfFileCache.fetchBlobWithProgress` 流式拼接 `new Blob(chunks)` **丢 Content-Type** → object URL 以 text/plain/octet-stream 出件 → `<img>` 对 SVG 严格 MIME 拒渲；旧账本字节（CacheStorage 跨会话存活）全是不可辨型。
- 修：取数腿把响应 MIME 注进 Blob；消费单点 `ensureBlobType` 按扩展名自愈（空 type 与 octet-stream 两形，窄白名单）；`chatSearch` SVG 卡 img 主形态 + `<object type="image/svg+xml">` 回退（选型+回退）。

## ⑤ 偶发消息原始 markdown 不渲染

- 根因：病态输入让 `marked`/KaTeX/DOM 后处理在 **rAF 升级批中途抛错** → 链断，后续消息永久滞留 `textContent` 原始态（与「编辑态显源码」不同面）。
- 修：`renderMarkdownWithMath` 永不抛（异常回落 escapeHtml 纯文本）；`persistence.js` 两条升级批（初始重放 + 上滑补页）加单元素护栏，单片失败不拖垮整批。

## ⑥ 聊完一轮历史不见了 —— 属 bug，已修（后端引导链）

- 根因（`SessionStore.ensureAgentSession` / `ensureActiveAgentSession`，**每次启动都跑**）：
  1. 前端每条「新建会话」都带 root agentName（`modal.js getTargetAgent`）⇒ 多 Nebula 会话是常态，而 multiple 腿把它当 race 残留**从索引剪枝**（消息文件仍在盘上 = 侧栏历史消失）；
  2. boot **无条件**把 activeId 换挂到最新 Nebula 会话 ⇒ 前端静默应用并整擦聊天 DOM（「像开了新会话」）；
  3. legacy claim 恒取第一条 agentless 会话，无视持久 activeId。
- 修：multiple 腿改 SELECT-only（选最新为代表，索引不动）；activeId 有效（空/不在索引除外）时**不劫持**；claim 首选持久 active 的 agentless 会话。钉：`BootSessionKeepPlaceSpec` T1-T4 + FreshInstall 回归 6/6 绿。
- 前端纵深防御（localStorage 记忆 last-active + 失配可见提示）未做，挂账见 [[#开放项]]。

## ⑦ 消息列表左右留白

- 修：`#chat` 水平内距 0 → `16px`（4px 栅格档，=#header 水平内距同量级），用户/AI 两行两侧同留白。

## ⑧ dock 中键缩小 + 输入框无缝贴合

- 修：`--dock-center-size` 50 → 44（一档）；`#input-area` padding-bottom 撤掉额外 `max(12px, safe-area)` 档 —— 输入卡底缘与中键大圆顶缘相切（缝 == lift 12px）。`mobile-dock.spec` D10 改钉 44 + 新增 D13 缝宽恒等。

## 验证

- 新批 spec `tests/cs1030-coldstart-batch.mjs`：**31/31**（A 图片查看器×2 主题 11 项 / B markdown 4 项 / C SVG 3 项 / D mobile 2 项 + R0×5）。
- `canvas-glass-unify` 31/31；`card-image-zoom` 54/54；`canvas-html-zoom` 23/23；`imgviewer-controls-shots` 8/10（G1 胶囊居中为存量红，pristine 复证同败）。
- Scala：本批新 spec T1-T4 + FreshInstall 2 回归 = **6/6**；`sbt compile` 693 源绿。
- checkJs：本批触及件零新增（image.js 一度 +1 已修）；存量漂移 3 项（bgMergedPanel/plugins/sidebar）pristine 复证同败，非本批。
- build-web 196 源 0 orphans 绿。
- 存量红（非本批，pristine 复证）：`msg-search-v3-smoke`（overlay 拦截点击）、`canvas-glass-unify` GU2-6（ask-fab 叠 source 圆钮，spec 已加临时摘除 workaround）、`mobile-dock` D5/D14、`imgviewer` G1。

## 开放项

- 会话链前端纵深防御（last-active localStorage + 失配可见横幅）——本批只修后端根因。
- ask-fab 与 md 查看器 source 圆钮同角叠印（存量布局冲突，ask-dock 批引入）。
- `mobile-dock` D5/D14、`msg-search-v3-smoke` 存量红待归属批次处理。
- checkJs 基线 3 项漂移待 --update 重锚（只降不升纪律下需单独批）。

相关：[[20261029_140000_mobile-projects-single-column__delegate-nebflow-ea10c90a]]（mobile 层同 dock 族）、docs 内 [[glossary]] 会话/画布条目。
