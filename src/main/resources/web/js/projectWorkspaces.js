import { h } from './dom.js';
import { t } from './i18n.js';
import { showConfirm, showToast, canShowConfirm } from './modal.js';
import { onMessage, onReconnect, sendWs } from './ws.js';
import { prefillProjectPrompt } from './projectTab.js';
import {
  fetchProjectWorkspaces, saveProjectBaseline, submitProjectMerge, cancelProjectMerge,
  previewProjectWorkspaceCleanup, cleanProjectWorkspace,
} from './nodeData.js';

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
onMessage('delegateWakeResult', message => {
  for (const instance of instances) {
    if (!instance.connected()) { instances.delete(instance); continue; }
    instance.wakeResult(message);
  }
});

function phaseLabel(phase) {
  const basic = {
    open: 'workspaceOpen', running: 'workspaceRunning', idle: 'workspaceIdle', frozen: 'workspaceFrozen',
    cleaning: 'workspaceCleaning', 'closed-merged': 'workspaceClosedMerged', 'closed-discarded': 'workspaceClosedRetained',
  };
  return t(basic[phase] ? `project.${basic[phase]}` : `project.mergePhase.${phase}`);
}

/** Readable facts for one workspace row: ahead / dirty chips, verification, reconcile badge. */
function factsOf(data, workspace) {
  const facts = data.facts?.[workspace.id] || {};
  const chips = [];
  let dirtyText = '';
  if (typeof facts.aheadCount === 'number') chips.push(t('project.factsAhead', { count: facts.aheadCount }));
  if (typeof facts.dirtyTracked === 'number' && typeof facts.dirtyUntracked === 'number') {
    dirtyText = facts.dirtyTracked || facts.dirtyUntracked
      ? t('project.factsDirty', { tracked: facts.dirtyTracked, untracked: facts.dirtyUntracked })
      : t('project.factsClean');
    chips.push(dirtyText);
  }
  if (facts.error) chips.push(t('project.factsError'));
  return { facts, chips, dirtyText };
}

function verificationLabel(facts) {
  const proof = facts.lastVerification;
  if (proof && proof.status === 'passed') return { text: t('project.factsVerified'), cls: 'is-verified' };
  return { text: t('project.factsUnverified'), cls: 'is-unverified' };
}

function reconcileLabel(facts) {
  const rows = Array.isArray(facts.reconcile) ? facts.reconcile : [];
  if (!rows.length) return null;
  const readings = rows.map(row => row.reading);
  if (readings.includes('resumable')) return { text: t('project.factsReconcileResumable'), cls: 'is-resumable' };
  if (readings.every(r => r === 'integrated')) return { text: t('project.factsReconcileIntegrated'), cls: 'is-verified' };
  return { text: t('project.factsReconcileUnknown'), cls: 'is-unknown' };
}

