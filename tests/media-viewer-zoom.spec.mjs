// media-viewer-zoom.spec.mjs — 媒体查看器缩放/平移（作者令 2026-11-18 逐字：
// 「媒体素材 图片 视频这些 手机端要支持手势放大缩小 电脑端也要支持鼠标滚轮以及
// 触控板手势」）的验收面。
//
// 形态：站内画廊浮层（js/lightbox.js openImageGallery）—— 手机层里它就是铺满整屏的
// 查看页（position:fixed; inset:0），不是弹窗卡片也不是内嵌面板；桌面端同一组件。
//
// 覆盖（真 DOM + Playwright 自建路由 + Chromium CDP 真触屏，不碰任何真实实例端口）：
//   M1 双指捏合放大：档位上升 + **锚点不变式**（两指中点下的内容点保持不动）
//   M2 放大态单指平移：跟手位移、松手钳回硬边界（不无限拖出）
//   M3 放大态边界续滑：已到边界继续拖过阈 ⇒ 切图（左右切换在放大态也保得住）
//   M4 最小档继续缩小 ⇒ **回弹到适应屏幕**（且不关闭；作者可预期手感二选一）
//   M5 既有手势零退让：横滑切图、下滑关闭在适应态逐字保留（含「缩放一轮之后仍保留」）
//   M6 再次打开回到初始态（缩放/位移不残留）
//   M7 视频：画面上的捏合能缩放；**起点落在原生控件带内的捏合不被接管**（播放控件
//      区域的操作不被缩放手势吞掉）
//   M8 手势窗口 rAF 帧率采样（捏合 + 平移过程，≥45fps 近似门；N9 同法）
//   D1 桌面滚轮缩放**以光标为锚点**（偏离中心处放大，光标下的内容点不动）
//   D2 触控板：ctrl+wheel（浏览器合成的捏合）= 缩放；像素步进 + deltaX = 双指平移
//   D3 桌面双击放大/还原（锚 = 双击点）+ Esc 关闭 + ←/→ 切图零退让
//   D4 桌面放大态拖拽平移 + 边界钳制
//
// 与既有 specs 的关系：tests/mobile-image-gallery.spec.mjs（G1–G6 画廊基础面）与
// tests/media-click-unify.spec.mjs（D1–D3/M1–M3 入口分流矩阵）本批一行未改、照跑，
// 它们就是「既有行为未被本批改动」的对照面；本 spec 只加缩放/平移专面。
//
// 证据：EVIDENCE_DIR=<dir> 时按手势帧写 PNG 帧序列（<名字>-NN.png，供 ffmpeg 合成
// GIF/MP4；闸本身零 ffmpeg 依赖）。
// Run: node node_modules/@playwright/test/cli.js test tests/media-viewer-zoom.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || '';
const SID = 'mvz-session';
/** 真夹具图（SVG 源，宽度写进 width/height ⇒ naturalWidth 确定，contain 档可算）。 */
const TALL = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="480" height="760"><rect width="480" height="760" fill="#2b6f8f"/><g fill="#ffd166"><circle cx="240" cy="380" r="90"/><rect x="120" y="600" width="240" height="60"/></g><text x="40" y="70" fill="#fff" font-size="34">TALL 480x760</text></svg>');
const WIDE = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#175b80"/><circle cx="700" cy="180" r="110" fill="#ffd166"/><text x="50" y="80" fill="#fff" font-size="42">WIDE 960x540</text></svg>');
const SMALL = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400"><rect width="300" height="400" fill="#7a5fa8"/><circle cx="150" cy="180" r="60" fill="#facb73"/><text x="24" y="60" fill="#fff" font-size="26">SMALL 300x400</text></svg>');

