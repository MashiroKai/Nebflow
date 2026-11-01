---
type: batch
status: published
date: 2026-11-01
scope: 手机层输入条的文件附件 chip 图标化（文件名褪出，图标盒与图片缩略图逐值同盒）
---

# 输入框附件图标化批（作者令 2026-11-02）——手机层文件附件改扩展名族图标盒

关联批：[[20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9]]（上一批手机输入卡垂直收窄，同一条输入卡的不同轴）；[[20261031_160300_mobile-fab-unify-search]]（手机层悬浮件统一族，同文件尾批次块形制）。

## 令文与口径

作者令：「输入框里的附件条**不显示文件名**，用与图片附件缩略图**一样大小的图标**代替；添加附件后占位提示文字不变，仍是『请输入消息...』。」

口径拆解：

| 项 | 要求 |
|---|---|
| 附件 chip 形态 | 文件类型图标（按扩展名族：文档/表格/PDF/压缩/代码各给对应图标配色），占据与图片缩略图**完全相同的盒子尺寸**，名字文字去掉 |
| 多附件 | 并排同规则 |
| 既有入口 | 点击预览、删除角标保留 |
| 占位文字 | 不动（不加「已添加 xx」提示） |
| 发送/上传链 | 零触碰 |
| 范围 | 手机层优先（`mobileLayer` 作用域）；桌面输入框若共组件先不动并申报 |
| 图片附件 | 形态零回归 |

## 关键结构事实（决定了实现路径）

手机输入条与桌面**共用同一个渲染函数** `chat.js::renderAttachmentPreview`（无第二份手机渲染腿）。因此「手机改、桌面不动」不可能靠 JS 分支做——本批用 **CSS 作用域 + 基座闸** 双层：

- 基座（`input.css`）：新增节点默认 `display:none` ⇒ 桌面 chip 逐像素原样；
- 手机层（`mobileLayer.css`，`body.mobile-layer` 作用域）：把同一节点翻成 28×28 图标盒、并隐藏文件名 span。

DOM 只增不减：文件名 span（`.att-file-name`）与 `.att-file` 类名原样保留（兼容硬约束：`tests/msgqueue-recall-attach.spec.mjs:189/321/483` 读 `.att-file` 的 `textContent` 找附件名；`tests/fwdguard-refonly-gate.spec.mjs:304` 数 `#attachment-preview .att-file` 个数），只在手机层 `display:none`。

## 改动账（六文件，单批单刀）

### JS

1. `web/js/popArtifacts.js:157-183`（`fileIconStyle`）——本表升级为附件 icon chip 的**唯一**色族/角标来源（零第二张扩展名表）；**补入归档族** `zip/rar/7z/tar/gz/tgz/bz2/xz → {cls:'zip'}`（此前落 `code` 灰档；令文要求「压缩」独立色族，故补入）。
2. `web/js/chat.js:919` —— `renderAttachmentPreview` 消费面 import 追加 `fileIconStyle, extOf`。
3. `web/js/chat.js:3767-3796` —— 文件附件（else）分支重写：
   - `wrap.title = nameText`（信息不丢，指针悬停可见）；
   - innerHTML = 旧内联 12px 线框 svg（保留类名 `.att-file-glyph`，手机层隐藏）+ 新增 `<span class="att-file-icon fc-icon <cls>">LABEL</span>` + 原 `.att-file-name` span 原样；
   - ✕ 删除角标（`CLOSE_X_SVG`）与图片附件逐字同族，未动。
4. `web/js/input.js` —— 附件态占位文案收单点为模块级 `attachmentPlaceholder(n)`（文件尾追加，见下「行号纪律」）：
   - `input.js:1651`（`updateInputPlaceholder` 附件分支）与 `input.js:2152`（voice 作用域 `refreshPlaceholder`）两处改为调用它；
   - `input.js:2342-2347` 函数体：`body.mobile-layer` ⇒ `t('input.placeholder')`（「输入消息...」）；桌面 ⇒ `t('input.ph.attachments').replace('%n', n)`（逐字沿用）。

