// canvas-overlap-guard.spec.mjs — canvas 顶部 chrome「碰撞体积」零交叠门禁
// （canvas-overlap-guard 批 2026-11-17；作者逐字：
//   「然后手机端canvas顶部还是会有重叠 是顶部胶囊点击展开后 会和两边按钮有重叠
//     然后搜索按钮点击后的搜索框会和顶部有重叠 我们要么用断言 要么用lint之类的
//     自动化检测 要么用碰撞体积的方式 来自动化检查重叠的发生」）
//
// 机制：真布局（零 mock 几何）——起独立静态服务，Playwright 开 手机375 / 桌面1440
//   × 浅/深 × 关键状态（默认、胶囊展开、搜索打开、堆叠列表），对登记在册的顶部
//   chrome 元素逐对求 rect 交叠，面积 > TOL（默认 1px²，AA 抖动余量）即 FAIL，
//   打印两个元素标识 + 交叠尺寸 + 相交矩形。白名单每条必须写免检理由。
//
// 改前红基线（本批修的两处，375 浅/深同值）：
//   ① 展开签 × 左✕ / 右堆叠钮：各 6.8×31.1 = 211.48px²（预算 +44px 外借所致）
//   ② findbar × 活签：117.7×1.6 = 188.32px²，且 findbar 左缘 -3px 溢出屏幕
//     （top 锚桌面口径 --cv-head-clear=56 + 输入定宽 200）
//
// 用法：
//   node tests/canvas-overlap-guard.spec.mjs                    # 门禁（默认仓内 web，PORT 18391）
//   node tests/canvas-overlap-guard.spec.mjs --web=<dir>        # 跑在另一棵检出上（验红实证）
//   node tests/canvas-overlap-guard.spec.mjs --selftest         # 注入红自检：把两条旧档规则
//                                                               # 注回**修后**树，必须红——
//                                                               # 证明门禁有牙（防假绿）
//   node tests/canvas-overlap-guard.spec.mjs --measure          # 打印全部登记元素 rect
//   可选：--port=、--shots=<dir>、--tol=<px²>
// 退出码：0 = 绿 / 1 = 红（--selftest 下：注入后**必须**红才算通过）

import pw from '/opt/homebrew/lib/node_modules/playwright/index.js';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = pw;
const HERE = dirname(fileURLToPath(import.meta.url));
const arg = (k, d) => { const a = process.argv.find((x) => x.startsWith('--' + k + '=')); return a ? a.slice(k.length + 3) : d; };
const MEASURE = process.argv.includes('--measure');
const SELFTEST = process.argv.includes('--selftest');
const WEB_DIR = resolve(arg('web', process.env.WEB_DIR || join(HERE, '../src/main/resources/web')));
const PORT = Number(arg('port', process.env.PORT || 18391));
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = arg('shots', join(HERE, '../.nebflow/evidence/canvas-overlap-guard'));
const TOL = Number(arg('tol', 1.0));
mkdirSync(SHOTS, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}
const R2 = (n) => Math.round(n * 100) / 100;
const ovl = (a, b) => {
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return { w: R2(Math.max(0, w)), h: R2(Math.max(0, h)), area: R2(Math.max(0, w) * Math.max(0, h)) };
};
const fmt = (b) => (b ? `l${b.left} t${b.top} r${b.right} b${b.bottom} (${b.w}×${b.h})` : 'null');

// ── 夹具：三篇长标题 markdown（长标签 = 展开档宽度压力面）────────────────
const FIX = '/tmp/nf-cog-spec';
mkdirSync(FIX, { recursive: true });
const NAMES = [
  '统一面板迁移总览与验收看板含截稿清单',
  '知识图谱多库合并与索引重建看板',
  '会话身份头同行批回归清单看板',
];
const DOCS = NAMES.map((n) => join(FIX, n + '.md'));
const BODY = '\n\n正文段落，用于撑出滚动面。\n'.repeat(8);
DOCS.forEach((d, i) => writeFileSync(d, `# ${NAMES[i]}\n\n第 ${i + 1} 篇。${BODY}`));

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
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
  description: `第 ${i + 1} 个演示插件。`, skills: [], mcpServers: [], toolsExtension: [], viewerContributions: { viewers: [] },
  blocked: false, trusted: true, contentChanged: false,
}));
const FILES = new Map(DOCS.map((d) => [d, 'markdown']));

const browser = await chromium.launch();

