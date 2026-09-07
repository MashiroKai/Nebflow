#!/usr/bin/env node
// previewclip-r4-probe.mjs — pixel-level forensics probe (R4 diagnostic).
// Mounts the AskUser card through the REAL production path (showOptions →
// buildOptionPreview) and measures, per image slot:
//   L1 layout:  slot.getBoundingClientRect() vs img.getBoundingClientRect()
//               + computed objectFit/objectPosition/height
//   L2 svg-in:  drawImage(img) at 10× viewBox → alpha scan → content bbox in
//               viewBox units (does the SVG's own ink touch its viewBox edges?)
//   L3 render:  slot element screenshot → canvas scan → ink bbox margins vs
//               slot edges (what the author actually sees), CSS px @2x.
// Usage: node previewclip-r4-probe.mjs <baseUrl> <phase-label>
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '/Users/dev/Claude code/Nebflow/node_modules/playwright/index.mjs';

const BASE = process.argv[2] || 'http://localhost:8097';
const LABEL = process.argv[3] || 'probe';
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
  { id: 'q1', question: 'Q1 · Square viewBox-only SVG (URL-encoded)', allowOther: false,
    options: [
      { label: 'Icon (no w/h attrs)', preview: { type: 'image', src: 'data:image/svg+xml,' + encodeURIComponent(SVG_SQUARE) } },
      { label: 'Icon (has w/h attrs)', preview: { type: 'image', src: 'data:image/svg+xml,' + encodeURIComponent(SVG_WITH_DIMS) } },
    ] },
  { id: 'q2', question: 'Q2 · Square viewBox-only SVG (base64)', allowOther: false,
    options: [{ label: 'Icon b64', preview: { type: 'image', src: 'data:image/svg+xml;base64,' + Buffer.from(SVG_SQUARE).toString('base64') } }] },
  { id: 'q3', question: 'Q3 · Non-square raster PNG 160×60', allowOther: false,
    options: [{ label: 'Gradient banner', preview: { type: 'image', src: '__RASTER__' } }] },
  { id: 'q4', question: 'Q4 · Swatch control (fills slot)', allowOther: false,
    options: [{ label: 'Two colors', preview: { type: 'swatch', colors: ['#5b7fbf', '#3f9d6b'] } }] },
];

// In-page L2: SVG-internal ink bbox (viewBox units) via 10× drawImage alpha scan.
const L2_FN = `() => new Promise((resolve) => {
  const dec = (s) => { const c = s.indexOf(','); if (c < 0) return null;
    const h = s.slice(0, c + 1), b = s.slice(c + 1);
    if (/;base64/i.test(h)) { try { return new TextDecoder().decode(Uint8Array.from(atob(b), (ch) => ch.charCodeAt(0))); } catch { return null; } }
    try { return decodeURIComponent(b); } catch { return null; } }`;
// (built as string to keep evaluate payload simple; see usage below)

const out = { label: LABEL, base: BASE, dpr: 2, slots: [] };

