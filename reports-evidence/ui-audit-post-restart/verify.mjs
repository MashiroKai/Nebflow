// verify.mjs — 逐条证实/否证候选缺陷（只读）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const DATA = path.join(ROOT, '_data');
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const out = {};
const browser = await chromium.launch({ headless: true });
const dir = path.join(ROOT, '11-verify'); fs.mkdirSync(dir, { recursive: true });

// ── A. 桌面：待办 FAB 层叠真相 + 破图 + 插件摘要裁切 + 主题底色 ──
for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme, locale: 'zh-CN' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(8500);
  const r = {};
  r.skin = await page.evaluate(() => {
    const g = (s) => { const e = document.querySelector(s); if (!e) return null; const c = getComputedStyle(e); return { bg: c.backgroundColor, color: c.color, bgi: c.backgroundImage.slice(0, 60) }; };
    return { body: g('body'), bar: g('#activity-bar'), chat: g('#chat'), header: g('#header'), sidebar: g('#sidebar'), input: g('#input-bar') };
  });
  r.fab = await page.evaluate(() => {
    const fab = document.querySelector('.tfl-fab-add');
    if (!fab) return { found: false };
    const chain = [];
    let e = fab;
    for (let i = 0; e && i < 6; i++, e = e.parentElement) {
      const c = getComputedStyle(e);
      chain.push({ sel: `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 34)}`, opacity: c.opacity, visibility: c.visibility, display: c.display, pe: c.pointerEvents, z: c.zIndex, pos: c.position, overflow: c.overflow });
    }
    const b = fab.getBoundingClientRect();
    const hit = document.elementFromPoint(Math.round(b.x + b.width / 2), Math.round(b.y + b.height / 2));
    return { found: true, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], chain, hit: hit ? `${hit.tagName.toLowerCase()}#${hit.id || ''}.${(hit.className || '').toString().slice(0, 30)}` : null, isCovered: hit !== fab && !fab.contains(hit) };
  });
  // 悬停待办条再取一次（若 FAB 是 hover-reveal，这里应可点）
  const tl = await page.$('#task-list');
  if (tl) {
    await tl.hover().catch(() => {});
    await page.waitForTimeout(600);
    r.fabAfterHover = await page.evaluate(() => {
      const fab = document.querySelector('.tfl-fab-add');
      if (!fab) return null;
      const b = fab.getBoundingClientRect();
      const hit = document.elementFromPoint(Math.round(b.x + b.width / 2), Math.round(b.y + b.height / 2));
      return { hit: hit ? `${hit.tagName.toLowerCase()}#${hit.id || ''}.${(hit.className || '').toString().slice(0, 30)}` : null, clickable: hit === fab || fab.contains(hit), opacity: getComputedStyle(fab).opacity };
    });
    await page.screenshot({ path: path.join(dir, `${theme}-desktop__todo-fab-hovered.png`), clip: { x: 620, y: 60, width: 460, height: 220 } }).catch(() => {});
  }
  r.brokenImgs = await page.evaluate(() => [...document.querySelectorAll('img')]
    .filter((i) => i.complete && i.naturalWidth === 0)
    .map((i) => ({ src: (i.currentSrc || i.src || '').replace(location.origin, ''), cls: (i.className || '').toString().slice(0, 40), box: (() => { const b = i.getBoundingClientRect(); return [Math.round(b.width), Math.round(b.height)]; })() })).slice(0, 10));
  r.pluginClamp = await page.evaluate(() => {
    const s = document.querySelector('.plugins-item-summary');
    if (!s) return null;
    const c = getComputedStyle(s);
    return { clamp: c.webkitLineClamp, display: c.display, maxH: c.maxHeight, oh: c.overflow, h: Math.round(s.clientHeight), sh: Math.round(s.scrollHeight) };
  });
  out[theme] = r;
  await ctx.close();
}

// ── B. 断连横幅：更长的断网窗口（WS 心跳/退避需要时间）──
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'zh-CN' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(8000);
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  const seen = [];
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(2000);
    const s = await page.evaluate(() => {
      const b = document.getElementById('conn-banner');
      const r = b?.getBoundingClientRect();
      return b ? { cls: b.className, text: (b.innerText || '').trim().slice(0, 40), h: r ? Math.round(r.height) : 0 } : null;
    });
    seen.push(`${(i + 1) * 2}s ${s?.cls} ${s?.text} h=${s?.h}`);
    if (s && !/hidden/.test(s.cls)) { await page.screenshot({ path: path.join(dir, 'light-desktop__offline-banner-30s.png'), animations: 'disabled' }).catch(() => {}); break; }
  }
  out.offlineTimeline = seen;
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await ctx.close();
}

await browser.close();
fs.writeFileSync(path.join(DATA, 'verify.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1).slice(0, 5500));
