// todo-island-morph-shots.mjs — 灵动岛批（2026-11-05 作者令 17:02）取证脚本。
//
// 产出（默认落 evidence/20261105_todo-island/shots/，可用 EVID_DIR 覆盖）：
//   · island-desktop.webm  → 由本脚本录屏（Playwright recordVideo，真实指针）
//   · island-mobile.webm
//   · key-1-collapsed.png / key-2-mid.png / key-3-open.png / key-4-operate.png /
//     key-5-collapse-mid.png / key-6-collapsed-again.png（桌面关键帧）
//   · m-entry-before.png / m-entry-after.png（手机端入口：改前/改后对照用）
//   · shots-readings.json（几何 + 过冲 + 节点同一性 读数）
//
// 数据面 = tests/_todo-face-harness.mjs（真实 web 树 + WS 帧桩 + todoStore 夹具；
// 零后端、零 sbt、不碰 :6138）。指针 = Playwright 真实鼠标事件（click/down/move/up）。
//
// Run: node tests/todo-island-morph-shots.mjs

import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace, itemFixture, TAG_FIXTURE } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const OUT = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_todo-island', 'shots');
mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const readings = {};

/** 岛几何 + 内容层状态读数。 */
const islandProbe = () => {
  const isl = document.querySelector('#todo-island');
  const panel = document.querySelector('.tfl-panel');
  const cap = document.querySelector('.tfl-capsule');
  const b = isl.getBoundingClientRect();
  const cs = getComputedStyle(isl);
  return {
    id: isl.id,
    sameAsRef: isl === window.__islandRef,
    rect: { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) },
    centerX: +(b.x + b.width / 2).toFixed(2),
    radius: cs.borderRadius,
    bg: cs.backgroundColor,
    backdrop: cs.backdropFilter,
    state: isl.dataset.state,
    capOpacity: +(+getComputedStyle(cap).opacity).toFixed(3),
    panelOpacity: +(+getComputedStyle(panel).opacity).toFixed(3),
    panelFilter: getComputedStyle(panel).filter,
    panelH: panel.offsetHeight,
    rows: document.querySelectorAll('.tfl-row').length,
    count: (document.querySelector('.tfl-header-count') || {}).textContent || null,
  };
};

async function recordDesktop() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
    recordVideo: { dir: OUT, size: { width: 1440, height: 900 } },
  });
  const page = await ctx.newPage();
  await bootTodoFace(page, { tags: TAG_FIXTURE, items: itemFixture() });
  await sleep(600);
  await page.evaluate(() => { window.__islandRef = document.querySelector('#todo-island'); });
  readings.desktopCollapsed = await page.evaluate(islandProbe);
  await page.screenshot({ path: join(OUT, 'key-1-collapsed.png') });

  // 逐帧采样（点岛前起跑；读岛盒 + 节点同一性）
  await page.evaluate(() => {
    const isl = window.__islandRef;
    const st = { s: [], done: false, idle: 0, last: -1 };
    window.__probe = st;
    const tick = () => {
      const r = isl.getBoundingClientRect();
      st.s.push([+r.width.toFixed(2), +r.height.toFixed(2), +(r.left + r.width / 2).toFixed(2), isl === document.querySelector('#todo-island') ? 1 : 0]);
      if (st.s.length > 240) { st.done = true; return; }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });

  // ① 收起 → 展开（真实点击）
  const capBox = await page.locator('#task-list .tfl-capsule').boundingBox();
  await page.mouse.move(capBox.x + capBox.width / 2, capBox.y + capBox.height / 2);
  await sleep(180);
  await page.mouse.down();
  await sleep(90);
  await page.mouse.up();
  await sleep(110);
  await page.screenshot({ path: join(OUT, 'key-2-mid.png') });
  await sleep(900);
  readings.desktopOpen = await page.evaluate(islandProbe);
  await page.screenshot({ path: join(OUT, 'key-3-open.png') });

  // ② 切换筛选（真实点击筛选键），再点一枚标签 chip
  await page.locator('.tfl-filter-toggle').click();
  await sleep(700);
  await page.locator('.tfl-chip-tag').first().click();
  await sleep(700);
  readings.desktopFiltered = await page.evaluate(islandProbe);

  // ③ 拖拽（真实按下-移动-抬起）横拖 chips 行的滚动
  const track = page.locator('.tfl-chips, .tfl-chip-track').first();
  if (await track.count()) {
    const tb = await track.boundingBox().catch(() => null);
    if (tb) {
      await page.mouse.move(tb.x + tb.width * 0.7, tb.y + tb.height / 2);
      await page.mouse.down();
      for (let i = 1; i <= 8; i++) {
        await page.mouse.move(tb.x + tb.width * (0.7 - i * 0.05), tb.y + tb.height / 2);
        await sleep(16);
      }
      await page.mouse.up();
      await sleep(300);
    }
  }

  // ④ 单条操作：完成一行（真实点击完成钮）—— 计数随之变化
  const row = page.locator('.tfl-row').first();
  const doneBtn = row.locator('.tfl-complete').first();
  if (await doneBtn.count()) {
    await doneBtn.click();
  } else {
    await row.click();
  }
  await sleep(200);
  await page.screenshot({ path: join(OUT, 'key-4-operate.png') });
  await sleep(900);
  readings.desktopOperated = await page.evaluate(islandProbe);

  // ⑤ 收起（真实点击收起键）
  await page.locator('.tfl-collapse-btn').click();
  await sleep(160);
  await page.screenshot({ path: join(OUT, 'key-5-collapse-mid.png') });
  await sleep(900);
  readings.desktopCollapsedAgain = await page.evaluate(islandProbe);
  await page.screenshot({ path: join(OUT, 'key-6-collapsed-again.png') });

  // 过冲读数（采样序列 → 首趟展开的那一段）
  readings.desktopFrames = await page.evaluate(() => {
    const s = window.__probe.s;
    return { frames: s.length, series: s.filter((_, i) => i % 2 === 0) };
  });

  await ctx.close();
  await browser.close();
}

