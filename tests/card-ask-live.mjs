#!/usr/bin/env node
// card-ask-live.mjs — LIVE acceptance for the revived Card tool in Canvas Ask
// (card-tool-revive batch, 2026-11-18). Zero production touch: it only talks to
// an isolated engine booted against its own home on its own port.
//
//   preflight: node tests/fixtures/card-ask/provision.mjs
//              node tests/fixtures/card-ask/mock-llm.mjs 19490          (background)
//              sbt -batch "run --home /tmp/card-ask-live-home --port 8499 --no-browser --foreground start"
//
//   leg ws : real WS -> askOpen -> askQuestion('CARD_NOW …') -> the mock LLM
//            emits a Card tool call -> the REAL ask session runs the REAL
//            CardTool through the REAL dispatch path -> the REAL toolEnd frame
//            carries the REAL `___CARD_HTML___` payload. Frames are archived.
//   leg ui : a real browser at 375x812 (mobile, full screen) and 1600x1000
//            (desktop) drives the app page served by the isolated engine: the
//            fixture .md is opened as a canvas tab through the product's own
//            `workspace-open-item` path, the ask dock is opened, the same
//            question is typed, and the card the agent renders is screenshotted
//            (plus a webm recording -> GIF).
//
//   node tests/card-ask-live.mjs [ws|ui|all]
import WebSocket from 'ws';
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync, appendFileSync, existsSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const HOME = process.env.CARD_HOME || '/tmp/card-ask-live-home';
const FILE = process.env.CARD_FIXTURE || '/tmp/card-ask-live-fixture/能量谱抽样说明.md';
const NAME = FILE.split('/').pop();
const PORT = Number(process.env.CARD_PORT || 8499);
const BASE = `http://127.0.0.1:${PORT}`;
const EVIDENCE = process.env.CARD_EVIDENCE || '/Users/kaiyu/stand-evidence/card-tool-revive-20261118/05-live';
const QUESTION = 'CARD_NOW 请把本文档的三个抽样点读数渲染成一张卡片。';
const LEG = (process.argv[2] || 'all').toLowerCase();

mkdirSync(EVIDENCE, { recursive: true });
const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const rows = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  rows.push(line);
  console.log(line);
  return cond;
}

// ── L1: real WS against the real ask session ────────────────────────────────
async function legWs() {
  const frames = [];
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Authorization: `Bearer ${TOKEN}` } });
  const done = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WS leg timeout (60s)')), 60000);
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'askOpen', file: { absPath: FILE, name: NAME }, mainSessionId: '' }));
      setTimeout(() => ws.send(JSON.stringify({ type: 'askQuestion', sessionId: sidOf(FILE), content: QUESTION, mainSessionId: '' })), 400);
    });
    ws.on('message', (raw) => {
      let m; try { m = JSON.parse(raw.toString()); } catch { return; }
      frames.push(m);
      if (m.type === 'done') { clearTimeout(timer); resolve(); }
    });
    ws.on('error', reject);
  });
  await done;
  await sleep(300);
  try { ws.close(); } catch { /* already */ }
  writeFileSync(join(EVIDENCE, 'ws-frames.jsonl'), frames.map((f) => JSON.stringify(f)).join('\n') + '\n');

  const types = [...new Set(frames.map((f) => f.type))].sort();
  const toolStarts = frames.filter((f) => f.type === 'toolStart');
  const toolEnds = frames.filter((f) => f.type === 'toolEnd');
  const cardEnd = toolEnds.find((f) => typeof f.content === 'string' && f.content.includes('___CARD_HTML___'));
  const errFrames = frames.filter((f) => f.type === 'askError' || f.type === 'error');

  console.log(`[ws] frame types: ${types.join(', ')}`);
  console.log(`[ws] toolStart: ${JSON.stringify(toolStarts)}`);
  console.log(`[ws] toolEnd labels: ${JSON.stringify(toolEnds.map((f) => f.label))}`);

  ok('L1-1 the ask session received the question and finished (no error frame)',
    errFrames.length === 0, JSON.stringify(errFrames).slice(0, 300));
  ok('L1-2 the REAL Card tool ran inside the ask session (toolStart label = Card)',
    toolStarts.some((f) => (f.label || '').startsWith('Card')), JSON.stringify(toolStarts.map((f) => f.label)));
  ok('L1-3 the REAL toolEnd frame carries the REAL ___CARD_HTML___ payload',
    !!cardEnd, cardEnd ? '' : 'no card payload in toolEnd frames');

  if (cardEnd) {
    const json = JSON.parse(cardEnd.content.replace(/^___CARD_HTML___/, ''));
    writeFileSync(join(EVIDENCE, 'card-payload.json'), JSON.stringify(json, null, 2) + '\n');
    writeFileSync(join(EVIDENCE, 'card-html.html'), json.html + '\n');
    ok('L1-4 the rendered card is the one the model asked for (title + marker in html)',
      json.title === 'CARD-LIVE-OK' && String(json.html).includes('CARD-LIVE-OK'),
      `title=${json.title} htmlChars=${String(json.html).length}`);
    ok('L1-5 the payload came back through the normal card transport (fileRefs + persist present)',
      !!json.fileRefs && !!json.persist,
      `fileRefs=${JSON.stringify(json.fileRefs)} persist=${JSON.stringify(json.persist)}`);
    // The Card tool writes its render source to <dataRoot>/cards/ — a real
    // side-effect of the real execution.
    const persisted = json.persist && json.persist.ok === true && existsSync(json.persist.path);
    ok('L1-6 Card persisted its render source to the isolated data root', persisted,
      json.persist ? json.persist.path : 'no persist field');
  }
  return frames;
}

