// wiki-graph-tip-dup-label.spec.mjs — 图谱悬停 pill「文件名称重复显示」修复批 spec 钉
// （作者报障 2026-10-25 10:4x：「现在还是存在胶囊底下，文件名称重复显示的问题，主要是图谱。」）
//
// 根因两处（wikiGraph.js）：
//   ① showEdgeTip 的 .td 行复读 .tt 行的同一文档名（非段落态恒复读；段落快照态
//      在 para.title 缺失时也复读）——pill 内部上下两行同名；
//   ② 悬停节点的画布标签（labelAlpha=1）与悬停 pill 的标题同屏复读——节点旁
//      两处同名。修复：pill 点名的节点（悬停节点 / 引用边目标）画布标签让位
//      （view.tipFor；hideTip 即恢复），.td 只补元信息（§ 段落态保留文档名交代
//      归属——那时不是重复）。
//
// 锁面：
//   T1 悬停节点：pill 显示名字 + 该节点画布标签带墨迹清零（像素级）+ 移开后墨迹
//      恢复（消除重复来源，非掩盖——标签随 pill 出没）；
//   T2 普通引用边悬停：pill .tt = 目标文档名，.td 零复读（只补 被引/引出 度数）；
//   T3 段落快照边（para.title 在）：.tt = § 段标题，.td 保留文档名（交代归属，
//      非重复）+ 跳转提示；
//   T4 锚未命中段落索引（§P3 回退态）：.tt = 文档名，.td 零复读 + 跳转提示保留
//      （wiki-graph-paragraph §P3 契约不回归）；
//   R0 全流程 console 零错误。
//
// 运行：npx playwright test tests/wiki-graph-tip-dup-label.spec.mjs
// （隔离静态服务器 + 页内 mock，真实 UI 代码全量执行；8100+ 端口纪律，
//   永不触碰宿主 8080。boot 同 wiki-graph-ux.spec.mjs。）

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8197; // fixed, 8100+ — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

const WIKI_GRAPH = {
  projects: [{ name: 'alpha', vault: '/w/alpha', docCount: 4 }],
  nodes: [
    // solo：零边孤岛节点 —— 标签带下方永无连线穿过，像素断言干净
    { id: 'alpha:docs/solo.md', project: 'alpha', rel: 'docs/solo.md', title: 'Solo Doc', kind: 'doc' },
    { id: 'alpha:docs/a.md', project: 'alpha', rel: 'docs/a.md', title: 'Alpha One', kind: 'doc' },
    { id: 'alpha:docs/b.md', project: 'alpha', rel: 'docs/b.md', title: 'Bravo Two', kind: 'doc',
      paragraphs: [{ anchor: '^setup', title: 'Setup Steps', text: 'Step one two three.' }] },
    { id: 'alpha:docs/c.md', project: 'alpha', rel: 'docs/c.md', title: 'Charlie Three', kind: 'doc' },
    { id: 'alpha:docs/d.md', project: 'alpha', rel: 'docs/d.md', title: 'Delta Four', kind: 'doc', paragraphs: [] },
  ],
  edges: [
    { from: 'alpha:docs/a.md', to: 'alpha:docs/b.md', anchor: '^setup' },   // 段落快照态（para.title 在）
    { from: 'alpha:docs/a.md', to: 'alpha:docs/c.md' },                     // 普通边（度数 pill）
    { from: 'alpha:docs/a.md', to: 'alpha:docs/d.md', anchor: '^missing' }, // 锚未命中（回退态）
  ],
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

async function boot(page) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('stand_locale', 'en');
  });
  // Playwright route matching is LIFO — catch-all FIRST, specific routes AFTER.
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/graph', r => r.fulfill({ json: WIKI_GRAPH }));
  await page.route('**/agents/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.goto(base);
  return errors;
}

async function openGraph(page) {
  await page.click('#wiki-btn');
  await page.waitForSelector('.wiki-graph-pane', { state: 'attached', timeout: 10000 });
  await page.waitForFunction(() => {
    const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
    return p && p.alpha < 0.02;
  }, { timeout: 15000 });
}

/** Settled probe — holds until two probes ≥400ms apart agree (布局冻结). */
async function settledProbe(page) {
  await expect.poll(() => page.evaluate(async () => {
    const a = window.__wikiGraphProbe();
    await new Promise((r) => setTimeout(r, 400));
    const b = window.__wikiGraphProbe();
    const frozen = (p) => JSON.stringify(p.nodes.map((n) => [n.id, Math.round(n.x), Math.round(n.y)]));
    return a.alpha < 0.02 && b.alpha < 0.02 &&
      a.scale === b.scale && a.vx === b.vx && a.vy === b.vy && frozen(a) === frozen(b);
  }), { timeout: 15000 }).toBe(true);
  return page.evaluate(() => window.__wikiGraphProbe());
}

