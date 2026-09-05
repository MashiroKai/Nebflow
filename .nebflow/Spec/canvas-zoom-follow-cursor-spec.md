# Canvas 查看器 CAD 式鼠标跟随缩放 — 实现方案

> 版本：v1.0 draft | 探索人：Explorer | 日期：2026-08-19
> 触发：用户 03:06 反馈 Canvas 图片/PDF 缩放是固定点缩放，很难用，要求改为 CAD 式跟随鼠标位置的缩放
> 基线 commit：c2c7d9f3 `feat(canvas): independent zoom for image and PDF viewers (#302 follow-up)`

---

## 0. TL;DR — 结论摘要

当前代码（`c2c7d9f3`，与 HEAD `b3757827` 在 viewer 文件上零差异）**已经写了鼠标跟随的 pivot 逻辑**——但只在 `ctrl/cmd + 滚轮` 路径上生效。macOS 触控板用户最常用的 **pinch（双指捏合）手势**走 Safari gesture 事件，传 `pivot=null`，退化为**固定点缩放**。PDF 查看器的 pivot 锚定逻辑还存在变量命名错误和缺失水平锚定的 bug。

改动三处文件即可让两条缩放路径都变成 CAD 式跟随：
1. `js/viewers/zoom.js` — gesture 事件补光标追踪，传真实 pivot
2. `js/viewers/image.js` — pivot 数学已正确，无需改（列明已验证）
3. `js/viewers/pdf.js` — 修复 pivot 锚定命名错误 + 补水平锚定

行为不变的三条路径：toolbar 按钮（无鼠标位置）、键盘快捷键（无光标）、双击 toggle（已跟随光标）。

---

## 1. 现状分析

### 1.1 架构总览

Canvas 文件查看器的缩放由一个**共享引擎** + 两个**查看器适配器**组成：

```
fileViewers.js (注册表)
  ├─ viewers/image.js   → enableViewerZoom(pane, {applyScale, attachTo: viewport})
  ├─ viewers/pdf.js     → enableViewerZoom(pane, {applyScale, attachTo: pages})
  └─ viewers/zoom.js    ← enableViewerZoom() 共享引擎
        ├─ toolbar 按钮  → zoomBy(factor, null)        ← pivot=null，固定中心
        ├─ ctrl/cmd+滚轮 → zoomBy(factor, {x,y})      ← pivot=光标坐标 ✓
        ├─ Safari pinch   → applyScale(next, null)     ← pivot=null，固定中心 ✗
        └─ 键盘 +/-/0     → zoomBy(factor, null)        ← pivot=null，固定中心
```

`enableViewerZoom` 是引擎，拥有 toolbar 与事件绑定；查看器提供 `applyScale(scale, pivot)` 回调，`pivot {x,y}` 是**相对 attachTo 容器的坐标**，约定"保持该内容点不动"。

### 1.2 图片查看器（image.js）—— pivot 逻辑已存在且数学正确

`src/main/resources/web/js/viewers/image.js:46-71`：

```js
// ── Transform state: img at translate(tx,ty) scale(s), origin 0 0 ──
let s = 1, tx = 0, ty = 0;
const apply = () => { img.style.transform = `translate(${tx}px, ${ty}px) scale(${s})`; };

const applyScale = (next, pivot) => {
  const old = s;
  s = next;
  if (pivot) {
    const k = s / old;
    tx = pivot.x - (pivot.x - tx) * k;   // 保持光标下内容点不动
    ty = pivot.y - (pivot.y - ty) * k;
  }
  apply();
};
```

CSS `split.css:859`：`.canvas-image-viewport img { transform-origin: 0 0; }`。

**数学验证**（transform-origin 在左上角，translate 先位移再绕原点缩放）：
- 缩放前内容点坐标 `c = (P - T_old) / s_old`（P=pivot，T=translate，均相对 viewport）
- 缩放后期望 `T_new + s_new·c = P`，解得 `T_new = P - (P - T_old)·k`，k = s_new/s_old
- 代码 `tx = pivot.x - (pivot.x - tx) * k` ✓ 与公式完全一致

**结论：`ctrl/cmd + 滚轮` 路径下，图片缩放已经是 CAD 式跟随光标。** 用户感知到的"固定点缩放"不来自这条路径。

### 1.3 真正的"固定点缩放"来源——Safari gesture（pinch 手势）

`src/main/resources/web/js/viewers/zoom.js:88-97`：

