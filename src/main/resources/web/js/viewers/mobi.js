// viewers/mobi.js — .mobi / .azw3 纯前端自解析（阅读器 P3 生态批，锚设计稿
// 20261030_181655_reader-experience-design §3.2 路线 A：零新依赖）。
//
// 两条路线（设计稿定案）：
//   · .azw3 = KF8：MOBI 前缀 + BOUNDARY 标记 + 标准 zip（内嵌完整 EPUB 包）。
//     扫 PK\x03\x04 边界 → 切出 zip 字节 → **直接喂现有 EPUB 渲染管线**
//     （epub.js renderEpubBuffer，零改动复用，标注格式落 'azw3'）。
//   · .mobi：PalmDB 记录容器 → record 0 的 PalmDOC 头（LZ77 解压出 HTML 流）+
//     MOBI 头（编码/书名/EXTH 作者）→ HTML 清洗 → 统一阅读内核单书流
//     （按 <mbp:pagebreak> / <h2> 切段为伪章节，段题进 TOC）。
//     · 加密（encryption type ≠ 0）→「仅下载」可见降级（设计稿 §3.1）；
//     · HUFF/CDIC 压缩（17480）→ 同降级面（零依赖纪律不给解）；
//     · INDX 目录太复杂 → 降级「无目录」（设计稿 §3.2 明示），TOC 由伪章节题给。
//
// 预算纪律：单书解压文本 ≤ 64MB（PalmDOC 头 textLength 预判 + 实际双检），
// 与 ZIP_BUDGET 同族思想（拦在解压动作之前）。取件腿 = epub.js 同构
// （nfViewerUrl 账本命中零取数直开 / 票据腿 401 恰好一次重取票）。
//
// 可测性：解析纯函数（palmDocLz77Decode / parsePalmDb / findEmbeddedZip /
// parseMobiBook）全部导出，tests/reader-p3.spec.mjs 页内直引钉扎。

import { escapeHtml, parseZipEntries } from './shared.js';
import { mountReader } from './readerChrome.js';
import { renderEpubBuffer, resetBookBlobUrls, sanitizeInlineStyles } from './epub.js';
import { reMint } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';
import { t } from '../i18n.js';

/** 单书解压文本闸（字节；PalmDOC 头 textLength 预判 + 实际字节双检）。 */
const MAX_TEXT_BYTES = 64 * 1024 * 1024;
/** 扫 PK 边界的候选上限（防病态文件扫穿；撞签候选试解析即筛除）。 */
const ZIP_PROBE_MAX = 64;

// ── PalmDOC LZ77（wiki.mobileread.com/wiki/PalmDOC） ────────────────────────

/** PalmDOC LZ77 解压（compression type 2）。
 *  码表：0x00=空格；0x01-0x08=后跟 N 字面值；0x09-0x3F=字面值本身；
 *  0x40-0x7F=后跟 (c&0x3F)+1 字面值；0x80-0xBF=回溯 (c&0x3F)+3、距=下字节+1；
 *  0xC0-0xFF=回溯 (c&0x1F)+4、距=后跟两字节+1（大端）。 */
export function palmDocLz77Decode(u8) {
  const out = [];
  let i = 0;
  const n = u8.length;
  while (i < n) {
    const c = u8[i++];
    if (c === 0x00) { out.push(0x20); continue; }
    if (c <= 0x08) {
      for (let k = 0; k < c && i < n; k++) out.push(u8[i++]);
      continue;
    }
    if (c <= 0x3F) { out.push(c); continue; }
    if (c <= 0x7F) {
      const count = (c & 0x3F) + 1;
      for (let k = 0; k < count && i < n; k++) out.push(u8[i++]);
      continue;
    }
    let len, dist;
    if (c <= 0xBF) {
      len = (c & 0x3F) + 3;
      dist = (i < n ? u8[i++] : 0) + 1;
    } else {
      len = (c & 0x1F) + 4;
      const hi = i < n ? u8[i++] : 0;
      const lo = i < n ? u8[i++] : 0;
      dist = ((hi << 8) | lo) + 1;
    }
    let from = out.length - dist;
    for (let k = 0; k < len; k++) {
      out.push(from >= 0 ? out[from] : 0x20);
      from++;
    }
  }
  return Uint8Array.from(out);
}

// ── PalmDB 容器 ─────────────────────────────────────────────────────────────

const FOURCC = (s) => s.charCodeAt(0) | (s.charCodeAt(1) << 8) | (s.charCodeAt(2) << 16) | (s.charCodeAt(3) << 24);

/** PalmDB 头 + 记录表解析。type 'BOOK' + creator 'MOBI' 才算 mobi（TEXt/REAd
 *  等 PalmDoc 变体不装 MOBI 头，本批不接管——可见错误，防增额）。
 *  @returns {{records: Uint8Array[], headerName: string}} */
