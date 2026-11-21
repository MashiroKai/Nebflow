#!/usr/bin/env node
// check-panel-unify.mjs — 面板设计语言唯一性静态哨兵
// （panel-language 批 2026-11-06；作者令 contact-panel-unify 链逐字：
//   (c)「面板宽度与形态：不要做成弹窗，要运用整个面板的宽度」
//   (d)「按钮去重：不要保留功能重复的按钮，比如已经有返回键了，就不要再放叉号键（X）」
//   (e)「规范统一：顶部文字大小等面板设计，可以参考项目面板，统一整理出一套面板的设计语言」）
//
// Why a gate：面板此前各画各的——标题字号 14px/600 裸字重散在 cfg-modal 族、
// 卡名行裸 600、执行器卡又一套；好友/群信息面板在手机层同时有返回键和 X 键
// （mobileSheetBack 注入 .mfs-back + renderBody 无条件 .pf-close），改前同一句
// 「面板标题」在哪个文件里长成什么样取决于写那一面的人当天的手感。手工审查
// 抓不住十几张面，所以把「同类面板一套版式」「有返回键不放 X」变成机器判据。
//
// ── 判据 ────────────────────────────────────────────────────────────────
//  P1 同类面板标题只能取规定令牌（裸字面量即红）：panel-spec.md §1.1「面板标题
//     登记表」逐行机检——登记选择器在 CSS/JS 样式源里必须存在；字号/字重/行高
//     令牌列（非 —）的声明必须在场，且值逐字 = 令牌（var(--ds-panel-title-fs)
//     形态；裸 15px / 600 / 借别的令牌名 = 红）。
//  P2 有返回键的面不许同时有 X 键（3(d) 机械判据）：panel-spec.md §7.1「按钮去重
//     登记表」逐行机检，按判定档：
//       · dom-guard：模块调 mobileSheetBack（返回键在）+ 关闭键锚形（创建形态
//         字面量）在去注释源里恰好 1 处 + 该处 ±3 行内必须见 isMobileLayer( 守卫
//         （判定单源 = popSheet.isMobileLayer，禁各面板自写 body.classList 检查）。
//       · css-hide：mobileLayer.css 必须仍有 `body.mobile-layer <#锚>` 隐藏规则
//         （沿袭 2026-10-30 重复返回钮清查批机制的面，防「今天隐藏了、明天被删」）。
//       · record：记录行（语义豁免在表内「删后可达性」列写明）。
//  P3 例外表卫生：panel-spec.md §8 例外清单逐行——理由为空/过短 ⇒ 红；例外指向
//     的选择器在 JS/CSS 语料里根本不存在（陈旧例外）⇒ 红。
//  P4 令牌在场：登记表引用的每一个 --* 令牌必须在某个 css 文件里声明（§⑲ 槽
//     或既有刻度槽）——规范引用了不存在的令牌 = 规范本身失效。
//
// 判据与术语的**规范侧**（人读条文、登记表、例外登记）在仓内
//   design-language/references/frontend/panel-spec.md
// （定位见 scripts/lib/design-spec-source.mjs）。
// 本脚本只做它机械可判的那部分；改判据要先改规范。
//
// 用法：
//   node scripts/check-panel-unify.mjs             # 门禁（红/绿）
//   node scripts/check-panel-unify.mjs --json      # 机器读
//   node scripts/check-panel-unify.mjs --inventory # 盘点（P1 命中/P2 登记/未收编清查）
//   node scripts/check-panel-unify.mjs --root=<dir># 跑在另一棵检出（改前/改后对照）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
/** 规范正文（单源在仓内 design-language/references/frontend/）。 */
const SPEC = resolveDesignSpec('references/frontend/panel-spec.md');
const MOBILE_LAYER = join(CSS_DIR, 'mobileLayer.css');

const rel = (p) => relative(ROOT, p).replaceAll('\\', '/');

// ── 解析工具 ─────────────────────────────────────────────────────────────

/** 去注释但保留行号（CSS 块注释 / JS 行+块注释，等量换行）。 */
function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}
function stripJsComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
}

