// flowmap-ui-fix.spec.mjs — Flow Map 两处修复验收（作者 2026-09-05 12:29 报告）。
//
//   ① 连线 hover 变淡看不清：
//      主因 — applyHover(null) 还原路径只清节点类、提前 return，非邻接边永久卡
//      .fm-edge-dim（opacity .06）「特别淡」；次因 — 连线本体无 hover 入口
//      （边层 pointer-events:none + 委托只认 .fm-node）。
//      修复后语义：连线 hover = 被 hover 边 + 共端点邻接边 .fm-edge-hi（加粗提亮）
//      + 两端节点 .fm-nb，其余保持 L0 原样，全程零淡化；节点 hover N1 语义零回归，
//      退场还原完整（边类同清）。
//   ② 标题栏「返回」与「项目名」合并为单元素「← 项目名」：
//      箭头 + 项目名（esc 转义、长名 ellipsis + title 全名），整元素点击回列表；
//      独立「返回项目列表」文本入口移除（键保留用于 aria-label）。
//
// Run: node "/Users/dev/Claude code/Nebflow/node_modules/@playwright/test/cli.js" test tests/flowmap-ui-fix.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'fm-ui-fix-session';
const T0 = Date.now();
const LONG_NAME = 'L'.repeat(120); // 长项目名（纯字母，attr 选择器安全）

// 三节点链 n1→n2→n3（仅 out 边：n1=>n2 delivered 实线、n2=>n3 inflight 虚线）
const N = (id, name, agent, status, out) => ({
  id, name, agent, status, in: [], out, hasWorktree: false, worktree: null,
  result: status === 'completed' ? 'done' : null, retries: 0,
  createdAt: T0, completedAt: status === 'completed' ? T0 : null, ttlLeftSec: null,
});
const FM_ALPHA = () => ({
  nodes: [N('n1', '研究', 'researcher', 'completed', 'n2'),
          N('n2', '写作', 'writer', 'completed', 'n3'),
          N('n3', '评审', 'reviewer', 'running', null)],
  worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() },
});
const FM_LONG = () => ({
  nodes: [N('m1', '研究', 'researcher', 'running', null)],
  worktrees: [], meta: { project: LONG_NAME, updatedAt: Date.now() },
});

async function bootApp(page) {
  // 几何确定性：reduce 下应用按 §6.4 直达（相机动画跳变、transition:none），
  // 画布几何在渲染完成即为终态——边命中点取坐标不与相机动画竞争
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
    }));
    ws.onMessage(() => { sendConfig(); sendSessions(); });
    sendConfig();
    sendSessions();
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 打开某项目的 Flow Map 就地视图（projects 标签页同页切换）。 */
async function openFlowMap(page, project) {
  await page.click('#projects-btn');
  await page.waitForSelector(`.project-card[data-project="${project}"]`, { timeout: 8000 });
  await page.click(`.project-card[data-project="${project}"] [data-open-flowmap="${project}"]`);
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
  // 鼠标先离开内容区（点击残留位置可能叠在新布局的节点/边上，Chromium 会在
  // DOM 变化后对驻留光标补发边界事件）；200ms > 80ms grace，hover 态归零
  await page.mouse.move(2, 2, { steps: 1 });
  await page.waitForTimeout(200);
}

/** 求边路径上一个真实可命中（elementFromPoint 命中该 path）的屏幕坐标。
 *  虚线边 dasharray 有缝隙，扫描多个长度比例取首个命中点。 */
async function hittablePointOnEdge(page, edgeId) {
  return page.evaluate((id) => {
    const p = document.querySelector(`path[data-edge-id="${id}"]`);
    if (!p) return null;
    const L = p.getTotalLength();
    const m = p.getScreenCTM();
    if (!m) return null;
    for (const f of [0.5, 0.35, 0.65, 0.25, 0.75, 0.15, 0.85, 0.45, 0.55]) {
      const pt = p.getPointAtLength(L * f);
      const x = m.a * pt.x + m.c * pt.y + m.e;
      const y = m.b * pt.x + m.d * pt.y + m.f;
      if (document.elementFromPoint(x, y) === p) return { x, y };
    }
    return null;
  }, edgeId);
}

