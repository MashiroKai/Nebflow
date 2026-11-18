#!/usr/bin/env node
// check-message-item-unify.mjs — 消息条目统一门（2026-11-18 · 作者令「主会话与消息
// 联系人消息条目整套统一」）。
//
// 断言（**源码级**，非渲染几何；渲染几何/行为由 tests/message-item-unify.spec.mjs 覆盖）：
//   M1 基座唯一 —— footer 基座（chatFooter / chatFooterDivider / chatFooterTime 的
//      直接调用）只允许出现在 js/chatItem.js；消费面必须走 chatItem.messageFooter。
//   M2 气泡节点唯一 —— messageBubbleNode 只在 chatItem.js 定义，且 chat.js 与
//      messages.js 都从 chatItem 引入（两面同源）。
//   M3 附件区唯一 —— attachmentArea 只在 chatItem.js 定义；两个消费面
//      （messages.js 的 fm-att-list / popArtifacts.js 的 pop-group）经它构造，
//      源码内不得再有第二处以裸类名拼该容器。
//   M4 叠卡 footer 分态登记 —— popArtifacts.js 的叠卡组件必须登记
//      data-nf-stack-footer="group"（合起 = 属整组）与 "card"（展开 = 每卡独立），
//      且组件的 groupFooterFor / cardFooterFor 两个供给位被声明；
//      两个消费面都必须接上分态（主会话 = buildMediaRowFooterBadge 走 stackGroupFooter；
//      消息面 = buildMediaStackGroup 传 groupFooterFor + cardFooterFor）。
//   M5 四类样式单份 —— 叠卡 footer 基类字面量 `.pop-item-footer` / 整组 footer 类
//      `pop-stack-group-footer` / 时间档类 `duration-badge` 的**新增装配**只能出自
//      chatItem.js（消费面只传 spec）。

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = `${ROOT}src/main/resources/web/js/`;
const read = (rel) => readFileSync(`${WEB}${rel}`, 'utf8');

const errors = [];
const need = (cond, msg) => { if (!cond) errors.push(msg); };

const CHAT_ITEM = 'chatItem.js';
const POP = 'popArtifacts.js';
const MSGS = 'messages.js';
const CHAT = 'chat.js';
const DUR = 'chat/durationBadges.js';

for (const f of [CHAT_ITEM, POP, MSGS, CHAT, DUR]) {
  if (!existsSync(`${WEB}${f}`)) errors.push(`M0 缺文件: src/main/resources/web/js/${f}`);
}
if (errors.length) { console.error(errors.join('\n')); process.exit(1); }

const chatItem = read(CHAT_ITEM);
const pop = read(POP);
const msgs = read(MSGS);
const chat = read(CHAT);
const dur = read(DUR);

