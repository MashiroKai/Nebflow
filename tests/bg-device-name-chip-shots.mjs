// bg-device-name-chip-shots.mjs — 设备同级批（作者令 2026-11-01「本地/远程这组徽标
// 整对退役」）视觉证据 + 机械断言。
//
// 契约：后台任务面板行只标三类 —— **设备名 + delegate 短名（有则）+ type（内核/
// 分发器）**；原「本地 / 远程」kind chip 整对删除。本机也是普通设备：不再显示
// 「本地」二字，改显本机设备名（花名册 / configData.selfDevice 同源读数，剥
// -iso-<hex8> 后缀）。行数 / 时长 / 取消钮 / 其余排版零触碰。
//
// 产出 = .nebflow/evidence/20261101_bg-device-name-chip/shots/
//        {desktop,narrow}-{light,dark}.png
// 用法：node tests/bg-device-name-chip-shots.mjs
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', '.nebflow', 'evidence', '20261101_bg-device-name-chip', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'devname-root-session';
const T0 = Date.now();

// 本机设备名：生产形态 = 隔离实例名（含 -iso-<hex8> 后缀），显示面须剥后缀。
const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };

// 三行 = 三种徽标形态（作者验收口径：本机任务 + 远程任务各至少一行）：
//   row-1 本机 + delegate 短名 + 内核  → 设备名 / 短名 / type
//   row-2 远程设备 + 内核              → 设备名 / type
//   row-3 本机 + nebula 来源           → 设备名 / 谁开启
function fixture() {
  return { [ROOT_SID]: [
    { taskId: 'job-delegate', description: 'Boot isolated instance :8791 from this worktree', status: 'running',
      startedAt: T0 - 86_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: 'kernel', ownerName: '模型链面板精简', heartbeat: { idleMs: 3_000, outputLines: 8 } },
    { taskId: 'job-remote', description: '[DESKTOP-IJBBEOT] Download gitlab-runner and install service', status: 'running',
      startedAt: T0 - 37 * 60_000 - 14_000, sessionId: ROOT_SID, kind: 'remote', origin: 'device', originLabel: 'DESKTOP-IJBBEOT',
      ownerMode: 'kernel', heartbeat: { idleMs: 5_000, outputLines: 0 } },
    { taskId: 'job-plain', description: 'npm run build --workspace=web', status: 'running',
      startedAt: T0 - 12_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      heartbeat: { idleMs: 1_000, outputLines: 42 } },
  ] };
}

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

