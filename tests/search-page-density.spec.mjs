// search-page-density.spec.mjs — 搜索页空间瘦身 + 手机层日期滚轮 + 次级浮层可读性
//（search-page-density 批 2026-11-21 作者令①–④ + 追加两条）。
//
// 判据（每条对应作者令的一句）：
//   M1 去重复 ：「全部来源」大下拉框整件退役（`#mh-search-kind` 零节点）；来源筛选
//               只剩 chips 一行（3 chips = 全部来源/用户/助手）；页内可见 select = 0。
//   M2 同行化 ：「选择文件」钮与 chips 同一行（父节点 = #search-filter-row、
//               纵向中心差 ≤ 6px、横向落在行右端）。
//   M3 打开钮  ：「打开所选文件 (N)」未选中不出现；选中后以底部悬浮形态出现
//               （position:absolute + 不占结果流布局 + 结果流补底部内距）。
//   M4 计数行   ：计数行收到最小字号单行；整块筛选 chrome（controls + status）
//               竖向开销 ≤ 150px（改前 ≈ 214px，读数见批报告）。
//   M5 来源筛选生效面：chips 在「全部」tab 也真的筛（用户 ⇒ 只剩 user 行），
//               计数随筛选走（主会话 · 1 条结果）。
//   M6 手机层日期滚轮：点「日期」tab ⇒ `#search-date-wheel`（3 列滚轮）出现、
//               旧日历网格/年月下拉**不在**；拨列 ⇒ 值变；确定 ⇒ 日期 tab 落锚。
//   D1 桌面零漂移：`.search-filters` 行仍在场、`#search-select-files` 不在 chips 行、
//               chips 仍只在类型网格档出现。
//   D2 次级浮层可读（作者追加②）：日历年/月下拉与筛选动作表菜单的底色 = 压不透
//               （computed alpha = 1），下层日历数字不再穿透。
//
// 零后端 harness（与 tests/mobile-p2-search.spec.mjs 同族）：
//   page.route 从磁盘喂 web/ 源树 + routeWebSocket 帧桩。
// Run: node node_modules/@playwright/test/cli.js test tests/search-page-density.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'spd-root-session';
const T0 = Date.now();
const KW = '密度';

/** 会话历史夹具：user / ai 各一条含关键词（来源筛选的两档素材）+ 一条**文件**
 *  附件行（文件档的网格素材 —— 手机层多选打开腿需要可打开的文件条目）。 */
