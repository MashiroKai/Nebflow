#!/usr/bin/env node
// mobile-title-morph.spec.mjs — 变形机制退役哨兵（ui-unify-panels B 批 2026-11-17）。
//
// 前提改写：本 spec 原验收「大标题 → 胶囊」单元素连续变形（L1–L11：字号/轮廓/
// 宽度连续性、无双影像素差、终点形态、降级路径、reduced-motion 二值、桌面快照
// diff、四面板通治 —— 历史判据全文见 git 历史旧版）。B 批作者令「一级与次级面板
// 都不渲染大标题」推翻该选型：页名节点 .mobile-page-title 不再建，滚动收缩探针
// （navController bindRollProbe/applyRoll/measureTitleStill/refreshRoll）与 CSS
// 形态族整链退役。本 spec 反向守门：**滚动全区间内变形机制不得复活** ——
//   S1 四面板（后台任务/项目/设置/任务）滚动 0→底 全程 ≥10 取样点，
//      每点标题节点计数恒 0、无 .rolled/.mpt-pane-scroll/.mtitle-clip、
//      无 --mtitle-p 内联进度。
//   S2 滚轮回顶同判（真 mouse.wheel）。
//   S3 reduced-motion 二值档同判。
//   S4 桌面零触碰（1280×800：mobile-layer 不激活、零标题节点）。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/mobile-title-morph.spec.mjs
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const TAG = process.env.MTM_TAG || 'b-retired';
const OUT = process.env.MTM_OUT_DIR || join(ROOT, 'evidence', `mobile-title-morph-${TAG}`);
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mtm-root-session';
// 端口 8899：WebKit bad-port 封禁表外（同族 spec 实证）。视口 390×844（作者实拍档）。
const ORIGIN = 'http://localhost:8899';
const VW = 390;
const VH = 844;

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`); });
};

const PROJECTS = Array.from({ length: 12 }, (_, i) => ({
  name: `fixture-project-${i + 1}`,
  workspace: `/tmp/mtm/proj-${i + 1}`,
  agentFile: '',
  description: '',
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
const TODOS = Array.from({ length: 24 }, (_, i) => ({
  id: `mtm-td-${i + 1}`, kind: 'todo',
  content: `验收夹具待办 ${i + 1}`, triggerAt: Date.now() + 3600_000,
}));

async function boot(scheme, engineName, opts = {}) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const ctx = await engine.launch().then((b) => b.newContext({
    viewport: { width: VW, height: VH },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
    reducedMotion: opts.reducedMotion ? 'reduce' : 'no-preference',
  }));
  const page = await ctx.newPage();
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
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
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') {
        const scope = m.scope === 'all' ? 'all' : 'session';
        ws.send(JSON.stringify({ type: 'scheduledTaskList', scope, tasks: TODOS.map((t) => ({ ...t })) }));
      }
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(600);
  return page;
}

/** 主滚轴判定（旧 L 判据同款：页体子树内最外层有余量的纵向滚动器）。 */
const PRIMARY_SEL_JS = `
  (sec) => {
    const bodyEl = sec.querySelector('.mobile-page-body');
    const depthOf = (n) => { let d = 0; for (let p = n; p && p !== sec; p = p.parentElement) d += 1; return d; };
    let primary = null; let best = Infinity;
    for (const n of [bodyEl, ...bodyEl.querySelectorAll('*')]) {
      const oy = getComputedStyle(n).overflowY;
      if (oy !== 'auto' && oy !== 'scroll') continue;
      if (n.scrollHeight - n.clientHeight <= 4) continue;
      const d = depthOf(n);
      if (d < best) { primary = n; best = d; }
      if (d === 1) break;
    }
    return primary || bodyEl;
  }`;

const SAMPLE_JS = (pid) => `
  (() => {
    const sec = document.querySelector('#mobile-pages .mobile-page[data-page="${pid}"]');
    if (!sec) return null;
    return {
      titleCount: sec.querySelectorAll('.mobile-page-title, .mobile-page-title-mini').length,
      rolled: sec.classList.contains('rolled'),
      paneScroll: sec.classList.contains('mpt-pane-scroll'),
      clip: sec.querySelectorAll('.mtitle-clip').length,
      p: sec.style.getPropertyValue('--mtitle-p') || '',
    };
  })()`;

async function morphLeg(engineName, scheme) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    // 四面板（通治面沿用旧 L11）：一律 pushPage 直推（会话态/nav 时序零依赖）。
    const legs = [
      { id: 'projects', sel: '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll' },
      { id: 'settings', sel: '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal' },
      { id: 'tasks', sel: '#mobile-pages .mobile-page.live[data-page="tasks"] .ta-root' },
      { id: 'background', sel: '#mobile-pages .mobile-page.live[data-page="background"]' },
    ];
    for (const leg of legs) {
      await page.evaluate(async (pid) => { (await import('/js/mobile/navController.js')).pushPage(pid); }, leg.id);
      await page.waitForSelector(leg.sel, { state: 'attached', timeout: 12000 });
      await page.waitForTimeout(350);
      const max = await page.evaluate(({ pid, selSrc }) => {
        const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
        const t = eval(selSrc)(sec);
        return t ? t.scrollHeight - t.clientHeight : -1;
      }, { pid: leg.id, selSrc: PRIMARY_SEL_JS });
      // 滚动域深腿交给 projects/settings（夹具滚动余量大）；tasks/background
      // 本 harness 夹具下可能无余量 ⇒ 域门降为记录（max>56 时才 11 点取样）。
      const steps = max > 56 ? 10 : 0;
      // S1：0→底 11 个取样点（无余量页 = 单点 0），每点零标题 + 零机器面。
      const samples = [];
      for (let i = 0; i <= steps; i += 1) {
        const v = Math.round((max * i) / steps);
        await page.evaluate(({ pid, v, selSrc }) => {
          const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
          const t = eval(selSrc)(sec);
          t.scrollTop = v;
          t.dispatchEvent(new Event('scroll', { bubbles: false }));
        }, { pid: leg.id, v, selSrc: PRIMARY_SEL_JS });
        await page.waitForTimeout(70);
        samples.push(await page.evaluate(SAMPLE_JS(leg.id)));
      }
      const bad = samples.filter((s) => s && (s.titleCount || s.rolled || s.paneScroll || s.clip || s.p));
      ok(`${tag} S1 ${leg.id} 滚动全程零标题/零机器面（${samples.length} 取样，max=${max}）`, bad.length === 0,
        bad.length ? JSON.stringify(bad[0]) : `end=${JSON.stringify(samples[samples.length - 1])}`);
      // S2：真滚轮回顶。
      for (let i = 0; i < 6; i += 1) await page.mouse.wheel(0, -400);
      await page.waitForTimeout(280);
      const top = await page.evaluate(SAMPLE_JS(leg.id));
      ok(`${tag} S2 ${leg.id} 滚轮回顶零机器面`, !top.rolled && top.titleCount === 0 && !top.p, JSON.stringify(top));
      await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* root */ } });
      await page.waitForTimeout(140);
    }
  } finally {
    await page.close();
  }
}

/* ── S3 reduced-motion（chromium-light 一次）── */
async function reducedLeg() {
  const page = await boot('light', 'chromium', { reducedMotion: true });
  try {
    await page.evaluate(async () => { (await import('/js/mobile/navController.js')).pushPage('projects'); });
    await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll', { state: 'attached', timeout: 12000 });
    await page.waitForTimeout(350);
    for (const v of [30, 90, 200]) {
      await page.evaluate(({ v, selSrc }) => {
        const sec = document.querySelector('#mobile-pages .mobile-page[data-page="projects"]');
        const t = eval(selSrc)(sec);
        t.scrollTop = v;
        t.dispatchEvent(new Event('scroll', { bubbles: false }));
      }, { v, selSrc: PRIMARY_SEL_JS });
      await page.waitForTimeout(90);
      const s = await page.evaluate(SAMPLE_JS('projects'));
      ok(`S3 reduced-motion scrollTop ${v} 零机器面`, s.titleCount === 0 && !s.rolled && !s.p, JSON.stringify(s));
    }
  } finally {
    await page.close();
  }
}

/* ── S4 桌面零触碰 ── */
async function desktopLeg() {
  const page = await (await chromium.launch()).newContext({
    viewport: { width: 1280, height: 800 }, colorScheme: 'light', locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(800);
  const d = await page.evaluate(() => ({
    mobileLayer: document.body.classList.contains('mobile-layer'),
    titles: document.querySelectorAll('.mobile-page-title').length,
  }));
  ok('S4 桌面腿 mobile-layer 不激活 + 零标题节点', !d.mobileLayer && d.titles === 0, JSON.stringify(d));
  await page.close();
}

console.log(`evidence → ${OUT}`);
for (const engineName of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await morphLeg(engineName, scheme);
  }
}
await reducedLeg();
await desktopLeg();
ok('零 console error（噪声过滤后硬零）', consoleErrors.length === 0, consoleErrors.slice(0, 5).join(' | '));
console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
