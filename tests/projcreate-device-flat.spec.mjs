// projcreate-device-flat.spec.mjs — 新建项目面板三修批（2026-10-30 作者报障）自验 harness。
//
// 三修（各挂 playwright 断言）：
//   修① 远端目录选择器开箱报错（Invalid path: \）——device 模式初始路径 = 对端家目录
//        `~`（接收端展开）；device 腿 additive `resolvedPath` 透传对端真实绝对路径
//        （面包屑/上级行按真实路径展开）；白名单拒绝 = 本地化可读态（非原始英文）。
//   修② 存放设备选择器下方「横杆残影」——关态 .dev-menu 必须 display:none（守卫
//        projectPanel.css `.dev-menu[hidden]`；旧构建缺守卫时空菜单以 300px 玻璃
//        细条盖在提示行上 = 作者截图 2 横杆）。断言三态（首开/远端选中/选择器往返）。
//   修③ 设备平级设计语言（作者裁定「所有设备是平等的」）——下拉无 self/本机特殊行
//        与标签，连接方式（direct/relay）弱化为 title tooltip；按钮无 is-remote 分级；
//        self/远端同一图标；文案不点名本机（提示行/错误动作/菜单脚注）。
//
// 形态沿 ui-device-faces.spec.mjs 既有路线：静态 route 引真实 js/css/locale，
// routeWebSocket 注入应答帧，🔴 零后端、零真实端口。
//
// Run:
//   node node_modules/@playwright/test/cli.js test tests/projcreate-device-flat.spec.mjs --workers=1 --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20261030_projcreate-device-flat');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'device-flat-root';
const T0 = Date.now();

/** 花名册夹具：self（Mac）+ 两台在线对端（Windows direct / 另一台 direct）。 */
const ROSTER = {
  loggedIn: true,
  device: { id: 'dev-self', name: 'Mashiros-MacBook', platform: 'darwin' },
  peers: [
    { deviceId: 'dev-desktop', deviceName: 'DESKTOP-IJBBEOT', online: true, directOnline: true },
    { deviceId: 'dev-kai', deviceName: 'KAI', online: true, directOnline: true },
  ],
};
const PROJECTS = [
  { name: 'alpha', workspace: '/w/alpha', agentFile: 'x', description: '', createdAt: new Date(T0).toISOString() },
];

/** 对端（Windows）家目录与一级子目录夹具。 */
const PEER_HOME = 'C:\\Users\\PC';
const PEER_HOME_DIRS = ['Desktop', 'Projects'];

function write(f, obj) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, f), JSON.stringify(obj, null, 2));
}

/** 起应用（同 ui-device-faces 形态）。device 腿 wsBrowse.list 由用例经 replyRef 显式应答。 */
async function bootApp(page) {
  let reply = null;
  const outbox = [];
  const pageErrors = [];
  page.on('pageerror', (e) => {
    const s = String(e.stack || e.message || '');
    if (/workerMain\.js/.test(s)) return;
    pageErrors.push(e.message);
  });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ROSTER) });
      const fm = p.match(/^\/api\/projects\/([^/]+)\/flow-map$/);
      if (fm) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: decodeURIComponent(fm[1]), updatedAt: Date.now() }, notMounted: true }) });
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
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID,
    }));
    sendConfig(); sendSessions();
    reply = (msg) => ws.send(JSON.stringify(msg));
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      outbox.push(msg);
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (msg.type === 'wsBrowse.list' && !msg.device) {
        // 本机腿夹具：~ → /tmp/flat-home（ws-alpha 子目录供必选闸用例）。
        const home = '/tmp/flat-home';
        const resolved = msg.path === '~' ? home : msg.path;
        ws.send(JSON.stringify({
          type: 'wsBrowseList', path: resolved, home,
          entries: resolved === home ? ['ws-alpha'] : [], parent: resolved === home ? '/' : undefined,
        }));
      }
      // device 腿（msg.device 在场）：不自动应答 —— 各用例经 reply() 显式驱动
      // （开箱无错 / 白名单可读态 / Windows 路径面包屑三腿各自注入）。
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(250);
  return {
    outbox,
    pageErrors,
    reply: (msg) => reply && reply(msg),
    frames: (type) => outbox.filter((m) => m.type === type),
  };
}

