// askuser-attach.spec.mjs — askuser-dragdrop 实施批（2026-10-19，批 #551）E2E：
// AskUser 卡片作用域**答复附件**面（拖入即传 / 粘贴 / 点选）+ 上传端点 + 375px 适配。
//
// Coverage split（与 AskUserAttachmentRoutesSpec.scala 分层，禁重复）：
//  - THIS spec：前端三态（活卡挂面 / 不支持卡静默隐藏 / 已答徽标）+ 多文件真支持
//    （作者修正③）+ 端点集成腿（真实例：鉴权 rail / requestId 闸 / 未知卡 410）
//    + 375×812 无横溢（作者修正④的移动面）+ 桌面/375 截图（证据留存）。
//  - Scala spec（AskUserAttachmentRoutesSpec）：纯闸 / 文件名清洗 / 台账推进 /
//    孤儿清扫（真盘）。
//
// Run against an ISOLATED instance (never the 8080 live one):
//   sbt "run --home /tmp/nb-askuser-smoke --port 8094 --no-browser"
//   BASE_URL=http://localhost:8094 NEBFLOW_TOKEN=<auth.json> npx playwright test tests/askuser-attach.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { establishSession } from './helpers/session-rail.mjs';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8094';
const NEBFLOW_HOME = process.env.NEBFLOW_HOME ?? '/tmp/nb-askuser-smoke';
const EVIDENCE = process.env.NEBFLOW_EVIDENCE_DIR ?? '/tmp/askuser-attach-evidence';

function defaultToken() {
  try {
    return JSON.parse(readFileSync(join(NEBFLOW_HOME, 'auth.json'), 'utf8'));
  } catch {
    return '';
  }
}
const TOKEN = process.env.NEBFLOW_TOKEN ?? defaultToken();

const SID = 'e2e-askuser-attach-session';
const RID = 'asknb-e2eattach01';

// 会话 cookie 复用（本 spec 16 腿 > console-ticket 铸票预算 10/60s 窗——
// ConsoleTicketStore.MintBudgetPerWindow）：首腿走完整 ticket rail 一次，
// 捕获整罐 cookie，后续腿直接注入 + goto。配 --workers=1 = 每进程至多一铸。
let sharedCookies = null;
async function establishShared(page) {
  // 文案断言面统一 en（隔离实例 locale 随 nebflow.json/系统走 zh-CN——
  // social-panel 先例同款 addInitScript 预置）。
  await page.addInitScript(() => { try { localStorage.setItem('nebflow_locale', 'en'); } catch { /* ignore */ } });
  if (sharedCookies && sharedCookies.length) {
    await page.context().addCookies(sharedCookies);
    await page.goto(BASE_URL + '/', { waitUntil: 'load' });
    return;
  }
  await establishSession(BASE_URL, TOKEN, page); // 唯一铸票点（模块级一次）
  sharedCookies = await page.context().cookies(BASE_URL);
}

/** 与 askuser.spec.mjs 同骨架的活卡装配；本批多传第 4 参 requestId（活卡挂面前提）。 */
async function setupCard(page, items, requestId) {
  await establishShared(page);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(600);

  const overlay = page.locator('.onboarding-overlay');
  if (await overlay.isVisible().catch(() => false)) {
    const dismiss = overlay.locator('#ob-skip, #ob-no');
    if (await dismiss.count()) {
      await dismiss.first().click();
      await overlay.waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
    } else {
      throw new Error('Onboarding overlay present but no dismiss button found');
    }
  }

  await page.evaluate(() => localStorage.removeItem('nebflow_askuser_drafts'));

  await page.evaluate(async () => {
    const { isCanvasOpen, closeCanvas } = await import('/js/canvas.js');
    if (isCanvasOpen()) closeCanvas();
    const { sendWs } = await import('/js/ws.js');
    sendWs({ type: 'createSession', name: 'e2e-askuser-attach', agentName: 'Nebula' });
  });
  await page.waitForFunction(() => {
    const c = document.getElementById('chat');
    return c && c.getBoundingClientRect().width > 100;
  }, null, { timeout: 10000 });

  await page.evaluate(async ({ items, requestId }) => {
    const { renderAskUser } = await import('/js/chat.js');
    const { chatViews, setActiveView } = await import('/js/chatView.js');
    const state = (await import('/js/state.js')).default;
    setActiveView(chatViews.primary);
    window.__captured = [];
    state.ws = { readyState: 1, send: (data) => window.__captured.push(JSON.parse(data)) };
    renderAskUser(items, 'e2e-askuser-attach-session', 'E2E', requestId);
  }, { items, requestId });
}

