// final-probe.mjs — 补齐三项取证：编辑态入口、断连横幅、已定位的遮挡/触点缺陷特写
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

// ── 桌面 ──
{
  const dir = path.join(ROOT, '01-chat'); fs.mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'zh-CN' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(8500);
  const r = {};

  // 1) 消息行的悬停动作面 —— 枚举全部按钮（找编辑入口）
  const rows = await page.$$('.row.user');
  if (rows.length) {
    const last = rows[rows.length - 1];
    await last.hover();
    await page.waitForTimeout(800);
    r.rowButtons = await page.evaluate(() => {
      const row = [...document.querySelectorAll('.row.user')].pop();
      if (!row) return null;
      const b = row.getBoundingClientRect();
      const kids = [...row.querySelectorAll('button, [role=button], [data-act]')].map((e) => {
        const rb = e.getBoundingClientRect();
        return { cls: (e.className || '').toString().slice(0, 40), t: (e.getAttribute('title') || e.innerText || '').trim().slice(0, 12), op: getComputedStyle(e).opacity, box: [Math.round(rb.width), Math.round(rb.height)] };
      });
      return { box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], kids };
    });
    await page.screenshot({ path: path.join(dir, 'light-desktop__5-row-actions.png'), clip: { x: 300, y: Math.max(0, (r.rowButtons?.box?.[1] || 400) - 40), width: 700, height: 160 } }).catch(() => {});
    // 双击气泡进编辑态（常见交互）
    const bub = await page.$('.row.user .bubble.user, .row.user .bubble');
    if (bub) {
      await bub.dblclick().catch(() => {});
      await page.waitForTimeout(900);
      r.afterDblclick = await page.evaluate(() => {
        const ta = [...document.querySelectorAll('textarea, [contenteditable=true]')].filter((e) => {
          const b = e.getBoundingClientRect(); return b.width > 100 && b.height > 20 && !e.closest('#input-area');
        }).map((e) => ({ tag: e.tagName.toLowerCase(), cls: (e.className || '').toString().slice(0, 40), val: (e.value || e.innerText || '').slice(0, 40) }));
        return ta;
      });
      await page.screenshot({ path: path.join(dir, 'light-desktop__6-edit-state.png'), animations: 'disabled' });
    }
  }

  // 2) 待办 FAB 与消息行的层叠关系（真实遮挡取证）
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
  r.fabOverlap = await page.evaluate(() => {
    const fab = document.querySelector('.tfl-fab-add');
    if (!fab) return { found: false };
    const b = fab.getBoundingClientRect();
    const cx = Math.round(b.x + b.width / 2), cy = Math.round(b.y + b.height / 2);
    const hit = document.elementFromPoint(cx, cy);
    return {
      found: true, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
      z: getComputedStyle(fab).zIndex, pos: getComputedStyle(fab).position, opacity: getComputedStyle(fab).opacity,
      hitAtCenter: hit ? `${hit.tagName.toLowerCase()}#${hit.id || ''}.${(hit.className || '').toString().slice(0, 40)}` : null,
      hitsFab: hit ? (hit === fab || fab.contains(hit)) : false,
    };
  });
  if (r.fabOverlap?.found) {
    const [x, y, w, h] = r.fabOverlap.box;
    await page.screenshot({ path: path.join(dir, 'light-desktop__7-todo-fab-overlap.png'), clip: { x: Math.max(0, x - 160), y: Math.max(0, y - 60), width: 380, height: h + 120 } }).catch(() => {});
  }

  // 3) 断连横幅：CDP 断网 + 长等待（真实重连态，不伪造 DOM）
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  const states = [];
  for (let i = 0; i < 8; i++) {
    await page.waitForTimeout(1500);
    const s = await page.evaluate(() => {
      const b = document.getElementById('conn-banner');
      const r = b ? b.getBoundingClientRect() : null;
      return b ? { cls: b.className, text: (b.innerText || '').trim().slice(0, 40), box: r ? [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] : null, op: getComputedStyle(b).opacity } : null;
    });
    states.push(s);
    if (s && !/hidden/.test(s.cls) && s.box && s.box[3] > 4) break;
  }
  r.offlineStates = states;
  const dir7 = path.join(ROOT, '07-notify'); fs.mkdirSync(dir7, { recursive: true });
  await page.screenshot({ path: path.join(dir7, 'light-desktop__1-offline-banner-real.png'), animations: 'disabled' }).catch(() => {});
  r.bannerShot = path.join(dir7, 'light-desktop__1-offline-banner-real.png');
  // 恢复
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await page.waitForTimeout(5000);
  r.afterRecover = await page.evaluate(() => ({ cls: document.getElementById('conn-banner')?.className, text: (document.getElementById('conn-banner')?.innerText || '').trim() }));
  out.desktop = r;
  await ctx.close();
}

