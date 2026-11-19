// perception-btn-ghost.spec.mjs — 顶栏感知入口按钮幽灵族钉（顶栏感知钮样式修
// 2026-10-28 批，作者报障 2026-10-28 09:45：感知按钮是顶栏唯一亮底填充式）。
//
// 根因：#perception-btn 由 js/perception.js wirePanelButton 动态创建，className
// 复制自 #daemon-btn = 空串 ⇒ 全站 CSS 零规则命中 ⇒ 浏览器 UA 默认亮底填充钮，
// 与同排幽灵/暗色图标族（#search-btn / #reminder-btn / #daemon-btn）风格断裂。
//
// 修法（css/perception.css ⓪ 段）：镜像同排档案逐字档（透明底 + 1px 透明边框 +
// 28×28 / radius 6 / 16px 图标 + 同 hover 玻璃）；激活态走同排 #daemon-btn.active
// 档（图标 sapphire 提亮，js 侧面板开合 ⇄ .active），亮底填充永不出现。
//
// 钉面：
//   G1 修前复现钉 —— legacy 页（?legacy=1，all:revert = 零规则命中的精确语义）
//      #perception-btn 计算背景非透明（亮底填充实证）；
//   G2 基础态一致性 —— 修复页 #perception-btn 与相邻 #search-btn / #reminder-btn /
//      #daemon-btn 的背景色/边框色/宽/高/圆角计算值逐项相等；背景全透明（亮底
//      不回归）；
//   G3 hover 态 —— 同 hover 计算背景/边框逐项相等，且 hover ≠ 基础态（可辨识）；
//   G4 激活态 —— 面板开合 ⇄ .active：图标 opacity 1（未激活 0.7）+ sapphire 色；
//      激活态背景仍全透明（不加亮底）；
//   G5 前后对照截图 —— 顶栏整排 before(legacy)/after(修) × 明/暗 + after 开面板
//      全景，落 SHOT_DIR。
//
// 驱动：tests/fixtures/perception-btn-ghost/harness.html —— #header 真结构 +
// 真 CSS 六件 + 真 perception.js + 真 lucide（createIcons 同生产脸）；fetch 全量
// mock、零真实网络。静态服务只起在 127.0.0.1 临时端口（🔴 绝非 8080 宿主），
// afterAll 全清。
//
// Run: node node_modules/@playwright/test/cli.js test tests/perception-btn-ghost.spec.mjs

