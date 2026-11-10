// lightbox.js — Click-to-zoom preview for chat bubbles and Canvas viewers.
//
// Trigger surfaces:
//   1. Markdown images tagged .nf-zoom-img by renderMarkdownWithMath
//      (chat bubbles + canvas markdown viewer) — document click delegation.
//   2. Frames this app renders — the Canvas HTML viewer and the chat Card
//      (both inject viewers/shared.js `imgClickScript()`) — post
//      { _nfImagePreview: { src, path, alt } } to the parent window.
//   3. mermaid / inline-SVG fence diagrams (mermaid-viewer-stability 批
//      2026-11-06，作者令逐字「你气泡里渲染的 要点击能变成大图查看 canvas里的
//      也要能支持」) — the ONE fence slot (js/fenceKit.js) marks itself
//      `.nf-fence-done` and carries a real `.nf-figure-open` button; clicking the
//      diagram body goes through the same delegation with a drag/selection guard.
//      Both faces (chat bubble + Canvas document) ride the SAME fence renderer,
//      so this is one entry point, not two components.
// Close: ESC / backdrop click / close button.
//
// ── 两种内容模式，一个组件（不造第二个查看器）───────────────────────────
//   mode 'img'  —— 图片：既有路径逐字保留（账本单点取字节 / 票据纪律 / 失败面）。
//   mode 'svg'  —— 框图：**克隆**已经消毒并落在 DOM 里的那枚 SVG（矢量，零二次
//                  传输、零重新渲染），提供缩放 / 平移 / 复位 / 关闭。克隆时把
//                  mermaid 内部 `<style>` 与 `url(#id)` 引用的 id 整体改名，
//                  避免与文档里那一份撞 id（同一 id 的 CSS 会命中两份，且原图
//                  卸载后 marker 引用会断）。
// 缩放手势与控件刻意自成一套（不复用 viewers/zoom.js）：那是 Canvas 窗格内的
// 悬挂工具条 + ctrl/cmd+滚轮语义（普通滚轮留给文档滚动）；全屏浮层的语义相反
// ——浮层里没有别的可滚内容，普通滚轮/捏合/拖动都该直接作用于图。两套语义共存
// 于不同宿主，互不覆盖。

import { t } from './i18n.js';
import { pushLayer, popLayer } from './focusTrap.js';
import { createIconsIn } from './utils.js';
import { reMint, decodePathParam } from './nfTicket.js';
import { figureSize } from './fenceKit.js';
// 二次加载清剿批（2026-10-24 · #816 同法）：本地路径的字节改经 **nfFileCache 账本
// 单点**先行——`nf:<path>` 命中零抓取直显（气泡/文档渲染已入账的同件）；未命中 =
// 账本票据链取数 + 入账（恰一次）。失败回落既有 reMint 直取腿（下方逐字保留）。
import { nfViewerSource, nfCacheDrop } from './nfFileCache.js';
// 弹窗退场批（作者令 2026-10-31②）：手机层灯箱补左上返回（灯箱本已全屏；桌面零变化）。
import { mobileSheetBack } from './mobile/popSheet.js';

let overlayEl = null;   // lazily-created singleton
let imgEl = null;
let statusEl = null;
let lastFocus = null;   // focus restore target
// Blob URL currently backing the previewed image (see openLightbox), and an
// open-sequence guard so a slow fetch cannot paint over a newer open.
let objectUrl = null;
let openSeq = 0;
/** 本次打开的递增序号（落成 overlay 上的 data-nf-open-seq 读数）：验收据此断言
 *  「一次点击只开一次」——重复绑定会跳过两次。 */
let openCount = 0;

/* ── 图形（SVG 框图）模式的状态 ─────────────────────────────────────── */
/** 当前内容模式：'img' | 'svg'。 */
let lmode = 'img';
let stageEl = null;      // .nf-lightbox-stage —— 图形舞台（平移/缩放的作用面）
let canvasEl = null;     // .nf-lightbox-canvas —— 承载 scale/translate 的容器
let barEl = null;        // 缩放控件条
let pctEl = null;
/** @type {{scale: number, tx: number, ty: number, min: number, max: number}|null} */
let figState = null;
/** 拖动/捏合的指针登记（pointerId → 坐标）。 */
/** @type {Map<number, {x: number, y: number}>} */
const pointers = new Map();
/** 主指针拖动起点；移动超过 DRAG_SLOP 记为「拖动过」（用于抑制误触打开）。 */
let figDrag = null;
/** 文档级「起手→落点」位移登记（框图误触闸②的数据面）。 */
/** @type {{x: number, y: number, moved: number, hadSelection: boolean}|null} */
let pendingFigClick = null;
/** 全局委托只装一次（见 initLightbox 的幂等闸）。 */
let initDone = false;
/** 舞台拖动累计位移（用于「拖完松手那一下不许关浮层」）。 */
let stageDragMoved = 0;
const FIG_MIN = 0.3;
const FIG_MAX = 6;
/** 拖动容差（px）：起手在图上滑一下不等于点开（作者令「避免…拖动误触打开」）。 */
const DRAG_SLOP = 8;

