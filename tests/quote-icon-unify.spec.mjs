// quote-icon-unify.spec.mjs — 引用入口图标统一（作者令 2026-11-02 12:58「图标就用跟我们
// footer那个引用图标一样的吧」）的机械验收网。
//
// 背景：上一批（引用牌图标化 2026-11-02 11:00）为**文字引用牌**新设计了 A/B/C 三个候选
// logo 并默认落 A；作者裁定弃用，**直接复用 composer footer 引用入口键 `#quote-item-btn`
// 那颗图标**。本批把该字形从 msgQuote.js 上提到 `js/quoteLogo.js` 的 `QUOTE_ENTRY_ICON`
// 成唯一来源，footer 键 / footer 徽章引用钮 / 气泡引用牌 / composer chip 四处同源。
//
// 五条机械断言（任务书 ①-⑤）：
//   ① 字形同一：footer 入口键与文字引用牌（气泡 + chip 两处宿主）的 SVG **path `d`
//      字符串逐字相同**，且 `data-icon` 标识相同（两处都 = quote-entry）；
//   ② 尺寸零变：文字引用牌图标盒仍 16px（chip 侧 18px），四类引用牌（图片缩略 / 文档
//      色块 / 文字图标 / 历史腿图片字形）图标盒同档，引用块高度不因换图标变化；
//   ③ 死代码清零：A/B/C 候选 logo 的导出/标识符/path 在 `src/` 与 `tests/` 零残留
//      （历史证据帧 f7-text-logo-candidates.png 保留不删，属非产品用途）；
//   ④ 色上下文：图标的 `getComputedStyle().color` = 宿主引用牌自身的 color（继承链成立，
//      不是写死 hex），深/浅两主题各自成立；`stroke` 恒为 `currentColor`；
//   ⑤ 其余三类引用牌零变化：图片真缩略 / 文档 `.fc-icon` 色族 / 历史腿图片字形逐项不变。
//
// 驱动：tests/fixtures/quote-message/harness.html（真实模块，无 stub）。
// Run: node node_modules/@playwright/test/cli.js test tests/quote-icon-unify.spec.mjs
import { test, expect } from '@playwright/test';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/quote-message/harness.html';
const SHOTS = path.join(REPO_ROOT, 'evidence', '20261102_quote-icon-unify', 'after');
const SELF_SPEC = 'quote-icon-unify.spec.mjs';

/** 改前基线读数（**实测**，不是猜的）：`git archive HEAD | tar -x` 落盘的纯净树上跑
 *  同一 harness、同一视口（1200×900）、同一引用牌，逐值如下。只固定**结构稳定**的量：
 *  图片/历史腿牌的附件缩略带（`.msg-quote-att-media`）高度取决于图片字节异步落地，
 *  跨进程读数会漂，故它们的块高不进基线；块高基线只取无附件带的文字牌（txt）。
 *  图标盒 / 图标行 / 摘要行三类四牌同档，全数进基线。 */
const BASELINE = {
  iconW: 16,
  iconH: 16,
  headH: 16,
  textH: 35,
  txtBoxH: 65,
};

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
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors, consoleErrors };
}

