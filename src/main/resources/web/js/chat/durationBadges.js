// chat.js 拆分(FE组件化批次三 2026-09-27):时长与徽章显示 可复用模块(行为保持)。
import { t } from '../i18n.js';
// formatFooterTime = footer-time 批（2026-10-06）的分层 footer label（今天仍为纯
// 时钟 formatHm 语义，更早按本地日历日加日期层）——A 侧 chat.js 本簇改动随簇迁入。
import { messageFooter } from '../chatItem.js';
import { defaultBadgeTipMixer, composeTipPhrase } from './badgeTips.js';

/**
 * Format milliseconds into a human-readable duration string.
 * e.g. 5000 -> "5s", 93000 -> "1m 33s", 547000 -> "9m 7s"
 */
export function formatDuration(ms) {
  const totalSeconds = ms / 1000;
  if (totalSeconds < 1) return '< 1s';
  const rounded = Math.round(totalSeconds);
  if (rounded < 60) return rounded + 's';
  const minutes = Math.floor(rounded / 60);
  const seconds = rounded % 60;
  return minutes + 'm ' + seconds + 's';
}

/**
 * Format duration for live timer display (always ticking, no rounding).
 * e.g. 3500 -> "3s", 65000 -> "1m 5s", 3700000 -> "1h 1m 40s"
 */
export function formatLiveDuration(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  if (totalSeconds < 60) return totalSeconds + 's';
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return minutes + 'm ' + seconds + 's';
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return hours + 'h ' + mins + 'm ' + seconds + 's';
}

/**
 * Cosmology-themed thinking phrases — now powered by i18n.
 * Keys: think.0 through think.18. {d} is replaced with the formatted duration.
 */
const THINKING_COUNT = 19;

/**
 * Pick a cosmology-themed thinking phrase with duration embedded.
 * e.g. "Counted some stars for 12s"
 * 每次调用 = 一次徽章出现：混入器（badgeTips.js，每 6 次出现 1 条使用提示，绝不连出两条）
 * 命中时以「提示：」标注的提示文案代替浪漫文案（composeTipPhrase），时长仍保留；其余走浪漫池（无标注）。
 * @param {number} durationMs
 * @param {number} [seed]
 * @param {{next: () => (string|null)}} [tipMixer] 可注入（测试用），默认模块级混入器
 * @returns {string} The full phrase text with duration.
 */
export function pickThinkingPhrase(durationMs, seed, tipMixer = defaultBadgeTipMixer) {
  const tip = tipMixer.next();
  const d = formatDuration(durationMs);
  if (tip) return composeTipPhrase(tip, d);
  const idx = seed != null
    ? ((seed % THINKING_COUNT) + THINKING_COUNT) % THINKING_COUNT
    : Math.floor(Math.random() * THINKING_COUNT);
  return '✻ ' + t('think.' + idx, { d });
}

/**
 * Plain message footer badge (v1.2 unified footer form): time + copy.
 *
 * The SINGLE builder for every non-duration footer path (2026-09-06 footer
 * 补齐批): user bubbles, injected bubbles, no-duration AI/ask-answer
 * fallbacks, thinking rows, tool rows, agent rows, ask questions. Duration
 * footers (final AI/ask answers) still go through
 * createDurationBadgeElement, which carries the phrase/model metadata the
 * turn header needs — plain footers deliberately carry NO data-nf-phrase so
 * turnGroup.findDoneBadge never mistakes them for done markers.
 *
 * The divider is a true separator: inserted only between the time span and
 * the copy button — never a leading or trailing orphan. Missing timestamp →
 * copy-only; missing copyText → time-only (single element, no divider).
 *
 * @param {number} [timestamp] epoch millis (0/absent → no time span)
 * @param {string} [copyText] (empty/absent → no copy button)
 * @returns {HTMLElement}
 */
export function createMsgFooterBadge(timestamp, copyText) {
  // 装配单点 = chatItem.messageFooter（消息条目统一批 2026-11-18；本函数仅保留
  // A 侧的字段/档位映射：分层日期 label 档 = 'day'）。
  return messageFooter({
    timestamp,
    copyText,
    timeFormat: 'day',
  });
}

/**
 * Create a duration badge DOM element (pill style).
 *
 * v1.2 user ruling (2026-08-21 12:08): AI bubble footers are uniformly
 * time + copy only — no phrase, no model tag in the footer. The phrase
 * (with embedded duration) and model name still travel on the badge as
 * dataset attributes (data-nf-phrase / data-nf-model) so the turn-group
 * summary row (#346) can recover them: live path via main.js, history
 * reload via turnGroup.groupSegment. The summary row is now the sole
 * display home of the phrase/model metadata.
 *
 * @param {number} durationMs
 * @param {string} [model]
 * @param {number} [seed]
 * @param {number} [timestamp] - epoch millis for display
 * @param {string} [copyText] - copy button text; omit = footer carries no copy button
 * @returns {HTMLElement}
 */
export function createDurationBadgeElement(durationMs, model, seed, timestamp, copyText) {
  // 装配单点 = chatItem.messageFooter（消息条目统一批 2026-11-18）：phrase/model
  // 走 duration 元数据档（#346 汇总行回读，footer 本体仍只渲染时间 + 复制）。
  return messageFooter({
    timestamp,
    copyText,
    timeFormat: 'day',
    duration: { phrase: pickThinkingPhrase(durationMs, seed), model },
  });
}

/**
 * Render a subtle duration badge below an AI bubble.
 */
export function renderDurationBadge(bubble, durationMs, model, seed, timestamp, copyText) {
  if (!bubble) return;
  const row = bubble.closest('.row');
  if (!row) return;
  const badge = createDurationBadgeElement(durationMs, model, seed, timestamp, copyText);
  row.appendChild(badge);
}
