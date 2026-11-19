// termWords.js — 身份/类型徽标词**单源**（术语统一批 2026-11-06 · 作者令逐字
// 「统一 根据英文」）。
//
// 作者口径（本文件就是它的执行面）：
//   ① 身份/类型徽标的规范词面 = **英文原词**，逐字小写/原样：
//      kernel / dispatcher / node / subagent / delegate / AgentFlow。中文面不再把
//      「内核 / 分发器 / 节点 / 子代理」当徽标词（正文叙述句不在本令范围——徽标位与
//      叙事句是两件事，判据见 scripts/check-badge-words.mjs 的文件头边界）。
//   ② **单一词表单源**：徽标词只从本文件取（BADGE_WORDS 一张表 + 两个取词函数）。
//      任务列表 / 后台智能体面板 / 后台任务面板 / 手机层全部消费它，别处不许再写
//      一遍字面量，也不许再开第二张 locale 键表（task.mode.* / subagents.kind* 已于
//      本批整族退役）。
//   ③ 同一概念不得有两个词面：本表一个概念一行；协议 kind / store source / 会话 id
//      前缀这些**数据词**经由 badgeFaceOfKind() 单点归一，渲染面不做二次判断。
//
// 为什么要一张常量表而不是 locale 键：徽标词是品牌/机制术语（作者术语，逐字保留、
// 不翻译），不是可本地化文案。放 locale ⇒ zh/en 各一份 = 天然的两张表（漂移面），
// 且中文语言包天然会把它们译成中文——正是本批要收掉的形态。
//
// 判据（机器）：scripts/check-badge-words.mjs（`make badge-words` / CI）+ 真渲染
// 回归 tests/term-unify-badge-words.spec.mjs（W1-W5）。

/** 徽标词表（键 = 概念；值 = 上屏词面）。**唯一一处定义**。 */
export const BADGE_WORDS = Object.freeze({
  kernel: 'kernel',
  dispatcher: 'dispatcher',
  node: 'node',
  subagent: 'subagent',
  delegate: 'delegate',
  agentflow: 'AgentFlow',
});

/**
 * 取徽标词面（值域外的键 ⇒ 空串 = 零徽标，无信息胜过错信息）。
 * @param {string} concept 表内概念键
 * @returns {string}
 */
export function badgeWord(concept) {
  const k = String(concept || '');
  return Object.prototype.hasOwnProperty.call(BADGE_WORDS, k) ? BADGE_WORDS[k] : '';
}

/**
 * 数据词 → 徽标词面（**单点归一**）：引擎协议 kind（`SubTask` / `Flow` /
 * `AgentFlow` / `flow-step`）、store 条目 source（`subagent` / `subtask` /
 * `delegate`）、会话 id 前缀判读结果（`Subagent` / `Delegate` …）都从这里过一道。
 *
 * 两条语义（与术语分层批一脉相承，不是本批新造）：
 *   · `subtask` 与 `delegate` 是同一概念（修订批九点②：SubTask 显示词更名 delegate
 *     语义）⇒ 同一词面 `delegate`；
 *   · 成员位的整体词（`Flow` / `AgentFlow` / `flow-step`）读作成员自身——成员是
 *     `node`，整体词只在整体行身份词位出现（见 bgAgents.js bgAgentflowWord）。
 *
 * 值域外的数据词原样返回（不猜、不改写：例如 `Team` / `Ephemeral` / `External`
 * 不在本批六词裁定内，保持数据词原样上屏）。
 * @param {string} kind 数据词（协议 kind / source / 前缀判读词）
 * @returns {string}
 */
export function badgeFaceOfKind(kind) {
  const w = String(kind || '');
  if (w === 'Kernel' || w === 'kernel') return badgeWord('kernel');
  if (w === 'Dispatcher' || w === 'dispatcher') return badgeWord('dispatcher');
  if (w === 'Subagent' || w === 'subagent') return badgeWord('subagent');
  if (w === 'Delegate' || w === 'delegate' || w === 'SubTask' || w === 'subtask') return badgeWord('delegate');
  if (w === 'Node' || w === 'node' || w === 'Flow' || w === 'AgentFlow' || w === 'flow-step') return badgeWord('node');
  return w;
}

/** 只在成员位成立的徽标词（非成员行不得带；见 bgTaskRowHtml 的顶层摘词门）。 */
/** @type {Set<string>} */
const MEMBER_ONLY = new Set([BADGE_WORDS.delegate, BADGE_WORDS.subagent]);

/**
 * 该词面是否**只属于成员位**（delegate / subagent）——顶层组头/孤儿行不得携带。
 * @param {string} face @returns {boolean}
 */
export function isMemberOnlyBadge(face) {
  return MEMBER_ONLY.has(String(face || ''));
}
