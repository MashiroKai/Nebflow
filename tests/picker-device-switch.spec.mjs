// picker-device-switch.spec.mjs — 目录选择器与文件浏览器跨设备化批（2026-10-30，
// 作者报单 09:36「选择项目路径只能看到本机路径；文件浏览器也应加设备选择」）自验 harness。
//
// 形态沿 ui-device-faces.spec.mjs 既有路线：静态 route 引真实 js/css/locale，
// routeWebSocket 在 Node 侧捕获出站帧 / 注入应答帧，🔴 零后端、零真实端口、
// 单 mock 设备通道（不强求真双设备）。
//
// 断言编号：
//   R-P1 选择器设备行在场：默认 self（花名册名平级呈现，无「本机」特化）；本机列表
//   R-P2 设备菜单 = self + peers；离线对端置灰禁选（disabled + 离线 tag），点击不选
//   R-P3 切到在线对端：wsBrowse.list 帧带 device 键；列表内容变化；远端只读 chrome
//        （mkdir/goto/search 不挂 DOM——Absent-not-hidden）；面包屑 chip + 只读脚注
//   R-P4 切回 self：帧零 device 键；本机列表恢复；mkdir/goto 重现；onPick 回 (path, null)
//   R-P5 deviceSwitch:false：设备行不渲染（sidebar set-project-root 契约）
//   R-E1 文件浏览器头部常驻设备钮：本机形（self 名）+ 点开菜单三行
//   R-E2 切到在线对端：listDir 帧带 device 键；chip 报设备名；写入口摘除
//   R-E3 切回 self：listDir 帧零 device 键；写入口恢复
//   R-E4 远端根下「换根」：目录选择器在同一设备盘上打开（wsBrowse.list 带 device）
//
// Run:
//   node node_modules/@playwright/test/cli.js test tests/picker-device-switch.spec.mjs --workers=1 --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20261030_picker-device-switch');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'picker-devswitch-root';
const T0 = Date.now();

/** /api/neblink/status 夹具：self + 在线对端 Studio-M2 + 离线对端 NAS-Uat。 */
const ROSTER = {
  loggedIn: true,
  device: { id: 'dev-self', name: 'This-Mac', platform: 'darwin' },
  peers: [
    { deviceId: 'dev-studio', deviceName: 'Studio-M2', online: true, directOnline: true },
    { deviceId: 'dev-nas', deviceName: 'NAS-Uat', online: false, directOnline: false },
  ],
};

function write(f, obj) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, f), JSON.stringify(obj, null, 2));
}

/** 起应用。返回 Node 侧句柄：outbox 出站帧台账 + reply 注入应答帧。 */
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
    localStorage.removeItem('nebflow_explorer_root');
    localStorage.removeItem('nebflow_explorer_root_device');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ROSTER) });
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
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    // 远端目录列举夹具：'/' → Volumes；其余 → docs/assets（对端盘语义）。
    const remoteBrowse = (path) => ({
      type: 'wsBrowseList',
      path,
      entries: path === '/' ? ['Volumes'] : ['docs', 'assets'],
      device: 'dev-studio',
    });
    sendConfig();
    sendSessions();
    reply = (msg) => ws.send(JSON.stringify(msg));
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      outbox.push(msg);
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (msg.type === 'wsBrowse.list') {
        if (msg.device === 'dev-studio') {
          ws.send(JSON.stringify(remoteBrowse(msg.path === '~' ? '/' : msg.path)));
        } else {
          // 本机腿镜像真实后端语义：~ 在服务端展开为绝对路径（PathUtil.expandTilde）。
          const home = '/tmp/pick-home';
          const resolved = msg.path === '~' ? home
            : msg.path.startsWith('~/') ? home + msg.path.slice(1)
            : msg.path;
          ws.send(JSON.stringify({ type: 'wsBrowseList', path: resolved, home, entries: ['ws-alpha'] }));
        }
      } else if (msg.type === 'listDir') {
        // 两条腿都自动应答（本机/远端条目不同 ⇒ 「切设备后列表变化」可断言）。
        const remote = msg.device === 'dev-studio';
        ws.send(JSON.stringify({
          type: 'dirListing',
          path: msg.path || '',
          resolvedPath: remote ? (msg.rootPath || '/') : (msg.rootPath || '/tmp/home/projects'),
          entries: remote
            ? [
                { name: 'docs', type: 'dir', size: 0, mtime: T0, broken: false },
                { name: 'main.js', type: 'file', size: 128, mtime: T0, broken: false },
              ]
            : [{ name: 'local-dir', type: 'dir', size: 0, mtime: T0, broken: false }],
          ...(remote ? { device: 'dev-studio' } : {}),
        }));
      }
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

