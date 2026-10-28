// md-backlinks.spec.mjs — KG 审计②「markdown viewer backlinks 区块」回归（2026-10-28）。
//
// 依据：reports/20261028_103500_knowledge-graph-audit §3 缺口——「文档阅读页没有
// backlinks 区块：『被引』只在图谱左栏详情卡」。本批把入边带到阅读面：文档页底部
// 折叠小节（<details> 默认收起），数据 = /api/wiki/graph 载荷（纯前端可算，零新端
// 点）；文档不在任何 vault 或零入链 ⇒ 整节不渲染（图外文件零噪音）。
//
// 判据面 = the RENDERED DOM（真实 viewMarkdown 链路；唯一替身 = /api/wiki/graph
// 应答的 fetch stub，载荷形状与 WikiIndex.nodeJson/edgeJson 逐字段同构）。七个面：
//   BL1 被引文档：区块在场 + 默认收起 + 恰 N 条 + 解析载荷形状（本地 absPath /
//       远端 remote）+ 边锚点击形态（'^blk' 原样 / '#锚' 去井号）
//   BL2 折叠交互红线：点 summary 展开 → details[open]
//   BL3 零入链文档：区块不渲染（克制面）
//   BL4 图外文档（repo reports/ 等不在任何 vault）：区块不渲染（零噪音面）
//   BL5 图谱不可用（403）：区块不渲染、正文照常（#762 渲染腿静默降级纪律）
//   BL6 跨项目入链：条目带项目标签 + remote 解析形状
//   BL7 委派契约：条目 = a.nf-wikilink（与正文 wikilink 共用同一条点击腿）
//
// 跑法：node <repo>/node_modules/@playwright/test/cli.js test tests/md-backlinks.spec.mjs

import { test, expect } from '@playwright/test';
import http from 'node:http';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/md-backlinks/harness.html';
const HARNESS_FILE = path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, ''));

const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;

// ---- 合成图谱载荷（形状 = WikiIndex.nodeJson / edgeJson / assembleMerged）----
const GRAPH = {
  generatedAt: 1,
  projects: [
    { name: 'stand', device: 'self', workspace: '/tmp/ws', vault: '/tmp/ws/docs', docCount: 4, stub: false },
    { name: 'peer', device: 'dev-9', workspace: '/x', vault: '/x/docs', docCount: 1, stub: false },
  ],
  nodes: [
    { id: 'stand:design/target-doc.md', project: 'stand', device: 'self', rel: 'design/target-doc.md', title: '目标文档', stub: false, kind: 'doc' },
    { id: 'stand:design/citing-a.md', project: 'stand', device: 'self', rel: 'design/citing-a.md', title: '引用甲', stub: false, kind: 'doc' },
    { id: 'peer:notes/citing-b.md', project: 'peer', device: 'dev-9', rel: 'notes/citing-b.md', title: '引用乙（远端）', stub: false, kind: 'doc' },
    { id: 'stand:design/glossary.md', project: 'stand', device: 'self', rel: 'design/glossary.md', title: '词汇表', stub: false, kind: 'doc' },
    { id: 'stand:design/no-links.md', project: 'stand', device: 'self', rel: 'design/no-links.md', title: '无引用文档', stub: false, kind: 'doc' },
  ],
  edges: [
    { from: 'stand:design/citing-a.md', to: 'stand:design/target-doc.md', kind: 'link', anchor: '^blk-9', line: 12 },
    { from: 'peer:notes/citing-b.md', to: 'stand:design/target-doc.md', kind: 'link', anchor: '#用法', line: 3 },
    { from: 'stand:design/target-doc.md', to: 'stand:design/glossary.md', kind: 'link', anchor: null, line: 5 },
  ],
};

const TARGET_DOC = '# 目标文档\n\n正文一段。[[glossary]] 词条引用。\n';
const GLOSSARY_DOC = '# 词汇表\n\n术语单源。\n';
const NO_LINKS_DOC = '# 无引用文档\n\n零出链零入链（孤儿形态的前半）。\n';

let port;
let srv;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
    s.on('error', reject);
  });
}

function startStaticServer(port, dir) {
  const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json',
    '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  };
  const srv = http.createServer((req, res) => {
    let p = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (p.endsWith('/')) p += 'index.html';
    const file = path.join(dir, p);
    if (!file.startsWith(dir)) { res.writeHead(403); res.end(); return; }
    try {
      const b = fs.readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.')).toLowerCase()] || 'application/octet-stream' });
      res.end(b);
    } catch { res.writeHead(404); res.end('not found'); }
  });
  srv.listen(port, '127.0.0.1');
  return srv;
}

