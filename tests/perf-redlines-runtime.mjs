#!/usr/bin/env node
// perf-redlines-runtime.mjs — 性能设计红线的**运行时**面（性能红线固化批 2026-11-21）。
//
// ── 为什么有这条闸 ────────────────────────────────────────────────────────────
// 静态闸（scripts/check-perf-redlines.mjs）能看懂源码形态，但看不出**真的过线多少字节**：
// 诊断批实测 `/api/sessions/<id>/history` 10 991 449B、`/api/subagent-tasks/<id>`
// 1 110 156B，全部 identity 裸传，而任何编译/类型/既有测试都是绿的。字节与请求数
// 只能对着活的应答量。本闸就是那把尺子。
//
// ── 判据（阈值见下方 BUDGET，只许收紧不许放宽）───────────────────────────────
//  R1 · 首屏请求数上限
//  R2 · 首屏传输体积上限（压缩后出向字节）
//  R3 · 关键读接口逐条体积上限（identity 字节——与 gzip 无关的那条真预算）
//  R3b · 分页有效性：`?limit=50` 必须严格小于 `?limit=all`。这是本批的核心回归
//        判据——修前两者字节**完全相同**（10 991 449B）。
//  R4 · 压缩覆盖率：关键端点在 `Accept-Encoding: gzip` 下必须真回 gzip。
//
// ── 口径与被测实例 ───────────────────────────────────────────────────────────
// 默认测 `http://127.0.0.1:6138`（活面板）；`PERF_BASE=<url>` 可指向隔离实例。
// **只读**：只发 GET，不 switchSession、不发消息、不写盘。
//
// 用法：
//   node tests/perf-redlines-runtime.mjs                  # 判定（红 = exit 1）
//   PERF_BASE=http://127.0.0.1:8090 node tests/perf-redlines-runtime.mjs
//   node tests/perf-redlines-runtime.mjs --list           # 打印实测数字（不比阈值）
//
// 退出码：0 = 绿；1 = 红（超预算）；2 = 环境错误（浏览器/网络/凭据缺失）。

import { chromium } from '@playwright/test';

const BASE = (process.env.PERF_BASE || 'http://127.0.0.1:6138').replace(/\/$/, '');
const TOKEN = process.env.PERF_TOKEN || process.env.TOKEN || '';
const LIST = process.argv.includes('--list');
const SID = process.env.PERF_SID || '';

/**
 * 阈值表（作者 2026-11-21 11:37 令「按复测后的实测基线定红线数值」）。
 * 左侧 = 本批修后实测基线；右侧 = 红线（留余量，收紧方向 = 更小）。
 * 放宽任何一条都要作者裁定：这些数字是「面板在手机上能用」的下界。
 */
const BUDGET = {
  // R1/R2 实测（2026-11-21，隔离实例 /tmp/qa-panelperf + :8090，手机形态）：
  //   首屏 389 请求 / 出向 99 110B。诊断批在**活实例**上是 250 资源 / 3 352 375B
  //   ——数字不同是因为夹具只种了 6 个会话（`/api/sessions` 极小）。故 R1 按
  //   「结构件数」定预算（≈ 资源条数，会话量无关），R2 按活实例实测定。
  //   🔴 这两条是**结构性**预算：请求数涨 = 又给隧道腿多加了串行回声。
  firstPaintRequests: 450,
  firstPaintTransferBytes: 3600000,
  perEndpointBytes: {
    // 实测（夹具 120 条 delegate 记录 / 1 820 条任务行；**gzip 后**出向见 R4）：
    //   /api/sessions            1 708B
    //   /api/delegates       1 395 892B（夹具刻意放大；活实例实测 501 336B）
    //   history 默认页         562 746B（修前 10 991 449B）
    // 预算按**夹具刻意放大后的实测**定，故对活实例是宽松上界；收紧方向 = 更小。
    '/api/sessions': 400000,
    '/api/delegates': 1600000,
    '/api/sessions/:id/history': 1200000,
  },
  pageLimit: 50,
  gzipRequired: ['/api/sessions', '/api/delegates', '/api/plugins'],
  /** gzip 后出向/解码字节比上限（实测 24%–41%；75% 已算「几乎没压」）。 */
  gzipMaxRatio: 0.75,
};

const fails = [];
const notes = [];
const fail = (rule, message) => fails.push({ rule, message });
const note = (rule, message) => notes.push({ rule, message });

