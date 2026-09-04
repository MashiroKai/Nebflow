// flowmap-camera.spec.mjs — Flow Map 固定视口 + CAD 式鼠标跟随缩放 + 三级渐进验收。
//
// 规格依据：v1 交互壳规格（20260903_flowmap-graphview-design.md §3/§8）交互骨架 +
// 作者 2026-09-04 四条裁定：①滚轮缩放以鼠标点为原点（缩放前后鼠标下内容屏幕位置
// 不变 ±1px，固定中心缩放已否决）②L0 全图→hover 局部强调→点击再放大进详情
// ③紧凑布局（层级纵向、间距压缩）④节点卡片文字不出界。
//
// Run: node node_modules/@playwright/test/cli.js test tests/flowmap-camera.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOTS = '/tmp/nb-flowmap-cad-zoom/shots';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'fm-root-session';
const T0 = Date.now();
const V_SPACING = 150; // 紧凑布局裁定后的层间距（flowMapTab.js 常量）

const N1 = {
  id: 'n1', name: '研究', agent: 'researcher', status: 'completed',
  in: [], out: 'n2', hasWorktree: false, worktree: null,
  result: '选题确定', retries: 0, createdAt: T0, completedAt: T0, ttlLeftSec: 300,
};
const N2 = {
  id: 'n2', name: '写作', agent: 'writer', status: 'running',
  in: ['n1'], out: 'Nebula', hasWorktree: false, worktree: null,
  result: null, retries: 0, createdAt: T0, completedAt: null, ttlLeftSec: null,
};
const FM_A = () => ({ nodes: [structuredClone(N1), structuredClone(N2)], worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() } });
const nodeJson = (over) => ({
  hasWorktree: false, worktree: null, result: null, retries: 0,
  createdAt: T0, completedAt: null, ttlLeftSec: null, ...over,
});

async function bootApp(page, serverFm) {
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
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  // 服务端权威快照随事件同步演化（对账拉取必须看到事件后的状态，否则会把
  // 增量应用过的节点回滚掉——realtime T1 同款桩模式）
  if (serverFm) {
    await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: structuredClone(serverFm) }));
  }
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function openFlowMapInPlace(page) {
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
}

/** 画布当前 scale（computed transform matrix a 分量）。 */
const canvasScale = (page) => page.evaluate(() => {
  const t = getComputedStyle(document.querySelector('.solar-canvas')).transform;
  if (!t || t === 'none') return 1;
  return new DOMMatrixReadOnly(t).a;
});

/** 视口 rect + 断言全部节点落在视口内（A2 口径，容差 tol px）。 */
async function expectAllNodesInViewport(page, tol = 2) {
  const ok = await page.evaluate((tol2) => {
    const vp = document.querySelector('.fm-viewport');
    const vr = vp.getBoundingClientRect();
    const nodes = [...vp.querySelectorAll('.fm-node')];
    return nodes.length > 0 && nodes.every((n) => {
      const r = n.getBoundingClientRect();
      return r.left >= vr.left - tol2 && r.right <= vr.right + tol2
        && r.top >= vr.top - tol2 && r.bottom <= vr.bottom + tol2;
    });
  }, tol);
  expect(ok).toBe(true);
}

