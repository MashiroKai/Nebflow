// reftag-redesign.spec.mjs — 引用块标签精简重设计回归 spec
// (单行紧凑 chip：类型图标+短标题；作者裁定 2026-09-02 21:59)。
//
// Verifies (验收四条，逐项自证):
//   T1 四类型混合并排 — chip 单行(28px)、容器无横向溢出、四来源类型图标互异、
//      短标题按规则取值且互异 (验收①②)。
//   T2 超长标题 — ellipsis 生效、chip 固定 footprint 不撑破 (验收①③之一)。
//   T3 元信息不丢 — 原生 title tooltip 含完整路径/页码/selector/session；
//      点击展开后 meta 预览行可见且含完整路径 (验收③)。
//   T4 亮暗双主题 + 展开态截图落盘 ~/.nebflow/docs/Nebflow/ (验收④)。
//
// Self-contained: spins up its own static server on an ephemeral port serving
// src/main/resources/web (never touches the running Nebflow instance / 8080).

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const DOCS = join(process.env.HOME, '.nebflow', 'docs', 'Nebflow');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

/** Static server for the web dir + harness fixture page. */
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/reftag-harness.html';
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = (p.startsWith('/js/') || p.startsWith('/css/')) ? join(WEB, p) : join(HERE, '..', p);
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

let server, port, base;

test.beforeAll(async () => {
  ({ server, port } = await startServer());
  base = `http://127.0.0.1:${port}`;
});
test.afterAll(async () => { server.close(); });

async function gotoHarness(page, scheme) {
  // Themes are plain prefers-color-scheme media queries in the real CSS —
  // emulateMedia flips every token (--color-bg, --glass-*, sapphire tint).
  await page.emulateMedia({ colorScheme: scheme });
  await page.setViewportSize({ width: 820, height: 480 });
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__reftagTest.ready === true');
}

const FILE_PATH = '/Users/dev/Claude code/Nebflow/src/main/scala/nebflow/cluster/DispatcherActor.scala';

test('T1 四类型混合 chip：单行 / 不溢出 / 图标互异 / 短标题互异', async ({ page }) => {
  await gotoHarness(page, 'dark');
  await page.evaluate('window.__reftagTest.render(window.__reftagTest.sets.mixed)');
  const chips = await page.evaluate('window.__reftagTest.chips()');
  expect(chips).toHaveLength(4);
  expect(chips.map((c) => c.refType)).toEqual(['file', 'document', 'task', 'html-element']);

  // 验收①: 每 chip 单行紧凑（固定 28px 高、标题不换行）
  for (const c of chips) {
    expect(c.height).toBe(28);
    expect(c.whiteSpace).toBe('nowrap');
  }
  // 验收①: 容器无横向溢出
  const box = await page.evaluate('window.__reftagTest.container()');
  expect(box.scrollW).toBeLessThanOrEqual(box.clientW);

  // 验收②: 四种来源类型图标互异（一眼区分 file/document/task/html-element）
  expect(new Set(chips.map((c) => c.icon)).size).toBe(4);

  // 验收②: 短标题取值规则 — file→文件名 / document→标题 / task→#号+标题 /
  // html-element→页面标题+<tag>；且互异
  expect(chips[0].title).toBe('DispatcherActor.scala');
  expect(chips[1].title).toBe('NIMA-D-26-00151.pdf');
  expect(chips[2].title).toBe('#312 压缩迁移：会话历史滚动压缩窗口');
  expect(chips[3].title).toBe('Global Reference — Nebflow Docs <table>');
  expect(new Set(chips.map((c) => c.title)).size).toBe(4);

  // 面上只剩 icon+标题+移除钮：meta 行不占位、⤢ 按钮不在面上
  const faceProbe = await page.evaluate(`(() => {
    const w = document.querySelector('#attachment-preview > .att-ref');
    return {
      metaDisplay: getComputedStyle(w.querySelector('.att-ref-meta')).display,
      expandBtns: w.querySelectorAll('.att-ref-expand').length,
      removeBtns: w.querySelectorAll('.att-ref-remove').length,
    };
  })()`);
  expect(faceProbe.metaDisplay).toBe('none');
  expect(faceProbe.expandBtns).toBe(0);
  expect(faceProbe.removeBtns).toBe(1);
});

