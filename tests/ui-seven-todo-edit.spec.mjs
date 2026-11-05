// ui-seven-todo-edit.spec.mjs — 件6「事项名与具体内容分开」门禁
// （UI 批次审计 2026-11-05 · ui-seven 件6；作者原话逐字：
//   「然后点击编辑的这个交互是在下方展开条目的，要把事项名和事项具体内容分开。
//     事项名应该简单。」）
//
// 口径（要求做法，逐条对断言）：
//   E1 位置：编辑面板仍在**该条目行下方**展开（不改触发方式：点行体进编辑）。
//   E2 两个独立输入面：事项名与具体内容是**两个不同元素**，都在编辑面板内，
//      DOM 序名在前；各自可获焦、各自可写值，焦点切换不丢值。
//   E3 名单行：名输入 tagName === 'INPUT'（非 TEXTAREA）+ 有 maxlength 属性（>0）；
//      粘贴含换行文本折叠成单行（换行剥离）、粘贴超长文本被夹到 maxlength。
//   E4 截断可编辑：保存超长名后，行内名称元素 scrollWidth > clientWidth 且
//      computed text-overflow: ellipsis；再次进入编辑，名输入 value === 完整原文
//      （长度 = maxlength，一字不丢）。
//   E5 保存语义不破：名 + 内容各自输入后保存，todoStore.updateItem 收到
//      { title, notes } 两字段且值正确（真 store 真状态，夹具模式）。
//   E6 零教学文案：编辑面板内文本节点（及 placeholder / aria-label）
//      ⊆ 既有 locale 键值集合（无新增解释性句子）。
//
// 读数落 evidence/20261105_ui-seven/c5/readings.json，截图同目录。
//
// 数据面 = tests/_todo-face-harness.mjs（零后端：page.route 注盘 + WS 帧桩 +
// todoStore.__seed 夹具，不碰 :6138、不跑 sbt）；指针 = Playwright 真指针。
//
// Run: node node_modules/@playwright/test/cli.js test tests/ui-seven-todo-edit.spec.mjs

import { test, expect } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace, openPanel } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const EVID = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_ui-seven', 'c5');
mkdirSync(EVID, { recursive: true });

const READINGS = {};
function save(name, payload) {
  let disk = {};
  try { disk = JSON.parse(readFileSync(join(EVID, 'readings.json'), 'utf8')); } catch { disk = {}; }
  READINGS[name] = payload;
  writeFileSync(join(EVID, 'readings.json'), JSON.stringify({ ...disk, ...READINGS }, null, 2));
}

const ROW = '.tfl-row[data-id="f-1"]';
const PANEL = `${ROW}.tfl-row-editing .tfl-edit-panel`;
const NAME = `${PANEL} .tfl-edit-name`;
const NOTES = `${PANEL} .tfl-edit-notes`;

/** 展开胶囊 → 点条目行体（既有触发方式）→ 等编辑面板 0fr→1fr 落地。 */
async function openEdit(page) {
  await openPanel(page);
  await page.locator(`${ROW} .tfl-row-main`).click();
  await page.waitForSelector(PANEL, { timeout: 10000 });
  await page.waitForTimeout(500);
}

/** E1/E2 几何 + 结构读数。 */
function editGeometry() {
  const row = document.querySelector('.tfl-row-editing');
  const panel = row.querySelector('.tfl-edit-panel');
  const name = row.querySelector('.tfl-edit-name');
  const notes = row.querySelector('.tfl-edit-notes');
  const rect = (el) => {
    const b = el.getBoundingClientRect();
    return {
      left: +b.left.toFixed(2), top: +b.top.toFixed(2),
      width: +b.width.toFixed(2), height: +b.height.toFixed(2), bottom: +b.bottom.toFixed(2),
    };
  };
  // 「条目行」= 行内除编辑面板外的头部（点名/备注/完成/删除在同一行带）：
  // 面板必须整块落在这一带的**下方**（row 自身含面板，故不能拿 row.bottom 比）。
  const headBottom = Math.max(...[...row.children]
    .filter((c) => !c.classList.contains('tfl-edit-wrap'))
    .map((c) => c.getBoundingClientRect().bottom));
  return {
    headBottom: +headBottom.toFixed(2),
    row: rect(row), panel: rect(panel),
    name: name ? { ...rect(name), tag: name.tagName, maxLength: name.getAttribute('maxlength') } : null,
    notes: notes ? { ...rect(notes), tag: notes.tagName, maxLength: notes.getAttribute('maxlength') } : null,
    // DOM 序：面板直接子元素里名在内容之前
    childOrder: [...panel.children].map((c) => c.className || c.tagName),
    sameNode: name === notes,
    nameInPanel: panel.contains(name), notesInPanel: panel.contains(notes),
  };
}

