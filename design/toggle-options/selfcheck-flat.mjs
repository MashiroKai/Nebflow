#!/usr/bin/env node
// selfcheck-flat.mjs — 把方案页 CSS 投进**真实的** scripts/check-flat-buttons.mjs 跑
//
// 判据不在这里复制一份（复制即漂移）。做法：在临时目录里搭一个与仓同构的最小树
//   <tmp>/scripts/check-flat-buttons.mjs                     ← 逐字节复制仓内脚本
//   <tmp>/src/main/resources/web/css/toggle-options.css       ← 本页 CSS
//   <tmp>/src/main/resources/seed/plugins/.../flat-button-spec.md  ← 逐字节复制例外清单源
// 脚本的 ROOT 由自身位置推出来（check-flat-buttons.mjs:44），所以它在临时树里扫的
// 就是本页 CSS —— 判据、例外清单、退出码全部是真门禁本体。
//
// 三段读数：
//   ① 真门禁读本页 CSS          → 期望 exit 0
//   ② 真门禁读**注入违规**的副本 → 期望 exit 1（证明它真的在看这个文件，不是空转）
//   ③ 全文裸串扫描（超集）      → 期望 0 处（覆盖 .tog__knob 这类子部件选择器：
//      真门禁按「主题复合选择器」判定按钮面，子部件不在其判定面内，故补这一道）
//
// 用法：node design/toggle-options/selfcheck-flat.mjs

import { mkdtempSync, mkdirSync, copyFileSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..');

const GATE = join(REPO, 'scripts', 'check-flat-buttons.mjs');
const SPEC = join(
  REPO, 'src', 'main', 'resources', 'seed', 'plugins', 'design-language',
  'skills', 'design-language', 'references', 'flat-button-spec.md',
);
const CSS = join(HERE, 'toggle.css');

const TMP = mkdtempSync(join(tmpdir(), 'toggle-flat-probe-'));
const CSS_DIR = join(TMP, 'src', 'main', 'resources', 'web', 'css');
const SPEC_DIR = dirname(join(
  TMP, 'src', 'main', 'resources', 'seed', 'plugins', 'design-language',
  'skills', 'design-language', 'references', 'flat-button-spec.md',
));

mkdirSync(join(TMP, 'scripts'), { recursive: true });
mkdirSync(CSS_DIR, { recursive: true });
mkdirSync(SPEC_DIR, { recursive: true });
copyFileSync(GATE, join(TMP, 'scripts', 'check-flat-buttons.mjs'));
copyFileSync(SPEC, join(SPEC_DIR, 'flat-button-spec.md'));
copyFileSync(CSS, join(CSS_DIR, 'toggle-options.css'));

function runGate() {
  const r = spawnSync(process.execPath, [join(TMP, 'scripts', 'check-flat-buttons.mjs')], { encoding: 'utf8' });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') };
}

let failures = 0;
const say = (ok, msg) => {
  if (!ok) failures++;
  console.log(`${ok ? '✓' : '✗'} ${msg}`);
};

console.log(`# 判据源 = ${relative(REPO, GATE)}（逐字节复制到 ${TMP}）`);
console.log(`# 被检文件 = ${relative(REPO, CSS)}\n`);

// ── ① 本页 CSS 过真门禁 ─────────────────────────────────────────────────
const clean = runGate();
const summary = (clean.out.match(/✓[^\n]*|✗[^\n]*/) || [clean.out.trim()])[0];
say(clean.code === 0, `① 真门禁读本页 CSS：exit ${clean.code} — ${summary}`);

// ── ② 注入违规（验红：证明门禁真的在看这个文件）─────────────────────────
const original = readFileSync(join(CSS_DIR, 'toggle-options.css'), 'utf8');
const poisoned = `${original}\n/* 验红注入：应当被判 F1/F3/F5 */\nbutton.togA:hover {\n box-shadow: 0 1px 2px #00000040;\n background: linear-gradient(#fff, #000);\n transform: translateY(1px);\n}\n`;
writeFileSync(join(CSS_DIR, 'toggle-options.css'), poisoned);
const red = runGate();
const hits = (red.out.match(/\[F\d\]/g) || []).length;
say(red.code === 1 && hits >= 3, `② 验红（注入 box-shadow + 渐变 + translateY）：exit ${red.code}、命中 ${hits} 条禁项`);
writeFileSync(join(CSS_DIR, 'toggle-options.css'), original);

// ── ③ 全文裸串扫描（超集：覆盖子部件选择器）─────────────────────────────
const text = original.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
const RULES = [
  { id: 'F1', label: 'box-shadow', re: /box-shadow\s*:\s*(?!none)/g },
  { id: 'F2', label: 'text-shadow', re: /text-shadow\s*:\s*(?!none)/g },
  { id: 'F3', label: '渐变', re: /(?:repeating-)?(?:linear|radial|conic)-gradient\s*\(/g },
  { id: 'F4', label: 'drop-shadow', re: /drop-shadow\s*\(/g },
  { id: 'F5', label: 'translateY / y 轴位移', re: /translateY\s*\(|translate3d\s*\([^)]*,[^,)]*[1-9]/g },
];
const counts = RULES.map((r) => ({ ...r, n: (text.match(r.re) || []).length }));
say(
  counts.every((c) => c.n === 0),
  `③ 全文超集扫描（含 .tog__knob / .tog__pill 等子部件）：${counts.map((c) => `${c.id}×${c.n}`).join(' · ')}`,
);

// ── ④ 顺带记：tokens 只许出现在 :root / [data-theme] 块（方案页零裸色值散落）──
console.log(`\n# 记录（不判红）：${clean.out.trim().split('\n')[0]}`);

rmSync(TMP, { recursive: true, force: true });

console.log(`\n${failures === 0 ? '✓ 全部通过' : `✗ ${failures} 项失败`}`);
process.exit(failures === 0 ? 0 : 1);
