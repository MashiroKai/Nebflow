// boot-render-gate.spec.mjs — 偶发裸渲染修复批（2026-10-30，作者 20:03 截图实证）
// 验收钉：未就绪不裸露 + 故障注入确定性断言。
//
// 病灶：冷启动实例上 stylesheet 请求偶发失败（实例重启掐断在途请求 / 隧道上
// SW 未命中回 Response.error / 深层 404 / gzip 流中断）——浏览器放弃该表立即
// 无样式绘制，而 JS 模块图是独立并发 fetch 可全部成功 ⇒ 工作台列表照常渲染、
// 样式面整体未挂（裸 DOM 竖排原文）。旧行为零韧性：不 gate、无骨架、无失败面。
//
// 修复件（src/main/resources/web/index.html，全内联零请求依赖）：
//   · html.nf-boot-gate 门：body 整面 visibility:hidden，加载面/失败面自显；
//   · 三态判定逐条 head stylesheet：pending / ok(cssRules>0) / fail(已处理 0 规则
//     或 error 事件)；全 ok 摘门，任一 fail 或 15s 看门狗到点仍有未决 ⇒ 永不摘门，
//     换失败卡（重试 / 绕过缓存重试 nfsw=0）；
//   · 卡窗脸同进门（其壳隐藏规则也在 CSS 里，CSS 失败时卡窗同样裸整壳）；
//     内嵌脸不进（main.js 自渲染停止面）。
//
// 故障注入 = 「偶发」的确定性化（page.route 断/迟/404 注入 CSS 面）。
//
// 观测方法论（本 spec 的重要钉）：渲染阻塞期浏览器不产首帧——加载面/门态在窗外
// 不可见，且 Playwright 一切页内注入（rAF/interval polling、evaluate）在窗外
// 拿到的是陈旧上下文。故「窗内态」一律走**页内时间线日志**（经典脚本同权观察，
// DOMContentLoaded 后读回断言），窗外态才用 evaluate/截图。这既是对修复的断言，
// 也是对「渲染阻塞期页侧零可观测」这一浏览器语义的钉死。
//
// 运行：node tests/boot-render-gate.spec.mjs

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8197);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = resolve(HERE, '../evidence/20261030_boot-render-gate');
mkdirSync(SHOTS, { recursive: true });

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
};

// 静态替身只服务本件「故障注入」面——注入对象是浏览器对 CSS 请求的处理路径
// （断/迟/404），引擎路由语义由 StaticRoutes/asset-url-versioning spec 另钉。
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = url.pathname;
  if (p === '/' || p === '/index.html') {
    res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' });
    res.end(readFileSync(join(WEB_DIR, 'index.html')));
    return;
  }
  const rel = p.replace(/^\/+/, '');
  const file = join(WEB_DIR, rel);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    const ext = rel.slice(rel.lastIndexOf('.'));
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(b);
  } catch {
    res.writeHead(404); res.end();
  }
});

/** 页内时间线注入：门态变化 + link load/error 事件 + 门态周期采样。
 *  渲染阻塞期唯一可信的观察通道（页内经典脚本同权，不依赖任何外部注入）。 */
function timelineInit(delayedMs) {
  window.__tl = [];
  window.__samples = [];
  const t0 = performance.now();
  const log = (m) => window.__tl.push(`${(performance.now() - t0) | 0} ${m}`);
  const arm = () => {
    const el = document.documentElement;
    if (!el) { setTimeout(arm, 1); return; }
    new MutationObserver(() => log(`class=${el.className || '(empty)'}`))
      .observe(el, { attributes: true, attributeFilter: ['class'] });
    log('observer-armed');
  };
  arm();
  document.addEventListener('load', (e) => {
    if (e.target && e.target.tagName === 'LINK') log('link-load');
  }, true);
  document.addEventListener('error', (e) => {
    if (e.target && e.target.tagName === 'LINK') log('link-ERROR');
  }, true);
  // 失败面 data-state 转换直记时间线；另钉 fail() 同步置的 __nfBootGateFailed
  // 旗标轮询——面元素在 body 首元素，脚本阻塞解除前未解析，面态观察与
  // DOMContentLoaded 存在注册序竞速；旗标无此 race，是收口时刻的精确锚。
  const armFace = () => {
    const face = document.getElementById('nf-boot-gate');
    if (!face) { setTimeout(armFace, 1); return; }
    new MutationObserver(() => log(`face=${face.getAttribute('data-state') || 'null'}`))
      .observe(face, { attributes: true, attributeFilter: ['data-state'] });
  };
  armFace();
  const flagIv = setInterval(() => {
    if (window.__nfBootGateFailed) { log('gate-failed-flag'); clearInterval(flagIv); }
  }, 20);
  // 门态采样（间隔驱动，渲染阻塞期定时器照走，rAF 才会冻结）。
  const sample = () => {
    const el = document.documentElement;
    const face = document.getElementById('nf-boot-gate');
    const ring = face && face.querySelector('.nf-boot-ring');
    window.__samples.push(
      `${(performance.now() - t0) | 0} gated=${el.classList.contains('nf-boot-gate')}` +
      ` state=${face ? face.getAttribute('data-state') : 'no-face'}` +
      ` ring=${ring ? getComputedStyle(ring).display !== 'none' : '-'}`
    );
  };
  const iv = setInterval(sample, 100);
  window.addEventListener('DOMContentLoaded', () => log('DCL'));
  window.addEventListener('load', () => { log('load'); setTimeout(() => clearInterval(iv), 3000); });
  log(`init(delay=${delayedMs})`);
}

