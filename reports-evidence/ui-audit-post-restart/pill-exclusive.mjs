// pill-exclusive.mjs — 提示位单槽互斥的机械验证（不依赖引擎真实消息）
// 在页面内动态 import 被改的 js/scrollPill.js，造两个胶囊（同宿主：消息位 + 搜索回跳位），
// 分别 show() 后数「可见胶囊数」。期望：
//   改后（ROUTE=1）= 永远 ≤1（高阶压住低阶）；改前（ROUTE=0）= 2（两枚并存）。
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

const BASE = 'http://localhost:6138/';
const WT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/src/main/resources/web';
const ROUTE = process.env.ROUTE === '1';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, colorScheme: 'light' });
if (ROUTE) {
  await ctx.route('**/js/scrollPill.js*', async (r) => {
    await r.fulfill({ status: 200, contentType: 'application/javascript; charset=utf-8', body: fs.readFileSync(`${WT}/js/scrollPill.js`) });
  });
}
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);

const res = await page.evaluate(async () => {
  const { createScrollPill } = await import('/js/scrollPill.js');
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;inset:auto 0 0 0;height:400px;z-index:99';
  const scrollEl = document.createElement('div');
  scrollEl.style.cssText = 'height:200px;overflow:auto';
  scrollEl.innerHTML = '<div style="height:2000px"></div>';
  host.appendChild(scrollEl);
  document.body.appendChild(host);

  const vis = () => [...host.querySelectorAll('.scroll-new-pill')]
    .filter((e) => !e.classList.contains('hidden')).map((e) => e.textContent.trim());

  const ours = createScrollPill({ host, scrollEl, anchor: () => null });
  const search = createScrollPill({ host, scrollEl, anchor: () => null, stack: 'above', labelFor: () => '返回搜索结果' });

  const out = {};
  // 低阶先出现（搜索回跳位）
  search.show();
  out['A-仅搜索回跳位'] = vis();
  // 高阶出现（未读计数）——改后应当只剩 1 枚，改前两枚并存
  ours.sync({ count: 3, atBottom: false });
  out['B-叠加未读计数后'] = vis();
  // 高阶退场（回底清零）——改后低阶应当自己回来
  ours.sync({ atBottom: true });
  out['C-未读清零回底后'] = vis();
  ours.destroy(); search.destroy(); host.remove();
  return out;
});
await browser.close();
const single = Object.values(res).every((v) => v.length <= 1);
console.log(JSON.stringify({ route: ROUTE ? 'after(worktree)' : 'before(main)', res, allStatesAtMostOnePill: single }, null, 1));
