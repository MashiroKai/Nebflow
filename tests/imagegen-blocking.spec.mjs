// imagegen-blocking.spec.mjs — 生图交互修正批（2026-10-30 作者令，照 Codex 玩法
// 定案）前端回归网：阻塞语义下「占位 → 单图直出 → 零双渲染」全生命周期断言 +
// 截图存证。
//
// 定案形态：
//   ① 调用期间 = 标准 pending 工具卡原位占位（spinner + 「生图」标签——纯文本
//      风格动效纪律，无 shimmer 占位框特判面）；
//   ② 成功 = toolEnd 的 ___POP_JSON___ 载荷走既有 isPopPayload 通用腿原位直出
//      单张图（Pop 卡片样式：点击 Canvas 打开、扇卡那套保留——本 spec 钉渲染
//      契约，扇卡/Canvas 打开面由 pop-artifacts/pop-image-single-fetch 族兜底）；
//   ③ 零双渲染：旧「占位卡 WS 替换 + 唤醒后再 Pop 一张缩略卡」两渲染腿整腿
//      退役——任何时刻聊天区恰好 ≤1 张图、零 .imagegen-* 残留节点。
//
// 驱动**真实模块**（tests/fixtures/imagegen-blocking/harness.html live-import
// chat.js/popArtifacts.js），按 main.js 的 WS 事件调用形态回放 renderToolPending
// → renderTool；图片字节腿由 page.route 拦截 /api/nf-ticket + /api/nf-file
// 提供（真 PNG、带取数计数——单图恰好取一次字节）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/imagegen-blocking.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/imagegen-blocking/harness.html';
const SHOT_DIR = process.env.NB_KEEP_IG_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261030_imagegen-blocking');

/** 96×60 蓝块 PNG（真编码器产物——截图存证里肉眼可辨的单图直出）。 */
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function makeSolidPng(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  const stride = w * 3 + 1;
  const raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = y * stride + 1 + x * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG = makeSolidPng(96, 60, [30, 100, 220]);
const IMG_PATH = '/tmp/imagegen/img-deadbeef.png';

/** 新后端阻塞语义的成功载荷（GenerateImageTool.popResult 契约形）。 */
const POP_PAYLOAD = '___POP_JSON___' + JSON.stringify({
  fileRefs: { inlined: 0, referenced: 1, failed: 0, omitted: 0 },
  warnings: [],
  notes: [],
  items: [{ kind: 'image', name: 'img-deadbeef.png', path: IMG_PATH, size: PNG.length, ext: 'png' }],
});
const TOOL_LABEL = 'generate_image("a red apple on a wooden table")';
const TOOL_SUMMARY = 'image shown in chat';
const TOOL_INPUT = JSON.stringify({ prompt: 'a red apple on a wooden table' });

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', colorScheme: 'dark', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  const counts = { mint: 0, file: 0 };
  await page.route('**/api/nf-ticket', (route) => {
    counts.mint += 1;
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ tickets: { [IMG_PATH]: { t: 'tk-spec-1', exp: Date.now() + 3600000, size: PNG.length } } }),
    });
  });
  await page.route('**/api/nf-file?**', (route) => {
    counts.file += 1;
    route.fulfill({ contentType: 'image/png', body: PNG });
  });
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors, counts };
}

