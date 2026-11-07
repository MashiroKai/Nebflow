// compare.mjs — 修复后逐面实拍 + 客观测量（顶/底让位、圆角、气泡底色）
// 用法：node compare.mjs after      → 写 20-fixes/<面>/<主题>-<尺寸>__after.png
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const LABEL = process.argv[2] || 'after';
const DATA = path.join(ROOT, '_data');
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const out = { label: LABEL, runs: {} };
const browser = await chromium.launch({ headless: true });

// ── 取证方法（重要）：运行中的引擎服务的是**主检出**的 web 树，改动的三个文件
// 在工作树里。为了不重启引擎又能拿到真实渲染，用 Playwright route 把这三条请求
// 就地换成工作树文件（引擎、数据、句子、布局全真，只有被改的字节来自本批）。
// ROUTE=1 → 用工作树文件（after）；ROUTE=0 → 用引擎现served 的主树文件（before）。
const ROUTE = process.env.ROUTE === '1';
const WT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/src/main/resources/web';
const OVR = [
  ['**/css/base.css*', `${WT}/css/base.css`, 'text/css'],
  ['**/css/mobileLayer.css*', `${WT}/css/mobileLayer.css`, 'text/css'],
  ['**/js/askDock.js*', `${WT}/js/askDock.js`, 'application/javascript'],
];
async function installOverride(ctx) {
  if (!ROUTE) return;
  for (const [glob, file, ct] of OVR) {
    await ctx.route(glob, async (route) => {
      try {
        const body = fs.readFileSync(file);
        await route.fulfill({ status: 200, contentType: `${ct}; charset=utf-8`, body });
      } catch (e) { await route.fallback(); }
    });
  }
}

const killTakeover = async (p) => {
  for (let i = 0; i < 2; i++) {
    const on = await p.evaluate(() => {
      const o = document.getElementById('modal-overlay');
      if (!o || !o.classList.contains('on') || getComputedStyle(o).display === 'none') return false;
      (document.getElementById('takeover-retake') || { click() {} }).click?.();
      return true;
    }).catch(() => false);
    await p.waitForTimeout(600);
    if (!on) break;
  }
};

// 客观测量：顶/底让位、气泡底色、直角交互件
const MEASURE = () => {
  const rect = (e) => { const b = e.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)]; };
  const intersect = (a, b) => Math.max(0, Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0])) *
                              Math.max(0, Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]));
  const res = {};
  // 顶部悬浮钮 vs 页体首行
  const fab = document.querySelector('.mh-chrome-btn[data-chrome="search"], #mobile-fab-layer .g2-fab, .mh-chrome-btn');
  const body = document.querySelector('#mobile-pages .mobile-page.live .mobile-page-body') ||
               document.querySelector('#mobile-pages .mobile-page.live') || document.querySelector('#chat');
  if (fab && body) {
    const fr = rect(fab);
    const first = [...body.children].find((c) => { const b = c.getBoundingClientRect(); return b.height > 8 && b.width > 8; });
    res.top = { fab: fr, firstChild: first ? rect(first) : null, firstChildCls: first ? (first.className || '').toString().slice(0, 40) : null };
    res.top.overlapArea = first ? intersect(fr, rect(first)) : null;
  }
  // dock vs 页体末行
  const dock = document.getElementById('mobile-dock');
  if (dock) {
    const dr = rect(dock);
    const b = body;
    res.dock = { dock: dr };
    if (b) {
      const kids = [...b.children].filter((c) => { const x = c.getBoundingClientRect(); return x.height > 8; });
      const last = kids[kids.length - 1];
      res.dock.lastChild = last ? rect(last) : null;
      res.dock.lastChildCls = last ? (last.className || '').toString().slice(0, 40) : null;
      res.dock.overlapArea = last ? intersect(dr, rect(last)) : null;
      const cs = getComputedStyle(b);
      res.dock.bodyPadBottom = cs.paddingBottom;
      res.dock.bodyPadTop = cs.paddingTop;
    }
  }
  // 我方气泡底色（单点 token 是否生效）
  const bub = document.querySelector('.bubble.user, .fm-msg-bubble.ours, .fm-msg-bubble.user');
  if (bub) { const cs = getComputedStyle(bub); res.bubble = { sel: (bub.className || '').toString().slice(0, 40), bg: cs.backgroundColor, color: cs.color, radius: cs.borderRadius }; }
  // 直角交互件（可见、非 svg/图片）
  const sq = [];
  for (const e of document.querySelectorAll('button, select, input:not([type=hidden]), .set-card, .provider-card, .fm-row, [class*="card"]')) {
    const b = e.getBoundingClientRect();
    const cs = getComputedStyle(e);
    if (b.width < 24 || b.height < 18 || cs.visibility === 'hidden' || cs.display === 'none') continue;
    if (cs.borderTopLeftRadius === '0px') {
      const sel = `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().split(/\s+/).slice(0, 2).join('.')}`;
      sq.push({ sel: sel.slice(0, 60), box: [Math.round(b.width), Math.round(b.height)] });
    }
  }
  const agg = {};
  for (const s of sq) agg[s.sel] = (agg[s.sel] || 0) + 1;
  res.squareTop = Object.entries(agg).sort((a, b) => b[1] - a[1]).slice(0, 12).map(([sel, n]) => ({ sel, n }));
  res.squareTotal = sq.length;
  return res;
};

