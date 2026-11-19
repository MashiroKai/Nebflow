#!/usr/bin/env node
// mobile-panel-title.spec.mjs — 大标题退役验收（ui-unify-panels B 批 2026-11-17
// 作者令「一级与次级面板都不渲染大标题」）。
//
// 前提改写：本 spec 原为「两段式标题」/「单元素连续变形」的几何验收（静止 28px
// 大标题 → 吸附 48 高毛玻璃胶囊，进度 --mtitle-p 0→1）；B 批推翻该选型 ——
// navController 不再建页名节点 .mobile-page-title，滚动收缩探针
// （bindRollProbe/applyRoll/measureTitleStill/refreshRoll）与 CSS 规则族
// （.mobile-page-title 基座 / ::before 壳层 / .mtitle-clip / .rolled pointer-
// events / no-backdrop 降级）整链退役。历史数值契约（盒 52/28px、胶囊 48/40/
// 左缘 72、区间 8→56）留档于 git 历史与本文件旧版。
//
// B 批判据（全部 getBoundingClientRect/getComputedStyle 机械读数）：
//   Z1 零标题节点：每个非根页壳（含 tasks/todo-apple 系/会话页）push 后
//      .mobile-page-title / -mini / -band 三系计数恒 0。
//   Z2 机器面退役：滚动（0→底→回顶）后壳子树无 .rolled/.mpt-pane-scroll/
//      .mtitle-clip 类、无 --mtitle-p 内联进度。
//   Z3 返回钮在场：直径 = clamp(36px,10vw,44px)（A 批 --band-d 单源）、
//      aria-label 非空、pointer-events auto。
//   Z4 首件让位自管：页体 padding-top = --mchrome-clear + safe（返回钮底缘
//      + 呼吸 8）；首可见件顶 ≥ 返回钮底缘 + 8（会话页豁免 —— 身份头自带
//      足迹，padding-top 0）。
//   Z5 页名信息面不灭：section aria-label = 页名（读屏播报承担者）。
//   Z6 双引擎 × 双主题 × 全页壳（20 页）+ 滚动取样。
//   P9 桌面零触碰腿（390 折叠点外零页面壳/零标题节点/#header 在场）。
//   P8 零 console error（噪声过滤 = pop-footer 同表）。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/mobile-panel-title.spec.mjs
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.MPT_OUT_DIR || join(HERE, '..', 'evidence', '20261117_b-no-title');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mobile-panel-title-root';
// 端口 8899：WebKit bad-port 封禁表外（pop-footer-family spec 同款实证）。
const ORIGIN = 'http://localhost:8899';
// 视口 = 验收口径 375×812（作者实拍机型档）。
const VW = 375;
const VH = 812;
// A 批带几何单源（mobileLayer.css --band-d）：375 档 = 37.5；返回钮底缘 =
// --mchrome-top 16 + 37.5 = 53.5；首件让位 = 底缘 + 呼吸 8 = 61.5
//（= --mchrome-clear，16 + 37.5 + 8 —— 随 --band-d 自动换档）。
const BAND_D = Math.min(44, Math.max(36, VW * 0.1));
const BACK_TOP = 16;
const BACK_BOTTOM = BACK_TOP + BAND_D;
const BREATHE = 8;

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const near = (a, b, tol = 1) => Math.abs(Number(a) - Number(b)) <= tol;

// Console/pageerror 硬零（噪声过滤 = pop-footer 同表）。
const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => {
    if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`);
  });
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`);
  });
};

