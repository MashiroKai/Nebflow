#!/usr/bin/env node
// check-self-explaining.mjs — 文案「自解释」静态哨兵（教案句防回退门禁）
// （self-explaining-cleanup 批 2026-11-04；判据来源 = 用户视觉裁定插件
//  `stand-dev` 铁律 8「自解释」：`references/frontend/visual-style.md` §铁律 8 细则
//  + e「机械判据」+ f「边界判例」。**本脚本不另立口径**：词表逐字取自细则 e，
//  en 半区补同义孪生词（细则 e 的词表全是中文，只跑原表会放过 en 文案的同一类
//  教学句，而细则 a 明写「zh/en 双版同禁」）。
//
// Why a static gate：铁律 8 原本只有一条「改前 red → 改后 green」的一次性 grep
// 纪律（细则 e），改完就散。文案是**持续增量面**（每个新面板都在加键），没有机器
// 判据 ⇒ 下一批又会加回教学句。本脚本把「页面文案只写事实与决策」变成常驻判据。
//
// ── 判定面 ────────────────────────────────────────────────────────────
// 界面文案 = locale 文件的文案值（`js/locales/*.js` 的 `'key': '…'` 右侧）+
// `index.html` 的文本节点。**代码注释不算**（细则 e 的尾管道 `grep -v '//'` 只
// 剔得掉行注释，块注释与 HTML 注释会漏成误报 —— 本脚本按语法剔注释，见
// stripComments；2026-11-04 实测：原 grep 的 37 命中里有 10 条是注释）。
//
// ── 三组机械判据（细则 e 逐字 + en 孪生）──────────────────────────────
// ① 路径指引 path-guidance —— 「到某页 → 某菜单 → 某开关」式动线指令
// ② 行为教学 behavior-teaching —— 教用户在当前处境怎么办
// ③ 能力说明 capability-claim —— 用人话复述界面/设备「能干什么」
// ④ 重复说明 duplicate-explanation —— **无机械判据**（同一事实在徽标与整行小字
//    里各说一遍，机器分不清「徽标 + 事实行」与「徽标 + 教学行」）。本门禁对它
//    零覆盖，属**已知边界**：靠人判 + 细则 e 的逐条处置记录。别把本门禁当全覆盖。
//
// ── 两档判据（形态照抄 scripts/check-design-tokens.mjs）───────────────────
// ① 存量护栏：`scripts/self-explaining-baseline.json` 记录**判定留存的每一条**
//    （file + rule + key 三元组，key = locale 键名 / index.html 行指纹）。
//    判据：命中不在基线里 ⇒ 红；基线里的条目已不存在 ⇒ 只提示（建议下次收紧）。
//    ⇒ 存量只许降不许升：新增一条教学句必然有新的 (file,rule,key)，必红。
// ② 新文件零容忍：基线里没有该文件任何条目时，一个命中都不许有 —— 即
//    「新面板/新语言包不许带教学句出生」（与①同机制，不需要额外分支：
//    allow 里查不到就红）。
//
// 基线里每一条都是**保留清单**（细则 b）的落点：状态事实 / 失败收口 / 动作标签 /
// 空态下一步 / tooltip 事实句。加条目 = 声称「这是事实不是说明书」，必须在批报告
// 里点名理由（`--update-baseline` 的注释头写死这条纪律）。
//
// ── 用法 ──────────────────────────────────────────────────────────────
//   node scripts/check-self-explaining.mjs                 # 门禁（红/绿）
//   node scripts/check-self-explaining.mjs --json          # 机器读
//   node scripts/check-self-explaining.mjs --list          # 只列命中（不做基线比较）
//   node scripts/check-self-explaining.mjs --update-baseline  # 重锚基线（只许在
//                                                          # 「确实把命中降下来」的
//                                                          # 合并里用；升基线 = 放水）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const LOCALES_DIR = join(WEB, 'js', 'locales');
const INDEX_HTML = join(WEB, 'index.html');
const BASELINE_PATH = join(ROOT, 'scripts', 'self-explaining-baseline.json');
const ARGS = new Set(process.argv.slice(2));

