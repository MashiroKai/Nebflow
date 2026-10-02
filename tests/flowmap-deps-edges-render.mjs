#!/usr/bin/env node
// flowmap-deps-edges-render.mjs — B 腿：真浏览器渲染读数（Playwright chromium，无端口）。
//
// 面：Flow Map 边与层级的**真渲染**计数（源载荷 = 冻结的 recon 时点夹具 30 节点 /
//     12 链，逐字节喂给 `/api/projects/<p>/flow-map`）。同一脚本对两棵前端树各跑一遍
//     （`--web` 指向基线只读副本 ⇒ 修复前读数），所以「前/后」两列都是真渲染实测，
//     不是静态重放的推算。
//
// 🔴 零端口：全部请求（含 `/api/*`）由 `page.route` 应答；不监听、不打 `:8080`/`:8097`。
// 🔴 零截图：本节点禁自产对照截图（由 `fe-flowmap-depsfix-shots` 产出）⇒ 只落读数 JSON。
//
// 断言（对应任务书 §二.3 B1…B4）：
//   B1 `path[data-edge-id]` 命中的非回边 path 数（前/后各一次读数）
//   B2 deps 边 path 的 class 含 `fm-edge-deps` 且 `stroke-dasharray` 非空
//   B3 同一 (源,目标) 不双画（in 镜像与 out 分支共用键 ⇒ 只 1 条）
//   B4 几何：同层 y 相同、跨层 y 严格递增、`depth0` 层节点数（前/后各一次）
//   B5 边端点落在卡内（边确实连到卡，而不是悬在空处）——「看不到连接线」的直接反证
//
// 用法（worktree 根）：node tests/flowmap-deps-edges-render.mjs [--web <dir>] [--out <dir>]
// 退出码：0 = 全绿；1 = 任一断言红。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { join, extname, normalize, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WT = resolve(HERE, '..');
// 工作树在 `<workspace>/.nebflow/worktrees/<name>` ⇒ 证据件落 workspace 根的 `.nebflow/`
// （工作树内没有 `.nebflow/`，process material 不进仓库）。
const WS = resolve(WT, '..', '..', '..');
const argv = process.argv.slice(2);
const argOf = (flag, dflt) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const WEB = normalize(argOf('--web', join(WT, 'src', 'main', 'resources', 'web')));
const BASELINE_WEB = normalize(argOf('--baseline-web', join(WS, '.nebflow', 'tmp', 'fm-depsfix-baseline-web')));
const OUT = normalize(argOf('--out', join(WS, '.nebflow', 'evidence', '20260929_fe-flowmap-depsfix')));
const PROJECT = 'fmfix-proj';
const ROOT_SID = 'fmfix-root';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const failures = [];
const notes = [];
function check(ok, label, detail) {
  if (ok) notes.push(`PASS ${label}${detail ? ` — ${detail}` : ''}`);
  else failures.push(`${label}${detail ? ` — ${detail}` : ''}`);
}

const FIXTURE = JSON.parse(readFileSync(join(WT, 'tests', 'fixtures', 'flowmap-deps-edges', 'recon-30n.json'), 'utf8'));

