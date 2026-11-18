// media-backdrop-close.spec.mjs — 交互回归闸：**拖动之后，点背景必关闭**。
//
// 来源（2026-11-18 媒体查看器缩放批 #7 引入的间歇红，本批定性并修）：
//   · 失败子项 tests/mobile-image-gallery.spec.mjs:472 › desktop gallery interactions，
//     失败断言 :506 `expect(locator('.nf-lightbox.on')).toHaveCount(0)` 收 1
//     —— 平移过图片、关掉、重开之后，第一次点背景关不掉。
//   · 归属实测（同一命令）：不含 #7 的树 8/8 全绿；含 #7 的树 4/8；
//     交错对照 ctl6-* 绿 / ctl7-2 红 ⇒ 与提交相关，不是机器负载。
//   · 根因（本批读源码 + 页内探针实测）：原 `ensureDom` 的遮罩点按闸是**时间窗**
//     `Date.now() - galDragEndAt < 160`，而 galDragEndAt 是模块级时刻、关灯箱/重开
//     都不清零 ⇒ 上一轮拖动收尾的时刻漏到下一轮。页内读数 delta=158ms（< 160）⇒
//     那一枚真点背景被当成上一轮拖动的余波吞掉。同一份源码快机跑红、慢机跑绿。
//   · 修法：闸改成**一次性旗标**（拖动收尾置位、紧随其后的那一枚合成 click 消费），
//     与墙上时钟无关；关灯箱（clearGalleryState）与每次新手势起手都复位。
//
// 本闸为什么是确定性的：把「关 → 重开 → 点背景」放进**同一个 JS 任务**（一次
// page.evaluate），使「上一轮拖动收尾 → 这次点背景」的时间差压到一次 CDP 往返
// （毫秒级）。旧实现下必然落在 160ms 窗内 ⇒ 必然红；修好后与时间无关 ⇒ 必然绿。
// 归因证据目录：/Users/kaiyu/stand-integrate-evidence-20261118/flake-media-gallery/
//
// 两种形态都跑：桌面 1280×900（真鼠标滚轮/拖动）+ 手机层 375×812 全屏页（真触屏
// 仿真 CDP：捏合 + 单指平移）。手机腿进的是作者真实看到的整屏页，不是内嵌面板。
//
// Run: node node_modules/@playwright/test/cli.js test tests/media-backdrop-close.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 'mbc-session';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const WIDE = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#175b80"/><circle cx="700" cy="180" r="110" fill="#ffd166"/><text x="50" y="80" fill="#fff" font-size="42">WIDE 960x540</text></svg>');
const TALL = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="480" height="760"><rect width="480" height="760" fill="#864ba1"/><circle cx="240" cy="320" r="150" fill="#facb73"/><text x="40" y="80" fill="#fff" font-size="38">TALL 480x760</text></svg>');
const FIXTURES = { 'a.png': WIDE, 'b.png': TALL };

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__nfOpenItems = [];
    window.addEventListener('workspace-open-item', (e) => window.__nfOpenItems.push(e && e.detail ? e.detail : null));
    window.__nfOpenCalls = [];
    const origOpen = window.open.bind(window);
    window.open = (...args) => { window.__nfOpenCalls.push(args.map(String)); return origOpen(...args); };
    // 探针自证读数：浮层上最后一次 pointerup 的时刻（= 拖动松手）。
    window.__nfPointerUpAt = 0;
    document.addEventListener('pointerup', (e) => {
      const t = e.target;
      if (t instanceof Element && t.closest && t.closest('.nf-lightbox')) window.__nfPointerUpAt = performance.now();
    }, true);
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

