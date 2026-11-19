#!/usr/bin/env node
// No-clip floating gate — static leg (author order 2026-11-18 22:01).
//
// The invariant family it guards (single source = the stand-dev plugin spec
// `references/frontend/visual-style.md` 铁律 9「浮层不截断」):
//   * a floating layer / card must show all four edges inside the viewport,
//     the TOP edge especially; no ancestor overflow/clip-path/contain may cut it;
//   * text must not be truncated to "look tidy" (`text-overflow: ellipsis`,
//     `-webkit-line-clamp`), it wraps and grows instead;
//   * a height cap must be paired with a scrollable axis (`overflow-y:auto`),
//     never with `hidden`/`clip`.
//
// This is a conservative source-text ratchet, not a layout inference: it flags
// the banned declaration combinations and matches them against a NAMED ledger
// of current violations. Only violations OUTSIDE the ledger are red. The ledger
// may only shrink (fixing a site deletes its line); adding an entry is an author
// ruling, so this tool never writes it (`--emit-ledger` only prints candidates).
//
// Exit: 0 = green, 1 = red (unledgered violation), 2 = SKIP/environment (no web
// tree). Under CI a SKIP is escalated to FAIL — see `--ci` / CI env detection.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, resolve, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const DEFAULT_WEB = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const DEFAULT_LEDGER = join(ROOT, 'scripts', 'noclip-floating-baseline.json');

// ── rule catalog (the single vocabulary shared by both legs) ────────────────
export const RULES = {
  'text-truncation': '文本截断（text-overflow:ellipsis / line-clamp）',
  'clip-no-scroll': '高度上限 + overflow hidden/clip 且无可滚轴（静默裁切）',
  'cap-without-scroll': 'height/max-height 上限无 overflow-y:auto|scroll（内容会被截）',
  'clip-path': 'clip-path 裁切浮层/内容',
  'contain-clip': 'contain: paint|strict|content 裁切浮层/内容',
  'float-overflow-hidden': '浮动定位（fixed/absolute/sticky）+ overflow hidden/clip',
};

const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));

/** Minimal block walker (repo convention: no CSS parser dependency is vendored). */
export function* walkRules(source) {
  let i = 0;
  while (i < source.length) {
    const open = source.indexOf('{', i);
    if (open === -1) break;
    const close = source.indexOf('}', open + 1);
    if (close === -1) break;
    const selector = source.slice(i, open).replace(/\s+/g, ' ').trim();
    const body = source.slice(open + 1, close);
    if (selector && !body.includes('{')) {
      const start = open;
      const clean = selector.replace(/^[;{}]*\s*/, '');
      // Skip at-rule preludes and keyframe stops — they are not element rules.
      if (!clean.startsWith('@') && !/^(from|to|\d+(?:\.\d+)?%)$/.test(clean)) {
        yield { selector: clean, body, line: source.slice(0, start).split('\n').length };
      }
      i = close + 1;
    } else {
      i = open + 1;
    }
  }
}

const decls = (body) => [...body.matchAll(/(?:^|;)\s*([a-z-]+)\s*:\s*([^;]+)/gi)]
  .map((m) => ({ prop: m[1].toLowerCase(), value: m[2].replace(/\s+/g, ' ').trim() }));

const get = (list, prop) => list.filter((d) => d.prop === prop).map((d) => d.value);

const FLOATING_HINT = /(?:^|[\s,>+~])[.#:]?[a-z-]*(?:float|overlay|modal|dialog|sheet|drawer|pop|panel|card|lightbox|toast|banner|dock|pill|capsule|dropdown|menu|option-box|deck|stack|fab|hud|tooltip|tip)\b/i;

/** The four-edge/no-clip rules applied to a single CSS rule block. */
export function ruleViolations(sel, body) {
  const selector = sel;
  const d = decls(body);
  const out = [];
  const overflowValues = [...get(d, 'overflow'), ...get(d, 'overflow-x'), ...get(d, 'overflow-y')];
  const hasScrollAxis = overflowValues.some((v) => /auto|scroll/.test(v));
  const clips = overflowValues.some((v) => /\b(hidden|clip)\b/.test(v));
  // `max-height` is the canonical height cap; a bare `height` only counts when
  // the box is positioned as a floating layer (icons/avatars use `height`).
  const floatPos = get(d, 'position').some((v) => /^(fixed|absolute|sticky)$/i.test(v));
  const cap = get(d, 'max-height').some((v) => /^\d|calc\(/.test(v)) || (floatPos && get(d, 'height').some((v) => /^\d|calc\(/.test(v)));

  if (get(d, 'text-overflow').some((v) => /ellipsis/i.test(v)) || d.some((x) => /line-clamp/.test(x.prop))) {
    out.push('text-truncation');
  }
  if (cap && clips && !hasScrollAxis) out.push('clip-no-scroll');
  if (cap && !hasScrollAxis && !clips) out.push('cap-without-scroll');
  if (get(d, 'clip-path').some((v) => v && v !== 'none')) out.push('clip-path');
  if (get(d, 'contain').some((v) => /paint|strict|content/.test(v))) out.push('contain-clip');
  const textLine = get(d, 'text-overflow').length > 0 || d.some((x) => /line-clamp/.test(x.prop))
    || get(d, 'white-space').some((v) => /nowrap/.test(v));
  if (clips && !hasScrollAxis && !textLine && (floatPos || FLOATING_HINT.test(sel)) ) out.push('float-overflow-hidden');
  return out;
}

function cssFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return name === 'vendor' ? [] : cssFiles(p);
    return p.endsWith('.css') ? [p] : [];
  });
}