function lastCard(page) {
  return page.locator('.option-box').last();
}

/** 上传端点桩：逐请求递增 seq，返回已落盘引用（与真端点响应同形状）。
  * mime 按扩展名回（真端点以 part Content-Type 为准；XHR FormData 恒带它）。
  * delayMs > 0 时压住响应，让「上传中」瞬态可被断言（否则 fulfill 先于断言到达）。
  * ⚠️ 并发两件 XHR 的 seq 竞争——断言按 name 匹配，禁按序。 */
const MIME_BY_EXT = { png: 'image/png', jpg: 'image/jpeg', txt: 'text/plain', pdf: 'application/pdf' };
function stubUploadOk(page, delayMs = 0) {
  let seq = 0;
  return page.route('**/api/askuser/attachments*', async (route) => {
    seq += 1;
    if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
    const url = new URL(route.request().url());
    const name = (route.request().postData() || '').match(/filename="([^"]+)"/)?.[1] || 'file';
    const ext = name.split('.').pop().toLowerCase();
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ok: true, id: `att-${url.searchParams.get('requestId')}-${seq}`, seq,
        name, path: `/uploads/e2e/ask-${url.searchParams.get('requestId')}/${seq}-${name}`,
        sha256: `sha-${seq}`, size: 10, mime: MIME_BY_EXT[ext] || 'application/octet-stream', deduplicated: false,
      }),
    });
  });
}

// ============================================================
// §1 端点集成腿（真实例，无浏览器）
// ============================================================

test.describe('POST /api/askuser/attachments — endpoint rail', () => {
  test('unauthenticated → 403 Unauthorized (same rail as the /api domain)', async ({ request }) => {
    const res = await request.post(`${BASE_URL}/api/askuser/attachments?requestId=${RID}`, {
      multipart: { file: { name: 'a.txt', mimeType: 'text/plain', buffer: Buffer.from('x') } },
    });
    expect(res.status()).toBe(403);
    const body = await res.json();
    expect(body.error).toBe('Unauthorized');
  });

  test('authenticated + missing requestId → 400 ASKUSER_BAD_REQUEST', async ({ request }) => {
    const mint = await fetch(`${BASE_URL}/api/auth/console-ticket`, {
      method: 'POST', headers: { Authorization: `Bearer ${TOKEN}` },
    });
    expect(mint.ok).toBeTruthy();
    const res = await request.post(`${BASE_URL}/api/askuser/attachments`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
      multipart: { file: { name: 'a.txt', mimeType: 'text/plain', buffer: Buffer.from('x') } },
    });
    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.ok).toBe(false);
    expect(body.error).toBe('ASKUSER_BAD_REQUEST');
  });

  test('authenticated + unknown requestId → 410 ASKUSER_CARD_NOT_LIVE (fail-closed hub gate)', async ({ request }) => {
    const res = await request.post(`${BASE_URL}/api/askuser/attachments?requestId=asknb-nonexistent`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
      multipart: { file: { name: 'a.txt', mimeType: 'text/plain', buffer: Buffer.from('x') } },
    });
    expect(res.status()).toBe(410);
    const body = await res.json();
    expect(body.error).toBe('ASKUSER_CARD_NOT_LIVE');
  });
});

// ============================================================
// §2 入口三态（作者修正①②）
// ============================================================

