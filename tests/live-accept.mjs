// live-accept.mjs — LIVE 实例验收：真实例 + 真 soffice 转换 + 真实 deck。
// 用法：NF_OFFICE_LIVE_BASE=http://127.0.0.1:8299 NF_OFFICE_LIVE_HOME=/tmp/nf-office-live \
//        DECK='/Users/kaiyu/local/ppt/ppt/博士开题/开题报告_开宇_4x3.pptx' \
//        node tests/live-accept.mjs
// （NEBFLOW_LIVE_* 同义兼容；沙箱执行面会剥离 NEBFLOW_LIVE_*，别名与
//   canvas-office-viewers.spec.mjs 同形。）
// 复活批对齐（2026-10-29）：Stage D 认证链后裸 localStorage token 不再被 WS
// 握手接受——会话建立改走 console-ticket 链（tests/helpers/session-rail.mjs，
// 与 composer-resync-live 等现役 LIVE 档同款）；localStorage token 仍设
// （API 面 Bearer 轨铸票用）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { establishSession } from './helpers/session-rail.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const BASE = process.env.NEFLOW_LIVE_BASE || process.env.NF_OFFICE_LIVE_BASE || 'http://127.0.0.1:8299';
const LIVE_HOME = process.env.NEFLOW_LIVE_HOME || process.env.NF_OFFICE_LIVE_HOME || '/tmp/nf-live-home';
const DECK = process.env.DECK || '/Users/kaiyu/local/ppt/ppt/博士开题/开题报告_开宇_4x3.pptx';
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/20261024_pptx-convert');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let token = 't';
try {
  const raw = readFileSync(join(LIVE_HOME, 'auth.json'), 'utf8').trim();
  let val = raw;
  try { const j = JSON.parse(raw); val = (typeof j === 'string' ? j : (j.token || j.gatewayToken || raw)); } catch { /* bare */ }
  if (val) token = val;
} catch { /* fallback t */ }
console.log(`[live] base=${BASE} home=${LIVE_HOME} deck=${DECK}`);

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1720, height: 1080 } });
await ctx.addInitScript((tok) => {
  localStorage.setItem('nebflow_token', tok); localStorage.setItem('neblink_token', tok);
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
}, token);
const page = await ctx.newPage();
page.setDefaultTimeout(300000);
// Stage D 会话链：console-ticket → GET /?ticket= 303 落 HttpOnly 会话 cookie
// （WS 握手与会话面全靠它；establishSession 内含 goto 落地）。
await establishSession(BASE, token, page);
await page.waitForSelector('#activity-bar', { timeout: 60000 });
await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
await sleep(600);
const errors = [];
const officeReqs = [];
page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
page.on('response', (r) => {
  if (r.url().includes('/api/nf-office')) {
    officeReqs.push({ status: r.status(), conv: r.headers()['x-nf-office-converter'] || '', cache: r.headers()['x-nf-office-cache'] || '' });
  }
});

// 第 1 次打开：真转换（miss）
await page.evaluate((abs) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + abs, itemType: '', content: '', absPath: abs, size: 0, title: abs.split('/').pop() },
  }));
}, DECK);
mkdirSync(SHOTS_DIR, { recursive: true });
await page.screenshot({ path: join(SHOTS_DIR, 'live-01-converting.png') });
const t0 = Date.now();
await page.waitForFunction(() => {
  const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
  return d && d.gateDecision && d.gateDecision !== 'pending';
}, null, { timeout: 240000 });
const t1 = Math.round((Date.now() - t0) / 1000);
const gate1 = await page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?._pptxDiag?.gateDecision);
const pdfPages = await page.evaluate(() => document.querySelectorAll('.canvas-tab-pane.active canvas.pdf-page').length);
console.log(`[live] first open: gate=${gate1} in ${t1}s, pdf-page canvases=${pdfPages}`);
await sleep(2500); // let more pages render for the shot
await page.screenshot({ path: join(SHOTS_DIR, 'live-02-pdf-first-open.png'), fullPage: false });
console.log(`[office-reqs so far] ${JSON.stringify(officeReqs)}`);

// 第 2 次打开（关 tab 再开同件）：缓存命中（hit）
await page.keyboard.press('Escape');
await page.evaluate(() => {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
});
await sleep(500);
await page.evaluate((abs) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + abs, itemType: '', content: '', absPath: abs, size: 0, title: abs.split('/').pop() },
  }));
}, DECK);
await page.waitForFunction(() => {
  const d = document.querySelector('.canvas-tab-pane.active')?._pptxDiag;
  return d && d.gateDecision && d.gateDecision !== 'pending';
}, null, { timeout: 120000 });
const t2 = Math.round((Date.now() - t0) / 1000);
const gate2 = await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane.active');
  const d = pane && pane._pptxDiag;
  return d ? `${d.gateDecision} cache=${d.convertCache}` : 'no-diag';
});
console.log(`[live] second open: ${gate2} (t+${t2}s)`);
await sleep(2000);
await page.screenshot({ path: join(SHOTS_DIR, 'live-03-pdf-cache-hit.png'), fullPage: false });
console.log(`[office-reqs total] ${JSON.stringify(officeReqs)}`);
console.log(`[errors] ${errors.length ? errors.join(' | ') : '(none)'}`);
await browser.close();
