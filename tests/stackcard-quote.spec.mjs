// stackcard-quote.spec.mjs — 叠卡内每张图片可分别引用（作者令 2026-11-02 09:19 ①
// 「让叠卡的图片可以分开引用」）+ **引用入口移入 footer**（作者令 2026-11-02 11:00
// 「图片引用在footer显示就行了。不需要hover图片的时候也显示一个图标」）的验收网。
//
// 本批形态（改后）：
//   · 叠卡面上**零引用钮** —— 展开态每行、折叠盒上都不许出现 `.pop-media-quote`
//     （也不再有任何 hover 才浮现的图标）；
//   · 入口 —— 🔴 composer footer 引用键已随作者令 2026-11-03 10:11 ② 整链退役；本 spec
//      保留**能力面**验收（引用载荷/张序/摘要由 startItemQuote 直调验证），入口腿改退役钉
//     （与附件键同族 `.icon-btn`），动作对象 = 最近一条叠卡的**当前张**
//     （`popQuoteState().index` = 拖动/横滑翻到的那张）；
//   · 引用载荷语义不变：content.itemIndex/itemTotal 标注位置，深链展开并高亮第 N 行。
//
// 机械断言（作者令逐条）：
//   ① 三张图各自引用：目标 id 两两不同，且每张的载荷指向**它自己**那张；
//   ② 三个引用牌互不相同（发送后三块引用块的全文两两不同，各含自己的张序 + 文件名）；
//   ③ 点第 2 张的引用牌 ⇒ 定位到**第 2 张**（目标叠卡展开 + 第 2 行高亮 + 该行在视口内）；
//   ④ 单图退化 = 消息级引用、无张数 UI（无 itemIndex、摘要不含「第 N/M 张」、
//      平铺单图面上没有叠卡 ⇒ 单张引用入口不存在（键已整体退役，恒不在场））；
//   ⑤ 不破坏既有语义：叠卡面（折叠 + 展开）零 `.pop-media-quote`、拖动仍翻页、
//      footer 键随 front 换目标、行级（整组）引用钮照旧可用。
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
const SHOTS = path.join(REPO_ROOT, 'evidence', '20261102_quote-iconify', 'after');

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

/* ═══════ ① 单张可引用：入口 = footer 键，目标 = 当前张 ═══════ */

