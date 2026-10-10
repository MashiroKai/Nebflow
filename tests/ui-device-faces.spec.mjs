// ui-device-faces.spec.mjs — 设备无关阶段 1 三面 UI 实施批（2026-10-10）自验 harness。
//
// 形态沿 projcreate-redesign.spec.mjs 既有路线：静态 route 引真实 js/css/locale，
// routeWebSocket 在 Node 侧捕获出站帧 / 注入应答帧，🔴 零后端、零真实端口。
//
// 断言编号（R = 本批红验；A/B/C = 面）：
//   R-A1 存放设备行在场（默认本机形）
//   R-A2 本机提交：projectCreate 帧零 device 键（存量逐字节）
//   R-A3 选择器菜单 = self + peers（/api/neblink/status）；离线行禁选
//   R-A4 远端选中：is-remote 形 + label 变体 + 语义提示行 + pick 钮变体
//   R-A5 远端路径选择走应用内浏览器：wsBrowse.list 带 device 键；无 mkdir；回显带设备 chip
//   R-A6 远端提交：帧带 device=<deviceId>；创建中 = spinner + 四步静态 chips；submit 禁用
//   R-A7 失败回滚态：层不关 + 错误行码尾注 + 行内动作（REMOTE_SCAFFOLD_FAILED/DEVICE_OFFLINE）
//   R-A8 [换成本机] 行内动作 = 重发帧零 device 键
//   R-B1 远端根：listDir 帧带 device 键；头部 dev-chip 常显；加载行报设备名
//   R-B2 目录失败：错误行 + 重试链 + 已列元数据保留
//   R-B3 取件中：行内 data-pickup=loading + readFile 帧带 device
//   R-B4 取件失败：行复原 + 失败标记 + 底部玻璃浮条（原因 + 重试/关闭）
//   R-B5 取件成功：查看器事件照发（workspace-open-item）+ 行复原
//   R-B6 远端只读闸：写操作（新建文件）零帧出站
//   R-C1 面C 徽标：本机零徽标 / 远端徽标（花名册名）/ 离线琥珀点
//   R-C2 projectCreated 帧 row.origin ⇒ 定点插卡带徽标（未挂载摘要）
//
// Run:
//   node node_modules/@playwright/test/cli.js test tests/ui-device-faces.spec.mjs --workers=1 --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20261010_ui-device-faces-impl');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'device-faces-root';
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

/** /api/projects 夹具：本机（无 origin）/ 远端（Studio-M2）/ 离线远端（NAS-Uat）。 */
const PROJECTS = [
  { name: 'local-proj', workspace: '/w/local', agentFile: 'x', description: '', createdAt: new Date(T0).toISOString() },
  {
    name: 'studio-site', workspace: '/Volumes/Studio/Projects/studio-site', agentFile: 'x', description: '',
    createdAt: new Date(T0 - 1000).toISOString(),
    origin: { device: 'dev-studio', path: '/Volumes/Studio/Projects/studio-site' },
  },
  {
    name: 'nas-archive', workspace: '/Volumes/archive/legacy', agentFile: 'x', description: '',
    createdAt: new Date(T0 - 2000).toISOString(),
    origin: { device: 'dev-nas', path: '/Volumes/archive/legacy' },
  },
];

const FM_EMPTY = (name) => ({ nodes: [], worktrees: [], meta: { project: name, updatedAt: Date.now() }, notMounted: true });

/** 远端目录列举夹具（wsBrowse.list device 腿 + listDir device 腿共用语义）。 */
const REMOTE_ROOT = '/Volumes/Studio/Projects/studio-site';
const REMOTE_DIRS = ['docs', 'assets'];

function write(f, obj) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, f), JSON.stringify(obj, null, 2));
}

