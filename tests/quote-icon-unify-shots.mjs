#!/usr/bin/env node
// quote-icon-unify-shots.mjs — 引用入口图标统一批（作者令 2026-11-02 12:58「图标就用跟我们
// footer那个引用图标一样的吧」）的证据帧生成器。
//
// 用法：
//   node tests/quote-icon-unify-shots.mjs --root <静态根> --tag before|after [--out <dir>]
//
// `--root` = 同时含 `src/main/resources/web/**` 与 `tests/fixtures/quote-message/harness.html`
// 的静态目录。改前帧 = `git archive HEAD` 落盘的纯净树，改后帧 = 本 worktree。两腿跑同一
// 脚本、同一批帧号 ⇒ 前后可直接并排比。
//
// 帧清单（任务书 §3 证据要求）：
//   U1 手机 375×812 亮 — footer 入口键 + 文字引用牌**并排**特写（同形状可判）
//   U2 手机 375×812 暗 — 同上
//   U3 桌面亮 — 同上 + 引用牌实样（色上下文）
//   U4 桌面暗 — 同上
//   U5 四类引用牌并排（图片缩略 / 文档图标 / 文字图标 / 历史腿图片字形）—— 尺寸一致
//   U6 文字引用牌图标裁切（改前/改后两腿各出一半，交付时并排贴进文档）
//   U7 字形身份读数帧：footer 键与引用牌 icon 的 path d 串并排打屏 + 「逐字相同」判定
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const ROOT = resolve(arg('--root', join(dirname(fileURLToPath(import.meta.url)), '..')));
const TAG = arg('--tag', 'after');
const OUT = resolve(arg('--out', join(dirname(fileURLToPath(import.meta.url)), '..',
  'evidence', '20261102_quote-icon-unify', TAG)));