test.describe('Entry gating — open questions only, silent elsewhere', () => {
  test('open-question live card: attach zone + pick button + multiple input', async ({ page }) => {
    await setupCard(page, [{ question: 'Describe the incident (files welcome).' }], RID);
    const box = lastCard(page);
    await expect(box.locator('.ask-answer-attach-zone')).toBeVisible();
    await expect(box.locator('.ask-answer-attach-empty')).toBeVisible();
    await expect(box.locator('input[type=file]')).toHaveAttribute('multiple', '');
  });

  test('choice-only card: NO entry at all (silent — 修正①)', async ({ page }) => {
    await setupCard(page, [{
      question: 'Pick one',
      options: [{ label: 'A' }, { label: 'B' }],
    }], RID);
    const box = lastCard(page);
    await expect(box.locator('.ask-answer-attach-zone')).toHaveCount(0);
    await expect(box.locator('input[type=file]')).toHaveCount(0);
  });

  test('mixed card (one open question): entry mounts (至少一道开放题)', async ({ page }) => {
    await setupCard(page, [
      { question: 'Pick one', options: [{ label: 'A' }, { label: 'B' }] },
      { question: 'Anything to add?' },
    ], RID);
    await expect(lastCard(page).locator('.ask-answer-attach-zone')).toBeVisible();
  });

  test('secret card: NO entry (fail-closed — 修正②)', async ({ page }) => {
    await setupCard(page, [{ question: 'Token?', secret: true }], RID);
    await expect(lastCard(page).locator('.ask-answer-attach-zone')).toHaveCount(0);
  });

  test('dirPicker card: NO entry (选择面写入的卡 — 修正②)', async ({ page }) => {
    await setupCard(page, [{ question: 'Choose a directory', dirPicker: true }], RID);
    await expect(lastCard(page).locator('.ask-answer-attach-zone')).toHaveCount(0);
  });

  test('history leg (no requestId): NO live entry — badge only via renderAskUserHistory', async ({ page }) => {
    await setupCard(page, [{ question: 'Open question' }], undefined);
    await expect(lastCard(page).locator('.ask-answer-attach-zone')).toHaveCount(0);
  });
});

// ============================================================
// §3 多文件真支持 + 发送闸（作者修正③ / 设计稿 §2.5-2.7）
// ============================================================