/** 起应用。返回 Node 侧句柄：outbox 出站帧台账 + reply 注入应答帧。 */
async function bootApp(page) {
  let reply = null;
  const outbox = [];
  const pageErrors = [];
  // 「零页面异常」只管应用层异常。已知环境噪声：Monaco 语言 worker
  // （workerMain.js，经 blob importScripts 加载）不走 page.route 拦截 ⇒ 真实
  // fetch http://localhost:1/vendor/... 失败 → Error('Not Found')。与被测三面
  // 无关（真实网关下资源齐备），按来源滤除。
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
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ROSTER) });
      }
      const fm = p.match(/^\/api\/projects\/([^/]+)\/flow-map$/);
      if (fm) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(FM_EMPTY(decodeURIComponent(fm[1]))) });
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
          ws.send(JSON.stringify({
            type: 'wsBrowseList', path: msg.path === '~' ? '/' : msg.path,
            entries: msg.path === '~' ? ['Volumes'] : REMOTE_DIRS, device: msg.device,
          }));
        } else {
          // 本机腿镜像真实后端语义：~ 在服务端展开为绝对路径（PathUtil.expandTilde），
          // wsBrowseList 帧携带**已解析**绝对 path —— 选择器 ctx.current 才是绝对的。
          const home = '/tmp/pick-home';
          const resolved = msg.path === '~' ? home
            : msg.path.startsWith('~/') ? home + msg.path.slice(1)
            : msg.path;
          ws.send(JSON.stringify({ type: 'wsBrowseList', path: resolved, home, entries: ['ws-alpha'] }));
        }
      } else if (msg.type === 'listDir' && msg.device === 'dev-studio') {
        // 远端 listDir：先挂起（由用例按需注入 ok/error），不自动应答 —— 由 reply() 驱动。
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

/** 打开 projects 标签页并等列表就绪。 */
async function openProjects(page) {
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    m.openProjectsTab();
  });
  await page.waitForSelector('.project-card[data-project="local-proj"]', { timeout: 8000 });
  await page.waitForFunction(() => {
    const s = document.querySelector('.team-scroll');
    return s && s.dataset.projectsState === 'ready';
  }, null, { timeout: 8000 });
  // 徽标设备名来自 /api/neblink/status（异步补写）——等它落地。
  await page.waitForTimeout(400);
}

/** 选远端设备（走真菜单）。 */
async function pickRemoteDevice(page) {
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForSelector('.proj-create-overlay .dev-menu', { timeout: 3000 });
  await page.click('.proj-create-overlay .dev-item:has-text("Studio-M2")');
  await page.waitForTimeout(200);
}

/** 远端根打开文件浏览器（face B 入口复用项目卡路径；这里直呼公开出口）。 */
async function openRemoteExplorer(page) {
  await page.evaluate(async ({ path, device }) => {
    const m = await import('/js/explorer.js');
    m.openExplorerAt(path, device);
  }, { path: REMOTE_ROOT, device: 'dev-studio' });
  await page.waitForTimeout(300);
}

test.setTimeout(120_000);

