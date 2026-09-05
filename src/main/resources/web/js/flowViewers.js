// flowViewers.js — Overlay viewers: mailbox, rules editor, definition editor.
//
// All viewers mount on the stable overlay root (survives renderAll re-renders).

import { renderMarkdownWithMath } from './utils.js';
import state from './state.js';
import { esc, authHeaders, fmtTime, fmtRelTime, overlayRoot, setMailPending } from './flowHelpers.js';
import { t } from './i18n.js';
import { fetchPresets, setAgentPreset, resolvedChainHtml } from './presets.js';

/** Render a mail body into safe HTML. The detail area must NEVER be silently
 *  blank (2026-08-26 bug: a record with an empty/undefined `message` rendered
 *  `renderMarkdownWithMath('')` → `''` → the row's detail pane looked empty
 *  while its from/to meta stayed visible — "列表可见，详情区空白"). Also guards
 *  per-row so a single bad render cannot throw and wipe the whole History list
 *  into the outer catch. */
function mailBodyHtml(msg) {
  if (msg == null || !String(msg).trim()) return `<span class="flow-mail-no-body">${esc(t('mailFlow.noBody'))}</span>`;
  try { return renderMarkdownWithMath(msg); }
  catch (e) { return `<span class="flow-mail-no-body">${esc(t('mailFlow.renderFailed', { msg: e.message }))}</span>`; }
}

/** Fetch and render per-agent model config into a placeholder element.
 *  Called after agent blocks are rendered in openDefinition. */
async function populateAgentModel(el, agentName) {
  try {
    const [resp, presetData] = await Promise.all([
      fetch(`/api/agents/${encodeURIComponent(agentName)}/model`, { headers: authHeaders() }),
      fetchPresets(),
    ]);
    if (!resp.ok) { el.innerHTML = '<span class="flow-agent-block-empty">无法加载</span>'; return; }
    const cfg = await resp.json();
    renderAgentModelSection(el, agentName, cfg, presetData);
  } catch (e) {
    el.innerHTML = '<span class="flow-agent-block-empty">无法加载</span>';
  }
}

