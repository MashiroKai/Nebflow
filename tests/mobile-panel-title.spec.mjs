#!/usr/bin/env node
// mobile-panel-title.spec.mjs — 面板头标识 B 批验收（作者 2026-11-01 17:01 选型
// B「iOS 大标题式」；样张 /Users/kaiyu/.stand/design-explore/panel-label-variants/
// README B 条目 + token 实测账本；旧「奶片」胶囊 mobileLayer.css L391-416 退役）。
//
// 覆盖（双引擎 Chromium/WebKit × 双主题 light/dark × 3 面板 × 两态）：
//   P1 展开态探针：大标题 = 页体流内首件（position:static），28px/600，
//      字距负档（-0.01em），色 = --color-frame-text-bright；收缩小字与顶缘
//      渐隐带 opacity 0；.rolled 不在场；「<」返回钮在场。
//   P2 阈值探针：主滚轴 scrollTop 40（< 56）不切态；120（> 56）切 .rolled
//      （navController.js bindRollProbe 阈值断言）。
//   P3 真滚轮交互：mouse.wheel 下滚 → 收缩态成立（小字居中 opacity 1 +
//      渐隐带 opacity 1 + 大标题 opacity 0）；回滚 → 展开态复现。
//   P4 截图存证：每面板展开态+收缩态各一拍（引擎×主题×面板 = 24 张）。
//   P5 零 console error（pageerror + console.error 硬零；资源噪声过滤同源
//      pop-footer-family.spec.mjs NOISE 表）。
//   P6 桌面零触碰腿：1280×800 下 body.mobile-layer 不激活、无页面壳节点。
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

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

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

/* ── 夹具：项目 12 行（projects 页滚动域）＋ 待办 12 行（todo 页滚动域）── */
const PROJECTS = Array.from({ length: 12 }, (_, i) => ({
  name: `fixture-project-${String(i + 1).padStart(2, '0')}`,
  workspace: `/tmp/mpt/proj-${i + 1}`,
  agentFile: '',
  description: i % 2 === 0 ? '验收夹具项目（滚动域填充行）' : '',
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
const TODOS = Array.from({ length: 12 }, (_, i) => ({
  id: `mpt-td-${i + 1}`,
  kind: 'todo',
  content: `验收夹具待办 ${i + 1}：滚动收缩域填充行`,
  triggerAt: Date.now() + 3600_000,
}));

/* ── boot（route 静态 + WS 夹具 + /api 夹具；390×844 手机视口）────────── */
async function boot(scheme, engineName) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const page = await (await engine.launch()).newContext({
    viewport: { width: 390, height: 844 },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
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
        // scope:'all' = 跨会话收件箱（todo 页）；否则 = 单会话提醒面（也喂行，
        // 防定时任务页空态干扰——本 spec 只进 todo 一面，两腿都给足行）。
        const scope = m.scope === 'all' ? 'all' : 'session';
        const tasks = TODOS.map((t) => ({ ...t }));
        ws.send(JSON.stringify({ type: 'scheduledTaskList', scope, tasks }));
      }
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  // 手机层激活（bootstrap.js matchMedia ≤768）。
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
  // 逐层弹栈到聊天根（home）再进工作台。
  for (let i = 0; i < 6; i += 1) {
    const onHome = await page.evaluate(() => {
      const nav = document.querySelector('body.mobile-layer');
      return !document.querySelector('#mobile-pages .mobile-page.live[data-page]:not([data-page="messages"]):not([data-page="contacts"]):not([data-page="canvas"]):not([data-page="workbench"])');
    });
    if (onHome) break;
    await page.waitForTimeout(120);
  }
  const dockKey = page.locator('#mobile-dock .dock-key[data-dock="workbench"]');
  await dockKey.click();
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="workbench"] .mf-hub-rows', { timeout: 10000 });
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

/* ── 探针：读页面壳标识态 ─────────────────────────────────────────────── */
async function probe(page, pageId) {
  return page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    if (!sec) return { found: false };
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const title = sec.querySelector('.mobile-page-title');
    const mini = sec.querySelector('.mobile-page-title-mini');
    const band = sec.querySelector('.mobile-page-title-band');
    const back = sec.querySelector('.mobile-back-btn');
    const t = cs(title); const m = cs(mini); const b = cs(band);
    const tokenProbe = document.createElement('span');
    tokenProbe.style.color = 'var(--color-frame-text-bright)';
    document.body.appendChild(tokenProbe);
    const ink = getComputedStyle(tokenProbe).color;
    tokenProbe.remove();
    const scroller = title ? title.parentElement : null; // .mobile-page-body
    return {
      found: true,
      rolled: sec.classList.contains('rolled'),
      titleText: title?.textContent || '',
      titlePos: t.position,
      titleFontSize: t.fontSize,
      titleWeight: t.fontWeight,
      titleSpacing: parseFloat(t.letterSpacing),
      titleColor: t.color,
      ink,
      titleTop: title ? Math.round(title.getBoundingClientRect().top) : -1,
      titleOpacity: t.opacity,
      titlePE: t.pointerEvents,
      miniOpacity: m.opacity,
      miniTop: m ? Math.round(parseFloat(m.top)) : -1,
      bandOpacity: b.opacity,
      bandImage: b.backgroundImage || '',
      backVisible: !!back && cs(back).display !== 'none',
      bodyScrollable: scroller ? scroller.scrollHeight - scroller.clientHeight : -1,
    };
  }, pageId);
}

/** 主滚轴 scrollTop 精调 + 滚动事件派发（阈值探针腿）。 */
async function setScroll(page, pageId, value) {
  return page.evaluate(({ pid, v }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const bodyEl = sec.querySelector('.mobile-page-body');
    const scrollables = [bodyEl];
    sec.querySelectorAll('*').forEach((el) => {
      const oy = getComputedStyle(el).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight - el.clientHeight > 4) scrollables.push(el);
    });
    const target = scrollables[scrollables.length - 1]; // 最外层纵向滚动器 = 主滚轴
    target.scrollTop = v;
    target.dispatchEvent(new Event('scroll', { bubbles: false }));
    return { tag: target.className || target.id || target.tagName, top: target.scrollTop };
  }, { pid: pageId, v: value });
}

