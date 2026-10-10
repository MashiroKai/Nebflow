// ui-device-faces-shots.mjs — 三面 UI 暗亮双主题截图自验（ui-device-faces 批 2026-10-10）。
//
// 形态 = ui-device-faces.spec.mjs 的静态 mock bootApp 同款（零后端、零真实端口），
// 只产截图不断言（视觉自验件；断言归 spec）。Node 侧持有 WS 注入句柄（replyRef），
// 失败态（创建失败回滚 / 目录离线错误 / 取件失败浮条）与成功态同样真实驱动。
// 落点：
//   .nebflow/evidence/20261010_ui-device-faces-impl/shots/{dark,light}/*.png
//   .nebflow/evidence/20261010_ui-device-faces-impl/shots-manifest.json
// 并镜像到设计稿目录 proto-device-agnostic/20261010_ui-device-faces-shots/。
//
// Run:
//   node node_modules/@playwright/test/cli.js test tests/ui-device-faces-shots.mjs --workers=1 --reporter=line

import { test } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, readdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20261010_ui-device-faces-impl', 'shots');
const DESIGN_DIR = '/tmp/nebflow-coldstart/general/proto-device-agnostic/20261010_ui-device-faces-shots';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'device-faces-root';
const T0 = Date.now();

const ROSTER = {
  loggedIn: true,
  device: { id: 'dev-self', name: 'This-Mac', platform: 'darwin' },
  peers: [
    { deviceId: 'dev-studio', deviceName: 'Studio-M2', online: true, directOnline: true },
    { deviceId: 'dev-nas', deviceName: 'NAS-Uat', online: false, directOnline: false },
  ],
};
const PROJECTS = [
  { name: 'local-proj', workspace: '/Users/kai/Projects/local-proj', agentFile: 'x', description: '本机项目（零徽标对照）', createdAt: new Date(T0).toISOString() },
  {
    name: 'studio-site', workspace: '/Volumes/Studio/Projects/studio-site', agentFile: 'x', description: '',
    createdAt: new Date(T0 - 1000).toISOString(),
    origin: { device: 'dev-studio', path: '/Volumes/Studio/Projects/studio-site' },
  },
  {
    name: 'nas-archive', workspace: '/Volumes/archive/legacy', agentFile: 'x', description: '离线设备上的归档项目',
    createdAt: new Date(T0 - 2000).toISOString(),
    origin: { device: 'dev-nas', path: '/Volumes/archive/legacy' },
  },
];
const FM_EMPTY = (name) => ({ nodes: [], worktrees: [], meta: { project: name, updatedAt: Date.now() }, notMounted: true });
const REMOTE_ROOT = '/Volumes/Studio/Projects/studio-site';

async function boot(page, theme) {
  let reply = null;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.removeItem('nebflow_explorer_root');
    localStorage.removeItem('nebflow_explorer_root_device');
  });
  await page.emulateMedia({ colorScheme: theme });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ROSTER) });
      const fm = p.match(/^\/api\/projects\/([^/]+)\/flow-map$/);
      if (fm) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(FM_EMPTY(decodeURIComponent(fm[1]))) });
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
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    reply = (msg) => ws.send(JSON.stringify(msg));
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') {
        ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      } else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (msg.type === 'wsBrowse.list') {
        if (msg.device === 'dev-studio') {
          ws.send(JSON.stringify({
            type: 'wsBrowseList', path: msg.path === '~' ? '/' : msg.path,
            entries: msg.path === '~' ? ['Volumes'] : ['docs', 'assets', 'README.md'], device: msg.device,
          }));
        } else {
          ws.send(JSON.stringify({ type: 'wsBrowseList', path: msg.path, home: '/Users/kai', entries: ['Projects', 'Documents', 'Downloads'] }));
        }
      } else if (msg.type === 'listDir' && msg.device === 'dev-studio') {
        // docs 挂起不自动应答 —— 目录失败态由 reply 注入错误帧（需有 pending 装载）。
        if (msg.path === 'docs') return;
        ws.send(JSON.stringify({
          type: 'dirListing', path: msg.path, resolvedPath: msg.path ? `${REMOTE_ROOT}/${msg.path}` : REMOTE_ROOT,
          entries:
            msg.path === 'assets'
              ? [{ name: 'banner.svg', type: 'file', size: 4096, mtime: T0, broken: false }]
              : [
                  { name: 'docs', type: 'dir', size: 0, mtime: T0, broken: false },
                  { name: 'assets', type: 'dir', size: 0, mtime: T0, broken: false },
                  { name: 'main.js', type: 'file', size: 128, mtime: T0, broken: false },
                  { name: 'README.md', type: 'file', size: 964, mtime: T0, broken: false },
                ],
          device: msg.device,
        }));
      }
      // readFile：不自动应答 —— 由 replyRef 显式驱动成功/失败两态。
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return (msg) => reply && reply(msg);
}

