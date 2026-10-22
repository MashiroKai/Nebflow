// popArtifacts.js — the Pop tool's chat-stream artifact faces (pop-upgrade
// batch, 2026-10-03 author ruling 「去掉card工具,然后对Pop工具进行升级」+
// 「Pop要支持一次工具调用多个Pop文件」).
//
// The backend PopTool returns its artifact payload as the tool result:
//   ___POP_JSON___{"fileRefs":…,"warnings":…,"items":[…]}
// AgentCore forwards the verbatim string as frontendContent (ToolEnd frame +
// .ui.json history), so live rendering (chat.js renderTool) and history
// replay (persistence.js) both land here — one parser, one renderer, no
// second copy of the payload contract.
//
// Two faces (the author-approved preview demo lives at demo/pop-upgrade-demo.html;
//   that path is git-ignored — `.gitignore:38` `*demo*.html` — so it is an
//   author-side preview, not a tracked deliverable):
//   - MEDIA (image / video / audio) — no Canvas tab. Several items in one call
//     render as ONE WeChat-style stacked card: front card + peeking layers to
//     the right, the 「展开 N」 pill beside the stack on the sender side (user
//     rows: left; LLM rows: right — 三修 ①, 2026-10-06); drag switches; a click
//     on the collapsed stack opens Canvas with the SHOWN (front) card — swipe
//     to the Nth card and the click opens N (复报令 2026-10-06 19:43 A-1;
//     三修 ③'s first-item face is the no-swipe case), and an
//     expanded row's whole-face click opens Canvas with THAT item (复验令
//     2026-10-06 16:47 — expanded was the last lightbox leg, and the collapsed
//     binding itself was dead on real clicks, see buildMediaStack); expand
//     flattens to one card per row (「收起」 re-stacks). A single media item
//     renders flat with no stack at all, and its whole-card click opens Canvas
//     like a file card (三修 ②, pathless inline bytes keep the lightbox zoom).
//     (canvas-media Wave2, 2026-10-05
//     author ruling OD-1 = (b): audio joins the face video already had —
//     native <audio controls> strip, ticket leg like video, no stack chrome.)
//   - FILE (documents / HTML animations / everything else) — file cards in the
//     artifact row beside the media faces: filename + a forward button whose
//     hover text is 「在 Canvas 打开」. The row carries `.row.pop-artifact`
//     (deliberately NOT `.row.tool`): turnGroup's tuck set is
//     `.row.tool` + thinking rows only, so the artifact survives the
//     turn-process collapse, and utils.countsAsRealMessage counts it like a
//     `.row.card-content` deliverable (D4 parity).
//
// Byte legs (shared FileRefs policy, one definition with the backend):
//   - `src` present  → the bytes ride in the payload (data: URI) — renders
//     with no request, replays from history.
//   - `src` absent   → the item carries its absolute path; the frontend mints
//     an /api/nf-file ticket (nfTicket.ticketUrl — the single URL builder)
//     and re-mints once on a load error. No URL is built anywhere else.
//
// Legacy history rows (the pre-batch Pop card / retired Card tool) keep their
// old render paths in chat.js / persistence.js — this module only owns the
// sentinel payload face.

import { t } from './i18n.js';
import state from './state.js';
import { escapeHtml, smartScroll, localizeToolLabel, localizeToolSummary } from './utils.js';
import { ticketUrl, reMint } from './nfTicket.js';
// quote-message 一期（2026-10-09）：用户媒体/文件卡行挂消息 id + 右键「引用」。
// 摘要 = [图片]/[文件] 文件名（spec §2.5 表）。挂点在本 builder ⇒ live 发送、
// 历史恢复（persistence.buildUserAttachmentRows）、attachmentsSaved 原位重渲
// （patchUserMediaRow 走 replaceWith ⇒ 重建后仍带 id/菜单）三路同构。
import { attachQuoteMenu, nextChatMsgId } from './msgQuote.js';

export const POP_SENTINEL = '___POP_JSON___';

/** Window-role motion switch (UI-G, author 2026-10-03 19:59 ruling). Read
 *  through the view that owns the row, falling back to the active view — this
 *  module renders for both the main window and the subagent popups (history
 *  replay targets whichever view is active), so it must not hard-code one.
 *  The decision itself lives in chatView.js (`isMainWindow` →
 *  `view.motionEnabled`); this module only consumes it, so popArtifacts.js
 *  keeps its existing dependency direction (no chatView.js import edge). */
function motionEnabled(view) {
  const v = view || (state.getActiveView ? state.getActiveView() : null);
  return typeof v?.motionEnabled === 'function' ? v.motionEnabled() : true;
}

/** True when a tool result IS a Pop artifact payload (position 0 — the URL
 *  leg returns plain text and never matches). */
export function isPopPayload(content) {
  return typeof content === 'string' && content.startsWith(POP_SENTINEL);
}

/** Parse the payload JSON. The truncated-preview shape (a persisted-output
 *  window) yields null — callers fall back to the safe placeholder paths. */
export function parsePopPayload(content) {
  if (!isPopPayload(content)) return null;
  try {
    const p = JSON.parse(content.substring(POP_SENTINEL.length));
    return Array.isArray(p.items) ? p : null;
  } catch {
    return null;
  }
}

/** The click-to-open path shared by the file-card face and search results —
 *  moved here from chat.js (chat.js re-exports it) so popArtifacts cannot
 *  import chat.js (that would close chat.js → popArtifacts → chat.js). */
export function openPopArtifact(filePath, title) {
  const fileName = filePath.split('/').pop() || filePath;
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: {
      id: 'file:' + filePath,
      title: title || fileName,
      itemType: '',
      content: '',
      absPath: filePath,
      pinned: true
    }
  }));
}

/** The openable-artifact predicate shared with the search results click —
 *  array-aware for the batch face (a search hit opens the FIRST file of the
 *  batch). Legacy 'Card' labels stay accepted: old histories keep their
 *  pre-retirement rows searchable. */
export function popArtifactFromInput(label, inputJson) {
  const name = label ? label.split('(')[0].split('\n')[0].trim() : '';
  if ((name !== 'Pop' && name !== 'Card') || !inputJson) return null;
  let filePath = '';
  let title = '';
  try {
    const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
    let v = inp.filePath || '';
    if (Array.isArray(v)) v = v.find(p => typeof p === 'string' && p.trim()) || '';
    filePath = v;
    title = inp.title || '';
  } catch { /* malformed input → no openable artifact */ }
  return filePath ? { filePath, title } : null;
}

/* ─────────────── shared bits ─────────────── */

function humanSize(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n < 0) return '';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(n < 10 * 1024 ? 1 : 0) + ' KB';
  return (n / (1024 * 1024)).toFixed(n < 10 * 1024 * 1024 ? 1 : 0) + ' MB';
}