/** The proxied local path behind an /api/nf-file URL ('' when not one). */
function nfPathOf(src) {
  if (typeof src !== 'string' || src.indexOf('/api/nf-file?') === -1) return '';
  const m = /[?&]path=([^&]*)/.exec(src);
  if (!m) return '';
  // imgref batch (2026-09-18): the ONE discipline, imported — a bare `+` in a
  // query string means a space (the JVM form encoder's spelling of a space),
  // so `decodeURIComponent` alone named a path that does not exist and the
  // re-mint asked for the wrong file.
  const p = decodePathParam(m[1]);
  return p === null ? '' : p;
}

/** True when `src` is the contentWindow of a frame THIS APP renders: a chat
 *  Card iframe (`data-nf-card-id`) or a Canvas HTML viewer iframe
 *  (`data-nf-canvas-html`).
 *
 *  card-image-zoom batch (2026-09-28): the `_nfImagePreview` channel used to be
 *  accepted from any same-origin window, and from any frame at all when the
 *  origin was opaque (the srcdoc case). Both the Canvas viewer and the Card are
 *  now producers, so the receiving side states the whitelist explicitly — the
 *  SAME discipline `viewers/shared.js` `isRenderedFrame` and `viewers/html.js`
 *  apply to `_nfOpenLocalFile` / `_nfZoomWheel` / `_nfRefPick`. A foreign
 *  window (another app window, a popup, a page that guessed the message shape)
 *  cannot open the viewer, and the Canvas leg stays in the whitelist.
 *  @param {Window|null} src @returns {boolean} */
function isAppFrameSource(src) {
  if (!src) return false;
  const frames = document.querySelectorAll(
    'iframe[data-nf-card-id], .canvas-tab-pane iframe[data-nf-canvas-html]'
  );
  for (const f of frames) {
    if (/** @type {HTMLIFrameElement} */ (f).contentWindow === src) return true;
  }
  return false;
}

/** The path gate: may `src` be opened as a viewer entry?
 *
 *  Rule (card-image-zoom batch): a `path`-BEARING payload is accepted only when
 *  that path is one the SENDER frame actually renders — i.e. it is the decoded
 *  `path=` value of an `<img>` in that frame's own document, which is the set
 *  of references resolved/ticketed at that card's render time. An arbitrary
 *  path handed over from outside never becomes a viewer entry through the
 *  `path` field.
 *
 *  🔴 Scope, measured (round-1 review, 2026-09-28): this gate constrains the
 *  `path` FIELD ONLY. A payload that omits `path` is passed through
 *  unconditionally (`!msg.path`), and `openLightbox` below then derives the
 *  entry from `nfPathOf(src)` — so a frame that is already an app frame CAN
 *  still name a file it does not render by sending a src-only payload
 *  (`{src: '/api/nf-file?path=%2Fetc%2F…'}`, no `path`). That is the
 *  pre-existing `src` fallback (present on e6d2e0e8 and exercised by every
 *  data-URI / remote-URL preview), not an entry this batch added: the batch
 *  NARROWED the channel (a sender must now be an app frame at all, and a
 *  `path` field must be a path that frame renders) without closing this leg.
 *  Closing it — gating src-only payloads by requiring that the frame renders
 *  that same `currentSrc` — is deferred to its own batch; it is not done here
 *  because the data-URI leg of this batch rides on precisely this fallback.
 *
 *  Returns false only for a path the frame does not render, or one that cannot
 *  be verified (fail closed).
 *  @param {Window} win @returns {(msg: {src: string, path?: unknown}) => boolean} */
function senderRendersPath(win) {
  /** @type {Set<string>} */
  let paths;
  try {
    const doc = /** @type {Document|null} */ (/** @type {any} */ (win).document);
    if (!doc) return () => false;
    paths = new Set();
    doc.querySelectorAll('img').forEach((img) => {
      const p = nfPathOf(/** @type {HTMLImageElement} */ (img).currentSrc
        || /** @type {HTMLImageElement} */ (img).getAttribute('src') || '');
      if (p) paths.add(p);
    });
  } catch (e) {
    // Unreadable frame (navigated cross-origin, discarded context): nothing can
    // be verified, so a path-bearing payload is refused rather than trusted.
    return (msg) => !msg.path;
  }
  return (msg) => !msg.path || paths.has(String(msg.path));
}

/** Drop the blob URL backing the current preview (it is ours to free). */
function releaseObjectUrl() {
  if (!objectUrl) return;
  try { URL.revokeObjectURL(objectUrl); } catch (e) { /* already revoked */ }
  objectUrl = null;
}

