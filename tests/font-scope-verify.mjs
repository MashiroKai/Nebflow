// font-scope-verify.mjs — 字号作用域双端验证（作者令 21:36：字号放大只手机端）：
// 桌面 1440（朋友/消息面板）+ 手机 390（mobile-layer 消息/联系人页）双跑，
// computed 读数断言 9 项档位 + 双主题截图。
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261031_mobile-emergency-inspect', 'font-scope');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'font-scope';
const NOW = Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const WIRE_FRIEND = { userId: 'u-f', username: 'buddy7', display_name: '老友', avatar: '', remark: null };
const WIRE_FRIEND2 = { userId: 'u-g', username: 'kai8', display_name: '凯', avatar: '', remark: '同事小凯' };

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const browser = await chromium.launch();

async function routes(page) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/friends') return route.fulfill({ json: { friends: [WIRE_FRIEND, WIRE_FRIEND2], incoming: [], outgoing: [] } });
    if (p === '/api/neblink/status') return route.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } });
    if (p === '/api/conversations') return route.fulfill({ json: [{ conversationId: 'c-1', friend: WIRE_FRIEND, lastMessage: { body: '晚上一起吃饭吗', timestamp: NOW - 3000 }, unreadCount: 2 }] });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    try { return route.fulfill({ status: 200, contentType: MIME[Object.keys(MIME).find((e) => p.endsWith(e))] || 'application/octet-stream', body: readFileSync(join(WEB, p)) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

/** computed 读数探针：rootSel 作用域内首个命中的条目（防串到隐藏面板）。 */
async function readFontProbe(page, rootSel) {
  return page.evaluate((rootSel) => {
    const root = rootSel ? document.querySelector(rootSel) : document;
    if (!root) return { error: `root ${rootSel} not found` };
    const out = {};
    const grab = (key, sel) => {
      const n = root.querySelector(sel);
      if (!n) { out[key] = null; return; }
      const s = getComputedStyle(n);
      out[key] = { fontSize: s.fontSize, lineHeight: s.lineHeight, padV: s.paddingTop, padH: s.paddingRight };
    };
    grab('rowName', '.fm-row-name');
    grab('rowSub', '.fm-row-sub');
    grab('convSummary', '.fm-conv-summary');
    grab('convTime', '.fm-conv-time');
    grab('nfLabel', '.fm-nf-label');
    grab('reqTime', '.fm-req-time');
    grab('avatar36', '.fm-avatar-36');
    grab('avatar40', '.fm-avatar-40');
    const anyRow = root.querySelector('.fm-row');
    out.rowPad = anyRow ? getComputedStyle(anyRow).padding : null;
    out.mobileLayer = document.body.classList.contains('mobile-layer');
    return out;
  }, rootSel || null);
}

// ── 桌面腿：1440×900，朋友面板 + 消息面板 ──
for (const scheme of ['dark', 'light']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('pageerror:', e.message.slice(0, 160)));
  page.on('console', (m) => { if (m.type() === 'error') console.log('console:', m.text().slice(0, 160)); });
  console.log(`[boot desktop/${scheme}]`);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await routes(page);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => !!document.getElementById('contacts-btn'), { timeout: 25000 });
  await sleep(900);
  await page.click('#contacts-btn');
  try {
    await page.waitForSelector('.fm-friend-row', { timeout: 15000 });
  } catch {
    const dbg = await page.evaluate(() => ({
      panelCls: document.getElementById('panel-contacts')?.className,
      rows: document.querySelectorAll('.fm-friend-row').length,
      fmRows: document.querySelectorAll('.fm-row').length,
      bodyCls: document.body.className.slice(0, 80),
      panelHTML: document.getElementById('panel-contacts')?.innerHTML.slice(0, 600),
    }));
    console.log(`[desktop/${scheme} DEBUG]`, JSON.stringify(dbg, null, 1));
    throw new Error('desktop contacts panel debug dump above');
  }
  await sleep(500);
  const p1 = await readFontProbe(page, '#panel-contacts.active');
  console.log(`[desktop/${scheme}]`, JSON.stringify(p1));
  await page.screenshot({ path: join(OUT, `desktop-contacts-${scheme}.png`) });
  ok(`desktop/${scheme} mobile-layer 关闭`, p1.mobileLayer === false);
  ok(`desktop/${scheme} 行名=13px`, p1.rowName && p1.rowName.fontSize === '13px', p1.rowName && p1.rowName.fontSize);
  ok(`desktop/${scheme} 行副文案=11px`, p1.rowSub && p1.rowSub.fontSize === '11px');
  ok(`desktop/${scheme} 新朋友标签=13px`, p1.nfLabel && p1.nfLabel.fontSize === '13px');
  ok(`desktop/${scheme} 头像36=14px`, p1.avatar36 && p1.avatar36.fontSize === '14px');
  ok(`desktop/${scheme} 行内垫=7px 10px`, p1.rowPad === '7px 10px', p1.rowPad);
  // 消息面板（会话行）
  await page.evaluate(() => { const b = document.querySelector('#messages-btn'); if (b) b.click(); });
  await sleep(600);
  const hasConv = await page.evaluate(() => !!document.querySelector('#panel-messages.active .fm-conv-row'));
  if (hasConv) {
    const p2 = await readFontProbe(page, '#panel-messages.active');
    console.log(`[desktop/${scheme} msgs]`, JSON.stringify(p2));
    ok(`desktop/${scheme} 会话摘要=12px`, p2.convSummary && p2.convSummary.fontSize === '12px', p2.convSummary && p2.convSummary.fontSize);
    ok(`desktop/${scheme} 会话时间=11px`, p2.convTime && p2.convTime.fontSize === '11px');
    await page.screenshot({ path: join(OUT, `desktop-messages-${scheme}.png`) });
  } else console.log(`[desktop/${scheme}] 会话面板未渲染（入口钮缺失），跳过摘要断言`);
  await ctx.close();
}

// ── 手机腿：390×844（body.mobile-layer 激活），消息页 + 联系人页 ──
for (const scheme of ['dark', 'light']) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme, deviceScaleFactor: 2, hasTouch: true });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await routes(page);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(1000);
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await page.waitForSelector('.mobile-page[data-page="messages"] .fm-conv-row', { timeout: 15000 });
  await sleep(500);
  const pm = await readFontProbe(page, '.mobile-page[data-page="messages"].live');
  console.log(`[mobile/${scheme} msgs]`, JSON.stringify(pm));
  ok(`mobile/${scheme} mobile-layer 激活`, pm.mobileLayer === true);
  ok(`mobile/${scheme} 会话摘要=14px`, pm.convSummary && pm.convSummary.fontSize === '14px', pm.convSummary && pm.convSummary.fontSize);
  ok(`mobile/${scheme} 会话时间=12px`, pm.convTime && pm.convTime.fontSize === '12px');
  await page.screenshot({ path: join(OUT, `mobile-messages-${scheme}.png`) });
  await page.locator('#mobile-dock .dock-key[data-dock="contacts"]').click();
  await page.waitForSelector('.mobile-page[data-page="contacts"] .fm-friend-row', { timeout: 15000 });
  await sleep(500);
  const pc = await readFontProbe(page, '.mobile-page[data-page="contacts"].live');
  console.log(`[mobile/${scheme} contacts]`, JSON.stringify(pc));
  ok(`mobile/${scheme} 行名=16px+行高1.4`, pc.rowName && pc.rowName.fontSize === '16px' && parseFloat(pc.rowName.lineHeight) > 20, JSON.stringify(pc.rowName));
  ok(`mobile/${scheme} 行副文案=13px`, pc.rowSub && pc.rowSub.fontSize === '13px');
  ok(`mobile/${scheme} 新朋友标签=16px`, pc.nfLabel && pc.nfLabel.fontSize === '16px');
  ok(`mobile/${scheme} 头像36=16px`, pc.avatar36 && pc.avatar36.fontSize === '16px');
  ok(`mobile/${scheme} 行内垫=9px 10px`, pc.rowPad === '9px 10px', pc.rowPad);
  await page.screenshot({ path: join(OUT, `mobile-contacts-${scheme}.png`) });
  await ctx.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAIL`);
process.exit(failures === 0 ? 0 : 1);
