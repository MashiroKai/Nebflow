// wiki-graph-multivault.spec.mjs — #744-B 多项目图谱契约 spec（隔离静态服务器 +
// 页内 mock，真实 UI 代码全量执行；8100+ 端口纪律，永不触碰宿主 8080）。
//
// 台账 #744-B（图谱演进 B 批）：每个项目 vault 各成一张图谱 + 子项目层级 +
// 面板项目切换器。本 spec 锁契约：
//
//   §A 切换器（.wg-switch-select）：
//     A1 选项 = 全部项目('') + 清单里的每个项目；默认选「全部项目」= 合并视图；
//     A2 切到项目 → GET /api/wiki/graph?project=X（vault 参数不带 = 根 vault），
//        统计行只反映该项目（docs/引用数隔离），合并大图节点不再出现；
//   §B 子项目层级（childVaults 聚合节点）：
//     B1 项目根视图渲染聚合节点（isVault，双环圆点带名称+docCount label）；
//     B2 点击聚合节点 → 详情卡显示子图谱卡（名称/文档数徽标/进入子图谱按钮）；
//     B3 进入子图谱 → GET ?project=X&vault=sub/docs，面包屑 X › sub 出现，
//        节点只剩子 vault 的文档，统计随视图切换；
//     B4 面包屑点回父级 → 回到根视图，聚合节点回来；
//   §C 数据隔离与跨库引用：
//     C1 external=true 占位不画成节点、不进断链清单，stats.crossVault 进统计行；
//     C2 断链清单只列当前视图的 unresolved（服务器 stats 口径一致）；
//   §D 合并视图默认兼容：
//     D1 无 project 参数的响应（pre-#744-B 形状，无 stats/childVaults/view）→
//        统计行走客户端计算（行为不变），切换器仍渲染；
//   R0 全流程 console 零错误（每个用例尾部断言）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8197; // fixed, 8100+ — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

// ── Mock 数据：两个项目；stand 有子 vault（sub/docs），DOSTEL 无 ──

const VAULTS = {
  vaults: [
    { project: 'stand', device: 'self', workspace: '/w/stand', vault: '/w/stand/docs', present: true },
    { project: 'DOSTEL', device: 'self', workspace: '/w/dostel', vault: '/w/dostel/docs', present: true },
  ],
};

const MERGED = {
  projects: [
    { name: 'stand', vault: '/w/stand/docs', docCount: 2 },
    { name: 'DOSTEL', vault: '/w/dostel/docs', docCount: 1 },
  ],
  nodes: [
    { id: 'stand:docs/arch.md', project: 'stand', rel: 'arch.md', title: 'Architecture', kind: 'doc' },
    { id: 'stand:docs/design.md', project: 'stand', rel: 'design.md', title: 'Design', kind: 'doc' },
    { id: 'DOSTEL:docs/paper.md', project: 'DOSTEL', rel: 'paper.md', title: 'Paper', kind: 'doc' },
  ],
  edges: [{ from: 'stand:docs/design.md', to: 'stand:docs/arch.md' }],
};

const STAND_ROOT = {
  generatedAt: 1,
  view: { project: 'stand', vault: 'docs', breadcrumb: [{ name: 'stand', vault: 'docs' }] },
  projects: [{ name: 'stand', device: 'self', workspace: '/w/stand', vault: 'docs', docCount: 2, error: null, stub: false }],
  childVaults: [{ name: 'subs', rel: 'subs/docs', docCount: 1 }],
  nodes: [
    { id: 'stand:docs/arch.md', project: 'stand', rel: 'arch.md', title: 'Architecture', kind: 'doc' },
    { id: 'stand:docs/design.md', project: 'stand', rel: 'design.md', title: 'Design', kind: 'doc' },
    { id: 'stand:subs/docs/child.md', project: 'stand', rel: 'child.md', title: 'Child', kind: 'doc', stub: true, external: true },
  ],
  edges: [
    { from: 'stand:docs/design.md', to: 'stand:docs/arch.md' },
    { from: 'stand:docs/design.md', to: 'stand:subs/docs/child.md' },
  ],
  stats: { docs: 2, attachments: 0, edges: 1, unresolved: 0, crossVault: 1 },
  error: null,
  stub: false,
};

