// footer-empty-turn.spec.mjs — 消息 footer 只跟「回复内容」走（作者令 2026-11-23：
// 「没有回复的内容，不用footer」）。
//
// 三形态（真浏览器 + 真模块图 + 真 CSS，经 _chat-face-harness.mjs；读 computed display）：
//   F1 有块回合：root report 块消息所在行 footer 照旧可见（零回归）。
//   F2 零块回合：root 零块气泡（data-nf-zero-output，已折进角标）所在行 footer 不渲染；
//      点开角标看过程后 footer 仍不出现；再收回仍不出现。
//   F3 混合回合：同一轮内零块消息无 footer，report 块消息保留 footer。
//   F4 非 root 零块（无戳）行 footer 不受影响（零回归）。
//
// 运行：node node_modules/@playwright/test/cli.js test tests/footer-empty-turn.spec.mjs --reporter=line
// 截图：FOOTER_SHOT_DIR=<绝对目录>（缺省不落盘）。

import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const ZERO_TEXT = '这一轮没有包 report 块：纯过程叙述，不是回复内容。';
const REPORT_TEXT = ['过程叙述：先看了链路。', '<report>', '结论：footer 只跟回复内容走。', '</report>'].join('\n');
const NONROOT_TEXT = '非 root 子会话的零块纯文字，逐字保留原展示。';

const ZERO_SEL = '#chat .bubble.ai[data-nf-zero-output="1"]';
const REPORT_SEL = '#chat .bubble.ai[data-nf-output]';

async function shot(page, name) {
  const dir = process.env.FOOTER_SHOT_DIR;
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: join(dir, `${name}.png`), fullPage: false });
}

// 读某气泡所在行的 footer（直接子级 .duration-badge）可见性。
const footerOf = ({ sel, needle }) => {
  const bubbles = [...document.querySelectorAll(sel)];
  const b = needle ? bubbles.find((x) => x.textContent.includes(needle)) : bubbles[0];
  if (!b) return { rowFound: false, footer: false, visible: false };
  const row = b.closest('.row');
  const f = row && row.querySelector(':scope > .duration-badge');
  return {
    rowFound: true,
    footer: !!f,
    visible: !!f && getComputedStyle(f).display !== 'none' && f.getBoundingClientRect().height > 0,
  };
};

// 点开包住某气泡的那一轮 turn-header 角标（从气泡向前扫描最近的 header）。
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

function historyForReport() {
  const now = Date.now();
  return [
    ...historyFixture(2, { lastRole: 'user' }),
    { type: 'ai', text: REPORT_TEXT, terminal: true, durationMs: 700, model: 'stub-x', isRoot: true, timestamp: now - 3000 },
  ];
}

function historyForZero() {
  const now = Date.now();
  return [
    ...historyFixture(2, { lastRole: 'user' }),
    { type: 'ai', text: ZERO_TEXT, terminal: true, durationMs: 900, model: 'stub-x', isRoot: true, timestamp: now - 3000 },
  ];
}

function historyMixed() {
  const now = Date.now();
  return [
    ...historyFixture(2, { lastRole: 'user' }),
    { type: 'ai', text: ZERO_TEXT, thinking: '过程思考', durationMs: 900, model: 'stub-x', isRoot: true, timestamp: now - 5000 },
    { type: 'ai', text: REPORT_TEXT, terminal: true, durationMs: 700, model: 'stub-x', isRoot: true, timestamp: now - 3000 },
  ];
}

test.describe('footer 只跟回复内容走', () => {
  test('F1 有块回合：report 块消息 footer 照旧可见', async ({ page }) => {
    await bootChat(page, { history: historyForReport() });
    await page.waitForTimeout(600);
    const f1 = await page.evaluate(footerOf, { sel: REPORT_SEL, needle: '结论' });
    expect(f1.rowFound, 'report 块气泡存在').toBe(true);
    expect(f1.visible, '有块回合 footer 照旧可见').toBe(true);
    await shot(page, 'f1-report-footer-visible');
  });

  test('F2 零块回合：无 footer；角标展开/收回后仍无 footer', async ({ page }) => {
    await bootChat(page, { history: historyForZero() });
    await page.waitForTimeout(600);
    const f2a = await page.evaluate(footerOf, { sel: ZERO_SEL, needle: '纯过程叙述' });
    expect(f2a.rowFound, '零块气泡存在').toBe(true);
    expect(f2a.visible, '零块回合 footer 不渲染（默认折叠态）').toBe(false);
    await shot(page, 'f2-zero-no-footer');

    expect(await page.evaluate(clickTurnHeaderOf, ZERO_SEL), '零块轮立角标可点').toBe(true);
    await page.waitForTimeout(300);
    const f2b = await page.evaluate(footerOf, { sel: ZERO_SEL, needle: '纯过程叙述' });
    expect(f2b.visible, '角标展开看过程也不出现 footer').toBe(false);
    await shot(page, 'f2-zero-expanded-no-footer');

    await page.evaluate(clickTurnHeaderOf, ZERO_SEL);
    await page.waitForTimeout(300);
    const f2c = await page.evaluate(footerOf, { sel: ZERO_SEL, needle: '纯过程叙述' });
    expect(f2c.visible, '收回后仍无 footer').toBe(false);
  });

  test('F3 混合回合：零块消息无 footer，report 块消息保留 footer', async ({ page }) => {
    await bootChat(page, { history: historyMixed() });
    await page.waitForTimeout(600);
    const zero = await page.evaluate(footerOf, { sel: ZERO_SEL, needle: '纯过程叙述' });
    const rep = await page.evaluate(footerOf, { sel: REPORT_SEL, needle: '结论' });
    expect(zero.rowFound).toBe(true);
    expect(rep.rowFound).toBe(true);
    expect(zero.visible, '混合回合中零块消息无 footer').toBe(false);
    expect(rep.visible, '混合回合中 report 块消息保留 footer').toBe(true);
    await shot(page, 'f3-mixed-footer-on-report-only');
  });

  test('F4 非 root 零块（无戳）行 footer 不受影响', async ({ page }) => {
    const now = Date.now();
    const hist = [
      ...historyFixture(2, { lastRole: 'user' }),
      { type: 'ai', text: NONROOT_TEXT, terminal: true, durationMs: 400, model: 'stub-x', isRoot: false, timestamp: now - 3000 },
    ];
    await bootChat(page, { history: hist });
    await page.waitForTimeout(600);
    const f4 = await page.evaluate(footerOf, { sel: '#chat .bubble.ai', needle: '非 root 子会话' });
    expect(f4.rowFound).toBe(true);
    expect(f4.visible, '非 root 行 footer 照旧可见（零回归）').toBe(true);
  });
});
