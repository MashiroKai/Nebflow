#!/usr/bin/env node
// todo-face-shots.mjs — 任务列表换血批（ReminderIsland 复刻 · 作者令 2026-11-02 20:02）
// 视觉证据 + 机械断言 + 零 console error。
//
// 验收矩阵（作者令②）：
//   · 桌面 1440 + 手机 375 × 双引擎（chromium/webkit）× 双主题（light/dark）实拍：
//     静默胶囊态 / 展开列表态 / 添加·完成·删除三动作中间帧；
//   · delegate 条目退场无残留断言（#task-list 内零 .task-chain-block）；
//   · 操作逻辑断言（增删改查/完成三段动画/筛选/键盘导航/点击外部收起/胶囊 pop）。
//
// 产出 = .nebflow/evidence/20261101_todo-face/shots/
//        d-{capsule,expand-mid,open,filter,add-mid,add-done,complete-mid1,
//           complete-mid2,complete-done,swipe-mid,swipe-done,pop-mid,after-close}
//        -{chromium,webkit}-{light,dark}.png + m-open/m-add-mid/m-add-done/
//        m-complete-mid-{engine}-{light,dark}.png
// 用法：node tests/todo-face-shots.mjs
import { chromium, webkit } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', '.nebflow', 'evidence', '20261101_todo-face', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'chain-root-session';
const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };
const T0 = Date.now();
const H = 3600_000;

