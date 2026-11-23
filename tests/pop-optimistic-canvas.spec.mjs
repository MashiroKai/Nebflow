// pop-optimistic-canvas.spec.mjs — Pop 文件卡「乐观打开」验收 spec
// （pop-optimistic-canvas 批 2026-11-18 · 作者令逐字「你pop的文件 我如果是在手机端
// 或者其他端查看 可能是由于没有下好的原因 我点了 并不会在canvas打开 可以做成是先乐
// 观打开在canvas占位防丢失 然后在canvas用一个环来显示加载进度」）。
//
// 分站（被测 = **真实模块链**，零 mock 替身进被测链）：
//   站1 占位时序实测：Pop 文件卡点击（popArtifacts.openPopArtifact 真发射点）经
//       workspace-open-item 进 canvas.js ⇒ tab 必须**先于** nf-file 请求返回就出现
//       （逐帧 MutationObserver 打点 + fetch 打点，给毫秒读数）；此刻取数计数 = 0。
//   站2 三态：成功（进度环 → 内容）/ 失败（可读原因 + 重试键，点了真重试 ⇒ 成功）/
//       取消（关页签 ⇒ 在飞取件**真 abort**，AbortError 打点）。
//   站3 二次打开：关掉重开同一件 ⇒ nf-file Δ=0 零取数即时显示（账本命中）。
//   站4 环形态：Content-Length 可得 ⇒ determinate + data-nf-pct；不可得 ⇒ indeterminate。
//   站5 失败分型：凭据拒绝（403 + X-Nf-Reason）/ 不存在（404）/ 离线（网络层抛）/
//       类型不支持（note）/ 超时（timeoutMs 到点）—— 五种各有可读原因，**无空白页**。
//   站6 文本 / HTML 交付件：.md 走 content；.html 不被排除面挡（Pop 交付件常是 HTML）。
//   站7 手机形态：375×812 真全屏页（mobile-layer 激活；占位页盒 ≈ 视口，无弹窗/内嵌面板）。
//   harness 纪律：/sw.js 静态 404（「引擎未重启」形态）—— SW 活体化会绕过 page.route。
//
// 运行：node tests/pop-optimistic-canvas.spec.mjs
// 退出码：0 = 全绿；1 = 有断言红；2 = 环境错误。

import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync, writeFileSync } from 'node:fs';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8161);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
const FIX = '/tmp/pop-optimistic';
const IMG_PATH = FIX + '/shot.png';
const MD_PATH = FIX + '/notes.md';
const HTML_PATH = FIX + '/anim.html';
const ZIP_PATH = FIX + '/archive.zip';
const MD_BODY = '# Pop notes\n\noptimistic open placeholder ring\n';
const HTML_BODY = '<!doctype html><html><body><h1>pop animation</h1></body></html>';
const BINARY_BODY = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02]);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  // /sw.js 必须 404（SW 活体化会绕过 page.route，见 nffile-three-tier 同款纪律）
  if (p === '/sw.js') { res.writeHead(404); res.end('nf'); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const report = {};
let fail = 0;

/** 每站可配的 nf-file 应答。 */
const DEFAULT_FILES = {
  [IMG_PATH]: { status: 200, contentType: 'image/png', body: PNG },
  [MD_PATH]: { status: 200, contentType: 'text/markdown', body: Buffer.from(MD_BODY) },
  [HTML_PATH]: { status: 200, contentType: 'text/html', body: Buffer.from(HTML_BODY) },
  [ZIP_PATH]: { status: 200, contentType: 'application/zip', body: BINARY_BODY },
};

async function bootPage(browserType, { delayMs = 0, files = DEFAULT_FILES, viewport = { width: 1600, height: 1000 }, colorScheme = 'light' } = {}) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  // 打点探针：① tab 出现帧（MutationObserver）；② /api/nf-file 请求起止；③ abort 观测。
  await ctx.addInitScript(() => {
    /** @type {any} */ (window).__po = { tabAt: null, nfStart: null, nfEnd: null, aborted: 0, t0: performance.now(), count: 0, clickAt: null };
    const of = window.fetch;
    /** @type {any} */ (window).fetch = function (input, init) {
      const u = typeof input === 'string' ? input : ((input && input.url) || '');
      const hit = u.includes('/api/nf-file');
      if (hit) {
        const p = /** @type {any} */ (window).__po;
        p.count++; if (p.nfStart == null) p.nfStart = performance.now();
      }
      const out = of.apply(this, arguments);
      if (!hit) return out;
      return Promise.resolve(out).then(
        (r) => { const p = /** @type {any} */ (window).__po; p.nfEnd = performance.now(); return r; },
        (e) => {
          const p = /** @type {any} */ (window).__po;
          p.nfEnd = performance.now();
          if (e && e.name === 'AbortError') p.aborted++;
          throw e;
        },
      );
    };
    const mo = new MutationObserver(() => {
      const p = /** @type {any} */ (window).__po;
      if (p.tabAt != null) return;
      if (document.querySelector('[data-tab-id^="file:"]')) p.tabAt = performance.now();
    });
    const arm = () => mo.observe(document.body, { childList: true, subtree: true });
    if (document.body) arm(); else document.addEventListener('DOMContentLoaded', arm);
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const path = url.searchParams.get('path') || '';
    if (delayMs) await sleep(delayMs);
    const spec = files[path];
    if (!spec) return route.fulfill({ status: 404, contentType: 'text/plain', body: 'nf' });
    return route.fulfill({ status: spec.status, contentType: spec.contentType, body: spec.body, headers: spec.headers });
  });
  const ticketMock = await installTicketMock(page);
  const cfgFrame = { type: 'configData', config: JSON.stringify({ features: {} }) };
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify(cfgFrame));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(() => !!document.getElementById('activity-bar'), null, { timeout: 20000 });
  if (viewport.width <= 768) {
    await page.waitForFunction(() => !!(/** @type {any} */ (window).__nfMobileLayer && /** @type {any} */ (window).__nfMobileLayer.active), null, { timeout: 8000 }).catch(() => {});
  }
  await sleep(500);
  return { browser, page, errors, ticketMock };
}

