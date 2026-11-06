// _panel-three-gen-shots.mjs — 面板代际层级批实拍脚本（门禁证据批 2026-11-06）。
// 用法：node tests/_panel-three-gen-shots.mjs --mode=threegen|realform --out=<abs-dir> --tag=before|after
//   threegen = 三层夹具（root 锚 + dispatcher + node，parentSessionId 属主边——后端 b3e8da634 注册形）
//   realform = 真实形夹具（同结构但无 parentSessionId——现网数据面形状）
// 产物：<out>/<tag>-{375,1440}-{light,dark}.png 八张。零后端、零 6138（页面路由全 mock）。

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'threegen-root-session';
const DISP_SID = 'dispatcher-3gen-a001';
const NODE_SID = 'node-3gen-b002';
const T0 = Date.now();

const argv = Object.fromEntries(process.argv.slice(2).map((a) => {
  const m = a.match(/^--([^=]+)=(.*)$/); return m ? [m[1], m[2]] : [a, true];
}));
const MODE = argv.mode || 'threegen';
const OUT = argv.out;
const TAG = argv.tag || 'shot';
if (!OUT) { console.error('need --out=<abs-dir>'); process.exit(2); }

function rootStart(sessionId, over = {}) {
  const base = {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    status: 'Processing',
  };
  if (MODE === 'threegen') {
    // 属主边（后端注册形状）：dispatcher 父=桶根；node 父=dispatcher。
    if (sessionId === DISP_SID) base.parentSessionId = ROOT_SID;
    if (sessionId === NODE_SID) base.parentSessionId = DISP_SID;
    if (sessionId === DISP_SID) base.name = 'dispatcher/Nebflow';
    if (sessionId === NODE_SID) base.name = 'stand';
  }
  return { ...base, ...over };
}

async function installRoutes(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
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
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

async function bootApp(page) {
  await installRoutes(page);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

async function openPanel(page, mobile) {
  if (!mobile) {
    await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
    await page.click('#bgagent-indicator');
    await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
    await page.waitForTimeout(300);
    return;
  }
  // 手机档：交互层自动激活 → navController.pushPage('background') 收编 #bg-dropdown
  // （与 spec G1 375 腿同一条缝——同一面板实现，手机真身 = 后台页体）。
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 8000 });
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage('background');
    const m = await import('/js/main/bgAgents.js');
    m.fetchActiveAgentsOnOpen();
    m.renderBgAgentDropdown();
  });
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(300);
}

const browser = await chromium.launch();
const shots = [
  { w: 375, h: 812, scheme: 'light', name: '375-light' },
  { w: 375, h: 812, scheme: 'dark', name: '375-dark' },
  { w: 1440, h: 900, scheme: 'light', name: '1440-light' },
  { w: 1440, h: 900, scheme: 'dark', name: '1440-dark' },
];
for (const s of shots) {
  const ctx = await browser.newContext({
    viewport: { width: s.w, height: s.h }, colorScheme: s.scheme, deviceScaleFactor: 2,
  });
  const page = await ctx.newPage();
  try {
    await bootApp(page);
    await inject(page, rootStart(DISP_SID, { taskDescription: MODE === 'threegen' ? '分发器代' : '分发器代（真实形：无属主边）' }));
    await inject(page, rootStart(NODE_SID, { taskDescription: '节点代' }));
    await page.evaluate(async ({ r, d, n }) => {
      const st = (await import('/js/state.js')).default;
      const b = st.sessionBgAgents[r] || {};
      if (b[d]) b[d].startedAt = 1000;
      if (b[n]) b[n].startedAt = 2000;
    }, { r: ROOT_SID, d: DISP_SID, n: NODE_SID });
    await openPanel(page, s.w <= 480);
    const out = join(OUT, `${TAG}-${s.name}.png`);
    await page.screenshot({ path: out, fullPage: false });
    console.log(`SHOT ${out}`);
  } catch (e) {
    console.error(`FAIL ${TAG}-${s.name}: ${e.message.split('\n')[0]}`);
    process.exitCode = 1;
  } finally {
    await ctx.close();
  }
}
await browser.close();
