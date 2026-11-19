// turn-activity-stream.spec.mjs — 回合活动流条（#activity-strip）防复活钉。
//
// 历史：该面板由对话层活性批（2026-10-22）落地——#input-area 顶部、#queue-bar
// 之上的一条可折叠活动流（标题「活动」+「N 条」+ delegate 更新/工具调用实时行
// 带 HH:MM 时钟），实现件 js/main/turnActivity.js + state.sessionActivity 账本 +
// liveness.css ①段 + locale activity.title/activity.count。作者此前下过删除令，
// 2026-10-23 补落地并逐字确认：「这个活动面板我不是让删掉吗，就是对话框上面
// 这个」⇒ 整链退役（UI + 事件接线 + 账本 + CSS + locale 键）。
//
// 本 spec 钉「它不存在」：
//   R 组  静态源钉：模块文件已删、index.html 无挂载点、state 无账本、
//         CSS 无规则、locale 无键（防复活第一道——谁顺手加回文件/键即红）。
//   A 组  运行时钉：真实 boot（静态路由 + 假后端 + 真实 ws.js 分发）后
//         #activity-strip 不在 DOM；注入五类活动帧（agentStart/agentToolStart/
//         delegateWaiting/agentDone/backgroundTaskUpdate）⇒ 面板仍不存在
//         （防「只加回接线、忘删渲染」的半复活）；账本槽不得回来。
//   N 组  零波及钉：邻接元素（#input-area/#queue-bar/#input/#input-bar/
//         #slash-dropdown/#conn-banner 挂载点）原样在场——只删这一块，不动邻居。
//   P 组  console 零新增错误（boot 与注帧过程干净，无引用残断）。
//
// harness = 原同款：静态路由 WEB 目录 + routeWebSocket 假后端 + 真实 ws.js
// 分发路径注入帧。零后端、零 sbt、零构建。
//
// Run: node tests/turn-activity-stream.spec.mjs
// 可选 env：PORT=8188、HEADLESS=0