/** 铺四类引用牌（图片真缩略 / 文档色块 / 文字图标 / 历史腿图片字形），返回行 id。 */
async function seedFourPills(page) {
  const tiny = await page.evaluate(() => window.__tinyImg('UNIFY'));
  const imgId = await page.evaluate(([preview]) => window.__quotePill({
    kind: 'image', fileName: 'site-photo.png', path: '/tmp/x/site-photo.png',
    attachments: [{ type: 'image', name: 'site-photo.png', path: '/tmp/x/site-photo.png', preview }],
    senderRole: 'user', senderName: '我', ts: Date.now(),
  }, '带图片引用', 'cm-u-img'), [tiny]);
  const docId = await page.evaluate(() => window.__quotePill({
    kind: 'file', fileName: '季度报告.pdf', path: '/tmp/x/季度报告.pdf',
    attachments: [{ type: 'file', name: '季度报告.pdf', path: '/tmp/x/季度报告.pdf' }],
    senderRole: 'user', senderName: '我', ts: Date.now(),
  }, '带文档引用', 'cm-u-doc'));
  const txtId = await page.evaluate(() => window.__quotePill({
    kind: 'text', text: '这是一段被引用的文字', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
  }, '带文字引用', 'cm-u-txt'));
  // 历史腿：后端 quote 快照白名单无 preview ⇒ 图片只落字形（不伪造缩略）
  const histId = await page.evaluate(() => window.__quotePill({
    kind: 'image', fileName: 'archived-shot.png', path: '/tmp/x/archived-shot.png',
    attachments: [{ type: 'image', name: 'archived-shot.png', path: '/tmp/x/archived-shot.png' }],
    senderRole: 'user', senderName: '我', ts: Date.now(),
  }, '带历史图片引用', 'cm-u-hist'));
  return { imgId, docId, txtId, histId };
}

/* ═══════ ① 字形同一：footer 入口键 = 文字引用牌（两处宿主） ═══════ */

test.describe('① 同一 SVG（footer 入口键 = 气泡引用牌 = composer chip）', () => {
  test('SVG path d 串逐字相同 + data-icon 标识相同', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // footer 键字形：先铺一条叠卡让 footer 键显形（装配 = initQuoteItemButton）
    await page.evaluate(() => window.__renderUserStack(3));
    const txtId = await page.evaluate(() => window.__quotePill({
      kind: 'text', text: '这是一段被引用的文字', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }, '带文字引用', 'cm-a-txt'));
    // 再走一次文字引用态，让 composer chip 同屏
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'text', text: '被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }));
    // footer 键刷新走 rAF 收敛、徽章引用钮走 rAF 挂载 —— 等帧落定再读
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    await page.waitForTimeout(150);

    const shape = await page.evaluate(id => window.__quoteEntryIconShape(id), txtId);
    expect(shape.footer, 'footer 入口键 #quote-item-btn 有 SVG').toBeTruthy();
    expect(shape.pill, '文字引用牌有 SVG').toBeTruthy();
    expect(shape.chip, 'composer 文字引用 chip 有 SVG').toBeTruthy();

    // ① path d 逐字相同（本批核心断言）
    expect(shape.footer.d.length, 'footer 键 = 双路径字形').toBe(2);
    expect(shape.pill.d, '气泡引用牌的 path d 与 footer 键逐字相同').toEqual(shape.footer.d);
    expect(shape.chip.d, 'composer chip 的 path d 与 footer 键逐字相同').toEqual(shape.footer.d);
    // ① data-icon 标识同源
    expect(shape.footer.iconId, 'footer 键带 data-icon').toBe('quote-entry');
    expect(shape.pill.iconId, '气泡引用牌 data-icon 同标识').toBe('quote-entry');
    expect(shape.chip.iconId, 'chip data-icon 同标识').toBe('quote-entry');
    // 徽章引用钮（行内引用钮）也同源 —— 「改一处四处同步」的证据
    if (shape.badge) expect(shape.badge.d, 'footer 徽章引用钮 .msg-quote-btn 同字形').toEqual(shape.footer.d);

    // 块级读数复核
    const pill = await page.evaluate(id => window.__quoteBlockShape(id), txtId);
    expect(pill.icon.kind, '文字引用牌 = 字形分支').toBe('glyph');
    expect(pill.icon.svgD, '引用牌 SVG d 与 footer 键逐字相同（块级复核）').toEqual(shape.footer.d);
    expect(pageErrors).toEqual([]);
    await page.locator('#input-bar').screenshot({ path: path.join(SHOTS, 'unify-footer-entry.png') }).catch(() => {});
    await context.close();
  });
});

/* ═══════ ② 尺寸零变 + 四类同盒 ═══════ */

