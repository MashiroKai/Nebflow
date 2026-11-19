// entrycard-dot-slot.spec.mjs — 入场加载卡四状态图标统一槽位（首项勾图标偏移批
// 2026-11-03，作者实拍 IMG_1699：「样式表」的勾相对其余三项横向偏移，要求靠左对齐）。
//
// 根因（本批改前实测，probe 数字见 evidence/20261103_entrycard-dot-slot/before.txt）：
//   四行 = [点14][gap10][标签W][gap10][::after留白14] 居中排版 ⇒ 点左缘 =
//   卡中线 − (48+W)/2，**随标签宽度漂移**。「样式表」3 个 CJK 字（42px）vs 四字
//   标签（56px）⇒ 首行点恒偏右 7px（四态×双主题一致；点盒本身 14×14 固定，
//   勾/叉伪元素几何逐行相同——不是字形问题，是**槽位锚点**问题）。en 更糟：
//   四行标签宽不等（38.9/57.5/93.2/84.5px）⇒ 四点 x 铺开 27px。
//
// 修法（css/cards.css）：标签升级为**固定宽度槽**（min-width：zh 4em = 最长四字
// 项；en 7.5em = 最长项 Checking host 93.2px ≈6.7em + 余量）+ 槽内 text-align
// 居中。四行排版盒全等 ⇒ 点列锚点统一（同一 left 锚）；点本身 14×14 固定槽、
// 字形伪元素绝对定位居中，done/pending 内容变化不推槽位。
//
// 契约钉（本 spec，机械判据）：
//   D1  四点左缘两两差 ≤1px——混合态 [done,done,pending,pending] 与反排
//       [pending,pending,done,done] 各测一遍（改前红：首行 +7px）；
//   D2  状态不推槽位：同行两点 x 在两混合态下差 ≤1px；点盒 14×14 恒定；
//   D3  文字居中不被破坏：各行文字盒中心 ↔ 卡中线 ≤1px（synccard-center S1 同向）；
//   D4  点不与文字相侵：点右缘 ≤ 文字盒左缘；
//   D5  zh 与 en 双 locale 各验（en 槽由 .nf-entry-card[data-locale="en"] 提供）；
//   D6  亮/暗双主题样张存证 + 零非环境噪声异常。
//
// Run: node node_modules/@playwright/test/cli.js test tests/entrycard-dot-slot.spec.mjs
import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const APP_PATH = '/src/main/resources/web/index.html';
const SHOTS = path.join(REPO_ROOT, 'evidence', '20261103_entrycard-dot-slot', 'after');

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], { stdio: 'ignore' });
}

async function waitUntilUp(url, tries = 400) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${APP_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

/* 入场卡真身 + 三/四态守卫（与 synccard-center.spec 同一夹具形态：真页面真样式，
   只跳过网络腿——状态是 DOM 状态，不是数据依赖）。locale 走门脚本同一读法
   （nebflow_locale）。 */
async function openEntryCard(browser, { colorScheme, locale }) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
  await ctx.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', loc);
    localStorage.setItem('stand_input_history', '[]');
  }, locale);
  const page = await ctx.newPage();
  const errors = [];
  const NOISE = /Failed to load resource|WebSocket connection|handshake|Monaco Editor|Monaco|404 \(File not found\)|status of 404|sw\.js|service.?worker|manifest|bad HTTP response code/i;
  page.on('pageerror', e => {
    const txt = 'PAGEERROR ' + String(e);
    if (NOISE.test(txt)) return;
    errors.push(txt);
  });
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const txt = m.text();
    if (NOISE.test(txt)) return;
    errors.push(txt);
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.goto(`http://127.0.0.1:${port}${APP_PATH}`);
  await page.waitForSelector('#nf-boot-gate .nf-entry-item', { timeout: 20000 });
  await page.evaluate(() => {
    const pin = () => {
      const face = document.getElementById('nf-boot-gate');
      if (face) {
        if (face.style.display === 'none') face.style.display = '';
        if (face.getAttribute('data-entry') !== 'on') face.setAttribute('data-entry', 'on');
        if (face.hasAttribute('data-state')) face.removeAttribute('data-state');
      }
      const root = document.documentElement;
      if (!root.classList.contains('nf-boot-gate')) root.classList.add('nf-boot-gate');
      root.classList.remove('nf-boot-reveal');
    };
    pin();
    window.__pinEntry = setInterval(pin, 40);
  });
  const card = page.locator('#nf-boot-gate .nf-entry-card');
  let visible = false;
  for (let i = 0; i < 60; i++) {
    if (await card.isVisible().catch(() => false)) { visible = true; break; }
    await page.waitForTimeout(50);
  }
  expect(visible, '入场卡面可见（守卫按 data-entry=on 摆回）').toBe(true);
  await page.waitForTimeout(150); // 行色 transition 0.2s 落定
  return { ctx, page, errors };
}

