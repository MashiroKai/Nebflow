#!/usr/bin/env node
// ask-image-ticket.spec.mjs — Ask 回答正文相对路径图片走票据 + 迟到结果竞争闸
// 验收 spec（ask-image-ticket 批 2026-11-24 · 作者报「Ask 回答正文相对路径图片
// 看不了」＝相对路径按页面 URL 解析 → `/attachments/<目录>/x.png` 404 text/plain）。
//
// 被测 = **真实模块链**（askDialog/askDock/mdImagePass/nfTicket/nfFileCache/
// viewers/markdown/canvas/popArtifacts），零 mock 替身进被测链；本夹具只提供
// HTTP 面（静态 web 根 + /api/nf-ticket + /api/nf-file）与 WS 面（configData/
// sessionList/askOpen 回声；textDelta/done 由 Node 侧注入）。
//
// 分站：
//   S1  Pop 文档卡重开（d6e1a349b 断言）：正文 + 图的旧票 403 ⇒ 必须自愈——
//       文档体 reMint 后 200，正文图片仍走票据并 200（基点 9c70f278 红：
//       乐观打开 403 直抛 ⇒ 错误面、无正文无图）。
//   S2  Ask 回答正文相对路径图片（主修）：
//       (a) 同目录 `fig_a.png`；(b) 子目录 `attachments/hi/fig_b.png` ——
//           以**所问文档所在目录**为基准铸票取字节，图成（naturalWidth>0）、
//           /api/nf-file 200、零页面相对 404 请求；
//       (c) 无文档上下文（选区档形态：门面直造、无 docPath）⇒ 明确占位
//           （data-nf-md-img-nodoc）、零网络、不抛错。
//   S3  迟到结果竞争（任务书「流式重渲多次 ⇒ 迟到结果不得回填」）：
//       (i) 铸票在飞时 askState 重渲替换回答（图被移除）⇒ 迟到 pass 弃写：
//           终态零图、零页面相对请求、零对已弃图的字节请求；
//       (ii) 铸票在飞时切换文档（A→B）⇒ A 的迟到结果不写入 B 的列、不对 A 的
//            图取字节；B 随后的回答以 **B 所在目录**为基准出图。
//
// 夹具纪律：vault 在 /tmp 自建（`/tmp/ask-image-ticket/qa/.stand/docs/`），
// 图片为本仓最小 PNG 字节——不读生产数据根；/sw.js 静态 404（SW 活体化会绕过
// page.route，pop-optimistic 同款纪律）；所有等待有界。
// WebKit mermaid SyntaxError 已定性为**夹具 charset 假象**（2026-11-24 核证令：
// mermaid.min.js tip/基点字节同一；夹具带 charset（本 spec 服务器对 .html/.js 一律
// charset=utf-8）⇒ WebKit 零异常；摘掉 charset+meta ⇒ gb18030 解码把 ASCII 破坏成
// 「Invalid regular expression: unmatched parentheses」@mermaid.min.js:258）。本
// 夹具恒带 charset ⇒ 「页面零 JS 异常」按全部 pageerror 硬断言，无过滤。
//
// 用法：node tests/ask-image-ticket.spec.mjs [--web-root=<dir>] [--engines=chromium,webkit]
//   --web-root：指向基点树（红控）或本批树（绿控）的 src/main/resources/web。
// 退出码：0 = 全绿；1 = 有断言红；2 = 环境错误。

import { chromium, webkit } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { webcrypto } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ARGS = process.argv.slice(2);
const argOf = (name) => {
  for (const x of ARGS) {
    const s = x.replace(/^--/, '');
    if (s.startsWith(name + '=')) return s.slice(name.length + 1);
  }
  return null;
};
const WEB_DIR = resolve(argOf('web-root') || join(HERE, '../src/main/resources/web'));
const ENGINE_NAMES = (argOf('engines') || 'chromium,webkit').split(',');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * WS 帧注入腿：routeWebSocket 的服务端柄在 bootPage 闭包里拿到，按 page 登记
 * （一次 page.goto 可能开不止一条 /ws 腿——注入一律广播到该 page 的全部腿，
 * 顺序帧的时序语义在每条腿内仍然保序）。
 */
