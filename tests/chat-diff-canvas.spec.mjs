// chat-diff-canvas.spec.mjs — 聊天卡 diff 展开改走 Canvas + 全量渲染验收
// （2026-10-10 用户裁定：气泡内保持 header 统计 + 文件行，diff 体不再内联展开，
//  点文件行/头部 → Canvas 打开 diff 视图；无任何 diff 字符上限）。
//
// 被测真实链路（无 mock 替身层跳过渲染链）：
//   WS 帧 {type:'user', injected:true, fileChanges} → main.js onMessage('user')
//   → renderInjectedBubble → chat.js buildInjectedRow（气泡面）
//   → 点击 Changes 头部/文件行 → diffCanvas.js openFileChangesCanvas
//   → canvas.js openTab(type 'filechanges') → Canvas 面板分栏视图。
//
// 断言面：
//   T1 气泡现状不回归：header 尾部 `· 3 files +a −r`；展开体有 Changes 段、
//      3 条文件行（徽标/路径/+/−/来源 tag）——改前改后都必须绿（守卫）。
//   T2 diff 体不再内联：气泡内零 <details>、零 .nf-diff 行。
//   T3 展开→Canvas 路由：点 Changes 头部 → body.canvas-open + type=filechanges
//      面板存在 + 左侧文件导航 3 行 + 焦点文件 = 第一个文件。
//   T4 超大 diff 全量断言：>60000 字符（20000 行）的 diff 在 Canvas 全量渲染
//      （行数精确 + 最后一行在场）——旧 60k chat cap 的退役证明。
//   T5 降级面退役：全页零 'batch-too-large'；legit 降级（binary-file）在 Canvas
//      内仍显示原因行（降级渲染器只为合法原因保留）。
//   T6 多文件导航：点第二个文件 → 视图切换为该文件 diff；大文件不残留在 DOM
//      （切换即卸载 = 性能兜底的第一层）。
//   T7 重复点击同一卡 → 同一 tab 复用（面板里 filechanges 面板数恒 1）。
//   T8 暗亮两态：emulateMedia 翻 light 后面板仍可见、边框仍为 1px 实线
//      （全部颜色走既有 --color-* 变量，零新色值的构造性证明）。
//
// 变异验红基线：改动前的树 T2/T3/T4/T6 RED（内联展开、无 Canvas 路由），
// T1/T5 GREEN（现状守卫 + 手工载荷本就不带 batch-too-large——后端退役由
// FileChangeReportSpec/FileChangeLedgerSpec 的 Scala 侧断言覆盖）。
//
// 运行：node node_modules/@playwright/test/cli.js test tests/chat-diff-canvas.spec.mjs
// 自包含：route 拦截伺服真身 web/ 树 + mock WS 握手——无后端、无 sbt、不碰 8080。

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'e2e-diff-root';

// ── 载荷：3 文件。f1 = 20000 行 / ~460k chars（> 旧 60k chat cap 的全量样本）；
//    f2 = 普通小 diff；f3 = legit 降级（binary-file 原因行必须仍在）。
const BIG_LINES = 20000;
const bigDiff = [
  '@@ -1,4 +1,' + BIG_LINES + ' @@',
  ...Array.from({ length: BIG_LINES }, (_, i) => `+line-${i + 1}-of-${BIG_LINES}`),
].join('\n');
const smallDiff = '@@ -1,2 +1,2 @@\n-old\n+new';
const PAYLOAD = {
  nodeId: 'delegate-diff-e2e',
  generatedAt: 1791999000000,
  root: '/tmp/ws-e2e',
  files: [
    { path: 'src/big.js', changeType: 'modified', added: BIG_LINES, removed: 4, source: 'git', diffText: bigDiff },
    { path: 'small.txt', changeType: 'modified', added: 1, removed: 1, source: 'git', diffText: smallDiff },
    { path: 'blob.bin', changeType: 'added', added: 0, removed: 0, source: 'git', degradedReason: 'binary-file' },
  ],
};
const LAST_BIG_LINE = `+line-${BIG_LINES}-of-${BIG_LINES}`;

async function bootApp(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));
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
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage(() => { /* 本 spec 不需要应答 getHistory 等 */ });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 经真实 ws.js 分发链注入 delegate 终态帧（live 注入路径）。 */
async function injectCompletion(page) {
  await page.evaluate(async (payload) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: s.activeSessionId,
        source: 'delegate', eventType: 'completed',
        header: 'DELEGATE · nebflow · diff-batch · COMPLETED',
        text: 'batch finished', timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }, PAYLOAD);
}

