// ui-seven-chat-width.spec.mjs — 件2 门禁：输入框宽度 == 顶栏宽度（逐像素对齐）。
//
// 作者原话（逐字）：「我们的主会话的输入框，应该和顶部的Header宽度一致。」
// 判据 = **实测盒宽**（getBoundingClientRect），不是 CSS 声明值：改前
// `#header` 是 `left:0; right:0` + 水平 padding 16px（盒 = #main 全宽），而
// `#input-area` 的水平 padding 16px 让 `#input-bar` 盒内缩 16px ⇒ 两侧各差 16px。
//
// 四档：1440×900 / 375×812 × 亮/暗。断言（①-⑤ 桌面用 #header 作参照）：
//   ① |bar.width − header.width| ≤ 1
//   ② |bar.left − header.left| ≤ 1
//   ③ |bar.right − header.right| ≤ 1
//   ④ 无横向溢出：scrollingElement.scrollWidth === clientWidth
//   ⑤ 输入条仍完整可见（rect.height > 0、未被裁出视口），发送钮/附件钮仍在条内
//   ⑥ 375 手机档：手机层顶栏整体退役（mobileLayer.css 的 `body.mobile-layer #header
//      { display:none }`）⇒ #header 无盒可量。该档的「顶栏宽度」参照 = 手机层同轴
//      参照物（下方 dock 胶囊，R69 批确立的「输入卡与 dock 同一左右边距」语义）：
//      ②③ 换成 |bar.left − dock.left| ≤ 1 / |bar.right − dock.right| ≤ 1，
//      ① 换成 |bar.width − dock.width| ≤ 1，④⑤ 照旧，另附 #header display 读数
//      证明该档确实无顶栏（不是跳过）。
//   ⑦ 既有行为不破：frozen 态与附件预览展开态下，bar.width 仍 == header.width。
//
// Run: PORT=8252 NEBFLOW_HOME=/tmp/ui-seven-c2 \
//   node node_modules/@playwright/test/cli.js test tests/ui-seven-chat-width.spec.mjs

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat } from './_chat-face-harness.mjs';

const EVID = 'evidence/20261105_ui-seven/c2';
const TAG = process.env.EVID_TAG || 'after';
const TOL = 1;

/** 页面几何读数：输入条 / 顶栏 / 同轴参照物 / 溢出 / 关键按钮。 */
const probe = (page) => page.evaluate(() => {
  const rect = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return {
      left: +b.left.toFixed(2), right: +b.right.toFixed(2),
      width: +b.width.toFixed(2), height: +b.height.toFixed(2),
      top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2),
    };
  };
  const px = (name) => {
    const probe = document.createElement('span');
    probe.style.cssText = `position:absolute;left:-9999px;top:0;width:var(${name});height:0`;
    document.body.appendChild(probe);
    const v = getComputedStyle(probe).width;
    probe.remove();
    return v;
  };
  const header = document.getElementById('header');
  const bar = document.getElementById('input-bar');
  const area = document.getElementById('input-area');
  const se = document.scrollingElement;
  return {
    viewport: { w: window.innerWidth, h: window.innerHeight },
    mobileLayer: document.body.classList.contains('mobile-layer'),
    header: rect('#header'),
    headerDisplay: header ? getComputedStyle(header).display : null,
    headerPadL: header ? getComputedStyle(header).paddingLeft : null,
    headerPadR: header ? getComputedStyle(header).paddingRight : null,
    headerMarginL: header ? getComputedStyle(header).marginLeft : null,
    headerMarginR: header ? getComputedStyle(header).marginRight : null,
    area: rect('#input-area'),
    bar: rect('#input-bar'),
    areaPadL: area ? getComputedStyle(area).paddingLeft : null,
    areaPadR: area ? getComputedStyle(area).paddingRight : null,
    main: rect('#main'),
    dock: rect('#mobile-dock'),
    sendBtn: rect('#send-btn'),
    attachBtn: rect('#attach-btn'),
    // 收口裁定（作者 2026-11-05 09:40）：gutter 与栅格档同档 ⇒ 不保留 --chat-gutter-x
    // 别名，两消费点（#header 外距 / #input-area 内距）直接用 --ds-space-4。
    gutterToken: px('--ds-space-4'),
    space4: px('--ds-space-4'),
    dockInset: px('--dock-inset-x'),
    hScroll: { scrollWidth: se.scrollWidth, clientWidth: se.clientWidth },
  };
});

