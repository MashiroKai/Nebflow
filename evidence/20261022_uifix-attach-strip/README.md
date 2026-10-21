# uifix 批 2026-10-22 — 附件条移到输入框上方独立行（作者令「不要遮挡文字」）

作者原话：「设计一批附件的ui优化。让附件放在对话框上面，不要遮挡文字。」

## 改后形态

- `#attachment-preview` 从「textarea 顶缘 overlay + 44px padding 让位带」（composer-overlap
  修复 2026-10-18 形态）改为 `#input-wrap`（column flex）内 **in-flow 独立行**（贴输入框
  顶缘，css/input.css 基座）—— chip 条带与文字输入区完全分离，任何附件数量下零遮挡。
- 溢出语义两层各取所需：
  - **桌面层 = 换行多行**（`flex-wrap: wrap`）：chip 恒可见恒可点、不藏件；这也是
    quote-message B12 在管断言的既批语义（「溢出走换行（flex-wrap）不横向溢出」，
    2026-10-18 改 nowrap+横滚时被打红的 4 条由本批复位绿）。
  - **手机层 = 单行 + 「+N」折叠**（v3 已批形态；折叠腿单点 = `js/attachStripFold.js`，
    由 floatController 原样上移；观察链桌面启动即挂、两层共用；折叠腿对换行条带自禁
    —— flexWrap 计算值闸，模块零层名感知）。
- 顺手打磨（克制、聚焦真实痛点）：
  1. **统一 chip 语言**：缩略图与文件卡同 28px 档 / 同 8px 圆角 / 同条带基线（旧
     `.att-thumb` 50×50 在 overlay 时代被 max-height:30px 裁成 50×30 杂交形态；旧
     `.att-file` 3px 8px 内距 + 11px 字号 + 6px 圆角与 ref chip 两档）；文件卡材质
     `--glass-etched-*` token（旧硬编码 `#e0e0e0` 暗色下发白、零主题适配）；文件名
     上限 100px→140px（`.att-file-name` 类挂样式，内联退役）。`.att-taskref` 同步 28px 档。
  2. **✕ 删除钮不再被条带裁顶**：行 `overflow: visible`（折叠腿 display:none 自裁，
     无需容器裁切）—— 16px 圆盘 -4px 悬出 + 30×30 命中域（::after，composer-overlap
     批所建）此前被 overlay 条带的 overflow-y:hidden 裁掉上半。
  3. **发送清空动画**：发送/排队/离线入队三腿消费载荷后，strip 改「渐隐 + 上浮退场」
     （WAAPI ~160ms，`chat.js::clearAttachmentStripAnimated`），落定才清 DOM；空条带
     `:empty` 时零占位（composer 高度精确回落）。chip ✕ 移除与引用替换保持瞬时重建
     （单件删除不延迟、不吃点击反馈）。
  4. **粘贴图片一致性**：粘贴/选图/拖放同走 `addFileAttachment` 单点（input/attachments.js）
     ⇒ 同一 `.att-thumb` 渲染腿，本批统一 28px 档后三入口零分叉（核查面，零改动）。

## 证据（shots/）

断言清单（`node tests/attach-strip-shots.mjs`，真 web 树 + 真 WS 桩）：

- **核心断言 = 文字零遮挡**：375 / 430 / 桌面 1280 × 0 / 1 / 5 附件（3 图 + 2 长名文件）
  × 长文字输入，全部 chip 几何盒 bottom ≤ textarea top + 1px —— 18/18 PASS。
- 条带无视觉横向溢出（chip 盒右缘读数；scrollWidth 会被 ✕ 命中域 ::after 悬出污染，
  非视觉量）。
- 发送清空动画中间帧（opacity < 1 + attClearing 标记）+ 落定清空 + 占位符复位。
- 手机层折叠：375/430 多附件溢出收「+2」，单行 28px。

文件 = `strip-<mobile|desktop>-<宽>-att{0,1,5}.png`（全景）、`*-att5-composer.png`
（composer 特写）、`*-clearing.png`（清空动画中间帧）。

## 回归

- quote-message 16/16（**基线 12/16**：overlay 时代打红的 B2-B4/B12×2/B13 四条复位绿）
- f1-send-queue / queue-image-refresh：ALL PASS；fwdguard-refonly-gate 16/16
- PW 家族（mobile-v3 含 T6/T7 折叠腿、attach-bubble-unify、reftag-redesign、
  msgqueue-recall-attach、msgqueue-image-mime、attachkey-idem、user-attach-canvas-open）：
  63/63 passed
- checkJs：触文件 tsc delta = 0（与 HEAD 逐类目一致，仅行号漂移）；整体 gate 红 =
  HEAD 既有漂移（agentProfile/historyReplay/scrollUnderGlass/socialPanel/canvas，
  pristine 对照定谳，与本批无关）
- HEAD 既有、与本批无关的红（stash 对照定谳）：msgmenu S6（光球重复挂载）、
  devpanelname U3Pd（设备行头像）、askuser-attach/askuser-history-attach（18 例环境性
  `fetch failed`）
