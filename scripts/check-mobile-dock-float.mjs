#!/usr/bin/env node
// check-mobile-dock-float.mjs — 「移动端 dock 在所有页面都是悬浮形态」静态哨兵
// （作者裁定 2026-11-21 10:41 console，逐字：
//   「手机端 dock 在其他页面都是悬浮效果，但主会话却是截断的——落成标准和规范，
//    进行自动化检查，都得是悬浮的」）
//
// Why a gate：dock 的**胶囊本体**一直是单源的、逐值正确的（r999 + 三边 inset + blur，
// 五个页面读出来一模一样），所以任何「只扫 #mobile-dock 自己的规则」的检查都会全绿
// ——而作者看到的缺陷在**它身后的带区**：`body.mobile-layer #mobile-pages::before`
// 是一条 `position:absolute; bottom:0; height: var(--mobile-dock-top);
// background: var(--color-bg)` 的扁平不透明带。
//   · 有 `.live` 页时：页面是全视口不透明面，天然盖住它 ⇒ 零影响（「其他页面都悬浮」）；
//   · 聊天根（无 `.live` 页）时：它是 `#mobile-pages`（z 156，压在 `#chat` 之上）里
//     **唯一上色的东西** ⇒ 滚进带区的聊天气泡被硬切遮住，dock 玻璃采样平坦常量色
//     = 读起来「半透明实底覆盖 / 截断」（「主会话却是截断的」）。
// 结论：判据不能只看 dock，必须看**「dock 与宿主内容之间有没有一层垫底」**。
//
// 判据把「都得是悬浮的」拆成可机械判定的五条（单源正文 =
// `design-language/references/frontend/mobile-chrome-spec.md` §1.1 D-1..D-6）：
//
//  F1 dock 单源形态：`#mobile-dock` 的几何规则必须在场且逐条命中 ——
//     `position: fixed` / 左右 `var(--dock-inset-x)` / `bottom` 取 safe + `--dock-inset-b` /
//     `height: var(--dock-h)` / `border-radius: var(--ds-radius-pill)`；
//     同时禁形清单零命中（`bottom: 0` / `left: 0` / `right: 0` / `border-radius: 0` /
//     `width: 100%` / `height: 100%` / `border-radius: 0px`）。
//  F2 几何单源：`#mobile-dock` 的几何声明只许出现在 `css/mobileLayer.css`；
//     其它文件对 `#mobile-dock` 只许声明材质（backdrop-filter / background / border /
//     box-shadow）。出现几何 = 第二套在长 = 红。
//  F3 带区无垫层（本次报障的正主）：`#mobile-pages` 容器自身与它的 `::before` / `::after`
//     一律零背景（`transparent` / `none` / 未声明）—— 容器是零交互壳，一上色就盖住宿主。
//     另加「底锚不透明面必须具名登记」（规范 §1.4 表逐行读）：兜住「换个类名再垫一层」。
//  F4 页面全高：`.mobile-page` 的几何规则必须 `top: 0` **且** `bottom: 0`；
//     禁把页底缘收到 dock 上缘（`bottom: var(--mobile-dock-*)`）——收短 = 页面不再穿过
//     dock 玻璃 = 又一种「非悬浮」形态。
//  F5 浮条座位（规范 §1.3 表逐行读）：登记过的页面内浮条（L2）必须真在 CSS 里消费
//     `--mobile-dock-clearance` 派生座位；浮条写 `bottom: 0` = 落进 dock 带 = 红。
//
// 判据盲点（不装作没有）：F1/F4 判的是**声明**，不是计算几何；运行时真读数由
// tests/mobile-dock.spec.mjs D13（胶囊几何）+ tests/mobile-dock-capsule-shots.mjs
// （带区无垫底 / 玻璃采样真实内容）承担。JS 内联写 `#mobile-pages.style.background`
// 这类越界在本仓不存在（容器由 navController 建、零内联造型），若将来出现需另加 F6。
//
// 用法：
//   node scripts/check-mobile-dock-float.mjs              # 门禁（红/绿）
//   node scripts/check-mobile-dock-float.mjs --json       # 机器读（逐条命中）
//   node scripts/check-mobile-dock-float.mjs --inventory  # 盘点（底锚不透明面 + dock 规则）
//   node scripts/check-mobile-dock-float.mjs --root=<dir> # 在另一个检出上跑同一判据
//   （--spec-root=<dir> 只给测试/一次性覆盖用，见 scripts/lib/design-spec-source.mjs）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
/** dock 几何的单源文件 */
const DOCK_FILE = join(CSS_DIR, 'mobileLayer.css');
/** 规范正文（单源在仓内 design-language/references/frontend/） */
const SPEC_FILE = resolveDesignSpec('references/frontend/mobile-chrome-spec.md', process.argv.slice(2));
if (!SPEC_FILE) {
  console.error(`mobile-dock-float: ${specMissingMessage('references/frontend/mobile-chrome-spec.md')}`);
  process.exit(2);
}

