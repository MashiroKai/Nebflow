#!/usr/bin/env node
// mobile-avatar-chip-title-gap.spec.mjs — 默认头像主题化+居中 & 面板标题-内容间距
// 修整批验收（作者令 2026-11-01 20:42 手机端截图反馈两问题；kernel 单会话）。
//
// 修整面：
//   ① agent 默认头像（无自定义头像时的 stand logo 兜底）随主题换色：
//      浅色主题 = 白底黑 logo / 深色主题 = 黑底白 logo（stand-tokens.css
//      --agent-avatar-chip-bg，配对 = brand app-icon 亮/暗档）。消费者 =
//      会话头 #agent-avatar-btn（桌面 28px / 手机 78px 同规则）+ 档案预览盒
//      .ap-avatar-box + dock 中键 logo 态（:has 限定，自定义头像态 sapphire 环
//      零回归）。logo 黑白翻面 = brand 双主题 picture（prefers-color-scheme）。
//   ② dock 中键 logo 居中修正：裸 inline <picture> 百分比双基准问题（修前像素
//      实证墨迹 left 2 / right 17.5，盒 left 0 / right 16.7）——尺寸上移
//      picture（62%）+ flex 双轴居中（会话头同款成熟模式）。
//   ③ 面板「标题→首个内容块」间距收整（手机档）：usage 双层垫 24→12；
//      projects 桌面胶囊让位带 68→12（pt56/top56 双维归零）；大标题 flex:none
//      防收编面争抢收缩（设置页标题盒被压 31px 裁字修红）。全站节奏 12px。
//
// 覆盖（双引擎 Chromium/WebKit × 双主题 light/dark × 手机 390×844）：
//   A1 会话头芯片：底色随主题黑白翻转；logo src 随主题 light/dark 换源；
//      img 盒四向边距等分（居中）；芯片圆形。
//   A2 dock 中键：芯片底色随主题（logo 态）；元素截图墨迹扫描 = 墨心偏移
//      ≤1.5px、左右墨距差 ≤2px（居中硬证）；SVG 换源随主题。
//   B1 标题-内容间距 sweep（9 面）：usage/projects 首块 12±2；todo 14±2；
//      settings/daemon/reminder/plugins/background/me ≤16（首可见件）。
//   B2 标题盒恒 52±2（flex:none 防裁字；设置页修红点专项）。
//   B3 截图存证：usage/projects/settings/todo 展开态 ×引擎×主题。
//   C1 自定义头像零回归（chromium-light 腿）：注入头像 → 会话头 has-img；
//      dock 显示照片且底色保持 sapphire 族（非黑白芯片）。
//   C2 桌面零回归腿（1280×800）：mobile-layer 不激活；桌面头芯片随主题翻转 +
//      logo 居中（规则全局生效为有意）；桌面 projects 让位带 56px 原值
//      （手机档限定不越界）。
//   D1 零 console error（全腿，噪声过滤同族 spec）。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/mobile-avatar-chip-title-gap.spec.mjs
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.AVOUT_DIR || join(HERE, '..', 'evidence', `${TS}_avatar-chip-title-gap`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'avatar-chip-gap-root';
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

/* ── 夹具 ── */
const PROJECTS = Array.from({ length: 8 }, (_, i) => ({
  name: `fixture-project-${String(i + 1).padStart(2, '0')}`,
  workspace: `/tmp/avchip/proj-${i + 1}`,
  agentFile: '',
  description: i % 2 === 0 ? '验收夹具项目（间距 sweep 行）' : '',
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
const TODOS = Array.from({ length: 10 }, (_, i) => ({
  id: `avchip-td-${i + 1}`,
  kind: 'todo',
  content: `验收夹具待办 ${i + 1}：标题间距 sweep 行`,
  triggerAt: Date.now() + 3600_000,
}));
// 1×1 红点 PNG（自定义头像零回归腿的注入字节）。
const AVATAR_PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

/* ── boot（route 静态 + WS 夹具 + /api 夹具；390×844 手机视口）────────── */
async function boot(scheme, engineName) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const browser = await engine.launch();
  const page = await browser.newContext({
    viewport: { width: 390, height: 844 },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
  page.browser = browser;
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
        const tasks = TODOS.map((t) => ({ ...t }));
        ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: m.scope === 'all' ? 'all' : 'session', tasks }));
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

/* ── 探针：会话头 + dock 中键芯片/居中读数 ───────────────────────────── */
async function avatarProbe(page) {
  return page.evaluate(() => {
    const out = {};
    const btn = document.querySelector('#agent-avatar-btn');
    const r = btn.getBoundingClientRect();
    const cs = getComputedStyle(btn);
    out.header = {
      w: Math.round(r.width), h: Math.round(r.height),
      radius: cs.borderRadius, bg: cs.backgroundColor, bgImg: cs.backgroundImage,
      picDisplay: null, margins: null, logoSrc: null,
    };
    const pic = btn.querySelector('.agent-avatar-stand-logo');
    const logo = btn.querySelector('.agent-avatar-logo-img');
    if (pic && logo) {
      const pcs = getComputedStyle(pic);
      const lr = logo.getBoundingClientRect();
      out.header.picDisplay = pcs.display;
      out.header.margins = {
        top: +(lr.top - r.top).toFixed(1), bottom: +(r.bottom - lr.bottom).toFixed(1),
        left: +(lr.left - r.left).toFixed(1), right: +(r.right - lr.right).toFixed(1),
      };
      out.header.logoSrc = (logo.currentSrc || logo.src || '').split('/').pop();
    }
    const dock = document.querySelector('.dock-key-center .dock-center');
    if (dock) {
      const dr = dock.getBoundingClientRect();
      const dcs = getComputedStyle(dock);
      out.dock = { w: Math.round(dr.width), bg: dcs.backgroundColor, hasImg: !!dock.querySelector('.dock-center-img') };
      const dl = dock.querySelector('.dock-center-logo');
      if (dl) {
        const lr = dl.getBoundingClientRect();
        out.dock.logoSrc = (dl.currentSrc || dl.src || '').split('/').pop();
        out.dock.boxMargins = {
          top: +(lr.top - dr.top).toFixed(1), bottom: +(dr.bottom - lr.bottom).toFixed(1),
          left: +(lr.left - dr.left).toFixed(1), right: +(dr.right - lr.right).toFixed(1),
        };
      }
    }
    return out;
  });
}

/* dock 中键元素截图墨迹扫描（居中硬证）：圆底 bbox vs logo 墨迹 bbox。 */
async function dockInkScan(page, tag) {
  const buf = await page.locator('.dock-key.dock-key-center').screenshot();
  const b64 = buf.toString('base64');
  return page.evaluate(async (b) => {
    const img = new Image();
    await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = 'data:image/png;base64,' + b; });
    const c = document.createElement('canvas');
    c.width = img.naturalWidth; c.height = img.naturalHeight;
    const g = c.getContext('2d');
    g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, c.width, c.height).data;
    const W = c.width, H = c.height;
    const isDarkChip = document.querySelector('.dock-key-center .dock-center');
    const chipDark = getComputedStyle(isDarkChip).backgroundColor === 'rgb(0, 0, 0)';
    let cMinX = 1e9, cMinY = 1e9, cMaxX = -1, cMaxY = -1; // 芯片圆底
    let iMinX = 1e9, iMinY = 1e9, iMaxX = -1, iMaxY = -1; // logo 墨迹
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const p = (y * W + x) * 4;
        const r = d[p], gg = d[p + 1], bb = d[p + 2];
        // 芯片底：亮档 = 近白（且非透明）；暗档 = 近黑。logo 墨 = 对反色。
        const chip = chipDark ? (r < 40 && gg < 40 && bb < 40) : (r > 215 && gg > 215 && bb > 215);
        const ink = chipDark ? (r > 200 && gg > 200 && bb > 200) : (r < 70 && gg < 70 && bb < 90);
        if (chip) { if (x < cMinX) cMinX = x; if (x > cMaxX) cMaxX = x; if (y < cMinY) cMinY = y; if (y > cMaxY) cMaxY = y; }
        if (ink) { if (x < iMinX) iMinX = x; if (x > iMaxX) iMaxX = x; if (y < iMinY) iMinY = y; if (y > iMaxY) iMaxY = y; }
      }
    }
    if (cMaxX < 0 || iMaxX < 0) return { found: false };
    const ccx = cMinX + (cMaxX - cMinX) / 2, ccy = cMinY + (cMaxY - cMinY) / 2;
    const icx = iMinX + (iMaxX - iMinX) / 2, icy = iMinY + (iMaxY - iMinY) / 2;
    const f = (v) => +(v / 2).toFixed(1); // devicePixelRatio 2 → 逻辑 px
    return {
      found: true,
      chipDark,
      inkCenterOffset: { dx: +(f(icx - ccx)), dy: +(f(icy - ccy)) },
      inkMargins: { left: f(iMinX - cMinX), right: f(cMaxX - iMaxX), top: f(iMinY - cMinY), bottom: f(cMaxY - iMaxY) },
    };
  }, b64).then((r) => { void tag; return r; });
}