/** 移到画布空白处（远离节点/边的视口角落）。 */
async function moveToEmptyArea(page) {
  const spot = await page.evaluate(() => {
    const vp = document.querySelector('.fm-viewport');
    const r = vp.getBoundingClientRect();
    return { x: r.right - 8, y: r.bottom - 8 };
  });
  await page.mouse.move(spot.x, spot.y, { steps: 3 });
}

test.beforeEach(() => {});

test('①连线 hover：被 hover 边+邻接边加亮、零淡化；移开完整还原（stale-dim 回归）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_ALPHA() }));
  await openFlowMap(page, 'alpha');

  // 边入口存在性：路径已开启 pointer events（命中前提）
  const pe = await page.evaluate(() =>
    getComputedStyle(document.querySelector('path[data-edge-id="n1=>n2"]')).pointerEvents);
  expect(pe).not.toBe('none');

  // 等相机 fit 动画落定（~400ms 过渡）再取命中点：动画中途的 getScreenCTM 坐标
  // 会过期，鼠标落点漂到节点上会误走节点语义
  await page.waitForTimeout(600);
  const pt = await hittablePointOnEdge(page, 'n1=>n2');
  expect(pt).toBeTruthy();
  await page.mouse.move(pt.x, pt.y, { steps: 1 });
  await page.waitForTimeout(500); // 挂类同步完成 + stroke-width 0.35s 过渡收敛

  // 被 hover 边 + 邻接边（n2=>n3 共端点 n2）→ .fm-edge-hi；箭头同亮
  const hiIds = await page.evaluate(() =>
    [...document.querySelectorAll('path.fm-edge-hi')].map((p) => p.getAttribute('data-edge-id')).sort());
  expect(hiIds).toEqual(['n1=>n2', 'n2=>n3']);
  expect(await page.locator('.fm-edge-arrow.fm-edge-hi').count()).toBe(2);

  // 零淡化：edge hover 全程不落 .fm-edge-dim / 节点 .fm-dim
  expect(await page.locator('.fm-edge-dim').count()).toBe(0);
  expect(await page.locator('.fm-node.fm-dim').count()).toBe(0);

  // 可读性：hover 边加粗至 2.5 + 不透明；两端节点轻强调（.fm-nb），第三节点不动
  const sw = await page.evaluate(() =>
    parseFloat(getComputedStyle(document.querySelector('path[data-edge-id="n1=>n2"]')).strokeWidth));
  expect(sw).toBeCloseTo(2.5, 1);
  const nbIds = await page.evaluate(() =>
    [...document.querySelectorAll('.fm-node.fm-nb')].map((n) => n.getAttribute('data-node-id')).sort());
  expect(nbIds).toEqual(['n1', 'n2']);
  expect(await page.locator('.fm-node.fm-hi').count()).toBe(0);

  // 移开 → 80ms 宽限后完整还原（无任何强调/淡化残留）
  await moveToEmptyArea(page);
  await page.waitForFunction(() =>
    document.querySelectorAll('.fm-hi, .fm-nb, .fm-dim, .fm-edge-hi, .fm-edge-dim').length === 0,
  null, { timeout: 3000 });
  expect(pageErrors).toEqual([]);
});

test('①节点 hover 邻域语义零回归：邻接 hi/其余 dim，退场还原边类同清（主因回归）', async ({ page }) => {
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_ALPHA() }));
  await openFlowMap(page, 'alpha');

  await page.hover('.fm-node[data-node-id="n1"]');
  await page.waitForTimeout(120);
  expect(await page.locator('.fm-node[data-node-id="n1"].fm-hi').count()).toBe(1);
  expect(await page.locator('.fm-node[data-node-id="n2"].fm-nb').count()).toBe(1);
  expect(await page.locator('.fm-node[data-node-id="n3"].fm-dim').count()).toBe(1);
  // 边按端点：n1=>n2 hi；n2=>n3 dim（N1 原语义保留）
  expect(await page.locator('path[data-edge-id="n1=>n2"].fm-edge-hi').count()).toBe(1);
  expect(await page.locator('path[data-edge-id="n2=>n3"].fm-edge-dim').count()).toBe(1);

  // 退场还原：边类必须同清（原实现提前 return 导致 .fm-edge-dim 永久残留 = 主因）
  await moveToEmptyArea(page);
  await page.waitForFunction(() =>
    document.querySelectorAll('.fm-hi, .fm-nb, .fm-dim, .fm-edge-hi, .fm-edge-dim').length === 0,
  null, { timeout: 3000 });
});