/** Bring a node to the canvas center (real shift+wheel pan — CAD 契约保底平移路). */
async function centerNodeOn(page, nodeId) {
  await page.evaluate((id) => {
    const p = window.__wikiGraphProbe();
    const c = document.querySelector('.wiki-canvas');
    const r = c.getBoundingClientRect();
    const n = p.nodes.find((m) => m.id === id);
    const sx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
    const sy = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
    c.dispatchEvent(new WheelEvent('wheel', {
      deltaX: p.W / 2 - sx, deltaY: p.H / 2 - sy, shiftKey: true,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
      bubbles: true, cancelable: true,
    }));
  }, nodeId);
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  const off = await page.evaluate((id) => {
    const p = window.__wikiGraphProbe();
    const n = p.nodes.find((m) => m.id === id);
    return Math.max(Math.abs((n.x - p.W / 2) * p.scale + p.vx), Math.abs((n.y - p.H / 2) * p.scale + p.vy));
  }, nodeId);
  if (off > 2) throw new Error(`centerNodeOn(${nodeId}) missed: ${off}px off-center`);
}

/** 世界坐标 → canvas 屏幕坐标（draw 变换正变换）。 */
const toScreen = (page, wx, wy) => page.evaluate(({ wx: x, wy: y }) => {
  const p = window.__wikiGraphProbe();
  return { x: (x - p.W / 2) * p.scale + p.W / 2 + p.vx, y: (y - p.H / 2) * p.scale + p.H / 2 + p.vy };
}, { wx, wy });

/** 画布标签带墨迹计数：世界点 (n.x, n.y+r+13) 基线 ±9px × ±44px 窗内 alpha>60 像素数。
 *  （solo 节点零边 = 窗内只有标签文字；dpr 缩放换算。） */
const labelBandInk = (page, nodeId) => page.evaluate((id) => {
  const p = window.__wikiGraphProbe();
  const c = document.querySelector('.wiki-canvas');
  const dpr = window.devicePixelRatio || 1;
  const n = p.nodes.find((m) => m.id === id);
  if (!n) return -1;
  const r = 5; // degree 0 → radiusOf = 5
  const bx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
  const by = (n.y + r + 13 - p.H / 2) * p.scale + p.H / 2 + p.vy;
  const x0 = Math.max(0, Math.floor((bx - 44) * dpr));
  const y0 = Math.max(0, Math.floor((by - 9) * dpr));
  const w = Math.min(c.width - x0, Math.floor(88 * dpr));
  const h = Math.min(c.height - y0, Math.floor(18 * dpr));
  if (w <= 0 || h <= 0) return -1;
  const img = c.getContext('2d').getImageData(x0, y0, w, h).data;
  let ink = 0;
  for (let i = 3; i < img.length; i += 4) if (img[i] > 60) ink++;
  return ink;
}, nodeId);

const tipFace = (page) => page.evaluate(() => {
  const tip = document.querySelector('.wiki-graph-pane .wg-tip');
  if (!tip || tip.style.display !== 'block') return null;
  return {
    tt: tip.querySelector('.tt').textContent,
    ts: tip.querySelector('.ts').textContent,
    td: tip.querySelector('.td').textContent,
    para: tip.classList.contains('para'),
  };
});

