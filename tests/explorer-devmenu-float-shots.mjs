#!/usr/bin/env node
// explorer-devmenu-float-shots.mjs — 文件浏览器设备菜单「弹层浮出批」（2026-11-01
// 作者令「设备选择弹层三处一起修」）实拍 harness。
//
// 作者令三处：① 设备名截断（头部 chip + 下拉行）→ 全显/自适应宽度；
// ② 弹层被裁切、说明文案压文件列表 → body 级 fixed 浮层（逃 overflow:hidden 链
//   + #sidebar backdrop-filter 困采样）；③ 弹层改毛玻璃（glass.css 单源）。
// 验收口径：双引擎（chromium/webkit）× 深/浅主题实拍（最长设备名全显帧 + 弹层浮
// 在文件列表之上帧），零 console error。
//
// 形态：静态面走本机真实 http.server（index.html 自带 boot 门脚本对逐条
// stylesheet 做三态判定，满载机上 Playwright route 截胡会触发门脚本整页
// reload、打断 goto——实测；真 server 供静态 = 零截胡确定性），page.route
// 只截 /api（花名册夹具），routeWebSocket 注答 ws 帧。🔴 零后端进程、
// 零真实端口；花名册夹具用真实设备名（含最长名 Mashiros-MacBook-Pro-iso-a659864a）。
//
// Run:
//   NODE_PATH="<repo>/node_modules" node tests/explorer-devmenu-float-shots.mjs

import { chromium, webkit } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20261101_explorer-devmenu-float');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'devmenu-float-root';
const T0 = Date.now();

/** 最长设备名（验收点名帧）+ 真实花名册同形。 */
const LONG_NAME = 'Mashiros-MacBook-Pro-iso-a659864a';
const ROSTER = {
  loggedIn: true,
  device: { id: 'dev-self', name: LONG_NAME, platform: 'darwin' },
  peers: [
    { deviceId: 'dev-ijbbeot', deviceName: 'DESKTOP-IJBBEOT', online: true, directOnline: true },
    { deviceId: 'dev-kai', deviceName: 'KAI', online: true, directOnline: true },
    { deviceId: 'dev-v7eucht', deviceName: 'DESKTOP-V7EUCHT', online: true, directOnline: true },
  ],
};
// 文件树夹具：目录名带长 CJK 行（弹层「浮在文件列表之上」帧的底衬）。
const TREE_ENTRIES = [
  '.audit', '.nebflow', '01_数据输入', '02_传输线', '03_文献调研',
  '04_设计_未开展', '05_论文精读', '06_FPGA逻辑',
];
const HOME = '/tmp/devmenu-float-home';

/** 本机静态 server（127.0.0.1 随机口；进程级单例，全 context 复用）。 */
let staticServer = null;
async function staticOrigin() {
  if (staticServer) return staticServer.origin;
  staticServer = await new Promise((resolve) => {
    const srv = createServer((req, res) => {
      const serve = (code, body, type) => {
        res.writeHead(code, type ? { 'Content-Type': type } : undefined);
        res.end(body);
      };
      const json = (body) => serve(200, body, 'application/json');
      let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      // 服务端兜底（真后端伺服面；route 截胡漏网时——webkit worker fetch 实测——
      // 由这里接住，保「零 console error」验收）：agent 档案空档 + api 空档
      // （neblink/status 给花名册夹具；canvas-tabs 给空数组防 .map 炸）。
      if (p.startsWith('/agents/')) return json('{}');
      if (p === '/api/neblink/status') return json(JSON.stringify(ROSTER));
      if (p === '/api/canvas-tabs') return json('[]');
      if (p.startsWith('/api/')) return json('{}');
      if (p === '/') p = '/index.html';
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) { serve(403, 'forbidden'); return; }
      try {
        const body = readFileSync(file); // 目录路径在此抛 EISDIR → 404（头未发出）
        serve(200, body, MIME[extname(file)] || 'application/octet-stream');
      } catch {
        serve(404, 'not found');
      }
    });
    srv.listen(0, '127.0.0.1', () => resolve({ server: srv, origin: `http://127.0.0.1:${srv.address().port}` }));
  });
  return staticServer.origin;
}