export function parsePalmDb(u8) {
  if (u8.byteLength < 78) throw new Error(t('mobi.invalid'));
  const view = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  if (view.getUint32(60, true) !== FOURCC('BOOK') || view.getUint32(64, true) !== FOURCC('MOBI')) {
    throw new Error(t('mobi.invalid'));
  }
  const headerName = new TextDecoder('windows-1252').decode(u8.subarray(0, 32)).replace(/\0.*$/, '').trim();
  const count = view.getUint16(76, true);
  if (count < 1) throw new Error(t('mobi.invalid'));
  const table = 78;
  if (table + count * 8 > u8.byteLength) throw new Error(t('mobi.invalid'));
  const offsets = [];
  for (let i = 0; i < count; i++) offsets.push(view.getUint32(table + i * 8, true));
  const records = offsets.map((off, i) => {
    const end = i + 1 < offsets.length ? offsets[i + 1] : u8.byteLength;
    return u8.subarray(off, Math.min(end, u8.byteLength));
  });
  return { records, headerName };
}

// ── azw3（KF8）：扫 PK 边界抽内嵌 EPUB ──────────────────────────────────────

/** 扫 PK\x03\x04 边界切出内嵌 zip（设计稿 §3.2 路线 A 定案）。
 *  候选逐个过 parseZipEntries（EOCD/CD/本地头三重结构校验）——压缩数据内随机
 *  撞签过不了结构校验，首个解析成功 = 真边界。
 *  @returns {{start: number, buffer: ArrayBuffer}|null} */
export function findEmbeddedZip(u8) {
  let tries = 0;
  for (let i = 0; i + 4 <= u8.length && tries < ZIP_PROBE_MAX; i++) {
    if (u8[i] !== 0x50 || u8[i + 1] !== 0x4b || u8[i + 2] !== 0x03 || u8[i + 3] !== 0x04) continue;
    tries++;
    const sliced = u8.slice(i); // 拷贝 → 独立 0 基 buffer（CD 偏移相对 zip 起点）
    try {
      parseZipEntries(sliced.buffer);
      return { start: i, buffer: sliced.buffer };
    } catch { /* 撞签/截尾：下一候选 */ }
  }
  return null;
}

// ── MOBI 书解析（PalmDOC 头 + MOBI 头 + EXTH） ──────────────────────────────

/** TextDecoder 标签映射（MOBI 编码号 → 平台标签；未识回 utf-8）。 */
const MOBI_ENCODINGS = {
  1252: 'windows-1252', 65001: 'utf-8', 20127: 'us-ascii',
  932: 'shift_jis', 936: 'gbk', 949: 'euc-kr', 950: 'big5',
};

function u32(view, off) { return off + 4 <= view.byteLength ? view.getUint32(off, true) : 0; }

/** 整书解析。ok=false 时 reason ∈ encrypted | unsupported | invalid（均走可见降级）。
 *  @returns {{ok:true,title:string,author:string,html:string}|{ok:false,reason:string}} */
