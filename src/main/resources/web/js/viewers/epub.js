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

import { escapeHtml, parseZipEntries, decompressZipEntry, decompressZipEntryBytes } from './shared.js';
import { reMint } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';
import { t } from '../i18n.js';

/** 阅读器偏好持久化键（设计稿 §1.2：跨书跨会话生效）。 */
const PREFS_KEY = 'nf.reader.prefs';
/** 单图体积闸（设计稿 §1.1-3：> 8MB 不解压为 object URL，占位可见降级）。 */
const SINGLE_IMAGE_BYTES = 8 * 1024 * 1024;

const FONT_SIZE_STEPS = [14, 16, 18, 20, 22];
const LINE_HEIGHT_STEPS = [1.5, 1.6, 1.8, 2.0];
const PARA_SPACE_STEPS = { compact: '0.5em', standard: '1em', loose: '1.5em' };

/** 主题档位。纸质 = 设计稿给定字面量；明亮/暗夜 = base.css 面板 token
 *  亮/暗档取值（--color-surface / --color-text 的两档值，非新设计 token），
 *  保证暗夜在任何面板态下对比度 ≥4.5:1（A3 可断言）。 */
const THEMES = {
  paper: { bg: '#F7F3EA', fg: '#3B342C' },
  light: { bg: '#ffffff', fg: '#1b1e26' },
  dark: { bg: '#1c1f27', fg: '#e8eaed' },
};

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

// ── 偏好持久化 ────────────────────────────────────────────────────────────

/** @returns {{theme: string, fontSize: number, lineHeight: number, paraSpace: string, fontFamily: string}} */
function loadPrefs() {
  const defaults = { theme: 'auto', fontSize: 16, lineHeight: 1.8, paraSpace: 'standard', fontFamily: 'serif' };
  try {
    const raw = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
    const p = { ...defaults, ...(raw && typeof raw === 'object' ? raw : {}) };
    if (!['auto', 'paper', 'light', 'dark'].includes(p.theme)) p.theme = 'auto';
    if (!FONT_SIZE_STEPS.includes(p.fontSize)) p.fontSize = 16;
    if (!LINE_HEIGHT_STEPS.includes(p.lineHeight)) p.lineHeight = 1.8;
    if (!Object.keys(PARA_SPACE_STEPS).includes(p.paraSpace)) p.paraSpace = 'standard';
    if (!['serif', 'sans'].includes(p.fontFamily)) p.fontFamily = 'serif';
    return p;
  } catch { return defaults; }
}

function savePrefs(prefs) {
  try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch { /* 存储不可用时仅本次生效 */ }
}