import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS = '/tests/fixtures/perception-btn-ghost/harness.html';
const SHOT_DIR = process.env.PERCEPTION_BTN_SHOTS
  || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261028_perception-btn-ghost');

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
  // node 静态服务（mail-perception.spec.mjs 同款）：127.0.0.1 临时端口 GET 静态件；
  // ES module 必须正确 MIME。
  const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript',
    '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json',
  };
  const root = normalize(dir);
  const srv = http.createServer(async (req, res) => {
    try {
      let rel = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
      if (rel.endsWith('/')) rel += 'index.html';
      const abs = normalize(join(root, rel));
      if (!abs.startsWith(root)) { res.writeHead(403); res.end(); return; }
      const buf = await readFile(abs);
      res.writeHead(200, { 'Content-Type': MIME[extname(abs).toLowerCase()] || 'application/octet-stream' });
      res.end(buf);
    } catch {
      res.writeHead(404); res.end('not found');
    }
  });
  srv.listen(p, '127.0.0.1');
  return { kill: () => srv.close() };
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
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS}`);
});

test.afterAll(async () => {
  for (const s of servers) killAll(s);
  servers.length = 0;
});
function killAll(s) { try { s.kill('SIGTERM'); } catch { /* already down */ } }

/** 新页：legacy=修前复现；dark=暗档（colorScheme + data-theme 双面，与真机同）。 */
async function newPage(browser, { legacy = false, dark = false } = {}) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 1280, height: 800 },
    colorScheme: dark ? 'dark' : 'light',
  });
  const page = await context.newPage();
  if (dark) {
    await page.addInitScript(() => {
      document.addEventListener('DOMContentLoaded', () => { document.documentElement.dataset.theme = 'dark'; });
      if (document.documentElement) document.documentElement.dataset.theme = 'dark';
    });
  }
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS}${legacy ? '?legacy=1' : ''}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const NEIGHBORS = ['#search-btn', '#reminder-btn', '#daemon-btn'];

/** 单元素计算样式面（背景/边框/尺寸/圆角 + svg 的 opacity/color）。 */
function faceOf(page, sel) {
  return page.evaluate((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const cs = getComputedStyle(el);
    const svg = el.querySelector('svg');
    const sv = svg ? getComputedStyle(svg) : null;
    return {
      background: cs.backgroundColor,
      borderColor: cs.borderTopColor,
      width: cs.width,
      height: cs.height,
      radius: cs.borderTopLeftRadius,
      svgOpacity: sv ? sv.opacity : null,
      svgColor: sv ? sv.color : null,
    };
  }, sel);
}

function alphaOf(rgba) {
  const m = /rgba?\(([^)]+)\)/.exec(rgba || '');
  if (!m) return 1;
  const parts = m[1].split(',').map((x) => x.trim());
  return parts.length >= 4 ? Number(parts[3]) : 1;
}

// ============================================================
// G1 修前复现钉：legacy 页 = 亮底填充（缺陷实证）
// ============================================================
test.describe('修前复现', () => {
  test('G1 legacy 页 #perception-btn 计算背景非透明（修前亮底填充实证）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, { legacy: true });
    const btn = await faceOf(page, '#perception-btn');
    expect(btn, 'legacy 页入口按钮存在').toBeTruthy();
    expect(alphaOf(btn.background), '修前 = UA 默认亮底填充（非透明）').toBeGreaterThan(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

// ============================================================
// G2 基础态一致性：与相邻图标按钮计算样式逐项相等 + 亮底不回归
// ============================================================
test.describe('基础态幽灵族一致性', () => {
  for (const dark of [false, true]) {
    test(`G2 修复页基础态：与相邻按钮背景/边框/尺寸逐项相等（${dark ? '暗' : '亮'}档）`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, { dark });
      const btn = await faceOf(page, '#perception-btn');
      expect(btn).toBeTruthy();
      // 亮底不回归钉：基础态背景全透明
      expect(alphaOf(btn.background), '基础态背景全透明（亮底填充不回归）').toBe(0);
      for (const n of NEIGHBORS) {
        const nb = await faceOf(page, n);
        expect(nb, `${n} 存在`).toBeTruthy();
        expect(btn.background, `背景色 = ${n}（同状态）`).toBe(nb.background);
        expect(btn.borderColor, `边框色 = ${n}（同状态）`).toBe(nb.borderColor);
        expect(btn.width, `宽 = ${n}（28px 族）`).toBe(nb.width);
        expect(btn.height, `高 = ${n}（28px 族）`).toBe(nb.height);
        expect(btn.radius, `圆角 = ${n}（6px 族）`).toBe(nb.radius);
      }
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  }
});

// ============================================================
// G3 hover 态：同 hover 一致 + hover ≠ 基础态（可辨识）
// ============================================================
test.describe('hover 态', () => {
  test('G3 同 hover 计算背景/边框逐项相等；hover 相对基础态可辨识', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const rest = await faceOf(page, '#perception-btn');
    await page.hover('#perception-btn');
    await page.waitForTimeout(250); // 等 .15s 过渡落定（修前抢读会截到过渡中间值）
    const hovBtn = await faceOf(page, '#perception-btn');
    expect(alphaOf(hovBtn.background), 'hover 背景上玻璃（非透明）').toBeGreaterThan(0);
    expect(hovBtn.background, 'hover ≠ 基础态（可辨识）').not.toBe(rest.background);
    for (const n of NEIGHBORS) {
      await page.hover(n);
      await page.waitForTimeout(250);
      const hovN = await faceOf(page, n);
      expect(alphaOf(hovN.background), `${n} hover 亦上玻璃`).toBeGreaterThan(0);
      expect(hovBtn.background, `hover 背景 = ${n}（同 hover）`).toBe(hovN.background);
      expect(hovBtn.borderColor, `hover 边框 = ${n}（同 hover）`).toBe(hovN.borderColor);
    }
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

// ============================================================
// G4 激活态：面板开合 ⇄ .active（图标提亮档，零亮底）
// ============================================================
test.describe('激活态', () => {
  test('G4 面板开 → .active：svg sapphire 提亮 opacity 1；背景仍全透明；关 → 撤', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const before = await faceOf(page, '#perception-btn');
    expect(before.svgOpacity, '未激活图标 0.7（同排族档）').toBe('0.7');

    await page.click('#perception-btn');
    await expect(page.locator('#perception-panel')).toBeVisible();
    // 指针停在按钮上 = hover 玻璃合法在场（同排族同行为）；激活态本体判读先把指针移开
    await page.mouse.move(10, 400);
    await page.waitForTimeout(250);
    const open = await faceOf(page, '#perception-btn');
    const isActive = await page.evaluate(() => document.getElementById('perception-btn').classList.contains('active'));
    expect(isActive, '面板开 ⇄ .active 在').toBe(true);
    expect(open.svgOpacity, '激活图标满不透明（#daemon-btn.active 同档）').toBe('1');
    expect(open.svgColor, '激活图标 sapphire 提亮').toBe('rgb(91, 127, 191)');
    expect(alphaOf(open.background), '激活态背景仍全透明（亮底永不出现）').toBe(0);

    await page.click('#perception-btn');
    await expect(page.locator('#perception-panel')).toBeHidden();
    await page.mouse.move(10, 400);
    await page.waitForTimeout(250);
    const closed = await faceOf(page, '#perception-btn');
    const isActiveAfter = await page.evaluate(() => document.getElementById('perception-btn').classList.contains('active'));
    expect(isActiveAfter, '面板关 ⇄ .active 撤').toBe(false);
    expect(closed.svgOpacity, '关闭后图标回 0.7').toBe('0.7');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

// ============================================================
// G5 前后对照截图：顶栏整排 before/after × 明暗 + after 开面板
// ============================================================
test.describe('前后对照截图', () => {
  test('G5 顶栏整排 before(legacy)/after × 明暗 + after 开面板全景', async ({ browser }) => {
    for (const dark of [false, true]) {
      const tag = dark ? 'dark' : 'light';
      const before = await newPage(browser, { legacy: true, dark });
      await before.page.locator('#header').screenshot({ path: path.join(SHOT_DIR, `perception-btn-before-${tag}.png`) });
      await before.context.close();

      const after = await newPage(browser, { dark });
      await after.page.locator('#header').screenshot({ path: path.join(SHOT_DIR, `perception-btn-after-${tag}.png`) });
      if (!dark) {
        // 开面板全景（激活态可见面 + 面板锚定真容）
        await after.page.evaluate(() => {
          window.__routes.push({ method: 'GET', match: (p) => p === '/api/perception/entries', data: { entries: [], serverTime: 1 } });
          window.__openPanel();
        });
        await after.page.waitForTimeout(400);
        await after.page.screenshot({ path: path.join(SHOT_DIR, 'perception-btn-after-open.png') });
      }
      await after.context.close();
    }
  });
});
