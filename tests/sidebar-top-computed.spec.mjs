#!/usr/bin/env node
// 侧栏面板顶部标准：离线真实源码壳，亮暗逐属性比较；数值容差 0.1px。
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const out = process.env.SIDEBAR_TOP_EVIDENCE || join(ROOT.split('/.stand/worktrees/')[0], '.stand/tmp/favorites-top-unify/evidence');
mkdirSync(out, { recursive: true });
const baseline = process.argv.includes('--baseline');
const { browser } = await launchBrowser(loadPlaywright().mod);
const readings = {};
try {
  for (const theme of ['light', 'dark']) {
    const s = await createSession(browser, { width: 1280, height: 800, colorScheme: theme });
    try {
      await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'sidebar-top', messages: [] });
      await s.page.evaluate(async () => (await import('/js/activityBar.js')).enableFriendPanels());
      readings[theme] = {};
      for (const [name, button, panel, header, title, search] of [
        ['messages', '#messages-btn', '#panel-messages', '.panel-header', '.panel-title', '.fm-msg-search'],
        ['favorites', '#favorites-btn', '#panel-favorites', '.cfp-bar', '.cfp-title', '.cfp-search'],
      ]) {
        await s.page.click(button);
        await s.page.waitForSelector(`${panel}.active`, { state: 'visible' });
        await s.page.locator(`${panel} input`).first().blur();
        readings[theme][name] = await s.page.evaluate(({panel, header, title, search}) => {
          const root = document.querySelector(panel), h = root.querySelector(header), t = root.querySelector(title), w = root.querySelector(search), f = w.querySelector('.sf-field'), i = f.querySelector('input'), icon = f.querySelector('svg');
          const props = ['height','paddingTop','paddingRight','paddingBottom','paddingLeft','borderTopWidth','borderRightWidth','borderBottomWidth','borderLeftWidth','borderTopStyle','borderRightStyle','borderBottomStyle','borderLeftStyle','borderTopColor','borderRightColor','borderBottomColor','borderLeftColor','borderTopLeftRadius','borderTopRightRadius','borderBottomLeftRadius','borderBottomRightRadius','backgroundColor','fontSize','fontWeight','lineHeight','color','letterSpacing','textTransform'];
          const get = el => Object.fromEntries(props.map(p => [p, getComputedStyle(el)[p]]));
          return { header:get(h), title:get(t), search:get(w), field:get(f), input:get(i), placeholder:{color:getComputedStyle(i,'::placeholder').color,opacity:getComputedStyle(i,'::placeholder').opacity}, icon: icon ? {width:getComputedStyle(icon).width,height:getComputedStyle(icon).height,color:getComputedStyle(icon).color} : null, gap: w.getBoundingClientRect().top-h.getBoundingClientRect().bottom };
        }, {panel, header, title, search});
        await s.page.locator(panel).screenshot({ path: join(out, `${baseline?'before':'after'}-${name}-${theme}.png`), animations:'disabled' });
      }
    } finally { await s.close(); }
  }
  writeFileSync(join(out, `${baseline?'before':'after'}-readings.json`), JSON.stringify(readings, null, 2));
  if (!baseline) for (const theme of ['light','dark']) {
    const a = readings[theme].messages, b = readings[theme].favorites;
    for (const part of ['header','title','search','field','input','placeholder','icon']) {
      for (const key of Object.keys(a[part])) {
        const x = a[part][key], y = b[part]?.[key];
        const numeric = /^-?\d+(?:\.\d+)?px$/;
        if (numeric.test(x) && numeric.test(y)) assert(Math.abs(parseFloat(x)-parseFloat(y)) <= 0.1, `${theme} ${part}.${key}: ${x} != ${y}`);
        else assert.equal(y,x, `${theme} ${part}.${key}`);
      }
    }
    assert(Math.abs(a.gap-b.gap)<=0.1, `${theme} title/search gap`);
  }
  console.log(`sidebar-top-computed: ${baseline?'BASELINE':'PASS'}`);
} finally { await browser.close(); }