// ── M1 基座唯一 ────────────────────────────────────────────────────────────
const files = ['chat.js', 'messages.js', 'popArtifacts.js', DUR, 'chatUi.js'];
const baseCall = /chatFooter(?:Divider|Time)?\s*\(/;
for (const f of files) {
  const src = f === 'chatUi.js' ? '' : read(f); // chatUi.js = 定义处，不作消费面
  if (f === CHAT_ITEM) continue;
  need(!baseCall.test(src), `M1 footer 基座直调出现在 js/${f}（应只走 chatItem.messageFooter）`);
}
need(/export function messageFooter\s*\(/.test(chatItem), 'M1 chatItem.js 缺 messageFooter 基座导出');
need(/from '\.\.\/chatItem\.js'/.test(dur) && /messageFooter/.test(dur), 'M1 chat/durationBadges.js 未消费 chatItem.messageFooter');
need(/from '\.\/chatItem\.js'/.test(msgs) && /messageFooter/.test(msgs), 'M1 messages.js 未消费 chatItem.messageFooter');

// ── M2 气泡节点唯一 ────────────────────────────────────────────────────────
need(/export function messageBubbleNode\s*\(/.test(chatItem), 'M2 chatItem.js 缺 messageBubbleNode');
for (const f of [CHAT, MSGS]) {
  const src = read(f);
  need(/messageBubbleNode/.test(src), `M2 js/${f} 未消费 chatItem.messageBubbleNode（气泡节点未同源）`);
}

// ── M3 附件区唯一 ──────────────────────────────────────────────────────────
need(/export function attachmentArea\s*\(/.test(chatItem), 'M3 chatItem.js 缺 attachmentArea');
need(/attachmentArea\('fm-att-list'\)/.test(msgs), 'M3 messages.js 未经 attachmentArea 构造 fm-att-list');
need(/attachmentArea\('pop-group'\)/.test(pop), 'M3 popArtifacts.js 未经 attachmentArea 构造 pop-group');
// 裸拼容器（第二处样式源）：
need(!/el\(\s*'div'\s*,\s*'fm-att-list'\s*\)/.test(msgs), 'M3 messages.js 仍有裸 fm-att-list 构造（第二处）');
need(!/className\s*=\s*'pop-group'/.test(pop), 'M3 popArtifacts.js 仍有裸 pop-group 构造（第二处）');

// ── M4 叠卡 footer 分态登记 ────────────────────────────────────────────────
need(/nfStackFooter\s*=\s*'group'/.test(chatItem) && /nfStackFooter\s*=\s*'card'/.test(chatItem), 'M4 chatItem.js 叠卡 footer 基座未登记 group/card 两态');
need(/cardFooterFor/.test(pop), 'M4 popArtifacts.js 叠卡组件缺 cardFooterFor 供给位（展开态每卡 footer 的下载键）');
// 合起态整组 footer：两消费面都必须接 NF_POP_EXPANDED 分态 + 用共享基座装配。
need(/stackGroupFooter/.test(pop) && /NF_POP_EXPANDED/.test(pop), 'M4 主会话面未按 NF_POP_EXPANDED 接整组 footer 分态');
need(/stackGroupFooter/.test(msgs) && /NF_POP_EXPANDED/.test(msgs), 'M4 消息面未按 NF_POP_EXPANDED 接整组 footer 分态');

// ── M5 四类样式单份 ────────────────────────────────────────────────────────
need(/export function stackGroupFooter\s*\(/.test(chatItem), 'M5 chatItem.js 缺 stackGroupFooter');
need(/export function stackCardFooter\s*\(/.test(chatItem), 'M5 chatItem.js 缺 stackCardFooter');
// 消费面不得再手拼这两类 footer 节点：
need(!/className\s*=\s*'[^']*pop-item-footer/.test(pop), 'M5 popArtifacts.js 手拼 .pop-item-footer（应走 stackCardFooter）');
need(!/className\s*=\s*'[^']*pop-stack-group-footer/.test(pop), 'M5 popArtifacts.js 手拼 .pop-stack-group-footer（应走 stackGroupFooter）');

// ── M6–M11 CSS 单定义（2026-11-18 复核补腿：原门只扫 JS，样式重复定义漏检）────
// 判据 = **规则块级**：注释剥除后逐块（含 @media 内块）取「选择器 + 声明体」，
// 对四类基元（气泡 / 附件区 / 叠卡与通用 footer / composer 外壳与内件）要求
// 基线声明只出现在**一条**命名两侧面的规则块里；面内变体只许覆盖 gap/宽度/落位。
const CSS_ROOT = `${ROOT}src/main/resources/web/css/`;
const cssFiles = readdirSync(CSS_ROOT).filter(f => f.endsWith('.css'));

/** @returns {Array<{file:string, selector:string, body:string, line:number}>} */
function parseBlocks(file, text) {
  const out = [];
  // 剥注释但保留长度（行号仍按原文计）：整块注释替换成等长空白。
  const src = text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const re = /([^{}]+)\{([^{}]*)\}/g; // 最内层块（@media 内块同样命中，选择器取内层）
  let m;
  while ((m = re.exec(src)) !== null) {
    const selector = m[1].trim();
    if (!selector || selector.startsWith('@')) continue;
    out.push({ file, selector, body: m[2], line: src.slice(0, m.index).split('\n').length });
  }
  return out;
}
const CSS_BLOCKS = cssFiles.flatMap(f => parseBlocks(f, readFileSync(`${CSS_ROOT}${f}`, 'utf8')));
const hasSel = (b, re) => re.test(b.selector);
// 选择器表里**存在一个恰好等于** sel 的项（排除 `::before` / 后代 / 状态修饰变体）
const selExact = (b, sel) => b.selector.split(',').map(s => s.trim()).includes(sel);
const hasDecl = (b, prop) => new RegExp(`(^|[;{\\s])${prop}\\s*:`, 'i').test(b.body);

// M6 气泡基类单份：`.bubble` 的几何基座块（选择器就是 `.bubble`）只许 chat.css 一处。
const bubbleGeo = CSS_BLOCKS.filter(b => b.selector === '.bubble' &&
  (hasDecl(b, 'padding') || hasDecl(b, 'border-radius') || hasDecl(b, 'font-size') || hasDecl(b, 'max-width')));
need(bubbleGeo.length === 1 && bubbleGeo[0].file === 'chat.css',
  `M6 气泡基类几何出现 ${bubbleGeo.length} 处（应仅在 css/chat.css 一处）：` +
  bubbleGeo.map(b => `css/${b.file}:${b.line}`).join(', '));

// M7 附件区基线单份：列向/起缘/可收缩基线必须由**一条同时命名两侧面**的块给出。
const attBase = CSS_BLOCKS.filter(b => hasDecl(b, 'display') && /flex/.test(b.body) &&
  hasDecl(b, 'flex-direction') && hasSel(b, /\.pop-group|\.fm-att-list/));
need(attBase.length === 1, `M7 附件区基线块出现 ${attBase.length} 处（应 1 处，命名 .pop-group 与 .fm-att-list）：` +
  attBase.map(b => `css/${b.file}:${b.line}`).join(', '));
need(attBase.length === 1 && hasSel(attBase[0], /\.pop-group/) && hasSel(attBase[0], /\.fm-att-list/),
  'M7 附件区基线块未同时命名 .pop-group 与 .fm-att-list（两面分叉定义）');

// M8 附件区变体不重写基线：两侧面的变体块不得再写 display/flex-direction/align-items。
need(!CSS_BLOCKS.some(b => (hasSel(b, /\.pop-group/) || hasSel(b, /\.fm-att-list/)) && b !== attBase[0] &&
  (hasDecl(b, 'display') || hasDecl(b, 'flex-direction'))),
  'M8 附件区变体块重写了基线（display / flex-direction 第二处）');

// M9 通用 footer 基类单份：`.duration-badge` 基座块只许一处。
const durBase = CSS_BLOCKS.filter(b => selExact(b, '.duration-badge') &&
  hasDecl(b, 'border-radius'));
need(durBase.length === 1, `M9 .duration-badge 基座出现 ${durBase.length} 处（应 1 处）：` +
  durBase.map(b => `css/${b.file}:${b.line}`).join(', '));

// M10 叠卡 footer 两态单份：`.pop-item-footer`（卡态）基座唯一；
//      合起态整组 footer 复用 .duration-badge 基座（不得另起第二套盒样式）。
const itemFooter = CSS_BLOCKS.filter(b => selExact(b, '.pop-item-footer'));
need(itemFooter.length <= 1, `M10 .pop-item-footer 定义 ${itemFooter.length} 处（应 ≤1）：` +
  itemFooter.map(b => `css/${b.file}:${b.line}`).join(', '));
need(!CSS_BLOCKS.some(b => hasSel(b, /pop-stack-group-footer/) && hasDecl(b, 'border-radius')),
  'M10 合起态整组 footer 自建盒样式（应复用 .duration-badge 基座）');

// M11 composer 单定义（composer 统一批）：外壳几何与内件几何各一条**命名两侧面**的块。
const shell = CSS_BLOCKS.filter(b => selExact(b, '#input-bar') && selExact(b, '.fm-input-bar'));
need(shell.length === 1, `M11 composer 外壳单定义块出现 ${shell.length} 处（应 1 处，命名 #input-bar 与 .fm-input-bar）`);
need(shell.length === 1 && hasDecl(shell[0], 'border-radius') && hasDecl(shell[0], 'padding') && hasDecl(shell[0], 'gap'),
  'M11 composer 外壳单定义块缺 border-radius/padding/gap（几何未收口）');
const shellPad = CSS_BLOCKS.filter(b => selExact(b, '.fm-input-bar') && hasDecl(b, 'padding'));
need(shellPad.length === 1 && shellPad[0] === shell[0],
  `M11 .fm-input-bar 内距第二处定义（应只在单定义块内）：` +
  shellPad.filter(b => b !== shell[0]).map(b => `css/${b.file}:${b.line}`).join(', '));
const field = CSS_BLOCKS.filter(b => selExact(b, '#input') && selExact(b, '.fm-input'));
need(field.length === 1, `M11 composer 内件单定义块出现 ${field.length} 处（应 1 处，命名 #input 与 .fm-input）`);
const fieldCap = CSS_BLOCKS.filter(b => selExact(b, '.fm-input') && hasDecl(b, 'max-height'));
need(fieldCap.length === 1 && fieldCap[0] === field[0],
  `M11 .fm-input 自适应上限第二处定义：` +
  fieldCap.filter(b => b !== field[0]).map(b => `css/${b.file}:${b.line}`).join(', '));

// ── M12 上下文占位文案单源（composer 统一批 2026-11-18）────────────────────
// 判据：`composerPlaceholder` 只许在 js/input/composer.js 定义一次；两个消费面
// （主会话 js/input.js、消息面 js/messages/fmComposer.js）都必须经它取文案，
// 且两面都不得再持有「直接写 input.placeholder = t(...)」的分支副本。
const JS_ROOT = `${ROOT}src/main/resources/web/js/`;
/** @param {string} dir @param {string[]} acc */
function walkJs(dir, acc = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = `${dir}${e.name}`;
    if (e.isDirectory()) walkJs(`${p}/`, acc);
    else if (e.name.endsWith('.js')) acc.push(p.slice(JS_ROOT.length));
  }
  return acc;
}
const jsFiles = walkJs(JS_ROOT);
const phDefs = jsFiles.filter(f => /export function composerPlaceholder\s*\(/.test(readFileSync(`${JS_ROOT}${f}`, 'utf8')));
need(phDefs.length === 1 && phDefs[0] === 'input/composer.js',
  `M12 composerPlaceholder 定义 ${phDefs.length} 处（应只在 js/input/composer.js 一处）：${phDefs.join(', ')}`);
const composerSrc = read('input/composer.js');
for (const tok of ['input.ph.drag', 'input.ph.listening', 'input.refOnlyHint', 'input.ph.attachments']) {
  need(composerSrc.includes(tok), `M12 input/composer.js 的 composerPlaceholder 缺 ${tok} 分支（判据未收全）`);
}
const inputSrc = read('input.js');
need(/composerPlaceholder\s*\(/.test(inputSrc), 'M12 js/input.js 未经 composerPlaceholder 取文案');
need(!/input\.placeholder\s*=\s*t\(/.test(inputSrc), 'M12 js/input.js 仍有直写 input.placeholder = t(...)（第二份判据）');
const fmSrc = read('messages/fmComposer.js');
need(/composerPlaceholder\s*\(/.test(fmSrc), 'M12 js/messages/fmComposer.js 未经 composerPlaceholder 取文案');
need(/'messages\.inputPlaceholder'/.test(fmSrc), 'M12 js/messages/fmComposer.js 未声明自己的 baseToken');
need(!/input\.placeholder\s*=\s*t\(/.test(fmSrc), 'M12 js/messages/fmComposer.js 仍有直写 input.placeholder = t(...)（第二份判据）');
need(/input\.placeholder = opts\.placeholder/.test(read('chatUi.js')), 'M12 js/chatUi.js 工厂未从调用方接收 placeholder（注入面应单点）');

if (errors.length) {
  console.error(`check-message-item-unify FAILED (${errors.length}):`);
  errors.forEach(e => console.error('  ✗ ' + e));
  process.exit(1);
}
console.log('check-message-item-unify OK (M1–M5 JS 四类单份 + M6–M11 CSS 单定义 + M12 上下文占位文案单源：气泡/附件区/叠卡与通用 footer/composer 外壳与内件/placeholder)');
