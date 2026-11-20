#!/usr/bin/env node
// capture-canvas-seat-draft.mjs — 「静默接管 + 失权端草稿」的**真渲染取证**
// （静默接管批 2026-11-19；作者裁定 2026-11-19 08:05「直接静默吧」，动机 2026-11-18 21:27
//  「我主要是觉得现在通知很烦」）。
//
// 为什么必须是**两个真窗口 + 真席位模型**（而不是往页面里塞一帧假消息）：
//   本批的两条不变量（静默 / 不丢）分别住在两处，只有把两处接起来才看得见——
//     · 静默：新窗口申请 ⇒ 服务端**申请即授予**、被顶下的一方**零用户可见通知**；
//     · 不丢：被顶下的一方在**丢席位的那一刻**把编辑缓冲落成本机草稿，且事后有取回入口。
//   只塞假帧就只能证明「客户端收到帧会落草稿」，证明不了「新窗口一申请，旧窗口静默地不丢」。
//   故本脚本在 Node 侧起一个**与生产语义同构的最小单写者席位**（epoch 单调、claim 即授予、
//   旧 epoch 写穿 409、grant 时向全体连接广播 canvasWriterChanged），两个真浏览器上下文
//   各自走**产品的真 HTTP 腿**去 claim/put；席位易主只由「另一个窗口真的写入」触发。
//
// 装置：零端口、零进程、真 Chromium；page.route 从**磁盘**喂静态资源（`--root` 可把改前检出
// 树整棵喂进来 ⇒ 同脚本跑 before/after），routeWebSocket 打桩、localStorage 打桩身份域。
// 不碰宿主实例 6138、不写库。
//
// 用法：
//   node scripts/capture-canvas-seat-draft.mjs --root <web树> --tag after --out <dir>
//   改前对照：git archive 7488de7d8 src/main/resources/web | tar -x -C /tmp/base \
//             node scripts/capture-canvas-seat-draft.mjs --root /tmp/base/src/main/resources/web --tag before --out <同目录>
//
// 产出（<out> 内）：
//   <tag>-375-<theme>.png / -375-<theme>-chip.png / -768-<theme>.png / -1440-<theme>.png
//   <tag>-readings.json   席位事件 / 通知读数（静默判据）/ 草稿单槽 / 取回入口几何 / 桌面几何
//   <tag>-video/*.webm    （--video，默认开）375 档实况录屏，供裁成 GIF/MP4
// 退出码：0 = 采集完成（不做红绿判定 —— 判定在 scripts/check-canvas-seat-draft.mjs +
//   tests/canvas-writer-client.test.mjs + scripts/tests/canvas-seat-draft.test.mjs）；
//   1 = 装配错误 / 关键腿缺失（此时 readings 里 errors 有原文）。
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, extname, normalize, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const REPO = fileURLToPath(new URL('..', import.meta.url));
const ROOT_ARG = valOf('--root', 'src/main/resources/web');
const WEB = normalize(isAbsolute(ROOT_ARG) ? ROOT_ARG : join(REPO, ROOT_ARG));
const TAG = valOf('--tag', 'after');
const OUT_ARG = valOf('--out', null);
const OUT = OUT_ARG ? normalize(isAbsolute(OUT_ARG) ? OUT_ARG : join(REPO, OUT_ARG))
  : join(REPO, 'evidence/canvas-seat-silent-20261119');
const THEMES = (valOf('--themes', 'dark,light') || '').split(',').filter(Boolean);
const VIDEO = !argv.includes('--no-video');

if (!existsSync(join(WEB, 'index.html'))) {
  console.error(`装配错误：${WEB}/index.html 不存在（--root 指向 web 树）`);
  process.exit(1);
}
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf', '.wasm': 'application/wasm',
};
const SID = 'canvas-seat-session';

const require = createRequire(import.meta.url);
function loadPlaywright() {
  const tried = [];
  for (const c of [process.env.NEBFLOW_PW_PATH, 'playwright', 'playwright-core', '@playwright/test'].filter(Boolean)) {
    try { return require(c); } catch (e) { tried.push(`${c}: ${String(e.message).split('\n')[0]}`); }
  }
  throw new Error('playwright 不可解析：' + tried.join(' | '));
}

