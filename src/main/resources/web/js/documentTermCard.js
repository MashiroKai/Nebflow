// Document-reference preview. The SQLite API is queried on every opening so a
// rebuild is visible without a page reload; no live-registry cache is involved.
import { authHeaders } from './flowHelpers.js';

// Canonical key: exact project + vault-relative path for registration-vault
// references, or exact absolute path for already-resolved local references.
// Never pick by basename alone; malformed, ambiguous and truncated results fail closed.
function documentIdentity(path, terms, complete) {
  if (!complete) return null;
  const value = String(path || '').replace(/\\/g, '/');
  const absolute = value.startsWith('/') || /^[A-Za-z]:\//.test(value);
  const parts = value.split('/');
  if (parts.some(p => p === '.' || p === '..')) return null;
  const relative = !absolute && parts.length >= 4 && parts[1] === '.stand' && parts[2] === 'docs';
  if (!absolute && !relative) return null;
  const hits = terms.filter(t => {
    if (t.source !== 'document_summary' || !t.doc) return false;
    if (absolute) return String(t.doc.abs_path || '').replace(/\\/g, '/') === value;
    return t.doc.project === parts[0] && t.doc.rel === parts.slice(3).join('/');
  });
  return hits.length === 1 ? hits[0] : null;
}

let active = null;
let generation = 0;
function closeCard(restore = true) {
  generation++;
  if (!active) return;
  const { card, trigger } = active;
  card.remove();
  active = null;
  if (restore && trigger.isConnected) trigger.focus();
}
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && active) { e.preventDefault(); closeCard(); }
});
document.addEventListener('pointerdown', e => {
  if (active && !active.card.contains(e.target) && !active.trigger.contains(e.target)) {
    const trigger = active.trigger;
    closeCard(false);
    setTimeout(() => { if (trigger.isConnected && !active) trigger.focus(); }, 0);
  }
}, true);
document.addEventListener('canvas-tab-switched', () => closeCard(false));

/** Preview an identified document; openDocument preserves its existing navigation. */
export async function showDocumentTermCard(trigger, { path, title, remote = false, openDocument }) {
  closeCard(false);
  const card = document.createElement('section');
  card.className = 'document-term-card';
  card.setAttribute('role', 'dialog');
  card.setAttribute('aria-label', '文档词条');
  const heading = document.createElement('strong');
  const summary = document.createElement('div');
  summary.className = 'document-term-summary';
  const metadata = document.createElement('div');
  metadata.className = 'document-term-meta';
  const pathEl = document.createElement('div');
  pathEl.className = 'document-term-path';
  const button = document.createElement('button');
  button.type = 'button'; button.className = 'document-term-open';
  button.setAttribute('aria-label', '打开原文档');
  button.textContent = '↗ 打开原文档';
  heading.textContent = title || path.split(/[\\/]/).pop() || '文档';
  summary.textContent = '此文档暂无提要';
  metadata.textContent = '状态：—　更新：—';
  pathEl.textContent = path;
  card.append(heading, summary, metadata, pathEl, button);
  document.body.append(card);
  active = { card, trigger };
  const current = ++generation;
  const rect = trigger.getBoundingClientRect();
  const x = Math.max(8, Math.min(rect.left, window.innerWidth - card.offsetWidth - 8));
  const y = rect.bottom + 8 + card.offsetHeight <= window.innerHeight ? rect.bottom + 8 : Math.max(8, rect.top - card.offsetHeight - 8);
  card.style.left = `${x}px`; card.style.top = `${y}px`;
  let resolvedPath = '';
  button.addEventListener('click', () => { closeCard(false); openDocument(resolvedPath); });
  // Place the button immediately after the trigger in keyboard order without
  // relying on body insertion order.
  card.addEventListener('keydown', e => { if (e.key === 'Tab' && e.shiftKey && document.activeElement === button) { e.preventDefault(); trigger.focus(); } });
  trigger.addEventListener('keydown', function enterCard(e) {
    if (!active || active.trigger !== trigger) { trigger.removeEventListener('keydown', enterCard); return; }
    if (e.key === 'Tab' && !e.shiftKey) { e.preventDefault(); button.focus(); }
  });
  if (remote) { summary.textContent = '远端文档提要不可用'; return; }
  if (!path) return;
  try {
    // The API filters against vault-relative paths, not absolute paths. Query
    // by basename and verify the full absolute identity before showing data.
    const basename = path.split(/[\\/]/).pop();
    const res = await fetch('/api/wiki/terms?doc=' + encodeURIComponent(basename) + '&limit=500', { headers: { ...authHeaders() }, cache: 'no-store' });
    if (!res.ok) throw new Error('terms unavailable');
    const data = await res.json();
    if (current !== generation || !active || active.card !== card) return;
    const hit = documentIdentity(path, data.terms || [], data.count == null || data.count <= (data.terms || []).length);
    if (hit) {
      resolvedPath = hit.doc.abs_path || '';
      heading.textContent = hit.doc.title || hit.name || heading.textContent;
      summary.textContent = hit.definition || '此文档暂无提要';
      metadata.textContent = `状态：${hit.doc.status || '—'}　更新：${hit.updated_at || '—'}`;
    } else if (data.available === false) summary.textContent = '词条库暂不可用';
  } catch { if (current === generation && active?.card === card) summary.textContent = '词条库暂不可用'; }
}
