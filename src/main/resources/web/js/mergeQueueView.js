// mergeQueueView.js — 合并窗「排队位次」载荷解析单点（queuepos 批 2026-09-15）。
//
// 为什么独立成模块：主图卡（flowMapTab.js）与详情面板（flowMapArchive.js）都要读同一份
// 排队视图，而 flowMapTab → flowMapArchive 已是单向依赖（后者不可反向 import）⇒ 共享解析
// 落在本叶子模块（无循环依赖）。**渲染**仍在两处各自的文件里（卡 = flowMapTab.js，
// 详情行 = flowMapArchive.js），本模块只做「载荷 → 只读视图」的解析与降级判定。
//
// 两个引擎条件键（契约见 src/main/scala/nebflow/core/project/ProjectTypes.scala）：
//   mergeQueue    = 闸判据「**谁挡着我**」：{ahead, inSection[], rank, holders[],
//                   sameKeyProjects?}——`ahead` = 阻塞集合的**势**（holders.length），
//                   **不是**位次：同刻只有一个 running 时全体排队者 `ahead ≡ 1`，
//                   这正是作者现场报的「前面还有几个都一样」。既有键语义**逐字冻结**。
//   mergeQueuePos = 队列序「**我排第几**」：{position, total, queue, arrived, readyAt,
//                   createdAt, rank, sameKeyProjects?}——位次**逐节点唯一**，真源 = SEM-2
//                   次序键 `rank=(readyAt,createdAt,id)` 升序；**仅同队竞争者 ≥2 时携带**
//                   （含队首：队首没人挡它，所以它不会有 mergeQueue，但会有本键）。
//
// 🔴 前端**只渲染不派生**：位次数字一律取载荷 `mergeQueuePos.position`（禁本地复算 rank /
//    持有者 / 准入过滤——判据持有方 = 引擎单点，前端复刻必静默漂移）；🔴 禁读文件票层
//    （.nebflow/locks/main-merge.queue）、🔴 禁从事件流回放（事件流是审计面）。
// 🔴 降级红线（逐字沿用）：键缺失 / 形状漂移 / 不可计算 / 同键多项目（O-1）⇒ **不渲染
//    数字**（至多裸「排队中」）；🔴 禁编造数字；🔴 禁把「阻塞数」（ahead）当位次渲染。

import { t } from './i18n.js';

/** 队列名 token → locale 键（引擎给 token，前端只翻译不派生；未知 token 原样回显）。 */
/** @type {Record<string, string>} */
const QUEUE_NAME_KEYS = {
  'merge-window': 'flowmap.queue.name.merge-window',
};

/**
 * 队列名可读化（未知 token 原样回显——🔴 不编造、不隐藏：名字由引擎给）。
 * @param {any} token
 * @returns {string}
 */
export function queueNameLabel(token) {
  const tk = String(token == null ? '' : token);
  const key = QUEUE_NAME_KEYS[tk];
  return key ? t(key) : tk;
}

/**
 * 解析排队视图（**导出 = fixture 验证面**）。
 * @param {any} n 节点载荷对象
 * @returns {?{ahead: ?number, holders: Array<{name: string, status: string}>, pos: ?{position: number, total: number, queue: string}, untrusted: boolean}} null = 不渲染。
 */
export function mergeQueueView(n) {
  if (!n || typeof n !== 'object') return null;
  const q = n.mergeQueue;
  const qp = n.mergeQueuePos;
  // 持有者清单（既有键；形状漂移防御与旧版逐字同款：非对象 / 数组 / 无 holders 数组 /
  // holders 全非法 ⇒ 空清单，零 console 噪音）。
  const qOk = !!q && typeof q === 'object' && !Array.isArray(q);
  const raw = qOk && Array.isArray(/** @type {any} */ (q).holders) ? /** @type {any} */ (q).holders : [];
  const holders = raw
    .filter((/** @type {any} */ h) => h && typeof h === 'object' && !Array.isArray(h))
    .map((/** @type {any} */ h) => ({ name: String(h.name || h.id || ''), status: String(h.status || '') }))
    .filter((/** @type {any} */ h) => h.name !== '');
  // 位次（新键）：position/total 必须是正整数且 position ≤ total；否则视为**不可计算**
  // ⇒ 不渲染数字（🔴 禁猜、禁由 ahead 顶替）。
  const pos = (() => {
    if (!qp || typeof qp !== 'object' || Array.isArray(qp)) return null;
    const p = /** @type {any} */ (qp).position;
    const m = /** @type {any} */ (qp).total;
    if (!Number.isInteger(p) || !Number.isInteger(m) || p < 1 || m < 1 || p > m) return null;
    return { position: p, total: m, queue: String(/** @type {any} */ (qp).queue || '') };
  })();
  // 两条键都没有可用信息 ⇒ 不渲染（与旧版「非排队节点零徽标零脚注」逐字一致）。
  if (!holders.length && !pos) return null;
  // ahead 只在「正整数」时可用；缺键 / 0 / 负 / 非整数 / 与持有者数不符 ⇒ 视为不可计算
  // ⇒ 裸「排队中」（数字面一律不猜：🔴 禁编造数字）。
  const aheadRaw = qOk ? /** @type {any} */ (q).ahead : null;
  const ahead = (Number.isInteger(aheadRaw) && aheadRaw > 0 && aheadRaw === holders.length) ? aheadRaw : null;
  const foreign = [
    ...(qOk && Array.isArray(/** @type {any} */ (q).sameKeyProjects) ? /** @type {any} */ (q).sameKeyProjects : []),
    ...(qp && typeof qp === 'object' && !Array.isArray(qp) && Array.isArray(/** @type {any} */ (qp).sameKeyProjects)
      ? /** @type {any} */ (qp).sameKeyProjects : []),
  ].filter((/** @type {any} */ p) => typeof p === 'string' && p !== '');
  return { ahead, holders, pos, untrusted: foreign.length > 0 };
}
