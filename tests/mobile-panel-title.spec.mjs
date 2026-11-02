#!/usr/bin/env node
// mobile-panel-title.spec.mjs — 面板头两段式标题批验收（作者令 2026-11-02，实拍
// IMG_1677「设置」/ IMG_1678「项目」）：B 批「iOS 大标题式」两态（左对齐大标题 +
// 小字渐隐带）升级为**两段式**：
//   静止态 = 页体流内**水平居中**大标题（28px/600）；吸附态 = 同页名的装饰副本
//   收成一颗**毛玻璃胶囊**（高 32、左缘 ≥72、垂直中心 40），绝对悬浮、不占高度；
//   两段之间 = 滚动跟随的连续插值 --mtitle-p 0→1（区间 8→56），回滚反向展开。
//   顶缘渐隐带 .mobile-page-title-band 整族退役（DOM 不再建）。
//
// 覆盖（双引擎 Chromium/WebKit × 双主题 light/dark × 3 面板 × 三态）：
//   P1 静止态：大标题 = 页体流内首件（static）、水平居中（text-align center +
//      左右内缩 72）、28px/600、字距负档、墨色 = --color-frame-text-bright、
//      opacity 1；盒高 52、盒顶 12、盒底 64 = 返回钮底缘、文字中心 = 钮中心 40；
//      胶囊 opacity 0、几何恒 32 高；渐隐带节点零；「<」返回钮在场。
//   P2 阈值 + 插值：主滚轴 scrollTop 40（区间内）⇒ p≈0.667、不切 .rolled、
//      大标题 opacity = 1−p、胶囊 opacity = p；scrollTop 300 ⇒ p=1、.rolled 成立、
//      大标题收高（pane 收高 leg：h=0）、胶囊满透明且几何仍 32/中心 40/左缘 ≥72、
//      返回钮仍可点（z 序 6 > 胶囊 5 / 胶囊 pointer-events none）。
//   P3 真滚轮回顶：mouse.wheel 上滚到顶 ⇒ p=0、.rolled 消失、大标题复现。
//   P4 面板切换对账：A 面板吸附态离场 → 进 B 面板 ⇒ B 静止态（p=0，不继承吸附）。
//   P5 reduced-motion：进度二值（只有 0/1），且大标题无位移量。
//   P6 截图存证：375×812 每面板 静止/吸附中/回顶 三拍（引擎×主题×面板 = 36 张）。
//   P7 零 console error，P8 桌面零触碰腿。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/mobile-panel-title.spec.mjs
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.MPT_OUT_DIR || join(HERE, '..', 'evidence', `${TS}_mobile-panel-title`);
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
// 视口 = 验收口径 375×812（作者实拍机型档）；--mtitle-inset 72 与之无关（固定档）。
const VW = 375;
const VH = 812;
// 阈值/几何契约（与 mobileLayer.css token 块、navController.js 探针常量同源）。
const ROLL_START = 8;
const ROLL_SNAP = 56;
const INSET = 72;
const CAP_H = 32;
const CAP_CENTER_Y = 40;

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

/* ── 夹具：项目 12 行（projects 页滚动域）＋ 待办 24 行（todo 页滚动域；
   行数取 24 = WebKit 行高更矮时仍保证主滚轴余量 > 吸附阈值 56px，否则
   scrollTop 被钳在 40 以内、进度永远到不了 1（实测账本在批文档））── */
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
  content: `验收夹具待办 ${i + 1}：滚动收缩域填充行`,
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

/* ── 导航（真实 DOM 点击路径）────────────────────────────────────────── */
async function gotoWorkbench(page) {
  await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page.live');
    if (sec) sec.querySelectorAll('.mobile-back-btn').forEach((b) => b.click());
  });
  for (let i = 0; i < 6; i += 1) {
    const onHome = await page.evaluate(() => !document.querySelector('#mobile-pages .mobile-page.live[data-page]:not([data-page="messages"]):not([data-page="contacts"]):not([data-page="canvas"]):not([data-page="workbench"])'));
    if (onHome) break;
    await page.waitForTimeout(120);
  }
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="workbench"] .mf-hub-rows', { timeout: 10000 });
  await page.waitForTimeout(200);
}

async function openPage(page, hubPage, liveSelector) {
  await gotoWorkbench(page);
  await page.locator(`#mobile-pages .mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${hubPage}"]`).click();
  await page.waitForSelector(liveSelector, { timeout: 15000 });
  await page.waitForTimeout(450); // 面装配/图标渲染收敛
}

