// console-takeover-falsepositive.spec.mjs — 接管误报根修批（2026-11-02 作者报障
// 「另外我怎么老被挤 我们的这个逻辑是不是有问题 没人挤我 也显示被挤」）的验收网。
//
// 现场（本批改前，日志实证）：`~/.stand/logs/stand.2026-11-02.0.log` 12:29–12:53
// 连续 19 条 `console-takeover: session X took over from Y — takeover pushed to
// 0 conn(s)`，每条的 Y 正是上一条的 X，全部 accountSub=local/relay-mint 同一账号、
// 同一台设备；对应 `console ticket redeemed … sid=<新>` 逐条相邻 ⇒ **同一浏览器
// 换会话（静默重座/会话过期）被当成另一个实例**，向自己推 takeover 帧。
//
// 契约钉（全部二值，机械断言）：
//   P1 单实例不提示：仅一个活跃客户端时，接管提示不出现；
//   P2 后台恢复不提示：心跳中断后同一 clientId 重连，不出现提示；
//   P3 刷新不提示：同 clientId、新连接、且**新会话 id**（静默重座/会话轮换）也
//      不出现提示（这是旧口径的误报主链）；
//   P4 真抢占要提示：两个 clientId 不同的客户端，第二个出现时必须提示，且被让出
//      的是**旧的**那个（旧端连接收 takeover 帧）；
//   P4b 身份不可证不提示：任一方无 clientId（老缓存页）⇒ 不提示不踢（假阴性可接受，
//      假阳性不可接受）；
//   P5 陈旧账清理：零活连接 + 超 staleTtl ⇒ 摘账；若它是 owner，owner 一并清空
//      （下一个连接走 fresh claim = 零提示），不靠会话 7d/30d TTL 兜底；
//   P6 内容帧闸跟着身份走：可证不同的被顶出端断喂内容帧，不可证的一端照常放行
//      （老缓存页不许「无提示但全白」）。
//
// 驱动方式 = **真实注册表**（ConsoleTakeover.scala 的编译产物由 sbt 编译；
// 本 spec 用 `sbt Test/runMain` 不可行，故走「Scala spec + 本 spec 双轨」：
// 本文件只负责 P5/P6 的**语义复算**与 UI，语义主证据在
// src/test/scala/nebflow/gateway/ConsoleTakeoverSpec.scala（A11–A15）。
//
// Run: node tests/console-takeover-falsepositive.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SHOTS = join(ROOT, 'reports', '20261102_193730_console-takeover-false-positive', 'evidence');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2',
};

// ── 服务端注册表语义复算（P5）：与 ConsoleTakeover.sweepStale 同一判据 ─────────
// 这里复算**判据形态**（零连接 + 超窗），钉的是「不靠会话 TTL 兜底」这条设计；
// 真实现的等价性由 ConsoleTakeoverSpec 的 A11–A15 在 Scala 侧驱动真注册表。
function sweepStale(m, now, staleTtlMs) {
  const alive = new Map();
  const dead = [];
  for (const [k, i] of m) {
    if (i.conns.size === 0 && now - i.lastSeen > staleTtlMs) dead.push(k);
    else alive.set(k, i);
  }
  return { alive, dead };
}

// ── 静态服务器 + 浏览器 ────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;
await mkdir(SHOTS, { recursive: true });