/** 主滚轴读数（滚轮腿的前后态核对）。 */
async function readScrollTop(page, pageId) {
  return page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const bodyEl = sec.querySelector('.mobile-page-body');
    let target = bodyEl;
    sec.querySelectorAll('*').forEach((el) => {
      const oy = getComputedStyle(el).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight - el.clientHeight > 4) target = el;
    });
    return { tag: target.className || target.id || target.tagName, top: target.scrollTop };
  }, pageId);
}

const num = (v) => parseFloat(String(v));

/* ── 单面板全腿（展开探针 → 阈值探针 → 滚轮交互 → 双态截图）──────────── */
async function panelLeg(page, tag, pageId, hoverSel) {
  const p0 = await probe(page, pageId);
  ok(`${tag} 页面壳在场`, p0.found);
  ok(`${tag} 展开态 .rolled 不在场`, p0.found && !p0.rolled);
  ok(`${tag} 大标题文案 = 面板现名`, p0.titleText.length > 0, `text="${p0.titleText}"`);
  ok(`${tag} 大标题在流内（position:static，奶片 absolute 退役）`, p0.titlePos === 'static', `got ${p0.titlePos}`);
  ok(`${tag} 大标题 28px/600（作者令破档档位）`, p0.titleFontSize === '28px' && p0.titleWeight === '600', `got ${p0.titleFontSize}/${p0.titleWeight}`);
  ok(`${tag} 大标题字距负档（-0.01em）`, p0.titleSpacing < 0, `got ${p0.titleSpacing}px`);
  ok(`${tag} 大标题墨色 = --color-frame-text-bright`, p0.titleColor === p0.ink, `got ${p0.titleColor} / token ${p0.ink}`);
  ok(`${tag} 大标题可见（opacity 1）`, num(p0.titleOpacity) === 1, `got ${p0.titleOpacity}`);
  ok(`${tag} 收缩小字承位（top ≈ 40+safe，本视口 safe=0）`, Math.abs(p0.miniTop - 40) <= 1, `got ${p0.miniTop}`);
  ok(`${tag} 展开态收缩小字 opacity 0`, num(p0.miniOpacity) === 0, `got ${p0.miniOpacity}`);
  ok(`${tag} 展开态渐隐带 opacity 0`, num(p0.bandOpacity) === 0, `got ${p0.bandOpacity}`);
  ok(`${tag} 渐隐带承底 = --g2-veil 渐变（现役 token）`, p0.bandImage.includes('linear-gradient'), p0.bandImage.slice(0, 60));
  ok(`${tag} 「<」返回钮在场`, p0.backVisible);

  // 面内同名双标题退役（B 批配套②）：与本面板壳名逐字同名的面内标题全隐。
  const dups = await page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const found = [];
    ['.todo-page-title', '#usage-title', '#settings-modal-title', '#daemon-panel-title'].forEach((s) => {
      sec.querySelectorAll(s).forEach((el) => found.push({ sel: s, display: getComputedStyle(el).display }));
    });
    return found;
  }, pageId);
  for (const d of dups) ok(`${tag} 面内同名标题退役（${d.sel}）`, d.display === 'none', d.display);

  await page.screenshot({ path: join(SHOTS, `${tag}-expanded.png`) });

  // P2 阈值探针：40 不切态 / 120 切态。
  await setScroll(page, pageId, 40);
  await page.waitForTimeout(60);
  const below = await probe(page, pageId);
  ok(`${tag} 阈值下（scrollTop 40 < 56）不切态`, !below.rolled);
  await setScroll(page, pageId, 120);
  await page.waitForTimeout(320); // 160ms 两态过渡收敛
  const above = await probe(page, pageId);
  ok(`${tag} 阈值上（scrollTop 120 > 56）切 .rolled`, above.rolled);
  ok(`${tag} 收缩态小字 opacity 1`, num(above.miniOpacity) === 1, `got ${above.miniOpacity}`);
  ok(`${tag} 收缩态渐隐带 opacity 1`, num(above.bandOpacity) === 1, `got ${above.bandOpacity}`);
  ok(`${tag} 收缩态大标题 opacity 0`, num(above.titleOpacity) === 0, `got ${above.titleOpacity}`);
  ok(`${tag} 收缩态大标题不拦交互（pointer-events none）`, above.titlePE === 'none', `got ${above.titlePE}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-rolled.png`) });

  // P3 真滚轮回弹（wheel 至顶 → 展开态复现）。
  await setScroll(page, pageId, 300);
  await page.waitForTimeout(280);
  const box = await page.locator(hoverSel).first().boundingBox();
  if (box) {
    await page.mouse.move(box.x + box.width / 2, Math.min(box.y + box.height / 2, 700));
    await page.mouse.wheel(0, -600);
    await page.waitForTimeout(320);
  }
  const back1 = await probe(page, pageId);
  const st = await readScrollTop(page, pageId);
  ok(`${tag} 滚轮回顶（真滚轮）展开态复现`, !back1.rolled && st.top === 0, `scrolled=${st.tag}:${st.top}`);
}

/* ── 主题腿（引擎 × scheme × 3 面板）────────────────────────────────── */
async function themeLeg(engineName, scheme) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    // ① 项目（pane 页：主滚轴 = .team-scroll，大标题随滚走真流内）。
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

    // ③ 待办事项（in-flow 页：todo 几何 auto 后主滚轴 = 页面体）。
    await openTodo(page);
    await panelLeg(page, `${tag}-todo`, 'todo', '#mobile-pages .mobile-page.live[data-page="todo"] #todo-page');
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

/* ── 主流程：双引擎 × 双主题 + 桌面腿 + 零 console error 总门 ──────────── */
console.log(`evidence → ${OUT}`);
for (const engineName of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await themeLeg(engineName, scheme);
  }
}
await desktopLeg();

ok('零 console error（双引擎×双主题全腿 + 桌面腿，噪声过滤后硬零）', consoleErrors.length === 0,
  consoleErrors.slice(0, 5).join(' | '));

console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