test('CAD 锚点缩放：滚轮以鼠标点为原点，鼠标下节点 boundingBox 不变 ±1px；初始 fit 全图可见', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const serverFm = FM_A();
  await bootApp(page, serverFm);
  await openFlowMapInPlace(page);

  // 固定视口：overflow hidden、零滚动条范式、fit 元数据
  const vpInfo = await page.evaluate(() => {
    const vp = document.querySelector('.fm-viewport');
    return {
      overflowX: getComputedStyle(vp).overflowX,
      hasScrollHost: !!document.querySelector('.flowmap-view-body .solar-scroll'),
      fitScale: parseFloat(vp.dataset.fitScale),
      camera: vp.dataset.fmCamera,
    };
  });
  expect(vpInfo.overflowX).toBe('hidden');
  expect(vpInfo.hasScrollHost).toBe(false);
  expect(vpInfo.fitScale).toBeGreaterThan(0);
  expect(vpInfo.camera).toBe('autofit');
  await expectAllNodesInViewport(page, 2);

  // CAD 锚点：以 n1 中心为缩放原点，缩放前后该节点 boundingBox 屏幕位置不变 ±1px
  // （光标取整：CDP 派发 wheel 事件时坐标 floor；round 后锚点距节点中心 ≤0.5px，
  //   不变量下节点中心最大位移 (f−1)×0.5 ≈ 0.73px，±1px 断言成立）
  const n1 = page.locator('.fm-node[data-node-id="n1"]');
  const before = await n1.boundingBox();
  const P = [Math.round(before.x + before.width / 2), Math.round(before.y + before.height / 2)];
  await page.mouse.move(P[0], P[1]);
  await page.waitForTimeout(400); // hover 强调过渡落定（hover scale 对中心不变量无影响）
  await page.screenshot({ path: `${SHOTS}/cad-before-zoom.png` });

  await page.mouse.wheel(0, -600); // 放大 k=e^0.9≈2.46
  await page.waitForTimeout(150);
  const afterIn = await n1.boundingBox();
  expect(Math.abs(afterIn.x + afterIn.width / 2 - (before.x + before.width / 2))).toBeLessThanOrEqual(1);
  expect(Math.abs(afterIn.y + afterIn.height / 2 - (before.y + before.height / 2))).toBeLessThanOrEqual(1);
  // 鼠标下命中的仍是同一节点（A3 口径）
  const hitId = await page.evaluate(([px, py]) => (
    document.elementFromPoint(px, py)?.closest('.fm-node')?.getAttribute('data-node-id') || null
  ), P);
  expect(hitId).toBe('n1');
  expect((await canvasScale(page))).toBeGreaterThan(1.5);
  expect(await page.getAttribute('.fm-viewport', 'data-fm-camera')).toBe('usernav');
  await page.screenshot({ path: `${SHOTS}/cad-after-zoom-in.png` });

  // 反向再验一次（不同 scale 下不变量仍成立）
  await page.mouse.wheel(0, 400);
  await page.waitForTimeout(150);
  const afterOut = await n1.boundingBox();
  expect(Math.abs(afterOut.x + afterOut.width / 2 - (before.x + before.width / 2))).toBeLessThanOrEqual(1);
  expect(Math.abs(afterOut.y + afterOut.height / 2 - (before.y + before.height / 2))).toBeLessThanOrEqual(1);

  // 双击空白回 fit（C5）：全图重新可见、相机回 autoFit
  const vp = await page.locator('.fm-viewport').boundingBox();
  await page.mouse.dblclick(vp.x + 40, vp.y + vp.height - 30);
  await page.waitForTimeout(600);
  await expectAllNodesInViewport(page, 2);
  expect(await page.getAttribute('.fm-viewport', 'data-fm-camera')).toBe('autofit');
  expect(pageErrors).toEqual([]);
});