test.describe('Multi-file + send gate', () => {
  test('click-pick 2 files → 2 chips upload → done; zero-option card confirm unlocks; frame carries manifest', async ({ page }) => {
    await stubUploadOk(page, 700); // 压 700ms：上传中瞬态可断言
    await setupCard(page, [{ question: 'Describe the incident (files welcome).' }], RID);
    const box = lastCard(page);
    const confirm = box.locator('.option-confirm');

    // 零选项卡：未附文件时发送闸保持（基础闸不过——开放题无文本作答）。
    await expect(confirm).toBeDisabled();

    await box.locator('input[type=file]').setInputFiles([
      { name: 'a.png', mimeType: 'image/png', buffer: Buffer.from('png-a') },
      { name: 'b.txt', mimeType: 'text/plain', buffer: Buffer.from('txt-b') },
    ]);
    await expect(box.locator('.ask-answer-attach-chip')).toHaveCount(2);
    await expect(box.locator('.ask-answer-attach-chip').first().locator('.ask-answer-attach-name')).toHaveText('a.png');
    await expect(confirm).toBeDisabled(); // 上传中 ⇒ 置灰 + 文案「上传中…」
    await expect(confirm).toContainText('Uploading');

    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'b.txt' }).locator('text=Attached')).toBeVisible();
    await expect(confirm).toBeEnabled(); // ≥1 done 件满足零选项卡发送闸
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'a.png' }).locator('text=Attached')).toBeVisible();

    // 图片 chip 有 object-url 缩略（img 元素），文件 chip 是 SVG 图标。
    await expect(box.locator('.ask-answer-attach-chip').first().locator('img')).toHaveCount(1);

    await confirm.click();
    const payloads = await page.evaluate(() => window.__captured);
    const p = payloads.find((m) => m.type === 'askUserAnswer');
    expect(p).toBeTruthy();
    expect(p.requestId).toBe(RID);
    expect(p.attachments).toHaveLength(2);
    const byName = Object.fromEntries(p.attachments.map((a) => [a.name, a])); // seq 竞争：按名匹配
    expect(byName['a.png']).toMatchObject({ kind: 'image', path: expect.stringContaining('a.png') });
    expect(byName['b.txt']).toMatchObject({ kind: 'file' });

    // 发送后锁卡：× 移除钮全数消失，空态按钮隐藏（只读降级）。
    await expect(box.locator('.ask-answer-attach-rm')).toHaveCount(0);
    await expect(box.locator('.ask-answer-attach-empty')).toBeHidden();
  });

  test('drag-drop 2 files at once (多文件拖入 — 修正③)', async ({ page }) => {
    await stubUploadOk(page);
    await setupCard(page, [{ question: 'Drop anything here.' }], RID);
    const box = lastCard(page);

    await page.evaluate(() => {
      const box = document.querySelectorAll('.option-box').item(document.querySelectorAll('.option-box').length - 1);
      const dt = new DataTransfer();
      dt.items.add(new File(['drag-1'], 'drag-1.txt', { type: 'text/plain' }));
      dt.items.add(new File(['drag-2'], 'drag-2.txt', { type: 'text/plain' }));
      box.dispatchEvent(new DragEvent('dragenter', { dataTransfer: dt, bubbles: true, cancelable: true }));
      box.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    });
    await expect(box.locator('.ask-answer-attach-chip')).toHaveCount(2);
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'drag-1.txt' })).toBeVisible();
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'drag-2.txt' })).toBeVisible();
  });

  test('paste 2 files onto focused card → 2 chips upload → done (paste leg — W1-W3 回归：enqueueFiles 作用域错位曾 ReferenceError)', async ({ page }) => {
    await stubUploadOk(page);
    await setupCard(page, [{ question: 'Paste anything here.' }], RID);
    const box = lastCard(page);

    // 粘贴目标 = 含 activeElement 的活卡（headless 无 hover ⇒ 走焦点腿）。
    await page.evaluate(() => {
      const boxes = document.querySelectorAll('.option-box');
      boxes.item(boxes.length - 1).querySelector('.ask-answer-attach-empty').focus();
    });
    await page.evaluate(() => {
      const dt = new DataTransfer();
      dt.items.add(new File(['paste-1'], 'paste-1.txt', { type: 'text/plain' }));
      dt.items.add(new File(['paste-2'], 'paste-2.txt', { type: 'text/plain' }));
      document.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
    });
    await expect(box.locator('.ask-answer-attach-chip')).toHaveCount(2);
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'paste-1.txt' })).toBeVisible();
    // done 断言钉全链：paste → enqueueFiles（模块级）→ addChip → upload → 完成。
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'paste-2.txt' }).locator('text=Attached')).toBeVisible();
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'paste-1.txt' }).locator('text=Attached')).toBeVisible();
  });

  test('failed upload → red chip + retry + confirm blocked with toast (§2.7)', async ({ page }) => {
    await setupCard(page, [{ question: 'Describe the incident.' }], RID);
    const box = lastCard(page);
    await page.route('**/api/askuser/attachments*', (route) =>
      route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ ok: false, error: 'ASKUSER_ATTACH_SECRET_CARD' }) }));
    await box.locator('input[type=file]').setInputFiles([
      { name: 'bad.bin', mimeType: 'application/octet-stream', buffer: Buffer.from('x') },
    ]);
    const chip = box.locator('.ask-answer-attach-chip').first();
    await expect(chip.locator('text=Failed')).toBeVisible();
    await expect(chip.locator('button', { hasText: 'Retry' })).toBeVisible();

    // 失败 chip（无 done 件）⇒ 发送闸不开：钮禁用 + 零作答帧（禁点禁用钮——
    // Playwright 点击禁用件会等满超时，闸语义用 disabled 断言读）。
    const captured = await page.evaluate(() => window.__captured);
    expect(captured.filter((m) => m.type === 'askUserAnswer').length).toBe(0);
    await expect(lastCard(page).locator('.option-confirm')).toBeDisabled();

    // 重试腿：路由改回成功 → Retry 点击 → chip 转 done ⇒ 闸开。
    await stubUploadOk(page);
    await chip.locator('button', { hasText: 'Retry' }).click();
    await expect(chip.locator('text=Attached')).toBeVisible();
    await expect(lastCard(page).locator('.option-confirm')).toBeEnabled();
  });

  test('choice card stays option-gated: attachments never substitute options (修正①)', async ({ page }) => {
    await stubUploadOk(page);
    await setupCard(page, [
      { question: 'Pick one', options: [{ label: 'A' }, { label: 'B' }] },
      { question: 'Anything to add?' },
    ], RID);
    const box = lastCard(page);
    const confirm = box.locator('.option-confirm');
    await expect(confirm).toBeDisabled();
    // 混合卡也挂面（≥1 开放题），附文件满足开放题腿但不满足选项腿。
    await box.locator('input[type=file]').setInputFiles([
      { name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('n') },
    ]);
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'note.txt' }).locator('text=Attached')).toBeVisible();
    await expect(confirm).toBeDisabled(); // 选项未答（附件不满足含选项卡的闸）
    await box.locator('.option-btn', { hasText: 'A' }).first().click();
    await expect(confirm).toBeDisabled(); // 选项已答但开放题文本未答
    await box.locator('.option-custom-input').last().fill('extra note');
    await expect(confirm).toBeEnabled(); // 全题作答 ⇒ 开闸
  });
});