const JSON_OUT = process.argv.includes('--json');
const INVENTORY = process.argv.includes('--inventory');

const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');

/** 非「零背景」的 background 值（transparent / none / 未声明都算零背景） */
const ZERO_BG = /^(?:transparent|none|unset|initial|inherit)?$/i;

function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 极简 CSS 块遍历器（与 check-input-surface / check-flat-buttons 同口径）。 */
function* walkRules(src) {
  const text = stripCssComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  let i = 0;
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      const bodyStart = i + 1;
      let j = bodyStart;
      let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') {
          if (inner === 0) break;
          inner--;
        }
        j++;
      }
      yield { selector: prelude, body: text.slice(bodyStart, j), file: null, line: lineOf(selStart) };
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}' || ch === ';') selStart = i + 1;
    i++;
  }
}

/** 规则体 → 声明对（跳过嵌套块；本仓 CSS 以平铺为主）。 */
function parseDecls(body) {
  const out = [];
  for (const raw of body.split(';')) {
    const k = raw.indexOf(':');
    if (k < 0) continue;
    const prop = raw.slice(0, k).trim().toLowerCase();
    const value = raw.slice(k + 1).replace(/!important/gi, '').trim();
    if (!/^[-a-z@]/.test(prop) || !value) continue;
    out.push({ prop, value });
  }
  return out;
}

const decl = (decls, prop) => {
  const row = decls.filter((d) => d.prop === prop).pop();
  return row ? row.value.replace(/\s+/g, ' ').trim() : null;
};

/** 底锚判据：贴住宿主底缘（`bottom: 0` 或 `inset: 0` 全铺） */
const bottomAnchored = (decls) => {
  const bottom = decl(decls, 'bottom');
  const inset = decl(decls, 'inset');
  return /^(?:0|0px)$/.test(bottom || '') || /^(?:0|0px)$/.test(inset || '');
};

/** 选择器组 → 去空白后的片段数组 */
const selectorsOf = (prelude) => prelude.split(',').map((s) => s.trim().replace(/\s+/g, ' ')).filter(Boolean);

/** 最右复合选择器（去伪元素/伪类后取最后一段）—— 判「这条规则是不是那个面」 */
const rightmost = (sel) => (sel.replace(/::?[a-z-]+(\([^)]*\))?/gi, ' ').split(/\s*[>+~]\s*|\s+/).filter(Boolean).pop() || '');

/** 该规则是否命中某个登记选择器（按最右复合选择器比对，不做全文子串匹配） */
const hits = (rule, wanted) => selectorsOf(rule.selector).some((s) => {
  const right = rightmost(s);
  return right === wanted || s === wanted || right === rightmost(wanted);
});

/** 座位令牌闭包：由 dock 派生的一切令牌（`--mobile-dock-clearance` / `--mobile-dock-top`
 *  的传递后代）。判「这个座位是不是从 dock 派生的」而不许逐字写一个固定令牌 ——
 *  `.ta-tool` 消费的是 `--ta-band-bottom`，它的定义 = max(kb, --mobile-dock-clearance)。
 *  链式判据与 check-floating-clearance.mjs 的「单源 token 链」同口径。 */
function seatTokenClosure(rules) {
  const seeds = ['--mobile-dock-clearance', '--mobile-dock-top'];
  const derived = new Set(seeds);
  const decls = [];
  for (const r of rules) {
    for (const d of r.decls) {
      if (!d.prop.startsWith('--')) continue;
      decls.push({ token: d.prop, value: d.value });
    }
  }
  let grew = true;
  while (grew) {
    grew = false;
    for (const d of decls) {
      if (derived.has(d.token)) continue;
      if ([...derived].some((t) => d.value.includes(t))) { derived.add(d.token); grew = true; }
    }
  }
  return derived;
}
/** 座位令牌闭包在工作集就绪后再求值（rules 收集在下块） */
let SEAT_TOKENS = new Set();
const seatOf = (value) => (value ? [...SEAT_TOKENS].find((t) => value.includes(t)) : null);