const DOCS = [['d1', 'meeting-notes-2026-11-19.md', 'notes'], ['d2', 'design-spec-seat.md', 'spec']];
const DOCS_B = [['b1', 'other-window.md', 'other']];
const MD_BODY = [
  '# 会议纪要 · 2026-11-19',
  '',
  '这一份文档**正在这个窗口里被编辑**：席位是单写者，另一个窗口一申请就接手。',
  '',
  '## 本批要证的两件事',
  '',
  '- 静默：被顶下的一方**不弹任何通知**（作者原话「我主要是觉得现在通知很烦」）；',
  '- 不丢：被顶下的一方在**丢席位的那一刻**把编辑缓冲落成本机草稿，事后有取回入口。',
  '',
  '## 为什么只能这样实现',
  '',
  '未保存的改动不在服务端（还在这个窗口的内存里），被踢的那端还可能已经死了——',
  '所以「静默」与「保存」要同时成立，只剩一条路：失权端自己零通知地把缓冲落草稿。',
  '',
  '## 取回',
  '',
  '草稿只写本机单槽、不写服务端、不覆盖别人正在写的内容；取回时只把缺的页签补开。',
].join('\n');

// ── Node 侧最小单写者席位（与 ConsoleTakeover 语义同构）──────────────────────
const seat = { epoch: 0, writer: null, claims: [], puts: [], broadcasts: [], releases: [] };
const clients = []; // 已连接的 ws 打桩：{ name, send }
const idOf = (b) => ({ clientId: b?.clientId || null, tabInstanceId: b?.tabInstanceId || null });
const sameId = (w, id) => !!w && w.clientId === id.clientId && w.tabInstanceId === id.tabInstanceId;
function broadcast(frame) {
  seat.broadcasts.push(frame);
  for (const c of clients) { try { c.send(JSON.stringify(frame)); } catch { /* 已断 */ } }
}

