// canvasFavorites.js — Canvas 文档收藏：持久化 + 失效判定 + 侧栏/收藏页渲染（收藏页见 canvasFavoritesPage.js）。
//
// 作者令（2026-11-23，前端功能批）：canvas 顶部胶囊左侧加收藏钮（已收藏态实心/描边区分，
// 再点取消）；侧边栏与手机工作台加同源收藏列表（点击 = 重新打开）；文档被移除后条目置灰
// 标「已失效」、点击给可读提示（不静默、不开空 canvas）；存在性在渲染时检查。
//
// 数据模型（作者裁定：存储按仓内惯例 = 既有 UI 状态存储 localStorage，版本化 key）：
//   key = key('canvas_favorites_v1') → JSON FavoriteEntry[]（见 canvasFavoritesCore.js）。
// 纯逻辑（增删/去重/归一化）在 canvasFavoritesCore.js；本文件只做 IO 与 DOM。
import { key } from './branding.js';
import { t } from './i18n.js';
import {
  normalizeList, isFavorited, toggleFavorite, FAVORITES_MAX,
} from './canvasFavoritesCore.js';

export { FAVORITES_MAX };
export const FAVORITES_CHANGE_EVENT = 'nebflow-favorites-change';
const STORAGE_KEY = key('canvas_favorites_v1');

/** @typedef {import('./canvasFavoritesCore.js').FavoriteEntry} FavoriteEntry */

// 项 5（2026-11-23）收藏不显示的根因：存储原为 origin 隔离的 localStorage（桌面 localhost
// 与手机 mystand.space 各一份，跨端必空）。修法：引擎侧单源存储 `/api/canvas-favorites`
// （CanvasFavoritesRoutes.scala）为真源；内存缓存为运行期视图；localStorage 仅作离线镜像。
/** 引擎真源的内存缓存（null = 尚未与引擎同步，读回落本地镜像）。 */
let cache = null;
/** 一次性同步的 promise（mount 多处调用，只发一次 GET）。 */
let syncPromise = null;

function favoritesAuthHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

/** @returns {FavoriteEntry[]} */
export function loadFavorites() {
  if (cache) return cache;
  try {
    return normalizeList(JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'));
  } catch (_) {
    return [];
  }
}

/** 写回：缓存 + 本地镜像 + 引擎（best-effort；引擎不可达时本地仍生效，下次同步再推）。
 *  @param {FavoriteEntry[]} list */
function saveFavorites(list) {
  cache = list;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
  } catch (_) { /* 配额/隐私模式：镜像失效不影响引擎真源 */ }
  window.dispatchEvent(new CustomEvent(FAVORITES_CHANGE_EVENT));
  void fetch('/api/canvas-favorites', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...favoritesAuthHeaders() },
    body: JSON.stringify({ list }),
  }).catch(() => { /* 离线：本地缓存仍是当前视图，下次同步回推 */ });
}

/**
 * 与引擎同步（一次性，幂等）。GET 得真源；若引擎为空而本地镜像有条目（旧版 localStorage
 * 收藏），整表迁入引擎（一次性迁移，之后以引擎为准）。引擎不可达 ⇒ 保留本地视图，不抛错。
 * @returns {Promise<void>}
 */
export function syncFavoritesFromEngine() {
  if (syncPromise) return syncPromise;
  syncPromise = (async () => {
    try {
      const resp = await fetch('/api/canvas-favorites', { headers: favoritesAuthHeaders() });
      if (!resp.ok) return;
      const data = await resp.json();
      const remote = normalizeList(data && data.list);
      const local = loadFavorites();
      if (!remote.length && local.length) {
        // 迁移：旧 localStorage 收藏 → 引擎（仅一次；此后引擎为空也不再回填本地）。
        cache = local;
        await fetch('/api/canvas-favorites', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', ...favoritesAuthHeaders() },
          body: JSON.stringify({ list: local }),
        });
        return;
      }
      cache = remote;
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify(remote)); } catch (_) { /* 镜像可失效 */ }
      window.dispatchEvent(new CustomEvent(FAVORITES_CHANGE_EVENT));
    } catch (_) {
      // 引擎不可达：沿用本地镜像视图（离线可读）。
    }
  })();
  return syncPromise;
}

/** 按 path 切换收藏（桌面钮与列表共用的唯一写入口）。
 *  @param {{ path: string, title?: string }} item @returns {boolean} 切换后是否为已收藏 */
export function toggleFavoritePath(item) {
  const { list, favorited } = toggleFavorite(loadFavorites(), item, Date.now());
  saveFavorites(list);
  return favorited;
}

/** 收藏页打开入口计数（工作台 / 侧栏共用）。 */
export function favoriteCount() {
  return loadFavorites().length;
}

/** @param {string} path */
export function isFavoritePath(path) {
  return isFavorited(loadFavorites(), path);
}

/**
 * 文档存在性判定（渲染时检查）。只有明确的「不存在」（404/410）判 missing；网络/鉴权
 * 等不确定情况判 unknown —— 不把暂时不可达误标成「已失效」。
 * 走既有 nfTicket 票据通道，Range 0-0 只拉 1 字节，不下载整文件。
 * @param {string} path @returns {Promise<'ok'|'missing'|'unknown'>}
 */
