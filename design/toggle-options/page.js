// page.js — 五组扁平 Toggle 方案的唯一数据源 + 渲染器（经典脚本，index.html / zoom.html 共用）
//
// 为什么把方案数据放在一处：方案页要出「总对照」与「单组放大」两张口径，若两页各自
// 抄一份控件结构，就会出现「对照图里是 A 造型、放大图里是 A' 造型」这种假证据。故
// 造型只在 toggle.css 定义，结构只在本文件定义，两页都只消费这两份单源。
//
// 控件契约（与产品现役组件 js/toggle.js 同构，落地批可直接对齐）：
//   · 真实 <button type="button"> + role="switch" + aria-checked → Space / Enter 走
//     原生 click，无需额外 keydown 布线；
//   · 状态只用 class `is-on` + aria-checked 两个落点（与 setToggleState 同一手法）；
//   · 不确定态 = aria-busy="true"（aria-checked 保留最后已知值——role="switch" 的
//     aria-checked 只允许 true/false，mixed 在 switch 上等于 false，故不用 mixed）。

/* 图标：1.5 视觉重量的 2px 描线，currentColor 上色，双主题跟随 */
const GLYPH = {
  check:
    '<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor"' +
    ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M2.4 6.3 L4.9 8.8 L9.6 3.5"/></svg>',
  cross:
    '<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor"' +
    ' stroke-width="2" stroke-linecap="round" aria-hidden="true">' +
    '<path d="M3.3 3.3 L8.7 8.7 M8.7 3.3 L3.3 8.7"/></svg>',
};

/** 五组方案（顺序 = 页面上从上到下的行序 = A..E）。 */
const OPTIONS = [
  {
    id: 'A',
    cls: 'togA',
    name: '胶囊滑块',
    intent: '经典 switch：全圆角轨道 + 圆钮，最稳、认知成本最低。',
    spec: '40×22 · r999 · 钮 16 圆 · 行程 18 · <b>180ms</b> cubic-bezier(.2,0,0,1)',
    inner: '<span class="tog__knob"></span>',
  },
  {
    id: 'B',
    cls: 'togB',
    name: '方形滑片',
    intent: '直角语言：微圆角轨道 + 满高方形滑片，硬朗、工程感。',
    spec: '40×22 · r4 · 滑片 18×18 方(r2) · 行程 18 · <b>160ms</b> cubic-bezier(.2,0,0,1)',
    inner: '<span class="tog__knob"></span>',
  },
  {
    id: 'C',
    cls: 'togC',
    name: '图标内嵌式',
    intent: '状态直说：轨道里左 ✓ 右 ✕，滑块盖住一侧，露出的一侧即当前状态。',
    spec: '44×24 · r12 · 钮 18 圆 · 行程 20 · <b>200ms</b> cubic-bezier(.2,0,0,1)',
    inner:
      '<span class="tog__glyph tog__glyph--on">' + GLYPH.check + '</span>' +
      '<span class="tog__glyph tog__glyph--off">' + GLYPH.cross + '</span>' +
      '<span class="tog__knob"></span>',
  },
  {
    id: 'D',
    cls: 'togD',
    name: '描边款',
    intent: '纯线语言：滑块永远是一枚空心环，变的只是轨道「有没有填充」。',
    spec: '40×20 · r999 · 环 12 空心 · 行程 20 · <b>200ms</b> cubic-bezier(.4,0,.2,1)',
    inner: '<span class="tog__knob"></span>',
  },
  {
    id: 'E',
    cls: 'togE',
    name: '双段按钮组',
    intent: '两段「开 | 关」，填充片在两端滑动，本身就是状态。单一 switch 语义。',
    spec: '64×24 · r6 · 片 30×20 · 行程 30 · <b>220ms</b> cubic-bezier(.2,0,0,1)',
    inner:
      '<span class="tog__pill"></span>' +
      '<span class="tog__segs">' +
      '<span class="tog__seg tog__seg--on">开</span>' +
      '<span class="tog__seg tog__seg--off">关</span>' +
      '</span>',
  },
];

const STATE_LABEL = {
  off: 'off',
  on: 'on',
  mid: '过渡中',
  disabled: '禁用',
  busy: '不确定',
};

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * 一枚控件的 HTML。
 * @param {object} opt 方案
 * @param {'off'|'on'|'mid'|'disabled'|'busy'} state
 * @param {string} theme 主题名（只进 id / data 属性，样式由祖先 [data-theme] 供给）
 */
