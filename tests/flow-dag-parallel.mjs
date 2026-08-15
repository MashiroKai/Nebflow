// flow-dag-parallel.mjs — Regression test: parallel (fan-out/join) branch layout
// in the Flow DAG visualizations (W2 engine: onComplete.parallel edges are plain
// condition=null edges).
//
// Verifies both layouts against the real `research` flow shape
// (planner → {r1, r2} → verify → writer):
//   layoutDagNodes     — r1/r2 same row (y), verify depth = planner + 2
//   layoutStellarNodes — r1/r2 same depth (y) AND horizontally spread (no overlap)
// plus DOM-level evidence: rendered node positions and edge paths.
//
// Self-contained: serves src/main/resources/web statically on an ephemeral port
// (the running 8080 instance is an old engine — do NOT use it), imports the real
// flowDag.js module in the page, asserts, screenshots to /tmp/flow-dag-parallel.png.
//
// Run: node tests/flow-dag-parallel.mjs

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire('/Users/dev/.nebflow/projects/slideblocks/node_modules/');
const { chromium } = require('playwright-chromium');

const WEB_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/main/resources/web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

// research flow shape — matches the flowStarted WS / GET /flow/dag/:name contract:
// nodes[] + edges[{from,to,condition}] with parallel fan-out as condition=null edges.
const RESEARCH_RF = {
  instanceId: 'test-research',
  flowName: 'research',
  description: 'Parallel research pipeline',
  entry: 'planner',
  status: 'running',
  nodes: [
    { nodeId: 'planner', agent: 'planner', status: 'completed' },
    { nodeId: 'r1', agent: 'researcher', status: 'running' },
    { nodeId: 'r2', agent: 'researcher', status: 'running' },
    { nodeId: 'verify', agent: 'verifier', status: 'pending' },
    { nodeId: 'writer', agent: 'writer', status: 'pending' },
  ],
  edges: [
    { from: 'planner', to: 'r1', condition: null },
    { from: 'planner', to: 'r2', condition: null },
    { from: 'r1', to: 'verify', condition: null },
    { from: 'r2', to: 'verify', condition: null },
    { from: 'verify', to: 'writer', condition: null },
    { from: 'writer', to: '$return', condition: null },
  ],
};

