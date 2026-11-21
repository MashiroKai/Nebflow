// zero-report-visibility.spec.mjs — 零 report 块 root 消息可见性 + 引用原文剥块
// （2026-11-21 作者令：「你没包report块的内容 我点击展开工具过程应该要可见」；
//  「引用的内容应该去掉report块的语法 直接显示渲染后的纯文字」）。
//
// 判据（文本/几何读数，真浏览器 + 真模块图，经 _chat-face-harness.mjs）：
//   Z1 root 零块纯文字轮：气泡可见、全文照常可见（不吞、不空泡）。
//   Z2 root 零块轮 turn-header 角标 ⇒ 展开：气泡切原文（raw，标签原样）；再点收回全文。
//   Z3 对照：有块 root 轮正常态仍只显块内（零回归）。
//   Z4 非 root 零块：不盖戳、逐字可见（零回归）。
//   Z5 引用原文剥块（展示面）：有块 ⇒ 摘要为块内纯文字；无块 ⇒ 原文；存储原文不动。
//
// 运行：node node_modules/@playwright/test/cli.js test tests/zero-report-visibility.spec.mjs --reporter=line
// 截图：ZERO_SHOT_DIR=<绝对目录>（缺省不落盘）。

import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const ZERO_TEXT = '这一轮没有包 report 块：纯文字回复，应当整段可见，一个字不摘。\n第二段同样是普通说明。';
const REPORT_TEXT = [
  '过程叙述：先看了链路。',
  '<report>',
  '结论：登录缓存失效已修复。',
  '</report>',
  '框外补充：机器面细节不应出现在正常态。',
].join('\n');
const ZERO_SEL = '#chat .bubble.ai[data-nf-zero-output="1"]';
const NONROOT_TEXT = '非 root 子会话的零块纯文字，逐字保留原展示。';

function history() {
  const base = historyFixture(4, { lastRole: 'user' });
  const now = Date.now();
  return [
    ...base,
    { type: 'ai', text: ZERO_TEXT, thinking: '零块轮的过程思考（有过程行 ⇒ 角标立）', terminal: true, durationMs: 900, model: 'stub-x', isRoot: true, timestamp: now - 30000 },
    { type: 'user', text: '继续', timestamp: now - 20000 },
    { type: 'ai', text: REPORT_TEXT, terminal: true, durationMs: 700, model: 'stub-x', isRoot: true, timestamp: now - 10000 },
    { type: 'user', text: '子会话', timestamp: now - 8000 },
    { type: 'ai', text: NONROOT_TEXT, terminal: true, durationMs: 400, model: 'stub-x', isRoot: false, timestamp: now - 5000 },
  ];
}

async function shot(page, name) {
  const dir = process.env.ZERO_SHOT_DIR;
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: join(dir, `${name}.png`), fullPage: false });
}

// 点击包住某气泡的那一轮的 turn-header：从气泡向前扫描最近的 header。
const clickTurnHeaderOf = (s) => {
  const b = document.querySelector(s);
  if (!b) return false;
  let cur = b;
  while (cur && cur.id !== 'chat') {
    let sib = cur.previousElementSibling;
    while (sib) {
      if (sib.classList && sib.classList.contains('turn-header')) { sib.click(); return true; }
      sib = sib.previousElementSibling;
    }
    cur = cur.parentElement;
  }
  return false;
};