/** Icon glyph + color family for a file-card icon (by extension). */
function fileIconStyle(ext) {
  const e = (ext || '').toLowerCase();
  if (e === 'pdf') return { label: 'PDF', cls: 'pdf' };
  if (e === 'html' || e === 'htm') return { label: 'HTML', cls: 'html' };
  if (e === 'md' || e === 'markdown') return { label: 'MD', cls: 'md' };
  if (['doc', 'docx'].includes(e)) return { label: 'DOC', cls: 'doc' };
  if (['xls', 'xlsx', 'xlsm', 'csv', 'tsv'].includes(e)) return { label: 'XLS', cls: 'xls' };
  if (['ppt', 'pptx'].includes(e)) return { label: 'PPT', cls: 'ppt' };
  if (e === 'epub') return { label: 'EPUB', cls: 'epub' };
  if (['json', 'yaml', 'yml'].includes(e)) return { label: e.toUpperCase(), cls: 'data' };
  if (e) return { label: e.toUpperCase().slice(0, 4), cls: 'code' };
  return { label: 'FILE', cls: 'code' };
}

const FWD_ICON = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M13.2 5.1c0-.86 1.02-1.3 1.65-.73l7.06 6.4c.44.4.44 1.08 0 1.48l-7.06 6.4c-.63.57-1.65.13-1.65-.73v-2.9c-4.3.14-7.02 1.42-9.06 4.06-.5.65-1.55.2-1.4-.6.9-4.7 3.9-8.5 10.46-8.9V5.1z"/></svg>';

/** Hydrate one media element's bytes. `src` (data URI) wins; otherwise the
 *  path rides an /api/nf-file ticket, re-minted once on a load error. On the
 *  TERMINAL failure the element is marked (`nf-img-failed` +
 *  `dataset.nfMediaFailed`) and `onFail` runs — the .pop-img/.pop-video family
 *  carries its own placeholder style now (the neighbouring `.bubble.ai img`
 *  rule never applied to a Pop row, which is the 缺陷 D this fixes), and the
 *  caller appends the plain-text note line once the face is assembled.
 *
 *  两条腿的分工与回落（mediaface-resolver-fallback 批 2026-10-22，#665 续）：
 *  - resolver 腿（用户附件面，uploads 路由）= 快路；**URL 出错不再是终态** ——
 *    旧标签页会话 cookie 滑动窗过期时 StaticAuth（session-only 轨）对 /uploads/
 *    一律 403（WS 长链不回签 ⇒ 页面看着在线、媒体全灭）；网关重启窗 404/连接错
 *    同理。此前此处直接 fail()，自愈腿永远等不到出场。
 *  - 票腿（nf-file 铸票）= 自愈路：自带 reMint 一次 + 铸票 401/403 的 Bearer
 *    真令牌重试（nfTicket.js）+ 旧根字面路径双读（NfFilePolicy.scala,
 *    b9ed91bb6）。**双腿都败**才落终态 fail —— 与 媒体统一适配批「resolver
 *    拒答（null）回落票腿；票腿也 404 才是终态」的既定语义补全成同一形状。 */
function hydrateMedia(el, item, onFail) {
  if (item.src) {
    el.src = item.src;
    return;
  }
  const path = item.path;
  const fail = () => {
    // 终态闩：双腿各有一次出场机会，fail 只落一次（appendFailureNote 已有
    // 幂等守卫，这里是 class/dataset 面的同款保险）。
    if (el.dataset.nfMediaFailed === '1') return;
    el.classList.add('nf-img-failed');
    el.dataset.nfMediaFailed = '1';
    if (typeof onFail === 'function') onFail(el);
  };
  // #491 主题三: user attachments ride the uploads route, NOT the nf-file
  // ticket — a resolver on the item replaces the ticket leg entirely.
  // 媒体统一适配批（2026-10-20）：resolver **拒答**（null）不再是终态 —— 该路径
  // 不属 uploads URL 族（社交通道入站媒体的指针登记落 media 根），回落**同一**
  // nf-file 票腿（2026-10-07 撤门后同域判据，与 agent Pop 媒体同路由）；票腿也
  // 404 才是终态失败。uploads 族路径仍由 resolver 命中，行为逐字不变。
  if (typeof item.resolver === 'function') {
    let url = null;
    try { url = item.resolver(path); } catch { url = null; }
    if (url) {
      // resolver URL 出错 ⇒ 摘本监听、进票腿（见函数头注）。不用 `once`：
      // 票腿自己要接 error 事件链；本监听出手后必须让位，否则同一 error
      // 会串两条监听（回落票腿后再触发 fail 的终态误伤）。
      const onResolverError = () => {
        el.removeEventListener('error', onResolverError);
        startTicketLeg();
      };
      el.addEventListener('error', onResolverError);
      el.src = url;
      return;
    }
  }
  startTicketLeg();

  function startTicketLeg() {
    // No `once` on the error listener: the FIRST error arms the re-mint, and a
    // second error (re-mint also failed, or the re-minted URL 404s) must reach
    // the terminal branch — with `once` that case left the element blank.
    el.addEventListener('error', () => {
      if (el.dataset.nfReminted === '1') { fail(); return; }
      el.dataset.nfReminted = '1';
      reMint(path)
        .then(url => { if (el.isConnected) el.src = url; })
        .catch(() => fail());
    });
    ticketUrl(path)
      .then(url => { if (el.isConnected) el.src = url; })
      .catch(() => fail());
  }
}

/* ─────────────── media face ─────────────── */

/** A plain-text failure note line appended under a failed media face: the Pop
 *  row had NO visible failure sign at all before (缺陷 D), and a subagent
 *  (static) window shows no animation chrome to hint with — the note is pure
 *  text, so it reads identically in both window roles (UI-C family). */
function appendFailureNote(face, el) {
  if (!face || face.querySelector('.nf-media-failed-note')) return;
  const note = document.createElement('div');
  note.className = 'nf-media-failed-note';
  const name = (el && (el.alt || el.dataset.nfName)) || '';
  const label = t('chat.popMediaFailed');
  // The name is appended here (not interpolated) so the locale string stays a
  // plain sentence and an item with no name shows no dangling separator.
  note.textContent = name ? label + ' — ' + name : label;
  face.appendChild(note);
}

/** One media card face for `item`: <img>/<video>/<audio> + the video overlay face.
 *  `owner` (a view or the active view) supplies the window-role motion switch;
 *  it is not read here today — the face is static in both roles — but the
 *  parameter keeps the call shape ready for role-dependent chrome.
 *
 *  zcode-484 D1/D3: `natural` receives the media's intrinsic size as soon as it
 *  is known (image `load`, video `loadedmetadata`) so the CALLER can give the
 *  surrounding box the media's own aspect ratio — the box's ratio is what makes
 *  the un-cropped `object-fit: contain` face fill its frame instead of
 *  letterboxing inside a mismatched one.
 *
 *  canvas-media Wave2 (2026-10-05 OD-1 = (b)): an AUDIO item has no intrinsic
 *  frame at all — the native controls strip IS the face. `natural` is never
 *  called for it (nothing to report: audio has no width/height), the box keeps
 *  its CSS fallback and the strip is centered inside it; native controls carry
 *  play state + progress, so no ring / progress / duration-chip chrome is
 *  layered on (克制). Controls are on in BOTH faces: unlike video, an audio
 *  strip without controls is an empty bar — there is no ring affordance to
 *  replace them. Controls clicks are stopPropagation'd so tapping play in the
 *  stack face does not also drive the bring-to-front pager. */
