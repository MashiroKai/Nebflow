#!/usr/bin/env node
/* Phase 5 解耦(行为保持重构,2026-09-25)。 */
// check-scala-layers.mjs — Phase 5 Scala 包分层门禁(棘轮基线)。
//
// 【作者裁定 2026-09-25,Phase 5】分层规则(依 src/main/scala 实测依赖强度成形):
//   (1) 底层 = shared、actor:不得 import / FQN 引用 core、agent、gateway、
//       service、neblink、dropbox、llm、cli、bridge、social 任何上层包;
//   (2) root(Main.scala,只出不进)与 Version(被 13 边引用的叶子)不入图、不计边;
//   (3) 其余包按 LAYER_ORDER 声明层次:依赖只准向下(目标层位低于自身层位),
//       反向边 = 违规;违规经 scripts/layer-exemptions.json 按 (文件, 目标包) 粒度
//       豁免(勿用包级计数——同名文件增删会漂移;先例 = check-js-types.mjs +
//       tests/type-baseline.json),基线只减不增:PR 减边须同步缩基线;
//       豁免边移出图后包图必须无环(Tarjan SCC 断言,沿用 check-circular.mjs)。
//
// 为什么不能是纯 import 正则(Scala 侧不健全,Phase 5 评审实测三缺口):
//   (a) Scala 3 通配 `import nebflow.p.*` 与 `import nebflow.p.{A,B}`;
//   (b) 无 import 语句的 FQN 裸引用(core→gateway 的 FlowTreeActor / tools/types /
//       FileRefs 均如此,import-only 门禁会对真实存在的边报假绿);
//   (c) 字符串/注释假阳性(RestartHelper 的 "com.nebflow.gateway" 是启动标签,
//       行为面,不得计入;logger 名、sys-props 键同理)。故先剥注释与字符串再扫,
//       剥法沿用 check-dead-logging.sh 内嵌 python 的 strip_source(含三引号与
//       嵌套块注释);内插字符串的 ${...} 是代码(实测 agent/ContextRefresher.scala
//       的 ${nebflow.service.MemoryBudget.*} 是真实边),剥串时原样保留。
//
// Usage:
//   node scripts/check-scala-layers.mjs            # 门禁(CI / 本地)
//   node scripts/check-scala-layers.mjs --update   # 以当前树重播种豁免基线
//                                                  # (同 (from,to) 的既有 reason 保留)
// Exit codes: 0 = 通过;1 = 存在未豁免违规 / 豁免后仍有环;2 = 配置错误
//             (豁免清单损坏、未知顶层包等)。

import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SRC = join(ROOT, 'src', 'main', 'scala');
const EXEMPT_PATH = join(ROOT, 'scripts', 'layer-exemptions.json');

// 声明层次:下标越小越底层。新增顶层包必须显式入表——不在表内即 exit 2,
// 逼一次有意识的分层裁定,而不是无声漏边。
const LAYER_ORDER = [
  'shared',   // 底层:纯支撑面(日志等),零上层依赖
  'actor',    // 底层:actor 原语
  'core',     // 引擎核心:只准依赖底下两层(C/D 步倒置 core→agent/core→gateway 后成形)
  'llm',      // 适配层:协议面在上、core 在下
  'bridge',
  'dropbox',
  'neblink',
  'social',
  'agent',
  'service',
  'cli',
  'gateway',  // 装配面:最上层
];
const BOTTOM = ['shared', 'actor'];                       // 裁定 (1)
const ROOT_FILES = ['nebflow/Main.scala', 'nebflow/Version.scala']; // 裁定 (2)
const RANK = new Map(LAYER_ORDER.map((p, i) => [p, i]));

// —— 剥注释与字符串(保留换行与列位;${...} 插值是代码,保留) ——
function blank(s) {
  let r = '';
  for (const ch of s) r += ch === '\n' ? '\n' : ' ';
  return r;
}

