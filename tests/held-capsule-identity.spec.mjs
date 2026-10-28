// held-capsule-identity.spec.mjs — ask-badge 批 2026-10-28「黄色问题胶囊无问题仍
// 显示计数」排查面④回归钉：顶栏双琥珀胶囊的身份可分辨。
//
// 背景（取证结论，reports/20261028_*_ask-badge-deathexit）：作者报障的「琥珀色
// 问题胶囊 badge=1」在引擎权威面无对应物——hub 全天 0 槽位（17:02-17:56 每拍
// ListAllPendingAsks=0）、PendingAskLedger 空、当日三问全收口（审计可查）。同位
// 置还有第二枚**同材质**琥珀胶囊 #held-receipts-indicator（回执待投递，fix 3 可
// 见化批 2026-10-21）：色值/点形/计数面与待答胶囊逐项相同（chat.css 并列选择器
// 共用 .pa-* ），且其计数在 17:48 实测 held=1（event=held-receipts-flushed-
// before-input）。两枚不可分辨的琥珀胶囊 = 「回执积压的 1」被认成「待答问题数」
// 的结构性误读面。
//
// 修法：回执胶囊换中性灰身份（信息性信号不占用警示色），问题胶囊独占琥珀语义。
// 本 spec 钉**计算样式**（canvas-glass-unify 同款计算样式判据），真实 CSS 链：
//   H1 色相分离：held 底色为中性灰（R≈G≈B），ask 底色为琥珀（R>G>B）——两胶囊
//      background 颜色不再相等；
//   H2 点形分离：ask 点=圆（border-radius 50%）+ 脉冲动画；held 点=方（2px）+
//      无动画；
//   H3 族面不破：同 padding / font-size / border-radius 胶囊形（同族材质仍在，
//      只换色相与点形——防「修复=改版」走样）；
//   H4 隐藏语义不变：两胶囊 .hidden 均 display:none（既有收口行为零回归）。
//
// Run: npx playwright test tests/held-capsule-identity.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS = '/tests/fixtures/held-capsule-identity/harness.html';
const SHOT_DIR = process.env.NB_HELD_CAPSULE_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261028_ask-badge-deathexit', 'held-capsule');

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
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 50) {
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
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 720, height: 320 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** 颜色串 → {rgb:[r,g,b], a}。兼容 rgba()/现代 color(srgb r g b / a)（color-mix
 *  的计算值在 Chromium 归一为 color(srgb …) 形）。 */
function parseColor(str) {
  const srgb = str.match(/color\(\s*srgb\s+([^)]+)\)/);
  if (srgb) {
    const parts = srgb[1].split(/[\s/]+/).filter(Boolean).map(Number);
    return { rgb: parts.slice(0, 3).map(v => Math.round(v * 255)), a: parts[3] ?? 1 };
  }
  const m = str.match(/rgba?\(([^)]+)\)/);
  if (!m) throw new Error(`not an rgb color: ${str}`);
  const parts = m[1].split(/[,/\s]+/).filter(Boolean).map(Number);
  return { rgb: parts.slice(0, 3), a: parts[3] ?? 1 };
}

const styleOf = (page, sel, prop) =>
  page.evaluate(([s, p]) => getComputedStyle(document.querySelector(s))[p], [sel, prop]);

