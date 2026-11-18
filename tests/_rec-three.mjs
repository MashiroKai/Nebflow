#!/usr/bin/env node
// _rec-three.mjs — 三件修复的「改前 / 改后」录屏（作者令 2026-11-18）。
// 用法: node tests/_rec-three.mjs <label> <outDir> <width> <height>
//   label = before|after；outDir 绝对路径；宽高 = 视口（录像尺寸 = 视口尺寸）
// 演示同一脚本在**同一夹具**下跑两棵源码树（改前 = f5300f375 独立 worktree），
// 因此两段录像的差异只来自代码，不来自夹具。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const [label = 'after', OUT = '/tmp/rec', W = '1280', H = '800'] = process.argv.slice(2);
mkdirSync(OUT, { recursive: true });

const SID = 'rec-three';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const TYPES = { domains: [{ id: 'llm', enabledSwitch: false }, { id: 'imagegen', enabledSwitch: true }], adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview', 'openai-images'] } };
const EP = 'https://open.bigmodel.cn/api/paas/v4/';
const IMG = { enabled: true, compiledIn: true, defaultProvider: 'zhipu', providers: { zhipu: { type: 'cogview', model: 'cogview-4', baseUrl: EP, keyRef: 'imagegen-zhipu-key', keyConfigured: true }, bare: { type: 'openai-images', model: 'gpt-image-1', keyRef: 'imagegen-bare-key', keyConfigured: false } } };
const CFG = JSON.stringify({ features: {}, llm: { providers: { 'openai-main': { baseUrl: 'https://api.openai.com/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'gpt-4o', contextWindow: 128000 }] } } }, imagegen: { enabled: true, providers: {} } });

const width = +W, height = +H;
const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width, height },
  deviceScaleFactor: 1,
  hasTouch: width < 500,
  isMobile: width < 500,
  recordVideo: { dir: OUT, size: { width, height } },
});
const page = await ctx.newPage();
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
});
await page.route('**/*', (route) => {
  const req = route.request();
  let p = decodeURIComponent(new URL(req.url()).pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) {
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    if (p === '/api/config/provider-types') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TYPES) });
    if (p === '/api/config/imagegen') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(IMG) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: req.method() === 'PUT' ? '{"ok":true}' : '{}' });
  }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
  catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const config = () => ws.send(JSON.stringify({ type: 'configData', config: CFG, configured: true, onboarding: 'done', models: [], defaults: {} }));
  const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  config(); sessions();
  ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    else if (m.type === 'getConfig') config();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
    else if (m.type === 'updateConfig') ws.send(JSON.stringify({ type: 'configUpdated', requestId: m.requestId, success: true }));
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 })); });
});
await page.goto('http://127.0.0.1:8394/');
await page.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws?.readyState === 1; }, SID, { timeout: 20000 });

// 画一个「焦点框 + 标签」的覆盖层，把当前动作指到屏幕上（视觉对照用）。
const mark = async (text) => page.evaluate((tx) => {
  let el = document.getElementById('rec-mark');
  if (!el) { el = document.createElement('div'); el.id = 'rec-mark';
    el.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:2px dashed #ff3b30;border-radius:6px;box-sizing:border-box';
    document.body.appendChild(el); }
  let lab = document.getElementById('rec-lab');
  if (!lab) { lab = document.createElement('div'); lab.id = 'rec-lab';
    lab.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;background:#ff3b30;color:#fff;font:600 12px -apple-system,sans-serif;padding:3px 7px;border-radius:4px;max-width:92vw';
    document.body.appendChild(lab); }
  const t = document.querySelector(tx.sel);
  if (!t) { el.style.display = 'none'; lab.style.display = 'none'; return null; }
  const r = t.getBoundingClientRect();
  el.style.display = 'block'; lab.style.display = 'block';
  el.style.left = r.left + 'px'; el.style.top = r.top + 'px'; el.style.width = r.width + 'px'; el.style.height = r.height + 'px';
  lab.textContent = tx.text;
  lab.style.left = Math.max(4, Math.min(r.left, window.innerWidth - 260)) + 'px';
  lab.style.top = Math.max(4, r.top - 26) + 'px';
  return { w: Math.round(r.width), h: Math.round(r.height) };
}, { sel: text.sel, text: text.text });
const unmark = () => page.evaluate(() => { document.getElementById('rec-mark')?.remove(); document.getElementById('rec-lab')?.remove(); });
/** 画一个显式矩形（用于标出「整行」的范围——触发钮只占其中一段）。 */
const markBox = (box, text) => page.evaluate((b) => {
  let el = document.getElementById('rec-row');
  if (!el) { el = document.createElement('div'); el.id = 'rec-row';
    el.style.cssText = 'position:fixed;z-index:2147483646;pointer-events:none;background:rgba(255,59,48,.14);border:2px solid #ff3b30;border-radius:6px;box-sizing:border-box';
    document.body.appendChild(el); }
  let lab = document.getElementById('rec-row-lab');
  if (!lab) { lab = document.createElement('div'); lab.id = 'rec-row-lab';
    lab.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;background:#ff3b30;color:#fff;font:600 12px -apple-system,sans-serif;padding:3px 7px;border-radius:4px;max-width:92vw';
    document.body.appendChild(lab); }
  el.style.left = b.left + 'px'; el.style.top = b.top + 'px'; el.style.width = b.width + 'px'; el.style.height = b.height + 'px';
  lab.textContent = b.text;
  lab.style.left = Math.max(4, Math.min(b.left, innerWidth - 280)) + 'px';
  lab.style.top = Math.max(4, b.top - 26) + 'px';
}, { ...box, text });
const unmarkBox = () => page.evaluate(() => { document.getElementById('rec-row')?.remove(); document.getElementById('rec-row-lab')?.remove(); });

