#!/usr/bin/env node
// md-image-in-doc.spec.mjs — 文档里放图（image-in-doc 批，作者报障 2026-11-05
// 14:18 逐字「可以放文档 但问题是 文档里只有路径 图片没渲染出来」+ 15:15 追加
// 第二份复现样本「是现在渲染引擎的问题吗 截图在里面 但是不渲染出来」）。
//
// 被测真实链路（前端零替身，照 mermaid-md.spec.mjs 同一套替身纪律）：
//   workspace-open-item → canvas.js openWorkspaceItem → fileViewers.js renderFile
//   → viewers/markdown.js viewMarkdown → utils.js renderMarkdownWithMath（共享管线）
//   → 图片 pass：embed 展开（![[a.png]]）→ 候选路径（文档目录 / vault 根 /
//     vault 的 attachments·assets / 绝对）→ nfTicket.mintTickets（POST /api/nf-ticket）
//   → nfFileCache.nfViewerUrl（账本）→ <img> 终局 src（blob 或 /api/nf-file?path&ticket）
//   → 失败面（明确占位：原因 + 原路径）。
//
// 判据（三写法 × 四基准）：
//   A1 标准语法 ![](…)  × ①文档同目录 / ①子目录 assets/ / ④绝对路径
//   A2 wikilink 嵌入 ![[…]] × ①子目录 / ③vault 附件目录（裸文件名，作者样本二形态）
//   A3 HTML <img src>    × ①文档同目录 / ④绝对路径
//   A5 断链 → 明确失败占位（原因文案 + 原路径原文），绝不留白
//   A6 尺寸：不超过内容列宽、保持比例；点开看原图（既有 .nf-zoom-img → lightbox）
//   A7 富渲染零回归（```human 卡片 / KaTeX / js 围栏 / 行内 SVG，独立文档）
//   A8 零 pageerror；A9 每次 /api/nf-file 都带票、URL 不夹带全局 token
//
// 运行：node tests/md-image-in-doc.spec.mjs
// 可选 env：PORT=8181、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8181);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, '.nebflow', 'evidence', '20261105_md-image-in-doc', 'shots');
const VIEWPORT = { width: 1280, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── 夹具：一个真 vault（布局照 SKILL.md「Vault layout」）───────────
   vault 根 = `…/.stand/docs/`；附件惯例目录 = `<vault>/attachments/`、`<vault>/assets/`。 */
const FIX = join(tmpdir(), `qa-mdimg-${process.pid}`);
const VAULT = join(FIX, 'projects', 'qa', '.stand', 'docs');   // ← vault 根（= docs/）
const DOC_ASSETS = join(VAULT, 'assets');
const VAULT_ATTACH = join(VAULT, 'attachments');
const MD_PATH = join(VAULT, 'doc-images.md');
const RICH_PATH = join(VAULT, 'rich-render.md');

// 真图字节：作者实测那只证据图（真截图，不是 1×1 像素）。
const REAL_SRC = join(homedir(), '.stand', 'evidence', 'canvas-note-button', 'note-open.png');
const PNG_BYTES = existsSync(REAL_SRC) ? readFileSync(REAL_SRC) : Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

for (const d of [DOC_ASSETS, VAULT_ATTACH]) mkdirSync(d, { recursive: true });
writeFileSync(join(VAULT, 'same-dir.png'), PNG_BYTES);            // 基准① 文档同目录
writeFileSync(join(DOC_ASSETS, 'via-assets.png'), PNG_BYTES);     // 基准① 子目录 / 基准③ assets
writeFileSync(join(VAULT_ATTACH, 'vault-attach.png'), PNG_BYTES); // 基准③ vault 的 attachments（裸名）
writeFileSync(join(FIX, 'abs.png'), PNG_BYTES);                   // 基准④ 绝对路径
writeFileSync(join(FIX, 'abs-md.png'), PNG_BYTES);                // 基准④ 绝对路径（markdown）

const MD_CONTENT = [
  '# 文档内图片渲染验收（三写法 × 四基准）',
  '',
  '## 标准语法（感叹号+方括号）',
  '',
  '① 文档同目录：',
  '',
  '![标准·文档同目录](same-dir.png)',
  '',
  '① 子目录 assets：',
  '',
  '![标准·子目录](assets/via-assets.png)',
  '',
  '④ 绝对路径：',
  '',
  `![标准·绝对路径](${join(FIX, 'abs-md.png')})`,
  '',
  '## wikilink 嵌入（Obsidian 形态）',
  '',
  '① 子目录：',
  '',
  '![[assets/via-assets.png]]',
  '',
  '③ vault 附件目录（裸文件名 —— 作者样本二的形态）：',
  '',
  '![[vault-attach.png]]',
  '',
  '## HTML img 标签',
  '',
  '① 文档同目录：',
  '',
  '<img src="same-dir.png" alt="HTML·文档同目录">',
  '',
  '④ 绝对路径：',
  '',
  `<img src="${join(FIX, 'abs.png')}" alt="HTML·绝对路径">`,
  '',
  '## 断链（文件不存在 ⇒ 必须出现明确失败占位）',
  '',
  '![断链](does-not-exist.png)',
  '',
].join('\n');
writeFileSync(MD_PATH, MD_CONTENT);

// 富渲染回归单独一份（```human 围栏会把围栏外内容折叠进 <details>，混在图片
// 文档里会让人误读成「图没渲染」——分文档测，判据才干净）。
const RICH_CONTENT = [
  '# 富渲染零回归',
  '',
  '```human',
  '### 结论卡',
  '正文里带 **加粗** 与行内数学 $a^2+b^2=c^2$。',
  '```',
  '',
  '```js',
  'const x = 1;',
  '```',
  '',
  '<svg width="60" height="20"><rect width="60" height="20" fill="currentColor"/></svg>',
  '',
].join('\n');
writeFileSync(RICH_PATH, RICH_CONTENT);

/* ─────────────── 静态服务（真 web/ 树；自有端口） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ─────────────── 应用引导 ─────────────── */
const EXT_ITEM = { md: 'markdown', markdown: 'markdown', json: 'json' };
const nfReqs = [];

async function boot(browser) {
  const ctx = await browser.newContext({ viewport: VIEWPORT });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 300)); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/graph**', (r) => r.fulfill({
    json: {
      projects: [{ name: 'qa', vault: VAULT, docsDir: VAULT }],
      nodes: [], edges: [],
    },
  }));
  await installTicketMock(page);
  // /api/nf-file：真字节（磁盘存在即 200，否则 404 —— 端点语义）；无票 401。
  await page.route('**/api/nf-file**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    nfReqs.push({ path: p, hasTicket: url.searchParams.has('ticket'), hasToken: url.searchParams.has('token') });
    if (await ticketGuard(route)) return;
    let body = null;
    try { body = readFileSync(p); } catch { body = null; }
    if (!body) return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    return route.fulfill({ status: 200, contentType: 'image/png', body });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type !== 'pop.readFile') return;
      const rawPath = String(m.path || '');
      const abs = rawPath.startsWith('~') ? join(homedir(), rawPath.slice(1).replace(/^[/\\]/, '')) : rawPath;
      try {
        const buf = readFileSync(abs);
        const ext = (abs.split('.').pop() || '').toLowerCase();
        ws.send(JSON.stringify({
          type: 'fileContent', path: rawPath, absPath: abs, itemType: EXT_ITEM[ext] || 'code',
          fileName: abs.split('/').pop(), size: buf.length, content: buf.toString('utf8'),
        }));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: abs }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors };
}

