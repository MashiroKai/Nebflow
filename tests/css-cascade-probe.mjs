// css-cascade-probe.mjs — 叠卡展开面「谁赢了」的取证探针（2026-11-07 · image-expand-disappears 批）
//
// 为什么需要它：图片组「一点展开就消失」的根因是**级联胜负**（漏掉的收括号让
// design-system.css 的规则凭空多得一个类名的特异性，压过 chat.css 的态规则）。
// 几何读数能证明「看不见」，但说不清「是谁把它压掉的」——本探针用 Chrome DevTools
// Protocol 的 CSS.getMatchedStylesForNode + document.styleSheets 两份读数回答：
//   ① 展开列 `.pop-expand-view` 的 computed display 与**命中规则的出处/选择器文本**；
//   ② design-system.css 这张表的顶层规则数（块被误挂进 :root 时会塌成个位数）。
//
// 用法（两种树各跑一次，产出直接进证据目录）：
//   node tests/css-cascade-probe.mjs [webRoot] > specificity-before.json
// 缺省 webRoot = 本仓 src/main/resources/web。
//
// 命名说明：文件名不带 .spec.mjs ⇒ 不进 playwright 收集面（它是独立 runner，
// 自起浏览器、自打 JSON、自己退出；进收集面会因顶层副作用掐掉整轮）。

import { chromium } from '@playwright/test';
import { bootChat } from './_chat-face-harness.mjs';

const R = process.env.CHATFACE_WEB_ROOT
  || process.argv[2]
  || new URL('../src/main/resources/web', import.meta.url).pathname;

const items = [0, 1, 2].map((i) => ({
  kind: 'image',
  name: `p${i}.png`,
  path: `/tmp/probe-${i}.png`,
  // 1×1 透明 PNG：本探针只关心盒子与级联，不关心像素内容
  src: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
}));

const payload = '___POP_JSON___' + JSON.stringify({ items, fileRefs: { ok: 3, failed: 0 }, warnings: [] });
const history = [
  { type: 'ai', text: '点展开看全部。', timestamp: Date.now() - 5000 },
  { type: 'tool', label: 'Pop', summary: '交付 3 个文件', content: payload, timestamp: Date.now() - 4000 },
];

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await bootChat(page, { history });
  await page.waitForSelector('#chat .pop-stack-area .pill', { timeout: 20000 });
  await page.waitForTimeout(400);
  await page.click('#chat .pop-stack-area .pill');
  await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view')?.classList.contains('show'));
  await page.waitForTimeout(700);

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('DOM.enable');
  await cdp.send('CSS.enable');

  const { root } = await cdp.send('DOM.getDocument', { depth: -1 });
  const { nodeId } = await cdp.send('DOM.querySelector', {
    nodeId: root.nodeId,
    selector: '#chat .pop-expand-view',
  });
  const matched = nodeId ? await cdp.send('CSS.getMatchedStylesForNode', { nodeId }) : { matchedCSSRules: [] };

  const declared = (matched.matchedCSSRules || []).map((r) => ({
    selector: r.rule.selectorList && r.rule.selectorList.text,
    origin: r.rule.origin,
    declarationsInheritedFrom: r.rule.styleSheetId || null,
    display: (r.rule.style.cssProperties || [])
      .filter((p) => p.name === 'display' && !p.disabled)
      .map((p) => p.value),
  }));

  const sheets = await page.evaluate(() => {
    const out = [];
    for (const s of Array.from(document.styleSheets)) {
      const href = s.href || '(inline)';
      let top = -1;
      let sample = [];
      try {
        const rules = s.cssRules || [];
        top = rules.length;
        sample = Array.from(rules).slice(0, 8).map((r) => (r.selectorText || r.cssText || '').slice(0, 60));
      } catch { /* 跨域表 */ }
      out.push({ href, topLevelRules: top, sample });
    }
    return out;
  });

  const computed = await page.evaluate(() => {
    const el = document.querySelector('#chat .pop-expand-view');
    const area = document.querySelector('#chat .pop-stack-area');
    const cs = el && getComputedStyle(el);
    const b = area && area.getBoundingClientRect();
    return {
      expandDisplay: cs && cs.display,
      expandClass: el && el.className,
      areaBox: b && { w: +b.width.toFixed(1), h: +b.height.toFixed(1) },
    };
  });

  process.stdout.write(JSON.stringify({
    webRoot: R,
    computed,
    dsSheet: sheets.find((s) => (s.href || '').includes('design-system.css')) || null,
    chatSheet: sheets.find((s) => (s.href || '').includes('chat.css')) || null,
    matchedRulesForExpandColumn: declared,
  }, null, 2) + '\n');
} finally {
  await browser.close();
}
