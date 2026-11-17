// lightbox.js — Click-to-zoom preview for chat bubbles and Canvas viewers.
//
// Trigger surfaces:
//   1. Markdown images tagged .nf-zoom-img by renderMarkdownWithMath
//      (chat bubbles + canvas markdown viewer) — document click delegation.
//      媒体点击统一批 P16（2026-11-11 作者令「电脑端的媒体统一都是canvas打开，
//      手机端的媒体统一都是直接打开」）起，本类入口经 mediaOpenRoute.routeMediaClick
//      分流：手机 = 系统直开；桌面带 path（nf-file URL / 原始本机路径）= Canvas
//      页签；灯箱降为桌面 pathless（data:/远程图）的兜底面。
//   2. Frames this app renders — the Canvas HTML viewer and the chat Card
//      (both inject viewers/shared.js `imgClickScript()`) — post
//      { _nfImagePreview: { src, path, alt } } to the parent window.
//      同一 P16 分流（手机直开 / 桌面 path→Canvas / pathless→灯箱兜底）。
//   3. mermaid / inline-SVG fence diagrams (mermaid-viewer-stability 批
//      2026-11-06，作者令逐字「你气泡里渲染的 要点击能变成大图查看 canvas里的
//      也要能支持」) — the ONE fence slot (js/fenceKit.js) marks itself
//      `.nf-fence-done` and carries a real `.nf-figure-open` button; clicking the
//      diagram body goes through the same delegation with a drag/selection guard.
//      Both faces (chat bubble + Canvas document) ride the SAME fence renderer,
//      so this is one entry point, not two components.
// Close: ESC / backdrop click / close button.
//   4. 手机层图片画廊（mobile-image-lightbox 批 2026-11-17 作者令逐字：「现在手机
//      端点图片为什么会单独打开一个标签页 我想的是像微信那样的弹窗就行了」）——
//      P16 的「手机媒体 = 系统直开」由本批收窄为「图片进画廊、视频/其他附件照旧
//      直开」：routeMediaClick 手机腿动态 import 本模块 openImageGallery（画廊 =
//      既有浮层的图片模式 + 滑动切图/页码/下滑关闭/「在标签页打开」次级入口，
//      见下方画廊块；动态 import 是既有防环先例——mediaOpenRoute 已被本模块静态
//      import，utils.js 同款）。
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
// 媒体点击统一批（P16 · 2026-11-11 作者令「电脑端的媒体统一都是canvas打开，手机端
// 的媒体统一都是直接打开」）：markdown 气泡图与帧消息图片两类入口的分流单点。
// 灯箱本体降为兜底面（桌面 pathless / 手机直开链尽空），不再是这两类入口的一级行为。
import { routeMediaClick, openMediaDirect } from './mediaOpenRoute.js';
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

/* ── 画廊模式（mobile-image-lightbox 批 2026-11-17）─────────────────────
   作者令逐字：「现在手机端点图片为什么会单独打开一个标签页 我想的是像微信
   那样的弹窗就行了」——手机层图片点击不再走 mediaOpenRoute 的系统直开腿，
   改开本组件的画廊面：
   · 遮罩 + 居中大图；进/退场 = 平滑淡入淡出（既有 .nf-lightbox-img 的 loaded
     过渡面；缩略图缩放起飞**不做**——申报制）；
   · 多图（图组）左右滑**跟手**切图 + 页码 `n / m`；下滑**跟手**关闭（图随指
     位移 + 淡出，松手过阈提交）；
   · 底层滚动锁：既有 body.nf-lightbox-open{overflow:hidden} 之外，手机页体
     的真实滚动容器 .mobile-page-body 补一条锁（见 lightbox.css——手机层页
     不滚在 body 上）；锁 = overflow:hidden，scrollTop 不动 ⇒ 关闭回原滚动位；
   · 返回键/右滑返 = 既有 mobileSheetBack 链路（openImageGallery 每开幂等补注，
     兼容跨档重排后首开 DOM 早于手机层的时序）；
   · 「在标签页打开」显式钮 = 原 openMediaDirect 腿原样保留（P16 原能力降级为
     次级入口，不静默丢）。
   桌面零变化：画廊只由 routeMediaClick 的手机腿进入；openLightbox /
   openDiagramLightbox 两条既有面逐字不动（画廊态在单图面/图形面进入时清零）。 */
