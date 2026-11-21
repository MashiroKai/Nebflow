// progressPane.js — 取数占位面的**单点**（进度环 / 失败态 / 终态注记）。
//
// 来源（pop-optimistic-canvas 批 2026-11-18）：这三件原先内联在 attachmentPreview.js
// （imgdoor #542 档3），只能被附件预览腿使用。作者本批令「先乐观打开在 canvas 占位防
// 丢失，然后在 canvas 用一个环来显示加载进度」把**同一个体验**要求同时落到 Pop 文件卡
// 腿上 ⇒ 若把三件复制一份到 canvas/popArtifacts 就是**第二套环体系**（本批明令禁）。
// 故原样抽出为叶子模块：attachmentPreview.js 与 canvas.js 双方 import 同一份实现，
// 环的 DOM 结构 / 类名（`.nf-progress-*`）/ 数据读数面（`data-nf-progress`、
// `data-nf-pct`、`data-nf-error`、`data-nf-note`）逐字节不变 —— 样式单源仍是
// css/split.css 的 `.nf-progress-pane` 族，本模块不新增任何 markup 变体。
//
// 模块纪律：叶子 —— 只 import i18n（t()）。不 import canvas / nfFileCache / nfTicket，
// 不成环（scripts/check-circular.mjs 面）。

import { t } from './i18n.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
/** 环半径 18（viewBox 44×44）——与 split.css `.nf-progress-ring` 的 44px 盒同源。 */
const PROGRESS_RING_R = 18;
const PROGRESS_RING_C = 2 * Math.PI * PROGRESS_RING_R;

/** @typedef {{ setProgress: (loaded: number, total: number) => void, dismiss: () => void }} ProgressHandle */

/** 占位面的读数面标记（唯一认脸口径：本模块产出的占位 = 带 data-nf-progress 的
 *  那个元素）。查占位/撤占位一律走它，禁按类名第二套判断。 */
const PROGRESS_ATTR = 'data-nf-progress';

/**
 * 撤除本 pane 上的进度占位（幂等；`renderProgressPane` 之后任何时刻可调）。
 *
 * 为什么这是一个独立出口（card-visible-reconcile 批 2026-11-21，作者 11:41 报障
 * 「内容已完整渲染但『正在打开…』悬浮不收」）：占位由 `renderProgressPane` 挂进
 * pane，而**撤除**原先没有任何出口 —— 取数成功后的渲染腿（attachmentPreview.js
 * `renderIntoPane` → fileViewers.js `renderFile` → 某个 viewer.render）只负责挂真容：
 * 多数 viewer 先清 pane（`pane.replaceChildren()`，或等价的整体清空）顺手带走了占位，
 * 但 **markdown viewer 不清 pane**（它只 `remove()` 自己那类 `.canvas-md-scroll`
 * 旧面，再 `pane.appendChild(mdScroller)`，见 viewers/markdown.js:1401-1424）——
 * 于是占位 `.nf-progress-pane`（height:100% 的居中盒）与正文并排同屏，
 * 转圈永不收。根因不是「某个 viewer 漏了一行」，而是**占位的生命周期没有挂钩
 * 到「真容出现」这个事实**：撤除既不在占位的所有者里，也不在渲染单点里。
 *
 * 故本函数是撤除的**单点**，三个调用面共用：① 占位自身的 MutationObserver
 * （真容一出现即撤 —— 不依赖任何 viewer 的返回值）；② fileViewers.renderFile
 * （渲染单点显式挂钩，兜住「渲染完成但观察器不可用」）；③ 失败/注记面板
 * （它们 `replaceChildren` 连带撤掉占位，语义等价）。
 * @param {HTMLElement|null|undefined} paneEl
 */
export function dismissProgressPane(paneEl) {
  if (!paneEl || !paneEl.querySelectorAll) return;
  paneEl.querySelectorAll(`:scope > [${PROGRESS_ATTR}]`).forEach((n) => n.remove());
}

/** 该 pane 上是否还有占位（诊断/兜底判据用）。 */
export function hasProgressPane(paneEl) {
  return !!(paneEl && paneEl.querySelector && paneEl.querySelector(`:scope > [${PROGRESS_ATTR}]`));
}

