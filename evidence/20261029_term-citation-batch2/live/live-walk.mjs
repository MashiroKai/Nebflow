// LIVE 走查：词条引用第二批（前端交互）点击链路。
//
// 隔离实例：`sbt "run --home /tmp/stand-live-8399 --port 8399 --no-browser"`
//   · home 里注册了真项目 `wt`（workspace = 本 worktree）——真 wiki graph 扫描
//     （docs/glossary.md 的 17 条词条）、真 readFile、真 /api/wiki/term 兜底面在场；
//   · 前端 = 本 worktree 静态树（本批改动，刷新即生效）。
// 入席：Bearer（隔离 home 自己的 auth.json）兑一次性 local-ticket → HttpOnly 会话 cookie。
// 被测交互全部为真鼠标事件；未测面（工作区根切换）走 app 内建 openExplorerAt。
//
// 用法：`node live-walk.mjs`
import { chromium } from '@playwright/test';
import { readFileSync } from 'node:fs';

const TOKEN = JSON.parse(readFileSync('/tmp/stand-live-8399/auth.json', 'utf8'));
const BASE = 'http://127.0.0.1:8399';
const WT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-926e20a3';
const SHOTS = 'evidence/20261029_term-citation-batch2/live';

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
const errors = [];
const termApiHits = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('request', (r) => { if (r.url().includes('/api/wiki/term')) termApiHits.push(r.url()); });

