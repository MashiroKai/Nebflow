// user-attach-canvas-open.spec.mjs — 用户附件「点击 → Canvas 打开」统一批
// (2026-10-08) 的回归网。
//
// 缺陷（本批修的）：助手 Pop 的文件/图片/视频点击在 Canvas 打开，但【用户自己
// 发送】的附件不可点开。根因（file:line 证据链，报告全文另附）：
//   · live 渲染腿 chat.js renderUserBubble → buildUserMediaRow(mediaAtts) 的
//     atts 是**线上形状**（input.js addFileAttachment：只有 data/preview，
//     无 path）—— 后端此刻还没把 uploads 落盘；
//   · popArtifacts 的开 Canvas 单点全部以 `item.path` 为闸
//     （openMediaItemClick / buildFileCard 的 `if (item.path)`）；
//   · ⇒ 媒体脸点击只能落到 pathless 灯箱、文件卡点击是死的。
//     （历史恢复腿 persistence.buildUserAttachmentRows 自带 path，本来就通——
//     这正是「刷新后又能点了」的结构性解释。）
// 修法：后端 ui.json 落盘后回传 `attachmentsSaved`（[{name,type,path}]），前端
// 用**同一套 builders** 原位重渲该行 —— 打开动作仍是 openPopArtifact 单点，
// 补丁后的行与 Pop 行、与历史恢复行同构。
//
// 本 spec 驱动 tests/fixtures/user-attach-canvas-open/harness.html（真实模块，
// 无 stub），探针读渲染后的 DOM + workspace-open-item 事件：
//   A1  补丁前（改前形态基线）：文件卡点击 = 死点击；媒体脸点击 = 灯箱（非 Canvas）
//   A2  attachmentsSaved 原位重渲：行被替换、角标按钮出现
//   A3  补丁后：媒体脸点击 → workspace-open-item absPath = 落盘路径（= Pop 整面
//       点击开 Canvas 的三修 ② 形态）
//   A4  补丁后：文件卡点击 → workspace-open-item absPath（= Pop 文件卡同单点）
//   A5  name 不匹配的帧被拒（错位防御）——行保持改前形态
//   A6  path=null（后端保存失败）的项保持 pathless 降级，绝不派假 open
//   A7  两图 stack：补丁后 front 卡点击也开 Canvas
//
// B 组（2026-10-28 断线修复批新增）：A 组从未钉「attachmentsSaved 帧 → chat.js
// 订阅 → 补丁」这一跳——2026-10-10 合并丢掉订阅时全组照绿，live 行停在 pathless
// （作者报障「我自己发的图，还是点不开」根因）。B 组走 harness 的真 ws.js 入站
// 派发腿（FakeWebSocket + connect + 真 onmessage：真会话闸 + 真 handler 表）：
//   B1  真帧派发 → 行补丁 → 媒体点击 = openPopArtifact 单点（订阅在场的证明）
//   B2  他会话帧被会话闸丢弃 ⇒ 不补丁（重入由恢复腿接管的契约面）
//
// Run: node node_modules/@playwright/test/cli.js test tests/user-attach-canvas-open.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/user-attach-canvas-open/harness.html';

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
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
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

/** 标准帧载荷：与后端 attJson 同形（ui.json 记录形状）。 */
const savedFrame = () => ([
  { name: 'shot.png', type: 'image', path: '/tmp/nebflow-coldstart/uploads/sess-1/1502663883343708_shot.jpg' },
  { name: 'notes.pdf', type: 'file', path: '/tmp/nebflow-coldstart/uploads/sess-1/1502663883343709_notes.pdf' },
]);

/* ═══════════ A1 — 改前形态基线：live 行（无 path）点击不开 Canvas ═══════════ */

test.describe('A1 — 补丁前基线：live 线上形状（无 path）的降级形态', () => {
  test('文件卡是死点击；媒体脸点击走灯箱而非 Canvas', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userLiveNoPath());

    const shape = await page.evaluate(() => window.__rowShape());
    expect(shape.rows, '一个独立 user-media 行').toBe(1);
    expect(shape.cornerBtns, '无 path ⇒ 无角标 Canvas 按钮（canvasCornerButton 的 path 闸）').toBe(0);

    const openEvents = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickFileCard()));
    expect(openEvents, '文件卡点击 ⇒ 零 workspace-open-item（死点击 = 报告的缺陷本体）').toEqual([]);

    const mediaClick = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickMediaFace()));
    expect(mediaClick, 'pathless 媒体点击不开 Canvas（#491 主题三 pathless 灯箱形态）').toEqual([]);
    const lb = await page.evaluate(() => window.__clickMediaFace());
    expect(lb.lightbox, '媒体脸点击弹的是消息流内灯箱').toBe(true);
    await page.evaluate(() => window.__closeLightbox());

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ A2/A3/A4 — 补丁后：同一套 builders 原位重渲，点击 = Canvas 单点 ═══════════ */

