// chat.js 拆分(FE组件化批次三 2026-09-27):时长与徽章显示 可复用模块(行为保持)。
import { t } from '../i18n.js';
// formatFooterTime = footer-time 批（2026-10-06）的分层 footer label（今天仍为纯
// 时钟 formatHm 语义，更早按本地日历日加日期层）——A 侧 chat.js 本簇改动随簇迁入。
import { messageFooter } from '../chatItem.js';
// ack-badge-pool 批（2026-11-23 作者令）：零 <report> 块回合的 ✻ 字句换 ack 池。
// 块判据复用 outputTag.hasOutputBlock（围栏感知，与渠道侧/气泡零块面同一实现，
// 不许第二套判据）；依赖方向单向（outputTag 只 import utils/dom，无环）。
import { hasOutputBlock } from '../outputTag.js';

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
 * ack-badge-pool 批（2026-11-23 作者令）：零 <report> 块回合的 ✻ 字句池。
 * 语义 = 收到 / 已办 / 轻量完成（同一宇宙意象、同一口气），键 ack.0–ack.17，
 * {d} 仍为时长占位。条数断言在 scripts/check-ack-phrases.mjs（池非空 / 字数 /
 * 两语言包对齐 / ACK_COUNT 常量一致）。
 */
const ACK_COUNT = 18;

/** Shared draw: `seed != null` = deterministic `seed % count`（live 腿种子 =
 *  徽章计数、回放腿 = 消息下标 —— 轮内必然前进 ⇒ 不连出同条）；无种子随机。
 *  轮换机制沿用 think 池原形（2026-11-23 作者令「轮换沿用现机制」）。 */
function pickFromPool(prefix, count, durationMs, seed) {
  const idx = seed != null
    ? ((seed % count) + count) % count
    : Math.floor(Math.random() * count);
  return '✻ ' + t(prefix + idx, { d: formatDuration(durationMs) });
}

/**
 * Pick a cosmology-themed thinking phrase with duration embedded.
 * e.g. "Counted some stars for 12s"
 * @param {number} durationMs
 * @param {number} [seed]
 * @returns {string} The full phrase text with duration.
 */
export function pickThinkingPhrase(durationMs, seed) {
  return pickFromPool('think.', THINKING_COUNT, durationMs, seed);
}

/**
 * ack 池取词（零 <report> 块回合专用）——同形同轮换，语义换 ack。
 * @param {number} durationMs
 * @param {number} [seed]
 * @returns {string} The full phrase text with duration.
 */
export function pickAckPhrase(durationMs, seed) {
  return pickFromPool('ack.', ACK_COUNT, durationMs, seed);
}

/**
 * 回合字句分派单点（ack-badge-pool 批 2026-11-23）：终稿全文带 ≥1 个平衡的
 * <report> 块 ⇒ think 池原样（「有 /report 的回合不动」）；零块（叙述、遗留
 * 内容、只在代码围栏里的标签）⇒ ack 池。判据 = outputTag.hasOutputBlock，
 * 与气泡零块面（markZeroBlockFace）同一谓词 —— 徽章字句与气泡面永不分叉。
 * @param {string} text 该条消息的全文（data 原文，非展示裁剪面）
 * @param {number} durationMs
 * @param {number} [seed]
 * @returns {string}
 */
export function pickTurnPhrase(text, durationMs, seed) {
  return hasOutputBlock(String(text || ''))
    ? pickThinkingPhrase(durationMs, seed)
    : pickAckPhrase(durationMs, seed);
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
  // ack-badge-pool 批（2026-11-23）：池分派看 copyText —— 本函数的全部调用方
  // （live chat.js finishAi 的 stream.aiText、回放 persistence.js 的 m.text /
  //   m.answer）传的都是该条消息的全文原文，正好是块判据要的输入；
  // turn header 的 ✻ 段回读 data-nf-phrase ⇒ live 与两条回放腿自动同面。
  return messageFooter({
    timestamp,
    copyText,
    timeFormat: 'day',
    duration: { phrase: pickTurnPhrase(copyText, durationMs, seed), model },
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
