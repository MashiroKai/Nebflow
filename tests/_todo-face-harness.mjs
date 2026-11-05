// _todo-face-harness.mjs — 待办面（胶囊 + 展开面板 + 标签 chips）spec 共用启动器。
//
// 用于 R05/R06（标签垃圾桶 + 叉号删除模式）与 R08（标签横拖保序）两支门禁 spec。
//   · 静态面 = 本仓真 web 树（page.route 从磁盘注入，零本地端口）；
//   · 数据面 = WS 帧桩（configData / sessionList / 空 history）+ todoStore.__seed
//     夹具模式（纯本地，不发写帧——见 js/todoStore.js 夹具模式注）；
//   · 零后端、零 sbt、零宿主实例（不碰 :6138）。
//
// 🔴 诚实申报：面板渲染链是真链（renderTodoTaskList → TodoFace → 展形变），
//   数据面是夹具；断言面 = 真 DOM 几何 / 真事件序列（Playwright 真指针）/ store 真状态。

import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

export const WEB = process.env.TODOFACE_WEB_ROOT
  ? normalize(process.env.TODOFACE_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');

export const SID = 'todo-face-gate-session';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

/** 分组夹具（4 枚标签；chips 行需要 ≥3 枚才量得出「拖过邻 chip」）。 */
export const TAG_FIXTURE = [
  { id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' },
  { id: 'tag-life', name: '生活', color: 'rgb(16,185,129)' },
  { id: 'tag-study', name: '学习', color: 'rgb(245,158,11)' },
  { id: 'tag-idea', name: '灵感', color: 'rgb(168,85,247)' },
];

/** 行夹具（标签归属 + 优先级覆盖四档）。 */
export function itemFixture() {
  const now = Date.now();
  return [
    { id: 'f-1', tagId: 'tag-work', title: '修登录态过期', notes: '', due: now + 3600_000, priority: 3, createdAt: now - 9 * 3600_000 },
    { id: 'f-2', tagId: 'tag-life', title: '缴房租', notes: '', due: now + 2 * 3600_000, priority: 2, createdAt: now - 8 * 3600_000 },
    { id: 'f-3', tagId: 'tag-study', title: '读设计中的设计', notes: '', due: now + 3 * 3600_000, priority: 1, createdAt: now - 7 * 3600_000 },
    { id: 'f-4', tagId: 'tag-idea', title: '记一个交互点子', notes: '', due: null, priority: 0, createdAt: now - 6 * 3600_000 },
  ];
}

/**
 * 启动应用并挂出桌面待办面（胶囊 + 可展开面板）。
 * @param {import('@playwright/test').Page} page
 * @param {{tags?: any[], items?: any[]}} [opts]
 */
export async function bootTodoFace(page, { tags = TAG_FIXTURE, items = itemFixture(), mobile = false } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
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
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction((sid) => {
    // 会话就绪（activeSessionId 命中）即可种夹具
    return !!document.querySelector('#task-list');
  }, SID, { timeout: 20000 });

  await page.evaluate(async ({ sid, tags: t, items: it }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: t, itemList: it });
    const face = await import('/js/todoFace.js');
    face.renderTodoTaskList(sid);
  }, { sid: SID, tags, items });

  // 手机腿（灵动岛批 2026-11-05）：手机层 `#task-list` 整条 display:none（端区分批
  // 口径：手机入口 = 左上圆钮，桌面才是胶囊/岛）⇒ 只等节点在场，不等可见。
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { state: mobile ? 'attached' : 'visible', timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 展开面板（点胶囊 → 等形变落地）。 */
export async function openPanel(page) {
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 10000 });
  await page.waitForTimeout(900); // spring 落地
}

/** 展开筛选栏（chips 行的宿主；面板默认收起筛选栏 —— `.tfl-filter-toggle`）。 */
export async function openFilter(page) {
  await page.locator('.tfl-filter-toggle').click();
  await page.waitForSelector('#task-list .tfl-chip-tag', { state: 'visible', timeout: 10000 });
  await page.waitForTimeout(250);
}

/** 分组表当前顺序（store 真值）+ chips DOM 顺序（渲染真值）。 */
export const readOrder = (page) => page.evaluate(async () => {
  const store = await import('/js/todoStore.js');
  const storeOrder = store.todoStore.tags.map((t) => t.id);
  const domOrder = [...document.querySelectorAll('#task-list .tfl-chip-tag')].map((c) => c.dataset.tagId);
  return { storeOrder, domOrder };
});