test('②标题栏单元素「← 项目名」：箭头+名字、无独立返回文本、title=全名、点击返回', async ({ page }) => {
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_ALPHA() }));
  await openFlowMap(page, 'alpha');

  const info = await page.evaluate(() => {
    const bar = document.querySelector('.flowmap-nav-bar');
    const btn = bar?.querySelector('[data-back-to-projects]');
    if (!bar || !btn) return null;
    return {
      btnCount: bar.querySelectorAll('[data-back-to-projects]').length,
      hasSvg: !!btn.querySelector('svg'),
      name: btn.querySelector('.flowmap-back-name')?.textContent,
      title: btn.getAttribute('title'),
      aria: btn.getAttribute('aria-label'),
      barText: bar.textContent,
    };
  });
  expect(info).toBeTruthy();
  expect(info.btnCount).toBe(1); // 单元素
  expect(info.hasSvg).toBe(true); // 左箭头 icon
  expect(info.name).toBe('alpha'); // 项目名
  expect(info.title).toBe('alpha'); // title 提示全名
  expect(info.aria).toBe('返回项目列表'); // backToProjects 键保留于 aria
  expect(info.barText.includes('返回项目列表')).toBe(false); // 独立文本入口零命中

  // 整元素点击 → 返回项目列表
  await page.click('.flowmap-nav-bar [data-back-to-projects]');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  expect(await page.locator('.flowmap-nav-bar').count()).toBe(0);
});

test('③顶栏合并（2026-09-06）：摘要入 nav-bar 右侧、view-body 无头部条、摘要内容随渲染填入', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM_ALPHA() }));
  await openFlowMap(page, 'alpha');

  // nav-bar 内：返回钮 + 摘要槽；摘要文本非空（FM_ALPHA: 2 completed + 1 running）
  const bar = page.locator('.flowmap-nav-bar');
  const sum = bar.locator('.flowmap-nav-summary');
  await expect(sum).toHaveCount(1);
  const sumInfo = await page.evaluate(() => {
    const el = document.querySelector('.flowmap-nav-bar .flowmap-summary');
    const btn = document.querySelector('.flowmap-back-btn');
    const r = el.getBoundingClientRect();
    const br = btn.getBoundingClientRect();
    return {
      text: el.textContent,
      rightOfBtn: r.left >= br.right - 1,      // 摘要在返回钮右侧（同一行）
      sameRow: Math.abs((r.top + r.bottom) - (br.top + br.bottom)) < 4, // 单行：垂直居中对齐
      align: getComputedStyle(el).textAlign,
      barOverflow: document.querySelector('.flowmap-nav-bar').scrollWidth
        > document.querySelector('.flowmap-nav-bar').clientWidth + 1,
    };
  });
  expect(sumInfo.text.trim().length).toBeGreaterThan(0);
  expect(sumInfo.text).toContain('运行中'); // 1 running
  expect(sumInfo.rightOfBtn).toBe(true);
  expect(sumInfo.sameRow).toBe(true);
  expect(sumInfo.align).toBe('right');
  expect(sumInfo.barOverflow).toBe(false);
  // view-body 内不再有头部条（就地路径）；.flowmap-summary 全文档唯一（nav-bar 槽）
  expect(await page.locator('.flowmap-view-body .flowmap-card-header').count()).toBe(0);
  expect(await page.locator('.flowmap-card-title').count()).toBe(0);
  expect(await page.locator('.flowmap-summary').count()).toBe(1);
  expect(pageErrors).toEqual([]);
});

