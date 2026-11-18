// chatItem.js — 消息条目统一组件（2026-11-18 · 作者令「主会话与消息联系人消息条目
// 整套统一」）。
//
// Why this module exists: before this batch the two message-item surfaces —
// the primary session chat (chat.js / chat/durationBadges.js / popArtifacts.js)
// and the message-contact window (messages.js, 好友/群/设备三面) — each held
// their own copy of the message-item sub-parts. Structure had already been
// partly收口 (chatUi.composer/chatFooter*, bubbleSide.bubbleClass), but the
// FOOTER base and the STACK-CARD footer states were still two implementations:
// A assembled time+divider+copy in chat/durationBadges.js, B assembled
// badges+time+divider+actions inline in messages.js::bubbleEl, and the collapsed
// stack had a group footer only on A (popArtifacts buildMediaRowFooterBadge)
// while B's expanded cards carried a corner download key and no per-card footer.
// From now on those four primitives live HERE and both surfaces consume them;
// neither surface re-implements a leg (四类子件不许各写一遍).
//
// Four legs (四条腿):
//   ① 气泡外壳 —— messageBubbleRow(): the single shell builder (row wrapper +
//      group sender slot + bubble node). Class vocabulary still comes from
//      bubbleSide.bubbleClass() / chatUi.mainBubbleClass() / fmBubbleRowClass()
//      (the 2026-11-05/11-06 single-source batch) — this leg only assembles them.
//   ② 附件区 —— attachmentArea(): the single container factory for a message's
//      attachment region (A: `.pop-group`, B: `.fm-att-list`). Host class is a
//      parameter (排列格式变体), the construction is one point.
//   ③ 叠卡 footer 分态 —— stackGroupFooter() / stackCardFooter():
//        · collapsed  → the footer belongs to the WHOLE group (one node, group
//          download / count), tagged data-nf-stack-footer="group";
//        · expanded   → EACH card gets its own footer carrying a download button,
//          tagged data-nf-stack-footer="card".
//      popArtifacts.buildMediaStack() emits the tags; both consumers pass the
//      same providers, so the collapsed/expanded state machine is one.
//   ④ footer 基组件 —— messageFooter(): the single base footer builder
//      (`.duration-badge` + [badges] + time + [divider + copy/actions]).
//      Only FIELDS and ARRANGEMENT vary between the two surfaces (A: time+copy,
//      duration metadata datasets; B: agent/forwarded badges, time, copy+forward
//      actions, B-specific类名钩子) — the base DOM construction is one point.
//
// Gate constraints honored: zero static cycle (imports only chatUi.js,
// timeFormat.js, utils.js — none of which reaches back here); the style/behavior
// single-source sentinel scripts/check-message-item-unify.mjs keeps it that way
// (四类样式/行为只许一份，叠卡 footer 分态必须登记).

import { chatFooter, chatFooterDivider, chatFooterTime, mainBubbleClass } from './chatUi.js';
import { formatFooterTime, bindTimeToggle } from './timeFormat.js';
import { createMsgCopyButton } from './utils.js';

// ── ① 气泡节点 ─────────────────────────────────────────────────────────────
/**
 * 气泡节点唯一工厂（主会话 `chat.js` 与 fm 三面 `messages.js` 共用）。
 * 类名词表仍由调用方经既有单源给出（mainBubbleClass / fmBubbleRowClass +
 * bubbleClass）；本函数只装配——不新造第二份拼写，不改任何类名。
 * @param {string} className
 * @returns {HTMLElement}
 */
export function messageBubbleNode(className) {
  const el = document.createElement('div');
  el.className = className;
  return el;
}

// 主会话平面气泡的基类（向 callers 暴露同一枚词表，禁第二份拼写）。
export { mainBubbleClass };

// ── ② 附件区容器 ───────────────────────────────────────────────────────────
/**
 * 附件区唯一容器工厂。`cls` = 宿主排列格式变体（A `.pop-group` / B
 * `.fm-att-list`）——变体只允许换容器类名，构造逻辑一处。
 * @param {string} cls
 * @returns {HTMLDivElement}
 */
export function attachmentArea(cls) {
  const el = document.createElement('div');
  el.className = cls;
  el.dataset.nfAttachArea = '1';
  return el;
}

// ── ④ footer 基组件 ────────────────────────────────────────────────────────
/**
 * 消息 footer 基组件（唯一基座）。字段/排列按 spec 变体：
 *   · `badges[{className,text}]` 前置徽标（B：agent / forwarded）；
 *   · `duration{phrase,model}` 元数据 datasets（A：turn 汇总行回读，不渲染）；
 *   · `timestamp` + `timeText`/`timeFormat`（'day' ⇒ 分层日期档；缺省纯时钟档）
 *     + `timeClass` + `alwaysTime` + `bindToggle`；
 *   · `copyText`（A 变体：分隔符 + 复制键）；
 *   · `actions`（B 变体：调用方装配好的动作行节点）；
 *   · `dividerClass`/`extraClass` 宿主类名钩子。
 * 分隔符**永远是分隔符**：只在「时间/徽标」与「复制/动作」之间插入，不做首尾孤儿。
 * @param {{
 *   extraClass?: string, dividerClass?: string, timeClass?: string,
 *   badges?: Array<{className: string, text: string}>,
 *   duration?: {phrase: string, model?: string},
 *   timestamp?: number, timeText?: string, timeFormat?: 'clock'|'day',
 *   alwaysTime?: boolean, bindToggle?: boolean,
 *   copyText?: string, actions?: HTMLElement|null,
 * }} [spec]
 * @returns {HTMLElement} `.duration-badge` 根
 */