async function openProjects(page) {
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    m.openProjectsTab();
  });
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.waitForFunction(() => {
    const s = document.querySelector('.team-scroll');
    return s && s.dataset.projectsState === 'ready';
  }, null, { timeout: 8000 });
}

async function openCreateDialog(page) {
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
}

async function pickRemoteDevice(page, name) {
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForFunction(() => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0, null, { timeout: 4000 });
  await page.click(`.proj-create-overlay .dev-item:has-text("${name}")`);
  await page.waitForTimeout(200);
}

/** 关态 .dev-menu 的残影读数（修②断言面：display:none ∧ 零占位）。 */
const menuGhostReadout = (page) => page.evaluate(() => {
  const menu = document.querySelector('.proj-create-overlay .dev-menu');
  if (!menu) return { missing: true };
  const cs = getComputedStyle(menu);
  const r = menu.getBoundingClientRect();
  return {
    missing: false,
    display: cs.display,
    offsetHeight: menu.offsetHeight,
    rect: { w: r.width, h: r.height },
    hiddenAttr: menu.hidden === true,
  };
});

test.setTimeout(120_000);

// ════════════════════════════════════════════════════════════════════════════
// 修① 远端目录选择器开箱无错 + 「从家目录开始」可读态
// ════════════════════════════════════════════════════════════════════════════
test('F1 device 选择器：初始路径 ~ 开箱无错 + resolvedPath 面包屑 + 白名单可读态', async ({ page }) => {
  const h = await bootApp(page);
  await openProjects(page);
  await openCreateDialog(page);
  await pickRemoteDevice(page, 'DESKTOP-IJBBEOT');

  // 打开远端选择器 —— 出站帧必须带 path '~'（修前 = '/'，Windows 对端折损 Invalid path: \）。
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-overlay .wsp-panel', { timeout: 5000 });
  await page.waitForTimeout(250);
  const browseFrames = h.frames('wsBrowse.list');
  expect(browseFrames.length, '选择器发出 wsBrowse.list').toBeGreaterThan(0);
  expect(browseFrames[0]?.device, '帧带 device 键').toBe('dev-desktop');
  expect(browseFrames[0]?.path, '修①：device 模式初始路径 = ~（对端家目录，接收端展开）').toBe('~');
  expect(browseFrames[0]?.path, '修①红锚：初始路径不得回退 POSIX 根 /').not.toBe('/');

  // 对端应答：家目录列举成功（additive resolvedPath = 对端解析后的真实绝对路径）。
  h.reply({
    type: 'wsBrowseList', path: '~', resolvedPath: PEER_HOME,
    entries: PEER_HOME_DIRS, device: 'dev-desktop',
  });
  await page.waitForTimeout(300);

  const opened = await page.evaluate(() => {
    const panel = document.querySelector('.wsp-overlay .wsp-panel');
    const err = panel.querySelector('.wsp-err');
    return {
      title: panel.querySelector('.wsp-title')?.textContent ?? null,
      errHidden: err ? err.hidden : null,
      errText: err && !err.hidden ? err.textContent : null,
      rows: [...panel.querySelectorAll('.wsp-list .wsp-row-name')].map((el) => el.textContent),
      upRow: !!panel.querySelector('.wsp-list .wsp-up-row'),
      crumbTexts: [...panel.querySelectorAll('.wsp-crumbs .wsp-crumb')].map((el) => el.textContent),
      crumbTitles: [...panel.querySelectorAll('.wsp-crumbs .wsp-crumb')].map((el) => el.getAttribute('title')),
      chip: panel.querySelector('.wsp-crumbs .path-chip')?.textContent ?? null,
      bodyText: panel.textContent,
    };
  });
  mkdirSync(EVID, { recursive: true });
  await page.screenshot({ path: join(EVID, 'F1-open-clean.png') });
  write('10-F1-open-clean.json', { browseFrame0: browseFrames[0], opened, pageErrors: h.pageErrors });
  expect(opened.title, '弹窗标题 = 设备名变体').toContain('DESKTOP-IJBBEOT');
  expect(opened.errHidden, '修①：开箱零错误行（wsp-err 保持隐藏）').toBe(true);
  expect(opened.bodyText, '修①：全版面零 Invalid path 字样').not.toContain('Invalid path');
  expect(opened.rows, '对端家目录直接列出（首行 = 上级行）').toEqual(['..', 'Desktop', 'Projects']);
  expect(opened.chip, '面包屑设备 chip 在场').toContain('DESKTOP-IJBBEOT');
  // Windows 路径面包屑：C:\ / Users / PC 三个可点 crumb（resolvedPath 驱动，修①后半）。
  expect(opened.crumbTexts, '修①：面包屑按对端分隔符分段（C: / Users）').toEqual(['C:', 'Users']);
  expect(opened.crumbTitles[0], '修①：盘符根 crumb = C:\\（可直达，不折损）').toBe('C:\\');
  expect(opened.upRow, '家目录非根 ⇒ 上级行在场').toBe(true);

  // 下行导航：子路径拼接用对端分隔符（出站帧 C:\Users\PC\Projects，非混拼）。
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("Projects"))');
  await page.waitForTimeout(250);
  const childFrames = h.frames('wsBrowse.list');
  const childFrame = childFrames[childFrames.length - 1];
  expect(childFrame?.path, '修①：子路径用对端分隔符拼接').toBe('C:\\Users\\PC\\Projects');

  // 白名单拒绝腿：可读态（本地化「未共享 + 出路」），非原始英文报错。
  h.reply({
    type: 'wsBrowseList', path: childFrame?.path ?? '', entries: [], device: 'dev-desktop',
    error: 'path is outside the device share whitelist: C:\\Users\\PC\\Projects (browse scope is limited to registered project workspaces, captures and uploads)',
  });
  await page.waitForTimeout(300);
  const denied = await page.evaluate(() => {
    const panel = document.querySelector('.wsp-overlay .wsp-panel');
    const err = panel.querySelector('.wsp-err');
    return {
      errHidden: err ? err.hidden : null,
      errText: err && !err.hidden ? err.textContent : null,
      listText: panel.querySelector('.wsp-list')?.textContent ?? null,
    };
  });
  write('11-F1-denial-readable.json', { denied, pageErrors: h.pageErrors });
  await page.screenshot({ path: join(EVID, 'F1-denial-readable.png') });
  expect(denied.errHidden, '白名单拒绝 = 显式错误行（不静默）').toBe(false);
  expect(denied.errText, '修①：拒绝态本地化可读（未共享）').toContain('未共享');
  expect(denied.errText, '修①：拒绝态给出路（deviceShare.extraPaths）').toContain('deviceShare.extraPaths');
  expect(denied.errText, '修①：拒绝态不直出对端原始英文').not.toContain('outside the device share whitelist');
  expect(denied.listText, '列表位 = 无法读取目录（既有面）').toContain('无法读取目录');
  expect(h.pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// 修② 无横杆：关态 .dev-menu 三态 display:none
// ════════════════════════════════════════════════════════════════════════════
test('F2 无横杆残影：关态设备菜单首开/远端选中/选择器往返均 display:none', async ({ page }) => {
  const h = await bootApp(page);
  await openProjects(page);
  await openCreateDialog(page);

  const atOpen = await menuGhostReadout(page);

  // 远端选中后（设备切换 = 作者报障现场之一）。
  await pickRemoteDevice(page, 'DESKTOP-IJBBEOT');
  const afterRemote = await menuGhostReadout(page);

  // 选择器打开→取消往返后再读（作者报障现场之二：横杆挂在提示行上）。
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-overlay .wsp-panel', { timeout: 5000 });
  await page.waitForTimeout(200);
  // 取消选择器（应答注入与否不影响本断言；直接关）。
  await page.click('.wsp-overlay .wsp-cancel');
  await page.waitForTimeout(300);
  const afterPickerRoundTrip = await menuGhostReadout(page);

  // 菜单开→关一轮后再读（开关切换后的收态正确性）。
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForFunction(() => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0, null, { timeout: 4000 });
  await page.click('.proj-create-overlay .dev-item:has-text("KAI")');
  await page.waitForTimeout(200);
  const afterMenuToggle = await menuGhostReadout(page);

  write('20-F2-no-ghost-bar.json', { atOpen, afterRemote, afterPickerRoundTrip, afterMenuToggle, pageErrors: h.pageErrors });
  for (const [tag, r] of Object.entries({ atOpen, afterRemote, afterPickerRoundTrip, afterMenuToggle })) {
    expect(r.missing, `[${tag}] .dev-menu 在场`).toBe(false);
    expect(r.hiddenAttr, `[${tag}] hidden 属性在位`).toBe(true);
    expect(r.display, `[${tag}] 修②：关态菜单 display:none（无横杆残影）`).toBe('none');
    expect(r.offsetHeight, `[${tag}] 修②：关态菜单零占位`).toBe(0);
  }
  expect(h.pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// 修③ 设备平级设计语言：下拉无 self/本机特殊行；连接方式弱化；文案中性
// ════════════════════════════════════════════════════════════════════════════
test('F3 设备平级：菜单无 self/本机特殊行 + 连接方式 tooltip + 按钮/文案无分级', async ({ page }) => {
  const h = await bootApp(page);
  await openProjects(page);
  await openCreateDialog(page);

  // 首开读数（初始渲染补齐后：self 缺省显示中性兜底名 + 提示行短句）。
  const idle = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const btn = panel.querySelector('.dev-btn');
    const hint = panel.querySelector('.dev-hintline');
    return {
      btnText: btn?.textContent ?? null,
      btnIcon: btn?.querySelector('.dev-ic')?.innerHTML ?? null,
      hasIsRemoteClass: btn?.classList.contains('is-remote') ?? null,
      hintText: hint?.textContent ?? null,
      hintVisible: !!hint && hint.hidden === false,
    };
  });

  // 打开菜单：self + peers 平级行。
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForFunction(() => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0, null, { timeout: 4000 });
  const menu = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.proj-create-overlay .dev-item')];
    return {
      count: items.length,
      names: items.map((el) => el.querySelector('.nm')?.textContent ?? ''),
      // 平级断言面：行结构完全一致（圆点 + 名 + [勾|离线 tag]），无 .sub 通道标签。
      subCount: items.filter((el) => el.querySelector('.sub')).length,
      dotCount: items.filter((el) => el.querySelector('.dot')).length,
      titles: items.map((el) => el.getAttribute('title') ?? ''),
      rowTexts: items.map((el) => el.textContent ?? ''),
      classShapes: items.map((el) => el.className.replace(' active', '').replace(' disabled', '')),
      foot: document.querySelector('.proj-create-overlay .dev-menu-foot')?.textContent ?? null,
    };
  });

  // self 行选中（首行 = 花名册本名）→ 提交帧仍零 device 键（机制面缺省腿不动）。
  await page.click('.proj-create-overlay .proj-item-self, .proj-create-overlay .dev-item >> nth=0');
  await page.waitForTimeout(200);
  const afterSelfPick = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const btn = panel.querySelector('.dev-btn');
    const hint = panel.querySelector('.dev-hintline');
    return {
      btnText: btn?.textContent ?? null,
      btnIcon: btn?.querySelector('.dev-ic')?.innerHTML ?? null,
      hasIsRemoteClass: btn?.classList.contains('is-remote') ?? null,
      hintText: hint?.textContent ?? null,
    };
  });

  // 远端选中读数（同一图标/无分级 class/提示行长句）。
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForFunction(() => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0, null, { timeout: 4000 });
  await page.click('.proj-create-overlay .dev-item:has-text("DESKTOP-IJBBEOT")');
  await page.waitForTimeout(200);
  const afterRemotePick = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const btn = panel.querySelector('.dev-btn');
    const hint = panel.querySelector('.dev-hintline');
    return {
      btnText: btn?.textContent ?? null,
      btnIcon: btn?.querySelector('.dev-ic')?.innerHTML ?? null,
      hasIsRemoteClass: btn?.classList.contains('is-remote') ?? null,
      hintText: hint?.textContent ?? null,
    };
  });

  // self 提交帧零 device 键（选中首行 self 后再选路径提交）。
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForFunction(() => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0, null, { timeout: 4000 });
  await page.click('.proj-create-overlay .dev-item >> nth=0'); // 选回 self
  await page.waitForTimeout(150);
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("ws-alpha"))');
  await page.waitForTimeout(300);
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(300);
  await page.evaluate(() => {
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '平级设计语言回归';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(300);
  const selfSubmitFrames = h.frames('projectCreate');

  write('30-F3-flat-menu.json', {
    idle, menu, afterSelfPick, afterRemotePick, selfSubmitFrames: selfSubmitFrames.slice(-1),
    pageErrors: h.pageErrors,
  });
  await page.screenshot({ path: join(EVID, 'F3-flat-remote-state.png') });

  // ── 菜单行：self + peers，无 self/本机 特殊行与标签 ──
  expect(menu.count, '菜单 = self + 2 peers').toBe(3);
  expect(menu.names, '修③：self 行显示花名册本名（无「本机」特殊名）').toEqual(
    ['Mashiros-MacBook', 'DESKTOP-IJBBEOT', 'KAI']
  );
  for (const t of menu.rowTexts) {
    expect(t, '修③：行文案零 self/local 分级字样').not.toMatch(/self|本机/i);
  }
  expect(menu.subCount, '修③：行间零 self/direct/relay 标签').toBe(0);
  expect(menu.dotCount, '平级行语言：每行在线点在场').toBe(3);
  expect(new Set(menu.classShapes).size, '平级行语言：全部行同一 class 形态').toBe(1);
  expect(menu.titles, '修③：连接方式弱化为 title tooltip').toEqual(['direct', 'direct', 'direct']);
  expect(menu.foot, '菜单脚注不含 peers 行话').not.toContain('peers');

  // ── 按钮面：self/远端同一图标、无分级 class ──
  expect(idle.hasIsRemoteClass, '修③：首开无 is-remote 分级 class').toBe(false);
  expect(afterRemotePick.hasIsRemoteClass, '修③：远端选中仍无分级 class').toBe(false);
  expect(afterRemotePick.btnIcon, '修③：远端选中 = 同一设备图标').toBe(idle.btnIcon);
  expect(afterSelfPick.btnIcon, '修③：self 选中 = 同一设备图标').toBe(idle.btnIcon);
  expect(afterSelfPick.btnText, 'self 选中后按钮显示花名册本名').toContain('Mashiros-MacBook');

  // ── 文案：提示行不点名本机 ──
  expect(idle.hintVisible, '首开提示行可见（self 短句）').toBe(true);
  expect(idle.hintText, 'self 提示行 = 存放+执行陈述').toContain('由该设备执行');
  expect(idle.hintText, '修③：self 提示行不点名本机').not.toContain('本机');
  expect(afterRemotePick.hintText, '远端提示行含指针从句').toContain('仅登记指针');
  expect(afterRemotePick.hintText, '远端提示行点设备名').toContain('DESKTOP-IJBBEOT');
  expect(afterRemotePick.hintText, '修③：远端提示行不点名本机').not.toContain('本机');

  // ── self 提交语义不动：零 device 键 ──
  expect(selfSubmitFrames.length, 'self 提交发 1 帧').toBe(1);
  expect(selfSubmitFrames[0] && 'device' in selfSubmitFrames[0], 'self 提交帧零 device 键（机制面缺省腿）').toBe(false);
  expect(h.pageErrors, '零页面异常').toEqual([]);
});
