// viewers/textReader.js — 纯文本阅读视图（2026-10-31 Canvas 改造批⑦修订）。
//
// 阅读器 P1 批（2026-10-30）曾把 .txt 从「code 回落直开 Monaco」转为统一阅读
// chrome（epub/txt/md 共内核）。Canvas 改造批⑦（2026-10-31 作者令「阅读器
// 视角仅电子书格式生效（epub/mobi/azw3 等）」）收回 txt 的阅读器视角：退出
// readerChrome（底栏/翻页/TOC 抽屉/进度条不再挂 txt），改朴素滚动阅读面
// （行 → 段落，textContent 防 XSS，空白保留 pre-wrap，\f 章界仅作分段视觉距，
// 不进任何操控件）。
//
// 大文本（服务端 stream 描述符腿）不经本件——fileViewers 单点分流先行
// （TextStream.isStream 阈值之上仍落 largeText.js 只读流式视图；分块读取
// 能力该腿复用不动）。
//
// 编辑面保留：addSourceToggle 同源/渲染双态（与 markdown/html 同件），
// Monaco 直连编辑不丢。

import { addSourceToggle } from './shared.js';
import { t } from '../i18n.js';

function viewText(pane, ctx) {
  // 旧 chrome 实例先卸（document 级钩子清理）——批⑦前打开的 pane 可能残留。
  if (/** @type {any} */ (pane)._nfReaderTeardown) {
    try { /** @type {any} */ (pane)._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    /** @type {any} */ (pane)._nfReaderTeardown = null;
  }

  const content = String(ctx.content || '');
  const fileName = ctx.fileName || (ctx.absPath ? ctx.absPath.split(/[\\/]/).pop() : 'TXT') || 'TXT';

  const wrap = document.createElement('div');
  wrap.className = 'canvas-txt-scroll canvas-viewer-safe-top';
  // \f 为段界（有则分段距；单段不产段头）——纯视觉，无操控语义。
  const segments = content.includes('\f') ? content.split('\f') : [content];
  segments.forEach((seg, i) => {
    if (i > 0) {
      const gap = document.createElement('div');
      gap.className = 'canvas-txt-seg-gap';
      gap.setAttribute('aria-hidden', 'true');
      wrap.appendChild(gap);
    }
    const body = document.createElement('div');
    body.className = 'canvas-txt-seg';
    for (const line of seg.replace(/^\n+|\n+$/g, '').split('\n')) {
      const p = document.createElement('p');
      p.textContent = line || ' ';
      if (!line) p.dataset.blank = '1';
      body.appendChild(p);
    }
    wrap.appendChild(body);
  });
  pane.appendChild(wrap);

  addSourceToggle(pane, viewText, {
    content,
    absPath: ctx.absPath,
    fileName: ctx.fileName,
    readOnly: ctx.readOnly === true,
  });
}

export default {
  name: 'text',
  label: 'Text reader',
  // extensions 空表：.txt 由 fileViewers.renderFile 单点映射进本件
  // （服务端 FileTypeRegistry 未知扩展名回落 'code'，注册表不动）。
  extensions: [],
  binary: false,
  priority: 0,
  render: viewText,
};
