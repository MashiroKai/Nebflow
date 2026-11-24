// viewers/markdown.js — Markdown viewer (rendered preview + source toggle).
// Migrated verbatim from fileViewers.js (viewMarkdown).
//
// wiki-graph batch (2026-10-14): Obsidian-compatible document face —
//   · `[[wikilinks]]` render as clickable cross-document references
//     (resolution through the /api/wiki/graph index; unresolved targets stay
//     visible and explain themselves on click).
//   · Reference jumps carry an anchor (block `^id` / heading / `L<line>`) —
//     the viewer scrolls the target paragraph to the top and flash-highlights
//     it (paper-style "first mention must explain or reference" reading flow).
//   · Floating glass toggles: in-file find (same family as the source toggle)
//     and "open in wiki graph".

import { addSourceToggle, addFindToggle, routeLocalHref, openLocalFileTab, openExternalUrlTab } from './shared.js';
import { showDocumentTermCard } from '../documentTermCard.js';
//（readerChrome 挂载已随 Canvas 改造批⑦退役：2026-10-31 作者令「阅读器视角
//  仅电子书格式生效（epub/mobi/azw3 等）」——md 退出统一阅读 chrome，正文
//  滚回 .canvas-md-scroll 原生滚动面；阅读位记账（pane._mdScrollPos +
//  canvas-tab-activated 恢复）是 pane 级机制、不依赖 chrome，逐字保留。）
import { mintTickets, stripCredentialParams, decodePathParam, ticketRejects, reMint } from '../nfTicket.js';
// 二次加载清剿批（2026-10-24 · #816 同法）：文档图片字节改经 **nfFileCache 账本
// 单点**——`nfViewerUrl` 命中零抓取 / 未命中取数即入账；blob 解码失败自愈逐出
// （`nfCacheDrop`，见下方 __nfMdImgFail）。
import { nfViewerUrl, nfCacheDrop } from '../nfFileCache.js';
import { noteAuthRejected } from '../sessionGate.js';
import { t } from '../i18n.js';
import { isMdHtmlFile } from '../mdHtmlGate.js';
// mermaid-md batch (2026-10-23): explicit hydration at the primary document
// face (this viewer + wiki doc opens). The global observer in main.js covers
// every other markdown face; this call is idempotent (state-attribute guard).
import { hydrateMermaidIn } from '../mermaidRenderer.js';

/** Scroll one heading to the TOP of the markdown content scroller — and of nothing
 *  else.
 *
 *  Why not `Element.scrollIntoView({ block: 'start' })` (what this path used until
 *  2026-09-20): the CSSOM View algorithm is NOT scoped to the nearest scroll
 *  container. It walks the target's entire ancestor chain and aligns the target
 *  against EVERY scrollable box on the way, each one clamped to its own scroll
 *  range — and `overflow: hidden` boxes are scroll containers too (they are just
 *  not user-scrollable). So one TOC click also wrote whatever residual scroll each
 *  ancestor happened to carry, including the root scroller: pointer-up on a
 *  directory item moved the whole client (author report 2026-09-20 22:56, Windows:
 *  「不仅是内容跳转了，nebflow 客户端整体也上移了一点」). Measured in the harness
 *  (tests/mdscroll-toc-scroll.spec.mjs): with a residual on the root scroller the
 *  click moved `documentElement.scrollTop` 0 → +N and `window.scrollY` 0 → +N; with a
 *  residual inside the canvas panel `#canvas-panel.scrollTop` moved with it. The
 *  intended effect is one container's own scrollTop, so it is written directly and
 *  nothing else may move — same discipline as messages.js `revealQuoteTarget`
 *  (「🔴 不使用 scrollIntoView：它会把滚动写进任一 overflow:hidden 祖先」).
 *
 *  `block: 'start'` semantics are preserved exactly: the heading's top edge lands on
 *  the scrollport's top edge, clamped to [0, max]. A heading near the document end
 *  therefore cannot be aligned — that clamp is the browser's own behaviour, not a
 *  precision regression (harness judges the landing against the same clamped
 *  expectation).
 *
 *  @param {HTMLElement} mdViewer `.canvas-md-viewer` — the scroller is its parent
 *    (this viewer builds `<div class="canvas-md-scroll"><div class="canvas-md-viewer">`)
 *  @param {HTMLElement} target the heading to bring to the top */
function scrollHeadingToTop(mdViewer, target) {
  const scroller = /** @type {HTMLElement|null} */ (mdViewer.closest('.canvas-md-scroll'));
  // No scroller in the chain (an unexpected host mounted this viewer) ⇒ nothing to
  // scroll. Deliberately NOT a scrollIntoView fallback: that is the leak itself.
  if (!scroller) return;
  const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  const delta = target.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
  scroller.scrollTo({
    top: Math.max(0, Math.min(max, scroller.scrollTop + delta)),
    behavior: 'smooth',
  });
}

// ── Source analysis (block map + ^block-id extraction) ────────────────
// The rendered graph needs two maps back into the SOURCE the user's links
// point at: which source line each rendered top-level block came from (line
// anchors `L<n>`), and where each `^block-id` lives. Block-id markers are
// stripped from the rendered string — Obsidian hides them in preview too.

