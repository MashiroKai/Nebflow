// 真实隔离实例(:8093)修复验证：预置 canvas_tabs(含 deck 标签) → 嵌套拒绝启动、不递归
import { chromium } from 'playwright';
const BASE = 'http://localhost:8093';
const TOKEN = 'EBfGgmcUCkL68wVWJX-0lVgzXUA1PD2jxOjV408EIbg';
const FILE = '/tmp/nb-canvas-html/repro-deck.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));
// 等实例起来
for (let i = 0; i < 60; i++) {
  try { const r = await fetch(BASE + '/'); if (r.ok) break; } catch {}
  await sleep(2000);
  if (i === 59) { console.log('instance never came up'); process.exit(1); }
}
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(([tok, file]) => {
  localStorage.setItem('nebflow_token', tok); localStorage.setItem('neblink_token', tok);
  const p = { v: 2, tabs: [{ id: 'file:' + file, title: 'repro-deck.html', type: 'file', absPath: file, pinned: false, closable: true }], activeTabId: 'file:' + file };
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify(p));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify(p));
}, [TOKEN, FILE]);
const page = await ctx.newPage();
page.on('pageerror', e => { const s = String(e.message || e); if (!s.includes('embedded context')) console.log('[pageerror]', s.slice(0, 120)); });
await page.goto(BASE + '/');
await sleep(2000);
const ob = await page.$('#ob-skip'); if (ob) { await ob.click(); await sleep(400); }
for (let i = 0; i < 8; i++) {
  await sleep(3000);
  const frames = page.frames().map(f => f.url());
  const nestedApp = frames.filter(u => u.startsWith(BASE)).length - 1;
  const srcdoc = frames.filter(u => u === 'about:srcdoc').length;
  console.log(`[t+${(i + 1) * 3}s] frames=${frames.length} nested-app=${nestedApp} srcdoc=${srcdoc}`);
}
// 终态：面板应显示防护提示（iframe 被导航后替换），或 iframe 停在 srcdoc
const final = await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane');
  return { paneNotice: (pane?.textContent || '').includes('Rendering was stopped'), iframes: document.querySelectorAll('iframe').length };
});
console.log('final:', JSON.stringify(final));
await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/canvas-html-fix-verify.png' });
await browser.close();
