// reference.js — Global Reference 统一引用模型（规格 20260825_global-reference-spec v1.1）
//
// 所有入口（文件浏览器右键 / 拖拽到输入框 / Canvas 选择 / 任务栏打回 / 标签页拖拽）
// 最后都调用 makeReference() 产出统一的 Reference 对象（§2.1），渲染统一走
// renderRefBlock(ref, {mode})。不允许多个入口各自拼不同结构（§2.4 统一性保障点）。
//
// Reference（JS 态，存入 pendingAttachments）：
//   { type:'ref', refType:'file'|'document'|'task'|'html-element', id,
//     source:{kind,path?,url?,fileName?,title?,taskId?,sessionId?},
//     anchor:{kind,pageStart?,pageEnd?,lineStart?,lineEnd?,sheet?,cellRange?,text?,selector?},
//     meta:{mimeType?,sizeBytes?,icon,typeLabel}, display:{label,preview,pageBadge?,lineBadge?} }
//
// P0 范围（v1.1）：输入框引用块定高/截断/可展开（A1-A6）+ 各入口产出引用（B 组）+ 拉到根目录（E 组）。
// 消息内引用卡片（mode='message'）+ 点击跳转 = P1/P2，本模块预留 renderRefBlock mode 分支。
import { t } from './i18n.js';

/** Stable-ish ref id (内部用，非用户可见) — refType + monotonic counter. */
let _refSeq = 0;
function nextRefId(refType) {
  _refSeq += 1;
  return `ref:${refType}:${Date.now().toString(36)}:${_refSeq}`;
}

function extLabel(name, mime) {
  const ext = ((name || '').split('.').pop() || '').toLowerCase();
  if (ext === 'pdf') return 'PDF';
  if (mime === 'application/pdf') return 'PDF';
  if (['md', 'markdown'].includes(ext)) return 'MD';
  if (['xlsx', 'xls', 'csv'].includes(ext)) return 'XLS';
  if (['doc', 'docx'].includes(ext)) return 'DOC';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) return 'IMG';
  if (ext) return ext.toUpperCase();
  if (mime) return (mime.split('/').pop() || '').toUpperCase();
  return 'FILE';
}

function fileIcon(name, mime) {
  const ext = ((name || '').split('.').pop() || '').toLowerCase();
  if (['png','jpg','jpeg','gif','webp'].includes(ext)) return 'image';
  if (['pdf'].includes(ext) || mime === 'application/pdf') return 'file-text';
  if (['xlsx','xls','csv'].includes(ext)) return 'table';
  if (['md','txt','markdown'].includes(ext)) return 'file-text';
  return 'file';
}

function previewOf(name, text, max) {
  const s = (text || '').trim();
  if (!s) return '';
  const n = max || 160;
  return s.length > n ? s.slice(0, n) + '…' : s;
}

function anchorOf(anchor) {
  if (!anchor) return { kind: 'none' };
  // Fold any range-only anchor to a definite kind; keep provided fields.
  const a = { kind: 'none', ...anchor };
  if (a.pageStart != null) a.kind = 'page';
  else if (a.lineStart != null) a.kind = 'range';
  else if (a.sheet) a.kind = 'cell';
  else if (a.selector) a.kind = 'element';
  return a;
}

function pageBadge(anchor) {
  if (!anchor) return '';
  if (anchor.kind === 'page' && anchor.pageStart != null) {
    return anchor.pageEnd != null && anchor.pageEnd !== anchor.pageStart
      ? `p.${anchor.pageStart}–${anchor.pageEnd}` : `p.${anchor.pageStart}`;
  }
  if (anchor.kind === 'range' && anchor.lineStart != null) {
    return anchor.lineEnd != null && anchor.lineEnd !== anchor.lineStart
      ? `L${anchor.lineStart}–${anchor.lineEnd}` : `L${anchor.lineStart}`;
  }
  if (anchor.kind === 'cell' && anchor.sheet) {
    return `${anchor.sheet}!${anchor.cellRange || ''}`;
  }
  if (anchor.kind === 'element') return anchor.tag ? `<${anchor.tag}>` : '';
  return '';
}