// ── 判据词表 ──────────────────────────────────────────────────────────
// zh 半区 = 铁律 8 细则 e 逐字（三组，一行不改；改口径先改 references 再改这里）。
// en 半区 = 同三类的同义孪生词（细则 e 只给了 `open the console` / `Please click`
// 两个 en 词，不足以覆盖 en 文案；细则 a 的「zh/en 双版同禁」要求补齐）。
const RULES = [
  {
    id: 'path-guidance',
    name: '路径指引',
    re: /(→|打开控制台|open the console|到该设备|在该设备上|点击上方|点击此处|Please click)/,
  },
  {
    id: 'behavior-teaching',
    name: '行为教学',
    re: /(请稍候|请勿|不需要手动|会自动接续|正在等待.{0,8}回来|稍后自动|稍候|please wait|no manual refresh|resumes automatically|never forward|do not forward)/i,
  },
  {
    id: 'capability-claim',
    name: '能力说明',
    re: /(可远控|直接推送|支持云端|仅支持.{0,6}更新|remotely updatable)/,
  },
];

/**
 * 去掉注释但**保留行号**（把注释内容换成等长空白/换行）。
 * - JS（locale）：块注释 `/* … *\/` + 行注释 `// …`（只认行首或前置空白的 `//`）。
 * - HTML（index.html）：`<!-- … -->`（外加其内联脚本里的块注释）。
 * @param {string} src
 * @param {'js'|'html'} kind
 */
