// capture-current.mjs — capture + measure the CURRENT mobile diff faces.
//
// Faces: delegate diff (Canvas 'filechanges' reader) and canvas ask diff
// (Canvas 'askreview' tab), at a真全屏 phone form (375x812), dark + light.
// Output: <EVID>/current/*.png + <EVID>/current/measurements.json
//
// run: node capture-current.mjs
import { chromium, boot, injectDelegate, openDelegateReader, injectAsk, ensureAskSession, raf } from './lib.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SURVEY = join(HERE, '..');
const EVID = process.env.EVID || '/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/current';
mkdirSync(EVID, { recursive: true });

const delegate = JSON.parse(readFileSync(join(SURVEY, 'fixtures', 'delegate.json'), 'utf8'));
const ask = JSON.parse(readFileSync(join(SURVEY, 'fixtures', 'ask.json'), 'utf8'));
const askLarge = JSON.parse(readFileSync(join(SURVEY, 'fixtures', 'ask-large.json'), 'utf8'));

const ASK_ABS = '/Users/kaiyu/Claude code/Stand/README.md';
const ASK_LARGE_ABS = '/Users/kaiyu/Claude code/Stand/src/main/resources/seed/plugins/visual-report/skills/wiki-docs/SKILL.md';
const W = 375, H = 812;

/** Screenshot after removing harness-artifact toasts (the mocked /api makes the
 *  real app raise "Canvas 保存失败" — a fixture artifact, not a product state). */
async function shoot(page, path) {
  await page.evaluate(() => {
    document.querySelectorAll('[class*="toast"], [class*="notice"], [class*="banner"]').forEach((e) => {
      if (e.tagName === 'DIV' || e.tagName === 'BUTTON') e.remove();
    });
  });
  await page.screenshot({ path });
}
const out = { viewport: { width: W, height: H }, dsf: 3, faces: {} };

// ── measurement helpers injected into the page ──────────────────────────────
const PAGE_METRICS = `
window.__srgb = (c) => { const m = String(c).match(/[\\d.]+/g).map(Number); return m.length >= 3 ? m : null; };
window.__lum = (rgb) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]); };
window.__over = (fg, bg) => { const a = fg.length > 3 ? fg[3] : 1; return [0,1,2].map(i => fg[i] * a + bg[i] * (1 - a)); };
window.__contrast = (fg, bg) => { const l1 = window.__lum(fg), l2 = window.__lum(bg); const hi = Math.max(l1,l2), lo = Math.min(l1,l2); return (hi + 0.05) / (lo + 0.05); };
window.__pageBg = () => { let e = document.body; const c = getComputedStyle(e).backgroundColor; return window.__srgb(c) || [255,255,255]; };
window.__rowColor = (el) => {
  const cs = getComputedStyle(el);
  const bgRaw = window.__srgb(cs.backgroundColor) || [0,0,0,0];
  const pageBg = window.__pageBg();
  const bg = window.__over(bgRaw, pageBg);
  const fg = window.__over(window.__srgb(cs.color), bg);
  return { fg, bg, ratio: +window.__contrast(fg, bg).toFixed(2), color: cs.color, backgroundColor: cs.backgroundColor };
};
window.__boxOf = (sel) => { const e = document.querySelector(sel); if (!e) return null; const r = e.getBoundingClientRect(); return { w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
window.__count = (sel) => document.querySelectorAll(sel).length;
window.__overflow = (sel) => { const e = document.querySelector(sel); if (!e) return null; return { clientW: e.clientWidth, scrollW: e.scrollWidth, innerW: window.innerWidth, needsHScroll: e.scrollWidth > e.clientWidth + 1, needsVScroll: e.scrollHeight > e.clientHeight + 1 }; };
window.__styleOf = (sel, props) => { const e = document.querySelector(sel); if (!e) return null; const cs = getComputedStyle(e); const o = {}; for (const p of props) o[p] = cs[p]; return o; };
window.__fpsDuringScroll = async (sel, durationMs = 1200) => {
  const el = document.querySelector(sel); if (!el) return null;
  const frames = []; let last = performance.now(); let stop = false;
  const tick = (t) => { frames.push(t - last); last = t; if (!stop) requestAnimationFrame(tick); };
  requestAnimationFrame(tick);
  const t0 = performance.now(); const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);
  while (performance.now() - t0 < durationMs) {
    const p = (performance.now() - t0) / durationMs;
    el.scrollTop = Math.floor(maxScroll * Math.min(1, p));
    await new Promise((r) => requestAnimationFrame(r));
  }
  stop = true;
  const f = frames.slice(3).sort((a, b) => a - b);
  const avg = f.reduce((a, b) => a + b, 0) / (f.length || 1);
  return { frames: f.length, avgMs: +avg.toFixed(2), p95Ms: +(f[Math.floor(f.length * 0.95)] || 0).toFixed(2), maxMs: +(f[f.length - 1] || 0).toFixed(2), approxFps: +(1000 / avg).toFixed(1) };
};
window.__longTasks = () => { const a = performance.getEntriesByType ? performance.getEntriesByType('longtask') : []; return a.map((x) => +x.duration.toFixed(1)); };
`;