/** E6：面板内所有可见文案 ⊆ 既有 locale 值集合（无新增解释性句子）。
 *  判据两条：
 *   ① 文案 = 某个既有 locale 值**原样**，或**以某个既有 locale 值为前缀的模板 + 数据**
 *      （例：截止时刻键的 aria-label = 「截止时间」+ 日期 + 时刻，见 timeWheel.js）；
 *   ② 例外：截止时刻**读数**节点（`.tfl-due-holder` / `.tw-trigger` 里的
 *      「11/5 10:17」）是**数据**（纯格式化时间），不是文案 ⇒ 不进文本节点审计。
 *  审计面 = ②以外的文本节点 + 全面板 placeholder / aria-label。 */
function copyAudit() {
  const panel = document.querySelector('.tfl-row-editing .tfl-edit-panel');
  const texts = [];
  const attrs = [];
  const walker = document.createTreeWalker(panel, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const s = node.textContent.trim();
    if (!s) continue;
    const host = node.parentElement;
    if (host && host.closest('.tfl-due-holder, .tw-trigger')) continue; // 数据不是文案
    texts.push(s);
  }
  panel.querySelectorAll('[placeholder],[aria-label]').forEach((el) => {
    const p = el.getAttribute('placeholder');
    const a = el.getAttribute('aria-label');
    if (p) attrs.push(p);
    if (a) attrs.push(a);
  });
  return { texts, attrs };
}

