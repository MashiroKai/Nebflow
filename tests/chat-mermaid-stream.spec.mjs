// Synthetic WS producer; real main.js, chat/persistence modules, CSS and Mermaid vendor.
// Run after MermaidStreamPreservationSpec with MERMAID_STREAM_FIXTURE pointing to its JSON.
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src/main/resources/web');
const OUT = join(ROOT, 'target/mermaid-stream');
const fixture = JSON.parse(readFileSync(process.env.MERMAID_STREAM_FIXTURE || join(OUT, 'fixed.json'), 'utf8'));
const baseline = process.env.MERMAID_EXPECT_BROKEN === '1';
const SID = 'synthetic-mermaid';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
mkdirSync(OUT, { recursive: true });
let assertions = 0;
function check(value, label) { assert.ok(value, label); assertions++; console.log('PASS', label); }

async function boot(browser, width, dark) {
  const ctx = await browser.newContext({ viewport: { width, height: 900 }, colorScheme: dark ? 'dark' : 'light' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'synthetic');
    localStorage.setItem('neblink_token', 'synthetic');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.hostname !== 'synthetic.invalid') return route.abort();
    const path = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
    if (path.startsWith('/api/') || path.startsWith('/agents/')) {
      return route.fulfill({ json: path === '/api/sessions' ? { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } : {} });
    }
    const file = join(WEB, path);
    if (!file.startsWith(WEB + '/')) return route.abort();
    try { return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' }); }
    catch { return route.fulfill({ status: 404, body: 'missing synthetic asset' }); }
  });
  let socket;
  let connections = 0;
  let onReconnect;
  let history = [{ type: 'user', text: 'Synthetic diagram request', timestamp: 1 }];
  let historyRequests = 0;
  let onHistory;
  await page.routeWebSocket(/\/ws/, ws => {
    socket = ws; connections++;
    if (onReconnect) onReconnect(connections);
    const send = msg => ws.send(JSON.stringify(msg));
    const config = () => send({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} });
    const sessions = () => send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
    config(); sessions();
    ws.onMessage(raw => {
      const msg = JSON.parse(raw);
      if (msg.type === 'ping') send({ type: 'pong' });
      if (msg.type === 'getConfig') config();
      if (msg.type === 'getSessions' || msg.type === 'listSessions') sessions();
      if (msg.type === 'getHistory') {
        historyRequests++;
        if (onHistory) onHistory();
        send({ type: 'historyPage', sessionId: SID, messages: history, hasMore: false, offset: 0, total: history.length });
      }
    });
  });
  await page.goto('http://synthetic.invalid/');
  await page.waitForSelector('#chat .row', { state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach(el => el.remove());
    document.documentElement.classList.remove('ui-init');
  });
  return {
    ctx, page, errors,
    send: frames => frames.forEach(frame => socket.send(JSON.stringify({ ...frame, sessionId: SID }))),
    setHistory: value => { history = value; },
    reconnect: async () => {
      const before = connections;
      const requestsBefore = historyRequests;
      const historyReceived = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('reconnect history request missing')), 15000);
        onHistory = () => { clearTimeout(timer); onHistory = null; resolve(); };
      });
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('synthetic WS reconnect timed out')), 15000);
        onReconnect = count => { clearTimeout(timer); onReconnect = null; resolve(count); };
        socket.close({ code: 1012, reason: 'synthetic reconnect' });
      });
      await historyReceived;
      check(connections === before + 1 && historyRequests > requestsBefore, 'real reconnect establishes transport and requests fresh history');
    },
    historyRequests: () => historyRequests,
  };
}

async function rawTexts(page) {
  return page.locator('#chat .bubble.ai').evaluateAll(els => els.map(el => el._nfText));
}
async function visibleSvg(page, count) {
  await page.waitForFunction(n => document.querySelectorAll('#chat .nf-mermaid[data-mermaid-state="done"] svg').length === n, count);
  const blocks = page.locator('#chat .nf-mermaid');
  for (let i = 0; i < count; i++) {
    await blocks.nth(i).scrollIntoViewIfNeeded();
    const box = await blocks.nth(i).locator('svg').boundingBox();
    check(box && box.width > 20 && box.height > 20, `visible SVG ${i + 1}: ${JSON.stringify(box)}`);
  }
}
async function clearChat(page) {
  await page.evaluate(async () => {
    const { default: state } = await import('/js/state.js');
    const { activeView } = await import('/js/chatView.js');
    document.getElementById('chat').replaceChildren();
    activeView.stream.currentAiBubble = null;
    activeView.stream.aiText = '';
    delete state.sessionTexts[state.activeSessionId];
  });
}

