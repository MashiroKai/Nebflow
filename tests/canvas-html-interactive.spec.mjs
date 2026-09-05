// canvas-html-interactive.spec.mjs — Canvas HTML 查看器交互使能验收
// （2026-09-03「原型在 Canvas 只能看不能点」修复，实施+验收一体）。
//
// 被测真实链路（无任何 mock 替身层跳过）：
//   workspace-open-item 事件 → canvas.js openWorkspaceItem → viewers/html.js
//   viewHtml → resolveLocalFiles 把 src="./snapshot-data.js" 改写成
//   /api/nf-file?path=<abs> → srcdoc iframe（sandbox allow-scripts …）内
//   加载执行。后端 /api/nf-file 扩展名白名单由 WebSocketRoutes.nfFileRoutes
//   提供（NfFileRoutesSpec 覆盖路由层）；本 spec 在 page.route 层模拟该
//   路由的真实行为（按磁盘真身文件 + 正确 Content-Type 应答，模拟修复后
//   的白名单；负控制组按 400 应答，模拟修复前的「File type not allowed」）。
//
// 断言面：
//   T1 iframe 存在且 sandbox 含 allow-scripts（安全契约快照）；
//   T2 伴随数据模块经 /api/nf-file 真身加载并执行（window.FM_SNAPSHOT +
//      11 张活动卡 + 25 条归档徽章）；
//   T3 完整交互链 A：点归档图标 → 面板展开（aria-expanded）→ 点条目 →
//      独占展开态 → Esc 关闭（状态回到 closed）；
//   T4 完整交互链 B：点主题按钮 → body.theme-dark + --color-bg 翻转 →
//      再点还原；
//   T5 防递归嵌套保护不误伤：无 "Rendering was stopped" 替换层；
//   T0 负控制组（复现控制，非回归）：nf-file 对 .js 应 400（= 修复前
//      白名单行为）→ 同一原型「渲染但不可交互」：.fm-node=0、点击图标
//      aria-expanded 不变 —— 证明本 harness 能检出「死」态（变异验红的
//      永久基线）。
//
// 运行（仓库根 node_modules 提供 playwright；本 worktree 以 node_modules
// 软链指向主仓）：
//   node tests/canvas-html-interactive.spec.mjs
// 可选 env：
//   PORT=8117            静态服务端口（非 8080；默认 8117）
//   CANVAS_HTML_SHOTS_DIR=<dir>  交互证据截图输出目录（默认不写截图）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const FIXTURE_DIR = resolve(HERE, 'fixtures/canvas-html-interactive');
const PORT = Number(process.env.PORT || 8117);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.CANVAS_HTML_SHOTS_DIR || '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树，非 8080 端口）──
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── /api/nf-file 模拟（按后端白名单修复后/前的两种行为）──
// fixed=true：js/css/json 等白名单类型按磁盘真身应答（= 修复后的后端）；
// fixed=false：.js 应 400 "File type not allowed"（= 修复前，负控制组）。
function nfFileRouteBehavior(fixed) {
  const ALLOWED = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico', 'mp4', 'webm', 'mp3', 'wav', 'woff', 'woff2', 'ttf', 'otf', 'pdf', 'docx', 'xlsx', 'pptx', 'epub', 'js', 'mjs', 'css', 'json'];
  const TXT = { js: 'text/javascript; charset=utf-8', mjs: 'text/javascript; charset=utf-8', css: 'text/css; charset=utf-8', json: 'application/json', svg: 'image/svg+xml', png: 'image/png', jpg: 'image/jpeg' };
  return async (route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    const ext = (p.split('.').pop() || '').toLowerCase();
    if (!ALLOWED.includes(ext) || (ext === 'js' && !fixed)) {
      return route.fulfill({ status: 400, contentType: 'text/plain', body: 'File type not allowed' });
    }
    try {
      return route.fulfill({ status: 200, contentType: TXT[ext] || 'application/octet-stream', body: readFileSync(p) });
    } catch {
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    }
  };
}

