// chat-diff-canvas.spec.mjs — 委托 diff「摘要上移 + 左右分体」验收
// （2026-10-22 作者令：「改为在最上面看，而且是一个小的摘要，点击了之后，在
//  左侧面板出现，diff的文件列表，在canvas显示选中文件的diff。等于是把目前的
//  这个面板拆为了左右两个面板」。
//  回访微调（2026-10-22 作者令「把diff胶囊靠右对齐」）：摘要 chip 靠右对齐，
//  右缘贴交付气泡内容右缘（T1 几何断言）。
//  同线微调（2026-10-22 作者令「计数与 COMPLETED 同行」+ 品牌归一 NEBFLOW→STAND）：
//  header 与 chip 同 flex 行（.nf-diff-headline），常规宽一行、窄面板截断；
//  夹具项目段 nebflow→stand（夹具即演示数据，与真实项目名无关）。
//  完工通知卡重设计批（2026-10-31 作者令「diff 胶囊很丑」+ 设计决定）：diff 胶囊
//  （.nf-diff-chip）**整体退役**——delegate 终态回执改三行卡面（批次短名+判词
//  单色徽标 / 一句话摘要 / +x−y 灰字小注 .nf-rcpt-note）；T1/T2/T12 随批重钉
//  （小注继承 chip 的 diff 面板入口职责：点击 → 左栏文件列表 + Canvas 阅读面），
//  T3–T11、T13 面板/阅读面断言面零改动。
//  回访三令（2026-10-22「居中+材质+动画节奏」）：Canvas 粘顶文件头标题胶囊
//  ①水平居中（sticky 视矩形按滚动容器自身 padding 内缩 ⇒ 水平恒零 padding，
//  胶囊在无 padding sticky 定心层内居中，T10 钉死居中几何与「钉住偏移随
//  padding 漂移（前会话实测 112px=56+56）」类回归）②材质走
//  sapphire.css 单源全量 frosted token（T10 材质断言）③动画节奏 0.3-0.45s
//  温和缓动、禁生硬瞬变（T10 时长断言 + T11 出现动画中间帧）。
//  追加判据（2026-10-22 作者截图令「胶囊挡住了其他文字和控件的查看和交互」）：
//  **胶囊与标题文字/相邻控件零重叠**——布局层面解决（.fcv-view 顶部 56px
//  有意让位带：卡头胶囊底缘 38 / deck fab 底缘 50 ⇒ 文件头胶囊整行位于
//  浮动带之下，宽度无关零重叠）+ pointer-events 不拦截下方命中（定心层
//  none/胶囊 auto，T13 几何不相交 + elementFromPoint 双臂钉死）。
//  谱系：bug #58 修 2026-10-09（file-diff → 委托结果行）→ 2026-10-10 裁定
//  （展开改 Canvas master-detail）→ 2026-10-22（交付气泡顶部摘要 chip；文件列表迁
//  左栏 #panel-filechanges；Canvas 只剩选中文件阅读面；旧一体大面板退役）
//  → 2026-10-31（chip 胶囊退役，灰字小注承位；完工通知卡三行面）。
//
// 被测真实链路（无 mock 替身层跳过渲染链）：
//   WS 帧 {type:'user', injected:true, fileChanges} → main.js onMessage('user')
//   → renderInjectedBubble → injectedRows.buildInjectedRow（交付气泡 + 卡面/小注）
//   → 点小注 → diffPanel.toggleDiffBatch → 左栏面板渲染 + activityBar.showSidePanel
//   → diffCanvas.openFileChangesCanvas → canvas.js openTab(type 'filechanges') 阅读面。
//
// 断言面：
//   T1 摘要条渲染：折叠态交付气泡内灰字小注（`+20001 −5`、aria-pressed=false、
//     零背景零边框零 padding——「不是 chip 不是胶囊」的构造性断言）；卡头 =
//     批次短名（DELEGATE · stand · diff-batch）+ 判词单色徽标（COMPLETED）；
//     摘要行 = payload 首行。
//   T2 气泡内零 diff 面：展开体零 .nf-filechanges / 零 details / 零 .nf-diff
//      （旧展开体 Changes 段退役）。
//   T3 点击联动：点小注 → #panel-filechanges 激活（3 行：徽标/路径/+/−/来源；
//      首行选中态；meta 合计）+ body.canvas-open + type=filechanges 面板，
//      焦点文件 = 第一个。
//   T4 旧面板不在 DOM：Canvas pane 零 .fcv / .fcv-head / .fcv-nav（master-detail
//      退役证明）；阅读面 = .fcv-view + .fcv-filehead。
//   T5 超大 diff 全量：20000 行 diff 全量渲染（行数精确 + 最后一行在场）。
//   T6 降级面：legit 降级（binary-file）原因行仍在；退役字符串零出现。
//   T7 面板行切换：点第二行 → Canvas 换文件；上一文件 460k 行从 DOM 卸载。
//   T8 多委托切换：批次 B 小注 → 面板换 B 文件列表、Canvas 换 B 焦点文件、
//      面板恒 1（preview 替换）；小注激活态随批次走（A false / B true）。
//   T9 再点收起：已打开批次再点小注 → 侧栏收起（现有面板 toggle 语义）；再点
//      → 复原（面板激活 + 侧栏展开）。
//   T10 粘顶文件头标题胶囊（回访三令）：材质 = 全量 frosted token（glass-bg 底/
//      blur saturate/描边/frost-shadow/999 圆角；旧全宽实条分隔线+实底退役）；
//      水平居中（左缝≈右缝 ≤1px）；静止 in-flow 零压首行 + 钉住偏移恒等
//      （scroll 500px 前后同值，防「padding 撑偏 sticky 约束矩形」回归）；动画
//      时长 ≥0.3s 慢档（出现 0.36s + 吸顶 scroll 时间线档 + 过渡 0.4s）。
//   T11 出现动画中间帧：换文件重建头部 ⇒ 0.36s 出现动画重放（窗内采样最小
//      不透明度 < 0.92 = 确有中间帧、非生硬瞬变）；终态归位 opacity=1。
//   T13 零重叠（追加令）：文件头胶囊 vs 卡头胶囊（.canvas-header）/deck fab
//      （#deck-fab）矩形双轴不相交；elementFromPoint 双臂——胶囊中心命中
//      胶囊自身、定心层空区（pointer-events:none）不拦截下方内容命中。
//   T12 暗亮两态：翻 light 后小注/面板/阅读面样式仍成立（全部颜色走既有
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
  }, { payload, header: header || 'DELEGATE · stand · diff-batch · COMPLETED' });
}

