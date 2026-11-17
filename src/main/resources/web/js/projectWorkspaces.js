import { h } from './dom.js';
import { t } from './i18n.js';
import { onMessage, onReconnect } from './ws.js';
import { fetchProjectWorkspaces } from './nodeData.js';

// Read-only legacy ledger view. The managed-workspace mechanism is retired:
// this panel renders what the old mechanism recorded (workspace rows as
// captured) and performs no actions — no baseline writes, no merge requests,
// no cleanup, no merger lifecycle. Data source = GET /api/projects/:name/
// workspaces (engine-side read face over the recorded history).
const mounted = new WeakMap();
const instances = new Set();
function refreshOpen(revision) {
  for (const instance of instances) {
    if (!instance.connected()) { instances.delete(instance); continue; }
    if (instance.open() && (revision === undefined || revision > instance.revision())) instance.refresh();
  }
}
onMessage('workspaceLedgerUpdated', message => refreshOpen(Number(message.revision)));
onReconnect(() => refreshOpen());

function phaseLabel(phase) {
  const basic = {
    open: 'workspaceOpen', running: 'workspaceRunning', idle: 'workspaceIdle', frozen: 'workspaceFrozen',
    cleaning: 'workspaceCleaning', 'closed-merged': 'workspaceClosedMerged', 'closed-discarded': 'workspaceClosedRetained',
  };
  return t(basic[phase] ? `project.${basic[phase]}` : `project.mergePhase.${phase}`);
}

/** Keyed rows preserve focus while authoritative snapshots change. */
export function mountProjectWorkspaces(card, name) {
  if (mounted.has(card)) return mounted.get(card);
  let revision = -1;
  let fetchGeneration = 0;
  const rows = new Map();
  const empty = h('li', {}, t('project.noWorkspaces'));
  const workspaceList = h('ul', { class: 'project-workspaces-list' }, [empty]);
  const detail = h('details', { class: 'project-workspaces' }, [
    h('summary', {}, t('project.workspaces')), workspaceList,
  ]);
  card.appendChild(detail);

  function apply(data) {
    const incoming = Number(data.revision) || 0;
    if (incoming < revision) return;
    revision = incoming;
    const alive = new Set();
    for (const workspace of data.workspaces || []) {
      alive.add(workspace.id);
      let row = rows.get(workspace.id);
      if (!row) {
        const title = h('span');
        const status = h('p', { class: 'project-workspaces-meta' });
        const base = h('p', { class: 'project-workspaces-meta' });
        const element = h('li', { class: 'project-workspaces-row' }, [title, status, base]);
        row = { element, title, status, base };
        rows.set(workspace.id, row);
        workspaceList.appendChild(element);
      }
      row.title.textContent = workspace.branch;
      row.status.textContent =
        `${phaseLabel(workspace.lifecycle)} · ${phaseLabel(workspace.activity)} · delegate:${workspace.owner}`;
      const cleanupState = data.cleanups?.[workspace.id];
      if (cleanupState?.error) row.status.textContent += ` · ${cleanupState.error}`;
      row.base.textContent = `${workspace.baseBranch} · ${workspace.baseCommit}`;
    }
    for (const [id, row] of rows) if (!alive.has(id)) { row.element.remove(); rows.delete(id); }
    empty.hidden = alive.size > 0;
  }
  async function refresh() {
    const generation = ++fetchGeneration;
    try {
      const data = await fetchProjectWorkspaces(name);
      if (card.isConnected) apply(data);
    } catch { /* read face only — transient failures keep the last snapshot */ }
  }
  const instance = { refresh, revision: () => revision, connected: () => card.isConnected, open: () => detail.open };
  mounted.set(card, instance);
  instances.add(instance);
  detail.addEventListener('toggle', () => { if (detail.open) refresh(); });
  return instance;
}