test('三级渐进：hover 局部强调（邻居提亮/其余淡出）→ 点击居中放大进详情 → Esc/双击还原', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const serverFm = FM_A();
  await bootApp(page, serverFm);
  await openFlowMapInPlace(page);
  // 注入一个孤立 pending 节点：n3 做「非邻居淡出」对照组（同时验证增量路径的邻接重建）
  const N3 = nodeJson({ id: 'n3', name: '评审', agent: 'reviewer', status: 'pending', in: [], out: 'Nebula' });
  serverFm.nodes.push(structuredClone(N3)); // 服务端权威快照同步演化（对账不回滚）
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n3', node: structuredClone(N3) });
  await expect(page.locator('.fm-node[data-node-id="n3"]')).toHaveCount(1);
  // 等布局重排与对账落定：第 0 层加入 n3 后 n1 横移（top 布局重排会让静态光标下的
  // 元素换位触发 pointerout——真实语义，hover 断言必须在稳定布局上进行）
  await page.waitForTimeout(1100);

  // 增量渲染哨兵：后续 hover/click 相机操作不得整页重建（探针存活）
  await page.evaluate(() => {
    document.querySelector('.fm-node[data-node-id="n1"]').dataset.probe = 'keep';
  });

  // L1 hover：自身 1.12 + 邻居 n2 提亮 + 非邻居 n3 淡出 ≤0.2 + 邻接边高亮
  const n1 = page.locator('.fm-node[data-node-id="n1"]');
  const b1 = await n1.boundingBox();
  await page.mouse.move(b1.x + b1.width / 2, b1.y + b1.height / 2);
  await expect(n1).toHaveClass(/fm-hi/);
  await expect(page.locator('.fm-node[data-node-id="n2"]')).toHaveClass(/fm-nb/);
  await page.waitForTimeout(450); // 过渡落定
  const hoverState = await page.evaluate(() => {
    const cs = (sel) => {
      const el = document.querySelector(sel);
      return { cls: el.className, opacity: parseFloat(getComputedStyle(el).opacity) };
    };
    const self = document.querySelector('.fm-node[data-node-id="n1"]');
    return {
      selfScale: new DOMMatrixReadOnly(getComputedStyle(self).transform).a,
      n2: cs('.fm-node[data-node-id="n2"]'),
      n3: cs('.fm-node[data-node-id="n3"]'),
      edgeHi: document.querySelector('path[data-edge-id="n1=>n2"]').classList.contains('fm-edge-hi'),
    };
  });
  expect(hoverState.selfScale).toBeGreaterThanOrEqual(1.1);
  expect(hoverState.n2.cls).toContain('fm-nb');
  expect(hoverState.n3.cls).toContain('fm-dim');
  expect(hoverState.n3.opacity).toBeLessThanOrEqual(0.2);
  expect(hoverState.edgeHi).toBe(true);
  await page.screenshot({ path: `${SHOTS}/hover-focus.png` });

  // 移出（80ms 宽限后）还原 L0
  const vpr = await page.locator('.fm-viewport').boundingBox();
  await page.mouse.move(vpr.x + 30, vpr.y + vpr.height - 20);
  await page.waitForTimeout(300);
  expect(await page.locator('.fm-hi, .fm-nb, .fm-dim').count()).toBe(0);

  // L2 click：相机居中放大（≥1.6）+ 详情面板；无遮罩（铁律）
  await page.click('.fm-node[data-node-id="n2"]');
  await page.waitForTimeout(600); // 280ms 相机动画 + 余量
  await expect(page.locator('.flowmap-view-body .fm-detail.open')).toHaveCount(1);
  const l2 = await page.evaluate(() => {
    const vp = document.querySelector('.fm-viewport').getBoundingClientRect();
    const n = document.querySelector('.fm-node[data-node-id="n2"]').getBoundingClientRect();
    const t = getComputedStyle(document.querySelector('.solar-canvas')).transform;
    return {
      scale: new DOMMatrixReadOnly(t).a,
      distToCenter: Math.hypot(n.left + n.width / 2 - (vp.left + vp.width / 2), n.top + n.height / 2 - (vp.top + vp.height / 2)),
      overlay: !!document.querySelector('.flow-viewer-overlay'),
    };
  });
  expect(l2.scale).toBeGreaterThanOrEqual(1.59);
  expect(l2.distToCenter).toBeLessThanOrEqual(60);
  expect(l2.overlay).toBe(false);
  await page.screenshot({ path: `${SHOTS}/click-detail-zoom.png` });

  // Esc 关详情（面板语义）→ 双击空白回 fit（C5）
  await page.keyboard.press('Escape');
  await expect(page.locator('.flowmap-view-body .fm-detail.open')).toHaveCount(0);
  await page.mouse.dblclick(vpr.x + 40, vpr.y + vpr.height - 30);
  await page.waitForTimeout(600);
  await expectAllNodesInViewport(page, 2);
  expect(await page.getAttribute('.fm-viewport', 'data-fm-camera')).toBe('autofit');

  // 全程未整页重建（相机/hover/详情链路增量存活）
  expect(await page.getAttribute('.fm-node[data-node-id="n1"]', 'data-probe')).toBe('keep');
  expect(pageErrors).toEqual([]);
});