test.describe('顶栏双胶囊身份可分辨（ask-badge 批 2026-10-28）', () => {
  test('H1+H2+H3 色相/点形分离、族面不破（真实 chat.css 计算样式）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // 两枚胶囊同框可见（挂上计数——hidden 摘除后无计算样式可比）。
    await page.evaluate(() => {
      for (const id of ['pending-asks-indicator', 'held-receipts-indicator']) {
        document.getElementById(id).classList.remove('hidden');
        document.getElementById(id).querySelector('.pa-count').textContent = '1';
      }
    });

    // ── H1 色相分离 ──────────────────────────────────────────────────────
    const askBg = parseColor(await styleOf(page, '#pending-asks-indicator', 'backgroundColor'));
    const heldBg = parseColor(await styleOf(page, '#held-receipts-indicator', 'backgroundColor'));
    expect(askBg.rgb, 'ask 底色应为琥珀（R>G>B 警示相）').toEqual(
      expect.arrayContaining([expect.any(Number)])
    );
    expect(askBg.rgb[0]).toBeGreaterThan(askBg.rgb[1]);
    expect(askBg.rgb[1]).toBeGreaterThan(askBg.rgb[2]);
    // held = 中性冷灰（--color-text-muted #8b8e96 系）：非暖相（B ≥ R），通道
    // 极差远小于琥珀的暖相差；判据核心 = 不占警示暖相。
    const heldSpread = Math.max(...heldBg.rgb) - Math.min(...heldBg.rgb);
    expect(heldSpread, `held 底色应为近中性灰（实测 ${heldBg.rgb}）`).toBeLessThanOrEqual(20);
    expect(heldBg.rgb[2], `held 底色不得为暖相（实测 ${heldBg.rgb}）`)
      .toBeGreaterThanOrEqual(heldBg.rgb[0]);
    expect(askBg.rgb.join(), `两胶囊底色不得相等（旧态：同为琥珀）`)
      .not.toBe(heldBg.rgb.join());

    // 前景色同律：ask 文字=琥珀相（暖）、held 文字=中性冷灰相（B ≥ R）。
    const askFg = parseColor(await styleOf(page, '#pending-asks-indicator', 'color'));
    const heldFg = parseColor(await styleOf(page, '#held-receipts-indicator', 'color'));
    expect(askFg.rgb[0]).toBeGreaterThan(askFg.rgb[2]);
    expect(heldFg.rgb[2], `held 前景不得为暖相（实测 ${heldFg.rgb}）`)
      .toBeGreaterThanOrEqual(heldFg.rgb[0]);

    // ── H2 点形分离 ──────────────────────────────────────────────────────
    const askDot = await page.evaluate(() => {
      const d = document.querySelector('#pending-asks-indicator .pa-dot');
      const cs = getComputedStyle(d);
      return { radius: cs.borderRadius, anim: cs.animationName };
    });
    const heldDot = await page.evaluate(() => {
      const d = document.querySelector('#held-receipts-indicator .pa-dot');
      const cs = getComputedStyle(d);
      return { radius: cs.borderRadius, anim: cs.animationName };
    });
    expect(askDot.radius).toBe('50%'); // 圆点（脉冲呼吸）
    expect(heldDot.radius).toBe('2px'); // 方点（收件格）
    expect(askDot.anim).toContain('bg-pulse');
    expect(heldDot.anim === 'none' || heldDot.anim === '').toBe(true);

    // ── H3 族面不破：胶囊形同款（padding / font-size / 圆角半径）────────
    for (const prop of ['padding', 'fontSize', 'borderRadius', 'borderTopWidth']) {
      const askV = await styleOf(page, '#pending-asks-indicator', prop);
      const heldV = await styleOf(page, '#held-receipts-indicator', prop);
      expect(heldV, `族面 ${prop} 应同款`).toBe(askV);
    }

    // 取证：双胶囊同框截图（亮主题）。
    await page.screenshot({ path: path.join(SHOT_DIR, 'capsules-lit.png') });
    expect(pageErrors).toEqual([]);
  });

  test('H4 隐藏语义不变：.hidden 均 display:none（收口行为零回归）', async ({ browser }) => {
    const { context, page } = await newPage(browser);
    for (const id of ['pending-asks-indicator', 'held-receipts-indicator']) {
      const display = await page.evaluate(
        (i) => getComputedStyle(document.getElementById(i)).display, id,
      );
      expect(display, `${id} 初态 hidden`).toBe('none');
    }
  });
});
