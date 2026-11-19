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
//
// 同任务双 flip（R1 追单 · 2026-09-17，来源 .nebflow/reports/20260917_projtabrealtime-verify.md §R1）：
// 一次 reconcile 内既「插入」又「搬动」时，`playFlip` 会在**同一同步任务**里被调用两次——
// 第一次写入的内联反转要等 rAF 才落成过渡起点，于是第二次量到的 First/Last **都带**这份
// 位移；第二次的反转必须与它**复合**（见 playFlip 内注释与 `pendingInvert`），否则该卡在
// 反转帧丢掉上一次的位移（实测 = 1 行矩形不连续 201.594px），随后才 240ms 过渡回位。
// 🔴 与上文「残余窗口」不是同一条窗口：那条讲**动画层压在** invert 上（本面板不可达），
// 本条讲**内联反转之间**的先后污染（同任务内必现，触发面 = 服务端名单序与 DOM 序不同）。

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

/** 读**本模块自己**留在元素上的待落帧反转（`translate(Xpx, Ypx)` ⇒ `{tx, ty}`）。
 *
 *  何时会有：同一同步任务内上一次 `playFlip` 写入的内联反转，要等 rAF 才被清成 `''`
 *  ⇒ 在那之前它一直挂在元素上（同时把该元素的实际矩形推离布局位置）。
 *  🔴 只认本模块的写入形态：其余形态（别处写的 transform / 动画中间值 / 矩阵形态）一律
 *  返回 null ⇒ 走覆盖旧行为，零回归面（不认识就不猜）。
 *  @returns {{tx: number, ty: number}|null} */
function pendingInvert(el) {
  const raw = el.style.transform;
  if (!raw) return null;
  const m = /^translate\((-?[\d.]+(?:e-?\d+)?)px,\s*(-?[\d.]+(?:e-?\d+)?)px\)$/.exec(raw);
  if (!m) return null;
  return { tx: parseFloat(m[1]), ty: parseFloat(m[2]) };
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
    // 同任务内的第二次 flip：上一次的内联反转还挂在元素上（待 rAF 落帧），于是本次量到的
    // **First 与 Last 都含它**（两者相减时它自己抵消 ⇒ (dx,dy) 仍是干净的「布局位移」）。
    // 反转的语义却是「让元素出现在**当前看起来**的位置」= 那个待落帧位移 + 本次布局位移
    // ⇒ 必须**复合**，不能覆盖：覆盖会把上一次的位移整段丢掉，该卡在反转帧错位一行，
    // 随后才 240ms 过渡回位（实测 jumpPx=201.594px、totalPx=0）。
    // 与「量 Last 前先把内联反转清掉」等价（First 已含该位移 ⇒ 只清 Last 侧即得复合值）；
    // 这里用算术复合，避免为测量改写 DOM——清而不复写还会吃掉未参与本次插值者的反转。
    const pending = pendingInvert(el);
    const tx = Math.round((dx + (pending ? pending.tx : 0)) * 1000) / 1000;
    const ty = Math.round((dy + (pending ? pending.ty : 0)) * 1000) / 1000;
    el.style.transition = 'none';
    el.style.transform = `translate(${tx}px, ${ty}px)`;  // Invert（与既存待落帧反转复合）
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
