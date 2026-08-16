#!/usr/bin/env node
// check-circular.mjs - P2-4 static-import cycle gate.
//
// Why static only: ES module circularity is dangerous when edges are STATIC
// (import ... from '...') - circular static imports cause TDZ ReferenceErrors
// at module init. Dynamic import() calls are deferred to runtime and are the
// SANCTIONED ESCAPE HATCH for breaking a cycle (see sidebar.js / neblink.js).
// madge --circular counts dynamic edges too, so this gate parses static
// imports itself and runs Tarjan SCC: any SCC with more than one node fails.
//
// Usage: node scripts/check-circular.mjs   (exit 1 with cycle detail on fail)

import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, normalize, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'js');

const files = [];
(function walk(d) {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.js')) files.push(p);
  }
})(DIR);

const fileSet = new Set(files.map(f => relative(DIR, f)));
const graph = {};
// Matches: import ... from '...' | import '...' | export ... from '...'
// Does NOT match import('...') - dynamic edges are runtime-deferred by design.
const STATIC_RE = /(?:^|\n)\s*(?:import[\s\S]*?from\s*|import\s*|export[\s\S]*?from\s*)['"](\.\.?\/[^'"]+)['"]/g;

for (const f of files) {
  const src = readFileSync(f, 'utf8');
  const rel = relative(DIR, f);
  const deps = new Set();
  let m;
  STATIC_RE.lastIndex = 0;
  while ((m = STATIC_RE.exec(src))) {
    let t = m[1];
    if (!t.endsWith('.js')) t += '.js';
    const norm = normalize(join(dirname(rel), t));
    if (fileSet.has(norm)) deps.add(norm);
  }
  graph[rel] = [...deps];
}

// Tarjan SCC
const idx = {}, low = {}, onSt = new Set(), st = [];
let counter = 0;
const sccs = [];
function strongconnect(v) {
  idx[v] = low[v] = counter++;
  st.push(v); onSt.add(v);
  for (const w of graph[v] || []) {
    if (!(w in idx)) { strongconnect(w); low[v] = Math.min(low[v], low[w]); }
    else if (onSt.has(w)) { low[v] = Math.min(low[v], idx[w]); }
  }
  if (low[v] === idx[v]) {
    const cc = []; let w;
    do { w = st.pop(); onSt.delete(w); cc.push(w); } while (w !== v);
    if (cc.length > 1) sccs.push(cc);
  }
}
for (const n of Object.keys(graph)) if (!(n in idx)) strongconnect(n);

if (sccs.length === 0) {
  console.log(`circular-deps PASS: ${files.length} files, static import graph is acyclic.`);
  process.exit(0);
}

console.error('circular-deps GATE FAILURES (static import cycles):');
for (const cc of sccs) {
  const s = new Set(cc);
  console.error(`  SCC [${cc.length}]: ${cc.join(', ')}`);
  for (const f of cc) for (const d of graph[f]) if (s.has(d)) console.error(`    ${f} -> ${d}`);
}
console.error('\nBreak the cycle: convert the thinnest edge to a dynamic import()');
console.error('at the call site, or inject an accessor via state.js (see chatView.js).');
process.exit(1);
