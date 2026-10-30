// viewers/annotations.js — 引擎通用标注面（阅读器 P3 生态批，锚设计稿
// 20261030_181655_reader-experience-design §4.2）。电子书库项目为未来消费方
// （以「卡片 ↔ annotation 查询」消费存储件，本批不消费、只落通用件；PDF 标注
// 本批不做——设计稿 §4.2 防增额边界）。
//
// 数据面（设计稿锁死）：
//   annotation = { id, bookRef(文件路径锚), format(epub|mobi|azw3),
//     spine: { chapterIdx },            // 本批实现 spine 的 chapterIdx 腿
//     locator: { quote(选文前 60 字), offset(章内字偏移) },
//     color: yellow|green|blue|pink,    // 设计稿四色
//     note: 批注文本（可空 = 纯高亮）, createdAt, updatedAt }
//
// 定位鲁棒性（设计稿 §4.2）：quote 为主键（全章精确命中，多命中取离 offset 最近）
// → quote 失配按 offset ±400 窗最长公共前缀模糊找回（灰显回渲）→ 双失配标注灰显
// 不丢数据（抽屉条目带「原文已变动」徽记）。
//
// 存储面（实现批裁 = json 件，服务端 ReaderAnnoStore 为纯核）：随工作区落库
// `<projectRoot>/.nebflow/reader-annotations.json`，整件读写走 WS 窄 op
// `reader.anno`（load/save，reqId 关联应答）。WS 不可用时退 localStorage 镜像
// （跟人走降级，不阻塞阅读）。整件形态：{ v:1, annotations:[…] }——普通 JSON，
// 任何文件工具可读（电子书库消费面）。

import { sendWs, onMessage } from '../ws.js';
import state from '../state.js';
import { t } from '../i18n.js';
import { escapeHtml } from './shared.js';

/** quote 前 60 字（设计稿 locator.quote 口径）。 */
export const QUOTE_LEN = 60;
/** offset 模糊找回窗口（±字）。 */
const FUZZY_WINDOW = 400;
/** 模糊命中所需的最长公共前缀下限。 */
const FUZZY_MIN_PREFIX = 8;
/** 落盘防抖（ms）——连击标注重组为整件一次写。 */
const FLUSH_MS = 400;
/** WS 无应答兜底（ms）：转 localStorage 镜像起步。 */
const LOAD_FALLBACK_MS = 3000;

export const ANNO_COLORS = ['yellow', 'green', 'blue', 'pink'];

// ── 存储面（WS reader.anno 整件 + localStorage 镜像降级） ───────────────────

const MIRROR_KEY = 'nf.reader.annotations.mirror';
let cache = null;           // { v:1, annotations: [...] }
let loadPromise = null;
let flushTimer = null;
let dirty = false;
let reqSeq = 0;
/** @type {Map<string, {resolve: Function, reject: Function}>} */
const pending = new Map();

onMessage('readerAnno', (msg) => {
  const entry = msg && msg.reqId ? pending.get(msg.reqId) : null;
  if (!entry) return;
  pending.delete(msg.reqId);
  if (msg.ok) entry.resolve(msg.content);
  else entry.reject(new Error(msg.error || 'reader.anno failed'));
});

/** 空库 / 解析失败统一形态（损坏件容错：空库起步，不阻塞阅读）。 */
function parseStore(content) {
  try {
    const parsed = typeof content === 'string' ? JSON.parse(content) : content;
    if (parsed && Array.isArray(parsed.annotations)) {
      return { v: 1, annotations: parsed.annotations.filter((a) => a && a.id && a.bookRef) };
    }
  } catch { /* 损坏/空件 = 空库 */ }
  return { v: 1, annotations: [] };
}

function activeSessionId() {
  return state.activeSessionId || undefined;
}

/** 登记待应答请求（带清账哨兵：无应答不留孤儿条目）。 */
function trackPending(reqId, entry, cleanupMs) {
  pending.set(reqId, entry);
  setTimeout(() => {
    const still = pending.get(reqId);
    if (still) { pending.delete(reqId); still.reject(new Error('reader.anno timeout')); }
  }, cleanupMs);
}

