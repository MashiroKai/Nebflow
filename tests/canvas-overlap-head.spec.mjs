// canvas-overlap-head.spec.mjs — 「canvas 正文顶部被胶囊遮挡」+「返回原文档钮 ↔ 关签钮
//   重叠」两条报障的回归钉（canvas-overlap-fix 批 2026-11-05）
//
// 作者报障（2026-11-05 17:14 逐字）：「现在插件顶部会有内容遮挡，另外就是文档如果
//   跳转到了其他的文档，返回原文档的按钮，会和关闭文档的按钮重叠。」
//
// 根因（改前本 spec 的读数即验红基线，非推测）：
//   ① 顶部遮挡 —— `#plugins-content` / `#agents-content` 各自写裸顶垫
//      `var(--ds-space-4)` = 16px，而顶行胶囊浮在面板 y10..46 ⇒ 面板首块起笔 y33
//      **整条压进胶囊带**（改前实测遮挡 30px）。缺的是统一安全区 token。
//   ② 两钮重叠 —— 关签钮 #canvas-head-close（body 级 fixed，面板左+12/顶+10，
//      40×40）与返回钮 .canvas-refback-btn（相对 #canvas-slide 的 absolute，
//      写死 top:10 / left:14）落在**同一个左上角** ⇒ 实测交叠 37×36 = 1332px²。
//
// 断言（全部 getBoundingClientRect / computed style 实测，零 mock 布局）：
//   S1 插件面板：正文根 = .canvas-safe-top，顶垫 = --cv-safe-top 解析值
//   S2 插件面板：首个内容块 top ≥ 胶囊 bottom（滚到顶 / 滚回顶两态）
//   S3 agent 面板：同 S1/S2（消费面不止一个 —— 单点修好必须对全体成立）
//   S4 文档：无返回钮时关签钮落位 = 基线读数（本项自身即关签钮独立性的锚）
//   S5 文档：一次跳转后 返回钮 与 关签钮 rect 零交叠 + 返回钮右缘不侵入居中胶囊
//   S6 文档：A→B→C 两次跳转后语义为「回到 B」且落位与首次跳转逐值相同
//   S7 单击返回钮 ⇒ 激活页签回到来源 B
//   S8 窄面板（520 < 540 断点）：返回钮落第二行，与关签钮、标题胶囊均零交叠
//   S9 手机层 375：返回钮真在场（改前挂在 hidden 的 #canvas-panel 里 = 0×0），
//      与左上 ✕、与环标签带零交叠
//
// 运行：node tests/canvas-overlap-head.spec.mjs
// 可选 env：PORT（默认 8291）、SHOTS_DIR（截图目录）

import pw from '/opt/homebrew/lib/node_modules/playwright/index.js';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = pw;
const HERE = dirname(fileURLToPath(import.meta.url));
// WEB_DIR 可覆写：验红实证时指向「改前」的 web 目录（git archive 导出的基线树），
// 同一份断言在修前代码上必须变红 —— 否则本 spec 不具判别力。
const WEB_DIR = resolve(process.env.WEB_DIR || join(HERE, '../src/main/resources/web'));
const PORT = Number(process.env.PORT || 8291);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = process.env.SHOTS_DIR || join(HERE, '../.nebflow/evidence/canvas-overlap-head');
mkdirSync(SHOTS, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}
const R2 = (n) => Math.round(n * 100) / 100;
const ovl = (a, b) => {
  if (!a || !b) return { w: 0, h: 0, area: 0 };
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return { w: R2(Math.max(0, w)), h: R2(Math.max(0, h)), area: R2(Math.max(0, w) * Math.max(0, h)) };
};
const fmt = (b) => (b ? `l${b.left} t${b.top} r${b.right} b${b.bottom}` : 'null');

// ── 夹具（真 markdown 三篇，引用跳转 A→B→C）────────────────────────────
const FIX = '/tmp/nf-canvas-overlap-spec';
mkdirSync(FIX, { recursive: true });
const DOC_A = join(FIX, 'a.md'), DOC_B = join(FIX, 'b.md'), DOC_C = join(FIX, 'c.md');
const BODY = '\n\n正文段落，用于撑出滚动面。\n'.repeat(6);
writeFileSync(DOC_A, `# 来源 A\n\n来源页。${BODY.repeat(6)}`);
writeFileSync(DOC_B, `# 目标 B\n\n从 A 跳进来。${BODY.repeat(6)}`);
writeFileSync(DOC_C, `# 第三 C\n\n从 B 跳进来。${BODY.repeat(6)}`);

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const PLUGINS = Array.from({ length: 14 }, (_, i) => ({
  name: `demo-${String(i + 1).padStart(2, '0')}`, version: '1.0.0', author: 'stand',
  description: `第 ${i + 1} 个演示插件。`, skills: [{ id: 's1', name: 's1', description: 'skill' }],
  mcpServers: [], toolsExtension: [], viewerContributions: { viewers: [] },
  blocked: false, trusted: true, contentChanged: false,
}));
const FILES = new Map([[DOC_A, 'markdown'], [DOC_B, 'markdown'], [DOC_C, 'markdown']]);

