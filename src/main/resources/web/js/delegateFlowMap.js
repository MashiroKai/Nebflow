// delegateFlowMap.js — delegate chain → flowmap view driver (L1 per-task map).
//
// Re-drives the flowmap engine (flowMapTab.js — camera/zoom/pan, edges, chain
// folding, detail panel, incremental diff animations) with delegate-plane data
// from the delegateChainStub read port. The engine's per-project REST fetch is
// replaced through the opts.fetchView seam; its WS listeners stay inert for
// these scopes (scope keys are namespaced `delegate-chain:<key>` and no node
// frames carry those project names). Re-renders are triggered by the provider
// mirror event (delegate-chain-mirror-updated) — frames-as-triggers only, REST
// mirror remains the authority.
//
// View projection (single point, zero fabrication):
//   - root node = the delegate row itself (id = delegateId, status = row status
//     verbatim incl. ready-to-merge, worktree/mergeQueue fields pass through);
//   - sub-agent children (G3 listDelegateChildren) = root's direct spawn edges
//     (root.out → child, solid result-style lines);
//   - AgentFlow steps (chain.nodes kind 'flow-step') keep their structural
//     deps (in-chain ids only — dangling refs dropped); entry steps (no
//     in-chain dep) additionally hang off the root via its out edge, so the
//     chain reads root → flow layer by layer;
//   - the whole unit is one flowmap chain (id = read-port key) with the root as
//     its declared entry — expanded: the root card carries the fold chevron;
//     collapsed: members fold into the aggregate chain card. control:false
//     marks the scope as ChainLedger-free (no cancel affordance).
//   - Node click = the flowmap detail card (flowMapArchive .fm-detail), camera
//     focuses the card — same interaction as the classic map.
import { renderFlowMapInto, highlightFlowMapNode, dropFlowMapScope, registerFlowMapPaneResolver } from './flowMapTab.js';
import { getChainByTask, listDelegateChildren, listDelegateRows } from './delegateChainStub.js';
// flowAnim.js is a side-effect module (MutationObserver-driven orbit phase
// continuity + running→terminal coast). It carries no imports and no API; the
// map face needs it for the classic orbit animations.
import './flowAnim.js';

/** Scope namespace for delegate-chain maps (keeps fmByProject/detail caches
 *  disjoint from per-project scopes; node WS frames never carry these names). */
const SCOPE_PREFIX = 'delegate-chain:';
export function delegateMapScope(key) { return `${SCOPE_PREFIX}${String(key || '')}`; }

/** Drop the engine-side caches of a delegate scope (view navigation/teardown). */
export function dropDelegateMapScope(key) {
  dropFlowMapScope(delegateMapScope(key));
}

// Pane-resolver registration (module side effect): the engine's fold /
// view-filter re-render path finds live delegate map hosts through here —
// chain-fold chevrons on delegate maps re-render the right container.
registerFlowMapPaneResolver(() => {
  const out = [];
  document.querySelectorAll('.canvas-tab-pane[data-delegate-mode="task"][data-delegate-key]')
    .forEach((el) => {
      const pane = /** @type {HTMLElement} */ (el);
      const host = pane.querySelector('.delegate-flowmap-host');
      if (host && host.isConnected) {
        out.push({ project: delegateMapScope(pane.dataset.delegateKey), container: host });
      }
    });
  return out;
});

/** Child store status → flowmap node status domain. */
function childNodeStatus(c) {
  const s = String(c && c.status || '');
  if (s === 'completed' || s === 'failed' || s === 'cancelled') return s;
  return 'running'; // running | restarting | unknown → running side
}

/**
 * Build the fm view ({nodes, chains, meta}) for one delegate chain.
 * @param {string} key read-port key (taskNo ‖ delegateId)
 * @returns {any|null} null when the chain is unknown (caller shows fail-soft)
 */
