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

// ── M6–M11 CSS 单定义（2026-11-18 复核**重写**：规范化 CSS AST 全声明审计）────
// 原版（同日早稿）是「正则内层块 + 枚举属性在场」扫描，只问「被点名的属性出现在
// 几个块里」，四个盲区（每条都附「造出来能红」的反证探针，见
// tests/message-item-unify-blindspots.md）：
//   B1 第二基座块不写枚举属性（如 `.duration-badge { padding: … }` 无 border-radius）
//      ⇒ 旧 M9 只数带 border-radius 的块，绿；新判据按 (成员,属性,层级) 数第二份，红。
//   B2 变体重写未枚举属性（如 `.fm-att-list { justify-content: … }`）⇒ 旧 M8 只查
//      display/flex-direction，绿；新判据变体属性白名单外即红。
//   B3 后代/复合上下文里的第二基座（如 `#chat .pop-item-footer { padding: … }`）
//      ⇒ 旧 M10 只看 selExact `.pop-item-footer` 块数，绿；新判据按成员 token 收
//      上下文变体并核属性白名单，红。
//   B4 单侧成员独立第二份（如 @media 里 `#input { max-height: … }`）⇒ 旧 M11 只查
//      `.fm-input` 的 max-height 块，绿；新判据 (成员,属性,层级) 唯一性即红。
//   B5 属性值归一化缺口（`.bubble { PADDING:4px }` / 值内多空白）⇒ 旧扫描按枚举
//      属性名子串与原始 body 正则，从未做 prop 小写/值空白归一的**声明级**比较；
//      新判据每条声明先归一（prop 小写、值空白折叠）再入账。
// 现行判据（全部基于同一个规范化 AST：注释剥除、@规则栈、声明按括号深度 0 的
// `;` 切分、prop 小写、值空白折叠）：
//   M6–M11 各对应一个「目标选择器族」（气泡/附件区/通用 footer/叠卡两态 footer/
//      composer 外壳与内件），族内判据三条：
//   (a) 基座第二份即红 —— 选择器项**恰好等于**成员（`.pop-group`、`#input` 等，
//       无祖先/复合/伪类修饰）的规则块，同一 (成员, 属性, 层级) 只许声明一次。
//       层级 = 档位语境（标准 / 暗色 / 窄屏 / 减动效 / 手机层）——跨层级的主题或
//       断点改写是 CSS 分层机制的合法形态，不算第二份；同层级第二份就是分叉。
//       唯一具名豁免 = glass.css 尾部「同值复述块」（G9c 级联复述教义：把参照
//       材质放到降级组之后再述一遍）；复述值与首份**归一化后逐值相等**才放行，
//       值不同 = 第二套材质，红。
//   (b) 变体只许白名单属性 —— 上下文变体（祖先/复合类/伪类/`:is()` 内嵌）可以
//       表达「格式/变体参数」（对齐方向、宽度档、落位、字体档、状态色……），
//       属性必须在族白名单里；白名单由现存合法变体逐条登记（见 variantProps）。
//   (c) 降级块不参战 —— `@supports not` / `html.no-backdrop` 上下文是「摘除」，
//       归 check-glass-unify 的 G1/G9 口径，此处跳过（同值同判，不双标）。
const CSS_ROOT = `${ROOT}src/main/resources/web/css/`;
const cssFiles = readdirSync(CSS_ROOT).filter(f => f.endsWith('.css'));

/** 规范化 CSS AST 遍历器 → { selector, decls:[{prop,value,line}], line, atStack }。
 *  （与 check-glass-unify.mjs walkRules 同一 AST 形态：这是仓内既定的机检 AST。） */
