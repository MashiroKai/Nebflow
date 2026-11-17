// todo-multiline-d.spec.mjs — D 批（ui-unify-panels 2026-11-17 作者令「待办内容
// 不要截断，展示与编辑都多行」）证据 spec。两腿：
//   桌面腿（tfl 胶囊面）＋手机腿（Apple 面）。断言族：
//   · 展示：ellipsis 退役（textOverflow=clip）、white-space=pre-line、
//     overflow-wrap=anywhere、无横向溢出（scrollWidth ≤ clientWidth+1）、
//     行高随内容长（getClientRects ≥ 2 / 高度 ≥ 1.9×行高）；
//   · 编辑：名输入面 tagName=TEXTAREA、Shift+Enter=换行（值里 \n 在场）、
//     Enter=保存、保存后 store 值含 \n、列表行照多行渲染。
// Run: node tests/todo-multiline-d.spec.mjs
//   CHATFACE_WEB_ROOT=<pre-change tree> 指向改前树 = red-leg（应红）。
// 零后端：静态路由 + WS 帧夹具 + todoStore.__seed（同 todo-mobile-edit-real 族）。

import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = process.env.CHATFACE_WEB_ROOT
  ? normalize(process.env.CHATFACE_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'evidence', '20261117_ui-unify-panels', 'd-multiline');
mkdirSync(OUT, { recursive: true });

const SID = 'todo-multiline-d-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}

const LONG_ASCII = 'a'.repeat(90); // 无空格长串（anywhere 兜底的目标形态）
const MULTI = '第一行标题\n第二行标题\n第三行标题';

async function wire(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => Promise.resolve(), configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    config(); sessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') config();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
}

/** 行内多行读数：computed 截断三件 + 横向溢出 + 渲染行数 + 文本。 */
const PROBE = (sel) => `(() => {
  const node = document.querySelector(${JSON.stringify(sel)});
  if (!node) return null;
  const cs = getComputedStyle(node);
  const lh = parseFloat(cs.lineHeight) || 0;
  return {
    tag: node.tagName,
    textOverflow: cs.textOverflow, whiteSpace: cs.whiteSpace, overflowWrap: cs.overflowWrap,
    sw: node.scrollWidth, cw: node.clientWidth,
    h: +node.getBoundingClientRect().height.toFixed(2), lh,
    rects: node.getClientRects().length,
    text: node.textContent,
    value: node.value !== undefined ? node.value : undefined,
  };
})()`;