### CSS

5. `web/css/input.css:341-346` —— 基座闸 `.att-file .att-file-icon { display: none; }`（桌面零视觉回归的唯一闸门）。
6. `web/css/mobileLayer.css:2681-2749`（文件尾批次块，全部 `body.mobile-layer` 作用域）：
   - `#attachment-preview .att-file` → 28×28 / padding 0 / gap 0 / radius 8px / background transparent / border none / justify-content center / `flex:none` / cursor default（**不设** `overflow:hidden`，保持 ✕ −4px 悬出的既有形态）；
   - `.att-file > .att-file-glyph { display:none }`；
   - `.att-file > .att-file-icon` → `display:flex` 28×28 / `border-radius:8px` / `box-sizing:border-box` / `border:1px solid var(--glass-border)` / `color:#fff` / `font-size:8px` / `weight:800` / `letter-spacing:.3px`；
   - `.att-file > .att-file-name { display:none }`；
   - `.att-file .att-remove` → 13×13 / top −4px / right −4px / font-size 8px / `::after{inset:-6px}` / `svg{6.5px}`（盒从胶囊缩成方盒后 ✕ 同档收一档，命中域仍外扩）。

7. `web/css/chat.css:4766-4769` —— `.fc-icon.zip { background: linear-gradient(135deg, #d7a05a, #a8743a); }`（归档棕档；与 `.pdf` 红 / `.doc` 蓝 / `.xls` 绿 / `.ppt` 橙 / `.code·.data` 灰均异，色族单点仍在 chat.css）。

## 几何契约（实测，非推算）

图片缩略图真值 = `.att-thumb`（`input.css:313-321`）：28×28、`border-radius:8px`、`border:1px solid var(--glass-border)`、`box-sizing:border-box`。

图标盒逐值对齐（`tests/composer-attach-icon-shots.mjs` A3 腿，双主题同读数）：

```
thumb  {w:28, h:28, radius:"8px", border:"1px", boxSizing:"border-box", x:187, y:668.6}
icon[0]{w:28, h:28, radius:"8px", border:"1px", boxSizing:"border-box", x:85,  y:668.6}
icon[1]{w:28, h:28, radius:"8px", border:"1px", boxSizing:"border-box", x:119, y:668.6}
icon[2]{w:28, h:28, radius:"8px", border:"1px", boxSizing:"border-box", x:153, y:668.6}
```

⇒ 宽/高/圆角/描边/盒模型**五项逐值相等**；多附件 y 全等（同基线并排，横向步进 34 = 28 + 6 gap，与图片缩略图同一 flex 规则）。色族分派实测：`att-file-icon fc-icon pdf` / `… xls` / `… zip`。

## 桌面申报（按令「共组件先不动」）

桌面（1280，非 `mobile-layer`）**未做任何形态改动**，由 D 腿钉死原值：

| 判据 | 桌面实测 |
|---|---|
| D1 文件 chip 形态 | 仍是「线框图标 + 文件名」胶囊（`.att-file-icon` `display:none`、`.att-file-name` `display:block`） |
| D2 附件态占位文案 | 仍是 `已附加文件——输入一句话一起发送`（`input.ph.attachments`，不在本批范围） |
| D3 chip 盒 | w162.33 × h28、radius 8px、border 1px —— h 与改前同值（原值钉） |

跨面申报：`.fc-icon` 色族是 pop 卡/引用卡的共用档，**归档族补入**使这些面同扩展名文件的角标从灰档变棕档（语义更准，纯增项，无布局变化）。本批未改 pop 卡/引用卡的任何几何。

## 验收证据

新证据脚本 `tests/composer-attach-icon-shots.mjs`（真 web 树 + 真 WS 桩，零伪造 state；形制同 `tests/composer-mobile-slim-shots.mjs`）：手机 **375×812** × 亮/暗双主题 × 六帧（0 空 / 1 单 PDF / 2 单图片 / 3 混合 PDF+XLS+ZIP+图片 / 4 ✕ 移除后 / 5 发送后）+ 桌面 1280 零触碰腿。

