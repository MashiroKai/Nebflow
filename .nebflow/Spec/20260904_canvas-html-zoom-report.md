# Canvas HTML 预览缩放——定性排查 + 修复验收报告

日期：2026-09-04 ｜ 节点：Coder（main 直接实施）｜ 主仓 commit：`9ccde499`

---

## 一、定性结论：B（HTML viewer 从未有缩放，非回归）

作者记忆中的「之前可以缩放」载体不是 Canvas HTML viewer——image（PNG）viewer 自 2026-08-18 起有滚轮缩放（海报 PNG 截图可缩），HTML 文件走 srcdoc iframe 从未做过缩放。

**证据链（全部实际执行）：**

| # | 检查 | 结果 |
|---|------|------|
| 1 | `grep enableViewerZoom` 全 web/js | 仅 `pdf.js:187`、`image.js:73` 接入；`html.js` 不 import `zoom.js` |
| 2 | `git log -S "enableViewerZoom"` | 唯一引入 commit `c2c7d9f3`（2026-08-18）「independent zoom for **image and PDF** viewers」——设计范围从未含 HTML |
| 3 | `html.js` 全文（439 行）通读 | 零 scale/transform/wheel/zoom 实现；`07528f67`（08-16）自 fileViewers.js `viewHtml` 逐字迁移，`git log -S viewHtml` 显示 fileViewers.js 时代也无 zoom commit |
| 4 | 09-01 后 `canvas.js` 历史 | **零 commit** |
| 5 | 09-01 后 `viewers/` 历史 | 仅 `b581226c`（09-01 11:19）保读位 fix——纯增量（scrollTop 恢复），无任何缩放代码被移除 |
| 6 | `canvas.js` 全文 zoom/scale/wheel 扫描 | 唯一命中 L516 `scale(0.7)` 为 tab 开启动画，与 viewer 无关 |

近期 Canvas 域改动逐个核对：元素选择器小修（`0b0583f4`/`1897282c`）、nf-file 白名单修复链（`7ce7afd9`/`579f1fd4`，后端域）、保读位（`b581226c`）——无一触碰缩放。A（退化）假设排除。

## 二、方案选型与理由

**主手势 = 向 iframe 注入 wheel/键盘转发桥（方向①）+ 共享玻璃工具条兜底（方向③必做）**，并采纳方向②的 ⌘/ctrl 键位（桥内一并提供）：

- 跨框事件（wheel/keydown/dblclick/gesture）**均不冒泡到父文档**，任务提醒属实；「外层容器 capture 监听」只覆盖 pane 边缘非 iframe 区。鼠标悬停 iframe 上是主场景（硬原则 a），唯有注入桥可达。
- 桥只拦截 **ctrl/cmd+wheel**（preventDefault 后转发 `{_nfZoomWheel:{deltaY,x,y}}`）与 **⌘/ctrl +/−/0**；**纯滚轮零拦截**——iframe 文档原生滚动不受影响（硬原则 b，spec Z5 实证 scrollTop 增长、缩放态纹丝不动）。
- 父侧 sender 校验 `e.source === iframe.contentWindow`（与既有 `_nfRefPick` 通道同纪律），再按当前 transform 把 iframe 内容坐标映射为 pane 锚点。
- **兜底**：复用 `viewers/zoom.js` 共享引擎——玻璃工具条（−/徽标/+/Fit 复位）+ pane 悬停时 ⌘±/0 全局键。复位手段选**工具钮 Fit（=100%）+ 键盘 ⌘0（桥内）**，未选双击容器：双击同样不跨框，桥内再拦双击会吞掉海报页内合法双击交互，得不偿失。
- 缩放本体 = 对 iframe 整体 `translate+scale`（origin 0 0，与 image viewer 同款数学），iframe 内部 DOM 不可控也不需可控：transform 语义即「整页 viewport 缩放」，内部布局/滚动条随之视觉缩放。
- 冲突取舍写明：ctrl/cmd+wheel 与纯滚轮二分——前者属查看器缩放（与浏览器 pinch 惯例一致），后者归页面滚动；Safari 专有 GestureEvent 不跨框，未桥接（WKWebView pinch 若表现为 ctrl+wheel 则已被桥覆盖），列入遗留。

