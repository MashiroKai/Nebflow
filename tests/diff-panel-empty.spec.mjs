// diff-panel-empty.spec.mjs — 桌面 diff 面板空态误报回归（作者报障 2026-11-19 20:56）
//
// 报障（逐字）：「我打开了diff了啊，它显示尚未打开」——Canvas 阅读面已打开，左栏
// 面板空态文案「尚未打开 diff。点击委托交付消息上的变更摘要查看文件列表。」仍显示。
//
// 判据（真值源 = 面板 DOM 的文件列表是否在场，而非某个入口置位的标志）：
//   E1 点小注打开 ⇒ .nfp-empty 隐藏、.nfp-list 在场（2 行）。
//   E2 重放（同一 delivery 帧重复注入）后再点 ⇒ 空态仍隐藏、列表正确。
//   E3 再点同一小注（收起→再开）⇒ 空态隐藏。
//   E4 真实关页签（closeTab）⇒ 无已开 diff ⇒ 空态回显、列表清空；再点 ⇒ 重开、空态隐藏。
//   E5 Canvas 阅读面在场时，面板空态绝不可见——任何入口打开的 diff 都让空态消失。
//   E6 locale 切换（面板文案重绘）不得把打开态的空态重新显示。
//   E7 面板实例残缺（列表子件丢失）⇒ 点开仍重建完备实例、列表正确、空态隐藏。
//
// 运行：node node_modules/@playwright/test/cli.js test tests/diff-panel-empty.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// DIFF_WEB 可指向导出的旧树（改前对照，见 evidence 说明）；缺省 = 本工作树。
const WEB = process.env.DIFF_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'e2e-diff-empty-root';
const PAYLOAD = {
  nodeId: 'delegate-diff-empty-a',
  generatedAt: 1791999100000,
  root: '/tmp/ws-e2e',
  files: [
    { path: 'src/a.js', changeType: 'modified', added: 2, removed: 1, source: 'git', diffText: '@@ -1,2 +1,3 @@\n-a\n+b\n+c' },
    { path: 'docs/b.md', changeType: 'added', added: 4, removed: 0, source: 'tool-ledger', diffText: '@@ -0,0 +1,4 @@\n+1\n+2\n+3\n+4' },
  ],
};

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    ws.onMessage(() => {});
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 点后等异步打开落定（动态 import + canvas openTab）。 */
async function settle(page) {
  await page.waitForSelector('#panel-filechanges.active', { timeout: 8000 });
  await page.waitForTimeout(400);
}

async function injectCompletion(page) {
  await page.evaluate(async ({ payload }) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: s.activeSessionId,
        source: 'delegate', eventType: 'completed',
        header: 'DELEGATE · stand · diff-batch · COMPLETED',
        text: 'batch finished', timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }, { payload: PAYLOAD });
}

const note = (page, n = 0) => page.locator('.bubble.injected .nf-rcpt-note').nth(n);

/** 面板空态状态快照（真值 = 列表行数 + 空态 hidden 逻辑态）。 */
async function snapshot(page) {
  return page.evaluate(() => {
    const p = document.getElementById('panel-filechanges');
    const empty = p?.querySelector('.nfp-empty');
    const list = p?.querySelector('.nfp-list');
    return {
      emptyVisible: !!empty && !empty.hidden, // 逻辑态（侧栏收起时 offsetParent 为空，不作判据）
      emptyText: empty ? empty.textContent : null,
      rows: list ? list.querySelectorAll('.nf-fc').length : 0,
      canvasPane: !!document.querySelector('.canvas-tab-pane[data-type="filechanges"]'),
    };
  });
}