/* ── 导航（真实 DOM 点击路径）────────────────────────────────────────── */
async function gotoWorkbench(page) {
  await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page.live');
    if (sec) sec.querySelectorAll('.mobile-back-btn').forEach((b) => b.click());
  });
  for (let i = 0; i < 6; i += 1) {
    const onHome = await page.evaluate(() => {
      return !document.querySelector('#mobile-pages .mobile-page.live[data-page]:not([data-page="messages"]):not([data-page="contacts"]):not([data-page="canvas"]):not([data-page="workbench"])');
    });
    if (onHome) break;
    await page.waitForTimeout(120);
  }
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="workbench"] .mf-hub-rows', { timeout: 10000 });
}

async function openHubPage(page, hubPage, liveSelector) {
  await gotoWorkbench(page);
  await page.locator(`#mobile-pages .mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${hubPage}"]`).click();
  await page.waitForSelector(liveSelector, { timeout: 15000 });
  await page.waitForTimeout(450);
}

async function openTodo(page) {
  await gotoWorkbench(page);
  await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:mobile-todo-entry')));
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="todo"] #todo-page .reminder-row', { timeout: 15000 });
  await page.waitForTimeout(450);
}

/* 标题→首个可见内容块间距（首可见件 = 页面体/收编面内首个 h>4 且非隐藏元素）。 */
async function gapProbe(page, pageId, firstSel) {
  return page.evaluate(({ pid, sel }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    if (!sec) return { found: false };
    const title = sec.querySelector('.mobile-page-title');
    if (!title) return { found: false };
    const tb = title.getBoundingClientRect();
    let first = null;
    if (sel) {
      first = sec.querySelector(sel);
    } else {
      const root = sec.querySelector('.mobile-page-body');
      const scan = (parent) => {
        for (const el of parent.children) {
          const s = getComputedStyle(el);
          if (s.display === 'none' || s.visibility === 'hidden') continue;
          const r = el.getBoundingClientRect();
          if (r.height > 4) { return el; }
          const deep = scan(el);
          if (deep) return deep;
        }
        return null;
      };
      first = scan(root);
    }
    if (!first) return { found: false, titleBottom: Math.round(tb.bottom) };
    const fr = first.getBoundingClientRect();
    return {
      found: true,
      titleText: title.textContent,
      titleH: Math.round(tb.height),
      titleBottom: Math.round(tb.bottom),
      firstTag: `${first.tagName.toLowerCase()}${first.id ? '#' + first.id : ''}.${String(first.className).split(' ').slice(0, 2).join('.')}`,
      firstTop: Math.round(fr.top),
      gap: Math.round(fr.top - tb.bottom),
    };
  }, { pid: pageId, sel: firstSel || null });
}

