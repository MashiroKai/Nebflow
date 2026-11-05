// askowner-naming.spec.mjs — 归属命名批（作者令 2026-11-05 10:07）机械断言。
//
// 令面（逐字）：「现在的后台agent的问题卡片还是只会显示stand，delegate现在不都是有
// name吗，node也有name，那么可以显示 delegate name，node就是 delegate name · node
// name」。
//
// 本 spec 只钉**渲染面**（名字从载荷 `ownerName` 来，前端不猜）：
//   A1 delegate 发起的 ask ⇒ 归属文本 = 该 delegate 的 name（DOM 文本断言）
//   A2 node 发起的 ask     ⇒ 文本 = `<delegate name> · <node name>`
//   A3 name 缺失注入       ⇒ 回落项目名；无 `undefined` / 空白
//   A4 超长名 375/1440 两档都不溢出（scrollWidth ≤ clientWidth）+ 完整名可获取
//   A5 两段主次（段①字重 < 段②字重），窄屏折行不丢段
//   A6 亮暗双档截图（1440 + 375）
//
// Drives the REAL render modules through tests/fixtures/askowner-badge/harness.html
// over a throwaway static server（🔴 绝非 6138 宿主；零后端、零装配实例）。
//
// Run: npx playwright test tests/askowner-naming.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
// 验红入口（qa 章 verification-rigor）：`NB_ASKOWNER_ROOT` 指向一份**改前代码**的
// 静态根（web/ + tests/fixtures/askowner-badge 按 HEAD 版本铺开）——同一 spec 跑
// 改前必红、跑改动后必绿，红绿两读都是机械证据，不靠目测。
const SERVE_ROOT = process.env.NB_ASKOWNER_ROOT || REPO_ROOT;
const HARNESS_PATH = '/tests/fixtures/askowner-badge/harness.html';
const SHOT_DIR = process.env.NB_ASKOWNER_SHOT_DIR
  || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261105_askowner-naming', 'shots');

let port;
let server;

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

async function waitUntilUp(url, tries = 80) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

// 长名样本：作者常写长中文批名（40+ 字），故意超过两个阈值（段①14em / 段②18em）。
const LONG_OWNER = '归属命名批·问题卡片要显示delegate的名字而不是只显示项目名';
const LONG_NODE = '改ask卡片归属标签的两段式渲染与长名截断窄屏折行';
const OWNER = 'ask-card-naming';
const NODE = '改归属两段';

test.beforeAll(async () => {
  port = await freePort();
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1',
    '--directory', SERVE_ROOT], { stdio: 'ignore' });
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  fs.mkdirSync(SHOT_DIR, { recursive: true });
});

test.afterAll(async () => {
  if (server) server.kill();
});

test.beforeEach(async ({ page }) => {
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction('window.__ready === true');
});

/** 读归属胶囊的机械读数（文本 / 溢出 / 完整名 / 两段可见性 / 字重）。 */
async function badge(page) {
  return page.evaluate(() => {
    const el = document.querySelector('.ask-user-source');
    if (!el) return { present: false };
    const parent = el.querySelector('.ask-user-source-parent');
    const node = el.querySelector('.ask-user-source-node');
    const ow = (e) => (e ? Math.round(e.getBoundingClientRect().width) : 0);
    return {
      present: true,
      text: el.textContent.replace(/\s+/g, ' ').trim(),
      title: el.title || '',
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      parentText: parent ? parent.textContent : '',
      nodeText: node ? node.textContent : '',
      parentScroll: parent ? parent.scrollWidth : 0,
      parentClient: parent ? parent.clientWidth : 0,
      nodeScroll: node ? node.scrollWidth : 0,
      nodeClient: node ? node.clientWidth : 0,
      parentWeight: parent ? Number(getComputedStyle(parent).fontWeight) : 0,
      nodeWeight: node ? Number(getComputedStyle(node).fontWeight) : 0,
      parentW: ow(parent),
      nodeW: ow(node),
      // 卡面（.option-box）也不许被归属胶囊撑破
      cardScroll: (() => { const c = el.closest('.option-box'); return c ? c.scrollWidth : 0; })(),
      cardClient: (() => { const c = el.closest('.option-box'); return c ? c.clientWidth : 0; })(),
    };
  });
}