/** 载库（幂等去重；WS 无应答 → localStorage 镜像起步）。 */
export function loadAnnotationStore() {
  if (cache) return Promise.resolve(cache);
  if (loadPromise) return loadPromise;
  loadPromise = new Promise((resolve) => {
    const reqId = 'ra-' + (++reqSeq);
    trackPending(reqId, {
      resolve: (content) => { cache = parseStore(content); resolve(cache); },
      reject: () => { cache = parseStore(localStorage.getItem(MIRROR_KEY)); resolve(cache); },
    }, LOAD_FALLBACK_MS);
    try { sendWs({ type: 'reader.anno', action: 'load', sessionId: activeSessionId(), reqId }); }
    catch { pending.get(reqId).reject(); pending.delete(reqId); }
  });
  return loadPromise;
}

function scheduleFlush() {
  if (flushTimer) clearTimeout(flushTimer);
  flushTimer = setTimeout(flushNow, FLUSH_MS);
}

function flushNow() {
  if (flushTimer) { clearTimeout(flushTimer); flushTimer = null; }
  if (!cache || !dirty) return;
  dirty = false;
  const content = JSON.stringify(cache);
  // 镜像恒写（WS 成败都写——降级腿与主腿同数据）。
  try { localStorage.setItem(MIRROR_KEY, content); } catch { /* 存储不可用时仅主腿 */ }
  const reqId = 'ra-' + (++reqSeq);
  trackPending(reqId, {
    resolve: () => { },
    reject: () => { dirty = true; }, // 写失败保留脏标：下次变更重试。
  }, 10000);
  try { sendWs({ type: 'reader.anno', action: 'save', sessionId: activeSessionId(), reqId, content }); }
  catch { pending.delete(reqId); dirty = true; }
}

function commit() {
  dirty = true;
  scheduleFlush();
  try {
    document.dispatchEvent(new CustomEvent('nf-annotations-changed', {
      detail: { annotations: cache.annotations },
    }));
  } catch { /* 事件面容错 */ }
}

export function annotationsForBook(bookRef) {
  const all = cache && Array.isArray(cache.annotations) ? cache.annotations : [];
  return all.filter((a) => a.bookRef === bookRef);
}

/** @returns {object} 入库后的标注（含时间戳回填）。 */
export function upsertAnnotation(anno) {
  if (!cache) return anno;
  const now = Date.now();
  const existing = cache.annotations.findIndex((a) => a.id === anno.id);
  const next = { ...anno, updatedAt: now };
  if (existing >= 0) cache.annotations[existing] = next;
  else cache.annotations.push({ ...next, createdAt: anno.createdAt || now });
  commit();
  return next;
}

export function removeAnnotation(id) {
  if (!cache) return;
  const idx = cache.annotations.findIndex((a) => a.id === id);
  if (idx < 0) return;
  cache.annotations.splice(idx, 1);
  commit();
}

// ── 定位（纯函数，导出供 spec 钉扎） ─────────────────────────────────────────

/** quote 主键 + offset 模糊重定位（设计稿 §4.2 鲁棒性三级）。
 *  @returns {{idx: number, exact: boolean}} idx=-1 = 双失配。 */
export function locateQuote(text, quote, offset) {
  if (!text || !quote) return { idx: -1, exact: false };
  let first = text.indexOf(quote);
  if (first >= 0) {
    // 主键命中：多处同名取离 offset 最近者。
    let best = first;
    let bestDist = Math.abs(first - (offset || 0));
    let at = first;
    while ((at = text.indexOf(quote, at + 1)) >= 0) {
      const d = Math.abs(at - (offset || 0));
      if (d < bestDist) { bestDist = d; best = at; }
    }
    return { idx: best, exact: true };
  }
  // 兜底：offset ±FUZZY_WINDOW 窗内最长公共前缀模糊找回。
  const from = Math.max(0, (offset || 0) - FUZZY_WINDOW);
  const to = Math.min(text.length, (offset || 0) + FUZZY_WINDOW);
  let bestIdx = -1;
  let bestLen = FUZZY_MIN_PREFIX - 1;
  for (let i = from; i < to; i++) {
    let len = 0;
    while (len < quote.length && i + len < text.length && text.charCodeAt(i + len) === quote.charCodeAt(len)) len++;
    if (len > bestLen) { bestLen = len; bestIdx = i; }
  }
  return bestIdx >= 0 ? { idx: bestIdx, exact: false } : { idx: -1, exact: false };
}