async function openTodo(page) {
  await gotoWorkbench(page);
  await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:mobile-todo-entry')));
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="todo"] #todo-page .reminder-row', { timeout: 15000 });
  await page.waitForTimeout(450);
}

/* ── 探针：读页面壳两段式标题态 ───────────────────────────────────────── */
async function probe(page, pageId) {
  return page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    if (!sec) return { found: false };
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const title = sec.querySelector('.mobile-page-title');
    const mini = sec.querySelector('.mobile-page-title-mini');
    const back = sec.querySelector('.mobile-back-btn');
    if (!title || !mini) return { found: false, why: 'title/mini missing' };
    const tb = title.getBoundingClientRect();
    const mb = mini.getBoundingClientRect();
    const bb = back ? back.getBoundingClientRect() : null;
    const tokenProbe = document.createElement('span');
    tokenProbe.style.color = 'var(--color-frame-text-bright)';
    document.body.appendChild(tokenProbe);
    const ink = getComputedStyle(tokenProbe).color;
    tokenProbe.remove();
    const t = cs(title); const m = cs(mini); const bs = cs(back);
    const pv = parseFloat(t.getPropertyValue('--mtitle-p')) || 0;
    return {
      found: true,
      p: Math.round(pv * 1000) / 1000,
      rolled: sec.classList.contains('rolled'),
      paneScroll: sec.classList.contains('mpt-pane-scroll'),
      titleText: title.textContent || '',
      titlePos: t.position,
      titleIsBodyFirst: sec.querySelector('.mobile-page-body')?.firstElementChild === title,
      titleAlign: t.textAlign,
      titlePadL: t.paddingLeft,
      titlePadR: t.paddingRight,
      titleFontSize: t.fontSize,
      titleWeight: t.fontWeight,
      titleSpacing: parseFloat(t.letterSpacing),
      titleColor: t.color,
      ink,
      titleH: Math.round(tb.height),
      titleTop: Math.round(tb.top),
      titleBottom: Math.round(tb.bottom),
      // 文字行盒中心（盒顶 + 上内距 + 行高/2）= 应与返回钮中心同轴 40；
      // 盒几何中心则是 12 + 52/2 = 38（盒内上下内距不对称 10/6 的直接结果）。
      titleTextCenterY: Math.round(tb.top + parseFloat(t.paddingTop) + parseFloat(t.lineHeight) / 2),
      titleOpacity: Math.round(parseFloat(t.opacity) * 1000) / 1000,
      titleTransform: t.transform,
      titlePE: t.pointerEvents,
      miniOpacity: Math.round(parseFloat(m.opacity) * 1000) / 1000,
      miniH: Math.round(mb.height),
      miniCenterY: Math.round(mb.top + mb.height / 2),
      miniLeft: Math.round(mb.left),
      miniRight: Math.round(mb.right),
      miniCenterX: Math.round(mb.left + mb.width / 2),
      miniPE: m.pointerEvents,
      miniZ: m.zIndex,
      miniFS: m.fontSize,
      miniBg: m.backgroundColor,
      miniBlur: m.backdropFilter || m.webkitBackdropFilter || '',
      miniRadius: m.borderRadius,
      backCenterY: bb ? Math.round(bb.top + bb.height / 2) : -1,
      backBottom: bb ? Math.round(bb.bottom) : -1,
      backZ: bs ? bs.zIndex : '',
      backPE: bs ? bs.pointerEvents : '',
      backVisible: !!back && bs.display !== 'none',
      bandCount: sec.querySelectorAll('.mobile-page-title-band').length,
      vw: window.innerWidth,
    };
  }, pageId);
}

