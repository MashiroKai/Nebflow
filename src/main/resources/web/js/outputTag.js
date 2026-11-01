// outputTag.js — <output> 用户可见块的聊天面摘除/展开（output 块语法批，
// 作者令 2026-10-31 16:13；16:16 修订：标签式 <output>…</output>，非围栏）。
//
// 两层分工（设计稿 §11）：
//   · 管线透明面 = utils.js 的源级提取 pass（标签不现形、内容照渲染）——
//     canvas/wiki/flow/report 四面读得了 output 文档；
//   · 聊天摘除面 = 本模块：气泡源文本有平衡 <output> 对时，正常态只渲染
//     块内内容（框外思考/工具叙述一概不显示——过程行本就 nf-tucked，摘除
//     的是气泡内的框外正文）；turn-header 角标展开（现看思考过程与工具结果
//     的入口）⇒ 过程行揭开 + 气泡切换为**原文全量**渲染——连 <output> 标签
//     语法本身也原样现形（escapeHtml 纯文本，pre-wrap）。
//
// 降级语义：无 <output> 块的旧内容照常全显（不吞历史消息）；流式中间态
// 散标签由 utils.js 转义为字面文本，不炸不吞。平衡闸 = utils.js
// _outputTagsBalanced（单点，extractOutputInner 复用之）。
//
// 特性总闸（output-block-gate 批，作者令「把 output 块相关的内容先关闭……」）：
// utils.extractOutputInner 头部读 outputBlockEnabled()（nebflow.json
// `outputBlock.enabled`，缺席 = 关）——关 ⇒ 本模块全链熄灭（hasOutputBlock=false、
// applyOutputFace=false、data-nf-*-output 不落标、setOutputExpanded 无操作），
// 气泡回全文渲染；本文件零改动即随闸翻面（闸 = utils.js 单点 + 渠道侧同一键）。
//
// 依赖方向：本模块只 import utils.js（单向）；turnGroup.js / chat.js /
// persistence.js import 本模块。utils.js 不得反向 import（无环）。

import { renderMarkdownWithMath, escapeHtml, extractOutputInner } from './utils.js';

/** True when `text` carries ≥1 balanced `<output>...</output>` pair. */
export function hasOutputBlock(text) {
  return extractOutputInner(text) !== null;
}

/** Normal-face render: tags invisible, only the wrapped content visible.
 *  Returns false when the text has no balanced pair (caller renders the full
 *  text as before — the legacy fallback). On match the bubble is stamped
 *  `data-nf-output="1"` + `bubble._nfOutputFull = fullText` for the expand
 *  face, and the inner content is rendered into it. */
export function applyOutputFace(bubble, fullText) {
  const inner = extractOutputInner(fullText);
  if (inner === null) return false;
  bubble.dataset.nfOutput = '1';
  bubble._nfOutputFull = fullText;
  bubble.innerHTML = renderMarkdownWithMath(inner);
  return true;
}

/** Expand-face swap, driven by the turn-header badge toggle (turnGroup.js):
 *  expanded = the FULL RAW source, `<output>` tags verbatim (escaped plain
 *  text, pre-wrap); collapsed = back to the output-only render. No-op on
 *  bubbles that never went through applyOutputFace. */
export function setOutputExpanded(bubble, expanded) {
  if (!bubble || !bubble._nfOutputFull) return;
  if (expanded) {
    bubble.classList.add('nf-output-raw');
    bubble.innerHTML = '<pre class="nf-output-raw-pre">' + escapeHtml(bubble._nfOutputFull) + '</pre>';
  } else {
    bubble.classList.remove('nf-output-raw');
    const inner = extractOutputInner(bubble._nfOutputFull);
    bubble.innerHTML = renderMarkdownWithMath(inner !== null ? inner : bubble._nfOutputFull);
  }
}