/* ── 可视屏幕对位（mermaid-inline-center 批 2026-11-07）─────────────────
   作者令逐字：「使用跟图片一样的点击打开后的查看逻辑。然后点开后要相对屏幕居中，
   现在有点歪。」

   两件事，一件是根因修复（不在这里 —— 舞台被 css/chat.css 的同名裸选择器钉成
   1100×810 左上角，见 css/lightbox.css 的批次注），另一件是「屏幕」到底指哪个
   屏幕：手机上 `position:fixed; inset:0` 盖的是**布局视口**，而用户看到的是
   **可视视口**（浏览器栏展开、地址栏收起、软键盘弹起时两者差几十至几百 px）。
   按布局视口居中 = 图在可视屏幕里偏上/被浏览器栏吃掉。

   所以浮层的盒改成读 `visualViewport` 的真值：可视视口相对布局视口有偏移或内缩
   时，把浮层的四边钉到可视区域（`syncViewportPin`），图仍然由舞台 flex 居中 ——
   中心 = 可视屏幕中心。桌面与「两者完全一致」的场合一律清掉内联盒，走 CSS
   （零行为变化，也不引入每帧计算）。 */
let viewportArmed = false;

/** 可视视口相对布局视口的真实盒；一致（桌面常态）时返回 null。 */
function visualBox() {
  const vv = typeof window !== 'undefined' ? window.visualViewport : null;
  if (!vv) return null;
  const w = Number(vv.width);
  const h = Number(vv.height);
  if (!(w > 0 && h > 0)) return null;
  const inset =
    Math.abs(w - window.innerWidth) > 1 ||
    Math.abs(h - window.innerHeight) > 1 ||
    Math.abs(Number(vv.offsetLeft) || 0) > 0.5 ||
    Math.abs(Number(vv.offsetTop) || 0) > 0.5 ||
    Math.abs((Number(vv.scale) || 1) - 1) > 0.01;
  return inset
    ? { left: Number(vv.offsetLeft) || 0, top: Number(vv.offsetTop) || 0, width: w, height: h }
    : null;
}

/** 把浮层钉到可视视口（或清空内联盒回到 CSS 整屏）。读数落 data-*，供验收断言。 */
function syncViewportPin() {
  if (!overlayEl) return;
  const box = visualBox();
  if (!box) {
    overlayEl.style.left = '';
    overlayEl.style.top = '';
    overlayEl.style.width = '';
    overlayEl.style.height = '';
    overlayEl.setAttribute('data-nf-vv-pinned', '0');
    return;
  }
  overlayEl.style.left = box.left + 'px';
  overlayEl.style.top = box.top + 'px';
  overlayEl.style.width = box.width + 'px';
  overlayEl.style.height = box.height + 'px';
  overlayEl.setAttribute('data-nf-vv-pinned', '1');
}

/** 视口变化（旋转 / 浏览器栏 / 键盘 / 页面捏合）：重钉浮层，图形模式重算图纸盒。 */
function armViewportPin() {
  if (viewportArmed || typeof window === 'undefined') return;
  viewportArmed = true;
  const on = () => {
    syncViewportPin();
    // 只在「图形模式且浮层真的开着」时重算图纸盒：关着时舞台盒是 0，重算没意义。
    if (lmode === 'svg' && overlayEl && overlayEl.classList.contains('on')) fitDiagramCanvas();
  };
  window.addEventListener('resize', on);
  window.addEventListener('orientationchange', on);
  const vv = window.visualViewport;
  if (vv && vv.addEventListener) {
    vv.addEventListener('resize', on);
    vv.addEventListener('scroll', on);
  }
  syncViewportPin();
}

function ensureDom() {
  if (overlayEl) return;
  overlayEl = document.createElement('div');
  overlayEl.className = 'nf-lightbox';
  overlayEl.setAttribute('role', 'dialog');
  overlayEl.setAttribute('aria-modal', 'true');

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'nf-lightbox-close';
  closeBtn.title = t('lightbox.close');
  closeBtn.setAttribute('aria-label', t('lightbox.close'));
  closeBtn.innerHTML =
    '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/>' +
    '<line x1="6" y1="6" x2="18" y2="18"/></svg>';
  closeBtn.addEventListener('click', closeLightbox);

  imgEl = document.createElement('img');
  imgEl.className = 'nf-lightbox-img';
  imgEl.alt = '';

  statusEl = document.createElement('div');
  statusEl.className = 'nf-lightbox-status';

  overlayEl.append(closeBtn, imgEl, statusEl);
  // Backdrop click closes; clicks on the image itself do not.
  overlayEl.addEventListener('click', (e) => {
    if (e.target === overlayEl) closeLightbox();
  });
  mobileSheetBack(overlayEl, closeLightbox);
  document.body.appendChild(overlayEl);
  // 浮层落 DOM 后再对位：`syncViewportPin` 只改内联盒，无观测、无回环。
  armViewportPin();
}