const wsByPage = new WeakMap();
function wsRegister(page, ws) {
  if (!wsByPage.has(page)) wsByPage.set(page, []);
  wsByPage.get(page).push(ws);
}
function sendToPage(page, frame) {
  const list = wsByPage.get(page) || [];
  for (const ws of list) { try { ws.send(JSON.stringify(frame)); } catch { /* 该腿已关 */ } }
}

/** sid 客户端同式派生（askDock.js ensureSid 逐字同算法）：sha256 前 4 字节 8 hex。 */
async function sidOf(absPath) {
  const buf = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(absPath));
  const hex = Array.from(new Uint8Array(buf).slice(0, 4)).map((b) => b.toString(16).padStart(2, '0')).join('');
  return 'ask-' + hex;
}

// ── 夹具 vault（/tmp 自建，零生产数据根依赖）─────────────────────────────────
const FIX = '/tmp/ask-image-ticket';
const VAULT = FIX + '/qa/.stand/docs';
const MD_PATH = VAULT + '/notes.md';            // S1：Pop 卡重开的文档
const DOC_A = VAULT + '/askdoc.md';             // S2/S3：被问文档 A（vault 根）
const DOC_B = VAULT + '/sub/askdoc2.md';        // S3(ii)：被问文档 B（子目录）
const FIG_A = VAULT + '/fig_a.png';             // (a) 同目录图
const FIG_B = VAULT + '/attachments/hi/fig_b.png'; // (b) 子目录图（vault 附件惯例）
const FIG_C = VAULT + '/sub/fig_c.png';         // S3(ii) B 同目录图
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
mkdirSync(VAULT + '/attachments/hi', { recursive: true });
mkdirSync(VAULT + '/sub', { recursive: true });
writeFileSync(MD_PATH, '# Pop notes\n\n![正文图](fig_a.png)\n');
writeFileSync(DOC_A, '# ask doc A\n\n这是被问的文档 A（vault 根）。\n');
writeFileSync(DOC_B, '# ask doc B\n\n这是被问的文档 B（子目录）。\n');
writeFileSync(FIG_A, PNG);
writeFileSync(FIG_B, PNG);
writeFileSync(FIG_C, PNG);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png',
  '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.md': 'text/markdown',
};
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
  if (p === '/') p = '/index.html';
  if (p === '/sw.js') { res.writeHead(404); res.end('nf'); return; } // SW 活体化会绕过 route
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('nf'); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const PORT = server.address().port;
const BASE = `http://127.0.0.1:${PORT}`;

let fail = 0;
const t = (name, cond, extra = '') => {
  if (cond) console.log(`PASS ${name}`);
  else { fail++; console.error(`FAIL ${name}${extra ? ' — ' + extra : ''}`); }
};

let ticketSeq = 0;

/**
 * 铸票面（自含 mock，支持「指定路径的首次铸票延迟」＝竞争窗）。
 * 必须在 `**\/api\/**` 兜底路由**之后**注册（Playwright 后注册者优先）。
 * tokens 依发行序记录（S1 的红票种子 = 截至某刻的快照）。
 */
async function installTicketRoute(page, { delayMs = 0, delayPath = '' } = {}) {
  const state = { calls: [], tokens: [], delayed: false };
  await page.route('**/api/nf-ticket*', async (route) => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    paths = Array.isArray(paths) ? paths : [];
    state.calls.push(paths.slice());
    if (delayMs && delayPath && !state.delayed && paths.some((p) => String(p).includes(delayPath))) {
      state.delayed = true;
      await sleep(delayMs);
    }
    const tickets = {};
    for (const p of paths) {
      const tk = 'tk-' + (++ticketSeq);
      tickets[p] = { t: tk, exp: Date.now() + 3600000, uses: -1 };
      state.tokens.push(tk);
    }
    return route.fulfill({ json: { tickets, rejected: [] } });
  });
  return state;
}