function historyFixture() {
  return { messages: [
    { type: 'user', text: `${KW} 检查一下这批的空间开销`, timestamp: T0 - 60_000,
      attachments: [{ type: 'file', name: 'density-note.md', path: '/tmp/spd/density-note.md' }] },
    { type: 'ai', text: `${KW} 相关结论在下面：筛选行合并后省下 100px 上下。`, timestamp: T0 - 40_000 },
    { type: 'user', text: '另外一条无关消息', timestamp: T0 - 20_000 },
  ] };
}

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === `/api/sessions/${SID}/history`) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(historyFixture()) });
    }
    if (p === '/api/sessions') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
      });
    }
    // 智能体身份卡（头像/显示名）：缺这条 = 两条 404 console error（既有 spec 同法供）。
    if (/^\/agents\/[^/]+\/agent\.json$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Nebula' }) });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

/** 夹具噪声清除：桩后端没有真正的 Canvas 保存通道 ⇒ 打开搜索面上方常驻一条
 *  「Canvas 保存失败」信息 toast（z 10000，压住滚轮的确定钮）。它与被测面**无关**
 *  （改前改后同一现象，见 tests/mobile-p2-search.spec.mjs 同源夹具不点滚轮故未暴露），
 *  这里按夹具噪声处理：只清 toast 节点，不动任何被测面。 */
async function clearToasts(page) {
  await page.evaluate(() => document.querySelectorAll('.nebflow-toast').forEach((el) => el.remove()));
}

async function openMobileSearch(page) {
  await page.locator('[data-chrome="search"]').click();
  await expect(page.locator('.mobile-page[data-page="search"]')).toHaveClass(/live/);
  await expect(page.locator('#search-overlay')).toHaveClass(/on/);
  await expect(page.locator('#search-modal')).toBeVisible();
  await settlePage(page, 'search');
}

test.describe('手机层 390×844（作者令①②③④ + 日期滚轮）', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'light' });

  test('M1 去重复：大下拉框退役，来源筛选只剩 chips 一行', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await openMobileSearch(page);

    // 作者令①：「删掉下拉框，来源筛选只用小标签 chips（全部来源/用户/助手），保留一行。」
    await expect(page.locator('#mh-search-kind')).toHaveCount(0);
    await expect(page.locator('#mh-search-kind-row')).toHaveCount(0);

    const chips = page.locator('#search-filter-row #search-source-chips .search-source-chip');
    await expect(chips).toHaveCount(3);
    const labels = await chips.allTextContents();
    expect(labels).toEqual(['全部来源', '用户', '助手']);
    await expect(page.locator('#search-filter-row')).toBeVisible();

    // 页内可见 select = 0（来源/类型筛选不再有原生下拉占位）。
    const visibleSelects = await page.evaluate(() => [...document.querySelectorAll('#search-modal select')]
      .filter((el) => {
        const r = el.getBoundingClientRect();
        const st = getComputedStyle(el);
        return st.display !== 'none' && st.visibility !== 'hidden' && r.width > 0 && r.height > 0;
      }).length);
    expect(visibleSelects).toBe(0);

    // 只有一行筛选（chips 行），没有第二筛选行。
    expect(await page.evaluate(() => document.querySelectorAll('#search-modal .search-controls > div:not([hidden])').length)).toBeGreaterThan(0);
    expect(errors).toEqual([]);
  });

  test('M2 同行化：「选择文件」与 chips 同一行（紧凑钮，右端）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await openMobileSearch(page);
    await page.locator('#search-tab-files').click();
    await page.waitForTimeout(200);

    const btn = page.locator('#search-select-files');
    await expect(btn).toBeVisible();
    const geo = await page.evaluate(() => {
      const row = document.getElementById('search-filter-row');
      const b = document.getElementById('search-select-files');
      const chip = row?.querySelector('.search-source-chip');
      const rr = row.getBoundingClientRect();
      const br = b.getBoundingClientRect();
      const cr = chip.getBoundingClientRect();
      return {
        parentId: b.parentElement?.id,
        rowCenterY: rr.top + rr.height / 2,
        btnCenterY: br.top + br.height / 2,
        chipCenterY: cr.top + cr.height / 2,
        btnRightGap: Math.round(rr.right - br.right),
        btnH: Math.round(br.height),
        btnW: Math.round(br.width),
      };
    });
    expect(geo.parentId).toBe('search-filter-row');            // 作者令②：同一行
    expect(Math.abs(geo.btnCenterY - geo.chipCenterY)).toBeLessThanOrEqual(6);
    expect(geo.btnRightGap).toBeLessThanOrEqual(20);            // 行右端
    expect(geo.btnH).toBeLessThanOrEqual(32);                   // 紧凑档（非 46px 大按钮）
    expect(errors).toEqual([]);
  });

  test('M3 打开钮：未选中不出现，选中后底部悬浮条（零布局高度）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await openMobileSearch(page);
    await page.locator('#search-tab-files').click();
    await page.waitForTimeout(300);

    const bar = page.locator('#search-open-selected-files');
    await expect(bar).toBeHidden();                             // 未选中 ⇒ 不显示

    await page.locator('#search-select-files').click();          // 进入选择态
    const card = page.locator('#search-results .search-media-card').first();
    await expect(card).toBeVisible();
    await card.click();
    await expect(bar).toBeVisible({ timeout: 5000 });            // 选中 ⇒ 出现
    await expect(bar).toHaveText(/打开所选文件 \(1\)/);

    const shape = await page.evaluate(() => {
      const b = document.getElementById('search-open-selected-files');
      const r = b.getBoundingClientRect();
      const modal = document.getElementById('search-modal').getBoundingClientRect();
      const results = document.getElementById('search-results');
      return {
        position: getComputedStyle(b).position,
        radius: getComputedStyle(b).borderRadius,
        bottomGap: Math.round(modal.bottom - r.bottom),
        height: Math.round(r.height),
        resultsPadBottom: parseFloat(getComputedStyle(results).paddingBottom),
        floatsOverResults: r.top < results.getBoundingClientRect().bottom,
      };
    });
    expect(shape.position).toBe('absolute');                     // 悬浮（不占流）
    expect(shape.floatsOverResults).toBe(true);
    expect(shape.height).toBeLessThanOrEqual(44);                // 紧凑胶囊
    expect(shape.resultsPadBottom).toBeGreaterThan(0);           // 末行不被压住
    expect(errors).toEqual([]);
  });

  test('M4 计数行压缩：最小字号单行 + 筛选 chrome 竖向开销 ≤ 150px', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await openMobileSearch(page);
    await page.locator('#search-keyword').fill(KW);
    await expect(page.locator('#search-status')).toHaveText(/^主会话 · 2 条结果/, { timeout: 8000 });

    const m = await page.evaluate(() => {
      const status = document.getElementById('search-status');
      const controls = document.querySelector('#search-modal .search-controls');
      const r = status.getBoundingClientRect();
      const c = controls.getBoundingClientRect();
      return {
        statusFontSize: parseFloat(getComputedStyle(status).fontSize),
        statusLines: Math.round(r.height / parseFloat(getComputedStyle(status).lineHeight || '1')),
        statusH: Math.round(r.height),
        chromeH: Math.round(c.height + r.height),
        kindRowPresent: !!document.getElementById('mh-search-kind-row'),
      };
    });
    expect(m.kindRowPresent).toBe(false);
    // 字号 = --mfs-min（13px，不是 11px）：`#search-status` 在手机层「H2 可读字
    // 下限（≥13px）」铁律清单里（css/mobileLayer.css「可读字下限」块逐字点名
    // `#search-status`）。作者令④是「小字号或并入筛选行」，不是破可读字下限
    // ⇒ 压缩落在**行盒**（padding / line-height / 零额外行），字号守住 13。
    expect(m.statusFontSize).toBeGreaterThanOrEqual(13);
    expect(m.statusFontSize).toBeLessThanOrEqual(14);            // 不回到正文档
    expect(m.statusH).toBeLessThanOrEqual(22);                   // 单行盒
    // 改前 = controls(含下拉行 40) + status ≈ 214px；改后阈值留 150。
    expect(m.chromeH).toBeLessThanOrEqual(150);
    expect(errors).toEqual([]);
  });

  test('M5 来源筛选在「全部」tab 真的生效（计数随筛选走）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await openMobileSearch(page);
    await page.locator('#search-keyword').fill(KW);
    const rows = page.locator('#search-results .search-result');
    await expect(rows).toHaveCount(2, { timeout: 8000 });

    await page.locator('#search-filter-row .search-source-chip[data-source="user"]').click();
    await expect(rows).toHaveCount(1, { timeout: 8000 });
    await expect(rows.first()).toHaveAttribute('data-kind', 'user');
    await expect(page.locator('#search-status')).toHaveText(/^主会话 · 1 条结果/);

    await page.locator('#search-filter-row .search-source-chip[data-source="agent"]').click();
    await expect(rows).toHaveCount(1, { timeout: 8000 });
    await expect(rows.first()).toHaveAttribute('data-kind', 'ai');

    await page.locator('#search-filter-row .search-source-chip[data-source=""]').click();
    await expect(rows).toHaveCount(2, { timeout: 8000 });
    expect(errors).toEqual([]);
  });

  test('M6 手机层日期滚轮：三列 wheel 取代日历+下拉混搭（复用全站滚轮单源）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await openMobileSearch(page);

    await page.locator('#search-tab-date').click();
    await clearToasts(page);
    const sheet = page.locator('.tw-panel.sheet');
    await expect(sheet).toBeVisible({ timeout: 5000 });
    // 旧形态整件不在：日历网格 / 年·月 pill / 下拉表 都不在这块面板里。
    await expect(page.locator('.tw-panel .cal-grid')).toHaveCount(0);
    await expect(page.locator('.tw-panel .cal-pill')).toHaveCount(0);
    await expect(page.locator('#search-date-popover')).toBeHidden();

    // 三列滚轮：年 / 月 / 日（复用 js/timeWheel.js 的 Wheel —— 单源纪律）。
    const dateCols = page.locator('.tw-body-date .tw-col');
    await expect(dateCols).toHaveCount(3);
    const roles = await dateCols.evaluateAll((els) => els.map((el) => el.dataset.role));
    expect(roles).toEqual(['dy', 'dm', 'dd']);
    const items = await page.evaluate(() => ({
      years: document.querySelectorAll('.tw-col[data-role="dy"] .tw-item').length,
      months: document.querySelectorAll('.tw-col[data-role="dm"] .tw-item').length,
      days: document.querySelectorAll('.tw-col[data-role="dd"] .tw-item').length,
      // 值集 × 3 份环形（Wheel 的 REP=3）。
      rep: 3,
    }));
    expect(items.months).toBe(12 * items.rep);
    // 年列 ≥3 档：历史集中在单一年度时（夹具/新装会话）年列只有 1 个值 ⇒ 环形
    // 滚轮把同一个年号铺成三行，读起来像渲染坏了（本批证据期实拍抓出，已修）。
    expect(items.years).toBeGreaterThanOrEqual(3 * items.rep);
    expect(items.days).toBeGreaterThanOrEqual(28 * items.rep);

    // 开面板即今天（无锚）且三列**互相对齐**：本批证据期实拍抓出重入缺陷 ——
    // setDateWheels 逐列写，写年列触发的 emit 读到还没落位的月列（idx 0）⇒
    // datePick.m0 被改写成 0 ⇒ 每次开面板月份跳到 1 月、日列随之按 1 月重铺。
    const now3 = await page.evaluate(() => {
      const g = (r) => document.querySelector(`.tw-col[data-role="${r}"]`).getAttribute('aria-valuetext');
      return { y: g('dy'), m: g('dm'), d: g('dd') };
    });
    const today = new Date();
    expect(now3.y).toContain(String(today.getFullYear()));
    expect(now3.m).toContain(`${today.getMonth() + 1}月`);
    expect(now3.d).toContain(String(today.getDate()));
    // aria 值串不重复单位（zh 月档值串自带「月」⇒ 不得读成「11月 月」）。
    expect(now3.m).not.toMatch(/月\s*月/);

    // 幕布：弹层展开即遮罩下层内容（作者令②「遮罩下层内容」那一支，手机端落点）。
    const scrim = page.locator('.tw-scrim.in');
    await expect(scrim).toBeVisible();
    const scrimStyle = await scrim.evaluate((el) => ({
      backdrop: getComputedStyle(el).backdropFilter || getComputedStyle(el).webkitBackdropFilter,
      inset0: (() => { const r = el.getBoundingClientRect(); return r.top === 0 && r.left === 0; })(),
    }));
    expect(scrimStyle.inset0).toBe(true);
    expect(scrimStyle.backdrop).toMatch(/blur/);

    // 面本体的底色 = 压不透（下层内容不参与合成）。
    const panelBg = await sheet.evaluate((el) => getComputedStyle(el).backgroundColor);
    const alphaOf = (bg) => {
      const m = /rgba?\(([^)]+)\)/.exec(bg);
      if (!m) return 1;
      const parts = m[1].split(',').map((s) => parseFloat(s));
      return parts.length === 4 ? parts[3] : 1;
    };
    expect(alphaOf(panelBg)).toBe(1);

    // 拨月列（滚动 = 值变 ⇒ 年/月联动重铺日列）。
    // 向上拨 = 走向更早的月份：日期锚的值域上界是**今天**，ArrowDown 会撞到
    // 本月即上限的收口（clampDatePick 原样收回到本月 ⇒ 值不变是正确行为）。
    const before = await page.evaluate(() => document.querySelector('.tw-col[data-role="dm"]').getAttribute('aria-valuetext'));
    await page.evaluate(() => {
      const col = document.querySelector('.tw-col[data-role="dm"]');
      col.focus();
      col.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    });
    await page.waitForTimeout(400);
    const after = await page.evaluate(() => document.querySelector('.tw-col[data-role="dm"]').getAttribute('aria-valuetext'));
    expect(after).not.toBe(before);

    // 确定 ⇒ 日期 tab 落锚（标签换成日期 + × 清除符），滚轮收场。
    await page.locator('.tw-panel .tw-confirm').click();
    await expect(sheet).not.toBeVisible();
    await expect(scrim).not.toBeVisible();
    const tabText = await page.locator('#search-tab-date').textContent();
    expect(tabText).not.toBe('日期');
    await expect(page.locator('#search-tab-date .search-date-clear')).toHaveCount(1);
    await expect(page.locator('#search-tab-date')).toHaveAttribute('aria-selected', 'true');
    expect(errors).toEqual([]);
  });
});