- **改后树：RESULT: ALL PASS**，`console: no errors`。
- **改前树验红**：`NEBFLOW_WEB_ROOT=/tmp/atticon-before/src/main/resources/web` 同命令 ⇒ **24 FAILURE(S)**（双主题各 12 条图标化判据全红）：绿是改动带来的、不是夹具自证。
- 占位文字：P0（空）/P1（单 PDF）/P2（单图片）/P3（混合）/R2（移除后仍余附件）/S2（发送后）六态全部 `"输入消息..."`。
- 移除/发送回归：R1 条带 4→3 且 `pending` 同步 3；S1 发送后 `chips:0 pending:0 text:""`。
- 产物：`evidence/20261102_composer-attach-icon/`（`mobile-{light,dark}-{0-empty,1-pdf,2-image,3-mixed,4-after-remove,5-sent}.png` + `desktop-light-mixed.png`）。

回归套（隔离实例，静态树 + route 桩，不碰宿主 8080）：

| 套 | 结果 |
|---|---|
| `tests/composer-attach-icon-shots.mjs` | ALL PASS（改后）/ 24 红（改前） |
| `tests/composer-mobile-slim-shots.mjs`（`TAG=after`） | ALL PASS（D 腿桌面零触碰二次确认） |
| `tests/ime-guard.spec.mjs` | ALL PASS |
| `tests/f1-send-queue.spec.mjs` | ALL PASS |
| `tests/attach-strip-shots.mjs` | ALL PASS（手机多附件零遮挡 + 发送清空动画） |
| `tests/attachkey-idem`（playwright） | 4 passed / 3 skipped |

### 既有基线红申报（非本批回归，两棵树同形）

| 套 | 改后树 | 改前树 | 判定 |
|---|---|---|---|
| `tests/msgqueue-recall-attach.spec.mjs` | 6 failed | **6 failed（同一条 `TypeError: Cannot read properties of undefined (reading 'id')` @ `enqueueViaSend` 131:15）** | 既有环境红 |
| `tests/msgqueue-image-mime.spec.mjs` | 4 failed / 1 passed | **同形 4 failed / 1 passed** | 既有红 |
| `tests/fwdguard-refonly-gate.spec.mjs` | 12/16（B1a/B1b/B2b/B4 红） | **5/16（A 组整组红 + 同一批 B 腿红）** | 既有红：B1a/B1b/B2b/B4 在两棵树**逐字同形**失败（`disabled:true` 卡在 `请附一句话后发送`、出站帧 `total:0`），与本批改动无因果；改后树多绿的 7 条正是改前树缺的闸族判据 |

## 行号纪律（本批踩坑记录）

`input.js` 的 1698/1699 是 ime-guard 哨兵的**文本锚**配套行号元数据：`scripts/check-ime-guard.mjs` ALLOWLIST 记 lines、`tests/ime-guard.spec.mjs` G4-1/G4-3 钉活行号。正文中段插入会把这两行逐行下推 ⇒ 哨兵打红（实测漂移后确实红）。处置：`attachmentPlaceholder()` **追加在文件尾**（函数声明提升 ⇒ 两处消费点照常可用），行号零漂移，`ime-guard` 复跑 ALL PASS。

## 未做 / 开放项

- 桌面输入条形态未动（按令申报，见上「桌面申报」）；若日后要桌面同步图标化，只需删 `input.css:341-346` 的基座闸并把 mobileLayer 块提升为无作用域块。
- 附件点击预览入口：文件附件本无预览面（既有行为，`att.preview` 只对图片/文本类生效），本批未新增能力——只保住「点击/✕ 角标既有入口」不退化。
- 好友会话输入框（窗口面）与 `.fa-input-bar` 弹窗输入条：非「手机端主会话输入框」面，未动。