function* walkCssAst(text) {
  const src = text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const lineOf = (idx) => src.slice(0, idx).split('\n').length;
  let i = 0, selStart = 0; const stack = [];
  while (i < src.length) {
    const ch = src[i];
    if (ch === '{') {
      const prelude = src.slice(selStart, i).trim();
      if (prelude.startsWith('@')) { stack.push(prelude.replace(/\s+/g, ' ')); i++; selStart = i; continue; }
      let j = i + 1, inner = 0;
      while (j < src.length) {
        if (src[j] === '{') inner++;
        else if (src[j] === '}') { if (!inner) break; inner--; }
        j++;
      }
      const body = src.slice(i + 1, j);
      // 声明切分：只切括号深度 0 的 `;`（值内 url()/data: 的分号不受伤）。
      const decls = [];
      let cur = '', depth = 0;
      for (const c of body) {
        if (c === '(') depth++;
        if (c === ')') depth--;
        if (c === ';' && depth <= 0) {
          const col = cur.indexOf(':');
          if (col > 0) decls.push({ prop: cur.slice(0, col).trim().toLowerCase(), value: cur.slice(col + 1).trim().replace(/\s+/g, ' ') });
          cur = ''; depth = 0;
        } else cur += c;
      }
      { const col = cur.indexOf(':'); if (col > 0) decls.push({ prop: cur.slice(0, col).trim().toLowerCase(), value: cur.slice(col + 1).trim().replace(/\s+/g, ' ') }); }
      yield { selector: prelude.replace(/\s+/g, ' '), decls, line: lineOf(selStart), atStack: [...stack] };
      i = j + 1; selStart = i; continue;
    }
    if (ch === '}') { if (stack.length) stack.pop(); i++; selStart = i; continue; }
    i++;
  }
}

// 族登记表：成员 token → 族；variantProps = 该族上下文变体**允许**的属性全集
// （由现存合法变体逐条登记；新增变体属性 = 改这张表，白名单外的变体即红）。
const FAMILY = {
  bubble: { m6: '气泡外壳', members: ['.bubble', '.fm-msg-bubble'],
    variantProps: ['color', 'background', 'border', 'border-color', 'border-width', 'border-bottom-right-radius', 'border-bottom-left-radius', 'border-radius', 'box-shadow', 'font-size', 'padding', 'max-width', 'margin', 'transform-origin', 'display', 'animation', 'opacity', 'overflow-wrap', 'white-space', 'flex-direction', 'gap', 'width', 'backdrop-filter', '-webkit-backdrop-filter'] },
  attach: { m6: '附件区', members: ['.pop-group', '.fm-att-list'],
    variantProps: ['align-items', 'max-width'] },
  footer: { m6: '通用 footer 基座', members: ['.duration-badge'],
    variantProps: ['font-size', 'gap'] },
  stackCard: { m6: '叠卡展开态每卡 footer', members: ['.pop-item-footer'], variantProps: [] },
  stackGroup: { m6: '叠卡合起态整组 footer', members: ['.pop-stack-group-footer'], variantProps: [] },
  shell: { m6: 'composer 外壳', members: ['#input-bar', '.fm-input-bar'],
    variantProps: ['margin', 'margin-bottom', 'padding', 'border-color', 'background', 'outline', 'outline-offset', 'transition', 'transition-duration', 'content', 'position', 'top', 'left', 'right', 'height'] },
  field: { m6: 'composer 内件', members: ['#input', '.fm-input'],
    variantProps: ['color', 'opacity', 'cursor', 'outline', 'box-shadow'] },
};
const memberToFamily = new Map(Object.values(FAMILY).flatMap((f) => f.members.map((m) => [m, f])));

const tierOf = (atStack, selector) => {
  const at = atStack.join('|');
  if (/prefers-color-scheme:\s*dark/i.test(at)) return 'dark';
  if (/max-width/i.test(at)) return 'narrow';
  if (/prefers-reduced-motion/i.test(at)) return 'rmotion';
  if (/mobile-layer/.test(selector)) return 'mlayer';
  return 'std';
};
const isDegrade = (atStack, selector) =>
  atStack.some((a) => /^@supports\s+not/i.test(a)) || /html\.no-backdrop/.test(selector);