async function fetchSized(ctx, path, extraHeaders) {
  // Same-origin requirement: a blank about:blank page cannot fetch the gateway.
  const probe = await ctx.newPage();
  try {
    if (!probe.url().startsWith(BASE)) {
      await probe.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 120000 });
    }
    if (TOKEN) await probe.evaluate(function (t) { try { localStorage.setItem('stand_token', t); } catch (_) {} }, TOKEN);
    return await probe.evaluate(async function (args) {
      // The probe page is a same-origin blank tab: pin the token from Node
      // rather than relying on localStorage seeding (which the init-script
      // sandbox does not guarantee for `about:blank` navigations).
      const token = args.token || localStorage.getItem('stand_token') || '';
      const headers = Object.assign({}, token ? { Authorization: 'Bearer ' + token } : {}, args.extraHeaders || {});
      // 🔴 `fetch` **ignores** `Accept-Encoding`（forbidden request header）：浏览器
      // 恒发 `gzip, deflate, br` 并**透明解码**，所以这里的字节数与响应头都证明
      // 不了压缩是否发生。
      //   · 预算腿（R3）= **解码后**字节：那才是真的载荷预算，且与编码无关；
      //   · 覆盖腿（R4）= CDP 的 encodedDataLength（出向字节），见下面的 census。
      const r = await fetch(args.base + args.path, { headers: headers });
      const buf = await r.arrayBuffer();
      return {
        status: r.status,
        bytes: buf.byteLength,
        encoding: 'decoded',
        contentType: r.headers.get('content-type') || '',
      };
    }, { base: BASE, path: path, token: TOKEN, extraHeaders: extraHeaders || {} });
  } finally {
    await probe.close();
  }
}

const pct = (n) => (n * 100).toFixed(0) + '%';

/**
 * 单端点**出向字节**探针：新起一个上下文，靠 CDP 读这一次请求的
 * `encodedDataLength` 与 `Content-Encoding`。这是唯一能看到「服务器到底压了没有」
 * 的观测面（见 [[fetchSized]] 头注）。
 */
async function wireProbe(browser, path) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  try {
    if (TOKEN) {
      await ctx.addInitScript(
        "try{localStorage.setItem('stand_token', " + JSON.stringify(TOKEN) + ');}catch(_){}',
      );
    }
    const page = await ctx.newPage();
    const cdp = await ctx.newCDPSession(page);
    await cdp.send('Network.enable');
    let bytes = 0;
    let encoding = null;
    let requestId = null;
    cdp.on('Network.responseReceived', (e) => {
      if (!(e.response.url || '').endsWith(path)) return;
      requestId = e.requestId;
      bytes = Math.max(bytes, e.response.encodedDataLength || 0);
      const h = e.response.headers || {};
      encoding = h['content-encoding'] || h['Content-Encoding'] || null;
    });
    // The authoritative encoded size lands on `loadingFinished`, not on
    // `responseReceived` (which fires before the body is transferred).
    cdp.on('Network.loadingFinished', (e) => {
      if (requestId && e.requestId === requestId) bytes = Math.max(bytes, e.encodedDataLength || 0);
    });
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.evaluate(async function (args) {
      const headers = args.token ? { Authorization: 'Bearer ' + args.token } : {};
      await (await fetch(args.base + args.path, { headers: headers })).arrayBuffer();
    }, { base: BASE, path: path, token: TOKEN });
    await page.waitForTimeout(400);
    return { bytes, encoding };
  } finally {
    await ctx.close();
  }
}

