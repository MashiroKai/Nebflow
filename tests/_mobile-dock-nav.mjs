// _mobile-dock-nav.mjs — dock 批（2026-10-29）手机层导航共用测试助手。
//
// 导航形态换代后（五栏底 dock + 全屏页面栈），p 系 spec 的入口腿统一走
// 真实 UI 路径：
//   · dock 根页 = 底栏五键（#mobile-dock .dock-key[data-dock]）；
//   · 详情页   = 设置根页「工作台」分组行（.mf-hub-row[data-hub-page]）
//     或聊天根顶栏 chrome 键（[data-chrome]）。
// 页面推入 = 0.26s 滑入 transform —— 类翻转即采样会踩进位移中段，
// settlePage 轮询到 transform 归位（收编几何读数的统一前置）。

import { expect } from '@playwright/test';

/** 等页面滑入终态（transform 归位）。 */
export async function settlePage(page, pageId) {
  await page.waitForFunction((pid) => {
    const el = document.querySelector(`.mobile-page[data-page="${pid}"]`);
    if (!el || !el.classList.contains('live')) return false;
    const tf = getComputedStyle(el).transform;
    return tf === 'none' || tf === 'matrix(1, 0, 0, 1, 0, 0)';
  }, pageId, { timeout: 5000 });
}

/** 底栏五键切根页（dock 根 = stack[0] 替换；聊天根无激活键）。 */
export async function dockTo(page, id) {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await expect(page.locator(`.mobile-page[data-page="${id}"]`)).toHaveClass(/live/);
  await settlePage(page, id);
}

/** 设置根页工作台行 → 详情页（定时/心跳/后台/项目/图谱/插件/用量）。 */
export async function openHubPage(page, face) {
  await dockTo(page, 'settings');
  await page.locator(`.mobile-page[data-page="settings"] .mf-hub-row[data-hub-page="${face}"]`).click();
  await expect(page.locator(`.mobile-page[data-page="${face}"]`)).toHaveClass(/live/);
  await settlePage(page, face);
}
