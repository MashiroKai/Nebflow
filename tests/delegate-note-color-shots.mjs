// delegate-note-color-shots.mjs — delegate 完工通知卡 +x/−y 小注语义色批
// （2026-10-31 14:14 作者令「diff 计数用颜色区分并且靠右对齐」）取证/验收：
//   · +数绿 / −数红（双主题可读，零新色值：复用 --status-success-icon /
//     --status-error-icon 亮暗双档既有 token 对）；
//   · 小注整体右对齐到卡缘（卡内 content box 右缘，±1px）；
//   · 其余逐字不动：零背景零边框零 padding、不加粗、点击行为/aria-pressed/
//     textContent（"+a −r"，字面空格保留）全保持。
// 用法：node tests/delegate-note-color-shots.mjs [before|after]
// 产物：evidence/20261031_delegate-note-color__delegate-nebflow-0fe8d307/
//   shots/<phase>-note-{dark,light}.png + readings.<phase>.json
import { chromium } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const OUT = join(HERE, '..', 'evidence', '20261031_delegate-note-color__delegate-nebflow-0fe8d307');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'delegate-note-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`);
  });
};

const PAYLOAD = {
  nodeId: 'deleg-note-color', generatedAt: 1793167000000,
  root: '/Users/kaiyu/Claude code/Stand',
  files: [
    { path: 'src/main/resources/web/js/chat/a.js', changeType: 'modified', added: 87, removed: 92, source: 'tool-ledger' },
    { path: 'src/main/resources/web/js/chat/b.js', changeType: 'modified', added: 12, removed: 7, source: 'tool-ledger' },
  ],
};
const MSG = {
  source: 'delegate', eventType: 'completed', sender: 'nebflow',
  header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
  text: '委托完成：diff 计数上色右对齐小批。', fileChanges: PAYLOAD,
};

const browser = await chromium.launch();

async function boot(width, height, scheme) {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 2,
  })).newPage();
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  return page;
}

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

const inject = (page, msg) => page.evaluate((m) => import('/js/state.js').then((mod) => {
  const s = mod.default;
  s.ws.onmessage({
    data: JSON.stringify({
      type: 'user', injected: true, sessionId: s.activeSessionId,
      timestamp: Date.now(), ...m,
    }),
  });
}), msg);

const measure = (page) => page.evaluate(() => {
  const bubble = document.querySelector('.bubble.injected');
  const note = bubble.querySelector('.nf-rcpt-note');
  const add = bubble.querySelector('.nf-rcpt-note-add');
  const del = bubble.querySelector('.nf-rcpt-note-del');
  const bcs = getComputedStyle(bubble);
  const bubbleContentRight = bubble.getBoundingClientRect().right - parseFloat(bcs.paddingRight);
  const ncs = note ? getComputedStyle(note) : null;
  return {
    noteText: note?.textContent.trim() || '',
    noteColor: ncs?.color || '',
    noteBg: ncs?.backgroundColor || '',
    noteBorder: ncs?.borderTopWidth || '',
    notePad: ncs?.paddingLeft || '',
    noteFontWeight: ncs?.fontWeight || '',
    addColor: add ? getComputedStyle(add).color : null,
    delColor: del ? getComputedStyle(del).color : null,
    rightDelta: note ? Math.abs(note.getBoundingClientRect().right - bubbleContentRight) : null,
    pressed: note?.getAttribute('aria-pressed') || '',
  };
});

const readings = { phase: PHASE, tiers: {} };

for (const [scheme, label] of [['dark', 'dark'], ['light', 'light']]) {
  const page = await boot(1440, 900, scheme);
  await inject(page, MSG);
  await raf(page);
  const m = await measure(page);
  readings.tiers[label] = m;
  ok(`[${label}] 小注 textContent = +99 −99（字面空格保留）`, m.noteText === '+99 −99', `actual="${m.noteText}"`);
  ok(`[${label}] 小注零背景零边框零 padding 不加粗（低调语义保持）`,
    m.noteBorder === '0px' && (m.noteBg === 'rgba(0, 0, 0, 0)' || m.noteBg === 'transparent')
    && m.notePad === '0px' && (m.noteFontWeight === '400' || m.noteFontWeight === 'normal'),
    JSON.stringify({ bg: m.noteBg, border: m.noteBorder, pad: m.notePad, w: m.noteFontWeight }));
  ok(`[${label}] +/− 两段语义色在场`, !!m.addColor && !!m.delColor, `add=${m.addColor} del=${m.delColor}`);
  ok(`[${label}] 小注右缘贴卡缘（±1px）`, m.rightDelta !== null && m.rightDelta < 1, `delta=${m.rightDelta}`);
  ok(`[${label}] aria-pressed 初始 false`, m.pressed === 'false');
  await page.locator('.bubble.injected').first().screenshot({ path: join(SHOTS, `${PHASE}-note-${label}.png`) });
  await page.close();
}

// 语义色跨主题差异（同一 token 亮暗双档 ⇒ 两档读数不得相同）与绿≠红。
if (readings.tiers.dark.addColor && readings.tiers.light.addColor) {
  ok('add 色双档可读且亮暗分档', readings.tiers.dark.addColor !== readings.tiers.light.addColor,
    `dark=${readings.tiers.dark.addColor} light=${readings.tiers.light.addColor}`);
  ok('add≠del（绿红可分）', readings.tiers.dark.addColor !== readings.tiers.dark.delColor
    && readings.tiers.light.addColor !== readings.tiers.light.delColor);
}

await browser.close();
ok('console/pageerror 零新增错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
writeFileSync(join(OUT, `readings.${PHASE}.json`), JSON.stringify(readings, null, 2));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
