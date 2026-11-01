#!/usr/bin/env node
'use strict';
/* shot.cjs — 统一按钮玻璃批：自包含截图 harness（2026-11-01）。
 *
 * 零端口零进程：page.route 从磁盘服务真实前端文件 + MockWebSocket 注入真实
 * ws.js 分发路径（boot 走生产链）；主题 = html[data-theme] 显式档 +
 * page.emulateMedia 跟随档双落位（stand 主题机制：data-theme 优先、
 * prefers-color-scheme 兜底，见 css/ask.css:52-60 三态结构）。
 *
 * 用法（NODE_PATH 解析全局 playwright，同 scripts/shot-header-name.cjs）：
 *   NODE_PATH=/opt/homebrew/lib/node_modules node shot.cjs \
 *     [--target chat|canvas|mobile|dialog|all] [--viewport 1280x844|390x844] \
 *     [--engine chromium|webkit|both] [--theme dark|light|both] [--out <dir>]
 *
 * 产出：<out>/<target>-<engine>-<theme>.png + <out>/shot-report.json
 * （逐张 console error / pageerror 收录；任一张有前端报错标 ok:false，
 *  但不中断其余组合——侦察批要求全矩阵证据）。
 */

const { chromium, webkit } = require('playwright');
const { mkdirSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const lib = require('./lib.cjs');

const argv = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};
const TARGET = arg('target', 'chat');
const VIEWPORT = arg('viewport', '');
const ENGINE = arg('engine', 'chromium');
const THEME = arg('theme', 'both');
const OUT = arg('out', join(__dirname, '..', 'shots'));

const DEFAULT_VP = { chat: [1280, 844], canvas: [1280, 844], dialog: [1280, 844], mobile: [390, 844] };

/** 目标准备：boot 后把该落点摆到取证形态。 */
async function prepare(page, target) {
  if (target === 'canvas') {
    await page.evaluate(() => import('/js/canvas.js').then((m) => m.openCanvas('glass-shot-doc.md')));
    await page.waitForTimeout(700); // 面板开合过渡 + deck-fab 显隐同步
  } else if (target === 'mobile') {
    // 390 宽视口 → js/mobile/bootstrap.js 的 max-width:768px MQ 自动挂 body.mobile-layer；
    // dock chrome 钮 / g2-chat-head / 顶缘渐隐带随激活链就位。
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });
    await page.waitForTimeout(900);
  } else if (target === 'dialog') {
    // 设置面板开启路径：优先生产 API（sidebar.js openSettingsPanel），失败则退化
    // 直接摘 #settings-modal 的 hidden（CSS 取证只需真实样式级联在场）。
    const opened = await page.evaluate(async () => {
      try {
        const m = await import('/js/sidebar.js');
        if (typeof m.openSettingsPanel === 'function') { m.openSettingsPanel(); return 'api'; }
      } catch { /* fallthrough */ }
      const el = document.getElementById('settings-modal');
      if (el) { el.removeAttribute('hidden'); return 'dom'; }
      return 'none';
    });
    await page.waitForTimeout(500);
    if (opened === 'none') console.warn('[shot] settings modal 未开启（两路径均失败）');
  }
  // chat / 其余目标统一注入气泡 fixture（#chat 不在场的 mobile 形态自动跳过）
  await lib.BUBBLE_HTML(page, 10);
  await page.waitForTimeout(250);
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const [w, h] = VIEWPORT ? VIEWPORT.split('x').map(Number) : DEFAULT_VP[TARGET] || [1280, 844];
  if (!w || !h) { console.error(`bad --viewport ${VIEWPORT}`); process.exit(2); }
  const report = [];
  let launched = false;

  for (const engineName of lib.engines(ENGINE)) {
    const engine = engineName === 'webkit' ? webkit : chromium;
    const browser = await engine.launch();
    launched = true;
    for (const theme of lib.themes(THEME)) {
      const page = await browser.newPage({ viewport: { width: w, height: h }, colorScheme: theme });
      const errors = lib.attachErrorCollectors(page);
      await page.addInitScript(lib.INIT_SCRIPT);
      await lib.makeRouter(page);
      try {
        await page.goto('http://glassshot.local/', { waitUntil: 'domcontentloaded' });
        await lib.bootSession(page);
        await lib.applyTheme(page, theme);
        await prepare(page, TARGET);
        const png = join(OUT, `${TARGET}-${engineName}-${theme}.png`);
        await page.screenshot({ path: png, fullPage: false });
        const late = await page.evaluate(() => (window.__shotErrors || []).slice());
        for (const e of late) errors.push(`window.onerror: ${e}`);
        report.push({ target: TARGET, engine: engineName, theme, viewport: `${w}x${h}`, png, consoleErrors: errors, ok: errors.length === 0 });
        console.log(`[shot] ${TARGET} ${engineName} ${theme} → ${png} (${errors.length} error${errors.length ? 's' : ''})`);
        if (errors.length) errors.slice(0, 5).forEach((e) => console.log(`    · ${e.slice(0, 160)}`));
      } catch (e) {
        report.push({ target: TARGET, engine: engineName, theme, viewport: `${w}x${h}`, png: null, consoleErrors: [String(e.message)], ok: false });
        console.error(`[shot] ${TARGET} ${engineName} ${theme} FAILED: ${e.message}`);
      }
      await page.close();
    }
    await browser.close();
  }
  const reportPath = join(OUT, 'shot-report.json');
  // 追加式报告：多次调用（不同 target）合并进同一份 shot-report.json；
  // 同 (target, engine, theme) 组合以本次运行覆盖。
  let prior = [];
  try { prior = JSON.parse(require('node:fs').readFileSync(reportPath, 'utf8')); if (!Array.isArray(prior)) prior = []; } catch { /* 首次运行 */ }
  const sig = (r) => `${r.target}|${r.engine}|${r.theme}`;
  const merged = prior.filter((r) => !report.some((n) => sig(n) === sig(r))).concat(report);
  writeFileSync(reportPath, JSON.stringify(merged, null, 2));
  const bad = merged.filter((r) => !r.ok).length;
  console.log(`shot.cjs done: ${merged.length} shot(s) in report, ${bad} with errors`);
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