const FIXTURES = { 'tall.png': TALL, 'wide.png': WIDE, 'small.png': SMALL, 'a.png': TALL, 'b.png': WIDE };

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 帧证据（仅 EVIDENCE_DIR 时写盘；写盘失败一律不阻断断言）。 */
function frameWriter(name) {
  if (!EVIDENCE_DIR) return async () => {};
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  let n = 0;
  return async (page) => {
    n += 1;
    try { await page.screenshot({ path: join(EVIDENCE_DIR, `${name}-${String(n).padStart(3, '0')}.png`) }); }
    catch { /* 证据写盘失败不阻断 */ }
  };
}

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__nfOpenItems = [];
    window.addEventListener('workspace-open-item', (e) => {
      const d = e && e.detail ? e.detail : null;
      window.__nfOpenItems.push(d ? { id: d.id, absPath: d.absPath, itemType: d.itemType, title: d.title } : null);
    });
    window.__nfOpenCalls = [];
    const origOpen = window.open.bind(window);
    window.open = (...args) => { window.__nfOpenCalls.push(args.map(String)); return origOpen(...args); };
  });
  await page.context().route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/nf-ticket') {
        let body = '{}';
        try {
          const post = route.request().postDataBuffer();
          if (post) {
            const j = JSON.parse(post.toString('utf8'));
            const tickets = {};
            for (const k of Object.keys(j && j.paths ? j.paths : {})) tickets[k] = { t: 'e2e-ticket', exp: Date.now() + 1800000, size: 4096 };
            body = JSON.stringify({ tickets });
          }
        } catch { /* fall through */ }
        return route.fulfill({ status: 200, contentType: 'application/json', body });
      }
      if (p === '/api/nf-file') {
        const name = (url.searchParams.get('path') || '').split('/').pop() || '';
        const bytes = FIXTURES[name];
        if (bytes) return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: bytes });
        // 视频/音频字节：夹具不给真体（控件与手势面不依赖可解码内容）。
        if (/\.(mp4|webm|mov|ogv)$/.test(name)) return route.fulfill({ status: 200, contentType: 'video/mp4', body: Buffer.alloc(0) });
        if (/\.(mp3|wav|ogg)$/.test(name)) return route.fulfill({ status: 200, contentType: 'audio/mpeg', body: Buffer.alloc(0) });
        return route.fulfill({ status: 404, body: 'not found' });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, { timeout: 15000 });
}

/** 生产构建器渲染一行媒体卡（live 发送 / 历史恢复同一构建器），再整面点开。 */
async function openViaPopCard(page, atts) {
  const list = Array.isArray(atts) ? atts : [atts];
  await page.evaluate(async (a) => {
    const { buildUserMediaRow } = await import('/js/popArtifacts.js');
    const row = buildUserMediaRow(a, { ts: Date.now() });
    if (!row) return;
    row.dataset.testid = 'pop-artifact-row';
    document.getElementById('chat')?.appendChild(row);
  }, list);
  await page.evaluate(() => {
    const row = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"]');
    const face = row.querySelector('.pop-stack-view') || row.querySelector('.pop-media-flat');
    face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
  await page.waitForFunction(() => {
    const lb = document.querySelector('.nf-lightbox');
    return !!lb && lb.classList.contains('on') && !!lb.querySelector('.nf-lightbox-img.loaded');
  }, null, { timeout: 8000 });
  // 入场档（.loaded 的 200ms transform 过渡）走完再量几何：否则 base 拍读到的 rect
  // 还停在 scale(0.96)⇒scale(1) 之间，锚点不变式会被这一拍误差污染。
  await page.waitForTimeout(320);
}

/** 画廊态几何/读数单点（各断言面共用；rect 是**变换后**的盒 ⇒ 锚点不变式可直接
 *  用「同一个归一化内容点」在两拍之间比对）。 */
const VIEW = () => {
  const lb = document.querySelector('.nf-lightbox');
  const el = lb.querySelector('.nf-lightbox-img') || lb.querySelector('.nf-lightbox-av');
  const r = el.getBoundingClientRect();
  const lr = lb.getBoundingClientRect();
  return {
    on: lb.classList.contains('on'),
    zoom: Number(lb.getAttribute('data-nf-lb-zoom')),
    panX: Number(lb.getAttribute('data-nf-lb-pan-x')),
    panY: Number(lb.getAttribute('data-nf-lb-pan-y')),
    index: Number(lb.getAttribute('data-nf-lb-gal-index')),
    total: Number(lb.getAttribute('data-nf-lb-gal-total')),
    rect: { x: r.left, y: r.top, w: r.width, h: r.height },
    layout: { w: el.offsetWidth, h: el.offsetHeight },
    center: { x: lr.left + lr.width / 2, y: lr.top + lr.height / 2 },
    hasVideo: !!lb.querySelector('video'),
    videoPaused: !!(lb.querySelector('video') && lb.querySelector('video').paused),
  };
};

/* ── Chromium CDP 真触屏（pointerType=touch；仿真，非真机） ───────────── */
async function touchClient(page) {
  return page.context().newCDPSession(page);
}
async function pinch(client, cx, cy, dist0, dist1, steps = 12, onStep = null) {
  const at = (d) => [{ x: cx - d / 2, y: cy, id: 1 }, { x: cx + d / 2, y: cy, id: 2 }];
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: at(dist0) });
  for (let i = 1; i <= steps; i += 1) {
    await client.send('Input.dispatchTouchEvent', {
      type: 'touchMove', touchPoints: at(dist0 + ((dist1 - dist0) * i) / steps),
    });
    if (onStep) await onStep(i);
    await sleep(12);
  }
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await sleep(260);   // 让 CSS 回弹过渡跑完
}
async function dragTouch(client, x0, y0, x1, y1, steps = 10, onStep = null) {
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x0, y: y0, id: 7 }] });
  for (let i = 1; i <= steps; i += 1) {
    await client.send('Input.dispatchTouchEvent', {
      type: 'touchMove', touchPoints: [{ x: x0 + ((x1 - x0) * i) / steps, y: y0 + ((y1 - y0) * i) / steps, id: 7 }],
    });
    if (onStep) await onStep(i);
    await sleep(14);
  }
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await sleep(260);
}

