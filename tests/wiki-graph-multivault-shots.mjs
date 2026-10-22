// wiki-graph-multivault-shots.mjs — #744-B 多项目图谱批验收证据（亮暗双主题实拍）。
//
// 演示面（真实 vault 内容，非伪造）：
//   · 合并视图（默认）——stand 仓 docs（88 篇）+ 视觉工作室 docs（1 篇）真实内容
//     经 check_wiki_docs.py --graph 导出、按 API wire 形状回放（id = <project>:<rel>）
//   · 切换器选中 stand → 项目隔离视图（真实 88 篇图谱）
//   · 切换器选中 视觉工作室 → 真实单文档图谱
//   · 子项目层级 = demo-subs 夹具（真实仓内暂无 <sub>/docs 子 vault——发现式约定
//     需作者先建目录；聚合节点/下钻/面包屑契约由 tests/wiki-graph-multivault.spec.mjs
//     锁死，此处补视觉样张）
//
// 断言（stdout）+ 截图（shots/）：合并/项目切换/子图谱下钻 × 亮暗 = 12 张。
// 用法：node tests/wiki-graph-multivault-shots.mjs
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, rmSync, mkdtempSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { homedir, tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const CHECK = join(HERE, '..', 'src', 'main', 'resources', 'seed', 'plugins', 'visual-report', 'skills', 'wiki-docs', 'scripts', 'check_wiki_docs.py');
const OUT = join(HERE, '..', 'evidence', '20261022_wiki-graph-multivault__delegate-nebflow-56930fbd', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
const PORT = 8199;

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// ── 真实 vault 导出（WikiIndex 同语义解析器）→ API wire 形状 ──────────────
const tmp = mkdtempSync(join(tmpdir(), 'wg-shots-'));
const readFileSyncSafe = (p) => { try { return readFileSync(p, 'utf8'); } catch { return null; } };
const REAL = [];
const VAULT_ROOTS = [
  { project: 'stand', vault: '/Users/kaiyu/Claude code/Stand/docs' },
  { project: '视觉工作室', vault: join(homedir(), '.stand', 'projects', '视觉工作室', 'docs') },
];
for (const { project, vault } of VAULT_ROOTS) {
  const out = join(tmp, project + '.json');
  // exit 1 = 有违规（真实仓存量，与校验无关）——只要 --graph 落盘就继续。
  const r = { status: 0 };
  try {
    execFileSync('python3', [CHECK, '--root', vault, '--graph', out, '--project', project, '--skip-concepts'], { stdio: 'pipe' });
  } catch (e) { r.status = e.status; }
  if (!readFileSyncSafe(out)) throw new Error(`graph export failed for ${project} (exit ${r.status})`);
  const raw = JSON.parse(readFileSync(out, 'utf8'));
  const fix = (id) => (id.includes(':') ? id : `${project}:${id}`);
  REAL.push({
    project,
    graph: {
      projects: [{ name: project, device: 'self', vault, docCount: raw.nodes.filter(n => n.kind === 'doc').length, error: null, stub: false }],
      nodes: raw.nodes.map(n => ({ ...n, id: fix(n.id), stub: !!n.stub })),
      edges: raw.edges.map(e => ({ ...e, from: fix(e.from), to: fix(e.to) })),
      stats: null, // merged shape (client-computed) — real export has no server stats
    },
  });
}
ok('real vault exports', REAL.length === 2 && REAL.every(r => r.graph.nodes.length > 0),
  REAL.map(r => `${r.project}=${r.graph.nodes.length}n`).join(' '));

// 夹具：子项目层级演示（demo-subs 根 + subs/docs 子 vault）
const FIXTURE_ROOT = {
  view: { project: 'demo-subs', vault: 'docs', breadcrumb: [{ name: 'demo-subs', vault: 'docs' }] },
  projects: [{ name: 'demo-subs', device: 'self', vault: '/fixture/demo-subs/docs', docCount: 3, error: null, stub: false }],
  childVaults: [{ name: 'detector', rel: 'detector/docs', docCount: 2 }],
  nodes: [
    { id: 'demo-subs:index.md', project: 'demo-subs', rel: 'index.md', title: 'Demo Index', kind: 'doc', summary: 'Fixture project root vault — aggregate node on the right drills into the sub-project graph.' },
    { id: 'demo-subs:plan.md', project: 'demo-subs', rel: 'plan.md', title: 'Plan', kind: 'doc', summary: 'Fixture document.' },
    { id: 'demo-subs:notes.md', project: 'demo-subs', rel: 'notes.md', title: 'Notes', kind: 'doc', summary: 'Fixture document.' },
  ],
  edges: [{ from: 'demo-subs:index.md', to: 'demo-subs:plan.md' }],
  stats: { docs: 3, attachments: 0, edges: 1, unresolved: 0, crossVault: 0 },
};
const FIXTURE_SUB = {
  view: { project: 'demo-subs', vault: 'detector/docs', breadcrumb: [{ name: 'demo-subs', vault: 'docs' }, { name: 'detector', vault: 'detector/docs' }] },
  projects: [{ name: 'demo-subs', device: 'self', vault: '/fixture/demo-subs/docs/detector/docs', docCount: 2, error: null, stub: false }],
  childVaults: [],
  nodes: [
    { id: 'demo-subs:readout.md', project: 'demo-subs', rel: 'readout.md', title: 'Readout Design', kind: 'doc', summary: 'Fixture sub-vault document — its own graph, isolated from the parent.' },
    { id: 'demo-subs:sim.md', project: 'demo-subs', rel: 'sim.md', title: 'Simulation Log', kind: 'doc', summary: 'Fixture sub-vault document.' },
  ],
  edges: [{ from: 'demo-subs:readout.md', to: 'demo-subs:sim.md' }],
  stats: { docs: 2, attachments: 0, edges: 1, unresolved: 0, crossVault: 0 },
};

const MERGED = {
  projects: REAL.flatMap(r => r.graph.projects),
  nodes: REAL.flatMap(r => r.graph.nodes),
  edges: REAL.flatMap(r => r.graph.edges),
};
const VAULTS = {
  vaults: [
    ...VAULT_ROOTS.map(v => ({ project: v.project, device: 'self', vault: v.vault, present: true })),
    { project: 'demo-subs', device: 'self', vault: '/fixture/demo-subs/docs', present: true },
  ],
};

const server = createServer(async (req, res) => {
  try {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/') p = '/index.html';
    if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    const data = readFileSync(join(WEB, p));
    res.writeHead(200, { 'content-type': MIME[extname(p)] || 'application/octet-stream' });
    res.end(data);
  } catch { res.writeHead(404); res.end(); }
});
await new Promise(r => server.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch();

async function boot(scheme) {
  const page = await (await browser.newContext({
    viewport: { width: 1440, height: 900 }, colorScheme: scheme, deviceScaleFactor: 2,
  })).newPage();
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('stand_locale', 'zh-CN');
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/vaults', r => r.fulfill({ json: VAULTS }));
  await page.route('**/agents/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => { ws.onMessage(() => {}); });
  await page.route('**/api/wiki/graph*', r => {
    const q = new URL(r.request().url()).searchParams;
    const proj = q.get('project') || '';
    const vault = q.get('vault') || '';
    let body = MERGED;
    if (proj === 'demo-subs') body = vault ? FIXTURE_SUB : FIXTURE_ROOT;
    else {
      const hit = REAL.find(x => x.project === proj);
      if (hit) body = { ...hit.graph, view: { project: proj, vault: 'docs', breadcrumb: [{ name: proj, vault: 'docs' }] }, childVaults: [], stats: { docs: hit.graph.nodes.filter(n => n.kind === 'doc').length, attachments: hit.graph.nodes.filter(n => n.kind === 'attachment').length, edges: hit.graph.edges.length, unresolved: 0, crossVault: 0 } };
    }
    r.fulfill({ json: body });
  });
  await page.goto(`http://127.0.0.1:${PORT}`);
  await page.evaluate(() => document.body.classList.add('canvas-open'));
  await page.evaluate(() => import('./js/wikiGraph.js').then(m => m.openWikiGraphTab()));
  await page.waitForFunction(() => window.__wikiGraphProbe?.() !== null && window.__wikiGraphProbe().sideMounted, null, { timeout: 10000 });
  await page.waitForTimeout(2200); // layout settles
  return { page, errors };
}

async function settle(page) {
  await page.waitForFunction(() => window.__wikiGraphProbe().alpha < 0.03, null, { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(400);
}

for (const scheme of ['dark', 'light']) {
  const tag = scheme;
  // ① 合并视图（默认）
  let { page, errors } = await boot(scheme);
  await settle(page);
  await page.screenshot({ path: join(OUT, `1-merged-${tag}.png`) });
  ok(`merged view (${tag})`, (await page.evaluate(() => window.__wikiGraphProbe().nodes.length)) > 50);

  // ② 切到 stand（真实 88 篇）
  await page.selectOption('.wg-switch-select', 'stand');
  await settle(page);
  await page.screenshot({ path: join(OUT, `2-stand-${tag}.png`) });
  ok(`stand view (${tag})`, (await page.evaluate(() => window.__wikiGraphProbe().selProject)) === 'stand');

  // ③ 切到 视觉工作室（真实单文档 vault）
  await page.selectOption('.wg-switch-select', '视觉工作室');
  await settle(page);
  await page.screenshot({ path: join(OUT, `3-shijue-${tag}.png`) });
  ok(`视觉工作室 view (${tag})`, (await page.evaluate(() => window.__wikiGraphProbe().nodes.length)) >= 1);

  // ④ 夹具：子项目聚合节点（根视图）
  await page.selectOption('.wg-switch-select', 'demo-subs');
  await settle(page);
  await page.screenshot({ path: join(OUT, `4-subroot-${tag}.png`) });
  ok(`demo-subs aggregate (${tag})`, (await page.evaluate(() => window.__wikiGraphProbe().nodes.filter(n => n.isVault).length)) === 1);

  // ⑤ 下钻进子图谱（面包屑）
  await page.evaluate(() => {
    const p = window.__wikiGraphProbe();
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    const n = p.nodes.find((m) => m.isVault);
    const sx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
    const sy = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
    c.dispatchEvent(new WheelEvent('wheel', { deltaX: p.W / 2 - sx, deltaY: p.H / 2 - sy, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true }));
  });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  const box = await page.locator('.wiki-canvas').boundingBox();
  const p1 = await page.evaluate(() => window.__wikiGraphProbe());
  const hub = p1.nodes.find((n) => n.isVault);
  await page.mouse.click(box.x + (hub.x - p1.W / 2) * p1.scale + p1.W / 2 + p1.vx, box.y + (hub.y - p1.H / 2) * p1.scale + p1.H / 2 + p1.vy);
  await page.waitForFunction(() => window.__wikiGraphProbe().selected === '__vault:detector/docs', null, { timeout: 5000 });
  // 选中 ≠ 下钻：下钻走详情卡「进入子图谱」按钮（同 spec B3 的真路径）。
  await page.click('#wiki-graph-side .wg-detail .openbtn');
  await page.waitForFunction(() => window.__wikiGraphProbe().selVault === 'detector/docs', null, { timeout: 5000 });
  await settle(page);
  await page.screenshot({ path: join(OUT, `5-subdrill-${tag}.png`) });
  ok(`drill-in + breadcrumb (${tag})`, (await page.evaluate(() => (window.__wikiGraphProbe().crumbs || []).map(c => c.name).join('>'))) === 'demo-subs>detector');

  ok(`console zero errors (${tag})`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.context().close();
}

await browser.close();
server.close();
rmSync(tmp, { recursive: true, force: true });
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