async function bootPage(browser, { fixed }) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  // 注册顺序：先泛 API 兜底，后 nf-file 专属（Playwright 后注册者优先）。
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRouteBehavior(fixed));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(400);
  // 真实打开链路：workspace-open-item → canvas → html viewer（真身 fixture 内容 + absPath）
  const protoPath = join(FIXTURE_DIR, 'prototype.html');
  const content = readFileSync(protoPath, 'utf8');
  await page.evaluate(({ content, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'html', title: 'prototype.html', content, absPath, pinned: false },
    }));
  }, { content, absPath: protoPath });
  await page.waitForSelector('iframe[data-nf-canvas-html]', { timeout: 10000 });
  // srcdoc document attach is async — poll instead of a fixed sleep.
  let frame = null;
  for (let i = 0; i < 20 && !frame; i++) {
    await sleep(300);
    frame = page.frames().find((f) => f.url() === 'about:srcdoc') || null;
  }
  return { ctx, page, frame, errors };
}

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  if (cond) { pass++; results.push(`PASS  ${name}${detail ? '  — ' + detail : ''}`); }
  else { fail++; results.push(`FAIL  ${name}${detail ? '  — ' + detail : ''}`); }
}

const browser = await chromium.launch();

/* ════════ T0 负控制组：nf-file 对 .js 400（= 修复前白名单）→「渲染但不可交互」 ════════ */
{
  const { ctx, page, frame } = await bootPage(browser, { fixed: false });
  await frame.waitForSelector('#btnArchive', { timeout: 5000 }).catch(() => {});
  const dead = await frame.evaluate(() => ({
    snapshotLoaded: typeof window.FM_SNAPSHOT !== 'undefined',
    fmNodes: document.querySelectorAll('.fm-node').length,
    ariaBefore: document.getElementById('btnArchive')?.getAttribute('aria-expanded'),
  })).catch((e) => ({ evalError: String(e).slice(0, 120) }));
  // 点图标（真实鼠标事件）→ aria-expanded 必须纹丝不动（脚本已死、无监听）
  await frame.click('#btnArchive', { timeout: 3000 }).catch(() => {});
  await sleep(350);
  const ariaAfter = await frame.evaluate(() => document.getElementById('btnArchive')?.getAttribute('aria-expanded')).catch(() => null);
  const paneText = await page.evaluate(() => document.querySelector('.canvas-tab-pane')?.textContent || '');
  ok('T0 复现控制：iframe 已渲染（死态可检出）', !!frame, `frame=${!!frame}`);
  ok('T0 复现控制：.fm-node = 0（数据模块被 400 拒绝）', dead.fmNodes === 0, `fmNodes=${dead.fmNodes}`);
  ok('T0 复现控制：点击图标 aria-expanded 不变（不可交互）', dead.ariaBefore === ariaAfter && dead.ariaBefore === 'false', `before=${dead.ariaBefore} after=${ariaAfter}`);
  ok('T0 复现控制：未被防递归保护误替换', !paneText.includes('Rendering was stopped'));
  await ctx.close();
}