/* ── 夹具：项目 12 行（projects 页滚动域）＋ 待办 24 行 ── */
const PROJECTS = Array.from({ length: 12 }, (_, i) => ({
  name: `fixture-project-${String(i + 1).padStart(2, '0')}`,
  workspace: `/tmp/mpt/proj-${i + 1}`,
  agentFile: '',
  description: i % 2 === 0 ? '验收夹具项目（滚动域填充行）' : '',
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
const TODOS = Array.from({ length: 24 }, (_, i) => ({
  id: `mpt-td-${i + 1}`,
  kind: 'todo',
  content: `验收夹具待办 ${i + 1}：滚动域填充行`,
  triggerAt: Date.now() + 3600_000,
}));

/* ── boot（route 静态 + WS 夹具 + /api 夹具；375×812 手机视口）────────── */
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
      value: () => new Promise(() => { /* 永不决 = 注册面静默空转 */ }),
      configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
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
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
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

/* ── 单页壳零标题 + 让位读数 ─────────────────────────────────────────── */
const PROBE_JS = (pid) => `
  (() => {
    const sec = document.querySelector('#mobile-pages .mobile-page[data-page="${pid}"]');
    if (!sec) return { found: false };
    const back = sec.querySelector('.mobile-back-btn');
    const body = sec.querySelector('.mobile-page-body');
    const bb = back ? back.getBoundingClientRect() : null;
    const bcs = body ? getComputedStyle(body) : null;
    const first = body ? [...body.children].find((c) => {
      const cs = getComputedStyle(c);
      return cs.display !== 'none' && cs.visibility !== 'hidden';
    }) : null;
    const fb = first ? first.getBoundingClientRect() : null;
    return {
      found: true,
      live: sec.classList.contains('live'),
      titleCount: sec.querySelectorAll('.mobile-page-title, .mobile-page-title-mini, .mobile-page-title-band').length,
      rolled: sec.classList.contains('rolled'),
      paneScroll: sec.classList.contains('mpt-pane-scroll'),
      clipCount: sec.querySelectorAll('.mtitle-clip').length,
      mtitleP: [...sec.querySelectorAll('*')].filter((e) => e.style.getPropertyValue('--mtitle-p')).length
        + (sec.style.getPropertyValue('--mtitle-p') ? 1 : 0),
      ariaLabel: sec.getAttribute('aria-label') || '',
      backPresent: !!back,
      backW: bb ? +bb.width.toFixed(2) : -1,
      backBottom: bb ? +bb.bottom.toFixed(2) : -1,
      backPE: back ? getComputedStyle(back).pointerEvents : '',
      bodyPadTop: bcs ? +parseFloat(bcs.paddingTop).toFixed(2) : null,
      firstTop: fb ? +fb.top.toFixed(2) : null,
      vw: window.innerWidth,
    };
  })()`;

/* ── 主腿：全页壳零标题（双引擎 × 双主题）────────────────────────────── */
async function themeLeg(engineName, scheme) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    const ids = await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      return nav.PAGES.filter((p) => !p.dock).map((p) => p.id);
    });
    ok(`${tag} 页壳表在场（PAGES 非根页 ≥ 17）`, ids.length >= 17, `n=${ids.length}`);
    const shotPages = new Set(['projects', 'settings', 'tasks']);
    const bad = [];
    for (const id of ids) {
      const pushed = await page.evaluate(async (pid) => {
        const nav = await import('/js/mobile/navController.js');
        try { nav.pushPage(pid); return true; } catch { return false; }
      }, id);
      await page.waitForTimeout(260);
      const p = await page.evaluate(PROBE_JS(id));
      const isConv = id === 'conversation';
      if (!p.found || !pushed) { bad.push(`${id}:no-shell`); continue; }
      const errs = [];
      if (p.titleCount !== 0) errs.push(`title=${p.titleCount}`);
      if (p.mtitleP !== 0) errs.push(`--mtitle-p=${p.mtitleP}`);
      if (!p.backPresent) errs.push('no-back');
      if (Math.abs(p.backW - BAND_D) > 1) errs.push(`backW=${p.backW}`);
      if (p.backPE !== 'auto') errs.push(`backPE=${p.backPE}`);
      if (!p.ariaLabel) errs.push('no-aria');
      if (!isConv) {
        if (Math.abs(p.bodyPadTop - (BACK_BOTTOM + BREATHE)) > 1.5) errs.push(`padTop=${p.bodyPadTop}`);
        if (p.firstTop != null && p.firstTop < BACK_BOTTOM + BREATHE - 1.5) errs.push(`firstTop=${p.firstTop}`);
      }
      // Z2：滚到底再回顶，机器面（.rolled/.mpt-pane-scroll/.mtitle-clip）不得出现。
      await page.evaluate((pid) => {
        const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
        for (const s of ['.mobile-page-body', '.team-scroll', '#settings-content', '.todo-page-body']) {
          for (const el of sec.querySelectorAll(s)) { el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event('scroll', { bubbles: false })); }
        }
      }, id);
      await page.waitForTimeout(160);
      const p2 = await page.evaluate(PROBE_JS(id));
      if (p2.rolled || p2.paneScroll || p2.clipCount || p2.mtitleP || p2.titleCount) {
        errs.push(`after-scroll: rolled=${p2.rolled} pane=${p2.paneScroll} clip=${p2.clipCount} p=${p2.mtitleP} t=${p2.titleCount}`);
      }
      if (errs.length) bad.push(`${id}:{${errs.join(',')}}`);
      if (shotPages.has(id)) await page.screenshot({ path: join(SHOTS, `${tag}-${id}-rest.png`) });
      await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* at root */ } });
      await page.waitForTimeout(120);
    }
    ok(`${tag} Z1-Z5 全页壳零标题/机器面退役/让位/返回钮/aria（${ids.length} 壳）`, bad.length === 0,
      bad.slice(0, 6).join(' | ') || 'all clean');
  } finally {
    await page.close();
  }
}

