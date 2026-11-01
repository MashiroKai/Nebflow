#!/usr/bin/env node
// pop-footer-family.spec.mjs — Pop 交付卡 footer 批验收（作者令 2026-11-01 16:19
// 「让你 pop 的文件和我发的文件也有 footer」——agent Pop 交付卡与用户上传附件
// 享受同文件/图片消息（6053ba021 长按退役批）同款 footer 信息条家族）。
//
// 背景考古（改前实况）：
//   · .row.user-media（用户上传附件行）= 6053ba021 已有 footer（时间+下载+引用）
//     ——本批**保真重钉**（任务面②；spec T1 用户行同框同族断言）。
//   · .row.pop-artifact（LLM Pop 交付行）= **零 footer 零引用**（改前实证：本
//     spec PHASE=before 全红）——本批补齐（任务面①）：时间档（live Date.now() /
//     重放两腿 m.timestamp 三腿）+ 下载键（单件行宁缺毋假，装配单点
//     buildMediaRowFooterBadge 与用户行同一装配器）+ 引用钮（attachQuoteMenu
//     同一挂点，rAF 收敛进徽章）。
//
// 覆盖（T1 断言 × Chromium/WebKit 双引擎 × dark/light 双主题 + 桌面宽档存证；
// 下载/引用/live 腿在 dark 档执行）：
//   T1 三类卡片同框 footer 家族：用户上传文件行 / Pop 文件卡行 / Pop 图片行 /
//      Pop 多件行（对照组：时间+引用、零下载键=宁缺毋假）四行同框，全部
//      .duration-badge 同族徽章 + data-ts 同源 + 引用钮齐整；同框实拍双主题。
//   T2 下载真实断言：Pop 文件卡行/图片行 + 用户行的下载键各点击一次 = 真实
//      download 事件（suggestedFilename 正确 + 落盘字节与夹具逐字节同长 +
//      成功态 .copied 绿勾反馈）；path 腿走 nf-ticket 铸票 + nf-file 字节
//      （机制本身在测：ticketGuard 无票拒答）。
//   T3 引用真实断言：Pop 文件卡行 footer 引用钮 → 引用 chip 入条（.att-ref
//      [data-ref-type=chat-message]）→ 发送 → 乐观气泡引用块（摘要 = [文件]
//      文件名 + .msg-quote-atts 附件缩略带）→ 点击引用块跳转原 Pop 行
//      （.quote-target-flash 1.8s 高亮，data-ref-message-id = Pop 行 msgId）。
//   T4 live 腿：ws 推真 toolEnd 帧 → renderTool 原位直出 Pop 行（footer 时间档
//      ≈ 到达时刻、下载键/引用钮/msgId 齐备，下载键同样真落盘）。
//   T5 零 console error（资源 404 噪声过滤，pageerror + console.error 硬零）。
//
// 数据面 = 自包含 harness（route 静态文件 + WS getHistory 夹具 + nf-ticket mock
// + ws 帧注入 live 腿），零后端、零 sbt。Run: node tests/pop-footer-family.spec.mjs
//   PHASE=before → 改前取证（新钉面预期红）
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { deflateSync, crc32 as zlibCrc32 } from 'node:zlib';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.PFF_OUT_DIR ||
  join(HERE, '..', 'evidence', `${TS}_pop-footer-family${PHASE === 'before' ? '-before' : ''}`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'pop-footer-family-root';
// mock 源端口：路由层全量拦截（不发起真连接），端口本身任意。**不可用 1**——
// WebKit/Safari bad-port 保护封禁低危端口表（1=tcpmux 在列），导航直接不发出
// （delegate-receipt-card spec A9 双引擎腿同款实证）；8899 不在封禁表。
const ORIGIN = 'http://localhost:8899';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// Console/pageerror 捕获（零 console error 硬判据）。存量基线噪声过滤：
//  · 资源加载噪声——mock 静态路由不伺服字体/favicon；
//  · 「ResizeObserver loop completed with undelivered notifications」——浏览器级
//    帧预算信号（WebKit 以 pageerror 浮出，chromium 不浮出；app 内
//    attachStripFold/askDock 等 RO 链既有形态，T3 引用 chip 入条触发同帧重排
//    时偶发；非 app 异常，W3C 明文 non-actionable）。
const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => {
    if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`);
  });
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`);
  });
};

