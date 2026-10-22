// chatQueue.js — Queue bar rendering for messages typed while LLM is busy.
// Renders a compact expandable bar above the input area (NOT in the chat flow).
//
// 排队信息量批（2026-10-22 作者追加令）：「排队中 (N)」死状态退役 ——
//   ① 头行有信息量：等待当前回复完成 · 已进行 {elapsed}（活秒表，锚
//      state.turnStartTimes[sessionId]，与 workline 转轮同源同格式）· N 条排队；
//   ② 每个排队项带位次标「第 N 位 · 等待当前回复完成」（作者原文形态）；
//   ③ 每个排队项带「已收到」确认 chip（check 图标 + 可读文本）——排队消息
//      不许消失在数字里。
// 位次 = 数组下标 +1（drain 队头先发；重排拖拽后 renderQueueBar 重渲自愈）。

import state from './state.js';
import { findViewBySessionId } from './chatView.js';
import { t } from './i18n.js';
import { formatLive } from './workline.js';

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
  // 排队信息量批：头行 = 「等待当前回复完成 · N 条排队 · 已进行 Xs」。秒表锚
  // state.turnStartTimes[sessionId]（main.js/input.js 派发点写入、回合终态删除，
  // 与 workline 活秒表同源）——锚缺失（busy 标先行、锚未达的窗口）降级为无时长
  // 形态，禁编造 0s。
  title.textContent = t('chatQueue.waitingTurn');
  title.appendChild(document.createTextNode(' · ' + t('chatQueue.queuedCount', { n: String(items.length) })));
  const turnStart = state.turnStartTimes[sessionId];
  if (turnStart) {
    const elapsed = document.createElement('span');
    elapsed.className = 'queue-bar-elapsed';
    elapsed.dataset.start = String(turnStart);
    elapsed.textContent = ' · ' + t('chatQueue.turnElapsed', { t: formatLive(Date.now() - turnStart) });
    title.appendChild(elapsed);
  }
  header.appendChild(title);

  bar.appendChild(header);

  // Items — always render, CSS handles hiding when collapsed.
  // (Previously skipped rendering when collapsed, which caused items to
  //  disappear after a re-render while collapsed — expanding showed nothing.)

  // Items
  items.forEach((item, qi) => {
    const row = document.createElement('div');
    row.className = 'queue-item' + (item.failed ? ' failed' : '');
    row.dataset.queueId = item.id;
    row.draggable = true;
    // qattachvault：失败项挂机器可读钩（QA 断言面）+ 失败原因码。
    if (item.failed) {
      row.dataset.queueFailed = '1';
      row.dataset.queueFailCode = item.failCode || 'failed';
    }

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

    // 排队信息量批：位次标 = 「第 N 位 · 等待当前回复完成」（作者原文形态；
    // N = 数组下标 +1，队头 = 下一个 drain）。失败项仍占位次（真值：还在队里）。
    const posEl = document.createElement('span');
    posEl.className = 'queue-item-pos';
    posEl.textContent = t('chatQueue.position', { n: String(qi + 1) });
    row.appendChild(posEl);

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

    // qattachvault：载荷丢失失败项 → 失败注记行 + 「立即发送」退场（对失败项再点
    // 只会重复拒绝）；撤回（回输入框重编、重新添加附件）与移除仍可达 = 可重传入口。
    if (item.failed) {
      const note = document.createElement('span');
      note.className = 'queue-item-failed-note';
      note.textContent = t('input.queueAttachLostShort');
      note.title = t('input.queueAttachLost');
      row.appendChild(note);
    }

    // 排队信息量批：「已收到」确认 chip（check + 可读文本）——排队消息不许消失
    // 在数字里（作者令）。与气泡上的 .msg-pending-mark.acked 同一语义双呈现。
    const ackEl = document.createElement('span');
    ackEl.className = 'queue-item-ack';
    ackEl.title = t('chat.queuedAckTip');
    ackEl.innerHTML = '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/></svg>';
    ackEl.appendChild(document.createTextNode(t('chat.queuedAck') || '已收到'));
    row.appendChild(ackEl);

    const actions = document.createElement('div');
    actions.className = 'queue-item-actions';

    if (!item.failed) {
      const immBtn = document.createElement('button');
      immBtn.className = 'queue-item-btn immediate';
      immBtn.textContent = t('input.sendImmediate') || '立即发送';
      immBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        handlers.onImmediate?.(item);
      });
      actions.appendChild(immBtn);
    }

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

  // 排队信息量批：头行带秒表 ⇒ 上表即武装 1s ticker（无在场秒表节点则本调用
  // 不武装——ticker 自身在扫空时自停）。
  if (document.querySelector('.queue-bar-elapsed[data-start]')) ensureElapsedTicker();
}

// ---------- 排队信息量批：头行活秒表（1s 自走，零外部接线） ----------
// renderQueueBar 只在队列变化时被调——秒表不能靠它。单例 interval：扫在场
// .queue-bar-elapsed[data-start]（头行重渲后新节点自动入扫；锚消失 = 回合终态
// 删 turnStartTimes → 由 renderQueueBar 的降级形态接管，此处只管已挂节点）。
// 无在场节点即自停（排队条隐藏 = 零周期工作，对齐 workline UI-G 纪律）。
let _elapsedTimer = 0;
function ensureElapsedTicker() {
  if (_elapsedTimer) return;
  _elapsedTimer = setInterval(() => {
    const nodes = /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.queue-bar-elapsed[data-start]'));
    if (!nodes.length) { clearInterval(_elapsedTimer); _elapsedTimer = 0; return; }
    nodes.forEach((el) => {
      const start = Number(el.dataset.start);
      if (!start) return;
      // 只重写「已进行 Xs」的值段：保留 ' · ' 分隔前缀（首文本节点不动，值在尾部）。
      const sep = el.textContent.match(/^(\s*·\s*)/);
      el.textContent = (sep ? sep[1] : '') + t('chatQueue.turnElapsed', { t: formatLive(Date.now() - start) });
    });
  }, 1000);
}