```js
let gestureBase = 1;
const onGestureStart = (e) => { e.preventDefault(); gestureBase = opts.getScale(); };
const onGestureChange = (e) => {
  e.preventDefault();
  const next = clamp(gestureBase * e.scale);
  opts.applyScale(next, null);   // ← pivot=null → 固定中心缩放
  setPct(next);
};
```

**关键事实**：macOS Safari/Chrome 的 `GestureEvent` **不携带坐标**（无 `clientX/clientY`）。用户在触控板上双指捏合缩放时：
1. 浏览器把 pinch 翻译成 `gesturestart` + 连续 `gesturechange`（`e.scale` 是缩放倍率）
2. 引擎调用 `applyScale(next, null)` → pivot 为 null → image.js 走 `if (pivot)` 的 else 分支，只改 `s` 不补偿 translate → **图片绕 transform-origin(0,0) 即左上角缩放，光标下的内容飞走**

这就是用户说的"固定点缩放、很难用"。macOS 触控板用户的本能缩放手势是 pinch，恰恰命中了这条退化路径。

> 注：`ctrl/cmd + 滚轮` 在触控板上需按住键双指滑动，不是本能操作，绝大多数用户不会用。鼠标用户滚轮不带修饰键时引擎直接 `return`（纯滚动），也得不到缩放。

### 1.4 PDF 查看器（pdf.js）—— pivot 逻辑有 bug

`src/main/resources/web/js/viewers/pdf.js:106-120`：

```js
const applyScale = (next, pivot) => {
  const old = scale;
  scale = next;
  const keep = pivot
    ? { ratio: next / old, top: pages.scrollTop + pivot.y, x: pivot.y }  // ← x 存的是 pivot.y（命名错误）
    : null;
  clearTimeout(debounce);
  debounce = setTimeout(() => {
    renderAll().then(() => {
      if (keep) pages.scrollTop = keep.top * keep.ratio - keep.x;        // ← 只调垂直滚动
    });
  }, 120);
  if (keep) pages.scrollTop = keep.top * keep.ratio - keep.x;            // ← 乐观更新同上
};
```

PDF 走的是 **pdf.js 重渲染**（不是 CSS transform）：每次缩放重新 `page.getViewport({scale})` 重画 canvas，靠 `scrollTop` 滚动锚定让光标下的内容尽量不动。三个问题：

1. **命名错误**：`x: pivot.y` —— 变量名 `x` 暗示水平坐标，实际存的是 `pivot.y`（垂直）。值碰巧正确（用于垂直锚定），但极易误读，且暗示原作者本想做水平锚定却写错了。
2. **无水平锚定**：`.pdf-pages` 是 `display:flex; align-items:center`（`split.css:881-890`），页面水平居中。放大后页面变宽，但容器不产生水平滚动（内容居中溢出被裁剪），光标下的水平内容点会偏移。对于窄视口或高倍缩放，水平偏移明显。
3. **乐观更新与重渲染不一致**：乐观更新用 `next/old` 比例算 `scrollTop`，但实际重渲染后页面尺寸由 `scale*dpr` 决定，且 `renderAll` 是异步逐页的，乐观值与最终值可能有偏差，导致缩放后内容轻微跳动。

### 1.5 其它缩放路径（pivot=null，均固定中心）

| 路径 | 代码位置 | pivot | 行为 |
|------|---------|-------|------|
| toolbar 放大/缩小按钮 | `zoom.js:70,72` | `null` | 固定中心（无鼠标位置，合理） |
| toolbar Fit 按钮 | `zoom.js:73` | — | 调 `reset()`，重新 fit（合理） |
| 键盘 cmd/ctrl +/-/0 | `zoom.js:29` | `null` | 固定中心（键盘无光标，合理） |
| 图片双击 toggle | `image.js:99-104` | 光标坐标 ✓ | 已跟随光标 |

toolbar 和键盘传 `null` 是**合理的**（无鼠标位置可用），保持现状即可。核心问题是 **gesture 事件本应有光标位置却传了 null**。

## 2. CAD 式鼠标跟随缩放原理

### 2.1 核心不变量

CAD 式缩放的不变量：**缩放前后，光标所指的内容点在屏幕上的位置不变**。即用户放大一张电路图时，鼠标下的那个焊盘始终钉在光标处，周围内容向外扩展/收缩。

### 2.2 两种等价实现

**方案 A — translate 补偿（当前 image.js 用的）**：

