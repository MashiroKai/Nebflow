// freezeState.js — 冻结态呈现判定（纯函数：零 DOM、零 import、可在 node 里直接判）。
//
// 冻结态误报批（2026-11-05，作者报障逐字：「它显示已冻结，但是我没有冻结它，而且
// 也没有显示恢复按钮」）。根因不在配色，在**语义**：`AgentStatus.Frozen` 一个枚举值
// 承载两类截然不同的处境——
//   ① 系统在等 / 在退避，马上自己好（时间表出窗、LLM/网络退避、供应商探测）；
//   ② 引擎停在这里只能由人决定（循环检测 L2、delegate 错误冻结、已升级到用户）。
// 两者都写「已冻结」时，用户读到的是「有人替我做了一个决定、现在要我去解」，于是
// 既不知道是谁冻的，也找不到恢复按钮。
//
// 判定单点在后端 `nebflow.actor.FreezeDisplay`（同一张表同时驱动线包 frozen/
// agentFrozen 帧、快照 activeAgents 行、AgentControl status 行）。本模块只做「后端
// 判定 → 控制台呈现」的映射，并在**旧后端缺字段**时按 reason 线值回落同一张表
// （回落表是同一张表的退化副本：改表先改后端，再回改这里与 locales）。

/** 冻结呈现分类（与后端 FreezeKind.wire 逐字同值）。 */
export const FREEZE_KIND = {
  /** 系统等待（时间表工作时段）：到点自己恢复。 */
  window: 'waiting-window',
  /** 自动恢复中的退避/重试：到期自动续跑，无需人。 */
  auto: 'auto-recovering',
  /** 需要人决策：引擎不会自己恢复。 */
  manual: 'needs-human',
};

/** 自动恢复族的原因线值（与后端 FreezeDisplay.of 的原因表同集）。 */
const AUTO_REASONS = ['llm-transient', 'network', 'provider-down', 'restart-recovery', 'waiting-network'];
/** 系统等待族的原因线值。 */
const WINDOW_REASONS = ['schedule'];
/** 需人介入族的原因线值。 */
const MANUAL_REASONS = ['loop'];

/**
 * 冻结原因线值归一：后端三个来源同名不同槽——实时帧 `freezeReason`/`reason`、
 * 快照 `freezeReasonWire`、delegate 错误冻结槽 `frozenReason`（`delegate-frozen:*`）。
 * @param {any} info
 * @returns {string} 空串 = 无线索
 */
export function freezeReasonWire(info) {
  const i = info || {};
  const raw = i.freezeReasonWire || i.frozenReason || i.freezeReason || '';
  return String(raw || '').trim();
}

/** Snapshot status 是否是冻结族（AgentStatus.toString）。@param {any} status */
export function isFrozenStatus(status) {
  return String(status || '') === 'Frozen';
}

/**
 * 该行是否属于冻结族（实时标记 `frozen`，或快照 status=Frozen）。
 * 行状态机的判据——**不许只看 `frozen`**：actor 级冻结（循环检测/错误退避）只写
 * registry status，刷新后 `frozen` 标记不在场，只看它会把冻结行画成「进行中」。
 * @param {any} info
 */
export function isFrozenFamily(info) {
  const i = info || {};
  return !!i.frozen || isFrozenStatus(i.status);
}

/**
 * 呈现分类判定（后端字段优先，旧后端缺字段按原因线值回落）。
 * 未知/空原因**不承诺自动恢复**（冻结着却记不下原因 ⇒ 只能人去看）——判词诚实铁律：
 * 宁可说「需人工确认」，不许把长停说成「马上重试」。
 * @param {any} info
 * @returns {string} FREEZE_KIND 之一
 */
export function freezeKindOf(info) {
  const i = info || {};
  const explicit = String(i.freezeKind || '');
  if (explicit === FREEZE_KIND.window || explicit === FREEZE_KIND.auto || explicit === FREEZE_KIND.manual) {
    return explicit;
  }
  if (i.needsHuman === true) return FREEZE_KIND.manual;
  const wire = freezeReasonWire(i);
  if (wire.startsWith('delegate-frozen')) return FREEZE_KIND.manual;
  if (MANUAL_REASONS.includes(wire)) return FREEZE_KIND.manual;
  if (WINDOW_REASONS.includes(wire)) return FREEZE_KIND.window;
  if (AUTO_REASONS.includes(wire)) return FREEZE_KIND.auto;
  if (i.needsHuman === false && (explicit || wire)) return FREEZE_KIND.auto;
  return FREEZE_KIND.manual;
}

/**
 * 是否显示「恢复按钮」：只有需人介入的冻结才给按钮。
 * 自动恢复族给按钮 = 教用户去做一件引擎正在自己做的事（本轮报障的另一面）。
 * @param {string} kind
 */
export function showsRecoveryAction(kind) {
  return kind === FREEZE_KIND.manual;
}

/**
 * 徽标词 i18n key（按分类；reason 有专属词的再由 `freezeReasonLabelKey` 补后缀）。
 * @param {string} kind
 * @returns {string}
 */
export function freezeBadgeKey(kind) {
  if (kind === FREEZE_KIND.window) return 'subagents.freezeWindow';
  if (kind === FREEZE_KIND.auto) return 'subagents.freezeAuto';
  return 'subagents.frozenBadge';
}

/**
 * 原因短名的 i18n key（缺席 = 无后缀，徽标只显示分类词）。
 * @param {string} wire
 * @returns {string}
 */
export function freezeReasonLabelKey(wire) {
  const w = String(wire || '');
  if (w.startsWith('delegate-frozen')) {
    const code = w.replace(/^delegate-frozen:?/, '');
    if (code === 'llm-failure') return 'subagents.frozenReasonLlm';
    if (code === 'tool-failure') return 'subagents.frozenReasonTool';
    if (code === 'engine-fault') return 'subagents.frozenReasonEngine';
    if (code === 'cancelled-by-quota') return 'subagents.frozenReasonQuota';
    return '';
  }
  if (w === 'loop') return 'subagents.freezeReasonLoop';
  if (w === 'llm-transient') return 'subagents.freezeReasonLlmTransient';
  if (w === 'network') return 'subagents.freezeReasonNetwork';
  if (w === 'provider-down') return 'subagents.freezeReasonProviderDown';
  if (w === 'restart-recovery') return 'subagents.freezeReasonRestartRecovery';
  if (w === 'waiting-network') return 'subagents.freezeReasonWaitingNetwork';
  if (w === 'schedule') return 'subagents.freezeReasonSchedule';
  return '';
}

/**
 * 行/弹窗上一次可见的冻结事实是否可被「恢复动作」解除（回执收口的判据）。
 * @param {any} info
 */
export function freezeNeedsHuman(info) {
  return showsRecoveryAction(freezeKindOf(info));
}