function save(name, payload) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

/** 四档统一的大屏/手机档公共断言（④⑤ + 输入条内按钮）。 */
function expectCommon(d, tag) {
  // ④ 无横向溢出
  expect(d.hScroll.scrollWidth, `${tag} ④ 横向溢出`).toBe(d.hScroll.clientWidth);
  // ⑤ 输入条完整可见：有高、四边在视口内
  expect(d.bar.height, `${tag} ⑤ 条高 > 0`).toBeGreaterThan(0);
  expect(d.bar.left, `${tag} ⑤ 条左缘不出视口`).toBeGreaterThanOrEqual(-0.5);
  expect(d.bar.right, `${tag} ⑤ 条右缘不出视口`).toBeLessThanOrEqual(d.viewport.w + 0.5);
  expect(d.bar.top, `${tag} ⑤ 条顶缘不出视口`).toBeGreaterThanOrEqual(-0.5);
  expect(d.bar.bottom, `${tag} ⑤ 条底缘不出视口`).toBeLessThanOrEqual(d.viewport.h + 0.5);
  // ⑤ 发送钮 / 附件钮仍在条内
  for (const [k, b] of [['send', d.sendBtn], ['attach', d.attachBtn]]) {
    expect(b, `${tag} ⑤ ${k} 钮存在`).not.toBeNull();
    expect(b.left, `${tag} ⑤ ${k} 左缘在条内`).toBeGreaterThanOrEqual(d.bar.left - 0.5);
    expect(b.right, `${tag} ⑤ ${k} 右缘在条内`).toBeLessThanOrEqual(d.bar.right + 0.5);
  }
}

const CASES = [
  { tag: '1440-亮', w: 1440, h: 900, scheme: 'light' },
  { tag: '1440-暗', w: 1440, h: 900, scheme: 'dark' },
  { tag: '375-亮', w: 375, h: 812, scheme: 'light' },
  { tag: '375-暗', w: 375, h: 812, scheme: 'dark' },
];

