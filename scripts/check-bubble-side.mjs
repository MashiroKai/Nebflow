#!/usr/bin/env node
// check-bubble-side.mjs — 气泡归属方底色的**单点/唯一两档**静态哨兵
//
// 出处（bubble-side-color 批，2026-11-06；作者令逐字）：
//   「消息气泡的样式现在好像绿的、白的都有。正常应该我们这方发过去的是绿色，对方
//    发过来的是白色。我们这方发的，不管是我发的还是 Agent 发的，都算我们这方发，」
//
// 判据一句话：**气泡底色只由「消息归属方」决定**。
//   我方（作者本人 + 我方 Agent / delegate / 节点 / 后台任务 / 工具注入 / 系统生成）
//     ⇒ 绿 --nf-bub-ours-bg
//   对方（好友 / 群内他人 / 设备对端 / 外部通道 / 对方 AI）
//     ⇒ 白 --nf-bub-theirs-bg
//   不准有第三档，也不准按「消息类型 / sender 种类 / 是否有工具结果 / 是否系统生成」
//   另开配色。这条判据此前**没有任何门禁**：改前盘面上一共有 4 种底色（绿 / 白 /
//   sapphire 蓝 tint / 中性灰）散在 5 个 CSS 文件的 12 条规则里，改一处漏一处。
//
// 五条规则（二值，任一命中即红）：
//   R1 令牌单点   气泡底色令牌**只有两枚**（--nf-bub-ours-bg / --nf-bub-theirs-bg），
//                 各定义一次，且在 css/glass.css；两枚的色相来源必须仍是
//                 --color-bubble-user / --color-bubble-ai（零新色值）。
//                 另：--color-bubble-* 全站只准这两个名字（禁第三枚「气泡色」令牌）。
//   R2 二档封闭   所有「气泡面 / 媒体面」规则的 background(-color) 取值必须落在
//                 {var(--nf-bub-ours-bg), var(--nf-bub-theirs-bg)}；豁免表（带理由，
//                 每次运行打印）外出现任何其它值（含颜色字面量、其它 token、
//                 transparent / none）即红。
//   R3 禁第三色   已知第三色字面量在**归属方对话气泡**上零命中：--sapphire-tint-15、
//                 rgb(var(--sapphire) / …) 系蓝 tint、rgba(0,0,0,0.04) 灰、
//                 --glass-etched-bg 灰。精确 `.bubble.injected` 通知面 carve（作者
//                 2026-11-06 20:09 令：通知非出向消息）在 R2 登记原值原样恢复，
//                 不吃本条；除此之外的老第三色一律零容忍。
//   R4 JS 零色值  气泡底色从不由 JS 计算：任何 JS 文件里同一行同时出现 bubble 与
//                 `.style.background` ⇒ 红；归属方词汇（'theirs'）只准出现在
//                 js/bubbleSide.js；js/messages.js 必须消费 bubbleClass()。
//   R5 缺省安全   css/glass.css 必须存在「气泡归属方底色单点」块：`.bubble` 缺省
//                 落我方（渲染路径忘挂角色类时也不许漏成第三色），`.bubble.theirs`
//                 落对方。
//
// Usage: node scripts/check-bubble-side.mjs
//        node scripts/check-bubble-side.mjs --json     机器可读读数
//        BUBBLE_SIDE_WEB_ROOT=<另一份 web 树> node scripts/check-bubble-side.mjs
//          （取证用：把 main 上的旧 CSS 导出到临时目录跑「改前红」）
// Exit 0 = clean；exit 1 = 违规逐条列出 `file:line` + 实测取值。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative, dirname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = process.env.BUBBLE_SIDE_WEB_ROOT
  ? normalize(process.env.BUBBLE_SIDE_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
const JSON_OUT = process.argv.includes('--json');

const OURS = 'var(--nf-bub-ours-bg)';
const THEIRS = 'var(--nf-bub-theirs-bg)';
const TOKEN_HOME = 'glass.css';
const TOKENS = { '--nf-bub-ours-bg': '--color-bubble-user', '--nf-bub-theirs-bg': '--color-bubble-ai' };

/** 气泡面锚点类（元素自身带这些类 = 气泡面）。 */
const FACE_ANCHORS = new Set(['bubble', 'ask-bub', 'dropbox-msg-bubble', 'fm-msg-bubble']);

/**
 * 豁免表：**不是**绿/白两档、但经作者既有裁定另属一类的面。每条都必须写理由，
 * 每次运行都会打印（豁免是公开的，不是隐藏的）。
 */
const EXEMPT = new Map([
  ['__ask-bub', {
    reason: 'canvas ask 气泡 = 毛玻璃面（作者 2026-11-05 16:50「这三处全部吃唯一毛玻璃标准」）；'
      + '无色相、全部代表本机账号，故不吃绿/白两档。材质单点 = 输入基准件配方档'
      + ' --nf-glass-input-*（ask 面三修② 2026-11-11 作者令「毛玻璃一律不透底」，'
      + '与主会话 #input-bar 逐项同档；ask-glass-drag G1 钉）',
    allow: new Set(['var(--glass-bg)', 'var(--nf-glass-input-bg)', 'transparent', 'none']),
  }],
  ['__card-seat', {
    reason: '承卡泡：卡**整体取代**气泡面（`.bubble.ai.ob-ghost-seat` 剥面承 ask 卡；'
      + '`.bubble.schedule-card` 是定时任务卡，自带头行/chip/参考行/时间行材质，色相族 = '
      + 'sapphire 结构色，2026-10-30 按钮体系批既有裁定）。卡不是「消息气泡面」，'
      + '本批不动其材质 —— 豁免按选择器精确点名，改一处不影响别处',
    allow: null,
  }],
]);
const CARD_SEAT_SELECTORS = new Set([
  '.bubble.ai.ob-ghost-seat',
  '.bubble.schedule-card',
  '.bubble.injected.schedule-card',
]);
const INJECTED_NOTICE_SELECTOR = '.bubble.injected';


const KNOWN_THIRDS = [
  { re: /--sapphire-tint-15/, label: '--sapphire-tint-15（sapphire 蓝 tint，旧出向气泡底色）' },
  { re: /rgb\(\s*var\(--sapphire\)\s*\/\s*0\.\d+\s*\)/, label: 'rgb(var(--sapphire) / α)（sapphire 蓝 tint，旧注入/schedule 气泡底色）' },
  { re: /rgba\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\.04\s*\)/, label: 'rgba(0,0,0,0.04)（中性灰，旧 att-bubble 底色）' },
  { re: /var\(--glass-etched-bg\)/, label: '--glass-etched-bg（etched 灰，旧 dropbox 入向底色）' },
];