function controlHTML(opt, state, theme) {
  const on = state === 'on';
  const cls = ['tog', opt.cls, on ? 'is-on' : ''].filter(Boolean).join(' ');
  const label = '方案 ' + opt.id + ' ' + opt.name + '（' + STATE_LABEL[state] + '）';
  let attrs =
    ' type="button"' +
    ' id="tog-' + theme + '-' + opt.id + '-' + state + '"' +
    ' class="' + cls + '"' +
    ' role="switch"' +
    ' aria-checked="' + (on ? 'true' : 'false') + '"' +
    ' aria-label="' + esc(label) + '"' +
    ' data-opt="' + opt.id + '"' +
    ' data-state="' + state + '"';
  if (state === 'mid') attrs += ' data-mid="1"';           // 载入后由 freezeMid() 定格在 50%
  if (state === 'disabled') attrs += ' disabled aria-disabled="true"';
  if (state === 'busy') attrs += ' aria-busy="true"';
  return '<button' + attrs + '>' + opt.inner + '</button>';
}

/* ── 状态翻转（与 js/toggle.js setToggleState 同一手法）────────────────── */
function setOn(btn, on) {
  btn.classList.toggle('is-on', on);
  btn.setAttribute('aria-checked', on ? 'true' : 'false');
}

document.addEventListener('click', (e) => {
  const btn = e.target instanceof Element ? e.target.closest('button.tog') : null;
  if (!btn || btn.disabled || btn.hasAttribute('data-mid')) return;
  setOn(btn, !btn.classList.contains('is-on'));
});

/**
 * 把「过渡中」格子定格成**滑块行程走到一半**的那一帧。
 *
 * 为什么不手写一个「半途值」：手写值是画的，不是渲染出来的。这里先把状态真的翻到
 * on（浏览器因此起了真实的 CSS transition），再用 Web Animations API 暂停并**按曲线
 * 反解**出「位移 = 行程一半」的那个时刻，把动画钉在那里 —— 屏幕上那一帧是浏览器自己
 * 插值出来的中间态，谁都能复现。
 *
 * 为什么不定在「时长的一半」：本批五组曲线都是强 ease-out（.2,0,0,1 一类），时间过半时
 * 位移已经走到 88% —— 图上看起来几乎就是 on，等于没给出中间态。所以判据改成**几何一半**
 * （位移 50%）而不是时间一半，四组方案的可比性也由此成立。
 *
 * 三个必踩的坑（本批实测，逐条踩过）：
 *   ① 元素刚插入 DOM 就翻 class ⇒ 没有「旧样式」，浏览器不开过渡（读回来直接是终值）。
 *      解法 = 翻 class 前先 `void btn.offsetWidth` 强制提交一次 off 档样式，作为起点。
 *   ② transition 动画对象要等到下一帧才生成 ⇒ 同一帧里取 getAnimations() 恒为空。
 *      解法 = 逐帧重试（最多 12 帧），拿不到就如实返回 false，不留假象。
 *   ③ 暂停后 currentTime 是**时间**，不是进度；强 ease-out 下二者差得远。
 *      解法 = 暂停后把 currentTime 从 0 扫到 duration，读 transform 的 translateX，
 *      二分到「终点位移的一半」。
 */
function readTranslateX(el) {
  const t = getComputedStyle(el).transform;
  if (!t || t === 'none') return 0;
  const m = t.match(/matrix\(([^)]+)\)/);
  if (!m) return 0;
  const parts = m[1].split(',').map(Number);
  return parts.length >= 6 ? parts[4] : 0;
}

