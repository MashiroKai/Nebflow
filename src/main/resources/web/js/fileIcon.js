// fileIcon.js — 扩展名 → 文件卡图标色族/角标的**唯一**来源（引擎侧 .file-card 与
// 前端各卡面同源；零第二张扩展名表）。
//
// 抽为叶子模块（引用牌图标化批 2026-11-02）：`quoteLogo.js`（引用牌类型图标）也要用
// 这张表，而它同时被 msgQuote.js / reference.js 引用 —— 若表仍住在 popArtifacts.js，
// quoteLogo 就得静态 import popArtifacts，把既有的 popArtifacts↔reference↔msgQuote
// 三环扩成四环（scripts/check-circular.mjs 记 FAIL）。表本身零依赖，落到叶子上后
// 两边同源取用，环成员集合**零变化**。
//
// 兼容面：`popArtifacts.js` 保留同名 re-export（既有 import 点逐字不动）。

/** 文件名 → 扩展名（无点 → ''；dot > 0 保证 .gitignore 这类点文件无扩展名）。 */
export function extOf(name) {
  const n = String(name || '');
  const i = n.lastIndexOf('.');
  return i > 0 ? n.slice(i + 1).toLowerCase() : '';
}

/** 扩展名 → `{label, cls}`（cls 直接落 CSS `.fc-icon.<cls>` 既有色档，chat.css 单点）。
 *
 *  输入框附件图标化批（作者令 2026-11-02：「输入框里的附件条不显示文件名，用与图片
 *  附件缩略图一样大小的图标代替」）：本表即附件 icon chip 的唯一色族/角标来源。
 *  归档族（zip/rar/7z/tar/gz/tgz/bz2/xz）该批**补入** —— 此前落 'code' 灰档。 */
export function fileIconStyle(ext) {
  const e = (ext || '').toLowerCase();
  if (e === 'pdf') return { label: 'PDF', cls: 'pdf' };
  if (e === 'html' || e === 'htm') return { label: 'HTML', cls: 'html' };
  if (e === 'md' || e === 'markdown') return { label: 'MD', cls: 'md' };
  if (['doc', 'docx'].includes(e)) return { label: 'DOC', cls: 'doc' };
  if (['xls', 'xlsx', 'xlsm', 'csv', 'tsv'].includes(e)) return { label: 'XLS', cls: 'xls' };
  if (['ppt', 'pptx'].includes(e)) return { label: 'PPT', cls: 'ppt' };
  if (e === 'epub') return { label: 'EPUB', cls: 'epub' };
  if (['zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz'].includes(e)) return { label: e.toUpperCase().slice(0, 4), cls: 'zip' };
  if (['json', 'yaml', 'yml'].includes(e)) return { label: e.toUpperCase(), cls: 'data' };
  if (e) return { label: e.toUpperCase().slice(0, 4), cls: 'code' };
  return { label: 'FILE', cls: 'code' };
}