const mint = await page.request.post(BASE + '/api/auth/console-ticket', {
  headers: { Authorization: 'Bearer ' + TOKEN },
});
const { ticket } = await mint.json();
await page.goto(BASE + '/?ticket=' + encodeURIComponent(ticket), { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(5000);

// ── 1. 打开走查夹具（真 explorer → 真 readFile）────────────────────────────
await page.evaluate((wt) => import('/js/explorer.js').then((m) => m.openExplorerAt(wt)), WT);
await page.waitForTimeout(1500);
await page.locator('.explorer-folder', { hasText: /^\s*docs\s*$/ }).first().click();
await page.waitForTimeout(800);
await page.locator('.explorer-item', { hasText: 'ZZ-live-walk-term-citation' }).first().click();
await page.waitForSelector('.canvas-md-viewer a.nf-wikilink-term', { timeout: 20000 });
await page.waitForTimeout(2000);
console.log('L1-open', JSON.stringify(await page.evaluate(() => ({
  termLinks: document.querySelectorAll('.canvas-md-viewer a.nf-wikilink-term').length,
  resolved: document.querySelectorAll('.canvas-md-viewer a.nf-wikilink-resolved').length,
}))));

// 来源页滚出非零阅读位（返回保真读数用），两条词条引用留在视口中段
const srcScroll = await page.evaluate(() => {
  const sc = document.querySelector('.canvas-tab-pane.active .canvas-md-scroll');
  const a = document.querySelector('a.nf-wikilink-term');
  sc.scrollTop = Math.max(60, a.getBoundingClientRect().top - sc.getBoundingClientRect().top - 160);
  sc.dispatchEvent(new Event('scroll'));
  return sc.scrollTop;
});

// ── 2. hover 词条 → 真气泡（载荷 terms[] 直读腿：零 API 请求）──────────────
const link = page.locator('.canvas-md-viewer a.nf-wikilink-term', { hasText: '样张' }).first();
for (let i = 0; i < 20; i++) {          // 版面落定再 hover（位移会触发正当 mouseleave）
  const r1 = await link.boundingBox();
  await page.waitForTimeout(150);
  const r2 = await link.boundingBox();
  if (r1 && r2 && Math.abs(r1.y - r2.y) < 0.5 && Math.abs(r1.x - r2.x) < 0.5) break;
}
await link.hover();
await page.waitForSelector('.nf-term-tip', { state: 'visible', timeout: 8000 });
await page.waitForTimeout(500);
console.log('L2-hover', JSON.stringify(await page.evaluate(() => ({
  name: document.querySelector('.nf-term-tip .tt')?.textContent,
  def: (document.querySelector('.nf-term-tip .ts')?.textContent || '').slice(0, 40),
  doc: document.querySelector('.nf-term-tip .td')?.textContent,
}))), 'termApiHits=' + termApiHits.length);
await page.screenshot({ path: SHOTS + '/01-live-hover-bubble.png' });

// ── 3. 点击深链跳转 → 真 readFile 开词表 + 定义段高亮 ──────────────────────
const jumpLink = page.locator('.canvas-md-viewer a.nf-wikilink-term', { hasText: '段落锚' }).first();
await page.mouse.move(40, 40);          // 先收泡
await page.waitForTimeout(300);
await jumpLink.click();
// 高亮类与落点源行在动画存续期内一次性抓（nf-jump-flash 是短动画）
const jump = await page.waitForFunction(() => {
  const pane = document.querySelector('.canvas-tab-pane.active');
  const viewer = pane?.querySelector('.canvas-md-viewer');
  const flash = viewer?.querySelector('.nf-jump-flash');
  if (!flash) return false;
  const kids = [...viewer.children];
  return {
    tab: pane.dataset.tabId,
    flashText: (flash.textContent || '').trim().slice(0, 40),
    anchorLine: (pane._mdAnalysis?.blockIds || new Map()).get('paragraph-anchor'),
    flashLine: Number(kids[kids.indexOf(flash)]?.dataset.srcLine),
    renderedBlocks: kids.length,
    sourceBlocks: (pane._mdAnalysis?.blockStarts || []).length,
  };
}, null, { timeout: 20000 }).then((h) => h.jsonValue());
// 高亮是短动画：落定前先抓一张（02），落定后再抓一张（02b）
await page.screenshot({ path: SHOTS + '/02-live-jump-highlight.png' });
await page.waitForTimeout(1500);        // smooth scroll 落定
jump.scrollTop = await page.evaluate(() =>
  document.querySelector('.canvas-tab-pane.active .canvas-md-scroll')?.scrollTop);
console.log('L3-jump', JSON.stringify(jump));
await page.screenshot({ path: SHOTS + '/02b-live-jump-landed.png' });

// ── 4. 返回原文档钮（左上角）→ 单击回原页原位置 ────────────────────────────
const btn = page.locator('#canvas-refback-btn');
await btn.waitFor({ state: 'visible', timeout: 8000 });
console.log('L4-backbtn', JSON.stringify(await page.evaluate(() => {
  const b = document.getElementById('canvas-refback-btn').getBoundingClientRect();
  const p = document.getElementById('canvas-panel').getBoundingClientRect();
  return {
    text: document.getElementById('canvas-refback-btn').textContent.trim(),
    offsetTop: Math.round(b.top - p.top), offsetLeft: Math.round(b.left - p.left),
  };
})));
await page.screenshot({ path: SHOTS + '/03-live-back-button.png' });
await btn.click();
await page.waitForTimeout(1000);
console.log('L5-return', JSON.stringify(await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane.active');
  return {
    tab: (pane?.dataset.tabId || '').replace(/^file:.*\//, ''),
    scrollTop: pane?.querySelector('.canvas-md-scroll')?.scrollTop,
    ledgerPos: pane?._mdScrollPos,
    backBtn: !!document.getElementById('canvas-refback-btn'),
  };
})), 'srcScrollWas=' + srcScroll);
await page.screenshot({ path: SHOTS + '/04-live-returned.png' });

// ── 5. 兜底腿（词条不在载荷）：本 vault 载荷全覆盖 ⇒ 该腿由 spec §T2 的 mock 面钉住
console.log('L6-termApi', JSON.stringify({ hits: termApiHits.length, urls: termApiHits }));
console.log('ERRORS', JSON.stringify(errors));
await browser.close();
