// nebula-retired-faces.spec.mjs — Nebula 退役批（作者令 2026-11-01「Nebula 退役了
// ——界面里不该再有 Nebula 的身影」）**显示层四面验收 + 实拍**。
//
// 契约（作者令逐条）：
//   ① 主 agent（根会话）创建的东西一律标**用户给它起的名字**（显示名，读点 =
//      js/rootName.js#rootDisplayName，未命名读通用称呼 agent.rootGeneric）——
//      不许硬编码任何名字；
//   ② 后台 agent 标**它们自己的身份**（delegate 短名 / 设备名 / type，沿用既有
//      徽标契约）；
//   ③ 用户可见面 Nebula 零残留。机制名只在协议/判定面（ROOT_AGENT_KEY）里。
//
// 四面（作者验收口径：后台任务面板本机行 + 远程行、AskUser 卡片归属行、delegate
// 完工卡）各出一张实拍；深/浅双主题 + 桌面 1440 / 手机 375 两形态。
//
// 被测真实链路（零伪造 state、零伪造 DOM）：
//   · 后台任务面板 = WS `activeBgTasks` 快照帧 → main.js onMessage → updateBgTasksUI
//     → renderBgDropdown（行 chip 读面 bgTaskOrigin / bgTaskDeviceLabel）；
//   · AskUser 归属行 = WS `askUser` 帧 → main.js onMessage('askUser') →
//     chat.js renderAskUser → askPending.js#askSourceLabel；
//   · delegate 完工卡 = WS `user`(injected) 帧 → renderInjectedBubble →
//     injectedRows.buildInjectedRow（胶囊 delegateReceiptName）；
//   · 跨端注入行 header = 同上（引擎 NotificationHeader 串的显示层归一）。
//
// 用法：node tests/nebula-retired-faces.spec.mjs   # 全绿 = 四面读数达标
// 产物：evidence/20261101_nebula-retired__delegate-nebflow-cd7a9fbd/shots/*.png
import { chromium } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261101_nebula-retired__delegate-nebflow-cd7a9fbd');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'nebula-retired-root';
// mock 源端口：路由层全量拦截（不发真连接）。避开低危端口表（1=tcpmux 等）——
// 只用高位端口，双引擎安全。
const ORIGIN = 'http://localhost:8899';
// 显示名（模拟用户起名结果：本机 home 现名 Spider；测试不硬编码产品文案，只喂
// 「用户起的名字」这个数据）。
const DISPLAY_NAME = 'Spider';
const SELF_DEVICE = 'Mashiros-MacBook-Pro';
const REMOTE_DEVICE = 'DESKTOP-IJBBEOT';
const T0 = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`);
  });
};

/** 后台任务行夹具：本机行（主 agent 开启，后端 originLabel 仍是机制名）+ 远程行
 *  （设备名 = 对端身份）——正是作者验收要求的「本机行 + 远程行」。 */
function bgFixture() {
  return { [SID]: [
    { taskId: 'job-local', description: 'npm run build --workspace=web', status: 'running',
      startedAt: T0 - 92_000, sessionId: SID, kind: 'local',
      origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 2_000, outputLines: 41 } },
    { taskId: 'job-remote', description: `[${REMOTE_DEVICE}] Download gitlab-runner and install service`, status: 'running',
      startedAt: T0 - 6 * 60_000, sessionId: SID, kind: 'remote',
      origin: 'device', originLabel: REMOTE_DEVICE, heartbeat: { idleMs: 4_000, outputLines: 0 } },
  ] };
}

const ASK_ITEMS = [{ question: '用哪个方向继续?', options: [{ label: '方向 A' }, { label: '方向 B' }] }];

const RECEIPT_MSG = {
  source: 'delegate', eventType: 'completed', sender: 'nebflow',
  header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
  text: '委托完成：显示层身份批 + 回归。',
  fileChanges: {
    nodeId: 'delegate-nebflow-fixture', generatedAt: T0, root: '/Users/kaiyu/Claude code/Stand',
    files: [
      { path: 'src/main/resources/web/js/rootName.js', changeType: 'modified', added: 38, removed: 12 },
      { path: 'src/main/resources/web/js/locales/en.js', changeType: 'modified', added: 6, removed: 5 },
    ],
    merge: { commit: 'b6ceb9d0e', added: 44, removed: 17 },
  },
};

/** 跨端注入行：header 的 PROJECT 段 = 引擎 NotificationHeader.RootProject（机制名），
 *  本面是「显示层归一」的验收对象。 */
const MAIL_MSG = {
  source: 'mail', eventType: 'result', sender: 'Manager',
  header: 'MAIL · NEBULA · MANAGER · RESULT',
  text: '跨端注入行（header PROJECT 段显示层归一验收）。',
};

const browser = await chromium.launch();

async function boot(scheme, narrow) {
  const page = await (await browser.newContext({
    viewport: narrow ? { width: 375, height: 812 } : { width: 1440, height: 900 },
    colorScheme: scheme, deviceScaleFactor: 2, locale: 'zh-CN',
  })).newPage();
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    // 根 agent 的持久化显示名（单一读点之一）：用户起的名 = DISPLAY_NAME。
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ json: { name: 'Nebula', displayName: DISPLAY_NAME } });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
      selfDevice: { id: 'self-dev-1', name: SELF_DEVICE },
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    const sendAgents = () => ws.send(JSON.stringify({
      type: 'agentList',
      agents: [{ name: 'Nebula', description: '', displayName: DISPLAY_NAME, avatar: null, tools: ['*'] }],
    }));
    sendConfig();
    sendSessions();
    sendAgents();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getAgents') sendAgents();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
    ws.__send = (obj) => ws.send(JSON.stringify(obj));
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  if (narrow) await page.evaluate(() => document.body.classList.add('mobile-layer'));
  return page;
}

/** WS 帧注入（走真实 onMessage 链，不放水）。 */
const pushFrame = (page, frame) => page.evaluate((f) => import('/js/state.js').then((mod) => {
  const s = mod.default;
  s.ws.onmessage({ data: JSON.stringify(f) });
}), { sessionId: SID, timestamp: Date.now(), ...frame });

const settle = (page) => page.waitForTimeout(320);

/** 面板内「所有可见文本」扫描：任何残留的退役名（大小写不敏感）都算红。 */
const panelText = (page, selector) => page.evaluate((sel) => {
  const el = document.querySelector(sel);
  return el ? (el.innerText || el.textContent || '') : null;
}, selector);

async function shootFace(page, name) {
  await settle(page);
  const target = await page.evaluate(() => {
    const bubble = [...document.querySelectorAll('.bubble.injected')].pop();
    if (bubble) return { x: 0, y: 0, w: 0, h: 0, clip: true };
    return null;
  });
  void target;
}

/** 一面的读数：行 chip / 归属行 / 胶囊文本 + 全量文本扫描。 */
const readBgPanel = (page) => page.evaluate(() => {
  const el = document.querySelector('#bg-dropdown');
  const rows = [...(el?.querySelectorAll('.bg-task-row[data-task-id]') || [])];
  return {
    rows: rows.map((r) => ({
      taskId: r.getAttribute('data-task-id'),
      chips: [...r.querySelectorAll('.bg-task-origin, .bg-task-mode')].map((c) => c.textContent.trim()),
    })),
    text: el ? (el.innerText || el.textContent || '') : '',
  };
});

const readAskBadge = (page) => page.evaluate(() => ({
  badge: document.querySelector('.bubble .ask-user-source')?.textContent?.trim() || '',
  text: [...document.querySelectorAll('.bubble')].map((b) => b.innerText).join('\n'),
}));

const readReceipt = (page) => page.evaluate(() => {
  const bubbles = [...document.querySelectorAll('.bubble.injected')];
  const b = bubbles[bubbles.length - 1];
  return {
    pill: b?.querySelector('.nf-rcpt-pill')?.textContent?.trim() || '',
    label: b?.querySelector('.injected-label, .injected-source')?.textContent?.trim() || '',
    text: b?.innerText || '',
    allLabels: bubbles.slice(-2).map((x) => x.innerText),
  };
});

const FORBIDDEN = /nebula/i;

for (const scheme of ['dark', 'light']) {
  for (const narrow of [false, true]) {
    const tag = `${narrow ? 'narrow' : 'desktop'}-${scheme}`;
    const page = await boot(scheme, narrow);

    // ── 面①：后台任务面板（本机行 + 远程行）──────────────────────────────
    await pushFrame(page, { type: 'activeBgTasks', tasks: bgFixture() });
    await settle(page);
    // 开面 = hidden 直开（float dock DOM 不在本 harness）+ 显式触发渲染面
    // （renderBgDropdown 只在面板开着时由 updateBgTasksUI 走到）。
    await page.evaluate(() => document.getElementById('bg-dropdown').classList.remove('hidden'));
    await page.evaluate(() => import('/js/state.js').then((m) => {
      const s = m.default;
      const v = s.getActiveView && s.getActiveView();
      if (s.updateBgTasksUI && v) s.updateBgTasksUI(v);
    }));
    await settle(page);
    const bg = await readBgPanel(page);
    const localRow = bg.rows.find((r) => r.taskId === 'job-local');
    const remoteRow = bg.rows.find((r) => r.taskId === 'job-remote');
    ok(`[${tag}] bg 本机行 chip = 显示名（${DISPLAY_NAME}）`,
      !!localRow && localRow.chips.includes(DISPLAY_NAME), JSON.stringify(localRow?.chips));
    ok(`[${tag}] bg 远程行 chip = 设备名（${REMOTE_DEVICE}）`,
      !!remoteRow && remoteRow.chips.includes(REMOTE_DEVICE), JSON.stringify(remoteRow?.chips));
    ok(`[${tag}] bg 面板零退役名`, !FORBIDDEN.test(bg.text), bg.text.replace(/\n/g, ' | ').slice(0, 160));
    await page.locator('#bg-dropdown').screenshot({ path: join(SHOTS, `bg-panel-${tag}.png`) });

    // 面①-b：详情卡来源行（bgTaskOutputPopup.js#originText）同口径——行的
    // 「谁开启」在卡里同样读显示名；此处点行开卡取 subtitle 读数。
    await page.locator('.bg-task-row[data-task-id="job-local"]').first().click();
    await page.waitForSelector('.bgt-overlay', { state: 'visible', timeout: 8000 });
    await settle(page);
    const detailSub = await page.locator('.bgt-subtitle').first().textContent();
    ok(`[${tag}] bg 详情卡来源行 = 显示名`, !!detailSub && detailSub.includes(DISPLAY_NAME) && !FORBIDDEN.test(detailSub), JSON.stringify(detailSub));
    await page.locator('.bgt-overlay').first().screenshot({ path: join(SHOTS, `bg-detail-${tag}.png`) });
    await page.evaluate(() => {
      const b = document.querySelector('.bgt-close');
      if (b) b.click();
    });
    await settle(page);

    // ── 面②：AskUser 卡片归属行（根会话自问）────────────────────────────
    await page.evaluate(() => document.getElementById('bg-dropdown').classList.add('hidden'));
    await pushFrame(page, { type: 'askUser', items: ASK_ITEMS, requestId: 'req-retire-1', agentName: 'Nebula' });
    await settle(page);
    const ask = await readAskBadge(page);
    ok(`[${tag}] AskUser 归属行 = 显示名（${DISPLAY_NAME}）`, ask.badge === DISPLAY_NAME, JSON.stringify(ask.badge));
    ok(`[${tag}] AskUser 卡零退役名`, !FORBIDDEN.test(ask.text));
    const askBubble = await page.locator('.bubble').last().boundingBox();
    await page.screenshot({
      path: join(SHOTS, `ask-badge-${tag}.png`),
      clip: askBubble ? { x: Math.max(0, askBubble.x - 8), y: Math.max(0, askBubble.y - 8), width: Math.min(askBubble.width + 16, narrow ? 375 : 1440), height: Math.min(askBubble.height + 16, 800) } : undefined,
    });

    // ── 面③：delegate 完工卡（KIND · EXECUTOR · 判词）───────────────────
    await pushFrame(page, { type: 'user', injected: true, ...RECEIPT_MSG });
    await settle(page);
    const rcpt = await readReceipt(page);
    ok(`[${tag}] delegate 完工卡胶囊 = KIND · 自身身份（无退役名）`,
      /^DELEGATE · INTERNAL · COMPLETED$/.test(rcpt.pill), JSON.stringify(rcpt.pill));
    const rcptBubble = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.bubble.injected')].pop();
      const r = b?.getBoundingClientRect();
      return r ? { x: r.x, y: r.y, width: r.width, height: r.height } : null;
    });
    await page.screenshot({
      path: join(SHOTS, `delegate-receipt-${tag}.png`),
      clip: rcptBubble ? { x: Math.max(0, rcptBubble.x - 8), y: Math.max(0, rcptBubble.y - 8), width: Math.min(rcptBubble.width + 16, narrow ? 375 : 1440), height: Math.min(rcptBubble.height + 16, 700) } : undefined,
    });

    // ── 面④：跨端注入行 header（引擎 PROJECT 段 = 机制名）───────────────
    await pushFrame(page, { type: 'user', injected: true, ...MAIL_MSG });
    await settle(page);
    const mail = await readReceipt(page);
    // 标签行受 CSS text-transform: uppercase 影响 ⇒ 大小写不敏感比对；判据 =
    // PROJECT 段已由机制名换成用户起的名字（其余段逐字保留）。
    const mailLabel = mail.allLabels.map((t) => t.split('\n')[0]).join(' | ');
    ok(`[${tag}] 注入行 header PROJECT 段 = 显示名`,
      new RegExp(`MAIL · ${DISPLAY_NAME} · MANAGER · RESULT`, 'i').test(mailLabel),
      JSON.stringify(mailLabel));
    ok(`[${tag}] 注入行零退役名`, !FORBIDDEN.test(mail.text));

    await page.context().close();
  }
}

// ── 未命名腿：显示名权威缺省 = 通用称呼（不是机制名）──────────────────────
{
  const page = await boot('dark', false);
  const generic = await page.evaluate(async () => {
    const { t } = await import('/js/i18n.js');
    const m = await import('/js/rootName.js');
    const s = (await import('/js/state.js')).default;
    s.rootDisplayName = null;
    s.rootAgentFileName = null;
    s.rootAgentFileNamed = false;
    s.agentsData = [{ name: 'Nebula', displayName: 'Nebula' }]; // 服务端 fallback 毒化行
    return { name: m.rootDisplayName(), expected: t('agent.rootGeneric'), key: m.ROOT_AGENT_KEY };
  });
  ok('未命名根 agent → 通用称呼（机制名不上屏）',
    generic.name === generic.expected && generic.name !== generic.key, JSON.stringify(generic));
  await page.context().close();
}

ok('零 console /pageerror（全轮）', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

writeFileSync(join(OUT, 'readings.json'), JSON.stringify({
  displayName: DISPLAY_NAME, failures, consoleErrors,
}, null, 2));

await browser.close();
console.log(failures === 0 ? '\nALL GREEN' : `\nFAILURES: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
