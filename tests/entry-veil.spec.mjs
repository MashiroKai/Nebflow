// entry-veil.spec.mjs — 入场加载卡验收（接管/入场同族批 2026-11-01 作者令③）。
//
// 作者令③：进控制台的加载期，用同族卡片覆盖全屏显示进度——目标体验：进入时一切
// 已就绪，而不是进去后看到各框架一点点蹦出来。实现 = 入场前预加载 + 资源就绪门槛，
// 卡片进度走**真实加载项**（不是假进度条），就绪才揭幕。
//
// 真实加载项四件（信号源 = index.html 门脚本 / ws.js / main.js，单缝
// window.__nfEntry.mark）：
//   css  样式表    —— 门脚本三态判定（全 ok）      [index.html]
//   ui   界面模块  —— main.js 模块图+壳装配完成     [main.js]
//   host 连接主机  —— ws 首次握手成功               [ws.js onopen]
//   data 同步数据  —— 首帧 configData 落地          [main.js onMessage]
//
// 契约钉（本 spec）：
//   V1  入场卡真实在场：四项清单逐字（中文 locale）+ 活动项标题跟随（「正在检查主机…」
//       = 入场族同名位）；
//   V2  进度只由真实信号驱动：stub 慢推 configData ⇒ data 项 pending（无假完成），
//       帧落地 ⇒ 四项全 done ⇒ 门摘 + 壳可见（揭幕）；
//   V3  fail 腿：握手被拒 ⇒ host 项 fail + 立即揭幕（让位给登录/重连面，不扣入口）；
//   V4  卡窗脸不进延长门（CSS 窗即门，行为与接管批之前逐字一致）；
//   V5  双主题样张（亮/暗）入场进度卡存证。
//
// Run: node tests/entry-veil.spec.mjs（自包含：静态服务器随机隔离端口，finally 必关）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
// 卡片家族批（2026-11-01 作者 16:05 令）起截图随本 spec 落本批证据目录
// （原接管批目录 evidence/20261101_103300_takeover-entry__* 定格为历史，不复写）。
const SHOTS = join(ROOT, 'evidence', '20261101_cards-family__delegate-nebflow-67f23d42', 'after');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩 WS：configData 推送可**门控**（V2 观察窗：spec 翻旗才推，负载免疫、确定性）；
//    其余 boot 帧照发 ────────────────────────────────────────────────────────────
// 说明：曾有 configDelayMs 定时版——并行跑批时页主线程被饿，evaluate 首样可迟于
// 1.5s 窗全窗错过（假阴性）。改 hold/release 旗：观察窗由 spec 显式开合。
const stub = { holdConfig: false, releaseConfig: false };
function wireUp(ws) {
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root-local-session', name: '本机会话' }], activeId: 'root-local-session', folders: [] }));
  const pushConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  if (!stub.holdConfig) { pushConfig(); return; }
  const iv = setInterval(() => {
    if (stub.releaseConfig) { clearInterval(iv); pushConfig(); }
  }, 60);
}

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

const browser = await chromium.launch();
const INIT = () => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_input_history', '[]');
};
const gateState = (page) => page.evaluate(() => {
  const f = document.getElementById('nf-boot-gate');
  const active = f ? f.querySelector('.nf-entry-item[data-state="active"]') : null;
  return {
    gated: document.documentElement.classList.contains('nf-boot-gate'),
    reveal: document.documentElement.classList.contains('nf-boot-reveal'),
    entry: !!f && f.getAttribute('data-entry') === 'on',
    title: f ? (f.querySelector('.nf-entry-title') || {}).textContent || '' : '',
    failed: !!f && f.getAttribute('data-state') === 'failed',
    states: window.__nfEntryState || {},
    activeItem: active ? active.getAttribute('data-item') : '',
    labels: Array.from((f || document).querySelectorAll('.nf-entry-label')).map(e => e.textContent),
  };
});
async function waitGateOff(page, budgetMs = 12000) {
  const t0 = Date.now();
  while (Date.now() - t0 < budgetMs) {
    const st = await gateState(page).catch(() => null);
    if (st && !st.gated) return true;
    await sleep(100);
  }
  return false;
}

