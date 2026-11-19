// header-input-width-align.spec.mjs — 顶栏宽度还原 + 输入框向顶栏看齐（逐像素几何门禁）。
//
// 作者原话（2026-11-05 17:17，逐字）：「然后就是我们电脑端的Header和输入框的问题。
//   我让统一的意思是跟Header的宽度保持一致，现在反而把Header缩短了，翻git历史，
//   找到以前正常的宽度。」
//
// 口径（理解反了就会做反）：Header 是**锚**，不许被任何一侧迁就 ——
//   ① `#header` 还原到历史正常宽度 = **#main 全宽**（水平外距 0、水平内距 16px，
//      考古见 vault 报告：2026-07-24 436651a57 起的形态，被 2026-11-05 62ed39ed2
//      「宽度对齐(件2)」把 16px 内距搬到外距 ⇒ 玻璃盒两侧各缩 16px）；
//   ② `#input-area` 向 Header 看齐 ⇒ `#input-bar` 外盒也铺满 #main；
//   ③ 两盒左右缘逐像素重合（差 ≤1px）。
//
// 判据（机械，桌面三档 1920/1440/1280 + 手机两档 768/375）：
//   ① `#header`.width == `#main`.width（锚 = 历史正常宽度，不是「上一轮那个窄值」）；
//   ② `#header`.left/right == `#main`.left/right；
//   ③ `#input-bar`.left == `#header`.left 且 `#input-bar`.right == `#header`.right（≤1px）；
//   ④ `#input-bar`.width == `#header`.width（≤1px）；
//   ⑤ 无横向溢出；输入条完整可见；发送钮 / 附件钮仍在条内；
//   ⑥ 顶栏内件不被挤坏：header-left / header-right 都在 header 内容盒内，
//      header-center（会话名）不被左右簇压穿；
//   ⑦ 窄屏（768 / 375）：手机层顶栏整体退役（display:none），输入卡侧距 ==
//      `--dock-inset-x`（R69 手机腿的既有语义），这两档**不因本批改变**。
//
// 读数落盘：EVID_DIR=<dir> EVID_TAG=before|after
// Run: node node_modules/@playwright/test/cli.js test tests/header-input-width-align.spec.mjs

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat } from './_chat-face-harness.mjs';

const EVID = process.env.EVID_DIR || 'evidence/20261105_header-input-width';
const TAG = process.env.EVID_TAG || 'after';
const TOL = 1;

/** 页面几何读数：主列 / 顶栏 / 输入区 / 输入条 / 顶栏内件 / 关键按钮 / 令牌。 */
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
  const area = document.getElementById('input-area');
  const se = document.scrollingElement;
  const csH = header ? getComputedStyle(header) : null;
  return {
    viewport: { w: window.innerWidth, h: window.innerHeight },
    mobileLayer: document.body.classList.contains('mobile-layer'),
    main: rect('#main'),
    header: rect('#header'),
    headerDisplay: header ? csH.display : null,
    headerPadL: csH ? csH.paddingLeft : null,
    headerPadR: csH ? csH.paddingRight : null,
    headerMarginL: csH ? csH.marginLeft : null,
    headerMarginR: csH ? csH.marginRight : null,
    headerLeftCluster: rect('#header .header-left'),
    headerRightCluster: rect('#header .header-right'),
    headerCenter: rect('#header .header-center'),
    area: rect('#input-area'),
    areaPadL: area ? getComputedStyle(area).paddingLeft : null,
    areaPadR: area ? getComputedStyle(area).paddingRight : null,
    bar: rect('#input-bar'),
    dock: rect('#mobile-dock'),
    sendBtn: rect('#send-btn'),
    attachBtn: rect('#attach-btn'),
    // 共用宽度来源（设计令牌单点）：0 = 两盒都铺满 #main
    edgeToken: getComputedStyle(document.documentElement).getPropertyValue('--ds-main-col-edge-x').trim(),
    dockInset: px('--dock-inset-x'),
    hScroll: { scrollWidth: se.scrollWidth, clientWidth: se.clientWidth },
  };
});

