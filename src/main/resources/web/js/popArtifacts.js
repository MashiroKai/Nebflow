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
//     render as ONE WeChat-style fan stack (2026-10-30 作者令: fixed 220×280
//     card face for every entry, `object-fit: cover`; back layers fan out
//     poker-style with slight rotate + offset, not a plain overlap): front card
//     + fanning layers to the right, the 「展开 N」 pill beside the stack on the
//     sender side (user rows: left; LLM rows: right — 三修 ①, 2026-10-06); drag
//     switches; a click
//     on the collapsed stack opens Canvas with the SHOWN (front) card — swipe
//     to the Nth card and the click opens N (复报令 2026-10-06 19:43 A-1;
//     三修 ③'s first-item face is the no-swipe case), and an
//     expanded row's whole-face click opens Canvas with THAT item (复验令
//     2026-10-06 16:47 — expanded was the last lightbox leg, and the collapsed
//     binding itself was dead on real clicks, see buildMediaStack); expand
//     flattens to one card per row at each media's OWN ratio (「收起」 re-stacks;
//     展开态不受扇形影响). A single media item
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
// imgdoor 二次加载批（2026-10-24）：图片脸字节进 nfFileCache 账本——预览取数即入账，
// Canvas 打开账本命中零取数（预览 + 打开 = 恰一次网络抓取）。账本模块只 import
// state/branding，本边不成环。nfViewerSource = 取字节单点（手机端长按退役批的
// footer 下载键同腿复用，零新增取字节路）。
import { nfViewerUrl, nfCacheDrop, nfViewerSource } from './nfFileCache.js';
// quote-message 一期（2026-10-09）：用户媒体/文件卡行挂消息 id + 右键「引用」。
// 摘要 = [图片]/[文件] 文件名（spec §2.5 表）。挂点在本 builder ⇒ live 发送、
// 历史恢复（persistence.buildUserAttachmentRows）、attachmentsSaved 原位重渲
// （patchUserMediaRow 走 replaceWith ⇒ 重建后仍带 id/菜单）三路同构。
import { attachQuoteMenu, nextChatMsgId, createMsgQuoteButton } from './msgQuote.js';
// footer 信息条族（手机端长按退役批 2026-10-31 作者令）：媒体行 footer 与文本
// 消息底部信息条同族徽章（时间 + 常用操作），durationBadges 是唯一 builder。
import { createMsgFooterBadge } from './chat/durationBadges.js';
// Pop 交付卡 footer 批（作者令 2026-11-01 16:19）：行引用描述符的 senderName
// 与 AI 文本行同源（rootName.js 叶子模块，零环风险）。
import { rootDisplayName } from './rootName.js';
// 叠卡封面内容感知取景批（作者令 2026-11-01 21:40 ①）：收拢卡脸 cover 裁剪
// 改显著区域滑窗择优（熵/方差/边缘密度 + 截图类顶部偏置，按 src 缓存，失败
// 回落几何中心）。叶子模块（零 import）不成环；只挂叠卡收拢层，平铺/展开行
// （contain 真实比）与视频脸零感知。
import { applySmartCrop } from './smartCrop.js';
// 视频内容封面（video-cover 批 2026-11-05）：视频面在出帧前不许是黑块/灰块。
// 叶子模块（只 import dom.js）⇒ 零环。
import { attachVideoCover } from './videoCover.js';

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

/** Icon glyph + color family for a file-card icon (by extension). Exported for
 *  the att-ref-card 语言统一批 (2026-10-26): the user-side ref card reuses the
 *  SAME icon spec — one family, zero second source.
 *
 *  引用牌图标化批（2026-11-02）：表体抽到叶子模块 `fileIcon.js`（quoteLogo.js 也要
 *  用同一张表，住这里会把既有三环扩成四环）；本文件 **re-export** 保持既有 import
 *  点（attachUpload.js / reference.js / chat.js 等）逐字不动、单一来源不变。 */
export { fileIconStyle, extOf } from './fileIcon.js';
// 本文件内部消费者（buildFileCard / popItemFromAttachment / buildImageRefMediaFace）
// 也要这两个符号：`export ... from` 只做转发、不建本地绑定 ⇒ 另起一条具名 import
// （同一模块、同一份实现，零第二套表）。
import { fileIconStyle, extOf } from './fileIcon.js';

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
/** 字节水合腿（导出：附件批 2026-10-31 —— msgQuote 引用块缩略带动态 import 复用
 *  本函数，与 Pop 脸同一取字节链；静态 import 会成环，故走动态导入）。 */
export function hydrateMedia(el, item, onFail) {
  if (item.src) {
    el.src = item.src;
    return;
  }
  // dry 腿（fm-att-card-unify 批 2026-11-02）：`item.dry` = 面先出、字节由调用方自填
  // （社交三窗的鉴权 id 路由 / 设备字节源都是异步腿，拿不到同步 URL）——本函数对
  // dry 面**零动作**（不挂 error 监听、不进任何取字节路），调用方随后直填 `el.src`；
  // load 事件照常驱动 `natural` 上报（fitLayer 出真比）。主会话链路零命中本分支。
  if (item.dry) return;
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
      // imgdoor 二次加载批（2026-10-24）：账本腿（blob: URL）解码失败 = 手上这份
      // 缓存字节坏了 ⇒ 先把该键逐出账本再走既有 reMint 直取链——自愈语义与票腿
      // 同族，坏字节不粘死账本（否则 TTL 内每次渲染都命中同一份坏字节）。
      const remedy = el.src.startsWith('blob:')
        ? nfCacheDrop('nf:' + path).then(() => reMint(path))
        : reMint(path);
      Promise.resolve(remedy)
        .then(url => { if (el.isConnected) el.src = url; })
        .catch(() => fail());
    });
    // 互斥分派（恰一腿）：图片 = 账本腿；视频/音频 = 既有票腿原样。
    // 既有票腿（视频/音频恒走这里；图片只在账本腿失败时回落）：ticketUrl ⇒ 裸 src
    // 直取，error ⇒ 上面的 reMint 自愈链。逐字保留。
    if (el.tagName !== 'IMG') {
      legacyTicketFace();
      return;
    }

    // imgdoor 二次加载批（2026-10-24 · 作者报障「pop 的图片预览会加载一次，点击在
    // canvas 打开好像又会加载一次」）：图片脸字节改经 **nfFileCache 账本**
    // （`nfViewerUrl` 单点：`nf:<path>` 命中 = blob URL 直显；未命中 = 既有票据链
    // 取数并 `nfCachePut` 入账）。修前形态：预览 = 裸 `<img src=票据URL>` 直取，
    // 字节不进账本 ⇒ Canvas 打开按「账本未命中」把同一件再全量下载一次（取数腿
    // `cache:'no-store'`，HTTP 缓存帮不上）——同图两次网络抓取。修后：预览取数即
    // 入账 ⇒ Canvas 打开（image viewer 同走 `nfViewerUrl`）命中零取数，预览 +
    // 打开 = 恰一次抓取；内容失效仍由既有事件失效/TTL/列目录对账三腿把关，灯箱
    // 复用同一 hydrateMedia ⇒ 同样零追加抓取。铸票/取数失败（nfViewerUrl 拒绝）
    // ⇒ 原样回落 legacyTicketFace，失败面与改前逐字一致。视频/音频不进账本
    // （Range/流式播放语义，非本批症状面）。
    nfViewerUrl({ absPath: path, objectUrl: null, pane: el })
      .then(url => { if (el.isConnected && !el.dataset.nfReminted) el.src = url; })
      .catch(() => { if (!el.dataset.nfReminted) legacyTicketFace(); });

    function legacyTicketFace() {
      ticketUrl(path)
        .then(url => { if (el.isConnected) el.src = url; })
        .catch(() => fail());
    }
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

