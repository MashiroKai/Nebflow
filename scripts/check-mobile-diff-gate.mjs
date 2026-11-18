#!/usr/bin/env node
// check-mobile-diff-gate.mjs — 共享差异组件（js/diffView）手机形态基准的**闸**：
// 自证检测力（作者验收要求：破坏 → 红（退出码非 0）；还原 → 绿；对未修源码
// 直跑 → 红；SKIP 口径 = exit 2，CI 档记 FAIL）。
//
// 五条腿（每条独立起一次 tests/mobile-diff-baseline.spec.mjs，独立端口）：
//   A 未修源码直跑       —— WEB_DIR = 迁移前基线树（git archive 41420bed8 的
//                           src/main/resources/web）；无 js/diffView、无 vendor
//                           ⇒ 组件装不起来 ⇒ 红。
//   B 破坏·去掉逐块按钮   —— 组件 review 模式 mergeControls: false ⇒ M6/M9 红。
//   C 破坏·去掉折行       —— 折行缺省关且不给开关 ⇒ M4 红。
//   D 破坏·回退触控尺寸   —— --nf-dv-hit 44px → 24px ⇒ M6 红。
//   E 正常源码            —— 本仓工作树 ⇒ 绿。
//
// 用法：node scripts/check-mobile-diff-gate.mjs
// 退出码：0 = 全部符合预期（A/B/C/D 红 + E 绿）；1 = 有腿不符；2 = 环境缺口（SKIP）。

import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WEB_REL = 'src/main/resources/web';
const SPEC = 'tests/mobile-diff-baseline.spec.mjs';
const BASE_REV = process.env.NF_BASE_REV || '41420bed8';

let port = 8320;
const nextPort = () => String(port++);

/** Run the spec against one web tree; return its exit code + tail of output. */
function runSpec(webDir) {
  try {
    const out = execFileSync(process.execPath, [SPEC], {
      cwd: REPO,
      env: { ...process.env, WEB_DIR: webDir, PORT: nextPort(), SKIP_SHOTS: '1' },
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status == null ? 1 : e.status, out: `${e.stdout || ''}${e.stderr || ''}` };
  }
}

const results = [];
const leg = (name, expectRed, run) => {
  const { code, out } = run();
  const red = code !== 0;
  const okLeg = expectRed ? red : !red;
  const fails = (out.match(/^FAIL.*$/gm) || []).slice(0, 3).join(' | ');
  results.push({ name, expectRed, code, ok: okLeg, fails });
  console.log(`${okLeg ? 'PASS' : 'FAIL'}  ${name}  (exit ${code}, 期望${expectRed ? '红' : '绿'})${fails ? '  — ' + fails : ''}`);
  return okLeg;
};

const tmp = mkdtempSync(join(tmpdir(), 'nf-diff-gate-'));
try {
  // ── A 未修源码直跑（基线树导出）───────────────────────────────────────────
  const baseWeb = join(tmp, 'base-web');
  mkdirSync(baseWeb, { recursive: true });
  try {
    execFileSync('bash', ['-lc', `git archive ${BASE_REV} ${WEB_REL} | tar -x -C ${JSON.stringify(tmp)}`], { cwd: REPO });
  } catch (e) {
    console.log(`SKIP  A 基线树导出失败（git archive ${BASE_REV}）`);
    process.exit(2);
  }
  const baseWebDir = join(tmp, WEB_REL);
  leg('A 未修源码直跑 → 红', true, () => runSpec(baseWebDir));

  // ── 品种树（工作树 web 的副本，逐条注破坏）────────────────────────────────
  const curWeb = join(REPO, WEB_REL);
  const mutated = join(tmp, 'mut-web');
  cpSync(curWeb, join(mutated, 'web'), { recursive: true });
  const mWeb = join(mutated, 'web');
  const IDX = join(mWeb, 'js/diffView/index.js');
  const TOK = join(mWeb, 'css/stand-tokens.css');
  const ORIG_IDX = readFileSync(IDX, 'utf8');
  const ORIG_TOK = readFileSync(TOK, 'utf8');

  const withMutation = (file, from, to, run) => {
    const orig = readFileSync(file, 'utf8');
    writeFileSync(file, orig.replace(from, to));
    const r = run();
    writeFileSync(file, orig);
    return r;
  };

  // ── B 破坏·去掉逐块按钮 ──────────────────────────────────────────────────
  leg('B 破坏（去掉逐块接受/拒绝按钮）→ 红', true, () => withMutation(
    IDX, 'const controls = mode === \'review\'', 'const controls = false && mode === \'review\'', () => runSpec(mWeb)));

  // ── C 破坏·去掉折行 ─────────────────────────────────────────────────────
  leg('C 破坏（去掉缺省折行/折行开关）→ 红', true, () => withMutation(
    IDX, 'let wrap = opts.wrap == null ? getWrapPref() : !!opts.wrap;',
    'let wrap = false;', () => runSpec(mWeb)));

  // ── D 破坏·回退触控尺寸 ──────────────────────────────────────────────────
  leg('D 破坏（触控目标回退 44px → 24px）→ 红', true, () => withMutation(
    TOK, '--nf-dv-hit: 44px;', '--nf-dv-hit: 24px;', () => runSpec(mWeb)));

  // ── E 正常源码 → 绿 ─────────────────────────────────────────────────────
  leg('E 正常源码 → 绿', false, () => runSpec(curWeb));

  writeFileSync(TOK, ORIG_TOK);
  writeFileSync(IDX, ORIG_IDX);
} finally {
  rmSync(tmp, { recursive: true, force: true });
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${bad.length === 0 ? '✅' : '❌'} mobile-diff gate: ${results.length - bad.length}/${results.length} legs as expected`);
if (existsSync(REPO)) process.exit(bad.length === 0 ? 0 : 1);