/* ── A 组：默认头像（每引擎×主题一次；聊天 home 即会话头，dock 常驻）──── */
async function avatarLeg(engineName, scheme) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    const chipBg = scheme === 'light' ? 'rgb(255, 255, 255)' : 'rgb(0, 0, 0)';
    const logoSrc = scheme === 'light' ? 'symbol-light.svg' : 'symbol-dark.svg';

    const a = await avatarProbe(page);
    ok(`${tag} A1 会话头芯片底色随主题（${scheme}）`, a.header && a.header.bg === chipBg && a.header.bgImg === 'none',
      `bg=${a.header && a.header.bg} img=${a.header && a.header.bgImg}`);
    ok(`${tag} A1 会话头 logo 换源随主题`, a.header && a.header.logoSrc === logoSrc, `src=${a.header && a.header.logoSrc}`);
    ok(`${tag} A1 会话头芯片圆形`, a.header && a.header.radius === '50%', a.header && a.header.radius);
    const m = a.header && a.header.margins;
    ok(`${tag} A1 会话头 img 盒四向边距等分（±1px）`,
      m && Math.abs(m.top - m.bottom) <= 1 && Math.abs(m.left - m.right) <= 1 && Math.abs(m.top - m.left) <= 1.6,
      m && JSON.stringify(m));
    ok(`${tag} A1 会话头 picture flex 居中档`, a.header && a.header.picDisplay === 'flex', a.header && a.header.picDisplay);

    ok(`${tag} A2 dock 芯片底色随主题（logo 态）`, a.dock && a.dock.bg === chipBg, a.dock && a.dock.bg);
    ok(`${tag} A2 dock logo 换源随主题`, a.dock && a.dock.logoSrc === logoSrc, a.dock && a.dock.logoSrc);
    const dm = a.dock && a.dock.boxMargins;
    ok(`${tag} A2 dock img 盒四向边距等分（±1px）`,
      dm && Math.abs(dm.top - dm.bottom) <= 1 && Math.abs(dm.left - dm.right) <= 1 && Math.abs(dm.top - dm.left) <= 1.6,
      dm && JSON.stringify(dm));

    const scan = await dockInkScan(page, tag);
    ok(`${tag} A2 dock 墨迹扫描有解`, scan.found);
    ok(`${tag} A2 dock 墨心偏移 ≤1.5px（水平/垂直）`,
      scan.found && Math.abs(scan.inkCenterOffset.dx) <= 1.5 && Math.abs(scan.inkCenterOffset.dy) <= 1.5,
      scan.found && JSON.stringify(scan.inkCenterOffset));
    ok(`${tag} A2 dock 左右墨距差 ≤2px`,
      scan.found && Math.abs(scan.inkMargins.left - scan.inkMargins.right) <= 2,
      scan.found && JSON.stringify(scan.inkMargins));

    await page.screenshot({ path: join(SHOTS, `${tag}-chat-home-avatar.png`) });

    // B3 截图存证 + B1/B2 间距 sweep（同腿顺路）。
    // 断言口径（作者令「标题→首个内容块」）：usage/projects = 纯内容首块精确档
    // 12±2（修红点）；settings/daemon/reminder/todo = 功能控制行（B 批「计数/
    // 新建/关闭功能钮全留」）首可见件 ≤16；plugins/background = 内容面首块 ≤16。
    const gapTargets = [
      ['usage', 'usage', '#usage-dashboard .ud-filters'],
      ['projects', 'projects', '.team-scroll .project-card'],
      ['settings', 'settings', '#settings-content .settings-section'],
      ['todo', null, '#todo-page .todo-page-header'],
      ['plugins', 'plugins', '#plugins-content'],
      ['daemon', 'daemon', '#daemon-panel .daemon-panel-header'],
      ['reminder', 'reminder', '#reminder-panel .reminder-panel-header'],
      ['background', 'background', '#bg-dropdown .bg-section'],
    ];
    const expect = { usage: 12, projects: 12 };
    for (const [pid, hub, firstSel] of gapTargets) {
      if (hub) await openHubPage(page, hub, `#mobile-pages .mobile-page.live[data-page="${pid}"]`);
      else await openTodo(page);
      const g = await gapProbe(page, pid, firstSel);
      ok(`${tag} B1 ${pid} 面板在场`, g.found);
      if (!g.found) continue;
      ok(`${tag} B2 ${pid} 标题盒 52±2（防裁字）`, Math.abs(g.titleH - 52) <= 2, `h=${g.titleH}`);
      if (pid in expect) {
        ok(`${tag} B1 ${pid} 标题→首块 = ${expect[pid]}±2px`, Math.abs(g.gap - expect[pid]) <= 2,
          `${g.firstTag} gap=${g.gap}`);
      } else {
        ok(`${tag} B1 ${pid} 标题→首可见件 ≤16px`, g.gap <= 16, `${g.firstTag} gap=${g.gap}`);
      }
      if (['usage', 'projects', 'settings', 'todo'].includes(pid)) {
        await page.screenshot({ path: join(SHOTS, `${tag}-${pid}-expanded.png`) });
      }
    }
  } finally {
    await page.browser.close();
  }
}

