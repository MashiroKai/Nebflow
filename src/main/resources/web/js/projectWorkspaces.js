import { h } from './dom.js';
import { t } from './i18n.js';
import { fetchProjectWorkspaces, saveProjectBaseline } from './nodeData.js';

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
      const rows = (data.workspaces || []).map(w => h('li', {}, [
        h('strong', {}, w.branch),
        h('p', {}, `${labels[w.lifecycle] || w.lifecycle} · ${labels[w.activity] || w.activity} · delegate:${w.owner}`),
        h('p', {}, `${w.baseBranch} · ${w.baseCommit}`),
      ]));
      content.replaceChildren(form, h('ul', {}, rows.length ? rows : [h('li', {}, t('project.noWorkspaces'))]));
      if (data.merger) content.appendChild(h('p', {}, `Merger · delegate:${data.merger.delegateId}`));
    } catch (error) {
      if (current === generation) content.textContent = error.message;
    }
  }
  detail.addEventListener('toggle', () => { if (detail.open) refresh(); });
}
