// round2.mjs — 第二轮四条（气泡回到白色系 / 设置留白 / 提示位去重 / 工作台 hero）
// 用法：
//   node round2.mjs before            （ROUTE=0，主检出当前线上态）
//   ROUTE=1 node round2.mjs after     （被改文件就地替换为本工作树版本）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const LABEL = process.argv[2] || 'after';
const DATA = path.join(ROOT, '_data');
const WT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/src/main/resources/web';
const ROUTE = process.env.ROUTE === '1';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const OVR = [
  ['**/css/base.css*', `${WT}/css/base.css`, 'text/css'],
  ['**/css/mobileLayer.css*', `${WT}/css/mobileLayer.css`, 'text/css'],
  ['**/css/settingsRows.css*', `${WT}/css/settingsRows.css`, 'text/css'],
  ['**/css/stand-tokens.css*', `${WT}/css/stand-tokens.css`, 'text/css'],
  ['**/js/scrollPill.js*', `${WT}/js/scrollPill.js`, 'application/javascript'],
  ['**/navController.js*', `${WT}/js/mobile/navController.js`, 'application/javascript'],
  ['**/js/sidebar.js*', `${WT}/js/sidebar.js`, 'application/javascript'],
];

const MEASURE_BUBBLE = () => {
  const pick = (sel) => {
    const e = document.querySelector(sel);
    if (!e) return null;
    const cs = getComputedStyle(e);
    return { sel, bg: cs.backgroundColor, color: cs.color, border: cs.borderTopColor, bw: cs.borderTopWidth };
  };
  const ours = pick('.bubble.user') || pick('.bubble.ai');
  const theirs = pick('.bubble.ai, .bubble.peer');
  const cs = ours && document.querySelector('.bubble.user, .bubble.ai');
  return { ours, theirs, sameNode: ours && theirs && ours.sel === theirs.sel, token: cs ? getComputedStyle(cs).getPropertyValue('background-color') : null };
};

const MEASURE_SETTINGS = () => {
  const g = (sel, props) => {
    const e = document.querySelector(sel);
    if (!e) return null;
    const cs = getComputedStyle(e);
    const b = e.getBoundingClientRect();
    const out = { sel, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)] };
    for (const p of props) out[p] = cs[p];
    return out;
  };
  const content = document.getElementById('settings-content');
  const cards = content ? [...content.querySelectorAll('.set-card')] : [];
  const rects = cards.map((c) => { const b = c.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width)]; });
  const rowRects = [...content.querySelectorAll('.set-row, .set-nav')].slice(0, 4).map((r) => {
    const b = r.getBoundingClientRect(); return [Math.round(b.height)];
  });
  const dock = document.getElementById('mobile-dock');
  const last = content ? [...content.querySelectorAll('.set-card')].pop() : null;
  let dockOverlap = null;
  if (dock && last) {
    const d = dock.getBoundingClientRect(); const l = last.getBoundingClientRect();
    dockOverlap = Math.max(0, Math.min(d.top + d.height, l.top + l.height) - Math.max(d.top, l.top)) > 0;
  }
  return {
    content: g('#settings-content', ['paddingTop', 'paddingLeft', 'paddingRight', 'paddingBottom', 'gap']),
    card: g('.set-card', ['paddingTop', 'paddingBottom', 'paddingLeft', 'marginLeft', 'borderRadius']),
    row: g('.set-row, .set-nav', ['paddingTop', 'paddingBottom', 'paddingLeft', 'minHeight']),
    cardRects: rects.slice(0, 3),
    cardCount: cards.length,
    rowHeights: rowRects,
    identityCardPresent: !!document.getElementById('settings-identity-card'),
    dedupHintPresent: !!document.querySelector('[data-acct-dedup-hint]'),
    dockOverlapLastCard: dockOverlap,
  };
};

const MEASURE_HERO = () => {
  const e = document.querySelector('[data-hero="account"]');
  const hub = document.querySelector('.mf-hub');
  if (!e) return { hero: null };
  const b = e.getBoundingClientRect();
  const cs = getComputedStyle(e);
  const av = e.querySelector('.mf-me-avatar-frame');
  const ab = av ? av.getBoundingClientRect() : null;
  const rows = [...document.querySelectorAll('.mf-hub-row')].map((r) => (r.dataset.hubPage || ''));
  return {
    hero: { box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], radius: cs.borderRadius, border: cs.borderTopWidth, bg: cs.backgroundColor, shadow: cs.boxShadow.slice(0, 40) },
    avatar: ab ? [Math.round(ab.width), Math.round(ab.height)] : null,
    name: (e.querySelector('.mf-me-name') || {}).textContent || null,
    email: (e.querySelector('.mf-me-sub') || {}).textContent || null,
    settingsBtn: !!e.querySelector('[data-hero-settings]'),
    hubTop: hub ? Math.round(hub.getBoundingClientRect().top) : null,
    hubRows: rows,
  };
};