test.describe('① 单张可引用（引用目标 = 该图自己）', () => {
  test('三张图各自的引用载荷：id 两两不同、itemIndex 对应、只带该张；面上零引用钮', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    expect(msgId, '用户媒体行 data-msg-id 在场').toBeTruthy();
    const shape = await page.evaluate(id => window.__stackQuoteButtons(id), msgId);
    expect(shape.expanded, '叠卡已展开').toBe(true);
    expect(shape.count).toBe(3);
    // 作者令的机械判据：叠卡面上零引用钮（展开列 + 折叠盒都零命中）
    expect(shape.surfaceButtons, '叠卡面上零 .pop-media-quote').toBe(0);
    expect(shape.buttons.every(b => b.index === null), '展开列每行都无引用钮').toBe(true);
    expect(shape.buttons.every(b => b.collapsedHasButton === false), '折叠面无引用钮').toBe(true);
    // 🔴 2026-11-03 10:11 ②：composer 引用键整链退役（「不留替代按钮」）——
    //    原「入口键在场/在动作行/可见/文案」四条断言随键退役，改为退役钉。
    expect(shape.footer, 'footer 引用键已退役（0 元素）').toBeNull();
    expect(shape.footerRetired, '探针确认 #quote-item-btn 不在场').toBe(true);

    const picked = [];
    for (const i of [0, 1, 2]) {
      const r = await page.evaluate(([id, k]) => window.__quoteItemOn(id, k), [msgId, i]);
      expect(r.ok, `第 ${i} 张引用态可进：${r.why || ''}`).toBe(true);
      picked.push(r);
    }
    const ids = picked.map(p => p.id);
    expect(new Set(ids).size, `三个引用 id 应两两不同：${ids.join(',')}`).toBe(3);
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
    const sent = [];
    for (const i of [0, 1, 2]) {
      await page.evaluate(([id, k]) => window.__quoteItemOn(id, k), [msgId, i]);
      const rid = await page.evaluate(k => window.__sendQuoted('引用第 ' + (k + 1) + ' 张', 'cm-item-' + k), i);
      expect(rid, `第 ${i} 条带引用消息落地`).toBeTruthy();
      sent.push(rid);
    }
    const shapes = [];
    for (const rid of sent) shapes.push(await page.evaluate(id => window.__quoteBlockShape(id), rid));
    const texts = shapes.map(s => s.text);
    expect(new Set(texts).size, `三个引用牌应互不相同：\n${texts.join('\n')}`).toBe(3);
    shapes.forEach((s, i) => {
      expect(s.itemIndex, `第 ${i} 块带张序`).toBe(String(i));
      expect(s.hasPosText, `第 ${i} 块显示「第 N/3 张」`).toBe(true);
      expect(s.text).toContain(`shot-${i}.png`);
      expect(s.attCells, '引用牌只出该张一枚缩略').toBe(1);
      expect(s.jump, '目标行在场 ⇒ 可点').toBe('ready');
      // 引用牌图标化：叠卡单张 ⇒ 图片真缩略（内联 data: 直用）
      expect(s.icon, `第 ${i} 块有类型图标`).toBeTruthy();
      expect(s.icon.kind, `第 ${i} 块图标 = 真缩略`).toBe('thumb');
      expect(s.icon.src.startsWith('data:image/'), `第 ${i} 块缩略 src 是内联字节`).toBe(true);
    });
    // 三个不同图 ⇒ 三个不同缩略 src（作者令「图片就是图片缩略」的机械判据）
    const srcs = shapes.map(s => s.icon.src);
    expect(new Set(srcs).size, '三张不同图 ⇒ 三个不同缩略 src').toBe(3);
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
  test('单图消息：引用无张序、摘要无「第 N/M 张」、平铺面无叠卡 ⇒ footer 单张键隐身', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(1));
    expect(msgId).toBeTruthy();
    // 平铺单图没有叠卡 ⇒ 无 popQuoteState、footer 单张引用键隐身（零死入口）
    expect(await page.evaluate(id => window.__stackQuoteButtons(id), msgId)).toBeNull();
    // 🔴 2026-11-03 10:11 ②：输入框引用键退役 ⇒ 「无叠卡时隐身」的前提不再存在
    //    （零死入口 = 键根本不在场）。
    expect(await page.evaluate(() => !!document.getElementById('quote-item-btn')),
      'footer 键已退役（无叠卡/有叠卡都不在场）').toBe(false);
    // 行级引用照旧（整条消息语义）
    const r = await page.evaluate(id => window.__quoteRowOn(id), msgId);
    expect(r.ok, '单图行的 footer 引用钮仍可用').toBe(true);
    expect(r.itemIndex, '单图引用不带张序').toBeUndefined();
    expect(r.excerpt, '单图摘要无张数 UI').not.toMatch(/第 \d+\/\d+ 张/);
    expect(r.attNames).toEqual(['shot-0.png']);
    const rid = await page.evaluate(() => window.__sendQuoted('单图引用', 'cm-single'));
    const shape = await page.evaluate(id => window.__quoteBlockShape(id), rid);
    expect(shape.itemIndex, '引用块无张序数据').toBeNull();
    expect(shape.hasPosText, '引用块无张数 UI').toBe(false);
    expect(shape.jump).toBe('ready');
    // 单图引用块的图标仍是图片缩略（类型图标与张序正交）
    expect(shape.icon.kind).toBe('thumb');
    const jump = await page.evaluate(id => window.__jumpItem(id), rid);
    expect(jump.ok).toBe(true);
    expect(jump.rowFlash, '整行仍高亮').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════ ⑤ 既有语义不被破坏（零引用钮 / 拖动 / footer 键跟随 front / 整组引用） ═══════ */

test.describe('⑤ 既有语义不破', () => {
  test('叠卡面零引用钮、拖动仍翻页且 footer 键跟随 front、行级引用钮照旧', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    const collapsed = await page.evaluate((id) => {
      const row = window.__mediaRowById(id);
      const area = row.querySelector('.pop-stack-area');
      return {
        quoteBtns: area.querySelectorAll('.pop-media-quote').length,
        pill: (area.querySelector('.pill') || {}).textContent || '',
        front: area.querySelector('.pop-stack-view .layer-front .pop-img')
          ? area.querySelector('.pop-stack-view .layer-front .pop-img').getAttribute('alt') : '',
      };
    }, msgId);
    expect(collapsed.quoteBtns, '折叠面零引用钮').toBe(0);
    expect(collapsed.pill, '展开钮文案').toContain('展开');
    // 展开后仍然零引用钮（本批核心：不再有 hover 才浮现的图标）
    const expanded = await page.evaluate(async id => {
      await window.__expandStack(id);
      const row = window.__mediaRowById(id);
      return {
        btns: row.querySelectorAll('.pop-media-quote').length,
        layers: row.querySelectorAll('.pop-expand-view .media-layer').length,
      };
    }, msgId);
    expect(expanded.layers, '展开列三行在场').toBe(3);
    expect(expanded.btns, '展开态零引用钮（无 hover 浮现图标）').toBe(0);

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
      const st = window.__stackFront(id);
      const btn = document.getElementById('quote-item-btn');
      return { changed: before !== after, frontIdx, state: st, btnTitle: btn ? btn.title : '', btnHidden: btn ? !!btn.hidden : null, btnRetired: !btn };
    }, msgId);
    expect(drag.frontIdx, '拖动后 front 换到第 2 张').toBe(1);
    expect(drag.state.index, 'popQuoteState 的当前张跟着拖动走').toBe(1);
    expect(drag.state.descName, '当前张描述符 = 第 2 张').toBe('shot-1.png');
    expect(drag.btnRetired, '引用键保持退役态（0 元素）').toBe(true);

    const whole = await page.evaluate(id => window.__quoteRowOn(id), msgId);
    expect(whole.ok).toBe(true);
    expect(whole.itemIndex, '整组引用无张序').toBeUndefined();
    expect(whole.attNames.length, '整组引用带全量附件').toBe(3);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('手机 375×812：footer 引用键可点、引用态成立（触屏无 hover 也可见）', async ({ browser }) => {
    // 触屏腿要真设备语义：hasTouch + 无 hover；deviceScaleFactor 2 = 真机 DPR。
    const { context, page, pageErrors } = await newPage(browser, { width: 375, height: 812 }, { hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    // 手机层姿态类（真机由 mobileLayer 挂；harness 独立页显式补，量的是手机档几何）
    await page.evaluate(() => document.body.classList.add('mobile-layer'));
    await page.evaluate(id => window.__expandStack(id), msgId);
    const geo = await page.evaluate((id) => {
      const row = window.__mediaRowById(id);
      const surface = row.querySelectorAll('.pop-media-quote').length;
      return { surface, btnRetired: !document.getElementById('quote-item-btn') };
    }, msgId);
    expect(geo.surface, '手机层叠卡面同样零引用钮').toBe(0);
    expect(geo.btnRetired, '手机层引用键同样退役（0 元素）').toBe(true);
    const r = await page.evaluate(([id]) => window.__quoteItemOn(id, 1), [msgId]);
    expect(r.ok).toBe(true);
    expect(r.itemIndex).toBe(1);
    expect(pageErrors).toEqual([]);
    await page.locator('#chat .pop-stack-area').last().screenshot({
      path: path.join(SHOTS, 'stackcard-quote-mobile-expanded.png'),
    });
    await context.close();
  });
});