test.describe('桌面（零漂移 + 次级浮层可读性）', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  test('D1 桌面零漂移：筛选三 select 仍在、选择文件钮不在 chips 行、chips 只在类型档', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#search-btn').click();
    await expect(page.locator('#search-overlay')).toHaveClass(/on/);
    await page.waitForTimeout(200);

    await expect(page.locator('#search-modal .search-filters')).toBeVisible();
    await expect(page.locator('#search-scope')).toBeVisible();
    // 「全部」tab：chips 行不在场（桌面由三枚 select 负责来源/范围）。
    await expect(page.locator('#search-filter-row')).toBeHidden();
    // 「选择文件」钮不搬进 chips 行（手机层专属归位）。
    expect(await page.evaluate(() => document.getElementById('search-select-files')?.parentElement?.id || ''))
      .not.toBe('search-filter-row');
    // 类型档出现 chips（改前行为）。
    await page.locator('#search-tab-files').click();
    await page.waitForTimeout(200);
    await expect(page.locator('#search-filter-row')).toBeVisible();
    expect(errors).toEqual([]);
  });

  test('D2 次级浮层压不透：日历年月下拉 + 筛选动作表菜单底色 alpha = 1', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#search-btn').click();
    await expect(page.locator('#search-overlay')).toHaveClass(/on/);
    await page.waitForTimeout(200);

    // 日历年/月下拉（作者截图证据那一面）。
    await page.locator('#search-tab-date').click();
    await expect(page.locator('#search-date-popover')).toBeVisible();
    await page.locator('#search-date-popover .cal-pill[data-pill="month"]').click();
    const listboxBg = await page.locator('.cal-pill-listbox').evaluate((el) => getComputedStyle(el).backgroundColor);
    const alphaOf = (bg) => {
      const m = /rgba?\(([^)]+)\)/.exec(bg);
      if (!m) return null;
      const parts = m[1].split(',').map((s) => parseFloat(s));
      return parts.length === 4 ? parts[3] : 1;
    };
    expect(alphaOf(listboxBg)).toBe(1);          // 压不透 ⇒ 下层数字不参与合成
    await page.keyboard.press('Escape');

    // 筛选动作表菜单（桌面 body 直挂）。
    await page.locator('#search-tool').click();
    await expect(page.locator('.search-filter-menu')).toBeVisible();
    const menuBg = await page.locator('.search-filter-menu').evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(alphaOf(menuBg)).toBe(1);
    expect(errors).toEqual([]);
  });
});
