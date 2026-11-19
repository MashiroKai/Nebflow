// _canvas-save-failure-measure.mjs — reproduces the "Canvas 保存失败" notice and
// prints machine-readable readings (network + toast tally). Standalone node run:
//   node tests/_canvas-save-failure-measure.mjs
// Not collected by playwright (no @playwright/test import).

import { loadChromium, startSeatServer, bootPage, driveWrite, toasts } from './_canvas-save-failure-harness.mjs';

const chromium = loadChromium();
const FAIL = 'Canvas 保存失败';
const out = (k, v) => console.log(`MEASURE ${k} ${JSON.stringify(v)}`);

(async () => {
  const server = await startSeatServer(0);
  const browser = await chromium.launch({ headless: true });
  const results = {};
  try {
    // ── S0: success path — a lone window silently takes the seat and writes ──
    {
      const { context, page, errors } = await bootPage(browser, server.base);
      await driveWrite(page, 's0-tab');
      const t = await toasts(page);
      const puts = server.net.filter((n) => n.method === 'PUT' && n.path === '/api/canvas-tabs');
      results.S0 = {
        failureToasts: t.filter((x) => x.includes(FAIL)).length,
        allToasts: t, putStatuses: puts.map((p) => p.status), pageErrors: errors,
      };
      await context.close();
    }

    // ── S1: a vanished holder keeps the seat; THREE writes in ONE load ──
    server.clearSeat();
    server.net.length = 0;
    const ghost = await server.seedGhostSeat();
    out('ghostClaimStatus', ghost);
    {
      const { context, page, errors } = await bootPage(browser, server.base);
      for (let i = 0; i < 3; i++) await driveWrite(page, `s1-tab-${i}`);
      const t = await toasts(page);
      const claim = server.net.find((n) => n.path.endsWith('/claim') && n.status === 409);
      results.S1 = {
        failureToasts: t.filter((x) => x.includes(FAIL)).length,
        allToasts: t,
        claim,
        claimRefusals: server.net.filter((n) => n.path.endsWith('/claim') && n.status === 409).length,
        pageErrors: errors,
      };
      await context.close();
    }

    // ── S2: same stale seat, three consecutive page loads (the "老是") ──
    {
      const { context, page } = await bootPage(browser, server.base);
      const perLoad = [];
      for (let i = 0; i < 3; i++) {
        if (i > 0) await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForSelector('#activity-bar', { state: 'attached' });
        await driveWrite(page, `s2-tab-${i}`);
        const t = await toasts(page);   // page-global resets on reload ⇒ per-load count
        perLoad.push(t.filter((x) => x.includes(FAIL)).length);
      }
      results.S2 = { failureToastsPerLoad: perLoad, total: perLoad.reduce((a, b) => a + b, 0) };
      await context.close();
    }

    // ── S3: two live console windows (multi-open) — the second cannot write ──
    server.clearSeat();
    {
      const { context, page: a } = await bootPage(browser, server.base);
      await driveWrite(a, 's3-a');
      const b = await secondPage(context, server.base);
      await driveWrite(b, 's3-b');
      const ta = await toasts(a);
      const tb = await toasts(b);
      results.S3 = {
        windowA_failureToasts: ta.filter((x) => x.includes(FAIL)).length,
        windowB_failureToasts: tb.filter((x) => x.includes(FAIL)).length,
        windowB_toasts: tb,
      };
      await context.close();
    }

    console.log('MEASURE_RESULT ' + JSON.stringify(results, null, 2));
  } finally {
    await browser.close();
    await server.close();
  }
})().catch((e) => { console.error('HARNESS_ERROR', e); process.exit(2); });

// second page in the SAME context = same device clientId, new tabInstanceId
async function secondPage(context, base) {
  const page = await context.newPage();
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {}, config: JSON.stringify({ llm: { providers: {} }, features: {} }) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'test', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'test', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 'test', messages: [], hasMore: false, offset: 0 }));
  });
  await page.goto(base, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { state: 'attached' });
  await page.waitForTimeout(400);
  return page;
}