/** 极简 CSS 规则块遍历器 → { selector, decls:[{prop,value,line}] }（同 check-glass-unify 族）。 */
function* walkRules(src) {
  const text = stripCssComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  let i = 0;
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      if (prelude.startsWith('@')) { // at-rule prelude 整段跳过（本闸不需要嵌套语境）
        let depth = 1;
        i++;
        while (i < text.length && depth > 0) {
          if (text[i] === '{') depth++;
          else if (text[i] === '}') depth--;
          i++;
        }
        selStart = i;
        continue;
      }
      const bodyStart = i + 1;
      let j = bodyStart;
      let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') { if (inner === 0) break; inner--; }
        j++;
      }
      const body = text.slice(bodyStart, j);
      const decls = [];
      let cur = '';
      let declStart = bodyStart;
      for (let k = 0; k <= body.length; k++) {
        const c = body[k];
        if (c === ';' || k === body.length) {
          const raw = cur;
          const colon = raw.indexOf(':');
          if (colon > 0) {
            decls.push({
              prop: raw.slice(0, colon).trim().toLowerCase(),
              value: raw.slice(colon + 1).trim().replace(/\s+/g, ' '),
              line: lineOf(declStart),
            });
          }
          cur = '';
          declStart = bodyStart + k + 1;
        } else {
          if (c === '\n') declStart = bodyStart + k + 1;
          cur += c;
        }
      }
      yield { selector: prelude.replace(/\s+/g, ' '), decls, line: lineOf(selStart) };
      i = j + 1;
      selStart = i;
      continue;
    }
    i++;
  }
}

