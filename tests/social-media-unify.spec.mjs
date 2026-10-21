// social-media-unify.spec.mjs — 社交通道入站媒体统一适配（weixin-inbound-media-
// unify batch, 2026-10-20）· 媒体渲染面 spec（质量闸 ①）。
//
// What the author ruling pins (each assertion binary):
//   B1 登记同一 → 显示同一 — bridgeUser 帧的 attachments（[{name,type,path}]，
//        控制台 attJson 同形）透传 renderUserBubble ⇒ 既有 buildUserMediaRow
//        媒体气泡面接管（图=Pop 媒体面 / 文件=文件卡 / 混排同行），与控制台
//        纯图发送同形（空随文 ⇒ 零文本气泡）。
//   B2 字节腿同一 — media 根指针路径（uploads 族之外）走**同一** nf-file 票腿：
//        hydrateMedia 的 resolver 拒答（null）回落票腿（铸票请求可见），uploads
//        族路径仍由 resolver 命中（零票腿请求 = 逐字现状）。
//   B3 存量「已存至」兼容渲染 — mediaLine 两种拼装严格识别（带/不带原始文件名；
//        图片/视频/文件三 kind）：识别命中 ⇒ 媒体行接管 + 文本气泡隐去；不匹配
//        （含截断/缺尾/路径越界）⇒ 原文本行渲染（fail-open，零回归）。
//   B4 旧帧零回归 — 无 attachments 的 bridgeUser/普通文本行逐字现状。
//
// The spec drives the REAL render modules through
// tests/fixtures/pop-artifacts/harness.html (self-spawned static server —
// never the host instance), the same discipline as attach-bubble-unify.
//
// Run: node node_modules/@playwright/test/cli.js test tests/social-media-unify.spec.mjs

import { test, expect } from '@playwright/test';
import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';

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