test.describe('件2 输入框宽度 == 顶栏宽度', () => {
  for (const c of CASES) {
    test(`${c.tag}：输入条与顶栏逐像素同宽同位`, async ({ page }) => {
      await page.setViewportSize({ width: c.w, height: c.h });
      await page.emulateMedia({ colorScheme: c.scheme });
      await bootChat(page);
      if (c.w <= 768) await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
      const d = await probe(page);
      await page.screenshot({ path: join(EVID, `width-${c.tag}.${TAG}.png`) });
      save(`width-${c.tag}`, d);

      expectCommon(d, c.tag);

      if (c.w > 768) {
        // 桌面档：参照 = #header 实测盒
        expect(d.headerDisplay, `${c.tag} 顶栏在场`).not.toBe('none');
        expect(Math.abs(d.bar.width - d.header.width), `${c.tag} ① 宽度差`).toBeLessThanOrEqual(TOL);
        expect(Math.abs(d.bar.left - d.header.left), `${c.tag} ② 左缘差`).toBeLessThanOrEqual(TOL);
        expect(Math.abs(d.bar.right - d.header.right), `${c.tag} ③ 右缘差`).toBeLessThanOrEqual(TOL);
      } else {
        // 手机档：顶栏整体退役，参照 = dock 胶囊（同轴同位参照物）
        expect(d.mobileLayer, `${c.tag} 手机层激活`).toBe(true);
        expect(d.headerDisplay, `${c.tag} ⑥ 手机层顶栏已退役（display:none）`).toBe('none');
        expect(d.dock, `${c.tag} ⑥ dock 胶囊在场`).not.toBeNull();
        expect(Math.abs(d.bar.width - d.dock.width), `${c.tag} ⑥/① 宽度差`).toBeLessThanOrEqual(TOL);
        expect(Math.abs(d.bar.left - d.dock.left), `${c.tag} ⑥/② 左缘差`).toBeLessThanOrEqual(TOL);
        expect(Math.abs(d.bar.right - d.dock.right), `${c.tag} ⑥/③ 右缘差`).toBeLessThanOrEqual(TOL);
      }
    });
  }

  test('1440-亮 ⑦ frozen 态：条宽仍等于顶栏宽', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: 'light' });
    await bootChat(page);
    const base = await probe(page);
    // 真实状态类（main.js 的 frozen 事件路径给同一元素加同一类）
    const d = await page.evaluate(() => {
      const bar = document.getElementById('input-bar');
      bar.classList.add('frozen');
      const b = bar.getBoundingClientRect();
      const h = document.getElementById('header').getBoundingClientRect();
      return {
        frozen: getComputedStyle(bar).borderColor,
        barW: +b.width.toFixed(2), barL: +b.left.toFixed(2), barR: +b.right.toFixed(2),
        headerW: +h.width.toFixed(2), headerL: +h.left.toFixed(2), headerR: +h.right.toFixed(2),
      };
    });
    await page.screenshot({ path: join(EVID, `width-frozen.${TAG}.png`) });
    save('width-frozen', { base, frozen: d });

    expect(d.barW).toBeGreaterThan(0);
    expect(Math.abs(d.barW - d.headerW), '⑦ frozen 宽度差').toBeLessThanOrEqual(TOL);
    expect(Math.abs(d.barL - d.headerL), '⑦ frozen 左缘差').toBeLessThanOrEqual(TOL);
    expect(Math.abs(d.barR - d.headerR), '⑦ frozen 右缘差').toBeLessThanOrEqual(TOL);
  });

  test('1440-亮 ⑦ 附件预览展开态：条宽仍等于顶栏宽', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: 'light' });
    await bootChat(page);
    // 高熵附件 chip（宽 label）——历史溢出源（input.css 的 min-width:auto 注释），
    // 用真实类名与真实 CSS：#attachment-preview.mh-att-expanded + .att-ref。
    const d = await page.evaluate(() => {
      const prev = document.getElementById('attachment-preview');
      prev.classList.add('mh-att-expanded');
      const chip = document.createElement('span');
      chip.className = 'att-ref';
      chip.dataset.refType = 'chat-message';
      chip.innerHTML = '<span class="att-ref-label">' + 'x'.repeat(120) + '</span>';
      prev.appendChild(chip);
      const b = document.getElementById('input-bar').getBoundingClientRect();
      const h = document.getElementById('header').getBoundingClientRect();
      const se = document.scrollingElement;
      return {
        barW: +b.width.toFixed(2), barL: +b.left.toFixed(2), barR: +b.right.toFixed(2),
        headerW: +h.width.toFixed(2), headerL: +h.left.toFixed(2), headerR: +h.right.toFixed(2),
        scrollWidth: se.scrollWidth, clientWidth: se.clientWidth,
      };
    });
    await page.screenshot({ path: join(EVID, `width-attach-expanded.${TAG}.png`) });
    save('width-attach-expanded', d);

    expect(d.scrollWidth, '⑦ 附件展开态横向溢出').toBe(d.clientWidth);
    expect(Math.abs(d.barW - d.headerW), '⑦ 附件展开态宽度差').toBeLessThanOrEqual(TOL);
    expect(Math.abs(d.barL - d.headerL), '⑦ 附件展开态左缘差').toBeLessThanOrEqual(TOL);
    expect(Math.abs(d.barR - d.headerR), '⑦ 附件展开态右缘差').toBeLessThanOrEqual(TOL);
  });
});
