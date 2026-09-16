// listFlip.js — 列表增删/重排的「零跳变」衔接（FLIP: First-Last-Invert-Play，零依赖）。
//
// 取证依据（.nebflow/reports/20260917_tabrealtime-forensic.md §F-2 形态 3，唯一满足
// 「与容器重排的衔接（避免跳变）」要求的路径）：`remove`/`insert` 前后记录各卡
// getBoundingClientRect()，对**位移过**的卡做 240ms `transform` 反向补偿插值
// （缓动 cubic-bezier(0.4, 0, 0.2, 1) = split.css:503 flex 族同值）。
// 与 flowMapTab.js:1742/1749 的 rAF 位移插值同族——差别仅在把「绝对定位插值」换成
// 「transform 反向补偿」，故卡片自身布局属性（flex 项、宽度、文档流位置）一律不动。
//
// 边界（诚实声明）：FLIP 只补偿**位置**。本项目卡是 `flex: 1 1 280px` 的 wrap 项
// （flowCss.js:26-32），同行卡在增删时宽度也会被 flex 重分配——宽度变化不做插值
// （§F-2 明确否决 `max-height`/宽度折叠路线：flex-wrap 下会造成 2D 跳变），
// 属已知取舍，验收单按「位置过渡」口径判定。
//
// 降级（§F-3）：`prefers-reduced-motion: reduce` ⇒ 直接落位（不插值、不写任何内联样式）。
//
// 🔴 层叠陷阱（本批实测踩到并修复，证据 06-flip-attribution.txt）：动画层声明**优先于**
// 内联样式。若被补偿的卡同时挂着一条**填充态为 `forwards`/`both`** 的入场动画，
// 其 `to { transform: none }` 会一直压在动画层 ⇒ 这里的 invert 内联写入被无声吃掉，
// 表现为邻卡整段跳变。本项目侧的处置 = 入场动画填充模式改 `backwards`
// （projectPanel.css `.project-card.is-entering`，动画结束即不参与层叠）。
// 残余窗口（诚实声明）：同一条入场动画**正在播放**（<220ms）时 invert 仍会被压；
// 本面板实现上不可达——任何 FLIP 都发生在触发事件之后 ≥ 退场本体 280ms（`startCardExit`
// 的 `animationend`/兜底定时器），而「动画仍在播」要求该卡插入时刻晚于触发事件，矛盾。

/** 减动效总闸（与 flowMapTab.js:192 `animOff()` 同判据；本文件自持一份以免跨模块耦合）。 */
export function prefersReducedMotion() {
  try {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches === true;
  } catch (_) {
    return false;
  }
}

/** First 快照：`Map<HTMLElement, DOMRect>`，只收仍是 HTMLElement 且仍在文档中的元素。 */
export function snapshotRects(elements) {
  /** @type {Map<HTMLElement, DOMRect>} */
  const rects = new Map();
  for (const el of elements || []) {
    if (el instanceof HTMLElement && el.isConnected) rects.set(el, el.getBoundingClientRect());
  }
  return rects;
}

/**
 * Play：必须在 DOM 变更**之后**同步调用（变更前用 snapshotRects 取 First）。
 * @param {Map<HTMLElement, DOMRect>} before snapshotRects() 的返回值
 * @param {{duration?: number, easing?: string}} [opts]
 * @returns {number} 参与插值的元素数（0 = 无位移或已降级）
 */
export function playFlip(before, opts = {}) {
  if (prefersReducedMotion() || !before || !before.size) return 0;
  const duration = opts.duration ?? 240;
  const easing = opts.easing ?? 'cubic-bezier(0.4, 0, 0.2, 1)';
  const moved = [];
  for (const [el, first] of before) {
    if (!el.isConnected) continue;
    const last = el.getBoundingClientRect();       // Last
    const dx = first.left - last.left;
    const dy = first.top - last.top;
    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;  // 亚像素噪声不插值
    el.style.transition = 'none';
    el.style.transform = `translate(${dx}px, ${dy}px)`;  // Invert
    moved.push(el);
  }
  if (!moved.length) return 0;
  // 强制一次样式解析：使上面写入的 invert 成为 transition 的「起点」——否则同一批
  // 写入会被合并进一次 recalc，transition 不触发（读数会表现为"跳变"）。
  void document.body.offsetWidth;
  requestAnimationFrame(() => {
    for (const el of moved) {
      el.style.transition = `transform ${duration}ms ${easing}`;
      el.style.transform = '';                            // Play
    }
  });
  window.setTimeout(() => {
    for (const el of moved) { el.style.transition = ''; el.style.transform = ''; }
  }, duration + 60);
  return moved.length;
}