/** 失败面单点（fm-att-component-lib 批 2026-11-02 导出）：字节终态失败的媒体元素
 *  落**主会话同款**失败占位（`.nf-img-failed` 虚线盒 + 纯文本注记行）——dry 腿宿主
 *  （社交三窗叠卡层等）与 hydrateMedia 内部失败腿同一张失败脸，禁第二套失败样式。
 *  `noteHost` = 注记行宿主（叠卡层 = `.media-layer`；缺省 = 元素父节点）；
 *  `noteText` = 注记文案覆写（缺省 = 既有「加载失败」键——如叠卡成员 410 过期传
 *  「附件已过期」，同一结构、准确语义）。 */
export function markMediaFailed(el, noteHost, noteText) {
  if (!el) return;
  if (el.dataset && el.dataset.nfMediaFailed === '1') return;
  el.classList.add('nf-img-failed');
  if (el.dataset) el.dataset.nfMediaFailed = '1';
  const host = noteHost || el.parentElement;
  if (!host || host.querySelector('.nf-media-failed-note')) return;
  const note = document.createElement('div');
  note.className = 'nf-media-failed-note';
  const name = (el && (el.alt || el.dataset.nfName)) || '';
  const label = noteText || t('chat.popMediaFailed');
  // The name is appended here (not interpolated) so the locale string stays a
  // plain sentence and an item with no name shows no dangling separator.
  note.textContent = name ? label + ' — ' + name : label;
  host.appendChild(note);
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
    // video-cover 批（2026-11-05）：播放面/叠卡面/展开面**同一处**挂内容封面
    // （本函数是视频面的唯一出口 ⇒ 三个宿主零第二套实现）。封面插在 video 之后、
    // ring 之前：DOM 层序即视觉层序（播放键与时长仍在封面上方）。
    // 懒加载/失败降级/跨域判定全在 videoCover.js；这里只负责「挂」。
    // UI-G 闸（与本文件 883/896 行的 motionEnabled 同一条口径）：子代理/流程弹窗
    // = 静态窗，零额外工作 —— 抓帧是一次 seek 解码 + 一条 seek 守卫定时器，属装饰性
    // 工作，静态窗一律不挂（代价：静态窗的视频面仍是现状面，已在本批报告申报）。
    if (motionEnabled()) attachVideoCover(video, { alt: item.name || '' });
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

/** The card language's ONE ratio arithmetic — pure packing of `w×h` into
 *  `maxW×maxH`, never upscaled (fm-att-card-unify 批 2026-11-02 导出：社交三窗的
 *  读数面复用同一份算术，禁第二套比例实现。`fitLayer` 是唯一行为消费方）。 */
export function fitBoxNum(w, h, maxW, maxH) {
  const scale = Math.min(maxW / w, maxH / h, 1);
  return { w: Math.round(w * scale), h: +(h * scale).toFixed(2) };
}
/** The flat single's packing, bound to the card language's own allowances
 *  (STACK_MAX.w × EXPAND_MAX.h) — the shape a `.pop-media-flat` takes once its
 *  bytes report. Exported for the social windows' reading face. */
export function fitMediaBox(w, h) {
  return fitBoxNum(w, h, STACK_MAX.w, EXPAND_MAX.h);
}

/** Give `layer` the media's OWN aspect ratio, scaled to fit `maxW`×`maxH`. The
 *  box ratio then equals the media ratio exactly, so the un-cropped
 *  `object-fit: contain` face is flush — no letterbox bars, no crop. Called
 *  again whenever a face reports its intrinsic size, which is why every box has
 *  a definite size even before its bytes are in (the CSS fallback ratio holds
 *  until then). */
function fitLayer(layer, w, h, maxW, maxH) {
  const box = fitBoxNum(w, h, maxW, maxH);
  layer.style.width = box.w + 'px';
  layer.style.aspectRatio = w + ' / ' + h;
  layer.dataset.nfRatio = w + 'x' + h;
}
/** The flat single's box allowance (its width cap; its height cap pairs with
 *  the expanded rows' EXPAND_MAX). The 2026-10-30 fan order retired the
 *  collapsed stack's per-media fit — the stack is a fixed 220×280 face in
 *  chat.css — so this no longer governs the stack, only buildMediaFlat. */
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
  // 预览层收编批（ui-unification-20261103-askuser-attach 2026-11-04）：本层与
  // css/lightbox.js 的看图灯箱**同名** `.nf-lightbox`，两份基座声明撞车 ——
  // lightbox.css 后载（index.html:80 > chat.css:53）⇒ 它的 `display:none` 压过本层
  // 的 `display:flex`，实测「点卡片放大毫无反应」的机制根因（作者报障「点击没反应」
  // 同族）。修法：本层挂**差异档修饰类** `nf-lightbox--media` + `on`（= 可视态档，
  // 两灯箱同一套开合词法），基座形仍归 chat.css 的 `.nf-lightbox.nf-lightbox--media`
  // 一档；看图灯箱（lightbox.js，`.on` 显隐）逐字不变。
  overlay.className = 'nf-lightbox nf-lightbox--media on';
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
 *  zoom: same face that case had before this ruling, no bogus open dispatch.
 *  （导出：附件批 2026-10-31 —— msgQuote 引用块缩略点击同款单点，动态 import
 *    复用；静态 import 会成环。） */
export function openMediaItemClick(item) {
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

/** The stacked media group (≥2 items), 2026-10-30 fan design (作者令 12:47
 *  「参考微信的叠卡…强制放缩所有尺寸的卡片到一个大小…像扑克一样轻微旋转
 *  展开 而不是重叠」): the collapsed stack is ONE fixed 220×280 card face —
 *  every entry (any image/video ratio) is force-fit onto it via the
 *  stack-scoped `object-fit: cover`; the back layers fan out poker-style
 *  (translateX + slight rotate, pure CSS transforms — chat.css). The pill rides
 *  the conversation CENTER-AXIS side of the stack, per sender (user rows left /
 *  LLM rows right, 三修 ①). A pointer drag of >38px goes next / previous; a
 *  trackpad two-finger horizontal swipe (wheel deltaX, 2026-10-22 作者令) goes
 *  next / previous too — threshold + momentum-window suppression, coexisting
 *  with the drag (separate decision windows); 「展开 N」 flattens (each row back
 *  to its OWN ratio + contain, 展开态不受扇形影响), 「收起」 re-stacks. A click on
 *  the COLLAPSED stack opens Canvas with the SHOWN (front) card — swipe to the
 *  Nth card and the click follows the swipe (复报令 2026-10-06 19:43 A-1); the
 *  first card before any swipe (三修 ③). An EXPANDED row's whole-face click opens
 *  Canvas with THAT row's item (复验令 16:47) — the lightbox survives only as
 *  openMediaItemClick's pathless-inline fallback. ≤3 layers all show; a 4th+
 *  item is-buried into the 「展开 N」 (zcode-484 D2).
 *
 *  **标准组件 API**（fm-att-component-lib 批 2026-11-02 · 作者令「把主会话用的组件
 *  直接做成标准组件库让各面板调用」）：叠卡媒体组与 flat/file 卡同属卡语言组件库，
 *  数据参数化入、DOM 出；缺省 opts = 主会话行为**逐字不变**。
 *   · `opts.onOpen(item, index)`：开件动作（折叠态整面点击 = 当前 front 张，index =
 *     翻页序；展开态整行点击 = 该行）。缺省 = `openMediaItemClick`（Canvas/灯箱
 *     单点）；传自定义 fn = 宿主面板自管（如社交三窗的鉴权预览腿）——**只换动作，
 *     不换形态**（禁宿主再包第二层卡 DOM）。
 *   · `opts.cornerFor(item, index)`：展开行角标元素工厂。缺省 = `canvasCornerButton`
 *     （Canvas 次级入口）；返回 null/undefined = 该行不挂角标。
 *   · `area.popFaces`：字节填充句柄（dry 腿宿主用）——`[{item, index, els}]`，
 *     `els` = 该件的全部媒体元素（折叠层 + 展开层各一枚，文档序），调用方按序
 *     直填 `el.src`（`item.dry` 约定不变，见 hydrateMedia 头注）。
 *   · `opts.quoteDescFor(item, index)`：该张的引用描述符工厂（单张引用批 2026-11-02）。
 *     产物不挂在面上，只经 `area.popQuoteState()` 暴露（见下）；缺席 ⇒ null。
 *   · `area.popQuoteState()`：`{index, total, desc}` —— **当前张 = front**（拖动/横滑
 *     翻到的那张 = 折叠面显示卡 = 整面点击打开的卡）+ 该张描述符。footer 引用入口
 *     （msgQuote.initQuoteItemButton）消费本接驳点；面上零引用钮（作者令 2026-11-02
 *     11:00「图片引用在footer显示就行了。不需要hover图片的时候也显示一个图标」）。
 *
 *  🔴 **「展开 N」钮的位置不是 API 参数位**（fm-stack-pill-align 批 2026-11-02 ·
 *  作者令「把 buildMediaStack 的展开钮默认位改回与主会话逐字同款（同角、同边距、
 *  同形态），fm 调用点不许传位置类 opts 覆盖」）：钮的**默认位 = 主会话逐字同款**
 *  —— 组件默认 DOM 序 [pill, view] 即「钮在栈卡**朝会话中轴**一侧、垂直居中」，
 *  侧别镜像（用户侧/出向 = 钮在左 + margin-right 32px + 扇形朝左；LLM 侧/入向 =
 *  `order:3` + 钮在右 + margin-left 32px + 扇形朝右）与 220×280 卡面几何**全部**
 *  在 chat.css 单源（`.pop-stack-area` / `.row.pop-artifact.user` /
 *  `.row.pop-artifact:not(.user)` 族），宿主面板只做**同值镜像**（friends.css 的
 *  `.fm-msg.out/.in` 族）。⇒ 本组件的 opts **不得**长出位置类键（如 pillSide /
 *  钮角位 / 边距覆盖）——面板「钮位置不对」的修法是修宿主镜像或组件默认，绝不
 *  在调用点传覆盖参数（那会重新长出第二套几何算术，正是本批要挡的漂移形态）。
 *  fm 三窗（好友/群聊/设备）的调用点 = `messages.js::buildMediaStackGroup`，只传
 *  `onOpen` + `cornerFor`（动作 + 角标），零位置类键。 */
function buildMediaStack(items, opts) {
  const openItem = (opts && typeof opts.onOpen === 'function') ? opts.onOpen : openMediaItemClick;
  const cornerFor = (opts && typeof opts.cornerFor === 'function') ? opts.cornerFor : null;
  // 引用入口移入 footer 批（2026-11-02 11:00 作者令「图片引用在footer显示就行了。
  // 不需要hover图片的时候也显示一个图标」）：叠卡面上**不再长**任何引用钮，改为把
  // 「当前张 + 该张引用描述符」暴露成接驳点 `area.popQuoteState()`（见下方），
  // footer 入口（msgQuote.initQuoteItemButton）消费 —— 面上零按钮，引用入口与
  // 文本/文件行同族（composer footer 动作行）。**只用动作位，不长相位置键**
  // （与 onOpen/cornerFor 同一纪律，见头注「展开 N 钮位置不是 API 参数位」）。
  const quoteDescFor = (opts && typeof opts.quoteDescFor === 'function') ? opts.quoteDescFor : null;
  // 自定义载荷（expando）面：lib.dom 无这些属性 —— 同一处声明类型，两个消费者
  // （popFaces 字节填充 / popQuoteState 引用接驳点）读同一份形状。
  const area = /** @type {HTMLDivElement & {
   *   popFaces?: Array<{item: any, index: number, els: Element[]}>,
   *   popQuoteState?: () => {index: number, total: number, desc: any},
   *   popExpanded?: () => boolean,
   * }} */ (document.createElement('div'));
  // UI 统一批（ui-unification-20261103-canvas-overlay）：形唯一家 = design-system.css
  // ⑨ .ds-media-stack 族；旧类保留为 DOM 寻址钩子（msgQuote/messages/smartCrop/
  // friends.css 镜像与既有 spec 全兼容）。
  area.className = 'pop-stack-area ds-media-stack';
  const pill = document.createElement('button');
  pill.type = 'button';
  pill.className = 'pill ds-media-stack__pill';
  pill.textContent = t('chat.popExpand', { n: items.length });
  const view = document.createElement('div');
  view.className = 'pop-stack-view ds-media-stack__view';
  const expandView = document.createElement('div');
  expandView.className = 'pop-expand-view ds-media-stack__expand';
  area.append(pill, view, expandView);

  /** 展开行宽度上限 = **本节点折叠卡脸宽度**（卡面尺度单源；宿主可把卡面收小）：
   *  主会话/好友窗卡脸 220 ⇒ min(220, EXPAND_MAX.w) = EXPAND_MAX.w，逐字不变；
   *  宿主收小卡面（引用牌 96 档，chat.css「引用牌 · 多图叠卡」）时展开行跟着收，
   *  不横向顶出宿主。读 view.offsetWidth（尚未布局时回落 EXPAND_MAX.w）。
   *  两条写入腿（load 回调的首次 fit / 展开动作前的 refit）**同一取值口**——
   *  只收在一条腿上会被另一条覆盖（实测：load 晚于展开时旧值 220 反写回来）。 */
  let faceWSeen = 0;   // 最近一次**可见**的卡脸宽度（展开后 view 被 display:none，offsetWidth 为 0）
  const expandMaxW = () => {
    const w = view.offsetWidth;
    if (w > 0) faceWSeen = w;
    return Math.min(faceWSeen || EXPAND_MAX.w, EXPAND_MAX.w);
  };

  let front = 0;
  let expanded = false;
  // 2026-10-30 fan order: the collapsed stack is ONE fixed 220×280 card face
  // (chat.css .pop-stack-view) with the stack-scoped `object-fit: cover` crop —
  // every entry, whatever its own ratio, is force-fit onto that face. No
  // fitLayer drives the stack box anymore; a face's natural ratio is only
  // consumed by its EXPANDED row (own-ratio fit, contain — 展开态不受扇形影响).
  const layers = items.map((item, i) => {
    const layer = document.createElement('div');
    layer.className = 'media-layer ds-media-card';
    layer.appendChild(mediaFace(item, 'stack', (w, h) => {
      fitLayer(expandLayers[i], w, h, EXPAND_MAX.w, EXPAND_MAX.h);
      if (expanded) settleExpandHeight();
      // 内容感知取景（作者令 2026-11-01 21:40 ①）：图片脸 load 报告点即取景点
      // —— 卡脸实测同比喂给滑窗择优 object-position（首报时盒子可能未布局，
      // 兜底 220/280 设计比）；视频脸无 img，零感知。中心回落与缓存都在
      // applySmartCrop 内（跨域污染/解码失败 = 不写内联样式 = 原几何中心）。
      const face = /** @type {HTMLImageElement|null} */ (layer.querySelector('img.pop-img'));
      if (face) {
        const box = view.getBoundingClientRect();
        applySmartCrop(face, box.height > 4 ? box.width / box.height : 220 / 280);
      }
    }));
    view.appendChild(layer);
    return layer;
  });
  const expandLayers = items.map((item, i) => {
    const layer = document.createElement('div');
    layer.className = 'media-layer ds-media-card';
    layer.appendChild(mediaFace(item, 'expanded', (w, h) => {
      fitLayer(layer, w, h, expandMaxW(), EXPAND_MAX.h);
      if (expanded) settleExpandHeight();
    }));
    // 复验令 (2026-10-06 16:47 作者核查令; amends the 三修-era "expanded keeps
    // its zoom click" reading): an expanded row's WHOLE-face click opens CANVAS
    // with ITS item — the same single point as every other face. The expand
    // view has no pointer-capture drag leg, so a real click does reach this
    // layer (unlike the collapsed view — see the view-level binding below).
    layer.addEventListener('click', () => openItem(item, i));
    const canvasBtn = cornerFor ? cornerFor(item, i) : canvasCornerButton(item);
    if (canvasBtn) layer.appendChild(canvasBtn);
    // 叠卡面上零引用钮（引用入口移入 footer 批 2026-11-02 11:00 作者令）：单张
    // 引用改由 footer 入口按 `area.popQuoteState()` 取「当前张」——手势仲裁面
    // 因此更窄：拖动翻页/双指横滑仍是折叠盒唯一的两条手势，展开列只剩整面点击
    // （= 打开该张）与 Canvas 角标。
    // 展开后每个媒体项拥有自己的 footer 引用入口；描述符仅包含本项，
    // 身份仍由 startItemQuote 从所属行统一解析。
    const itemQuote = quoteDescFor ? quoteDescFor(item, i) : null;
    if (itemQuote) layer.appendChild(createMsgQuoteButton(itemQuote));
    expandView.appendChild(layer);
    return layer;
  });
  // 字节填充句柄（dry 腿宿主，见头注）：每件 = 折叠层 + 展开层两枚媒体元素。
  const faceElOf = (layer) => layer.querySelector('img, video, audio');
  area.popFaces = items.map((item, i) => ({
    item, index: i,
    els: [faceElOf(layers[i]), faceElOf(expandLayers[i])].filter(Boolean),
  }));

  // zcode-484 D2: only the front card and its two peeking neighbours are
  // painted. Every item past the third used to land on `layer-back` at the same
  // z-index as the third, so 4+ items overlapped indistinguishably (and clicks
  // could hit a buried card). The parked items are `display:none`.
  const VISIBLE_LAYERS = 3;
  // 翻页播报（冒泡）：拖动 / 双指横滑换「当前张」时通知宿主，footer 引用入口据此
  // 即时更新文案（不吃缓存）。零第二套状态 —— 每次 applyLayers（= front 变更的
  // 唯一落点）都发，宿主侧幂等去重。
  const announceFront = () => {
    area.dispatchEvent(new CustomEvent('nf-quote-front', { bubbles: true, detail: { index: front } }));
  };
  const applyLayers = () => {
    layers.forEach((layer, i) => {
      const p = (i - front + layers.length) % layers.length;
      layer.classList.remove('layer-front', 'layer-mid', 'layer-back');
      layer.classList.add(['layer-front', 'layer-mid', 'layer-back'][Math.min(p, 2)]);
      layer.style.zIndex = String(3 - Math.min(p, 2));
      layer.classList.toggle('is-buried', p > VISIBLE_LAYERS - 1);
    });
    announceFront();
  };
  applyLayers();

  // 引用接驳点（引用入口移入 footer 批 2026-11-02 11:00 作者令）：footer 入口问
  // 「这一组此刻是第几张、它的引用描述符是什么」——**当前张 = `front`**，即拖动
  // 翻页 / 双指横滑翻到的那一张（与折叠面显示卡、整面点击打开的卡同一序 ⇒ 零
  // 第二套「当前」定义）。`desc` 由宿主传入的 `quoteDescFor(index)` 产出，缺席
  // （旧宿主 / fm 三窗）⇒ null，footer 入口据此隐藏（零死入口）。
  area.popQuoteState = () => ({
    index: front,
    total: items.length,
    desc: quoteDescFor ? quoteDescFor(items[front], front) : null,
  });
  // Footer 引用粒度跟随可见形态：折叠时宿主引用整组，展开后逐行引用。
  area.popExpanded = () => expanded;

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
    openItem(items[front], front);
  });

  // 键盘腿（引用多图叠卡批 2026-11-05 · 作者令「桌面要能点击/键盘切换」）：
  // 折叠卡脸可聚焦，聚焦时 ArrowLeft/ArrowRight（纵向箭头同义）翻页、
  // Home/End 首末张、Enter/Space 开当前张。键位与 CLI 叠卡 deck 的 next/prev
  // 同族（keyRules.js deck.next/prev），而 deck 的 Arrow* 绑定有 `deckOpen &&
  // !deckCardFocus` 闸、探险器的 Arrow* 有 `explorerTreeFocus` 闸 —— 聊天流里
  // 焦点在卡脸上时两条规则都不成立，**零键位冲突**（先查 keymap 的结论）。
  // 只处理本脸自身持有焦点的事件（e.target === view）：卡内 pill 的 Enter/Space
  // 归 pill 自己（原生按钮语义），不在此重复消费。
  view.tabIndex = 0;
  view.addEventListener('keydown', (e) => {
    if (e.target !== view) return;
    const n = layers.length;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') front = (front + 1) % n;
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') front = (front - 1 + n) % n;
    else if (e.key === 'Home') front = 0;
    else if (e.key === 'End') front = n - 1;
    else if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault();
      e.stopPropagation();
      if (!stack.suppressClick) openItem(items[front], front);
      return;
    } else return;
    e.preventDefault();
    applyLayers();
  });

  /** The expanded column's natural height: the sum of its layers plus the
   *  column gap. Every layer has a definite height (its own ratio × the column
   *  width) even while its bytes are still loading, so the value is stable. */
  const expandHeight = () => Array.from(expandView.children).reduce((s, c) => {
    const el = /** @type {HTMLElement} */ (c);
    return s + el.offsetHeight;
  }, 0) + 20 * (expandView.children.length - 1);

  /** 展开列宽度 = **折叠卡脸宽度**（卡面尺度单源）：主会话/好友窗的卡脸是 220
   *  ⇒ 与 EXPAND_MAX.w 同值、逐字不变；宿主把卡面尺度收小（引用牌 96 档，
   *  chat.css「引用牌 · 多图叠卡」）时展开行跟着收，不横向顶出宿主。
   *  展开行宽是 fitLayer 写在每个 layer 上的**内联 px**（首次由 load 腿按
   *  220 上限写入），故展开动作前按 dataset.nfRatio 重算一次 —— 几何算术仍只在
   *  组件内（零宿主参数、零 !important）。 */
  const refitExpanded = () => {
    expandLayers.forEach((layer) => {
      const r = String(layer.dataset.nfRatio || '').split('x').map(Number);
      if (r.length === 2 && r[0] > 0 && r[1] > 0) {
        fitLayer(layer, r[0], r[1], expandMaxW(), EXPAND_MAX.h);
      }
    });
  };

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
    // 展开前把展开行宽度对到本节点的卡脸宽度（见 refitExpanded 头注）。
    if (v) refitExpanded();
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
export { buildMediaStack };