// ── L2: real browser, real app page, real render ────────────────────────────
async function legUi(frames) {
  const browser = await chromium.launch();
  const results = {};
  for (const [label, viewport, hasTouch, isMobile] of [
    ['mobile-375x812', { width: 375, height: 812 }, true, true],
    ['desktop-1600x1000', { width: 1600, height: 1000 }, false, false],
  ]) {
    const videoDir = join(EVIDENCE, `_video-${label}`);
    rmSync(videoDir, { recursive: true, force: true });
    mkdirSync(videoDir, { recursive: true });
    const ctx = await browser.newContext({
      viewport, hasTouch, isMobile, colorScheme: 'dark',
      recordVideo: { dir: videoDir, size: viewport },
    });
    await ctx.addInitScript(([tok]) => {
      localStorage.setItem('nebflow_token', tok);
      localStorage.setItem('neblink_token', tok);
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('neblink_locale', 'zh-CN');
    }, [TOKEN]);
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
    await page.goto(BASE, { waitUntil: 'domcontentloaded' });
    await sleep(3500);

    // Open the fixture .md as a canvas tab through the product's own path.
    await page.evaluate(([absPath, title]) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + absPath, title, itemType: '', content: '', absPath, pinned: true },
      }));
    }, [FILE, NAME]);

    // On mobile bring the canvas dock key to the front.
    if (hasTouch) {
      const key = await page.$('#mobile-dock .dock-key[data-dock="canvas"]');
      if (key) { await key.click().catch(() => {}); await sleep(1200); }
    }
    await page.waitForSelector('.canvas-tab-pane[data-tab-id="file:' + FILE + '"]', { state: 'visible', timeout: 15000 }).catch(() => {});
    await sleep(800);

    const fab = await page.$('.ask-fab');
    ok(`L2-1 [${label}] the canvas ask entry is mounted on the file tab`, !!fab);
    if (!fab) { await ctx.close(); results[label] = { fab: false }; continue; }
    const fabVisible = await page.evaluate(() => {
      const f = document.querySelector('.ask-fab');
      if (!f) return null;
      const r = f.getBoundingClientRect();
      const cs = getComputedStyle(f);
      return { w: r.width, h: r.height, display: cs.display, visibility: cs.visibility, opacity: cs.opacity };
    });
    if (!fabVisible || fabVisible.w === 0 || fabVisible.display === 'none') {
      const dbg = await page.evaluate(() => {
        const vis = (sel) => { const e = document.querySelector(sel); if (!e) return 'absent'; const cs = getComputedStyle(e); const r = e.getBoundingClientRect(); return `${cs.display}/${cs.visibility}/${Math.round(r.width)}x${Math.round(r.height)}`; };
        return {
          bodyClass: document.body.className,
          mobileLayer: document.body.classList.contains('mobile-layer'),
          canvasPanel: vis('#canvas-panel'), canvas: vis('.canvas'), canvasTabPane: vis('.canvas-tab-pane'),
          mobileDock: vis('#mobile-dock'), askFab: vis('.ask-fab'),
          dockKeys: [...document.querySelectorAll('#mobile-dock .dock-key')].map((k) => k.dataset.dock),
          tabs: [...document.querySelectorAll('.canvas-tab')].map((t) => t.dataset.tabId),
        };
      });
      await page.screenshot({ path: join(EVIDENCE, `debug-${label}.png`) });
      writeFileSync(join(EVIDENCE, `debug-${label}.json`), JSON.stringify(dbg, null, 2) + '\n');
      console.log(`[ui] ${label} debug: ${JSON.stringify(dbg)}`);
      // One retry through the mobile canvas dock key with a longer settle.
      const key = await page.$('#mobile-dock .dock-key[data-dock="canvas"]');
      if (key) { await key.click({ force: true }).catch(() => {}); await sleep(2500); }
      await page.screenshot({ path: join(EVIDENCE, `debug-${label}-after-key.png`) });
    }
    await fab.click({ timeout: 20000 });
    await sleep(500);
    await page.fill('.ask-dock textarea', QUESTION);
    await page.press('.ask-dock textarea', 'Enter');

    // The card arrives as a toolEnd frame over the real socket; cardRegistry.js
    // renders it into a sandboxed iframe.
    await page.waitForSelector('.ask-dock iframe.html-card-iframe', { timeout: 45000 }).catch(() => {});
    await sleep(2500);

    const read = await page.evaluate(() => {
      const dock = document.querySelector('.ask-dock');
      const ifr = document.querySelector('.ask-dock iframe.html-card-iframe');
      let srcdoc = '';
      try { srcdoc = ifr ? (ifr.getAttribute('srcdoc') || '') : ''; } catch { /* */ }
      return {
        dockOpen: dock ? !dock.classList.contains('closed') : null,
        cardIframes: document.querySelectorAll('.ask-dock iframe.html-card-iframe').length,
        srcdocHasMarker: srcdoc.includes('CARD-LIVE-OK'),
        srcdocChars: srcdoc.length,
        sandbox: ifr ? ifr.getAttribute('sandbox') : null,
        viewport: [innerWidth, innerHeight],
        text: dock ? dock.innerText.slice(0, 400) : '',
      };
    });
    // Inside the iframe (srcdoc = same-origin frame).
    let frameText = '';
    for (const f of page.frames()) {
      try { const t = await f.textContent('body'); if (t && t.includes('CARD-LIVE-OK')) { frameText = t.slice(0, 300); break; } } catch { /* */ }
    }
    const shot = join(EVIDENCE, `${label}.png`);
    await page.screenshot({ path: shot, fullPage: false });
    await sleep(900);
    const video = page.video();
    await ctx.close();
    const webmPath = video ? await video.path().catch(() => null) : null;
    if (webmPath && existsSync(webmPath)) {
      const target = join(EVIDENCE, `${label}.webm`);
      spawnSync('cp', [webmPath, target]);
      results[label] = { webm: target };
    }

    ok(`L2-2 [${label}] the ask dock rendered a card iframe in the ask stream`,
      read.cardIframes === 1, JSON.stringify({ cardIframes: read.cardIframes, dockOpen: read.dockOpen }));
    ok(`L2-3 [${label}] the rendered card carries the marker the agent produced`,
      read.srcdocHasMarker && frameText.includes('CARD-LIVE-OK'),
      JSON.stringify({ srcdocHasMarker: read.srcdocHasMarker, frameText: frameText.slice(0, 120), srcdocChars: read.srcdocChars }));
    ok(`L2-4 [${label}] the card is sandboxed and lives at the requested viewport`,
      !!read.sandbox && read.viewport[0] === viewport.width && read.viewport[1] === viewport.height,
      JSON.stringify({ sandbox: read.sandbox, viewport: read.viewport }));
    ok(`L2-5 [${label}] zero pageerror`, errors.length === 0, errors.join('; '));
    results[label] = { ...results[label], read, shot, errors };
  }
  await browser.close();
  writeFileSync(join(EVIDENCE, 'ui-readings.json'), JSON.stringify(results, null, 2) + '\n');
  return results;
}

let frames = [];
try {
  if (LEG === 'ws' || LEG === 'all') frames = await legWs();
  if (LEG === 'ui' || LEG === 'all') await legUi(frames);
} catch (e) {
  ok('LIVE leg crashed', false, String(e && e.message ? e.message : e));
}
writeFileSync(join(EVIDENCE, 'readings.txt'), rows.join('\n') + '\n');
const failed = rows.filter((r) => r.startsWith('FAIL')).length;
console.log(`\n=== card-ask-live: ${rows.length - failed} pass, ${failed} fail ===`);
process.exit(failed ? 1 : 0);