const browser = await chromium.launch();
for (const theme of ['light', 'dark']) {
  const context = await browser.newContext({
    baseURL: BASE, colorScheme: theme, viewport: { width: 1280, height: 900 },
    deviceScaleFactor: 2,
  });
  await context.addInitScript((tok) => localStorage.setItem('nebflow_token', tok), readToken());
  const page = await context.newPage();
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const ob = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 3000 }).then(() => true).catch(() => false);
  if (ob) {
    const skip = await page.$('#ob-skip');
    if (skip) await skip.click(); else await page.click('#ob-no').catch(() => {});
    await page.waitForTimeout(600);
  }

  await page.evaluate(async ({ questions }) => {
    const { showOptions } = await import('/js/chat.js');
    const c = document.createElement('canvas'); c.width = 160; c.height = 60;
    const g = c.getContext('2d');
    const grad = g.createLinearGradient(0, 0, 160, 0);
    grad.addColorStop(0, '#5b7fbf'); grad.addColorStop(1, '#3f9d6b');
    g.fillStyle = grad; g.fillRect(0, 0, 160, 60);
    g.fillStyle = '#d9534f'; g.fillRect(0, 0, 16, 16); g.fillRect(144, 44, 16, 16);
    const qs = questions.map((q) => ({
      ...q,
      options: q.options.map((o) => (o.preview && o.preview.src === '__RASTER__'
        ? { ...o, preview: { type: 'image', src: c.toDataURL('image/png') } } : o)),
    }));
    const chat = document.getElementById('chat');
    const row = document.createElement('div'); row.className = 'row ai';
    const bubble = document.createElement('div'); bubble.className = 'bubble ai';
    row.appendChild(bubble); chat.appendChild(row);
    showOptions(bubble, qs, () => {}, 'Confirm', () => {});
  }, { questions: QUESTIONS });

  await page.waitForFunction(() => {
    const imgs = [...document.querySelectorAll('.option-preview .preview-img')];
    return imgs.length && imgs.every((im) => im.classList.contains('nf-loaded'));
  }, null, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(450);

  const labels = ['Icon (no w/h attrs)', 'Icon (has w/h attrs)', 'Icon b64', 'Gradient banner'];
  for (const label of labels) {
    const btn = await page.$(`.option-btn[data-label="${label}"]`);
    if (!btn) continue;
    const slot = await btn.$('.option-preview');
    const img = await btn.$('.preview-img');
    // L1 — layout rects
    const l1 = await page.evaluate(([slotEl, imgEl]) => {
      const rs = slotEl.getBoundingClientRect(), ri = imgEl.getBoundingClientRect();
      const cs = getComputedStyle(imgEl);
      return {
        slot: { x: rs.x, y: rs.y, w: rs.width, h: rs.height },
        img: { x: ri.x, y: ri.y, w: ri.width, h: ri.height },
        imgOffsetInSlot: { dx: +(ri.x - rs.x).toFixed(2), dy: +(ri.y - rs.y).toFixed(2) },
        natural: `${imgEl.naturalWidth}x${imgEl.naturalHeight}`,
        objectFit: cs.objectFit, objectPosition: cs.objectPosition,
        display: cs.display, lineHeightSlot: getComputedStyle(slotEl).lineHeight,
      };
    }, [slot, img]);
    // L2 — SVG-internal ink bbox in viewBox units (raster: skip)
    const l2 = await page.evaluate(`([imgEl]) => new Promise((resolve) => {
      try {
        const S = 10; // 10px per viewBox unit
        const c = document.createElement('canvas');
        c.width = imgEl.naturalWidth * S; c.height = imgEl.naturalHeight * S;
        const g = c.getContext('2d', { willReadFrequently: true });
        g.drawImage(imgEl, 0, 0, c.width, c.height);
        const d = g.getImageData(0, 0, c.width, c.height).data;
        let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
        for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
          if (d[(y * c.width + x) * 4 + 3] > 16) {
            if (x < x0) x0 = x; if (x > x1) x1 = x;
            if (y < y0) y0 = y; if (y > y1) y1 = y;
          }
        }
        if (x1 < 0) return resolve({ ink: 'EMPTY' });
        resolve({ vw: imgEl.naturalWidth, vh: imgEl.naturalHeight,
          ink: { x0: +(x0 / S).toFixed(2), y0: +(y0 / S).toFixed(2), x1: +(x1 / S).toFixed(2), y1: +(y1 / S).toFixed(2) },
          margins_vb: { top: +(y0 / S).toFixed(2), bottom: +(imgEl.naturalHeight - 1 - y1 / S).toFixed(2),
                        left: +(x0 / S).toFixed(2), right: +(imgEl.naturalWidth - 1 - x1 / S).toFixed(2) } });
      } catch (e) { resolve({ err: String(e) }); }
    })`, [img]);
    // L3 — rendered ink bbox from the slot element screenshot
    const buf = await slot.screenshot();
    const l3 = await page.evaluate(async (b64) => {
      const im = new Image(); im.src = 'data:image/png;base64,' + b64; await im.decode();
      const c = document.createElement('canvas'); c.width = im.naturalWidth; c.height = im.naturalHeight;
      const g = c.getContext('2d', { willReadFrequently: true });
      g.drawImage(im, 0, 0);
      const W = c.width, H = c.height;
      const ring = [];
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++)
        if (x < 2 || x >= W - 2 || y < 2 || y >= H - 2) ring.push(g.getImageData(x, y, 1, 1).data);
      const med = (ch) => { const a = ring.map(p => p[ch]).sort((m, n) => m - n); return a[a.length >> 1]; };
      const bg = [med(0), med(1), med(2)];
      let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const p = g.getImageData(x, y, 1, 1).data;
        const dev = Math.max(Math.abs(p[0] - bg[0]), Math.abs(p[1] - bg[1]), Math.abs(p[2] - bg[2]));
        if (dev > 25) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
      }
      if (x1 < 0) return { shotPx: `${W}x${H}`, ink: 'EMPTY' };
      const dpr = 2;
      return { shotPx: `${W}x${H}`, bg,
        inkDev: { x0, y0, x1, y1 },
        margins_css: { top: +((y0) / dpr).toFixed(2), bottom: +((H - 1 - y1) / dpr).toFixed(2),
                        left: +(x0 / dpr).toFixed(2), right: +((W - 1 - x1) / dpr).toFixed(2) } };
    }, buf.toString('base64'));
    out.slots.push({ theme, label, l1, l2, l3 });
  }
  await context.close();
}
await browser.close();
console.log(JSON.stringify(out, null, 1));
