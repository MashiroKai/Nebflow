// toolline-perf-recording.spec.mjs — 工具执行行增强批①（spinner 卡顿复核）的
// 实测录制（作者 2026-10-06 16:51 令①：性能录制——运行中工具行的 spinner
// 帧率 / 主线程 stall / 合成层；分发器预核：流畅度修已随 546cf831c 落刀）。
//
// 读数四件：
//   1. rAF 帧距（2s 窗口）：spinner 空转 vs spinner+细节行流式（60Hz 喂入）
//      ——p95 帧距与最大帧距（主线程 stall 读数）。
//   2. longtask 计数（>50ms 主线程任务，PerformanceObserver）。
//   3. CDP Performance.getMetrics 增量：LayoutCount / RecalcStyleCount /
//      TaskDuration —— 细节行流式是否引入每帧布局/样式抖动。
//   4. 动画面普查：spinner 的 getAnimations 形态（nf-wl-frames 纯文本 steps，
//      opacity/content 离散步进——transform-only 纪律的现态确认）。
//
// 判定线（处方口径）：p95 帧距 < 32ms（≥~30fps）、无 >100ms 帧、流式窗口
// longtask = 0。顺 = 如实报「已落+读数」收项，不再动 CSS。
//
// Run: npx playwright test tests/toolline-perf-recording.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/toolline-detail/harness.html';
const SHOT_DIR = process.env.NB_KEEP_TEXT_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261006_toolline-detail-x1', 'perf');

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
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** 页内采样器：采 durationMs 的 rAF 帧距 + longtask。返回统计读数。 */
async function sampleFrames(page, durationMs) {
  return page.evaluate(async (ms) => {
    const deltas = [];
    let last = performance.now();
    let raf = 0;
    const stopAt = last + ms;
    const longTasks = [];
    let po = null;
    try {
      po = new PerformanceObserver((list) => {
        for (const e of list.getEntries()) longTasks.push(e.duration);
      });
      po.observe({ entryTypes: ['longtask'] });
    } catch { /* longtask unsupported — read as [] */ }
    await new Promise((resolve) => {
      const tick = (t) => {
        deltas.push(t - last);
        last = t;
        if (t >= stopAt) { resolve(); return; }
        raf = requestAnimationFrame(tick);
      };
      raf = requestAnimationFrame(tick);
    });
    cancelAnimationFrame(raf);
    if (po) po.disconnect();
    const d = deltas.slice(1); // 首帧距含采样器启动偏移，弃
    d.sort((a, b) => a - b);
    const p = (q) => d.length ? Math.round(d[Math.min(d.length - 1, Math.floor(d.length * q))]) : 0;
    return {
      frames: d.length,
      avgMs: d.length ? Math.round(d.reduce((a, b) => a + b, 0) / d.length) : 0,
      p50Ms: p(0.5),
      p95Ms: p(0.95),
      maxMs: d.length ? Math.round(d[d.length - 1]) : 0,
      over32ms: d.filter(x => x > 32).length,
      over100ms: d.filter(x => x > 100).length,
      longTasks,
    };
  }, durationMs);
}

async function perfMetrics(page) {
  const session = await page.context().newCDPSession(page);
  await session.send('Performance.enable');
  const { metrics } = await session.send('Performance.getMetrics');
  const get = (n) => metrics.find(m => m.name === n)?.value ?? 0;
  return { session, read: () => ({
    LayoutCount: get('LayoutCount'),
    RecalcStyleCount: get('RecalcStyleCount'),
    TaskDurationMs: Math.round(get('TaskDuration') * 1000),
    JSHeapUsedMB: Math.round(get('JSHeapUsedSize') / 1048576 * 10) / 10,
  }) };
}

