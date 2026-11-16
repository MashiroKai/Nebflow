// viewers/image.js — Image viewer (/api/nf-file) with pan & zoom.
// Zoom: transform scale on the <img> (layout-free), wheel/keys/toolbar via the
// shared viewers/zoom.js engine; pan via pointer drag.
//
// 手机端纯手势腿（作者令 2026-11-02 23:26「控制栏去掉，改纯手势」，规格表
// docs/20261103_000044_mobile-image-gesture-viewer__delegate-nebflow-12aef298
// §2.2，S1-S10）：body.mobile-layer 下
//   · 控制栏零 DOM（S1，zoom.js controls:'mobile-gesture' 承担）；
//   · 双指捏合缩放，锚 = 双指中点，钳制 [fit, 4×fit]（S3）；
//   · 单指平移（放大后有效），钳制图片边界，越界橡皮筋阻尼 + 松手回弹（S4）；
//   · 双击 fit ↔ 2.5×fit 切换，220ms 动画，锚 = 双击点；放大态双击回适屏比例
//     fit（S5/S6，作者「正正好的最终比例」）；
//   · 单击零动作（§2.6，微信式单击关闭不采纳——页面级导航已有 ✕/边缘返回）；
//   · reduced-motion 双击/回弹直切（S8/§2.8，HIG Accessibility Reduce Motion）；
//   · 桌面腿零触碰：hand 钮 / Space 平移 / 双击 fit↔100% / Ctrl 滚轮 全保留（§2.7）。

import { escapeHtml } from './shared.js';
import { createIconsIn } from '../utils.js';
import { reMint } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';
import { enableViewerZoom } from './zoom.js';
import { t } from '../i18n.js';

/** 手机层判据（body.mobile-layer；事件时点动态判 —— 层起落不必重开页签）。 */
const isMobileLayer = () => document.body.classList.contains('mobile-layer');
const prefersReducedMotion = () =>
  typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// ── 手机手势常数（规格表 §2.2/§2.4）──────────────────────────────────
const DBL_TAP_ZOOM = 2.5;   // 双击放大档 = fit × 2.5（远离 [1,4] 两端，双击后仍有捏合余量）
const PINCH_MAX_RATIO = 4;  // 捏合上限 = fit × 4（略过 1:1 像素，文字可读且不糊成马赛克）
const RUBBER_RESIST = 3;    // 越界位移按 1/3 衰减（iOS UIScrollView 橡皮筋量级）
const ANIM_MS = 220;        // 双击/回弹过渡时长（与 readerChrome/navController 过渡同档）
const TAP_SLOP_PX = 10;     // 双击判定：位移容差（iOS touchSlop 同量级）
const TAP_GAP_MS = 320;     // 双击判定：两次 tap 间隔上限

/** 手机手势子引擎：viewport 上的一套 pointer 管线（捏合/平移/双击/回弹）。
 *  只在手机层 image pane 挂；返回 dispose（随 _nfImageTeardown 回收）。
 *  变换模型与桌面腿同式：img translate(tx,ty) scale(s)，origin 0 0 ——
 *  内容点 p 在屏幕上的投影 = p·s + t，锚点公式与桌面 applyScale 同源。 */