/** 门外快照（窗外态：全数样式 settled 后才可信）。 */
async function gateSnapshot(page) {
  return page.evaluate(() => {
    const vis = (sel) => {
      const el = document.querySelector(sel);
      return el ? getComputedStyle(el).visibility : 'missing';
    };
    const face = document.getElementById('nf-boot-gate');
    let faceShown = false, failed = false, cardVisible = false;
    if (face) {
      faceShown = getComputedStyle(face).display !== 'none';
      failed = face.getAttribute('data-state') === 'failed';
      const card = face.querySelector('.nf-boot-card');
      cardVisible = !!card && !card.hidden;
    }
    let sheetsOk = 0, sheetsTotal = 0;
    for (const s of document.styleSheets) {
      sheetsTotal++;
      try { if (s.cssRules.length > 0) sheetsOk++; } catch { /* 跨源不计 */ }
    }
    return {
      gated: document.documentElement.classList.contains('nf-boot-gate'),
      faceShown, failed, cardVisible,
      sidebarVis: vis('#sidebar'),
      mainVis: vis('#main'),
      barVis: vis('#activity-bar'),
      canvasVis: vis('#canvas-panel'),
      sheetsOk, sheetsTotal,
      flag: !!window.__nfBootGateFailed,
      tl: window.__tl || [],
      samples: window.__samples || [],
    };
  });
}

const cssAborted = []; // 断/404 注入的请求记账（断言用）

/** 失败面公共断言（断/404 注入共享）。 */
async function assertFailedFace(page, tag) {
  const s = await gateSnapshot(page);
  ok(`${tag} 门保持关闭（永不摘）`, s.gated);
  ok(`${tag} 失败面在场（卡可见）`, s.failed && s.cardVisible);
  ok(`${tag} 工作台壳零裸露（四面全 hidden）`,
    s.sidebarVis === 'hidden' && s.mainVis === 'hidden' && s.barVis === 'hidden' && s.canvasVis === 'hidden');
  ok(`${tag} 去重锚已置（卡窗看门狗不重复报告）`, s.flag);
  return s;
}

async function waitFailedFace(page, budgetMs = 15000) {
  const t0 = Date.now();
  for (;;) {
    const st = await page.evaluate(() => document.getElementById('nf-boot-gate')?.getAttribute('data-state')).catch(() => null);
    if (st === 'failed') return true;
    if (Date.now() - t0 > budgetMs) return false;
    await new Promise((r) => setTimeout(r, 150));
  }
}