/** 主滚轴（判定同 navController.refreshRoll：页体子树内**最外层**有余量的纵向
 *  滚动器——按距 section 的层级取最小者，把嵌套小滚区排除；页体自身可滚即它）。 */
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
      if (d === 1) break; // 页体自身可滚 ⇒ 它必是最外层
    }
    return primary || bodyEl;
  }`;

/** 主滚轴 scrollTop 精调 + 滚动事件派发（阈值/插值腿）。 */
async function setScroll(page, pageId, value) {
  const res = await page.evaluate(({ pid, v, selSrc }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const target = eval(selSrc)(sec);
    target.scrollTop = v;
    target.dispatchEvent(new Event('scroll', { bubbles: false }));
    return { tag: target.className || target.id || target.tagName, top: target.scrollTop };
  }, { pid: pageId, v: value, selSrc: PRIMARY_SEL_JS });
  await page.waitForTimeout(120);
  return res;
}

/** 主滚轴读数（滚轮腿的前后态核对）。 */
async function readScrollTop(page, pageId) {
  return page.evaluate(({ pid, selSrc }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const target = eval(selSrc)(sec);
    return { tag: target.className || target.id || target.tagName, top: target.scrollTop };
  }, { pid: pageId, selSrc: PRIMARY_SEL_JS });
}

/** 期望进度（区间 8→56 线性钳死）。 */
const expectP = (st) => {
  const p = (st - ROLL_START) / (ROLL_SNAP - ROLL_START);
  return Math.round(Math.max(0, Math.min(1, p)) * 1000) / 1000;
};

/* ── 单面板全腿 ──────────────────────────────────────────────────────── */
async function panelLeg(page, tag, pageId, hoverSel) {
  const p0 = await probe(page, pageId);
  ok(`${tag} 页面壳在场`, p0.found);
  if (!p0.found) return;

  // ── P1 静止态 ──
  ok(`${tag} 静止态 .rolled 不在场`, !p0.rolled);
  ok(`${tag} 静止态进度 p = 0`, p0.p === 0, `p=${p0.p}`);
  ok(`${tag} 大标题文案 = 面板现名`, p0.titleText.length > 0, `text="${p0.titleText}"`);
  ok(`${tag} 大标题在流内（position:static）、页体首件`, p0.titlePos === 'static' && p0.titleIsBodyFirst, `${p0.titlePos}/${p0.titleIsBodyFirst}`);
  ok(`${tag} 大标题水平居中（text-align center）`, p0.titleAlign === 'center', p0.titleAlign);
  ok(`${tag} 大标题左右内缩各 72px（--mtitle-inset）`, p0.titlePadL === `${INSET}px` && p0.titlePadR === `${INSET}px`, `${p0.titlePadL}/${p0.titlePadR}`);
  ok(`${tag} 大标题 28px/600（作者令破档档位保留）`, p0.titleFontSize === '28px' && p0.titleWeight === '600', `${p0.titleFontSize}/${p0.titleWeight}`);
  ok(`${tag} 大标题字距负档（-0.01em）`, p0.titleSpacing < 0, `got ${p0.titleSpacing}px`);
  ok(`${tag} 大标题墨色 = --color-frame-text-bright`, p0.titleColor === p0.ink, `got ${p0.titleColor} / token ${p0.ink}`);
  ok(`${tag} 大标题可见（opacity 1）`, near(p0.titleOpacity, 1, 0.01), `got ${p0.titleOpacity}`);
  ok(`${tag} 大标题盒高 52（防裁字）`, p0.titleH === 52, `h=${p0.titleH}`);
  ok(`${tag} 大标题盒顶 12（页体顶呼吸缝）`, p0.titleTop === 12, `top=${p0.titleTop}`);
  ok(`${tag} 大标题盒底 64 = 返回钮底缘`, p0.titleBottom === 64 && p0.backBottom === 64, `title=${p0.titleBottom} back=${p0.backBottom}`);
  ok(`${tag} 大标题文字中心 = 返回钮中心（40，同轴）`, p0.titleTextCenterY === CAP_CENTER_Y && p0.backCenterY === CAP_CENTER_Y, `text=${p0.titleTextCenterY} back=${p0.backCenterY}`);
  ok(`${tag} 静止态胶囊 opacity 0`, p0.miniOpacity === 0, `got ${p0.miniOpacity}`);
  ok(`${tag} 胶囊高度恒 32（--mcap-h）`, p0.miniH === CAP_H, `h=${p0.miniH}`);
  ok(`${tag} 胶囊垂直中心 40（= 返回钮中心）`, p0.miniCenterY === CAP_CENTER_Y, `cy=${p0.miniCenterY}`);
  ok(`${tag} 胶囊横向居中（页面中轴）`, near(p0.miniCenterX, p0.vw / 2, 1), `cx=${p0.miniCenterX} vw=${p0.vw}`);
  ok(`${tag} 胶囊右缘不越返回钮禁区（左缘 ≥ 72）`, p0.miniLeft >= INSET - 1, `left=${p0.miniLeft}`);
  ok(`${tag} 胶囊 15px / 全圆角 / 毛玻璃材质（backdrop-filter）`, p0.miniFS === '15px' && p0.miniRadius === '999px' && /blur/.test(p0.miniBlur), `${p0.miniFS} ${p0.miniRadius} ${p0.miniBlur.slice(0, 30)}`);
  ok(`${tag} 胶囊不拦事件（pointer-events none）`, p0.miniPE === 'none', p0.miniPE);
  ok(`${tag} 渐隐带节点退役（.mobile-page-title-band 计数 0）`, p0.bandCount === 0, `count=${p0.bandCount}`);
  ok(`${tag} 「<」返回钮在场且可点`, p0.backVisible && p0.backPE !== 'none', `pe=${p0.backPE}`);

  // 面内同名双标题退役（B 批配套，两段式批沿用）。
  const dups = await page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const found = [];
    ['.todo-page-title', '#usage-title', '#settings-modal-title', '#daemon-panel-title'].forEach((s) => {
      sec.querySelectorAll(s).forEach((el) => found.push({ sel: s, display: getComputedStyle(el).display }));
    });
    return found;
  }, pageId);
  for (const d of dups) ok(`${tag} 面内同名标题退役（${d.sel}）`, d.display === 'none', d.display);

  await page.screenshot({ path: join(SHOTS, `${tag}-rest.png`) });

  // ── P2 阈值 + 连续插值 ──
  const s40 = await setScroll(page, pageId, 40);
  const mid = await probe(page, pageId);
  // 期望进度按**实测**主滚轴 scrollTop 换算（WebKit 会把 scrollTop 吸附到整数/
  // 设备像素档，写 40 可能读 39 —— 断言口径以实测值为准，仍钉死插值公式）。
  const a40 = await readScrollTop(page, pageId);
  const p40 = expectP(a40.top);
  const range = await page.evaluate(({ pid, selSrc }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const t = eval(selSrc)(sec);
    return { max: t.scrollHeight - t.clientHeight };
  }, { pid: pageId, selSrc: PRIMARY_SEL_JS });
  ok(`${tag} 主滚轴余量 > 吸附阈值（夹具充足，实测 max=${range.max}）`, range.max > ROLL_SNAP + 40, `max=${range.max}`);
  ok(`${tag} 区间内（scrollTop ${a40.top}）进度 = ${p40}`, near(mid.p, p40, 0.02), `got p=${mid.p} @${s40.tag}`);
  ok(`${tag} 区间内不切 .rolled（${a40.top} < 56）`, !mid.rolled);
  ok(`${tag} 区间内大标题 opacity = 1−p（连续跟手）`, near(mid.titleOpacity, 1 - p40, 0.02), `got ${mid.titleOpacity}`);
  ok(`${tag} 区间内胶囊 opacity = p（连续跟手）`, near(mid.miniOpacity, p40, 0.02), `got ${mid.miniOpacity}`);
  ok(`${tag} 区间内胶囊几何不漂（h 32 / 中心 40）`, mid.miniH === CAP_H && mid.miniCenterY === CAP_CENTER_Y, `${mid.miniH}/${mid.miniCenterY}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-mid.png`) });

  const s300 = await setScroll(page, pageId, 300);
  const top = await probe(page, pageId);
  ok(`${tag} 阈值上（scrollTop 300）进度 = 1`, top.p === 1, `got p=${top.p} @${s300.tag}`);
  ok(`${tag} 阈值上切 .rolled`, top.rolled);
  ok(`${tag} 吸附态胶囊 opacity 1`, near(top.miniOpacity, 1, 0.01), `got ${top.miniOpacity}`);
  ok(`${tag} 吸附态大标题 opacity 0`, near(top.titleOpacity, 0, 0.01), `got ${top.titleOpacity}`);
  ok(`${tag} 吸附态大标题不拦交互（pointer-events none）`, top.titlePE === 'none', top.titlePE);
  ok(`${tag} 吸附态顶区零占位（大标题收高 → 0）`, top.titleH === 0, `h=${top.titleH}`);
  ok(`${tag} 吸附态胶囊几何 = 32 高 / 中心 40 / 左缘 ≥72`, top.miniH === CAP_H && top.miniCenterY === CAP_CENTER_Y && top.miniLeft >= INSET - 1, `${top.miniH}/${top.miniCenterY}/${top.miniLeft}`);
  ok(`${tag} 吸附态胶囊仍横向居中`, near(top.miniCenterX, top.vw / 2, 1), `cx=${top.miniCenterX}`);
  ok(`${tag} 吸附态返回钮仍在胶囊左侧且可点（z 6 > 5）`, top.backVisible && Number(top.backZ) > Number(top.miniZ), `backZ=${top.backZ} miniZ=${top.miniZ}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-snapped.png`) });

  // ── P3 真滚轮回顶（形态反向插值展开）──
  const box = await page.locator(hoverSel).first().boundingBox();
  if (box) {
    await page.mouse.move(Math.min(box.x + box.width / 2, VW / 2), Math.min(box.y + box.height / 2, 600));
    for (let i = 0; i < 6; i += 1) await page.mouse.wheel(0, -400);
    await page.waitForTimeout(320);
  }
  const back1 = await probe(page, pageId);
  const st = await readScrollTop(page, pageId);
  ok(`${tag} 滚轮回顶（真滚轮）大标题展开复现`, back1.p === 0 && !back1.rolled && near(back1.titleOpacity, 1, 0.01), `p=${back1.p} top=${st.top}@${st.tag}`);
  ok(`${tag} 回顶后胶囊退回 0（无残留）`, back1.miniOpacity === 0, `got ${back1.miniOpacity}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-backtop.png`) });
}

/* ── 主题腿（引擎 × scheme × 3 面板）────────────────────────────────── */
async function themeLeg(engineName, scheme) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    // ① 项目（pane 页：主滚轴 = .team-scroll，吸附后面内收高）。
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll .project-card, #mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    await panelLeg(page, `${tag}-projects`, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');

    // ② 设置（overlay 收编面：主滚轴 = #settings-content）。
    await openPage(page, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal');
    const setScrollable = await page.evaluate(() => {
      const sec = document.querySelector('#mobile-pages .mobile-page[data-page="settings"]');
      const c = sec.querySelector('#settings-content');
      return c ? c.scrollHeight - c.clientHeight : -1;
    });
    ok(`${tag}-settings 设置内容滚动域成立（夹具充足）`, setScrollable > 100, `surplus=${setScrollable}`);
    await panelLeg(page, `${tag}-settings`, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-content');

    // ③ 待办事项（in-flow 页：todo 几何 auto 后主滚轴 = .todo-page-body）。
    await openTodo(page);
    await panelLeg(page, `${tag}-todo`, 'todo', '#mobile-pages .mobile-page.live[data-page="todo"] #todo-page');

    // ── P4 面板切换对账：吸附态离场 → 下一面板静止态（不继承）──
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll .project-card, #mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    await setScroll(page, 'projects', 300);
    const pScrolled = await probe(page, 'projects');
    ok(`${tag} 切换前 projects 处于吸附态`, pScrolled.p === 1 && pScrolled.rolled);
    await page.locator('#mobile-pages .mobile-page[data-page="projects"] .mobile-back-btn').click();
    await page.waitForTimeout(350);
    const pAfter = await probe(page, 'projects');
    ok(`${tag} 返回后 projects 退场（.live 摘除）`, await page.evaluate(() => !document.querySelector('#mobile-pages .mobile-page[data-page="projects"]').classList.contains('live')));
    ok(`${tag} 退场后进度保留但不影响他页（离场态即 p=${pAfter.p}）`, pAfter.p === 1, `p=${pAfter.p}`);
    await openPage(page, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal');
    const sFresh = await probe(page, 'settings');
    ok(`${tag} 新进面板静止态（进场对账 p=0，不继承他页吸附）`, sFresh.p === 0 && !sFresh.rolled && sFresh.titleH === 52, `p=${sFresh.p} h=${sFresh.titleH}`);
  } finally {
    await page.close();
  }
}

/* ── reduced-motion 腿（chromium-light 一次）────────────────────────── */
async function reducedMotionLeg() {
  const page = await boot('light', 'chromium', { reducedMotion: true });
  try {
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    const samples = [];
    for (const v of [20, 30, 40, 50]) {
      await setScroll(page, 'projects', v);
      samples.push((await probe(page, 'projects')).p);
    }
    ok('reduced-motion 进度二值化（取样只会是 0 或 1）', samples.every((x) => x === 0 || x === 1), JSON.stringify(samples));
    ok('reduced-motion 区间前段即 0（scrollTop 20 = p 0.25 侧）', samples[0] === 0, JSON.stringify(samples));
    await setScroll(page, 'projects', 120);
    const hi = await probe(page, 'projects');
    ok('reduced-motion 阈值上瞬时成立（p=1）', hi.p === 1, `p=${hi.p}`);
    ok('reduced-motion 大标题无位移量（transform none）', hi.titleTransform === 'none' || hi.titleTransform === 'matrix(1, 0, 0, 1, 0, 0)', hi.titleTransform);
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
  ok('桌面腿 body.mobile-layer 不激活（390 折叠点外）', !d.mobileLayer);
  ok('桌面腿零页面壳/零标题节点（页面世界不建）', d.pageShells === 0 && d.titleNodes === 0, `shells=${d.pageShells} titles=${d.titleNodes}`);
  ok('桌面腿 #header 在场（桌面壳零变化）', d.headerShown);
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

ok('零 console error（双引擎×双主题全腿 + reduced-motion + 桌面腿，噪声过滤后硬零）', consoleErrors.length === 0,
  consoleErrors.slice(0, 5).join(' | '));

console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