const shot = (page, dir, name) => page.screenshot({ path: join(dir, name + '.png'), fullPage: false });

test('三面截图：暗亮双主题（面A 弹层五态 / 面B 远端浏览+取件失败 / 面C 三卡徽标）', async ({ page }) => {
  test.setTimeout(180_000);
  for (const theme of ['dark', 'light']) {
    const dir = join(EVID, theme);
    mkdirSync(dir, { recursive: true });
    mkdirSync(DESIGN_DIR, { recursive: true });
    const reply = await boot(page, theme);

    // ── 面C：项目卡三态徽标（本机零徽标 / 在线远端 / 离线远端） ──
    await page.evaluate(async () => { (await import('/js/projectTab.js')).openProjectsTab(); });
    await page.waitForSelector('.project-card[data-project="studio-site"] .dev-badge', { timeout: 8000 });
    await page.waitForTimeout(600); // 花名册补名落地
    await shot(page, dir, 'faceC-project-cards-badges');

    // ── 面A：创建弹层（默认本机形） ──
    await page.click('.proj-create-fab');
    await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
    await shot(page, dir, 'faceA-create-dialog-self');

    // 菜单开（self + peers + 离线禁选 + 脚注）
    await page.click('.proj-create-overlay .dev-btn');
    await page.waitForFunction(() => document.querySelectorAll('.proj-create-overlay .dev-item').length > 0, null, { timeout: 4000 });
    await shot(page, dir, 'faceA-device-menu');

    // 远端选中形（label/pick 变体 + 语义提示行）
    await page.click('.proj-create-overlay .dev-item:has-text("Studio-M2")');
    await page.waitForTimeout(250);
    await shot(page, dir, 'faceA-create-dialog-remote');

    // 远端路径选择器（device 腿：chip 面包屑 + 只读脚注，无 mkdir/goto）
    await page.click('.proj-create-overlay .proj-create-pick');
    await page.waitForSelector('.wsp-list .wsp-row', { timeout: 5000 });
    await page.waitForTimeout(250);
    await shot(page, dir, 'faceA-remote-picker');

    // 选中远端目录 → 回显 chip；填描述 → 提交 → 创建中四步
    await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("assets"))');
    await page.waitForTimeout(300);
    await page.click('.wsp-foot .wsp-pick');
    await page.waitForTimeout(350);
    await page.evaluate(() => {
      const ta = document.querySelector('.proj-create-desc');
      ta.value = '在 Studio-M2 上创建一个站点项目，目录结构按设计稿来。';
      ta.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.click('.proj-create-overlay .wsp-pick');
    await page.waitForSelector('.proj-create-overlay .creating-row', { timeout: 4000 });
    await shot(page, dir, 'faceA-creating-four-steps');

    // 失败回滚态（REMOTE_SCAFFOLD_FAILED → 错误行 + [重试][换一台设备…]）
    reply({
      type: 'projectCreateResult',
      ok: false,
      error: "Project 'assets' was NOT created: scaffold write on device 'Studio-M2' failed — permission denied. Retry is safe. (REMOTE_SCAFFOLD_FAILED)",
    });
    await page.waitForSelector('.proj-create-overlay .proj-create-error .err-act', { timeout: 4000 });
    await shot(page, dir, 'faceA-failure-rollback');

    // ── 面B：远端根浏览器（chip 常显 + 列表） ──
    await page.keyboard.press('Escape'); // 关创建弹层
    await page.waitForTimeout(200);
    await page.evaluate(async ({ path, device }) => {
      (await import('/js/explorer.js')).openExplorerAt(path, device);
    }, { path: REMOTE_ROOT, device: 'dev-studio' });
    await page.waitForSelector('.explorer-item.explorer-file[data-path="main.js"]', { timeout: 6000 });
    await page.waitForTimeout(400); // chip 补名
    await shot(page, dir, 'faceB-remote-root-chip');

    // 目录失败态（离线错误行 + [重试]；已列元数据保留）：先展开 docs（发起 listDir，
    // mock 挂起该装载）→ 注入错误帧。
    await page.evaluate(() => {
      const row = document.querySelector('.explorer-item.explorer-folder[data-path="docs"]');
      row?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await page.waitForTimeout(250);
    reply({ type: 'dirListing', error: "device 'Studio-M2' not reachable (offline)", path: 'docs', device: 'dev-studio' });
    await page.waitForSelector('.explorer-dir-wrapper[data-path="docs"] .explorer-error', { timeout: 4000 });
    await shot(page, dir, 'faceB-dir-error-retry');

    // 取件失败态（玻璃浮条：原因 + [重试][关闭]；行失败标记）
    await page.click('.explorer-item.explorer-file[data-path="main.js"]');
    await page.waitForSelector('.explorer-item.explorer-file[data-path="main.js"][data-pickup="loading"]', { timeout: 4000 });
    reply({ type: 'fileContent', error: 'single fetch failed: peer unreachable', path: 'main.js', device: 'dev-studio' });
    await page.waitForSelector('.explorer-pickup-bar', { timeout: 4000 });
    await page.waitForTimeout(350); // 过入场动画帧（截图取settled态）
    await shot(page, dir, 'faceB-pickup-failure-bar');

    // 取件成功态（行复原；查看器 tab 打开 = Canvas 文件页）
    await page.click('.explorer-item.explorer-file[data-path="main.js"]');
    await page.waitForSelector('.explorer-item.explorer-file[data-path="main.js"][data-pickup="loading"]', { timeout: 4000 });
    reply({
      type: 'fileContent', path: 'main.js', content: 'console.log("hello from Studio-M2");', itemType: 'code',
      fileName: 'main.js', size: 34, mtimeMs: T0, absPath: '/abs/pickups/main.js', device: 'dev-studio',
    });
    await page.waitForTimeout(800); // Monaco 首载
    await shot(page, dir, 'faceB-pickup-success-viewer');
  }

  // ── 镜像关键图到设计稿目录 ──
  const mirrored = [];
  for (const theme of ['dark', 'light']) {
    const src = join(EVID, theme);
    let files = [];
    try { files = readdirSync(src); } catch { files = []; }
    for (const f of files.filter((x) => x.endsWith('.png'))) {
      try {
        copyFileSync(join(src, f), join(DESIGN_DIR, theme + '-' + f));
        mirrored.push(`${theme}/${f}`);
      } catch { /* 目标目录不可写时不阻断 */ }
    }
  }
  writeFileSync(join(ROOT, '.nebflow', 'evidence', '20261010_ui-device-faces-impl', 'shots-manifest.json'), JSON.stringify({
    generatedAt: new Date().toISOString(),
    themes: ['dark', 'light'],
    faces: ['A-create-device(5 states)', 'B-remote-browser(4 states)', 'C-card-badges'],
    mirrored,
  }, null, 2));
});
