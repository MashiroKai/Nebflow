// mdHtmlGate.js — md/html 四扩展名硬约束闸 · 前端判据单点
// （设计稿 docs/design/20261028_canvas-ask-mode-design.zh-CN.md §2「类型硬约束闸」
// 与 §8 词条同闸回写；调研稿 §5.6/§9-7/§9-8，作者 2026-10-29 11:46 自裁）。
//
// 后端同枚举单点 = nebflow.core.wiki.MdHtmlGate（scala）——跨语言两单点，
// 枚举与判据语义逐字对齐（禁写第二份：askDock / markdown viewer / 后续词条面
// 一律 import 本模块，不内联扩展名表）。消费面：
//   · askDock.js —— ask 入口同闸（FAB/输入条在非 md/html 文件 tab 隐藏，
//     与既有「只认文件型 tab」可见性闸同一悬挂点，双层防呆）；
//   · viewers/markdown.js —— 词条/wikilink 渲染同闸：非 md/html 文档里
//     `[[词条]]` 按普通文本渲染不解析、点击无深链。
//
// 路径归一：入参可能带 `?query` 与 `#fragment`（词条深链 / 定位锚），判定前先
// 剥离再取末尾扩展名（目录名里的点不误判）。大小写不敏感（README.MD 放行）。
//
// 存量口径（§9-8）：本闸只拦新问/新解析，不清理任何既有状态。

/** 四扩展名封闭枚举（小写；与 scala 侧 MdHtmlGate.Extensions 逐字对齐）。 */
export const MD_HTML_EXTENSIONS = ['.md', '.markdown', '.html', '.htm'];

/** 剥掉 `?query` / `#fragment`（取更靠前者截断）并 trim。 */
export function stripQueryAndFragment(path) {
  if (!path || typeof path !== 'string') return '';
  const p = path.trim();
  const q = p.indexOf('?');
  const h = p.indexOf('#');
  const cut = q < 0 && h < 0 ? p.length : q < 0 ? h : h < 0 ? q : Math.min(q, h);
  return p.slice(0, cut);
}

/** 扩展名（小写含前导点）：归一化路径中最后一个路径分隔符之后的最后一个点。 */
export function extOf(path) {
  const p = stripQueryAndFragment(path).toLowerCase();
  if (!p) return '';
  const slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
  const dot = p.lastIndexOf('.');
  return dot <= slash ? '' : p.slice(dot);
}

/**
 * 判据单点：path 扩展名 ∈ 四扩展名白名单。null/空/无扩展名/白名单外一律 false。
 * 只判字符串不触盘——调用方负责路径已归一（tilde 展开等）。
 */
export function isMdHtmlFile(path) {
  if (!path || typeof path !== 'string') return false;
  return MD_HTML_EXTENSIONS.includes(extOf(path));
}

/* ── ask 可问面扩列（Canvas 改造批⑩，2026-10-31 作者令「对话按钮扩到电子书
   格式（原仅 md/html）」）：md/html 四扩展名 + 电子书 epub/mobi/azw3。
   注意判据分工：词条/wikilink 渲染面仍走 isMdHtmlFile（未扩）；ask 面走
   本函数。后端镜像 = nebflow.core.ask.AskSessions.isAskable（同判据），
   MdHtmlGate（scala）保持四扩展名不动——扩列是 ask 侧单裁定，词条侧不跟。 */
export const ASKABLE_EBOOK_EXTENSIONS = ['.epub', '.mobi', '.azw3'];

/** ask 可问判据：md/html 白名单 ∪ 电子书白名单（大小写不敏感，query/frag
 *  剥离同 extOf 纪律）。 */
export function isAskableFile(path) {
  if (!path || typeof path !== 'string') return false;
  return MD_HTML_EXTENSIONS.includes(extOf(path)) || ASKABLE_EBOOK_EXTENSIONS.includes(extOf(path));
}
