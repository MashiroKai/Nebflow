---
name: card-design
description: Card 工具 HTML 输出的视觉设计规范。当使用 Card/Pop 工具生成 HTML 卡片、内联 SVG 图表、可视化报告时使用。覆盖颜色、字体、圆角、SVG 嵌入、暗色模式适配等所有样式规则。
---

# Card Visual Design Guidelines

Follow these strictly. They override any conflicting defaults.

## 禁止事项

- **禁止 Emoji** — 用排版和间距创造视觉趣味，不要用 emoji
- **禁止硬编码 hex 颜色** — 必须用 CSS 变量（见下文）
- **禁止"强调条"**（accent bar / highlight strip）— 左侧竖条、顶部横条等用颜色填充的装饰性条状元素一律不用。用边框、间距、字体粗细区分层级
- **禁止引用外部 CSS/JS** — HTML 必须自包含，所有样式内联
- **禁止嵌入栅格图（PNG/JPG）via `<img>`** — 硬编码颜色无法适配暗色模式，始终生成 SVG

## 颜色：必须用 CSS 变量

| 用途 | 变量 |
|------|------|
| 正文文字 | `var(--color-text)` |
| 背景 | `var(--color-bg)` / `var(--color-surface)` |
| 仅标题/说明文字 | `var(--color-text-muted)` — 对比度太低，不可用于正文 |
| 状态指示 | `var(--color-primary)` / `var(--color-success)` / `var(--color-error)` / `var(--color-warning)` |

CSS 变量保证明暗模式下的最大对比度。

## SVG 嵌入：两种策略

### Strategy A: `<img src>` — 简单可靠（默认选择）

```bash
dot -Tsvg -o /tmp/output.svg input.dot
```
```html
<img src="/tmp/output.svg" style="width:100%;height:auto;display:block" alt="Diagram"/>
```

- 浏览器原生处理 SVG 缩放，尺寸始终正确
- SVG 自包含：graphviz/matplotlib 已为每个节点设置正确文字颜色
- 在沙箱 iframe / shadow DOM 中可靠渲染
- **不支持暗色模式 CSS 覆盖**（SVG 作为图片渲染，CSS 变量无法穿透）。暗色模式重要时用 Strategy B

### Strategy B: 内联 SVG — 支持暗色模式 CSS 覆盖

1. 生成 **SVG 格式**输出
2. 读取 SVG 文件，剥离 `<?xml?>` 和 `<!DOCTYPE>` 声明
3. 将 `width="..."` 替换为 `width="100%"`，删除 `height` 属性（让 viewBox 控制宽高比）
4. **将 SVG 直接粘贴到 HTML 中** — 不要包外层 `<svg>`
5. 添加 `<style>` 块用 CSS 覆盖

**警告：** 内联 SVG 在某些沙箱环境中可能渲染尺寸不正确。如果卡片太小，切换到 Strategy A。

### CSS 覆盖模板 — graphviz 图

```html
<div class="gv-diagram">
  <svg width="100%" viewBox="...">...</svg>
</div>
<style>
.gv-diagram svg { width: 100%; height: auto; display: block; }
/* 隐藏 graphviz 白色背景 */
.gv-diagram svg > g > polygon { fill: transparent !important; }
/* 边标签跟随主题 — 不要覆盖 .node text，graphviz 已为每个节点设置正确对比度 */
.gv-diagram .edge text { fill: var(--color-text-muted) !important; }
/* 边：无填充，muted 描边 */
.gv-diagram .edge path { fill: none !important; stroke: var(--color-text-muted) !important; }
/* 箭头 */
.gv-diagram .edge polygon { fill: var(--color-text-muted) !important; stroke: none !important; }
</style>
```

### CSS 覆盖模板 — matplotlib 图表

```html
<div class="mpl-chart">
  <svg width="100%" viewBox="...">...</svg>
</div>
<style>
.mpl-chart svg { width: 100%; height: auto; display: block; }
/* 轴标签和刻度文字跟随主题 — 数据标签保持原色 */
.mpl-chart .tick text, .mpl-chart .axis text { fill: var(--color-text-muted) !important; }
/* 覆盖黑色轴线、刻度、脊线 — 数据线保持原色 */
.mpl-chart path[style*="#000000"] { stroke: var(--color-text-muted) !important; }
.mpl-chart use[style*="#000000"] { stroke: var(--color-text-muted) !important; }
/* 图例/patch 边框 */
.mpl-chart path[style*="fill: #ffffff"], .mpl-chart path[style*="fill: white"] {
  fill: var(--color-surface) !important;
}
</style>
```