/** @returns {{entries:Array, files:number}} the full current violation inventory. */
export function scan({ webRoot = DEFAULT_WEB, webLabel = 'src/main/resources/web/css' } = {}) {
  const entries = [];
  const files = cssFiles(webRoot);
  for (const file of files) {
    const rel = `${webLabel}/${relative(webRoot, file)}`.split('\\').join('/');
    const source = stripComments(readFileSync(file, 'utf8'));
    for (const r of walkRules(source)) {
      for (const rule of ruleViolations(r.selector, r.body)) {
        entries.push({ file: rel, rule, selector: r.selector, line: r.line, key: `${rel}::${rule}::${r.selector}` });
      }
    }
  }
  return { entries, files: files.length };
}

export function loadLedger(ledgerPath = DEFAULT_LEDGER) {
  if (!existsSync(ledgerPath)) return { entries: [], ok: true };
  const data = JSON.parse(readFileSync(ledgerPath, 'utf8'));
  const entries = data.static || data.entries || [];
  return { entries, ok: true, data };
}

/** Compare the scan against the ledger. Red = violation key outside the ledger. */
export function evaluate(scanResult, ledger) {
  const known = new Map(ledger.entries.map((e) => [e.key, e]));
  const fresh = scanResult.entries.filter((e) => !known.has(e.key));
  const present = new Set(scanResult.entries.map((e) => e.key));
  const improved = ledger.entries.filter((e) => !present.has(e.key));
  return { fresh, improved, total: scanResult.entries.length, ledgerSize: ledger.entries.length };
}

function ciMode(argv) {
  if (argv.includes('--ci')) return true;
  return Boolean(process.env.CI || process.env.GITLAB_CI || process.env.GITHUB_ACTIONS);
}

function main() {
  const argv = process.argv.slice(2);
  const argOf = (name) => { const i = argv.indexOf(name); return i === -1 ? null : argv[i + 1]; };
  const webRoot = argOf('--web-root') ? resolve(argOf('--web-root')) : DEFAULT_WEB;
  const webLabel = argOf('--web-label') || 'src/main/resources/web/css';
  const ledgerPath = argOf('--ledger') ? resolve(argOf('--ledger')) : DEFAULT_LEDGER;

  if (!existsSync(webRoot)) {
    const msg = `SKIP: no web css tree at ${webRoot}`;
    if (ciMode(argv)) { console.error(`noclip-floating: ${msg} — counted as FAIL under CI`); process.exit(1); }
    console.error(`noclip-floating: ${msg} (exit 2 = environment, not red)`);
    process.exit(2);
  }

  let result;
  try {
    result = scan({ webRoot, webLabel });
  } catch (error) {
    console.error(`noclip-floating: scan error: ${error.message}`);
    process.exit(2);
  }

  if (argv.includes('--emit-ledger')) {
    console.log(JSON.stringify({ entries: result.entries.map(({ key, file, rule, selector }) => ({ key, file, rule, selector, reason: '', owner: '' })) }, null, 2));
    return;
  }
  if (argv.includes('--json')) {
    console.log(JSON.stringify(result.entries, null, 2));
    return;
  }
  if (!existsSync(ledgerPath) && !argv.includes('--no-ledger')) {
    console.error(`noclip-floating: missing named ledger ${ledgerPath} (every current violation must be registered by name; a new one needs an author ruling)`);
    process.exit(1);
  }

  const ledger = argv.includes('--no-ledger') ? { entries: [] } : loadLedger(ledgerPath);
  const { fresh, improved, total, ledgerSize } = evaluate(result, ledger);
  const byFile = {};
  for (const e of fresh) byFile[e.file] = (byFile[e.file] || 0) + 1;
  if (fresh.length) {
    console.error(`noclip-floating: ${fresh.length} violation(s) outside the named ledger (scanned ${total} total, ledger ${ledgerSize}):`);
    for (const e of fresh.slice(0, 200)) console.error(`  ${e.file}:${e.line}  [${e.rule}]  ${e.selector}`);
    console.error(`  by file: ${JSON.stringify(byFile)}`);
    process.exit(1);
  }
  console.log(`noclip-floating (static): PASS — ${total} known violation(s) all ledgered${improved.length ? `, ${improved.length} ledger entr(ies) now gone (shrink the ledger)` : ''}`);
  if (improved.length && argv.includes('--strict-shrink')) {
    console.error(`noclip-floating: ${improved.length} ledger entries no longer match any source (delete them)`);
    process.exit(1);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