/** 点 Pop 文件卡（真发射点 = popArtifacts.openPopArtifact）。 */
const CLICK = (path, name) => `import('/js/popArtifacts.js').then(m => {`
  + ` const p = /** @type {any} */ (window).__po; if (p) p.clickAt = performance.now();`
  + ` return m.openPopArtifact(${JSON.stringify(path)}, ${JSON.stringify(name)}); })`;

async function state(page, id) {
  return page.evaluate((tabId) => {
    const probe = /** @type {any} */ (window).__po;
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${tabId}"]`);
    const prog = document.querySelector('[data-nf-progress]');
    const err = document.querySelector('[data-nf-error]');
    const note = document.querySelector('[data-nf-note]');
    const img = document.querySelector('.canvas-tab-pane img');
    const paneAny = /** @type {any} */ (pane);
    return {
      tabOpen: !!pane,
      canvasOpen: document.body.classList.contains('canvas-open'),
      progress: prog ? prog.getAttribute('data-nf-progress') : null,
      pct: prog ? prog.getAttribute('data-nf-pct') : null,
      errorText: err ? err.textContent : null,
      noteText: note ? note.textContent : null,
      retry: !!document.querySelector('[data-nf-retry]'),
      imgLoaded: !!img && img.complete && img.naturalWidth > 0,
      textBody: pane ? (pane.textContent || '').slice(0, 120) : '',
      clickAt: probe ? probe.clickAt : null,
      tabAt: probe ? probe.tabAt : null,
      nfStart: probe ? probe.nfStart : null,
      nfEnd: probe ? probe.nfEnd : null,
      nfCount: probe ? probe.count : -1,
      aborted: probe ? probe.aborted : -1,
      paneBox: paneAny ? { w: Math.round(paneAny.getBoundingClientRect().width), h: Math.round(paneAny.getBoundingClientRect().height) } : null,
      mobileActive: !!(/** @type {any} */ (window).__nfMobileLayer && /** @type {any} */ (window).__nfMobileLayer.active),
    };
  }, id);
}

const closeTab = (page, id) => page.evaluate((tabId) => import('/js/canvas.js').then((c) => c.closeTab(tabId)), id);

/** 等 DOM 出现（**不要求可见**——桌面标签行已退役/隐藏，可见性判据会假红）。 */
const waitDom = (page, sel, timeout = 8000) =>
  page.waitForFunction((s) => !!document.querySelector(s), sel, { timeout });

