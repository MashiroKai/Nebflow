// quote-iconify.spec.mjs — 引用入口移入 footer + 引用牌图标化（作者令 2026-11-02
// 11:00「图片引用在footer显示就行了。不需要hover图片的时候也显示一个图标。然后引用
// 的消息也需要图标化，文档和图片就是原图标和图片缩略，文字的话就单独设计一个logo」）
// 的机械验收网。
//
// 七条机械断言：
//   ① 叠卡面上零引用钮 —— 折叠态、展开态、hover 态三处 `.pop-media-quote` 命中数全为 0
//      （「不需要hover图片的时候也显示一个图标」= 那枚 hover 图标整条退役）；
//   ② 单张引用入口 = composer footer 动作行的一枚键 —— 🔴 **已随作者令 2026-11-03
//      10:11 ② 整链退役**（「输入框引用图标移除，不留替代按钮」）：本条改为退役钉
//      （#quote-item-btn 0 元素 + 动作行零引用控件）；当前张状态仍跟手（能力面保留）；
//   ③ 引用牌三分支图标化：图片 = 真缩略（内联 data: `<img>`）/ 文档 = `.fc-icon` 既有
//      扩展名色族 / 文字 = 独立 logo（内联 SVG）；历史腿无 preview ⇒ 图片字形（不伪造缩略）；
//   ④ 三张不同图 ⇒ 三块引用牌的缩略 src 两两不同（图标是「这张」的，不是组的）；
//   ⑤ 引用牌几何零回归：图标 16×16、在摘要行之上、`.msg-quote-text` 仍只含摘要全文、
//      引用块高度相对无图标的基线增量 ≤ 图标行高（≤22px）；
//   ⑥ composer 引用 chip 同样图标化，且 chip 高度档不变（28px ±2px；与附件 chip 同档）；
//   ⑦ 其它 refType（file/document/task/html-element）的 composer chip 图标逐字节不变
//      （仍走 `chipIcon` 的 svg 族，无 `.nf-qicon`）—— 改动只对 chat-message 生效。
//
// 驱动：tests/fixtures/quote-message/harness.html（真实模块，无 stub）。
// Run: node node_modules/@playwright/test/cli.js test tests/quote-iconify.spec.mjs
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

