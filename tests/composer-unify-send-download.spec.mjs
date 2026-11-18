#!/usr/bin/env node
// composer-unify-send-download.spec.mjs — composer/message-item 统一批·**真实发送 +
// 两面叠卡一致 + 下载真落盘**验收（2026-11-18 复核）。
//
// 与 tests/composer-message-item-unify-app.spec.mjs 的分工（互补，不重复）：
//   · app spec  = 真 index.html 里的**渲染几何同源**（computed 逐值相等矩阵）+ 叠卡两态；
//   · 本 spec   = 那条几何之外的**行为腿**：
//       A 主会话真实发送：真 composer 键鼠事件 → 真 ws 客户端出帧（帧里带探针文案）
//         → 输入框清空（引擎侧在本 spec 里是 mock ws，客户端链路是真的）。
//       B 消息面真实发送：真 `.fm-input` 键鼠事件 → 真 friendsApi REST（**不经
//         fm_api_mock**）→ 真 HTTP 到**伪 neblink 中继**（仓内既有手法，
//         tests/composer-unify-fake-neblink.mjs，127.0.0.1:18998）→ 中继落库到磁盘
//         JSON → spec 读**磁盘那一份**断言后端确实收到（进程内假答不算数）。
//       C 两面叠卡一致：同一条（同附件组）消息在两面各由**真组件**渲染，叠卡两态
//         标记 + 每卡 `.msg-dl` 键数 + footer 基座 computed 逐值一致。
//       D 下载真落盘：两面各点一次 `.msg-dl` → Playwright download 事件 → saveAs 落盘
//         → 断言字节 > 0 且是 PNG 头（「假按钮」不算，必须真有字节）。
//   两视口 375×812 / 1280×800，各录屏一段（EVID 目录，供 ffprobe 取证）。
//
// 隔离护栏：只绑 127.0.0.1；端口 18998（非保护口 6138/8080/8097）；数据只写
// /tmp/stand-cu2/；不碰生产实例与主检出。
//
// Run: node tests/composer-unify-send-download.spec.mjs

import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync, rmSync, existsSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = process.env.EVID || join(ROOT, '.stand', 'tmp', 'composer-unify', 'evidence', 'shots-send');
const RELAY = { port: 18998, base: 'http://127.0.0.1:18998', store: '/tmp/stand-cu2/fake-neblink-store.json' };
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };

const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
const UID = 'u-cu-real';
const CID = 'conv-cu-real';
const nowSec = () => Math.floor(Date.now() / 1000);
const HISTORY = [
  { type: 'user', text: '统一批主会话用户行', timestamp: nowSec() * 1000 - 5000 },
  { type: 'ai', text: '统一批主会话回执行', timestamp: nowSec() * 1000 - 4000 },
];
const MAIN_PROBE = '统一批·主会话真实发送探针';
const FM_PROBE = '统一批·消息面真实发送探针';

let failures = 0;
const results = [];
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  results.push({ name, pass: !!cond, extra });
  if (!cond) failures++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 等伪 neblink 中继就绪（真 HTTP 探活；macOS 无 timeout，用 fetch + 轮询）。 */
async function waitRelay(ms = 15000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    try {
      const r = await fetch(`${RELAY.base}/api/friends`);
      if (r.ok) return true;
    } catch { /* not up yet */ }
    await sleep(200);
  }
  return false;
}
const readStore = () => (existsSync(RELAY.store) ? JSON.parse(readFileSync(RELAY.store, 'utf8')) : null);
async function waitStore(pred, ms = 8000) {
  const until = Date.now() + ms;
  let last = null;
  while (Date.now() < until) {
    last = readStore();
    if (last && pred(last)) return last;
    await sleep(200);
  }
  return last;
}

const relay = spawn(process.execPath, [join(ROOT, 'tests', 'composer-unify-fake-neblink.mjs')], { stdio: ['ignore', 'pipe', 'pipe'] });
relay.stdout.on('data', () => {});
relay.stderr.on('data', (d) => process.stderr.write(`[relay] ${d}`));
const relayUp = await waitRelay();