async function measureDelegate(page) {
  return page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane[data-type="filechanges"]');
    const first = pane.querySelector('.nf-diff-row');
    return {
      paneSelector: '.canvas-tab-pane[data-type="filechanges"]',
      viewOverflow: window.__overflow('.canvas-tab-pane[data-type="filechanges"] .fcv-view'),
      bodyOverflow: window.__overflow('.canvas-tab-pane[data-type="filechanges"] .nf-diff-body'),
      rowCount: window.__count('.nf-diff-row'),
      lineNumberNodes: window.__count('[class*="lineno"], [class*="line-no"], .nf-diff-num'),
      intraLineNodes: window.__count('.nf-diff-row span[class*="word"], .nf-diff-row span[class*="diff-"]'),
      addContrast: window.__rowColor(pane.querySelector('.nf-diff-add') || first),
      delContrast: window.__rowColor(pane.querySelector('.nf-diff-del') || first),
      ctxContrast: window.__rowColor(pane.querySelector('.nf-diff-ctx') || first),
      rowStyle: window.__styleOf('.nf-diff-body', ['fontSize', 'lineHeight', 'whiteSpace', 'overflowX', 'fontFamily']),
      rowBox: window.__boxOf('.nf-diff-row'),
      fileHead: window.__styleOf('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead', ['position', 'top', 'height']),
      headpillBox: window.__boxOf('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill'),
      filestripRows: window.__count('.fcv-filestrip .nf-fc'),
      fileHeadVisibleInViewport: (() => { const e = pane.querySelector('.fcv-headpill'); if (!e) return null; const r = e.getBoundingClientRect(); return r.top >= 0 && r.bottom <= window.innerHeight; })(),
      touchTargets: [...pane.querySelectorAll('button, [role="option"], .nf-fc, .nf-rcpt-note')].map((e) => { const r = e.getBoundingClientRect(); return { cls: (e.className || '').toString().split(' ')[0], w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; }).slice(0, 12),
      headerText: (pane.querySelector('.fcv-headpill') || {}).textContent || '',
    };
  });
}