/** Render model section: preset dropdown + read-only resolved chain + current indicator. */
function renderAgentModelSection(el, agentName, cfg, presetData) {
  const presetName = cfg.preset || '';
  const current = cfg.current || cfg.preferred || cfg.default || '';
  const preferred = cfg.preferred || cfg.default || '';
  const isFallback = current && preferred && current !== preferred;
  const presetList = presetData?.presets || [];
  const defaultPreset = presetList.find(p => p.name === presetData?.defaultPreset);

  const currentHtml = isFallback
    ? `<span class="flow-agent-model-current fallback">运行: ${esc(current)}</span>`
    : '';

  el.innerHTML = `
    <div class="flow-agent-model-row">
      <select class="flow-agent-model-select" data-agent="${esc(agentName)}">
        <option value=""${!presetName ? ' selected' : ''}>${t('preset.useDefault')}${defaultPreset ? `（${esc(defaultPreset.name)}）` : ''}</option>
        ${presetList.map(p => `<option value="${esc(p.name)}"${p.name === presetName ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}
      </select>
      ${currentHtml}
    </div>
    ${resolvedChainHtml(cfg)}`;

  // Bind dropdown change → PUT /api/agents/:name/preset
  const sel = el.querySelector('.flow-agent-model-select');
  sel?.addEventListener('change', async () => {
    await setAgentPreset(agentName, sel.value || null);
    populateAgentModel(el, agentName);
  });
}

export function closeViewer() {
  const existing = overlayRoot().querySelector('.flow-viewer-overlay');
  if (existing) existing.remove();
  mailboxCtx = null;
}

// ── Node 结果详情（Flow Map 节点点击查看）──────────────────
// 复用 openViewerShell 的 overlay 语义；结果从活动或归档读取（阶段 0 mock 显 result）。
// cfg = {skill, mcp, preset}——节点配置（Flow Map 卡片上紧凑徽标，详情里全量展示）。
/** blocked = { feedback: {category, detail, suggestion} | null, count: number } | null
 *  —— NodePayload.blockedFeedback/blockCount 透传（20260902 反馈路径设计 §4.3），
 *  仅 status='blocked' 时由 openNodeDetail 传入。 */
export function openNodeResultViewer(nodeName, agent, status, worktree, result, nodeId, blocked = null, cfg = {}) {
  const title = `${nodeName}${result ? ' · ' + t('flowmap.resultTitle') : ' · ' + t('flowmap.noResult')}`;
  const body = openViewerShell(title);
  if (!body) return;
  const meta = [agent, status, worktree].filter(Boolean).map((s) => esc(s)).join(' · ');
  const cfgParts = [['skill', cfg.skill], ['mcp', cfg.mcp], ['preset', cfg.preset]]
    .filter(([, v]) => typeof v === 'string' && v)
    .map(([k, v]) => `<span class="flow-node-cfg"><span class="flow-node-cfg-key">${esc(k)}</span>${esc(v)}</span>`);
  const label = result ? t('flowmap.resultTitle') : t('flowmap.noResult');
  const content = result
    ? result
    : (status === 'running' ? t('flowmap.runningDetail') : t('flowmap.noResultDetail'));
  // blocked 结构化渲染（§4.3）：category 标签 + detail + suggestion + 「已被阻断 N 轮」；
  // result（后端渲染串 [blocked:<category>] <detail> — 建议: <suggestion>）折叠可展开。
  const fb = status === 'blocked' ? (blocked?.feedback || {}) : null;
  const blockedPanel = fb ? `
    <div class="flow-blocked-panel">
      <div class="flow-blocked-head">
        <span class="flow-blocked-badge">⚑ ${esc(t('flowmap.blockedTitle'))}</span>
        ${fb.category ? `<span class="flow-blocked-category" title="${esc(t('flowmap.blockedCategory'))}">${esc(fb.category)}</span>` : ''}
        <span class="flow-blocked-count">${esc(t('flowmap.blockedRounds', { n: Number(blocked?.count) || 0 }))}</span>
      </div>
      ${fb.detail ? `<div class="flow-blocked-row"><span class="flow-blocked-label">${esc(t('flowmap.blockedDetail'))}</span><div class="flow-blocked-text">${esc(fb.detail)}</div></div>` : ''}
      ${fb.suggestion ? `<div class="flow-blocked-row"><span class="flow-blocked-label">${esc(t('flowmap.blockedSuggestion'))}</span><div class="flow-blocked-text">${esc(fb.suggestion)}</div></div>` : ''}
    </div>` : '';
  const resultHtml = fb && result
    ? `<details class="flow-blocked-raw">
        <summary>${esc(t('flowmap.blockedRawTitle'))}</summary>
        <div class="flow-agent-block-readonly">${esc(result)}</div>
      </details>`
    : `<div class="flow-agent-block-field">
        <span class="flow-agent-block-label">${esc(label)}</span>
        <div class="flow-agent-block-readonly" style="max-height:420px;overflow-y:auto;white-space:pre-wrap">${esc(content)}</div>
      </div>`;
  body.innerHTML = `
    <div class="flow-def-section">
      <div class="flow-agent-block-head"><span class="flow-agent-block-name">${esc(nodeName)}</span></div>
      ${meta ? `<div class="flow-def-source">${meta}</div>` : ''}
      ${cfgParts.length ? `<div class="flow-def-source flow-node-cfg-line">${cfgParts.join('')}</div>` : ''}
      ${blockedPanel}
      ${resultHtml}
    </div>`;
}

export function openViewerShell(title, opts = {}) {
  closeViewer();
  const overlay = document.createElement('div');
  overlay.className = 'flow-viewer-overlay';
  const footerHtml = opts.footer ? `<div class="flow-viewer-footer">${opts.footer}</div>` : '';
  overlay.innerHTML = `
    <div class="flow-viewer">
      <div class="flow-viewer-header">
        <span class="flow-viewer-title">${esc(title)}</span>
        <span class="flow-viewer-close" id="flow-viewer-close">✕</span>
      </div>
      <div class="flow-viewer-body" id="flow-viewer-body"></div>
      ${footerHtml}
    </div>`;
  overlayRoot().appendChild(overlay);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay || e.target.id === 'flow-viewer-close') closeViewer();
  });
  return overlay.querySelector('#flow-viewer-body');
}

// ── Team Rules editor ──────────────────────────────────────

export async function openRules(teamName) {
  const footer = `<span class="flow-viewer-status" id="flow-rules-status"></span><button class="flow-viewer-save" id="flow-rules-save">${t('flowViewers.save')}</button>`;
  const body = openViewerShell(`${teamName} · ${t('rules.title')}`, { footer });
  if (!body) return;
  body.innerHTML = `<div class="flow-mail-empty">${t('flowViewers.loading')}</div>`;
  try {
    const resp = await fetch(`/api/team/rules/${encodeURIComponent(teamName)}`, { headers: authHeaders() });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    body.innerHTML = `
      <div class="flow-def-section">
        <h3>${t('rules.title')}</h3>
        <p style="font: 400 11px -apple-system; color: var(--color-text-muted); margin: 0 0 8px;">${t('flowViewers.rulesHint')}</p>
        <textarea class="flow-def-edit" id="flow-rules-textarea" style="min-height: 320px; font-family: ui-monospace, SFMono-Regular, monospace;">${esc(data.content || '')}</textarea>
      </div>`;
    const saveBtn = document.getElementById('flow-rules-save');
    const statusEl = document.getElementById('flow-rules-status');
    if (saveBtn) saveBtn.addEventListener('click', async () => {
      saveBtn.disabled = true;
      if (statusEl) statusEl.textContent = t('flowViewers.saving');
      try {
        const text = document.getElementById('flow-rules-textarea')?.value || '';
        const r = await fetch(`/api/team/rules/${encodeURIComponent(teamName)}`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ content: text }) });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        if (statusEl) statusEl.textContent = t('flowViewers.saved');
        setTimeout(() => { if (statusEl) statusEl.textContent = ''; }, 3000);
      } catch (e) { if (statusEl) statusEl.textContent = t('flowViewers.saveFailed', { msg: e.message }); }
      finally { saveBtn.disabled = false; }
    });
  } catch (e) { body.innerHTML = `<div class="flow-mail-empty">Failed to load: ${esc(e.message)}</div>`; }
}

// ── Inbox viewer ───────────────────────────────────────────

// Context of the currently open mailbox — lets mailQueued/mailDequeued WS
// events refresh the Pending section live (see refreshMailboxPending).
let mailboxCtx = null; // { flowName, team }

function pendingRowHtml(it) {
  // Mail type tag is display-localized; protocol field values stay English.
  // Unknown values fall back to the raw type.
  let typeLabel = it.type;
  if (it.type) {
    const key = 'mailType.' + it.type;
    const translated = t(key);
    if (translated !== key) typeLabel = translated;
  }
  const typeTag = it.type ? `<span class="flow-mail-queue-tag">${esc(typeLabel)}</span>` : '';
  return `
    <div class="flow-mail-row pending" data-item-id="${esc(it.id || '')}" data-sid="${esc(it.toSession || '')}">
      <div class="flow-mail-meta">
        <span class="flow-mail-pending-dot"></span>
        <span class="flow-mail-from">${esc(it.from || '?')}</span>
        <span class="flow-mail-arrow">→</span>
        <span class="flow-mail-to">${esc(it.to || '?')}</span>
        <span class="flow-mail-queue-tag" title="${esc(t('mailDelivery.queueTitle'))}">${esc(t('mailDelivery.queue'))}</span>
        ${typeTag}
        <span class="flow-mail-time">${esc(fmtRelTime(it.timestamp))}</span>
        <span class="flow-mail-expand">展开 ▾</span>
        <button class="flow-mail-cancel" title="${t('flowViewers.removeFromQueue')}">${t('flows.cancel')}</button>
      </div>
      <div class="flow-mail-content">${mailBodyHtml(it.message)}</div>
    </div>`;
}

/** Load pending mail-queue items for every agent session of the team and
 *  render the Pending section. Hidden entirely when the queue is empty. */
async function loadPendingSection(body, team) {
  const section = body.querySelector('#flow-mail-pending-section');
  const list = body.querySelector('#flow-mail-pending-list');
  const countEl = body.querySelector('#flow-mail-pending-count');
  if (!section || !list || !countEl) return;
  const agents = (team?.agents || []).filter(a => a.sessionId);
  if (agents.length === 0) { section.style.display = 'none'; list.innerHTML = ''; return; }
  const results = await Promise.allSettled(agents.map(async a => {
    const resp = await fetch(`/api/teams/mail-queue/${encodeURIComponent(a.sessionId)}`, { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    const items = Array.isArray(data) ? data : (data.items || []);
    return items.map(it => ({ ...it, to: it.to || a.name, toSession: a.sessionId }));
  }));
  // Viewer may have been closed/reopened while awaiting — bail if detached.
  if (!section.isConnected) return;
  const items = results.flatMap(r => (r.status === 'fulfilled' ? r.value : []));
  // Queue order: oldest first (FIFO).
  items.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
  if (items.length === 0) { section.style.display = 'none'; list.innerHTML = ''; return; }
  section.style.display = '';
  countEl.textContent = `(${items.length})`;
  list.innerHTML = items.map(pendingRowHtml).join('');
  // Row click toggles expand/collapse (same interaction as History rows).
  list.querySelectorAll('.flow-mail-row.pending').forEach(row => {
    row.addEventListener('click', () => {
      const expanded = row.classList.toggle('expanded');
      const hint = row.querySelector('.flow-mail-expand');
      if (hint) hint.textContent = expanded ? '收起 ▴' : '展开 ▾';
    });
  });
  list.querySelectorAll('.flow-mail-cancel').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const row = btn.closest('.flow-mail-row');
      const itemId = row?.getAttribute('data-item-id') || '';
      const sid = row?.getAttribute('data-sid') || '';
      if (!itemId || !sid) return;
      btn.disabled = true;
      try {
        const resp = await fetch(`/api/teams/mail-queue/${encodeURIComponent(sid)}/${encodeURIComponent(itemId)}`, { method: 'DELETE', headers: authHeaders() });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const remaining = await resp.json();
        // Keep the team-card badge in sync, then reload the section.
        const remainingItems = Array.isArray(remaining) ? remaining : (remaining.items || []);
        setMailPending(sid, remainingItems.length);
        document.dispatchEvent(new CustomEvent('mail-pending-changed'));
        loadPendingSection(body, team);
      } catch (err) {
        btn.disabled = false;
        btn.textContent = t('flowViewers.retry');
      }
    });
  });
  // Count correction: after a successful render, sync the per-session badge to
  // the actual rendered row count so the aggregate badge matches the list.
  const perSession = new Map();
  for (const row of list.querySelectorAll('.flow-mail-row.pending')) {
    const sid = row.getAttribute('data-sid') || '';
    if (sid) perSession.set(sid, (perSession.get(sid) || 0) + 1);
  }
  for (const [sid, cnt] of perSession) setMailPending(sid, cnt);
}

/** Called by flowCanvas on mailQueued/mailDequeued — live-refresh the
 *  Pending section if the mailbox viewer is currently open. */
export function refreshMailboxPending() {
  if (!mailboxCtx) return;
  const body = overlayRoot().querySelector('.flow-viewer-overlay #flow-viewer-body');
  if (!body) { mailboxCtx = null; return; }
  loadPendingSection(body, mailboxCtx.team);
}

export async function openMailbox(flowName, team = null) {
  const body = openViewerShell(`Inbox — ${flowName}`);
  if (!body) return;
  mailboxCtx = { flowName, team };
  body.innerHTML = `
    <div class="flow-mail-section" id="flow-mail-pending-section" style="display:none">
      <div class="flow-mail-section-title"><span class="flow-mail-pending-dot"></span>${t('flowViewers.pending')} <span id="flow-mail-pending-count"></span></div>
      <div id="flow-mail-pending-list"></div>
    </div>
    <div class="flow-mail-section">
      <div class="flow-mail-section-title">${t('flowViewers.history')}</div>
      <div id="flow-mail-history-list"><div class="flow-mail-empty">${t('flowViewers.loading')}</div></div>
    </div>`;
  // Pending: per-session queue items, aggregated across the team's agents.
  loadPendingSection(body, team);
  // History: existing FlowMailStore records (per-team, unchanged).
  const historyList = body.querySelector('#flow-mail-history-list');
  const parentSid = state.activeSessionId || '';
  if (!parentSid) { historyList.innerHTML = `<div class="flow-mail-empty">No active session.</div>`; return; }
  try {
    const resp = await fetch(`/api/teams/mailbox/${encodeURIComponent(parentSid)}/${encodeURIComponent(flowName)}`, { headers: authHeaders() });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    const records = data.records || [];
    if (!historyList.isConnected) return;
    if (records.length === 0) { historyList.innerHTML = `<div class="flow-mail-empty">Inbox is empty.</div>`; return; }
    const sorted = [...records].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
    historyList.innerHTML = sorted.map((r, i) => `
      <div class="flow-mail-row" data-idx="${i}">
        <div class="flow-mail-meta">
          <span class="flow-mail-from">${esc(r.from || '?')}</span>
          <span class="flow-mail-arrow">→</span>
          <span class="flow-mail-to">${esc(r.to || '?')}</span>
          <span class="flow-mail-time">${esc(fmtTime(r.timestamp))}</span>
          <span class="flow-mail-expand">展开 ▾</span>
        </div>
        <div class="flow-mail-content">${mailBodyHtml(r.message)}</div>
      </div>`).join('');
    historyList.querySelectorAll('.flow-mail-row').forEach(row => {
      row.addEventListener('click', () => {
        const expanded = row.classList.toggle('expanded');
        const hint = row.querySelector('.flow-mail-expand');
        if (hint) hint.textContent = expanded ? '收起 ▴' : '展开 ▾';
      });
    });
  } catch (e) { historyList.innerHTML = `<div class="flow-mail-empty">Failed: ${esc(e.message)}</div>`; }
}

// ── Definition editor ──────────────────────────────────────

export async function openDefinition(flowName) {
  const body = openViewerShell(flowName);
  if (!body) return;
  body.innerHTML = `<div class="flow-mail-empty">${t('flowViewers.loading')}</div>`;
  try {
    const resp = await fetch(`/api/teams/def/${encodeURIComponent(flowName)}`, { headers: authHeaders() });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const fd = await resp.json();
    const agents = fd.agents || [];
    const managerName = fd.manager || '';

    function globalDescOf(extendsName) {
      if (!extendsName) return '';
      const ga = (state.agentsData || []).find(a => a.name === extendsName);
      return ga ? (ga.description || '') : '';
    }

    const agentsHtml = agents.map(a => {
      const isMgr = (a.name === managerName) || (a.extends === 'FlowManager');
      const badge = isMgr ? `<span class="flow-agent-block-badge">${t('flowViewers.manager')}</span>` : '';
      const ext = a.extends ? `<span class="flow-agent-block-ext">${t('flowViewers.extends', { name: esc(a.extends) })}</span>` : '';
      const dutyHtml = a.duty ? `<div class="flow-agent-block-field"><span class="flow-agent-block-label">${t('flowViewers.duty')}</span><div class="flow-agent-block-readonly">${esc(a.duty)}</div></div>` : '';
      const ownDesc = a.description || '';
      const inheritedDesc = globalDescOf(a.extends);
      const capabilityDesc = ownDesc || inheritedDesc;
      const sourceLabel = ownDesc ? t('flowViewers.sourceCustom') : inheritedDesc ? t('flowViewers.sourceInherited', { name: esc(a.extends) }) : t('flowViewers.sourceNone');
      const capabilityHtml = `<div class="flow-agent-block-field"><span class="flow-agent-block-label">${t('flowViewers.capability')}</span><div class="flow-def-source">${esc(sourceLabel)}</div><div class="flow-agent-block-readonly">${capabilityDesc ? esc(capabilityDesc) : `<span class="flow-agent-block-empty">${t('flowViewers.empty')}</span>`}</div></div>`;
      const agentTools = a.tools || [];
      const toolsHtml = agentTools.length > 0 ? `<div class="flow-agent-block-field"><span class="flow-agent-block-label">${t('flowViewers.tools')}</span><div class="flow-agent-block-readonly">${esc(agentTools.join(', '))}</div></div>` : '';
      const sysHtml = a.systemPrompt ? `<div class="flow-agent-block-field"><span class="flow-agent-block-label">${t('flowViewers.systemPrompt')}</span><div class="flow-agent-block-readonly" style="max-height:200px;overflow-y:auto">${esc(a.systemPrompt)}</div></div>` : '';
      const modelHtml = `<div class="flow-agent-block-field"><span class="flow-agent-block-label">${t('flowViewers.model')}</span><div class="flow-agent-model-container" data-agent-name="${esc(a.name)}"></div></div>`;
      return `<div class="flow-agent-block${isMgr ? ' is-manager' : ''}"><div class="flow-agent-block-head"><span class="flow-agent-block-name">${esc(a.name)}</span>${badge}${ext}</div>${dutyHtml}${capabilityHtml}${toolsHtml}${modelHtml}${sysHtml}</div>`;
    });

    const flowTabContent = `<div class="flow-def-tab-content active" data-tab-content="flow"><div class="flow-def-section"><h3>${t('flowViewers.teamDescription')}</h3><div class="flow-agent-block-readonly">${esc(fd.description || '')}</div></div></div>`;
    const flowTabBtn = `<button class="flow-def-tab active" data-tab="flow">${t('flowViewers.team')}</button>`;
    const agentTabBtns = agents.map(a => { const isMgr = (a.name === managerName) || (a.extends === 'FlowManager'); return `<button class="flow-def-tab" data-tab="${esc(a.name)}">${esc(a.name)}${isMgr ? '<span class="flow-def-tab-badge">●</span>' : ''}</button>`; }).join('');
    const agentTabContents = agents.map((a, i) => `<div class="flow-def-tab-content" data-tab-content="${esc(a.name)}">${agentsHtml[i] || ''}</div>`).join('');

    body.innerHTML = `<div class="flow-def-tabs">${flowTabBtn}${agentTabBtns}</div>${flowTabContent}${agentTabContents || ''}`;

    body.querySelectorAll('.flow-def-tab').forEach(tab => {
      tab.addEventListener('click', (e) => {
        e.stopPropagation();
        const target = tab.getAttribute('data-tab');
        body.querySelectorAll('.flow-def-tab').forEach(t => t.classList.toggle('active', t === tab));
        body.querySelectorAll('.flow-def-tab-content').forEach(c => c.classList.toggle('active', c.getAttribute('data-tab-content') === target));
      });
    });

    // Async-populate per-agent model config
    body.querySelectorAll('.flow-agent-model-container').forEach(el => {
      populateAgentModel(el, el.dataset.agentName);
    });
  } catch (e) { body.innerHTML = `<div class="flow-mail-empty">Failed: ${esc(e.message)}</div>`; }
}
