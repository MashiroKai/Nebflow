# 修正批 · canvas 胶囊/按钮族六项 · 批报告

- 批次：修正批（作者令 2026-10-23 15:10 附截图逐字六项：①「胶囊内文件名截断显示、其下方又渲染完整文件名」→ 去重只留胶囊一处；②「顶部越出去掉截断感 → 让胶囊真能渲染超出 canvas 顶缘…消灭硬裁切线」；③右下角按钮组靠右对齐贴面板右缘；④右下角按钮毛玻璃加强与聊天对话框气泡一致；⑤胶囊尺寸跨场景统一（基准 134.9×36.0）；⑥拖动删除动画仿堆叠滑删 + 堆叠滑删卡顿性能修复）
- 基线：main 最新（7c97e85f5；对称批/顶部修正批均已在 main）
- 分支：`delegate/delegate-nebflow-a65aba03`（独立分支，未触碰 main）
- 证据：`evidence/20261023_capsule-six/`（README 对照总表 + shots/ 12 张亮暗双态）

## 一、改了什么

### ① 名称双显去重（阅读面同名首 H1 隐藏）
- 根因：作者截图所指「重复渲染层」非 DOM 残留，是 markdown 文件自身首行 `# <文件名>` 渲染出的 H1（胶囊已显示同名，视觉双显）。
- `viewers/markdown.js` 新增 `dedupeTitleHeading(viewer, fileName)`：innerHTML 渲染后取**首块级 H1**，文本 === 去扩展名文件名（或全名）→ 内联 `display:none` 单点；源码/DOM 未删（S1-4 源码态/非同名全不动）。

### ② 顶部真越出（消灭硬裁切线）
- `canvas.js` `initCapsuleRing`：环层 `root` 改挂 `document.body`；`split.css` `.capsule-ring` `absolute → position:fixed; z-index:246` —— `#canvas-panel` 的 `overflow:hidden` 裁切盒失效，上缘位探出段**真实渲染**在面板顶缘外；`anchor()` 随改视口坐标，`layout()` edgeY 面板顶几何反解带 `out`（越出量克制档保持，CR2-6：0 < out ≤ 0.5×胶囊高）。
- 配套稳态门（回归修复，实测必要）：环层叠到标题头之上后，0.36s 展开过渡中缘位胶囊从指针位掠过会抢按压（CC1/CC2/CC3 三断言全挂）→ `openRing` 0.36s 后加 `.steady` 才放行缘位 pointer-events；`canvas-tab-closed` 重建同样重走门。
- 中心位恒 `pointer-events:none` 落穿标题头手势链（CR2-9b；`#canvas-capsule-ring.open.steady .cr-capsule.is-center` 双选择器特异性 (1,4,0) 压过 steady 全开 (1,3,0)——特异性回归实测后钉死）。

### ③ 右下钮组贴右缘
- `split.css`：`.canvas-source-toggle` right 64→16；`.canvas-ref-select` 112→64、markdown `addFindToggle` 112→64、`addGraphToggle` 160→112、html `addFindToggle` 160→112、`.canvas-zoom-bar` 64→16、html pane zoom 208→160、窄容器查询腿 64→16 —— 整簇右移 48 回原右锚 16 槽（实测 gap=17，容差 ≤1px）。

### ④ 毛玻璃材质与 chat 一致
- 三钮（source-toggle/ref-select/zoom-bar）材质换全站 frosted token 单源：`--glass-bg / blur(var(--glass-blur)) saturate(1.15) / --glass-border / --frost-shadow`，`opacity:.6` 减淡退役（S4-2）。
- 注：`#header` 自身的 border-bottom 透明（Header 底缘零边界批）+ box-shadow 仅顶缘 inset（--frost-shadow 豁免裁定）是**既有豁免**，按钮用完整 token —— 断言口径 = bg/blur/border-top 与 #header 相等 + shadow 与 --frost-shadow token 解析值相等（S4-1/L1）。

### ⑤ 胶囊尺寸跨场景统一
- 根因：deck 叠卡态面板整区 `scale(0.85)` 把前卡名称条一起缩小（HEAD 实测 109.1×30.6）。
- `canvas.js` `initDeck` 注入 `--stack-scale-inv: 1/DECK_SCALE`；`split.css` 叠卡态头部 `scale(calc(var(--head-scale,1) * var(--stack-scale-inv,1)))` 反量回乘 —— 修后 deck 态 128.3×36.0 与常态逐位相等（高 36.0 恰落基准档；宽随标题文案长度），computed scale = 1.1765（S5-1/S5-2）。

