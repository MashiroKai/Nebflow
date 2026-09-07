#!/usr/bin/env node
// previewclip-shots.mjs — AskUser preview anti-clip R3 visual harness.
// Drives the REAL production code path (showOptions → buildOptionPreview)
// against the isolated instance, in both themes, and emits assertions +
// screenshots. Usage: node previewclip-shots.mjs <before|after> <baseUrl> <shotsDir>

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '/Users/dev/Claude code/Nebflow/node_modules/playwright/index.mjs';

const PHASE = process.argv[2] || 'before';
const BASE = process.argv[3] || 'http://localhost:8097';
const SHOTS = process.argv[4] || '/tmp/qa-previewclip/shots-r3';
const EXPECT_FIT = PHASE === 'before' ? 'cover' : 'contain';

const HOME = '/tmp/qa-previewclip';
const HOST_PID = 53186; // 环境宿主 PID —— 仅为防御性断言用（本脚本不 kill 任何进程）

function readToken() {
  const raw = fs.readFileSync(path.join(HOME, 'auth.json'), 'utf-8').trim();
  try { const p = JSON.parse(raw); return typeof p === 'string' ? p : raw; } catch { return raw; }
}

// Square viewBox-only SVG (NO width/height attrs) — the author's failing case.
// Rect stroke spans y=1..23 so cover-cropping the top/bottom is clearly visible.
const SVG_SQUARE =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' +
  '<rect x="1" y="1" width="22" height="22" rx="3" fill="none" stroke="#d9534f" stroke-width="1.6"/>' +
  '<path d="M6.5 12.5l3.5 3.5 7.5-8" fill="none" stroke="#3f9d6b" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>' +
  '</svg>';
// Same icon WITH explicit width/height — normalizer must leave this untouched.
const SVG_WITH_DIMS = SVG_SQUARE.replace(
  '<svg ', '<svg width="24" height="24" ');

const QUESTIONS = [
  {
    id: 'q1', question: 'Q1 · Square viewBox-only SVG (URL-encoded) — the reported clip case',
    allowOther: false,
    options: [
      { label: 'Icon (no w/h attrs)', preview: { type: 'image', src: 'data:image/svg+xml,' + encodeURIComponent(SVG_SQUARE) } },
      { label: 'Icon (has w/h attrs)', preview: { type: 'image', src: 'data:image/svg+xml,' + encodeURIComponent(SVG_WITH_DIMS) } },
    ],
  },
  {
    id: 'q2', question: 'Q2 · Square viewBox-only SVG (base64)', allowOther: false,
    options: [{ label: 'Icon b64', preview: { type: 'image', src: 'data:image/svg+xml;base64,' + Buffer.from(SVG_SQUARE).toString('base64') } }],
  },
  {
    id: 'q3', question: 'Q3 · Non-square raster PNG 160×60 (letterbox vs fill)', allowOther: false,
    options: [{ label: 'Gradient banner', preview: { type: 'image', src: '__RASTER__' } }],
  },
  {
    id: 'q4', question: 'Q4 · Swatch regression (must be unchanged)', allowOther: false,
    options: [{ label: 'Two colors', preview: { type: 'swatch', colors: ['#5b7fbf', '#3f9d6b'] } }],
  },
  {
    id: 'q5', question: 'Q5 · Broken src — E2 slot must hide itself', allowOther: false,
    options: [{ label: 'Broken icon', preview: { type: 'image', src: 'data:image/svg+xml,<not-svg' } }],
  },
  {
    id: 'q6', question: 'Q6 · No preview — E1 byte-identical plain button', allowOther: false,
    options: [{ label: 'Just text' }],
  },
];

const results = { phase: PHASE, base: BASE, themes: {}, allPass: true };
let failures = 0;
const fail = (theme, name, detail) => {
  results.allPass = false; failures++;
  results.themes[theme].failures.push(`${name}: ${detail}`);
};