try {
  // ── V1+V2: 入场卡真实在场 + 进度只由真实信号驱动（慢推 configData 观察窗）──
  {
    stub.holdConfig = true; stub.releaseConfig = false; // V2 观察窗开：configData 扣住不发
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript(INIT);
    const page = await ctx.newPage();
    await page.route('**/api/**', r => r.fulfill({ json: {} }));
    await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
    await page.goto(BASE + '/index.html');
    // 等入场卡就位（四项清单在场）
    await page.waitForSelector('#nf-boot-gate .nf-entry-item', { timeout: 15000 });
    // 等到观察窗的精确形态：host 已 done（ws 已开）而 data 仍 pending（configData
    // 迟 1500ms）——活动项 = data、标题 =「正在同步数据…」。
    await (async () => {
      const t0 = Date.now();
      for (;;) {
        const st = await gateState(page).catch(() => null);
        if (st && st.states.host === 'done' && st.states.data !== 'done' && st.activeItem === 'data') return;
        if (Date.now() - t0 > 10000) return; // 超时不硬等：后续断言如实判
        await sleep(80);
      }
    })();
    const mid = await gateState(page);
    ok('V1 入场卡在场（data-entry=on）', mid.entry);
    ok('V1 四项清单逐字（中文 locale：样式表/界面模块/连接主机/同步数据）',
      JSON.stringify(mid.labels) === JSON.stringify(['样式表', '界面模块', '连接主机', '同步数据']),
      JSON.stringify(mid.labels));
    ok('V1 活动项标题跟随（正在同步数据…）', mid.title === '正在同步数据…', mid.title);
    ok('V2 真实进度：host 已 done（ws 已开）',
      mid.states.host === 'done', JSON.stringify(mid.states));
    ok('V2 真实进度：data 仍 pending（configData 未落地 = 无假完成）',
      mid.states.data !== 'done', JSON.stringify(mid.states));
    ok('V2 活动项 = data（同步数据）', mid.activeItem === 'data', mid.activeItem);
    // 卡片家族批（作者 16:05 令）重钉：入场卡 = .nfc 家族实色卡 + 官方字标 +
    // 家族字阶（白底/1px 边/radius 18/宽 384/标题 21px/600，官网 auth 域 DNA）。
    const fam = await page.locator('.nf-entry-card').evaluate(el => {
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, radius: s.borderRadius, width: s.width, border: `${s.borderWidth} ${s.borderColor}` };
    });
    ok('V1 家族卡材质（bg #fff 实色 + radius 18 + width 384 + 边 #e5e7eb）',
      fam.bg === 'rgb(255, 255, 255)' && fam.radius === '18px' && fam.width === '384px'
      && fam.border === '1px rgb(229, 231, 235)', JSON.stringify(fam));
    ok('V1 家族字标在场（官方 lockup，alt=STAND）',
      await page.locator('.nf-entry-card .nfc-lockup-light').isVisible()
      && (await page.locator('.nf-entry-card .nfc-lockup-light').getAttribute('alt')) === 'STAND');
    const titleSt = await page.locator('.nf-entry-title').evaluate(el => getComputedStyle(el).fontSize + '/' + getComputedStyle(el).fontWeight);
    ok('V1 家族标题档（21px/600）', titleSt === '21px/600', titleSt);
    await page.screenshot({ path: join(SHOTS, 'entry-progress-light.png') });
    stub.releaseConfig = true; // 观察窗收：帧落地 ⇒ 四项全 done ⇒ 揭幕（门摘 + 壳可见）
    await page.waitForSelector('#input', { state: 'visible', timeout: 15000 });
    ok('V2 就绪揭幕（门摘）', await waitGateOff(page, 6000));
    const fin = await gateState(page);
    ok('V2 四项全 done（css/ui/host/data 真实信号齐）',
      fin.states.css === 'done' && fin.states.ui === 'done' && fin.states.host === 'done' && fin.states.data === 'done',
      JSON.stringify(fin.states));
    ok('V2 无失败面残留', !fin.failed);
    await ctx.close();
  }

  // ── V3: fail 腿（握手被拒）⇒ host fail + 立即揭幕，绝不扣入口 ───────────────
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript(INIT);
    const page = await ctx.newPage();
    await page.route('**/api/**', r => r.fulfill({ json: {} }));
    // 无 WS 桩：静态替身不处理 upgrade ⇒ 握手被拒
    await page.goto(BASE + '/index.html');
    ok('V3 握手被拒 ⇒ 门摘（fail 腿揭幕）', await waitGateOff(page, 12000));
    const fin = await gateState(page);
    ok('V3 host 项标 fail（真实状态，不假 done）', fin.states.host === 'fail', JSON.stringify(fin.states));
    ok('V3 无失败面残留（CSS 全 ok）', !fin.failed);
    await ctx.close();
  }

  // ── V4: 卡窗脸不进延长门（CSS 窗即门，行为逐字保留）─────────────────────────
  {
    stub.holdConfig = false; stub.releaseConfig = false;
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript(INIT);
    const page = await ctx.newPage();
    await page.route('**/api/**', r => r.fulfill({ json: {} }));
    await page.goto(BASE + '/?card-window=1&path=/x.md');
    ok('V4 卡窗脸门摘（CSS 窗即门，不等入场项）', await waitGateOff(page, 12000));
    const fin = await gateState(page);
    ok('V4 卡窗脸未显入场卡（data-entry 未置）', !fin.entry, JSON.stringify({ entry: fin.entry, gated: fin.gated }));
    await ctx.close();
  }

  // ── V5: 双主题入场进度卡样张（暗档）─────────────────────────────────────────
  {
    stub.holdConfig = true; stub.releaseConfig = false; // 暗档样张同走真实观察窗
    const darkCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
    await darkCtx.addInitScript(INIT);
    const dark = await darkCtx.newPage();
    await dark.route('**/api/**', r => r.fulfill({ json: {} }));
    await dark.routeWebSocket(/\/ws/, ws => wireUp(ws));
    await dark.goto(BASE + '/index.html');
    await dark.waitForSelector('#nf-boot-gate .nf-entry-item', { timeout: 15000 });
    // 等观察窗形态（host done、data 被 hold 扣住 ⇒ 活动项 = data 稳定在场）
    await (async () => {
      const t0 = Date.now();
      for (;;) {
        const s = await gateState(dark).catch(() => null);
        if (s && s.states.host === 'done' && s.activeItem === 'data') return;
        if (Date.now() - t0 > 10000) return;
        await sleep(80);
      }
    })();
    const st = await gateState(dark);
    ok('V5 暗档入场卡在场（四项清单）', st.entry && st.labels.length === 4, JSON.stringify(st.labels));
    // 卡片家族批重钉：暗档 = 官网 auth 域 dark 值 + 暗字标切换。
    const darkFam = await dark.locator('.nf-entry-card').evaluate(el => {
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, border: `${s.borderWidth} ${s.borderColor}` };
    });
    ok('V5 家族暗档（bg #161b22 + 边 #2d333b）',
      darkFam.bg === 'rgb(22, 27, 34)' && darkFam.border === '1px rgb(45, 51, 59)', JSON.stringify(darkFam));
    ok('V5 暗字标切换（dark lockup 可见/亮档隐）',
      await dark.locator('.nf-entry-card .nfc-lockup-dark').isVisible()
      && !(await dark.locator('.nf-entry-card .nfc-lockup-light').isVisible()));
    await dark.screenshot({ path: join(SHOTS, 'entry-progress-dark.png') });
    await darkCtx.close();
  }
} finally {
  await browser.close().catch(() => {});
  server.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
