// viewers/textReader.js — 纯文本统一阅读内核（阅读器 P1 批，锚设计稿 §3.1）。
//
// .txt 从「code 回落直开 Monaco」改为转换渲染进统一阅读 chrome（epub/txt/md
// 共内核）：统一主题/翻页/进度条/TOC 抽屉。分段纪律：内容含 \f（换页符）按
// 段为章（章序进 TOC 抽屉）；无 \f 则全书单章。行 → 段落（textContent，防
// XSS），空白保留（pre-wrap）。
//
// 大文本（服务端 stream 描述符腿）不经过本件——fileViewers 单点分流先行
// （TextStream.isStream 阈值之上仍落 largeText.js 只读流式视图，分块读取
// 能力该腿复用不动；虚拟行与翻页夹动画不同构，内核化只覆盖整件进页面的
// 文本，防增额）。
//
// 编辑面保留：addSourceToggle 同源/渲染双态（与 markdown/html 同件），
// Monaco 直连编辑不丢。

import { mountReader } from './readerChrome.js';
import { addSourceToggle } from './shared.js';
import { t } from '../i18n.js';

/** 给文本阅读器的角浮件（源码钮）让座：底栏 44px，浮件抬到栏上同簇。 */
function seatFloatsAboveBar(pane) {
  const srcBtn = pane.querySelector(':scope > .canvas-source-toggle');
  if (srcBtn) srcBtn.style.bottom = '64px';
}

function viewText(pane, ctx) {
  // 旧 chrome 实例先卸（document 级钩子清理），同 epub.js 入口纪律。
  if (/** @type {any} */ (pane)._nfReaderTeardown) {
    try { /** @type {any} */ (pane)._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    /** @type {any} */ (pane)._nfReaderTeardown = null;
  }

  const content = String(ctx.content || '');
  const fileName = ctx.fileName || (ctx.absPath ? ctx.absPath.split(/[\\/]/).pop() : 'TXT') || 'TXT';
  // 分段纪律：\f 为章界；单章不产 TOC（☰ 钮隐藏）。
  const rawChapters = content.includes('\f') ? content.split('\f') : [content];
  const chapters = rawChapters.map((text, i) => ({
    title: rawChapters.length > 1 ? t('reader.chapter', { n: String(i + 1) }) : fileName,
    lines: text.replace(/^\n+|\n+$/g, '').split('\n'),
  }));

  const adapter = {
    title: fileName,
    chapterCount: chapters.length,
    chapterTitle: (i) => (chapters[i] ? chapters[i].title : ''),
    toc: chapters.length > 1
      ? chapters.map((c, i) => ({ label: c.title, depth: 0, chapterIdx: i, anchor: '' }))
      : null,
    themeable: true,
    contentKind: 'flow',
    tracking: false,
    mountChapter(el, i) {
      const chapter = chapters[i];
      if (!chapter) return;
      el.dataset.format = 'text';
      el.replaceChildren();
      for (const line of chapter.lines) {
        const p = document.createElement('p');
        p.textContent = line || ' ';
        if (!line) p.dataset.blank = '1';
        el.appendChild(p);
      }
    },
  };

  mountReader(pane, adapter);
  addSourceToggle(pane, viewText, {
    content,
    absPath: ctx.absPath,
    fileName: ctx.fileName,
    readOnly: ctx.readOnly === true,
  });
  seatFloatsAboveBar(pane);
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