const browser = await chromium.launch();
try {
  for (const width of baseline ? [1440] : [1440, 375]) {
    for (const dark of baseline ? [false] : [false, true]) {
      console.log(`MATRIX width=${width} theme=${dark ? 'dark' : 'light'} baseline=${baseline}`);
      const app = await boot(browser, width, dark);
      const { page } = app;
      try {
        app.send(fixture.single);
        await visibleSvg(page, 1);
        check((await rawTexts(page)).at(-1) === fixture.expected, 'single-frame done retains exact source');
        await clearChat(page);
        app.send(fixture.split);
        await page.waitForFunction(() => document.querySelector('#chat .bubble.ai')?._nfRendered);
        const actual = (await rawTexts(page)).at(-1);
        if (baseline) {
          check(actual === fixture.historyText && actual.includes('```mermaidflowchart'), 'Scala-produced broken WS frames reproduce glued fence in real main.js');
          check(await page.locator('#chat .nf-mermaid').count() === 0, 'glued fence produces no Mermaid placeholder/SVG');
          await page.screenshot({ path: join(OUT, 'baseline-glued.png') });
          continue;
        }
        check(actual === fixture.expected, 'Scala split frames equal single-frame Markdown');
        await visibleSvg(page, 1);
        await clearChat(page);
        const pieces = ['```mermaid', '\n', 'flowchart TD\n  A[Start] --> B[End]', '\n```'];
        for (const [index, delta] of pieces.entries()) {
          app.send([{ type: 'textDelta', delta, round: 0, block: index + 1 }]);
          await page.waitForFunction(expected => document.querySelector('#chat .bubble.ai')?._nfText === expected, pieces.slice(0, index + 1).join(''));
          check(await page.locator('#chat .nf-mermaid').count() === 0, `stream frame ${index + 1}: no premature placeholder`);
        }
        app.send([{ type: 'done' }]);
        await visibleSvg(page, 1);
        check((await rawTexts(page)).at(-1) === fixture.expected, 'frontend four WS deltas preserve newline exactly');
        await clearChat(page);
        app.send([
          ...fixture.single.filter(f => f.type !== 'done'),
          { type: 'toolCallDetected', name: 'Read' },
          { type: 'toolStart', label: 'Read synthetic.txt' },
          { type: 'toolEnd', label: 'Read synthetic.txt', summary: 'synthetic result', content: 'fixture only', isError: false },
          ...fixture.split,
        ]);
        await visibleSvg(page, 2);
        check((await rawTexts(page)).every(text => text === fixture.expected), 'tool boundary preserves two distinct diagrams');
        const history = [
          { type: 'ai', text: fixture.historyText, timestamp: 2 },
          { type: 'tool', label: 'Read synthetic.txt', content: 'fixture only', timestamp: 3 },
          { type: 'ai', text: fixture.historyText, timestamp: 4 },
        ];
        app.setHistory(history);
        // Reload uses getHistory → main.js historyPage → persistence restore (not direct render calls).
        await page.reload();
        await visibleSvg(page, 2);
        check(await page.locator('#chat .nf-mermaid').evaluateAll(els => els.length === 2 && els.every(el => decodeURIComponent(el.getAttribute('data-mermaid-src')).includes('flowchart TD\n  A[Start] --> B[End]'))), 'historyPage restore retains Mermaid source and visible SVG');
        app.setHistory(history.map(msg => msg.type === 'ai' ? { ...msg, text: msg.text.replace('Start', 'Reconnected') } : msg));
        await app.reconnect();
        await page.waitForFunction(() => [...document.querySelectorAll('#chat .nf-mermaid')].every(el => el.getAttribute('data-mermaid-src').includes('Reconnected')));
        await visibleSvg(page, 2);
        check(await page.locator('#chat .nf-mermaid svg').first().textContent().then(text => text.includes('Reconnected')), 'fresh reconnect history replaces prior SVG content');
        const header = page.locator('#chat .turn-header').first();
        await header.click();
        check(await header.getAttribute('aria-expanded') === 'true', 'real turn header expands process rows');
        await visibleSvg(page, 2);
        await header.click();
        check(await header.getAttribute('aria-expanded') === 'false', 'real turn header collapses without losing diagrams');
        await page.screenshot({ path: join(OUT, `history-${width}-${dark ? 'dark' : 'light'}.png`) });
        // Native collapse keeps a real history-rendered node hidden before hydration.
        // The next restore places the second diagram outside the viewport for lazy loading.
        app.setHistory([
          { type: 'ai', text: fixture.expected, timestamp: 5 },
          { type: 'ai', text: Array.from({ length: 100 }, (_, i) => `Synthetic filler ${i}\n\n`).join(''), timestamp: 6 },
          { type: 'ai', text: fixture.expected, timestamp: 7 },
        ]);
        await page.reload();
        await page.waitForSelector('#chat .nf-mermaid', { state: 'attached' });
        const lazy = page.locator('#chat .nf-mermaid').first();
        check(await lazy.getAttribute('data-mermaid-state') !== 'done', 'offscreen history diagram waits for viewport hydration');
        const collapse = await page.evaluate(() => {
          const row = document.querySelector('#chat .nf-mermaid').closest('.row');
          const details = document.createElement('details');
          const summary = document.createElement('summary'); summary.textContent = 'Synthetic collapse';
          row.before(details); details.append(summary, row); return true;
        });
        check(collapse, 'unhydrated history diagram enters native collapsed container');
        await page.locator('#chat').hover();
        await page.mouse.wheel(0, -10000);
        await page.locator('#chat details summary').scrollIntoViewIfNeeded();
        check(await lazy.getAttribute('data-mermaid-state') !== 'done', 'collapsed diagram remains unrendered while its summary is visible');
        await page.locator('#chat details summary').click();
        await page.waitForFunction(() => document.querySelector('#chat .nf-mermaid')?.dataset.mermaidState === 'done');
        const expanded = await lazy.locator('svg').boundingBox();
        check(expanded && expanded.width > 20 && expanded.height > 20, 'expanded diagram remains visible');
        check(app.errors.length === 0, `no uncaught page errors: ${app.errors.join('; ')}`);
      } finally { await app.ctx.close(); }
    }
  }
} finally { await browser.close(); }
console.log(`ALL PASS: ${assertions} assertions`);
