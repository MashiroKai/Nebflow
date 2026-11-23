// canvasFavoritesCore.js — Canvas 文档收藏的纯逻辑（无 DOM / 无存储 / 无 import）。
//
// 作者令（2026-11-23，前端功能批 · Canvas 文档收藏三端面）：收藏条目 = path 引用 +
// 标题快照 + 收藏时间；同文档去重——重复收藏 = 置顶（不产生第二条）。本模块只做
// 形状与变换，存储与渲染在 canvasFavorites.js。纯函数，便于 node --test 直接覆盖。

/** 收藏上限（防止无界增长；超出时丢弃最旧的条目）。 */
export const FAVORITES_MAX = 200;

/**
 * @typedef {{ path: string, title: string, savedAt: number }} FavoriteEntry
 */

/** @param {string} path @returns {string} 路径末段（标题缺省时的兜底名）。 */
export function basenameOf(path) {
  const s = String(path || '');
  const parts = s.split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : s;
}

/**
 * 归一化外部输入（localStorage 读回的原始值）：丢弃形状不合法的条目，按 path 去重
 * （保留首次出现 = 最新置顶位），并截断到上限。
 * @param {unknown} raw @returns {FavoriteEntry[]}
 */
export function normalizeList(raw) {
  if (!Array.isArray(raw)) return [];
  /** @type {FavoriteEntry[]} */
  const out = [];
  const seen = new Set();
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const path = typeof item.path === 'string' ? item.path.trim() : '';
    if (!path || !path.startsWith('/')) continue;   // 收藏只认绝对路径（canvas 的 absPath 口径）
    if (seen.has(path)) continue;
    seen.add(path);
    const title = typeof item.title === 'string' && item.title.trim() ? item.title.trim() : basenameOf(path);
    const savedAt = Number.isFinite(item.savedAt) ? Number(item.savedAt) : 0;
    out.push({ path, title, savedAt });
    if (out.length >= FAVORITES_MAX) break;
  }
  return out;
}

/** @param {FavoriteEntry[]} list @param {string} path */
export function isFavorited(list, path) {
  return list.some((e) => e.path === path);
}

/**
 * 收藏一份文档：
 *  - 未收藏 → 插到表首（最新置顶），savedAt = now；
 *  - 已收藏（同 path）→ 不产生重复条目：移到表首置顶，**保留原 savedAt**（收藏时间不被
 *    重复点击改写），标题快照刷新为本次传入的标题。
 * 返回新数组，不改入参。
 * @param {FavoriteEntry[]} list @param {{ path: string, title?: string }} item @param {number} now
 */
export function addFavorite(list, item, now) {
  const path = String(item.path || '').trim();
  if (!path.startsWith('/')) return list.slice();
  const prev = list.find((e) => e.path === path);
  const title = (item.title && String(item.title).trim()) || (prev && prev.title) || basenameOf(path);
  const entry = { path, title, savedAt: prev ? prev.savedAt : now };
  const rest = list.filter((e) => e.path !== path);
  return [entry, ...rest].slice(0, FAVORITES_MAX);
}

/** 取消收藏（按 path 删除；不存在则原样返回副本）。 */
export function removeFavorite(list, path) {
  return list.filter((e) => e.path !== path);
}

/**
 * 切换收藏：已收藏 → 取消；未收藏 → 收藏（见 addFavorite）。
 * @returns {{ list: FavoriteEntry[], favorited: boolean }}
 */
export function toggleFavorite(list, item, now) {
  if (isFavorited(list, item.path)) {
    return { list: removeFavorite(list, item.path), favorited: false };
  }
  return { list: addFavorite(list, item, now), favorited: true };
}

// ── 收藏页分类 / 搜索（2026-11-23 收藏 r2 · 微信收藏形态）。纯函数。 ──
// 存储层只落 path/title/savedAt（引擎归一化会丢弃未知字段），类型因此在渲染时由 path
// 扩展名推断 —— 对存量条目与新条目口径一致，无需迁移。

const NOTE_EXT = new Set(['md', 'markdown', 'txt']);
const MEDIA_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'heic', 'mp4', 'mov', 'webm', 'mkv']);

/** @param {string} path @returns {string} 小写扩展名（无点）；无扩展名 = ''。 */
export function extOf(path) {
  const name = basenameOf(path);
  const i = name.lastIndexOf('.');
  return i > 0 ? name.slice(i + 1).toLowerCase() : '';
}

/**
 * 条目内容类型：note（笔记）| media（图片与视频）| pdf | file（其它文件）。
 * @param {string} path
 * @returns {'note'|'media'|'pdf'|'file'}
 */
export function favKind(path) {
  const ext = extOf(path);
  if (ext === 'pdf') return 'pdf';
  if (MEDIA_EXT.has(ext)) return 'media';
  if (NOTE_EXT.has(ext) || ext === '') return 'note';
  return 'file';
}

/** 类型 → 分类 tab（pdf 归入「文件」）。 */
export function favTabOf(kind) {
  if (kind === 'pdf') return 'file';
  return kind;
}

/** 分类 tab 固定顺序（微信收藏口径：笔记 / 图片与视频 / 文件 / 链接）。链接预留，当前无链接型条目。 */
export const FAV_TAB_ORDER = ['note', 'media', 'file', 'link'];

/** 列表中实际存在的分类（只返回有条目的 tab；stand 没有的类型不出 tab）。
 *  @param {FavoriteEntry[]} list @returns {string[]} */
export function presentTabs(list) {
  const have = new Set(list.map((e) => favTabOf(favKind(e.path))));
  return FAV_TAB_ORDER.filter((t) => have.has(t));
}

/**
 * 按分类 + 关键词过滤（保持原顺序 = 最新置顶）。query 大小写不敏感，匹配标题或路径。
 * @param {FavoriteEntry[]} list @param {{ tab?: string, query?: string }} [opts]
 */
export function filterFavorites(list, opts = {}) {
  const q = String(opts.query || '').trim().toLowerCase();
  return list.filter((e) => {
    if (opts.tab && favTabOf(favKind(e.path)) !== opts.tab) return false;
    if (!q) return true;
    return e.title.toLowerCase().includes(q) || e.path.toLowerCase().includes(q);
  });
}
