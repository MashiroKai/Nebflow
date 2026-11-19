// viewers/epub.js — EPUB viewer (paginated reader with chapter navigation).
// Migrated verbatim from fileViewers.js (viewEpub + renderEpubReader).
// ZIP parsing lives in ./shared.js (parseZipEntries / decompressZipEntry).
//
// 阅读器 P0 止血批（设计稿 20261030_181655_reader-experience-design §1.1/§1.2/§2.1/§6）：
//   · 图片根治——manifest 双索引（id 序 + 归一 href → zip entry）+ 章节 DOM 化
//     资源改写（blob: object URL，同书缓存、切书统一 revoke）+ 缺失 data-nf-res-miss
//     一次性 toast + 单图 8MB 闸（data-nf-res-too-big 占位）+ 章节 head 样式
//     提取 scoped 注入（scoped 前缀 + 只读清洗 color 族声明）。
//   · 三主题（纸质/明亮/暗夜）+ 字号/行距/段距/字族档位，全部持久化
//     localStorage `nf.reader.prefs`（A11）。
//   · 点按 1/3-1/3-1/3 分区（A4）：左翻上页、右翻下页、中间唤阅读菜单；
//     菜单开时正文点按仅收菜单不翻页；选区守卫维持既有口径。
//
// 阅读器 P1 统一批（同设计稿 §2.2/§2.3/§3.1/§4.1）：渲染面改挂统一阅读
// chrome（./readerChrome.js——底栏重排/进度条滑杆+章节刻度/TOC 抽屉/240ms
// 双页夹翻页/触滑跟手/reduced-motion/翻页·滚动双模式，epub/txt/md 共内核）；
// 本件只留 EPUB 专属：ZIP/OPF 解析、资源改写、章节装载、TOC 提取
// （OPF2 NCX navMap + OPF3 nav.xhtml）、章内锚点定位、epub-page-change 追踪。

import { escapeHtml, parseZipEntries, decompressZipEntry, decompressZipEntryBytes } from './shared.js';
import { mountReader } from './readerChrome.js';
import { reMint } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';
import { t } from '../i18n.js';

/** 单图体积闸（设计稿 §1.1-3：> 8MB 不解压为 object URL，占位可见降级）。 */
const SINGLE_IMAGE_BYTES = 8 * 1024 * 1024;

/** 资源引用属性（设计稿 §1.1-2 清单）。值按「相对章节路径归一 → 资源索引」解析。 */
const RESOURCE_ATTRS = ['src', 'href', 'xlink:href'];

/** 书内硬编码色只读清洗的四条属性（设计稿 §1.2：值非 inherit/none/currentColor
 *  即删，让位主题层；background 的图片类声明（含 url()）保留）。 */
const COLOR_PROPS = new Set(['color', 'background', 'background-color', '-webkit-text-fill-color']);
const KEPT_COLOR_VALUES = new Set(['inherit', 'none', 'currentcolor', 'initial', 'unset', 'revert']);

// ── 路径归一（根因 R2/R3：href 未 URL-decode + regex 归一不健壮） ──────────

/** decodeURIComponent（容错：畸形 % 序列保留原样）+ `/` 分隔 + `.`/`..` 段展开
 *  + 去重斜杠。`baseDir` = 引用方所在 zip 目录（OPF 目录/章节目录/样式表目录），
 *  带尾 `/`。 */
function normalizeZipPath(baseDir, ref) {
  let decoded = String(ref || '');
  try { decoded = decodeURIComponent(decoded); } catch { /* 畸形编码：按原样参与归一 */ }
  decoded = decoded.replace(/\\/g, '/');
  const full = decoded.startsWith('/') ? decoded.slice(1) : baseDir + decoded;
  const out = [];
  for (const seg of full.split('/')) {
    if (!seg || seg === '.') continue;
    if (seg === '..') { out.pop(); continue; }
    out.push(seg);
  }
  return out.join('/');
}

/** zip entry 名归一索引：归一形 → 真实 entry 名（entry 名经 TextDecoder 已解码，
 *  与 OPF href decode 后同域）。spine 外裸路径引用（R4）经此回查 zip 全集。 */
function buildEntriesIndex(entries) {
  const map = new Map();
  for (const name of Object.keys(entries)) map.set(normalizeZipPath('', name), name);
  return map;
}

/** 目录部取尾：'OEBPS/text/ch1.xhtml' → 'OEPBS/text/' 形态（带尾斜杠）。 */
function dirOf(normPath) {
  return normPath.includes('/') ? normPath.slice(0, normPath.lastIndexOf('/') + 1) : '';
}