async function openMdTab(page, { absPath, content, title }) {
  await page.evaluate(({ absPath, content, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content, absPath, pinned: true },
    }));
  }, { absPath, content, title });
  await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll');
  await sleep(300);
}

/* ─────────────── 断言 ─────────────── */
let failed = 0;
function ok(cond, label, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${extra ? '  — ' + extra : ''}`);
  if (!cond) failed++;
}

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();
try {
  const { page, pageErrors, consoleErrors } = await boot(browser);
  await openMdTab(page, { absPath: MD_PATH, content: MD_CONTENT, title: 'doc-images.md' });
  const V = '.canvas-tab-pane.active .canvas-md-viewer';
  await sleep(2500);   // 等齐：铸票 → 账本/票据取字节 → 级联

  const st = await page.evaluate((sel) => {
    const root = document.querySelector(sel);
    if (!root) return null;
    const imgs = Array.from(root.querySelectorAll('img')).map((im) => {
      const r = im.getBoundingClientRect();
      return {
        alt: im.getAttribute('alt') || '',
        src: String(im.currentSrc || im.src || '').slice(0, 200),
        nw: im.naturalWidth, nh: im.naturalHeight,
        w: Math.round(r.width), h: Math.round(r.height),
        visible: r.width > 0 && r.height > 0,
        path: im.dataset.nfMdPath || '',
        zoomable: im.classList.contains('nf-zoom-img'),
      };
    });
    const fails = Array.from(root.querySelectorAll('[data-nf-md-img-fail]')).map((el) => ({
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 200),
      path: (el.querySelector('.nf-md-img-fail-path') || {}).textContent || '',
      reason: (el.querySelector('.nf-md-img-fail-reason') || {}).textContent || '',
      html: el.outerHTML.slice(0, 400),
    }));
    return {
      imgs, fails,
      wikilinks: Array.from(root.querySelectorAll('a.nf-wikilink')).map((a) => a.textContent || ''),
      contentWidth: Math.round(root.getBoundingClientRect().width),
    };
  }, V);

  const imgOf = (alt) => st.imgs.find((i) => i.alt === alt) || null;
  const shot = async (name, sel) => {
    const loc = page.locator(sel).first();
    if (!(await loc.count())) return;
    try { await loc.scrollIntoViewIfNeeded(); } catch { /* in view */ }
    await sleep(150);
    try { await loc.screenshot({ path: join(SHOTS_DIR, name) }); } catch { /* zero box */ }
  };

  // ── A1 标准语法 × 三基准 ──
  const a1a = imgOf('标准·文档同目录');
  ok(!!a1a && a1a.nw > 0 && a1a.visible, 'A1a ![](same-dir.png) 文档同目录 → 真出图', JSON.stringify(a1a));
  const a1b = imgOf('标准·子目录');
  ok(!!a1b && a1b.nw > 0 && a1b.visible, 'A1b ![](assets/via-assets.png) 文档子目录 → 真出图', JSON.stringify(a1b));
  const a1c = imgOf('标准·绝对路径');
  ok(!!a1c && a1c.nw > 0 && a1c.visible, 'A1c ![](绝对路径) → 真出图', JSON.stringify(a1c));

  // ── A2 wikilink 嵌入 × 两基准 ──
  const a2a = st.imgs.find((i) => i.alt === 'assets/via-assets.png');
  ok(!!a2a && a2a.nw > 0 && a2a.visible, 'A2a ![[assets/via-assets.png]] 文档子目录 → 真出图', JSON.stringify(a2a));
  const a2b = st.imgs.find((i) => /vault-attach\.png/.test(i.alt || ''));
  ok(!!a2b && a2b.nw > 0 && a2b.visible, 'A2b ![[vault-attach.png]] 裸名 → vault 的 attachments/ 出图（作者样本二）', JSON.stringify(a2b));
  ok(!!a2b && /\/attachments\/vault-attach\.png$/.test(a2b.path || ''), 'A2c 生效路径 = vault attachments 腿（基准③）', a2b && a2b.path);
  ok(!(st.wikilinks || []).some((t) => /\.png/.test(t)), 'A2d 图片嵌入不再降级成蓝字链接', JSON.stringify(st.wikilinks));

  // ── A3 HTML <img> × 两基准 ──
  const a3a = imgOf('HTML·文档同目录');
  ok(!!a3a && a3a.nw > 0 && a3a.visible, 'A3a <img src="same-dir.png"> 文档同目录 → 真出图', JSON.stringify(a3a));
  const a3b = imgOf('HTML·绝对路径');
  ok(!!a3b && a3b.nw > 0 && a3b.visible, 'A3b <img src=绝对路径> → 真出图', JSON.stringify(a3b));

  // ── A5 断链失败占位 ──
  const failBox = (st.fails || [])[0];
  ok((st.fails || []).length >= 1, 'A5 断链出现失败占位（不留白）', JSON.stringify(st.fails));
  ok(!!failBox && /does-not-exist\.png/.test(failBox.text || ''), 'A5b 占位含原路径原文', failBox && failBox.text);
  ok(!!failBox && /不存在|404|失败|无读取|权限/.test(failBox.reason || failBox.text || ''), 'A5c 占位含原因文案', failBox && failBox.reason);

  // ── A6 尺寸 / 比例 / 可点开 ──
  ok(st.imgs.every((i) => !i.visible || i.w <= st.contentWidth), `A6 图片不超过内容列宽（列宽 ${st.contentWidth}）`,
    JSON.stringify(st.imgs.map((i) => i.w)));
  ok(st.imgs.every((i) => !i.visible || !i.nw || Math.abs((i.w / i.h) - (i.nw / i.nh)) < 0.06), 'A6b 保持宽高比');
  ok(st.imgs.every((i) => i.zoomable), 'A6c 每枚文档图片都带 .nf-zoom-img（既有 lightbox 腿可点开看原图）',
    JSON.stringify(st.imgs.map((i) => i.zoomable)));

  // 真点一下：标准语法那张 → lightbox
  const pt = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width };
  }, `${V} img[alt="标准·文档同目录"]`);
  if (pt && pt.w > 0) {
    await sleep(200);
    await page.mouse.click(pt.x, pt.y);
    await sleep(800);
    const lb = await page.evaluate(() => !!document.querySelector('.nf-lightbox.on'));
    ok(lb, 'A6d 点击图片 → lightbox 看原图（既有腿零改动）');
    await page.screenshot({ path: join(SHOTS_DIR, 'after-lightbox.png') });
    await page.keyboard.press('Escape');
    await sleep(200);
  } else {
    ok(false, 'A6d 点击图片 → lightbox 看原图（图片不在）');
  }

  // 截图（真图，非「路径文字变蓝」）
  await page.screenshot({ path: join(SHOTS_DIR, 'after-doc-images.png') });
  await shot('after-standard.png', `${V} img[alt="标准·文档同目录"]`);
  await shot('after-wikilink.png', `${V} img[alt="vault-attach.png"]`);
  await shot('after-html.png', `${V} img[alt="HTML·绝对路径"]`);
  await shot('after-failface.png', `${V} [data-nf-md-img-fail]`);

  // ── A7 富渲染零回归（独立文档，避免 human 围栏的折叠语义混淆读数）──
  await openMdTab(page, { absPath: RICH_PATH, content: RICH_CONTENT, title: 'rich-render.md' });
  await sleep(600);
  // KaTeX 是视口懒渲染（fenceKit 的 IntersectionObserver）：等它落定再断言。
  await page.waitForFunction(() => {
    const m = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer .nf-math');
    return !m || m.dataset.mathState === 'done' || m.dataset.mathState === 'error';
  }, { timeout: 15000 }).catch(() => {});
  const rich = await page.evaluate((sel) => {
    const root = document.querySelector(sel);
    if (!root) return null;
    return {
      humanCard: !!root.querySelector('.nf-human-card'),
      katex: !!root.querySelector('.katex'),
      pre: !!root.querySelector('pre code'),
      svg: !!root.querySelector('svg'),
      html: (root.innerHTML || '').slice(0, 1200),
    };
  }, V);
  ok(rich && rich.humanCard, 'A7 ```human 卡片仍在（零回归）', JSON.stringify(rich));
  ok(rich && rich.katex, 'A7b KaTeX 行内数学仍在');
  ok(rich && rich.pre, 'A7c js 代码块仍在');
  ok(rich && rich.svg, 'A7d 行内 SVG 仍在');
  await page.screenshot({ path: join(SHOTS_DIR, 'after-rich-render.png') });

  // ── A8/A9 错误与凭据面 ──
  ok(pageErrors.length === 0, 'A8 零 pageerror', pageErrors.join(' | '));
  const realConsoleErrors = consoleErrors.filter((m) => !/Failed to load resource/.test(m));
  ok(realConsoleErrors.length === 0, 'A8b 零 console error（资源 404 属断链夹具预期，单列 A8c）', realConsoleErrors.join(' | '));
  ok(true, `A8c 断链夹具的预期 404 读数 ${consoleErrors.length} 条`);
  const noTicket = nfReqs.filter((r) => !r.hasTicket);
  ok(noTicket.length === 0, 'A9 每次 /api/nf-file 都带票（无 401 腿）', JSON.stringify(noTicket.slice(0, 3)));
  ok(!nfReqs.some((r) => r.hasToken), 'A9b URL 里不夹带全局 token');
  writeFileSync(join(SHOTS_DIR, 'readings.json'), JSON.stringify({ imgs: st.imgs, fails: st.fails, nfReqs }, null, 2));
} finally {
  await browser.close();
  webServer.close();
  try { rmSync(FIX, { recursive: true, force: true }); } catch { /* best effort */ }
}

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAIL`);
process.exit(failed === 0 ? 0 : 1);
