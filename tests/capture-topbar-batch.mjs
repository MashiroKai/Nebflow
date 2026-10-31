// capture-topbar-batch.mjs — 手机端顶栏批（2026-10-31 作者令五件）证据脚本。
// ① canvas 页头胶囊退役 ② 标签条上移就位 + 横向边缘渐隐（左右特写）
// ③ 桌面胶囊环竖环回退（考古锚 fd9134d54 父版）④ 手机堆叠视角展开动画
//   （中段帧取证）⑤ 桌面堆叠帧率读数见 capture-deck-perf.mjs。
// Run: node tests/capture-topbar-batch.mjs
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = resolve(HERE, '../evidence/20261031_topbar-batch');
const PORT = Number(process.env.PORT || 8191);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(join(OUT, 'shots'), { recursive: true });

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  cond ? pass++ : fail++;
};

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const LONG_NAMES = [
  '20261030_background-research-notes.md',
  '20261030_memory-dimension-profile-design.md',
  '20261030_human-readable-presentation.zh-CN.md',
  '20261031_canvas-liquid-glass-batch.md',
  '20261031_deck-performance-readings.md',
  '20261031_mobile-topbar-batch.md',
];
const FILE_CONTENTS = new Map(LONG_NAMES.map((n) => ['/tmp/tb-fixture/' + n, { content: '# ' + n + '\n\nbody\n', itemType: 'markdown' }]));

async function boot({ colorScheme = 'dark', mobile = false } = {}) {
  const viewport = mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 };
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown', fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0 }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

async function openFile(page, absPath) {
  const rec = FILE_CONTENTS.get(absPath);
  await page.evaluate(({ absPath, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title: absPath.split('/').pop(), content, absPath, pinned: true },
    }));
  }, { absPath, content: rec.content, itemType: rec.itemType });
  await sleep(300);
}

/* ════ 腿 A：手机端（暗/亮双主题）①②④ ════ */
for (const scheme of ['dark', 'light']) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme, mobile: true });
  for (const n of LONG_NAMES) await openFile(page, '/tmp/tb-fixture/' + n);
  // 手机端开卡自动弹 canvas 页有 3s 启动抑制窗（boot 恢复不算用户开卡）——
  // 直过抑制窗后以真实用户路径点 dock canvas 键进页。
  await sleep(3200);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await page.waitForSelector('.mobile-page[data-page="canvas"].live', { timeout: 8000 });
  await sleep(400);

  const probes = await page.evaluate(() => {
    const pageEl = document.querySelector('.mobile-page[data-page="canvas"]');
    const head = pageEl.querySelector('.mobile-page-header');
    const title = pageEl.querySelector('.mobile-page-title');
    const strip = document.querySelector('.mf-strip');
    const sr = strip.getBoundingClientRect();
    return {
      hasHeader: !!head, hasTitle: !!title,
      stripTop: +sr.top.toFixed(1), stripLeft: +sr.left.toFixed(1), stripH: +sr.height.toFixed(1),
      stripFade: strip.classList.contains('fade'),
      stripOverflow: strip.scrollWidth > strip.clientWidth + 1,
      stripBarless: getComputedStyle(strip).backgroundImage === 'none' && getComputedStyle(strip).borderBottomWidth === '0px',
      maskImage: getComputedStyle(strip).maskImage || getComputedStyle(strip).webkitMaskImage,
      chipCount: strip.querySelectorAll('.mf-strip-chip').length,
    };
  });
  ok(`[${scheme}] ① canvas 页头/标题胶囊退役`, !probes.hasHeader && !probes.hasTitle);
  ok(`[${scheme}] ② 标签条置顶就位（top ≤ 12px）`, probes.stripTop <= 12, `top=${probes.stripTop}`);
  ok(`[${scheme}] ② 条面退役（无通栏底色/底缘线）`, probes.stripBarless);
  ok(`[${scheme}] ② 溢出门控：内容溢出 + .fade 在位 + 双侧 mask`, probes.stripOverflow && probes.stripFade && /linear-gradient/.test(probes.maskImage), `overflow=${probes.stripOverflow} fade=${probes.stripFade}`);

  // 全顶带截图 + 左右渐隐特写
  await page.screenshot({ path: join(OUT, `shots/m1-strip-top-${scheme}.png`), clip: { x: 0, y: 0, width: 390, height: Math.round(probes.stripTop + probes.stripH + 46) } });
  await page.screenshot({ path: join(OUT, `shots/m2-strip-fade-left-${scheme}.png`), clip: { x: 0, y: Math.max(0, probes.stripTop - 4), width: 130, height: Math.round(probes.stripH + 8) } });
  await page.screenshot({ path: join(OUT, `shots/m3-strip-fade-right-${scheme}.png`), clip: { x: 260, y: Math.max(0, probes.stripTop - 4), width: 130, height: Math.round(probes.stripH + 8) } });

  // ④ 堆叠视角展开动画：开面中段帧（stagger 未播完）+ 播完终态
  await page.click('.mf-cv-stack');
  await sleep(130);                                    // 中段：首张已起、末张未动
  const mid = await page.evaluate(() => {
    const view = document.querySelector('.mf-stack-view');
    const cards = [...view.querySelectorAll('.mf-stack-card')];
    const cs = cards.map((c) => getComputedStyle(c));
    return {
      open: view.classList.contains('open'), hidden: view.hidden,
      firstOp: +cs[0].opacity, lastOp: +cs[cs.length - 1].opacity,
      firstTf: cs[0].transform, lastTf: cs[cs.length - 1].transform,
      n: cards.length,
    };
  });
  ok(`[${scheme}] ④ 堆叠面展开动画（中段 stagger：首卡已现、末卡未现）`,
    mid.open && !mid.hidden && mid.firstOp > 0.05 && mid.lastOp < 0.05 && mid.lastTf !== 'none',
    `firstOp=${mid.firstOp} lastOp=${mid.lastOp} n=${mid.n}`);
  await page.screenshot({ path: join(OUT, `shots/m4-stack-expand-mid-${scheme}.png`) });
  await sleep(500);
  await page.screenshot({ path: join(OUT, `shots/m5-stack-open-${scheme}.png`) });
  const settled = await page.evaluate(() => +getComputedStyle(document.querySelector('.mf-stack-card:last-child')).opacity);
  ok(`[${scheme}] ④ 展开终态全列就位`, settled === 1, `lastOp=${settled}`);
  // 收面回归：✕ 点击后 200ms+ 隐去
  await page.click('.mf-stack-x');
  await sleep(420);
  const closed = await page.evaluate(() => { const v = document.querySelector('.mf-stack-view'); return v.hidden && !v.classList.contains('open'); });
  ok(`[${scheme}] ④ 收面回归隐去`, closed);
  ok(`[${scheme}] 零 JS 错误`, errors.length === 0, errors.join(' | ').slice(0, 300));
  writeFileSync(join(OUT, `probes-mobile-${scheme}.json`), JSON.stringify({ strip: probes, stackMid: mid }, null, 2));
  await ctx.close();
}