const browser = await chromium.launch();

async function boot({ width = 1440, height = 900, mobile = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme: 'dark', locale: 'zh-CN', hasTouch: mobile });
  await ctx.addInitScript(() => {
    for (const k of ['nebflow', 'neblink']) {
      localStorage.setItem(k + '_token', 't'); localStorage.setItem(k + '_locale', 'zh-CN');
      localStorage.setItem(k + '_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    }
    localStorage.setItem('stand_sw', '0');
  });
  await ctx.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/plugins') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ plugins: PLUGINS, rejected: [] }) });
    if (path === '/api/plugins/updates') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sources: [], updatable: 0 }) });
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });
  // 契约面之外**零接触**宿主实例：WS 全本地 mock（只应答三类帧）。
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const t = FILES.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: t ? readFileSync(m.path, 'utf8') : '', itemType: t || 'markdown',
          fileName: (m.path || '').split('/').pop(), size: 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 160)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 20000 });
  await sleep(900);
  return { ctx, page, errors };
}

const box = (page, sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return null;
  const b = el.getBoundingClientRect();
  const r = (n) => Math.round(n * 100) / 100;
  return { left: r(b.left), top: r(b.top), right: r(b.right), bottom: r(b.bottom), w: r(b.width), h: r(b.height) };
}, sel);

/** --cv-* 自定义属性的 computed 值保留 calc() 链 ⇒ 必须在真实消费点取解析值。 */
const tokenPx = (page, name) => page.evaluate((n) => {
  const el = document.createElement('div');
  el.style.paddingTop = `var(${n})`;
  document.body.appendChild(el);
  const v = parseFloat(getComputedStyle(el).paddingTop);
  el.remove();
  return Number.isFinite(v) ? v : null;
}, name);

const openDoc = async (page, p, srcTab) => {
  await page.evaluate(({ path, src }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + path, itemType: 'markdown', title: path.split('/').pop(), absPath: path, refSourceTab: src || undefined },
    }));
  }, { path: p, src: srcTab });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${p}"] .canvas-md-scroll`, { timeout: 15000, state: 'attached' });
  await sleep(800);
};