test.describe('wiki graph hover pill — 文件名重复显示修复（作者报障 2026-10-25）', () => {
  test('T1 悬停节点：pill 命名 + 画布标签带墨迹清零 + 移开恢复（同屏同名消除）', async ({ page }) => {
    const errors = await boot(page);
    await openGraph(page);
    await centerNodeOn(page, 'alpha:docs/solo.md');
    const p = await settledProbe(page);
    const node = p.nodes.find((n) => n.id === 'alpha:docs/solo.md');
    const scr = await toScreen(page, node.x, node.y);
    const box = await page.locator('.wiki-canvas').boundingBox();
    const px = box.x + scr.x, py = box.y + scr.y;

    const inkBefore = await labelBandInk(page, 'alpha:docs/solo.md');
    expect(inkBefore, '静息态标签在场（hubs 档全标注）').toBeGreaterThan(30);

    await page.mouse.move(px, py, { steps: 6 });
    await expect.poll(() => page.evaluate(() => {
      const tip = document.querySelector('.wiki-graph-pane .wg-tip');
      return tip && tip.style.display === 'block';
    })).toBe(true);
    await page.waitForFunction(() => {
      const pr = window.__wikiGraphProbe();
      return pr && pr.tipFor === 'alpha:docs/solo.md';
    }, { timeout: 5000 });
    const face = await tipFace(page);
    expect(face.tt, 'pill 标题 = 节点名').toBe('Solo Doc');
    await page.waitForFunction(() => {
      // 悬停态重绘落地后采样（raf 静止）
      return new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    });
    const inkDuring = await labelBandInk(page, 'alpha:docs/solo.md');
    expect(inkDuring, '悬停中画布标签让位（pill 已命名 —— 重复来源消除）').toBeLessThan(Math.max(6, inkBefore * 0.2));

    // 移开 → pill 收 + 标签墨迹恢复（非掩盖：随 pill 出没）
    await page.mouse.move(box.x + 40, box.y + 40, { steps: 6 });
    await expect.poll(() => page.evaluate(() => {
      const tip = document.querySelector('.wiki-graph-pane .wg-tip');
      return !tip || tip.style.display !== 'block';
    })).toBe(true);
    await page.waitForFunction(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const inkAfter = await labelBandInk(page, 'alpha:docs/solo.md');
    expect(inkAfter, '移开后标签恢复').toBeGreaterThan(30);
    expect(errors, errors.join(' | ')).toEqual([]);
  });

  test('T2 普通引用边悬停：.tt 文档名 + .td 零复读（只补度数）', async ({ page }) => {
    const errors = await boot(page);
    await openGraph(page);
    const p = await settledProbe(page);
    const a = p.nodes.find((n) => n.id === 'alpha:docs/a.md');
    const c = p.nodes.find((n) => n.id === 'alpha:docs/c.md');
    const mid = await toScreen(page, (a.x + c.x) / 2, (a.y + c.y) / 2);
    const box = await page.locator('.wiki-canvas').boundingBox();
    await page.mouse.move(box.x + mid.x, box.y + mid.y, { steps: 6 });
    await expect.poll(async () => {
      const f = await tipFace(page);
      return f && f.tt === 'Charlie Three';
    }).toBe(true);
    const face = await tipFace(page);
    expect(face.tt, 'pill 命名目标文档').toBe('Charlie Three');
    expect(face.td, '.td 零复读文档名（修复点：旧行为 title · cited by …）').not.toContain('Charlie Three');
    expect(face.td, '.td 仍补度数信息').toMatch(/cited by|cites/);
    expect(face.para, '非段落态').toBe(false);
    const pr = await page.evaluate(() => window.__wikiGraphProbe());
    expect(pr.tipFor, 'pill 点名目标节点（画布标签让位判据）').toBe('alpha:docs/c.md');
    expect(errors, errors.join(' | ')).toEqual([]);
  });

  test('T3 段落快照边（para.title 在）：.tt = § 段标题 + .td 保留文档名交代归属 + 跳转提示', async ({ page }) => {
    const errors = await boot(page);
    await openGraph(page);
    const p = await settledProbe(page);
    const a = p.nodes.find((n) => n.id === 'alpha:docs/a.md');
    const b = p.nodes.find((n) => n.id === 'alpha:docs/b.md');
    const mid = await toScreen(page, (a.x + b.x) / 2, (a.y + b.y) / 2);
    const box = await page.locator('.wiki-canvas').boundingBox();
    await page.mouse.move(box.x + mid.x, box.y + mid.y, { steps: 6 });
    await expect.poll(async () => {
      const f = await tipFace(page);
      return !!f && f.para;
    }).toBe(true);
    const face = await tipFace(page);
    expect(face.tt, '§ 段标题').toBe('§ Setup Steps');
    expect(face.td, '.td 保留文档名（.tt 是段标题 —— 非重复，交代段落归属）').toContain('Bravo Two');
    expect(face.td, '跳转提示保留').toContain('Click to jump');
    expect(errors, errors.join(' | ')).toEqual([]);
  });

  test('T4 锚未命中段落索引（回退态）：.tt 文档名 + .td 零复读 + 跳转提示保留（§P3 契约）', async ({ page }) => {
    const errors = await boot(page);
    await openGraph(page);
    const p = await settledProbe(page);
    const a = p.nodes.find((n) => n.id === 'alpha:docs/a.md');
    const d = p.nodes.find((n) => n.id === 'alpha:docs/d.md');
    const mid = await toScreen(page, (a.x + d.x) / 2, (a.y + d.y) / 2);
    const box = await page.locator('.wiki-canvas').boundingBox();
    await page.mouse.move(box.x + mid.x, box.y + mid.y, { steps: 6 });
    await expect.poll(async () => {
      const f = await tipFace(page);
      return !!f && f.tt === 'Delta Four';
    }).toBe(true);
    const face = await tipFace(page);
    expect(face.tt, '回退态 pill = 文档名').toBe('Delta Four');
    expect(face.para, '回退态无 .para').toBe(false);
    expect(face.td, '.td 零复读文档名').not.toContain('Delta Four');
    expect(face.td, '§P3 契约：跳转提示仍在').toContain('Click to jump');
    expect(errors, errors.join(' | ')).toEqual([]);
  });
});

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { await new Promise((r) => server.close(r)); });