// ── DOM 侧：字偏移索引 / 高亮包裹 ───────────────────────────────────────────

function buildTextIndex(root) {
  /** @type {{node: Text, start: number, len: number}[]} */
  const nodes = [];
  let total = 0;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let node;
  while ((node = walker.nextNode())) {
    const len = node.nodeValue.length;
    if (len > 0) { nodes.push({ node, start: total, len }); total += len; }
  }
  return { nodes, total };
}

function unwrapMarks(root) {
  for (const mark of Array.from(root.querySelectorAll('mark.nf-anno'))) {
    mark.replaceWith(...Array.from(mark.childNodes));
  }
  // 包裹拆散文本节点后归并不必要碎片（同父相邻文本节点）。
  root.normalize();
}

/** 章内字偏移区间 → mark 包裹（逐段 surroundContents；每笔记后重建索引
 *  防前序包裹拆散节点）。 */
function applyMark(indexRoot, start, end, anno, stale) {
  let index = buildTextIndex(indexRoot);
  end = Math.min(end, index.total);
  if (start < 0 || end <= start) return;
  const spans = index.nodes.filter((o) => o.start < end && o.start + o.len > start);
  for (const o of spans) {
    const s = Math.max(start, o.start) - o.start;
    const e = Math.min(end, o.start + o.len) - o.start;
    if (e <= s) continue;
    const range = document.createRange();
    try {
      range.setStart(o.node, s);
      range.setEnd(o.node, e);
    } catch { continue; }
    const mark = document.createElement('mark');
    mark.className = 'nf-anno';
    mark.dataset.annoId = anno.id;
    mark.dataset.annoColor = anno.color || 'yellow';
    if (stale) mark.dataset.annoStale = '1';
    try { range.surroundContents(mark); } catch { /* 节点竞态：该段跳过 */ }
    index = buildTextIndex(indexRoot);
  }
}

/** 选区 → 章内字偏移（用现成索引；mark 不增文本，索引在包裹后仍有效）。 */
function selectionOffset(index, range) {
  const node = range.startContainer;
  const at = range.startOffset;
  if (node.nodeType === 3) {
    const hit = index.nodes.find((o) => o.node === node);
    if (hit) return hit.start + at;
  }
  return -1;
}

// ── 阅读 chrome 内的标注面挂载 ──────────────────────────────────────────────

/**
 * 在统一阅读 chrome 上挂标注面（readerChrome 在 adapter.annotatable 存在时调用）。
 * @param {object} deps
 * @param {HTMLElement} deps.pane canvas tab pane
 * @param {HTMLElement} deps.reader .epub-reader 根
 * @param {HTMLElement} deps.scroller 真正滚动的盒子
 * @param {() => HTMLElement} deps.getContentEl 当前章节内容盒
 * @param {{format: string, bookRef: string, chapterText: (i:number)=>string}} deps.annotatable
 * @param {(i:number)=>string} deps.chapterTitle
 * @param {(i:number)=>void} deps.jumpToChapter 跳章（落位由本面接管）
 * @param {(open: boolean)=>void} deps.setPanelGate 面板开闭 ⇒ fab 让位联动
 * @returns {{renderChapter: (i:number)=>void, count: ()=>number, openDrawer: ()=>void,
 *           closeDrawer: ()=>void, drawerOpen: ()=>boolean, destroy: ()=>void}}
 */
