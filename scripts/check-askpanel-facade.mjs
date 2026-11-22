#!/usr/bin/env node
// check-askpanel-facade.mjs — Ask 面板门面静态闸（P4 第一期第一批，2026-11-21）
//
// 验收（设计 model-rule-engine-and-panel-design §4.4 P4 行原文）：
//   (a) canvas 与选区 ask 全部经门面构造 —— 本闸守卫「禁止直接构造 createAskDialog」；
//   门面规格（ask-unify-implementation 第 45-56 段）：askDialog 为内部引擎，
//   不允许消费方绕过门面直接构造，也不许面板临时直连 createAskDialog 形成双入口。
//
// 判据：
//   G1 js/askPanel.js 在场，导出 createAskPanel，返回对象含 root/dialog/open/close/
//      isOpen/focusInput/destroy 七个键。
//   G2 唯一构造点：全部 web/js 源码中，`createAskDialog(` 调用只许出现在
//      js/askPanel.js；`createAskDialog` 的 import 只许出现在 js/askPanel.js。
//      定义处 js/askDialog.js（`export function createAskDialog(`）豁免。
//   G3 两个消费者（js/askDock.js canvas 档、js/selectionAsk.js 选区档）都
//      `import { createAskPanel } from './askPanel.js'` 且调用 `createAskPanel(`。
//   G4 消费者的开/收/是否开着只经门面：askDock.js / selectionAsk.js 中不许出现
//      `.openDom(` / `.closeDom(` 直接调用（那是门面之下的 DOM 面，绕过 = 违规）。
//
// Usage: node scripts/check-askpanel-facade.mjs
//        ASKPANEL_WEB_ROOT=<另一份 web 树> node scripts/check-askpanel-facade.mjs
//          （检测力自证：对注入违规的副本树直跑应红）
// Exit 0 = clean；exit 1 = 违规逐条列出 file:line；exit 2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = process.env.ASKPANEL_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const JS_DIR = join(WEB, 'js');
const FACADE = 'askPanel.js';
const DIALOG = 'askDialog.js';
const CONSUMERS = ['askDock.js', 'selectionAsk.js'];
const JSON_OUT = process.argv.includes('--json');

if (!existsSync(JS_DIR)) {
  console.error(`环境错误：找不到 web 树 ${JS_DIR}`);
  process.exit(2);
}

const violations = [];
const passes = [];
const rel = (f) => relative(ROOT, f);
const lineOf = (src, re) => {
  const lines = src.split('\n');
  const i = lines.findIndex((l) => re.test(l));
  return i < 0 ? 1 : i + 1;
};
function v(code, file, msg, src, re) {
  violations.push({ code, file: rel(file), line: src ? lineOf(src, re) : 1, msg });
}
function pass(code, msg) { passes.push({ code, msg }); }

/** 递归收集 web/js 下全部 .js（含子目录）。 */
function walk(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.js')) out.push(p);
  }
  return out;
}

// G1 门面在场 + API 面
const facadePath = join(JS_DIR, FACADE);
if (!existsSync(facadePath)) {
  v('G1', facadePath, 'js/askPanel.js 不存在（门面缺席）', '', /./);
} else {
  const src = readFileSync(facadePath, 'utf8');
  if (!/export\s+function\s+createAskPanel\s*\(/.test(src)) {
    v('G1', facadePath, '门面未导出 createAskPanel', src, /createAskPanel/);
  }
  const keys = ['root', 'dialog', 'open', 'close', 'isOpen', 'focusInput', 'destroy'];
  const ret = src.slice(src.search(/return\s*\{/));
  const missing = keys.filter((k) => !new RegExp(`^\\s*${k}\\b`, 'm').test(ret));
  if (missing.length) v('G1', facadePath, `门面返回对象缺键：${missing.join(', ')}`, src, /return\s*\{/);
  else pass('G1', '门面在场，API 七键齐全');
}

// G2 唯一构造点
const files = walk(JS_DIR);
let g2bad = 0;
for (const f of files) {
  const base = f.slice(f.lastIndexOf('/') + 1);
  if (base === FACADE || base === DIALOG) continue;
  const src = readFileSync(f, 'utf8');
  // 调用（非注释行）：createAskDialog(
  src.split('\n').forEach((line, i) => {
    const code = line.replace(/\/\/.*$/, '');
    if (/\bcreateAskDialog\s*\(/.test(code)) {
      g2bad++;
      violations.push({ code: 'G2', file: rel(f), line: i + 1, msg: '直接构造 createAskDialog（须经 createAskPanel 门面）' });
    }
    if (/^\s*import\b[^;]*\bcreateAskDialog\b/.test(code)) {
      g2bad++;
      violations.push({ code: 'G2', file: rel(f), line: i + 1, msg: '直接 import createAskDialog（双入口）' });
    }
  });
}
if (!g2bad) pass('G2', `唯一构造点成立：createAskDialog 只在 ${FACADE} 内构造`);

// G3 消费者经门面
for (const name of CONSUMERS) {
  const p = join(JS_DIR, name);
  if (!existsSync(p)) { v('G3', p, `消费者 ${name} 不存在`, '', /./); continue; }
  const src = readFileSync(p, 'utf8');
  if (!/import\s*\{[^}]*\bcreateAskPanel\b[^}]*\}\s*from\s*['"]\.\/askPanel\.js['"]/.test(src)) {
    v('G3', p, `${name} 未从 ./askPanel.js 引入 createAskPanel`, src, /from\s*['"]\.\/askDialog/);
  }
  if (!/\bcreateAskPanel\s*\(/.test(src)) {
    v('G3', p, `${name} 未调用 createAskPanel 构造面板`, src, /createAskPanel/);
  }
}
if (!violations.some((x) => x.code === 'G3')) pass('G3', 'canvas 与选区两个消费者均经门面构造');

// G4 开/收只经门面
for (const name of CONSUMERS) {
  const p = join(JS_DIR, name);
  if (!existsSync(p)) continue;
  const src = readFileSync(p, 'utf8');
  src.split('\n').forEach((line, i) => {
    const code = line.replace(/\/\/.*$/, '');
    if (/\.(openDom|closeDom)\s*\(/.test(code)) {
      violations.push({ code: 'G4', file: rel(p), line: i + 1, msg: '消费者直连 openDom/closeDom（须经门面 open/close）' });
    }
  });
}
if (!violations.some((x) => x.code === 'G4')) pass('G4', '消费者开/收只经门面（无 openDom/closeDom 直连）');

if (JSON_OUT) {
  console.log(JSON.stringify({ ok: violations.length === 0, violations, passes }, null, 2));
} else {
  for (const p of passes) console.log(`PASS ${p.code} ${p.msg}`);
  for (const x of violations) console.log(`FAIL ${x.code} ${x.file}:${x.line} ${x.msg}`);
  console.log(violations.length ? `askpanel-facade: ${violations.length} 条违规` : 'askpanel-facade: clean');
}
process.exit(violations.length ? 1 : 0);
