// ask-mail-channel.spec.mjs — ask-mail 批（2026-11-16）前端结构钉。
//
// 作者令（2026-11-16 12:13 意旨）：canvas Ask 目前无双向通信通道；应给
// 「ASK · INJECTED」加上地址，使编排方（Spider）可通过 Mail 向该地址续聊。
//
// 地址链（engine 单源，前端零推导）：
//   ExternalEvent metadata `askMailAddress` → emitInjectedUserEvent(askAddress=)
//   → UiMessage.User.askAddress → WS 帧 / .ui.json 行（条件键：缺席 = 不出键）
//   → 注入泡 label 尾的 `.ask-mail-addr` chip（data-ask-addr 携全文）；
//   dock 输入面不展示地址；注入消息上的地址芯片仍保留。
//
// 本 spec 钉的面（fixture 结构断言，零像素门禁）：
//   T1 ASK · INJECTED 注入泡：chip 在 label 尾、文本/data-ask-addr/title 三处
//      = 引擎字段逐字（ASK · INJECTED 标签同时在场——作者令点名的那个面）；
//   T2 条件键纪律：无 askAddress 的注入行零 chip（缺席 = 旧行为，不编造）；
//   T3 恢复双腿：saveMsg 落的本地缓存行携带 askAddress（restoreFromStorage 族
//      读 m.askAddress——header 先例）；reload 呈现单源 = 后端历史重放（引擎
//      .ui.json 行携 askAddress → buildHistoryRow 透传 → chip 复现）；
//   D1–D3 dock：无会话、收到地址、有会话但无地址、收起时均不渲染地址行。
//
// 数据面 = WS mock 直种（routeWebSocket），零后端、零 sbt：
//   T 组 = agentcontrol-address-chip.spec.mjs 同款（静态文件 catch-all 路由 +
//   mock WS）；注入帧在**首次 getHistory 之后**一次性补发——会话激活竞态归零，
//   且 reload 不再补发（T3 的呈现只能来自 localStorage 缓存恢复腿）。
//   D 组 = canvas-ask-dock.spec.mjs 夹具形态（本机静态 http 服务 + installTicketMock
//   + workspace-open-item 自举；localhost:1 在 canvas 腿无 catch-all ⇒ 真起服务）。
// Run: node node_modules/@playwright/test/cli.js test tests/ask-mail-channel.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';
import { scanRowCenters } from './row-center-scan.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.mjs': 'text/javascript',
};

const ROOT_SID = 'askmail-root-session';
const T0 = Date.now();

/** 客户端/服务端同式的 ask 会话 id 派生（sha256(absPath) 前 8 hex，前缀 ask-）。 */
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

const ASK_ADDR = 'ask:ask-1a2b3c4d'; // 引擎字段逐字钉（chip 文本 = 字段，零推导）

/** 引擎 installAskHandoffFace 的注入帧形态（source="ask" × eventType="inject"）。 */
const handoffFrame = {
  type: 'user', sessionId: ROOT_SID, injected: true,
  source: 'ask', eventType: 'inject',
  text: '来自文件问答 · spec-doc.md\n\n这条文档的结论是什么？\n\n（续聊该问答会话：Mail(address="ask:ask-1a2b3c4d")）',
  askAddress: ASK_ADDR,
  timestamp: T0,
};

/** 无地址的注入行（缺席 = 旧行为；T2 条件键纪律的对照组）。 */
const plainInjectedFrame = {
  type: 'user', sessionId: ROOT_SID, injected: true,
  source: 'mail', eventType: 'inject',
  text: '一封不带 ask 地址的普通注入信。',
  timestamp: T0 + 1,
};

function staticRoutes(page) {
  return page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], activeId: ROOT_SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p === '/agents/Nebula/agent.json') {
      // agentcontrol-address-chip.spec.mjs 同款：agent 定义面（缺它 ⇒ 两个 404 +
      // 注入行渲染腿的 script fetch 报错进 console 面子）
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Spider' }) });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const baselineFiles = ['/css/chat.css', '/js/chat/injectedRows.js'];
      const body = process.env.ROW_CENTER_BASELINE && baselineFiles.includes(p)
        ? execFileSync('git', ['show', `${process.env.ROW_CENTER_BASELINE}:src/main/resources/web${p}`], { cwd: join(HERE, '..') })
        : readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
}