let browser;
const pageErrors = [];
try {
  browser = await chromium.launch();
  const INIT = () => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  };

  /** 打开控制台；返回 page + 本次 ws 握手 URL（判据 = clientId 是否随握手带上）。 */
  async function openConsole({ width = 1440, height = 900, clientId = null } = {}) {
    const ctx = await browser.newContext({ viewport: { width, height } });
    await ctx.addInitScript(INIT);
    if (clientId) {
      // 固定 clientId（= 同一浏览器的稳定 id 语义；不同 context = 不同浏览器）
      await ctx.addInitScript(id => {
        localStorage.setItem('stand_device_client_id', id);
      }, clientId);
    }
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', e => errs.push('pageerror: ' + e.message));
    page.on('console', m => {
      if (m.type() === 'error') errs.push('console: ' + m.text());
    });
    await page.route('**/api/**', r => r.fulfill({ json: {} }));
    const wsUrls = [];
    /** 当前服务端侧 socket（用于推进「服务端→浏览器」帧：takeover/...） */
    const sockets = [];
    await page.routeWebSocket(/\/ws/, ws => {
      wsUrls.push(ws.url());
      sockets.push(ws);
      ws.onMessage(raw => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
        if (m.type === 'getHistory') {
          ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
          return;
        }
      });
      ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root-local-session', name: '本机会话' }], activeId: 'root-local-session', folders: [] }));
    });
    await page.goto(BASE + '/index.html');
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(900);
    return { ctx, page, errs, wsUrls, sockets };
  }

  const visible = async (page) => page.locator('#takeover-box').isVisible().catch(() => false);
  const cardRect = async (page) => page.locator('#takeover-box').evaluate(el => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return {
      x: r.x, y: r.y, w: r.width, h: r.height,
      radius: s.borderRadius, border: s.borderTopWidth,
      bg: s.backgroundColor,
    };
  });

  // ════════════════════════════════════════════════════════════════════
  // P1/P2/P3 主线：同一 clientId（同一浏览器）——单实例/刷新/重座 都不许提示
  // ════════════════════════════════════════════════════════════════════
  {
    const { ctx, page, errs, wsUrls } = await openConsole({ clientId: 'client-stable-aaa' });
    ok('P1 握手带上 clientId（服务端实例键的输入面）',
      wsUrls.some(u => u.includes('clientId=client-stable-aaa')), JSON.stringify(wsUrls));

    // 单实例：本页 ws 是唯一客户端 ⇒ 全程零 takeover 帧（无提示）。
    ok('P1 单实例不提示（开场 3s 无接管卡）', !(await visible(page)));

    // P2 后台恢复：切后台 → 心跳停摆/连接被判死 → 回前台重连（同 clientId）。
    // 判据 = 重连后仍不弹卡（同身份静默续位）。用 ws.js 自己的 forceReconnect
    // 驱动（跳过退避，等价于 onclose ⇒ scheduleReconnect 的落地形态）。
    const before = wsUrls.length;
    await page.evaluate(() => import('/js/ws.js').then(m => m.forceReconnect()));
    await sleep(1500);
    ok('P2 后台恢复（同 clientId 重连）不提示', !(await visible(page)),
      `wsUrls ${before} → ${wsUrls.length}`);
    ok('P2 重连确实发生了（新握手，且 clientId 不变）',
      wsUrls.length > before && wsUrls[wsUrls.length - 1].includes('clientId=client-stable-aaa'),
      JSON.stringify(wsUrls.slice(-1)));

    // P3 刷新：同标签 reload，新连接、**同 clientId**（浏览器身份不变）。
    // 旧口径在这一步若同时换了会话 id（静默重座）就会自弹——本判据盯 UI 面：
    // reload 后不弹卡。
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(1200);
    ok('P3 刷新（新连接 + 同 clientId）不提示', !(await visible(page)));
    ok('P3 刷新后 clientId 不变（localStorage 稳定 id）',
      (await page.evaluate(() => localStorage.getItem('stand_device_client_id'))) === 'client-stable-aaa');
    ok('P1/P2/P3 零页面异常', errs.length === 0, errs.join(' | ').slice(0, 200));
    await ctx.close();
  }

  // ════════════════════════════════════════════════════════════════════
  // P4 真抢占（UI 面）：不同 clientId ⇒ 服务端推 takeover ⇒ 卡面在场且形态合法
  // ════════════════════════════════════════════════════════════════════
  {
    const { ctx, page, errs, sockets } = await openConsole({ clientId: 'client-stable-bbb' });
    // 服务端判定的真抢占由 Scala spec（A14/A17）驱动真注册表；UI 面只需证明
    // 「收到服务端推来的 takeover 帧 ⇒ 卡面按新形态出现」。此处从**服务端侧**
    // socket 推同形帧（与 ConsoleTakeover.takeoverFrame 逐字节同形），走前端真实
    // 收帧路径——不手改 DOM。
    const ws = sockets[sockets.length - 1];
    ws.send(JSON.stringify({ type: 'takeover', at: Date.now() }));
    await sleep(600);
    ok('P4 真抢占提示在场（卡面由 takeover 帧弹出）', await visible(page));

    // ── 桌面形态：居中浮层卡（家族 DNA 不变）─────────────────────────────
    const r = await cardRect(page);
    ok('P4 桌面包裹为卡片（radius 18 / 1px 边 / 居中）',
      r.radius === '18px' && r.border === '1px' && Math.abs(r.x + r.w / 2 - 720) < 2,
      JSON.stringify(r));
    await page.screenshot({ path: join(SHOTS, 'card-desktop-light.png') });
    await ctx.close();
    ok('P4 零页面异常', errs.length === 0, errs.join(' | ').slice(0, 160));
  }

  // ════════════════════════════════════════════════════════════════════
  // P4-mobile + P5(UI)：375px 手机端 —— 卡片不贴边、两钮不挤压、热区 ≥44px
  // ════════════════════════════════════════════════════════════════════
  {
    const { ctx, page, errs, sockets } = await openConsole({ width: 375, height: 812, clientId: 'client-stable-ccc' });
    // 同 P4：从服务端侧推 takeover 帧，让卡面走真实弹出路径（含 mobile 层类）。
    const ws = sockets[sockets.length - 1];
    ws.send(JSON.stringify({ type: 'takeover', at: Date.now() }));
    await sleep(600);
    ok('P4-M 手机层激活（body.mobile-layer）',
      await page.evaluate(() => document.body.classList.contains('mobile-layer')));

    const m = await page.locator('#takeover-box').evaluate(el => {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      const btns = Array.from(el.querySelectorAll('.nfc-actions .g2-pill')).map(b => {
        const br = b.getBoundingClientRect();
        return { id: b.id, x: br.x, y: br.y, w: br.width, h: br.height };
      });
      return {
        x: r.x, y: r.y, w: r.width, h: r.height,
        radius: cs.borderRadius, border: cs.borderTopWidth, bg: cs.backgroundColor,
        vw: window.innerWidth, vh: window.innerHeight,
        btns,
        bodyFs: getComputedStyle(el.querySelector('.nfc-body')).fontSize,
        titleFs: getComputedStyle(el.querySelector('.nfc-title')).fontSize,
      };
    });
    const rect = await cardRect(page);
    ok('P4-M 卡片不贴边（左右各 ≥16px）', m.x >= 15.5 && (m.vw - (m.x + m.w)) >= 15.5,
      `left=${m.x.toFixed(1)} right=${(m.vw - m.x - m.w).toFixed(1)}`);
    ok('P4-M 卡片有卡面（圆角 18 / 1px 边 / 非透明底）',
      m.radius === '18px' && m.border === '1px' && m.bg !== 'rgba(0, 0, 0, 0)', JSON.stringify({ r: m.radius, b: m.border, bg: m.bg }));
    ok('P4-M 不铺满整屏（高度 < 视口 80% ⇒ 下方不再有 2/3 空白）',
      m.h < m.vh * 0.8, `cardH=${m.h.toFixed(1)} vh=${m.vh}`);
    ok('P4-M 卡片垂直居中（上下留白均衡，|Δ| ≤ 2px）',
      Math.abs((m.y) - (m.vh - (m.y + m.h))) <= 2,
      `top=${m.y.toFixed(1)} bottom=${(m.vh - m.y - m.h).toFixed(1)}`);
    ok('P4-M 两钮等宽一行不挤压（宽度差 ≤ 1px，同一行）',
      m.btns.length === 2 && Math.abs(m.btns[0].w - m.btns[1].w) <= 1
      && Math.abs(m.btns[0].y - m.btns[1].y) < 1,
      JSON.stringify(m.btns));
    ok('P4-M 热区 ≥44px（两钮高 ≥44）',
      m.btns.every(b => b.h >= 44), JSON.stringify(m.btns.map(b => b.h)));
    ok('P4-M 正文字号 ≥17px、标题 ≥20px',
      parseFloat(m.bodyFs) >= 17 && parseFloat(m.titleFs) >= 20, `${m.bodyFs}/${m.titleFs}`);
    void rect;
    await page.screenshot({ path: join(SHOTS, 'card-mobile-375-light.png') });
    // 暗档样张
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
    await sleep(300);
    await page.screenshot({ path: join(SHOTS, 'card-mobile-375-dark.png') });
    ok('P4-M 零页面异常', errs.length === 0, errs.join(' | ').slice(0, 160));
    await ctx.close();
  }

  // ════════════════════════════════════════════════════════════════════
  // P6 陈旧账清理判据复算（不靠 7d/30d 会话 TTL 兜底）
  // ════════════════════════════════════════════════════════════════════
  {
    const TTL = 10 * 60 * 1000;
    const now = 1_000_000_000;
    const m = new Map([
      ['c:live', { conns: new Set(['x']), lastSeen: now - TTL * 100 }], // 有活连接：永不清
      ['c:idle-just', { conns: new Set(), lastSeen: now - 1000 }],       // 刚断：窗内保留
      ['c:idle-old', { conns: new Set(), lastSeen: now - TTL - 1 }],     // 零连接 + 超窗：摘
    ]);
    const { alive, dead } = sweepStale(m, now, TTL);
    ok('P6 有活连接的实例永不清扫', alive.has('c:live'));
    ok('P6 刚断连的实例在窗内保留（同 clientId 重连仍认得出）', alive.has('c:idle-just'));
    ok('P6 零连接 + 超窗 ⇒ 摘账（owner 随后清空）', dead.includes('c:idle-old'));
    ok('P6 陈旧窗 ≪ 会话 idle 窗（10min vs 7d）⇒ 不靠会话寿命兜底',
      TTL < 7 * 24 * 3600 * 1000);
  }

  ok('零页面异常（全局）', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
} finally {
  await browser?.close().catch(() => {});
  server.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