/** 批次小注的统一取用器（第 n 个交付摘要条；2026-10-31 起 = 灰字小注，旧 chip 退役）。 */
const note = (page, n = 0) => page.locator('.bubble.injected .nf-rcpt-note').nth(n);

test.describe('委托 diff 摘要上移 + 左右分体（2026-10-22 裁定）', () => {
  test('T1+T2 摘要条渲染于交付气泡（2026-10-31 卡面形态）、气泡内零 diff 面', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);

    // T1 — 折叠态小注在场：灰字一行（+X −Y）、可点击标识（button + aria-pressed
    //      初始 false）；「不是 chip 不是胶囊」构造性断言 = 零背景零边框零 padding。
    const bubble = page.locator('.bubble.injected').first();
    const c = note(page);
    await expect(c).toBeVisible();
    await expect(c).toHaveText('+20001 −5');
    await expect(c).toHaveAttribute('aria-pressed', 'false');
    await expect(bubble.locator('.nf-rcpt-note')).toHaveCount(1);
    // 旧胶囊彻底退役（零 .nf-diff-chip / 零 .nf-diff-headline 于 DOM）。
    await expect(bubble.locator('.nf-diff-chip')).toHaveCount(0);
    await expect(bubble.locator('.nf-diff-headline')).toHaveCount(0);
    // 完工通知卡头（重设计批）：批次短名 = header 去判词段（sender 缺席逐字保留
    // 中段）+ 判词单色徽标 + 一句话摘要行（payload 首行）。
    await expect(bubble.locator('.nf-rcpt-name')).toHaveText('DELEGATE · stand · diff-batch');
    await expect(bubble.locator('.nf-rcpt-badge')).toHaveText('COMPLETED');
    await expect(bubble.locator('.nf-rcpt-summary')).toHaveText('batch finished');
    // delegate 卡分支下无通用标签行（判词已从串尾段移入徽标）。
    await expect(bubble.locator('.injected-source-label')).toHaveCount(0);
    const noteLook = await c.evaluate((el) => {
      const cs = getComputedStyle(el);
      return {
        borderTopWidth: cs.borderTopWidth,
        backgroundColor: cs.backgroundColor,
        paddingLeft: cs.paddingLeft,
        color: cs.color,
        fontFamily: cs.fontFamily,
      };
    });
    expect(noteLook.borderTopWidth).toBe('0px');
    expect(noteLook.backgroundColor === 'rgba(0, 0, 0, 0)' || noteLook.backgroundColor === 'transparent').toBe(true);
    expect(noteLook.paddingLeft).toBe('0px');
    // 墨族灰字（--color-text-muted 面，双主题 token——暗亮两档由 T12 覆盖）。
    expect(noteLook.color).not.toBe('');
    expect(noteLook.fontFamily).toContain('monospace');
    // 判词徽标墨族单色（hairline 边框 + muted 文字，无色相填充）。
    const badgeLook = await bubble.locator('.nf-rcpt-badge').evaluate((el) => {
      const cs = getComputedStyle(el);
      return { borderTopWidth: cs.borderTopWidth, borderTopStyle: cs.borderTopStyle, color: cs.color, backgroundColor: cs.backgroundColor };
    });
    expect(badgeLook.borderTopWidth).toBe('1px');
    expect(badgeLook.borderTopStyle).toBe('solid');
    expect(badgeLook.backgroundColor === 'rgba(0, 0, 0, 0)' || badgeLook.backgroundColor === 'transparent').toBe(true);

    // T2 — 展开体内零 diff 面（旧 Changes 段退役）。展开点击面 = 卡头/摘要行
    //（.nf-rcpt-top 绑定折叠语义，旧 .injected-source-label 在 delegate 卡下不存在）。
    await bubble.locator('.nf-rcpt-top').click();
    await expect(bubble.locator('.injected-content')).toBeVisible();
    await expect(bubble.locator('.nf-filechanges')).toHaveCount(0);
    await expect(bubble.locator('details')).toHaveCount(0);
    await expect(bubble.locator('.nf-diff')).toHaveCount(0);
  });

  test('T3+T4+T5 点小注 → 左栏面板 + Canvas 阅读面；旧 master-detail 不在 DOM；超大 diff 全量', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await note(page).click();

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
    await note(page).click();
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

  test('T8 多委托切换：面板换批、Canvas 换焦点、小注激活态随批走、面板数恒 1', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await injectCompletion(page, PAYLOAD_B, 'DELEGATE · stand · batch-b · COMPLETED');
    await expect(page.locator('.bubble.injected .nf-rcpt-note')).toHaveCount(2);

    // 打开批次 A → 面板 3 行、小注 A 激活。
    await note(page, 0).click();
    const panel = page.locator('#panel-filechanges');
    await expect(panel.locator('.nfp-list .nf-fc')).toHaveCount(3);
    await expect(note(page, 0)).toHaveAttribute('aria-pressed', 'true');
    await expect(note(page, 1)).toHaveAttribute('aria-pressed', 'false');

    // 点批次 B 小注 → 面板换 B 文件列表（A 路径不在场）、Canvas 换 B 焦点文件、
    // 面板数恒 1（同 preview 组替换，无标签垃圾）、激活态易主。
    await note(page, 1).click();
    await expect(panel.locator('.nfp-list .nf-fc')).toHaveCount(2);
    await expect(panel.locator('.nfp-list')).toContainText('docs/b.md');
    await expect(panel.locator('.nfp-list')).not.toContainText('src/big.js');
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toHaveCount(1);
    await expect(pane.locator('.fcv-filehead')).toContainText('docs/b.md');
    await expect(pane.locator('.nf-diff-body')).toContainText('+c');
    await expect(note(page, 0)).toHaveAttribute('aria-pressed', 'false');
    await expect(note(page, 1)).toHaveAttribute('aria-pressed', 'true');
  });

  test('T9 再点收起：已打开批次再点小注 → 侧栏收起；再点 → 复原', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await note(page).click();
    const panel = page.locator('#panel-filechanges');
    await expect(panel).toBeVisible();
    await expect(page.locator('body')).not.toHaveClass(/sidebar-collapsed/);

    // 已打开（同批次 + 面板激活 + 侧栏展开）再点 → 收起侧栏（现有面板 toggle 语义）。
    await note(page).click();
    await expect(page.locator('body')).toHaveClass(/sidebar-collapsed/);
    await expect(panel).not.toBeVisible(); // 收起 ⇒ 面板族全体失活

    // 再点 → 复原：侧栏展开 + 面板重新激活（同批次仍在面板承载）。
    await note(page).click();
    await expect(page.locator('body')).not.toHaveClass(/sidebar-collapsed/);
    await expect(panel).toBeVisible();
    await expect(panel).toHaveClass(/active/);
    await expect(panel.locator('.nfp-list .nf-fc')).toHaveCount(3);
  });

  test('T10 粘顶文件头标题胶囊：frosted token 材质 + 水平居中 + 钉住几何恒等 + 动画慢档', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await note(page).click();
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    const view = pane.locator('.fcv-view');
    const head = pane.locator('.fcv-filehead');
    const pill = pane.locator('.fcv-headpill');
    await expect(pane).toBeVisible();
    await expect(head).toContainText('src/big.js');
    await expect(pill).toHaveCount(1);
    // 出现动画（0.36s，translateY 帧）走完再采几何——rect 采样含自身 transform，
    // 动画窗内读数失真（实测 offset 会偏成 0）。
    await page.waitForTimeout(500);

    // 材质（作者令②）：胶囊 = sapphire.css 单源全量 frosted token —— --glass-bg
    // 底（亮档 rgba(255,255,255,0.55) 直读）+ blur(--glass-blur) saturate(1.15)
    // + --glass-border 描边 + --frost-shadow 阴影 + 999 胶囊圆角。
    const mat = await pill.evaluate((el) => {
      const cs = getComputedStyle(el);
      return {
        radius: cs.borderRadius,
        border: cs.borderTopWidth + ' ' + cs.borderTopStyle,
        borderColor: cs.borderTopColor,
        bg: cs.backgroundColor,
        backdrop: cs.backdropFilter || cs.webkitBackdropFilter || '',
        shadow: cs.boxShadow,
      };
    });
    expect(mat.radius).toBe('999px');
    expect(mat.border).toBe('1px solid');
    expect(mat.borderColor).not.toBe('rgba(0, 0, 0, 0)');
    expect(mat.bg).toBe('rgba(255, 255, 255, 0.55)');
    expect(mat.backdrop).toContain('blur(24px)');
    expect(mat.backdrop).toContain('saturate(1.15)');
    expect(mat.shadow).not.toBe('none');
    // 旧全宽实条语言退役：定心层零分隔线、零实底、pointer-events 让位。
    const wrapper = await head.evaluate((el) => {
      const cs = getComputedStyle(el);
      return { bb: cs.borderBottomWidth, bg: cs.backgroundColor, pe: cs.pointerEvents };
    });
    expect(wrapper.bb).toBe('0px');
    expect(wrapper.bg).toBe('rgba(0, 0, 0, 0)');
    expect(wrapper.pe).toBe('none');

    // 居中几何（作者令①）：胶囊对 .fcv-view 内容盒水平居中（左缝≈右缝 ≤1px）。
    // sticky 视矩形 = scrollport 按滚动容器自身 padding 内缩 ⇒ .fcv-view 恒零
    // 水平 padding，前会话「钉住偏移 112px = 56+56 容器 padding 撑偏」类缺陷
    // 被此断言钉死。clientWidth 不含滚动条 = 真内容盒右缘。
    const center = await pill.evaluate((el) => {
      const v = el.closest('.fcv-view');
      const vr = v.getBoundingClientRect();
      const pr = el.getBoundingClientRect();
      return { gapL: pr.left - vr.left, gapR: vr.left + v.clientWidth - pr.right };
    });
    expect(Math.abs(center.gapL - center.gapR)).toBeLessThanOrEqual(1);

    // 静止零重叠 + 钉住偏移恒等（作者令①后半 + 追加零重叠令）：胶囊 in-flow，
    // 静止态不压 diff 首行；滚动 500px 钉住后偏移与静止恒等（= 56px 有意让位带
    // + 6px 定心层顶 padding，与滚程/容器水平 padding 无关）。
    const rest = await pill.evaluate((el) => {
      const v = el.closest('.fcv-view');
      const first = v.querySelector('.nf-diff-row');
      return {
        offset: el.getBoundingClientRect().top - v.getBoundingClientRect().top,
        overlap: first
          ? el.getBoundingClientRect().bottom > first.getBoundingClientRect().top
          : false,
      };
    });
    expect(rest.offset).toBeGreaterThan(50); // 56 带 + 6 定心层内缩（≥56 判段）
    expect(rest.offset).toBeLessThan(80); // 非 112px 漂移量级
    expect(rest.overlap).toBe(false);
    await view.evaluate((el) => { el.scrollTop = 500; });
    await page.waitForTimeout(80);
    const stuckOffset = await pill.evaluate((el) =>
      el.getBoundingClientRect().top - el.closest('.fcv-view').getBoundingClientRect().top);
    expect(Math.abs(stuckOffset - rest.offset)).toBeLessThanOrEqual(1.5);
    await view.evaluate((el) => { el.scrollTop = 0; });

    // 动画节奏（作者令③）：出现 = 0.36s 温和缓出；吸顶 = scroll() 时间线阴影
    // 爬坡档（@supports 档，Playwright Chromium 支持）；过渡 0.4s ≥ 0.3s 慢档。
    const anim = await pill.evaluate((el) => {
      const cs = getComputedStyle(el);
      return { name: cs.animationName, dur: cs.animationDuration, trans: cs.transitionDuration };
    });
    expect(anim.name).toContain('fcv-head-in');
    expect(anim.name).toContain('fcv-head-stuck');
    expect(anim.dur.split(',').map((s) => parseFloat(s))).toContain(0.36);
    const transMs = anim.trans.split(',').map((s) => parseFloat(s));
    expect(transMs.length).toBeGreaterThan(0);
    expect(transMs.every((s) => s >= 0.3)).toBe(true);
  });

  test('T11 出现动画中间帧：换文件重建头部 ⇒ 0.36s 缓入重放（半途帧 + 终态归位）', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await note(page).click();
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane.locator('.fcv-filehead')).toContainText('src/big.js');
    await page.waitForTimeout(600); // 首次出现动画走完，采干净基线

    // 换文件（面板第二行）→ renderReader 重建头部 ⇒ 出现动画重放；在动画窗内
    // 逐帧采样最小不透明度（rAF 轮询 600ms 覆盖 0.36s 动画全程）。逐帧重查
    // 元素——点击后头部节点在数十 ms 内才被重建，缓存旧节点会采到已完成的
    // 旧动画（实测旧节点 opacity 恒 1 ⇒ 假阴）。
    await page.locator('#panel-filechanges .nfp-list .nf-fc').nth(1).click();
    const minOpacity = await page.evaluate(() => new Promise((resolve) => {
      let min = 1;
      const t0 = performance.now();
      const step = () => {
        const pill = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill');
        if (pill) {
          const o = parseFloat(getComputedStyle(pill).opacity);
          if (Number.isFinite(o)) min = Math.min(min, o);
        }
        if (performance.now() - t0 > 600) resolve(min);
        else requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    }));
    expect(minOpacity).toBeLessThan(0.92); // 确有「半途」中间帧 = 非生硬瞬变
    await expect(pane.locator('.fcv-filehead')).toContainText('small.txt');
    const settled = await pane.locator('.fcv-headpill').evaluate((el) => getComputedStyle(el).opacity);
    expect(settled).toBe('1'); // 终态归位
  });

  test('T13 零重叠（作者追加令）：文件头胶囊 vs 卡头胶囊/deck fab 不相交 + 命中穿透', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await note(page).click();
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    const pill = pane.locator('.fcv-headpill');
    await expect(pill).toHaveCount(1);
    // 出现动画（0.36s，translateY 帧）走完再采几何——rect 采样含自身 transform。
    await page.waitForTimeout(500);

    // 几何臂：文件头胶囊矩形与卡头胶囊（.canvas-header，顶带横轴居中 —— 居中批
    // 2026-10-23；fcv-headpill 在 56px 让位带内 offsetTop ≥50，纵轴错开零重叠）
    // 及 deck fab（#deck-fab，fixed top10/right12，canvas-open 时在场）双轴
    // 不相交——56px 让位带的布局层保证（任意路径宽/面板宽成立）。
    const geo = await page.evaluate(() => {
      const p = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill');
      const cap = document.querySelector('#canvas-panel .canvas-header');
      const fab = document.getElementById('deck-fab');
      const hit = (a, b) => !(a.left >= b.right || b.left >= a.right
        || a.top >= b.bottom || b.top >= a.bottom);
      const pr = p.getBoundingClientRect();
      const fabVisible = fab && getComputedStyle(fab).display !== 'none'
        && fab.getBoundingClientRect().width > 0;
      return {
        capOverlap: cap ? hit(pr, cap.getBoundingClientRect()) : false,
        fabOverlap: fabVisible ? hit(pr, fab.getBoundingClientRect()) : false,
        fabThere: !!fabVisible,
      };
    });
    expect(geo.capOverlap).toBe(false);
    expect(geo.fabOverlap).toBe(false);

    // 命中臂：胶囊中心 = 命中胶囊自身（tooltip/文本选择可用）；定心层空区
    //（pointer-events:none，胶囊左旁 24px 同高度）= 不得命中定心层——遮挡
    // 元素不得拦截下方内容命中（追加令原文）。
    const hitTest = await pill.evaluate((el) => {
      const r = el.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      const cy = r.top + r.height / 2;
      const c = document.elementFromPoint(cx, cy);
      const s = document.elementFromPoint(Math.max(r.left - 24, 1), cy);
      return {
        centerIsPill: !!(c && c.closest('.fcv-headpill')),
        sideHitsWrapper: !!(s && s.closest('.fcv-filehead')),
      };
    });
    expect(hitTest.centerIsPill).toBe(true);
    expect(hitTest.sideHitsWrapper).toBe(false);
  });

  test('T12 暗亮两态：翻 light 后小注 / 面板 / 阅读面样式仍成立（零新色值）', async ({ page }) => {
    await bootApp(page);
    await injectCompletion(page, PAYLOAD_A);
    await note(page).click();
    const panel = page.locator('#panel-filechanges');
    const pane = page.locator('.canvas-tab-pane[data-type="filechanges"]');
    await expect(pane).toBeVisible();

    await page.emulateMedia({ colorScheme: 'light' });
    await expect(note(page)).toBeVisible();
    await expect(panel).toBeVisible();
    await expect(pane).toBeVisible();
    // 阅读面 = 定心层 + 胶囊：胶囊材质 token 亮档成立（glass-bg 亮值）、定心层
    // 保持透明零边（旧实条语言不回流）。
    const reads = await pane.locator('.fcv-headpill').evaluate((el) => {
      const cs = getComputedStyle(el);
      return { bg: cs.backgroundColor, backdrop: cs.backdropFilter || cs.webkitBackdropFilter || '' };
    });
    expect(reads.bg).toBe('rgba(255, 255, 255, 0.55)');
    expect(reads.backdrop).toContain('saturate(1.15)');
    // 小注墨族灰字在亮档同样成立：透明底 + 零边框 + 非透明文字色（「灰字小注」
    // 形态双主题不漂移；旧 glass chip 的描边/999 圆角断言随胶囊退役移除）。
    // 单行化+hover 批（2026-10-31 22:29）：小注为分块 hover 单元（hover 上底色），
    // 上文 click 后指针仍悬停小注 ⇒ 先移开指针再读 rest 构造（零背景是 rest 断言）。
    await page.mouse.move(2, 2);
    await page.waitForTimeout(300); // hover 底色 0.15s 过渡走完再读 rest 构造。
    const noteStyles = await note(page).evaluate((el) => {
      const cs = getComputedStyle(el);
      return { border: cs.borderTopWidth, bg: cs.backgroundColor, color: cs.color };
    });
    expect(noteStyles.border).toBe('0px');
    expect(noteStyles.bg === 'rgba(0, 0, 0, 0)' || noteStyles.bg === 'transparent').toBe(true);
    expect(noteStyles.color).not.toBe('rgba(0, 0, 0, 0)');
    // 判词徽标 hairline 边框亮档成立（墨族单色，两档同形态）。
    const badgeStyles = await page.locator('.bubble.injected .nf-rcpt-badge').evaluate((el) => {
      const cs = getComputedStyle(el);
      return { border: cs.borderTopWidth, style: cs.borderTopStyle, color: cs.color };
    });
    expect(badgeStyles.border).toBe('1px');
    expect(badgeStyles.style).toBe('solid');
    expect(badgeStyles.color).not.toBe('rgba(0, 0, 0, 0)');
  });
});
