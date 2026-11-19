// nffile-three-tier.spec.mjs — imgdoor #542 三档加速验收 spec。
//
// 作者令 2026-10-20 逐字：「1.本机文件打开应该很快。2.已点击下载过的文件（存在本机），
// 应该也打开要很快。慢的应该是非本机文件，点击有一个下载过程，但是也应该立即在
// canvas显示标签页，然后标签页显示一个圈作为下载进度显示。」
//
// 分站（被测 = **真实模块链**，零 mock 替身进被测链）：
//   站1 档1+档3 tab-first：nf-file 慢路（1.2s 延迟）⇒ 点击后**先**出标签+进度面板、
//       **后**收字节（请求数在面板出现时刻 = 0），完成即渲染（img naturalWidth>0）。
//   站2 档2 缓存命中：关标签重开同一件 ⇒ **零取数**（nf-file 计数 Δ=0）零铸票直渲染。
//   站3 档3 失败可见：nf-file 500 ⇒ 标签内 [data-nf-error] + 重试键；改回 200 点重试
//       ⇒ 渲染成功（messages.js:2588 族静默降级的可见化替身）。
//   站4 进度环形态：onProgress(loaded,total) → determinate + data-nf-pct；
//       total 缺席 → indeterminate（转圈）。
//   站5 票据计数（N 图 ≤ 1 铸票）：同帧 N 路径 queuedTicketUrl/mintTicketsQueued
//       ⇒ 1 次 POST /api/nf-ticket 携 N 路径；ticketUrl 复用 ⇒ 0 追加。
//   站6 档2 LRU+配置：maxBytes 预算内淘汰（configData 注入 nebflow.json 键）。
//   站7 接线钉：messages.js 三处消费点（device blob 腿 / 好友腿 / 设备内联合帧）源码钉。
//   站8 #538 候选吸收钉：NfFileRoutes.scala 缓存头源码钉（引擎契约回归在 Scala spec）。
//   站9 设置面板清缓存入口（M2 · 设计 v1 §6.4/§7 表5）：占用读数入画（nfCacheStats
//       seam，分桶明细入 title）+ 清空键两段式确认（nfCacheReset seam，同
//       btn-restart-gateway 先例）⇒ 账本真空 + 行为缺失实证；static 附接线钉
//       （sidebar.js seam 单源 / 占位双件 / 两段式 / locale 双语键组）。
//   harness 纪律：/sw.js 静态 404（「引擎未重启」形态）——SW 活体化会绕过
//       page.route mock（97fd9e274 存量红根因，见 server 内注）。
//
// 运行：node tests/nffile-three-tier.spec.mjs

import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8153);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 真实可解码 1×1 PNG（imgcard 同款字节）
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
const IMG_PATH = '/tmp/imgdoor542/shot.png';
import { mkdirSync, writeFileSync } from 'node:fs';
try { mkdirSync('/tmp/imgdoor542', { recursive: true }); writeFileSync(IMG_PATH, PNG); } catch { /* 已在 */ }

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  // /sw.js 必须 404：harness = 「引擎未重启」形态（swReg.js 自述口径——注册被拒 ⇒
  // 静默空转零影响）。97fd9e274（控制台加载提速 A 段）把 sw.js 放进 web 目录后，
  // 静态服务器会 200 它 ⇒ SW 注册成功并接管 fetch ⇒ **绕过 page.route mock**
  // （nf-ticket POST/nf-file GET 直落静态服务器 404）——本 spec 站1-6 存量红的根因。
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

async function bootPage(browserType, { nfFileDelayMs = 0, nfFileStatus = 200, cacheConfig = null } = {}) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  /** nf-file 兑换读数：只在**出字节**时计（= 渲染吃到了那次取数的字节）。 */
  let nfFileCount = 0;
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    if (nfFileDelayMs) await sleep(nfFileDelayMs);
    nfFileCount++;
    if (nfFileStatus !== 200) {
      return route.fulfill({ status: nfFileStatus, contentType: 'text/plain', body: 'mock nf-file failure' });
    }
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
  });
  const ticketMock = await installTicketMock(page);
  // configData 契约（main.js:2256）：`msg.config` = nebflow.json 原文的**字符串**。
  const cfgObj = { features: {}, ...(cacheConfig ? { nfFileCache: cacheConfig } : {}) };
  const cfgFrame = { type: 'configData', config: JSON.stringify(cfgObj) };
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify(cfgFrame));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(400);
  return { browser, page, errors, nfFile: { count: () => nfFileCount }, ticketMock };
}