/**
 * /api/nf-file 面：无票 401（真机制自测）；旧票 403 invalid-ticket（自愈入口）；
 * 其余按 files 表 200。记录每发请求（path / ticket / status / contentType）。
 */
function nfFileRoute(files, oldTickets, hits) {
  return async (route) => {
    const url = new URL(route.request().url());
    const path = url.searchParams.get('path') || '';
    const ticket = url.searchParams.get('ticket') || '';
    if (!ticket) { hits.push({ path, ticket: '', status: 401, ct: '' }); return route.fulfill({ status: 401, contentType: 'text/plain', body: 'Missing ticket' }); }
    if (oldTickets && oldTickets.includes(ticket)) {
      hits.push({ path, ticket, status: 403, ct: '' });
      return route.fulfill({ status: 403, headers: { 'X-Nf-Reason': 'invalid-ticket' }, body: 'denied' });
    }
    const spec = files[path];
    if (!spec) { hits.push({ path, ticket, status: 404, ct: '' }); return route.fulfill({ status: 404, contentType: 'text/plain', body: 'nf' }); }
    hits.push({ path, ticket, status: 200, ct: spec.contentType });
    return route.fulfill({ status: 200, contentType: spec.contentType, body: spec.body });
  };
}

/**
 * 起 page：静态面 + API 面 + WS 面（configData/sessionList 注入；askOpen 回声
 * askState——sid 用与 askDock.ensureSid 同式的确定性派生；askQuestion 只记录。
 * 回答正文由 Node 侧注入 textDelta/done（被测链照常消费）。
 * @returns 状态柄（page / errors / fileHits / ticket / pageRelative）
 */
async function bootPage(engine, { files = {}, delayMs = 0, delayPath = '', viewport = { width: 1600, height: 1000 } } = {}) {
  const browser = await engine.launch();
  const ctx = await browser.newContext({ viewport });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  /** 全部 pageerror（零过滤——mermaid 假象已定性为夹具 charset 问题，本夹具恒带 charset）。 */
  const errors = [];
  page.on('pageerror', (e) => {
    // 记 message + 栈首行：真实异常的归属文件定位用。
    const msg = String((e && e.message) || e).slice(0, 200);
    const stack = String((e && e.stack) || '').split('\n').slice(0, 4).join(' | ');
    errors.push(msg + (stack ? ` @ ${stack}` : ''));
  });
  /** 页面相对图片请求（缺陷面：相对路径按页面 URL 解析的那发 404）。夹具文件名
   *  之外也盯字符串级 pass 的占位 token 族（__NF_MD_IMG_/FB_/ERR_——它们只该活在
   *  HTML 字符串里，进网络即缺陷）。 */
  const pageRelative = [];
  page.on('response', (r) => {
    const u = r.url();
    if (/fig_[abc]\.png|rel\.png|__NF_MD_(IMG|FB|ERR)_/.test(u) && !u.includes('/api/nf-file') && !u.startsWith('blob:')) {
      pageRelative.push({ url: u.split('?')[0], status: r.status() });
    }
  });
  const fileHits = [];
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute(files, null, fileHits));
  const ticket = await installTicketRoute(page, { delayMs, delayPath });
  await page.routeWebSocket(/\/ws/, (ws) => {
    wsRegister(page, ws);
    ws.onMessage(async (raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen' && m.file && m.file.absPath) {
        // sid 与客户端派生同式（sha256 前 8 hex）——askState 回声必须带它，
        // 否则 askDock.consumeFrame 按 sid 反查会找不到会话账。
        const sid = await sidOf(m.file.absPath);
        ws.send(JSON.stringify({ type: 'askState', sessionId: sid, sessionKey: 'ask:fixture', fileName: m.file.name || 'doc.md', messages: [] }));
      }
      // askQuestion：回答帧由测试按注入脚本回（textDelta/done），此处不回声。
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify({ features: {} }) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(() => !!document.getElementById('activity-bar'), null, { timeout: 20000 });
  await sleep(600);
  return { browser, page, errors, fileHits, ticket, pageRelative };
}

/** 打开文档卡（真实发射点：workspace-open-item，带 content + absPath）。 */
function openDoc(page, absPath, content, title) {
  return page.evaluate(({ absPath, content, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, title, itemType: 'markdown', content, absPath, pinned: true },
    }));
  }, { absPath, content, title });
}

