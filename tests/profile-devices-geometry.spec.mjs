// profile-devices-geometry.spec.mjs — 个人资料面「设备清单」的**几何 / 交互**判据
//（profile-multidevice 修残批，2026-11-18 独立评审 blocker ②）。
//
// 为什么单开本 spec：`tests/profile-multidevice.spec.mjs` 证明的是**文本与请求
// 口径**（行内容、deviceId 隔离、零可编辑控件），它读的是 `textContent` 和路由
// 足迹 —— 一行被 `overflow: hidden` 裁掉之后，这两条读数**照样全绿**（DOM 在、
// 文本在、按钮也在，只是用户点不到）。本 spec 补的正是那条空白：**布局真值**
//（卡是否被 flex 收缩压扁 / 行是否落在可见框内 / 元素是否真的能命中）与
// **触控真值**（按坐标点下去，得到的是不是那一行自己的动作）。
//
// 修前实锤（390×844，本机 + 5 对端；证据见报告）：
//   · `.mf-hub-rows`（设备卡）overflow:hidden + flex:0 1 auto；卡内容高 300px、
//     可见高 101.3px；
//   · 页体 `scrollHeight == clientHeight == 782`（**没有**滚动条 ⇒ 被裁内容不可滚）；
//   · 6 行中 dev-1…dev-4 的中心 `document.elementFromPoint()` 命中不到自己。
//   根因 = `.mobile-page-body` 同时是「滚动容器 + 列向 flex」，而 CSS 规范规定
//   overflow 非 visible 的 flex 项自动最小尺寸归零（min-height:auto 失效）⇒
//   带 overflow:hidden 的卡被算法压缩，内容被裁且不回落到滚动区。
//
// 判据（全部机械读数，零截图判读）：
//   ① 无「静默裁剪祖先」：任何祖先不得同时满足「overflow-y:hidden」与
//      「scrollHeight > clientHeight + 1」；
//   ② 每一行完整落在清单可见框内（矩形包含）；
//   ③ 内容超高时页体必须真的可滚（scrollHeight > clientHeight）—— 否则高度
//      只能是被裁掉，不是被收纳；
//   ④ 逐行触达：把该行滚到视口中央后，行中心 `elementFromPoint` = 该行或其子孙；
//   ⑤ 触控热区：手机档退出键命中盒（rect ∪ ::before/::after，口径同
//      tests/mobile-touch-target.spec.mjs）≥ 44×44；
//   ⑥ 真按坐标点一下对端行 → 恰好一发该行自己的设备域请求（隔离 + 可达性同时证）。
//   ⑦ **探测器自证**（反空转）：临时把收缩行为加回去，① 必须变红 —— 证明本 spec
//      的判据能抓到该类回归，而不是恒绿的摆设。
//
// 零实例、零生产数据：web 根目录直读磁盘，REST/WS 全桩，页面只 goto
// http://localhost:1/（无端口监听）。
//
// Run: node <repo>/node_modules/@playwright/test/cli.js test tests/profile-devices-geometry.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const SELF = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', displayName: 'Fixture User', email: 'fixture@example.test' };
/** 5 台对端 ⇒ 6 行；内容必然高于 390×844 的一屏（修前正是「被裁且不可滚」的现场）。 */
const PEERS = Array.from({ length: 5 }, (_, i) => ({
  deviceId: `dev-${i}`,
  deviceName: `Peer ${i}`,
  platform: ['windows', 'linux', 'android', 'ios', 'macos'][i % 5],
  lastSeen: Date.now() - 60_000 * (i + 1),
  online: i % 2 === 0,
  directOnline: false,
}));
const MIN_TAP = 44;

const MOBILE_ROOT = '.mobile-page[data-page="me"]';
const DESKTOP_ROOT = '#settings-profile-overlay';

