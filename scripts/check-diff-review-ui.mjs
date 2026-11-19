#!/usr/bin/env node
// check-diff-review-ui.mjs — 审阅动作区 / 模式闸门 / canvas 适配 / 多文件适配 的**闸**：
// 自证检测力（作者验收要求：破坏 → 红（退出码非 0）；还原 → 绿；对未修源码直跑 → 红；
// SKIP 口径 = exit 2，CI 档记 FAIL）。
//
// 六条腿（每条独立起一次 tests/diff-review-ui.spec.mjs，独立端口，独立 web 树副本）：
//   A 未修源码直跑       —— WEB_DIR = 本批起点树（git archive 1fc5d3a52 的
//                           src/main/resources/web；无组件动作区/多文件/canvas 断言面）
//                           ⇒ G1/G2/G3/G6 全红。
//   B 破坏·模式闸门       —— 组件 review 动作区改为无条件渲染 ⇒ G1 红（read 面出现控件）。
//   C 破坏·去掉全量钮     —— 组件不再渲染全量动作区 ⇒ G2/G3 红。
//   D 破坏·canvas 截断    —— 编辑面 scroller overflow:auto → hidden ⇒ G7b 红（静默裁切）。
//   F 破坏·多文件指错文件 —— 逐块动作固定读第 0 节 hunk 表 ⇒ G6c 红（第 2 文件的动作落到
//                           第 1 文件）。
//   E 正常源码            —— 本仓工作树 ⇒ 绿。
//
// 用法：node scripts/check-diff-review-ui.mjs
// 退出码：0 = 全部符合预期（A/B/C/D/F 红 + E 绿）；1 = 有腿不符；2 = 环境缺口（SKIP）。

import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WEB_REL = 'src/main/resources/web';
const SPEC = 'tests/diff-review-ui.spec.mjs';
const BASE_REV = process.env.NF_BASE_REV || '1fc5d3a52';

let port = 8340;
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
  const fails = (out.match(/^FAIL.*$/gm) || []).slice(0, 2).join(' | ');
  results.push({ name, expectRed, code, ok: okLeg, fails });
  console.log(`${okLeg ? 'PASS' : 'FAIL'}  ${name}  (exit ${code}, 期望${expectRed ? '红' : '绿'})${fails ? '  — ' + fails : ''}`);
  return okLeg;
};

const tmp = mkdtempSync(join(tmpdir(), 'nf-diff-review-gate-'));
try {
  // ── A 未修源码直跑（起点树导出）─────────────────────────────────────────────
  mkdirSync(join(tmp, 'base'), { recursive: true });
  try {
    execFileSync('bash', ['-lc', `git archive ${BASE_REV} ${WEB_REL} | tar -x -C ${JSON.stringify(join(tmp, 'base'))}`], { cwd: REPO });
  } catch {
    console.log(`SKIP  A 起点树导出失败（git archive ${BASE_REV}）`);
    process.exit(2);
  }
  leg(`A 未修源码直跑（${BASE_REV}）→ 红`, true, () => runSpec(join(tmp, 'base', WEB_REL)));

  // ── 品种树（工作树 web 的副本，逐条注破坏）──────────────────────────────────
  const curWeb = join(REPO, WEB_REL);
  const mWeb = join(tmp, 'mut', 'web');
  mkdirSync(mWeb, { recursive: true });
  cpSync(curWeb, mWeb, { recursive: true });
  const IDX = join(mWeb, 'js/diffView/index.js');
  const THM = join(mWeb, 'js/diffView/theme.js');
  const ORIG_IDX = readFileSync(IDX, 'utf8');
  const ORIG_THM = readFileSync(THM, 'utf8');

  const withMutation = (file, from, to, run) => {
    const orig = readFileSync(file, 'utf8');
    if (!orig.includes(from)) throw new Error(`mutation anchor missing in ${file}: ${from.slice(0, 60)}`);
    writeFileSync(file, orig.replace(from, to));
    const r = run();
    writeFileSync(file, orig);
    return r;
  };

  const REVIEW_BAR = "if (mode === 'review') {\n    const actions";

  // ── B 破坏·模式闸门（read 面插回动作控件）──────────────────────────────────
  leg('B 破坏（read 面插回接受/拒绝控件 = 模式闸门失效）→ 红', true, () => withMutation(
    IDX, REVIEW_BAR, "if (true) {\n    const actions", () => runSpec(mWeb)));

  // ── C 破坏·去掉全量动作钮（全部接受/全部拒绝）───────────────────────────────
  leg('C 破坏（去掉「全部接受/全部拒绝」钮）→ 红', true, () => withMutation(
    IDX, REVIEW_BAR, "if (mode === 'review' && false) {\n    const actions", () => runSpec(mWeb)));

  // ── D 破坏·canvas 面静默裁切（编辑面不再可滚）───────────────────────────────
  leg('D 破坏（canvas 编辑面 scroller 不可滚 = 静默裁切）→ 红', true, () => withMutation(
    THM, "overflow: 'auto',", "overflow: 'hidden',", () => runSpec(mWeb)));

  // ── F 破坏·多文件逐块归属（固定读第 0 节 hunk 表）───────────────────────────
  leg('F 破坏（多文件逐块动作固定读第 1 个文件的 hunk 表）→ 红', true, () => withMutation(
    IDX, 'resolveHunk(cm, view, btn, sections[activeIdx].hunks)', 'resolveHunk(cm, view, btn, sections[0].hunks)', () => runSpec(mWeb)));

  // ── E 正常源码 → 绿 ─────────────────────────────────────────────────────────
  leg('E 正常源码 → 绿', false, () => runSpec(curWeb));

  writeFileSync(IDX, ORIG_IDX);
  writeFileSync(THM, ORIG_THM);
} finally {
  rmSync(tmp, { recursive: true, force: true });
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${bad.length === 0 ? '✅' : '❌'} diff-review-ui gate: ${results.length - bad.length}/${results.length} legs as expected`);
if (existsSync(REPO)) process.exit(bad.length === 0 ? 0 : 1);
