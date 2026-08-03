// flowTeams.js — Team panel rendering: glass cards with agent tiles.
//
// Each mounted team is a glass card with a header (name, rules, mailbox buttons)
// and an agent grid. Clicking an agent tile opens a chat popup.

import { openStepPopup } from './flowAgentPopup.js';
import { esc, overlayRoot } from './flowHelpers.js';

export function statusOf(agent, agentStatus) {
  const live = agent.sessionId ? agentStatus.get(agent.sessionId) : null;
  const s = (live || agent.status || 'idle').toLowerCase();
  if (s === 'running' || s === 'busy') return 'running';
  return 'idle';
}

export function flowCardHtml(flow, agentStatus, mailFlash) {
  const agents = flow.agents || [];
  const running = agents.filter(a => statusOf(a, agentStatus) === 'running').length;
  const summaryText = running > 0 ? `${running} running` : `${agents.length} idle`;
  const summaryCls = running > 0 ? 'running' : '';

  const tilesHtml = agents.map(a => {
    const st = statusOf(a, agentStatus);
    const isManager = !!a.manager;
    const flash = a.sessionId && mailFlash.has(a.sessionId) ? ' mail-flash' : '';
    const role = isManager ? 'manager' : st;
    return `
      <div class="flow-tile ${st}${isManager ? ' manager' : ''}${flash}"
           data-flow="${esc(flow.name)}"
           data-agent="${esc(a.name)}"
           data-sid="${esc(a.sessionId || '')}"
           title="${esc(a.duty || a.description || a.name)}">
        <div class="flow-tile-dot"></div>
        <div class="flow-tile-name">${esc(a.name)}</div>
        <div class="flow-tile-role">${role}</div>
      </div>`;
  }).join('');

  return `
    <div class="flow-card">
      <div class="flow-card-header">
        <div class="flow-card-title" data-flow="${esc(flow.name)}" data-act="def" title="View team definition">${esc(flow.name)}</div>
        <div class="flow-card-actions">
          <button class="flow-act-btn" data-act="rules" data-flow="${esc(flow.name)}" title="Team rules"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="15" y2="17"/></svg></button>
          <button class="flow-act-btn" data-act="mailbox" data-flow="${esc(flow.name)}" title="Inbox"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg></button>
        </div>
        <div class="flow-card-summary ${summaryCls}"><span class="dot"></span>${summaryText}</div>
      </div>
      <div class="flow-agents">${tilesHtml}</div>
    </div>`;
}

export function renderTeamsPanel(scroll, flows, agentStatus, mailFlash) {
  if (flows.length === 0) {
    scroll.innerHTML = `<div class="flow-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No active teams</div><div class="hint">Mount a team to see its agents</div></div>`;
    return;
  }
  scroll.innerHTML = flows.map(flow => flowCardHtml(flow, agentStatus, mailFlash)).join('');
}

export function bindTileClicks() {
  document.querySelectorAll('.flow-tile').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      const flowName = el.getAttribute('data-flow') || '';
      const agentName = el.getAttribute('data-agent') || '';
      const sid = el.getAttribute('data-sid') || null;
      openStepPopup(`${flowName}/${agentName}`, agentName, agentName, flowName, sid || null);
    });
  });
}

export function bindCardActions(openMailbox, openRules, openDefinition) {
  document.querySelectorAll('.flow-act-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const act = btn.getAttribute('data-act');
      const flowName = btn.getAttribute('data-flow') || '';
      if (act === 'mailbox') openMailbox(flowName);
      else if (act === 'rules') openRules(flowName);
    });
  });
  document.querySelectorAll('.flow-card-title[data-act="def"]').forEach(el => {
    el.addEventListener('click', (e) => {
      e.stopPropagation();
      openDefinition(el.getAttribute('data-flow') || '');
    });
  });
}
