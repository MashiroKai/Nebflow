// shoot-merge-fixture.mjs — 合并管线视图 · 兜底夹具截图（明标）。
//
// 背景：真机 ：8080 是旧构建——G1 `merging` 覆写与 terminal-verdict 判词行都在
// 本批新引擎代码里（未部署），waiting-merge 期的行 taskStatus 恒 'running'，
// 真实「候合并/合并中」状态在旧引擎面上不可达（shoot.mjs merge 实测超时）。
// 按批计划走兜底：stub 夹具注入（harness 缝）合成合并管线带——**带名明标
// 「⚠️夹具演示」**，其余项目带仍为真机真实数据同框，方便与真实带对照读图。
// DOM 级三重编码断言已由 tests/delegate-panorama.spec.mjs T1 钉死（非夹具依赖）。

import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAIN = '/Users/kaiyu/Claude code/Stand';
const require = createRequire(join(MAIN, 'package.json'));
const { chromium } = require('playwright');

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = HERE;
const BASE = process.env.PANO_BASE || 'http://127.0.0.1:8123';
const TOKEN = JSON.parse(readFileSync('/Users/kaiyu/.stand/auth.json', 'utf8'));
const T0 = Date.now();

async function mintTicket() {
  const r = await fetch(`${BASE}/api/auth/console-ticket`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}` },
  });
  if (!r.ok) throw new Error(`console-ticket HTTP ${r.status}`);
  const b = await r.json();
  return b.ticket;
}

/** 合并管线夹具（真实 record 形加宽）：#M1 候合并（队首 #1/2）＋ #M2 合并中（持窗）。 */
function mergeFixture() {
  const row = (over) => ({
    taskNo: '', delegateId: '', title: 'T', executor: 'nebflow', status: 'running',
    external: false, project: '⚠️夹具演示 merge-pipeline', orchestrationMode: 'single',
    archivedAt: null, createdAt: T0, taskStatus: '', branch: '', baseBranch: '',
    worktreePath: '/wt/demo', projectWorkspace: '/ws/demo', reportStatus: '', reportDetail: '',
    verdict: null, mergeQueuePos: null, topology: null,
    ...over,
  });
  return {
    rows: [
      row({
        taskNo: '81', delegateId: 'delegate-demo-rtm-81', title: '候合并卡 · 队首（琥珀 FIFO）',
        status: 'ready-to-merge', project: '⚠️夹具演示 merge-pipeline', createdAt: T0 - 300_000,
        branch: 'delegate/task-81', baseBranch: 'main',
        verdict: { verdict: 'ready-to-merge', reason: 'branch ready', source: 'judge', at: T0 - 60_000 },
        mergeQueuePos: { position: 1, total: 2, branch: 'delegate/task-81', baseBranch: 'main', readyAt: T0 - 60_000 },
      }),
      row({
        taskNo: '82', delegateId: 'delegate-demo-mrg-82', title: '合并中卡 · 持窗（蓝晶行军蚁）',
        status: 'completed', taskStatus: 'merging', project: '⚠️夹具演示 merge-pipeline', createdAt: T0 - 600_000,
        branch: 'delegate/task-82', baseBranch: 'main',
      }),
    ],
    chains: [],
    children: [],
  };
}

const browser = await chromium.launch();
try {
  for (const scheme of ['dark', 'light']) {
    const context = await browser.newContext({
      colorScheme: scheme,
      viewport: { width: 1720, height: 1100 },
      deviceScaleFactor: 2,
    });
    const ticket = await mintTicket();
    const page = await context.newPage();
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'load' });
    await page.waitForFunction(async () => {
      const s = (await import('/js/state.js')).default;
      return s.ws && s.ws.readyState === 1;
    }, undefined, { timeout: 30_000 });
    await page.waitForFunction(async () => {
      const st = await import('/js/delegateChainStub.js');
      return st.listDelegateRows().length > 0;
    }, undefined, { timeout: 30_000 });
    // 注入合并管线夹具：真机镜像行**原样保留**（真实带同框对照）＋ 明标演示带
    // 追加在行集头部（harness 缝；事件广播 → 全景重渲）。
    await page.evaluate(async (fx) => {
      const stub = await import('/js/delegateChainStub.js');
      const real = stub.listDelegateRows();
      stub.__setFixture({ rows: [...fx.rows, ...real], chains: [], children: [] });
      window.dispatchEvent(new Event('delegate-chain-mirror-updated'));
    }, mergeFixture());
    await page.evaluate(async () => {
      const m = await import('/js/delegatePanoramaView.js');
      m.openDelegatePanorama(''); // 全量全景：真实项目带与明标夹具带同框对照
    });
    await page.waitForFunction(() => {
      const bands = [...document.querySelectorAll('.canvas-tab-pane[data-tab-id="projects"] .dvp-band')];
      return bands.some((b) => !b.hidden && b.querySelector('.dvp-canvas') && b.getBoundingClientRect().height > 40);
    }, undefined, { timeout: 20_000 });
    // 三重编码在场判据：琥珀队列徽标 + 合并窗徽标。
    await page.waitForSelector('.dvp-qbadge.rtm, .dvp-qbadge.mrg', { timeout: 10_000 });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(700);
    // 占满视口（同 shoot.mjs fitViewport）。
    await page.addStyleTag({
      content: '.deck-look-v2, .deck-look-v2 *{transform:none!important;filter:none!important;backdrop-filter:none!important;perspective:none!important;will-change:auto!important;contain:none!important;content-visibility:visible!important;}',
    });
    await page.addStyleTag({
      content: '.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;max-width:none!important;z-index:9999!important;background:var(--color-bg,#101418);}',
    });
    const h = await page.evaluate(() => {
      const body = document.querySelector('.delegate-chain-view-body');
      return Math.min(body.scrollHeight + (window.innerHeight - body.clientHeight) + 24, 4600);
    });
    await page.setViewportSize({ width: 1720, height: h });
    await page.waitForTimeout(400);
    await page.locator('.delegate-chain-view-body').first().screenshot({
      path: join(OUT, `pano-merge-${scheme}.png`), animations: 'disabled',
    });
    console.log(`saved pano-merge-${scheme}.png`);
    await context.close();
  }
} finally {
  await browser.close();
}
console.log('done');
