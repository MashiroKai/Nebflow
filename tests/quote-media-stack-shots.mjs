// quote-media-stack-shots.mjs — 引用牌多图叠卡批的前后读数与截图取证（standalone）。
//
// 用法：
//   EVID_DIR=reports-evidence/quote-media-stack__<ts> LEG=before CHATFACE_WEB_ROOT=<改前 web 树> \
//     node tests/quote-media-stack-shots.mjs
//   EVID_DIR=... LEG=after node tests/quote-media-stack-shots.mjs
//
// 落盘：<EVID_DIR>/<LEG>-<vp>.png（叠卡态）、<LEG>-<vp>-expanded.png（展开态，改后腿）、
//      <EVID_DIR>/readings.json（两档视口的块高/单张高/占屏比/张数/展开钮文案）。
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat } from './_chat-face-harness.mjs';

const EVID = process.env.EVID_DIR || 'reports-evidence/quote-media-stack';
const LEG = process.env.LEG || 'after';

function svgData(w, h, c1, c2) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">`
    + `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">`
    + `<stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs>`
    + `<rect width="100%" height="100%" fill="url(#g)"/></svg>`;
  return 'data:image/svg+xml;base64,' + Buffer.from(svg).toString('base64');
}
const RATIOS = [[1000, 800], [1000, 620], [1000, 1100], [1000, 500], [800, 1000], [1200, 900]];
const COLS = [['#221133', '#ee4466'], ['#123322', '#44ee88'], ['#112244', '#4488ee'],
  ['#332211', '#eeaa44'], ['#221133', '#aa44ee'], ['#113322', '#44eebb']];
const atts = (n) => Array.from({ length: n }, (_, i) => ({
  type: 'image', name: `q${i + 1}.svg`,
  preview: svgData(RATIOS[i][0], RATIOS[i][1], COLS[i][0], COLS[i][1]),
}));

const history = [
  {
    type: 'user', text: '嗯 对 这几张喂去做一下 @living-ascii 引用三张', timestamp: Date.now() - 60_000,
    quote: {
      refMessageId: 'gone-3', excerpt: '嗯 对 这几张喂去做一下 @living-ascii',
      senderRole: 'user', senderName: '我', ts: Date.now() - 300_000, attachments: atts(3),
    },
  },
  {
    type: 'user', text: '嗯 对 这几张喂去做一下 @living-ascii 引用六张', timestamp: Date.now() - 50_000,
    quote: {
      refMessageId: 'gone-6', excerpt: '嗯 对 这几张喂去做一下 @living-ascii', senderRole: 'user', senderName: '我',
      ts: Date.now() - 290_000, attachments: atts(6),
    },
  },
];

const probe = () => {
  const pick = (t) => [...document.querySelectorAll('#chat .row.user')]
    .find(r => (r.textContent || '').includes(t) && r.querySelector('.msg-quote-block'));
  const read = (t) => {
    const row = pick(t);
    if (!row) return null;
    const box = row.querySelector('.msg-quote-block');
    const b = box.getBoundingClientRect();
    const thumbs = [...box.querySelectorAll('.msg-quote-att-media')];
    const stack = box.querySelector('.pop-stack-area');
    const face = box.querySelector('.pop-stack-view');
    const pill = box.querySelector('.pill');
    const fb = face ? face.getBoundingClientRect() : null;
    return {
      blockH: +b.height.toFixed(2),
      blockW: +b.width.toFixed(2),
      viewportSharePct: +(b.height / window.innerHeight * 100).toFixed(1),
      thumbH: thumbs.length ? +thumbs[0].getBoundingClientRect().height.toFixed(2) : 0,
      thumbRows: new Set(thumbs.map(el => Math.round(el.getBoundingClientRect().top))).size,
      stackPresent: !!stack,
      faceW: fb ? +fb.width.toFixed(2) : 0,
      faceH: fb ? +fb.height.toFixed(2) : 0,
      pillText: pill ? pill.textContent.trim() : '',
    };
  };
  return {
    viewport: { w: innerWidth, h: innerHeight },
    mobileLayer: document.body.classList.contains('mobile-layer'),
    three: read('引用三张'),
    six: read('引用六张'),
  };
};

const browser = await chromium.launch();
const out = { leg: LEG, webRoot: process.env.CHATFACE_WEB_ROOT || '(repo)', shots: [] };
mkdirSync(EVID, { recursive: true });
for (const vp of [{ width: 1440, height: 900 }, { width: 375, height: 812 }]) {
  const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await bootChat(page, { history });
  await page.waitForTimeout(900);
  out.shots.push(await page.evaluate(probe));
  await page.screenshot({ path: join(EVID, `${LEG}-${vp.width}.png`), fullPage: false });
  const pill = await page.$('.msg-quote-block .pill');
  if (pill) {
    await pill.click();
    await page.waitForTimeout(800);
    await page.screenshot({ path: join(EVID, `${LEG}-${vp.width}-expanded.png`), fullPage: false });
  }
  await ctx.close();
}
await browser.close();
writeFileSync(join(EVID, `readings-${LEG}.json`), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
