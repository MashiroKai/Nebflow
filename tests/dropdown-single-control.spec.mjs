#!/usr/bin/env node
// dropdown-single-control.spec.mjs — 「同一字段只渲染一个下拉控件」真渲染检查
// （2026-11-18 作者报障「电脑端的下拉选框，怎么有重复」修复批，作者令：统一
//   必须形成自动化检测 ⇒ 本文件断言受影响字段的可见控件数，不是看源码）。
//
// 判据（每个受影响字段各一行）：可见下拉控件数 === 1。
//   控件 = ① 统一 trigger（.nf-select-trigger，渲染中）+ ② 被替换的原生
//   <select>（若仍渲染 = 旧控件复活）+ ③ 同字段容器里的其余 <select>（半迁移残留）。
//
// 两种资产状态各测一遍（都要求 1/1）：
//   · as-shipped：源树本来的样子；
//   · hiding-ineffective：往页面注入「覆盖 css/dropdown.css 隐藏声明」的非
//     important 规则（模拟 CSS 资产陈旧/半更新 —— 作者现场最可能的成因）。
//     修复前此状态下每个字段都变 2（统一 trigger + 复活的原生 select）；
//     修复后旧控件的不可见由收编器 inline 拥有 ⇒ 状态无关，恒为 1。
//
// 视口：桌面 1280×800 与手机 375×812（作者令两视口都要）。
// 运行：node tests/dropdown-single-control.spec.mjs
//   可选 env：WEB_DIR=<web 根>（改前树对照用）· VIDEO=1（录 webm）· LOG=<日志路径>
// 产出：<LOG>（默认 /private/tmp/dropdown-single-control/log.txt）+ VIDEO=1 时
//       <视频目录>/<state>/<viewport>.webm；任一 FAIL ⇒ exit 1。

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(process.env.WEB_DIR || join(ROOT, 'src/main/resources/web'));
const PORT = Number(process.env.PORT || 8397);
const BASE = `http://127.0.0.1:${PORT}`;
const LOG = process.env.LOG || '/private/tmp/dropdown-single-control/log.txt';
const VIDEO = process.env.VIDEO === '1';
const VIDEO_DIR = process.env.VIDEO_DIR || '/private/tmp/dropdown-single-control/video';
const SID = 'dropdown-single-root';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(dirname(LOG), { recursive: true });
writeFileSync(LOG, '');
if (VIDEO) mkdirSync(VIDEO_DIR, { recursive: true });