/* ════════ 主场景：nf-file 模拟修复后行为 → 完整交互 ════════ */
{
  const { ctx, page, frame, errors } = await bootPage(browser, { fixed: true });

  /* T1 iframe 与 sandbox 安全契约 */
  const sandbox = await page.evaluate(() => document.querySelector('iframe[data-nf-canvas-html]')?.getAttribute('sandbox'));
  ok('T1 iframe 存在且 sandbox 含 allow-scripts', !!sandbox && sandbox.split(/\s+/).includes('allow-scripts'), sandbox);
  ok('T1 sandbox 契约快照（allow-scripts allow-same-origin allow-forms allow-popups）', sandbox === 'allow-scripts allow-same-origin allow-forms allow-popups', sandbox);

  /* T2 伴随数据模块经 /api/nf-file 真身加载并执行 */
  await frame.waitForSelector('.fm-node', { timeout: 6000 });
  const t2 = await frame.evaluate(() => ({
    snapshotLoaded: typeof window.FM_SNAPSHOT !== 'undefined',
    fmNodes: document.querySelectorAll('.fm-node').length,
    badge: document.getElementById('fmBadge')?.textContent?.trim(),
  }));
  ok('T2 window.FM_SNAPSHOT 已由伴随模块注入', t2.snapshotLoaded);
  ok('T2 活动卡 = 11（数据模块真实执行）', t2.fmNodes === 11, `fmNodes=${t2.fmNodes}`);
  ok('T2 归档徽章 = 25', t2.badge === '25', `badge=${t2.badge}`);

  /* T3 完整交互链 A：图标 → 面板展开 → 条目独占展开 → Esc 关闭 */
  await frame.click('#btnArchive');
  await sleep(350);
  const t3open = await frame.evaluate(() => ({
    open: window.__fm?.state?.().open,
    aria: document.getElementById('btnArchive')?.getAttribute('aria-expanded'),
    entries: document.querySelectorAll('[data-testid="archive-entry"]').length,
  }));
  ok('T3 点击归档图标 → 面板展开（aria-expanded=true）', t3open.open === true && t3open.aria === 'true', JSON.stringify(t3open));
  ok('T3 面板条目 = 25', t3open.entries === 25, `entries=${t3open.entries}`);
  await frame.locator('[data-testid="archive-entry"]').nth(1).click();
  await sleep(300);
  const t3exp = await frame.evaluate(() => {
    const els = [...document.querySelectorAll('[data-testid="archive-entry"]')];
    return { expanded: els.filter((e) => e.classList.contains('expanded')).map((e) => e.dataset.id), aria: els[1]?.getAttribute('aria-expanded') };
  });
  ok('T3 点击条目 → 独占展开（同时仅一条 expanded + aria=true）', t3exp.expanded.length === 1 && t3exp.aria === 'true', JSON.stringify(t3exp));
  // Focus lives inside the iframe after the entry click; Escape routes there.
  await page.keyboard.press('Escape');
  await sleep(300);
  const t3closed = await frame.evaluate(() => window.__fm?.state?.().open);
  ok('T3 Esc 关闭面板（状态回 closed）', t3closed === false, `open=${t3closed}`);

  /* T4 完整交互链 B：主题切换（按钮 → body.theme-dark + token 翻转 → 还原） */
  const bgLight = await frame.evaluate(() => getComputedStyle(document.body).getPropertyValue('--color-bg').trim());
  await frame.click('#btnTheme');
  await sleep(250);
  const t4dark = await frame.evaluate(() => ({
    dark: document.body.classList.contains('theme-dark'),
    bg: getComputedStyle(document.body).getPropertyValue('--color-bg').trim(),
  }));
  ok('T4 点击主题按钮 → body.theme-dark + --color-bg 翻转', t4dark.dark === true && t4dark.bg !== bgLight, `${bgLight} → ${t4dark.bg}`);
  if (SHOTS_DIR) {
    mkdirSync(SHOTS_DIR, { recursive: true });
    await page.screenshot({ path: join(SHOTS_DIR, '20260903_canvas-html-interactive-dark.png') });
  }
  await frame.click('#btnTheme');
  await sleep(250);
  const t4back = await frame.evaluate(() => ({
    dark: document.body.classList.contains('theme-dark'),
    bg: getComputedStyle(document.body).getPropertyValue('--color-bg').trim(),
  }));
  ok('T4 再次点击 → 主题还原', t4back.dark === false && t4back.bg === bgLight, `bg=${t4back.bg}`);
  if (SHOTS_DIR) {
    await page.screenshot({ path: join(SHOTS_DIR, '20260903_canvas-html-interactive-light.png') });
  }

  /* T5 防递归嵌套保护不误伤 + 无 JS 运行时错误 */
  const paneText = await page.evaluate(() => document.querySelector('.canvas-tab-pane')?.textContent || '');
  ok('T5 无防递归误替换（无 "Rendering was stopped"）', !paneText.includes('Rendering was stopped'));
  ok('R0 主文档无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));

  await ctx.close();
}

await browser.close();
server.close();
console.log(results.join('\n'));
console.log(`\n═══ canvas-html-interactive: ${pass} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);