const browser = await chromium.launch();
const errors = [];
try {
  /* ═════════ 桌面腿 · 1280×800 light（胶囊面 tfl-*）═════════ */
  {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(`desk: ${e.message}`));
    await wire(page);
    await page.goto('http://localhost:1/');
    await page.waitForFunction(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === sid && s.ws?.readyState === 1;
    }, SID, { timeout: 20000 });
    await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { timeout: 15000, state: 'attached' });
    await page.evaluate(([longAscii, multi]) => {
      const now = Date.now();
      return import('/js/todoStore.js').then(({ todoStore }) => todoStore.__seed({
        tagList: [{ id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' }],
        itemList: [
          { id: 'd-ctrl', tagId: 'tag-work', title: '短', notes: '', due: null, priority: 1, createdAt: now },
          { id: 'd-long', tagId: 'tag-work', title: longAscii, notes: '', due: null, priority: 1, createdAt: now },
          { id: 'd-multi', tagId: 'tag-work', title: multi, notes: '', due: null, priority: 1, createdAt: now },
        ],
      }));
    }, [LONG_ASCII, MULTI]);
    await page.locator('.tfl-capsule').click();
    await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 8000 });
    await page.waitForTimeout(900); // spring 落地
    await page.waitForSelector('.tfl-row[data-id="d-long"]', { timeout: 8000 });

    // D1a 长串不截断：wrap 到多行、无横向溢出、ellipsis 退役。
    // 行数判定 = 与同夹具单行对照行（d-ctrl）的高度比（flex 子块化后
    // getClientRects 恒 1 块，rects 不能当行数探针）。
    const ctrlP = await page.evaluate(PROBE('.tfl-row[data-id="d-ctrl"] .tfl-row-title'));
    const longP = await page.evaluate(PROBE('.tfl-row[data-id="d-long"] .tfl-row-title'));
    ok('D1a-1 桌面长串 ellipsis 退役（textOverflow=clip）', longP.textOverflow === 'clip', JSON.stringify(longP && { textOverflow: longP.textOverflow }));
    ok('D1a-2 桌面长串 pre-line 换行', longP.whiteSpace === 'pre-line', String(longP.whiteSpace));
    ok('D1a-3 桌面长串 overflow-wrap=anywhere', longP.overflowWrap === 'anywhere', String(longP.overflowWrap));
    ok('D1a-4 桌面长串无横向溢出（sw≤cw+1）', longP.sw <= longP.cw + 1, `sw=${longP.sw} cw=${longP.cw}`);
    ok('D1a-5 桌面长串行高随内容长（h≥1.9×对照行）', longP.h >= 1.9 * ctrlP.h, `h=${longP.h} ctrlH=${ctrlP.h}`);
    await page.screenshot({ path: join(OUT, '01-desk-long-wrap.png') });

    // D1b 显式多行名：\n 保真、按行渲染。
    const multiP = await page.evaluate(PROBE('.tfl-row[data-id="d-multi"] .tfl-row-title'));
    ok('D1b-1 桌面多行名 \n 保真', multiP.text === MULTI, JSON.stringify(multiP.text));
    ok('D1b-2 桌面多行名按 3 行渲染（h≥2.9×对照行）', multiP.h >= 2.9 * ctrlP.h, `h=${multiP.h} ctrlH=${ctrlP.h}`);
    ok('D1b-3 桌面多行名无横向溢出', multiP.sw <= multiP.cw + 1, `sw=${multiP.sw} cw=${multiP.cw}`);

    // D2 编辑：textarea 在场、值含 \n、Shift+Enter 换行、Enter 保存、\n 落库。
    await page.locator('.tfl-row[data-id="d-multi"] .tfl-row-main').click();
    const nameEl = page.locator('.tfl-row[data-id="d-multi"] .tfl-edit-name');
    await nameEl.waitFor({ state: 'visible', timeout: 8000 });
    const editP = await page.evaluate(PROBE('.tfl-row[data-id="d-multi"] .tfl-edit-name'));
    ok('D2-1 桌面编辑名 = TEXTAREA（input 退役）', editP.tag === 'TEXTAREA', String(editP.tag));
    ok('D2-2 桌面编辑名初值含 \\n', editP.value === MULTI, JSON.stringify(editP.value));
    await nameEl.focus();
    await page.evaluate(() => {
      const n = document.querySelector('.tfl-row[data-id="d-multi"] .tfl-edit-name');
      n.setSelectionRange(n.value.length, n.value.length); // 光标置末
    });
    await page.keyboard.press('Shift+Enter');
    const afterShift = await page.evaluate(() => document.querySelector('.tfl-row[data-id="d-multi"] .tfl-edit-name').value);
    ok('D2-3 桌面 Shift+Enter = 换行（值尾追加 \\n）', afterShift === `${MULTI}\n`, JSON.stringify(afterShift));
    await page.keyboard.press('Enter');
    await page.waitForTimeout(300);
    const savedDesk = await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      const it = todoStore.visibleItems().find((x) => x.id === 'd-multi');
      return it ? it.name : null;
    });
    ok('D2-4 桌面 Enter = 保存且内部 \\n 落库（store 恒 trim 首尾——既有语义）', savedDesk === MULTI, JSON.stringify(savedDesk));
    const reP = await page.evaluate(PROBE('.tfl-row[data-id="d-multi"] .tfl-row-title'));
    ok('D2-5 桌面保存后列表行照多行渲染（h≥2.9×对照行）', reP.h >= 2.9 * ctrlP.h, `h=${reP.h} ctrlH=${ctrlP.h}`);
    await page.screenshot({ path: join(OUT, '02-desk-edit-saved.png') });

    // D3 行内新建：textarea 多行可建，Enter 落库。
    await page.locator('.tfl-fab-add').click();
    const createInput = page.locator('.tfl-create-row .tfl-row-title-input');
    await createInput.waitFor({ state: 'visible', timeout: 8000 });
    const cP = await page.evaluate(PROBE('.tfl-create-row .tfl-row-title-input'));
    ok('D3-1 桌面新建名 = TEXTAREA', cP.tag === 'TEXTAREA', String(cP.tag));
    await createInput.fill('新建甲行\n新建乙行');
    await createInput.press('Enter');
    await page.waitForTimeout(300);
    const created = await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      return todoStore.visibleItems().map((x) => x.name);
    });
    ok('D3-2 桌面多行新建落库（含 \\n）', created.includes('新建甲行\n新建乙行'), JSON.stringify(created));
    const createdRow = await page.evaluate(`(() => {
      const rows = [...document.querySelectorAll('.tfl-row')];
      const row = rows.find((r) => r.querySelector('.tfl-row-title')?.textContent === '新建甲行\\n新建乙行');
      if (!row) return null;
      const t = row.querySelector('.tfl-row-title');
      const cs = getComputedStyle(t);
      return { h: +t.getBoundingClientRect().height.toFixed(2), sw: t.scrollWidth, cw: t.clientWidth, whiteSpace: cs.whiteSpace };
    })()`);
    ok('D3-3 桌面新建行照多行渲染（h≥1.9×对照行 且 sw≤cw+1）', !!createdRow && createdRow.h >= 1.9 * ctrlP.h && createdRow.sw <= createdRow.cw + 1, JSON.stringify(createdRow));
    await page.screenshot({ path: join(OUT, '03-desk-create-multiline.png') });
    await context.close();
  }

  /* ═════════ 手机腿 · 375×812 light（Apple 面 ta-*）═════════ */
  {
    const context = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true, colorScheme: 'light' });
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(`mob: ${e.message}`));
    await wire(page);
    await page.goto('http://localhost:1/');
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
    await page.waitForFunction(async (sid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === sid && s.ws?.readyState === 1;
    }, SID, { timeout: 20000 });
    await page.evaluate(([longAscii, multi]) => {
      const now = Date.now();
      return import('/js/todoStore.js').then(({ todoStore }) => todoStore.__seed({
        tagList: [{ id: 'tag-work', name: '很长很长的工作列表名称换行测试', color: 'rgb(59,130,246)' }],
        itemList: [
          { id: 'm-ctrl', tagId: 'tag-work', title: '短', notes: '', due: null, priority: 1, createdAt: now },
          { id: 'm-long', tagId: 'tag-work', title: longAscii, notes: '', due: null, priority: 1, createdAt: now },
          { id: 'm-multi', tagId: 'tag-work', title: multi, notes: '', due: null, priority: 1, createdAt: now },
        ],
      }));
    }, [LONG_ASCII, MULTI]);
    const taskButton = page.locator("[data-chrome='tasks']");
    await taskButton.waitFor({ state: 'visible' });
    await taskButton.click();
    await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='tasks']")?.classList.contains('live'));

    // D4a 首页列表行名（ta-row-label）多行不截断（单行对照 = 「全部」行）。
    const allLabelP = await page.evaluate(PROBE('.mobile-page[data-page="tasks"] .ta-row-all .ta-row-label'));
    const labelP = await page.evaluate(PROBE('.mobile-page[data-page="tasks"] .ta-row.ta-row-tag .ta-row-label'));
    ok('D4a-1 手机首页行名 ellipsis 退役', labelP && labelP.textOverflow === 'clip', JSON.stringify(labelP && { textOverflow: labelP.textOverflow }));
    ok('D4a-2 手机首页行名 pre-line + anywhere', labelP.whiteSpace === 'pre-line' && labelP.overflowWrap === 'anywhere', `${labelP.whiteSpace}/${labelP.overflowWrap}`);
    ok('D4a-3 手机首页行名换行（h≥1.9×对照行 且 sw≤cw+1）', labelP.h >= 1.9 * allLabelP.h && labelP.sw <= labelP.cw + 1, `h=${labelP.h} allH=${allLabelP.h} sw=${labelP.sw} cw=${labelP.cw}`);
    await page.screenshot({ path: join(OUT, '04-mob-home-rowlabel-wrap.png') });

    // 进列表页：条目名多行。
    await page.locator('.mobile-page[data-page="tasks"] .ta-row.ta-row-tag').click();
    await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-list']")?.classList.contains('live'));
    await page.waitForSelector('.ta-item[data-item-id="m-long"]', { timeout: 8000 });
    const mCtrlP = await page.evaluate(PROBE('.ta-item[data-item-id="m-ctrl"] .ta-item-title'));
    const mLongP = await page.evaluate(PROBE('.ta-item[data-item-id="m-long"] .ta-item-title'));
    ok('D4b-1 手机条目名 ellipsis 退役', mLongP.textOverflow === 'clip', String(mLongP.textOverflow));
    ok('D4b-2 手机条目名 pre-line + anywhere', mLongP.whiteSpace === 'pre-line' && mLongP.overflowWrap === 'anywhere', `${mLongP.whiteSpace}/${mLongP.overflowWrap}`);
    ok('D4b-3 手机条目名无横向溢出', mLongP.sw <= mLongP.cw + 1, `sw=${mLongP.sw} cw=${mLongP.cw}`);
    ok('D4b-4 手机条目名行高随内容长（h≥1.9×对照行）', mLongP.h >= 1.9 * mCtrlP.h, `h=${mLongP.h} ctrlH=${mCtrlP.h}`);
    const mMultiP = await page.evaluate(PROBE('.ta-item[data-item-id="m-multi"] .ta-item-title'));
    ok('D4b-5 手机多行名 \n 保真且按行渲染（h≥2.9×对照行）', mMultiP.text === MULTI && mMultiP.h >= 2.9 * mCtrlP.h, `h=${mMultiP.h} ctrlH=${mCtrlP.h}`);
    await page.screenshot({ path: join(OUT, '05-mob-list-itemtitle-wrap.png') });

    // D5 条目页编辑：textarea、Shift+Enter 换行、保存后 \n 落库。
    await page.locator('.ta-item[data-item-id="m-multi"] .ta-item-body').click();
    await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-item']")?.classList.contains('live'));
    const titleInput = page.locator('.mobile-page[data-page="todo-apple-item"] .ta-title-input');
    await titleInput.waitFor({ state: 'visible', timeout: 8000 });
    const tP = await page.evaluate(PROBE('.mobile-page[data-page="todo-apple-item"] .ta-title-input'));
    ok('D5-1 手机条目名编辑域 = TEXTAREA', tP.tag === 'TEXTAREA', String(tP.tag));
    ok('D5-2 手机编辑域初值含 \\n', tP.value === MULTI, JSON.stringify(tP.value));
    await titleInput.focus();
    await page.evaluate(() => {
      const n = document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-title-input');
      n.setSelectionRange(n.value.length, n.value.length);
    });
    await page.keyboard.press('Shift+Enter');
    const mAfterShift = await page.evaluate(() => document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-title-input').value);
    ok('D5-3 手机 Shift+Enter = 换行（值尾追加 \\n）', mAfterShift === `${MULTI}\n`, JSON.stringify(mAfterShift));
    await page.screenshot({ path: join(OUT, '06-mob-item-edit-multiline.png') });
    await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-save').click();
    await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='todo-apple-list']")?.classList.contains('live'));
    const savedMob = await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      const it = todoStore.visibleItems().find((x) => x.id === 'm-multi');
      return it ? it.title : null;
    });
    ok('D5-4 手机保存后 \\n 落库（trim 仅去首尾）', savedMob === `${MULTI}\n`.trim(), JSON.stringify(savedMob));
    const mReP = await page.evaluate(PROBE('.ta-item[data-item-id="m-multi"] .ta-item-title'));
    ok('D5-5 手机保存后列表行照多行渲染（h≥2.9×对照行）', mReP.h >= 2.9 * mCtrlP.h, `h=${mReP.h} ctrlH=${mCtrlP.h}`);
    await page.screenshot({ path: join(OUT, '07-mob-edit-saved.png') });
    await context.close();
  }

  ok('E2E 全程零 pageerror', errors.length === 0, errors.join(' | '));
} finally {
  await browser.close();
}

writeFileSync(join(OUT, 'assertions.txt'), lines.join('\n') + '\n');
console.log(`\n${pass} passed, ${fail} failed  →  ${OUT}`);
process.exit(fail ? 1 : 0);
