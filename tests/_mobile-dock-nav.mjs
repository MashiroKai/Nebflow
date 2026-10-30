// _mobile-dock-nav.mjs — dock 批（2026-10-29）手机层导航共用测试助手。
// 工作台批（2026-10-30）修订：第五 dock 根 = 工作台（设置降级为工作台行）。
//
// 导航形态换代后（五栏底 dock + 全屏页面栈），p 系 spec 的入口腿统一走
// 真实 UI 路径：
//   · dock 根页 = 底栏五键（#mobile-dock .dock-key[data-dock]）；
//   · 详情页   = 工作台根页「工作台」分组行（.mf-hub-row[data-hub-page]）
//     或聊天根顶栏 chrome 键（[data-chrome]）。
// 页面切换 = 无动画直切（作者令 2026-10-30 动画退役批）——live 即终态，
// settlePage 保留为统一前置（直切下零等待语义不变，调用点无需改动）。

import { expect } from '@playwright/test';

/** 等页面直切终态（live 在位即终态；动画退役批后无位移中段可踩）。 */
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

/** 工作台根页分组行 → 详情页（定时/心跳/后台/项目/图谱/插件/用量/设置）。 */
export async function openHubPage(page, face) {
  await dockTo(page, 'workbench');
  await page.locator(`.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${face}"]`).click();
  await expect(page.locator(`.mobile-page[data-page="${face}"]`)).toHaveClass(/live/);
  await settlePage(page, face);
}
