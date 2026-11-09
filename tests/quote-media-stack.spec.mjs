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
// 新验收判据（作者令：引用内容统一紧凑缩略，不可展开/大图）：
//   R1 媒体 ≥2 件 ⇒ 每件一枚 .msg-quote-att-media 48×48，无 .pop-stack-area/.pill；
//   R2 媒体 = 1 件 ⇒ 保持单缩略原样（48×48）；
//   R3 多图引用块无展开交互或大图占位，缩略数量 = 附件数；
//   R4 文件 chip 与图片共存时各自紧凑展示（文件仍走 .msg-quote-att-file）。
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

    test(`${tag} ${vp.width}×${vp.height}：多媒体引用为紧凑缩略 / 单图样式不变 / 无展开和大图`, async ({ browser }) => {
      const ctx = await browser.newContext({
        viewport: vp, deviceScaleFactor: 2, hasTouch: vp.width < 1000,
      });
      const page = await ctx.newPage();
      await bootChat(page, { history });
      await page.waitForTimeout(700);

      const three = await measure(page, '引用三张');
      expect(three, '三图引用块未渲染').not.toBeNull();
      expect(three.thumbs, '三图应逐项缩略').toBe(3);
      expect(three.stack, '引用块不得出现叠卡').toBeNull();
      expect(three.pillText, '无展开控件').toBe('');
      const threeSizes = await quoteBlockOf(page, '引用三张').locator('.msg-quote-att-media').evaluateAll(els => els.map(el => {
        const b = el.getBoundingClientRect(); return [Math.round(b.width), Math.round(b.height)];
      }));
      expect(threeSizes).toEqual([[48, 48], [48, 48], [48, 48]]);
      expect(await quoteBlockOf(page, '引用三张').locator('.nf-lightbox, .pop-expand-view, .pill').count()).toBe(0);

      const six = await measure(page, '引用六张');
      expect(six.thumbs).toBe(6);
      expect(six.stack).toBeNull();

      const one = await measure(page, '引用一张');
      expect(one.thumbs).toBe(1);
      expect(one.stack).toBeNull();
      const oneThumb = await quoteBlockOf(page, '引用一张').locator('.msg-quote-att-media').first()
        .evaluate(el => { const b = el.getBoundingClientRect(); return [Math.round(b.width), Math.round(b.height)]; });
      expect(oneThumb).toEqual([48, 48]);

      const mix = await measure(page, '引用两图加文件');
      expect(mix.thumbs).toBe(2);
      expect(mix.stack).toBeNull();
      expect(mix.fileChips).toBe(1);
      expect(await quoteBlockOf(page, '引用两图加文件').locator('.nf-lightbox, .pop-expand-view, .pill').count()).toBe(0);
      saveReadings(tag, { viewport: vp, three, six, one, mix });

      if (EVID) await page.screenshot({ path: join(EVID, `${TAG}-${vp.width}-quote-thumbnails.png`), fullPage: false });
      await ctx.close();
    });
  }
});