/**
 * 档3 进度面板：百分比环（Content-Length 可得）或转圈（不可得）。
 * 数据读数面：`data-nf-progress` = determinate|indeterminate、`data-nf-pct` = 0..100。
 * @param {HTMLElement} paneEl
 * @param {string} label
 * @returns {ProgressHandle}
 */
export function renderProgressPane(paneEl, label) {
  paneEl.classList.add('scrollable');
  paneEl.replaceChildren();   // 组件契约 R5：新文件禁裸 innerHTML 赋值
  const wrap = document.createElement('div');
  wrap.className = 'nf-progress-pane';
  wrap.setAttribute(PROGRESS_ATTR, 'indeterminate');
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'nf-progress-ring');
  svg.setAttribute('viewBox', '0 0 44 44');
  const mkCircle = (/** @type {string} */ cls) => {
    const c = document.createElementNS(SVG_NS, 'circle');
    c.setAttribute('class', cls);
    c.setAttribute('cx', '22');
    c.setAttribute('cy', '22');
    c.setAttribute('r', String(PROGRESS_RING_R));
    return c;
  };
  const bg = mkCircle('nf-progress-ring-bg');
  const fg = mkCircle('nf-progress-ring-fg');
  fg.style.strokeDasharray = String(PROGRESS_RING_C);
  fg.style.strokeDashoffset = String(PROGRESS_RING_C);
  svg.append(bg, fg);
  const spinner = document.createElement('div');
  spinner.className = 'canvas-loading-spinner nf-progress-spinner';
  const pct = document.createElement('div');
  pct.className = 'nf-progress-pct';
  const lab = document.createElement('div');
  lab.className = 'nf-progress-label';
  lab.textContent = label;
  wrap.append(svg, spinner, pct, lab);
  paneEl.appendChild(wrap);

  // 挂钩「真容出现」这个事实（作者 11:41 报障的根因层修法）：占位自己盯着 pane，
  // 一旦出现**不是占位**的元素子节点（renderFile 的任一 viewer 挂出的真容，
  // 或是失败/注记面板），立刻撤除自己 —— 不依赖 viewer 记得清 pane、也不依赖
  // 渲染 promise 的解析时机（渲染腿卡住时占位也照撤，绝不「内容已出还转圈」）。
  /** @type {MutationObserver|null} */
  let obs = null;
  const stopWatch = () => { if (obs) { obs.disconnect(); obs = null; } };
  const reconcile = () => {
    if (!wrap.isConnected) { stopWatch(); return; }
    const realContent = Array.from(paneEl.children).some(
      (n) => n !== wrap && !n.hasAttribute(PROGRESS_ATTR),
    );
    if (realContent) { stopWatch(); wrap.remove(); }
  };
  if (typeof MutationObserver === 'function') {
    obs = new MutationObserver(reconcile);
    obs.observe(paneEl, { childList: true });
  }

  return {
    /** @param {number} loaded @param {number} total */
    setProgress(loaded, total) {
      if (!wrap.isConnected) return;
      if (total > 0) {
        wrap.setAttribute(PROGRESS_ATTR, 'determinate');
        const p = Math.max(0, Math.min(100, Math.round((loaded / total) * 100)));
        fg.style.strokeDashoffset = String(PROGRESS_RING_C * (1 - p / 100));
        pct.textContent = `${p}%`;
        wrap.setAttribute('data-nf-pct', String(p));
      } else {
        wrap.setAttribute(PROGRESS_ATTR, 'indeterminate');
      }
    },
    /** 显式撤除（渲染单点/兜底路径用；幂等）。 */
    dismiss() { stopWatch(); if (wrap.isConnected) wrap.remove(); },
  };
}