test('T2 超长标题：ellipsis 生效、固定 footprint', async ({ page }) => {
  await gotoHarness(page, 'dark');
  await page.evaluate('window.__reftagTest.render(window.__reftagTest.sets.overflow)');
  const chips = await page.evaluate('window.__reftagTest.chips()');
  expect(chips).toHaveLength(5);
  const long = chips.find((c) => c.title.includes('超长标题截断省略号验证'));
  expect(long).toBeTruthy();
  expect(long.ellipsis).toBe('ellipsis');
  expect(long.labelScroll).toBeGreaterThan(long.labelClient); // 确有截断
  expect(long.height).toBe(28);                               // footprint 不变
  const box = await page.evaluate('window.__reftagTest.container()');
  expect(box.scrollW).toBeLessThanOrEqual(box.clientW);       // 5 chip 仍无横向溢出
  for (const c of chips) expect(c.height).toBe(28);
});

test('T3 元信息不丢：tooltip 完整元信息 + 点击展开预览行', async ({ page }) => {
  await gotoHarness(page, 'dark');
  await page.evaluate('window.__reftagTest.render(window.__reftagTest.sets.mixed)');
  const chips = await page.evaluate('window.__reftagTest.chips()');
  // 原生 title tooltip（全 app 既有机制）：完整路径 / 页码 / session / selector
  expect(chips[0].tooltip).toContain(FILE_PATH);
  expect(chips[0].tooltip).toContain('L12–48');
  expect(chips[1].tooltip).toContain('p.3–7');
  expect(chips[2].tooltip).toContain('session sess_9f2c');
  expect(chips[3].tooltip).toContain('https://nebflow.space/docs/global-reference');
  expect(chips[3].tooltip).toContain('selector: #spec > section.quota > table');

  // 点击展开 → meta 预览行可见，含完整路径等元信息（信息未丢）
  await page.evaluate('window.__reftagTest.clickAt(0)');
  expect(await page.evaluate('window.__reftagTest.metaVisibleAt(0)')).toBe(true);
  const meta = await page.evaluate('window.__reftagTest.metaTextAt(0)');
  expect(meta).toContain(FILE_PATH);
  expect(meta).toContain('L12–48');
  // 展开不破行高预算（A4 ≤72px）
  const h = await page.evaluate(`document.querySelector('#attachment-preview > .att-ref').getBoundingClientRect().height`);
  expect(h).toBeLessThanOrEqual(72);
});

test('T4 亮暗双主题 + 展开态截图', async ({ page }) => {
  await mkdir(DOCS, { recursive: true });
  await gotoHarness(page, 'dark');
  await page.evaluate('window.__reftagTest.render(window.__reftagTest.sets.overflow)');
  await page.waitForTimeout(350); // 入场动画 0.18s 落定
  await page.screenshot({ path: join(DOCS, '20260902_quotetag-redesign-dark.png') });

  await gotoHarness(page, 'light');
  await page.evaluate('window.__reftagTest.render(window.__reftagTest.sets.overflow)');
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(DOCS, '20260902_quotetag-redesign-light.png') });

  // 展开态（tooltip 预览行）：元信息可达的可视证明
  await page.evaluate('window.__reftagTest.clickAt(0)');
  await page.waitForTimeout(150);
  expect(await page.evaluate('window.__reftagTest.metaVisibleAt(0)')).toBe(true);
  await page.screenshot({ path: join(DOCS, '20260902_quotetag-redesign-tooltip.png') });
});