// ============================================================
// §4 已答徽标（历史卡「已回答 · 附 N 个文件」）
// ============================================================

test.describe('Answered badge on history cards', () => {
  test('renderAskUserHistory with answerAttachments → badge + read-only row; without → byte-identical legacy', async ({ page }) => {
    await establishShared(page);
    await page.waitForLoadState('networkidle');
    await page.evaluate(async () => {
      const { isCanvasOpen, closeCanvas } = await import('/js/canvas.js');
      if (isCanvasOpen()) closeCanvas();
      const { sendWs } = await import('/js/ws.js');
      sendWs({ type: 'createSession', name: 'e2e-askuser-attach-hist', agentName: 'Nebula' });
    });
    await page.waitForFunction(() => {
      const c = document.getElementById('chat');
      return c && c.getBoundingClientRect().width > 100;
    }, null, { timeout: 10000 });

    const atts = [
      { id: 'att-1', seq: 1, name: 'photo.png', path: '/uploads/s/ask-r/1-photo.png', sha256: 's1', size: 10, mime: 'image/png', kind: 'image' },
      { id: 'att-2', seq: 2, name: 'log.txt', path: '/uploads/s/ask-r/2-log.txt', sha256: 's2', size: 20, mime: 'text/plain', kind: 'file' },
    ];
    const badgeText = await page.evaluate(async (atts) => {
      const { renderAskUserHistory } = await import('/js/chat.js');
      const { chatViews, setActiveView } = await import('/js/chatView.js');
      setActiveView(chatViews.primary);
      const bubble = document.createElement('div');
      document.querySelector('#chat .msgs, #chat')?.appendChild(bubble);
      renderAskUserHistory(bubble, [{ question: 'Hist?' }], 'my answer', undefined, undefined, atts);
      const box = bubble.querySelector('.option-box');
      const badge = box.querySelector('.ask-answer-attach-badge');
      return {
        badge: badge ? badge.textContent : null,
        rows: box.querySelectorAll('.ask-attachments, .ask-attach-row, [class*=ask-attach]').length,
        rmButtons: box.querySelectorAll('.ask-answer-attach-rm').length,
        fileButtons: box.querySelectorAll('.ask-answer-attach-empty').length,
      };
    }, atts);
    expect(badgeText.badge).toContain('Answered');
    expect(badgeText.badge).toContain('2');
    expect(badgeText.rmButtons).toBe(0); // 只读：无移除钮
    expect(badgeText.fileButtons).toBe(0); // 只读：无点选入口

    // 无作答附件 ⇒ 徽标不挂（历史卡与改前同形）。
    const noBadge = await page.evaluate(async () => {
      const { renderAskUserHistory } = await import('/js/chat.js');
      const bubble = document.createElement('div');
      document.querySelector('#chat .msgs, #chat')?.appendChild(bubble);
      renderAskUserHistory(bubble, [{ question: 'Hist2?' }], 'answer 2', undefined, undefined, undefined);
      return bubble.querySelectorAll('.ask-answer-attach-badge').length;
    });
    expect(noBadge).toBe(0);
  });
});

