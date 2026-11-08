import { h } from './dom.js';
import { t } from './i18n.js';
import { showConfirm, canShowConfirm } from './modal.js';
import { onMessage, onReconnect, sendWs } from './ws.js';
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
  const merge = h('button', { type: 'button', class: 'project-link-btn', disabled: true, 'on:click': async () => {
    if (!selected.size || submitting) return;
    submitting = true;
    updateMerge();
    requestId ||= crypto.randomUUID();
    try {
      const result = await submitProjectMerge(name, requestId, [...selected]);
      selected.clear();
      requestId = null;
      await refresh();
      message.textContent = t(result.notificationPending ? 'project.mergeNotificationPending'
        : (result.mergerLive ? 'project.mergeSubmitted' : 'project.mergeNeedsResume'));
    } catch (error) { message.textContent = error.message; }
    finally { submitting = false; updateMerge(); }
  } }, t('project.mergeSelected'));
  const detail = h('details', { class: 'project-workspaces' }, [
    h('summary', {}, t('project.workspaces')), form, workspaceList,
    h('p', { class: 'project-workspaces-meta' }, t('project.mergeScope')), merge, message, requestList, merger, openMerger, resumeMerger,
  ]);
  card.appendChild(detail);
  function updateMerge() { merge.disabled = submitting || !selected.size || !currentData?.merger; }

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
        const clean = h('button', { type: 'button', class: 'project-link-btn' }, t('project.cleanupWorkspace'));
        const element = h('li', { class: 'project-workspaces-row' }, [h('label', {}, [checkbox, title]), status, base, clean]);
        row = { element, checkbox, title, status, base, clean, workspace };
        clean.addEventListener('click', () => cleanup(row.workspace, clean));
        rows.set(workspace.id, row);
        workspaceList.appendChild(element);
      }
      row.workspace = workspace;
      row.checkbox.setAttribute('aria-label', workspace.branch);
      row.checkbox.disabled = !data.merger || workspace.lifecycle !== 'open' || workspace.activity !== 'idle';
      if (row.checkbox.disabled) selected.delete(workspace.id);
      row.checkbox.checked = selected.has(workspace.id);
      row.title.textContent = workspace.branch;
      const cleanupState = data.cleanups?.[workspace.id];
      row.status.textContent = cleanupState?.removed && workspace.lifecycle === 'merged-cleanup-pending'
        ? t('project.cleanupNeedsAcceptance')
        : `${phaseLabel(workspace.lifecycle)} · ${phaseLabel(workspace.activity)} · delegate:${workspace.owner}`;
      if (cleanupState?.error) row.status.textContent += ` · ${cleanupState.error}`;
      row.base.textContent = `${workspace.baseBranch} · ${workspace.baseCommit}`;
      row.clean.disabled = cleanupPending.has(workspace.id) || !!cleanupState?.removed || workspace.activity !== 'idle' || !['open', 'merged-cleanup-pending', 'cleaning'].includes(workspace.lifecycle);
    }
    for (const [id, row] of rows) if (!alive.has(id)) { row.element.remove(); rows.delete(id); selected.delete(id); }
    empty.hidden = alive.size > 0;
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