/** @type {{kind: string, name?: string, src?: string, path?: string,
 *          resolver?: (p: string) => string, blob?: Blob}[]|null} 画廊组；null = 非画廊。 */
let galItems = null;
let galIndex = 0;
/** .nf-lightbox-pager 页码（组内 ≥2 才显示）。 */
let galPagerEl = null;
/** .nf-lightbox-opentab 「在标签页打开」次级入口钮。 */
let galTabBtnEl = null;
/** 滑切换序号：慢腿（blob 铸 URL 等）不得盖掉更新的滑。 */
let galSlideSeq = 0;
/** 本组 item.blob 腿铸出的 objectURL（关闭/换组时统一回收）。 */
const galBlobUrls = [];
/** 跟手拖动态：{id,x0,y0,t0,axis:'x'|'y'|0,dx,dy}；axis=0 未定轴。 */
let galDrag = null;
/** 最近一次成轴拖动的松手时刻（拖完松手那一下不当作点遮罩关闭）。 */
let galDragEndAt = 0;

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
  // 画廊态多一道「刚拖完」闸：跟手拖动松手会续派一次 click 到遮罩，不当作关闭
  // （非画廊路径的判据逐字不变——Date 差恒大于闸窗）。
  overlayEl.addEventListener('click', (e) => {
    if (e.target !== overlayEl) return;
    if (galItems && Date.now() - galDragEndAt < 160) return;
    closeLightbox();
  });
  mobileSheetBack(overlayEl, closeLightbox);
  document.body.appendChild(overlayEl);
  // 画廊跟手手势（只在画廊态起作用；非画廊的点击/滚轮语义零变化）。
  bindGalleryGestures();
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
  clearGalleryState();        // 单图面进入 = 画廊态清零（面语义互斥；非画廊时零动作）
  ensureDom();
  setMode('img');
  syncViewportPin();          // 浮层先对位到可视屏幕（图片模式同一处入口）
  openCount += 1;
  overlayEl.setAttribute('data-nf-open-seq', String(openCount));
  overlayEl.setAttribute('aria-label', t('lightbox.ariaLabel'));
  lastFocus = document.activeElement;
  presentImage(src, alt, path);

  overlayEl.classList.add('on');
  // B4：看图大图 = 第 9 个壳（body 级 overlay）。入层栈 ⇒ Tab 不跑到背后的画布。
  pushLayer(overlayEl, { name: 'lightbox' });
  document.body.classList.add('nf-lightbox-open');
  overlayEl.querySelector('.nf-lightbox-close').focus();
}

/**
 * 图片装载面（单图 openLightbox 与画廊滑 galGo 共用同一装载腿，字节链/失败面/
 * 自愈腿逐字同源）：置 loading 态 → 本地路径走账本单点票据纪律 / pathless 直挂
 * src。画廊滑进来时浮层已 on，本函数只换内容不碰层栈。
 * @param {string} src @param {string} [alt] @param {string} [path]
 */
function presentImage(src, alt, path) {
  if (!src && !path) return;
  // Reset to loading state
  imgEl.style.transition = '';   // 画廊跟手/滑动的内联样式清零（跟指位移不带到新图）
  imgEl.style.transform = '';
  imgEl.style.opacity = '';
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
}

