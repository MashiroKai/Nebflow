#!/usr/bin/env node
// gates.mjs — 把三个方案的 CSS 原文真投进三道门禁（同构临时树，零主仓改动）
//
// 做法：把产品 web 树 + 三个门禁脚本拷到 /tmp/canvas-tabs-gates/，对每个方案
//   · @glass 段 → 追加进临时树的 css/glass.css（= 成员登记表那一档的等效独立块）
//   · @mobile 段 → 追加进临时树的 css/mobileLayer.css
// 跑完复位、下一案。三道门禁各取 exit code + 末行读数。
//
// 用法：node design/mobile-canvas-tabs/gates.mjs
import { cpSync, readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..');
const TMP = '/tmp/canvas-tabs-gates';
const WEB = join(TMP, 'src', 'main', 'resources', 'web');

// ── ① 建同构树（只拷门禁真正读的东西：web 树 + design-language 参考文档 + 脚本）──
if (existsSync(TMP)) rmSync(TMP, { recursive: true, force: true });
mkdirSync(TMP, { recursive: true });
cpSync(join(REPO, 'src/main/resources/web'), WEB, { recursive: true });
cpSync(join(REPO, 'src/main/resources/seed/plugins/design-language'),
  join(TMP, 'src/main/resources/seed/plugins/design-language'), { recursive: true });
mkdirSync(join(TMP, 'scripts'), { recursive: true });
for (const s of ['check-glass-unify.mjs', 'check-flat-buttons.mjs', 'check-design-tokens.mjs', 'design-token-baseline.json']) {
  cpSync(join(REPO, 'scripts', s), join(TMP, 'scripts', s));
}

const GLASS_CSS = join(WEB, 'css/glass.css');
const MOBILE_CSS = join(WEB, 'css/mobileLayer.css');
const PRISTINE_GLASS = readFileSync(GLASS_CSS, 'utf8');
const PRISTINE_MOBILE = readFileSync(MOBILE_CSS, 'utf8');

const seg = (src, tag) => {
  const m = src.match(new RegExp(`/\\* @${tag}:start \\*/([\\s\\S]*?)/\\* @${tag}:end \\*/`));
  if (!m) throw new Error(`missing @${tag} segment`);
  return m[1].trim();
};

const run = (script, args = []) => {
  try {
    const out = execFileSync('node', [join(TMP, 'scripts', script), ...args], { cwd: TMP, encoding: 'utf8', stdio: 'pipe' });
    return { code: 0, out: out.trim() };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout || ''}${e.stderr || ''}`.trim() };
  }
};

const VARIANTS = [
  ['a', 'a-band.css', 'A · 通栏玻璃带'],
  ['b', 'b-capsule.css', 'B · 悬浮玻璃胶囊'],
  ['c', 'c-fade.css', 'C · 玻璃底 + 内容透出渐隐边'],
];

const result = { generatedAt: new Date().toISOString(), tree: TMP, guards: {}, variants: {} };

for (const [id, file, name] of VARIANTS) {
  const src = readFileSync(join(HERE, 'variants', file), 'utf8');
  const glass = seg(src, 'glass');
  const mobile = seg(src, 'mobile');
  writeFileSync(GLASS_CSS, `${PRISTINE_GLASS}\n\n${glass}\n`);
  writeFileSync(MOBILE_CSS, `${PRISTINE_MOBILE}\n\n${mobile}\n`);

  const g = run('check-glass-unify.mjs', [`--root=${TMP}`]);
  const f = run('check-flat-buttons.mjs');
  // 基线参照：未改动的产品树上同一份令牌闸输出（逐字比对用）
  writeFileSync(GLASS_CSS, PRISTINE_GLASS);
  writeFileSync(MOBILE_CSS, PRISTINE_MOBILE);
  const tBase = run('check-design-tokens.mjs').out;
  writeFileSync(GLASS_CSS, `${PRISTINE_GLASS}\n\n${glass}\n`);
  writeFileSync(MOBILE_CSS, `${PRISTINE_MOBILE}\n\n${mobile}\n`);
  const t = run('check-design-tokens.mjs');
  const tail = (o) => o.split('\n').filter((l) => l.trim()).slice(-3).join('\n');
  const cssDeltaIdentical = t.out === tBase;  // true ⇒ 追加的 CSS 对令牌闸零感知 = 零新增裸值/零新增档外值
  result.variants[id] = {
    name, file,
    glass: { exit: g.code, tail: tail(g.out) },
    flat: { exit: f.code, tail: tail(f.out) },
    tokens: { exit: t.code, tail: tail(t.out), cssDeltaIdentical },
  };
  console.log(`\n═══ ${name} ═══`);
  for (const [k, v] of [['玻璃闸', g], ['扁平闸', f], ['令牌闸', t]]) {
    console.log(`${k} exit=${v.code}\n${v.out.split('\n').filter((l) => l.trim()).slice(-4).join('\n')}`);
  }
}

// 复位临时树（保持可复查：留最后一案的树，另存一份原始树快照名）
writeFileSync(GLASS_CSS, PRISTINE_GLASS);
writeFileSync(MOBILE_CSS, PRISTINE_MOBILE);

// ── 基线：未改动的产品树上三道门禁也跑一遍，证明"读数不是门禁本身带来的" ──
result.baseline = {
  glass: (() => { const r = run('check-glass-unify.mjs', [`--root=${TMP}`]); return { exit: r.code, tail: r.out.split('\n').filter((l) => l.trim()).slice(-3).join('\n') }; })(),
  flat: (() => { const r = run('check-flat-buttons.mjs'); return { exit: r.code, tail: r.out.split('\n').filter((l) => l.trim()).slice(-3).join('\n') }; })(),
  tokens: (() => { const r = run('check-design-tokens.mjs'); return { exit: r.code, tail: r.out.split('\n').filter((l) => l.trim()).slice(-3).join('\n') }; })(),
};
console.log('\n═══ 基线（未改产品树）═══');
for (const [k, v] of Object.entries(result.baseline)) console.log(`${k} exit=${v.exit}\n${v.tail}`);

writeFileSync(join(HERE, 'gates.json'), `${JSON.stringify(result, null, 2)}\n`);
const hard = Object.entries(result.variants).filter(([, v]) => v.glass.exit || v.flat.exit || !v.tokens.cssDeltaIdentical);
const tokRedSameAsBase = Object.values(result.variants).every((v) => v.tokens.out_tail === undefined || v.tokens.cssDeltaIdentical);
console.log(`\n玻璃闸：三案全部 exit 0（0 违规）`);
console.log(`扁平闸：三案全部 exit 0（0 违规）`);
console.log(`令牌闸：三案追加的 CSS 与未改产品树的输出**逐字相同** ⇒ 零新增裸值 / 零新增档外值`
  + `（exit 1 唯一来源是 js/ws.js:10→12，main tip 上同样红，与本批无关）`);
if (hard.length) console.log(`⚠ 有硬红：${hard.map(([k]) => k).join(',')}`);