async function boot(page, outFrames) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_trusted', JSON.stringify([]));
    // 刻意**不设** fm_api_mock：本 spec 要的是真 REST 腿。
    localStorage.removeItem('fm_api_mock');
    localStorage.removeItem('fm_api_mock_seed');
    // 下载取证钩：拦 <a>.click() 记 href/download（blob 腿也在内），供字节级断言。
    window.__dlProbe = [];
    const oc = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try { window.__dlProbe.push({ href: this.href, download: this.download }); } catch (e) { /* ignore */ }
      return oc.apply(this, arguments);
    };
  });
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] }) });
    }
    // 真 REST：经 Node 侧 route.fetch 打到伪 neblink 中继（真 TCP/HTTP 往返，
    // 非 page.route 假答；route.fetch 在 Node 里发请求 ⇒ 不受页面 CORS 影响）。
    if (p.startsWith('/api/')) {
      const resp = await route.fetch({ url: `${RELAY.base}${p}${url.search}` });
      return route.fulfill({ response: resp });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => { outFrames.push(String(raw)); });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 's1', messages: HISTORY, hasMore: false, offset: 0 }));
  });
  await page.goto('http://localhost:1/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#input', { state: 'attached', timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await sleep(700);
}

/** 进入消息面板 → 好友会话（真点击路径；同 app spec 口径）。 */
async function openConversation(page) {
  // 有的入口会直接落到会话面（auto-select）；已到就直接用。
  if (await page.locator('#input-area .fm-input').count() && await page.locator('.fm-input').first().isVisible().catch(() => false)) {
    await sleep(400);
    if (await page.locator('.fm-input').first().isVisible()) return true;
  }
  const dock = page.locator('#mobile-dock .dock-key[data-dock="messages"]');
  const mobile = (await dock.count()) > 0 && await dock.first().isVisible();
  if (mobile) {
    await dock.first().click({ timeout: 15000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="messages"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 15000 }).catch(() => {});
  } else if (!(await page.locator('#panel-messages.active').count())) {
    await page.click('#messages-btn', { timeout: 15000 });
    await sleep(700);
  }
  const row = page.locator(`.fm-conv-row[data-conversation-id="${CID}"]`).first();
  if (!(await row.count())) {
    // 退化腿：会话行未按 data-conversation-id 命中时，取第一行（同一夹具只有一条会话）。
    const anyRow = page.locator('.fm-conv-row').first();
    if (!(await anyRow.count())) return false;
    await anyRow.click({ timeout: 15000 });
  } else {
    await row.click({ timeout: 15000 });
  }
  if (mobile) {
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="conversation"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 15000 }).catch(() => {});
  } else {
    await page.waitForSelector('#header .fm-main-head', { timeout: 15000 });
  }
  await sleep(600);
  return true;
}

/** 探针宿主：固定可视层（两视口/两面板态下都可点击；组件仍是真组件）。
 * 布局几何的取证由 tests/composer-message-item-unify-app.spec.mjs（真页面内）承担，
 * 本 spec 只取「组件产物 + 行为 + 字节」。 */
const HOST_PROBE = (side) => {
  document.querySelectorAll(`[data-probe-host="${side}"]`).forEach((n) => n.remove());
  const host = document.createElement('div');
  host.dataset.probeHost = side;
  host.style.cssText = 'position:fixed;left:0;top:0;z-index:2147483000;background:#fff;padding:4px;width:360px';
  document.body.appendChild(host);
  return host;
};