test.beforeAll(async () => {
  // 红腿引导（NB_UX_ROOT 指基线树时，harness 覆盖复制过去——turn-terminal 同款自举）。
  const serveHarness = path.join(SERVE_ROOT, HARNESS_PATH.replace(/^\//, ''));
  if (path.resolve(serveHarness) !== path.resolve(HARNESS_FILE)) {
    fs.mkdirSync(path.dirname(serveHarness), { recursive: true });
    fs.copyFileSync(HARNESS_FILE, serveHarness);
  }
  expect(fs.existsSync(serveHarness)).toBe(true);
  port = await freePort();
  srv = startStaticServer(port, path.resolve(SERVE_ROOT));
});

test.afterAll(async () => {
  if (srv) await new Promise((r) => srv.close(r));
});

async function openPage(page) {
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  await page.evaluate((g) => window.__setGraph(g), GRAPH);
}

async function openDoc(page, content, absPath, fileName) {
  return page.evaluate(([c, a, f]) => window.__openDoc(c, a, f), [content, absPath, fileName]);
}

test('BL1 被引文档：折叠区块在场，恰 2 条，解析载荷与边锚点击形态正确', async ({ page }) => {
  await openPage(page);
  const c = await openDoc(page, TARGET_DOC, '/tmp/ws/docs/design/target-doc.md', 'target-doc.md');
  expect(c.viewerMounted, 'viewer 照常挂载').toBe(true);
  expect(c.blockPresent, 'backlinks 区块在场').toBe(true);
  expect(c.collapsed, '默认收起（克制形态）').toBe(true);
  expect(c.summary, `摘要带计数（got ${c.summary}）`).toContain('反向链接');
  expect(c.summary).toContain('2');
  expect(c.entries).toHaveLength(2);
  const a = c.entries.find((e) => e.title === '引用甲');
  expect(a, '本地入链条目在场').toBeTruthy();
  expect(a.cls, '条目 = a.nf-wikilink（委派契约面）').toContain('nf-wikilink');
  expect(a.resolved.absPath, '本地解析 = vault + rel（POSIX sep）').toBe('/tmp/ws/docs/design/citing-a.md');
  expect(a.anchor, '块锚 ^ 形态原样透传').toBe('^blk-9');
  const b = c.entries.find((e) => e.title === '引用乙（远端）');
  expect(b, '远端入链条目在场').toBeTruthy();
  expect(b.resolved.remote, '远端解析 = remote 形状').toEqual({ device: 'dev-9', vault: '/x/docs', rel: 'notes/citing-b.md', project: 'peer' });
  expect(b.anchor, '标题锚去井号（点击腿认识 Heading 形态）').toBe('用法');
  expect(c.anchorChips.sort(), '边锚信息 chip 原样展示').toEqual(['#用法', '^blk-9']);
  expect(c.projectTags, '跨项目入链带项目标签').toEqual(['peer']);
});

test('BL2 折叠交互红线：点 summary 展开（details[open]）', async ({ page }) => {
  await openPage(page);
  await openDoc(page, TARGET_DOC, '/tmp/ws/docs/design/target-doc.md', 'target-doc.md');
  await page.click('.nf-backlinks summary');
  const open = await page.evaluate(() => document.querySelector('.nf-backlinks').hasAttribute('open'));
  expect(open, '点击后展开（原生 details 行为）').toBe(true);
});

test('BL3 零入链文档：区块不渲染（克制面——不为零计数渲染空节）', async ({ page }) => {
  await openPage(page);
  const c = await openDoc(page, NO_LINKS_DOC, '/tmp/ws/docs/design/no-links.md', 'no-links.md');
  expect(c.viewerMounted, 'viewer 照常挂载').toBe(true);
  expect(c.blockPresent, '零入链 ⇒ 无区块').toBe(false);
});

test('BL4 图外文档（不在任何 vault）：区块不渲染（repo reports/ 等零噪音）', async ({ page }) => {
  await openPage(page);
  const c = await openDoc(page, GLOSSARY_DOC, '/tmp/reports/20261028_some-batch.md', '20261028_some-batch.md');
  expect(c.viewerMounted, 'viewer 照常挂载').toBe(true);
  expect(c.blockPresent, '图外路径 ⇒ 无区块').toBe(false);
});

test('BL5 图谱不可用（403）：区块不渲染、正文照常（渲染腿静默降级纪律）', async ({ page }) => {
  await openPage(page);
  await page.evaluate(() => window.__setGraph(null, 403));
  const c = await openDoc(page, TARGET_DOC, '/tmp/ws/docs/design/target-doc.md', 'target-doc.md');
  expect(c.viewerMounted, 'viewer 照常挂载').toBe(true);
  expect(c.blockPresent, '图谱不可用 ⇒ 无区块（背景面，绝不拉重登）').toBe(false);
  const bodyText = await page.evaluate(() => document.querySelector('.canvas-md-viewer').textContent || '');
  expect(bodyText, '正文照常渲染').toContain('正文一段');
});

test('BL6/BL7 委派契约：条目复用 a.nf-wikilink 点击腿（data-wiki-resolved 直填）', async ({ page }) => {
  await openPage(page);
  await openDoc(page, TARGET_DOC, '/tmp/ws/docs/design/target-doc.md', 'target-doc.md');
  const faces = await page.evaluate(() => {
    const as = [...document.querySelectorAll('.nf-backlinks a.nf-backlink')];
    return as.map((a) => ({
      isWikilink: a.classList.contains('nf-wikilink'),
      hasResolved: !!a.dataset.wikiResolved,
      href: a.getAttribute('href'),
    }));
  });
  expect(faces.length).toBe(2);
  for (const f of faces) {
    expect(f.isWikilink, '类名进委派选择器 a.nf-wikilink').toBe(true);
    expect(f.hasResolved, '解析结果直填 data-wiki-resolved（onWikilinkClick 同一消费面）').toBe(true);
    expect(f.href, 'href=# 防导航').toBe('#');
  }
});