// ── 手机：消息时间戳 vs 浮动 chrome 按钮（遮挡特写）+ 待办入口路径 ──
{
  const dir = path.join(ROOT, '10-mobile-faces'); fs.mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, colorScheme: 'light', deviceScaleFactor: 2, locale: 'zh-CN' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(8500);
  const r = {};
  r.overlap = await page.evaluate(() => {
    const fab = document.querySelector('.mh-chrome-btn.g2-fab') || document.querySelector('.mh-chrome-btn');
    const stamp = document.querySelector('.duration-badge-time.time-toggle');
    const rect = (e) => { const b = e.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)]; };
    return {
      fab: fab ? { sel: (fab.className || '').toString().slice(0, 50), box: rect(fab), z: getComputedStyle(fab).zIndex, bg: getComputedStyle(fab).backgroundColor } : null,
      stamp: stamp ? { box: rect(stamp), t: (stamp.textContent || '').trim().slice(0, 10) } : null,
      intersects: (() => {
        if (!fab || !stamp) return null;
        const a = fab.getBoundingClientRect(), b = stamp.getBoundingClientRect();
        const x = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        const y = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        return { w: Math.round(x), h: Math.round(y), area: Math.round(x * y), stampArea: Math.round(b.width * b.height) };
      })(),
    };
  });
  await page.screenshot({ path: path.join(dir, 'light-mobile__overlap-clip.png'), clip: { x: 0, y: 0, width: 375, height: 200 } }).catch(() => {});
  // 工作台 → 待办 入口
  await page.evaluate(() => { [...document.querySelectorAll('.dock-key')].pop()?.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
  await page.waitForTimeout(1600);
  await page.screenshot({ path: path.join(dir, 'light-mobile__workbench.png'), animations: 'disabled' }).catch(() => {});
  r.hubRows = await page.evaluate(() => [...document.querySelectorAll('.mf-hub-row, .mf-hub button, .mobile-page button')]
    .map((e) => ({ sel: (e.className || '').toString().slice(0, 30), t: (e.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 18) })).slice(0, 14));
  const todoClicked = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.mf-hub-row, .mf-hub button, .mobile-page button')].find((e) => /待办/.test(e.innerText || ''));
    if (!el) return null;
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return (el.innerText || '').trim().slice(0, 12);
  });
  await page.waitForTimeout(1600);
  await page.screenshot({ path: path.join(dir, 'light-mobile__todo-from-hub.png'), animations: 'disabled' }).catch(() => {});
  r.todoClicked = todoClicked;
  r.todoPage = await page.evaluate(() => {
    const p = document.getElementById('todo-page');
    if (!p) return null;
    const b = p.getBoundingClientRect();
    return { cls: p.className, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], text: (p.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 100) };
  });
  // 设置（手机层，从工作台进）
  await page.evaluate(() => { [...document.querySelectorAll('.dock-key')].pop()?.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
  await page.waitForTimeout(1200);
  const setClicked = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.mf-hub-row, .mf-hub button, .mobile-page button')].find((e) => /设置/.test(e.innerText || ''));
    if (!el) return null;
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return (el.innerText || '').trim().slice(0, 12);
  });
  await page.waitForTimeout(1800);
  r.setClicked = setClicked;
  for (let i = 0; i < 4; i++) {
    await page.evaluate(([i]) => {
      const c = document.getElementById('settings-content');
      const h = c && c.scrollHeight > c.clientHeight ? c : document.getElementById('settings-modal');
      if (h) h.scrollTop = Math.round((h.scrollHeight - h.clientHeight) * (i / 3));
    }, [i]);
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(dir, `light-mobile__settings-${i + 1}.png`), animations: 'disabled' }).catch(() => {});
  }
  r.settingsBox = await page.evaluate(() => {
    const m = document.getElementById('settings-modal');
    if (!m) return null;
    const b = m.getBoundingClientRect();
    const c = document.getElementById('settings-content');
    return { box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], sh: c?.scrollHeight, ch: c?.clientHeight };
  });
  out.mobile = r;
  await ctx.close();
}

await browser.close();
fs.writeFileSync(path.join(DATA, 'final-probe.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1).slice(0, 5000));