/** A single media item renders flat — no pill, no stack, no switching. Its box
 *  takes the media's own ratio (zcode-484 D1/D3), so a lone wide screenshot is
 *  not cropped into a portrait box. An AUDIO item has no ratio to report — the
 *  `is-audio` class retires the fallback ratio box so the flat face collapses
 *  to the controls strip (chat.css) instead of a mostly-empty 4/3 frame.
 *  三修 ② (2026-10-06 10:59 作者截图令): a click on the whole image card opens
 *  CANVAS (openMediaItemClick = the file card's single point when a path
 *  exists, the 主题三 lightbox for pathless inline bytes). The corner button
 *  stays as the explicit entry — same handler, same single point. */
/** Single media item — flat, no stack, no switching. (Rendering出口：fm-att-card-unify
 *  批 2026-11-02 起好友/群/设备三窗的图片附件卡同接本面 —— 主会话卡语言的**唯一**家，
 *  禁第二套媒体卡实现。视频/音频件同走本面（item.kind 驱动面内 `<video>`/`<audio>`，
 *  形态与主会话 Pop 媒体卡逐字同款）。)
 *  **标准组件 API**（fm-att-component-lib 批 2026-11-02）`opts`（可省；省略/无键 =
 *  主会话既有行为**逐字不变**）：
 *   · `opts.onOpen(item)`：整面点击动作。缺省 = `openMediaItemClick`（Canvas/灯箱
 *     单点）；传 `null` = 不挂面内点击（调用方自己在宿主根上挂，如社交三窗的可点面）；
 *     `opts.onClick` = 旧名兼容位（onOpen 在场即优先）；
 *   · `opts.corner`：面角标元素（替代默认 Canvas 角标）；`null` = 不挂角标；
 *   · `box.popFaces`：字节填充句柄（dry 腿宿主用）——`[{item, index:0, els:[mediaEl]}]`。 */