// ── 书内硬编码色只读清洗（A3 另一半） ───────────────────────────────────────

/** 该声明是否保留：四属性遇白名单值放行；background 图片类（url()）放行；
 *  其余具体色值删除（让位主题层）。 */
function keepDeclaration(prop, value) {
  if (!COLOR_PROPS.has(prop)) return true;
  if (prop === 'background' && /url\(/i.test(value)) return true; // 图片类声明保留
  return KEPT_COLOR_VALUES.has(value.trim().toLowerCase());
}

/** 扫描一条声明块（prop:value; 序列），跳过 url(...) 括号深度内的分号。
 *  返回清洗后的块文本。 */
function sanitizeDeclarationBlock(body) {
  const out = [];
  let i = 0;
  const n = body.length;
  while (i < n) {
    while (i < n && /[\s;]/.test(body[i])) i++;
    if (i >= n) break;
    let j = i;
    while (j < n && /[\w-]/.test(body[j])) j++;
    const prop = body.slice(i, j).toLowerCase();
    let k = j;
    while (k < n && /\s/.test(body[k])) k++;
    if (!prop || body[k] !== ':') {
      // 非声明片段：原样跳过到下一分号
      while (k < n && body[k] !== ';') k++;
      i = k + 1;
      continue;
    }
    k++;
    let depth = 0;
    let vEnd = k;
    while (vEnd < n) {
      const ch = body[vEnd];
      if (ch === '(') depth++;
      else if (ch === ')') depth = Math.max(0, depth - 1);
      else if (ch === ';' && depth === 0) break;
      vEnd++;
    }
    const value = body.slice(k, vEnd).trim();
    if (keepDeclaration(prop, value)) out.push(`${prop}:${value};`);
    i = vEnd + 1;
  }
  return out.join(' ');
}

/** 内联 style 只读清洗：color/background/background-color/-webkit-text-fill-color
 *  具体色值删除，排版声明（字体/字号/行距/对齐/斜体）全保留（保真优先）。
 *  P3 批：导出供 mobi.js 复用（同一清洗纪律，防第二实现漂移）。 */
export function sanitizeInlineStyles(root) {
  const els = root.querySelectorAll('[style]');
  for (const el of els) {
    const st = el.style;
    for (let i = st.length - 1; i >= 0; i--) {
      const prop = st[i];
      if (!keepDeclaration(prop, st.getPropertyValue(prop))) st.removeProperty(prop);
    }
  }
}

/** 提取 CSS 文本中的 url(...) 引用（配平引号），按序交 resolve 改写。
 *  外部协议（https?:/data:/blob:）原样保留；resolve 返回 null → 写 none
 *  （不阻塞渲染、不发 404 噪声）。 */
async function rewriteCssUrlsAsync(css, baseDir, resolve) {
  const re = /url\(\s*(['"]?)([^'")]+)\1\s*\)/g;
  let out = '';
  let last = 0;
  let m;
  while ((m = re.exec(css)) !== null) {
    out += css.slice(last, m.index);
    const raw = m[2].trim();
    if (/^(https?:|data:|blob:)/i.test(raw)) {
      out += m[0]; // 外部/内嵌资源原样保留
    } else {
      const resolved = await resolve(normalizeZipPath(baseDir, raw));
      out += resolved ? `url("${resolved}")` : 'none';
    }
    last = m.index + m[0].length;
  }
  return out + css.slice(last);
}

/** CSS 规则递归改写：声明块只读清洗 + url(...) 改写；选择器挂 scope 前缀
 *  （@media/@supports/@layer/@container 递归；@keyframes 只清洗不挂前缀——
 *    关键帧选择器 from/to/% 无 scope 语义；@font-face/@page 原样保真）。 */
async function rewriteBookCss(css, baseDir, scope, resolve) {
  let out = '';
  let i = 0;
  const n = css.length;
  while (i < n) {
    let j = i;
    while (j < n && css[j] !== '{' && css[j] !== ';') j++;
    if (j >= n) { out += css.slice(i); break; }
    const prelude = css.slice(i, j).trim();
    if (css[j] === ';') { // @import/@charset 等无块语句原样
      out += css.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    let depth = 1;
    let k = j + 1;
    while (k < n && depth > 0) {
      if (css[k] === '{') depth++;
      else if (css[k] === '}') depth--;
      k++;
    }
    const body = css.slice(j + 1, k - 1);
    i = k;
    if (!prelude) continue;
    if (prelude.startsWith('@')) {
      const atName = prelude.split(/\s+/)[0].toLowerCase();
      if (['@media', '@supports', '@layer', '@container'].includes(atName)) {
        out += `${prelude}{${await rewriteBookCss(body, baseDir, scope, resolve)}}`;
      } else if (atName === '@keyframes') {
        out += `${prelude}{${await rewriteBookCss(body, baseDir, '', resolve)}}`;
      } else {
        out += `${prelude}{${body}}`;
      }
    } else {
      const scoped = scope
        ? prelude.split(',').map((selRaw) => {
            const s = selRaw.trim();
            if (!s) return s;
            // html/body 起手替换为 scope（书内 body 即阅读器内容容器）
            if (/^(html|body)(?=$|[\s\[.:>#])/.test(s)) return s.replace(/^(html|body)/, scope);
            return `${scope} ${s}`;
          }).join(',')
        : prelude;
      const bodyRewritten = await rewriteCssUrlsAsync(body, baseDir, resolve);
      out += `${scoped}{${sanitizeDeclarationBlock(bodyRewritten)}}`;
    }
  }
  return out;
}

// ── 每本书的资源上下文（同书 blob 缓存 + manifest 双索引 + 账本） ──────────

/** 无 manifest 登记的 spine 外资源按扩展名嗅探 MIME（blob type 用）。 */
function sniffMediaType(entryName) {
  const ext = (entryName.split('.').pop() || '').toLowerCase();
  return {
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
    webp: 'image/webp', svg: 'image/svg+xml', css: 'text/css',
    woff: 'font/woff', woff2: 'font/woff2', ttf: 'font/ttf', otf: 'font/otf',
    mp3: 'audio/mpeg', mp4: 'video/mp4', webm: 'video/webm',
  }[ext] || 'application/octet-stream';
}

/** 每本书的资源上下文：manifest 归一索引 + blob URL 缓存（同书去重）。 */
function createBookResources(entries, entriesByNorm, opfDir) {
  const index = new Map();
  return {
    entries,
    /** blob URL 缓存：entryName → object URL（切书统一 revoke）。 */
    blobUrls: new Map(),
    /** 登记 manifest 项（解析 OPF 时调用）：归一 zip 形 → {entryName, mediaType}。 */
    addManifestItem(href, mediaType) {
      const norm = normalizeZipPath(opfDir, href);
      const entryName = entriesByNorm.get(norm);
      if (entryName) index.set(norm, { entryName, mediaType: mediaType || '' });
    },
    /** 查询：manifest 内直查；miss 回查 zip 全集（R4 spine 外裸路径）。 */
    lookup(normPath) {
      const hit = index.get(normPath);
      if (hit) return hit;
      const entryName = entriesByNorm.get(normPath);
      if (!entryName) return null;
      return { entryName, mediaType: sniffMediaType(entryName) };
    },
  };
}

/** 取资源 blob URL：命中缓存直返；未命中解压入 ZIP_BUDGET 账本 → blob。
 *  @returns {Promise<{url: string, tooBig: boolean} | null>} null = 未命中 */
async function bookResourceUrl(res, normPath, gateSingleImage) {
  const hit = res.lookup(normPath);
  if (!hit) return null;
  const cached = res.blobUrls.get(hit.entryName);
  if (cached) return { url: cached, tooBig: false };
  const entry = res.entries[hit.entryName];
  const declared = (typeof entry.uncompSize === 'number' && entry.uncompSize > 0)
    ? entry.uncompSize
    : (entry.compMethod === 0 ? entry.data.byteLength : 0);
  // 单图 8MB 闸（设计稿 §1.1-3）：declared 预判 + 实际字节双检
  if (gateSingleImage && declared > SINGLE_IMAGE_BYTES) return { url: '', tooBig: true };
  const bytes = await decompressZipEntryBytes(entry);
  if (gateSingleImage && bytes.byteLength > SINGLE_IMAGE_BYTES) return { url: '', tooBig: true };
  const url = URL.createObjectURL(new Blob([bytes], { type: hit.mediaType || sniffMediaType(hit.entryName) }));
  res.blobUrls.set(hit.entryName, url);
  return { url, tooBig: false };
}

/** 元素标记（可见降级纪律）：miss → 摘资源属性（防裸相对 URL 404 噪声）+
 *  data-nf-res-miss；超限 → 占位。 */
function markMiss(el) {
  el.removeAttribute('src');
  el.removeAttribute('srcset');
  el.removeAttribute('href');
  el.removeAttribute('xlink:href');
  el.setAttribute('data-nf-res-miss', '');
}
function markTooBig(el) {
  el.setAttribute('data-nf-res-too-big', '');
  el.setAttribute('aria-label', t('epub.imgTooBig'));
}

/** 章节 DOM 资源改写（渲染前改写，零 iframe 零新依赖）。
 *  @returns {Promise<number>} 该章缺失资源计数 */
async function rewriteChapterResources(res, chapterDoc, chapterDir) {
  let missCount = 0;

  const resolveElRef = async (el, rawRef, isImage) => {
    const got = await bookResourceUrl(res, normalizeZipPath(chapterDir, rawRef), isImage);
    if (!got) { markMiss(el); missCount++; return null; }
    if (got.tooBig) { markTooBig(el); return null; }
    return got.url;
  };

  // 元素资源引用属性（img/image/svg 系/source/audio/video/track）
  const els = chapterDoc.querySelectorAll('[src], [href], [xlink\\:href]');
  for (const el of els) {
    const tag = el.localName;
    if (tag === 'a' || tag === 'link') continue; // 锚点保留语义；stylesheet 下方内联注入
    const isImage = tag === 'img' || tag === 'image' || tag === 'svg' || tag === 'use' || el.closest('svg');
    for (const attr of RESOURCE_ATTRS) {
      if (!el.hasAttribute(attr)) continue;
      const raw = el.getAttribute(attr);
      if (!raw || /^(https?:|data:|blob:|mailto:|#)/i.test(raw)) continue;
      const url = await resolveElRef(el, raw, !!isImage);
      if (url) el.setAttribute(attr, url);
    }
    // srcset：逗号分隔候选，各段首 token 为 URL
    if (el.hasAttribute('srcset')) {
      const raw = el.getAttribute('srcset');
      const rewritten = [];
      let missed = false;
      for (const cand of raw.split(',')) {
        const parts = cand.trim().split(/\s+/);
        if (!parts[0]) continue;
        if (/^(https?:|data:|blob:)/i.test(parts[0])) { rewritten.push(cand.trim()); continue; }
        const got = await bookResourceUrl(res, normalizeZipPath(chapterDir, parts[0]), !!isImage);
        if (!got) { missed = true; rewritten.push(cand.trim()); }
        else if (got.tooBig) { markTooBig(el); rewritten.push(cand.trim()); }
        else rewritten.push([got.url, ...parts.slice(1)].join(' '));
      }
      if (missed) { markMiss(el); missCount++; }
      else el.setAttribute('srcset', rewritten.join(', '));
    }
  }

  // 内联 style 的 url(...)（改写后做只读清洗）
  const cssResolve = async (norm) => {
    const got = await bookResourceUrl(res, norm, true);
    return got && !got.tooBig ? got.url : null;
  };
  const styled = chapterDoc.querySelectorAll('[style]');
  for (const el of styled) {
    const st = el.getAttribute('style');
    if (!st || !/url\(/i.test(st)) continue;
    el.setAttribute('style', await rewriteCssUrlsAsync(st, chapterDir, cssResolve));
  }
  sanitizeInlineStyles(chapterDoc);

  return missCount;
}

/** 提取章节样式（<style> 块 + manifest 内 link[rel=stylesheet]）：url(...) 异步
 *  改写入同书缓存 → 声明只读清洗 → 选择器挂 .epub-reader-content scope →
 *  返回可注入的 CSS 文本。原 <style>/<link> 节点由 loadChapter 摘除（防全局泄漏）。 */
async function extractChapterStyles(res, chapterDoc, chapterDir) {
  const out = [];
  const cssResolve = async (norm) => {
    const got = await bookResourceUrl(res, norm, true);
    return got && !got.tooBig ? got.url : null;
  };
  for (const st of chapterDoc.querySelectorAll('style')) {
    const css = st.textContent || '';
    if (!css.trim()) continue;
    out.push(await rewriteBookCss(css, chapterDir, '.epub-reader-content', cssResolve));
  }
  for (const link of chapterDoc.querySelectorAll('link[rel~="stylesheet"][href]')) {
    const raw = link.getAttribute('href');
    const norm = raw && !/^(https?:|data:)/i.test(raw) ? normalizeZipPath(chapterDir, raw) : null;
    const hit = norm && res.lookup(norm);
    link.remove(); // 无论命中与否都摘除：不命中不留会 404 的裸 href
    if (!hit) continue;
    try {
      const text = new TextDecoder().decode(await decompressZipEntryBytes(res.entries[hit.entryName]));
      // 样式表内 url() 相对样式表自身目录解析
      out.push(await rewriteBookCss(text, dirOf(norm), '.epub-reader-content', cssResolve));
    } catch { /* 账本超限等：该样式表降级跳过 */ }
  }
  return out.join('\n');
}

// ── TOC 提取（设计稿 §2.1：解析 OPF NCX / nav.xhtml 出目录） ────────────────

/** OPF2 toc.ncx：navMap > navPoint 递归（depth = 嵌套深），content@src →
 *  章序 + 章内锚。href 相对 NCX 自身目录解析（R2 同纪律：先 decode 再归一）。 */
async function parseNcxToc(entries, entriesByNorm, ncxNorm, chapterOfNorm) {
  const entryName = entriesByNorm.get(ncxNorm);
  if (!entryName) return [];
  let raw;
  try { raw = await decompressZipEntry(entries[entryName]); } catch { return []; }
  const doc = new DOMParser().parseFromString(raw, 'application/xml');
  const ncxDir = dirOf(ncxNorm);
  const out = [];
  const walk = (node, depth) => {
    for (const np of Array.from(node.children)) {
      if (!/navPoint$/.test(np.tagName)) continue;
      const labelEl = np.getElementsByTagName('navLabel')[0];
      const textEl = labelEl ? labelEl.getElementsByTagName('text')[0] : null;
      const contentEl = np.getElementsByTagName('content')[0];
      const src = contentEl ? contentEl.getAttribute('src') : '';
      const label = textEl ? (textEl.textContent || '').trim() : '';
      let chapterIdx = -1;
      let anchor = '';
      if (src) {
        const hash = src.indexOf('#');
        const pathPart = hash >= 0 ? src.slice(0, hash) : src;
        anchor = hash >= 0 ? src.slice(hash + 1) : '';
        const ch = chapterOfNorm.get(normalizeZipPath(ncxDir, pathPart));
        if (typeof ch === 'number') chapterIdx = ch;
      }
      if (label && chapterIdx >= 0) out.push({ label, depth, chapterIdx, anchor });
      walk(np, depth + 1);
    }
  };
  const navMap = doc.getElementsByTagName('navMap')[0];
  if (navMap) walk(navMap, 0);
  return out;
}

/** OPF3 nav.xhtml：`<nav epub:type="toc">` 首个 ol 递归；li > a[href] →
 *  章序 + 章内锚；li > span（无链）仅作层级标题（不可点，落在可解析子项）。 */
async function parseNavToc(entries, entriesByNorm, navNorm, chapterOfNorm) {
  const entryName = entriesByNorm.get(navNorm);
  if (!entryName) return [];
  let raw;
  try { raw = await decompressZipEntry(entries[entryName]); } catch { return []; }
  const doc = new DOMParser().parseFromString(raw, 'application/xhtml+xml');
  const navDir = dirOf(navNorm);
  const out = [];
  let nav = null;
  for (const n of Array.from(doc.getElementsByTagName('nav'))) {
    const type = n.getAttribute('epub:type') ||
      n.getAttributeNS('http://www.idpf.org/2007/ops', 'type') || '';
    if (type === 'toc') { nav = n; break; }
  }
  if (!nav) nav = doc.getElementsByTagName('nav')[0] || null;
  if (!nav) return [];
  const walkList = (ol, depth) => {
    for (const li of Array.from(ol.children)) {
      if (li.tagName.toLowerCase() !== 'li') continue;
      const a = li.getElementsByTagName('a')[0] || null;
      const span = li.getElementsByTagName('span')[0] || null;
      const label = (((a || span) && (a || span).textContent) || '').trim();
      let chapterIdx = -1;
      let anchor = '';
      if (a && a.getAttribute('href')) {
        const href = a.getAttribute('href');
        const hash = href.indexOf('#');
        const pathPart = hash >= 0 ? href.slice(0, hash) : href;
        anchor = hash >= 0 ? href.slice(hash + 1) : '';
        const ch = chapterOfNorm.get(normalizeZipPath(navDir, pathPart));
        if (typeof ch === 'number') chapterIdx = ch;
      }
      if (label && chapterIdx >= 0) out.push({ label, depth, chapterIdx, anchor });
      for (const sub of Array.from(li.children)) {
        if (sub.tagName.toLowerCase() === 'ol') walkList(sub, depth + 1);
      }
    }
  };
  const firstOl = nav.getElementsByTagName('ol')[0];
  if (firstOl) walkList(firstOl, 0);
  return out;
}

/** EPUB viewer — paginated reader with chapter navigation.
 *  Parses ZIP structure, extracts XHTML chapters, renders with CSS column pagination.
 *  Dispatches 'epub-page-change' events for tracking what user is reading.
 *
 *  `objectUrl` = 附件预览腿（attachmentPreview.js）：整件字节已由应用内鉴权路由取回，
 *  blob: URL 直接 fetch 成 `arrayBuffer` 交给 ZIP 解析，无本机路径 ⇒ 不触票据链。
 *  与 `pdf.js` / `image.js` 的 `objectUrl` 腿**同构**（同一模式，非第二套取字节路）。 */
async function viewEpub(pane, { absPath, fileName, size, objectUrl }) {
  // 旧 chrome 实例（切书/重开）先卸：撤 fab 让位、面板主题监听等 document 级钩子。
  if (/** @type {any} */ (pane)._nfReaderTeardown) {
    try { /** @type {any} */ (pane)._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    /** @type {any} */ (pane)._nfReaderTeardown = null;
  }
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading ebook...</div>`;
  if (!absPath && !objectUrl) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for EPUB viewer.</div>`;
    return;
  }
  // 切书/重开：先 revoke 上一本书的全部 object URL（同书缓存、切书统一 revoke）。
  resetBookBlobUrls(pane);
  // M2 账本接缝（imgdoor #542 推广批）：票据腿经 nfViewerUrl——账本命中 = blob URL
  // 直开（第二次打开零取数），未命中走既有票据链并入账 `nf:<absPath>`。
  // #762 同族盘点批：本 viewer 是 office 族里最后一个**裸取件**（无 reMint 自愈）
  // 的票据腿。修 = 镜像 xlsx.js/docx.js 的 T3 形态：nfViewerUrl（账本未命中时内部
  // 就地取件，401/403 在此抛出）进 try ⇒ 取件失败恰好一次重取票＋重试；第二败即
  // 定局，落下方既有错误面板。blob 腿（objectUrl）无可刷新的凭据 ⇒ 不重试
  // （image.js 同守卫）。
  const fetchBytes = async (/** @type {string} */ u) => {
    const resp = await fetch(u);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    return resp.arrayBuffer();
  };
  let retried = false;
  try {
    /** @type {ArrayBuffer} */
    let buffer;
    try {
      buffer = await fetchBytes(await nfViewerUrl({ absPath, objectUrl, pane }));
    } catch (err) {
      if (retried || !absPath || objectUrl) throw err;
      retried = true;
      buffer = await fetchBytes(await reMint(absPath));
    }
    await renderEpubBuffer(pane, buffer, fileName || 'EPUB', {
      format: 'epub',
      bookRef: absPath || ('attachment:' + (fileName || 'epub')),
    });
  } catch (err) {
    if (!pane.querySelector('.canvas-error')) {
      pane.innerHTML = `<div class="canvas-error">Failed to render EPUB: ${escapeHtml(err.message)}</div>`;
    }
  }
}

/** 从 EPUB（zip）字节直渲染统一阅读 chrome——P3 批抽出供 azw3 腿复用：
 *  azw3 = KF8 内嵌 EPUB，mobi.js 扫 PK 边界切出 zip 字节后**零改动**进本管线
 *  （设计稿 §3.2 路线 A：「直接喂现有 EPUB viewer，现有管线零改动复用」）。
 *  blob 账本接管（pane._epubBlobUrls）在解析中途失败时也随下次入口统一 revoke。 */
export async function renderEpubBuffer(pane, buffer, title, opts) {
  try {
    const entries = parseZipEntries(buffer);
    if (!entries['META-INF/container.xml']) throw new Error('Not a valid EPUB file');

    // Parse container.xml → OPF path
    const containerRaw = await decompressZipEntry(entries['META-INF/container.xml']);
    const containerDoc = new DOMParser().parseFromString(containerRaw, 'application/xml');
    const rootfileEl = containerDoc.querySelector('rootfile');
    if (!rootfileEl) throw new Error('EPUB: container.xml missing rootfile');
    const opfPath = rootfileEl.getAttribute('full-path');
    const opfDir = dirOf(opfPath);

    // Parse OPF — manifest 双索引（设计稿 §1.1-1）：id → item（spine 序用）+
    // 归一 href → zip entry（资源回查用，含 spine 外裸路径回查）
    const opfRaw = await decompressZipEntry(entries[opfPath]);
    const opfDoc = new DOMParser().parseFromString(opfRaw, 'application/xml');
    const manifest = {};
    opfDoc.querySelectorAll('manifest > item').forEach(item => {
      manifest[item.getAttribute('id')] = {
        href: item.getAttribute('href'),
        mediaType: item.getAttribute('media-type') || '',
      };
    });
    const spineIds = [];
    opfDoc.querySelectorAll('spine > itemref').forEach(itemref => {
      spineIds.push(itemref.getAttribute('idref'));
    });

    const entriesByNorm = buildEntriesIndex(entries);
    const resources = createBookResources(entries, entriesByNorm, opfDir);
    pane._epubBlobUrls = resources.blobUrls; // 早期移交：解析中途失败也随下次入口统一 revoke
    for (const id of Object.keys(manifest)) {
      const item = manifest[id];
      if (item.href) resources.addManifestItem(item.href, item.mediaType);
    }

    // Pre-load all chapter contents（章节 DOM 化：去脚本 + 资源改写 + 样式提取）
    const chapters = [];
    /** 章归一路径 → 章序（TOC 条目 href 定位用）。 */
    const chapterOfNorm = new Map();
    for (const id of spineIds) {
      const item = manifest[id];
      if (!item || !item.href) continue;
      const norm = normalizeZipPath(opfDir, item.href);
      const entryName = entriesByNorm.get(norm);
      if (!entryName) continue;
      const html = await decompressZipEntry(entries[entryName]);
      const doc = new DOMParser().parseFromString(html, 'text/html');
      // 脚本剥离维持既有口径（epub.js:86 同纪律：script 元素移除，其余保留）
      doc.querySelectorAll('script').forEach((s) => s.remove());
      const chapterDir = dirOf(norm);
      const missCount = await rewriteChapterResources(resources, doc, chapterDir);
      const scopedCss = await extractChapterStyles(resources, doc, chapterDir);
      const titleMatch = doc.body.querySelector('h1, h2, h3, h4, h5, h6');
      const title = titleMatch ? titleMatch.textContent.trim() : `Chapter ${chapters.length + 1}`;
      chapterOfNorm.set(norm, chapters.length);
      chapters.push({ title, doc, scopedCss, missCount, norm });
    }

    if (chapters.length === 0) throw new Error('EPUB has no readable chapters');

    // TOC 提取（§2.1）：EPUB3 nav 优先，OPF2 NCX 次之；href 定位章序 + 章内锚。
    let tocEntries = [];
    const navItem = opfDoc.querySelector('manifest > item[properties~="nav"]');
    if (navItem && navItem.getAttribute('href')) {
      tocEntries = await parseNavToc(entries, entriesByNorm,
        normalizeZipPath(opfDir, navItem.getAttribute('href')), chapterOfNorm);
    }
    if (!tocEntries.length) {
      const spineTocId = opfDoc.querySelector('spine') && opfDoc.querySelector('spine').getAttribute('toc');
      let ncxItem = spineTocId ? manifest[spineTocId] : null;
      if (!ncxItem) {
        for (const id of Object.keys(manifest)) {
          if ((manifest[id].mediaType || '').includes('dtbncx')) { ncxItem = manifest[id]; break; }
        }
      }
      if (ncxItem && ncxItem.href) {
        tocEntries = await parseNcxToc(entries, entriesByNorm,
          normalizeZipPath(opfDir, ncxItem.href), chapterOfNorm);
      }
    }

    renderEpubReader(pane, chapters, tocEntries, title || 'EPUB', opts);
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render EPUB: ${escapeHtml(err.message)}</div>`;
  }
}

/** 切书/重开统一卸上一本书的 object URL（同书缓存、切书统一 revoke）。
 *  P3 批：导出供 mobi.js（azw3 → EPUB 管线腿）复用，防第二实现。 */
export function resetBookBlobUrls(pane) {
  const prevUrls = pane._epubBlobUrls;
  if (prevUrls) {
    for (const u of prevUrls.values ? prevUrls.values() : prevUrls) URL.revokeObjectURL(u);
  }
  pane._epubBlobUrls = null;
}

/** Render paginated EPUB reader UI —— 统一阅读 chrome 挂载（P1 批）：
 *  底栏/进度条/TOC 抽屉/菜单/翻页动效全在 readerChrome.js；本函数只给 EPUB
 *  正文适配器（章节装载 + 章内锚定位 + 缺失资源 toast + epub-page-change）。
 *  opts.bookRef = 标注锚（文件路径；blob 腿退化 attachment:文件名）；opts.format
 *  进标注数据面（epub / azw3——azw3 腿全量复用本管线，标注格式落 'azw3'）。 */
function renderEpubReader(pane, chapters, tocEntries, bookTitle, opts) {
  /** 当前内容盒（锚定位用；chrome 每次装载后回写）。 */
  let currentContentEl = null;
  /** 章纯文本缓存（标注定位/回渲用，懒算）。 */
  const chapterTextCache = new Map();

  /** @type {Parameters<typeof mountReader>[1]} 正文适配器（形由 readerChrome.mountReader 定） */
  const adapter = {
    title: bookTitle,
    chapterCount: chapters.length,
    chapterTitle: (i) => (chapters[i] ? chapters[i].title : ''),
    toc: tocEntries && tocEntries.length ? tocEntries : null,
    themeable: true,
    contentKind: 'flow',
    tracking: true,
    // P3 批（设计稿 §4.2）：引擎通用标注面——epub 系格式（epub/azw3）落标注。
    annotatable: {
      format: (opts && opts.format) || 'epub',
      bookRef: (opts && opts.bookRef) || ('attachment:' + bookTitle),
      chapterText(i) {
        if (chapterTextCache.has(i)) return chapterTextCache.get(i);
        const ch = chapters[i];
        const text = ch && ch.doc && ch.doc.body ? (ch.doc.body.textContent || '') : '';
        chapterTextCache.set(i, text);
        return text;
      },
    },
    /** @param {HTMLElement} el @param {number} i */
    mountChapter(el, i) {
      const chapter = chapters[i];
      if (!chapter) return;
      // cloneNode 而非搬运：章节 doc 是重复装载的源（翻页邻页层/重访章共用），
      // 搬运会把 doc 掏空导致重访空白（P0 潜在缺欠，P1 随内核化一并根治）。
      el.replaceChildren(...Array.from(chapter.doc.body.childNodes).map((n) => n.cloneNode(true)));
      // 原书内 <style> 一律摘除（防注入进应用页后全局生效），scoped 版单点注入
      el.querySelectorAll('style').forEach((s) => s.remove());
      if (chapter.scopedCss) {
        const styleEl = document.createElement('style');
        styleEl.className = 'epub-book-style';
        styleEl.textContent = chapter.scopedCss;
        el.appendChild(styleEl);
      }
      currentContentEl = el;
    },
    /** @param {number} i */
    onChapterChanged(i) {
      const chapter = chapters[i];
      // 缺失资源一次性 toast（可见降级纪律：不阻塞渲染，N = 该章 miss 计数）
      if (chapter && chapter.missCount > 0 && window.__showToast) {
        window.__showToast(t('epub.resMissing', { count: chapter.missCount }), 'info');
      }
    },
    /** @param {number} _i @param {string} anchor */
    anchorScrollTop(_i, anchor) {
      if (!anchor || !currentContentEl) return null;
      let target = null;
      try {
        target = currentContentEl.querySelector(`[id="${CSS.escape(anchor)}"]`);
      } catch { target = null; }
      if (!target) return null;
      const scroller = currentContentEl.closest('.epub-reader-viewport');
      if (!scroller) return null;
      return target.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop;
    },
  };

  mountReader(pane, adapter);
}

export default {
  name: 'epub',
  label: 'EPUB',
  extensions: ['.epub'],
  binary: true,
  priority: 0,
  render: viewEpub,
  /** Protocol teardown (阅读/编辑体验优化批 2026-11-01): the reader chrome hangs a
   *  document keydown, a documentElement MutationObserver, a matchMedia listener
   *  and a ResizeObserver that all outlive the pane — readerChrome exposes its
   *  remover as pane._nfReaderTeardown and the blob ledger is revoked here too
   *  (closing the tab was previously the one exit that never revoked the book's
   *  object URLs). */
  dispose: (pane) => {
    const slot = /** @type {any} */ (pane);
    if (typeof slot._nfReaderTeardown === 'function') {
      try { slot._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    }
    slot._nfReaderTeardown = null;
    resetBookBlobUrls(/** @type {HTMLElement} */ (pane));
  },
};