transform-origin 固定在左上角 `(0,0)`，transform = `translate(tx,ty) scale(s)`。缩放时同步调整 translate，使光标点的内容坐标不变：

```
T_new = P - (P - T_old) · (s_new / s_old)
```

- 优点：transform-origin 不变，无 CSS 抖动；平移与缩放统一在 translate 里，拖拽平移逻辑天然兼容
- 缺点：每次缩放要算 translate，公式不能写错

**方案 B — 动态 transform-origin**：

transform-origin 设为光标坐标 `(px, py)`，transform = `scale(s)`（无 translate 或 translate 另算）。缩放时浏览器自动绕 origin 缩放，光标点天然不动。但一旦还要平移，origin 与 translate 耦合，平移拖拽逻辑会变复杂。

**结论**：image.js 已用方案 A 且数学正确，**不改方案**，只补 gesture 路径的 pivot 传参。PDF 走重渲染+滚动，不适用 transform 方案，单独处理（见第 4 章）。

### 2.3 光标坐标的获取

所有缩放路径都需"光标相对 attachTo 容器的坐标"：

```
rect = attachTo.getBoundingClientRect()
pivot = { x: e.clientX - rect.left, y: e.clientY - rect.top }
```

- `wheel` 事件：`e.clientX/clientY` 直接可用 ✓（当前已这么做）
- `gesturechange` 事件：**无坐标**，需在 `gesturestart` 前用 `pointermove`/`mousemove` 持续追踪最近光标位置，gesture 期间用该位置作 pivot
- 键盘/toolbar：无光标，用容器中心 `{x: rect.width/2, y: rect.height/2}` 作 pivot（比 null 更一致，但 null 也行——见下）

## 3. 精确改动点

### 3.1 `js/viewers/zoom.js` — gesture 补光标追踪（核心修复）

**目标**：Safari pinch 手势也走 CAD 式跟随光标。

**改动位置**：`enableViewerZoom` 函数内，`onGestureStart`/`onGestureChange` 区域（当前第 88-97 行）。

**改动内容**：

```js
// ── Safari pinch (gesture events) ────────────────────────
// GestureEvent 不携带坐标，需用 pointermove 持续追踪最近光标位置，
// gesture 期间以该位置为 pivot 实现 CAD 式跟随缩放。
let gestureBase = 1;
let cursorPos = null;  // {x,y} 相对 host，最近一次光标位置

const onPointerMove = (e) => {
  const rect = host.getBoundingClientRect();
  cursorPos = { x: e.clientX - rect.left, y: e.clientY - rect.top };
};
host.addEventListener('pointermove', onPointerMove);

const onGestureStart = (e) => {
  e.preventDefault();
  gestureBase = opts.getScale();
  // 若 pinch 起手时光标不在容器内（极少见），回退到容器中心
  if (!cursorPos) {
    const rect = host.getBoundingClientRect();
    cursorPos = { x: rect.width / 2, y: rect.height / 2 };
  }
};
const onGestureChange = (e) => {
  e.preventDefault();
  const next = clamp(gestureBase * e.scale);
  opts.applyScale(next, cursorPos);   // ← null → cursorPos
  setPct(next);
};
```

**配套清理**：`destroy()` 里需 `host.removeEventListener('pointermove', onPointerMove)`（当前第 110-116 行的 destroy 块加一行）。

**行为变化**：
- 改前：pinch 缩放绕容器左上角（image，因 transform-origin 0 0）或视口中心，光标下内容飞走
- 改后：pinch 缩放以最近光标位置为锚点，光标下的焊盘/文字钉在原地，周围内容扩展——与 ctrl+滚轮体验一致

**为什么用 pointermove 而非 mousemove**：pointermove 统一覆盖鼠标/触控笔/触屏，且 `viewport` 已设 `touch-action: none`，pointer 事件不会被浏览器吞掉。`pointermove` 在 host 上持续更新 `cursorPos`，开销极低（仅赋值两个数）。

### 3.2 `js/viewers/image.js` — 无需改 pivot 数学（已正确）

image.js 的 `applyScale` pivot 补偿公式（第 61-71 行）**数学正确，不改**。

唯一可选优化：`dblclick` 的 toggle（第 99-104 行）当前用 `viewport.getBoundingClientRect()` 取 pivot，与 zoom.js 的 `host`（=viewport）一致，无需改。

**本文件零改动**，列入方案仅为明确"已验证正确、不动"。

