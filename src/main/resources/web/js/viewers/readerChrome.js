// viewers/readerChrome.js — 统一阅读 chrome 内核（阅读器 P1 批，锚设计稿
// 20261030_181655_reader-experience-design §2.2/§2.3/§3.1/§4.1）。
//
// 一个 chrome 件，三种正文适配器共用：
//   · epub.js   —— spine 章节流（全量：主题/Aa/TOC/翻页/进度条）
//   · textReader.js —— 纯文本分页（\f 分段为章；无段则单章）
//   · markdown.js —— 现有渲染面包 chrome（先统一操控，排版二期：无主题/Aa）
//
// chrome 统一承载（设计稿 §2.2 底栏重排 + §2.3 阅读菜单 + §4.1 翻页动效）：
//   底栏： [‹ ›] [══ 进度条滑杆（TOC 章节刻度 + 常态位置 tooltip）══] [页码] [Aa] [☰] [⋯]
//        移动端（≤768px）收成 [‹]（下一页交点右区/横滑），底高让位 safe-area。
//   刷新钮不在底栏：移动端进 ⋯ 溢出菜单；桌面维持面板角浮件原位
//        （shared.js 不动，角浮件与底栏互斥由 split.css :has() 让位承担）。
//   翻页动效：双页夹 transform:translateX(±100%)，240ms，
//        cubic-bezier(0.22,0.61,0.36,1)（split.css 温和缓出同族）；页缘 8px
//        投影随位移线性衰减（纯渐变，无图片资源）；一次只动两页，连翻入队；
//        touch 横滑跟手，|v|>0.3px/ms 或位移>1/4 页宽 → 完成（240ms 收束），
//        否则回弹（180ms）；prefers-reduced-motion 全程 0.01s 直切。
//   翻页/滚动双模式：默认滚动（现状行为，P0 A4 断言不扰）；翻页模式走双页夹。
//   P3 生态批（同设计稿 §4.2）：标注面接线——adapter.annotatable 在场即挂
//   annotations.js（划选浮动条四色/笔记编辑器/笔记抽屉/章内高亮回渲），
//   存储面随工作区落库（WS reader.anno 腿），epub/mobi/azw3 系格式启用。
//   进度条：全书均匀位置空间 (章内偏移+章序)/N —— 跨章跳章无需预估页数；
//        TOC 章起刻度（密度过高抽样）；拖动出 tooltip「章名 · %」，松手才跳。
//
// 自动化钩子（data-* / 既有类名全保留，P0 断言不破）：
//   .epub-reader / .epub-reader-viewport / .epub-reader-content / .epub-reader-menu
//   [data-epub-menu] / .epub-nav-info / .epub-prev / .epub-next / .epub-reader[data-epub-theme]
//   新增：.epub-progress-range（滑杆 input）/.epub-progress-tick / .epub-progress-tip
//         .epub-toc[data-epub-toc] / .epub-flip-stage / .epub-flip-layer

import { escapeHtml } from './shared.js';
import { mountAnnotationFace } from './annotations.js';
import { t } from '../i18n.js';

/** 阅读器偏好持久化键（P0 既定：跨书跨会话生效；P1 增补 mode 档）。 */
export const READER_PREFS_KEY = 'nf.reader.prefs';

const FONT_SIZE_STEPS = [14, 16, 18, 20, 22];
const LINE_HEIGHT_STEPS = [1.5, 1.6, 1.8, 2.0];
const PARA_SPACE_STEPS = { compact: '0.5em', standard: '1em', loose: '1.5em' };

/** 主题档位（P0 既定：纸质 = 字面量；明亮/暗夜 = 面板 token 取值）。 */
export const READER_THEMES = {
  paper: { bg: '#F7F3EA', fg: '#3B342C' },
  light: { bg: '#ffffff', fg: '#1b1e26' },
  dark: { bg: '#1c1f27', fg: '#e8eaed' },
};

/** @returns {{theme: string, fontSize: number, lineHeight: number, paraSpace: string, fontFamily: string, mode: string}} */
export function loadReaderPrefs() {
  const defaults = { theme: 'auto', fontSize: 16, lineHeight: 1.8, paraSpace: 'standard', fontFamily: 'serif', mode: 'scroll' };
  try {
    const raw = JSON.parse(localStorage.getItem(READER_PREFS_KEY) || '{}');
    const p = { ...defaults, ...(raw && typeof raw === 'object' ? raw : {}) };
    if (!['auto', 'paper', 'light', 'dark'].includes(p.theme)) p.theme = 'auto';
    if (!FONT_SIZE_STEPS.includes(p.fontSize)) p.fontSize = 16;
    if (!LINE_HEIGHT_STEPS.includes(p.lineHeight)) p.lineHeight = 1.8;
    if (!Object.keys(PARA_SPACE_STEPS).includes(p.paraSpace)) p.paraSpace = 'standard';
    if (!['serif', 'sans'].includes(p.fontFamily)) p.fontFamily = 'serif';
    if (!['scroll', 'flip'].includes(p.mode)) p.mode = 'scroll';
    return p;
  } catch { return defaults; }
}

export function saveReaderPrefs(prefs) {
  try { localStorage.setItem(READER_PREFS_KEY, JSON.stringify(prefs)); } catch { /* 存储不可用时仅本次生效 */ }
}