/* ── C1 自定义头像零回归（chromium-light 一次）────────────────────────── */
async function customAvatarLeg() {
  const page = await boot('light', 'chromium');
  try {
    // 注入 root agent 自定义头像（localStorage 同键：branding.key 前缀 + agent_avatar_Nebula）。
    await page.evaluate((dataUrl) => {
      localStorage.setItem('stand_agent_avatar_Nebula', JSON.stringify({ dataUrl, sha: 'e2e-fix', at: Date.now() }));
    }, AVATAR_PNG);
    await page.reload();
    await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
    await page.waitForTimeout(900);
    const a = await avatarProbe(page);
    ok('C1 会话头自定义头像态 has-img（logo 节点让位）',
      a.header && a.header.logoSrc === null, `logoSrc=${a.header && a.header.logoSrc}`);
    ok('C1 dock 自定义头像照片在场', a.dock && a.dock.hasImg === true);
    ok('C1 dock 自定义头像态底色保持 sapphire 族（零回归）',
      a.dock && a.dock.bg !== 'rgb(255, 255, 255)' && a.dock.bg !== 'rgb(0, 0, 0)', a.dock && a.dock.bg);
    await page.screenshot({ path: join(SHOTS, 'chromium-light-custom-avatar.png') });
  } finally {
    await page.browser.close();
  }
}