/** 按给定四态摆行并量：每行点的盒（x/w）+ 标签文字盒（Range 矩形，非行盒）。 */
async function measurePattern(page, states) {
  return page.evaluate((st) => {
    const rows = Array.from(document.querySelectorAll('#nf-boot-gate .nf-entry-item'));
    rows.forEach((r, i) => r.setAttribute('data-state', st[i % st.length]));
    const card = document.querySelector('#nf-boot-gate .nf-entry-card');
    const cr = card.getBoundingClientRect();
    const cardCx = cr.x + cr.width / 2;
    const textBox = (el) => {
      const rg = document.createRange();
      rg.selectNodeContents(el);
      const rects = Array.from(rg.getClientRects()).filter(r => r.width > 0);
      if (!rects.length) return null;
      const r = rects[0];
      return { x: r.x, w: r.width, cx: r.x + r.width / 2 };
    };
    return {
      cardCx,
      rows: rows.map((row, i) => {
        const dot = row.querySelector('.nf-entry-dot');
        const label = row.querySelector('.nf-entry-label');
        const dr = dot.getBoundingClientRect();
        const tb = textBox(label);
        return {
          state: row.getAttribute('data-state'),
          text: label ? label.textContent : '',
          dot: { x: dr.x, right: dr.x + dr.width, w: dr.width, h: dr.height },
          textBox: tb,
        };
      }),
    };
  }, states);
}

const TOL = 1; // px（作者令：≤1px）

for (const { locale, label } of [{ locale: 'zh-CN', label: 'zh' }, { locale: 'en-US', label: 'en' }]) {
  for (const scheme of ['light', 'dark']) {
    test(`D1-D6 入场卡四态点槽位对齐 @ ${label}/${scheme}`, async ({ browser }) => {
      const { ctx, page, errors } = await openEntryCard(browser, { colorScheme: scheme, locale });

      // 混合态 A：第 1、2 项 done，第 3、4 项 pending（验收令原样）
      const patA = await measurePattern(page, ['done', 'done', 'pending', 'pending']);
      // 混合态 B：反排（证明态切换不推槽位）
      const patB = await measurePattern(page, ['pending', 'pending', 'done', 'done']);

      // 样张先行（改前红也留证）
      const rect = await page.evaluate(() => {
        const r = document.querySelector('#nf-boot-gate .nf-entry-card').getBoundingClientRect();
        return { x: r.x + window.scrollX, y: r.y + window.scrollY, width: r.width, height: r.height };
      });
      await page.screenshot({ path: path.join(SHOTS, `entrycard-dot-slot-${label}-${scheme}.png`), clip: rect });

      const fmt = (m) => m.rows.map(r => `${r.text}:${r.state}@x=${r.dot.x.toFixed(2)}`).join(' | ');

      // D1 两混合态下：四点左缘两两差 ≤1px
      for (const [name, m] of [['A(done,done,pending,pending)', patA], ['B(pending,pending,done,done)', patB]]) {
        for (let i = 0; i < m.rows.length; i++) {
          for (let j = i + 1; j < m.rows.length; j++) {
            const d = Math.abs(m.rows[i].dot.x - m.rows[j].dot.x);
            expect(d, `[${name}] 「${m.rows[i].text}」x=${m.rows[i].dot.x.toFixed(2)} vs 「${m.rows[j].text}」x=${m.rows[j].dot.x.toFixed(2)} 两两差 ${d.toFixed(2)}px ≤1px  [${fmt(m)}]`)
              .toBeLessThanOrEqual(TOL);
          }
        }
      }
      // D2 状态不推槽位：同行两点 x 跨混合态差 ≤1px；点盒 14×14 恒定
      for (let i = 0; i < patA.rows.length; i++) {
        const d = Math.abs(patA.rows[i].dot.x - patB.rows[i].dot.x);
        expect(d, `「${patA.rows[i].text}」态切换 ${patA.rows[i].state}→${patB.rows[i].state} 点x漂移 ${d.toFixed(2)}px ≤1px`).toBeLessThanOrEqual(TOL);
        for (const m of [patA, patB]) {
          expect(m.rows[i].dot.w, `「${m.rows[i].text}」点宽 ${m.rows[i].dot.w}`).toBeCloseTo(14, 1);
          expect(m.rows[i].dot.h, `「${m.rows[i].text}」点高 ${m.rows[i].dot.h}`).toBeCloseTo(14, 1);
        }
      }
      // D3 文字居中保持（synccard-center S1 同向钉）：各行文字盒中心 ↔ 卡中线 ≤1px
      for (const [name, m] of [['A', patA], ['B', patB]]) {
        for (const row of m.rows) {
          expect(row.textBox, `${row.state} 行「${row.text}」文字盒可量`).toBeTruthy();
          const d = Math.abs(row.textBox.cx - m.cardCx);
          expect(d, `[${name}] 「${row.text}」文字中心 ${row.textBox.cx.toFixed(2)} vs 卡中线 ${m.cardCx.toFixed(2)} (Δ${d.toFixed(2)}px) ≤1px`)
            .toBeLessThanOrEqual(TOL);
        }
      }
      // D4 点不与文字相侵：点右缘 ≤ 文字盒左缘（间隙 ≥0）
      for (const m of [patA, patB]) {
        for (const row of m.rows) {
          expect(row.dot.right, `[${fmt(m)}] 「${row.text}」点右缘 ${row.dot.right.toFixed(2)} ≤ 文字左缘 ${row.textBox.x.toFixed(2)}`)
            .toBeLessThanOrEqual(row.textBox.x + 0.01);
        }
      }
      // D6 零非环境噪声异常
      expect(errors, errors.join(' | ')).toEqual([]);
      await ctx.close();
    });
  }
}
