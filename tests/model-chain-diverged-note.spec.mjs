// model-chain-diverged-note.spec.mjs — 设置页「模型（按会话类别）」节分叉披露注记验收
// （model-route 分叉不可见批）。现网背景：作者把主链切成 cmdcode 后，delegate 会话
// 仍跑 subagent 槽钉住的 IricalGpt/gpt-6.1-terra（槽显式链压过主链——引擎语义正
// 确），但设置页没有任何一行点破分叉。本批在节首亮出与 /model 面板同款
// （model.divergedBar）的纯文本注记。
//
// 判据：
//   ① subagent 槽显式 ⇒ #model-chain-diverged-note 在场，文案含类别标签与
//     「已独立配置，不受主链影响」；
//   ② 注记纯文本（零 <button>）、不带 data-role（不扰 model-chain-slim 的五类
//     行序断言）；主agent行不在注记里（分叉只报 follower）；
//   ③ subagent 槽行自身显示其显式生效 ref（「实际生效的那一个」）；
//   ④ 清除 subagent 独立配置（回跟随）⇒ 注记离场。
//
// 运行：node tests/model-chain-diverged-note.spec.mjs   （可选 PORT=8341）

import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(process.env.WEB_DIR || resolve(ROOT, 'src/main/resources/web'));
const PORT = Number(process.env.PORT || 8341);
const BASE = `http://127.0.0.1:${PORT}`;
const SID = 'mdn-root-session';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

/* 夹具：主链 cmdcode，subagent 槽显式钉 IricalGpt/gpt-6.1-terra（现网形态）。 */
const PROVIDERS = {
  cmdcode: { baseUrl: 'https://api.cmd.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'deepseek/deepseek-v4-flash' }] },
  IricalGpt: { baseUrl: 'https://sub.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'gpt-6.1-terra' }, { id: 'gpt-6-luna' }] },
};
const MAIN = { preferred: 'cmdcode/deepseek/deepseek-v4-flash', fallbacks: [] };
const SUBAGENT = { preferred: 'IricalGpt/gpt-6.1-terra', fallbacks: [] };
const sim = { chains: { Nebula: MAIN, subagent: SUBAGENT }, puts: [] };

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml',
};
const webServer = createServer((req, res) => {
  const p0 = decodeURIComponent(new URL(req.url, BASE).pathname);
  const p = p0 === '/' ? '/index.html' : p0;
  if (p.startsWith('/api/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
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
page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 200)}`); });
await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
await page.route('**/api/**', (r) => {
  const p = decodeURIComponent(new URL(r.request().url()).pathname);
  if (p === '/api/sessions') {
    return r.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
  }
  if (p.startsWith(`/api/sessions/${SID}/history`)) {
    return r.fulfill({ json: { messages: [], total: 0, sessionId: SID } });
  }
  if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
    const role = decodeURIComponent(p.split('/')[3]);
    if (r.request().method() === 'PUT') {
      const body = r.request().postDataJSON();
      sim.puts.push({ role, ...body });
      sim.chains[role] = body && body.model ? body.model : null;
    }
    const own = sim.chains[role];
    const effective = own || sim.chains.Nebula;
    return r.fulfill({
      json: {
        mode: own ? 'explicit' : 'follow', chain: own, effectiveChain: effective,
        resolvedFrom: own ? 'own-chain' : 'nebula-chain', current: effective.preferred, settable: true,
      },
    });
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
    if (m.type === 'getConfig') sendConfig();
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: SID, messages: [], hasMore: false, offset: 0, total: 0 }));
  });
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
await sleep(600);
await page.click('#settings-btn');
const disclosure = page.locator('#model-chain-section [data-settings-disclosure="models"]');
if (await disclosure.getAttribute('aria-expanded') !== 'true') await disclosure.click();
await page.waitForSelector('#model-chain-section-body [data-role="Nebula"] .mp-settings-role-model', { state: 'visible', timeout: 15000 });
await sleep(350);

/* ① 注记在场 + 文案 */
const note = page.locator('#model-chain-diverged-note');
ok('① 分叉注记在场（subagent 显式 ⇒ 设置页节点破分叉）', await note.count() === 1);
const noteText = (await note.textContent() || '').trim();
ok('① 注记文案含类别标签与「不受主链影响」',
  noteText.includes('subagent') && noteText.includes('已独立配置') && noteText.includes('不受主链影响'), noteText);
ok('① 注记不把主agent行算作分叉（只报 follower 槽）', !noteText.includes('主agent') && !noteText.includes('Main agent'), noteText);

/* ② 结构纪律：纯文本、无 data-role */
ok('② 注记零 <button>（设置页节控件面不扩）', await note.locator('button').count() === 0);
ok('② 注记无 data-role（不扰五类行序断言）', await note.evaluate((el) => el.getAttribute('data-role')) === null);

/* ③ 分叉槽行自身显示显式生效 ref（「实际生效的那一个」） */
const subSelect = page.locator('#model-role-select-subagent');
ok('③ subagent 行选择器回显其显式生效 ref', (await subSelect.inputValue()) === 'IricalGpt/gpt-6.1-terra', await subSelect.inputValue());

/* ④ 清除独立配置 ⇒ 注记离场（PUT null 走既有通道） */
await subSelect.selectOption('');
await page.waitForFunction(() => !document.querySelector('#model-chain-diverged-note'), null, { timeout: 10000 });
ok('④ 清除 subagent 独立配置后注记离场', await note.count() === 0);
ok('④ 清除走既有 PUT 通道（model:null）', sim.puts.some((p) => p.role === 'subagent' && p.model === null));

const NOISE = /favicon|net::ERR|WebSocket|404|ResizeObserver loop/i;
ok('零 console error / 零 pageerror', errors.filter((e) => !NOISE.test(e)).length === 0, errors.slice(0, 3).join(' | '));

await ctx.close();
await browser.close();
webServer.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures ? 1 : 0);