/* ── reduced-motion 腿（chromium-light 一次）：二值档同样零机器面 ──────── */
async function reducedMotionLeg() {
  const page = await boot('light', 'chromium', { reducedMotion: true });
  try {
    const pushed = await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.pushPage('projects');
      return true;
    });
    await page.waitForTimeout(400);
    for (const v of [20, 56, 120, 300]) {
      await page.evaluate((top) => {
        const sec = document.querySelector('#mobile-pages .mobile-page[data-page="projects"]');
        const t = sec.querySelector('.team-scroll') || sec.querySelector('.mobile-page-body');
        t.scrollTop = top;
        t.dispatchEvent(new Event('scroll', { bubbles: false }));
      }, v);
      await page.waitForTimeout(90);
      const p = await page.evaluate(PROBE_JS('projects'));
      ok(`reduced-motion scrollTop ${v} 零标题 + 零机器面`, p.titleCount === 0 && !p.rolled && p.mtitleP === 0,
        `t=${p.titleCount} rolled=${p.rolled} p=${p.mtitleP}`);
    }
  } finally {
    await page.close();
  }
}

/* ── 桌面零触碰腿 ─────────────────────────────────────────────────────── */
async function desktopLeg() {
  const engine = chromium;
  const page = await (await engine.launch()).newContext({
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
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
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
    pageShells: document.querySelectorAll('.mobile-page').length,
    titleNodes: document.querySelectorAll('.mobile-page-title').length,
    headerShown: !!document.querySelector('#header'),
  }));
  ok('P9 桌面腿 body.mobile-layer 不激活（390 折叠点外）', !d.mobileLayer);
  ok('P9 桌面腿零页面壳/零标题节点（页面世界不建）', d.pageShells === 0 && d.titleNodes === 0, `shells=${d.pageShells} titles=${d.titleNodes}`);
  ok('P9 桌面腿 #header 在场（桌面壳零变化）', d.headerShown);
  await page.close();
}

/* ── 主流程：双引擎 × 双主题 + reduced-motion + 桌面腿 + 零 console error ── */
console.log(`evidence → ${OUT}`);
for (const engineName of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await themeLeg(engineName, scheme);
  }
}
await reducedMotionLeg();
await desktopLeg();

ok('P8 零 console error（双引擎×双主题全腿 + reduced-motion + 桌面腿，噪声过滤后硬零）', consoleErrors.length === 0,
  consoleErrors.slice(0, 5).join(' | '));

console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