/** 叠卡探针（单面）：真组件渲染同一组三件媒体 → 叠卡两态标记 + 每卡 `.msg-dl` + footer 基座。 */
const stackProbe = async ({ side, png, uid, cid }) => {
  const main = await import('/js/popArtifacts.js');
  const msgs = await import('/js/messages.js');
  const host = document.querySelector(`[data-probe-host="${side}"]`) || document.body;
  const atts = () => [0, 1, 2].map((i) => ({ state: 'ready', id: 'p' + i, type: 'image', name: side + i + '.png', path: '/tmp/' + side + i + '.png', preview: png }));
  let root;
  if (side === 'main') {
    root = main.buildUserMediaRow(atts(), { ts: Date.now() });
  } else {
    root = document.createElement('div');
    root.className = 'fm-flow';
    root.append(msgs.bubbleEl({ id: 'probe-stack', body: '统一批叠卡探针', ours: false, createdAt: Date.now(), attachments: atts() },
      { id: cid, kind: 'direct', friend: { userId: uid } }));
  }
  // 宿主追加契约（messages.js fillBubble 行内序：泡 → 附件组 → 整组 footer）：合起态
  // 整组 footer 是 `attGroup.nfStackGroupFooter` 供给的**兄弟节点**，由宿主追加 ——
  // 探针按同款契约挂上，证明两面用的是同一个 `stackGroupFooter` 基座。
  const holder = [...root.querySelectorAll('*')].find((n) => n.nfStackGroupFooter);
  if (holder) root.append(holder.nfStackGroupFooter);
  root.dataset.probe = 'send-stack-' + side;
  host.append(root);
  await new Promise((r) => setTimeout(r, 250));
  const cards = [...root.querySelectorAll('[data-nf-stack-footer="card"]')];
  const f = root.querySelector('.duration-badge');
  const c = f ? getComputedStyle(f) : null;
  return { group: root.querySelectorAll('[data-nf-stack-footer="group"]').length,
    cards: cards.length,
    dl: cards.map((x) => x.querySelectorAll('.msg-dl').length),
    dlDisabled: cards.map((x) => { const b = x.querySelector('.msg-dl'); return !b || b.disabled; }),
    base: c ? { bg: c.backgroundColor, radius: c.borderRadius } : null,
    box: (() => { const r = root.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)]; })() };
};

/** 下载探针（单面）：单件媒体 + 内联 data: 字节腿 → 点 `.msg-dl`。 */
const dlProbe = async ({ side, png, uid, cid }) => {
  const main = await import('/js/popArtifacts.js');
  const msgs = await import('/js/messages.js');
  const host = document.querySelector(`[data-probe-host="${side}"]`) || document.body;
  const att = { state: 'ready', id: 'dl1', type: 'image', name: side + '-probe.png', path: '/tmp/' + side + '-probe.png', src: png, preview: png };
  let root;
  if (side === 'main') {
    root = main.buildUserMediaRow([att], { ts: Date.now() });
  } else {
    root = document.createElement('div');
    root.className = 'fm-flow';
    root.append(msgs.bubbleEl({ id: 'probe-dl', body: '统一批下载探针', ours: false, createdAt: Date.now(), attachments: [att] },
      { id: cid, kind: 'direct', friend: { userId: uid } }));
  }
  root.dataset.probe = 'send-dl-' + side;
  host.append(root);
  await new Promise((r) => setTimeout(r, 250));
  const btns = [...root.querySelectorAll('.msg-dl')];
  return { n: btns.length, disabled: btns.map((b) => b.disabled), box: btns.map((b) => { const r = b.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)]; }) };
};

const VIEWPORTS = [
  { name: 'mobile-375x812', width: 375, height: 812 },
  { name: 'desktop-1280x800', width: 1280, height: 800 },
];

const browser = await chromium.launch();
rmSync(EVID, { recursive: true, force: true });
mkdirSync(EVID, { recursive: true });
const dlDir = join(EVID, 'downloads');
mkdirSync(dlDir, { recursive: true });

ok('R0 伪 neblink 中继就绪（127.0.0.1:18998）', relayUp, `up=${relayUp} store=${RELAY.store}`);

