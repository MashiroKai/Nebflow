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

  // 阅读/编辑体验优化批 2026-11-01（重渲叠加 + 回顶部，两处确定性缺陷）：
  //  ① 修前 viewText 只 appendChild、从不清 pane —— 而 canvas.js 的页签激活
  //     刷新（setActiveTab → scheduleFileRefresh，canvas.js:1089）会重跑
  //     renderFile，第二份 .canvas-txt-scroll（position:absolute;inset:0，
  //     split.css:2518-2521）正好盖在第一份上面 ⇒ 每激活一次 DOM 无界增长。
  //  ② 同一个 display 翻转会把滚动盒重置为 0（Chromium，实测同 markdown.js
  //     :858-861）⇒ 长文本每次切签都回顶部。
  // 两处一起收：先摘旧面，渲染后按记账还位，切签时由 canvas-tab-activated 还位。
  const keepScroll = /** @type {any} */ (pane)._nfTxtScrollPos;
  pane.innerHTML = '';

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

  // 记账键（canvas.js setActiveTab 依此判据派发 canvas-tab-activated；一经写入
  // 即为「本 pane 有滚动记账」的声明）。
  /** @type {any} */ (pane)._nfTxtScrollPos = typeof keepScroll === 'number' ? keepScroll : 0;
  wrap.addEventListener('scroll', () => {
    /** @type {any} */ (pane)._nfTxtScrollPos = wrap.scrollTop;
  }, { passive: true });
  if (keepScroll > 0) {
    // 追加后布局尚未完成，直接写 scrollTop 会被夹到 0 —— 与 readerChrome:1000
    // 同款，等一帧再还位。
    requestAnimationFrame(() => {
      if (wrap.isConnected) wrap.scrollTop = keepScroll;
    });
  }

  // 切回本页签时还位：滚动盒在 display 翻转中被重置，而 wrap 元素本身没换，
  // 所以要主动写回（一次绑定，按当前 wrap 查询，重渲不叠监听）。
  const paneAny = /** @type {any} */ (pane);
  if (!paneAny._nfTxtActivateBound) {
    paneAny._nfTxtActivateBound = true;
    pane.addEventListener('canvas-tab-activated', () => {
      const sc = pane.querySelector('.canvas-txt-scroll');
      const keep = paneAny._nfTxtScrollPos;
      if (sc && typeof keep === 'number' && keep > 0 && Math.abs(sc.scrollTop - keep) > 1) {
        sc.scrollTop = keep;
      }
    });
  }

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
