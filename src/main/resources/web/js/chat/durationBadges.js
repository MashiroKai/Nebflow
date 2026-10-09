// chat.js 拆分(FE组件化批次三 2026-09-27):时长与徽章显示 可复用模块(行为保持)。
import { t } from '../i18n.js';
// formatFooterTime = footer-time 批（2026-10-06）的分层 footer label（今天仍为纯
// 时钟 formatHm 语义，更早按本地日历日加日期层）——A 侧 chat.js 本簇改动随簇迁入。
import { formatFooterTime, bindTimeToggle } from '../timeFormat.js';
import { createMsgCopyButton } from '../utils.js';

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
 * @param {number} durationMs
 * @param {number} [seed]
 * @returns {string} The full phrase text with duration.
 */
export function pickThinkingPhrase(durationMs, seed) {
  const idx = seed != null
    ? ((seed % THINKING_COUNT) + THINKING_COUNT) % THINKING_COUNT
    : Math.floor(Math.random() * THINKING_COUNT);
  return '✻ ' + t('think.' + idx, { d: formatDuration(durationMs) });
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
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  if (timestamp && timestamp > 0) {
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    // data-ts = 搜索跳转锚点（chatSearchFloat.js 消费，容器级挂载点同用此属性）；
    // data-ts-text = 时制刷新的显式声明（纯时钟文本节点，见 timeFormat.js 契约）；
    // data-ts-text-format="day" = 本节点文本走分层 footer label（formatFooterTime，
    // footer-time 批 2026-10-06）：时制切换就地重排仍与渲染时同一 formatter。
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.setAttribute('data-ts-text', String(timestamp));
    timeSpan.setAttribute('data-ts-text-format', 'day');
    timeSpan.textContent = formatFooterTime(timestamp);
    bindTimeToggle(timeSpan);
    badge.appendChild(timeSpan);
  }
  if (copyText) {
    if (badge.childElementCount > 0) {
      const div = document.createElement('span');
      div.className = 'duration-badge-divider';
      badge.appendChild(div);
    }
    badge.appendChild(createMsgCopyButton(copyText));
  }
  return badge;
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
 * @returns {HTMLElement}
 */
export function createDurationBadgeElement(durationMs, model, seed, timestamp, copyText) {
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  // Metadata for #346 summary reconstruction (not rendered — footer is
  // time + copy only, v1.2 ruling).
  badge.dataset.nfPhrase = pickThinkingPhrase(durationMs, seed);
  if (model) badge.dataset.nfModel = model;

  // Divider is a SEPARATOR: insert only between two existing/forthcoming
  // elements — never as a leading or trailing orphan (v1.2 footer = time +
  // copy only; the old leading divider was a leftover of "phrase | time").
  const appendDivider = () => {
    if (badge.childElementCount === 0) return;
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
  };

  if (timestamp) {
    appendDivider();
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    // data-ts = jump anchor (chatSearchFloat.js); data-ts-text = refresh target;
    // data-ts-text-format="day" = layered footer label (formatFooterTime —
    // footer-time batch 2026-10-06), same formatter on 12/24 in-place refresh.
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.setAttribute('data-ts-text', String(timestamp));
    timeSpan.setAttribute('data-ts-text-format', 'day');
    timeSpan.textContent = formatFooterTime(timestamp);
    bindTimeToggle(timeSpan);
    badge.appendChild(timeSpan);
  }

  if (copyText) {
    appendDivider();
    const copyBtn = createMsgCopyButton(copyText);
    badge.appendChild(copyBtn);
  }

  return badge;
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
