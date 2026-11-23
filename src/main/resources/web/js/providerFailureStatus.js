import { h } from './dom.js';
import { t } from './i18n.js';

const surfaces = new WeakMap();

/**
 * 后台任务服务商失败组的**卡片化呈现**（provider-notice-card-unify 批 2026-11-23，
 * 作者令「这些通知不都应该用 llm 卡吗」——对，收编进 llmFailureCard 家族）：
 *   - 卡面走卡族同一套（.option-box .llm-failure-card，check-interaction-card-family 守卫），
 *     不再是裸 details/summary 横幅；
 *   - presentation 由后端给出（族区分呈现 2026-11-21 作者裁定：availability / shape /
 *     config 三型标题不变）；
 *   - 窗口受限型（provider 侧不可用）额外带「窗口受限」徽标与恢复预期行；
 *   - 任务按钮（跳对应后台任务）与逐条 reason 行保留。
 * 群组清空 = 收卡（引擎恢复 ⇒ digest 空 ⇒ aside 摘除——与 llmFailureRecovered 同语义）。
 */

/** presentation → 标题 i18n 键（与后端 ProviderFailureGroups.presentation 同枚举）。 */
function titleKey(presentation) {
  return presentation === 'shape'
    ? 'subagents.providerFailureGroupShape'
    : presentation === 'config'
      ? 'subagents.providerFailureGroupConfig'
      : 'subagents.providerFailureGroup';
}

/** 单个失败组 → 一张 llm-failure-card 卡面（无 dismiss：恢复即整组摘除）。 */
function groupCard(group, items, openTask) {
  const count = group.sessionIds.length;
  const card = h('div', { class: 'option-box llm-failure-card', 'data-card-type': 'providerFailureGroup' });
  // 标题（大字）：N 个任务 · 服务商 不可用/待处理/格式不合规。
  const title = h('div', { class: 'option-q llm-failure-title' }, [
    h('i', { 'data-lucide': 'triangle-alert', class: 'llm-failure-ico llm-failure-ico-title', 'aria-hidden': 'true' }),
    h('span', { text: t(titleKey(group.presentation), { count, provider: group.provider }) }),
  ]);
  // 元信息行：呈现分组徽标（availability=窗口受限/恢复预期行；shape/config 只给分类）。
  const reason = h('div', { class: 'perm-reason llm-failure-reason' }, [
    h('span', { class: 'perm-risk-badge lv-1', text: 'L1' }),
    h('span', { class: 'perm-reason-label', text: group.reason }),
  ]);
  const cardBody = [title, reason];
  if (group.presentation === 'availability') {
    // 服务商不可用族：恢复预期（引擎退避重试/探测恢复，不是任务失败）。
    cardBody.push(h('div', { class: 'llm-failure-summary', text: t('subagents.providerFailureRecovering') }));
  }
  for (const id of group.sessionIds) {
    cardBody.push(h('button', {
      type: 'button', class: 'option-cancel', 'on:click': () => openTask(id),
    }, items.find((i) => i.sessionId === id)?.title || id));
  }
  card.append(...cardBody);
  return card;
}

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
    // 卡片化（2026-11-23）：组 → llm-failure-card 家族卡面；清空 = 收卡。
    node.replaceChildren(...groups.map(group => groupCard(group, items, openTask)));
  }
  if (!groups.length) node.remove();
  else if (node.parentNode !== root) root.appendChild(node);
}
