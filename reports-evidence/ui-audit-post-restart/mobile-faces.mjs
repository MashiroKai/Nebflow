// mobile-faces.mjs — 手机面专项：底部导航各面、设置入口、待办入口、顶/底浮层遮挡
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

for (const theme of ['light', 'dark']) {
  const dir = path.join(ROOT, '10-mobile-faces');
  fs.mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme: theme, isMobile: true, hasTouch: true,
    deviceScaleFactor: 2, locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  const errs = [];
  page.on('response', (r) => { if (r.status() >= 400) errs.push(`${r.status()} ${r.url().replace(BASE, '/')}`); });
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(8500);
  const shot = async (l) => page.screenshot({ path: path.join(dir, `${theme}-mobile__${l}.png`), animations: 'disabled' }).catch(() => {});
  const r = { errs: [] };

  // 底部导航项 + 顶部遮挡物的身份
  r.chrome = await page.evaluate(() => {
    const els = [...document.querySelectorAll('body *')].filter((e) => {
      const b = e.getBoundingClientRect(); const s = getComputedStyle(e);
      return b.width > 20 && b.height > 20 && b.bottom > innerHeight - 120 && s.visibility !== 'hidden';
    });
    const seen = new Set();
    const nav = [];
    for (const e of els) {
      const b = e.getBoundingClientRect();
      const k = `${Math.round(b.x)},${Math.round(b.y)},${Math.round(b.width)},${Math.round(b.height)}`;
      if (seen.has(k)) continue; seen.add(k);
      nav.push({
        tag: e.tagName.toLowerCase(), id: e.id || '', cls: (e.className || '').toString().slice(0, 44),
        t: (e.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 18),
        box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
        bg: getComputedStyle(e).backgroundColor, z: getComputedStyle(e).zIndex,
      });
    }
    // 顶部红圆身份
    const top = document.elementFromPoint(innerWidth / 2, 70);
    const chain = [];
    let c = top;
    while (c && chain.length < 5) { chain.push(`${c.tagName.toLowerCase()}#${c.id || ''}.${(c.className || '').toString().slice(0, 30)}`); c = c.parentElement; }
    return { nav: nav.slice(-14), topCenterChain: chain };
  });
  await shot('00-base');
  r.bottomNavTexts = await page.evaluate(() => [...document.querySelectorAll('body *')]
    .filter((e) => e.children.length === 0 && ['消息', '联系人', '看板', '工作台', '待办', '设置'].includes((e.textContent || '').trim()))
    .map((e) => ({ t: e.textContent.trim(), cls: (e.parentElement?.className || '').toString().slice(0, 40) })));

  // 依次点底部导航（消息/联系人/看板/工作台）
  for (const [label, sel] of [['消息', 'messages'], ['联系人', 'contacts'], ['看板', 'kanban'], ['工作台', 'workbench']]) {
    const clicked = await page.evaluate(([label]) => {
      const cand = [...document.querySelectorAll('body *')].filter((e) => {
        const t = (e.textContent || '').trim();
        if (t !== label) return false;
        const b = e.getBoundingClientRect();
        return b.bottom > innerHeight - 120 && b.width > 20;
      });
      const el = cand[cand.length - 1];
      if (!el) return null;
      const btn = el.closest('button, [role=button], a') || el;
      btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      return (btn.className || btn.tagName).toString().slice(0, 40);
    }, [label]).catch(() => null);
    await page.waitForTimeout(1400);
    await shot(`nav-${sel}`);
    r['nav_' + sel] = clicked;
  }

  // 设置入口（手机层）：找 设置 / gear
  r.settingsEntry = await page.evaluate(() => {
    const cands = [...document.querySelectorAll('body *')].filter((e) => {
      const s = getComputedStyle(e); const b = e.getBoundingClientRect();
      return b.width > 8 && b.height > 8 && s.visibility !== 'hidden' && /设置/.test((e.getAttribute('title') || '') + (e.getAttribute('aria-label') || '')) && e.children.length < 3;
    }).map((e) => ({ tag: e.tagName.toLowerCase(), id: e.id || '', cls: (e.className || '').toString().slice(0, 40), t: (e.getAttribute('title') || e.getAttribute('aria-label') || '').slice(0, 20) }));
    return cands.slice(0, 6);
  });
  const opened = await page.evaluate(() => {
    const t = [...document.querySelectorAll('[title="设置"],[aria-label="设置"],#settings-btn')];
    const el = t.find((e) => { const b = e.getBoundingClientRect(); return b.width > 0 && getComputedStyle(e).display !== 'none'; });
    if (!el) return false;
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return true;
  });
  await page.waitForTimeout(1600);
  await shot('settings-attempt');
  r.settingsOpened = opened;
  r.settingsVisible = await page.evaluate(() => {
    const m = document.getElementById('settings-modal');
    if (!m) return null;
    const b = m.getBoundingClientRect();
    return { w: Math.round(b.width), h: Math.round(b.height), top: Math.round(b.top), overflows: document.getElementById('settings-content')?.scrollHeight > document.getElementById('settings-content')?.clientHeight };
  });
  // 设置面板滚动取证
  if (r.settingsVisible && r.settingsVisible.w > 50) {
    for (let i = 0; i < 4; i++) {
      await page.evaluate(([i]) => {
        const c = document.getElementById('settings-content');
        const h = c && c.scrollHeight > c.clientHeight ? c : document.getElementById('settings-modal');
        if (h) h.scrollTop = Math.round((h.scrollHeight - h.clientHeight) * (i / 3));
      }, [i]);
      await page.waitForTimeout(500);
      await shot(`settings-${i + 1}`);
    }
    await page.evaluate(() => document.getElementById('settings-modal-close')?.click());
    await page.waitForTimeout(700);
  }

  // 待办入口：找含"待办"的可点面
  r.todoEntry = await page.evaluate(() => [...document.querySelectorAll('body *')]
    .filter((e) => { const b = e.getBoundingClientRect(); return b.width > 8 && b.height > 8 && /待办/.test(e.textContent || '') && e.children.length < 4; })
    .map((e) => ({ id: e.id || '', cls: (e.className || '').toString().slice(0, 40), t: (e.textContent || '').trim().slice(0, 24), box: (() => { const b = e.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)]; })() })).slice(0, 8));
  await page.evaluate(() => {
    const isl = document.getElementById('todo-island') || document.getElementById('task-list');
    isl?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForTimeout(1400);
  await shot('todo-open');
  r.todoPage = await page.evaluate(() => {
    const p = document.getElementById('todo-page');
    if (!p) return null;
    const b = p.getBoundingClientRect();
    return { cls: p.className, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], text: (p.innerText || '').trim().slice(0, 120) };
  });
  await page.evaluate(() => document.getElementById('todo-close-btn')?.click());

  // 顶部/底部元素的真实遮挡与溢出（手机层专项）
  r.edge = await page.evaluate(() => {
    const vh = innerHeight, vw = innerWidth;
    const pick = (pt) => { const e = document.elementFromPoint(...pt); return e ? `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 24)}` : null; };
    const fixed = [...document.querySelectorAll('body *')].filter((e) => {
      const s = getComputedStyle(e); const b = e.getBoundingClientRect();
      return (s.position === 'fixed' || s.position === 'sticky') && b.width > 30 && b.height > 30 && s.visibility !== 'hidden';
    }).map((e) => {
      const b = e.getBoundingClientRect();
      return { sel: `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 30)}`, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], z: getComputedStyle(e).zIndex, bg: getComputedStyle(e).backgroundColor };
    });
    return { vw, vh, probeTop: pick([vw / 2, 40]), probeBottom: pick([vw / 2, vh - 40]), fixed: fixed.slice(0, 14) };
  });
  r.errs = [...new Set(errs)].slice(0, 20);
  out[theme] = r;
  await ctx.close();
}

await browser.close();
fs.writeFileSync(path.join(DATA, 'mobile-faces.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1).slice(0, 6000));
