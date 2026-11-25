#!/usr/bin/env node
// 离线真实导航；RADIUS_BASE=1 用 merge-base CSS，RADIUS_MUTATE=1 注入负控。
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
const base = execFileSync('git', ['merge-base', 'HEAD', 'feat/base'], { encoding: 'utf8' }).trim();
const cssPath = 'src/main/resources/web/css/mobile-todo-apple.css';
const baseCss = execFileSync('git', ['show', `${base}:${cssPath}`], { encoding: 'utf8' });
const errors = [];
const results = [];
const seed = {
  tagList: [{ id: 'work', name: '工作', color: 'rgb(59,130,246)' }],
  itemList: [
    { id: 'plain', tagId: 'work', title: '普通事项', priority: 1 },
    { id: 'decision', tagId: 'work', title: '决策事项', decision: { question: '选择？', options: ['甲', '乙'] } },
  ],
};
async function session(width, theme, baseline = false) {
  const context = await browser.newContext({ viewport: { width, height: 844 }, isMobile: width < 500, hasTouch: width < 500, colorScheme: theme });
  const s = await createSession({ newPage: () => context.newPage() }, { width, height: 844, baseURL: 'https://stand.test/' });
  if (baseline || process.env.RADIUS_MUTATE) await s.page.route('**/css/mobile-todo-apple.css', async route => {
    let css = baseline ? baseCss : await (await import('node:fs/promises')).readFile(new URL('../src/main/resources/web/css/mobile-todo-apple.css', import.meta.url), 'utf8');
    if (process.env.RADIUS_MUTATE && !baseline) css += '\nbody.mobile-layer .mobile-page[data-page="tasks"] .ta-quick-fab { border-radius: 6px !important; }';
    await route.fulfill({ status: 200, contentType: 'text/css', body: css });
  });
  await s.boot();
  await s.page.evaluate(async ({seed, theme}) => {
    document.documentElement.setAttribute('data-theme', theme);
    (await import('/js/todoStore.js')).todoStore.__seed(seed);
  }, {seed, theme});
  return { ...s, context };
}
async function home(s) {
  await s.page.locator('[data-chrome="tasks"]').click();
  await s.page.locator('.mobile-page.live[data-page="tasks"] .ta-card').first().waitFor();
  await settle(s.page);
}
async function probe(page, label, selector, expected, combo) {
  const loc = page.locator(`.mobile-page.live ${selector}`);
  const count = await loc.count();
  if (!count) { errors.push(`${combo} ${label}: missing ${selector}`); return; }
  let visible = 0;
  for (let i = 0; i < count; i++) {
    const item = loc.nth(i);
    if (!(await item.isVisible())) continue;
    visible++;
    const corners = await item.evaluate(el => { const s = getComputedStyle(el); return [s.borderTopLeftRadius, s.borderTopRightRadius, s.borderBottomRightRadius, s.borderBottomLeftRadius]; });
    if (label === 'quick-fab' || label === 'filter-chip') {
      const hit = await item.evaluate(el => {
        const r = el.getBoundingClientRect();
        const at = (x, y) => { const n = document.elementFromPoint(x, y); return { tag: n?.tagName, className: typeof n?.className === 'string' ? n.className : '', inside: !!n && (n === el || el.contains(n)), button: n?.closest('button')?.className || null }; };
        return { width:r.width, height:r.height, center:at(Math.round(r.x+r.width/2),Math.round(r.y+r.height/2)), corner:at(r.x+1,r.y+1) };
      });
      console.log('HIT', JSON.stringify({combo,label,hit}));
      if (!hit.center.inside) errors.push(`${combo} ${label}: center hit outside target`);
    }
    results.push({ combo, label, selector, corners });
    if (!process.env.RADIUS_MEASURE && !corners.every(x => x === expected)) errors.push(`${combo} ${label} ${selector}: ${corners.join(',')} != ${expected}`);
  }
  if (!visible) errors.push(`${combo} ${label}: no visible ${selector}`);
}
async function mobile(width, theme) {
  const combo = `${width}-${theme}`;
  const baseMode = process.env.RADIUS_BASE === '1';
  let s = await session(width, theme, baseMode);
  try {
    await home(s);
    for (const [label, sel, value] of [ ['cards','.ta-card','18px'], ['listcard','.ta-listcard','18px'], ['dots','.ta-dot','999px'], ['pill-home','.ta-pill-btn','999px'], ['quick-fab','.ta-quick-fab','999px'] ]) await probe(s.page,label,sel,value,combo);
    await s.page.locator('.mobile-page.live .ta-pill-btn').nth(0).click();
    const fieldRadius = await s.page.evaluate(() => {
      const root = getComputedStyle(document.documentElement);
      const token = root.getPropertyValue('--nf-field-radius').trim();
      const match = /^var\((--[a-z0-9-]+)\)$/.exec(token);
      return match ? root.getPropertyValue(match[1]).trim() : token;
    });
    assert.ok(fieldRadius, '输入面圆角令牌必须存在');
    await probe(s.page,'search','.ta-search-input',fieldRadius,combo);
    await probe(s.page,'pill-selected','.ta-pill-btn.on','999px',combo);
    await s.page.locator('.mobile-page.live .ta-pill-btn').nth(0).click();
    await s.page.locator('.mobile-page.live .ta-pill-btn').nth(1).click();
    await probe(s.page,'new-dot','.ta-dot-new','999px',combo);
  } finally { await s.context.close(); }
  s = await session(width, theme, baseMode);
  try {
    await home(s);
    await s.page.locator('.mobile-page.live .ta-pill-btn').nth(2).click();
    await probe(s.page,'menu','.ta-menu','14px',combo);
    await probe(s.page,'menu-row','.ta-menu-row','999px',combo);
  } finally { await s.context.close(); }
  s = await session(width, theme, baseMode);
  try {
    await home(s);
    await s.page.locator('.mobile-page.live .ta-card-all').click();
    await s.page.locator('.mobile-page.live .ta-item[data-item-id="decision"] .ta-item-body').click();
    await probe(s.page,'decision-option','.ta-decision-option','999px',combo);
  } finally { await s.context.close(); }
  s = await session(width, theme, baseMode);
  try {
    await home(s);
    await s.page.locator('.mobile-page.live .ta-card-all').click();
    await s.page.locator('.mobile-page.live .ta-item[data-item-id="plain"] .ta-item-body').click();
    await probe(s.page,'pill-detail','.ta-pill-btn','999px',combo);
    await s.page.locator('.mobile-page.live .ta-pill-btn').last().click();
    await probe(s.page,'pill-detail-selected','.ta-pill-btn.on','999px',combo);
    await probe(s.page,'tool-detail','.ta-tool-btn','999px',combo);
  } finally { await s.context.close(); }
  s = await session(width, theme, baseMode);
  try {
    await home(s);
    await s.page.locator('.mobile-page.live .ta-quick-fab').click();
    await s.page.locator('.mobile-page.live .ta-qd-filter-btn').click();
    await probe(s.page,'filters','.ta-qd-filters','18px',combo);
    await probe(s.page,'filter-chip','.ta-qd-flt-chip','999px',combo);
    await s.page.locator('.mobile-page.live .ta-qd-flt-chip').nth(1).click();
    await probe(s.page,'filter-selected','.ta-qd-filter-btn.on','999px',combo);
  } finally { await s.context.close(); }
}
const desktopSelectors = ['.ta-card','.ta-listcard','.ta-search-input','.ta-menu','.ta-dot','.ta-menu-row','.ta-pill-btn','.ta-quick-fab','.ta-tool-btn','.ta-decision-option','.ta-qd-flt-chip','.ta-qd-filter-btn.on','.ta-qd-filters'];
async function desktop(baseline) {
  const s = await session(1440, 'light', baseline);
  try {
    await s.page.evaluate(async () => {
      const face = await import('/js/todoFace.js');
      face.renderTodoTaskList('visual-root');
      if (!document.querySelector('#task-list .tfl-capsule')) face.mountTodoFaceInto(document.getElementById('task-list'));
    });
    await s.page.locator('#task-list .tfl-capsule').first().click();
    await s.page.locator('#task-list .tfl-list-inner').waitFor({state:'visible'});
    return await s.page.evaluate(sels => Object.fromEntries(sels.map(sel => [sel, [...document.querySelectorAll(sel)].map(el => { const s=getComputedStyle(el); return [s.borderTopLeftRadius,s.borderTopRightRadius,s.borderBottomRightRadius,s.borderBottomLeftRadius]; })])), desktopSelectors);
  } finally { await s.context.close(); }
}
try {
  execFileSync(process.execPath, ['scripts/check-input-surface.mjs'], { stdio: 'inherit' });
  for (const width of [390,320]) for (const theme of ['light','dark']) {
    try { await mobile(width,theme); } catch (e) { errors.push(`${width}-${theme}: ${e.stack}`); }
  }
  const before = await desktop(true), after = await desktop(false);
  console.log('DESKTOP', JSON.stringify({ before, after }));
  assert.deepEqual(after,before,'桌面真实待办视图前后应一致');
  console.log('RESULTS',JSON.stringify(results));
  console.log('SKIP 新建／确认 sheet：入口停用，不强开');
  for (const error of errors) console.error('FAIL',error);
  if (errors.length) process.exitCode = 1;
  else console.log(`PASS ${results.length} measurements`);
} finally { await browser.close(); }
