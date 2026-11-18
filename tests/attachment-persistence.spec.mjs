// attachment-persistence.spec.mjs — 「已发送的文件过一会失效」P0 回归 spec
// （attachment-persistence 批，2026-11-18；作者 2026-11-18 17:00 报障）。
//
// 症状：己方消息的图片附件「过一会」（= 刷新/重进，即历史恢复腿）变成虚线空卡 +
// 蓝色问号裂图，footer 一行「无文件来源，无法下载」。live 腿好（内联 data: 预览）。
//
// 本 spec 钉的端到端形状（每一段都是 root cause 链上的一个环节）：
//   ① 发件（真生产入口：/js/input/attachments.js addFileAttachment + /js/input.js
//      send()）后，服务端回的 `attachmentsSaved` 帧里 **path 非空且是 uploads 形状的
//      绝对路径**（修前恒空 ⇒ 详见 scripts/check-attachment-persistence.mjs 头注）。
//   ② 该 path 指向的文件**真在盘上**，且 GET `<base>/uploads/<sid>/<file>` = **200**
//      （授权键 = owner 台账的 `resource:attachment:<sid>/<file>`；修前 403）。
//   ③ 刷新（= 作者看到的「过一会」）后媒体行仍活着：img src 走 `/uploads/` 且
//      **真加载到字节**（naturalWidth > 0）、无 `nf-img-failed` 失败标记。
//   ④ footer 下载键 **非 disabled**（「无文件来源，无法下载」的反面），且点它真能
//      落一份以原件名命名的文件（Playwright download 事件）。
//
// 需真实例（instance-backed，见 playwright.config.mjs 的同名名单）：
//   node tests/harness/isolated-browser.mjs up --tag attach
//   eval "$(node tests/harness/isolated-browser.mjs env --tag attach)"
//   BASE_URL=$BASE_URL NEBFLOW_TOKEN=$TOKEN NEBFLOW_HOME=$NEBFLOW_HOME_DIR npx playwright test tests/attachment-persistence.spec.mjs
//
// 禁跑生产实例（6138/8080/8097）；本 spec 只读它自己的隔离 home。

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { establishSession } from './helpers/session-rail.mjs';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8094';
const NEBFLOW_HOME = process.env.NEBFLOW_HOME ?? process.env.NEBFLOW_HOME_DIR ?? '';
/** 隔离 harness 的 `env` 腿导出 TOKEN / NEBFLOW_TOKEN；两者都认。 */
function defaultToken() {
  const envTok = process.env.NEBFLOW_TOKEN ?? process.env.TOKEN;
  if (envTok) return envTok;
  if (!NEBFLOW_HOME) return '';
  try { return JSON.parse(readFileSync(join(NEBFLOW_HOME, 'auth.json'), 'utf8')); } catch { return ''; }
}
const TOKEN = defaultToken();

const IMG_NAME = 'IMG_1964.jpeg';

/** 铸票 + 落地（与 askuser-attach.spec.mjs 同一条 rail）。 */
async function enter(page) {
  await page.addInitScript(() => { try { localStorage.setItem('nebflow_locale', 'zh-CN'); } catch { /* ignore */ } });
  await establishSession(BASE_URL, TOKEN, page);
  await page.waitForFunction(() => {
    const c = document.getElementById('chat');
    return c && c.getBoundingClientRect().width > 100;
  }, null, { timeout: 20000 });
  // activeView 就绪 = 会话面可发件：既要有 sessionId，也要有 dom.input，且 WS 已
  // open（实例刚起时 activeView 会有一次短暂重建，只等 sessionId 会踩空 dom）。
  await page.waitForFunction(async () => {
    const cv = await import('/js/chatView.js');
    const st = (await import('/js/state.js')).default;
    const v = cv.activeView;
    return !!(v && v.sessionId && v.dom && v.dom.input && st && st.ws && st.ws.readyState === 1);
  }, null, { timeout: 30000 });
  await page.waitForTimeout(800);
}