const STAND_SUB = {
  generatedAt: 2,
  view: { project: 'stand', vault: 'subs/docs', breadcrumb: [{ name: 'stand', vault: 'docs' }, { name: 'subs', vault: 'subs/docs' }] },
  projects: [{ name: 'stand', device: 'self', workspace: '/w/stand', vault: 'subs/docs', docCount: 1, error: null, stub: false }],
  childVaults: [],
  nodes: [
    { id: 'stand:child.md', project: 'stand', rel: 'child.md', title: 'Child', kind: 'doc' },
    { id: 'stand:arch.md', project: 'stand', rel: 'arch.md', title: 'Architecture', kind: 'doc', stub: true, external: true },
  ],
  edges: [{ from: 'stand:child.md', to: 'stand:arch.md' }],
  stats: { docs: 1, attachments: 0, edges: 0, unresolved: 0, crossVault: 1 },
  error: null,
  stub: false,
};

function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;

/** Graph route mock keyed by query — records every graph request for assertions. */
function graphHandler(record) {
  return (route) => {
    const url = new URL(route.request().url());
    const q = url.searchParams;
    const key = q.get('project')
      ? (q.get('vault') ? `${q.get('project')}|${q.get('vault')}` : q.get('project'))
      : 'merged';
    if (record) record.push(key);
    const body = key === 'merged' ? MERGED : key === 'stand' ? STAND_ROOT : key === 'stand|subs/docs' ? STAND_SUB : { projects: [], nodes: [], edges: [] };
    route.fulfill({ json: body });
  };
}

async function boot(page, { viewport } = {}) {
  await page.setViewportSize(viewport || { width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('stand_locale', 'en');
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/vaults', r => r.fulfill({ json: VAULTS }));
  await page.route('**/agents/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
    });
  });
  await page.goto(base);
  await page.evaluate(() => document.body.classList.add('canvas-open'));
}