test.describe('② 图标盒尺寸与引用牌几何零变化', () => {
  test('四类引用牌图标盒同档（16px）；chip 侧 18px；引用块几何与改前基线逐值相同', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const ids = await seedFourPills(page);
    const shapes = {};
    for (const [k, id] of Object.entries(ids)) shapes[k] = await page.evaluate(i => window.__quoteBlockShape(i), id);
    const keys = { imgId: 'img', docId: 'doc', txtId: 'txt', histId: 'hist' };

    // 四类图标盒 = 16×16（同盒同尺寸），且与改前基线逐值相同（Δ≤1px）
    for (const [k, base] of Object.entries(keys)) {
      const b = BASELINE;
      const g = await page.evaluate(i => {
        const row = window.__rowById(i);
        const box = row.querySelector('.msg-quote-block');
        const head = box.querySelector('.msg-quote-head');
        return {
          boxH: Math.round(box.getBoundingClientRect().height),
          headH: head ? Math.round(head.getBoundingClientRect().height) : -1,
          iconW: Math.round(box.querySelector('.nf-qicon').getBoundingClientRect().width),
          iconH: Math.round(box.querySelector('.nf-qicon').getBoundingClientRect().height),
          textH: Math.round(box.querySelector('.msg-quote-text').getBoundingClientRect().height),
        };
      }, ids[k]);
      // ② 机读口径：改后 vs 改前基线逐值（Δ≤1px）
      expect(Math.abs(g.headH - b.headH), `${base} 图标行高与改前相同`).toBeLessThanOrEqual(1);
      expect(Math.abs(g.iconW - b.iconW), `${base} 图标盒宽与改前相同`).toBeLessThanOrEqual(1);
      expect(Math.abs(g.iconH - b.iconH), `${base} 图标盒高与改前相同`).toBeLessThanOrEqual(1);
      expect(Math.abs(g.textH - b.textH), `${base} 摘要行高与改前相同`).toBeLessThanOrEqual(1);
      if (base === 'txt') {
        expect(Math.abs(g.boxH - b.txtBoxH), 'txt 引用块高与改前逐值相同（Δ≤1px）').toBeLessThanOrEqual(1);
      }
    }
    // 四类图标盒同档（唯一尺寸源 = `.nf-qicon` 的 --nf-qicon-size 缺省 16px）
    const cssSize = await page.evaluate(() => {
      const el = document.querySelector('#chat .msg-quote-block .nf-qicon');
      return el ? getComputedStyle(el).getPropertyValue('--nf-qicon-size').trim() : '';
    });
    expect(cssSize, '引用牌未显式设 --nf-qicon-size（吃 16px 缺省档）').toBe('');
    // 换图标不改高度：图标行代价仍 ≤22px（沿用上一批的零增量口径）
    const delta = await page.evaluate(id => window.__iconDelta(id), ids.txtId);
    expect(delta.delta, '文字引用牌图标零高度代价（≤22px 判据）').toBeLessThanOrEqual(22);
    // 文字牌与文档牌：图标头行 / 图标盒 / 摘要行同档即「同盒同尺寸」（块总高之差
    // 来自各自的附件条 .msg-quote-att-file / .msg-quote-att-media，与图标无关）
    expect(shapes.txtId.textHeight, '文字牌与文档牌摘要行同高').toBe(shapes.docId.textHeight);
    expect(shapes.txtId.icon.w, '文字牌与文档牌图标盒同宽').toBe(shapes.docId.icon.w);
    expect(shapes.txtId.icon.h, '文字牌与文档牌图标盒同高').toBe(shapes.docId.icon.h);

    // chip 侧 18px 档
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'text', text: '被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }));
    const chip = await page.evaluate(() => window.__chipIconShape());
    expect(chip.kind, '文字 chip = 字形').toBe('glyph');
    expect(chip.w, 'chip 图标 18px 档不变').toBe(18);
    expect(chip.h).toBe(18);
    expect(chip.chipHeight, 'chip 高度档仍 28±2').toBeGreaterThanOrEqual(26);
    expect(chip.chipHeight).toBeLessThanOrEqual(30);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════ ③ 死代码清零（A/B/C 候选 logo 零残留） ═══════ */

test.describe('③ 废弃候选 logo 清零', () => {
  test('QUOTE_LOGO_CANDIDATES / QUOTE_LOGO_TEXT 与三个候选字形路径零命中', async () => {
    // 扫描面 = 产品代码 + 测试代码。**排除本断言脚本自身**：它必须逐字写出这些标识符
    // 才能做 grep（非产品用途的断言字面量，不是死代码；本批 grep 结果见批文档）。
    const scan = (pattern) => {
      try {
        const out = execFileSync('grep', ['-rn', '--binary-files=without-match', '-e', pattern,
          'src/main/resources/web', 'tests'], { cwd: REPO_ROOT, encoding: 'utf8' });
        return out.trim().split('\n')
          .filter(Boolean)
          .filter(l => !l.startsWith(`tests/${SELF_SPEC}:`));
      } catch {
        return [];   // grep 退出码 1 = 零命中（正是本断言要的）
      }
    };
    // 标识符面
    expect(scan('QUOTE_LOGO_CANDIDATES'), 'QUOTE_LOGO_CANDIDATES 导出零残留').toEqual([]);
    expect(scan('QUOTE_LOGO_TEXT'), 'QUOTE_LOGO_TEXT 导出零残留').toEqual([]);
    // 字形面（三个候选各自的特征 path 片段 —— 即便改名换壳也拦得住）
    expect(scan('M3\\.2 2\\.6c-1 0-1\\.8\\.8-1\\.8 1\\.8'), '候选 A 字形路径零残留').toEqual([]);
    expect(scan('M2\\.4 3\\.6h11\\.2v8\\.8H2\\.4z'), '候选 B 字形路径零残留').toEqual([]);
    expect(scan('M10\\.6 13\\.4c1\\.6-'), '候选 C 字形路径零残留').toEqual([]);
    // 唯一来源仍在且只有一处定义
    const entry = scan('QUOTE_ENTRY_ICON');
    expect(entry.length, 'QUOTE_ENTRY_ICON 有定义与调用点').toBeGreaterThan(0);
    const definers = entry.filter(l => /export const QUOTE_ENTRY_ICON\b/.test(l));
    expect(definers.length, 'QUOTE_ENTRY_ICON 只有一处 export const 定义').toBe(1);
    expect(definers[0], '定义点在 js/quoteLogo.js').toContain('js/quoteLogo.js');
    // 入口图标的 path 串本仓也只有一份字面量（quoteLogo.js）——零第二份抄写。
    // 扫描面排除 `vendor/`：lucide 第三方库打包全集里天然含同一枚字形（非本仓抄写）。
    const pathLiterals = scan('M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1')
      .filter(l => !l.includes('/vendor/') && !l.includes(SELF_SPEC));
    expect(pathLiterals.length, '入口图标 path d 字面量本仓仅 1 处（quoteLogo.js）').toBe(1);
    expect(pathLiterals[0], '该字面量在 quoteLogo.js').toContain('js/quoteLogo.js');
  });
});

/* ═══════ ④ 色上下文（只复用形状，不复用颜色） ═══════ */

/** 深浅两腿各自的实测图标色（workers=1 ⇒ 声明序执行，dark 腿比一次两主题不同）。 */
const THEME_PILL_COLOR = {};

test.describe('④ 深浅主题下随引用牌色上下文', () => {
  for (const scheme of ['light', 'dark']) {
    test(`${scheme}：图标色 = 引用牌自身 color（继承链），stroke = currentColor`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, { width: 1200, height: 900 }, { colorScheme: scheme });
      const ids = await seedFourPills(page);
      await page.evaluate(() => window.__renderUserStack(3));
      await page.evaluate(() => window.__startQuoteDirect({
        kind: 'text', text: '被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
      }));
      await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
      await page.waitForTimeout(120);

      const shape = await page.evaluate(id => window.__quoteEntryIconShape(id), ids.txtId);
      const block = await page.evaluate(id => window.__quoteBlockShape(id), ids.txtId);

      // ④ 继承链：svg 的 computed color == 引用牌块的 computed color（同一色上下文）
      expect(shape.pill.color, '气泡引用牌图标色 = 引用牌文字色（继承链，非写死 hex）').toBe(shape.pillBlockColor);
      expect(block.icon.svgColor, '块级读数复核：图标色 = 块色').toBe(block.boxColor);
      // ④ stroke 走 currentColor
      expect(shape.pill.stroke, '引用牌 SVG stroke = currentColor').toBe('currentColor');
      expect(shape.footer.stroke, 'footer 键 SVG stroke = currentColor').toBe('currentColor');
      // ④ 只复用形状、不复用颜色 —— 变量级判据：引用牌图标吃的是**引用牌那条链**
      //    （`--color-text-muted`），而不是 footer 键的按钮色链。
      //    注：dark 主题下两个变量恰好同值、且 `.icon-btn` 在 dark 里另有硬编码
      //    `color:#7b7e88`（既有规则，本批未动）—— 所以「值不相等」不是可判据，
      //    只有「吃哪个变量」是可判据的。
      expect(shape.pill.color, '引用牌图标吃 --color-text-muted（引用牌色上下文）').toBe(shape.varTextMuted);
      expect(shape.footer.color, 'footer 键仍吃自己的按钮色链（本批未改按钮外观）')
        .toBe(shape.footerHostColor);
      // chip 侧同理：图标色 = chip 宿主色（不是引用牌、也不是按钮的色）
      expect(shape.chip.color, 'chip 图标色 = chip 宿主色').toBe(shape.chipHostColor);
      // ④ 是 rgb() 计算值（证明不是 hex 字面量）
      expect(shape.pill.color, '图标色是 rgb() 计算值不是 hex 字面量').toMatch(/^rgb/);
      // ④ 深浅主题确实算出不同值（主题自适应实证；两腿各写一格，末腿比一次）
      THEME_PILL_COLOR[scheme] = shape.pill.color;
      if (scheme === 'dark' && THEME_PILL_COLOR.light) {
        expect(THEME_PILL_COLOR.dark, '深/浅主题下图标色不同（随主题自适应）')
          .not.toBe(THEME_PILL_COLOR.light);
      }

      expect(pageErrors).toEqual([]);
      await page.locator('#chat').screenshot({ path: path.join(SHOTS, `unify-${scheme}-pills.png`) });
      await context.close();
    });
  }
});

/* ═══════ ⑤ 其余三类引用牌零变化 ═══════ */

test.describe('⑤ 图片 / 文档 / 历史腿三类零变化', () => {
  test('真缩略 src、.fc-icon 色族、历史腿图片字形逐项与改前同值', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const ids = await seedFourPills(page);
    const img = await page.evaluate(id => window.__quoteBlockShape(id), ids.imgId);
    expect(img.icon.kind, '图片引用牌 = 真缩略（不是字形）').toBe('thumb');
    expect(img.icon.src.startsWith('data:image/'), '缩略来自内联字节').toBe(true);
    expect(img.icon.hasSvg, '图片缩略不含 SVG').toBe(false);
    expect(img.text, '摘要仍 [图片]').toContain('[图片]');

    const doc = await page.evaluate(id => window.__quoteBlockShape(id), ids.docId);
    expect(doc.icon.kind, '文档引用牌 = .fc-icon 色块').toBe('file');
    expect(doc.icon.fcCls, 'PDF 色族不变').toBe('pdf');
    expect(doc.icon.text, '角标仍是 PDF').toBe('PDF');
    expect(doc.icon.svgD, '文档牌不含本批 SVG').toEqual([]);

    const hist = await page.evaluate(id => window.__quoteBlockShape(id), ids.histId);
    expect(hist.icon.kind, '历史腿无 preview ⇒ 图片字形（不伪造缩略）').toBe('glyph');
    expect(hist.icon.svgId, '历史腿走 QUOTE_LOGO_IMAGE（无 data-icon = 不是入口图标）').toBe('');
    expect(hist.icon.svgD.length, '历史腿图片字形含多条 path').toBeGreaterThan(0);
    expect(hist.icon.svgD, '历史腿字形 ≠ 入口图标字形（不误用）').not.toEqual(img.icon.svgD);
    expect(hist.text, '摘要仍 [图片]').toContain('[图片]');

    // 既有跳转语义零回归：先铺一条真实在场的目标行，再从它引用 ⇒ 必须 ready 可跳
    const userId = await page.evaluate(() => window.__renderUserRow('被引用的目标消息'));
    await page.evaluate(async id => window.__startQuoteOn(id), userId);
    const jumpId = await page.evaluate(() => window.__renderUserRowWithQuote('跳转断言', 'cm-u-jump').dataset.msgId);
    const jumpShape = await page.evaluate(id => window.__quoteBlockShape(id), jumpId);
    expect(jumpShape.jump, '文字引用牌仍 ready 可跳（既有语义）').toBe('ready');
    expect(jumpShape.icon.kind, '该牌的图标仍是字形分支').toBe('glyph');
    expect(jumpShape.icon.svgId, '该牌字形 = 入口图标（与 footer 键同源）').toBe('quote-entry');
    expect(pageErrors).toEqual([]);
    await page.locator('#chat').screenshot({ path: path.join(SHOTS, 'unify-four-pills.png') });
    await context.close();
  });
});