async function boot({ width = 1440, height = 900, mobile = false, scheme = 'dark' } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme: scheme, locale: 'zh-CN', hasTouch: mobile });
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
  // 契约面之外零接触宿主实例：WS 全本地 mock（与 canvas-overlap-head.spec.mjs 同款）。
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

const openDoc = async (page, p) => {
  await page.evaluate((path) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + path, itemType: 'markdown', title: path.split('/').pop().replace(/\.md$/, ''), absPath: path },
    }));
  }, p);
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${p}"] .canvas-md-scroll`, { timeout: 15000, state: 'attached' });
  await sleep(700);
};

// ── chrome 元素登记表（碰撞体积面；只登记「顶部/四角浮件」，正文让位归
//    canvas-overlap-head.spec.mjs 管，两 spec 职责不重叠）─────────────────
const REGISTRY = [
  { id: 'close', sel: '.mf-cv-toolbar .mf-cv-close', mobileOnly: true },
  { id: 'stack', sel: '.mf-cv-toolbar .mf-cv-stack', mobileOnly: true },
  { id: 'chip', sel: '.mf-strip-chip', mobileOnly: true, multi: true },
  { id: 'findbar', sel: '.nf-findbar' },
  { id: 'find-toggle', sel: '.canvas-find-toggle' },
  { id: 'refback', sel: '#canvas-refback-btn' },
  { id: 'head-close', sel: '#canvas-head-close', desktopOnly: true },
  { id: 'header', sel: '#canvas-panel .canvas-header', desktopOnly: true },
];

async function collect(page, mobile) {
  return page.evaluate(({ regs, mobile }) => {
    const out = [];
    for (const r of regs) {
      if (r.mobileOnly && !mobile) continue;
      if (r.desktopOnly && mobile) continue;
      document.querySelectorAll(r.sel).forEach((el, i) => {
        if (el.closest('[hidden]')) return;
        const cs = getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return;
        const b = el.getBoundingClientRect();
        if (b.width < 1 || b.height < 1) return;
        const rd = (n) => Math.round(n * 100) / 100;
        let id = r.id;
        if (r.multi) {
          const t = (el.querySelector('.mf-strip-label')?.textContent || el.textContent || '').trim().slice(0, 8);
          id = `${r.id}[${t}]${el.classList.contains('is-expanded') ? '+exp' : el.classList.contains('on') ? '+on' : ''}`;
        } else if (i) id = `${r.id}#${i}`;
        out.push({ id, rect: { left: rd(b.left), top: rd(b.top), right: rd(b.right), bottom: rd(b.bottom), w: rd(b.width), h: rd(b.height) } });
      });
    }
    return out;
  }, { regs: REGISTRY, mobile });
}

// 白名单：允许相交的对（id 前缀匹配，双向）。每条必须写免检理由。
// 当前为空 —— 修后矩阵全绿，无需豁免；新增豁免先在此登记理由。
const WHITELIST = [];

function pairFails(stateName, items) {
  const bad = [];
  for (let i = 0; i < items.length; i++) {
    for (let j = i + 1; j < items.length; j++) {
      const a = items[i], b = items[j];
      const o = ovl(a.rect, b.rect);
      if (o.area <= TOL) continue;
      const wl = WHITELIST.find((w) =>
        (a.id.startsWith(w.a) && b.id.startsWith(w.b)) || (a.id.startsWith(w.b) && b.id.startsWith(w.a)));
      console.log(`${wl ? 'WL   ' : 'FAIL '} [${stateName}] ${a.id} × ${b.id} — 交叠 ${o.w}×${o.h}=${o.area}px²  rect(l${R2(Math.max(a.rect.left, b.rect.left))} t${R2(Math.max(a.rect.top, b.rect.top))} r${R2(Math.min(a.rect.right, b.rect.right))} b${R2(Math.min(a.rect.bottom, b.rect.bottom))})${wl ? '  免检: ' + wl.reason : ''}`);
      if (!wl) bad.push(`${a.id}×${b.id}`);
    }
  }
  return bad;
}