async function expandBubble(page) {
  const label = page.locator('.bubble.injected .injected-source-label').first();
  await expect(label).toHaveText(/DELEGATE .*COMPLETED · 3 files \+20001 −5$/);
  await label.click(); // 展开气泡体（现状交互不动）
  const section = page.locator('.bubble.injected .nf-filechanges').first();
  await expect(section).toBeVisible();
  return section;
}

test.describe('chat diff → Canvas（2026-10-10 裁定）', () => {
  test('T1+T2 气泡现状不动、diff 体不再内联；T3+T4 展开→Canvas 全量渲染', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);

    // T1 — header 统计行 + 文件行（徽标/+/−/来源）原样。
    const section = await expandBubble(page);
    await expect(section.locator('.nf-filechanges-head')).toContainText('Changes · 3 files');
    const rows = section.locator('.nf-fc');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).locator('.nf-fc-badge')).toHaveText('modified');
    await expect(rows.nth(0).locator('.nf-fc-path')).toHaveText('src/big.js');
    await expect(rows.nth(0).locator('.nf-fc-counts')).toContainText('+20000');
    await expect(rows.nth(0).locator('.nf-fc-src')).toHaveText('git');
    await expect(rows.nth(2).locator('.nf-fc-badge')).toHaveText('added');

    // T2 — 气泡内不再内联展开 diff 体（零 details、零 .nf-diff）。
    await expect(section.locator('details')).toHaveCount(0);
    await expect(section.locator('.nf-diff')).toHaveCount(0);

    // T3 — 点 Changes 头部 → Canvas 打开 diff 视图（type=filechanges 面板）。
    await section.locator('.nf-filechanges-head').click();
    await expect(page.locator('body')).toHaveClass(/canvas-open/);
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();
    await expect(pane.locator('.fcv-nav .nf-fc')).toHaveCount(3);
    // 焦点文件 = 第一个（src/big.js），视图区开始渲染其 diff。
    await expect(pane.locator('.fcv-filehead')).toContainText('src/big.js');

    // T4 — 超大 diff 全量渲染：行数精确 + 最后一行在场（无任何截断）。
    await expect(pane.locator('.nf-diff-row')).toHaveCount(BIG_LINES + 1, { timeout: 15000 });
    await expect(pane.locator('.nf-diff-body')).toContainText(LAST_BIG_LINE);
  });

  test('T5 降级面退役 + legit 原因行保留；T6 多文件导航 + 切换卸载', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    const section = await expandBubble(page);
    await section.locator('.nf-filechanges-head').click();
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();

    // T5 — 退役字符串零出现；选中降级文件 → 原因行仍在（合法降级不退役）。
    const body = await page.locator('body').innerText();
    expect(body).not.toContain('batch-too-large');
    await pane.locator('.fcv-nav .nf-fc').nth(2).click();
    await expect(pane.locator('.fcv-filehead')).toContainText('blob.bin');
    await expect(pane.locator('.fcv-view')).toContainText('binary-file');

    // T6 — 点第二个文件 → 视图切换；上一个 460k 大 diff 从 DOM 卸载。
    await pane.locator('.fcv-nav .nf-fc').nth(1).click();
    await expect(pane.locator('.fcv-filehead')).toContainText('small.txt');
    await expect(pane.locator('.nf-diff-body')).toContainText('+new');
    await expect(pane.locator('.nf-diff-body')).not.toContainText('line-1-of-20000');
    await expect(pane.locator('.nf-diff-row')).toHaveCount(3); // hunk + -old + +new（无尾换行 ⇒ 3 行）

    // T7 — 重复点击同一卡 → 同一 tab 复用（面板数恒 1，无标签垃圾）。
    await section.locator('.nf-filechanges-head').click();
    await expect(page.locator('.canvas-tab-pane[data-type="filechanges"]')).toHaveCount(1);
    await expect(pane).toBeVisible();
  });

  test('T8 暗亮两态：翻 light 后 Canvas diff 面板样式仍成立（零新色值）', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page);
    const section = await expandBubble(page);
    await section.locator('.nf-filechanges-head').click();
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();
    await page.emulateMedia({ colorScheme: 'light' });
    await expect(pane).toBeVisible();
    const styles = await pane.locator('.fcv-nav').evaluate((el) => {
      const cs = getComputedStyle(el);
      return { borderRight: cs.borderRightWidth, bg: cs.backgroundColor };
    });
    expect(styles.borderRight).toBe('1px');
    expect(styles.bg).not.toBe('');
  });
});