async function makePage(browser, colorScheme, narrow) {
  const page = await (await browser.newContext({
    viewport: narrow ? { width: 390, height: 844 } : { width: 1440, height: 900 },
    colorScheme, deviceScaleFactor: 2,
  })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  page.on('response', (r) => { if (r.status() >= 400) console.log('HTTP', r.status(), r.url()); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // 服务工人逃生门（swReg.js 既有开关）：静态 harness 无 /sw.js 路由 ⇒ 显式
    // 跳过注册，避免注册失败帧污染「零 console error」判据。
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    // 智能体定义静态件（/agents/<name>/agent.json）：本 harness 无该目录 ⇒ 空壳
    // JSON 兜底，避免 404 噪声污染「零 console error」判据（与功能面无关）。
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: SELF_DEVICE,
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: fixture() }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(400);
  return { page, errors };
}

const browser = await chromium.launch();

async function shoot(colorScheme, narrow) {
  const { page, errors } = await makePage(browser, colorScheme, narrow);
  if (narrow) await page.evaluate(() => document.body.classList.add('mobile-layer'));
  // 开面 = hidden 直开（float dock DOM 不在本 harness）+ 显式触发渲染面。
  await page.evaluate(() => document.getElementById('bg-dropdown').classList.remove('hidden'));
  await page.evaluate(() => import('/js/state.js').then((m) => {
    const s = m.default;
    const v = s.getActiveView && s.getActiveView();
    if (s.updateBgTasksUI && v) s.updateBgTasksUI(v);
  }));
  await page.waitForTimeout(500);
  const tag = `${narrow ? 'narrow' : 'desktop'}-${colorScheme}`;
  await page.locator('#bg-dropdown').screenshot({ path: join(OUT, `${tag}.png`) });

  const audit = await page.evaluate(() => {
    const list = document.querySelector('#bg-dropdown .bg-section-tasks .bg-dropdown-list');
    const rows = list ? [...list.querySelectorAll('.bg-task-row')] : [];
    const read = (id) => {
      const r = rows.find((x) => x.getAttribute('data-task-id') === id);
      if (!r) return null;
      const meta = r.querySelector('.bg-task-meta');
      return {
        chips: [...meta.querySelectorAll('.bg-task-origin, .bg-task-mode, .bg-task-kind')]
          .map((c) => c.textContent.trim()),
        devices: [...meta.querySelectorAll('.bg-origin-device')].map((c) => c.textContent.trim()),
        kinds: meta.querySelectorAll('.bg-task-kind').length,
        modes: [...meta.querySelectorAll('.bg-task-mode')].map((c) => c.textContent.trim()),
        lines: meta.querySelector('.bg-task-lines')?.textContent || '',
        uptime: meta.querySelector('.bg-task-uptime')?.textContent || '',
        cancel: !!r.querySelector('.bg-task-cancel'),
      };
    };
    return { rows: rows.length, delegate: read('job-delegate'), remote: read('job-remote'), plain: read('job-plain') };
  });

  ok(`rows=3 (${tag})`, audit.rows === 3, String(audit.rows));
  // 形态 1：本机 + delegate 短名 + 内核（设备同级：本机 = 设备名，无「本地」）。
  ok(`delegate row device=本机设备名 (${tag})`, audit.delegate?.devices?.[0] === 'Mashiros-MacBook-Pro', JSON.stringify(audit.delegate));
  ok(`delegate row chips = [设备, 短名, 内核] (${tag})`,
    JSON.stringify(audit.delegate?.chips) === JSON.stringify(['Mashiros-MacBook-Pro', '模型链面板精简', '内核']), JSON.stringify(audit.delegate?.chips));
  // 形态 2：远程 = 对端设备名 + 内核（无「远程」字样，单设备 chip）。
  ok(`remote row device=对端设备名 (${tag})`, audit.remote?.devices?.[0] === 'DESKTOP-IJBBEOT', JSON.stringify(audit.remote));
  ok(`remote row chips = [设备, 内核] (${tag})`,
    JSON.stringify(audit.remote?.chips) === JSON.stringify(['DESKTOP-IJBBEOT', '内核']), JSON.stringify(audit.remote?.chips));
  // 形态 3：本机 + 谁开启（nebula）——设备 chip 与来源 chip 并列。
  ok(`plain row chips = [设备, Nebula] (${tag})`,
    JSON.stringify(audit.plain?.chips) === JSON.stringify(['Mashiros-MacBook-Pro', 'Nebula']), JSON.stringify(audit.plain?.chips));
  // 零残留：本地/远程 kind chip 整对退役。
  const kindLeft = [audit.delegate, audit.remote, audit.plain].reduce((n, r) => n + (r?.kinds || 0), 0);
  ok(`no local/remote kind chip (${tag})`, kindLeft === 0, `${kindLeft} left`);
  // 零触碰回归：行数 / 时长 / 取消钮。
  ok(`lines chip intact (${tag})`, audit.delegate?.lines === '8行' && audit.plain?.lines === '42行', `${audit.delegate?.lines} / ${audit.plain?.lines}`);
  ok(`uptime + cancel intact (${tag})`, /m|s/.test(audit.delegate?.uptime || '') && audit.delegate?.cancel === true, `${audit.delegate?.uptime}`);
  ok(`zero console errors (${tag})`, errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
}

await shoot('light', false);
await shoot('dark', false);
await shoot('light', true);
await shoot('dark', true);

await browser.close();
console.log(`shots + audit done → ${OUT}`);
process.exit(failures ? 1 : 0);
