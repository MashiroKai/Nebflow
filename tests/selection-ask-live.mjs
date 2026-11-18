// Isolated real gateway / loopback LLM probe. All child processes stop cooperatively.
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

const repo = '/private/tmp/stand-selask';
const root = `/private/tmp/qa-selask-live-${process.pid}`;
const privateHome = `/private/tmp/qa-selask-user-${process.pid}`;
const artifacts = '/private/tmp/selask-live-evidence';
const jar = '/Users/kaiyu/Claude code/Stand/target/scala-3.5.2/stand-assembly-2026.11.8-beta.1.jar';
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true }); mkdirSync(artifacts, { recursive: true });
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer(); server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
let hits = 0, gateway, browser;
const mock = http.createServer((req, res) => {
  if (req.method !== 'POST') return res.writeHead(404).end();
  let body = ''; req.on('data', d => { body += d; });
  req.on('end', () => {
    hits++;
    const text = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: { content: '这是一段确定的模拟解释。' }, finish_reason: null }] });
    const done = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
    res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(`data: ${text}\n\ndata: ${done}\n\ndata: [DONE]\n\n`);
  });
});
try {
  await listen(mock);
  const mockPort = mock.address().port, port = await freePort();
  if ([6138,8080,8097].includes(port) || [6138,8080,8097].includes(mockPort)) throw Error('protected port');
  const env = { PATH: '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin', HOME: privateHome, NEBFLOW_HOME_DIR: root, MOCK_PORT: String(mockPort) };
  mkdirSync(root, { recursive: true });
  writeFileSync(`${root}/stand.json`, JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' }, llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${mockPort}/v1/`, apiKey: 'fixture-only-placeholder', protocol: 'openai', models: [{ id: 'mock-1', contextWindow: 128000 }] } }, fallback: { preferred: 'mock/mock-1' } } }));
  mkdirSync(`${root}/agents/Nebula`, { recursive: true });
  writeFileSync(`${root}/agents/Nebula/agent.json`, JSON.stringify({ name: 'Nebula', model: { preferred: 'mock/mock-1' } }));
  writeFileSync(`${root}/onboarding.json`, JSON.stringify({ state: 'skipped' }));
  console.log(`root=${root} gatewayPort=${port} mockPort=${mockPort}`);
  gateway = spawn('/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java', ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-Dnebflow.webdist=dev', '-cp', `${repo}/src/main/resources:${jar}`, 'nebflow.Main', '--home', root, '--port', String(port), '--no-browser', '--foreground', 'start'], { cwd: repo, env: { ...env, NEBFLOW_GATEWAY_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
  console.log(`gatewayPid=${gateway.pid}`);
  let log = '';
  gateway.stdout.on('data', d => { log += d; }); gateway.stderr.on('data', d => { log += d; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`gateway readiness timeout: ${log.slice(-1600)}`)), 90000);
    const check = d => { if (String(d).includes('gateway listening on')) { clearTimeout(timer); resolve(); } };
    gateway.stdout.on('data', check); gateway.stderr.on('data', check);
    gateway.once('exit', code => { clearTimeout(timer); reject(Error(`gateway exited ${code}: ${log.slice(-1600)}`)); });
  });
  const token = JSON.parse(readFileSync(`${root}/auth.json`, 'utf8'));
  const base = `http://127.0.0.1:${port}`;
  const created = await fetch(`${base}/api/sessions`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ name: 'selection fixture' }) });
  if (!created.ok) throw Error(`session create ${created.status}`);
  const session = await created.json();
  browser = await chromium.launch({ headless: true });
  for (const mobile of [false, true]) {
    const context = await browser.newContext({ viewport: mobile ? { width: 390, height: 844 } : { width: 1200, height: 900 }, isMobile: mobile, hasTouch: mobile, recordVideo: { dir: artifacts, size: mobile ? { width: 390, height: 844 } : { width: 1200, height: 900 } } });
    const page = await context.newPage();
    page.on('pageerror', error => console.log('pageerror', String(error)));
    await page.addInitScript(() => {
      const Native = window.WebSocket;
      window.__frames = [];
      window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
    });
    await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'sessionList' && f.activeId), null, { timeout: 30000 });
    const activeId = await page.evaluate(() => window.__frames.find(f => f.type === 'sessionList' && f.activeId).activeId);
    console.log(`activeSession=${activeId}`);
    const warm = await fetch(`${base}/api/sessions/${activeId}/turn`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ content: 'fixture warmup', timeoutSec: 30 }) });
    console.log(`warmup status=${warm.status} hits=${hits} response=${(await warm.text()).slice(0, 300)}`);
    if (!warm.ok || hits === 0) throw Error('warmup did not reach loopback model');
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'historyPage'), null, { timeout: 30000 });
    await page.evaluate(async () => {
      const chat = await import('/js/chat.js');
      chat.renderUserBubble('before selected after', [], Date.now(), { msgId: 'selection-fixture' });
    });
    const row = page.locator('#chat .row.user[data-msg-id="selection-fixture"]').last();
    await row.waitFor({ timeout: 30000 });
    await row.evaluate(row => {
      const walker = document.createTreeWalker(row, NodeFilter.SHOW_TEXT); let n;
      while ((n = walker.nextNode()) && !n.textContent.includes('before selected after')) {}
      const r = document.createRange(); r.setStart(n, 7); r.setEnd(n, 15); const s = getSelection(); s.removeAllRanges(); s.addRange(r);
    });
    if (mobile) {
      const box = await row.boundingBox(); const cdp = await context.newCDPSession(page);
      const x = Math.round(box.x + box.width / 2), y = Math.round(box.y + box.height / 2);
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
      await page.locator('.fm-context-menu').waitFor({ timeout: 4000 });
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    } else {
      const box = await row.locator('.bubble').boundingBox();
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: 'right' });
    }
    console.log('menu', await page.locator('.fm-context-menu').allTextContents(), 'row', await row.evaluate(el => ({ connected: el.isConnected, desc: !!el.__nfQuoteDesc, selected: getSelection().toString(), body: document.body.className })));
    await page.getByRole('menuitem', { name: 'Ask', exact: true }).click();
    try { await page.locator('.selection-ask-answer').getByText('这是一段确定的模拟解释。').waitFor({ timeout: 15000 }); }
    catch (e) { console.log('answer state', await page.locator('.selection-ask-answer').allTextContents(), 'frames', await page.evaluate(() => window.__frames.filter(f => /ask|busy|error/i.test(f.type)).map(f => ({type:f.type, message:f.message, sessionId:f.sessionId})))); throw e; }
    await page.locator('.selection-ask-panel button').last().click();
    await page.locator('#attachment-preview').getByText('这是一段确定的模拟解释。').first().waitFor({ timeout: 10000 });
    console.log(`${mobile ? 'mobile' : 'desktop'} PASS askDelta=${await page.evaluate(() => window.__frames.filter(f => f.type === 'askTextDelta').length)} mockHits=${hits}`);
    const video = page.video(); await context.close(); console.log(`video=${await video.path()}`);
  }
} finally {
  if (browser) await browser.close();
  if (gateway && gateway.exitCode === null) {
    const exit = new Promise(resolve => gateway.once('exit', resolve)); gateway.stdin.write('quit\n');
    const result = await Promise.race([exit.then(() => 'exited'), new Promise(resolve => setTimeout(() => resolve('NOT_EXITED'), 30000))]);
    console.log(`gatewayShutdown=${result} pid=${gateway.pid}`);
    if (result !== 'exited') process.exitCode = 1;
  }
  await new Promise(resolve => mock.close(resolve));
  console.log(`mockClosed=true hits=${hits} root=${root}`);
}