// ════════════════════════════════════════════════════════════════════════════
// 面 A · 创建项目设备选择
// ════════════════════════════════════════════════════════════════════════════
test('R-A 面A：设备行 + 本机零 device 键 + 菜单/禁选 + 远端模式形', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  await openProjects(page);
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });

  const idle = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const btn = panel.querySelector('.dev-btn');
    return {
      hasDevRow: !!btn,
      btnText: btn?.textContent ?? null,
      isRemote: btn?.classList.contains('is-remote') ?? null,
      wsLabel: panel.querySelector('label[for="proj-create-ws"]')?.textContent ?? null,
    };
  });

  // 本机提交：帧零 device 键（先补路径——复用本机选择器，mock 返回 ws-alpha）。
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("ws-alpha"))');
  await page.waitForTimeout(350);
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(350);
  await page.evaluate(() => {
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '本机创建（存量语义）';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(300);
  const localFrames = h.frames('projectCreate');

  // 失败应答（复用本层）→ 继续测菜单。
  h.reply({ type: 'projectCreateResult', ok: false, error: 'workspace must be an absolute path (got \'\')' });
  await page.waitForTimeout(200);

  await pickRemoteDevice(page);
  const remoteState = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const btn = panel.querySelector('.dev-btn');
    return {
      isRemote: btn?.classList.contains('is-remote') ?? null,
      btnText: btn?.textContent ?? null,
      wsLabel: panel.querySelector('label[for="proj-create-ws"]')?.textContent ?? null,
      pickText: panel.querySelector('.proj-create-pick')?.textContent ?? null,
      hintline: panel.querySelector('.dev-hintline')?.textContent ?? null,
      hintlineVisible: !!panel.querySelector('.dev-hintline') && panel.querySelector('.dev-hintline').hidden === false,
      pathPicked: panel.querySelector('.proj-create-path')?.dataset.picked ?? null,
    };
  });

  // 菜单：重开校验行集合与离线禁选。
  await page.click('.proj-create-overlay .dev-btn');
  await page.waitForSelector('.proj-create-overlay .dev-menu', { timeout: 3000 });
  // 行集合是花名册应答后异步填充（菜单壳先现）——等行落地再读。
  await page.waitForFunction(
    () => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0,
    null, { timeout: 4000 },
  );
  const menu = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.proj-create-overlay .dev-item')];
    const off = items.find((el) => el.classList.contains('disabled'));
    return {
      names: items.map((el) => el.querySelector('.nm')?.textContent ?? ''),
      offlineName: off?.querySelector('.nm')?.textContent ?? null,
      offlineTag: off?.querySelector('.off-tag')?.textContent ?? null,
      foot: document.querySelector('.proj-create-overlay .dev-menu-foot')?.textContent ?? null,
    };
  });
  const beforeNasClick = await page.evaluate(() => document.querySelector('.proj-create-overlay .dev-btn').textContent);
  // 禁选行 = 原生 disabled button：HTMLElement.click() 对 disabled 元素**不派发**
  // click 事件（浏览器语义即「点了不选」）——正合用例；page.click 会一直等 enabled。
  await page.evaluate(() => {
    const el = [...document.querySelectorAll('.proj-create-overlay .dev-item')]
      .find((x) => x.textContent.includes('NAS-Uat'));
    el?.click();
  }); // 禁选行：点了不选
  await page.waitForTimeout(150);
  const afterNasClick = await page.evaluate(() => document.querySelector('.proj-create-overlay .dev-btn').textContent);
  await page.click('.proj-create-overlay .dev-item:has-text("Studio-M2")'); // 收菜单选回远端
  await page.waitForTimeout(150);

  write('60-faceA.json', { idle, localFrames, remoteState, menu, afterNasClick, pageErrors });
  expect(idle.hasDevRow, 'R-A1：存放设备行在场').toBe(true);
  expect(idle.isRemote, 'R-A1：默认本机形（无 is-remote）').toBe(false);
  expect(localFrames.length, '前置：本机提交发出 1 帧').toBe(1);
  expect(
    localFrames[0] && 'device' in localFrames[0],
    'R-A2：本机提交帧零 device 键（机制面缺省腿逐字节）'
  ).toBe(false);
  expect(localFrames[0]?.workspace, 'R-A2 前置：workspace 键照旧').toBe('/tmp/pick-home/ws-alpha');
  expect(menu.names, 'R-A3：菜单 = self + peers（在线在前、离线在列）').toEqual(
    expect.arrayContaining(['本机（这台 Mac）', 'Studio-M2', 'NAS-Uat'])
  );
  expect(menu.offlineName, 'R-A3：离线行在列（可见性）').toBe('NAS-Uat');
  expect(menu.offlineTag, 'R-A3：离线行带「离线」tag').toBe('离线');
  expect(menu.foot || '', 'R-A3：菜单脚注说明 peers 来源').toContain('NebLink');
  expect(afterNasClick, 'R-A3：离线行禁选 —— 点击后按钮文案不变').toBe(beforeNasClick);
  expect(remoteState.isRemote, 'R-A4：远端选中 → is-remote 形').toBe(true);
  expect(remoteState.wsLabel, 'R-A4：工作区 label 变体（<设备> 上）').toContain('Studio-M2');
  expect(remoteState.pickText, 'R-A4：pick 钮变体（在 <设备> 上选择）').toContain('Studio-M2');
  expect(remoteState.hintlineVisible, 'R-A4：语义提示行可见').toBe(true);
  expect(remoteState.hintline || '', 'R-A4：提示行说清执行绑定语义').toContain('Studio-M2');
  expect(remoteState.pathPicked, 'R-A4：设备切换后路径清空（路径是设备定语，不跨设备复用）').toBe('0');
  expect(pageErrors, '零页面异常').toEqual([]);
});

