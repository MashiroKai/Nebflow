// quote-media-stack.spec.mjs — 引用牌「多图叠卡」门禁（作者令 2026-11-05 16:25
// 逐字「引用多张图片的时候，也走叠卡」）。
//
// 改前事实（本 spec 的判据来源，实读见 reports-evidence/<批>/）：
//   · 被引消息的图片组渲染腿 = msgQuote.renderQuoteAttStrip（引用块 .msg-quote-atts）
//     —— 改前**一律** 48px 方缩略逐张平铺（flex-wrap），张数一多就换行、块高随张数
//     线性长；没有任何叠卡形态。
//   · 叠卡实现早就存在且是单点：popArtifacts.buildMediaStack（Pop 多图交付卡 /
//     用户媒体行 / 好友三窗共用）。本批只把它接到引用牌上，零第二套视觉。
//
// 判据（机械）：
//   R1 媒体 ≥2 件 ⇒ 引用块出现叠卡（.pop-stack-area + .pill「展开 N」），
//      不再出现逐张平铺的 .msg-quote-att-media；
//   R2 媒体 = 1 件 ⇒ 保持原样（.msg-quote-att-media 48×48，无 .pop-stack-area）；
//   R3 高度收敛：3 张与 6 张的引用块高度相等（±2px），不随张数线性增长；
//   R4 切图可用（桌面键盘腿）：聚焦卡脸 → ArrowRight/ArrowLeft 换前卡，
//      front 层换人、pill 文案不变；
//   R5 「展开 N」摊平：点 pill ⇒ .pop-expand-view.show 且每张一行（行数 = 张数）、
//      pill 文案变「收起」；再点回叠卡；
//   R6 点图不丢开件：点折叠卡脸 ⇒ 进灯箱（无 path 的纯内联字节腿 =
//      .nf-lightbox--media.on），且**不**触发引用块自身的跳转（无 .quote-target-flash）；
//   R7 文件 chip 与图片共存时两不相干（多图走叠卡，文件仍走 .msg-quote-att-file）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/quote-media-stack.spec.mjs
//   env：CHATFACE_WEB_ROOT=<web 树> 跑改前腿（预期红）；EVID_DIR=<dir> 落读数。

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat } from './_chat-face-harness.mjs';

const EVID = process.env.EVID_DIR || null;
const TAG = process.env.EVID_TAG || 'after';

function svgData(w, h, c1, c2) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">`
    + `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">`
    + `<stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs>`
    + `<rect width="100%" height="100%" fill="url(#g)"/></svg>`;
  return 'data:image/svg+xml;base64,' + Buffer.from(svg).toString('base64');
}

const RATIOS = [[1000, 800], [1000, 620], [1000, 1100], [1000, 500], [800, 1000], [1200, 900]];
const COLS = [['#221133', '#ee4466'], ['#123322', '#44ee88'], ['#112244', '#4488ee'],
  ['#332211', '#eeaa44'], ['#221133', '#aa44ee'], ['#113322', '#44eebb']];

/** n 张图附件（无 path ⇒ 纯内联字节腿，点开进灯箱，不进 Canvas）。 */
function atts(n) {
  return Array.from({ length: n }, (_, i) => ({
    type: 'image',
    name: `q${i + 1}.svg`,
    preview: svgData(RATIOS[i][0], RATIOS[i][1], COLS[i][0], COLS[i][1]),
  }));
}

const FILE_ATT = { type: 'file', name: 'brief.md', path: '/uploads/chat-face-geom-session/brief.md' };

const history = [
  {
    type: 'user', text: '嗯 对 这几张喂去做一下 @living-ascii 引用三张', timestamp: Date.now() - 60_000,
    quote: {
      refMessageId: 'gone-3', excerpt: '嗯 对 这几张喂去做一下 @living-ascii',
      senderRole: 'user', senderName: '我', ts: Date.now() - 300_000, attachments: atts(3),
    },
  },
  {
    type: 'user', text: '嗯 对 这几张喂去做一下 @living-ascii 引用六张', timestamp: Date.now() - 50_000,
    quote: {
      refMessageId: 'gone-6', excerpt: '嗯 对 这几张喂去做一下 @living-ascii', senderRole: 'user', senderName: '我',
      ts: Date.now() - 290_000, attachments: atts(6),
    },
  },
  {
    type: 'user', text: '引用一张', timestamp: Date.now() - 40_000,
    quote: {
      refMessageId: 'gone-1', excerpt: '单图原样', senderRole: 'user', senderName: '我',
      ts: Date.now() - 280_000, attachments: atts(1),
    },
  },
  {
    type: 'user', text: '引用两图加文件', timestamp: Date.now() - 30_000,
    quote: {
      refMessageId: 'gone-mix', excerpt: '图 + 文件混装', senderRole: 'user', senderName: '我',
      ts: Date.now() - 270_000, attachments: [...atts(2), FILE_ATT],
    },
  },
];