test.describe('桌面 diff 面板空态误报（已打开 diff 不得显示「尚未打开」）', () => {
  test('E4 真实关页签 ⇒ 空态回显且列表清空；再点 ⇒ 重开、空态隐藏', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await note(page).click();
    await settle(page);
    await page.evaluate(async () => {
      const c = await import('/js/canvas.js');
      const id = [...document.querySelectorAll('.canvas-tab-pane[data-type="filechanges"]')]
        .map((p) => p.dataset.tabId)[0];
      c.closeTab(id);
    });
    await page.waitForTimeout(300);
    const closed = await snapshot(page);
    expect(closed.canvasPane).toBe(false);
    expect(closed.emptyVisible).toBe(true);
    expect(closed.emptyText).toContain('尚未打开 diff');
    expect(closed.rows).toBe(0);
    await note(page).click();
    await settle(page);
    const reopened = await snapshot(page);
    expect(reopened.canvasPane).toBe(true);
    expect(reopened.emptyVisible).toBe(false);
    expect(reopened.rows).toBe(2);
  });

  test('E6 locale 切换（打开态）不回显空态', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await note(page).click();
    await settle(page);
    await page.evaluate(() => window.dispatchEvent(new Event('locale-changed')));
    await page.waitForTimeout(150);
    const s = await snapshot(page);
    expect(s.canvasPane).toBe(true);
    expect(s.emptyVisible).toBe(false);
    expect(s.rows).toBe(2);
  });

  test('E7 面板实例残缺（列表子件丢失）⇒ 点开重建完备实例，列表正确', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await page.evaluate(() => document.querySelector('#panel-filechanges .nfp-list')?.remove());
    await note(page).click();
    await settle(page);
    const s = await snapshot(page);
    expect(s.canvasPane).toBe(true);
    expect(s.emptyVisible).toBe(false);
    expect(s.rows).toBe(2);
  });

  test('E1 点小注打开 ⇒ 空态隐藏、列表在场', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await note(page).click();
    await settle(page);
    const s = await snapshot(page);
    expect(s.canvasPane).toBe(true);
    expect(s.emptyVisible).toBe(false);
    expect(s.rows).toBe(2);
  });

  test('E2 重放（同批次帧重复注入）后再点 ⇒ 空态仍隐藏', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await injectCompletion(page);
    await note(page).click();
    await settle(page);
    const s = await snapshot(page);
    expect(s.emptyVisible).toBe(false);
    expect(s.rows).toBe(2);
  });

  test('E3 再点同一小注（切换收起后再开）⇒ 空态隐藏', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await note(page).click();
    await settle(page);
    await note(page).click(); // 收起
    await page.waitForTimeout(300);
    await note(page).click(); // 再开
    await settle(page);
    const s = await snapshot(page);
    expect(s.emptyVisible).toBe(false);
    expect(s.rows).toBe(2);
  });

  test('E5 阅读面在场时空态绝不可见', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    await note(page).click();
    await settle(page);
    const s = await snapshot(page);
    if (s.canvasPane) expect(s.emptyVisible).toBe(false);
  });
});

// 证据截图（DIFF_EVIDENCE_DIR 置位时才跑）：改前/改后同一路径的真实 UI 态。
const EVID = process.env.DIFF_EVIDENCE_DIR;
if (EVID) {
  test.describe('evidence', () => {
    test('shots', async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      const tag = process.env.DIFF_EVIDENCE_TAG || 'run';
      await bootApp(page);
      await injectCompletion(page);
      await page.screenshot({ path: `${EVID}/${tag}-0-delivery.png` });
      await note(page).click();
      await settle(page);
      await page.screenshot({ path: `${EVID}/${tag}-1-open-normal.png` });
      // 残缺实例路径（E7 同源）：列表子件丢失后点开
      await page.evaluate(() => document.querySelector('#panel-filechanges .nfp-list')?.remove());
      await page.evaluate(async () => { (await import('/js/activityBar.js')).setSideBarCollapsed(true); });
      await page.waitForTimeout(200);
      await note(page).click();
      await page.waitForTimeout(600);
      await page.evaluate(async () => { (await import('/js/activityBar.js')).showSidePanel('filechanges'); });
      await page.waitForTimeout(400);
      const st = await snapshot(page);
      console.log('SHOT ' + tag + ' ' + JSON.stringify(st));
      await page.screenshot({ path: `${EVID}/${tag}-2-open-after-incomplete-panel.png` });
    });
  });
}