function pushLiteral(out, chunk) {
  // 字符串字面量整体置空,但其中的 ${...} 插值段原样保留(代码)。
  let j = 0;
  while (j < chunk.length) {
    const d = chunk.indexOf('${', j);
    if (d < 0) { out.push(blank(chunk.slice(j))); break; }
    out.push(blank(chunk.slice(j, d)));
    let depth = 1, k = d + 2; // 朴素花括号配对;未闭合则按代码保留到串尾
    while (k < chunk.length && depth > 0) {
      if (chunk[k] === '{') depth += 1;
      else if (chunk[k] === '}') depth -= 1;
      k += 1;
    }
    out.push(chunk.slice(d, k));
    j = k;
  }
}

function stripSource(text) {
  const out = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    const c = text[i];
    if (c === '\n') { out.push('\n'); i += 1; }
    else if (c === '/' && text[i + 1] === '/') {
      const j = text.indexOf('\n', i);
      const e = j < 0 ? n : j;
      out.push(blank(text.slice(i, e))); i = e;
    } else if (c === '/' && text[i + 1] === '*') {
      let depth = 1, j = i + 2; // Scala 块注释可嵌套
      while (j < n && depth) {
        if (text.startsWith('/*', j)) { depth += 1; j += 2; }
        else if (text.startsWith('*/', j)) { depth -= 1; j += 2; }
        else j += 1;
      }
      out.push(blank(text.slice(i, j))); i = j;
    } else if (text.startsWith('"""', i)) {
      let j = text.indexOf('"""', i + 3);
      j = j < 0 ? n : j + 3;
      pushLiteral(out, text.slice(i, j)); i = j;
    } else if (c === '"') {
      let j = i + 1;
      while (j < n) {
        if (text[j] === '\\') { j += 2; continue; }
        if (text[j] === '"') { j += 1; break; }
        j += 1;
      }
      pushLiteral(out, text.slice(i, j)); i = j;
    } else if (c === "'" && i + 2 < n && text[i + 2] === "'") {
      out.push('   '); i += 3; // 字符字面量 'x'
    } else { out.push(c); i += 1; }
  }
  return out.join('');
}

// —— 测边 ——
const IMPORT_RE = /\b(?:import|export)\s+nebflow\.([A-Za-z0-9_]+)/g;
const FQN_RE = /\bnebflow\.([A-Za-z0-9_]+)\./g;

function pkgOf(rel) { // rel 相对 src/main/scala,如 'nebflow/core/tools/X.scala'
  const parts = rel.split('/');
  return parts.length <= 2 ? 'root' : parts[1];
}

function targetsOf(stripped, own) {
  const s = new Set();
  for (const re of [IMPORT_RE, FQN_RE]) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(stripped))) {
      const p = m[1];
      if (p === own) continue;        // 同包引用不计边
      if (p === 'Version') continue;  // 裁定 (2):Version 叶子不计边
      if (RANK.has(p)) s.add(p);      // 其余根包符号(Main 等)不入图
    }
  }
  return s;
}

// —— 收集全部 .scala(仅 src/main/scala;测试源暂不管,与 Phase 5 计划一致) ——
const scalaFiles = [];
(function walk(d) {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.scala')) scalaFiles.push(p);
  }
})(SRC);

const unknownPkgs = new Set();
const edges = []; // { file(仓库相对, / 分隔), from, to }
let scanned = 0;
for (const f of scalaFiles.sort()) {
  const rel = relative(SRC, f).split(sep).join('/');
  scanned += 1;
  const own = pkgOf(rel);
  if (ROOT_FILES.includes(rel)) continue; // 裁定 (2):root/Version 不作边源
  if (!RANK.has(own)) { unknownPkgs.add(own); continue; }
  const stripped = stripSource(readFileSync(f, 'utf8'));
  for (const to of targetsOf(stripped, own)) {
    edges.push({ file: 'src/main/scala/' + rel, from: own, to });
  }
}
if (unknownPkgs.size) {
  console.error(`scala-layers CONFIG ERROR: 未知顶层包 ${[...unknownPkgs].join(', ')} ——`);
  console.error('  新包必须显式加入 scripts/check-scala-layers.mjs 的 LAYER_ORDER(一次有意识的分层裁定)。');
  process.exit(2);
}

