// viewers/video.js — Canvas 视频面：自研扁平控件 + contain 放大（canvas-video-player
// 批 2026-11-05；作者报障逐字「现在视频在canvas的占比，和样式都很丑，需要优化一下。」）
//
// ── 改前现场（真实 DOM 读数见 reports-evidence/canvas-video-player__*/readings-before/）──
// 只想当然看 CSS 会以为是「父容器没高度」，实测不是：`.canvas-video-viewer` 是
// height:100% 的居中 flex 列，父容器高度**是**确定的。真根因是**只有上限没有尺寸**：
//   改前 split.css:2894-2899 —— `video.canvas-video { max-width:100%; max-height:100% }`
// `max-*` 对**替换元素**只是**封顶**，它不会把元素撑到上限；`<video>` 在没有任何
// `width/height` 声明时按**内在尺寸**布局（intrinsic 320×180）。于是 320×180 的片子
// 就停在 320×180，四周留白 = pane 减去 320×180（改前桌面 718×864 画布实测）
//   320×180 / pane 718×864 = 宽 44.6% × 高 20.8% · 面积 9.3% · 留白 199/342/199/342。
// 大于画布的片子（4K / 超宽）由 max-width 封顶，contain 关系**恰好**成立 —— 这解释了
// 「只有小视频看起来有病」：封顶是对的，缺的是「放大」那一半。
//
// ── 修法 A：占屏比例（测出来的法则，不是硬编码尺寸）──
// 目标法则：**画面 = contain(可用区)** —— 保持原始宽高比（不拉伸/不裁切），在可用区内
// 尽可能大且居中。实现 = CSS 容器查询单位（stage 是 `container-type: size`）：
//   宽 = min(100cqw, 100cqh × AR)  ·  高 = 宽 / AR  ·  AR = videoWidth/videoHeight
// ⇒ 宽高比小的（竖屏）由高度定宽，大的（超宽/4K）由宽度定高，永远整幅装得下；
//    小视频被放大到可用区边界（同一个法则的另一半），4K 不会溢出（同一条 min()）。
// AR 由 loadedmetadata 写进 `--cvv-ar`（CSS 里没有第二次宽高比真值）。
// 可用区 = pane − 顶部胶囊带（--cv-head-clear / 手机 --mchrome-clear）− 底部浮钮带
// （既有的 `--canvas-bottom-clearance`，viewers/shared.js 实测发布）− 控件条（流内）。
// 与「底部按钮让位」机制的关系：**消费同一把尺，不另立一把** —— 控件条是流内盒，
// 给它让位之后浮钮带仍然只压 padding，两者不重叠、也不会双计。
//
// ── 修法 B：控件（零遮挡）──
// 改前是浏览器原生 `<video controls>`：两条深色胶囊控件**压在画面像素上**，材质/圆角/
// 厚度与本站扁平风格无关。改为**流内控件条**（stage 之下的独立一行）：
//   · 结构上就**不可能**遮画面（画面在 stage 内 contain，控件条在 stage 之外）；
//     作者对「控件遮挡内容」多次报障，故不做 overlay + 自动淡出那种「默认遮、动才让」，
//     直接选「永不遮」。
//   · 扁平六态（零阴影/零渐变/零 translateY）走 `--btn-*` 令牌 + flatButtons.css 共享态层，
//     族名 `.canvas-video-btn` 已进登记表；门禁 scripts/check-flat-buttons.mjs。
//   · 手机档热区 = --ds-hit-tap-lg(48)；音量条桌面档才有（@media (hover: hover)）。
//   · 键盘：空格播放/暂停、←/→ ±5s、M 静音 —— 键位与条件登记在 keyRules.js 的唯一表
//     （when 走 `canvasVideoOpen`，本文件 provide 的派生上下文键），不与叠卡/输入/树冲突。
//
// 零取数语义不变：`nfViewerUrl` 的 ≤64MiB 账本腿与 >64MiB 票据流式腿原样（Range seek）。
// 文本面：本文件仍只出「真实状态」文案（时间/时长）+ 失败的可见错误面板；控件按钮的字
// 走 aria-label/title（无障碍面，不是页面文案面）。

import { h } from '../dom.js';
import { reMint } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';
import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { KEYS, provideContextKey } from '../keyContext.js';
import { attachVideoCover } from '../videoCover.js';

/** 无元数据时的兜底宽高比（只在 loadedmetadata 之前那一拍生效）。 */
const FALLBACK_AR = 16 / 9;
/** 方向键单步（秒）。 */
const SEEK_STEP = 5;

/** pane → 本查看器的资源回收闭包（幂等；见文件尾 dispose 注）。 */
const CLEANUP = new WeakMap();