/** 直接打开统一目录选择器（chat dirPicker 卡 / explorer 换根 / projectTab D4 全走此组件）。 */
async function openPickerDirect(page, opts = {}) {
  await page.evaluate(async (o) => {
    window.__picked = null;
    const m = await import('/js/workspacePicker.js');
    m.openPicker({
      onPick: (p, d) => { window.__picked = { p, d: d || null }; },
      onCancel: () => {},
      ...o,
    });
  }, opts);
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
}

test.setTimeout(120_000);

// ════════════════════════════════════════════════════════════════════════════
// R-P · 目录选择器跨设备化（面板顶部设备选择行）
// ════════════════════════════════════════════════════════════════════════════
test('R-P 选择器：设备行 + 离线禁选 + 切设备列表变化 + 切回本机复原', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  await openPickerDirect(page);
  // 花名册预取异步补写 self 名——等按钮读数稳定。
  await page.waitForFunction(
    () => document.querySelector('.wsp-dev-btn .dev-nm')?.textContent === 'This-Mac',
    null, { timeout: 5000 },
  );

  const initial = await page.evaluate(() => ({
    devRow: !!document.querySelector('.wsp-dev-row'),
    devBtn: document.querySelector('.wsp-dev-btn .dev-nm')?.textContent ?? null,
    isRemote: document.querySelector('.wsp-dev-btn')?.classList.contains('is-remote') ?? null,
    rows: [...document.querySelectorAll('.wsp-list .wsp-row-name')].map((el) => el.textContent),
    mkdir: document.querySelectorAll('.wsp-foot .wsp-mkdir').length,
    goto: document.querySelectorAll('.wsp-goto').length,
  }));

  // ── 菜单：self + peers 平级；离线禁选 ──
  await page.click('.wsp-dev-btn');
  await page.waitForFunction(
    () => document.querySelectorAll('.wsp-dev-row .dev-item').length >= 3,
    null, { timeout: 5000 },
  );
  const menu = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.wsp-dev-row .dev-item')];
    const off = items.find((el) => el.classList.contains('disabled'));
    return {
      names: items.map((el) => el.querySelector('.nm')?.textContent ?? ''),
      offDisabled: off?.disabled ?? null,
      offTag: off?.querySelector('.off-tag')?.textContent ?? null,
      foot: document.querySelector('.wsp-dev-row .dev-menu-foot')?.textContent ?? null,
    };
  });
  const btnBeforeOffClick = initial.devBtn;
  await page.evaluate(() => {
    const el = [...document.querySelectorAll('.wsp-dev-row .dev-item')]
      .find((x) => x.textContent.includes('NAS-Uat'));
    el?.click(); // 禁选行：disabled button 点击不派发 ⇒ 不选
  });
  await page.waitForTimeout(150);
  const afterOffClick = await page.evaluate(() => ({
    btn: document.querySelector('.wsp-dev-btn .dev-nm')?.textContent ?? null,
    menuOpen: !document.querySelector('.wsp-dev-row .dev-menu')?.hidden,
  }));

  // ── 切到在线对端 Studio-M2 ──
  const browseBefore = h.frames('wsBrowse.list').length;
  await page.click('.wsp-dev-row .dev-item:has-text("Studio-M2")');
  await page.waitForFunction(
    () => document.querySelector('.wsp-list .wsp-row-name')?.textContent === 'Volumes',
    null, { timeout: 5000 },
  );
  const remote = await page.evaluate(() => ({
    title: document.querySelector('.wsp-title')?.textContent ?? null,
    btn: document.querySelector('.wsp-dev-btn .dev-nm')?.textContent ?? null,
    isRemote: document.querySelector('.wsp-dev-btn')?.classList.contains('is-remote') ?? null,
    chip: document.querySelector('.wsp-crumbs .path-chip')?.textContent ?? null,
    rows: [...document.querySelectorAll('.wsp-list .wsp-row-name')].map((el) => el.textContent),
    mkdir: document.querySelectorAll('.wsp-foot .wsp-mkdir').length,
    goto: document.querySelectorAll('.wsp-goto').length,
    search: document.querySelectorAll('.wsp-search').length,
    note: document.querySelector('.wsp-foot .pick-note')?.textContent ?? null,
  }));
  const remoteFrames = h.frames('wsBrowse.list').slice(browseBefore);

  // 下钻一层（docs/assets；列表首行是 ".." 上级行，用 :text-is 等目标行）。
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("Volumes"))');
  await page.waitForSelector('.wsp-list .wsp-row:has(.wsp-row-name:text-is("docs"))', { timeout: 5000 });

  // ── 切回 self：本机腿零 device 键、全功能 chrome 复原 ──
  const browseBeforeBack = h.frames('wsBrowse.list').length;
  await page.click('.wsp-dev-btn');
  await page.waitForFunction(
    () => document.querySelectorAll('.wsp-dev-row .dev-item').length >= 3,
    null, { timeout: 5000 },
  );
  await page.click('.wsp-dev-row .dev-item:has-text("This-Mac")');
  await page.waitForSelector('.wsp-list .wsp-row:has(.wsp-row-name:text-is("ws-alpha"))', { timeout: 5000 });
  const back = await page.evaluate(() => ({
    rows: [...document.querySelectorAll('.wsp-list .wsp-row-name')].map((el) => el.textContent),
    mkdir: document.querySelectorAll('.wsp-foot .wsp-mkdir').length,
    goto: document.querySelectorAll('.wsp-goto').length,
    search: document.querySelectorAll('.wsp-search').length,
    chip: document.querySelectorAll('.wsp-crumbs .path-chip').length,
    note: document.querySelectorAll('.wsp-foot .pick-note').length,
  }));
  const backFrames = h.frames('wsBrowse.list').slice(browseBeforeBack);

  // 选中提交：onPick 第二参 = 选择时 origin（本机 = null）。
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(200);
  const picked = await page.evaluate(() => window.__picked);
  write('10-picker.json', {
    initial, menu, afterOffClick, remote, remoteFrames, back, backFrames, picked, pageErrors,
  });
  // R-P1
  expect(initial.devRow, 'R-P1：设备选择行在场').toBe(true);
  expect(initial.devBtn, 'R-P1：默认 self（花名册名平级呈现）').toBe('This-Mac');
  expect(initial.isRemote, 'R-P1：默认本机形（无 is-remote）').toBe(false);
  expect(initial.rows, 'R-P1：本机列表（mock 本机腿）').toContain('ws-alpha');
  expect(initial.mkdir, 'R-P1：本机模式 mkdir 在场').toBe(1);
  // R-P2
  expect(menu.names, 'R-P2：菜单 = self + peers（平级，无本机特化名）').toEqual(['This-Mac', 'Studio-M2', 'NAS-Uat']);
  expect(menu.offDisabled, 'R-P2：离线行 disabled 置灰').toBe(true);
  expect(menu.offTag, 'R-P2：离线行「离线」标注').toBe('离线');
  expect(menu.foot || '', 'R-P2：菜单脚注说明 peers 来源').toContain('NebLink');
  expect(afterOffClick.btn, 'R-P2：离线行点击不选（按钮读数不变）').toBe(btnBeforeOffClick);
  // R-P3
  expect(remoteFrames.length, 'R-P3：切设备后重发 wsBrowse.list').toBeGreaterThan(0);
  expect(remoteFrames[0]?.device, 'R-P3：wsBrowse.list 帧带 device 键（既有 dirPicker 远端伺服腿）').toBe('dev-studio');
  expect(remote.btn, 'R-P3：设备钮报对端名').toBe('Studio-M2');
  expect(remote.isRemote, 'R-P3：is-remote 形').toBe(true);
  expect(remote.rows, 'R-P3：列表内容变化（对端盘 / ⇒ Volumes）').toContain('Volumes');
  expect(remote.chip || '', 'R-P3：面包屑设备 chip').toContain('Studio-M2');
  expect(remote.mkdir, 'R-P3：远端只读——mkdir 不挂 DOM（Absent-not-hidden）').toBe(0);
  expect(remote.goto, 'R-P3：远端无手打路径').toBe(0);
  expect(remote.search, 'R-P3：远端无搜索').toBe(0);
  expect(remote.note || '', 'R-P3：远端只读脚注').toContain('只读');
  expect(remote.title || '', 'R-P3：标题 = 在 <设备> 上选择目录').toContain('Studio-M2');
  // R-P4
  expect(backFrames.length, 'R-P4：切回 self 重发 wsBrowse.list').toBeGreaterThan(0);
  expect(backFrames[0] && 'device' in backFrames[0], 'R-P4：本机腿帧零 device 键').toBe(false);
  expect(back.rows, 'R-P4：本机列表恢复').toContain('ws-alpha');
  expect(back.mkdir, 'R-P4：mkdir 复原').toBe(1);
  expect(back.goto, 'R-P4：goto 复原').toBe(1);
  expect(back.search, 'R-P4：search 复原').toBe(1);
  expect(back.chip, 'R-P4：设备 chip 摘除').toBe(0);
  expect(back.note, 'R-P4：只读脚注摘除').toBe(0);
  expect(picked ? picked.d : 'missing', 'R-P4：onPick 第二参 = 本机 null').toBeNull();
  expect(picked?.p || '', 'R-P4：onPick 第一参 = 路径').toContain('/tmp/pick-home');
  expect(pageErrors, '零页面异常').toEqual([]);
});