/* ── 图形模式：DOM / 缩放 / 平移 / 指针手势 ──────────────────────────── */

/** 图形模式的舞台与控件（惰性建一次，随后复用；不随图片模式出现）。 */
function ensureFigureDom() {
  ensureDom();
  if (stageEl) return;
  stageEl = document.createElement('div');
  stageEl.className = 'nf-lightbox-stage';
  stageEl.setAttribute('aria-hidden', 'false');
  canvasEl = document.createElement('div');
  canvasEl.className = 'nf-lightbox-canvas';
  stageEl.appendChild(canvasEl);

  barEl = document.createElement('div');
  barEl.className = 'nf-lightbox-bar';
  const mkBtn = (icon, label, fn) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'nf-lightbox-ctl';
    b.title = label;
    b.setAttribute('aria-label', label);
    const i = document.createElement('i');
    i.setAttribute('data-lucide', icon);
    b.appendChild(i);
    b.addEventListener('click', fn);
    return b;
  };
  barEl.appendChild(mkBtn('maximize-2', t('lightbox.zoomFit'), figFit));

  overlayEl.append(stageEl, barEl);
  createIconsIn(barEl);
  bindFigureGestures();
}

/** 适配盒分数（`--nf-lb-fit-w/h`，单源在 css/lightbox.css —— 图片模式与图形模式
 *  同一档；这里只读，不另写一套数）。读不到时回落到同值。 */
function fitFractions() {
  const cs = overlayEl ? getComputedStyle(overlayEl) : null;
  const frac = (raw, fallback) => {
    const m = /^([\d.]+)%$/.exec(String(raw || '').trim());
    if (!m) return fallback;
    const v = Number(m[1]) / 100;
    return v > 0 && v <= 1 ? v : fallback;
  };
  return {
    w: frac(cs && cs.getPropertyValue('--nf-lb-fit-w'), 0.92),
    h: frac(cs && cs.getPropertyValue('--nf-lb-fit-h'), 0.88),
  };
}

/**
 * 把图纸盒按「舞台实盒 × 适配盒分数」摆正（居中由舞台 flex 完成，不写死坐标）。
 *
 * 与图片模式同一条规则：整幅可见、比例不变、相对可视屏幕居中（作者令「使用跟图片
 * 一样的点击打开后的查看逻辑」「点开后要相对屏幕居中」）。
 * 舞台盒读的是**实测**矩形而不是 `innerWidth/innerHeight`：浮层会被
 * `syncViewportPin()` 钉到可视视口（手机浏览器栏/键盘），按布局视口算就会算出屏幕
 * 外的高宽 —— 旧实现既有这一条，也同时在横向超出舞台（实测 1152 > 1100）。
 * 图纸 padding 从计算样式读回来并扣掉：否则纸面内距会把图二次缩小、还让纸面
 * 越过适配盒。
 */
function fitDiagramCanvas() {
  if (!canvasEl || !stageEl) return;
  const size = figureSize(canvasEl.firstElementChild);
  const r = stageEl.getBoundingClientRect();
  const f = fitFractions();
  const padX = parseFloat(getComputedStyle(canvasEl).paddingLeft || '0') * 2 || 0;
  const padY = parseFloat(getComputedStyle(canvasEl).paddingTop || '0') * 2 || 0;
  const boxW = Math.max(1, r.width * f.w - padX);
  const boxH = Math.max(1, r.height * f.h - padY);
  let w = boxW;
  let h = boxH;
  if (size && size.w > 0 && size.h > 0) {
    const ar = size.w / size.h;
    if (boxW / boxH > ar) h = boxH, w = h * ar;   // 盒比图宽 ⇒ 高度受限
    else w = boxW, h = w / ar;                     // 盒比图高 ⇒ 宽度受限
  }
  // 不取整：像素取整会把纸面中心推离屏幕中心（±0.5px 的肉眼「歪」，验收是 1px 级）。
  // 纸面是 border-box（css/base.css 全局）⇒ 写回去的是「内容盒 + padding」；
  // 只写内容尺寸会让 padding 吃掉高度（实测 36:1 的宽图内容高被压成 0）。
  canvasEl.style.width = (w + padX) + 'px';
  canvasEl.style.height = (h + padY) + 'px';
}

/** 把一段 SVG 克隆进舞台：id 整体改名（防与文档里那份撞 id），尺寸交给舞台盒。
 *  矢量克隆 = 零二次传输、零重新渲染，放大不糊（作者令「保留 SVG 清晰度」）。 */
