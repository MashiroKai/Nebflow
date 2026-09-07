#!/usr/bin/env node
// previewclip-r4.mjs — AskUser preview anti-clip R4 acceptance harness.
// Real production path (showOptions → buildOptionPreview) against the
// isolated instance, both themes. Per image slot (q1 nodims, q1b hasdims,
// q2 b64, q3 raster) it pixel-scans the slot element screenshot for the ink
// bbox and asserts, in the after phase:
//   A. four-direction min ink margin ≥ 1.0 css px
//   B. |top-bottom| margin差 ≤ 2.0 css px (vertical symmetry), both axes ≤2
// In the before phase the same measurements are recorded and the harness
// asserts the RESIDUAL DEFECT reproduces (≥1 slot per theme violating A or B)
// — that is the R4 repro the author screenshotted.
// Structural regression net carried over from R3 (E1/E2/E3 + objectFit +
// normalizer dims + swatch stripes). Screenshots: card + q1/q1b/q2/q3/q4
// buttons per theme. Usage: node previewclip-r4.mjs <before|after> <base> <dir>
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '/Users/dev/Claude code/Nebflow/node_modules/playwright/index.mjs';

const PHASE = process.argv[2] || 'after';
const BASE = process.argv[3] || 'http://localhost:8097';
const SHOTS = process.argv[4] || '/tmp/qa-previewclip/shots-r4';
const HOME = '/tmp/qa-previewclip';

function readToken() {
  const raw = fs.readFileSync(path.join(HOME, 'auth.json'), 'utf-8').trim();
  try { const p = JSON.parse(raw); return typeof p === 'string' ? p : raw; } catch { return raw; }
}

const SVG_SQUARE =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' +
  '<rect x="1" y="1" width="22" height="22" rx="3" fill="none" stroke="#d9534f" stroke-width="1.6"/>' +
  '<path d="M6.5 12.5l3.5 3.5 7.5-8" fill="none" stroke="#3f9d6b" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>' +
  '</svg>';
const SVG_WITH_DIMS = SVG_SQUARE.replace('<svg ', '<svg width="24" height="24" ');

const QUESTIONS = [
  { id: 'q1', question: 'Q1 · Square viewBox-only SVG (URL-encoded) — the reported clip case', allowOther: false,
    options: [
      { label: 'Icon (no w/h attrs)', preview: { type: 'image', src: 'data:image/svg+xml,' + encodeURIComponent(SVG_SQUARE) } },
      { label: 'Icon (has w/h attrs)', preview: { type: 'image', src: 'data:image/svg+xml,' + encodeURIComponent(SVG_WITH_DIMS) } },
    ] },
  { id: 'q2', question: 'Q2 · Square viewBox-only SVG (base64)', allowOther: false,
    options: [{ label: 'Icon b64', preview: { type: 'image', src: 'data:image/svg+xml;base64,' + Buffer.from(SVG_SQUARE).toString('base64') } }] },
  { id: 'q3', question: 'Q3 · Non-square raster PNG 160×60 (letterbox vs fill)', allowOther: false,
    options: [{ label: 'Gradient banner', preview: { type: 'image', src: '__RASTER__' } }] },
  { id: 'q4', question: 'Q4 · Swatch regression (must be unchanged)', allowOther: false,
    options: [{ label: 'Two colors', preview: { type: 'swatch', colors: ['#5b7fbf', '#3f9d6b'] } }] },
  { id: 'q5', question: 'Q5 · Broken src — E2 slot must hide itself', allowOther: false,
    options: [{ label: 'Broken icon', preview: { type: 'image', src: 'data:image/svg+xml,<not-svg' } }] },
  { id: 'q6', question: 'Q6 · No preview — E1 byte-identical plain button', allowOther: false,
    options: [{ label: 'Just text' }] },
];

const PIXEL_SLOTS = [
  { label: 'Icon (no w/h attrs)', key: 'q1' },
  { label: 'Icon (has w/h attrs)', key: 'q1b' },
  { label: 'Icon b64', key: 'q2' },
  { label: 'Gradient banner', key: 'q3' },
];

const results = { phase: PHASE, base: BASE, themes: {}, allPass: true, pixel: {} };
let failures = 0;
const fail = (theme, name, detail) => {
  results.allPass = false; failures++;
  results.themes[theme].failures.push(`${name}: ${detail}`);
};

