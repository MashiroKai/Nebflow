# dock 悬浮胶囊批（作者令 2026-11-01 21:07）—— 手机端底 dock 胶囊化 + 输入间隙

- 批次：delegate-nebflow-7fa8a6bb · 分支 `delegate/delegate-nebflow-7fa8a6bb`
- 作者令：21:07 实拍（uploads/687436871179875_IMG_1672.jpg）两件——
  ① dock 上缘与消息输入框之间保留呼吸间隙（当时贴死）；
  ② dock 通栏贴边条 → Apple 风格悬浮胶囊（左右下三边屏幕边距、全圆角、
     safe-area 避让照旧、材质用既有玻璃样式包毛玻璃档、双主题成立）；
  五个 tab 位 / 中央头像钮 / 选中态 / 切换逻辑 / 角标行为零变化。

## 落地

生产代码全在手机层（桌面渲染零感知，规则全部 `body.mobile-layer` 作用域）：

| 文件 | 改动 |
|---|---|
| `web/css/mobileLayer.css` | ① 新 token：`--dock-inset-x:12px` / `--dock-inset-b:8px` / `--dock-gap:10px`（+既有 `--dock-h:54`）；② `#mobile-dock` 换胶囊：左右 12 / 底 safe+8、高 54（safe 垫外置）、`border-radius:999px`、材质 = glass.css 面级浮件档（`--glass-bg` + `blur(24) saturate(1.15)` 双写 + `--glass-border` + `--cv-shadow`，`#input-bar` 同配方单源）；通栏 `border-top` 与暗档 `#16181d` 实底字面随批退役；③ `.mobile-page` 让位面扩为胶囊全带（dock-h+inset-b+safe）；④ `#mobile-pages::before` 承载面背板（带区 = 页面底色，收编根页带区不透聊天根，聊天页同 token 零差）；⑤ `#input-area` padding-bottom = 胶囊顶缘 + `--dock-gap`（呼吸缝；main.js 让位读 offsetHeight 零 JS 改动） |
| `web/css/ask.css` | canvas ask FAB/输入条底距并入 `--dock-inset-b`（保持「与左锚文档工具簇 16px 同带共中线」——pane 底随批同步上移） |
| `web/css/glass.css` | no-backdrop 双保险两块列名 `#mobile-dock`（微信/X5 回落实底档，`#g2-edge-fade` 同款先例） |

胶囊身后是平坦承载面（聊天页=本底色、根页=背板带），毛玻璃 blur 即采样该底；
内容不滚入胶囊之下（与收形批后一致的让位模型，只换形态不动信息架构）。

## 几何契约（钉进 spec）

- 输入卡底缘↔胶囊上缘缝 = `--dock-gap`（10px；收形批「缝==lift 0」契约退役，lift 恒 0 保留）。
- 胶囊三边距：左右 12 / 底 = safe+8（真机 home indicator 之上 8px 净空）；本体高 54。
- r999 全圆角；材质含 backdrop blur。

## 测试（契约随批修订 + 三处 main 在飞陈旧探针修复，均注明批号）

- `tests/mobile-dock.spec.mjs`：D13 改读胶囊契约（gap==`--dock-gap`、三边距、r999、blur 材质）。
- `tests/mobile-dock-shape-shots.mjs`、`tests/cs1030-coldstart-batch.mjs`：⑤/D2 缝断言随批换 10px。
- `tests/mobile-fab-search.spec.mjs` F1、`tests/mobile-dock.spec.mjs` D18：**main 既有红**——探针仍读已退役 token `--g2-fab-bg`（统一按钮玻璃批 2026-11-01 换代 `--glass-bg`）/标题仍在页头内查（面板头标识 B 批 17:01 改流内首件），随批修复探针（生产代码零动）。
- `tests/mobile-p12-settings.spec.mjs` T1：**main 既有红**——「页体首件=收编面」钉被 B 批「首件=流内大标题」换代，探针改读新序（首=标题、次=收编面）。
- 新增 `tests/mobile-dock-capsule-shots.mjs`：双主题×双页面（消息页/工作台）截图 + 六项机械探针 + 零 console error，产出 `evidence/20261101_dock-capsule/`。

## 结果

- `mobile-dock.spec` 21/21 绿（连跑 4 轮）；手势返回（gesture-tribunal）/顶栏/topline/canvas-sheet/popup-retire/p8/p10/p11 等 mobile 族全绿（见批报告）。
- `mobile-v3` T2（任务徽标 10→8）**main 既有红**（本分支与 main 同败，涉桌面任务计数链非本批域，未动，留任务系统处置）。
- 零 console error；桌面零命中（全部规则 mobile-layer 作用域）。
