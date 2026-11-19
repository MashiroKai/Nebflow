#!/usr/bin/env node
// ask-button-unify-grids.mjs — 「改前 / 改后 × 主会话 / Ask」2×2 并排对照板。
// 素材 = shots/{before,after}/<screen>/{main,ask}-{rest,busy}.png（shots.mjs 产出）。
// Run: OUT=<证据目录> node tests/ask-button-unify-grids.mjs
import { chromium } from 'playwright';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(process.env.OUT || join(HERE, '..', 'evidence', 'ask-button-unify'));
mkdirSync(join(OUT, 'grid'), { recursive: true });
const BOX = { w: 176, h: 84 };

const mk = (screen, phase, state, side) => `../shots/${phase}/${screen}/${side}-${state}.png`;

function html(title, state, mainLabel, askLabel) {
  const rows = ['before', 'after'].map((phase) => `<tr>
    <td class="rowlab">${phase === 'before' ? '改前 (523a21209)' : '改后 (本批)'}</td>
    ${['mobile', 'desktop'].map((screen) => `
      <td class="cell"><img src="${mk(screen, phase, state, 'main')}">
        <img src="${mk(screen, phase, state, 'ask')}"></td>`).join('')}
  </tr>`).join('');
  return `<!doctype html><meta charset="utf-8"><style>
  body{margin:0;background:#0f1113;color:#e8ebf0;font:600 13px -apple-system,'PingFang SC',sans-serif}
  h1{font:600 15px -apple-system,'PingFang SC',sans-serif;margin:14px 16px 10px}
  table{border-collapse:collapse}
  th{padding:6px 10px;color:#9aa4b2;font:600 12px -apple-system,'PingFang SC',sans-serif}
  td{padding:8px 10px;vertical-align:middle}
  .rowlab{color:#9aa4b2;font:600 12px -apple-system,'PingFang SC',sans-serif;white-space:nowrap}
  .cell{display:flex;gap:10px}
  .pair{position:relative}
  img{display:block;width:${BOX.w * 2}px;border:1px solid #2c3138;border-radius:8px;background:#0b0d0f}
  .cap{color:#7d8794;font:600 11px -apple-system,'PingFang SC',sans-serif;padding:2px 0 0}
  </style><h1>${title}</h1>
  <table>
    <tr><th></th><th>手机 375×812</th><th>桌面 1600×1000</th></tr>
    ${rows}
  </table>
  <p style="color:#7d8794;font:600 11px -apple-system,'PingFang SC',sans-serif;margin:6px 16px 16px">
    每格左 = ${mainLabel}，右 = ${askLabel}（各为同一窗裁切，逐像素同尺度并排）</p>`;
}

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1100, height: 700 }, deviceScaleFactor: 1 });
const page = await ctx.newPage();
for (const [state, title, ml, al] of [
  ['rest', 'rest 态：发送圆钮（主会话 vs Ask）', '主会话 #send-btn', 'Canvas Ask .ask-send'],
  ['busy', 'busy 态：中断圆钮（主会话 vs Ask）', '主会话 #stop-btn', 'Canvas Ask .ask-stop'],
]) {
  const f = join(OUT, 'grid', `_${state}.html`);
  writeFileSync(f, html(title, state, ml, al));
  await page.goto('file://' + f);
  await page.waitForFunction(() => [...document.images].every((i) => i.complete), null, { timeout: 10000 });
  await (await page.$('table')).screenshot({ path: join(OUT, 'grid', `before-after-${state}.png`) });
  console.log('grid ->', join(OUT, 'grid', `before-after-${state}.png`));
}
await ctx.close(); await browser.close();