/** 「跟随面板明暗」：显式 data-theme 优先，否则随系统色方案（设计稿 §1.2 默认）。 */
function panelDark() {
  const explicit = document.documentElement.dataset.theme;
  if (explicit === 'dark' || explicit === 'light') return explicit === 'dark';
  return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

/** 应用主题档：auto → 面板暗则暗夜、面板亮则纸质。 */
function effectiveTheme(prefs) {
  return prefs.theme === 'auto' ? (panelDark() ? 'dark' : 'paper') : prefs.theme;
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
 *  具体色值删除，排版声明（字体/字号/行距/对齐/斜体）全保留（保真优先）。 */
function sanitizeInlineStyles(root) {
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

/** EPUB viewer — paginated reader with chapter navigation.
 *  Parses ZIP structure, extracts XHTML chapters, renders with CSS column pagination.
 *  Dispatches 'epub-page-change' events for tracking what user is reading.
 *
 *  `objectUrl` = 附件预览腿（attachmentPreview.js）：整件字节已由应用内鉴权路由取回，
 *  blob: URL 直接 fetch 成 `arrayBuffer` 交给 ZIP 解析，无本机路径 ⇒ 不触票据链。
 *  与 `pdf.js` / `image.js` 的 `objectUrl` 腿**同构**（同一模式，非第二套取字节路）。 */
async function viewEpub(pane, { absPath, fileName, size, objectUrl }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading ebook...</div>`;
  if (!absPath && !objectUrl) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for EPUB viewer.</div>`;
    return;
  }
  // 切书/重开：先 revoke 上一本书的全部 object URL（同书缓存、切书统一 revoke）。
  const prevUrls = pane._epubBlobUrls;
  if (prevUrls) {
    for (const u of prevUrls.values ? prevUrls.values() : prevUrls) URL.revokeObjectURL(u);
  }
  pane._epubBlobUrls = null;
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
      chapters.push({ title, doc, scopedCss, missCount });
    }

    if (chapters.length === 0) throw new Error('EPUB has no readable chapters');

    renderEpubReader(pane, chapters, fileName || 'EPUB');
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render EPUB: ${escapeHtml(err.message)}</div>`;
  }
}

/** Render paginated EPUB reader UI — scroll-based pagination. */
function renderEpubReader(pane, chapters, bookTitle) {
  let chapterIdx = 0;
  const prefs = loadPrefs();

  pane.innerHTML = `
    <div class="epub-reader" data-epub-theme="${effectiveTheme(prefs)}">
      <div class="epub-reader-header">
        <span class="epub-chapter-title"></span>
      </div>
      <div class="epub-reader-menu" data-epub-menu="closed" hidden></div>
      <div class="epub-reader-viewport canvas-viewer-safe-top">
        <div class="epub-reader-content"></div>
      </div>
      <div class="epub-reader-nav">
        <button class="epub-nav-btn epub-prev" title="${escapeHtml(t('epub.prev'))}">‹</button>
        <span class="epub-nav-info"></span>
        <button class="epub-nav-btn epub-next" title="${escapeHtml(t('epub.next'))}">›</button>
      </div>
    </div>`;

  const reader = pane.querySelector('.epub-reader');
  const viewport = pane.querySelector('.epub-reader-viewport');
  const content = pane.querySelector('.epub-reader-content');
  const menu = pane.querySelector('.epub-reader-menu');
  const chapterTitleEl = pane.querySelector('.epub-chapter-title');
  const infoEl = pane.querySelector('.epub-nav-info');
  const prevBtn = pane.querySelector('.epub-prev');
  const nextBtn = pane.querySelector('.epub-next');

  // ── 主题/档位应用（改档即重排，无动画） ──────────────────────────────────
  function applyPrefs() {
    const theme = effectiveTheme(prefs);
    reader.dataset.epubTheme = theme;
    const palette = THEMES[theme];
    content.style.setProperty('--epub-bg', palette.bg);
    content.style.setProperty('--epub-fg', palette.fg);
    content.style.fontSize = prefs.fontSize + 'px';
    content.style.lineHeight = String(prefs.lineHeight);
    content.style.setProperty('--epub-para-space', PARA_SPACE_STEPS[prefs.paraSpace]);
    content.dataset.fontFamily = prefs.fontFamily;
    if (!menu.hidden) renderMenu();
    updateUI();
  }

  function setPrefs(patch) {
    Object.assign(prefs, patch);
    savePrefs(prefs);
    applyPrefs();
  }

  // 「跟随面板」档：面板明暗翻转时自动跟随（显式选档时不跟）
  const darkMq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  const onPanelThemeChange = () => { if (prefs.theme === 'auto') applyPrefs(); };
  if (darkMq) {
    if (darkMq.addEventListener) darkMq.addEventListener('change', onPanelThemeChange);
    else if (darkMq.addListener) darkMq.addListener(onPanelThemeChange);
  }
  const themeAttrObserver = new MutationObserver(() => { if (prefs.theme === 'auto') applyPrefs(); });
  themeAttrObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

  // ── 阅读菜单（中间 1/3 唤出；毛玻璃浮层锚定阅读器顶部，禁 overlay 暗化） ──
  const menuOpen = () => !menu.hidden;

  function renderMenu() {
    const theme = effectiveTheme(prefs);
    const cycle = (steps, current, dir) => {
      const i = steps.indexOf(current);
      return steps[(i + dir + steps.length) % steps.length];
    };
    const paraKeys = { compact: 'epub.aa.paraCompact', standard: 'epub.aa.paraStandard', loose: 'epub.aa.paraLoose' };
    menu.innerHTML = `
      <div class="epub-menu-title">${escapeHtml(chapters[chapterIdx].title)}</div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.theme.label'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.theme.label'))}</span>
        ${['paper', 'light', 'dark'].map((m) =>
          `<button type="button" class="epub-menu-btn${theme === m ? ' on' : ''}" data-epub-theme-pick="${m}">${escapeHtml(t('epub.theme.' + m))}</button>`
        ).join('')}
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.fontSize'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.fontSize'))}</span>
        <button type="button" class="epub-menu-btn" data-epub-step="fontSize:-1">−</button>
        <span class="epub-menu-value">${prefs.fontSize}px</span>
        <button type="button" class="epub-menu-btn" data-epub-step="fontSize:1">＋</button>
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.lineHeight'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.lineHeight'))}</span>
        <button type="button" class="epub-menu-btn" data-epub-step="lineHeight:-1">−</button>
        <span class="epub-menu-value">${prefs.lineHeight}</span>
        <button type="button" class="epub-menu-btn" data-epub-step="lineHeight:1">＋</button>
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.paraSpace'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.paraSpace'))}</span>
        ${Object.keys(PARA_SPACE_STEPS).map((k) =>
          `<button type="button" class="epub-menu-btn${prefs.paraSpace === k ? ' on' : ''}" data-epub-para="${k}">${escapeHtml(t(paraKeys[k]))}</button>`
        ).join('')}
      </div>
      <div class="epub-menu-row" role="group" aria-label="${escapeHtml(t('epub.aa.fontFamily'))}">
        <span class="epub-menu-label">${escapeHtml(t('epub.aa.fontFamily'))}</span>
        <button type="button" class="epub-menu-btn${prefs.fontFamily === 'serif' ? ' on' : ''}" data-epub-font="serif">${escapeHtml(t('epub.aa.serif'))}</button>
        <button type="button" class="epub-menu-btn${prefs.fontFamily === 'sans' ? ' on' : ''}" data-epub-font="sans">${escapeHtml(t('epub.aa.sans'))}</button>
      </div>
      <button type="button" class="epub-menu-btn epub-menu-close" data-epub-close>${escapeHtml(t('epub.menu.close'))}</button>`;
    menu.querySelectorAll('[data-epub-theme-pick]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ theme: btn.dataset.epubThemePick }));
    });
    menu.querySelectorAll('[data-epub-step]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const [key, dirRaw] = btn.dataset.epubStep.split(':');
        const dir = Number(dirRaw);
        if (key === 'fontSize') setPrefs({ fontSize: cycle(FONT_SIZE_STEPS, prefs.fontSize, dir) });
        else setPrefs({ lineHeight: cycle(LINE_HEIGHT_STEPS, prefs.lineHeight, dir) });
      });
    });
    menu.querySelectorAll('[data-epub-para]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ paraSpace: btn.dataset.epubPara }));
    });
    menu.querySelectorAll('[data-epub-font]').forEach((btn) => {
      btn.addEventListener('click', () => setPrefs({ fontFamily: btn.dataset.epubFont }));
    });
    menu.querySelector('[data-epub-close]').addEventListener('click', closeMenu);
  }

  function openMenu() {
    renderMenu();
    menu.hidden = false;
    menu.dataset.epubMenu = 'open';
  }
  function closeMenu() {
    menu.hidden = true;
    menu.dataset.epubMenu = 'closed';
  }

  function getTotalPages() {
    return Math.max(1, Math.ceil(viewport.scrollHeight / viewport.clientHeight));
  }

  function getCurrentPage() {
    return Math.round(viewport.scrollTop / viewport.clientHeight);
  }

  function emitTracking() {
    const total = getTotalPages();
    const page = getCurrentPage();
    pane.dispatchEvent(new CustomEvent('epub-page-change', {
      detail: {
        bookTitle,
        chapter: chapterIdx,
        chapterTitle: chapters[chapterIdx].title,
        page,
        totalPages: total,
        totalChapters: chapters.length,
        progress: chapterIdx / chapters.length + (page + 1) / total / chapters.length,
      }
    }));
  }

  function updateUI() {
    const page = getCurrentPage() + 1;
    const total = getTotalPages();
    chapterTitleEl.textContent = chapters[chapterIdx].title;
    infoEl.textContent = `Ch ${chapterIdx + 1}/${chapters.length} · Pg ${page}/${total}`;
    prevBtn.disabled = chapterIdx === 0 && viewport.scrollTop <= 0;
    nextBtn.disabled = chapterIdx === chapters.length - 1 && viewport.scrollTop >= viewport.scrollHeight - viewport.clientHeight - 1;
    emitTracking();
  }

  function loadChapter(idx, scrollPos) {
    chapterIdx = idx;
    const chapter = chapters[idx];
    content.replaceChildren(...chapter.doc.body.childNodes);
    // 原书内 <style> 一律摘除（防注入进应用页后全局生效），scoped 版单点注入
    content.querySelectorAll('style').forEach((s) => s.remove());
    if (chapter.scopedCss) {
      const styleEl = document.createElement('style');
      styleEl.className = 'epub-book-style';
      styleEl.textContent = chapter.scopedCss;
      content.appendChild(styleEl);
    }
    requestAnimationFrame(() => {
      viewport.scrollTop = scrollPos || 0;
      updateUI();
      // 缺失资源一次性 toast（可见降级纪律：不阻塞渲染，N = 该章 miss 计数）
      if (chapter.missCount > 0 && window.__showToast) {
        window.__showToast(t('epub.resMissing', { count: chapter.missCount }), 'info');
      }
    });
  }

  function nextPage() {
    const bottom = viewport.scrollHeight - viewport.clientHeight;
    if (viewport.scrollTop < bottom - 1) {
      viewport.scrollTo({ top: viewport.scrollTop + viewport.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx < chapters.length - 1) {
      loadChapter(chapterIdx + 1, 0);
    }
  }

  function prevPage() {
    if (viewport.scrollTop > 1) {
      viewport.scrollTo({ top: viewport.scrollTop - viewport.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx > 0) {
      loadChapter(chapterIdx - 1, 999999); // bottom of previous chapter
    }
  }

  prevBtn.addEventListener('click', (e) => { e.stopPropagation(); prevPage(); });
  nextBtn.addEventListener('click', (e) => { e.stopPropagation(); nextPage(); });

  // 点按 1/3-1/3-1/3 分区（A4）：菜单开时正文点按仅收菜单不翻页（Apple Books 口径）
  viewport.addEventListener('click', (e) => {
    // 章节内锚点不触发应用页导航（单页应用守卫；锚跳转属 P1 目录面）
    const anchor = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (anchor) e.preventDefault();
    if (menuOpen()) { closeMenu(); return; }
    const sel = window.getSelection();
    if (sel && sel.toString().trim().length > 0) return;
    const rect = viewport.getBoundingClientRect();
    if (e.clientX < rect.left + rect.width / 3) prevPage();
    else if (e.clientX > rect.right - rect.width / 3) nextPage();
    else openMenu();
  });

  // Keyboard navigation
  pane.tabIndex = 0;
  pane.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); prevPage(); }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); nextPage(); }
  });

  // Update indicator on scroll
  let scrollTimer = null;
  viewport.addEventListener('scroll', () => {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => updateUI(), 100);
  });

  // Recalculate on resize
  new ResizeObserver(() => updateUI()).observe(viewport);

  // Initial load
  applyPrefs();
  loadChapter(0, 0);
}

export default {
  name: 'epub',
  label: 'EPUB',
  extensions: ['.epub'],
  binary: true,
  priority: 0,
  render: viewEpub,
};