async function wireContext(context, name, { recordDir = null } = {}) {
  void recordDir;
  await context.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    try { Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true }); } catch { /* ignore */ }
  });
  await context.route('**/*', (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = decodeURIComponent(url.pathname);
    if (p.startsWith('/api/')) {
      const bodyOf = () => { try { return JSON.parse(req.postData() || '{}'); } catch { return {}; } };
      // GET 角色：只有当前写者读到 writer，其余一律读者。
      if (p === '/api/canvas-writer' && req.method() === 'GET') {
        const id = { clientId: url.searchParams.get('clientId'), tabInstanceId: url.searchParams.get('tabInstanceId') };
        const role = sameId(seat.writer, id) ? 'writer' : 'reader';
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(role === 'writer' ? { role, epoch: seat.epoch } : { role }) });
      }
      // claim：**申请即授予**（静默抢占）—— epoch 单调自增，grant 时向全体广播席位变更。
      if (p === '/api/canvas-writer/claim' && req.method() === 'POST') {
        const id = idOf(bodyOf());
        seat.epoch += 1;
        const displaced = sameId(seat.writer, id) ? null : seat.writer;
        seat.writer = id;
        seat.claims.push({ window: name, epoch: seat.epoch, displaced });
        broadcast({ type: 'canvasWriterChanged', epoch: seat.epoch });
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ role: 'writer', epoch: seat.epoch }) });
      }
      if (p === '/api/canvas-writer/release' && req.method() === 'POST') {
        const id = idOf(bodyOf());
        if (sameId(seat.writer, id)) { seat.releases.push({ window: name, epoch: bodyOf().epoch ?? null }); seat.writer = null; }
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
      }
      // PUT：只有「当前写者 + epoch 对齐」才 200，否则 409（旧 epoch 当场失效）。
      if (p === '/api/canvas-tabs' && req.method() === 'PUT') {
        const body = bodyOf();
        const ok = sameId(seat.writer, idOf(body)) && body.epoch === seat.epoch;
        seat.puts.push({ window: name, epoch: body.epoch ?? null, accepted: ok });
        return ok
          ? route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' })
          : route.fulfill({ status: 409, contentType: 'application/json', body: '{"error":"stale_writer_epoch"}' });
      }
      // 其余 /api（含 GET /api/canvas-tabs 的权威存档）：空对象 ⇒ 客户端视为「无权威快照」，
      // 不覆盖本窗口已开的页签（与既有 capture 脚本同一配方）。
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    let file = p === '/' ? '/index.html' : p;
    if (file === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const abs = normalize(join(WEB, file));
    if (!abs.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(abs)] || 'application/octet-stream', body: readFileSync(abs) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await context.routeWebSocket(/\/ws/, (ws) => {
    clients.push({ name, send: (s) => { try { ws.send(s); } catch { /* 已断 */ } } });
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

async function bootPage(browser, { name, width, height, theme, video = null }) {
  const t0 = Date.now();   // 录屏起点（recordVideo 随 context 建立开录）
  const context = await browser.newContext({
    viewport: { width, height },
    hasTouch: width <= 768,
    colorScheme: theme,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'no-preference',
    deviceScaleFactor: 2,
    ...(video ? { recordVideo: { dir: video, size: { width, height } } } : {}),
  });
  await wireContext(context, name, {});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  return { page, context, errors, name, t0 };
}

async function openDocs(page, list) {
  await page.evaluate(async (docs) => {
    const c = await import('/js/canvas.js');
    for (const [id, title, type] of docs) c.openTab(id, title, { type });
  }, list);
  await page.waitForTimeout(320);
}

async function renderBody(page, content) {
  const ok = await page.evaluate(async (md) => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane.active') || document.querySelector('.canvas-tab-pane.active');
    if (!pane) return false;
    const mod = await import('/js/viewers/markdown.js');
    await mod.default.render(pane, { content: md, absPath: '/tmp/seat-notes.md', fileName: 'meeting-notes-2026-11-19.md', anchor: null, readOnly: true });
    return true;
  }, content);
  await page.waitForTimeout(240);
  return ok;
}

/** 打开画布面：手机/平板走 dock（在场才点），桌面走 openCanvas()。 */
async function openCanvasSurface(page, width) {
  await page.evaluate(async () => { (await import('/js/canvas.js')).openCanvas(''); });
  if (width <= 768) {
    const dock = page.locator('#mobile-dock .dock-key[data-dock="canvas"]');
    if (await dock.count()) { try { await dock.first().click({ timeout: 3000 }); } catch { /* 已在画布页 */ } }
  }
  await page.waitForFunction(() => !!document.querySelector('.canvas-tab-pane.active'), null, { timeout: 8000 });
  await page.waitForTimeout(320);
}

const waitFor = async (fn, ms = 10000, step = 60) => {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if (fn()) return true; await new Promise((r) => setTimeout(r, step)); }
  return false;
};

/** 页面侧读数：取回入口几何 + 令牌 + 通知面 + 草稿槽。 */
async function probeDraft(page) {
  return page.evaluate(async () => {
    const { key } = await import('/js/branding.js');
    const chip = document.querySelector('.canvas-draft-chip');
    const rectOf = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
    const cs = chip ? getComputedStyle(chip) : null;
    const toasts = [...document.querySelectorAll('.nebflow-toast')];
    return {
      chip: chip ? {
        rect: rectOf(chip),
        role: chip.getAttribute('role'),
        text: chip.textContent,
        background: cs.background.slice(0, 80),
        color: cs.color,
        radius: cs.borderRadius,
        border: cs.borderTopWidth + ' ' + cs.borderTopColor,
        buttons: [...chip.querySelectorAll('button')].map((b) => ({ cls: b.className, text: b.textContent, rect: rectOf(b) })),
        inShell: !!chip.closest('.mf-cv-host'),
      } : null,
      toasts: toasts.map((t) => t.textContent.trim()),
      toastTexts: toasts.map((t) => t.className),
      bodyHasSaveFailure: document.body.innerText.includes('保存失败'),
      draftSlot: localStorage.getItem(key('canvas_seat_draft')),
      noticeSeen: sessionStorage.getItem(key('canvas_notice_seen')),
      tabsKey: localStorage.getItem(key('canvas_tabs')),
    };
  });
}

/** 激活页签的结构读数：滚动宿主是哪一个、它的让位垫有多高（取回入口的落点判据）。 */
async function probePaneHost(page) {
  return page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    if (!pane) return null;
    const host = pane.querySelector('.canvas-md-scroll, .canvas-txt-scroll');
    const root = getComputedStyle(document.documentElement);
    if (host) host.scrollTop = 0;   // 读数归零：几何必须与滚动位置无关
    const chipEl = document.querySelector('.canvas-draft-chip');
    const h1 = host ? host.querySelector('h1') : null;
    const viewer = host ? host.querySelector('.canvas-md-viewer') : null;
    const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
    return {
      paneClasses: pane.className,
      paneChildren: [...pane.children].map((c) => c.className),
      hostClass: host ? host.className : null,
      hostPosition: host ? getComputedStyle(host).position : null,
      hostPaddingTop: host ? getComputedStyle(host).paddingTop : null,
      hostOverflow: host ? getComputedStyle(host).overflowY : null,
      cvSafeTop: root.getPropertyValue('--cv-safe-top').trim(),
      chipParentIsHost: host ? !!host.querySelector('.canvas-draft-chip') : null,
      chipParentClass: chipEl ? String(chipEl.parentElement?.className) : null,
      chipRect: box(chipEl),
      hostChildren: host ? [...host.children].map((c) => ({ cls: c.className, rect: box(c), pos: getComputedStyle(c).position })) : null,
      h1Rect: box(h1),
      h1ParentPos: h1 ? getComputedStyle(h1.parentElement).position : null,
      hostScrollH: host ? host.scrollHeight : null,
      hostClientH: host ? host.clientHeight : null,
      hostRect: box(host),
      hostScrollTop: host ? host.scrollTop : null,
      hostDisplay: host ? getComputedStyle(host).display : null,
      hostPadBottom: host ? getComputedStyle(host).paddingBottom : null,
      viewerMarginTop: viewer ? getComputedStyle(viewer).marginTop : null,
      viewerOffsetTop: viewer ? viewer.offsetTop : null,
      chipOffsetTop: chipEl ? chipEl.offsetTop : null,
    };
  });
}