/** 规范 markdown 里的表格 → [{ header:[...], rows:[[...]] }]（同 check-glass-unify 解析法）。 */
function parseTables(specSrc) {
  const tables = [];
  let cur = null;
  for (const line of specSrc.split('\n')) {
    const t = line.trim();
    if (!t.startsWith('|')) { cur = null; continue; }
    const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map((x) => x.trim());
    if (/^[-:| ]+$/.test(cells.join(''))) continue; // 分隔行
    if (!cur) { cur = { header: cells, rows: [] }; tables.push(cur); continue; }
    cur.rows.push(cells);
  }
  return tables;
}
const clean = (s) => String(s || '').replace(/`/g, '').trim();
const escRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** 登记选择器（`.x`）是否作为类 token 出现在选择器串里（`.x-y` 不算命中）。 */
const selRe = (sel) => new RegExp(`${escRe(sel)}(?![\\w-])`);

// ── 规范登记表 ───────────────────────────────────────────────────────────

const violations = [];
const add = (v) => violations.push(v);
const inventory = { p1: [], p2: [], exceptions: [], unsanctioned: [] };

if (!SPEC) {
  console.error(`✗ panel-unify gate 不可跑 —— ${specMissingMessage('references/frontend/panel-spec.md')}`);
  process.exit(2);
}
const specSrc = readFileSync(SPEC, 'utf8');
const tables = parseTables(specSrc);

// P1 表：表头含「选择器」+「字号令牌」
const p1Table = tables.find((t) => t.header.some((h) => /选择器/.test(h)) && t.header.some((h) => /字号令牌/.test(h)));
// P2 表：表头含「模块」+「关闭键锚」+「判定档」
const p2Table = tables.find((t) => t.header.some((h) => /关闭键锚/.test(h)) && t.header.some((h) => /判定档/.test(h)));
// 例外表：表头含「选择器」+「理由」
const excTable = tables.find((t) => t.header.some((h) => /^理由$/.test(h)) && t.header.some((h) => /选择器/.test(h)));

if (!p1Table) add({ rule: 'P1', file: SPEC, line: 0, selector: '-', detail: '缺少「面板标题登记表」表', why: '没有登记表 = 面板标题无约束；先改规范再加面' });
if (!p2Table) add({ rule: 'P2', file: SPEC, line: 0, selector: '-', detail: '缺少「按钮去重登记表」表', why: '没有登记表 = 返回键/X 去重无约束；先改规范再加面' });

// ── 样式语料（CSS 走规则块；JS 内嵌 POPUP_CSS 走选择器切片）────────────────
const cssFiles = readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()
  .map((f) => ({ file: join(CSS_DIR, f), src: readFileSync(join(CSS_DIR, f), 'utf8') }));
const jsFiles = readdirSync(JS_DIR).filter((x) => x.endsWith('.js')).sort()
  .map((f) => ({ file: join(JS_DIR, f), src: readFileSync(join(JS_DIR, f), 'utf8') }));

/** 收集「选择器命中 sel」的所有标题相关 decl（CSS 全量 + JS 切片）。 */
function collectDecls(sel) {
  const hits = [];
  const re = selRe(sel);
  for (const { file, src } of cssFiles) {
    for (const rule of walkRules(src)) {
      if (!re.test(rule.selector)) continue;
      for (const d of rule.decls) hits.push({ file: rel(file), line: d.line, selector: rule.selector, prop: d.prop, value: d.value });
    }
  }
  for (const { file, src } of jsFiles) {
    const text = stripJsComments(src);
    let idx = text.indexOf(sel);
    while (idx >= 0) {
      // 选择器切片：从命中处到下一个 `}` 之间按声明正则抓（POPUP_CSS 一类模板串）
      const end = text.indexOf('}', idx);
      if (end > idx && end - idx < 600) {
        const slice = text.slice(idx, end);
        for (const prop of ['font-size', 'font-weight', 'line-height']) {
          const m = slice.match(new RegExp(`${prop}\\s*:\\s*([^;}]+)`));
          if (m) hits.push({ file: rel(file), line: text.slice(0, idx).split('\n').length, selector: `${sel}（JS 内嵌样式）`, prop, value: m[1].trim().replace(/\s+/g, ' ') });
        }
      }
      idx = text.indexOf(sel, idx + sel.length);
    }
  }
  return hits;
}

// ── P4 令牌在场 + P1 标题登记表 ──────────────────────────────────────────
const allCssText = cssFiles.map((f) => f.src).join('\n');
const tokenDeclared = (tok) => new RegExp(`${escRe(tok)}\\s*:`).test(allCssText);

if (p1Table) {
  for (const row of p1Table.rows) {
    const [slot, sel, face, fsTok, fwTok, lhTok] = row.map(clean);
    if (!sel || !sel.startsWith('.')) continue; // 表头/空行
    const want = [
      ['font-size', fsTok],
      ['font-weight', fwTok],
      ['line-height', lhTok],
    ].filter(([, tok]) => tok && tok !== '—' && tok !== '-');
    const entry = { slot, selector: sel, face, ok: true, detail: '', decls: [] };
    if (!want.length) { entry.detail = '登记行没有令牌列'; entry.ok = false; }
    for (const [, tok] of want) {
      if (!tokenDeclared(tok)) {
        entry.ok = false;
        entry.detail += `令牌 ${tok} 未在任何 css 声明；`;
      }
    }
    const decls = collectDecls(sel);
    entry.decls = decls.map((d) => `${d.prop}: ${d.value}`);
    if (!decls.length) {
      entry.ok = false;
      entry.detail += '登记面在样式源里找不到（陈旧登记？）';
    }
    for (const [prop, tok] of want) {
      const mine = decls.filter((d) => d.prop === prop);
      if (!mine.length) {
        entry.ok = false;
        entry.detail += `${prop} 缺声明（应 = ${tok}）；`;
        continue;
      }
      for (const d of mine) {
        if (d.value !== `var(${tok})`) {
          entry.ok = false;
          entry.detail += `${prop}: ${d.value} ≠ ${tok}（裸字面量/借档 = 红）；`;
        }
      }
    }
    inventory.p1.push(entry);
    if (!entry.ok) {
      add({ rule: 'P1', file: SPEC, line: 0, selector: sel, detail: entry.detail, why: `面板标题登记槽 ${slot} 漂移：同类面板标题只能取规定令牌（裸字面量即红）` });
    }
  }
}

// ── P2 按钮去重登记表 ────────────────────────────────────────────────────
if (p2Table) {
  const mobileSrc = existsSync(MOBILE_LAYER) ? stripCssComments(readFileSync(MOBILE_LAYER, 'utf8')) : '';
  for (const row of p2Table.rows) {
    const [face, moduleCell, backCell, anchorCell, modeCell] = row.map(clean);
    const mode = modeCell || '';
    if (!mode || !['dom-guard', 'css-hide', 'record'].includes(mode)) continue; // 表头/空行
    const entry = { face, module: moduleCell, anchor: anchorCell, mode, ok: true, detail: '' };
    if (mode === 'dom-guard') {
      const modPath = join(JS_DIR, moduleCell.replace(/^js\//, ''));
      if (!existsSync(modPath)) {
        entry.ok = false;
        entry.detail = '登记模块不存在（陈旧登记？）';
      } else {
        const src = stripJsComments(readFileSync(modPath, 'utf8'));
        const lines = src.split('\n');
        const anchor = anchorCell;
        const occ = [];
        let idx = src.indexOf(anchor);
        while (idx >= 0) { occ.push(idx); idx = src.indexOf(anchor, idx + anchor.length); }
        if (!/mobileSheetBack\s*\(/.test(src)) {
          entry.ok = false;
          entry.detail += '模块未调 mobileSheetBack（登记返回键但找不到返回注入）；';
        }
        if (occ.length !== 1) {
          entry.ok = false;
          entry.detail += `锚形出现 ${occ.length} 处（应恰 1 处 = 创建形态）；`;
        } else {
          const lineNo = src.slice(0, occ[0]).split('\n').length - 1;
          const win = lines.slice(Math.max(0, lineNo - 3), lineNo + 3).join('\n');
          if (!/isMobileLayer\s*\(/.test(win)) {
            entry.ok = false;
            entry.detail += `锚行（L${lineNo + 1}）±3 行内无 isMobileLayer( 守卫（有返回键的面不许同时建 X）；`;
          }
        }
      }
    } else if (mode === 'css-hide') {
      const sel = anchorCell.startsWith('#') ? anchorCell : `#${anchorCell}`;
      const re = new RegExp(`body\\.mobile-layer\\s+${escRe(sel)}\\b`);
      if (!re.test(mobileSrc)) {
        entry.ok = false;
        entry.detail = `mobileLayer.css 缺少 body.mobile-layer ${sel} 隐藏规则（2026-10-30 清查批机制漂移）`;
      }
    }
    inventory.p2.push(entry);
    if (!entry.ok) {
      add({ rule: 'P2', file: SPEC, line: 0, selector: anchorCell, detail: entry.detail, why: `按钮去重登记面「${face}」漂移：有返回键的面不许同时有 X 键（3(d) 机械判据）` });
    }
  }
}