### 3.3 `js/viewers/pdf.js` — 修复 pivot 锚定（见第 4 章）

PDF 走重渲染路线，改动较独立，详见第 4 章。

## 4. PDF 查看器单独处理方案

### 4.1 为什么 PDF 不能用 CSS transform 方案

PDF 用 pdf.js 把每页渲染成 `<canvas>`，缩放时 `page.getViewport({scale: scale*dpr})` 重设 canvas 尺寸并重绘，保证文字矢量清晰。这是**重渲染+滚动容器**模型，不是 transform 缩放。直接对 `.pdf-pages` 做 `transform: scale()` 会让 canvas 位图拉伸模糊，违背"重渲染保清晰"的设计初衷。

因此 PDF 的 CAD 式跟随只能靠**调整 `scrollTop`/`scrollLeft`** 让光标下的内容点在重渲染后回到原位。

### 4.2 当前 bug 详解

`pdf.js:109-119` 现有逻辑：

```js
const keep = pivot
  ? { ratio: next / old, top: pages.scrollTop + pivot.y, x: pivot.y }
  : null;
// ...
if (keep) pages.scrollTop = keep.top * keep.ratio - keep.x;
```

- `x: pivot.y` —— 命名错误，存的是垂直 pivot，用于从 `top` 里减回去。值碰巧对（垂直锚定），但变量名 `x` 极易让维护者误以为在做水平锚定
- **只调 `scrollTop`，从不调 `scrollLeft`** —— 水平方向无锚定
- `.pdf-pages` 是 `align-items:center`，页面水平居中，内容溢出被裁剪、无水平滚动条。窄视口或高倍缩放时，光标下的水平内容点会偏移到视口外

### 4.3 修复方案 — 补水平锚定 + 修正命名

**改动位置**：`pdf.js` 的 `applyScale` 函数（第 106-120 行）。

**改动内容**：

```js
let debounce = null;
const applyScale = (next, pivot) => {
  const old = scale;
  scale = next;
  const k = next / old;
  // 记录缩放前光标下的内容点（相对 pages 内容区，含 scroll 偏移）
  const keep = pivot
    ? {
        // 光标在内容区的绝对位置 = scroll + pivot
        contentTop: pages.scrollTop + pivot.y,
        contentLeft: pages.scrollLeft + pivot.x,
      }
    : null;
  clearTimeout(debounce);
  debounce = setTimeout(() => {
    renderAll().then(() => {
      if (!keep) return;
      // 重渲染后内容尺寸 ×k，让同一内容点回到光标处：
      // scroll_new = contentPoint × k - pivot
      pages.scrollTop  = keep.contentTop  * k - pivot.y;
      pages.scrollLeft = keep.contentLeft * k - pivot.x;
    });
  }, 120);
  // 乐观更新（渲染前先估算，让手势即时响应）
  if (keep) {
    pages.scrollTop  = keep.contentTop  * k - pivot.y;
    pages.scrollLeft = keep.contentLeft * k - pivot.x;
  }
};
```

**配套 CSS 改动**（`split.css` 的 `.pdf-pages`，第 881-890 行）：

当前 `align-items: center` 让页面居中，但居中布局下 `scrollLeft` 锚定失效（内容不产生可滚动宽度时 `scrollLeft` 始终 0）。两个选择：

- **选择 1（推荐，最小改动）**：保持 `align-items: center`，接受"垂直 CAD 跟随 + 水平居中"的折中。绝大多数 PDF 是竖向阅读，垂直跟随已解决主要痛点；水平方向页面始终居中，不会飞出视口，体验可接受。`scrollLeft` 锚定代码保留但在 center 布局下自然退化为 no-op，无害。
- **选择 2（完整 CAD）**：改 `align-items: flex-start` + 给 `.pdf-page` 加 `margin: 0 auto`（窄页面仍视觉居中，宽页面可水平滚动），此时 `scrollLeft` 锚定真正生效。代价：窄视口下页面贴左，需额外处理居中。

**建议采用选择 1**：与 PDF 阅读器主流行为一致（macOS Preview、Adobe Reader 缩放时也是垂直锚定为主），改动面最小，不引入水平滚动条回归。

### 4.4 乐观更新与重渲染一致性