let passCount = 0;
let failCount = 0;
const fails = [];
function ok(name, cond, detail = '') {
  if (cond) { passCount++; console.log(`  PASS ${name}`); }
  else { failCount++; fails.push(`${name}${detail ? ` — ${detail}` : ''}`); console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`); }
}

// ── 确定性种子（todoStore.__seed 测试缝；约 ReminderIsland 全功能一览）────
function seedScript() {
  return `
  (async () => {
    const m = await import('/js/todoStore.js');
    const now = ${T0};
    const H = ${H};
    m.todoStore.__seed({
      tagList: [
        { id: 'tag-work', name: '工作', color: m.TAG_COLOR_PRESETS[5] },
        { id: 'tag-life', name: '生活', color: m.TAG_COLOR_PRESETS[3] },
        { id: 'tag-study', name: '学习', color: m.TAG_COLOR_PRESETS[7] },
        { id: 'tag-idea', name: '灵感', color: m.TAG_COLOR_PRESETS[1] },
      ],
      itemList: [
        { id: 'i-1', tagId: 'tag-work', title: '修复登录态在手机层的过期提示', notes: '过期后应回到登录页', due: now + 2 * H, priority: 3, createdAt: now - 26 * H },
        { id: 'i-2', tagId: 'tag-life', title: '缴房租', notes: '', due: now - 3 * H, priority: 3, createdAt: now - 50 * H },
        { id: 'i-3', tagId: 'tag-work', title: '整理验收对照表的动画取帧', notes: '', due: now + 26 * H, priority: 2, createdAt: now - 20 * H },
        { id: 'i-4', tagId: 'tag-life', title: '下班顺路买菜', notes: '番茄 + 鸡蛋', due: now + 5 * H, priority: 2, createdAt: now - 30 * H },
        { id: 'i-5', tagId: 'tag-work', title: '回复设计稿第二批意见', notes: '', due: null, priority: 1, createdAt: now - 9 * H },
        { id: 'i-6', tagId: 'tag-study', title: '读《设计中的设计》第三章', notes: '', due: now + 72 * H, priority: 1, createdAt: now - 60 * H },
        { id: 'i-7', tagId: 'tag-life', title: '预约体检', notes: '', due: now + 168 * H, priority: 0, createdAt: now - 80 * H },
        { id: 'i-8', tagId: 'tag-idea', title: '记录灵感：刘海收展节奏迁移到胶囊', notes: 'spring(0.42,0.8) 开 / spring(0.45,1.0) 收', due: null, priority: 1, createdAt: now - 4 * H },
      ],
    });
  })()`;
}

async function makePage(browser, engine, colorScheme, { mobile = false } = {}) {
  const ctx = await browser.newContext({
    colorScheme,
    viewport: mobile ? { width: 375, height: 812 } : { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    hasTouch: mobile,
    serviceWorkers: 'block', // sw.js 注册在无引擎 mock 源上必败（静默空转语义的测试等价）
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  page.on('pageerror', (err) => errors.push(String(err)));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = url.pathname;
    if (p === '/') p = '/index.html';
    if (p === '/api/nf-asset-stamp') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ stamp: 'e2e-fixed' }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Spider' }) });
    }
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: SELF_DEVICE,
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:8117/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  await page.evaluate(seedScript());
  if (!mobile) {
    await page.evaluate(async (sid) => {
      const f = await import('/js/todoFace.js');
      f.renderTodoTaskList(sid);
    }, ROOT_SID);
  }
  await page.waitForTimeout(500);
  return { page, errors, ctx };
}

const shot = (page, name) => page.screenshot({ path: join(OUT, `${name}.png`) });
const shotEl = (page, sel, name) => page.locator(sel).first().screenshot({ path: join(OUT, `${name}.png`) });

// ── 桌面场景 ─────────────────────────────────────────────────────────────
async function desktopScenario(browser, engine, colorScheme) {
  const tag = `${engine}-${colorScheme}`;
  const { page, errors, ctx } = await makePage(browser, engine, colorScheme);
  try {
    // A. 静默胶囊态
    await page.waitForSelector('#task-list .tfl-capsule', { timeout: 5000 });
    ok(`capsule summary text (${tag})`,
      (await page.textContent('.tfl-capsule-count')).includes('8 待办') ||
      (await page.textContent('.tfl-capsule-count')).match(/8/),
      await page.textContent('.tfl-capsule-count'));
    ok(`capsule overdue flag (${tag})`,
      await page.locator('.tfl-capsule.has-overdue').count() === 1);
    ok(`delegate rows zero residue (${tag})`,
      await page.locator('#task-list .task-chain-block').count() === 0);
    await shot(page, `d-capsule-${tag}`);
    await shotEl(page, '#task-list', `d-capsule-el-${tag}`);

    // B. 展开（刘海节奏：中间帧 + 展开态）
    await page.click('.tfl-capsule');
    await page.waitForTimeout(130);
    await shotEl(page, '#task-list', `d-expand-mid-${tag}`);
    await page.waitForTimeout(900);
    ok(`panel open state (${tag})`,
      await page.locator('.tfl-card[data-state="open"]').count() === 1);
    const rowCount = await page.locator('.tfl-row').count();
    ok(`rows = 8 (${tag})`, rowCount === 8, String(rowCount));
    // 排序：高重要度在前（All 视图 = 重要度降序 → 标签序 → 截止）
    const firstDot = await page.locator('.tfl-row').first().evaluate((r) => r.querySelector('.tfl-row-dot').style.background);
    ok(`first row is high priority red (${tag})`, firstDot.includes('255, 59, 48'), firstDot);
    const badgeTexts = await page.locator('.tfl-row .tfl-row-badge').allTextContents();
    ok(`All view carries tag badges (${tag})`, badgeTexts.length === 8 && badgeTexts[0] === '工作', badgeTexts.join('|'));
    await shot(page, `d-open-${tag}`);
    await shotEl(page, '#task-list', `d-open-el-${tag}`);

    // C. 筛选栏 + 重要度筛选
    await page.click('.tfl-filter-toggle');
    await page.waitForTimeout(400);
    ok(`filter bar chips (${tag})`,
      await page.locator('.tfl-chip-tag').count() === 4 &&
      await page.locator('.tfl-chip-prio').count() === 4);
    await shotEl(page, '#task-list', `d-filter-${tag}`);
    await page.locator('.tfl-chip-prio').nth(3).click(); // 高
    await page.waitForTimeout(400);
    const highRows = await page.locator('.tfl-row').count();
    ok(`priority filter → 2 high rows (${tag})`, highRows === 2, String(highRows));
    await page.locator('.tfl-chip-prio').nth(3).click(); // 取消
    await page.waitForTimeout(300);

    // D. 添加流（单标签视图：footer + 行内创建行）
    await page.locator('.tfl-chip-tag').first().click(); // 工作
    await page.waitForTimeout(500);
    ok(`tag view rows (${tag})`, (await page.locator('.tfl-row').count()) === 3,
      String(await page.locator('.tfl-row').count()));
    await page.click('.tfl-add-btn');
    await page.waitForTimeout(400);
    ok(`create row input present (${tag})`,
      await page.locator('.tfl-create-row .tfl-row-title-input').count() === 1);
    await page.keyboard.type('给新 face 拍一组验收帧');
    await page.waitForTimeout(200);
    await shotEl(page, '#task-list', `d-add-mid-${tag}`);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(600);
    const titlesAfterAdd = await page.locator('.tfl-row-title').allTextContents();
    ok(`created row lands (${tag})`,
      (await page.locator('.tfl-row').count()) === 4 &&
      titlesAfterAdd.some((x) => x.includes('验收帧')),
      titlesAfterAdd.join('|'));
    await shotEl(page, '#task-list', `d-add-done-${tag}`);

    // E. 完成流（三段动画：绿填 → 对勾 → 淡出 → 销账）
    await page.locator('.tfl-row').first().locator('.tfl-complete').click();
    await page.waitForTimeout(260);
    ok(`complete fill checked class (${tag})`,
      await page.locator('.tfl-complete.checked').count() === 1);
    await shotEl(page, '#task-list', `d-complete-mid1-${tag}`);
    await page.waitForTimeout(420); // ~0.68s：行淡出中段
    await shotEl(page, '#task-list', `d-complete-mid2-${tag}`);
    await page.waitForTimeout(900);
    ok(`completed row removed (${tag})`, (await page.locator('.tfl-row').count()) === 3);

    // F. 键盘导航（↑↓ 选择 / Return 进编辑 / Esc 收起保存）
    await page.focus('.tfl-list');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(150);
    ok(`keyboard selection (${tag})`,
      await page.locator('.tfl-row.tfl-selected').count() === 1);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    ok(`Return enters editing (${tag})`,
      await page.locator('.tfl-row-editing .tfl-row-title-input').count() === 1);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
    ok(`Esc saves and collapses edit (${tag})`,
      await page.locator('.tfl-row-editing').count() === 0);

    // G. 删除流（chip 下划 ≥25px → 删标签连带条目）；筛选栏按需打开
    if (!(await page.locator('.tfl-chip-tag').first().isVisible())) {
      await page.click('.tfl-filter-toggle');
      await page.waitForTimeout(400);
    }
    const ideaChip = page.locator('.tfl-chip-tag', { hasText: '灵感' });
    const box = await ideaChip.boundingBox();
    ok(`idea chip visible (${tag})`, !!box);
    if (box) {
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 18, { steps: 6 });
      await page.waitForTimeout(120);
      await shotEl(page, '#task-list', `d-swipe-mid-${tag}`);
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 45, { steps: 5 });
      await page.mouse.up();
      await page.waitForTimeout(500);
      ok(`swipe-down deletes tag (${tag})`,
        (await page.locator('.tfl-chip-tag').count()) === 3);
      await shotEl(page, '#task-list', `d-swipe-done-${tag}`);
      // 收起筛选栏，回到干净展开态
      await page.click('.tfl-filter-toggle');
      await page.waitForTimeout(300);
    }

    // H. 胶囊 pop（notchPop 等价；收起后触发取帧）
    await page.click('.tfl-collapse-btn');
    await page.waitForTimeout(900);
    ok(`collapse back to capsule (${tag})`,
      await page.locator('.tfl-card[data-state="capsule"]').count() === 1);
    await page.evaluate(async () => {
      const f = await import('/js/todoFace.js');
      f.__desktopFace().pop();
    });
    await page.waitForTimeout(160);
    await shotEl(page, '#task-list', `d-pop-mid-${tag}`);
    await page.waitForTimeout(900);

    // I. 点击外部收起（click-outside close）
    //    「外部」= 真实的面板外（重做批 2026-11-02：面板从 30px 条变成 420×374 浮层，
    //    旧写死的 (720,600) 现在落在面板内部 —— 那不是「点外部」）。取点规则：以面板
    //    盒子为准，取其左侧 24px 之外、纵向落在面板中线的一个点，先断言该点确实不在
    //    面板里，再点。
    await page.click('.tfl-capsule');
    await page.waitForTimeout(800);
    const outside = await page.evaluate(() => {
      const card = document.querySelector('.tfl-card');
      const r = card.getBoundingClientRect();
      const x = Math.max(8, Math.round(r.left - 24));
      const y = Math.round(r.top + r.height / 2);
      const el = document.elementFromPoint(x, y);
      return { x, y, outsideCard: !card.contains(el), hit: el ? (el.id || el.className || el.tagName) : null };
    });
    ok(`click-outside target is truly outside the panel (${tag})`, outside.outsideCard, JSON.stringify(outside));
    await page.mouse.click(outside.x, outside.y);
    await page.waitForTimeout(900);
    ok(`click-outside closes (${tag})`,
      await page.locator('.tfl-card[data-state="capsule"]').count() === 1);
    await shot(page, `d-after-close-${tag}`);

    ok(`zero console errors desktop (${tag})`, errors.length === 0, errors.slice(0, 3).join(' | '));
  } catch (e) {
    failCount++; fails.push(`desktopScenario ${tag} threw — ${e.message}`);
    console.log(`  FAIL desktopScenario ${tag} threw — ${e.message}`);
    await shot(page, `d-ERROR-${tag}`).catch(() => {});
  } finally {
    await ctx.close();
  }
}

// ── 手机场景 ─────────────────────────────────────────────────────────────
async function mobileScenario(browser, engine, colorScheme) {
  const tag = `${engine}-${colorScheme}`;
  const { page, errors, ctx } = await makePage(browser, engine, colorScheme, { mobile: true });
  try {
    // 左侧悬浮圆钮（静默态形态维持）+ 徽标 = 待办数
    await page.waitForSelector('[data-chrome="tasks"]', { timeout: 8000 });
    ok(`mobile layer active (${tag})`,
      await page.evaluate(() => document.body.classList.contains('mobile-layer')));
    const badge = await page.textContent('[data-chrome="tasks"] .mh-chrome-badge');
    ok(`FAB badge = 8 (${tag})`, badge && badge.trim() === '8', String(badge));
    await shot(page, `m-capsule-fab-${tag}`);

    // 点开任务页 = 展开态（待办列表换血）
    await page.click('[data-chrome="tasks"]');
    await page.waitForSelector('#mobile-task-panel:not([hidden]) .tfl-mobile', { timeout: 6000 });
    await page.waitForTimeout(600);
    ok(`mobile face rows (${tag})`, (await page.locator('#mobile-task-panel .tfl-row').count()) === 8);
    await shot(page, `m-open-${tag}`);

    // 添加流（开筛选栏 → 单标签视图 → footer 创建行）
    await page.click('#mobile-task-panel .tfl-filter-toggle');
    await page.waitForTimeout(400);
    await page.locator('#mobile-task-panel .tfl-chip-tag').first().click();
    await page.waitForTimeout(400);
    await page.click('#mobile-task-panel .tfl-add-btn');
    await page.waitForTimeout(400);
    await page.keyboard.type('手机端的一条新待办');
    await page.waitForTimeout(150);
    await shotEl(page, '#mobile-task-panel', `m-add-mid-${tag}`);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(600);
    ok(`mobile created row (${tag})`, (await page.locator('#mobile-task-panel .tfl-row').count()) === 4);
    await shotEl(page, '#mobile-task-panel', `m-add-done-${tag}`);

    // 完成流（中间帧）
    await page.locator('#mobile-task-panel .tfl-row').first().locator('.tfl-complete').click();
    await page.waitForTimeout(260);
    ok(`mobile complete check (${tag})`,
      await page.locator('#mobile-task-panel .tfl-complete.checked').count() === 1);
    await shotEl(page, '#mobile-task-panel', `m-complete-mid-${tag}`);
    await page.waitForTimeout(1400);

    ok(`zero console errors mobile (${tag})`, errors.length === 0, errors.slice(0, 3).join(' | '));
  } catch (e) {
    failCount++; fails.push(`mobileScenario ${tag} threw — ${e.message}`);
    console.log(`  FAIL mobileScenario ${tag} threw — ${e.message}`);
    await shot(page, `m-ERROR-${tag}`).catch(() => {});
  } finally {
    await ctx.close();
  }
}

// ── 主流程 ───────────────────────────────────────────────────────────────
const engines = { chromium, webkit };
const onlyEngine = process.env.TODO_FACE_ENGINE;
const colorSchemes = ['light', 'dark'];
for (const [name, mod] of Object.entries(engines)) {
  if (onlyEngine && name !== onlyEngine) continue;
  const browser = await mod.launch({ headless: true });
  for (const scheme of colorSchemes) {
    console.log(`\n══ desktop ${name} ${scheme} ══`);
    await desktopScenario(browser, name, scheme);
    console.log(`\n══ mobile ${name} ${scheme} ══`);
    await mobileScenario(browser, name, scheme);
  }
  await browser.close();
}

console.log(`\n──── todo-face-shots: ${passCount} pass / ${failCount} fail ────`);
if (fails.length) { console.log('FAILED:\n' + fails.map((f) => `  - ${f}`).join('\n')); process.exit(1); }