test.describe('归属命名 · 桌面 1440', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  test('A1 delegate 发起的 ask ⇒ 归属 = delegate name', async ({ page }) => {
    await page.evaluate(([owner]) => window.__render({ source: { ownerName: owner }, agentName: 'general' }), [OWNER]);
    const b = await badge(page);
    expect(b.present).toBe(true);
    expect(b.text).toBe(OWNER);
    expect(b.nodeText).toBe('');           // delegate 自问无节点段
    expect(b.title).toBe(OWNER);
    expect(b.text).not.toContain('undefined');
  });

  test('A2 node 发起的 ask ⇒ 归属 = `delegate name · node name`', async ({ page }) => {
    await page.evaluate(([owner, node]) => window.__render({ source: { ownerName: owner, nodeName: node }, agentName: 'general' }), [OWNER, NODE]);
    const b = await badge(page);
    expect(b.present).toBe(true);
    expect(b.text).toBe(`${OWNER} · ${NODE}`);
    expect(b.parentText).toBe(OWNER);
    expect(b.nodeText).toBe(NODE);
    expect(b.title).toBe(`${OWNER} · ${NODE}`);
  });

  test('A5 两段有主次：段①（父批）字重 < 段②（当前节点）', async ({ page }) => {
    await page.evaluate(([owner, node]) => window.__render({ source: { ownerName: owner, nodeName: node } }), [OWNER, NODE]);
    const b = await badge(page);
    expect(b.parentWeight).toBeLessThan(b.nodeWeight);
  });

  test('A3 name 缺失注入 ⇒ 回落项目名，无 undefined / 空白', async ({ page }) => {
    // ① 无 ownerName 有 project + node ⇒ 现状口径不变
    await page.evaluate(() => window.__render({ source: { project: 'stand', nodeName: '无主节点' } }));
    let b = await badge(page);
    expect(b.text).toBe('stand · 无主节点');
    // ② 无 ownerName 有 project、无 node ⇒ 第二段回落问者显示名
    await page.evaluate(() => window.__render({ source: { project: 'stand' }, agentName: 'general' }));
    b = await badge(page);
    expect(b.text.startsWith('stand · ')).toBe(true);
    expect(b.text).not.toContain('undefined');
    expect(b.text.replace(/\s/g, '')).not.toBe('stand·');
    // ③ ownerName = 空串（旧记录/异常注入）⇒ 视同缺失，不渲染空段
    await page.evaluate(() => window.__render({ source: { ownerName: '   ', project: 'stand', nodeName: 'n1' } }));
    b = await badge(page);
    expect(b.text).toBe('stand · n1');
    // ④ 三者全缺且问者无名 ⇒ 不挂归属胶囊（绝不渲染 undefined / 空白胶囊）
    await page.evaluate(() => window.__render({ source: {}, agentName: '' }));
    b = await badge(page);
    expect(b.present).toBe(false);
    const bodyText = await page.evaluate(() => document.getElementById('chat').textContent);
    expect(bodyText).not.toContain('undefined');
  });

  test('A4a 超长名 1440 不溢出且完整名可获取', async ({ page }) => {
    await page.evaluate(([o, n]) => window.__render({ source: { ownerName: o, nodeName: n } }), [LONG_OWNER, LONG_NODE]);
    const b = await badge(page);
    expect(b.scrollWidth).toBeLessThanOrEqual(b.clientWidth);
    expect(b.cardScroll).toBeLessThanOrEqual(b.cardClient);
    expect(b.title).toBe(`${LONG_OWNER} · ${LONG_NODE}`);
    // 段被截断（ellipsis）但两段都还在且宽度大于「只剩两个字」
    expect(b.parentW).toBeGreaterThan(40);
    expect(b.nodeW).toBeGreaterThan(40);
  });

  test('A6a 亮暗双档截图 1440', async ({ page }) => {
    for (const scheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.evaluate(([o, n]) => {
        window.__mobile(false);
        window.__render({ source: { ownerName: o, nodeName: n } });
      }, [LONG_OWNER, LONG_NODE]);
      await page.waitForTimeout(120);
      await page.screenshot({ path: path.join(SHOT_DIR, `owner-badge-1440-${scheme}.png`), fullPage: false });
    }
    await page.emulateMedia({ colorScheme: 'light' });
  });
});

test.describe('归属命名 · 手机 375', () => {
  test.use({ viewport: { width: 375, height: 812 }, colorScheme: 'light', isMobile: true, hasTouch: true, deviceScaleFactor: 2 });

  test('A4b 超长名 375 不溢出、折行不丢段、完整名可获取', async ({ page }) => {
    await page.evaluate(([o, n]) => {
      window.__mobile(true);
      window.__render({ source: { ownerName: o, nodeName: n } });
    }, [LONG_OWNER, LONG_NODE]);
    const b = await badge(page);
    expect(b.scrollWidth).toBeLessThanOrEqual(b.clientWidth);
    expect(b.cardScroll).toBeLessThanOrEqual(b.cardClient);
    expect(b.title).toBe(`${LONG_OWNER} · ${LONG_NODE}`);
    // 不丢段：两段都有可见宽度与文本
    expect(b.parentW).toBeGreaterThan(0);
    expect(b.nodeW).toBeGreaterThan(0);
    expect(b.parentText.length).toBeGreaterThan(0);
    expect(b.nodeText.length).toBeGreaterThan(0);
  });

  test('A5b 触屏点按展开：撤截断后仍是同一全名', async ({ page }) => {
    await page.evaluate(([o, n]) => {
      window.__mobile(true);
      window.__render({ source: { ownerName: o, nodeName: n } });
    }, [LONG_OWNER, LONG_NODE]);
    await page.locator('.ask-user-source').first().tap();
    const expanded = await page.evaluate(() => {
      const el = document.querySelector('.ask-user-source');
      return {
        cls: el.className,
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        text: el.textContent.replace(/\s+/g, ' ').trim(),
      };
    });
    expect(expanded.cls).toContain('is-expanded');
    expect(expanded.text).toBe(`${LONG_OWNER} · ${LONG_NODE}`);
    expect(expanded.scrollWidth).toBeLessThanOrEqual(expanded.clientWidth);
  });

  test('A6b 亮暗双档截图 375', async ({ page }) => {
    for (const scheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.evaluate(([o, n]) => {
        window.__mobile(true);
        window.__render({ source: { ownerName: o, nodeName: n } });
      }, [LONG_OWNER, LONG_NODE]);
      await page.waitForTimeout(120);
      await page.screenshot({ path: path.join(SHOT_DIR, `owner-badge-375-${scheme}.png`), fullPage: false });
    }
    await page.emulateMedia({ colorScheme: 'light' });
  });
});