function buildMediaFlat(item, opts) {
  const box = document.createElement('div');
  box.className = 'pop-media-flat' + (item.kind === 'audio' ? ' is-audio' : '');
  box.appendChild(mediaFace(item, 'expanded', (w, h) => fitLayer(box, w, h, STACK_MAX.w, EXPAND_MAX.h)));
  const click = opts && ('onOpen' in opts ? opts.onOpen : ('onClick' in opts ? opts.onClick : undefined));
  if (typeof click === 'function') box.addEventListener('click', () => click(item));
  else if (click === undefined) box.addEventListener('click', () => openMediaItemClick(item));
  let corner = opts && 'corner' in opts ? opts.corner : undefined;
  if (corner === undefined) corner = canvasCornerButton(item);
  if (corner) box.appendChild(corner);
  const mediaEl = box.querySelector('img, video, audio');
  // 自定义载荷面（expando，lib.dom 无此属性）：与 buildMediaStack 的 area 同形状。
  if (mediaEl) /** @type {any} */ (box).popFaces = [{ item, index: 0, els: [mediaEl] }];
  return box;
}
export { buildMediaFlat };

/* ─────────────── file-card face ─────────────── */

/** File card（渲染出口：fm-att-card-unify 批 2026-11-02 起三窗文件附件卡同接本面 ——
 *  名称/大小/footer 信息行与打开交互的**唯一**家，禁第二套文件卡实现。）
 *  **标准组件 API**（fm-att-component-lib 批 2026-11-02）`opts`（可省；省略/无键 =
 *  主会话既有行为**逐字不变**）：
 *   · `opts.onOpen(item)`：整卡点击动作。缺省 = 有 path 即 `openPopArtifact`（无 path
 *     渲染但不动作，#491 主题三口径）；传 `null` = 不挂卡级点击（调用方自管）；
 *     `opts.onClick` = 旧名兼容位（onOpen 在场即优先）；
 *   · `opts.trailing`：卡尾元素（替代默认「在 Canvas 打开」圆键）；`null` = 不挂卡尾；
 *   · `opts.subNote`：footer 信息行的**追加段**（状态语义，如「已过期」）——信息结构
 *     等价适配（fm-att-card-unify 批申报）：`[类型, 大小, 追加段] · join`；
 *   · `opts.extras`：附加行元素数组（**整行折行**挂卡尾之前——上传进度条/状态注记行
 *     等全宽挂件的标准参数位；宿主卡根需自持 `flex-wrap`）。 */
