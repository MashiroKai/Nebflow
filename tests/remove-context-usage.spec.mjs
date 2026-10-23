// remove-context-usage.spec.mjs — 桌面端上下文用量显示（ctxring 环族）防复活钉。
//
// 历史：Header 上下文用量环（ctxring 批，2026-09-15 方案 A 起；2026-09-17 恢复
// 「环上拖动」= 压缩阈值唯一入口）+ 两个弹窗头部的同款用量环（flow/bg agent
// popup）。作者令（2026-10-23 微信）逐字「把桌面端的上下文用量显示也去掉」——
// 「也」= 与同日「活动面板」移除同族口径：整链退役，不留 UI 残迹。
// 随批删除：环渲染器（main.js）+ 环上拖动模块（js/ctxthresh.js 整文件）+
// 弹窗环（flowAgentPopup/bgAgentPopup 的 updatePopupCtxRing/挂载点/样式）+
// chat.css 环族段 + locale `ctxthresh.title` + 陈旧钉脚本（e2e-ctxthresh.cjs、
// tests/tmp-usage-*）。数据管道保留：usageUpdate → state.sessionModelInfo
// （弹窗模型徽章仍消费 `.model`；后端压缩阈值行为零改动）。
//
// 本 spec 钉「它不存在」：
//   R 组  静态源钉：拖动模块与陈旧钉脚本已删、index.html 无挂载点、
//         CSS 无环族规则、locale 无键、全 web/js 零活引用
//         （谁顺手加回文件/键/渲染点即红）。
//   A 组  运行时钉：真实 boot（静态路由 + 假后端 + 真实 ws.js 分发）后
//         环宿主/环 DOM/注入样式全不在；注入 usageUpdate/done 用量帧
//         ⇒ 环仍不存在（防「只删渲染、留半截」的半复活）；
//         state 管道照常写入（弹窗徽章数据面存活）；
//         boot 不得外发 getCompactThreshold/setCompactThreshold（拖动模块
//         是其唯一前端消费方，回来即红）。
//   N 组  零波及钉：头部邻接元素（侧栏钮/会话名/搜索/记忆钮/bg 胶囊挂载点）
//         原样在场——只删这一块，不动邻居。
//   P 组  console 干净 + /js/ 模块图零 404（悬空 import 即红）。
//
// harness = turn-activity-stream.spec.mjs 同款：静态路由 WEB 目录 +
// routeWebSocket 假后端 + 真实 ws.js 分发路径注入帧。零后端、零 sbt、零构建。
//
// Run: node tests/remove-context-usage.spec.mjs
// 可选 env：PORT=8189、HEADLESS=0

import { chromium } from 'playwright';
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8189);

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
      // 记录客户端出站帧类型（A5 钉：拖动模块的 boot 补拉不得回来）。
      if (parsed.type) wsInboundTypes.push(String(parsed.type));
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

/** 环存在性 + 邻接元素在场性 + state 管道面的联合探针。 */
function probe(page) {
  return page.evaluate(async () => {
    const s = (await import('/js/state.js')).default;
    return {
      hostMount: !!document.getElementById('header-model-info'),
      ringDom: document.querySelectorAll('.ctx-ring-wrap, .ctx-ring-svg, .ctx-ring-pct, .ctx-ring-threshold').length,
      injectedSheet: !!document.getElementById('ctxring-css'),
      sliderRole: document.querySelectorAll('[role="slider"][aria-label*="阈值"], [aria-label*="Compaction threshold"]').length,
      // 邻接元素（零波及面）：原样在场。
      sidebarToggle: !!document.getElementById('sidebar-toggle'),
      sessionName: !!document.getElementById('session-name'),
      searchBtn: !!document.getElementById('search-btn'),
      memoryBtn: !!document.getElementById('memory-btn'),
      bgIndicator: !!document.getElementById('bg-indicator'),
      headerLeft: !!document.querySelector('#header .header-left'),
      // state 面：退役的是**显示**，管道与渲染钩子槽不得复活。
      pipelineAlive: !!(s.sessionModelInfo && typeof s.sessionModelInfo === 'object'),
      rendererHookGone: typeof s.updateHeaderModelInfo !== 'function',
      compactConstGone: !('COMPACT_THRESHOLD' in s),
    };
  });
}

const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};

// ── R 组：静态源钉（文件系统面，不起浏览器） ────────────────────────────
console.log('── R 组：静态源钉 ──');
ok('R1 ctxthresh.js drag module removed', !existsSync(join(WEB, 'js', 'ctxthresh.js')));
ok('R1b stale pin scripts removed',
  !existsSync(join(HERE, '..', 'scripts', 'e2e-ctxthresh.cjs'))
  && !existsSync(join(HERE, 'tmp-usage-e2e.mjs'))
  && !existsSync(join(HERE, 'tmp-usage-display-verify.mjs')));