// ── P3 例外表卫生 ────────────────────────────────────────────────────────
if (excTable) {
  const corpus = jsFiles.map((f) => f.src).join('\n') + allCssText;
  for (const row of excTable.rows) {
    const sel = clean(row[0]);
    const reason = clean(row.slice(2).join(' | '));
    if (!sel) continue;
    const name = sel.replace(/^\./, '');
    const realExists = new RegExp(escRe(name)).test(corpus);
    const entry = { selector: sel, reason, ok: true, detail: '' };
    if (!reason || reason.length < 8) {
      entry.ok = false;
      entry.detail = '例外理由为空/过短';
    } else if (!realExists) {
      entry.ok = false;
      entry.detail = '陈旧例外：样式源里找不到这个选择器';
    }
    inventory.exceptions.push(entry);
    if (!entry.ok) {
      add({ rule: 'P3', file: SPEC, line: 0, selector: sel, detail: entry.detail, why: '例外必须有具名登记处与理由（空理由 / 陈旧例外 = 红，豁免不许空转）' });
    }
  }
} else {
  add({ rule: 'P3', file: SPEC, line: 0, selector: '-', detail: '缺少「例外清单」表', why: '例外必须有具名登记处；没有表 = 豁免无账本' });
}

// ── 未收编清查（盘点面，不判红）：调 mobileSheetBack 且建 close 键、既无守卫也未登记 ──
for (const { file, src } of jsFiles) {
  const text = stripJsComments(src);
  if (!/mobileSheetBack\s*\(/.test(text)) continue;
  const lines = text.split('\n');
  const registered = new Set((p2Table ? p2Table.rows : [])
    .map((r) => clean(r[3])).filter(Boolean));
  for (let n = 0; n < lines.length; n++) {
    const m = lines[n].match(/(?:className\s*=\s*|class(?:Name)?\s*=\s*['"`])['"`]?[\w-]*-close[\w-]*/);
    if (!m) continue;
    const win = lines.slice(Math.max(0, n - 3), n + 3).join('\n');
    if (/isMobileLayer\s*\(/.test(win)) continue;
    if ([...registered].some((a) => lines[n].includes(a))) continue;
    inventory.unsanctioned.push({ file: rel(file), line: n + 1, text: lines[n].trim().slice(0, 90) });
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────
const argv = new Set(process.argv.slice(2).filter((a) => !a.startsWith('--root=')));

if (argv.has('--json')) {
  console.log(JSON.stringify({
    ok: violations.length === 0,
    spec: SPEC,
    p1: inventory.p1,
    p2: inventory.p2,
    exceptions: inventory.exceptions,
    unsanctionedBackPlusClose: inventory.unsanctioned,
    violations,
  }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

if (argv.has('--inventory')) {
  console.log(`规范 = ${SPEC}`);
  console.log(`\n── P1 面板标题登记（${inventory.p1.length} 行，红 ${inventory.p1.filter((r) => !r.ok).length}）──`);
  for (const r of inventory.p1) {
    console.log(`  ${r.ok ? '✓' : '✗'} ${r.slot} ${r.selector}${r.ok ? '' : `  ← ${r.detail}`}`);
    if (r.ok) for (const d of r.decls) console.log(`      ${d}`);
  }
  console.log(`\n── P2 按钮去重登记（${inventory.p2.length} 行，红 ${inventory.p2.filter((r) => !r.ok).length}）──`);
  for (const r of inventory.p2) {
    console.log(`  ${r.ok ? '✓' : '✗'} [${r.mode}] ${r.face}${r.ok ? '' : `  ← ${r.detail}`}`);
  }
  console.log(`\n── P3 例外（${inventory.exceptions.length} 行，红 ${inventory.exceptions.filter((r) => !r.ok).length}）──`);
  for (const r of inventory.exceptions) {
    console.log(`  ${r.ok ? '✓' : '✗'} ${r.selector} — ${r.reason.slice(0, 46)}${r.ok ? '' : `  ← ${r.detail}`}`);
  }
  console.log(`\n── 未收编清查（调 mobileSheetBack 且建 close 键、无守卫未登记：${inventory.unsanctioned.length} 处，盘点不判红）──`);
  for (const r of inventory.unsanctioned) console.log(`  ${r.file}:${r.line}  ${r.text}`);
  process.exit(violations.length ? 1 : 0);
}

if (violations.length) {
  console.error('✗ panel-unify gate FAILED');
  for (const v of violations) {
    console.error(`  [${v.rule}] ${v.detail}`);
    if (v.selector && v.selector !== '-') console.error(`      ${v.selector}`);
    console.error(`      ${v.why}`);
  }
  console.error('\n处置：改规范先 —— 面板标题登记表（§1.1）/ 按钮去重登记表（§7.1）/ 例外清单（§8）');
  console.error(`        都在 ${SPEC}；登记令牌 = design-system.css §⑲ --ds-panel-*；`);
  console.error('        守卫单源 = js/mobile/popSheet.js isMobileLayer()。');
  process.exit(1);
}

console.log(
  `✓ panel-unify gate passed — 面板标题登记 ${inventory.p1.length} 槽全取规定令牌、` +
  `按钮去重登记 ${inventory.p2.length} 面（dom-guard ${inventory.p2.filter((r) => r.mode === 'dom-guard').length} / ` +
  `css-hide ${inventory.p2.filter((r) => r.mode === 'css-hide').length} / record ${inventory.p2.filter((r) => r.mode === 'record').length}）、` +
  `例外 ${inventory.exceptions.length} 条、未收编清查 ${inventory.unsanctioned.length} 处（盘点项）`,
);
process.exit(0);