/** 「跟随面板明暗」：显式 data-theme 优先，否则随系统色方案。 */
function panelDark() {
  const explicit = document.documentElement.dataset.theme;
  if (explicit === 'dark' || explicit === 'light') return explicit === 'dark';
  return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

/** 应用主题档：auto → 面板暗则暗夜、面板亮则纸质。 */
export function effectiveReaderTheme(prefs) {
  return prefs.theme === 'auto' ? (panelDark() ? 'dark' : 'paper') : prefs.theme;
}

/** touch 释放判定（设计稿 §4.1）：速率阈值 px/ms 或位移阈值（页宽比例）。 */
const FLIP_VELOCITY_PX_MS = 0.3;
const FLIP_DISTANCE_RATIO = 0.25;
/** 连翻队列上限（一次只动两页；超出丢弃，防手势风暴）。 */
const FLIP_QUEUE_MAX = 4;
/** TOC 章起刻度抽样上限（密度过高时等距抽样显示）。 */
const MAX_TICKS = 48;

/**
 * 挂载统一阅读 chrome。
 * @param {HTMLElement} pane canvas tab pane
 * @param {object} adapter 正文适配器
 * @param {string} adapter.title 书名（页眉/事件用）
 * @param {number} adapter.chapterCount
 * @param {(i: number) => string} adapter.chapterTitle
 * @param {(el: HTMLElement, i: number) => void} adapter.mountChapter —— 同步把第 i
 *   章正文装进 el（.epub-reader-content；host 模式下 el 仅为语义空壳，正文在 hostScroller）
 * @param {Array<{label: string, depth: number, chapterIdx: number, anchor: string}>|null} adapter.toc
 * @param {boolean} adapter.themeable —— 主题/Aa 档（md = false，排版二期）
 * @param {'flow'|'host'} adapter.contentKind —— host = 正文自带滚动盒（markdown）
 * @param {HTMLElement|null} [adapter.hostScroller] 仅在 contentKind='host' 时提供
 * @param {HTMLElement|null} [adapter.hostContent] flip 层克隆源（host 模式）
 * @param {(i: number) => void} [adapter.onChapterChanged]
 * @param {boolean} [adapter.tracking] 派发 epub-page-change（epub 专属，既有事件名）
 * @param {(i: number, anchor: string) => number|null} [adapter.anchorScrollTop] TOC 锚点像素
 * @param {object|null} [adapter.annotatable] 标注适配器（设计稿 §4.2；epub 腿提供，md 腿缺席）
 * @returns {object} controller
 */
export function mountReader(pane, adapter) {
  let chapterIdx = 0;
  let annoFace = null; // P3 标注面（adapter.annotatable 在场即挂，见事件接线前段）
  const prefs = loadReaderPrefs();
  const N = Math.max(1, adapter.chapterCount);

  pane.innerHTML = `
    <div class="epub-reader" data-epub-theme="${effectiveReaderTheme(prefs)}">
      <div class="epub-reader-header">
        <span class="epub-chapter-title"></span>
      </div>
      <div class="epub-reader-menu" data-epub-menu="closed" hidden></div>
      <div class="epub-toc" data-epub-toc="closed" hidden>
        <div class="epub-toc-head">
          <span class="epub-toc-title">${escapeHtml(t('reader.toc'))}</span>
          <button type="button" class="epub-nav-btn epub-toc-close" aria-label="${escapeHtml(t('epub.menu.close'))}">×</button>
        </div>
        <div class="epub-toc-list" role="listbox"></div>
      </div>
      <div class="epub-reader-viewport canvas-viewer-safe-top"></div>
      <div class="epub-reader-nav">
        <button type="button" class="epub-nav-btn epub-prev" title="${escapeHtml(t('epub.prev'))}" aria-label="${escapeHtml(t('epub.prev'))}">‹</button>
        <button type="button" class="epub-nav-btn epub-next" title="${escapeHtml(t('epub.next'))}" aria-label="${escapeHtml(t('epub.next'))}">›</button>
        <div class="epub-progress">
          <div class="epub-progress-ticks" aria-hidden="true"></div>
          <input type="range" class="epub-progress-range" min="0" max="1000" step="1"
                 aria-label="${escapeHtml(t('reader.aria.slider'))}">
          <div class="epub-progress-tip" aria-hidden="true"></div>
        </div>
        <span class="epub-nav-info"></span>
        <button type="button" class="epub-nav-btn epub-aa" aria-label="${escapeHtml(t('reader.aria.aa'))}">Aa</button>
        <button type="button" class="epub-nav-btn epub-toc-btn" aria-label="${escapeHtml(t('reader.aria.toc'))}">☰</button>
        <button type="button" class="epub-nav-btn epub-more-btn" aria-label="${escapeHtml(t('reader.aria.more'))}">⋯</button>
        <div class="epub-bar-menu" hidden></div>
      </div>
    </div>`;

  const reader = /** @type {HTMLElement} */ (pane.querySelector('.epub-reader'));
  const viewport = /** @type {HTMLElement} */ (pane.querySelector('.epub-reader-viewport'));
  const menu = /** @type {HTMLElement} */ (pane.querySelector('.epub-reader-menu'));
  const tocEl = /** @type {HTMLElement} */ (pane.querySelector('.epub-toc'));
  const tocList = pane.querySelector('.epub-toc-list');
  const chapterTitleEl = pane.querySelector('.epub-chapter-title');
  const infoEl = pane.querySelector('.epub-nav-info');
  const prevBtn = /** @type {HTMLButtonElement} */ (pane.querySelector('.epub-prev'));
  const nextBtn = /** @type {HTMLButtonElement} */ (pane.querySelector('.epub-next'));
  const aaBtn = /** @type {HTMLElement} */ (pane.querySelector('.epub-aa'));
  const tocBtn = /** @type {HTMLElement} */ (pane.querySelector('.epub-toc-btn'));
  const moreBtn = /** @type {HTMLElement} */ (pane.querySelector('.epub-more-btn'));
  const barMenu = /** @type {HTMLElement} */ (pane.querySelector('.epub-bar-menu'));
  const progress = pane.querySelector('.epub-progress');
  const range = /** @type {HTMLInputElement} */ (pane.querySelector('.epub-progress-range'));
  const ticksEl = pane.querySelector('.epub-progress-ticks');
  const tipEl = /** @type {HTMLElement} */ (pane.querySelector('.epub-progress-tip'));

  // ── 滚动模型：flow = chrome 自管 scroller + .epub-reader-content；host = md 自带 ──
  /** @type {HTMLElement} 真正滚动的盒子 */
  let scroller;
  /** @type {HTMLElement} flip 层克隆源（章节内容根） */
  let contentEl;
  if (adapter.contentKind === 'host' && adapter.hostScroller) {
    scroller = adapter.hostScroller;
    contentEl = adapter.hostContent || adapter.hostScroller;
    viewport.appendChild(scroller);
  } else {
    scroller = viewport;
    contentEl = document.createElement('div');
    contentEl.className = 'epub-reader-content';
    viewport.appendChild(contentEl);
  }

  const isMobileBar = () => !!window.matchMedia && window.matchMedia('(max-width: 768px)').matches;

  // ── 主题/档位（themeable 面；md 不挂载档位控制但保留 prefs 读，防呆） ──────
  function applyPrefs() {
    const theme = effectiveReaderTheme(prefs);
    reader.dataset.epubTheme = theme;
    if (adapter.themeable) {
      const palette = READER_THEMES[theme];
      contentEl.style.setProperty('--epub-bg', palette.bg);
      contentEl.style.setProperty('--epub-fg', palette.fg);
      contentEl.style.fontSize = prefs.fontSize + 'px';
      contentEl.style.lineHeight = String(prefs.lineHeight);
      contentEl.style.setProperty('--epub-para-space', PARA_SPACE_STEPS[prefs.paraSpace]);
      contentEl.dataset.fontFamily = prefs.fontFamily;
    }
    if (!menu.hidden) renderMenu();
    if (!barMenu.hidden) renderBarMenu();
    updateUI();
  }

  function setPrefs(patch) {
    Object.assign(prefs, patch);
    saveReaderPrefs(prefs);
    applyPrefs();
  }

  // 「跟随面板」档：面板明暗翻转时自动跟随（显式选档时不跟）
  const darkMq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  const onPanelThemeChange = () => { if (prefs.theme === 'auto') applyPrefs(); };
  if (darkMq) {
    if (darkMq.addEventListener) darkMq.addEventListener('change', onPanelThemeChange);
    else if (darkMq.addListener) darkMq.addListener(onPanelThemeChange);
  }
  const themeAttrObserver = new MutationObserver(() => { if (prefs.theme === 'auto') applyPrefs(); });
  themeAttrObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

  // ── 章节装载 ──────────────────────────────────────────────────────────────
  function mountChapter(i) {
    chapterIdx = i;
    if (adapter.contentKind !== 'host') adapter.mountChapter(contentEl, i);
    if (adapter.onChapterChanged) adapter.onChapterChanged(i);
    if (annoFace) annoFace.renderChapter(i); // 标注回渲（§4.2：重开本/跳章时）
  }

  /** 章内位置（0..1）：maxScroll=0 的章（无滚动）在末章记 1，否则 0。 */
  function withinChapter() {
    const max = scroller.scrollHeight - scroller.clientHeight;
    if (max <= 0) return chapterIdx >= N - 1 ? 1 : 0;
    return Math.min(1, Math.max(0, scroller.scrollTop / max));
  }

  /** 全书均匀位置空间（0..1）：(章序 + 章内偏移)/N —— 跨章跳章免预估页数。 */
  function position01() {
    return Math.min(1, (chapterIdx + withinChapter()) / N);
  }

  function getTotalPages() {
    return Math.max(1, Math.ceil(scroller.scrollHeight / scroller.clientHeight));
  }
  function getCurrentPage() {
    return Math.min(getTotalPages() - 1, Math.round(scroller.scrollTop / scroller.clientHeight));
  }

  function emitTracking() {
    if (!adapter.tracking) return;
    const total = getTotalPages();
    const page = getCurrentPage();
    pane.dispatchEvent(new CustomEvent('epub-page-change', {
      detail: {
        bookTitle: adapter.title,
        chapter: chapterIdx,
        chapterTitle: adapter.chapterTitle(chapterIdx),
        page,
        totalPages: total,
        totalChapters: N,
        progress: chapterIdx / N + (page + 1) / total / N,
      }
    }));
  }

  /** tooltip 常态显示（§2.2）：左偏移对齐滑杆拇指（内轨 = 宽 − 14px 拇指）。 */
  function syncTipPos() {
    const W = range.clientWidth;
    const f = Math.min(1, Math.max(0, Number(range.value) / 1000));
    tipEl.style.left = (7 + f * Math.max(0, W - 14)) + 'px';
  }

  function updateUI() {
    const page = getCurrentPage() + 1;
    const total = getTotalPages();
    const mobile = isMobileBar();
    chapterTitleEl.textContent = adapter.chapterTitle(chapterIdx);
    infoEl.textContent = mobile
      ? `Ch ${chapterIdx + 1}/${N}`
      : `Pg ${page}/${total} · Ch ${chapterIdx + 1}/${N}`;
    prevBtn.disabled = chapterIdx === 0 && scroller.scrollTop <= 0;
    nextBtn.disabled = chapterIdx === N - 1 && scroller.scrollTop >= scroller.scrollHeight - scroller.clientHeight - 1;
    if (!rangeDragging) {
      range.value = String(Math.round(position01() * 1000));
      range.setAttribute('aria-valuetext', tipText());
    }
    if (tipEl.dataset.pin !== '1') tipEl.textContent = tipText();
    syncTipPos();
    markActiveToc();
    emitTracking();
  }

  /** tooltip 文：「章名 · N%」。 */
  function tipText() {
    const pct = Math.round(position01() * 100);
    let title = adapter.chapterTitle(chapterIdx);
    if (adapter.toc && adapter.toc.length) {
      // 覆盖当前位置的最后一条 TOC 目（章起 ≤ 当前章）。
      let best = null;
      for (const e of adapter.toc) {
        if (e.chapterIdx <= chapterIdx && (!best || e.chapterIdx >= best.chapterIdx)) best = e;
      }
      if (best && best.label) title = best.label;
    }
    return t('reader.pos', { title, pct: String(pct) });
  }

  /** 跳到全书均匀位置 f：跨章则换装后落位（无动画——设计稿：拖动即跳）。 */
  function jumpToPosition01(f, targetAnchor) {
    const clamped = Math.min(1, Math.max(0, f));
    const idx = Math.min(N - 1, Math.floor(clamped * N));
    const within = clamped * N - idx;
    if (idx !== chapterIdx) {
      mountChapter(idx);
      requestAnimationFrame(() => {
        const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
        scroller.scrollTop = within * max;
        if (targetAnchor && adapter.anchorScrollTop) {
          const top = adapter.anchorScrollTop(idx, targetAnchor);
          if (typeof top === 'number' && top >= 0) scroller.scrollTop = Math.min(top, max);
        }
        updateUI();
      });
    } else {
      const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
      scroller.scrollTop = within * max;
      if (targetAnchor && adapter.anchorScrollTop) {
        const top = adapter.anchorScrollTop(idx, targetAnchor);
        if (typeof top === 'number' && top >= 0) scroller.scrollTop = Math.min(top, max);
      }
      updateUI();
    }
  }

  // ── TOC 抽屉（§2.1 桌面侧抽 / 移动底弹；毛玻璃，无 overlay 暗化） ─────────
  const tocOpen = () => !tocEl.hidden;

  function renderToc() {
    if (!adapter.toc || !adapter.toc.length) return;
    const items = adapter.toc;
    tocList.innerHTML = items.map((e, i) =>
      `<button type="button" role="option" class="epub-toc-item" data-epub-toc-item="${i}"
        data-depth="${e.depth}" aria-selected="false">${escapeHtml(e.label)}</button>`
    ).join('');
    tocList.querySelectorAll('[data-epub-toc-item]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const e = items[Number((/** @type {HTMLElement} */ (btn)).dataset.epubTocItem)];
        if (!e) return;
        closeToc();
        jumpToPosition01(e.chapterIdx / N, e.anchor);
      });
    });
  }

  function markActiveToc() {
    if (!tocOpen() || !adapter.toc) return;
    let active = -1;
    adapter.toc.forEach((e, i) => { if (e.chapterIdx <= chapterIdx) active = i; });
    tocList.querySelectorAll('[data-epub-toc-item]').forEach((btn) => {
      const on = Number((/** @type {HTMLElement} */ (btn)).dataset.epubTocItem) === active;
      btn.classList.toggle('on', on);
      btn.setAttribute('aria-selected', on ? 'true' : 'false');
    });
  }

  function openToc() {
    if (!adapter.toc || !adapter.toc.length) return;
    renderToc();
    tocEl.hidden = false;
    tocEl.dataset.epubToc = 'open';
    markActiveToc();
    closeMenu();
    closeBarMenu();
    setFabGate(true);
  }
  function closeToc() {
    if (tocEl.hidden) return;
    tocEl.hidden = true;
    tocEl.dataset.epubToc = 'closed';
    if (menu.hidden) setFabGate(false);
  }

  // ── 阅读菜单（中间 1/3 唤出；毛玻璃浮层锚定阅读器顶部，禁 overlay 暗化） ──
  const menuOpen = () => !menu.hidden;

  /** 刷新动作（设计稿 §2.2：语义 = 文件变了重读，走面板角浮件同款腿）。 */
  function requestRefresh() {
    closeMenu();
    closeBarMenu();
    // 有壳层钮就点它（桌面原位不动），无钮（预览组等）退化为画布请求事件。
    const shellBtn = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-content > .canvas-refresh-toggle:not([hidden])'));
    if (shellBtn) shellBtn.click();
    else pane.dispatchEvent(new CustomEvent('canvas-request-refresh', { bubbles: true }));
  }

  function renderMenu() {
    const theme = effectiveReaderTheme(prefs);
    const cycle = (steps, current, dir) => {
      const i = steps.indexOf(current);
      return steps[(i + dir + steps.length) % steps.length];
    };
    const paraKeys = { compact: 'epub.aa.paraCompact', standard: 'epub.aa.paraStandard', loose: 'epub.aa.paraLoose' };
    const themeRows = !adapter.themeable ? '' : `
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.theme.label'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.theme.label'))}</span>
        ${['paper', 'light', 'dark'].map((m) =>
          `<button type="button" class="epub-menu-btn${theme === m ? ' on' : ''}" data-epub-theme-pick="${m}">${escapeHtml(t('epub.theme.' + m))}</button>`
        ).join('')}
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.fontSize'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.fontSize'))}</span>
        <button type="button" class="epub-menu-btn" data-epub-step="fontSize:-1">−</button>
        <span class="epub-menu-value">${prefs.fontSize}px</span>
        <button type="button" class="epub-menu-btn" data-epub-step="fontSize:1">＋</button>
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.lineHeight'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.lineHeight'))}</span>
        <button type="button" class="epub-menu-btn" data-epub-step="lineHeight:-1">−</button>
        <span class="epub-menu-value">${prefs.lineHeight}</span>
        <button type="button" class="epub-menu-btn" data-epub-step="lineHeight:1">＋</button>
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.paraSpace'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.paraSpace'))}</span>
        ${Object.keys(PARA_SPACE_STEPS).map((k) =>
          `<button type="button" class="epub-menu-btn${prefs.paraSpace === k ? ' on' : ''}" data-epub-para="${k}">${escapeHtml(t(paraKeys[k]))}</button>`
        ).join('')}
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.fontFamily'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.fontFamily'))}</span>
        <button type="button" class="epub-menu-btn${prefs.fontFamily === 'serif' ? ' on' : ''}" data-epub-font="serif">${escapeHtml(t('epub.aa.serif'))}</button>
        <button type="button" class="epub-menu-btn${prefs.fontFamily === 'sans' ? ' on' : ''}" data-epub-font="sans">${escapeHtml(t('epub.aa.sans'))}</button>
      </div>`;
    const tocRow = (!adapter.toc || !adapter.toc.length) ? '' : `
      <div class="epub-menu-row">
        <button type="button" class="epub-menu-btn epub-menu-wide" data-reader-toc>☰ ${escapeHtml(t('reader.toc'))}</button>
      </div>`;
    const annoRow = annoFace ? `
      <div class="epub-menu-row">
        <button type="button" class="epub-menu-btn epub-menu-wide" data-reader-anno>✎ ${escapeHtml(t('reader.anno.list'))}${annoFace.count() ? `（${annoFace.count()}）` : ''}</button>
      </div>` : '';
    const refreshRow = isMobileBar() ? `
      <div class="epub-menu-row">
        <button type="button" class="epub-menu-btn epub-menu-wide" data-reader-refresh>⟳ ${escapeHtml(t('reader.refresh'))}</button>
      </div>` : '';
    menu.innerHTML = `
      <div class="epub-menu-title">${escapeHtml(adapter.chapterTitle(chapterIdx))}</div>
      ${tocRow}
      ${annoRow}
      ${themeRows}
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('reader.jumpPct'))}">
        <span class="epub-menu-label">${escapeHtml(t('reader.jumpPct'))}</span>
        <input type="number" class="epub-menu-input" data-reader-jump-input min="0" max="100" step="1" value="${Math.round(position01() * 100)}">
        <button type="button" class="epub-menu-btn" data-reader-jump>${escapeHtml(t('reader.jump'))}</button>
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('reader.mode'))}">
        <span class="epub-menu-label">${escapeHtml(t('reader.mode'))}</span>
        <button type="button" class="epub-menu-btn${prefs.mode === 'scroll' ? ' on' : ''}" data-reader-mode="scroll">${escapeHtml(t('reader.mode.scroll'))}</button>
        <button type="button" class="epub-menu-btn${prefs.mode === 'flip' ? ' on' : ''}" data-reader-mode="flip">${escapeHtml(t('reader.mode.flip'))}</button>
      </div>
      ${refreshRow}
      <button type="button" class="epub-menu-btn epub-menu-close" data-epub-close>${escapeHtml(t('epub.menu.close'))}</button>`;

    menu.querySelectorAll('[data-epub-theme-pick]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ theme: (/** @type {HTMLElement} */ (btn)).dataset.epubThemePick }));
    });
    menu.querySelectorAll('[data-epub-step]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const [key, dirRaw] = (/** @type {HTMLElement} */ (btn)).dataset.epubStep.split(':');
        const dir = Number(dirRaw);
        if (key === 'fontSize') setPrefs({ fontSize: cycle(FONT_SIZE_STEPS, prefs.fontSize, dir) });
        else setPrefs({ lineHeight: cycle(LINE_HEIGHT_STEPS, prefs.lineHeight, dir) });
      });
    });
    menu.querySelectorAll('[data-epub-para]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ paraSpace: (/** @type {HTMLElement} */ (btn)).dataset.epubPara }));
    });
    menu.querySelectorAll('[data-epub-font]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ fontFamily: (/** @type {HTMLElement} */ (btn)).dataset.epubFont }));
    });
    const tocBtnRow = menu.querySelector('[data-reader-toc]');
    if (tocBtnRow) tocBtnRow.addEventListener('click', () => { closeMenu(); openToc(); });
    const annoBtnRow = menu.querySelector('[data-reader-anno]');
    if (annoBtnRow && annoFace) annoBtnRow.addEventListener('click', () => { closeMenu(); annoFace.openDrawer(); });
    const jumpInput = /** @type {HTMLInputElement} */ (menu.querySelector('[data-reader-jump-input]'));
    const doJump = () => {
      const v = Math.min(100, Math.max(0, parseInt(jumpInput.value, 10) || 0));
      closeMenu();
      jumpToPosition01(v / 100);
    };
    menu.querySelector('[data-reader-jump]').addEventListener('click', doJump);
    jumpInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); doJump(); } });
    menu.querySelectorAll('[data-reader-mode]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ mode: (/** @type {HTMLElement} */ (btn)).dataset.readerMode }));
    });
    const menuRefresh = menu.querySelector('[data-reader-refresh]');
    if (menuRefresh) menuRefresh.addEventListener('click', requestRefresh);
    menu.querySelector('[data-epub-close]').addEventListener('click', closeMenu);
  }

  function openMenu() {
    renderMenu();
    menu.hidden = false;
    menu.dataset.epubMenu = 'open';
    closeToc();
    closeBarMenu();
    setFabGate(true);
  }
  function closeMenu() {
    if (menu.hidden) return;
    menu.hidden = true;
    menu.dataset.epubMenu = 'closed';
    if (tocEl.hidden) setFabGate(false);
  }

  // ── ⋯ 溢出菜单（移动刷新入口 + 翻页模式快捷切换） ─────────────────────────
  const barMenuOpen = () => !barMenu.hidden;

  function renderBarMenu() {
    const refreshRow = isMobileBar() ? `
      <button type="button" class="epub-menu-btn epub-menu-wide" data-reader-refresh>⟳ ${escapeHtml(t('reader.refresh'))}</button>` : '';
    barMenu.innerHTML = `
      ${refreshRow}
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('reader.mode'))}">
        <button type="button" class="epub-menu-btn${prefs.mode === 'scroll' ? ' on' : ''}" data-reader-mode="scroll">${escapeHtml(t('reader.mode.scroll'))}</button>
        <button type="button" class="epub-menu-btn${prefs.mode === 'flip' ? ' on' : ''}" data-reader-mode="flip">${escapeHtml(t('reader.mode.flip'))}</button>
      </div>`;
    const refreshBtn = barMenu.querySelector('[data-reader-refresh]');
    if (refreshBtn) refreshBtn.addEventListener('click', requestRefresh);
    barMenu.querySelectorAll('[data-reader-mode]').forEach((btn) => {
      // 先关菜单再落档：setPrefs→applyPrefs 会同步重渲染开着的菜单，
      // 被点钮会在点击派发中途被摘除（人无碍，自动化稳定性检查会咬）。
      btn.addEventListener('click', () => { closeBarMenu(); setPrefs({ mode: (/** @type {HTMLElement} */ (btn)).dataset.readerMode }); });
    });
  }

  function openBarMenu() {
    renderBarMenu();
    barMenu.hidden = false;
    closeMenu();
    closeToc();
  }
  function closeBarMenu() {
    if (!barMenu.hidden) barMenu.hidden = true;
  }

  // ── ask-fab 让位联动（设计稿 §2.2：阅读菜单/TOC 开 ⇒ fab gate-off） ──────
  // 事件名既有化：askDock.js 订阅 nf-reader-panel-toggle 联动 gate-off。
  let fabGated = false;
  function setFabGate(on) {
    if (on === fabGated) return;
    fabGated = on;
    document.dispatchEvent(new CustomEvent('nf-reader-panel-toggle', { detail: { open: on } }));
  }

  // ── 滑杆：拖动出 tooltip，松手（change）才跳（input 仅预览，防连番重排） ──
  let rangeDragging = false;
  function renderTicks() {
    if (!adapter.toc || !adapter.toc.length) { ticksEl.textContent = ''; return; }
    const seen = new Set();
    const pts = [];
    for (const e of adapter.toc) {
      if (e.chapterIdx < 0 || e.chapterIdx >= N || seen.has(e.chapterIdx)) continue;
      seen.add(e.chapterIdx);
      pts.push(e.chapterIdx / N);
    }
    const step = pts.length > MAX_TICKS ? Math.ceil(pts.length / MAX_TICKS) : 1;
    ticksEl.innerHTML = pts
      .filter((_, i) => i % step === 0)
      .map((p) => `<i class="epub-progress-tick" style="left:${(p * 100).toFixed(3)}%"></i>`)
      .join('');
  }

  function previewRange() {
    rangeDragging = true;
    tipEl.dataset.pin = '1';
    tipEl.textContent = tipFromFraction(Number(range.value) / 1000);
    range.setAttribute('aria-valuetext', tipEl.textContent);
    syncTipPos();
  }
  function tipFromFraction(f) {
    const idx = Math.min(N - 1, Math.floor(f * N));
    let title = adapter.chapterTitle(idx);
    if (adapter.toc && adapter.toc.length) {
      let best = null;
      for (const e of adapter.toc) {
        if (e.chapterIdx <= idx && (!best || e.chapterIdx >= best.chapterIdx)) best = e;
      }
      if (best && best.label) title = best.label;
    }
    return t('reader.pos', { title, pct: String(Math.round(f * 100)) });
  }
  range.addEventListener('input', previewRange);
  range.addEventListener('change', () => {
    rangeDragging = false;
    tipEl.dataset.pin = '0';
    jumpToPosition01(Number(range.value) / 1000);
  });
  range.addEventListener('pointerdown', () => { tipEl.dataset.pin = '1'; });
  range.addEventListener('pointerup', () => { tipEl.dataset.pin = '0'; });

  // ── 页面翻动（滚动模式 = P0 现状；翻页模式 = §4.1 双页夹） ─────────────────

  function loadChapterScroll(i, scrollPos) {
    mountChapter(i);
    requestAnimationFrame(() => {
      scroller.scrollTop = scrollPos || 0;
      updateUI();
    });
  }

  function nextPage() {
    if (flipBusy()) { enqueueFlip(1); return; }
    if (prefs.mode === 'flip') { flipBy(1); return; }
    const bottom = scroller.scrollHeight - scroller.clientHeight;
    if (scroller.scrollTop < bottom - 1) {
      scroller.scrollTo({ top: scroller.scrollTop + scroller.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx < N - 1) {
      loadChapterScroll(chapterIdx + 1, 0);
    }
  }

  function prevPage() {
    if (flipBusy()) { enqueueFlip(-1); return; }
    if (prefs.mode === 'flip') { flipBy(-1); return; }
    if (scroller.scrollTop > 1) {
      scroller.scrollTo({ top: scroller.scrollTop - scroller.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx > 0) {
      loadChapterScroll(chapterIdx - 1, 999999); // bottom of previous chapter
    }
  }

  // ── 双页夹动效引擎 ────────────────────────────────────────────────────────

  /** 现页/邻页定位：dir=+1 下一页（章末跨章）、-1 上一页（章首跨章末页）。 */
  function flipTarget(dir) {
    const H = scroller.clientHeight;
    const bottom = scroller.scrollHeight - scroller.clientHeight;
    if (dir > 0) {
      if (scroller.scrollTop < bottom - 1) return { ok: true, chapter: chapterIdx, top: scroller.scrollTop + H };
      if (chapterIdx < N - 1) return { ok: true, chapter: chapterIdx + 1, top: 0 };
      return { ok: false };
    }
    if (scroller.scrollTop > 1) return { ok: true, chapter: chapterIdx, top: Math.max(0, scroller.scrollTop - H) };
    if (chapterIdx > 0) return { ok: true, chapter: chapterIdx - 1, top: -1 }; // -1 = 章末页
    return { ok: false };
  }

  /** 层内容工厂：克隆当前滚动内容，或按章现装（跨章邻页）。 */
  function buildLayerContent(target) {
    if (target.chapter === chapterIdx && adapter.contentKind !== 'host') {
      return contentEl.cloneNode(true);
    }
    if (adapter.contentKind === 'host') {
      return contentEl.cloneNode(true);
    }
    const el = document.createElement('div');
    el.className = 'epub-reader-content';
    if (adapter.themeable) {
      const palette = READER_THEMES[effectiveReaderTheme(prefs)];
      el.style.setProperty('--epub-bg', palette.bg);
      el.style.setProperty('--epub-fg', palette.fg);
      el.style.fontSize = prefs.fontSize + 'px';
      el.style.lineHeight = String(prefs.lineHeight);
      el.style.setProperty('--epub-para-space', PARA_SPACE_STEPS[prefs.paraSpace]);
      el.dataset.fontFamily = prefs.fontFamily;
    }
    adapter.mountChapter(el, target.chapter);
    return el;
  }

  /** 在 stage 层内装配一页：页盒 = 内容克隆 + 顶偏移（-top），层宽 = 滚动盒宽。 */
  function assemblePage(layer, target) {
    const W = scroller.clientWidth;
    const page = document.createElement('div');
    page.className = 'epub-flip-page';
    page.style.width = W + 'px';
    const body = /** @type {HTMLElement} */ (buildLayerContent(target));
    page.appendChild(body);
    layer.appendChild(page);
    // 先落地再量高：页盒定高 = 内容总高（至少一屏），防翻页中内容抖动。
    const h = Math.max(body.scrollHeight, scroller.clientHeight);
    page.style.height = h + 'px';
    let top = target.top;
    if (top === -1) top = Math.max(0, h - scroller.clientHeight);
    page.dataset.pageTop = String(top);
    body.style.transform = `translateY(${-top}px)`;
    return page;
  }

  /** 翻页动画中枢。onDone 提交真状态后调。 */
  let flipState = null; // { stage, layerA, layerB, target, dir, mode }
  const flipQueue = [];
  const flipBusy = () => !!flipState;
  function enqueueFlip(dir) {
    if (flipQueue.length < FLIP_QUEUE_MAX) flipQueue.push(dir);
  }

  function cleanupFlip() {
    if (!flipState) return;
    flipState.stage.remove();
    flipState = null;
    const next = flipQueue.shift();
    if (next) flipBy(next);
  }

  function commitFlipTarget(target) {
    if (target.chapter !== chapterIdx) mountChapter(target.chapter);
    const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
    scroller.scrollTop = target.top === -1 ? max : Math.min(target.top, max);
    updateUI();
  }

  /** 构建双页夹舞台（挂在 reader 根，按滚动盒可视带定位，不占文档流）。 */
  function createStage() {
    const stage = document.createElement('div');
    stage.className = 'epub-flip-stage';
    stage.style.top = scroller.offsetTop + 'px';
    stage.style.height = scroller.clientHeight + 'px';
    stage.style.setProperty('--epub-bg', getComputedStyle(contentEl).backgroundColor || 'transparent');
    reader.appendChild(stage);
    return stage;
  }

  /** 页缘 8px 投影臂（§4.1：随位移线性衰减，纯渐变）：入页层的领先缘。 */
  function armFlipShadows(dir) {
    const st = flipState;
    if (!st) return;
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const lead = st.layerB.querySelector(dir > 0 ? '.epub-flip-shadow.l' : '.epub-flip-shadow.r');
    if (!lead) return;
    lead.style.opacity = '1';
    requestAnimationFrame(() => requestAnimationFrame(() => {
      lead.style.transition = 'opacity 240ms linear';
      lead.style.opacity = '0';
    }));
  }

  function startFlip(dir, fromDx) {
    const target = flipTarget(dir);
    if (!target.ok) return false;
    const stage = createStage();
    const layerA = document.createElement('div');
    layerA.className = 'epub-flip-layer a';
    const layerB = document.createElement('div');
    layerB.className = 'epub-flip-layer b';
    // 页缘投影元件（左右各一；谁领先谁点亮，回弹不亮）。
    for (const layer of [layerA, layerB]) {
      const sl = document.createElement('i');
      sl.className = 'epub-flip-shadow l';
      const sr = document.createElement('i');
      sr.className = 'epub-flip-shadow r';
      layer.append(sl, sr);
    }
    stage.append(layerA, layerB);
    // 先装 B（邻页），A 克隆当前——两层的页顶偏移各自独立。
    assemblePage(layerB, target);
    assemblePage(layerA, { chapter: chapterIdx, top: scroller.scrollTop });
    const W = scroller.clientWidth;
    flipState = { stage, layerA, layerB, target, dir, W };
    if (fromDx && fromDx !== 0) {
      // touch 跟手起点：A 随指位移，B 贴邻侧同位移（提交/回弹在 touchend）。
      applyFlipDx(fromDx);
    } else {
      // 点按/键盘翻页：真状态先提交（舞台不透明盖着，底下瞬跳不可见），
      // 动画只负责视觉滑动；落定走 settleFlip 双保险。
      commitFlipTarget(target);
      layerB.style.transform = `translateX(${dir * W}px)`;
      requestAnimationFrame(() => requestAnimationFrame(() => {
        layerA.style.transform = `translateX(${-dir * W}px)`;
        layerB.style.transform = 'translateX(0px)';
      }));
      armFlipShadows(dir);
      settleFlip();
    }
    return true;
  }

  function applyFlipDx(dx) {
    const { layerA, layerB, dir, W } = flipState;
    const clamped = Math.max(-W, Math.min(W, dx));
    layerA.style.transform = `translateX(${clamped}px)`;
    // B 静置侧 = 翻动方向同侧；dx 反向越过中线时随 A 退走。
    layerB.style.transform = `translateX(${dir * W + clamped}px)`;
  }

  /**
   * 收束双保险：transitionend（正常路径）+ rAF 时间哨兵（headless 首帧前改写
   * 终值会把 CSS transition 合并掉、transitionend 不派发的兜底）。零定时器
   * 纪律：哨兵只数 rAF 帧并读 performance.now，到界即清。上界 reduce 50ms /
   * 常态 400ms（240ms 过渡 + 余量）。
   */
  function settleFlip() {
    const st = flipState;
    if (!st || st.settled) return;
    st.settled = true;
    const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const deadline = performance.now() + (reduce ? 50 : 400);
    const done = () => {
      if (flipState !== st) return;
      st.layerA.removeEventListener('transitionend', onEnd);
      st.layerB.removeEventListener('transitionend', onEnd);
      cleanupFlip();
    };
    const onEnd = (e) => {
      if (e.propertyName && e.propertyName !== 'transform') return;
      done();
    };
    st.layerA.addEventListener('transitionend', onEnd);
    st.layerB.addEventListener('transitionend', onEnd);
    const guard = () => {
      if (flipState !== st) return;
      if (performance.now() >= deadline) { done(); return; }
      requestAnimationFrame(guard);
    };
    requestAnimationFrame(guard);
  }

  function finishFlip(commit) {
    const st = flipState;
    if (!st) return;
    const targetA = commit ? -st.dir * st.W : 0;
    const targetB = commit ? 0 : st.dir * st.W;
    // 零位移直清：手势终点已贴终态时 transition 不派发，stage 立即卸。
    const curOf = (layer) => {
      const m = /translateX\((-?[\d.]+)px\)/.exec(layer.style.transform || '');
      return m ? parseFloat(m[1]) : 0;
    };
    if (Math.abs(curOf(st.layerA) - targetA) < 0.5 && Math.abs(curOf(st.layerB) - targetB) < 0.5) {
      if (commit) commitFlipTarget(st.target);
      cleanupFlip();
      return;
    }
    if (commit) {
      // 提交先行：真状态先落定（舞台不透明盖着，底下瞬跳不可见），
      // 再摆终值走 240ms 收束动画。
      commitFlipTarget(st.target);
      st.layerA.style.transform = `translateX(${targetA}px)`;
      st.layerB.style.transform = `translateX(${targetB}px)`;
      armFlipShadows(st.dir);
    } else {
      // 回弹（§4.1：180ms）：rebound 类压过渡时长，双层退回手势起点。
      st.layerA.classList.add('rebound');
      st.layerB.classList.add('rebound');
      st.layerA.style.transform = `translateX(${targetA}px)`;
      st.layerB.style.transform = `translateX(${targetB}px)`;
    }
    settleFlip();
  }

  function flipBy(dir) {
    if (flipState) { enqueueFlip(dir); return; }
    // reduced-motion 由 split.css 媒体查询把 .epub-flip-layer 过渡压成 0.01s
    // （设计稿 §4.1：全程 0.01s 直切）——舞台照建，时长即降级口径（A10 可断言）。
    startFlip(dir, 0);
  }

  // ── touch 横滑跟手（设计稿 §4.1：跟手 + 速率判定 + 回弹） ────────────────
  let touchGesture = null; // { startX, startY, lastX, lastT, samples:[{x,t}], decided }
  scroller.addEventListener('touchstart', (e) => {
    if (prefs.mode !== 'flip') return;
    if (e.touches.length !== 1) return;
    const tc = e.touches[0];
    touchGesture = { startX: tc.clientX, startY: tc.clientY, lastX: tc.clientX, lastT: Date.now(), samples: [{ x: tc.clientX, t: Date.now() }], decided: false };
  }, { passive: true });

  scroller.addEventListener('touchmove', (e) => {
    if (!touchGesture) return;
    const tc = e.touches[0];
    const dx = tc.clientX - touchGesture.startX;
    const dy = tc.clientY - touchGesture.startY;
    if (!touchGesture.decided) {
      if (Math.abs(dx) < 10 || Math.abs(dx) < Math.abs(dy) * 1.2) {
        // 非横滑意图：垂直滚动照旧（touchGesture 留给 touchend 清零）。
        return;
      }
      // 横滑意图成立：判定可翻页侧，建舞台跟手；垂直滚动就此让位。
      const dir = dx < 0 ? 1 : -1;
      if (!startFlip(dir, dx)) { touchGesture = null; return; }
      touchGesture.decided = true;
      // 判定这步本身计入速率窗口：真实手势里前 10px 与起手同窗，漏记会让
      // 起手样本过期稀释尾窗速率（慢起手快甩尾被误判回弹）。
      touchGesture.samples.push({ x: tc.clientX, t: Date.now() });
      e.preventDefault();
      return;
    }
    // 跟手：近 100ms 速率采样 + 舞台位移。
    const now = Date.now();
    touchGesture.lastX = tc.clientX;
    touchGesture.lastT = now;
    touchGesture.samples.push({ x: tc.clientX, t: now });
    while (touchGesture.samples.length > 2 && now - touchGesture.samples[0].t > 100) touchGesture.samples.shift();
    applyFlipDx(tc.clientX - touchGesture.startX);
    e.preventDefault();
  }, { passive: false });

  scroller.addEventListener('touchend', (e) => {
    if (!touchGesture) return;
    const g = touchGesture;
    touchGesture = null;
    if (!g.decided || !flipState) return;
    const W = scroller.clientWidth;
    const dx = (e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientX : g.lastX) - g.startX;
    // 速率判定：近 100ms 窗口平均速率。
    let v = 0;
    if (g.samples.length >= 2) {
      const a = g.samples[0];
      const b = g.samples[g.samples.length - 1];
      const dt = b.t - a.t;
      if (dt > 0) v = (b.x - a.x) / dt;
    }
    const fling = Math.abs(v) > FLIP_VELOCITY_PX_MS;
    const distance = Math.abs(dx) > W * FLIP_DISTANCE_RATIO;
    finishFlip(fling || distance);
  }, { passive: true });

  scroller.addEventListener('touchcancel', () => {
    touchGesture = null;
    if (flipState) finishFlip(false);
  }, { passive: true });

  // ── 标注面（设计稿 §4.2；epub 系格式 adapter.annotatable 在场即挂） ────────
  if (adapter.annotatable) {
    annoFace = mountAnnotationFace({
      pane, reader, scroller,
      getContentEl: () => contentEl,
      annotatable: adapter.annotatable,
      chapterTitle: (i) => adapter.chapterTitle(i),
      // 不用 loadChapterScroll：它 rAF 里 scrollTop=0 会盖掉标注回跳的
      // scrollIntoView（renderChapter 在 mountChapter 内同步触发 reveal）。
      jumpToChapter: (i) => {
        scroller.scrollTop = 0;
        mountChapter(i);
        updateUI();
      },
      setPanelGate: (on) => {
        if (on) { setFabGate(true); return; }
        if (menu.hidden && tocEl.hidden) setFabGate(false);
      },
    });
  }

  // ── 事件接线 ──────────────────────────────────────────────────────────────

  prevBtn.addEventListener('click', (e) => { e.stopPropagation(); prevPage(); });
  nextBtn.addEventListener('click', (e) => { e.stopPropagation(); nextPage(); });
  aaBtn.hidden = !adapter.themeable;
  aaBtn.addEventListener('click', (e) => { e.stopPropagation(); if (menuOpen()) closeMenu(); else openMenu(); });
  tocBtn.hidden = !adapter.toc || !adapter.toc.length;
  tocBtn.addEventListener('click', (e) => { e.stopPropagation(); if (tocOpen()) closeToc(); else openToc(); });
  moreBtn.addEventListener('click', (e) => { e.stopPropagation(); if (barMenuOpen()) closeBarMenu(); else openBarMenu(); });

  // 点按 1/3-1/3-1/3 分区（P0 A4 既定）：菜单/抽屉开时正文点按仅收面板不翻页。
  scroller.addEventListener('click', (e) => {
    const hit = /** @type {Element|null} */ (e.target);
    const anchor = hit && hit.closest ? hit.closest('a[href]') : null;
    if (anchor) e.preventDefault(); // 单页应用守卫：锚/外链不触发应用页导航
    if (menuOpen()) { closeMenu(); return; }
    if (tocOpen()) { closeToc(); return; }
    if (barMenuOpen()) { closeBarMenu(); return; }
    // 链接/表单控件/既有高亮交还原语义（md 链接路由、菜单钮、标注编辑条等），
    // 不进点按分区。
    const interactive = hit && hit.closest
      ? hit.closest('a[href], button, input, select, textarea, summary, label, mark.nf-anno')
      : null;
    if (interactive) return;
    if (flipBusy()) return;
    const sel = window.getSelection();
    if (sel && sel.toString().trim().length > 0) return;
    const rect = scroller.getBoundingClientRect();
    if (e.clientX < rect.left + rect.width / 3) prevPage();
    else if (e.clientX > rect.right - rect.width / 3) nextPage();
    else openMenu();
  });

  pane.tabIndex = 0;
  pane.addEventListener('keydown', (e) => {
    // 输入焦点守卫（阅读体验批 2026-11-01）：目录项、跳百分比 number 输入、
    // 标注笔记 textarea 都在 pane 内（readerChrome:122-128 / annotations.js:396-433），
    // 无守卫时在笔记框里按方向键会翻页并吃掉 caret 移动（e.preventDefault()）。
    // 可编辑目标一律放行 —— 阅读器只接管「正文」上的方向键。
    const target = /** @type {any} */ (e.target);
    if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName || ''))) return;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); prevPage(); }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); nextPage(); }
  });

  let scrollTimer = null;
  scroller.addEventListener('scroll', () => {
    // 阅读位置记账：Chromium 在 .canvas-tab-pane{display:none} 期间会重置滚动盒
    // （measured 178 → 0，同 markdown.js:858-861 的记录），切回本页签时由
    // canvas-tab-activated 还原（见下方 _nfReaderScrollPos）。
    /** @type {any} */ (pane)._nfReaderScrollPos = scroller.scrollTop;
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => updateUI(), 100);
  }, { passive: true });

  // document 级 Esc：每个 chrome 实例挂一个监听；实例的 reader 根被 pane
  // 重建摘出 DOM（切书/换查看器）后首个按键即自卸，防跨实例泄漏。
  function onEscClose(e) {
    if (e.key !== 'Escape') return;
    if (!reader.isConnected) { document.removeEventListener('keydown', onEscClose); return; }
    if (annoFace && annoFace.noteEditorOpen && annoFace.noteEditorOpen()) { annoFace.closeNoteEditor(); return; }
    if (annoFace && annoFace.drawerOpen()) { annoFace.closeDrawer(); return; }
    if (menuOpen()) { closeMenu(); return; }
    if (tocOpen()) { closeToc(); return; }
    if (barMenuOpen()) closeBarMenu();
  }
  document.addEventListener('keydown', onEscClose);

  // ResizeObserver 必须在 teardown 里 disconnect：观察 scroller 的 observer 会
  // 让整本书的 DOM 与闭包跟着 chrome 一起永生（勘察：关页签后仍每帧可触发）。
  const layoutObserver = new ResizeObserver(() => updateUI());
  layoutObserver.observe(scroller);

  // ── 启动 ────────────────────────────────────────────────────────────────
  renderTicks();
  applyPrefs();
  mountChapter(0);
  requestAnimationFrame(() => {
    scroller.scrollTop = 0;
    updateUI();
  });

  // 切回本页签时还原阅读位置（见 scroll 监听里的记账）。canvas.js 的
  // setActiveTab 只对「声明了滚动记账的 pane」派发 canvas-tab-activated
  // （判据 `_mdScrollPos !== undefined` 同族），故记账键一经写入即生效。
  pane.addEventListener('canvas-tab-activated', () => {
    const keep = /** @type {any} */ (pane)._nfReaderScrollPos;
    if (typeof keep === 'number' && keep > 0) {
      scroller.scrollTop = keep;
      updateUI();
    }
  });

  // 清理函数挂 pane 供复用入口（epub 切书 revoke blob 等独立于 chrome）与
  // viewer 的 dispose(pane)（fileViewers.disposeViewer → canvas.js 关页签）。
  /** @type {any} */ (pane)._nfReaderTeardown = () => {
    setFabGate(false);
    if (annoFace) annoFace.destroy();
    themeAttrObserver.disconnect();
    layoutObserver.disconnect();
    clearTimeout(scrollTimer);
    if (darkMq && darkMq.removeEventListener) darkMq.removeEventListener('change', onPanelThemeChange);
    document.removeEventListener('keydown', onEscClose);
  };

  // Esc 关闭（单个 document 监听，多实例共存：闭包各自判断自己面板状态）。
  document.addEventListener('keydown', onEscClose);

  // 批⑩ 2026-10-31：底栏挂载完成广播——ask-fab 座位重算（epub 并入可问面后
  // fab 上阅读器页，底栏 ⋯ 右端与 fab 同角，must 让位；askDock syncSeat 收听）。
  pane.dispatchEvent(new CustomEvent('nf-reader-mounted', { bubbles: true }));

  return {
    reader, viewport, scroller, contentEl, menu, tocEl,
    prefs, setPrefs,
    openMenu, closeMenu, openToc, closeToc,
    nextPage, prevPage, jumpToPosition01, position01, updateUI,
    mountChapter: (i) => { mountChapter(i); updateUI(); },
    get chapterIdx() { return chapterIdx; },
  };
}