/* ── C2 桌面零回归腿（1280×800，light+dark）──────────────────────────── */
async function desktopLeg(scheme) {
  const browser = await chromium.launch();
  const page = await browser.newContext({
    viewport: { width: 1280, height: 800 }, colorScheme: scheme, locale: 'zh-CN',
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
  const chipBg = scheme === 'light' ? 'rgb(255, 255, 255)' : 'rgb(0, 0, 0)';
  const d = await page.evaluate(() => {
    const btn = document.querySelector('#agent-avatar-btn');
    const r = btn.getBoundingClientRect();
    const cs = getComputedStyle(btn);
    const logo = btn.querySelector('.agent-avatar-logo-img');
    let margins = null;
    let src = null;
    if (logo) {
      const lr = logo.getBoundingClientRect();
      margins = {
        top: +(lr.top - r.top).toFixed(1), bottom: +(r.bottom - lr.bottom).toFixed(1),
        left: +(lr.left - r.left).toFixed(1), right: +(r.right - lr.right).toFixed(1),
      };
      src = (logo.currentSrc || logo.src || '').split('/').pop();
    }
    // 桌面 projects 让位带原值断言：桌面 pane 节点随页签惰性建（夹具桌面不开页），
    // 以同选择器探针子树读规则值（projectPanel.css 规则无作用域前缀，任意挂点即真值；
    // 手机档覆盖挂在 body.mobile-layer #mobile-pages 页面体链上，桌面视口不可能命中）。
    const pane = document.querySelector('.canvas-tab-pane[data-type="projects"]');
    let panePad = pane ? getComputedStyle(pane).paddingTop : null;
    let tsTop = null;
    if (!pane) {
      const probePane = document.createElement('div');
      probePane.className = 'canvas-tab-pane';
      probePane.dataset.type = 'projects';
      const probeTs = document.createElement('div');
      probeTs.className = 'team-scroll';
      probePane.appendChild(probeTs);
      document.body.appendChild(probePane);
      panePad = getComputedStyle(probePane).paddingTop;
      tsTop = getComputedStyle(probeTs).top;
      probePane.remove();
    } else {
      const ts = pane.querySelector('.team-scroll');
      tsTop = ts ? getComputedStyle(ts).top : null;
    }
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      bg: cs.backgroundColor, margins, src,
      paneFound: true, panePad, tsTop,
    };
  });
  ok(`桌面腿(${scheme}) body.mobile-layer 不激活`, !d.mobileLayer);
  ok(`桌面腿(${scheme}) 桌面头芯片随主题翻转`, d.bg === chipBg, d.bg);
  ok(`桌面腿(${scheme}) 桌面头 logo 居中（盒四向 ±1.6px）`,
    d.margins && Math.abs(d.margins.top - d.margins.bottom) <= 1 && Math.abs(d.margins.left - d.margins.right) <= 1 && Math.abs(d.margins.top - d.margins.left) <= 1.6,
    d.margins && JSON.stringify(d.margins));
  ok(`桌面腿(${scheme}) 桌面 projects 让位带 56px 原值（手机档限定不越界）`,
    d.paneFound && d.panePad === '56px' && d.tsTop === '56px', `pad=${d.panePad} top=${d.tsTop}`);
  await page.screenshot({ path: join(SHOTS, `desktop-${scheme}-header.png`) });
  await page.close();
  await browser.close();
}

/* ── 主流程：双引擎 × 双主题 + 自定义头像腿 + 桌面腿 + 零 error 总门 ── */
console.log(`evidence → ${OUT}`);
for (const engineName of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await avatarLeg(engineName, scheme);
  }
}
await customAvatarLeg();
await desktopLeg('light');
await desktopLeg('dark');

ok('零 console error（双引擎×双主题全腿 + 自定义头像腿 + 桌面腿，噪声过滤后硬零）', consoleErrors.length === 0,
  consoleErrors.slice(0, 5).join(' | '));

console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
