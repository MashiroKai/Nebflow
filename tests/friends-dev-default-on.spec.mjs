// friends-dev-default-on.spec.mjs — 好友功能构建环境区分·dev 两态门
// （判据换代注记：本 spec 初版钉作者令 2026-09-10「dev 默认开」——该前提已被
// friendseal 批作者裁定 ④（2026-09-25，featureFlags.js friendsEnabled：dev 树
// 缺席 = 封存 = 默认 OFF，显式 true 才回挂）推翻。旧「空配置 ⇒ 入口回挂可见」
// 判据在干净 HEAD 即红，换代为钉封存缺省**新形态**（缺席 + 防复活），显式两态
// 与面板激活契约保留。）
//
// 覆盖（featureFlags.js friendsEnabled() 语义，源码树直跑 = 无 __NEBFLOW_RELEASE__）：
//   D1  空配置（configData 不含 features.friends）→ 封存缺省：Messages/Contacts
//       入口与面板**不在 DOM**（防复活钉）；#social-btn 按 friendseal ④ 明示不随
//       封存摘挂（社交卡入口 = "Phone access" 配置面板，非好友域）
//   D2  显式 features.friends:true → 入口回挂（顺序契约 messages → contacts →
//       social 且在 spacer 之前）+ 点击 Contacts → 面板激活（原 D2/D4 合并）
//   D3  显式 features.friends:false → 入口缺席（dev 两态调试：关态可达）
// 自包含：route 拦截静态文件 + mock WS，无后端、无真实端口。
// Run: node tests/friends-dev-default-on.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

async function bootPage({ configJson = '{}' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 't'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: configJson, configured: true, onboarding: 'done', models: [], defaults: {} }));
    sendConfig();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, undefined, { timeout: 15000 });
  await sleep(600); // configData latch → enableFriendPanels 回挂
  return { ctx, page, pageErrors };
}

// ── D1: 空配置 → 封存缺省（friendseal ④）：入口/面板缺席 + 防复活 ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{}' });
  ok('D1 #messages-btn absent (sealed default: dev 树缺席 = OFF，friendseal 2026-09-25 ④)', await page.locator('#messages-btn').count() === 0);
  ok('D1 #contacts-btn absent (sealed default)', await page.locator('#contacts-btn').count() === 0);
  // 防复活钉：面板 DOM 也不得在（detachFriendEntries 摘挂的是按钮 + 面板四件套）。
  ok('D1 #panel-messages/#panel-contacts absent (detach 四件套，防复活)', await page.locator('#panel-messages').count() === 0 && await page.locator('#panel-contacts').count() === 0);
  // friendseal ④ 明示：#social-btn 不随好友封存摘挂（社交卡配置面板，非好友域）。
  ok('D1 #social-btn stays mounted (friendseal ④ 不随封存摘挂)', await page.locator('#social-btn').count() === 1 && await page.locator('#social-btn').evaluate(el => el.isConnected));
  ok('D1 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ── D2: 显式 true → 回挂（顺序契约）+ 可进（原 D2/D4 合并） ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{"features":{"friends":true}}' });
  ok('D2 #messages-btn attached (explicit on)', await page.locator('#messages-btn').count() === 1 && await page.locator('#messages-btn').evaluate(el => el.isConnected));
  ok('D2 #contacts-btn attached (explicit on)', await page.locator('#contacts-btn').count() === 1 && await page.locator('#contacts-btn').evaluate(el => el.isConnected));
  // 顺序契约：messages → contacts → social，且通讯入口在 spacer 之前
  //（enableFriendPanels W1：社交入口的前兄弟保持 contacts）。
  const order = await page.locator('#activity-bar > *').evaluateAll(els => els.map(e => e.id || e.className));
  const spacerIdx = order.findIndex(c => String(c).includes('activity-spacer'));
  ok('D2 entries before spacer, messages→contacts→social (W1)', order.indexOf('messages-btn') !== -1 && order.indexOf('contacts-btn') !== -1 &&
    order.indexOf('contacts-btn') === order.indexOf('messages-btn') + 1 && order.indexOf('contacts-btn') === order.indexOf('social-btn') - 1 &&
    order.indexOf('contacts-btn') < spacerIdx, JSON.stringify(order));
  await page.click('#contacts-btn');
  await sleep(200);
  ok('D2 #panel-contacts activated on click', await page.locator('#panel-contacts.active').count() === 1 &&
    (await page.locator('#contacts-btn').getAttribute('aria-pressed')) === 'true');
  ok('D2 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ── D3: 显式 false → dev 关态（入口缺席） ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{"features":{"friends":false}}' });
  ok('D3 #messages-btn absent (explicit off)', await page.locator('#messages-btn').count() === 0);
  ok('D3 #contacts-btn absent (explicit off)', await page.locator('#contacts-btn').count() === 0);
  ok('D3 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
process.exit(failures ? 1 : 0);
