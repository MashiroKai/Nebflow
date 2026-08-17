// flowList.js — Static Flow list page (P6).
// Renders all flow definitions from GET /api/flows/list as cards with a
// "View DAG" action that opens a static solar-system preview tab.

import { openTab, getTabPane } from './canvas.js';
import { esc, authHeaders } from './flowHelpers.js';
import { renderFlowDefStatic } from './flowDag.js';
import { FLOW_CSS } from './flowCss.js';
import { brand } from './brand.js';

// ── API ────────────────────────────────────────────────────
async function fetchFlows() {
  try {
    const resp = await fetch('/api/flows/list', { headers: authHeaders() });
    if (!resp.ok) return [];
    const data = await resp.json();
    return data.flows || [];
  } catch (e) { return []; }
}

// ── Render ─────────────────────────────────────────────────
export async function renderFlowList(scroll) {
  scroll.innerHTML = '<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">Loading flows…</div></div>';
  const flows = await fetchFlows();

  if (flows.length === 0) {
    scroll.innerHTML = `<div class="dag-empty"><div style="font:600 14px -apple-system;color:var(--color-text-muted)">No flows defined</div><div class="hint">Create ~/${brand.homeDirName ?? '.nebflow'}/flows/&lt;name&gt;/flow.json to define a DAG pipeline</div></div>`;
    return;
  }

  scroll.innerHTML = `
    <div class="flow-list-header">
      <span class="flow-list-title">Flows</span>
      <span class="flow-list-count">${flows.length} defined</span>
    </div>` +
    flows.map(flowDefCardHtml).join('');

  scroll.querySelectorAll('.flow-def-view-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const name = btn.getAttribute('data-flow') || '';
      openFlowDefTab(name);
    });
  });
}

function flowDefCardHtml(f) {
  const nodes = f.nodes || [];
  const entryTag = `<span class="flow-def-entry-tag">entry: ${esc(f.entry || '')}</span>`;
  return `
    <div class="flow-def-card">
      <div class="flow-def-card-header">
        <span class="flow-def-card-icon">⚡</span>
        <span class="flow-def-card-name">${esc(f.name)}</span>
        <span class="flow-def-card-meta">
          <span>${nodes.length} nodes</span>
          <span>maxLoop ${esc(f.maxLoop ?? '∞')}</span>
        </span>
      </div>
      <div class="flow-def-card-desc">${esc(f.description || '')}</div>
      <div class="flow-def-card-footer">
        ${entryTag}
        <button class="flow-def-view-btn" data-flow="${esc(f.name)}" style="margin-left:auto">View DAG →</button>
      </div>
    </div>`;
}

// ── Static DAG preview tab ─────────────────────────────────
async function openFlowDefTab(name) {
  openTab(`flow-def-${name}`, name, { type: 'flow-def', closable: true, pinned: true });
  const pane = getTabPane(`flow-def-${name}`);
  if (!pane) return;
  if (!pane.querySelector('#team-canvas-style')) {
    pane.insertAdjacentHTML('afterbegin', FLOW_CSS);
  }

  const scroll = document.createElement('div');
  scroll.className = 'team-scroll';
  scroll.style.display = 'block';
  pane.appendChild(scroll);
  scroll.innerHTML = '<div class="dag-empty"><div class="hint">Loading DAG…</div></div>';

  const flows = await fetchFlows();
  const flowDef = flows.find(f => f.name === name);
  renderFlowDefStatic(scroll, flowDef);
}

// Test hook
if (typeof window !== 'undefined') {
  window.__testFlowList = { renderFlowList, openFlowDefTab };
}
