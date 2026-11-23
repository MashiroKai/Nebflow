// canvasFavoritesPage.js — 收藏页（2026-11-23 收藏 r2 · 微信收藏形态）。
//
// 作者令：工作台新增「收藏」栏，点进去 = 独立收藏页：顶栏（返回 + 标题「收藏」）、搜索框、
// 分类 tab（笔记 / 图片与视频 / 文件 / 链接——仅显示实际存在的类型）、条目卡片列表
// （标题 + 摘要两行 + 来源 + 日期 + 右侧缩略图 / 类型角标）；点条目 = canvas 打开。
// 数据与失效判定复用 canvasFavorites.js（单源）；分类/过滤纯逻辑在 canvasFavoritesCore.js。
import { t } from './i18n.js';
import {
  loadFavorites, openFavorite, onFavoritesChange, syncFavoritesFromEngine, checkFavoriteStatus,
} from './canvasFavorites.js';
import { favKind, presentTabs, filterFavorites, extOf } from './canvasFavoritesCore.js';

const PAGE_ID = 'canvas-fav-page';
/** 无限滚动：每批渲染条数。 */
const BATCH = 30;

const TAB_LABEL = {
  note: () => t('canvas.favPage.tab.note'),
  media: () => t('canvas.favPage.tab.media'),
  file: () => t('canvas.favPage.tab.file'),
  link: () => t('canvas.favPage.tab.link'),
};
const KIND_LABEL = {
  note: () => t('canvas.favPage.kind.note'),
  media: () => t('canvas.favPage.kind.media'),
  pdf: () => t('canvas.favPage.kind.pdf'),
  file: () => t('canvas.favPage.kind.file'),
};

/** 日期文案：当天 = 时分；其余 = 月/日（跨年带年）。 */
function formatDate(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  const sameYear = d.getFullYear() === now.getFullYear();
  return d.toLocaleDateString(undefined, sameYear
    ? { month: 'numeric', day: 'numeric' }
    : { year: 'numeric', month: 'numeric', day: 'numeric' });
}

/** 来源 = 父目录名（微信收藏的「来源」位；stand 条目无独立来源字段）。 */
function sourceOf(path) {
  const parts = String(path).split('/').filter(Boolean);
  return parts.length >= 2 ? parts[parts.length - 2] : '';
}

/** 摘要：文件所在目录（两行内，不截断）。 */
function summaryOf(path) {
  const parts = String(path).split('/').filter(Boolean);
  return parts.slice(0, -1).join('/') || '/';
}

/** 右侧缩略图 / 类型角标。图片 = 真缩略图（nfTicket URL，浏览器按需拉取）；PDF = 红色文件角标；其它 = 扩展名角标。
 *  @param {import('./canvasFavoritesCore.js').FavoriteEntry} entry */
async function buildThumb(entry) {
  const kind = favKind(entry.path);
  const el = document.createElement('div');
  el.className = `cfp-thumb cfp-thumb-${kind}`;
  if (kind === 'media') {
    try {
      const { ticketUrl } = await import('./nfTicket.js');
      const url = await ticketUrl(entry.path);
      const img = document.createElement('img');
      img.alt = '';
      img.loading = 'lazy';
      img.src = url;
      el.appendChild(img);
    } catch (_) {
      el.textContent = extOf(entry.path).toUpperCase();
    }
  } else if (kind === 'pdf') {
    const badge = document.createElement('span');
    badge.className = 'cfp-pdf-badge';
    badge.textContent = 'PDF';
    el.appendChild(badge);
  } else {
    el.textContent = (extOf(entry.path) || 'TXT').toUpperCase().slice(0, 4);
  }
  return el;
}