// ── 工具 ────────────────────────────────────────────────────────────────────
function walk(dir, ext) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p, ext));
    else if (e.name.endsWith(ext)) out.push(p);
  }
  return out;
}
const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
const lineAt = (src, idx) => src.slice(0, idx).split('\n').length;

/** 扁平化 CSS 成 [{selector, decls:[{prop,value,line}], line}]（@media 内规则按同样方式拍平）。 */
function parseCss(src) {
  const clean = stripComments(src);
  const rules = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(clean)) !== null) {
    const selector = m[1].replace(/\s+/g, ' ').trim();
    if (!selector || selector.startsWith('@')) continue;
    const body = m[2];
    const decls = [];
    const base = m.index + m[1].length + 1;
    for (const part of body.split(';')) {
      const i = part.indexOf(':');
      if (i < 0) continue;
      const prop = part.slice(0, i).trim().toLowerCase();
      const value = part.slice(i + 1).trim();
      if (!prop || !value) continue;
      decls.push({ prop, value, line: lineAt(src, base) });
    }
    rules.push({ selector, decls, line: lineAt(src, m.index + m[1].indexOf(selector)) });
  }
  return rules;
}

/** 选择器最后一个复合选择器里的类名集合（忽略伪类/伪元素）。 */
function lastCompound(selector) {
  const parts = selector.split(/\s*[\s>+~]\s*/).filter(Boolean);
  return { last: parts[parts.length - 1] || '', all: parts };
}
function classesOf(compound) {
  const noPseudo = compound.replace(/::?[a-z-]+(\([^)]*\))?/g, '');
  return (noPseudo.match(/\.[A-Za-z0-9_-]+/g) || []).map((c) => c.slice(1));
}
function hasTypeSelector(compound) {
  return /(^|[\s>+~])([a-zA-Z][a-zA-Z0-9]*)/.test(compound.split('.')[0] || '');
}

