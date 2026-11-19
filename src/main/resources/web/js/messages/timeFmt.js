// messages.js 拆分(FE组件化试点 2026-09-27):时间格式 可复用模块(行为保持)。
import { t } from '../i18n.js';
import { formatHm } from '../timeFormat.js';

// ── Time format: today HH:mm / yesterday / M-D (§3.2) ───
// Backend timestamps are epoch SECONDS (numbers, FriendApiRoutesSpec:
// "createdAt":1234567890) or ISO strings — normalize before new Date().
function toEpochMs(ts) {
  if (ts == null || ts === '') return 0;
  if (typeof ts === 'number') return ts < 1e12 ? ts * 1000 : ts; // s vs ms
  const ms = Date.parse(ts);
  return isNaN(ms) ? 0 : ms;
}

export function fmtTime(ts) {
  const ms = toEpochMs(ts);
  if (!ms) return '';
  // 同日 = 纯时钟文本 ⇒ 走共享的 12h/24h 偏好（formatHm）。
  if (isSameDayMs(ms)) return formatHm(ms);
  const d = new Date(ms);
  const now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.getFullYear() === y.getFullYear() && d.getMonth() === y.getMonth() && d.getDate() === y.getDate()) {
    return t('messages.yesterday');
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

/** Same calendar day as now (local) — the only branch that is a pure clock. */
function isSameDayMs(ms) {
  const d = new Date(ms);
  const now = new Date();
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
}

export { toEpochMs, isSameDayMs };