function stripComments(src, kind) {
  const blank = (m) => m.replace(/[^\n]/g, ' ');
  let out = src;
  if (kind === 'html') out = out.replace(/<!--[\s\S]*?-->/g, blank);
  out = out.replace(/\/\*[\s\S]*?\*\//g, blank);
  out = out.split('\n').map((line) => {
    const m = line.match(/^(\s*)\/\//);
    return m ? m[1] + ' '.repeat(line.length - m[1].length) : line;
  }).join('\n');
  return out;
}

/**
 * locale 文件的文案值行 → [{line, key, value}]。
 * 只认单行 `'key': 'value'` 形态（本仓 locale 全是这个形态）。
 * @param {string} src 已剔注释的源码
 */
function localeEntries(src) {
  const out = [];
  src.split('\n').forEach((line, i) => {
    const m = line.match(/^\s*(['"])([^'"]+)\1\s*:\s*(['"`])([\s\S]*)\3\s*,?\s*$/);
    if (!m) return;
    out.push({ line: i + 1, key: m[2], value: m[4] });
  });
  return out;
}

/** 一行里命中的全部判据类别。 */
function hitsOf(text) {
  return RULES.filter((r) => r.re.test(text));
}

const fails = [];
const notes = [];
const hits = [];   // {file, line, rule, key, text}

// ── ① locale 面 ───────────────────────────────────────────────────────
const localeFiles = existsSync(LOCALES_DIR)
  ? readdirSync(LOCALES_DIR).filter((n) => n.endsWith('.js')).sort()
  : [];
if (localeFiles.length === 0) {
  console.error(`环境错误：找不到 ${relative(ROOT, LOCALES_DIR)} 下的 locale 文件`);
  process.exit(2);
}

for (const name of localeFiles) {
  const rel = `src/main/resources/web/js/locales/${name}`;
  const src = stripComments(readFileSync(join(LOCALES_DIR, name), 'utf8'), 'js');
  for (const e of localeEntries(src)) {
    for (const rule of hitsOf(e.value)) {
      hits.push({ file: rel, line: e.line, rule: rule.id, key: e.key, text: e.value });
    }
  }
}

// ── ② index.html 文本面 ───────────────────────────────────────────────
if (existsSync(INDEX_HTML)) {
  const rel = 'src/main/resources/web/index.html';
  const src = stripComments(readFileSync(INDEX_HTML, 'utf8'), 'html');
  src.split('\n').forEach((line, i) => {
    const text = line.trim();
    if (!text) return;
    for (const rule of hitsOf(text)) {
      // key = 行指纹（HTML 没有键名；行漂移即要求重新判定，宁可红得吵一点）
      const fp = createHash('sha1').update(text).digest('hex').slice(0, 10);
      hits.push({ file: rel, line: i + 1, rule: rule.id, key: `line:${fp}`, text });
    }
  });
}

// ── 报告辅助 ──────────────────────────────────────────────────────────
const idOf = (h) => `${h.file}::${h.rule}::${h.key}`;
const byRule = {};
for (const h of hits) byRule[h.rule] = (byRule[h.rule] || 0) + 1;

if (ARGS.has('--json') && ARGS.has('--list')) {
  console.error('--json 与 --list 互斥（--list 已是人读逐条形态）');
  process.exit(2);
}

if (ARGS.has('--list')) {
  console.log(`self-explaining: ${hits.length} 命中（未做基线比较）`);
  for (const h of hits) console.log(`  ${h.file}:${h.line}  [${h.rule}] ${h.key}\n      ${h.text}`);
  process.exit(0);
}

if (ARGS.has('--update-baseline')) {
  const allow = hits.map((h) => ({ file: h.file, rule: h.rule, key: h.key, text: h.text.slice(0, 160) }));
  const payload = {
    _comment: 'check-self-explaining.mjs 存量基线（self-explaining-cleanup 批 2026-11-04）：'
      + '每条 = 一条**判定留存**的命中（铁律 8 细则 b 保留清单的落点：状态事实/失败收口/'
      + '动作标签/空态下一步/tooltip 事实句）。只许降不许升：加条目 = 声称「这是事实不是说明书」，'
      + '必须在批报告里点名理由；升基线 = 放水。重锚：node scripts/check-self-explaining.mjs --update-baseline',
    generatedAt: new Date().toISOString(),
    counts: byRule,
    total: hits.length,
    allow,
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(payload, null, 2) + '\n');
  console.log(`基线已写入 ${relative(ROOT, BASELINE_PATH)}（${hits.length} 条：`
    + `${Object.entries(byRule).map(([k, v]) => `${k}×${v}`).join(' ')}）`);
  process.exit(0);
}

// ── 存量护栏：命中必须在基线里 ────────────────────────────────────────
const baseline = existsSync(BASELINE_PATH)
  ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8'))
  : null;
const allowSet = new Set((baseline?.allow || []).map(idOf));
const seen = new Set();

for (const h of hits) {
  const id = idOf(h);
  if (allowSet.has(id)) { seen.add(id); continue; }
  const knownFile = (baseline?.allow || []).some((a) => a.file === h.file);
  fails.push({
    ...h,
    detail: knownFile
      ? '该文件基线下没有这一条 —— 新增的教学句（铁律 8 四类禁止），删或改成事实句'
      : '新文件里出现教学句 —— 新面零容忍（铁律 8 四类禁止）',
  });
}
for (const a of baseline?.allow || []) {
  if (!seen.has(idOf(a))) notes.push(`${a.file} :: ${a.rule} :: ${a.key} —— 基线条目已不存在（改善，建议下次 --update-baseline 收紧）`);
}

const total = hits.length;
if (ARGS.has('--json')) {
  console.log(JSON.stringify({
    ok: fails.length === 0,
    total,
    byRule,
    baselineTotal: baseline?.allow?.length ?? null,
    fails,
    notes,
  }, null, 2));
} else {
  const counts = Object.entries(byRule).map(([k, v]) => `${k}×${v}`).join(' ') || '无';
  console.log(`self-explaining：${localeFiles.length} 个语言包 + index.html，${total} 命中（${counts}）；基线 ${baseline?.allow?.length ?? 0} 条`);
  for (const n of notes) console.log(`  ↑ ${n}`);
  if (fails.length === 0) {
    console.log('PASS: 页面文案零新增教学句；留存命中全部落在铁律 8 保留清单（状态事实/失败收口/动作标签/空态下一步/tooltip 事实句）。');
  } else {
    console.log(`\nFAIL ${fails.length} 项（铁律 8「自解释」）：`);
    for (const f of fails) console.log(`  ${f.file}:${f.line}  [${f.rule}] ${f.key}\n      ${f.text}\n      → ${f.detail}`);
  }
}
process.exit(fails.length ? 1 : 0);