function mediaFace(item, interactive, natural) {
  const wrap = document.createElement('div');
  wrap.className = 'pop-media' + (item.kind === 'audio' ? ' is-audio' : '');
  const report = (w, h) => {
    if (typeof natural === 'function' && Number.isFinite(w) && Number.isFinite(h) && w > 0 && h > 0) {
      natural(w, h);
    }
  };
  if (item.kind === 'audio') {
    const audio = document.createElement('audio');
    audio.className = 'pop-audio';
    audio.preload = 'metadata';
    audio.controls = true;
    audio.dataset.nfName = item.name || item.path || '';
    audio.addEventListener('click', e => e.stopPropagation());
    // Same two legs as img/video: `src` wins, else the /api/nf-file ticket,
    // re-minted once on a load error, terminal failure → note line (缺陷 D).
    hydrateMedia(audio, item, el => appendFailureNote(wrap, el));
    wrap.appendChild(audio);
  } else if (item.kind === 'video') {
    const expandedFace = interactive === 'expanded';
    const video = document.createElement('video');
    video.className = 'pop-video';
    video.preload = 'metadata';
    video.playsInline = true;
    video.dataset.nfName = item.name || item.path || '';
    // Expanded face: native controls from the start. Stack face: the ring is
    // the affordance while paused; controls appear while playing.
    video.controls = expandedFace;
    // The note is appended through the callback (the face must already be in
    // the DOM — `wrap` is by the time the async failure resolves).
    hydrateMedia(video, item, el => appendFailureNote(wrap, el));
    const ring = document.createElement('div');
    ring.className = 'play-ring';
    const chip = document.createElement('div');
    chip.className = 'dur-chip';
    chip.textContent = '';
    video.addEventListener('loadedmetadata', () => {
      // The video's own frame ratio drives the box (zcode-484 D1/D3) — and it
      // is the ONLY point where `videoWidth/videoHeight` are known, so the
      // expand height can also be re-measured here (D4).
      report(video.videoWidth, video.videoHeight);
      const d = Math.round(video.duration);
      if (Number.isFinite(d) && d > 0) {
        chip.textContent = Math.floor(d / 60) + ':' + String(d % 60).padStart(2, '0');
      }
    });
    const progress = document.createElement('div');
    progress.className = 'vd-progress';
    const sync = () => {
      const playing = !video.paused && !video.ended;
      wrap.classList.toggle('playing', playing);
      if (!expandedFace) video.controls = playing;
      if (video.duration > 0 && video.currentTime > 0) {
        progress.style.width = (video.currentTime / video.duration) * 100 + '%';
      }
    };
    video.addEventListener('play', sync);
    video.addEventListener('pause', sync);
    video.addEventListener('ended', sync);
    video.addEventListener('timeupdate', sync);
    if (!expandedFace) {
      ring.addEventListener('click', e => { e.stopPropagation(); video.play().catch(() => {}); });
    } else {
      ring.style.display = 'none';
    }
    wrap.append(video, ring, chip, progress);
  } else {
    const img = document.createElement('img');
    img.className = 'pop-img';
    img.alt = item.name || '';
    img.dataset.nfName = item.name || item.path || '';
    // `loading=lazy` + a definite box: the ratio is pinned on the layer as soon
    // as the bytes arrive, so a lazy image swaps in without a layout jump
    // (zcode-484 D4) instead of the old fixed 256px box.
    img.loading = 'lazy';
    img.draggable = false;
    img.addEventListener('load', () => report(img.naturalWidth, img.naturalHeight));
    hydrateMedia(img, item, el => appendFailureNote(wrap, el));
    wrap.appendChild(img);
  }
  return wrap;
}

/** Give `layer` the media's OWN aspect ratio, scaled to fit `maxW`×`maxH`. The
 *  box ratio then equals the media ratio exactly, so the un-cropped
 *  `object-fit: contain` face is flush — no letterbox bars, no crop. Called
 *  again whenever a face reports its intrinsic size, which is why every box has
 *  a definite size even before its bytes are in (the CSS fallback ratio holds
 *  until then). */
function fitLayer(layer, w, h, maxW, maxH) {
  const scale = Math.min(maxW / w, maxH / h, 1);
  layer.style.width = Math.round(w * scale) + 'px';
  layer.style.aspectRatio = w + ' / ' + h;
  layer.dataset.nfRatio = w + 'x' + h;
}
/** The stack box (front card) allowance — the WeChat-style card size. */
const STACK_MAX = { w: 220, h: 320 };
/** The expanded column's per-item allowance — full column width, taller cap. */
const EXPAND_MAX = { w: 220, h: 420 };

/** #491 主题三 (AC-S1): the WeChat-style in-stream lightbox — the zoom face
 *  shared by BOTH sides (user attachments and LLM Pop media). One overlay on
 *  document.body: dark backdrop, the media centered at its own ratio, the file
 *  name, and Canvas as the SECONDARY explicit entry (the ruling: 「媒体（图片/
 *  视频）点击放大 = 消息流内灯箱/浮层（微信式，就地放大，Esc/点击空白关闭）；
 *  Canvas 打开保留为次级动作」). File-type items never come here — they stay
 *  small cards whose click IS the Canvas open. */
let _lightboxEl = null;
let _lightboxKey = null;

export function closeMediaLightbox() {
  if (!_lightboxEl) return;
  const el = _lightboxEl;
  _lightboxEl = null;
  if (_lightboxKey) {
    window.removeEventListener('keydown', _lightboxKey, true);
    _lightboxKey = null;
  }
  el.remove();
}

/** `item`: {kind:'image'|'video', name, src?, path?, resolver?} — the same
 *  item shape the faces build, so callers pass what they already hold. */