/** messages.js 同款 load 闭包（设备 path 腿的真实字节链：ticketUrl ⇒ 流式读出器）。 */
const LOAD_SNIPPET = async (path) => {
  const ap = await import('/js/attachmentPreview.js');
  const nf = await import('/js/nfTicket.js');
  const fc = await import('/js/nfFileCache.js');
  return ap.previewWithProgress({
    id: 'probe:img', title: 'shot.png', fileName: 'shot.png',
    cacheKey: 'nf:' + path,
    load: async ({ onProgress }) => {
      const res = await fc.fetchBlobWithProgress(await nf.ticketUrl(path), onProgress);
      return { blob: res.blob, version: res.version };
    },
  });
};

async function viewerState(page) {
  return page.evaluate(() => {
    const viewer = document.querySelector('.canvas-image-viewer');
    const img = viewer ? viewer.querySelector('img') : null;
    const prog = document.querySelector('[data-nf-progress]');
    const err = document.querySelector('[data-nf-error]');
    return {
      viewerInDom: !!viewer,
      imgLoaded: !!img && img.complete && img.naturalWidth > 0,
      progress: prog ? prog.getAttribute('data-nf-progress') : null,
      pct: prog ? prog.getAttribute('data-nf-pct') : null,
      error: !!err,
      retryBtn: !!document.querySelector('[data-nf-retry]'),
      tabOpen: !!document.querySelector('[data-tab-id="probe:img"]'),
    };
  });
}