/** 生产构建器渲染一行两张图的叠卡组（整组进画廊），再整面点开。 */
async function openStack(page) {
  await page.evaluate(async () => {
    const { buildUserMediaRow } = await import('/js/popArtifacts.js');
    const row = buildUserMediaRow([
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
    ], { ts: Date.now() });
    row.dataset.testid = 'pop-artifact-row';
    document.getElementById('chat')?.appendChild(row);
  });
  await page.evaluate(() => {
    document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view')
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
  await page.waitForFunction(() => {
    const lb = document.querySelector('.nf-lightbox');
    return !!lb && lb.classList.contains('on') && !!lb.querySelector('.nf-lightbox-img.loaded');
  }, null, { timeout: 8000 });
  // 量几何/点背景之前，先等**元素自身**走完 `.loaded` 的 200ms 进场过渡（opacity 1
  // 且 transform 已到恒等态）—— 不赌固定毫秒，与 tests/media-viewer-zoom.spec.mjs 同口径。
  await page.waitForFunction(() => {
    const lb = document.querySelector('.nf-lightbox');
    if (!lb || !lb.classList.contains('on')) return false;
    const el = lb.querySelector('.nf-lightbox-img');
    if (!el || !el.classList.contains('loaded')) return false;
    const cs = getComputedStyle(el);
    return cs.opacity === '1' && (cs.transform === 'none' || cs.transform === 'matrix(1, 0, 0, 1, 0, 0)');
  }, null, { timeout: 8000 });
}

const VIEW = () => {
  const lb = document.querySelector('.nf-lightbox');
  const el = lb && (lb.querySelector('.nf-lightbox-img') || lb.querySelector('.nf-lightbox-av'));
  const r = el && el.getBoundingClientRect();
  return {
    on: !!lb && lb.classList.contains('on'),
    zoom: lb && Number(lb.getAttribute('data-nf-lb-zoom')),
    panX: lb && Number(lb.getAttribute('data-nf-lb-pan-x')),
    panY: lb && Number(lb.getAttribute('data-nf-lb-pan-y')),
    index: lb && Number(lb.getAttribute('data-nf-lb-gal-index')),
    rect: r ? { x: r.left, y: r.top, w: r.width, h: r.height } : null,
  };
};

/**
 * 「paint 已追上状态」：元素 computed transform == 浮层已写下的状态
 * （`data-nf-lb-zoom/pan-x/pan-y`）。与 tests/media-viewer-zoom.spec.mjs 同一判据 ——
 * 松手后的回弹/收束走得是 200ms 过渡，等**状态**而不是等固定毫秒。
 */
async function waitPainted(page) {
  await page.waitForFunction(() => {
    const lb = document.querySelector('.nf-lightbox');
    if (!lb || !lb.classList.contains('on')) return false;   // 见 allowClosed 的说明
    const z = Number(lb.getAttribute('data-nf-lb-zoom'));
    const tx = Number(lb.getAttribute('data-nf-lb-pan-x'));
    const ty = Number(lb.getAttribute('data-nf-lb-pan-y'));
    if (!Number.isFinite(z) || !Number.isFinite(tx) || !Number.isFinite(ty)) return false;
    // 视频/音频腿里，ensureDom 建的 `.nf-lightbox-img` **仍在 DOM 但 opacity:0、零盒**
    // （实测），真正在画的是 `.nf-lightbox-av` ⇒ 只在「真的在画」的候选里比不变式，
    // 否则会一直盯着一张不动的隐藏图而误超时。
    const cands = [lb.querySelector('.nf-lightbox-av'), lb.querySelector('.nf-lightbox-img')]
      .filter(Boolean)
      .filter((el) => { const cs = getComputedStyle(el); return cs.opacity === '1' && el.getBoundingClientRect().width > 0; });
    if (!cands.length) return false;
    return cands.some((el) => {
      const m = getComputedStyle(el).transform;
      let a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
      if (m && m !== 'none') { const hit = /^matrix\(([^)]+)\)$/.exec(m); if (!hit) return false; [a, b, c, d, e, f] = hit[1].split(',').map(Number); }
      return Math.abs(a - z) < 1e-3 && Math.abs(d - z) < 1e-3 && Math.abs(b) < 1e-9 && Math.abs(c) < 1e-9
          && Math.abs(e - tx) <= 0.5 && Math.abs(f - ty) <= 0.5;
    });
  }, null, { timeout: 8000 });
}

/**
 * 确定性尾段（本闸的核心）：**同一个 JS 任务**里做完「拖动松手 → 关 → 重开 → 点背景」。
 * 时间差被压到毫秒级 ⇒ 旧实现的 160ms 时间窗必然吞掉那一枚点背景（必然红），
 * 新实现的一次性闸与时间无关（必然绿）。
 * 点背景用真实「落在遮罩自身、坐标 (10,450)」的 click；与失败子项同点位。
 */
async function dragThenCloseReopenBackdrop(page) {
  return page.evaluate(async () => {
    const lbEl = () => document.querySelector('.nf-lightbox');
    const on = () => !!lbEl() && lbEl().classList.contains('on');
    const stillOnAfterDrag = on();                       // 拖动收尾那一枚合成 click 不得关灯箱
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    const closedByEsc = !on();
    document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view')
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    // 重开腿走 `import('./lightbox.js').then(openImageGallery)`：等一个宏任务节拍
    // （实测微任务队列不够 —— 动态 import 在已求值模块上仍过一次任务检查点）。
    const tick = () => new Promise((r) => setTimeout(r, 0));
    for (let i = 0; i < 20 && !on(); i += 1) await tick();
    const reopened = on();
    const zoomAtReopen = Number(lbEl() && lbEl().getAttribute('data-nf-lb-zoom'));
    const hitAt = document.elementFromPoint(10, 450);
    // 本闸的自证读数：从**上一轮拖动的松手**到这一枚点背景的实测毫秒差。
    // 旧实现的闸是 `< 160` 时间窗 ⇒ 该读数 < 160 才是一次有效探针（否则说明机器
    // 慢到探针失效，必须显式报警，不许静默绿）。
    const dragEndToClickMs = Math.round(performance.now() - (window.__nfPointerUpAt || 0));
    // 点之前浮层**确实还开着**：否则 closedByBackdrop 会因为「本来就是关的」而变成
    // 假绿（探针自证的第二半）。
    const onBeforeClick = on();
    lbEl().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: 10, clientY: 450 }));
    const closedByBackdrop = !on();
    return {
      stillOnAfterDrag, closedByEsc, reopened, onBeforeClick, closedByBackdrop, zoomAtReopen,
      dragEndToClickMs,
      hitAt: hitAt ? (hitAt.tagName + '|' + String(hitAt.className)) : null,
      hitIsOverlay: hitAt === lbEl(),
    };
  });
}

