// design-system-shots.mjs — 设计令牌体系批次（delegate-nebflow-ec513a08）取证 harness。
//
// 两腿（MODE 环境变量）：
//   MODE=settings（默认）—— 设置页读数 + 截图：改前/改后各跑一次，靠
//     SHOTS_DIR 区分落盘目录；同时做**功能断言**（O6：开关点击的 WS 报文、
//     语言切换、下钻子页开合、JeV 保存 PUT 报文与 inline 状态、思考档位 WS）。
//   MODE=kit —— 设计体系 kit 文档页（docs/…/kit.html）五组件 DOM 读数 + 截图。
//
// 读数输出：每行 `PASS|FAIL  <name>  — <json 读数>`，末尾 `___READINGS___` 后
// 逐行 JSON（供 closeout 直接抄读数，不靠人眼）。
//
// 用法：
//   MODE=settings SHOTS_DIR=evidence/…/before node tests/design-system-shots.mjs
//   MODE=kit      SHOTS_DIR=evidence/…/kit    node tests/design-system-shots.mjs
//
// 纪律：静态服务只读 web/ 源树（与 tests/config-drill-shots.mjs 同款），
// `/api/**` 全 mock，WS 走 routeWebSocket 的假后端；不连真实例。

import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const DOCS = join(HERE, '..', 'docs', 'design-language', 'design-system-token-batch');
const MODE = process.env.MODE || 'settings';
const SHOTS_DIR = process.env.SHOTS_DIR || join(HERE, '..', 'evidence', '20261102_design-system', MODE);
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};

const SID = 's-ds';
const NOW = Date.now();
const HISTORY = [
  { type: 'user', text: 'hello omega seed', timestamp: NOW - 3600000 },
  { type: 'ai', text: 'omega reply text', timestamp: NOW - 3500000 },
];