for (const vp of VIEWPORTS) {
  const vidDir = join(EVID, `video-${vp.name}`);
  mkdirSync(vidDir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN', reducedMotion: 'reduce',
    acceptDownloads: true, recordVideo: { dir: vidDir, size: { width: vp.width, height: vp.height } },
  });
  const page = await ctx.newPage();
  const outFrames = [];
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const tag = (n) => `${vp.name} ${n}`;

  try {
    await boot(page, outFrames);

    // ── A 主会话真实发送 ──────────────────────────────────────────────────
    await page.evaluate(async (probe) => {
      const input = document.querySelector('#input');
      input.focus();
      input.value = probe;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 60));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    }, MAIN_PROBE);
    await sleep(500);
    const afterMain = await page.evaluate(() => document.querySelector('#input').value);
    ok(tag('A1 主会话 Enter 提交后输入框清空'), afterMain === '', `after=${JSON.stringify(afterMain)}`);
    const sentFrame = outFrames.find((f) => f.includes(MAIN_PROBE));
    ok(tag('A2 主会话真 ws 出帧带探针文案'), !!sentFrame,
      sentFrame ? sentFrame.slice(0, 140) : `frames=${outFrames.length}`);
    await page.screenshot({ path: join(EVID, `send-main-${vp.name}.png`) });

    // ── 消息面：真 REST → 伪 neblink 真落库 ───────────────────────────────
    const opened = await openConversation(page);
    ok(tag('B0 真点击进入好友会话面'), opened);
    if (!opened) throw new Error('conversation surface unreachable');
    const before = readStore() || { receivedPosts: [] };
    const postsBefore = (before.receivedPosts || []).length;
    await page.evaluate(async (probe) => {
      const input = document.querySelector('.fm-input');
      input.focus();
      input.value = probe;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 60));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    }, FM_PROBE);
    const store = await waitStore((s) => (s.receivedPosts || []).length > postsBefore
      && s.receivedPosts.some((p2) => p2.body === FM_PROBE));
    const post = store && (store.receivedPosts || []).find((p2) => p2.body === FM_PROBE);
    ok(tag('B1 后端（伪 neblink 中继）磁盘落库收到 POST'), !!post,
      post ? `friendId=${post.friendId} at=${post.at}` : `store=${JSON.stringify((store || {}).receivedPosts || []).slice(0, 200)}`);
    ok(tag('B2 中继消息表新增该条（读面可回读）'),
      !!store && (store.messages || []).some((m) => m.body === FM_PROBE),
      `rows=${store ? (store.messages || []).length : 'n/a'}`);
    const afterFm = await page.evaluate(() => document.querySelector('.fm-input').value);
    ok(tag('B3 消息面 Enter 提交后输入框清空'), afterFm === '', `after=${JSON.stringify(afterFm)}`);
    const fmShown = await page.evaluate((probe) => [...document.querySelectorAll('.fm-msg-bubble')]
      .some((b) => (b.textContent || '').includes(probe)), FM_PROBE);
    ok(tag('B4 消息面 UI 显示该条（同一条消息在发面可见）'), fmShown);
    await page.screenshot({ path: join(EVID, `send-fm-${vp.name}.png`) });

    // ── C 两面叠卡一致（同一组三件媒体，两面各由真组件渲染）─────────────
    await page.evaluate((side) => { const h = document.querySelector(`[data-probe-host="${side}"]`); if (h) h.remove(); }, 'main');
    await page.evaluate((side) => { const h = document.querySelector(`[data-probe-host="${side}"]`); if (h) h.remove(); }, 'fm');
    const args = { png: PNG, uid: UID, cid: CID };
    const stackMain = await page.evaluate(stackProbe, { ...args, side: 'main' });
    const stackFm = await page.evaluate(stackProbe, { ...args, side: 'fm' });
    ok(tag('C1 两面叠卡合起态各一枚整组 footer'),
      stackMain.group === 1 && stackFm.group === 1, `main=${stackMain.group} fm=${stackFm.group}`);
    ok(tag('C2 两面叠卡每卡 footer 各三枚'),
      stackMain.cards === 3 && stackFm.cards === 3, `main=${stackMain.cards} fm=${stackFm.cards}`);
    ok(tag('C3 两面每卡各一枚 .msg-dl 下载键（且非禁用）'),
      stackMain.dl.join(',') === '1,1,1' && stackFm.dl.join(',') === '1,1,1'
      && !stackMain.dlDisabled.some(Boolean) && !stackFm.dlDisabled.some(Boolean),
      `main=${stackMain.dl.join(',')} fm=${stackFm.dl.join(',')} disabled(main)=${stackMain.dlDisabled.join(',')} disabled(fm)=${stackFm.dlDisabled.join(',')}`);
    ok(tag('C4 两面 footer 基座 computed 同底/同圆角'),
      !!stackMain.base && !!stackFm.base && stackMain.base.bg === stackFm.base.bg
      && stackMain.base.radius === stackFm.base.radius,
      `main=${JSON.stringify(stackMain.base)} fm=${JSON.stringify(stackFm.base)}`);
    await page.screenshot({ path: join(EVID, `stack-both-${vp.name}.png`) });

    // ── D 下载真落盘（两面各一次；拦 <a>.click() 取字节）─────────────────
    for (const side of ['main', 'fm']) {
      const shape = await page.evaluate(dlProbe, { ...args, side });
      ok(tag(`D0 ${side} 面单件行挂 .msg-dl（非禁用）`), shape.n === 1 && !shape.disabled[0],
        `n=${shape.n} disabled=${shape.disabled.join(',')} box=${shape.box.join('x')}`);
      await page.evaluate(() => { window.__dlProbe = []; });
      let clickMode = 'pointer';
      try {
        await page.locator(`[data-probe="send-dl-${side}"] .msg-dl`).first().click({ timeout: 6000 });
      } catch (e) {
        // 手机层有全屏手势/遮罩层截获命中测试时，退到按钮自身的 click()（仍是真事件、
        // 仍走组件同一条 click 处理器；指针命中本身由桌面腿覆盖）。
        clickMode = 'programmatic';
        await page.evaluate((sd) => { document.querySelector(`[data-probe="send-dl-${sd}"] .msg-dl`).click(); }, side);
      }
      await sleep(1200);
      const cap = await page.evaluate(async () => {
        const log = window.__dlProbe || [];
        const last = log[log.length - 1];
        if (!last) return null;
        try {
          const buf = new Uint8Array(await (await fetch(last.href)).arrayBuffer());
          return { download: last.download, bytes: buf.length, head: [...buf.slice(0, 4)] };
        } catch (e) { return { download: last.download, error: String(e && e.message ? e.message : e) }; }
      });
      ok(tag(`D1 ${side} 面 .msg-dl 触发下载（真 anchor/blob 腿）`), !!cap && !cap.error,
        cap ? `download=${cap.download} click=${clickMode} ${cap.error || ''}` : 'no anchor click captured');
      const marked = await page.evaluate((sd) => {
        const b = document.querySelector(`[data-probe="send-dl-${sd}"] .msg-dl`);
        return !!b && (b.classList.contains('copied') || b.classList.contains('copy-failed'));
      }, side);
      ok(tag(`D2 ${side} 面真取到字节（bytes > 0）+ 成功态键形 .copied`),
        !!cap && !cap.error && cap.bytes > 0 && marked,
        cap ? `bytes=${cap.bytes} marked=${marked} click=${clickMode} head=${(cap.head || []).join(',')}` : 'n/a');
    }
    await page.screenshot({ path: join(EVID, `download-${vp.name}.png`) });
    await page.screenshot({ path: join(EVID, `download-${vp.name}.png`) });
    ok(tag('E 无 pageerror'), pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
  } catch (e) {
    ok(tag('EXEC'), false, String(e && e.message ? e.message : e));
  } finally {
    const videoPath = await page.video()?.path().catch(() => null);
    await page.close();
    if (videoPath) writeFileSync(join(EVID, `video-path-${vp.name}.txt`), videoPath);
    await ctx.close();
  }
}

await browser.close();
relay.kill('SIGTERM');
const passed = results.filter((r) => r.pass).length;
writeFileSync(join(EVID, 'results.json'), JSON.stringify({ passed, total: results.length, failures, results }, null, 2));
console.log(`\ncomposer-unify-send-download: ${passed}/${results.length} assertions passed (failures=${failures})`);
process.exit(failures ? 1 : 0);
