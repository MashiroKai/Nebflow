// chat-diff-canvas.spec.mjs — 委托 diff「摘要上移 + 左右分体」验收
// （2026-10-22 作者令：「改为在最上面看，而且是一个小的摘要，点击了之后，在
//  左侧面板出现，diff的文件列表，在canvas显示选中文件的diff。等于是把目前的
//  这个面板拆为了左右两个面板」。
//  回访微调（2026-10-22 作者令「把diff胶囊靠右对齐」）：摘要 chip 靠右对齐，
//  右缘贴交付气泡内容右缘（T1 几何断言）。
//  谱系：bug #58 修 2026-10-09（file-diff → 委托结果行）→ 2026-10-10 裁定
//  （展开改 Canvas master-detail）→ 本批（交付气泡顶部摘要 chip；文件列表迁
//  左栏 #panel-filechanges；Canvas 只剩选中文件阅读面；旧一体大面板退役））。
//
// 被测真实链路（无 mock 替身层跳过渲染链）：
//   WS 帧 {type:'user', injected:true, fileChanges} → main.js onMessage('user')
//   → renderInjectedBubble → injectedRows.buildInjectedRow（交付气泡 + 顶部摘要 chip）
//   → 点 chip → diffPanel.toggleDiffBatch → 左栏面板渲染 + activityBar.showSidePanel
//   → diffCanvas.openFileChangesCanvas → canvas.js openTab(type 'filechanges') 阅读面。
//
// 断言面：
//   T1 摘要条渲染：折叠态交付气泡顶部 chip（`3 files · +20001 −5`、aria-pressed
//     =false）；chip **靠右对齐**（右缘贴气泡内容右缘，2026-10-22 回访令）；
//     标签行不再携带旧 hint（信息一处呈现）。
//   T2 气泡内零 diff 面：展开体零 .nf-filechanges / 零 details / 零 .nf-diff
//      （旧展开体 Changes 段退役）。
//   T3 点击联动：点 chip → #panel-filechanges 激活（3 行：徽标/路径/+/−/来源；
//      首行选中态；meta 合计）+ body.canvas-open + type=filechanges 面板，
//      焦点文件 = 第一个。
//   T4 旧面板不在 DOM：Canvas pane 零 .fcv / .fcv-head / .fcv-nav（master-detail
//      退役证明）；阅读面 = .fcv-view + .fcv-filehead。
//   T5 超大 diff 全量：20000 行 diff 全量渲染（行数精确 + 最后一行在场）。
//   T6 降级面：legit 降级（binary-file）原因行仍在；退役字符串零出现。
//   T7 面板行切换：点第二行 → Canvas 换文件；上一文件 460k 行从 DOM 卸载。
//   T8 多委托切换：批次 B chip → 面板换 B 文件列表、Canvas 换 B 焦点文件、
//      面板恒 1（preview 替换）；chip 激活态随批次走（A false / B true）。
//   T9 再点收起：已打开批次再点 chip → 侧栏收起（现有面板 toggle 语义）；再点
//      → 复原（面板激活 + 侧栏展开）。
//   T10 暗亮两态：翻 light 后 chip/面板/阅读面样式仍成立（全部颜色走既有
//      --color-* / glass token，零新色值的构造性证明）。
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

// ── 批次 A 载荷：3 文件。f1 = 20000 行 / ~460k chars（全量渲染样本）；
//    f2 = 普通小 diff；f3 = legit 降级（binary-file 原因行必须仍在）。
const BIG_LINES = 20000;
const bigDiff = [
  '@@ -1,4 +1,' + BIG_LINES + ' @@',
  ...Array.from({ length: BIG_LINES }, (_, i) => `+line-${i + 1}-of-${BIG_LINES}`),
].join('\n');
const smallDiff = '@@ -1,2 +1,2 @@\n-old\n+new';
const PAYLOAD_A = {
  nodeId: 'delegate-diff-batch-a',
  generatedAt: 1791999000000,
  root: '/tmp/ws-e2e',
  files: [
    { path: 'src/big.js', changeType: 'modified', added: BIG_LINES, removed: 4, source: 'git', diffText: bigDiff },
    { path: 'small.txt', changeType: 'modified', added: 1, removed: 1, source: 'git', diffText: smallDiff },
    { path: 'blob.bin', changeType: 'added', added: 0, removed: 0, source: 'git', degradedReason: 'binary-file' },
  ],
};
const LAST_BIG_LINE = `+line-${BIG_LINES}-of-${BIG_LINES}`;