/* ══════════════════ 桌面腿（1280×900，真鼠标） ══════════════════ */

test.describe('backdrop close after a drag — desktop', () => {
  test.use({ viewport: { width: 1280, height: 900 } });

  test('D: pan release keeps it open; the next backdrop click always closes', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await openStack(page);
    const lb = page.locator('.nf-lightbox.on');

    // 先真放大（双击 = 2×），否则桌面鼠标腿不开平移会话。
    await page.locator('.nf-lightbox-img').dispatchEvent('dblclick');
    await expect(lb).toHaveAttribute('data-nf-lb-zoom', '2');
    // 真鼠标平移一段。
    await page.mouse.move(640, 450);
    await page.mouse.down();
    await page.mouse.move(700, 490, { steps: 5 });
    await page.mouse.up();
    const v = await page.evaluate(VIEW);
    expect(v.zoom).toBe(2);
    expect(v.panX).toBe(60);
    expect(v.panY).toBe(40);

    const tail = await dragThenCloseReopenBackdrop(page);
    expect(tail.stillOnAfterDrag).toBe(true);    // 拖动收尾不误关
    expect(tail.closedByEsc).toBe(true);
    expect(tail.reopened).toBe(true);
    expect(tail.onBeforeClick).toBe(true);       // 点背景前浮层确实还开着（探针有效）
    expect(tail.zoomAtReopen).toBe(1);           // 重开回到初始档（不残留上一轮缩放）
    expect(tail.hitIsOverlay).toBe(true);        // 落点就是遮罩自身，不是被图片盖住
    // 探针有效性：实测「拖动松手 → 点背景」落在旧实现的 160ms 窗内（否则这次
    // 探针没打到那个竞态，必须显式报警而不是静默绿）。
    expect(tail.dragEndToClickMs).toBeLessThan(160);
    expect(tail.closedByBackdrop).toBe(true);    // ← 本批修的回归点
    expect(errors).toEqual([]);
  });

  test('D2: without any drag a backdrop click closes (control)', async ({ page }) => {
    await bootApp(page);
    await openStack(page);
    const lb = page.locator('.nf-lightbox.on');
    await lb.click({ position: { x: 10, y: 450 } });
    await expect(lb).toHaveCount(0);
  });
});

/* ══════════════ 手机腿（375×812 全屏页，真触屏仿真） ══════════════ */

test.describe('backdrop close after a drag — mobile full-screen page', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test('M: pinch+pan release keeps it open; the next backdrop tap always closes', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openStack(page);
    const lb = page.locator('.nf-lightbox.on');
    const client = await page.context().newCDPSession(page);

    // 真触屏捏合放大（CDP，pointerType=touch）。
    const cx = 187, cy = 406;
    const at = (d) => [{ x: cx - d / 2, y: cy, id: 1 }, { x: cx + d / 2, y: cy, id: 2 }];
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: at(120) });
    for (let i = 1; i <= 10; i += 1) {
      await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: at(120 + (140 * i) / 10) });
      await sleep(12);
    }
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await waitPainted(page);                 // 等回弹过渡落定（paint == 状态），不等固定毫秒
    expect((await page.evaluate(VIEW)).zoom).toBeGreaterThan(1);

    // 真触屏单指平移（放大态 = 平移会话）。
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 180, y: 400, id: 7 }] });
    for (let i = 1; i <= 8; i += 1) {
      await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 180 + (60 * i) / 8, y: 400 + (40 * i) / 8, id: 7 }] });
      await sleep(12);
    }
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    // 不 sleep：立刻接确定性尾段（本闸的关键——把时间差压到毫秒级）。
    const tail = await dragThenCloseReopenBackdrop(page);
    expect(tail.stillOnAfterDrag).toBe(true);
    expect(tail.closedByEsc).toBe(true);
    expect(tail.reopened).toBe(true);
    expect(tail.onBeforeClick).toBe(true);       // 点背景前浮层确实还开着（探针有效）
    expect(tail.hitIsOverlay).toBe(true);       // 点背景的落点确实是遮罩自身（不是被图片盖住）
    expect(tail.dragEndToClickMs).toBeLessThan(160);
    expect(tail.closedByBackdrop).toBe(true);    // ← 本批修的回归点
    expect(errors).toEqual([]);
  });

  test('M2: without any drag a backdrop tap closes (control)', async ({ page }) => {
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await openStack(page);
    const lb = page.locator('.nf-lightbox.on');
    await lb.click({ position: { x: 10, y: 450 } });
    await expect(lb).toHaveCount(0);
  });
});