// ══ 2026-09-03 文件名重复渲染修复（追加断言组；既有 T1-T4 断言语义不动）══
// 根因（数据层）：canvas 标签页把「显示标题」当 fileName（canvas.js refInputForTab
// name = tab.title || basename，标题可无扩展名）→ extLabel 的 split('.').pop()
// 把整个无点名字当「扩展名」→ typeLabel = 完整文件名。污染面：
//   ① 消息内引用卡徽章（pageBadge||typeLabel）→ 标题旁再渲染一遍文件名（09-03 截图）；
//   ② 输入 chip 元信息行1（tooltip/展开预览）同源污染；
//   ③ 消息卡辅助行 normalizeSource 尾段=文件名本身，与标题重复。
// 修复：extLabel 仅在 basename 有真实点号时取扩展名；normalizeSource 辅助行=纯
// 目录路径（「…/」首部省略）；作者裁定（08-29「标签信息够区分即可」+ 09-03 预期）：
// 文件名恰出现一次、辅助行只放目录路径、块高固定。
const DUP_TITLE = 'PR #41 致谢关闭文案草稿';
const DUP_BASE = '20260903_pr41-close-draft.md';
const countStr = (hay, needle) => hay.split(needle).length - 1;

test('T5 无扩展名标题引用：文件名恰出现一次、徽章=类型标签、辅助行=纯目录', async ({ page }) => {
  await gotoHarness(page, 'dark');

  // 5a 输入 chip（未展开）：粗体标题唯一，元信息行不含文件名字符串
  await page.evaluate('window.__reftagTest.render(["canvasTitleFile"])');
  const chips = await page.evaluate('window.__reftagTest.chips()');
  expect(chips[0].title).toBe(DUP_TITLE);
  expect(chips[0].height).toBe(28);
  const chipProbe = await page.evaluate(`(() => {
    const w = document.querySelector('#attachment-preview > .att-ref');
    return {
      text: w.textContent,
      metaDisplay: getComputedStyle(w.querySelector('.att-ref-meta')).display,
      metaText: w.querySelector('.att-ref-meta').textContent,
      tooltip: w.title,
    };
  })()`);
  expect(chipProbe.metaDisplay).toBe('none');                    // 面上不占位（T1 同款）
  expect(countStr(chipProbe.text, DUP_TITLE)).toBe(1);           // 全块文件名恰一次
  expect(chipProbe.metaText.startsWith('MD · ')).toBe(true);     // typeLabel=类型,非文件名
  expect(chipProbe.metaText).not.toContain(DUP_TITLE);           // 元信息不再带标题串
  expect(chipProbe.tooltip.split('\n')[0]).toBe(DUP_TITLE);      // tooltip 行1=标题
  expect(countStr(chipProbe.tooltip, DUP_TITLE)).toBe(1);

  // 5b 展开态：文件名仍唯一、A4 预算不破
  await page.evaluate('window.__reftagTest.clickAt(0)');
  const expProbe = await page.evaluate(`(() => {
    const w = document.querySelector('#attachment-preview > .att-ref');
    return { text: w.textContent, expanded: w.classList.contains('expanded'),
             h: w.getBoundingClientRect().height };
  })()`);
  expect(expProbe.expanded).toBe(true);
  expect(countStr(expProbe.text, DUP_TITLE)).toBe(1);
  expect(expProbe.h).toBeLessThanOrEqual(72);

  // 5c 消息内引用卡（用户气泡附件卡 = 09-03 截图实体）：
  //    徽章=「MD」（修复前=整个文件名）；辅助行=纯目录路径（不含文件名/标题串）
  await page.evaluate('window.__reftagTest.renderCard("canvasTitleFile")');
  const card = await page.evaluate('window.__reftagTest.card()');
  expect(card.title).toBe(DUP_TITLE);
  expect(card.badge).toBe('MD');
  expect(countStr(card.text, DUP_TITLE)).toBe(1);                // 卡内文件名恰一次
  expect(card.source).toBe('…/Claude code/Nebflow');             // 纯目录、首部省略
  expect(card.source).not.toContain(DUP_BASE);                   // 不含文件名字符串
  expect(card.source).not.toContain(DUP_TITLE);
});