/** 顶层切分（括号深度 0 才切）——`:is(a b, c)` 内的空白/逗号不受伤。 */
function splitTopItems(s, sepRe) {
  const out = []; let depth = 0; let cur = '';
  for (const c of s) {
    if (c === '(') depth++;
    if (c === ')') depth--;
    if (depth === 0 && sepRe.test(c)) { out.push(cur); cur = ''; continue; }
    cur += c;
  }
  out.push(cur);
  return out.map((x) => x.trim()).filter(Boolean);
}
/** 复合选择器（无顶层空白）的成员 token 精确命中：类/id 各按**整 token**等值比，
 *  `#input` 不得误吞 `#input-bar`，`.pop-group` 不得误吞 `.pop-group-item`。 */
function memberHits(compound) {
  const hits = new Set();
  const tokens = [...compound.matchAll(/[.#][A-Za-z_][\w-]*/g)].map((m) => m[0]);
  for (const tok of tokens) if (memberToFamily.has(tok)) hits.add(tok);
  return hits;
}

// 账本：base[(member,prop,tier)] → [{file,line,value}]；variant 命中即时判。
const baseLedger = new Map();
const errsM6to11 = [];
for (const f of cssFiles) {
  const text = readFileSync(`${CSS_ROOT}${f}`, 'utf8');
  for (const rule of walkCssAst(text)) {
    for (const item of splitTopItems(rule.selector, /,/)) {
      if (isDegrade(rule.atStack, item)) continue;               // (c) 降级块不参战
      const tier = tierOf(rule.atStack, item);
      const classify = (ctx, hitMember) => {
        for (const d of rule.decls) {
          if (ctx === 'base') {
            const key = `${hitMember}|${d.prop}|${tier}`;
            if (!baseLedger.has(key)) baseLedger.set(key, []);
            baseLedger.get(key).push({ file: f, line: rule.line, value: d.value, prop: d.prop });
          } else {
            const fam = memberToFamily.get(hitMember);
            if (!fam.variantProps.includes(d.prop)) {
              errsM6to11.push(`变体属性越权：css/${f}:${rule.line} ${item} 声明 ${d.prop}:${d.value.slice(0, 60)}（族「${fam.m6}」白名单外——新变体属性须登记进 FAMILY.variantProps）`);
            }
          }
        }
      };
      if (memberToFamily.has(item)) { classify('base', item); continue; }   // 恰好等于成员 ⇒ 基座
      // 上下文变体：主语复合（顶层复合链的最后一节）命中的成员；`:is()/:where()`
      // 内嵌名单逐项展开后按各自主语再认一次（藏在 :is() 里不是免检通道）。
      const chain = splitTopItems(item, /[\s>+~]/);
      const subject = chain[chain.length - 1] || '';
      let hits = memberHits(subject);
      for (const grp of subject.matchAll(/:is?\(|:where\(/g)) {
        const inner = subject.slice(subject.indexOf('(', grp.index));
        const list = splitTopItems(inner.replace(/^\(([\s\S]*)\)$/, '$1'), /,/);
        for (const one of list) {
          const c2 = splitTopItems(one, /[\s>+~]/);
          for (const m2 of memberHits(c2[c2.length - 1] || '')) hits.add(m2);
        }
      }
      for (const mem of hits) classify('variant', mem);
    }
  }
}
// (a) 基座第二份即红（含 glass.css 同值复述豁免：同文件族 glass.css 且归一化值相等）。
for (const [key, decls] of baseLedger) {
  if (decls.length <= 1) continue;
  const [member, prop, tier] = key.split('|');
  const first = decls[0];
  const rest = decls.slice(1);
  const allRepeat = rest.every((d) => d.file === 'glass.css' && d.value === first.value);
  if (allRepeat && first.file === 'glass.css') continue;        // G9c 同值复述块（唯一豁免）
  for (const d of rest) {
    errsM6to11.push(`基座第二份：css/${d.file}:${d.line} ${member} 的 ${prop}（tier=${tier}）已由 css/${first.file}:${first.line} 声明（首值 ${first.value.slice(0, 50)} / 次值 ${d.value.slice(0, 50)}）——同层级第二份 = 分叉定义，收到唯一基座块或登记变体`);
  }
}
for (const e of errsM6to11) need(false, `M6–M11(AST) ${e}`);

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