export function openMediaLightbox(item) {
  if (!item || (item.kind !== 'image' && item.kind !== 'video')) return;
  closeMediaLightbox();

  const overlay = document.createElement('div');
  overlay.className = 'nf-lightbox';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-label', item.name || 'media');
  const stage = document.createElement('div');
  stage.className = 'nf-lightbox-stage';
  const head = document.createElement('div');
  head.className = 'nf-lightbox-head';
  const name = document.createElement('div');
  name.className = 'nf-lightbox-name';
  name.textContent = item.name || '';
  head.appendChild(name);
  // Secondary entry: Canvas open (only when the item knows an absolute path).
  if (item.path) {
    const canvasBtn = document.createElement('button');
    canvasBtn.type = 'button';
    canvasBtn.className = 'nf-lightbox-canvas';
    canvasBtn.innerHTML = FWD_ICON + '<span>' + escapeHtml(t('chat.popOpenCanvas')) + '</span>';
    canvasBtn.addEventListener('click', e => {
      e.stopPropagation();
      // Act-and-exit for the lightbox layer: Canvas opens, the zoom face closes.
      closeMediaLightbox();
      openPopArtifact(item.path, item.name);
    });
    head.appendChild(canvasBtn);
  }
  stage.appendChild(head);

  let mediaEl;
  if (item.kind === 'video') {
    mediaEl = document.createElement('video');
    mediaEl.className = 'nf-lightbox-media';
    mediaEl.controls = true;
    mediaEl.playsInline = true;
    mediaEl.preload = 'metadata';
  } else {
    mediaEl = document.createElement('img');
    mediaEl.className = 'nf-lightbox-media';
    mediaEl.alt = item.name || '';
    mediaEl.draggable = false;
  }
  stage.appendChild(mediaEl);
  overlay.appendChild(stage);
  // Blank-area click closes (the ruling's 「点击空白关闭」): only a click that
  // landed on the overlay itself — clicks on the media/head stop here.
  overlay.addEventListener('click', e => {
    if (e.target === overlay) closeMediaLightbox();
  });
  // Esc closes the TOP layer only. The handler rides window CAPTURE — window
  // fires before document, where the search modal's own three-stage Esc
  // handler lives; without this, an open modal would swallow every Escape and
  // the lightbox could only be closed by the mouse. stopImmediatePropagation
  // keeps the modal shut for THIS press; a second Esc (lightbox gone) reaches
  // the modal's own contract again.
  _lightboxKey = e => {
    if (e.key === 'Escape') {
      e.stopImmediatePropagation();
      closeMediaLightbox();
    }
  };
  window.addEventListener('keydown', _lightboxKey, true);
  document.body.appendChild(overlay);
  _lightboxEl = overlay;
  // Same byte legs as the inline faces: inline src wins, then the item's own
  // resolver (uploads route for user attachments), then the ticket leg.
  hydrateMedia(mediaEl, item, null);
}

/** The per-card Canvas secondary entry (ruling: 「卡片…上的显式入口」). Rendered
 *  on flat media faces and expanded stack layers — the collapsed stack keeps
 *  click-to-advance (the retained R8 invariant), its zoom/Canvas entries live
 *  one 「展开」 away. Null when the item has no absolute path to open. */
function canvasCornerButton(item) {
  if (!item || !item.path) return null;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'pop-media-canvas';
  btn.setAttribute('aria-label', t('chat.popOpenCanvas'));
  btn.title = t('chat.popOpenCanvas');
  btn.innerHTML = FWD_ICON;
  btn.addEventListener('click', e => {
    e.stopPropagation();
    openPopArtifact(item.path, item.name);
  });
  return btn;
}

/** 三修 ② (2026-10-06 10:59 作者截图令): a media card's WHOLE-face click is the
 *  CANVAS open — the exact same single point (openPopArtifact) and detail shape
 *  the file card's whole-card click uses; no second open path is minted. An
 *  item without an absolute path (a live user attachment whose bytes ride
 *  inline — Canvas cannot open a pathless file) keeps the #491 主题三 lightbox
 *  zoom: same face that case had before this ruling, no bogus open dispatch. */
function openMediaItemClick(item) {
  if (item && item.path) openPopArtifact(item.path, item.name);
  else openMediaLightbox(item);
}

/* ── 触控板横滑翻页（2026-10-22 作者令「除了长按左右拖动来切换，mac的双指
   左右滑动也要支持」）——同图谱胶囊环批手法（canvas.js initCapsuleRing 环上滚轮：
   wheel deltaX 横滑累积 + 阈值步进 + 动量抑制，交互语言全站一致）：
   · 步进阈值与环同档（两指一次横扫的 deltaX 量级）；
   · 动量抑制：翻页后进入抑制窗，事件流静默 ≥ POP_SWIPE_QUIET_MS 才重新武装
     ——惯性滚动尾巴不再重复翻页（一次动量窗口只翻一页；连续慢滚按累积量翻页）；
   · 纵滚放行：竖向主导不劫持不 preventDefault（叠卡嵌在聊天流里，双指上下滚
     继续滚聊天列），同时清累积 = 触控板微 deltaX 抖动过滤（竖滚斜漂噪声不攒出
     误翻）；Shift+纵滚 = 显式横意（环同款仲裁）。 */
const POP_SWIPE_STEP_PX = 56;    // 横滑一格步进（= canvas.js RING_WHEEL_STEP 同档）
const POP_SWIPE_QUIET_MS = 160;  // 动量窗：翻页后事件流静默 ≥160ms 才重新武装

/**
 * The stacked media group (≥2 items): pill beside the stack on the sender-side
 * (user rows left / LLM rows right, 三修 ①). A pointer drag of >38px goes
 * next / previous; a trackpad two-finger horizontal swipe (wheel deltaX,
 * 2026-10-22 作者令) goes next / previous too — threshold + momentum-window
 * suppression, coexisting with the drag (separate decision windows); 「展开 N」
 * flattens, 「收起」 re-stacks. A click on the
 * COLLAPSED stack opens Canvas with the SHOWN (front) card — swipe to the Nth
 * card and the click follows the swipe (复报令 2026-10-06 19:43 A-1); the
 * first card before any swipe (三修 ③). An EXPANDED row's whole-face click
 * opens Canvas with THAT row's item (复验令 16:47) — the lightbox survives
 * only as openMediaItemClick's pathless-inline fallback.
 */