const FENCE_RE = /^\s*(```+|~~~+)/;
const BLOCK_ID_LINE_RE = /^(\s*(?:#{1,6}\s+)?[^\n]*\S)[ \t]+\^([A-Za-z0-9][A-Za-z0-9-]*)[ \t]*$/;

/**
 * @param {string} content raw markdown
 * @returns {{ html: string, blockStarts: number[], blockIds: Map<string, number> }}
 */
function analyzeSource(content) {
  let lines = content.split('\n');
  // Frontmatter is metadata, not content — blank its lines (count preserved,
  // so block-id and block-start line numbers stay in FULL-FILE coordinates
  // and every anchor agrees with the authoring view).
  if (lines[0] && lines[0].trim() === '---') {
    for (let i = 1; i < lines.length; i++) {
      if (lines[i].trim() === '---') { lines = lines.map((l, j) => (j <= i ? '' : l)); break; }
    }
  }
  const blockIds = new Map();
  const stripped = lines.map((line, i) => {
    const m = BLOCK_ID_LINE_RE.exec(line);
    if (m) {
      if (!blockIds.has(m[2])) blockIds.set(m[2], i + 1);
      return m[1];
    }
    return line;
  });
  // Fence-aware block starts: a line begins a new top-level block when the
  // previous line is blank, or when it opens a heading / a first quote line.
  const blockStarts = [];
  let inFence = false;
  let fenceMarker = '';
  for (let i = 0; i < stripped.length; i++) {
    const line = stripped[i];
    const fenceMatch = FENCE_RE.exec(line);
    if (fenceMatch) {
      if (!inFence) {
        inFence = true;
        fenceMarker = fenceMatch[1].slice(0, 3);
        blockStarts.push(i + 1);
      } else if (fenceMatch[1].startsWith(fenceMarker)) {
        inFence = false;
        fenceMarker = '';
      }
      continue;
    }
    if (inFence) continue;
    const prev = i > 0 ? stripped[i - 1] : '';
    const prevBlank = prev.trim() === '';
    const prevQuote = prev.startsWith('>');
    const isHeading = line.startsWith('#');
    const isQuoteStart = line.startsWith('>') && !prevQuote;
    if (i === 0 || prevBlank || isHeading || isQuoteStart) blockStarts.push(i + 1);
  }
  if (blockStarts.length === 0) blockStarts.push(1);
  return { html: stripped.join('\n'), blockStarts, blockIds };
}

/** Tag each rendered top-level element with its source line (data-src-line).
 *  When the element count matches the block count the mapping is exact;
 *  otherwise it degrades proportionally — close enough to flash the right
 *  neighborhood, never silently absent. */
function assignSourceLines(mdViewer, blockStarts) {
  const kids = /** @type {HTMLElement[]} */ (Array.from(mdViewer.children));
  if (!kids.length || !blockStarts.length) return;
  kids.forEach((el, i) => {
    const idx = kids.length === blockStarts.length
      ? i
      : Math.min(blockStarts.length - 1, Math.floor((i / kids.length) * blockStarts.length));
    el.dataset.srcLine = String(blockStarts[idx]);
  });
}

/**
 * Apply a reference-jump anchor to the rendered document: locate the target
 * block, scroll it to the top of the scroller and flash-highlight it.
 * Anchor forms: {kind:'block', id} · {kind:'heading', text} · {kind:'line', line}
 * (the same payload `openLocalFileTab` dispatches; PDF pageStart is consumed
 * elsewhere).
 * @param {HTMLElement} mdViewer
 * @param {any} anchor
 * @param {Map<string, number>} [blockIds]
 */
function applyJumpAnchor(mdViewer, anchor, blockIds) {
  if (!anchor || !mdViewer) return;
  let target = null;
  if (anchor.kind === 'block' && anchor.id && blockIds && blockIds.has(anchor.id)) {
    const line = blockIds.get(anchor.id);
    target = blockAtOrBefore(mdViewer, line);
  } else if (anchor.kind === 'heading' && anchor.text) {
    // Same slug discipline the TOC click handler uses (marked v12 encodes
    // CJK hrefs; ids here are raw).
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s\u4e00-\u9fff-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    const want = slugify(anchor.text);
    mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((h) => {
      if (h.id === want) target = h;
    });
    if (!target) {
      // Fall back to a text match over the headings.
      mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((h) => {
        if (!target && (h.textContent || '').trim() === anchor.text.trim()) target = h;
      });
    }
  } else if (anchor.kind === 'line' && Number.isFinite(anchor.line)) {
    target = blockAtOrBefore(mdViewer, anchor.line);
  }
  if (!target) return;
  scrollHeadingToTop(mdViewer, target);
  // 落点时间戳：阅读位恢复（canvas-tab-activated 腿）在跳后 2s 窗口内让位——
  // 落点滚动是新真源，不被旧阅读位拽回。
  const jumpPane = /** @type {any} */ (mdViewer.closest('.canvas-tab-pane'));
  if (jumpPane) jumpPane._mdJumpAt = Date.now();
  target.classList.remove('nf-jump-flash');
  // Force a reflow so consecutive jumps restart the animation.
  void target.offsetWidth;
  target.classList.add('nf-jump-flash');
  target.addEventListener('animationend', () => target.classList.remove('nf-jump-flash'), { once: true });
}

/** The rendered block whose data-src-line is the greatest one ≤ `line`. */
function blockAtOrBefore(mdViewer, line) {
  let best = null, bestLine = -1;
  mdViewer.querySelectorAll('[data-src-line]').forEach((el) => {
    const l = parseInt(el.dataset.srcLine || '0', 10);
    if (l <= line && l > bestLine) { best = el; bestLine = l; }
  });
  return best || mdViewer.querySelector('[data-src-line]');
}

// ── Wikilink graph index (module-level, TTL-cached) ───────────────────

/** @type {{at:number, data:any}|null} */
let wikiIndexCache = null;
const WIKI_INDEX_TTL = 30_000;

/** 在途请求合并（CPU 修复批 2026-11-21）：文档页首屏并发渲染多个 wikilink 块时，
 *  缓存尚未落地，每个调用都会各发一次 ~7MB 的全量图谱（实测一拍 6 次突发 ≈ 42MB）。
 *  同一时刻只允许一条在途请求，后来的调用共享其 promise；失败同样广播给全部等待者。 */
/** @type {Promise<any>|null} */
let wikiGraphInflight = null;

function wikiGraph() {
  if (wikiIndexCache && Date.now() - wikiIndexCache.at < WIKI_INDEX_TTL) return Promise.resolve(wikiIndexCache.data);
  if (!wikiGraphInflight) {
    wikiGraphInflight = fetchWikiGraph().finally(() => { wikiGraphInflight = null; });
  }
  return wikiGraphInflight;
}

async function fetchWikiGraph() {
  const { authHeaders } = await import('../flowHelpers.js');
  const res = await fetch('/api/wiki/graph', { headers: { ...authHeaders() } });
  if (!res.ok) {
    // #762 图谱 403 族：本函数有两条腿，纪律按 authgate 批（2026-10-22）分流——
    //   · 渲染腿（renderWikilinks，文档打开即跑）= 背景面：静默降级（链接留待
    //     点击时重解析），绝不拉重登（nfTicket authgate 批「渲染即重登」禁令同源）；
    //   · 点击腿（onWikilinkClick，用户主动点链接）= 用户面：401/403 交
    //     sessionGate 重认证伞（下方调用点）。err.status 供调用点分流。
    const err = new Error('wiki graph unavailable: ' + res.status);
    /** @type {any} */ (err).status = res.status;
    throw err;
  }
  const data = await res.json();
  wikiIndexCache = { at: Date.now(), data };
  return data;
}

/**
 * Resolve an Obsidian-style link target against the graph index.
 * Returns { absPath } for a local hit, { remote } for a peer doc, or null.
 * 词条引用批 batch 2 (F1): after BOTH document legs fail, a target with no
 * explicit anchor falls through to the TERM REGISTRY third leg — the graph
 * payload already carries `nodes[].terms[]` (batch-1 projection), so this is
 * a pure client-side lookup with zero extra requests. The hit reuses the
 * resolved shape and adds `anchor:{kind:'block', id}` (the definition's block
 * anchor — the deep-link landing) plus `term:{name,definition,rel,anchor,
 * source}` (the hover-bubble payload). Ambiguity never guesses, mirroring the
 * engine's selectTerm: a UNIQUE glossary hit wins, else a unique hit anywhere
 * in the registry scope, else null (the link stays unresolved).
 * @param {string} target raw target (no anchor/alias)
 * @param {string} baseDir the current document's directory ('' when unknown)
 * @param {any} data graph JSON
 * @param {{project?: string|null, allowTerm?: boolean}} [opts] project scopes
 *   the term registry (engine: per-vault); allowTerm=false skips the third leg
 *   (targets the author anchored explicitly are doc+anchor intent, never term
 *   citations — [[GCR#某节]] must not re-route).
 */
function resolveWikiTarget(target, baseDir, data, opts) {
  const norm = (s) => String(s || '').replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
  const clean = target.replace(/\\/g, '/').replace(/^\.\/+/, '');
  const withMd = clean.toLowerCase().endsWith('.md') ? clean : clean + '.md';
  const base = norm(baseDir);
  const nodes = (data && data.nodes) || [];
  const projects = (data && data.projects) || [];
  const sameVault = (n) => {
    const p = projects.find((pj) => pj.name === n.project);
    if (!p) return null;
    const vault = norm(p.vault);
    const nodeDir = norm(vault + '/' + n.rel.split('/').slice(0, -1).join('/'));
    return nodeDir === base ? p : null;
  };
  // 1. Vault-relative exact path, preferring THIS document's vault.
  let hit = nodes.find((n) => !n.stub && n.rel.toLowerCase() === withMd && sameVault(n));
  // 2. Unique basename across all vaults (Obsidian's shortest-name rule).
  if (!hit) {
    const byBase = nodes.filter((n) => !n.stub && n.rel.toLowerCase().split('/').pop() === withMd.split('/').pop());
    if (byBase.length === 1) hit = byBase[0];
    else if (byBase.length > 1) {
      // Prefer a doc in the same vault, then self-device docs.
      hit = byBase.find((n) => sameVault(n)) || byBase.find((n) => n.device === 'self') || null;
    }
  }
  if (!hit) {
    // 3. Term registry (词条引用批 batch 2, F1) — documents first, terms fill
    // only the gap (裁定 §9-4: today's doc hits stay doc hits tomorrow).
    if (!opts || opts.allowTerm !== false) {
      const termHit = resolveTermHit(target, data, opts ? opts.project : null);
      if (termHit) return termHit;
    }
    return null;
  }
  const proj = projects.find((pj) => pj.name === hit.project);
  if (!proj) return null;
  if (hit.device === 'self') {
    const sep = proj.vault.includes('\\') ? '\\' : '/';
    return { absPath: proj.vault.replace(/[\\/]+$/, '') + sep + hit.rel.split('/').join(sep) };
  }
  return { remote: { device: hit.device, vault: proj.vault, rel: hit.rel, project: hit.project } };
}

/** Term-registry lookup over the merged graph payload (F1 third leg). Key
 *  normalization mirrors the engine's termTargetKey (trailing doc extension
 *  stripped, case-insensitive); the registry scope is the linking document's
 *  project (the engine derives the registry per vault — the merged view needs
 *  the project filter to reproduce that scope). */
function resolveTermHit(target, data, currentProject) {
  if (!data || !Array.isArray(data.nodes)) return null;
  const key = String(target || '').trim().replace(/\\/g, '/')
    .replace(/\.markdown$/i, '').replace(/\.md$/i, '').toLowerCase();
  if (!key) return null;
  return resolveTermKey(key, data, currentProject);
}

// 词条键索引（B5-1）：`key → [{node, term}]` 只建一次，按 data 对象弱引用缓存。
// 正文裸词识别要为每个落点做一次消解，逐次全量扫描 nodes×terms 在万级词条库
// 上就是 O(链接数 × 词条数)；索引把这一步压成一次建表 + O(1) 查表。
/** @type {WeakMap<object, Map<string, any[]>>} */
const termKeyIndexCache = new WeakMap();

function termKeyIndex(data) {
  let idx = termKeyIndexCache.get(data);
  if (idx) return idx;
  /** @type {Map<string, any[]>} */
  idx = new Map();
  for (const n of data.nodes) {
    if (!n || n.stub || !Array.isArray(n.terms)) continue;
    for (const term of n.terms) {
      const keys = [term.name].concat(term.aliases || [])
        .map((k) => String(k || '').trim().toLowerCase())
        .filter(Boolean);
      for (const k of new Set(keys)) {
        if (!idx.has(k)) idx.set(k, []);
        idx.get(k).push({ node: n, term });
      }
    }
  }
  termKeyIndexCache.set(data, idx);
  return idx;
}

/**
 * 一个已归一化的词条键 → 消解结果（`[[词条]]` 与正文裸词两条路共用同一套
 * 消解裁定：唯一 glossary 命中优先于 doc-intro；同级多命中永不猜）。
 * @param {string} key 小写键
 * @param {any} data graph 载荷
 * @param {string|null} currentProject 作用域工程
 */
function resolveTermKey(key, data, currentProject) {
  const candidates = termKeyIndex(data).get(key) || [];
  if (!candidates.length) return null;
  const scoped = currentProject
    ? candidates.filter((c) => c.node.project === currentProject)
    : candidates;
  const pool = scoped.length ? scoped : candidates;
  // selectTerm mirror (第一批裁定 5): unique glossary hit wins over doc-intro
  // hits; multiple same-tier hits never guess.
  const glossaryHits = pool.filter((c) => c.term.source === 'glossary');
  const pick = glossaryHits.length === 1 ? glossaryHits[0]
    : glossaryHits.length > 1 ? null
    : pool.length === 1 ? pool[0] : null;
  if (!pick) return null;
  const project = (data.projects || []).find((p) => p.name === pick.node.project);
  const resolved = resolvedForNode(pick.node, project);
  if (!resolved) return null;
  const anchorId = String(pick.term.anchor || '').replace(/^#\^/, '');
  if (!anchorId) return null;
  resolved.anchor = { kind: 'block', id: anchorId };
  resolved.term = {
    name: pick.term.name,
    definition: pick.term.definition || '',
    rel: pick.term.rel || pick.node.rel,
    anchor: pick.term.anchor,
    source: pick.term.source || '',
    doc: pick.node.title || '',   // 所在文档标题（词条索引块；空则回落 term.rel 基名）
  };
  return resolved;
}

// ── Term hover bubble ─────────────────────────────────────────────────────
// 词条 hover 气泡机制本体已抽为共享件 ../termTip.js（human-face 批
// 2026-10-30）：docs viewer 与 chat 等无图谱面共用同一份气泡/兜底腿实现。
// 本文件只消费其导出面；深链导航回调在模块装载时注入（onWikilinkClick）。
import { attachTermFace, termTapExpand, hideTermTip, setTermTipNav } from '../termTip.js';
// onWikilinkClick 是函数声明（提升），模块装载时即可注入气泡的深链导航回调。
setTermTipNav(onWikilinkClick);
// 正文裸词识别（B5-1）：匹配规则是纯函数（零依赖、可离线断言），DOM 面的排除
// 判据仍在本文件的 TreeWalker 里 —— 下面 renderAutoTerms 与 renderWikilinks 用
// 同一套 acceptNode 纪律（CODE/PRE/A/.katex 一律不进）。
import { termsForScope, buildTermMatcher } from '../termLexicon.js';

/**
 * 正文裸词识别（B5-1，2026-11-01 文档增强腿）。
 *
 * 与 `[[词条]]` 的关系：显式引用原本就通篇可点（五件已有设施），缺的是**作者
 * 没写括号的裸词**。本 pass 把已注册词条在正文里的**首次出现**变成 `.nf-term`
 * 链接，消解走同一套裁定（`resolveTermKey`），气泡/深链/触屏首击全部复用
 * `attachTermFace` 与既有点击委派 —— 零新机制、零新请求。
 *
 * 排除面（缺一条就会静默出错，见 verify-md-term.mjs 的「0 命中」四条）：
 *   · 代码围栏 / 行内代码（CODE、PRE 子树）—— 把代码里的符号标成链接是最坏坏法；
 *   · 既有链接（A 子树）—— 输出 `<a>` 套 `<a>` 是非法 HTML；
 *   · KaTeX 公式（`.katex` 子树，含其 TeX 源 `<annotation>`）；
 *   · 标题（h1–h6）—— 标题是结构面，词条首现留在正文段落；
 *   · 词条自己的声明文档 —— glossary 里 `- GCR — …` 不加自指链接。
 *
 * @param {HTMLElement} mdViewer
 * @param {any} data graph 载荷（`nodes[].terms[]` = 正文块锚段落派生）
 * @param {string|null} projectName 作用域工程
 * @param {string} [absPath] 当前文档路径（用于剔除「自己的声明文档」）
 * @returns {number} 实际生成的链接数（断言面用）
 */
function renderAutoTerms(mdViewer, data, projectName, absPath) {
  if (!data || !mdViewer) return 0;
  const selfKey = normVaultPath(absPath);
  const terms = termsForScope(data, projectName).filter((term) => {
    // 自己的声明文档不链自己：把 term.rel 还原成绝对路径比较（工程 vault + rel）。
    if (!selfKey || !term.rel) return true;
    const proj = (data.projects || []).find((p) => p.name === term.project);
    if (!proj || !proj.vault) return true;
    return normVaultPath(String(proj.vault).replace(/[\\/]+$/, '') + '/' + term.rel) !== selfKey;
  });
  if (!terms.length) return 0;
  const matcher = buildTermMatcher(terms);
  if (!matcher.size) return 0;

  const walker = document.createTreeWalker(mdViewer, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      const tag = p.tagName;
      if (tag === 'CODE' || tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'A') return NodeFilter.FILTER_REJECT;
      if (/^H[1-6]$/.test(tag)) return NodeFilter.FILTER_REJECT;
      if (p.closest('pre, code, a, .katex, .nf-term')) return NodeFilter.FILTER_REJECT;
      return node.nodeValue && node.nodeValue.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    },
  });
  const textNodes = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode);

  let made = 0;
  for (const node of textNodes) {
    const value = node.nodeValue || '';
    const hits = matcher.find(value);
    if (!hits.length) continue;
    const frag = document.createDocumentFragment();
    let last = 0;
    for (const hit of hits) {
      if (hit.start > last) frag.appendChild(document.createTextNode(value.slice(last, hit.start)));
      const a = document.createElement('a');
      a.className = 'nf-term';
      a.href = '#';
      a.textContent = hit.text;              // 保留原文大小写
      a.dataset.term = hit.term.name;        // 消解在下面统一做（一次建表，O(1)/命中）
      frag.appendChild(a);
      made++;
      last = hit.end;
    }
    if (last < value.length) frag.appendChild(document.createTextNode(value.slice(last)));
    node.parentNode?.replaceChild(frag, node);
  }

  // 消解 + 挂面：命中才留链接（歧义/越界 = 退回裸文本，绝不猜）。
  mdViewer.querySelectorAll('a.nf-term').forEach((el) => {
    const a = /** @type {HTMLAnchorElement} */ (el);
    const key = String(a.dataset.term || '').trim().toLowerCase();
    const hit = key ? resolveTermKey(key, data, projectName || null) : null;
    if (!hit) {
      a.replaceWith(document.createTextNode(a.textContent || key));
      made--;
      return;
    }
    a.dataset.wikiResolved = JSON.stringify(hit);
    if (projectName) a.dataset.wikiProject = projectName;
    a.dataset.wikiTarget = String(a.dataset.term || '');   // 非空 ⇒ 点击腿走 resolved.absPath 深链（不是本文自锚）
    if (hit.anchor && hit.anchor.kind === 'block' && hit.anchor.id) a.dataset.wikiAnchor = '^' + hit.anchor.id;
    if (hit.term) attachTermFace(a, mdViewer, { term: hit.term });
  });
  return made;
}

/**
 * 任意 DOM 宿主的词条化落面（Ask 回答词条化批 2026-11-23；设计正本 §5 G4/G5
 * 「在 createAskDialog 增加 termLinks 钩子，复用 termLexicon.js 同源」）。
 *
 * 与正文文档**同一个 pass**（`renderAutoTerms`：同一匹配规则、同一消解裁定、同一
 * termTip 气泡/深链腿、同一排除面 CODE/PRE/A/KaTeX/标题），差别只有宿主与作用域：
 *   · 宿主 = 调用方给的节点（Ask 回答气泡），不是 mdViewer；
 *   · 作用域没有「自己的声明文档」（问答回答不属于任何 vault 文档）⇒ 自指剔除腿
 *     传空路径，永不误剔。
 * 唯一新增面 = 把图谱取用与自指路径从内部函数提升为**可复用导出**，零第二套实现。
 *
 * @param {HTMLElement} host 落面宿主
 * @param {string|null} [projectName] 作用域工程（缺省 = 合并视图，歧义不猜）
 * @returns {Promise<number>} 生成的词条链接数（0 = 无词条/无图谱/出错）
 */
export async function termLinkify(host, projectName) {
  if (!host || typeof host.querySelectorAll !== 'function') return 0;
  let data = null;
  try { data = await wikiGraph(); } catch { data = null; }
  if (!data) return 0;
  try { return renderAutoTerms(host, data, projectName || null, ''); } catch { return 0; }
}

/** The canvas tab id hosting this viewer — the single-layer source a reference
 * jump records (F5 返回原文档；canvas.js openWorkspaceItem 消费). */
function refSourceTabId(mdViewer) {
  const pane = mdViewer && mdViewer.closest ? mdViewer.closest('.canvas-tab-pane') : null;
  return (pane && pane.dataset && pane.dataset.tabId) || undefined;
}

/**
 * Post-render pass: turn `[[wikilink]]` text occurrences into reference
 * anchors. Runs over text nodes so fenced code and inline code stay literal.
 * @param {HTMLElement} mdViewer
 * @param {string} baseDir current document directory
 * @param {string} [absPath] current document path (scopes the term registry)
 */
async function renderWikilinks(mdViewer, baseDir, absPath) {
  let data = null;
  try { data = await wikiGraph(); } catch (e) { data = null; }
  // `![[embed]]` rides the same regex (Obsidian embed syntax): image embeds
  // keep the literal for now, everything else (literature PDFs, figures) is
  // turned into the reference link — one click opens it in canvas.
  const WIKI_TEXT_RE = /(!?)\[\[([^\[\]]+?)\]\]/g;
  const walker = document.createTreeWalker(mdViewer, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      const tag = p.tagName;
      if (tag === 'CODE' || tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'A') return NodeFilter.FILTER_REJECT;
      if (p.closest('pre, code, a.nf-wikilink')) return NodeFilter.FILTER_REJECT;
      return node.nodeValue && /\!?\[\[/.test(node.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    },
  });
  const textNodes = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode);
  for (const node of textNodes) {
    const value = node.nodeValue || '';
    WIKI_TEXT_RE.lastIndex = 0;
    if (!WIKI_TEXT_RE.test(value)) continue;
    WIKI_TEXT_RE.lastIndex = 0;
    const frag = document.createDocumentFragment();
    let last = 0;
    let m;
    while ((m = WIKI_TEXT_RE.exec(value)) !== null) {
      if (m.index > last) frag.appendChild(document.createTextNode(value.slice(last, m.index)));
      const embed = m[1] === '!';
      const inner = m[2];
      const pipe = inner.indexOf('|');
      const body = pipe >= 0 ? inner.slice(0, pipe) : inner;
      const hash = body.indexOf('#');
      const target = hash >= 0 ? body.slice(0, hash).trim() : body.trim();
      const anchorRaw = hash >= 0 ? body.slice(hash + 1).trim() : '';
      const alias = pipe >= 0 ? inner.slice(pipe + 1).trim() : '';
      const a = document.createElement('a');
      a.className = 'nf-wikilink';
      a.href = '#';
      a.textContent = alias || target || anchorRaw;
      a.dataset.wikiTarget = target;      // '' = self-reference ([[#Heading]])
      a.dataset.wikiAnchor = anchorRaw;   // '^id' or 'Heading'
      frag.appendChild(a);
      last = m.index + m[0].length;
    }
    if (last < value.length) frag.appendChild(document.createTextNode(value.slice(last)));
    node.parentNode?.replaceChild(frag, node);
  }
  // Resolution pass (best-effort, after all links exist).
  // 词条引用批 batch 2: the term-registry scope follows the linking document's
  // project (engine derives the registry per vault — graphNodeFor maps this
  // document into its project; outside every vault = merged view scope).
  const currentProject = (absPath && data)
    ? ((graphNodeFor(absPath, data) || {}).project || {}).name || null
    : null;
  mdViewer.querySelectorAll('a.nf-wikilink').forEach((el) => {
    const a = /** @type {HTMLAnchorElement} */ (el);
    if (currentProject) a.dataset.wikiProject = currentProject;
    const target = a.dataset.wikiTarget || '';
    if (!target) { a.classList.add('nf-wikilink-self'); return; }
    const anchorRaw = a.dataset.wikiAnchor || '';
    const hit = data
      ? resolveWikiTarget(target, baseDir, data, { project: currentProject, allowTerm: !anchorRaw })
      : null;
    if (hit) {
      a.classList.add('nf-wikilink-resolved');
      a.dataset.wikiResolved = JSON.stringify(hit);
      // 词条命中：挂 hover 气泡 + 点击深链（F3；数据在 dataset 里零请求）。
      if (hit.term) attachTermFace(a, mdViewer, { term: hit.term });
    } else {
      a.classList.add('nf-wikilink-unresolved');
      a.title = t('wiki.unresolved');
      // 兜底腿（F3②）：无锚短形引用在载荷里查不到时，hover 打 term API
      // 出泡（词条文档未重扫的 30s 窗口里引用仍然可读）。带锚目标 =
      // 文档+锚意图，永不进词条面。
      if (!anchorRaw) attachTermFace(a, mdViewer, { fallbackName: target });
    }
  });
  // 正文裸词识别（B5-1）：显式 `[[词条]]` 全部就位后再跑（walker 已拒 A 子树，
  // 这里是顺序兜底：绝不把刚生成的 wikilink 文本再链一次）。无词条 ⇒ 零开销、
  // 输出与改造前逐字节相同。
  if (data) renderAutoTerms(mdViewer, data, currentProject, absPath);
  // 词条索引块（term-system-v2 词条视觉面批 2026-11-05）：渲染管线尾部自动生成，
  // 必须在两条词条腿（显式引用 + 正文裸词）都挂完面之后才扫得出引用集合。
  renderTermIndex(mdViewer);
}

// ── 词条索引（term-system-v2 词条视觉面批 2026-11-05）────────────────────
// 作者令原话：「引用了词条之后，引擎自动在下方建立索引，就是引用的词条所在的
// 文档。也可以点击跳转。」
//
// 形态：正文尾部一枚 `<details class="nf-term-index">`，默认收起，summary =
// 「词条索引（N）」，逐条 = 本文引用的词条 + 它所在的文档；条目点击复用既有
// wikilink 委派（`data-wiki-resolved` 直填消解产物 ⇒ openLocalFileTab / 远端
// 文档腿全部免费复用），零新导航机制。
//
// 与行内词条的分工：行内只显示词条名（作者令「不需要显示文档名」，见
// split.css 词条族注），文档名整体下沉到本块 —— 于是「这条引用出自哪篇文档」
// 仍然可读，但不再打断正文行。
//
// 数据面纪律：零请求、零新端点 —— 只消费消解 pass 已写进每个词条链接的
// `dataset.wikiResolved`（图谱载荷 30s TTL 共享缓存）。兜底腿（载荷未收录、
// 靠 term API 出泡的短形）**不进索引**：索引是背景面，不许为它触发网络。
// 幂等：重挂前先摘旧块（重复渲染不叠块）；0 条引用整块不渲染（repo reports/
// 等图外文档零噪音，与 .nf-backlinks 同纪律）。
//
// @param {HTMLElement} mdViewer
// @returns {number} 写进索引的词条条数（0 = 未渲染）
//
// 导出面：仅供离线夹具 tests/_term-face-harness.mjs 断言「幂等 / 0 引用不渲染 /
// N 计数」——那三条是函数自身的语义，复刻一份实现去断言等于没断言。生产调用点
// 只有 renderWikilinks 尾部一处。
export function renderTermIndex(mdViewer) {
  if (!mdViewer) return 0;
  mdViewer.querySelectorAll('.nf-term-index').forEach((el) => el.remove());
  /** @type {Map<string, any>} 词条名（小写）→ 一条索引记录（正文序首次出现为准） */
  const entries = new Map();
  mdViewer.querySelectorAll('a.nf-wikilink-term').forEach((el) => {
    const a = /** @type {HTMLAnchorElement} */ (el);
    const raw = a.dataset.wikiResolved;
    if (!raw) return;                       // 兜底腿：无载荷 ⇒ 不索引（零请求纪律）
    let hit = null;
    try { hit = JSON.parse(raw); } catch (e) { return; }
    if (!hit || !hit.term || !(hit.absPath || hit.remote)) return;
    const name = String(hit.term.name || a.textContent || '').trim();
    if (!name) return;
    const key = name.toLowerCase();
    if (entries.has(key)) return;           // 同一词条多处引用只列一行
    // 所在文档：图谱节点标题优先，空则回落 term.rel 的基名（去扩展名）——
    // 与引擎 termJson 的字段面一致，前端不造第二套口径。
    const rel = String(hit.term.rel || '');
    const docTitle = String(hit.term.doc || '').trim()
      || rel.split('/').pop().replace(/\.(md|markdown|html?)$/i, '');
    entries.set(key, { name, docTitle, resolved: hit, anchor: a.dataset.wikiAnchor || '' });
  });
  if (!entries.size) return 0;

  const details = document.createElement('details');
  details.className = 'nf-term-index';
  const summary = document.createElement('summary');
  summary.textContent = t('wiki.termIndexCount', { n: entries.size });
  details.appendChild(summary);
  const ul = document.createElement('ul');
  for (const entry of entries.values()) {
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.className = 'nf-wikilink nf-term-index-term';
    a.href = '#';
    a.textContent = entry.name;
    // 非空 target ⇒ 点击腿直接用 dataset 里的消解产物（不落「本文自锚」分支）。
    a.dataset.wikiTarget = entry.name;
    a.dataset.wikiResolved = JSON.stringify(entry.resolved);
    if (entry.anchor) a.dataset.wikiAnchor = entry.anchor;
    li.appendChild(a);
    if (entry.docTitle) {
      const doc = document.createElement('span');
      doc.className = 'nf-term-index-docname';
      doc.textContent = entry.docTitle;      // 文档名唯一出现的地方（作者令）
      li.appendChild(doc);
    }
    ul.appendChild(li);
  }
  details.appendChild(ul);
  mdViewer.appendChild(details);
  return entries.size;
}

// ── Backlinks (KG audit ②, 2026-10-28) ────────────────────────────────
// 文档阅读页底部的折叠小节：谁引用了本文（入边）。数据 = /api/wiki/graph 载荷
// （edges.from/to + nodes，30s TTL 共享缓存）——纯前端可算，零新端点。
// 克制形态：<details> 默认收起；文档不在任何 vault 或零入链 ⇒ 整节不渲染
// （repo reports/ 等图外文件保持零噪音）。条目复用 a.nf-wikilink 的委派点击
// 路径（data-wiki-resolved 直填解析结果 ⇒ openLocalFileTab / remote-doc 腿
// 全部免费复用；边锚 '^blk'/'#Heading' 归一成点击腿认识的形态 ⇒ 点开即跳到
// 引用落点段落）。

/** Normalize a Windows/POSIX path for prefix comparison. */
function normVaultPath(p) {
  return String(p || '').replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
}

/** The graph node for this absPath (null when the doc is outside every vault). */
function graphNodeFor(absPath, data) {
  if (!absPath || !data) return null;
  const norm = normVaultPath(absPath);
  const projects = data.projects || [];
  for (const p of projects) {
    const vault = normVaultPath(p.vault);
    if (!vault || !norm.startsWith(vault + '/')) continue;
    const rel = norm.slice(vault.length + 1);
    const hit = (data.nodes || []).find((n) => !n.stub && n.project === p.name && String(n.rel || '').toLowerCase() === rel);
    if (hit) return { node: hit, project: p };
  }
  return null;
}

/** Mirror resolveWikiTarget's tail: a graph node → the click-leg resolved shape. */
function resolvedForNode(node, project) {
  if (!node || !project) return null;
  if (node.device === 'self') {
    const sep = project.vault.includes('\\') ? '\\' : '/';
    return { absPath: project.vault.replace(/[\\/]+$/, '') + sep + String(node.rel).split('/').join(sep) };
  }
  return { remote: { device: node.device, vault: project.vault, rel: node.rel, project: project.name } };
}

/** Click-leg anchor form: '^blk' stays, '#Heading' drops the '#', 'L42' has no
 *  click mapping (omitted). */
function clickAnchorForm(edgeAnchor) {
  const a = String(edgeAnchor || '');
  if (!a) return '';
  if (a.startsWith('^')) return a;
  if (a.startsWith('#') && a.length > 1) return a.slice(1);
  return '';
}

/** Append the collapsible backlinks section (incoming graph edges) at the foot
 *  of the rendered document. Best-effort background face: any failure = no
 *  section, never an error surface (the #762 render-leg discipline). */
async function renderBacklinks(mdViewer, absPath) {
  let data = null;
  try { data = await wikiGraph(); } catch (e) { return; }
  const found = graphNodeFor(absPath, data);
  if (!found) return;
  const { node, project } = found;
  const incoming = (data.edges || [])
    .filter((e) => e.to === node.id)
    .map((e) => ({ edge: e, from: (data.nodes || []).find((n) => n.id === e.from) }))
    .filter((x) => x.from && !x.from.stub);
  if (!incoming.length) return;
  const byProject = new Map([[project.name, project]]);
  for (const x of incoming) {
    if (!byProject.has(x.from.project)) {
      byProject.set(x.from.project, (data.projects || []).find((p) => p.name === x.from.project) || null);
    }
  }
  const details = document.createElement('details');
  details.className = 'nf-backlinks';
  details.setAttribute('data-cv-hint', 'foot');   // canvas-hint-text 批：文末脚注形态
  const summary = document.createElement('summary');
  summary.textContent = `${t('wiki.backlinks')} · ${incoming.length}`;
  details.appendChild(summary);
  const ul = document.createElement('ul');
  for (const { edge, from } of incoming) {
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.className = 'nf-wikilink nf-backlink';
    a.href = '#';
    a.textContent = from.title || String(from.rel || '').split('/').pop();
    const resolved = resolvedForNode(from, byProject.get(from.project));
    if (resolved) {
      a.dataset.wikiResolved = JSON.stringify(resolved);
      const anchor = clickAnchorForm(edge.anchor);
      if (anchor) a.dataset.wikiAnchor = anchor;
    } else {
      // Cross-project node whose project manifest is absent — no click target.
      a.classList.add('nf-wikilink-unresolved');
    }
    li.appendChild(a);
    if (edge.anchor) {
      const chip = document.createElement('span');
      chip.className = 'nf-backlink-anchor';
      chip.textContent = edge.anchor;
      li.appendChild(chip);
    }
    if (from.project !== project.name && from.project) {
      const tag = document.createElement('span');
      tag.className = 'nf-backlink-project';
      tag.textContent = from.project;
      li.appendChild(tag);
    }
    ul.appendChild(li);
  }
  details.appendChild(ul);
  mdViewer.appendChild(details);
}

/** Handle a click on a rendered wikilink. */
async function onWikilinkClick(a, mdViewer, confirmed = false) {
  hideTermTip();
  const anchorRaw = a.dataset.wikiAnchor || '';
  const target = a.dataset.wikiTarget || '';
  // Anchor normalization: '^id' → block jump; otherwise a heading jump.
  let anchor = anchorRaw.startsWith('^')
    ? { kind: 'block', id: anchorRaw.slice(1) }
    : (anchorRaw ? { kind: 'heading', text: anchorRaw } : null);
  // Self-reference (or an anchor-only link in this document).
  if (!target && !a.dataset.wikiResolved) {
    if (anchor) applyJumpAnchor(mdViewer, anchor);
    return;
  }
  let resolved = null;
  try { resolved = a.dataset.wikiResolved ? JSON.parse(a.dataset.wikiResolved) : null; } catch (e) { resolved = null; }
  if (!resolved) {
    try { resolved = resolveWikiTarget(target, '', await wikiGraph(), { allowTerm: !anchorRaw }); } catch (e) {
      // #762：点击腿是用户主动面——401/403（会话被拒）交 sessionGate 重认证伞，
      // 不再误报「无法解析该链接」；其余错误照旧落 unresolved 提示。
      if (e && (/** @type {any} */ (e).status === 401 || /** @type {any} */ (e).status === 403)) {
        noteAuthRejected('wiki-link');
        return;
      }
      resolved = null;
    }
  }
  if (!resolved) { window.__showToast?.(t('wiki.unresolved'), 'info'); return; }
  if (!resolved.term && !confirmed) {
    const path = resolved.absPath || (resolved.remote ? resolved.remote.vault.replace(/[\\/]+$/, '') + '/' + resolved.remote.rel : '');
    showDocumentTermCard(a, { path, title: a.textContent, remote: !!resolved.remote,
      openDocument: () => onWikilinkClick(a, mdViewer, true) });
    return;
  }
  // 词条命中落点（F2）：作者未写锚时采用消解产物自带的块锚 —— 深链跳到词条
  // 定义段（applyJumpAnchor 滚动 + nf-jump-flash 高亮的既有通道，零新机制）。
  if (!anchor && resolved.anchor) anchor = resolved.anchor;
  // 来源页签 id（F5 返回原文档；v1 单层来源，每次跳转覆写）。
  const refSourceTab = refSourceTabId(mdViewer);
  // F5 阅读位保真：任何跳转派发前固化来源阅读位并冻结 scroll 记账。新开
  // 页签的同步布局抖动会把还在前台的来源滚动盒瞬时 clamp 回 0 并派发 scroll
  // （harness 实测：事件落在 class 切换前 ~50ms，防无可防）——不冻结真值就被
  // artifact 冲掉。冻结到来源页签下次激活（恢复阅读位）时解除。
  const srcPane = mdViewer.closest('.canvas-tab-pane');
  if (srcPane) {
    const sc0 = srcPane.querySelector('.canvas-md-scroll');
    if (sc0) srcPane._mdScrollPos = sc0.scrollTop;
    srcPane._mdPosPinned = true;
  }
  if (resolved.absPath) {
    openLocalFileTab(resolved.absPath, anchor, refSourceTab);
    return;
  }
  if (resolved.remote) {
    try {
      const { authHeaders } = await import('../flowHelpers.js');
      const url = `/api/wiki/remote-doc?device=${encodeURIComponent(resolved.remote.device)}&path=${encodeURIComponent(resolved.remote.vault + '/' + resolved.remote.rel)}`;
      const res = await fetch(url, { headers: { ...authHeaders() } });
      // #762：远端文档腿同属图谱族用户主动面——401/403 交重认证伞，不再裸
      // remoteFetchFailed toast；其余非 2xx 照旧。
      if (res.status === 401 || res.status === 403) {
        noteAuthRejected('wiki-remote-doc');
        return;
      }
      if (!res.ok) throw new Error(String(res.status));
      const body = await res.json();
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: {
          id: 'wiki-remote:' + resolved.remote.project + ':' + resolved.remote.rel,
          itemType: 'markdown',
          title: (target.replace(/\.md$/i, '')) + ' · ' + resolved.remote.device,
          content: body.content || '',
          readOnly: true,
          anchor,
          refSourceTab,
        },
      }));
    } catch (e) {
      window.__showToast?.(t('wiki.remoteFetchFailed'), 'error');
    }
  }
}

/** Markdown viewer - render formatted markdown (read-only preview) */
  /** 名称双显去重（作者令 2026-10-23 六项① + 2026-10-31 Canvas 改造批⑥
   *  「文件名不重复显示（标签页已显）」强化）：
   *  笔记类文档惯以 `# 与文件名相同的标题` 起笔 —— 阅读面里卡头胶囊/chip
   *  条（文件名）之下再渲染同一份名称，作者读作「底层旧名称元素残留」。此处
   *  在阅读渲染面去重：首个块级元素为 H1 且文本与文件名归一相等 ⇒ 隐藏该
   *  标题（display:none，DOM 与源码不动 —— 源码态/导出零影响；锚点结构保留）。
   *  归一口径（批⑥）：双方都剥扩展名与 `__` 溯源尾（本项目文档出处约定
   *  `…__<chainId>` 落在文件名尾，body 零元数据头——起笔 H1 常带全名或
   *  带尾全名）+ trim + 大小写不敏感；首个 H1 之外一律不动。 */
function dedupeTitleHeading(viewer, fileName) {
  if (!viewer || !fileName) return;
  const first = /** @type {HTMLElement|null} */ (viewer.firstElementChild);
  if (!first || first.tagName !== 'H1') return;
  const text = (first.textContent || '').trim();
  if (!text) return;
  const norm = (s) => s.trim().replace(/\.[^.]*$/, '').replace(/__[^_]*$/, '').trim().toLowerCase();
  if (norm(text) === norm(fileName)) first.style.display = 'none';
}

// ── 文档里放图（image-in-doc 批 2026-11-05 · 作者报障「文档里只有路径 图片没
//    渲染出来」）─────────────────────────────────────────────────────────
//
// 三条纪律，一条都不能少：
//   ① **三种写法同等**：`![](path)`（标准）、`![[path]]`（Obsidian 嵌入，也是
//      wiki-docs 纪律 ⑥「图随文档走」推荐的第一形态）、`<img src="…">`（手写
//      HTML）。修前 `![[a.png]]` 落进 renderWikilinks 的 `![[…]]` 正则、被做成
//      **普通引用链接**（正文里只是一段蓝字文件名）—— 那就是作者读到的「只有
//      路径」。现在嵌入在**进 marked 之前**就地展开成 <img>，三条腿汇到同一处
//      取字节（票据 + 账本单点），再没有第二套图片实现。
//   ② **路径解析优先级**（作者令）：①文档自身所在目录 → ②vault 根（`…/.stand/`）
//      → ③绝对路径 / `~` 家目录（由 /api/nf-file 端点展开） → ④终局 = `/api/nf-file`
//      票据化 URL（唯一字节路由，不新开端点）。①② 之间用**加载失败级联**判：
//      前端没有 stat，谁能读出来就是谁（失败才走下一候选，一次一张，不成环）。
//   ③ **失败不许静默**：路径解析不到 / 404 / 票据被拒 ⇒ 一块明确的失败占位
//      （原因文案 + 原路径原文），绝不留白让人以为「图本来就没有」。

/** 图片扩展名（决定 `![[…]]` 是「图片嵌入」还是「文档引用」）。 */
const MD_IMG_EXT_RE = /\.(png|jpe?g|jfif|gif|webp|avif|bmp|svg|ico|tiff?)$/i;

/** vault 根 = 文档库的注册根。wiki-docs 纪律的布局口径（SKILL.md「Vault layout」）：
 *  vault 根 = `<data_root>/projects/<project>/.stand/docs/`，附件按惯例放
 *  `<vault>/attachments/` 或 `<vault>/assets/`（`docs/attachments/…`）。
 *  `/…/.stand/docs/design/x.md` ⇒ `/…/.stand/docs`；
 *  不在 `docs/` 下但仍在 `.stand/` 里（少见）⇒ `/…/.stand`；
 *  两者都不在（repo reports/、/tmp 夹具）⇒ ''，候选表自然少几层，不猜。 */
function vaultRootOf(absPath) {
  if (!absPath) return '';
  const norm = String(absPath).replace(/\\/g, '/');
  const docsIdx = norm.lastIndexOf('/.stand/docs/');
  if (docsIdx >= 0) return String(absPath).slice(0, docsIdx + '/.stand/docs'.length);
  const standIdx = norm.lastIndexOf('/.stand/');
  if (standIdx >= 0) return String(absPath).slice(0, standIdx + '/.stand'.length);
  return '';
}

/** 属性值转义（候选路径/原路径要进 data-*，引号与尖括号必须先折叠）。 */
function escapeAttr(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** ```human 摘要围栏的**卡片体行号集合**（human-card-image-expand 批 2026-11-05）。
 *
 *  口径必须与 utils.js 的卡片 pass 逐字一致，否则会出现「展开进了一个不会
 *  变成卡片的围栏」：那边 `_humanFencesBalanced()`（utils.js:847）只认**第 0 列
 *  起写的** ```human（`/^```human[ \t]*$/`，带别的 info 或带缩进都不算卡片），
 *  关栏 `_transformHumanFace()`（utils.js:924）只认裸 ``` 行；未闭合的围栏在那
 *  边不变换、照旧当代码块显示 ⇒ 这边也必须**不展开**（否则代码块里会露出一串
 *  字面 `<img src=…>`）。
 *
 *  返回的是「**已闭合** 的 ```human 围栏内部行」——本次放开的正是这一段：
 *  卡片体也要出图（作者裁定「以图为主」，见下方 expandImageEmbeds 头注）。
 *  @param {string[]} lines
 *  @returns {Set<number>} 行下标（0 基） */
function humanCardBodyLines(lines) {
  const out = new Set();
  let open = -1;
  for (let i = 0; i < lines.length; i++) {
    if (open < 0) {
      if (/^```human[ \t]*$/.test(lines[i])) open = i;
    } else if (/^```[ \t]*$/.test(lines[i])) {
      for (let j = open + 1; j < i; j++) out.add(j);
      open = -1;
    }
  }
  return out;
}

/**
 * 把 `![[a.png]]` 展开成 `<img src="a.png" alt="…">`（**行数不变** —— 源码行
 * 映射 data-src-line / 块锚 ^id 全部照旧成立）。
 *
 * 只展开**图片扩展名**的目标；`![[doc.md]]` / `[[词条]]` 原样留给
 * renderWikilinks 的既有引用腿（零回归）。**通用**代码围栏内（``` / ~~~）与
 * 行内代码（`…`）里的 `![[…]]` 保持字面 —— 代码块是代码，不是图。
 *
 * 例外（human-card-image-expand 批，作者裁定「以图为主」）：```human 摘要围栏
 * **不是代码块，是人面卡片体**（utils.js `_transformHumanFace` 把围栏正文重新
 * 当 markdown 渲进 `.nf-human-card`），而卡片机制把围栏**外**的一切折进默认收起
 * 的 `<details>` 抽屉 ⇒ 图写在围栏外，读者首屏看不见。所以围栏内嵌图**同样
 * 展开**：卡片体走一条正常的图片腿（候选表 → 铸票 → 账本 → 失败占位），
 * 「路径对 + 在卡片里」从此出图，不再只剩字面量。
 * 判定面不变：裸名/失效路径照旧落失败占位（见 imageCandidates / showMdImageFailure），
 * 放开的是**位置**，不是**路径正确性**。
 * @param {string} md
 * @returns {string}
 */
function expandImageEmbeds(md) {
  if (!md || md.indexOf('![[', 1) < 0) return md;
  const lines = md.split('\n');
  const cardLines = humanCardBodyLines(lines);
  let inFence = false;
  let fenceMarker = '';
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const fenceMatch = FENCE_RE.exec(line);
    if (fenceMatch) {
      if (!inFence) { inFence = true; fenceMarker = fenceMatch[1].slice(0, 3); }
      else if (fenceMatch[1].startsWith(fenceMarker)) { inFence = false; fenceMarker = ''; }
      continue;
    }
    // 围栏内默认不展开；只有「已闭合的 ```human 卡片体」这一种围栏例外。
    if ((inFence && !cardLines.has(i)) || line.indexOf('![[', 0) < 0) continue;
    // 行内代码段（反引号对）不参与：'a `![[x.png]]` b' 里的嵌入是引用示例。
    lines[i] = line.split(/(`+[^`]*`+)/)
      .map((seg, k) => (k % 2 ? seg : seg.replace(/!\[\[([^\[\]]+?)\]\]/g, (m, inner) => {
        const pipe = String(inner).indexOf('|');
        const body = pipe >= 0 ? String(inner).slice(0, pipe) : String(inner);
        const target = body.split('#')[0].trim();
        if (!target || !MD_IMG_EXT_RE.test(target)) return m;   // 非图片嵌入：零改动
        const alias = pipe >= 0 ? String(inner).slice(pipe + 1).trim() : '';
        return `<img src="${escapeAttr(target)}" alt="${escapeAttr(alias || target)}"`
          + ` data-nf-md-embed="${escapeAttr(target)}">`;
      })))
      .join('');
  }
  return lines.join('\n');
}

/** 一张图 src 的**候选绝对路径**表（按作者令的优先级排，去重后交给级联）。
 *
 *  ①`文档自身所在目录 + ref`
 *  ②`vault 根 + ref`（vault 根 = `…/.stand/docs`）
 *  ③`<vault>/attachments/<ref>`、`<vault>/assets/<ref>` —— **vault 的附件惯例目录**。
 *    作者第二份复现样本就是这一条：正文写的是裸文件名 `![[x.png]]`，图却在
 *    `docs/attachments/x.png`（既不在文档同目录、也不在 vault 根），只按 ①② 解析
 *    必然 404 —— 这正是「截图在里面但不渲染」的那条离线路径。
 *  ④绝对路径 / `~`（`~` 由 /api/nf-file 端点展开成 user.home）= 单候选
 *  ⑤此表之外唯一的字节路由始终是 `/api/nf-file?path&ticket`（下方铸票），
 *    不新增端点、直连文件系统一律不经前端。
 *  @param {string} ref 文档里写的原始引用
 *  @param {string} docDir 文档自身目录（'' = 无路径上下文）
 *  @param {string} vaultRoot vault 根（'' = 不在 vault 里）
 *  @returns {string[]} 空数组 = 远端 / data: / 已代理 URL（原样留给浏览器） */
function imageCandidates(ref, docDir, vaultRoot) {
  const src = String(ref == null ? '' : ref).trim();
  if (!src) return [];
  if (/^(https?:|data:|blob:|\/api\/|javascript:|mailto:)/i.test(src)) return [];
  // ④ 绝对路径 / `~` 家目录：单候选
  if (src.startsWith('/') || src.startsWith('~') || /^[A-Za-z]:[\\/]/.test(src)) return [src];
  const rel = src.replace(/^\.\//, '');
  /** @type {string[]} */
  const out = [];
  const push = (p) => { if (p && !out.includes(p)) out.push(p); };
  // ① 文档自身所在目录（`![](assets/x.png)` 的标准 markdown 语义）
  if (docDir) push(docDir + '/' + rel);
  // ② vault 根
  if (vaultRoot) push(vaultRoot + '/' + rel);
  // ③ vault 的附件惯例目录（裸文件名的 Obsidian 嵌入语义 + 本库布局）
  if (vaultRoot) {
    push(vaultRoot + '/attachments/' + rel);
    push(vaultRoot + '/assets/' + rel);
  }
  // 文档在 vault 子目录里时，同级的 attachments/assets 也试一档（`docs/design/x.md`
  // 引用 `docs/attachments/a.png` 的写法）
  if (docDir && vaultRoot && docDir !== vaultRoot) {
    push(docDir + '/attachments/' + rel);
    push(docDir + '/assets/' + rel);
  }
  if (!out.length) push(rel);
  return out;
}

/** 读 `data-nf-md-alt` 里剩下的候选 URL 表（encodeURIComponent(JSON) 载体）。 */
function readAltUrls(ds) {
  try {
    const parsed = JSON.parse(decodeURIComponent(ds.nfMdAlt || ''));
    return Array.isArray(parsed) ? parsed.filter((u) => typeof u === 'string' && u) : [];
  } catch (e) { return []; }
}

/** 失败占位的**原因文案**：HTTP 状态码 → 作者能据以行动的一句话。
 *  @param {number} status @param {string} [detail] */
function mdImageFailReason(status, detail) {
  const s = Number(status) || 0;
  if (s === 404 || s === 410) return t('canvas.imgFailMissing', { status: String(s) });
  // 401 / 403 **分叉**（作者裁 2026-11-19「区分」）：401 = 请求根本没带票（真因 =
  // 没有凭据：铸票面没给票 / 退避窗内无票 / URL 形状问题），403 = 带了票但票被判据
  // 拒绝（`X-Nf-Reason`：invalid-ticket / expired / path-mismatch / credential-*）。
  // 共用一句会把「没有票」误报成「票被拒」—— 服务端语义未动，只拆文案与分支。
  if (s === 403) return t('canvas.imgFailDenied', { status: String(s) });
  if (s === 401) return t('canvas.imgFailNoCredential', { status: String(s) });
  if (s === 429 || s === 503) return t('canvas.imgFailBusy', { status: String(s) });
  if (s === 400) return t('canvas.imgFailBadPath', { status: String(s) });
  if (s === 415) return t('canvas.imgFailUnsupported', { status: String(s) });
  if (s === 0) return t('canvas.imgFailUnreachable');
  return t('canvas.imgFailUnknown', { status: String(s), detail: detail || '' });
}

/** 尽力探一次终局 URL 的 HTTP 状态（只为把「为什么失败」写准；失败面已经在）。 */
async function probeMdImageUrl(url) {
  if (!url) return 0;
  try {
    const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
    return res.status;
  } catch (e) { return 0; }
}

/** 失败占位的 **HTML 串**形态（**铸票面当场判决**用：全部候选都被端点拒绝
 *  ⇒ 连 <img> 都不发，直接落一块明确占位，原因来自服务端 `rejected` 判决）。
 *  与 showMdImageFailure 产物同构（同一组 class + 同一个 data 标记），样式与
 *  测试选择器两边共用一份。
 *  @param {string} ref 文档里写的原路径原文
 *  @param {number} status 由服务端 reason 映射来的状态码（404/403/415/0）
 *  @param {string} [detail] 服务端 reason 原文（诊断留痕）
 *  @returns {string} */
function mdImageFailHtml(ref, status, detail) {
  return '<div class="nf-md-img-fail" data-nf-md-img-fail="1"'
    + ` data-nf-md-img-status="${escapeAttr(String(status || 0))}"`
    + (detail ? ` data-nf-md-img-reason="${escapeAttr(detail)}"` : '')
    + '>'
    + `<div class="nf-md-img-fail-title">${escapeAttr(t('canvas.imgFailTitle'))}</div>`
    + `<div class="nf-md-img-fail-reason">${escapeAttr(mdImageFailReason(status, detail))}</div>`
    + `<code class="nf-md-img-fail-path">${escapeAttr(ref)}</code>`
    + '</div>';
}

/** 终局失败面：把坏掉的 <img> 换成一块**明确**的占位（原因 + 原路径），
 *  绝不留白。视频/文档里作者能立刻读到「哪张图、哪条路径、为什么」。
 *  @param {HTMLImageElement} img */
function showMdImageFailure(img) {
  if (!img || !img.isConnected) return;
  const ds = img.dataset || {};
  const authored = ds.nfMdEmbed ? `![[${ds.nfMdEmbed}]]` : (ds.nfMdSrc || img.getAttribute('src') || '');
  const box = document.createElement('div');
  box.className = 'nf-md-img-fail';
  box.setAttribute('data-nf-md-img-fail', '1');
  if (ds.nfMdEmbed) box.setAttribute('data-nf-md-embed', ds.nfMdEmbed);
  const head = document.createElement('div');
  head.className = 'nf-md-img-fail-title';
  head.textContent = t('canvas.imgFailTitle');
  const reason = document.createElement('div');
  reason.className = 'nf-md-img-fail-reason';
  const known = Number(ds.nfMdErr || 0);
  reason.textContent = mdImageFailReason(known, ds.nfMdErrText);
  const pathEl = document.createElement('code');
  pathEl.className = 'nf-md-img-fail-path';
  pathEl.textContent = authored;
  box.append(head, reason, pathEl);
  img.replaceWith(box);
  // 原因精修：渲染期没拿到状态码（直取票据 URL 失败）⇒ 探一次状态码写回。
  if (!known) {
    const probeUrl = ds.nfMdFb || img.getAttribute('src') || '';
    probeMdImageUrl(probeUrl).then((status) => {
      if (!box.isConnected) return;
      box.setAttribute('data-nf-md-img-status', String(status));
      reason.textContent = mdImageFailReason(status, '');
    }).catch(() => { /* 占位已经在，探测器失败不改结论 */ });
  }
}

async function viewMarkdown(pane, { content, absPath, fileName, anchor: jumpAnchor, readOnly }) {
  // Content-unchanged guard. Focus/visibility refreshes re-fetch the same
  // file and re-render every open tab; rebuilding .canvas-md-scroll via
  // innerHTML resets scrollTop and throws the reader back to the top (bug:
  // Canvas md reading position lost on app switch). Same "unchanged never
  // remounts" rule as the Monaco path in canvas.js openWorkspaceItem. The
  // guard also requires a live preview DOM: a source-mode round trip wipes
  // innerHTML before calling back into this function, so scroller is null
  // there and the re-render must proceed. A jump anchor still applies to the
  // existing DOM (re-clicking a reference in an open doc must jump again).
  const scroller = pane.querySelector('.canvas-md-scroll');
  // Source analysis BEFORE the guard: the unchanged-content early-return
  // below still applies jump anchors (re-clicking a reference in an open
  // doc must jump), and a block anchor needs this document's ^id → line
  // map. analyzeSource is pure and cheap (fence-aware line walk), and it
  // MUST run for content-only opens too (no absPath — the remote-doc leg):
  // without it blockIds stays empty and every `#^id` jump into a remote
  // doc silently no-ops (段落级引用批 repaired: block jumps are the whole
  // point of the graph's paragraph-anchored references).
  const analysis = analyzeSource(content || '');
  if (scroller && /** @type {any} */ (pane)._renderedMdContent === content) {
    const existingViewer = pane.querySelector('.canvas-md-viewer');
    if (existingViewer && jumpAnchor) applyJumpAnchor(existingViewer, jumpAnchor, analysis.blockIds);
    return;
  }

  // Capture before rebuild: when content actually changed (external edit),
  // restore the reader's position afterwards so the refresh at worst drifts
  // but never jumps to the top. The scrollTop setter auto-clamps when the
  // new content is shorter than the old scroll offset.
  const prevScrollTop = scroller ? scroller.scrollTop : 0;

  const { renderMarkdownWithMath } = await import('../utils.js');
  // 进 marked 之前先把 Obsidian 图片嵌入展开成 <img>（见 expandImageEmbeds 头注）：
  // 三条图片写法此后走同一条取字节腿，renderWikilinks 再也不会把一张图做成蓝字链接。
  let html = renderMarkdownWithMath(expandImageEmbeds(analysis.html), false);

  // Directory of this document — resolves BOTH the relative image paths below
  // and the relative link hrefs in the click handler. Computed once, so the
  // image rewrite and the link routing cannot disagree about what a relative
  // path in this document means.
  const baseDir = absPath
    ? absPath.substring(0, absPath.lastIndexOf(absPath.includes('\\') ? '\\' : '/'))
    : '';
  // vault 根（`…/.stand/`）—— 路径解析优先级 ②。
  const vaultBase = vaultRootOf(absPath);

  // 文档图片 pass（三种写法在此汇合；解析优先级见 imageCandidates 头注）：
  // ①收集候选 → ②一次批量铸票 → ③逐图写占位符 → ④账本单点解析终局 src →
  // ⑤onerror 级联（vault 根腿）→ ⑥终局 = 明确失败占位（绝不留白）。
  if (absPath) {
    const IMG_SCAN_RE = /<img\s+[^>]*src="([^"]+)"/g;
    // 匹配**整枚标签**（到 `>` 为止），不是「只到 src 的收尾引号」——失败面是
    // 一块整块替换（mdImageFailHtml 返回一个 div），只吃 `src="…"` 会把后面的
    // ` alt="…" data-nf-md-embed="…">` 原样留在正文里当字面量（human-card-img-
    // expand 批 2026-11-05 截图实证：失败占位下面拖着一行 `alt="…"` 残渣）。
    const IMG_REWRITE_RE = /(<img\s+[^>]*src=")([^"]+)("[^>]*>)/g;
    /** 原始 src → 已解码引用（marked 会 percent-encode：`assets/截屏 1.png` 与
     *  `<assets/x 2.png>` 都到这里变成 `assets/%E6%88%AA…%201.png`。先解码，
     *  否则下面再转义一次、端点去找一个字面 `%E6…` 的文件。非法转义（名字里
     *  的真 `%`）原样用）。 */
    const decodeRef = (raw) => {
      try { return decodeURIComponent(raw); } catch (e) { return raw; }
    };
    // Pass 1 — collect every candidate, then mint ONE batched ticket set
    // (dozens of images in a document must not mean dozens of requests).
    // 2026-09-11 (C batch): the credential is now a per-path ticket; the
    // global token never appears in an image URL again (T2).
    // 候选（文档目录 + vault 根两条腿）**一起铸票**：级联时不再多发一次铸票。
    const candidates = new Set();
    html.replace(IMG_SCAN_RE, (m, raw) => {
      for (const p of imageCandidates(decodeRef(raw), baseDir, vaultBase)) candidates.add(p);
      return m;
    });
    const tickets = await mintTickets([...candidates]);
    // 铸票判决（`rejected` 表，见 nfTicket.ticketRejects 头注）：被端点拒绝的
    // 候选 = **确定读不到**（not-found / credential / extension），直接跳过，
    // 既不白发一次注定 401 的请求，也不让「文件不存在」被读成「票据被拒」。
    const rejects = ticketRejects();
    /** 服务端 reason → 状态码（失败占位据此说人话）。 */
    const reasonStatus = (reason) => {
      const r = String(reason || '');
      if (r === 'not-found' || r === 'missing') return 404;
      if (r.startsWith('credential') || r === 'denied') return 403;
      if (r === 'extension-not-allowed') return 415;
      return 0;
    };
    // Pass 2 — rewrite to SHORT-LIVED placeholders (index tokens), synchronously.
    // 二次加载清剿批（2026-10-24 · #816 同法）：出帧前每张图的最终 src 先经
    // **账本单点**解析（下方 hydration）——占位符阶段保证 innerHTML 落地时图已带
    // 终局 src，浏览器不会先对票据 URL 发一轮注定重复的抓取。
    /** @type {{ path: string, urls: string[], finalUrl?: string, errStatus?: number, errText?: string }[]} */
    const mdImgs = [];
    html = html.replace(IMG_REWRITE_RE, (m, prefix, raw, suffix) => {
      const ref = decodeRef(raw);
      const all = imageCandidates(ref, baseDir, vaultBase);
      if (!all.length) return m;    // 远端 / data: / 已代理 URL：原样交给浏览器
      // 候选分三类：有票（可读）｜被拒（确定不可读，跳过）｜未知（铸票没判决，
      // 例如本轮铸票请求本身失败 ⇒ 保留既有「无票 URL → 401 → 失败面」退化腿）。
      const live = all.filter((p) => tickets.get(p));
      const unknown = all.filter((p) => !tickets.get(p) && !rejects.has(p));
      const usable = live.concat(unknown);
      const urlOf = (p) => {
        const tk = tickets.get(p);
        return `/api/nf-file?path=${encodeURIComponent(p)}${tk ? `&ticket=${encodeURIComponent(tk)}` : ''}`;
      };
      if (!usable.length) {
        // 全部候选被端点拒绝 ⇒ **零请求**，当场给明确失败占位（原因来自服务端判决）。
        const first = all.find((p) => rejects.has(p)) || all[0];
        const status = reasonStatus(rejects.get(first));
        return mdImageFailHtml(ref, status, rejects.get(first));
      }
      const urls = usable.map(urlOf);
      const i = mdImgs.length;
      mdImgs.push({ path: usable[0], urls });
      // token sits INSIDE the src="…" value; the fallback / 候选 / 原路径都骑
      // **属性外**的第二个占位符（把字面 URL 塞进引号值里会把标记写坏）。
      //   data-nf-md-fb   = 账本 blob 解码失败时回落的那枚票据 URL
      //   data-nf-md-alt  = 剩余候选（vault 根腿）→ onerror 级联
      //   data-nf-md-src  = 文档里写的原路径原文（失败占位要原样给人看）
      //   data-nf-md-err  = 渲染期已知的 HTTP 状态（有则不必再探）
      // 位置：**src 收尾引号之后、标签原有其余属性之前**（`suffix` 以那枚引号
      // 开头，`slice(1)` 才是剩下的 ` alt="…">`）——写成「整枚标签后缀」以后
      // 忘了这一步，属性就会掉到 `>` 外面，变成一段裸文字。
      return `${prefix}__NF_MD_IMG_${i}__"`
        + ` data-nf-md-fb="__NF_MD_FB_${i}__"`
        + ` data-nf-md-alt="${escapeAttr(encodeURIComponent(JSON.stringify(urls.slice(1))))}"`
        + ` data-nf-md-src="${escapeAttr(ref)}"`
        + ` data-nf-md-path="${escapeAttr(usable[0])}"`
        + ` data-nf-md-err="__NF_MD_ERR_${i}__"`
        + suffix.slice(1);
    });
    // Hydration — every rewritten image resolves its final src through the
    // ledger single point (`nfViewerUrl`): `nf:<path>` hit = blob URL (ZERO
    // fetch — e.g. the same image already rendered in a bubble or opened in
    // Canvas); miss = the existing ticketed fetch + `nfCachePut` (exactly once,
    // so re-opens/re-renders of this doc and Canvas opens of the image reuse
    // the bytes). Failure (mint/fetch) ⇒ the ticketed URL (browser fetches it
    // directly — the pre-batch behavior, verbatim) —— 但失败**状态码记账**，
    // 让终局失败占位能说出「404 不存在 / 403 票据被拒」而不是一句泛泛的失败。
    await Promise.all(mdImgs.map(async (e) => {
      try {
        e.finalUrl = await nfViewerUrl({ absPath: e.path, objectUrl: null, pane });
      } catch (err) {
        e.finalUrl = e.urls[0];
        /** @type {any} */ (e).errStatus = Number(/** @type {any} */ (err).status || 0);
        /** @type {any} */ (e).errText = String((err && /** @type {any} */ (err).message) || err || '');
      }
    }));
    html = html.replace(/__NF_MD_FB_(\d+)__/g, (m, i) => {
      const e = mdImgs[Number(i)];
      // fallback 属性只对「账本 blob src」在场（票据 URL 直取的图，失败面 = 下方占位）。
      return e && e.finalUrl && e.finalUrl !== e.urls[0] ? e.urls[0] : '';
    });
    html = html.replace(/__NF_MD_ERR_(\d+)__/g, (m, i) => {
      const e = mdImgs[Number(i)];
      return e && e.errStatus ? String(e.errStatus) : '';
    });
    html = html.replace(/__NF_MD_IMG_(\d+)__/g, (m, i) => {
      const e = mdImgs[Number(i)];
      return e ? (e.finalUrl || e.urls[0]) : m;
    });
    // Broken-image → 级联 → 明确失败占位。
    // The shown URL must never carry a credential (T2 / Δ⑦): the ONE strip
    // implementation lives in nfTicket.js — this module imports it directly.
    // （`window.__nfMdImgFail` 仍在发布，因为 onerror 是内联属性、求值于全局
    //   作用域——模块 import 不可见。）
    // 顺序：①账本 blob 解码失败的自愈腿（既有语义逐字保留：逐出账本 + 回落票据
    // URL 恰一次）→ ②候选级联（文档目录腿失败 ⇒ vault 根腿，一次一张、不成环）
    // → ③终局失败占位（原因 + 原路径，绝不留白）。
    /** @type {any} */ (window).__nfMdImgFail = (/** @type {HTMLImageElement} */ img) => {
      if (!img || !img.dataset) return;
      const ds = img.dataset;
      const fallback = ds.nfMdFb || '';
      // ① 账本 blob 解码失败：坏字节逐出账本，回落票据 URL 恰一次。
      if (img.src && img.src.startsWith('blob:') && fallback && !ds.nfMdRetried) {
        ds.nfMdRetried = '1';
        const q = /[?&]path=([^&]*)/.exec(fallback);
        const p = q ? decodePathParam(q[1]) : null;
        if (p) void nfCacheDrop('nf:' + p).catch(() => {});
        img.src = fallback;
        return;
      }
      // ①′ 票据自愈（B1，票据自愈批 2026-11-19 · 作者令「文档里的图片，重启/票过期后
      // 必须自愈恢复，不许长期停在错误态」）：<img>/<video> 元素腿此前只有「①账本坏字节
      // 逐出 + 回落那枚**可能已过期**的候选 URL」与「②候选级联」两条腿 —— 网关重启
      // 清空票仓、或票 TTL 过期后，回落的那枚旧票 URL 同样 401/403，于是（② 的候选全都
      // 骑同一批失效票）直落 ③ 终局占位（作者实拍「无权读取，票据被拒（HTTP 401）」停住）。
      // 这里补一条腿：**重铸新票**并重试。覆盖面 = 401 与 403 的**全部** reason
      // （invalid-ticket / expired / path-mismatch / credential-* —— 只按**状态类**判，
      // 不筛服务端 reason，因为元素腿拿不到 reason 时要照旧自愈）。
      //   · 闸① **只对票的失败动手**：onerror 不带状态，所以用既有 `probeMdImageUrl`
      //     探一次；探到 401/403 才重铸。404/400/415 等非票因 ⇒ 逐字交回既有 ② 候选
      //     级联（否则会把「文件不存在」误当票据问题去重铸，并对不存在路径打转 ——
      //     本轮自测抓到过该回归：vault 根候选 404 被重铸腿截住，② 的 attachments
      //     候选永远轮不到）。
      //   · 闸② **每次失败至多重铸 3 次**（nfMdRerolls 计数）：任何情形都不成环。
      //   · 探到的状态回填 nfMdErr ⇒ ③ 不必再白探一次。
      // 服务端票仓语义与校验强度**一个字不动**：这只是把客户端已有的「重铸 + 重试」
      // 自愈腿补齐到元素腿。
      const rerolls = Number(ds.nfMdRerolls || 0);
      const carryingBlob = (img.getAttribute('src') || '').startsWith('blob:');
      if (rerolls < 3 && !carryingBlob) {
        const p = ds.nfMdPath || '';
        const known = Number(ds.nfMdErr || 0);
        if (p && (known === 0 || known === 401 || known === 403)) {
          ds.nfMdRerolls = String(rerolls + 1);
          const failing = img.getAttribute('src') || '';
          const decide = known ? Promise.resolve(known) : probeMdImageUrl(failing).catch(() => 0);
          decide.then((s) => {
            if (!img.isConnected) return;
            const st = Number(s) || 0;
            if (st === 401 || st === 403) {
              return reMint(p)
                .then((url) => {
                  if (!img.isConnected) return;
                  const next = String(url || '');
                  if (next && next !== img.getAttribute('src')) img.src = next; // 新票 ⇒ 再试
                  else if (/** @type {any} */ (window).__nfMdImgFail) /** @type {any} */ (window).__nfMdImgFail(img); // 拿不到新票 ⇒ 走 ②/③
                })
                .catch(() => { if (/** @type {any} */ (window).__nfMdImgFail) /** @type {any} */ (window).__nfMdImgFail(img); });
            }
            if (st) ds.nfMdErr = String(st);            // 回填（③ 不再白探）
            if (/** @type {any} */ (window).__nfMdImgFail) /** @type {any} */ (window).__nfMdImgFail(img); // 非票因 ⇒ 交回 ②/③
          });
          return;
        }
      }
      // ② 候选级联：vault 根腿（或反过来的文档目录腿）再试恰一次/候选。
      const alts = readAltUrls(ds);
      if (alts.length) {
        ds.nfMdAlt = encodeURIComponent(JSON.stringify(alts.slice(1)));
        // 这个候选就是**真实生效**的路径了：看原图（lightbox）要拿它。
        const q2 = /[?&]path=([^&]*)/.exec(alts[0]);
        const np = q2 ? decodePathParam(q2[1]) : null;
        if (np) ds.nfMdPath = np;
        img.src = alts[0];
        return;
      }
      // ③ 终局：明确失败占位。
      img.title = t('canvas.imgFailed') + ': ' + stripCredentialParams(ds.nfMdSrc || img.src || '');
      showMdImageFailure(img);
    };
    html = html.replace(
      /<img\b/g,
      '<img onerror="window.__nfMdImgFail && window.__nfMdImgFail(this)"'
    );
  }

  pane.classList.remove('scrollable');
  // P0 §6 顶垫共享类（防胶囊遮挡；桌面常态由 split.css 尾块钉，手机层常态垫）。
  //
  // Canvas 改造批⑦（2026-10-31 作者令）：md 退出统一阅读 chrome（阅读器视角
  // 仅电子书格式生效）——正文 DOM 原样自建直接挂 pane（.canvas-md-scroll
  // 自带 absolute/inset:0/overflow-y:auto，split.css），无底栏/TOC 抽屉/
  // 翻页动效；阅读位记账（下方 scroll 监听 + canvas-tab-activated）是 pane
  // 级机制、零 chrome 依赖，逐字保留。
  if (/** @type {any} */ (pane)._nfReaderTeardown) {
    try { /** @type {any} */ (pane)._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    /** @type {any} */ (pane)._nfReaderTeardown = null;
  }
  // 双层重叠修复批（作者报障 2026-11-02 23:07「底下有一层内容固定，上面有一层
  // 内容可以滑动」）：重渲挂新 scroller 前必须**清掉本 pane 的一切旧阅读面**
  //（.canvas-md-scroll/.canvas-md-viewer 及旧角浮件）。根因：本函数的
  // unchanged-guard 只挡「内容没变」的重入；内容**变了**（外部改动/刷新腿/重开
  // 腿）就走全量重建，而此前的挂载腿是 `pane.appendChild(mdScroller)` —— 旧
  // scroller 从不摘除。`.canvas-md-scroll` 是 `position:absolute; inset:0` 的
  // 全幅独立滚动盒（split.css:2007），两个实例完全重叠：旧层滚动位冻结（无新
  // 内容写入、非 active 记账监听也不更新）=「底层内容固定」；新层照常滚动 =
  // 「上层可滑」，两层文字交错同屏 —— 正是作者实拍的双影。
  // 复现（tests/mobile-dup-layer.spec.mjs）：同 pane 先渲 v1 → scrollTop=500
  // → 再渲 v2 ⇒ 修前 pane 内 .canvas-md-scroll = 2（必红）；修后 = 1（必绿）。
  // 根因早于本批（git log -L：`pane.appendChild` 随 Canvas 改造批⑦ 2026-10-31
  // dc7c965bb 落地；本批堆叠快照腿只读克隆，与此无关 —— 申报存证）。
  pane.querySelectorAll(':scope > .canvas-md-scroll, :scope > .canvas-md-viewer').forEach((n) => n.remove());
  pane.querySelectorAll(':scope > .canvas-source-toggle, :scope > .canvas-find-toggle').forEach((n) => n.remove());
  const mdHost = document.createElement('div');
  mdHost.className = 'canvas-md-viewer';
  mdHost.innerHTML = html;
  // 作者 2026-11-24 令文档首行缩进「都去掉」。剥除直接子段落开头的 em space /
  // 全角空格，避免源码中已有空格造成视觉缩进；不改变源文件或列表等结构内容。
  mdHost.querySelectorAll(':scope > p').forEach((p) => {
    const first = /** @type {Text} */ (p.firstChild);
    if (!first || first.nodeType !== 3) return;
    const raw = first.nodeValue || '';
    const stripped = raw.replace(/^[\u2003\u3000]+/, '');
    if (stripped !== raw) first.nodeValue = stripped;
  });
  const mdScroller = document.createElement('div');
  mdScroller.className = 'canvas-md-scroll canvas-viewer-safe-top';
  mdScroller.appendChild(mdHost);
  pane.appendChild(mdScroller);

  // marked v5+ dropped the `headerIds` option, so add slug ids manually
  // for TOC anchor navigation. Keeps word chars, spaces, CJK, hyphens;
  // strips other punctuation, lowercases, spaces→hyphens.
  // （P1：id 分配前移到 chrome 挂载前——TOC 抽屉条目要带锚 id。）
  {
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s一-鿿-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    mdHost.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(h => {
      if (h.id) return;
      let slug = slugify(h.textContent);
      if (!slug) slug = 'heading';
      if (slug in slugCounts) { slugCounts[slug]++; slug = `${slug}-${slugCounts[slug]}`; }
      else slugCounts[slug] = 0;
      h.id = slug;
    });
    // Source-line map for reference jumps (only meaningful with a file).
    if (analysis.blockStarts.length) assignSourceLines(mdHost, analysis.blockStarts);
  }
  /** @type {any} */ (pane)._mdScrollPos = /** @type {any} */ (pane)._mdScrollPos || 0;
  // 阅读位保真（词条引用批 batch 2，F5 返回原文档配套）：pane 切换走
  // display:none，Chromium 不保留滚动盒（实测 scrollTop 178→0）。scroll 监听
  // 记账 pane._mdScrollPos，激活时（canvas-tab-activated，canvas.js 只对带
  // 阅读位记账的 pane 派发）恢复；跳后 2s 窗口内不抢 TOC/引用落点。
  const scrollTrack = mdScroller;
  {
    // display:none 化（切走页签）时 Chromium 把滚动盒重置为 0 并派发 scroll——
    // 那是布局 artifact 不是阅读意图，记账必须跳过（否则回来时永远恢复成 0）。
    // 判据用 active 类而非 clientHeight：artifact 事件的派发时序里 clientHeight
    // 读数不可靠（实测守不住），而 classList 是 JS 同步面。
    scrollTrack.addEventListener('scroll', () => {
      if (!pane.classList.contains('active')) return;   // 切走后的 artifact（见上）
      if (pane._mdPosPinned) return;                    // 跳转冻结窗（onWikilinkClick）
      pane._mdScrollPos = scrollTrack.scrollTop;
    }, { passive: true });
  }
  dedupeTitleHeading(mdHost, fileName);
  /** @type {any} */ (pane)._renderedMdContent = content;
  // Jump face: the live analysis stays on the pane so a reference click into
  // an ALREADY-OPEN tab (canvas.js switches without re-rendering) can still
  // resolve block anchors — it dispatches nf-jump-anchor on the pane.
  /** @type {any} */ (pane)._mdAnalysis = analysis;
  if (!/** @type {any} */ (pane)._mdJumpBound) {
    /** @type {any} */ (pane)._mdJumpBound = true;
    pane.addEventListener('nf-jump-anchor', (/** @type {CustomEvent} */ e) => {
      const viewer = pane.querySelector('.canvas-md-viewer');
      const blockIds = /** @type {any} */ (pane)._mdAnalysis?.blockIds;
      if (viewer && e.detail) applyJumpAnchor(viewer, e.detail, blockIds);
    });
    // 阅读位恢复：切换回来（含「返回原文档」钮的单击）时把滚动盒写回记账值。
    pane.addEventListener('canvas-tab-activated', () => {
      // 解冻跳转冻结窗：回到来源页即恢复用户滚动记账权。
      /** @type {any} */ (pane)._mdPosPinned = false;
      if (Date.now() - (/** @type {any} */ (pane)._mdJumpAt || 0) < 2000) return;
      const sc = pane.querySelector('.canvas-md-scroll');
      if (sc && Number.isFinite(/** @type {any} */ (pane)._mdScrollPos)) {
        sc.scrollTop = /** @type {any} */ (pane)._mdScrollPos;
      }
    });
  }

  // Restore the reader's position after a content-changed rebuild. Clamped
  // automatically if the new document is shorter (scrollTop setter).
  const newScroller = pane.querySelector('.canvas-md-scroll');
  if (newScroller && prevScrollTop > 0) newScroller.scrollTop = prevScrollTop;

  // slug id 与 source-line 映射已在 chrome 挂载前落定（P1 TOC 抽屉需要锚 id），
  // 此处只取引用继续链接路由/wiki 面接线。
  const mdViewer = pane.querySelector('.canvas-md-viewer');
  if (mdViewer) {
    // Link routing — the SAME criterion the HTML preview frame runs
    // (`routeLocalHref` in viewers/shared.js, embedded by source into the
    // frame's injected script, so there is one definition and no drift).
    // This pane renders markdown INTO the app document: the preview surface is
    // not a sandboxed frame and has no navigation guard, so an unhandled
    // `[x](doc.md)` navigated the WHOLE application away (URL became /doc3.md,
    // 404, #activity-bar gone). Local paths now open as Canvas tabs, external
    // http(s) links as Canvas URL tabs, `#` anchors still scroll in this pane.
    // 2026-09-12 (canvas-preview nav parity, residual face): a markdown
    // deliverable is the same authored-markup surface as an HTML one and gets
    // the same browser-grade link behaviour.
    mdViewer.addEventListener('click', (e) => {
      const wikilink = e.target instanceof Element ? e.target.closest('a.nf-wikilink, a.nf-term') : null;
      if (wikilink) {
        // 词条点击显示释义小弹层并消费点击；预览不触发文档导航。
        if (termTapExpand(/** @type {HTMLAnchorElement} */ (wikilink), mdViewer)) {
          e.preventDefault();
          e.stopPropagation();
          return;
        }
        e.preventDefault();
        e.stopPropagation();
        onWikilinkClick(/** @type {HTMLAnchorElement} */ (wikilink), mdViewer);
        return;
      }
      const link = e.target.closest('a[href]');
      if (!link) return;
      const href = link.getAttribute('href');
      const route = routeLocalHref(href, baseDir);
      if (route.kind === 'anchor') {
        // TOC anchor: scroll within the pane's content container, not the window,
        // and not any other scrollable ancestor either (scrollHeadingToTop above).
        // marked v12 URL-encodes CJK chars in href="#..." anchors, but heading IDs
        // use raw characters. Try decoded first, fall back to raw.
        if (!href || href === '#') return;
        e.preventDefault();
        const raw = href.slice(1);
        let decoded;
        try { decoded = decodeURIComponent(raw); } catch { decoded = raw; }
        const target = mdViewer.querySelector(`[id="${decoded}"]`) ||
                       mdViewer.querySelector(`[id="${raw}"]`);
        if (target) scrollHeadingToTop(mdViewer, target);
        return;
      }
      // `none`: mailto:/tel:/data:/javascript: — the browser's own business.
      if (route.kind === 'none') return;
      // Never let a link replace the application document: `unresolved` is a
      // relative href in a document opened without a path (no base to resolve
      // against) — it has no target, so the click resolves to nothing at all.
      e.preventDefault();
      if (route.kind === 'external') openExternalUrlTab(route.url);
      else if (route.kind === 'local' && route.path) openLocalFileTab(route.path);
    });

    // Wiki face: wikilinks (async, post-render) + jump anchor + backlinks foot
    // (KG audit ② — incoming edges only, collapsed; absent outside vaults).
    // md/html 类型硬约束闸（设计稿 §8 词条同闸回写；调研稿 §9-1/§9-7）：wikilink
    // 渲染消解（含词条引用第二批 F1 的词条回退——已落本闸内侧）仅对四扩展名
    // 生效；非 md/html 文档 `[[词条]]` 按普通文本渲染不解析、点击无深链。viewer
    // 注册表今天只把 .md/.markdown 指到本件，本闸是防再映射的显式单点（与
    // askDock 同挂 mdHtmlGate.js 白名单）。
    if (absPath && isMdHtmlFile(absPath)) { renderWikilinks(mdViewer, baseDir, absPath); renderBacklinks(mdViewer, absPath); }
    if (jumpAnchor) applyJumpAnchor(mdViewer, jumpAnchor, analysis.blockIds);
    // 词条气泡随滚动收起：fixed 定位气泡不跟随文档滚动，锚点滚走即失位。
    const mdScroller = pane.querySelector('.canvas-md-scroll');
    if (mdScroller) mdScroller.addEventListener('scroll', hideTermTip, { passive: true });
    // Mermaid face: hydrate ```mermaid placeholders (lazy viewport render —
    // js/mermaidRenderer.js). Idempotent; no-op when the doc has no fences.
    hydrateMermaidIn(mdViewer);
  }

  addSourceToggle(pane, viewMarkdown, { content, absPath, fileName, readOnly: readOnly === true });
  // Canvas 改造批⑦⑧（2026-10-31）：md 已无阅读器底栏，角浮件回落单行同簇
  //（右下 48px 间距档：源码 16 · 查找 64 · 壳刷新钮实测续排）；图谱圆钮随
  // 批④退役，词条面走 vault 入口。
  // canvas 控件尺寸对齐批（2026-11-02）：钮 40→48 ⇒ 槽距 56，查找钮 64 → 72
  //（= 源码 16 + 48 + 8；缝 8px 不变，只放大不挤压）。
  addFindToggle(pane, 72);
}

export default {
  name: 'markdown',
  label: 'Markdown',
  extensions: ['.md', '.markdown'],
  binary: false,
  priority: 0,
  render: viewMarkdown,
};