## 三、改动清单（文件/函数级）

### `src/main/resources/web/js/viewers/html.js`（+108/-1）
| 位置 | 改动 |
|---|---|
| 模块 import | + `import { enableViewerZoom } from './zoom.js'` |
| 新常量 `zoomBridgeScript` | 注入 srcdoc 的休眠脚本：ctrl/cmd+wheel preventDefault+转发、⌘/ctrl +/−/0 转发（与 themePropScript 等同模式） |
| 新函数 `bindZoomBridge()` + `_zoomBridgeBound` | 父侧单实例 message 监听：e.source 匹配活动 `[data-nf-canvas-html]` iframe → 命中 pane 的 `_nfHtmlZoom` 控件执行缩放/复位 |
| `viewHtml()` 顶部 | + `bindZoomBridge()` |
| `viewHtml()` srcdoc 装配行 | + `${zoomBridgeScript}` |
| `viewHtml()` iframe 创建处 | + `transformOrigin='0 0'`（默认 50% 50% 会引入 (1−s)·center 偏移——spec 钉出的真 bug） |
| `viewHtml()` 缩放块（新增 ~40 行） | 状态 `zs/zx/zy` + `zoomApply`（钳制 0.3–3.0 + pivot 锚定数学）+ `zoomReset` + `enableViewerZoom(pane,{min:0.3,max:3,…})`；pane 上挂 `_nfZoomDestroy`（source-mode 往返/导航逃逸先 retire 旧引擎，防 pane 监听器累积）与 `_nfHtmlZoom`（桥用：`panePoint` 坐标映射/`zoomBy`/`reset`） |
| `viewHtml()` 导航逃逸 fallback | 替换前 `pane._nfZoomDestroy?.()` |

### `src/main/resources/web/css/split.css`（+9）
- `.canvas-tab-pane[data-type="html"] .canvas-zoom-bar { top:auto; right:112px; bottom:18px }`——HTML pane 缩放条移至右下角簇（与 source/ref 圆钮同簇）。**原因**：默认右上位置实测压住海报页头交互区，回归 spec 点击被拦截；image/PDF 不受影响（选择器 scoped）。

### 新增测试
- `tests/canvas-html-zoom.spec.mjs`（307 行）：23 断言。
- `tests/fixtures/canvas-html-zoom/poster.html`：自足 fixture（#zoom-marker 锚点探针 + 30 行 filler 可滚文档，零外部依赖）。

**零触碰**：pdf.js/image.js/zoom.js/canvas.js/shared.js（addSourceToggle 等）、sandbox 属性、防递归嵌套、resolveLocalFiles、既有 postMessage 通道（_nfThemeVars/_nfRefPick/_nfImagePreview）。

## 四、验证结果（全部真实前台执行）

**新 spec `tests/canvas-html-zoom.spec.mjs`：23 PASS / 0 FAIL**

| 断言 | 数字 |
|---|---|
| Z1 工具条存在/初始 100%/无 transform/sandbox 快照 | bars=1，pct=100%，raw=none，sandbox 四元组不变 |
| Z2 工具钮放大 | computed scale=1.25，徽标 125% |
| Z3 工具钮复位 | 恒等矩阵 + 100% |
| Z4 ctrl+wheel 缩放生效 | scale=1.43294（理论 1.0015^240） |
| Z4 **锚点数学** tx=my·(1−s) | 实测 −116.029 vs 期望 −116.028（**0.001px**） |
| Z4 **锚点不变 ±1px**（几何合成） | **Δx=0.001 Δy=0.001** |
| Z4 elementFromPoint 命中不变 | 光标下仍是 #zoom-marker |
| Z4 徽标同步 | 143% = round(1.43294×100) |
| Z5 纯滚轮 | scrollTop=240（内部原生滚动），transform/pct 逐字符不变 |
| Z6 上钳制 | scale=3、徽标 300%；续点仍 3.0 |
| Z7 下钳制 | scale=0.3、徽标 30% |
| Z8 复位 100% | 恒等矩阵 |
| Z9 键盘桥（iframe 聚焦后） | Control+= → 1.5625；Control+0 → 100% |
| Z10 暗色 | scale 1.43294 + 143% 同步，复位 100% |
| R0 | 亮/暗两 context 主文档 0 pageerror |