function buildMediaStack(items) {
  const area = document.createElement('div');
  area.className = 'pop-stack-area';
  const pill = document.createElement('button');
  pill.type = 'button';
  pill.className = 'pill';
  pill.textContent = t('chat.popExpand', { n: items.length });
  const view = document.createElement('div');
  view.className = 'pop-stack-view';
  const expandView = document.createElement('div');
  expandView.className = 'pop-expand-view';
  area.append(pill, view, expandView);

  let front = 0;
  let expanded = false;
  // The media's own ratio, per item, as soon as the bytes report it. Until then
  // the CSS fallback ratio holds the box (never a 0-height frame).
  const ratios = items.map(() => null);
  const layers = items.map((item, i) => {
    const layer = document.createElement('div');
    layer.className = 'media-layer';
    layer.appendChild(mediaFace(item, 'stack', (w, h) => {
      ratios[i] = { w, h };
      // The front card drives the stack box, and the expanded row drives its own
      // layer — re-fit whichever face this item owns (zcode-484 D1/D4).
      if (i === front) fitLayer(view, w, h, STACK_MAX.w, STACK_MAX.h);
      fitLayer(expandLayers[i], w, h, EXPAND_MAX.w, EXPAND_MAX.h);
      if (expanded) settleExpandHeight();
    }));
    view.appendChild(layer);
    return layer;
  });
  const expandLayers = items.map((item, i) => {
    const layer = document.createElement('div');
    layer.className = 'media-layer';
    layer.appendChild(mediaFace(item, 'expanded', (w, h) => {
      ratios[i] = { w, h };
      if (i === front) fitLayer(view, w, h, STACK_MAX.w, STACK_MAX.h);
      fitLayer(layer, w, h, EXPAND_MAX.w, EXPAND_MAX.h);
      if (expanded) settleExpandHeight();
    }));
    // 复验令 (2026-10-06 16:47 作者核查令; amends the 三修-era "expanded keeps
    // its zoom click" reading): an expanded row's WHOLE-face click opens CANVAS
    // with ITS item — the same single point as every other face. The expand
    // view has no pointer-capture drag leg, so a real click does reach this
    // layer (unlike the collapsed view — see the view-level binding below).
    layer.addEventListener('click', () => openMediaItemClick(item));
    const canvasBtn = canvasCornerButton(item);
    if (canvasBtn) layer.appendChild(canvasBtn);
    expandView.appendChild(layer);
    return layer;
  });

  // zcode-484 D2: only the front card and its two peeking neighbours are
  // painted. Every item past the third used to land on `layer-back` at the same
  // z-index as the third, so 4+ items overlapped indistinguishably (and clicks
  // could hit a buried card). The parked items are `display:none`.
  const VISIBLE_LAYERS = 3;
  const applyLayers = () => {
    layers.forEach((layer, i) => {
      const p = (i - front + layers.length) % layers.length;
      layer.classList.remove('layer-front', 'layer-mid', 'layer-back');
      layer.classList.add(['layer-front', 'layer-mid', 'layer-back'][Math.min(p, 2)]);
      layer.style.zIndex = String(3 - Math.min(p, 2));
      layer.classList.toggle('is-buried', p > VISIBLE_LAYERS - 1);
    });
    const frontRatio = ratios[front];
    if (frontRatio) fitLayer(view, frontRatio.w, frontRatio.h);
    else { view.style.width = ''; view.style.aspectRatio = ''; }
  };
  applyLayers();

  // 三修 ③ (2026-10-06 10:59 作者截图令), 复验修正 (16:47 作者核查令), 复报令
  // A-1 修 (2026-10-06 19:43 作者截图令): a click on the COLLAPSED stack opens
  // CANVAS with the SHOWN (front) card — the same single point the flat face
  // and the file card share (三修 ②). The old tap-advance retires; switching
  // cards stays on drag/swipe, and a real drag's suppressClick window keeps
  // the swipe from also firing this open.
  //
  // The listener lives on `view`, NOT on the layers — the 复验 root cause: the
  // drag leg calls view.setPointerCapture() on pointerdown, and per the
  // pointer-events spec the click synthesized after a captured pointer fires
  // at the CAPTURE target. Every layer-level click listener was therefore dead
  // code for real clicks (trace: pointerup@view, click@view, NO click@layer) —
  // a JS element.click() skips the pointer sequence entirely, which is exactly
  // why the 10:59 三修 read green in the spec yet never fired for the author.
  // `view` IS the front card's box (layers are inset:0), so a click here is a
  // click on the collapsed stack. (`stack` is declared below; the body only
  // runs on real events, after the declaration.)
  //
  // A-1 病根 (复报令 19:43): the 复验 rebinding hardcoded items[0] — the swipe
  // index (`front`, the same variable applyLayers paints from) never reached
  // the click path, so after a swipe to the Nth card the click still opened
  // the first. Read `front` live: swipe to N → click opens N. Before any
  // swipe front === 0 — the 三修 ③ first-item face, unchanged. (`front` is a
  // `let` captured by this closure; TDZ is unreachable because a click can
  // only fire after the builder returned.)
  view.addEventListener('click', () => {
    if (stack.suppressClick) return;
    openMediaItemClick(items[front]);
  });

  /** The expanded column's natural height: the sum of its layers plus the
   *  column gap. Every layer has a definite height (its own ratio × the column
   *  width) even while its bytes are still loading, so the value is stable. */
  const expandHeight = () => Array.from(expandView.children).reduce((s, c) => {
    const el = /** @type {HTMLElement} */ (c);
    return s + el.offsetHeight;
  }, 0) + 20 * (expandView.children.length - 1);

  /** zcode-484 D4: a media that reports its ratio AFTER the expand animation
   *  started would grow the column mid-flight (the old code summed a lazy
   *  image's 0-height box and then jumped). Re-target the live height instead —
   *  a single transition, no jump. No-op outside the animated expand window. */
  const settleExpandHeight = () => {
    if (!expanded || !motionEnabled()) return;
    if (!area.style.height) return; // the transition already settled
    area.style.height = expandHeight() + 'px';
  };

  // `motion` is read per toggle (not captured) so the same builder serves the
  // main window and a subagent popup without a second code path.
  const setExpanded = v => {
    if (expanded === v) return;
    expanded = v;
    pill.textContent = v ? t('chat.popCollapse') : t('chat.popExpand', { n: items.length });
    if (!motionEnabled()) {
      // UI-G static window: no height transition and NO settle timer — the
      // two faces swap in one frame and the element keeps its natural height.
      // Same visible result (克制 ≠ 破功), zero periodic work added.
      area.style.height = '';
      view.style.display = v ? 'none' : '';
      expandView.classList.toggle('show', v);
      return;
    }
    const h0 = area.offsetHeight;
    view.style.display = v ? 'none' : '';
    expandView.classList.toggle('show', v);
    const h1 = v ? expandHeight() : view.offsetHeight;
    area.style.height = h0 + 'px';
    void area.offsetHeight;
    area.style.height = h1 + 'px';
    setTimeout(() => { area.style.height = ''; smartScroll(); }, 460);
  };
  pill.addEventListener('click', () => setExpanded(!expanded));

  // Drag-to-switch (pointer events; a real drag suppresses the click so a
  // swipe does not double-fire as a bring-to-front).
  const stack = { suppressClick: false };
  let sx = null, dx = 0;
  view.addEventListener('pointerdown', e => {
    sx = e.clientX; dx = 0;
    view.classList.add('dragging');
    try { view.setPointerCapture(e.pointerId); } catch { /* detached */ }
  });
  view.addEventListener('pointermove', e => {
    if (sx === null) return;
    dx = e.clientX - sx;
    view.style.transform = 'translateX(' + (dx * 0.35) + 'px)';
  });
  const up = () => {
    if (sx === null) return;
    view.classList.remove('dragging');
    view.style.transform = '';
    if (dx < -38) { front = (front + 1) % layers.length; applyLayers(); }
    else if (dx > 38) { front = (front - 1 + layers.length) % layers.length; applyLayers(); }
    if (Math.abs(dx) > 8) { stack.suppressClick = true; setTimeout(() => { stack.suppressClick = false; }, 60); }
    sx = null; dx = 0;
  };
  view.addEventListener('pointerup', up);
  view.addEventListener('pointercancel', up);

  // 触控板双指横滑 = 轻扫翻页（wheel 事件流，无按下态——与 pointer 拖动的判定
  // 窗口天然分开，不打架：拖划进行中（sx 非 null）wheel 直接让路）。方向语义与
  // 长按拖动一致：mac 自然滚动下双指向左（= 把卡向左翻）deltaX 为正 ⇒ 下一张。
  // deltaMode 归一化同叠卡引擎滚轮（鼠标 Shift+纵滚的 line/page 模式换算成 px）。
  let swipeAcc = 0;              // 横滑 deltaX 累积
  let swipeArmed = true;         // false = 动量尾流抑制中（静默满窗才重新武装）
  let swipeQuietTimer = 0;       // 事件流静默计时
  const swipeRearm = () => {
    swipeQuietTimer = 0;
    swipeArmed = true;
    swipeAcc = 0;
  };
  view.addEventListener('wheel', (e) => {
    if (sx !== null) return;                                   // 拖划进行中：让路
    const k = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 100 : 1;
    const wx = e.deltaX * k, wy = e.deltaY * k;
    const d = Math.abs(wx) >= Math.abs(wy) ? wx : (e.shiftKey ? wy : 0);
    if (!d) { swipeAcc = 0; return; }                          // 纵滚：聊天列原生滚动照旧 + 清抖动累积
    e.preventDefault();
    if (swipeQuietTimer) { clearTimeout(swipeQuietTimer); swipeQuietTimer = 0; }
    if (!swipeArmed) {                                         // 动量尾流：吞 delta，静默满窗再武装
      swipeQuietTimer = setTimeout(swipeRearm, POP_SWIPE_QUIET_MS);
      return;
    }
    swipeAcc += d;
    if (Math.abs(swipeAcc) >= POP_SWIPE_STEP_PX) {
      front = (front + (swipeAcc > 0 ? 1 : -1) + layers.length) % layers.length;
      applyLayers();
      swipeArmed = false;                                      // 本次动量窗口只翻一页
    }
    swipeQuietTimer = setTimeout(swipeRearm, POP_SWIPE_QUIET_MS);
  }, { passive: false });

  return area;
}