**关键原理：** `<style>` 块中的 CSS `!important` 同时覆盖 SVG presentation attributes 和 inline `style="..."` — 主题变量始终胜出。

### 栅格图不可避免时（照片、截图、复杂渲染）

用 `var(--color-bg)` 背景的容器包裹，加 `border-radius`。**不要**应用 CSS 滤镜（invert 等）— 会不可预测地扭曲颜色。

## 视觉默认值

- **圆角：** 卡片 16px，按钮 10px，输入框 8px，徽章 9999px
- **字体：** `-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", sans-serif`
- **字号：** 正文 13-14px / 400，标题 16-20px / 600，标题紧字距
- **动画：** 仅在有助于理解时使用（200-400ms，ease-out 入场）。遵循 `prefers-reduced-motion`

## 自包含 HTML

HTML 必须自包含（所有样式/标签内联，无外部 CSS/JS）。本地文件路径在 src/href 中由后端自动服务。**必须使用绝对路径**（如 `/Users/you/project/plot.png` 或 `~/project/plot.png`）。相对路径不会被解析。

## Canvas 交互选择按钮（_nfAskAnswer）

当 Pop/Canvas 的 HTML 需要承载「视觉类选择题」的直选回传（看图即选，见 `~/.nebflow/docs/Nebflow/askuser-canvas-integration-spec.md` §11.2）时，在 HTML 内嵌选择按钮并走 `_nfAskAnswer` postMessage 回传。

**标准片段（html viewer 以 sandbox iframe 渲染时嵌入）：**

```html
<button class="nf-pick" data-answer="方案 A · 晨雾">选择此方案</button>
<script>
(function(){
  var SESSION_ID = '...';      // agent 生成 HTML 时注入（见下方 sessionId 注入说明）
  var QUESTION_INDEX = 0;      // 问题索引，与 AskUserQuestion 的 question 序对应
  document.querySelectorAll('.nf-pick').forEach(function(btn){
    btn.addEventListener('click', function(){
      parent.postMessage({ _nfAskAnswer: {
        sessionId: SESSION_ID, questionIndex: QUESTION_INDEX,
        answer: btn.getAttribute('data-answer')
      } }, '*');   // targetOrigin 必须用 '*'，见下方教训
    });
  });
  // 5 行 _nfAskState 锁定 listener：回答后父窗口广播，锁定所有选择按钮
  window.addEventListener('message', function(e){
    if (e.data && e.data._nfAskState && e.data._nfAskState.answered) {
      document.querySelectorAll('.nf-pick').forEach(function(b){ b.disabled = true; });
    }
  });
})();
</script>
```

### 硬性规则

- **`data-answer` 必须与 AskUserQuestion 选项的 `label` 完全一致**（逐字符，含标点/空格）。父窗口不做模糊匹配，label 不在该题 options 集合内一律丢弃（规格书 §8 E7）。
- **`targetOrigin` 必须用 `'*'`**，禁止用 `window.location.origin`。
- **仅限单选**：多选 / dependsOn 分支题 / Other 自由输入不走 Canvas 直答（规格书 §3.3），多选走卡片确认路径。
- 按钮视觉遵守 card-design 视觉默认值 + 克制基调：`background: var(--color-surface)` + `border: 1px solid var(--color-text-muted)` 级描边，圆角 10px，字号 13px/500；**禁止**高饱和实心大按钮。

### ⚠ 教训：sandbox iframe 的 origin 是 opaque，targetOrigin 必须用 `'*'`

Canvas HTML 由 html viewer（`web/js/viewers/html.js`）以 **sandbox iframe**（`srcdoc` + `sandbox="allow-scripts allow-same-origin allow-forms allow-popups"`）渲染。**sandbox iframe 的 `window.location.origin` 是 opaque 字符串 `"null"`**，作为 `postMessage` 的 `targetOrigin` 参数会直接抛 TypeError——「The string did not match the expected pattern」，消息未发出，Canvas 内点选完全失效（2026-08-25 实测确认）。改用 `parent.postMessage(payload, '*')` 后全链路打通：父窗口 chat.js 的 `_nfAskAnswer` listener **不校验 `e.origin`**，靠 payload 内部校验 `sessionId`/`questionIndex`/`label`（规格书 §3.2 安全约束），用 `'*'` 安全。

### sessionId 注入说明

`SESSION_ID` 由 agent 生成 HTML 时从**当前 AskUserQuestion 卡片的 sessionId** 注入（agent 在创作该 HTML 时知道问题上下文，直接写死字符串即可）。若同一 Canvas 页面对应多个问题，需在 `<button>` 上另行标注 `data-question-index` 并在 listener 中按 `btn` 取对应 QUESTION_INDEX，勿用单一全局变量。
