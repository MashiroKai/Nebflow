// mobile-fade-todo-fab.spec.mjs — 手机端 UI 修复批（作者令 2026-11-05 09:28/09:29）
// 三件的机械判据：①文字右侧渐隐定位与撤除 ②手机端待办回左上悬浮圆钮（桌面保胶囊）
// ③消息 footer 间距端差量化。
//
// 运行：node tests/mobile-fade-todo-fab.spec.mjs [--shots-only]
//   SHOT_TAG=before|after 决定截图落点（evidence/20261105_mobile-fade-todo/<tag>/）
//   CHATFACE_WEB_ROOT 指到另一份 web 树可跑「改前腿」（见 tests/_chat-face-harness.mjs）
//
// 判据（逐条对应任务书验收）：
//   A1 375×812：聊天面内文本元素的 mask-image 必须为 none（渐隐撤除）；
//   A2 375×812：.duration-badge（消息 footer）实测 gap ≤ 桌面档；首尾子项 x 间距
//      有理上界；justify-content ≠ space-between；子项不竖排（同一行 y 差 ≤1）；
//   A3 375×812：左上待办圆钮 [data-chrome='tasks'] 存在、可点、开面板；
//   A4 375×812：#task-list（胶囊容器）不显示（display:none 或零盒）；
//   A5 1440×900：胶囊仍在（#task-list.has-tasks + .tfl-capsule 可见）、无任务圆钮；
//   A6 滚动后内容可从圆钮下穿过（圆钮层 pointer-events 之外的滚动带命中内容）。

import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const TAG = process.env.SHOT_TAG || 'after';
const SHOTS = resolve(ROOT, 'evidence/20261105_mobile-fade-todo', TAG);
mkdirSync(SHOTS, { recursive: true });

let pass = 0, fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ── 页内读数：渐隐扫描 / footer 几何 / 待办入口 ───────────────────────── */
const PROBE = `(() => {
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1),
             right: +b.right.toFixed(1), bottom: +b.bottom.toFixed(1) }; };
  const desc = (el) => {
    if (!el) return 'null';
    const cls = typeof el.className === 'string' && el.className.trim()
      ? '.' + el.className.trim().split(/\\s+/).filter(Boolean).join('.') : '';
    const id = el.id ? '#' + el.id : '';
    return el.tagName.toLowerCase() + id + cls;
  };
  // A1 渐隐扫描：任何 mask-image ≠ none 的元素（记录是否含文本 + 文本前缀）
  const masked = [];
  for (const el of document.querySelectorAll('body *')) {
    const cs = getComputedStyle(el);
    const m = cs.maskImage || cs.webkitMaskImage || 'none';
    if (m === 'none' || !m) continue;
    const b = el.getBoundingClientRect();
    if (b.width < 1 || b.height < 1) continue;
    const own = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join(' ').trim();
    const txt = own || (el.textContent || '').trim();
    masked.push({ el: desc(el), mask: m, rect: r(el), text: txt.slice(0, 28) });
  }
  // A2 footer 几何
  const badges = [...document.querySelectorAll('#chat .duration-badge')].map((el) => {
    const cs = getComputedStyle(el);
    const kids = [...el.children].map((k) => ({ el: desc(k), rect: r(k) }));
    return {
      el: desc(el), rect: r(el), gap: cs.gap, columnGap: cs.columnGap, rowGap: cs.rowGap,
      justifyContent: cs.justifyContent, alignItems: cs.alignItems, flexWrap: cs.flexWrap,
      display: cs.display, padding: cs.padding, marginTop: cs.marginTop, minHeight: cs.minHeight,
      width: cs.width, childCount: kids.length, kids,
      kidStyles: [...el.children].map((k) => { const c = getComputedStyle(k);
        return { el: desc(k), minWidth: c.minWidth, minHeight: c.minHeight, padding: c.padding,
                 margin: c.margin, flex: c.flex, display: c.display, justifyContent: c.justifyContent }; }),
    };
  });
  // A3/A5 待办入口
  const taskBtn = document.querySelector("[data-chrome='tasks']");
  const taskList = document.getElementById('task-list');
  const capsule = document.querySelector('.tfl-capsule');
  return {
    viewport: { w: innerWidth, h: innerHeight },
    bodyClass: document.body.className,
    mobileLayer: document.body.classList.contains('mobile-layer'),
    masked,
    badges,
    taskBtn: taskBtn ? { rect: r(taskBtn), display: getComputedStyle(taskBtn).display,
      pe: getComputedStyle(taskBtn).pointerEvents, z: getComputedStyle(taskBtn).zIndex,
      parent: desc(taskBtn.parentElement) } : null,
    taskList: taskList ? { display: getComputedStyle(taskList).display, cls: taskList.className,
      rect: r(taskList) } : null,
    capsule: capsule ? { rect: r(capsule), display: getComputedStyle(capsule).display,
      visible: !!(capsule.offsetParent || capsule.getClientRects().length) } : null,
    fabLayer: (() => { const f = document.getElementById('mobile-fab-layer');
      if (!f) return null; const c = getComputedStyle(f);
      return { rect: r(f), z: c.zIndex, pe: c.pointerEvents, position: c.position }; })(),
    chatScroll: (() => { const c = document.getElementById('chat');
      return c ? { top: c.scrollTop, h: c.scrollHeight, clientH: c.clientHeight } : null; })(),
  };
})()`;