const edgeKey = (e) => `${e.file}=>${e.to}`;
const isBackward = (e) => RANK.get(e.to) > RANK.get(e.from);
const violations = edges.filter(isBackward);

// —— 豁免清单 ——
function loadExemptions() {
  if (!existsSync(EXEMPT_PATH)) return null;
  let raw;
  try {
    raw = JSON.parse(readFileSync(EXEMPT_PATH, 'utf8'));
  } catch (err) {
    console.error(`scala-layers CONFIG ERROR: ${EXEMPT_PATH} 不是合法 JSON: ${err.message}`);
    process.exit(2);
  }
  if (!Array.isArray(raw.entries)) {
    console.error('scala-layers CONFIG ERROR: layer-exemptions.json 缺少 entries 数组。');
    process.exit(2);
  }
  const map = new Map();
  for (const ent of raw.entries) {
    const bad = !ent || typeof ent.from !== 'string' || typeof ent.to !== 'string'
      || typeof ent.reason !== 'string' || !ent.from || !ent.to || !ent.reason;
    if (bad) {
      console.error(`scala-layers CONFIG ERROR: 豁免项缺 from/to/reason(非空字符串): ${JSON.stringify(ent)}`);
      process.exit(2);
    }
    if (!RANK.has(ent.to) || ent.from === ent.to) {
      console.error(`scala-layers CONFIG ERROR: 豁免项 to 非法: ${ent.from} -> ${ent.to}`);
      process.exit(2);
    }
    const k = `${ent.from}=>${ent.to}`;
    if (map.has(k)) {
      console.error(`scala-layers CONFIG ERROR: 豁免项重复: ${k}`);
      process.exit(2);
    }
    map.set(k, ent.reason);
  }
  return map;
}

// —— --update:以当前树重播种(同 (from,to) 保留既有 reason;基线只减不增的收缩靠 PR 手改/复跑) ——
if (process.argv.includes('--update')) {
  const old = loadExemptions() ?? new Map();
  const seen = new Set(edges.map(edgeKey));
  const entries = violations
    .sort((a, b) => (a.file + a.to).localeCompare(b.file + b.to))
    .map((e) => ({
      from: e.file,
      to: e.to,
      reason: old.get(edgeKey(e))
        ?? 'TODO(Phase 5):新增反向边——倒置依赖,或补豁免理由后入基线',
    }));
  const kept = entries.filter((e) => old.has(edgeKey(e))).length;
  const json = {
    _header: 'Phase 5 解耦(行为保持重构,2026-09-25)。分层豁免基线:门禁断言『实测 (文件,目标包) 反向边集合 ⊆ 本基线』,基线只减不增;PR 减边须同步缩基线(node scripts/check-scala-layers.mjs --update 可重播种,既有 reason 按同 (from,to) 保留)。',
    generatedBy: 'scripts/check-scala-layers.mjs --update',
    entries,
  };
  writeFileSync(EXEMPT_PATH, JSON.stringify(json, null, 2) + '\n');
  console.log(`baseline written: ${entries.length} exempted violations (${kept} kept reasons, ${entries.length - kept} new) -> scripts/layer-exemptions.json`);
  process.exit(0);
}

// —— 门禁 ——
const exempt = loadExemptions();
const exempted = [];
const failures = [];
for (const v of violations) {
  const k = edgeKey(v);
  const reason = exempt?.get(k);
  if (reason != null) exempted.push({ ...v, reason });
  else {
    const kind = BOTTOM.includes(v.from) ? '底层反向依赖' : '反向边(目标层高于自身层)';
    failures.push(`[${kind}] ${v.file} (${v.from}) -> ${v.to}`);
  }
}