test('③360px 窄窗：nav-bar 单行不换行不溢出、返回钮与摘要双 ellipsis 均在', async ({ page }) => {
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: LONG_NAME, workspace: '/w/x', agentFile: 'x', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route(new RegExp(`/api/projects/${LONG_NAME}/flow-map`), (r) => r.fulfill({ json: FM_LONG() }));
  await openFlowMap(page, LONG_NAME);
  await page.setViewportSize({ width: 360, height: 667 });
  await page.waitForTimeout(500); // host 收缩落定

  const m = await page.evaluate(() => {
    const bar = document.querySelector('.flowmap-nav-bar');
    const btn = bar?.querySelector('.flowmap-back-btn');
    const sum = bar?.querySelector('.flowmap-nav-summary');
    if (!bar || !btn || !sum) return null;
    return {
      barOverflow: bar.scrollWidth > bar.clientWidth + 1,
      barH: bar.getBoundingClientRect().height,
      btnVisible: btn.getBoundingClientRect().width > 0,
      sumVisible: getComputedStyle(sum).display !== 'none' && sum.getBoundingClientRect().width > 0,
      nameTruncated: (() => { const s = btn.querySelector('.flowmap-back-name'); return s.scrollWidth > s.clientWidth; })(),
      sumEllipsis: getComputedStyle(sum).textOverflow === 'ellipsis',
    };
  });
  expect(m).toBeTruthy();
  expect(m.barOverflow).toBe(false);   // 不撑横
  expect(m.barH).toBeLessThan(60);     // 单行（未换行增高）
  expect(m.btnVisible).toBe(true);     // 返回钮保住
  expect(m.sumVisible).toBe(true);     // 360px > 339 兜底线：摘要仍在
  expect(m.nameTruncated).toBe(true);  // 长名确实在截断
  expect(m.sumEllipsis).toBe(true);
});

test('③极窄（320px < 339 兜底线）：摘要 display:none 让位，返回钮全宽可用', async ({ page }) => {
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: LONG_NAME, workspace: '/w/x', agentFile: 'x', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route(new RegExp(`/api/projects/${LONG_NAME}/flow-map`), (r) => r.fulfill({ json: FM_LONG() }));
  await openFlowMap(page, LONG_NAME);
  await page.setViewportSize({ width: 320, height: 568 });
  await page.waitForTimeout(500);

  const m = await page.evaluate(() => {
    const bar = document.querySelector('.flowmap-nav-bar');
    const btn = bar?.querySelector('.flowmap-back-btn');
    const sum = bar?.querySelector('.flowmap-nav-summary');
    if (!bar || !btn || !sum) return null;
    return {
      sumDisplay: getComputedStyle(sum).display,
      barOverflow: bar.scrollWidth > bar.clientWidth + 1,
      btnVisible: btn.getBoundingClientRect().width > 0,
    };
  });
  expect(m).toBeTruthy();
  expect(m.sumDisplay).toBe('none');   // 极窄兜底：摘要让位
  expect(m.barOverflow).toBe(false);
  expect(m.btnVisible).toBe(true);     // 返回入口保住
});

test('②长项目名：ellipsis 截断 + 不撑横 nav-bar', async ({ page }) => {
  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: LONG_NAME, workspace: '/w/x', agentFile: 'x', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route(new RegExp(`/api/projects/${LONG_NAME}/flow-map`), (r) => r.fulfill({ json: FM_LONG() }));
  await openFlowMap(page, LONG_NAME);

  const m = await page.evaluate(() => {
    const bar = document.querySelector('.flowmap-nav-bar');
    const span = bar?.querySelector('.flowmap-back-name');
    if (!bar || !span) return null;
    const r = span.getBoundingClientRect();
    return {
      name: span.textContent,
      textOverflow: getComputedStyle(span).textOverflow,
      truncated: span.scrollWidth > Math.ceil(r.width),
      barOverflow: bar.scrollWidth > bar.clientWidth + 1,
      btnW: bar.querySelector('[data-back-to-projects]').getBoundingClientRect().width,
    };
  });
  expect(m).toBeTruthy();
  expect(m.name).toBe(LONG_NAME);
  expect(m.textOverflow).toBe('ellipsis');
  expect(m.truncated).toBe(true); // 确实截断（scrollWidth > 可视宽）
  expect(m.barOverflow).toBe(false); // nav-bar 不横向溢出
  expect(m.btnW).toBeLessThanOrEqual(424); // max-width 420 + 边框
});