function buildFileCard(item, opts) {
  const card = document.createElement('div');
  card.className = 'file-card';
  const icon = fileIconStyle(item.ext);
  const meta = document.createElement('div');
  meta.className = 'fc-meta';
  const name = document.createElement('div');
  name.className = 'fc-name';
  name.textContent = item.name || (item.path || '').split('/').pop() || item.path || '';
  name.title = item.path || '';
  const sub = document.createElement('div');
  sub.className = 'fc-sub';
  const sizeText = humanSize(item.size);
  sub.textContent = [String(icon.label), sizeText, (opts && opts.subNote) || ''].filter(Boolean).join(' · ');
  const trailing = opts && 'trailing' in opts ? opts.trailing : undefined;
  const click = opts && ('onOpen' in opts ? opts.onOpen : ('onClick' in opts ? opts.onClick : undefined));
  /** 默认点击动作（主会话口径）：有 path 才动作；无 path 渲染但不动作。 */
  const defaultOpen = () => { if (item.path) openPopArtifact(item.path, item.name); };
  if (typeof click === 'function') card.addEventListener('click', () => click(item));
  else if (click === undefined) card.addEventListener('click', defaultOpen);
  let btn = trailing;
  if (btn === undefined) {
    btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'fwd-btn';
    btn.setAttribute('aria-label', t('chat.popOpenCanvas'));
    btn.innerHTML = FWD_ICON + '<span class="fwd-tip">' + escapeHtml(t('chat.popOpenCanvas')) + '</span>';
    // #491 主题三: a card without an absolute path (a live user attachment whose
    // bytes ride inline, path unknown until the backend resolves it) renders but
    // opens nothing — never a bogus workspace-open-item with an empty path.
    btn.addEventListener('click', e => {
      e.stopPropagation();
      defaultOpen();
    });
  }
  const iconEl = document.createElement('div');
  iconEl.className = 'fc-icon ' + icon.cls;
  iconEl.textContent = icon.label;
  // 文件类每件本来就是独立文件卡，引用入口也逐卡挂在 footer（卡尾）。
  const quoteItem = opts && opts.quoteRef;
  const quoteBtn = quoteItem ? createMsgQuoteButton(quoteItem) : null;
  if (quoteBtn) quoteBtn.classList.add('fc-quote-btn');
  card.append(iconEl, meta);
  if (Array.isArray(opts && opts.extras)) opts.extras.forEach(x => { if (x) card.appendChild(x); });
  if (btn) card.appendChild(btn);
  if (quoteBtn) card.appendChild(quoteBtn);
  meta.append(name, sub);
  return card;
}
export { buildFileCard };

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