function bindMobileGestures(viewport, api) {
  const { get, apply, fitState, clampScale, boundsFor, animateTo, stopAnim, onInteract, onGestureEnd } = api;

  /** @type {Map<number, {x:number, y:number}>} 活跃触点（pointerId → viewport 坐标） */
  const pts = new Map();
  /** 单指平移会话（跟手起点） */
  let drag = null;               // { x0, y0, tx0, ty0 }
  /** 双指捏合会话 */
  let pinch = null;              // { baseDist, baseS, mid: {x,y} }
  /** 双击判定 */
  let lastTap = null;            // { t, x, y }
  /** 触摸起手旗（桌面 dblclick 合成事件去重用） */
  let lastPointerType = 'mouse';
  let disposed = false;

  const vpPos = (e) => {
    const r = viewport.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };
  const twoPts = () => [...pts.values()].slice(0, 2);
  const distOf = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const midOf = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });

  /** 越界阻尼（橡皮筋）：界内原样，界外按 1/RUBBER_RESIST 衰减。 */
  const rubber = (v, lo, hi) => {
    if (lo > hi) { const mid = (lo + hi) / 2; return mid; } // 图比视口小：钉居中
    if (v < lo) return lo + (v - lo) / RUBBER_RESIST;
    if (v > hi) return hi + (v - hi) / RUBBER_RESIST;
    return v;
  };

  const onPointerDown = (e) => {
    if (disposed || !isMobileLayer()) return;
    lastPointerType = e.pointerType || 'mouse';
    if (lastPointerType !== 'touch') return; // 鼠标腿 = 桌面既有路径（hand/Space 门）
    stopAnim();
    const p = vpPos(e);
    pts.set(e.pointerId, p);
    try { viewport.setPointerCapture(e.pointerId); } catch { /* 已释放 */ }
    onInteract(); // 手动接管 —— geometryObserver 停跟随（桌面同语义）
    if (pts.size === 2) {
      const [a, b] = twoPts();
      pinch = { baseDist: Math.max(20, distOf(a, b)), baseS: get().s, mid: midOf(a, b) };
      drag = null;
    } else if (pts.size === 1) {
      const st = get();
      drag = { x0: p.x, y0: p.y, tx0: st.tx, ty0: st.ty, moved: false };
    }
  };

  const onPointerMove = (e) => {
    if (disposed || !pts.has(e.pointerId)) return;
    const p = vpPos(e);
    pts.set(e.pointerId, p);
    if (pinch && pts.size >= 2) {
      const [a, b] = twoPts();
      const d = Math.max(20, distOf(a, b));
      const mid = midOf(a, b);
      const st = get();
      // 目标比例（钳制 [fit, fit×PINCH_MAX_RATIO]，规格表 S3）
      const nextS = clampScale(pinch.baseS * (d / pinch.baseDist));
      // 锚 = 双指中点：内容点保持在指下 + 中点位移跟随（平移合成一步）
      const k = nextS / st.s;
      let tx = mid.x - (mid.x - st.tx) * k;
      let ty = mid.y - (mid.y - st.ty) * k;
      tx += mid.x - pinch.mid.x;
      ty += mid.y - pinch.mid.y;
      pinch.mid = mid;
      apply({ s: nextS, tx, ty });
      return;
    }
    if (drag && pts.size === 1) {
      const dx = p.x - drag.x0, dy = p.y - drag.y0;
      if (!drag.moved && Math.hypot(dx, dy) > TAP_SLOP_PX) {
        drag.moved = true;
        lastTap = null; // 拖动打断双击判定（pinch 同理：捏合不触发双击）
      }
      const st = get();
      const b = boundsFor(st.s);
      // 越界橡皮筋：界内 1:1 跟手，界外 1/3 阻尼（规格表 S4）
      apply({ s: st.s, tx: rubber(drag.tx0 + dx, b.minX, b.maxX), ty: rubber(drag.ty0 + dy, b.minY, b.maxY) });
    }
  };

  const onPointerUp = (e) => {
    if (disposed || !pts.has(e.pointerId)) return;
    pts.delete(e.pointerId);
    try { viewport.releasePointerCapture(e.pointerId); } catch { /* 已释放 */ }
    if (pts.size >= 2) return;          // 还在捏合中
    if (pts.size === 1) {
      // 双指 → 单指收指：残留触点接管平移（基点重置防跳变；wikiGraph 同语义）
      const [p] = [...pts.values()];
      const st = get();
      pinch = null;
      drag = { x0: p.x, y0: p.y, tx0: st.tx, ty0: st.ty, moved: true };
      return;
    }
    // 全部松手
    const wasPinch = !!pinch;
    pinch = null;
    const st = get();
    const b = boundsFor(st.s);
    const outX = st.tx < b.minX - 0.5 || st.tx > b.maxX + 0.5;
    const outY = st.ty < b.minY - 0.5 || st.ty > b.maxY + 0.5;
    if (outX || outY) {
      // 越界回弹（规格表 S4：钳制回边界；reduced-motion 直切）
      const dur = prefersReducedMotion() ? 0 : ANIM_MS;
      animateTo({
        s: st.s,
        tx: Math.min(b.maxX, Math.max(b.minX, st.tx)),
        ty: Math.min(b.maxY, Math.max(b.minY, st.ty)),
      }, dur);
      drag = null;
      return;
    }
    // 1× 捏合收指回适屏（iOS Photos：缩回 fit 即吸附回正中，规格表 S2/S3）
    if (wasPinch && st.s <= fitState() * 1.01) {
      const f = fitState();
      const vb = boundsFor(f);
      animateTo({ s: f, tx: (vb.minX + vb.maxX) / 2, ty: (vb.minY + vb.maxY) / 2 }, prefersReducedMotion() ? 0 : ANIM_MS);
      drag = null;
      return;
    }
    // 单击/双击判定（drag.moved = 位移过 slop 的拖动，不算 tap）
    if (drag && !drag.moved) {
      const p = vpPos(e);
      const now = performance.now();
      if (lastTap && now - lastTap.t <= TAP_GAP_MS &&
          Math.hypot(p.x - lastTap.x, p.y - lastTap.y) <= TAP_SLOP_PX) {
        lastTap = null;
        onDoubleTap(p); // animateTo 终点自播报
      } else {
        lastTap = { t: now, x: p.x, y: p.y }; // 单击零动作（§2.6），不播报
      }
    } else if (onGestureEnd) {
      // 平移/捏合收束且未被回弹/吸附动画接管 → 播报终档（规格表 §2.8）。
      onGestureEnd();
    }
    drag = null;
  };

  /** 双击：fit ↔ fit×DBL_TAP_ZOOM 切换，锚 = 双击点，带动画（规格表 S5/S6）。
   *  状态机周期 2（自洽性推导见规格表 §2.3）：判据只看当前是否已放大。 */
  const onDoubleTap = (p) => {
    const st = get();
    const fit = fitState();
    const goingZoom = st.s <= fit * 1.05;
    const dur = prefersReducedMotion() ? 0 : ANIM_MS;
    if (goingZoom) {
      const target = clampScale(fit * DBL_TAP_ZOOM);
      // 锚点公式与桌面 applyScale 同源：p 不动 ⇒ t' = p − (p − t)·k
      const k = target / st.s;
      animateTo({ s: target, tx: p.x - (p.x - st.tx) * k, ty: p.y - (p.y - st.ty) * k }, dur);
    } else {
      // 放大态双击 = 复位到「正正好的最终比例」（fit）并居中（S6）
      const vb = boundsFor(fit);
      animateTo({ s: fit, tx: (vb.minX + vb.maxX) / 2, ty: (vb.minY + vb.maxY) / 2 }, dur);
    }
  };

  viewport.addEventListener('pointerdown', onPointerDown);
  viewport.addEventListener('pointermove', onPointerMove);
  viewport.addEventListener('pointerup', onPointerUp);
  viewport.addEventListener('pointercancel', onPointerUp);

  return {
    /** 最近触点类型（桌面 dblclick 去重判据）。 */
    lastPointerType: () => lastPointerType,
    dispose() {
      disposed = true;
      viewport.removeEventListener('pointerdown', onPointerDown);
      viewport.removeEventListener('pointermove', onPointerMove);
      viewport.removeEventListener('pointerup', onPointerUp);
      viewport.removeEventListener('pointercancel', onPointerUp);
      for (const id of [...pts.keys()]) {
        try { viewport.releasePointerCapture(id); } catch { /* 已释放 */ }
      }
      pts.clear();
    },
  };
}