function mountDiagram(/** @type {Element} */ svg) {
  const clone = /** @type {Element} */ (svg.cloneNode(true));
  const oldId = clone.getAttribute('id') || '';
  if (oldId) retargetIds(clone, oldId, oldId + '-lb');
  clone.removeAttribute('style');           // mermaid 的 max-width 是给行内槽的
  clone.removeAttribute('height');
  clone.setAttribute('width', '100%');
  clone.setAttribute('height', '100%');
  clone.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  clone.removeAttribute('data-probe-k');
  canvasEl.replaceChildren(clone);

  // 100% = 「整幅可见」这一档（拟合盒），缩放从这个基准往上走。
  fitDiagramCanvas();
}

/** 克隆树里的 id 全部改名，并同步改 `<style>` 文本与 `url(#…)` / `href="#…"` 引用。 */
function retargetIds(/** @type {Element} */ root, /** @type {string} */ oldId, /** @type {string} */ newId) {
  const walk = (el) => {
    for (const at of Array.from(el.attributes)) {
      if (at.value.indexOf(oldId) === -1) continue;
      el.setAttribute(at.name, at.value.split(oldId).join(newId));
    }
    if (el.localName === 'style') {
      const txt = el.textContent || '';
      if (txt.indexOf(oldId) !== -1) el.textContent = txt.split(oldId).join(newId);
    }
    for (const c of Array.from(el.children)) walk(c);
  };
  walk(root);
}

function figApply() {
  if (!canvasEl || !figState) return;
  canvasEl.style.transform = `translate3d(${figState.tx}px, ${figState.ty}px, 0) scale(${figState.scale})`;
  if (pctEl) pctEl.textContent = Math.round(figState.scale * 100) + '%';
}

/** @param {number} factor @param {number} [cx] @param {number} [cy] 锚点（视口坐标） */
function figZoomAt(factor, cx, cy) {
  if (!figState || !stageEl) return;
  const next = Math.min(FIG_MAX, Math.max(FIG_MIN, figState.scale * factor));
  if (next === figState.scale) return;
  const r = stageEl.getBoundingClientRect();
  const ox = r.left + r.width / 2;
  const oy = r.top + r.height / 2;
  const ax = typeof cx === 'number' ? cx : ox;
  const ay = typeof cy === 'number' ? cy : oy;
  // 光标下的内容点保持不动（CAD 式锚点缩放）。
  const px = (ax - ox - figState.tx) / figState.scale;
  const py = (ay - oy - figState.ty) / figState.scale;
  figState.scale = next;
  figState.tx = ax - ox - px * next;
  figState.ty = ay - oy - py * next;
  figApply();
}

function figFit() {
  if (!figState) return;
  figState.scale = 1;
  figState.tx = 0;
  figState.ty = 0;
  figApply();
}

function bindFigureGestures() {
  // 滚轮：浮层里没有别的可滚内容 ⇒ 普通滚轮直接缩放（与 Canvas 窗格的
  // ctrl/cmd 语义刻意不同，理由见文件头注）。
  stageEl.addEventListener('wheel', (e) => {
    if (lmode !== 'svg') return;
    e.preventDefault();
    figZoomAt(Math.pow(1.0015, -e.deltaY), e.clientX, e.clientY);
  }, { passive: false });

  const pinch = () => {
    const list = [...pointers.values()];
    if (list.length < 2) return null;
    return {
      d: Math.hypot(list[0].x - list[1].x, list[0].y - list[1].y),
      cx: (list[0].x + list[1].x) / 2,
      cy: (list[0].y + list[1].y) / 2,
    };
  };
  let pinchBase = null;

  stageEl.addEventListener('pointerdown', (e) => {
    if (lmode !== 'svg') return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    try { stageEl.setPointerCapture(e.pointerId); } catch (err) { /* 已释放 */ }
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) { pinchBase = { ...pinch(), scale: figState ? figState.scale : 1 }; return; }
    if (pointers.size === 1) figDrag = { x: e.clientX, y: e.clientY, moved: 0 };
  });
  stageEl.addEventListener('pointermove', (e) => {
    if (lmode !== 'svg' || !pointers.has(e.pointerId) || !figState) return;
    const prev = pointers.get(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size >= 2 && pinchBase) {
      const now = pinch();
      if (!now || !now.d) return;
      const next = Math.min(FIG_MAX, Math.max(FIG_MIN, pinchBase.scale * (now.d / (pinchBase.d || now.d))));
      if (next === figState.scale) return;
      const r = stageEl.getBoundingClientRect();
      const ox = r.left + r.width / 2;
      const oy = r.top + r.height / 2;
      const px = (now.cx - ox - figState.tx) / figState.scale;
      const py = (now.cy - oy - figState.ty) / figState.scale;
      figState.scale = next;
      figState.tx = now.cx - ox - px * next;
      figState.ty = now.cy - oy - py * next;
      figApply();
      return;
    }
    const dx = e.clientX - prev.x;
    const dy = e.clientY - prev.y;
    figState.tx += dx;
    figState.ty += dy;
    if (figDrag) figDrag.moved += Math.abs(dx) + Math.abs(dy);
    stageDragMoved += Math.abs(dx) + Math.abs(dy);
    figApply();
  });
  const endPointer = (e) => {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinchBase = null;
    if (pointers.size === 0) figDrag = null;
  };
  stageEl.addEventListener('pointerup', endPointer);
  stageEl.addEventListener('pointercancel', endPointer);
  stageEl.addEventListener('lostpointercapture', endPointer);

  // 空白处关闭：舞台铺满全屏，所以「点空白」落在舞台自己身上（不是 overlay）。
  // 拖完松手那一下不算 —— 平移结束时手指抬起同样会派发 click。
  stageEl.addEventListener('click', (e) => {
    if (lmode !== 'svg') return;
    if (stageDragMoved > DRAG_SLOP) return;
    if (e.target !== stageEl) return;
    closeLightbox();
  });
}