/** 可读失败原因（pop-optimistic-canvas 批）：把取数腿的失败**分型**成一句话，
 *  让「离线 / 取不到 / 凭据判定拒绝 / 类型不支持 / 超时」在占位里各不相同 ——
 *  修前只有一句 `Failed to open (HTTP 403)`，离线与凭据拒绝同貌。
 *
 *  判定只读既有读数（error.name / error.status / error.nfReason），不新增探测：
 *   · `AbortError` + reason='timeout' → 超时；reason='user'（关页签）→ 取消；
 *   · `TypeError`（fetch 网络层抛）→ 离线/网络不可达；
 *   · 401/403 → 凭据判定拒绝（带 X-Nf-Reason 时把网关判决附上，禁回显凭据值）；
 *   · 404 → 文件不存在/已被清理；
 *   · 其余 → 通用打开失败 + HTTP 码。
 *  @param {unknown} err
 *  @param {''|'timeout'|'user'} abortReason
 *  @returns {string} */
export function describeLoadFailure(err, abortReason) {
  const e = /** @type {{ name?: string, message?: string, status?: number, nfReason?: string }} */ (err || {});
  if (abortReason === 'timeout') return t('canvas.loadTimeout');
  if (abortReason === 'user') return t('canvas.loadCancelled');
  if (e.name === 'AbortError') return t('canvas.loadCancelled');
  if (e.status === 404) return t('canvas.loadNotFound');
  if (e.status === 401 || e.status === 403) {
    return e.nfReason ? `${t('canvas.loadDenied')} (${e.nfReason})` : t('canvas.loadDenied');
  }
  if (e.status === 429 || e.status === 503) return `${t('canvas.loadBusy')} (HTTP ${e.status})`;
  if (e.name === 'TypeError') return t('canvas.loadOffline');
  const suffix = e.status ? ` (HTTP ${e.status})` : '';
  return `${t('canvas.loadFailed')}${suffix}`;
}

/**
 * 可见失败态：就地错误面板 + **重试**键（禁静默 —— 空白页 / 永远转圈 / 静默回聊天
 * 三禁由本面板承担）。`data-nf-error=1` + `data-nf-retry=1` 是既有读数面（不动）。
 * @param {HTMLElement} paneEl
 * @param {unknown} err
 * @param {string} subject — 文件显示名（第二行，可读定位）
 * @param {() => void} onRetry
 * @param {''|'timeout'|'user'} [abortReason]
 */
export function renderProgressFailure(paneEl, err, subject, onRetry, abortReason) {
  if (!paneEl.isConnected) return;
  paneEl.classList.add('scrollable');
  paneEl.replaceChildren();   // 组件契约 R5：新文件禁裸 innerHTML 赋值
  const box = document.createElement('div');
  box.className = 'canvas-error';
  box.setAttribute('data-nf-error', '1');
  const msg = document.createElement('div');
  msg.textContent = describeLoadFailure(err, abortReason || '');
  const sub = document.createElement('div');
  sub.className = 'nf-error-subject';
  sub.textContent = subject || '';
  box.append(msg, sub);
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'viewer-retry-btn';
  retry.textContent = t('canvas.retry');
  retry.setAttribute('data-nf-retry', '1');
  retry.addEventListener('click', () => { if (paneEl.isConnected) onRetry(); });
  box.appendChild(retry);
  paneEl.appendChild(box);
}

/**
 * 终态注记（unsupported / 超上限）：同一面板家族 + 可读原因；pop-optimistic-canvas
 * 批起**同带重试键** —— 判据可能因文件被替换（重命名为认领扩展名）而改变，「类型不
 * 支持」并非永远不可重试，故不再是无路可走的死文案。
 * @param {HTMLElement} paneEl
 * @param {string} message
 * @param {() => void} [onRetry]
 */
export function renderProgressNote(paneEl, message, onRetry) {
  if (!paneEl.isConnected) return;
  paneEl.classList.add('scrollable');
  paneEl.replaceChildren();   // 组件契约 R5：新文件禁裸 innerHTML 赋值
  const box = document.createElement('div');
  box.className = 'canvas-error';
  box.setAttribute('data-nf-note', '1');
  const msg = document.createElement('div');
  msg.textContent = message;
  box.appendChild(msg);
  if (typeof onRetry === 'function') {
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'viewer-retry-btn';
    retry.textContent = t('canvas.retry');
    retry.setAttribute('data-nf-retry', '1');
    retry.addEventListener('click', () => { if (paneEl.isConnected) onRetry(); });
    box.appendChild(retry);
  }
  paneEl.appendChild(box);
}