/**
 * 统一工厂（§2.4）：input 提供 refType + 该类型的源信息/锚点，补全成一个
 * 结构完整的 Reference。refType 判别产出；未知 refType 返回 null（调用方
 * 应兜底为普通文本/附件）。
 * @param {Object} input
 * @returns {Object|null}
 */
export function makeReference(input) {
  if (!input || !input.refType) return null;
  const rt = input.refType;
  const src = input.source || {};
  const anchor = anchorOf(input.anchor);
  const name = src.fileName || src.title || (src.path ? src.path.split('/').pop() : '') || '';

  if (rt === 'task') {
    const label = `@#${src.taskId != null ? src.taskId : ''} ${src.title || ''}`.trim();
    return {
      type: 'ref', refType: 'task', id: nextRefId('task'),
      source: { kind: src.kind || 'task', taskId: src.taskId, sessionId: src.sessionId, title: src.title || '' },
      anchor,
      meta: { icon: 'clipboard', typeLabel: t('ref.typeTask') },
      display: { label, preview: '', pageBadge: '' },
    };
  }

  if (rt === 'file') {
    const mime = src.mimeType || '';
    return {
      type: 'ref', refType: 'file', id: nextRefId('file'),
      source: { kind: src.kind || 'workspace', path: src.path || '', fileName: name, title: src.title || name, mimeType: mime },
      anchor,
      meta: { mimeType: mime, sizeBytes: src.sizeBytes, icon: fileIcon(name, mime), typeLabel: extLabel(name, mime) },
      display: { label: name, preview: previewOf(name, src.preview || src.text, 160), lineBadge: pageBadge(anchor) },
    };
  }

  if (rt === 'document') {
    const mime = src.mimeType || '';
    return {
      type: 'ref', refType: 'document', id: nextRefId('document'),
      source: { kind: src.kind || 'canvas', path: src.path || '', fileName: name, title: src.title || name, mimeType: mime },
      anchor,
      meta: { mimeType: mime, sizeBytes: src.sizeBytes, icon: fileIcon(name, mime), typeLabel: extLabel(name, mime) },
      display: { label: name, preview: previewOf(name, src.preview || anchor.text, 160), pageBadge: pageBadge(anchor), lineBadge: pageBadge(anchor) },
    };
  }

  if (rt === 'html-element') {
    return {
      type: 'ref', refType: 'html-element', id: nextRefId('html-element'),
      source: { kind: src.kind || 'web', url: src.url || '', title: src.title || '', path: src.path || '' },
      anchor,
      meta: { icon: 'code', typeLabel: t('ref.typeElement') },
      display: { label: src.title || '', preview: previewOf(name, anchor.text || src.preview, 160), pageBadge: pageBadge(anchor) },
    };
  }

  // #290 A2A (addendum §3.2): friend message forward - content-bearing ref
  // (the message body rides the payload as content.fullText, unlike the
  // pointer refs above). Stable id pinned to the source message.
  if (rt === 'friend-message') {
    const content = input.content || {};
    const fullText = String(content.fullText || '').slice(0, 4000);
    const preview = previewOf('', content.preview || fullText, 160);
    const friendName = src.friendName || src.friendNeblinkId || '';
    const date = src.date || '';
    return {
      type: 'ref', refType: 'friend-message',
      id: src.messageId ? `ref:fm:${src.messageId}` : nextRefId('fm'),
      source: {
        kind: src.kind || 'friend-message',
        conversationId: src.conversationId || '',
        messageId: src.messageId || '',
        friendName,
        friendNeblinkId: src.friendNeblinkId || '',
        direction: src.direction === 'out' ? 'out' : 'in',
      },
      anchor,
      content: { preview, fullText },
      meta: { icon: 'message-circle', typeLabel: t('ref.typeFriendMessage'), date },
      display: { label: t('ref.fromFriend', { name: friendName }), preview, pageBadge: date },
    };
  }

  return null;
}