// 基线棘轮的另一半:清单里已不对应任何实测违规边的条目 = 可收缩(信息级,不算失败)。
const violationKeys = new Set(violations.map(edgeKey));
const stale = [];
if (exempt) {
  for (const [k, reason] of exempt) {
    if (!violationKeys.has(k)) {
      const [file, to] = k.split('=>');
      const exists = new Set(edges.map(edgeKey)).has(k);
      stale.push(`  可移除  ${file} -> ${to}${exists ? '(边已不再是违规:方向/层位变化)' : '(边已消除)'} — ${reason}`);
    }
  }
}

// Tarjan SCC(沿用 check-circular.mjs):豁免边移出图后,包图必须无环。
const pkgGraph = new Map(LAYER_ORDER.map((p) => [p, []]));
const exemptKeys = new Set(exempted.map(edgeKey));
for (const e of edges) {
  if (e.from === e.to || exemptKeys.has(edgeKey(e))) continue;
  pkgGraph.get(e.from).push(e.to);
}
const idx = {}, low = {}, onSt = new Set(), st = [];
let counter = 0;
const sccs = [];
function strongconnect(v) {
  idx[v] = low[v] = counter++;
  st.push(v); onSt.add(v);
  for (const w of pkgGraph.get(v) || []) {
    if (!(w in idx)) { strongconnect(w); low[v] = Math.min(low[v], low[w]); }
    else if (onSt.has(w)) { low[v] = Math.min(low[v], idx[w]); }
  }
  if (low[v] === idx[v]) {
    const cc = []; let w;
    do { w = st.pop(); onSt.delete(w); cc.push(w); } while (w !== v);
    if (cc.length > 1) sccs.push(cc);
  }
}
for (const n of pkgGraph.keys()) if (!(n in idx)) strongconnect(n);
for (const cc of sccs) {
  const s = new Set(cc);
  const inScc = new Set();
  for (const f of cc) for (const t of pkgGraph.get(f)) if (s.has(t)) inScc.add(`${f} -> ${t}`);
  failures.push(`[环] 豁免后包图仍有强连通分量: ${cc.join(', ')} (${[...inScc].sort().join('; ')})`);
}

// —— 报告 ——
const pkgTable = new Map();
for (const e of edges) {
  if (e.from === e.to) continue;
  if (!pkgTable.has(e.from)) pkgTable.set(e.from, new Map());
  const m = pkgTable.get(e.from);
  m.set(e.to, (m.get(e.to) ?? 0) + 1);
}
const unexempted = violations.length - exempted.length;
console.log(`scala-layers: ${scanned} files scanned, ${edges.length} cross-package file-edges, ${violations.length} 反向边 (${exempted.length} 豁免 / ${unexempted} 未豁免)。`);
for (const from of [...pkgTable.keys()].sort()) {
  const row = [...pkgTable.get(from).entries()].sort((a, b) => b[1] - a[1]).map(([t, c]) => `${t}:${c}`).join(' ');
  console.log(`  pkg ${from} -> ${row}`);
}
if (exempt == null) {
  console.error('scala-layers: 未找到 scripts/layer-exemptions.json —— 校准:node scripts/check-scala-layers.mjs --update');
}
for (const e of exempted) console.log(`  [豁免] ${e.file} (${e.from}) -> ${e.to}: ${e.reason}`);
if (stale.length) {
  console.log(`scala-layers: ${stale.length} 条基线已可收缩(--update 重播种并保留 reason):`);
  for (const s of stale) console.log(s);
}
if (failures.length) {
  console.error('\nscala-layers GATE FAILURES:');
  for (const f of failures) console.error(`  ${f}`);
  console.error('\n修复:倒置依赖方向;确属现状的,把 (from,to,reason) 加入 scripts/layer-exemptions.json');
  console.error('(基线只减不增——PR 减边须同步缩基线)。');
  process.exit(1);
}
console.log('scala-layers PASS: 无未豁免反向边,豁免后包图无环。');
process.exit(0);