export function mountAnnotationFace(deps) {
  const { pane, reader, scroller, getContentEl, annotatable, chapterTitle, jumpToChapter, setPanelGate } = deps;
  const bookRef = annotatable.bookRef;
  /** 本书标注（缓存订阅快照；变更事件后刷新）。 */
  let bookAnnos = [];
  /** 抽屉/编辑器的未决回跳标注。 */
  let pendingJumpId = null;
  /** 当前章未落位的标注 id（抽屉灰显徽记用）。 */
  let unresolved = new Set();
  /** 当前章靠 offset 模糊找回的标注 id（quote 失配但落位——同徽记）。 */
  let fuzzyHits = new Set();

  loadAnnotationStore().then(() => refreshFromStore());
  const onStoreChanged = () => refreshFromStore();
  document.addEventListener('nf-annotations-changed', onStoreChanged);

  function refreshFromStore() {
    bookAnnos = annotationsForBook(bookRef);
    renderChapter(currentChapter);
    if (drawer && !drawer.hidden) renderDrawer();
  }

  // ── 浮动条（划选唤出：四色 + 写笔记；命中既有高亮：编辑/删除） ──────────
  const bar = document.createElement('div');
  bar.className = 'epub-anno-bar';
  bar.hidden = true;
  reader.appendChild(bar);
  let barForAnno = null; // null = 新选区；否则 = 既有标注

  function hideBar() { bar.hidden = true; barForAnno = null; }

  function renderBar() {
    const colors = ANNO_COLORS.map((c) =>
      `<button type="button" class="epub-anno-swatch" data-anno-color="${c}"
        title="${escapeHtml(t('reader.anno.' + c))}" aria-label="${escapeHtml(t('reader.anno.' + c))}"></button>`
    ).join('');
    const noteBtn = `<button type="button" class="epub-menu-btn" data-anno-note>✎ ${escapeHtml(t('reader.anno.note'))}</button>`;
    const delBtn = barForAnno
      ? `<button type="button" class="epub-menu-btn" data-anno-del>${escapeHtml(t('reader.anno.del'))}</button>` : '';
    bar.innerHTML = barForAnno
      ? `${noteBtn}${delBtn}<button type="button" class="epub-menu-btn" data-anno-bar-close>×</button>`
      : `${colors}${noteBtn}`;
    bar.querySelectorAll('[data-anno-color]').forEach((btn) => {
      btn.style.background = `var(--anno-${btn.dataset.annoColor})`;
      btn.addEventListener('click', () => createFromSelection(btn.dataset.annoColor, false));
    });
    const note = bar.querySelector('[data-anno-note]');
    if (note) note.addEventListener('click', () => {
      if (barForAnno) openNoteEditor(barForAnno);
      else createFromSelection('yellow', true);
    });
    const del = bar.querySelector('[data-anno-del]');
    if (del) del.addEventListener('click', () => {
      if (barForAnno) { removeAnnotation(barForAnno.id); hideBar(); }
    });
    const close = bar.querySelector('[data-anno-bar-close]');
    if (close) close.addEventListener('click', hideBar);
  }

  function showBarAt(rect) {
    renderBar();
    bar.hidden = false;
    const readerRect = reader.getBoundingClientRect();
    const bw = bar.offsetWidth || 200;
    const bh = bar.offsetHeight || 40;
    let left = rect.left + rect.width / 2 - bw / 2;
    left = Math.max(readerRect.left + 8, Math.min(left, readerRect.right - bw - 8));
    let top = rect.top - bh - 8;
    if (top < readerRect.top + 8) top = rect.bottom + 8; // 选区贴顶：落下方
    bar.style.left = (left - readerRect.left) + 'px';
    bar.style.top = (top - readerRect.top) + 'px';
  }

  // ── 选区采集 ──────────────────────────────────────────────────────────────
  function selectionInContent() {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
    const text = sel.toString();
    if (!text || !text.trim()) return null;
    const range = sel.getRangeAt(0);
    const contentEl = getContentEl();
    if (!contentEl || !contentEl.contains(range.startContainer) || !contentEl.contains(range.endContainer)) return null;
    return { sel, range, text };
  }

  function onSelectEnd() {
    // rAF 后读选区：mouseup 先派、选区后落定。
    requestAnimationFrame(() => {
      if (suppressSelectEnd) { suppressSelectEnd = false; return; } // 高亮点击同 tick：保编辑条
      const got = selectionInContent();
      if (!got) { hideBar(); return; }
      const mark = got.range.startContainer.nodeType === 3
        ? got.range.startContainer.parentElement && got.range.startContainer.parentElement.closest('mark.nf-anno')
        : got.range.startContainer.closest && got.range.startContainer.closest('mark.nf-anno');
      const existing = mark ? bookAnnos.find((a) => a.id === mark.dataset.annoId) : null;
      barForAnno = existing || null;
      showBarAt(got.range.getBoundingClientRect());
    });
  }
  scroller.addEventListener('mouseup', onSelectEnd);
  scroller.addEventListener('touchend', onSelectEnd, { passive: true });

  function createFromSelection(color, openEditor) {
    const got = selectionInContent();
    if (!got) { hideBar(); return; }
    const contentEl = getContentEl();
    const index = buildTextIndex(contentEl);
    const offset = selectionOffset(index, got.range);
    if (offset < 0) { hideBar(); return; }
    const anno = upsertAnnotation({
      id: 'a' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
      bookRef,
      format: annotatable.format,
      spine: { chapterIdx: currentChapter },
      locator: { quote: got.text.slice(0, QUOTE_LEN), offset },
      color,
      note: '',
    });
    got.sel.removeAllRanges();
    hideBar();
    renderChapter(currentChapter);
    if (openEditor) openNoteEditor(anno);
  }

  // ── 笔记编辑器（毛玻璃小面板，锚阅读器顶部——与阅读菜单同材质同位） ────────
  const editor = document.createElement('div');
  editor.className = 'epub-reader-menu epub-anno-note';
  editor.hidden = true;
  reader.appendChild(editor);
  let editorAnno = null;

  function openNoteEditor(anno) {
    editorAnno = anno;
    editor.innerHTML = `
      <div class="epub-menu-title">${escapeHtml(anno.locator.quote.slice(0, 24))}</div>
      <textarea class="epub-anno-textarea" rows="4" placeholder="${escapeHtml(t('reader.anno.placeholder'))}"></textarea>
      <div class="epub-menu-row">
        <button type="button" class="epub-menu-btn" data-anno-save>${escapeHtml(t('reader.anno.save'))}</button>
        <button type="button" class="epub-menu-btn" data-anno-edel>${escapeHtml(t('reader.anno.del'))}</button>
        <button type="button" class="epub-menu-btn" data-anno-eclose>${escapeHtml(t('reader.anno.close'))}</button>
      </div>`;
    const ta = editor.querySelector('.epub-anno-textarea');
    ta.value = anno.note || '';
    editor.querySelector('[data-anno-save]').addEventListener('click', () => {
      if (editorAnno) upsertAnnotation({ ...editorAnno, note: ta.value.trim() });
      closeNoteEditor();
    });
    editor.querySelector('[data-anno-edel]').addEventListener('click', () => {
      if (editorAnno) removeAnnotation(editorAnno.id);
      closeNoteEditor();
    });
    editor.querySelector('[data-anno-eclose]').addEventListener('click', closeNoteEditor);
    editor.hidden = false;
    hideBar();
    setPanelGate(true);
    ta.focus();
  }
  function closeNoteEditor() {
    if (editor.hidden) return;
    editor.hidden = true;
    editorAnno = null;
    if (drawer.hidden) setPanelGate(false);
  }

  // ── 笔记抽屉（按章分组，点击回跳定位——TOC 抽屉同族玻璃件） ───────────────
  const drawer = document.createElement('div');
  drawer.className = 'epub-toc epub-anno-list';
  drawer.hidden = true;
  reader.appendChild(drawer);

  const drawerOpen = () => !drawer.hidden;

  function renderDrawer() {
    const byChapter = new Map();
    for (const a of bookAnnos) {
      const key = a.spine && typeof a.spine.chapterIdx === 'number' ? a.spine.chapterIdx : 0;
      if (!byChapter.has(key)) byChapter.set(key, []);
      byChapter.get(key).push(a);
    }
    const groups = [...byChapter.keys()].sort((x, y) => x - y);
    const body = groups.map((ci) => {
      const items = byChapter.get(ci)
        .sort((a, b) => (a.locator.offset || 0) - (b.locator.offset || 0))
        .map((a) => {
          const stale = unresolved.has(a.id) || fuzzyHits.has(a.id);
          const snippet = (a.locator.quote || '').replace(/\s+/g, ' ').slice(0, 18);
          const noteDot = a.note ? `<span class="epub-anno-item-note">${escapeHtml(a.note.replace(/\s+/g, ' ').slice(0, 18))}</span>` : '';
          const staleBadge = stale ? `<span class="epub-anno-stale">${escapeHtml(t('reader.anno.stale'))}</span>` : '';
          return `<button type="button" role="option" class="epub-toc-item epub-anno-item" data-anno-jump="${a.id}">
            <i class="epub-anno-dot" data-c="${a.color}"></i>
            <span class="epub-anno-item-quote">${escapeHtml(snippet)}</span>${noteDot}${staleBadge}</button>`;
        }).join('');
      return `<div class="epub-anno-group-label">${escapeHtml(chapterTitle(ci) || String(ci + 1))}</div>${items}`;
    }).join('');
    drawer.querySelector('.epub-anno-list-body').innerHTML = body || '';
    drawer.querySelectorAll('[data-anno-jump]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const anno = bookAnnos.find((a) => a.id === btn.dataset.annoJump);
        if (!anno) return;
        closeDrawer();
        pendingJumpId = anno.id;
        jumpToChapter(anno.spine && typeof anno.spine.chapterIdx === 'number' ? anno.spine.chapterIdx : 0);
      });
    });
  }

  function openDrawer() {
    drawer.innerHTML = `
      <div class="epub-toc-head">
        <span class="epub-toc-title">${escapeHtml(t('reader.anno.list'))}（${bookAnnos.length}）</span>
        <button type="button" class="epub-nav-btn epub-toc-close" aria-label="${escapeHtml(t('epub.menu.close'))}">×</button>
      </div>
      <div class="epub-anno-list-body epub-toc-list" role="listbox"></div>`;
    drawer.querySelector('.epub-toc-close').addEventListener('click', closeDrawer);
    renderDrawer();
    drawer.hidden = false;
    setPanelGate(true);
  }
  function closeDrawer() {
    if (drawer.hidden) return;
    drawer.hidden = true;
    if (editor.hidden) setPanelGate(false);
  }

  // ── 章内回渲（chrome 每次装载后调用） ─────────────────────────────────────
  let currentChapter = 0;
  function renderChapter(i) {
    currentChapter = i;
    const contentEl = getContentEl();
    if (!contentEl) return;
    unwrapMarks(contentEl);
    unresolved = new Set();
    fuzzyHits = new Set();
    const text = annotatable.chapterText(i) || contentEl.textContent || '';
    for (const anno of bookAnnos) {
      const ci = anno.spine && typeof anno.spine.chapterIdx === 'number' ? anno.spine.chapterIdx : 0;
      if (ci !== i) continue;
      const located = locateQuote(text, anno.locator.quote, anno.locator.offset);
      if (located.idx < 0) { unresolved.add(anno.id); continue; }
      if (!located.exact) fuzzyHits.add(anno.id);
      const span = Math.max(1, Math.min((anno.locator.quote || '').length, text.length - located.idx));
      applyMark(contentEl, located.idx, located.idx + span, anno, !located.exact);
      if (pendingJumpId === anno.id) revealAnno(anno);
    }
  }

  function revealAnno(anno) {
    pendingJumpId = null;
    requestAnimationFrame(() => {
      const mark = getContentEl().querySelector(`mark.nf-anno[data-anno-id="${CSS.escape(anno.id)}"]`);
      if (!mark) return;
      mark.scrollIntoView({ block: 'center' });
      mark.classList.remove('nf-jump-flash');
      void mark.offsetWidth;
      mark.classList.add('nf-jump-flash');
      mark.addEventListener('animationend', () => mark.classList.remove('nf-jump-flash'), { once: true });
    });
  }

  // 正文内点既有高亮 → 出编辑条（chrome 点按分区的守卫在 readerChrome 侧）。
  let suppressSelectEnd = false;
  function onContentClick(e) {
    const mark = e.target && e.target.closest ? e.target.closest('mark.nf-anno') : null;
    if (!mark) return;
    const anno = bookAnnos.find((a) => a.id === mark.dataset.annoId);
    if (!anno) return;
    e.stopPropagation();
    suppressSelectEnd = true;
    barForAnno = anno;
    showBarAt(mark.getBoundingClientRect());
  }
  scroller.addEventListener('click', onContentClick);

  function count() { return bookAnnos.length; }

  function destroy() {
    document.removeEventListener('nf-annotations-changed', onStoreChanged);
  }

  return {
    renderChapter, count, openDrawer, closeDrawer, drawerOpen,
    closeNoteEditor, noteEditorOpen: () => !editor.hidden, destroy,
  };
}