async function measureAsk(page, sid) {
  return page.evaluate((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="askreview:${id}"]`);
    const first = pane.querySelector('.nf-ar-line');
    return {
      paneSelector: `askreview:${id}`,
      bodyOverflow: window.__overflow(`.canvas-tab-pane[data-tab-id="askreview:${id}"] .nf-ar-body`),
      lineCount: window.__count('.nf-ar-line'),
      addCount: window.__count('.nf-ar-line.add'),
      delCount: window.__count('.nf-ar-line.del'),
      ctxCount: window.__count('.nf-ar-line.ctx'),
      foldCount: window.__count('.nf-ar-fold'),
      lineNumberNodes: window.__count('[class*="lineno"], [class*="line-no"]'),
      intraLineNodes: window.__count('.nf-ar-line span'),
      addContrast: window.__rowColor(pane.querySelector('.nf-ar-line.add') || first),
      delContrast: window.__rowColor(pane.querySelector('.nf-ar-line.del') || first),
      ctxContrast: window.__rowColor(pane.querySelector('.nf-ar-line.ctx') || first),
      lineStyle: window.__styleOf('.nf-ar-body', ['fontSize', 'lineHeight', 'whiteSpace', 'overflow']),
      headStyle: window.__styleOf('.nf-ar-head', ['position', 'top', 'flexWrap']),
      hunkbarCount: window.__count('.nf-ar-hunkbar'),
      buttons: [...pane.querySelectorAll('.nf-ar-btn')].map((b) => { const r = b.getBoundingClientRect(); return { cls: b.className.split(' ').pop(), w: +r.width.toFixed(1), h: +r.height.toFixed(1), text: (b.textContent || '').trim().slice(0, 18) }; }),
      foldBox: window.__boxOf('.nf-ar-fold'),
      headPillBox: window.__boxOf('.nf-ar-headpill'),
      headActionsBox: window.__boxOf('.nf-ar-actions'),
      hunksRendered: window.__count('.nf-ar-hunk'),
    };
  }, sid);
}

function emptyScroll(sel) { return { clientW: 0, scrollW: 0, needsHScroll: false }; }

const browser = await chromium.launch();
const t = {};
try {
  for (const scheme of ['dark', 'light']) {
    // ── delegate face ──────────────────────────────────────────────────────
    {
      const { page, errors } = await boot(browser, { width: W, height: H, scheme, mobile: true, dsf: 3 });
      await page.addInitScript(PAGE_METRICS);
      await page.evaluate(PAGE_METRICS);
      await injectDelegate(page, delegate);
      await openDelegateReader(page);
      await raf(page, 3);
      await shoot(page, join(EVID, `delegate-${scheme}.png`));
      out.faces[`delegate-${scheme}`] = { ...(await measureDelegate(page)), pageErrors: errors };
      // horizontal-scroll proof: scroll the diff body far right and re-shoot
      const moved = await page.evaluate(() => {
        const b = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .nf-diff-body');
        if (!b) return null;
        b.scrollLeft = 99999;
        return { scrollLeft: b.scrollLeft, scrollW: b.scrollWidth, clientW: b.clientWidth };
      });
      await page.waitForTimeout(150);
      await shoot(page, join(EVID, `delegate-${scheme}-hscroll-right.png`));
      out.faces[`delegate-${scheme}`].hScrollProof = moved;
      t[`delegate-${scheme}`] = 1;
      await page.close();
    }
    // ── canvas ask face ────────────────────────────────────────────────────
    {
      const { page, errors } = await boot(browser, { width: W, height: H, scheme, mobile: true, dsf: 3 });
      await page.evaluate(PAGE_METRICS);
      const sid = await ensureAskSession(page, ASK_ABS, 'README.md');
      await injectAsk(page, { ...ask, sessionId: sid });
      await raf(page, 3);
      await shoot(page, join(EVID, `ask-${scheme}.png`));
      const m = await measureAsk(page, sid);
      // scroll to the first per-block action bar and shoot the interaction face
      await page.evaluate((id) => {
        const b = document.querySelector(`.canvas-tab-pane[data-tab-id="askreview:${id}"] .nf-ar-body`);
        const hb = document.querySelectorAll(`.canvas-tab-pane[data-tab-id="askreview:${id}"] .nf-ar-hunkbar`)[1];
        if (b && hb) b.scrollTop = hb.getBoundingClientRect().top - b.getBoundingClientRect().top + b.scrollTop - 120;
      }, sid);
      await page.waitForTimeout(200);
      await shoot(page, join(EVID, `ask-${scheme}-hunkbar.png`));
      out.faces[`ask-${scheme}`] = { ...m, pageErrors: errors };
      t[`ask-${scheme}`] = 1;
      await page.close();
    }
  }

  // ── perf probes (dark, one page) ─────────────────────────────────────────
  {
    const { page } = await boot(browser, { width: W, height: H, scheme: 'dark', mobile: true, dsf: 3 });
    await page.evaluate(PAGE_METRICS);
    // delegate: real large payload extracted from a real engine report
    const big = JSON.parse(readFileSync('/Users/kaiyu/Claude code/Stand/.nebflow/file-changes/n-35180ad8.json', 'utf8'));
    let best = null;
    for (const f of big.files) {
      if (typeof f.diffText !== 'string') continue;
      const n = f.diffText.split('\n').length;
      if (!best || n > best.n) best = { f, n };
    }
    const bigPayload = { nodeId: 'survey-big', generatedAt: Date.now(), root: big.root, files: [best.f] };
    await injectDelegate(page, bigPayload);
    const t0 = Date.now();
    await page.locator('.bubble.injected .nf-rcpt-note').last().click();
    await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .nf-diff-body', { state: 'attached', timeout: 20000 });
    const firstFrameMs = Date.now() - t0;
    await page.evaluate(() => import('/js/mobile/navController.js').then((m) => m.navigateTo('canvas')));
    await page.waitForTimeout(300);
    // wait until the loading face disappears (chunked render completed)
    let fullMs = null;
    for (let i = 0; i < 600; i++) { if (await page.locator('.nf-diff-loading').count() === 0) { fullMs = Date.now() - t0; break; } await page.waitForTimeout(50); }
    const bigMeasured = await measureDelegate(page);
    out.faces['delegate-large-dark'] = {
      sourceFile: best.f.path, sourceLines: best.n, firstFrameMs, fullMountMs: fullMs,
      rowCount: bigMeasured.rowCount, bodyOverflow: bigMeasured.bodyOverflow,
      fps: await page.evaluate(() => window.__fpsDuringScroll('.canvas-tab-pane[data-type="filechanges"] .fcv-view', 1200)),
    };
    await shoot(page, join(EVID, 'delegate-large-dark.png'));
    // ── ask: large real document, sync render cost ──
    const sid2 = await ensureAskSession(page, ASK_LARGE_ABS, 'SKILL.md');
    const t1 = Date.now();
    const renderMs = await page.evaluate(async (a) => {
      const mod = await import('/js/state.js');
      const s = mod.default;
      const t0 = performance.now();
      s.ws.onmessage({ data: JSON.stringify({ type: 'askEditApplied', sessionId: a.sessionId, changeId: 'c9', path: a.path, fileName: a.fileName, baseText: a.baseText, currentText: a.currentText, hunks: a.hunks, count: a.count, updatedAt: Date.now() }) });
      return +(performance.now() - t0).toFixed(1);
    }, { ...askLarge, sessionId: sid2 });
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid2}"] .nf-ar-body`, { state: 'attached', timeout: 20000 });
    const askFullMs = Date.now() - t1;
    await page.evaluate(() => import('/js/mobile/navController.js').then((m) => m.navigateTo('canvas')));
    await page.waitForTimeout(300);
    const askM = await measureAsk(page, sid2);
    out.faces['ask-large-dark'] = { ...askM, syncRenderMs: renderMs, totalMs: askFullMs, sourceLines: askLarge.baseText.split('\n').length,
      fps: await page.evaluate((id) => window.__fpsDuringScroll(`.canvas-tab-pane[data-tab-id="askreview:${id}"] .nf-ar-body`, 1200), sid2) };
    await shoot(page, join(EVID, 'ask-large-dark.png'));
    await page.close();
  }
} finally {
  await browser.close();
}
out.generatedAt = Date.now();
writeFileSync(join(EVID, 'measurements.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
console.log('SHOTS →', EVID);