/**
 * 主会话 boot（agentcontrol-address-chip.spec.mjs 同款）+ 注入帧一次性补发。
 * `inject`：在**首次 getHistory 之后**依序补发（会话激活已成 ⇒ user 帧的
 * `sid === state.activeSessionId` 渲染闸不再竞态）；one-shot 旗在连接外——
 * reload 的新连接不补发（T3 的呈现只能来自恢复腿）。
 * `history`：可变 { rows }——getHistory 恒回 rows 快照；T3 在 reload 前塞入
 * 引擎 .ui.json 形态的后端历史行，reload 后的重放即真源呈现。
 */
async function bootMain(page, { inject = [], history = { rows: [] } } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // swReg 逃生门（swReg.js 头注登记的官方反向开关）：SW 脚本抓取不经
    // page.route（SW 系统旁路）⇒ 真打 localhost:1 ⇒ ERR_UNSAFE_PORT ⇒ Chrome
    // 自抛「An unknown error occurred when fetching the script.」——非被测面
    // 噪声，关闸归零（watchErrors 对其它 console error 仍然有牙）。
    localStorage.setItem('nebflow_sw', '0');
  });
  await staticRoutes(page);
  let injected = false; // 连接外 one-shot：reload 换连接也不重发
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
      selfDevice: { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro' },
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: history.rows, hasMore: false, offset: 0 }));
        if (!injected && parsed.sessionId === ROOT_SID) {
          injected = true;
          for (const f of inject) ws.send(JSON.stringify(f));
        }
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://localhost:1' });
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: CANVAS_BASE });
});