乐观更新用 `k = next/old` 线性估算 scroll，重渲染后实际尺寸由 `scale*dpr` 决定。当 `dpr` 固定（已 `Math.min(devicePixelRatio, 2)`）时，CSS 尺寸 = `vp.width/dpr` 严格正比于 `scale`，乐观值与最终值一致，不会跳动。当前代码的 120ms debounce 在快速连续 pinch 时会合并为一次重渲染，体验流畅。保持 debounce 机制不变。

## 5. 验收条件

### 5.1 冒烟测试（硬性条件，第一项）

缩放跟随是**视觉行为**，curl 只能验证 `200 + HTML`，无法判定"内容是否钉在光标下"。验收必须以浏览器渲染实测为准。

```
1. 启动服务（sbt run 或开发态 vite dev server）
2. 打开 Canvas → 打开一张含细节的大图（电路图/架构截图）
3. pinch / ctrl+滚轮 缩放 → 光标下的特征点保持不动
4. 打开一份多页 PDF → pinch / ctrl+滚轮 缩放 → 光标下文本行保持不动
5. 截图 before/after 或录屏人工确认
```

> 当前环境无运行中的 nebflow 实例（PID 85671 为本 agent 自身）。验收清单列出可执行步骤，由实施方在 dev 环境用 Playwright 或人工浏览器执行。

### 5.2 图片查看器（image.js）验收 — CAD 跟随

| # | 验收条件 | 验证方式 |
|---|---------|---------|
| I1 | ctrl/cmd+滚轮缩放，光标下图像特征点保持不动 | 浏览器实测：滚轮缩放后光标处细节不位移 |
| I2 | 触控板 pinch 双指缩放，光标（双指中心）下特征点保持不动 | Safari + Chrome 实测：pinch 后光标处细节不位移 |
| I3 | 缩放倍率指示器（右上角百分比）实时更新 | 对照缩放前后百分比 |
| I4 | 缩放 clamp（min 5% / max 800%）生效，触界不再缩放 | 连续缩放观察停在极限 |
| I5 | pinch 过程无浏览器原生缩放干扰（preventDefault 生效） | 观察浏览器不随 pinch 缩放整个页面 |
| I6 | 拖拽平移仍工作，缩放后平移坐标正确 | 平移后缩放再平移，无跳变 |
| I7 | 双击左键切换 fit ↔ 100%，锚在光标点 | 双击观察切换锚在光标 |
| I8 | 缩至 fit 后内容完整居中，无裁切 | 默认打开即 fit，视觉确认 |

### 5.3 PDF 查看器（pdf.js）验收 — 垂直锚定

| # | 验收条件 | 验证方式 |
|---|---------|---------|
| P1 | ctrl/cmd+滚轮缩放，光标下文本行保持不动（垂直） | 浏览器实测：zoom 后光标所在行文字停留原处 |
| P2 | 触控板 pinch 缩放，光标下文本行保持不动（垂直） | Safari 实测（gesture 路径带 pivot） |
| P3 | 缩放后文字依然矢量清晰（重渲染保真） | zoom 到 200%+ 观察无马赛克 |
| P4 | 缩放倍率指示器实时更新 | 视觉确认 |
| P5 | 多页文档缩放后滚动位置合理，无跳页 | 翻几页再缩放实测 |
| P6 | 窄视口（~800px）放大后页面仍可见，垂直锚定有效 | 实测确认不飞出视口 |

### 5.4 行为对比（改前 vs 改后）— 通俗解释

| 场景 | 改前（当前） | 改后（目标） |
|------|------------|------------|
| 触控板双指捏合缩放图片 | 图片绕固定点缩放，鼠标指的地方跑掉，需不断重新定位 | 图片以手指中心为锚缩放，想放大哪就放大哪（CAD 式） |
| 触控板双指捏合缩放 PDF | 页面绕固定中心缩放，正在看的那行跑掉 | 页面以手指位置为锚缩放，正在看的那行钉在原地 |
| 鼠标 ctrl+滚轮缩放 | 已跟随光标（现状保留） | 不变 |
| 点击 +/- 按钮缩放 | 绕中心缩放（无鼠标位置，合理） | 不变 |
| 键盘 ctrl/cmd +/- 缩放 | 绕中心缩放（无光标，合理） | 不变 |

### 5.5 回归验证