/** A single media item renders flat — no pill, no stack, no switching. Its box
 *  takes the media's own ratio (zcode-484 D1/D3), so a lone wide screenshot is
 *  not cropped into a portrait box. An AUDIO item has no ratio to report — the
 *  `is-audio` class retires the fallback ratio box so the flat face collapses
 *  to the controls strip (chat.css) instead of a mostly-empty 4/3 frame.
 *  三修 ② (2026-10-06 10:59 作者截图令): a click on the whole image card opens
 *  CANVAS (openMediaItemClick = the file card's single point when a path
 *  exists, the 主题三 lightbox for pathless inline bytes). The corner button
 *  stays as the explicit entry — same handler, same single point. */
function buildMediaFlat(item) {
  const box = document.createElement('div');
  box.className = 'pop-media-flat' + (item.kind === 'audio' ? ' is-audio' : '');
  box.appendChild(mediaFace(item, 'expanded', (w, h) => fitLayer(box, w, h, STACK_MAX.w, EXPAND_MAX.h)));
  box.addEventListener('click', () => openMediaItemClick(item));
  const canvasBtn = canvasCornerButton(item);
  if (canvasBtn) box.appendChild(canvasBtn);
  return box;
}

/* ─────────────── file-card face ─────────────── */

function buildFileCard(item) {
  const card = document.createElement('div');
  card.className = 'file-card';
  const icon = fileIconStyle(item.ext);
  const meta = document.createElement('div');
  meta.className = 'fc-meta';
  const name = document.createElement('div');
  name.className = 'fc-name';
  name.textContent = item.name || item.path.split('/').pop() || item.path;
  name.title = item.path || '';
  const sub = document.createElement('div');
  sub.className = 'fc-sub';
  const sizeText = humanSize(item.size);
  sub.textContent = [String(icon.label), sizeText].filter(Boolean).join(' · ');
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'fwd-btn';
  btn.setAttribute('aria-label', t('chat.popOpenCanvas'));
  btn.innerHTML = FWD_ICON + '<span class="fwd-tip">' + escapeHtml(t('chat.popOpenCanvas')) + '</span>';
  // #491 主题三: a card without an absolute path (a live user attachment whose
  // bytes ride inline, path unknown until the backend resolves it) renders but
  // opens nothing — never a bogus workspace-open-item with an empty path.
  if (item.path) {
    btn.addEventListener('click', e => {
      e.stopPropagation();
      openPopArtifact(item.path, item.name);
    });
    card.addEventListener('click', () => openPopArtifact(item.path, item.name));
  }
  const iconEl = document.createElement('div');
  iconEl.className = 'fc-icon ' + icon.cls;
  iconEl.textContent = icon.label;
  card.append(iconEl, meta, btn);
  meta.append(name, sub);
  return card;
}

/* ── 存量「已存至」兼容渲染（媒体统一适配批 2026-10-20）──────────────────
 * 社交通道入站媒体统一前的存量行是**纯文本**注入：「[图片] 已存至 <path>
 * （原始文件名 n），可用 Read 查看」（WeixinIlinkBridgePlugin.mediaLine 两种
 * 拼装——微信腿存量 + 飞书腿现役同款）。识别器把该行**换算**成媒体附件
 * （kind 标签→image/video/file，原始文件名→name，路径→path），让存量行走与
 * 新登记行**同一张媒体气泡面**（buildUserMediaRow），文本气泡随之隐去。
 * 严格匹配 = fail-open：不匹配/缺路径 ⇒ null（调用方保持原文本行渲染，零回归）。
 * 识别纯前端（后端不迁移存量行）；文件已亡 ⇒ 媒体面自身的失败占位与控制台
 * 失败脸同款（路径仍在灯箱/Canvas 可读）。 */

/** mediaLine 的严格形状（两种拼装各一例）：
 *   「[图片] 已存至 /root/m1-pic.png（原始文件名 pic.png），可用 Read 查看」
 *   「[视频] 已存至 /root/m2-clip.mp4，可用 Read 查看」 */
const LEGACY_MEDIA_LINE = /^\[(图片|视频|文件)\] 已存至 (.+?)(?:（原始文件名 (.+)）)?，可用 Read 查看$/;

/** kind 标签 → 附件 type（识别是**恢复**不是分类：kind 原样带回最富形态——
 * 新登记行的 type 判据仍是控制台管线的 image|file，两侧各守其源）。 */
const LEGACY_MEDIA_TYPE = { '图片': 'image', '视频': 'video', '文件': 'file' };

/** @returns {Array|null} 换算出的媒体附件数组；非媒体行 ⇒ null。 */
export function legacyMediaAttsOf(text) {
  if (typeof text !== 'string') return null;
  const m = LEGACY_MEDIA_LINE.exec(text.trim());
  if (!m) return null;
  const path = (m[2] || '').trim();
  if (!path || path.includes('..')) return null;
  const name = (m[3] || '').trim() || path.split('/').pop() || 'file';
  return [{ type: LEGACY_MEDIA_TYPE[m[1]] || 'file', name, path }];
}

