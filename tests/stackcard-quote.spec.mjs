// stackcard-quote.spec.mjs — 叠卡内每张图片可分别引用（作者令 2026-11-02 09:19 ①
// 「让叠卡的图片可以分开引用」）的验收网。
//
// 现场事实（改前）：叠卡整组只有**一个行级**引用描述符（popArtifacts.js
// buildUserMediaRow / buildPopArtifactRow 各挂一个全量 attachments 描述符），
// 引用对象 = 该行 messageId，引用块缩略带把整组都排出来 ⇒ 单张**完全不支持**。
// 本批路径：展开态每行上左角一枚单张引用钮（折叠面零新按钮，拖动/横滑仲裁零
// 交集）→ startItemQuote 以「行身份 + 该张描述符」进引用态，content.itemIndex /
// itemTotal 标注位置；深链点回时展开该叠卡并高亮第 N 行。
//
// 机械断言（作者令逐条）：
//   ① 三张图各自引用：目标 id 两两不同，且每张的载荷指向**它自己**那张
//      （itemIndex = 该张序号、attachments 只带该张、摘要带「第 N/3 张」）；
//   ② 三个引用牌互不相同（发送后三块引用块的全文两两不同，各含自己的张序 + 文件名）；
//   ③ 点第 2 张的引用牌 ⇒ 定位到**第 2 张**（目标叠卡展开 + 第 2 行高亮 + 该行在视口内）；
//   ④ 单图退化 = 消息级引用、无张数 UI（无 itemIndex、摘要不含「第 N/M 张」、
//      平铺单图面上没有单张引用钮）；
//   ⑤ 不破坏既有语义：折叠态无引用钮（整面点击 = 打开当前张不变）、点引用钮不
//      触发整面打开、拖动仍翻页、行级（整组）引用钮照旧可用。
//
// 驱动：tests/fixtures/quote-message/harness.html（真实模块，无 stub）。
// Run: node node_modules/@playwright/test/cli.js test tests/stackcard-quote.spec.mjs
import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/quote-message/harness.html';
const SHOTS = path.join(REPO_ROOT, 'evidence', '20261102_stackcard-ref-and-synccard-center', 'after');

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
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], { stdio: 'ignore' });
}

