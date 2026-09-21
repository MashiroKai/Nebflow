#!/usr/bin/env node
// e2e-sessperf-localfirst.cjs — sessperf Phase B 阶段 1 的**渲染自验腿**（卡
// `.nebflow/20260920_184900_sessperf-local-first-card__chain-sessperf.md` §4⑤）。
//
// 本 harness 自包含：真 Chromium + 真前端模块（**从磁盘服务本树 `web/` 的真文件**，
// 非 mock 实现）+ 真 IndexedDB；只把**网络对端**打桩（远端头像源站 + 新增同源路由
// `/api/avatars/{userId}`），沿用 `scripts/e2e-avatar-flicker.cjs` 的既有路线。
// 不起网关端口、不碰 `:8080`、不碰真实例 `~/.nebflow/**`、不装依赖。
//
// 钉四条（逐条给判据；控制项保证「不是恒绿」）：
//  ① 头像**内容指纹双层缓存**：冷窗（窗口 1）⇒ 远端 URL 一次请求 + 同源路由一次
//    建缓存；**二次开窗（窗口 2，新文档、同 context/同 IDB）** ⇒ 头像**零网络请求**
//    且渲染成 `blob:` 源并真解码。控制项 = 冷窗请求数必须 ≥1（否则「零」无信息量）。
//  ② **断网渲缓存**：`context.setOffline(true)` + **卸掉全部路由**后在页内
//    ① 取一个未缓存 URL ⇒ 必须**网络失败**（证明断网真生效，断网臂不是空跑）；
//    ② 渲染已缓存的头像 ⇒ **零网络、真解码**（断网臂真实执行）。
//  ③ 本地层**一致性抽验**：游标增量写（`writeMessages` 两次滑动窗口 = 增量合并）
//    vs 全量对账（重开文档后从 IDB 重建的镜像）⇒ 条目集**逐条相等**（无丢、无重、
//    升序），且「无可信水位 ⇒ 当无条目」「重复 id ⇒ 去重」两条纪律成立。
//  ④ 新路由**未鉴权探测**不做在本文件（本文件无网关）：见同批
//    `.nebflow/evidence/20260920_sessperf-b1/route-auth-probe.log`（隔离实例真 HTTP）。
//
// Run（worktree 根）：
//   node scripts/e2e-sessperf-localfirst.cjs
// 可选：SF_WEB=<web 树>  SF_OUT=<json 落盘>  SF_HEADED=1
const { chromium } = require('playwright');
const crypto = require('node:crypto');
const { readFileSync, writeFileSync, mkdirSync } = require('node:fs');
const { join, extname, resolve, dirname } = require('node:path');
const { deflateSync } = require('node:zlib');

const WEB = process.env.SF_WEB ? resolve(process.env.SF_WEB) : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.SF_OUT ? resolve(process.env.SF_OUT) : '';
const ORIGIN = 'http://sf.test';
const REMOTE = { u1: 'http://avatars.test/a.jpg', u2: 'http://avatars.test/b.jpg' };
const ACCT = 'd1|dev@test';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2',
};