/** 锚点不变式读数：pivot 下的**归一化内容点**在缩放前后是否仍落在 pivot 上。 */
function anchorError(before, after, pivot) {
  const u = (pivot.x - before.rect.x) / before.rect.w;
  const v = (pivot.y - before.rect.y) / before.rect.h;
  const px = after.rect.x + u * after.rect.w;
  const py = after.rect.y + v * after.rect.h;
  return Math.hypot(px - pivot.x, py - pivot.y);
}

/* ══════════════════ 手机腿（375×812，真触屏仿真） ══════════════════ */

test.describe('mobile media viewer zoom (full-screen gallery)', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test('M1: pinch zooms, anchored at the finger midpoint', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('mobile-pinch');
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, { type: 'image', name: 'tall.png', path: 'docs/tall.png' });

    const client = await touchClient(page);
    const base = await page.evaluate(VIEW);
    // 两指中点刻意偏离视口中心：只有锚点成立时缩放后该内容点才不动（居中缩放会被逮住）。
    const pivot = { x: base.center.x - 86, y: base.center.y - 40 };
    await pinch(client, pivot.x, pivot.y, 120, 240, 10, async () => shot(page));
    const after = await page.evaluate(VIEW);
    await shot(page);

    // 档位上升（120→240 指距 ≈ ×2，钳制 [1,5]）。
    expect(after.zoom).toBeGreaterThan(1.9);
    expect(after.zoom).toBeLessThan(2.1);
    // 锚点不变式：两指中点下的内容点漂移 ≤ 3px。
    expect(anchorError(base, after, pivot)).toBeLessThan(3);
    // 平移确实发生了（不是中心缩放）：pan 读数非零且与中点偏移同号。
    expect(after.panX).toBeGreaterThan(60);
    expect(after.panY).toBeGreaterThan(20);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M2: single-finger pan tracks and stops at the hard bound', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('mobile-pan');
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, { type: 'image', name: 'tall.png', path: 'docs/tall.png' });

    const client = await touchClient(page);
    const base = await page.evaluate(VIEW);
    await pinch(client, base.center.x, base.center.y, 120, 264, 8);   // ≈×2.2，两轴都可平移
    const zoomed = await page.evaluate(VIEW);
    expect(zoomed.zoom).toBeGreaterThan(2);
    // 期望硬边界：±(内容屏幕边长 − 视口边长)/2（与 mediaZoom.axisBounds 同式）。
    const vp = await page.evaluate(() => {
      const r = document.querySelector('.nf-lightbox').getBoundingClientRect();
      return { w: r.width, h: r.height };
    });
    const slackX = (zoomed.layout.w * zoomed.zoom - vp.w) / 2;
    const slackY = (zoomed.layout.h * zoomed.zoom - vp.h) / 2;
    expect(slackX).toBeGreaterThan(10);
    expect(slackY).toBeGreaterThan(10);

    // 跟手：拖 (+120,+120) 期间 pan 读数随指推进。
    await dragTouch(client,
      base.center.x, base.center.y, base.center.x + 120, base.center.y + 120, 8,
      async () => shot(page));
    const mid = await page.evaluate(VIEW);
    expect(mid.panX).toBeGreaterThan(zoomed.panX + 60);
    // 大幅越界拖（+400,+400）→ 松手钳回硬边界，绝不驻留界外。
    await dragTouch(client, base.center.x, base.center.y, base.center.x + 400, base.center.y + 400, 8);
    const bounded = await page.evaluate(VIEW);
    await shot(page);
    expect(Math.abs(bounded.panX)).toBeLessThanOrEqual(slackX + 1);
    expect(Math.abs(bounded.panY)).toBeLessThanOrEqual(slackY + 1);
    expect(Math.abs(bounded.panX)).toBeGreaterThan(slackX - 2);   // 真的顶到了边界
    expect(bounded.zoom).toBeGreaterThan(2);                      // 平移不改档位
    expect(bounded.on).toBe(true);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M3: over-pan at the edge continues into the next image', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('mobile-edge-switch');
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, [
      { type: 'image', name: 'tall.png', path: 'docs/tall.png' },
      { type: 'image', name: 'wide.png', path: 'docs/wide.png' },
    ]);
    const client = await touchClient(page);
    const base = await page.evaluate(VIEW);
    expect(base.total).toBe(2);
    await pinch(client, base.center.x, base.center.y, 120, 264, 8);
    expect((await page.evaluate(VIEW)).zoom).toBeGreaterThan(2);
    // 一路拖到左边界再继续拖过阈 ⇒ 切到下一张（放大态也保得住左右切换）。
    await dragTouch(client, base.center.x + 40, base.center.y, base.center.x - 420, base.center.y, 12,
      async () => shot(page));
    const after = await page.evaluate(VIEW);
    await shot(page);
    expect(after.index).toBe(1);
    expect(after.zoom).toBe(1);        // 换图 = 回到初始态
    expect(after.on).toBe(true);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M4: pinching below the minimum springs back to fit — it does not close', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('mobile-shrink-back');
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, { type: 'image', name: 'tall.png', path: 'docs/tall.png' });
    const client = await touchClient(page);
    const base = await page.evaluate(VIEW);
    // 先放大到 ×2.2，再一路捏到远低于最小档（240 → 40）：阻尼后松手回弹。
    await pinch(client, base.center.x, base.center.y, 120, 264, 8);
    expect((await page.evaluate(VIEW)).zoom).toBeGreaterThan(2);
    await pinch(client, base.center.x, base.center.y, 240, 40, 12, async () => shot(page));
    const after = await page.evaluate(VIEW);
    await shot(page);
    expect(after.on).toBe(true);          // 关键：回弹，不是关闭
    expect(after.zoom).toBe(1);           // 回到适应屏幕
    expect(after.panX).toBe(0);
    expect(after.panY).toBe(0);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M5: the shipped fit-state gestures survive a zoom round-trip', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, [
      { type: 'image', name: 'tall.png', path: 'docs/tall.png' },
      { type: 'image', name: 'wide.png', path: 'docs/wide.png' },
      { type: 'image', name: 'small.png', path: 'docs/small.png' },
    ]);
    const client = await touchClient(page);
    const base = await page.evaluate(VIEW);
    // 先走一轮缩放（放大 → 缩回），确认两套手势互不吞。
    await pinch(client, base.center.x, base.center.y, 120, 264, 8);
    expect((await page.evaluate(VIEW)).zoom).toBeGreaterThan(2);
    await pinch(client, base.center.x, base.center.y, 240, 110, 8);
    expect((await page.evaluate(VIEW)).zoom).toBe(1);
    // ① 适应态横滑 = 切下一张（既有手势，逐字保留）。
    await dragTouch(client, base.center.x + 40, base.center.y, base.center.x - 160, base.center.y, 8);
    expect((await page.evaluate(VIEW)).index).toBe(1);
    // ② 适应态下滑 = 关闭（既有手势，逐字保留）。
    await dragTouch(client, base.center.x, base.center.y - 60, base.center.x, base.center.y + 180, 10);
    const after = await page.evaluate(VIEW);
    expect(after.on).toBe(false);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M6: reopening the same image starts from the initial view', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, { type: 'image', name: 'tall.png', path: 'docs/tall.png' });
    const client = await touchClient(page);
    let base = await page.evaluate(VIEW);
    await pinch(client, base.center.x - 60, base.center.y, 120, 240, 10);
    await dragTouch(client, base.center.x, base.center.y, base.center.x + 90, base.center.y + 90, 8);
    const zoomed = await page.evaluate(VIEW);
    expect(zoomed.zoom).toBeGreaterThan(1.9);
    expect(Math.abs(zoomed.panX) + Math.abs(zoomed.panY)).toBeGreaterThan(10);
    // 关闭（Esc 等价：CSS 面 closeLightbox 同一出口）后重新点开同一张。
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.nf-lightbox.on'), null, { timeout: 5000 });
    await page.evaluate(() => {
      document.querySelectorAll('.row.pop-artifact[data-testid="pop-artifact-row"]').forEach((n) => n.remove());
    });
    await openViaPopCard(page, { type: 'image', name: 'tall.png', path: 'docs/tall.png' });
    base = await page.evaluate(VIEW);
    expect(base.zoom).toBe(1);
    expect(base.panX).toBe(0);
    expect(base.panY).toBe(0);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M7: video zooms on its picture, and the native control band is never swallowed', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('mobile-video');
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await page.evaluate(async () => {
      const { openImageGallery } = await import('/js/lightbox.js');
      openImageGallery([{ kind: 'video', name: 'clip.mp4', src: '/api/nf-file?path=docs/clip.mp4' }], 0);
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return !!lb && lb.classList.contains('on') && !!lb.querySelector('video');
    }, null, { timeout: 8000 });
    await page.waitForTimeout(300);
    const client = await touchClient(page);
    const v = await page.evaluate(() => {
      const el = document.querySelector('.nf-lightbox video');
      const r = el.getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height, bottom: r.bottom };
    });
    // ① 原生控件带（元素盒下沿 56px）内的捏合：缩放管线必须让位（zoom 恒 1）。
    const bandY = v.bottom - 20;
    await pinch(client, v.x + v.w / 2, bandY, 120, 260, 8);
    const afterBand = await page.evaluate(VIEW);
    expect(afterBand.zoom).toBe(1);
    // ② 画面上的捏合：同一套缩放能力（视频也走缩放）。
    const bodyY = v.y + Math.min(40, v.h * 0.25);
    await pinch(client, v.x + v.w / 2, bodyY, 120, 260, 10, async () => shot(page));
    const afterBody = await page.evaluate(VIEW);
    await shot(page);
    expect(afterBody.zoom).toBeGreaterThan(1.8);
    expect(afterBody.hasVideo).toBe(true);
    // ③ 放大态下视频面上单指拖动 = 平移（画面与图片同规），且不触发切图/关闭。
    await dragTouch(client, v.x + v.w / 2 + 40, bodyY, v.x + v.w / 2 - 40, bodyY, 8);
    const afterVideoPan = await page.evaluate(VIEW);
    expect(afterVideoPan.zoom).toBeGreaterThan(1.8);   // 平移不改档位
    expect(afterVideoPan.panX).not.toBe(afterBody.panX);
    expect(afterVideoPan.on).toBe(true);
    // ④ 缩回适应档后，视频面上的单指下滑**不关闭**（关闭仍走遮罩/返回钮/Esc）。
    await pinch(client, v.x + v.w / 2, bodyY, 260, 60, 10);
    expect((await page.evaluate(VIEW)).zoom).toBe(1);
    await dragTouch(client, v.x + v.w / 2, bodyY, v.x + v.w / 2, bodyY + 220, 10);
    const afterFitDrag = await page.evaluate(VIEW);
    expect(afterFitDrag.on).toBe(true);
    expect(afterFitDrag.zoom).toBe(1);
    expect(errors).toEqual([]);
    await client.detach().catch(() => {});
  });

  test('M8: gesture window keeps painting every frame (rAF sample)', async ({ page }) => {
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openViaPopCard(page, { type: 'image', name: 'tall.png', path: 'docs/tall.png' });
    const client = await touchClient(page);
    const base = await page.evaluate(VIEW);
    await page.evaluate(() => {
      window.__rafFrames = 0;
      const loop = () => { window.__rafFrames += 1; requestAnimationFrame(loop); };
      requestAnimationFrame(loop);
    });
    const t0 = Date.now();
    await pinch(client, base.center.x, base.center.y, 120, 260, 24);
    await dragTouch(client, base.center.x + 60, base.center.y, base.center.x - 60, base.center.y + 60, 24);
    const el = Date.now() - t0;
    const frames = await page.evaluate(() => window.__rafFrames);
    const fps = Math.round((frames / el) * 1000);
    // 近似门（与 mobile-image-gesture 的 N9 同法）：headless 采样，不是真机帧率声明。
    expect(fps).toBeGreaterThanOrEqual(45);
    await client.detach().catch(() => {});
  });
});