function save(name, payload) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

async function shot(page, name, tag) {
  mkdirSync(EVID, { recursive: true });
  await page.screenshot({ path: join(EVID, `${name}.${tag}.png`) });
}

/** 公共断言（桌面/手机共用）：无溢出、条完整、钮在条内。 */
function expectCommon(d, tag) {
  expect(d.hScroll.scrollWidth, `${tag} ⑤ 横向溢出`).toBe(d.hScroll.clientWidth);
  expect(d.bar.height, `${tag} ⑤ 条高 > 0`).toBeGreaterThan(0);
  expect(d.bar.left, `${tag} ⑤ 条左缘不出视口`).toBeGreaterThanOrEqual(-0.5);
  expect(d.bar.right, `${tag} ⑤ 条右缘不出视口`).toBeLessThanOrEqual(d.viewport.w + 0.5);
  for (const [k, b] of [['send', d.sendBtn], ['attach', d.attachBtn]]) {
    expect(b, `${tag} ⑤ ${k} 钮存在`).not.toBeNull();
    expect(b.left, `${tag} ⑤ ${k} 左缘在条内`).toBeGreaterThanOrEqual(d.bar.left - 0.5);
    expect(b.right, `${tag} ⑤ ${k} 右缘在条内`).toBeLessThanOrEqual(d.bar.right + 0.5);
  }
}

const DESKTOP = [
  { tag: '1920-亮', w: 1920, h: 1080, scheme: 'light' },
  { tag: '1920-暗', w: 1920, h: 1080, scheme: 'dark' },
  { tag: '1440-亮', w: 1440, h: 900, scheme: 'light' },
  { tag: '1440-暗', w: 1440, h: 900, scheme: 'dark' },
  { tag: '1280-亮', w: 1280, h: 800, scheme: 'light' },
];