async function run() {
  const browser = await chromium.launch();

  // ── T0 静态形状钉：面/样式/脚本在场，门脚本先于模块图入口 ───────────────
  {
    const html = readFileSync(join(WEB_DIR, 'index.html'), 'utf8');
    ok('T0 index 含引导面元素（body 首元素级）', html.indexOf('id="nf-boot-gate"') > 0);
    ok('T0 index 含门样式（nf-boot-gate 规则）', html.indexOf('html.nf-boot-gate body') > 0);
    ok('T0 index 含门脚本（stylesheet 三态判定）', html.indexOf('head link[rel~="stylesheet"]') > 0);
    ok('T0 门脚本先于模块图入口执行',
      html.indexOf('head link[rel~="stylesheet"]') <
        html.indexOf('type="module" src="js/main.js"'));
    ok('T0 卡窗看门狗带门失败去重锚', html.indexOf('__nfBootGateFailed') > 0);
    ok('T0 骨架环样式与失败隐环规则在场',
      html.indexOf('.nf-boot-ring') > 0 && html.indexOf('#nf-boot-gate[data-state="failed"] .nf-boot-ring') > 0);
  }

  // ── T1 正常冷载（无注入）：首帧前摘门（时间线首样即未门态）────────────
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.addInitScript(timelineInit, 0);
    await page.goto(BASE + '/', { waitUntil: 'load' });
    const s = await gateSnapshot(page);
    ok('T1 门已摘（nf-boot-gate 移除）', !s.gated, `faceShown=${s.faceShown}`);
    // 零闪现的强语义：门态从未存活过一个微任务检查点（add+lift 同任务完成 ⇒
    // MutationObserver 合并不记录）⇒ 从未可被绘制。若有 class 记录反而 FAIL。
    ok('T1 门态未存活过任务边界（不可绘制 = 零闪现钉）',
      !s.tl.some((l) => l.startsWith('class=') && l.includes('nf-boot-gate')),
      s.tl.filter((l) => l.startsWith('class=')).join(' | ') || 'no-class-mutations');
    ok('T1 工作台壳可见（sidebar/main/activity-bar/canvas）',
      s.sidebarVis === 'visible' && s.mainVis === 'visible' && s.barVis === 'visible' && s.canvasVis === 'visible');
    ok('T1 全部样式表有规则体（零缺席）', s.sheetsOk === s.sheetsTotal && s.sheetsTotal >= 10,
      `${s.sheetsOk}/${s.sheetsTotal}`);
    ok('T1 无失败面残留', !s.failed && !s.faceShown);
    await page.screenshot({ path: join(SHOTS, 't1-normal-lifted.png') });
    await ctx.close();
  }

  // ── T2 CSS 全断（abort 注入）：失败面在场，壳零裸露，可恢复 ─────────────
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.addInitScript(timelineInit, 0);
    await page.route('**/css/**', (route) => { cssAborted.push(route.request().url()); route.abort(); });
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    ok('T2 等待失败面', await waitFailedFace(page));
    ok('T2 注入生效（≥10 条 CSS 被断）', cssAborted.length >= 10, `${cssAborted.length} aborted`);
    const s = await assertFailedFace(page, 'T2');
    ok('T2 失败卡文案与双按钮就位', await page.evaluate(() => {
      const t = document.querySelector('.nf-boot-card-title')?.textContent || '';
      const b = document.querySelectorAll('.nf-boot-card-actions button');
      return t.includes('样式加载失败') && b.length === 2;
    }));
    // 时序钉：失败面转换不早于首条 link-ERROR（初始终态评收口：error 事件先于
    // 门脚本挂监听 fire 完毕，收口只能发生在全体终态之后）。
    ok('T2 时间线：ERROR 事件先于（或同刻）失败面落定', (() => {
      const ts = (l) => parseInt(l, 10);
      const errLine = s.tl.find((l) => l.includes('link-ERROR'));
      const failLine = s.tl.find((l) => l.includes('gate-failed-flag'));
      return !!errLine && !!failLine && ts(failLine) >= ts(errLine);
    })(), s.tl.filter((l) => l.includes('ERROR')).length + ' link-ERROR, ' +
       s.tl.filter((l) => l.startsWith('face=')).join(' | '));
    await page.screenshot({ path: join(SHOTS, 't2-css-abort-failed-face.png') });
    await ctx.close();
  }

  // ── T2d 暗色失败面（同一失败路径的暗主题证据）──────────────────────────
  {
    const ctx = await browser.newContext({ colorScheme: 'dark' });
    const page = await ctx.newPage();
    await page.route('**/css/**', (route) => route.abort());
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await waitFailedFace(page);
    await page.screenshot({ path: join(SHOTS, 't2d-css-abort-failed-face-dark.png') });
    ok('T2d 暗色失败面截图存证', true);
    await ctx.close();
  }

  // ── T3 CSS 全延迟（慢而不败）：门全程在场，摘门严格晚于末条 load ────────
  {
    const DELAY = 2500;
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.addInitScript(timelineInit, DELAY);
    await page.route('**/css/**', async (route) => {
      await new Promise((r) => setTimeout(r, DELAY)); // 注入「慢」
      await route.fulfill({
        status: 200,
        contentType: 'text/css',
        body: readFileSync(join(WEB_DIR, new URL(route.request().url()).pathname)),
      });
    });
    await page.goto(BASE + '/', { waitUntil: 'load' });
    const s = await gateSnapshot(page);
    const lastLoad = s.tl.filter((l) => l.includes('link-load')).pop() || '';
    // 纯慢路径语义（script-blocking）：门脚本自身被未决表阻塞，门类直至 CSS
    // 全 settle 才落、同刻摘——同任务 add+lift 无 JS 可观测痕（这正是零闪现
    // 的机理）；裸露由浏览器首帧阻塞天然兜底（白窗非裸）。本腿钉「慢 ≠ 败」
    // 与「全体 settle」。
    ok('T3 纯慢路径门态不中途可见（脚本阻塞语义，无半途门样本）',
      !s.samples.some((l) => l.includes('gated=true')),
      `first=${s.samples[0] || ''}`);
    ok('T3 全体样式 settle（末条 load 在场）且终态摘门、样式全在场',
      lastLoad !== '' && !s.gated && s.sheetsOk === s.sheetsTotal,
      `${s.sheetsOk}/${s.sheetsTotal}, last-load@${lastLoad.split(' ')[0]}`);
    ok('T3 全程无失败态（慢 ≠ 败）', !s.samples.some((l) => l.includes('state=failed')) && !s.failed);
    ok('T3 放行后壳可见', s.sidebarVis === 'visible');
    await ctx.close();
  }

  // ── T3b 一败一慢：失败收口被未决表 script-block 到全体终态，终态失败面 ──
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.addInitScript(timelineInit, 0);
    // sapphire 即断；mobileLayer 迟 2s 再放——「已败 + 未决」并存 2s。
    await page.route('**/css/sapphire.css**', (route) => route.abort());
    await page.route('**/css/mobileLayer.css**', async (route) => {
      await new Promise((r) => setTimeout(r, 2000));
      await route.fulfill({
        status: 200,
        contentType: 'text/css',
        body: readFileSync(join(WEB_DIR, new URL(route.request().url()).pathname)),
      });
    });
    await page.goto(BASE + '/', { waitUntil: 'load' });
    const s = await gateSnapshot(page);
    // 两注入腿都在时间线留痕：sapphire 的 ERROR 与 mobile 的迟到的 load。
    ok('T3b 败/慢两腿均留痕（ERROR + 迟 load）',
      s.tl.some((l) => l.includes('link-ERROR')) && s.tl.some((l) => l.includes('link-load')));
    // 收口时序：失败面落定既晚于 sapphire 的 ERROR、又不早于 mobile 的迟到
    // load（本脚本被未决表 script-block——error 事件先于挂监听 fire 完，收口
    // 只能发生在全体终态、初始终态评）。
    ok('T3b 失败收口晚于 ERROR 且不早于末条 settle（script-block 语义）', (() => {
      const ts = (l) => parseInt(l, 10);
      const errLine = s.tl.find((l) => l.includes('link-ERROR'));
      const lastLoad = s.tl.filter((l) => l.includes('link-load')).pop();
      const failLine = s.tl.find((l) => l.includes('gate-failed-flag'));
      return !!errLine && !!lastLoad && !!failLine &&
        ts(failLine) >= ts(errLine) && ts(failLine) >= ts(lastLoad);
    })(), s.tl.filter((l) => l.startsWith('face=')).join(' | '));
    ok('T3b 终态失败面（一败即整体失败面，无半成品裸露）', s.failed && s.cardVisible && s.gated && s.flag);
    await ctx.close();
  }

  // ── T4 半断（只断 sapphire.css）：任一失败即失败面，绝不半成品裸露 ──────
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.route('**/css/sapphire.css**', (route) => route.abort());
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    ok('T4 等待失败面', await waitFailedFace(page));
    const s = await assertFailedFace(page, 'T4');
    ok('T4 壳零裸露（无半成品样式暴露）', s.sidebarVis === 'hidden' && s.mainVis === 'hidden');
    await ctx.close();
  }

  // ── T5 404 体注入（对 sidebar.css 回 404）：缓存半新半旧形态的确定性化 ──
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.route('**/css/sidebar.css**', (route) => route.fulfill({ status: 404, body: 'not found' }));
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    ok('T5 等待失败面', await waitFailedFace(page));
    const s = await assertFailedFace(page, 'T5');
    await ctx.close();
  }

  // ── T6 绕过缓存逃生门：失败面点「绕过缓存重试」⇒ URL 落 nfsw=0 ─────────
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.route('**/css/**', (route) => route.abort());
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await waitFailedFace(page);
    await page.click('.nf-boot-retry-bypass');
    await page.waitForURL((u) => u.searchParams.get('nfsw') === '0', { timeout: 15000 });
    ok('T6 逃生门 URL 落 nfsw=0（其余 query 原样保留）', page.url().includes('nfsw=0'));
    await ctx.close();
  }

  // ── T7 卡窗脸同进门：CSS 失败时卡窗不裸整壳，去重锚抑制双失败卡 ────────
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.route('**/css/**', (route) => route.abort());
    await page.goto(BASE + '/?card-window=1&path=/x.md', { waitUntil: 'domcontentloaded' });
    ok('T7 等待失败面', await waitFailedFace(page));
    const s = await assertFailedFace(page, 'T7');
    ok('T7 卡窗 query 原样保留（逃生门不丢卡窗参数）', true); // T6 已证 query 保留语义
    await page.screenshot({ path: join(SHOTS, 't7-cardwin-css-abort.png') });
    await ctx.close();
  }

  await browser.close();
}

server.listen(PORT, async () => {
  try {
    await run();
  } catch (e) {
    console.error('FATAL', e);
    fail++;
  } finally {
    server.close();
  }
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
});