// ── 规范表读取（数据面在正文，不在脚本里硬编码）──────────────────────────
// 表语义：`##` 节内第一张含 `selectorHeader` 列名的表 —— 表头行与 `---` 分隔行都跳过，
// 只返回数据行（cells 数组）。缺节或缺表返回 null（调用方按红/环境错误处置）。
function readSpecTable(headingRe, selectorHeader) {
  const text = readFileSync(SPEC_FILE, 'utf8');
  const lines = text.split('\n');
  const start = lines.findIndex((l) => headingRe.test(l));
  if (start < 0) return null;
  const isRow = (t) => /^\|/.test(t);
  const cellsOf = (t) => t.replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  const isSep = (cells) => cells.length > 0 && cells.every((c) => /^:?-+:?$/.test(c));
  let header = -1;
  for (let i = start + 1; i < lines.length; i++) {
    const t = lines[i].trim();
    if (/^## /.test(t)) break;
    if (isRow(t) && cellsOf(t).some((c) => c.includes(selectorHeader))) { header = i; break; }
  }
  if (header < 0) return null;
  const rows = [];
  for (let i = header + 1; i < lines.length; i++) {
    const t = lines[i].trim();
    if (/^## /.test(t)) break;
    if (!isRow(t)) { if (rows.length) break; continue; }
    const cells = cellsOf(t);
    if (isSep(cells)) continue;
    rows.push(cells);
  }
  return rows.length ? rows : null;
}

/** §1.3 浮条座位表（浮条 | 选择器 | 归属页 | 座位（令牌）） */
function loadFloatingRows() {
  const rows = readSpecTable(/^###\s*1\.3\b/, '选择器');
  if (!rows) return null;
  return rows.map((cells) => ({
    name: cells[0] || '',
    selectors: (cells[1] || '').split('/').map((s) => s.replace(/`/g, '').trim()).filter(Boolean),
    seat: cells[3] || '',
  })).filter((r) => r.selectors.length);
}

/** §1.4 垫层豁免表（选择器 | 理由） */
function loadExemptRows() {
  const rows = readSpecTable(/^###\s*1\.4\b/, '选择器');
  if (!rows) return null;
  return rows.map((cells) => ({
    selector: (cells[0] || '').replace(/`/g, '').trim(),
    reason: (cells[1] || '').trim(),
  })).filter((r) => r.selector);
}

// ── 扫描 ────────────────────────────────────────────────────────────────
const violations = [];
const inventory = [];
const add = (code, file, line, selector, detail) =>
  violations.push({ code, file: rel(file), line, selector: selector.trim().replace(/\s+/g, ' '), detail });

const cssFiles = existsSync(CSS_DIR)
  ? readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort().map((f) => join(CSS_DIR, f))
  : [];
if (!cssFiles.length) {
  console.error(`mobile-dock-float: css dir not found: ${CSS_DIR}`);
  process.exit(2);
}

const floatingRows = loadFloatingRows();
const exemptRows = loadExemptRows();
if (!floatingRows) {
  console.error('mobile-dock-float: 规范正文缺 §1.3「L2 浮条座位登记表」—— 没有登记表就没有约束');
  process.exit(2);
}
if (!exemptRows) {
  console.error('mobile-dock-float: 规范正文缺 §1.4「垫层豁免登记表」—— 没有豁免表就没有约束');
  process.exit(2);
}
const badExempt = exemptRows.filter((r) => r.reason.length < 4);
if (badExempt.length) {
  console.error(`mobile-dock-float: §1.4 有空理由行：${badExempt.map((b) => b.selector).join(' / ')}`);
  process.exit(2);
}

/** 收集全部规则（带来源文件） */
const rules = [];
for (const file of cssFiles) {
  const src = readFileSync(file, 'utf8');
  for (const r of walkRules(src)) rules.push({ ...r, file, decls: parseDecls(r.body) });
}
SEAT_TOKENS = seatTokenClosure(rules);

// ── F1 dock 单源形态 ────────────────────────────────────────────────────
const dockRules = rules.filter((r) => selectorsOf(r.selector).some((s) => /^(?:body\.mobile-layer\s+)?#mobile-dock$/.test(s)));
if (!dockRules.length) {
  add('F1', DOCK_FILE, 0, '#mobile-dock', 'dock 几何规则缺席（base 形态不存在 = 判据消失）');
} else {
  const base = dockRules.find((r) => r.file === DOCK_FILE) || dockRules[0];
  const need = [
    ['position', /^fixed$/i, 'position: fixed'],
    ['left', /--dock-inset-x/, 'left: var(--dock-inset-x)'],
    ['right', /--dock-inset-x/, 'right: var(--dock-inset-x)'],
    ['bottom', /env\(\s*safe-area-inset-bottom/, 'bottom: env(safe-area-inset-bottom) + var(--dock-inset-b)'],
    ['bottom', /--dock-inset-b/, 'bottom: env(safe-area-inset-bottom) + var(--dock-inset-b)'],
    ['height', /--dock-h/, 'height: var(--dock-h)'],
    ['border-radius', /--ds-radius-pill/, 'border-radius: var(--ds-radius-pill)'],
  ];
  for (const [prop, re, label] of need) {
    const value = decl(base.decls, prop);
    if (!value || !re.test(value)) {
      add('F1', base.file, base.line, base.selector, `${label} 不在场或取值不符（实读 ${prop}: ${value ?? '缺声明'}）`);
    }
  }
  // 禁形清单：贴底 / 直角 / 通栏 / 满高
  const forbid = [
    ['bottom', /^(?:0|0px)$/i, 'dock 贴底（bottom: 0）= 非悬浮形态'],
    ['left', /^(?:0|0px)$/i, 'dock 贴左缘（left: 0）= 通栏形态'],
    ['right', /^(?:0|0px)$/i, 'dock 贴右缘（right: 0）= 通栏形态'],
    ['border-radius', /^(?:0|0px)$/i, 'dock 直角（border-radius: 0）= 非胶囊形态'],
    ['width', /^(?:100%|100vw)$/i, 'dock 通栏（width: 100%）'],
    ['height', /^(?:100%|100vh|100dvh|100svh)$/i, 'dock 满高（非胶囊条形态）'],
  ];
  for (const rule of dockRules) {
    for (const [prop, re, detail] of forbid) {
      const value = decl(rule.decls, prop);
      if (value && re.test(value)) add('F1', rule.file, rule.line, rule.selector, `${detail}（${prop}: ${value}）`);
    }
  }
}

// ── F2 几何单源（其它文件只许材质）───────────────────────────────────────
const GEOMETRY_PROPS = new Set([
  'position', 'left', 'right', 'top', 'bottom', 'inset', 'width', 'height', 'min-width', 'min-height',
  'max-width', 'max-height', 'margin', 'margin-left', 'margin-right', 'margin-top', 'margin-bottom',
  'padding', 'padding-left', 'padding-right', 'border-radius', 'border-top-left-radius',
  'border-top-right-radius', 'border-bottom-left-radius', 'border-bottom-right-radius', 'transform',
]);
for (const rule of dockRules) {
  if (rule.file === DOCK_FILE) continue;
  const geom = rule.decls.filter((d) => GEOMETRY_PROPS.has(d.prop));
  if (geom.length) {
    add('F2', rule.file, rule.line, rule.selector,
      `#mobile-dock 几何声明越界（${geom.map((d) => `${d.prop}: ${d.value}`).join('; ')}）—— 几何单源 = ${rel(DOCK_FILE)}，此处只许材质`);
  }
}

// ── F3 带区无垫层 ──────────────────────────────────────────────────────
// F3a 硬判据：容器自身 + 伪元素零背景
const CONTAINER_RE = /^(?:body\.mobile-layer\s+)?#mobile-pages(?:::?(?:before|after))?$/;
for (const rule of rules) {
  const hit = selectorsOf(rule.selector).filter((s) => CONTAINER_RE.test(s));
  if (!hit.length) continue;
  const bg = decl(rule.decls, 'background') ?? decl(rule.decls, 'background-color');
  const content = decl(rule.decls, 'content');
  if (INVENTORY && (bg || content)) {
    inventory.push({ kind: 'container', file: rel(rule.file), line: rule.line, selector: rule.selector, detail: `background=${bg ?? '—'} content=${content ?? '—'}` });
  }
  if (bg && !ZERO_BG.test(bg)) {
    add('F3', rule.file, rule.line, rule.selector,
      `#mobile-pages 容器上色（background: ${bg}）—— 容器是零交互壳，一上色就盖住宿主聊天根 = dock 带区被垫层截断（D-4 硬判据，不可豁免）`);
  }
}

// F3b 兜底：底锚 + 不透明 + 撑到带区 的面必须具名登记
const MIN_COVER_PX = 32; // 低于此高度的底锚面是分隔线/描边，不是「垫层」
for (const rule of rules) {
  if (!/body\.mobile-layer/.test(rule.selector)) continue;
  const sels = selectorsOf(rule.selector);
  if (sels.some((s) => CONTAINER_RE.test(s))) continue; // 已由 F3a 判
  const bg = decl(rule.decls, 'background') ?? decl(rule.decls, 'background-color');
  if (!bg || ZERO_BG.test(bg)) continue;
  if (!bottomAnchored(rule.decls)) continue;
  const h = decl(rule.decls, 'height');
  const px = h ? parseFloat(h) : NaN;
  const coversBand = h === null ? true : /--(?:mobile-)?dock-/.test(h) ? true : (Number.isFinite(px) && px >= MIN_COVER_PX);
  if (INVENTORY && coversBand) {
    inventory.push({ kind: 'bottom-face', file: rel(rule.file), line: rule.line, selector: rule.selector, detail: `background=${bg} height=${h ?? 'auto'}` });
  }
  if (!coversBand) continue;
  const registered = exemptRows.find((row) =>
    sels.some((s) => s.includes(row.selector) || row.selector.includes(s)) ||
    sels.some((s) => s.split(/\s+/).pop() === row.selector));
  if (!registered) {
    add('F3', rule.file, rule.line, rule.selector,
      `底锚不透明面未登记（background: ${bg}, height: ${h ?? 'auto'}）—— 带区垫层会硬切内容；确为正当面请在规范 §1.4 具名登记`);
  }
}

// F3c 陈旧豁免：登记了但 CSS 里已无此面
for (const row of exemptRows) {
  const present = rules.some((r) => selectorsOf(r.selector).some((s) => s.includes(row.selector) || row.selector.includes(s)));
  if (!present) add('F3', SPEC_FILE, 0, row.selector, '§1.4 登记的选择器在 CSS 里已不存在（陈旧豁免 = 红）');
}

// ── F4 页面全高 ────────────────────────────────────────────────────────
const PAGE_RE = /^body\.mobile-layer\s+\.mobile-page$/;
const pageRules = rules.filter((r) => selectorsOf(r.selector).some((s) => PAGE_RE.test(s)));
const geomPage = pageRules.filter((r) => decl(r.decls, 'top') !== null || decl(r.decls, 'bottom') !== null || decl(r.decls, 'height') !== null);
if (!geomPage.length) {
  add('F4', DOCK_FILE, 0, '.mobile-page', '页面壳几何规则缺席（base 形态不存在 = 判据消失）');
} else {
  const topOk = pageRules.some((r) => /^(?:0|0px)$/.test(decl(r.decls, 'top') || ''));
  const bottomOk = pageRules.some((r) => /^(?:0|0px)$/.test(decl(r.decls, 'bottom') || ''));
  if (!topOk) add('F4', geomPage[0].file, geomPage[0].line, geomPage[0].selector, '页面壳缺 top: 0（未铺满视口顶）');
  if (!bottomOk) add('F4', geomPage[0].file, geomPage[0].line, geomPage[0].selector, '页面壳缺 bottom: 0（页面未铺到视口底 = dock 读起来在页面外，「内容穿过玻璃」不成立）');
  for (const r of pageRules) {
    const b = decl(r.decls, 'bottom');
    if (b && !/^(?:0|0px|auto)$/.test(b)) {
      add('F4', r.file, r.line, r.selector, `页面壳底缘被收短（bottom: ${b}）—— 禁收短到 dock 上缘（D-5）；净空归滚域末端 token`);
    }
    const h = decl(r.decls, 'height');
    if (h && /^(?:calc\(.*--mobile-dock|var\(--mobile-dock)/.test(h)) {
      add('F4', r.file, r.line, r.selector, `页面壳高度消费 dock 让位（height: ${h}）—— 页面必须全高`);
    }
  }
}

// ── F5 浮条座位 ────────────────────────────────────────────────────────
// 判据：登记过的浮条（L2）必须在**某条命中它的规则**上消费 dock 派生的座位令牌
// （`bottom` / `padding-bottom` 的取值落在令牌闭包里），且命中它的规则里不许出现
// 「贴视口底且无 dock 派生垫高」的写法（`bottom: 0` + padding 不派生 = 落进 dock 带）。
for (const row of floatingRows) {
  const matching = rules.filter((r) => row.selectors.some((sel) => hits(r, sel)));
  if (!matching.length) {
    add('F5', SPEC_FILE, 0, row.selectors.join(' / '), `§1.3 登记的浮条在 CSS 里不存在（陈旧登记 = 红）：${row.name}`);
    continue;
  }
  const seatEditor = matching.find((r) => seatOf(decl(r.decls, 'bottom')) || seatOf(decl(r.decls, 'padding-bottom')));
  if (!seatEditor) {
    add('F5', matching[0].file, matching[0].line, matching[0].selector,
      `浮条未消费 dock 派生座位令牌（登记档 = ${row.seat || '空'}）—— 座位必须由 dock 派生，禁自算像素 / 贴视口底`);
  }
  for (const r of matching) {
    // 「落进 dock 带」只在手机层有意义（dock 只存在于 body.mobile-layer）：桌面基础规则
    // （如 input.css `#input-area` 的桌面档 bottom: 0）不在本判据面上，由手机层规则覆盖。
    if (!/body\.mobile-layer/.test(r.selector)) continue;
    const b = decl(r.decls, 'bottom');
    const p = decl(r.decls, 'padding-bottom');
    if (b !== null && /^(?:0|0px)$/.test(b) && !seatOf(p)) {
      add('F5', r.file, r.line, r.selector, '页面内浮条贴视口底且无 dock 派生垫高（bottom: 0 且 padding-bottom 不派生）= 落进 dock 带（D-6）');
    }
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────
const codes = [...new Set(violations.map((v) => v.code))].sort();
const result = { ok: violations.length === 0, spec: rel(SPEC_FILE), violations };

if (JSON_OUT) {
  console.log(JSON.stringify(result, null, 2));
  process.exit(violations.length ? 1 : 0);
}

if (INVENTORY) {
  console.log(`mobile-dock-float inventory — ${inventory.length} 条面\n`);
  for (const kind of ['container', 'bottom-face']) {
    const rows = inventory.filter((i) => i.kind === kind);
    console.log(`## ${kind}  (${rows.length})`);
    for (const r of rows) console.log(`  ${r.file}:${r.line}  ${r.selector}\n      ${r.detail}`);
    console.log('');
  }
  const dockRows = dockRules.map((r) => `${rel(r.file)}:${r.line}  ${r.selector}`);
  console.log(`## #mobile-dock  ({${dockRules.length}})`);
  for (const r of dockRows) console.log(`  ${r}`);
  console.log(`\nTOTAL=${inventory.length} DOCK_RULES=${dockRules.length} EXEMPT=${exemptRows.length} FLOATING=${floatingRows.length}`);
  process.exit(0);
}

if (violations.length) {
  console.error(`mobile-dock-float: RED — ${violations.length} 违规（${codes.join('/')}）`);
  const byCode = new Map();
  for (const v of violations) {
    if (!byCode.has(v.code)) byCode.set(v.code, []);
    byCode.get(v.code).push(v);
  }
  for (const [code, rows] of [...byCode.entries()].sort()) {
    console.error(`\n  ${code}  (${rows.length})`);
    for (const r of rows) console.error(`    ${r.code}  ${r.file}:${r.line}  ${r.selector}\n        ${r.detail}`);
  }
  process.exit(1);
}
console.log(`mobile-dock-float: OK — dock 全页悬浮（胶囊单源 / 带区零垫底 / 页面全高 / 浮条座位派生；豁免 ${exemptRows.length} 条、浮条 ${floatingRows.length} 条已具名登记）`);
process.exit(0);