/** 当前活动页签里的视频元素（无则 null）。所有「作用于视频的键」的唯一取件口。
 *  @returns {HTMLVideoElement|null} 选择器已钉死 `video.canvas-video`；此处类型注
 *  只补 checkJs 的收窄（querySelector 的静态返回是 Element|null），零行为改动。 */
function activeVideo() {
  return /** @type {HTMLVideoElement|null} */ (
    document.querySelector('.canvas-tab-pane.active video.canvas-video'));
}

// 键盘层的上下文键（keyboard-first 三期口径）：keyRules.js 的 when 消费它们。
// 派生而非推送 —— 页签切走后 `.active` 不在本 pane 上，键立刻让位给别的查看器，
// 不需要任何 focusout 时序腿。
provideContextKey(KEYS.canvasVideoOpen, () => !!activeVideo());
// 「焦点在本播放器内」是播控键的**准入闸**（实测取证 2026-11-05）：应用启动后
// 焦点常驻对话输入框（activeElement = #input TEXTAREA），若只按
// `!textInputFocus` 放行，播控键在真实使用里几乎永远不可达；而反过来无条件
// 接管空格/方向键，就会把「在对话里打字」变成「视频乱跳」。所以准入闸 = 焦点
// 落在本播放器子树内（stage 带 tabindex=0，点一下画面即入焦点；Tab 也能到达）。
// 这条同时把「不冲突」写成了结构事实：焦点不在播放器里，一条播控键都不生效。
provideContextKey(KEYS.canvasVideoFocus, () => {
  const a = document.activeElement;
  return !!(a instanceof Element && a.closest('.canvas-video-viewer'));
});

/**
 * 秒 → `m:ss` / `h:mm:ss`。NaN/Infinity（直播流、未取到时长）→ `--:--`。
 * @param {number} sec
 * @returns {string}
 */
function fmtTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) return '--:--';
  const s = Math.floor(sec % 60);
  const m = Math.floor(sec / 60) % 60;
  const hh = Math.floor(sec / 3600);
  const ss = String(s).padStart(2, '0');
  return hh > 0 ? `${hh}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** lucide 图标叶子（createIconsIn 会把 <i data-lucide> 换成同几何的 svg）。 */
function iconLeaf(name) {
  return h('i', { 'data-lucide': name });
}

/** 换一颗按钮图标（只在语义态真的变了时调用，别在 timeupdate 里抖 DOM）。 */
function setIcon(btn, name) {
  if (btn.dataset.icon === name) return;
  btn.dataset.icon = name;
  btn.textContent = '';
  btn.appendChild(iconLeaf(name));
  createIconsIn(btn);
}

/**
 * 建一颗扁平控件钮。六态由 flatButtons.css 共享态层补（hover 关在 (hover:hover)、
 * 按下缩放、焦点描边环、禁用档、触屏档、减少动效），本文件只给几何与 aria。
 * @param {string} icon lucide 名
 * @param {() => void} onClick
 * @param {string} label 无障碍名（同时做 title）
 * @param {string} extra 附加类
 * @returns {HTMLButtonElement}
 */
function makeButton(icon, onClick, label, extra) {
  const btn = /** @type {HTMLButtonElement} */ (h('button', {
    type: 'button',
    class: `canvas-video-btn${extra ? ' ' + extra : ''}`,
    'aria-label': label,
    title: label,
  }, [iconLeaf(icon)]));
  btn.addEventListener('click', onClick);
  btn.dataset.icon = icon;
  createIconsIn(btn);          // 首帧就把图标渲出来（setIcon 的同名早退依赖这一点）
  return btn;
}

/**
 * 建一条扁平滑轨（进度 / 音量共用一份实现 —— 两条各写一套必然漂移）。
 * 指针拖动 + 键盘（←/→ 由 keyRules 的全局规则处理，这里只负责指针与 aria-valuenow）。
 * @param {string} cls 根类名
 * @param {string} label 无障碍名
 * @param {(ratio: number) => void} onRatio 拖动中的实时回调（0..1）
 * @returns {{ root: HTMLElement, set: (ratio: number) => void }}
 */
function makeSlider(cls, label, onRatio) {
  const fill = h('span', { class: `${cls}-fill` });
  const knob = h('span', { class: `${cls}-knob` });
  const root = h('div', {
    class: cls,
    role: 'slider',
    tabindex: '0',
    'aria-label': label,
    'aria-valuemin': '0',
    'aria-valuemax': '100',
    'aria-valuenow': '0',
  }, [h('span', { class: `${cls}-track` }), fill, knob]);

  const ratioAt = (ev) => {
    const r = root.getBoundingClientRect();
    if (r.width <= 0) return 0;
    return Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width));
  };
  let dragging = false;
  root.addEventListener('pointerdown', (ev) => {
    if (ev.pointerType === 'mouse' && ev.button !== 0) return;
    dragging = true;
    try { root.setPointerCapture(ev.pointerId); } catch { /* 合成事件无捕获权 */ }
    onRatio(ratioAt(ev));
    ev.preventDefault();
  });
  root.addEventListener('pointermove', (ev) => { if (dragging) onRatio(ratioAt(ev)); });
  const end = (ev) => {
    if (!dragging) return;
    dragging = false;
    try { root.releasePointerCapture(ev.pointerId); } catch { /* 已释放 */ }
  };
  root.addEventListener('pointerup', end);
  root.addEventListener('pointercancel', end);

  return {
    root,
    set(ratio) {
      const pct = Math.min(1, Math.max(0, Number.isFinite(ratio) ? ratio : 0));
      fill.style.width = `${(pct * 100).toFixed(2)}%`;
      knob.style.left = `${(pct * 100).toFixed(2)}%`;
      root.setAttribute('aria-valuenow', String(Math.round(pct * 100)));
    },
  };
}

/**
 * 建自研控件条（流内；永不在画面像素上）。
 * @param {HTMLVideoElement} video
 * @returns {{ root: HTMLElement, sync: () => void, destroy: () => void }}
 */
function buildControls(video) {
  const playBtn = makeButton('play', () => togglePlay(video), t('canvas.videoPlay'), 'cvv-play');
  const muteBtn = makeButton('volume-2', () => { video.muted = !video.muted; }, t('canvas.videoMute'), 'cvv-mute');
  const timeEl = h('span', { class: 'cvv-time', text: `${fmtTime(0)} / ${fmtTime(NaN)}` });

  const progress = makeSlider('cvv-scrub', t('canvas.videoProgress'), (ratio) => {
    if (!Number.isFinite(video.duration) || video.duration <= 0) return;
    video.currentTime = Math.min(video.duration - 0.001, Math.max(0, ratio * video.duration));
  });

  const volume = makeSlider('cvv-vol', t('canvas.videoVolume'), (ratio) => {
    video.muted = ratio <= 0;
    video.volume = ratio;
  });

  const pipBtn = makeButton('picture-in-picture', () => {
    if (!document.pictureInPictureEnabled || !video.requestPictureInPicture) return;
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    else video.requestPictureInPicture().catch(() => {});
  }, t('canvas.videoPip'), 'cvv-pip');

  const fullBtn = makeButton('maximize', () => {
    const host = video.closest('.canvas-video-viewer') || video.parentElement;
    if (!host) return;
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (host.requestFullscreen) host.requestFullscreen().catch(() => {});
  }, t('canvas.videoFullscreen'), 'cvv-full');

  const root = h('div', {
    class: 'canvas-video-bar',
    role: 'toolbar',
    'aria-label': t('canvas.videoControls'),
  }, [playBtn, timeEl, progress.root, muteBtn, volume.root, pipBtn, fullBtn]);

  if (!document.pictureInPictureEnabled) {
    pipBtn.disabled = true;
    pipBtn.setAttribute('aria-disabled', 'true');
  }

  /** 只在语义态变化时写 DOM 的局部缓存（避免 timeupdate 4Hz 抖动图标）。 */
  const seen = { paused: null, muted: null, full: null, dur: null };

  const sync = () => {
    const dur = Number.isFinite(video.duration) ? video.duration : NaN;
    const cur = Number.isFinite(video.currentTime) ? video.currentTime : 0;
    progress.set(Number.isFinite(dur) && dur > 0 ? cur / dur : 0);
    if (seen.dur !== dur || seen.cur !== cur) {
      timeEl.textContent = `${fmtTime(cur)} / ${fmtTime(dur)}`;
      seen.dur = dur;
      seen.cur = cur;
    }
    if (seen.paused !== video.paused) {
      seen.paused = video.paused;
      setIcon(playBtn, video.paused ? 'play' : 'pause');
      const label = video.paused ? t('canvas.videoPlay') : t('canvas.videoPause');
      playBtn.setAttribute('aria-label', label);
      playBtn.title = label;
    }
    const muted = video.muted || video.volume === 0;
    if (seen.muted !== muted) {
      seen.muted = muted;
      setIcon(muteBtn, muted ? 'volume-x' : 'volume-2');
      const label = muted ? t('canvas.videoUnmute') : t('canvas.videoMute');
      muteBtn.setAttribute('aria-label', label);
      muteBtn.title = label;
      volume.set(muted ? 0 : video.volume);
    }
    const full = !!document.fullscreenElement;
    if (seen.full !== full) {
      seen.full = full;
      setIcon(fullBtn, full ? 'minimize' : 'maximize');
      const label = full ? t('canvas.videoExitFullscreen') : t('canvas.videoFullscreen');
      fullBtn.setAttribute('aria-label', label);
      fullBtn.title = label;
    }
  };

  const EVENTS = ['timeupdate', 'durationchange', 'play', 'pause', 'volumechange',
    'loadedmetadata', 'seeked', 'emptied'];
  for (const name of EVENTS) video.addEventListener(name, sync);
  document.addEventListener('fullscreenchange', sync);
  video.addEventListener('enterpictureinpicture', sync);
  video.addEventListener('leavepictureinpicture', sync);

  sync();

  return {
    root,
    sync,
    destroy() {
      for (const name of EVENTS) video.removeEventListener(name, sync);
      document.removeEventListener('fullscreenchange', sync);
      video.removeEventListener('enterpictureinpicture', sync);
      video.removeEventListener('leavepictureinpicture', sync);
    },
  };
}

/** 播放/暂停（play() 的 AbortError 是「用户又点了暂停」，不是失败）。 */
function togglePlay(video) {
  if (!video) return;
  if (video.paused) video.play().catch(() => {});
  else video.pause();
}

/**
 * keyRules.js 的唯一动作出口（键位与条件都在那张表里，这里只做动作）。
 * @param {'playPause'|'seekForward'|'seekBackward'|'mute'} action
 * @returns {void}
 */
export function videoKeyAction(action) {
  const v = activeVideo();
  if (!v) return;
  switch (action) {
    case 'playPause': togglePlay(v); break;
    case 'seekForward': seekBy(v, SEEK_STEP); break;
    case 'seekBackward': seekBy(v, -SEEK_STEP); break;
    case 'mute': v.muted = !v.muted; break;
    default: break;
  }
}

/** 相对跳转，夹在 [0, duration] 内（duration 是 NaN 时按「还没元数据」不动）。 */
function seekBy(video, delta) {
  if (!Number.isFinite(video.duration) || video.duration <= 0) return;
  video.currentTime = Math.min(video.duration, Math.max(0, video.currentTime + delta));
}

/**
 * Video viewer — 自研控件 + contain 放大，喂 /api/nf-file。
 * @param {HTMLElement} pane
 * @param {{absPath?: string, fileName?: string, size?: number, objectUrl?: string}} ctx
 */
async function viewVideo(pane, { absPath, fileName, size, objectUrl }) {
  // 两条取数腿同 viewers/image.js：
  //   `objectUrl` = 字节已在页面里（附件预览 blob 腿）—— 不需要路径、不铸票；
  //   `absPath`   = Pop / 文件树 / 设备保存路径 —— 本查看器自己铸每打开一次的票据 URL。
  if (!absPath && !objectUrl) {
    pane.classList.add('scrollable');
    pane.textContent = '';
    pane.appendChild(h('div', { class: 'canvas-error', text: 'Cannot play video: no absolute path available.' }));
    return;
  }

  pane.classList.remove('scrollable'); // 视频按 contain 铺满，不需要 pane 滚动区
  pane.textContent = '';

  const video = /** @type {HTMLVideoElement} */ (h('video', {
    class: 'canvas-video',
    preload: 'metadata',
    playsinline: true,
  }));
  // controls 属性**不设** —— 原生控件条退役（材质/厚度/遮挡三宗罪）。
  video.controls = false;

  // 舞台可聚焦：点画面即入焦点（播控键的准入闸见文件头 canvasVideoFocus 注）。
  // tabindex 取 **-1** 而不是 0：本仓 keyContext.isActivatableTarget 把
  // `tabIndex >= 0` 一律判为「自己用 Space 激活的控件」⇒ 取 0 会让空格被判给
  // 「原生激活」（div 上没有这个语义，等于空格失灵；实测 2026-11-05 首轮红即此）。
  // 键盘全链路不受影响：Tab 落到控件条第一颗（播放）钮，空格/方向键/M 都在
  // 播放器子树内，同样命中；点画面则直接聚焦舞台，三条键全部生效。
  // 画面盒 = **video 元素自身**（canvas-video-player 批的 contain 法则：元素盒由
  // min(100cqw, 100cqh × AR) 算出、恒等于画面像素边界，圆角 `--ds-radius-input`
  // 直接落在它上面 —— 见 split.css）。本批**不引入包裹层**：包裹层会让
  // `video.parentElement` 不再是可用区舞台，既破坏 canvas-video-stage.spec.mjs 的
  // 「画面 = contain(可用区) / 可用区即被利用」两条既有读数口径（实测 4 档全红：
  // 0.427/0.417/0.190/0.427 < 0.6），又对同一批断言引入第二套尺寸算术。
  // 封面 `<img>` 因此不靠 offset parent，而是用**同一 AR 盒**复刻画面（见 split.css
  // 的 `.canvas-video-stage .nf-video-cover`：inset:0 + margin:auto + max-*100%）。
  const stage = h('div', {
    class: 'canvas-video-stage',
    tabindex: '-1',
    role: 'group',
    'aria-label': t('canvas.videoControls'),
  }, [video]);
  // 内容封面（作者令 2026-11-05：「不管是在对话框还是canvas，都应该有一个内容预览
  // 的一个封面」）：未播放时盖一帧代表帧，播放即让位。抓帧失败/跨域污染/超时一律
  // 不挂（面回到现状），规则全在 videoCover.js。
  const cover = attachVideoCover(video, { alt: fileName || '' });
  const controls = buildControls(video);
  const wrap = h('div', { class: 'canvas-video-viewer' }, [stage, controls.root]);

  // 宽高比单源：元数据到了就写进 --cvv-ar，CSS 的 min(100cqw, 100cqh × AR) 读它。
  const onMeta = () => {
    if (!video.videoWidth || !video.videoHeight) return;
    wrap.style.setProperty('--cvv-ar', String(video.videoWidth / video.videoHeight));
  };
  video.addEventListener('loadedmetadata', onMeta);

  // 载入失败 → 恰好一次重铸票据重试，再失败就落终止错误面板
  // （viewers/image.js T3/T4 纪律：第二次失败是确定性的）。
  let retried = false;
  const onError = () => {
    if (!retried && absPath) {
      retried = true;
      reMint(absPath).then((next) => {
        if (pane.isConnected) video.src = next;
      });
      return;
    }
    if (!pane.isConnected) return; // 页签在错误落定前已关闭
    controls.destroy();
    if (cover) cover.destroy();
    video.removeEventListener('loadedmetadata', onMeta);
    pane.classList.add('scrollable');
    pane.textContent = '';
    pane.appendChild(h('div', { class: 'canvas-error' }, [
      h('div', { text: 'Cannot play video: the file may be corrupted or the browser lacks a codec for it.' }),
      h('div', { text: `Path: ${absPath || fileName || ''}` }),
    ]));
  };
  video.addEventListener('error', onError);

  wrap.style.setProperty('--cvv-ar', String(FALLBACK_AR));
  pane.appendChild(wrap);

  // 资源回收账本（WeakMap 而不是 pane 属性：`_nfViewerDispose` 槽由
  // fileViewers.renderFile 统一持有，本查看器只提供 cleanup 本体，别抢那个槽）。
  const cleanup = () => {
    controls.destroy();
    if (cover) cover.destroy();
    video.removeEventListener('loadedmetadata', onMeta);
    video.removeEventListener('error', onError);
    video.removeAttribute('src');
    try { video.load(); } catch { /* 已脱离文档 */ }
  };
  CLEANUP.set(pane, cleanup);

  // M2 账本接缝（imgdoor #542 推广批，**带尺寸闸**）：≤64MiB 的视频走账本——命中 =
  // blob URL 直开（第二次打开零取数，seek 在 blob 内本地解析）；超限视频**保持票据
  // URL 流式**（Range seek 是既有语义，大件整收等全量是 UX 回归；64MiB 阈与
  // nfFileCache 桶预算留距，超预算本也不可缓存）。
  video.src = await nfViewerUrl({ absPath, objectUrl, pane, size, maxBytes: 64 * 1024 * 1024 });
}

/**
 * 页签关闭 / 换格式时的资源回收（阅读/编辑体验优化批 2026-11-01 的协议面）：
 * 视频控件挂了 8 个 video 事件 + 1 个 document 全屏事件，不摘就是每开一个视频
 * 泄漏一份监听链。幂等（cleanup 取走即删）。
 * @param {HTMLElement} pane
 */
export function disposeVideo(pane) {
  const fn = CLEANUP.get(pane);
  if (!fn) return;
  CLEANUP.delete(pane);
  fn();
}

export default {
  name: 'video',
  label: 'Video',
  extensions: ['.mp4', '.webm', '.ogv', '.ogg', '.mov'],
  binary: true,
  priority: 0,
  render: viewVideo,
  /** fileViewers.renderFile 的协议钩子（重渲前一拍 + 页签关闭时各调一次）。 */
  dispose: disposeVideo,
};
