// selection-explain-smoke.mjs — selectionExplain WS 路由真链路冒烟（隔离网关 + loopback mock 小模型）。
// 判据：发 {type:'selectionExplain', requestId, question} ⇒ 收到 selectionExplain{requestId,text}，
// text 含 mock 解释；且 mock 命中数 ≥1（证明走的是 small-llm 候选源的真实模型调用）。
// 运行：STAND_JAR=<assembly jar> node tests/selection-explain-smoke.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = `/private/tmp/qa-selexpl-smoke-${process.pid}`;
const privateHome = `/private/tmp/qa-selexpl-smoke-user-${process.pid}`;
const jar = process.env.STAND_JAR;
const JAVA = process.env.STAND_JAVA || '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java';
if (!jar) throw Error('STAND_JAR required');
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true });
const EXPLAIN = '冒烟解释：这段文字的含义是测试。';
let hits = 0, gateway, browser;
const mock = http.createServer((req, res) => {
  if (req.method !== 'POST') return res.writeHead(404).end();
  let body = '';
  req.on('data', d => { body += d; });
  req.on('end', () => {
    hits++;
    // selection-explain 腿是非流式单次调用 ⇒ 回 JSON；流式请求才回 SSE（按请求体 stream 字段分流）。
    let streaming = false;
    try { streaming = JSON.parse(body).stream === true; } catch {}
    if (!streaming) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ id: 'mock', object: 'chat.completion', model: 'mock-1', choices: [{ index: 0, message: { role: 'assistant', content: EXPLAIN }, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
    }
    const text = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: { content: EXPLAIN }, finish_reason: null }] });
    const done = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
    res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(`data: ${text}\n\ndata: ${done}\n\ndata: [DONE]\n\n`);
  });
});
const listen = s => new Promise((res, rej) => { s.once('error', rej); s.listen(0, '127.0.0.1', res); });
const freePort = () => new Promise((res, rej) => { const s = net.createServer(); s.on('error', rej); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });

try {
  await listen(mock);
  const mockPort = mock.address().port, port = await freePort();
  if ([6138, 8080, 8097].includes(port) || [6138, 8080, 8097].includes(mockPort)) throw Error('protected port');
  mkdirSync(root, { recursive: true });
  writeFileSync(`${root}/stand.json`, JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' }, llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${mockPort}/v1/`, apiKey: 'fixture-only-placeholder', protocol: 'openai', models: [{ id: 'mock-1', contextWindow: 128000 }] } }, fallback: { preferred: 'mock/mock-1' } } }));
  mkdirSync(`${root}/agents/Nebula`, { recursive: true });
  writeFileSync(`${root}/agents/Nebula/agent.json`, JSON.stringify({ name: 'Nebula', model: { preferred: 'mock/mock-1' } }));
  writeFileSync(`${root}/onboarding.json`, JSON.stringify({ state: 'skipped' }));
  gateway = spawn(JAVA, ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-Dnebflow.webdist=dev', '-cp', `${repo}/src/main/resources:${jar}`, 'nebflow.Main', '--home', root, '--port', String(port), '--no-browser', '--foreground', 'start'], { cwd: repo, env: { PATH: '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin', HOME: privateHome, NEBFLOW_HOME_DIR: root, NEBFLOW_GATEWAY_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
  let log = '';
  gateway.stdout.on('data', d => { log += d; }); gateway.stderr.on('data', d => { log += d; });
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(Error(`readiness: ${log.slice(-800)}`)), 90000);
    const c = d => { if (String(d).includes('gateway listening on')) { clearTimeout(t); res(); } };
    gateway.stdout.on('data', c); gateway.stderr.on('data', c);
    gateway.once('exit', code => { clearTimeout(t); rej(Error(`gateway exited ${code}`)); });
  });
  const token = JSON.parse(readFileSync(`${root}/auth.json`, 'utf8'));
  const base = `http://127.0.0.1:${port}`;
  browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext()).newPage();
  await page.addInitScript(() => {
    const Native = window.WebSocket;
    window.__frames = [];
    window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
  });
  await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
  const requestId = 'smoke-req-1';
  await page.evaluate((rid) => window.__socket.send(JSON.stringify({ type: 'selectionExplain', requestId: rid, question: '请解释：冒烟选区文字' })), requestId);
  await page.waitForFunction((rid) => window.__frames.some(f => (f.type === 'selectionExplain' || f.type === 'selectionExplainError') && f.requestId === rid), requestId, { timeout: 60000 });
  const frame = await page.evaluate((rid) => window.__frames.find(f => f.requestId === rid), requestId);
  const ok = frame.type === 'selectionExplain' && String(frame.text || '').includes('冒烟解释') && hits >= 1;
  console.log(JSON.stringify({ frame, mockHits: hits }));
  console.log(`VERDICT=${ok ? 'PASS' : 'FAIL'}`);
  if (!ok) { console.log(`GATEWAY_LOG_TAIL:\n${log.slice(-2500)}`); process.exitCode = 1; }
} finally {
  if (browser) await browser.close();
  if (gateway && gateway.exitCode === null) {
    const exit = new Promise(res => gateway.once('exit', res)); gateway.stdin.write('quit\n');
    const r = await Promise.race([exit.then(() => 'exited'), new Promise(res => setTimeout(() => res('NOT_EXITED'), 30000))]);
    console.log(`gatewayShutdown=${r} pid=${gateway.pid}`);
  }
  await new Promise(res => mock.close(res));
  console.log(`mockClosed=true hits=${hits}`);
}
