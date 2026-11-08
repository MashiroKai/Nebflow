import { h } from './dom.js';
import { t } from './i18n.js';
import { fetchProjectWorkspaces, saveProjectBaseline, submitProjectMerge, cancelProjectMerge } from './nodeData.js';

const mounted = new WeakSet();

/** Embedded in the existing project card; task-chain navigation stays in place. */
export function mountProjectWorkspaces(card, name) {
  if (mounted.has(card)) return;
  mounted.add(card);
  const content = h('div');
  const detail = h('details', {}, [h('summary', {}, t('project.workspaces')), content]);
  card.appendChild(detail);
  let generation = 0;
  async function refresh() {
    const current = ++generation;
    content.textContent = t('project.workspacesLoading');
    try {
      const data = await fetchProjectWorkspaces(name);
      if (current !== generation || !card.isConnected) return;
      const branch = h('input', { value: data.baseline || 'main', required: true,
        'aria-label': t('project.creationBaseline') });
      const message = h('p', { role: 'status' });
      const save = h('button', { type: 'submit' }, t('project.saveBaseline'));
      const form = h('form', { 'on:submit': async event => {
        event.preventDefault();
        save.disabled = true;
        try {
          const result = await saveProjectBaseline(name, branch.value.trim());
          branch.value = result.baseline;
          message.textContent = t('project.baselineSaved');
        } catch (error) { message.textContent = error.message; }
        finally { save.disabled = false; }
      } }, [h('label', {}, [t('project.creationBaseline'), branch]), save, message]);
      const labels = {
        open: t('project.workspaceOpen'), reserved: t('project.workspaceReserved'),
        running: t('project.workspaceRunning'), idle: t('project.workspaceIdle'), frozen: t('project.workspaceFrozen'),
      };
      const selection = new Set();
      const mergeMessage = h('p', { role: 'status' });
      const mergeButton = h('button', { type: 'button', disabled: true }, t('project.mergeSelected'));
      let requestId = null;
      mergeButton.addEventListener('click', async () => {
        if (!selection.size) return;
        mergeButton.disabled = true;
        requestId ||= crypto.randomUUID();
        try {
          const result = await submitProjectMerge(name, requestId, [...selection]);
          await refresh();
          content.appendChild(h('p', { role: 'status' }, t(result.notificationPending ? 'project.mergeNotificationPending' : (result.mergerLive ? 'project.mergeSubmitted' : 'project.mergeNeedsResume'))));
        } catch (error) {
          mergeMessage.textContent = error.message;
          mergeButton.disabled = false;
        }
      });
      Object.assign(labels, Object.fromEntries([
        'accepted', 'processing', 'paused', 'checking', 'merging', 'validating', 'awaiting-user',
        'merged-cleanup-pending', 'completed', 'failed', 'cancelled', 'reserved',
      ].map(phase => [phase, t(`project.mergePhase.${phase}`)])));
      const rows = (data.workspaces || []).map(w => h('li', {}, [
        h('label', {}, [h('input', {
          type: 'checkbox', disabled: !data.merger || w.lifecycle !== 'open' || w.activity !== 'idle',
          'aria-label': w.branch,
          'on:change': event => {
            if (event.target.checked) selection.add(w.id); else selection.delete(w.id);
            requestId = null;
            mergeButton.disabled = !selection.size;
          },
        }), w.branch]),
        h('strong', {}, w.branch),
        h('p', {}, `${labels[w.lifecycle] || w.lifecycle} · ${labels[w.activity] || w.activity} · delegate:${w.owner}`),
        h('p', {}, `${w.baseBranch} · ${w.baseCommit}`),
      ]));
      content.replaceChildren(form, h('ul', {}, rows.length ? rows : [h('li', {}, t('project.noWorkspaces'))]),
        h('p', {}, t('project.mergeScope')), mergeButton, mergeMessage);
      if (!data.merger) content.appendChild(h('p', {}, t('project.mergerRequired')));
      for (const request of data.mergeRequests || []) {
        const status = h('p', { role: 'status' }, request.cancelRequested ? t('project.mergeStopRequested') : '');
        const stop = h('button', { type: 'button', disabled: !!request.cancelRequested,
          'on:click': async () => {
            stop.disabled = true;
            try {
              await cancelProjectMerge(name, request.id);
              status.textContent = t('project.mergeStopRequested');
            } catch (error) { status.textContent = error.message; stop.disabled = false; }
          },
        }, t('project.mergeStop'));
        content.appendChild(h('details', {}, [
          h('summary', {}, `${t('project.mergeRequest')} · ${request.id} · ${labels[request.status] || request.status}`),
          h('ul', {}, (request.items || []).map(item => h('li', {},
            `${item.workspaceId} · ${labels[item.phase] || item.phase} · ${item.mergeCommit || ''}`))),
          !['completed', 'cancelled'].includes(request.status) ? stop : null, status,
        ]));
      }
      if (data.merger) content.appendChild(h('p', {}, `Merger · delegate:${data.merger.delegateId}`));
    } catch (error) {
      if (current === generation) content.textContent = error.message;
    }
  }
  detail.addEventListener('toggle', () => { if (detail.open) refresh(); });
}