/* ── 夹具字节 ─────────────────────────────────────────────────────────── */

// 72×48 纯蓝 PNG（node:zlib deflateSync + zlib.crc32 真编码器产物——图片行可见
// + 下载字节断言同源；零手搓编码器）。
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(zlibCrc32(body), 0);
  return Buffer.concat([len, body, crc]);
}
function makeSolidPng(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  const stride = w * 3 + 1;
  const raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = y * stride + 1 + x * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG = makeSolidPng(72, 48, [30, 100, 220]);
const PDF_A = Buffer.from('%PDF-1.4\n%pop-footer-family fixture A (quarterly)\n%%EOF\n', 'utf8');
const PDF_B = Buffer.from('%PDF-1.4\n%pop-footer-family fixture B (weekly)\n%%EOF\n', 'utf8');

const PATH_QUARTERLY = '/tmp/pop-footer/quarterly-report.pdf';
const PATH_WEEKLY = '/tmp/pop-footer/weekly-report.pdf';
const PATH_CSV = '/tmp/pop-footer/metrics.csv';
const PATH_PHOTO = '/tmp/pop-footer/site-photo.png';
const CSV_BYTES = Buffer.from('k,v\n1,2\n', 'utf8');

/** Pop 载荷（PopTool.popResult 契约形）。 */
const popPayload = (items) => '___POP_JSON___' + JSON.stringify({
  fileRefs: { inlined: 0, referenced: items.length, failed: 0, omitted: 0 },
  warnings: [], notes: [], items,
});
const LIVE_POP = popPayload([{ kind: 'file', name: 'live-交付.md', path: '/tmp/pop-footer/live.md', size: 42, ext: 'md' }]);
const LIVE_MD = Buffer.from('# live leg fixture\n', 'utf8');

// 固定时间戳（相对偏移）：footer data-ts 断言与视觉时间档共用同一源。
const T_USER = Date.now() - 240_000;  // 用户上传文件行（重放腿）
const T_MULTI = Date.now() - 180_000; // Pop 多件行（对照：无下载键）
const T_POP = Date.now() - 120_000;   // Pop 文件卡行
const T_IMG = Date.now() - 60_000;    // Pop 图片行
const T = { userTs: T_USER, multiTs: T_MULTI, popTs: T_POP, imgTs: T_IMG };

/** 历史夹具：四行同框（用户上传文件 / Pop 多件 / Pop 文件卡 / Pop 图片）。 */
const HISTORY = () => [
  {
    type: 'user', text: '同框对照：我上传的文件。', timestamp: T.userTs, injected: false,
    attachments: [{ name: 'weekly-report.pdf', type: 'file', path: PATH_WEEKLY }],
  },
  {
    type: 'tool', label: 'Pop("两件交付")', summary: '2 files delivered', isError: false,
    timestamp: T.multiTs,
    content: popPayload([
      { kind: 'file', name: 'metrics.csv', path: PATH_CSV, size: CSV_BYTES.length, ext: 'csv' },
      { kind: 'file', name: 'quarterly-report.pdf', path: PATH_QUARTERLY, size: PDF_A.length, ext: 'pdf' },
    ]),
  },
  {
    type: 'tool', label: 'Pop("季度报告")', summary: '1 file delivered', isError: false,
    timestamp: T.popTs,
    content: popPayload([
      { kind: 'file', name: 'quarterly-report.pdf', path: PATH_QUARTERLY, size: PDF_A.length, ext: 'pdf' },
    ]),
  },
  {
    type: 'tool', label: 'Pop("现场照片")', summary: '1 image delivered', isError: false,
    timestamp: T.imgTs,
    content: popPayload([
      { kind: 'image', name: 'site-photo.png', path: PATH_PHOTO, size: PNG.length, ext: 'png' },
    ]),
  },
];

/* ── boot（自包含 harness：route 静态 + ws 夹具 + 票 mock）─────────────── */

async function boot(width, height, scheme, engine) {
  const page = await (await engine.launch()).newContext({
    viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 2,
    hasTouch: width <= 768, // bootstrap.js 手机层折叠点同源
    acceptDownloads: true,
    locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    // SW 关闸（双保险）：swReg.js 的既有逃生门（<lower>_sw='0'）+ register 桩。
    // 根因（WebKit 探测实证 2026-11-01）：WebKit 的 SW fetch 管道**绕过**
    // Playwright page.route 拦截（chromium 可拦）——SW 控页后铸票/nf-file 请求
    // 打穿 mock 直奔真网（localhost:8899 无监听 ⇒ Load failed）。sw.js 是温
    // 访问秒开缓存，零功能依赖，关闸零副作用。
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => { /* 永不决 = 注册面静默空转 */ }),
      configurable: true,
    });
  });
  const dlCounts = { mint: 0, file: 0 };
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p.startsWith('/api/nf-file')) {
        return ticketGuard(route).then((guarded) => {
          if (guarded) return;
          dlCounts.file += 1;
          const path = new URL(route.request().url()).searchParams.get('path') || '';
          const body = path === PATH_PHOTO ? PNG : path === PATH_CSV ? CSV_BYTES
            : path === PATH_WEEKLY ? PDF_B : path === '/tmp/pop-footer/live.md' ? LIVE_MD : PDF_A;
          return route.fulfill({ status: 200, contentType: 'application/octet-stream', body });
        });
      }
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY(), hasMore: false, offset: 0 }));
      } else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'toolEnd') ws.send(raw); // T4 live 腿：客户端推帧 → mock 回显服务器帧（ws.js 服务器帧派发面）
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  // 历史腿行齐（含票腿字节水合）。
  await page.waitForFunction(() => document.querySelectorAll('#chat .row.pop-artifact').length >= 3, null, { timeout: 15000 });
  await page.waitForTimeout(500);
  return { page, dlCounts };
}

