#!/usr/bin/env node
// tier-shots.mjs — 第二/三层「改前/改后」双档对照截图（1440 与 375 各一套）
//
// 任务书：②「必带 375 与 1440 双档改前/改后对照截图」；③「走同样口径」。
// 零端口零进程（复用 visual-harness 的 page.route + MockWS），碰不到宿主实例 6138。
//
// 用法：
//   node tier-shots.mjs --web-root <静根> --out <目录> --tag pre|post
// 然后 compare-shots.mjs 逐对报差异像素（改值层的变化必须"可见且可量化"）。
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, setWebRoot, webRoot } from '../../scripts/lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root');
const OUT = resolve(valOf('--out', './shots'));
const TAG = valOf('--tag', 'x');
const CLOCK = 1757000000000;

const CHAT = [
  { type: 'user', text: '把这份报告的重点整理成三条，并说明数据来源。', timestamp: 1757000000000 },
  { type: 'ai', text: '好的，三条重点如下：\n1. 先补视觉基线；\n2. 再上类型基建；\n3. 最后拆巨型函数。' },
  { type: 'tool', label: 'Read("a.md")', summary: '128 lines', isError: false, input: '{"path":"a.md"}', content: '1\t# 前端地基\n2\t\n' },
  { type: 'ai', text: '已推进到第二步。' },
];
const STUBS = { '/api/plugins': { body: { plugins: [], rejected: [] } }, '/api/nf-authcheck': { status: 204 }, '/api/agents': { body: { agents: [] } } };

const SCENARIOS = [
  { name: 'shell', run: async (s) => { await s.boot(); } },
  { name: 'chat', run: async (s) => { await s.boot({ messages: CHAT }); } },
  {
    name: 'settings',
    run: async (s) => {
      await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
      await s.page.evaluate(() => { const b = document.getElementById('settings-btn'); if (b) b.click(); });
      await s.page.waitForTimeout(800);
      await settle(s.page, 250);
    },
  },
];
const VIEWPORTS = [{ w: 1440, h: 900, tag: '1440' }, { w: 375, h: 812, tag: '375' }];

if (WEB_ROOT) setWebRoot(WEB_ROOT);
mkdirSync(OUT, { recursive: true });
const pwRes = loadPlaywright();
const pw = pwRes.mod.playwright || pwRes.mod;
const { browser, label } = await launchBrowser(pw);
const written = [];
for (const vp of VIEWPORTS) {
  for (const sc of SCENARIOS) {
    const s = await createSession(browser, { width: vp.w, height: vp.h, colorScheme: 'dark', clock: CLOCK, apiStubs: { ...STUBS } });
    try {
      await sc.run(s);
      await settle(s.page, 250);
      const buf = await s.page.screenshot({ animations: 'disabled' });
      const name = `${sc.name}-${vp.tag}-dark.png`;
      writeFileSync(join(OUT, name), buf);
      written.push(name);
    } finally {
      await s.close();
    }
  }
}
await browser.close();
console.log(`tier-shots [${TAG}] ${written.length} 张 → ${OUT}`);
console.log(`  静态根：${webRoot()}   浏览器：${label}`);
console.log('  ' + written.join(' '));