test('T6 60+ 字符长文件名：块高固定 28px 不撑高、四型同源面核验', async ({ page }) => {
  await gotoHarness(page, 'dark');
  await page.evaluate('window.__reftagTest.render(["canvasTitleFile", "longTitleFile"])');
  const chips = await page.evaluate('window.__reftagTest.chips()');
  const long = chips[1];
  expect(long.title.length).toBeGreaterThanOrEqual(60);          // 确为 60+ 场景
  expect(long.ellipsis).toBe('ellipsis');
  expect(long.labelScroll).toBeGreaterThan(long.labelClient);    // 确在截断
  expect(long.height).toBe(28);                                  // 不撑高
  // 长名恰一次在「可见面」断言：meta 行 display:none（不占面）；其 DOM 文本里的
  // 完整路径按 T3 语义保留（路径尾段必然含标题子串，非重复渲染），故此处核验
  // typeLabel（'MD · '）而非整体计数——整体计数由 T5（标题≠路径尾段）承担。
  const longProbe = await page.evaluate(`(() => {
    const w = document.querySelectorAll('#attachment-preview > .att-ref')[1];
    return {
      label: w.querySelector('.att-ref-label').textContent,
      metaDisplay: getComputedStyle(w.querySelector('.att-ref-meta')).display,
      metaText: w.querySelector('.att-ref-meta').textContent,
    };
  })()`);
  expect(longProbe.label).toBe(long.title);                      // 粗体标题=文件名本体
  expect(longProbe.metaDisplay).toBe('none');
  expect(longProbe.metaText.startsWith('MD · /')).toBe(true);    // 辅助行以类型+路径开头
  const box = await page.evaluate('window.__reftagTest.container()');
  expect(box.scrollW).toBeLessThanOrEqual(box.clientW);          // 无横向溢出

  // 同源长名进消息卡：徽章=类型标签、辅助行=纯目录
  await page.evaluate('window.__reftagTest.renderCard("longTitleFile")');
  const card = await page.evaluate('window.__reftagTest.card()');
  expect(card.badge).toBe('MD');
  expect(card.source).toBe('…/docs/notes');
  expect(countStr(card.text, long.title)).toBe(1);

  // 四型逐一过目：task 副标题=#任务号+标题、html-element=页面标题+<tag> ——
  // 设计上副标题与主标题不同源（非文件名重复域），断言其 meta 不回显主标题串
  await page.evaluate('window.__reftagTest.render(window.__reftagTest.sets.mixed)');
  const metaTask = await page.evaluate('window.__reftagTest.metaTextAt(2)');
  expect(metaTask).not.toContain('压缩迁移');                     // 不回显任务标题
  expect(metaTask).toContain('session sess_9f2c');                // 副标题= session 元信息
  const metaHtml = await page.evaluate('window.__reftagTest.metaTextAt(3)');
  expect(metaHtml).toContain('selector:');                        // 副标题= selector
});

test('T7 修复验证截图（亮暗双主题）落盘 docs/Nebflow', async ({ page }) => {
  await mkdir(DOCS, { recursive: true });
  const scene = async () => {
    await page.evaluate('window.__reftagTest.render(["canvasTitleFile", "longTitleFile"])');
    await page.evaluate('window.__reftagTest.renderCard("canvasTitleFile")');
    await page.waitForTimeout(350); // 入场动画落定
  };
  await gotoHarness(page, 'dark');
  await scene();
  await page.screenshot({ path: join(DOCS, '20260903-reftag-filename-dup-fix-dark.png') });
  await gotoHarness(page, 'light');
  await scene();
  await page.screenshot({ path: join(DOCS, '20260903-reftag-filename-dup-fix-light.png') });
});