/* ── 行定位单点 ───────────────────────────────────────────────────────── */

const census = (page) => page.evaluate(() => {
  // .row.user-media 也带 .pop-artifact 类（同层同形家族）⇒ Pop 数组须排除 user 行。
  const rows = Array.from(document.querySelectorAll('#chat .row.pop-artifact')).filter((r) => !r.classList.contains('user'));
  const media = document.querySelector('#chat .row.user-media');
  const badges = Array.from(document.querySelectorAll('#chat .row > .duration-badge'));
  return {
    userMedia: !!media,
    popRows: rows.length,
    popNonUser: rows.length,
    badgesTotal: badges.length,
    userBadgeTs: media?.querySelector(':scope > .duration-badge .duration-badge-time')?.getAttribute('data-ts') || '',
    userDl: !!media?.querySelector(':scope > .duration-badge .msg-dl'),
    userQuote: !!media?.querySelector(':scope > .duration-badge .msg-quote-btn'),
    popBadgeTs: rows.map((r) => r.querySelector(':scope > .duration-badge .duration-badge-time')?.getAttribute('data-ts') || ''),
    popDl: rows.map((r) => !!r.querySelector(':scope > .duration-badge .msg-dl')),
    popQuote: rows.map((r) => !!r.querySelector(':scope > .duration-badge .msg-quote-btn')),
    popMsgIds: rows.map((r) => r.dataset.msgId || ''),
  };
});