async function openGraph(page, record) {
  // NOTE: trailing '*' — glob patterns must swallow the query string, else
  // ?project=/&vault= requests fall through to the '**/api/**' catch-all.
  await page.route('**/api/wiki/graph*', graphHandler(record));
  await page.evaluate(() => import('./js/wikiGraph.js').then(m => m.openWikiGraphTab()));
  await expect.poll(() => page.evaluate(() => !!window.__wikiGraphProbe?.()), { timeout: 5000 }).toBe(true);
  await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().sideMounted), { timeout: 5000 }).toBe(true);
}

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.describe('wiki-graph #744-B 多项目图谱', () => {
  test('A1+A2+D1 切换器默认合并视图；切到项目 = 隔离视图', async ({ page }) => {
    const reqs = [];
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    await boot(page);
    await openGraph(page, reqs);

    // A1: switcher rendered, options = '' + stand + DOSTEL; default '' (merged)
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().vaultOptions))
      .toEqual(['', 'stand', 'DOSTEL']);
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selProject)).toBe('');
    expect(reqs.filter(r => r === 'merged').length).toBeGreaterThanOrEqual(1);

    // D1: merged view has no stats key → client-side computation still works
    const mergedNodes = await page.evaluate(() => window.__wikiGraphProbe().nodes.length);
    expect(mergedNodes).toBe(3);

    // A2: switch to stand → project param request + isolated nodes
    await page.selectOption('.wg-switch-select', 'stand');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selProject)).toBe('stand');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().nodes.filter(n => !n.isVault).length)).toBe(2);
    expect(reqs).toContain('stand');

    // C1: external placeholder not drawn, crossVault counted into stats line
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().crossVault)).toBe(1);
    const stats = await page.evaluate(() => document.querySelector('#wiki-graph-side .wg-stats')?.textContent || '');
    expect(stats).toContain('2 docs');
    expect(stats).toContain('cross-vault');

    // R0
    expect(errors).toEqual([]);
  });

  test('B1-B4 子 vault 聚合节点 → 详情卡 → 下钻 → 面包屑回父级', async ({ page }) => {
    const reqs = [];
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    await boot(page);
    await openGraph(page, reqs);
    await page.selectOption('.wg-switch-select', 'stand');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selProject)).toBe('stand');

    // B1: aggregate node present with the child rel id + always labeled
    const vaultNode = await page.evaluate(() => window.__wikiGraphProbe().nodes.find(n => n.isVault));
    expect(vaultNode).toBeTruthy();
    expect(vaultNode.id).toBe('__vault:subs/docs');

    // B2: select it → detail card shows the sub-graph card + drill button.
    // Center the aggregate node (real pick path needs a hittable spot), then
    // drive a REAL mouse click at its screen position (page.mouse — trusted
    // pointer events; synthetic PointerEvent breaks setPointerCapture).
    await page.evaluate(() => {
      const p = window.__wikiGraphProbe();
      const c = document.querySelector('.wiki-canvas');
      const r = c.getBoundingClientRect();
      const n = p.nodes.find((m) => m.isVault);
      const sx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
      const sy = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
      c.dispatchEvent(new WheelEvent('wheel', {
        deltaX: p.W / 2 - sx, deltaY: p.H / 2 - sy, shiftKey: true, // CAD 批：shift+wheel = 保底平移
        clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
        bubbles: true, cancelable: true,
      }));
    });
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const box = await page.locator('.wiki-canvas').boundingBox();
    const p1 = await page.evaluate(() => window.__wikiGraphProbe());
    const hub = p1.nodes.find((n) => n.isVault);
    await page.mouse.click(
      box.x + (hub.x - p1.W / 2) * p1.scale + p1.W / 2 + p1.vx,
      box.y + (hub.y - p1.H / 2) * p1.scale + p1.H / 2 + p1.vy,
    );
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selected)).toBe('__vault:subs/docs');
    const cardText = await page.evaluate(() => document.querySelector('#wiki-graph-side .wg-detail')?.textContent || '');
    expect(cardText).toContain('subs');
    expect(cardText).toContain('1 docs');

    // B3: drill in → vault param request + breadcrumb + isolated child nodes
    await page.evaluate(() => document.querySelector('#wiki-graph-side .wg-detail .openbtn').click());
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selVault)).toBe('subs/docs');
    expect(reqs).toContain('stand|subs/docs');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().nodes.filter(n => !n.isVault).map(n => n.id))).toEqual(['stand:child.md']);
    const crumbs = await page.evaluate(() => window.__wikiGraphProbe().crumbs);
    expect(crumbs.map(c => c.name)).toEqual(['stand', 'subs']);
    const crumbVisible = await page.evaluate(() => {
      const el = document.querySelector('#wiki-graph-side .wg-crumbs');
      return !!el && !el.hidden && el.textContent.includes('›');
    });
    expect(crumbVisible).toBe(true);

    // B4: click the parent crumb → back to root view, aggregate returns
    await page.evaluate(() => document.querySelector('#wiki-graph-side .wg-crumbs button.crumb').click());
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selVault)).toBe('');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().nodes.filter(n => n.isVault).length)).toBe(1);
    expect(reqs.filter(r => r === 'stand').length).toBeGreaterThanOrEqual(2);

    // R0
    expect(errors).toEqual([]);
  });

  test('C2 standalone view stats/broken isolation (mobile panebar parity via probe)', async ({ page }) => {
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    await boot(page);
    await openGraph(page, null);
    await page.selectOption('.wg-switch-select', 'stand');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selProject)).toBe('stand');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().unresolved)).toBe(0);
    await page.selectOption('.wg-switch-select', '');
    await expect.poll(() => page.evaluate(() => window.__wikiGraphProbe().selProject)).toBe('');
    expect(errors).toEqual([]);
  });
});
