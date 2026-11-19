// todo-island-mobile-entry-shots.mjs — 手机端「待办入口 + 未完成数角标」A/B 取证腿。
//
// 作者附图（2026-11-05 17:02）要求：手机端待办入口 = **左上角圆形钮**（清单+勾图标）
// + **右上角数字角标（未完成数）**。本脚本在同一份夹具（8 条未完成待办 + **非 Nebula
// 的 agent 会话 = Spider**，与附图同语境）下拍两张对照图：
//   SHOT_TAG=before（旧读数口）/ after（本批修复后）
// 并对角标存在性、落位像素、直径做机械读数，落 mobile-entry-<tag>.json。
//
// 改前腿的跑法（旧树 = 干净 main 导出）：
//   TODOFACE_WEB_ROOT=/tmp/main-clean/src/main/resources/web SHOT_TAG=before \
//     node tests/todo-island-mobile-entry-shots.mjs
// 改后腿（本分支）：
//   SHOT_TAG=after node tests/todo-island-mobile-entry-shots.mjs
//
// 数据面 = tests/_todo-face-harness.mjs（真实 web 树 + WS 帧桩 + todoStore 夹具；
// 零后端、零 sbt、不碰 :6138）。指针 = Playwright 真实点击。

import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace, itemFixture, TAG_FIXTURE } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const TAG = process.env.SHOT_TAG || 'after';
const OUT = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_todo-island', 'shots');
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
  colorScheme: 'dark',
});
const page = await ctx.newPage();
await bootTodoFace(page, { tags: TAG_FIXTURE, items: itemFixture(), mobile: true });

// 与作者附图同语境：会话 agent 不是 Nebula（= Spider），8 条未完成待办。
await page.evaluate(async () => {
  const st = (await import('/js/state.js')).default;
  const sid = st.activeSessionId;
  if (!st.sessionAgentMap) st.sessionAgentMap = {};
  st.sessionAgentMap[sid] = 'Spider';
  const { todoStore } = await import('/js/todoStore.js');
  const now = Date.now();
  const itemList = Array.from({ length: 8 }, (_, i) => ({
    id: 'ab-' + i, tagId: null, name: '待办事项 ' + (i + 1), detail: '',
    due: now + 3600e3 * (i + 1), priority: 1, completed: false, completedAt: null,
    createdAt: now, sessionId: '', project: null,
  }));
  todoStore.__seed({ tagList: [], itemList, order: [] });
  window.dispatchEvent(new Event('nebflow-session-change'));
});
await sleep(800);

const read = await page.evaluate(async () => {
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
  const btn = document.querySelector("[data-chrome='tasks']");
  const badge = document.querySelector('.mh-chrome-badge');
  const av = document.querySelector('#agent-avatar-btn');
  const search = document.querySelector("[data-chrome='search']");
  const idhead = document.querySelector('.mh-idhead');
  const store = await import('/js/todoStore.js');
  return {
    sessionAgent: 'Spider',
    viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
    btn: r(btn),
    btnRadius: btn ? getComputedStyle(btn).borderRadius : null,
    btnIcon: btn && btn.querySelector('svg') ? btn.querySelector('svg').getAttribute('data-lucide') : null,
    badgeVisible: !!(badge && !badge.hidden && getComputedStyle(badge).display !== 'none'),
    badge: r(badge),
    badgeText: badge ? badge.textContent : null,
    badgeOffset: badge && !badge.hidden ? {
      rightOfButton: +(badge.getBoundingClientRect().right - btn.getBoundingClientRect().right).toFixed(1),
      topOfButton: +(badge.getBoundingClientRect().top - btn.getBoundingClientRect().top).toFixed(1),
    } : null,
    badgeFont: badge ? getComputedStyle(badge).fontSize : null,
    badgeBg: badge ? getComputedStyle(badge).backgroundColor : null,
    avatar: r(av),
    searchBtn: r(search),
    idheadTop: idhead ? r(idhead).y : null,
    avatarCenterX: av ? +(av.getBoundingClientRect().x + av.getBoundingClientRect().width / 2).toFixed(1) : null,
    buttonCenterY: btn ? +(btn.getBoundingClientRect().y + btn.getBoundingClientRect().height / 2).toFixed(1) : null,
    avatarCenterY: av ? +(av.getBoundingClientRect().y + av.getBoundingClientRect().height / 2).toFixed(1) : null,
    // 真源读数（旧读数口 taskPanel.taskBadgeCount 随模块退役移除）
    openCount_store: store.todoStore.openCount(),
  };
});
writeFileSync(join(OUT, `mobile-entry-${TAG}.json`), JSON.stringify(read, null, 2));
await page.screenshot({ path: join(OUT, `m-entry-${TAG}.png`) });
await page.screenshot({ path: join(OUT, `m-entry-${TAG}-topbar.png`), clip: { x: 0, y: 0, width: 375, height: 120 } });
console.log(JSON.stringify({ tag: TAG, ...read }, null, 2));
await browser.close();
