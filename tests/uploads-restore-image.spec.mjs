// uploads-restore-image.spec.mjs — 「历史重渲染后用户图片 src 可 200 取回」
// (refresh-image fix, 2026-10-09) 的回归网。
//
// 用户报障（2026-10-09 07:15）：桌面对话里用户发的图片，刷新前当次会话显示正常，
// 一刷新就变问号占位。
//
// 根因（file:line 证据链，报告全文另附）：
//   · 刷新后两条恢复腿（localStorage 缓存 sanitizeForCache persistence.js:240-253、
//     后端 ui.json 历史）都只带 {name,type,path} —— inline data: preview 被剥掉；
//   · 恢复渲染经 buildUserAttachmentRows → resolver=attachmentImageUrl
//     （persistence.js:423-433）拼 /uploads/<sid>/<encodeURIComponent(file)>；
//   · 服务端 StaticRoutes.uploadsRoutes（StaticRoutes.scala:189）用
//     `segments.map(_.encoded)` —— http4s 的 wire **编码**形态 —— 直接当文件名查盘，
//     从不解码 ⇒ 非 ASCII / 带空格的文件名（每张 macOS 截图：
//     「截屏2026-10-09 07.10.22.jpg」）一律 404；
//   · 404 ⇒ hydrateMedia 终态 fail（popArtifacts.js:157-181）⇒ nf-img-failed 问号。
//     刷新前 live 行走 inline preview（item.src 赢，popArtifacts.js:158-161），
//     零网络 ⇒ 症状只在刷新后出现。
// 修复（后端单点）：路由先 `Segment.decoded()` 再查盘，解码落在穿越守卫**之前**。
// 本 spec 断言前端恢复链在「解码语义的服务器」上整链可用：src 可 200 取回、
// 像素真解码、缺件时失败面可见（负例证明取字节腿真的发生了）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/uploads-restore-image.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/uploads-restore-image/harness.html';

let port;
const servers = [];
let tmpRoot;

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

/** 静态服务器 = python http.server：与修复后的 uploadsRoutes 同一「路径段解码后
 *  取件」语义（浏览器/attachmentImageUrl 发编码形态，服务器解码后命中真名）。 */
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
  // 独立根：harness 与 uploads 素材同源（resolver 产出的是相对 URL）。
  // 模块树用 symlink 挂进临时根，仓库零落盘。
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nf-uploads-restore-'));
  fs.symlinkSync(path.join(REPO_ROOT, 'src'), path.join(tmpRoot, 'src'), 'dir');
  fs.symlinkSync(path.join(REPO_ROOT, 'tests'), path.join(tmpRoot, 'tests'), 'dir');
  const sessDir = path.join(tmpRoot, 'uploads', 'sess-1');
  fs.mkdirSync(sessDir, { recursive: true });
  // 1×1 PNG（真字节，img 可真解码出 naturalWidth>0）。
  const png1x1 = Buffer.from(
    '89504e470d0a1a0a0000000d494844520000000100000001080600000'
    + '01f15c4890000000d49444154789c626001000000ffff030000060005'
    + '57bfabd40000000049454e44ae426082',
    'hex'
  );
  // 真实报障的文件名形状：非 ASCII + 空格（每张 macOS 截图）。
  fs.writeFileSync(path.join(sessDir, '截屏2026-10-09 07.10.22.png'), png1x1);
  // ASCII 对照（修前的既有可用面）。
  fs.writeFileSync(path.join(sessDir, 'plain.png'), png1x1);
  port = await freePort();
  servers.push(startStaticServer(port, tmpRoot));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  if (tmpRoot) fs.rmSync(tmpRoot, { recursive: true, force: true });
});

test.beforeEach(async ({ page }) => {
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction('window.__ready === true');
});

test('A1: 非 ASCII+空格名的 ui.json 行，恢复后 src 可 200 取回且像素真解码', async ({ page }) => {
  const mounted = await page.evaluate(() => window.__restoreUserImage({
    name: '截屏2026-10-09 07.10.22.png',
    path: '/tmp/nebflow-coldstart/uploads/sess-1/截屏2026-10-09 07.10.22.png',
  }));
  expect(mounted).toBe(true);

  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  // src 指向 uploads 路由、路径解码后命中真名（前端只发编码形态，解码是服务端职责；
  // URL.pathname 保留线上编码形态，这里解码后对表）。
  const u = new URL(states[0].src);
  expect(decodeURIComponent(u.pathname)).toBe('/uploads/sess-1/截屏2026-10-09 07.10.22.png');
  // 不是失败面。
  expect(states[0].failed).toBe(false);
  expect(states[0].failedFlag).toBe(false);
  // 像素真解码（不是骨架/破图）。
  expect(states[0].loaded).toBe(true);
  // 「可 200 取回」的直接读数。
  const fetchState = await page.evaluate(() => window.__fetchImg(0));
  expect(fetchState.status).toBe(200);
  expect(fetchState.bytes).toBeGreaterThan(0);
  // 无失败注记。
  expect(await page.evaluate(() => window.__failedNotes())).toHaveLength(0);
});

test('A2: ASCII 名（修前的既有可用面）恢复后同样 200', async ({ page }) => {
  await page.evaluate(() => window.__restoreUserImage({
    name: 'plain.png',
    path: '/tmp/nebflow-coldstart/uploads/sess-1/plain.png',
  }));
  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  expect(new URL(states[0].src).pathname).toBe('/uploads/sess-1/plain.png');
  expect(states[0].loaded).toBe(true);
  expect(await page.evaluate(() => window.__fetchImg(0))).toMatchObject({ status: 200 });
});

test('A3: 缺件（404）⇒ 失败面可见 —— 负例证明取字节腿真的发生了', async ({ page }) => {
  await page.evaluate(() => window.__restoreUserImage({
    name: 'gone.png',
    path: '/tmp/nebflow-coldstart/uploads/sess-1/gone.png',
  }));
  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  expect(states[0].failed).toBe(true);
  expect(states[0].failedFlag).toBe(true);
  // hydrateMedia 终态契约：失败注记行在屏上（禁静默）。
  const notes = await page.evaluate(() => window.__failedNotes());
  expect(notes).toHaveLength(1);
  expect(notes[0]).toContain('gone.png');
});
