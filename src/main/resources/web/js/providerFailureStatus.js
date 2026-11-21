import { h } from './dom.js';
import { t } from './i18n.js';

const surfaces = new WeakMap();

/** One replaceable status surface per conversation; never append notification bubbles. */
export function renderProviderFailureStatus(view, digest, openTask) {
  const root = view?.dom?.chat;
  if (!root) return;
  let surface = surfaces.get(root);
  if (!surface) {
    const node = h('aside', { role: 'status', 'aria-live': 'polite' });
    surface = { node, signature: null };
    const observer = new MutationObserver(() => {
      const current = surfaces.get(root);
      if (current?.context && node.parentNode !== root) {
        renderProviderFailureStatus(...current.context);
      }
    });
    observer.observe(root, { childList: true });
    surfaces.set(root, surface);
  }
  surface.context = [view, digest, openTask];
  const { node } = surface;
  const items = (digest?.items || []).filter(i =>
    i.parentSessionId === view.sessionId || i.sessionId === view.sessionId);
  const ids = new Set(items.map(i => i.sessionId));
  const groups = (digest?.providerGroups || []).map(group => ({
    ...group, sessionIds: (group.sessionIds || []).filter(id => ids.has(id)),
  })).filter(group => group.sessionIds.length);
  const signature = JSON.stringify([view.sessionId, groups, items.map(item => [item.sessionId, item.title])]);
  if (signature !== surface.signature) {
    surface.signature = signature;
    node.replaceChildren(...groups.map(group => h('details', {}, [
      // 族区分呈现（2026-11-21 作者裁定）：同 bgAgents —— presentation 由后端给出。
      h('summary', {}, t(group.presentation === 'shape'
        ? 'subagents.providerFailureGroupShape'
        : group.presentation === 'config'
          ? 'subagents.providerFailureGroupConfig'
          : 'subagents.providerFailureGroup', {
        count: group.sessionIds.length, provider: group.provider,
      })),
      h('p', {}, group.reason),
      ...group.sessionIds.map(id => h('button', {
        type: 'button', 'on:click': () => openTask(id),
      }, items.find(i => i.sessionId === id)?.title || id)),
    ])));
  }
  if (!groups.length) node.remove();
  else if (node.parentNode !== root) root.appendChild(node);
}