/* ══════════════════ 桌面腿（1280×900） ══════════════════ */

test.describe('desktop media viewer zoom (wheel / trackpad / dblclick)', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ viewport: { width: 1280, height: 900 } });

  test('D1: mouse wheel zooms anchored at the cursor', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('desktop-wheel');
    await bootApp(page);
    // 300×400 的图在 1280×900 里按自身尺寸渲染（max-* 只是封顶）⇒ 两轴都在边界内，
    // 锚点读数不会被边界钳制污染。
    await openViaPopCard(page, { type: 'image', name: 'small.png', path: 'docs/small.png' });
    const before = await page.evaluate(VIEW);
    expect(before.layout.w).toBe(300);
    expect(before.layout.h).toBe(400);
    // 光标刻意偏离中心（图内左上区域）：中心缩放会把内容点甩开，锚点缩放不会。
    const pivot = { x: before.rect.x + 80, y: before.rect.y + 90 };
    await page.mouse.move(pivot.x, pivot.y);
    await page.mouse.wheel(0, -120);
    await page.waitForTimeout(120);
    const after = await page.evaluate(VIEW);
    await shot(page);
    expect(after.zoom).toBe(1.2);                          // 既有档位逐字保留
    expect(Math.hypot(after.rect.w - before.rect.w * 1.2, after.rect.h - before.rect.h * 1.2)).toBeLessThan(1);
    expect(anchorError(before, after, pivot)).toBeLessThan(2);
    // 连续滚轮继续推档，并被 5× 封顶吃掉。
    for (let i = 0; i < 14; i += 1) { await page.mouse.wheel(0, -120); await shot(page); }
    await page.waitForTimeout(120);
    const capped = await page.evaluate(VIEW);
    expect(capped.zoom).toBe(5);
    expect(errors).toEqual([]);
  });

  test('D2: trackpad pinch (ctrl+wheel) zooms; two-finger pan pans', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    const shot = frameWriter('desktop-trackpad');
    await bootApp(page);
    await openViaPopCard(page, { type: 'image', name: 'small.png', path: 'docs/small.png' });
    const before = await page.evaluate(VIEW);
    const pivot = { x: before.rect.x + 90, y: before.rect.y + 120 };
    // ① 触控板捏合 = 浏览器合成的 wheel{ctrlKey:true}（真实事件形态，逐字派发）。
    for (const d of [-4, -6, -9, -12, -16, -22]) {
      await page.evaluate(({ x, y, delta }) => {
        const lb = document.querySelector('.nf-lightbox');
        const el = lb.querySelector('.nf-lightbox-img');
        el.dispatchEvent(new WheelEvent('wheel', { deltaY: delta, deltaMode: 0, ctrlKey: true, cancelable: true, bubbles: true, clientX: x, clientY: y }));
      }, { x: pivot.x, y: pivot.y, delta: d });
      await shot(page);
    }
    const pinched = await page.evaluate(VIEW);
    await shot(page);
    expect(pinched.zoom).toBeGreaterThan(1.1);
    expect(pinched.zoom).toBeLessThan(1.6);
    expect(anchorError(before, pinched, pivot)).toBeLessThan(3);
    // ② 双指平移：像素步进 + 横向分量（鼠标滚轮不产生 deltaX）⇒ 平移，档位不变。
    const beforePan = await page.evaluate(VIEW);
    for (let i = 0; i < 5; i += 1) await page.mouse.wheel(24, -18);
    await page.waitForTimeout(120);
    const panned = await page.evaluate(VIEW);
    expect(panned.zoom).toBe(beforePan.zoom);                        // 档位不动
    expect(panned.panX).toBeLessThan(beforePan.panX - 10);           // 内容反向跟手
    expect(panned.panY).toBeGreaterThan(beforePan.panY + 5);
    expect(errors).toEqual([]);
  });

  test('D3: dblclick zooms at the click point; Esc and arrows stay intact', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await openViaPopCard(page, [
      { type: 'image', name: 'small.png', path: 'docs/small.png' },
      { type: 'image', name: 'wide.png', path: 'docs/wide.png' },
    ]);
    const lb = page.locator('.nf-lightbox.on');
    await expect(lb).toHaveAttribute('data-nf-lb-gal-total', '2');
    const before = await page.evaluate(VIEW);
    const pivot = { x: before.rect.x + 70, y: before.rect.y + 60 };
    await page.mouse.move(pivot.x, pivot.y);
    await page.mouse.dblclick(pivot.x, pivot.y);
    await page.waitForTimeout(120);
    const after = await page.evaluate(VIEW);
    expect(after.zoom).toBe(2);
    expect(anchorError(before, after, pivot)).toBeLessThan(2);
    // 还原：双击回适应档并居中。两次 dblclick 之间留出 >600ms —— 否则连续四次点击
    // 落在 browser 的双击判定窗里，事件按 click-detail 合并，断言面会数错次数。
    await page.waitForTimeout(700);
    await page.mouse.move(pivot.x, pivot.y);
    await page.mouse.dblclick(pivot.x, pivot.y);
    await page.waitForTimeout(160);
    const restored = await page.evaluate(VIEW);
    expect(restored.zoom).toBe(1);
    expect(restored.panX).toBe(0);
    expect(restored.panY).toBe(0);
    // 既有键盘面零退让。
    await page.keyboard.press('ArrowRight');
    await expect(lb).toHaveAttribute('data-nf-lb-gal-index', '1');
    await page.keyboard.press('ArrowLeft');
    await expect(lb).toHaveAttribute('data-nf-lb-gal-index', '0');
    await page.keyboard.press('Escape');
    await expect(page.locator('.nf-lightbox.on')).toHaveCount(0);
    expect(errors).toEqual([]);
  });

  test('D4: desktop drag pans when zoomed, clamped at the bound', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    // wide.png（960×540）在 1280×900 里按自身尺寸渲染（960 < 92% 宽上限，封顶不生效）；
    // ×2.07 之后两轴都超出视口 ⇒ 两轴都能验到硬边界钳制。
    await openViaPopCard(page, { type: 'image', name: 'wide.png', path: 'docs/wide.png' });
    const before = await page.evaluate(VIEW);
    expect(before.layout.w).toBe(960);
    expect(before.layout.h).toBe(540);
    await page.mouse.move(before.center.x, before.center.y);
    for (let i = 0; i < 4; i += 1) await page.mouse.wheel(0, -120);
    await page.waitForTimeout(120);
    const zoomed = await page.evaluate(VIEW);
    expect(zoomed.zoom).toBeCloseTo(2.0736, 3);
    const vp = await page.evaluate(() => {
      const r = document.querySelector('.nf-lightbox').getBoundingClientRect();
      return { w: r.width, h: r.height };
    });
    const slackX = (zoomed.layout.w * zoomed.zoom - vp.w) / 2;
    const slackY = (zoomed.layout.h * zoomed.zoom - vp.h) / 2;
    expect(slackX).toBeGreaterThan(20);
    expect(slackY).toBeGreaterThan(20);
    // 大幅拖出 → 硬边界钳制（「不能无限拖出」在桌面腿同样成立）。
    await page.mouse.move(before.center.x, before.center.y);
    await page.mouse.down();
    for (let i = 1; i <= 8; i += 1) await page.mouse.move(before.center.x + (900 * i) / 8, before.center.y + (900 * i) / 8);
    await page.mouse.up();
    await page.waitForTimeout(120);
    const panned = await page.evaluate(VIEW);
    expect(panned.panX).toBeLessThanOrEqual(slackX + 1);
    expect(panned.panY).toBeLessThanOrEqual(slackY + 1);
    expect(panned.panX).toBeGreaterThan(slackX - 2);
    expect(panned.panY).toBeGreaterThan(slackY - 2);
    expect(panned.on).toBe(true);
    expect(errors).toEqual([]);
  });
});
