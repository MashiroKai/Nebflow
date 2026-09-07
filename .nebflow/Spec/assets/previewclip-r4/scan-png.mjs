#!/usr/bin/env node
// scan-png.mjs — offline pixel forensics on archived R3 button screenshots.
// Loads each PNG into a chromium canvas, scans the 56×40 slot region
// (x∈[26,138) y∈[13,93) device px @2x for the q1 button: border1+pad12=13 CSS,
// vertical center of 41px content row) for ink pixels (deviation from the
// region's border-ring background > threshold), reports ink bbox + margins.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '/Users/dev/Claude code/Nebflow/node_modules/playwright/index.mjs';

const files = process.argv.slice(2);
if (!files.length) { console.error('usage: node scan-png.mjs <png>...'); process.exit(2); }

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 800, height: 600 } });

for (const f of files) {
  const b64 = fs.readFileSync(path.resolve(f)).toString('base64');
  const data = await page.evaluate(async (payload) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + payload;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.naturalWidth; c.height = img.naturalHeight;
    const g = c.getContext('2d', { willReadFrequently: true });
    g.drawImage(img, 0, 0);
    const W = c.width, H = c.height;
    // slot region (device px, @2x): x 26..138, y 13..93 — see header comment
    const RX0 = 26, RX1 = 138, RY0 = 13, RY1 = 93;
    // background reference = median-ish of the region border ring (outer 2px)
    const ring = [];
    for (let y = RY0; y < RY1; y++) for (let x = RX0; x < RX1; x++) {
      const onRing = (x < RX0 + 2 || x >= RX1 - 2 || y < RY0 + 2 || y >= RY1 - 2);
      if (onRing) ring.push(g.getImageData(x, y, 1, 1).data);
    }
    const med = (ch) => {
      const arr = ring.map(p => p[ch]).sort((a, b) => a - b);
      return arr[arr.length >> 1];
    };
    const bg = [med(0), med(1), med(2)];
    // ink scan with threshold 25 per-channel
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, inkCount = 0;
    for (let y = RY0; y < RY1; y++) for (let x = RX0; x < RX1; x++) {
      const p = g.getImageData(x, y, 1, 1).data;
      const dev = Math.max(Math.abs(p[0] - bg[0]), Math.abs(p[1] - bg[1]), Math.abs(p[2] - bg[2]));
      if (dev > 25) {
        inkCount++;
        if (x < x0) x0 = x; if (x > x1) x1 = x;
        if (y < y0) y0 = y; if (y > y1) y1 = y;
      }
    }
    const dev2 = (v) => v === 1e9 ? 'NONE' : v;
    const fmt = (v) => (v / 2).toFixed(1); // device→CSS px
    return {
      file: null, W, H, bg,
      ink: { x0: dev2(x0), y0: dev2(y0), x1: dev2(x1), y1: dev2(y1), inkCount },
      margins_css_px: (x1 < 0) ? null : {
        top: fmt(y0 - RY0), bottom: fmt(RY1 - 1 - y1),
        left: fmt(x0 - RX0), right: fmt(RX1 - 1 - x1),
      },
    };
  }, b64);
  data.file = path.basename(f);
  console.log(JSON.stringify(data));
}
await browser.close();
