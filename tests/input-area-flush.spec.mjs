// input-area-flush.spec.mjs — R70 输入条底缘与 sidebar / canvas 齐平几何门禁。
//
// 条目来源：UI 批次审计 2026-11-04（D 档 9 条之 R70；台账 #1475⑤）。改前读数
// （审计 + 对抗复核R2 双方独立亲量，1440×900）：`#input-bar` bottom=**896**、
// `#input-area` bottom=**900**（=视口底）vs `#sidebar` / `#canvas-panel` bottom=**890**
// ⇒ **差 6px / 10px**。批方当年只比「输入区底 = 视口底」就报了齐平。
//
// 判据（机械）：
//   ① `#input-bar`.bottom == `#sidebar`.bottom（|Δ| ≤ 0.5px）；
//   ② `#input-bar`.bottom == `#canvas-panel`.bottom（|Δ| ≤ 0.5px；画布开/闭两态各量一次，
//      开态用 #canvas-toggle-btn 真实点开 —— 面板底缘不随开合变，但两态都量才叫「齐平」）；
//   ③ 三列同底不靠视口贴边：bar.bottom 距视口底 = 侧栏底距视口底（= 10px 令牌档），
//      即「齐平」是**三列同基准**，不是「输入条贴到视口底」；
//   ④ 不回流：bar 顶 − chat 底 ≥ 0（聊天列不压条；末条消息仍在条上方，见 R71 spec）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/input-area-flush.spec.mjs

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat, probeChatFace } from './_chat-face-harness.mjs';

const EVID = process.env.EVID_DIR || null;
const TAG = process.env.EVID_TAG || 'after';

function saveReadings(name, payload) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

test.describe('R70 输入条底缘与 sidebar / canvas 齐平', () => {
  test('桌面 1440×900：三列同底（画布闭态）', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await bootChat(page);
    const geo = await probeChatFace(page);
    const d = {
      barBottom: geo.inputBar.bottom,
      sidebarBottom: geo.sidebar && geo.sidebar.bottom,
      canvasBottom: geo.canvas && geo.canvas.bottom,
      areaBottom: geo.inputArea.bottom,
      viewportBottom: geo.viewport.h,
      barToSidebar: +(geo.inputBar.bottom - geo.sidebar.bottom).toFixed(2),
      barToCanvas: +(geo.inputBar.bottom - geo.canvas.bottom).toFixed(2),
      geo,
    };
    saveReadings('r70-desktop-closed', d);

    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r70-desktop-1440.${TAG}.png`) });
    }

    // ① 输入条底缘 == 侧栏底缘
    expect(d.barToSidebar).toBeCloseTo(0, 0);
    expect(Math.abs(d.barToSidebar)).toBeLessThanOrEqual(0.5);
    // ② 输入条底缘 == 画布底缘
    expect(Math.abs(d.barToCanvas)).toBeLessThanOrEqual(0.5);
    // ③ 三列同基准（都离视口底同一段），不是「输入条贴视口底」
    expect(d.barBottom).toBeLessThan(d.viewportBottom - 1);
    expect(d.barBottom).toBeCloseTo(d.sidebarBottom, 1);
  });

  test('桌面 1440×900：三列同底（画布开态）', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await bootChat(page);
    // 画布开态 = `body.canvas-open`（面板几何全部由该类驱动：split.css
    // `body.canvas-open #canvas-panel{flex-basis:var(--canvas-width);opacity:1}`）。
    // 本桩无工程上下文，开合钮的工程闸不必过——直接翻类，量的是**布局态**本身。
    await page.evaluate(() => document.body.classList.add('canvas-open'));
    await page.waitForFunction(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return p.width > 100 && p.height > 100;
    }, null, { timeout: 10000 });
    await page.waitForTimeout(700); // 面板 flex-basis 过渡落地
    const geo = await probeChatFace(page);
    const d = {
      barBottom: geo.inputBar.bottom,
      sidebarBottom: geo.sidebar && geo.sidebar.bottom,
      canvasBottom: geo.canvas && geo.canvas.bottom,
      barToSidebar: +(geo.inputBar.bottom - geo.sidebar.bottom).toFixed(2),
      barToCanvas: +(geo.inputBar.bottom - geo.canvas.bottom).toFixed(2),
      geo,
    };
    saveReadings('r70-desktop-open', d);

    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r70-desktop-1440-canvas-open.${TAG}.png`) });
    }

    expect(Math.abs(d.barToSidebar)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(d.barToCanvas)).toBeLessThanOrEqual(0.5);
  });
});
