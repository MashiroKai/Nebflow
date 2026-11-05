// videoCover.js — 视频「内容封面」（video-cover 批 2026-11-05；作者令逐字
// 「不管是在对话框还是canvas，都应该有一个内容预览的一个封面」）。
//
// ── 改前现场 ────────────────────────────────────────────────────────────────
// 视频面的出帧前状态是**空**：对话框 = `.pop-video` 的纯黑底块（底色见 chat.css 的
// `.pop-video` 规则）+ 播放键；Canvas = 无背景的灰块（split.css 明令「此处不设任何
// 背景」）。两者都看不出片子里是什么 —— 唯一的「封面」是用户自己点开后的第一帧。
//
// ── 选型（前端抓帧 vs 后端预生成缩略图）────────────────────────────────────
// 取**前端抓帧**，三条理由都是本仓现场推出来的，不是省事：
//  ① 字节源有**四条腿**且两条不在服务端：`localFace`（发送侧本机 File 句柄）、
//     设备 path 腿（对端盘上的文件）、blob 账本腿、nf-file 票据流式腿。后端预生成
//     对前两条**根本没有字节可读**，只能回落到前端腿 ⇒ 两套实现、两处漂移。
//  ② Canvas 视频查看器有 >64MiB **不落地**的流式腿（viewers/video.js 的票据
//     Range 语义）：服务端要出缩略图就得整件读一遍或引入 ffmpeg 管线 + 缩略图
//     仓库 + 失效对账，成本远超收益（作者要的是「看得出内容」，不是「秒出」）。
//  ③ 降级天然对齐既有语义：抓帧失败 = 什么都不加，面回到**现状**（黑/灰块 +
//     播放键），不引入第二种失败面。
// 代价（如实申报）：① 每个可见视频多一次 seek 解码（Range 只取目标帧附近，账本/
// blob 腿零网络）；② 首帧出现有 ~100-300ms 延迟（这段延迟里看到的就是现状面，
// 不是空白）；③ 客户端 CPU。②③ 由两条纪律压住：**不可见不抓**（IntersectionObserver）
// 与**并发上限 2**（防一屏几十个视频同时解码）。
//
// ── 机制（为什么是「覆盖层」而不是 `video.poster`）────────────────────────
// `poster` 只在「尚未解出任何一帧」时显示：抓帧必须 seek 一次，seek 完成即解出了
// 帧 ⇒ poster 永远不出现（浏览器规范行为，非实现差异）。故封面是**一层绝对定位的
// `<img>`**，骑在 video 之上、播放键/时长之下（插在 video 之后 ⇒ DOM 层序即视觉
// 层序，零 z-index 战争）；`play` 事件到达即淡出让位给实际画面。
//
// ── 失败降级（三条，全部回落到现状面）─────────────────────────────────────
//   · 解码失败 / 取不到 videoWidth → 不挂封面（`fails++`）；
//   · 跨域画面污染 canvas（`toDataURL` 抛 SecurityError）→ 不挂封面；
//   · seek 超时（SEEK_TIMEOUT_MS；直播流/无 duration 的源）→ 不挂封面。
//
// ── 懒加载证据面 ───────────────────────────────────────────────────────────
// `globalThis.__nfVideoCover`（只读计数 + `reset()`）：grabs = 真正解码抓帧次数，
// fails，cacheHits。验收/回归断言直接读它。

import { h } from './dom.js';

/** 同时解码的视频上限（一屏 N 个视频不许一起起解码器）。 */
const MAX_CONCURRENT = 2;
/** seek → seeked 的等待上限；超时按失败降级（不留半张图）。 */
const SEEK_TIMEOUT_MS = 4000;
/** 封面缓存条数上限（同 URL 重绘零二次解码；data URL 约 8-30KB/条）。 */
const POSTER_CACHE_MAX = 32;
/** 封面 JPEG 宽度上限（够卡面显示，不值得存原尺寸）。 */
const POSTER_MAX_W = 480;

/** 计数面（只读消费；测试缝见文件头）。**共享对象**：模块图里只有一个实例，
 *  但验收/调试腿可能经动态 import 再进来一次 —— 取既有全局对象而不是新建，
 *  计数器永远只有一份（否则两边各记一半，读数就成了假的）。 */
const stats = /** @type {{grabs:number,fails:number,cacheHits:number,reset:()=>void}} */ (
  /** @type {any} */ (globalThis).__nfVideoCover
  || (/** @type {any} */ (globalThis).__nfVideoCover = {
    grabs: 0, fails: 0, cacheHits: 0,
    reset() { stats.grabs = 0; stats.fails = 0; stats.cacheHits = 0; },
  })
);

/** src → poster data URL（LRU：满则丢最早一条）。 */
const POSTERS = new Map();

/** @param {string} key @param {string} url */
function remember(key, url) {
  POSTERS.set(key, url);
  while (POSTERS.size > POSTER_CACHE_MAX) {
    const first = POSTERS.keys().next();
    if (first.done) break;
    POSTERS.delete(first.value);
  }
}