test.describe('A2-A4 — attachmentsSaved 原位重渲后与 Pop 同构', () => {
  test('行被替换、角标按钮出现、媒体/文件点击都派 workspace-open-item', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userLiveNoPath());
    await page.evaluate((saved) => window.__patchSaved(saved), savedFrame());

    const shape = await page.evaluate(() => window.__rowShape());
    expect(shape.rows, '仍是一个独立行（原位替换，不是新行）').toBe(1);
    expect(shape.connected, '行仍在文档里').toEqual([true]);
    expect(shape.cornerBtns, 'path 已知 ⇒ 角标 Canvas 按钮出现（= Pop 媒体卡同款）').toBe(1);
    expect(shape.fileCards, '文件卡仍在').toBe(1);

    // 媒体脸整面点击 = openPopArtifact 单点（三修 ② 形态）
    const mediaOpen = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickMediaFace()));
    expect(mediaOpen, '媒体整面点击派一次 open').toHaveLength(1);
    expect(mediaOpen[0].absPath, 'absPath = 后端落盘路径').toBe(savedFrame()[0].path);
    expect(mediaOpen[0].id, 'id 形状 = Pop 同款 file:<absPath>').toBe('file:' + savedFrame()[0].path);

    // 角标按钮 = 显式次级入口，同一条单点
    const cornerOpen = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickCornerBtn()));
    expect(cornerOpen, '角标按钮点击派一次 open').toHaveLength(1);
    expect(cornerOpen[0].absPath).toBe(savedFrame()[0].path);

    // 文件卡点击 = Pop 文件卡同一条 openPopArtifact
    const fileOpen = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickFileCard()));
    expect(fileOpen, '文件卡点击派一次 open').toHaveLength(1);
    expect(fileOpen[0].absPath).toBe(savedFrame()[1].path);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ A5 — 错位防御：name 不匹配的帧不动行 ═══════════ */