const indexHtml = readFileSync(join(WEB, 'index.html'), 'utf8');
ok('R2 index.html has no ring mount point', !indexHtml.includes('header-model-info'));
let cssHit = null;
for (const name of readdirSync(join(WEB, 'css'))) {
  if (!name.endsWith('.css')) continue;
  const txt = readFileSync(join(WEB, 'css', name), 'utf8');
  const m = txt.match(/ctx-ring|ctx-model-label|header-model-info/);
  if (m) { cssHit = `${name}: ${m[0]}`; break; }
}
ok('R3 css has no ring-family rules', cssHit === null, { hit: cssHit });
for (const loc of ['zh-CN', 'en']) {
  const txt = readFileSync(join(WEB, 'js', 'locales', `${loc}.js`), 'utf8');
  ok(`R4 locale ${loc} has no ctxthresh.title key`, !/['"]ctxthresh\.title['"]/.test(txt));
}
// 全 js 源零活引用（渲染器/拖动模块/弹窗环/挂载点/状态槽任一命中即红）。
// 注：`ctxthresh` 裸词不在钉内（退役记录注释可提批次名）；钉的是活代码形状。
const offenders = [];
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else if (name.endsWith('.js') && /ctxthresh\.js|header-model-info|headerModelInfoEl|ctx-ring|updateHeaderModelInfo|initHeaderModelInfo|updatePopupCtxRing|flow-agent-ctx|bgagent-ctx|ringDragPreviewPct|COMPACT_THRESHOLD/.test(readFileSync(p, 'utf8'))) {
      offenders.push(p.slice(WEB.length + 1));
    }
  }
};
walk(join(WEB, 'js'));
ok('R5 zero live source references across web/js', offenders.length === 0, { offenders });

const wsInboundTypes = [];
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

  // ── A0 + N 组：boot 后环不存在、邻接元素在场 ──
  console.log('── N 组：boot 后环不存在 + 邻接零波及 ──');
  let g = await probe(page);
  ok('A0 ring host mount absent after boot', g.hostMount === false);
  ok('A0b no ring DOM/injected sheet/slider role anywhere', g.ringDom === 0 && g.injectedSheet === false && g.sliderRole === 0);
  ok('N1 #sidebar-toggle present', g.sidebarToggle === true);
  ok('N2 #header .header-left present (cluster untouched)', g.headerLeft === true);
  ok('N3 #session-name present', g.sessionName === true);
  ok('N4 #search-btn present', g.searchBtn === true);
  ok('N5 #memory-btn present', g.memoryBtn === true);
  ok('N6 #bg-indicator mount present', g.bgIndicator === true);

  // ── A 组：用量帧注入 ⇒ 环不复活、管道照常、出站腿不回来 ──
  console.log('── A 组：用量帧注入 ⇒ 显示不复活 ──');
  await inject(page, {
    type: 'usageUpdate', sessionId: SID,
    inputTokens: 21000, contextWindow: 128000, outputTokens: 1200,
    model: 'qa-model', compactThreshold: 0.8,
  });
  await sleep(150);
  g = await probe(page);
  ok('A1 ring still absent after usageUpdate frame', g.hostMount === false && g.ringDom === 0);
  const stored = await page.evaluate(async (sid) => {
    const s = (await import('/js/state.js')).default;
    const mi = s.sessionModelInfo[sid];
    return mi && { model: mi.model, contextWindow: mi.contextWindow, inputTokens: mi.inputTokens };
  }, SID);
  ok('A2 state.sessionModelInfo pipeline still records usage (popup model badges alive)',
    !!stored && stored.model === 'qa-model' && stored.contextWindow === 128000, { stored });
  await inject(page, {
    type: 'done', sessionId: SID,
    model: 'qa-model-2', contextWindow: 128000, inputTokens: 30000, outputTokens: 900,
  });
  await sleep(150);
  g = await probe(page);
  ok('A3 ring still absent after done frame', g.hostMount === false && g.ringDom === 0);
  ok('A4 renderer hook slot stays retired', g.rendererHookGone === true && g.compactConstGone === true && g.pipelineAlive === true);
  ok('A5 no getCompactThreshold/setCompactThreshold outbound on boot (drag module is gone)',
    !wsInboundTypes.includes('getCompactThreshold') && !wsInboundTypes.includes('setCompactThreshold'),
    { types: wsInboundTypes });
  ok('N7 neighbors still present after usage frames',
    g.sidebarToggle && g.headerLeft && g.sessionName && g.searchBtn && g.memoryBtn && g.bgIndicator);

  // ── P 组：console 干净（豁免已知既有 harness 404，模块图零缺件）──
  console.log('── P 组：console 干净 ──');
  const KNOWN_HARNESS_ERRORS = [
    'Failed to load resource: the server responded with a status of 404 (Not Found)',
    'An unknown error occurred when fetching the script.',
  ];
  const unexpected = consoleErrors.filter((t) => !KNOWN_HARNESS_ERRORS.includes(t));
  ok('P1 zero unexpected console errors during boot + injections', unexpected.length === 0, { errors: unexpected });
  ok('P2 no /js/ module 404 (no dangling import resurrects the retired module)', missingJs.length === 0, { missing: missingJs });

  console.log('\nremove-context-usage.spec (anti-resurrection) done');
} finally {
  await browser.close();
}