| # | 验收条件 | 验证方式 |
|---|---------|---------|
| R1 | zoom 工具栏（-/百分比/+fit）功能不回归 | 手动点四键，百分比变化、fit 回原状 |
| R2 | 非 zoom 查看器不受影响（monaco/HTML/markdown 等不 import zoom.js，天然隔离） | 打开代码文件确认无缩放 UI |
| R3 | `destroy()` 无事件泄漏（tab 关闭后 pointermove 监听被移除） | 打开→关闭图片 tab 多次，控制台无重复事件、无报错 |
| R4 | `git diff` 改动范围仅 2 个文件（zoom.js + pdf.js），image.js 零改动 | `git diff --stat` 确认 |
| R5 | 浏览器控制台零 error | 打开图片+PDF tab，观察控制台 |

### 5.6 自查清单

- [x] 有冒烟测试（5.1，浏览器实测缩放跟随）
- [x] 冒烟测试用真实启动（sbt run + 真实浏览器），非 mock
- [x] 每条可自动化/可执行验证（浏览器实测 + git diff + 控制台）
- [x] 每条二值判断（特征点动/不动、有/无报错）
- [x] 覆盖回归（R1-R5）
- [ ] 端到端：从"用户 pinch 手势"到"光标下内容不动"的完整链路（5.2/5.3 覆盖）

## 6. 风险与回滚

### 6.1 风险

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| `pointermove` 在某些浏览器/触控板上与 gesture 事件时序不确定，`cursorPos` 可能滞后 | 低 | pinch 锚点略偏 | `onGestureStart` 时若 `cursorPos` 为 null 回退到容器中心；pointermove 持续更新，连续 gesturechange 会收敛到正确位置 |
| Safari `gesturechange` 的 `e.scale` 在快速捏合时跳变大，单次 applyScale 倍率过大 | 低 | 缩放过冲 | 已有 `clamp(min,max)` 兜底；且 image.js 的 translate 补偿对任意倍率都正确 |
| pdf.js 重渲染 120ms debounce 期间连续 pinch，乐观 scroll 与最终渲染不一致 | 低 | 缩放结束轻微跳动 | 乐观值与最终值在 dpr 固定时严格一致（4.4 已论证）；若仍跳动可缩短 debounce |
| Windows/Linux 无 gesture 事件，pinch 走 wheel+ctrl（浏览器翻译），路径不同 | — | 无影响 | 这些平台 pinch 本就走 `onWheel`（ctrlKey=true），已有 pivot ✓，无需 gesture 路径 |
| `cursorPos` 在 host 外的区域不更新，pinch 起手点偏离 | 极低 | 锚点为上次位置 | pointermove 绑在 host 上，pinch 必然在 host 内触发，cursorPos 总是 host 内最近位置 |

### 6.2 回滚

改动集中在 2 个文件、约 30 行，回滚简单：

```bash
# 回滚 zoom.js 的 gesture 追踪 + pdf.js 的 applyScale 修复
git checkout c2c7d9f3 -- src/main/resources/web/js/viewers/zoom.js
git checkout c2c7d9f3 -- src/main/resources/web/js/viewers/pdf.js
```

回滚后恢复"ctrl+滚轮跟随光标 + pinch 固定中心"的原状。image.js 全程零改动，无需回滚。

### 6.3 不做的事（明确边界）

- **不碰 lightbox.js**：聊天/Markdown 里的点击放大灯箱是单图预览，无连续缩放需求，不在本次范围
- **不碰 flowCanvas.js / canvas.js**：Canvas 画布的节点缩放是另一套逻辑（DAG 视图），与文件查看器无关
- **不引入新依赖**：无 npm 包、无新 vendor 文件，`pointermove` 是原生事件
- **不改 viewer 插拔协议**：`render(pane, ctx)` 接口不动，`enableViewerZoom(pane, opts)` 签名不动，仅 gesture 内部补追踪
- **不做水平 CAD for PDF**：选择 1（垂直锚定 + 水平居中）已足够，避免引入水平滚动条回归（4.3 已论证）

---

## 附：文件改动一览

| 文件 | 改动 | 行数 |
|------|------|------|
| `src/main/resources/web/js/viewers/zoom.js` | gesture 区加 `pointermove` 光标追踪 + `onGestureChange` 传 `cursorPos` + `destroy()` 移除监听 | ~+8 |
| `src/main/resources/web/js/viewers/pdf.js` | `applyScale` 修正命名 + 补 `scrollLeft` 水平锚定 | ~+6 |
| `src/main/resources/web/js/viewers/image.js` | **零改动**（pivot 数学已正确，已验证） | 0 |
| `src/main/resources/web/css/split.css` | **零改动**（选择 1，保持 `align-items:center`） | 0 |
