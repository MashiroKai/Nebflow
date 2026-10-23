# 证据 · 修正批 canvas 胶囊/按钮族六项（作者令 2026-10-23 15:10）

截图全部由 spec/探针自动产出（隔离静态服务器 + 真实 fixture，永不触碰宿主 8080）。
`shots/` 文件名序号即下方清单序号；亮（light）暗（dark）双态成对。

## 一、shot 清单

| 文件 | 内容 | 断言对应 |
|---|---|---|
| `1-ring-open-dark.png` / `3-ring-open-light.png` | hover 环开态顶带特写（亮暗）：上缘位探出段**真实渲染在面板顶缘外**（环层挂 body，`overflow:hidden` 裁切盒失效），零硬裁切线；下缘位模糊下垂 | 六项②（ring spec CR2-9 + CR2-6） |
| `2-rest-dark.png` / `4-rest-light.png` | 平时态单胶囊顶带居中（亮暗） | 六项②配套形态 |
| `5-dedupe-before-dark.png` / `5-dedupe-before-light.png` | **修前**（HEAD 7c97e85f5 git-archive 基线，8178 端口直出）：胶囊内截断文件名 + 其下方阅读面首块级 H1 同名全文 = 作者截图所指「双显」 | 六项①对照面 |
| `6-dedupe-after-dark.png` / `6-dedupe-after-light.png` | **修后**：同名首 H1 隐藏（`display:none` 内联单点，源码/DOM 未删），阅读面直接从正文起，只留胶囊内一处 | 六项①（six spec S1-1..4） |
| `9-deck-size-dark.png` | **修后** deck 叠卡态全面板：前卡名称条 `128.3×36.0`（与常态 `rest=128.3×36.0` 逐位相等 —— 整区 scale(0.85) 被 `--stack-scale-inv = 1/0.85` 反量回乘抵消，computed scale = 1.1765） | 六项⑤（six spec S5-1/S5-2） |
| `9b-deck-size-before-dark.png` | **修前**（HEAD 基线同 fixture 同操作）：前卡名称条 `109.1×30.6` —— 整区 0.85 直接吃掉尺寸（作者令「面板上的胶囊会小一点」的实证） | 六项⑤对照面 |
| `10-buttons-dark.png` / `10-buttons-light.png` | 右下钮组特写（亮暗）：source-toggle 右锚 16 贴面板右缘（gap=17 实测 ≤1px 容差），三钮材质 = `--glass-bg / blur(--glass-blur) saturate(1.15) / --glass-border / --frost-shadow`（与 chat `#header` 同源；减淡 opacity 0.6 退役） | 六项③④（six spec S3-1 / S4-1/S4-2 / L1） |

## 二、六项 → 断言/证据对照总表

| 作者令 | 实现 | spec | 证据 |
|---|---|---|---|
| ① 胶囊名称双显去重 | `viewers/markdown.js` `dedupeTitleHeading()`（首块级 H1 === 去扩展名文件名 → 内联 display:none） | six S1-1..S1-4 | 5/6 前后对照 ×亮暗 |
| ② 顶部越出去掉截断感 | `#canvas-capsule-ring` 挂 `document.body`（`position:fixed; z-index:246`）+ steady 可点性门 + 中心位恒 pe:none 落穿 | ring CR2-6/CR2-8b/CR2-9/CR2-9b | 1/3（越出段真实渲染） |
| ③ 右下按钮组贴右 | `.canvas-source-toggle` right 64→16（ref-select/find/zoom 簇槽联动 −48） | six S3-1 | 10 ×亮暗 |
| ④ 毛玻璃与聊天一致 | 三钮材质换全站 frosted token 单源，`opacity:.6` 撤除 | six S4-1/S4-2/L1 | 10 ×亮暗 |
| ⑤ 胶囊尺寸跨场景统一 | deck 叠卡态前卡头部 `scale(calc(var(--head-scale) * var(--stack-scale-inv)))` 反量回乘 | six S5-1/S5-2 | 9 vs 9b（36.0 vs 30.6 高） |
| ⑥a 拖动删除仿堆叠滑删 | 胶囊横划跟手 1:1 + 微倾 + 过阈甩出（`.is-swipe-out` 240ms 13deg）再关页签；不足阈回弹 | six S6-1..S6-4 | （spec 断言面） |
| ⑥b 堆叠滑删卡顿修复 | `syncDeckBox` 四元盒脏检 + 环卡位姿签名去重（`_deckSig` 全等零写） | six S6-5/S6-6（MutationObserver 计数 = 0） | （spec 断言面） |

## 三、验证基点（既有失败，非本批面）

- `canvas-capsule-ring` CR2-5（材质逐项相等）：HEAD git-archive 基线（`/tmp/nf-head-base`，8178 直出）**逐位同败**（chat header shadow 漂移，前批 README 已记录判例）。
- `wiki-graph-ux` §F（frosted 视觉档）：main HEAD 既有判例（graph-button-403 批 git archive 实锤）；本批几何敏感面 §G PASS。