/** 图形模式下的键盘面：+/−/0 缩放复位，方向键平移（与 ESC 关闭并存）。 */
function onFigureKey(e) {
  if (lmode !== 'svg' || !overlayEl || !overlayEl.classList.contains('on')) return;
  if (!figState) return;
  const step = 40;
  if (e.key === '+' || e.key === '=') { e.preventDefault(); figZoomAt(1.25); return; }
  if (e.key === '-' || e.key === '_') { e.preventDefault(); figZoomAt(1 / 1.25); return; }
  if (e.key === '0') { e.preventDefault(); figFit(); return; }
  if (e.key === 'ArrowLeft') { e.preventDefault(); figState.tx += step; figApply(); return; }
  if (e.key === 'ArrowRight') { e.preventDefault(); figState.tx -= step; figApply(); return; }
  if (e.key === 'ArrowUp') { e.preventDefault(); figState.ty += step; figApply(); return; }
  if (e.key === 'ArrowDown') { e.preventDefault(); figState.ty -= step; figApply(); }
}

/** 切内容模式（图片 / 图形）。两模式共用同一个浮层节点 —— 不造第二个查看器。 */
function setMode(/** @type {'img'|'svg'} */ next) {
  lmode = next;
  if (!overlayEl) return;
  overlayEl.setAttribute('data-nf-lb-mode', next);
  if (imgEl) imgEl.style.display = next === 'img' ? '' : 'none';
  if (stageEl) stageEl.style.display = next === 'svg' ? '' : 'none';
  if (barEl) barEl.style.display = next === 'svg' ? '' : 'none';
  if (next === 'img' && canvasEl) canvasEl.replaceChildren();
}

/**
 * 打开框图大图查看（气泡 / Canvas 文档同一入口）。
 * @param {Element} svg 已经消毒并落在 DOM 里的那枚 SVG（克隆进浮层，零二次传输）
 */
export function openDiagramLightbox(svg) {
  if (!svg) return;
  ensureFigureDom();
  const wasOn = overlayEl.classList.contains('on');
  if (!wasOn) lastFocus = document.activeElement;
  openCount += 1;
  overlayEl.setAttribute('data-nf-open-seq', String(openCount));
  overlayEl.setAttribute('aria-label', t('lightbox.diagramLabel'));
  setMode('svg');
  overlayEl.classList.add('on');
  if (statusEl) statusEl.style.display = 'none';
  imgEl.classList.remove('loaded');
  imgEl.removeAttribute('src');
  releaseObjectUrl();
  openSeq += 1;               // 作废任何在飞的图片取字节
  // 先对位（浮层 = 可视屏幕），再算图纸盒 —— 顺序反了就会按旧舞台尺寸摆图。
  syncViewportPin();
  mountDiagram(svg);
  figState = { scale: 1, tx: 0, ty: 0, min: FIG_MIN, max: FIG_MAX };
  figApply();
  if (!wasOn) {
    pushLayer(overlayEl, { name: 'lightbox' });
    document.body.classList.add('nf-lightbox-open');
  }
  overlayEl.querySelector('.nf-lightbox-close').focus();
}

/**
 * @param {string} src
 * @param {string} [alt]
 * @param {string} [path] proxied local path (derived from `src` when omitted)
 */
