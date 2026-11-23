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

/**
 * 服务商名解析（2026-11-23 ④）：后端 `providerGroups[].provider` 为 `unknown` 时，
 * 正文（reason）若携带 `provider/model` 形状，按与后端 ProviderFailureGroups.ProviderRef
 * 同一判据（[tried]/[skipped]/受限：/failed: 前置）读回 provider 段；全未命中维持 unknown。
 * 只读展示，不改后端分组键。
 */
const PROVIDER_REF = /(?:\[(?:tried|skipped)\]\s*|受限：|failed:\s*)([A-Za-z0-9_.-]+)\/[A-Za-z0-9_.-]+/;
export function resolveProviderLabel(group) {
  const raw = String(group?.provider || '');
  if (raw && raw !== 'unknown') return raw;
  const m = PROVIDER_REF.exec(String(group?.reason || ''));
  return m ? m[1] : (raw || 'unknown');
}

/** presentation → 标题 i18n 键（与后端 ProviderFailureGroups.presentation 同枚举）。 */
function titleKey(presentation) {
  return presentation === 'shape'
    ? 'subagents.providerFailureGroupShape'
    : presentation === 'config'
      ? 'subagents.providerFailureGroupConfig'
      : 'subagents.providerFailureGroup';
}

/**
 * 过期判据（项 3 · 2026-11-23）：会话行已被恢复信号（delegateWoken / 唤醒回执）显式置为
 * `frozen === false` ⇒ 该会话的失败状态已过期。行缺席或 `frozen` 未知 ⇒ 视为存活（保守：
 * 快照缺字段时不误灰）。纯函数：isLive 由调用方注入（真实读 state.sessionBgAgents）。
 */
export function partitionFailureSessions(sessionIds, isExpired) {
  const live = [];
  const expired = [];
  for (const id of sessionIds || []) (isExpired(id) ? expired : live).push(id);
  return { live, expired };
}

/**
 * 从面板行表读过期态（项 3）：会话 sid 的所有行里，只要有一行 `frozen === true` ⇒ 仍存活（重新冻结
 * 覆盖旧恢复）；否则有任一行带 `freezeRecovered === true`（恢复信号 delegateWoken / 唤醒回执
 * 显式打的标记）⇒ 过期。注意：不用 `frozen === false`——agentStart 新建的普通行默认即 false，
 * 会把所有存活会话误判为已恢复。行缺席或无标记 ⇒ 存活（保守）。
 * @param {Record<string, Record<string, {sessionId?: string, frozen?: boolean, freezeRecovered?: boolean}>>} sessionBgAgents
 * @param {string} sid
 */
export function isSessionFrozenExpired(sessionBgAgents, sid) {
  const want = String(sid || '').replace(/^team-/, '');
  let cleared = false;
  for (const agents of Object.values(sessionBgAgents || {})) {
    for (const info of Object.values(agents || {})) {
      if (String((info && info.sessionId) || '').replace(/^team-/, '') !== want) continue;
      if (info.frozen === true) return false;
      if (info.freezeRecovered === true) cleared = true;
    }
  }
  return cleared;
}

/** 单个失败组 → 一张 llm-failure-card 卡面（无 dismiss：恢复即整组摘除）。
 *  项 3：恢复信号到达后，已过期会话转灰（.llm-failure-card--expired / 按钮 .is-expired）；
 *  全组过期 ⇒ 标题改「已恢复（已过期）」、撤掉「等待恢复中」文案，卡保留以告知结果。 */
function groupCard(group, items, openTask, isExpired) {
  const { live, expired } = partitionFailureSessions(group.sessionIds, isExpired);
  const allExpired = live.length === 0 && expired.length > 0;
  const count = group.sessionIds.length;
  const card = h('div', {
    class: 'option-box llm-failure-card' + (allExpired ? ' llm-failure-card--expired' : ''),
    'data-card-type': 'providerFailureGroup',
    'data-expired-count': String(expired.length),
  });
  // 标题（大字）：N 个任务 · 服务商 不可用/待处理/格式不合规。
  const titleText = allExpired
    ? t('subagents.providerFailureExpired', { count, provider: resolveProviderLabel(group) })
    : t(titleKey(group.presentation), { count, provider: resolveProviderLabel(group) });
  const title = h('div', { class: 'option-q llm-failure-title' }, [
    h('i', { 'data-lucide': 'triangle-alert', class: 'llm-failure-ico llm-failure-ico-title', 'aria-hidden': 'true' }),
    h('span', { text: titleText }),
  ]);
  // 元信息行：呈现分组徽标（availability=窗口受限/恢复预期行；shape/config 只给分类）。
  const reason = h('div', { class: 'perm-reason llm-failure-reason' }, [
    h('span', { class: 'perm-risk-badge lv-1', text: 'L1' }),
    h('span', { class: 'perm-reason-label', text: group.reason }),
  ]);
  const cardBody = /** @type {HTMLElement[]} */ ([title, reason]);
  if (group.presentation === 'availability' && !allExpired) {
    // 服务商不可用族：恢复预期（引擎退避重试/探测恢复，不是任务失败）。全组过期后撤掉。
    cardBody.push(h('div', { class: 'llm-failure-summary', text: t('subagents.providerFailureRecovering') }));
  }
  for (const id of group.sessionIds) {
    // 会话标题回填（2026-11-23）：点开时把批次名（digest 条目 title）一并交给弹窗；
    // 只传 id 时弹窗头退到 agentStart 的 def 名（「subagent」）。
    const title = items.find((i) => i.sessionId === id)?.title || '';
    const gone = expired.includes(id);
    cardBody.push(h('button', {
      type: 'button', class: 'option-cancel' + (gone ? ' is-expired' : ''),
      'aria-disabled': gone ? 'true' : null,
      'on:click': () => openTask(id, title),
    }, title || id));
  }
  card.append(...cardBody);
  return card;
}

/** One replaceable status surface per conversation; never append notification bubbles. */
export function renderProviderFailureStatus(view, digest, openTask, isExpired = (_id) => false) {
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
  surface.context = [view, digest, openTask, isExpired];
  const { node } = surface;
  const items = (digest?.items || []).filter(i =>
    i.parentSessionId === view.sessionId || i.sessionId === view.sessionId);
  const ids = new Set(items.map(i => i.sessionId));
  const groups = (digest?.providerGroups || []).map(group => ({
    ...group, sessionIds: (group.sessionIds || []).filter(id => ids.has(id)),
  })).filter(group => group.sessionIds.length);
  // 过期态入签名：恢复信号到达（过期集合变化）必须触发重绘，否则卡仍显示「等待恢复中」。
  const expiredIds = [...ids].filter(id => isExpired(id)).sort();
  const signature = JSON.stringify([view.sessionId, groups, items.map(item => [item.sessionId, item.title]), expiredIds]);
  if (signature !== surface.signature) {
    surface.signature = signature;
    // 卡片化（2026-11-23）：组 → llm-failure-card 家族卡面；清空 = 收卡。
    node.replaceChildren(...groups.map(group => groupCard(group, items, openTask, isExpired)));
  }
  if (!groups.length) node.remove();
  else if (node.parentNode !== root) root.appendChild(node);
}