/* ════════ S1–S3：面板正文顶部安全区（插件 / agent 两个消费面）════════ */
{
  const { ctx, page, errors } = await boot();
  await page.evaluate(() => import('/js/plugins.js').then((m) => m.openPlugins()));
  await page.waitForSelector('#plugins-content .plugins-card', { timeout: 15000, state: 'attached' });
  await sleep(900);

  const safeTop = await tokenPx(page, '--cv-safe-top');
  const capsule = await box(page, '#canvas-panel .canvas-header');
  const p1 = await page.evaluate(() => {
    const el = document.getElementById('plugins-content');
    const first = el.querySelector('.plugins-topbar');
    return { cls: el.className, pad: parseFloat(getComputedStyle(el).paddingTop),
      scrollPad: getComputedStyle(el).scrollPaddingTop, firstTop: first.getBoundingClientRect().top };
  });
  ok('S1 插件面板正文根消费统一安全区类（.canvas-safe-top）', /canvas-safe-top/.test(p1.cls), p1.cls);
  ok('S1b 插件面板顶垫 = --cv-safe-top 解析值', safeTop !== null && p1.pad === safeTop, `pad=${p1.pad} token=${safeTop}`);
  ok('S1c 插件面板 scroll-padding-top 同步（跳转/查找落点不停在胶囊下）',
    parseFloat(p1.scrollPad) === safeTop, `scrollPad=${p1.scrollPad}`);
  ok('S2 插件面板首块 top ≥ 胶囊 bottom（滚到顶态：正文不被胶囊压住）',
    capsule !== null && p1.firstTop >= capsule.bottom,
    `first=${R2(p1.firstTop)} capsuleBottom=${capsule && capsule.bottom}`);

  // 滚到中部再滚回顶：让位是静态档（不随滚动塌），回顶读数逐值不变
  await page.evaluate(() => { const el = document.getElementById('plugins-content'); el.scrollTop = Math.round((el.scrollHeight - el.clientHeight) / 2); });
  await sleep(300);
  await page.screenshot({ path: join(SHOTS, 's2-plugins-mid.png') });
  await page.evaluate(() => { document.getElementById('plugins-content').scrollTop = 0; });
  await sleep(300);
  const back = await box(page, '#plugins-content .plugins-topbar');
  ok('S2b 滚回顶部后首块落位逐值不变（让位为静态档，非滚动期特判）',
    !!back && Math.abs(back.top - p1.firstTop) < 0.5, `back=${back && back.top} first=${R2(p1.firstTop)}`);
  await page.screenshot({ path: join(SHOTS, 's2-plugins-top.png') });

  // 底部同类核查：滚到底时末块不落在底部浮钮带底下
  const bot = await page.evaluate(() => {
    const el = document.getElementById('plugins-content');
    el.scrollTop = el.scrollHeight;
    const last = [...el.querySelectorAll('.plugins-card')].pop();
    const pane = document.getElementById('canvas-content');
    const floats = [...pane.children].filter((n) => n !== el && getComputedStyle(n).position === 'absolute' && n.getBoundingClientRect().height > 0);
    return { lastBottom: last ? last.getBoundingClientRect().bottom : null, paneBottom: pane.getBoundingClientRect().bottom, floats: floats.length };
  });
  ok('S2c 插件面板滚到底：末块在面板底缘之上（该面无底部浮钮，无需底部让位）',
    bot.floats === 0 && bot.lastBottom !== null && bot.lastBottom < bot.paneBottom,
    `lastBottom=${R2(bot.lastBottom)} paneBottom=${R2(bot.paneBottom)} floats=${bot.floats}`);

  // agent 面（同一裸值档 ⇒ 同一修法必须同时成立）
  await page.evaluate(() => import('/js/agentManager.js').then((m) => m.openAgents()));
  await sleep(1200);
  const ag = await page.evaluate(() => {
    const el = document.getElementById('agents-content');
    if (!el) return null;
    const first = el.firstElementChild;
    return { cls: el.className, pad: parseFloat(getComputedStyle(el).paddingTop), firstTop: first ? first.getBoundingClientRect().top : null };
  });
  ok('S3 agent 面板同消费统一安全区（类是唯一来源，非逐面补丁）',
    !!ag && /canvas-safe-top/.test(ag.cls) && ag.pad === safeTop, ag ? `pad=${ag.pad} cls=${ag.cls}` : 'panel missing');
  ok('S3b agent 面板首块 top ≥ 胶囊 bottom',
    !!ag && capsule !== null && ag.firstTop >= capsule.bottom, ag ? `first=${R2(ag.firstTop)}` : '');

  ok('S9a 桌面腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ S4–S7：返回原文档钮 ↔ 关签钮（桌面 1440）══════════════════ */
{
  const { ctx, page, errors } = await boot();
  await openDoc(page, DOC_A);
  const noRef = { close: await box(page, '#canvas-head-close'), ref: await box(page, '#canvas-refback-btn') };
  ok('S4 无跳转：不渲染返回钮（触发条件 = 经引用跳转）', noRef.ref === null, `ref=${fmt(noRef.ref)}`);
  ok('S4b 无跳转：关签钮在场且 40×40（左缘浮钮档）',
    !!noRef.close && noRef.close.w === 40 && noRef.close.h === 40, fmt(noRef.close));
  await page.screenshot({ path: join(SHOTS, 's4-doc-no-refback.png') });

  await openDoc(page, DOC_B, 'file:' + DOC_A);
  const j1 = {
    close: await box(page, '#canvas-head-close'),
    ref: await box(page, '#canvas-refback-btn'),
    capsule: await box(page, '#canvas-panel .canvas-header'),
  };
  const o1 = ovl(j1.close, j1.ref);
  ok('S5 一次跳转：返回钮与关签钮 rect 零交叠（四边界实测）',
    !!j1.close && !!j1.ref && o1.area === 0,
    `close[${fmt(j1.close)}] refback[${fmt(j1.ref)}] overlap=${o1.w}×${o1.h}`);
  ok('S5b 返回钮不侵入居中标题胶囊',
    !!j1.capsule && !!j1.ref && j1.ref.right <= j1.capsule.left,
    `refRight=${j1.ref && j1.ref.right} capsuleLeft=${j1.capsule && j1.capsule.left}`);
  ok('S5c 关签钮落位与「无返回钮」基线逐值相同（两钮互不推挤）',
    !!noRef.close && !!j1.close && Math.abs(noRef.close.left - j1.close.left) < 0.5 && Math.abs(noRef.close.top - j1.close.top) < 0.5,
    `baseline=${fmt(noRef.close)} withRef=${fmt(j1.close)}`);
  await page.screenshot({ path: join(SHOTS, 's5-doc-jump1.png') });

  await openDoc(page, DOC_C, 'file:' + DOC_B);
  const j2 = { close: await box(page, '#canvas-head-close'), ref: await box(page, '#canvas-refback-btn'),
    src: await page.evaluate(() => document.getElementById('canvas-refback-btn')?.dataset.refSourceTab || null) };
  ok('S6 二次跳转（A→B→C）：来源记账覆写为 B（单层语义）',
    j2.src === 'file:' + DOC_B, String(j2.src));
  ok('S6b 二次跳转：落位与首次跳转逐值相同（位置不随跳转次数漂移）',
    !!j1.ref && !!j2.ref && Math.abs(j1.ref.left - j2.ref.left) < 0.5 && Math.abs(j1.ref.top - j2.ref.top) < 0.5,
    `j1=${fmt(j1.ref)} j2=${fmt(j2.ref)}`);
  ok('S6c 二次跳转：仍与关签钮零交叠', ovl(j2.close, j2.ref).area === 0, JSON.stringify(ovl(j2.close, j2.ref)));
  await page.screenshot({ path: join(SHOTS, 's6-doc-jump2.png') });

  await page.click('#canvas-refback-btn');
  await sleep(700);
  const active = await page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.dataset.tabId || null);
  ok('S7 单击返回钮 ⇒ 激活页签回到来源 B', active === 'file:' + DOC_B, String(active));
  ok('S9b 桌面腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ S8：窄面板（520 < 540 断点 ⇒ 第二行档）════════════════════ */
{
  const { ctx, page, errors } = await boot({ width: 1280, height: 860 });
  await page.evaluate(() => document.documentElement.style.setProperty('--canvas-width', '520px'));
  await sleep(400);
  await openDoc(page, DOC_A);
  await openDoc(page, DOC_B, 'file:' + DOC_A);
  const n = {
    panelW: await page.evaluate(() => Math.round(document.getElementById('canvas-panel').getBoundingClientRect().width)),
    tight: await page.evaluate(() => document.getElementById('canvas-panel').classList.contains('is-head-tight')),
    close: await box(page, '#canvas-head-close'),
    ref: await box(page, '#canvas-refback-btn'),
    capsule: await box(page, '#canvas-panel .canvas-header'),
    mdPad: await page.evaluate(() => parseFloat(getComputedStyle(document.querySelector('.canvas-tab-pane.active .canvas-md-scroll')).paddingTop)),
    twoRow: await tokenPx(page, '--cv-head-2row'),
  };
  ok('S8 窄面板（520px）判入第二行档（is-head-tight）', n.tight === true, `panelW=${n.panelW}`);
  ok('S8b 窄面板：返回钮落第二行且与关签钮零交叠',
    ovl(n.close, n.ref).area === 0 && !!n.ref && n.ref.top >= n.close.bottom,
    `close[${fmt(n.close)}] refback[${fmt(n.ref)}]`);
  ok('S8c 窄面板：返回钮与标题胶囊零交叠', ovl(n.capsule, n.ref).area === 0, JSON.stringify(ovl(n.capsule, n.ref)));
  ok('S8d 窄面板：正文让出两行净空（md 顶垫 = --cv-head-2row）',
    n.twoRow !== null && n.mdPad === n.twoRow, `pad=${n.mdPad} token=${n.twoRow}`);
  await page.screenshot({ path: join(SHOTS, 's8-narrow520.png') });
  ok('S9c 窄面板腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ S9：手机 375 ══════════════════════════════════════════════ */
{
  const { ctx, page, errors } = await boot({ width: 375, height: 812, mobile: true });
  await openDoc(page, DOC_A);
  await openDoc(page, DOC_B, 'file:' + DOC_A);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(1000);
  const m = {
    ref: await box(page, '#canvas-refback-btn'),
    close: await box(page, '.mf-cv-close'),
    strip: await box(page, '.mf-strip'),
    mdPad: await page.evaluate(() => parseFloat(getComputedStyle(document.querySelector('.canvas-tab-pane.active .canvas-md-scroll')).paddingTop)),
    twoRow: await tokenPx(page, '--cv-head-2row'),
  };
  ok('S9 手机层 375：返回钮真在场（改前挂在 hidden 的 #canvas-panel 里 ⇒ 0×0）',
    !!m.ref && m.ref.w > 40 && m.ref.h > 20, fmt(m.ref));
  ok('S9b 手机层 375：返回钮与左上 ✕ 零交叠', ovl(m.close, m.ref).area === 0,
    `close[${fmt(m.close)}] refback[${fmt(m.ref)}]`);
  ok('S9c 手机层 375：返回钮与环标签带零交叠', ovl(m.strip, m.ref).area === 0,
    `strip[${fmt(m.strip)}] refback[${fmt(m.ref)}]`);
  ok('S9d 手机层 375：正文让出两行（md 顶垫 = --cv-head-2row 手机值）',
    m.twoRow !== null && m.mdPad === m.twoRow, `pad=${m.mdPad} token=${m.twoRow}`);
  await page.screenshot({ path: join(SHOTS, 's9-mobile375.png') });
  ok('S9e 手机腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-overlap-head: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
