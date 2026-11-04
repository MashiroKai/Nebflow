// input-bar-inset.spec.mjs — R69 输入框收窄（不顶满、两侧留一致留白）几何门禁。
//
// 条目来源：UI 批次审计 2026-11-04（D 档 9 条之 R69；台账 #1475⑤，作者 recall
// 2026-11-03 18:30 + 2026-11-04 11:09）。改前读数（审计 + 对抗复核R2 双方独立亲量）：
//   `#input-bar` L/R ≡ `#main` L/R ⇒ 两侧留白 **0.0 / 0.0**（1440 与 390 两档皆然）；
//   `#input-area` 是 `absolute; left:0; right:0`，bar 无 max-width —— 一条收窄规则都没有。
//
// 判据（机械，三组；桌面 1440×900 与手机层 375×812 各跑一遍）：
//   ① 侧距 > 0（不顶满）：bar 左右缘到 #main 内容缘的距离各自 > 0；
//   ② 两侧相等：|L − R| ≤ 0.5px（「两侧留一致留白」的字面判据）；
//   ③ 数值钉在令牌上：桌面 L/R = 解析后的 `--ds-space-4`（16px）；手机层 L/R =
//      解析后的 `--dock-inset-x`（12px，= dock 胶囊左右边距，输入卡与 dock 同列）——
//      同一条规则喂两档，改值只改令牌。
//   ④ 反回退：bar 宽度 = #main 宽度 − L − R（不许出现「padding 加大但 bar 仍顶满」
//      这类假收窄）。
//
// 改前跑（红）= 本 spec 在本批 CSS 落地前跑出的读数；改后跑（绿）= 现在。
// Run: node node_modules/@playwright/test/cli.js test tests/input-bar-inset.spec.mjs
//   env：EVID_DIR=<dir> 落 readings.json + 双档截图（改前/改后各一次，目录分档）

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat, probeChatFace } from './_chat-face-harness.mjs';

const EVID = process.env.EVID_DIR || null;
const TAG = process.env.EVID_TAG || 'after';

/** 令牌解析（运行时读真值，钉「侧距 == 令牌」而不是钉字面量）。 */
const tokenPx = (page, name) => page.evaluate((n) => {
  const probe = document.createElement('span');
  probe.style.cssText = `position:absolute;left:-9999px;top:0;width:var(${n});height:0`;
  document.body.appendChild(probe);
  const v = parseFloat(getComputedStyle(probe).width) || 0;
  probe.remove();
  return v;
}, name);

function saveReadings(name, payload) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

test.describe('R69 输入框收窄（两侧一致留白）', () => {
  test('桌面 1440×900：侧距 == --ds-space-4 且左右逐值相等', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await bootChat(page);
    const geo = await probeChatFace(page);
    const want = await tokenPx(page, '--ds-space-4');
    const left = +(geo.inputBar.left - geo.main.left).toFixed(2);
    const right = +(geo.main.right - geo.inputBar.right).toFixed(2);
    saveReadings('r69-desktop', { left, right, want, geo });
    if (EVID) {
      // 截图先于断言拍（改前腿也要留对照图：红也必须有同一张图）
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r69-desktop-1440.${TAG}.png`) });
    }

    expect(geo.mobileLayer).toBe(false);
    // ① 不顶满
    expect(left).toBeGreaterThan(0);
    expect(right).toBeGreaterThan(0);
    // ② 两侧一致
    expect(Math.abs(left - right)).toBeLessThanOrEqual(0.5);
    // ③ 钉令牌
    expect(left).toBeCloseTo(want, 1);
    expect(right).toBeCloseTo(want, 1);
    // ④ 真收窄（宽度守恒）
    expect(geo.inputBar.width).toBeCloseTo(geo.main.width - left - right, 1);
  });

  test('手机层 375×812：侧距 == --dock-inset-x（与 dock 胶囊同列）且左右相等', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.emulateMedia({ colorScheme: 'dark' });
    await bootChat(page);
    // 手机层由视口宽自动进入（body.mobile-layer），确认到位
    await page.waitForSelector('body.mobile-layer', { timeout: 10000 });
    const geo = await probeChatFace(page);
    const want = await tokenPx(page, '--dock-inset-x');
    const left = +(geo.inputBar.left - geo.main.left).toFixed(2);
    const right = +(geo.main.right - geo.inputBar.right).toFixed(2);
    const dock = await page.evaluate(() => {
      const d = document.getElementById('mobile-dock');
      if (!d) return null;
      const b = d.getBoundingClientRect();
      return { left: +b.left.toFixed(2), right: +b.right.toFixed(2) };
    });
    saveReadings('r69-mobile', { left, right, want, dock, geo });
    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r69-mobile-375.${TAG}.png`) });
    }

    expect(geo.mobileLayer).toBe(true);
    expect(left).toBeGreaterThan(0);
    expect(right).toBeGreaterThan(0);
    expect(Math.abs(left - right)).toBeLessThanOrEqual(0.5);
    expect(left).toBeCloseTo(want, 1);
    expect(right).toBeCloseTo(want, 1);
    // 与 dock 胶囊同列（输入卡两侧留白 = dock 胶囊两侧边距 ⇒ 两件同一竖轴参照）
    expect(left).toBeCloseTo(geo.inputBar.left - geo.main.left, 1);
    if (dock) expect(left).toBeCloseTo(dock.left, 0);
  });
});