for (const [label, engine] of [['chromium', chromium], ['webkit(Safari引擎族)', webkit]]) {
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };

  // ── 站1：占位时序实测（tab 先于请求返回）+ 三态成功腿 ──
  {
    const { browser, page, errors } = await bootPage(engine, { delayMs: 1200 });
    page.evaluate(CLICK(IMG_PATH, 'shot.png'));   // 不 await：量「点击后先出占位」
    const id = 'file:' + IMG_PATH;
    await waitDom(page, `.canvas-tab-pane[data-tab-id="${id}"]`, 5000);
    const early = await state(page, id);
    t('1a 点击后立即出标签（tab-first）', early.tabOpen === true);
    // 「先于网络结果出现」的最强读数：占位在场的这一刻，nf-file **尚未返回**。
    t('1b 占位先于应答：占位在场时 nf-file 尚未返回（nfEnd == null）',
      early.nfEnd == null, `nfEnd=${early.nfEnd}`);
    t('1c 占位带进度环（data-nf-progress）', early.progress != null, `progress=${early.progress}`);
    const tabDelta = early.tabAt != null && early.clickAt != null ? early.tabAt - early.clickAt : null;
    t('1d 时序读数：tab 于点击后 <150ms 出现，且不晚于请求发起',
      tabDelta != null && tabDelta < 150 && (early.nfStart == null || early.tabAt <= early.nfStart + 60),
      `clickAt=${early.clickAt?.toFixed?.(1)} tabAt=${early.tabAt?.toFixed?.(1)} Δ=${tabDelta?.toFixed?.(1)}ms nfStart=${early.nfStart?.toFixed?.(1)}`);
    await page.waitForSelector('.canvas-tab-pane img', { timeout: 8000 });
    await sleep(300);
    const done = await state(page, id);
    t('1d2 应答迟到（≥1000ms）而 tab 早已在场 —— 请求返回**晚于**占位',
      done.nfEnd != null && done.clickAt != null && (done.nfEnd - done.clickAt) >= 1000,
      `clickAt=${done.clickAt?.toFixed?.(1)} nfEnd=${done.nfEnd?.toFixed?.(1)} Δ=${(done.nfEnd - done.clickAt)?.toFixed?.(1)}ms`);
    t('1e 成功：内容显示（img naturalWidth>0）', done.imgLoaded === true, JSON.stringify({ loaded: done.imgLoaded }));
    t('1f 取数恰一次', done.nfCount === 1, `count=${done.nfCount}`);
    t('1g 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站2b：失败可见 + 重试真重试（403 credential-denied）──
  {
    const denied = {
      [IMG_PATH]: { status: 403, contentType: 'text/plain', body: 'denied', headers: { 'X-Nf-Reason': 'credential-denied' } },
    };
    const { browser, page, errors } = await bootPage(engine, { files: denied });
    const id = 'file:' + IMG_PATH;
    await page.evaluate(CLICK(IMG_PATH, 'shot.png'));
    await page.waitForSelector('[data-nf-error]', { timeout: 8000 });
    const st = await state(page, id);
    t('2b-1 失败可见（[data-nf-error] 就地，非空白页）', !!st.errorText, String(st.errorText));
    t('2b-2 原因为「凭据判定拒绝」且可读', /无权读取|凭据/.test(st.errorText || '') && /credential-denied/.test(st.errorText || ''), String(st.errorText));
    t('2b-3 重试键在场', st.retry === true);
    // 改路 200 → 点重试 → 渲染成功（重试键走的是同一取数链）
    await page.route(/\/api\/nf-file\?/, async (route) => {
      if (await ticketGuard(route)) return;
      await sleep(40);
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    });
    await page.click('[data-nf-retry]');
    await page.waitForSelector('.canvas-tab-pane img', { timeout: 8000 });
    await sleep(250);
    const ok = await state(page, id);
    t('2b-4 点了真重试 ⇒ 渲染成功', ok.imgLoaded === true, JSON.stringify({ loaded: ok.imgLoaded }));
    t('2b-5 页面零 JS 异常（失败腿不抛）', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站2c：取消（关页签 ⇒ 在飞取件真 abort）──
  {
    const { browser, page, errors } = await bootPage(engine, { delayMs: 2500 });
    const id = 'file:' + IMG_PATH;
    await page.evaluate(CLICK(IMG_PATH, 'shot.png'));
    await waitDom(page, `.canvas-tab-pane[data-tab-id="${id}"]`, 5000);
    // 请求已发出（在飞）后关页签
    await page.waitForFunction(() => /** @type {any} */ (window).__po.nfStart != null, null, { timeout: 5000 });
    await closeTab(page, id);
    await sleep(600);
    const st = await state(page, id);
    t('2c-1 关页签后取件被 abort（AbortError 打点 ≥1）', st.aborted >= 1, `aborted=${st.aborted}`);
    t('2c-2 页签确已关闭、无残留占位', st.tabOpen === false);
    t('2c-3 页面零 JS 异常（取消不抛）', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站3：二次打开走缓存（Δ 取数 = 0）──
  {
    const { browser, page, errors } = await bootPage(engine, { delayMs: 0 });
    const id = 'file:' + IMG_PATH;
    await page.evaluate(CLICK(IMG_PATH, 'shot.png'));
    await page.waitForSelector('.canvas-tab-pane img', { timeout: 8000 });
    await sleep(300);
    const first = await state(page, id);
    await closeTab(page, id);
    await sleep(400);
    const before = first.nfCount;
    await page.evaluate(CLICK(IMG_PATH, 'shot.png'));
    await page.waitForSelector('.canvas-tab-pane img', { timeout: 8000 });
    await sleep(250);
    const second = await state(page, id);
    t('3a 二次打开零取数（nf-file Δ=0）', second.nfCount - before === 0, `Δ=${second.nfCount - before}`);
    t('3b 二次打开即时显示内容', second.imgLoaded === true);
    t('3c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站4：环形态（Content-Length 可得 ⇒ determinate；不可得 ⇒ indeterminate）──
  {
    const { browser, page, errors } = await bootPage(engine, {});
    const modes = await page.evaluate(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const ap = await import('/js/attachmentPreview.js');
      const blob = new Blob([new Uint8Array(16)], { type: 'image/png' });
      const mk = (mode) => ap.previewWithProgress({
        id: 'po:ring', title: 'r.png', fileName: 'r.png', allowExcluded: true,
        load: ({ onProgress }) => new Promise((resolve) => {
          if (mode === 'det') onProgress(40, 100); else onProgress(9, 0);
          setTimeout(() => resolve({ blob }), 320);
        }),
      });
      const p1 = mk('det');
      await wait(80);
      const det = (() => { const e = document.querySelector('[data-nf-progress]'); return { mode: e?.getAttribute('data-nf-progress'), pct: e?.getAttribute('data-nf-pct') }; })();
      await p1;
      const c = await import('/js/canvas.js');
      c.closeTab('po:ring');
      await wait(340);
      const p2 = mk('ind');
      await wait(80);
      const ind = (() => { const e = document.querySelector('[data-nf-progress]'); return { mode: e?.getAttribute('data-nf-progress'), pct: e?.getAttribute('data-nf-pct') }; })();
      await p2;
      return { det, ind };
    });
    t('4a Content-Length 可得 ⇒ determinate 40%', modes.det.mode === 'determinate' && modes.det.pct === '40', JSON.stringify(modes.det));
    t('4b 不可得 ⇒ indeterminate（转圈）', modes.ind.mode === 'indeterminate' && modes.ind.pct == null, JSON.stringify(modes.ind));
    t('4c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站5：失败分型（404 / 离线 / 类型不支持 / 超时）──
  {
    const { browser, page, errors } = await bootPage(engine, {});
    const out = await page.evaluate(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const ap = await import('/js/attachmentPreview.js');
      const nf = await import('/js/nfTicket.js');
      const fc = await import('/js/nfFileCache.js');
      const c = await import('/js/canvas.js');
      const read = () => {
        const e = document.querySelector('[data-nf-error]');
        const n = document.querySelector('[data-nf-note]');
        return { err: e ? e.textContent : null, note: n ? n.textContent : null, retry: !!document.querySelector('[data-nf-retry]') };
      };
      const realLoad = (path) => async ({ onProgress, signal }) => {
        const res = await fc.fetchBlobWithProgress(await nf.ticketUrl(path), onProgress, { signal });
        return { blob: res.blob, version: res.version };
      };
      // 404（路径不在 mock 表里）
      const r404 = await ap.previewWithProgress({ id: 'po:404', title: 'missing.png', fileName: 'missing.png', allowExcluded: true, load: realLoad('/tmp/pop-optimistic/missing.png') });
      const s404 = read(); c.closeTab('po:404'); await wait(340);
      // 类型不支持（.zip + 二进制内容 ⇒ 无 viewer 认领 + 非文本）
      const rZip = await ap.previewWithProgress({ id: 'po:zip', title: 'archive.zip', fileName: 'archive.zip', allowExcluded: true, load: realLoad('/tmp/pop-optimistic/archive.zip') });
      const sZip = read(); c.closeTab('po:zip'); await wait(340);
      // 离线（fetch 网络层抛 TypeError）
      const rOff = await ap.previewWithProgress({ id: 'po:off', title: 'off.png', fileName: 'off.png', allowExcluded: true, load: async () => { throw Object.assign(new TypeError('Failed to fetch'), {}); } });
      const sOff = read(); c.closeTab('po:off'); await wait(340);
      // 超时（load 永不应答）
      const rTo = await ap.previewWithProgress({ id: 'po:to', title: 'slow.png', fileName: 'slow.png', allowExcluded: true, timeoutMs: 250, load: () => new Promise(() => {}) });
      const sTo = read(); c.closeTab('po:to'); await wait(340);
      return { r404, s404, rZip, sZip, rOff, sOff, rTo, sTo };
    });
    t('5a 404 ⇒ 可读「文件不存在/已被清理」+ 重试', out.r404 === 'failed' && /不存在|清理/.test(out.s404.err || '') && out.s404.retry, JSON.stringify(out.s404));
    t('5b 类型不支持 ⇒ 可见 note + 重试（非空白/非死文案）', out.rZip === 'unsupported' && !!out.sZip.note && out.sZip.retry, JSON.stringify(out.sZip));
    t('5c 离线 ⇒ 可读「网络不可用（离线）」', out.rOff === 'failed' && /离线|网络不可用/.test(out.sOff.err || ''), JSON.stringify(out.sOff));
    t('5d 超时 ⇒ 可读「取数超时」+ 重试（不永远转圈）', out.rTo === 'failed' && /超时/.test(out.sTo.err || '') && out.sTo.retry, JSON.stringify(out.sTo));
    t('5e 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站6：文本交付件（.md 走 content；.html 不被排除面挡）──
  {
    const { browser, page, errors } = await bootPage(engine, {});
    await page.evaluate(CLICK(MD_PATH, 'notes.md'));
    await waitDom(page, '.canvas-tab-pane', 8000);
    await sleep(900);
    const md = await state(page, 'file:' + MD_PATH);
    t('6a .md 文本交付件渲染出正文', /Pop notes/.test(md.textBody || ''), (md.textBody || '').slice(0, 60));
    // 6a2/6a3 —— 作者 2026-11-21 11:41 报障的**两态覆盖**（card-visible-reconcile 批）：
    // 修前 .md 打开后正文已完整渲染，但「正在打开…」占位仍悬浮（转圈不收）。根因 =
    // markdown viewer 不清 pane（viewers/markdown.js:1401-1424 只 remove 自己那类旧面
    // 再 appendChild），占位 `.nf-progress-pane` 于是与正文并排同屏。修法 = 占位生命
    // 周期挂到「真容出现」（progressPane 自撤 + fileViewers.renderFile 单点兜底）。
    //   6a2 完成态解除：真容在场 ⇒ 占位必须已撤（两态之「加载中→完成解除」）；
    //   6a3 反证：真容在场时不得同时留占位（「内容已出还转圈」这一形态必红）。
    t('6a2 .md 渲染完成后「正在打开…」占位已撤（loading→完成 解除）',
      md.progress == null, `progress=${md.progress}`);
    const bothPresent = await page.evaluate((tabId) => {
      // 必须按 **tab id** 定位 pane：document.querySelector('.canvas-tab-pane') 会
      // 命中侧栏常驻的 plugins 面板（它是首个 .canvas-tab-pane，永无 md 面），
      // 断言就会拿错 pane 判对错（本断言首版即栽在此）。
      const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${tabId}"]`);
      if (!pane) return null;
      return {
        hasProgress: !!pane.querySelector('[data-nf-progress]'),
        mdScrollers: pane.querySelectorAll(':scope > .canvas-md-scroll').length,
        text: (pane.textContent || '').slice(0, 40),
      };
    }, 'file:' + MD_PATH);
    t('6a3 完成态不得「正文与占位并存」（内容已出还转圈必红）',
      !!bothPresent && bothPresent.hasProgress === false && bothPresent.mdScrollers >= 1,
      JSON.stringify(bothPresent));
    await page.evaluate(CLICK(HTML_PATH, 'anim.html'));
    await page.waitForFunction(() => !!document.querySelector('.canvas-tab-pane iframe'), null, { timeout: 8000 });
    await sleep(500);
    const htmlOk = await page.evaluate(() => {
      const f = document.querySelector('.canvas-tab-pane iframe');
      try { return !!(f && f.contentDocument && /pop animation/.test(f.contentDocument.body.textContent || '')); }
      catch { return false; }
    });
    t('6b .html 交付件不被排除面挡（iframe 渲染真容）', htmlOk === true);
    t('6c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站6b：兜底路径（作者令「完成但解除失败 → 兜底」两态之第二态）──
  // 不依赖占位自身的 MutationObserver（该臂可能被环境禁用/被 viewer 覆写 pane 跳过），
  // 直接验证**渲染单点**的显式出口：手工挂一个占位，再走一次真实 renderFile，
  // 真容挂出后占位必须被撤（fileViewers.renderFile → dismissProgressPane）。
  {
    const { browser, page, errors } = await bootPage(engine, {});
    await page.evaluate(CLICK(MD_PATH, 'notes.md'));
    await waitDom(page, '.canvas-tab-pane', 8000);
    await sleep(900);
    // 用真实单点重演：先手工塞回一个占位（模拟「解除失败」的现场），再 re-render。
    const fallen = await page.evaluate(async (tabId) => {
      const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${tabId}"]`);
      if (!pane) return { err: 'no pane' };
      const pp = await import('/js/progressPane.js');
      pp.renderProgressPane(pane, '正在打开…');
      const had = !!pane.querySelector('[data-nf-progress]');
      // 兜底路径：渲染单点被再次调用 ⇒ 占位必须撤（不新增第二个环体系）。
      const fv = await import('/js/fileViewers.js');
      await fv.renderFile(pane, { itemType: 'markdown', content: '# Pop notes\n\nagain\n', fileName: 'notes.md', size: 20 });
      return { had, after: !!pane.querySelector('[data-nf-progress]'), text: (pane.textContent || '').slice(0, 40) };
    }, 'file:' + MD_PATH);
    t('6d 兜底路径：renderFile 完成后占位必撤（不靠观察器也解解除）',
      fallen && fallen.had === true && fallen.after === false, JSON.stringify(fallen));
    t('6e 兜底路径零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站7：手机形态 375×812（真全屏页；暗亮两档）──
  for (const scheme of ['light', 'dark']) {
    const { browser, page, errors } = await bootPage(engine, { delayMs: 900, viewport: { width: 375, height: 812 }, colorScheme: scheme });
    page.evaluate(CLICK(IMG_PATH, 'shot.png'));
    const id = 'file:' + IMG_PATH;
    await waitDom(page, `.canvas-tab-pane[data-tab-id="${id}"]`, 6000);
    const st = await state(page, id);
    t(`7a[${scheme}] 手机层已激活（body.mobile-layer）`, st.mobileActive === true && st.canvasOpen === true, JSON.stringify({ m: st.mobileActive, c: st.canvasOpen }));
    t(`7b[${scheme}] 占位页盒 ≈ 视口（真全屏页 375×812）`,
      !!st.paneBox && st.paneBox.h >= 700 && st.paneBox.w >= 350, JSON.stringify(st.paneBox));
    t(`7c[${scheme}] 占位带进度环（点击后先出占位）`, st.progress != null, String(st.progress));
    await page.waitForSelector('.canvas-tab-pane img', { timeout: 8000 });
    await sleep(250);
    const done = await state(page, id);
    t(`7d[${scheme}] 成功渲染 + 零 JS 异常`, done.imgLoaded === true && errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  report[label] = rows;
}

server.close();
for (const [engine, rows] of Object.entries(report)) {
  console.log(`\n== ${engine} ==`);
  for (const [name, verdict] of Object.entries(rows)) {
    console.log(`  ${verdict.startsWith('PASS') ? 'PASS' : 'FAIL'}  ${name}${verdict.startsWith('PASS') ? '' : '  ' + verdict.slice(5)}`);
    if (!verdict.startsWith('PASS')) fail++;
  }
}
const total = Object.values(report).flat().length;
console.log(`\n${total} checks, ${fail} failed`);
process.exit(fail ? 1 : 0);