const typeSel = () => page.evaluate(() => document.querySelector('#cfg-modal [data-field="type"]') ? 'type' : 'protocol');

await sleep(1200);
await page.evaluate(async () => { (await import('/js/sidebar.js')).openSettingsPanel?.(); });
await sleep(700);
await page.evaluate(() => document.querySelector('[data-settings-disclosure="providers"]')?.click());
await sleep(900);
await mark({ sel: '#provider-panel-host', text: `② 端点如实显示：已配置 = URL / 未配置 = 默认端点` });
await sleep(1600); await unmark();
await page.evaluate(() => document.querySelector('[data-provider-type="imagegen"]')?.click());
await sleep(800);
await mark({ sel: '#provider-list', text: '② 生图配置：卡面端点读数' });
await sleep(1500); await unmark();
await page.screenshot({ path: join(OUT, `${label}-${W}x${H}-2-endpoint.png`) });

// ① 协议选择器：点整行右侧
await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
await sleep(900);
const f = await typeSel();
const sel = `#cfg-modal [data-field="${f}"]`;
const geo = await page.evaluate((s) => {
  const el = document.querySelector(s); const w = el.closest('.nf-select') || el; const r = w.getBoundingClientRect();
  const sib = document.querySelector('#cfg-modal [data-field="baseUrl"]')?.getBoundingClientRect();
  return { w: Math.round(r.width), sibW: sib ? Math.round(sib.width) : null, left: r.left, top: r.top, h: r.height };
}, sel);
// 标出**整行**（兄弟字段宽度）——触发钮只占其中一段；改前那段右半是死区。
await markBox({ left: geo.left, top: geo.top, width: geo.sibW || geo.w, height: geo.h }, `① 字段行宽 ${geo.sibW || geo.w}px ／ 触发钮宽 ${geo.w}px`);
await sleep(1800); await unmarkBox();
await page.screenshot({ path: join(OUT, `${label}-${W}x${H}-1-protocol-before-click.png`) });
// 真实 pointer 落点 = **整行右侧 80%**（改前 = 触发钮之外 = 死区；改后 = 行内命中区）
const clickX = geo.left + (geo.sibW || geo.w) * 0.8;
const clickY = geo.top + geo.h / 2;
await page.mouse.move(clickX, clickY, { steps: 14 });
await sleep(800);
await page.mouse.click(clickX, clickY);
await sleep(700);
const menuOpened = await page.evaluate(() => !!document.querySelector('.nf-select-menu'));
await page.screenshot({ path: join(OUT, `${label}-${W}x${H}-3-after-row-click.png`) });
await mark({ sel: '#cfg-modal .nf-select', text: menuOpened ? `① 点整行右侧(${Math.round((geo.sibW || geo.w) * 0.8)}px)即开选项单 ✅` : `① 点整行右侧(${Math.round((geo.sibW || geo.w) * 0.8)}px)无反应 ❌（触发钮只有 ${geo.w}px，右半是死区）` });
await sleep(1900); await unmark();
if (menuOpened) { await page.evaluate(() => document.querySelector('.nf-select-menu .nf-select-opt:nth-child(2)')?.click()); await sleep(600); }
await page.evaluate(() => document.querySelector('#cfg-modal .cfg-modal-cancel')?.click()).catch(() => {});
await sleep(500);

// ③ 按钮：设置页动作族 + 编辑窗动作族
await mark({ sel: '#settings-content .set-actions', text: '③ 设置页动作按钮一档' });
await sleep(1400); await unmark();
await page.evaluate(() => document.querySelector('[data-provider-type="llm"]')?.click());
await sleep(700);
await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
await sleep(900);
const btns = await page.evaluate(() => {
  const one = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return Math.round(r.height) + 'px/' + cs.paddingLeft + '/' + cs.fontSize + '/' + cs.borderRadius; };
  const s = [...document.querySelectorAll('#settings-content .set-btn')].filter((b) => b.getBoundingClientRect().width > 0).map(one);
  const m = [...document.querySelectorAll('#cfg-modal .cfg-modal-actions button')].map(one);
  return { settings: [...new Set(s)], modal: [...new Set(m)] };
});
await mark({ sel: '#cfg-modal .cfg-modal-actions', text: '③ 编辑窗动作按钮一档' });
await sleep(1600); await unmark();
await page.screenshot({ path: join(OUT, `${label}-${W}x${H}-4-buttons.png`) });
console.log(JSON.stringify({ label, viewport: `${width}x${height}`, protocolRowWidth: geo, menuOpenedFromRowRight: menuOpened, buttons: btns }));
await sleep(600);
await ctx.close();          // 关 ctx 才会把 webm 落盘
await browser.close();