/* ════ 腿 B：桌面端 ③ 竖环回退 ════ */
for (const scheme of ['dark', 'light']) {
  const { ctx, page, errors } = await boot({ colorScheme: scheme, mobile: false });
  for (const n of LONG_NAMES.slice(0, 4)) await openFile(page, '/tmp/tb-fixture/' + n);
  await page.waitForSelector('#canvas-panel.open, body.canvas-open', { timeout: 8000 });
  await sleep(400);
  const hdr = await page.$('#canvas-panel .canvas-header');
  const hb = await hdr.boundingBox();
  await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
  await sleep(600);
  const ring = await page.evaluate(() => {
    const root = document.getElementById('canvas-capsule-ring');
    const caps = [...root.querySelectorAll('.cr-capsule')];
    const vis = caps.filter((c) => !c.classList.contains('is-far'));
    // 上下对称取证：缘位相对锚点的 translateY 一正一负
    const ys = vis.map((c) => (c.style.getPropertyValue('--cr-y') || getComputedStyle(c).getPropertyValue('--cr-y')).trim());
    const tinner = getComputedStyle(document.querySelector('#canvas-panel .canvas-title .t-inner')).opacity;
    return { open: root.classList.contains('open'), n: caps.length, vis: vis.length, ys, tinner };
  });
  ok(`[${scheme}] ③ hover 开竖环（环开 + 中心覆层文字让位）`, ring.open && ring.tinner === '0', `open=${ring.open} tinner=${ring.tinner}`);
  ok(`[${scheme}] ③ 上下对称排布（缘位 --cr-y 正负成对）`, /-/.test(ring.ys.join(',')) && !ring.ys.every((y) => y.startsWith('-')), JSON.stringify(ring.ys));
  const panel = await page.$('#canvas-panel');
  const pb = await panel.boundingBox();
  await page.screenshot({ path: join(OUT, `shots/d1-ring-open-${scheme}.png`), clip: { x: 0, y: Math.max(0, pb.y - 60), width: 1440, height: 280 } });
  // 滚轮换选：纵滚一格（hover 区几何 = 开环点 ∪ 标题头 ∪ 缘位卡 ±14px——
  // 缘位卡缘外即区外零响应（不消费不切换），故在标题头盒内派发），游标移动
  // 但激活不变（还原契约：滚动≠切换，点击才切换）
  await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
  await page.evaluate(({ x, y }) => {
    document.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true, clientX: x, clientY: y }));
  }, { x: hb.x + hb.width / 2, y: hb.y + hb.height / 2 });
  await sleep(300);
  const wheel = await page.evaluate(() => {
    const on = document.querySelector('#canvas-tab-bar .canvas-tab.active .canvas-tab-label');
    const center = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center .cr-name');
    return { active: on?.textContent, center: center?.textContent };
  });
  ok(`[${scheme}] ③ 滚轮换选（游标移、点击前激活不变）`, !!wheel.center && wheel.center !== wheel.active, `active=${wheel.active} cursor=${wheel.center}`);
  ok(`[${scheme}] 零 JS 错误`, errors.length === 0, errors.join(' | ').slice(0, 300));
  await ctx.close();
}

console.log(`\ntopbar-batch: ${pass} PASS / ${fail} FAIL`);
await browser.close();
server.close();
process.exit(fail ? 1 : 0);
