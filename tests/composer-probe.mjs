// composer-probe.mjs — 主会话输入面双树对照读数（作者 2026-11-06 20:12 令
// 「主会话输入框的样式也变了，之前的就很好了」）。
// 用法：node tests/composer-probe.mjs <tag> [webRootOverride]
//   输出 reports-evidence/notify-bubble-fix/composer-<tag>.json + .png（亮暗两档）
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const tag = process.argv[2] || 'probe';
if (process.argv[3]) process.env.CHATFACE_WEB_ROOT = process.argv[3];
const dir = new URL('../reports-evidence/notify-bubble-fix/', import.meta.url);
mkdirSync(dir, { recursive: true });
const browser = await chromium.launch();
const results = [];
try {
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme });
    const page = await context.newPage();
    await bootChat(page, { history: historyFixture(1) });
    const reading = await page.evaluate(async () => {
      const cs = (el, props) => {
        if (!el) return null;
        const s = getComputedStyle(el);
        return Object.fromEntries(props.map((p) => [p, s.getPropertyValue(p)]));
      };
      const bar = document.getElementById('input-bar');
      const wrap = document.getElementById('input-wrap');
      const input = document.getElementById('input');
      const area = document.getElementById('input-area');
      const send = document.getElementById('send-btn');
      const rect = (el) => (el ? (({ x, y, width, height }) => ({ x, y, width, height }))(el.getBoundingClientRect()) : null);
      // 键盘焦点态（textarea 聚焦后的 outline / box-shadow 读数）
      input?.focus();
      await new Promise((r) => setTimeout(r, 80));
      const focusState = input
        ? {
            outline: getComputedStyle(input).outlineStyle,
            outlineWidth: getComputedStyle(input).outlineWidth,
            boxShadow: getComputedStyle(input).boxShadow,
            borderColor: getComputedStyle(input).borderColor,
          }
        : null;
      return {
        bar: cs(bar, ['background-color', 'backdrop-filter', 'border-radius', 'border-color', 'box-shadow', 'padding']),
        wrap: cs(wrap, ['background-color', 'border', 'border-radius', 'box-shadow']),
        input: cs(input, ['background-color', 'border-radius', 'font-size', 'padding']),
        send: cs(send, ['background-color', 'width', 'height', 'border-radius']),
        rects: { bar: rect(bar), wrap: rect(wrap), area: rect(area), input: rect(input) },
        focusState,
      };
    });
    results.push({ theme, reading });
    await page.locator('#input-area').screenshot({ path: new URL(`composer-${tag}-${theme}.png`, dir).pathname });
    await context.close();
  }
} finally {
  await browser.close();
}
writeFileSync(new URL(`composer-${tag}.json`, dir), JSON.stringify(results, null, 2));
console.log(`composer-probe ${tag}: written`);