/** 按引用牌所在行的正文锁到某个引用块。 */
function quoteBlockOf(page, text) {
  return page.locator('#chat .row.user', { hasText: text }).first().locator('.msg-quote-block');
}

async function measure(page, text) {
  return page.evaluate((t) => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    const row = rows.find(r => (r.textContent || '').includes(t) && r.querySelector('.msg-quote-block'));
    if (!row) return null;
    const box = row.querySelector('.msg-quote-block');
    const rect = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { w: +b.width.toFixed(2), h: +b.height.toFixed(2) };
    };
    const stack = box.querySelector('.pop-stack-area');
    const pill = box.querySelector('.pill');
    const view = box.querySelector('.pop-stack-view');
    const layers = [...box.querySelectorAll('.pop-stack-view .media-layer')];
    const front = layers.filter(l => l.classList.contains('layer-front'));
    const visible = layers.filter(l => !l.classList.contains('is-buried')
      && getComputedStyle(l).display !== 'none');
    const expand = box.querySelector('.pop-expand-view');
    const expandRows = expand
      ? [...expand.querySelectorAll('.media-layer')].filter(l => l.getBoundingClientRect().height > 0)
      : [];
    return {
      block: rect(box),
      blockShareOfViewport: +(box.getBoundingClientRect().height / window.innerHeight * 100).toFixed(1),
      stack: rect(stack),
      face: rect(view),
      pillText: pill ? pill.textContent.trim() : '',
      layerCount: layers.length,
      frontIndex: front.length ? layers.indexOf(front[0]) : -1,
      visibleLayers: visible.length,
      expandShown: !!(expand && expand.classList.contains('show')),
      expandRows: expandRows.length,
      expandRowHeights: expandRows.map(l => +l.getBoundingClientRect().height.toFixed(2)),
      expandRowWidths: expandRows.map(l => +l.getBoundingClientRect().width.toFixed(2)),
      thumbs: box.querySelectorAll('.msg-quote-att-media').length,
      fileChips: box.querySelectorAll('.msg-quote-att-file').length,
    };
  }, text);
}

