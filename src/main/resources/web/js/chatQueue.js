// chatQueue.js — Queue bar rendering for messages typed while LLM is busy.
// Renders a compact expandable bar above the input area (NOT in the chat flow).

import state from './state.js';
import { findViewBySessionId } from './chatView.js';
import { t } from './i18n.js';

/**
 * Re-render the queue bar for a given session.
 * Reads state.messageQueue[sessionId] and renders all items.
 * If queue is empty, the bar is hidden.
 *
 * @param {string} sessionId
 * @param {{ onImmediate:Function, onRemove:Function }} handlers
 */
export function renderQueueBar(sessionId, handlers = {}) {
  const view = findViewBySessionId(sessionId);
  if (!view || !view.dom.queueBar) return;

  const bar = view.dom.queueBar;
  const items = state.messageQueue[sessionId] || [];

  if (items.length === 0) {
    bar.classList.remove('visible');
    bar.innerHTML = '';
    return;
  }

  bar.classList.add('visible');
  bar.innerHTML = '';

  // Header
  const header = document.createElement('div');
  header.className = 'queue-bar-header';
  const title = document.createElement('span');
  title.className = 'queue-bar-title';
  const label = t('input.queued') || '排队中';
  title.textContent = `${label} (${items.length})`;
  header.appendChild(title);
  bar.appendChild(header);

  // Items
  items.forEach((item) => {
    const row = document.createElement('div');
    row.className = 'queue-item';
    row.dataset.queueId = item.id;

    const textEl = document.createElement('span');
    textEl.className = 'queue-item-text';
    const previewLen = 80;
    textEl.textContent = item.text.length > previewLen
      ? item.text.slice(0, previewLen) + '…'
      : item.text;
    row.appendChild(textEl);

    const actions = document.createElement('div');
    actions.className = 'queue-item-actions';

    const immBtn = document.createElement('button');
    immBtn.className = 'queue-item-btn immediate';
    immBtn.textContent = t('input.sendImmediate') || '立即发送';
    immBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      handlers.onImmediate?.(item);
    });
    actions.appendChild(immBtn);

    const rmBtn = document.createElement('button');
    rmBtn.className = 'queue-item-btn remove';
    rmBtn.textContent = '\u00d7';
    rmBtn.title = t('input.removeQueued') || '移除';
    rmBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      handlers.onRemove?.(item);
    });
    actions.appendChild(rmBtn);

    row.appendChild(actions);
    bar.appendChild(row);
  });
}
