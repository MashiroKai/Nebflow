#!/usr/bin/env node
// ask-dock-slash-model.spec.mjs — Ask dock 斜杠命令先审（ask 面三修③ 2026-11-11）。
//
// 缺陷：askDock submit() 把一切文本当 askQuestion 发送，dock 又 260ms 自动抢焦点
// ⇒ 作者在 ask 面键入 `/model` 被发成提问，面板永不开 = 「/model 无反应」。
// 修复：submit() 顶部过主会话同一单源 handleSlash（input/slashCommands.js），
// 白名单命中 ⇒ 本地开面板、清输入、零 WS；未命中 ⇒ '/' 仍是纯文本（封存闸不扩）。
//
// 三条真键盘腿（判定全部来自真实渲染与 WS 事件录证，无发送 Mock）：
//   S1 `/model` + Enter ⇒ #model-panel-overlay 可见 + dock 输入清空 +
//      askQuestion 帧数 = 0 + 用户气泡数不变（面板响应，零误发）。
//   S2 `/clear`（已退役、不在白名单）+ Enter ⇒ askQuestion 原样发出
//      （content = '/clear'）+ 用户气泡出现 ⇒ 封存面语义零放大。
//   S3 普通文本 + Enter ⇒ askQuestion 原样发出（先审不改写正常发问）。
//
// 运行：node tests/ask-dock-slash-model.spec.mjs
// 只读静态资源 + routeWebSocket mock：不连、不启、不 kill 任何引擎进程（零 6138 接触）。

import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8323);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const MD_PATH = '/tmp/ask-fixture/ask-dock-slash.md';
const SID = 'ask-' + createHash('sha256').update(MD_PATH).digest('hex').slice(0, 8);
const docText = () => Array.from({ length: 20 }, (_, i) => `第 ${i + 1} 段样本正文。`).join('\n\n');
/** askQuestion 事件录证（Node 侧）——每腿独立 context，零串扰。 */
const askQuestions = [];

async function boot() {
  const replay = [
    { role: 'user', text: '探针预置问：这份文件讲什么？' },
    { role: 'ai', text: '探针预置答：样本回答，用于在开 dock 时给气泡列一个基线。' },
  ];
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'askQuestion') askQuestions.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: docText(), itemType: 'markdown', fileName: 'ask-dock-slash.md', size: docText().length }));
      } else if (m.type === 'askOpen') {
        ws.send(JSON.stringify({ type: 'askState', sessionId: SID, fileName: m.file?.name || 'ask-dock-slash.md', messages: replay }));
      } else if (m.type === 'askQuestion') {
        ws.send(JSON.stringify({ type: 'textDelta', sessionId: m.sessionId, delta: '探针实时回答：' + (m.content || '') }));
        ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, finalText: '探针实时回答：' + (m.content || '') }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(700);
  await page.evaluate(({ absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'ask-dock-slash.md', content: null, absPath, pinned: true },
    }));
  }, { absPath: MD_PATH });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${MD_PATH}"] .canvas-md-viewer`, { timeout: 15000, state: 'attached' });
  await sleep(400);
  await page.click('.ask-fab', { force: true });
  await sleep(900); // 开合曲线 + askState 回放 + 260ms 抢焦点
  return { ctx, page, errors };
}

/** dock 输入读数（气泡数 / 输入值 / 面板在否）。 */
const dockRead = (page) => page.evaluate(() => ({
  bubUser: document.querySelectorAll('.ask-bub.user').length,
  value: (document.querySelector('.ask-dock .ask-input textarea') || {}).value ?? null,
  panel: !!document.getElementById('model-panel-overlay'),
}));

/* ════════ S1：/model + Enter ⇒ 面板响应，零误发 ════════ */
{
  const { ctx, page, errors } = await boot();
  ok('S0 页面零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  const before = await dockRead(page);
  await page.click('.ask-dock .ask-input textarea');
  await page.keyboard.type('/model', { delay: 40 });
  await page.keyboard.press('Enter');
  await page.waitForSelector('#model-panel-overlay', { timeout: 8000 }).catch(() => {});
  await sleep(400);
  const after = await dockRead(page);
  const qs = askQuestions.filter((m) => (m.content || '').startsWith('/model'));
  ok('S1a /model + Enter ⇒ 模型面板可见（面板真实响应，不再被发成提问）', after.panel === true, `panel=${after.panel}`);
  ok('S1b dock 输入清空（命中白名单后本地消费，不留残文）', after.value === '', `value=${JSON.stringify(after.value)}`);
  ok('S1c askQuestion 零误发（含 /model 内容的帧数 = 0）', qs.length === 0, `frames=${JSON.stringify(qs.map((m) => m.content))}`);
  ok('S1d 用户气泡数不变（无注入泡）', before.bubUser > 0 && after.bubUser === before.bubUser, `before=${before.bubUser} after=${after.bubUser}`);
  await ctx.close();
}

/* ════════ S2：/clear（不在白名单）⇒ 封存语义不扩，原样发问 ════════ */
{
  askQuestions.length = 0;
  const { ctx, page } = await boot();
  const before = await dockRead(page);
  await page.click('.ask-dock .ask-input textarea');
  await page.keyboard.type('/clear', { delay: 40 });
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelectorAll('.ask-bub.user').length > 2, null, { timeout: 8000 }).catch(() => {});
  await sleep(300);
  const after = await dockRead(page);
  ok('S2a 未命中白名单 ⇒ askQuestion 原样发出（content = /clear，封存闸零放大）',
    askQuestions.length === 1 && askQuestions[0].content === '/clear', JSON.stringify(askQuestions.map((m) => m.content)));
  ok('S2b /clear 以用户气泡呈现（走 ask 链，非本地吞掉）', after.bubUser === before.bubUser + 1, `before=${before.bubUser} after=${after.bubUser}`);
  ok('S2c 输入清空（发送后回落，与正常发问同式）', after.value === '', `value=${JSON.stringify(after.value)}`);
  ok('S2d /model 面板未被误开', after.panel === false, `panel=${after.panel}`);
  await ctx.close();
}

/* ════════ S3：普通文本 ⇒ 先审零改写，正常发问 ════════ */
{
  askQuestions.length = 0;
  const { ctx, page } = await boot();
  const before = await dockRead(page);
  await page.click('.ask-dock .ask-input textarea');
  await page.keyboard.type('这份文档讲什么？', { delay: 25 });
  await page.keyboard.press('Enter');
  await page.waitForFunction((n) => document.querySelectorAll('.ask-bub.user').length > n, before.bubUser, { timeout: 8000 }).catch(() => {});
  await sleep(300);
  const after = await dockRead(page);
  ok('S3a 普通文本 ⇒ askQuestion 原样发出（先审零改写）',
    askQuestions.length === 1 && askQuestions[0].content === '这份文档讲什么？', JSON.stringify(askQuestions.map((m) => m.content)));
  ok('S3b 用户气泡出现 + 面板未误开', after.bubUser === before.bubUser + 1 && after.panel === false, `before=${before.bubUser} after=${after.bubUser} panel=${after.panel}`);
  await ctx.close();
}

console.log(`\n${pass} pass, ${fail} fail`);
await browser.close();
server.close();
process.exit(fail ? 1 : 0);