/** 这条规则是不是「气泡面 / 媒体面」自身的底色声明。 */
function faceOf(selector) {
  const { last } = lastCompound(selector);
  if (!last) return null;
  // 伪元素（::before / ::after 折射线、滚动条…）是装饰件，不是气泡面本体
  if (/::(before|after)\b/.test(last)) return null;
  const cls = classesOf(last);
  if (cls.some((c) => FACE_ANCHORS.has(c)) && !hasTypeSelector(last)) return 'bubble';
  // 媒体面：宿主是会话行 / 主会话用户行，目标是自己那张卡
  const hostIsMsg = /\.(fm-msg|user-media)\b/.test(selector);
  if (hostIsMsg && cls.includes('pop-media-flat')) return 'media';
  if (hostIsMsg && cls.includes('media-layer')) return 'media';
  return null;
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
const violations = [];
const notes = [];
const cssFiles = walk(CSS_DIR, '.css');
const jsFiles = walk(JS_DIR, '.js').filter((f) => !f.includes(`${'vendor'}`));

// R1 令牌单点
{
  const defs = [];
  for (const f of cssFiles) {
    const src = stripComments(readFileSync(f, 'utf8'));
    const rel = relative(CSS_DIR, f).split('\\').join('/');
    for (const m of src.matchAll(/(--nf-bub-ours-bg|--nf-bub-theirs-bg)\s*:\s*([^;]+);/g)) {
      defs.push({ file: rel, name: m[1], value: m[2].trim(), line: lineAt(src, m.index) });
    }
  }
  for (const name of Object.keys(TOKENS)) {
    const hits = defs.filter((d) => d.name === name);
    if (hits.length !== 1) {
      violations.push(`R1 ${name}: 定义 ${hits.length} 次，期望 1 次（${hits.map((h) => `${h.file}:${h.line}`).join(', ') || '未找到'}）`);
      continue;
    }
    const d = hits[0];
    if (d.file !== TOKEN_HOME) violations.push(`R1 ${name}: 必须定义在 css/${TOKEN_HOME}，实际 ${d.file}:${d.line}`);
    if (!d.value.includes(TOKENS[name])) {
      violations.push(`R1 ${name}: 色相来源必须是 ${TOKENS[name]}（零新色值），实际 \`${d.value}\`（${d.file}:${d.line}）`);
    }
  }
  notes.push(`R1 两枚令牌：${defs.map((d) => `${d.name}@${d.file}:${d.line}`).join(' · ') || '缺失'}`);
}

// R1b --color-bubble-* 只准两个名字
{
  const names = new Set();
  for (const f of cssFiles) {
    const src = stripComments(readFileSync(f, 'utf8'));
    for (const m of src.matchAll(/(--color-bubble-[a-z0-9-]+)\s*:/g)) names.add(m[1]);
  }
  const allowed = ['--color-bubble-ai', '--color-bubble-user'];
  for (const n of names) if (!allowed.includes(n)) violations.push(`R1 --color-bubble-* 出现第三枚「气泡色」令牌 ${n}`);
  notes.push(`R1 色相令牌：${[...names].join(', ') || '（无）'}`);
}

// Injected notices retain their tint. Ask bubbles are a separate glass surface:
// G8 in check-glass-unify enforces the shared glass background, not the notice tint.
{
  const src = stripComments(readFileSync(join(CSS_DIR, 'glass.css'), 'utf8'));
  const defs = [...src.matchAll(/--nf-bub-injected-bg\s*:\s*([^;]+);/g)];
  if (defs.length !== 1 || defs[0][1].trim() !== 'rgb(var(--sapphire) / 0.12)') {
    violations.push('R1 注入通知底色令牌必须单点定义为原 sapphire tint');
  }
  const rules = parseCss(src);
  for (const selector of [INJECTED_NOTICE_SELECTOR]) {
    const matches = rules.filter((r) => r.selector === selector);
    if (matches.length !== 1 || !matches[0].decls.some((d) => d.prop === 'background' && d.value === 'var(--nf-bub-injected-bg)')) {
      violations.push(`R1 ${selector} 必须使用注入通知底色令牌`);
    }
  }
}

// R2 / R3 逐规则的底色取值
let faceRules = 0;
for (const f of cssFiles) {
  const rel = relative(CSS_DIR, f).split('\\').join('/');
  const src = readFileSync(f, 'utf8');
  for (const rule of parseCss(src)) {
    const kind = faceOf(rule.selector);
    if (!kind) continue;
    const bg = rule.decls.filter((d) => d.prop === 'background' || d.prop === 'background-color');
    if (bg.length === 0) continue;
    faceRules++;
    // Legacy notification surface: injected rows are persistent notices, not sent speech.
    // The pre-2026-11-06 color is intentionally restored and pinned to the exact selector.
    if (rule.selector === INJECTED_NOTICE_SELECTOR) {
      for (const d of bg) {
        if (d.value.replace(/\s+/g, ' ').trim() !== 'var(--nf-bub-injected-bg)') {
          violations.push(`R2 ${rel}:${d.line} 注入通知底色偏离改前 sapphire tint：${d.value}`);
        }
      }
      notes.push(`R2 carve ${rel}:${rule.line} \`.bubble.injected\` —— 通知非出向消息；恢复改前 sapphire tint（仅此面）`);
      continue;
    }
    // 承卡泡：整条规则豁免（卡体材质自管，见豁免理由）
    if (CARD_SEAT_SELECTORS.has(rule.selector)) {
      notes.push(`R2 豁免 ${rel}:${rule.line} \`${rule.selector}\` —— ${EXEMPT.get('__card-seat').reason}`);
      continue;
    }
    const exempt = classesOf(lastCompound(rule.selector).last).includes('ask-bub') ? EXEMPT.get('__ask-bub') : null;
    for (const d of bg) {
      const v = d.value.replace(/\s+/g, ' ').trim();
      const ok = v === OURS || v === THEIRS;
      if (ok) continue;
      if (exempt && exempt.allow.has(v)) {
        notes.push(`R2 豁免 ${rel}:${d.line} \`${rule.selector}\` = ${v} —— ${exempt.reason}`);
        continue;
      }
      violations.push(`R2 ${rel}:${d.line} \`${rule.selector}\` 的 ${d.prop} = \`${v}\` 不是两枚归属方令牌之一（${OURS} / ${THEIRS}）`);
    }
    // R3 已知第三色（在气泡面上一律零命中）
    for (const d of rule.decls) {
      for (const t of KNOWN_THIRDS) {
        if (d.prop.startsWith('background') && t.re.test(d.value)) {
          violations.push(`R3 ${rel}:${d.line} \`${rule.selector}\` 仍在用 ${t.label}`);
        }
      }
    }
  }
}
notes.push(`R2 扫描气泡面底色规则 ${faceRules} 条`);

// R5 缺省安全档
{
  const glassPath = join(CSS_DIR, TOKEN_HOME);
  const src = existsSync(glassPath) ? stripComments(readFileSync(glassPath, 'utf8')) : '';
  const hasDefault = /\.bubble\s*\{\s*background:\s*var\(--nf-bub-ours-bg\)/.test(src.replace(/\n/g, ' ').replace(/\s+/g, ' '));
  const hasTheirs = /\.bubble\.theirs\s*\{\s*background:\s*var\(--nf-bub-theirs-bg\)/.test(src.replace(/\n/g, ' ').replace(/\s+/g, ' '));
  if (!hasDefault) violations.push(`R5 css/${TOKEN_HOME}: 缺 \`.bubble { background: var(--nf-bub-ours-bg) }\`（缺省 = 我方绿的安全档）`);
  if (!hasTheirs) violations.push(`R5 css/${TOKEN_HOME}: 缺 \`.bubble.theirs { background: var(--nf-bub-theirs-bg) }\`（对方白）`);
  notes.push(`R5 单点块：缺省=${hasDefault ? '✓' : '✗'} theirs=${hasTheirs ? '✓' : '✗'}`);
}

// R4 JS 侧
{
  const sideMod = join(JS_DIR, 'bubbleSide.js');
  if (!existsSync(sideMod)) {
    violations.push('R4 js/bubbleSide.js 不存在（归属方判定单点丢失）');
  } else {
    const m = readFileSync(sideMod, 'utf8');
    for (const sym of ['bubbleClass', 'bubbleSideOfOutgoing', 'SIDE_THEIRS', 'bubbleSideOfRole']) {
      if (!new RegExp(`export (function |const )${sym}`).test(m)) {
        violations.push(`R4 js/bubbleSide.js 缺导出 ${sym}`);
      }
    }
  }
  for (const f of jsFiles) {
    const rel = relative(WEB, f).split('\\').join('/');
    const src = readFileSync(f, 'utf8');
    src.split('\n').forEach((line, i) => {
      // 同一行同时出现 bubble 与 .style.background ⇒ 一处 JS 侧底色实现
      if (/bubble/i.test(line) && /\.style\.background(Color)?\s*=/.test(line)) {
        violations.push(`R4 ${rel}:${i + 1} 用内联 style 给气泡写底色（底色只准走 CSS 单点块）：${line.trim()}`);
      }
      // 归属方词汇单源
      if (rel !== 'js/bubbleSide.js' && /['"]theirs['"]/.test(line) && /\.classList|className|dataset|class:/.test(line)) {
        violations.push(`R4 ${rel}:${i + 1} 直接写归属方词汇 'theirs'（只准经 js/bubbleSide.js 单点）：${line.trim()}`);
      }
    });
  }
  const msgPath = join(JS_DIR, 'messages.js');
  if (existsSync(msgPath)) {
    const src = readFileSync(msgPath, 'utf8');
    if (!/from '\.\/bubbleSide\.js'/.test(src)) violations.push('R4 js/messages.js 未从 ./bubbleSide.js 取归属方（会话窗是唯一会算出 in/out 的路径）');
    if (!/bubbleClass\(/.test(src)) violations.push('R4 js/messages.js 未消费 bubbleClass()');
    notes.push('R4 JS 面：零内联气泡底色 + 归属方词汇单源 + messages.js 消费 bubbleClass ✓');
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────────
if (JSON_OUT) {
  console.log(JSON.stringify({ web: WEB, cssFiles: cssFiles.length, jsFiles: jsFiles.length, violations, notes }, null, 2));
} else {
  for (const n of notes) console.log(`  · ${n}`);
  console.log(`bubble-side: ${cssFiles.length} css + ${jsFiles.length} js scanned (web=${WEB}).`);
}
if (violations.length === 0) {
  if (!JSON_OUT) {
    console.log('PASS: 气泡底色只有「我方绿 / 对方白」两档，单点在 css/glass.css + js/bubbleSide.js。');
  }
  process.exit(0);
}
if (!JSON_OUT) {
  console.error('\nbubble-side GATE FAILURES:');
  for (const v of violations) console.error('  ✗ ' + v);
  console.error('\nFix: 底色只准落 css/glass.css「气泡归属方底色单点」块的两枚令牌；');
  console.error('     归属方判定只准落 js/bubbleSide.js。第三色一律收编，不要新增令牌。');
}
process.exit(1);