test.describe('零块 root 可见性 + 引用剥块', () => {
  test('Z1–Z4：零块 root 全文可见、角标展开见原文；report 块轮不回归；非 root 不变', async ({ page }) => {
    await bootChat(page, { history: history() });
    await page.waitForTimeout(600);

    const z1 = await page.evaluate(() => {
      const zero = document.querySelector('#chat .bubble.ai[data-nf-zero-output="1"]');
      return {
        found: !!zero,
        visible: !!zero && !zero.hidden && zero.getBoundingClientRect().height > 0,
        fullShown: !!zero && zero.textContent.includes('一个字不摘') && zero.textContent.includes('第二段'),
        tagsAbsent: !!zero && !zero.textContent.includes('<report>'),
      };
    });
    expect(z1.found, 'root 零块气泡盖零块戳').toBe(true);
    expect(z1.visible, 'root 零块气泡可见（不空泡、不隐藏）').toBe(true);
    expect(z1.fullShown, 'root 零块全文照常可见').toBe(true);
    await shot(page, 'z1-zero-collapsed');

    // Z2：角标展开 ⇒ 原文（标签原样）；再点收回全文。
    const hdrOk = await page.evaluate(clickTurnHeaderOf, ZERO_SEL);
    expect(hdrOk, '零块轮立 turn-header 角标并可点').toBe(true);
    await page.waitForTimeout(300);
    const z2a = await page.evaluate(() => {
      const b = document.querySelector('#chat .bubble.ai[data-nf-zero-output="1"]');
      return { raw: b.classList.contains('nf-output-raw'), pre: !!b.querySelector('.nf-output-raw-pre') };
    });
    expect(z2a.raw, '角标展开 ⇒ 零块气泡切原文').toBe(true);
    expect(z2a.pre).toBe(true);
    await shot(page, 'z2-zero-expanded');

    await page.evaluate(clickTurnHeaderOf, ZERO_SEL);
    await page.waitForTimeout(300);
    const z2b = await page.evaluate(() => {
      const b = document.querySelector('#chat .bubble.ai[data-nf-zero-output="1"]');
      return { raw: b.classList.contains('nf-output-raw'), visible: !b.hidden && b.textContent.includes('一个字不摘') };
    });
    expect(z2b.raw, '再点收回 ⇒ 全文正常态').toBe(false);
    expect(z2b.visible).toBe(true);

    // Z3：有块 root 轮正常态只显块内（零回归）。
    const z3 = await page.evaluate(() => {
      const b = [...document.querySelectorAll('#chat .bubble.ai[data-nf-output]')].find((x) => x.textContent.includes('登录缓存'));
      return { found: !!b, outerHidden: !!b && !b.textContent.includes('机器面细节'), tagsAbsent: !!b && !b.textContent.includes('<report>') };
    });
    expect(z3.found, '有块 root 气泡盖块面戳').toBe(true);
    expect(z3.outerHidden, '有块轮框外正文仍摘除').toBe(true);
    expect(z3.tagsAbsent, '有块轮正常态标签不现形').toBe(true);

    // Z4：非 root 零块 ⇒ 不盖任何戳，逐字可见。
    const z4 = await page.evaluate(() => {
      const b = [...document.querySelectorAll('#chat .bubble.ai')].find((x) => x.textContent.includes('非 root 子会话'));
      return { found: !!b, noZero: !!b && !b.dataset.nfZeroOutput, noOut: !!b && !b.dataset.nfOutput, visible: !!b && !b.hidden };
    });
    expect(z4.found).toBe(true);
    expect(z4.noZero, '非 root 不盖零块戳').toBe(true);
    expect(z4.noOut, '非 root 不盖块戳').toBe(true);
    expect(z4.visible).toBe(true);
    await shot(page, 'z3-mixed-normal');
  });

  test('Z5：引用原文剥块 —— 有块 ⇒ 摘要为块内纯文字；无块 ⇒ 原文', async ({ page }) => {
    await bootChat(page, { history: history() });
    await page.waitForTimeout(400);
    const r = await page.evaluate(async () => {
      const out = await import('./js/outputTag.js');
      const withBlock = '前言叙述。\n<report>\n**结论**：引用块要显示纯文字。\n</report>\n框外尾巴';
      const shown = out.displayOutputText(withBlock);
      return {
        shown,
        hasTag: /<\/?report>/.test(shown),
        hasOuter: shown.includes('框外尾巴'),
        noBlock: out.displayOutputText('无块的普通引用原文'),
      };
    });
    expect(r.hasTag, '引用展示不现形 report 标签').toBe(false);
    expect(r.hasOuter, '框外正文不进引用摘要').toBe(false);
    expect(r.shown).toContain('引用块要显示纯文字');
    expect(r.shown, 'markdown 粗体解析为纯文字').not.toContain('**');
    expect(r.noBlock, '无块 = 原文不变').toBe('无块的普通引用原文');
    await shot(page, 'z4-quote-strip');
  });
});