/** 等 canvas 里该文档的 pane 落位（有界）。 */
const waitPane = (page, absPath, timeout = 8000) =>
  page.waitForFunction((sel) => !!document.querySelector(sel), `.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout }).catch(() => null);

/** 提问（真实交互链：fab → 填字 → Enter）。fab 是 toggle（askDock.toggle：
 *  开态再点 = 收起），面板已开着就不再点 fab，直接填。等 user 气泡落列再返回
 *  ——它证明 ensureSession 已完成（会话账已按 sid 入 map），后续注入的
 *  textDelta/done 才不会被 consumeFrame 的 sid 反查丢掉。 */
async function askClickFillEnter(page, text) {
  const dockOpen = await page.evaluate(() => !!document.querySelector('.ask-dock:not(.closed)'));
  if (!dockOpen) await page.click('.ask-fab:not(.note-fab)', { timeout: 8000 });
  await page.fill('.ask-dock textarea', text);
  await page.press('.ask-dock textarea', 'Enter');
  await page.waitForFunction(() => !!document.querySelector('.ask-dock .ask-col .ask-bub.user'), null, { timeout: 8000 });
}

/** 页面异常表（零过滤：任何 pageerror 都是红）。 */
const errNote = (errors) => errors.join(' | ');

/** 等气泡列里成图数达到 n（有界；到不了返回现值）。 */
const waitImgs = (page, scopeSel, n, timeout = 10000) =>
  page.waitForFunction(({ sel, n }) => {
    const imgs = Array.from(document.querySelectorAll(sel + ' img'));
    return imgs.filter((i) => i.complete && i.naturalWidth > 0).length >= n;
  }, { sel: scopeSel, n }, { timeout }).then(() => true, () => false);

const ENGINE_MAP = { chromium, webkit };
const SID_A = await sidOf(DOC_A);
const SID_B = await sidOf(DOC_B);

for (const engineName of ENGINE_NAMES) {
  const engine = ENGINE_MAP[engineName];
  if (!engine) { console.error(`unknown engine: ${engineName}`); process.exit(2); }
  console.log(`[ask-image-ticket] engine=${engineName} web=${WEB_DIR}`);
  const E = engineName === 'webkit' ? 'wk' : 'cr';

  // ── S1：Pop 文档卡重开（旧票 403 ⇒ 文档体 + 正文图片自愈走票据 200）──
  {
    const files = {
      [MD_PATH]: { contentType: 'text/markdown', body: readFileSync(MD_PATH) },
      [FIG_A]: { contentType: 'image/png', body: PNG },
    };
    const { browser, page, errors, fileHits, ticket, pageRelative } = await bootPage(engine, { files });
    // 旧票 = 「网关重启 / 票过期」形态：文档体与正文图的票都已在缓存里过期。
    await page.evaluate(async (paths) => {
      const nfTicket = await import('/js/nfTicket.js');
      for (const p of paths) await nfTicket.ticketUrl(p);
    }, [MD_PATH, FIG_A]);
    const oldTickets = ticket.tokens.slice();
    await page.route(/\/api\/nf-file\?/, nfFileRoute(files, oldTickets, fileHits));
    await page.evaluate(`import('/js/popArtifacts.js').then(m => m.openPopArtifact(${JSON.stringify(MD_PATH)}, 'notes.md'))`);
    await waitPane(page, MD_PATH);
    const imgOk = await page.waitForFunction((sel) => {
      const i = document.querySelector(sel + ' .canvas-md-scroll img') || document.querySelector(sel + ' img');
      return !!(i && i.complete && i.naturalWidth > 0);
    }, `.canvas-tab-pane[data-tab-id="file:${MD_PATH}"]`, { timeout: 10000 }).then(() => true, () => false);
    await sleep(300);
    const mdHits = fileHits.filter((h) => h.path === MD_PATH);
    const figHits = fileHits.filter((h) => h.path === FIG_A);
    t(`${E}S1 文档卡重开：正文图片渲染（旧票自愈后 naturalWidth>0）`, imgOk);
    t(`${E}S1 文档体：旧票 403 ⇒ reMint 重取 200（恰两次读）`,
      mdHits.length === 2 && mdHits[0].status === 403 && mdHits[1].status === 200,
      JSON.stringify(mdHits));
    t(`${E}S1 正文图片仍走票据并 200`,
      figHits.length >= 2 && figHits[0].status === 403 && figHits[figHits.length - 1].status === 200 && figHits.every((h) => h.ticket),
      JSON.stringify(figHits));
    t(`${E}S1 零页面相对图片请求`, pageRelative.length === 0, JSON.stringify(pageRelative));
    t(`${E}S1 页面零 JS 异常`, errors.length === 0, errNote(errors));
    await browser.close();
  }

  // ── S2：Ask 回答正文相对路径图片（a 同目录 / b 子目录 / c 无文档上下文）──
  {
    const files = {
      [DOC_A]: { contentType: 'text/markdown', body: readFileSync(DOC_A) },
      [FIG_A]: { contentType: 'image/png', body: PNG },
      [FIG_B]: { contentType: 'image/png', body: PNG },
      [FIG_C]: { contentType: 'image/png', body: PNG },
    };
    const { browser, page, errors, fileHits, ticket, pageRelative } = await bootPage(engine, { files });
    await openDoc(page, DOC_A, readFileSync(DOC_A, 'utf8'), 'askdoc.md');
    await waitPane(page, DOC_A);
    await askClickFillEnter(page, '两图');
    sendToPage(page, {type: 'textDelta', sessionId: SID_A, delta: '![同目录图](fig_a.png)\n\n![子目录图](attachments/hi/fig_b.png)' });
    sendToPage(page, {type: 'done', sessionId: SID_A });
    await waitImgs(page, '.ask-dock .ask-col', 2);
    await sleep(300);
    const imgInfo = await page.evaluate(() => Array.from(document.querySelectorAll('.ask-dock .ask-col img')).map((i) => ({ src: String(i.src).slice(0, 24), w: i.naturalWidth })));
    const flat = ticket.calls.flat();
    const figA = fileHits.filter((h) => h.path === FIG_A);
    const figB = fileHits.filter((h) => h.path === FIG_B);
    t(`${E}S2a/b 两图全渲染（naturalWidth>0）`, imgInfo.length === 2 && imgInfo.every((x) => x.w > 0), JSON.stringify(imgInfo));
    t(`${E}S2a/b 终局 src 为账本 blob（票据化取数后入账）`, imgInfo.length === 2 && imgInfo.every((x) => x.src.startsWith('blob:')), JSON.stringify(imgInfo));
    t(`${E}S2a 铸票面收到同目录候选`, flat.some((p) => p === FIG_A), JSON.stringify(flat));
    t(`${E}S2b 铸票面收到子目录候选`, flat.some((p) => p === FIG_B), JSON.stringify(flat));
    t(`${E}S2a /api/nf-file 带票 200`, figA.length >= 1 && figA.every((h) => h.ticket && h.status === 200), JSON.stringify(figA));
    t(`${E}S2b /api/nf-file 带票 200`, figB.length >= 1 && figB.every((h) => h.ticket && h.status === 200), JSON.stringify(figB));
    t(`${E}S2a/b 零页面相对图片请求（修前缺陷面）`, pageRelative.length === 0, JSON.stringify(pageRelative));
    t(`${E}S2a/b 页面零 JS 异常`, errors.length === 0, errNote(errors));

    // (c) 无文档上下文：门面直造（选区档形态——cfg 无 docPath）。try 包住：
    // 基点树若构造面不兼容（抛错）也只记红，不掀翻本站后续断言。
    await page.evaluate(async () => {
      try {
        const sess = { sid: 'qa-nodoc', msgs: [{ role: 'ai', text: '![无上下文图](rel.png)', ts: Date.now() }], busy: false };
        const mod = await import('/js/askPanel.js');
        const panel = mod.createAskPanel({ dialog: {
          tt: (zh) => zh, placeholder: 'p', panelClass: 'qa-nodoc-dock',
          activeSession: () => sess, canSubmit: () => true,
          ensureSession: async () => sess, ensureBound: () => {}, send: () => {},
          frameKind: () => null,
        } });
        document.body.appendChild(panel.root);
        panel.dialog.render();
      } catch (e) { window.__qaNodocErr = String(e && e.message || e); }
    });
    await sleep(300);
    const nodocErr = await page.evaluate(() => window.__qaNodocErr || '');
    const nodocState = await page.evaluate(() => {
      const root = document.querySelector('.qa-nodoc-dock');
      const ph = root && root.querySelector('.nf-md-img-fail[data-nf-md-img-nodoc]');
      const img = root && root.querySelector('.ask-col img');
      return { placeholder: !!ph, imgLeft: !!img, reason: ph ? ph.textContent.slice(0, 80) : '' };
    });
    const relReqs = pageRelative.filter((r) => r.url.endsWith('/rel.png'));
    t(`${E}S2c 无文档上下文 ⇒ 明确占位（不抛错、不留白）`, !nodocErr && nodocState.placeholder && !nodocState.imgLeft,
      nodocErr ? `factory threw: ${nodocErr}` : JSON.stringify(nodocState));
    t(`${E}S2c 零网络（不发注定 404 的页面相对请求）`, relReqs.length === 0, JSON.stringify(relReqs));
    t(`${E}S2c 页面零 JS 异常`, errors.length === 0, errNote(errors));
    await browser.close();
  }

  // ── S3(i)：铸票在飞 ⇒ askState 重渲替换回答 ⇒ 迟到 pass 弃写 ──
  {
    const files = {
      [DOC_A]: { contentType: 'text/markdown', body: readFileSync(DOC_A) },
      [FIG_B]: { contentType: 'image/png', body: PNG },
    };
    const { browser, page, errors, fileHits, pageRelative } = await bootPage(engine, { files, delayMs: 1200, delayPath: FIG_B });
    await openDoc(page, DOC_A, readFileSync(DOC_A, 'utf8'), 'askdoc.md');
    await waitPane(page, DOC_A);
    await askClickFillEnter(page, '竞争');
    sendToPage(page, {type: 'textDelta', sessionId: SID_A, delta: '![迟到图](attachments/hi/fig_b.png)' });
    sendToPage(page, {type: 'done', sessionId: SID_A });            // render 轮 1：pass 铸票（延迟 1200ms）
    await sleep(80);
    sendToPage(page, {type: 'askState', sessionId: SID_A, sessionKey: 'ask:fixture', fileName: 'askdoc.md',
      messages: [
        { role: 'user', text: '竞争', timestamp: Date.now() },
        { role: 'ai', text: '回答修订版：图已随修订移除，纯文字结论。', timestamp: Date.now() },
      ] });                                                  // render 轮 2：新轮替换旧轮
    await sleep(1600);                                       // 有界等待：迟到铸票必然已回
    const finalState = await page.evaluate(() => ({
      imgs: document.querySelectorAll('.ask-dock .ask-col img').length,
      fails: document.querySelectorAll('.ask-dock .ask-col .nf-md-img-fail').length,
      text: (document.querySelector('.ask-dock .ask-col .ask-bub.ai') || {}).textContent || '',
    }));
    const figB = fileHits.filter((h) => h.path === FIG_B);
    t(`${E}S3i 迟到结果不回填：终态零图、零占位、正文为修订版`,
      finalState.imgs === 0 && finalState.fails === 0 && finalState.text.includes('回答修订版'),
      JSON.stringify(finalState));
    t(`${E}S3i 旧轮弃写 ⇒ 不对已弃图取字节（fig_b nf-file = 0）`, figB.length === 0, JSON.stringify(figB));
    t(`${E}S3i 零页面相对图片请求`, pageRelative.length === 0, JSON.stringify(pageRelative));
    t(`${E}S3i 页面零 JS 异常`, errors.length === 0, errNote(errors));
    await browser.close();
  }

  // ── S3(ii)：铸票在飞 ⇒ 切文档（A→B）⇒ A 的迟到结果不写 B 的列 ──
  {
    const files = {
      [DOC_A]: { contentType: 'text/markdown', body: readFileSync(DOC_A) },
      [DOC_B]: { contentType: 'text/markdown', body: readFileSync(DOC_B) },
      [FIG_B]: { contentType: 'image/png', body: PNG },
      [FIG_C]: { contentType: 'image/png', body: PNG },
    };
    const { browser, page, errors, fileHits, pageRelative } = await bootPage(engine, { files, delayMs: 1200, delayPath: FIG_B });
    await openDoc(page, DOC_A, readFileSync(DOC_A, 'utf8'), 'askdoc.md');
    await waitPane(page, DOC_A);
    await askClickFillEnter(page, '切文档');
    sendToPage(page, {type: 'textDelta', sessionId: SID_A, delta: '![A的图](attachments/hi/fig_b.png)' });
    sendToPage(page, {type: 'done', sessionId: SID_A });            // A 轮：pass 铸票（延迟 1200ms）
    await sleep(80);
    await openDoc(page, DOC_B, readFileSync(DOC_B, 'utf8'), 'askdoc2.md');
    await waitPane(page, DOC_B);                                    // 切卡 ⇒ askOpen(B) ⇒ askState ⇒ render 轮 2（epoch 递增）
    await sleep(1600);                                              // 有界等待：A 的迟到铸票必然已回
    const midState = await page.evaluate(() => ({
      imgs: document.querySelectorAll('.ask-dock .ask-col img').length,
      fails: document.querySelectorAll('.ask-dock .ask-col .nf-md-img-fail').length,
    }));
    // B 随后的回答以 **B 所在目录**为基准出图（子目录同图）。
    await askClickFillEnter(page, 'B 的图');
    sendToPage(page, {type: 'textDelta', sessionId: SID_B, delta: '![B同目录图](fig_c.png)' });
    sendToPage(page, {type: 'done', sessionId: SID_B });
    await waitImgs(page, '.ask-dock .ask-col', 1);
    await sleep(300);
    const finalImgs = await page.evaluate(() => Array.from(document.querySelectorAll('.ask-dock .ask-col img')).map((i) => i.naturalWidth));
    const figB = fileHits.filter((h) => h.path === FIG_B);
    const figC = fileHits.filter((h) => h.path === FIG_C);
    t(`${E}S3ii 切文档后 A 的迟到结果未写入 B 的列（零图零占位）`,
      midState.imgs === 0 && midState.fails === 0, JSON.stringify(midState));
    t(`${E}S3ii A 的弃图零字节请求（fig_b nf-file = 0）`, figB.length === 0, JSON.stringify(figB));
    t(`${E}S3ii B 的回答以 B 目录为基准出图（fig_c 带票 200 且渲染）`,
      finalImgs.length === 1 && finalImgs[0] > 0 && figC.length >= 1 && figC.every((h) => h.ticket && h.status === 200),
      JSON.stringify({ finalImgs, figC }));
    t(`${E}S3ii 零页面相对图片请求`, pageRelative.length === 0, JSON.stringify(pageRelative));
    t(`${E}S3ii 页面零 JS 异常`, errors.length === 0, errNote(errors));
    await browser.close();
  }
}

await new Promise((r) => server.close(r));
if (fail) { console.error(`ask-image-ticket: ${fail} failure(s)`); process.exit(1); }
console.log('ask-image-ticket: green');