import { chromium } from 'playwright';
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8188);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'cas-root-session';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
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
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
  await page.goto(`http://127.0.0.1:${PORT}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(600);
}

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 面板存在性 + 邻接元素在场性的联合探针。 */
function probe(page) {
  return page.evaluate(() => {
    const $ = (id) => document.getElementById(id);
    return {
      strip: !!$('activity-strip'),
      // 邻接元素（零波及面）：原样在场。
      inputArea: !!$('input-area'),
      queueBar: !!$('queue-bar'),
      slashDropdown: !!$('slash-dropdown'),
      input: !!$('input'),
      inputBar: !!$('input-bar'),
      connBanner: !!$('conn-banner'),
    };
  });
}

const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};

// ── R 组：静态源钉（文件系统面，不起浏览器） ────────────────────────────
console.log('── R 组：静态源钉 ──');
ok('R1 module file removed', !existsSync(join(WEB, 'js', 'main', 'turnActivity.js')));
const indexHtml = readFileSync(join(WEB, 'index.html'), 'utf8');
ok('R2 index.html has no #activity-strip mount', !indexHtml.includes('activity-strip'));
ok('R3 css has no #activity-strip rules', !readFileSync(join(WEB, 'css', 'liveness.css'), 'utf8').includes('activity-strip'));
for (const loc of ['zh-CN', 'en']) {
  const txt = readFileSync(join(WEB, 'js', 'locales', `${loc}.js`), 'utf8');
  ok(`R4 locale ${loc} has no activity.title/activity.count keys`,
    !/['"]activity\.title['"]/.test(txt) && !/['"]activity\.count['"]/.test(txt));
}
// 全 js 源零引用（main/state/ws/input 等——含 noteActivity/clearActivity/sessionActivity）。
const { readdirSync, statSync } = await import('node:fs');
const offenders = [];
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else if (name.endsWith('.js') && /noteActivity|clearActivity|renderActivityStrip|sessionActivity|activity-strip/.test(readFileSync(p, 'utf8'))) {
      offenders.push(p.slice(WEB.length + 1));
    }
  }
};
walk(join(WEB, 'js'));
ok('R5 zero source references across web/js', offenders.length === 0, { offenders });

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const consoleErrors = [];
const missingJs = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
page.on('pageerror', (e) => consoleErrors.push(String(e).slice(0, 400)));
// 404 盘点：豁免已知既有 harness 面（/agents/*/agent.json 静态路由本就不供，
// 与本钉无关）；任何 /js/（模块图）404 = 引用残断，红。
page.on('response', (r) => {
  if (r.status() === 404) {
    const u = r.url();
    if (!/\/agents\/[^/]+\/agent\.json$/.test(u)) missingJs.push(u);
  }
});

try {
  await bootApp(page);

  // ── A0 + N 组：boot 后面板不存在、邻接元素在场 ──
  console.log('── N 组：boot 后面板不存在 + 邻接零波及 ──');
  let g = await probe(page);
  ok('A0 #activity-strip absent after boot', g.strip === false);
  ok('N1 #input-area present', g.inputArea === true);
  ok('N2 #queue-bar present (queue bar untouched)', g.queueBar === true);
  ok('N3 #slash-dropdown present', g.slashDropdown === true);
  ok('N4 #input present (chat input untouched)', g.input === true);
  ok('N5 #input-bar present', g.inputBar === true);
  ok('N6 #conn-banner mount present (liveness banner untouched)', g.connBanner === true);

  // ── A 组：五类活动帧注入 ⇒ 面板仍不存在、账本槽不复活 ──
  console.log('── A 组：活动帧注入 ⇒ 面板不复活 ──');
  await inject(page, {
    type: 'agentStart', sessionId: SID, rootSessionId: SID,
    agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x',
    name: '实现批', agentType: 'delegate', taskTitle: '活动流批',
  });
  await inject(page, {
    type: 'agentToolStart', sessionId: SID, rootSessionId: SID,
    agentId: 'delegate-nebflow-b1', nodeSessionId: 'delegate-nebflow-b1-x',
    label: 'Read src/main/scala/nebflow/gateway/WsHub.scala',
  });
  await inject(page, { type: 'delegateWaiting', sessionId: 'delegate-nebflow-b1-x', mode: 'merge' });
  await inject(page, {
    type: 'backgroundTaskUpdate', sessionId: SID, rootSessionId: SID,
    taskId: 't1', description: 'sbt compile', status: 'running',
  });
  await inject(page, { type: 'agentDone', sessionId: SID, rootSessionId: SID, agentId: 'delegate-nebflow-b1' });
  await sleep(250);
  g = await probe(page);
  ok('A1 strip still absent after 5 activity frames', g.strip === false);
  const ledgerGone = await page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return !('sessionActivity' in s);
  });
  ok('A2 state.sessionActivity ledger slot stays retired', ledgerGone === true);
  ok('N7 neighbors still present after activity frames',
    g.inputArea && g.queueBar && g.input && g.inputBar && g.slashDropdown && g.connBanner);

  // ── P 组：console 干净（豁免已知既有 harness 404，模块图零缺件）──
  // 已知既有面：/agents/Nebula/agent.json 静态路由不供 ⇒ 一对固定报错
  // （资源 404 + script fetch）；console 文本不带 URL，故按已知字符串豁免，
  // 模块图缺件由 P2 的 404 URL 盘点兜住。
  console.log('── P 组：console 干净 ──');
  const KNOWN_HARNESS_ERRORS = [
    'Failed to load resource: the server responded with a status of 404 (Not Found)',
    'An unknown error occurred when fetching the script.',
  ];
  const unexpected = consoleErrors.filter((t) => !KNOWN_HARNESS_ERRORS.includes(t));
  ok('P1 zero unexpected console errors during boot + injections', unexpected.length === 0, { errors: unexpected });
  ok('P2 no /js/ module 404 (no dangling import resurrects the retired module)', missingJs.length === 0, { missing: missingJs });

  console.log('\nturn-activity-stream.spec (anti-resurrection) done');
} finally {
  await browser.close();
}