test.describe('① spinner smoothness — measured readings (546cf831c 落刀复核)', () => {
  test('spinner idle window: frame pacing, longtask, animation census (composite-relevant)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__tdUser('spinner 读数'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"sleep 120"}'));
    await page.evaluate(() => window.__tdExecStart('Bash\n  (sleep 120)'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));

    // 动画面普查：spinner 帧本体动画 = nf-wl-frames（纯文本 steps）。
    // 动画挂在 ::before 伪元素上（UI-C 纯文本 spinner）——普查走
    // document.getAnimations() 按 pseudoElement 过滤 + ::before 计算样式。
    const animCensus = await page.evaluate(() => {
      const spin = document.querySelector('#chat .nf-workline .nf-wl-spin');
      const anims = document.getAnimations().filter(a =>
        (a.effect?.target === spin && (a.effect?.pseudoElement || '').includes('before')) ||
        a.effect?.target === spin);
      const beforeCs = spin ? getComputedStyle(spin, '::before') : null;
      return {
        count: anims.length,
        names: anims.map(a => a.animationName || 'css'),
        pseudoElement: anims.map(a => a.effect?.pseudoElement || 'self'),
        cs: beforeCs ? (({ animationName, animationDuration, animationTimingFunction }) =>
          ({ animationName, animationDuration, animationTimingFunction }))(beforeCs) : null,
        text: beforeCs ? beforeCs.content : null,
      };
    });
    expect(animCensus.count).toBeGreaterThan(0);
    expect(animCensus.cs.animationName).toBe('nf-wl-frames');
    // 纯文本 steps 轮换（UI-C）：无 transform/rotate 动画残留在 spinner 上。
    expect(animCensus.cs.animationTimingFunction).toContain('steps');

    const metrics = await perfMetrics(page);
    const before = metrics.read();
    const frames = await sampleFrames(page, 2000);
    const after = metrics.read();

    // 判定线：p95 < 32ms；无 >100ms 帧（stall=0）；longtask = 0。
    expect(frames.p95Ms).toBeLessThan(32);
    expect(frames.over100ms).toBe(0);
    expect(frames.longTasks.length).toBe(0);

    const readings = { animCensus, frames, metricsDelta: {
      LayoutCount: after.LayoutCount - before.LayoutCount,
      RecalcStyleCount: after.RecalcStyleCount - before.RecalcStyleCount,
      TaskDurationMs: after.TaskDurationMs - before.TaskDurationMs,
    } };
    console.log('[spinner-idle]', JSON.stringify(readings, null, 2));

    const shot = path.join(SHOT_DIR, 'perf-spinner-idle.png');
    await page.screenshot({ path: shot, fullPage: false });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await metrics.session.detach();
    await context.close();
  });

  test('spinner + 60Hz detail streaming: frame budget holds (textContent-only updates)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__tdUser('流式帧预算'));
    await page.evaluate(() => window.__tdCallStart('Bash'));
    await page.evaluate(() => window.__tdArg('Bash', '{"command":"./gradlew buildWeb runAllChecks --parallel'));
    await page.evaluate(() => window.__tdExecStart('Bash\n  (./gradlew buildWeb)'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));

    const metrics = await perfMetrics(page);
    const before = metrics.read();

    // 流式窗口：采样与喂入同窗（每帧一条增量 —— 真实 toolArgDelta 节奏）。
    const readings = await page.evaluate(async (ms) => {
      const deltas = [];
      let last = performance.now();
      const stopAt = last + ms;
      const longTasks = [];
      let po = null;
      try {
        po = new PerformanceObserver((l) => { for (const e of l.getEntries()) longTasks.push(e.duration); });
        po.observe({ entryTypes: ['longtask'] });
      } catch { /* unsupported */ }
      let i = 0;
      await new Promise((resolve) => {
        const tick = (t) => {
          deltas.push(t - last);
          last = t;
          // 每帧喂一条参数增量（长命令反复追加尾段）。
          window.__tdArg('Bash', ' -x --continue');
          if (t >= stopAt) { resolve(); return; }
          requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      });
      if (po) po.disconnect();
      const d = deltas.slice(1);
      d.sort((a, b) => a - b);
      const p = (q) => d.length ? Math.round(d[Math.min(d.length - 1, Math.floor(d.length * q))]) : 0;
      return {
        frames: d.length,
        avgMs: d.length ? Math.round(d.reduce((a, b) => a + b, 0) / d.length) : 0,
        p95Ms: p(0.95),
        maxMs: d.length ? Math.round(d[d.length - 1]) : 0,
        over32ms: d.filter(x => x > 32).length,
        longTasks,
      };
    }, 2000);
    const after = metrics.read();

    expect(readings.p95Ms).toBeLessThan(32);
    expect(readings.longTasks.length).toBe(0);
    // 细节行是 textContent 替换：不引入布局风暴（每帧 ≤1 次 reflow 属采样噪声内）。
    const layoutDelta = after.LayoutCount - before.LayoutCount;
    expect(layoutDelta).toBeLessThan(readings.frames / 4);

    const detail = await page.evaluate(() => window.__tdCensus());
    expect(detail.detailPresent).toBe(true);
    expect(detail.detailText.length).toBeGreaterThan(0);

    console.log('[spinner+stream]', JSON.stringify({
      readings,
      metricsDelta: {
        LayoutCount: layoutDelta,
        RecalcStyleCount: after.RecalcStyleCount - before.RecalcStyleCount,
        TaskDurationMs: after.TaskDurationMs - before.TaskDurationMs,
      },
    }, null, 2));

    const shot = path.join(SHOT_DIR, 'perf-spinner-streaming.png');
    await page.screenshot({ path: shot, fullPage: false });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await metrics.session.detach();
    await context.close();
  });
});
