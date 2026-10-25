// askuser-badge-mirror.spec.mjs — 修复批 2026-10-25「顶部黄色待答徽章常显」回归钉。
//
// 作者报障（2026-10-25 13:0x）：顶栏琥珀胶囊徽章（#pending-asks-indicator，圆点+
// 数字）在没有任何待回答问题时常显「1」。实证（线上 hub 日志 + 台账）：引擎全天
// 0 槽位、PendingAskLedger 空 ⇒ 纯前端镜像僵尸。缺口三件套（本批全部收口）：
//   ① hub complete() 不广播关卡帧 —— 没亲手作答的标签页收不到「已解决」事件
//     （引擎侧修复：InteractionHub.complete 补 askUserClosed{reason:"answered"}，
//      本 spec 钉前端消费面；引擎侧行为由 sbt 面spec 覆盖）；
//   ② 重放帧刷新的镜像条目被 race 闸豁免 —— 对账摘不掉僵尸（askPending.toEntry
//      .live 钉，B5 面本 spec 直钉）；
//   ③ 镜像对账只挂在 WS 重连 —— 长连标签页永不自愈（requestPendingAskSync 单点
//      + 可见期节拍/补账，D 面钉请求形状与 since 闸）。
//
// 语义钉（任务书最小集 + 两条不回归钉）：
//   A1 无待答不显示徽章（初态 + 空快照对账后）
//   A2 有 ask 显示且数字正确（1 → 2，待办条同镜像）
//   A3 ask 解决后消失（askUserClosed 关卡腿）
//   A4 权威对账摘僵尸（重启恢复形态：旧 live 条目不在快照 ⇒ 摘除）
//   B5 重放帧不再豁免对账（本批缺陷的核心形态钉——改前此形常驻）
//   B6 live 帧竞态豁免保留（#250 ③ 原保护不回归）
//
// Drives the REAL mirror module (askPending.js) through
// tests/fixtures/askuser-badge-mirror/harness.html over a throwaway static
// server（🔴 绝非 8080 宿主）。
//
// Run: npx playwright test tests/askuser-badge-mirror.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS = '/tests/fixtures/askuser-badge-mirror/harness.html';
const SHOT_DIR = process.env.NB_BADGE_MIRROR_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261025_ask-badge-fix', 'mirror-spec');

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
    viewport: { width: 900, height: 700 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const ask = (rid, extra = {}) => ({
  requestId: rid,
  sessionId: 'harness-session',
  agentName: 'Nebula',
  items: [{ question: `问题 ${rid}?`, options: [{ label: '甲' }, { label: '乙' }] }],
  ...extra,
});

test.describe('待答徽章镜像语义（修复批 2026-10-25）', () => {
  test('A1+A2 无待答不显示；有 ask 显示且数字正确（1→2，待办条同镜像）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // A1 初态：模块镜像空 ⇒ 徽章隐藏（index.html 初值 hidden 不得被渲染面翻开）。
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });

    // A1 空快照对账（重启后权威=空）：镜像保持空 ⇒ 仍隐藏。
    await page.evaluate(() => window.__snapshot([]));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });

    // A2 一条待答（live 首发帧）：徽章出现、计数 1；待办条同镜像 1 行。
    await page.evaluate((m) => window.__note(m), ask('asknb-badge-1'));
    let badge = await page.evaluate(() => window.__badge());
    expect(badge).toEqual({ visible: true, count: '1' });
    expect(await page.evaluate(() => window.__barRows())).toBe(1);

    // A2 第二条并发待答：计数 2（多 Ask 并发批 #250 口径不回归）。
    await page.evaluate((m) => window.__note(m), ask('asknb-badge-2'));
    badge = await page.evaluate(() => window.__badge());
    expect(badge).toEqual({ visible: true, count: '2' });
    expect(await page.evaluate(() => window.__barRows())).toBe(2);

    const shot = path.join(SHOT_DIR, 'a2-badge-count-two.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('A3 ask 解决后消失（askUserClosed 关卡腿，逐条收口）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-close-1'));
    await page.evaluate((m) => window.__note(m), ask('asknb-close-2'));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '2' });

    // 收口第一条（hub 关卡帧 / 卡上作答同走 removePendingAsk 单点）：计数降 1。
    await page.evaluate((rid) => window.__remove(rid), 'asknb-close-1');
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '1' });

    // 收口最后一条：归零即隐（「常显」缺陷的反面钉）。
    await page.evaluate((rid) => window.__remove(rid), 'asknb-close-2');
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });
    expect(await page.evaluate(() => window.__barRows())).toBe(0);

    const shot = path.join(SHOT_DIR, 'a3-badge-cleared.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('A4 权威对账摘僵尸（重启恢复形态：旧 live 条目不在快照 ⇒ 摘除归隐）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 镜像里有一条旧待答（重启前入场）。
    await page.evaluate((m) => window.__note(m), ask('asknb-stale'));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '1' });

    // 重启后权威快照=空、`since` 晚于条目入场（对账请求在先）⇒ 摘除、徽章归隐。
    await new Promise(r => setTimeout(r, 15)); // 保证 entry.ts < since（时间戳有分辨率）
    await page.evaluate(() => window.__snapshot([], Date.now()));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });
    expect(await page.evaluate(() => window.__count())).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('B5 重放帧不再豁免对账（核心缺陷形态钉：重放刷新的僵尸必须被摘）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 对账请求先发出（盖 since 戳）——其后到达的**重放帧**（hub 订阅重放腿，
    // replayed/replaces 标）把镜像条目 ts 刷成「晚于查询」。改前 race 闸只看 ts
    // ⇒ 该僵尸在权威快照缺席时仍被豁免保留 ⇒ 徽章常显（作者报障形态）。
    await page.evaluate(() => window.__sync());
    const sent = await page.evaluate(() => window.__captured.map(f => f.type));
    expect(sent).toContain('getPendingAsks'); // D 面前置：同步请求形状（单点发出）
    await page.evaluate((m) => window.__note(m), ask('asknb-replay-zombie', { replayed: true, replaces: true }));

    // 快照先于重放帧构建（槽位在两读之间已收口、关卡帧在途丢失）⇒ 权威说没有
    // ⇒ 非活帧不豁免：摘除、归隐。
    await page.evaluate(() => window.__snapshot([]));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });
    expect(await page.evaluate(() => window.__count())).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('B6 live 帧竞态豁免保留（#250 ③ 原保护不回归：查后到达的活帧不被对账误杀）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__sync());
    // 活帧（live 首发竞态：hub 建快照时还没见过它）在 since 之后到达。
    await page.evaluate((m) => window.__note(m), ask('asknb-live-race'));

    // 权威快照（该帧不在其中）不得把它摘掉——否则正是 #250 ③ 要堵的「静默丢待办」。
    await page.evaluate(() => window.__snapshot([]));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '1' });

    // 后续对账（该帧早已在 hub 落账、或已收口）照常收口：带 since=现在 ⇒ 摘除。
    await new Promise(r => setTimeout(r, 15));
    await page.evaluate(() => window.__snapshot([], Date.now()));
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
