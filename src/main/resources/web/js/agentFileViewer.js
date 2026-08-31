// agentFileViewer.js — 项目 Agent.md 查看/编辑器（#27 方向调整：像 team rules.md 一样可点击查看、可改）。
// 复用 flowViewers 的 openViewerShell overlay + 保存语义；数据走 nodeData 的
// fetchAgentFile / saveAgentFile（契约 §1：GET / PUT /api/projects/<name>/agent.md）。

import { openViewerShell } from './flowViewers.js';
import { esc } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchAgentFile, saveAgentFile } from './nodeData.js';

export async function openAgentFile(projectName) {
  const footer = `<span class="flow-viewer-status" id="flow-agentfile-status"></span><button class="flow-viewer-save" id="flow-agentfile-save">${esc(t('flowViewers.save'))}</button>`;
  const body = openViewerShell(`${projectName} · Agent.md`, { footer });
  if (!body) return;
  body.innerHTML = `<div class="flow-mail-empty">${esc(t('flowViewers.loading'))}</div>`;
  let content = '';
  try {
    // 契约 §1：GET /api/projects/<name>/agent.md → {content}；404 → 缺省文本（在 nodeData.fetchAgentFile 处理）。
    content = await fetchAgentFile(projectName);
  } catch (e) {
    content = '';
  }
  if (!body.isConnected) return; // viewer 已关
  body.innerHTML = `
    <div class="flow-def-section">
      <h3>Agent.md</h3>
      <p style="font:400 11px -apple-system;color:var(--color-text-muted);margin:0 0 8px;">${esc(t('agentFile.hint'))}</p>
      <textarea class="flow-def-edit" id="flow-agentfile-textarea" style="min-height:320px;font-family:ui-monospace,SFMono-Regular,monospace;">${esc(content)}</textarea>
    </div>`;
  const saveBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('flow-agentfile-save'));
  const statusEl = /** @type {HTMLElement|null} */ (document.getElementById('flow-agentfile-status'));
  if (saveBtn) saveBtn.addEventListener('click', async () => {
    saveBtn.disabled = true;
    if (statusEl) statusEl.textContent = t('flowViewers.saving');
    try {
      const text = /** @type {HTMLTextAreaElement} */ (document.getElementById('flow-agentfile-textarea'))?.value || '';
      await saveAgentFile(projectName, text);
      if (statusEl) statusEl.textContent = t('flowViewers.saved');
      setTimeout(() => { if (statusEl) statusEl.textContent = ''; }, 3000);
    } catch (e) {
      if (statusEl) statusEl.textContent = t('flowViewers.saveFailed', { msg: e.message });
    } finally {
      saveBtn.disabled = false;
    }
  });
}
