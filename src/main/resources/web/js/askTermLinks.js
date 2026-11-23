// askTermLinks.js — Ask 回答「可点击词条」落面（设计正本 §5 G4/G5 实施批 2026-11-23）。
//
// 作者令（2026-11-21 17:24 逐字）：「……像我们主会话的多选ask，你再去搜一下我的设计
// 原话，现在实现不完整，然后图标和对话框就完全使用canvas的ask。**回答完要生成可以
// 点击的词条**。图标的位置我也交代了的。气泡框的旁边居中。类似于pop的卡片的展开按钮
// 的位置一样。」
//
// 设计正本 `model-rule-engine-and-panel-design__20261121_172934.md` §5：
//   G4 回答无可点击词条 → 在 `createAskDialog` 增加 `termLinks` 钩子，复用
//      `termLexicon.js`（`viewers/markdown.js` 同源）；
//   G5 词条来源 = `termLexicon.js` 的 `/api/wiki/graph` `nodes[].terms[]`，**同源，
//      不另建词表**；
//   §5.2 词条点击 = 打开 wiki 词条（既有入口），不在对话里跳转别处；不做「点词条回填
//      脚本」（对话与脚本双向耦合）。
//
// 为什么本模块这么薄：词条匹配规则（termLexicon.js，纯函数）与 DOM 落面
// （viewers/markdown.js 的 renderAutoTerms —— 排除面 CODE/PRE/A/KaTeX/标题、消解
// 裁定、termTip 气泡与深链）都已有唯一实现，本批**不新造第三处**：只把 markdown.js
// 那条 pass 提升为可复用导出（`termLinkify`），本文件负责
//   ① 宿主节目的身份标记（同一气泡只处理一次，重渲时短路）；
//   ② 作用域（主会话没有 vault 文档作用域 ⇒ 传 null = 合并视图，歧义永不猜）。
//
// 放在 js/ 而不是 js/viewers/：消费者是 Ask 面（chat 侧），viewers/ 是文档渲染面；
// 依赖方向 = js/askTermLinks.js → viewers/markdown.js（单向，viewers 不反向依赖任何
// Ask 模块 ⇒ check:circular 零新环）。

import { termLinkify } from './viewers/markdown.js';

/** 已词条化标记（幂等键：同一节点二次进入即短路，流式增量重渲不重复扫）。 */
const DONE_ATTR = 'data-nf-term-linkified';

/**
 * 把一段已渲染的 Ask 回答正文词条化（设计 §5 G4/G5）。
 * 幂等：同一节点只处理一次；节点未连接/无正文时零动作。
 * @param {HTMLElement} node 回答气泡体（markdown 已落 DOM 的那个节点）
 * @returns {Promise<number>} 生成链接数（0 = 无命中/无图谱/出错/已处理）
 */
export function linkifyAskAnswer(node) {
  if (!node || typeof node.querySelectorAll !== 'function') return Promise.resolve(0);
  if (node.getAttribute(DONE_ATTR) === '1') return Promise.resolve(0);
  // 先打标再算：图谱取用是异步腿，期间若同一节点被二次调用（同帧多次 render），
  // 不许两条 pass 交叉写 DOM。
  node.setAttribute(DONE_ATTR, '1');
  const text = String(node.textContent || '');
  // 长度闸：极短回答（「好的」）没有词条可链，省掉一次图谱取用。
  if (text.trim().length < 2) return Promise.resolve(0);
  return termLinkify(node, null).catch(() => 0);
}
