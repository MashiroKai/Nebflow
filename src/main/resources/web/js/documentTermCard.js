// Document-reference preview. The doc-metadata API is queried on every opening so
// a freshly produced document (and a fresh frontmatter edit) shows up without a
// page reload; no live-registry cache is involved.
// 批注（canvas-popup-unify 批 ④ · 2026-11-22）：批前这里按 basename 查词条库再用
// `documentIdentity` 反算绝对路径 —— 词条库只收**已登记提要**的文档，没有提要的
// 产出文档查不到 ⇒ 弹窗空白。现在改查文档元数据端点（服务端按路径读文档本身），
// 身份判定由服务端的路径解析承担，本文件不再自己猜。
import { authHeaders } from './flowHelpers.js';

let active = null;
let generation = 0;
function closeCard(restore = true) {
  generation++;
  if (!active) return;
  const { card, trigger, onClose } = active;
  card.remove();
  active = null;
  onClose?.();
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
export function closeDocumentTermCard() { closeCard(false); }

export async function showDocumentTermCard(trigger, { path = '', title = '', remote = false, openDocument = null, term = null, onClose = null }) {
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
  active = { card, trigger, onClose };
  const current = ++generation;
  const rect = trigger.getBoundingClientRect();
  const x = Math.max(8, Math.min(rect.left, window.innerWidth - card.offsetWidth - 8));
  const y = rect.bottom + 8 + card.offsetHeight <= window.innerHeight ? rect.bottom + 8 : Math.max(8, rect.top - card.offsetHeight - 8);
  card.style.left = `${x}px`; card.style.top = `${y}px`;
  let resolvedPath = '';
  button.addEventListener('click', () => { if (!term) { closeCard(false); openDocument(resolvedPath); } });
  // Place the button immediately after the trigger in keyboard order without
  // relying on body insertion order.
  card.addEventListener('keydown', e => { if (e.key === 'Tab' && e.shiftKey && document.activeElement === card.querySelector('button:not([hidden])')) { e.preventDefault(); trigger.focus(); } });
  trigger.addEventListener('keydown', function enterCard(e) {
    if (!active || active.trigger !== trigger) { trigger.removeEventListener('keydown', enterCard); return; }
    if (e.key === 'Tab' && !e.shiftKey) {
      const action = card.querySelector(/** @type {'button'} */ ('button:not([hidden])'));
      if (action) { e.preventDefault(); action.focus(); }
    }
  });
  if (term) {
    card.setAttribute('aria-label', '词条预览');
    summary.setAttribute('aria-live', 'polite');
    pathEl.hidden = true;
    button.textContent = '↗ 打开原词条';
    button.setAttribute('aria-label', '打开原词条');
    const update = (data) => {
      if (active?.card !== card) return;
      heading.textContent = data.title || '词条';
      summary.textContent = data.summary || '';
      metadata.textContent = data.metadata || '';
      button.hidden = !data.open;
      button.onclick = data.open ? () => { closeCard(false); data.open(); } : null;
      let retry = /** @type {HTMLButtonElement | null} */ (card.querySelector('.document-term-retry'));
      if (data.retry && !retry) {
        retry = document.createElement('button');
        retry.type = 'button'; retry.className = 'document-term-open document-term-retry';
        retry.textContent = '重试'; card.append(retry);
      }
      if (retry) { retry.hidden = !data.retry; retry.onclick = data.retry || null; }
      const r = trigger.getBoundingClientRect();
      card.style.left = `${Math.max(8, Math.min(r.left, window.innerWidth - card.offsetWidth - 8))}px`;
      card.style.top = `${r.bottom + 8 + card.offsetHeight <= window.innerHeight ? r.bottom + 8 : Math.max(8, r.top - card.offsetHeight - 8)}px`;
    };
    update(term);
    return { update };
  }
  if (remote) { summary.textContent = '远端文档提要不可用'; return; }
  if (!path) return;
  try {
    // 唯一数据腿 = 文档元数据端点（canvas-popup-unify 批 ④，2026-11-22 作者令
    // 「现在产出的文档 点击还是没有说明……现在点击还是空的」）：
    //   GET /api/wiki/doc?path=<absolute|project/.stand/docs/rel>
    // 服务端只认注册目录 vault 一种落位形态，读序 = frontmatter `summary:` 优先
    // → 旧文档 `<!-- reader-summary -->` 完整段落 → 无（WikiIndex.readerSummary）。
    // 批前这里查的是词条库（/api/wiki/terms?doc=basename）：词条库只收**已登记
    // 词条**的文档提要，没有提要的产出文档根本不在库里 ⇒ 弹窗只剩「暂无提要」，
    // 正是作者看到的「空的」。
    const res = await fetch('/api/wiki/doc?path=' + encodeURIComponent(path), {
      headers: { ...authHeaders() }, cache: 'no-store',
    });
    if (!res.ok) throw new Error('doc meta unavailable');
    const meta = await res.json();
    if (current !== generation || !active || active.card !== card) return;
    if (meta.available === false) {
      // 文档读不到（远端离线 / 超限 / 已移走）：壳照常画，措辞指出缺的是哪一层。
      summary.textContent = '读不到这篇文档的正文（离线或已移走），元数据暂不可用。';
      summary.classList.add('document-term-empty');
      summary.dataset.cvHint = 'empty';
      metadata.textContent = `类型：—　状态：—　更新：${meta.updated || '—'}`;
      return;
    }
    resolvedPath = meta.path || path;
    heading.textContent = meta.title || heading.textContent;
    // 提要顺序的服务端读序已经在 payload 里落定（summarySource 显式回报来源）；
    // 没有提要 = 可读空态 + 具名缺哪个字段，绝不画纯空白。
    if (meta.summary) {
      summary.textContent = meta.summary;
      summary.dataset.summarySource = meta.summarySource || 'frontmatter';
      summary.title = meta.summary;
    } else {
      summary.textContent = '此文档暂无提要：frontmatter 未写 summary:，正文也没有 <!-- reader-summary --> 标记。';
      summary.classList.add('document-term-empty');
      summary.dataset.cvHint = 'empty';
    }
    const metaBits = [`类型：${meta.type || '—'}`, `状态：${meta.status || '—'}`, `更新：${meta.updated || '—'}`];
    metadata.textContent = metaBits.join('　');
    if (Array.isArray(meta.tags) && meta.tags.length) {
      metadata.textContent += `　标签：${meta.tags.join(' · ')}`;
    }
  } catch {
    if (current === generation && active?.card === card) {
      summary.textContent = '文档元数据暂不可用（请求失败）。';
      summary.classList.add('document-term-empty');
    }
  }
}