/** Legacy taskRef → normalized Reference (向后兼容，§2.4 不推翻 taskRef). */
export function normalizeTaskRef(att) {
  return makeReference({
    refType: 'task',
    source: { kind: 'task', taskId: att.taskId, sessionId: att.sessionId, title: att.subject || '' },
  });
}

/** Short at-mention text for aria/display (§2.3 ④). */
export function refMentionText(ref) {
  if (!ref) return '';
  if (ref.refType === 'task') return ref.display?.label || `@#${ref.source?.taskId ?? ''}`;
  if (ref.refType === 'file') return `@${ref.source?.path || ref.source?.fileName || ''}`;
  if (ref.refType === 'document') {
    const p = ref.anchor?.kind === 'page' && ref.anchor.pageStart != null
      ? `:p${ref.anchor.pageStart}${ref.anchor.pageEnd && ref.anchor.pageEnd !== ref.anchor.pageStart ? `-${ref.anchor.pageEnd}` : ''}` : '';
    return `@${ref.source?.path || ''}${p}`;
  }
  if (ref.refType === 'html-element') return `@${ref.source?.url || ''}#${ref.anchor?.selector || ''}`;
  if (ref.refType === 'friend-message') return `@${ref.display?.label || ref.source?.friendName || ''}`;
  return '';
}

// ── renderRefBlock ──────────────────────────────────────────────────────
//
// mode='input': 输入框引用块（§3.5）——定高 36px 单行截断、⤢ 展开 ≤72px 可滚动、
//   ✕ 本地删除。A1-A6 断言基准。
// mode='message': 消息内引用块（§4）——块级卡片（P1/P2 消息流使用）。

const EXPAND_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>';
const COLLAPSE_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>';
const ICONS = {
  file: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>',
  clipboard: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/></svg>',
  code: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',
  table: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="3" y1="15" x2="21" y2="15"/><line x1="12" y1="3" x2="12" y2="21"/></svg>',
  'message-circle': '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
};
function refIcon(ref) {
  return ICONS[ref.meta?.icon] || ICONS.file;
}