/* ── 抓帧队列（并发闸） ─────────────────────────────────────────────────── */
let active = 0;
/** @type {Array<() => Promise<void>>} */
const queue = [];

function pump() {
  while (active < MAX_CONCURRENT && queue.length) {
    const task = queue.shift();
    if (!task) return;
    active += 1;
    Promise.resolve().then(task).catch(() => {}).finally(() => { active -= 1; pump(); });
  }
}

/** @param {() => Promise<void>} task */
function enqueue(task) { queue.push(task); pump(); }

/* ── 抓帧原语 ───────────────────────────────────────────────────────────── */

/**
 * seek 到 `time` 并等 `seeked`；失败/超时/元素报错一律 false（调用方降级）。
 * @param {HTMLVideoElement} video
 * @param {number} time
 * @returns {Promise<boolean>}
 */
function seekTo(video, time) {
  return new Promise((resolve) => {
    let settled = false;
    /** @param {boolean} ok */
    const finish = (ok) => {
      if (settled) return;
      settled = true;
      video.removeEventListener('seeked', onSeeked);
      video.removeEventListener('error', onError);
      clearTimeout(timer);
      resolve(ok);
    };
    const onSeeked = () => finish(true);
    const onError = () => finish(false);
    const timer = setTimeout(() => finish(false), SEEK_TIMEOUT_MS);
    video.addEventListener('seeked', onSeeked);
    video.addEventListener('error', onError);
    try {
      video.currentTime = time;
    } catch { finish(false); }
  });
}

/**
 * 当前帧 → JPEG data URL（`null` = 失败：未解码 / 跨域污染 / 无 2d 上下文）。
 * @param {HTMLVideoElement} video
 * @returns {string|null}
 */
function drawFrame(video) {
  const w = video.videoWidth;
  const hgt = video.videoHeight;
  if (!w || !hgt) return null;
  const scale = Math.min(1, POSTER_MAX_W / w);
  const cw = Math.max(1, Math.round(w * scale));
  const ch = Math.max(1, Math.round(hgt * scale));
  const cvs = document.createElement('canvas');
  cvs.width = cw;
  cvs.height = ch;
  const ctx = cvs.getContext('2d');
  if (!ctx) return null;
  try {
    ctx.drawImage(video, 0, 0, cw, ch);
  } catch {
    return null;
  }
  try {
    return cvs.toDataURL('image/jpeg', 0.72);
  } catch {
    return null; // 跨域污染（画面不是同源字节）
  }
}

/**
 * 代表性取帧时刻：避开片头黑场（多数成片前 0.2s 是黑/台标），又不超过片长的
 * 四分之一 —— 1/4 处最不容易撞上片尾字幕。片长未知（流/坏元数据）→ 0.5s。
 * @param {HTMLVideoElement} video
 * @returns {number}
 */
function pickTime(video) {
  const dur = Number(video.duration);
  if (!Number.isFinite(dur) || dur <= 0) return 0.5;
  return Math.min(Math.max(0.3, dur * 0.25), Math.max(0.3, dur - 0.05));
}

/**
 * 在一个**未播放**的视频上抓一帧。抓完把播放头放回原处（未播放 = 0），
 * 用户看到的是封面，播放头不动。
 * @param {HTMLVideoElement} video
 * @returns {Promise<string|null>}
 */
async function grab(video) {
  if (!video.isConnected) return null;
  if (!video.paused) return null;                        // 已经在播 —— 无需封面
  if (video.dataset.nfMediaFailed === '1') return null;  // 字节终态失败 —— 现状面接管
  // 元数据未到（preload=metadata 的异步腿）：等一次，超时按无封面处理。
  if (!(video.readyState >= 1) && !(video.videoWidth > 0)) {
    const ok = await new Promise((resolve) => {
      let settled = false;
      const finish = (v) => {
        if (settled) return;
        settled = true;
        clearTimeout(t);
        video.removeEventListener('loadedmetadata', onMeta);
        video.removeEventListener('error', onErr);
        resolve(v);
      };
      const onMeta = () => finish(true);
      const onErr = () => finish(false);
      const t = setTimeout(() => finish(false), SEEK_TIMEOUT_MS);
      video.addEventListener('loadedmetadata', onMeta);
      video.addEventListener('error', onErr);
    });
    if (!ok) return null;
  }
  const prev = Number.isFinite(video.currentTime) ? video.currentTime : 0;
  const target = pickTime(video);
  stats.grabs += 1;
  const seeked = await seekTo(video, target);
  const url = seeked ? drawFrame(video) : null;
  // 播放头归位：封面之下的帧位置与「未播放」语义一致（用户点播放从 0 开始）。
  if (prev !== target) {
    try { video.currentTime = prev; } catch { /* 流已脱离文档 */ }
  }
  if (!url) { stats.fails += 1; return null; }
  return url;
}

