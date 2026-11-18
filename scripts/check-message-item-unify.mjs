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

import { readFileSync, existsSync } from 'node:fs';
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

if (errors.length) {
  console.error(`check-message-item-unify FAILED (${errors.length}):`);
  errors.forEach(e => console.error('  ✗ ' + e));
  process.exit(1);
}
console.log('check-message-item-unify OK (M1–M5: 气泡/附件区/叠卡 footer 分态/footer 基座 四类单份)');