### ⑥a 拖动删除仿堆叠滑删
- `canvas.js` `initCardHeadGestures`：横划跟手 1:1（x 轴 `--head-dx` 直读，净空钳制）+ `--head-rot` 微倾（0.028 系数 cap 6deg）；释放过阈（>64）= `swipeOutFly()` 甩出动画（`.is-swipe-out`，dist=min(0.6pw+240, room+半宽+80) + 13deg + opacity 0，240ms 后 closeTab；deck 划走 0ms）；不足阈回弹归零（S6-1..S6-4）。

### ⑥b 堆叠滑删卡顿修复
- 根因（⑥b）：`syncDeckBox` 逐帧读写抖动写 layout 属性 + 每帧 zIndex/位姿重写。
- `deckFrame/syncDeckBox`：盒四元（x/y/w/h）全等跳过写；环卡绘制改位姿签名去重（`_deckSig`：z/opacity/transform(含 extra)/shade 全等零写）—— 横划按住期间非拖卡 style 写 **0 次**、静态面板 deck 盒 style 写 **0 次**（S6-5/S6-6 MutationObserver 计数）。

## 二、spec 同步

| 文件 | 改动 |
|---|---|
| `tests/canvas-capsule-six.spec.mjs`（**新**，19 断言） | S1 去重×4 / S3 对齐 / S4 材质×2 / S5 尺寸×2 / S6 滑删×7 / L1 亮色腿 / R0/R0b 零 JS 错误；S4-1/L1 口径带 #header 既有豁免（bg/blur/border-top 相等 + shadow = --frost-shadow token 解析值）；合成 pointer 捕获 try/catch（无活动指针 NotFoundError，事件直派不需要捕获） |
| `tests/canvas-capsule-ring.spec.mjs` | 头注 F 令；CR2-9 真越出（parent=BODY + !panel.contains + 上缘位 rect.top < panel.top，上缘位取 min-top 非 DOM 序首元）+ CR2-9b 中心位落穿；证据输出路径 `evidence/20261023_capsule-six/shots/` |
| `src/main/resources/web/js/viewers/markdown.js` | `addFindToggle` 112→64、`addGraphToggle` 160→112（③ 簇槽联动） |
| `src/main/resources/web/js/viewers/html.js` | `addFindToggle` 160→112（同上） |

## 三、验证（全绿，基点既有失败逐位同败）

| 套件 | 结果 | 说明 |
|---|---|---|
| canvas-capsule-six（本批新钉） | **19 / 19** | 六项全断言 + 亮暗双态，零失败 |
| canvas-capsule-ring | **34 PASS / 1 FAIL** | 唯一 FAIL = CR2-5 材质逐项相等，HEAD git-archive 基线（8178 直出）**逐位同败**（chat header shadow 漂移既有判例）——非本批面；CR2-6/8b/9/9b（本批几何敏感面）全过 |
| canvas-cardhead-capsule | **28 / 28** | 稳态门修复后手势链全过（CC1/CC2/CC3 复活） |
| canvas-stack | **288 / 288** | ⑥b 脏检/签名去重零回归 |
| canvas-card-window | **22 / 22** | 卡窗族全过 |
| wiki-graph-ux | **12 / 13** | 唯一 FAIL = §F frosted 视觉档，main HEAD 既有判例（graph-button-403 批实锤）；§G 环×画布分区 PASS |
| checkJs | **257 = 基线 257，零新增** | scripts/check-js-types.mjs |

## 四、证据（evidence/20261023_capsule-six/，README 有对照总表）

- `shots/5-*.png / 6-*.png`：①去重前后 ×亮暗 —— 修前胶囊截断名 + 下方同名 H1 全文（作者截图所指双显）；修后阅读面直接正文起。
- `shots/1-*.png / 3-*.png`：②环开态顶带（亮暗）—— 上缘位探出段真实渲染在面板顶缘外，零硬裁切线；`2-*/4-*` 平时态单胶囊。
- `shots/10-*.png`：③④右下钮组（亮暗）—— 贴右缘 16 + frosted 材质。
- `shots/9-*.png / 9b-*.png`：⑤deck 态尺寸 —— 修后 128.3×36.0（= 常态）vs 修前 109.1×30.6（整区 0.85 吃掉尺寸）。

## 五、红线自检

- 最小改动：src 3 文件（`split.css` 环层挂本位/钮组右锚+材质/滑删与反量回乘规则；`canvas.js` 环挂 body+稳态门+滑删+deck 反量+盒脏检；`markdown.js` dedupe 单函数）+ `html.js` 1 处槽位数字；无架构改动，未触碰 main，未重启引擎。
- 跟随仓库风格：spec 钉 + 证据目录 + REPORT.md + 作者令逐字引注，与前批同构。
- 已知残留：CR2-5 / §F 两处材质判例为基点既有（git-archive 逐位同败），建议另开「chat header 材质漂移」专项批收敛。
