// chainDetailEntry.js — live GUI entry for the Flow Map node detail layer
// inside the delegate chain view (S10 per-task view).
//
// Why this module exists: the per-node changed-file diff view (diffView.js /
// diffRender.js) mounts into the Flow Map node detail panel (.fm-detail), whose
// floating layer (archive FAB + panel + detail) is created by
// flowMapArchive.ensureArchiveLayer. After the in-project flow-map view was
// retired the layer had no live navigation entry left in the running build, so
// the diff view was unreachable by pure GUI (file-diff verifier defect ①).
// This module re-attaches the SAME single-source layer to the delegate chain
// view and wires its node cards to the detail — no second detail or diff
// implementation, no store mutation.
//
// Data contract: chain nodes come from the delegate chain read port
// (delegateChainStub ChainNode). When the engine's delegate-scoped projection
// is present (GET /api/chains/:key → chain.nodes) those ids are real Flow Map
// node ids, so the detail's changed-file section can resolve
// GET /api/projects/<project>/flow-map/nodes/<nodeId>/file-changes. With only
// the fallback single-delegate node (id = delegateId) the report fetch simply
// 404s and the section silently stays absent — the detail still opens.
// `project` is the row's mounted project name ('' = no project context: the
// layer still opens, remote fetches fail soft to empty states).

import { openDetailForMember, syncArchiveUi } from './flowMapArchive.js';

/** ChainNode (delegateChainStub) → ChainMember (flowMapArchive detail payload).
 *  Field mapping only — unknown fields stay absent, nothing is fabricated. */
function memberOfChainNode(n) {
  const src = n && typeof n === 'object' ? n : {};
  return {
    id: String(src.id || ''),
    name: String(src.label || src.name || src.id || ''),
    status: String(src.status || ''),
    description: String(src.description || ''),
  };
}

/**
 * Attach the archive floating layer to a rendered chain view and wire node
 * cards to open the detail panel (completed nodes mount their changed-file
 * diff section inside it). Idempotent: the layer is keyed by its host pane and
 * reused across re-renders; cards are bound once per element.
 *
 * @param {HTMLElement} container element inside the chain view pane (the
 *        layer's host is resolved as the enclosing .canvas-tab-pane)
 * @param {string} project project context for report/archive fetches
 * @param {Array<{id: string, label?: string, name?: string, status?: string,
 *        description?: string}>} [nodes] chain node payloads backing the cards
 */
export function attachChainDetailLayer(container, project, nodes) {
  if (!container || !container.isConnected) return;
  try { syncArchiveUi(container, String(project || '')); } catch (_) { /* fail-soft */ }
  const byId = new Map();
  for (const n of (Array.isArray(nodes) ? nodes : [])) {
    const m = memberOfChainNode(n);
    if (m.id) byId.set(m.id, m);
  }
  for (const cardEl of container.querySelectorAll('.fm-node[data-node-id]')) {
    const card = /** @type {HTMLElement} */ (cardEl);
    // 两层架构批（§C）：根卡/subagent 卡的详情承载 = 对话窗（data-flow-popup-card）
    // ——file-diff 详情层只属 flow-step 卡，跳过对话窗承载卡。
    if (card.dataset.flowPopupCard === '1') continue;
    const member = byId.get(String(card.getAttribute('data-node-id') || ''));
    if (!member || card.dataset.chainDetailBound === '1') continue;
    card.dataset.chainDetailBound = '1';
    card.classList.add('chain-detail-openable');
    card.addEventListener('click', (e) => {
      e.stopPropagation();
      try { openDetailForMember(container, String(project || ''), member); } catch (_) { /* fail-soft */ }
    });
  }
}