/**
 * 给一枚未播放的视频挂「内容封面」覆盖层。幂等（同元素二次调用返回同一句柄）。
 * 封面插在 `<video>` 之后、播放键之前：DOM 层序 = 视觉层序（播放键/时长仍在上）。
 *
 * @param {HTMLVideoElement|null} video
 * @param {{alt?: string}} [opts]
 * @returns {{ destroy: () => void, img: HTMLImageElement } | null}
 */
export function attachVideoCover(video, opts = {}) {
  if (!video) return null;
  const host = video.parentElement;
  if (!host) return null;
  const prev = /** @type {any} */ (video).__nfCover;
  if (prev && prev.img && prev.img.isConnected) return prev;

  // `h()` 的返回类型是 HTMLElement（泛型按标签收敛不完整）—— 本模块需要 `.src`，
  // 故在此一次性收窄（checkJs 门的 2 错来源；不做 `any` 逃逸）。
  const img = /** @type {HTMLImageElement} */ (h('img', {
    class: 'nf-video-cover',
    alt: opts.alt || '',
    'aria-hidden': 'true',
    draggable: false,
  }));

  let destroyed = false;
  let shown = false;
  /** @type {IntersectionObserver|null} */
  let io = null;
  /** 自抓帧自家 seek 的静默窗（见 onSeek 注）：抓帧期间置 Infinity，收尾后留
   *  600ms —— 「播放头归位」那次 seek 的 `seeking` 事件是异步到达的，晚于封面
   *  落位；不挡掉它，封面会被自己触发的一次隐藏立刻摘掉（首轮实测即此）。 */
  const guard = { until: 0 };

  /**
   * **封面之下的播放键**（作者令「封面之上保留播放键与时长」）：展开面/气泡面的
   * `<video controls>` 原生控件条画在 video 自身像素里，封面一盖就没了 —— 用户会
   * 面对一张「能看出内容但不知怎么播」的图。故封面在位期间把同族的 `.play-ring`
   * 临时立起来（原本只在叠卡面可见），封面让位时按原样收回。
   * Canvas 面没有 `.play-ring`（它的控件条在画面**之外**，天然不被封面遮），此支零命中。
   */
  const ring = /** @type {HTMLElement|null} */ (host.querySelector('.play-ring'));
  let ringPopped = false;
  const onRingClick = (ev) => { ev.stopPropagation(); video.play().catch(() => {}); };
  const popRing = () => {
    if (!ring || ringPopped) return;
    if (getComputedStyle(ring).display !== 'none') return; // 本来就可见（叠卡面）—— 不碰
    ring.style.display = 'flex';
    ring.addEventListener('click', onRingClick);
    ringPopped = true;
  };
  const unpopRing = () => {
    if (!ringPopped || !ring) return;
    ring.style.display = 'none';
    ring.removeEventListener('click', onRingClick);
    ringPopped = false;
  };

  const hide = () => {
    if (!shown) return;
    shown = false;
    img.classList.add('is-out');
    setTimeout(() => { if (img.isConnected) img.remove(); }, 220);
    unpopRing();
  };
  const onPlay = () => hide();
  // 用户拖进度条 = 他要看实际画面 ⇒ 让位；但**自家抓帧**的 seek 不算（guard 挡）。
  const onSeek = () => { if (performance.now() < guard.until) return; if (shown) hide(); };

  /** @param {string|null} url */
  const show = (url) => {
    if (!url || destroyed || shown) return;
    if (!video.paused) return; // 抓帧期间用户已经点了播放 —— 让它让位
    img.src = url;
    video.after(img);
    shown = true;
    popRing();
  };

  const start = () => {
    if (destroyed) return;
    const key = video.currentSrc || video.src || '';
    if (key && POSTERS.has(key)) {
      stats.cacheHits += 1;
      show(POSTERS.get(key) || null);
      return;
    }
    enqueue(async () => {
      if (destroyed || !video.isConnected) return;
      guard.until = Number.POSITIVE_INFINITY;
      const url = await grab(video);
      guard.until = performance.now() + 600;
      if (!url || destroyed || !video.isConnected) return;
      const k = video.currentSrc || video.src || '';
      if (k) remember(k, url);
      show(url);
    });
  };

  video.addEventListener('play', onPlay);
  video.addEventListener('seeking', onSeek);

  if (typeof IntersectionObserver === 'function') {
    io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        if (io) io.disconnect();
        io = null;
        start();
        return;
      }
    }, { rootMargin: '200px' });
    io.observe(video);
  } else {
    start();
  }

  const handle = {
    img,
    destroy() {
      destroyed = true;
      if (io) { io.disconnect(); io = null; }
      video.removeEventListener('play', onPlay);
      video.removeEventListener('seeking', onSeek);
      if (img.isConnected) img.remove();
      const anyVideo = /** @type {any} */ (video);
      if (anyVideo.__nfCover === handle) delete anyVideo.__nfCover;
    },
  };
  /** @type {any} */ (video).__nfCover = handle;
  return handle;
}

export default { attachVideoCover };