// Rendered-page lint: ROW_CENTER_BASELINE serves the two unchanged baseline
// production files; ROW_CENTER_INJECT adds a 4px defect and must exit nonzero.
for (const [device, viewport] of [['desktop', { width: 1280, height: 800 }], ['mobile', { width: 390, height: 844 }]]) {
  for (const theme of ['light', 'dark']) {
    test(`row-center lint ${device} ${theme}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: theme });
      await bootMain(page, { inject: [handoffFrame, plainInjectedFrame] });
      const chip = page.locator('#chat .ask-mail-addr');
      await expect(chip).toHaveCount(1);
      if (process.env.ROW_CENTER_INJECT) await chip.evaluate(el => { el.style.transform = 'translateY(4px)'; });
      if (process.env.ROW_CENTER_SCREENSHOTS) {
        await page.screenshot({ path: `${process.env.ROW_CENTER_SCREENSHOTS}/${process.env.ROW_CENTER_BASELINE ? 'before' : 'after'}-${device}-${theme}.png` });
      }
      const result = await page.evaluate(scanRowCenters);
      console.log('ROW_CENTER_AUDIT', device, theme, JSON.stringify(result));
      expect(result.violations, JSON.stringify(result, null, 2)).toEqual([]);
    });
  }
}

// ── T 组：ASK · INJECTED 注入泡 chip（主会话面）──────────────────────────────

test('T1 ASK · INJECTED row carries the ask chip: text + data-ask-addr + title all verbatim from the engine field', async ({ page }) => {
  const errors = watchErrors(page);
  await bootMain(page, { inject: [handoffFrame] });
  const row = page.locator('#chat .bubble.injected', { hasText: '来自文件问答' });
  await expect(row).toHaveCount(1);
  // 标签面 = 作者令点名的那一块：Ask · Injected（source=ask × eventType=inject
  // 的呈现形——视觉面 CSS 大写，textContent 即此）
  const label = row.locator('.injected-source-label');
  await expect(label).toHaveCount(1);
  await expect(label).toContainText('Ask · Injected');
  // chip 三处同源逐字：文本 / data-ask-addr（复制面）/ title（悬停全文）
  const chip = label.locator('.ask-mail-addr');
  await expect(chip).toHaveCount(1);
  await expect(chip).toHaveText(ASK_ADDR);
  await expect(chip).toHaveAttribute('data-ask-addr', ASK_ADDR);
  await expect(chip).toHaveAttribute('title', ASK_ADDR);
  // Compare actual rendered text and chip centers, allowing 1 CSS px for rounding.
  const centers = await label.evaluate((el) => {
    const text = document.createRange();
    text.selectNodeContents(el.firstChild);
    const a = text.getBoundingClientRect();
    const b = el.querySelector('.ask-mail-addr').getBoundingClientRect();
    const row = el.getBoundingClientRect();
    return { text: (a.top + a.bottom) / 2, chip: (b.top + b.bottom) / 2, row: (row.top + row.bottom) / 2 };
  });
  console.log('ASK label centers', JSON.stringify(centers));
  expect(Math.abs(centers.text - centers.chip), JSON.stringify(centers)).toBeLessThanOrEqual(1);
  const scanSource = scanRowCenters.toString();
  const { violations } = await row.evaluate((el, source) => (0, eval)(`(${source})`)(el), scanSource);
  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
  await chip.evaluate((el) => { el.style.transform = 'translateY(4px)'; });
  const injected = await row.evaluate((el, source) => (0, eval)(`(${source})`)(el), scanSource);
  console.log('Injected displacement audit:', JSON.stringify(injected));
  expect(injected.violations.some(v => v.container.includes('injected-source-label') && v.delta > 1)).toBe(true);
  await chip.evaluate((el) => { el.style.transform = ''; });
  // 正文里的可续聊地址行（编排方 LLM 上下文面）也在场
  await expect(row).toContainText('Mail(address="ask:ask-1a2b3c4d")');
  expect(errors).toEqual([]);
});

test('T2 condition-coded absence: injected rows WITHOUT askAddress render zero chip (never invented)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootMain(page, { inject: [plainInjectedFrame, handoffFrame] });
  const plain = page.locator('#chat .bubble.injected', { hasText: '不带 ask 地址' });
  await expect(plain).toHaveCount(1);
  await expect(plain.locator('.ask-mail-addr')).toHaveCount(0);
  const withAddr = page.locator('#chat .bubble.injected', { hasText: '来自文件问答' });
  await expect(withAddr).toHaveCount(1);
  await expect(withAddr.locator('.ask-mail-addr')).toHaveCount(1);
  expect(errors).toEqual([]);
});

test('T3 restore legs: saveMsg caches askAddress; reload replays the engine .ui.json row with the chip', async ({ page }) => {
  const errors = watchErrors(page);
  const history = { rows: [] };
  await bootMain(page, { inject: [handoffFrame], history });
  await expect(page.locator('#chat .bubble.injected .ask-mail-addr')).toHaveCount(1);
  // 腿①（本地缓存面）：saveMsg 落的行携带 askAddress（restoreFromStorage 族
  // 读 m.askAddress——header 先例；此处直读 LS 单点断言，不借 UI 路径）。
  const cached = await page.evaluate(async (rootSid) => {
    const { LS_SESSIONS_KEY } = await import('/js/state.js');
    const all = JSON.parse(localStorage.getItem(LS_SESSIONS_KEY) || '{}');
    return (all[rootSid] || []).find((m) => m && m.type === 'user' && m.injected) || null;
  }, ROOT_SID);
  expect(cached && cached.askAddress).toBe(ASK_ADDR);
  // 腿②（reload 真源面）：重载的呈现单源 = 后端历史重放（historyReplay →
  // buildHistoryRow → buildInjectedRow 读行上 askAddress）。mock 在 reload 前
  // 塞入引擎 .ui.json 形态的历史行；注入 one-shot 已花 ⇒ chip 只能来自重放腿。
  history.rows.push({
    type: 'user', injected: true, source: 'ask', eventType: 'inject',
    text: handoffFrame.text, askAddress: ASK_ADDR, timestamp: T0,
  });
  await page.reload();
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(500);
  const chip = page.locator('#chat .bubble.injected', { hasText: '来自文件问答' }).locator('.ask-mail-addr');
  await expect(chip).toHaveCount(1);
  await expect(chip).toHaveText(ASK_ADDR);
  await expect(chip).toHaveAttribute('data-ask-addr', ASK_ADDR);
  expect(errors).toEqual([]);
});

// ── D 组：dock 面板头部地址行（canvas 面）───────────────────────────────────

// canvas 腿静态服务（canvas-ask-dock.spec.mjs 同款真 http 服务：无 catch-all
// 路由的面，localhost:1 的 ERR_UNSAFE_PORT 面归零）。懒起 + 单例（workers=1）。
const CANVAS_PORT = Number(process.env.ASK_MAIL_PORT || 8323);
const CANVAS_BASE = `http://127.0.0.1:${CANVAS_PORT}`;
let canvasServerReady = null;
function ensureCanvasServer() {
  if (!canvasServerReady) {
    canvasServerReady = new Promise((resolve, reject) => {
      const server = createServer((req, res) => {
        const url = new URL(req.url, CANVAS_BASE);
        let p = decodeURIComponent(url.pathname);
        if (p === '/') p = '/index.html';
        if (p === '/agents/Nebula/agent.json') {
          // agent 定义面（T 组 staticRoutes 同款；缺它 ⇒ openFile 后两枚 404）
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ name: 'Nebula', displayName: 'Spider' }));
          return;
        }
        const file = normalize(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        try {
          const body = readFileSync(file); // 先读后写头（404 路径不得半途炸响应）
          res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
          res.end(body);
        } catch {
          res.writeHead(404); res.end('not found');
        }
      });
      server.on('error', reject);
      server.listen(CANVAS_PORT, '127.0.0.1', () => resolve());
    });
  }
  return canvasServerReady;
}

/** canvas boot（canvas-ask-dock.spec.mjs 夹具形态）+ askState 回包可配 mailAddress。 */
async function bootCanvas(page, { mailAddress = null } = {}) {
  await ensureCanvasServer();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type === 'askOpen') {
        const name = (m.file && m.file.name) || 'file';
        const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
        const frame = {
          type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8),
          file: (m.file && m.file.absPath) || '', fileName: name, messages: [],
        };
        if (mailAddress) frame.mailAddress = mailAddress; // 引擎字段腿（缺席 = 客户端派生兜底）
        ws.send(JSON.stringify(frame));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }],
      activeId: 'sess-1', folders: [],
    }));
  });
  await page.goto(CANVAS_BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await page.waitForTimeout(600);
}

async function openFile(page, name) {
  const absPath = `/tmp/ask-mail-fixture/${name}`;
  await page.evaluate(({ name: n, absPath: a }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + a, itemType: 'markdown', title: n, content: `# ${n}\n\nmock body\n`, absPath: a, pinned: true },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
  await page.waitForTimeout(300);
  return absPath;
}

/** ask 入口钮（.ask-fab 双挂：ask 本体 + note-fab 同类名——选 ask 本体）。 */
const askFab = (page) => page.locator('.ask-fab:not(.note-fab)');

for (const mailAddress of ['ask:ask-9f8e7d6c', null]) {
  test(`dock never renders address chip with mailAddress=${mailAddress}`, async ({ page }) => {
    const errors = watchErrors(page);
    await bootCanvas(page, { mailAddress });
    const row = page.locator('.ask-dock .ask-addr, .ask-dock [data-ask-addr]');
    await expect(row).toHaveCount(0);
    await openFile(page, 'spec-doc.md');
    await expect(askFab(page)).toBeVisible();
    await askFab(page).click();
    await page.waitForTimeout(600);
    await expect(row).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(row).toHaveCount(0);
    expect(errors).toEqual([]);
  });
}
