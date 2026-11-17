import state from './state.js';
import { onMessage } from './ws.js';
import { currentSessionDecisionCards, unresolvedDecisionCardCount, validateNewDecisionCard, DECISION_CARD_PROJECT, shouldBlockDecisionTurn } from './decisionCardModel.js';
import { todoStore } from './todoStore.js';

let latestRows = [];
export function hasUnresolvedDecision(sessionId) { return shouldBlockDecisionTurn(latestRows, sessionId); }

// Preview only. The list feed has no verified row-level permission contract.
// Filter raw rows, never todoStore's sessionId fallback.
export function installDecisionCardPreview() {
  let rows = [];
  let selected = 0;
  let detail = false;
  let showForm = false;
  let formDraft = { title: '', summary: '', content: '' };
  let requestId = null;
  let saving = false;
  const entry = document.createElement('button');
  entry.type = 'button';
  entry.className = 'decision-preview-entry';
  const panel = document.createElement('section');
  panel.className = 'decision-preview';
  panel.hidden = true;
  panel.setAttribute('aria-label', '决策卡预览');
  const style = document.createElement('style');
  style.textContent = `
    .decision-preview-entry{position:fixed;right:16px;bottom:90px;z-index:200;background:var(--ds-surface);color:var(--ds-text);border:1px solid var(--ds-border);border-radius:20px;padding:8px 12px}
    .decision-preview{position:fixed;inset:8% 12%;z-index:201;overflow:auto;background:var(--ds-surface);color:var(--ds-text);border:1px solid var(--ds-border);border-radius:16px;padding:20px;box-shadow:var(--ds-shadow-panel)}
    .decision-preview[hidden]{display:none}.decision-preview article{border:1px solid var(--ds-border);border-radius:12px;padding:16px;margin:12px 0;overflow-wrap:anywhere}.decision-preview h2{margin:0}.decision-preview button{margin:6px;padding:8px}.decision-preview .cover{aspect-ratio:16/9;background:var(--ds-surface-sunken);display:grid;place-items:center;color:var(--ds-text-muted)}.decision-preview .mobile-nav{display:none}
    @media(max-width:700px){.decision-preview{inset:3%;}.decision-preview article:not(.current){display:none}.decision-preview .mobile-nav{display:block}}
  `;
  document.head.append(style);
  document.body.append(entry, panel);
  function visible() {
    return currentSessionDecisionCards(rows, state.activeSessionId);
  }
  function button(label, action) {
    const el = document.createElement('button'); el.type = 'button'; el.textContent = label; el.onclick = action; return el;
  }
  function render() {
    const cards = visible();
    selected = Math.max(0, Math.min(selected, cards.length - 1));
    entry.textContent = `决策卡预览 · ${unresolvedDecisionCardCount(cards)}`;
    panel.replaceChildren();
    const header = document.createElement('h2'); header.textContent = '决策卡预览'; panel.append(header);
    const notice = document.createElement('p'); notice.textContent = '本阶段仅显示当前会话的待办，跨会话可见性待权限模型确认。只读预览，回应尚未接线。'; panel.append(notice);
    panel.append(button('关闭', () => { panel.hidden = true; }));
    panel.append(button('新建决策卡', () => { showForm = !showForm; render(); }));
    if (showForm) {
      const form = document.createElement('form');
      for (const [field, label] of [['title', '标题'], ['summary', '摘要（40–100字）'], ['content', '内容（最多300字）']]) {
        const wrapper = document.createElement('label'); wrapper.textContent = label;
        const input = document.createElement(field === 'title' ? 'input' : 'textarea');
        input.name = field; input.value = formDraft[field]; input.style.cssText = 'display:block;width:100%;box-sizing:border-box;margin:6px 0 12px';
        input.addEventListener('input', () => { formDraft[field] = input.value; requestId = null; });
        wrapper.append(input); form.append(wrapper);
      }
      const error = document.createElement('p'); error.setAttribute('role', 'alert'); form.append(error);
      const submit = document.createElement('button'); submit.type = 'submit'; submit.textContent = saving ? '正在保存…' : '保存决策卡'; submit.disabled = saving; form.append(submit);
      form.onsubmit = async event => {
        event.preventDefault();
        const { title, summary, content } = formDraft;
        const result = validateNewDecisionCard({ summary, content });
        if (!title.trim() || !result.summaryValid || !result.contentValid) { error.textContent = '请填写标题，摘要须为40–100字、内容不超过300字。'; return; }
        if (saving) return;
        saving = true; submit.disabled = true;
        requestId ||= crypto.randomUUID();
        // Temporary encoding is deliberately explicit; a formal schema needs migration.
        const payload = JSON.stringify({ summary, content });
        const ok = await todoStore.createItemConfirmed({ title, notes: payload, project: DECISION_CARD_PROJECT, requestId, sessionId: state.activeSessionId });
        saving = false;
        if (!ok) { error.textContent = '保存未确认，请保留内容重试。'; submit.disabled = false; return; }
        formDraft = { title: '', summary: '', content: '' }; requestId = null; showForm = false; todoStore.refresh(); render();
      };
      panel.append(form);
    }
    if (!cards.length) { const empty = document.createElement('p'); empty.textContent = '当前会话暂无可显示的待办。'; panel.append(empty); }
    cards.forEach((card, index) => {
      const article = document.createElement('article');
      if (index === selected) article.className = 'current';
      const cover = document.createElement('div'); cover.className = 'cover'; cover.textContent = card.type === 'detail' ? '有详情' : '无详情'; article.append(cover);
      const title = document.createElement('h3'); title.textContent = card.title; article.append(title);
      const summary = document.createElement('p'); summary.textContent = card.summary; article.append(summary);
      const status = document.createElement('p'); status.textContent = card.status === 'completed' ? '已完成' : '未完成 · 回应未接线'; article.append(status);
      if (card.type === 'detail') {
        article.append(button(detail && index === selected ? '收起详情' : '阅读详情', () => { selected = index; detail = !detail; render(); }));
        if (detail && index === selected) { const body = document.createElement('p'); body.textContent = card.content; article.append(body); }
      }
      panel.append(article);
    });
    const nav = document.createElement('div'); nav.className = 'mobile-nav';
    nav.append(button('上一张', () => { selected = Math.max(0, selected - 1); detail = false; render(); }), button('下一张', () => { selected = Math.min(cards.length - 1, selected + 1); detail = false; render(); })); panel.append(nav);
  }
  let touchX = null;
  panel.addEventListener('touchstart', e => { touchX = e.touches[0]?.clientX ?? null; }, { passive: true });
  panel.addEventListener('touchend', e => {
    if (touchX == null) return;
    const dx = e.changedTouches[0].clientX - touchX; touchX = null;
    if (Math.abs(dx) < 70) return;
    selected = Math.max(0, Math.min(visible().length - 1, selected + (dx < 0 ? 1 : -1))); detail = false; render();
  }, { passive: true });
  entry.onclick = () => { panel.hidden = false; render(); };
  onMessage('scheduledTaskList', msg => { if (msg.scope === 'all') { rows = Array.isArray(msg.tasks) ? msg.tasks : []; latestRows = rows; render(); } });
  // Session changes can occur without another list response: never leave old cards visible.
  let lastSession = state.activeSessionId;
  document.addEventListener('click', () => { setTimeout(() => { if (lastSession !== state.activeSessionId) { lastSession = state.activeSessionId; selected = 0; detail = false; render(); } }, 0); }, true);
  render();
}