/** 起应用（静态真 server + /api 截胡 + ws 注答）。返回 console/pageerror 台账。 */
async function bootApp(page) {
  const consoleErrors = [];
  const pageErrors = [];
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (/workerMain\.js/.test(m.text())) return;
    consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => {
    const s = String(e.stack || e.message || '');
    if (/workerMain\.js/.test(s)) return;
    pageErrors.push(e.message);
  });
  page.on('response', (resp) => {
    if (resp.status() >= 400) consoleErrors.push('HTTP ' + resp.status() + ' ' + resp.url());
  });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.removeItem('nebflow_explorer_root');
    localStorage.removeItem('nebflow_explorer_root_device');
  });
  await page.context().route('**/api/**', (route) => {
    const p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ROSTER) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await page.context().route('**/agents/**', (route) => {
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (msg.type === 'listDir') {
        ws.send(JSON.stringify({
          type: 'dirListing',
          path: msg.path || '',
          resolvedPath: msg.rootPath || HOME + '/projects',
          entries: TREE_ENTRIES.map((name) => ({ name, type: 'dir', size: 0, mtime: T0, broken: false })),
        }));
      }
    });
  });
  const origin = await staticOrigin();
  // commit 即返：应用就绪由下方 state.js waitForFunction 把关，不依赖文档级事件。
  await page.goto(origin + '/', { waitUntil: 'commit', timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  // 落一个本机根（chip 常驻 + 树渲染）。
  await page.evaluate(async () => {
    const m = await import('/js/explorer.js');
    m.openExplorerAt('/tmp/devmenu-float-home/projects');
  });
  await page.waitForSelector('#explorer-device-btn', { timeout: 5000 });
  await page.waitForFunction(
    () => document.getElementById('explorer-device-btn')?.textContent?.includes('Mashiros-MacBook-Pro'),
    null, { timeout: 5000 },
  );
  await page.waitForSelector('.explorer-item', { timeout: 5000 });
  await page.waitForTimeout(250);
  return { consoleErrors, pageErrors };
}

/** 弹层开态读数：几何/层叠/材质/全显四断言一次取齐。 */
async function menuReadings(page) {
  return page.evaluate(() => {
    const menu = document.querySelector('.dev-menu.explorer-dev-menu');
    const chip = document.getElementById('explorer-device-btn');
    const tree = document.getElementById('explorer-tree');
    const mr = menu.getBoundingClientRect();
    const tr = tree.getBoundingClientRect();
    const cr = chip.getBoundingClientRect();
    const cx = mr.left + mr.width / 2;
    const cy = mr.top + mr.height / 2;
    const hit = document.elementFromPoint(cx, cy);
    const nm = [...menu.querySelectorAll('.dev-item .nm')][0];
    const foot = menu.querySelector('.dev-menu-foot');
    const fr = foot ? foot.getBoundingClientRect() : null;
    const vw = window.innerWidth, vh = window.innerHeight;
    const M = 8;
    return {
      open: !menu.hidden,
      longestNameFull: nm ? nm.textContent : null,
      nmNoClip: nm ? nm.scrollWidth <= nm.clientWidth + 1 : null,
      nmWraps: nm ? nm.clientWidth < nm.scrollWidth : null,
      inViewport: mr.left >= M - 1 && mr.top >= M - 1 && mr.right <= vw - M + 1 && mr.bottom <= vh - M + 1,
      floatsOverTree: mr.left < tr.right && mr.right > tr.left && mr.top < tr.bottom && mr.bottom > tr.top,
      hitAtMenuCenterIsMenu: !!hit && (!!hit.closest('.dev-menu.explorer-dev-menu')),
      footInsideMenu: fr ? (fr.left >= mr.left - 1 && fr.right <= mr.right + 1 && fr.top >= mr.top - 1 && fr.bottom <= mr.bottom + 1) : null,
      backdrop: getComputedStyle(menu).backdropFilter || getComputedStyle(menu).webkitBackdropFilter || '',
      noBackdropClass: document.documentElement.classList.contains('no-backdrop'),
      menuBox: { w: Math.round(mr.width), h: Math.round(mr.height), left: Math.round(mr.left), top: Math.round(mr.top) },
      chipTitle: chip.title || null,
      chipEllipsized: (() => {
        const label = chip.querySelector('.dev-nm');
        if (!label) return null;
        return label.scrollWidth > label.clientWidth + 1;
      })(),
      menuOffChip: { below: Math.round(mr.top - cr.bottom) },
    };
  });
}

async function runEngine(engineName, launch) {
  const browser = await launch();
  const results = {};
  for (const theme of ['light', 'dark']) {
    const ctx = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      colorScheme: theme,
      deviceScaleFactor: 2,
    });
    const page = await ctx.newPage();
    const h = await bootApp(page);
    const tag = `${engineName}-${theme}`;

    // 帧①：窄栏（默认 220px 面板）chip 放不下 → 省略 + title 悬浮全名。
    const narrow = await page.evaluate(() => {
      const label = document.querySelector('#explorer-device-btn .dev-nm');
      return { ellipsized: label.scrollWidth > label.clientWidth + 1, title: document.getElementById('explorer-device-btn').title };
    });
    await page.screenshot({ path: join(EVID, `${tag}-01-chip-narrow.png`) });

    // 帧②：放宽侧栏面板（等同用户拉宽）→ chip 放得下 → 全显。
    await page.evaluate(() => { document.getElementById('sidebar-panel').style.width = '460px'; });
    await page.waitForTimeout(120);
    const full = await page.evaluate(() => {
      const label = document.querySelector('#explorer-device-btn .dev-nm');
      return {
        ellipsized: label.scrollWidth > label.clientWidth + 1,
        labelText: label.textContent,
        title: document.getElementById('explorer-device-btn').title,
      };
    });
    await page.screenshot({ path: join(EVID, `${tag}-02-chip-full.png`) });

    // 帧③④：点开菜单 → 浮在文件列表之上（玻璃采样全页）。
    await page.click('#explorer-device-btn');
    await page.waitForSelector('.dev-menu.explorer-dev-menu .dev-item', { timeout: 5000 });
    await page.waitForTimeout(150); // 花名册行集落定 + 重锚
    const readings = await menuReadings(page);
    await page.screenshot({ path: join(EVID, `${tag}-03-menu-open.png`) });
    await page.locator('.dev-menu.explorer-dev-menu').screenshot({ path: join(EVID, `${tag}-04-menu-closeup.png`) });

    results[tag] = { narrow, full, readings, consoleErrors: h.consoleErrors, pageErrors: h.pageErrors };
    await ctx.close();
  }
  await browser.close();
  return results;
}

