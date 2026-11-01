#!/usr/bin/env node
'use strict';
/* scrollfps.cjs — 统一按钮玻璃批：长列表滚动 rAF 采样探针（2026-11-01）。
 *
 * 零端口零进程，同一打桩路线。流程：boot → 注入 N 条生产类名气泡
 * （.row.user/.bubble.user、.row.ai/.bubble.ai）→ rAF 循环程序化滚动滚动容器
 * （顶↔底往返，持续 D 毫秒）→ 采样每帧间隔 → 输出平均 fps / 掉帧数
 * （interval > 1.5×帧预算）/ p95 / max。
 * 基线用途：改玻璃前后对比同机同参数 fps（backdrop-filter 面积是滚动成本
 * 大头——清点报告给 F1 退役批作回归参考）。
 *
 * 用法：
 *   NODE_PATH=/opt/homebrew/lib/node_modules node scrollfps.cjs \
 *     [--engine chromium|webkit|both] [--theme dark|light|both] \
 *     [--viewport 1280x844|390x844] [--duration 2500] [--bubbles 120] [--out <file>]
 */

const { chromium, webkit } = require('playwright');
const { writeFileSync } = require('node:fs');
const { join } = require('node:path');
const lib = require('./lib.cjs');

const argv = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};
const ENGINE = arg('engine', 'chromium');
const THEME = arg('theme', 'both');
const VIEWPORT = arg('viewport', '1280x844');
const DURATION = Number(arg('duration', '2500'));
const BUBBLES = Number(arg('bubbles', '120'));
const OUT = arg('out', join(__dirname, '..', 'scrollfps-report.json'));

const [W, H] = VIEWPORT.split('x').map(Number);

(async () => {
  const report = [];
  for (const engineName of lib.engines(ENGINE)) {
    const engine = engineName === 'webkit' ? webkit : chromium;
    const browser = await engine.launch();
    for (const theme of lib.themes(THEME)) {
      const page = await browser.newPage({ viewport: { width: W, height: H }, colorScheme: theme });
      const errors = lib.attachErrorCollectors(page);
      await page.addInitScript(lib.INIT_SCRIPT);
      await lib.makeRouter(page);
      await page.goto('http://glassshot.local/', { waitUntil: 'domcontentloaded' });
      await lib.bootSession(page);
      await lib.applyTheme(page, theme);
      await lib.BUBBLE_HTML(page, BUBBLES);
      await page.waitForTimeout(300);
      const data = await page.evaluate(async (dur) => {
        const sc = document.getElementById('chat') || document.scrollingElement;
        if (!sc) return { error: 'no scroll container' };
        const max = () => sc.scrollHeight - sc.clientHeight;
        sc.scrollTop = max();
        await new Promise((r) => setTimeout(r, 250));
        const t0 = performance.now();
        const ivals = [];
        let last = t0;
        let dir = -1; // 先向上滚
        await new Promise((resolve) => {
          const step = () => {
            const now = performance.now();
            ivals.push(now - last);
            last = now;
            const m = max();
            sc.scrollTop += dir * 26;
            if (sc.scrollTop <= 0) dir = 1;
            if (sc.scrollTop >= m) dir = -1;
            if (now - t0 < dur) requestAnimationFrame(step);
            else resolve();
          };
          requestAnimationFrame(step);
        });
        const n = ivals.length;
        const elapsed = ivals.reduce((a, b) => a + b, 0);
        const sorted = [...ivals].sort((a, b) => a - b);
        const budget = 1000 / 60;
        return {
          frames: n,
          durationMs: Math.round(elapsed),
          avgFps: Math.round((n / (elapsed / 1000)) * 10) / 10,
          droppedFrames: ivals.filter((v) => v > budget * 1.5).length,
          p95Ms: Math.round(sorted[Math.floor(n * 0.95)] * 10) / 10,
          maxMs: Math.round(sorted[n - 1] * 10) / 10,
          scrolledHeight: Math.round(max()),
          bubbles: document.querySelectorAll('.bubble').length,
        };
      }, DURATION);
      report.push({ engine: engineName, theme, viewport: `${W}x${H}`, bubbles: BUBBLES, errors, ...data });
      console.log(`[scrollfps] ${engineName} ${theme} ${W}x${H}: avgFps=${data.avgFps} dropped=${data.droppedFrames}/${data.frames} p95=${data.p95Ms}ms max=${data.maxMs}ms`);
      await page.close();
    }
    await browser.close();
  }
  writeFileSync(OUT, JSON.stringify(report, null, 2));
  console.log(`scrollfps.cjs done → ${OUT}`);
})().catch((e) => { console.error(e); process.exit(1); });