test.describe('件6 桌面 1440×900：事项名与内容两个独立输入面', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  test('E1-E6 位置 / 两独立面 / 单行 + 上限 / 截断可编辑 / 保存语义 / 零教学文案', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => {
      if (m.type() !== 'error') return;
      const text = m.text();
      if (/Failed to load resource|fetching the script/i.test(text)) return;
      errors.push(`console: ${text}`);
    });

    await bootTodoFace(page);
    // store 写面探针（E5：夹具模式下拦 updateItem 的入参）
    await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      window.__patches = [];
      const orig = todoStore.updateItem;
      todoStore.updateItem = function (id, patch) {
        window.__patches.push({ id, patch: { ...patch } });
        return orig.call(this, id, patch);
      };
    });
    await openEdit(page);

    // ── E1 位置：面板整块在该条目行头部下方 ──
    const g = await page.evaluate(editGeometry);
    expect(g.panel.top, `面板必须在行头下方（panel.top=${g.panel.top} vs headBottom=${g.headBottom}）`)
      .toBeGreaterThanOrEqual(g.headBottom - 1);
    // 面板占满整行宽（第二行带），不与被编辑的行头并排
    expect(Math.abs(g.panel.width - (g.row.width - 12)), '面板宽度 = 行内容宽').toBeLessThanOrEqual(14);

    // ── E2 两个独立输入面 ──
    expect(g.name, '名输入存在').toBeTruthy();
    expect(g.notes, '内容输入存在').toBeTruthy();
    expect(g.sameNode, '名与内容必须是两个不同元素').toBe(false);
    expect(g.nameInPanel && g.notesInPanel, '两个输入面都在编辑面板内').toBe(true);
    const iName = g.childOrder.findIndex((c) => /tfl-edit-name/.test(c));
    const iNotes = g.childOrder.findIndex((c) => /tfl-edit-notes/.test(c));
    expect(iName, '名输入在面板内').toBeGreaterThanOrEqual(0);
    expect(iName, 'DOM 序：名在内容之前').toBeLessThan(iNotes);

    // 各自可获焦 + 各自 setValue 成功 + 焦点切换不丢值
    await page.locator(NAME).click();
    expect(await page.evaluate(() => document.activeElement?.className || '')).toContain('tfl-edit-name');
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('名A');
    await page.locator(NOTES).click();
    expect(await page.evaluate(() => document.activeElement?.className || '')).toContain('tfl-edit-notes');
    await page.keyboard.insertText('内容B');
    await page.locator(NAME).click();
    const bothValues = {
      name: await page.locator(NAME).inputValue(),
      notes: await page.locator(NOTES).inputValue(),
    };
    expect(bothValues.name, '焦点切走再回来名值仍在').toBe('名A');
    expect(bothValues.notes, '内容值独立保持').toBe('内容B');

    // ── E3 名单行 + 长度上限 + 换行剥离 ──
    expect(g.name.tag).toBe('INPUT');
    const maxLen = Number(g.name.maxLength);
    expect(maxLen, 'maxlength 属性存在且 > 0').toBeGreaterThan(0);
    // ① 真粘贴：剪贴板多行文本 → Ctrl+V（单行面上不许留换行）
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.locator(NAME).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.evaluate(() => navigator.clipboard.writeText('aaa\nbbb'));
    await page.keyboard.press('ControlOrMeta+v');
    const afterNewline = await page.locator(NAME).inputValue();
    expect(afterNewline, '真粘贴后 value 不含换行').not.toMatch(/[\r\n]/);
    // ② 带换行的值直接进输入面（自动填充 / 程序赋值同一通道）：由输入面剥离
    await page.locator(NAME).evaluate((el) => {
      el.value = 'aaa\nbbb';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(await page.locator(NAME).inputValue(), '换行被剥离（不落成空格/多行）').toBe('aaabbb');
    // ③ 粘贴超长串：夹到上限
    await page.locator(NAME).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('长'.repeat(500));
    const afterLong = await page.locator(NAME).inputValue();
    expect(afterLong).not.toMatch(/[\r\n]/);
    expect(afterLong.length, 'value.length === maxlength').toBe(maxLen);
    // 名输入面与内容面同视觉厚度（作者：对照 Notes(optional) 那一层）
    expect(Math.abs(g.name.height - g.notes.height), '名输入高度 ≈ 内容输入高度').toBeLessThanOrEqual(3);

    // ── E4 截断可编辑：保存超长名 → 行内 ellipsis 截断；再进编辑值 = 全文 ──
    await page.locator(NAME).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('W'.repeat(300));
    const typedFull = await page.locator(NAME).inputValue();
    await page.keyboard.press('Enter'); // Enter 保存（既有交互）
    await page.waitForTimeout(400);
    await expect(page.locator(`${ROW}.tfl-row-editing`), '保存后编辑面板收起').toHaveCount(0);
    const inline = await page.locator(`${ROW} .tfl-row-title`).evaluate((el) => ({
      scrollWidth: el.scrollWidth, clientWidth: el.clientWidth,
      textOverflow: getComputedStyle(el).textOverflow,
      whiteSpace: getComputedStyle(el).whiteSpace,
      text: el.textContent,
    }));
    expect(inline.text, '行内名称 = 保存的值').toBe(typedFull);
    expect(inline.scrollWidth, '行内名称溢出（scrollWidth > clientWidth）').toBeGreaterThan(inline.clientWidth);
    expect(inline.textOverflow, 'computed text-overflow').toBe('ellipsis');
    expect(inline.whiteSpace).toBe('nowrap');
    // 再次进入编辑：值仍是全文
    await page.locator(`${ROW} .tfl-row-main`).click();
    await page.waitForSelector(PANEL, { timeout: 10000 });
    await page.waitForTimeout(400);
    expect(await page.locator(NAME).inputValue(), '再进编辑值 = 完整原文').toBe(typedFull);
    const nameInputEllipsis = await page.locator(NAME).evaluate((el) => ({
      textOverflow: getComputedStyle(el).textOverflow,
      scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, value: el.value,
    }));
    expect(nameInputEllipsis.textOverflow, '名输入框失焦/溢出时读作截断').toBe('ellipsis');
    expect(nameInputEllipsis.value.length, '输入框 value = 全文').toBe(maxLen);

    // ── E5 保存语义：updateItem 收到 { title, notes } ──
    await page.locator(NAME).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('汇报ppt');
    await page.locator(NOTES).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('Q3 数据 + 三页结论');
    await page.locator(NOTES).press('Enter');
    await page.waitForTimeout(400);
    const patches = await page.evaluate(() => window.__patches);
    const patch = patches[patches.length - 1];
    expect(patch.id).toBe('f-1');
    expect(patch.patch.title).toBe('汇报ppt');
    expect(patch.patch.notes).toBe('Q3 数据 + 三页结论');
    const stored = await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      const it = todoStore.visibleItems().find((x) => x.id === 'f-1');
      return { title: it.title, notes: it.notes, dataSource: todoStore.dataSource };
    });
    expect(stored.title).toBe('汇报ppt');
    expect(stored.notes).toBe('Q3 数据 + 三页结论');
    expect(stored.dataSource, '夹具模式（零后端）').toBe('fixture');

    // ── E6 零教学文案 ──
    await page.waitForTimeout(200);
    await page.locator(`${ROW} .tfl-row-main`).click();
    await page.waitForSelector(PANEL, { timeout: 10000 });
    await page.waitForTimeout(400);
    const copy = await page.evaluate(copyAudit);
    const zh = await page.evaluate(async () => Object.entries((await import('/js/locales/zh-CN.js')).default));
    const allowedList = zh.map(([, v]) => String(v).trim());
    const allowed = new Set(allowedList);
    // locale 值是文案本体，或以「locale 值 + 数据」的模板形态出现
    const isCopy = (s) => allowed.has(s) || allowedList.some((v) => v.length > 0 && s.startsWith(v));
    const offendingText = copy.texts.filter((s) => !isCopy(s));
    const offendingAttr = copy.attrs.filter((s) => !isCopy(s));
    expect(offendingText, '面板内文本节点全部来自既有 locale 键').toEqual([]);
    expect(offendingAttr, 'placeholder / aria-label 全部来自既有 locale 键').toEqual([]);

    save('desktop', {
      E1: { panelTop: g.panel.top, headBottom: g.headBottom, rowWidth: g.row.width, panelWidth: g.panel.width },
      E2: { childOrder: g.childOrder, sameNode: g.sameNode, focusValues: bothValues },
      E3: { tag: g.name.tag, maxLength: maxLen, afterNewline, longLen: afterLong.length, nameH: g.name.height, notesH: g.notes.height },
      E4: { inline: { ...inline, text: `<${inline.text.length} chars>` }, nameInputEllipsis: { ...nameInputEllipsis, value: `<${nameInputEllipsis.value.length} chars>` } },
      E5: { patch: { title: patch.patch.title, notes: patch.patch.notes }, stored, dataSource: stored.dataSource },
      E6: { texts: copy.texts, attrs: copy.attrs },
    });
    await page.screenshot({ path: join(EVID, 'd1440-edit-panel.png') });
    expect(errors).toEqual([]);
  });
});