async function waitUntilUp(url, tries = 60) {
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
  expect(existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

const MEDIA_ROOT = '/Users/wx/media/weixin-inbound/a-im-bot';

/* ═══════════ B1 — 桥帧 attachments ⇒ 控制台媒体气泡面 ═══════════ */

test.describe('B1 — bridgeUser 帧的媒体行（三同一 · 显示同一）', () => {
  test('纯图帧：媒体行接管、零文本气泡（控制台纯图发送同形）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const seq = await page.evaluate((root) => window.__bridgeMedia('', [
      { name: 'm1-pic.png', type: 'image', path: `${root}/m1-pic.png` },
    ]), MEDIA_ROOT);
    expect(seq, '用户行 + 独立媒体行（与控制台 live 相同的行序）').toEqual([
      expect.objectContaining({ kind: 'user' }),
      expect.objectContaining({ kind: 'artifact', media: 1, files: 0 }),
    ]);
    const face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.rowPresent, '媒体行在场').toBe(true);
    expect(face.rowClass, '媒体行 = 控制台 user-media 行（同一 builder）').toContain('user-media');
    expect(face.mediaTag, '图片走 Pop 媒体面 <img>').toBe('img');
    expect(face.mediaName, 'dataset 名 = 指针件名').toBe('m1-pic.png');
    expect(face.textBubbles, '空随文 ⇒ 零文本气泡').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('文件帧：控制台文件卡同款；混排帧：媒体 + 文件同行', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const seq = await page.evaluate((root) => window.__bridgeMedia('', [
      { name: 'm9-report.pdf', type: 'file', path: `${root}/m9-report.pdf` },
    ]), MEDIA_ROOT);
    expect(seq[1], '文件指针 ⇒ 文件卡（控制台 attJson type=file 同判）').toEqual(
      expect.objectContaining({ files: 1, media: 0 })
    );
    let face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.cardName).toBe('m9-report.pdf');

    const mixed = await page.evaluate((root) => window.__bridgeMedia('', [
      { name: 'm1-pic.png', type: 'image', path: `${root}/m1-pic.png` },
      { name: 'm2-clip.mp4', type: 'file', path: `${root}/m2-clip.mp4` },
    ]), MEDIA_ROOT);
    expect(mixed[1], '混排：一张媒体面 + 一张文件卡，同一行').toEqual(
      expect.objectContaining({ media: 1, files: 1 })
    );
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B2 — 字节腿同一：票腿回落（uploads 族零变化）═══════════ */

test.describe('B2 — hydrateMedia 的 resolver 拒答回落同一票腿', () => {
  test('media 根路径（uploads 族外）⇒ 铸票请求可见（不再终态死脸）；uploads 路径仍走 resolver', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((atts) => window.__restoreRows(atts), [
      { name: 'm1-pic.png', type: 'image', path: `${MEDIA_ROOT}/m1-pic.png` },
    ]);
    let reqs = await page.evaluate(() => window.__nfRequests);
    expect(reqs.some(u => u.includes('/api/nf-ticket')), 'resolver 拒答 ⇒ 同一票腿铸票（fallback 可观察）').toBe(true);

    // 清空观察窗，再走 uploads 族路径（resolver 命中 ⇒ 零票腿请求）。
    await page.evaluate(() => { window.__nfRequests = []; });
    await page.evaluate((atts) => window.__restoreRows(atts), [
      { name: 'c1.png', type: 'image', path: '/uploads/pop-harness/c1.png' },
    ]);
    reqs = await page.evaluate(() => window.__nfRequests.filter(u => u.includes('/api/nf-ticket')));
    expect(reqs, 'uploads 族路径仍由 resolver 直接命中（零票腿请求 = 逐字现状）').toEqual([]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B3 — 存量「已存至」行兼容渲染 ═══════════ */

test.describe('B3 — 存量 mediaLine 文本行 ⇒ 媒体行接管', () => {
  const legacyImage = `[图片] 已存至 ${MEDIA_ROOT}/m1-pic.png（原始文件名 pic.png），可用 Read 查看`;
  const legacyVideo = `[视频] 已存至 ${MEDIA_ROOT}/m2-clip.mp4，可用 Read 查看`;
  const legacyFile = `[文件] 已存至 ${MEDIA_ROOT}/m9-report.pdf，可用 Read 查看`;

  test('识别器（单点）三种拼装 + fail-open 形状', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const cases = await page.evaluate((roots) => {
      const [img, vid, file] = [
        `${roots.root}/m1-pic.png`, `${roots.root}/m2-clip.mp4`, `${roots.root}/m9-report.pdf`,
      ];
      return {
        image: window.__legacyAtts(`[图片] 已存至 ${img}（原始文件名 pic.png），可用 Read 查看`),
        imageBare: window.__legacyAtts(`[图片] 已存至 ${img}，可用 Read 查看`),
        video: window.__legacyAtts(vid),
        file: window.__legacyAtts(file),
        videoLine: window.__legacyAtts(`[视频] 已存至 ${vid}，可用 Read 查看`),
        fileLine: window.__legacyAtts(`[文件] 已存至 ${file}，可用 Read 查看`),
        truncated: window.__legacyAtts(`[图片] 已存至 ${img}`),
        missingTail: window.__legacyAtts(`[图片] 已存至 ${img}，可读`),
        otherText: window.__legacyAtts('普通一句话'),
        traversal: window.__legacyAtts('[图片] 已存至 /a/../b.png，可用 Read 查看'),
        nonString: window.__legacyAtts(null),
        attachWins: window.__legacyUnify('正文', [{ type: 'image', name: 'x.png', path: '/p.png' }]),
      };
    }, { root: MEDIA_ROOT });
    expect(cases.image).toEqual([{ type: 'image', name: 'pic.png', path: `${MEDIA_ROOT}/m1-pic.png` }]);
    expect(cases.imageBare, '无原始名 ⇒ 基名回落').toEqual([{ type: 'image', name: 'm1-pic.png', path: `${MEDIA_ROOT}/m1-pic.png` }]);
    expect(cases.videoLine, '视频 kind 原样恢复（识别=恢复，非分类）').toEqual([{ type: 'video', name: 'm2-clip.mp4', path: `${MEDIA_ROOT}/m2-clip.mp4` }]);
    expect(cases.fileLine).toEqual([{ type: 'file', name: 'm9-report.pdf', path: `${MEDIA_ROOT}/m9-report.pdf` }]);
    expect(cases.video, '裸路径不是媒体行（识别严格）').toBe(null);
    expect(cases.file).toBe(null);
    expect(cases.truncated, '缺尾 ⇒ null（fail-open）').toBe(null);
    expect(cases.missingTail).toBe(null);
    expect(cases.otherText).toBe(null);
    expect(cases.traversal, '路径越界 ⇒ null').toBe(null);
    expect(cases.nonString).toBe(null);
    expect(cases.attachWins, '附件在场 ⇒ 识别不参与（新登记行优先）').toEqual({ atts: [{ type: 'image', name: 'x.png', path: '/p.png' }], text: '正文' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('存量图片行 ⇒ 媒体行 + 文本气泡隐去；存量文件行 ⇒ 文件卡', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((t) => window.__bridgeMedia(t, []), legacyImage);
    let face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.mediaTag, '存量图片行接管为 Pop 媒体面').toBe('img');
    expect(face.mediaName).toBe('pic.png');
    expect(face.textBubbles, '媒体行接管 ⇒ 文本气泡隐去').toBe(0);

    await page.evaluate((t) => window.__bridgeMedia(t, []), legacyFile);
    face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.fileCards, '存量文件行 ⇒ 文件卡').toBe(1);
    expect(face.cardName).toBe('m9-report.pdf');

    await page.evaluate((t) => window.__bridgeMedia(t, []), legacyVideo);
    face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.mediaTag, '存量视频行 ⇒ Pop 视频面').toBe('video');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B4 — 零回归：旧帧/普通文本行逐字现状 ═══════════ */

test.describe('B4 — 旧帧零回归', () => {
  test('无附件文本行 = 纯文本气泡（无媒体行）；不匹配的「已存至」残行保持文本', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const seq = await page.evaluate(() => window.__bridgeMedia('hello', []));
    expect(seq, '普通文本行：一个用户行，无媒体行').toEqual([expect.objectContaining({ kind: 'user' })]);
    let face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.rowPresent).toBe(false);
    expect(face.textBubbles, '文本气泡照旧').toBe(1);

    await page.evaluate(() => window.__bridgeMedia('[图片] 已存至 /x.png', []));
    face = await page.evaluate(() => window.__mediaFaceRead());
    expect(face.rowPresent, '不匹配 ⇒ 文本行渲染（fail-open）').toBe(false);
    expect(face.textBubbles).toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