/** 构建收藏页（overlay 根）。 */
function buildPage(onClose, onOpened) {
  const page = document.createElement('section');
  page.id = PAGE_ID;
  page.className = 'cfp-page';
  page.setAttribute('role', 'dialog');
  page.setAttribute('aria-label', t('canvas.favPage.title'));

  // 顶栏：返回 + 标题（微信收藏顶栏）。
  const bar = document.createElement('header');
  bar.className = 'cfp-bar';
  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'cfp-back';
  back.setAttribute('aria-label', t('canvas.favPage.back'));
  back.title = t('canvas.favPage.back');
  back.textContent = '‹';
  back.addEventListener('click', () => onClose());
  const title = document.createElement('h2');
  title.className = 'cfp-title';
  title.textContent = t('canvas.favPage.title');
  bar.append(back, title);

  // 搜索框。
  const search = document.createElement('div');
  search.className = 'cfp-search';
  const input = document.createElement('input');
  input.type = 'search';
  input.className = 'cfp-search-input nf-field';
  input.placeholder = t('canvas.favPage.search');
  input.setAttribute('aria-label', t('canvas.favPage.search'));
  search.appendChild(input);

  // 分类 tab（仅实际存在的类型）。
  const tabs = document.createElement('div');
  tabs.className = 'cfp-tabs';
  tabs.setAttribute('role', 'tablist');

  const body = document.createElement('div');
  body.className = 'cfp-body';
  const list = document.createElement('div');
  list.className = 'cfp-list';
  body.appendChild(list);

  page.append(bar, search, tabs, body);

  const state = { tab: '', query: '', shown: 0, items: [], gen: 0 };

  function paintTabs() {
    const present = presentTabs(loadFavorites());
    if (state.tab && !present.includes(state.tab)) state.tab = '';
    tabs.replaceChildren();
    tabs.hidden = present.length === 0;
    for (const id of present) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cfp-tab' + (state.tab === id ? ' is-on' : '');
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', state.tab === id ? 'true' : 'false');
      b.dataset.tab = id;
      b.textContent = TAB_LABEL[id]();
      b.addEventListener('click', () => {
        state.tab = state.tab === id ? '' : id;
        paintTabs();
        paintList();
      });
      tabs.appendChild(b);
    }
  }

  function renderBatch() {
    const gen = state.gen;
    const from = state.shown;
    const to = Math.min(state.items.length, from + BATCH);
    state.shown = to;
    for (let i = from; i < to; i++) {
      const entry = state.items[i];
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'cfp-row';
      row.dataset.favPath = entry.path;
      row.dataset.kind = favKind(entry.path);
      const main = document.createElement('div');
      main.className = 'cfp-main';
      const h = document.createElement('div');
      h.className = 'cfp-row-title';
      h.textContent = entry.title;
      const sum = document.createElement('div');
      sum.className = 'cfp-row-sum';
      sum.textContent = summaryOf(entry.path);
      const meta = document.createElement('div');
      meta.className = 'cfp-row-meta';
      const src = document.createElement('span');
      src.className = 'cfp-row-src';
      src.textContent = `${KIND_LABEL[favKind(entry.path)]()} · ${sourceOf(entry.path)}`;
      const date = document.createElement('span');
      date.className = 'cfp-row-date';
      date.textContent = formatDate(entry.savedAt);
      meta.append(src, date);
      main.append(h, sum, meta);
      row.appendChild(main);
      row.addEventListener('click', () => {
        void openFavorite(entry).then((status) => {
          if (status === 'missing') return;   // 失效：留在收藏页，toast 已给可读提示
          onClose();
          onOpened?.();                        // 手机端：切到 canvas 页（桌面无回调）
        });
      });
      list.appendChild(row);
      // 失效判定（与侧栏同口径：仅 404/410 判失效）：置灰 + 「已失效」角标，点击仅给提示。
      void checkFavoriteStatus(entry.path).then((status) => {
        if (status !== 'missing' || !row.isConnected) return;
        row.classList.add('is-invalid');
        row.setAttribute('aria-disabled', 'true');
        const badge = document.createElement('span');
        badge.className = 'canvas-fav-badge';
        badge.textContent = t('canvas.fav.invalid');
        h.appendChild(badge);
      });
      void buildThumb(entry).then((thumb) => {
        if (gen !== state.gen || !row.isConnected) return;
        row.appendChild(thumb);
      });
    }
  }

  function paintList() {
    state.gen += 1;
    const all = loadFavorites();
    state.items = filterFavorites(all, { tab: state.tab, query: state.query });
    state.shown = 0;
    list.replaceChildren();
    body.querySelector('.cfp-empty')?.remove();
    if (!state.items.length) {
      const empty = document.createElement('div');
      empty.className = 'cfp-empty';
      empty.textContent = all.length ? t('canvas.favPage.noMatch') : t('canvas.favPage.empty');
      body.appendChild(empty);
      return;
    }
    renderBatch();
  }

  // 无限滚动：接近底部时追加下一批。
  body.addEventListener('scroll', () => {
    if (body.scrollTop + body.clientHeight >= body.scrollHeight - 80 && state.shown < state.items.length) {
      renderBatch();
    }
  });

  input.addEventListener('input', () => {
    state.query = input.value;
    paintList();
  });

  paintTabs();
  paintList();
  const dispose = onFavoritesChange(() => { paintTabs(); paintList(); });
  return { el: page, dispose };
}

/** 当前打开的收藏页（单例）。 */
let current = null;

function onKey(e) {
  if (e.key === 'Escape') closeFavoritesPage();
}

/** 打开收藏页（幂等：已打开则不重复建）。
 *  @param {{ onOpened?: () => void }} [opts] 条目打开成功后的回调（手机端 = 切到 canvas 页）。 */
export function openFavoritesPage(opts = {}) {
  if (current) return;
  const built = buildPage(() => closeFavoritesPage(), opts.onOpened);
  document.body.appendChild(built.el);
  current = built;
  requestAnimationFrame(() => built.el.classList.add('is-open'));
  document.addEventListener('keydown', onKey);
  void syncFavoritesFromEngine();   // 打开即与引擎真源对齐（同步完成派发变更 ⇒ 页内重绘）
}

/** 关闭收藏页。 */
export function closeFavoritesPage() {
  if (!current) return;
  current.dispose();
  current.el.remove();
  current = null;
  document.removeEventListener('keydown', onKey);
}

/** 收藏页根元素（未打开 = null）；供视觉闸断言。 */
export function favoritesPageEl() {
  return current ? current.el : null;
}
