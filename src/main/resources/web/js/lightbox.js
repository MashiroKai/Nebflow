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
import { routeMediaClick, mediaDirectUrl } from './mediaOpenRoute.js';
// mobile-download 批（2026-11-18 作者令）：画廊「下载」键落点单点——手机层图片/视频
// 走系统分享（可入相册）/ 其它「存储到文件」（可指定位置）；能力不支持 ⇒ 原下载 + 落点提示。
import { saveFileBlob, notifyLanding } from './downloadSave.js';
// 二次加载清剿批（2026-10-24 · #816 同法）：本地路径的字节改经 **nfFileCache 账本
// 单点**先行——`nf:<path>` 命中零抓取直显（气泡/文档渲染已入账的同件）；未命中 =
// 账本票据链取数 + 入账（恰一次）。失败回落既有 reMint 直取腿（下方逐字保留）。
import { nfViewerSource, nfCacheDrop } from './nfFileCache.js';
// 媒体查看器缩放批（2026-11-18 作者令逐字「媒体素材 图片 视频这些 手机端要支持手势
// 放大缩小 电脑端也要支持鼠标滚轮以及触控板手势」）：缩放/平移的**纯算术与判定表**
// 在 js/mediaZoom.js（零 DOM，可被 node --test 直接断言）；本文件只做事件分流与
// DOM 读写。坐标系与边界口径见该模块头注（视口中心为原点）。
import {
  ZOOM_MIN, PINCH_MIN_DIST, EDGE_SWITCH_PX,
  clampZoom, axisBounds, rubber, overrun, anchorZoom, pinchView, fitDragOutcome,
  createWheelClassifier, wheelPanDelta, wheelZoomFactor, doubleTapTarget,
} from './mediaZoom.js';
// 弹窗退场批（作者令 2026-10-31②）：手机层灯箱补左上返回（灯箱本已全屏；桌面零变化）。
import { registerOverlayBack } from './gesture/backSwipe.js';

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
let galMediaEl = null;
/* ── 缩放/平移态（媒体查看器缩放批 2026-11-18）─────────────────────────
   (galZoom, galPanX, galPanY) 是唯一真值，坐标 = **视口中心相对**；galGesture 是
   「跟手层」（既有横滑/下滑的实时位移与淡出），绘画时与缩放态合成 ⇒ 变换只有一个
   写点。改前，拖动直接覆写 imgEl.style.transform，与缩放态互相打架：放大后横滑会
   把缩放整块抹掉，且桌面拖动只看得到「有没有放大」而看不到放大到哪 —— 这是本批
   修掉的根因，不是新加的花样。 */
let galZoom = 1, galPanX = 0, galPanY = 0;
/** @type {{dx: number, dy: number, ds: number, opacity: number}|null} 跟手层（null = 恒等）。 */
let galGesture = null;
let galMouseDrag = null;
/** 滑切换序号：慢腿（blob 铸 URL 等）不得盖掉更新的滑。 */
let galSlideSeq = 0;
/** 本组 item.blob 腿铸出的 objectURL（关闭/换组时统一回收）。 */
const galBlobUrls = [];
/** 跟手拖动态：{id,x0,y0,t0,axis,mode,dx,dy,tx0,ty0,over}；axis=0 未定轴。 */
let galDrag = null;
/** 遮罩「刚拖完」一次性闸：成轴拖动松手时置位，由随后的那一枚合成 click 消费
 *  （见 ensureDom 的遮罩点按处理器）。
 *  为什么是**一次性旗标而不是时间窗**（媒体查看器缩放批 2026-11-18 修）：改前用的是
 *  `Date.now() - galDragEndAt < 160`，而 galDragEndAt 是模块级时刻、**关灯箱/重开都
 *  不清零** ⇒ 上一轮拖动的收尾时刻会漏到下一轮：只要「拖完松手 → 关 → 重开 → 点背景」
 *  整串落在 160ms 内，重开后的第一次点背景就被当成上一轮拖动的余波吞掉（本批实测
 *  读数 delta=158ms：同一份源码快机跑红、慢机跑绿 = 间歇红的唯一变量）。闸的语义本来
 *  就是「拖动收尾续派的那一枚 click」，与墙上时钟无关 —— 改成一次性消费即确定。 */
let galSuppressBackdrop = false;
/** 触屏指针登记（pointerId → 视口坐标）：≥2 即进入捏合会话。 */
/** @type {Map<number, {x: number, y: number}>} */
const galPts = new Map();
/** 捏合会话基线（视口中心相对坐标）：baseS/baseTx/baseTy + baseMid/baseDist。 */
/** @type {{baseS: number, baseTx: number, baseTy: number,
 *          baseMid: {x: number, y: number}, baseDist: number}|null} */