// 提示位状态机：五态逐一验证「同宿主只有一枚可见」
const PILL_STATES = async () => {
  const vis = () => [...document.querySelectorAll('.scroll-new-pill')]
    .filter((e) => !e.classList.contains('hidden') && getComputedStyle(e).display !== 'none')
    .map((e) => ({ txt: (e.textContent || '').trim(), box: (() => { const b = e.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)]; })() }));
  const chat = document.getElementById('chat');
  const out = {};
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const appendRow = () => {
    const src = [...chat.querySelectorAll('.row')].pop();
    const c = src.cloneNode(true); c.dataset.probe = '1'; c.style.height = '60px';
    chat.append(c); return c;
  };
  const scrollTop = () => { chat.scrollTop = 0; chat.dispatchEvent(new Event('scroll')); };
  const scrollBottom = () => { chat.scrollTop = chat.scrollHeight; chat.dispatchEvent(new Event('scroll')); };

  out['0-已在底部'] = { before: vis() };
  scrollTop(); await wait(500);
  out['1-无新消息但滚离底部'] = { visible: vis(), expect: '只 1 枚 回到底部' };
  appendRow(); await wait(500);
  out['2-新消息到达且不在底部'] = { visible: vis(), expect: '只 1 枚 ↓1 条新消息' };
  appendRow(); await wait(500);
  out['3-连续第2条新消息'] = { visible: vis(), expect: '只 1 枚 ↓2 条新消息（计数更新）' };
  const pill = document.querySelector('.scroll-new-pill:not(.hidden)');
  if (pill) pill.click();
  await wait(1200);
  out['4-点击后归位'] = { visible: vis(), scrollTop: Math.round(chat.scrollTop), expect: '0 枚（已回底）' };
  scrollBottom(); await wait(400);
  out['5-回到已底部'] = { visible: vis(), expect: '0 枚' };
  return out;
};

const out = { label: LABEL, route: ROUTE, runs: {} };
const browser = await chromium.launch({ headless: true });

for (const theme of ['light', 'dark']) {
  for (const [size, vp, mobile] of [['mobile', { width: 375, height: 812 }, true], ['desktop', { width: 1440, height: 900 }, false]]) {
    const tag = `${theme}-${size}`;
    const ctx = await browser.newContext({
      viewport: vp, isMobile: mobile, hasTouch: mobile, colorScheme: theme,
      deviceScaleFactor: mobile ? 2 : 1, locale: 'zh-CN',
    });
    if (ROUTE) for (const [glob, file, ct] of OVR) {
      await ctx.route(glob, async (route) => {
        try { await route.fulfill({ status: 200, contentType: `${ct}; charset=utf-8`, body: fs.readFileSync(file) }); }
        catch { await route.fallback(); }
      });
    }
    const page = await ctx.newPage();
    const run = {};
    await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(9000);
    // 接管弹窗（并发会话抢控制权）
    for (let i = 0; i < 2; i++) {
      const on = await page.evaluate(() => {
        const o = document.getElementById('modal-overlay');
        if (!o || !o.classList.contains('on')) return false;
        (document.getElementById('takeover-retake') || { click() {} }).click?.();
        return true;
      }).catch(() => false);
      await page.waitForTimeout(600);
      if (!on) break;
    }
    const shot = async (face, step) => {
      // 并发会话会抢控制台 → 每次拍摄前先点「重新接管」，避免截图里出现接管弹窗。
      for (let i = 0; i < 2; i++) {
        const on = await page.evaluate(() => {
          const o = document.getElementById('modal-overlay');
          if (!o || !o.classList.contains('on') || getComputedStyle(o).display === 'none') return false;
          (document.getElementById('takeover-retake') || { click() {} }).click?.();
          return true;
        }).catch(() => false);
        await page.waitForTimeout(500);
        if (!on) break;
      }
      const dir = path.join(ROOT, '20-fixes', face);
      fs.mkdirSync(dir, { recursive: true });
      const f = path.join(dir, `${tag}__${step}-${LABEL}.png`);
      await page.screenshot({ path: f, animations: 'disabled' }).catch(() => {});
      return f;
    };

    // ① 主会话气泡（我方 / 对方同屏）
    await shot('06-bubble-white', 'chat');
    run.bubble = await page.evaluate(MEASURE_BUBBLE);

    // ③ 提示位状态机（仅手机；需在消息根页）
    if (mobile) {
      run.pill = await page.evaluate(PILL_STATES);
      await page.waitForTimeout(400);
    }

    // ④ 工作台 hero（手机 = 最后一个 dock 键；桌面 = 无 hero，跳过）
    if (mobile) {
      await page.evaluate(() => [...document.querySelectorAll('.dock-key')].pop()?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
      await page.waitForTimeout(1600);
      await page.evaluate(() => {
        const p = document.querySelector('#mobile-pages .mobile-page.live .mobile-page-body') ||
                  document.querySelector('#mobile-pages .mobile-page.live');
        if (p) p.scrollTop = 0;
      });
      await page.waitForTimeout(400);
      await shot('08-workbench-hero', 'hub');
      run.hero = await page.evaluate(MEASURE_HERO);
      // 从 hero 进设置（同时验证「设置唯一入口」可用）
      await page.evaluate(() => document.querySelector('[data-hero-settings]')?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
      await page.waitForTimeout(1600);
    } else {
      await page.evaluate(() => document.getElementById('settings-btn')?.click());
      await page.waitForTimeout(1600);
    }

    // ② 设置页留白
    await shot('07-settings-space', 'top');
    run.settingsTop = await page.evaluate(MEASURE_SETTINGS);
    await page.evaluate(() => {
      const c = document.getElementById('settings-content');
      const m = document.getElementById('settings-modal');
      const pb = document.querySelector('#mobile-pages .mobile-page.live .mobile-page-body');
      [c, m, pb].forEach((h) => { if (h && h.scrollHeight > h.clientHeight) h.scrollTop = h.scrollHeight; });
    });
    await page.waitForTimeout(800);
    await shot('07-settings-space', 'bottom');
    run.settingsBottom = await page.evaluate(MEASURE_SETTINGS);

    out.runs[tag] = run;
    await ctx.close();
  }
}
await browser.close();
fs.mkdirSync(DATA, { recursive: true });
fs.writeFileSync(path.join(DATA, `round2-${LABEL}.json`), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1).slice(0, 4000));