test('紧凑布局与文字截断：层级纵向 V_SPACING=150、竖直贝塞尔、长文本省略不撑破卡片', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const serverFm = FM_A();
  await bootApp(page, serverFm);
  await openFlowMapInPlace(page);
  await page.evaluate(() => {
    document.querySelector('.fm-node[data-node-id="n1"]').dataset.probe = 'keep';
  });

  // 层级纵向：布局锚定卡片 top（top = pos.y − NODE_H/2 + PAD），top 间距 = V_SPACING × scale
  const geo = await page.evaluate(() => {
    const r = (id) => document.querySelector(`.fm-node[data-node-id="${id}"]`).getBoundingClientRect();
    const a = r('n1'), b = r('n2');
    const t = getComputedStyle(document.querySelector('.solar-canvas')).transform;
    const s = new DOMMatrixReadOnly(t).a;
    const d = document.querySelector('path[data-edge-id="n1=>n2"]').getAttribute('d');
    const nums = d.match(/-?[\d.]+/g).map(Number);
    return { dx: Math.abs((a.x + a.width / 2) - (b.x + b.width / 2)), gap: (b.top - a.top) / s, edgeXs: [nums[0], nums[2], nums[4], nums[6]] };
  });
  expect(geo.dx).toBeLessThanOrEqual(1);
  expect(Math.abs(geo.gap - V_SPACING)).toBeLessThanOrEqual(1.5);
  // 竖直贝塞尔：起点/两控制点/终点 x 同列（±0.5）
  expect(Math.abs(geo.edgeXs[0] - geo.edgeXs[1])).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geo.edgeXs[2] - geo.edgeXs[3])).toBeLessThanOrEqual(0.5);

  // 裁定④：长节点名/长 result 不撑破卡片（ellipsis 截断，卡宽 124 不变）
  const longName = '超长节点名称用来验证省略号截断行为'.repeat(6);
  const longResult = '这是一段非常长的运行结果内容用于验证摘要条省略。'.repeat(10);
  const N4 = nodeJson({ id: 'n4', name: longName, agent: 'coder', status: 'pending', in: [], out: 'Nebula', result: longResult });
  serverFm.nodes.push(structuredClone(N4)); // 服务端权威快照同步演化
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n4', node: structuredClone(N4) });
  await expect(page.locator('.fm-node[data-node-id="n4"]')).toHaveCount(1);
  await page.waitForTimeout(450);
  const overflow = await page.evaluate(() => {
    const card = document.querySelector('.fm-node[data-node-id="n4"]');
    const label = card.querySelector('.solar-node-label');
    const sub = card.querySelector('.solar-node-sub');
    const res = card.querySelector('.fm-result-summary');
    return {
      cardW: card.offsetWidth,
      // 「不撑破卡片」口径：溢出内容被卡截住（scrollWidth 不超出卡盒），文字行内部
      // 由 ellipsis 截断（scrollWidth 含被截内容是 ellipsis 的正常表现，不作卡内断言）
      cardClips: card.scrollWidth <= card.clientWidth + 1,
      labelClipped: getComputedStyle(label).textOverflow === 'ellipsis' && getComputedStyle(label).overflow === 'hidden',
      subClipped: getComputedStyle(sub).textOverflow === 'ellipsis',
      resClipped: getComputedStyle(res).textOverflow === 'ellipsis',
    };
  });
  expect(overflow.cardW).toBe(124);
  expect(overflow.cardClips).toBe(true);
  expect(overflow.labelClipped).toBe(true);
  expect(overflow.subClipped).toBe(true);
  expect(overflow.resClipped).toBe(true);
  await page.screenshot({ path: `${SHOTS}/compact-overflow.png` });

  // WS 增量哨兵：注入 n4 前后 n1 探针存活（增量 diff 未整页重建）
  expect(await page.getAttribute('.fm-node[data-node-id="n1"]', 'data-probe')).toBe('keep');
  expect(pageErrors).toEqual([]);
});