test('R-P5 deviceSwitch:false：设备行不渲染（sidebar set-project-root 契约）', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  await openPickerDirect(page, { deviceSwitch: false });
  const state = await page.evaluate(() => ({
    devRow: document.querySelectorAll('.wsp-dev-row').length,
    rows: [...document.querySelectorAll('.wsp-list .wsp-row-name')].map((el) => el.textContent),
    mkdir: document.querySelectorAll('.wsp-foot .wsp-mkdir').length,
  }));
  write('11-picker-gate.json', { state, pageErrors });
  expect(state.devRow, 'R-P5：deviceSwitch:false ⇒ 设备行零渲染').toBe(0);
  expect(state.rows, 'R-P5：本机列表照常').toContain('ws-alpha');
  expect(state.mkdir, 'R-P5：本机模式功能不受影响').toBe(1);
  expect(pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// R-E · 文件浏览器设备切换
// ════════════════════════════════════════════════════════════════════════════
test('R-E 文件浏览器：常驻设备钮 + 切设备 listDir 带 device + 只读边界', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  // 本机初始态：显式落一个本机根（等同用户换根本地目录）——chip 常驻（本机形）、
  // 本机树渲染、本机根入记忆（后续切回时还原它）。
  await page.evaluate(async () => {
    const m = await import('/js/explorer.js');
    m.openExplorerAt('/tmp/home/projects');
  });
  await page.waitForSelector('#explorer-device-btn', { timeout: 5000 });
  await page.waitForFunction(
    () => document.getElementById('explorer-device-btn')?.textContent?.includes('This-Mac'),
    null, { timeout: 5000 },
  );
  const localInitial = await page.evaluate(() => ({
    chip: document.getElementById('explorer-device-btn')?.textContent ?? null,
    rows: [...document.querySelectorAll('.explorer-item')].map((el) => el.dataset.path),
    newFileHidden: document.getElementById('explorer-new-file-btn')?.hidden ?? null,
    newFolderHidden: document.getElementById('explorer-new-folder-btn')?.hidden ?? null,
  }));
  const localFrames = h.frames('listDir');
  const lastLocalFrame = localFrames[localFrames.length - 1];

  // ── 菜单切到 Studio-M2 ──
  await page.click('#explorer-device-btn');
  await page.waitForFunction(
    () => document.querySelectorAll('.dev-menu.explorer-dev-menu .dev-item').length >= 3,
    null, { timeout: 5000 },
  );
  const menu = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.dev-menu.explorer-dev-menu .dev-item')];
    const off = items.find((el) => el.classList.contains('disabled'));
    return {
      names: items.map((el) => el.querySelector('.nm')?.textContent ?? ''),
      offDisabled: off?.disabled ?? null,
      offTag: off?.querySelector('.off-tag')?.textContent ?? null,
    };
  });
  const listDirBefore = h.frames('listDir').length;
  await page.click('.dev-menu.explorer-dev-menu .dev-item:has-text("Studio-M2")');
  await page.waitForSelector('.explorer-item[data-path="docs"]', { timeout: 5000 });
  // chip 同步先画 deviceId、花名册名异步补写——等读数稳定再断言显示名。
  await page.waitForFunction(
    () => document.getElementById('explorer-device-btn')?.textContent?.includes('Studio-M2'),
    null, { timeout: 5000 },
  );
  const remoteFrames = h.frames('listDir').slice(listDirBefore);
  const remote = await page.evaluate(() => ({
    chip: document.getElementById('explorer-device-btn')?.textContent ?? null,
    rows: [...document.querySelectorAll('.explorer-item')].map((el) => el.dataset.path),
    newFileHidden: document.getElementById('explorer-new-file-btn')?.hidden ?? null,
    newFolderHidden: document.getElementById('explorer-new-folder-btn')?.hidden ?? null,
  }));

  // 远端只读闸：写操作（新建文件钮即便隐藏也点）零写帧出站。
  const writesBefore = h.outbox.filter((m) => ['createFile', 'createDir', 'deletePath'].includes(m.type)).length;
  await page.evaluate(() => document.getElementById('explorer-new-file-btn')?.click());
  await page.waitForTimeout(250);
  const writesAfter = h.outbox.filter((m) => ['createFile', 'createDir', 'deletePath'].includes(m.type)).length;

  // ── 切回 self ──
  await page.click('#explorer-device-btn');
  await page.waitForFunction(
    () => document.querySelectorAll('.dev-menu.explorer-dev-menu .dev-item').length >= 3,
    null, { timeout: 5000 },
  );
  const listDirBeforeBack = h.frames('listDir').length;
  await page.click('.dev-menu.explorer-dev-menu .dev-item:has-text("This-Mac")');
  await page.waitForSelector('.explorer-item[data-path="local-dir"]', { timeout: 5000 });
  const backFrames = h.frames('listDir').slice(listDirBeforeBack);
  const back = await page.evaluate(() => ({
    chip: document.getElementById('explorer-device-btn')?.textContent ?? null,
    rows: [...document.querySelectorAll('.explorer-item')].map((el) => el.dataset.path),
    newFileHidden: document.getElementById('explorer-new-file-btn')?.hidden ?? null,
  }));

  // ── R-E4：远端根下「换根」（folder 钮）在同一设备盘上打开选择器 ──
  await page.click('#explorer-device-btn');
  await page.waitForFunction(
    () => document.querySelectorAll('.dev-menu.explorer-dev-menu .dev-item').length >= 3,
    null, { timeout: 5000 },
  );
  await page.click('.dev-menu.explorer-dev-menu .dev-item:has-text("Studio-M2")');
  await page.waitForSelector('.explorer-item[data-path="docs"]', { timeout: 5000 });
  // chip 同步先画 deviceId、花名册名异步补写——等读数稳定再断言显示名。
  await page.waitForFunction(
    () => document.getElementById('explorer-device-btn')?.textContent?.includes('Studio-M2'),
    null, { timeout: 5000 },
  );
  const browseBefore = h.frames('wsBrowse.list').length;
  await page.click('#explorer-folder-btn');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  // 显示名经花名册异步补写（调用方只传 deviceId）——等按钮读数稳定。
  await page.waitForFunction(
    () => document.querySelector('.wsp-dev-btn .dev-nm')?.textContent === 'Studio-M2',
    null, { timeout: 5000 },
  );
  const remotePick = await page.evaluate(() => ({
    devBtn: document.querySelector('.wsp-dev-btn .dev-nm')?.textContent ?? null,
    rows: [...document.querySelectorAll('.wsp-list .wsp-row-name')].map((el) => el.textContent),
    mkdir: document.querySelectorAll('.wsp-foot .wsp-mkdir').length,
  }));
  const remoteBrowseFrames = h.frames('wsBrowse.list').slice(browseBefore);
  // 下钻 Volumes ⇒ docs，选中 docs ⇒ 树根换到该设备盘的 /Volumes/docs。
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("Volumes"))');
  await page.waitForSelector('.wsp-list .wsp-row:has(.wsp-row-name:text-is("docs"))', { timeout: 5000 });
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("docs"))');
  await page.waitForTimeout(300);
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(300);
  const rerootFrames = h.frames('listDir');
  const lastReroot = rerootFrames[rerootFrames.length - 1];

  write('20-explorer.json', {
    localInitial, lastLocalFrame: lastLocalFrame ?? null, menu, remoteFrames, remote,
    writesBefore, writesAfter, backFrames, back, remotePick, remoteBrowseFrames, lastReroot, pageErrors,
  });
  // R-E1
  expect(localInitial.chip || '', 'R-E1：头部常驻设备钮（本机形 = self 名，所有设备平等）').toContain('This-Mac');
  expect(localInitial.rows, 'R-E1：本机树渲染').toContain('local-dir');
  expect(lastLocalFrame && 'device' in lastLocalFrame, 'R-E1：本机 listDir 帧零 device 键').toBe(false);
  expect(localInitial.newFileHidden, 'R-E1：本机写入口在场').toBe(false);
  // R-E2
  expect(menu.names, 'R-E2：设备菜单 = self + peers 平级').toEqual(['This-Mac', 'Studio-M2', 'NAS-Uat']);
  expect(menu.offDisabled, 'R-E2：离线行禁选').toBe(true);
  expect(menu.offTag, 'R-E2：离线行「离线」标注').toBe('离线');
  expect(remoteFrames.length, 'R-E2：切设备后发 listDir').toBeGreaterThan(0);
  expect(remoteFrames[0]?.device, 'R-E2：listDir 帧带 device 键（既有远端腿）').toBe('dev-studio');
  expect(remote.chip || '', 'R-E2：chip 报对端名').toContain('Studio-M2');
  expect(remote.rows, 'R-E2：对端树渲染').toContain('docs');
  expect(remote.newFileHidden, 'R-E2：远端写入口摘除').toBe(true);
  expect(remote.newFolderHidden, 'R-E2：远端新建目录摘除').toBe(true);
  expect(writesAfter, 'R-E2：远端只读闸——写操作零帧出站').toBe(writesBefore);
  // R-E3
  expect(backFrames.length, 'R-E3：切回 self 发 listDir').toBeGreaterThan(0);
  expect(backFrames[0] && 'device' in backFrames[0], 'R-E3：本机腿帧零 device 键').toBe(false);
  expect(back.chip || '', 'R-E3：chip 回本机形').toContain('This-Mac');
  expect(back.rows, 'R-E3：本机树复原').toContain('local-dir');
  expect(back.newFileHidden, 'R-E3：写入口恢复').toBe(false);
  // R-E4
  expect(remoteBrowseFrames.length, 'R-E4：换根打开选择器发 wsBrowse.list').toBeGreaterThan(0);
  expect(remoteBrowseFrames[0]?.device, 'R-E4：选择器在同一设备盘上打开（device 键）').toBe('dev-studio');
  expect(remotePick.devBtn, 'R-E4：选择器设备行选中对端').toBe('Studio-M2');
  expect(remotePick.rows, 'R-E4：对端盘根列表').toContain('Volumes');
  expect(remotePick.mkdir, 'R-E4：远端只读无 mkdir').toBe(0);
  expect(lastReroot?.device, 'R-E4：选中后树根带 device 落根').toBe('dev-studio');
  expect(lastReroot?.rootPath, 'R-E4：树根 = 对端盘所选路径').toBe('/Volumes/docs');
  expect(pageErrors, '零页面异常').toEqual([]);
});