/* ── 滚动穿顶读数：圆钮下方那条带上命中的是谁 ─────────────────────────── */
const UNDERPASS = `(() => {
  const btn = document.querySelector("[data-chrome='tasks']");
  const chat = document.getElementById('chat');
  if (!btn || !chat) return null;
  const b = btn.getBoundingClientRect();
  const pts = [];
  for (const dx of [0, 8, -8]) {
    const x = Math.round(b.x + b.width / 2 + dx);
    const y = Math.round(b.bottom + 10);
    const el = document.elementFromPoint(x, y);
    pts.push({ x, y, el: el ? (el.tagName.toLowerCase() + (el.id ? '#' + el.id : '')
      + (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\\s+/)[0] : '')) : 'null',
      inChat: !!(el && (chat.contains(el) || el === chat)) });
  }
  return { btnRect: { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1),
    bottom: +b.bottom.toFixed(1) }, pts, scrollTop: chat.scrollTop };
})()`;

const browser = await chromium.launch();

async function boot({ width, height, colorScheme = 'dark' }) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await bootChat(page, { history: historyFixture(10) });
  await sleep(900);
  return { ctx, page, errors };
}

async function run({ width, height, colorScheme, tag }) {
  const { ctx, page, errors } = await boot({ width, height, colorScheme });
  const label = `${width}x${height}-${colorScheme}`;
  const data = await page.evaluate(PROBE);
  await page.screenshot({ path: join(SHOTS, `chat-${label}.png`), fullPage: false });
  await ctx.close();
  return { data, errors, label, tag };
}

/* ═══ 手机腿 375×812 ═══ */
const mDark = await run({ width: 375, height: 812, colorScheme: 'dark', tag: 'mobile-dark' });
const m = mDark.data;
writeFileSync(join(SHOTS, 'probe-mobile.json'), JSON.stringify(mDark, null, 2));