test.describe('A5 — name 不匹配的帧被拒', () => {
  test('错位帧后行保持改前形态（无角标、点击不开 Canvas）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userLiveNoPath());
    const mismatched = [
      { name: '别的.png', type: 'image', path: '/tmp/other.png' },
      { name: '别的.pdf', type: 'file', path: '/tmp/other.pdf' },
    ];
    await page.evaluate((saved) => window.__patchSaved(saved), mismatched);

    const shape = await page.evaluate(() => window.__rowShape());
    expect(shape.cornerBtns, '错位帧 ⇒ 不补丁 ⇒ 仍无角标按钮').toBe(0);
    const openEvents = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickFileCard()));
    expect(openEvents, '文件卡仍是死点击').toEqual([]);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ A6 — path=null：保存失败的项保持 pathless 降级 ═══════════ */

test.describe('A6 — path=null 的项不派假 open', () => {
  test('null path 不补丁：媒体点击仍是灯箱，不出角标按钮', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userLiveNoPath());
    const nullPathFrame = [
      { name: 'shot.png', type: 'image', path: null },
      { name: 'notes.pdf', type: 'file', path: null },
    ];
    await page.evaluate((saved) => window.__patchSaved(saved), nullPathFrame);

    const shape = await page.evaluate(() => window.__rowShape());
    expect(shape.cornerBtns, '无 path ⇒ 无角标按钮').toBe(0);
    const openEvents = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickFileCard()));
    expect(openEvents, 'pathless 文件卡绝不出假 open').toEqual([]);
    const mediaClick = await page.evaluate(() => window.__clickMediaFace());
    expect(mediaClick.lightbox, '媒体仍走灯箱（pathless 现状保持）').toBe(true);
    await page.evaluate(() => window.__closeLightbox());

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B 组 — 真 ws.js 派发腿：「帧 → chat.js 订阅 → 补丁 → 点击」全链 ═══════════
 *
 * 2026-10-28 作者报障「我自己发的图，还是点不开」的回归钉。根因 = 2026-10-10
 * USTC dev/main 合并在 chat.js 丢了 `onMessage('attachmentsSaved', …)` 订阅
 * （checkpoint a0f2edfc7 可溯），A 组照绿——它直调 patchUserMediaRow，「帧 →
 * 订阅 → 补丁」这一跳从未被钉过。B 组走 harness 的真派发腿
 * （真 ws.js 会话闸 + 真 handler 表 + 真 chat.js 模块级订阅），帧→行→点击
 * 三跳一体；订阅再丢，这里必红。 */
test.describe('B — 真 ws.js 派发：attachmentsSaved 帧驱动 live 行补丁（订阅在场的证明）', () => {
  test('B1: 真帧派发 → 行补丁（角标出现）→ 媒体点击 = workspace-open-item 单点（非灯箱）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const wsReady = await page.evaluate(() => window.__wsReady);
    expect(wsReady, 'harness 的真派发腿必须装配成功（FakeWebSocket + connect + onmessage）').toBe(true);

    await page.evaluate(() => window.__userLiveNoPath());
    expect((await page.evaluate(() => window.__rowShape())).cornerBtns, '改前形态基线：live 行 pathless 无角标').toBe(0);

    // 真帧（与 WebSocketRoutes.scala attJson 同形）经真 onmessage 派发
    const dispatched = await page.evaluate((saved) => window.__dispatchWsFrame({
      type: 'attachmentsSaved',
      sessionId: 'user-attach-harness',
      attachments: saved,
    }), savedFrame());
    expect(dispatched, '喂帧必须走真 onmessage（装配失败应在上面的 __wsReady 断言爆）').toBe(true);
    await page.waitForTimeout(60);

    const shape = await page.evaluate(() => window.__rowShape());
    expect(shape.rows, '原位替换，不另起新行').toBe(1);
    expect(shape.cornerBtns, '帧经订阅到达 patchUserMediaRow ⇒ 角标出现（订阅丢失时此行恒 0 = 报障形态）').toBe(1);

    // 点击 = openPopArtifact 单点（三修 ②：与 Pop 整面点击、与恢复腿三者同构）
    const mediaOpen = await page.evaluate(() => window.__captureOpenEvents(() => window.__clickMediaFace()));
    const openEvents = await mediaOpen;
    expect(openEvents, '补丁后媒体点击派一次 open').toHaveLength(1);
    expect(openEvents[0].absPath, 'absPath = 后端落盘路径').toBe(savedFrame()[0].path);

    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('B2: 他会话的帧被 ws.js 会话闸丢弃 ⇒ 行不补丁（重入由恢复腿接管的契约面）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userLiveNoPath());

    const dispatched = await page.evaluate((saved) => window.__dispatchWsFrame({
      type: 'attachmentsSaved',
      sessionId: 'another-session',
      attachments: saved,
    }), savedFrame());
    expect(dispatched).toBe(true);
    await page.waitForTimeout(60);

    const shape = await page.evaluate(() => window.__rowShape());
    expect(shape.cornerBtns, '他-session 帧不得跨会话补丁（ws.js:1174 会话闸丢弃）').toBe(0);
    const mediaClick = await page.evaluate(() => window.__clickMediaFace());
    expect(mediaClick.lightbox, 'pathless 现状保持：点击仍落灯箱').toBe(true);
    await page.evaluate(() => window.__closeLightbox());

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});


/* ═══════════ A7 — 两图 stack：补丁后 front 卡点击也开 Canvas ═══════════ */
test.describe('A7 — stack 形态的 live 行补丁后点击开 Canvas', () => {
  test('两图 stack 点击 front 卡派 workspace-open-item', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userLiveTwoImages());
    const twoFrame = [
      { name: 'u1.png', type: 'image', path: '/tmp/uploads/sess-1/1_u1.png' },
      { name: 'u2.png', type: 'image', path: '/tmp/uploads/sess-1/2_u2.png' },
    ];
    await page.evaluate((saved) => window.__patchSaved(saved), twoFrame);

    const stackView = await page.evaluate(() => {
      const v = document.querySelector('#chat .row.user-media .pop-stack-view');
      if (!v) return { stack: false };
      v.click();
      return { stack: true };
    });
    expect(stackView.stack, '两图 ⇒ stack 形态').toBe(true);

    const openEvents = await page.evaluate(() => window.__captureOpenEvents(async () => {
      document.querySelector('#chat .row.user-media .pop-stack-view').click();
      await new Promise(r => setTimeout(r, 30));
    }));
    expect(openEvents, 'collapsed stack 点击 = front 卡开 Canvas（三修 ③/A-1 形态）').toHaveLength(1);
    expect(openEvents[0].absPath, 'front 卡 = 第一张图的落盘路径').toBe(twoFrame[0].path);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
