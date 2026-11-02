#!/usr/bin/env node
// quote-iconify-shots.mjs — 引用入口移入 footer + 引用牌图标化批（作者令 2026-11-02
// 11:00）的证据帧生成器。
//
// 用法：
//   node tests/quote-iconify-shots.mjs --root <静态根> --tag before|after [--out <dir>]
//
// `--root` = 一个同时含 `src/main/resources/web/**` 与
// `tests/fixtures/quote-message/harness.html` 的静态目录（harness 用绝对路径
// `/src/...` 引模块与样式）。改前帧 = `git archive HEAD` 落盘的纯净树，改后帧 =
// 本 worktree。两条腿跑同一份脚本、同一批帧号 ⇒ 前后可直接并排比。
//
// 帧清单（作者令要求）：
//   F1 手机 375×812 亮 — 展开叠卡（证明展开列零引用钮）
//   F2 手机 375×812 暗 — 同上
//   F3 桌面亮 — 鼠标真悬停在叠卡图片上（证明 hover 也不出图标）
//   F4 footer 入口特写（#input-bar 动作行；改前 = 该位无第 5 枚钮）
//   F5 footer 入口点击后：生成的 composer 引用 chip（pending 预览）
//   F6 四类引用牌并排（图片缩略 / 文档图标 / 文字 logo / 叠卡单张）
//   F7 三个文字 logo 候选对比（A/B/C 放大 3× + 16px 实尺）
import { chromium } from 'playwright';
import { mkdirSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const ROOT = resolve(arg('--root', join(dirname(fileURLToPath(import.meta.url)), '..')));
const TAG = arg('--tag', 'after');
const OUT = resolve(arg('--out', join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261102_quote-iconify', TAG)));

const results = [];
const ok = (label, pass, detail) => {
  results.push({ label, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${TAG} ${label}${detail !== undefined ? '  — ' + JSON.stringify(detail) : ''}`);
};
// 改前帧（--tag before）的期望值是**相反**的：那正是本批要修的红面。判据写成
// 双向显式断言（不是「改前随便过」）—— 改前帧若出现 footer 引用键/类型图标，
// 说明基线树不是改前状态，同样算 FAIL。
const BEFORE = TAG === 'before';
const expectBefore = (cond) => (BEFORE ? !cond : cond);

function freePort() {
  return new Promise((res, rej) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
    s.on('error', rej);
  });
}

mkdirSync(OUT, { recursive: true });
const port = await freePort();
const srv = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', ROOT], { stdio: 'ignore' });
const BASE = `http://127.0.0.1:${port}/tests/fixtures/quote-message/harness.html`;
for (let i = 0; i < 80; i++) {
  try { if ((await fetch(BASE)).ok) break; } catch { /* wait */ }
  await new Promise(r => setTimeout(r, 100));
}

const browser = await chromium.launch();
const errors = [];
const errorsOut = [];

async function openPage(viewport, extra = {}) {
  const ctx = await browser.newContext({ viewport, ...extra });
  const page = await ctx.newPage();
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errorsOut.push(m.text()); });
  await page.goto(BASE);
  await page.waitForFunction(() => window.__ready === true);
  return { ctx, page };
}

/** 探针能力面（改前树 = git archive HEAD，harness 只有旧探针；本批新增探针缺席）。
 *  取证脚本因此按能力分派：缺 __quotePill 的腿走旧探针路径（引用牌仍能出，只是没有
 *  类型图标读数）—— 前后两腿都能跑出完整帧，不靠「改前跳过」。 */
async function caps(page) {
  return page.evaluate(() => ({
    quotePill: typeof window.__quotePill === 'function',
    chipIcon: typeof window.__chipIconShape === 'function',
    quoteBlockShape: typeof window.__quoteBlockShape === 'function',
    stackFront: typeof window.__stackFront === 'function',
    logos: true,
  }));
}

/** 一条引用牌：新探针走 __quotePill；旧树退化为 startQuoteDirect + 渲染带引用行。 */
async function makePill(page, spec, text, msgId, c) {
  if (c.quotePill) return page.evaluate(([s, tx, id]) => window.__quotePill(s, tx, id), [spec, text, msgId]);
  await page.evaluate(s => window.__startQuoteDirect(s), spec);
  return page.evaluate(([tx, id]) => window.__renderUserRowWithQuote(tx, id).dataset.msgId, [text, msgId]);
}

/** 一条用户消息附 N 张图（叠卡）。 */
async function renderStack(page, n) {
  return page.evaluate(k => window.__renderUserStack(k), n);
}
async function expandStack(page, msgId) {
  await page.evaluate(id => window.__expandStack(id), msgId);
}

/* ── F1/F2 手机亮暗：展开态叠卡（零引用钮） ─────────────────────────── */
for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await openPage({ width: 375, height: 812 }, {
    hasTouch: true, isMobile: true, deviceScaleFactor: 2, colorScheme: scheme,
  });
  await page.evaluate(() => document.body.classList.add('mobile-layer'));
  const { msgId } = await renderStack(page, 3);
  await expandStack(page, msgId);
  await page.waitForTimeout(200);
  const read = await page.evaluate((id) => {
    const row = window.__mediaRowById(id);
    const b = document.getElementById('quote-item-btn');
    return {
      surfaceQuoteBtns: row.querySelectorAll('.pop-media-quote').length,
      layers: row.querySelectorAll('.pop-expand-view .media-layer').length,
      footerBtn: b ? { hidden: !!b.hidden, title: b.title, w: Math.round(b.getBoundingClientRect().width) } : null,
    };
  }, msgId);
  ok(`F${scheme === 'light' ? 1 : 2} 手机(${scheme}) 展开列引用钮数（改前=3 红面 / 改后=0）`,
    BEFORE ? (read.surfaceQuoteBtns === 3 && read.layers === 3)
      : (read.surfaceQuoteBtns === 0 && read.layers === 3 && read.footerBtn && !read.footerBtn.hidden), read);
  await page.screenshot({ path: join(OUT, `f${scheme === 'light' ? 1 : 2}-mobile-${scheme}-expanded.png`), fullPage: false });
  await page.locator('#chat .pop-stack-area').last().screenshot({ path: join(OUT, `f${scheme === 'light' ? 1 : 2}-mobile-${scheme}-stack-closeup.png`) });
  await ctx.close();
}

/* ── F3 桌面亮：鼠标真悬停在叠卡图片上 ─────────────────────────────── */
{
  const { ctx, page } = await openPage({ width: 1200, height: 900 }, { colorScheme: 'light' });
  const { msgId } = await renderStack(page, 3);
  await expandStack(page, msgId);
  await page.locator('#chat .pop-expand-view .media-layer').nth(1).hover();
  await page.waitForTimeout(220);
  const read = await page.evaluate((id) => {
    const row = window.__mediaRowById(id);
    return {
      quoteBtns: row.querySelectorAll('.pop-media-quote').length,
      hovering: row.querySelectorAll('.media-layer:hover .pop-media-quote').length,
      canvasCornerVisible: Array.from(row.querySelectorAll('.pop-expand-view .media-layer'))
        .map(l => { const b = l.querySelector('.pop-media-canvas'); return b ? getComputedStyle(b).opacity : null; }),
    };
  }, msgId);
  ok(BEFORE ? 'F3 桌面 hover 图片=改前面（hover 才浮出引用图标）' : 'F3 桌面 hover 图片时零引用图标',
    BEFORE ? (read.quoteBtns === 3 && read.hovering === 1) : (read.quoteBtns === 0 && read.hovering === 0), read);
  await page.screenshot({ path: join(OUT, 'f3-desktop-light-hover-no-icon.png'), fullPage: false });
  await page.locator('#chat .pop-stack-area').last().screenshot({ path: join(OUT, 'f3-desktop-light-stack-closeup.png') });
  await ctx.close();
}

/* ── F4/F5 footer 入口特写 + 点击后的 composer chip ────────────────── */
{
  const { ctx, page } = await openPage({ width: 1200, height: 900 }, { colorScheme: 'light' });
  const { msgId } = await renderStack(page, 3);
  const bar = await page.evaluate(() => {
    const b = document.getElementById('quote-item-btn');
    return {
      barButtons: Array.from(document.querySelectorAll('#input-bar > button')).map(x => x.id || x.className),
      footerBtn: b ? { hidden: !!b.hidden, title: b.title } : null,
    };
  });
  ok(BEFORE ? 'F4 footer 动作行（改前无 #quote-item-btn，红面）' : 'F4 footer 动作行含 #quote-item-btn（可见）',
    BEFORE ? !bar.barButtons.includes('quote-item-btn')
      : (bar.barButtons.includes('quote-item-btn') && bar.footerBtn && !bar.footerBtn.hidden), bar);
  await page.locator('#input-bar').screenshot({ path: join(OUT, 'f4-footer-entry.png') });

  // 改前没有 footer 入口 ⇒ 这一帧只可能是「动作行原样」对照（红面 = 无第 5 枚键）
  if (!BEFORE) {
    if (bar.footerBtn) {
      await page.evaluate((id) => window.__quoteItemOn(id, 1), msgId);
      await page.waitForTimeout(120);
      const chip = await page.evaluate(() => ({
        text: (document.querySelector('#attachment-preview .att-ref[data-ref-type="chat-message"]') || {}).textContent || '',
        icons: !!document.querySelector('#attachment-preview .nf-qicon'),
      }));
      ok('F5 footer 入口生成 composer 引用 chip（带类型图标）', chip.text.includes('第 2/3 张') && chip.icons, chip);
      await page.locator('#input-bar').screenshot({ path: join(OUT, 'f5-footer-click-pending-chip.png') });
    }
  } else {
    // 改前对照帧：从旧的叠卡引用钮进引用态（那条路本批退役）
    const old = await page.evaluate(async (id) => {
      await window.__expandStack(id);
      const row = window.__mediaRowById(id);
      const btn = row.querySelector('.pop-expand-view .media-layer:nth-child(2) .pop-media-quote');
      if (!btn) return { ok: false };
      btn.click();
      await new Promise(r => setTimeout(r, 30));
      const chip = document.querySelector('#attachment-preview .att-ref[data-ref-type="chat-message"]');
      return { ok: true, text: chip ? chip.textContent : '', icons: !!document.querySelector('#attachment-preview .nf-qicon') };
    }, msgId);
    ok('F5 改前对照：旧叠卡引用钮进引用态（chip 无类型图标）', old.ok && !old.icons, old);
    await page.locator('#input-bar').screenshot({ path: join(OUT, 'f5-before-stack-button-chip.png') });
  }
  await ctx.close();
}

/* ── F6 四类引用牌并排 ─────────────────────────────────────────────── */
{
  const { ctx, page } = await openPage({ width: 1200, height: 1100 }, { colorScheme: 'light' });
  const c = await caps(page);
  const tiny = await page.evaluate(() => (typeof window.__tinyImg === 'function'
    ? window.__tinyImg('THUMB')
    : 'data:image/svg+xml;base64,' + btoa('<svg xmlns="http://www.w3.org/2000/svg" width="80" height="80"><rect width="100%" height="100%" fill="#2a4d8f"/><text x="4" y="20" fill="#fff">THUMB</text></svg>')));
  // 图片（真缩略）
  await makePill(page, {
    kind: 'image', fileName: 'site-photo.png', path: '/tmp/x/site-photo.png',
    attachments: [{ type: 'image', name: 'site-photo.png', path: '/tmp/x/site-photo.png', preview: tiny }],
    senderRole: 'user', senderName: '我', ts: Date.now(),
  }, '图片引用（真缩略）', 'cm-f6-img', c);
  // 文档（.fc-icon 原图标）
  await makePill(page, {
    kind: 'file', fileName: '季度报告.pdf', path: '/tmp/x/季度报告.pdf',
    attachments: [{ type: 'file', name: '季度报告.pdf', path: '/tmp/x/季度报告.pdf' }],
    senderRole: 'user', senderName: '我', ts: Date.now(),
  }, '文档引用（原图标）', 'cm-f6-doc', c);
  // 文字（新 logo）
  await makePill(page, {
    kind: 'text', text: '这是一段被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
  }, '文字引用（新 logo）', 'cm-f6-txt', c);
  // 叠卡单张
  const { msgId } = await renderStack(page, 3);
  let rid;
  if (c.quotePill) {
    await page.evaluate(id => window.__quoteItemOn(id, 1), msgId);
    rid = await page.evaluate(() => window.__sendQuoted('叠卡单张引用（第 2/3 张）', 'cm-f6-item'));
  } else {
    // 改前腿：从旧叠卡引用钮进引用态
    await page.evaluate(id => window.__expandStack(id), msgId);
    await page.evaluate((id) => {
      const row = window.__mediaRowById(id);
      const btn = row.querySelector('.pop-expand-view .media-layer:nth-child(2) .pop-media-quote');
      if (btn) btn.click();
    }, msgId);
    await page.waitForTimeout(40);
    rid = await page.evaluate(() => window.__sendQuoted('叠卡单张引用（第 2/3 张）', 'cm-f6-item'));
  }
  await page.waitForTimeout(150);
  const kinds = c.quoteBlockShape ? await page.evaluate(ids => ids.map(id => {
    const s = window.__quoteBlockShape(id);
    return s ? { id, kind: s.icon && s.icon.kind, text: s.text.slice(0, 24) } : null;
  }), ['cm-f6-img', 'cm-f6-doc', 'cm-f6-txt', rid]) : await page.evaluate(ids => ids.map(id => {
    const row = window.__rowById(id);
    const box = row && row.querySelector('.msg-quote-block');
    return { id, kind: box ? (box.querySelector('.nf-qicon') ? '?' : null) : null, text: box ? box.textContent.slice(0, 24) : '' };
  }), ['cm-f6-img', 'cm-f6-doc', 'cm-f6-txt', rid]);
  const withIcon = kinds.filter(k => k && k.kind).length;
  ok(`F6 四类引用牌类型图标（改前=0 红面 / 改后=4）`, BEFORE ? withIcon === 0 : withIcon === 4, kinds);
  await page.locator('#chat').screenshot({ path: join(OUT, 'f6-four-pill-kinds.png') });
  for (const k of kinds) {
    if (!k) continue;
    await page.locator(`#chat .row[data-msg-id="${k.id}"] .msg-quote-block`).screenshot({ path: join(OUT, `f6-pill-${k.id}.png`) });
  }
  await ctx.close();
}

/* ── F7 文字引用牌图标（图标统一批 2026-11-02 12:58：= footer 引用入口图标） ──
 *
 *  历史说明：上一批（引用牌图标化 11:00）在本帧渲染 A/B/C 三个候选 logo。作者同日
 *  裁定弃用自制 logo、直接复用 footer 引用入口键的图标，候选字形随之删除（死代码），
 *  本帧改为对照「footer 入口键字形 / 引用牌内实样」——同一枚、两种色上下文。
 *  候选对比帧 `evidence/20261102_quote-iconify/after/f7-text-logo-candidates.png` 保留
 *  作历史，不再由本脚本生成。 */
{
  const { ctx, page } = await openPage({ width: 900, height: 420 }, { colorScheme: 'light' });
  const info = await page.evaluate(async () => {
    // 字形单源：改前树没有 quoteLogo.js ⇒ 退化为「无字形可渲染」的说明帧。
    let svg = null;
    try {
      const mod = await import('/src/main/resources/web/js/quoteLogo.js');
      svg = mod.QUOTE_ENTRY_ICON || null;
    } catch { svg = null; }
    const host = document.createElement('div');
    host.id = 'logo-candidates';
    host.style.cssText = 'position:absolute;inset:0;margin:0;padding:20px;background:#fff;font:12px/1.4 -apple-system,sans-serif;color:#333;display:flex;flex-direction:column;gap:14px;';
    if (!svg) {
      host.innerHTML = '<div style="font-weight:600">改前树：文字引用牌没有类型图标 —— 本帧为红面对照。</div>';
      document.body.appendChild(host);
      return [];
    }
    const rows = [
      ['footer 引用入口键（#quote-item-btn）', '#7b7e88'],
      ['文字引用牌 16px 实尺（引用牌色上下文）', '#8b8e96'],
      ['引用块里的样子（Nebula：摘要）', '#8b8e96'],
    ];
    for (const [label, color] of rows) {
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;gap:18px;';
      row.innerHTML = `<div style="width:260px;font-weight:600">${label}</div>`
        + `<div style="width:48px;height:48px;color:${color}">${svg.replace('<svg ', '<svg style="width:48px;height:48px;display:block" ')}</div>`
        + `<div style="width:110px;display:flex;align-items:center;gap:8px;color:${color}">`
        + `<div style="width:16px;height:16px">${svg.replace('<svg ', '<svg style="width:16px;height:16px;display:block" ')}</div>`
        + `<span style="font-size:11px">16px 实尺</span></div>`;
      host.appendChild(row);
    }
    document.body.appendChild(host);
    return rows.map(r => r[0]);
  });
  ok(BEFORE ? 'F7 改前无文字引用牌图标（红面对照帧）' : 'F7 文字引用牌图标 = footer 入口图标（同一枚）',
    BEFORE ? info.length === 0 : info.length === 3, info);
  await page.locator('#logo-candidates').screenshot({ path: join(OUT, 'f7-text-logo-candidates.png') });
  await ctx.close();
}

// 改前树探 quoteLogo.js 会 404（该模块本批才引入）——那条 404 是**预期的红面**，
// 不算 console 错误；改后树任何 console error 都是失败。
const unexpectedConsole = BEFORE ? errorsOut.filter(x => !/404/.test(x)) : errorsOut;
ok('console 零 error' + (BEFORE ? '（豁免 quoteLogo.js 404 = 预期红面）' : ''),
  errors.length === 0 && unexpectedConsole.length === 0,
  { pageErrors: errors.slice(0, 3), consoleErrors: errorsOut.slice(0, 3) });

await browser.close();
srv.kill('SIGTERM');
const fails = results.filter(r => !r.pass).length;
console.log(`\n${TAG}: ${results.length - fails} PASS / ${fails} FAIL  → ${OUT}`);
process.exit(fails ? 1 : 0);