**回归红线**：`tests/canvas-html-interactive.spec.mjs` **17/17 全绿**（T0 负控制组 4 + T1 sandbox 2 + T2 数据模块 3 + T3 交互链 4 + T4 主题 2 + T5 防递归 1 + R0 1）。

**check-js-types**：改动前 `0 errors (baseline 326)` → 改动后 `0 errors (baseline 326)`，**零新增**。
**node --check**：`viewers/html.js`、`tests/canvas-html-zoom.spec.mjs` 语法通过。
**截图**：4 张（下方路径）。

### 修复过程中被 spec 钉出的真 bug（已修）
初版未设 `transform-origin:0 0`：CSS 默认 50% 50% 使视觉锚点偏移 (1−s)·(paneW/2, paneH/2)=（实测 −136.38, −195.91，pane 半宽/半高精确吻合）。±1px 断言以 0.001px 复验通过——验证口径有效性的直接证据。

### 环境纪律执行记录
静态服务 spec 内置、端口 8123（≥8100，起前 `lsof -ti :8123` 确认空闲）；Playwright 失败未重试——首次 2 FAIL 即降级为几何探针脚本定位根因后一次修复；探针临时文件用毕已删（`tests/tmp-geom-probe.mjs`；误删的历史临时文件 `tests/tmp-canvas-zoom-verify.mjs` 已 `git restore` 还原工作区）；未跑 sbt，未触碰 8080/宿主（PID 94384）。

## 五、截图（绝对路径）

- `/tmp/nb-canvas-html-zoom/20260904_canvas-html-zoom-light-zoomed.png`（亮·143%）
- `/tmp/nb-canvas-html-zoom/20260904_canvas-html-zoom-light-reset.png`（亮·100%）
- `/tmp/nb-canvas-html-zoom/20260904_canvas-html-zoom-dark-zoomed.png`（暗·143%）
- `/tmp/nb-canvas-html-zoom/20260904_canvas-html-zoom-dark-reset.png`（暗·100%）

## 六、主仓 commit

- `9ccde4994f0b6b1c2b86779ab493c3d675a83ffb`（main，未 push）——4 文件 +477/−1

## 七、生效说明

**运行时未生效**：需前端产物重建 + 宿主重启（本批未做）。作者在下个重启窗口用真实海报 `poster-v8.html` 真人复验：预期 ctrl/cmd+滚轮（悬停页面上）缩放、右下角玻璃条 −/143%/+/Fit、⌘±/0、纯滚轮正常滚页面。

## 八、遗留问题

1. **Safari 专有 GestureEvent（trackpad pinch）不跨框未桥接**：macOS WKWebView 若将 pinch 表现为 ctrl+wheel 已被桥覆盖；若表现为原生 gesture 事件则捏合无效（工具条/⌘键/ctrl+wheel 不受影响）。可在后续按需补 gesture 桥。
2. **双击复位未做**：双击不跨框，桥内拦截会吞页面合法双击（如选词）；已由 Fit 钮 + ⌘0 覆盖复位需求。
3. **zoom 键盘桥在 iframe 内拦截 ⌘±**：若某海报页自身需要浏览器级 ⌘± 会被改为查看器缩放——对预览场景是期望行为，记录在案。
4. **source-mode 往返后缩放态不保留**（回渲染视图重置 100%）：与 iframe 重建（读位保留机制同理）一致的最小意外原则，未做跨态记忆。
5. 暗色截图中 fixture 页面自身仍为浅色——海报文件自带硬编码配色，属内容层；查看器层（玻璃条/pane chrome）已正确适配双主题。