function saveReadings(name, payload) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${TAG}-${name}.json`), JSON.stringify(payload, null, 2));
}

test.describe('引用牌多图叠卡', () => {
  for (const vp of [{ width: 1440, height: 900 }, { width: 375, height: 812 }]) {
    const tag = vp.width >= 1000 ? 'desktop' : 'mobile';

    test(`${tag} ${vp.width}×${vp.height}：≥2 张走叠卡 / 1 张原样 / 高度收敛 / 切换与展开 / 点图进灯箱`, async ({ browser }) => {
      const ctx = await browser.newContext({
        viewport: vp, deviceScaleFactor: 2, hasTouch: vp.width < 1000,
      });
      const page = await ctx.newPage();
      await bootChat(page, { history });
      await page.waitForTimeout(700);

      // ── R1：≥2 张 = 叠卡 ─────────────────────────────────────────────
      const three = await measure(page, '引用三张');
      expect(three, '三图引用块未渲染').not.toBeNull();
      expect(three.stack, '三图引用块缺叠卡区 .pop-stack-area').not.toBeNull();
      expect(three.face, '叠卡卡脸缺失').not.toBeNull();
      expect(three.pillText).toContain('展开');
      expect(three.pillText).toContain('3');
      expect(three.thumbs, '走叠卡后不应再有逐张平铺缩略').toBe(0);
      expect(three.layerCount).toBe(3);
      // 前卡完整可见：卡脸 = 宿主收口尺度（96×122 ≈ 11:14 预览档），且不横向顶出引用块
      expect(Math.round(three.face.w)).toBe(96);
      expect(Math.round(three.face.h / three.face.w * 11)).toBe(14);
      expect(three.face.w).toBeLessThanOrEqual(three.block.w + 0.5);

      // ── R3：高度收敛（3 张 vs 6 张）──────────────────────────────────
      const six = await measure(page, '引用六张');
      expect(six.stack).not.toBeNull();
      expect(six.thumbs).toBe(0);
      expect(Math.abs(six.block.h - three.block.h)).toBeLessThanOrEqual(2);

      // ── R2：单张保持原样 ────────────────────────────────────────────
      const one = await measure(page, '引用一张');
      expect(one.thumbs, '单图必须保持 48px 缩略原样').toBe(1);
      expect(one.stack, '单图不得出现叠卡').toBeNull();
      const oneThumb = await quoteBlockOf(page, '引用一张').locator('.msg-quote-att-media').first()
        .evaluate(el => {
          const b = el.getBoundingClientRect();
          return { w: Math.round(b.width), h: Math.round(b.height) };
        });
      expect(oneThumb).toEqual({ w: 48, h: 48 });

      // ── R7：图 + 文件混装 ──────────────────────────────────────────
      const mix = await measure(page, '引用两图加文件');
      expect(mix.stack, '两图 + 文件 ⇒ 图片组走叠卡').not.toBeNull();
      expect(mix.thumbs).toBe(0);
      expect(mix.fileChips, '文件 chip 不受叠卡影响').toBe(1);

      saveReadings(tag, { viewport: vp, three, six, one, mix });

      // ── R4：切图（键盘腿；手机档用 touch 横滑不在此 spec 做，见 R6 注释）────
      const block3 = quoteBlockOf(page, '引用三张');
      const view3 = block3.locator('.pop-stack-view');
      await view3.focus();
      const f0 = (await measure(page, '引用三张')).frontIndex;
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(120);
      const f1 = (await measure(page, '引用三张')).frontIndex;
      expect(f1, 'ArrowRight 应把下一张顶到 front').toBe((f0 + 1) % 3);
      await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(120);
      expect((await measure(page, '引用三张')).frontIndex).toBe(f0);
      // 换前卡后 pill 文案不变（仍是「展开 N」）
      expect((await measure(page, '引用三张')).pillText).toContain('展开');

      // ── R4b：手机档真触屏横滑翻页（CDP touch → pointer 事件链）──────────
      if (vp.width < 1000) {
        const box = await view3.boundingBox();
        const cx = Math.round(box.x + box.width / 2);
        const cy = Math.round(box.y + box.height / 2);
        const cdp = await ctx.newCDPSession(page);
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: cx, y: cy }] });
        for (let i = 1; i <= 6; i++) {
          await cdp.send('Input.dispatchTouchEvent', {
            type: 'touchMove', touchPoints: [{ x: cx - i * 15, y: cy }],
          });
        }
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
        await page.waitForTimeout(200);
        const afterSwipe = await measure(page, '引用三张');
        expect(afterSwipe.frontIndex, '真触屏左滑应把下一张顶到 front').not.toBe(f0);
        await cdp.detach();
      }

      // ── R5：展开 N 摊平 ────────────────────────────────────────────
      await block3.locator('.pill').click();
      await page.waitForTimeout(650);
      const expanded = await measure(page, '引用三张');
      expect(expanded.expandShown, '点「展开 N」后展开列必须上屏').toBe(true);
      expect(expanded.expandRows, '展开态 = 一张一行').toBe(3);
      expect(expanded.pillText).toContain('收起');
      const distinctHeights = new Set(expanded.expandRowHeights);
      expect(distinctHeights.size, '展开态每行按自身比例（行高不全相同）').toBeGreaterThan(1);
      // 展开行宽 = 折叠卡脸宽（宿主尺度同源）：不横向顶出引用块 / 视口
      for (const w of expanded.expandRowWidths) expect(w).toBeLessThanOrEqual(three.block.w + 0.5);
      await block3.locator('.pill').click();
      await page.waitForTimeout(650);
      const recollapsed = await measure(page, '引用三张');
      expect(recollapsed.expandShown).toBe(false);
      expect(recollapsed.pillText).toContain('展开');

      // ── R6：点图进灯箱 + 不误触发引用跳转 ───────────────────────────
      await block3.locator('.pop-stack-view').click({ position: { x: 30, y: 30 } });
      await page.waitForTimeout(400);
      const opened = await page.evaluate(() => ({
        lightbox: !!document.querySelector('.nf-lightbox.nf-lightbox--media.on'),
        jumped: !!document.querySelector('.quote-target-flash'),
      }));
      expect(opened.lightbox, '点折叠卡脸必须仍能进灯箱看大图').toBe(true);
      expect(opened.jumped, '开件不得升级成「跳回原消息」').toBe(false);
      if (EVID) await page.screenshot({ path: join(EVID, `${TAG}-${vp.width}-lightbox.png`) });
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);

      if (EVID) {
        await page.evaluate(() => { const c = document.getElementById('chat'); if (c) c.scrollTop = 0; });
        await page.waitForTimeout(150);
        await page.screenshot({ path: join(EVID, `${TAG}-${vp.width}-stack.png`), fullPage: false });
        await block3.locator('.pill').click();
        await page.waitForTimeout(700);
        await page.screenshot({ path: join(EVID, `${TAG}-${vp.width}-expanded.png`), fullPage: false });
        await block3.locator('.pill').click();
        await page.waitForTimeout(500);
      }
      await ctx.close();
    });
  }
});