// 注入红自检：把本批修掉的两条旧档规则**原样**注回页面（同特异性、文档末位
// ⇒ 生效）。门禁必须在展开态/搜索态报红，否则 = 假绿（选择器失效/断言恒真）。
const INJECT_OLD_RULES = `
body.mobile-layer .mf-strip-chip.is-expanded {
  max-width: min(calc((100vw - 2 * var(--mchrome-inset) + 44px) / var(--rsc, 1)), calc(260px + 44px));
}
body.mobile-layer .canvas-tab-pane .nf-findbar {
  top: var(--cv-head-clear, 56px);
  left: auto;
}
body.mobile-layer .nf-findbar-input { width: 200px; flex: none; }
`;

async function runStates(page, { mobile, scheme }, tag, selftest) {
  const states = mobile ? ['default', 'expanded', 'search', 'stack'] : ['default', 'search'];
  const redByState = {};
  for (const st of states) {
    if (st === 'expanded') {
      await page.click('.mf-strip-chip.on').catch(() => {});
      await sleep(600);
    } else if (st === 'search') {
      await page.click('.canvas-tab-pane.active .canvas-find-toggle').catch(() => {});
      await sleep(600);
    } else if (st === 'stack') {
      await page.click('.mf-cv-stack').catch(() => {});
      await sleep(600);
    }
    const items = await collect(page, mobile);
    if (MEASURE) console.log(`\n== ${tag}/${st} ==\n` + items.map((x) => `  ${x.id}: ${fmt(x.rect)}`).join('\n'));
    await page.screenshot({ path: join(SHOTS, `${tag}-${st}${selftest ? '-inject' : ''}.png`) });
    const bad = pairFails(`${tag}/${st}`, items);
    redByState[st] = bad;
    if (!selftest) ok(`[${tag}/${st}] chrome 两两零交叠（> ${TOL}px²）`, bad.length === 0, bad.length ? `${bad.length} 对交叠: ${bad.join('; ')}` : `${items.length} 件登记`);
    // 收场：关 findbar / 收起签 / 关堆叠
    if (st === 'search') { await page.click('.nf-findbar-close').catch(() => {}); await sleep(300); }
    if (st === 'expanded') { await page.click('.mf-strip-chip.is-expanded').catch(() => {}); await sleep(400); }
    if (st === 'stack') {
      await page.evaluate(() => { const s = document.querySelector('.mf-stack-view:not([hidden])'); if (s) (s.querySelector('.mf-stack-close') || s).dispatchEvent(new MouseEvent('click', { bubbles: true })); }).catch(() => {});
      await sleep(400);
    }
  }
  return redByState;
}

if (SELFTEST) {
  /* ═══ 注入红自检（手机 375 × dark 一档足够：两处病灶均与配色无关）═══ */
  const { ctx, page, errors } = await boot({ width: 375, height: 812, mobile: true, scheme: 'dark' });
  for (const d of DOCS) await openDoc(page, d);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(1000);
  await page.addStyleTag({ content: INJECT_OLD_RULES });
  const red = await runStates(page, { mobile: true, scheme: 'dark' }, 'selftest-m375', true);
  const expRed = (red.expanded || []).some((p) => p.includes('chip') && (p.includes('close') || p.includes('stack')));
  const searchRed = (red.search || []).some((p) => p.includes('findbar') && p.includes('chip'));
  ok('[selftest] 注入旧档①后展开态必须报红（chip×close/stack）', expRed, (red.expanded || []).join('; ') || '未报红 = 假绿！');
  ok('[selftest] 注入旧档②后搜索态必须报红（findbar×chip）', searchRed, (red.search || []).join('; ') || '未报红 = 假绿！');
  ok('[selftest] 自检腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
} else {
  /* ═══ 门禁矩阵：手机 375 / 桌面 1440 × 浅/深 × 状态 ═══ */
  for (const scheme of ['dark', 'light']) {
    const { ctx, page, errors } = await boot({ width: 375, height: 812, mobile: true, scheme });
    for (const d of DOCS) await openDoc(page, d);
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
    await sleep(1000);
    await runStates(page, { mobile: true, scheme }, `m375-${scheme}`, false);
    ok(`m375-${scheme} 零 JS 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }
  for (const scheme of ['dark', 'light']) {
    const { ctx, page, errors } = await boot({ width: 1440, height: 900, scheme });
    for (const d of DOCS) await openDoc(page, d);
    await runStates(page, { mobile: false, scheme }, `d1440-${scheme}`, false);
    ok(`d1440-${scheme} 零 JS 错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
    await ctx.close();
  }
}

await browser.close();
server.close();
console.log(`\ncanvas-overlap-guard${SELFTEST ? ' [selftest]' : ''}: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