async function waitUntilUp(url, tries = 80) {
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
  fs.mkdirSync(SHOTS, { recursive: true });
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

async function newPage(browser, viewport = { width: 1200, height: 900 }, extra = {}) {
  const context = await browser.newContext({ viewport, ...extra });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/* ═══════ ① 三张图各自引用：目标 id 两两不同且各指自己那张 ═══════ */

test.describe('① 单张可引用（引用目标 = 该图自己）', () => {
  test('三张图各自的引用载荷：id 两两不同、itemIndex 对应、只带该张', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    expect(msgId, '用户媒体行 data-msg-id 在场').toBeTruthy();
    // 展开态每行一枚单张引用钮，索引 0/1/2，折叠面无钮（验收⑤）
    const btns = await page.evaluate(id => window.__stackQuoteButtons(id), msgId);
    expect(btns.expanded, '叠卡已展开').toBe(true);
    expect(btns.count).toBe(3);
    expect(btns.buttons.map(b => b.index)).toEqual(['0', '1', '2']);
    expect(btns.buttons.every(b => b.collapsedHasButton === false), '折叠面无单张引用钮').toBe(true);

    const picked = [];
    for (const i of [0, 1, 2]) {
      const r = await page.evaluate(([id, k]) => window.__quoteItemOn(id, k), [msgId, i]);
      expect(r.ok, `第 ${i} 张引用态可进：${r.why || ''}`).toBe(true);
      picked.push(r);
    }
    // ① 目标 id 两两不同
    const ids = picked.map(p => p.id);
    expect(new Set(ids).size, `三个引用 id 应两两不同：${ids.join(',')}`).toBe(3);
    // ① 每张的载荷 = 它自己那张（itemIndex / 只带该张的附件 / 文件名）
    picked.forEach((p, i) => {
      expect(p.itemIndex, `第 ${i} 张 itemIndex`).toBe(i);
      expect(p.itemTotal, `第 ${i} 张 itemTotal`).toBe(3);
      expect(p.attNames, `第 ${i} 张只带该张附件`).toEqual([`shot-${i}.png`]);
      expect(p.attPaths).toEqual([`/tmp/up/shot-${i}.png`]);
      expect(p.excerpt, `第 ${i} 张摘要带位置标注`).toContain(`第 ${i + 1}/3 张`);
      expect(p.excerpt).toContain(`shot-${i}.png`);
      expect(p.messageId, '引用目标仍是该行 messageId（深链寻址不变）').toBe(msgId);
    });
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════ ②+③ 引用牌互不相同 + 点引用牌定位到第 2 张 ═══════ */

test.describe('②③ 引用牌可区分 + 深链定位到那一张', () => {
  test('三块引用牌两两不同；点第 2 张的引用牌 ⇒ 展开并高亮第 2 行', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    // 依次对第 1/2/3 张进引用态并发一条消息 ⇒ 三块引用牌
    const sent = [];
    for (const i of [0, 1, 2]) {
      await page.evaluate(([id, k]) => window.__quoteItemOn(id, k), [msgId, i]);
      const rid = await page.evaluate(k => window.__sendQuoted('引用第 ' + (k + 1) + ' 张', 'cm-item-' + k), i);
      expect(rid, `第 ${i} 条带引用消息落地`).toBeTruthy();
      sent.push(rid);
    }
    const shapes = [];
    for (const rid of sent) shapes.push(await page.evaluate(id => window.__quoteBlockShape(id), rid));
    // ② 三个引用牌互不相同（全文两两不同 + 各自的张序/文件名在场）
    const texts = shapes.map(s => s.text);
    expect(new Set(texts).size, `三个引用牌应互不相同：\n${texts.join('\n')}`).toBe(3);
    shapes.forEach((s, i) => {
      expect(s.itemIndex, `第 ${i} 块带张序`).toBe(String(i));
      expect(s.hasPosText, `第 ${i} 块显示「第 N/3 张」`).toBe(true);
      expect(s.text).toContain(`shot-${i}.png`);
      expect(s.attCells, '引用牌只出该张一枚缩略').toBe(1);
      expect(s.jump, '目标行在场 ⇒ 可点').toBe('ready');
    });
    // ③ 点第 2 张（index 1）的引用牌 ⇒ 定位到那一张
    const jump = await page.evaluate(id => window.__jumpItem(id), sent[1]);
    expect(jump.ok).toBe(true);
    expect(jump.expanded, '目标叠卡被展开').toBe(true);
    expect(jump.flashed, '高亮落在第 2 行（index 1）').toEqual([1]);
    expect(jump.layerInView[1], '第 2 行在视口内').toBe(true);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOTS, 'stackcard-quote-jump-item2.png'), fullPage: false });
    await context.close();
  });
});

/* ═══════ ④ 单图退化 = 消息级引用、无张数 UI ═══════ */

test.describe('④ 单图退化', () => {
  test('单图消息：引用无张序、摘要无「第 N/M 张」、平铺面无单张引用钮', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(1));
    expect(msgId).toBeTruthy();
    // 平铺单图没有叠卡 ⇒ 无展开列、无单张引用钮
    expect(await page.evaluate(id => window.__stackQuoteButtons(id), msgId)).toBeNull();
    // 行级引用照旧（整条消息语义）
    const r = await page.evaluate(id => window.__quoteRowOn(id), msgId);
    expect(r.ok, '单图行的 footer 引用钮仍可用').toBe(true);
    expect(r.itemIndex, '单图引用不带张序').toBeUndefined();
    expect(r.excerpt, '单图摘要无张数 UI').not.toMatch(/第 \d+\/\d+ 张/);
    expect(r.attNames).toEqual(['shot-0.png']);
    // 发送后引用块同判
    const rid = await page.evaluate(() => window.__sendQuoted('单图引用', 'cm-single'));
    const shape = await page.evaluate(id => window.__quoteBlockShape(id), rid);
    expect(shape.itemIndex, '引用块无张序数据').toBeNull();
    expect(shape.hasPosText, '引用块无张数 UI').toBe(false);
    expect(shape.jump).toBe('ready');
    // 深链在无张序时退回整行定位（不抛、不死点击）
    const jump = await page.evaluate(id => window.__jumpItem(id), rid);
    expect(jump.ok).toBe(true);
    expect(jump.rowFlash, '整行仍高亮').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════ ⑤ 既有语义不被破坏（折叠面 / 拖动 / 整组引用） ═══════ */

test.describe('⑤ 既有语义不破', () => {
  test('折叠面整面点击不改、拖动仍翻页、行级引用钮照旧', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    // 折叠态：无单张引用钮；「展开 N」钮文案照旧
    const collapsed = await page.evaluate((id) => {
      const row = window.__mediaRowById(id);
      const area = row.querySelector('.pop-stack-area');
      return {
        quoteBtns: area.querySelectorAll('.pop-stack-view .pop-media-quote').length,
        pill: (area.querySelector('.pill') || {}).textContent || '',
        front: area.querySelector('.pop-stack-view .layer-front .pop-img')
          ? area.querySelector('.pop-stack-view .layer-front .pop-img').getAttribute('alt') : '',
      };
    }, msgId);
    expect(collapsed.quoteBtns, '折叠面无单张引用钮').toBe(0);
    expect(collapsed.pill, '展开钮文案').toContain('展开');
    // 真实拖动（>38px）仍翻页：front 层换人
    const drag = await page.evaluate(async (id) => {
      const row = window.__mediaRowById(id);
      const view = row.querySelector('.pop-stack-view');
      const before = row.querySelector('.pop-stack-view .layer-front').className;
      const r = view.getBoundingClientRect();
      const opts = { bubbles: true, pointerId: 1, pointerType: 'mouse', isPrimary: true };
      view.dispatchEvent(new PointerEvent('pointerdown', { ...opts, clientX: r.x + r.width - 20, clientY: r.y + r.height / 2 }));
      view.dispatchEvent(new PointerEvent('pointermove', { ...opts, clientX: r.x + r.width - 120, clientY: r.y + r.height / 2 }));
      view.dispatchEvent(new PointerEvent('pointerup', { ...opts, clientX: r.x + r.width - 120, clientY: r.y + r.height / 2 }));
      await new Promise(res => requestAnimationFrame(() => requestAnimationFrame(res)));
      const after = row.querySelector('.pop-stack-view .layer-front').className;
      const frontIdx = Array.from(row.querySelectorAll('.pop-stack-view .media-layer')).findIndex(l => l.classList.contains('layer-front'));
      return { changed: before !== after, frontIdx };
    }, msgId);
    expect(drag.frontIdx, '拖动后 front 换到第 2 张').toBe(1);
    // 行级（整组）引用：attachments = 全量、无张序 —— 既有语义逐字保留
    const whole = await page.evaluate(id => window.__quoteRowOn(id), msgId);
    expect(whole.ok).toBe(true);
    expect(whole.itemIndex, '整组引用无张序').toBeUndefined();
    expect(whole.attNames.length, '整组引用带全量附件').toBe(3);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('手机 375×812：展开态引用钮可点、引用态成立（触屏无 hover 也可见）', async ({ browser }) => {
    // 触屏腿要真设备语义：hasTouch + 无 hover（CSS `@media (hover: none)` 才命中）；
    // deviceScaleFactor 2 = 真机 DPR，样张才看得清 28px 的引用钮。
    const { context, page, pageErrors } = await newPage(browser, { width: 375, height: 812 }, { hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    await page.evaluate(id => window.__expandStack(id), msgId);
    const opa = await page.evaluate((id) => {
      const row = window.__mediaRowById(id);
      const b = row.querySelector('.pop-expand-view .pop-media-quote');
      return b ? getComputedStyle(b).opacity : 'none';
    }, msgId);
    expect(Number(opa), '无 hover 设备上引用钮可见（opacity ≥0.7）').toBeGreaterThanOrEqual(0.7);
    const r = await page.evaluate(([id]) => window.__quoteItemOn(id, 1), [msgId]);
    expect(r.ok).toBe(true);
    expect(r.itemIndex).toBe(1);
    expect(pageErrors).toEqual([]);
    // 样张取**叠卡区特写**（375 整页缩略里 28px 的引用钮看不见；特写才有证据
    // 价值）。⚠ 不能加 `animations:'disabled'`：Playwright 那条会把 opacity 的
    // transition 取消回基态，引用钮（opacity 0→0.75）会在样张里消失 —— 样张必须
    // 是用户真看到的帧。
    await page.locator('#chat .pop-stack-area').last().screenshot({
      path: path.join(SHOTS, 'stackcard-quote-mobile-expanded.png'),
    });
    await context.close();
  });
});
