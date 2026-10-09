// chat.js 拆分(FE组件化批次三 2026-09-27):pop-artifact 三件套 可复用模块(行为保持)。
import { escapeHtml, localizeToolLabel, localizeToolSummary } from '../utils.js';

/**
 * Extract the Pop/Card artifact from a tool label + raw input — the SINGLE
 * detection predicate shared by the message-bubble Pop card (applyPopCard)
 * and the search-results click (chatSearch queue #2): a Pop or legacy-Card
 * tool whose input JSON carries a resolvable filePath. Returns
 * {filePath, title} or null (not an openable artifact → plain jump).
 */
export function popArtifactFromInput(label, inputJson) {
  const name = label ? label.split('(')[0].split('\n')[0].trim() : '';
  if ((name !== 'Pop' && name !== 'Card') || !inputJson) return null;
  let filePath = '';
  let title = '';
  try {
    const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
    filePath = inp.filePath || '';
    title = inp.title || '';
  } catch { /* malformed input → no openable artifact */ }
  return filePath ? { filePath, title } : null;
}

/** Open a Pop artifact in the Canvas panel — the SINGLE open path shared by
 *  the message-bubble Pop card and the search-results click (queue #2).
 *  Dispatches the workspace-open-item event canvas.js listens for; the empty
 *  content + absPath shape makes canvas fetch the real content via readFile. */
export function openPopArtifact(filePath, title) {
  const fileName = filePath.split('/').pop() || filePath;
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: {
      id: 'file:' + filePath,
      title: title || fileName,
      itemType: '',
      content: '',
      absPath: filePath,
      pinned: true
    }
  }));
}

/** Render a Pop tool card onto an existing card element.
 *  Shared between live renderTool and history restoreFromBackendHistory.
 *  Returns true if the card was handled (Pop tool), false otherwise. */
export function applyPopCard(card, label, summary, inputJson, isError) {
  const _toolName = label ? label.split('(')[0].split('\n')[0].trim() : '';
  if (_toolName !== 'Pop' || !inputJson) return false;

  const icon = isError
    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const art = popArtifactFromInput(label, inputJson);
  const popFilePath = art ? art.filePath : '';
  const popTitle = art ? art.title : '';
  const popFileName = popFilePath.split('/').pop() || popFilePath;
  const popLocalLabel = localizeToolLabel(label);
  const popLocalSummary = localizeToolSummary(summary, label);
  const popLabelParts = popLocalLabel.split('\n', 2);
  const rainbowName = '<span class="pop-rainbow-name">' + escapeHtml(popTitle || popFileName) + '</span>';
  const summaryHtml = escapeHtml(popLocalSummary).replace(escapeHtml(popFileName), rainbowName);
  const labelHtml = escapeHtml(popLabelParts[0]) + ' &mdash; ' + summaryHtml
    + (popLabelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(popLabelParts[1]) + '</span>' : '');
  card.classList.add('pop-tool-card');
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div></div>';
  if (popFilePath) {
    card.addEventListener('click', () => openPopArtifact(popFilePath, popTitle));
  }
  return true;
}
