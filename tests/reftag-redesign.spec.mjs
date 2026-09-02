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