const FIELDS = [
  'cfg-language', 'cfg-thinking-effort',
  'model-role-select-Nebula', 'model-role-select-kernel', 'model-role-select-project-dispatcher',
  'model-role-select-node', 'model-role-select-subagent',
  'small-llm-select', 'imagegen-settings-select',
];
const PROVIDERS = {
  zhipu: { baseUrl: 'https://api.zhipu.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'glm-5.3-flash' }] },
  cmdcode: { baseUrl: 'https://api.cmd.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'deepseek/deepseek-v4.1-flash' }] },
};
// 非 important 的替换声明：只在「该隐藏声明不是生效声明」时起作用（模拟 CSS 资产
// 陈旧/半更新），对被收编器 inline 拥有的不可见无效 —— 这正是修复的分界。
const HIDING_RULE = '.nf-select .nf-select-native { opacity: 1; pointer-events: auto; }';

let pass = 0;
let fail = 0;
const out = [];
const log = (line) => { console.log(line); out.push(line); };

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const webServer = createServer((req, res) => {
  const p0 = decodeURIComponent(new URL(req.url, BASE).pathname);
  const p = p0 === '/' ? '/index.html' : p0;
  if (p.startsWith('/api/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

const chain = { preferred: 'zhipu/glm-5.3-flash', fallbacks: [] };

/** 页内读数：每个受影响字段的可见下拉控件数拆解。 */
const MEASURE = (fields) => fields.map((id) => {
  const sel = document.getElementById(id);
  if (!sel) return { id, present: false, count: 0 };
  const rendered = (el) => {
    if (!el) return false;
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0.01 && r.width > 0 && r.height > 0;
  };
  const wrap = sel.closest('.nf-select');
  const trigger = wrap ? wrap.querySelector('.nf-select-trigger') : null;
  const container = (wrap || sel).parentElement;
  const others = container ? [...container.querySelectorAll('select')].filter((s) => s !== sel) : [];
  return {
    id, present: true,
    triggerRendered: rendered(trigger),
    nativeRendered: rendered(sel),
    othersRendered: others.filter(rendered).length,
    count: (rendered(trigger) ? 1 : 0) + (rendered(sel) ? 1 : 0) + others.filter(rendered).length,
  };
});

async function boot(browser, viewport, recordPath) {
  const ctx = await browser.newContext({ viewport, colorScheme: 'light', ...(recordPath ? { recordVideo: { dir: recordPath, size: viewport } } : {}) });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 200)}`));
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/**', (r) => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    if (p === '/api/sessions') return r.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      const role = decodeURIComponent(p.split('/')[3]);
      const own = role === 'Nebula' ? chain : null;
      const effective = own || chain;
      return r.fulfill({ json: { mode: own ? 'explicit' : 'follow', chain: own, effectiveChain: effective, resolvedFrom: 'seed', current: effective.preferred, settable: true } });
    }
    return r.fulfill({ json: {} });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ llm: { providers: PROVIDERS } }),
    }));
    sendConfig();
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: SID, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await sleep(700);
  return { ctx, page, errors };
}

async function openSettings(page, mobile) {
  if (mobile) {
    await page.evaluate(async () => (await import('/js/mobile/navController.js')).pushPage('settings'));
    await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings"]')?.classList.contains('live'), null, { timeout: 15000 });
  } else {
    await page.click('#settings-btn');
  }
  await sleep(400);
  const disclosure = page.locator('#model-chain-section [data-settings-disclosure="models"]');
  if (await disclosure.count() && (await disclosure.getAttribute('aria-expanded')) !== 'true') await disclosure.click();
  await page.waitForSelector('#model-chain-section-body [data-role="Nebula"]', { state: 'attached', timeout: 15000 });
  await page.evaluate(() => document.getElementById('model-chain-section')?.scrollIntoView({ block: 'start' }));
  await sleep(600);
}

/** 一条腿 = 一个视口：as-shipped 与 hiding-ineffective 两种状态各测一次。 */
async function leg(browser, viewport, mobile, label) {
  const recordPath = VIDEO ? join(VIDEO_DIR, label) : null;
  const { ctx, page, errors } = await boot(browser, viewport, recordPath);
  try {
    await openSettings(page, mobile);
    const asShipped = await page.evaluate(MEASURE, FIELDS);
    const present = asShipped.filter((r) => r.present);
    const okCount = present.filter((r) => r.count === 1).length;
    const badShipped = present.filter((r) => r.count !== 1);
    log(`${badShipped.length ? 'FAIL' : 'PASS'}  [${label}/as-shipped] 受影响字段可见下拉控件数 = ${okCount}/${present.length}`
      + (badShipped.length ? ` — 多控件的字段：${badShipped.map((r) => `${r.id}(count=${r.count}, trigger=${r.triggerRendered}, native=${r.nativeRendered}, extra=${r.othersRendered})`).join('; ')}` : ''));
    if (badShipped.length) fail++; else pass++;

    await page.addStyleTag({ content: HIDING_RULE });
    await sleep(300);
    const degraded = await page.evaluate(MEASURE, FIELDS);
    const present2 = degraded.filter((r) => r.present);
    const ok2 = present2.filter((r) => r.count === 1).length;
    const badDeg = present2.filter((r) => r.count !== 1);
    log(`${badDeg.length ? 'FAIL' : 'PASS'}  [${label}/hiding-ineffective] 受影响字段可见下拉控件数 = ${ok2}/${present2.length}`
      + (badDeg.length ? ` — 多控件的字段：${badDeg.map((r) => `${r.id}(count=${r.count}, trigger=${r.triggerRendered}, native=${r.nativeRendered}, extra=${r.othersRendered})`).join('; ')}` : ''));
    if (badDeg.length) fail++; else pass++;

    if (badDeg.length) await page.screenshot({ path: join(dirname(LOG), `${label}-duplicate.png`) }).catch(() => {});
    log(`INFO  [${label}] 字段清单：${present.map((r) => r.id).join(', ') || '(无)'}`);
    log(`INFO  [${label}] 页面 JS 异常 = ${errors.length}${errors.length ? ' — ' + errors.slice(0, 3).join(' | ') : ''}`);
  } finally {
    await ctx.close();
  }
}

const browser = await chromium.launch();
try {
  await leg(browser, { width: 1280, height: 800 }, false, 'desktop-1280x800');
  await leg(browser, { width: 375, height: 812 }, true, 'mobile-375x812');
} finally {
  await browser.close();
  webServer.close();
}
log(`SUMMARY pass=${pass} fail=${fail} · web=${WEB_DIR} · log=${LOG}`);
writeFileSync(LOG, out.join('\n') + '\n');
process.exit(fail === 0 ? 0 : 1);