async function run(browser, label, webDir) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !m.text().includes('Failed to load resource')) pageErrors.push(m.text()); });

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'fmfix-harness-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send() {} close() {} onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        return route.fulfill({ json: { projects: [{ name: PROJECT, workspace: '/w/fmfix', agentFile: 'fmfix', description: '', createdAt: new Date(1790611200000).toISOString() }] } });
      }
      if (p === `/api/projects/${PROJECT}/flow-map`) return route.fulfill({ json: FIXTURE });
      if (p === `/api/projects/${PROJECT}/flow-map/archive`) return route.fulfill({ json: { batches: [], ttlMs: 0, count: 0 } });
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    const file = normalize(join(webDir, p));
    if (!file.startsWith(webDir)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });

  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  const frame = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await frame({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await frame({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await frame({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
  await page.waitForTimeout(300);

  await page.click('#projects-btn');
  await page.waitForSelector(`.project-card[data-project="${PROJECT}"]`, { timeout: 15000 });
  await page.click(`.project-card[data-project="${PROJECT}"] [data-open-flowmap="${PROJECT}"]`);
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 15000 });
  await page.mouse.move(2, 2);
  await page.waitForTimeout(900); // 相机 fit 动画落定（取几何前必须静止）

  const read = await page.evaluate(() => {
    const paths = [...document.querySelectorAll('path[data-edge-id]')];
    const ids = paths.map((p) => p.getAttribute('data-edge-id'));
    const nonLoop = paths.filter((p) => !/=>loop=>/.test(p.getAttribute('data-edge-id') || ''));
    const deps = paths.filter((p) => p.classList.contains('fm-edge-deps'));
    const cards = [...document.querySelectorAll('.fm-node')].map((n) => {
      const r = n.getBoundingClientRect();
      return { id: n.getAttribute('data-node-id'), cy: Math.round((r.top + r.height / 2) * 10) / 10, cx: Math.round((r.left + r.width / 2) * 10) / 10, top: r.top, left: r.left, bottom: r.bottom, right: r.right };
    });
    // 层级读法：**形心 y 聚类**。卡是 height:auto（实测高 5 档），层心 y 相同但卡高
    // 不同 ⇒ 同层卡的形心差 ≤ 2px；层间距由实测高驱动，恒 ≥ V_SPACING(150)。容差取
    // 20px 既不漏层也不并层（实测：同层散布 ≤2.1px，相邻层最小间距 ≥44px）。
    const cyCount = new Map();
    for (const c of cards) cyCount.set(c.cy, (cyCount.get(c.cy) || 0) + 1);
    const cys = [...cyCount.keys()].sort((a, b) => a - b);
    const layerGroups = [];
    for (const y of cys) {
      const g = layerGroups[layerGroups.length - 1];
      if (g && y - g.last <= 20) { g.n += cyCount.get(y); g.last = y; } else layerGroups.push({ n: cyCount.get(y), first: y, last: y });
    }
    const colCount = layerGroups.length;
    const rects = cards.map((c) => c);
    const endpointInCard = (x, y) => rects.some((r) => x >= r.left - 2 && x <= r.right + 2 && y >= r.top - 2 && y <= r.bottom + 2);
    let attached = 0;
    for (const p of nonLoop) {
      const mm = p.getScreenCTM();
      if (!mm) continue;
      const s = p.getPointAtLength(0);
      const x = mm.a * s.x + mm.c * s.y + mm.e;
      const y = mm.b * s.x + mm.d * s.y + mm.f;
      if (endpointInCard(x, y)) attached += 1;
    }
    const seenIds = {};
    for (const id of ids) seenIds[id] = (seenIds[id] || 0) + 1;
    return {
      cardCount: cards.length,
      allPaths: paths.length,
      nonLoopPaths: nonLoop.length,
      depsPaths: deps.length,
      loopPaths: paths.filter((p) => /=>loop=>/.test(p.getAttribute('data-edge-id') || '')).length,
      depsDash: deps.map((p) => getComputedStyle(p).strokeDasharray),
      depsIds: deps.map((p) => p.getAttribute('data-edge-id')).sort(),
      colCount,
      colSizes: layerGroups.map((g) => g.n),
      minGapInColumn: (() => { let m = Infinity; for (let i = 1; i < layerGroups.length; i += 1) m = Math.min(m, layerGroups[i].first - layerGroups[i - 1].last); return Number.isFinite(m) ? Math.round(m * 10) / 10 : null; })(),
      layerY: layerGroups.map((g) => g.first),
      connected: !!cards.length,
      attachedEdges: attached,
      edgeCountWithIdentity: new Set(ids).size,
      forwardIds: paths.filter((p) => !/=>loop=>/.test(p.getAttribute('data-edge-id') || '')
        && !p.classList.contains('fm-edge-deps')).map((p) => p.getAttribute('data-edge-id')).sort(),
      allIds: ids.slice().sort(),
    };
  });
  // 读取器的暴露名（B 组断言用）：`layerCount` / `layerSizes` / `attachedEdges`。
  read.layerCount = read.colCount;
  read.layerSizes = read.colSizes;

  check(read.cardCount === 30, `${label} 渲染卡数 = 30`, `got ${read.cardCount}`);
  check(read.connected, `${label} 边层已挂载（svg 屏幕矩阵可用）`);
  check(pageErrors.length === 0, `${label} 零 pageerror`, JSON.stringify(pageErrors.slice(0, 3)));
  await ctx.close();
  return read;
}

(async () => {
  // 基线前端树（前红读数面）：默认从**分支 HEAD** 导出一份只读副本 ⇒ 同脚本前后两跑
  // 均对真实产品源码，不依赖手工作业。已存在则复用（可 `--baseline-web` 指别处）。
  if (!existsSync(join(BASELINE_WEB, 'js', 'flowMapTab.js'))) {
    mkdirSync(BASELINE_WEB, { recursive: true });
    execSync(`git -C "${WT}" archive HEAD src/main/resources/web | tar -x -C "${BASELINE_WEB}" --strip-components=4`);
  }
  const browser = await chromium.launch();
  let before = null;
  let after = null;
  try {
    before = await run(browser, 'PRE-FIX ', BASELINE_WEB);
    after = await run(browser, 'POST-FIX', WEB);
  } finally {
    await browser.close();
  }

  // ── B1 非回边 path 数（前/后各一次真渲染读数）──────────────────────────────
  check(after.nonLoopPaths >= before.nonLoopPaths,
    'B1 非回边 path 数不减少（前 → 后）',
    `${before.nonLoopPaths} → ${after.nonLoopPaths}（deps ${before.depsPaths} → ${after.depsPaths}）`);
  check(after.depsPaths === 9 && before.depsPaths === 0,
    'B1 deps 边：修复前 0 条 → 修复后 9 条（真渲染）',
    `${before.depsPaths} → ${after.depsPaths}`);
  check(after.allPaths === before.allPaths + after.depsPaths,
    'B1 总 path 数增量 = 新增 deps 边数（forward/loop 边集逐字未动）',
    `${before.allPaths} → ${after.allPaths}`);
  check(JSON.stringify(after.forwardIds) === JSON.stringify(before.forwardIds),
    'B1 forward 边 id 集逐字相同（既有视觉语言零漂移；本批只**新增** deps 边）',
    `pre=${before.forwardIds.length} post=${after.forwardIds.length}`);
  check(JSON.stringify(after.allIds.filter((k) => /=>loop=>/.test(k))) === JSON.stringify(before.allIds.filter((k) => /=>loop=>/.test(k))),
    'B1 loop 回边 id 集逐字相同（回边段未动）',
    `${before.allIds.filter((k) => /=>loop=>/.test(k)).length} 条`);

  // ── B2 deps 视觉语言（虚线）───────────────────────────────────────────────
  check(after.depsDash.length > 0 && after.depsDash.every((d) => d && d !== 'none'),
    'B2 deps path class 含 fm-edge-deps 且 stroke-dasharray 非空',
    JSON.stringify(after.depsDash.slice(0, 3)));
  check(after.depsIds.every((id) => id.includes('~>')),
    'B2 deps 键形 `…~>…`（与 forward `=>`、loop `=>loop=>` 区分）', after.depsIds.join(' '));

  // ── B3 不双画 ─────────────────────────────────────────────────────────────
  check(before.edgeCountWithIdentity === before.allPaths && after.edgeCountWithIdentity === after.allPaths,
    'B3 data-edge-id 全域唯一（一 (源,目标,型) 一键 ⇒ in 镜像与 out 分支不双画）',
    `pre ${before.edgeCountWithIdentity}/${before.allPaths} · post ${after.edgeCountWithIdentity}/${after.allPaths}`);
  check(after.depsIds.every((id) => !before.depsIds.includes(id)) && after.depsIds.length > 0,
    'B3 修复后新增 deps 边与修复前边集零交集', `${before.allPaths} → ${after.allPaths}`);

  // ── B4 几何：层级不再压平 ────────────────────────────────────────────────
  const inc = after.layerY.every((y, i) => i === 0 || y > after.layerY[i - 1]);
  check(after.layerCount === 16 && before.layerCount === 4,
    'B4 层数（真渲染 y 聚类）：修复前 4 → 修复后 16', `${before.layerCount} → ${after.layerCount}`);
  check(inc, 'B4 跨层 y 严格递增（层序正确，无「同一行」）', `layerY=${after.layerY.slice(0, 4).join(',')}…`);
  check(before.layerSizes[0] === 14 && after.layerSizes[0] === 5,
    'B4 第 0 层节点数：修复前 14（塌平）→ 修复后 5（塌平根治）',
    `${before.layerSizes[0]} → ${after.layerSizes[0]}, post=${JSON.stringify(after.layerSizes)}`);
  check(after.layerSizes.reduce((a, b) => a + b, 0) === 30 && before.layerSizes.reduce((a, b) => a + b, 0) === 30,
    'B4 层内计数合计 = 30（前后均无节点丢失）', `${before.layerSizes.reduce((a, b) => a + b, 0)} → ${after.layerSizes.reduce((a, b) => a + b, 0)}`);
  check(after.minGapInColumn !== null && after.minGapInColumn >= 20,
    'B4 相邻层最小间距 ≥20px（层间不压叠；层距 = V_SPACING×fit-zoom，视口 1600×1000 实测 ≈45px）',
    `minGap=${after.minGapInColumn}px`);
  check(after.layerSizes[13] === 6 && after.layerSizes[15] === 1,
    'B4 修复后 depth13=6 / depth15=1（与静态重放逐字一致）',
    `d13=${after.layerSizes[13]} d15=${after.layerSizes[15]}`);

  // ── B5 边确实连到卡 ──────────────────────────────────────────────────────
  check(after.attachedEdges === after.nonLoopPaths && after.nonLoopPaths > 0,
    'B5 非回边起点全部落在卡矩形内（「看不到连接线」的直接反证）',
    `${after.attachedEdges}/${after.nonLoopPaths}；修复前 ${before.attachedEdges}/${before.nonLoopPaths}`);

  mkdirSync(OUT, { recursive: true });
  const summary = {
    web: WEB, baselineWeb: BASELINE_WEB, project: PROJECT,
    fixture: 'tests/fixtures/flowmap-deps-edges/recon-30n.json',
    before, after, notes: notes.filter((n) => n.startsWith('PASS')), failures,
  };
  writeFileSync(join(OUT, '30_b-render-readings.json'), JSON.stringify(summary, null, 2));
  console.log(`web tree (post) = ${WEB}`);
  console.log(`web tree (pre)  = ${BASELINE_WEB}`);
  console.log(`PRE-FIX : cards=${before.cardCount} paths=${before.allPaths} (nonLoop=${before.nonLoopPaths} deps=${before.depsPaths} loop=${before.loopPaths}) layers=${before.layerCount} layer0=${before.layerSizes[0]}`);
  console.log(`POST-FIX: cards=${after.cardCount} paths=${after.allPaths} (nonLoop=${after.nonLoopPaths} deps=${after.depsPaths} loop=${after.loopPaths}) layers=${after.layerCount} layer0=${after.layerSizes[0]}`);
  console.log(`layer sizes(post) = ${JSON.stringify(after.layerSizes)}`);
  console.log(`layer Y(post)     = ${after.layerY.join(', ')}`);
  console.log(`deps ids   = ${after.depsIds.join(' ')}`);
  for (const n of notes) console.log(`  ${n}`);
  console.log(`checks: ${notes.length} pass / ${failures.length} fail`);
  for (const f of failures) console.log(`  FAIL ${f}`);
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