/** 桩化 boot：所有 REST/WS 输入走路由，页面只连 localhost:1（无监听端口）。 */
async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    window.__reqs = [];
  });
  await page.route('**/*', (route) => {
    const req = route.request();
    const url = new URL(req.url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      void page.evaluate((entry) => { window.__reqs.push(entry); }, { method: req.method(), path: p }).catch(() => {});
      if (p === '/api/neblink/status') return route.fulfill({ json: { loggedIn: true, device: SELF, peers: PEERS } });
      if (/^\/api\/neblink\/devices\/[^/]+\/sign-out$/.test(p)) {
        return route.fulfill({ status: 409, json: { ok: false, code: 'remote-sign-out-unsupported', error: 'remote device sign-out is not supported' } });
      }
      return route.fulfill({ json: {} });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    config();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
    ws.onMessage((raw) => { try { if (JSON.parse(raw).type === 'getConfig') config(); } catch { /* 非 JSON 帧 */ } });
  });
  await page.goto('http://localhost:1/');
}

async function openMobileMe(page) {
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="me"]')?.classList.contains('live'), null, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function openDesktopProfile(page) {
  await page.waitForSelector('#settings-btn', { timeout: 20000 });
  await page.locator('#settings-btn').click();
  await page.waitForSelector('#settings-content', { timeout: 15000 });
  await page.evaluate(async () => (await import('/js/settingsIdentity.js')).openProfileFace());
  await page.waitForSelector(`${DESKTOP_ROOT}:not([hidden])`, { timeout: 10000 });
  await page.waitForTimeout(300);
}

/**
 * 布局真值读数（页面内执行）。
 * @returns {{clippers: Array, scroll: object|null, list: object, rows: Array}}
 */
function geometry(sel) {
  const round = (n) => Math.round(n * 10) / 10;
  const rect = (el) => { const b = el.getBoundingClientRect(); return { x: round(b.x), y: round(b.y), w: round(b.width), h: round(b.height), top: round(b.top), bottom: round(b.bottom) }; };
  /** 命中盒 = 元素 rect ∪ ::before/::after 外扩（tests/mobile-touch-target.spec.mjs 口径）。 */
  const hitBox = (el) => {
    const r = el.getBoundingClientRect();
    const box = { l: r.left, t: r.top, r: r.right, b: r.bottom };
    for (const p of ['::before', '::after']) {
      const s = getComputedStyle(el, p);
      if (!s.content || s.content === 'none' || s.display === 'none') continue;
      const w = parseFloat(s.width); const h = parseFloat(s.height);
      if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) continue;
      const num = (v) => (v === 'auto' ? NaN : parseFloat(v));
      let l = r.left + r.width / 2 - w / 2; let t = r.top + r.height / 2 - h / 2;
      const insL = num(s.left); const insR = num(s.right); const insT = num(s.top); const insB = num(s.bottom);
      if (Number.isFinite(insL)) l = r.left + insL; else if (Number.isFinite(insR)) l = r.right - insR - w;
      if (Number.isFinite(insT)) t = r.top + insT; else if (Number.isFinite(insB)) t = r.bottom - insB - h;
      box.l = Math.min(box.l, l); box.t = Math.min(box.t, t); box.r = Math.max(box.r, l + w); box.b = Math.max(box.b, t + h);
    }
    return { w: round(box.r - box.l), h: round(box.b - box.t) };
  };
  const root = document.querySelector(sel);
  const list = root && root.querySelector('[data-device-list="1"]');
  if (!list) return { error: `no [data-device-list="1"] under ${sel}` };
  // ① 静默裁剪祖先：overflow-y:hidden 且内容真的溢出（被裁且不可滚 = 不可达）。
  const clippers = [];
  for (let el = list; el && el !== document.body; el = el.parentElement) {
    const s = getComputedStyle(el);
    if (s.overflowY === 'hidden' && el.scrollHeight > el.clientHeight + 1) {
      clippers.push({ cls: String(el.className).slice(0, 80), scrollH: el.scrollHeight, clientH: el.clientHeight });
    }
  }
  const scrollEl = (() => {
    for (let el = list.parentElement; el && el !== document.body; el = el.parentElement) {
      const s = getComputedStyle(el);
      if (s.overflowY === 'auto' || s.overflowY === 'scroll') return { cls: String(el.className).slice(0, 80), scrollH: el.scrollHeight, clientH: el.clientHeight };
    }
    return null;
  })();
  const lb = rect(list);
  const rows = [...list.querySelectorAll('.pd-device-row')].map((row) => {
    const rb = rect(row);
    const btn = row.querySelector('[data-device-signout]');
    const hit = document.elementFromPoint(rb.x + rb.w / 2, rb.y + rb.h / 2);
    return {
      id: row.dataset.deviceId,
      rect: rb,
      insideListBox: rb.top >= lb.top - 0.5 && rb.bottom <= lb.bottom + 0.5,
      centerHitOwnRow: !!hit && (hit === row || row.contains(hit)),
      button: btn ? {
        id: btn.getAttribute('data-device-signout') || '',
        rect: rect(btn),
        hitBox: hitBox(btn),
        centerHitOwnButton: (() => { const b = btn.getBoundingClientRect(); const t = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2); return !!t && (t === btn || btn.contains(t)); })(),
      } : null,
    };
  });
  return { clippers, scroll: scrollEl, list: { ...lb, scrollH: list.scrollHeight, clientH: list.clientHeight }, rows };
}