const results = [];
const ok = (label, pass, detail) => {
  results.push({ label, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${TAG} ${label}${detail !== undefined ? '  — ' + JSON.stringify(detail) : ''}`);
};
// 改前帧的期望值是**相反**的：改前文字引用牌用的是自制候选 logo（A），与 footer 键
// 不同字形 —— 正是本批要改的红面。判据双向显式，改前树若已是改后状态同样 FAIL。
const BEFORE = TAG === 'before';

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

/** 铺一条文字引用牌 + 一条叠卡（footer 键显形）。 */
async function seed(page) {
  await page.evaluate(() => window.__renderUserStack(3));
  const txtId = await page.evaluate(() => window.__quotePill({
    kind: 'text', text: '这是一段被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
  }, '带文字引用', 'cm-txt'));
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
  await page.waitForTimeout(120);
  return txtId;
}

/** 「footer 键 + 文字引用牌」并排对照面板（同缩放，肉眼可判是不是同一枚）。
 *  返回两者 path d 串供断言。 */
async function sideBySide(page, txtId, dark) {
  return page.evaluate(([id, isDark]) => {
    const btn = document.getElementById('quote-item-btn');
    const row = window.__rowById(id);
    const icon = row && row.querySelector('.msg-quote-block .nf-qicon');
    const host = document.createElement('div');
    host.id = 'unify-compare';
    host.style.cssText = 'position:fixed;inset:0;z-index:99999;display:flex;align-items:center;justify-content:center;gap:36px;'
      + `background:${isDark ? '#101418' : '#ffffff'};font:12px/1.5 -apple-system,sans-serif;color:${isDark ? '#c9ccd2' : '#333'}`;
    const cell = (label, node) => {
      const wrap = document.createElement('div');
      wrap.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:12px;';
      const box = document.createElement('div');
      box.style.cssText = 'width:96px;height:96px;display:flex;align-items:center;justify-content:center;border-radius:14px;'
        + `background:${isDark ? 'rgba(255,255,255,.07)' : 'rgba(0,0,0,.045)'}`;
      // 只取**字形本体**（SVG）进对照盒：宿主按钮 / `.nf-qicon` 各自的盒尺寸不参与，
      // 两边同尺度才可肉眼判「是不是同一枚」。宿主 CSS 有 `.icon-btn svg{20px}` 与
      // `.nf-qicon svg{100%}` ⇒ 必须 !important 才能压到同一档。
      const svgNode = node && (node.tagName === 'svg' ? node : node.querySelector('svg'));
      if (svgNode) {
        const c = svgNode.cloneNode(true);
        c.style.setProperty('width', '64px', 'important');
        c.style.setProperty('height', '64px', 'important');
        c.style.setProperty('display', 'block', 'important');
        box.appendChild(c);
      }
      const lbl = document.createElement('div');
      lbl.textContent = label;
      lbl.style.cssText = 'font-weight:600;opacity:.85;max-width:220px;text-align:center';
      wrap.append(box, lbl);
      return wrap;
    };
    const dOf = (node) => {
      const svg = node && (node.tagName === 'svg' ? node : node.querySelector('svg'));
      return svg ? Array.from(svg.querySelectorAll('path')).map(p => p.getAttribute('d')) : [];
    };
    host.appendChild(cell('footer 引用入口键 #quote-item-btn', btn));
    host.appendChild(cell('文字引用牌 .msg-quote-icon（16px 档）', icon));
    document.body.appendChild(host);
    return { footerD: dOf(btn), pillD: dOf(icon) };
  }, [txtId, dark]);
}

const sameD = (d) => JSON.stringify(d.footerD) === JSON.stringify(d.pillD)
  && d.footerD.length > 0;
const judge = (d) => (BEFORE ? (d.footerD.length > 0 && !sameD(d)) : sameD(d));

/* ── U1/U2 手机亮暗：footer 键 + 引用牌并排 ─────────────────────────── */
for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await openPage({ width: 375, height: 812 }, {
    hasTouch: true, isMobile: true, deviceScaleFactor: 2, colorScheme: scheme,
  });
  await page.evaluate(() => document.body.classList.add('mobile-layer'));
  const txtId = await seed(page);
  const d = await sideBySide(page, txtId, scheme === 'dark');
  ok(`U${scheme === 'light' ? 1 : 2} 手机(${scheme}) footer 键与文字引用牌同字形（改前不同 = 红面）`,
    judge(d), { footerPaths: d.footerD.length, pillPaths: d.pillD.length, same: sameD(d) });
  await page.locator('#unify-compare').screenshot({ path: join(OUT, `u${scheme === 'light' ? 1 : 2}-mobile-${scheme}-footer-vs-pill.png`) });
  await ctx.close();
}

/* ── U3/U4 桌面亮暗：同上 + 引用牌实样（色上下文实证）───────────────── */
for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await openPage({ width: 1200, height: 900 }, { colorScheme: scheme });
  const txtId = await seed(page);
  const d = await sideBySide(page, txtId, scheme === 'dark');
  ok(`U${scheme === 'light' ? 3 : 4} 桌面(${scheme}) footer 键与文字引用牌同字形`,
    judge(d), { same: sameD(d) });
  await page.locator('#unify-compare').screenshot({ path: join(OUT, `u${scheme === 'light' ? 3 : 4}-desktop-${scheme}-footer-vs-pill.png`) });
  await page.evaluate(() => document.getElementById('unify-compare')?.remove());
  await page.locator(`#chat .row[data-msg-id="${txtId}"] .msg-quote-block`)
    .screenshot({ path: join(OUT, `u${scheme === 'light' ? 3 : 4}-desktop-${scheme}-pill.png`) });
  await ctx.close();
}

/* ── U5 四类引用牌并排（尺寸一致证明）─────────────────────────────── */
{
  const { ctx, page } = await openPage({ width: 1200, height: 1100 }, { colorScheme: 'light' });
  const tiny = await page.evaluate(() => window.__tinyImg('THUMB'));
  const ids = await page.evaluate(([preview]) => {
    const mk = (spec, text, id) => window.__quotePill(spec, text, id);
    return (async () => ({
      img: await mk({ kind: 'image', fileName: 'site-photo.png', path: '/tmp/x/site-photo.png', attachments: [{ type: 'image', name: 'site-photo.png', path: '/tmp/x/site-photo.png', preview }], senderRole: 'user', senderName: '我', ts: Date.now() }, '图片引用（真缩略）', 'cm-f-img'),
      doc: await mk({ kind: 'file', fileName: '季度报告.pdf', path: '/tmp/x/季度报告.pdf', attachments: [{ type: 'file', name: '季度报告.pdf', path: '/tmp/x/季度报告.pdf' }], senderRole: 'user', senderName: '我', ts: Date.now() }, '文档引用（原图标）', 'cm-f-doc'),
      txt: await mk({ kind: 'text', text: '这是一段被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now() }, '文字引用（入口图标）', 'cm-f-txt'),
      hist: await mk({ kind: 'image', fileName: 'archived-shot.png', path: '/tmp/x/archived-shot.png', attachments: [{ type: 'image', name: 'archived-shot.png', path: '/tmp/x/archived-shot.png' }], senderRole: 'user', senderName: '我', ts: Date.now() }, '历史图片引用（图片字形）', 'cm-f-hist'),
    }))();
  }, [tiny]);
  const boxes = await page.evaluate(ids => Object.entries(ids).map(([k, id]) => {
    const row = window.__rowById(id);
    const icon = row && row.querySelector('.msg-quote-block .nf-qicon');
    const r = icon && icon.getBoundingClientRect();
    return { k, w: r ? Math.round(r.width) : 0, h: r ? Math.round(r.height) : 0 };
  }), ids);
  ok('U5 四类引用牌图标盒同档（16×16）', boxes.every(b => b.w === 16 && b.h === 16), boxes);
  await page.locator('#chat').screenshot({ path: join(OUT, 'u5-four-pill-kinds.png') });
  await ctx.close();
}

/* ── U6 文字引用牌图标放大特写（5× + 16px 实尺；两腿同名帧并排即前后对照）────── */
{
  const { ctx, page } = await openPage({ width: 900, height: 320 }, { colorScheme: 'light' });
  const txtId = await seed(page);
  const info = await page.evaluate(([id, before]) => {
    const row = window.__rowById(id);
    const icon = row && row.querySelector('.msg-quote-block .nf-qicon');
    const svgOf = (n) => (n && (n.tagName === 'svg' ? n : n.querySelector('svg'))) || null;
    const svg = svgOf(icon);
    const host = document.createElement('div');
    host.id = 'unify-zoom';
    host.style.cssText = 'position:fixed;inset:0;z-index:99999;background:#fff;color:#333;display:flex;align-items:center;justify-content:center;gap:40px;font:12px/1.5 -apple-system,sans-serif';
    const mk = (label, size) => {
      const cell = document.createElement('div');
      cell.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:10px';
      const box = document.createElement('div');
      box.style.cssText = `width:${size}px;height:${size}px;display:flex;align-items:center;justify-content:center;color:#8b8e96`;
      if (svg) {
        const c = svg.cloneNode(true);
        c.style.width = `${size}px`; c.style.height = `${size}px`; c.style.display = 'block';
        box.appendChild(c);
      }
      const lbl = document.createElement('div');
      lbl.textContent = label;
      lbl.style.cssText = 'font-weight:600;opacity:.8';
      cell.append(box, lbl);
      return cell;
    };
    host.append(mk('5× 放大（80px）', 80), mk('引用牌内实尺（16px）', 16));
    const note = document.createElement('div');
    note.style.cssText = 'max-width:260px;line-height:1.7;opacity:.8';
    note.textContent = before
      ? '改前：文字引用牌用自制候选 logo A（双引号 + 双横线），与 footer 引用入口键不是同一枚。'
      : '改后：文字引用牌 = footer 引用入口键同一枚 QUOTE_ENTRY_ICON（双引号双路径，只复用形状、颜色走引用牌色上下文）。';
    host.appendChild(note);
    document.body.appendChild(host);
    return { hasSvg: !!svg };
  }, [txtId, BEFORE]);
  ok(`U6 文字引用牌图标特写（${BEFORE ? '改前候选 logo A' : '改后入口图标'}）`, info.hasSvg, info);
  await page.locator('#unify-zoom').screenshot({ path: join(OUT, 'u6-text-pill-icon-zoom.png') });
  await ctx.close();
}

/* ── U7 字形身份读数帧（path d 串并排打屏 + 逐字相同判定）─────────────── */
{
  const { ctx, page } = await openPage({ width: 1000, height: 460 }, { colorScheme: 'light' });
  const txtId = await seed(page);
  const info = await page.evaluate(([id, before]) => {
    const btn = document.getElementById('quote-item-btn');
    const row = window.__rowById(id);
    const icon = row && row.querySelector('.msg-quote-block .nf-qicon');
    const dOf = (n) => {
      const svg = n && (n.tagName === 'svg' ? n : n.querySelector('svg'));
      return svg ? Array.from(svg.querySelectorAll('path')).map(p => p.getAttribute('d')) : [];
    };
    const fd = dOf(btn), pd = dOf(icon);
    const same = JSON.stringify(fd) === JSON.stringify(pd) && fd.length > 0;
    const host = document.createElement('div');
    host.id = 'unify-identity';
    host.style.cssText = 'position:fixed;inset:0;z-index:99999;background:#fff;color:#222;font:12px/1.6 ui-monospace,Menlo,monospace;padding:20px;overflow:auto';
    host.innerHTML = `<div style="font:600 13px/1.6 -apple-system,sans-serif;margin-bottom:10px">`
      + `字形身份读数 · ${before ? '改前树（footer 键 lucide quote vs 引用牌自制 logo A —— 两套字形）' : '改后树（两处同一枚 QUOTE_ENTRY_ICON）'}</div>`
      + `<div style="margin-bottom:8px"><b>footer 键 #quote-item-btn</b> paths=${fd.length}</div>`
      + fd.map(x => `<div style="color:#0a58ca;word-break:break-all;margin:2px 0">${x}</div>`).join('')
      + `<div style="margin:10px 0 8px"><b>文字引用牌 .msg-quote-icon</b> paths=${pd.length}</div>`
      + pd.map(x => `<div style="color:${same ? '#0a7f3f' : '#b42318'};word-break:break-all;margin:2px 0">${x}</div>`).join('')
      + `<div style="margin-top:12px;font:600 13px/1.6 -apple-system,sans-serif;color:${same ? '#0a7f3f' : '#b42318'}">`
      + `path d 逐字相同 = ${same}</div>`;
    document.body.appendChild(host);
    return { same, n: fd.length };
  }, [txtId, BEFORE]);
  ok(BEFORE ? 'U7 改前：两处字形不同（红面）' : 'U7 改后：两处字形逐字相同',
    BEFORE ? !info.same : info.same, info);
  await page.locator('#unify-identity').screenshot({ path: join(OUT, 'u7-icon-identity.png') });
  await ctx.close();
}

// 任何脚本类 console error / pageerror 都是失败；「Failed to load resource」是静态
// harness 里历史腿取字节链（nf-ticket/nf-file）的基线同源噪声 —— `git archive HEAD`
// 纯净树实测逐条相同（两腿一并豁免）。
const noise = /Failed to load resource/;
const unexpectedConsole = errorsOut.filter(x => !noise.test(x));
ok('console 零 error（豁免资源加载噪声 = 基线同源）',
  errors.length === 0 && unexpectedConsole.length === 0,
  { pageErrors: errors.slice(0, 3), consoleErrors: errorsOut.slice(0, 3) });

await browser.close();
srv.kill('SIGTERM');
const fails = results.filter(r => !r.pass).length;
console.log(`\n${TAG}: ${results.length - fails} PASS / ${fails} FAIL  → ${OUT}`);
process.exit(fails ? 1 : 0);
