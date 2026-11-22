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
// 两相渲染面（report-filter-gap 批 2026-11-22）：历史回放/上滑分页都先落纯文本
// 再 rAF 升级——第一相文本必须由 [[outputFirstPaintText]] 收口，否则升级批落地
// 前（手机端可很长）气泡暴露 `<report>` 源语法。
//
// 依赖方向：本模块只 import utils.js（单向）；turnGroup.js / chat.js /
// persistence.js import 本模块。utils.js 不得反向 import（无环）。

import { renderMarkdownWithMath, escapeHtml, extractOutputInner } from './utils.js';
import { setHtmlTree } from './dom.js';

/** True when `text` carries ≥1 balanced `<report>...</report>` pair (fence-aware,
 *  same single implementation as the channel side). A text without any block —
 *  narration, legacy content, tags that only live inside a code fence — is NOT a
 *  report message: the caller renders it in full, exactly as before. */
export function hasOutputBlock(text) {
  return extractOutputInner(text) !== null;
}

/** Zero-block root face (2026-11-21 零块可见性批). A ROOT text bubble whose source
 *  has no balanced pair renders its FULL text (作者裁定：无块内容不丢弃). It is
 *  stamped `data-nf-zero-output` + `_nfZeroFull` so the turn-header badge — the
 *  only expand entry for a block-less turn — can swap it to the raw source and
 *  back. Unlike the block face, collapsing never hides the bubble: the full text
 *  is the normal face here. */
export function markZeroBlockFace(bubble, fullText) {
  if (!bubble) return;
  bubble.dataset.nfZeroOutput = '1';
  bubble._nfZeroFull = fullText;
  bubble.hidden = false;
}

/** Expand-face swap for a zero-block root bubble (driven by the turn-header badge,
 *  turnGroup.js). expanded = full raw source (tags verbatim, pre-wrap);
 *  collapsed = the normal full-text render. No-op on bubbles never stamped. */
export function setZeroBlockExpanded(bubble, expanded) {
  if (!bubble || bubble._nfZeroFull === undefined) return;
  bubble.hidden = false;
  if (expanded) {
    bubble.classList.add('nf-output-raw');
    setHtmlTree(bubble, '<pre class="nf-output-raw-pre">' + escapeHtml(bubble._nfZeroFull) + '</pre>');
  } else {
    bubble.classList.remove('nf-output-raw');
    setHtmlTree(bubble, renderMarkdownWithMath(bubble._nfZeroFull, true, { report: true }));
  }
}

/** First-paint text for the two-phase history render (作者报障 2026-11-22 09:04
 *  「消息气泡退化为原 md report 块」). persistence.js 的历史回放腿与上滑分页腿
 *  都是**两相**渲染：先 `textContent = 源文本`（成本低，先让行有内容），再经
 *  rAF 升级批换成 markdown/摘除面。第一相此前吐的是**源文本**——于是 root 面
 *  含平衡 `<report>` 块的气泡，在升级批落地前看到的就是「裸标签 + 未渲染
 *  markdown」的原报告块；手机端这一窗口可以被拉得很长（后台标签 rAF 挂起 /
 *  长历史跨数百帧 / 分页批与手势抢帧），作者实拍正是这一相。
 *
 *  修法 = 第一相就同终相：root 面（`opts.report`）且有平衡块 ⇒ 只显块内正文
 *  （标签零暴露）；无块 / 非 root ⇒ 源文本原样（与终相同相——零块全文可见是
 *  裁定，不是降级）。纯函数、绝不抛。 */
export function outputFirstPaintText(text, opts) {
  const src = String(text == null ? '' : text);
  if (!(opts && opts.report)) return src;
  const inner = extractOutputInner(src);
  return inner === null ? src : inner;
}

/** In-flight replay face (report-filter-gap 追加批 2026-11-22). The reconnect
 *  replay (chat/historyReplay.js) and the sidebar popup replay (sidebar.js) rebuild
 *  a text bubble synchronously from the in-flight buffer. Their identity comes from
 *  the server-stamped frame mark carried by state.sessionTextIdentity (or the
 *  pendingRestore payload) — `root !== true` ⇒ returns false and the caller keeps
 *  its legacy full-text render bit-identical. Root ⇒ the same report face as the
 *  main legs: streaming = extracted block body (partial tags hidden, cursor kept);
 *  terminal = applyOutputFace, zero-block = full text + zero-block stamp. The bubble
 *  is stamped `_nfIsRoot` so later live frames keep the report face. */
export function applyReplayFace(bubble, text, { root, streaming, cache }) {
  if (root !== true) return false;
  bubble._nfIsRoot = true;
  const src = String(text || '');
  if (streaming) {
    const partial = extractOutputInner(src) === null && /<\/?report>/.test(src);
    const vis = partial ? '' : outputFirstPaintText(src, { report: true });
    bubble.hidden = !vis.trim();
    bubble.innerHTML = renderMarkdownWithMath(vis, true, { report: true, stream: true }) + '<span class="cursor"></span>';
    return true;
  }
  if (applyOutputFace(bubble, src)) return true;
  bubble.innerHTML = renderMarkdownWithMath(src, true, { report: true, cache });
  bubble.hidden = false;
  markZeroBlockFace(bubble, src);
  return true;
}

/** Quote-snapshot display text (2026-11-21 作者令「引用的内容应该去掉report块的语法，
 *  直接显示渲染后的纯文字」). Display-side only — the stored snapshot keeps its
 *  raw source. With ≥1 balanced block ⇒ the rendered plain text of the block
 *  bodies (tags gone, markdown resolved to text). Without a block ⇒ the full
 *  source text, unchanged. */
export function displayOutputText(text) {
  const src = String(text || '');
  const inner = extractOutputInner(src);
  if (inner === null) return src;
  const html = renderMarkdownWithMath(inner, false, { cache: false });
  const doc = new DOMParser().parseFromString(html, 'text/html');
  return (doc.body.textContent || '').trim();
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
  setHtmlTree(bubble, inner.trim() ? renderMarkdownWithMath(inner) : '');
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
    setHtmlTree(bubble, '<pre class="nf-output-raw-pre">' + escapeHtml(bubble._nfOutputFull) + '</pre>');
  } else {
    bubble.classList.remove('nf-output-raw');
    const inner = extractOutputInner(bubble._nfOutputFull);
    bubble.hidden = inner === null || !inner.trim();
    setHtmlTree(bubble, inner === null || !inner.trim() ? '' : renderMarkdownWithMath(inner));
  }
}