/** 逐行触达：把该行滚到视口中央，再看它自己（及其退出键）能不能被命中。 */
async function rowReachability(page, rootSel) {
  const rows = page.locator(`${rootSel} [data-device-list="1"] .pd-device-row`);
  const count = await rows.count();
  const out = [];
  for (let i = 0; i < count; i++) {
    const row = rows.nth(i);
    await row.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(60);
    out.push(await row.evaluate((el) => {
      const r = el.getBoundingClientRect();
      const cx = r.x + r.width / 2; const cy = r.y + r.height / 2;
      const hitRow = document.elementFromPoint(cx, cy);
      const btn = el.querySelector('[data-device-signout]');
      const b = btn.getBoundingClientRect();
      const hitBtn = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
      return {
        id: el.dataset.deviceId,
        inViewport: r.top >= -0.5 && r.bottom <= innerHeight + 0.5,
        rect: { top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height) },
        centerHitOwnRow: !!hitRow && (hitRow === el || el.contains(hitRow)),
        centerHitOwnButton: !!hitBtn && (hitBtn === btn || btn.contains(hitBtn)),
      };
    }));
  }
  return out;
}

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'dark' });

test('G1 mobile: device list is not flex-clipped and the page grows a real scroll range', async ({ page }) => {
  await boot(page);
  await openMobileMe(page);
  const g = await page.evaluate(geometry, MOBILE_ROOT);
  expect(g.error, '设备清单必须渲染').toBeUndefined();
  // ① 零静默裁剪祖先（修前 = .mf-hub-rows scrollH 300 / clientH 101）
  expect(g.clippers, `被裁且不可滚的祖先：${JSON.stringify(g.clippers)}`).toEqual([]);
  // ② 每行完整落在清单可见框内
  expect(g.rows).toHaveLength(6);
  expect(g.rows.filter((r) => !r.insideListBox).map((r) => r.id), '行必须完整落在清单框内').toEqual([]);
  // ③ 内容高于一屏 ⇒ 页体必须真的可滚（否则只能是被裁掉）
  expect(g.scroll, '设备清单必须活在某个滚动容器里').not.toBeNull();
  expect(g.scroll.scrollH, `页体未长滚动区：scrollH=${g.scroll.scrollH} clientH=${g.scroll.clientH}`).toBeGreaterThan(g.scroll.clientH + 1);
  expect(g.list.scrollH, '清单自身不得再裁内容').toBeLessThanOrEqual(g.list.clientH + 1);
});