for (const [label, engine] of [['chromium', chromium], ['webkit(Safari引擎族)', webkit]]) {
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };

  // ── 站1：档1+档3 tab-first（慢路 1.2s）──
  {
    const { browser, page, errors, nfFile, ticketMock } = await bootPage(engine, { nfFileDelayMs: 1200 });
    const pending = page.evaluate(LOAD_SNIPPET, IMG_PATH); // 不 await：量「点击后先出标签」
    await page.waitForSelector('[data-nf-progress]', { timeout: 4000 });
    const early = await viewerState(page);
    t('1a 点击后立即出标签（tab-first）', early.tabOpen === true);
    t('1b 标签先于字节：面板在场时零取数', nfFile.count() === 0, `count=${nfFile.count()}`);
    t('1c 进度面板在场', !!early.progress, `progress=${early.progress}`);
    t('1d 零铸票（缓存在前）或恰一次', ticketMock.count() <= 1, `mints=${ticketMock.count()}`);
    const result = await pending;
    await page.waitForSelector('.canvas-image-viewer', { timeout: 4000 });
    await sleep(250);
    const done = await viewerState(page);
    t('1e 完成即渲染（img naturalWidth>0）', done.imgLoaded === true, JSON.stringify(done));
    t('1f 取数恰一次', nfFile.count() === 1, `count=${nfFile.count()}`);
    t('1g 返回 ok', result === 'ok', String(result));
    t('1h 页面零 JS 异常', errors.length === 0, errors.join(' | '));

    // ── 站2：档2 缓存命中（关标签重开同一件）──
    await page.evaluate(async () => { const c = await import('/js/canvas.js'); c.closeTab('probe:img'); });
    await sleep(300);
    const before = { nf: nfFile.count(), mint: ticketMock.count() };
    const r2 = await page.evaluate(LOAD_SNIPPET, IMG_PATH); // 同 id 重开：标签已关 ⇒ 走完整链
    await sleep(300);
    const done2 = await viewerState(page);
    t('2a 零取数（nf-file Δ=0）', nfFile.count() - before.nf === 0, `Δ=${nfFile.count() - before.nf}`);
    t('2b 零追加铸票', ticketMock.count() - before.mint === 0, `Δ=${ticketMock.count() - before.mint}`);
    t('2c 命中直渲染（img loaded）', r2 === 'ok' && done2.imgLoaded === true, JSON.stringify(done2));
    t('2d 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站3：档3 失败可见 + 重试（nf-file 500）──
  {
    const { browser, page, errors } = await bootPage(engine, { nfFileStatus: 500, nfFileDelayMs: 80 });
    const r = await page.evaluate(LOAD_SNIPPET, IMG_PATH);
    await sleep(350);
    const st = await viewerState(page);
    t('3a 失败可见（[data-nf-error] 就地）', r === 'failed' && st.error === true, JSON.stringify(st));
    t('3b 重试键在场', st.retryBtn === true);
    // 改路 200 → 点重试 → 渲染成功（新路由注册在后者优先）
    await page.route(/\/api\/nf-file\?/, async (route) => {
      if (await ticketGuard(route)) return;
      await sleep(60);
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
    });
    await page.click('[data-nf-retry]');
    await page.waitForSelector('.canvas-image-viewer', { timeout: 4000 });
    await sleep(250);
    const ok = await viewerState(page);
    t('3c 重试后渲染成功', ok.imgLoaded === true, JSON.stringify(ok));
    t('3d 页面零 JS 异常（失败腿不抛）', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站4：进度环形态（fake load 直驱 onProgress 契约）──
  {
    const { browser, page, errors } = await bootPage(engine);
    const states = await page.evaluate(async (pngB64) => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const ap = await import('/js/attachmentPreview.js');
      const bytes = Uint8Array.from(atob(pngB64), (c) => c.charCodeAt(0));
      const mk = (mode) => ap.previewWithProgress({
        id: 'probe:img', title: 'shot.png', fileName: 'shot.png',
        load: ({ onProgress }) => new Promise((resolve) => {
          if (mode === 'det') onProgress(40, 100);
          else onProgress(30, 0); // total 缺席 → 不定态
          setTimeout(() => resolve({ blob: new Blob([bytes], { type: 'image/png' }) }), 260);
        }),
      });
      const detP = mk('det');
      await wait(80);
      const det = await (async () => {
        const viewer = document.querySelector('[data-nf-progress]');
        return { mode: viewer?.getAttribute('data-nf-progress'), pct: viewer?.getAttribute('data-nf-pct') };
      })();
      await detP;
      // 不定态（重新开同一 id 前先关）
      const c = await import('/js/canvas.js');
      c.closeTab('probe:img');
      await wait(320); // closeTab 走 200ms 退出动画（transitionend/timeout finalize）后才摘 tabs 表项
      const indP = mk('ind');
      await wait(80);
      const ind = await (async () => {
        const viewer = document.querySelector('[data-nf-progress]');
        return { mode: viewer?.getAttribute('data-nf-progress') };
      })();
      await indP;
      return { det, ind };
    }, PNG.toString('base64'));
    t('4a Content-Length 可得 → 百分比环 40%', states.det.mode === 'determinate' && states.det.pct === '40', JSON.stringify(states.det));
    t('4b 不可得 → 转圈（indeterminate）', states.ind.mode === 'indeterminate', JSON.stringify(states.ind));
    t('4c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站5：票据计数（N 图 ≤ 1 铸票）──
  {
    const { browser, page, ticketMock, errors } = await bootPage(engine);
    const mint = await page.evaluate(async (path) => {
      const nf = await import('/js/nfTicket.js');
      nf.resetTickets(); nf.resetBatchQueue();
      const paths = [path, path + 'b.png', path + 'c.png'];
      const [u1, u2, u3] = await Promise.all(paths.map((p) => nf.queuedTicketUrl(p)));
      const u1again = await nf.ticketUrl(path); // 同路径复用 ⇒ 0 追加
      return {
        gotAll: [u1, u2, u3].every((u) => /[?&]ticket=/.test(u)),
        urlsDistinct: new Set([u1, u2, u3]).size === 3,
        reuseHasTicket: /[?&]ticket=/.test(u1again),
      };
    }, IMG_PATH).catch((e) => ({ error: String(e) }));
    const calls = ticketMock.calls;
    t('5a N=3 图同帧 ≤ 1 铸票', calls.length === 1, `posts=${calls.length}`);
    t('5b 一次铸票携带全部 N 路径', calls[0] && calls[0].length === 3, JSON.stringify(calls));
    t('5c 三 URL 全部带票', mint.gotAll === true, JSON.stringify(mint));
    t('5d 同路径复用零追加铸票', calls.length === 1, `posts=${calls.length}`);
    t('5e 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站6：档2 LRU + 配置（nebflow.json 键经 configData 注入）──
  {
    const { browser, page, errors } = await bootPage(engine, { cacheConfig: { maxBytes: 1048576 } });
    const lru = await page.evaluate(async () => {
      const fc = await import('/js/nfFileCache.js');
      await fc.nfCacheReset();
      const mk = (kb) => new Blob([new Uint8Array(kb * 1024).fill(7)], { type: 'application/octet-stream' });
      await fc.nfCachePut('k1', mk(400), 'v1');
      await fc.nfCachePut('k2', mk(400), 'v2');
      await fc.nfCachedBlob('k1'); // LRU touch k1 ⇒ 最久未用 = k2
      await fc.nfCachePut('k3', mk(400), 'v3'); // 总 1.2MB > 1MB ⇒ 淘 k2
      const hitK1 = !!(await fc.nfCachedBlob('k1'));
      const hitK2 = !!(await fc.nfCachedBlob('k2'));
      const hitK3 = !!(await fc.nfCachedBlob('k3'));
      const stats = fc.nfCacheStats();
      return { hitK1, hitK2, hitK3, stats };
    });
    t('6a 预算内淘汰最久未用（k2 出局）', lru.hitK1 === true && lru.hitK2 === false && lru.hitK3 === true, JSON.stringify(lru));
    t('6b 配置生效（maxBytes=1MiB 读回）', lru.stats.maxBytes === 1048576, JSON.stringify(lru.stats));
    t('6c 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }

  // ── 站9：设置面板清缓存入口（M2 · 设计 v1 §6.4/§7 表5）──
  // 占用读数（nfCacheStats seam）+ 清空键（nfCacheReset seam，两段式确认）。被测链 =
  // 真实 sidebar.js renderSettings/bindSettingsEvents（零 mock 替身）；locale 经
  // addInitScript 钉 zh-CN（读数文案「1 条 · 64 KB」确定性断言）。
  {
    const { browser, page, errors } = await bootPage(engine);
    const seeded = await page.evaluate(async () => {
      const fc = await import('/js/nfFileCache.js');
      await fc.nfCacheReset();
      await fc.nfCachePut('k9seed', new Blob([new Uint8Array(64 * 1024).fill(7)], { type: 'application/octet-stream' }), 'v9');
      return fc.nfCacheStats();
    });
    t('9a 前置：账本已入账一条（entries=1, bytes=64KiB）', seeded.entries === 1 && seeded.bytes === 64 * 1024, JSON.stringify(seeded));
    await page.click('#settings-btn');
    await page.waitForSelector('#nf-cache-usage', { timeout: 8000 });
    const usage0 = await page.textContent('#nf-cache-usage');
    t('9b 占用读数入画（1 条 · 64 KB）', usage0 && /1\s*条\s*·\s*64\s*KB/.test(usage0), String(usage0));
    const title0 = await page.getAttribute('#nf-cache-usage', 'title');
    t('9c 分桶明细入 title（local/预算）', !!title0 && /local \d+ [KM]B\/128\.0 MB/.test(title0), String(title0));
    // 第一段：进入确认位
    await page.click('#btn-clear-nf-cache');
    const armed = await page.textContent('#btn-clear-nf-cache');
    t('9d 第一击 = 确认位（确认清空？）', armed && armed.includes('确认清空'), String(armed));
    // 第二段 = 确认位动作：清空落地
    await page.click('#btn-clear-nf-cache');
    await page.waitForFunction(() => document.getElementById('nf-cache-usage')?.textContent?.includes('0 条'), null, { timeout: 8000 });
    const usage1 = await page.textContent('#nf-cache-usage');
    t('9e 清空后读数归「0 条 · 0 KB」', usage1 && /0\s*条\s*·\s*0\s*KB/.test(usage1), String(usage1));
    const after = await page.evaluate(async () => {
      const fc = await import('/js/nfFileCache.js');
      const stats = fc.nfCacheStats();
      const blob = await fc.nfCachedBlob('k9seed');
      return { entries: stats.entries, bytes: stats.bytes, hit: !!blob };
    });
    t('9f 账本真空（entries=0, bytes=0）', after.entries === 0 && after.bytes === 0, JSON.stringify(after));
    t('9g 行为缺失：下轮打开走重新取数', after.hit === false, JSON.stringify(after));
    t('9h 页面零 JS 异常', errors.length === 0, errors.join(' | '));
    await browser.close();
  }
  report[label] = rows;
}

// ── 站7：messages.js 三处消费点源码钉（接线回归钉，零浏览器）──
{
  const rows = {};
  const src = readFileSync(join(WEB_DIR, 'js/messages.js'), 'utf8');
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  t('7a 设备 path 腿走 previewWithProgress（流式开）', /previewWithProgress\(\{[^}]*cacheKey:\s*`nf:\$\{s\.path\}`/.test(src));
  t('7b 好友腿走 previewWithProgress（attach:<id> 缓存键）', /cacheKey:\s*`attach:\$\{att\.id\}`/.test(src));
  t('7c 设备内联腿走合帧铸票（queuedTicketUrl）', /queuedTicketUrl\(s\.path\)/.test(src));
  t('7d 静默回落腿已移除（预览路径 previewLocalPath 只剩文本腿一处）', (src.match(/previewLocalPath\(\{ path: s\.path, title \}\)/g) || []).length === 1);
  t('7e 文本腿逐字保留（previewLocalPath 仍在场）', /canFetchLocalBytes\(s\.path\)\) return previewLocalPath/.test(src));
  // #538 结论①「本机件不得误走下载腿」的接线钉：设备 path 腿字节源 = 票据路
  // （ticketUrl ⇒ /api/nf-file，引擎本机直读），好友鉴权路由只属好友/群腿。
  // 切片止于下载键腿的 JSDoc 起始（其块注释提到 `downloadAttachment`，非代码）。
  const deviceLeg = src.slice(
    src.indexOf('async function previewDeviceSavedAttachment'),
    src.indexOf('/** 设备面（legacy）下载键')
  );
  t('7f 本机件走本机票据路（不误走好友下载腿）', /ticketUrl\(s\.path\)/.test(deviceLeg) && !/downloadAttachment/.test(deviceLeg));
  report['static(messages.js 接线钉)'] = rows;
}

// ── 站8：#538 候选吸收钉（nf-file 缓存头，引擎面）──
// 测量批 nf-file-cache-header.patch（evidence/20261019_imgslow538-timing/）由本批
// 裁量吸收：200/206/304 ⇒ Cache-Control: private, max-age=300（远小于票据 TTL，
// 凭据不可能活过缓存）；401/403/400 ⇒ no-store（拒绝响应永不入库）。真正的契约
// 回归 = Scala 侧 NfFileRoutesSpec（本批运行，输出入 evidence）；此处源码钉保证
// 前端树与该地基不脱钩。
{
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  const scala = readFileSync(join(WEB_DIR, '../../../main/scala/nebflow/gateway/NfFileRoutes.scala'), 'utf8');
  t('8a 200/206/304 腿携 private, max-age=300', /private val NfCacheControl: String = "private, max-age=300"/.test(scala));
  t('8b 凭据拒绝腿 no-store（401 缺票腿）', /putHeaders\(cacheHeader\("no-store"\)\)/.test(scala));
  t('8c 缓存头只挂可缓存正腿（404 腿不动）', /if r\.status == Status\.Ok \|\| r\.status == Status\.NotModified then/.test(scala));
  report['static(NfFileRoutes.scala 缓存头钉)'] = rows;
}

// ── 站9（static）：设置面板清缓存入口接线钉（M2 · v1 §6.4/§7 表5）──
{
  const rows = {};
  const t = (name, cond, extra = '') => { rows[name] = cond ? 'PASS' : `FAIL${extra ? ' — ' + extra : ''}`; };
  const src = readFileSync(join(WEB_DIR, 'js/sidebar.js'), 'utf8');
  const zh = readFileSync(join(WEB_DIR, 'js/locales/zh-CN.js'), 'utf8');
  const en = readFileSync(join(WEB_DIR, 'js/locales/en.js'), 'utf8');
  t('9p1 sidebar.js 接线 seam 单源（nfCacheStats/nfCacheReset ← nfFileCache.js）', /import\s*\{\s*nfCacheStats,\s*nfCacheReset\s*\}\s*from\s*'\.\/nfFileCache\.js'/.test(src));
  t('9p2 高级区渲染占位双件（#nf-cache-usage + #btn-clear-nf-cache）', src.includes('id="nf-cache-usage"') && src.includes('id="btn-clear-nf-cache"'));
  t('9p3 两段式确认（btn-restart-gateway 先例：确认文案键 + 5s 自动复位）', /fileCacheClearConfirm/.test(src) && /setTimeout\(cacheDisarm,\s*5000\)/.test(src));
  t('9p4 locale 双语键组齐备（zh/en 各 4 键）', ['fileCache', 'fileCacheClear', 'fileCacheClearConfirm', 'fileCacheEntries']
    .every(k => zh.includes(`'settings.${k}'`) && en.includes(`'settings.${k}'`)));
  report['static(设置清缓存入口接线钉)'] = rows;
}

server.close();
for (const [engine, rows] of Object.entries(report)) {
  console.log(`\n== ${engine} ==`);
  for (const [name, verdict] of Object.entries(rows)) {
    console.log(`  ${verdict.startsWith('PASS') ? 'PASS' : 'FAIL'}  ${name}${verdict.startsWith('PASS') ? '' : '  ' + verdict.slice(5)}`);
    if (!verdict.startsWith('PASS')) fail++;
  }
}
console.log(`\n${Object.values(report).flat().length} checks, ${fail} failed`);
process.exit(fail ? 1 : 0);