test('R-A5/A6 远端提交：wsBrowse.list 带 device + 创建中 chips + 失败动作', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  await openProjects(page);
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await pickRemoteDevice(page);

  // 远端路径选择 = 应用内浏览器（device 腿）。
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  const browse = await page.waitForTimeout(200).then(() =>
    page.evaluate(() => ({
      mkdirCount: document.querySelectorAll('.wsp-foot .wsp-mkdir').length,
      note: document.querySelector('.wsp-foot .pick-note')?.textContent ?? null,
      chip: document.querySelector('.wsp-crumbs .path-chip')?.textContent ?? null,
      gotoRows: document.querySelectorAll('.wsp-goto, .wsp-search').length,
    }))
  );
  const remoteBrowseFrames = h.frames('wsBrowse.list');
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("assets"))');
  await page.waitForTimeout(350);
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(400);
  const picked = await page.evaluate(() => ({
    chip: document.querySelector('.proj-create-overlay .proj-create-path .path-chip')?.textContent ?? null,
    pathPicked: document.querySelector('.proj-create-overlay .proj-create-path')?.dataset.picked ?? null,
    pathText: document.querySelector('.proj-create-overlay .proj-create-path')?.textContent ?? null,
  }));

  await page.evaluate(() => {
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '远端创建（G.13 序）';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(300);
  const remoteFrames = h.frames('projectCreate');
  const creating = await page.evaluate(() => ({
    row: document.querySelector('.proj-create-overlay .creating-row')?.textContent ?? null,
    steps: [...document.querySelectorAll('.proj-create-overlay .creating-row .step')].map((el) => el.textContent),
    spin: !!document.querySelector('.proj-create-overlay .creating-row .spin'),
    submitDisabled: document.querySelector('.proj-create-overlay .wsp-pick')?.hasAttribute('disabled') ?? null,
  }));

  // 失败面（脚手架失败码 → [重试][换一台设备…]）。
  h.reply({
    type: 'projectCreateResult',
    ok: false,
    error: "Project 'assets' was NOT created: scaffold write on device 'Studio-M2' failed — permission denied. Retry is safe. (REMOTE_SCAFFOLD_FAILED)",
  });
  await page.waitForTimeout(250);
  const failA = await page.evaluate(() => ({
    open: !!document.querySelector('.proj-create-overlay'),
    errorText: document.querySelector('.proj-create-error')?.textContent ?? null,
    acts: [...document.querySelectorAll('.proj-create-error .err-act')].map((el) => el.textContent),
    code: document.querySelector('.proj-create-error .err-code')?.textContent ?? null,
  }));

  // [换一台设备…] = 打开设备菜单（不自动提交）。
  await page.click('.proj-create-error .err-act:has-text("换一台设备")');
  await page.waitForTimeout(150);
  const menuReopened = await page.evaluate(() => !!document.querySelector('.proj-create-overlay .dev-menu'));

  // 重提 → DEVICE_OFFLINE 失败 → [重试][换成本机]；点 [换成本机] ⇒ 重发帧零 device。
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(250);
  h.reply({
    type: 'projectCreateResult',
    ok: false,
    error: "project 'assets' 的设备 'Studio-M2' 不在线（在线设备：（无））。项目不会改在本机创建。 (DEVICE_OFFLINE)",
  });
  await page.waitForTimeout(250);
  const failB = await page.evaluate(() => ({
    acts: [...document.querySelectorAll('.proj-create-error .err-act')].map((el) => el.textContent),
  }));
  const framesBeforeSwitch = h.frames('projectCreate').length;
  await page.click('.proj-create-error .err-act:has-text("换成本机")');
  await page.waitForTimeout(300);
  const framesAfterSwitch = h.frames('projectCreate');
  const lastFrame = framesAfterSwitch[framesAfterSwitch.length - 1];

  write('61-faceA-remote.json', {
    browse, remoteBrowseFrames, picked, remoteFrames, creating, failA, menuReopened, failB,
    framesBeforeSwitch, lastFrame, pageErrors,
  });
  expect(browse.mkdirCount, 'R-A5：远端选择器无「新建文件夹」（G.13 只读）').toBe(0);
  expect(browse.gotoRows, 'R-A5：远端模式无手打路径/搜索（D4 纪律延展）').toBe(0);
  expect(browse.note || '', 'R-A5：远端只读脚注').toContain('只读');
  expect(remoteBrowseFrames.length, '前置：选择器发 wsBrowse.list').toBeGreaterThan(0);
  expect(
    remoteBrowseFrames[0]?.device,
    'R-A5：wsBrowse.list 带 device 键（后端缺口①前端对端）'
  ).toBe('dev-studio');
  expect(picked.pathPicked, 'R-A5：远端回显 data-picked=1').toBe('1');
  expect(picked.chip || '', 'R-A5：回显前缀设备 chip').toContain('Studio-M2');
  expect(remoteFrames.length, '远端提交发出 1 帧').toBe(1);
  expect(remoteFrames[0]?.device, 'R-A6：帧带 device=<deviceId>（不是显示名）').toBe('dev-studio');
  expect(creating.spin, 'R-A6：创建中 = spinner').toBe(true);
  expect(creating.steps, 'R-A6：四步静态说明 chips（校验设备→校验目录→写脚手架→落项目定义）').toHaveLength(4);
  expect(creating.submitDisabled, 'R-A6：提交中 submit 禁用').toBe(true);
  expect(failA.open, 'R-A7：失败层不关').toBe(true);
  expect(failA.code || '', 'R-A7：错误行码尾注').toContain('REMOTE_SCAFFOLD_FAILED');
  expect(failA.acts, 'R-A7：脚手架失败动作 = [重试][换一台设备…]').toEqual(
    expect.arrayContaining(['重试', '换一台设备…'])
  );
  expect(menuReopened, 'R-A7：[换一台设备…] 打开设备菜单').toBe(true);
  expect(failB.acts, 'R-A7：DEVICE_OFFLINE 动作 = [重试][换成本机]').toEqual(
    expect.arrayContaining(['重试', '换成本机'])
  );
  expect(framesAfterSwitch.length, 'R-A8：[换成本机] 重发一帧').toBe(framesBeforeSwitch + 1);
  expect(
    lastFrame && 'device' in lastFrame,
    'R-A8：换本机重发帧零 device 键'
  ).toBe(false);
  expect(pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// 面 B · 文件浏览器远端目录
// ════════════════════════════════════════════════════════════════════════════
test('R-B 面B：远端根 device 腿 + 失败保留 + 取件三态 + 只读闸', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  await openProjects(page);
  await openRemoteExplorer(page);

  // R-B1：根 listDir 帧 + 头部 chip + 加载行。
  await page.waitForFunction(() => !!document.querySelector('.explorer-root'), null, { timeout: 5000 });
  const rootLoad = await page.waitForTimeout(200).then(() =>
    page.evaluate(() => ({
      frames: null,
      chip: document.querySelector('.explorer-header .dev-chip')?.textContent ?? null,
      chipHidden: document.querySelector('.explorer-header .dev-chip')?.hidden ?? null,
      loading: document.querySelector('.explorer-loading')?.textContent ?? null,
    }))
  );
  rootLoad.frames = h.frames('listDir');  h.reply({
    type: 'dirListing', path: '', resolvedPath: REMOTE_ROOT,
    entries: [
      { name: 'docs', type: 'dir', size: 0, mtime: T0, broken: false },
      { name: 'main.js', type: 'file', size: 128, mtime: T0, broken: false },
    ],
    device: 'dev-studio',
  });
  await page.waitForSelector('.explorer-item.explorer-file[data-path="main.js"]', { timeout: 5000 });

  // R-B2：目录失败（离线）→ 错误行 + 重试链 + 已列元数据保留 + chip 琥珀点。
  const expanded = await page.evaluate(() => {
    // 展开已渲染的 docs 目录（会重新 listDir 'docs'），然后注入离线错误帧。
    const row = document.querySelector('.explorer-item.explorer-folder[data-path="docs"]');
    row?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return !!row;
  });
  await page.waitForTimeout(250);
  h.reply({ type: 'dirListing', error: "device 'Studio-M2' not reachable (offline)", path: 'docs', device: 'dev-studio' });
  await page.waitForTimeout(250);
  const failState = await page.evaluate(() => ({
    errRow: document.querySelector('.explorer-dir-wrapper[data-path="docs"] .explorer-error')?.textContent ?? null,
    retry: !!document.querySelector('.explorer-dir-wrapper[data-path="docs"] .explorer-error .retry'),
    siblingKept: !!document.querySelector('.explorer-item.explorer-file[data-path="main.js"]'),
    chipOffline: document.querySelector('.explorer-header .dev-chip')?.classList.contains('is-offline') ?? null,
  }));
  const docsFramesBefore = h.frames('listDir').length;
  await page.click('.explorer-dir-wrapper[data-path="docs"] .explorer-error .retry');
  await page.waitForTimeout(250);
  const retryFrames = h.frames('listDir').length - docsFramesBefore;

  // R-B3/B4/B5：单文件取件三态。
  await page.click('.explorer-item.explorer-file[data-path="main.js"]');
  await page.waitForTimeout(250);
  const pickup = await page.evaluate(() => ({
    rowState: document.querySelector('.explorer-item.explorer-file[data-path="main.js"]')?.dataset.pickup ?? null,
    tag: document.querySelector('.explorer-item.explorer-file[data-path="main.js"] .pickup-tag')?.textContent ?? null,
  }));
  const readFrames = h.frames('readFile');
  h.reply({
    type: 'fileContent', error: 'single fetch failed: peer unreachable', path: 'main.js', device: 'dev-studio',
  });
  await page.waitForTimeout(300);
  const pickupFail = await page.evaluate(() => ({
    rowState: document.querySelector('.explorer-item.explorer-file[data-path="main.js"]')?.dataset.pickup ?? null,
    bar: document.querySelector('.explorer-pickup-bar')?.textContent ?? null,
    barRetry: !!document.querySelector('.explorer-pickup-bar [data-pickup-retry]'),
    barClose: !!document.querySelector('.explorer-pickup-bar [data-pickup-close]'),
  }));
  await page.click('.explorer-pickup-bar [data-pickup-close]');
  await page.waitForTimeout(150);
  const barGone = await page.evaluate(() => !document.querySelector('.explorer-pickup-bar'));

  // 取件成功腿：重开 → 注入 ok 帧 → workspace-open-item 事件 + 行复原。
  const opened = page.evaluate(() => new Promise((resolve) => {
    window.addEventListener('workspace-open-item', (e) => resolve({ title: e.detail?.title, path: e.detail?.path }), { once: true });
  }));
  await page.click('.explorer-item.explorer-file[data-path="main.js"]');
  await page.waitForTimeout(200);
  h.reply({
    type: 'fileContent', path: 'main.js', content: 'console.log(1)', itemType: 'code',
    fileName: 'main.js', size: 14, mtimeMs: T0, absPath: '/abs/pickups/main.js', device: 'dev-studio',
  });
  const openedItem = await Promise.race([opened, page.waitForTimeout(1500).then(() => null)]);
  const pickupOk = await page.evaluate(() => ({
    // dataset.pickup 已删 = undefined；`?? null` 统一为 null（行在场的读数）。
    rowState: document.querySelector('.explorer-item.explorer-file[data-path="main.js"]')?.dataset.pickup ?? null,
  }));

  // R-B6：远端只读闸 — 新建文件零帧出站。
  const writesBefore = h.outbox.filter((m) => ['createFile', 'createDir', 'deletePath'].includes(m.type)).length;
  await page.click('#explorer-new-file-btn');
  await page.waitForTimeout(300);
  const writesAfter = h.outbox.filter((m) => ['createFile', 'createDir', 'deletePath'].includes(m.type)).length;

  write('62-faceB.json', {
    rootLoad, failState, retryFrames, pickup, readFrames, pickupFail, barGone, openedItem, pickupOk,
    writesBefore, writesAfter, pageErrors,
  });
  const rootFrames = rootLoad.frames;
  const rootFrame = rootFrames[rootFrames.length - 1];
  expect(rootFrame?.device, 'R-B1：根 listDir 帧带 device 键').toBe('dev-studio');
  expect(rootLoad.chipHidden, 'R-B1：头部 dev-chip 常显（远端根）').toBe(false);
  expect(rootLoad.chip || '', 'R-B1：chip 文本 = 花名册设备名').toContain('Studio-M2');
  expect(rootLoad.loading || '', 'R-B1：加载行报设备名').toContain('Studio-M2');
  expect(expanded, '前置：docs 目录行可展开').toBe(true);
  expect(failState.errRow || '', 'R-B2：目录失败显式错误行（网关原因）').toContain('offline');
  expect(failState.retry, 'R-B2：错误行带重试链').toBe(true);
  expect(failState.siblingKept, 'R-B2：已列元数据行保留（不假装不存在）').toBe(true);
  expect(failState.chipOffline, 'R-B2：失败时头部 chip 琥珀点（is-offline）').toBe(true);
  expect(retryFrames, 'R-B2：[重试] 重发 listDir').toBe(1);
  expect(pickup.rowState, 'R-B3：取件中 data-pickup=loading').toBe('loading');
  expect(pickup.tag, 'R-B3：行尾「取件中…」').toContain('取件中');
  expect(readFrames[0]?.device, 'R-B3：readFile 帧带 device 键').toBe('dev-studio');
  expect(pickupFail.rowState, 'R-B4：失败行 data-pickup=failed').toBe('failed');
  expect(pickupFail.bar || '', 'R-B4：底部玻璃浮条（可读原因）').toContain('peer unreachable');
  expect(pickupFail.barRetry, 'R-B4：浮条 [重试]').toBe(true);
  expect(pickupFail.barClose, 'R-B4：浮条 [关闭]').toBe(true);
  expect(barGone, 'R-B4：[关闭] 摘浮条').toBe(true);
  expect(openedItem?.path, 'R-B5：成功 = 既有查看器事件照发').toBe('main.js');
  expect(pickupOk.rowState, 'R-B5：成功行复原（零取件态）').toBeNull();
  expect(writesAfter, 'R-B6：远端只读闸 —— 写操作零帧出站').toBe(writesBefore);
  expect(pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// 面 C · 项目面板设备标识
// ════════════════════════════════════════════════════════════════════════════
test('R-C 面C：三卡对照徽标 + projectCreated 帧 origin 插卡', async ({ page }) => {
  const h = await bootApp(page);
  const { pageErrors } = h;
  await openProjects(page);

  const cards = await page.evaluate(() => {
    const read = (name) => {
      const card = document.querySelector(`.project-card[data-project="${name}"]`);
      const badge = card?.querySelector('.dev-badge') || null;
      return {
        hasBadge: !!badge,
        badgeText: badge?.textContent ?? null,
        offline: badge?.classList.contains('is-offline') ?? null,
        warnDot: !!badge?.querySelector('.warn-dot'),
        title: badge?.getAttribute('title') ?? null,
        summary: card?.querySelector('.team-card-summary')?.textContent ?? null,
      };
    };
    return { local: read('local-proj'), remote: read('studio-site'), offline: read('nas-archive') };
  });

  // projectCreated 帧（row.origin）⇒ 定点插卡带徽标；未挂载摘要。
  h.reply({
    type: 'projectCreated',
    project: 'live-created',
    row: {
      name: 'live-created', workspace: '/Volumes/Studio/Projects/live', agentFile: 'x',
      description: '', createdAt: new Date().toISOString(),
      origin: { device: 'dev-studio', path: '/Volumes/Studio/Projects/live' },
    },
    mounted: false,
  });
  await page.waitForSelector('.project-card[data-project="live-created"]', { timeout: 5000 });
  await page.waitForTimeout(400);
  const liveCard = await page.evaluate(() => {
    const card = document.querySelector('.project-card[data-project="live-created"]');
    return {
      badge: card?.querySelector('.dev-badge')?.textContent ?? null,
      summary: card?.querySelector('.team-card-summary')?.textContent ?? null,
    };
  });

  write('63-faceC.json', { cards, liveCard, pageErrors });
  expect(cards.local.hasBadge, 'R-C1：本机项目零徽标（无感缺省）').toBe(false);
  expect(cards.remote.hasBadge, 'R-C1：远端项目徽标在').toBe(true);
  expect(cards.remote.badgeText || '', 'R-C1：徽标 = 花名册设备名').toContain('Studio-M2');
  expect(cards.remote.offline, 'R-C1：在线常态无琥珀点').toBe(false);
  expect((cards.remote.title || '') + (cards.offline.title || ''), 'R-C1：title 携带 device id + origin.path（元数据）').toContain('dev-studio');
  expect(cards.offline.offline, 'R-C1：离线徽标 is-offline').toBe(true);
  expect(cards.offline.warnDot, 'R-C1：离线徽标琥珀点（--status-warn-icon 载体）').toBe(true);
  expect(cards.remote.summary || '', 'R-C1 前置：远端项目未挂载摘要（机制面事实）').toContain('未挂载');
  expect(liveCard.badge || '', 'R-C2：projectCreated 帧 row.origin ⇒ 插卡带徽标').toContain('Studio-M2');
  expect(liveCard.summary || '', 'R-C2：插卡摘要 = 未挂载').toContain('未挂载');
  expect(pageErrors, '零页面异常').toEqual([]);
});
