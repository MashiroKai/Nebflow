// askTimeDivider.js — canvas Ask 会话的时间分隔线：分段与标签的**纯函数**单点。
//
// 作者令（功能 · canvas ask 会话显示时间分隔线）：每段连续会话之间居中一条细横线，
// 线上是时间（微信式）。口径：
//   ① 会话开头显示起始时间；
//   ② 相邻两条带时间的消息间隔 > TIME_DIVIDER_GAP_MS（5 分钟）⇒ 新段，段界插分隔线；
//   ③ 标签：当天 HH:mm；昨天 「昨天 HH:mm」；本年其它日 「MM-DD HH:mm」；跨年带年份；
//   ④ 样式见 ask.css `.ask-time-divider*`（与 ask 面板既有视觉一致，不抢内容）。
//
// 时间源 = 消息既有的时间戳字段（客户端行 `ts`；服务端行 `timestamp` 若下发）。不新建状态。
// 无时间戳的行不参与分段（既不起段也不断段）——旧历史行因此按现状渲染，零回归。
//
// 本模块**零 import**（不碰 DOM / localStorage / i18n）：时钟格式、文案、「现在」都由调用方
// 注入，所以 Node 门禁（scripts/check-ask-time-divider.mjs）可直接真跑。

/** 段界阈值（批内可调）。 */
export const TIME_DIVIDER_GAP_MS = 5 * 60 * 1000;

/**
 * 取一条转录行的时间戳（epoch 毫秒）；缺席 / 非正数 / 非有限数 ⇒ 0（= 无时间）。
 * @param {any} m
 * @returns {number}
 */
export function msgTs(m) {
  if (!m) return 0;
  const v = typeof m.ts === 'number' ? m.ts : typeof m.timestamp === 'number' ? m.timestamp : 0;
  return Number.isFinite(v) && v > 0 ? v : 0;
}

/**
 * 算分隔线位置：返回一组「可见行下标」，每个下标处之前插一条分隔线。
 * 规则：第一条带时间的行必插（会话开头）；之后每条带时间行与「上一条带时间行」
 * 间隔 > gapMs 即插。无时间行不参与。
 * @param {any[]} rows 可见转录行（role !== 'sys'）
 * @param {number} [gapMs]
 * @returns {Set<number>}
 */
export function timeDividerIndices(rows, gapMs = TIME_DIVIDER_GAP_MS) {
  const at = new Set();
  let prev = 0;
  rows.forEach((m, i) => {
    const t = msgTs(m);
    if (!t) return;
    if (!prev || t - prev > gapMs) at.add(i);
    prev = t;
  });
  return at;
}

/**
 * 分隔线标签（本地日历日口径，时区安全：按 Y/M/D 比较，不按 24h 差）。
 * @param {number} ts 分隔线所属消息的时间（epoch ms）
 * @param {number} nowMs 「现在」（epoch ms，由调用方注入）
 * @param {(ms:number)=>string} fmtHm 时分格式化（调用方注入，遵循 12/24h 偏好）
 * @param {(zh:string,en:string)=>string} tt 双语文案选择器
 * @returns {string}
 */
export function timeDividerLabel(ts, nowMs, fmtHm, tt) {
  const d = new Date(ts);
  const n = new Date(nowMs);
  const hm = fmtHm(ts);
  const sameDay = (a, b) => a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (sameDay(d, n)) return hm;
  const yest = new Date(n.getFullYear(), n.getMonth(), n.getDate() - 1);
  if (sameDay(d, yest)) return tt('昨天', 'Yesterday') + ' ' + hm;
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  if (d.getFullYear() === n.getFullYear()) return mm + '-' + dd + ' ' + hm;
  return d.getFullYear() + '-' + mm + '-' + dd + ' ' + hm;
}

/**
 * 续传时间戳：服务端回放行若不带时间，沿用本地同位同角色同文的行的 ts。
 * 只补缺，不覆盖服务端已下发的时间。返回新数组（不改入参行）。
 * @param {any[]} serverRows
 * @param {any[]} localRows
 * @returns {any[]}
 */
export function carryTs(serverRows, localRows) {
  return serverRows.map((row, i) => {
    if (msgTs(row)) return row;
    const loc = localRows[i];
    if (!loc || loc.role !== row.role || loc.text !== row.text) return row;
    const t = msgTs(loc);
    return t ? { ...row, ts: t } : row;
  });
}