export function openLightbox(src, alt, path) {
  if (!src || typeof src !== 'string') return;
  ensureDom();
  setMode('img');
  syncViewportPin();          // 浮层先对位到可视屏幕（图片模式同一处入口）
  openCount += 1;
  overlayEl.setAttribute('data-nf-open-seq', String(openCount));
  overlayEl.setAttribute('aria-label', t('lightbox.ariaLabel'));
  lastFocus = document.activeElement;

  // Reset to loading state
  imgEl.classList.remove('loaded');
  imgEl.removeAttribute('src');
  delete imgEl.dataset.nfLbRetried; // 账本自愈旗标按次开清零（每开恰一次自愈机会）
  statusEl.className = 'nf-lightbox-status loading';
  statusEl.textContent = t('lightbox.loading');
  statusEl.style.display = '';
  releaseObjectUrl();

  const seq = ++openSeq;
  const fail = () => {
    if (seq !== openSeq) return;
    statusEl.className = 'nf-lightbox-status error';
    statusEl.textContent = t('lightbox.loadError');
  };
  /** 既有直取腿（C2-20 语义逐字：本腿永远新铸票据、字节自取）——现在只由账本腿
   *  失败/自愈时出场（二次加载清剿批），修前它是本地路径的**唯一**腿：每次开图
   *  都 reMint（新票据 = 新 URL）⇒ 已在气泡/文档里显示过的同件再全量下载一次。 */
  const legacyFetch = () => {
    reMint(localPath)
      .then((url) => fetch(url, { credentials: 'same-origin' }))
      .then((resp) => {
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return resp.blob();
      })
      .then((blob) => {
        if (seq !== openSeq) return;
        objectUrl = URL.createObjectURL(blob);
        imgEl.src = objectUrl;
      })
      .catch(() => { fail(); });
  };
  imgEl.onload = () => {
    imgEl.classList.add('loaded');
    statusEl.style.display = 'none';
  };
  imgEl.onerror = () => {
    if (seq !== openSeq) return;
    // 账本 blob 解码失败（坏字节，#816 同族语义）：先逐出账本，再走既有 reMint
    // 直取腿恰一次——坏字节不粘死账本。直取腿再败 ⇒ 既有 loadError 失败面。
    if (imgEl.src && imgEl.src.startsWith('blob:') && !imgEl.dataset.nfLbRetried) {
      imgEl.dataset.nfLbRetried = '1';
      void nfCacheDrop('nf:' + localPath).catch(() => {});
      legacyFetch();
      return;
    }
    fail();
  };
  imgEl.alt = alt || '';

  const localPath = path || nfPathOf(src);
  if (localPath) {
    // 2026-09-11 (C batch, C2-20 / self-correction ⑧): the card's iframe used
    // to hand the parent a fully-formed URL — credential included — which was
    // then written straight into this img element: a live ticket parked in the
    // parent document (serializable, screenshot-able, visible in devtools) and
    // reused later, so a lightbox opened after the TTL just showed `loadError`.
    // Now the parent mints a FRESH ticket for this path, fetches the bytes
    // itself, and shows a `blob:` URL — `img.src` carries no credential at all,
    // while the one request that mattered carried a newly issued one.
    //
    // 二次加载清剿批（2026-10-24）：字节面改经 **账本单点**（上方 import 处注释）。
    // C2-20 的凭据纪律原样保留——账本命中根本不碰票据；账本腿内部取数仍走
    // 票据链新铸；本函数自己的 reMint 腿降级为失败/自愈回落。
    nfViewerSource({ absPath: localPath, objectUrl: null, pane: overlayEl })
      .then(({ url }) => {
        if (seq !== openSeq) { try { URL.revokeObjectURL(url); } catch (e) { /* already dead */ } return; }
        objectUrl = url;
        imgEl.src = url;
      })
      .catch(() => { if (seq === openSeq) legacyFetch(); });
  } else {
    imgEl.src = src;  // remote / data: URL — nothing to mint, nothing to strip
  }

  overlayEl.classList.add('on');
  // B4：看图大图 = 第 9 个壳（body 级 overlay）。入层栈 ⇒ Tab 不跑到背后的画布。
  pushLayer(overlayEl, { name: 'lightbox' });
  document.body.classList.add('nf-lightbox-open');
  overlayEl.querySelector('.nf-lightbox-close').focus();
}

export function closeLightbox() {
  if (!overlayEl || !overlayEl.classList.contains('on')) return;
  popLayer(overlayEl); // B4：出栈 + 回焦
  overlayEl.classList.remove('on');
  document.body.classList.remove('nf-lightbox-open');
  openSeq += 1;                   // invalidate any in-flight ticket fetch
  imgEl.onload = null;
  imgEl.onerror = null;
  imgEl.removeAttribute('src');   // stop any in-flight load
  releaseObjectUrl();             // and drop the blob behind it
  // 图形模式收尾：克隆撤掉、变换归零、指针登记清空（下次打开是全新一档）。
  pointers.clear();
  figDrag = null;
  if (canvasEl) { canvasEl.replaceChildren(); canvasEl.style.transform = ''; }
  if (figState) { figState.scale = 1; figState.tx = 0; figState.ty = 0; }
  figState = null;
  if (pctEl) pctEl.textContent = '100%';
  if (lastFocus && lastFocus.focus) lastFocus.focus();
  lastFocus = null;
}

