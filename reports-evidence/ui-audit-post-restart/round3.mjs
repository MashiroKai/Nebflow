// round3.mjs — 气泡定稿：助手白 + 用户绿，逐档 WCAG 对比度实测 + 实拍
// 用法：node round3.mjs before(main tree)  /  ROUTE=1 node round3.mjs after(worktree)
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const WT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/src/main/resources/web';
const BASE = 'http://localhost:6138/';
const LABEL = process.argv[2] || 'after';
const ROUTE = process.env.ROUTE === '1';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const OVR = [
  ['**/css/base.css*', `${WT}/css/base.css`, 'text/css'],
  ['**/css/chat.css*', `${WT}/css/chat.css`, 'text/css'],
  ['**/css/glass.css*', `${WT}/css/glass.css`, 'text/css'],
];

// 页内：WCAG 相对亮度 + 比值；把气泡及其内部件的前景/底色的**实际计算值**取出来
const MEASURE = () => {
  const parse = (c) => {
    const m = c.match(/rgba?\(([^)]+)\)/);
    if (m) { const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; }
    const s = c.match(/color\(srgb ([^)]+)\)/);
    if (s) { const p = s[1].split(/[\s/]+/).filter(Boolean).map(Number); return [p[0] * 255, p[1] * 255, p[2] * 255, p.length > 3 ? p[3] : 1]; }
    return null;
  };
  const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  const L = (rgb) => 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2]);
  const ratio = (fg, bg) => {
    const a = L(fg), b = L(bg);
    const hi = Math.max(a, b), lo = Math.min(a, b);
    return +((hi + 0.05) / (lo + 0.05)).toFixed(2);
  };
  const effBg = (el) => {
    let n = el;
    while (n && n !== document.documentElement) {
      const cs = getComputedStyle(n);
      const c = parse(cs.backgroundColor);
      if (c && c[3] > 0.5) return c;
      n = n.parentElement;
    }
    return parse(getComputedStyle(document.body).backgroundColor) || [255, 255, 255, 1];
  };
  const probe = (sel) => {
    const e = document.querySelector(sel);
    if (!e) return null;
    const cs = getComputedStyle(e);
    const fg = parse(cs.color);
    const bg = effBg(e);
    const r = e.getBoundingClientRect();
    return {
      sel, text: (e.innerText || '').trim().slice(0, 18),
      fg: cs.color, bg: `rgb(${bg.slice(0, 3).map((v) => Math.round(v)).join(', ')})`,
      fontSize: cs.fontSize, weight: cs.fontWeight,
      contrast: fg ? ratio(fg, bg) : null,
      box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
    };
  };
  const pageBg = getComputedStyle(document.body).backgroundColor;
  const ours = probe('.bubble.user');
  const theirs = probe('.bubble.ai') || probe('.bubble.peer');
  // 我方气泡内部的次级文字（时间戳/状态/引用块/代码）——逐类取样
  const innerSel = ['.bubble.user .bubble-time', '.bubble.user .msg-time', '.bubble.user time',
    '.bubble.user code', '.bubble.user a', '.bubble.user .msg-quote-block'];
  const inner = {};
  for (const s of innerSel) { const p = probe(s); if (p) inner[s] = p; }
  // 气泡下方的行元数据（时间戳/复制钮所在行）
  const meta = probe('.row .row-meta') || probe('[class*="row-time"]') || probe('.row .time');
  // 时间戳/状态标记：按文本形态找（类名不稳），并给出其对比度
  const byText = (re, near) => {
    const scope = near ? (document.querySelector(near) || document) : document;
    for (const e of scope.querySelectorAll('*')) {
      if (e.children.length) continue;
      const t = (e.innerText || '').trim();
      if (!re.test(t)) continue;
      const cs = getComputedStyle(e);
      const bg = effBg(e);
      const fg = parse(cs.color);
      if (!fg) continue;
      const r = e.getBoundingClientRect();
      if (r.width < 2 || cs.display === 'none') continue;
      return { sel: `${e.tagName.toLowerCase()}.${(e.className || '').toString().split(/\s+/)[0] || ''}`, text: t.slice(0, 16), fg: cs.color, bg: `rgb(${bg.slice(0, 3).map((v) => Math.round(v)).join(', ')})`, fontSize: cs.fontSize, contrast: ratio(fg, bg) };
    }
    return null;
  };
  const stamp = byText(/^\d{1,2}:\d{2}(:\d{2})?$/);
  const stampInBubble = byText(/^\d{1,2}:\d{2}(:\d{2})?$/, '.bubble.user');
  return { pageBg, ours, theirs, inner, meta, stamp, stampInBubble };
};

const browser = await chromium.launch({ headless: true });
const out = { label: LABEL, route: ROUTE, runs: {} };
for (const theme of ['light', 'dark']) {
  for (const [size, vp, mobile] of [['mobile', { width: 375, height: 812 }, true], ['desktop', { width: 1440, height: 900 }, false]]) {
    const ctx = await browser.newContext({ viewport: vp, isMobile: mobile, hasTouch: mobile, colorScheme: theme, deviceScaleFactor: mobile ? 2 : 1, locale: 'zh-CN' });
    if (ROUTE) for (const [glob, file, ct] of OVR) {
      await ctx.route(glob, async (r) => {
        try { await r.fulfill({ status: 200, contentType: `${ct}; charset=utf-8`, body: fs.readFileSync(file) }); } catch { await r.fallback(); }
      });
    }
    const page = await ctx.newPage();
    await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(9000);
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
    const dir = path.join(ROOT, '20-fixes', '10-bubble-final');
    fs.mkdirSync(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, `${theme}-${size}__chat-${LABEL}.png`), animations: 'disabled' }).catch(() => {});
    out.runs[`${theme}-${size}`] = await page.evaluate(MEASURE);
    await ctx.close();
  }
}
await browser.close();
fs.writeFileSync(path.join(ROOT, '_data', `round3-${LABEL}.json`), JSON.stringify(out, null, 1));
for (const [k, v] of Object.entries(out.runs)) {
  console.log(k, '| ours', v.ours && `${v.ours.bg} / ${v.ours.fg} → ${v.ours.contrast}:1`, '| theirs', v.theirs && `${v.theirs.bg} / ${v.theirs.fg} → ${v.theirs.contrast}:1`);
}