function serve() {
  return new Promise((resolve) => {
    const server = http.createServer(async (req, res) => {
      const url = req.url.split('?')[0];
      if (url === '/__blank__') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end('<!doctype html><html><head><link rel="stylesheet" href="/css/base.css"><link rel="stylesheet" href="/css/sapphire.css"></head><body style="padding:24px;background:var(--color-bg)"><div id="host"></div></body></html>');
        return;
      }
      const file = path.join(WEB_ROOT, url === '/' ? 'index.html' : url);
      if (!file.startsWith(WEB_ROOT)) { res.writeHead(403); res.end(); return; }
      try {
        const data = await readFile(file);
        res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

const results = [];
function check(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}

const { server, port } = await serve();
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 900, height: 1000 } });
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push(e.message));

  await page.goto(`http://127.0.0.1:${port}/__blank__`);

  // Inject FLOW_CSS (JS-injected design system layer) for faithful visuals.
  await page.evaluate(async () => {
    const { FLOW_CSS } = await import('/js/flowCss.js');
    const style = document.createElement('style');
    style.textContent = FLOW_CSS.replace(/<\/?style>/g, '');
    document.head.appendChild(style);
  });

  // ── 1. Pure layout assertions (both layouts) ──────────────
  const layout = await page.evaluate(async (rf) => {
    const { layoutDagNodes, dagCardHtml, renderStellarSystem } = await import('/js/flowDag.js');

    const dag = layoutDagNodes(rf);

    // Render solar (dag) card into DOM
    const host = document.getElementById('host');
    const dagWrap = document.createElement('div');
    dagWrap.id = 'dag-wrap';
    dagWrap.innerHTML = dagCardHtml(rf);
    host.appendChild(dagWrap);

    // Render stellar card into DOM
    const stellarWrap = document.createElement('div');
    stellarWrap.id = 'stellar-wrap';
    host.appendChild(stellarWrap);
    renderStellarSystem(stellarWrap, [rf]);

    const rect = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { top: Math.round(r.top), left: Math.round(r.left), width: Math.round(r.width) };
    };

    return {
      dag,
      dom: {
        dagR1: rect('#dag-wrap .solar-node[data-node="r1"]'),
        dagR2: rect('#dag-wrap .solar-node[data-node="r2"]'),
        dagVerify: rect('#dag-wrap .solar-node[data-node="verify"]'),
        dagPlanner: rect('#dag-wrap .solar-node[data-node="planner"]'),
        dagEdgePaths: [...document.querySelectorAll('#dag-wrap svg.solar-edges path.flow-edge')].map(p => p.getAttribute('d')),
        stellarR1: rect('#stellar-wrap .flow-orbit-node[data-node="r1"]'),
        stellarR2: rect('#stellar-wrap .flow-orbit-node[data-node="r2"]'),
        stellarVerify: rect('#stellar-wrap .flow-orbit-node[data-node="verify"]'),
        stellarEdgePaths: [...document.querySelectorAll('#stellar-wrap svg.stellar-svg path.flow-edge')].map(p => p.getAttribute('d')),
        stellarNodeCount: document.querySelectorAll('#stellar-wrap .flow-orbit-node').length,
      },
    };
  }, RESEARCH_RF);

  const { dag, dom } = layout;
  const V = 120; // V_SPACING in flowDag.js

  // layoutDagNodes — pure
  check('dag: r1/r2 same depth (y equal)', dag.positions.r1.y === dag.positions.r2.y,
    `r1.y=${dag.positions.r1.y} r2.y=${dag.positions.r2.y}`);
  check('dag: verify depth = planner + 2', dag.positions.verify.y === dag.positions.planner.y + 2 * V,
    `verify.y=${dag.positions.verify.y}`);
  check('dag: writer depth = planner + 3', dag.positions.writer.y === dag.positions.planner.y + 3 * V,
    `writer.y=${dag.positions.writer.y}`);
  check('dag: r1/r2 horizontally spread', dag.positions.r1.x !== dag.positions.r2.x,
    `r1.x=${dag.positions.r1.x} r2.x=${dag.positions.r2.x}`);

  // dagCardHtml — DOM
  check('dag DOM: r1/r2 rendered at same row', dom.dagR1 && dom.dagR2 && dom.dagR1.top === dom.dagR2.top,
    `top r1=${dom.dagR1?.top} r2=${dom.dagR2?.top}`);
  check('dag DOM: verify one row below r1/r2', dom.dagVerify && dom.dagR1 && dom.dagVerify.top === dom.dagR1.top + V,
    `verify.top=${dom.dagVerify?.top} r1.top=${dom.dagR1?.top}`);
  check('dag DOM: 5 edges drawn ($return excluded)', dom.dagEdgePaths.length === 5,
    `paths=${dom.dagEdgePaths.length}`);
  check('dag DOM: fan-out edges distinct (no overlap)', new Set(dom.dagEdgePaths).size === dom.dagEdgePaths.length,
    `${new Set(dom.dagEdgePaths).size}/${dom.dagEdgePaths.length} unique`);
  check('dag DOM: fan-out edges symmetric', (() => {
    const [e1, e2] = dom.dagEdgePaths;
    if (!e1 || !e2) return false;
    const nums = s => s.match(/-?\d+\.?\d*/g).map(Number);
    const n1 = nums(e1), n2 = nums(e2);
    // planner at x=0: r1 endpoint x should be -r2 endpoint x
    return n1[n1.length - 2] === -n2[n2.length - 2];
  })(), `e1=${dom.dagEdgePaths[0]} e2=${dom.dagEdgePaths[1]}`);

  // renderStellarSystem — DOM (layoutStellarNodes is internal; verify via render)
  check('stellar: all 5 nodes rendered', dom.stellarNodeCount === 5, `count=${dom.stellarNodeCount}`);
  check('stellar: r1/r2 same ring depth (top equal)', dom.stellarR1 && dom.stellarR2 && dom.stellarR1.top === dom.stellarR2.top,
    `top r1=${dom.stellarR1?.top} r2=${dom.stellarR2?.top}`);
  check('stellar: r1/r2 horizontally spread (no stack)', dom.stellarR1 && dom.stellarR2 && dom.stellarR1.left !== dom.stellarR2.left,
    `left r1=${dom.stellarR1?.left} r2=${dom.stellarR2?.left}`);
  check('stellar: r1/r2 do not overlap', dom.stellarR1 && dom.stellarR2 &&
    Math.abs(dom.stellarR1.left - dom.stellarR2.left) >= dom.stellarR1.width,
    `|Δleft|=${Math.abs((dom.stellarR1?.left ?? 0) - (dom.stellarR2?.left ?? 0))} width=${dom.stellarR1?.width}`);
  check('stellar: verify one ring below r1/r2', dom.stellarVerify && dom.stellarR1 && dom.stellarVerify.top === dom.stellarR1.top + 160,
    `verify.top=${dom.stellarVerify?.top} r1.top=${dom.stellarR1?.top}`);
  check('stellar: 5 fan-out/join edges drawn ($return has no position → skipped)', dom.stellarEdgePaths.length === 5,
    `paths=${dom.stellarEdgePaths.length}`);
  check('stellar: fan-out edges distinct (no overlap)', new Set(dom.stellarEdgePaths).size === dom.stellarEdgePaths.length,
    `${new Set(dom.stellarEdgePaths).size}/${dom.stellarEdgePaths.length} unique`);

  check('no console/page errors', consoleErrors.length === 0, consoleErrors.join(' | ').slice(0, 300));

  await page.screenshot({ path: '/tmp/flow-dag-parallel.png', fullPage: true });
  console.log('\nScreenshot: /tmp/flow-dag-parallel.png');
} finally {
  await browser.close();
  server.close();
}

const failed = results.filter(r => !r.pass);
console.log(`\n${results.length - failed.length}/${results.length} assertions passed`);
process.exit(failed.length ? 1 : 0);