export function initLightbox() {
  // 幂等（把「重复绑定」变成结构上不可能）：本函数是全局委托的安装点，重复调用
  // （模块重入、宿主重初始化）不得叠第二份监听——叠了就会一次点击开两次。
  if (initDone) return;
  initDone = true;
  // 1. Delegated click on tagged markdown images (chat + canvas md viewer)
  document.addEventListener('click', (e) => {
    const img = /** @type {HTMLImageElement|null} */ ((/** @type {Element} */ (e.target)).closest('img.nf-zoom-img'));
    if (!img) return;
    e.preventDefault();
    openLightbox(img.currentSrc || img.src, img.alt);
  });

  // 1b. 框图（mermaid / 内联 SVG 围栏）→ 看大图。**一处接线覆盖气泡与 Canvas
  //     文档两面**：两面走同一条围栏渲染（js/fenceKit.js），DOM 形态一致，所以
  //     这里不需要按面分支。三条防误触闸（作者令「避免链接节点、图内交互与拖动
  //     误触打开」）：
  //       ① 命中链接/表单类节点 ⇒ 交给它们自己（图内链接、按钮不打开浮层）；
  //       ② 起手到落点移动超过 DRAG_SLOP ⇒ 是拖动/平移，不是点击；
  //       ③ 有文本选区 ⇒ 用户在选字，不是点击。
  document.addEventListener('pointerdown', (e) => {
    // 选区状态必须在 mousedown 的默认动作（清选区）之前读 —— 落到 click 上再读，
    // 用户在图上选完字的那一次点击永远读到「空选区」，闸就形同虚设（实测踩过）。
    const sel = window.getSelection && window.getSelection();
    const hadSelection = !!sel && !sel.isCollapsed && String(sel).length > 0;
    pendingFigClick = { x: e.clientX, y: e.clientY, moved: 0, hadSelection };
    stageDragMoved = 0;
  }, true);
  document.addEventListener('pointermove', (e) => {
    if (!pendingFigClick) return;
    pendingFigClick.moved = Math.hypot(e.clientX - pendingFigClick.x, e.clientY - pendingFigClick.y);
  }, true);
  document.addEventListener('pointerup', (e) => {
    if (!pendingFigClick) return;
    pendingFigClick.moved = Math.hypot(e.clientX - pendingFigClick.x, e.clientY - pendingFigClick.y);
  }, true);
  document.addEventListener('click', (e) => {
    const target = /** @type {Element} */ (e.target);
    // 角标钮：真按钮，键盘 Enter/Space 与点击都走这里（无障碍唯一入口）。
    const btn = target.closest('.nf-figure-open');
    if (btn) {
      const block = btn.closest('.nf-mermaid, .nf-svg');
      const svg = block ? block.querySelector('.nf-mermaid-body svg, .nf-svg-body svg') : null;
      if (svg) { e.preventDefault(); openDiagramLightbox(svg); }
      return;
    }
    const svg = target.closest('.nf-mermaid-body svg, .nf-svg-body svg');
    if (!svg) return;
    // ① 图内链接/交互节点自己处理。
    if (target.closest('a, button, input, textarea, select')) return;
    // ② 拖动/滑动起手 ⇒ 不打开。
    const move = pendingFigClick ? pendingFigClick.moved : 0;
    if (move > DRAG_SLOP) return;
    // ③ 起手时正在选字 ⇒ 用户在选，不是点击。
    if (pendingFigClick && pendingFigClick.hadSelection) return;
    e.preventDefault();
    openDiagramLightbox(svg);
  });

  // 2. Image clicks forwarded from the frames this app renders: the Canvas HTML
  //    viewer and (card-image-zoom batch, 2026-09-28) the chat Card.
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (!d || !d._nfImagePreview || typeof d._nfImagePreview.src !== 'string') return;
    // srcdoc iframes report an opaque origin ('null'/'') even with
    // allow-same-origin, so a strict e.origin check would silently drop them.
    // Validate the SENDER instead: it must be a live frame this app renders —
    // Canvas HTML viewer or Card iframe. Returning early keeps a foreign window
    // (any window that guessed the message shape) from opening the viewer, for
    // same-origin senders and opaque ones alike.
    if (!isAppFrameSource(/** @type {Window|null} */ (e.source))) return;
    // Path gate: a path-bearing payload must name a reference that sender
    // frame actually renders — never an arbitrary path passed in from outside.
    if (!senderRendersPath(/** @type {Window} */ (e.source))(d._nfImagePreview)) return;
    openLightbox(d._nfImagePreview.src, d._nfImagePreview.alt, d._nfImagePreview.path);
  });

  // 3. ESC closes — capture phase so canvas/sidebar ESC handlers don't
  //    also fire while the lightbox is open.
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && overlayEl && overlayEl.classList.contains('on')) {
      e.preventDefault();
      e.stopPropagation();
      closeLightbox();
    }
  }, true);

  // 4. 图形模式的键盘缩放/平移（ESC 已在上方统一处理）。
  document.addEventListener('keydown', onFigureKey);
}
