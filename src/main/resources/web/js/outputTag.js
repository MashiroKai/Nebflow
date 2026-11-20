// outputTag.js — `<report>` 用户可见块的聊天面摘除/展开（report 块可见性批，
// 作者令 2026-11-20：单机制迁移，旧 `<output>` 字样按普通文本处理）。
//
// 两层分工：
//   · 管线透明面 = utils.js 的源级提取 pass（标签不现形、内容照渲染，opt-in
//     `opts.report`）—— 只有明确要读 report 文档的面才传；
//   · 聊天摘除面 = 本模块：气泡源文本有平衡 `<report>` 对时，正常态只渲染
//     块内内容（框外思考/工具叙述一概不显示——过程行本就 nf-tucked，摘除
//     的是气泡内的框外正文）；turn-header 角标展开（现看思考过程与工具结果
//     的入口）⇒ 过程行揭开 + 气泡切换为**原文全量**渲染——连 `<report>` 标签
//     语法本身也原样现形（escapeHtml 纯文本，pre-wrap）。
//
// 范围（作者裁定 2026-11-20：源头打标，不按窗口/渠道归属推断）：本模块只被
// root 消息的行渲染点调用（persistence.js / chat.js 的 `m.isRoot === true` 分支）；
// 非 root 会话的行一个字节都不经过这里 —— 既有展示逐字保留。
//
// 降级语义：无 report 块的旧内容照常全显（不吞历史消息）；流式中间态散标签由
// utils.js 转义为字面文本，不炸不吞。平衡闸 = utils.js `_outputTagsBalancedOn`
// （单点，`extractOutputInner` 复用之）。
//
// 依赖方向：本模块只 import utils.js（单向）；turnGroup.js / chat.js /
// persistence.js import 本模块。utils.js 不得反向 import（无环）。

import { renderMarkdownWithMath, escapeHtml, extractOutputInner } from './utils.js';

/** True when `text` carries ≥1 balanced `<report>...</report>` pair (fence-aware,
 *  same single implementation as the channel side). A text without any block —
 *  narration, legacy content, tags that only live inside a code fence — is NOT a
 *  report message: the caller renders it in full, exactly as before. */
export function hasOutputBlock(text) {
  return extractOutputInner(text) !== null;
}

/** Normal-face render: tags invisible, only the wrapped content visible.
 *  Returns false when the text has no balanced pair (caller renders the full
 *  text as before — the legacy fallback). On match the bubble is stamped
 *  `data-nf-output="1"` + `bubble._nfOutputFull = fullText` for the expand
 *  face, and the inner content is rendered into it. An EMPTY pair renders as an
 *  empty (hidden) bubble — the block is complete, it just carries no text. */
export function applyOutputFace(bubble, fullText) {
  const inner = extractOutputInner(fullText);
  if (inner === null) return false;
  bubble.dataset.nfOutput = '1';
  bubble._nfOutputFull = fullText;
  bubble.innerHTML = inner.trim() ? renderMarkdownWithMath(inner) : '';
  bubble.hidden = !inner.trim();
  return true;
}

/** Expand-face swap, driven by the turn-header badge toggle (turnGroup.js):
 *  expanded = the FULL RAW source, `<report>` tags verbatim (escaped plain
 *  text, pre-wrap); collapsed = back to the report-only render. No-op on
 *  bubbles that never went through applyOutputFace. */
export function setOutputExpanded(bubble, expanded) {
  if (!bubble || !bubble._nfOutputFull) return;
  if (expanded) {
    bubble.hidden = false;
    bubble.classList.add('nf-output-raw');
    bubble.innerHTML = '<pre class="nf-output-raw-pre">' + escapeHtml(bubble._nfOutputFull) + '</pre>';
  } else {
    bubble.classList.remove('nf-output-raw');
    const inner = extractOutputInner(bubble._nfOutputFull);
    bubble.hidden = inner === null || !inner.trim();
    bubble.innerHTML = inner === null || !inner.trim() ? '' : renderMarkdownWithMath(inner);
  }
}