(async () => {
  const browser = await chromium.launch();
  try {
    // ── R1/R2: first paint ──────────────────────────────────────────────
    const ctx = await browser.newContext({
      viewport: { width: 390, height: 844 },
      isMobile: true,
      hasTouch: true,
      deviceScaleFactor: 2,
      userAgent:
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
    });
    if (TOKEN) {
      await ctx.addInitScript(
        "try{localStorage.setItem('stand_token', " + JSON.stringify(TOKEN) + ');}catch(_){}',
      );
    }
    const page = await ctx.newPage();
    const cdp = await ctx.newCDPSession(page);
    await cdp.send('Network.enable');
    const seen = new Map();
    cdp.on('Network.responseReceived', (e) => {
      const len = e.response.encodedDataLength || 0;
      if (!len) return;
      const u = (e.response.url || '').replace(BASE, '').split('?')[0];
      seen.set(u, (seen.get(u) || 0) + len);
    });
    await page.goto(BASE + '/', { waitUntil: 'load', timeout: 120000 });
    await page.evaluate(() => new Promise((r) => setTimeout(r, 4000)));
    const requests = seen.size;
    const transfer = [...seen.values()].reduce((a, b) => a + b, 0);
    console.log(
      '[runtime] 首屏请求 ' + requests + ' 条 / 传输 ' + transfer + 'B' +
      '（预算 ' + BUDGET.firstPaintRequests + ' / ' + BUDGET.firstPaintTransferBytes + 'B）',
    );
    if (LIST) {
      const top = [...seen.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12);
      for (const pair of top) console.log('   ' + pair[1] + 'B  ' + pair[0]);
    }
    if (requests > BUDGET.firstPaintRequests) {
      fail('R1', '首屏请求 ' + requests + ' 条 > 预算 ' + BUDGET.firstPaintRequests);
    }
    if (transfer > BUDGET.firstPaintTransferBytes) {
      fail('R2', '首屏传输 ' + transfer + 'B > 预算 ' + BUDGET.firstPaintTransferBytes + 'B');
    }

    // ── R3: representative read endpoints ───────────────────────────────
    let sid = SID;
    if (!sid) {
      const probe = await ctx.newPage();
      sid = await probe.evaluate(async function (args) {
        const headers = args.token ? { Authorization: 'Bearer ' + args.token } : {};
        const r = await fetch(args.base + '/api/sessions', { headers: headers });
        if (!r.ok) return '';
        const j = await r.json().catch(() => null);
        return (j && (j.activeId || (j.sessions && j.sessions[0] && j.sessions[0].id))) || '';
      }, { base: BASE, token: TOKEN });
      await probe.close();
    }

    const paths = ['/api/sessions', '/api/delegates'];
    if (sid) paths.push('/api/sessions/' + encodeURIComponent(sid) + '/history');

    for (const path of paths) {
      const r = await fetchSized(ctx, path);
      console.log('[runtime] ' + path + ' -> ' + r.status + ' ' + r.bytes + 'B (' + r.encoding + ')');
      if (r.status !== 200) {
        note('R3', path + ' 返回 ' + r.status + '（本闸只量 200 的体）');
        continue;
      }
      const key = path.replace(/\/sessions\/[^/]+\/history$/, '/api/sessions/:id/history');
      const budget = BUDGET.perEndpointBytes[key];
      if (budget && r.bytes > budget) fail('R3', path + ' ' + r.bytes + 'B > 预算 ' + budget + 'B');
    }

    // ── R3b: paging must actually page ──────────────────────────────────
    if (sid) {
      const hist = '/api/sessions/' + encodeURIComponent(sid) + '/history';
      const pageN = await fetchSized(ctx, hist + '?limit=' + BUDGET.pageLimit);
      const all = await fetchSized(ctx, hist + '?limit=all');
      const def = await fetchSized(ctx, hist);
      console.log(
        '[runtime] history 默认 ' + def.bytes + 'B / limit=' + BUDGET.pageLimit + ' ' +
        pageN.bytes + 'B / limit=all ' + all.bytes + 'B',
      );
      if (all.bytes > 0) {
        if (pageN.bytes >= all.bytes) {
          fail(
            'R3b',
            '?limit=' + BUDGET.pageLimit + ' (' + pageN.bytes + 'B) 不小于 ?limit=all (' +
            all.bytes + 'B) —— 历史接口又把分页参数忽略了（诊断批 10.7MB 事故形态）',
          );
        }
        if (def.bytes >= all.bytes && all.bytes > BUDGET.perEndpointBytes['/api/sessions/:id/history']) {
          fail('R3b', '默认页 ' + def.bytes + 'B 等于全量 ' + all.bytes + 'B —— 默认页没走分页');
        }
      }
    } else {
      note('R3b', '拿不到会话 id（未授权/空实例）⇒ 分页有效性腿未执行');
    }

    // ── R4: compression must actually happen ───────────────────────────
    //
    // 判据只能从 **CDP 的出向字节**（`encodedDataLength`）读：`fetch` 既不能指定
    // `Accept-Encoding`，又透明解码，所以页面里那层数字永远是解码后的膨胀字节。
    // 这里对每个关键端点单独起一次 CDP 记录，取该 URL 的 encoded 字节 + 浏览器
    // 实际收到的 `Content-Encoding`。
    for (const path of BUDGET.gzipRequired) {
      const wire = await wireProbe(browser, path);
      const decoded = await fetchSized(ctx, path);
      const ratio = decoded.bytes ? wire.bytes / decoded.bytes : 1;
      console.log(
        '[runtime] gzip ' + path + ': 解码 ' + decoded.bytes + 'B → 出向 ' + wire.bytes + 'B (' +
        pct(ratio) + ', ce=' + (wire.encoding || 'none') + ')',
      );
      if (wire.encoding !== 'gzip') {
        fail('R4', path + ' 未压缩出向（ce=' + (wire.encoding || 'none') + '）');
      } else if (ratio > BUDGET.gzipMaxRatio) {
        fail('R4', path + ' gzip 后仍有 ' + pct(ratio) + '（预算 ' + pct(BUDGET.gzipMaxRatio) + '）');
      }
    }

    await ctx.close();
  } finally {
    await browser.close();
  }

  for (const n of notes) console.log('[runtime] note ' + n.rule + ': ' + n.message);

  if (fails.length) {
    console.error('✗ 性能红线（运行时）— RED');
    for (const f of fails) console.error('  · ' + f.rule + ' ' + f.message);
    process.exit(1);
  }
  console.log('✓ 性能红线（运行时）— GREEN');
})().catch((e) => {
  console.error('runtime-perf ERROR: ' + ((e && e.message) || e));
  process.exit(2);
});
