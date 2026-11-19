// evfmt-rc-header-persist.spec.mjs — R-C 补（web 面）行为门：注入气泡**四段式 header 的
// 客户端持久化往返**（「气泡四段式统一」批 2026-09-15 收尾 · 作者裁定 root 2026-09-15 ③）。
//
// 缺陷形态（本件先红后绿的判据）：
//   引擎面（R-A，已落 main）在唯一发射点 `AgentActor#emitInjectedUserEvent` 产出的整串
//   `KIND · PROJECT · SUBJECT · STATE` 同时进 **WS 帧**与 **.ui.json 落盘**；但**客户端
//   localStorage 缓存**这一腿漏了：注入帧的 `saveMsg` 落盘字段集不含 `header`
//   （main.js 的 `onMessage('user')` 构造的对象字面量），而**两条恢复读路径都读 `m.header`**
//   （persistence.js 的 `restoreFromStorage` / `restoreFromBackendHistory`）⇒ 任何走**缓存**
//   的重载都把 header 丢成 `undefined`，回落成 `injectedSourceLabel` 的旧标签（默认降级）。
//
// 本件断言**落在持久化数据上**（禁以运行时内存态兜底渲染蒙过）：
//   T1 一帧注入 → ① live 标签 === 帧上 header 逐字（发送时刻基线，逐段对照）
//                ② **localStorage 里那条注入行的 `header` 字段 === 帧上 header 逐字**
//   T2 page.reload()（真·重载，DOM 全清）+ **后端历史为空**（mock 只回空 historyPage）
//      ⇒ 唯一数据源 = localStorage 缓存 ⇒ `restoreFromStorage()` 渲染出的标签
//      必须 === 发送时刻的 header，且**逐段**（4 段，段值/顺序/大写逐字）一致。
//   T3 防回归：**旧缓存行（无 `header` 键 / `header: ''`）⇒ 不崩**，按既定降级口径渲染
//      回落标签（`injectedSourceLabel` 真函数求值），页内零 pageerror。
//
// 隔离性：纯静态夹具（`page.route` 从 `src/main/resources/web` 供文件）+ mock WS
// （`page.routeWebSocket`），**不起服务、不占端口、不触宿主实例**——同
// `tests/node-bubble-header.spec.mjs` 的夹具形态。
//
// Run: node node_modules/@playwright/test/cli.js test tests/evfmt-rc-header-persist.spec.mjs

import { test, expect } from '@playwright/test';
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

const ROOT_SID = 'evfmt-rc-root-session';

// 引擎已渲染的四段式 header（`NotificationHeader.render` 的形态：`KIND · PROJECT ·
// SUBJECT · STATE`，段值全大写 + ` · ` 分隔）。刻意取 **CHAIN 腿**：前端回落
// `injectedSourceLabel` 对 chain 只产出两段 TitleCase（'Chain · Completed'），与四段式
// 形态**可判读地不同** ⇒ 回落不可能蒙过判据（红/绿二值真实）。
const CHAIN_HEADER = 'CHAIN · NEBULA · CHAIN-7F3A · COMPLETED';
// 旧口径回落（`injectedSourceLabel` 的通用分支 = `SOURCE · sender · EVENT_TYPE`，
// TitleCase、三段、**无 PROJECT 段**）：T3 把它当**逐字冻结的降级基线**钉住
// （页内用真函数求值交叉核对 ⇒ 既防手抄漂移，也防降级口径被悄悄改写）。
const CHAIN_FALLBACK = 'Chain · nebflow/chain-7f3a · Completed';
const CHAIN_SEGMENTS = ['CHAIN', 'NEBULA', 'CHAIN-7F3A', 'COMPLETED'];

const CHAIN_FRAME = {
  type: 'user',
  text: "[Chain 'chain-7f3a' completed]\n摘要正文",
  injected: true,
  source: 'chain',
  eventType: 'completed',
  sender: 'nebflow/chain-7f3a',
  sessionId: ROOT_SID,
  header: CHAIN_HEADER,
};

/** 静态夹具 + mock WS（后端历史**恒为空** ⇒ 缓存是唯一数据源）。装一次即可跨重载复用。 */
async function installMocks(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
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
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      // 后端历史**恒空**：本件判的是客户端持久化往返，后端腿（R-A 已落）不参与。
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
}