/** Keyed rows preserve focus, edits and selection while authoritative snapshots change. */
export function mountProjectWorkspaces(card, name) {
  if (mounted.has(card)) return mounted.get(card);
  let revision = -1;
  let fetchGeneration = 0;
  let baselineDirty = false;
  let submitting = false;
  let requestId = null;
  let currentData = null;
  const selected = new Set();
  const cleanupPending = new Set();
  const rows = new Map();
  const requests = new Map();
  const message = h('p', { role: 'status', class: 'project-workspaces-message' });
  const branch = h('input', { required: true, 'aria-label': t('project.creationBaseline'),
    'on:input': () => { baselineDirty = true; } });
  const save = h('button', { type: 'submit', class: 'project-link-btn' }, t('project.saveBaseline'));
  const form = h('form', { class: 'project-workspaces-baseline', 'on:submit': async event => {
    event.preventDefault();
    save.disabled = true;
    try {
      const result = await saveProjectBaseline(name, branch.value.trim());
      baselineDirty = false;
      branch.value = result.baseline;
      message.textContent = t('project.baselineSaved');
      await refresh();
    } catch (error) { message.textContent = error.message; }
    finally { save.disabled = false; }
  } }, [h('label', {}, [t('project.creationBaseline'), branch]), save]);
  const empty = h('li', {}, t('project.noWorkspaces'));
  const workspaceList = h('ul', { class: 'project-workspaces-list' }, [empty]);
  const requestList = h('div', { class: 'project-workspaces-requests' });
  const merger = h('p', { class: 'project-workspaces-meta' });
  const openMerger = h('button', { type: 'button', class: 'project-link-btn', hidden: true,
    'on:click': () => {
      const id = currentData?.merger?.delegateId;
      if (id) import('./bgAgentPopup.js').then(module => module.openStepPopup(id));
    } }, t('project.openMerger'));
  const resumeMerger = h('button', { type: 'button', class: 'project-link-btn', hidden: true,
    'on:click': () => {
      const id = currentData?.merger?.delegateId;
      if (!id) return;
      resumeMerger.disabled = true;
      sendWs({ type: 'delegateWake', sessionId: id, instruction: '' });
    } }, t('project.resumeMerger'));
  // No merger yet: one click composes the exact creation instruction into the main
  // chat input (nothing is sent automatically — the same seam as the empty-state CTA).
  const createMerger = h('button', { type: 'button', class: 'project-link-btn', hidden: true,
    'on:click': () => {
      if (!prefillProjectPrompt(t('project.createMergerPrefill', { name }))) {
        message.textContent = t('project.createMergerUnavailable');
      }
    } }, t('project.createMerger'));
  const merge = h('button', { type: 'button', class: 'project-link-btn', disabled: true,
    'on:click': () => { confirmMerge(); } }, t('project.mergeSelected'));
  const detail = h('details', { class: 'project-workspaces' }, [
    h('summary', {}, t('project.workspaces')), form, workspaceList,
    h('p', { class: 'project-workspaces-meta' }, t('project.mergeScope')), merge, message, requestList, merger, createMerger, openMerger, resumeMerger,
  ]);
  card.appendChild(detail);
  function updateMerge() { merge.disabled = submitting || !selected.size || !currentData?.merger; }

  /** Confirm dialog: pinned source SHA + target branch + current facts per selected workspace. */
  function confirmMerge() {
    if (!selected.size || submitting) return;
    if (!canShowConfirm()) { message.textContent = t('project.cleanupConfirmUnavailable'); return; }
    const lines = [...selected].map(id => {
      const workspace = currentData?.workspaces?.find(w => w.id === id);
      if (!workspace) return null;
      const { facts, dirtyText } = factsOf(currentData, workspace);
      const ahead = typeof facts.aheadCount === 'number' ? t('project.factsAhead', { count: facts.aheadCount }) : t('project.factsError');
      return t('project.mergeConfirmEntry', {
        branch: workspace.branch, target: workspace.baseBranch,
        commit: String(facts.headCommit || workspace.baseCommit || '').slice(0, 12),
        ahead, dirty: dirtyText,
      });
    }).filter(Boolean);
    const body = [...lines, '', t('project.mergeScope')].join('\n');
    showConfirm(t('project.mergeConfirmTitle'), body, () => submitMerge(), { confirmLabel: t('project.mergeConfirmConfirm') });
  }

  /** Submit the authorized request; stable requestId retries the identical scope. */
  async function submitMerge() {
    if (!selected.size || submitting) return;
    submitting = true;
    updateMerge();
    requestId ||= crypto.randomUUID();
    try {
      const result = await submitProjectMerge(name, requestId, [...selected]);
      selected.clear();
      requestId = null;
      await refresh();
      const text = t(result.notificationPending ? 'project.mergeNotificationPending'
        : (result.mergerLive ? 'project.mergeSubmitted' : 'project.mergeNeedsResume'));
      message.textContent = text;
      showToast(text, 'success');
    } catch (error) {
      // Server-side pin/occupancy/revision failures: surface loudly, refresh the
      // snapshot so expired selections drop out of the panel.
      message.textContent = error.message;
      showToast(error.message, 'error');
      await refresh();
    } finally { submitting = false; updateMerge(); }
  }

  async function cleanup(workspace, button) {
    if (cleanupPending.has(workspace.id)) return;
    cleanupPending.add(workspace.id);
    button.disabled = true;
    try {
      const preview = await previewProjectWorkspaceCleanup(name, workspace.id);
      if (preview.alreadyRemoved) { await refresh(); message.textContent = t('project.cleanupDone'); return; }
      if (!canShowConfirm()) throw new Error(t('project.cleanupConfirmUnavailable'));
      const text = [
        t('project.cleanupScope', { path: preview.path, branch: preview.branch }),
        preview.unmerged ? t('project.cleanupUnmerged') : t('project.cleanupIntegrated'),
        preview.changes?.length ? t('project.cleanupDirty', { count: preview.changes.length }) + '\n' + preview.changes.slice(0, 12).join('\n') : '',
      ].filter(Boolean).join('\n\n');
      showConfirm(t('project.cleanupTitle'), text, async () => {
        if (cleanupPending.has(workspace.id)) return;
        cleanupPending.add(workspace.id);
        button.disabled = true;
        try {
          await cleanProjectWorkspace(name, workspace.id, {
            previewToken: preview.previewToken, confirmUnmerged: !!preview.unmerged,
            discardUncommitted: !!preview.changes?.length,
          });
          await refresh();
          message.textContent = t('project.cleanupDone');
        } catch (error) { message.textContent = error.message; }
        finally {
          cleanupPending.delete(workspace.id);
          if (currentData) apply(currentData);
          await refresh();
        }
      }, { confirmLabel: t('project.cleanupConfirm') });
    } catch (error) { message.textContent = error.message; await refresh(); }
    finally {
      cleanupPending.delete(workspace.id);
      if (currentData) apply(currentData);
    }
  }

  function apply(data) {
    const incoming = Number(data.revision) || 0;
    if (incoming < revision) return;
    revision = incoming;
    currentData = data;
    if (!baselineDirty) branch.value = data.baseline || 'main';
    const alive = new Set();
    let expired = 0;
    for (const workspace of data.workspaces || []) {
      alive.add(workspace.id);
      let row = rows.get(workspace.id);
      if (!row) {
        const checkbox = h('input', { type: 'checkbox', 'on:change': event => {
          if (event.target.checked) selected.add(workspace.id); else selected.delete(workspace.id);
          requestId = null;
          updateMerge();
        } });
        const title = h('span');
        const status = h('p', { class: 'project-workspaces-meta' });
        const base = h('p', { class: 'project-workspaces-meta' });
        const chips = h('p', { class: 'project-ws-chips' });
        const openOwner = /** @type {HTMLButtonElement} */ (h('button', { type: 'button', class: 'project-link-btn' }, t('project.continueWork')));
        openOwner.addEventListener('click', () => {
          openOwner.disabled = true;
          import('./bgAgentPopup.js').then(module => {
            module.openStepPopup(row.workspace.owner);
            openOwner.disabled = false;
          }).catch(() => { openOwner.disabled = false; });
        });
        const clean = h('button', { type: 'button', class: 'project-link-btn' }, t('project.cleanupWorkspace'));
        const element = h('li', { class: 'project-workspaces-row' }, [
          h('label', {}, [checkbox, title]), status, base, chips,
          h('p', { class: 'project-ws-actions' }, [openOwner, clean]),
        ]);
        row = { element, checkbox, title, status, base, chips, openOwner, clean, workspace };
        clean.addEventListener('click', () => cleanup(row.workspace, clean));
        rows.set(workspace.id, row);
        workspaceList.appendChild(element);
      }
      row.workspace = workspace;
      row.checkbox.setAttribute('aria-label', workspace.branch);
      row.checkbox.disabled = !data.merger || workspace.lifecycle !== 'open' || workspace.activity !== 'idle';
      if (row.checkbox.disabled && selected.has(workspace.id)) { selected.delete(workspace.id); expired += 1; }
      row.checkbox.checked = selected.has(workspace.id);
      row.title.textContent = workspace.branch;
      const { facts, chips: factChips } = factsOf(data, workspace);
      const verification = verificationLabel(facts);
      const reconcile = reconcileLabel(facts);
      row.chips.replaceChildren(...[
        ...factChips.map(text => h('span', { class: 'project-ws-chip' }, text)),
        h('span', { class: `project-ws-chip ${verification.cls}` }, verification.text),
        ...(reconcile ? [h('span', { class: `project-ws-chip ${reconcile.cls}` }, reconcile.text)] : []),
      ]);
      const cleanupState = data.cleanups?.[workspace.id];
      row.status.textContent = cleanupState?.removed && workspace.lifecycle === 'merged-cleanup-pending'
        ? t('project.cleanupNeedsAcceptance')
        : `${phaseLabel(workspace.lifecycle)} · ${phaseLabel(workspace.activity)} · delegate:${workspace.owner}`;
      if (cleanupState?.error) row.status.textContent += ` · ${cleanupState.error}`;
      row.base.textContent = `${workspace.baseBranch} · ${workspace.baseCommit}`;
      row.openOwner.hidden = workspace.lifecycle !== 'open';
      row.clean.disabled = cleanupPending.has(workspace.id) || !!cleanupState?.removed || workspace.activity !== 'idle' || !['open', 'merged-cleanup-pending', 'cleaning'].includes(workspace.lifecycle);
    }
    for (const [id, row] of rows) if (!alive.has(id)) { row.element.remove(); rows.delete(id); selected.delete(id); }
    empty.hidden = alive.size > 0;
    if (expired > 0 && !submitting) message.textContent = t('project.selectionExpired', { count: expired });
    const activeRequests = new Set();
    for (const request of data.mergeRequests || []) {
      activeRequests.add(request.id);
      let row = requests.get(request.id);
      if (!row) {
        const summary = h('summary');
        const items = h('ul');
        const status = h('p', { role: 'status' });
        const stop = h('button', { type: 'button', class: 'project-link-btn' }, t('project.mergeStop'));
        const element = h('details', {}, [summary, items, stop, status]);
        row = { element, summary, items, status, stop };
        stop.addEventListener('click', async () => {
          stop.disabled = true;
          try { await cancelProjectMerge(name, request.id); status.textContent = t('project.mergeStopRequested'); }
          catch (error) { status.textContent = error.message; stop.disabled = false; }
        });
        requests.set(request.id, row);
        requestList.appendChild(element);
      }
      row.summary.textContent = `${t('project.mergeRequest')} · ${request.id} · ${phaseLabel(request.status)}`;
      row.items.replaceChildren(...(request.items || []).map(item => h('li', {}, `${item.workspaceId} · ${phaseLabel(item.phase)} · ${item.mergeCommit || ''}`)));
      row.stop.hidden = ['completed', 'cancelled'].includes(request.status);
      row.stop.disabled = !!request.cancelRequested;
      if (request.cancelRequested) row.status.textContent = t('project.mergeStopRequested');
    }
    for (const [id, row] of requests) if (!activeRequests.has(id)) { row.element.remove(); requests.delete(id); }
    merger.textContent = data.merger ? `Merger · delegate:${data.merger.delegateId}` : t('project.mergerRequired');
    createMerger.hidden = !!data.merger;
    openMerger.hidden = !data.merger;
    resumeMerger.hidden = !data.merger || data.mergerInitialization?.status !== 'failed';
    resumeMerger.disabled = false;
    if (data.mergerInitialization?.status === 'initializing') merger.textContent += ` · ${t('project.mergerInitializing')}`;
    if (data.mergerInitialization?.status === 'failed') {
      merger.textContent += ` · ${t('project.mergerRecoveryRequired')}`;
      if (data.mergerInitialization.error) merger.textContent += ` · ${data.mergerInitialization.error}`;
    }
    updateMerge();
  }
  async function refresh() {
    const generation = ++fetchGeneration;
    try {
      const data = await fetchProjectWorkspaces(name);
      if (card.isConnected) apply(data);
    } catch (error) { if (generation === fetchGeneration) message.textContent = error.message; }
  }
  function wakeResult(result) {
    if (result.sessionId !== currentData?.merger?.delegateId) return;
    resumeMerger.disabled = false;
    message.textContent = result.ok ? t('project.mergerRecoveryAccepted') : result.error || t('project.mergerRecoveryRequired');
    refresh();
  }
  const instance = { refresh, wakeResult, revision: () => revision, connected: () => card.isConnected, open: () => detail.open };
  mounted.set(card, instance);
  instances.add(instance);
  detail.addEventListener('toggle', () => { if (detail.open) refresh(); });
  return instance;
}