test.describe('顶栏宽度还原 + 输入框向顶栏看齐', () => {
  for (const c of DESKTOP) {
    test(`桌面 ${c.tag}：Header 铺满 #main，输入条与 Header 左右缘逐像素重合`, async ({ page }) => {
      await page.setViewportSize({ width: c.w, height: c.h });
      await page.emulateMedia({ colorScheme: c.scheme });
      await bootChat(page);
      const d = await probe(page);
      await shot(page, `desktop-${c.tag}`, TAG);
      save(`desktop-${c.tag}`, d);
      expectCommon(d, c.tag);

      // ① / ② 锚：Header == #main 外盒（历史正常宽度）
      expect(d.headerDisplay, `${c.tag} 顶栏在场`).not.toBe('none');
      expect(Math.abs(d.header.width - d.main.width), `${c.tag} ① Header 宽 == #main 宽`)
        .toBeLessThanOrEqual(TOL);
      expect(Math.abs(d.header.left - d.main.left), `${c.tag} ② Header 左缘 == #main 左缘`)
        .toBeLessThanOrEqual(TOL);
      expect(Math.abs(d.header.right - d.main.right), `${c.tag} ② Header 右缘 == #main 右缘`)
        .toBeLessThanOrEqual(TOL);

      // ③ / ④ 输入条向 Header 看齐
      expect(Math.abs(d.bar.left - d.header.left), `${c.tag} ③ 条左缘 == Header 左缘`)
        .toBeLessThanOrEqual(TOL);
      expect(Math.abs(d.bar.right - d.header.right), `${c.tag} ③ 条右缘 == Header 右缘`)
        .toBeLessThanOrEqual(TOL);
      expect(Math.abs(d.bar.width - d.header.width), `${c.tag} ④ 条宽 == Header 宽`)
        .toBeLessThanOrEqual(TOL);

      // ⑥ 顶栏内件不被挤坏：左右簇都在内容盒内，居中的会话名不被压穿
      const contentL = d.header.left + parseFloat(d.headerPadL);
      const contentR = d.header.right - parseFloat(d.headerPadR);
      expect(d.headerLeftCluster.left, `${c.tag} ⑥ 左簇在内容盒内`)
        .toBeGreaterThanOrEqual(contentL - 0.5);
      expect(d.headerRightCluster.right, `${c.tag} ⑥ 右簇在内容盒内`)
        .toBeLessThanOrEqual(contentR + 0.5);
      expect(d.headerRightCluster.left, `${c.tag} ⑥ 左右簇不重叠（右簇不退到左簇之后）`)
        .toBeGreaterThanOrEqual(d.headerLeftCluster.right - 0.5);
      if (d.headerCenter) {
        expect(d.headerCenter.left, `${c.tag} ⑥ 会话名不被左簇压穿`)
          .toBeGreaterThanOrEqual(d.headerLeftCluster.right - 0.5);
        expect(d.headerCenter.right, `${c.tag} ⑥ 会话名不被右簇压穿`)
          .toBeLessThanOrEqual(d.headerRightCluster.left + 0.5);
      }

      // 共用宽度来源：令牌在场且解析为 0（两盒都铺满 #main）
      expect(d.edgeToken, `${c.tag} 共用令牌 --ds-main-col-edge-x 在场`).not.toBe('');
      expect(parseFloat(d.edgeToken), `${c.tag} 共用令牌 == 0`).toBeCloseTo(0, 1);
    });
  }

  for (const c of [
    { tag: '768-亮', w: 768, h: 1024, scheme: 'light' },
    { tag: '375-亮', w: 375, h: 812, scheme: 'light' },
  ]) {
    test(`窄屏 ${c.tag}：手机层顶栏退役，输入卡侧距 == --dock-inset-x（既有语义不变）`, async ({ page }) => {
      await page.setViewportSize({ width: c.w, height: c.h });
      await page.emulateMedia({ colorScheme: c.scheme });
      await bootChat(page);
      await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
      const d = await probe(page);
      await shot(page, `narrow-${c.tag}`, TAG);
      save(`narrow-${c.tag}`, d);
      expectCommon(d, c.tag);

      expect(d.mobileLayer, `${c.tag} ⑦ 手机层激活`).toBe(true);
      expect(d.headerDisplay, `${c.tag} ⑦ 顶栏在手机层退役（display:none）`).toBe('none');
      // 手机腿既有语义：输入卡侧距 = --dock-inset-x，且与 dock 胶囊同轴
      const left = +(d.bar.left - d.main.left).toFixed(2);
      const right = +(d.main.right - d.bar.right).toFixed(2);
      expect(left, `${c.tag} ⑦ 左距 == --dock-inset-x`).toBeCloseTo(parseFloat(d.dockInset), 1);
      expect(right, `${c.tag} ⑦ 右距 == --dock-inset-x`).toBeCloseTo(parseFloat(d.dockInset), 1);
      if (d.dock) {
        expect(Math.abs(d.bar.left - d.dock.left), `${c.tag} ⑦ 与 dock 同轴（左）`)
          .toBeLessThanOrEqual(TOL);
        expect(Math.abs(d.bar.right - d.dock.right), `${c.tag} ⑦ 与 dock 同轴（右）`)
          .toBeLessThanOrEqual(TOL);
      }
    });
  }

  test('1440-亮 ⑧ 附件预览展开态：条宽仍 == Header 宽（高熵副作用不破对齐）', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: 'light' });
    await bootChat(page);
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
    await shot(page, 'desktop-1440-attach-expanded', TAG);
    save('desktop-1440-attach-expanded', d);
    expect(d.scrollWidth, '⑧ 附件展开态横向溢出').toBe(d.clientWidth);
    expect(Math.abs(d.barW - d.headerW), '⑧ 附件展开态宽度差').toBeLessThanOrEqual(TOL);
    expect(Math.abs(d.barL - d.headerL), '⑧ 附件展开态左缘差').toBeLessThanOrEqual(TOL);
    expect(Math.abs(d.barR - d.headerR), '⑧ 附件展开态右缘差').toBeLessThanOrEqual(TOL);
  });
});