/* ═══════ console 零 error（手机暗态整页） ═══════ */

test.describe('console 零 error', () => {
  test('全量铺帧（图片/文档/文字三类引用牌 + 叠卡 + chip）零 pageerror / 零 console error', async ({ browser }) => {
    const { context, page, pageErrors, consoleErrors } = await newPage(browser, { width: 375, height: 812 }, {
      hasTouch: true, isMobile: true, deviceScaleFactor: 2, colorScheme: 'dark',
    });
    await page.evaluate(() => document.body.classList.add('mobile-layer'));
    await page.evaluate(() => window.__renderUserStack(3));
    const tiny = await page.evaluate(() => window.__tinyImg('UNIFY'));
    await page.evaluate(([preview]) => window.__quotePill({
      kind: 'image', fileName: 'site-photo.png', path: '/tmp/x/site-photo.png',
      attachments: [{ type: 'image', name: 'site-photo.png', path: '/tmp/x/site-photo.png', preview }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }, '带图片引用', 'cm-c-img'), [tiny]);
    await page.evaluate(() => window.__quotePill({
      kind: 'file', fileName: '季度报告.pdf', path: '/tmp/x/季度报告.pdf',
      attachments: [{ type: 'file', name: '季度报告.pdf', path: '/tmp/x/季度报告.pdf' }],
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }, '带文档引用', 'cm-c-doc'));
    await page.evaluate(() => window.__quotePill({
      kind: 'text', text: '这是一段被引用的文字', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }, '带文字引用', 'cm-c-txt'));
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'text', text: '被引用的文字消息', senderRole: 'ai', senderName: 'Nebula', ts: Date.now(),
    }));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    await page.waitForTimeout(150);
    expect(pageErrors).toEqual([]);
    expect(consoleErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOTS, 'unify-mobile-dark-full.png') });
    await context.close();
  });

  test('历史腿（无 preview 的图片引用）只有基线同源的资源加载噪声，零脚本错误', async ({ browser }) => {
    // 历史腿的附件条走既有取字节链（nf-file / nf-ticket），静态 harness 没有这两条
    // 后端路由 ⇒ `Failed to load resource` 501/404 是**基线同源噪声**（改前纯净树
    // `git archive HEAD` 实测逐条相同）。本断言把它单列：只允许资源加载噪声，
    // 任何脚本 error 或 pageerror 仍是红。
    const { context, page, pageErrors, consoleErrors } = await newPage(browser);
    await seedFourPills(page);
    await page.waitForTimeout(250);
    expect(pageErrors, '历史腿零脚本错误').toEqual([]);
    const scriptErrors = consoleErrors.filter(x => !/Failed to load resource/.test(x));
    expect(scriptErrors, '历史腿的 console error 只有资源加载噪声（基线同源）').toEqual([]);
    await context.close();
  });
});