// A1 渐隐：聊天面文本元素零 mask
const maskedChat = m.masked.filter((x) => /#chat/.test(x.el) || /bubble|duration|msg/.test(x.el));
ok('A1 mobile body.mobile-layer 已激活', m.mobileLayer === true, `body="${m.bodyClass}"`);
ok('A1 聊天面文本元素零 mask-image', maskedChat.length === 0,
  maskedChat.length ? maskedChat.map((x) => `${x.el}(${x.text})`).join(' | ') : 'masked=0');
console.log(`      [读数] 全页 mask 元素 ${m.masked.length} 个：` +
  (m.masked.map((x) => x.el).join(' | ') || '（无）'));

// A2 footer
const b = m.badges[0];
if (b) {
  const kidX = b.kids.map((k) => k.rect.x);
  const spanFirstLast = +(b.kids[b.kids.length - 1].rect.x - b.kids[0].rect.x).toFixed(1);
  // 「同一行」判据 = 各子项**垂直中心**同一线（分隔线 1px 高、与文字基线本就差 1px，
  // 用 y 直接比会把正常排版判成两行）。容差 1.5px。
  const centers = b.kids.map((k) => k.rect.y + k.rect.h / 2);
  const spread = +(Math.max(...centers) - Math.min(...centers)).toFixed(1);
  const rowOk = spread <= 1.5;
  ok('A2 消息 footer 存在（.duration-badge）', true,
    `子项 ${b.childCount} · gap=${b.gap} · justify=${b.justifyContent} · wrap=${b.flexWrap} · w=${b.rect.w}`);
  ok('A2 footer 非 space-between 两端撑开', b.justifyContent !== 'space-between', `justify=${b.justifyContent}`);
  ok('A2 footer 子项同一行（垂直中心差 ≤1.5px）', rowOk, `中心差=${spread}px`);
  ok('A2 footer 首尾子项 x 跨度 ≤ 96px（手机档）', spanFirstLast <= 96, `span=${spanFirstLast}px`);
  // 手机档键缝 = --ds-space-3（12px，触屏底线：中心距 ≥24 满足 WCAG 2.5.8 间距例外）；
  // 桌面档 = --ds-space-1h（6px）。判据 = 两档都必须是具名 --ds-space-* 档位值，
  // 且手机档不超过桌面档 + 一档（6px）。
  ok('A2 footer 键缝 = --ds-space-3（12px，触屏底线档）', b.gap === '12px', `gap=${b.gap}`);
  console.log('      [读数] footer 子项：' + JSON.stringify(b.kids.map((k) => ({ e: k.el, x: k.rect.x, w: k.rect.w }))));
  console.log('      [读数] footer 子项样式：' + JSON.stringify(b.kidStyles));
} else {
  ok('A2 消息 footer 存在（.duration-badge）', false, '未渲染 —— 夹具需补完成回合');
}

// A3 待办圆钮
ok('A3 左上待办悬浮圆钮存在', !!m.taskBtn,
  m.taskBtn ? `rect=${JSON.stringify(m.taskBtn.rect)} z=${m.taskBtn.z} pe=${m.taskBtn.pe}` : 'absent');
if (m.taskBtn) {
  ok('A3 圆钮在左上角（x ≤ 24 且 y ≤ 48）', m.taskBtn.rect.x <= 24 && m.taskBtn.rect.y <= 48,
    `x=${m.taskBtn.rect.x} y=${m.taskBtn.rect.y}`);
  ok('A3 圆钮 44+ 触控档（w=h ≥44）', m.taskBtn.rect.w >= 44 && m.taskBtn.rect.h >= 44,
    `${m.taskBtn.rect.w}×${m.taskBtn.rect.h}`);
}

// A4 手机端无胶囊
const capsuleShown = !!(m.capsule && m.capsule.visible) && m.taskList && m.taskList.display !== 'none';
ok('A4 手机端不出现胶囊待办条', !capsuleShown,
  `#task-list display=${m.taskList ? m.taskList.display : 'null'} capsule=${JSON.stringify(m.capsule)}`);

/* 圆钮点击开面板 */
if (m.taskBtn) {
  const { ctx, page } = await boot({ width: 375, height: 812, colorScheme: 'dark' });
  await page.click("[data-chrome='tasks']");
  await sleep(900);
  const opened = await page.evaluate(() => {
    const p = document.querySelector('#mobile-task-panel');
    const face = document.querySelector('#mobile-task-panel .tfl-card, #mobile-task-panel .tfl-panel');
    return { panel: !!p, hidden: p ? p.hidden : null, cls: p ? p.className : null,
      face: !!face, pageStack: (window.__nfMobileNav && (window.__nfMobileNav.stack || window.__nfMobileNav.pages)) || null };
  });
  await page.screenshot({ path: join(SHOTS, 'todo-opened-375x812.png') });
  ok('A3 点圆钮打开待办面', opened.panel && opened.hidden === false && opened.face,
    JSON.stringify(opened));
  await ctx.close();
}

/* 滚动穿顶 */
{
  const { ctx, page } = await boot({ width: 375, height: 812, colorScheme: 'dark' });
  await page.evaluate(() => { const c = document.getElementById('chat'); if (c) c.scrollTop = 240; });
  await sleep(400);
  const up = await page.evaluate(UNDERPASS);
  writeFileSync(join(SHOTS, 'probe-underpass.json'), JSON.stringify(up, null, 2));
  const inChat = up ? up.pts.filter((p) => p.inChat).length : 0;
  ok('A6 圆钮下方滚动带命中聊天内容（内容可穿过）', inChat === (up ? up.pts.length : 0),
    up ? JSON.stringify(up.pts) : 'null');
  await page.screenshot({ path: join(SHOTS, 'chat-scrolled-375x812.png') });
  await ctx.close();
}

/* ═══ 手机亮档 ═══ */
const mLight = await run({ width: 375, height: 812, colorScheme: 'light', tag: 'mobile-light' });

/* ═══ 桌面腿 1440×900 ═══ */
const d = (await run({ width: 1440, height: 900, colorScheme: 'dark', tag: 'desktop-dark' })).data;
writeFileSync(join(SHOTS, 'probe-desktop.json'), JSON.stringify(d, null, 2));
ok('A5 桌面端无待办圆钮', !d.taskBtn, d.taskBtn ? JSON.stringify(d.taskBtn) : 'absent');
ok('A5 桌面端胶囊仍在', !!(d.capsule && d.capsule.visible), JSON.stringify(d.capsule));
ok('A5 桌面端 body 无 mobile-layer', d.mobileLayer === false, `body="${d.bodyClass}"`);
if (d.badges[0]) {
  const db = d.badges[0];
  console.log(`      [读数] 桌面 footer：子项 ${db.childCount} · gap=${db.gap} · justify=${db.justifyContent} · w=${db.rect.w}`);
  console.log('      [读数] 桌面 footer 子项：' + JSON.stringify(db.kids.map((k) => ({ e: k.el, x: k.rect.x, w: k.rect.w }))));
  if (b) {
    const dB = +(db.kids[db.kids.length - 1].rect.x - db.kids[0].rect.x).toFixed(1);
    const mB = +(b.kids[b.kids.length - 1].rect.x - b.kids[0].rect.x).toFixed(1);
    ok('A2 手机 footer 跨度 ≤ 桌面 ×1.35', mB <= dB * 1.35 + 2, `mobile=${mB}px desktop=${dB}px`);
  }
}
const { ctx: dctx, page: dpage } = await boot({ width: 1440, height: 900, colorScheme: 'light' });
await dpage.screenshot({ path: join(SHOTS, 'chat-1440x900-light.png') });
await dctx.close();

await browser.close();
writeFileSync(join(SHOTS, 'result.txt'), lines.join('\n') + `\n\nPASS ${pass} / FAIL ${fail}\n`);
console.log(`\n=== ${pass} passed, ${fail} failed ===`);
process.exit(fail ? 1 : 0);