/** File-name → extension (no dot → ''). (导出：fm-att-card-unify 批 —— 三窗文件卡
 *  同一扩展名单点，禁第二份 ext 解析。) 实现 = 叶子模块 `fileIcon.js`（同一份
 *  re-export，见上方 fileIconStyle 段）。 */

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

/** 叠卡单张的引用描述符（2026-11-02 单图引用批）：只带**这一张**的附件
 *  （引用牌的缩略/文件名 = 被引那张），并带 itemIndex/itemTotal 作位置标注
 *  ⇒ 同组三张的引用牌彼此可区分（摘要「第 N/total 张」+ 单张缩略）。宿主身份
 *  （messageId/sessionId/sender）不走这里 —— msgQuote.startItemQuote 从行级
 *  描述符统一解析（挂点零身份算术）。 */
function stackItemQuoteDesc(item, index, total) {
  const isMedia = item.kind === 'image' || item.kind === 'video';
  return {
    kind: isMedia ? 'image' : 'file',
    fileName: item.name || (item.path || '').split('/').pop() || '',
    path: item.path || '',
    attachments: [{
      type: isMedia ? item.kind : 'file',
      name: item.name || (item.path || '').split('/').pop() || '',
      path: item.path || '',
      preview: (typeof item.src === 'string' && item.src.startsWith('data:')) ? item.src : undefined,
    }],
    itemIndex: index,
    itemTotal: total,
  };
}

/** The INDEPENDENT media row for one user message's non-ref attachments — the
 *  exact same row shape a Pop payload builds (`.row.pop-artifact` + faces), so
 *  「与 LLM Pop 卡片同层同形」 holds by construction. The `user-media` marker
 *  class is the QA/spec hook. Returns null when nothing displayable remains
 *  (refs-only messages keep the plain user row).
 *  opts.ts = 所属消息时间戳（footer 信息条时间档；live/重放/补丁三腿各自传入，
 *  0/缺席 ⇒ 无时间档——防御门，正常链路恒带）。 */