// ── 批次 B 载荷：2 小文件（多委托切换样本）。
const PAYLOAD_B = {
  nodeId: 'delegate-diff-batch-b',
  generatedAt: 1791999000001,
  root: '/tmp/ws-e2e',
  files: [
    { path: 'docs/b.md', changeType: 'modified', added: 3, removed: 1, source: 'tool-ledger', diffText: '@@ -1,2 +1,3 @@\n-b\n+b\n+c' },
    { path: 'docs/c.md', changeType: 'added', added: 5, removed: 0, source: 'tool-ledger', diffText: '@@ -0,0 +1,2 @@\n+x\n+y' },
  ],
};

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'en'); // 面板标题断言定locale
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
async function injectCompletion(page, payload, header) {
  await page.evaluate(async ({ payload, header }) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: s.activeSessionId,
        source: 'delegate', eventType: 'completed',
        header,
        text: 'batch finished', timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }, { payload, header: header || 'DELEGATE · nebflow · diff-batch · COMPLETED' });
}

/** 批次 chip 的统一取用器（第 n 个交付摘要条）。 */
const chip = (page, n = 0) => page.locator('.bubble.injected .nf-diff-chip').nth(n);

test.describe('委托 diff 摘要上移 + 左右分体（2026-10-22 裁定）', () => {
  test('T1+T2 摘要条渲染于交付气泡顶部、气泡内零 diff 面', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);

    // T1 — 折叠态 chip 在场：紧凑摘要（文件数 · +X −Y）、可点击标识（button +
    //      aria-pressed 初始 false）；标签行回落纯四段头（旧 hint 摘除 = 信息
    //      一处呈现，不再重复）。
    const bubble = page.locator('.bubble.injected').first();
    const c = chip(page);
    await expect(c).toBeVisible();
    await expect(c).toHaveText(/3 files/);
    await expect(c).toContainText('+20001');
    await expect(c).toContainText('−5');
    await expect(c).toHaveAttribute('aria-pressed', 'false');
    // chip 位于交付气泡顶部（label 之后、正文 content 之前的同 bubble 内）。
    await expect(bubble.locator('.nf-diff-chip')).toHaveCount(1);
    await expect(bubble.locator('.injected-source-label')).toHaveText('DELEGATE · nebflow · diff-batch · COMPLETED');
    // chip 靠右对齐（2026-10-22 回访令「把diff胶囊靠右对齐」）：气泡 = block
    // 容器，chip 块级 flex + fit-content + margin-left:auto ⇒ 右缘贴气泡内容
    // 右缘（rightGap < 1px），左缘留白 > 0（非撑满、非居中）。
    const align = await c.evaluate((el) => {
      const bubbleEl = el.closest('.bubble');
      const cs = getComputedStyle(el);
      const gap = bubbleEl.getBoundingClientRect().right
        - parseFloat(getComputedStyle(bubbleEl).paddingRight)
        - el.getBoundingClientRect().right;
      return { rightGap: gap, display: cs.display, width: cs.width };
    });
    expect(align.rightGap).toBeLessThan(1);
    expect(align.display).toBe('flex');
    expect(parseFloat(align.width)).toBeGreaterThan(0);

    // T2 — 展开体内零 diff 面（旧 Changes 段退役）。
    await bubble.locator('.injected-source-label').click();
    await expect(bubble.locator('.nf-filechanges')).toHaveCount(0);
    await expect(bubble.locator('details')).toHaveCount(0);
    await expect(bubble.locator('.nf-diff')).toHaveCount(0);
  });

  test('T3+T4+T5 点 chip → 左栏面板 + Canvas 阅读面；旧 master-detail 不在 DOM；超大 diff 全量', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await chip(page).click();

    // T3 — 左栏面板激活：面板族语言（.panel.active）、3 行、首行选中、meta 合计。
    const panel = page.locator('#panel-filechanges');
    await expect(panel).toBeVisible();
    await expect(panel).toHaveClass(/active/);
    await expect(panel.locator('#panel-title-filechanges')).toHaveText('Changed Files');
    const rows = panel.locator('.nfp-list .nf-fc');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).locator('.nf-fc-badge')).toHaveText('modified');
    await expect(rows.nth(0).locator('.nf-fc-path')).toHaveText('src/big.js');
    await expect(rows.nth(0).locator('.nf-fc-counts')).toContainText('+20000');
    await expect(rows.nth(0).locator('.nf-fc-src')).toHaveText('git');
    await expect(rows.nth(2).locator('.nf-fc-badge')).toHaveText('added');
    await expect(rows.nth(0)).toHaveClass(/active/);
    await expect(panel.locator('.nfp-meta')).toContainText('3 files');
    await expect(panel.locator('.nfp-meta')).toContainText('+20001');
    await expect(panel.locator('.nfp-meta')).toContainText('−5');

    // T3 — Canvas 打开 + 焦点文件 = 第一个（阅读面 = .fcv-view + .fcv-filehead）。
    await expect(page.locator('body')).toHaveClass(/canvas-open/);
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();
    await expect(pane.locator('.fcv-view')).toHaveCount(1);
    await expect(pane.locator('.fcv-filehead')).toContainText('src/big.js');

    // T4 — 旧 master-detail 面板不在 DOM（一体大面板退役证明）。
    await expect(page.locator('.canvas-tab-pane .fcv')).toHaveCount(0);
    await expect(page.locator('.canvas-tab-pane .fcv-head')).toHaveCount(0);
    await expect(page.locator('.canvas-tab-pane .fcv-nav')).toHaveCount(0);

    // T5 — 超大 diff 全量渲染：行数精确 + 最后一行在场（无任何截断）。
    await expect(pane.locator('.nf-diff-row')).toHaveCount(BIG_LINES + 1, { timeout: 15000 });
    await expect(pane.locator('.nf-diff-body')).toContainText(LAST_BIG_LINE);
  });

  test('T6+T7 降级原因行保留、退役字符串零出现；面板行切换即卸载上一 diff', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await chip(page).click();
    const panel = page.locator('#panel-filechanges');
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();

    // T6 — 退役字符串零出现；选中降级文件 → 原因行仍在（合法降级不退役）。
    const body = await page.locator('body').innerText();
    expect(body).not.toContain('batch-too-large');
    await panel.locator('.nfp-list .nf-fc').nth(2).click();
    await expect(pane.locator('.fcv-filehead')).toContainText('blob.bin');
    await expect(pane.locator('.fcv-view')).toContainText('binary-file');
    await expect(panel.locator('.nfp-list .nf-fc').nth(2)).toHaveClass(/active/);

    // T7 — 点第二行 → 视图切换；上一个 460k 大 diff 从 DOM 卸载（切换即卸载）。
    await panel.locator('.nfp-list .nf-fc').nth(1).click();
    await expect(pane.locator('.fcv-filehead')).toContainText('small.txt');
    await expect(pane.locator('.nf-diff-body')).toContainText('+new');
    await expect(pane.locator('.nf-diff-body')).not.toContainText('line-1-of-20000');
    await expect(pane.locator('.nf-diff-row')).toHaveCount(3); // hunk + -old + +new（无尾换行 ⇒ 3 行）
  });

  test('T8 多委托切换：面板换批、Canvas 换焦点、chip 激活态随批走、面板数恒 1', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await injectCompletion(page, PAYLOAD_B, 'DELEGATE · nebflow · batch-b · COMPLETED');
    await expect(page.locator('.bubble.injected .nf-diff-chip')).toHaveCount(2);

    // 打开批次 A → 面板 3 行、chip A 激活。
    await chip(page, 0).click();
    const panel = page.locator('#panel-filechanges');
    await expect(panel.locator('.nfp-list .nf-fc')).toHaveCount(3);
    await expect(chip(page, 0)).toHaveAttribute('aria-pressed', 'true');
    await expect(chip(page, 1)).toHaveAttribute('aria-pressed', 'false');

    // 点批次 B chip → 面板换 B 文件列表（A 路径不在场）、Canvas 换 B 焦点文件、
    // 面板数恒 1（同 preview 组替换，无标签垃圾）、激活态易主。
    await chip(page, 1).click();
    await expect(panel.locator('.nfp-list .nf-fc')).toHaveCount(2);
    await expect(panel.locator('.nfp-list')).toContainText('docs/b.md');
    await expect(panel.locator('.nfp-list')).not.toContainText('src/big.js');
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toHaveCount(1);
    await expect(pane.locator('.fcv-filehead')).toContainText('docs/b.md');
    await expect(pane.locator('.nf-diff-body')).toContainText('+c');
    await expect(chip(page, 0)).toHaveAttribute('aria-pressed', 'false');
    await expect(chip(page, 1)).toHaveAttribute('aria-pressed', 'true');
  });

  test('T9 再点收起：已打开批次再点 chip → 侧栏收起；再点 → 复原', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await chip(page).click();
    const panel = page.locator('#panel-filechanges');
    await expect(panel).toBeVisible();
    await expect(page.locator('body')).not.toHaveClass(/sidebar-collapsed/);

    // 已打开（同批次 + 面板激活 + 侧栏展开）再点 → 收起侧栏（现有面板 toggle 语义）。
    await chip(page).click();
    await expect(page.locator('body')).toHaveClass(/sidebar-collapsed/);
    await expect(panel).not.toBeVisible(); // 收起 ⇒ 面板族全体失活

    // 再点 → 复原：侧栏展开 + 面板重新激活（同批次仍在面板承载）。
    await chip(page).click();
    await expect(page.locator('body')).not.toHaveClass(/sidebar-collapsed/);
    await expect(panel).toBeVisible();
    await expect(panel).toHaveClass(/active/);
    await expect(panel.locator('.nfp-list .nf-fc')).toHaveCount(3);
  });

  test('T10 暗亮两态：翻 light 后 chip / 面板 / 阅读面样式仍成立（零新色值）', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await chip(page).click();
    const panel = page.locator('#panel-filechanges');
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();

    await page.emulateMedia({ colorScheme: 'light' });
    await expect(chip(page)).toBeVisible();
    await expect(panel).toBeVisible();
    await expect(pane).toBeVisible();
    const reads = await pane.locator('.fcv-filehead').evaluate((el) => {
      const cs = getComputedStyle(el);
      return { borderBottom: cs.borderBottomWidth, bg: cs.backgroundColor };
    });
    expect(reads.borderBottom).toBe('1px');
    expect(reads.bg).not.toBe('');
    // chip 次档描边族仍在（sapphire button.glass-control：1px 描边 + 胶囊圆角 +
    // 非透明描边色；本代次档 = 透明底 + 描边，背景断言不适用）。
    const chipStyles = await chip(page).evaluate((el) => {
      const cs = getComputedStyle(el);
      return { border: cs.borderTopWidth, style: cs.borderTopStyle, color: cs.borderTopColor, radius: cs.borderRadius };
    });
    expect(chipStyles.border).toBe('1px');
    expect(chipStyles.style).toBe('solid');
    expect(chipStyles.color).not.toBe('rgba(0, 0, 0, 0)');
    expect(chipStyles.radius).toBe('999px');
  });
});
