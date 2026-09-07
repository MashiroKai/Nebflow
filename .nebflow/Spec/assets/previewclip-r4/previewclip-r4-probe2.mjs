#!/usr/bin/env node
// previewclip-r4-probe2.mjs — mechanism isolation probe (R4 diag step 2).
// The r4 probe found img box at dy=+4px inside the 40px slot on EVERY image
// slot. This probe narrows the mechanism: computed box-model dump, running/
// filling animations on the img→slot axis, and a synthetic bare control
// (same markup+styles, no app ancestors) to split browser quirk vs app CSS.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '/Users/dev/Claude code/Nebflow/node_modules/playwright/index.mjs';

const BASE = process.argv[2] || 'http://localhost:8097';
const HOME = '/tmp/qa-previewclip';
function readToken() {
  const raw = fs.readFileSync(path.join(HOME, 'auth.json'), 'utf-8').trim();
  try { const p = JSON.parse(raw); return typeof p === 'string' ? p : raw; } catch { return raw; }
}

const SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' +
  '<rect x="1" y="1" width="22" height="22" rx="3" fill="none" stroke="#d9534f" stroke-width="1.6"/>' +
  '</svg>';
const SRC = 'data:image/svg+xml,' + encodeURIComponent(SVG);

const browser = await chromium.launch();
const context = await browser.newContext({
  baseURL: BASE, colorScheme: 'light', viewport: { width: 1280, height: 900 },
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
await page.evaluate(async (src) => {
  const { showOptions } = await import('/js/chat.js');
  const qs = [{ id: 'q1', question: 'Q1', allowOther: false,
    options: [{ label: 'Icon (no w/h attrs)', preview: { type: 'image', src } }] }];
  const chat = document.getElementById('chat');
  const row = document.createElement('div'); row.className = 'row ai';
  const bubble = document.createElement('div'); bubble.className = 'bubble ai';
  row.appendChild(bubble); chat.appendChild(row);
  showOptions(bubble, qs, () => {}, 'Confirm', () => {});
  // synthetic control: same slot/img styles, zero app ancestry
  const wrap = document.createElement('div');
  wrap.style.cssText = 'position:fixed;left:20px;top:20px;z-index:99999';
  wrap.innerHTML = '<span class="option-preview"><img class="preview-img" src="' + src + '"></span>';
  document.body.appendChild(wrap);
}, SRC);
await page.waitForFunction(() => {
  const imgs = [...document.querySelectorAll('.option-preview .preview-img')];
  return imgs.length === 2 && imgs.every((im) => im.classList.contains('nf-loaded'));
}, null, { timeout: 8000 }).catch(() => {});
await page.waitForTimeout(600);

const report = await page.evaluate(() => {
  const dump = (slot, img, tag) => {
    const cs = getComputedStyle(slot), ci = getComputedStyle(img);
    const rs = slot.getBoundingClientRect(), ri = img.getBoundingClientRect();
    const anims = [];
    for (const a of slot.getAnimations({ subtree: true })) {
      const t = a.effect ? a.effect.getComputedTiming() : {};
      anims.push({ name: a.animationName || a.id || 'waapi', state: a.playState,
        fill: a.effect && a.effect.getTiming().fill, progress: t.progress, endTime: t.endTime });
    }
    return {
      tag, dy: +(ri.y - rs.y).toFixed(3),
      slot: { rect: `${rs.x.toFixed(1)},${rs.y.toFixed(1)} ${rs.width}x${rs.height}`,
        display: cs.display, alignItems: cs.alignItems, alignContent: cs.alignContent,
        paddingTop: cs.paddingTop, lineHeight: cs.lineHeight, flexDirection: cs.flexDirection,
        scrollH: slot.scrollHeight, clientH: slot.clientHeight },
      img: { rect: `${ri.x.toFixed(1)},${ri.y.toFixed(1)} ${ri.width}x${ri.height}`,
        offsetTop: img.offsetTop, offsetParentTag: img.offsetParent && (img.offsetParent.className || img.offsetParent.tagName),
        marginTop: ci.marginTop, transform: ci.transform, verticalAlign: ci.verticalAlign,
        display: ci.display, height: ci.height, boxSizing: ci.boxSizing },
      anims,
    };
  };
  const slots = [...document.querySelectorAll('.option-preview')];
  const appSlot = slots.find(s => s.closest('.option-box'));
  const synSlot = slots.find(s => !s.closest('.option-box'));
  const out = [dump(appSlot, appSlot.querySelector('img'), 'app')];
  if (synSlot) out.push(dump(synSlot, synSlot.querySelector('img'), 'synthetic'));
  // in-page mutations on the app slot to isolate the contributing declaration
  const img = appSlot.querySelector('img');
  const dyOf = () => +(img.getBoundingClientRect().y - appSlot.getBoundingClientRect().y).toFixed(3);
  const muts = {};
  const tryMut = (name, apply, revert) => {
    apply(); muts[name] = dyOf(); revert();
  };
  tryMut('img_height_auto', () => { img.style.height = 'auto'; }, () => { img.style.height = ''; });
  tryMut('img_width_auto', () => { img.style.width = 'auto'; }, () => { img.style.width = ''; });
  tryMut('slot_block', () => { appSlot.style.display = 'block'; }, () => { appSlot.style.display = ''; });
  tryMut('slot_align_center', () => { appSlot.style.alignItems = 'center'; }, () => { appSlot.style.alignItems = ''; });
  tryMut('slot_flex_start', () => { appSlot.style.alignItems = 'flex-start'; }, () => { appSlot.style.alignItems = ''; });
  tryMut('img_margin0', () => { img.style.margin = '0'; }, () => { img.style.margin = ''; });
  tryMut('img_lineheight0', () => { appSlot.style.lineHeight = '0'; }, () => { appSlot.style.lineHeight = ''; });
  muts.baseline = dyOf();
  out.push({ mutations_dy: muts });
  return out;
});
console.log(JSON.stringify(report, null, 1));
await browser.close();
