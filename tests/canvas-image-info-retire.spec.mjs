// canvas-image-info-retire.spec.mjs — 「文件名+体积」标签层退役 spec 钉
// （2026-10-25 作者报障 Bug A 第三根因：「胶囊本体……背后浮着一层更大的暗色
//   文字层，内容是『fig07_He破案_….png 279.2 KB』——旧版文件名+体积标签面仍
//   在渲染，被胶囊叠住只露出边缘，形成重复视觉」）
//
// 根因：viewers/image.js:78-81（修前）在图片页签 pane 顶部渲染 .image-info
//   「fileName — formatSize(size)」；canvas 顶带标题胶囊（icon + 文件名）恒在
//   场且是唯一名称面 ⇒ 两层面同屏叠置，标签行被胶囊盖住只露边缘。video.js
//   同型（viewers/video.js:49-52 修前），split.css 两条 .image-info 规则随层
//   退役。修复 = 渲染端摘除整层（胶囊互斥令：胶囊在场不渲染底层标签），
//   非 z-index 遮盖。
//
// 消费面核查（退役安全性）：image/video viewer 唯一消费腿 = canvas 文件页签 /
//   附件预览页签（attachmentPreview renderIntoTab→openTab）/ 卡窗 —— 三腿标题
//   胶囊全在场，无「无胶囊」消费面，整层退役无信息孤岛（文件名在胶囊）。
//
// 锁面：
//   R1 图片页签零 .image-info（pane 内 + 全文档；前置 = 图片真加载 onload，
//      即修前创建 info 的同一条路径）；
//   R2 标题胶囊是唯一名称面：#canvas-title-name = 文件名；
//   R3 split.css 文本零 .image-info 选择器（两条规则——含 video 死规则——同验）；
//   R4 视频页签同口径：video 元素在、.image-info 无；
//   R5 全程零 JS 错误。
//
// 验红：stash 本批三文件后 R1a/R1b/R3/R4 即败（.image-info 复现 + CSS 规则
//   回归）。
//
// 运行：node tests/canvas-image-info-retire.spec.mjs
// （boot 同 capsule-size-uniform.spec.mjs：隔离静态服务器 + workspace-open-item
//   直灌页签 + nf-ticket 假票 + /api/nf-file 字节路由；永不触碰宿主 8080）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8219);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

// 1×1 PNG（修前 .image-info 在 img.onload 里创建 —— 前置须真加载走同一路径）
const PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const IMG_DISK = '/tmp/ci-fixture/fig07.png';
const MP4_DISK = '/tmp/ci-fixture/clip.mp4';
mkdirSync('/tmp/ci-fixture', { recursive: true });
writeFileSync(IMG_DISK, Buffer.from(PNG_B64, 'base64'));
writeFileSync(MP4_DISK, Buffer.alloc(0)); // 字节腿挂起路由接管，磁盘件仅占位

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const IMG_TITLE = 'fig07_He破案_机制链与修复瀑布.png';
const VID_TITLE = 'clip.mp4';

function makeServerWsMocks(ctx) {
  return ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

// /api/nf-file 字节腿（同 canvas-html-zoom 形态：catch-all 先注册、nf-file 后
// 注册 = 后注册优先；无票 401 由 ticketGuard 把守）。
function nfFileRoute() {
  return async (route) => {
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 200, contentType: 'image/png', body: readFileSync(IMG_DISK) });
  };
}
// 视频字节腿：永不 fulfill —— <video> 停在 loadstart，不触发 error 面板，
// 让 DOM 断言（video 在场 / info 不在场）与网络结果解耦。
const mp4HangRoute = async () => { await new Promise(() => {}); };

async function boot() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  makeServerWsMocks(ctx);
  const page = await ctx.newPage();
  // 页级路由压过 ctx 级 catch-all：png 走字节、mp4 走挂起（后注册优先）。
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await page.route(/\/api\/nf-file\?.*\.mp4/, mp4HangRoute);
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(700);
  return { ctx, page, errors };
}

async function openItem(page, { absPath, itemType, title }) {
  await page.evaluate(({ absPath, itemType, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, absPath, pinned: true },
    }));
  }, { absPath, itemType, title });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

const probe = (page, tabId) => page.evaluate((id) => {
  const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
  const name = document.getElementById('canvas-title-name');
  return {
    paneInfo: !!pane && !!pane.querySelector('.image-info'),
    docInfo: !!document.querySelector('.image-info'),
    viewportImg: !!pane && !!pane.querySelector('.canvas-image-viewport img'),
    imgLoaded: !!pane && (() => { const i = pane.querySelector('.canvas-image-viewport img'); return !!i && i.complete && i.naturalWidth >= 1; })(),
    videoEl: !!pane && !!pane.querySelector('video.canvas-video'),
    capsuleName: name ? name.textContent : null,
  };
}, tabId);

/* ════════ R1/R2/R3：图片页签 ════════ */
{
  const { ctx, page, errors } = await boot();
  await openItem(page, { absPath: IMG_DISK, itemType: 'image', title: IMG_TITLE });
  // 前置：图片真加载（onload DOM 构建完成 = 修前创建 info 的同一路径）
  await page.waitForSelector('.canvas-image-viewport img', { timeout: 10000 });
  await sleep(400);
  const p = await probe(page, 'file:' + IMG_DISK);

  ok('R1a 前置：图片经 onload 真渲染（viewport + img loaded）', p.viewportImg && p.imgLoaded,
    `viewport=${p.viewportImg} loaded=${p.imgLoaded}`);
  ok('R1b 图片页签 pane 零 .image-info（修前「fileName — 279.2 KB」标签行）', !p.paneInfo);
  ok('R1c 全文档零 .image-info', !p.docInfo);
  ok('R2 标题胶囊是唯一名称面（#canvas-title-name = 文件名）',
    p.capsuleName === IMG_TITLE, `got=${p.capsuleName}`);

  const cssText = await page.evaluate(async () => (await fetch('/css/split.css')).text());
  // 断言选择器而非字面：退役留痕注释（.image-info 退役…）允许提及类名，
  // 规则本体（.image-info { / ,）不得存在。
  ok('R3 split.css 零 .image-info 规则选择器（图片+视频两规则同验）',
    !/\.image-info\s*[{,]/.test(cssText));

  // R4 视频页签同口径（字节挂起：video 在场、info 不在场、无 error 面板干扰）
  await openItem(page, { absPath: MP4_DISK, itemType: 'video', title: VID_TITLE });
  await page.waitForSelector('.canvas-video-viewer video.canvas-video', { timeout: 10000 });
  const v = await probe(page, 'file:' + MP4_DISK);
  ok('R4 视频页签：video 元素在、.image-info 无（video.js 同型摘除）',
    v.videoEl && !v.paneInfo && !v.docInfo, `video=${v.videoEl} info=${v.paneInfo}/${v.docInfo}`);

  ok('R5 全程零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-image-info-retire: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