/** Live 渲染/历史恢复共用的行级换算：附件在场 ⇒ 原样；否则试存量识别。
 *  返回 {atts, text} —— atts = 有效附件；text = 仍需文本气泡的内容
 *  （媒体接管 ⇒ ''，与控制台纯图发送同形）。 */
export function unifyUserMediaFace(text, attachments) {
  const atts = Array.isArray(attachments) ? attachments.filter(a => a && typeof a === 'object') : [];
  if (atts.length) return { atts, text: text || '' };
  const legacy = legacyMediaAttsOf(text);
  return legacy ? { atts: legacy, text: '' } : { atts, text: text || '' };
}

/* ─────────────── row assembly ─────────────── */

/* ── #491 主题三: user attachments on the Pop faces (AC-S2/S3/S4) ──────────
 * The author's 2026-10-05 12:14 ruling: user input media/files behave EXACTLY
 * like the LLM Pop faces — small file cards (Canvas open), WeChat multi-image
 * stacking, and INDEPENDENT card placement (「不是放消息的下面了，而是像LLM
 * Pop的那样是单独的卡片」). One mapping + one row builder, both sharing the
 * exact face builders above, so the two sides cannot drift apart. */

/** File-name → extension (no dot → ''). */
function extOf(name) {
  const n = String(name || '');
  const i = n.lastIndexOf('.');
  return i > 0 ? n.slice(i + 1).toLowerCase() : '';
}

/** UiMessage.User.attachment → the pop-item shape the faces consume.
 *  `resolver` (optional) replaces the ticket leg for user bytes: the uploads
 *  route is a different URL family than /api/nf-file, and byte-leg unification
 *  is explicitly a LATER batch (PLAN §4 — 登记不承诺本期).
 *  ref/taskRef attachments return null — they keep their in-bubble face. */
export function popItemFromAttachment(att, resolver) {
  if (!att || typeof att !== 'object') return null;
  if (att.type === 'ref' || att.type === 'taskRef') return null;
  const name = String(att.name || '');
  const path = typeof att.path === 'string' ? att.path : '';
  const size = Number(att.size) || undefined;
  if (att.type === 'image' || att.type === 'video') {
    return {
      kind: att.type,
      name,
      // Inline preview wins (the live send always carries one); after a cache
      // strip the uploads resolver takes over via `path`.
      src: (typeof att.preview === 'string' && att.preview.startsWith('data:')) ? att.preview : '',
      path,
      ext: extOf(name),
      size,
      resolver,
    };
  }
  if (!name && !path) return null;
  return { kind: 'file', name: name || path.split('/').pop() || 'file', path, ext: extOf(name), size };
}

/** The INDEPENDENT media row for one user message's non-ref attachments — the
 *  exact same row shape a Pop payload builds (`.row.pop-artifact` + faces), so
 *  「与 LLM Pop 卡片同层同形」 holds by construction. The `user-media` marker
 *  class is the QA/spec hook. Returns null when nothing displayable remains
 *  (refs-only messages keep the plain user row). */
export function buildUserMediaRow(attachments, opts) {
  const resolver = opts && typeof opts.resolver === 'function' ? opts.resolver : undefined;
  const items = (attachments || [])
    .map(a => popItemFromAttachment(a, resolver))
    .filter(Boolean);
  if (!items.length) return null;
  const media = items.filter(it => it.kind === 'image' || it.kind === 'video');
  const files = items.filter(it => it.kind !== 'image' && it.kind !== 'video');
  if (!media.length && !files.length) return null;
  const row = document.createElement('div');
  // attach-side-align (2026-10-06 作者截图令): the row carries the SAME
  // sender-side class as the user bubble row (chat.js renderUserBubble's
  // 'row user'), so .row.user's positioning owns the side — no second
  // alignment system.
  row.className = 'row pop-artifact user user-media';
  // quote-message 一期（spec §2.1/§2.5）：媒体/文件行可引用（仅引用项，无正文
  // 可复制）。多条 stack 摘要取首项（引用对象 = 行）。
  row.dataset.msgId = nextChatMsgId();
  {
    const first = items[0] || {};
    const d = first.kind === 'image' || first.kind === 'video'
      ? { kind: 'image', fileName: first.name, path: first.path }
      : { kind: 'file', fileName: first.name || (first.path || '').split('/').pop(), path: first.path };
    attachQuoteMenu(row, {
      ...d, senderRole: 'user', senderName: t('chat.quoteSenderMe'), ts: Date.now(),
    });
  }
  const group = document.createElement('div');
  group.className = 'pop-group';
  row.appendChild(group);
  if (media.length === 1) group.appendChild(buildMediaFlat(media[0]));
  else if (media.length > 1) group.appendChild(buildMediaStack(media));
  files.forEach(f => group.appendChild(buildFileCard(f)));
  return row;
}

/* ── imageref-media（2026-10-09 修复批）：图片文件引用的消息内面 ─────────────
 * 症状（用户 2026-10-09 07:45 现场报）：「引用文件」入口（explorer 右键 引用 /
 * explorer 行拖拽 / canvas 引用——#303 P0 40c199bd1 归一）把一切文件引用成
 * refType:'file'，消息内引用面（reference.renderMessageRef）对文件引用只出
 * att-ref-card（文档图标 + 文件名 + IMG 角标[typeLabel] + 截断路径[source 行]）
 * ——图片引用没有媒体预览分支。本构建器给图片引用**同一张 Pop 媒体预览卡**
 * （buildMediaFlat 复用，零第二套面）：ref.source.path 是工作区绝对路径，字节走
 * hydrateMedia 的 nf-file 票腿（不传 resolver ⇒ 不走 uploads 腿，与 explorer /
 * Canvas 读工作区图片同一路由）；整面点击 = openMediaItemClick → openPopArtifact
 * 单点开 Canvas（ref 恒带 path，与引用跳转 jumpRef 同一动作族）。引用身份保留在
 * dataset（refType/refPath）+ .att-ref-media 标记类（spec 钩）。非图片引用不进
 * 此分支——文件卡只给非图片。 */
export function buildImageRefMediaFace(ref) {
  const src = (ref && ref.source) || {};
  const name = String(src.fileName || src.title || '');
  const path = typeof src.path === 'string' ? src.path : '';
  const item = { kind: 'image', name, path, ext: extOf(name) };
  const face = buildMediaFlat(item);
  face.classList.add('att-ref-media');
  face.dataset.refType = 'file';
  face.dataset.refPath = path;
  return face;
}

/* ── attachmentsSaved 原位补丁（用户附件点击统一批 2026-10-08）──────────────
 * live 发送腿的行渲染早于后端落盘：item 只有 data/preview、没有 `path`，于是
 * 每张脸都在「作者刚发完就看的那一条」上降级（媒体整面点击走 pathless 灯箱、
 * 文件卡点击是死的三修 ②/#491 形态）。后端在 ui.json 落盘后回传
 * `attachmentsSaved`（WebSocketRoutes.scala，载荷 = 与 ui.json 同形的
 * [{name,type,path}]），本模块——行的唯一属主——用**同一套 builders** 原位
 * 重渲该行：补丁后的行与历史恢复腿、与 Pop 行逐字节同构，打开动作仍是
 * openPopArtifact 单点，零第二套开法。 */