export function buildUserMediaRow(attachments, opts) {
  const resolver = opts && typeof opts.resolver === 'function' ? opts.resolver : undefined;
  const ts = opts && Number(opts.ts) > 0 ? Number(opts.ts) : 0;
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
  // 附件批（2026-10-31 作者令「引用要支持你发的文件 图片等」）：描述符随带
  // **全量**附件清单（type/name/path/preview）——引用块据此渲染附件缩略带；
  // 文本行引用时经 msgQuote.siblingMediaAttsOf 按同 msgId 合入（微信整消息语义）。
  row.dataset.msgId = nextChatMsgId();
  {
    const first = items[0] || {};
    const d = first.kind === 'image' || first.kind === 'video'
      ? { kind: 'image', fileName: first.name, path: first.path }
      : { kind: 'file', fileName: first.name || (first.path || '').split('/').pop(), path: first.path };
    attachQuoteMenu(row, {
      ...d,
      attachments: items.map(it => ({
        type: (it.kind === 'image' || it.kind === 'video') ? it.kind : 'file',
        name: it.name || (it.path || '').split('/').pop() || '',
        path: it.path || '',
        preview: it.src || undefined,
      })),
      senderRole: 'user', senderName: t('chat.quoteSenderMe'), ts: ts || Date.now(),
    });
  }
  const group = document.createElement('div');
  group.className = 'pop-group';
  row.appendChild(group);
  if (media.length === 1) group.appendChild(buildMediaFlat(media[0]));
  else if (media.length > 1) {
    // 单张引用入口（2026-11-02 11:00 作者令移入 footer）：叠卡每张各自的描述符
    // —— 张数 = media 组（拖动/横滑翻页的同一序）；footer 入口按当前 front 取。
    group.appendChild(buildMediaStack(media, {
      quoteDescFor: (it, i) => stackItemQuoteDesc(it, i, media.length),
    }));
  }
  files.forEach(f => group.appendChild(buildFileCard(f, {
    quoteRef: { kind: 'file', fileName: f.name || (f.path || '').split('/').pop(), path: f.path || '', attachments: [{ type: 'file', name: f.name || '', path: f.path || '' }] },
  })));
  // footer 信息条（手机端长按退役批 2026-10-31 作者令）：与文本消息底部信息条
  // 同族 .duration-badge——时间 + 下载（常用操作），触屏触达不再依赖长按；
  // 引用钮由 mountQuoteFooterButton（attachQuoteMenu 的 rAF 收敛）挂进同一徽章。
  // 装配单点 = buildMediaRowFooterBadge（Pop 交付卡 footer 批 2026-11-01 起，
  // 用户媒体行与 LLM Pop 行同一装配器——零第二套装配）。
  const badge = buildMediaRowFooterBadge(items, ts);
  if (badge) row.appendChild(badge);
  return row;
}

/* ── footer 信息条装配单点（Pop 交付卡 footer 批 2026-11-01 作者令）─────────
 * 用户媒体行（buildUserMediaRow）与 LLM Pop 交付行（buildPopArtifactRow）共用：
 * 时间档 + 下载键，视觉逐字同族（.duration-badge / .duration-badge-divider /
 * .msg-copy.msg-dl 全站既有规则，零新 CSS）。引用钮不在此装配——由
 * attachQuoteMenu 的 rAF 收敛（mountQuoteFooterButton）挂进已入行的徽章。
 * 下载键只在**单件行**挂：一枚键对叠卡/多文件里哪一件负责说不清，宁缺毋假
 * （多件行 footer 仍带时间档）。字节两腿（path / 内联 data:）皆缺 ⇒ 不挂键
 * （零假按钮；attachmentsSaved 补丁腿重渲后 path 到位即补挂）。 */
function buildMediaRowFooterBadge(items, ts) {
  const single = items && items.length === 1 ? items[0] : null;
  const badge = createMsgFooterBadge(ts, '');
  const dlBtn = createMediaDownloadButton(single);
  if (dlBtn) {
    if (badge.childElementCount > 0) {
      const div = document.createElement('span');
      div.className = 'duration-badge-divider';
      badge.appendChild(div);
    }
    badge.appendChild(dlBtn);
  }
  return badge.childElementCount > 0 ? badge : null;
}

/* ── footer 下载键（手机端长按退役批 2026-10-31）──────────────────────────
 * 视觉族复用 .msg-copy（复制/引用钮同档 12px 灰键；.copied 绿勾 / .copy-failed
 * 红态规则全站既有 ⇒ 零新 CSS），.msg-dl 标记类作探针/特化钩。字节腿 =
 * nfViewerSource 账本单点（path 腿，零新增取字节路）/ 内联 data: 腿（未落盘的
 * live 图直接落盘为本地文件）；两腿皆缺 ⇒ 不挂键（零假按钮）。 */
const DOWNLOAD_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
const DL_CHECK_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
// 字形导出（fm-att-card-unify 批 2026-11-02）：三窗下载键与主会话 footer 下载键
// 同一枚 SVG 单点（禁第二份拷贝——字形漂移即第二套样式）。
export { DOWNLOAD_SVG, DL_CHECK_SVG };

/** 下载键工厂（单件行专用；不可下载 ⇒ null）。成功态复用 .copied 绿勾 1.5s
 *  自清（复制钮同节奏）；失败态复用 .copy-failed 红键 2s 自清 + console 取证。 */