async function recordMobile() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
    colorScheme: 'dark',
    recordVideo: { dir: OUT, size: { width: 375, height: 812 } },
  });
  const page = await ctx.newPage();
  await bootTodoFace(page, { tags: TAG_FIXTURE, items: itemFixture(), mobile: true });
  // 让 8 条未完成待办对齐作者附图的读数
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const now = Date.now();
    const itemList = Array.from({ length: 8 }, (_, i) => ({
      id: 'shot-' + i, tagId: null, name: '待办事项 ' + (i + 1), detail: '',
      due: now + 3600e3 * (i + 1), priority: 1, completed: false, completedAt: null,
      createdAt: now, sessionId: '', project: null,
    }));
    todoStore.__seed({ tagList: [], itemList, order: [] });
  });
  await sleep(700);
  const entry = () => {
    const btn = document.querySelector("[data-chrome='tasks']");
    const badge = document.querySelector('.mh-chrome-badge');
    const idhead = document.querySelector('.mh-idhead');
    const av = document.querySelector('#agent-avatar-btn');
    const search = document.querySelector("[data-chrome='search']");
    const r = (el) => { const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
    return {
      btn: r(btn),
      radius: getComputedStyle(btn).borderRadius,
      badge: badge.hidden ? null : { ...r(badge), text: badge.textContent, radius: getComputedStyle(badge).borderRadius, bg: getComputedStyle(badge).backgroundColor },
      badgeOffset: badge.hidden ? null : { right: +(badge.getBoundingClientRect().right - btn.getBoundingClientRect().right).toFixed(1), top: +(badge.getBoundingClientRect().top - btn.getBoundingClientRect().top).toFixed(1) },
      avatar: av ? r(av) : null,
      searchBtn: search ? r(search) : null,
      idheadTop: idhead ? r(idhead).y : null,
      viewport: { w: innerWidth, h: innerHeight },
    };
  };
  readings.mobileEntry = await page.evaluate(entry);
  await page.screenshot({ path: join(OUT, 'm-entry-after.png') });

  // 圆钮 → 任务页（真实点击）→ 完成一条 → 返回（角标弹簧跟新）
  await page.locator("[data-chrome='tasks']").click();
  await sleep(900);
  await page.screenshot({ path: join(OUT, 'm-task-page.png') });
  const doneBtn = page.locator('#mobile-task-panel .tfl-complete').first();
  await doneBtn.click().catch(() => {});
  await sleep(900);
  await page.locator('.mobile-back-btn').first().click().catch(() => {});
  await sleep(900);
  readings.mobileEntryAfterOp = await page.evaluate(entry);
  await page.screenshot({ path: join(OUT, 'm-entry-badge-after.png') });
  await ctx.close();
  await browser.close();
}

await recordDesktop();
await recordMobile();
writeFileSync(join(OUT, 'shots-readings.json'), JSON.stringify(readings, null, 2));
console.log('shots written to', OUT);
console.log(JSON.stringify({
  desktopCollapsed: readings.desktopCollapsed,
  desktopOpen: readings.desktopOpen,
  mobileEntry: readings.mobileEntry,
  mobileEntryAfterOp: readings.mobileEntryAfterOp,
}, null, 2));