export function parseMobiBook(u8) {
  let parsed;
  try { parsed = parsePalmDb(u8); } catch { return { ok: false, reason: 'invalid' }; }
  const { records } = parsed;
  const r0 = records[0];
  if (!r0 || r0.byteLength < 16 + 20) return { ok: false, reason: 'invalid' };
  const v0 = new DataView(r0.buffer, r0.byteOffset, r0.byteLength);
  // PalmDOC 头：compression@0 / textLength@4 / textRecordCount@8 / encryption@12
  const compression = v0.getUint16(0, true);
  const textLength = v0.getUint32(4, true);
  const textRecordCount = v0.getUint16(8, true);
  const encryption = v0.getUint16(12, true);
  if (encryption !== 0) return { ok: false, reason: 'encrypted' };
  if (compression === 17480) return { ok: false, reason: 'unsupported' }; // HUFF/CDIC
  if (compression !== 1 && compression !== 2) return { ok: false, reason: 'unsupported' };
  if (textLength > MAX_TEXT_BYTES) return { ok: false, reason: 'too-big' };

  // MOBI 头（record 0 偏移 16 起）：magic 'MOBI' / headerLen@+4 / encoding@+12 /
  // full name offset@+0x54、length@+0x58（相对 record 0 起点）/ EXTH 旗标@+0x80。
  let encoding = 65001;
  let title = '';
  let author = '';
  const mobiMagic = r0.byteLength >= 20 ? v0.getUint32(16, true) : 0;
  if (mobiMagic === FOURCC('MOBI')) {
    encoding = u32(v0, 16 + 12) || 65001;
    const nameOff = u32(v0, 16 + 0x54);
    const nameLen = u32(v0, 16 + 0x58);
    if (nameLen > 0 && nameLen < 4096 && nameOff + nameLen <= r0.byteLength) {
      title = new TextDecoder('utf-8').decode(r0.subarray(nameOff, nameOff + nameLen)).replace(/\0+$/, '').trim();
    }
    const exthFlags = u32(v0, 16 + 0x80);
    if (exthFlags & 0x40) {
      const mobiHeaderLen = u32(v0, 16 + 4);
      const exthBase = 16 + mobiHeaderLen;
      if (exthBase + 12 <= r0.byteLength && v0.getUint32(exthBase, true) === FOURCC('EXTH')) {
        const count = u32(v0, exthBase + 8);
        let p = exthBase + 12;
        for (let i = 0; i < count && p + 8 <= r0.byteLength; i++) {
          const type = v0.getUint32(p, true);
          const size = v0.getUint32(p + 4, true);
          if (size < 8 || p + size > r0.byteLength) break;
          if (type === 100 && !author) { // EXTH 100 = author（重复记录取首条）
            author = new TextDecoder('utf-8').decode(r0.subarray(p + 8, p + size)).replace(/\0+$/, '').trim();
          }
          p += size;
        }
      }
    }
  }

  // 文本记录（record 1..textRecordCount）解压拼接 → textLength 截断。
  const decoder = new TextDecoder(MOBI_ENCODINGS[encoding] || 'utf-8');
  let html;
  if (compression === 1) {
    const chunks = [];
    let total = 0;
    for (let i = 1; i <= textRecordCount && i < records.length; i++) {
      const rec = records[i];
      chunks.push(rec);
      total += rec.byteLength;
      if (total >= textLength) break;
    }
    const merged = new Uint8Array(Math.min(total, textLength || total));
    let at = 0;
    for (const c of chunks) {
      const take = Math.min(c.byteLength, merged.byteLength - at);
      merged.set(c.subarray(0, take), at);
      at += take;
      if (at >= merged.byteLength) break;
    }
    html = decoder.decode(merged);
  } else {
    const out = [];
    let total = 0;
    for (let i = 1; i <= textRecordCount && i < records.length; i++) {
      const dec = palmDocLz77Decode(records[i]);
      out.push(dec);
      total += dec.byteLength;
      if (total >= textLength) break;
    }
    if (total > MAX_TEXT_BYTES) return { ok: false, reason: 'too-big' };
    const merged = new Uint8Array(total);
    let at = 0;
    for (const c of out) { merged.set(c, at); at += c.byteLength; }
    html = decoder.decode(merged.subarray(0, Math.min(textLength || total, total)));
  }
  return { ok: true, title, author, html };
}

// ── HTML 清洗 + 伪章节切段 ──────────────────────────────────────────────────

/** MOBI HTML → 伪章节（设计稿 §3.2：按 <mbp:pagebreak> / <h2> 切段）。
 *  去脚本（epub.js 同纪律）；章节容器过 epub.js 同款只读色清洗（让位主题层）。
 *  导出供 tests/reader-p3.spec.mjs 页内钉扎。 */
export function mobiChaptersFromHtml(html, fallbackTitle) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('script').forEach((s) => s.remove());

  const body = doc.body;
  /** 切段谓词：优先 <mbp:pagebreak>，无则 <h2>（≥2 个才切段）。 */
  const hasBreak = Array.from(body.children).some((el) => el.tagName === 'MBP:PAGEBREAK');
  let isSplit = null;
  if (hasBreak) isSplit = (el) => el.tagName === 'MBP:PAGEBREAK';
  else {
    const h2count = body.querySelectorAll('h2').length;
    if (h2count >= 2) isSplit = (el) => el.tagName === 'H2';
  }

  const groups = [[]];
  if (isSplit) {
    for (const child of Array.from(body.childNodes)) {
      if (child.nodeType === 1 && isSplit(child)) {
        groups.push([]);
        // h2 界 = 新章题，留在新章作首节点（段题来源）；mbp:pagebreak 丢弃。
        if (child.tagName !== 'MBP:PAGEBREAK') groups[groups.length - 1].push(child);
      } else {
        groups[groups.length - 1].push(child);
      }
    }
  } else {
    groups[0] = Array.from(body.childNodes);
  }

  const chapters = [];
  for (const nodes of groups) {
    const wrap = doc.createElement('div');
    for (const n of nodes) wrap.appendChild(n);
    sanitizeInlineStyles(wrap);
    const text = wrap.textContent || '';
    if (!text.trim()) continue;
    const h = wrap.querySelector('h1, h2, h3, h4, h5, h6');
    const title = h ? (h.textContent || '').trim() : '';
    chapters.push({ title, root: wrap, text });
  }
  if (!chapters.length) {
    chapters.push({ title: fallbackTitle, root: (() => { const d = doc.createElement('div'); d.textContent = (html || '').slice(0, 4000); return d; })(), text: html || '' });
  }
  return chapters;
}