let galPinch = null;
/** 捏合收束挂起旗：双指同时离屏时（后一枚 pointerup 紧跟），收束权挂到那一枚上。 */
let galPendingPinch = false;
/** 收尾 click 吞掉旗（放大态拖动松手会续派一次 click：不当作遮罩关闭，
 *  也不当作视频「点画面切换播放」）。 */
let galSwallowClick = false;
/** 滚轮判别器（有状态：触控板会话粘连；见 mediaZoom.js 的判定次序）。 */
const galClassifyWheel = createWheelClassifier();

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
  closeBtn.innerHTML = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>';
  closeBtn.addEventListener('click', closeLightbox);

  imgEl = document.createElement('img');
  imgEl.className = 'nf-lightbox-img';
  imgEl.draggable = false;
  imgEl.alt = '';

  statusEl = document.createElement('div');
  statusEl.className = 'nf-lightbox-status';

  overlayEl.append(closeBtn, imgEl, statusEl);
  // Backdrop click closes; clicks on the image itself do not.
  // 画廊态多一道「刚拖完」闸：跟手拖动松手会续派一次 click 到遮罩（指针捕获把它
  // 重定向到浮层），那一枚不当作关闭 —— 闸是**一次性旗标**（拖动收尾置位、这一枚
  // click 消费掉），不是时间窗；非画廊路径走不到这里（旗标恒为 false）。
  overlayEl.addEventListener('click', (e) => {
    if (galSuppressBackdrop) { galSuppressBackdrop = false; return; }
    if (e.target !== overlayEl) return;
    closeLightbox();
  });
  document.body.appendChild(overlayEl);
  registerOverlayBack('lightbox', closeLightbox, overlayEl);
  // 画廊跟手手势（只在画廊态起作用；非画廊的点击/滚轮语义零变化）。
  bindGalleryGestures();
  // 浮层落 DOM 后再对位：`syncViewportPin` 只改内联盒，无观测、无回环。
  armViewportPin();
}

/* ── 图形模式：DOM / 缩放 / 平移 / 指针手势 ──────────────────────────── */

/** Canvas Mermaid 使用图片画廊；聊天及其他图形保留原来的查看器。 */
function openCanvasMermaidOrDiagram(svg) {
  if (!svg.closest('.canvas-md-viewer .nf-mermaid')) {
    openDiagramLightbox(svg);
    return;
  }
  const clone = svg.cloneNode(true);
  const sourceNodes = [svg, ...svg.querySelectorAll('*')];
  const copyNodes = [clone, ...clone.querySelectorAll('*')];
  const properties = ['color', 'fill', 'fill-opacity', 'stroke', 'stroke-width', 'stroke-opacity',
    'stroke-dasharray', 'stroke-linecap', 'stroke-linejoin', 'opacity', 'font-family', 'font-size',
    'font-weight', 'font-style', 'letter-spacing', 'text-anchor', 'dominant-baseline', 'visibility'];
  sourceNodes.forEach((node, i) => {
    const style = getComputedStyle(node);
    for (const property of properties) copyNodes[i].style.setProperty(property, style.getPropertyValue(property));
  });
  const rect = svg.getBoundingClientRect();
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  clone.setAttribute('width', String(Math.max(1, rect.width)));
  clone.setAttribute('height', String(Math.max(1, rect.height)));
  clone.style.maxWidth = 'none';
  const blob = new Blob([new XMLSerializer().serializeToString(clone)], { type: 'image/svg+xml' });
  openImageGallery([{ kind: 'image', name: 'diagram.svg', blob }], 0);
}

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
  const mkBtn = (icon, label, action, fn) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'nf-lightbox-ctl';
    b.dataset.action = action;
    b.title = label;
    b.setAttribute('aria-label', label);
    const i = document.createElement('i');
    i.setAttribute('data-lucide', icon);
    b.appendChild(i);
    b.addEventListener('click', fn);
    return b;
  };
  barEl.appendChild(mkBtn('maximize-2', t('lightbox.zoomFit'), 'fit', figFit));
  barEl.appendChild(mkBtn('download', t('lightbox.downloadSvg'), 'download-svg', () => downloadDiagram('svg')));
  barEl.appendChild(mkBtn('image-down', t('lightbox.downloadPng'), 'download-png', () => downloadDiagram('png')));

  overlayEl.append(stageEl, barEl);
  createIconsIn(barEl);
  bindFigureGestures();
}