function createMediaDownloadButton(item) {
  if (!item) return null;
  const hasInline = typeof item.src === 'string' && item.src.startsWith('data:');
  if (!item.path && !hasInline) return null;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'msg-copy msg-dl';
  const label = t('messages.attachDownload');
  btn.title = label;
  btn.setAttribute('aria-label', label);
  btn.innerHTML = DOWNLOAD_SVG;
  let busy = false;
  btn.addEventListener('click', async (e) => {
    e.stopPropagation();
    if (busy) return;
    busy = true;
    const flash = (cls, svg, ms) => {
      btn.innerHTML = svg;
      btn.classList.add(cls);
      setTimeout(() => { btn.innerHTML = DOWNLOAD_SVG; btn.classList.remove(cls); }, ms);
    };
    let url = '';
    try {
      if (item.path) {
        ({ url } = await nfViewerSource({ absPath: item.path, objectUrl: null, pane: null }));
      } else {
        url = URL.createObjectURL(await (await fetch(item.src)).blob());
      }
      const a = document.createElement('a');
      a.href = url;
      a.download = item.name || String(item.path || '').split('/').pop() || 'file';
      document.body.appendChild(a);
      a.click();
      a.remove();
      // blob URL 由下载侧消费；延迟回收（nfViewerSource 腿的 URL 挂 document.body
      // 不会被 canvas-tab-closed 回收，这里就是它的清理点）。
      setTimeout(() => { try { URL.revokeObjectURL(url); } catch { /* already dead */ } }, 60_000);
      flash('copied', DL_CHECK_SVG, 1500);
    } catch (err) {
      console.warn('[popArtifacts] media download failed', err);
      flash('copy-failed', DOWNLOAD_SVG, 2000);
    } finally {
      busy = false;
    }
  });
  return btn;
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

/** sessionId → [{row, atts, ts}] FIFO（上限 8）：等待落盘路径的 live 行。上限只是
 *  防御性保鲜——正常流里帧在发送后毫秒级到达即消费掉条目；帧丢失（旧后端）时
 *  条目随上限滚动，且 att 对象本就被在 DOM 的行闭包持有，登记不放大保留面。 */
const pendingUserMediaRows = new Map();
const PENDING_MEDIA_ROWS_CAP = 8;

/** Live 渲染登记钩子（chat.js renderUserBubble 唯一调用方）。ts = 消息时间戳，
 *  补丁腿重渲 footer 信息条时间档与 live 腿同值（手机端长按退役批 2026-10-31）。 */
export function registerUserMediaRow(sessionId, row, atts, ts) {
  if (!sessionId || !row || !Array.isArray(atts) || !atts.length) return;
  const list = pendingUserMediaRows.get(sessionId) || [];
  list.push({ row, atts, ts: Number(ts) > 0 ? Number(ts) : 0 });
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
  const fresh = buildUserMediaRow(entry.atts, { ts: entry.ts });
  if (fresh && entry.row.isConnected) {
    // 重放腿同形对齐（附件批 2026-10-31 口径的重放侧补齐）：原位重渲不换消息身份——
    // 替换行继承原行的 data-msg-id，引用寻址/兄弟行合入判据（按同 msgId 找
    // .row.user-media）在补丁后仍然成立。
    fresh.dataset.msgId = entry.row.dataset.msgId || fresh.dataset.msgId;
    entry.row.replaceWith(fresh);
  }
}

/**
 * Build the artifact row for one Pop payload: media group (stack or flat) on
 * top, file cards under it, the failed-paths chip last. Returns null when the
 * payload carries no displayable item (callers keep the plain tool row).
 *
 * opts.ts = 所属工具消息时间戳（footer 信息条时间档；live 腿 Date.now() /
 * 重放两腿 m.timestamp，0/缺席 ⇒ 无时间档——防御门）。footer/引用批（作者令
 * 2026-11-01 16:19）：LLM Pop 交付行补与文件/图片消息同族的 footer 信息条——
 * 时间档 + 下载键（单件行，装配单点 buildMediaRowFooterBadge）+ 引用钮
 * （attachQuoteMenu 同一挂点，rAF 收敛进徽章；描述符 = 首项 kind/文件名/path，
 * 全量 items 随带 ⇒ 引用块渲染缩略带，与用户媒体行同语义）。行挂自己的
 * msgId（live nextChatMsgId；重放腿由调用方覆写为 historyMsgId(i)+'-pop'）——
 * 引用寻址落卡行本体，不串到同轮的工具行。
 */
/** The payload kinds that ride the MEDIA face (stack / flat); everything else
 *  is a file card. canvas-media Wave2 adds `audio` beside image/video. */
const MEDIA_KINDS = new Set(['image', 'video', 'audio']);

export function buildPopArtifactRow(payload, opts) {
  const ts = opts && Number(opts.ts) > 0 ? Number(opts.ts) : 0;
  const items = (payload.items || []).filter(it => it && typeof it.path === 'string');
  if (!items.length) return null;
  const media = items.filter(it => MEDIA_KINDS.has(it.kind));
  const files = items.filter(it => !MEDIA_KINDS.has(it.kind));

  const row = document.createElement('div');
  row.className = 'row pop-artifact';
  // quote-message 语义（Pop 交付卡 footer 批 2026-11-01）：行可引用 + footer
  // 信息条引用钮。摘要 = [图片]/[文件] 首项文件名（§2.5 表，媒体行同款）；
  // attachments 随带全量 items ⇒ 引用块缩略带与用户媒体行同构。
  row.dataset.msgId = nextChatMsgId();
  {
    const first = items[0] || {};
    const d = MEDIA_KINDS.has(first.kind)
      ? { kind: 'image', fileName: first.name, path: first.path }
      : { kind: 'file', fileName: first.name || (first.path || '').split('/').pop(), path: first.path };
    attachQuoteMenu(row, {
      ...d,
      attachments: items.map(it => ({
        type: MEDIA_KINDS.has(it.kind) && it.kind !== 'audio' ? it.kind : 'file',
        name: it.name || (it.path || '').split('/').pop() || '',
        path: it.path || '',
        preview: (typeof it.src === 'string' && it.src.startsWith('data:')) ? it.src : undefined,
      })),
      senderRole: 'ai', senderName: rootDisplayName(), ts: ts || Date.now(),
    });
  }
  const group = document.createElement('div');
  group.className = 'pop-group';
  row.appendChild(group);

  if (media.length === 1) group.appendChild(buildMediaFlat(media[0]));
  else if (media.length > 1) {
    // 单张引用入口（2026-11-02 11:00 作者令移入 footer）：LLM Pop 交付行与用户
    // 媒体行同一条路 —— 叠卡每张各有描述符，footer 入口按当前 front 取该张。
    group.appendChild(buildMediaStack(media, {
      quoteDescFor: (it, i) => stackItemQuoteDesc(it, i, media.length),
    }));
  }
  files.forEach(f => group.appendChild(buildFileCard(f, {
    quoteRef: { kind: 'file', fileName: f.name || (f.path || '').split('/').pop(), path: f.path || '', attachments: [{ type: 'file', name: f.name || '', path: f.path || '' }] },
  })));

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
  // footer 信息条（作者令 2026-11-01 16:19）：与文件/图片消息同族徽章，装配
  // 单点 buildMediaRowFooterBadge（用户媒体行同一装配器）。
  const badge = buildMediaRowFooterBadge(items, ts);
  if (badge) row.appendChild(badge);
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