export async function checkFavoriteStatus(path) {
  try {
    const { ticketUrl } = await import('./nfTicket.js');
    const url = await ticketUrl(path);
    const res = await fetch(url, { headers: { Range: 'bytes=0-0' }, credentials: 'same-origin' });
    try { await res.body?.cancel(); } catch (_) { /* 丢弃 1 字节响应体 */ }
    if (res.status === 404 || res.status === 410) return 'missing';
    if (res.ok || res.status === 206 || res.status === 416) return 'ok';
    return 'unknown';
  } catch (_) {
    return 'unknown';
  }
}

/** 点击收藏条目：先重判存在性；失效 = 可读提示 + 不开 canvas；其余 = 交 canvas 打开。
 *  @param {FavoriteEntry} entry */
export async function openFavorite(entry) {
  const status = await checkFavoriteStatus(entry.path);
  if (status === 'missing') {
    window.__showToast?.(t('canvas.fav.missing', { title: entry.title }), 'error');
    return 'missing';
  }
  const { openWorkspaceItem } = await import('./canvas.js');
  await openWorkspaceItem({
    id: `file:${entry.path}`,
    itemType: '',
    title: entry.title,
    content: '',
    absPath: entry.path,
  });
  return status;
}

/**
 * 渲染收藏列表到 container（侧边栏 / 手机工作台共用同一份 DOM 语义）。
 * 失效判定对每条异步回填 —— 条目先以快照渲染（不阻塞），判定完成后置灰标「已失效」。
 * 全部 textContent 写入（不拼 innerHTML，标题可能含任意字符）。
 * @param {HTMLElement} container
 * @param {{ compact?: boolean, onOpened?: () => void }} [opts]
 */
export function renderFavoritesList(container, opts = {}) {
  const list = loadFavorites();
  container.replaceChildren();
  container.classList.add('canvas-fav-list');
  if (!list.length) {
    const empty = document.createElement('div');
    empty.className = 'canvas-fav-empty';
    empty.textContent = t('canvas.fav.empty');
    container.appendChild(empty);
    return;
  }
  for (const entry of list) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'canvas-fav-row' + (opts.compact ? ' compact' : '');
    row.dataset.favPath = entry.path;
    const name = document.createElement('span');
    name.className = 'canvas-fav-title';
    name.textContent = entry.title;
    const meta = document.createElement('span');
    meta.className = 'canvas-fav-meta';
    meta.textContent = entry.savedAt ? new Date(entry.savedAt).toLocaleString() : '';
    row.append(name, meta);
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      void openFavorite(entry).then((status) => {
        if (status !== 'missing') opts.onOpened?.();   // 手机端：打开成功后切到 canvas 页
      });
    });
    container.appendChild(row);
    void checkFavoriteStatus(entry.path).then((status) => {
      if (status !== 'missing' || !row.isConnected) return;
      row.classList.add('is-invalid');
      row.setAttribute('aria-disabled', 'true');
      const badge = document.createElement('span');
      badge.className = 'canvas-fav-badge';
      badge.textContent = t('canvas.fav.invalid');
      row.appendChild(badge);
    });
  }
}

/** 监听收藏变化，对已挂载的列表重绘（增删/置顶即时反映到所有端）。
 *  @param {() => void} fn @returns {() => void} 取消监听 */
export function onFavoritesChange(fn) {
  window.addEventListener(FAVORITES_CHANGE_EVENT, fn);
  return () => window.removeEventListener(FAVORITES_CHANGE_EVENT, fn);
}

/** 侧边栏收藏区：挂在会话列表（#session-list）之前，标题 + 列表。幂等。 */
export function mountSidebarFavorites() {
  if (document.getElementById('canvas-fav-section')) return;
  // 锚点：桌面侧栏真实容器 #panel-sessions 内、#explorer-section 之前（index.html）。
  // 旧锚点 #session-list 在当前 DOM 中不存在（挂载曾是静默 no-op，2026-11-23 实测）。
  const anchor = document.getElementById('explorer-section') || document.getElementById('session-list');
  if (!anchor || !anchor.parentElement) return;
  const section = document.createElement('section');
  section.id = 'canvas-fav-section';
  section.className = 'canvas-fav-section';
  // 表头可点 = 打开收藏页（微信收藏形态的全量入口；与工作台「收藏」栏同一页）。
  const head = document.createElement('button');
  head.type = 'button';
  head.className = 'canvas-fav-head';
  head.textContent = `${t('canvas.fav.sectionTitle')} ›`;
  head.setAttribute('aria-label', t('canvas.favPage.open'));
  head.addEventListener('click', (e) => {
    e.stopPropagation();
    void import('./canvasFavoritesPage.js').then((m) => m.openFavoritesPage());
  });
  const body = document.createElement('div');
  section.append(head, body);
  anchor.parentElement.insertBefore(section, anchor);
  const paint = () => renderFavoritesList(body, { compact: true });
  paint();
  onFavoritesChange(paint);
  void syncFavoritesFromEngine();
}