/** sessionId → [{row, atts}] FIFO（上限 8）：等待落盘路径的 live 行。上限只是
 *  防御性保鲜——正常流里帧在发送后毫秒级到达即消费掉条目；帧丢失（旧后端）时
 *  条目随上限滚动，且 att 对象本就被在 DOM 的行闭包持有，登记不放大保留面。 */
const pendingUserMediaRows = new Map();
const PENDING_MEDIA_ROWS_CAP = 8;

/** Live 渲染登记钩子（chat.js renderUserBubble 唯一调用方）。 */
export function registerUserMediaRow(sessionId, row, atts) {
  if (!sessionId || !row || !Array.isArray(atts) || !atts.length) return;
  const list = pendingUserMediaRows.get(sessionId) || [];
  list.push({ row, atts });
  if (list.length > PENDING_MEDIA_ROWS_CAP) list.splice(0, list.length - PENDING_MEDIA_ROWS_CAP);
  pendingUserMediaRows.set(sessionId, list);
}

/** `attachmentsSaved` 消费端：`saved` 与行的 atts 顺序严格一致（同一条非 ref
 *  线上列表，index 对 index），name 逐位对表做错位防御；path 为 null（保存失败/
 *  未解析）的项保持原样——那张脸维持 pathless 现状，绝不派假 open。 */
export function patchUserMediaRow(sessionId, saved) {
  const list = pendingUserMediaRows.get(sessionId);
  if (!list || !list.length || !Array.isArray(saved) || !saved.length) return;
  const i = list.findIndex(e =>
    e.atts.length === saved.length &&
    saved.every((s, k) => s && e.atts[k] && s.name === e.atts[k].name));
  if (i < 0) return;
  const entry = list.splice(i, 1)[0];
  saved.forEach((s, k) => {
    if (s && typeof s.path === 'string' && s.path) entry.atts[k].path = s.path;
  });
  const fresh = buildUserMediaRow(entry.atts);
  if (fresh && entry.row.isConnected) entry.row.replaceWith(fresh);
}

/**
 * Build the artifact row for one Pop payload: media group (stack or flat) on
 * top, file cards under it, the failed-paths chip last. Returns null when the
 * payload carries no displayable item (callers keep the plain tool row).
 */
/** The payload kinds that ride the MEDIA face (stack / flat); everything else
 *  is a file card. canvas-media Wave2 adds `audio` beside image/video. */
const MEDIA_KINDS = new Set(['image', 'video', 'audio']);

export function buildPopArtifactRow(payload) {
  const items = (payload.items || []).filter(it => it && typeof it.path === 'string');
  if (!items.length) return null;
  const media = items.filter(it => MEDIA_KINDS.has(it.kind));
  const files = items.filter(it => !MEDIA_KINDS.has(it.kind));

  const row = document.createElement('div');
  row.className = 'row pop-artifact';
  const group = document.createElement('div');
  group.className = 'pop-group';
  row.appendChild(group);

  if (media.length === 1) group.appendChild(buildMediaFlat(media[0]));
  else if (media.length > 1) group.appendChild(buildMediaStack(media));
  files.forEach(f => group.appendChild(buildFileCard(f)));

  const failed = payload.fileRefs && Number(payload.fileRefs.failed) > 0;
  if (failed) {
    const warn = document.createElement('div');
    warn.className = 'pop-warn';
    const reasons = (payload.warnings || [])
      .map(w => (w && (w.ref || '')).split('/').pop())
      .filter(Boolean)
      .slice(0, 3)
      .join(', ');
    warn.textContent = t('chat.popFailed', { n: Number(payload.fileRefs.failed) }) + (reasons ? ' — ' + reasons : '');
    group.appendChild(warn);
  }
  return row;
}

/**
 * #491 placement note (2026-10-05 author ruling 「Pop的文件和媒体，要按时间顺序
 * 显示，不要总是在文字的最下方。而是像微信一样，按时间顺序显示」): the artifact
 * row is appended IN PLACE by its render path — `row.after(artifactRow)` in
 * chat.js renderTool, plain doc-order appends in persistence.js's two replay
 * legs. The UI-D turn-end re-anchor that used to live here
 * (placeArtifactRowsAtTurnEnd + the `_artifactsSeen` latch + the
 * `artifactsSeen()` telemetry hook) is physically removed: its semantic was
 * superseded two days after it shipped, and a dead switch would only grow
 * maintenance surface. The retained R8 invariants are structural and do not
 * need this pass: the row is `.row.pop-artifact` (never `.row.tool`), so
 * turnGroup's tuck set cannot swallow it.
 */

/**
 * The Pop tool's PROCESS row face (spinner → check, like every tool) — plain
 * icon + label, no body, no rainbow-name click-to-open (opening moved to the
 * file cards' forward buttons).
 */
export function renderPopToolRow(card, label, summary, isError) {
  const icon = isError
    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const parts = String(label || '').split('\n', 2);
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + escapeHtml(parts[0] || 'Pop') + ' &mdash; ' +
    escapeHtml(summary || '') + '</div></div>';
}

/**
 * LEGACY Pop row face (pre-pop-upgrade histories): rainbow filename inline in
 * the label, whole card clickable to re-open in Canvas. Kept byte-compatible
 * with the shipped renderer (moved verbatim out of chat.js, where it lived
 * since the imgfix batch) so old sessions replay unchanged — new Pop calls
 * always carry the payload face above instead.
 * Shared between live renderTool and history restore. Returns true if the
 * card was handled (Pop tool), false otherwise.
 */
export function applyLegacyPopCard(card, label, summary, inputJson, isError) {
  const _toolName = label ? label.split('(')[0].split('\n')[0].trim() : '';
  if (_toolName !== 'Pop' || !inputJson) return false;

  const icon = isError
    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const art = popArtifactFromInput(label, inputJson);
  const popFilePath = art ? art.filePath : '';
  const popTitle = art ? art.title : '';
  const popFileName = popFilePath.split('/').pop() || popFilePath;
  const popLocalLabel = localizeToolLabel(label);
  const popLocalSummary = localizeToolSummary(summary, label);
  const popLabelParts = popLocalLabel.split('\n', 2);
  const rainbowName = '<span class="pop-rainbow-name">' + escapeHtml(popTitle || popFileName) + '</span>';
  const summaryHtml = escapeHtml(popLocalSummary).replace(escapeHtml(popFileName), rainbowName);
  const labelHtml = escapeHtml(popLabelParts[0]) + ' &mdash; ' + summaryHtml
    + (popLabelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(popLabelParts[1]) + '</span>' : '');
  card.classList.add('pop-tool-card');
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div></div>';
  if (popFilePath) {
    card.addEventListener('click', () => openPopArtifact(popFilePath, popTitle));
  }
  return true;
}