export function buildDelegateFm(key) {
  const chain = getChainByTask(key);
  if (!chain) return null;
  const row = listDelegateRows()
    .find((r) => r && String(r.taskNo || r.delegateId || '') === String(key)) || null;
  const delegateId = String(chain.delegateId || (row && row.delegateId) || key);
  const title = String((row && (row.title || row.delegateId)) || chain.taskId || delegateId);
  const taskNo = String((row && row.taskNo) || '');

  const chainNodes = Array.isArray(chain.nodes) ? chain.nodes : [];
  const flowSteps = chainNodes.filter((n) => n && n.kind === 'flow-step');
  const flowIds = new Set(flowSteps.map((n) => String(n.id)));

  // Root node: delegate row verbatim (status/word domain already matches the
  // node domain). The chain's own delegate-kind node, when present, carries the
  // backend-projected status — it wins over the row projection (same source
  // family, fresher scope).
  const rootFromChain = chainNodes.find((n) => n && n.kind === 'delegate') || null;
  const rootId = String((rootFromChain && rootFromChain.id) || delegateId);
  const rootStatus = String((rootFromChain && rootFromChain.status)
    || (row && row.status) || 'running');
  const rootName = [taskNo ? `#${taskNo}` : '', title].filter(Boolean).join(' ');
  /** @type {any} */
  const root = {
    id: rootId,
    name: rootName || delegateId,
    status: rootStatus,
    description: '',
    agent: String((row && row.executor) || ''),
    createdAt: (row && row.createdAt) || undefined,
    hasWorktree: !!(chain.worktree && (chain.worktree.worktree || chain.worktree.cwd)),
    worktree: chain.worktree ? String(chain.worktree.worktree || chain.worktree.cwd || '') : '',
    mergeQueue: row && row.mergeQueue ? row.mergeQueue : undefined,
    mergeQueuePos: row && row.mergeQueuePos ? row.mergeQueuePos : undefined,
    in: [], deps: [], out: [],
  };

  // Sub-agent children: direct spawn lines root → child.
  const kids = delegateId ? listDelegateChildren(delegateId) : [];
  const childNodes = [];
  const rootOut = [];
  for (const c of kids) {
    const c0 = c && typeof c === 'object' ? c : {};
    const id = String(c0.taskId || '');
    if (!id || id === rootId) continue;
    childNodes.push({
      id,
      name: String(c0.agentName || '') || id,
      status: childNodeStatus(c0),
      description: String(c0.description || c0.prompt || ''),
      createdAt: typeof c0.spawnedAt === 'number' ? c0.spawnedAt : undefined,
      completedAt: typeof c0.completedAt === 'number' && c0.completedAt > 0 ? c0.completedAt : undefined,
      in: [], deps: [], out: [],
    });
    rootOut.push({ to: id, on: ['pass'], mode: 'result' });
  }

  // Flow steps: structural deps (in-chain only); entry steps hang off the root
  // (the chain belongs to the delegate — the entry edge is a view projection,
  // not engine data).
  const flowNodes = [];
  for (const n of flowSteps) {
    const id = String(n.id || '');
    if (!id || id === rootId) continue;
    const deps = (Array.isArray(n.deps) ? n.deps : [])
      .map((d) => String(d || '')).filter((d) => flowIds.has(d) && d !== id);
    flowNodes.push({
      id,
      name: String(n.label || n.id || ''),
      status: String(n.status || 'pending'),
      description: String(n.description || ''),
      in: [], deps, out: [],
    });
    if (!deps.length) rootOut.push({ to: id, on: ['pass'], mode: 'result' });
  }
  root.out = rootOut;

  const nodes = [root, ...childNodes, ...flowNodes];
  const memberIds = nodes.map((n) => n.id);
  return {
    nodes,
    chains: [{
      id: String(key), title, entries: [rootId], ends: [], memberIds,
      control: false, // ChainLedger-free scope: no cancel affordance on cards
    }],
    meta: { project: delegateMapScope(key), updatedAt: Date.now() },
  };
}

/**
 * Render (or diff-update) the delegate chain's flowmap into a host element.
 * Camera/zoom/pan, edge drawing, fold controls, detail cards and the diff
 * animations all come from the engine; repeated calls diff against the
 * previous render of the same host (no jump when nothing changed).
 * @param {HTMLElement} host container inside the L1 pane
 * @param {string} key read-port key
 * @param {{highlightNodeId?: string}} [opts]
 */
export function renderDelegateFlowMap(host, key, opts = {}) {
  if (!host) return;
  const scope = delegateMapScope(key);
  renderFlowMapInto(host, scope, {
    fetchView: () => Promise.resolve(buildDelegateFm(key)),
    highlightNodeId: String((opts && opts.highlightNodeId) || '') || undefined,
  });
}

/** Camera focus + flash on a member node of an already-rendered map
 *  (auto-unfolds the chain when folded). */
export function highlightDelegateMapNode(host, key, nodeId) {
  highlightFlowMapNode(host, delegateMapScope(key), nodeId);
}