test.describe('生图阻塞语义（占位 → 单图直出 → 零双渲染）', () => {
  test('T1 占位阶段：原位 pending 卡（生图标签 + spinner），零图 / 零 artifact / 零旧占位面', async ({ browser }) => {
    const { context, page, pageErrors, counts } = await newPage(browser);
    await page.evaluate(() => window.__igUser('给我画一张红苹果'));
    await page.evaluate(l => window.__igCallStart(l), TOOL_LABEL);

    const c = await page.evaluate(() => window.__igCensus());
    expect(c.toolRows).toBe(1);
    expect(c.pendingCards).toBe(1);          // 恰一张原位占位卡
    expect(c.pendingSpinner).toBe(true);     // 生成中状态（纯文本风格 spinner 动效）
    expect(c.imgs).toBe(0);                  // 占位阶段零图
    expect(c.artifactRows).toBe(0);
    expect(c.legacyImagegen).toBe(0);        // 旧 shimmer 占位面零残留
    const labelText = await page.evaluate(() =>
      document.querySelector('#chat .tool-card--pending .label')?.textContent || '');
    expect(labelText).toContain('生图');      // 本地化工具名（占位框文案契约）

    await page.screenshot({ path: path.join(SHOT_DIR, 't1-placeholder.png'), fullPage: false });
    expect(pageErrors).toEqual([]);
    expect(counts.file).toBe(0);             // 占位阶段零字节取数
    await context.close();
  });

  test('T2 完成直出：原位翻转 + 紧随 artifact 行 + 恰一张已装载图（生命周期唯一一次）', async ({ browser }) => {
    const { context, page, pageErrors, counts } = await newPage(browser);
    await page.evaluate(() => window.__igUser('给我画一张红苹果'));
    await page.evaluate(l => window.__igCallStart(l), TOOL_LABEL);
    const pendingRowId = await page.evaluate(() => window.__igProbePendingRowId());
    expect(pendingRowId).toBeTruthy();

    await page.evaluate(([l, s, c, i]) => window.__igDone(l, s, c, i), [TOOL_LABEL, TOOL_SUMMARY, POP_PAYLOAD, TOOL_INPUT]);
    await page.waitForFunction(() => window.__igCensus().popImgsLoaded >= 1, null, { timeout: 10000 });

    const c = await page.evaluate(() => window.__igCensus());
    expect(c.pendingCards).toBe(0);          // 占位翻完成
    expect(c.doneIcons).toBe(1);             // 勾徽标原位翻转
    expect(c.toolRows).toBe(1);
    expect(c.firstToolRowTag).toBe(pendingRowId); // 原位：同一行节点复用（零跳动重建）
    expect(c.artifactRows).toBe(1);          // Pop artifact 行恰一
    expect(c.artifactAfterTool).toBe(true);  // 紧随工具行（时间序原位语义）
    expect(c.imgs).toBe(1);                  // 零双渲染铁断言：全聊天区恰一张图
    expect(c.popImgs).toBe(1);
    expect(c.popImgsLoaded).toBe(1);         // 真字节装载（非破图）
    expect(c.legacyImagegen).toBe(0);

    await page.screenshot({ path: path.join(SHOT_DIR, 't2-single-image.png'), fullPage: false });
    expect(pageErrors).toEqual([]);
    expect(counts.mint).toBe(1);             // 恰一次铸票
    expect(counts.file).toBe(1);             // 恰一次字节取数（单图单取，双渲染必 ≥2）
    await context.close();
  });

  test('T3 imagegenUpdate 帧整腿退役：源面零引用 + 占位样式面零残留', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 静态源面断言：帧类型与占位模块的注册点必须已从生产源移除。
    const [chatSrc, mainSrc, cssSrc] = await Promise.all([
      (await fetch(`http://127.0.0.1:${port}/src/main/resources/web/js/chat.js`)).text(),
      (await fetch(`http://127.0.0.1:${port}/src/main/resources/web/js/main.js`)).text(),
      (await fetch(`http://127.0.0.1:${port}/src/main/resources/web/css/chat.css`)).text(),
    ]);
    expect(chatSrc).not.toContain('imagegenUpdate');
    expect(chatSrc).not.toContain("from './imagegen.js'");
    expect(mainSrc).not.toContain('imagegenUpdate');
    expect(cssSrc).not.toContain('.imagegen-'); // 占位卡样式块整面退役
    // 旧占位模块文件必须已删除（served 404 = 退役面）。
    const legacy = await fetch(`http://127.0.0.1:${port}/src/main/resources/web/js/imagegen.js`);
    expect(legacy.status).toBe(404);
    // 分发安全网（ws.js 现行为）：未注册类型的帧进空 handler 表 no-op——
    // 灰度期老后端推来的僵尸 imagegenUpdate 帧既不渲染也不崩。
    const wsSrc = await (await fetch(`http://127.0.0.1:${port}/src/main/resources/web/js/ws.js`)).text();
    expect(wsSrc).toContain('const list = handlers[msg.type];');
    expect(wsSrc).toMatch(/if \(list\) for/);
    // DOM 面：退役帧形的任何渲染残留都不存在。
    await page.evaluate(() => window.__igUser('帧退役探针'));
    const c = await page.evaluate(() => window.__igCensus());
    expect(c.legacyImagegen).toBe(0);
    expect(c.imgs).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('T4 失败面：原位翻错误卡、零图零 artifact', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__igUser('给我画一张图'));
    await page.evaluate(l => window.__igCallStart(l), TOOL_LABEL);
    await page.evaluate(([l, s, i]) => window.__igError(l, s, 'generate_image failed: provider boom (IMAGEGEN_JOB)', i),
      [TOOL_LABEL, 'generate_image failed', TOOL_INPUT]);

    const c = await page.evaluate(() => window.__igCensus());
    expect(c.pendingCards).toBe(0);
    expect(c.imgs).toBe(0);
    expect(c.artifactRows).toBe(0);
    expect(c.legacyImagegen).toBe(0);
    const errIcon = await page.evaluate(() => !!document.querySelector('#chat .tool-card .icon.err'));
    expect(errIcon).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