// ── 测试头像字节（真 PNG，可解码；噪声图 = 解码成本真实）──────────────
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = c ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function noisePng(size, seed) {
  const raw = Buffer.alloc(size * (size * 3 + 1));
  let s = seed;
  for (let y = 0; y < size; y++) {
    const off = y * (size * 3 + 1);
    raw[off] = 0;
    for (let x = 0; x < size; x++) {
      s = (s * 1103515245 + 12345) & 0x7fffffff;
      raw[off + 1 + x * 3] = 120 + ((s >> 16) & 0x5f);
      raw[off + 2 + x * 3] = 60 + ((s >> 8) & 0x7f);
      raw[off + 3 + x * 3] = 180 + (s & 0x3f);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 1 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG = { u1: noisePng(64, 7), u2: noisePng(64, 19) };
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
const SHA = { u1: sha(PNG.u1), u2: sha(PNG.u2) };

// ── 探针页（真模块入口：localStore / avatarRender / fmMessageCache）─────
const SHELL = `<!doctype html><html><head><meta charset="utf-8"><title>sessperf-probe</title></head>
<body><div id="host"></div>
<script type="module">
import * as LS from '/js/localStore.js';
import { avatarNodeFor, avatarSrc } from '/js/avatarRender.js';
import { setCacheAccount, getCacheAccount } from '/js/fmMessageCache.js';
import { key } from '/js/branding.js';
try { localStorage.setItem(key('token'), 'probe-token'); } catch {}
window.__LS = LS;
window.__AR = { avatarNodeFor, avatarSrc };
window.__MC = { setCacheAccount, getCacheAccount };
window.__booted = true;
</script></body></html>`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 读数累加器 ───────────────────────────────────────────
const checks = [];
function check(name, ok, detail) {
  checks.push({ name, ok: !!ok, detail: detail === undefined ? '' : String(detail) });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== undefined ? `  — ${detail}` : ''}`);
}

async function main() {
  readFileSync(join(WEB, 'js', 'localStore.js'), 'utf8'); // 早失败：web 树必须可达
  console.log(`[sf] web tree = ${WEB}`);
  const browser = await chromium.launch({ headless: !process.env.SF_HEADED });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  /** 网络日志：只记「数据面」请求（静态资源另计），逐条带阶段标签。 */
  const netLog = [];
  const netCount = (phase, kind) => netLog.filter((r) => r.phase === phase && (!kind || r.kind === kind)).length;
  let phase = 'boot';

  const serveStatic = async (route) => {
    const u = new URL(route.request().url());
    try {
      if (u.pathname === '/' || u.pathname === '/__probe.html') {
        return route.fulfill({ body: SHELL, contentType: 'text/html' });
      }
      const file = join(WEB, decodeURIComponent(u.pathname));
      return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' });
    } catch {
      return route.fulfill({ status: 404, body: '' });
    }
  };
  const installRoutes = async () => {
    // 🔴 匹配优先级 = **注册序的反序**（后注册者先匹配）⇒ 越专有的路由越晚注册。
    // 首轮踩坑：`**/api/**` 最后注册 ⇒ 它先匹配走 `/api/avatars/{id}`，把新路由
    // 永远挡成 404，读数表现为「建缓存 0 次」——不是产品缺陷，是本文件的顺序错。
    await context.route('**/*', serveStatic);
    await context.route('**/api/**', (route) => route.fulfill({ status: 404, json: { error: 'not in this probe' } }));
    await context.route('**/avatars.test/**', async (route) => {
      netLog.push({ phase, kind: 'remote-avatar', url: route.request().url(), t: Date.now() });
      return route.fulfill({ body: PNG.u1, contentType: 'image/png' });
    });
    await context.route('**/api/avatars/**', async (route) => {
      const userId = route.request().url().split('/').pop();
      const body = PNG[userId] || PNG.u1;
      netLog.push({ phase, kind: 'peer-avatar-route', url: route.request().url(), t: Date.now() });
      return route.fulfill({
        body, contentType: 'image/png',
        headers: { 'X-Avatar-Sha256': SHA[userId] || SHA.u1, 'ETag': `"${SHA[userId] || SHA.u1}"`, 'Cache-Control': 'private, max-age=3600' },
      });
    });
  };
  await installRoutes();

  /** 开一扇窗（= 新文档；同 context ⇒ 同 origin ⇒ 同 IDB）。 */
  async function openWindow() {
    const page = await context.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(e.message));
    await page.goto(`${ORIGIN}/__probe.html`, { waitUntil: 'load' });
    await page.waitForFunction(() => window.__booted === true, null, { timeout: 15000 });
    await page.evaluate(async (acct) => {
      window.__MC.setCacheAccount(acct);
      await window.__LS.openLocalStore(acct);
    }, ACCT);
    return { page, errs };
  }
  /** 有界等待内存镜像预热（`warmMirror` 走 requestIdleCallback）。 */
  async function waitWarm(page, key, ms = 8000) {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      const v = await page.evaluate((k) => window.__LS.readAvatar(k), key);
      if (v) return true;
      await sleep(100);
    }
    return false;
  }

  const result = { web: WEB, ts: new Date().toISOString(), legs: {}, net: netLog };

  // ══ 窗口 1（冷）× ══════════════════════════════════════
  console.log('\n── 窗口 1（冷开窗）：远端取字节 + 建缓存 ──');
  const w1 = await openWindow();
  const coldEnabled = await w1.page.evaluate(() => window.__LS.isLocalStoreEnabled());
  check('本地层可用（L1 降级未触发）', coldEnabled === true, `isLocalStoreEnabled=${coldEnabled}`);
  const coldMiss = await w1.page.evaluate(() => window.__LS.readAvatar('u:u1'));
  check('冷窗：头像未命中（控制项，保证「零网络」不是恒绿）', coldMiss === null, `readAvatar=${coldMiss}`);

  phase = 'cold';
  await w1.page.evaluate(([u1, u2]) => {
    const host = document.getElementById('host');
    for (const p of [{ userId: 'u1', avatarUrl: u1 }, { userId: 'u2', avatarUrl: u2 }]) {
      const n = window.__AR.avatarNodeFor(p);
      if (n) host.appendChild(n);
    }
  }, [REMOTE.u1, REMOTE.u2]);
  await w1.page.waitForFunction(() => {
    const imgs = [...document.querySelectorAll('#host img')];
    return imgs.length === 2 && imgs.every((i) => i.complete && i.naturalWidth === 64);
  }, null, { timeout: 15000 });
  // 等「延迟建缓存」腿把字节补进磁盘层（有界）
  let coldMeta = null;
  for (let i = 0; i < 60; i++) {
    coldMeta = await w1.page.evaluate(() => window.__LS.avatarMeta('u:u1'));
    if (coldMeta && coldMeta.sha256) break;
    await sleep(150);
  }
  await sleep(400);
  const coldRemote = netCount('cold', 'remote-avatar');
  const coldRoute = netCount('cold', 'peer-avatar-route');
  check('冷窗：远端 URL 请求 ≥1（控制项）', coldRemote >= 1, `remote-avatar=${coldRemote}`);
  check('冷窗：同源新路由按人各取一次（有界：2 人 ⇒ 2 次）并落 sha256', coldRoute === 2 && !!coldMeta && coldMeta.sha256.length === 64,
    `peer-avatar-route=${coldRoute} sha256=${coldMeta ? coldMeta.sha256.slice(0, 12) : 'none'}…`);
  check('冷窗：X-Avatar-Sha256 与真字节 sha256 一致', !!coldMeta && coldMeta.sha256 === SHA.u1,
    `stored=${coldMeta && coldMeta.sha256.slice(0, 16)}… vs expected=${SHA.u1.slice(0, 16)}…`);

  // ── 腿 ③：游标增量写（窗口 1 内两次滑动窗口）──
  console.log('\n── 腿 ③：本地层（游标增量写 + 全量对账）──');
  const w1Ids = await w1.page.evaluate(async () => {
    const mk = (from, to, conv) => Array.from({ length: to - from }, (_, i) => {
      const n = from + i;
      return { id: n, convId: conv, body: `m${n}`, createdAtMs: 1700000000000 + n, senderId: 'u1' };
    });
    // 第 1 拍：窗口 [1..10]，水位 10（= 服务端 keyset 游标）
    await window.__LS.writeMessages('c1', mk(1, 11, 'c1'), { watermark: 10, lastMessageAt: 1700000000010 });
    // 第 2 拍：**增量合并**（旧窗口 + 新增 11..25），水位推进到 25
    await window.__LS.writeMessages('c1', mk(1, 26, 'c1').concat([{ id: 12, convId: 'c1', body: 'dup-12', createdAtMs: 1700000000012 }]), { watermark: 25, lastMessageAt: 1700000000025 });
    // 无可信水位 ⇒ 当无条目（纪律 1）：乐观临时 id（非数字）算不出可信 keyset 游标
    // （`after=0` 在服务端是「从最早开始」⇒ 拿它当增量锚会拉回最旧窗口）。
    await window.__LS.writeMessages('c2', [{ id: 'tmp-1', convId: 'c2', body: 'x', createdAtMs: 1 }], { watermark: 0 });
    const wm0 = window.__LS.readMessages('c2');
    const r = window.__LS.readMessages('c1');
    return { ids: r ? r.msgs.map((m) => m.id) : null, watermark: r ? r.watermark : null, fresh: r ? r.fresh : null, wm0IsNull: wm0 === null };
  });
  const expectIds = Array.from({ length: 25 }, (_, i) => String(i + 1));
  const incOk = w1Ids.ids && w1Ids.ids.length === 25 && JSON.stringify([...w1Ids.ids].sort((a, b) => a - b)) === JSON.stringify([...expectIds].sort((a, b) => a - b));
  check('增量写：25 条无丢无重（重复 id 已去重）+ 水位推进到 25',
    incOk && w1Ids.watermark === 25, `n=${w1Ids.ids ? w1Ids.ids.length : 'null'} watermark=${w1Ids.watermark}`);
  check('增量写：升序（读数序 = id 升序）', w1Ids.ids && JSON.stringify(w1Ids.ids) === JSON.stringify(expectIds),
    JSON.stringify((w1Ids.ids || []).slice(0, 4)) + '…');
  check('纪律：watermark≤0 ⇒ readMessages 当无条目（增量锚不可信）', w1Ids.wm0IsNull === true, `readMessages('c2')=null → ${w1Ids.wm0IsNull}`);
  const w1Stats = await w1.page.evaluate(() => window.__LS.stats());
  result.legs.window1 = { coldRemote, coldRoute, walSha: coldMeta && coldMeta.sha256, stats: w1Stats, ids: w1Ids.ids, watermark: w1Ids.watermark };

  // 同窗第二次渲染（内存层命中）——加一档「同窗热」
  phase = 'same-window-hot';
  await w1.page.evaluate((u) => { const n = window.__AR.avatarNodeFor({ userId: 'u1', avatarUrl: u }); document.getElementById('host').appendChild(n); }, REMOTE.u1);
  await sleep(300);
  check('同窗热渲染：对象 URL 命中 ⇒ 零网络', netCount('same-window-hot') === 0, `requests=${netCount('same-window-hot')}`);

  // ══ 窗口 2（二次开窗）× ════════════════════════════════
  console.log('\n── 腿 ①：二次开窗（新文档、同 context/同 IDB）──');
  const w2 = await openWindow();
  const warmOk = await waitWarm(w2.page, 'u:u1');
  check('二次开窗：镜像预热后 readAvatar(' + 'u:u1' + ') 命中', warmOk, `hit=${warmOk}`);
  const warmSrc = await w2.page.evaluate((u) => window.__AR.avatarSrc({ userId: 'u1', avatarUrl: u }), REMOTE.u1);
  check('二次开窗：src 解析为 blob:（本地层命中，非远端 URL）', typeof warmSrc === 'string' && warmSrc.startsWith('blob:'), String(warmSrc).slice(0, 24) + '…');
  phase = 'warm-window';
  const warmRender = await w2.page.evaluate(([u1, u2]) => {
    const host = document.getElementById('host');
    for (const p of [{ userId: 'u1', avatarUrl: u1 }, { userId: 'u2', avatarUrl: u2 }]) {
      const n = window.__AR.avatarNodeFor(p);
      if (n) host.appendChild(n);
    }
    return [...document.querySelectorAll('#host img')].map((i) => ({ src: i.getAttribute('src').slice(0, 12), w: i.naturalWidth }));
  }, [REMOTE.u1, REMOTE.u2]);
  await w2.page.waitForFunction(() => {
    const imgs = [...document.querySelectorAll('#host img')];
    return imgs.length === 2 && imgs.every((i) => i.complete && i.naturalWidth === 64);
  }, null, { timeout: 15000 });
  await sleep(500);
  const warmDom = await w2.page.evaluate(() => [...document.querySelectorAll('#host img')]
    .map((i) => ({ src: i.getAttribute('src').slice(0, 12), w: i.naturalWidth, complete: i.complete })));
  const warmNet = netCount('warm-window');
  check('腿 ① 判据：二次开窗头像**零网络请求**（远端 + 同源路由合计 0）', warmNet === 0,
    `requests=${warmNet} ${JSON.stringify(netLog.filter((r) => r.phase === 'warm-window'))}`);
  check('腿 ①：两枚头像均经 blob: 真解码（naturalWidth=64，非空白占位）',
    warmDom.length === 2 && warmDom.every((r) => r.src.startsWith('blob:') && r.w === 64),
    JSON.stringify(warmDom));
  check('腿 ①：二次开窗 ≠ 恒绿（同一读数方法在冷窗得到 ≥1 请求）', coldRemote >= 1 && coldRoute >= 1,
    `cold remote=${coldRemote} route=${coldRoute} vs warm=${warmNet}`);

  // ── 腿 ③（全量对账侧）：重开文档后从 IDB 重建的镜像 vs 增量写结果 ──
  const w2Read = await w2.page.evaluate(() => {
    const r = window.__LS.readMessages('c1');
    return { ids: r ? r.msgs.map((m) => m.id) : null, watermark: r ? r.watermark : null, sizes: window.__LS.localStoreSizes() };
  });
  check('全量对账：IDB 重建镜像的条目集 == 增量写结果（逐条相等）',
    w2Read.ids && JSON.stringify(w2Read.ids) === JSON.stringify(expectIds),
    `n=${w2Read.ids ? w2Read.ids.length : 'null'} 首/末=${w2Read.ids ? w2Read.ids[0] + '…' + w2Read.ids[w2Read.ids.length - 1] : 'null'}`);
  check('全量对账：水位（convmeta）跨文档保持 = 25', w2Read.watermark === 25, `watermark=${w2Read.watermark}`);
  result.legs.window2 = { warmNet, warmSrc, warmRender, ids: w2Read.ids, watermark: w2Read.watermark, sizes: w2Read.sizes, pageErrors: w2.errs };

  // ══ 腿 ②：断网渲缓存 ═══════════════════════════════════
  console.log('\n── 腿 ②：断网臂（setOffline + 卸掉全部路由）──');
  await context.unroute('**/*');
  await context.unroute('**/avatars.test/**').catch(() => {});
  await context.unroute('**/api/avatars/**').catch(() => {});
  await context.unroute('**/api/**').catch(() => {});
  await context.setOffline(true);
  const offline = await w2.page.evaluate(async () => {
    const out = { onLine: navigator.onLine, control: '' };
    try {
      const r = await fetch('http://offline-probe.test/ping', { cache: 'no-store' });
      out.control = 'ok:' + r.status;
    } catch (e) {
      out.control = 'neterr:' + (e && e.name ? e.name : 'err');
    }
    return out;
  });
  check('断网真生效（控制项）：未缓存 URL 取字节 ⇒ 网络失败', offline.onLine === false && offline.control.startsWith('neterr'),
    `navigator.onLine=${offline.onLine} control=${offline.control}`);
  const offlineBefore = netLog.length;
  const offlineRender = await w2.page.evaluate(([u1, u2]) => {
    const host = document.getElementById('host');
    host.innerHTML = '';
    const nodes = [{ userId: 'u1', avatarUrl: u1 }, { userId: 'u2', avatarUrl: u2 }]
      .map((p) => window.__AR.avatarNodeFor(p));
    for (const n of nodes) if (n) host.appendChild(n);
    return new Promise((res) => {
      const t0 = performance.now();
      const tick = () => {
        const imgs = [...document.querySelectorAll('#host img')];
        if (imgs.length === 2 && imgs.every((i) => i.complete && i.naturalWidth === 64)) {
          return res({ ok: true, ms: Math.round(performance.now() - t0), srcs: imgs.map((i) => i.getAttribute('src').slice(0, 12)) });
        }
        if (performance.now() - t0 > 8000) {
          return res({ ok: false, ms: Math.round(performance.now() - t0), srcs: imgs.map((i) => i.getAttribute('src').slice(0, 12)) });
        }
        requestAnimationFrame(tick);
      };
      tick();
    });
  }, [REMOTE.u1, REMOTE.u2]);
  check('腿 ② 判据：断网下载缓存头像仍真解码（blob: 渲染，零网络）',
    offlineRender.ok === true && offlineRender.srcs.every((s) => s.startsWith('blob:')) && netLog.length === offlineBefore,
    `decoded=${offlineRender.ok} in ${offlineRender.ms}ms newRequests=${netLog.length - offlineBefore}`);
  result.legs.offline = { onLine: offline.onLine, control: offline.control, render: offlineRender, newRequests: netLog.length - offlineBefore };

  // ══ 收尾 ═════════════════════════════════════════════
  const pageErrs = [...w1.errs, ...w2.errs];
  check('探针页零 JS 异常（pageerror）', pageErrs.length === 0, JSON.stringify(pageErrs.slice(0, 3)));
  result.legs.pageErrors = pageErrs;
  result.net = netLog;
  result.checks = checks;
  const failed = checks.filter((c) => !c.ok);
  result.verdict = failed.length === 0 ? 'PASS' : 'FAIL';
  result.failed = failed.map((f) => f.name);

  await context.setOffline(false).catch(() => {});
  await browser.close();

  console.log(`\n[sf] verdict = ${result.verdict}  (${checks.length - failed.length}/${checks.length} PASS)`);
  if (OUT) {
    mkdirSync(dirname(OUT), { recursive: true });
    writeFileSync(OUT, JSON.stringify(result, null, 2));
    console.log(`[sf] json -> ${OUT}`);
  }
  process.exit(result.verdict === 'PASS' ? 0 : 1);
}

main().catch((e) => { console.error('[sf] harness error:', e && e.stack ? e.stack : e); process.exit(2); });