test('user attachment persists: survives reload with live bytes and an enabled download', async ({ page }) => {
  expect(TOKEN, 'TOKEN/NEBFLOW_TOKEN must be set for this instance-backed spec').toBeTruthy();

  const frames = [];
  page.on('websocket', (ws) => {
    ws.on('framereceived', (f) => {
      const p = String(f.payload || '');
      if (p.includes('attachmentsSaved')) frames.push(p);
    });
  });

  await enter(page);

  // ── ① 真生产入口发件 ───────────────────────────────────────────────────────
  const sid = await page.evaluate(async (name) => {
    const c = document.createElement('canvas');
    c.width = 400; c.height = 300;
    const g = c.getContext('2d');
    g.fillStyle = '#2e7d32'; g.fillRect(0, 0, 400, 300);
    g.fillStyle = '#fff'; g.font = '32px sans-serif'; g.fillText('IMG_1964', 40, 160);
    const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.9));
    const file = new File([blob], name, { type: 'image/jpeg' });
    const att = await import('/js/input/attachments.js');
    const inputMod = await import('/js/input.js');
    const cv = await import('/js/chatView.js');
    const view = cv.activeView;
    if (!view || !view.dom || !view.dom.input) throw new Error('activeView/dom.input not ready');
    await att.addFileAttachment(file);
    view.dom.input.value = 'attachment persistence e2e';
    inputMod.send();
    return view.sessionId;
  }, IMG_NAME);

  await expect.poll(() => frames.length, { timeout: 20000 }).toBeGreaterThan(0);

  const saved = JSON.parse(frames[frames.length - 1]);
  const att = (saved.attachments || [])[0];
  expect(att, 'attachmentsSaved must carry the attachment').toBeTruthy();
  expect(att.name).toBe(IMG_NAME);
  const absPath = String(att.path || '');
  // 核心断言 ①：path 非空、不是内联 data:、是 uploads 族绝对路径。
  expect(absPath, 'persisted attachment path must not be empty (修前恒空)').not.toBe('');
  expect(absPath).not.toMatch(/^data:/);
  expect(absPath).toContain('/uploads/');

  const rel = absPath.slice(absPath.indexOf('/uploads/') + '/uploads/'.length); // <sid>/<file>
  const served = `/uploads/${rel.split('/').map(encodeURIComponent).join('/')}`;

  // ── ② 服务端授权读：同一 URL 必须 200（授权键 resource:attachment:<sid>/<file>）──
  const head = await page.evaluate(async (u) => {
    const r = await fetch(u, { method: 'GET', credentials: 'same-origin' });
    return { status: r.status, bytes: (await r.arrayBuffer()).byteLength };
  }, served);
  expect(head.status, `${served} must be authorized (修前 403)`).toBe(200);
  expect(head.bytes).toBeGreaterThan(1000);

  // ── ③ 刷新（= 作者口中的「过一会」）：媒体行仍能取到字节 ─────────────────────
  await page.reload({ waitUntil: 'load' });
  const img = page.locator('.row.pop-artifact.user-media img.pop-img').last();
  await expect(img).toBeVisible({ timeout: 20000 });
  const src = await img.getAttribute('src');
  expect(String(src), 'restored media row must resolve through the uploads route').toContain('/uploads/');
  await expect
    .poll(async () => img.evaluate((el) => ({ w: el.naturalWidth, failed: el.dataset.nfMediaFailed || '', cls: el.className })), { timeout: 20000 })
    .toEqual({ w: expect.any(Number), failed: '', cls: expect.stringContaining('pop-img') });
  expect(await img.evaluate((el) => el.naturalWidth), 'bytes must really load (裂图 = naturalWidth 0)').toBeGreaterThan(0);
  await expect(img).not.toHaveClass(/nf-img-failed/);

  // ── ④ footer 下载键：非 disabled + 真下载 ──────────────────────────────────
  const dl = page.locator('.row.pop-artifact.user-media .msg-dl').last();
  await expect(dl).toBeVisible();
  await expect(dl, '「无文件来源，无法下载」的反面：下载键必须可用').toBeEnabled();
  await expect(page.locator('.row.pop-artifact.user-media .pop-download-reason')).toHaveCount(0);

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 20000 }),
    dl.click(),
  ]);
  expect(download.suggestedFilename()).toContain('IMG_1964');

  expect(sid, 'session id is the uploads scope segment').toBeTruthy();
  expect(rel.startsWith(`${sid}/`)).toBe(true);
});