test.beforeAll(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });
  port = await freePort();
  servers.push(spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', REPO_ROOT], { stdio: 'ignore' }));
  for (let i = 0; i < 80; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}${HARNESS_PATH}`);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('static server never came up');
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

/* ═══════ ①② 叠卡面零引用钮 + 唯一入口 = footer 键 ═══════ */

test.describe('①② 引用入口只活在 footer', () => {
  test('折叠/展开/hover 三态叠卡面零引用钮；footer 键在场且作用于当前张', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));

    // ① 折叠态：零命中
    expect(await page.evaluate(id => window.__mediaRowById(id).querySelectorAll('.pop-media-quote').length, msgId)).toBe(0);
    // ① 展开态：零命中（真实手势 = 点「展开 N」）
    await page.evaluate(id => window.__expandStack(id), msgId);
    const expanded = await page.evaluate(id => {
      const row = window.__mediaRowById(id);
      return {
        total: row.querySelectorAll('.pop-media-quote').length,
        perLayer: Array.from(row.querySelectorAll('.pop-expand-view .media-layer'))
          .map(l => l.querySelectorAll('.pop-media-quote').length),
        canvasCorners: row.querySelectorAll('.pop-expand-view .pop-media-canvas').length,
      };
    }, msgId);
    expect(expanded.total, '展开态叠卡面零 .pop-media-quote').toBe(0);
    expect(expanded.perLayer, '展开列每行零引用钮（Canvas 角标是另一族 .pop-media-canvas）').toEqual([0, 0, 0]);
    expect(expanded.canvasCorners, 'Canvas 角标照旧三枚（引用钮退役不牵动它）').toBe(3);
    // ① hover 态：鼠标真悬停在图片上，仍然零命中（作者令的正面判据）
    const layer = page.locator('#chat .pop-expand-view .media-layer').first();
    await layer.hover();
    await page.waitForTimeout(120); // 让 :hover 过渡档落定
    const hovered = await page.evaluate(id => ({
      hits: window.__mediaRowById(id).querySelectorAll('.pop-media-quote').length,
      hoverHits: window.__mediaRowById(id).querySelectorAll('.media-layer:hover .pop-media-quote').length,
      visibleButtons: Array.from(window.__mediaRowById(id).querySelectorAll('.pop-expand-view button'))
        .filter(b => getComputedStyle(b).opacity !== '0' && b.getBoundingClientRect().width > 0)
        .map(b => b.className),
    }), msgId);
    expect(hovered.hits, 'hover 态零 .pop-media-quote').toBe(0);
    expect(hovered.hoverHits, 'hover 命中数为 0').toBe(0);
    expect(hovered.visibleButtons, '展开列可见按钮只有 Canvas 角标').toEqual(['pop-media-canvas']);

    // ② 🔴 输入框引用键已整链退役（作者令 2026-11-03 10:11 ②「输入框引用图标移除，
    //    不留替代按钮」）：动作行零引用控件、零替代按钮，本 spec 的原「footer 键在场/
    //    几何/文案」三条断言**随键退役**，改为退役钉（键 0 元素 + 动作行零引用控件）。
    const entry = await page.evaluate(() => ({
      key: !!document.getElementById('quote-item-btn'),
      barButtons: Array.from(document.querySelectorAll('#input-bar button'))
        .map(b => b.id || b.className || b.tagName.toLowerCase()),
      quoteish: Array.from(document.querySelectorAll('#input-bar button'))
        .filter(b => /quote/i.test(b.id + ' ' + b.className)).length,
    }));
    expect(entry.key, 'footer 引用键已退役（0 元素）').toBe(false);
    expect(entry.quoteish, '动作行零引用控件（无替代按钮）').toBe(0);

    // ② 翻页：当前张状态仍然跟手（能力面保留，只是没有 UI 入口）
    const afterDrag = await page.evaluate(async (id) => {
      const row = window.__mediaRowById(id);
      const view = row.querySelector('.pop-stack-view');
      const r = view.getBoundingClientRect();
      const opts = { bubbles: true, pointerId: 1, pointerType: 'mouse', isPrimary: true };
      view.dispatchEvent(new PointerEvent('pointerdown', { ...opts, clientX: r.x + r.width - 20, clientY: r.y + r.height / 2 }));
      view.dispatchEvent(new PointerEvent('pointermove', { ...opts, clientX: r.x + r.width - 120, clientY: r.y + r.height / 2 }));
      view.dispatchEvent(new PointerEvent('pointerup', { ...opts, clientX: r.x + r.width - 120, clientY: r.y + r.height / 2 }));
      await new Promise(res => requestAnimationFrame(() => requestAnimationFrame(res)));
      return { state: window.__stackFront(id) };
    }, msgId);
    expect(afterDrag.state.index, '拖动把当前张翻到 index 1').toBe(1);
    expect(afterDrag.state.descName).toBe('shot-1.png');

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════ ③ 引用牌三分支图标化 ═══════ */

test.describe('③ 引用牌带类型图标（图片缩略 / 文档原图标 / 文字 logo）', () => {
  test('图片 = 真缩略；文档 = .fc-icon 色族；文字 = 独立 logo；历史腿无 preview ⇒ 图片字形', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const tiny = await page.evaluate(() => window.__tinyImg('IK'));

    const imgId = await page.evaluate(([preview]) => window.__quotePill({
      kind: 'image', fileName: 'site-photo.png', path: '/tmp/x/site-photo.png',
      attachments: [{ type: 'image', name: 'site-photo.png', path: '/tmp/x/site-photo.png', preview }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }, '带图片引用', 'cm-ic-img'), [tiny]);
    await page.evaluate(() => window.__clearQuoteAll());
    const docId = await page.evaluate(() => window.__quotePill({
      kind: 'file', fileName: '季度报告.pdf', path: '/tmp/x/季度报告.pdf',
      attachments: [{ type: 'file', name: '季度报告.pdf', path: '/tmp/x/季度报告.pdf' }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }, '带文档引用', 'cm-ic-doc'));
    await page.evaluate(() => window.__clearQuoteAll());
    const txtId = await page.evaluate(() => window.__quotePill({
      kind: 'text', text: '这是一段被引用的文字', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }, '带文字引用', 'cm-ic-txt'));
    // 历史腿：后端 quote 快照白名单无 preview ⇒ 图片只能落字形（不伪造缩略）
    await page.evaluate(() => window.__clearQuoteAll());
    const histId = await page.evaluate(() => window.__quotePill({
      kind: 'image', fileName: 'archived-shot.png', path: '/tmp/x/archived-shot.png',
      attachments: [{ type: 'image', name: 'archived-shot.png', path: '/tmp/x/archived-shot.png' }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }, '带历史图片引用', 'cm-ic-hist'));

    const img = await page.evaluate(id => window.__quoteBlockShape(id), imgId);
    expect(img.icon.kind, '图片引用牌 = 真缩略').toBe('thumb');
    expect(img.icon.src.startsWith('data:image/'), '缩略来自内联字节').toBe(true);
    expect(img.icon.w, '图标盒 16px 档').toBe(16);
    expect(img.icon.h).toBe(16);
    expect(img.icon.leftOfText, '图标在摘要行的左列（引用块左缘）').toBe(true);
    expect(img.icon.besideText, '图标与摘要首行同高（左列 + 右列文本）').toBe(true);
    expect(img.text, '摘要文本零污染（.msg-quote-text 只装发送方 + 摘要）').toContain('[图片]');
    expect(img.text, '图标字形不进摘要文本').not.toMatch(/[\uE000-\uF8FF]/);

    const doc = await page.evaluate(id => window.__quoteBlockShape(id), docId);
    expect(doc.icon.kind, '文档引用牌 = .fc-icon 色块').toBe('file');
    expect(doc.icon.fcCls, '复用既有 PDF 扩展名色族').toBe('pdf');
    expect(doc.icon.text, '色块角标 = fileIconStyle 的 label').toBe('PDF');
    expect(doc.text).toContain('[文件]');
    expect(doc.text).toContain('季度报告.pdf');

    const txt = await page.evaluate(id => window.__quoteBlockShape(id), txtId);
    expect(txt.icon.kind, '文字引用牌 = logo 字形').toBe('glyph');
    expect(txt.icon.hasSvg, 'logo 是内联 SVG').toBe(true);
    expect(txt.icon.text, 'logo 无文字节点（不污染 chipText）').toBe('');
    expect(txt.text).toContain('这是一段被引用的文字');

    const hist = await page.evaluate(id => window.__quoteBlockShape(id), histId);
    expect(hist.icon.kind, '无 preview 的图片引用 ⇒ 图片字形（不伪造缩略）').toBe('glyph');
    expect(hist.icon.hasSvg).toBe(true);
    expect(hist.text).toContain('[图片]');

    // ⑤ 几何：图标行只加一行高，摘要文本不变形
    expect(img.textHeight, '摘要两行档内').toBeLessThanOrEqual(40);
    const delta = await page.evaluate(id => window.__iconDelta(id), imgId);
    expect(delta, '同块前后对比可量').toBeTruthy();
    expect(delta.delta, '图标零高度代价（左列与摘要首行同高，撑高的是摘要不是图标）').toBeLessThanOrEqual(22);
    expect(delta.textHeight, '摘掉图标后摘要仍两行 clamp').toBeLessThanOrEqual(40);

    expect(pageErrors).toEqual([]);
    await page.locator('#chat').screenshot({ path: path.join(SHOTS, 'quote-pill-icons-desktop.png') });
    await context.close();
  });
});

/* ═══════ ④ 三张不同图 ⇒ 三个不同缩略 ═══════ */

test.describe('④ 图标属于「这张」而不是「这一组」', () => {
  test('同一叠卡三张引用牌 ⇒ 三个不同缩略 src；每个都是该张自己的字节', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const { msgId } = await page.evaluate(() => window.__renderUserStack(3));
    const sent = [];
    for (const i of [0, 1, 2]) {
      await page.evaluate(([id, k]) => window.__quoteItemOn(id, k), [msgId, i]);
      sent.push(await page.evaluate(k => window.__sendQuoted('第 ' + (k + 1) + ' 张', 'cm-src-' + k), i));
    }
    const shapes = [];
    for (const rid of sent) shapes.push(await page.evaluate(id => window.__quoteBlockShape(id), rid));
    const srcs = shapes.map(s => s.icon.src);
    expect(srcs.every(s => s.startsWith('data:image/')), '三块都是真缩略').toBe(true);
    expect(new Set(srcs).size, '三个不同图 ⇒ 三个不同 src').toBe(3);
    // 同一张重复引用 ⇒ src 恒等（图标跟随内容，不是跟随渲染次序）
    const againId = await page.evaluate(async (id) => {
      await window.__quoteItemOn(id, 1);
      return window.__sendQuoted('再来一次第 2 张', 'cm-src-again');
    }, msgId);
    const again = await page.evaluate(id => window.__quoteBlockShape(id), againId);
    expect(again.icon.src, '同一张的缩略 src 恒等').toBe(srcs[1]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════ ⑥⑦ composer chip 图标化 + 其它 refType 零变化 ═══════ */

test.describe('⑥⑦ composer 引用 chip', () => {
  test('chip 图标三分支；chip 高度档不变（28 ±2）；其它 refType 的图标逐字节不变', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.emulateMedia({ reducedMotion: 'reduce' }); // 几何读数避开入场动画
    const tiny = await page.evaluate(() => window.__tinyImg('CH'));

    // ⑥ 图片 chip：真缩略，高 28 档
    await page.evaluate(([preview]) => window.__startQuoteDirect({
      kind: 'image', fileName: 'site-photo.png', path: '/tmp/x/site-photo.png',
      attachments: [{ type: 'image', name: 'site-photo.png', path: '/tmp/x/site-photo.png', preview }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }), [tiny]);
    let chip = await page.evaluate(() => window.__chipIconShape());
    expect(chip.kind, '图片 chip = 真缩略').toBe('thumb');
    expect(chip.src.startsWith('data:image/')).toBe(true);
    expect(chip.w, 'chip 图标 18px 档').toBe(18);
    expect(chip.chipHeight, 'chip 高度档不变（28 ±2）').toBeGreaterThanOrEqual(26);
    expect(chip.chipHeight).toBeLessThanOrEqual(30);

    // ⑥ 文档 chip：.fc-icon 色族
    // 多选引用批（2026-11-22）：引用态是追加语义 ⇒ 换类取证前复位（单条整卡读数面）。
    await page.evaluate(() => window.__clearQuoteAll());
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'file', fileName: '季度报告.pdf', path: '/tmp/x/季度报告.pdf',
      attachments: [{ type: 'file', name: '季度报告.pdf', path: '/tmp/x/季度报告.pdf' }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }));
    chip = await page.evaluate(() => window.__chipIconShape());
    expect(chip.kind, '文档 chip = .fc-icon').toBe('file');
    expect(chip.fcCls).toBe('pdf');
    expect(chip.chipHeight, '文档 chip 仍 28 档').toBeGreaterThanOrEqual(26);
    expect(chip.chipHeight).toBeLessThanOrEqual(30);

    // ⑥ 文字 chip：logo 字形
    await page.evaluate(() => window.__clearQuoteAll());
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'text', text: '被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }));
    chip = await page.evaluate(() => window.__chipIconShape());
    expect(chip.kind, '文字 chip = logo 字形').toBe('glyph');
    expect(chip.hasSvg).toBe(true);
    expect(chip.chipText, 'chip 文本仍是「发送方：摘要」').toContain('被引用的文字消息');

    // ⑦ 其它 refType：file/document 的 chip 图标仍走 chipIcon（无 .nf-qicon）
    const others = await page.evaluate(async () => {
      const refMod = await import('/src/main/resources/web/js/reference.js');
      const mk = (refType, source, meta) => refMod.renderRefBlock({
        type: 'ref', refType, id: 'ref:' + refType, source, display: { label: 'x' }, meta,
      }, { mode: 'input' });
      const out = {};
      for (const [k, ref] of Object.entries({
        file: mk('file', { path: '/tmp/a.md', fileName: 'a.md' }, { icon: 'file', typeLabel: 'MD' }),
        document: mk('document', { title: '页面', path: '/tmp/b' }, { icon: 'document', typeLabel: 'DOC' }),
        task: mk('task', { taskId: 7, title: '任务' }, { icon: 'clipboard', typeLabel: 'TASK' }),
        html: mk('html-element', { title: '页面', url: 'https://x' }, { icon: 'code', typeLabel: 'HTML' }),
      })) {
        out[k] = {
          qicon: ref.querySelectorAll('.nf-qicon').length,
          svg: ref.querySelectorAll('.att-ref-icon svg').length,
          iconHtml: ref.querySelector('.att-ref-icon').innerHTML.slice(0, 40),
        };
      }
      return out;
    });
    for (const k of Object.keys(others)) {
      expect(others[k].qicon, `${k} chip 不挂 .nf-qicon（改动只对 chat-message 生效）`).toBe(0);
      expect(others[k].svg, `${k} chip 仍走 chipIcon 的 svg 字形`).toBe(1);
    }
    expect(pageErrors).toEqual([]);
    await page.locator('#attachment-preview').screenshot({ path: path.join(SHOTS, 'quote-chip-icons.png') });
    await context.close();
  });
});

/* ═══════ ⑤ 引用块既有语义零回归（与图标并存） ═══════ */

test.describe('⑤ 引用块语义零回归', () => {
  test('图标在场时：text 仍只含「发送方：摘要」、可跳转 ready、两行 clamp 不破', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const userId = await page.evaluate(() => window.__renderUserRow('被引用的目标消息'));
    await page.evaluate(async id => window.__startQuoteOn(id), userId);
    const rid = await page.evaluate(() => window.__renderUserRowWithQuote('跳转断言', 'cm-shape').dataset.msgId);
    const shape = await page.evaluate(id => window.__quoteShape(id), rid);
    expect(shape.text, '摘要行 = 发送方：正文（图标不进文本）').toBe('我：被引用的目标消息');
    expect(shape.jump, '目标在场 ⇒ 可点').toBe('ready');
    expect(shape.role).toBe('button');
    const box = await page.evaluate(id => window.__quoteBlockShape(id), rid);
    expect(box.icon.kind, '文字引用 ⇒ logo').toBe('glyph');
    expect(box.textLines, '长摘要仍两行 clamp').toBeLessThanOrEqual(2);
    const jump = await page.evaluate(id => window.__jumpFromQuote(id), rid);
    expect(jump.flash, '跳转高亮仍落目标行').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