export function messageFooter(spec = {}) {
  const badge = chatFooter(spec.extraClass || '');
  // 分隔符只在已有兄弟节点时插入（禁首尾孤儿）。
  const divider = () => {
    if (badge.childElementCount === 0) return;
    badge.appendChild(chatFooterDivider(spec.dividerClass || ''));
  };

  (spec.badges || []).forEach((b) => {
    const s = document.createElement('span');
    if (b.className) s.className = b.className;
    s.textContent = b.text;
    badge.appendChild(s);
  });

  if (spec.duration) {
    if (spec.duration.phrase) badge.dataset.nfPhrase = spec.duration.phrase;
    if (spec.duration.model) badge.dataset.nfModel = spec.duration.model;
  }

  const ts = spec.timestamp;
  if (ts && ts > 0) {
    divider();
    const timeSpan = chatFooterTime(spec.timeClass || '');
    // data-ts = 搜索跳转锚点（chatSearch.js#scrollToMessage）；data-ts-text =
    // 时制刷新目标；data-ts-text-format="day" = 分层 footer label（timeFormat 契约）。
    timeSpan.setAttribute('data-ts', String(ts));
    timeSpan.setAttribute('data-ts-text', String(ts));
    if (spec.timeFormat === 'day') timeSpan.setAttribute('data-ts-text-format', 'day');
    timeSpan.textContent = spec.timeText != null ? spec.timeText : formatFooterTime(ts);
    if (spec.bindToggle !== false) bindTimeToggle(timeSpan);
    badge.appendChild(timeSpan);
  } else if (spec.alwaysTime) {
    // 空槽时间节点（B：无时间戳时仍保留纯时钟槽，行为与改前逐字同）。
    const timeSpan = chatFooterTime(spec.timeClass || '');
    timeSpan.textContent = spec.timeText != null ? spec.timeText : '';
    badge.appendChild(timeSpan);
  }

  if (spec.copyText) {
    divider();
    badge.appendChild(createMsgCopyButton(spec.copyText));
  }
  if (spec.actions) {
    divider();
    badge.appendChild(spec.actions);
  }
  return badge;
}

// ── ③ 叠卡 footer 分态 ─────────────────────────────────────────────────────
/**
 * 叠卡**合起**态 footer —— 属于**整组**（一枚节点，动作作用于全部条目）。
 * @param {{ items?: any[], ts?: number, timeText?: string, label?: string,
 *           downloadFor?: (items: any[]) => HTMLElement|null }} spec
 * @returns {HTMLElement} `.duration-badge.pop-stack-group-footer`
 */
export function stackGroupFooter(spec = {}) {
  const items = spec.items || [];
  const badge = messageFooter({
    extraClass: 'pop-stack-group-footer',
    timestamp: spec.ts,
    timeText: spec.timeText,
    timeFormat: 'day',
  });
  if (spec.label) {
    const lab = document.createElement('span');
    lab.className = 'pop-stack-group-label';
    lab.textContent = spec.label;
    badge.insertBefore(lab, badge.firstChild);
  }
  const dl = spec.downloadFor ? spec.downloadFor(items) : null;
  if (dl) {
    if (badge.childElementCount > 0) badge.appendChild(chatFooterDivider());
    badge.appendChild(dl);
    if (dl.disabled) {
      const reason = document.createElement('span');
      reason.className = 'pop-download-reason';
      reason.textContent = dl.title;
      badge.appendChild(reason);
    }
  }
  badge.dataset.nfStackFooter = 'group';
  return badge;
}

/**
 * 叠卡**展开**态每卡独立 footer —— 携带该卡自己的下载键（唯一基座 =
 * `.duration-badge.pop-item-footer`；A 另可有引用钮，由调用方 append）。
 * @param {{ download?: HTMLElement|null, quote?: HTMLElement|null }} spec
 * @returns {HTMLElement} `.duration-badge.pop-item-footer`
 */
export function stackCardFooter(spec = {}) {
  const footer = chatFooter('pop-item-footer');
  if (spec.download) {
    footer.appendChild(spec.download);
    if (spec.download.disabled) {
      const reason = document.createElement('span');
      reason.className = 'pop-download-reason';
      reason.textContent = spec.download.title;
      footer.appendChild(reason);
    }
  }
  if (spec.quote) footer.appendChild(spec.quote);
  footer.dataset.nfStackFooter = 'card';
  return footer;
}