/** 主断言组：四行同框 + footer 家族齐整（时间档同源 + 下载键档位 + 引用钮）。 */
async function assertFamily(page, tag) {
  const c = await census(page);
  ok(`${tag} 用户上传文件行在场（历史重放腿）`, c.userMedia);
  ok(`${tag} Pop 交付行 = 3（多件/文件卡/图片）`, c.popNonUser === 3, `got ${c.popNonUser}`);
  ok(`${tag} 四行 footer 徽章齐（.duration-badge 同族）`, c.badgesTotal >= 4, `got ${c.badgesTotal}`);
  ok(`${tag} 用户行 footer 时间档 = 消息时间戳`, c.userBadgeTs === String(T.userTs), `got ${c.userBadgeTs}`);
  ok(`${tag} 用户行 footer 下载键 + 引用钮（保真重钉 6053ba021 面）`, c.userDl && c.userQuote, `dl=${c.userDl} quote=${c.userQuote}`);
  // [多件对照, 文件卡, 图片]
  ok(`${tag} Pop 多件行时间档（重放腿 m.timestamp 三腿）`, c.popBadgeTs[0] === String(T.multiTs), `got ${c.popBadgeTs[0]}`);
  ok(`${tag} Pop 文件卡行时间档`, c.popBadgeTs[1] === String(T.popTs), `got ${c.popBadgeTs[1]}`);
  ok(`${tag} Pop 图片行时间档`, c.popBadgeTs[2] === String(T.imgTs), `got ${c.popBadgeTs[2]}`);
  ok(`${tag} Pop 多件行零下载键（宁缺毋假）+ 引用钮`, c.popDl[0] === false && c.popQuote[0] === true, `dl=${c.popDl[0]} quote=${c.popQuote[0]}`);
  ok(`${tag} Pop 文件卡行下载键 + 引用钮`, c.popDl[1] === true && c.popQuote[1] === true, `dl=${c.popDl[1]} quote=${c.popQuote[1]}`);
  ok(`${tag} Pop 图片行下载键 + 引用钮`, c.popDl[2] === true && c.popQuote[2] === true, `dl=${c.popDl[2]} quote=${c.popQuote[2]}`);
  ok(`${tag} Pop 行 msgId 齐备（引用寻址锚）`, c.popMsgIds.every((id) => !!id));
  return c;
}

/** 下载真实断言（Pop 行，按 data-ts 的 :has() 精确寻址）：点击 → download 事件
 *  → 落盘字节同长 + .copied 绿勾反馈采样。改前（PHASE=before）键缺席 ⇒ 记 FAIL
 *  即返（验红面，不抛未捕获异常打断后续取证）。 */
async function downloadPopRow(page, ts, wantName, wantBytes, tag, tmpName) {
  const sel = `#chat .row.pop-artifact:not(.user):has(.duration-badge .duration-badge-time[data-ts="${ts}"])`;
  if ((await page.locator(`${sel} > .duration-badge .msg-dl`).count()) === 0) {
    ok(`${tag} 下载键在场（改前验红面）`, false, 'no .msg-dl on Pop row');
    return;
  }
  const dlPromise = page.waitForEvent('download', { timeout: 10000 });
  await page.locator(`${sel} > .duration-badge .msg-dl`).first().click();
  const dl = await dlPromise;
  const nameOk = dl.suggestedFilename() === wantName;
  const tmp = join(SHOTS, tmpName);
  await dl.saveAs(tmp);
  const { size } = await stat(tmp);
  ok(`${tag} 下载文件名 ${wantName}`, nameOk, `got ${dl.suggestedFilename()}`);
  ok(`${tag} 下载字节同长（${wantBytes.length}B，票腿真字节）`, size === wantBytes.length, `got ${size}`);
  const flashed = await page.evaluate((tsv) => {
    const row = Array.from(document.querySelectorAll('#chat .row.pop-artifact:not(.user)'))
      .find((r) => r.querySelector(`:scope > .duration-badge .duration-badge-time[data-ts="${tsv}"]`));
    return !!row?.querySelector(':scope > .duration-badge .msg-dl.copied');
  }, String(ts));
  ok(`${tag} 成功态 .copied 绿勾反馈`, flashed);
}

/* ── 用例：手机层四格（双引擎 × 双主题）────────────────────────────────── */

