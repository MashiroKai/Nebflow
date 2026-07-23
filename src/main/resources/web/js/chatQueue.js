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

  // Collapse toggle
  const collapseBtn = document.createElement('button');
  collapseBtn.className = 'queue-bar-collapse';
  collapseBtn.innerHTML = bar.classList.contains('collapsed') ? '\u25B8' : '\u25BE';
  collapseBtn.title = bar.classList.contains('collapsed') ? 'Expand' : 'Collapse';
  collapseBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    bar.classList.toggle('collapsed');
    collapseBtn.innerHTML = bar.classList.contains('collapsed') ? '\u25B8' : '\u25BE';
    collapseBtn.title = bar.classList.contains('collapsed') ? 'Expand' : 'Collapse';
  });
  header.appendChild(collapseBtn);
  bar.appendChild(header);

  // Skip rendering items if collapsed
  if (bar.classList.contains('collapsed')) return;

  // Items
  items.forEach((item) => {
    const row = document.createElement('div');
    row.className = 'queue-item';
    row.dataset.queueId = item.id;
    row.draggable = true;

    // Drag handle (grip icon)
    const grip = document.createElement('span');
    grip.className = 'queue-item-grip';
    grip.innerHTML = '\u22EE';
    grip.title = 'Drag to reorder';
    row.appendChild(grip);

    // Drag events
    row.addEventListener('dragstart', (e) => {
      row.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', item.id);
      bar._draggedId = item.id;
    });
    row.addEventListener('dragend', () => {
      row.classList.remove('dragging');
      bar.querySelectorAll('.queue-item.drag-over').forEach(el => el.classList.remove('drag-over'));
      delete bar._draggedId;
    });
    row.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      if (bar._draggedId && bar._draggedId !== item.id) {
        row.classList.add('drag-over');
      }
    });
    row.addEventListener('dragleave', () => {
      row.classList.remove('drag-over');
    });
    row.addEventListener('drop', (e) => {
      e.preventDefault();
      e.stopPropagation();
      row.classList.remove('drag-over');
      const draggedId = bar._draggedId;
      if (!draggedId || draggedId === item.id) return;
      const queue = state.messageQueue[sessionId] || [];
      const fromIdx = queue.findIndex(q => q.id === draggedId);
      const toIdx = queue.findIndex(q => q.id === item.id);
      if (fromIdx === -1 || toIdx === -1) return;
      const [moved] = queue.splice(fromIdx, 1);
      queue.splice(toIdx, 0, moved);
      state.messageQueue[sessionId] = queue;
      renderQueueBar(sessionId, handlers);
    });

	    const textEl = document.createElement('span');
	    textEl.className = 'queue-item-text';
	    const previewLen = 80;
	    const fullText = item.skillName ? `/${item.skillName} ${item.text}` : item.text;
	    textEl.textContent = fullText.length > previewLen
	      ? fullText.slice(0, previewLen) + '…'
	      : fullText;
	    row.appendChild(textEl);

	    // Attachment indicator: show small thumbnail for images, file icon for others
	    const atts = item.attachments || [];
	    if (atts.length > 0) {
	      const attEl = document.createElement('span');
	      attEl.className = 'queue-item-att';
	      atts.slice(0, 3).forEach((att, i) => {
	        if (att.type === 'image' && att.preview && typeof att.preview === 'string') {
	          const img = document.createElement('img');
	          img.className = 'queue-item-att-thumb';
	          img.src = att.preview;
	          img.alt = '';
	          attEl.appendChild(img);
	        } else {
	          const fileLabel = document.createElement('span');
	          fileLabel.className = 'queue-item-att-file';
	          fileLabel.textContent = att.name || 'file';
	          attEl.appendChild(fileLabel);
	        }
	      });
	      if (atts.length > 3) {
	        const more = document.createElement('span');
	        more.className = 'queue-item-att-more';
	        more.textContent = '+' + (atts.length - 3);
	        attEl.appendChild(more);
	      }
	      row.appendChild(attEl);
	    }

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
