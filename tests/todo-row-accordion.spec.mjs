// todo-row-accordion.spec.mjs — 桌面待办行 accordion / 点外部收起 回归门（2026-11-21）。
//
// 三条行为断言（作者原话：「点开了之后无法收起……再点其他的，它自动就收起了」）：
//   A1 点开一条详情（折角钮）→ 点另一条详情 ⇒ 前一条自动收起（同一时间只开一条）；
//   A2 编辑中点其他行 ⇒ 旧编辑落盘（标题保存）、新编辑打开（旧行不再编辑态）；
//   A3 编辑中点列表空白区 ⇒ 落盘收起（不新建行）；
//   A4 点面板外部 ⇒ 整卡收起（既有 bindOutsideClose 行为，回归护栏）。
//
// 数据面 = _todo-face-harness 的 WS 桩 + todoStore.__seed 夹具（零后端、零 6138）。
// 截图证据（供 GIF 合成）落 SHOT_DIR（env TODO_ACCORDION_SHOT_DIR 可覆写）。
// Run: npx playwright test tests/todo-row-accordion.spec.mjs --project=chromium

import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { bootTodoFace, openPanel } from './_todo-face-harness.mjs';

const SHOT_DIR = process.env.TODO_ACCORDION_SHOT_DIR
  || join(process.cwd(), '..', '..', '..', 'tmp', 'todo-panel-disclosure', 'evidence', 'shots');

const NOW = Date.now();
const ITEMS = [
  { id: 'a', tagId: 'tag-work', name: '条目甲', detail: '甲的详情：第一段长文本，用于展开折角。', due: NOW + 3600_000, priority: 2, createdAt: NOW - 3 * 3600_000 },
  { id: 'b', tagId: 'tag-work', name: '条目乙', detail: '乙的详情：第二段长文本，用于展开折角。', due: NOW + 7200_000, priority: 1, createdAt: NOW - 2 * 3600_000 },
  { id: 'c', tagId: 'tag-work', name: '条目丙', detail: '', due: null, priority: 0, createdAt: NOW - 3600_000 },
];
const TAGS = [{ id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' }];

// TODO_ACCORDION_VIDEO=1 时录像（供 GIF 合成；默认关，不改变门禁行为）。
test.use({
  viewport: { width: 1280, height: 800 },
  colorScheme: 'light',
  video: process.env.TODO_ACCORDION_VIDEO ? { mode: 'on', size: { width: 1280, height: 800 } } : 'off',
});

test.beforeAll(() => mkdirSync(SHOT_DIR, { recursive: true }));

const rowSel = (id) => `.tfl-row[data-id="${id}"]`;
const isOpen = (page, id) => page.locator(rowSel(id)).evaluate((el) => el.classList.contains('tfl-row-open'));
const isEditing = (page, id) => page.locator(rowSel(id)).evaluate((el) => el.classList.contains('tfl-row-editing'));
const detailHidden = (page, id) => page.locator(`${rowSel(id)} > .tfl-row-main > .tfl-row-detail`).evaluate((el) => el.hidden);
const shot = (page, name) => page.screenshot({ path: join(SHOT_DIR, `${name}.png`) });

test('A1 点开一条详情后点另一条 ⇒ 前一条自动收起；点面板外部 ⇒ 整卡收起', async ({ page }) => {
  await bootTodoFace(page, { tags: TAGS, items: ITEMS });
  await openPanel(page);

  await page.locator(`${rowSel('a')} .tfl-row-fold`).click();
  await expect.poll(() => isOpen(page, 'a')).toBe(true);
  expect(await detailHidden(page, 'a')).toBe(false);
  await shot(page, '01-open-a');

  await page.locator(`${rowSel('b')} .tfl-row-fold`).click();
  await expect.poll(() => isOpen(page, 'b')).toBe(true);
  await expect.poll(() => isOpen(page, 'a')).toBe(false);
  expect(await detailHidden(page, 'a')).toBe(true);
  expect(await page.locator(`${rowSel('a')} .tfl-row-fold`).getAttribute('aria-expanded')).toBe('false');
  await shot(page, '02-switch-to-b-a-collapsed');

  // 点面板外部（左上角页面空白，卡片外）⇒ 整卡收起
  await page.mouse.click(8, 8);
  await expect.poll(() => page.locator('.tfl-card').getAttribute('data-state')).not.toBe('open');
  await shot(page, '03-outside-click-card-closed');
});

test('A2 编辑中点其他行 ⇒ 旧编辑落盘收起、新编辑打开', async ({ page }) => {
  await bootTodoFace(page, { tags: TAGS, items: ITEMS });
  await openPanel(page);

  await page.locator(`${rowSel('a')} .tfl-row-title`).click();
  await expect.poll(() => isEditing(page, 'a')).toBe(true);
  await page.locator(`${rowSel('a')} .tfl-edit-name`).fill('条目甲已改');
  await shot(page, '04-editing-a');

  await page.locator(`${rowSel('b')} .tfl-row-title`).click();
  await expect.poll(() => isEditing(page, 'b')).toBe(true);
  await expect.poll(() => isEditing(page, 'a')).toBe(false);
  // 旧编辑已落盘：标题回写到行上，且编辑输入框只剩 b 的那一个
  await expect(page.locator(`${rowSel('a')} .tfl-row-title`)).toHaveText('条目甲已改');
  expect(await page.locator('.tfl-edit-name').count()).toBe(1);
  await shot(page, '05-switch-edit-to-b');
});

test('A3 编辑中点列表空白区 ⇒ 落盘收起，不新建行', async ({ page }) => {
  await bootTodoFace(page, { tags: TAGS, items: ITEMS });
  await openPanel(page);

  await page.locator(`${rowSel('b')} .tfl-row-title`).click();
  await expect.poll(() => isEditing(page, 'b')).toBe(true);
  await page.locator(`${rowSel('b')} .tfl-edit-name`).fill('条目乙已改');
  const rowsBefore = await page.locator('#task-list .tfl-row').count();

  const box = await page.locator('.tfl-list').boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height - 6);
  await expect.poll(() => isEditing(page, 'b')).toBe(false);
  await expect(page.locator(`${rowSel('b')} .tfl-row-title`)).toHaveText('条目乙已改');
  expect(await page.locator('#task-list .tfl-row').count()).toBe(rowsBefore);
  await shot(page, '06-blank-click-saves-and-closes');
});
