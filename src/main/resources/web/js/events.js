// ── events.js — 事件名单点（ask-paradigm-a 批 2026-11-03）──────────────────
// 为什么有这个文件：事件名以前直接内联在 CustomEvent 构造的实参里（存量 113 处 /
// 44 文件，见 scripts/component-contract.mjs R3 基线）。后果：拼错不报错（静默不达）、
// 改名靠全仓搜索、跨面调用靠口头约定。**存量走基线（只降不升），新增一律走本文件**：
//   · 发事件：emit(target, EVENTS.X, detail)      —— 事件名不写字面量
//   · 收事件：target.addEventListener(EVENTS.X, fn)
// 门禁：scripts/check-component-contract.mjs R3 —— 新文件的 CustomEvent 构造若以
// 字符串字面量作事件名即红；实参不含字符串字面量的形态（如 EVENTS.X）永不计入。
// 纪律：**值 = 线上真实事件名，逐字照抄既有树，不许改名**（改名 = 收发两端同时断）。

/**
 * 事件名注册表：键 = 常量名，值 = 既有事件名字符串（逐字核准自当日树）。
 * 冻结：运行期不许改表（新增事件写在这里，别在调用点拼名字）。
 * @type {Readonly<Record<string, string>>}
 */
export const EVENTS = Object.freeze({
  // workspace 面：工作区条目打开（树/搜索/最近三腿共用一个事件）
  WORKSPACE_OPEN_ITEM: 'workspace-open-item',
  // flowMap 社交面：分组/好友缓存变更后重绘两条腿
  FM_GROUPS_CHANGED: 'fm-groups-changed',
  FM_FRIENDS_CHANGED: 'fm-friends-changed',
  // 会话面：某会话需要/撤销「求关注」标记
  SESSION_ATTENTION: 'session-attention',
  // i18n 面：语言切换（content-i18n 重渲染腿）
  LOCALE_CHANGED: 'locale-changed',
  // Delegate mirror refresh and archive notifications.
  DELEGATE_CHAIN_MIRROR_UPDATED: 'delegate-chain-mirror-updated',
  DELEGATE_ARCHIVED: 'delegate-archived',
  // 折叠态：本名在当日树里是**存储键**字面量（branding.js LEGACY_IRREGULAR
  // .task_collapsed），登记它是为了让折叠态通道只有一处拼写来源 —— 将来若升级成
  // 事件，收发两端不必再改名字。
  NEBFLOW_TASK_COLLAPSED: 'nebflow-task-collapsed',
});

/** 常量名 → 事件名的查表别名（供 emit 解析；类型收窄到可索引形态）。 */
/** @type {Record<string, string>} */
const BY_NAME = EVENTS;

/**
 * 发事件：name 传 EVENTS 的键（常量名）则解析成真实事件名，传真实事件名则原样透传。
 * 事件一律 bubbles:true（既有 113 处全是冒泡语义，本表保持一致）。
 * @param {EventTarget} target 派发目标（window / document / 某元素）
 * @param {string} name EVENTS 的键，或已是事件名
 * @param {any} [detail]
 * @returns {boolean} dispatchEvent 的返回值（未被 preventDefault 时 = true）
 */
export function emit(target, name, detail) {
  return target.dispatchEvent(new CustomEvent(BY_NAME[name] ?? name, { detail, bubbles: true }));
}