mkdirSync(EVID, { recursive: true });
const all = {};
Object.assign(all, await runEngine('chromium', () => chromium.launch()));
Object.assign(all, await runEngine('webkit', () => webkit.launch()));
writeFileSync(join(EVID, 'readings.json'), JSON.stringify(all, null, 2));

// ── 断言（验收口径）────────────────────────────────────────────────────────
let fail = 0;
const assert = (cond, label) => {
  if (!cond) { fail++; console.log(`FAIL ${label}`); }
  else console.log(`PASS ${label}`);
};
for (const [tag, r] of Object.entries(all)) {
  assert(r.narrow.ellipsized === true, `${tag} 帧① 窄栏 chip 放不下才省略`);
  assert(r.narrow.title === LONG_NAME, `${tag} 帧① 省略时 title 悬浮全名`);
  assert(r.full.ellipsized === false, `${tag} 帧② 放宽后 chip 全显（不省略）`);
  assert(r.full.labelText === LONG_NAME, `${tag} 帧② chip 标签 = 最长设备名`);
  const m = r.readings;
  assert(m.open === true, `${tag} 帧③ 菜单开态`);
  assert(m.longestNameFull === LONG_NAME && m.nmNoClip === true, `${tag} 帧③ 列表最长设备名全显不截断`);
  assert(m.inViewport === true, `${tag} 帧③ 弹层完整在视口内（不裁切）`);
  assert(m.floatsOverTree === true, `${tag} 帧③ 弹层几何压在文件列表区上`);
  assert(m.hitAtMenuCenterIsMenu === true, `${tag} 帧③ 命中测试 = 弹层在最上层（浮于内容之上）`);
  assert(m.footInsideMenu === true, `${tag} 帧③ 说明文案完整收在弹层盒内`);
  assert(/blur\(/.test(m.backdrop) && m.noBackdropClass === false, `${tag} 帧③ 毛玻璃 blur 生效`);
  assert(r.consoleErrors.length === 0 && r.pageErrors.length === 0,
    `${tag} 零 console error（console=${r.consoleErrors.length} page=${r.pageErrors.length}）`);
}
console.log(fail === 0 ? `ALL PASS → ${EVID}` : `${fail} FAIL`);
process.exit(fail === 0 ? 0 : 1);