/** Export the mounted, sanitized diagram with its computed appearance frozen. */
async function downloadDiagram(format) {
  const svg = canvasEl && canvasEl.querySelector('svg');
  if (!svg) return;
  try {
    const clone = svg.cloneNode(true);
    const sourceNodes = [svg, ...svg.querySelectorAll('*')];
    const copyNodes = [clone, ...clone.querySelectorAll('*')];
    const properties = ['color', 'fill', 'fill-opacity', 'stroke', 'stroke-width', 'stroke-opacity',
      'stroke-dasharray', 'stroke-linecap', 'stroke-linejoin', 'opacity', 'font-family', 'font-size',
      'font-weight', 'font-style', 'letter-spacing', 'text-anchor', 'dominant-baseline', 'visibility'];
    sourceNodes.forEach((node, i) => {
      const style = getComputedStyle(node);
      for (const property of properties) copyNodes[i].style.setProperty(property, style.getPropertyValue(property));
    });
    const rect = svg.getBoundingClientRect();
    const scale = figState ? figState.scale : 1;
    const width = Math.max(1, rect.width / scale);
    const height = Math.max(1, rect.height / scale);
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    clone.setAttribute('width', String(width));
    clone.setAttribute('height', String(height));
    clone.style.background = getComputedStyle(canvasEl).backgroundColor;
    const xml = new XMLSerializer().serializeToString(clone);
    let blob = new Blob([xml], { type: 'image/svg+xml' });
    if (format === 'png') {
      const url = URL.createObjectURL(blob);
      try {
        const image = new Image();
        image.src = url;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(width * 3);
        canvas.height = Math.round(height * 3);
        const ctx = canvas.getContext('2d');
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
        blob = await new Promise((resolve, reject) => canvas.toBlob(b => b ? resolve(b) : reject(new Error('PNG export failed')), 'image/png'));
      } finally { URL.revokeObjectURL(url); }
    }
    notifyLanding(await saveFileBlob(blob, 'diagram.' + format));
  } catch (error) {
    console.error('Diagram export failed', error);
    window.__showToast?.(t('lightbox.downloadError'), 'error');
  }
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
  const r = stageEl.getBoundingClientRect();
  const f = fitFractions();
  const padX = parseFloat(getComputedStyle(canvasEl).paddingLeft || '0') * 2 || 0;
  const padY = parseFloat(getComputedStyle(canvasEl).paddingTop || '0') * 2 || 0;
  const boxW = Math.max(1, r.width * f.w - padX);
  const boxH = Math.max(1, r.height * f.h - padY);
  // Use the same maximum fit area as the image viewer for the paper itself.
  // The SVG's preserveAspectRatio=meet keeps its artwork contained inside it.
  let w = boxW;
  let h = boxH;
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
  // 缩放/平移 + 跟手层复位到初始档（换图/重开同一张不残留上一张的缩放与位移）；
  // 内联 transition/transform/opacity 一并清零，让 CSS 的 loaded 过渡重新接管。
  galGesture = null;
  galSnapToFit();
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
  const label = /^zh/i.test(document.documentElement.lang || navigator.language) ? '下载' : 'Download';
  galTabBtnEl.title = label;
  galTabBtnEl.setAttribute('aria-label', label);
  const svgNs = 'http://www.w3.org/2000/svg';
  const icon = document.createElementNS(svgNs, 'svg');
  for (const [name, value] of Object.entries({ width: '18', height: '18', viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })) icon.setAttribute(name, value);
  const path = document.createElementNS(svgNs, 'path');
  path.setAttribute('d', 'M12 3v12m-5-5 5 5 5-5M4 17v3h16v-3');
  icon.append(path);
  galTabBtnEl.append(icon);
  galTabBtnEl.addEventListener('click', async (e) => {
    e.stopPropagation();
    const item = galItems && galItems[galIndex];
    if (!item) return;
    try {
      const url = await mediaDirectUrl(item);
      if (!url) throw new Error('No media URL');
      const response = await fetch(url, { credentials: 'same-origin' });
      if (!response.ok) throw new Error('Download failed');
      // mobile-download 批（2026-11-18）：画廊「下载」键的落点收归单点——手机层
      // 图片/视频走系统分享（可「存储到照片」入相册）/ 其它分享「存储到文件」；能力
      // 不支持 ⇒ 原 `<a download>` + 落点提示（桌面层不经画廊，逐字零触碰）。
      const landed = await saveFileBlob(await response.blob(), item.name || 'download');
      if (landed.route === 'cancelled') return;
      notifyLanding(landed);
      statusEl.textContent = label + ' ✓';
      statusEl.className = 'nf-lightbox-status'; statusEl.style.display = '';
      setTimeout(() => { if (statusEl.textContent === label + ' ✓') statusEl.style.display = 'none'; }, 2000);
    } catch {
      statusEl.textContent = label + ' failed';
      statusEl.className = 'nf-lightbox-status error'; statusEl.style.display = '';
    }
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
  galPts.clear();                 // 指针登记/捏合会话/跟手层一并清零
  galPinch = null;
  galGesture = null;
  galSwallowClick = false;
  galSuppressBackdrop = false;    // 关灯箱即复位：闸绝不跨「开」存活
  galPendingPinch = false;
  if (galTransitionTimer) { clearTimeout(galTransitionTimer); galTransitionTimer = 0; }
  galZoom = ZOOM_MIN; galPanX = 0; galPanY = 0; galMouseDrag = null;
  if (galMediaEl) {
    galMediaEl.pause();
    galMediaEl.removeAttribute('src');
    galMediaEl.style.transition = '';
    galMediaEl.style.transform = '';
    galMediaEl.style.opacity = '';
    galMediaEl.remove();
    galMediaEl = null;
  }
  if (imgEl) {
    imgEl.style.display = '';
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
  // 换图 = 回到初始态（作者令「再次打开同一张时回到初始态」，横向切图同理）：
  // 缩放/平移档与捏合会话一并清零，再绘画一次落下 data-* 读数。
  galGesture = null;
  galPinch = null;
  galDrag = null;
  galZoom = ZOOM_MIN; galPanX = 0; galPanY = 0;
  galPaint();
  if (galMediaEl) { galMediaEl.pause(); galMediaEl.removeAttribute('src'); galMediaEl.remove(); galMediaEl = null; }
  imgEl.style.display = item.kind === 'image' ? '' : 'none';
  const seq = ++galSlideSeq;
  if (item.kind === 'video' || item.kind === 'audio') {
    ++openSeq;
    imgEl.removeAttribute('src'); releaseObjectUrl();
    statusEl.style.display = 'none';
    galMediaEl = document.createElement(item.kind);
    galMediaEl.className = 'nf-lightbox-av';
    galMediaEl.controls = true;
    if (item.kind === 'video') galMediaEl.setAttribute('playsinline', '');
    overlayEl.appendChild(galMediaEl);
    const media = galMediaEl;
    void mediaDirectUrl(item).then(url => {
      if (seq !== galSlideSeq) return;
      if (url) media.src = url;
      else { statusEl.textContent = t('lightbox.loadError'); statusEl.className = 'nf-lightbox-status error'; statusEl.style.display = ''; }
    });
    return;
  }
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
    (it) => it && ['image', 'video', 'audio'].includes(it.kind) && (it.src || it.path || it.blob),
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
  galGo(typeof index === 'number' ? index : 0);
  overlayEl.classList.add('on');
  if (!wasOn) {
    pushLayer(overlayEl, { name: 'lightbox' });
    document.body.classList.add('nf-lightbox-open');
  }
  overlayEl.querySelector('.nf-lightbox-close').focus();
  return true;
}

/** 当前变换目标（图片腿 = imgEl；视频腿 = galMediaEl）。 */
function galTarget() {
  return galMediaEl || imgEl;
}

/** 浮层中心 + 内容 layout 边长。layout 尺寸不受 transform 影响 ⇒ 直接读 offset*，
 *  免得 getBoundingClientRect 把当前缩放算进去（那就是「缩放改变边界、边界改变
 *  缩放」的自锁）。 */
function galFrame() {
  const el = galTarget();
  const r = overlayEl.getBoundingClientRect();
  const vw = r.width || 1, vh = r.height || 1;
  return {
    cx: r.left + vw / 2, cy: r.top + vh / 2, vw, vh,
    w: el ? el.offsetWidth || 0 : 0,
    h: el ? el.offsetHeight || 0 : 0,
  };
}

/** 变换唯一写点：缩放态 ∘ 跟手层。恒等态**清空内联样式** —— 让
 *  .nf-lightbox-img.loaded / .nf-gal-in-{left,right} 的 CSS 过渡档重新接管
 *  （横滑切图的进场档依赖 CSS 类，内联 transform 会把它整块盖住）。 */
function galPaint() {
  if (!overlayEl) return;
  overlayEl.dataset.nfLbZoom = String(galZoom);
  overlayEl.dataset.nfLbPanX = String(Math.round(galPanX));
  overlayEl.dataset.nfLbPanY = String(Math.round(galPanY));
  const el = galTarget();
  if (!el) return;
  const g = galGesture;
  const dx = galPanX + (g ? g.dx : 0);
  const dy = galPanY + (g ? g.dy : 0);
  const s = galZoom * (g ? g.ds : 1);
  el.style.transform = (dx === 0 && dy === 0 && s === 1)
    ? ''
    : `translate(${dx.toFixed(2)}px, ${dy.toFixed(2)}px) scale(${s.toFixed(4)})`;
  const opacity = g ? g.opacity : 1;
  el.style.opacity = opacity < 1 ? String(Math.max(0, opacity)) : '';
}

/** 按目标档算钳制盒（soft = 橡皮筋；pin = 触屏腿：装得下的轴钉回居中）。 */
function galClampView(s, tx, ty, pin, soft) {
  const f = galFrame();
  const x = axisBounds(f.w * s, f.vw, pin);
  const y = axisBounds(f.h * s, f.vh, pin);
  return {
    tx: soft ? rubber(tx, x.min, x.max) : Math.min(x.max, Math.max(x.min, tx)),
    ty: soft ? rubber(ty, y.min, y.max) : Math.min(y.max, Math.max(y.min, ty)),
    bounds: { x, y },
  };
}

/** 写下一档（硬钳制）并重绘；pin = 触屏腿。 */
function galSetView(s, tx, ty, pin) {
  galZoom = clampZoom(s);
  const c = galClampView(galZoom, tx, ty, pin, false);
  galPanX = c.tx;
  galPanY = c.ty;
  galPaint();
}

/** 连续缩放/平移期间关掉 transform 过渡，停手 180ms 后交还。
 *  为什么必须关：.nf-lightbox-img / .nf-lightbox-av 挂着 200ms 的 transform 过渡（进场
 *  淡入与回弹都靠它）。若滚轮逐格拉档也走这条过渡，每一次滚轮的 200ms 动画都会被下一次
 *  滚轮重新起头 ⇒ 缩放整体滞后于手（实测：一格滚轮 +120ms 才走到 1.198 而非 1.2）。
 *  所以：**跟手期间零过渡**（位移/缩放在事件那一帧就落地），**收束的一拍交还过渡**
 *  （回弹、双击还原仍然是平滑的 200ms）。 */
let galTransitionTimer = 0;
function galHoldTransition() {
  const el = galTarget();
  if (el) el.style.transition = 'none';
  if (galTransitionTimer) clearTimeout(galTransitionTimer);
  galTransitionTimer = setTimeout(() => {
    galTransitionTimer = 0;
    const t = galTarget();
    if (t) t.style.transition = '';
  }, 180);
}

/** 复位到适应档并居中（回弹 / 双击还原 / 换图的共同收束档）。内联 transition 交还
 *  CSS ⇒ 200ms 过渡由既有档位承担（reduced-motion 由 CSS 直切，不另写动画引擎）。 */
function galSnapToFit() {
  if (galTransitionTimer) { clearTimeout(galTransitionTimer); galTransitionTimer = 0; }
  const el = galTarget();
  if (el) el.style.transition = '';
  galZoom = ZOOM_MIN; galPanX = 0; galPanY = 0; galGesture = null;
  galPaint();
}

/** 视频/音频**原生控件带**（元素盒下沿起量）：落在这里的指针与滚轮一律交给元素
 *  自己 —— 作者令「缩放手势不得吞掉播放控件区域的操作」。取值 56px = 浏览器原生
 *  控件条的实际高度档（Chromium / Safari 48–56px）取上界，保证整条控件被让开；
 *  音频无画面 ⇒ 整件都算控件面。 */
const AV_CONTROL_BAND_PX = 56;

/** 指针/滚轮落点是否在媒体自身的原生控件带上。 */
function galOnAvControl(target, x, y) {
  const el = galMediaEl;
  if (!el || !target || !el.contains(/** @type {Node} */ (target))) return false;
  if (el.tagName === 'AUDIO') return true;
  const r = el.getBoundingClientRect();
  if (r.width <= 0 || r.height <= 0) return false;
  return x >= r.left && x <= r.right && y >= r.bottom - AV_CONTROL_BAND_PX;
}

/** 画廊手势与缩放（ensureDom 装一次；只在画廊态起作用——非画廊的浮层点击/滚轮
 *  语义零变化）。作者令 2026-11-18 逐字：「媒体素材 图片 视频这些 手机端要支持
 *  手势放大缩小 电脑端也要支持鼠标滚轮以及触控板手势」。
 *
 *  ── 既有手势与新缩放手势如何共存（判定表；两态行为可不同）──────────────
 *  适应态（s=1） 单指横滑 = 切上/下一张（组端橡皮筋 ×0.3、不越组）、单指下滑
 *                跟手 + 淡出 = 关闭、其余回弹 —— 判据与改前逐字同值
 *                （阈值搬进 mediaZoom.fitDragOutcome，改前是内联算术）；
 *  放大态（s>1） 单指拖动 = 平移（界外橡皮筋、松手回弹到硬边界）；**已到该轴
 *                边界继续拖过 EDGE_SWITCH_PX(72px) = 切图**（微信同款「边界续滑」，
 *                放大态也保住左右切换）；下滑关闭在放大态**不启用**（放大态的下向
 *                拖动是平移，不是关闭；关闭仍走遮罩点按 / 返回钮 / 返回手势 / Esc）；
 *  双指（任何态） 捏合 = 以两指中点为锚缩放 + 中点位移合成为平移；下越最小档 =
 *                橡皮筋阻尼，松手回弹到适应屏幕（不是关闭，理由见报告）；
 *  视频面        单指手势在适应态不参与切图/关闭（那是画面自己的操作面），只在
 *                放大态平移；起始落在原生控件带的指针一律不被接管。
 *
 *  两条输入腿刻意分开（语义不同）：触屏腿 = pointer 多点管线；鼠标腿 = wheel /
 *  dblclick / 放大态按住拖动。 */
function bindGalleryGestures() {
  const active = () => overlayEl.getAttribute('data-nf-lb-gallery') === 'on'
    && lmode === 'img' && !!galItems;
  /** 组内当前项（可能为 null）。 */
  const current = () => (galItems ? galItems[galIndex] || null : null);
  /** 本项是否参与缩放（音频没有画面，零缩放语义 —— 它的手势与改前一样全让给元素）。 */
  const zoomable = () => {
    const it = current();
    return !!it && (it.kind === 'image' || it.kind === 'video');
  };
  const twoPts = () => [...galPts.values()].slice(0, 2);
  const distOf = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const midOf = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
  /** 光标锚点（视口中心相对）；不在媒体面上（合成滚轮 / 遮罩上）⇒ null = 居中缩放。 */
  const pivotOf = (e) => {
    const el = galTarget();
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return null;
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) return null;
    const f = galFrame();
    return { x: e.clientX - f.cx, y: e.clientY - f.cy };
  };

  /* ── 鼠标腿 ①：滚轮 / 触控板（作者令「鼠标滚轮以及触控板手势」）────────
     分类见 mediaZoom.createWheelClassifier：ctrl/meta（触控板捏合或 ctrl+滚轮）、
     行模式、孤立大步 = 缩放；带横向分量或小步连续 = 双指平移。缩放锚点 = **光标
     位置**（「滚到哪放大哪」，不是中心缩放）。 */
  overlayEl.addEventListener('wheel', (e) => {
    if (!active() || !zoomable()) return;
    if (galOnAvControl(e.target, e.clientX, e.clientY)) return;   // 控件带上：交给元素自己
    const kind = galClassifyWheel(e, performance.now());
    if (kind === 'ignore') return;
    e.preventDefault();
    galHoldTransition();
    if (kind === 'zoom') {
      const next = anchorZoom(
        { s: galZoom, tx: galPanX, ty: galPanY },
        galZoom * wheelZoomFactor(e.deltaY),
        pivotOf(e),
      );
      galSetView(next.s, next.tx, next.ty, false);
      return;
    }
    const d = wheelPanDelta(e);
    galSetView(galZoom, galPanX + d.dx, galPanY + d.dy, false);
  }, { passive: false });

  /* ── 鼠标腿 ②：双击 fit ↔ 2×（锚 = 双击点；视频不夺原生双击全屏）────── */
  overlayEl.addEventListener('dblclick', (e) => {
    if (!active()) return;
    const it = current();
    if (!it || it.kind !== 'image') return;
    const pivot = pivotOf(e);
    // 目标判定用**命中测试**（该点是否在媒体盒内），不只用 e.target：放大态按住
    // 拖动会 `setPointerCapture`，而指针捕获会把随后的 click/dblclick 重定向到捕获
    // 元素（实测：捕获中 e.target = .nf-lightbox 而 elementFromPoint = img）—— 只认
    // e.target === imgEl 的话，放大之后的双击还原永远收不到（本批实测逮到的真 bug）。
    // pivot 非空 = 点在媒体盒内且不受捕获影响；pivot 为空时仍保留既有的
    // 「合成 dblclick 直落在 img 上」腿（既有桌面 spec 就是这样派的）。
    if (!pivot && e.target !== galTarget()) return;
    const next = doubleTapTarget(galZoom);
    if (next <= ZOOM_MIN) { galSnapToFit(); return; }
    const v = anchorZoom({ s: galZoom, tx: galPanX, ty: galPanY }, next, pivot);
    galSetView(v.s, v.tx, v.ty, false);
  });
  /* ── 键盘：←/→ 切图（既有面零改动；缩放态下同样生效） ── */
  document.addEventListener('keydown', e => {
    if (!active() || !overlayEl.classList.contains('on') || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) return;
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    if (e.target instanceof Element && e.target.closest('video, audio, input, textarea')) return;
    e.preventDefault(); e.stopPropagation(); galSlide(e.key === 'ArrowRight' ? 1 : -1);
  }, true);

  /* ── 指针起手：鼠标腿（放大态按住拖动平移）与触屏腿（捏合 / 单指）分流 ── */
  overlayEl.addEventListener('pointerdown', (e) => {
    if (!active()) return;
    galSwallowClick = false;
    galSuppressBackdrop = false;   // 新手势起手：作废上一轮遗留的收尾闸
    if (e.pointerType === 'mouse' && !document.body.classList.contains('mobile-layer')) {
      // 桌面鼠标腿：只在放大态按住拖动 = 平移（既有行为逐字保留）。视频面同规，
      // 但让开原生控件带。
      if (e.button !== 0 || galZoom <= ZOOM_MIN || !zoomable()) return;
      if (galOnAvControl(e.target, e.clientX, e.clientY)) return;
      if (e.target !== galTarget()) return;
      galMouseDrag = { id: e.pointerId, x: e.clientX, y: e.clientY, tx: galPanX, ty: galPanY };
      galHoldTransition();
      try { overlayEl.setPointerCapture(e.pointerId); } catch (err) { /* released */ }
      return;
    }
    // 触屏腿。
    if (galOnAvControl(e.target, e.clientX, e.clientY)) return;   // 原生控件带：不接手
    // 控件起手不进拖动（关闭/在标签页打开/返回钮自己处理点击）。
    if (e.target.closest && e.target.closest('button, .nf-lightbox-bar')) return;
    if (galPts.has(e.pointerId)) return;
    galPendingPinch = false;                // 新手势：作废上一轮挂起的收束
    const p = { x: e.clientX, y: e.clientY };
    galPts.set(e.pointerId, p);
    try { overlayEl.setPointerCapture(e.pointerId); } catch (err) { /* 已释放 */ }
    galHoldTransition();                     // 跟手期间零过渡（见 galHoldTransition）
    if (galPts.size === 2) {
      if (!zoomable()) { galPts.delete(e.pointerId); return; }
      const [a, b] = twoPts();
      const f = galFrame();
      const mid = midOf(a, b);
      galPinch = {
        baseS: galZoom, baseTx: galPanX, baseTy: galPanY,
        baseMid: { x: mid.x - f.cx, y: mid.y - f.cy },
        baseDist: Math.max(PINCH_MIN_DIST, distOf(a, b)),
      };
      galDrag = null;               // 第二指落下：单指会话让位给捏合
      return;
    }
    if (galPts.size > 1) return;    // 三指以上不定义语义（不吞、不乱动）
    // 单指会话：视频面在适应态不参与切图/关闭（那是画面自己的操作面），
    // 故只在放大态（= 平移）为它开会话。
    const mode = galZoom > ZOOM_MIN * 1.001 ? 'pan' : 'fit';
    galGesture = null;
    if (mode === 'fit') {
      const it = current();
      if (it && it.kind !== 'image') return;
    }
    galDrag = {
      id: e.pointerId, x0: p.x, y0: p.y, t0: performance.now(),
      axis: 0, mode, dx: 0, dy: 0, tx0: galPanX, ty0: galPanY, over: 0,
    };
  });
  overlayEl.addEventListener('pointermove', (e) => {
    if (!active()) return;
    const cur = galPts.get(e.pointerId);
    if (cur) { cur.x = e.clientX; cur.y = e.clientY; }

    if (galMouseDrag && e.pointerId === galMouseDrag.id) {
      galHoldTransition();
      galSetView(galZoom,
        galMouseDrag.tx + (e.clientX - galMouseDrag.x),
        galMouseDrag.ty + (e.clientY - galMouseDrag.y), false);
      return;
    }

    if (cur && galPinch && galPts.size >= 2) {
      if (!zoomable()) return;
      galHoldTransition();
      const [a, b] = twoPts();
      const f = galFrame();
      const mid = midOf(a, b);
      const v = pinchView(galPinch, { baseDist: galPinch.baseDist },
        { x: mid.x - f.cx, y: mid.y - f.cy }, distOf(a, b));
      const c = galClampView(v.s, v.tx, v.ty, true, false);
      galZoom = v.s; galPanX = c.tx; galPanY = c.ty;
      galPaint();
      return;
    }

    if (!cur || !galDrag || e.pointerId !== galDrag.id) return;
    const d = galDrag;
    d.dx = e.clientX - d.x0;
    d.dy = e.clientY - d.y0;
    if (!d.axis) {
      if (Math.abs(d.dx) < DRAG_SLOP && Math.abs(d.dy) < DRAG_SLOP) return;
      d.axis = Math.abs(d.dx) > Math.abs(d.dy) ? 'x' : 'y';
    }
    if (d.mode === 'pan') {
      // 放大态：单指平移（触屏腿 pin=true：装得下的轴钉居中；界外橡皮筋）。
      galHoldTransition();
      const wantX = d.tx0 + d.dx, wantY = d.ty0 + d.dy;
      const c = galClampView(galZoom, wantX, wantY, true, true);
      galPanX = c.tx; galPanY = c.ty;
      // 「已到该轴边界继续拖」的量（用**未阻尼**的期望值量：阻尼只是手感，
      // 不该抬高切图门槛）。
      d.over = 0;
      if (d.axis === 'x') {
        if (d.dx < 0 && wantX < c.bounds.x.min) d.over = c.bounds.x.min - wantX;
        else if (d.dx > 0 && wantX > c.bounds.x.max) d.over = wantX - c.bounds.x.max;
      }
      galPaint();
      return;
    }
    // 适应态：既有跟手档（横滑位移 / 下滑位移 + 淡出），与缩放态在绘画层合成。
    if (d.axis === 'x') {
      const atEdge = (galIndex === 0 && d.dx > 0) || (galIndex === galItems.length - 1 && d.dx < 0);
      galGesture = { dx: atEdge ? d.dx * 0.3 : d.dx, dy: 0, ds: 1, opacity: 1 };
    } else {
      const p = Math.min(1, Math.max(0, d.dy / 320));
      galGesture = { dx: 0, dy: d.dy * 0.9, ds: 1 - p * 0.15, opacity: 1 - p * 0.6 };
    }
    galPaint();
  });
  /** 捏合收束：下越最小档（橡皮筋把档位压到 ≤1）/ 取消 ⇒ 回弹到适应屏幕居中；
   *  否则把平移钳回硬边界。缓速回弹由 CSS 200ms 过渡承担。 */
  const finishPinch = () => {
    const el = galTarget();
    if (el) el.style.transition = '';
    if (galZoom <= ZOOM_MIN * 1.01) { galSnapToFit(); return; }
    galSetView(galZoom, galPanX, galPanY, true);
  };

  const end = (e, cancelled) => {
    if (galMouseDrag && e.pointerId === galMouseDrag.id) {
      galMouseDrag = null;
      galSuppressBackdrop = true;    // 平移松手续派的 click 不当作点遮罩
      const el = galTarget();
      if (el) el.style.transition = '';
      return;
    }
    const known = galPts.has(e.pointerId) || (galDrag && e.pointerId === galDrag.id) || !!galPinch;
    if (!known) return;
    galPts.delete(e.pointerId);
    try { overlayEl.releasePointerCapture(e.pointerId); } catch (err) { /* 已释放 */ }

    if (galPinch) {
      if (galPts.size >= 2) return;                 // 还在捏合中
      galPinch = null;
      const rest = [...galPts.entries()][0];
      if (rest && !cancelled) {
        // 双指 → 单指收指：残留触点接管平移（基点重置防跳变；viewers/image.js 同语义）。
        // 两指**同时**离屏时也会走这一腿（后一枚 pointerup 紧跟其后）：所以这里只把
        // 收束权挂起（galPendingPinch），由那一枚的收尾真正执行。
        galDrag = {
          id: rest[0], x0: rest[1].x, y0: rest[1].y, t0: performance.now(),
          axis: 0, mode: 'pan', dx: 0, dy: 0, tx0: galPanX, ty0: galPanY, over: 0,
        };
        galPendingPinch = true;
        return;
      }
      finishPinch();
      return;
    }

    if (!galDrag || e.pointerId !== galDrag.id) { galDrag = null; galPendingPinch = false; return; }
    const d = galDrag;
    galDrag = null;
    if (!d.axis) {
      // 纯点按：click 语义自己走；但若这一枚是捏合收束的挂起腿，收束仍要执行。
      if (galPendingPinch) { galPendingPinch = false; finishPinch(); }
      return;
    }
    galPendingPinch = false;
    // 成轴拖动（平移或横滑/下滑）松手会续派一次 click：置一次性闸给它消费，
    // 否则那一枚会被当成「点遮罩」而误关（旧实现是 160ms 时间窗，见旗标声明处）。
    galSuppressBackdrop = true;
    if (!galItems) return;                  // 拖动途中画廊已关：无收尾动作
    const el = galTarget();
    if (el) el.style.transition = '';
    if (d.mode === 'pan') {
      galSwallowClick = true;               // 放大态拖动收尾：吃掉这一次 click
      if (!cancelled && d.over > EDGE_SWITCH_PX) { galSlide(d.dx < 0 ? 1 : -1); return; }
      if (galZoom <= ZOOM_MIN * 1.01) { galSnapToFit(); return; }   // 捏合缩到最小档的收束
      galSetView(galZoom, galPanX, galPanY, true);
      return;
    }
    const outcome = fitDragOutcome({
      dx: d.dx, dy: d.dy, dt: performance.now() - d.t0,
      atFirst: galIndex === 0, atLast: galIndex === galItems.length - 1, cancelled,
    });
    galGesture = null;
    galPaint();                             // 复位内联变换（让位给进场档 / 回弹过渡）
    if (outcome.kind === 'switch') galSlide(outcome.dir);
    else if (outcome.kind === 'close') closeLightbox();
  };
  overlayEl.addEventListener('pointerup', (e) => end(e, false));
  overlayEl.addEventListener('pointercancel', (e) => end(e, true));

  // 放大态拖动收尾的合成 click：不切开视频的播放/暂停。（遮罩点按关闭由
  // ensureDom 的 galSuppressBackdrop 一次性闸挡，见那边的点击处理器。）
  overlayEl.addEventListener('click', (e) => {
    if (!galSwallowClick) return;
    galSwallowClick = false;
    if (!galMediaEl || e.target !== galMediaEl) return;
    e.stopPropagation();
    e.preventDefault();
  }, true);
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
      if (svg) { e.preventDefault(); openCanvasMermaidOrDiagram(svg); }
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
    openCanvasMermaidOrDiagram(svg);
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