for (const theme of ['light', 'dark']) {
  const t = { failures: [], consoleErrors: [], shots: [], pixel: {} };
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
  // Capture 400 response URLs so the pre-existing tip noise (R3 archives,
  // both phases/themes) is distinguishable from anything new.
  page.on('response', (r) => { if (r.status() >= 400) t.consoleErrors.push(`http${r.status()}: ${r.url()}`); });

  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const ob = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 3000 }).then(() => true).catch(() => false);
  if (ob) {
    const skip = await page.$('#ob-skip');
    if (skip) await skip.click(); else await page.click('#ob-no').catch(() => {});
    await page.waitForTimeout(600);
  }

  await page.evaluate(async ({ questions, phase }) => {
    const { showOptions } = await import('/js/chat.js');
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

  await page.waitForFunction(() => {
    const imgs = [...document.querySelectorAll('.option-preview .preview-img')];
    return imgs.every((im) => im.classList.contains('nf-loaded') ||
      im.closest('.option-preview').style.display === 'none');
  }, null, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(450);

  // ---- structural regression net (R3 carry-over) ----
  const probe = await page.evaluate(() => {
    const dec = (s) => {
      if (typeof s !== 'string') return s;
      const c = s.indexOf(','); if (c < 0) return s;
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
    out.q1_marginTop = i1 ? getComputedStyle(i1).marginTop : 'MISSING';
    out.q1_loaded = i1 ? (i1.classList.contains('nf-loaded') && i1.complete) : false;
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
    return out;
  });

  t.probe = probe;
  if (probe.q1_objectFit !== 'contain') fail(theme, 'q1.objectFit', probe.q1_objectFit);
  if (!probe.q1_loaded) fail(theme, 'q1.loaded', 'image did not reach nf-loaded');
  if (probe.q1_srcHasInjectedDims !== true) fail(theme, 'q1.normalizer', 'dim injection state wrong');
  if (probe.q1b_srcUntouched !== true) fail(theme, 'q1b.noDoubleInject', 'src with dims was touched');
  if (probe.q2_srcHasInjectedDims !== true) fail(theme, 'q2.normalizer', 'base64 src dim injection state wrong');
  if (probe.q2_objectFit !== 'contain') fail(theme, 'q2.objectFit', probe.q2_objectFit);
  if (!probe.q2_loaded) fail(theme, 'q2.loaded', 'b64 image did not load');
  if (probe.q3_objectFit !== 'contain') fail(theme, 'q3.objectFit', probe.q3_objectFit);
  if (probe.q3_natural !== '160x60') fail(theme, 'q3.natural', probe.q3_natural);
  if (!probe.q3_loaded) fail(theme, 'q3.loaded', 'raster did not load');
  if (probe.q4_swatchStripes !== 2) fail(theme, 'q4.swatch', `${probe.q4_swatchStripes} stripes`);
  if (probe.q5_slotHidden !== true) fail(theme, 'q5.E2hide', 'broken-src slot not hidden');
  if (probe.q6_noPreviewSlot !== true) fail(theme, 'q6.E1plain', 'plain button carries preview markup');
  // R4-specific structural: margin bleed-through severed (after phase only)
  if (PHASE === 'after' && probe.q1_marginTop !== '0px') {
    fail(theme, 'q1.marginBleed', `computed margin-top=${probe.q1_marginTop} (expected 0px)`);
  }
  if (PHASE === 'before' && probe.q1_marginTop !== '4px') {
    fail(theme, 'before.q1.marginBleed', `expected 4px repro, got ${probe.q1_marginTop}`);
  }

  // ---- pixel assertions on the 4 image slots ----
  for (const { label, key } of PIXEL_SLOTS) {
    const el = await page.$(`.option-btn[data-label="${label}"] .option-preview`);
    if (!el) { fail(theme, `${key}.slot`, 'slot not found'); continue; }
    const buf = await el.screenshot();
    const m = await page.evaluate(async (b64) => {
      const im = new Image(); im.src = 'data:image/png;base64,' + b64; await im.decode();
      const c = document.createElement('canvas'); c.width = im.naturalWidth; c.height = im.naturalHeight;
      const g = c.getContext('2d', { willReadFrequently: true });
      g.drawImage(im, 0, 0);
      const W = c.width, H = c.height;
      const ring = [];
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++)
        if (x < 2 || x >= W - 2 || y < 2 || y >= H - 2) ring.push(g.getImageData(x, y, 1, 1).data);
      const med = (ch) => { const a = ring.map(p => p[ch]).sort((m2, n) => m2 - n); return a[a.length >> 1]; };
      const bg = [med(0), med(1), med(2)];
      let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const p = g.getImageData(x, y, 1, 1).data;
        const dev = Math.max(Math.abs(p[0] - bg[0]), Math.abs(p[1] - bg[1]), Math.abs(p[2] - bg[2]));
        if (dev > 25) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
      }
      if (x1 < 0) return { ink: 'EMPTY' };
      const dpr = 2;
      return { shotPx: `${W}x${H}`,
        margins: { top: +(y0 / dpr).toFixed(2), bottom: +((H - 1 - y1) / dpr).toFixed(2),
                   left: +(x0 / dpr).toFixed(2), right: +((W - 1 - x1) / dpr).toFixed(2) } };
    }, buf.toString('base64'));
    if (!m.margins) { fail(theme, `${key}.ink`, 'no ink found in slot'); continue; }
    const { top, bottom, left, right } = m.margins;
    const min4 = Math.min(top, bottom, left, right);
    const dV = Math.abs(top - bottom), dH = Math.abs(left - right);
    t.pixel[key] = { margins: m.margins, min4, dV, dH };
    if (PHASE === 'after') {
      if (min4 < 1.0) fail(theme, `${key}.min4`, `four-dir min ${min4} < 1.0 (${JSON.stringify(m.margins)})`);
      if (dV > 2.0) fail(theme, `${key}.symV`, `|top-bottom| ${dV.toFixed(2)} > 2.0`);
      if (dH > 2.0) fail(theme, `${key}.symH`, `|left-right| ${dH.toFixed(2)} > 2.0`);
    } else {
      if (min4 < 1.0 || dV > 2.0) t.pixel[key].repro = true;
    }
  }
  if (PHASE === 'before') {
    const repro = Object.values(t.pixel).filter(p => p.repro).length;
    if (repro === 0) fail(theme, 'before.repro', 'residual defect did not reproduce at tip 4dfec825');
  }

  // ---- screenshots: card + q1/q1b/q2/q3/q4 buttons ----
  fs.mkdirSync(SHOTS, { recursive: true });
  const shot = async (sel, name) => {
    const el = await page.$(sel);
    if (el) { const p = path.join(SHOTS, name); await el.screenshot({ path: p }); t.shots.push(name); }
  };
  await shot('.option-box', `${PHASE}-${theme}-card.png`);
  await shot('.option-btn[data-label="Icon (no w/h attrs)"]', `${PHASE}-${theme}-q1-btn.png`);
  await shot('.option-btn[data-label="Icon (has w/h attrs)"]', `${PHASE}-${theme}-q1b-btn.png`);
  await shot('.option-btn[data-label="Icon b64"]', `${PHASE}-${theme}-q2-btn.png`);
  await shot('.option-btn[data-label="Gradient banner"]', `${PHASE}-${theme}-q3-btn.png`);
  await shot('.option-btn[data-label="Two colors"]', `${PHASE}-${theme}-q4-btn.png`);
  // Console gate: everything fails EXCEPT the pre-existing tip noise — the
  // app's background `GET /api/nf-file` probe 400s on the isolated instance
  // (present in R3 harness archives at tip 4dfec825, both phases/themes; it
  // surfaces twice: once as the response event, once as console.error).
  const PRE = [
    /^http400: \S+\/api\/nf-file$/,
    /^console\.error: Failed to load resource: the server responded with a status of 400 \(Bad Request\)$/,
  ];
  const newConsole = t.consoleErrors.filter((e) => !PRE.some((re) => re.test(e)));
  t.consoleNoise = t.consoleErrors.length - newConsole.length;
  if (newConsole.length) fail(theme, 'console', newConsole.join(' | '));
  await browser.close();
}

console.log(JSON.stringify({ ...results, failures }, null, 1));
process.exit(failures > 0 ? 1 : 0);