/** Truncate a preview to N chars (意见预览只取输入框首行 + 截断在 ~180). */
function truncate(str, n) {
  const s = String(str || '');
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/**
 * Build the reference block element.
 * @param {Object} ref  unified Reference
 * @param {{mode?:'input'|'message'}} [opts]
 * @param {(ref:Object)=>void} [onRemove]  input-mode local remove callback
 * @returns {HTMLElement}
 */
export function renderRefBlock(ref, { mode = 'input' } = {}, onRemove) {
  if (mode === 'message') return renderMessageRef(ref);
  return renderInputRef(ref, onRemove);
}

// #290 A2A (addendum §3.4): friend-message input block - etched surface,
// header 来自 {好友名} + date corner badge, body 2-line clamp, expand = full
// text (content.fullText). Fixed footprint, zero new color tokens.
function renderFriendInputRef(ref, onRemove) {
  const wrap = document.createElement('div');
  wrap.className = 'att-ref att-ref-fm';
  wrap.dataset.refType = 'friend-message';
  wrap.dataset.refId = ref.id || '';
  const label = ref.display?.label || '';
  wrap.setAttribute('role', 'group');
  wrap.setAttribute('aria-label', `${t('ref.cardAria')}: ${label}`.trim());
  wrap.title = label;

  const icon = document.createElement('span');
  icon.className = 'att-ref-icon';
  icon.innerHTML = refIcon(ref);
  icon.setAttribute('aria-hidden', 'true');

  const body = document.createElement('span');
  body.className = 'att-ref-fm-body';
  const head = document.createElement('span');
  head.className = 'att-ref-fm-head';
  const nameEl = document.createElement('span');
  nameEl.className = 'att-ref-fm-name';
  nameEl.textContent = label;
  head.appendChild(nameEl);
  const date = ref.meta?.date || ref.display?.pageBadge || '';
  if (date) {
    const dateEl = document.createElement('span');
    dateEl.className = 'att-ref-fm-date';
    dateEl.textContent = date;
    head.appendChild(dateEl);
  }
  const text = document.createElement('span');
  text.className = 'att-ref-fm-text';
  text.textContent = ref.content?.preview || ref.display?.preview || '';
  body.append(head, text);

  const expand = document.createElement('button');
  expand.type = 'button';
  expand.className = 'att-ref-expand';
  expand.title = t('ref.expand');
  expand.setAttribute('aria-expanded', 'false');
  expand.innerHTML = EXPAND_SVG;
  const fullText = ref.content?.fullText || '';
  const toggle = (e) => {
    if (e && e.stopPropagation) e.stopPropagation();
    const expanded = wrap.classList.toggle('expanded');
    expand.setAttribute('aria-expanded', String(expanded));
    expand.title = expanded ? t('ref.collapse') : t('ref.expand');
    expand.innerHTML = expanded ? COLLAPSE_SVG : EXPAND_SVG;
    text.textContent = expanded ? fullText : (ref.content?.preview || '');
  };
  expand.addEventListener('click', toggle);
  wrap.addEventListener('click', toggle);

  const rm = document.createElement('button');
  rm.type = 'button';
  rm.className = 'att-ref-remove';
  rm.textContent = '×';
  rm.title = t('ref.remove');
  rm.setAttribute('aria-label', t('ref.remove'));
  rm.addEventListener('click', (e) => { e.stopPropagation(); onRemove?.(ref); });

  wrap.append(icon, body, expand, rm);
  return wrap;
}

function renderInputRef(ref, onRemove) {
  if (ref.refType === 'friend-message') return renderFriendInputRef(ref, onRemove);
  const wrap = document.createElement('div');
  wrap.className = 'att-ref';
  wrap.dataset.refType = ref.refType || '';
  wrap.dataset.refId = ref.id || '';
  const mention = refMentionText(ref);
  const label = ref.display?.label || mention || ref.source?.title || '';
  wrap.setAttribute('role', 'group');
  wrap.setAttribute('aria-label', `${t('ref.cardAria')}: ${label}`.trim());
  wrap.title = label;

  const icon = document.createElement('span');
  icon.className = 'att-ref-icon';
  icon.innerHTML = refIcon(ref);
  icon.setAttribute('aria-hidden', 'true');

  const body = document.createElement('span');
  body.className = 'att-ref-body';
  const labelEl = document.createElement('span');
  labelEl.className = 'att-ref-label';
  labelEl.textContent = label;
  const opinionEl = document.createElement('span');
  opinionEl.className = 'att-ref-opinion';
  const metaEl = document.createElement('span');
  metaEl.className = 'att-ref-meta';
  metaEl.textContent = [ref.display?.pageBadge, ref.meta?.typeLabel, normalizeSource(ref)].filter(Boolean).join(' · ');
  metaEl.setAttribute('aria-hidden', 'true');
  body.append(labelEl, opinionEl, metaEl);

  // ⤢ expand/collapse
  const expand = document.createElement('button');
  expand.type = 'button';
  expand.className = 'att-ref-expand';
  expand.title = t('ref.expand');
  expand.setAttribute('aria-expanded', 'false');
  expand.innerHTML = EXPAND_SVG;
  expand.setAttribute('aria-hidden', 'true');
  const toggle = (e) => {
    if (e && e.stopPropagation) e.stopPropagation();
    const expanded = wrap.classList.toggle('expanded');
    expand.setAttribute('aria-expanded', String(expanded));
    expand.title = expanded ? t('ref.collapse') : t('ref.expand');
    expand.innerHTML = expanded ? COLLAPSE_SVG : EXPAND_SVG;
  };
  expand.addEventListener('click', toggle);
  wrap.addEventListener('click', toggle);          // clicking the card head also toggles (A4)

  // ✕ remove (local-only, C16 draft semantics)
  const rm = document.createElement('button');
  rm.type = 'button';
  rm.className = 'att-ref-remove';
  rm.textContent = '×';
  rm.title = t('ref.remove');
  rm.setAttribute('aria-label', t('ref.remove'));
  rm.addEventListener('click', (e) => { e.stopPropagation(); onRemove?.(ref); });

  wrap.append(icon, body, expand, rm);

  // Live opinion preview (第一行 + 截断 ~180) — typed in the input textarea.
  // Textarea-scoped + self-cleaning (removes itself once the card is gone —
  // a re-render clearsinnerHTML so wrap.isConnected flips false).
  const inputEl = wrap.closest('.input-bar')?.querySelector('textarea')
    || document.querySelector('#input-bar textarea') || document.querySelector('textarea');
  const syncOpinion = () => {
    if (!wrap.isConnected) { inputEl?.removeEventListener('input', syncOpinion); return; }
    const first = (inputEl?.value || '').split('\n')[0].trim();
    opinionEl.textContent = first ? truncate(first, 180) : '';
    opinionEl.classList.toggle('has-opinion', !!first);
  };
  syncOpinion();
  inputEl?.addEventListener('input', syncOpinion);
  return wrap;
}

/** Normalized short source line for the input card meta (workspace path / url). */
function normalizeSource(ref) {
  if (ref.refType === 'task') return '';
  if (ref.refType === 'friend-message') return ref.source?.friendNeblinkId || '';
  if (ref.source?.url) return ref.source.url;
  const p = ref.source?.path || '';
  if (!p) return '';
  // Show the last two path segments for a compact来源 line.
  const segs = p.split('/').filter(Boolean);
  return segs.length > 2 ? '…/' + segs.slice(-2).join('/') : p;
}

/** Parse the backend return-injection text "[打回任务 #id: title]" (the only
 *  marker the return flow emits today). Non-matching text returns null and the
 *  caller falls through to normal markdown - zero behavior change elsewhere.
 *  NOTE sync point: the wording is owned by Task.returnInjectionBlock; if the
 *  payload wording changes this recognizer must follow (Manager 2026-08-27:
 *  frontend/backend changes are independent - worst case here is graceful
 *  fallback to the old full-text rendering, never broken UI).
 * @param {string} text
 * @returns {{taskId: string, title: string, opinion: string}|null}
 */
export function parseTaskReturnText(text) {
  const trimmed = String(text || '').trim();
  const lines = trimmed.split(/\r?\n/);
  const head = /^\[打回任务\s*#([^:\]]+):\s*([^\]]*)\]\s*$/.exec(lines[0] || '');
  if (!head) return null;
  let opinion = '';
  for (const l of lines.slice(1)) {
    if (/^用户意见/.test(l)) { opinion = l.replace(/^用户意见\s*[:：]\s*/, ''); break; }
  }
  if (opinion === '（未附意见）') opinion = '';
  return { taskId: head[1].trim(), title: (head[2] || '').trim(), opinion };
}

/** The converged one-line task-return node (2026-08-27 user ruling "只显任务名"):
 *  clipboard glyph + "#<id> <title>" (+ optional one-line-truncated opinion).
 *  Description/output NEVER render here regardless of what the payload hints.
 * @param {{taskId?: string|number|null, title?: string, opinion?: string}} p
 * @returns {HTMLElement}
 */
export function buildTaskRefLine(p) {
  const line = document.createElement('div');
  line.className = 'att-task-line';
  line.dataset.refType = 'task';
  const id = p.taskId != null ? String(p.taskId) : '';
  const title = String(p.title || '');
  const ariaLabel = (`#${id} ${title}`).trim();
  line.setAttribute('role', 'group');
  line.setAttribute('aria-label', ariaLabel);
  const icon = document.createElement('span');
  icon.className = 'att-task-line-icon';
  icon.innerHTML = ICONS.clipboard;
  icon.setAttribute('aria-hidden', 'true');
  const name = document.createElement('span');
  name.className = 'att-task-line-name';
  name.textContent = ariaLabel || `#${id}`;
  line.append(icon, name);
  const opinion = String(p.opinion || '').split('\n')[0].trim();
  if (opinion && opinion !== '（未附意见）') {
    const op = document.createElement('span');
    op.className = 'att-task-line-opinion';
    op.textContent = truncate(opinion, 80);
    line.appendChild(op);
  }
  return line;
}

function renderMessageRef(ref) {
  // Task references converge to the one-line form above (same shape as the
  // injected [打回任务] collapse); file/document/html-element keep the card.
  if (ref.refType === 'task') {
    return buildTaskRefLine({
      taskId: ref.source?.taskId ?? '',
      title: ref.source?.title || '',
      opinion: '',
    });
  }
  const card = document.createElement('div');
  card.className = 'att-ref-card';
  const label = ref.display?.label || ref.source?.title || '';
  const head = document.createElement('div');
  head.className = 'att-ref-card-head';
  const icon = document.createElement('span');
  icon.className = 'att-ref-card-icon';
  icon.innerHTML = refIcon(ref);
  head.appendChild(icon);
  const title = document.createElement('span');
  title.className = 'att-ref-card-title';
  title.textContent = label;
  head.appendChild(title);
  const badge = document.createElement('span');
  badge.className = 'att-ref-card-badge';
  badge.textContent = ref.display?.pageBadge || ref.meta?.typeLabel || '';
  head.appendChild(badge);
  card.appendChild(head);
  const src = document.createElement('div');
  src.className = 'att-ref-card-source';
  src.textContent = normalizeSource(ref);
  card.appendChild(src);
  const body = document.createElement('div');
  body.className = 'att-ref-card-content';
  body.textContent = ref.display?.preview || '';
  card.appendChild(body);
  card.dataset.refType = ref.refType || '';
  card.dataset.refPath = ref.source?.path || '';
  // #303 C2/C3: click the message card to jump to the referenced content
  // (open the file/URL, or reveal the task). Canvas is loaded lazily via a
  // dynamic import() — canvas.js statically imports reference.js, so a static
  // import here would create a reference→canvas→reference cycle.
  card.addEventListener('click', () => jumpRef(ref));
  return card;
}

// ── C2/C3 reference jump ─────────────────────────────────────────────────────
// Route by refType. File/document: open in Canvas (empty content + absPath →
// canvas fetches via readFile and picks the right viewer). html-element: open
// the source URL. task: referral records are back-references — no silent
// navigation, but let the task panel reveal it if it owns the id (future); for
// now this is a non-navigating record (the return already happened).
function jumpRef(ref) {
  if (!ref) return;
  const rt = ref.refType;
  const src = ref.source || {};
  const path = src.path || '';
  if (rt === 'file' || rt === 'document') {
    if (!path) return;
    const title = ref.display?.label || src.title || path.split('/').pop();
    // #303 C3: carry the page anchor through to the pdf viewer (canvas.js
    // stashes it across the readFile round trip; pdf.js scrolls after render).
    const pg = ref.anchor && ref.anchor.pageStart;
    const anchor = Number.isFinite(pg) ? { pageStart: pg } : undefined;
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: `file:${path}`, title, itemType: '', content: '', absPath: path, path, pinned: false, anchor },
    }));
    return;
  }
  if (rt === 'html-element') {
    // #303 B6: srcdoc previews have no usable URL (about:srcdoc) — open the
    // source file path instead; cross-origin URL pages jump by URL (C5).
    if (path) {
      const title = ref.display?.label || src.title || path.split('/').pop();
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: `file:${path}`, title, itemType: '', content: '', absPath: path, path, pinned: false },
      }));
      return;
    }
    if (src.url) {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: `url:${src.url}`, itemType: 'url', title: ref.display?.label || src.url, url: src.url, pinned: false },
      }));
    }
    return;
  }
  // task / friend-message / unknown → non-navigating record (the friend
  // message content already rides the payload - there is nothing to open).
}