async function freezeOne(btn) {
  void btn.offsetWidth;                 // ① 提交 off 档样式 = 过渡起点
  setOn(btn, true);                     // 起真实过渡

  let anims = [];
  for (let i = 0; i < 12; i++) {        // ② 逐帧等动画对象出生
    anims = btn.getAnimations({ subtree: true });
    if (anims.length) break;
    await new Promise((r) => requestAnimationFrame(r));
  }
  if (!anims.length) return false;

  for (const a of anims) a.pause();

  // ③ 找承载位移的那条 transition（transform），没有就退回「时长一半」
  const move = anims.find((a) => a.transitionProperty === 'transform');
  const settle = (t) => { for (const a of anims) a.currentTime = t; };
  if (!move) {
    const dur = parseFloat(getComputedStyle(btn).transitionDuration) * 1000 || 180;
    settle(dur / 2);
    return true;
  }

  const target = move.effect.target;
  const dur = Number(move.effect.getTiming().duration) || 180;
  settle(0);
  const tx0 = readTranslateX(target);
  settle(dur);
  const tx1 = readTranslateX(target);            // 行程终点
  if (tx0 === tx1) { settle(dur / 2); return true; }

  // 位移可能向右增（A/B/C/D 的滑块）也可能向左减（E 的填充片回滑），故按方向二分
  const midX = (tx0 + tx1) / 2;
  const ascending = tx1 > tx0;
  let lo = 0;
  let hi = dur;
  for (let i = 0; i < 24; i++) {
    const t = (lo + hi) / 2;
    settle(t);
    const v = readTranslateX(target);
    if ((v < midX) === ascending) lo = t;
    else hi = t;
  }
  settle(hi);
  return true;
}

function freezeMid() {
  const cells = [...document.querySelectorAll('button.tog[data-mid]')];
  window.__togFrozenCount = { total: cells.length, frozen: 0 };
  // 先等第一帧：确保 off 档已经真的落进渲染树，再逐个定格
  return new Promise((r) => requestAnimationFrame(r))
    .then(() => Promise.all(cells.map(async (btn) => {
      const ok = await freezeOne(btn);
      if (ok) window.__togFrozenCount.frozen++;
      return ok;
    })));
}

/* ── 渲染 ─────────────────────────────────────────────────────────────── */
function rowHTML(opt, stateKeys, theme) {
  const cells = stateKeys.map((s) => (
    '<div class="cell">' +
    '<span class="cell__cap">' + STATE_LABEL[s] + '</span>' +
    controlHTML(opt, s, theme) +
    '</div>'
  )).join('');
  return (
    '<article class="row" id="opt-' + theme + '-' + opt.id + '">' +
    '<div class="row__meta">' +
    '<h3 class="row__name">' + opt.id + ' · ' + esc(opt.name) + '</h3>' +
    '<p class="row__spec">' + opt.spec + '</p>' +
    '</div>' +
    '<div class="row__cells">' + cells + '</div>' +
    '</article>'
  );
}

function zoomRowHTML(opt, stateKeys, theme) {
  const cells = stateKeys.map((s) => (
    '<div class="zcell"><span class="cell__cap">' + STATE_LABEL[s] + '</span>' +
    controlHTML(opt, s, theme) + '</div>'
  )).join('');
  return (
    '<div class="zrow" id="z-' + theme + '-' + opt.id + '">' +
    '<div><div class="zname">' + opt.id + ' · ' + esc(opt.name) + '</div>' +
    '<div class="zspec">' + opt.spec + '</div></div>' +
    cells +
    '</div>'
  );
}

function render() {
  // 一个页面上可以有多个渲染宿主（index.html 的浅/深两栏、zoom.html 的两栏），
  // 每个宿主自带 data-theme 与 data-mode —— 主题来自宿主而不是 body，因为同一张
  // 页面上要并排两种主题。
  for (const host of document.querySelectorAll('[data-render-host]')) {
    const mode = host.dataset.mode || 'compare';
    const theme = host.dataset.theme || 'light';

    let opts = OPTIONS;
    if (mode === 'zoom') {
      const want = new URLSearchParams(location.search).get('option');
      if (want) opts = OPTIONS.filter((o) => o.id.toLowerCase() === want.toLowerCase());
      if (!opts.length) opts = OPTIONS;
      host.innerHTML = opts.map((o) => zoomRowHTML(o, ['off', 'on', 'mid', 'disabled', 'busy'], theme)).join('');
      document.title = '放大 · 方案 ' + opts.map((o) => o.id).join('/') + ' · ' + theme;
    } else {
      host.innerHTML = opts.map((o) => rowHTML(o, ['off', 'on', 'mid'], theme)).join('');
    }
  }
}

render();
// 对外给渲染脚本一个可等待的完成信号（截图前必须等它，否则「过渡中」可能是空白帧）
window.__togFrozen = freezeMid().then(() => { window.__togReady = true; });