test('G2 mobile: every device row and its sign-out key is touch-reachable after scrolling', async ({ page }) => {
  await boot(page);
  await openMobileMe(page);
  const reach = await rowReachability(page, MOBILE_ROOT);
  expect(reach).toHaveLength(6);
  for (const r of reach) {
    expect(r.id, `${r.id} 行高必须撑住内容（≥44）`).toBeTruthy();
    expect(r.rect.h, `${r.id} 行高`).toBeGreaterThanOrEqual(MIN_TAP);
    expect(r.centerHitOwnRow, `${r.id} 行中心必须命中该行（修前 dev-1…dev-4 为 false）`).toBe(true);
    expect(r.centerHitOwnButton, `${r.id} 退出键中心必须命中该键`).toBe(true);
  }
  const g = await page.evaluate(geometry, MOBILE_ROOT);
  for (const r of g.rows) {
    expect(r.button, `${r.id} 行必须有退出键`).not.toBeNull();
    expect(r.button.hitBox.h, `${r.id} 退出键命中盒高度 < ${MIN_TAP}（手机触控下限）`).toBeGreaterThanOrEqual(MIN_TAP);
    expect(r.button.hitBox.w, `${r.id} 退出键命中盒宽度 < ${MIN_TAP}`).toBeGreaterThanOrEqual(MIN_TAP);
    expect(r.button.id, '退出键必须带该行自己的 deviceId').toBe(r.id);
  }
});

test('G3 mobile: the clipping detector itself catches a shrink regression (anti-vacuous)', async ({ page }) => {
  await boot(page);
  await openMobileMe(page);
  const clean = await page.evaluate(geometry, MOBILE_ROOT);
  expect(clean.clippers).toEqual([]);
  // 把收缩行为加回去（= 修前状态）⇒ 探测器必须报红，否则本 spec 是恒绿摆设。
  const tag = await page.addStyleTag({
    content: 'body.mobile-layer .mobile-page[data-page="me"] .mf-me-fields { flex: 0 1 auto !important; }',
  });
  const regressed = await page.evaluate(geometry, MOBILE_ROOT);
  expect(regressed.clippers.length, '收缩回归必须被探测到').toBeGreaterThan(0);
  await tag.evaluate((el) => el.remove());
  const restored = await page.evaluate(geometry, MOBILE_ROOT);
  expect(restored.clippers).toEqual([]);
});

test('G4 mobile: tapping a peer row at its own coordinates signs out exactly that device', async ({ page }) => {
  await boot(page);
  await openMobileMe(page);
  const row = page.locator(`${MOBILE_ROOT} .pd-device-row[data-device-id="dev-4"]`);
  await row.evaluate((el) => el.scrollIntoView({ block: 'center' }));
  await page.waitForTimeout(80);
  const box = await row.locator('[data-device-signout]').boundingBox();
  expect(box, '退出键必须可见').not.toBeNull();
  // 真按坐标点（触控语义，不走 locator 的自动滚动/命中重试）。
  await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(300);
  const paths = await page.evaluate(() => window.__reqs.filter((r) => r.path.startsWith('/api/')).map((r) => `${r.method} ${r.path}`));
  // 只认设备域足迹：恰好一发，且路径段 = 该行自己的 deviceId；链上不得出现别的
  // 设备 id，也不得触发账号级 end-session（隔离判据 + 可达性判据同时成立）。
  const deviceCalls = paths.filter((p) => p.includes('/devices/'));
  expect(deviceCalls).toEqual(['POST /api/neblink/devices/dev-4/sign-out']);
  expect(paths.filter((p) => p.includes('/devices/')).join(' ')).not.toMatch(/dev-0|dev-1|dev-2|dev-3|dev-self/);
  expect(paths.filter((p) => p.includes('end-session'))).toEqual([]);
  // 失败禁静默：409 的「无此能力」读数必须在该行可见（不是假装已退出）。
  await expect(page.locator(`${MOBILE_ROOT} .pd-device-notice`)).toContainText('keeps its own credential locally');
});

test('G5 desktop: the profile overlay device list is not clipped and every row is hit-testable', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await boot(page);
  await openDesktopProfile(page);
  const g = await page.evaluate(geometry, DESKTOP_ROOT);
  expect(g.error).toBeUndefined();
  expect(g.clippers).toEqual([]);
  expect(g.rows).toHaveLength(6);
  expect(g.rows.filter((r) => !r.insideListBox).map((r) => r.id)).toEqual([]);
  const reach = await rowReachability(page, DESKTOP_ROOT);
  for (const r of reach) {
    expect(r.centerHitOwnRow, `${r.id} 行中心必须命中该行`).toBe(true);
    expect(r.centerHitOwnButton, `${r.id} 退出键中心必须命中该键`).toBe(true);
  }
});