for (const [engineName, engine] of [['chromium', chromium], ['webkit', webkit]]) {
  for (const scheme of ['dark', 'light']) {
    console.log(`\n── ${engineName} × ${scheme} ─────────────────────────────`);
    const tag = `${engineName}-${scheme}`;
    const { page, dlCounts } = await boot(375, 812, scheme, engine);

    // T1 三类卡片同框 footer 家族。
    await assertFamily(page, tag);
    // 同框实拍：滚到聊天列底部，手机层整幅。
    await page.evaluate(() => {
      const chat = document.querySelector('#chat');
      chat.scrollTop = chat.scrollHeight;
    });
    await page.waitForTimeout(350);
    await page.screenshot({ path: join(SHOTS, `sameframe-${tag}.png`), fullPage: false });

    if (scheme === 'dark') {
      // T2 下载真实断言（双引擎执行；字节三源各自真落盘）。
      await downloadPopRow(page, T.popTs, 'quarterly-report.pdf', PDF_A, `${tag} Pop 文件卡行`, `.dl-popfile-${engineName}`);
      await downloadPopRow(page, T.imgTs, 'site-photo.png', PNG, `${tag} Pop 图片行`, `.dl-popimg-${engineName}`);
      {
        const dlPromise = page.waitForEvent('download', { timeout: 10000 });
        await page.locator('#chat .row.user-media > .duration-badge .msg-dl').first().click();
        const dl = await dlPromise;
        const tmp = join(SHOTS, `.dl-user-${engineName}`);
        await dl.saveAs(tmp);
        const { size } = await stat(tmp);
        ok(`${tag} 用户行下载文件名`, dl.suggestedFilename() === 'weekly-report.pdf', `got ${dl.suggestedFilename()}`);
        ok(`${tag} 用户行下载字节同长（${PDF_B.length}B）`, size === PDF_B.length, `got ${size}`);
      }
      ok(`${tag} 下载字节走 nf-ticket 铸票 + nf-file（机制在测）`, dlCounts.file >= 3, `file=${dlCounts.file}`);

      // T3 引用真实断言：Pop 文件卡行 footer 引用钮 → chip → 发送 → 引用块 → 跳转。
      // 改前（PHASE=before）引用钮缺席 ⇒ 记 FAIL 即跳过整链（验红面，不悬挂）。
      const popMsgId = (await census(page)).popMsgIds[1];
      const quoteBtn = page.locator(`#chat .row.pop-artifact[data-msg-id="${popMsgId}"] > .duration-badge .msg-quote-btn`);
      if ((await quoteBtn.count()) === 0) {
        ok(`${tag} footer 引用钮在场（改前验红面）`, false, 'no .msg-quote-btn on Pop row');
      } else {
        await quoteBtn.click();
        await page.waitForFunction(() => !!document.querySelector('#attachment-preview .att-ref[data-ref-type="chat-message"]'), null, { timeout: 8000 });
        ok(`${tag} 引用 chip 入条（.att-ref chat-message）`, true);
        await page.fill('#input', '引用 Pop 交付卡试试');
        await page.click('#send-btn');
        await page.waitForFunction(() => !!document.querySelector('#chat .row.user .msg-quote-block'), null, { timeout: 8000 });
        const qText = await page.evaluate(() => document.querySelector('#chat .row.user .msg-quote-block .msg-quote-text')?.textContent || '');
        ok(`${tag} 引用块摘要 = [文件] 季度报告`, qText.includes('文件') && qText.includes('quarterly-report.pdf'), qText.trim().slice(0, 40));
        const qAtts = await page.evaluate(() => ({
          strip: !!document.querySelector('#chat .row.user .msg-quote-block .msg-quote-atts'),
          fileChip: !!document.querySelector('#chat .row.user .msg-quote-att-file'),
        }));
        ok(`${tag} 引用块附件缩略带（文件 chip）`, qAtts.strip && qAtts.fileChip);
        // 跳转：点击引用块 → 原 Pop 行 1.8s 高亮。
        await page.locator('#chat .row.user .msg-quote-block').first().click();
        const flashed = await page.waitForFunction((id) => {
          const row = document.querySelector(`#chat .row.pop-artifact[data-msg-id="${id}"]`);
          return !!row && row.classList.contains('quote-target-flash');
        }, popMsgId, { timeout: 5000 }).then(() => true).catch(() => false);
        ok(`${tag} 引用跳转命中 Pop 行（.quote-target-flash）`, flashed);
      }
    }

    if (scheme === 'dark' && engineName === 'chromium') {
      // T4 live 腿（chromium 档单引擎执行足够——帧→renderTool 链路引擎无关）。
      // 行数轴口径：.row.user-media 也带 .pop-artifact 类 ⇒ 计数/末行定位都走
      // :not(.user)（改前 phase=before 跑本段时曾因轴混用读到图片行）。
      const before = await page.evaluate(() => document.querySelectorAll('#chat .row.pop-artifact:not(.user)').length);
      await page.evaluate(async (payload) => {
        const state = (await import('/js/state.js')).default;
        state.ws.send(JSON.stringify({
          type: 'toolEnd', sessionId: 'pop-footer-family-root',
          label: 'Pop("live 腿交付")', summary: '1 file delivered', isError: false,
          content: payload,
          input: JSON.stringify({ filePath: '/tmp/pop-footer/live.md', title: 'live 腿交付' }),
        }));
      }, LIVE_POP);
      await page.waitForFunction((n) => document.querySelectorAll('#chat .row.pop-artifact:not(.user)').length > n, before, { timeout: 10000 });
      // 引用钮 = attachQuoteMenu 的 rAF 收敛（mountQuoteFooterButton）——行出现后
      // 下一帧才落，等它到位再取样（与 T1 历史行的稳态取样口径一致）。
      await page.waitForFunction(() => {
        const rows = Array.from(document.querySelectorAll('#chat .row.pop-artifact:not(.user)'));
        const r = rows[rows.length - 1];
        return !!r && !!r.querySelector(':scope > .duration-badge .msg-quote-btn');
      }, null, { timeout: 5000 }).catch(() => {});
      const liveRow = await page.evaluate(() => {
        const rows = Array.from(document.querySelectorAll('#chat .row.pop-artifact:not(.user)'));
        const r = rows[rows.length - 1];
        return {
          ts: r.querySelector(':scope > .duration-badge .duration-badge-time')?.getAttribute('data-ts') || '',
          id: r.dataset.msgId || '',
          dl: !!r.querySelector(':scope > .duration-badge .msg-dl'),
          quote: !!r.querySelector(':scope > .duration-badge .msg-quote-btn'),
          name: r.querySelector('.fc-name')?.textContent || '',
        };
      });
      const drift = Math.abs(Number(liveRow.ts) - Date.now());
      ok(`chromium-dark live 腿 Pop 行直出（footer 时间档 ≈ 到达时刻）`, !!liveRow.ts && drift < 15000, `drift=${drift}ms`);
      ok(`chromium-dark live 腿下载键 + 引用钮 + msgId`, liveRow.dl && liveRow.quote && !!liveRow.id, `dl=${liveRow.dl} quote=${liveRow.quote}`);
      if (liveRow.dl) {
        const dlPromise = page.waitForEvent('download', { timeout: 10000 });
        await page.evaluate(() => {
          const rows = Array.from(document.querySelectorAll('#chat .row.pop-artifact:not(.user)'));
          rows[rows.length - 1].querySelector(':scope > .duration-badge .msg-dl').click();
        });
        const dl = await dlPromise;
        const tmp = join(SHOTS, '.dl-live-chromium');
        await dl.saveAs(tmp);
        const { size } = await stat(tmp);
        ok(`chromium-dark live 腿下载真落盘`, dl.suggestedFilename() === 'live-交付.md' && size === LIVE_MD.length, `${dl.suggestedFilename()} ${size}B`);
      } else {
        ok(`chromium-dark live 腿下载真落盘（改前验红面）`, false, 'no .msg-dl on live Pop row');
      }
    }

    // T5 该页零 console error。
    const errs = consoleErrors.splice(0);
    ok(`${tag} 零 console error`, errs.length === 0, errs.slice(0, 3).join(' | '));
    await page.context().browser().close();
  }
}

/* 桌面宽档存证（暗档 × 双引擎）：footer 家族是通用聊天面，非手机层专属。 */
for (const [engineName, engine] of [['chromium', chromium], ['webkit', webkit]]) {
  console.log(`\n── desktop ${engineName} ─────────────────────────────`);
  const { page } = await boot(1280, 900, 'dark', engine);
  await assertFamily(page, `desktop-${engineName}`);
  await page.evaluate(() => { document.querySelector('#chat').scrollTop = document.querySelector('#chat').scrollHeight; });
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(SHOTS, `sameframe-desktop-${engineName}.png`), fullPage: false });
  const errs = consoleErrors.splice(0);
  ok(`desktop-${engineName} 零 console error`, errs.length === 0, errs.slice(0, 3).join(' | '));
  await page.context().browser().close();
}

writeFileSync(join(OUT, `readings.${PHASE}.json`), JSON.stringify({
  phase: PHASE, at: new Date().toISOString(), failures,
  note: 'pop-footer-family spec readings',
}, null, 2));

console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILURES'}  (phase=${PHASE}, shots → ${SHOTS})`);
process.exit(failures === 0 ? 0 : 1);