test.describe('件6 手机 375×812：同一口径单列不横溢', () => {
  test.use({ viewport: { width: 375, height: 812 }, colorScheme: 'light' });

  test('E1/E2/E3/E4 在单列下同样成立', async ({ page }) => {
    await bootTodoFace(page);
    await expect(page.locator('body.mobile-layer')).toHaveCount(1);
    await openEdit(page);

    const g = await page.evaluate(editGeometry);
    expect(g.panel.top).toBeGreaterThanOrEqual(g.headBottom - 1);
    expect(g.name && g.notes, '两个输入面都在').toBeTruthy();
    expect(g.sameNode).toBe(false);
    expect(g.nameInPanel && g.notesInPanel).toBe(true);
    expect(g.childOrder.findIndex((c) => /tfl-edit-name/.test(c)))
      .toBeLessThan(g.childOrder.findIndex((c) => /tfl-edit-notes/.test(c)));
    expect(g.name.tag).toBe('INPUT');
    const maxLen = Number(g.name.maxLength);
    expect(maxLen).toBeGreaterThan(0);

    await page.locator(NAME).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('手机名第二行');
    expect(await page.locator(NAME).inputValue(), '单行写入').toBe('手机名第二行');
    // 带换行的值经输入面（粘贴/自动填充通道）必须被剥离成单行
    await page.locator(NAME).evaluate((el) => {
      el.value = '手机名\n第二行';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(await page.locator(NAME).inputValue(), '换行剥离').toBe('手机名第二行');
    await page.locator(NAME).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText('M'.repeat(500));
    expect((await page.locator(NAME).inputValue()).length).toBe(maxLen);

    await page.keyboard.press('Enter');
    await page.waitForTimeout(400);
    const inline = await page.locator(`${ROW} .tfl-row-title`).evaluate((el) => ({
      scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, textOverflow: getComputedStyle(el).textOverflow,
    }));
    expect(inline.scrollWidth).toBeGreaterThan(inline.clientWidth);
    expect(inline.textOverflow).toBe('ellipsis');
    // 单列：不产生横向滚动
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(376);

    save('mobile', { panelTop: g.panel.top, headBottom: g.headBottom, maxLen, inline });
    await page.screenshot({ path: join(EVID, 'm375-edit-panel.png') });
  });
});