// ============================================================
// §5 375×812 移动面（作者修正④）+ 截图证据
// ============================================================
// 无测试间依赖（每 test 独立 page/context）——不用 serial：一条装配抖动不该
// 级联跳过整批（截图腿各自成证）。

test.describe('375px adaptation + evidence shots', () => {
  let browser;

  test('desktop 1280×800: attach face renders, no overflow; screenshot to evidence', async ({ page }) => {
    await stubUploadOk(page);
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupCard(page, [{ question: 'Describe the incident (files welcome).' }], RID);
    const box = lastCard(page);
    await box.locator('input[type=file]').setInputFiles([
      { name: 'incident-report.pdf', mimeType: 'application/pdf', buffer: Buffer.from('pdf') },
      { name: 'screenshot.png', mimeType: 'image/png', buffer: Buffer.from('png') },
    ]);
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'screenshot.png' }).locator('text=Attached')).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.screenshot({ path: join(EVIDENCE, 'ask-attach-desktop-1280.png'), fullPage: false });
  });

  test('375×812: zone + 2 done chips, zero horizontal overflow; screenshot to evidence', async ({ page }) => {
    await stubUploadOk(page);
    await page.setViewportSize({ width: 375, height: 812 });
    await setupCard(page, [{ question: 'Describe the incident (files welcome).' }], RID);
    const box = lastCard(page);
    await box.locator('input[type=file]').setInputFiles([
      { name: 'mobile-photo.png', mimeType: 'image/png', buffer: Buffer.from('png') },
      { name: 'mobile-log.txt', mimeType: 'text/plain', buffer: Buffer.from('log') },
    ]);
    await expect(box.locator('.ask-answer-attach-chip')).toHaveCount(2);
    await expect(box.locator('.ask-answer-attach-chip', { hasText: 'mobile-photo.png' }).locator('text=Attached')).toBeVisible();

    const m = await page.evaluate(() => {
      const doc = document.documentElement;
      const zone = document.querySelector('.ask-answer-attach-zone');
      const zr = zone.getBoundingClientRect();
      return {
        scrollW: doc.scrollWidth,
        clientW: doc.clientWidth,
        zoneW: Math.round(zr.width),
        zoneRight: Math.round(zr.right),
        chipsWrap: getComputedStyle(document.querySelector('.ask-answer-attach-chips')).flexWrap,
      };
    });
    expect(m.scrollW).toBeLessThanOrEqual(m.clientW); // 无横溢
    expect(m.chipsWrap).toBe('wrap'); // chip 换行不挤爆
    expect(m.zoneRight).toBeLessThanOrEqual(375 + 1); // 附件区不探出视口
    await page.waitForTimeout(900); // 卡片淡入（ob-fade-in）+ 挂面动画落定 ⇒ 证据截图不糊
    await page.screenshot({ path: join(EVIDENCE, 'ask-attach-375.png'), fullPage: false });
  });
});