let failures = 0;
/** @type {Array<Record<string, unknown>>} */
const readings = [];
const ok = (name, cond, extra) => {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${extra !== undefined ? '  — ' + JSON.stringify(extra) : ''}`;
  console.log(line);
  readings.push({ name, pass: !!cond, reading: extra ?? null });
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** 客户端 → 服务端 WS 报文捕获（O6 功能断言的证据面）。 */
/** @type {Array<any>} */
const wsOut = [];

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    // kit 腿：文档页从 docs/ 取（只读），其余静态资源仍走 web/ 源树。
    // kit.html 用相对路径 `../../../src/main/resources/web/css/…` 引用产品样式
    // （双击即离线可看）；服务器把 URL 里 `src/main/resources/web/` 之后的
    // 部分映射回 WEB 树，同一份 HTML 两处都成立。
    if (p === '/kit.html') {
      const body = await readFile(join(DOCS, 'kit.html'));
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(body);
      return;
    }
    const WEB_MARK = '/src/main/resources/web/';
    const mapped = p.includes(WEB_MARK) ? '/' + p.slice(p.indexOf(WEB_MARK) + WEB_MARK.length) : p;
    const file = normalize(join(WEB, mapped === '/' ? 'index.html' : mapped));
    if (!file.startsWith(WEB) || !existsSync(file)) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{}');
      return;
    }
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const CONFIG = {
  llm: {
    providers: {
      kimi: { baseUrl: 'https://api.kimi.example/v1/', apiKey: '***', protocol: 'anthropic', models: [{ id: 'kimi-k2', contextWindow: 8192 }] },
    },
  },
  jev: { enabled: true, provider: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', keyRef: 'typesafe-api-key' },
  stt: { enabled: false },
};

function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
    config: JSON.stringify(CONFIG),
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    wsOut.push(m);
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({
        type: 'historyPage', sessionId: m.sessionId, messages: HISTORY,
        hasMore: false, offset: HISTORY.length, total: HISTORY.length,
      }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

async function boot(colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  await page.route('**/api/**', (route) => {
    const u = new URL(route.request().url());
    const p = u.pathname;
    if (p.startsWith('/api/secrets/')) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ value: 'sk-jev-secret' }) });
    }
    if (p === '/api/config/jev' && route.request().method() === 'PUT') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ok: true }) });
    }
    if (p === '/api/config/imagegen') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ compiledIn: true, enabled: false, providers: {} }) });
    }
    if (p === '/api/sessions') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    return route.fulfill({ contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  return { ctx, page };
}

/* ══ settings 腿 ══════════════════════════════════════════════════════ */

/** 设置页实测读数：行高、热区宽度、开关形态清点、字体/圆角消费面。 */
const settingsProbe = () => {
  const content = document.getElementById('settings-content');
  if (!content) return null;
  const row = content.querySelector('.settings-row');
  // provider-add-tabs 批换代：下钻入口行退役——热区读数改走统一面板类型标签。
  const drillBtn = content.querySelector('.nf-ptab');
  const drillRow = drillBtn ? drillBtn.closest('.nf-ptabs') : null;
  const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { w: Math.round(r.width), h: Math.round(r.height) }; };
  const switches = [...content.querySelectorAll('[role="switch"], .nb-toggle, .toggle, .cfg-toggle, .pp-toggle')];
  const legacy = switches.filter(el => !el.classList.contains('nb-toggle')).map(el => el.className);
  // token 解析：把消费点读回计算值，证明 var() 链真生效（不是字面量巧合）。
  const cs = content.querySelector('.settings-row');
  const probe = document.createElement('span');
  probe.style.color = 'var(--ds-border-hairline, rgb(1 2 3))';
  document.body.appendChild(probe);
  const dsResolved = getComputedStyle(probe).color;
  probe.remove();
  return {
    rowH: row ? Math.round(row.getBoundingClientRect().height) : null,
    rowPad: cs ? getComputedStyle(cs).padding : null,
    drillRow: rect(drillRow),
    drillBtn: rect(drillBtn),
    drillRowW: drillRow ? Math.round(drillRow.getBoundingClientRect().width) : null,
    switchCount: switches.length,
    legacySwitches: legacy,
    sectionCount: content.querySelectorAll('.settings-section').length,
    dsBorderResolved: dsResolved,
  };
};

async function settingsLeg(colorScheme) {
  wsOut.length = 0;
  const { ctx, page } = await boot(colorScheme);
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await sleep(600);

  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
  await sleep(500);
  await page.screenshot({ path: join(SHOTS_DIR, `settings-top-${colorScheme}.png`) });

  // ① 视觉读数：行高 / 热区 / 开关形态。
  const probe = await page.evaluate(settingsProbe);
  ok(`settings row 实测（${colorScheme}）`, !!probe && probe.rowH > 0, probe);
  ok(`设置页开关全部 nb-toggle（${colorScheme}）`, !!probe && probe.legacySwitches.length === 0, probe && { count: probe.switchCount, legacy: probe.legacySwitches });
  ok(`类型标签分段控件热区（${colorScheme}）`, !!probe && probe.drillBtn && probe.drillRowW > 0,
    probe && { rowH: probe.drillRow && probe.drillRow.h, btnW: probe.drillBtn && probe.drillBtn.w, rowW: probe.drillRowW });

  // ② O6 功能断言（真实控件，不只看视觉）。
  //    a) LLM 日志开关 → aria-checked 翻转 + WS setLlmLog 报文。
  const before = await page.getAttribute('#toggle-llm-log', 'aria-checked');
  await page.click('#toggle-llm-log');
  await sleep(300);
  const after = await page.getAttribute('#toggle-llm-log', 'aria-checked');
  const llmMsg = wsOut.filter(m => m.type === 'setLlmLog').pop();
  ok(`功能·LLM 日志开关（${colorScheme}）`, before !== after && !!llmMsg && llmMsg.enabled === (after === 'true'),
    { before, after, ws: llmMsg ?? null });

  //    b) 思考档位下拉 → WS setThinking。
  await page.selectOption('#cfg-thinking-effort', 'high');
  await sleep(300);
  const thinkMsg = wsOut.filter(m => m.type === 'setThinking').pop();
  ok(`功能·思考档位（${colorScheme}）`, !!thinkMsg && !!thinkMsg.thinking && thinkMsg.thinking.budgetTokens > 0, thinkMsg ?? null);

  //    c) provider-add-tabs 批换代：类型标签切换 = 面板重绘（列表 data-type 跟随）。
  await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="jev"]')?.click());
  await page.waitForSelector('.nf-provider-panel[data-type="jev"]', null, { timeout: 5000 });
  await sleep(250);
  await page.screenshot({ path: join(SHOTS_DIR, `provider-panel-jev-${colorScheme}.png`) });
  const tabState = await page.evaluate(() => ({
    type: document.querySelector('.nf-provider-panel')?.dataset.type,
    cards: document.querySelectorAll('#provider-list .cfg-card').length,
    overlay: !!document.querySelector('.config-drill-overlay'),
  }));
  ok(`功能·类型标签切换（${colorScheme}）`, tabState.type === 'jev' && tabState.overlay === false, tabState);

  //    d) JeV 保存：统一表单保存腿 → PUT /api/config/jev 报文。
  let putBody = null;
  page.on('request', (r) => {
    if (r.url().includes('/api/config/jev') && r.method() === 'PUT') putBody = r.postData();
  });
  await page.evaluate(() => document.querySelector('#provider-list .cfg-card .cfg-card-title')?.click());
  await page.waitForSelector('#cfg-modal .cfg-modal-body', null, { timeout: 5000 });
  await page.click('#cfg-modal-save');
  await sleep(600);
  ok(`功能·JeV 统一表单保存（${colorScheme}）`, !!putBody, { put: putBody ? JSON.parse(putBody) : null });
  await page.evaluate(() => document.getElementById('cfg-modal')?.remove());
  await sleep(200);

  //    e) 语言切换 → select 值落盘 + 重渲（locale 生效判据）。
  await page.selectOption('#cfg-language', 'en');
  await sleep(500);
  const locale = await page.evaluate(() => {
    // locale 落盘键 = `<lowerName>_locale`（branding.key('locale')），前缀随品牌变 ——
    // 这里扫全 localStorage 取唯一以 _locale 结尾的键，不写死前缀。
    const key = Object.keys(localStorage).find(k => k.endsWith('_locale')) || '';
    return {
      key,
      stored: key ? localStorage.getItem(key) : null,
      firstTitle: document.querySelector('#settings-content .settings-section-title')?.textContent?.trim(),
    };
  });
  ok(`功能·语言切换（${colorScheme}）`, locale.stored === 'en' && locale.firstTitle === 'Account', locale);
  await page.selectOption('#cfg-language', 'zh-CN');
  await sleep(400);

  //    f) 折叠/展开（STT 头行 aria-expanded 翻转 + body hidden 切换）。
  const sttBefore = await page.evaluate(() => ({
    exp: document.getElementById('stt-advance-toggle')?.getAttribute('aria-expanded'),
    hidden: document.getElementById('stt-advance-body')?.hidden,
  }));
  await page.click('#stt-advance-toggle');
  await sleep(300);
  const sttAfter = await page.evaluate(() => ({
    exp: document.getElementById('stt-advance-toggle')?.getAttribute('aria-expanded'),
    hidden: document.getElementById('stt-advance-body')?.hidden,
  }));
  ok(`功能·STT 折叠开合（${colorScheme}）`, sttBefore.hidden !== sttAfter.hidden && sttBefore.exp !== sttAfter.exp,
    { before: sttBefore, after: sttAfter });

  // ③ 长页全貌（滚动到 Providers / 回退链节）。
  await page.evaluate(() => {
    const el = document.getElementById('model-chain-section');
    if (el) el.scrollIntoView({ block: 'center' });
  });
  await sleep(500);
  await page.screenshot({ path: join(SHOTS_DIR, `settings-chain-${colorScheme}.png`) });
  await ctx.close();
}

/* ══ kit 腿 ══════════════════════════════════════════════════════════ */

async function kitLeg(colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 1100 }, colorScheme, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  await page.goto(BASE + '/kit.html');
  await page.waitForSelector('.ds-kit');
  await sleep(300);
  await page.screenshot({ path: join(SHOTS_DIR, `kit-${colorScheme}.png`), fullPage: true });
  const kit = await page.evaluate(() => {
    const q = (sel) => [...document.querySelectorAll(sel)];
    const geo = (sel) => q(sel).map(el => {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      return { w: Math.round(r.width), h: Math.round(r.height), radius: cs.borderRadius, fs: cs.fontSize, color: cs.color };
    });
    return {
      btn: geo('.ds-btn'),
      row: geo('.ds-row'),
      badge: geo('.ds-badge'),
      input: geo('.ds-input'),
      card: geo('.ds-card'),
      switchH: q('.ds-switch-track').map(el => Math.round(el.getBoundingClientRect().height)),
    };
  });
  ok('kit·五组件齐备（按钮/行/徽标/输入/卡）',
    kit.btn.length >= 3 && kit.row.length >= 2 && kit.badge.length >= 3 && kit.input.length >= 2 && kit.card.length >= 1, kit);
  ok('kit·行高热区 ≥44px（触控档）', kit.row.every(r => r.h >= 44), kit.row.map(r => r.h));
  ok('kit·字号全落 11/12/13/15 阶梯', kit.btn.concat(kit.row, kit.badge, kit.input)
    .every(x => ['11px', '12px', '13px', '15px'].includes(x.fs)),
    kit.btn.concat(kit.row, kit.badge, kit.input).map(x => x.fs));
  ok('kit·圆角全落 4/6/8/10/14/16/20/999 阶梯（0px = 未声明圆角，合法）', kit.btn.concat(kit.row, kit.badge, kit.input, kit.card)
    .every(x => ['0px', '4px', '6px', '8px', '10px', '14px', '16px', '20px', '999px'].includes(x.radius.split(' ')[0])),
    kit.btn.concat(kit.row, kit.badge, kit.input, kit.card).map(x => x.radius));
  await ctx.close();
}

if (MODE === 'kit') {
  for (const scheme of ['light', 'dark']) await kitLeg(scheme);
} else {
  for (const scheme of ['light', 'dark']) await settingsLeg(scheme);
}

const ALLOWLIST = /favicon|net::|WebSocket|404/;
const fresh = consoleErrors.filter(e => !ALLOWLIST.test(e));
ok('console 零新增错误', fresh.length === 0, fresh.slice(0, 5).join(' | '));

await browser.close();
server.close();
console.log('\n___READINGS___');
for (const r of readings) console.log(JSON.stringify(r));
console.log(`\nshots → ${SHOTS_DIR}`);
process.exit(failures ? 1 : 0);