/** 桌面关键几何（逐值比对用）：面板 / 顶部环带 / 激活页签 / 聊天列 / 画布宿主。 */
async function probeGeometry(page) {
  return page.evaluate(() => {
    const sels = ['#canvas-panel', '#canvas-tab-bar', '.canvas-header', '.canvas-tab-pane.active',
      '.mf-cv-host', '#chat-column', '.chat-column', '.mf-strip'];
    const out = {};
    for (const s of sels) {
      const el = document.querySelector(s);
      if (!el) continue;
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      out[s] = {
        rect: { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) },
        display: cs.display, position: cs.position,
        padding: cs.paddingTop + '/' + cs.paddingBottom,
        radius: cs.borderTopLeftRadius,
        scrollTop: el.scrollTop, scrollH: el.scrollHeight, clientH: el.clientHeight,
      };
    }
    return out;
  });
}

// ── 主流程 ────────────────────────────────────────────────────────────────
const { chromium } = loadPlaywright();
const readings = { tag: TAG, root: WEB, themes: THEMES, seat: {}, screens: {}, errors: [], video: null };
const browser = await chromium.launch();

for (const theme of THEMES) {
  for (const scene of [
    { key: 'mobile', w: 375, h: 812, video: theme === THEMES[0] && VIDEO },
    { key: 'tablet', w: 768, h: 1024, video: false },
    { key: 'desktop', w: 1440, h: 900, video: false },
  ]) {
    const id = `${scene.key}-${theme}`;
    const rec = { viewport: [scene.w, scene.h], theme, errors: [] };
    const videoDir = scene.video ? join(OUT, `${TAG}-video`) : null;
    if (videoDir) mkdirSync(videoDir, { recursive: true });
    let A = null, B = null;
    try {
      A = await bootPage(browser, { name: `A-${id}`, width: scene.w, height: scene.h, theme, video: videoDir });
      await openDocs(A.page, DOCS);
      await openCanvasSurface(A.page, scene.w);
      await renderBody(A.page, MD_BODY);
      // 窗口 A 真的写一次 ⇒ 产品写路径自动申请席位（Node 席位模型授予 epoch=1）。
      await A.page.evaluate(async () => { const c = await import('/js/canvas.js'); c.setActiveTab('d1'); });
      await A.page.evaluate(async () => { const c = await import('/js/canvas.js'); c.setActiveTab('d2'); });
      await waitFor(() => seat.claims.length >= 1 && seat.writer !== null, 8000);
      // 无草稿态的几何（桌面零回归的「改 / 未改」同页读数）。
      const geomNoLoss = await probeGeometry(A.page);
      const epochA = seat.epoch;
      const tBeforeLoss = Date.now();

      // 窗口 B：第二个真窗口，自己写一次 ⇒ 静默接手。
      B = await bootPage(browser, { name: `B-${id}`, width: scene.w, height: scene.h, theme });
      await openDocs(B.page, DOCS_B);
      await B.page.evaluate(async () => { const c = await import('/js/canvas.js'); c.setActiveTab('b1'); });
      const tookOver = await waitFor(() => seat.claims.length >= 2 && seat.epoch > epochA, 8000);
      const chipVisible = await A.page.waitForSelector('.canvas-draft-chip', { timeout: 8000 }).then(() => true).catch(() => false);
      const tChip = Date.now();
      await A.page.waitForTimeout(500);

      const draftA = await probeDraft(A.page);
      const paneHost = await probePaneHost(A.page);   // 必须在取回之前读（取回后条目已撤）
      const geomAfter = await probeGeometry(A.page);
      if (scene.key === 'mobile') {
        await A.page.screenshot({ path: join(OUT, `${TAG}-${scene.w}-${theme}.png`) });
        if (draftA.chip) await A.page.locator('.canvas-draft-chip').screenshot({ path: join(OUT, `${TAG}-${scene.w}-${theme}-chip.png`) }).catch(() => {});
        await A.page.screenshot({ path: join(OUT, `${TAG}-${scene.w}-${theme}-chipAnchored.png`) });
      } else {
        await A.page.screenshot({ path: join(OUT, `${TAG}-${scene.w}-${theme}.png`) });
      }

      // 取回：点入口 → 草稿清、页签并集、零通知。
      let restore = null;
      let tRestore = null;
      if (draftA.chip) {
        await A.page.locator('.canvas-draft-chip-restore').click({ timeout: 5000 }).catch((e) => { rec.errors.push('restore click: ' + e.message); });
        tRestore = Date.now();
        await A.page.waitForTimeout(500);
        const after = await probeDraft(A.page);
        restore = {
          chipGone: after.chip === null,
          slotCleared: after.draftSlot === null,
          toastsAfter: after.toasts.length,
          activePaneCount: await A.page.evaluate(() => document.querySelectorAll('.canvas-tab-pane').length),
        };
        if (scene.key === 'mobile') await A.page.screenshot({ path: join(OUT, `${TAG}-${scene.w}-${theme}-restored.png`) });
      }

      rec.seat = {
        claims: seat.claims.length, epochAfterTakeover: seat.epoch, tookOver, chipVisible,
        displacedSeen: seat.claims.filter((c) => c.displaced).length,
        broadcastsSeen: seat.broadcasts.length,
        lastBroadcast: seat.broadcasts.at(-1) || null,
      };
      rec.draft = {
        presentAfterLoss: draftA.draftSlot !== null,
        activeTabId: draftA.draftSlot ? (JSON.parse(draftA.draftSlot).activeTabId ?? null) : null,
        tabCount: draftA.draftSlot ? JSON.parse(draftA.draftSlot).tabs.length : 0,
      };
      rec.silence = {
        toastCount: draftA.toasts.length, toastTexts: draftA.toasts,
        bodyHasSaveFailureWord: draftA.bodyHasSaveFailure,
        noticeSeenKey: draftA.noticeSeen,
        saveFailedNoticeRecorded: !!(draftA.noticeSeen && draftA.noticeSeen.includes('save-failed')),
      };
      rec.chip = draftA.chip;
      rec.paneHost = paneHost;
      rec.videoMarks = {
        videoStart: 0,
        beforeLoss: +((tBeforeLoss - A.t0) / 1000).toFixed(2),
        chipVisible: +((tChip - A.t0) / 1000).toFixed(2),
        restoreClick: tRestore ? +((tRestore - A.t0) / 1000).toFixed(2) : null,
      };
      rec.restore = restore;
      rec.geometry = { noLoss: geomNoLoss, chipPresent: geomAfter };
      readings.errors.push(...A.errors.map((e) => `[${id}] ${e}`));
    } catch (e) {
      rec.errors.push(String(e && e.message ? e.message : e));
      readings.errors.push(`[${id}] ${String(e && e.message ? e.message : e)}`);
    }
    readings.screens[id] = rec;
    for (const w of [A, B]) {
      if (!w) continue;
      const vid = w.page.video ? w.page.video() : null;   // 必须在 close 之前取句柄
      await w.context.close().catch(() => {});
      if (vid && w === A) readings.video = await vid.path().catch(() => null);
    }
  }
}
await browser.close();
readings.seat = { claims: seat.claims, puts: seat.puts, broadcasts: seat.broadcasts, releases: seat.releases, finalEpoch: seat.epoch };
writeFileSync(join(OUT, `${TAG}-readings.json`), JSON.stringify(readings, null, 2));
const failed = readings.errors.length > 0 || Object.values(readings.screens).some((s) => s.errors.length > 0);
console.log(`[${TAG}] screens=${Object.keys(readings.screens).length} errors=${readings.errors.length} video=${readings.video || '-'}`);
for (const [k, s] of Object.entries(readings.screens)) {
  console.log(`  ${k}: chip=${s.chip ? 'yes' : 'no'} draft=${s.draft?.presentAfterLoss ? 'yes' : 'no'} toasts=${s.silence?.toastCount} restore=${s.restore ? JSON.stringify(s.restore) : '-'}${s.errors.length ? ' ERR=' + s.errors[0].slice(0, 120) : ''}`);
}
process.exit(failed ? 1 : 0);