for (const theme of ['light', 'dark']) {
  for (const [size, vp, mobile] of [['mobile', { width: 375, height: 812 }, true], ['desktop', { width: 1440, height: 900 }, false]]) {
    const tag = `${theme}-${size}`;
    const ctx = await browser.newContext({
      viewport: vp, isMobile: mobile, hasTouch: mobile, colorScheme: theme,
      deviceScaleFactor: mobile ? 2 : 1, locale: 'zh-CN',
    });
    await installOverride(ctx);
    const page = await ctx.newPage();
    await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(9000);
    await killTakeover(page);
    const run = {};
    const shot = async (face, step) => {
      const dir = path.join(ROOT, '20-fixes', face);
      fs.mkdirSync(dir, { recursive: true });
      const f = path.join(dir, `${tag}__${step}-${LABEL}.png`);
      await page.screenshot({ path: f, animations: 'disabled' }).catch(() => {});
      return f;
    };

    // 面：主会话（含我方气泡）
    await shot('01-bubble', 'chat');
    run.chat = await page.evaluate(MEASURE);

    // 面：设置（手机走 工作台→设置；桌面点设置钮）
    if (mobile) {
      await page.evaluate(() => [...document.querySelectorAll('.dock-key')].pop()?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
      await page.waitForTimeout(1300);
      await page.evaluate(() => {
        const row = [...document.querySelectorAll('.mf-hub-row, .mf-hub button, .mobile-page button')].find((e) => /设置/.test(e.innerText || ''));
        row?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      });
    } else {
      await page.evaluate(() => document.getElementById('settings-btn')?.click());
    }
    await page.waitForTimeout(1800);
    await killTakeover(page);
    await shot('02-settings', 'top');
    run.settingsTop = await page.evaluate(MEASURE);
    // 滚到底看末行是否被 dock 压
    await page.evaluate(() => {
      const c = document.getElementById('settings-content');
      const m = document.getElementById('settings-modal');
      const host = c && c.scrollHeight > c.clientHeight ? c : m;
      const pb = document.querySelector('#mobile-pages .mobile-page.live .mobile-page-body');
      [host, pb].forEach((h) => { if (h && h.scrollHeight > h.clientHeight) h.scrollTop = h.scrollHeight; });
    });
    await page.waitForTimeout(700);
    await shot('02-settings', 'bottom');
    run.settingsBottom = await page.evaluate(MEASURE);
    if (!mobile) await page.evaluate(() => document.getElementById('settings-modal-close')?.click());
    await page.waitForTimeout(600);

    // 面：好友聊天（联系人 → 第一行）
    if (mobile) {
      await page.evaluate(() => {
        const keys = [...document.querySelectorAll('.dock-key')];
        keys[1]?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      });
      await page.waitForTimeout(1300);
      await page.evaluate(() => {
        const row = [...document.querySelectorAll('.mobile-page.live [class*="fm-row"], .mobile-page.live .fm-conv, .mobile-page.live li, .mobile-page.live button')]
          .find((e) => { const b = e.getBoundingClientRect(); return b.height > 44 && b.width > 150; });
        row?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      });
      await page.waitForTimeout(1800);
      await killTakeover(page);
      await shot('03-peer', 'chat');
      run.peer = await page.evaluate(MEASURE);
    }

    // 面：消息列表
    if (mobile) {
      await page.evaluate(() => document.querySelectorAll('.dock-key')[0]?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
      await page.waitForTimeout(1400);
      await shot('04-list', 'list');
      run.list = await page.evaluate(MEASURE);
    }

    // 面：画布
    await page.evaluate(() => document.getElementById('canvas-toggle-btn')?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    await page.waitForTimeout(1500);
    await killTakeover(page);
    await shot('05-canvas', 'canvas');
    run.canvas = await page.evaluate(MEASURE);

    out.runs[tag] = run;
    await ctx.close();
  }
}
await browser.close();
fs.writeFileSync(path.join(DATA, `compare-${LABEL}.json`), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1).slice(0, 3500));