// ── 可见降级：仅下载（加密 / 不支持的压缩） ─────────────────────────────────

/** 「仅下载」降级面（设计稿 §3.1：加密书不给解析）。字节已在手——直接
 *  object URL 触发下载，零服务端新增面。 */
function renderDownloadOnly(pane, bytes, fileName, messageKey) {
  const box = document.createElement('div');
  box.className = 'canvas-error';
  const msg = document.createElement('div');
  msg.textContent = t(messageKey);
  box.appendChild(msg);
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'viewer-retry-btn';
  btn.textContent = t('mobi.download');
  btn.setAttribute('data-nf-download', '1');
  btn.addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName || 'book';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  });
  box.appendChild(btn);
  pane.innerHTML = '';
  pane.appendChild(box);
}

// ── Viewer 入口 ─────────────────────────────────────────────────────────────

async function viewMobi(pane, ctx) {
  const { absPath, fileName, size, objectUrl } = ctx;
  // 旧 chrome 实例先卸（document 级钩子清理），同 epub.js 入口纪律。
  if (/** @type {any} */ (pane)._nfReaderTeardown) {
    try { /** @type {any} */ (pane)._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    /** @type {any} */ (pane)._nfReaderTeardown = null;
  }
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading ebook...</div>`;
  if (!absPath && !objectUrl) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for MOBI viewer.</div>`;
    return;
  }
  // 切书统一 revoke（与 epub.js 同账本纪律）。
  resetBookBlobUrls(pane);

  // 取件腿 = epub.js 同构：nfViewerUrl（账本命中零取数）/ 票据腿失败恰好一次重取。
  const fetchBytes = async (/** @type {string} */ u) => {
    const resp = await fetch(u);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.arrayBuffer();
  };
  let retried = false;
  /** @type {ArrayBuffer} */
  let buffer;
  try {
    try {
      buffer = await fetchBytes(await nfViewerUrl({ absPath, objectUrl, pane, size }));
    } catch (err) {
      if (retried || !absPath || objectUrl) throw err;
      retried = true;
      buffer = await fetchBytes(await reMint(absPath));
    }
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render MOBI: ${escapeHtml(err && err.message ? err.message : String(err))}</div>`;
    return;
  }

  const u8 = new Uint8Array(buffer);

  // ① azw3（KF8）/ 改名 epub：扫 PK 边界 → 现有 EPUB 管线零改动复用。
  const zip = findEmbeddedZip(u8);
  if (zip) {
    await renderEpubBuffer(pane, zip.buffer, fileName || 'AZW3', {
      format: 'azw3',
      bookRef: absPath || ('attachment:' + (fileName || 'azw3')),
    });
    return;
  }

  // ② mobi：PalmDB + PalmDOC LZ77 + MOBI 头。
  const book = parseMobiBook(u8);
  if (!book.ok) {
    const key = {
      encrypted: 'mobi.encrypted',
      unsupported: 'mobi.unsupported',
      'too-big': 'mobi.tooBig',
      invalid: 'mobi.invalid',
    }[book.reason] || 'mobi.invalid';
    renderDownloadOnly(pane, buffer, fileName, key);
    return;
  }

  const bookTitle = book.title || fileName || 'MOBI';
  const chapters = mobiChaptersFromHtml(book.html, bookTitle);
  /** 章纯文本缓存（标注定位/回渲用）。 */
  const chapterTextCache = new Map();
  const chapterText = (i) => {
    if (chapterTextCache.has(i)) return chapterTextCache.get(i);
    const text = chapters[i] ? chapters[i].text : '';
    chapterTextCache.set(i, text);
    return text;
  };

  const adapter = {
    title: bookTitle,
    chapterCount: chapters.length,
    chapterTitle: (i) => (chapters[i] ? chapters[i].title || `${bookTitle} · ${i + 1}` : ''),
    toc: chapters.length > 1
      ? chapters.map((c, i) => ({ label: c.title || `${bookTitle} · ${i + 1}`, depth: 0, chapterIdx: i, anchor: '' }))
      : null,
    themeable: true,
    contentKind: 'flow',
    tracking: false, // epub-page-change 是 epub 专属事件名（P1 既定），mobi 不派发。
    annotatable: {
      format: 'mobi',
      bookRef: absPath || ('attachment:' + (fileName || 'mobi')),
      chapterText,
    },
    mountChapter(el, i) {
      const chapter = chapters[i];
      if (!chapter) return;
      el.dataset.format = 'mobi';
      el.replaceChildren(...Array.from(chapter.root.childNodes).map((n) => n.cloneNode(true)));
    },
  };

  mountReader(pane, adapter);
}

export default {
  name: 'mobi',
  label: 'MOBI / AZW3',
  extensions: ['.mobi', '.azw3'],
  binary: true,
  priority: 0,
  render: viewMobi,
};
