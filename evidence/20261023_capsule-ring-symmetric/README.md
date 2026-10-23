# 修复批 · canvas 胶囊 hover 环上下对称 + 尺寸恒定 + canvas 居中（作者令 2026-10-23 08:53）

> 作者令逐字：「这个胶囊的hover要在上下显示，这样才有滚动的感觉，现在全在下面。另外，胶囊的大小要保持固定，而且相对与canvas居中显示。」

## 三令落地

### ① hover 环上下真对称
- 根因：昨批（2026-10-22 hover 改向批）头锚贴顶（顶带 ~28px），对称竖环上缘必被 `#canvas-panel` overflow:hidden 裁掉，妥协成「双缘全下垂 peek 0.58×/hang 1.62×」——环项全在下面。
- 解法（brief 解法一「hover 展开时锚点下沉」）：胶囊常态回 canvas **顶带横轴居中**（v2①「居中标题头」原位；不取永久纵居中——wiki 图谱/流程图中心区节点点击会被胶囊拦截，实测 wiki spec §C/§D 红）；hover 展开时胶囊整体**沉降 canvas 垂直中点**（`.ring-open` + `--ring-dy`，JS `syncRingDy()` 置值），环锚上下都有空间。
- 几何（canvas.js `layout()`）：上一签上展 −edgeY、下一签下垂 +edgeY，**|y| 严格相等**；rotateX 反号等幅（上 +34° 顶边后仰 / 下 −34° 底边后仰，竖圆柱同侧曲率，CSS rotateX(+θ)=顶边远离视者）；translateZ 双缘同为 −110px；透视由 `.capsule-ring` 自身 `perspective: 640px` 提供（原 batch 挂在 .canvas-header 上够不到孙级胶囊，纯正交压扁——本批修正）。
- 不裁切：环开态上缘位顶 ≥ 面板顶、下缘位底 ≤ 面板底（spec CR2-6 实测 385.7 vs 16.0 / 524.3 vs 890.0）。

### ② 胶囊尺寸恒定
- 胶囊本体（.canvas-header）任何状态（常态 / hover 沉降展开 / 环滚动中）宽高全等、transform 零缩放（spec CR2-7/CR2-7b 实测 134.9×36.0 三态逐位相等，scale=1.000）。
- 沉降只改位移（--ring-dy）不改大小；`:active` 按压 scale 0.96/0.97 是 CC1 按压触感既定裁定（非呼吸），保留。

### ③ 相对 canvas 居中
- 常态：胶囊中心 x = 面板中线（spec CR2-8a / cardhead CC0-3 实测 dx=0.0，左右缝相等 ±3px）。
- 环开态：胶囊中心 = 面板中心双轴（spec CR2-8b 实测 dx=0.0 dy=1.0）。
- 收环回顶（0.18s ease）；沉降展开 0.36s cubic-bezier(0.22,0.61,0.36,1)（放慢·自然·流畅裁定族同节奏）。

## 既有裁定延续（未破）
- 0.36s 温和缓出环族节奏 · frosted 材质单源 sapphire token（--glass-bg/--glass-blur saturate(1.15)/--glass-border/--frost-shadow）· 环项与内容/控件零重叠（环开态短暂居中是 brief 明列解法一，常态顶带不挡画布中心命中）· 纵滚 deltaY 主导 + 触控板横滑 deltaX 副手势 · 56px 让位带几何（fcv 族，未触碰）。
- 环开期间滚轮改 document 捕获（面板矩形内消费为换选，面板外零感知）——沉降后指针停在原地（不必悬在胶囊上）也能换选。
- 收环判定改 **hover 区联合盒**（环开点 56px 半径 ∪ 环开点→沉底胶囊走廊 ±90px ∪ 胶囊本体 ∪ 缘位胶囊，各外扩 14px）——pointerleave 单源在「胶囊已沉离指针」的几何下会立即误收（开→收振荡）。
- deck 叠卡态不开环（叠卡有自己的环卡滚轮语义，双环互搏）；手机层/卡窗整腿让位不变。

## 顺修（同族缺陷，本批几何使其显形）
- `canvas-tab-closed` 环重建监听：teardownTab 在 `tabs.delete` **之前**派发事件，同步重建读到删除前状态——死胶囊残留 + `tabs.size<2` 收环腿永不触发（关到单页签环卡死不收）。改延一拍读删后状态。HEAD 上被「头不沉离指针、pointerleave 即收」掩盖，本批沉降几何使其必现。

## spec
- `tests/canvas-capsule-ring.spec.mjs`：CR2-3 重写为上下真对称断言；新增 CR2-6 不裁切 / CR2-7+CR2-7b 尺寸恒定（常态/展开/滚动中）/ CR2-8a+CR2-8b canvas 居中（常态横轴 + 环开双轴）；亮色腿 L1/L2 + 亮暗×开收 4 截图。
- `tests/canvas-cardhead-capsule.spec.mjs`：CC0-1 顶带 + CC0-3 横轴居中重钉（左右缝相等）。
- `tests/wiki-graph-ux.spec.mjs`：§G「离环」目标点改画布中下空区（胶囊居中后「回画布中心」= 回胶囊本体）。

## 结果
- canvas-capsule-ring 32 PASS / 1 FAIL（CR2-5 材质逐项相等 = 基点同败既有，stash 对照逐位同败；chat header 自身 shadow 漂移，非本批面）· canvas-cardhead-capsule 28/28 · wiki-graph-ux 12/1（§F 同一基点既有）· checkJs 254 < 基线 257。
- 证据：本目录 shots/ 1-ring-open-dark / 2-rest-dark / 3-ring-open-light / 4-rest-light（亮暗 × 环开/收起）。
