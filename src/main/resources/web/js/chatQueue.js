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

  // Header — toggle button first, matching task list style
  const header = document.createElement('div');
  header.className = 'queue-bar-header';

  // Collapse toggle (chevron icon, same style as task-toggle)
  const collapseBtn = document.createElement('button');
  collapseBtn.className = 'queue-bar-collapse';
  const chevronDown = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>';
  const chevronRight = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>';
  const isCollapsed = bar.classList.contains('collapsed');
  collapseBtn.innerHTML = isCollapsed ? chevronRight : chevronDown;
  collapseBtn.title = isCollapsed ? 'Expand' : 'Collapse';
  collapseBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    bar.classList.toggle('collapsed');
    const coll = bar.classList.contains('collapsed');
    collapseBtn.innerHTML = coll ? chevronRight : chevronDown;
    collapseBtn.title = coll ? 'Expand' : 'Collapse';
  });
  header.appendChild(collapseBtn);

  const title = document.createElement('span');
  title.className = 'queue-bar-title';
  const label = t('input.queued') || '排队中';
  title.textContent = `${label} (${items.length})`;
  header.appendChild(title);

  bar.appendChild(header);

  // Items — always render, CSS handles hiding when collapsed.
  // (Previously skipped rendering when collapsed, which caused items to
  //  disappear after a re-render while collapsed — expanding showed nothing.)

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
    grip.title = t('chatQueue.dragReorder');
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
	    const fullText = item.mode === 'compact'
	      ? `/compact ${item.text}`.trim()
	      : item.skillName ? `/${item.skillName} ${item.text}` : item.text;
	    textEl.textContent = fullText;
	    // Always allow click-to-expand — CSS truncates by container width,
	    // not character count, so even short messages may be visually clipped.
	    textEl.title = t('chatQueue.clickExpand');
	    textEl.addEventListener('click', () => {
		const isExpanded = row.classList.toggle('expanded');
		textEl.title = isExpanded ? t('chatQueue.clickCollapse') : t('chatQueue.clickExpand');
	    });
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

    const recallBtn = document.createElement('button');
    recallBtn.className = 'queue-item-btn recall';
    recallBtn.title = t('chat.recall') || 'Recall';
    recallBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/></svg>';
    recallBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      handlers.onRecall?.(item);
    });
    actions.appendChild(recallBtn);

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
