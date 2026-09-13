#!/usr/bin/env node
/**
 * verify-human-docs.mjs — 人读件门禁（R8 = A：**只警告不阻断**）。
 *
 * 真源：~/.nebflow/docs/Nebflow/visual-report-human-readable-spec.md（§B.1 判定式 / §C 硬约束 /
 * §D 场景档位 / §E 可视化）＋ 作者 2026-09-13 裁定 R1–R10（R1=A 钉死 20,300 + D1；R2=A 标记行+路径优先；
 * R3=A 首屏 <8 KB/80 行 + 四段骨架；R6=A ≤3 图；R8=A 只警告不阻断；R10=A 附录豁免 ≤5×）。
 *
 * 面分工（防双实现漂移）：**逐条判据**只有一处实现 = scripts/human_doc_lint.py（本门禁只做
 * ①人读件选取（§B.1 路径+标记行）②调 lint 取 JSON ③汇总渲染/退出码）。类判定条文引用 §B.1。
 *
 * 用法：
 *   node scripts/verify-human-docs.mjs --all                  # 扫描 ~/.nebflow/docs/** 的人读件
 *   node scripts/verify-human-docs.mjs <file> [<file>…]       # 指定文件（仍打类判定）
 *   选项：--scene 产品设计|验收报告|改bug|调研…  --kind 条款|叙事  --fail-on-red  --json  --docs-root <dir>
 * 退出码：默认 0（警告而已）；--fail-on-red 时红 >0 ⇒ 1。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const MARKER = '> 读者：人类';
const argv = process.argv.slice(2);
const flag = (name, def = null) => {
  const i = argv.indexOf(name);
  return i < 0 ? def : (argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : true);
};
const has = (name) => argv.includes(name);
const scene = typeof flag('--scene') === 'string' ? flag('--scene') : null;
const kind = typeof flag('--kind') === 'string' ? flag('--kind') : '叙事';
const failOnRed = has('--fail-on-red');
const asJson = has('--json');
const docsRoot = resolve(typeof flag('--docs-root') === 'string' ? flag('--docs-root') : join(homedir(), '.nebflow', 'docs'));
const files = argv.filter((a, i) => !a.startsWith('--') && !(i > 0 && argv[i - 1].startsWith('--')));

/** §B.1 判定式（路径优先 + 标记不可越权）：返回 人类读 | agent 读 · 未分类 | agent 读 */
export function classify(path) {
  const abs = resolve(path);
  const inDocsHome = abs.startsWith(join(homedir(), '.nebflow', 'docs') + '/');
  const inManaged = /\/(\.nebflow)\//.test(abs);
  if (inDocsHome) {
    let t = '';
    try { t = readFileSync(abs, 'utf8'); } catch { return 'agent 读 · 未分类'; }
    const lines = t.split('\n');
    const h1 = lines.findIndex((l) => l.startsWith('# '));
    const first = lines.slice(h1 + 1).find((l) => l.trim().length > 0) ?? '';
    return first.trim().startsWith(MARKER) ? '人类读' : 'agent 读 · 未分类';
  }
  if (inManaged) return 'agent 读';
  return 'agent 读 · 未分类';
}

function walkMd(dir, depth = 0) {
  if (depth > 3 || !existsSync(dir)) return [];
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) return walkMd(p, depth + 1);
    return n.endsWith('.md') ? [p] : [];
  });
}

const targets = files.length ? files.map((f) => resolve(f)) : walkMd(docsRoot);
const rows = [];
for (const f of targets) {
  const cls = classify(f);
  if (!files.length && cls !== '人类读') continue; // --all：只查人读件
  let r;
  try {
    const args = [join(HERE, 'human_doc_lint.py'), f, '--kind', kind, '--json'];
    if (scene) args.push('--scene', scene);
    r = JSON.parse(execFileSync('python3', args, { encoding: 'utf8' }));
  } catch (e) {
    r = { path: f, red: -1, items: [{ item: 'lint', reading: String(e.message).slice(0, 160), verdict: '红', note: 'lint 执行失败' }] };
  }
  rows.push({ cls, ...r });
}

if (asJson) {
  console.log(JSON.stringify({ docsRoot, scene, kind, rows }, null, 2));
} else {
  console.log(`# verify-human-docs（只警告不阻断 · R8=A）  scope=${files.length ? 'explicit' : docsRoot}  文件=${rows.length}`);
  for (const r of rows) {
    console.log(`\n## ${r.path}\n   类=${r.cls}  红=${r.red}`);
    for (const it of r.items) console.log(`   [${it.verdict}] ${it.item}: ${it.reading}  (${it.note})`);
  }
  const total = rows.reduce((s, r) => s + Math.max(r.red, 0), 0);
  const redFiles = rows.filter((r) => r.red > 0).length;
  console.log(`\n# 汇总：文件=${rows.length} 红文件=${redFiles} 红项合计=${total} ⇒ ${redFiles ? 'WARN（不阻断，R8=A）' : '全绿'}`);
  if (!files.length) console.log('# 注：--all 只覆盖声明了标记行的人读件；<ws>/.nebflow/** 按 §B.1 判 agent 读，不在本门面。');
}
process.exit(failOnRed && rows.some((r) => r.red > 0) ? 1 : 0);