/** 导航 + 就绪等待（同一 page 复用：第二次调用即真·重载）。 */
async function gotoApp(page) {
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    if (!(s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1)) return false;
    const { activeView } = await import('/js/chatView.js');
    return !!(activeView && activeView.dom && activeView.dom.chat);
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 首次装配：装夹具 + 首屏。 */
async function bootApp(page) {
  await installMocks(page);
  await gotoApp(page);
}

/** 走真实 WS 处理链投一帧（同 live 广播的解码路径）。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 当前所有注入气泡标签文本（按出现顺序）。 */
function labels(page) {
  return page.locator('#chat .row.user .bubble.injected .ask-label.injected-source-label').allTextContents();
}

/** localStorage 缓存里该会话的注入行（`saveMsg` 落盘后的真实数据）。 */
function cacheInjectedRow(page) {
  return page.evaluate(async (sid) => {
    const key = (await import('/js/state.js')).LS_SESSIONS_KEY;
    const all = JSON.parse(localStorage.getItem(key) || '{}');
    const arr = all[sid] || [];
    const row = arr.find((m) => m && m.type === 'user' && m.injected);
    return row ? { row, hasHeaderKey: Object.prototype.hasOwnProperty.call(row, 'header') } : null;
  }, ROOT_SID);
}

/** 清空聊天面后调**真** `restoreFromStorage()`（缓存恢复路径；DOM 全清 ⇒ 只能来自持久化数据）。 */
function cacheRender(page) {
  return page.evaluate(async () => {
    const { activeView } = await import('/js/chatView.js');
    if (activeView && activeView.dom && activeView.dom.chat) activeView.dom.chat.innerHTML = '';
    const { restoreFromStorage } = await import('/js/persistence.js');
    restoreFromStorage();
  });
}

/** 在页内用**真** `injectedSourceLabel` 求回落标签（T3 的期望值，禁手抄字面量）。 */
function fallbackLabel(page, source, eventType, sender, sourceTeam, intake) {
  return page.evaluate(async (a) => {
    const { injectedSourceLabel } = await import('/js/chat.js');
    return injectedSourceLabel(...a);
  }, [source, eventType, sender, sourceTeam, intake]);
}

test('T1 注入帧的四段式 header 落在持久化数据上（saveMsg 字段集）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  await inject(page, CHAIN_FRAME);

  // ① 发送时刻基线：live 标签 === 帧上 header 逐字（引擎单一来源）。
  const live = await labels(page);
  expect(live).toEqual([CHAIN_HEADER]);
  expect(CHAIN_HEADER.split(' · ')).toEqual(CHAIN_SEGMENTS); // 逐段对照（4 段）

  // ② 持久化数据本身必须带 header（本件的红点）。
  const cached = await cacheInjectedRow(page);
  expect(cached).not.toBeNull();
  expect(cached.row.source).toBe('chain'); // 锚：确认找到的就是那条注入行
  expect(cached.row.injected).toBe(true);
  expect(cached.row.header).toBe(CHAIN_HEADER);

  expect(pageErrors).toEqual([]);
});

test('T2 重载后（后端历史为空）四段式 header 逐段完整且逐字一致', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  await inject(page, CHAIN_FRAME);
  const headerAtSend = (await labels(page))[0];
  expect(headerAtSend).toBe(CHAIN_HEADER);

  // 真·重载：DOM 全清、后端历史为空 ⇒ 唯一数据源 = localStorage 缓存。
  await bootApp(page);

  await cacheRender(page);
  const restored = await labels(page);
  expect(restored).toEqual([headerAtSend]); // 与发送时刻逐字一致
  expect(restored[0].split(' · ')).toEqual(CHAIN_SEGMENTS); // 逐段完整

  // 逐段对照读数（留证）：发送时刻 header / 持久化字段 / 重载后渲染 —— 三者同源逐字。
  console.log('[evfmt-rc] header round-trip reading: ' + JSON.stringify({
    headerAtSend,
    persistedHeader: (await cacheInjectedRow(page)).row.header,
    restoredLabel: restored[0],
    segAtSend: headerAtSend.split(' · '),
    segRestored: restored[0].split(' · '),
    segmentsEqual: JSON.stringify(headerAtSend.split(' · ')) === JSON.stringify(restored[0].split(' · ')),
  }));

  expect(pageErrors).toEqual([]);
});

test('T3 防回归：旧缓存行无 header ⇒ 不崩、按既定降级口径渲染', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 旧数据形态：一条无 `header` 键、一条 `header: ''`（空串 = 缺席）。
  await page.evaluate(async (sid) => {
    const key = (await import('/js/state.js')).LS_SESSIONS_KEY;
    localStorage.setItem(key, JSON.stringify({
      [sid]: [
        { type: 'user', text: 'legacy row A', injected: true, source: 'chain', eventType: 'completed', sender: 'nebflow/chain-7f3a' },
        { type: 'user', text: 'legacy row B', injected: true, source: 'chain', eventType: 'completed', sender: 'nebflow/chain-7f3a', header: '' },
      ],
    }));
  }, ROOT_SID);

  await bootApp(page);

  const want = await fallbackLabel(page, 'chain', 'completed', 'nebflow/chain-7f3a', undefined, undefined);
  expect(want).toBe(CHAIN_FALLBACK); // 期望值来自真函数，非手抄

  await cacheRender(page);
  expect(await labels(page)).toEqual([want, want]);
  expect(pageErrors).toEqual([]);
});