export function closeLightbox() {
  if (!overlayEl || !overlayEl.classList.contains('on')) return;
  popLayer(overlayEl); // B4：出栈 + 回焦
  overlayEl.classList.remove('on');
  document.body.classList.remove('nf-lightbox-open');
  clearGalleryState();            // 画廊态/页码/开标签页钮/组内 objectURL 一并收尾
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

/* ── 画廊模式：开/滑/页码/次级入口/手势 ──────────────────────────────── */

/** 画廊 chrome（页码 + 「在标签页打开」钮）：惰性建一次、只在画廊态显示——桌面
 *  DOM 永不出现这两枚节点（画廊只在手机层进入）。 */
function ensureGalleryChrome() {
  if (galPagerEl && galPagerEl.isConnected) return;
  galPagerEl = document.createElement('div');
  galPagerEl.className = 'nf-lightbox-pager';
  galPagerEl.setAttribute('aria-live', 'polite');

  galTabBtnEl = document.createElement('button');
  galTabBtnEl.type = 'button';
  galTabBtnEl.className = 'nf-lightbox-opentab';
  const label = t('lightbox.openTab');
  galTabBtnEl.title = label;
  galTabBtnEl.setAttribute('aria-label', label);
  const svgNs = 'http://www.w3.org/2000/svg';
  const icon = document.createElementNS(svgNs, 'svg');
  for (const [name, value] of Object.entries({ width: '17', height: '17', viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })) icon.setAttribute(name, value);
  for (const [tag, attrs] of [
    ['path', { d: 'M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6' }],
    ['polyline', { points: '15 3 21 3 21 9' }],
    ['line', { x1: '10', y1: '14', x2: '21', y2: '3' }],
  ]) {
    const child = document.createElementNS(svgNs, tag);
    for (const [name, value] of Object.entries(attrs)) child.setAttribute(name, value);
    icon.append(child);
  }
  galTabBtnEl.append(icon);
  galTabBtnEl.addEventListener('click', (e) => {
    e.stopPropagation();          // 不冒泡成遮罩点空白关闭
    const item = galItems && galItems[galIndex];
    if (!item) return;
    // 原能力显式入口（P16 直开不静默丢）：openMediaDirect 腿原样（预约式开页）。
    void openMediaDirect(item);
  });

  overlayEl.append(galPagerEl, galTabBtnEl);
}

/** 页码读数（`n / m`；组内 ≥2 才显示）。index/total 同步落 data-*，供验收断言。 */
function updateGalChrome() {
  if (!galPagerEl || !galItems) return;
  const n = galItems.length;
  galPagerEl.style.display = n > 1 ? '' : 'none';
  galPagerEl.textContent = (galIndex + 1) + ' / ' + n;
  overlayEl.setAttribute('data-nf-lb-gal-index', String(galIndex));
  overlayEl.setAttribute('data-nf-lb-gal-total', String(n));
}

/** 画廊态清零：页码/钮隐藏、组内 objectURL 回收、跟手内联样式复位。openLightbox
 *  （单图面进入）与 closeLightbox 都走这里；非画廊时除属性清零外零动作。 */
function clearGalleryState() {
  galItems = null;
  galIndex = 0;
  galSlideSeq += 1;               // 作废在飞的滑装载
  galDrag = null;
  if (imgEl) {
    imgEl.style.transition = '';
    imgEl.style.transform = '';
    imgEl.style.opacity = '';
  }
  if (!overlayEl) return;
  overlayEl.removeAttribute('data-nf-lb-gallery');
  overlayEl.removeAttribute('data-nf-lb-gal-index');
  overlayEl.removeAttribute('data-nf-lb-gal-total');
  if (galPagerEl) galPagerEl.style.display = 'none';
  if (galTabBtnEl) galTabBtnEl.style.display = 'none';
  for (const u of galBlobUrls.splice(0)) {
    try { URL.revokeObjectURL(u); } catch (e) { /* already dead */ }
  }
}

/** 画廊装载第 i 张：item 取数序与 mediaOpenRoute.mediaDirectUrl 同源（src >
 *  blob > resolver > path），但本地路径走 presentImage 的账本票据腿（C2-20
 *  凭据纪律不降档）；resolver/src 的可显示 URL 直挂（与缩略图同一份 URL）。 */
function galGo(i) {
  if (!galItems || !galItems.length) return;
  galIndex = Math.min(Math.max(0, i), galItems.length - 1);
  updateGalChrome();
  const item = galItems[galIndex];
  const seq = ++galSlideSeq;
  const apply = (src, path) => {
    if (seq !== galSlideSeq) return;
    presentImage(src, item.name || '', path || '');
  };
  const fail = () => {
    if (seq !== galSlideSeq) return;
    statusEl.className = 'nf-lightbox-status error';
    statusEl.textContent = t('lightbox.loadError');
  };
  const s = typeof item.src === 'string' ? item.src : '';
  if (s) return apply(s, item.path || nfPathOf(s));
  if (item.blob && typeof item.blob.size === 'number' && item.blob.size > 0) {
    try {
      const u = URL.createObjectURL(item.blob);
      galBlobUrls.push(u);
      return apply(u, '');
    } catch (e) { /* 落下一腿 */ }
  }
  if (item.path) {
    if (typeof item.resolver === 'function') {
      try {
        const u = item.resolver(item.path);
        if (u) return apply(u, '');
      } catch (e) { /* resolver 拒答 ⇒ 落票据腿 */ }
    }
    return apply('', item.path);
  }
  fail();
}

/** 滑向切图（dir = +1 下一张 / -1 上一张）：新图从手势对侧带位移进场，与既有
 *  淡入过渡合成（进场档 44px / 200ms，与 .nf-lightbox-img 过渡同手感）。 */
function galSlide(dir) {
  if (!galItems) return;
  const next = galIndex + dir;
  if (next < 0 || next >= galItems.length) return;
  imgEl.classList.add(dir > 0 ? 'nf-gal-in-right' : 'nf-gal-in-left');
  galGo(next);
  requestAnimationFrame(() => { imgEl.classList.remove('nf-gal-in-right', 'nf-gal-in-left'); });
}

/**
 * 打开手机层图片画廊（微信式弹窗面；入口 = mediaOpenRoute.routeMediaClick 的
 * 手机图片腿——桌面不经此路，openLightbox/openDiagramLightbox 面逐字不变）。
 * 进场 = 平滑淡入淡出（既有 loaded 过渡；缩略图缩放起飞不做，申报制）。
 * @param {Array<{kind: string, name?: string, src?: string, path?: string,
 *          resolver?: (p: string) => string, blob?: Blob}>} items 图组（≥1）
 * @param {number} [index] 起始张序号
 * @returns {boolean} 是否真开（无可装载项 = false，调用方落直开兜底）
 */
export function openImageGallery(items, index) {
  const list = (Array.isArray(items) ? items : []).filter(
    (it) => it && it.kind !== 'video' && (it.src || it.path || it.blob),
  );
  if (!list.length) return false;
  ensureDom();
  setMode('img');
  syncViewportPin();
  const wasOn = overlayEl.classList.contains('on');
  if (!wasOn) lastFocus = document.activeElement;
  clearGalleryState();            // 上一组的 objectURL/页码/跟手内联样式先收
  galItems = list;
  openCount += 1;
  overlayEl.setAttribute('data-nf-open-seq', String(openCount));
  overlayEl.setAttribute('data-nf-lb-gallery', 'on');
  overlayEl.setAttribute('aria-label', t('lightbox.ariaLabel'));
  ensureGalleryChrome();
  galPagerEl.style.display = '';
  galTabBtnEl.style.display = '';
  // 返回键/右滑返 = 既有链路；每开幂等补注（singleton 首建早于手机层跨档的时序兜底）。
  mobileSheetBack(overlayEl, closeLightbox);
  galGo(typeof index === 'number' ? index : 0);
  overlayEl.classList.add('on');
  if (!wasOn) {
    pushLayer(overlayEl, { name: 'lightbox' });
    document.body.classList.add('nf-lightbox-open');
  }
  overlayEl.querySelector('.nf-lightbox-close').focus();
  return true;
}

/** 画廊跟手手势（ensureDom 装一次；只在画廊态起作用——非画廊的浮层点击/滚轮
 *  语义零变化）：横滑跟手位移（组端橡皮筋 ×0.3）松手过阈/快弹切图；下滑跟手
 *  （图随指位移 + 淡出）松手过阈/快弹关闭；其余回弹。纯点按不拦截（遮罩点按
 *  关闭 / 图上点按无动作 = 既有语义）。 */
function bindGalleryGestures() {
  const active = () => overlayEl.getAttribute('data-nf-lb-gallery') === 'on'
    && lmode === 'img' && !!galItems;
  overlayEl.addEventListener('pointerdown', (e) => {
    if (!active() || galDrag) return;
    // 控件起手不进拖动（关闭/在标签页打开/返回钮自己处理点击）。
    if (e.target.closest && e.target.closest('button, .nf-lightbox-bar')) return;
    try { overlayEl.setPointerCapture(e.pointerId); } catch (err) { /* 已释放 */ }
    galDrag = { id: e.pointerId, x0: e.clientX, y0: e.clientY, t0: Date.now(), axis: 0, dx: 0, dy: 0 };
  });
  overlayEl.addEventListener('pointermove', (e) => {
    if (!galDrag || e.pointerId !== galDrag.id || !active()) return;
    const d = galDrag;
    d.dx = e.clientX - d.x0;
    d.dy = e.clientY - d.y0;
    if (!d.axis) {
      if (Math.abs(d.dx) < DRAG_SLOP && Math.abs(d.dy) < DRAG_SLOP) return;
      d.axis = Math.abs(d.dx) > Math.abs(d.dy) ? 'x' : 'y';
    }
    imgEl.style.transition = 'none';   // 跟手帧不进过渡
    if (d.axis === 'x') {
      const atEdge = (galIndex === 0 && d.dx > 0) || (galIndex === galItems.length - 1 && d.dx < 0);
      imgEl.style.transform = `translateX(${(atEdge ? d.dx * 0.3 : d.dx).toFixed(1)}px)`;
    } else {
      const p = Math.min(1, Math.max(0, d.dy / 320));
      imgEl.style.transform = `translateY(${(d.dy * 0.9).toFixed(1)}px) scale(${(1 - p * 0.15).toFixed(3)})`;
      imgEl.style.opacity = String(1 - p * 0.6);
    }
  });
  const end = (e, cancelled) => {
    if (!galDrag || e.pointerId !== galDrag.id) return;
    const d = galDrag;
    galDrag = null;
    if (!d.axis) return;               // 纯点按：click 语义自己走
    galDragEndAt = Date.now();
    if (!galItems) return;             // 拖动途中画廊已关：无收尾动作
    const dt = Math.max(1, Date.now() - d.t0);
    if (d.axis === 'x') {
      const dir = d.dx < 0 ? 1 : -1;
      const commit = !cancelled
        && (Math.abs(d.dx) > 72 || (Math.abs(d.dx) > 24 && Math.abs(d.dx) / dt > 0.45))
        && galIndex + dir >= 0 && galIndex + dir < galItems.length;
      imgEl.style.transition = '';
      imgEl.style.transform = '';      // 回弹（不过阈）或让位给 galSlide 进场档
      if (commit) galSlide(dir);
    } else {
      const commit = !cancelled && d.dy > 0
        && (d.dy > 110 || (d.dy > 40 && d.dy / dt > 0.5));
      if (commit) { closeLightbox(); return; }   // 关闭路径的内联样式复位在 clearGalleryState
      imgEl.style.transition = '';
      imgEl.style.transform = '';
      imgEl.style.opacity = '';
    }
  };
  overlayEl.addEventListener('pointerup', (e) => end(e, false));
  overlayEl.addEventListener('pointercancel', (e) => end(e, true));
}

/** 桌面 Canvas 单点的懒包装（媒体点击统一批 P16）：lightbox.js 是轻叶子，禁静态
 *  回指 popArtifacts（其静态闭包含 msgQuote/smartCrop 等重组件）——点击时动态
 *  import，与 utils.js:1798 工具卡图片引用件同款先例。 */
function openCanvasLazy(path, name) {
  import('./popArtifacts.js')
    .then((m) => m.openPopArtifact(path, name))
    .catch(() => { /* 打开失败已由模块自身可见面兜底 */ });
}

/** 媒体项的 path 导出（媒体点击统一批 P16）：帧消息显式 path > nf-file URL 内嵌
 *  path > 原始本机路径（md 图片可携带 /abs/path 形态的 src——Canvas readFile
 *  直接可开，比灯箱直挂相对路径更可用）。data:/blob:/http(s) ⇒ ''（pathless）。 */
function mediaPathOf(src, explicitPath) {
  if (typeof explicitPath === 'string' && explicitPath) return explicitPath;
  const s = typeof src === 'string' ? src : '';
  if (!s) return '';
  const viaTicket = nfPathOf(s);
  if (viaTicket) return viaTicket;
  if (s.startsWith('/') && !s.startsWith('//')) return s;
  return '';
}

export function initLightbox() {
  // 幂等（把「重复绑定」变成结构上不可能）：本函数是全局委托的安装点，重复调用
  // （模块重入、宿主重初始化）不得叠第二份监听——叠了就会一次点击开两次。
  if (initDone) return;
  initDone = true;
  // 1. Delegated click on tagged markdown images (chat + canvas md viewer).
  //    媒体点击统一批 P16：分流单点——手机 = 系统直开；桌面带 path（nf-file /
  //    原始本机路径）= Canvas；桌面 pathless（data:/远程）= 灯箱（既 Face）。
  document.addEventListener('click', (e) => {
    const img = /** @type {HTMLImageElement|null} */ ((/** @type {Element} */ (e.target)).closest('img.nf-zoom-img'));
    if (!img) return;
    e.preventDefault();
    const src = img.currentSrc || img.src;
    // Canvas 文档腿的图在 hydration 后常为 blob: src（账本命中零抓取）——真实
    // 本机路径在 data-nf-md-path（markdown viewer 改写腿写入），优先取它。
    const dsPath = (img.dataset && img.dataset.nfMdPath) || '';
    const path = mediaPathOf(src, dsPath);
    // 画廊图组（mobile-image-lightbox 批）：同一消息气泡内的全部 .nf-zoom-img
    // 为一组（微信式多图消息，手机画廊可左右滑）；无气泡宿主（Canvas 文档等）
    // = 单图。group 只被手机腿消费——桌面行为逐字不变。
    const host = img.closest('.bubble');
    const imgs = host ? Array.from(host.querySelectorAll(/** @type {'img'} */ ('img.nf-zoom-img'))) : [img];
    const gi = Math.max(0, imgs.indexOf(img));
    const group = imgs.map((im) => {
      const s = im.currentSrc || im.src;
      const p = mediaPathOf(s, (im.dataset && im.dataset.nfMdPath) || '');
      return { kind: 'image', name: im.alt || p.split('/').pop(), src: s, path: p };
    });
    routeMediaClick(
      group[gi] || { kind: 'image', name: img.alt || path.split('/').pop(), src, path },
      openCanvasLazy,
      () => openLightbox(src, img.alt),
      group,
      gi
    );
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
  //    媒体点击统一批 P16：同一分流单点——手机 = 系统直开；桌面带 path = Canvas；
  //    桌面 pathless（data:/远程）= 灯箱。
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
    const payload = d._nfImagePreview;
    routeMediaClick(
      { kind: 'image', name: payload.alt || '', src: payload.src, path: payload.path },
      openCanvasLazy,
      () => openLightbox(payload.src, payload.alt, payload.path)
    );
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