/** Image viewer — <img> served via /api/nf-file, pannable + zoomable.
 *
 *  2026-09-11 (C batch): async — the URL is minted per open (T1: never early),
 *  and carries a path-bound ticket instead of the global token. A load failure
 *  gets exactly ONE re-mint + retry before the error panel (T3 self-healing);
 *  a second failure is treated as deterministic and is NOT retried again. */
async function viewImage(pane, { absPath, fileName, size, objectUrl }) {
  // `objectUrl` = bytes already in the page, no path needed:
  //   · 附件预览腿（attachmentPreview.js）：字节由鉴权路由取回，挂 blob: URL；
  //   · Pop 直开图片腿（PopTool，2026-09-16 imgfix）：工具把 ≤5MB 的图片
  //     内联成 data: URI 随 pop 载荷下发，渲染零请求（票据腿对白名单外的
  //     命名空间——如 `<dataRoot>/logs/**`——必然 401）。
  // 两条腿都**没有**可用的本机路径 ⇒ 不触票据链、不重取字节。
  if (!absPath && !objectUrl) {
    pane.innerHTML = '<div class="canvas-error">Cannot display image: no absolute path available.</div>';
    return;
  }
  const img = new Image();
  img.alt = fileName || 'image';
  // Prevent native image drag. <img> is draggable by default — an accidental
  // drag on the image starts a native DnD session, and a wedged session
  // (e.g. drop outside the window, Safari quirks) suppresses click events
  // page-wide, which presents as "canvas tabs stop responding".
  img.draggable = false;
  let retried = false;
  img.onerror = () => {
    // Self-healing (re-mint) is a **ticket-chain** remedy: it only applies when
    // there is a path to mint for. A blob: URL has no credential to refresh —
    // retrying it would mean fetching a ticket for `null`.
    if (!retried && absPath) {
      // Ticket failures (401 missing, 403 expired) self-heal once; a
      // deterministic failure (400/403 credential-path/404) simply fails the
      // same way again and falls through to the panel below (T4).
      retried = true;
      reMint(absPath).then((next) => {
        if (pane.isConnected) img.src = next;
      });
      return;
    }
    // The load can fail for reasons that have nothing to do with the file
    // itself — a dropped host connection (relay tunnel flap) fails the
    // /api/nf-file fetch exactly like a broken file does. Say both, and give
    // a manual retry (the automatic re-mint above already ran once).
    let reason = 'Could not load the image. The host connection may have dropped, or the file is not a valid image format.';
    if (size && size < 500) reason += ` (File is only ${size} bytes — likely an error page or placeholder, not a real image.)`;
    pane.innerHTML = `<div class="canvas-error">${reason}<br>Path: ${escapeHtml(absPath || fileName || '')}</div>`;
    if (absPath) {
      const retry = document.createElement('button');
      retry.type = 'button';
      retry.textContent = 'Retry';
      retry.className = 'canvas-button viewer-retry-btn';
      retry.addEventListener('click', () => {
        if (!pane.isConnected) return;
        retried = false;
        pane.innerHTML = '';
        pane.classList.add('scrollable');
        viewImage(pane, { absPath, fileName, size, objectUrl: undefined }).catch(() => {});
      });
      pane.appendChild(retry);
    }
  };
  img.onload = () => {
    if (!pane.isConnected) return;  // tab closed while the image was loading
    pane.innerHTML = '';
    pane.classList.remove('scrollable'); // zoom/pan viewport manages its own overflow

    const wrap = document.createElement('div');
    // P0 §6 顶垫共享类（防胶囊遮挡；桌面常态零垫、手机层/环展开态垫）
    wrap.className = 'canvas-image-viewer canvas-viewer-safe-top';
    // 「文件名+体积」标签行退役（2026-10-25 作者报障「胶囊底下文件名称重复显示」）：
    // canvas 顶带标题胶囊（icon + 文件名，split.css .canvas-title）恒在场且是唯一
    // 名称面 —— 本行渲染的「fileName — size」被胶囊叠住只露出边缘，形成同屏重复。
    // 按互斥令退役整层渲染（非 z-index 遮盖）：胶囊在场 ⇒ 不渲染底层标签。
    // 消费面核查：image viewer 唯一消费腿 = canvas 文件页签 / 附件预览页签 /
    // 卡窗（fileViewers.js renderFile ← canvas.js ×2 + attachmentPreview.js
    // renderIntoTab→openTab），三腿标题胶囊全在场，无「无胶囊」消费面。

    const viewport = document.createElement('div');
    viewport.className = 'canvas-image-viewport';
    viewport.appendChild(img);
    wrap.appendChild(viewport);
    pane.appendChild(wrap);

    // ── Transform state: img at translate(tx,ty) scale(s), origin 0 0 ──
    let s = 1, tx = 0, ty = 0;
    /** 上一次适屏写下的比例（「容器变了但用户还停在适屏档」的判据 —— 见下方
     *  geometry 重适屏块；0 = 尚未适屏过）。 */
    let lastFitS = 0;
    const iw = img.naturalWidth || 1, ih = img.naturalHeight || 1;

    const apply = () => { img.style.transform = `translate(${tx}px, ${ty}px) scale(${s})`; };

    const fit = () => {
      const vw = viewport.clientWidth || 1, vh = viewport.clientHeight || 1;
      s = Math.min(vw / iw, vh / ih, 1);
      tx = (vw - iw * s) / 2;
      ty = (vh - ih * s) / 2;
      lastFitS = s;
      apply();
    };
    fit();

    // ── 手机层 a11y 读数（规格表 §2.8）：viewport 读屏名带档位；aria-live
    //    行在档位变化时播报一次（navController sr-only live 区同族）。 ──
    viewport.setAttribute('role', 'img');
    const live = document.createElement('div');
    live.className = 'nf-img-zoom-live';
    live.setAttribute('data-nf-img-live', '');
    live.setAttribute('aria-live', 'polite');
    live.setAttribute('role', 'status');
    wrap.appendChild(live);
    const fitScaleOf = () => {
      const vw = viewport.clientWidth || 1, vh = viewport.clientHeight || 1;
      return Math.min(vw / iw, vh / ih, 1);
    };
    const announce = () => {
      viewport.setAttribute('aria-label',
        `${img.alt || 'image'} ${t('canvas.a11yZoomLevel').replace('%d', String(Math.round(s * 100)))}`);
      live.textContent = `${t('canvas.a11yZoomLevel').replace('%d', String(Math.round(s * 100)))}`;
    };

    // ── Geometry-converge re-fit ───────────────────────────
    // The first fit() above can run while the canvas panel is still in its
    // open transition (flex-basis 0 → target, 0.32s), reading a near-zero
    // viewport width ⇒ s≈0 ⇒ the image renders at scale(0), visually blank
    // (the "Fit to view" button later re-runs the same fit and "fixes" it).
    // ResizeObserver fires on every transition frame, so re-fit as the
    // geometry settles — until the user takes over zoom/pan manually.
    let userTouched = false;
    const geometryObserver = new ResizeObserver(() => {
      if (!pane.isConnected) return;
      // 跟随条件（stack-view 三处批① 2026-11-03）：用户从未碰过 **或** 用户仍
      // 停在初始适屏档（s === lastFitS —— 没主动调过档）。旧判据 `!userTouched`
      // 把「轻触过图片」当成「用户接管了缩放」，容器一变（转屏/分屏/层翻转）
      // 图就冻在旧容器的像素宽上 —— 作者实拍「只占 1/3–1/2 宽」的成因面
      // （机械复现：横屏后 358/668 = 0.536）。
      if (!userTouched || s === lastFitS) fit();
    });
    geometryObserver.observe(viewport);

    const applyScale = (next, pivot) => {
      userTouched = true; // manual zoom — stop auto re-fit
      const old = s;
      s = next;
      if (pivot) {
        // Keep the content point under the cursor stationary.
        const k = s / old;
        tx = pivot.x - (pivot.x - tx) * k;
        ty = pivot.y - (pivot.y - ty) * k;
      }
      apply();
    };

    // ── Pan (pointer drag) — OPT-IN ONLY (cold-start batch 2026-10-30) ──
    // Default = non-drag: opening the viewer used to arm the canvas in grab
    // state (cursor: grab + drag on any press-and-move), so a stray drag
    // yanked the image. Pan now arms in exactly two ways:
    //   · the hand tool toggle (first button of the shared zoom bar), or
    //   · holding Space (press-and-hold temporary pan, editor convention).
    // （手机手势批：状态声明与 injectHand 提前到 zoomApi 之前 —— 手机层条
    //   零 DOM，注入走 onBarMount 钩子随挂随行，见下。）
    let drag = null;
    let panEnabled = false;   // hand toggle latch
    let spaceHeld = false;    // Space press-and-hold
    const syncPanCursor = () => {
      viewport.classList.toggle('pan-enabled', panEnabled || spaceHeld);
    };

    // Hand tool toggle — first button of the shared zoom bar. 注入改走
    // onBarMount 钩子（手机手势批）：controls:'mobile-gesture' 时手机层条不建
    // DOM（S1），而层跨越 768px 时条动态拆/挂而 pane 不重挂 —— 旧实现只在
    // onload 时 querySelector 一次，条重挂后抓手钮会永久丢失。幂等防双注，
    // 重挂时档位（is-on）跟随 latch，不漂移。
    const injectHand = (bar) => {
      if (bar.querySelector('.canvas-pan-toggle')) return;
      const hand = document.createElement('button');
      hand.type = 'button';
      hand.className = 'canvas-button canvas-zoom-btn canvas-pan-toggle';
      hand.innerHTML = '<i data-lucide="hand"></i>';
      hand.title = t('canvas.pan');
      hand.setAttribute('aria-label', t('canvas.pan'));
      hand.classList.toggle('is-on', panEnabled);
      hand.addEventListener('click', () => {
        panEnabled = !panEnabled;
        hand.classList.toggle('is-on', panEnabled);
        if (!panEnabled && !spaceHeld) { drag = null; viewport.classList.remove('panning'); }
        syncPanCursor();
      });
      bar.insertBefore(hand, bar.firstChild);
      createIconsIn(hand);
    };

    const zoomApi = enableViewerZoom(pane, {
      min: 0.05, max: 8,
      getScale: () => s,
      applyScale,
      reset: () => { userTouched = false; fit(); }, // explicit re-fit re-arms geometry follow
      attachTo: viewport,
      // 手机层 image viewer：控制栏不建（S1）；桌面腿照旧（'bar' 默认）。
      controls: 'mobile-gesture',
      // 手机层双指捏合在 pointer 管线时，Safari 的 gesturestart/change 让路
      // （同一捏合两路同收 = 双重缩放；桌面触控板 pinch 照旧）。
      gestureOwned: () => mobileGestures != null && mobilePts > 0,
      // 抓手钮随条挂载注入（手机层不挂 → 回桌面层重挂时注入随行，N11 面）。
      onBarMount: injectHand,
    });

    // ── 手机层纯手势腿（规格表 S3/S4/S5）：缩放状态与动画由本闭包统一持有，
    //    桌面腿（applyScale/fit）与手机腿读写同一组 (s, tx, ty) —— 单一真值，
    //    不存在第二套查看器（作者铁律）。 ──
    let mobileGestures = null;
    let mobilePts = 0;
    let animRaf = 0;
    const easeOutCubic = (u) => 1 - Math.pow(1 - u, 3);
    const clampScale = (v) => {
      const fit = fitScaleOf();
      return Math.min(fit * 4 /* PINCH_MAX_RATIO */, Math.max(fit, v));
    };
    /** 内容钳制盒：图覆盖视口的轴钳到边界；不足的轴钉居中（规格表 S4）。 */
    const boundsFor = (sc) => boundsFull(sc);
    const boundsFull = (sc) => {
      const vw = viewport.clientWidth || 1, vh = viewport.clientHeight || 1;
      const w = iw * sc, h = ih * sc;
      const x = w >= vw ? { minX: vw - w, maxX: 0 } : { minX: (vw - w) / 2, maxX: (vw - w) / 2 };
      const y = h >= vh ? { minY: vh - h, maxY: 0 } : { minY: (vh - h) / 2, maxY: (vh - h) / 2 };
      return { ...x, ...y };
    };
    const setNow = (st) => { s = st.s; tx = st.tx; ty = st.ty; apply(); };
    const stopAnim = () => { if (animRaf) { cancelAnimationFrame(animRaf); animRaf = 0; } };
    /** 带动画变换（220ms ease-out；reduced-motion dur=0 直切，规格表 §2.8）。 */
    const animateTo = (target, dur) => {
      stopAnim();
      if (dur <= 0) { setNow(target); announce(); return; }
      const from = { s, tx, ty };
      const t0 = performance.now();
      const step = () => {
        const u = Math.min(1, (performance.now() - t0) / dur);
        const e = easeOutCubic(u);
        setNow({
          s: from.s + (target.s - from.s) * e,
          tx: from.tx + (target.tx - from.tx) * e,
          ty: from.ty + (target.ty - from.ty) * e,
        });
        if (u < 1) { animRaf = requestAnimationFrame(step); }
        else { animRaf = 0; announce(); }
      };
      animRaf = requestAnimationFrame(step);
    };
    mobileGestures = bindMobileGestures(viewport, {
      get: () => ({ s, tx, ty }),
      apply: setNow,
      fitState: fitScaleOf,
      clampScale,
      boundsFor: boundsFull,
      animateTo,
      stopAnim,
      onInteract: () => { userTouched = true; mobilePts = 1; }, // 手动接管：停 geometry 跟随
      onGestureEnd: announce, // 捏合/平移收束播报终档（§2.8；动画路径 animateTo 自播报）
    });
    // mobilePts 语义：≥1 表示手机触点管线在场（gestureOwned 判据）——
    // pointerdown 置 1；松手清零让 Safari 桌面触控板 pinch 恢复直管。
    // （手机手势批：匿名 → 命名，teardown 摘除，防关页签后残留。）
    const onMobilePtsClear = () => { mobilePts = 0; };
    viewport.addEventListener('pointerup', onMobilePtsClear);
    viewport.addEventListener('pointercancel', onMobilePtsClear);
    announce();

    // Space = temporary pan. document-level (the cursor may sit anywhere over
    // the viewer); armed ONLY while the pointer hovers this viewport — global
    // Space hijack would eat button keystrokes elsewhere in the app. Detached-
    // pane self-cleanup so re-opened tabs never stack listeners. Editable
    // focus keeps Space for typing.
    let pointerOver = false;
    viewport.addEventListener('mouseenter', () => { pointerOver = true; });
    viewport.addEventListener('mouseleave', () => { pointerOver = false; });
    const onSpaceDown = (e) => {
      if (!pane.isConnected) {
        document.removeEventListener('keydown', onSpaceDown);
        document.removeEventListener('keyup', onSpaceUp);
        return;
      }
      if (e.code !== 'Space' || e.repeat) return;
      const ae = document.activeElement;
      if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || /** @type {HTMLElement} */ (ae).isContentEditable)) return;
      if (!pointerOver && !(ae && pane.contains(ae))) return; // 只认本查看器上的 Space
      e.preventDefault();
      spaceHeld = true;
      syncPanCursor();
    };
    const onSpaceUp = (e) => {
      if (e.code !== 'Space' || !spaceHeld) return;
      spaceHeld = false;
      if (!panEnabled) { drag = null; viewport.classList.remove('panning'); }
      syncPanCursor();
    };
    document.addEventListener('keydown', onSpaceDown);
    document.addEventListener('keyup', onSpaceUp);

    viewport.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      if (!(panEnabled || spaceHeld)) return; // default non-drag (cold-start batch)
      userTouched = true; // manual pan — stop auto re-fit
      drag = { x: e.clientX, y: e.clientY, tx, ty };
      viewport.setPointerCapture(e.pointerId);
      viewport.classList.add('panning');
    });
    viewport.addEventListener('pointermove', (e) => {
      if (!drag) return;
      tx = drag.tx + (e.clientX - drag.x);
      ty = drag.ty + (e.clientY - drag.y);
      apply();
    });
    const endDrag = () => { drag = null; viewport.classList.remove('panning'); };
    viewport.addEventListener('pointerup', endDrag);
    viewport.addEventListener('pointercancel', endDrag);
    // Double-click: toggle fit ↔ 100% (cursor-anchored) — DESKTOP ONLY.
    // 手机层的双击由 bindMobileGestures 的 tap 判定接管（fit ↔ 2.5×fit，带锚点
    // 动画）；触屏上浏览器同样会合成 dblclick，两路都会跑 ⇒ 这里按「触屏起手」
    // 让路（lastPointerType 由手机管线维护；鼠标腿恒为 mouse）。
    viewport.addEventListener('dblclick', (e) => {
      if (mobileGestures) {
        if (isMobileLayer()) return;                     // 手机层 = 手势管线单一真值
        if (mobileGestures.lastPointerType() === 'touch') return; // 触屏文档的合成 dblclick
      }
      const rect = viewport.getBoundingClientRect();
      const pivot = { x: e.clientX - rect.left, y: e.clientY - rect.top };
      if (Math.abs(s - 1) < 0.01) { userTouched = false; fit(); } // toggle to fit = re-arm follow
      else applyScale(1, pivot);
    });

    // 卸载（阅读/编辑体验优化批 2026-11-01）：修前三件在关页签后都不回收 ——
    // 观察 viewport 的 ResizeObserver（持 img + viewport，永不 disconnect）、
    // 缩放引擎（document 级 cmd± keydown + 模块级 hovered 集合里那条永不出栈的
    // ctl）、以及 Space 平移的 document keydown/keyup。修前唯一的自愈是
    // 「下一次任意 keydown 且 pane 已断连」才摘 Space 键，observer 与 hovered
    // 无任何回收路径。dispose 由 fileViewers.disposeViewer 调用。
    // 手机手势批追加：第 4 件 = 手机 pointer 管线 + 动画 rAF（本批新增的
    // mobileGestures dispose + stopAnim，一并回收）。
    /** @type {any} */ (pane)._nfImageTeardown = () => {
      try { geometryObserver.disconnect(); } catch { /* 已断开 */ }
      try { zoomApi.destroy(); } catch { /* 引擎已卸 */ }
      if (mobileGestures) { try { mobileGestures.dispose(); } catch { /* 已卸 */ } mobileGestures = null; }
      stopAnim();
      viewport.removeEventListener('pointerup', onMobilePtsClear);
      viewport.removeEventListener('pointercancel', onMobilePtsClear);
      document.removeEventListener('keydown', onSpaceDown);
      document.removeEventListener('keyup', onSpaceUp);
    };
  };
  // M2 账本接缝（imgdoor #542 推广批 · 设计 v1 §7 M1-2）：票据腿经 `nfViewerUrl`
  // ——`nf:<absPath>` 账本命中 = blob URL 直渲染（第二次打开零取数）；未命中走既有
  // 票据链并入账。`objectUrl` 腿（字节已在手）原样透传，零行为变化。
  img.src = await nfViewerUrl({ absPath, objectUrl, pane });
}

export default {
  name: 'image',
  label: 'Image',
  extensions: ['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico', '.avif', '.tiff', '.tif'],
  binary: true,
  priority: 0,
  render: viewImage,
  /** Protocol teardown — see _nfImageTeardown above. */
  dispose: (pane) => {
    const slot = /** @type {any} */ (pane);
    const fn = slot._nfImageTeardown;
    slot._nfImageTeardown = null;
    if (typeof fn === 'function') fn();
  },
};