for (const theme of ['light', 'dark']) {
  const t = { failures: [], consoleErrors: [], shots: [] };
  results.themes[theme] = t;
  const browser = await chromium.launch();
  const context = await browser.newContext({
    baseURL: BASE, colorScheme: theme, viewport: { width: 1280, height: 900 },
    deviceScaleFactor: 2,
  });
  await context.addInitScript((tok) => localStorage.setItem('nebflow_token', tok), readToken());
  const page = await context.newPage();
  page.on('pageerror', (e) => t.consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') t.consoleErrors.push(`console.error: ${m.text()}`); });

  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  // Onboarding overlay defensive skip (sealed OFF by default — should not appear)
  const ob = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 3000 }).then(() => true).catch(() => false);
  if (ob) {
    const skip = await page.$('#ob-skip');
    if (skip) await skip.click(); else await page.click('#ob-no').catch(() => {});
    await page.waitForTimeout(600);
  }

  // Mount the card through the REAL production entry point
  await page.evaluate(async ({ questions, phase }) => {
    const { showOptions } = await import('/js/chat.js');
    // Raster 160×60: corner markers + gradient — contain ⇒ big letterbox bars,
    // cover ⇒ left/right crop. Generated in-page via canvas.
    const c = document.createElement('canvas'); c.width = 160; c.height = 60;
    const g = c.getContext('2d');
    const grad = g.createLinearGradient(0, 0, 160, 0);
    grad.addColorStop(0, '#5b7fbf'); grad.addColorStop(1, '#3f9d6b');
    g.fillStyle = grad; g.fillRect(0, 0, 160, 60);
    g.fillStyle = '#d9534f'; g.fillRect(0, 0, 16, 16); g.fillRect(144, 44, 16, 16);
    g.strokeStyle = '#1b1e26'; g.lineWidth = 4;
    g.beginPath(); g.moveTo(0, 60); g.lineTo(160, 0); g.stroke();
    const qs = questions.map((q) => ({
      ...q,
      options: q.options.map((o) => (o.preview && o.preview.src === '__RASTER__'
        ? { ...o, preview: { type: 'image', src: c.toDataURL('image/png') } } : o)),
    }));
    const chat = document.getElementById('chat');
    const row = document.createElement('div'); row.className = 'row ai';
    const bubble = document.createElement('div'); bubble.className = 'bubble ai';
    row.appendChild(bubble); chat.appendChild(row);
    const cap = document.createElement('div');
    cap.style.cssText = 'font-size:12px;color:var(--color-text-muted);margin-bottom:8px';
    cap.textContent = `AskUser preview verification · phase=${phase} · theme follows OS`;
    bubble.appendChild(cap);
    showOptions(bubble, qs, () => {}, 'Confirm', () => {});
  }, { questions: QUESTIONS, phase: PHASE });

  // Wait until every non-broken preview image finished loading (nf-loaded)
  await page.waitForFunction(() => {
    const imgs = [...document.querySelectorAll('.option-preview .preview-img')];
    return imgs.every((im) => im.classList.contains('nf-loaded') ||
      im.closest('.option-preview').style.display === 'none');
  }, null, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(450); // fade-in transition settle

  // Assertions via the DOM
  const probe = await page.evaluate((expectedFit) => {
    // Decode a data-URI (URL-encoded or base64) payload back to text so
    // injected-dims checks see the real SVG markup.
    const dec = (s) => {
      if (typeof s !== 'string') return s;
      const c = s.indexOf(',');
      if (c < 0) return s;
      const h = s.slice(0, c + 1), b = s.slice(c + 1);
      if (/;base64/i.test(h)) {
        try { return new TextDecoder().decode(Uint8Array.from(atob(b), (ch) => ch.charCodeAt(0))); }
        catch { return s; }
      }
      try { return decodeURIComponent(b); } catch { return s; }
    };
    const btn = (label) => document.querySelector(`.option-btn[data-label="${label}"]`);
    const imgOf = (label) => btn(label)?.querySelector('.preview-img') || null;
    const out = {};
    const i1 = imgOf('Icon (no w/h attrs)');
    out.q1_objectFit = i1 ? getComputedStyle(i1).objectFit : 'MISSING';
    out.q1_loaded = i1 ? (i1.classList.contains('nf-loaded') && i1.complete) : false;
    out.q1_svgStart = i1 ? dec(i1.getAttribute('src')).slice(0, 40) : 'MISSING';
    out.q1_srcHasInjectedDims = i1 ? /^<svg width="24" height="24" /.test(dec(i1.getAttribute('src'))) : null;
    const i1b = imgOf('Icon (has w/h attrs)');
    out.q1b_srcUntouched = i1b ? /^<svg width="24" height="24" /.test(dec(i1b.getAttribute('src'))) : null;
    const i2 = imgOf('Icon b64');
    out.q2_objectFit = i2 ? getComputedStyle(i2).objectFit : 'MISSING';
    out.q2_loaded = i2 ? i2.classList.contains('nf-loaded') : false;
    out.q2_srcHasInjectedDims = i2 ? /^<svg width="24" height="24" /.test(dec(i2.getAttribute('src'))) : null;
    const i3 = imgOf('Gradient banner');
    out.q3_objectFit = i3 ? getComputedStyle(i3).objectFit : 'MISSING';
    out.q3_natural = i3 ? `${i3.naturalWidth}x${i3.naturalHeight}` : 'MISSING';
    out.q3_loaded = i3 ? i3.classList.contains('nf-loaded') : false;
    const sw = btn('Two colors')?.querySelector('.option-preview');
    out.q4_swatchStripes = sw ? sw.querySelectorAll('.preview-swatch').length : 0;
    const bk = imgOf('Broken icon');
    out.q5_slotHidden = bk ? bk.closest('.option-preview').style.display === 'none' : false;
    const plain = btn('Just text');
    out.q6_noPreviewSlot = plain ? (plain.querySelector('.option-preview') === null &&
      !plain.classList.contains('has-preview')) : false;
    out.expectedFit = expectedFit;
    return out;
  }, EXPECT_FIT);

  t.probe = probe;
  if (probe.q1_objectFit !== EXPECT_FIT) fail(theme, 'q1.objectFit', `${probe.q1_objectFit} ≠ expected ${EXPECT_FIT}`);
  if (!probe.q1_loaded) fail(theme, 'q1.loaded', 'image did not reach nf-loaded');
  if (probe.q1_srcHasInjectedDims !== (PHASE === 'after')) fail(theme, 'q1.normalizer', `svgStart=${probe.q1_svgStart}`);
  if (probe.q1b_srcUntouched !== true) fail(theme, 'q1b.noDoubleInject', `svgStart=${probe.q1_svgStart}`);
  if (probe.q2_srcHasInjectedDims !== (PHASE === 'after')) fail(theme, 'q2.normalizer', 'base64 src dim injection state wrong');
  if (probe.q2_objectFit !== EXPECT_FIT) fail(theme, 'q2.objectFit', probe.q2_objectFit);
  if (!probe.q2_loaded) fail(theme, 'q2.loaded', 'b64 image did not load');
  if (probe.q3_objectFit !== EXPECT_FIT) fail(theme, 'q3.objectFit', probe.q3_objectFit);
  if (probe.q3_natural !== '160x60') fail(theme, 'q3.natural', probe.q3_natural);
  if (!probe.q3_loaded) fail(theme, 'q3.loaded', 'raster did not load');
  if (probe.q4_swatchStripes !== 2) fail(theme, 'q4.swatch', `${probe.q4_swatchStripes} stripes`);
  if (probe.q5_slotHidden !== true) fail(theme, 'q5.E2hide', 'broken-src slot not hidden');
  if (probe.q6_noPreviewSlot !== true) fail(theme, 'q6.E1plain', 'plain button carries preview markup');

  // Screenshots: card + key buttons, per theme
  fs.mkdirSync(SHOTS, { recursive: true });
  const shot = async (sel, name) => {
    const el = await page.$(sel);
    if (el) { const p = path.join(SHOTS, name); await el.screenshot({ path: p }); t.shots.push(name); }
  };
  await shot('.option-box', `${PHASE}-${theme}-card.png`);
  await shot('.option-btn[data-label="Icon (no w/h attrs)"]', `${PHASE}-${theme}-q1-btn.png`);
  await shot('.option-btn[data-label="Icon b64"]', `${PHASE}-${theme}-q2-btn.png`);
  await shot('.option-btn[data-label="Gradient banner"]', `${PHASE}-${theme}-q3-btn.png`);
  await shot('.option-btn[data-label="Two colors"]', `${PHASE}-${theme}-q4-btn.png`);
  await browser.close();
}

console.log(JSON.stringify({ ...results, failures }, null, 2));
process.exit(failures > 0 ? 1 : 0);
