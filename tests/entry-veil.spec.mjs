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
    // 品牌图标缺字形批（2026-11-02）：字标由「两张 PNG 双档」改为**内联 SVG
    // 单档**（老契约在实跑实例上 404 ⇒ 蓝底问号破图，见
    // evidence/20261102_bootcard-brandicon__* §1）。新判据 = SVG 在场 +
    // naturalWidth 类「已加载」判据不再适用，改判：svg 可见 / 无 <img> 残留 /
    // 几何 96×24 / 无硬编码色值（currentColor 随 --nfc-heading）。
    const lockSvg = page.locator('.nf-entry-card svg.nfc-lockup');
    const lockInfo = await lockSvg.evaluate(el => {
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height), html: el.outerHTML, label: el.getAttribute('aria-label') };
    }).catch(() => null);
    ok('V1 家族字标在场（内联 SVG，aria-label=STAND）',
      !!lockInfo && lockInfo.label === 'STAND' && (await lockSvg.isVisible()), JSON.stringify(lockInfo && { w: lockInfo.w, h: lockInfo.h, label: lockInfo.label }));
    ok('V1 字标零网络依赖（卡内不再有 lockup <img>）',
      (await page.locator('.nf-entry-card img.nfc-lockup').count()) === 0);
    ok('V1 字标几何 96×24（家族 token 档）',
      !!lockInfo && lockInfo.w === 96 && lockInfo.h === 24, lockInfo ? `${lockInfo.w}x${lockInfo.h}` : 'missing');
    ok('V1 字标零硬编码色值（fill=currentColor）',
      !!lockInfo && !/#[0-9a-fA-F]{3,8}/.test(lockInfo.html) && lockInfo.html.includes('currentColor'));
    const titleSt = await page.locator('.nf-entry-title').evaluate(el => getComputedStyle(el).fontSize + '/' + getComputedStyle(el).fontWeight);
    ok('V1 家族标题档（21px/600）', titleSt === '21px/600', titleSt);
    // ── V6 三态视觉 + 加载动效（作者 2026-11-02 验收项③④）──────────────
    // 真实进度窗里天然存在的态 = done（css/ui/host）+ active（data）；
    // pending 态在同一刻不存在（活动项就是由 pending 翻上来的），故三态在
    // **探针**上一次性测全（挂同一套类与 data-state，零副作用、可重复）。
    const triProbe = await page.evaluate(() => {
      const host = document.querySelector('.nf-entry-card .nf-entry-steps-probe')
        || document.querySelector('.nf-entry-items');
      const wrap = document.createElement('div');
      wrap.className = 'nf-entry-steps-probe';
      wrap.innerHTML = ['done', 'active', 'pending']
        .map(st => `<div class="nf-entry-item nfc-step" data-state="${st}"><span class="nf-entry-dot"></span><span class="nf-entry-label">${st}</span></div>`)
        .join('');
      host.appendChild(wrap);
      const out = {};
      for (const st of ['done', 'active', 'pending']) {
        const row = wrap.querySelector(`[data-state="${st}"]`);
        const dot = row.querySelector('.nf-entry-dot');
        const ds = getComputedStyle(dot);
        const after = getComputedStyle(dot, '::after');
        out[st] = {
          rowColor: getComputedStyle(row).color,
          borderTop: ds.borderTopColor,
          borderRight: ds.borderRightColor,
          anim: ds.animationName,
          afterContent: after.content,
          afterBg: after.backgroundColor,
          afterBorderRightW: after.borderRightWidth,
          afterTransform: after.transform,
        };
      }
      wrap.remove();
      return out;
    });
    // 三态互不相同（几何 + 墨色 + 动效三通道各自分层）
    ok('V6 三态环几何互异（done 实环 / active 断口弧 / pending 空环）',
      triProbe.done.borderTop !== triProbe.active.borderTop
      && triProbe.active.borderTop !== triProbe.pending.borderTop,
      JSON.stringify({ d: triProbe.done.borderTop, a: triProbe.active.borderTop, p: triProbe.pending.borderTop }));
    ok('V6 进行中真实动效（animation = nf-boot-spin，非静帧）',
      triProbe.active.anim === 'nf-boot-spin' && triProbe.done.anim === 'none' && triProbe.pending.anim === 'none',
      JSON.stringify({ a: triProbe.active.anim, d: triProbe.done.anim, p: triProbe.pending.anim }));
    ok('V6 完成项 = 纯 CSS 几何勾（无字形 content，2px 描边）',
      triProbe.done.afterContent === '""' && triProbe.done.afterBorderRightW === '2px',
      `content=${triProbe.done.afterContent} bw=${triProbe.done.afterBorderRightW} tf=${triProbe.done.afterTransform}`);
    ok('V6 失败项 = 纯 CSS 几何叉（::before/::after 双杆）',
      triProbe.pending.afterContent === 'none',
      `pending content=${triProbe.pending.afterContent}`);
    // 三态字色分层：以**卡面 bg 的对比度距离**为尺（淡 → 中 → 亮），
    // 不假定主题明暗（浅档 row 越亮越淡、暗档越暗越淡，同一把尺两种都成立）。
    const lum = c => { const m = String(c).match(/[\d.]+/g); return m ? (+m[0]) * 0.299 + (+m[1]) * 0.587 + (+m[2]) * 0.114 : -1; };
    const cardBg = await page.locator('.nf-entry-card').evaluate(el => getComputedStyle(el).backgroundColor);
    const dist = c => Math.abs(lum(c) - lum(cardBg));
    ok('V6 三态字色分层（pending 淡 < done 中 < active 亮，对比度单调）',
      dist(triProbe.pending.rowColor) < dist(triProbe.done.rowColor)
      && dist(triProbe.done.rowColor) < dist(triProbe.active.rowColor),
      `bg=${cardBg} Δ ${dist(triProbe.pending.rowColor).toFixed(1)} < ${dist(triProbe.done.rowColor).toFixed(1)} < ${dist(triProbe.active.rowColor).toFixed(1)}`);
    // 动效「在跑」而非「声明在」：真实活动行两帧 transform 必须变化
    const spinA = await page.locator('.nf-entry-card .nf-entry-item[data-state="active"] .nf-entry-dot')
      .evaluate(el => getComputedStyle(el).transform).catch(() => '');
    await sleep(160);
    const spinB = await page.locator('.nf-entry-card .nf-entry-item[data-state="active"] .nf-entry-dot')
      .evaluate(el => getComputedStyle(el).transform).catch(() => '');
    ok('V6 动效实测在跑（两帧 transform 变化）', spinA !== spinB, `${spinA} → ${spinB}`);
    ok('V6 卡内零图片依赖（字标与状态点均无 <img>/emoji）',
      (await page.locator('.nf-entry-card img').count()) === 0);
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
    // 新契约：单档内联 SVG，主题切换靠 currentColor 继承 --nfc-heading
    // （不再是双档显隐 —— 那条腿正是 production 404 的来源）。
    const darkLock = await dark.locator('.nf-entry-card svg.nfc-lockup').evaluate(el => ({
      vis: !!el.getBoundingClientRect().width,
      color: getComputedStyle(el).color,
    })).catch(() => null);
    ok('V5 暗字标随主题换墨（单档 currentColor ⇒ #e6edf3）',
      !!darkLock && darkLock.vis && darkLock.color === 'rgb(230, 237, 243)', JSON.stringify(darkLock));
    await dark.screenshot({ path: join(SHOTS, 'entry-progress-dark.png') });
    await darkCtx.close();
  }
} finally {
  await browser.close().catch(() => {});
  server.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
